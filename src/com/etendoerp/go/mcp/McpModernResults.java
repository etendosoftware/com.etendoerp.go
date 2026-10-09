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

import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * How the MCP server describes itself, and the fields a modern (2026-07-28) result must carry
 * (ETP-5640, design {@code docs/plans/2026-10-09-etp-5640-mcp-dual-era-design.md} §3.1, §5.1,
 * §5.2).
 *
 * <p>{@code initialize} and {@code server/discover} build their capabilities and server identity
 * here, so the two eras cannot drift. Structure only: nothing in this class depends on a spec, an
 * entity, the caller or the database.</p>
 */
final class McpModernResults {

  static final String SERVER_NAME = "etendo-mcp";
  static final String SERVER_VERSION = "1.0.0";
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

  /** The params/result key under which protocol metadata travels. */
  static final String META = "_meta";
  /** {@code _meta} key under which a modern result names the server. */
  static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
  /** {@code Result.resultType} of every final modern result (MCP 2026-07-28). */
  private static final String RESULT_TYPE_COMPLETE = "complete";
  private static final String TTL_MS = "ttlMs";
  private static final String CACHE_SCOPE = "cacheScope";
  /**
   * Freshness hint for the per-caller catalog results (design §5.2): short enough that a config
   * push or a role change reaches a modern client within minutes.
   */
  static final long CATALOG_TTL_MS = 300_000L;
  /** Freshness hint of {@code server/discover}: its answer changes only with a deployment. */
  static final long DISCOVER_TTL_MS = 3_600_000L;
  /** {@code cacheScope} of a result that is the same for every caller. */
  static final String CACHE_SCOPE_PUBLIC = "public";
  /** {@code cacheScope} of a result that depends on the caller's role, scopes or language. */
  static final String CACHE_SCOPE_PRIVATE = "private";
  /** Modern methods whose result is a {@code CacheableResult} we compute per caller. */
  private static final Set<String> PER_CALLER_CACHEABLE =
      Set.of("tools/list", "resources/list", "resources/read");

  private McpModernResults() {
  }

  /**
   * Answer {@code server/discover}: every version served, the same capabilities and server identity
   * as {@code initialize}, and cache hints. The answer is the same for every caller, hence
   * {@code public}. Answering it is what moves a dual-era client to the modern era.
   */
  static JSONObject discover() throws JSONException {
    JSONObject result = new JSONObject();
    result.put("supportedVersions", new JSONArray(McpProtocolVersion.ALL_SUPPORTED));
    result.put("capabilities", capabilities());
    result.put(META, new JSONObject().put(META_SERVER_INFO, serverInfo()));
    result.put(TTL_MS, DISCOVER_TTL_MS);
    result.put(CACHE_SCOPE, CACHE_SCOPE_PUBLIC);
    return result;
  }

  /**
   * What the server offers, as {@code initialize} and {@code server/discover} both answer it.
   * {@code listChanged} is false: the server never pushes a list change, so a client's freshness
   * signal is the result's TTL (modern) or the session (legacy).
   */
  static JSONObject capabilities() throws JSONException {
    JSONObject capabilities = new JSONObject();
    capabilities.put("tools", new JSONObject().put("listChanged", false));
    capabilities.put("resources", new JSONObject().put("listChanged", false));
    return capabilities;
  }

  /** The server's full {@code Implementation}, as {@code initialize} and discovery answer it. */
  static JSONObject serverInfo() throws JSONException {
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
    return serverInfo;
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
  static JSONObject decorate(String method, JSONObject result) throws JSONException {
    result.put("resultType", RESULT_TYPE_COMPLETE);
    JSONObject meta = result.optJSONObject(META);
    if (meta == null) {
      meta = new JSONObject();
      result.put(META, meta);
    }
    if (!meta.has(META_SERVER_INFO)) {
      // Name and version only: ~50 bytes on every response instead of the full Implementation.
      meta.put(META_SERVER_INFO,
          new JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION));
    }
    if (PER_CALLER_CACHEABLE.contains(method) && !result.has(TTL_MS)) {
      result.put(TTL_MS, CATALOG_TTL_MS);
      result.put(CACHE_SCOPE, CACHE_SCOPE_PRIVATE);
    }
    return result;
  }
}
