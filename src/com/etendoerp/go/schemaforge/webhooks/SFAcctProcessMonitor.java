/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.webhooks;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.model.ad.ui.ProcessRun;
import org.openbravo.scheduling.OBScheduler;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessContext;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * ETP-5269 — read the accounting server process's current status / execution history, and let an
 * administrator launch it manually, without waiting for its next automatic run.
 *
 * <p>Two actions on one endpoint, selected by the {@code Action} parameter, mirroring {@code
 * SFDebugInvitationBypass}'s two-action shape. The default is the READ action, so a bare
 * {@code GET /sws/neo/acctprocessmonitor} can never fire anything — only an explicit
 * {@code ?Action=trigger} does. That default matters because the whole Etendo GO webhook family is
 * reached over GET (the {@code BaseWebhookService.get(Map, Map)} contract the {@code
 * NeoGoWebhookBridge} bridges); making the side-effecting action opt-in is what keeps an
 * accidental, prefetched or retried GET harmless.</p>
 *
 * <h2>Why a one-shot sibling request, and not the recurring one</h2>
 *
 * <p>The accounting process ({@code AD_Process.Value = 'AcctServerProcess'}) already runs on a
 * recurring {@link ProcessRequest} — every 5 minutes on the reference instance. That recurring row
 * is <b>never read for anything but its identity, and never written</b>. Triggering a manual run
 * inserts a brand-new, separate one-shot {@code AD_PROCESS_REQUEST} pointing at the same
 * {@code AD_Process}, via {@link OBScheduler#schedule(ProcessBundle)} — the overload whose javadoc
 * is literally "Schedule a new process (bundle) to run immediately in the background, using a
 * random name for the Quartz's JobDetail". It mints its own id, INSERTs its own row with status
 * {@code SCH} and NULL timing columns, and schedules it; {@code TriggerProvider} maps a null/
 * unrecognized timing option to {@code TimingOption.IMMEDIATE}, whose generator is
 * {@code newTrigger().startNow()}. So the run starts now and the recurring cadence is untouched by
 * construction — there is no code path here that can reach the recurring row's schedule.</p>
 *
 * <p>Two alternatives were investigated and rejected:</p>
 * <ul>
 *   <li><b>Invoking the "Schedule Process" AD_Process</b> ({@code 0515E6559C31478E92703A3D10E6783B}).
 *       It has {@code UIPattern = 'M'} and an EMPTY {@code Classname} — it is the manual button on
 *       the Process Request window, which rewrites the schedule of the request row it is run
 *       against. Using it would mutate the recurring row's Quartz job.</li>
 *   <li><b>Updating {@code start_date}/{@code start_time} on the recurring row.</b> Refuted from
 *       source: {@code OBScheduler.initialize()} reads {@code AD_PROCESS_REQUEST} exactly ONCE, at
 *       Quartz startup. Afterwards triggers live in Quartz's own JobStore and nothing re-reads that
 *       table for timing, so the UPDATE would be invisible until Tomcat restarted — while still
 *       having corrupted the recurring row's stored schedule.</li>
 * </ul>
 *
 * <p>The one-shot row is also restart-safe. It carries {@code Channel.DIRECT} ("Direct"), and
 * {@code OBScheduler.initialize()} explicitly marks any still-{@code SCH} "Direct" request as
 * {@code SYR} (System Restart) and skips rescheduling it. A one-shot can therefore never silently
 * become a second recurring job, however Tomcat stops.</p>
 *
 * <p>The manual run reuses the recurring request's own stored {@code ob_context} as the run
 * identity, so it executes as exactly the same user/client/org the automatic run does. This is
 * deliberate: the point of the feature is "do the scheduled run now", not "do a different run".
 * It also means the endpoint refuses to trigger when no recurring request exists — there is then no
 * established identity to borrow, and inventing one is not this endpoint's call to make.</p>
 *
 * <h2>Access</h2>
 *
 * <p>Both actions are gated on {@link NeoAccessHelper#isAdminOrClientAdmin(Role)}, enforced
 * server-side. Any other caller (including one with no role) gets the {@code notAuthorized} payload
 * — the frontend flag is visual gating only and is never trusted here. Denial mirrors the
 * "answer, don't 403" convention the rest of this webhook family uses.</p>
 *
 * <h2>What is deliberately NOT exposed</h2>
 *
 * <p>{@code AD_PROCESS_RUN.LOG} (a CLOB of raw process output) and {@code REPORT} are never read
 * into the response — not in the list, not truncated, not behind a drill-down. The page shows
 * status, timings and duration only. Keep it that way: the log can carry arbitrary internal detail
 * and this endpoint is reachable by every client-admin.</p>
 */
public class SFAcctProcessMonitor extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFAcctProcessMonitor.class);

  /**
   * Search key of the accounting server process. Resolved by search key rather than by its
   * {@code AD_Process_ID} so no id is hardcoded; on the reference instance this is {@code 800064}
   * ({@code org.openbravo.erpCommon.ad_process.AcctServerProcess}).
   */
  static final String ACCT_PROCESS_SEARCH_KEY = "AcctServerProcess";

  private static final String PARAM_ACTION = "Action";
  private static final String PARAM_LIMIT = "Limit";
  private static final String ACTION_TRIGGER = "trigger";

  private static final int DEFAULT_HISTORY_LIMIT = 20;
  private static final int MAX_HISTORY_LIMIT = 100;

  private static final String RESPONSE_VAR_RESULT = "result";
  private static final String FIELD_ERROR = "error";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_REASON = "reason";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_TRIGGERED = "triggered";
  private static final String FIELD_HISTORY = "history";

  /**
   * How long a {@code PRC} run (or an unfired one-shot request) is believed before it is treated
   * as an orphan left behind by a killed JVM. See {@link #hasRunInProgress}.
   */
  private static final long STALE_RUN_MS = 60L * 60L * 1000L;

  /** Machine-readable {@code reason} values on an error payload; see {@link #errorPayload}. */
  private static final String REASON_NOT_AUTHORIZED = "notAuthorized";
  private static final String REASON_NOT_INSTALLED = "notInstalled";

  /** {@code AD_PROCESS_REQUEST.CHANNEL} written by a manual one-shot; see class javadoc. */
  private static final String CHANNEL_DIRECT = ProcessBundle.Channel.DIRECT.toString();

  private static final String STATUS_SCHEDULED = org.openbravo.scheduling.Process.SCHEDULED;
  private static final String STATUS_PROCESSING = org.openbravo.scheduling.Process.PROCESSING;

  /** ISO-8601 without a zone: these are wall-clock server timestamps, formatted by the browser. */
  private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    Role currentRole = NeoAccessHelper.resolveCurrentRole();
    if (currentRole == null || !NeoAccessHelper.isAdminOrClientAdmin(currentRole)) {
      responseVars.put(RESPONSE_VAR_RESULT, notAuthorized().toString());
      return;
    }

    boolean trigger = ACTION_TRIGGER
        .equalsIgnoreCase(StringUtils.trimToEmpty(parameter.get(PARAM_ACTION)));

    // Admin mode: the accounting process's recurring request is a System (AD_Client_ID = '0') row,
    // which a tenant admin's own context cannot read. Every criteria below additionally disables
    // readable-client/org filtering, matching every sibling webhook in this package.
    OBContext.setAdminMode(true);
    try {
      Process process = resolveAcctProcess();
      if (process == null) {
        responseVars.put(RESPONSE_VAR_RESULT, errorPayload(REASON_NOT_INSTALLED,
            "The accounting server process is not installed on this instance.").toString());
        return;
      }

      int limit = historyLimit(parameter);
      ProcessRequest recurring = findRecurringRequest(process);
      JSONObject result = buildStatus(process, recurring, limit);
      if (trigger) {
        result.put(FIELD_TRIGGERED, triggerManualRun(process, recurring));
        // Re-read the history so the freshly created run is already visible in the response that
        // the "Run now" click renders, rather than only after the next poll.
        result.put(FIELD_HISTORY, buildHistory(process, limit));
      }
      responseVars.put(RESPONSE_VAR_RESULT, result.toString());
    } catch (JSONException e) {
      log.error("Error building SFAcctProcessMonitor response", e);
      responseVars.put(FIELD_ERROR, e.getMessage());
    } catch (RuntimeException e) {
      log.error("Unexpected error in SFAcctProcessMonitor", e);
      responseVars.put(FIELD_ERROR, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Read side
  // ---------------------------------------------------------------------------------------------

  private JSONObject buildStatus(Process process, ProcessRequest recurring, int limit)
      throws JSONException {
    JSONObject result = new JSONObject();
    result.put(FIELD_ERROR, false);
    result.put("processName", process.getName());
    result.put("scheduled", recurring != null);
    result.put("nextRunTime",
        recurring == null ? JSONObject.NULL : formatTimestamp(recurring.getNextExecution()));

    JSONArray history = buildHistory(process, limit);
    result.put(FIELD_HISTORY, history);
    result.put("lastRun", history.length() == 0 ? JSONObject.NULL : history.get(0));
    result.put("running", hasRunInProgress(process));
    return result;
  }

  /**
   * History is resolved at the PROCESS level — every {@link ProcessRun} of every
   * {@link ProcessRequest} for this process — not just the recurring request's own runs. A manual
   * run lives on its own one-shot request (see class javadoc), so filtering by the recurring
   * request id would hide from this page exactly the runs it just started.
   */
  private JSONArray buildHistory(Process process, int limit) throws JSONException {
    JSONArray history = new JSONArray();
    for (ProcessRun run : findRuns(process, limit)) {
      history.put(toRunJson(run));
    }
    return history;
  }

  private JSONObject toRunJson(ProcessRun run) throws JSONException {
    JSONObject json = new JSONObject();
    json.put("id", run.getId());
    json.put(FIELD_STATUS, run.getStatus());
    json.put("startTime", formatTimestamp(run.getStartTime()));
    json.put("endTime", formatTimestamp(run.getEndTime()));
    json.put("duration", StringUtils.trimToNull(run.getDuration()) == null
        ? JSONObject.NULL : run.getDuration());
    // Lets the UI label a row Manual vs Automatic. Derived from the owning request's channel, so it
    // needs no extra column and stays correct for runs created before this feature existed.
    ProcessRequest request = run.getProcessRequest();
    json.put("manual", request != null && CHANNEL_DIRECT.equals(request.getChannel()));
    // NOTE: run.getLog() and run.getReport() are intentionally absent. See class javadoc.
    return json;
  }

  private List<ProcessRun> findRuns(Process process, int limit) {
    OBCriteria<ProcessRun> criteria = OBDal.getInstance().createCriteria(ProcessRun.class);
    criteria.createAlias(ProcessRun.PROPERTY_PROCESSREQUEST, "request");
    criteria.add(Restrictions.eq("request." + ProcessRequest.PROPERTY_PROCESS + ".id",
        process.getId()));
    criteria.addOrder(Order.desc(ProcessRun.PROPERTY_STARTTIME));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(limit);
    return criteria.list();
  }

  /**
   * True while a run of this process is still {@code PRC}, so the UI can keep the trigger off.
   *
   * <p>Bounded by {@link #STALE_RUN_MS} on purpose. A {@code PRC} row is only moved out of that
   * state by {@code ProcessMonitor} when the job finishes; a JVM killed mid-run leaves the row at
   * {@code PRC} forever. Without the bound, one such orphan would disable the manual trigger
   * permanently and the page would insist a run was in progress for the rest of the instance's
   * life. The accounting process completes in well under a second on the reference instance, so an
   * hour is several orders of magnitude of head-room — long enough that a genuinely slow run is
   * never mistaken for an orphan, short enough that an orphan does not outlive the working day.</p>
   */
  private boolean hasRunInProgress(Process process) {
    OBCriteria<ProcessRun> criteria = OBDal.getInstance().createCriteria(ProcessRun.class);
    criteria.createAlias(ProcessRun.PROPERTY_PROCESSREQUEST, "request");
    criteria.add(Restrictions.eq("request." + ProcessRequest.PROPERTY_PROCESS + ".id",
        process.getId()));
    criteria.add(Restrictions.eq(ProcessRun.PROPERTY_STATUS, STATUS_PROCESSING));
    criteria.add(Restrictions.gt(ProcessRun.PROPERTY_STARTTIME,
        new Date(System.currentTimeMillis() - STALE_RUN_MS)));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Trigger side
  // ---------------------------------------------------------------------------------------------

  /**
   * Inserts and schedules a one-shot sibling request. Returns the outcome as a small object rather
   * than throwing, so a refusal ("already running", "scheduler in standby") renders as an ordinary
   * message next to a still-usable status card instead of collapsing the whole page into an error.
   */
  private JSONObject triggerManualRun(Process process, ProcessRequest recurring)
      throws JSONException {
    if (recurring == null) {
      return triggerResult(false, "notScheduled");
    }
    if (hasRunInProgress(process) || hasPendingManualRequest(process)) {
      return triggerResult(false, "alreadyRunning");
    }

    OBScheduler scheduler = OBScheduler.getInstance();
    try {
      // A node under the no-execute background policy leaves Quartz in standby, where
      // OBScheduler.schedule(...) silently no-ops. Reporting success there would be a lie.
      if (!scheduler.isSchedulingAllowed()) {
        return triggerResult(false, "schedulerUnavailable");
      }

      ProcessContext context = ProcessContext.newInstance(recurring.getOpenbravoContext());
      VariablesSecureApp vars = context.toVars();
      ConnectionProvider connection = scheduler.getConnection();

      ProcessBundle bundle = new ProcessBundle(process.getId(), vars,
          ProcessBundle.Channel.DIRECT, context.getClient(), context.getOrganization(),
          Boolean.TRUE.equals(recurring.isSecurityBasedOnRole())).init(connection);

      // The no-requestId overload: mints a fresh id, INSERTs its own AD_PROCESS_REQUEST and
      // schedules it with an IMMEDIATE trigger. It never touches `recurring`.
      scheduler.schedule(bundle);

      log.info("SFAcctProcessMonitor: manual run of {} scheduled by user {}", process.getName(),
          vars.getUser());
      return triggerResult(true, "started");
    } catch (Exception e) {
      log.error("SFAcctProcessMonitor: could not schedule a manual run of {}", process.getName(), e);
      return triggerResult(false, "scheduleFailed");
    }
  }

  /**
   * A one-shot we already created that has not been picked up yet — avoids stacking duplicates.
   *
   * <p>Bounded by {@link #STALE_RUN_MS} for the same reason as {@link #hasRunInProgress}: a
   * one-shot left at {@code SCH} because Tomcat died between the INSERT and the fire would
   * otherwise block every future manual run. ({@code OBScheduler.initialize()} does move such a
   * row to {@code SYR} on the next startup, so this bound only matters for the window before that
   * restart — but that window is exactly when an admin would be reaching for this button.)</p>
   */
  private boolean hasPendingManualRequest(Process process) {
    OBCriteria<ProcessRequest> criteria = OBDal.getInstance().createCriteria(ProcessRequest.class);
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_PROCESS + ".id", process.getId()));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_CHANNEL, CHANNEL_DIRECT));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_STATUS, STATUS_SCHEDULED));
    criteria.add(Restrictions.gt(ProcessRequest.PROPERTY_CREATIONDATE,
        new Date(System.currentTimeMillis() - STALE_RUN_MS)));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  private JSONObject triggerResult(boolean started, String reason) throws JSONException {
    JSONObject result = new JSONObject();
    result.put("started", started);
    result.put(FIELD_REASON, reason);
    return result;
  }

  // ---------------------------------------------------------------------------------------------
  // Lookups and helpers
  // ---------------------------------------------------------------------------------------------

  private Process resolveAcctProcess() {
    OBCriteria<Process> criteria = OBDal.getInstance().createCriteria(Process.class);
    criteria.add(Restrictions.eq(Process.PROPERTY_SEARCHKEY, ACCT_PROCESS_SEARCH_KEY));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    List<Process> found = criteria.list();
    return found.isEmpty() ? null : found.get(0);
  }

  /**
   * The active, still-scheduled recurring request for this process. Read only — this row is the
   * automatic cadence and is never modified by this endpoint.
   */
  private ProcessRequest findRecurringRequest(Process process) {
    OBCriteria<ProcessRequest> criteria = OBDal.getInstance().createCriteria(ProcessRequest.class);
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_PROCESS + ".id", process.getId()));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_STATUS, STATUS_SCHEDULED));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.ne(ProcessRequest.PROPERTY_CHANNEL, CHANNEL_DIRECT));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    List<ProcessRequest> found = criteria.list();
    return found.isEmpty() ? null : found.get(0);
  }

  private int historyLimit(Map<String, String> parameter) {
    String raw = StringUtils.trimToNull(parameter.get(PARAM_LIMIT));
    if (raw == null) {
      return DEFAULT_HISTORY_LIMIT;
    }
    try {
      int requested = Integer.parseInt(raw);
      if (requested < 1) {
        return DEFAULT_HISTORY_LIMIT;
      }
      return Math.min(requested, MAX_HISTORY_LIMIT);
    } catch (NumberFormatException e) {
      return DEFAULT_HISTORY_LIMIT;
    }
  }

  private Object formatTimestamp(Date value) {
    if (value == null) {
      return JSONObject.NULL;
    }
    return new SimpleDateFormat(TIMESTAMP_FORMAT).format(value);
  }

  private JSONObject notAuthorized() {
    return errorPayload(REASON_NOT_AUTHORIZED, "Not authorized");
  }

  /**
   * Error payloads carry a stable machine-readable {@code reason} alongside the human message.
   * Without it the frontend would have to tell "you may not see this" apart from "this instance
   * has no accounting process" by string-matching {@code message} — two states that need very
   * different UI and must never be conflated.
   */
  private JSONObject errorPayload(String reason, String message) {
    try {
      JSONObject result = new JSONObject();
      result.put(FIELD_ERROR, true);
      result.put(FIELD_REASON, reason);
      result.put(FIELD_MESSAGE, message);
      return result;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build SFAcctProcessMonitor error result", e);
    }
  }
}
