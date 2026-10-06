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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

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

/**
 * Unit tests for {@link McpServlet} covering CORS, authentication, JSON-RPC
 * dispatch, error handling, GET endpoints, and inner classes.
 */
public class McpServletTest {

  private McpServlet servlet;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private StringWriter responseBody;
  private PrintWriter writer;
  private EnvironmentAccessGuard environmentAccessGuard;

  private static final String KILL_SWITCH_PROPERTY =
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
    System.clearProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY);
    System.clearProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY);
    System.clearProperty(KILL_SWITCH_PROPERTY);
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
    assertEquals("neo:read neo:write neo:process neo:report", identity.scopes);
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

  // ── doOptions ───────────────────────────────────────────────────────────

  @Test
  public void doOptionsReturns204NoContent() throws Exception {
    servlet.doOptions(request, response);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  // ── doGet: server info ──────────────────────────────────────────────────

  @Test
  public void doGetReturnsServerInfo() throws Exception {
    when(request.getPathInfo()).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject info = new JSONObject(getResponseBody());
    assertEquals("etendo-mcp", info.getString("name"));
    assertEquals("1.0.0", info.getString("version"));
    assertEquals("2024-11-05", info.getString("protocolVersion"));
    assertEquals("streamable-http", info.getString("transport"));
  }

  @Test
  public void doGetWithNonWellKnownPathReturnsServerInfo() throws Exception {
    when(request.getPathInfo()).thenReturn("/some/other/path");

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject info = new JSONObject(getResponseBody());
    assertEquals("etendo-mcp", info.getString("name"));
  }

  @Test
  public void doGetWellKnownReturnsMetadata() throws Exception {
    System.setProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY, "https://example.com/mcp");
    System.setProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY, "https://example.com/oauth2");
    when(request.getPathInfo()).thenReturn("/.well-known/oauth-protected-resource");

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    JSONObject meta = new JSONObject(getResponseBody());
    assertEquals("https://example.com/mcp", meta.getString("resource"));
    assertEquals("https://example.com/oauth2",
        meta.getJSONArray("authorization_servers").getString(0));
    assertTrue(meta.has("scopes_supported"));
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

  @Test
  public void doPostThatFailsAuthenticationNeverAsksTheGuard() throws Exception {
    when(request.getAttribute(OAuth2Filter.ATTR_USER_ID)).thenReturn(null);
    when(request.getHeader("Authorization")).thenReturn(null);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verifyNoInteractions(environmentAccessGuard);
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
    System.setProperty(KILL_SWITCH_PROPERTY, "true");
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
    assertEquals("2024-11-05", result.getString("protocolVersion"));
    assertTrue(result.has("capabilities"));
    assertTrue(result.has("serverInfo"));

    JSONObject serverInfo = result.getJSONObject("serverInfo");
    assertEquals("etendo-mcp", serverInfo.getString("name"));
    assertEquals("1.0.0", serverInfo.getString("version"));
    assertEquals("Etendo MCP", serverInfo.getString("title"));
    assertEquals("https://app.etendo.ai", serverInfo.getString("websiteUrl"));

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
  public void doPostInitializedNotificationReturns204() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", "initialized")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @Test
  public void doPostNotificationsInitializedReturns204() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", "notifications/initialized")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
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

  @Test
  public void doPostMalformedJsonReturnsInternalError() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    setRequestBody("this is not valid json {{{");

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    String body = getResponseBody();
    assertTrue(body.contains("error"));
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
  public void doPostNullIdNotificationReturns204WithEmptyBody() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", "initialized")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
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
  public void doPostPingWithNullIdReturns204() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", "ping")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
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
    setOAuth2FilterAttributes("user1", "role1", tokenClient, tokenOrg, "neo:read");
    setRequestBody(new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "tools/call")
        .put("params", new JSONObject().put("name", "neo_list")
            .put("arguments", new JSONObject().put("spec", "sales-order")))
        .toString());

    org.openbravo.dal.service.OBDal obDal = mock(org.openbravo.dal.service.OBDal.class);
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(obDal.getSession()).thenReturn(session);
    // McpSessionManager resolves the org first, then the client.
    when(session.doReturningWork(org.mockito.ArgumentMatchers.any()))
        .thenReturn(resolvedOrg, resolvedClient);

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

  @Test
  public void doPostUnbindsTheEffectiveTenantSoAPooledThreadCannotLeakIt() throws Exception {
    recordedRowForToolsCall("0", "0", "realOrg", "realClient", false);

    assertNull(McpUsageTelemetry.currentTenant());
  }
}
