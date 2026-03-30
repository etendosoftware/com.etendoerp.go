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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.oauth2.OAuth2Filter;

/**
 * MCP (Model Context Protocol) servlet implementing SSE transport over javax.servlet.
 * <p>
 * The MCP Java SDK's built-in {@code HttpServletSseServerTransportProvider} requires
 * jakarta.servlet (Servlet 6.0), which is incompatible with Etendo's Tomcat 9 runtime
 * (javax.servlet / Servlet 4.0). This servlet implements the MCP SSE transport manually
 * using {@link AsyncContext}.
 * <p>
 * <b>MCP SSE Protocol:</b>
 * <ol>
 *   <li>Client sends GET to {@code /sws/mcp} — server opens SSE stream, sends
 *       an {@code endpoint} event with a POST URL containing the session ID</li>
 *   <li>Client sends JSON-RPC 2.0 messages as POST to that endpoint URL</li>
 *   <li>Server processes each message and writes SSE {@code message} events back
 *       on the held GET connection</li>
 * </ol>
 * <p>
 * Authentication is handled upstream by {@link OAuth2Filter}, which sets request
 * attributes (userId, roleId, clientId, orgId, scopes). Each tool call is executed
 * within a scoped OBContext via {@link McpSessionManager}.
 */
public class McpServlet extends HttpServlet {

  private static final long serialVersionUID = 1L;
  private static final Logger log = LogManager.getLogger(McpServlet.class);

  private static final String PROTOCOL_VERSION = "2024-11-05";
  private static final String SERVER_NAME = "etendo-neo";
  private static final String SERVER_VERSION = "1.0.0";

  private static final String CONTENT_TYPE_SSE = "text/event-stream";
  private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";
  private static final String CHARSET_UTF8 = "UTF-8";

  /** Active SSE sessions keyed by session ID. Thread-safe for concurrent access. */
  private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();

  // ── GET: Open SSE connection ────────────────────────────────────────────

  /**
   * Handle GET /sws/mcp — open an SSE connection.
   * <p>
   * Starts an async context with no timeout, creates an SseSession storing the
   * OAuth2 identity, registers cleanup listeners, and sends the initial
   * {@code endpoint} event telling the client where to POST JSON-RPC messages.
   */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {

    // Read OAuth2 identity set by OAuth2Filter
    String userId = (String) request.getAttribute(OAuth2Filter.ATTR_USER_ID);
    String roleId = (String) request.getAttribute(OAuth2Filter.ATTR_ROLE_ID);
    String clientId = (String) request.getAttribute(OAuth2Filter.ATTR_CLIENT_ID);
    String orgId = (String) request.getAttribute(OAuth2Filter.ATTR_ORG_ID);
    String scopes = (String) request.getAttribute(OAuth2Filter.ATTR_SCOPES);

    // SSE response headers
    response.setContentType(CONTENT_TYPE_SSE);
    response.setCharacterEncoding(CHARSET_UTF8);
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("X-Accel-Buffering", "no");

    // Start async context — SSE connections are long-lived
    AsyncContext asyncContext = request.startAsync();
    asyncContext.setTimeout(0); // No timeout

    // Create session
    String sessionId = UUID.randomUUID().toString();
    SseSession session = new SseSession(
        sessionId, asyncContext, userId, roleId, clientId, orgId, scopes);
    sessions.put(sessionId, session);

    // Register cleanup on disconnect, timeout, or error
    asyncContext.addListener(new AsyncListener() {
      @Override
      public void onComplete(AsyncEvent event) {
        cleanupSession(sessionId);
      }

      @Override
      public void onTimeout(AsyncEvent event) {
        cleanupSession(sessionId);
      }

      @Override
      public void onError(AsyncEvent event) {
        cleanupSession(sessionId);
      }

      @Override
      public void onStartAsync(AsyncEvent event) {
        // No action needed
      }
    });

    // Send the initial "endpoint" event — tells the client the POST URL
    String postUrl = request.getContextPath() + request.getServletPath() + "/" + sessionId;
    sendSseEvent(session, "endpoint", postUrl);

    log.info("MCP SSE session opened: {} (user: {}, role: {})", sessionId, userId, roleId);
  }

  // ── POST: Receive JSON-RPC message ──────────────────────────────────────

  /**
   * Handle POST /sws/mcp/{sessionId} — receive a JSON-RPC 2.0 message.
   * <p>
   * Parses the session ID from the path, looks up the SSE session, dispatches
   * the JSON-RPC method, and writes the response back as an SSE event on the
   * GET connection. The HTTP response to the POST is always 202 Accepted.
   */
  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {

    String pathInfo = request.getPathInfo();
    if (pathInfo == null || pathInfo.length() < 2) {
      sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing session ID in path");
      return;
    }

    String sessionId = pathInfo.substring(1); // Strip leading '/'
    SseSession session = sessions.get(sessionId);
    if (session == null) {
      sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Session not found: " + sessionId);
      return;
    }

    // Read request body
    String body = readRequestBody(request);

    try {
      JSONObject rpcMessage = new JSONObject(body);
      String method = rpcMessage.optString("method", "");
      Object id = rpcMessage.opt("id"); // null for notifications (e.g. "initialized")

      log.debug("MCP message received: method={}, id={}, session={}", method, id, sessionId);

      // Dispatch the method
      JSONObject result = dispatchMethod(session, method, rpcMessage.optJSONObject("params"));

      // Send JSON-RPC response via SSE (only for requests, not notifications)
      if (id != null && result != null) {
        JSONObject rpcResponse = new JSONObject();
        rpcResponse.put("jsonrpc", "2.0");
        rpcResponse.put("id", id);
        rpcResponse.put("result", result);
        sendSseEvent(session, "message", rpcResponse.toString());
      }

      // HTTP POST always returns 202 Accepted
      response.setStatus(HttpServletResponse.SC_ACCEPTED);
      response.setContentType(CONTENT_TYPE_JSON);
      response.getWriter().write("{\"status\":\"accepted\"}");

    } catch (Exception e) {
      log.error("Error processing MCP message for session {}: {}", sessionId, e.getMessage(), e);

      // Try to send JSON-RPC error via SSE with appropriate error code
      try {
        Object rpcId = new JSONObject(body).opt("id");
        int errorCode = (e instanceof McpMethodNotFoundException) ? -32601 : -32603;
        JSONObject errorResponse = buildJsonRpcError(rpcId, errorCode, e.getMessage());
        sendSseEvent(session, "message", errorResponse.toString());
      } catch (Exception sseErr) {
        log.error("Failed to send error via SSE for session {}", sessionId, sseErr);
      }

      // HTTP POST still returns 202 — errors go via SSE
      response.setStatus(HttpServletResponse.SC_ACCEPTED);
      response.setContentType(CONTENT_TYPE_JSON);
      response.getWriter().write("{\"status\":\"accepted\"}");
    }
  }

  // ── JSON-RPC method dispatch ────────────────────────────────────────────

  /**
   * Route a JSON-RPC method to its handler.
   *
   * @param session the SSE session with OAuth2 identity
   * @param method  the JSON-RPC method name
   * @param params  the params object (may be null)
   * @return the result object to include in the JSON-RPC response, or null for notifications
   */
  private JSONObject dispatchMethod(SseSession session, String method, JSONObject params)
      throws Exception {
    switch (method) {
      case "initialize":
        return handleInitialize();
      case "initialized":
        // Notification — no response required
        return null;
      case "ping":
        return new JSONObject();
      case "tools/list":
        return handleToolsList(session);
      case "tools/call":
        return handleToolsCall(session, params);
      case "resources/list":
        return handleResourcesList(session);
      case "resources/read":
        return handleResourcesRead(session, params);
      default:
        throw new McpMethodNotFoundException("Method not found: " + method);
    }
  }

  // ── Handler: initialize ─────────────────────────────────────────────────

  /**
   * Handle the "initialize" method — return server capabilities and info.
   */
  private JSONObject handleInitialize() throws JSONException {
    JSONObject result = new JSONObject();
    result.put("protocolVersion", PROTOCOL_VERSION);

    // Capabilities
    JSONObject capabilities = new JSONObject();

    JSONObject toolsCap = new JSONObject();
    toolsCap.put("listChanged", false);
    capabilities.put("tools", toolsCap);

    JSONObject resourcesCap = new JSONObject();
    resourcesCap.put("listChanged", false);
    capabilities.put("resources", resourcesCap);

    result.put("capabilities", capabilities);

    // Server info
    JSONObject serverInfo = new JSONObject();
    serverInfo.put("name", SERVER_NAME);
    serverInfo.put("version", SERVER_VERSION);
    result.put("serverInfo", serverInfo);

    return result;
  }

  // ── Handler: tools/list ─────────────────────────────────────────────────

  /**
   * Handle "tools/list" — return all MCP tools the authenticated user can access.
   * Executes within an OBContext scoped to the session's OAuth2 identity.
   */
  private JSONObject handleToolsList(SseSession session) throws Exception {
    return McpSessionManager.executeInContext(
        session.userId, session.roleId, session.clientId, session.orgId, null,
        () -> {
          ToolRegistry registry = new ToolRegistry();
          Set<String> scopes = parseScopes(session.scopes);
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
        });
  }

  // ── Handler: tools/call ─────────────────────────────────────────────────

  /**
   * Handle "tools/call" — execute a tool by name.
   * <p>
   * This is a placeholder implementation. Real tool handlers (CRUD, process,
   * report, discover) will be wired in tasks C4-C7.
   */
  private JSONObject handleToolsCall(SseSession session, JSONObject params) throws Exception {
    if (params == null) {
      throw new IllegalArgumentException("Missing params for tools/call");
    }

    String toolName = params.getString("name");
    JSONObject arguments = params.optJSONObject("arguments");

    return McpSessionManager.executeInContext(
        session.userId, session.roleId, session.clientId, session.orgId, null,
        () -> {
          // TODO: Route to actual tool handlers (C4, C5, C6, C7)
          // For now, return a placeholder response
          log.info("MCP tools/call: tool={}, session={}", toolName, session.id);

          JSONObject textContent = new JSONObject();
          textContent.put("type", "text");
          textContent.put("text",
              "Tool '" + toolName + "' called successfully (handler not yet implemented)");

          JSONObject result = new JSONObject();
          JSONArray contentArray = new JSONArray();
          contentArray.put(textContent);
          result.put("content", contentArray);
          return result;
        });
  }

  // ── Handler: resources/list ─────────────────────────────────────────────

  /**
   * Handle "resources/list" — return available resources.
   * Queries all active specs and builds a resource catalog that AI agents can browse
   * without invoking tool calls.
   */
  private JSONObject handleResourcesList(SseSession session) throws Exception {
    return McpSessionManager.executeInContext(
        session.userId, session.roleId, session.clientId, session.orgId, null,
        () -> {
          McpResourceProvider provider = new McpResourceProvider();
          JSONObject result = new JSONObject();
          result.put("resources", provider.listResources());
          return result;
        });
  }

  // ── Handler: resources/read ─────────────────────────────────────────────

  /**
   * Handle "resources/read" — read a specific resource by URI.
   * Delegates to McpResourceProvider which resolves spec schemas, entity details,
   * and process parameters from the ETGO_SF_* tables.
   */
  private JSONObject handleResourcesRead(SseSession session, JSONObject params) throws Exception {
    String uri = params != null ? params.optString("uri", "") : "";
    if (uri.isEmpty()) {
      throw new IllegalArgumentException("Missing 'uri' parameter for resources/read");
    }

    return McpSessionManager.executeInContext(
        session.userId, session.roleId, session.clientId, session.orgId, null,
        () -> {
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
        });
  }

  // ── SSE helpers ─────────────────────────────────────────────────────────

  /**
   * Send an SSE event on the session's async response writer.
   * <p>
   * Format per SSE spec:
   * <pre>
   * event: {eventType}\n
   * data: {data}\n
   * \n
   * </pre>
   *
   * @param session   the SSE session
   * @param eventType SSE event name (e.g. "endpoint", "message")
   * @param data      event payload (typically JSON string or URL)
   */
  private void sendSseEvent(SseSession session, String eventType, String data) throws IOException {
    PrintWriter writer = session.asyncContext.getResponse().getWriter();
    writer.write("event: " + eventType + "\n");
    writer.write("data: " + data + "\n\n");
    writer.flush();

    if (writer.checkError()) {
      log.warn("SSE write error detected for session {}, cleaning up", session.id);
      cleanupSession(session.id);
    }
  }

  // ── Session cleanup ─────────────────────────────────────────────────────

  /**
   * Remove a session from the map and complete its async context.
   */
  private void cleanupSession(String sessionId) {
    SseSession removed = sessions.remove(sessionId);
    if (removed != null) {
      try {
        removed.asyncContext.complete();
      } catch (Exception e) {
        // Already completed or response committed — safe to ignore
        log.debug("AsyncContext already completed for session {}", sessionId);
      }
      log.info("MCP SSE session closed: {}", sessionId);
    }
  }

  // ── JSON-RPC error builder ──────────────────────────────────────────────

  /**
   * Build a JSON-RPC 2.0 error response.
   *
   * @param id      the request ID (may be null)
   * @param code    JSON-RPC error code (-32600 to -32603, or application-defined)
   * @param message human-readable error message
   * @return the complete JSON-RPC error response object
   */
  private JSONObject buildJsonRpcError(Object id, int code, String message) throws JSONException {
    JSONObject error = new JSONObject();
    error.put("code", code);
    error.put("message", message != null ? message : "Internal error");

    JSONObject response = new JSONObject();
    response.put("jsonrpc", "2.0");
    if (id != null) {
      response.put("id", id);
    } else {
      response.put("id", JSONObject.NULL);
    }
    response.put("error", error);
    return response;
  }

  // ── Utility methods ─────────────────────────────────────────────────────

  /**
   * Read the full request body as a string.
   */
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

  /**
   * Parse a space-separated scope string into a Set.
   * Returns empty set if scopes is null or blank.
   */
  private Set<String> parseScopes(String scopes) {
    if (scopes == null || scopes.trim().isEmpty()) {
      return Collections.emptySet();
    }
    return new HashSet<>(Arrays.asList(scopes.trim().split("\\s+")));
  }

  /**
   * Send a JSON error as an HTTP response body (for POST errors before SSE).
   */
  private void sendJsonError(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(CONTENT_TYPE_JSON);
    response.getWriter().write("{\"error\":\"" + escapeJson(message) + "\"}");
  }

  /**
   * Minimal JSON string escaping.
   */
  private String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  /**
   * Convert a {@code Map<String, Object>} to a JSONObject, handling nested maps and lists.
   */
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

  /**
   * Convert a List to a JSONArray, handling nested maps and lists.
   */
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

  // ── SSE Session ─────────────────────────────────────────────────────────

  /**
   * Holds the state for a single MCP SSE connection.
   * <p>
   * Stores the async context (for writing SSE events) and the OAuth2 identity
   * (for scoping OBContext on each tool call).
   */
  static class SseSession {
    final String id;
    final AsyncContext asyncContext;
    final String userId;
    final String roleId;
    final String clientId;
    final String orgId;
    final String scopes;

    SseSession(String id, AsyncContext asyncContext, String userId, String roleId,
        String clientId, String orgId, String scopes) {
      this.id = id;
      this.asyncContext = asyncContext;
      this.userId = userId;
      this.roleId = roleId;
      this.clientId = clientId;
      this.orgId = orgId;
      this.scopes = scopes;
    }
  }

  // ── Custom exceptions ───────────────────────────────────────────────────

  /**
   * Thrown when a JSON-RPC method is not recognized.
   * Maps to JSON-RPC error code -32601 (Method not found).
   */
  static class McpMethodNotFoundException extends Exception {
    private static final long serialVersionUID = 1L;

    McpMethodNotFoundException(String message) {
      super(message);
    }
  }
}
