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

package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.common.BoundedJsonParserTest;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.oauth2.OAuth2Filter;
import com.etendoerp.go.payment.EnvironmentAccessGuard;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.smf.securewebservices.utils.SecureWebServicesUtils;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.etendoerp.go.session.SessionRoleRevokedException;
import com.etendoerp.go.usageevents.LogCapture;

/**
 * Unit tests for {@link McpServlet} covering CORS, authentication, JSON-RPC
 * dispatch, error handling, GET endpoints, and inner classes.
 *
 * @covers com.etendoerp.go.mcp.McpServlet
 * @covers com.etendoerp.go.mcp.McpModernResults
 */
public class McpServletTest {

  private McpServlet servlet;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private StringWriter responseBody;
  private PrintWriter writer;
  private EnvironmentAccessGuard environmentAccessGuard;
  private GoSessionRoleReconciler roleReconciler;

  private static final String ENFORCEMENT_OFF_PROPERTY =
      "etendo.go.flags.environment-access-enforcement-off";

  @Before
  public void setUp() throws Exception {
    servlet = new McpServlet();
    // ETP-5047 — doPost asks the commercial access guard, which runs as system against the DAL.
    // A mock that allows every tenant (null denial) keeps these dispatch tests DB-free; the
    // refusal itself is pinned by the environment-access tests below.
    environmentAccessGuard = mock(EnvironmentAccessGuard.class);
    Field guardField = McpServlet.class.getDeclaredField("environmentAccessGuard");
    guardField.setAccessible(true);
    guardField.set(servlet, environmentAccessGuard);
    // ETP-5270 — the role lookups hit AD_User_Roles; a mock is "role still held, no rebind".
    roleReconciler = mock(GoSessionRoleReconciler.class);
    servlet.sessionRoleReconciler = roleReconciler;
    newExchange();

    System.setProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY, "https://example.com/mcp");
  }

  /** A fresh request/response pair, so one test can send several requests. */
  private void newExchange() throws Exception {
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    responseBody = new StringWriter();
    writer = new PrintWriter(responseBody);
    when(response.getWriter()).thenReturn(writer);

    when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/etendo/sws/mcp"));
    when(request.getScheme()).thenReturn("http");
    when(request.getServerName()).thenReturn("localhost");
    when(request.getServerPort()).thenReturn(8080);
    when(request.getContextPath()).thenReturn("/etendo");
  }

  @After
  public void tearDown() {
    System.clearProperty(KILL_SWITCH_PROPERTY);
    System.clearProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY);
    System.clearProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY);
    System.clearProperty(ENFORCEMENT_OFF_PROPERTY);
  }

  private void setRequestBody(String body) throws Exception {
    BufferedReader reader = new BufferedReader(new StringReader(body));
    when(request.getReader()).thenReturn(reader);
  }

  private void setOAuth2FilterAttributes(String userId, String roleId,
      String clientId, String orgId, String scopes) {
    when(request.getAttribute(OAuth2Filter.ATTR_USER_ID)).thenReturn(userId);
    when(request.getAttribute(OAuth2Filter.ATTR_ROLE_ID)).thenReturn(roleId);
    when(request.getAttribute(OAuth2Filter.ATTR_CLIENT_ID)).thenReturn(clientId);
    when(request.getAttribute(OAuth2Filter.ATTR_ORG_ID)).thenReturn(orgId);
    when(request.getAttribute(OAuth2Filter.ATTR_SCOPES)).thenReturn(scopes);
  }

  private String getResponseBody() {
    writer.flush();
    return responseBody.toString();
  }

  // ── authenticate: credential schemes (ETP-4576) ─────────────────────────

  private McpServlet.AuthIdentity invokeAuthenticate() throws Exception {
    Method authenticate = McpServlet.class.getDeclaredMethod("authenticate",
        HttpServletRequest.class, HttpServletResponse.class);
    authenticate.setAccessible(true);
    return (McpServlet.AuthIdentity) authenticate.invoke(servlet, request, response);
  }

  private McpServlet.AuthIdentity invokeSessionIdentity(GoSessionRecord session) throws Exception {
    Method sessionIdentity = McpServlet.class.getDeclaredMethod("sessionIdentity",
        HttpServletRequest.class, HttpServletResponse.class, GoSessionRecord.class);
    sessionIdentity.setAccessible(true);
    return (McpServlet.AuthIdentity) sessionIdentity.invoke(servlet, request, response, session);
  }

  private static GoSessionRecord sessionRecord(String userId, String roleId, String clientId,
      String orgId) {
    GoSessionRecord session = new GoSessionRecord();
    session.setUserId(userId);
    session.setRoleId(roleId);
    session.setCtxClientId(clientId);
    session.setCtxOrgId(orgId);
    return session;
  }

  /**
   * The pre-existing contract: a request that already carries a validated OAuth2 credential is
   * resolved from the filter's attributes and never reaches the cookie path. Adding the cookie
   * scheme must not change what an MCP client (Claude, the IDE) sees.
   */
  @Test
  public void authenticateStillResolvesTheOAuth2FilterIdentity() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");

    McpServlet.AuthIdentity identity = invokeAuthenticate();

    assertNotNull(identity);
    assertEquals("user1", identity.userId);
    assertEquals("neo:read", identity.scopes);
  }

  /**
   * And the other half of "nothing changed": no Bearer header AND no session cookie still ends in
   * the same 401, so an MCP client's OAuth discovery keeps being triggered exactly as before.
   */
  @Test
  public void authenticateWithNoCredentialOfEitherSchemeStillReturns401() throws Exception {
    when(request.getHeader("Authorization")).thenReturn(null);
    when(request.getCookies()).thenReturn(null);

    assertNull(invokeAuthenticate());

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    assertTrue(getResponseBody().contains("Missing Authorization"));
  }

  /**
   * ETP-4576 — a resolved cookie session yields the same identity shape and the same scope set as
   * the validated legacy JWT path. Granting less would make one user see a different tool catalog
   * depending only on which credential scheme the backend happened to issue.
   */
  @Test
  public void sessionIdentityBuildsTheIdentityFromAResolvedCookieSession() throws Exception {
    McpServlet.AuthIdentity identity =
        invokeSessionIdentity(sessionRecord("user1", "role1", "client1", "org1"));

    assertNotNull(identity);
    assertEquals("user1", identity.userId);
    assertEquals("role1", identity.roleId);
    assertEquals("client1", identity.clientId);
    assertEquals("org1", identity.orgId);
    assertEquals("etendo:read etendo:write etendo:process etendo:report", identity.scopes);
  }

  /**
   * The tenant scope is what every downstream query is filtered by, so a session that has not
   * selected an environment is rejected rather than defaulted.
   */
  @Test
  public void sessionIdentityRejectsASessionWithNoEnvironmentSelected() throws Exception {
    assertNull(invokeSessionIdentity(sessionRecord("user1", "role1", null, null)));

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    assertTrue(getResponseBody().contains("no environment selected"));
  }

  // ── authenticate: role revalidation (ETP-5270) ──────────────────────────

  /**
   * The copilot reaches MCP with the cookie session, and every other surface already rebinds a
   * role an admin revoked since the session entered (ETP-5395). The identity must carry the role
   * the user holds now, so RBAC filters the tool catalog with it.
   */
  @Test
  public void sessionIdentityCarriesTheReboundRole() throws Exception {
    GoSessionRecord session = sessionRecord("user1", "revoked-role", "client1", "org1");
    when(roleReconciler.reconcile(session)).thenAnswer(invocation -> {
      session.setRoleId("rebound-role");
      return true;
    });

    McpServlet.AuthIdentity identity = invokeSessionIdentity(session);

    assertNotNull(identity);
    assertEquals("rebound-role", identity.roleId);
  }

  @Test
  public void sessionIdentityRejectsASessionWhoseUserHoldsNoRoleLeft() throws Exception {
    GoSessionRecord session = sessionRecord("user1", "revoked-role", "client1", "org1");
    when(roleReconciler.reconcile(session))
        .thenThrow(new SessionRoleRevokedException("no role left"));

    assertNull(invokeSessionIdentity(session));

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    assertTrue(getResponseBody().contains("no role left"));
  }

  /** A legacy JWT cannot be rebound: a role revoked since it was issued refuses the request. */
  @Test
  public void authenticateRefusesALegacyJwtWhoseRoleWasRevoked() throws Exception {
    when(request.getHeader("Authorization")).thenReturn("Bearer legacy-jwt");
    doThrow(new SessionRoleRevokedException(GoSessionRoleReconciler.MSG_TOKEN_ROLE_REVOKED))
        .when(roleReconciler).requireHeldRole("user1", "role1", "client1");

    try (MockedStatic<OAuth2Filter> oauth2Mock = mockStatic(OAuth2Filter.class);
         MockedStatic<com.smf.securewebservices.utils.SecureWebServicesUtils> jwtMock =
             mockStatic(com.smf.securewebservices.utils.SecureWebServicesUtils.class)) {
      oauth2Mock.when(() -> OAuth2Filter.validateToken("legacy-jwt")).thenReturn(null);
      com.auth0.jwt.interfaces.DecodedJWT jwt = legacyJwt();
      jwtMock.when(() -> com.smf.securewebservices.utils.SecureWebServicesUtils
          .decodeToken("legacy-jwt")).thenReturn(jwt);

      assertNull(invokeAuthenticate());
    }

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    assertTrue(getResponseBody().contains(GoSessionRoleReconciler.MSG_TOKEN_ROLE_REVOKED));
  }

  @Test
  public void authenticateAcceptsALegacyJwtWhoseRoleIsStillHeld() throws Exception {
    when(request.getHeader("Authorization")).thenReturn("Bearer legacy-jwt");

    McpServlet.AuthIdentity identity;
    try (MockedStatic<OAuth2Filter> oauth2Mock = mockStatic(OAuth2Filter.class);
         MockedStatic<com.smf.securewebservices.utils.SecureWebServicesUtils> jwtMock =
             mockStatic(com.smf.securewebservices.utils.SecureWebServicesUtils.class)) {
      oauth2Mock.when(() -> OAuth2Filter.validateToken("legacy-jwt")).thenReturn(null);
      com.auth0.jwt.interfaces.DecodedJWT jwt = legacyJwt();
      jwtMock.when(() -> com.smf.securewebservices.utils.SecureWebServicesUtils
          .decodeToken("legacy-jwt")).thenReturn(jwt);

      identity = invokeAuthenticate();
    }

    assertNotNull(identity);
    assertEquals("role1", identity.roleId);
    verify(roleReconciler).requireHeldRole("user1", "role1", "client1");
  }

  private static com.auth0.jwt.interfaces.DecodedJWT legacyJwt() {
    com.auth0.jwt.interfaces.DecodedJWT jwt = mock(com.auth0.jwt.interfaces.DecodedJWT.class);
    Map<String, String> claims = new HashMap<>();
    claims.put("user", "user1");
    claims.put("role", "role1");
    claims.put("client", "client1");
    claims.put("organization", "org1");
    for (Map.Entry<String, String> entry : claims.entrySet()) {
      com.auth0.jwt.interfaces.Claim claim = mock(com.auth0.jwt.interfaces.Claim.class);
      when(claim.asString()).thenReturn(entry.getValue());
      when(jwt.getClaim(entry.getKey())).thenReturn(claim);
    }
    return jwt;
  }

  // ── doOptions ───────────────────────────────────────────────────────────

  @Test
  public void doOptionsReturns204NoContent() throws Exception {
    servlet.doOptions(request, response);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  // ── doGet: no SSE stream → 405 (ETP-5639) ───────────────────────────────

  /**
   * A Streamable HTTP server without an SSE stream MUST answer GET with 405; it used to answer an
   * informational JSON. The .well-known metadata shares doGet and must keep answering (next tests).
   */
  @Test
  public void doGetOnTheEndpointAnswers405WithAllowHeader() throws Exception {
    when(request.getPathInfo()).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    verify(response).setHeader("Allow", "POST, OPTIONS");
    assertTrue(new JSONObject(getResponseBody()).has("error"));
  }

  @Test
  public void doGetWithNonWellKnownPathAnswers405() throws Exception {
    when(request.getPathInfo()).thenReturn("/some/other/path");

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
  }

  @Test
  public void doGetWellKnownIsNotRefusedWith405() throws Exception {
    System.setProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY, "https://example.com/oauth2");
    when(request.getPathInfo()).thenReturn("/.well-known/oauth-protected-resource");

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    org.mockito.Mockito.verify(response, org.mockito.Mockito.never())
        .setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    assertTrue(new JSONObject(getResponseBody()).has("resource"));
  }

  @Test
  public void doGetWellKnownReturnsMetadata() throws Exception {
    System.setProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY, "https://example.com/mcp");
    System.setProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY, "https://example.com/oauth2");
    when(request.getPathInfo()).thenReturn("/.well-known/oauth-protected-resource");

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    // Exactly once: the metadata path owns its content type, as the 405 path's error writer does.
    verify(response).setContentType("application/json;charset=UTF-8");
    JSONObject meta = new JSONObject(getResponseBody());
    assertEquals("https://example.com/mcp", meta.getString("resource"));
    assertEquals("https://example.com/oauth2",
        meta.getJSONArray("authorization_servers").getString(0));
    // Only the current etendo: scopes are advertised; the deprecated neo: aliases are accepted
    // but never listed (ETP-5602). Each scope is its own array entry.
    assertEquals("[\"etendo:read\",\"etendo:write\",\"etendo:process\",\"etendo:report\","
        + "\"etendo:*\"]", meta.getJSONArray("scopes_supported").toString());
    assertTrue(meta.has("bearer_methods_supported"));
  }

  // ── doPost: authentication ──────────────────────────────────────────────

  @Test
  public void doPostWithNoAuthHeaderReturns401() throws Exception {
    when(request.getAttribute(OAuth2Filter.ATTR_USER_ID)).thenReturn(null);
    when(request.getHeader("Authorization")).thenReturn(null);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    String body = getResponseBody();
    assertTrue(body.contains("error"));
    assertTrue(body.contains("Missing Authorization"));
  }

  @Test
  public void doPostWithInvalidAuthPrefixReturns401() throws Exception {
    when(request.getAttribute(OAuth2Filter.ATTR_USER_ID)).thenReturn(null);
    when(request.getHeader("Authorization")).thenReturn("Basic abc123");

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  public void doPostWithOAuth2FilterAttributesAuthenticates() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "ping")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject rpcResponse = new JSONObject(getResponseBody());
    assertEquals("2.0", rpcResponse.getString("jsonrpc"));
    assertEquals(1, rpcResponse.getInt("id"));
    assertNotNull(rpcResponse.get("result"));
  }

  @Test
  public void doPostWithBearerTokenAuthenticatesViaOAuth2() throws Exception {
    when(request.getAttribute(OAuth2Filter.ATTR_USER_ID)).thenReturn(null);
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Map<String, String> tokenIdentity = new HashMap<>();
    tokenIdentity.put(OAuth2Filter.ATTR_USER_ID, "user2");
    tokenIdentity.put(OAuth2Filter.ATTR_ROLE_ID, "role2");
    tokenIdentity.put(OAuth2Filter.ATTR_CLIENT_ID, "client2");
    tokenIdentity.put(OAuth2Filter.ATTR_ORG_ID, "org2");
    tokenIdentity.put(OAuth2Filter.ATTR_SCOPES, "neo:read");

    try (MockedStatic<OAuth2Filter> oauth2Mock = mockStatic(OAuth2Filter.class)) {
      oauth2Mock.when(() -> OAuth2Filter.validateToken("valid-token"))
          .thenReturn(tokenIdentity);

      String rpcBody = new JSONObject()
          .put("jsonrpc", "2.0")
          .put("id", 2)
          .put("method", "ping")
          .toString();
      setRequestBody(rpcBody);

      servlet.doPost(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      JSONObject rpcResponse = new JSONObject(getResponseBody());
      assertEquals("2.0", rpcResponse.getString("jsonrpc"));
      assertEquals(2, rpcResponse.getInt("id"));
    }
  }

  @Test
  public void doPostWithInvalidTokenReturns401WhenBothOAuth2AndJwtFail() throws Exception {
    when(request.getAttribute(OAuth2Filter.ATTR_USER_ID)).thenReturn(null);
    when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");

    try (MockedStatic<OAuth2Filter> oauth2Mock = mockStatic(OAuth2Filter.class);
         MockedStatic<com.smf.securewebservices.utils.SecureWebServicesUtils> jwtMock =
             mockStatic(com.smf.securewebservices.utils.SecureWebServicesUtils.class)) {

      oauth2Mock.when(() -> OAuth2Filter.validateToken("bad-token"))
          .thenReturn(null);
      jwtMock.when(() -> com.smf.securewebservices.utils.SecureWebServicesUtils.decodeToken("bad-token"))
          .thenThrow(new RuntimeException("Invalid JWT"));

      servlet.doPost(request, response);

      verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      String body = getResponseBody();
      assertTrue(body.contains("error"));
    }
  }

  // ── doPost: commercial environment access (ETP-5047) ────────────────────

  /**
   * A denial built by the real guard, so the body asserted below is the shared wire format and not
   * a test double of it. The guard's lifecycle service answers the decision; its kill switch is
   * off.
   */
  private static EnvironmentAccessGuard.Denial denialFor(EnvironmentAccessPolicy.Decision decision) {
    com.etendoerp.go.payment.TenantEnvironmentLifecycleService lifecycle =
        mock(com.etendoerp.go.payment.TenantEnvironmentLifecycleService.class);
    when(lifecycle.evaluateAccess(eq("client1"), eq(true),
        org.mockito.ArgumentMatchers.any(java.time.Instant.class))).thenReturn(decision);
    try (MockedStatic<com.etendoerp.go.payment.EnvironmentAccessEnforcementFlag> flag =
        mockStatic(com.etendoerp.go.payment.EnvironmentAccessEnforcementFlag.class)) {
      flag.when(() -> com.etendoerp.go.payment.EnvironmentAccessEnforcementFlag
          .isEnforcementSwitchedOff(anyString())).thenReturn(false);
      return new EnvironmentAccessGuard(lifecycle).check("client1", "test");
    }
  }

  @Test
  public void doPostIntoABlockedTenantAnswers402WithTheSharedBodyAndDispatchesNothing()
      throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping")
        .toString());
    EnvironmentAccessGuard.Denial denial =
        denialFor(EnvironmentAccessPolicy.Decision.SUBSCRIPTION_REQUIRED);
    when(environmentAccessGuard.checkAsSystem("client1", "mcp")).thenReturn(denial);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    verify(response, never()).setStatus(HttpServletResponse.SC_OK);
    // Written by the shared Denial.writeTo: JSON in UTF-8, like every other entry point.
    verify(response).setContentType("application/json");
    verify(response).setCharacterEncoding("UTF-8");
    verify(request, never()).getReader();
    JSONObject error = new JSONObject(getResponseBody()).getJSONObject("error");
    assertEquals("Environment access is not available: SUBSCRIPTION_REQUIRED",
        error.getString("message"));
    assertEquals(402, error.getInt("status"));
    assertEquals("ENVIRONMENT_ACCESS_DENIED", error.getString("code"));
    assertEquals("SUBSCRIPTION_REQUIRED", error.getString("decision"));
    assertFalse("a refused request is not a JSON-RPC response",
        new JSONObject(getResponseBody()).has("jsonrpc"));
  }

  @Test
  public void doPostIntoAnExpiredDemoAnswers402WithItsDecision() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping")
        .toString());
    // Built before the stubbing starts: denialFor stubs mocks of its own.
    EnvironmentAccessGuard.Denial denial =
        denialFor(EnvironmentAccessPolicy.Decision.DEMO_TRIAL_EXPIRED);
    when(environmentAccessGuard.checkAsSystem("client1", "mcp")).thenReturn(denial);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    assertEquals("DEMO_TRIAL_EXPIRED", new JSONObject(getResponseBody())
        .getJSONObject("error").getString("decision"));
  }

  @Test
  public void doPostIntoAnAllowedTenantAsksTheGuardForItsClientAndProceeds() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", "ping")
        .toString());
    when(environmentAccessGuard.checkAsSystem("client1", "mcp")).thenReturn(null);

    servlet.doPost(request, response);

    verify(environmentAccessGuard).checkAsSystem("client1", "mcp");
    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response, never()).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    assertEquals(7, new JSONObject(getResponseBody()).getInt("id"));
  }

  /**
   * A notification ({@code notifications/initialized}, no {@code id}) is normally answered 202
   * with no body (ETP-5639). Into a blocked tenant it must still get the structured 402: the
   * access guard runs before the body is read, so a client that only sends notifications learns it
   * is refused instead of seeing every message accepted, and nothing is dispatched.
   */
  @Test
  public void aNotificationIntoABlockedTenantIsRefusedWith402NotAccepted() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0")
        .put("method", "notifications/initialized").toString());
    EnvironmentAccessGuard.Denial denial =
        denialFor(EnvironmentAccessPolicy.Decision.SUBSCRIPTION_REQUIRED);
    when(environmentAccessGuard.checkAsSystem("client1", "mcp")).thenReturn(denial);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    verify(response, never()).setStatus(HttpServletResponse.SC_ACCEPTED);
    // The body is never read, so the notification is never parsed nor dispatched.
    verify(request, never()).getReader();
    JSONObject error = new JSONObject(getResponseBody()).getJSONObject("error");
    assertEquals("Environment access is not available: SUBSCRIPTION_REQUIRED",
        error.getString("message"));
    assertEquals(402, error.getInt("status"));
    assertEquals("ENVIRONMENT_ACCESS_DENIED", error.getString("code"));
    assertEquals("SUBSCRIPTION_REQUIRED", error.getString("decision"));
  }

  @Test
  public void doPostThatFailsAuthenticationNeverAsksTheGuard() throws Exception {
    when(request.getAttribute(OAuth2Filter.ATTR_USER_ID)).thenReturn(null);
    when(request.getHeader("Authorization")).thenReturn(null);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verifyNoInteractions(environmentAccessGuard);
  }

  /**
   * ETP-5047 review W1 — an MCP token commonly carries the wildcard client {@code "0"}; the tenant
   * it acts on is its role's client, which {@link McpSessionManager} resolves before building the
   * context. The guard must judge THAT tenant: asked about {@code "0"} it finds no lifecycle
   * metadata and allows, so a blocked tenant walked past it with a wildcard token.
   */
  @Test
  public void doPostWithAWildcardTokenAsksTheGuardForTheRolesTenant() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "0", "0", "neo:read");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping")
        .toString());
    EnvironmentAccessGuard.Denial denial =
        denialFor(EnvironmentAccessPolicy.Decision.SUBSCRIPTION_REQUIRED);
    when(environmentAccessGuard.checkAsSystem("tenantOfRole1", "mcp")).thenReturn(denial);
    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    // The role lookup (SELECT ad_client_id FROM ad_role) answers the role's tenant.
    when(session.doReturningWork(org.mockito.ArgumentMatchers.any())).thenReturn("tenantOfRole1");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      servlet.doPost(request, response);
    }

    verify(environmentAccessGuard).checkAsSystem("tenantOfRole1", "mcp");
    verify(environmentAccessGuard, never()).checkAsSystem(eq("0"), anyString());
    verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
  }

  /**
   * A guard that cannot decide (its lifecycle lookup hit a DB error) must fail closed with the
   * same JSON 503 a failed role lookup answers — never an escaped exception, which Tomcat turns
   * into an HTML 500, and never a dispatch.
   */
  @Test
  public void doPostWhoseAccessCheckFailsAnswers503AndDispatchesNothing() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setRequestBody(new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 9)
        .put("method", "tools/call")
        .put("params", new JSONObject().put("name", "etendo_list")
            .put("arguments", new JSONObject().put("spec", "product")))
        .toString());
    when(environmentAccessGuard.checkAsSystem("client1", "mcp"))
        .thenThrow(new IllegalStateException("lifecycle lookup failed"));

    try (MockedConstruction<McpToolRouter> routerMock =
        mockConstruction(McpToolRouter.class)) {
      servlet.doPost(request, response);

      assertTrue(routerMock.constructed().isEmpty());
    }
    verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    verify(response, never()).setStatus(HttpServletResponse.SC_OK);
    verify(request, never()).getReader();
    String body = getResponseBody();
    assertTrue(body, new JSONObject(body).getString("error").contains("could not be verified"));
    assertFalse(body.contains("\"jsonrpc\""));
  }

  /** Runs a wildcard-token {@code ping} whose role lookup answers {@code roleLookup}. */
  private void doPostWildcardPing(Object roleLookup) throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "0", "0", "neo:read");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "ping")
        .toString());
    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    if (roleLookup instanceof RuntimeException) {
      when(session.doReturningWork(org.mockito.ArgumentMatchers.any()))
          .thenThrow((RuntimeException) roleLookup);
    } else {
      when(session.doReturningWork(org.mockito.ArgumentMatchers.any())).thenReturn(roleLookup);
    }
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      servlet.doPost(request, response);
    }
  }

  /**
   * ETP-5047 review W2 — fail closed: a role lookup that FAILED must not fall back to
   * {@code "0"}, which the guard allows; a later successful lookup in
   * {@code McpSessionManager.executeInContext} would then run under the tenant unjudged.
   */
  @Test
  public void doPostWithAWildcardTokenWhoseRoleLookupFailsIsRefusedUnjudged() throws Exception {
    doPostWildcardPing(new IllegalStateException("db down"));

    verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    verifyNoInteractions(environmentAccessGuard);
    assertFalse(getResponseBody().contains("\"jsonrpc\""));
  }

  /** A System role legitimately has no tenant: it keeps {@code "0"} and proceeds, as before. */
  @Test
  public void doPostWithAWildcardTokenOfASystemRoleKeepsTheSystemClient() throws Exception {
    doPostWildcardPing(null);

    verify(environmentAccessGuard).checkAsSystem("0", "mcp");
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  // ── doPost: the 402 under every credential scheme (ETP-5047) ────────────

  /**
   * Every way {@link McpServlet#doPost} accepts a caller. The guard is asked with the tenant the
   * credential names, so each scheme carries its own tenant id: a scheme whose tenant never reached
   * the guard would be decided for the wrong tenant (or for none) and slip through.
   */
  private enum CredentialScheme {
    /** Attributes the OAuth2 filter already set on the request. */
    OAUTH2_FILTER("filter-tenant"),
    /** An opaque OAuth2 access token in {@code Authorization: Bearer}. */
    OAUTH2_TOKEN("oauth2-tenant"),
    /** A legacy Etendo JWT in {@code Authorization: Bearer}. */
    LEGACY_JWT("jwt-tenant"),
    /** The SPA's {@code __Host-go_session} cookie, with its Origin and CSRF proof. */
    COOKIE_SESSION("cookie-tenant");

    final String clientId;

    CredentialScheme(String clientId) {
      this.clientId = clientId;
    }
  }

  private static final String SAME_ORIGIN = "http://localhost:8080";
  private static final String SESSION_CSRF = "csrf-token-value-123456";

  /**
   * Makes the current request carry {@code scheme}'s credential, naming its tenant.
   *
   * @return the static mocks the scheme needs, to be closed by the caller
   */
  private List<MockedStatic<?>> presentCredential(CredentialScheme scheme) throws Exception {
    List<MockedStatic<?>> statics = new ArrayList<>();
    switch (scheme) {
      case OAUTH2_FILTER:
        setOAuth2FilterAttributes("user1", "role1", scheme.clientId, "org1", "neo:read");
        break;
      case OAUTH2_TOKEN: {
        when(request.getHeader("Authorization")).thenReturn("Bearer opaque-token");
        Map<String, String> tokenIdentity = new HashMap<>();
        tokenIdentity.put(OAuth2Filter.ATTR_USER_ID, "user1");
        tokenIdentity.put(OAuth2Filter.ATTR_ROLE_ID, "role1");
        tokenIdentity.put(OAuth2Filter.ATTR_CLIENT_ID, scheme.clientId);
        tokenIdentity.put(OAuth2Filter.ATTR_ORG_ID, "org1");
        tokenIdentity.put(OAuth2Filter.ATTR_SCOPES, "neo:read");
        MockedStatic<OAuth2Filter> oauth2 = mockStatic(OAuth2Filter.class);
        statics.add(oauth2);
        oauth2.when(() -> OAuth2Filter.validateToken("opaque-token")).thenReturn(tokenIdentity);
        break;
      }
      case LEGACY_JWT: {
        when(request.getHeader("Authorization")).thenReturn("Bearer legacy.jwt.token");
        MockedStatic<OAuth2Filter> oauth2 = mockStatic(OAuth2Filter.class);
        statics.add(oauth2);
        oauth2.when(() -> OAuth2Filter.validateToken("legacy.jwt.token")).thenReturn(null);
        MockedStatic<SecureWebServicesUtils> jwt = mockStatic(SecureWebServicesUtils.class);
        statics.add(jwt);
        DecodedJWT decoded = mock(DecodedJWT.class);
        Claim user = claim("user1");
        Claim role = claim("role1");
        Claim client = claim(scheme.clientId);
        Claim org = claim("org1");
        when(decoded.getClaim("user")).thenReturn(user);
        when(decoded.getClaim("role")).thenReturn(role);
        when(decoded.getClaim("client")).thenReturn(client);
        when(decoded.getClaim("organization")).thenReturn(org);
        jwt.when(() -> SecureWebServicesUtils.decodeToken("legacy.jwt.token")).thenReturn(decoded);
        break;
      }
      case COOKIE_SESSION: {
        when(request.getMethod()).thenReturn("POST");
        when(request.getCookies()).thenReturn(
            new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-token") });
        when(request.getHeader("Origin")).thenReturn(SAME_ORIGIN);
        when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(SESSION_CSRF);
        GoSessionRecord session = sessionRecord("user1", "role1", scheme.clientId, "org1");
        session.setCsrfToken(SESSION_CSRF);
        // Far from both expiries, so resolving it writes nothing back.
        session.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        session.setAbsoluteExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        // The servlet's session store reads through the DAL session's JDBC work.
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        statics.add(obDal);
        OBDal dal = mock(OBDal.class);
        Session hibernateSession = mock(Session.class);
        when(dal.getSession()).thenReturn(hibernateSession);
        when(hibernateSession.doReturningWork(org.mockito.ArgumentMatchers.any()))
            .thenReturn(session);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        break;
      }
      default:
        throw new IllegalArgumentException(scheme.name());
    }
    return statics;
  }

  private static Claim claim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }

  /**
   * Replaces the mock guard with the real one over a lifecycle service that answers
   * {@code decision} for every tenant, so the refusal, its body and the kill switch are the
   * production code's, not a stub's.
   */
  private TenantEnvironmentLifecycleService useTheRealGuardDeciding(
      EnvironmentAccessPolicy.Decision decision) throws Exception {
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(lifecycle.evaluateAccess(anyString(), eq(true),
        org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(decision);
    Field guardField = McpServlet.class.getDeclaredField("environmentAccessGuard");
    guardField.setAccessible(true);
    guardField.set(servlet, new EnvironmentAccessGuard(lifecycle));
    return lifecycle;
  }

  /**
   * Sends a {@code ping} under {@code scheme} into a tenant the lifecycle service refuses. The
   * guard runs as system; {@link OBContext} is static-mocked so that switch touches no database.
   */
  private TenantEnvironmentLifecycleService pingABlockedTenantUnder(CredentialScheme scheme)
      throws Exception {
    newExchange();
    TenantEnvironmentLifecycleService lifecycle =
        useTheRealGuardDeciding(EnvironmentAccessPolicy.Decision.SUBSCRIPTION_REQUIRED);
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 5).put("method", "ping")
        .toString());
    List<MockedStatic<?>> statics = presentCredential(scheme);
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      servlet.doPost(request, response);
    } finally {
      for (MockedStatic<?> mocked : statics) {
        mocked.close();
      }
    }
    return lifecycle;
  }

  private void assertRefusedWith402AndNothingDispatched(CredentialScheme scheme,
      TenantEnvironmentLifecycleService lifecycle) throws Exception {
    String body = getResponseBody();
    verify(lifecycle).evaluateAccess(eq(scheme.clientId), eq(true),
        org.mockito.ArgumentMatchers.any(Instant.class));
    verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    verify(response, never()).setStatus(HttpServletResponse.SC_OK);
    verify(response).setContentType("application/json");
    verify(response).setCharacterEncoding("UTF-8");
    verify(request, never()).getReader();
    JSONObject json = new JSONObject(body);
    assertFalse(scheme + ": a refusal is not a JSON-RPC response", json.has("jsonrpc"));
    JSONObject error = json.getJSONObject("error");
    assertEquals(scheme + ": " + body,
        "Environment access is not available: SUBSCRIPTION_REQUIRED", error.getString("message"));
    assertEquals(402, error.getInt("status"));
    assertEquals("ENVIRONMENT_ACCESS_DENIED", error.getString("code"));
    assertEquals("SUBSCRIPTION_REQUIRED", error.getString("decision"));
  }

  @Test
  public void aBlockedTenantIsRefusedWith402UnderTheOAuth2FilterAttributes() throws Exception {
    CredentialScheme scheme = CredentialScheme.OAUTH2_FILTER;
    assertRefusedWith402AndNothingDispatched(scheme, pingABlockedTenantUnder(scheme));
  }

  @Test
  public void aBlockedTenantIsRefusedWith402UnderAnOAuth2AccessToken() throws Exception {
    CredentialScheme scheme = CredentialScheme.OAUTH2_TOKEN;
    assertRefusedWith402AndNothingDispatched(scheme, pingABlockedTenantUnder(scheme));
  }

  @Test
  public void aBlockedTenantIsRefusedWith402UnderALegacyJwt() throws Exception {
    CredentialScheme scheme = CredentialScheme.LEGACY_JWT;
    assertRefusedWith402AndNothingDispatched(scheme, pingABlockedTenantUnder(scheme));
  }

  @Test
  public void aBlockedTenantIsRefusedWith402UnderTheCookieSession() throws Exception {
    CredentialScheme scheme = CredentialScheme.COOKIE_SESSION;
    assertRefusedWith402AndNothingDispatched(scheme, pingABlockedTenantUnder(scheme));
  }

  /** The kill switch reopens MCP for a blocked tenant whichever credential the caller holds. */
  @Test
  public void aBlockedTenantIsDispatchedUnderEverySchemeWhileEnforcementIsSwitchedOff()
      throws Exception {
    System.setProperty(ENFORCEMENT_OFF_PROPERTY, "true");
    for (CredentialScheme scheme : CredentialScheme.values()) {
      TenantEnvironmentLifecycleService lifecycle = pingABlockedTenantUnder(scheme);

      verify(lifecycle).evaluateAccess(eq(scheme.clientId), eq(true),
          org.mockito.ArgumentMatchers.any(Instant.class));
      verify(response).setStatus(HttpServletResponse.SC_OK);
      verify(response, never()).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
      JSONObject rpcResponse = new JSONObject(getResponseBody());
      assertEquals(scheme + ": the ping is answered", 5, rpcResponse.getInt("id"));
      assertTrue(scheme.name(), rpcResponse.has("result"));
    }
  }

  // ── doPost: JSON-RPC dispatch ───────────────────────────────────────────

  @Test
  public void doPostInitializeReturnsProtocolVersionAndCapabilities() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 10)
        .put("method", "initialize")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject rpcResponse = new JSONObject(getResponseBody());
    assertEquals("2.0", rpcResponse.getString("jsonrpc"));
    assertEquals(10, rpcResponse.getInt("id"));

    JSONObject result = rpcResponse.getJSONObject("result");
    assertEquals("no protocolVersion asked: the latest is answered",
        McpProtocolVersion.LATEST, result.getString("protocolVersion"));
    assertTrue(result.has("capabilities"));
    assertTrue(result.has("serverInfo"));

    JSONObject serverInfo = result.getJSONObject("serverInfo");
    assertEquals("etendo-mcp", serverInfo.getString("name"));
    assertEquals("1.0.0", serverInfo.getString("version"));
    assertEquals("Etendo MCP", serverInfo.getString("title"));
    assertEquals("https://app.etendo.ai", serverInfo.getString("websiteUrl"));
    assertEquals(McpModernResults.SERVER_DESCRIPTION, serverInfo.getString("description"));

    JSONArray icons = serverInfo.getJSONArray("icons");
    assertEquals(1, icons.length());
    JSONObject icon = icons.getJSONObject(0);
    assertEquals("https://app.etendo.ai/favicon.png", icon.getString("src"));
    assertEquals("image/png", icon.getString("mimeType"));
    assertEquals("513x513", icon.getJSONArray("sizes").getString(0));

    JSONObject capabilities = result.getJSONObject("capabilities");
    assertTrue(capabilities.has("tools"));
    assertTrue(capabilities.has("resources"));
  }

  // ── Protocol version negotiation (ETP-5639) ─────────────────────────────

  private JSONObject initializeAsking(String protocolVersion) throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setRequestBody(new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 11)
        .put("method", "initialize")
        .put("params", new JSONObject().put("protocolVersion", protocolVersion)
            .put("clientInfo", new JSONObject().put("name", "claude-code")))
        .toString());
    servlet.doPost(request, response);
    return new JSONObject(getResponseBody()).getJSONObject("result");
  }

  @Test
  public void initializeEchoesEverySupportedVersion() throws Exception {
    for (String version : McpProtocolVersion.SUPPORTED) {
      setUp();
      assertEquals(version, initializeAsking(version).getString("protocolVersion"));
    }
  }

  @Test
  public void initializeWithAnUnsupportedVersionAnswersTheLatest() throws Exception {
    assertEquals(McpProtocolVersion.LATEST,
        initializeAsking("2026-07-28").getString("protocolVersion"));
  }

  @Test
  public void supportedVersionsAreTheFourLegacyRevisions() {
    assertEquals(java.util.List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"),
        McpProtocolVersion.SUPPORTED);
    assertEquals("2025-11-25", McpProtocolVersion.LATEST);
  }

  @Test
  public void missingProtocolHeaderIsTakenAs20250326WithoutWarning() {
    try (LogCapture logs = LogCapture.of(McpProtocolVersion.class)) {
      assertEquals("2025-03-26", McpProtocolVersion.forRequest(null, "2025-11-25", "x"));
      assertTrue(logs.messages(Level.WARN).isEmpty());
    }
  }

  @Test
  public void supportedProtocolHeaderIsServedAsSent() {
    assertEquals("2025-06-18", McpProtocolVersion.forRequest("2025-06-18", null, "x"));
  }

  /**
   * Lenient policy: an unsupported header value is served — with the session's negotiated version,
   * else the latest — and leaves one WARN naming the value and the client. Never a 400.
   */
  @Test
  public void unsupportedProtocolHeaderIsServedWithOneWarn() throws Exception {
    try (LogCapture logs = LogCapture.of(McpProtocolVersion.class)) {
      assertEquals("2025-06-18",
          McpProtocolVersion.forRequest("2026-07-28", "2025-06-18", "cursor"));
      assertEquals(McpProtocolVersion.LATEST,
          McpProtocolVersion.forRequest("bogus", null, "cursor"));
      assertEquals(2, logs.messages(Level.WARN).size());
      String warn = logs.messages(Level.WARN).get(0);
      assertTrue(warn, warn.contains("'2026-07-28'") && warn.contains("client=cursor"));
    }

    // L3 (ETP-5640): an unknown header with no modern _meta is still a legacy request — served.
    // A modern header value is no longer "unsupported": see modernHeaderWithoutMetaIsModern.
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    when(request.getHeader(McpProtocolVersion.HEADER)).thenReturn("2027-01-01");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 12).put("method", "ping")
        .toString());
    servlet.doPost(request, response);
    verify(response).setStatus(HttpServletResponse.SC_OK);
    assertTrue(new JSONObject(getResponseBody()).has("result"));
  }

  /** M10 (ETP-5640): a 2026-07-28 header without _meta is a modern request — ping is gone there. */
  @Test
  public void modernHeaderWithoutMetaIsModern() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    when(request.getHeader(McpProtocolVersion.HEADER))
        .thenReturn(McpProtocolVersion.MODERN_LATEST);
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 13).put("method", "ping")
        .toString());
    servlet.doPost(request, response);
    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
  }

  @Test
  public void corsAllowsTheProtocolVersionHeader() throws Exception {
    try (MockedStatic<com.etendoerp.go.common.CorsUtils> cors =
             mockStatic(com.etendoerp.go.common.CorsUtils.class)) {
      servlet.doOptions(request, response);
      cors.verify(() -> com.etendoerp.go.common.CorsUtils.apply(eq(request), eq(response),
          anyString(),
          org.mockito.ArgumentMatchers.contains(McpProtocolVersion.HEADER),
          anyString(), eq(false)));
    }
  }

  /** ETP-5640: a browser-based 2026-07-28 client sends the mirror headers on every POST. */
  @Test
  public void corsAllowsTheModernMirrorHeaders() throws Exception {
    try (MockedStatic<com.etendoerp.go.common.CorsUtils> cors =
             mockStatic(com.etendoerp.go.common.CorsUtils.class)) {
      servlet.doOptions(request, response);
      cors.verify(() -> com.etendoerp.go.common.CorsUtils.apply(eq(request), eq(response),
          anyString(),
          org.mockito.ArgumentMatchers.argThat((String headers) -> headers != null
              && headers.contains(McpRequestEra.HEADER_METHOD + ",")
              && headers.contains(McpRequestEra.HEADER_NAME + ",")
              && headers.contains("Mcp-Session-Id")),
          anyString(), eq(false)));
    }
  }

  // ── Dual era (ETP-5640) ─────────────────────────────────────────────────

  private static final String MODERN = McpProtocolVersion.MODERN_LATEST;
  private static final String KILL_SWITCH_PROPERTY =
      "etendo.go.flags."
          + com.etendoerp.go.featureflags.GoFeatureFlags.FLAG_MCP_MODERN_ERA_DISABLED;

  private static JSONObject modernMeta(String version) throws Exception {
    return new JSONObject()
        .put(McpRequestEra.META_PROTOCOL_VERSION, version)
        .put(McpRequestEra.META_CLIENT_CAPABILITIES, new JSONObject())
        .put(McpServlet.META_CLIENT_INFO,
            new JSONObject().put("name", "claude-code").put("version", "2.1.0"));
  }

  private void setModernHeaders(String version, String method, String name) {
    when(request.getHeader(McpProtocolVersion.HEADER)).thenReturn(version);
    when(request.getHeader(McpRequestEra.HEADER_METHOD)).thenReturn(method);
    when(request.getHeader(McpRequestEra.HEADER_NAME)).thenReturn(name);
  }

  /** POST one modern request (well-formed headers for {@code version}) and parse the answer. */
  private JSONObject postModern(String method, JSONObject params, String version)
      throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setModernHeaders(version, method, null);
    JSONObject withMeta = (params != null ? params : new JSONObject())
        .put("_meta", modernMeta(version));
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 40).put("method", method)
        .put("params", withMeta).toString());
    servlet.doPost(request, response);
    return new JSONObject(getResponseBody());
  }

  @Test
  public void modernPingIsMethodNotFoundWith404() throws Exception {
    JSONObject answer = postModern("ping", null, MODERN);

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    assertEquals(-32601, answer.getJSONObject("error").getInt("code"));
    assertEquals(40, answer.getInt("id"));
  }

  @Test
  public void modernUnknownMethodIsMethodNotFoundWith404() throws Exception {
    JSONObject answer = postModern("subscriptions/listen", null, MODERN);

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    assertEquals(-32601, answer.getJSONObject("error").getInt("code"));
  }

  @Test
  public void modernUnsupportedVersionIs400WithTheSupportedList() throws Exception {
    JSONObject answer = postModern("tools/list", null, "2027-01-01");

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    JSONObject error = answer.getJSONObject("error");
    assertEquals(McpRequestEra.UNSUPPORTED_PROTOCOL_VERSION, error.getInt("code"));
    assertEquals("2027-01-01", error.getJSONObject("data").getString("requested"));
    assertEquals(MODERN, error.getJSONObject("data").getJSONArray("supported").getString(0));
  }

  @Test
  public void modernHeaderThatContradictsTheBodyIs400HeaderMismatch() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setModernHeaders("2025-11-25", "tools/list", null);
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 41)
        .put("method", "tools/list")
        .put("params", new JSONObject().put("_meta", modernMeta(MODERN))).toString());

    try (LogCapture logs = LogCapture.of(McpServlet.class)) {
      servlet.doPost(request, response);
      assertEquals(1, logs.messages(Level.WARN).size());
      assertTrue(logs.messages(Level.ERROR).isEmpty());
    }

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertEquals(McpRequestEra.HEADER_MISMATCH,
        new JSONObject(getResponseBody()).getJSONObject("error").getInt("code"));
  }

  @Test
  public void modernToolsListIsServedWithoutInitialize() throws Exception {
    try (MockedStatic<McpSessionManager> sessionMock = mockStatic(McpSessionManager.class)) {
      sessionMock.when(() -> McpSessionManager.executeInContext(anyString(), anyString(),
          anyString(), anyString(), org.mockito.ArgumentMatchers.isNull(),
          org.mockito.ArgumentMatchers.<Callable<JSONObject>>any()))
          .thenReturn(new JSONObject().put("tools", new JSONArray()));

      JSONObject answer = postModern("tools/list", null, MODERN);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      JSONObject result = answer.getJSONObject("result");
      assertTrue(result.has("tools"));
      assertEquals("complete", result.getString("resultType"));
      assertEquals(McpModernResults.CATALOG_TTL_MS, result.getLong("ttlMs"));
      assertEquals("private", result.getString("cacheScope"));
      assertEquals("etendo-mcp", result.getJSONObject("_meta")
          .getJSONObject(McpModernResults.META_SERVER_INFO).getString("name"));
      verify(response, never()).setHeader(eq(McpUsageTelemetry.HEADER_SESSION_ID), anyString());
    }
  }

  @Test
  public void legacyToolsListIsNotDecorated() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 42)
        .put("method", "tools/list").toString());
    try (MockedStatic<McpSessionManager> sessionMock = mockStatic(McpSessionManager.class)) {
      sessionMock.when(() -> McpSessionManager.executeInContext(anyString(), anyString(),
          anyString(), anyString(), org.mockito.ArgumentMatchers.isNull(),
          org.mockito.ArgumentMatchers.<Callable<JSONObject>>any()))
          .thenReturn(new JSONObject().put("tools", new JSONArray()));

      servlet.doPost(request, response);
    }

    JSONObject result = new JSONObject(getResponseBody()).getJSONObject("result");
    assertFalse(result.has("resultType"));
    assertFalse(result.has("ttlMs"));
    assertFalse(result.has("_meta"));
  }

  /**
   * Read a resource the provider refuses as {@code OBSecurityException} (unknown or inaccessible
   * spec), through the real servlet path, with the request already set up by the caller.
   */
  private JSONObject readRefusedResource() throws Exception {
    try (MockedStatic<McpSessionManager> sessionMock = mockStatic(McpSessionManager.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedConstruction<McpResourceProvider> providerMock = mockConstruction(
             McpResourceProvider.class, (provider, ctx) -> when(provider.readResource(anyString()))
                 .thenThrow(new org.openbravo.base.exception.OBSecurityException(
                     "Access denied to spec 'secret'")))) {
      sessionMock.when(() -> McpSessionManager.executeInContext(anyString(), anyString(),
          anyString(), anyString(), org.mockito.ArgumentMatchers.isNull(),
          org.mockito.ArgumentMatchers.<Callable<JSONObject>>any()))
          .thenAnswer(inv -> ((Callable<?>) inv.getArgument(5)).call());

      try (LogCapture logs = LogCapture.of(McpServlet.class)) {
        servlet.doPost(request, response);
        assertTrue("a missing resource is no server failure: " + logs.messages(Level.ERROR),
            logs.messages(Level.ERROR).isEmpty());
        assertEquals(1, logs.messages(Level.WARN).size());
        assertNull(logs.events(Level.WARN).get(0).getThrown());
      }
    }
    return new JSONObject(getResponseBody()).getJSONObject("error");
  }

  @Test
  public void modernMissingResourceIsInvalidParams() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "etendo:read");
    setModernHeaders(MODERN, "resources/read", "etendo://specs/secret");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 43)
        .put("method", "resources/read")
        .put("params", new JSONObject().put("uri", "etendo://specs/secret")
            .put("_meta", modernMeta(MODERN))).toString());

    JSONObject error = readRefusedResource();

    assertEquals(McpRequestEra.INVALID_PARAMS, error.getInt("code"));
    assertEquals("Resource not found: etendo://specs/secret", error.getString("message"));
  }

  /** Read a resource whose entity the provider cannot find (an McpRoutingException not_found). */
  private JSONObject readMissingEntity() throws Exception {
    try (MockedStatic<McpSessionManager> sessionMock = mockStatic(McpSessionManager.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedConstruction<McpResourceProvider> providerMock = mockConstruction(
             McpResourceProvider.class, (provider, ctx) -> when(provider.readResource(anyString()))
                 .thenThrow(McpRoutingException.entityNotFound("nope", "sales-order",
                     java.util.List.of("header"))))) {
      sessionMock.when(() -> McpSessionManager.executeInContext(anyString(), anyString(),
          anyString(), anyString(), org.mockito.ArgumentMatchers.isNull(),
          org.mockito.ArgumentMatchers.<Callable<JSONObject>>any()))
          .thenAnswer(inv -> ((Callable<?>) inv.getArgument(5)).call());

      try (LogCapture logs = LogCapture.of(McpServlet.class)) {
        servlet.doPost(request, response);
        assertTrue("an unknown entity is no server failure: " + logs.messages(Level.ERROR),
            logs.messages(Level.ERROR).isEmpty());
        assertEquals(1, logs.messages(Level.WARN).size());
        assertNull(logs.events(Level.WARN).get(0).getThrown());
      }
    }
    return new JSONObject(getResponseBody()).getJSONObject("error");
  }

  @Test
  public void modernUnknownEntityResourceIsInvalidParams() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "etendo:read");
    setModernHeaders(MODERN, "resources/read", "etendo://specs/sales-order/nope");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 45)
        .put("method", "resources/read")
        .put("params", new JSONObject().put("uri", "etendo://specs/sales-order/nope")
            .put("_meta", modernMeta(MODERN))).toString());

    JSONObject error = readMissingEntity();

    assertEquals(McpRequestEra.INVALID_PARAMS, error.getInt("code"));
    assertEquals("Resource not found: etendo://specs/sales-order/nope", error.getString("message"));
  }

  @Test
  public void legacyUnknownEntityResourceKeepsItsCodeWithoutAStackTrace() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "etendo:read");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 46)
        .put("method", "resources/read")
        .put("params", new JSONObject().put("uri", "etendo://specs/sales-order/nope")).toString());

    JSONObject error = readMissingEntity();

    assertEquals(McpServlet.JSON_RPC_INTERNAL_ERROR, error.getInt("code"));
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  /** S1: a refused modern request opens no derived session and logs no session start. */
  @Test
  public void refusedModernRequestOpensNoSession() throws Exception {
    try (LogCapture logs = LogCapture.of(McpUsageTelemetry.class)) {
      postModern("tools/list", null, "2027-01-01");

      verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
      assertTrue(logs.messages(Level.INFO).toString(), logs.messages(Level.INFO).stream()
          .noneMatch(line -> line.contains("modern session started")));
    }
  }

  @Test
  public void legacyMissingResourceKeepsItsCodeWithoutAStackTrace() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "etendo:read");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 44)
        .put("method", "resources/read")
        .put("params", new JSONObject().put("uri", "etendo://specs/secret")).toString());

    JSONObject error = readRefusedResource();

    assertEquals(McpServlet.JSON_RPC_INTERNAL_ERROR, error.getInt("code"));
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  public void decorateModernMarksEveryResultButCachesOnlyTheCatalog() throws Exception {
    JSONObject call = McpModernResults.decorate("tools/call",
        new JSONObject().put("content", new JSONArray()));
    assertEquals("complete", call.getString("resultType"));
    assertFalse("a tool result is not cacheable", call.has("ttlMs"));

    JSONObject read = McpModernResults.decorate("resources/read", new JSONObject());
    assertEquals("private", read.getString("cacheScope"));
  }

  @Test
  public void decorateModernKeepsWhatTheHandlerAlreadySet() throws Exception {
    JSONObject fullInfo = new JSONObject().put("name", "etendo-mcp").put("title", "Etendo MCP");
    JSONObject own = new JSONObject().put("ttlMs", 1L).put("cacheScope", "public")
        .put("_meta", new JSONObject().put(McpModernResults.META_SERVER_INFO, fullInfo));

    JSONObject decorated = McpModernResults.decorate("tools/list", own);

    assertEquals(1L, decorated.getLong("ttlMs"));
    assertEquals("public", decorated.getString("cacheScope"));
    assertEquals("Etendo MCP", decorated.getJSONObject("_meta")
        .getJSONObject(McpModernResults.META_SERVER_INFO).getString("title"));
  }

  /** K1: with the kill switch on, a modern request is served exactly as before the modern era. */
  @Test
  public void killSwitchServesModernRequestsUnderTheLegacyEra() throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "true");

    JSONObject answer = postModern("ping", null, MODERN);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    assertEquals(0, answer.getJSONObject("result").length());
  }

  @Test
  public void killSwitchKeepsAnUnsupportedModernVersionServed() throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "true");

    JSONObject answer = postModern("ping", null, "2027-01-01");

    verify(response).setStatus(HttpServletResponse.SC_OK);
    assertTrue(answer.has("result"));
  }

  // ── server/discover and the compatibility matrix (ETP-5640) ─────────────

  @Test
  public void discoverAnswersEveryVersionTheCapabilitiesAndTheFullServerInfo() throws Exception {
    JSONObject answer = postModern(McpRequestEra.SERVER_DISCOVER, null, MODERN);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject result = answer.getJSONObject("result");
    assertEquals("complete", result.getString("resultType"));
    JSONArray versions = result.getJSONArray("supportedVersions");
    assertEquals(McpProtocolVersion.ALL_SUPPORTED.size(), versions.length());
    assertEquals(MODERN, versions.getString(0));
    assertTrue(result.getJSONObject("capabilities").has("tools"));
    assertTrue(result.getJSONObject("capabilities").has("resources"));
    JSONObject serverInfo = result.getJSONObject("_meta")
        .getJSONObject(McpModernResults.META_SERVER_INFO);
    assertEquals("etendo-mcp", serverInfo.getString("name"));
    assertEquals("Etendo MCP", serverInfo.getString("title"));
    assertTrue(serverInfo.has("icons"));
    assertEquals(McpModernResults.DISCOVER_TTL_MS, result.getLong("ttlMs"));
    assertEquals("public", result.getString("cacheScope"));
    assertFalse("no instructions, as initialize", result.has("instructions"));
    verify(response, never()).setHeader(eq(McpUsageTelemetry.HEADER_SESSION_ID), anyString());
  }

  /** Discover and initialize describe the server with the same builders. */
  @Test
  public void discoverAndInitializeAgreeOnCapabilitiesAndServerInfo() throws Exception {
    JSONObject discover = postModern(McpRequestEra.SERVER_DISCOVER, null, MODERN)
        .getJSONObject("result");
    setUp();
    JSONObject init = initializeAsking("2025-11-25");

    assertEquals(init.getJSONObject("capabilities").toString(),
        discover.getJSONObject("capabilities").toString());
    assertEquals(init.getJSONObject("serverInfo").toString(), discover.getJSONObject("_meta")
        .getJSONObject(McpModernResults.META_SERVER_INFO).toString());
  }

  /**
   * M9: a bare probe with no _meta is still a modern method — served (lenient) with one WARN. The
   * WARN carries the derived session, so Datadog can correlate it with the calls that follow.
   */
  @Test
  public void discoverWithoutMetaIsServedWithAWarn() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 50)
        .put("method", McpRequestEra.SERVER_DISCOVER).toString());

    try (LogCapture logs = LogCapture.of(McpServlet.class)) {
      servlet.doPost(request, response);
      List<String> warns = logs.messages(Level.WARN);
      assertFalse(warns.isEmpty());
      assertTrue(warns.toString(), warns.stream()
          .filter(line -> line.contains("served without"))
          .allMatch(line -> line.contains("session=m-")));
    }

    verify(response).setStatus(HttpServletResponse.SC_OK);
    assertTrue(new JSONObject(getResponseBody()).getJSONObject("result")
        .has("supportedVersions"));
  }

  /** K1: with the kill switch on, the probe gets exactly today's answer, so clients fall back. */
  @Test
  public void killSwitchAnswersDiscoverAsBefore() throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "true");

    try (LogCapture logs = LogCapture.of(McpServlet.class)) {
      JSONObject answer = postModern(McpRequestEra.SERVER_DISCOVER, null, MODERN);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      assertEquals(-32601, answer.getJSONObject("error").getInt("code"));
      assertTrue(logs.messages(Level.ERROR).isEmpty());
      assertTrue(logs.messages(Level.WARN).get(0).contains("'server/discover'"));
    }
  }

  /**
   * K1: with the kill switch on, the probe derives no modern session — an m- key that nothing
   * uses afterwards would pollute the rollout evidence exactly when the era is rolled back — but
   * the WARN still names the client from its _meta.
   */
  @Test
  public void killSwitchProbeOpensNoModernSessionButNamesTheClient() throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "true");

    try (LogCapture servletLogs = LogCapture.of(McpServlet.class);
        LogCapture telemetryLogs = LogCapture.of(McpUsageTelemetry.class)) {
      postModern(McpRequestEra.SERVER_DISCOVER, null, MODERN);

      assertTrue(telemetryLogs.messages(Level.INFO).toString(), telemetryLogs
          .messages(Level.INFO).stream().noneMatch(line -> line.contains("modern session")));
      String warn = servletLogs.messages(Level.WARN).get(0);
      assertTrue(warn, warn.contains("client=claude-code"));
      assertTrue(warn, warn.contains("session=none"));
    }
  }

  /** Matrix — legacy client: initialize mints a session and the answer carries no modern field. */
  @Test
  public void legacyClientStillNegotiatesAndGetsASession() throws Exception {
    JSONObject init = initializeAsking("2025-06-18");

    assertEquals("2025-06-18", init.getString("protocolVersion"));
    assertFalse(init.has("resultType"));
    verify(response).setHeader(eq(McpUsageTelemetry.HEADER_SESSION_ID), anyString());
  }

  /** Matrix — modern-only client: an unknown version first, then a retry with a served one. */
  @Test
  public void modernOnlyClientRetriesWithAnAdvertisedVersion() throws Exception {
    JSONObject refused = postModern(McpRequestEra.SERVER_DISCOVER, null, "2027-01-01");
    String retryWith = refused.getJSONObject("error").getJSONObject("data")
        .getJSONArray("supported").getString(0);
    setUp();

    JSONObject served = postModern(McpRequestEra.SERVER_DISCOVER, null, retryWith);

    assertTrue(served.getJSONObject("result").has("supportedVersions"));
  }

  // ── commercial access gate (ETP-5642, through the ETP-5047 guard) ────────
  //
  // ETP-5642's specs, kept for what they add to the guard specs above: the refusal comes before
  // any dispatch whatever the method (tools/call, and the protocol methods initialize and
  // tools/list), and the real guard serves an allowed tenant and a tenant without lifecycle
  // metadata (a null decision: the legacy transition). The wildcard-client spec of ETP-5642 is
  // covered by doPostWithAWildcardTokenAsksTheGuardForTheRolesTenant. The body is the shared
  // structured one; ETP-5642 first answered a plain {"error":"<message>"} string.

  /** Posts {@code body} as an OAuth2 caller of client1 through the real guard deciding so. */
  private void postThroughTheRealGuard(EnvironmentAccessPolicy.Decision decision, String body)
      throws Exception {
    useTheRealGuardDeciding(decision);
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setRequestBody(body);
    // The guard runs as system; OBContext is static-mocked so that switch touches no database.
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      servlet.doPost(request, response);
    }
  }

  private static String rpc(int id, String method) throws Exception {
    return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).toString();
  }

  @Test
  public void doPostRefusesExpiredDemoWith402BeforeAnyDispatch() throws Exception {
    String toolsCall = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 30)
        .put("method", "tools/call")
        .put("params", new JSONObject().put("name", "etendo_list")
            .put("arguments", new JSONObject().put("spec", "product")))
        .toString();

    try (MockedConstruction<McpToolRouter> routerMock =
        mockConstruction(McpToolRouter.class)) {
      postThroughTheRealGuard(EnvironmentAccessPolicy.Decision.DEMO_TRIAL_EXPIRED, toolsCall);

      verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
      assertTrue(routerMock.constructed().isEmpty());
    }
    JSONObject body = new JSONObject(getResponseBody());
    JSONObject error = body.getJSONObject("error");
    assertEquals("Environment access is not available: DEMO_TRIAL_EXPIRED",
        error.getString("message"));
    assertEquals("DEMO_TRIAL_EXPIRED", error.getString("decision"));
    assertFalse(body.has("result"));
  }

  @Test
  public void doPostRefusesSuspendedSubscriptionEvenForProtocolMethods() throws Exception {
    for (String method : new String[] { "initialize", "tools/list" }) {
      newExchange();
      postThroughTheRealGuard(EnvironmentAccessPolicy.Decision.SUBSCRIPTION_REQUIRED,
          rpc(31, method));

      verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
      verify(response, never()).setStatus(HttpServletResponse.SC_OK);
      assertEquals(method, "Environment access is not available: SUBSCRIPTION_REQUIRED",
          new JSONObject(getResponseBody()).getJSONObject("error").getString("message"));
    }
  }

  @Test
  public void doPostServesAnAllowedEnvironment() throws Exception {
    postThroughTheRealGuard(EnvironmentAccessPolicy.Decision.ALLOWED, rpc(32, "ping"));

    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response, never()).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    assertEquals(32, new JSONObject(getResponseBody()).getInt("id"));
  }

  @Test
  public void doPostServesATenantWithoutLifecycleMetadata() throws Exception {
    postThroughTheRealGuard(null, rpc(33, "ping"));

    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response, never()).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
  }

  @Test
  public void doPostPingReturnsEmptyResult() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 20)
        .put("method", "ping")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject rpcResponse = new JSONObject(getResponseBody());
    assertEquals(20, rpcResponse.getInt("id"));
    assertNotNull(rpcResponse.get("result"));
  }

  @Test
  public void toolsListExecutesWithAuthenticatedTokenContext() throws Exception {
    McpServlet.AuthIdentity identity =
        new McpServlet.AuthIdentity("user1", "role1", "client1", "org1", "neo:read");
    Method handler = McpServlet.class.getDeclaredMethod("handleToolsList",
        McpServlet.AuthIdentity.class);
    handler.setAccessible(true);

    try (MockedStatic<McpSessionManager> sessionMock = mockStatic(McpSessionManager.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedConstruction<ToolRegistry> registryMock = mockConstruction(ToolRegistry.class)) {
      sessionMock.when(() -> McpSessionManager.executeInContext(
          eq("user1"), eq("role1"), eq("client1"), eq("org1"),
          org.mockito.ArgumentMatchers.isNull(),
          org.mockito.ArgumentMatchers.<Callable<JSONObject>>any()))
          .thenReturn(new JSONObject().put("tools", new org.codehaus.jettison.json.JSONArray()));

      handler.invoke(servlet, identity);

      sessionMock.verify(() -> McpSessionManager.executeInContext(
          eq("user1"), eq("role1"), eq("client1"), eq("org1"),
          org.mockito.ArgumentMatchers.isNull(),
          org.mockito.ArgumentMatchers.<Callable<JSONObject>>any()));
    }
  }

  @Test
  public void doPostInitializedNotificationReturns202() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", "initialized")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
  }

  /**
   * Into an allowed tenant the notification keeps ETP-5639's 202 with no body; the guard is still
   * asked first (see {@link #aNotificationIntoABlockedTenantIsRefusedWith402NotAccepted}).
   */
  @Test
  public void doPostNotificationsInitializedReturns202() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", "notifications/initialized")
        .toString();
    setRequestBody(rpcBody);
    when(environmentAccessGuard.checkAsSystem("client1", "mcp")).thenReturn(null);

    servlet.doPost(request, response);

    verify(environmentAccessGuard).checkAsSystem("client1", "mcp");
    verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
    verify(response, never()).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
    assertEquals("", getResponseBody());
  }

  @Test
  public void doPostUnknownMethodReturnsMethodNotFoundError() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 30)
        .put("method", "unknown/method")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject rpcResponse = new JSONObject(getResponseBody());
    assertEquals("2.0", rpcResponse.getString("jsonrpc"));
    assertEquals(30, rpcResponse.getInt("id"));
    assertTrue(rpcResponse.has("error"));

    JSONObject error = rpcResponse.getJSONObject("error");
    assertEquals(-32601, error.getInt("code"));
    assertTrue(error.getString("message").contains("Method not found"));
  }

  /**
   * A client asking for a method we do not offer is not a server failure: no ERROR, no stack
   * trace, one WARN naming the method and the client, whose name comes from {@code params._meta}
   * when the client never ran {@code initialize}. (This used to be the {@code server/discover}
   * probe; since ETP-5640 that is answered — see {@code killSwitchAnswersDiscoverAsBefore}.)
   */
  @Test
  public void unknownMethodLogsOneWarnWithClientFromMetaAndNoError() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 31)
        .put("method", "resources/templates/list")
        .put("params", new JSONObject().put("_meta", new JSONObject()
            .put(McpServlet.META_CLIENT_INFO, new JSONObject().put("name", "claude-code"))))
        .toString();
    setRequestBody(rpcBody);

    try (LogCapture logs = LogCapture.of(McpServlet.class)) {
      servlet.doPost(request, response);

      assertEquals(-32601,
          new JSONObject(getResponseBody()).getJSONObject("error").getInt("code"));
      assertTrue("no ERROR for a client probe: " + logs.messages(Level.ERROR),
          logs.messages(Level.ERROR).isEmpty());
      assertEquals(1, logs.messages(Level.WARN).size());
      String warn = logs.messages(Level.WARN).get(0);
      assertTrue(warn, warn.contains("'resources/templates/list'"));
      assertTrue(warn, warn.contains("client=claude-code"));
      assertTrue(warn, warn.contains("session=" + McpUsageTelemetry.NO_SESSION));
      assertNull("one line, no stack trace", logs.events(Level.WARN).get(0).getThrown());
    }
  }

  @Test
  public void clientNameForPrefersTheTelemetrySessionThenMetaThenUnknown() throws Exception {
    JSONObject withMeta = new JSONObject().put("_meta", new JSONObject()
        .put(McpServlet.META_CLIENT_INFO, new JSONObject().put("name", "cursor")));
    assertEquals("cursor", McpServlet.clientNameFor(withMeta));
    assertEquals("unknown", McpServlet.clientNameFor(null));
    assertEquals("unknown", McpServlet.clientNameFor(new JSONObject()));

    String sessionKey = McpUsageTelemetry.openSession(
        new JSONObject().put("clientInfo", new JSONObject().put("name", "claude-ai")));
    McpUsageTelemetry.setCurrentSessionKey(sessionKey);
    try {
      assertEquals("the handshake name wins over _meta", "claude-ai",
          McpServlet.clientNameFor(withMeta));
    } finally {
      McpUsageTelemetry.clearCurrentSessionKey();
    }
  }

  /**
   * A body that is not JSON is the client's error: JSON-RPC parse error {@code -32700} with a null
   * id (none can be read from it) and HTTP 400, never a 500. That includes a body that breaks
   * jettison itself (StackOverflowError / OutOfMemoryError, which escape every catch (Exception)).
   */
  @Test
  public void doPostMalformedJsonReturnsParseError() throws Exception {
    List<String> bodies = new ArrayList<>(BoundedJsonParserTest.stackBreakingBodies().toList());
    bodies.add("this is not valid json {{{");
    for (String raw : bodies) {
      setUp();
      setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
      setRequestBody(raw);

      servlet.doPost(request, response);

      verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
      JSONObject rpc = new JSONObject(getResponseBody());
      assertEquals(McpServlet.JSON_RPC_PARSE_ERROR, rpc.getJSONObject("error").getInt("code"));
      assertTrue(rpc.isNull("id"));
    }
  }

  @Test
  public void doPostInitializeWithStringIdReturnsStringId() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", "req-abc")
        .put("method", "initialize")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject rpcResponse = new JSONObject(getResponseBody());
    assertEquals("req-abc", rpcResponse.getString("id"));
    assertTrue(rpcResponse.has("result"));
  }

  @Test
  public void doPostNullIdNotificationReturns202WithEmptyBody() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", "initialized")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
    assertEquals("", getResponseBody());
  }

  @Test
  public void doPostErrorResponseIncludesJsonRpcIdFromRequest() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 99)
        .put("method", "nonexistent/method")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    JSONObject rpcResponse = new JSONObject(getResponseBody());
    assertEquals("2.0", rpcResponse.getString("jsonrpc"));
    assertTrue(rpcResponse.has("error"));
    JSONObject error = rpcResponse.getJSONObject("error");
    assertEquals(-32601, error.getInt("code"));
  }

  @Test
  public void doGetWellKnownWithFallbackUrlsReturnsMetadata() throws Exception {
    System.clearProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY);
    System.clearProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY);
    when(request.getPathInfo()).thenReturn("/.well-known/oauth-protected-resource");

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject meta = new JSONObject(getResponseBody());
    assertTrue(meta.has("resource"));
  }

  // ── AuthIdentity ────────────────────────────────────────────────────────

  @Test
  public void authIdentityStoresAllFields() {
    McpServlet.AuthIdentity identity = new McpServlet.AuthIdentity(
        "userId1", "roleId1", "clientId1", "orgId1", "neo:read neo:write");
    assertEquals("userId1", identity.userId);
    assertEquals("roleId1", identity.roleId);
    assertEquals("clientId1", identity.clientId);
    assertEquals("orgId1", identity.orgId);
    assertEquals("neo:read neo:write", identity.scopes);
  }

  @Test
  public void authIdentityAcceptsNullFields() {
    McpServlet.AuthIdentity identity = new McpServlet.AuthIdentity(
        null, null, null, null, null);
    assertEquals(null, identity.userId);
    assertEquals(null, identity.roleId);
    assertEquals(null, identity.clientId);
    assertEquals(null, identity.orgId);
    assertEquals(null, identity.scopes);
  }

  // ── McpMethodNotFoundException ──────────────────────────────────────────

  @Test
  public void mcpMethodNotFoundExceptionStoresMessage() {
    McpServlet.McpMethodNotFoundException ex =
        new McpServlet.McpMethodNotFoundException("Method not found: foo/bar");
    assertEquals("Method not found: foo/bar", ex.getMessage());
  }

  @Test
  public void mcpMethodNotFoundExceptionIsCheckedException() {
    McpServlet.McpMethodNotFoundException ex =
        new McpServlet.McpMethodNotFoundException("test");
    assertTrue(ex instanceof Exception);
  }

  @Test
  public void doPostPingWithNullIdReturns202() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", "ping")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
  }

  @Test
  public void doPostSetsContentTypeJson() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "ping")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setContentType("application/json;charset=UTF-8");
  }

  @Test
  public void doGetSetsContentTypeJson() throws Exception {
    when(request.getPathInfo()).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setContentType("application/json;charset=UTF-8");
  }

  @Test
  public void doPostUnauthorizedSetsWwwAuthenticateHeader() throws Exception {
    when(request.getAttribute(OAuth2Filter.ATTR_USER_ID)).thenReturn(null);
    when(request.getHeader("Authorization")).thenReturn(null);

    servlet.doPost(request, response);

    verify(response).setHeader(eq("WWW-Authenticate"), anyString());
  }

  @Test
  public void doPostEmptyMethodReturnsMethodNotFoundError() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 40)
        .put("method", "")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject rpcResponse = new JSONObject(getResponseBody());
    assertTrue(rpcResponse.has("error"));
    assertEquals(-32601, rpcResponse.getJSONObject("error").getInt("code"));
  }

  @Test
  public void doPostWithNoMethodFieldReturnsMethodNotFoundError() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 50)
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject rpcResponse = new JSONObject(getResponseBody());
    assertTrue(rpcResponse.has("error"));
    assertEquals(-32601, rpcResponse.getJSONObject("error").getInt("code"));
  }

  @Test
  public void doPostInitializeCapabilitiesListChangedIsFalse() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 60)
        .put("method", "initialize")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    JSONObject rpcResponse = new JSONObject(getResponseBody());
    JSONObject capabilities = rpcResponse.getJSONObject("result").getJSONObject("capabilities");

    JSONObject toolsCap = capabilities.getJSONObject("tools");
    assertEquals(false, toolsCap.getBoolean("listChanged"));

    JSONObject resourcesCap = capabilities.getJSONObject("resources");
    assertEquals(false, resourcesCap.getBoolean("listChanged"));
  }

  @Test
  public void doPostPingResultIsEmptyJsonObject() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 70)
        .put("method", "ping")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    JSONObject rpcResponse = new JSONObject(getResponseBody());
    JSONObject result = rpcResponse.getJSONObject("result");
    assertEquals(0, result.length());
  }

  // ── Telemetry tenant (ETP-5594) ─────────────────────────────────────────

  /**
   * Run one {@code tools/call} through the real {@link McpSessionManager}, with the role's client
   * and default org resolved to {@code resolvedClient} / {@code resolvedOrg}, and return the row
   * handed to {@link McpUsageLogger}.
   */
  private McpUsageRow recordedRowForToolsCall(String tokenClient, String tokenOrg,
      String resolvedOrg, String resolvedClient, boolean routerThrows) throws Exception {
    return recordedRowForToolsCall(tokenClient, tokenOrg, resolvedOrg, resolvedClient,
        routerThrows, "etendo_list", new JSONObject().put("spec", "sales-order"));
  }

  private McpUsageRow recordedRowForToolsCall(String tokenClient, String tokenOrg,
      String resolvedOrg, String resolvedClient, boolean routerThrows, String toolName,
      JSONObject arguments) throws Exception {
    return recordedRowForToolsCall(tokenClient, tokenOrg, resolvedOrg, resolvedClient,
        routerThrows, toolName, arguments, null);
  }

  /** Same, with a {@code params._meta} — a modern-shaped call (ETP-5640) when it is not null. */
  private McpUsageRow recordedRowForToolsCall(String tokenClient, String tokenOrg,
      String resolvedOrg, String resolvedClient, boolean routerThrows, String toolName,
      JSONObject arguments, JSONObject meta) throws Exception {
    setOAuth2FilterAttributes("user1", "role1", tokenClient, tokenOrg, "neo:read");
    JSONObject params = new JSONObject().put("name", toolName).put("arguments", arguments);
    if (meta != null) {
      params.put("_meta", meta);
    }
    setRequestBody(new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "tools/call")
        .put("params", params)
        .toString());

    org.openbravo.dal.service.OBDal obDal = mock(org.openbravo.dal.service.OBDal.class);
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(obDal.getSession()).thenReturn(session);
    // ETP-5047: the servlet resolves a wildcard client once, before the access guard. The session
    // manager then looks up the org, and the client a second time only when it is still the
    // wildcard.
    if ("0".equals(tokenClient)) {
      when(session.doReturningWork(org.mockito.ArgumentMatchers.any()))
          .thenReturn(resolvedClient, resolvedOrg, resolvedClient);
    } else {
      when(session.doReturningWork(org.mockito.ArgumentMatchers.any())).thenReturn(resolvedOrg);
    }

    try (MockedStatic<org.openbravo.dal.service.OBDal> obDalMock =
             mockStatic(org.openbravo.dal.service.OBDal.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<com.smf.securewebservices.utils.SecureWebServicesUtils> swsMock =
             mockStatic(com.smf.securewebservices.utils.SecureWebServicesUtils.class);
         MockedStatic<McpUsageLogger> loggerMock = mockStatic(McpUsageLogger.class);
         MockedConstruction<McpToolRouter> routerMock = mockConstruction(McpToolRouter.class,
             (router, ctx) -> {
               if (routerThrows) {
                 when(router.route(anyString(), org.mockito.ArgumentMatchers.any(),
                     org.mockito.ArgumentMatchers.any()))
                     .thenThrow(new IllegalStateException("boom"));
               } else {
                 when(router.route(anyString(), org.mockito.ArgumentMatchers.any(),
                     org.mockito.ArgumentMatchers.any())).thenReturn(new JSONObject());
               }
             })) {
      obDalMock.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(obDal);
      swsMock.when(() -> com.smf.securewebservices.utils.SecureWebServicesUtils.createContext(
          anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
          .thenReturn(mock(OBContext.class));
      loggerMock.when(McpUsageLogger::isEnabled).thenReturn(true);

      servlet.doPost(request, response);

      org.mockito.ArgumentCaptor<McpUsageRow> row =
          org.mockito.ArgumentCaptor.forClass(McpUsageRow.class);
      loggerMock.verify(() -> McpUsageLogger.enqueue(row.capture()));
      return row.getValue();
    }
  }

  // ── Modern telemetry (ETP-5640) ─────────────────────────────────────────

  private McpUsageRow recordedModernRow(String clientName) throws Exception {
    setModernHeaders(MODERN, "tools/call", "etendo_list");
    JSONObject meta = modernMeta(MODERN);
    meta.getJSONObject(McpServlet.META_CLIENT_INFO).put("name", clientName);
    return recordedRowForToolsCall("client1", "org1", "org1", "client1", false, "etendo_list",
        new JSONObject().put("spec", "sales-order"), meta);
  }

  /** The client name comes from each request's _meta, the session key is derived and marked. */
  @Test
  public void modernToolsCallRowCarriesTheMetaClientAndADerivedSession() throws Exception {
    // A stale legacy session header on a modern request is ignored, never used.
    when(request.getHeader(McpUsageTelemetry.HEADER_SESSION_ID)).thenReturn("stale-legacy-key");

    McpUsageRow row = recordedModernRow("claude-code-qa-1");

    assertEquals("claude-code-qa-1", row.clientName());
    assertEquals("2.1.0", row.clientVersion());
    assertTrue(row.sessionKey(), row.sessionKey().startsWith(
        McpUsageTelemetry.MODERN_SESSION_PREFIX));
    assertNull("bound only for the request", McpUsageTelemetry.currentClient());
  }

  @Test
  public void consecutiveModernCallsOfOneClientShareTheirSession() throws Exception {
    String first = recordedModernRow("claude-code-qa-2").sessionKey();
    setUp();
    String second = recordedModernRow("claude-code-qa-2").sessionKey();

    assertEquals(first, second);
  }

  /**
   * With the kill switch on, the protocol is legacy: the row keeps the _meta client name, but no
   * m- session is derived — the request carries no Mcp-Session-Id, so it has none.
   */
  @Test
  public void killSwitchKeepsTheModernClientNameOnTheRow() throws Exception {
    System.setProperty(KILL_SWITCH_PROPERTY, "true");

    McpUsageRow row = recordedModernRow("claude-code-qa-3");

    assertEquals("claude-code-qa-3", row.clientName());
    assertNull(row.sessionKey());
  }

  @Test
  public void legacyToolsCallKeepsTheEchoedSessionKey() throws Exception {
    when(request.getHeader(McpUsageTelemetry.HEADER_SESSION_ID)).thenReturn("legacy-key-1");

    McpUsageRow row = recordedRowForToolsCall("client1", "org1", "org1", "client1", false);

    assertEquals("legacy-key-1", row.sessionKey());
  }

  @Test
  public void toolsCallTelemetryRecordsTheTenantTheCallRanUnderNotTheTokenWildcard()
      throws Exception {
    McpUsageRow row = recordedRowForToolsCall("0", "0", "realOrg", "realClient", false);

    assertEquals("realClient", row.clientId());
    assertEquals("realOrg", row.orgId());
    assertEquals("user1", row.userId());
  }

  @Test
  public void failedToolsCallTelemetryStillRecordsTheEffectiveTenant() throws Exception {
    McpUsageRow row = recordedRowForToolsCall("0", "0", "realOrg", "realClient", true);

    assertEquals(McpUsageRow.OUTCOME_ERROR, row.outcome());
    assertEquals("realClient", row.clientId());
    assertEquals("realOrg", row.orgId());
  }

  @Test
  public void toolsCallTelemetryKeepsAConcreteTokenTenant() throws Exception {
    McpUsageRow row = recordedRowForToolsCall("client1", "org1", null, null, false);

    assertEquals("client1", row.clientId());
    assertEquals("org1", row.orgId());
  }

  /**
   * ETP-5639: an accepted feedback report leaves exactly one INFO line, and its usageId is the id
   * of the row handed to the writer — so the report can be read from the DB by that id.
   */
  @Test
  public void acceptedFeedbackLogsOneInfoLineWithTheRowId() throws Exception {
    JSONObject verdict = new JSONObject().put("outcome", "OKAY").put("summary", "s")
        .put("achieved", "a");
    try (LogCapture logs = LogCapture.of(McpFeedbackTool.class)) {
      McpUsageRow row = recordedRowForToolsCall("client1", "org1", null, null, false,
          McpConstants.TOOL_NEO_FEEDBACK, verdict);

      assertNotNull(row.payload());
      assertEquals(1, logs.messages(Level.INFO).size());
      assertTrue(logs.messages(Level.INFO).get(0),
          logs.messages(Level.INFO).get(0).contains("usageId=" + row.id() + " "));
    }
  }

  @Test
  public void rejectedFeedbackLogsNoReceivedLine() throws Exception {
    try (LogCapture logs = LogCapture.of(McpFeedbackTool.class)) {
      McpUsageRow row = recordedRowForToolsCall("client1", "org1", null, null, true,
          McpConstants.TOOL_NEO_FEEDBACK, new JSONObject().put("outcome", "OKAY"));

      assertNull(row.payload());
      assertTrue(logs.messages(Level.INFO).isEmpty());
    }
  }

  @Test
  public void doPostUnbindsTheEffectiveTenantSoAPooledThreadCannotLeakIt() throws Exception {
    recordedRowForToolsCall("0", "0", "realOrg", "realClient", false);

    assertNull(McpUsageTelemetry.currentTenant());
  }
}
