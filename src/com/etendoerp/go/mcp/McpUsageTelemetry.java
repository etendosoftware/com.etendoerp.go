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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Turns an MCP request/response pair into the <i>shape</i> of a call, for {@link McpUsageLogger}
 * (Track B1), and remembers what the {@code initialize} handshake said about the client.
 *
 * <h2>Shape, never content (D25)</h2>
 *
 * <p>Everything this class extracts is structural: the tool, the verb it resolved to, the spec and
 * entity addressed, the <i>names</i> of the fields the call carried, whether it failed and with
 * which canonical error code, and how big and how slow it was. {@link #fieldsTouched(JSONObject)}
 * reads JSON <b>keys</b>, never JSON values — which is the whole reason a create and an update are
 * legible here without any business data being stored. The one place a value could sneak in is a
 * {@code fields} array used as a projection list, and those entries are field names too.</p>
 *
 * <p>If a future change needs a value in order to diagnose something, the answer is a different
 * diagnostic, not a column: this table is read by people who are not entitled to the tenant's data,
 * and every value stored here is a value that has to be protected forever afterwards.</p>
 *
 * <h2>The session key</h2>
 *
 * <p>The design wants a sequence of calls to be readable as one task, but the servlet is stateless
 * and nothing previously minted a session identifier. {@link #openSession(JSONObject)} creates one
 * during {@code initialize} and {@link McpServlet} echoes it back in the {@code Mcp-Session-Id}
 * response header, which is where the Streamable HTTP transport says it belongs; a spec-conformant
 * client returns it on every subsequent request. Clients that ignore the header still work — their
 * rows simply carry a null session key and no client name, because {@code initialize} and
 * {@code tools/call} are separate HTTP requests with nothing else tying them together.</p>
 *
 * <p>The registry is bounded and evicts oldest-first. Losing an entry costs the client name on later
 * rows of a very old session; it cannot fail a call.</p>
 *
 * <h2>Modern clients: a derived session key (ETP-5640)</h2>
 *
 * <p>MCP 2026-07-28 has no {@code initialize} and no {@code Mcp-Session-Id}. A modern client names
 * itself on every request ({@code params._meta["io.modelcontextprotocol/clientInfo"]}, read by
 * {@link #clientInfoFromMeta}), and {@link #modernSession} derives the key: one per (user, token
 * client, role, client name), renewed after {@link #MODERN_SESSION_IDLE_MS} without a call, and
 * prefixed {@value #MODERN_SESSION_PREFIX} so the era is visible in the table and in the logs
 * without a column of its own. Two parallel conversations of one user with one agent merge, and a
 * multi-node deployment can split one task across nodes — accepted for telemetry; the rows still
 * carry user, client name and timestamps, from which SQL can regroup them cluster-wide.</p>
 */
final class McpUsageTelemetry {

  private static final Logger log = LogManager.getLogger(McpUsageTelemetry.class);

  /** Response/request header carrying the MCP session id (Streamable HTTP transport). */
  static final String HEADER_SESSION_ID = "Mcp-Session-Id";

  /** How many concurrent MCP sessions keep their handshake details. Oldest are evicted first. */
  private static final int MAX_TRACKED_SESSIONS = 1_000;

  /** Prefix that marks a derived (modern-era) session key. Legacy keys are bare UUIDs. */
  static final String MODERN_SESSION_PREFIX = "m-";

  /** Inactivity after which a modern client's next call starts a new derived session. */
  static final long MODERN_SESSION_IDLE_MS = 30L * 60L * 1000L;

  /** Bound on a {@code clientInfo} name or version kept from a request: it is self-reported. */
  private static final int MAX_CLIENT_FIELD = 100;

  /** Derived sessions by caller tuple. Bounded and oldest-first, like {@link #SESSIONS}. */
  private static final Map<String, DerivedSession> MODERN_SESSIONS = Collections.synchronizedMap(
      new LinkedHashMap<String, DerivedSession>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DerivedSession> eldest) {
          return size() > MAX_TRACKED_SESSIONS;
        }
      });

  private static final Map<String, ClientInfo> SESSIONS = Collections.synchronizedMap(
      new LinkedHashMap<String, ClientInfo>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ClientInfo> eldest) {
          return size() > MAX_TRACKED_SESSIONS;
        }
      });

  /**
   * The session key of the request being served on this thread, so a tool handler — which has no
   * access to the {@code HttpServletRequest} — can scope per-session behaviour such as
   * {@link McpFeedbackTool}'s rate limit. Set and cleared by {@link McpServlet#doPost}; the clear
   * is in a {@code finally} because a servlet thread is pooled and a leaked value would attribute
   * one client's calls to another client's session.
   */
  private static final ThreadLocal<String> CURRENT_SESSION = new ThreadLocal<>();

  /**
   * The tenant the tool call on this thread actually ran under (ETP-5594). An MCP token commonly
   * carries the wildcard client and org {@code "0"}; {@link McpSessionManager#executeInContext}
   * resolves them to the role's client and first transactional org before building the
   * {@code OBContext}, and binds the result here so the usage row records the same tenant instead
   * of {@code "0"}. Bound by the session manager, cleared by {@link McpServlet#doPost} in a
   * {@code finally} for the same pooled-thread reason as {@link #CURRENT_SESSION}.
   */
  private static final ThreadLocal<Tenant> CURRENT_TENANT = new ThreadLocal<>();

  /**
   * The client a modern request named in its own {@code _meta} (ETP-5640) — there is no handshake
   * to remember it from. Cleared by {@link McpServlet#doPost} in a {@code finally}, for the same
   * pooled-thread reason as {@link #CURRENT_SESSION}.
   */
  private static final ThreadLocal<ClientInfo> CURRENT_CLIENT = new ThreadLocal<>();

  private McpUsageTelemetry() {
  }

  /** Bind the effective client/org the current call runs under. Never throws. */
  static void setCurrentTenant(String clientId, String orgId) {
    CURRENT_TENANT.set(new Tenant(clientId, orgId));
  }

  /** Unbind the effective tenant. Must run in a {@code finally} — the servlet thread is pooled. */
  static void clearCurrentTenant() {
    CURRENT_TENANT.remove();
  }

  /**
   * @return the effective tenant bound on this thread, or null when the request never entered
   *     {@link McpSessionManager#executeInContext}
   */
  static Tenant currentTenant() {
    return CURRENT_TENANT.get();
  }

  /** Bind the client a modern request named in its {@code _meta}. */
  static void setCurrentClient(ClientInfo client) {
    CURRENT_CLIENT.set(client);
  }

  /** Unbind the per-request client. Must run in a {@code finally} — the thread is pooled. */
  static void clearCurrentClient() {
    CURRENT_CLIENT.remove();
  }

  /** @return the client bound for this request, or null when it is a legacy request */
  static ClientInfo currentClient() {
    return CURRENT_CLIENT.get();
  }

  /** Bind the session key for the duration of this request. */
  static void setCurrentSessionKey(String sessionKey) {
    CURRENT_SESSION.set(sessionKey);
  }

  /** Unbind the session key. Must run in a {@code finally} — the servlet thread is pooled. */
  static void clearCurrentSessionKey() {
    CURRENT_SESSION.remove();
  }

  /** @return the session key of the request on this thread, or null when there is none. */
  static String currentSessionKey() {
    return CURRENT_SESSION.get();
  }

  /** What a log line prints when the request carries no session key. */
  static final String NO_SESSION = "none";

  /**
   * The session key as MCP WARN/ERROR lines print it ({@code session=<key>}), so Datadog can put a
   * session's failures next to its feedback report (ETP-5639).
   *
   * @return the current session key, or {@value #NO_SESSION}
   */
  static String sessionForLog() {
    String key = CURRENT_SESSION.get();
    return key != null ? key : NO_SESSION;
  }

  // ── Session / client handshake ──────────────────────────────────────────

  /**
   * Mint a session key for an {@code initialize} call and remember the client that made it.
   *
   * @param params the {@code initialize} params, whose {@code clientInfo} carries name and version
   * @return the new session key, to be echoed in the {@value #HEADER_SESSION_ID} response header
   */
  static String openSession(JSONObject params) {
    return openSession(params, null);
  }

  /**
   * Same as {@link #openSession(JSONObject)}, also remembering the protocol version the handshake
   * negotiated, which later requests fall back to (ETP-5639).
   *
   * @param params          the {@code initialize} params
   * @param protocolVersion the negotiated version, may be {@code null}
   * @return the new session key
   */
  static String openSession(JSONObject params, String protocolVersion) {
    String sessionKey = UUID.randomUUID().toString();
    JSONObject clientInfo = params != null ? params.optJSONObject("clientInfo") : null;
    String name = clientInfo != null ? StringUtils.trimToNull(clientInfo.optString("name", null))
        : null;
    String version = clientInfo != null
        ? StringUtils.trimToNull(clientInfo.optString("version", null))
        : null;
    SESSIONS.put(sessionKey, new ClientInfo(name, version, protocolVersion));
    return sessionKey;
  }

  /**
   * The client a request names in {@code params._meta["io.modelcontextprotocol/clientInfo"]}
   * (MCP 2026-07-28). Self-reported, so only {@code name} and {@code version} are kept, trimmed and
   * bounded; anything malformed is ignored, never refused.
   *
   * @param params the request's {@code params}, may be {@code null}
   * @return the client, or {@link ClientInfo#UNKNOWN} when the request names none
   */
  static ClientInfo clientInfoFromMeta(JSONObject params) {
    JSONObject meta = params != null ? params.optJSONObject("_meta") : null;
    JSONObject info = meta != null ? meta.optJSONObject(McpServlet.META_CLIENT_INFO) : null;
    if (info == null) {
      return ClientInfo.UNKNOWN;
    }
    String name = StringUtils.abbreviate(StringUtils.trimToNull(info.optString("name", null)),
        MAX_CLIENT_FIELD);
    String version = StringUtils.abbreviate(
        StringUtils.trimToNull(info.optString("version", null)), MAX_CLIENT_FIELD);
    return name == null && version == null ? ClientInfo.UNKNOWN : new ClientInfo(name, version);
  }

  /**
   * The derived session a modern request belongs to (see the class Javadoc).
   *
   * @param userId     the caller's {@code AD_User_ID}
   * @param clientId   the token's {@code AD_Client_ID}
   * @param roleId     the caller's {@code AD_Role_ID}
   * @param clientName the client name the request declared, may be {@code null}
   * @param nowMs      the current time, in milliseconds
   * @return the session, and whether this call started it
   */
  static ModernSession modernSession(String userId, String clientId, String roleId,
      String clientName, long nowMs) {
    // Kept in memory only, never logged: the tuple holds identifiers of a user.
    String caller = String.join("\0", StringUtils.defaultString(userId),
        StringUtils.defaultString(clientId), StringUtils.defaultString(roleId),
        StringUtils.defaultString(clientName));
    synchronized (MODERN_SESSIONS) {
      DerivedSession known = MODERN_SESSIONS.get(caller);
      if (known != null && nowMs - known.lastSeenMs <= MODERN_SESSION_IDLE_MS) {
        known.lastSeenMs = nowMs;
        return new ModernSession(known.key, false);
      }
      DerivedSession fresh =
          new DerivedSession(MODERN_SESSION_PREFIX + UUID.randomUUID(), nowMs);
      // Re-insert so a renewed caller moves to the young end of the eviction order.
      MODERN_SESSIONS.remove(caller);
      MODERN_SESSIONS.put(caller, fresh);
      return new ModernSession(fresh.key, true);
    }
  }

  /**
   * A derived session as {@link #modernSession} answers it.
   *
   * @param key     the session key, {@value #MODERN_SESSION_PREFIX}-prefixed
   * @param started whether this call opened it
   */
  record ModernSession(String key, boolean started) {
  }

  /** A derived session's key and last activity. Guarded by the MODERN_SESSIONS monitor. */
  private static final class DerivedSession {
    private final String key;
    private long lastSeenMs;

    DerivedSession(String key, long lastSeenMs) {
      this.key = key;
      this.lastSeenMs = lastSeenMs;
    }
  }

  /** @return what {@code initialize} reported for this session, or an empty record if unknown. */
  static ClientInfo clientInfo(String sessionKey) {
    if (StringUtils.isBlank(sessionKey)) {
      return ClientInfo.UNKNOWN;
    }
    ClientInfo known = SESSIONS.get(sessionKey);
    return known != null ? known : ClientInfo.UNKNOWN;
  }

  // ── Call shape ──────────────────────────────────────────────────────────

  /**
   * The CRUD/action verb a tool resolves to. Derived from the tool name alone, so it stays correct
   * without the router having to report anything back.
   */
  static String verbFor(String toolName) {
    if (StringUtils.isBlank(toolName)) {
      return null;
    }
    switch (toolName) {
      case "etendo_list":
        return "list";
      case "etendo_get":
        return "get";
      case "etendo_create":
        return "create";
      case "etendo_update":
        return "update";
      case "etendo_delete":
        return "delete";
      case "etendo_batch":
        return "batch";
      case "etendo_action":
        return "action";
      case "etendo_schema":
        return "schema";
      case "etendo_defaults":
        return "defaults";
      case "etendo_selectors":
        return "selectors";
      case "etendo_discover":
        return "discover";
      case "etendo_vector_search":
        return "search";
      case "docs":
        return "docs";
      default:
        return toolName.startsWith("generate_") ? "report" : "process";
    }
  }

  /**
   * The spec (and included entity, when the call names one) the tool addressed, as
   * {@code spec} or {@code spec/entity}. Both are configuration names, not data.
   */
  static String targetEntityFor(JSONObject arguments) {
    if (arguments == null) {
      return null;
    }
    String spec = StringUtils.trimToNull(arguments.optString(McpConstants.PARAM_SPEC, null));
    String entity = StringUtils.trimToNull(arguments.optString(McpConstants.PARAM_ENTITY, null));
    if (spec == null) {
      return entity;
    }
    return entity == null ? spec : spec + "/" + entity;
  }

  /**
   * The NAMES of the fields the call carried, comma-separated and sorted so the same call always
   * produces the same string.
   *
   * <p>Three shapes are read, and all three yield names: {@code fields} as an object (a write
   * payload — its <b>keys</b>), {@code fields} as an array (a projection list — its entries are
   * field names), and {@code parameters} as an object (a process or report call — its keys). Values
   * are never read from any of them.</p>
   *
   * @return the sorted names, or null when the call carried none
   */
  static String fieldsTouched(JSONObject arguments) {
    if (arguments == null) {
      return null;
    }
    List<String> names = new ArrayList<>();
    collectKeys(arguments.optJSONObject(McpConstants.PARAM_FIELDS), names);
    collectKeys(arguments.optJSONObject(McpConstants.PARAM_PARAMETERS), names);
    collectEntries(arguments.optJSONArray(McpConstants.PARAM_FIELDS), names);
    if (names.isEmpty()) {
      return null;
    }
    Collections.sort(names);
    return String.join(",", names);
  }

  /** Reads KEYS only — see the class javadoc. */
  private static void collectKeys(JSONObject source, List<String> into) {
    if (source == null) {
      return;
    }
    for (Iterator<?> it = source.keys(); it.hasNext();) {
      String key = StringUtils.trimToNull(String.valueOf(it.next()));
      if (key != null && !into.contains(key)) {
        into.add(key);
      }
    }
  }

  /** A {@code fields} array is a projection list: its entries are field names, not values. */
  private static void collectEntries(JSONArray source, List<String> into) {
    if (source == null) {
      return;
    }
    for (int i = 0; i < source.length(); i++) {
      String name = StringUtils.trimToNull(source.optString(i, null));
      if (name != null && !into.contains(name)) {
        into.add(name);
      }
    }
  }

  /**
   * Whether the tool result is an error. The router does not throw for a rejected call — it answers
   * with an {@code isError} envelope — so the outcome has to be read off the result, not caught.
   */
  static boolean isError(JSONObject result) {
    return result != null && result.optBoolean("isError", false);
  }

  /**
   * The canonical error code from an error envelope ({@code error}, e.g. {@code validation_error},
   * {@code not_found}, {@code server_error}).
   *
   * <p>Only the code is read. The envelope's {@code detail} is deliberately left alone: it is prose
   * about this particular call and can quote what the agent sent, which is exactly the content this
   * table must not hold.</p>
   *
   * @return the code, or null when the envelope is not JSON (the pre-IMP-5 prose fallback)
   */
  static String errorCodeFrom(JSONObject result) {
    try {
      JSONArray content = result != null ? result.optJSONArray("content") : null;
      if (content == null || content.length() == 0) {
        return null;
      }
      JSONObject first = content.optJSONObject(0);
      String text = first != null ? first.optString("text", null) : null;
      if (StringUtils.isBlank(text) || !StringUtils.startsWith(StringUtils.trim(text), "{")) {
        return null;
      }
      return StringUtils.trimToNull(
          new JSONObject(text).optString(McpConstants.KEY_ERROR, null));
    } catch (Exception e) {
      log.debug("Could not read the error code off an MCP tool result.", e);
      return null;
    }
  }

  /** The client and org a tool call ran under, as resolved by {@link McpSessionManager}. */
  static final class Tenant {

    private final String clientId;
    private final String orgId;

    Tenant(String clientId, String orgId) {
      this.clientId = clientId;
      this.orgId = orgId;
    }

    String getClientId() {
      return clientId;
    }

    String getOrgId() {
      return orgId;
    }
  }

  /** What the {@code initialize} handshake reported about the calling agent, and negotiated. */
  static final class ClientInfo {

    static final ClientInfo UNKNOWN = new ClientInfo(null, null);

    private final String name;
    private final String version;
    private final String protocolVersion;

    ClientInfo(String name, String version) {
      this(name, version, null);
    }

    ClientInfo(String name, String version, String protocolVersion) {
      this.name = name;
      this.version = version;
      this.protocolVersion = protocolVersion;
    }

    /** @return the protocol version {@code initialize} negotiated, or {@code null} */
    String getProtocolVersion() {
      return protocolVersion;
    }

    String getName() {
      return name;
    }

    String getVersion() {
      return version;
    }
  }
}
