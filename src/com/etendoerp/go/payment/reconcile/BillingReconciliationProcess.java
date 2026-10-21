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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.startup.BillingReconciliationScheduleStartup;
import com.etendoerp.go.schemaforge.email.InternalAlertService;
import com.etendoerp.go.usage.UsageMessages;

/**
 * ETP-5048 — the Classic entry point of the billing reconciliation: the background process
 * {@code ETGO_BillingReconciliation}, on the System menu entry <b>Billing Reconciliation</b> and
 * planted as a System process request by {@code BillingReconciliationScheduleStartup}.
 *
 * <p>Modelled on {@code UsageAggregationProcess} (ETP-5050). The operator reads the outcome in the
 * popup (the {@link OBError} result) and, for scheduled runs, in Process Monitor, where the summary
 * is also written to the run log. A run that met errors, or whose absent-subscription breaker
 * tripped, sets an error result <em>and</em> throws: the interactive popup reads the result, while
 * Process Monitor records ERROR only when an exception leaves the process.
 *
 * <p><b>Output.</b> The popup and the run log (Process Monitor) carry the summary and one line per
 * affected item; a dry run words everything as what it would do. <b>One run at a time</b>
 * ({@link ReconciliationRunLock}): a second run ends at once with a warning that one is already
 * running. A run fired by a Process Group has no process-request id of its own and is not gated
 * by the flag.
 *
 * <p>Parameter (optional): <b>Dry run</b> ({@code dryrun}, Yes/No). Yes reads and decides
 * everything and writes nothing.
 *
 * <p><b>Scheduling.</b> Production is updated by delta and never restarted (see
 * {@code DemoTrialNoticeProcess}), so there the reconciliation is scheduled by hand in the Process
 * Request window, like Demo Trial Notices. Environments that do restart get the request planted
 * by {@link BillingReconciliationScheduleStartup}, only while the
 * {@value GoFeatureFlags#FLAG_BILLING_RECONCILIATION_SCHEDULE} flag is on.
 *
 * <p><b>The flag gates every scheduled run</b> (decided by Martin, 2026-10-21): a run the Process
 * Scheduler fires for a request whose timing is <em>Schedule</em> ({@code S}) — the startup's or
 * one an administrator created — does nothing while the flag is off, and ends as an ERROR (error
 * result and exception) so Process Monitor shows that a scheduled request is firing for nothing.
 * The first such run of each UTC day also sends one internal alert.
 * A launch from the menu, or a Process Request run immediately or later once, is never gated.
 */
public class BillingReconciliationProcess extends DalBaseProcess {

  private static final Logger log = LogManager.getLogger(BillingReconciliationProcess.class);

  static final String PARAM_DRY_RUN = "dryrun";
  private static final String TITLE_DONE = "Billing Reconciliation Complete";
  private static final String TITLE_FAILED = "Billing Reconciliation Finished With Errors";
  private static final String TITLE_SKIPPED = "Billing Reconciliation Schedule Is Off";
  private static final String TITLE_BUSY = "Billing Reconciliation Already Running";
  static final String ALREADY_RUNNING = "A billing reconciliation is already running; nothing "
      + "was done.";
  /** {@code AD_PROCESS_REQUEST.TIMING_OPTION} of a recurring schedule. */
  static final String TIMING_SCHEDULED = "S";
  private static final FeatureFlagContext ENVIRONMENT = FeatureFlagContext.forAccount(null);

  static final String KIND_SCHEDULE_FLAG_OFF = "schedule-flag-off";
  private static final String ALERT_SCHEDULE_FLAG_OFF = "billing-reconcile-schedule-flag-off";
  private static final String ALERT_STAGE_SCHEDULE_FLAG_OFF = "turn-flag-on-or-unschedule-request";

  private final Supplier<BillingReconciliationService> serviceFactory;
  /** Where the flag-off alert row goes; package-visible so tests can substitute it. */
  BillingEventStore eventStore = new BillingEventStore();
  /** Where the flag-off alert goes; package-visible so tests can substitute it. */
  InternalAlertService internalAlerts = new InternalAlertService();
  /** Decides the UTC day of the once-a-day alert; package-visible for tests. */
  Clock clock = Clock.systemUTC();
  /** At most one run at a time (review F4); package-visible so tests can substitute it. */
  ReconciliationRunLock runLock = new ReconciliationRunLock();

  /** Production wiring. */
  public BillingReconciliationProcess() {
    this(BillingReconciliationService::new);
  }

  BillingReconciliationProcess(Supplier<BillingReconciliationService> serviceFactory) {
    this.serviceFactory = serviceFactory;
  }

  @Override
  public void doExecute(ProcessBundle bundle) throws Exception {
    if (isScheduledRun(bundle) && !scheduleEnabled()) {
      String message = "The " + GoFeatureFlags.FLAG_BILLING_RECONCILIATION_SCHEDULE
          + " flag is off, so this scheduled request did nothing. Turn the flag on, or unschedule "
          + "the request in the Process Request window.";
      log.warn("{} Scheduled run of process request {} skipped: {}",
          ReconciliationAudit.LOG_PREFIX, bundle.getProcessRequestId(), message);
      // About the schedule, not the reconciliation's writes: alerts whatever the request's Dry
      // run parameter says (review F3).
      alertFlagOffOncePerDay(bundle.getProcessRequestId());
      throw fail(bundle, TITLE_SKIPPED, message, message);
    }
    boolean dryRun = readDryRun(bundle);
    ReconciliationRunLock.Held lock = runLock.tryAcquire();
    if (lock == null) {
      alreadyRunning(bundle);
      return;
    }
    ReconciliationResult result;
    try {
      result = serviceFactory.get().run(dryRun);
    } catch (RuntimeException e) {
      log.error("{} Billing reconciliation failed", ReconciliationAudit.LOG_PREFIX, e);
      String reason = UsageMessages.atSafe(e.getMessage());
      throw fail(bundle, "Billing Reconciliation Failed", reason, reason);
    } finally {
      lock.close();
    }
    if (lock.isUnlocked()) {
      result.markRanWithoutLock();
    }
    String summary = UsageMessages.atSafe(result.toString());
    // Process Monitor shows the run log: the summary and the same item lines the popup lists.
    bundle.getLogger().logln(summary);
    for (String line : result.itemLines()) {
      bundle.getLogger().logln(UsageMessages.atSafe(line));
    }
    String message = UsageMessages.atSafe(result.toHtml());
    if (result.hasFailures()) {
      // The popup renders HTML; the exception reaches the log and Process Monitor as text.
      throw fail(bundle, TITLE_FAILED, message, UsageMessages.atSafe(result.toPlainText()));
    }
    log.info("{} {}", ReconciliationAudit.LOG_PREFIX, summary);
    OBError success = new OBError();
    success.setType("Success");
    success.setTitle(TITLE_DONE);
    success.setMessage(message);
    bundle.setResult(success);
  }

  /**
   * Review F4: another run holds the run lock (a menu launch overlapping the schedule). This one
   * does nothing and says so, as a warning rather than a failure: nothing is wrong, the other
   * run does the work.
   */
  private static void alreadyRunning(ProcessBundle bundle) {
    log.warn("{} {}", ReconciliationAudit.LOG_PREFIX, ALREADY_RUNNING);
    bundle.getLogger().logln(ALREADY_RUNNING);
    OBError warning = new OBError();
    warning.setType("Warning");
    warning.setTitle(TITLE_BUSY);
    warning.setMessage(ALREADY_RUNNING);
    bundle.setResult(warning);
  }

  /**
   * Tells a person, once per UTC day, that a scheduled request keeps firing while the flag is off,
   * so no reconciliation is happening. The breaker's pattern: a deterministic
   * {@code reconcile:schedule-flag-off:<yyyy-MM-dd>} billing-event row, and only the call that
   * wrote it sends the internal alert. Hourly runs the same day stay silent. The request's Dry
   * run parameter does not apply: the alert is about the schedule, not the reconciliation's
   * writes, and a dry-run schedule firing for nothing must be seen too. Never throws: the run
   * ends as an ERROR whatever happens here.
   */
  private void alertFlagOffOncePerDay(String requestId) {
    try {
      ReconciliationAudit audit = new ReconciliationAudit(eventStore, false, internalAlerts);
      String day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
      if (audit.write(ReconciliationAudit.eventId(KIND_SCHEDULE_FLAG_OFF, day),
          KIND_SCHEDULE_FLAG_OFF, null, BillingEventStore.RESULT_IGNORED, "The "
              + GoFeatureFlags.FLAG_BILLING_RECONCILIATION_SCHEDULE + " flag is off while a "
              + "scheduled Billing Reconciliation request keeps firing, so no reconciliation is "
              + "happening. Turn the flag on, or unschedule the request in the Process Request "
              + "window.", ReconciliationSupport.summary("processRequest", requestId))) {
        audit.internalAlert(ALERT_SCHEDULE_FLAG_OFF, day, ALERT_STAGE_SCHEDULE_FLAG_OFF, null);
      }
    } catch (RuntimeException e) {
      log.warn("{} Could not record the schedule-flag-off alert ({})",
          ReconciliationAudit.LOG_PREFIX, e.getClass().getSimpleName());
    }
  }

  /**
   * Whether this run was fired by a recurring schedule: the Process Scheduler channel, for a
   * request whose timing is <em>Schedule</em>. {@code ProcessBundle.request} builds every
   * scheduler-run bundle on {@link ProcessBundle.Channel#SCHEDULED}, an immediate Process Request
   * run included, so the request's timing is what tells a schedule apart.
   */
  boolean isScheduledRun(ProcessBundle bundle) {
    String requestId = bundle.getProcessRequestId();
    return bundle.getChannel() == ProcessBundle.Channel.SCHEDULED && requestId != null
        && TIMING_SCHEDULED.equals(timingOf(requestId));
  }

  /**
   * The timing option of a process request. Package-visible so tests can answer it without a
   * database.
   *
   * @return {@code I}, {@code L} or {@code S}; null when the request does not exist
   */
  String timingOf(String requestId) {
    OBContext.setAdminMode(true);
    try {
      ProcessRequest request = OBDal.getInstance().get(ProcessRequest.class, requestId);
      return request == null ? null : request.getTiming();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  boolean scheduleEnabled() {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_BILLING_RECONCILIATION_SCHEDULE,
        ENVIRONMENT);
  }

  /**
   * Reads the optional Dry run flag, case-insensitively on the key (the parameter window, a
   * scheduled request and a direct call do not agree on its case) and leniently on the value.
   */
  static boolean readDryRun(ProcessBundle bundle) {
    Map<String, Object> params = bundle.getParams();
    if (params == null) {
      return false;
    }
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(PARAM_DRY_RUN)) {
        Object value = entry.getValue();
        if (value instanceof Boolean) {
          return (Boolean) value;
        }
        String text = String.valueOf(value).trim();
        return "Y".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text)
            || "yes".equalsIgnoreCase(text);
      }
    }
    return false;
  }

  /**
   * Reports a failed run on both surfaces — see {@code UsageAggregationProcess#fail} for why both.
   *
   * @param popupMessage the popup text, which may carry HTML line breaks
   * @param plainMessage the same content as plain text, for the log and the exception
   * @return the exception for the caller to throw, so the compiler sees the run end there
   */
  private static OBException fail(ProcessBundle bundle, String title, String popupMessage,
      String plainMessage) {
    log.error("{} {}: {}", ReconciliationAudit.LOG_PREFIX, title, plainMessage);
    OBError error = new OBError();
    error.setType("Error");
    error.setTitle(title);
    error.setMessage(popupMessage);
    bundle.setResult(error);
    return new OBException(plainMessage);
  }
}
