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
package com.etendoerp.go.startup;

import java.sql.Timestamp;
import java.util.Date;

import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.ComponentProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.scheduling.OBScheduler;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessContext;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.payment.reconcile.ReconciliationSettings;

/**
 * ETP-5048 — plants the System process request of the billing reconciliation
 * ({@code ETGO_BillingReconciliation}) on application startup, every
 * {@code etendo.go.billing.reconcile.interval.minutes} minutes (default hourly).
 *
 * <p>The same idempotent shape as {@link TenantPoolScheduleStartup}: one fixed-id System request,
 * inserted only when no active {@code SCH} request for the process exists, then registered with the
 * live scheduler (bounded wait for it to leave standby) so it fires in this boot; if that
 * registration does not happen, {@code OBScheduler.initialize()} picks the row up on the next
 * restart.
 *
 * <p><b>Production never runs this hook.</b> It is updated by delta and never restarted (see
 * {@code DemoTrialNoticeProcess}), so there the reconciliation is scheduled by hand in the Process
 * Request window, like Demo Trial Notices. This hook is for environments that do restart.
 *
 * <p><b>Strictly off by default (decided 2026-10-18).</b> The request is planted only while the
 * {@code billing-reconciliation-schedule} flag is on, so turning the flag on takes effect at the
 * next restart. The process checks the flag on every scheduled run, this request's or one an
 * administrator created, so turning the flag off stops the scheduled work without a restart: each
 * firing then ends as an ERROR in Process Monitor until the request is unscheduled. The interval
 * is read when the request is first planted; changing it later means editing the request in the
 * Process Request window.
 *
 * <p><b>Its fixed id is never inserted twice.</b> When a row with {@link #REQUEST_ID} exists in
 * any status, typically unscheduled by an operator, it is left as it is: an unscheduled request was
 * unscheduled on purpose, and inserting the same primary key again would fail the startup.
 */
@ApplicationScoped
@ComponentProvider.Qualifier(BillingReconciliationScheduleStartup.QUALIFIER)
public class BillingReconciliationScheduleStartup extends SessionAwareStartup {

  static final String QUALIFIER = "com.etendoerp.go.startup.BillingReconciliationScheduleStartup";

  private static final Logger log =
      LogManager.getLogger(BillingReconciliationScheduleStartup.class);

  /** {@code AD_PROCESS_ID} of {@code ETGO_BillingReconciliation}. */
  static final String PROCESS_ID = "886F350C8A744D03B5EB42A1F3EFBD5A";
  /**
   * Fixed PK of the System process request this initializer owns. The process tells a scheduled
   * run from an operator's by this id, so it is public.
   */
  public static final String REQUEST_ID = "2230765696594CF787C07608FE14A427";

  private static final String SYSTEM = "0";
  private static final String SYSTEM_USER = "100";
  private static final String LANGUAGE = "en_US";
  private static final String CHANNEL_SCHEDULER = "Process Scheduler";
  private static final String TIMING_SCHEDULED = "S";
  private static final String FREQUENCY_MINUTELY = "2";
  private static final long SCHEDULER_POLL_MS = 500L;
  private static final long SCHEDULER_WAIT_TIMEOUT_MS = 120_000L;

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected String name() {
    return "BillingReconciliationScheduleStartup";
  }

  @Override
  protected void runPass() {
    if (!scheduleEnabled()) {
      log.info("BillingReconciliationScheduleStartup: the {} flag is off; no schedule is planted "
          + "(the process can still be run from the Billing Reconciliation menu)",
          GoFeatureFlags.FLAG_BILLING_RECONCILIATION_SCHEDULE);
      return;
    }
    if (ensureScheduled()) {
      registerWithScheduler();
    }
  }

  /** Package-visible so tests can decide the flag without a provider. */
  boolean scheduleEnabled() {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_BILLING_RECONCILIATION_SCHEDULE,
        FeatureFlagContext.environment());
  }

  /**
   * Registers the freshly committed request with {@link OBScheduler}. Package-visible so tests can
   * keep off the real scheduler. Never throws: the persisted row is the fallback.
   */
  void registerWithScheduler() {
    try {
      OBScheduler scheduler = OBScheduler.getInstance();
      long deadline = System.currentTimeMillis() + SCHEDULER_WAIT_TIMEOUT_MS;
      while (!scheduler.isSchedulingAllowed() && System.currentTimeMillis() < deadline) {
        Thread.sleep(SCHEDULER_POLL_MS);
      }
      if (!scheduler.isSchedulingAllowed()) {
        log.info("BillingReconciliationScheduleStartup: scheduler still inactive; the "
            + "reconciliation starts on the next restart");
        return;
      }
      scheduler.schedule(REQUEST_ID, ProcessBundle.request(REQUEST_ID, systemVars(),
          scheduler.getConnection()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.warn("BillingReconciliationScheduleStartup: could not register the reconciliation with "
          + "the scheduler now; OBScheduler picks it up on the next restart", e);
    }
  }

  /** Visible for testing: the synchronous idempotent pass. */
  boolean ensureScheduled() {
    OBContext.setAdminMode(true);
    try {
      if (activeScheduledRequestExists()) {
        return false;
      }
      ProcessRequest own = OBDal.getInstance().get(ProcessRequest.class, REQUEST_ID);
      if (own != null) {
        log.info("BillingReconciliationScheduleStartup: process request {} exists with status {}; "
            + "it was unscheduled on purpose and is left as it is", REQUEST_ID, own.getStatus());
        return false;
      }
      Process process = OBDal.getInstance().get(Process.class, PROCESS_ID);
      if (process == null) {
        log.warn("BillingReconciliationScheduleStartup: process {} not found; skipping",
            PROCESS_ID);
        return false;
      }
      long interval = ReconciliationSettings.fromConfiguration().getIntervalMinutes();
      insertSystemRequest(process, interval);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      log.info("BillingReconciliationScheduleStartup: scheduled the billing reconciliation every "
          + "{} min", interval);
      return true;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  boolean activeScheduledRequestExists() {
    return !OBDal.getInstance().createQuery(ProcessRequest.class,
            "as pr where pr.process.id = :processId and pr.status = :status and pr.active = true")
        .setNamedParameter("processId", PROCESS_ID)
        .setNamedParameter("status", org.openbravo.scheduling.Process.SCHEDULED)
        .setFilterOnReadableClients(false)
        .setMaxResult(1)
        .list()
        .isEmpty();
  }

  private void insertSystemRequest(Process process, long intervalMinutes) {
    ProcessRequest request = OBProvider.getInstance().get(ProcessRequest.class);
    request.setNewOBObject(true);
    request.setId(REQUEST_ID);
    request.setClient(OBDal.getInstance().get(Client.class, SYSTEM));
    request.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM));
    request.setActive(true);
    request.setProcess(process);
    request.setUserContact(OBDal.getInstance().get(User.class, SYSTEM_USER));
    request.setSecurityBasedOnRole(true);
    request.setOpenbravoContext(
        new ProcessContext(systemVars(), SYSTEM, SYSTEM, true).toString());
    request.setStatus(org.openbravo.scheduling.Process.SCHEDULED);
    request.setChannel(CHANNEL_SCHEDULER);
    request.setTiming(TIMING_SCHEDULED);
    request.setFrequency(FREQUENCY_MINUTELY);
    request.setIntervalInMinutes(intervalMinutes);
    Date now = new Date();
    request.setStartDate(now);
    request.setStartTime(new Timestamp(now.getTime()));
    request.setScheduleProcess(false);
    request.setRescheduleProcess(false);
    request.setUnscheduleProcess(false);
    request.setGroup(false);
    OBDal.getInstance().save(request);
  }

  private static VariablesSecureApp systemVars() {
    return new VariablesSecureApp(SYSTEM_USER, SYSTEM, SYSTEM, SYSTEM, LANGUAGE);
  }
}
