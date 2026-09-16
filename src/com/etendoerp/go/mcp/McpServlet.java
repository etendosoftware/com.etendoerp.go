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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.CorsUtils;
import com.etendoerp.go.common.ProtocolErrorAdapters;
import com.etendoerp.go.common.PublicUrlResolver;
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
  /** The only JSON-RPC method that produces a telemetry row (B1). */
  private static final String TOOLS_CALL = "tools/call";
  // Browser sessions use the validated legacy JWT path. RBAC still filters the
  // catalog and authorizes each operation by the user's role and window access.
  private static final String LEGACY_JWT_FALLBACK_SCOPES =
      "neo:read neo:write neo:process neo:report";

  // ── CORS ───────────────────────────────────────────────────────────────

  private void setCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
    CorsUtils.apply(request, response, "GET, POST, OPTIONS",
        "Content-Type, Authorization, Accept, Mcp-Session-Id",
        "Mcp-Session-Id, WWW-Authenticate", false);
  }

  @Override
  protected void doOptions(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    setCorsHeaders(request, response);
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

    setCorsHeaders(request, response);

    // Authenticate via OAuth2 Bearer token
    AuthIdentity identity = authenticate(request, response);
    if (identity == null) {
      return; // Response already sent by authenticate()
    }

    response.setContentType(CONTENT_TYPE_JSON);

    String body = readRequestBody(request);
    // Telemetry only (B1): started before parsing so the measured duration is the whole call, and
    // read here because nothing downstream sees the raw request.
    long startedAtNanos = System.nanoTime();
    JSONObject callParams = null;
    String toolName = null;

    McpUsageTelemetry.setCurrentSessionKey(
        StringUtils.trimToNull(request.getHeader(McpUsageTelemetry.HEADER_SESSION_ID)));
    try {
      JSONObject rpcMessage = new JSONObject(body);
      String method = rpcMessage.optString("method", "");
      Object id = rpcMessage.opt("id");
      callParams = rpcMessage.optJSONObject("params");
      toolName = TOOLS_CALL.equals(method) && callParams != null
          ? callParams.optString("name", null)
          : null;

      log.debug("MCP request: method={}, id={}", method, id);

      // Dispatch the method
      JSONObject result = dispatchMethod(identity, method, callParams, response);

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

      String rendered = rpcResponse.toString();
      response.setStatus(HttpServletResponse.SC_OK);
      response.getWriter().write(rendered);

      // AFTER the business transaction has been committed and closed by McpSessionManager, and
      // after the caller already has its answer: nothing below can affect either.
      recordToolCall(identity, new McpCallObservation(request, body, rendered, startedAtNanos),
          toolName, callParams, result, null);

    } catch (Exception e) {
      log.error("Error processing MCP message: {}", e.getMessage(), e);

      try {
        Object rpcId = new JSONObject(body).opt("id");
        int errorCode = (e instanceof McpMethodNotFoundException) ? -32601 : -32603;
        JSONObject errorResponse = buildJsonRpcError(rpcId, errorCode, e.getMessage());

        String rendered = errorResponse.toString();
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(rendered);

        recordToolCall(identity, new McpCallObservation(request, body, rendered, startedAtNanos),
            toolName, callParams, null, McpConstants.ERROR_SERVER);
      } catch (Exception ex) {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.getWriter().write("{\"error\":\"Internal server error\"}");
      }
    } finally {
      // Servlet threads are pooled: a leaked session key would attribute one client's calls to
      // another client's session.
      McpUsageTelemetry.clearCurrentSessionKey();
    }
  }

  // ── Telemetry (Track B1) ────────────────────────────────────────────────

  /**
   * Stop the telemetry writer on undeploy or container shutdown, so whatever is still queued is
   * reported rather than lost silently (see {@link McpUsageLogger#shutdown()}).
   */
  @Override
  public void destroy() {
    try {
      McpUsageLogger.shutdown();
    } catch (Exception e) {
      log.debug("Could not stop MCP telemetry on servlet destroy.", e);
    }
    super.destroy();
  }
  // ── Telemetry (Track B1) ────────────────────────────────────────────────

  /**
   * Hand one {@code tools/call} to {@link McpUsageLogger}, which writes it on its own thread, on its
   * own connection, in its own transaction.
   * <p>
   * Called only once the response has been written, so the user's answer is already out and the
   * business transaction is already committed and closed. The whole body is wrapped in a
   * {@code catch (Throwable)} for the same reason the logger is: a defect in telemetry must not turn
   * a successful tool call into a failed HTTP request.
   *
   * @param forcedErrorCode the canonical code to record when the call blew up before producing a
   *     result envelope; null when {@code result} carries its own outcome
   */
  private void recordToolCall(AuthIdentity identity, McpCallObservation call, String toolName,
      JSONObject params, JSONObject result, String forcedErrorCode) {
    try {
      if (StringUtils.isBlank(toolName) || !McpUsageLogger.isEnabled()) {
        return;
      }
      JSONObject arguments = params != null ? params.optJSONObject("arguments") : null;
      String sessionKey = call.sessionKey();
      McpUsageTelemetry.ClientInfo client = McpUsageTelemetry.clientInfo(sessionKey);

      boolean failed = forcedErrorCode != null || McpUsageTelemetry.isError(result);
      String errorCode = errorCodeToRecord(forcedErrorCode, failed, result);

      // B3/D31: a neo_feedback call IS a tool call, so it produces exactly ONE row — this one —
      // discriminated by row_type and carrying the report. It therefore inherits the session,
      // tenant, timestamp and client columns, and lands in the same sequence as the calls that
      // provoked it. The payload is stored only when the tool accepted the verdict; a rejected or
      // rate-limited call is an ordinary error row with nothing in Payload.
      boolean isFeedback = McpConstants.TOOL_NEO_FEEDBACK.equals(toolName);
      String payload = (isFeedback && !failed) ? McpFeedbackTool.payloadFor(arguments) : null;

      McpUsageLogger.enqueue(McpUsageRow.builder()
          .clientId(identity != null ? identity.clientId : null)
          .orgId(identity != null ? identity.orgId : null)
          .userId(identity != null ? identity.userId : null)
          .sessionKey(sessionKey)
          .toolName(toolName)
          .verb(McpUsageTelemetry.verbFor(toolName))
          .targetEntity(McpUsageTelemetry.targetEntityFor(arguments))
          .fieldsTouched(McpUsageTelemetry.fieldsTouched(arguments))
          .outcome(failed ? McpUsageRow.OUTCOME_ERROR : McpUsageRow.OUTCOME_OK)
          .errorCode(errorCode)
          .durationMs(call.durationMs())
          .reqBytes(call.reqBytes())
          .respBytes(call.respBytes())
          .clientName(client.getName())
          .clientVersion(client.getVersion())
          .rowType(isFeedback ? McpUsageRow.ROW_TYPE_FEEDBACK : McpUsageRow.ROW_TYPE_TOOL_CALL)
          .payload(payload)
          .build());
    } catch (Throwable t) { // NOSONAR — telemetry never escalates to the caller.
      log.debug("Could not record MCP usage for tool '{}'.", toolName, t);
    }
  }

  /**
   * The canonical error code to store on the row, or null when the call succeeded.
   *
   * <p>Its own method rather than a nested ternary (java:S3358): this expression decides whether a
   * call is remembered as failed and under which code, so it is worth reading at a glance. The
   * order matters — a {@code forcedErrorCode} is set when the call blew up <i>before</i> producing
   * a result envelope, so there is no envelope to derive a code from and it wins outright.</p>
   *
   * @param forcedErrorCode the code imposed by the caller, or null to derive one
   * @param failed          whether the call is being recorded as a failure
   * @param result          the tool result envelope, which may carry its own code
   * @return the code to store, or null for a successful call
   */
  private static String errorCodeToRecord(String forcedErrorCode, boolean failed,
      JSONObject result) {
    if (forcedErrorCode != null) {
      return forcedErrorCode;
    }
    return failed ? McpUsageTelemetry.errorCodeFrom(result) : null;
  }

  // ── GET: Server info / health check ────────────────────────────────────

  /**
   * Handle GET /sws/mcp — return server info for discovery.
   * Also handles GET /sws/mcp/.well-known/oauth-protected-resource for RFC 9728.
   */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    setCorsHeaders(request, response);
    response.setContentType(CONTENT_TYPE_JSON);

    String pathInfo = request.getPathInfo();
    if ("/.well-known/oauth-protected-resource".equals(pathInfo)) {
      handleResourceMetadata(request, response);
      return;
    }

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

  /**
   * Serve OAuth2 Protected Resource Metadata (RFC 9728).
   * MCP clients use this to discover the authorization server.
   */
  private void handleResourceMetadata(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String mcpResourceUrl = PublicUrlResolver.resolveMcpResourceUrl(request);
    String oauth2Url = PublicUrlResolver.resolveOAuth2Url(request);
    response.setStatus(HttpServletResponse.SC_OK);
    if (mcpResourceUrl == null || oauth2Url == null) {
      ProtocolErrorAdapters.writeSimpleJsonError(response,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to resolve public MCP/OAuth2 URL");
      return;
    }
    try {
      JSONObject meta = new JSONObject();
      meta.put("resource", mcpResourceUrl);
      meta.put("authorization_servers", new JSONArray().put(oauth2Url));
      meta.put("scopes_supported", new JSONArray()
          .put(LEGACY_JWT_FALLBACK_SCOPES).put("neo:write").put("neo:process").put("neo:report").put("neo:*"));
      meta.put("bearer_methods_supported", new JSONArray().put("header"));
      response.getWriter().write(meta.toString());
    } catch (JSONException e) {
      response.getWriter().write("{\"resource\":\"" + mcpResourceUrl + "\"}");
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
      sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
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
          LEGACY_JWT_FALLBACK_SCOPES);
    } catch (Exception e) {
      log.warn("Both OAuth2 and JWT authentication failed for MCP request");
      sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
          "Invalid or expired token (OAuth2 and JWT both failed)");
      return null;
    }
  }

  // ── JSON-RPC method dispatch ────────────────────────────────────────────

  /**
   * Route a JSON-RPC method to its handler.
   */
  private JSONObject dispatchMethod(AuthIdentity identity, String method, JSONObject params,
      HttpServletResponse response) throws Exception {
    switch (method) {
      case "initialize":
        return handleInitialize(params, response);
      case "initialized":
      case "notifications/initialized":
        return null;
      case "ping":
        return new JSONObject();
      case "tools/list":
        return handleToolsList(identity);
      case TOOLS_CALL:
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

  /**
   * Answer the handshake and open a telemetry session.
   * <p>
   * The session key is published in the {@value McpUsageTelemetry#HEADER_SESSION_ID} response
   * header, where the Streamable HTTP transport says it belongs; a spec-conformant client returns
   * it on every later request, which is what lets a sequence of tool calls be read as one task and
   * what carries {@code clientInfo} forward (B1). A client that ignores the header still works — it
   * simply produces rows with no session key and no client name.
   */
  private JSONObject handleInitialize(JSONObject params, HttpServletResponse response)
      throws JSONException {
    try {
      response.setHeader(McpUsageTelemetry.HEADER_SESSION_ID, McpUsageTelemetry.openSession(params));
    } catch (Exception e) {
      // Telemetry must never break the handshake.
      log.debug("Could not open an MCP telemetry session.", e);
    }

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
    return McpSessionManager.executeInContext(
        identity.userId, identity.roleId, identity.clientId,
        identity.orgId, null, () -> {
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
        });
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
            return router.route(toolName, arguments, parseScopes(identity.scopes));
          } finally {
            OBContext.restorePreviousMode();
          }
        });
  }

  // ── Handler: resources/list ─────────────────────────────────────────────

  private JSONObject handleResourcesList(AuthIdentity identity) throws Exception {
    Set<String> scopes = parseScopes(identity != null ? identity.scopes : null);
    McpAuthorizationService.authorizeResourceRead(scopes);
    return McpSessionManager.executeInContext(
        identity.userId, identity.roleId, identity.clientId,
        identity.orgId, null, () -> {
          OBContext.setAdminMode(true);
          try {
            McpResourceProvider provider = new McpResourceProvider();
            JSONObject result = new JSONObject();
            result.put("resources", provider.listResources());
            return result;
          } catch (org.openbravo.base.exception.OBException e) {
            throw e;
          } catch (Exception e) {
            throw new org.openbravo.base.exception.OBException(e);
          } finally {
            OBContext.restorePreviousMode();
          }
        });
  }

  // ── Handler: resources/read ─────────────────────────────────────────────

  private JSONObject handleResourcesRead(AuthIdentity identity, JSONObject params) throws Exception {
    String uri = params != null ? params.optString("uri", "") : "";
    if (uri.isEmpty()) {
      throw new IllegalArgumentException("Missing 'uri' parameter for resources/read");
    }

    Set<String> scopes = parseScopes(identity.scopes);
    McpAuthorizationService.authorizeResourceRead(scopes);
    return McpSessionManager.executeInContext(
        identity.userId, identity.roleId, identity.clientId,
        identity.orgId, null, () -> {
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
        });
  }

  // ── JSON-RPC error builder ──────────────────────────────────────────────

  private JSONObject buildJsonRpcError(Object id, int code, String message) throws JSONException {
    return ProtocolErrorAdapters.buildJsonRpcError(id, code, message);
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
    return McpAuthorizationService.parseScopes(scopes);
  }

  private void sendJsonError(HttpServletRequest request, HttpServletResponse response,
      int status, String message) throws IOException {
    if (status == HttpServletResponse.SC_UNAUTHORIZED) {
      String metaUrl = PublicUrlResolver.appendPath(
          PublicUrlResolver.resolveMcpResourceUrl(request),
          ".well-known/oauth-protected-resource");
      if (metaUrl != null) {
        response.setHeader("WWW-Authenticate",
            "Bearer resource_metadata=\"" + metaUrl + "\"");
      }
    }
    ProtocolErrorAdapters.writeSimpleJsonError(response, status, message);
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
