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

import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.client.kernel.ComponentProvider;
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
 * <h2>Idempotency</h2>
 *
 * <p>Safe on every boot. A tenant already at {@code 1}/30 with a single active request is reported
 * {@code alreadyCorrect} and its cadence columns are not rewritten; the pass still restates the
 * start boundary and re-arms, which at boot is what the scheduler was going to do anyway. Verified
 * live on ETP-5370: a second run over an already-realigned tenant reports
 * {@code realigned:0, alreadyCorrect:1} and writes no cadence change.
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

    // null = every tenant. The service opens its own admin context and commits per client.
    RealignReport report = scheduleService().realignCadence(null);
    if (!report.isSchedulerAvailable()) {
      log.info("CostingCadenceStartup: the scheduler reported itself unavailable mid-pass; nothing"
          + " was changed. The next startup will retry.");
      return;
    }

    int realigned = 0;
    int alreadyCorrect = 0;
    int failed = 0;
    int deactivated = 0;
    for (ClientOutcome outcome : report.getOutcomes()) {
      deactivated += outcome.getDeactivated();
      if (ClientOutcome.REALIGNED.equals(outcome.getStatus())) {
        realigned++;
      } else if (ClientOutcome.ALREADY_CORRECT.equals(outcome.getStatus())) {
        alreadyCorrect++;
      } else {
        failed++;
        log.warn("CostingCadenceStartup: could not realign client {} ({}): {}",
            outcome.getClientId(), outcome.getRequestId(), outcome.getDetail());
      }
    }
    log.info("CostingCadenceStartup: {} client(s) realigned to 30s, {} already correct, {} failed,"
        + " {} redundant request(s) unscheduled.", realigned, alreadyCorrect, failed, deactivated);
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
