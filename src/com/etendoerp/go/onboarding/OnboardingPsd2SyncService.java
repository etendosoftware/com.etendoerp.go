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
package com.etendoerp.go.onboarding;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.scheduling.OBScheduler;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessContext;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Provisions, per onboarded client, a daily scheduled {@code AD_Process_Request} that runs the PSD2
 * "Get Bank Statements" process so the statements of every Salt Edge-connected financial account
 * are imported automatically.
 *
 * <p>The schedule is created during onboarding (one request per client) and fires once a day at a
 * random time in the 03:00–06:00 window — the jitter spreads the load across tenants. The PSD2
 * process itself is global (it iterates every connected account regardless of client), so this
 * service does not scope it; a fresh job is simply a no-op for clients with no connected accounts.
 *
 * <p>The PSD2 module is a hard dependency of {@code com.etendoerp.go}, so the process is expected to
 * exist; it is resolved by its search key (the AD_Process is module sourcedata, not an importable
 * Java symbol, and its UUID must never be hardcoded). The defensive null-guard therefore only covers
 * an unconfigured/partially-updated database, and it stays non-fatal so it never blocks onboarding.
 * Creating the row and activating it in Quartz are split on purpose: the row is created inside the
 * onboarding transaction
 * (so it commits atomically with the rest), and {@link #activateSchedule(String)} is called
 * <em>after</em> that commit, when the row is visible to the scheduler's own DB connection. If the
 * immediate activation cannot run, the row's {@code SCH} status means Etendo's scheduler still picks
 * it up on the next initialization.
 */
public class OnboardingPsd2SyncService {

  private static final Logger log = LogManager.getLogger(OnboardingPsd2SyncService.class);

  /** Search key of the PSD2 "Get Bank Statements" AD_Process (module com.etendoerp.psd2.bank.integration). */
  static final String PSD2_PROCESS_KEY = "PSD2_GetBankStatements";

  private static final String STATUS_SCHEDULED = "SCH";
  private static final String TIMING_SCHEDULED = "S";
  private static final String FREQUENCY_DAILY = "4";
  private static final String DAILY_OPTION_EVERY_N = "N";
  private static final String CHANNEL_SCHEDULER = "Process Scheduler";
  private static final String DEFAULT_LANGUAGE = "en_US";
  private static final String DESCRIPTION =
      "PSD2 automatic bank statement synchronization (Etendo GO onboarding)";

  /** Sync window lower bound (inclusive), in hours. */
  private static final int SYNC_WINDOW_START_HOUR = 3;
  /** Number of whole hours in the sync window: 03:00–06:00 → hours {3, 4, 5}. */
  private static final int SYNC_WINDOW_HOURS = 3;
  private static final int MINUTES_PER_HOUR = 60;

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * Creates the per-client daily PSD2 statement-sync schedule if it does not already exist. Runs
   * inside the onboarding transaction; the row is flushed but committed by the caller. Idempotent:
   * a second onboarding of the same client reuses the existing request.
   *
   * @param clientId    target client identifier
   * @param orgId       organization identifier the request is owned by
   * @param adminUserId administrator user the scheduled process runs as
   * @param adminRoleId administrator role used for the process security context
   * @return the AD_Process_Request id, or {@code null} when the PSD2 process is not installed
   */
  public String schedulePsd2StatementSync(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    OBContext.setAdminMode(true);
    try {
      Process process = resolveProcess(PSD2_PROCESS_KEY);
      if (process == null) {
        // PSD2 is a hard dependency, so this is unexpected (e.g. the PSD2 dataset has not been
        // applied yet via update.database). Stay non-fatal: skip rather than block onboarding.
        log.warn("PSD2 process '{}' not found — skipping statement-sync schedule for client {}",
            PSD2_PROCESS_KEY, clientId);
        return null;
      }
      ProcessRequest existing = findExistingRequest(clientId, process);
      if (existing != null) {
        log.debug("PSD2 statement-sync request already exists for client {} — skipping", clientId);
        return existing.getId();
      }
      ProcessRequest request = buildRequest(clientId, orgId, adminUserId, adminRoleId, process);
      OBDal.getInstance().save(request);
      OBDal.getInstance().flush();
      log.info("Created PSD2 statement-sync schedule {} for client {}", request.getId(), clientId);
      return request.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Registers an already-created schedule with the Quartz scheduler so it becomes active without a
   * server restart. Must be called <em>after</em> the onboarding transaction commits (the
   * scheduler's connection cannot see uncommitted rows). Best-effort: any failure is logged and
   * swallowed because the {@code SCH} row is still picked up on the next scheduler initialization.
   *
   * @param clientId the client whose schedule should be activated
   */
  public void activateSchedule(String clientId) {
    OBContext.setAdminMode(true);
    try {
      Process process = resolveProcess(PSD2_PROCESS_KEY);
      if (process == null) {
        return;
      }
      ProcessRequest request = findExistingRequest(clientId, process);
      if (request == null) {
        return;
      }
      String requestId = request.getId();
      VariablesSecureApp vars = ProcessContext.newInstance(request.getOpenbravoContext()).toVars();
      DalConnectionProvider conn = new DalConnectionProvider(false);
      OBScheduler.getInstance().schedule(requestId, ProcessBundle.request(requestId, vars, conn));
      log.info("Activated PSD2 statement-sync schedule {} for client {}", requestId, clientId);
    } catch (Exception e) {
      log.warn("Could not immediately activate PSD2 schedule for client {} (it will be picked up "
          + "on the next scheduler initialization): {}", clientId, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  protected ProcessRequest buildRequest(String clientId, String orgId, String adminUserId,
      String adminRoleId, Process process) {
    ProcessRequest request = OBProvider.getInstance().get(ProcessRequest.class);
    request.setClient(OBDal.getInstance().get(Client.class, clientId));
    request.setOrganization(OBDal.getInstance().get(Organization.class, orgId));
    request.setUserContact(OBDal.getInstance().get(User.class, adminUserId));
    request.setProcess(process);
    request.setDescription(DESCRIPTION);
    request.setChannel(CHANNEL_SCHEDULER);
    request.setSecurityBasedOnRole(true);
    request.setTiming(TIMING_SCHEDULED);
    request.setFrequency(FREQUENCY_DAILY);
    request.setDailyInterval(1L);
    request.setDailyOption(DAILY_OPTION_EVERY_N);
    request.setStatus(STATUS_SCHEDULED);
    request.setFinishes(false);
    request.setActive(true);

    Date startDate = startDate();
    request.setStartDate(startDate);
    request.setStartTime(randomNightTime());
    request.setOpenbravoContext(buildObContext(clientId, orgId, adminUserId, adminRoleId));
    return request;
  }

  /**
   * Builds the serialized execution context stored on the request so the scheduler can rebuild the
   * security variables (user/role/client/org/language) when it fires — both on immediate activation
   * and on startup pickup.
   */
  protected String buildObContext(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    OBContext ctx = OBContext.getOBContext();
    String language = (ctx != null && ctx.getLanguage() != null)
        ? ctx.getLanguage().getLanguage() : DEFAULT_LANGUAGE;
    VariablesSecureApp vars = new VariablesSecureApp(adminUserId, clientId, orgId, adminRoleId,
        language);
    return new ProcessContext(vars).toString();
  }

  protected ProcessRequest findExistingRequest(String clientId, Process process) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    OBCriteria<ProcessRequest> criteria = OBDal.getInstance().createCriteria(ProcessRequest.class);
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_PROCESS, process));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (ProcessRequest) criteria.uniqueResult();
  }

  protected Process resolveProcess(String searchKey) {
    OBCriteria<Process> criteria = OBDal.getInstance().createCriteria(Process.class);
    criteria.add(Restrictions.eq(Process.PROPERTY_SEARCHKEY, searchKey));
    criteria.setMaxResults(1);
    return (Process) criteria.uniqueResult();
  }

  protected Date startDate() {
    return Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  /**
   * Returns a timestamp whose time component is a random instant in the 03:00–06:00 window. Only the
   * time-of-day is used by the daily trigger; the jitter spreads tenants' sync calls across the
   * window to avoid hammering the Salt Edge middleware at the same minute.
   */
  protected Timestamp randomNightTime() {
    int hour = SYNC_WINDOW_START_HOUR + RANDOM.nextInt(SYNC_WINDOW_HOURS);
    int minute = RANDOM.nextInt(MINUTES_PER_HOUR);
    LocalDateTime dateTime = LocalDate.now().atTime(LocalTime.of(hour, minute));
    return Timestamp.valueOf(dateTime);
  }
}
