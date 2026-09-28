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

/**
 * Schedules the "Tenant Pool Filler" process (ETP-5389) for the System client, every
 * {@value #INTERVAL_MINUTES} minutes, on application startup.
 *
 * <p>Scheduled unconditionally: the process itself reads the {@code onboarding-tenant-pool} flag
 * on every run and does nothing while it is off, so a flag flipped in ConfigCat takes effect at
 * the next run instead of at the next restart. Same idempotent shape as
 * {@link StoredColumnQueueScheduleStartup}: one fixed-id System request, inserted only when no
 * active {@code SCH} request for the process exists, then registered with the live scheduler
 * (bounded wait for it to leave standby) so it fires in this boot; if that registration does not
 * happen, {@code OBScheduler.initialize()} picks the row up on the next restart.
 */
@ApplicationScoped
@ComponentProvider.Qualifier(TenantPoolScheduleStartup.QUALIFIER)
public class TenantPoolScheduleStartup extends SessionAwareStartup {

  static final String QUALIFIER = "com.etendoerp.go.startup.TenantPoolScheduleStartup";

  private static final Logger log = LogManager.getLogger(TenantPoolScheduleStartup.class);

  /** {@code AD_PROCESS_ID} of {@code com.etendoerp.go.rest.TenantPoolFillProcess}. */
  static final String FILL_PROCESS_ID = "B4C1BDC7106D46E1BF5337B46AB56300";
  /** Fixed PK of the System Process Request this initializer owns. */
  static final String REQUEST_ID = "61166BD7E15245A4BA394B0249F44110";

  private static final String SYSTEM = "0";
  private static final String SYSTEM_USER = "100";
  private static final String LANGUAGE = "en_US";
  private static final String CHANNEL_SCHEDULER = "Process Scheduler";
  private static final String TIMING_SCHEDULED = "S";
  private static final String FREQUENCY_MINUTELY = "2";
  static final long INTERVAL_MINUTES = 5L;
  private static final long SCHEDULER_POLL_MS = 500L;
  private static final long SCHEDULER_WAIT_TIMEOUT_MS = 120_000L;

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected String name() {
    return "TenantPoolScheduleStartup";
  }

  @Override
  protected void runPass() {
    if (ensureScheduled()) {
      registerWithScheduler();
    }
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
        log.info("TenantPoolScheduleStartup: scheduler still inactive; the filler starts on the "
            + "next restart");
        return;
      }
      scheduler.schedule(REQUEST_ID, ProcessBundle.request(REQUEST_ID, systemVars(),
          scheduler.getConnection()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.warn("TenantPoolScheduleStartup: could not register the filler with the scheduler now; "
          + "OBScheduler picks it up on the next restart", e);
    }
  }

  /** Visible for testing: the synchronous idempotent pass. */
  boolean ensureScheduled() {
    OBContext.setAdminMode(true);
    try {
      if (activeScheduledRequestExists()) {
        return false;
      }
      Process process = OBDal.getInstance().get(Process.class, FILL_PROCESS_ID);
      if (process == null) {
        log.warn("TenantPoolScheduleStartup: process {} not found; skipping", FILL_PROCESS_ID);
        return false;
      }
      insertSystemRequest(process);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      log.info("TenantPoolScheduleStartup: scheduled the tenant pool filler every {} min",
          INTERVAL_MINUTES);
      return true;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  boolean activeScheduledRequestExists() {
    return !OBDal.getInstance().createQuery(ProcessRequest.class,
            "as pr where pr.process.id = :processId and pr.status = :status and pr.active = true")
        .setNamedParameter("processId", FILL_PROCESS_ID)
        .setNamedParameter("status", org.openbravo.scheduling.Process.SCHEDULED)
        .setFilterOnReadableClients(false)
        .setMaxResult(1)
        .list()
        .isEmpty();
  }

  private void insertSystemRequest(Process process) {
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
    request.setIntervalInMinutes(INTERVAL_MINUTES);
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
