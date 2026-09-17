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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
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
 * "Costing Background process" ({@code org.openbravo.costing.CostingBackground}) every 30 seconds,
 * so material transactions get their cost calculated without anyone opening Classic (ETP-5190,
 * cadence lowered from 5 minutes to 30 seconds by ETP-5370).
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
 * <p><b>The 30-second cadence, and why the columns are the ones they are.</b> {@code FREQUENCY='1'}
 * is {@link org.openbravo.scheduling.Frequency#SECONDLY} and pairs with {@code SECONDLY_INTERVAL};
 * timing {@code 'S'} + frequency {@code '1'} is the {@code "S1"} key that resolves to Quartz's
 * {@code repeatSecondlyForever} in {@code TriggerProvider} (no {@code SECONDLY_REPETITIONS} means no
 * repetition cap). This shape is not invented here: GOClient has been running exactly it since
 * 2026-04-08, configured by hand in Classic's Process Request window and dumped into
 * {@code referencedata/sampledata/GOClient/AD_PROCESS_REQUEST.xml} — {@code S | 1 | 30}, with
 * {@code MINUTELY_INTERVAL} absent and the inert {@code DAILY_INTERVAL=1} / {@code DAILY_OPTION='N'}
 * defaults the Classic window writes whatever frequency is selected.
 *
 * <p><b>Leave {@code MINUTELY_INTERVAL} unset.</b> The scheduler switches on {@code FREQUENCY} and
 * reads ONLY the matching interval column, so a leftover minutely value is inert rather than
 * contradictory — but it produces a row that matches no hand-made one, which is the hard part to
 * diagnose later. ETP-5370 lowered this from the previous 5-minute shape ({@code '2'} +
 * {@code MINUTELY_INTERVAL=5}); {@code StoredColumnQueueScheduleStartup} still uses that shape for
 * the queue drain, deliberately — it is a different process and its cadence was not in scope.
 *
 * <p>Creating the row and activating it in Quartz are split, the same shape
 * {@code SiiTbaiAutoSendScheduleService} uses: {@link #scheduleCostingBackground} runs inside the
 * onboarding transaction so it commits atomically with the rest of provisioning, and
 * {@link #activateSchedule(String)} is called AFTER that commit, once the row is visible to the
 * scheduler's own DB connection. Both halves are non-fatal — a costing schedule is worth having, but
 * never worth failing an environment creation over — and the {@code SCH} status means an activation
 * that could not run is still picked up on the next scheduler initialization.
 *
 * <p><b>Both fronts now exist, and the corrective one is NOT SQL.</b> ETP-5190 shipped this
 * preventive half alone; ETP-5245 later added the corrective data-fix
 * {@code cli/src/data-fixes/sql/20260910T120000Z__R36-costing-background-schedule.sql}, which
 * CREATES the missing request for already-onboarded tenants. ETP-5370's corrective half — realigning
 * the cadence of requests that already exist — could NOT be another {@code .sql}: see
 * {@link #realignCadence()}. Production does not restart Tomcat, and an {@code UPDATE} to this table
 * is invisible to an already-armed Quartz trigger, so the correction has to run in the live JVM.
 *
 * <p><b>Deliberate duplication.</b> This is the second per-client scheduled-request builder left in
 * the module ({@code SiiTbaiAutoSendScheduleService} is the other; a third,
 * {@code OnboardingBankConnectionSyncService}, was removed by ETP-5275) and it repeats its
 * {@code resolveProcess}/{@code findExistingRequest}/{@code buildObContext} shape rather than
 * sharing a base class. That is a decision, not an oversight: the two differ in frequency shape
 * (secondly / hourly), in idempotency scope (client / client+org) and in lifecycle (onboarding step
 * / handler hook), and extracting a common parent would mean touching a service that is stable and
 * covered. Extracting it is worth its own ticket once a third appears again.
 */
public class OnboardingCostingScheduleService {

  private static final Logger log = LogManager.getLogger(OnboardingCostingScheduleService.class);

  /** Search key of the core "Costing Background process" AD_Process (module 0, always present). */
  public static final String COSTING_PROCESS_KEY = "CostingBackground";

  private static final String STATUS_SCHEDULED = "SCH";
  /** {@code org.openbravo.scheduling.Process.UNSCHEDULED} — what Classic's Unschedule action writes. */
  static final String STATUS_UNSCHEDULED = org.openbravo.scheduling.Process.UNSCHEDULED;
  /** Timing option "Schedule" — recurring, as opposed to {@code 'I'} (immediate) / {@code 'L'} (later). */
  private static final String TIMING_SCHEDULED = "S";
  /** Frequency "Every n seconds" — {@link org.openbravo.scheduling.Frequency#SECONDLY}. */
  static final String FREQUENCY_SECONDLY = "1";
  /** Run every 30 seconds. Left with no repetition cap so the trigger repeats forever. */
  static final Long INTERVAL_SECONDS = 30L;
  private static final String CHANNEL_SCHEDULER = "Process Scheduler";
  private static final String DEFAULT_LANGUAGE = "en_US";
  private static final String DESCRIPTION =
      "Automatic cost calculation (Etendo GO onboarding)";

  /** Seconds in the cadence, i.e. the width of the window the start instant is spread over. */
  private static final int CADENCE_SECONDS = INTERVAL_SECONDS.intValue();

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * Creates the per-client 30-second costing schedule if it does not already exist. Runs inside the
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
      log.info("Created costing schedule {} for client {} (every {} s)", request.getId(), clientId,
          INTERVAL_SECONDS);
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
    request.setFrequency(FREQUENCY_SECONDLY);
    request.setIntervalInSeconds(INTERVAL_SECONDS);
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
   * Start instant, offset by a random number of seconds inside the 30-second cadence.
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

  // ─── ETP-5370: corrective cadence realignment ────────────────────────────────────────────────

  /**
   * Brings every existing tenant to the invariant this class provisions for new ones: <b>exactly
   * ONE active scheduled {@code CostingBackground} request per client, running every 30 seconds</b>.
   *
   * <p><b>Why this is Java and not a data-fix {@code .sql}.</b> Production does not restart Tomcat,
   * and an {@code UPDATE} on {@code AD_PROCESS_REQUEST} is invisible to a trigger that is already
   * armed: {@code OBScheduler.initialize()} reads that table exactly once, at Quartz startup, and
   * {@code DefaultJob.execute} rebuilds its bundle from Quartz's own {@code JobDataMap} — it never
   * re-reads the row. Deactivating or even DELETING the row does not stop the job either (the PSD2
   * schedule-removal data-fix documents that same finding). The cadence of a live trigger can only
   * be changed from inside the running JVM, which is what this method does.
   *
   * <p><b>The surviving row is ALWAYS re-armed</b>, even when its columns already read
   * {@code '1'}/30. The row is not evidence about the live trigger — that is the whole lesson above
   * — so re-arming is the only thing that actually guarantees the invariant. {@link
   * OBScheduler#reschedule} is used rather than {@link OBScheduler#schedule} because the latter is a
   * no-op when the Quartz job already exists; reschedule unschedules and deletes it first.
   *
   * <p>Per client: the most recently created active {@code SCH} request wins (ties broken by id, so
   * the choice is deterministic) — it is the one this service or the ETP-5245 data-fix provisioned
   * with a resolved, verified {@code ob_context} for that tenant, whereas older ones are legacy
   * artefacts. The losers are unscheduled from Quartz and marked {@code UNS} + inactive; they are
   * never deleted. Rows in status {@code COM} are execution history, not schedules, and are ignored.
   *
   * <p>Best-effort per client: one tenant's failure is recorded and the sweep continues.
   *
   * @param clientIdFilter a single client to act on, or {@code null} to sweep every tenant
   * @return what happened, per client, for the caller to report
   */
  public RealignReport realignCadence(String clientIdFilter) {
    return realignCadence(clientIdFilter, Collections.emptySet());
  }

  /**
   * As {@link #realignCadence(String)}, but skipping tenants that have already been migrated.
   *
   * <p>This is what makes the one-shot startup sweep one-shot. The unconditional form above stays
   * the webhook's entry point on purpose: an operator asking for ONE tenant to be corrected means
   * it, and must not be silently ignored because a marker says the migration already ran.
   *
   * <p><b>Why skipping matters beyond saving work.</b> Converging every tenant to 30s on every boot
   * is right for a migration and wrong as a standing policy: the day the product lets a user choose
   * their own costing frequency, a permanent sweep would silently reset that choice on the next
   * deploy. Skipping already-migrated tenants is what keeps this a migration.
   *
   * @param clientIdFilter a single client to act on, or {@code null} to sweep every tenant
   * @param skipClientIds  clients to leave untouched (already migrated); never {@code null}
   * @return what happened, per client, for the caller to report and to mark as migrated. Skipped
   *     clients produce no outcome at all, so an empty report means there was nothing left to do.
   */
  public RealignReport realignCadence(String clientIdFilter, Set<String> skipClientIds) {
    RealignReport report = new RealignReport();
    OBContext.setAdminMode(true);
    try {
      if (!schedulingAllowed()) {
        // Reporting success on a standby node would be a lie: schedule/reschedule silently no-op
        // there. Same stance as SFAcctProcessMonitor's manual trigger.
        report.schedulerAvailable = false;
        return report;
      }
      Process process = resolveProcess(COSTING_PROCESS_KEY);
      if (process == null) {
        log.warn("Costing process '{}' not found — nothing to realign", COSTING_PROCESS_KEY);
        return report;
      }
      for (Map.Entry<String, List<String>> entry
          : scheduledRequestIdsByClient(process, clientIdFilter).entrySet()) {
        if (skipClientIds.contains(entry.getKey())) {
          continue;
        }
        realignClient(entry.getKey(), entry.getValue(), report);
      }
      return report;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Active {@code SCH} request ids of the costing process, grouped by client, each client's list
   * ordered with the winner first (newest creation date, then id).
   *
   * <p>Only ids are collected, never the entities: {@link #realignClient} commits per client, which
   * closes the DAL session the query ran in, so every row is re-fetched by id when it is about to be
   * touched. {@code setFilterOnReadableClients(false)} is what makes this cross-tenant — the same
   * escape {@code StoredColumnQueueScheduleStartup} uses for its per-process idempotency probe.
   */
  private Map<String, List<String>> scheduledRequestIdsByClient(Process process,
      String clientIdFilter) {
    OBCriteria<ProcessRequest> criteria = OBDal.getInstance().createCriteria(ProcessRequest.class);
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_PROCESS, process));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_STATUS, STATUS_SCHEDULED));
    if (clientIdFilter != null) {
      criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_CLIENT + ".id", clientIdFilter));
    }
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.addOrderBy(ProcessRequest.PROPERTY_CREATIONDATE, false);
    criteria.addOrderBy(ProcessRequest.PROPERTY_ID, false);

    Map<String, List<String>> byClient = new LinkedHashMap<>();
    for (ProcessRequest request : criteria.list()) {
      byClient.computeIfAbsent(request.getClient().getId(), k -> new ArrayList<>())
          .add(request.getId());
    }
    return byClient;
  }

  /**
   * Applies the invariant to one client: losers unscheduled and deactivated, winner set to the
   * 30-second shape, committed, and then re-armed in Quartz. The commit has to happen BEFORE the
   * re-arm — {@code TriggerProvider} reads the timing columns through the scheduler's own JDBC
   * connection, which cannot see an uncommitted row (the same ordering {@link #activateSchedule}
   * depends on).
   */
  private void realignClient(String clientId, List<String> requestIds, RealignReport report) {
    String winnerId = requestIds.get(0);
    // Outside the try on purpose: a Quartz unschedule already committed on the scheduler's own
    // connection cannot be rolled back with the DAL transaction, so a failure must still report
    // how many extras were actually taken down rather than a comforting zero.
    int deactivated = 0;
    try {
      for (String loserId : requestIds.subList(1, requestIds.size())) {
        deactivateExtra(loserId);
        deactivated++;
      }
      boolean rowChanged = applyCadence(winnerId);
      OBDal.getInstance().flush();
      clearNextFireTime(winnerId);
      OBDal.getInstance().commitAndClose();

      rearm(winnerId);

      String clientName = OBDal.getInstance().get(Client.class, clientId).getName();
      report.outcomes.add(new ClientOutcome(clientId, clientName,
          rowChanged || deactivated > 0 ? ClientOutcome.REALIGNED : ClientOutcome.ALREADY_CORRECT,
          winnerId, deactivated, null));
      log.info("Realigned costing schedule for client {}: winner {} ({}), {} extra request(s) "
          + "deactivated", clientId, winnerId, rowChanged ? "updated" : "already 30s", deactivated);
    } catch (Exception e) {
      // One tenant must never abort the sweep. Roll back so the next client starts clean.
      log.error("Could not realign the costing schedule for client {}", clientId, e);
      try {
        OBDal.getInstance().rollbackAndClose();
      } catch (Exception rollbackError) {
        log.debug("Rollback after realign failure also failed for client {}", clientId,
            rollbackError);
      }
      report.outcomes.add(new ClientOutcome(clientId, null, ClientOutcome.FAILED, winnerId,
          deactivated, e.getMessage() != null ? e.getMessage() : e.toString()));
    }
  }

  /**
   * Writes the 30-second shape onto the surviving request, clearing {@code MINUTELY_INTERVAL} so the
   * row matches a hand-made one (the scheduler reads only the column matching {@code FREQUENCY}, so
   * a leftover minutely value is inert but misleading).
   *
   * @return {@code true} when the row actually needed changing
   */
  private boolean applyCadence(String requestId) {
    ProcessRequest request = OBDal.getInstance().get(ProcessRequest.class, requestId);
    boolean alreadyCorrect = FREQUENCY_SECONDLY.equals(request.getFrequency())
        && INTERVAL_SECONDS.equals(request.getIntervalInSeconds())
        && request.getIntervalInMinutes() == null;
    if (!alreadyCorrect) {
      request.setTiming(TIMING_SCHEDULED);
      request.setFrequency(FREQUENCY_SECONDLY);
      request.setIntervalInSeconds(INTERVAL_SECONDS);
      request.setIntervalInMinutes(null);
    }
    // ALWAYS restated, even when the cadence columns were already right: together with
    // clearNextFireTime below this is what makes the re-armed trigger start NOW instead of
    // inheriting the old trigger's schedule. Reuses the provisioning path's own helpers so a
    // realigned row ends up shaped exactly like a freshly provisioned one, jitter included.
    request.setStartDate(startDate());
    request.setStartTime(spreadStartTime());
    OBDal.getInstance().save(request);
    return !alreadyCorrect;
  }

  /**
   * Nulls {@code NEXT_FIRE_TIME}, which is the single most important line in this whole routine and
   * the one thing live verification caught that no unit test could.
   *
   * <p>{@code ScheduledTriggerGenerator#getBuilder} does NOT start a rebuilt trigger from the
   * request's start boundary when the row carries a next fire time — it starts it AT that instant:
   *
   * <pre>
   *   if (StringUtils.isEmpty(data.nextFireTime)) { builder.startAt(getStartDate(data)); }
   *   else                                        { builder.startAt(getNextFireDate(data)); }
   * </pre>
   *
   * That column still holds the OLD trigger's next fire — five minutes out, in the case this was
   * found on. So a re-arm that leaves it in place produces a trigger that is correct but DORMANT
   * until the old cadence's next tick, and only then starts repeating every 30 seconds. Measured:
   * the webhook ran at 19:55:02, the row read {@code 1|30} immediately, and the process did not run
   * once until 19:58:55 — the stale next fire — after which the 30-second cadence held exactly.
   * Harmless at a 5-minute old cadence; a daily old cadence would have left the job idle for a day.
   *
   * <p>Done in native SQL because {@code NEXT_FIRE_TIME} is deliberately NOT mapped on the
   * {@link ProcessRequest} entity — it is scheduler bookkeeping, written by {@code ProcessMonitor}
   * through {@code ProcessRequestData}'s XSQL, so there is no setter to call. Must run AFTER the DAL
   * flush and BEFORE the commit, so it lands in the same transaction as the cadence columns.
   */
  private void clearNextFireTime(String requestId) {
    NativeQuery<?> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(
            "UPDATE ad_process_request SET next_fire_time = NULL WHERE ad_process_request_id = :id");
    query.setParameter("id", requestId);
    query.executeUpdate();
  }

  /**
   * Removes a redundant schedule: live Quartz trigger first, then the row marked {@code UNS} and
   * inactive. That order matters — {@link OBScheduler#unschedule} writes the status through its OWN
   * JDBC connection, so doing it before the DAL write keeps the two off the same locked row. The row
   * is kept (never deleted) so the history stays auditable; {@code UNS} + inactive is enough to stop
   * the next {@code OBScheduler.initialize()} from picking it up again.
   */
  private void deactivateExtra(String requestId) {
    ProcessRequest extra = OBDal.getInstance().get(ProcessRequest.class, requestId);
    unscheduleFromQuartz(requestId, extra.getOpenbravoContext());
    extra.setStatus(STATUS_UNSCHEDULED);
    extra.setActive(false);
    OBDal.getInstance().save(extra);
  }

  /** Best-effort removal of a live Quartz trigger; a failure still leaves the row deactivated. */
  private void unscheduleFromQuartz(String requestId, String openbravoContext) {
    try {
      ProcessContext context = ProcessContext.newInstance(openbravoContext);
      if (context == null) {
        log.warn("Costing schedule {} has no usable stored OpenbravoContext — skipping the live "
            + "Quartz unschedule (the row is still deactivated)", requestId);
        return;
      }
      OBScheduler.getInstance().unschedule(requestId, context);
    } catch (Exception e) {
      log.warn("Could not unschedule costing request {} from the live scheduler: {}", requestId,
          e.getMessage());
    }
  }

  /**
   * Re-arms the surviving request's Quartz trigger from the freshly committed row. Package-visible
   * so tests can override it and keep their assertions off the real scheduler.
   */
  void rearm(String requestId) {
    ProcessRequest request = OBDal.getInstance().get(ProcessRequest.class, requestId);
    ProcessContext context = ProcessContext.newInstance(request.getOpenbravoContext());
    if (context == null) {
      // A row whose stored context is missing or unparseable cannot be re-armed at all: the
      // scheduler rebuilds the run's security variables from it. Fail loudly with a message that
      // names the row, rather than letting a bare NPE surface as the client's failure detail.
      throw new IllegalStateException(
          "Costing request " + requestId + " has no usable stored OpenbravoContext");
    }
    DalConnectionProvider conn = new DalConnectionProvider(false);
    try {
      OBScheduler.getInstance()
          .reschedule(requestId, ProcessBundle.request(requestId, context.toVars(), conn));
    } catch (Exception e) {
      throw new IllegalStateException("Could not re-arm costing request " + requestId, e);
    }
  }

  /** {@link OBScheduler#isSchedulingAllowed()} with its checked exception treated as "no". */
  boolean schedulingAllowed() {
    try {
      return OBScheduler.getInstance().isSchedulingAllowed();
    } catch (Exception e) {
      log.warn("Could not determine whether scheduling is allowed: {}", e.getMessage());
      return false;
    }
  }

  /** What {@link #realignCadence(String)} did, for the caller to turn into a response. */
  public static final class RealignReport {
    private boolean schedulerAvailable = true;
    private final List<ClientOutcome> outcomes = new ArrayList<>();

    public boolean isSchedulerAvailable() {
      return schedulerAvailable;
    }

    public List<ClientOutcome> getOutcomes() {
      return Collections.unmodifiableList(outcomes);
    }
  }

  /** One client's result. */
  public static final class ClientOutcome {
    public static final String REALIGNED = "realigned";
    public static final String ALREADY_CORRECT = "alreadyCorrect";
    public static final String FAILED = "failed";

    private final String clientId;
    private final String clientName;
    private final String status;
    private final String requestId;
    private final int deactivated;
    private final String detail;

    ClientOutcome(String clientId, String clientName, String status, String requestId,
        int deactivated, String detail) {
      this.clientId = clientId;
      this.clientName = clientName;
      this.status = status;
      this.requestId = requestId;
      this.deactivated = deactivated;
      this.detail = detail;
    }

    public String getClientId() {
      return clientId;
    }

    public String getClientName() {
      return clientName;
    }

    public String getStatus() {
      return status;
    }

    public String getRequestId() {
      return requestId;
    }

    public int getDeactivated() {
      return deactivated;
    }

    public String getDetail() {
      return detail;
    }
  }
}
