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
package com.etendoerp.go.schemaforge.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.etendoerp.go.onboarding.OnboardingCostingScheduleService;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.ClientOutcome;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.RealignReport;

/**
 * Unit tests for {@link SFCostingCadence} (ETP-5370) — the remediation endpoint that realigns the
 * costing schedule of tenants that were onboarded before the cadence changed.
 *
 * <p>Extends {@link BaseWebhookTest} for the shared {@code OBDal}/{@code OBContext} statics and
 * mirrors {@link SFAcctProcessMonitorTest}'s convention of proving the access gate first and the
 * behaviour after it second. The gate is driven the same way: the webhook reads its role through
 * {@code NeoAccessHelper.resolveCurrentRole()}, which is just {@code OBContext.getOBContext()
 * .getRole()}, so the {@code role} mock the base class already wires is the whole seam — no static
 * mocking of the helper is needed, and the real {@code isAdminOrClientAdmin} logic is exercised.
 *
 * <p><b>What the assertions are really guarding.</b> Three things here are security- or
 * correctness-critical rather than cosmetic:</p>
 *
 * <ol>
 *   <li><b>A refusal must not reach the service at all.</b> Every denial test asserts both that
 *       {@code realignCadence} was never called AND that the {@code scheduleService()} seam was
 *       never even reached — an endpoint that refused in its payload but had already re-armed 74
 *       tenants' Quartz triggers would look correct in the response and be a privilege escalation on
 *       the instance.</li>
 *   <li><b>The scope argument.</b> {@code scope=all} must call {@code realignCadence(null)} — null
 *       is what makes the sweep cross-tenant — while the default scope must pass the CALLER'S OWN
 *       client id. Passing the caller's id when {@code all} was asked (or vice versa) produces a
 *       perfectly well-formed response that silently did the wrong thing, so the argument itself is
 *       asserted, not just the resulting payload.</li>
 *   <li><b>{@code success} tracks failures, not HTTP.</b> A refusal and a partially failed sweep are
 *       both {@code 200} with {@code success:false} (the bridge maps {@code responseVars["error"]}
 *       to a 500, so a refusal must never travel as an error).</li>
 * </ol>
 *
 * <p>The remediation logic itself is covered by
 * {@code com.etendoerp.go.onboarding.OnboardingCostingScheduleRealignTest}; here the service is a
 * mock, substituted through the package-visible {@link SFCostingCadence#scheduleService()} seam.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFCostingCadenceTest extends BaseWebhookTest {

  private static final String TENANT_CLIENT_ID = "tenant-client-1";
  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String SYSTEM_ADMIN_ROLE_ID = "0";

  private static final String PARAM_SCOPE = "scope";
  private static final String SCOPE_ALL = "all";
  private static final String SCOPE_CLIENT = "client";

  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_REASON = "reason";
  private static final String FIELD_DETAIL = "detail";
  private static final String FIELD_CLIENTS = "clients";
  private static final String FIELD_CLIENT_ID = "clientId";
  private static final String FIELD_DEACTIVATED = "deactivated";
  private static final String FIELD_STATUS = "status";

  private static final String REASON_NOT_AUTHORIZED = "notAuthorized";

  private static final String CLIENT_ONE_ID = "client-1";
  private static final String CLIENT_TWO_ID = "client-2";
  private static final String CLIENT_THREE_ID = "client-3";
  private static final String CLIENT_FOUR_ID = "client-4";

  private static final String FAILURE_DETAIL = "Could not re-arm costing request req-4";

  @Mock
  private OnboardingCostingScheduleService scheduleService;
  @Mock
  private RealignReport report;
  @Mock
  private ClientOutcome outcomeRealigned;
  @Mock
  private ClientOutcome outcomeRealignedWithExtras;
  @Mock
  private ClientOutcome outcomeAlreadyCorrect;
  @Mock
  private ClientOutcome outcomeFailed;

  private TestableWebhook webhook;

  @BeforeEach
  void setUp() {
    webhook = new TestableWebhook(scheduleService);

    when(client.getId()).thenReturn(TENANT_CLIENT_ID);
    when(report.isSchedulerAvailable()).thenReturn(true);
    when(report.getOutcomes()).thenReturn(List.of());
    when(scheduleService.realignCadence(any())).thenReturn(report);
  }

  // ── access gate ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A non-admin caller is refused, and the remediation never runs")
  void testRestrictedRoleIsRefusedAndTheServiceIsNeverInvoked() throws Exception {
    givenRestrictedCaller();

    JSONObject result = invoke();

    assertFalse(result.getBoolean(FIELD_SUCCESS));
    assertEquals(REASON_NOT_AUTHORIZED, result.getString(FIELD_REASON));
    assertEquals(0, webhook.seamCalls, "the service must not even be reached");
    verify(scheduleService, never()).realignCadence(any());
  }

  @Test
  @DisplayName("A caller with no role at all is refused")
  void testMissingRoleIsRefusedAndTheServiceIsNeverInvoked() throws Exception {
    when(obContext.getRole()).thenReturn(null);

    JSONObject result = invoke();

    assertFalse(result.getBoolean(FIELD_SUCCESS));
    assertEquals(REASON_NOT_AUTHORIZED, result.getString(FIELD_REASON));
    assertEquals(0, webhook.seamCalls);
    verify(scheduleService, never()).realignCadence(any());
  }

  @Test
  @DisplayName("A client-admin caller is allowed")
  void testClientAdminCallerIsAllowed() throws Exception {
    givenClientAdminCaller();

    JSONObject result = invoke();

    assertTrue(result.getBoolean(FIELD_SUCCESS));
    assertFalse(result.has(FIELD_REASON));
  }

  // ── scope ─────────────────────────────────────────────────────────────────

  /**
   * The privilege boundary of this endpoint: {@code scope=all} re-arms every tenant's Quartz
   * trigger, so it is refused for anyone outside the System client even when their ROLE is a
   * client-admin. Checking the role alone would let a tenant admin reach 74 other tenants.
   */
  @Test
  @DisplayName("scope=all from a tenant client is refused, and the remediation never runs")
  void testScopeAllFromATenantClientIsRefused() throws Exception {
    givenClientAdminCaller();
    parameters.put(PARAM_SCOPE, SCOPE_ALL);

    JSONObject result = invoke();

    assertFalse(result.getBoolean(FIELD_SUCCESS));
    assertEquals("systemScopeRequired", result.getString(FIELD_REASON));
    assertEquals(0, webhook.seamCalls, "a refused sweep must not touch any tenant");
    verify(scheduleService, never()).realignCadence(any());
  }

  /**
   * {@code null} is what makes {@code realignCadence} cross-tenant, so the ARGUMENT is asserted —
   * passing the caller's own client id here would answer {@code scope:"all"} while having swept a
   * single tenant, which no assertion on the payload alone would catch.
   */
  @Test
  @DisplayName("scope=all from the System client sweeps every tenant")
  void testScopeAllFromTheSystemClientSweepsEveryTenant() throws Exception {
    when(role.getId()).thenReturn(SYSTEM_ADMIN_ROLE_ID);
    when(client.getId()).thenReturn(SYSTEM_CLIENT_ID);
    // Also covers the trim + case-insensitive parsing of the scope parameter.
    parameters.put(PARAM_SCOPE, " ALL ");

    JSONObject result = invoke();

    verify(scheduleService).realignCadence(null);
    assertEquals(1, webhook.seamCalls);
    assertTrue(result.getBoolean(FIELD_SUCCESS));
    assertEquals(SCOPE_ALL, result.getString(PARAM_SCOPE));
  }

  @Test
  @DisplayName("Without a scope parameter only the caller's own client is realigned")
  void testDefaultScopeRealignsOnlyTheCallersOwnClient() throws Exception {
    givenClientAdminCaller();

    JSONObject result = invoke();

    verify(scheduleService).realignCadence(TENANT_CLIENT_ID);
    assertEquals(SCOPE_CLIENT, result.getString(PARAM_SCOPE));
    assertTrue(result.getBoolean(FIELD_SUCCESS));
  }

  /** An unrecognised scope value is not "all" — it falls back to the safe, caller-scoped default. */
  @Test
  @DisplayName("An unrecognised scope value falls back to the caller's own client")
  void testUnknownScopeValueFallsBackToTheCallersOwnClient() throws Exception {
    givenClientAdminCaller();
    parameters.put(PARAM_SCOPE, "everything");

    JSONObject result = invoke();

    verify(scheduleService).realignCadence(TENANT_CLIENT_ID);
    assertEquals(SCOPE_CLIENT, result.getString(PARAM_SCOPE));
  }

  // ── scheduler availability ────────────────────────────────────────────────

  /**
   * On a standby node Quartz's {@code schedule}/{@code reschedule} silently no-op, so the report
   * comes back with no scheduler. Answering {@code success:true} there would tell an operator the
   * fleet was fixed when nothing was armed.
   */
  @Test
  @DisplayName("A report with no scheduler answers schedulerUnavailable, not success")
  void testSchedulerUnavailableIsReportedAsARefusal() throws Exception {
    givenClientAdminCaller();
    when(report.isSchedulerAvailable()).thenReturn(false);

    JSONObject result = invoke();

    assertFalse(result.getBoolean(FIELD_SUCCESS));
    assertEquals("schedulerUnavailable", result.getString(FIELD_REASON));
    assertFalse(result.has(FIELD_CLIENTS), "a refusal carries no per-client payload");
    verify(scheduleService).realignCadence(TENANT_CLIENT_ID);
  }

  // ── the result payload ────────────────────────────────────────────────────

  /**
   * The counters an operator reads the run by. One failed tenant is enough to make the whole call
   * {@code success:false} — a sweep that silently swallowed a tenant would be reported as a clean
   * run, and nobody would go back for the one that was left on the wrong cadence.
   */
  @Test
  @DisplayName("Every outcome is counted, and one failure makes the whole run unsuccessful")
  void testCountersAggregateEveryOutcome() throws Exception {
    givenClientAdminCaller();
    givenOutcome(outcomeRealigned, CLIENT_ONE_ID, "Tenant One", ClientOutcome.REALIGNED, "req-1",
        0, null);
    givenOutcome(outcomeRealignedWithExtras, CLIENT_TWO_ID, "Tenant Two", ClientOutcome.REALIGNED,
        "req-2", 1, null);
    givenOutcome(outcomeAlreadyCorrect, CLIENT_THREE_ID, "Tenant Three",
        ClientOutcome.ALREADY_CORRECT, "req-3", 0, null);
    givenOutcome(outcomeFailed, CLIENT_FOUR_ID, null, ClientOutcome.FAILED, "req-4", 0,
        FAILURE_DETAIL);
    when(report.getOutcomes()).thenReturn(List.of(outcomeRealigned, outcomeRealignedWithExtras,
        outcomeAlreadyCorrect, outcomeFailed));

    JSONObject result = invoke();

    assertEquals(2, result.getInt("realigned"));
    assertEquals(1, result.getInt("alreadyCorrect"));
    assertEquals(1, result.getInt("failed"));
    assertEquals(1, result.getInt(FIELD_DEACTIVATED), "the deactivated counter sums every client");
    assertFalse(result.getBoolean(FIELD_SUCCESS), "one failed tenant fails the whole run");

    JSONArray clients = result.getJSONArray(FIELD_CLIENTS);
    assertEquals(4, clients.length());

    JSONObject withExtras = entryFor(clients, CLIENT_TWO_ID);
    assertEquals(ClientOutcome.REALIGNED, withExtras.getString(FIELD_STATUS));
    assertEquals("Tenant Two", withExtras.getString("clientName"));
    assertEquals("req-2", withExtras.getString("requestId"));
    assertEquals(1, withExtras.getInt(FIELD_DEACTIVATED));
    assertFalse(withExtras.has(FIELD_DETAIL), "a successful client carries no detail");

    JSONObject failed = entryFor(clients, CLIENT_FOUR_ID);
    assertEquals(ClientOutcome.FAILED, failed.getString(FIELD_STATUS));
    assertEquals(FAILURE_DETAIL, failed.getString(FIELD_DETAIL),
        "the failing client must carry the cause — it is the only trace the operator gets");

    assertEquals(ClientOutcome.ALREADY_CORRECT,
        entryFor(clients, CLIENT_THREE_ID).getString(FIELD_STATUS));
  }

  @Test
  @DisplayName("An outcome without a detail does not emit the detail key at all")
  void testAnOutcomeWithoutADetailOmitsTheDetailKey() throws Exception {
    givenClientAdminCaller();
    givenOutcome(outcomeRealigned, CLIENT_ONE_ID, "Tenant One", ClientOutcome.REALIGNED, "req-1",
        0, null);
    when(report.getOutcomes()).thenReturn(List.of(outcomeRealigned));

    JSONObject result = invoke();

    JSONObject entry = entryFor(result.getJSONArray(FIELD_CLIENTS), CLIENT_ONE_ID);
    assertFalse(entry.has(FIELD_DETAIL));
    assertTrue(result.getBoolean(FIELD_SUCCESS));
    assertEquals(0, result.getInt(FIELD_DEACTIVATED));
  }

  @Test
  @DisplayName("A report with no outcomes is a successful, empty run rather than an error")
  void testAnEmptyReportIsASuccessfulRun() throws Exception {
    givenClientAdminCaller();

    JSONObject result = invoke();

    assertTrue(result.getBoolean(FIELD_SUCCESS));
    assertEquals(0, result.getJSONArray(FIELD_CLIENTS).length());
    assertEquals(0, result.getInt("realigned"));
    assertEquals(0, result.getInt("alreadyCorrect"));
    assertEquals(0, result.getInt("failed"));
    assertEquals(0, result.getInt(FIELD_DEACTIVATED));
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private JSONObject invoke() throws Exception {
    webhook.get(parameters, responseVars);
    String raw = responseVars.get("result");
    assertNotNull(raw, "Expected a 'result' entry, got: " + responseVars);
    return new JSONObject(raw);
  }

  private static JSONObject entryFor(JSONArray clients, String clientId) throws Exception {
    for (int i = 0; i < clients.length(); i++) {
      JSONObject entry = clients.getJSONObject(i);
      if (entry.has(FIELD_CLIENT_ID) && clientId.equals(entry.getString(FIELD_CLIENT_ID))) {
        return entry;
      }
    }
    throw new AssertionError("no entry for client " + clientId + " in " + clients);
  }

  private static void givenOutcome(ClientOutcome outcome, String clientId, String clientName,
      String status, String requestId, int deactivated, String detail) {
    when(outcome.getClientId()).thenReturn(clientId);
    when(outcome.getClientName()).thenReturn(clientName);
    when(outcome.getStatus()).thenReturn(status);
    when(outcome.getRequestId()).thenReturn(requestId);
    when(outcome.getDeactivated()).thenReturn(deactivated);
    when(outcome.getDetail()).thenReturn(detail);
  }

  /** A role that is neither System Administrator nor a client-admin. */
  private void givenRestrictedCaller() {
    when(role.getId()).thenReturn("restricted-role");
    when(role.isClientAdmin()).thenReturn(false);
  }

  private void givenClientAdminCaller() {
    when(role.isClientAdmin()).thenReturn(true);
  }

  /**
   * Substitutes the remediation service through the package-visible seam, and records how many
   * times that seam was reached so the refusal tests can prove the service was never even asked
   * for — the same override-a-seam harness the service's own tests use for {@code rearm}.
   */
  private static final class TestableWebhook extends SFCostingCadence {
    private final OnboardingCostingScheduleService service;
    private int seamCalls;

    private TestableWebhook(OnboardingCostingScheduleService service) {
      this.service = service;
    }

    @Override
    OnboardingCostingScheduleService scheduleService() {
      seamCalls++;
      return service;
    }
  }
}
