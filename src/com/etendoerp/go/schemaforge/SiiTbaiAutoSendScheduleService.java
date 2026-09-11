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
package com.etendoerp.go.schemaforge;

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
 * Provisions, per fiscal configuration (SII / TicketBAI) saved through Etendo GO, a scheduled
 * {@code AD_Process_Request} that runs the corresponding invoice-sending process twice a day —
 * hourly frequency, 12-hour interval, starting at 11:00 (fires at 11:00 and 23:00) — mirroring
 * what an operator does by hand in Classic's Process Request window (Timing = Schedule,
 * Frequency = Hourly, Hourly Interval = 12, Start Time = 11:00:00, "Schedule Process") (ETP-5117).
 *
 * <p><b>GO-only scope (explicit human decision).</b> This service is invoked only from the
 * {@code sii-config-deactivate-handler} / {@code tbai-config-sequence-handler} {@link NeoHandler}
 * {@code afterHandle} hooks — i.e. only when the fiscal configuration is saved through Etendo GO.
 * Classic UI saves of {@code AEATSII_CONFIG}/{@code TBAI_Config} are intentionally NOT covered
 * (no Hibernate-level {@code EntityPersistenceEventObserver}); a config created only in Classic
 * gets no automatic schedule.
 *
 * <p><b>Per-organization scope.</b> SII and TicketBAI configurations are
 * inherently per-organization records ({@code AEATSII_CONFIG}/{@code TBAI_Config} both carry
 * their own {@code AD_Org_ID}, and the sending processes operate on that organization's invoices).
 * Idempotency is therefore scoped to client + organization + process, not client + process alone
 * — two different organizations of the same client configuring SII independently must each get
 * their own schedule, and re-saving the same organization's config must never create a second one.
 *
 * <p>The two AD_Process records (Grouped invoices SII sending process / RegisterTBAInvoice) are
 * resolved by search key — never hardcoded UUIDs, module sourcedata, defensive null-guard,
 * non-fatal — see {@link #resolveProcess}.
 *
 * <p>Creating the row and activating it in Quartz are split, same as the onboarding precedent:
 * {@link #ensureAutoSendSchedule} creates (or reuses) the row inside the caller's own request
 * transaction, and {@link #activateSchedule(String)} attempts to register it with the live
 * scheduler right after. Unlike the onboarding case — a multi-step orchestrated transaction with
 * an explicit post-commit callback — a {@link NeoHandler#afterHandle} hook has no separate
 * "after commit" callback to hang activation off. Activation is therefore attempted immediately,
 * best-effort: if the enclosing request's transaction has not yet committed by the time
 * {@link OBScheduler} queries the row on its own connection, activation simply fails silently
 * (caught, logged) and the row's {@code SCH} status means it is still picked up on the next
 * scheduler initialization — the exact same degraded-but-safe fallback the onboarding service
 * documents for its own activation failures.
 *
 * <p><b>Removal counterpart (ETP-5117 follow-up).</b> The original ETP-5117 delivery covered only
 * the creation side; nothing ever stopped a schedule once its fiscal config was deleted or
 * deactivated, so it kept firing the sending process twice a day forever. {@link
 * #unscheduleAutoSend} closes that gap, mirroring the create/activate split: it removes the live
 * Quartz trigger (best-effort, same degraded-but-safe reasoning as {@link #activateSchedule}) and
 * then deactivates the {@code AD_Process_Request} row itself (never deletes it — history/audit is
 * kept, matching this codebase's general preference for deactivation over deletion). It is called
 * from the same two {@code NeoHandler}s' <b>pre-hooks</b>, never from {@code afterHandle}: from
 * {@code smartDeactivate} when the incoming PUT explicitly sets {@code active=false} (covering
 * both of its outcomes — the fiscal config deleted outright because no invoices were ever sent
 * through it, and the fall-through to default CRUD that deactivates it while preserving its audit
 * trail), and from {@code beforeDelete} for a genuine HTTP {@code DELETE}. Running before the
 * record disappears is what lets the caller pass the config's <b>own</b> {@code clientId}/{@code
 * orgId}; the session's current organization is not a usable substitute, since a GO client-admin
 * role normally sits on organization {@code '0'} (the {@code '*'} org) and {@link
 * #findExistingRequest} would then match nothing and silently no-op.
 */
public class SiiTbaiAutoSendScheduleService {

  private static final Logger log = LogManager.getLogger(SiiTbaiAutoSendScheduleService.class);

  /** Search key of the SII "Grouped invoices SII sending process" AD_Process. */
  public static final String SII_PROCESS_SEARCH_KEY = "Grouped invoices SII sending process";

  /** Search key of the TicketBAI "Registrar facturas en TBAI" AD_Process. */
  public static final String TBAI_PROCESS_SEARCH_KEY = "RegisterTBAInvoice";

  private static final String STATUS_SCHEDULED = "SCH";
  /**
   * AD_Process_Request.Status value "Unscheduled" — mirrors
   * {@code org.openbravo.scheduling.Process.UNSCHEDULED}. Kept as a local literal, matching the
   * sibling {@link #STATUS_SCHEDULED} above, rather than importing the core constant: this class already imports
   * {@link org.openbravo.model.ad.ui.Process} for the AD entity, so referencing the scheduling
   * {@code Process} would need a fully-qualified name for one of the two constants only.
   */
  private static final String STATUS_UNSCHEDULED = "UNS";
  private static final String TIMING_SCHEDULED = "S";
  /** AD_Ref_List value "03 - Hourly" for AD_Process_Request.Frequency. */
  private static final String FREQUENCY_HOURLY = "3";
  private static final long HOURLY_INTERVAL = 12L;
  private static final int START_HOUR = 11;
  private static final String CHANNEL_SCHEDULER = "Process Scheduler";
  private static final String DEFAULT_LANGUAGE = "en_US";

  /**
   * Creates the per-client-per-organization twice-a-day auto-send schedule for {@code process}
   * if it does not already exist. Runs inside the caller's own transaction (typically a
   * {@link NeoHandler#afterHandle} hook); the row is flushed but committed by the caller/servlet.
   * Idempotent: re-saving the same organization's fiscal config reuses the existing request.
   *
   * @param clientId        target client identifier
   * @param orgId           organization identifier the fiscal config belongs to
   * @param userId          user the scheduled process runs as (the user who saved the config)
   * @param roleId          role used for the process security context
   * @param processSearchKey search key of the AD_Process to schedule ({@link #SII_PROCESS_SEARCH_KEY}
   *                         or {@link #TBAI_PROCESS_SEARCH_KEY})
   * @param description     human-readable description stored on the request
   * @return the AD_Process_Request id, or {@code null} when the process could not be resolved
   */
  public String ensureAutoSendSchedule(String clientId, String orgId, String userId, String roleId,
      String processSearchKey, String description) {
    OBContext.setAdminMode(true);
    try {
      Process process = resolveProcess(processSearchKey);
      if (process == null) {
        // The SII/TBAI module dataset is expected to define this process; stay non-fatal so an
        // unconfigured/partially-updated database never blocks the fiscal config save.
        log.warn("Process '{}' not found — skipping auto-send schedule for client {} org {}",
            processSearchKey, clientId, orgId);
        return null;
      }
      ProcessRequest existing = findExistingRequest(clientId, orgId, process);
      if (existing != null) {
        log.debug("Auto-send schedule already exists for client {} org {} process {} — skipping",
            clientId, orgId, processSearchKey);
        return existing.getId();
      }
      ProcessRequest request = buildRequest(clientId, orgId, userId, roleId, process, description);
      OBDal.getInstance().save(request);
      OBDal.getInstance().flush();
      log.info("Created auto-send schedule {} for client {} org {} process {}", request.getId(),
          clientId, orgId, processSearchKey);
      return request.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Registers an already-created schedule with the Quartz scheduler so it becomes active without
   * waiting for the next server restart. Best-effort: any failure (including the enclosing
   * request's transaction not having committed yet — see class Javadoc) is logged and swallowed,
   * because the {@code SCH} row is still picked up on the next scheduler initialization.
   *
   * @param requestId the AD_Process_Request id to activate, or {@code null} (no-op)
   */
  public void activateSchedule(String requestId) {
    if (requestId == null) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      ProcessRequest request = OBDal.getInstance().get(ProcessRequest.class, requestId);
      if (request == null) {
        return;
      }
      VariablesSecureApp vars = ProcessContext.newInstance(request.getOpenbravoContext()).toVars();
      DalConnectionProvider conn = new DalConnectionProvider(false);
      OBScheduler.getInstance().schedule(requestId, ProcessBundle.request(requestId, vars, conn));
      log.info("Activated auto-send schedule {}", requestId);
    } catch (Exception e) {
      log.warn("Could not immediately activate auto-send schedule {} (it will be picked up on "
          + "the next scheduler initialization): {}", requestId, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Removes the per-client-per-organization twice-a-day auto-send schedule for {@code process},
   * the counterpart to {@link #ensureAutoSendSchedule}. Called when the fiscal configuration the
   * schedule was created for is deleted or deactivated through Etendo GO (ETP-5117 follow-up) —
   * without this, a schedule created for a config that no longer exists (or is no longer active)
   * would keep firing the SII/TBAI sending process twice a day forever.
   *
   * <p>Idempotent and safe to call even when no schedule was ever created for this client/org/
   * process combination — e.g. the fiscal config was saved only through Classic UI (see class
   * Javadoc), or the process could not be resolved at creation time: both cases resolve to
   * "no matching {@code AD_Process_Request} found" via {@link #findExistingRequest} and this
   * method returns without error. Calling it twice in a row (e.g. two deactivating PUTs, or a
   * deactivation of an already-unscheduled config) is likewise a safe no-op the second time,
   * since the first call already flipped the row's {@code Active} flag to {@code false} and
   * {@link #findExistingRequest} only ever matches active rows.
   *
   * <p>Two independent removal steps, in this order:
   * <ol>
   *   <li>Best-effort live unschedule via {@link OBScheduler#unschedule(String, ProcessContext)}
   *       — removes the Quartz trigger/job so the process stops firing immediately. Any failure
   *       here (this method's own catch, on top of {@link OBScheduler#unschedule}'s own internal
   *       swallow-and-log) is non-fatal: it only means the live in-memory scheduler still holds a
   *       stale trigger until the next server restart.</li>
   *   <li>Deactivating the {@code AD_Process_Request} row itself ({@code Active = false}) via
   *       OBDal — <b>not</b> deleted, matching this codebase's general preference for
   *       deactivation over deletion when there is meaningful history (mirrors the
   *       "invoices were sent → deactivate, don't delete" branch of {@code smartDeactivate} in
   *       the calling handlers). This is the step that actually guarantees correctness: the row's
   *       own state is what the scheduler consults on its next initialization, so even if step 1
   *       fails outright, the row is never picked up again afterwards. The same write also sets
   *       the row's {@code Status} to {@link #STATUS_UNSCHEDULED} — see below.</li>
   * </ol>
   *
   * <p><b>Why the row's {@code Status} is set here and not left to {@link OBScheduler}.</b>
   * {@link OBScheduler#unschedule(String, ProcessContext)} does three things in one
   * exception-swallowing block: remove the Quartz trigger, delete the Quartz job, and only then
   * update the row's {@code Status} to {@code UNS}. So its status update is reachable only when
   * the two preceding Quartz calls succeed. In an environment where the scheduler was never
   * initialized (a SmartTomcat dev setup, for instance) the very first call throws, the catch
   * swallows it, and the status update never runs — leaving the row at the self-contradictory
   * {@code Active = N, Status = SCH} that Classic's Process Request window then displays. Setting
   * {@code Status} ourselves after the best-effort Quartz call makes the row's own state correct
   * and self-consistent regardless of whether Quartz was reachable, exactly as {@code
   * Active = false} already is. Ordering is deliberate: Quartz first, so that when it <i>is</i>
   * alive it still performs the real trigger/job removal (and its own status update); writing
   * {@code UNS} over {@code UNS} afterwards is idempotent and harmless.
   *
   * @param clientId         target client identifier
   * @param orgId            organization identifier the fiscal config belonged to
   * @param processSearchKey search key of the AD_Process the schedule runs ({@link
   *                         #SII_PROCESS_SEARCH_KEY} or {@link #TBAI_PROCESS_SEARCH_KEY})
   */
  public void unscheduleAutoSend(String clientId, String orgId, String processSearchKey) {
    OBContext.setAdminMode(true);
    try {
      Process process = resolveProcess(processSearchKey);
      if (process == null) {
        // Same non-fatal stance as ensureAutoSendSchedule: an unconfigured/partially-updated
        // database must never block the fiscal config delete/deactivate request.
        log.warn("Process '{}' not found — nothing to unschedule for client {} org {}",
            processSearchKey, clientId, orgId);
        return;
      }
      ProcessRequest existing = findExistingRequest(clientId, orgId, process);
      if (existing == null) {
        // INFO, not DEBUG: this is a low-frequency lifecycle event, and its invisibility at the
        // default INFO level is exactly what made a wrongly-scoped cleanup look like dead code
        // for several rounds of ETP-5117 debugging.
        log.info("No active auto-send schedule found for client {} org {} process {} — already "
            + "unscheduled, or one was never created", clientId, orgId, processSearchKey);
        return;
      }
      String requestId = existing.getId();
      unscheduleFromQuartz(requestId, existing.getOpenbravoContext());
      existing.setActive(false);
      existing.setStatus(STATUS_UNSCHEDULED);
      OBDal.getInstance().save(existing);
      OBDal.getInstance().flush();
      log.info("Deactivated auto-send schedule {} (status {}) for client {} org {} process {}",
          requestId, STATUS_UNSCHEDULED, clientId, orgId, processSearchKey);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Best-effort removal of {@code requestId}'s live Quartz trigger/job. {@code openbravoContext}
   * is the same serialized execution context {@link #buildObContext} stored on the row at
   * creation time; {@link ProcessContext#newInstance(String)} rebuilds it directly (no need for
   * the {@link org.openbravo.base.secureApp.VariablesSecureApp}/{@link ProcessBundle} detour
   * {@link #activateSchedule} needs for {@link OBScheduler#schedule(String, ProcessBundle)} —
   * {@link OBScheduler#unschedule(String, ProcessContext)} takes a {@link ProcessContext}
   * directly). Any failure (missing/corrupt stored context, scheduler error) is logged and
   * swallowed here — see {@link #unscheduleAutoSend} Javadoc for why this is safe to no-op.
   */
  private void unscheduleFromQuartz(String requestId, String openbravoContext) {
    try {
      ProcessContext processContext = ProcessContext.newInstance(openbravoContext);
      if (processContext == null) {
        log.warn("Auto-send schedule {} has no usable stored OpenbravoContext — skipping live "
            + "Quartz unschedule (the row will still be deactivated)", requestId);
        return;
      }
      OBScheduler.getInstance().unschedule(requestId, processContext);
    } catch (Exception e) {
      log.warn("Could not immediately unschedule auto-send schedule {} from the live scheduler "
          + "(it will still be deactivated in the DB, so it will not be picked up on the next "
          + "scheduler initialization regardless): {}", requestId, e.getMessage());
    }
  }

  protected ProcessRequest buildRequest(String clientId, String orgId, String userId, String roleId,
      Process process, String description) {
    ProcessRequest request = OBProvider.getInstance().get(ProcessRequest.class);
    request.setClient(OBDal.getInstance().get(Client.class, clientId));
    request.setOrganization(OBDal.getInstance().get(Organization.class, orgId));
    request.setUserContact(OBDal.getInstance().get(User.class, userId));
    request.setProcess(process);
    request.setDescription(description);
    request.setChannel(CHANNEL_SCHEDULER);
    request.setSecurityBasedOnRole(true);
    request.setTiming(TIMING_SCHEDULED);
    request.setFrequency(FREQUENCY_HOURLY);
    request.setHourlyInterval(HOURLY_INTERVAL);
    request.setStatus(STATUS_SCHEDULED);
    request.setFinishes(false);
    request.setActive(true);

    request.setStartDate(startOfToday());
    request.setStartTime(startTimeAtEleven());
    request.setOpenbravoContext(buildObContext(clientId, orgId, userId, roleId));
    return request;
  }

  /**
   * Builds the serialized execution context stored on the request so the scheduler can rebuild
   * the security variables (user/role/client/org/language) when it fires — both on immediate
   * activation and on startup pickup.
   */
  protected String buildObContext(String clientId, String orgId, String userId, String roleId) {
    OBContext ctx = OBContext.getOBContext();
    String language = (ctx != null && ctx.getLanguage() != null)
        ? ctx.getLanguage().getLanguage() : DEFAULT_LANGUAGE;
    VariablesSecureApp vars = new VariablesSecureApp(userId, clientId, orgId, roleId, language);
    return new ProcessContext(vars).toString();
  }

  protected ProcessRequest findExistingRequest(String clientId, String orgId, Process process) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    Organization organization = OBDal.getInstance().get(Organization.class, orgId);
    OBCriteria<ProcessRequest> criteria = OBDal.getInstance().createCriteria(ProcessRequest.class);
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.eq(ProcessRequest.PROPERTY_ORGANIZATION, organization));
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

  protected Date startOfToday() {
    return Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  /** Returns a timestamp whose time component is 11:00:00 today (only the time-of-day matters). */
  protected Timestamp startTimeAtEleven() {
    LocalDateTime dateTime = LocalDate.now().atTime(LocalTime.of(START_HOUR, 0));
    return Timestamp.valueOf(dateTime);
  }
}
