/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.startup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.etendoerp.go.onboarding.OnboardingCostingScheduleService;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.ClientOutcome;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.RealignReport;

/**
 * Unit tests for {@link CostingCadenceStartup} (ETP-5370) — the boot-time self-healer that realigns
 * every existing tenant's costing schedule, so the release that changes the cadence for NEW tenants
 * is the same event that fixes the EXISTING ones, with nobody calling anything per tenant.
 *
 * <p>Mirrors {@link StoredColumnQueueScheduleStartupTest}'s convention: the class under test is
 * subclassed and its package-visible seams are overridden, so no test ever sleeps on the real
 * scheduler wait, reaches the real {@code OBScheduler}, or touches the DAL.
 *
 * <p><b>What is actually worth asserting here.</b> This class holds no business logic — it is a
 * trigger — so the assertions are about the two decisions it does make:</p>
 *
 * <ol>
 *   <li><b>The sweep argument is {@code null}.</b> {@code null} is what makes
 *       {@link OnboardingCostingScheduleService#realignCadence(String)} cross-tenant; any client id
 *       here would silently realign one tenant at boot and report nothing unusual, which is exactly
 *       the per-tenant manual step this class exists to remove.</li>
 *   <li><b>A scheduler that never wakes up means doing NOTHING.</b> Not a best-effort pass: while
 *       Quartz is in standby {@code schedule}/{@code reschedule} silently no-op, so a pass that won
 *       the race would leave rows claiming 30 seconds whose triggers were never re-armed — a row
 *       that lies is worse than a row that is merely stale, and the next boot retries anyway.</li>
 * </ol>
 *
 * <p>The remediation itself is covered by
 * {@code com.etendoerp.go.onboarding.OnboardingCostingScheduleRealignTest}; here the service is a
 * mock substituted through the {@code scheduleService()} seam.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CostingCadenceStartupTest {

  private static final String CLIENT_ONE_ID = "client-1";
  private static final String CLIENT_TWO_ID = "client-2";
  private static final String CLIENT_THREE_ID = "client-3";
  private static final String REQUEST_THREE_ID = "req-3";
  private static final String FAILURE_DETAIL = "Could not re-arm costing request req-3";

  @Mock
  private OnboardingCostingScheduleService scheduleService;
  @Mock
  private RealignReport report;
  @Mock
  private ClientOutcome outcomeRealigned;
  @Mock
  private ClientOutcome outcomeAlreadyCorrect;
  @Mock
  private ClientOutcome outcomeFailed;

  private TestableStartup startup;

  @BeforeEach
  void setUp() {
    startup = new TestableStartup(scheduleService);

    when(report.isSchedulerAvailable()).thenReturn(true);
    when(report.getOutcomes()).thenReturn(List.of());
    when(scheduleService.realignCadence(any())).thenReturn(report);
  }

  /**
   * {@code null} is the whole point of the call: it is what turns {@code realignCadence} into a
   * cross-tenant sweep. Asserting the ARGUMENT rather than just "the service was called" is what
   * separates this from a boot hook that quietly fixed one client.
   */
  @Test
  @DisplayName("A ready scheduler triggers one cross-tenant sweep")
  void testRealignEveryTenantSweepsEveryTenantWhenTheSchedulerIsReady() {
    startup.realignEveryTenant();

    verify(scheduleService).realignCadence(null);
    assertEquals(1, startup.seamCalls);
  }

  /**
   * The case that matters most. A half-corrected tenant — cadence columns rewritten, trigger never
   * re-armed — is the one outcome worth avoiding, so a scheduler that never leaves standby means
   * the pass is skipped entirely and retried on the next boot.
   */
  @Test
  @DisplayName("A scheduler that never wakes up leaves every row untouched")
  void testRealignEveryTenantDoesNothingWhenTheSchedulerNeverBecomesAvailable() {
    startup.schedulingAllowed = false;

    startup.realignEveryTenant();

    verify(scheduleService, never()).realignCadence(any());
    assertEquals(0, startup.seamCalls, "the service must not even be constructed");
  }

  /**
   * The scheduler can also go unavailable mid-pass, which the report reports rather than throws.
   * Nothing further is read — reporting per-client results from a pass that armed nothing would be
   * a lie of the same kind as the one above.
   */
  @Test
  @DisplayName("A report with no scheduler is a quiet no-op, not a failure")
  void testRealignEveryTenantIsQuietWhenTheReportHasNoScheduler() {
    when(report.isSchedulerAvailable()).thenReturn(false);

    assertDoesNotThrow(() -> startup.realignEveryTenant());

    verify(scheduleService).realignCadence(null);
    verify(report, never()).getOutcomes();
  }

  /**
   * A failed tenant is recorded by the service, never thrown, and must not stop this hook: a boot
   * that aborted on the first bad tenant would leave every later one on the old cadence, and the
   * only trace would be a startup stack trace nobody reads.
   */
  @Test
  @DisplayName("Every outcome status is consumed and a failed client never propagates")
  void testRealignEveryTenantConsumesEveryOutcomeStatusWithoutThrowing() {
    givenOutcome(outcomeRealigned, CLIENT_ONE_ID, ClientOutcome.REALIGNED, "req-1", 1, null);
    givenOutcome(outcomeAlreadyCorrect, CLIENT_TWO_ID, ClientOutcome.ALREADY_CORRECT, "req-2", 0,
        null);
    givenOutcome(outcomeFailed, CLIENT_THREE_ID, ClientOutcome.FAILED, REQUEST_THREE_ID, 0,
        FAILURE_DETAIL);
    when(report.getOutcomes())
        .thenReturn(List.of(outcomeRealigned, outcomeAlreadyCorrect, outcomeFailed));

    assertDoesNotThrow(() -> startup.realignEveryTenant());

    // Every outcome contributes to the deactivated total, the failed one included.
    verify(outcomeRealigned).getDeactivated();
    verify(outcomeAlreadyCorrect).getDeactivated();
    verify(outcomeFailed).getDeactivated();

    // Only the failure branch reports a cause — that log line is the sole trace an operator gets.
    verify(outcomeFailed).getDetail();
    verify(outcomeFailed).getClientId();
    verify(outcomeFailed).getRequestId();
    verify(outcomeRealigned, never()).getDetail();
    verify(outcomeAlreadyCorrect, never()).getDetail();
  }

  @Test
  @DisplayName("An empty report is a successful, silent pass")
  void testRealignEveryTenantHandlesAnEmptyReport() {
    assertDoesNotThrow(() -> startup.realignEveryTenant());

    verify(scheduleService).realignCadence(null);
    verify(report).getOutcomes();
  }

  @Test
  @DisplayName("runPass delegates to the realignment pass")
  void testRunPassDelegatesToRealignEveryTenant() {
    startup.runPass();

    assertEquals(1, startup.realignCalls);
    verify(scheduleService).realignCadence(null);
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private static void givenOutcome(ClientOutcome outcome, String clientId, String status,
      String requestId, int deactivated, String detail) {
    when(outcome.getClientId()).thenReturn(clientId);
    when(outcome.getStatus()).thenReturn(status);
    when(outcome.getRequestId()).thenReturn(requestId);
    when(outcome.getDeactivated()).thenReturn(deactivated);
    when(outcome.getDetail()).thenReturn(detail);
  }

  /**
   * Substitutes the two seams that would otherwise reach the real scheduler — {@code
   * scheduleService()} and the bounded {@code waitForSchedulingAllowed()} poll loop, which would
   * really sleep for up to two minutes — and records how many times each was reached.
   *
   * <p>{@code realignEveryTenant()} is recorded but still delegates to {@code super}, so the
   * {@code runPass} delegation test proves the real pass ran rather than that a stub was called.
   */
  private static final class TestableStartup extends CostingCadenceStartup {
    private final OnboardingCostingScheduleService service;
    private boolean schedulingAllowed = true;
    private int seamCalls;
    private int realignCalls;

    private TestableStartup(OnboardingCostingScheduleService service) {
      this.service = service;
    }

    @Override
    OnboardingCostingScheduleService scheduleService() {
      seamCalls++;
      return service;
    }

    @Override
    boolean waitForSchedulingAllowed() {
      return schedulingAllowed;
    }

    @Override
    void realignEveryTenant() {
      realignCalls++;
      super.realignEveryTenant();
    }
  }
}
