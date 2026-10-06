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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.oauth2.OAuth2Filter;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.usageevents.LogCapture;

/**
 * Unit tests for {@link McpServlet} covering CORS, authentication, JSON-RPC
 * dispatch, error handling, GET endpoints, and inner classes.
 *
 * @covers com.etendoerp.go.mcp.McpServlet
 */
public class McpServletTest {

  private McpServlet servlet;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private StringWriter responseBody;
  private PrintWriter writer;

  @Before
  public void setUp() throws Exception {
    servlet = new McpServlet();
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

    System.setProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY, "https://example.com/mcp");
  }

  @After
  public void tearDown() {
    System.clearProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY);
    System.clearProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY);
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
    assertEquals(McpServlet.SERVER_DESCRIPTION, serverInfo.getString("description"));

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

    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    when(request.getHeader(McpProtocolVersion.HEADER)).thenReturn("2026-07-28");
    setRequestBody(new JSONObject().put("jsonrpc", "2.0").put("id", 12).put("method", "ping")
        .toString());
    servlet.doPost(request, response);
    verify(response).setStatus(HttpServletResponse.SC_OK);
    assertTrue(new JSONObject(getResponseBody()).has("result"));
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

  @Test
  public void doPostNotificationsInitializedReturns202() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", "notifications/initialized")
        .toString();
    setRequestBody(rpcBody);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
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
   * A client probing with a method we do not offer (2026-07-28 {@code server/discover}) is not a
   * server failure: no ERROR, no stack trace, one WARN naming the method and the client, whose name
   * comes from {@code params._meta} when the client never ran {@code initialize}.
   */
  @Test
  public void unknownMethodLogsOneWarnWithClientFromMetaAndNoError() throws Exception {
    setOAuth2FilterAttributes("user1", "role1", "client1", "org1", "neo:read");
    String rpcBody = new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 31)
        .put("method", "server/discover")
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
      assertTrue(warn, warn.contains("'server/discover'"));
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
        routerThrows, "neo_list", new JSONObject().put("spec", "sales-order"));
  }

  private McpUsageRow recordedRowForToolsCall(String tokenClient, String tokenOrg,
      String resolvedOrg, String resolvedClient, boolean routerThrows, String toolName,
      JSONObject arguments) throws Exception {
    setOAuth2FilterAttributes("user1", "role1", tokenClient, tokenOrg, "neo:read");
    setRequestBody(new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "tools/call")
        .put("params", new JSONObject().put("name", toolName)
            .put("arguments", arguments))
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
