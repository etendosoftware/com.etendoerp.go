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

package com.etendoerp.go.startup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.kernel.ComponentProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.scheduling.OBScheduler;

import com.etendoerp.go.onboarding.OnboardingCostingScheduleService;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.ClientOutcome;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.RealignReport;

/**
 * Startup self-healer that brings every EXISTING tenant to the costing-schedule invariant new
 * tenants are born with: <b>exactly ONE active scheduled {@code CostingBackground} request per
 * client, firing every 30 seconds</b> (ETP-5370).
 *
 * <h2>Why a startup hook is the right trigger, and the reasoning that nearly missed it</h2>
 *
 * <p>The cadence of a schedule cannot be corrected with SQL: {@code OBScheduler.initialize()} reads
 * {@code AD_PROCESS_REQUEST} exactly once, at Quartz startup, and {@code DefaultJob.execute} rebuilds
 * its bundle from Quartz's own {@code JobDataMap}, so an already-armed trigger never re-reads the
 * row. That ruled out a data-fix and led first to a manual remediation webhook
 * ({@code SFCostingCadence}), on the premise that "production never restarts Tomcat".
 *
 * <p>That premise is false for the case that matters. <b>Shipping this module IS a restart</b> — a
 * new WAR means a new JVM, and therefore exactly one guaranteed boot per release. Leaving the
 * correction to a per-tenant webhook call would have meant one authenticated call per tenant, using
 * each tenant's own admin token, which is precisely the "manual intervention per client" the ticket
 * exists to avoid. This initializer uses that boot instead: the release that changes the cadence for
 * NEW tenants is the same event that realigns the EXISTING ones, with nobody doing anything.
 *
 * <p>The webhook is kept, and is no longer the primary path: it is the escape hatch for correcting
 * one tenant without waiting for a release.
 *
 * <h2>Ordering: why it waits for the scheduler</h2>
 *
 * <p>Application initializers run several seconds BEFORE Quartz leaves standby, and
 * {@code OBScheduler.schedule}/{@code reschedule} silently no-op while it is in standby — a pass
 * that won the race would report success and change nothing. So this waits (bounded) for
 * {@link OBScheduler#isSchedulingAllowed()} first, which also places it after
 * {@code OBScheduler.initialize()} has armed the triggers from the DB, so every request this pass
 * re-arms is replacing a real, live trigger rather than racing its creation.
 *
 * <p>If the scheduler never becomes available within the timeout the pass is skipped entirely and
 * logged: the rows are left untouched (a half-corrected row whose trigger was not re-armed is the
 * one outcome worth avoiding) and the next boot tries again.
 *
 * <h2>ONE-SHOT: it migrates each tenant once, then stops</h2>
 *
 * <p>Every tenant it processes gets a marker row in {@code ETGO_DATA_FIX_HISTORY}
 * ({@code fix_id='__costing-cadence-30s__'}, System-owned, keyed by {@code remediated_client_id} —
 * the same ledger and the same shape {@code OnboardingBaselineService} already writes its
 * {@code __baseline__} rows into). Marked tenants are passed to
 * {@link com.etendoerp.go.onboarding.OnboardingCostingScheduleService#realignCadence(String, Set)}
 * as the skip set, so from the second boot on this costs two queries and writes nothing.
 *
 * <p><b>This is a migration, not a standing policy, and the distinction is not cosmetic.</b> A
 * permanent sweep would keep forcing 30s on every boot, which means (a) on a large instance, one
 * commit and one Quartz re-arm per tenant per restart, all of it to leave things exactly as they
 * were, and (b) the day the product lets a user choose their own costing frequency, their choice
 * would be silently reset by the next deploy. The marker is what prevents both.
 *
 * <p>A tenant that FAILS is deliberately left unmarked, so the next boot retries it. A tenant with
 * no costing request at all produces no outcome and no marker — there is nothing to realign, and
 * creating a missing schedule is not this class's job (see the webhook's javadoc).
 *
 * <p>Within a single pass the work is still idempotent: a tenant already at {@code 1}/30 with one
 * active request is reported {@code alreadyCorrect} and its cadence columns are not rewritten.
 * Verified live on ETP-5370.
 *
 * <p>Never fatal: {@link SessionAwareStartup} catches, logs and rolls back, and
 * {@code realignCadence} is already best-effort per client — one tenant's failure is recorded and
 * the sweep continues with the rest.
 */
@ApplicationScoped
@ComponentProvider.Qualifier(CostingCadenceStartup.QUALIFIER)
public class CostingCadenceStartup extends SessionAwareStartup {

  static final String QUALIFIER = "com.etendoerp.go.startup.CostingCadenceStartup";

  private static final Logger log = LogManager.getLogger(CostingCadenceStartup.class);

  /** Ledger id this migration marks tenants with; mirrors {@code __baseline__}'s synthetic shape. */
  static final String MARKER_FIX_ID = "__costing-cadence-30s__";

  private static final String SYSTEM_ID = "0";
  private static final String STATUS_APPLIED = "APPLIED";
  private static final String STATUS_SKIPPED_NOT_NEEDED = "SKIPPED_NOT_NEEDED";

  private static final String SQL_SELECT_MARKERS =
      "SELECT remediated_client_id FROM etgo_data_fix_history WHERE fix_id = ?";

  private static final String SQL_INSERT_MARKER = ""
      + "INSERT INTO etgo_data_fix_history ("
      + "  etgo_data_fix_history_id, ad_client_id, ad_org_id, isactive,"
      + "  created, createdby, updated, updatedby,"
      + "  remediated_client_id, fix_id, status, applied_utc, rows_affected, detail"
      + ") VALUES ("
      + "  get_uuid(), ?, ?, 'Y',"
      + "  now(), ?, now(), ?,"
      + "  ?, ?, ?, ?, ?, ?"
      + ") ON CONFLICT ON CONSTRAINT etgo_dfh_tenant_fix_un DO NOTHING";

  /** Poll interval while waiting for the scheduler to leave standby. */
  private static final long SCHEDULER_POLL_MS = 500L;
  /** Hard cap waiting for the scheduler to become active (it starts a few seconds after the app). */
  private static final long SCHEDULER_WAIT_TIMEOUT_MS = 120_000L;

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected String name() {
    return "CostingCadenceStartup";
  }

  @Override
  protected void runPass() {
    realignEveryTenant();
  }

  /**
   * Visible for testing: the synchronous idempotent pass, decoupled from the startup thread and the
   * {@code SessionInfo} wait.
   */
  void realignEveryTenant() {
    if (!waitForSchedulingAllowed()) {
      log.info("CostingCadenceStartup: scheduler still inactive after {} ms; leaving the costing"
          + " cadence realignment for the next startup.", SCHEDULER_WAIT_TIMEOUT_MS);
      return;
    }

    Set<String> alreadyMigrated = loadAlreadyMigrated();

    // null = every tenant, minus the ones already migrated. The service opens its own admin
    // context and commits per client.
    RealignReport report = scheduleService().realignCadence(null, alreadyMigrated);
    if (!report.isSchedulerAvailable()) {
      log.info("CostingCadenceStartup: the scheduler reported itself unavailable mid-pass; nothing"
          + " was changed. The next startup will retry.");
      return;
    }

    if (report.getOutcomes().isEmpty()) {
      log.debug("CostingCadenceStartup: every tenant is already migrated ({} marked); nothing to do.",
          alreadyMigrated.size());
      return;
    }

    int realigned = 0;
    int alreadyCorrect = 0;
    int failed = 0;
    int deactivated = 0;
    for (ClientOutcome outcome : report.getOutcomes()) {
      deactivated += outcome.getDeactivated();
      if (ClientOutcome.FAILED.equals(outcome.getStatus())) {
        failed++;
        // Left UNMARKED on purpose: the next boot retries this tenant.
        log.warn("CostingCadenceStartup: could not realign client {} ({}): {}",
            outcome.getClientId(), outcome.getRequestId(), outcome.getDetail());
        continue;
      }
      if (ClientOutcome.REALIGNED.equals(outcome.getStatus())) {
        realigned++;
      } else {
        alreadyCorrect++;
      }
      markMigrated(outcome);
    }
    commitMarkers();

    log.info("CostingCadenceStartup: {} client(s) realigned to 30s, {} already correct, {} failed,"
        + " {} redundant request(s) unscheduled. Migrated tenants are marked and will be skipped"
        + " from the next startup on.", realigned, alreadyCorrect, failed, deactivated);
  }

  /**
   * The tenants this migration has already covered, read from the System-owned data-fix ledger.
   *
   * <p>Deliberately NOT swallowed on error: if the marker set cannot be read we do not know who has
   * been migrated, and guessing "nobody" would re-run the sweep over the whole fleet. Letting it
   * throw hands control to {@link SessionAwareStartup}, which logs, rolls back and skips the pass —
   * leaving every row untouched and retrying on the next boot.
   */
  private Set<String> loadAlreadyMigrated() {
    Set<String> migrated = new HashSet<>();
    OBContext.setAdminMode(true);
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_MARKERS)) {
        ps.setString(1, MARKER_FIX_ID);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            migrated.add(rs.getString(1));
          }
        }
      }
      return migrated;
    } catch (SQLException e) {
      throw new OBException("Could not read the costing-cadence migration markers", e);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Records that this tenant has been migrated. {@code APPLIED} when the cadence was actually
   * rewritten, {@code SKIPPED_NOT_NEEDED} when it was already correct — the two statuses the ledger
   * already uses for exactly that distinction. The insert is guarded by the ledger's own uniqueness
   * constraint, so a re-run can never duplicate a marker.
   */
  private void markMigrated(ClientOutcome outcome) {
    OBContext.setAdminMode(true);
    try {
      OBDal.getInstance().flush();
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_MARKER)) {
        ps.setString(   1, SYSTEM_ID);   // ad_client_id — System-owned
        ps.setString(   2, SYSTEM_ID);   // ad_org_id    — System
        ps.setString(   3, SYSTEM_ID);   // createdby    — System
        ps.setString(   4, SYSTEM_ID);   // updatedby    — System
        ps.setString(   5, outcome.getClientId());
        ps.setString(   6, MARKER_FIX_ID);
        ps.setString(   7, ClientOutcome.REALIGNED.equals(outcome.getStatus())
            ? STATUS_APPLIED : STATUS_SKIPPED_NOT_NEEDED);
        ps.setTimestamp(8, Timestamp.from(Instant.now()));
        ps.setInt(      9, outcome.getDeactivated());
        ps.setString(  10, "Surviving request " + outcome.getRequestId());
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new OBException(
          "Could not mark the costing-cadence migration for client " + outcome.getClientId(), e);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Commits the marker rows. A failure here means the sweep simply runs again next boot. */
  private void commitMarkers() {
    OBContext.setAdminMode(true);
    try {
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The remediation routine, behind a package-visible seam so tests can substitute it — the same
   * override-a-seam shape used by {@code SFCostingCadence} and by the service itself.
   */
  OnboardingCostingScheduleService scheduleService() {
    return new OnboardingCostingScheduleService();
  }

  /**
   * Blocks (bounded) until Quartz leaves standby, so the re-arms in this pass actually register
   * instead of silently no-oping. Returns {@code false} on timeout or interruption.
   *
   * <p>Package-visible so tests can override it instead of really sleeping.
   */
  boolean waitForSchedulingAllowed() {
    long deadline = System.currentTimeMillis() + SCHEDULER_WAIT_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (schedulingAllowedQuietly()) {
        return true;
      }
      try {
        Thread.sleep(SCHEDULER_POLL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return schedulingAllowedQuietly();
  }

  /** {@link OBScheduler#isSchedulingAllowed()} with its checked exception treated as "not yet". */
  private boolean schedulingAllowedQuietly() {
    try {
      return OBScheduler.getInstance().isSchedulingAllowed();
    } catch (Exception e) {
      return false;
    }
  }
}
