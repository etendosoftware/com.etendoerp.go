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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.payment.EnvironmentAccessEnforcementFlag;
import com.etendoerp.go.payment.EnvironmentAccessGuard;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.NeoFavoritesServlet;
import com.etendoerp.go.schemaforge.ReportSelectorsServlet;
import com.etendoerp.go.schemaforge.SurveyConfigServlet;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5047 — {@link JwtAuthUtils#authenticateOrFail} refuses a tenant whose commercial access was
 * cut off with the same HTTP 402 body NEO and MCP answer, through the shared
 * {@link EnvironmentAccessGuard} (swapped here through the package-private
 * {@code JwtAuthUtils.environmentAccessGuard} seam).
 *
 * <p>Both credential schemes reach the check: the cookie session (its record is served by a
 * mocked {@code OBDal} session, so {@code JdbcGoSessionStore} runs for real without a database)
 * and the legacy Bearer JWT. The check runs only once the caller is authenticated, and only when
 * the installed {@link OBContext} names a tenant.
 *
 * <p>The last block drives each servlet that authenticates through this method
 * ({@link NeoFavoritesServlet}, {@link ReportSelectorsServlet}, {@link SurveyConfigServlet},
 * {@link NeoFiscalTestModeServlet}) into a blocked tenant: each must stop at the 402.
 */
class JwtAuthUtilsEnvironmentAccessTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String BEARER_TOKEN = "bearer-token-xyz";
  private static final String LEGACY_BEARER_PROPERTY = "etgo.legacy.bearer.enabled";
  private static final String KILL_SWITCH_PROPERTY =
      "etendo.go.flags.environment-access-enforcement-off";

  private EnvironmentAccessGuard originalGuard;
  private MockedStatic<SecureWebServicesUtils> sws;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private OBContext tenantContext;
  private Client tenant;
  private Session hibernateSession;
  private HttpServletResponse response;
  private StringWriter body;
  private Logger log;

  @BeforeEach
  void setUp() throws Exception {
    originalGuard = JwtAuthUtils.environmentAccessGuard;
    sws = mockStatic(SecureWebServicesUtils.class);
    obContext = mockStatic(OBContext.class);
    obDal = mockStatic(OBDal.class);
    OBDal dal = mock(OBDal.class);
    hibernateSession = mock(Session.class);
    when(dal.getSession()).thenReturn(hibernateSession);
    obDal.when(OBDal::getInstance).thenReturn(dal);

    tenant = mock(Client.class);
    when(tenant.getId()).thenReturn(CLIENT_ID);
    tenantContext = mock(OBContext.class);
    when(tenantContext.getCurrentClient()).thenReturn(tenant);
    sws.when(() -> SecureWebServicesUtils.createContext(anyString(), anyString(), anyString(),
        any(), anyString())).thenReturn(tenantContext);
    // The context authenticateOrFail installs is the one the access check then reads.
    obContext.when(OBContext::getOBContext).thenReturn(tenantContext);

    response = mock(HttpServletResponse.class);
    body = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    log = mock(Logger.class);
  }

  @AfterEach
  void tearDown() {
    JwtAuthUtils.environmentAccessGuard = originalGuard;
    obDal.close();
    obContext.close();
    sws.close();
    System.clearProperty(LEGACY_BEARER_PROPERTY);
    System.clearProperty(KILL_SWITCH_PROPERTY);
  }

  // ===================== blocked tenants =====================

  @Test
  void aBlockedCookieSessionIsRefusedWith402AndTheSharedBody() throws Exception {
    TenantEnvironmentLifecycleService lifecycle = lifecycleDeciding(Decision.SUBSCRIPTION_REQUIRED);
    JwtAuthUtils.environmentAccessGuard = new EnvironmentAccessGuard(lifecycle);

    boolean allowed = JwtAuthUtils.authenticateOrFail(cookieRequest(), response, log,
        "favorites GET");

    assertFalse(allowed);
    verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    verify(response).setContentType("application/json");
    verify(response).setCharacterEncoding("UTF-8");
    assertSharedBody("SUBSCRIPTION_REQUIRED");
    // The tenant is the session's environment, as installed in OBContext.
    verify(lifecycle).evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class));
    verify(log).info("Commercial access denied for {}: {}", "favorites GET",
        "Environment access is not available: SUBSCRIPTION_REQUIRED");
  }

  @Test
  void aBlockedBearerCallerIsRefusedWith402AndTheSharedBody() throws Exception {
    JwtAuthUtils.environmentAccessGuard =
        new EnvironmentAccessGuard(lifecycleDeciding(Decision.DEMO_TRIAL_EXPIRED));

    boolean allowed = JwtAuthUtils.authenticateOrFail(bearerRequest(), response, log,
        "report-selectors GET");

    assertFalse(allowed);
    verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    verify(response).setContentType("application/json");
    verify(response).setCharacterEncoding("UTF-8");
    assertSharedBody("DEMO_TRIAL_EXPIRED");
  }

  @Test
  void theKillSwitchLetsABlockedTenantThrough() throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "true");
    JwtAuthUtils.environmentAccessGuard =
        new EnvironmentAccessGuard(lifecycleDeciding(Decision.SUBSCRIPTION_REQUIRED));

    assertTrue(JwtAuthUtils.authenticateOrFail(cookieRequest(), response, log, "favorites GET"));

    verify(response, never()).setStatus(anyInt());
    assertEquals("", body.toString());
  }

  @Test
  void anAllowedTenantProceeds() throws Exception {
    JwtAuthUtils.environmentAccessGuard =
        new EnvironmentAccessGuard(lifecycleDeciding(Decision.ALLOWED));

    assertTrue(JwtAuthUtils.authenticateOrFail(bearerRequest(), response, log, "favorites GET"));

    verify(response, never()).setStatus(anyInt());
  }

  @Test
  void aTenantThatPredatesLifecycleMetadataProceeds() throws Exception {
    JwtAuthUtils.environmentAccessGuard = new EnvironmentAccessGuard(lifecycleDeciding(null));

    assertTrue(JwtAuthUtils.authenticateOrFail(cookieRequest(), response, log, "favorites GET"));
  }

  @Test
  void theGuardIsAskedForTheContextTenantWithTheEndpointLabel() throws Exception {
    EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);
    JwtAuthUtils.environmentAccessGuard = guard;

    assertTrue(JwtAuthUtils.authenticateOrFail(bearerRequest(), response, log,
        "survey-config POST"));

    verify(guard).check(CLIENT_ID, "survey-config POST");
  }

  // ===================== the check runs only after authentication =====================

  @Test
  void anUnauthenticatedCallerGets401AndTheGuardIsNeverAsked() throws Exception {
    EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);
    JwtAuthUtils.environmentAccessGuard = guard;
    HttpServletRequest request = mock(HttpServletRequest.class);

    assertFalse(JwtAuthUtils.authenticateOrFail(request, response, log, "favorites GET"));

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(response, never()).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    verifyNoInteractions(guard);
  }

  @Test
  void anInvalidBearerGets401AndTheGuardIsNeverAsked() throws Exception {
    EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);
    JwtAuthUtils.environmentAccessGuard = guard;
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");
    sws.when(() -> SecureWebServicesUtils.decodeToken("bad-token")).thenReturn(null);

    assertFalse(JwtAuthUtils.authenticateOrFail(request, response, log, "favorites GET"));

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verifyNoInteractions(guard);
  }

  @Test
  void noContextAfterAuthenticationProceedsWithoutAsking() throws Exception {
    EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);
    JwtAuthUtils.environmentAccessGuard = guard;
    obContext.when(OBContext::getOBContext).thenReturn(null);

    assertTrue(JwtAuthUtils.authenticateOrFail(bearerRequest(), response, log, "favorites GET"));

    verifyNoInteractions(guard);
  }

  @Test
  void aContextWithNoClientProceedsWithoutAsking() throws Exception {
    EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);
    JwtAuthUtils.environmentAccessGuard = guard;
    when(tenantContext.getCurrentClient()).thenReturn(null);

    assertTrue(JwtAuthUtils.authenticateOrFail(cookieRequest(), response, log, "favorites GET"));

    verifyNoInteractions(guard);
  }

  // ===================== every JwtAuthUtils servlet stops at the 402 =====================

  @Nested
  class Servlets {

    private MockedStatic<CorsUtils> cors;

    @BeforeEach
    void blockTheTenant() throws Exception {
      cors = mockStatic(CorsUtils.class);
      EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);
      EnvironmentAccessGuard.Denial denial = denial(Decision.SUBSCRIPTION_REQUIRED);
      when(guard.check(eq(CLIENT_ID), anyString())).thenReturn(denial);
      JwtAuthUtils.environmentAccessGuard = guard;
    }

    @AfterEach
    void releaseCors() {
      cors.close();
    }

    @Test
    void neoFavorites() throws Exception {
      new NeoFavoritesServlet().doGet(bearerRequest(), response);

      assertRefusedBeforeAnyWork();
    }

    @Test
    void reportSelectors() throws Exception {
      HttpServletRequest request = bearerRequest();
      when(request.getPathInfo()).thenReturn("/product");

      new ReportSelectorsServlet().doGet(request, response);

      assertRefusedBeforeAnyWork();
      verify(request, never()).getPathInfo();
    }

    @Test
    void surveyConfig() throws Exception {
      new SurveyConfigServlet().doGet(bearerRequest(), response);

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

  /** A GET carrying a live cookie session into the tenant, served by the mocked DAL session. */
  private HttpServletRequest cookieRequest() {
    GoSessionRecord record = new GoSessionRecord();
    record.setId("session-1");
    record.setUserId("user-1");
    record.setRoleId("role-1");
    record.setCtxOrgId("org-1");
    record.setCtxClientId(CLIENT_ID);
    record.setExpiresAt(Instant.now().plusSeconds(30L * 24 * 3600));
    record.setAbsoluteExpiresAt(Instant.now().plusSeconds(60L * 24 * 3600));
    when(hibernateSession.doReturningWork(any())).thenReturn(record);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "raw-session-token") });
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
