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
import java.time.Instant;
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

import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.common.CorsUtils;
import com.etendoerp.go.common.ProtocolErrorAdapters;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.oauth2.ApiScopes;
import com.etendoerp.go.oauth2.OAuth2Filter;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.session.GoLegacyBearer;
import com.etendoerp.go.session.GoNeoAuth;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.JdbcGoSessionStore;

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

  private static final String SERVER_NAME = "etendo-mcp";
  private static final String SERVER_VERSION = "1.0.0";
  /** Human-readable name clients may show instead of {@link #SERVER_NAME} (MCP 2025-11-25). */
  private static final String SERVER_TITLE = "Etendo MCP";
  private static final String SERVER_WEBSITE_URL = "https://app.etendo.ai";
  /** {@code Implementation.description} (MCP 2025-11-25). */
  static final String SERVER_DESCRIPTION = "Etendo ERP for agents: read and write documents, "
      + "master data and processes, and run reports, within the permissions of your role.";
  /**
   * Public, unauthenticated icon advertised in {@code serverInfo.icons} (MCP 2025-11-25, SEP-973).
   * Same file for every environment, so a fixed production URL is fine. Clients that predate the
   * field ignore it.
   */
  private static final String SERVER_ICON_URL = "https://app.etendo.ai/favicon.png";
  private static final String SERVER_ICON_MIME_TYPE = "image/png";
  private static final String SERVER_ICON_SIZES = "513x513";

  private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";
  /** JSON-RPC 2.0: the method does not exist or is not available. */
  static final int JSON_RPC_METHOD_NOT_FOUND = -32601;
  /** JSON-RPC 2.0: internal JSON-RPC error. */
  static final int JSON_RPC_INTERNAL_ERROR = -32603;
  /** {@code Result.resultType} of every final modern result (MCP 2026-07-28). */
  private static final String RESULT_TYPE_COMPLETE = "complete";
  /** {@code _meta} key under which a modern result names the server. */
  static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
  /**
   * Freshness hint for the per-caller catalog results (design §5.2): short enough that a config
   * push or a role change reaches a modern client within minutes.
   */
  static final long CATALOG_TTL_MS = 300_000L;
  /** {@code cacheScope} of a result that depends on the caller's role, scopes or language. */
  static final String CACHE_SCOPE_PRIVATE = "private";
  /** Modern methods whose result is a {@code CacheableResult} we compute per caller. */
  private static final Set<String> PER_CALLER_CACHEABLE =
      Set.of("tools/list", "resources/list", "resources/read");
  /** The legacy-era liveness method, removed by MCP 2026-07-28. */
  private static final String PING = "ping";
  /** Where MCP 2026-07-28 requests carry the client's identity, under {@code params._meta}. */
  static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
  /** The handshake method: the one request that carries no {@code MCP-Protocol-Version}. */
  private static final String INITIALIZE = "initialize";
  /** The only JSON-RPC method that produces a telemetry row (B1). */
  private static final String TOOLS_CALL = "tools/call";
  // Browser sessions use the validated legacy JWT path. RBAC still filters the
  // catalog and authorizes each operation by the user's role and window access.
  private static final String LEGACY_JWT_FALLBACK_SCOPES = String.join(" ",
      ApiScopes.READ, ApiScopes.WRITE, ApiScopes.PROCESS, ApiScopes.REPORT);

  private static final GoSessionAuthenticator SESSION_AUTHENTICATOR =
      new GoSessionAuthenticator(new GoSessionService(new JdbcGoSessionStore()));

  // javax.servlet.http.HttpServletResponse predates RFC 7231 and has no 402 constant.
  private static final int SC_PAYMENT_REQUIRED = 402;

  private final transient TenantEnvironmentLifecycleService lifecycleService;

  /** Production wiring: the tenant lifecycle policy every environment surface shares. */
  public McpServlet() {
    this(new TenantEnvironmentLifecycleService());
  }

  /**
   * Wiring with an explicit lifecycle service, for tests.
   *
   * @param lifecycleService answers the commercial access decision for a client
   */
  McpServlet(TenantEnvironmentLifecycleService lifecycleService) {
    this.lifecycleService = lifecycleService;
  }

  // ── CORS ───────────────────────────────────────────────────────────────

  private void setCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
    // Mcp-Method / Mcp-Name: the mirror headers every 2026-07-28 POST carries (ETP-5640).
    // Mcp-Session-Id stays for the legacy era's browser clients.
    CorsUtils.apply(request, response, "GET, POST, OPTIONS",
        "Content-Type, Authorization, Accept, Mcp-Session-Id, " + McpProtocolVersion.HEADER
            + ", " + McpRequestEra.HEADER_METHOD + ", " + McpRequestEra.HEADER_NAME
            + ", X-Go-CSRF, X-Go-Account",
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
    if (refuseCommerciallyBlocked(request, response, identity)) {
      return; // 402 already sent
    }

    response.setContentType(CONTENT_TYPE_JSON);

    String body = readRequestBody(request);
    // Telemetry only (B1): started before parsing so the measured duration is the whole call, and
    // read here because nothing downstream sees the raw request.
    long startedAtNanos = System.nanoTime();
    JSONObject callParams = null;
    String toolName = null;
    String method = null;
    McpRequestEra.Classification era = McpRequestEra.Classification.legacy();

    McpUsageTelemetry.setCurrentSessionKey(
        StringUtils.trimToNull(request.getHeader(McpUsageTelemetry.HEADER_SESSION_ID)));
    try {
      JSONObject rpcMessage = new JSONObject(body);
      method = rpcMessage.optString("method", "");
      Object id = rpcMessage.opt("id");
      callParams = rpcMessage.optJSONObject("params");
      toolName = TOOLS_CALL.equals(method) && callParams != null
          ? callParams.optString("name", null)
          : null;

      log.debug("MCP request: method={}, id={}", method, id);

      era = classifyEra(request, method, callParams);
      if (era.isModern()) {
        if (!acceptModern(response, id, method, callParams, era)) {
          return; // 400 already written
        }
      } else if (!INITIALIZE.equals(method)) {
        // ETP-5639: validated, never refused — see McpProtocolVersion for the lenient policy.
        McpProtocolVersion.forRequest(request.getHeader(McpProtocolVersion.HEADER),
            McpUsageTelemetry.clientInfo(McpUsageTelemetry.currentSessionKey())
                .getProtocolVersion(),
            clientNameFor(callParams));
      }

      // Dispatch the method
      JSONObject result = dispatchMethod(identity, method, callParams, response, era.isModern());

      // Notifications (no id) don't get a response body: 202 Accepted (Streamable HTTP,
      // MCP 2025-03-26 onwards).
      if (id == null) {
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        return;
      }

      // Build JSON-RPC response
      JSONObject rpcResponse = new JSONObject();
      rpcResponse.put("jsonrpc", "2.0");
      rpcResponse.put("id", id);
      JSONObject answer = result != null ? result : new JSONObject();
      rpcResponse.put("result", era.isModern() ? decorateModern(method, answer) : answer);

      String rendered = rpcResponse.toString();
      response.setStatus(HttpServletResponse.SC_OK);
      response.getWriter().write(rendered);

      // AFTER the business transaction has been committed and closed by McpSessionManager, and
      // after the caller already has its answer: nothing below can affect either.
      recordToolCall(identity, new McpCallObservation(request, body, rendered, startedAtNanos),
          toolName, callParams, result, null);

    } catch (McpResourceNotFoundException e) {
      // Not a server failure: one WARN line, no stack trace. The modern era answers -32602, the
      // code MCP 2026-07-28 gives it; the legacy era keeps the -32603 it always answered.
      log.warn("MCP client read a missing resource: {} (client={}) session={}", e.getMessage(),
          clientNameFor(callParams), McpUsageTelemetry.sessionForLog());
      writeRpcError(response, body,
          era.isModern() ? McpRequestEra.INVALID_PARAMS : JSON_RPC_INTERNAL_ERROR,
          e.getMessage());
    } catch (McpMethodNotFoundException e) {
      // A client asking for something we do not offer — chiefly 2026-07-28 clients probing with
      // server/discover before falling back to initialize. Not a server failure: one WARN line, no
      // stack trace, and the same -32601 as before. No telemetry row: only tools/call has a tool
      // name, and an unknown method is never one.
      log.warn("MCP client called unsupported method '{}' (client={}) session={}", method,
          clientNameFor(callParams), McpUsageTelemetry.sessionForLog());
      // The modern era answers 404 (Streamable HTTP, 2026-07-28); the legacy era keeps its 200.
      writeRpcError(response, body, JSON_RPC_METHOD_NOT_FOUND, e.getMessage(),
          era.isModern() ? HttpServletResponse.SC_NOT_FOUND : HttpServletResponse.SC_OK, null);
    } catch (Exception e) {
      log.error("Error processing MCP message: {} session={}", e.getMessage(),
          McpUsageTelemetry.sessionForLog(), e);

      String rendered = writeRpcError(response, body, JSON_RPC_INTERNAL_ERROR, e.getMessage());
      if (rendered != null) {
        recordToolCall(identity, new McpCallObservation(request, body, rendered, startedAtNanos),
            toolName, callParams, null, McpConstants.ERROR_SERVER);
      }
    } finally {
      // Servlet threads are pooled: a leaked session key would attribute one client's calls to
      // another client's session — and a leaked tenant would attribute it to another company.
      McpUsageTelemetry.clearCurrentSessionKey();
      McpUsageTelemetry.clearCurrentTenant();
    }
  }

  /**
   * Write a JSON-RPC error answering the request in {@code body}.
   *
   * @return the rendered error, or {@code null} when it could not be built — a plain 500 was
   *         written instead
   */
  private String writeRpcError(HttpServletResponse response, String body, int code,
      String message) throws IOException {
    return writeRpcError(response, body, code, message, HttpServletResponse.SC_OK, null);
  }

  /**
   * Same as {@link #writeRpcError(HttpServletResponse, String, int, String)}, with the HTTP status
   * and the error's {@code data}: the modern era answers its protocol errors with {@code 400} /
   * {@code 404}, because a dual-era client reads the status and body to tell a modern server from a
   * legacy one (ETP-5640).
   *
   * @param data the error {@code data}, or {@code null} for none
   */
  private String writeRpcError(HttpServletResponse response, String body, int code,
      String message, int httpStatus, JSONObject data) throws IOException {
    try {
      Object rpcId = new JSONObject(body).opt("id");
      JSONObject error = buildJsonRpcError(rpcId, code, message);
      if (data != null) {
        error.getJSONObject("error").put("data", data);
      }
      String rendered = error.toString();
      response.setStatus(httpStatus);
      response.getWriter().write(rendered);
      return rendered;
    } catch (Exception ex) {
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      response.getWriter().write("{\"error\":\"Internal server error\"}");
      return null;
    }
  }

  /**
   * The era to serve this request under (ETP-5640). Everything is legacy while the
   * {@link com.etendoerp.go.featureflags.GoFeatureFlags#FLAG_MCP_MODERN_ERA_DISABLED} kill
   * switch is on, which is exactly how the servlet behaved before the modern era existed.
   */
  private static McpRequestEra.Classification classifyEra(HttpServletRequest request,
      String method, JSONObject params) {
    if (McpRequestEra.SERVER_DISCOVER.equals(method) || !McpRequestEra.modernEnabled()) {
      // server/discover is not answered yet: its probe must keep getting the legacy -32601 that
      // makes a dual-era client fall back to initialize, so the probe is not classified modern.
      return McpRequestEra.Classification.legacy();
    }
    return McpRequestEra.classify(method, params,
        new McpRequestEra.Headers(request.getHeader(McpProtocolVersion.HEADER),
            request.getHeader(McpRequestEra.HEADER_METHOD),
            request.getHeader(McpRequestEra.HEADER_NAME)),
        McpRequestEra.strict());
  }

  /**
   * Report what a served modern request left out, or refuse a malformed one.
   *
   * @return {@code false} when the request was refused and the {@code 400} already written
   */
  private boolean acceptModern(HttpServletResponse response, Object id, String method,
      JSONObject params, McpRequestEra.Classification era) throws IOException, JSONException {
    String client = clientNameFor(params);
    for (String issue : era.issues()) {
      log.warn("MCP modern request served without: {} (method={}, client={}) session={}", issue,
          method, client, McpUsageTelemetry.sessionForLog());
    }
    McpRequestEra.Refusal refusal = era.refusal();
    if (refusal == null) {
      return true;
    }
    log.warn("MCP modern request refused ({}): {} (method={}, client={}) session={}",
        refusal.code(), refusal.message(), method, client, McpUsageTelemetry.sessionForLog());
    JSONObject error = buildJsonRpcError(id, refusal.code(), refusal.message());
    if (refusal.data() != null) {
      error.getJSONObject("error").put("data", refusal.data());
    }
    response.setStatus(refusal.httpStatus());
    response.getWriter().write(error.toString());
    return false;
  }

  /**
   * Give a modern result the fields MCP 2026-07-28 requires of it (design §5.1, §5.2), in one place
   * so a new method cannot forget them: {@code resultType}, the server's identity in {@code _meta},
   * and cache hints on the catalog results. A field the handler already set is kept — so a result
   * that carries the full {@code serverInfo} or its own hints is not overwritten. Legacy results
   * never come here.
   *
   * @return {@code result}, decorated in place
   */
  static JSONObject decorateModern(String method, JSONObject result) throws JSONException {
    result.put("resultType", RESULT_TYPE_COMPLETE);
    JSONObject meta = result.optJSONObject("_meta");
    if (meta == null) {
      meta = new JSONObject();
      result.put("_meta", meta);
    }
    if (!meta.has(META_SERVER_INFO)) {
      // Name and version only: ~50 bytes on every response instead of the full Implementation.
      meta.put(META_SERVER_INFO,
          new JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION));
    }
    if (PER_CALLER_CACHEABLE.contains(method) && !result.has("ttlMs")) {
      result.put("ttlMs", CATALOG_TTL_MS);
      result.put("cacheScope", CACHE_SCOPE_PRIVATE);
    }
    return result;
  }

  /**
   * The calling client's name, for log lines about a request that has no tool row.
   *
   * <p>From the telemetry session when the client ran {@code initialize}; otherwise from
   * {@code params._meta["io.modelcontextprotocol/clientInfo"].name}, which 2026-07-28 requests
   * (such as a {@code server/discover} probe) carry; else {@code unknown}. Only the name is read —
   * never the request body.</p>
   *
   * @param params the request's {@code params}, may be {@code null}
   * @return the client name, never {@code null}
   */
  static String clientNameFor(JSONObject params) {
    String fromSession =
        McpUsageTelemetry.clientInfo(McpUsageTelemetry.currentSessionKey()).getName();
    if (StringUtils.isNotBlank(fromSession)) {
      return fromSession;
    }
    JSONObject meta = params != null ? params.optJSONObject("_meta") : null;
    JSONObject clientInfo = meta != null ? meta.optJSONObject(META_CLIENT_INFO) : null;
    String fromMeta = clientInfo != null ? StringUtils.trimToNull(clientInfo.optString("name"))
        : null;
    return fromMeta != null ? fromMeta : "unknown";
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

      // B3/D31: a etendo_feedback call IS a tool call, so it produces exactly ONE row — this one —
      // discriminated by row_type and carrying the report. It therefore inherits the session,
      // tenant, timestamp and client columns, and lands in the same sequence as the calls that
      // provoked it. The payload is stored only when the tool accepted the verdict; a rejected or
      // rate-limited call is an ordinary error row with nothing in Payload.
      boolean isFeedback = McpConstants.TOOL_NEO_FEEDBACK.equals(toolName);
      String payload = (isFeedback && !failed) ? McpFeedbackTool.payloadFor(arguments) : null;

      // ETP-5594: the tenant the call ran under (resolved from the role when the token carries the
      // "0" wildcard), falling back to the token's own values when no context was ever entered.
      McpUsageTelemetry.Tenant tenant = McpUsageTelemetry.currentTenant();
      String identityClient = identity != null ? identity.clientId : null;
      String identityOrg = identity != null ? identity.orgId : null;

      McpUsageRow row = McpUsageRow.builder()
          .clientId(tenant != null ? tenant.getClientId() : identityClient)
          .orgId(tenant != null ? tenant.getOrgId() : identityOrg)
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
          .build();
      McpUsageLogger.enqueue(row);
      if (payload != null) {
        // ETP-5639: Datadog sees an accepted report, and where to read it — counts only.
        McpFeedbackTool.logReceived(row);
      }
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
   * Handle GET /sws/mcp/.well-known/oauth-protected-resource (RFC 9728).
   *
   * <p>Any other GET answers {@code 405 Method Not Allowed}: a Streamable HTTP server that offers no
   * SSE stream MUST (MCP 2025-03-26 onwards). It used to answer an informational JSON, which a
   * client opening the optional GET stream could mistake for one (ETP-5639).</p>
   */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    setCorsHeaders(request, response);

    String pathInfo = request.getPathInfo();
    if ("/.well-known/oauth-protected-resource".equals(pathInfo)) {
      handleResourceMetadata(request, response);
      return;
    }

    response.setHeader("Allow", "POST, OPTIONS");
    // writeSimpleJsonError sets the JSON content type itself; setting it here too set it twice.
    ProtocolErrorAdapters.writeSimpleJsonError(response,
        HttpServletResponse.SC_METHOD_NOT_ALLOWED,
        "This MCP server offers no SSE stream; send JSON-RPC messages with POST");
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
    response.setContentType(CONTENT_TYPE_JSON);
    try {
      JSONObject meta = new JSONObject();
      meta.put("resource", mcpResourceUrl);
      meta.put("authorization_servers", new JSONArray().put(oauth2Url));
      meta.put("scopes_supported", new JSONArray(ApiScopes.ADVERTISED));
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
      // Only reached when there is no Bearer credential at all — the case that used to be an
      // unconditional 401. Every request that already carried a token keeps the exact path
      // below, so OAuth2 and legacy-JWT clients are untouched by ETP-4576.
      return cookieSessionIdentity(request, response);
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

  /**
   * Resolve the request against the backend-managed cookie session (ETP-4576).
   *
   * <p>Reached only when no {@code Authorization: Bearer} header is present, which before this
   * existed was an unconditional 401. The browser SPA holds no token under the cookie scheme —
   * {@code authHeaders()} deliberately sends nothing and lets the {@code __Host-} cookie travel
   * on its own — so that 401 rejected every in-app conversation while reads elsewhere in the app
   * kept working, which is what made it read like a deployment fault rather than an auth one.
   *
   * <p>{@link GoNeoAuth#decide} is the module's single arbiter of this, and the outcomes are kept
   * distinct because the client reacts to them: a failed CSRF/Origin proof is 403 (the session is
   * valid; re-authenticating would not help), an expired session is 401, and no session at all
   * falls back to the unchanged "missing Authorization header" 401 — including its
   * {@code WWW-Authenticate} discovery hint, so an MCP client's OAuth flow still starts here.
   */
  private AuthIdentity cookieSessionIdentity(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    GoSessionAuthResult sessionAuth = SESSION_AUTHENTICATOR.authenticate(request);
    switch (GoNeoAuth.decide(sessionAuth.getStatus(), GoLegacyBearer.isEnabled())) {
      case USE_SESSION:
        return sessionIdentity(request, response, sessionAuth.getRecord());
      case CSRF_REJECTED:
        log.warn("Forbidden MCP request: {}", sessionAuth.getRefusalMessage());
        sendJsonError(request, response, HttpServletResponse.SC_FORBIDDEN,
            sessionAuth.getRefusalMessage());
        return null;
      case SESSION_INVALID:
        log.warn("Unauthorized MCP request: invalid or expired session");
        sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
            "Invalid or expired session");
        return null;
      default:
        // NO_CREDENTIALS and USE_LEGACY_BEARER both land here: there is no cookie session AND no
        // Bearer header, so the answer is the pre-existing one, unchanged.
        sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
            "Missing Authorization: Bearer <token> header");
        return null;
    }
  }

  /**
   * Build the request identity from a resolved cookie session.
   *
   * <p>The scopes are the same set the validated legacy JWT path grants, and for the same
   * reason: both are interactive browser sessions, and RBAC still filters the tool catalog
   * and authorizes every operation by the user's role and window access. Granting less here
   * would make the same user see a different catalog depending only on which credential
   * scheme the backend happened to issue.
   *
   * <p>A session with no environment selected is rejected rather than defaulted — the tenant
   * scope is what every downstream query is filtered by, so guessing it is not an option.
   */
  private AuthIdentity sessionIdentity(HttpServletRequest request, HttpServletResponse response,
      GoSessionRecord session) throws IOException {
    if (StringUtils.isAnyBlank(session.getUserId(), session.getRoleId(), session.getCtxOrgId(),
        session.getCtxClientId())) {
      log.warn("Unauthorized MCP request: session has no environment selected");
      sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
          "Session has no environment selected");
      return null;
    }
    return new AuthIdentity(session.getUserId(), session.getRoleId(), session.getCtxClientId(),
        session.getCtxOrgId(), LEGACY_JWT_FALLBACK_SCOPES);
  }

  /**
   * Refuse an environment whose commercial access is cut (ETP-5642).
   *
   * <p>NEO, Copilot and the account endpoints answer 402 once a demo trial expires or a
   * subscription's grace elapses, but this servlet resolves its identity on its own and never
   * asked: an MCP client kept reading and writing the blocked tenant through every scheme. The
   * check runs once, after {@link #authenticate} and before any method dispatch, so it covers the
   * cookie session, OAuth2 and legacy JWT alike — and {@code initialize}/{@code tools/list} too:
   * a tool catalog for an environment that cannot be used is of no use to the client.
   *
   * <p>The answer is an HTTP 402 rather than an in-band tool error, with NEO's exact wording, so a
   * client stops instead of retrying and the SPA recognizes the same decision. The decision is
   * evaluated on the effective client — a credential carrying the System wildcard runs under its
   * role's client — so the check sees exactly the tenant the call would touch. A null decision is
   * a tenant that predates lifecycle metadata: the controlled legacy transition, not a refusal,
   * exactly as in {@code EnvironmentRequestAuthenticator}.
   *
   * @return true when the request was refused and the response already written
   */
  private boolean refuseCommerciallyBlocked(HttpServletRequest request,
      HttpServletResponse response, AuthIdentity identity) throws IOException {
    String clientId = McpSessionManager.resolveEffectiveClientId(identity.clientId,
        identity.roleId);
    EnvironmentAccessPolicy.Decision decision =
        lifecycleService.evaluateAccess(clientId, true, Instant.now());
    if (decision == null || decision == EnvironmentAccessPolicy.Decision.ALLOWED) {
      return false;
    }
    log.warn("Refused MCP request: environment {} is commercially blocked ({})", clientId,
        decision);
    sendJsonError(request, response, SC_PAYMENT_REQUIRED,
        EnvironmentRequestAuthenticator.MSG_ACCESS_PREFIX + decision.name());
    return true;
  }

  // ── JSON-RPC method dispatch ────────────────────────────────────────────

  /**
   * Route a JSON-RPC method to its handler.
   */
  private JSONObject dispatchMethod(AuthIdentity identity, String method, JSONObject params,
      HttpServletResponse response, boolean modern) throws Exception {
    if (modern && PING.equals(method)) {
      // Removed by MCP 2026-07-28: a modern client gets the modern "method not found".
      throw new McpMethodNotFoundException("Method not found: " + method);
    }
    switch (method) {
      case INITIALIZE:
        return handleInitialize(params, response);
      case "initialized":
      case "notifications/initialized":
        return null;
      case PING:
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
    // ETP-5639: answer the client's version when we speak it, else our latest (lifecycle rule).
    String negotiated = McpProtocolVersion.negotiate(
        params != null ? params.optString("protocolVersion", null) : null);
    try {
      response.setHeader(McpUsageTelemetry.HEADER_SESSION_ID,
          McpUsageTelemetry.openSession(params, negotiated));
    } catch (Exception e) {
      // Telemetry must never break the handshake.
      log.debug("Could not open an MCP telemetry session.", e);
    }

    JSONObject result = new JSONObject();
    result.put("protocolVersion", negotiated);

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
    serverInfo.put("title", SERVER_TITLE);
    serverInfo.put("websiteUrl", SERVER_WEBSITE_URL);
    serverInfo.put("description", SERVER_DESCRIPTION);
    JSONObject icon = new JSONObject();
    icon.put("src", SERVER_ICON_URL);
    icon.put("mimeType", SERVER_ICON_MIME_TYPE);
    icon.put("sizes", new JSONArray().put(SERVER_ICON_SIZES));
    serverInfo.put("icons", new JSONArray().put(icon));
    result.put("serverInfo", serverInfo);

    return result;
  }

  // ── Handler: tools/list ─────────────────────────────────────────────────

  /**
   * Language code of the user the MCP token belongs to, for localized tool titles.
   *
   * @return a code such as {@code es_ES}, or {@code null} when the context carries none
   */
  private static String currentLanguageCode() {
    OBContext context = OBContext.getOBContext();
    return context != null && context.getLanguage() != null
        ? context.getLanguage().getLanguage()
        : null;
  }

  private JSONObject handleToolsList(AuthIdentity identity) throws Exception {
    return McpSessionManager.executeInContext(
        identity.userId, identity.roleId, identity.clientId,
        identity.orgId, null, () -> {
          OBContext.setAdminMode(true);
          try {
            ToolRegistry registry = new ToolRegistry();
            Set<String> scopes = parseScopes(identity.scopes);
            List<McpToolDefinition> tools = registry.generateTools(scopes);

            String language = currentLanguageCode();
            JSONObject result = new JSONObject();
            JSONArray toolsArray = new JSONArray();
            for (McpToolDefinition tool : tools) {
              toolsArray.put(describeTool(tool, language));
            }
            result.put("tools", toolsArray);
            return result;
          } finally {
            OBContext.restorePreviousMode();
          }
        });
  }

  /**
   * One {@code tools/list} entry: name, localized title, description, input schema and the four
   * behaviour hints ({@link McpToolAnnotations}, ETP-5639).
   */
  JSONObject describeTool(McpToolDefinition tool, String language) throws JSONException {
    JSONObject toolJson = new JSONObject();
    toolJson.put("name", tool.getName());
    toolJson.put("title", McpToolTitles.resolve(tool, language));
    toolJson.put("description", tool.getDescription());
    toolJson.put("inputSchema", mapToJsonObject(tool.getInputSchema()));
    toolJson.put("annotations", McpToolAnnotations.of(tool.getName()));
    return toolJson;
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
            JSONObject resourceContent;
            try {
              resourceContent = provider.readResource(uri);
            } catch (org.openbravo.base.exception.OBSecurityException e) {
              // An unknown spec and a spec the role cannot see: one answer, so the error does not
              // reveal which specs exist (ETP-5640).
              throw new McpResourceNotFoundException(uri, e);
            }

            JSONObject result = new JSONObject();
            JSONArray contents = new JSONArray();
            JSONObject content = new JSONObject();
            content.put("uri", uri);
            content.put("mimeType", "application/json");
            // IMP-53: compact, like every tool result — the reader is an agent, not a person.
            content.put("text", resourceContent.toString());
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
