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

package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.apache.logging.log4j.Level;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;

/**
 * ETP-5047 — the single commercial access check NEO, MCP, the {@code JwtAuthUtils} servlets and
 * the environment login share.
 *
 * <p>What it must guarantee: an allowed tenant (and a tenant that predates lifecycle metadata)
 * never costs a flag evaluation; a denial is returned unless the kill switch is positively on, in
 * which case it is logged at INFO and allowed; the denial's wire format is the one shared shape;
 * and {@link EnvironmentAccessGuard#checkAsSystem} hands the thread back the context it came with.
 */
class EnvironmentAccessGuardTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";

  private TenantEnvironmentLifecycleService lifecycle;
  private final List<String> flagQueries = new ArrayList<>();

  @BeforeEach
  void mockLifecycle() {
    lifecycle = mock(TenantEnvironmentLifecycleService.class);
  }

  private void decide(Decision decision) {
    when(lifecycle.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(decision);
  }

  /** A guard whose kill switch answers {@code switchedOff}, recording every tenant it is asked. */
  private EnvironmentAccessGuard guard(boolean switchedOff) {
    Predicate<String> killSwitch = clientId -> {
      flagQueries.add(clientId);
      return switchedOff;
    };
    return new EnvironmentAccessGuard(lifecycle, killSwitch);
  }

  // ===================== check =====================

  @Test
  void anAllowedTenantIsLetThroughWithoutEvaluatingTheFlag() {
    decide(Decision.ALLOWED);

    assertNull(guard(false).check(CLIENT_ID, "neo"));

    assertTrue(flagQueries.isEmpty(), "an allowed request must cost no flag evaluation");
  }

  @Test
  void aTenantThatPredatesLifecycleMetadataIsLetThroughWithoutEvaluatingTheFlag() {
    decide(null);

    assertNull(guard(false).check(CLIENT_ID, "neo"));

    assertTrue(flagQueries.isEmpty());
  }

  @ParameterizedTest
  @EnumSource(value = Decision.class, names = "ALLOWED", mode = EnumSource.Mode.EXCLUDE)
  void everyRefusingDecisionIsADenialWhileEnforcing(Decision decision) {
    decide(decision);

    EnvironmentAccessGuard.Denial denial = guard(false).check(CLIENT_ID, "mcp");

    assertNotNull(denial);
    assertEquals(decision, denial.decision());
    assertEquals("Environment access is not available: " + decision.name(), denial.message());
    assertEquals(List.of(CLIENT_ID), flagQueries, "the kill switch is asked for this tenant");
  }

  @Test
  void theDecisionIsEvaluatedForAnActiveMembershipNow() {
    decide(Decision.ALLOWED);
    Instant before = Instant.now();

    guard(false).check(CLIENT_ID, "neo");

    ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
    Mockito.verify(lifecycle).evaluateAccess(eq(CLIENT_ID), eq(true), now.capture());
    assertTrue(!now.getValue().isBefore(before) && !now.getValue().isAfter(Instant.now()));
  }

  @Test
  void theKillSwitchTurnsADenialIntoAnInfoLineAndAllowsAccess() {
    decide(Decision.SUBSCRIPTION_REQUIRED);
    TestLogCapture info = TestLogCapture.attachTo(EnvironmentAccessGuard.class, Level.INFO);
    EnvironmentAccessGuard.Denial denial;
    try {
      denial = guard(true).check(CLIENT_ID, "environment-login");
    } finally {
      info.detach();
    }

    assertNull(denial);
    List<String> lines = info.messagesAt(Level.INFO);
    assertEquals(1, lines.size(), lines.toString());
    // Who would have been refused, where, and why — so an operator sees the switch's effect.
    assertTrue(lines.get(0).contains("environment-login"), lines.get(0));
    assertTrue(lines.get(0).contains(CLIENT_ID), lines.get(0));
    assertTrue(lines.get(0).contains("SUBSCRIPTION_REQUIRED"), lines.get(0));
  }

  @Test
  void anEnforcedDenialLogsNothingAtInfo() {
    decide(Decision.DEMO_TRIAL_EXPIRED);
    TestLogCapture info = TestLogCapture.attachTo(EnvironmentAccessGuard.class, Level.INFO);
    try {
      assertNotNull(guard(false).check(CLIENT_ID, "neo"));
    } finally {
      info.detach();
    }

    assertTrue(info.messagesAt(Level.INFO).isEmpty());
  }

  @Test
  void thePublicConstructorUsesTheRealKillSwitchForTheTenant() {
    decide(Decision.SUBSCRIPTION_REQUIRED);
    try (MockedStatic<EnvironmentAccessEnforcementFlag> flag =
        mockStatic(EnvironmentAccessEnforcementFlag.class)) {
      flag.when(() -> EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID))
          .thenReturn(true);

      assertNull(new EnvironmentAccessGuard(lifecycle).check(CLIENT_ID, "neo"));

      flag.verify(() -> EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID));
    }
  }

  // ===================== enforcedDecision (QA-low) =====================

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullSource
  @org.junit.jupiter.params.provider.EnumSource(value = Decision.class, names = "ALLOWED")
  void enforcedDecisionPassesAnAllowingOrMissingDecisionThroughWithoutTheSwitch(
      Decision decision) {
    decide(decision);

    assertEquals(decision, guard(true).enforcedDecision(CLIENT_ID));

    assertTrue(flagQueries.isEmpty(), "no refusal, no flag evaluation");
  }

  @ParameterizedTest
  @EnumSource(value = Decision.class, names = { "SUBSCRIPTION_REQUIRED", "DEMO_TRIAL_EXPIRED" })
  void enforcedDecisionKeepsTheRefusalWhileEnforcing(Decision decision) {
    decide(decision);

    assertEquals(decision, guard(false).enforcedDecision(CLIENT_ID));

    assertEquals(List.of(CLIENT_ID), flagQueries, "the switch is asked for this tenant");
  }

  @ParameterizedTest
  @EnumSource(value = Decision.class, names = { "SUBSCRIPTION_REQUIRED", "DEMO_TRIAL_EXPIRED" })
  void enforcedDecisionReadsAllowedWhenTheSwitchIsOn(Decision decision) {
    decide(decision);

    assertEquals(Decision.ALLOWED, guard(true).enforcedDecision(CLIENT_ID));

    assertEquals(List.of(CLIENT_ID), flagQueries);
  }

  @Test
  void enforcedDecisionLogsNothingWhateverItDecides() {
    // It reports a state; it decides no request — so, unlike check, no INFO line.
    decide(Decision.SUBSCRIPTION_REQUIRED);
    TestLogCapture info = TestLogCapture.attachTo(EnvironmentAccessGuard.class, Level.INFO);
    try {
      guard(true).enforcedDecision(CLIENT_ID);
      guard(false).enforcedDecision(CLIENT_ID);
    } finally {
      info.detach();
    }

    assertTrue(info.messagesAt(Level.INFO).isEmpty(), info.messagesAt(Level.INFO).toString());
    assertTrue(info.messagesAt(Level.WARN).isEmpty());
  }

  // ===================== the shared wire format =====================

  @Test
  void theErrorBodyIsTheSharedEnvelope() throws Exception {
    decide(Decision.SUBSCRIPTION_REQUIRED);

    JSONObject body = guard(false).check(CLIENT_ID, "neo").errorBody(402);

    assertEquals(1, body.length(), "only the error envelope");
    JSONObject error = body.getJSONObject("error");
    assertEquals(4, error.length(), "message, status, code, decision — nothing else");
    // The message is byte-for-byte the pre-ETP-5047 NEO text: an older SPA still parses it.
    assertEquals("Environment access is not available: SUBSCRIPTION_REQUIRED",
        error.getString("message"));
    assertEquals(402, error.getInt("status"));
    assertEquals("ENVIRONMENT_ACCESS_DENIED", error.getString("code"));
    assertEquals(EnvironmentAccessGuard.ERROR_CODE, error.getString("code"));
    assertEquals("SUBSCRIPTION_REQUIRED", error.getString("decision"));
    assertTrue(error.getString("message").startsWith(EnvironmentAccessGuard.MESSAGE_PREFIX));
  }

  @Test
  void theErrorBodyCarriesTheStatusTheCallerAnswersWith() throws Exception {
    decide(Decision.DEMO_TRIAL_EXPIRED);

    JSONObject error = guard(false).check(CLIENT_ID, "neo").errorBody(403)
        .getJSONObject("error");

    assertEquals(403, error.getInt("status"));
    assertEquals("DEMO_TRIAL_EXPIRED", error.getString("decision"));
  }

  @Test
  void writeToAnswersTheCompleteSharedHttpDenial() throws Exception {
    // Review fix W5 — the one writer MCP, JwtAuthUtils and the environment login share.
    decide(Decision.SUBSCRIPTION_REQUIRED);
    EnvironmentAccessGuard.Denial denial = guard(false).check(CLIENT_ID, "mcp");
    javax.servlet.http.HttpServletResponse response =
        mock(javax.servlet.http.HttpServletResponse.class);
    java.io.StringWriter body = new java.io.StringWriter();
    when(response.getWriter()).thenReturn(new java.io.PrintWriter(body, true));

    denial.writeTo(response);

    Mockito.verify(response).setStatus(402);
    Mockito.verify(response).setContentType("application/json");
    Mockito.verify(response).setCharacterEncoding("UTF-8");
    assertEquals(402, EnvironmentAccessGuard.Denial.STATUS);
    assertEquals(denial.errorBody(402).toString(), body.toString());
  }

  // ===================== checkAsSystem =====================

  @Nested
  class CheckAsSystem {

    private MockedStatic<OBContext> context;
    private final List<String> calls = new ArrayList<>();
    private final AtomicReference<OBContext> current = new AtomicReference<>();
    private final OBContext caller = mock(OBContext.class);
    private final OBContext system = mock(OBContext.class);

    @BeforeEach
    void recordContextCalls() {
      context = mockStatic(OBContext.class);
      context.when(OBContext::getOBContext).thenAnswer(invocation -> current.get());
      context.when(() -> OBContext.setOBContext("0", "0", "0", "0")).thenAnswer(invocation -> {
        calls.add("system");
        current.set(system);
        return null;
      });
      context.when(() -> OBContext.setAdminMode(anyBoolean()))
          .thenAnswer(invocation -> calls.add("adminMode"));
      context.when(OBContext::restorePreviousMode)
          .thenAnswer(invocation -> calls.add("restorePreviousMode"));
      context.when(() -> OBContext.setOBContext(Mockito.<OBContext>any()))
          .thenAnswer(invocation -> {
            OBContext restored = invocation.getArgument(0);
            calls.add(restored == caller ? "restore(caller)"
                : restored == null ? "restore(null)" : "restore(other)");
            current.set(restored);
            return null;
          });
    }

    @AfterEach
    void close() {
      context.close();
    }

    @Test
    void decidesAsSystemAndHandsTheCallerItsContextBack() {
      current.set(caller);
      when(lifecycle.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
          .thenAnswer(invocation -> {
            calls.add("evaluate as " + (current.get() == system ? "system" : "caller"));
            return Decision.SUBSCRIPTION_REQUIRED;
          });

      EnvironmentAccessGuard.Denial denial = guard(false).checkAsSystem(CLIENT_ID, "mcp");

      assertEquals(Decision.SUBSCRIPTION_REQUIRED, denial.decision());
      assertEquals(List.of("system", "adminMode", "evaluate as system", "restorePreviousMode",
          "restore(caller)"), calls);
      assertSame(caller, current.get());
    }

    @Test
    void aContextLessCallerIsLeftContextLess() {
      // MCP has no OBContext at this point: leaving the system one behind would make the rest of
      // the request silently privileged.
      decide(Decision.ALLOWED);

      assertNull(guard(false).checkAsSystem(CLIENT_ID, "mcp"));

      assertEquals("restore(null)", calls.get(calls.size() - 1));
      assertNull(current.get());
    }

    @Test
    void theCallersContextComesBackEvenWhenTheDecisionThrows() {
      current.set(caller);
      when(lifecycle.evaluateAccess(anyString(), anyBoolean(), any(Instant.class)))
          .thenThrow(new IllegalStateException("lifecycle read failed"));

      assertThrows(IllegalStateException.class,
          () -> guard(false).checkAsSystem(CLIENT_ID, "mcp"));

      Iterator<String> last = calls.subList(calls.size() - 2, calls.size()).iterator();
      assertEquals("restorePreviousMode", last.next());
      assertEquals("restore(caller)", last.next());
      assertSame(caller, current.get());
    }
  }
}
