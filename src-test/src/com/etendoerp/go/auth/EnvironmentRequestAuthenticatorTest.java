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

package com.etendoerp.go.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.auth.EnvironmentAuthOutcome.Status;
import com.etendoerp.go.oauth2.OAuth2Filter;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5455 — the scheme-parity matrix for the single environment pipeline.
 *
 * <p>The acceptance criterion: for the same client and the same commercial decision, every scheme
 * a surface accepts gets the same outcome ({@code 200} / {@code 402} / {@code 401}). The matrix is
 * {@link SurfacePolicy} × {@link AuthScheme} × decision, and the expected cell is computed from
 * the policy's two flags, never from the scheme — which is the property being asserted.
 *
 * <p>No database: every collaborator is a mock and the statics are mocked.
 */
class EnvironmentRequestAuthenticatorTest {

  private static final String CLIENT_ID = "client-1";
  private static final String USER_ID = "user-1";
  private static final String ROLE_ID = "role-1";
  private static final String ORG_ID = "org-1";
  private static final String BEARER_TOKEN = "bearer-token-xyz";
  private static final String OAUTH2_TOKEN = "oauth2-opaque-token-xyz";
  private static final String LANGUAGE = "es_ES";
  private static final String LEGACY_BEARER_PROPERTY = "etgo.legacy.bearer.enabled";

  private GoSessionAuthenticator sessionAuthenticator;
  private TenantEnvironmentLifecycleService lifecycleService;
  private WarehouseResolver warehouseResolver;
  private EnvironmentRequestAuthenticator authenticator;
  private OBContext context;

  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<SecureWebServicesUtils> swsStatic;
  private MockedStatic<OAuth2Filter> oauth2FilterStatic;
  private MockedStatic<NeoLanguage> languageStatic;

  @BeforeEach
  void setUp() {
    sessionAuthenticator = mock(GoSessionAuthenticator.class);
    lifecycleService = mock(TenantEnvironmentLifecycleService.class);
    warehouseResolver = mock(WarehouseResolver.class);
    authenticator = new EnvironmentRequestAuthenticator(sessionAuthenticator, lifecycleService,
        warehouseResolver);

    obContextStatic = mockStatic(OBContext.class);
    swsStatic = mockStatic(SecureWebServicesUtils.class);
    oauth2FilterStatic = mockStatic(OAuth2Filter.class);
    languageStatic = mockStatic(NeoLanguage.class);
    context = mock(OBContext.class);
    swsStatic.when(() -> SecureWebServicesUtils.createContext(
        anyString(), anyString(), anyString(), any(), anyString())).thenReturn(context);
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
  }

  @AfterEach
  void tearDown() {
    languageStatic.close();
    oauth2FilterStatic.close();
    swsStatic.close();
    obContextStatic.close();
    System.clearProperty(LEGACY_BEARER_PROPERTY);
  }

  // ============================== the matrix ==============================

  static Stream<Arguments> matrix() {
    List<Arguments> cells = new ArrayList<>();
    Decision[] decisions = { Decision.ALLOWED, null, Decision.SUBSCRIPTION_REQUIRED,
        Decision.DEMO_TRIAL_EXPIRED };
    for (SurfacePolicy policy : SurfacePolicy.values()) {
      for (AuthScheme scheme : AuthScheme.values()) {
        for (Decision decision : decisions) {
          cells.add(Arguments.of(policy, scheme, decision));
        }
      }
    }
    return cells.stream();
  }

  @ParameterizedTest(name = "{0} × {1} × {2}")
  @MethodSource("matrix")
  void everySchemeGetsTheOutcomeItsPolicyDictates(SurfacePolicy policy, AuthScheme scheme,
      Decision decision) {
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(decision);

    EnvironmentAuthOutcome outcome = authenticator.authenticate(requestFor(scheme), policy);

    boolean schemeAccepted = scheme != AuthScheme.OAUTH2 || policy.isOAuth2Allowed();
    boolean blocked = decision != null && decision != Decision.ALLOWED;
    if (!schemeAccepted) {
      assertEquals(Status.UNAUTHENTICATED, outcome.getStatus());
      assertEquals(401, outcome.getHttpStatus());
      assertEquals("Invalid or expired token", outcome.getMessage());
      verify(lifecycleService, never()).evaluateAccess(anyString(), eq(true), any(Instant.class));
    } else if (policy.isCommercialAccessRequired() && blocked) {
      assertEquals(Status.PAYMENT_REQUIRED, outcome.getStatus());
      assertEquals(402, outcome.getHttpStatus());
      assertEquals("Environment access is not available: " + decision.name(),
          outcome.getMessage());
      assertEquals(scheme, outcome.getScheme());
    } else {
      assertTrue(outcome.isAuthenticated(), "expected the request to be let through");
      assertEquals(scheme, outcome.getScheme());
      assertSame(context, outcome.getContext());
      assertNull(outcome.getMessage());
    }

    int expectedPolicyChecks = schemeAccepted && policy.isCommercialAccessRequired() ? 1 : 0;
    verify(lifecycleService, times(expectedPolicyChecks))
        .evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class));
    languageStatic.verify(() -> NeoLanguage.applyToContext(LANGUAGE),
        times(outcome.isAuthenticated() ? 1 : 0));
  }

  /** The auxiliary surfaces are the ones a blocked customer must still reach (support, surveys). */
  @ParameterizedTest(name = "{0}")
  @EnumSource(value = AuthScheme.class, names = { "COOKIE", "JWT" })
  void anAuxiliarySurfaceNeverConsultsTheCommercialPolicy(AuthScheme scheme) {
    when(lifecycleService.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.SUBSCRIPTION_REQUIRED);

    EnvironmentAuthOutcome outcome =
        authenticator.authenticate(requestFor(scheme), SurfacePolicy.NEO_AUXILIARY);

    assertTrue(outcome.isAuthenticated());
    verify(lifecycleService, never()).evaluateAccess(anyString(), eq(true), any(Instant.class));
  }

  // ============================== kill switch ==============================

  @ParameterizedTest(name = "{0}")
  @EnumSource(SurfacePolicy.class)
  void withTheSwitchOffAJwtIsRefusedOnEverySurface(SurfacePolicy policy) {
    System.setProperty(LEGACY_BEARER_PROPERTY, "false");

    EnvironmentAuthOutcome outcome = authenticator.authenticate(requestFor(AuthScheme.JWT), policy);

    assertEquals(Status.UNAUTHENTICATED, outcome.getStatus());
    assertEquals("Missing or invalid Authorization header", outcome.getMessage());
  }

  @Test
  void withTheSwitchOffOAuth2StillAuthenticatesWhereItIsAllowed() {
    System.setProperty(LEGACY_BEARER_PROPERTY, "false");

    EnvironmentAuthOutcome outcome =
        authenticator.authenticate(requestFor(AuthScheme.OAUTH2), SurfacePolicy.NEO_API);

    assertTrue(outcome.isAuthenticated());
    assertEquals(AuthScheme.OAUTH2, outcome.getScheme());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(SurfacePolicy.class)
  void withTheSwitchOffTheCookieStillAuthenticates(SurfacePolicy policy) {
    System.setProperty(LEGACY_BEARER_PROPERTY, "false");

    assertTrue(authenticator.authenticate(requestFor(AuthScheme.COOKIE), policy).isAuthenticated());
  }

  // ============================== credential failures ==============================

  @ParameterizedTest(name = "{0}")
  @EnumSource(SurfacePolicy.class)
  void aFailedCsrfProofIs403OnEverySurface(SurfacePolicy policy) {
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.csrfFailed());

    EnvironmentAuthOutcome outcome = authenticator.authenticate(mock(HttpServletRequest.class), policy);

    assertEquals(Status.CSRF_REJECTED, outcome.getStatus());
    assertEquals(403, outcome.getHttpStatus());
    assertEquals("CSRF validation failed", outcome.getMessage());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(SurfacePolicy.class)
  void anInvalidCookieIs401AndNeverFallsBackToTheBearer(SurfacePolicy policy) {
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.unauthenticated());

    EnvironmentAuthOutcome outcome = authenticator.authenticate(bearerRequest(BEARER_TOKEN), policy);

    assertEquals(401, outcome.getHttpStatus());
    assertEquals("Invalid or expired session", outcome.getMessage());
    swsStatic.verify(() -> SecureWebServicesUtils.decodeToken(anyString()), never());
  }

  @Test
  void aCookieWithoutAnEnvironmentIs401() {
    GoSessionRecord noEnvironment = new GoSessionRecord();
    noEnvironment.setUserId(USER_ID);
    when(sessionAuthenticator.authenticate(any()))
        .thenReturn(GoSessionAuthResult.authenticated(noEnvironment));

    EnvironmentAuthOutcome outcome =
        authenticator.authenticate(mock(HttpServletRequest.class), SurfacePolicy.NEO_API);

    assertEquals(401, outcome.getHttpStatus());
    assertEquals("Session has no environment selected", outcome.getMessage());
  }

  @Test
  void noCredentialIs401() {
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());

    EnvironmentAuthOutcome outcome =
        authenticator.authenticate(mock(HttpServletRequest.class), SurfacePolicy.NEO_API);

    assertEquals(401, outcome.getHttpStatus());
    assertEquals("Missing or invalid Authorization header", outcome.getMessage());
  }

  @Test
  void aJwtWithoutRequiredClaimsIs401() {
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    DecodedJWT jwt = mockJwt(null);
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(BEARER_TOKEN)).thenReturn(jwt);

    EnvironmentAuthOutcome outcome =
        authenticator.authenticate(bearerRequest(BEARER_TOKEN), SurfacePolicy.NEO_API);

    assertEquals(401, outcome.getHttpStatus());
    assertEquals("Invalid token: missing required claims", outcome.getMessage());
  }

  @Test
  void anOAuth2TokenWithAReadScopeCannotWrite() {
    when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
    stubOAuth2("neo:read");
    HttpServletRequest request = bearerRequest(OAUTH2_TOKEN);
    when(request.getMethod()).thenReturn("POST");

    EnvironmentAuthOutcome outcome = authenticator.authenticate(request, SurfacePolicy.NEO_API);

    assertEquals(401, outcome.getHttpStatus());
    assertEquals("Insufficient scope or invalid token context", outcome.getMessage());
    verify(lifecycleService, never()).evaluateAccess(anyString(), eq(true), any(Instant.class));
  }

  /** Our own OBException messages are safe to show... */
  @Test
  void anOBExceptionWhileBindingKeepsItsMessage() {
    stubCookie();
    swsStatic.when(() -> SecureWebServicesUtils.createContext(
        anyString(), anyString(), anyString(), any(), anyString()))
        .thenThrow(new OBException("Role not granted"));
    HttpServletRequest request = mock(HttpServletRequest.class);

    EnvironmentAuthOutcome outcome = authenticator.authenticate(request, SurfacePolicy.NEO_API);

    assertEquals(401, outcome.getHttpStatus());
    assertEquals("Role not granted", outcome.getMessage());
  }

  /** ...anything else (NPEs, driver errors) must not leak internals. */
  @Test
  void anyOtherFailureWhileBindingIsTheGenericInvalidToken() {
    stubCookie();
    swsStatic.when(() -> SecureWebServicesUtils.createContext(
        anyString(), anyString(), anyString(), any(), anyString()))
        .thenThrow(new IllegalStateException("db connection reset"));
    HttpServletRequest request = mock(HttpServletRequest.class);

    EnvironmentAuthOutcome outcome = authenticator.authenticate(request, SurfacePolicy.NEO_API);

    assertFalse(outcome.isAuthenticated());
    assertEquals(401, outcome.getHttpStatus());
    assertEquals("Invalid or expired token", outcome.getMessage());
  }

  // ============================== fixtures ==============================

  /** A request carrying exactly one credential of the given scheme, the collaborators agreeing. */
  private HttpServletRequest requestFor(AuthScheme scheme) {
    switch (scheme) {
      case COOKIE:
        stubCookie();
        HttpServletRequest cookieRequest = mock(HttpServletRequest.class);
        when(cookieRequest.getMethod()).thenReturn("GET");
        when(cookieRequest.getHeader("Accept-Language")).thenReturn(LANGUAGE);
        return cookieRequest;
      case JWT:
        when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
        stubJwt();
        return bearerRequest(BEARER_TOKEN);
      case OAUTH2:
      default:
        when(sessionAuthenticator.authenticate(any())).thenReturn(GoSessionAuthResult.noSession());
        stubOAuth2("neo:*");
        return bearerRequest(OAUTH2_TOKEN);
    }
  }

  private void stubCookie() {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setUserId(USER_ID);
    sessionRecord.setRoleId(ROLE_ID);
    sessionRecord.setCtxOrgId(ORG_ID);
    sessionRecord.setCtxClientId(CLIENT_ID);
    when(sessionAuthenticator.authenticate(any()))
        .thenReturn(GoSessionAuthResult.authenticated(sessionRecord));
  }

  private void stubJwt() {
    DecodedJWT jwt = mockJwt(USER_ID);
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(BEARER_TOKEN)).thenReturn(jwt);
  }

  private void stubOAuth2(String scopes) {
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(OAUTH2_TOKEN)).thenReturn(null);
    Map<String, String> identity = new HashMap<>();
    identity.put(OAuth2Filter.ATTR_USER_ID, USER_ID);
    identity.put(OAuth2Filter.ATTR_ROLE_ID, ROLE_ID);
    identity.put(OAuth2Filter.ATTR_ORG_ID, ORG_ID);
    identity.put(OAuth2Filter.ATTR_CLIENT_ID, CLIENT_ID);
    identity.put(OAuth2Filter.ATTR_SCOPES, scopes);
    oauth2FilterStatic.when(() -> OAuth2Filter.validateToken(OAUTH2_TOKEN)).thenReturn(identity);
  }

  private static HttpServletRequest bearerRequest(String token) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
    when(request.getHeader("Accept-Language")).thenReturn(LANGUAGE);
    return request;
  }

  private static DecodedJWT mockJwt(String userId) {
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim userClaim = stringClaim(userId);
    Claim roleClaim = stringClaim(ROLE_ID);
    Claim organizationClaim = stringClaim(ORG_ID);
    Claim warehouseClaim = stringClaim(null);
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
