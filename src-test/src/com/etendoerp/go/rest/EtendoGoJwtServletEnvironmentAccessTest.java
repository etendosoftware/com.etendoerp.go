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

package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.IssuedGoSession;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5047 — the commercial access check on the environment entry points of
 * {@link EtendoGoJwtServlet}.
 *
 * <ul>
 *   <li>{@code GET /sws/go/login} hands out a raw Etendo JWT valid on every secure web service of
 *       the tenant, so a blocked tenant is refused with the shared 402 body and gets no token.</li>
 *   <li>{@code POST /sws/go/session/environment} deliberately does NOT refuse (the blocked screen
 *       and the pay pages render inside the entered environment); it answers 200 and reports the
 *       decision as {@code accessDecision}, absent when the tenant is allowed.</li>
 * </ul>
 *
 * <p>The decision comes from the servlet's own lifecycle service (mocked here) through the shared
 * {@code EnvironmentAccessGuard}, so the real kill switch applies.
 */
public class EtendoGoJwtServletEnvironmentAccessTest {

  private static final String LEGACY_BEARER_PROPERTY = "etgo.legacy.bearer.enabled";
  private static final String KILL_SWITCH_PROPERTY =
      "etendo.go.flags.environment-access-enforcement-off";
  private static final String ORIGIN = "https://app.example.test";
  private static final String CSRF = "csrf-token-value-123456";
  private static final String USER_ID = "U1";
  private static final String CLIENT_ID = "C1";

  private final GoSessionService goSessionService = mock(GoSessionService.class);
  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
      mock(TransactionalAuthEmailSender.class), mock(EtendoGoSsoProviderRegistry.class),
      goSessionService);
  private final TenantEnvironmentLifecycleService lifecycle =
      mock(TenantEnvironmentLifecycleService.class);

  private MockedStatic<OBContext> obContext;
  private MockedStatic<EtendoGoJwtDalHelper> dalHelper;
  private MockedStatic<EtendoGoJwtSupport> support;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<SecureWebServicesUtils> sws;
  private User user;
  private Role role;

  @Before
  public void setUp() throws Exception {
    servlet.sessionRoleReconciler = mock(GoSessionRoleReconciler.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");

    obContext = mockStatic(OBContext.class);
    dalHelper = mockStatic(EtendoGoJwtDalHelper.class);
    support = mockStatic(EtendoGoJwtSupport.class);
    obDal = mockStatic(OBDal.class);
    sws = mockStatic(SecureWebServicesUtils.class);

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    dalHelper.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
        .thenReturn(account);
    dalHelper.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
    support.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount("user@test.com",
        USER_ID)).thenReturn(true);
    support.when(() -> EtendoGoJwtSupport.loadRoleListData(USER_ID)).thenReturn(
        new EtendoGoJwtSupport.RoleListData("R1", new JSONArray().put(new JSONObject()
            .put("id", "R1")
            .put("orgList", new JSONArray().put(new JSONObject().put("id", "O1"))))));

    Client tenant = mock(Client.class);
    when(tenant.getId()).thenReturn(CLIENT_ID);
    user = mock(User.class);
    when(user.getClient()).thenReturn(tenant);
    role = mock(Role.class);
    OBDal dal = mock(OBDal.class);
    when(dal.get(User.class, USER_ID)).thenReturn(user);
    when(dal.get(Role.class, "R1")).thenReturn(role);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    sws.when(() -> SecureWebServicesUtils.generateToken(user, role)).thenReturn("jwt-token");
  }

  @After
  public void tearDown() {
    sws.close();
    obDal.close();
    support.close();
    dalHelper.close();
    obContext.close();
    System.clearProperty(LEGACY_BEARER_PROPERTY);
    System.clearProperty(KILL_SWITCH_PROPERTY);
  }

  private void decide(Decision decision) {
    when(lifecycle.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(decision);
  }

  // ===================== GET /login =====================

  @Test
  public void environmentLoginIntoABlockedTenantIsRefusedWith402AndNoToken() throws Exception {
    decide(Decision.SUBSCRIPTION_REQUIRED);
    Captured resp = new Captured();

    servlet.doGet(loginRequest(), resp.response);

    assertEquals(HttpServletResponse.SC_PAYMENT_REQUIRED, resp.status);
    JSONObject body = new JSONObject(resp.body.toString());
    assertFalse("a blocked tenant must not get an Etendo JWT", body.has("token"));
    JSONObject error = body.getJSONObject("error");
    assertEquals("Environment access is not available: SUBSCRIPTION_REQUIRED",
        error.getString("message"));
    assertEquals(402, error.getInt("status"));
    assertEquals("ENVIRONMENT_ACCESS_DENIED", error.getString("code"));
    assertEquals("SUBSCRIPTION_REQUIRED", error.getString("decision"));
    sws.verify(() -> SecureWebServicesUtils.generateToken(any(), any()), never());
    support.verify(() -> EtendoGoJwtSupport.loadRoleListData(anyString()), never());
  }

  @Test
  public void environmentLoginIntoAnExpiredDemoReportsThatDecision() throws Exception {
    decide(Decision.DEMO_TRIAL_EXPIRED);
    Captured resp = new Captured();

    servlet.doGet(loginRequest(), resp.response);

    assertEquals(HttpServletResponse.SC_PAYMENT_REQUIRED, resp.status);
    assertEquals("DEMO_TRIAL_EXPIRED", new JSONObject(resp.body.toString())
        .getJSONObject("error").getString("decision"));
  }

  @Test
  public void environmentLoginIntoAnAllowedTenantGetsItsToken() throws Exception {
    decide(Decision.ALLOWED);
    Captured resp = new Captured();

    servlet.doGet(loginRequest(), resp.response);

    assertEquals(HttpServletResponse.SC_OK, resp.status);
    assertEquals("jwt-token", new JSONObject(resp.body.toString()).getString("token"));
  }

  @Test
  public void environmentLoginWhileEnforcementIsSwitchedOffGetsItsToken() throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "true");
    decide(Decision.SUBSCRIPTION_REQUIRED);
    Captured resp = new Captured();

    servlet.doGet(loginRequest(), resp.response);

    assertEquals(HttpServletResponse.SC_OK, resp.status);
    assertEquals("jwt-token", new JSONObject(resp.body.toString()).getString("token"));
  }

  @Test
  public void environmentLoginForAUserNotOwnedIsStill403BeforeAnyAccessCheck() throws Exception {
    support.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount("user@test.com",
        USER_ID)).thenReturn(false);
    Captured resp = new Captured();

    servlet.doGet(loginRequest(), resp.response);

    assertEquals(HttpServletResponse.SC_FORBIDDEN, resp.status);
    verify(lifecycle, never()).evaluateAccess(anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
        any());
  }

  // ===================== POST /session/environment =====================

  @Test
  public void enteringABlockedTenantSucceedsAndReportsTheAccessDecision() throws Exception {
    decide(Decision.SUBSCRIPTION_REQUIRED);
    Captured resp = new Captured();

    servlet.doPost(sessionEnvironmentRequest(), resp.response);

    assertEquals("entry is not refused: the pay pages render inside the environment",
        HttpServletResponse.SC_OK, resp.status);
    JSONObject body = new JSONObject(resp.body.toString());
    assertEquals("SUBSCRIPTION_REQUIRED", body.getString("accessDecision"));
    assertEquals("newcsrf", body.getString("csrfToken"));
    assertTrue(body.has("environment"));
    verify(goSessionService).rotate(any());
  }

  @Test
  public void enteringAnAllowedTenantCarriesNoAccessDecision() throws Exception {
    decide(Decision.ALLOWED);
    Captured resp = new Captured();

    servlet.doPost(sessionEnvironmentRequest(), resp.response);

    assertEquals(HttpServletResponse.SC_OK, resp.status);
    assertFalse(new JSONObject(resp.body.toString()).has("accessDecision"));
  }

  @Test
  public void enteringALegacyTenantWithNoDecisionCarriesNoAccessDecision() throws Exception {
    decide(null);
    Captured resp = new Captured();

    servlet.doPost(sessionEnvironmentRequest(), resp.response);

    assertEquals(HttpServletResponse.SC_OK, resp.status);
    assertFalse(new JSONObject(resp.body.toString()).has("accessDecision"));
  }

  @Test
  public void enteringABlockedTenantWhileEnforcementIsSwitchedOffCarriesNoAccessDecision()
      throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "true");
    decide(Decision.DEMO_TRIAL_EXPIRED);
    Captured resp = new Captured();

    servlet.doPost(sessionEnvironmentRequest(), resp.response);

    assertEquals(HttpServletResponse.SC_OK, resp.status);
    assertFalse(new JSONObject(resp.body.toString()).has("accessDecision"));
  }

  // ===================== fixtures =====================

  private static HttpServletRequest loginRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn("/login");
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(request.getParameter("userId")).thenReturn(USER_ID);
    return request;
  }

  private HttpServletRequest sessionEnvironmentRequest() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);

    DecodedJWT decoded = mock(DecodedJWT.class);
    Claim userClaim = claim(USER_ID);
    Claim roleClaim = claim("R1");
    Claim clientClaim = claim(CLIENT_ID);
    Claim orgClaim = claim("O1");
    Claim warehouseClaim = claim("W1");
    when(decoded.getClaim("user")).thenReturn(userClaim);
    when(decoded.getClaim("role")).thenReturn(roleClaim);
    when(decoded.getClaim("client")).thenReturn(clientClaim);
    when(decoded.getClaim("organization")).thenReturn(orgClaim);
    when(decoded.getClaim("warehouse")).thenReturn(warehouseClaim);
    sws.when(() -> SecureWebServicesUtils.decodeToken("jwt-token")).thenReturn(decoded);

    GoSessionRecord rotatedRecord = new GoSessionRecord();
    rotatedRecord.setUserId(USER_ID);
    rotatedRecord.setRoleId("R1");
    rotatedRecord.setCtxClientId(CLIENT_ID);
    rotatedRecord.setCtxOrgId("O1");
    rotatedRecord.setWarehouseId("W1");
    when(goSessionService.rotate(any())).thenReturn(
        new IssuedGoSession("newtok", "newref", "newcsrf", rotatedRecord));

    String json = new JSONObject().put("userId", USER_ID).put("roleId", "R1").put("orgId", "O1")
        .toString();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");
    when(request.getPathInfo()).thenReturn("/session/environment");
    when(request.getContentType()).thenReturn("application/json");
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    when(request.getHeader("Origin")).thenReturn(ORIGIN);
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(CSRF);
    when(request.getRequestURL())
        .thenReturn(new StringBuffer(ORIGIN + "/sws/go/session/environment"));
    when(request.getCookies())
        .thenReturn(new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "tok") });
    return request;
  }

  private static Claim claim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }

  private static final class Captured {
    final HttpServletResponse response = mock(HttpServletResponse.class);
    final StringWriter body = new StringWriter();
    int status;

    Captured() throws Exception {
      doAnswer(invocation -> {
        status = invocation.getArgument(0);
        return null;
      }).when(response).setStatus(anyInt());
      when(response.getWriter()).thenReturn(new PrintWriter(body));
    }
  }
}
