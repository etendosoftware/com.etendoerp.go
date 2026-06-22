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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.oauth2.OAuth2Filter;

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
    assertEquals("etendo-neo", info.getString("name"));
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
    assertEquals("etendo-neo", info.getString("name"));
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
    assertEquals("2024-11-05", result.getString("protocolVersion"));
    assertTrue(result.has("capabilities"));
    assertTrue(result.has("serverInfo"));

    JSONObject serverInfo = result.getJSONObject("serverInfo");
    assertEquals("etendo-neo", serverInfo.getString("name"));
    assertEquals("1.0.0", serverInfo.getString("version"));

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
}
