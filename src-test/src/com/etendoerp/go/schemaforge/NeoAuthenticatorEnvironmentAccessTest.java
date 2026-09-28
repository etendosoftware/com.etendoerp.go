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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.WarehouseResolver;
import com.etendoerp.go.oauth2.OAuth2Filter;
import com.etendoerp.go.payment.EnvironmentAccessGuard;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Unit tests for the environment-access enforcement in {@link NeoAuthenticator} (ETP-5443, R3).
 *
 * <p>Before this fix, {@code applySessionContext} (the {@code USE_SESSION} cookie path) never
 * called {@code enforceEnvironmentAccess}, while {@code authenticateJwt} (the legacy Bearer path)
 * always did — a cookie session whose selected environment was {@code DEMO_TRIAL_EXPIRED} or
 * {@code SUBSCRIPTION_REQUIRED} still got full NEO access instead of {@code 402}.
 *
 * <p>{@link GoSessionAuthenticator} and {@link TenantEnvironmentLifecycleService} are mocks handed
 * to the shared {@link EnvironmentRequestAuthenticator} pipeline (ETP-5455), and
 * {@link SecureWebServicesUtils}/{@link OBContext}/{@link OAuth2Filter} are statically stubbed, so
 * these run with no database.
 *
 * <p>Three request schemes reach the enforcement: the cookie session ({@code USE_SESSION} —
 * {@code applySessionContext}), the legacy Bearer JWT ({@code authenticateJwt}), and the opaque
 * OAuth2 client-credentials token ({@code authenticateOAuth2Token}, reached when the Bearer token
 * fails JWT decoding). All three now call {@code enforceEnvironmentAccess} at the same point —
 * after {@code OBContext} is set up, before {@code applyRequestLanguage} — so they must agree.
 */
class NeoAuthenticatorEnvironmentAccessTest {

  private static final String CLIENT_ID = "client-1";
  private static final String USER_ID = "user-1";
  private static final String ROLE_ID = "role-1";
  private static final String ORG_ID = "org-1";
  private static final String WAREHOUSE_ID = "wh-1";
  private static final String BEARER_TOKEN = "bearer-token-xyz";
  private static final String OAUTH2_TOKEN = "oauth2-opaque-token-xyz";
  private static final String LEGACY_BEARER_PROPERTY = "etgo.legacy.bearer.enabled";
  /** ETP-5047 — the local form of the {@code environment-access-enforcement-off} kill switch. */
  private static final String KILL_SWITCH_PROPERTY =
      "etendo.go.flags.environment-access-enforcement-off";

  private NeoServlet servlet;
  private NeoAuthenticator authenticator;
  private GoSessionAuthenticator sessionAuthenticator;
  private TenantEnvironmentLifecycleService lifecycleService;

  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<SecureWebServicesUtils> swsStatic;
  private MockedStatic<OAuth2Filter> oauth2FilterStatic;

  @BeforeEach
  void setUp() throws Exception {
    servlet = mock(NeoServlet.class);
    sessionAuthenticator = mock(GoSessionAuthenticator.class);
    lifecycleService = mock(TenantEnvironmentLifecycleService.class);
    authenticator = new NeoAuthenticator(servlet, new EnvironmentRequestAuthenticator(
        sessionAuthenticator, lifecycleService, mock(WarehouseResolver.class),
        mock(GoSessionRoleReconciler.class)));

    obContextStatic = mockStatic(OBContext.class);
    swsStatic = mockStatic(SecureWebServicesUtils.class);
    oauth2FilterStatic = mockStatic(OAuth2Filter.class);
    OBContext mockContext = mock(OBContext.class);
    swsStatic.when(() -> SecureWebServicesUtils.createContext(
        anyString(), anyString(), anyString(), any(), anyString())).thenReturn(mockContext);
  }

  @AfterEach
  void tearDown() {
    oauth2FilterStatic.close();
    swsStatic.close();
    obContextStatic.close();
    System.clearProperty(LEGACY_BEARER_PROPERTY);
    System.clearProperty(KILL_SWITCH_PROPERTY);
  }

  // ===================== Cookie session (USE_SESSION) path =====================

  @Test
  void cookieSessionSubscriptionRequiredReturns402AndDoesNotAuthenticate() throws Exception {
    stubCookieSession(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.SUBSCRIPTION_REQUIRED);

    HttpServletRequest request = cookieRequest();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertFalse(authenticated, "a refused environment must not authenticate the request");
    assertRefusedWith402(response, "SUBSCRIPTION_REQUIRED");
  }

  @Test
  void cookieSessionDemoTrialExpiredReturns402AndDoesNotAuthenticate() throws Exception {
    stubCookieSession(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.DEMO_TRIAL_EXPIRED);

    HttpServletRequest request = cookieRequest();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertFalse(authenticated, "a refused environment must not authenticate the request");
    assertRefusedWith402(response, "DEMO_TRIAL_EXPIRED");
  }

  @Test
  void cookieSessionAllowedAuthenticatesNormally() throws Exception {
    stubCookieSession(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.ALLOWED);

    HttpServletRequest request = cookieRequest();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertTrue(authenticated, "an allowed environment must authenticate the request");
    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(servlet, never()).writeResponse(any(), any());
  }

  /**
   * A legacy tenant that predates lifecycle metadata resolves a {@code null} decision — the
   * controlled legacy transition flow, not a refusal. Must be allowed, exactly like the Bearer
   * path already did before this fix.
   */
  @Test
  void cookieSessionNullDecisionAllowsLegacyTenant() throws Exception {
    stubCookieSession(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(null);

    HttpServletRequest request = cookieRequest();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertTrue(authenticated, "a legacy tenant with no lifecycle metadata must not be refused");
    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(servlet, never()).writeResponse(any(), any());
  }

  /**
   * ETP-5047 — NEO decides through the shared {@code EnvironmentAccessGuard}, built over its own
   * lifecycle service but with the real kill switch: switched off, a blocked tenant is let through
   * (the decision is only logged) and nothing is written.
   */
  @Test
  void cookieSessionOfABlockedTenantIsAllowedWhileEnforcementIsSwitchedOff() throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "true");
    stubCookieSession(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.SUBSCRIPTION_REQUIRED);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(cookieRequest(), response);

    assertTrue(authenticated, "the kill switch must stop the refusal");
    verify(servlet, never()).writeResponse(any(), any());
    verify(servlet, never()).sendError(any(), anyInt(), anyString());
  }

  @Test
  void cookieSessionOfABlockedTenantIsRefusedWhenTheSwitchIsExplicitlyFalse() throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "false");
    stubCookieSession(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.SUBSCRIPTION_REQUIRED);
    HttpServletResponse response = mock(HttpServletResponse.class);

    assertFalse(authenticator.authenticateRequest(cookieRequest(), response));
    assertRefusedWith402(response, "SUBSCRIPTION_REQUIRED");
  }

  /**
   * ETP-5047 / ETP-5455 merge — the guard lives in the shared pipeline now (one per pipeline, a
   * final field), and NEO hands it its entry-point label so the kill-switch log line names NEO.
   */
  @Test
  void theSharedGuardIsAskedForTheSessionTenantUnderTheNeoLabel() throws Exception {
    EnvironmentAccessGuard guard = mock(EnvironmentAccessGuard.class);
    NeoAuthenticator withGuard = new NeoAuthenticator(servlet, new EnvironmentRequestAuthenticator(
        sessionAuthenticator, guard, mock(WarehouseResolver.class),
        mock(GoSessionRoleReconciler.class)));
    stubCookieSession(CLIENT_ID);

    assertTrue(withGuard.authenticateRequest(cookieRequest(), mock(HttpServletResponse.class)));

    verify(guard).check(CLIENT_ID, "neo");
  }

  // ===================== Legacy Bearer (USE_LEGACY_BEARER) path is unchanged =====================

  @Test
  void bearerPathSubscriptionRequiredStillReturns402() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    stubBearerToken(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.SUBSCRIPTION_REQUIRED);

    HttpServletRequest request = bearerRequest();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertFalse(authenticated, "the pre-existing Bearer enforcement must remain intact");
    assertRefusedWith402(response, "SUBSCRIPTION_REQUIRED");
  }

  // ===================== OAuth2 opaque client-credentials token path =====================

  /**
   * Reached from {@code authenticateJwt} when {@code SecureWebServicesUtils.decodeToken} cannot
   * decode the Bearer token as a JWT (returns {@code null} here — a thrown decode failure falls
   * back the same way) — {@code authenticateOAuth2Token} then resolves the identity via
   * {@link OAuth2Filter#validateToken}.
   */
  @Test
  void oauth2SubscriptionRequiredReturns402AndDoesNotAuthenticate() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    stubOAuth2Token(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.SUBSCRIPTION_REQUIRED);

    HttpServletRequest request = oauth2Request();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertFalse(authenticated, "a refused environment must not authenticate the OAuth2 request");
    assertRefusedWith402(response, "SUBSCRIPTION_REQUIRED");
  }

  @Test
  void oauth2DemoTrialExpiredReturns402AndDoesNotAuthenticate() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    stubOAuth2Token(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.DEMO_TRIAL_EXPIRED);

    HttpServletRequest request = oauth2Request();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertFalse(authenticated, "a refused environment must not authenticate the OAuth2 request");
    assertRefusedWith402(response, "DEMO_TRIAL_EXPIRED");
  }

  @Test
  void oauth2AllowedAuthenticatesNormally() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    stubOAuth2Token(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.ALLOWED);

    HttpServletRequest request = oauth2Request();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertTrue(authenticated, "an allowed environment must authenticate the OAuth2 request");
    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(servlet, never()).writeResponse(any(), any());
  }

  @Test
  void oauth2NullDecisionAllowsLegacyTenant() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    stubOAuth2Token(CLIENT_ID);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(null);

    HttpServletRequest request = oauth2Request();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertTrue(authenticated, "a legacy tenant with no lifecycle metadata must not be refused");
    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(servlet, never()).writeResponse(any(), any());
  }

  /**
   * When {@link OAuth2Filter#validateToken} resolves no identity at all, {@code authenticateOAuth2Token}
   * throws before ever reaching {@code enforceEnvironmentAccess} — the lifecycle service must not be
   * consulted, and the request is refused with the pre-existing 401, not a 402.
   */
  @Test
  void oauth2MissingIdentityReturns401AndNeverConsultsLifecycleService() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(OAUTH2_TOKEN)).thenReturn(null);
    oauth2FilterStatic.when(() -> OAuth2Filter.validateToken(OAUTH2_TOKEN)).thenReturn(null);

    HttpServletRequest request = oauth2Request();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertFalse(authenticated, "a missing OAuth2 identity must not authenticate the request");
    verify(servlet).sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
    verifyNoInteractions(lifecycleService);
  }

  /**
   * A resolved identity with an insufficient scope for the request method (here {@code neo:read}
   * on a {@code POST}) is refused by {@code hasRequiredScope} before {@code enforceEnvironmentAccess}
   * runs — same guarantee as the missing-identity case above.
   */
  @Test
  void oauth2InsufficientScopeReturns401AndNeverConsultsLifecycleService() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(OAUTH2_TOKEN)).thenReturn(null);
    Map<String, String> identity = new HashMap<>();
    identity.put(OAuth2Filter.ATTR_USER_ID, USER_ID);
    identity.put(OAuth2Filter.ATTR_ROLE_ID, ROLE_ID);
    identity.put(OAuth2Filter.ATTR_ORG_ID, ORG_ID);
    identity.put(OAuth2Filter.ATTR_CLIENT_ID, CLIENT_ID);
    identity.put(OAuth2Filter.ATTR_SCOPES, "neo:read");
    oauth2FilterStatic.when(() -> OAuth2Filter.validateToken(OAUTH2_TOKEN)).thenReturn(identity);

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeader("Authorization")).thenReturn("Bearer " + OAUTH2_TOKEN);
    when(request.getHeader("Accept-Language")).thenReturn(null);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(request, response);

    assertFalse(authenticated, "insufficient scope must not authenticate the request");
    verify(servlet).sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
        "Insufficient scope or invalid token context");
    verifyNoInteractions(lifecycleService);
  }

  // ============ Parity: cookie, Bearer JWT and OAuth2 agree for the same decision ============

  @Test
  void parityAllThreeSchemesRefuseSubscriptionRequiredWithTheSameMessage() throws Exception {
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.SUBSCRIPTION_REQUIRED);

    HttpServletResponse cookieResponse = mock(HttpServletResponse.class);
    HttpServletResponse bearerResponse = mock(HttpServletResponse.class);
    HttpServletResponse oauth2Response = mock(HttpServletResponse.class);
    boolean cookieResult = runCookiePath(CLIENT_ID, cookieResponse);
    boolean bearerResult = runBearerPath(CLIENT_ID, bearerResponse);
    boolean oauth2Result = runOAuth2Path(CLIENT_ID, oauth2Response);

    assertFalse(cookieResult);
    assertEquals(cookieResult, bearerResult);
    assertEquals(cookieResult, oauth2Result);
    JSONObject cookieBody = assertRefusedWith402(cookieResponse, "SUBSCRIPTION_REQUIRED");
    JSONObject bearerBody = assertRefusedWith402(bearerResponse, "SUBSCRIPTION_REQUIRED");
    JSONObject oauth2Body = assertRefusedWith402(oauth2Response, "SUBSCRIPTION_REQUIRED");
    assertEquals(cookieBody.toString(), bearerBody.toString(),
        "the cookie and Bearer schemes must answer the same 402 body");
    assertEquals(cookieBody.toString(), oauth2Body.toString(),
        "the cookie and OAuth2 schemes must answer the same 402 body");
  }

  @Test
  void parityAllThreeSchemesAllowTheSameAllowedDecision() throws Exception {
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.ALLOWED);

    boolean cookieResult = runCookiePath(CLIENT_ID, mock(HttpServletResponse.class));
    boolean bearerResult = runBearerPath(CLIENT_ID, mock(HttpServletResponse.class));
    boolean oauth2Result = runOAuth2Path(CLIENT_ID, mock(HttpServletResponse.class));

    assertTrue(cookieResult);
    assertEquals(cookieResult, bearerResult);
    assertEquals(cookieResult, oauth2Result);
    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(servlet, never()).writeResponse(any(), any());
  }

  // ===================== Fixtures =====================

  /**
   * ETP-5047 — NEO answers a refused tenant with the shared {@code EnvironmentAccessGuard} body
   * through {@code writeResponse}, no longer through the plain-text {@code sendError}: HTTP 402,
   * the message text unchanged since ETP-5443 (a client parsing the prefix keeps working), plus
   * the machine-readable {@code code} and {@code decision}.
   *
   * @return the {@code error} object written, for cross-scheme comparison
   */
  private JSONObject assertRefusedWith402(HttpServletResponse response, String decision)
      throws Exception {
    ArgumentCaptor<NeoResponse> written = ArgumentCaptor.forClass(NeoResponse.class);
    verify(servlet).writeResponse(eq(response), written.capture());
    verify(servlet, never()).sendError(eq(response), anyInt(), anyString());
    NeoResponse neoResponse = written.getValue();
    assertEquals(HttpServletResponse.SC_PAYMENT_REQUIRED, neoResponse.getHttpStatus());
    JSONObject error = neoResponse.getBody().getJSONObject("error");
    assertEquals("Environment access is not available: " + decision, error.getString("message"));
    assertEquals(HttpServletResponse.SC_PAYMENT_REQUIRED, error.getInt("status"));
    assertEquals("ENVIRONMENT_ACCESS_DENIED", error.getString("code"));
    assertEquals(decision, error.getString("decision"));
    assertEquals(4, error.length(), "the error body carries exactly message, status, code, decision");
    return error;
  }

  private boolean runCookiePath(String clientId, HttpServletResponse response) throws Exception {
    stubCookieSession(clientId);
    return authenticator.authenticateRequest(cookieRequest(), response);
  }

  private boolean runBearerPath(String clientId, HttpServletResponse response) throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    stubBearerToken(clientId);
    return authenticator.authenticateRequest(bearerRequest(), response);
  }

  private boolean runOAuth2Path(String clientId, HttpServletResponse response) throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    stubOAuth2Token(clientId);
    return authenticator.authenticateRequest(oauth2Request(), response);
  }

  private void stubCookieSession(String clientId) {
    when(sessionAuthenticator.authenticate(any())).thenReturn(
        GoSessionAuthResult.authenticated(validRecord(clientId)));
  }

  private void stubBearerToken(String clientId) {
    // mockJwt(...) must be fully resolved BEFORE the swsStatic.when(...).thenReturn(...) chain
    // starts — see the note on mockJwt about nesting a when() inside an unfinished stub.
    DecodedJWT jwt = mockJwt(USER_ID, ROLE_ID, ORG_ID, clientId);
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(BEARER_TOKEN)).thenReturn(jwt);
  }

  /**
   * Makes {@code SecureWebServicesUtils.decodeToken} answer {@code null} for the OAuth2 token —
   * the "not a JWT" outcome that sends {@code authenticateJwt} down the
   * {@code authenticateOAuth2Token} fallback — and stubs {@link OAuth2Filter#validateToken} to
   * resolve a full identity with a scope wide enough for any HTTP method ({@code neo:*}).
   */
  private void stubOAuth2Token(String clientId) {
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(OAUTH2_TOKEN)).thenReturn(null);
    Map<String, String> identity = new HashMap<>();
    identity.put(OAuth2Filter.ATTR_USER_ID, USER_ID);
    identity.put(OAuth2Filter.ATTR_ROLE_ID, ROLE_ID);
    identity.put(OAuth2Filter.ATTR_ORG_ID, ORG_ID);
    identity.put(OAuth2Filter.ATTR_CLIENT_ID, clientId);
    identity.put(OAuth2Filter.ATTR_SCOPES, "neo:*");
    oauth2FilterStatic.when(() -> OAuth2Filter.validateToken(OAUTH2_TOKEN)).thenReturn(identity);
  }

  private static GoSessionRecord validRecord(String clientId) {
    GoSessionRecord record = new GoSessionRecord();
    record.setUserId(USER_ID);
    record.setRoleId(ROLE_ID);
    record.setCtxOrgId(ORG_ID);
    record.setCtxClientId(clientId);
    record.setWarehouseId(WAREHOUSE_ID);
    return record;
  }

  private static HttpServletRequest cookieRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Accept-Language")).thenReturn(null);
    return request;
  }

  private static HttpServletRequest bearerRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Authorization")).thenReturn("Bearer " + BEARER_TOKEN);
    when(request.getHeader("Accept-Language")).thenReturn(null);
    return request;
  }

  private static HttpServletRequest oauth2Request() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeader("Authorization")).thenReturn("Bearer " + OAUTH2_TOKEN);
    when(request.getHeader("Accept-Language")).thenReturn(null);
    return request;
  }

  private static DecodedJWT mockJwt(String userId, String roleId, String orgId, String clientId) {
    DecodedJWT jwt = mock(DecodedJWT.class);
    // Each claim must be fully built (its own when().thenReturn()) BEFORE being handed to
    // jwt.getClaim(...)'s own when().thenReturn() — nesting a when() call inside the argument
    // of another unfinished when().thenReturn() confuses Mockito's stubbing state and throws
    // UnfinishedStubbingException.
    Claim userClaim = stringClaim(userId);
    Claim roleClaim = stringClaim(roleId);
    Claim organizationClaim = stringClaim(orgId);
    Claim warehouseClaim = stringClaim(null);
    Claim clientClaim = stringClaim(clientId);
    when(jwt.getClaim("user")).thenReturn(userClaim);
    when(jwt.getClaim("role")).thenReturn(roleClaim);
    when(jwt.getClaim("organization")).thenReturn(organizationClaim);
    when(jwt.getClaim("warehouse")).thenReturn(warehouseClaim);
    when(jwt.getClaim("client")).thenReturn(clientClaim);
    return jwt;
  }

  private static Claim stringClaim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }

}
