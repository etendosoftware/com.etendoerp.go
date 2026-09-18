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
import java.util.Arrays;
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
 * <p>The one-shot row carries no frequency, so it can never become a second recurring job. It is
 * created with {@code Channel.BACKGROUND} and scoped to the CALLING client — both decisions, and
 * the restart behaviour that follows from the first, are explained on
 * {@link #triggerManualRun(Process, ProcessRequest)}.</p>
 *
 * <h2>Access and tenant scope</h2>
 *
 * <p>Both actions are gated on {@link NeoAccessHelper#isAdminOrClientAdmin(Role)}, enforced
 * server-side. Any other caller (including one with no role) gets the {@code notAuthorized} payload
 * — the frontend flag is visual gating only and is never trusted here. Denial mirrors the
 * "answer, don't 403" convention the rest of this webhook family uses.</p>
 *
 * <p><b>A manual run posts only the caller's OWN client's documents.</b> That is the whole point of
 * the scoping described on {@code triggerManualRun}: the automatic cadence runs as System and
 * therefore posts every tenant, which is right for an unattended instance-wide job and wrong for a
 * button any client-admin can press. Reading is scoped the same way — {@link #visibleClientIds()}
 * — so one tenant's admin never sees another tenant's manual runs.</p>
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

  /** {@code AD_PROCESS_REQUEST.CHANNEL} written by a manual one-shot from this page. */
  private static final String CHANNEL_MANUAL = ProcessBundle.Channel.BACKGROUND.toString();
  /** The interactive "Posting by DB tables" form's channel — never created by this endpoint. */
  private static final String CHANNEL_INTERACTIVE = ProcessBundle.Channel.DIRECT.toString();

  private static final String SYSTEM_CLIENT = "0";
  /** Organization {@code '0'} inside one client means "every organization of that client". */
  private static final String ORG_ALL_WITHIN_CLIENT = "0";

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
    // which a tenant admin's own context cannot read. Every criteria below ALSO disables
    // readable-client/org filtering explicitly, so the scope never depends on ambient context.
    //
    // The boolean is `doOrgClientAccessCheck`, NOT "bypass everything": `true` KEEPS the
    // cross-client write check and is therefore the STRICTER variant. The 10 sibling webhooks in
    // this package all call the no-arg setAdminMode(), which is the laxer one. `true` is
    // deliberate here — this class performs no OBDal writes at all (its only mutation goes through
    // raw XSQL in ProcessRequestData.insert, which never reaches SecurityChecker), so the strict
    // check costs nothing and keeps the class honest if a DAL write is ever added. If you DO add
    // one, expect an OBSecurityException and decide consciously rather than switching this to the
    // no-arg form to make it go away. The same trap is documented in
    // UserRoleCompositionServiceIntegrationTest (see its notes on setAdminMode's argument).
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

      // Trigger FIRST, then read. Reading first and patching only `history` afterwards produced a
      // response whose fields disagreed with each other: `running` was captured before the run was
      // scheduled — and since the trigger refuses outright when a run is already in progress, it
      // was necessarily false in EVERY successful trigger response — while `lastRun` came from the
      // stale read and `history` from the fresh one. The frontend keys its poll off `running`, so
      // it never started polling and the page sat there claiming a run had begun.
      JSONObject triggered = trigger ? triggerManualRun(process, recurring) : null;
      JSONObject result = buildStatus(process, recurring, limit);
      if (triggered != null) {
        result.put(FIELD_TRIGGERED, triggered);
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
   *
   * <p>Scoped to the caller's own client PLUS System ({@code '0'}) — see
   * {@link #visibleClientIds()}.</p>
   */
  private JSONArray buildHistory(Process process, int limit) throws JSONException {
    JSONArray history = new JSONArray();
    for (ProcessRun run : findRuns(process, limit)) {
      history.put(toRunJson(run));
    }
    return history;
  }

  /**
   * The clients whose runs this caller may see: their own, plus System.
   *
   * <p>Both {@code AD_PROCESS_REQUEST} and {@code AD_PROCESS_RUN} are tagged with the scheduling
   * context's client ({@code ProcessRequestData.insert} and {@code ProcessMonitor.jobToBeExecuted}
   * both pass {@code ctx.getClient()}), and the live instance confirms real per-tenant values, so
   * filtering by client is possible rather than theoretical.</p>
   *
   * <p>System is included deliberately, and it is not a leak: the System-context automatic run is
   * the shared cadence that posts <em>every</em> client's documents, including this caller's. Its
   * rows are the history of work done on the caller's own data, and excluding them would leave the
   * page looking as though the process had never run. What the filter does exclude is another
   * tenant's MANUAL runs, which say nothing about this caller's accounting.</p>
   */
  private List<String> visibleClientIds() {
    return Arrays.asList(OBContext.getOBContext().getCurrentClient().getId(), SYSTEM_CLIENT);
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
    json.put("manual", request != null && CHANNEL_MANUAL.equals(request.getChannel()));
    // NOTE: run.getLog() and run.getReport() are intentionally absent. See class javadoc.
    return json;
  }

  private List<ProcessRun> findRuns(Process process, int limit) {
    OBCriteria<ProcessRun> criteria = OBDal.getInstance().createCriteria(ProcessRun.class);
    criteria.createAlias(ProcessRun.PROPERTY_PROCESSREQUEST, "request");
    criteria.add(Restrictions.eq("request." + ProcessRequest.PROPERTY_PROCESS + ".id",
        process.getId()));
    // Client filtering is explicit, not DAL's: admin mode is on (needed to read the System
    // recurring request at all), which disables the readable-clients filter this would otherwise
    // rely on. Stating the restriction here means the scope does not depend on ambient context.
    criteria.add(Restrictions.in(ProcessRun.PROPERTY_CLIENT + ".id", visibleClientIds()));
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
    // Same scope as the history. Including System is the conservative half: while the instance-wide
    // automatic run is mid-flight it is posting THIS client's documents too, so starting a manual
    // run on top of it would have two jobs contending for the same unposted rows.
    criteria.add(Restrictions.in(ProcessRun.PROPERTY_CLIENT + ".id", visibleClientIds()));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Trigger side
  // ---------------------------------------------------------------------------------------------

  /**
   * Inserts and schedules a one-shot sibling request, scoped to the CALLING client. Returns the
   * outcome as a small object rather than throwing, so a refusal ("already running", "scheduler in
   * standby") renders as an ordinary message next to a still-usable status card instead of
   * collapsing the whole page into an error.
   *
   * <h3>Why the caller's own client, and not the recurring request's context (ETP-5269 scope
   * change)</h3>
   *
   * <p>{@code AcctServerProcess.doExecute} branches on the bundle context's client:</p>
   * <pre>
   *   if (vars.getClient().equals("0")) {  // every non-System client, one after another
   *     for (Client c : allClientsExceptSystem) processClient(...);
   *   } else {
   *     processClient(vars, bundle);       // this client alone
   *   }
   * </pre>
   *
   * <p>The recurring request is a System row ({@code AD_Client_ID = '0'}), so it takes the first
   * branch — which is correct for an unattended instance-wide cadence, but wrong for a button: it
   * would let a client-admin of one tenant post accounting for every other tenant. Passing the
   * caller's own client id takes the second branch, so a manual run processes only the caller's
   * documents. Organization is {@code '0'} deliberately: within one client that means "every
   * organization", which is the client-wide equivalent of what the automatic run does for that
   * client ({@code processClient} then calls {@code selectAcctTable(connection, client)}).</p>
   *
   * <h3>Why Channel.BACKGROUND and not DIRECT</h3>
   *
   * <p>{@code AcctServerProcess} reads the channel: {@code isDirect = bundle.getChannel() ==
   * Channel.DIRECT}, and when direct it loads its table/org/date parameters from
   * {@code AD_PINSTANCE_PARA} using the bundle's pinstance id. A one-shot scheduled request has NO
   * pinstance, and those generated finders return {@code ""} rather than null when nothing matches
   * — so {@code strOrg} would be silently overwritten from {@code "0"} to {@code ""} and
   * {@code AcctServer.get(table, client, "", conn)} would be asked for an empty organization. The
   * run would report success and post nothing. {@code BACKGROUND} keeps {@code isDirect} false, so
   * the process takes exactly the same path the automatic run takes, and it stays a distinct
   * channel string from both {@code "Direct"} (the interactive "Posting by DB tables" form) and
   * {@code "Process Scheduler"} (the recurring row) — which is what lets the queries below tell the
   * three kinds of row apart without a new column.</p>
   *
   * <p>The trade-off accepted in exchange: {@code OBScheduler.initialize()} skips rescheduling a
   * leftover {@code SCH} row only when its channel is {@code "Direct"} or its timing is IMMEDIATE.
   * A {@code BACKGROUND} one-shot that was interrupted between INSERT and firing is therefore
   * re-fired once on the next startup. One extra posting run is harmless — {@code AcctServer} only
   * posts documents that are still unposted — and it is far better than a run that silently posts
   * nothing. It still cannot become recurring: the row carries no frequency.</p>
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

      OBContext caller = OBContext.getOBContext();
      String clientId = caller.getCurrentClient().getId();
      if (SYSTEM_CLIENT.equals(clientId)) {
        // A System-context caller has no own tenant to scope to, and falling back to the
        // instance-wide run is exactly what this change exists to prevent. The automatic cadence
        // already covers System; there is nothing for the button to do that would be safe.
        return triggerResult(false, "systemClientNotScopable");
      }

      VariablesSecureApp vars = new VariablesSecureApp(caller.getUser().getId(), clientId,
          ORG_ALL_WITHIN_CLIENT, caller.getRole().getId(), caller.getLanguage().getLanguage());
      ConnectionProvider connection = scheduler.getConnection();

      ProcessBundle bundle = new ProcessBundle(process.getId(), vars,
          ProcessBundle.Channel.BACKGROUND, clientId, ORG_ALL_WITHIN_CLIENT,
          Boolean.TRUE.equals(recurring.isSecurityBasedOnRole())).init(connection);

      // The no-requestId overload: mints a fresh id, INSERTs its own AD_PROCESS_REQUEST (tagged
      // with this client, which is what makes the run auditable and filterable per tenant) and
      // schedules it with an IMMEDIATE trigger. It never touches `recurring`.
      scheduler.schedule(bundle);

      log.info("SFAcctProcessMonitor: manual run of {} scheduled by user {} for client {}",
          process.getName(), vars.getUser(), clientId);
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
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_CHANNEL, CHANNEL_MANUAL));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_STATUS, STATUS_SCHEDULED));
    // Only this client's own pending one-shot blocks it. Another tenant's queued manual run is
    // none of this caller's business and must not disable their button.
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_CLIENT + ".id",
        OBContext.getOBContext().getCurrentClient().getId()));
    criteria.add(Restrictions.gt(ProcessRequest.PROPERTY_CREATIONDATE,
        new Date(System.currentTimeMillis() - STALE_RUN_MS)));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  /**
   * {@code started: true} means the job was HANDED TO QUARTZ, not that it has run or even that its
   * {@code AD_PROCESS_RUN} row exists yet.
   *
   * <p>{@code ProcessMonitor.jobToBeExecuted} writes that row on the scheduler's own thread, so a
   * read taken in the same request — however late — can legitimately still see neither a
   * {@code PRC} run nor a new history entry. Callers must therefore POLL after a successful
   * trigger rather than treating the triggering response as the final word; the frontend hook does
   * exactly that, on a bounded deadline, instead of keying solely off {@code running}.</p>
   *
   * <h3>Concurrency: the recurring run can NOT veto ours (established ETP-5269, twice)</h3>
   *
   * <p>{@code AD_Process.preventconcurrent} is {@code 'Y'} for this process and the flag does
   * reach our trigger ({@code TriggerGenerator} copies it into the job data map), so it is natural
   * to assume a manual run can be vetoed when the recurring job is mid-flight. <b>It cannot.</b>
   * {@code ProcessMonitor.vetoJobExecution} only treats another executing job as concurrent when
   * it matches on BOTH client and organization:</p>
   *
   * <pre>
   *   boolean isSameClient = isSameParam(jobAlreadyScheduled, newJob, "Client");
   *   if (!isSameClient || !isSameParam(jobAlreadyScheduled, newJob, "Organization")) {
   *     continue;                       // not concurrent — no veto
   *   }
   * </pre>
   *
   * <p>and {@code isSameParam} compares {@code ProcessBundle.getContext().getClient()}. Our
   * one-shot runs as the CALLER'S client; the recurring run is System ({@code '0'}). Different
   * client, so the two are mutually invisible to the concurrency check. Scoping the bundle to the
   * caller — done for tenant isolation — removed this scenario as a side effect.</p>
   *
   * <p>The only reachable veto is another run of this process on the SAME client and SAME
   * organization: a second manual run squeezing through the TOCTOU gap in the guards above, or a
   * tenant that also holds its own recurring request. <b>In that case vetoing is the correct
   * outcome and must not be worked around</b> — the in-flight run is already posting exactly the
   * documents the second one would, so retrying would duplicate work, and an automatic retry was
   * evaluated and rejected on those grounds.</p>
   *
   * <p>If a veto does occur it is not silent: {@code ProcessMonitor.stopConcurrency} writes its
   * own {@code AD_PROCESS_RUN} row, tagged with this caller's client, status {@code ERR}, duration
   * {@code "00:00:00.000"}. Its explanation ("Concurrent attempt to execute") goes to the
   * {@code LOG} column, which this endpoint deliberately never exposes, so it surfaces as a plain
   * failed run. Note that it is NOT reliably distinguishable from a genuine failure without that
   * log: {@code getDuration(jec.getJobRunTime())} renders the identical duration string for any
   * real failure that dies inside a millisecond, and neither path ever writes {@code RESULT} or
   * {@code REPORT} (they are not even parameters of {@code ProcessRunData.insert}). Do not build
   * behaviour that branches on "zero-duration ERR means it was skipped".</p>
   */
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
   *
   * <h3>Which row wins when there is more than one</h3>
   *
   * <p>More than one is possible: the shipped configuration is a single System row
   * ({@code AD_Client_ID = '0'}), but a tenant may also hold its OWN recurring request for this
   * process — the reference instance still carries a completed one (F&amp;B,
   * {@code EE664C05…}), which is how this case came to light. This query previously took
   * {@code maxResults(1)} with NO ordering, so which row won was whatever the database happened to
   * return.</p>
   *
   * <p>Ordered by <b>soonest next fire time</b>, tie-broken by id for total determinism.</p>
   *
   * <p><b>Why soonest, and NOT "prefer the caller's own client".</b> The single thing this row
   * feeds the UI is "Next automatic run" — the answer to <i>when will my accounting next be posted
   * without me doing anything</i>. Both candidates post the caller's documents: the System row
   * sweeps every tenant ({@code AcctServerProcess.doExecute} iterates all clients when its context
   * client is {@code '0'}), and a tenant row posts that tenant. So the truthful answer is
   * whichever fires FIRST, not whichever is organizationally closer. Preferring the caller's own
   * client would actively mislead in the likely configuration: a tenant row scheduled nightly
   * alongside the System row's five-minute sweep would make the page announce tomorrow 02:00 while
   * the documents were in fact going to be posted within minutes. Sorting nulls last falls out of
   * SQL {@code ASC} and is what we want — a row whose next fire time is unknown should never
   * outrank one with a concrete imminent time.</p>
   *
   * <p>The row's {@code securityBasedOnRole} is also copied onto the manual bundle. That is a
   * weaker use of an arbitrary-ish pick, but it is bounded: the flag is {@code true} on every
   * recurring request for this process, and the manual run's ACTUAL authority comes from the
   * caller's own session, not from this row.</p>
   */
  private ProcessRequest findRecurringRequest(Process process) {
    OBCriteria<ProcessRequest> criteria = OBDal.getInstance().createCriteria(ProcessRequest.class);
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_PROCESS + ".id", process.getId()));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_STATUS, STATUS_SCHEDULED));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_ACTIVE, true));
    // Exclude both one-shot kinds — this page's own manual runs (BACKGROUND) and the interactive
    // form's (DIRECT). What is wanted is the row that carries the automatic cadence.
    criteria.add(Restrictions.not(Restrictions.in(ProcessRequest.PROPERTY_CHANNEL,
        Arrays.asList(CHANNEL_MANUAL, CHANNEL_INTERACTIVE))));
    criteria.addOrder(Order.asc(ProcessRequest.PROPERTY_NEXTEXECUTION));
    criteria.addOrder(Order.asc(ProcessRequest.PROPERTY_ID));
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
