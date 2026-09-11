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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.onboarding;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Provisions, per onboarded client, a scheduled {@code AD_Process_Request} that runs the core
 * "Costing Background process" ({@code org.openbravo.costing.CostingBackground}) every 5 minutes,
 * so material transactions get their cost calculated without anyone opening Classic (ETP-5190).
 *
 * <p><b>Why a new tenant needs this.</b> Onboarding already imports a VALIDATED costing rule —
 * {@code M_COSTING_RULE} is on {@link OnboardingDatasetDefinition}'s allowlist and the GOClient
 * sampledata row ships {@code ISVALIDATED='Y'} — but nothing ever ran the process that consumes it.
 * Costs therefore stayed uncalculated on every tenant: at the time of writing the instance held 83
 * validated costing rules across 74 clients and exactly TWO {@code CostingBackground} requests, both
 * created by hand (core's own F&B demo client, and GOClient). The PSD2 statement-sync schedule, which
 * onboarding DID provision at the time (that step was later removed by ETP-5275), sat at 74/74 over
 * the same period — that contrast is the whole argument for doing this here instead of by hand.
 *
 * <p><b>Not solvable with sampledata.</b> {@code referencedata/sampledata/GOClient/
 * AD_PROCESS_REQUEST.xml} does contain a {@code CostingBackground} row, which makes it look as
 * though new tenants are covered. They are not: {@code AD_PROCESS_REQUEST} is on
 * {@link OnboardingDatasetDefinition}'s EXCLUDED list and is never imported — correctly so, because
 * every dumped row carries an {@code OB_CONTEXT} JSON with GOClient's own user, role, client, org and
 * warehouse ids hardcoded inside it. A Process Request is instance data, not model data (the same
 * reasoning {@code StoredColumnQueueScheduleStartup} states for shipping no XML at all). It has to be
 * built for the tenant being created, which is what {@link #buildObContext} does.
 *
 * <p><b>Per-client scope, unlike the queue drainer.</b> {@code StoredColumnQueueScheduleStartup}
 * covers every tenant with ONE System ({@code client '0'}) request because its processor is
 * client-position-independent. {@code CostingBackground} is the opposite: it resolves the
 * organizations to cost with {@code ad_isorgincluded(o.id, :orgId, :clientId)}, binding
 * {@code :clientId} to {@code bundle.getContext().getClient()} and {@code :orgId} to the request's
 * organization. A System request would match only org {@code '0'} and silently cost nothing, so this
 * has to be one request per client — which is also why it belongs in onboarding.
 *
 * <p><b>The 5-minute cadence is the platform's own.</b> {@code FREQUENCY='2'} is
 * {@link org.openbravo.scheduling.Frequency#MINUTELY} and pairs with {@code MINUTELY_INTERVAL};
 * timing {@code 'S'} + frequency {@code '2'} is the {@code "S2"} key that resolves to Quartz's
 * {@code repeatMinutelyForever} in {@code TriggerProvider}. Core's F&B demo client has shipped this
 * exact shape (minutely, interval 5) since 2013, and {@code StoredColumnQueueScheduleStartup} uses it
 * for the queue drain — so 5 is the conventional value here, not a number invented for this ticket.
 *
 * <p>Creating the row and activating it in Quartz are split, the same shape
 * {@code SiiTbaiAutoSendScheduleService} uses: {@link #scheduleCostingBackground} runs inside the
 * onboarding transaction so it commits atomically with the rest of provisioning, and
 * {@link #activateSchedule(String)} is called AFTER that commit, once the row is visible to the
 * scheduler's own DB connection. Both halves are non-fatal — a costing schedule is worth having, but
 * never worth failing an environment creation over — and the {@code SCH} status means an activation
 * that could not run is still picked up on the next scheduler initialization.
 *
 * <p><b>Preventive only, by decision.</b> This covers tenants created from here on. The codebase's
 * usual two-front pattern would pair it with a corrective data-fix under {@code cli/src/data-fixes/}
 * for the clients already onboarded without the schedule; that half was explicitly declined
 * (ETP-5190) — new tenants are enough. So do not read the absence of a data-fix here as an
 * oversight, and note the consequence: an already-onboarded client calculates no costs until someone
 * schedules the process for it by hand in Classic's Process Request window.
 *
 * <p><b>Deliberate duplication.</b> This is the second per-client scheduled-request builder left in
 * the module ({@code SiiTbaiAutoSendScheduleService} is the other; a third,
 * {@code OnboardingBankConnectionSyncService}, was removed by ETP-5275) and it repeats its
 * {@code resolveProcess}/{@code findExistingRequest}/{@code buildObContext} shape rather than
 * sharing a base class. That is a decision, not an oversight: the two differ in frequency shape
 * (minutely / hourly), in idempotency scope (client / client+org) and in lifecycle (onboarding step
 * / handler hook), and extracting a common parent would mean touching a service that is stable and
 * covered. Extracting it is worth its own ticket once a third appears again.
 */
public class OnboardingCostingScheduleService {

  private static final Logger log = LogManager.getLogger(OnboardingCostingScheduleService.class);

  /** Search key of the core "Costing Background process" AD_Process (module 0, always present). */
  public static final String COSTING_PROCESS_KEY = "CostingBackground";

  private static final String STATUS_SCHEDULED = "SCH";
  /** Timing option "Schedule" — recurring, as opposed to {@code 'I'} (immediate) / {@code 'L'} (later). */
  private static final String TIMING_SCHEDULED = "S";
  /** Frequency "Every n minutes" — {@link org.openbravo.scheduling.Frequency#MINUTELY}. */
  private static final String FREQUENCY_MINUTELY = "2";
  /** Run every 5 minutes. Left with no repetition cap so the trigger repeats forever. */
  private static final Long INTERVAL_MINUTES = 5L;
  private static final String CHANNEL_SCHEDULER = "Process Scheduler";
  private static final String DEFAULT_LANGUAGE = "en_US";
  private static final String DESCRIPTION =
      "Automatic cost calculation (Etendo GO onboarding)";

  /** Seconds in the cadence, i.e. the width of the window the start instant is spread over. */
  private static final int CADENCE_SECONDS = (int) (INTERVAL_MINUTES * 60);

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * Creates the per-client 5-minute costing schedule if it does not already exist. Runs inside the
   * onboarding transaction; the row is flushed but committed by the caller. Idempotent: a second
   * onboarding of the same client reuses the existing request.
   *
   * @param clientId    target client identifier
   * @param orgId       organization the request is owned by, and the root of the org subtree the
   *                    process will cost (see the class comment on {@code ad_isorgincluded})
   * @param adminUserId administrator user the scheduled process runs as
   * @param adminRoleId administrator role used for the process security context
   * @return the AD_Process_Request id, or {@code null} when the costing process cannot be resolved
   */
  public String scheduleCostingBackground(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    OBContext.setAdminMode(true);
    try {
      Process process = resolveProcess(COSTING_PROCESS_KEY);
      if (process == null) {
        // Core process, so this is unexpected — it means the AD is partially updated. Stay
        // non-fatal: skip rather than block onboarding.
        log.warn("Costing process '{}' not found — skipping costing schedule for client {}",
            COSTING_PROCESS_KEY, clientId);
        return null;
      }
      ProcessRequest existing = findExistingRequest(clientId, process);
      if (existing != null) {
        log.debug("Costing schedule already exists for client {} — skipping", clientId);
        return existing.getId();
      }
      ProcessRequest request = buildRequest(clientId, orgId, adminUserId, adminRoleId, process);
      OBDal.getInstance().save(request);
      OBDal.getInstance().flush();
      log.info("Created costing schedule {} for client {} (every {} min)", request.getId(), clientId,
          INTERVAL_MINUTES);
      return request.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Registers an already-created schedule with the Quartz scheduler so it becomes active without a
   * server restart. Must be called AFTER the onboarding transaction commits (the scheduler's
   * connection cannot see uncommitted rows). Best-effort: any failure is logged and swallowed
   * because the {@code SCH} row is still picked up on the next scheduler initialization.
   *
   * @param clientId the client whose schedule should be activated
   */
  public void activateSchedule(String clientId) {
    OBContext.setAdminMode(true);
    try {
      Process process = resolveProcess(COSTING_PROCESS_KEY);
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
      log.info("Activated costing schedule {} for client {}", requestId, clientId);
    } catch (Exception e) {
      log.warn("Could not immediately activate the costing schedule for client {} (it will be "
          + "picked up on the next scheduler initialization): {}", clientId, e.getMessage());
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
    request.setFrequency(FREQUENCY_MINUTELY);
    request.setIntervalInMinutes(INTERVAL_MINUTES);
    request.setStatus(STATUS_SCHEDULED);
    request.setFinishes(false);
    request.setActive(true);

    request.setStartDate(startDate());
    request.setStartTime(spreadStartTime());
    request.setOpenbravoContext(buildObContext(clientId, orgId, adminUserId, adminRoleId));
    return request;
  }

  /**
   * Builds the serialized execution context stored on the request so the scheduler can rebuild the
   * security variables (user/role/client/org/language) when it fires — both on immediate activation
   * and on startup pickup. This is also what makes the request client-scoped at run time: the
   * costing process reads its client and organization from here.
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
   * Start instant, offset by a random number of seconds inside the 5-minute cadence.
   *
   * <p>Every tenant of an instance shares one Quartz scheduler, so 74 costing jobs all phased to the
   * same second would bunch every DB round of cost calculation into the same moment. Onboarding
   * already spreads them naturally (each tenant is created at a different instant) — this offset is
   * for the case that does not: a corrective bulk fix creating the missing schedules for existing
   * tenants in one pass.
   *
   * <p>Best-effort by nature, not a guarantee: a Quartz simple trigger whose start instant is in the
   * past realigns its phase to the first fire under the default misfire policy. It costs nothing when
   * it does not help, which is why it is here rather than argued about.
   */
  protected Timestamp spreadStartTime() {
    LocalDateTime start = LocalDateTime.now().withNano(0).plusSeconds(RANDOM.nextInt(CADENCE_SECONDS));
    return Timestamp.valueOf(start);
  }
}
