package com.etendoerp.go.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.oauth2.OAuth2Filter;

/**
 * MCP (Model Context Protocol) servlet implementing Streamable HTTP transport.
 * <p>
 * Uses stateless request/response over POST instead of SSE+AsyncContext,
 * because Etendo's servlet filter chain (DalRequestFilter, KernelFilter, etc.)
 * does not support async operations.
 * <p>
 * <b>Streamable HTTP Protocol:</b>
 * <ul>
 *   <li>Client sends JSON-RPC 2.0 messages as POST to {@code /sws/mcp}</li>
 *   <li>Server processes each message and returns the JSON-RPC response directly</li>
 *   <li>No persistent SSE connection — each call is independent</li>
 * </ul>
 * <p>
 * Authentication is handled inline via {@link OAuth2Filter#validateToken(String)}.
 * Each tool call is executed within a scoped OBContext via {@link McpSessionManager}.
 */
public class McpServlet extends HttpServlet {

  private static final long serialVersionUID = 1L;
  private static final Logger log = LogManager.getLogger(McpServlet.class);

  private static final String PROTOCOL_VERSION = "2024-11-05";
  private static final String SERVER_NAME = "etendo-neo";
  private static final String SERVER_VERSION = "1.0.0";

  private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

  // ── CORS ───────────────────────────────────────────────────────────────

  private void setCorsHeaders(HttpServletResponse response) {
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    response.setHeader("Access-Control-Allow-Headers",
        "Content-Type, Authorization, Accept, Mcp-Session-Id");
    response.setHeader("Access-Control-Expose-Headers", "Mcp-Session-Id, WWW-Authenticate");
    response.setHeader("Access-Control-Max-Age", "86400");
  }

  @Override
  protected void doOptions(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    setCorsHeaders(response);
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  // ── POST: Receive JSON-RPC message ──────────────────────────────────────

  /**
   * Handle POST /sws/mcp — receive a JSON-RPC 2.0 message and respond synchronously.
   * <p>
   * Validates the OAuth2 Bearer token, parses the JSON-RPC request, dispatches
   * the method, and returns the JSON-RPC response in the same HTTP response.
   */
  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {

    setCorsHeaders(response);

    // Authenticate via OAuth2 Bearer token
    AuthIdentity identity = authenticate(request, response);
    if (identity == null) {
      return; // Response already sent by authenticate()
    }

    response.setContentType(CONTENT_TYPE_JSON);

    String body = readRequestBody(request);

    try {
      JSONObject rpcMessage = new JSONObject(body);
      String method = rpcMessage.optString("method", "");
      Object id = rpcMessage.opt("id");

      log.debug("MCP request: method={}, id={}", method, id);

      // Dispatch the method
      JSONObject result = dispatchMethod(identity, method, rpcMessage.optJSONObject("params"));

      // Notifications (no id) don't get a response body
      if (id == null) {
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        return;
      }

      // Build JSON-RPC response
      JSONObject rpcResponse = new JSONObject();
      rpcResponse.put("jsonrpc", "2.0");
      rpcResponse.put("id", id);
      rpcResponse.put("result", result != null ? result : new JSONObject());

      response.setStatus(HttpServletResponse.SC_OK);
      response.getWriter().write(rpcResponse.toString());

    } catch (Exception e) {
      log.error("Error processing MCP message: {}", e.getMessage(), e);

      try {
        Object rpcId = new JSONObject(body).opt("id");
        int errorCode = (e instanceof McpMethodNotFoundException) ? -32601 : -32603;
        JSONObject errorResponse = buildJsonRpcError(rpcId, errorCode, e.getMessage());

        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(errorResponse.toString());
      } catch (Exception ex) {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.getWriter().write("{\"error\":\"Internal server error\"}");
      }
    }
  }

  // ── GET: Server info / health check ────────────────────────────────────

  /**
   * Handle GET /sws/mcp — return server info for discovery.
   * MCP Streamable HTTP clients may GET the endpoint to check capabilities.
   */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    setCorsHeaders(response);
    response.setContentType(CONTENT_TYPE_JSON);
    response.setStatus(HttpServletResponse.SC_OK);

    try {
      JSONObject info = new JSONObject();
      info.put("name", SERVER_NAME);
      info.put("version", SERVER_VERSION);
      info.put("protocolVersion", PROTOCOL_VERSION);
      info.put("transport", "streamable-http");
      response.getWriter().write(info.toString());
    } catch (JSONException e) {
      response.getWriter().write("{\"name\":\"" + SERVER_NAME + "\"}");
    }
  }

  // ── Authentication ─────────────────────────────────────────────────────

  /**
   * Validate the OAuth2 Bearer token and return the identity.
   * Sends an error response and returns null if authentication fails.
   */
  private AuthIdentity authenticate(HttpServletRequest request, HttpServletResponse response)
      throws IOException {

    // Check if OAuth2Filter already set attributes
    String userId = (String) request.getAttribute(OAuth2Filter.ATTR_USER_ID);
    if (userId != null) {
      return new AuthIdentity(
          userId,
          (String) request.getAttribute(OAuth2Filter.ATTR_ROLE_ID),
          (String) request.getAttribute(OAuth2Filter.ATTR_CLIENT_ID),
          (String) request.getAttribute(OAuth2Filter.ATTR_ORG_ID),
          (String) request.getAttribute(OAuth2Filter.ATTR_SCOPES));
    }

    // Inline validation
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
          "Missing Authorization: Bearer <token> header");
      return null;
    }

    String bearerToken = authHeader.substring(7).trim();

    // Try OAuth2 token first
    Map<String, String> tokenIdentity = OAuth2Filter.validateToken(bearerToken);
    if (tokenIdentity != null) {
      return new AuthIdentity(
          tokenIdentity.get(OAuth2Filter.ATTR_USER_ID),
          tokenIdentity.get(OAuth2Filter.ATTR_ROLE_ID),
          tokenIdentity.get(OAuth2Filter.ATTR_CLIENT_ID),
          tokenIdentity.get(OAuth2Filter.ATTR_ORG_ID),
          tokenIdentity.get(OAuth2Filter.ATTR_SCOPES));
    }

    // Fallback: try JWT token
    try {
      com.auth0.jwt.interfaces.DecodedJWT jwt =
          com.smf.securewebservices.utils.SecureWebServicesUtils.decodeToken(bearerToken);
      return new AuthIdentity(
          jwt.getClaim("user").asString(),
          jwt.getClaim("role").asString(),
          jwt.getClaim("client").asString(),
          jwt.getClaim("organization").asString(),
          "neo:*");
    } catch (Exception e) {
      log.warn("Both OAuth2 and JWT authentication failed for MCP request");
      sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
          "Invalid or expired token (OAuth2 and JWT both failed)");
      return null;
    }
  }

  // ── JSON-RPC method dispatch ────────────────────────────────────────────

  /**
   * Route a JSON-RPC method to its handler.
   */
  private JSONObject dispatchMethod(AuthIdentity identity, String method, JSONObject params)
      throws Exception {
    switch (method) {
      case "initialize":
        return handleInitialize();
      case "initialized":
      case "notifications/initialized":
        return null;
      case "ping":
        return new JSONObject();
      case "tools/list":
        return handleToolsList(identity);
      case "tools/call":
        return handleToolsCall(identity, params);
      case "resources/list":
        return handleResourcesList(identity);
      case "resources/read":
        return handleResourcesRead(identity, params);
      default:
        throw new McpMethodNotFoundException("Method not found: " + method);
    }
  }

  // ── Handler: initialize ─────────────────────────────────────────────────

  private JSONObject handleInitialize() throws JSONException {
    JSONObject result = new JSONObject();
    result.put("protocolVersion", PROTOCOL_VERSION);

    JSONObject capabilities = new JSONObject();

    JSONObject toolsCap = new JSONObject();
    toolsCap.put("listChanged", false);
    capabilities.put("tools", toolsCap);

    JSONObject resourcesCap = new JSONObject();
    resourcesCap.put("listChanged", false);
    capabilities.put("resources", resourcesCap);

    result.put("capabilities", capabilities);

    JSONObject serverInfo = new JSONObject();
    serverInfo.put("name", SERVER_NAME);
    serverInfo.put("version", SERVER_VERSION);
    result.put("serverInfo", serverInfo);

    return result;
  }

  // ── Handler: tools/list ─────────────────────────────────────────────────

  private JSONObject handleToolsList(AuthIdentity identity) throws Exception {
    OBContext.setAdminMode(true);
    try {
      ToolRegistry registry = new ToolRegistry();
      Set<String> scopes = parseScopes(identity.scopes);
      List<McpToolDefinition> tools = registry.generateTools(scopes);

      JSONObject result = new JSONObject();
      JSONArray toolsArray = new JSONArray();
      for (McpToolDefinition tool : tools) {
        JSONObject toolJson = new JSONObject();
        toolJson.put("name", tool.getName());
        toolJson.put("description", tool.getDescription());
        toolJson.put("inputSchema", mapToJsonObject(tool.getInputSchema()));
        toolsArray.put(toolJson);
      }
      result.put("tools", toolsArray);
      return result;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ── Handler: tools/call ─────────────────────────────────────────────────

  private JSONObject handleToolsCall(AuthIdentity identity, JSONObject params) throws Exception {
    if (params == null) {
      throw new IllegalArgumentException("Missing params for tools/call");
    }

    String toolName = params.getString("name");
    JSONObject arguments = params.optJSONObject("arguments");

    log.info("MCP tools/call: tool={}, user={}, role={}, client={}, org={}",
        toolName, identity.userId, identity.roleId, identity.clientId, identity.orgId);

    return McpSessionManager.executeInContext(
        identity.userId, identity.roleId, identity.clientId,
        identity.orgId, null, () -> {
          OBContext.setAdminMode(true);
          try {
            McpToolRouter router = new McpToolRouter();
            return router.route(toolName, arguments);
          } finally {
            OBContext.restorePreviousMode();
          }
        });
  }

  // ── Handler: resources/list ─────────────────────────────────────────────

  private JSONObject handleResourcesList(AuthIdentity identity) throws Exception {
    OBContext.setAdminMode(true);
    try {
      McpResourceProvider provider = new McpResourceProvider();
      JSONObject result = new JSONObject();
      result.put("resources", provider.listResources());
      return result;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ── Handler: resources/read ─────────────────────────────────────────────

  private JSONObject handleResourcesRead(AuthIdentity identity, JSONObject params) throws Exception {
    String uri = params != null ? params.optString("uri", "") : "";
    if (uri.isEmpty()) {
      throw new IllegalArgumentException("Missing 'uri' parameter for resources/read");
    }

    OBContext.setAdminMode(true);
    try {
      McpResourceProvider provider = new McpResourceProvider();
      JSONObject resourceContent = provider.readResource(uri);

      JSONObject result = new JSONObject();
      JSONArray contents = new JSONArray();
      JSONObject content = new JSONObject();
      content.put("uri", uri);
      content.put("mimeType", "application/json");
      content.put("text", resourceContent.toString(2));
      contents.put(content);
      result.put("contents", contents);
      return result;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ── JSON-RPC error builder ──────────────────────────────────────────────

  private JSONObject buildJsonRpcError(Object id, int code, String message) throws JSONException {
    JSONObject error = new JSONObject();
    error.put("code", code);
    error.put("message", message != null ? message : "Internal error");

    JSONObject resp = new JSONObject();
    resp.put("jsonrpc", "2.0");
    resp.put("id", id != null ? id : JSONObject.NULL);
    resp.put("error", error);
    return resp;
  }

  // ── Utility methods ─────────────────────────────────────────────────────

  private String readRequestBody(HttpServletRequest request) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
    }
    return sb.toString();
  }

  private Set<String> parseScopes(String scopes) {
    if (scopes == null || scopes.trim().isEmpty()) {
      return Collections.emptySet();
    }
    return new HashSet<>(Arrays.asList(scopes.trim().split("\\s+")));
  }

  private void sendJsonError(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(CONTENT_TYPE_JSON);
    if (status == HttpServletResponse.SC_UNAUTHORIZED) {
      response.setHeader("WWW-Authenticate",
          "Bearer resource_metadata=\"/.well-known/oauth-protected-resource\"");
    }
    response.getWriter().write("{\"error\":\"" + escapeJson(message) + "\"}");
  }

  private String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  @SuppressWarnings("unchecked")
  private JSONObject mapToJsonObject(Map<String, Object> map) throws JSONException {
    JSONObject json = new JSONObject();
    if (map == null) {
      return json;
    }
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Map) {
        json.put(entry.getKey(), mapToJsonObject((Map<String, Object>) value));
      } else if (value instanceof List) {
        json.put(entry.getKey(), listToJsonArray((List<Object>) value));
      } else {
        json.put(entry.getKey(), value);
      }
    }
    return json;
  }

  @SuppressWarnings("unchecked")
  private JSONArray listToJsonArray(List<Object> list) throws JSONException {
    JSONArray array = new JSONArray();
    if (list == null) {
      return array;
    }
    for (Object item : list) {
      if (item instanceof Map) {
        array.put(mapToJsonObject((Map<String, Object>) item));
      } else if (item instanceof List) {
        array.put(listToJsonArray((List<Object>) item));
      } else {
        array.put(item);
      }
    }
    return array;
  }

  // ── Auth identity holder ───────────────────────────────────────────────

  /**
   * Holds the authenticated OAuth2 identity for the duration of one request.
   */
  static class AuthIdentity {
    final String userId;
    final String roleId;
    final String clientId;
    final String orgId;
    final String scopes;

    AuthIdentity(String userId, String roleId, String clientId, String orgId, String scopes) {
      this.userId = userId;
      this.roleId = roleId;
      this.clientId = clientId;
      this.orgId = orgId;
      this.scopes = scopes;
    }
  }

  // ── Custom exceptions ───────────────────────────────────────────────────

  static class McpMethodNotFoundException extends Exception {
    private static final long serialVersionUID = 1L;

    McpMethodNotFoundException(String message) {
      super(message);
    }
  }
}
