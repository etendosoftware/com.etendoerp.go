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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.WarehouseResolver;
import com.etendoerp.go.oauth2.OAuth2Filter;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.session.GoLegacyBearer;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5455 — every credential scheme that reaches {@link NeoAuthenticator} (cookie session, legacy
 * Bearer JWT, OAuth2 client-credentials token) must pass through the SAME post-authentication step.
 *
 * <p>{@link NeoAuthenticatorEnvironmentAccessTest} already pins the commercial-access half of that
 * contract (ETP-5443). This class pins the rest of it, which today still diverges per branch:
 * <ul>
 *   <li><b>Kill switch vs OAuth2</b> — {@code etgo.legacy.bearer.enabled} exists to retire the
 *   browser's legacy Bearer JWT. OAuth2 client-credentials tokens (MCP connectors, ETP-5345) are
 *   not that credential, yet the OAuth2 attempt lives inside the Bearer branch, so switching the
 *   legacy path off also locks every OAuth2 client out of NEO. Worse, an OAuth2 request is counted
 *   as a legacy Bearer use, so the metric meant to tell when the switch is safe to flip overcounts.
 *   </li>
 *   <li><b>Warehouse correction</b> — a context whose warehouse is outside the role's readable
 *   organizations is repaired on the JWT branch only; a cookie session carrying the same stale
 *   warehouse is not.</li>
 *   <li>Guarantees that already hold and a refactor must not lose: the access policy is consulted
 *   exactly once per request, the request language is applied on every scheme, and the switch
 *   still refuses a JWT.</li>
 * </ul>
 *
 * <p>No database: the pipeline is built over mocked collaborators and the statics are mocked,
 * like {@link NeoAuthenticatorEnvironmentAccessTest}.
 */
class NeoAuthenticatorSchemeParityTest {

  private static final String CLIENT_ID = "client-1";
  private static final String USER_ID = "user-1";
  private static final String ROLE_ID = "role-1";
  private static final String ORG_ID = "org-1";
  private static final String FOREIGN_ORG_ID = "org-not-readable";
  private static final String STALE_WAREHOUSE_ID = "wh-stale";
  private static final String CORRECTED_WAREHOUSE_ID = "wh-corrected";
  private static final String BEARER_TOKEN = "bearer-token-xyz";
  private static final String OAUTH2_TOKEN = "oauth2-opaque-token-xyz";
  private static final String LANGUAGE = "es_ES";
  private static final String LEGACY_BEARER_PROPERTY = "etgo.legacy.bearer.enabled";

  private NeoServlet servlet;
  private NeoAuthenticator authenticator;
  private GoSessionAuthenticator sessionAuthenticator;
  private TenantEnvironmentLifecycleService lifecycleService;
  private WarehouseResolver warehouseResolver;
  private OBContext context;

  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<SecureWebServicesUtils> swsStatic;
  private MockedStatic<OAuth2Filter> oauth2FilterStatic;
  private MockedStatic<NeoLanguage> languageStatic;

  @BeforeEach
  void setUp() throws Exception {
    servlet = mock(NeoServlet.class);
    sessionAuthenticator = mock(GoSessionAuthenticator.class);
    lifecycleService = mock(TenantEnvironmentLifecycleService.class);
    warehouseResolver = mock(WarehouseResolver.class);
    authenticator = buildAuthenticator();

    obContextStatic = mockStatic(OBContext.class);
    swsStatic = mockStatic(SecureWebServicesUtils.class);
    oauth2FilterStatic = mockStatic(OAuth2Filter.class);
    languageStatic = mockStatic(NeoLanguage.class);

    context = mock(OBContext.class);
    swsStatic.when(() -> SecureWebServicesUtils.createContext(
        anyString(), anyString(), anyString(), any(), anyString())).thenReturn(context);
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.ALLOWED);
  }

  @AfterEach
  void tearDown() {
    languageStatic.close();
    oauth2FilterStatic.close();
    swsStatic.close();
    obContextStatic.close();
    System.clearProperty(LEGACY_BEARER_PROPERTY);
  }

  // ===================== H4 — the kill switch retires the JWT, not OAuth2 =====================

  @Test
  void oauth2StillAuthenticatesWhenTheLegacyBearerSwitchIsOff() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "false");
    stubNoSession();
    stubOAuth2Token();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(oauth2Request(), response);

    assertTrue(authenticated,
        "turning the browser's legacy Bearer JWT off must not lock OAuth2 clients out of NEO");
  }

  @Test
  void jwtIsStillRefusedWhenTheLegacyBearerSwitchIsOff() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "false");
    stubNoSession();
    stubBearerJwt();
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean authenticated = authenticator.authenticateRequest(bearerRequest(), response);

    assertFalse(authenticated, "the switch exists to refuse exactly this credential");
    verify(servlet).sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
        "Missing or invalid Authorization header");
  }

  @Test
  void anOAuth2RequestIsNotCountedAsALegacyBearerUse() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    stubNoSession();
    stubOAuth2Token();
    long before = GoLegacyBearer.useCount();

    assertTrue(authenticator.authenticateRequest(oauth2Request(), mock(HttpServletResponse.class)));

    assertEquals(before, GoLegacyBearer.useCount(),
        "the legacy counter decides when the switch is safe to flip; OAuth2 must not inflate it");
  }

  @Test
  void aJwtRequestIsCountedExactlyOnceAsALegacyBearerUse() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    stubNoSession();
    stubBearerJwt();
    long before = GoLegacyBearer.useCount();

    assertTrue(authenticator.authenticateRequest(bearerRequest(), mock(HttpServletResponse.class)));

    assertEquals(before + 1, GoLegacyBearer.useCount());
  }

  @Test
  void aCookieRequestIsNotCountedAsALegacyBearerUse() throws Exception {
    stubCookieSession(STALE_WAREHOUSE_ID);
    long before = GoLegacyBearer.useCount();

    assertTrue(authenticator.authenticateRequest(cookieRequest(), mock(HttpServletResponse.class)));

    assertEquals(before, GoLegacyBearer.useCount());
  }

  // ===================== H5 — warehouse correction applies to every scheme =====================

  @Test
  void aCookieSessionWithAnUnreadableWarehouseIsCorrectedLikeTheJwtPath() throws Exception {
    stubCookieSession(STALE_WAREHOUSE_ID);
    stubStaleWarehouse();

    assertTrue(authenticator.authenticateRequest(cookieRequest(), mock(HttpServletResponse.class)));

    swsStatic.verify(() -> SecureWebServicesUtils.createContext(
        USER_ID, ROLE_ID, ORG_ID, CORRECTED_WAREHOUSE_ID, CLIENT_ID));
  }

  @Test
  void aJwtWithAnUnreadableWarehouseIsCorrected() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    stubNoSession();
    stubBearerJwt();
    stubStaleWarehouse();

    assertTrue(authenticator.authenticateRequest(bearerRequest(), mock(HttpServletResponse.class)));

    swsStatic.verify(() -> SecureWebServicesUtils.createContext(
        USER_ID, ROLE_ID, ORG_ID, CORRECTED_WAREHOUSE_ID, CLIENT_ID));
  }

  // ============ One post-authentication step: same checks, once, on every scheme ============

  @Test
  void theAccessPolicyIsConsultedExactlyOnceOnTheCookiePath() throws Exception {
    stubCookieSession(null);

    assertTrue(authenticator.authenticateRequest(cookieRequest(), mock(HttpServletResponse.class)));

    verify(lifecycleService, times(1)).evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class));
  }

  @Test
  void theAccessPolicyIsConsultedExactlyOnceOnTheJwtPath() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    stubNoSession();
    stubBearerJwt();

    assertTrue(authenticator.authenticateRequest(bearerRequest(), mock(HttpServletResponse.class)));

    verify(lifecycleService, times(1)).evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class));
  }

  @Test
  void theAccessPolicyIsConsultedExactlyOnceOnTheOAuth2Path() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    stubNoSession();
    stubOAuth2Token();

    assertTrue(authenticator.authenticateRequest(oauth2Request(), mock(HttpServletResponse.class)));

    verify(lifecycleService, times(1)).evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class));
  }

  @Test
  void theRequestLanguageIsAppliedOnEveryScheme() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");

    stubCookieSession(null);
    assertTrue(authenticator.authenticateRequest(cookieRequest(), mock(HttpServletResponse.class)));
    stubNoSession();
    stubBearerJwt();
    assertTrue(authenticator.authenticateRequest(bearerRequest(), mock(HttpServletResponse.class)));
    stubOAuth2Token();
    assertTrue(authenticator.authenticateRequest(oauth2Request(), mock(HttpServletResponse.class)));

    languageStatic.verify(() -> NeoLanguage.applyToContext(LANGUAGE), times(3));
  }

  // ===================== Credential failures keep their current answers =====================

  @Test
  void aCookieSessionWithoutAnEnvironmentIsRefusedWith401() throws Exception {
    GoSessionRecord noEnvironment = new GoSessionRecord();
    noEnvironment.setUserId(USER_ID);
    when(sessionAuthenticator.authenticate(any()))
        .thenReturn(GoSessionAuthResult.authenticated(noEnvironment));
    HttpServletResponse response = mock(HttpServletResponse.class);

    assertFalse(authenticator.authenticateRequest(cookieRequest(), response));

    verify(servlet).sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
        "Session has no environment selected");
  }

  @Test
  void aFailedCsrfProofIsRefusedWith403() throws Exception {
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.csrfFailed());
    HttpServletResponse response = mock(HttpServletResponse.class);

    assertFalse(authenticator.authenticateRequest(cookieRequest(), response));

    verify(servlet).sendError(response, HttpServletResponse.SC_FORBIDDEN, "CSRF validation failed");
  }

  /** A dead cookie is final: it must not fall through to a Bearer header sent alongside it. */
  @Test
  void anInvalidCookieIsRefusedWith401EvenWithAValidBearerAlongside() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.unauthenticated());
    stubBearerJwt();
    HttpServletResponse response = mock(HttpServletResponse.class);

    assertFalse(authenticator.authenticateRequest(bearerRequest(), response));

    verify(servlet).sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
        "Invalid or expired session");
  }

  @Test
  void noCredentialAtAllIsRefusedWith401() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    stubNoSession();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    assertFalse(authenticator.authenticateRequest(request, response));

    verify(servlet).sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
        "Missing or invalid Authorization header");
  }

  /** Counted before the header was even read, a request with no credential inflated the metric. */
  @Test
  void aRequestWithNoCredentialIsNotCountedAsALegacyBearerUse() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
    stubNoSession();
    long before = GoLegacyBearer.useCount();

    assertFalse(authenticator.authenticateRequest(mock(HttpServletRequest.class),
        mock(HttpServletResponse.class)));

    assertEquals(before, GoLegacyBearer.useCount());
  }

  // ===================== Fixtures =====================

  /**
   * The authenticator under test, wired to the mocked collaborators. The ONLY place that knows
   * how {@link NeoAuthenticator} gets them, so a change to that wiring touches this method alone.
   */
  private NeoAuthenticator buildAuthenticator() {
    return new NeoAuthenticator(servlet, new EnvironmentRequestAuthenticator(
        sessionAuthenticator, lifecycleService, warehouseResolver));
  }

  /** A context whose warehouse belongs to an organization the role cannot read. */
  private void stubStaleWarehouse() {
    Organization foreignOrg = mock(Organization.class);
    when(foreignOrg.getId()).thenReturn(FOREIGN_ORG_ID);
    Warehouse staleWarehouse = mock(Warehouse.class);
    when(staleWarehouse.getOrganization()).thenReturn(foreignOrg);
    when(context.getWarehouse()).thenReturn(staleWarehouse);
    when(context.getReadableOrganizations()).thenReturn(new String[] { ORG_ID });
    when(warehouseResolver.findAccessibleWarehouse(any())).thenReturn(CORRECTED_WAREHOUSE_ID);
  }

  private void stubNoSession() {
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
  }

  private void stubCookieSession(String warehouseId) {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setUserId(USER_ID);
    sessionRecord.setRoleId(ROLE_ID);
    sessionRecord.setCtxOrgId(ORG_ID);
    sessionRecord.setCtxClientId(CLIENT_ID);
    sessionRecord.setWarehouseId(warehouseId);
    when(sessionAuthenticator.authenticate(any()))
        .thenReturn(GoSessionAuthResult.authenticated(sessionRecord));
  }

  private void stubBearerJwt() {
    // Built before the outer when(): nesting stubbing inside an unfinished one throws
    // UnfinishedStubbingException (see NeoAuthenticatorEnvironmentAccessTest#mockJwt).
    DecodedJWT jwt = mockJwt();
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(BEARER_TOKEN)).thenReturn(jwt);
  }

  private void stubOAuth2Token() {
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(OAUTH2_TOKEN)).thenReturn(null);
    Map<String, String> identity = new HashMap<>();
    identity.put(OAuth2Filter.ATTR_USER_ID, USER_ID);
    identity.put(OAuth2Filter.ATTR_ROLE_ID, ROLE_ID);
    identity.put(OAuth2Filter.ATTR_ORG_ID, ORG_ID);
    identity.put(OAuth2Filter.ATTR_CLIENT_ID, CLIENT_ID);
    identity.put(OAuth2Filter.ATTR_SCOPES, "neo:*");
    oauth2FilterStatic.when(() -> OAuth2Filter.validateToken(OAUTH2_TOKEN)).thenReturn(identity);
  }

  private static HttpServletRequest cookieRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeader("Accept-Language")).thenReturn(LANGUAGE);
    return request;
  }

  private static HttpServletRequest bearerRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeader("Authorization")).thenReturn("Bearer " + BEARER_TOKEN);
    when(request.getHeader("Accept-Language")).thenReturn(LANGUAGE);
    return request;
  }

  private static HttpServletRequest oauth2Request() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeader("Authorization")).thenReturn("Bearer " + OAUTH2_TOKEN);
    when(request.getHeader("Accept-Language")).thenReturn(LANGUAGE);
    return request;
  }

  private static DecodedJWT mockJwt() {
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim userClaim = stringClaim(USER_ID);
    Claim roleClaim = stringClaim(ROLE_ID);
    Claim organizationClaim = stringClaim(ORG_ID);
    Claim warehouseClaim = stringClaim(STALE_WAREHOUSE_ID);
    Claim clientClaim = stringClaim(CLIENT_ID);
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
