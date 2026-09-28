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

package com.etendoerp.go.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.WarehouseResolver;
import com.etendoerp.go.payment.EnvironmentAccessEnforcementFlag;
import com.etendoerp.go.payment.EnvironmentAccessGuard;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.NeoFavoritesServlet;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5047 — {@link JwtAuthUtils#authenticateOrFail} refuses a tenant whose commercial access was
 * cut off with the same HTTP 402 body NEO and MCP answer, through the shared
 * {@link EnvironmentAccessGuard}.
 *
 * <p>Since the merge with ETP-5455 the guard runs in the bind step of the shared
 * {@link EnvironmentRequestAuthenticator} pipeline ({@code SurfacePolicy.NEO_DATA}), which hands
 * the denial back on the outcome; {@code JwtAuthUtils} writes it with
 * {@link EnvironmentAccessGuard.Denial#writeTo}. These tests drive the package-private overload
 * that takes the pipeline, built over a mocked cookie authenticator and the guard under test. The
 * scheme matrix of the pipeline itself lives in {@code EnvironmentRequestAuthenticatorTest}.
 *
 * <p>The last block drives each servlet that authenticates through this method
 * ({@link NeoFavoritesServlet}, {@link NeoFiscalTestModeServlet}) into a blocked tenant: each must
 * stop at the 402. (Survey configuration is {@code NEO_AUXILIARY} since ETP-5455 and deliberately
 * stays reachable; report selectors authenticate through {@code NeoServletSupport} and are pinned
 * in {@code ReportSelectorsServletTest}.)
 */
class JwtAuthUtilsEnvironmentAccessTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String BEARER_TOKEN = "bearer-token-xyz";
  private static final String LEGACY_BEARER_PROPERTY = "etgo.legacy.bearer.enabled";
  private static final String KILL_SWITCH_PROPERTY =
      "etendo.go.flags.environment-access-enforcement-off";

  private MockedStatic<SecureWebServicesUtils> sws;
  private MockedStatic<OBContext> obContext;
  private GoSessionAuthenticator sessionAuthenticator;
  private HttpServletResponse response;
  private StringWriter body;
  private Logger log;

  @BeforeEach
  void setUp() throws Exception {
    sws = mockStatic(SecureWebServicesUtils.class);
    obContext = mockStatic(OBContext.class);
    OBContext tenantContext = mock(OBContext.class);
    sws.when(() -> SecureWebServicesUtils.createContext(anyString(), anyString(), anyString(),
        any(), anyString())).thenReturn(tenantContext);
    sessionAuthenticator = mock(GoSessionAuthenticator.class);
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());

    response = mock(HttpServletResponse.class);
    body = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    log = mock(Logger.class);
  }

  @AfterEach
  void tearDown() {
    obContext.close();
    sws.close();
    System.clearProperty(LEGACY_BEARER_PROPERTY);
    System.clearProperty(KILL_SWITCH_PROPERTY);
  }

  // ===================== blocked tenants =====================

  @Test
  void aBlockedCookieSessionIsRefusedWith402AndTheSharedBody() throws Exception {
    TenantEnvironmentLifecycleService lifecycle = lifecycleDeciding(Decision.SUBSCRIPTION_REQUIRED);

    boolean allowed = JwtAuthUtils.authenticateOrFail(pipeline(new EnvironmentAccessGuard(lifecycle)),
        cookieRequest(), response, log, "favorites GET");

    assertFalse(allowed);
    verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    verify(response).setContentType("application/json");
    verify(response).setCharacterEncoding("UTF-8");
    assertSharedBody("SUBSCRIPTION_REQUIRED");
    // The tenant is the session's environment.
    verify(lifecycle).evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class));
    verify(log).info("Commercial access denied for {}: {}", "favorites GET",
        "Environment access is not available: SUBSCRIPTION_REQUIRED");
  }

  @Test
  void aBlockedBearerCallerIsRefusedWith402AndTheSharedBody() throws Exception {
    boolean allowed = JwtAuthUtils.authenticateOrFail(
        pipeline(new EnvironmentAccessGuard(lifecycleDeciding(Decision.DEMO_TRIAL_EXPIRED))),
        bearerRequest(), response, log, "fiscal-test-mode GET");

    assertFalse(allowed);
    verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    verify(response).setContentType("application/json");
    verify(response).setCharacterEncoding("UTF-8");
    assertSharedBody("DEMO_TRIAL_EXPIRED");
  }

  @Test
  void theKillSwitchLetsABlockedTenantThrough() throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "true");

    assertTrue(JwtAuthUtils.authenticateOrFail(
        pipeline(new EnvironmentAccessGuard(lifecycleDeciding(Decision.SUBSCRIPTION_REQUIRED))),
        cookieRequest(), response, log, "favorites GET"));

    verify(response, never()).setStatus(anyInt());
    assertEquals("", body.toString());
  }

  @Test
  void anAllowedTenantProceeds() throws Exception {
    assertTrue(JwtAuthUtils.authenticateOrFail(
        pipeline(new EnvironmentAccessGuard(lifecycleDeciding(Decision.ALLOWED))),
        bearerRequest(), response, log, "favorites GET"));

    verify(response, never()).setStatus(anyInt());
  }

  @Test
  void aTenantThatPredatesLifecycleMetadataProceeds() throws Exception {
    assertTrue(JwtAuthUtils.authenticateOrFail(
        pipeline(new EnvironmentAccessGuard(lifecycleDeciding(null))), cookieRequest(), response,
        log, "favorites GET"));
  }

  @Test
  void theGuardIsAskedForTheIdentityTenantWithTheEndpointLabel() throws Exception {
    EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);

    assertTrue(JwtAuthUtils.authenticateOrFail(pipeline(guard), bearerRequest(), response, log,
        "favorites PUT"));

    verify(guard).check(CLIENT_ID, "favorites PUT");
  }

  // ===================== the check runs only after authentication =====================

  @Test
  void anUnauthenticatedCallerGets401AndTheGuardIsNeverAsked() throws Exception {
    EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);
    HttpServletRequest request = mock(HttpServletRequest.class);

    assertFalse(JwtAuthUtils.authenticateOrFail(pipeline(guard), request, response, log,
        "favorites GET"));

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(response, never()).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    verifyNoInteractions(guard);
  }

  @Test
  void anInvalidBearerGets401AndTheGuardIsNeverAsked() throws Exception {
    EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");
    sws.when(() -> SecureWebServicesUtils.decodeToken("bad-token")).thenReturn(null);

    assertFalse(JwtAuthUtils.authenticateOrFail(pipeline(guard), request, response, log,
        "favorites GET"));

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verifyNoInteractions(guard);
  }

  // ===================== every JwtAuthUtils servlet stops at the 402 =====================

  @Nested
  class Servlets {

    private MockedStatic<CorsUtils> cors;
    private MockedStatic<JwtAuthUtils> jwtAuthUtils;

    /**
     * Routes the servlets' public entry point through a pipeline whose guard refuses the tenant;
     * everything else in {@code JwtAuthUtils} runs for real.
     */
    @BeforeEach
    void blockTheTenant() throws Exception {
      cors = mockStatic(CorsUtils.class);
      EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);
      EnvironmentAccessGuard.Denial denial = denial(Decision.SUBSCRIPTION_REQUIRED);
      when(guard.check(eq(CLIENT_ID), anyString())).thenReturn(denial);
      EnvironmentRequestAuthenticator blocking = pipeline(guard);
      jwtAuthUtils = mockStatic(JwtAuthUtils.class, CALLS_REAL_METHODS);
      jwtAuthUtils.when(() -> JwtAuthUtils.authenticateOrFail(any(HttpServletRequest.class),
          any(HttpServletResponse.class), any(Logger.class), anyString()))
          .thenAnswer(inv -> JwtAuthUtils.authenticateOrFail(blocking, inv.getArgument(0),
              inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
    }

    @AfterEach
    void release() {
      jwtAuthUtils.close();
      cors.close();
    }

    @Test
    void neoFavorites() throws Exception {
      new NeoFavoritesServlet().doGet(bearerRequest(), response);

      assertRefusedBeforeAnyWork();
    }

    @Test
    void fiscalTestMode() throws Exception {
      try (MockedStatic<FiscalTestModeResolver> resolver =
          mockStatic(FiscalTestModeResolver.class)) {
        new NeoFiscalTestModeServlet().doGet(bearerRequest(), response);

        assertRefusedBeforeAnyWork();
        resolver.verifyNoInteractions();
      }
    }

    private void assertRefusedBeforeAnyWork() throws Exception {
      verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
      verify(response, never()).setStatus(HttpServletResponse.SC_OK);
      assertSharedBody("SUBSCRIPTION_REQUIRED");
      // Each servlet's own work starts by entering admin mode (or reading the context's client):
      // none of it may run for a refused tenant.
      obContext.verify(OBContext::setAdminMode, never());
      obContext.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  // ===================== fixtures =====================

  /** The shared pipeline over the mocked cookie authenticator and the given guard. */
  private EnvironmentRequestAuthenticator pipeline(EnvironmentAccessGuard guard) {
    return new EnvironmentRequestAuthenticator(sessionAuthenticator, guard,
        mock(WarehouseResolver.class), mock(GoSessionRoleReconciler.class));
  }

  private void assertSharedBody(String decision) throws Exception {
    JSONObject error = new JSONObject(body.toString()).getJSONObject("error");
    assertEquals("Environment access is not available: " + decision, error.getString("message"));
    assertEquals(HttpServletResponse.SC_PAYMENT_REQUIRED, error.getInt("status"));
    assertEquals("ENVIRONMENT_ACCESS_DENIED", error.getString("code"));
    assertEquals(decision, error.getString("decision"));
  }

  private static TenantEnvironmentLifecycleService lifecycleDeciding(Decision decision) {
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(lifecycle.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(decision);
    return lifecycle;
  }

  /** A denial built by the real guard (its constructor is not visible here). */
  private static EnvironmentAccessGuard.Denial denial(Decision decision) {
    try (MockedStatic<EnvironmentAccessEnforcementFlag> flag =
        mockStatic(EnvironmentAccessEnforcementFlag.class)) {
      flag.when(() -> EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(anyString()))
          .thenReturn(false);
      return new EnvironmentAccessGuard(lifecycleDeciding(decision)).check(CLIENT_ID, "test");
    }
  }

  /** A GET carrying a live cookie session into the tenant. */
  private HttpServletRequest cookieRequest() {
    GoSessionRecord session = new GoSessionRecord();
    session.setId("session-1");
    session.setUserId("user-1");
    session.setRoleId("role-1");
    session.setCtxOrgId("org-1");
    session.setCtxClientId(CLIENT_ID);
    when(sessionAuthenticator.authenticate(any()))
        .thenReturn(GoSessionAuthResult.authenticated(session));
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    return request;
  }

  /** A request authenticated by a legacy Bearer JWT for the tenant. */
  private HttpServletRequest bearerRequest() {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    DecodedJWT jwt = mockJwt();
    sws.when(() -> SecureWebServicesUtils.decodeToken(BEARER_TOKEN)).thenReturn(jwt);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeader("Authorization")).thenReturn("Bearer " + BEARER_TOKEN);
    return request;
  }

  private static DecodedJWT mockJwt() {
    DecodedJWT jwt = mock(DecodedJWT.class);
    // Each claim is fully stubbed before being handed to jwt's own stubbing.
    Claim user = claim("user-1");
    Claim role = claim("role-1");
    Claim org = claim("org-1");
    Claim warehouse = claim(null);
    Claim client = claim(CLIENT_ID);
    when(jwt.getClaim("user")).thenReturn(user);
    when(jwt.getClaim("role")).thenReturn(role);
    when(jwt.getClaim("organization")).thenReturn(org);
    when(jwt.getClaim("warehouse")).thenReturn(warehouse);
    when(jwt.getClaim("client")).thenReturn(client);
    return jwt;
  }

  private static Claim claim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }
}
