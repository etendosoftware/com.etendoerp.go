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

import java.util.Map;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoAuditTokenRefresh;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * Executes NeoHandler pre/post hooks for MCP write operations, providing the same
 * hook parity with the REST CRUD path ({@code NeoServlet}) so validation and field
 * derivation run identically regardless of whether the write originates from the
 * REST API or from an AI agent via MCP.
 */
final class McpHookExecutor {

  /** {@code 202 Accepted}: the handler queued the request; for DELETE, not yet deleted. */
  private static final int HTTP_ACCEPTED = 202;

  private McpHookExecutor() {
  }

  /**
   * Resolve the {@link NeoHandler} registered for the entity's Java_Qualifier.
   *
   * <p>Delegates to {@link NeoHandlerLookup}, where the CDI lookup was moved (ETP-4793 / IMP-19)
   * so {@code schemaforge} code — the report-callability gate — can ask a handler what it
   * supports without depending on this package.</p>
   *
   * @return the matching handler, or {@code null} when the entity declares no
   *         qualifier or no matching {@code @Named} handler is deployed
   */
  static NeoHandler resolveEntityHandler(SFEntity sfEntity) {
    return NeoHandlerLookup.byQualifier(sfEntity.getJavaQualifier());
  }

  /**
   * Build the {@link NeoContext} an MCP write passes to its entity hook.
   * The body is the live DAL-property map the handler may mutate (e.g. inject
   * derived FK values) before the generic service persists it.
   */
  static NeoContext buildHookContext(String specName, String entityName, String method,
      String recordId, JSONObject body, Tab adTab, SFEntity sfEntity) {
    return NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .mcpOrigin(true)
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /**
   * Build the {@link NeoContext} an MCP read passes to its entity hook.
   *
   * <p>ETP-5405 — mirrors what the REST dispatcher hands a handler on
   * {@code GET /sws/neo/{spec}/{entity}}: {@code endpointType=CRUD}, {@code httpMethod=GET}, no
   * request body, and the query string as a flat map. A read handler reads its input from
   * {@link NeoContext#getQueryParams()} — {@code NotPostedDocumentsHandler.handleCrud} branches on
   * {@code _mode} and then passes the whole map to its datasource — so the map must never be
   * {@code null}, and the MCP arguments are flattened into it under the names the handler already
   * expects from the SPA.</p>
   *
   * @param adTab the entity's AD tab, {@code null} for the tab-less entities this path exists for
   */
  static NeoContext buildReadHookContext(String specName, String entityName, String recordId,
      Tab adTab, SFEntity sfEntity, Map<String, String> queryParams) {
    return NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod("GET")
        .recordId(recordId)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .mcpOrigin(true)
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(queryParams)
        .build();
  }

  /**
   * Build the {@link NeoContext} for the DEFAULTS endpoint hook.
   * Unlike the CRUD overload, this sets {@code endpointType=DEFAULTS} and carries
   * the query-param map (e.g. {@code assetId}) so handlers like
   * {@code AmortizationHeaderHandler} can read them via {@link NeoContext#getQueryParams()}.
   */
  static NeoContext buildDefaultsHookContext(String specName, String entityName,
      Tab adTab, SFEntity sfEntity, Map<String, String> queryParams) {
    return NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod("GET")
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .mcpOrigin(true)
        .endpointType(NeoEndpointType.DEFAULTS)
        .queryParams(queryParams)
        .build();
  }

  /**
   * Build the {@link NeoContext} for the ACTION endpoint hook (ETP-4285).
   *
   * <p>Mirrors what the REST path passes on
   * {@code POST /sws/neo/{spec}/{entity}/{id}/action/{name}}
   * ({@code NeoSubEndpointDispatcher.handleHookedSubEndpoint}), so a button action fired
   * through MCP reaches the entity's handler with the same shape the UI produces.
   * {@code endpointType=ACTION} plus {@code fieldName=actionName} are the two values handlers
   * branch on — see {@code AbstractOrderHeaderHandler.isActionDocumentActionComplete}, which
   * then reads the action value from the request body ({@code fieldValues.documentAction},
   * root {@code docAction}, or root {@code documentAction}).</p>
   *
   * @param specName   the spec that owns the entity
   * @param entityName the entity that owns the button field
   * @param recordId   the record the action targets
   * @param actionName the button field name as passed to {@code neo_action}, e.g.
   *                   {@code documentAction}
   * @param params     the MCP {@code parameters} object, used as the request body; must not be
   *                   {@code null} so a handler can read and mutate it
   * @param adTab      the entity's AD tab, may be {@code null} for tab-less entities
   * @param sfEntity   the entity configuration
   * @return a NeoContext with {@code endpointType=ACTION} and {@code httpMethod=POST}
   */
  static NeoContext buildActionHookContext(String specName, String entityName, String recordId,
      String actionName, JSONObject params, Tab adTab, SFEntity sfEntity) {
    return NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod("POST")
        .recordId(recordId)
        .requestBody(params)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .mcpOrigin(true)
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(actionName)
        .build();
  }

  /**
   * Run the entity hook's pre-phase. Returns an MCP result to short-circuit to
   * write (a validation error, or a handler that fully handled the request), or
   * {@code null} to proceed with generic persistence. The handler may have mutated
   * the request body in place. DELETE uses {@link #runDeletePreHook} instead.
   */
  static JSONObject runPreHook(NeoHandler handler, NeoContext ctx) throws JSONException {
    if (handler == null) {
      return null;
    }
    NeoResponse pre = handler.handle(ctx);
    return pre != null ? neoResponseToMcpResult(pre) : null;
  }

  /**
   * DELETE-specific variant of {@link #runPreHook}. Only a completed-success 2xx with no body (or
   * an empty one) — a handler that resolved the delete itself, typically with
   * {@code 204 No Content} — answers the same {@code {"deleted": true, "id"}} confirmation as the
   * generic delete path. Everything else goes through {@link #neoResponseToMcpResult} unchanged:
   * errors (status &ge; 400), 2xx responses carrying a real body, 1xx/3xx codes, and
   * {@code 202 Accepted}, which means the delete was queued and has not happened yet, so it is not
   * a confirmation.
   *
   * <p>ETP-5474: the 204 used to be rendered as {@code {}}, so {@code neo_delete} on a financial
   * account that had just been removed read to the agent as a failed delete. Handled here rather
   * than per handler so any future handler resolving DELETE with 204 is covered. Not folded into
   * {@link #runPreHook}: on the process/report/widget paths a 204 does not mean "deleted".</p>
   *
   * @param handler  the entity handler, may be {@code null}
   * @param ctx      the DELETE hook context
   * @param recordId the id of the record being deleted, echoed in the confirmation
   * @return an MCP result to short-circuit with, or {@code null} to proceed with generic removal
   */
  static JSONObject runDeletePreHook(NeoHandler handler, NeoContext ctx, String recordId)
      throws JSONException {
    if (handler == null) {
      return null;
    }
    NeoResponse pre = handler.handle(ctx);
    if (pre == null) {
      return null;
    }
    int status = pre.getHttpStatus();
    JSONObject body = pre.getBody();
    if (status >= 200 && status < 300 && status != HTTP_ACCEPTED
        && (body == null || body.length() == 0)) {
      return McpToolRouter.deleteConfirmation(recordId);
    }
    return neoResponseToMcpResult(pre);
  }

  /**
   * Run the entity hook's post-phase after a successful persist. Returns an MCP
   * result when the handler replaced the response, or {@code null} to keep the
   * default response.
   */
  static JSONObject runPostHook(NeoHandler handler, NeoContext ctx, JSONObject responseJson)
      throws JSONException {
    if (handler == null) {
      return null;
    }
    ctx.setPreviousResult(NeoResponse.ok(responseJson));
    NeoResponse post = handler.afterHandle(ctx);
    // ETP-5262: the post-hook may have written to the record whose response was serialised before
    // it ran, which leaves the `updated` concurrency token in that response one version behind the
    // row. Refreshed on both outcomes for the same reason the REST dispatcher does it in one place
    // (see NeoAuditTokenRefresh): a handler that returns a replacement response almost always
    // builds it from `previousResult`, so the stale token travels into it. The declining case
    // patches `responseJson` in place, which is the object the caller goes on to flatten and hand
    // to the agent.
    if (post != null) {
      NeoAuditTokenRefresh.refreshInResponse(ctx, post);
      return neoResponseToMcpResult(post);
    }
    NeoAuditTokenRefresh.refreshInBody(ctx, responseJson);
    return null;
  }

  /**
   * Convert a {@link NeoResponse} to MCP result format.
   * Responses with status &ge; 400 set {@code isError: true}.
   *
   * <p>This is the <b>fourth error funnel</b> (ETP-4793 / IMP-5 clause (iv)). It used to forward the
   * handler's body verbatim, which is how {@code generate_aging_receivable({})} answered the nested
   * pre-IMP-5 {@code {"error":{"message":…,"status":422}}} with nothing an agent could branch on —
   * found while verifying IMP-19, after IMP-17 had closed the three funnels it enumerated and this
   * was in none of them. Every MCP path that returns a handler's or a process's {@code NeoResponse}
   * comes through here — report generation, {@code neo_process}, the widget/amortization paths and
   * all four entity pre/post hooks — so normalizing once covers all of them. The normalization is
   * additive and idempotent; see {@link McpToolRouterSupport#toMcpHandlerError} for why it does not
   * live in {@code NeoResponse.error} itself.</p>
   */
  static JSONObject neoResponseToMcpResult(NeoResponse neoResponse) throws JSONException {
    // ETP-5306: both branches hand over the JSONObject, so the reserved-key sanitisation in the
    // content wrappers covers every NeoResponse-carrying path funnelled through here — a handler's
    // body is produced by the same core serialiser that puts `$ref` on a row.
    if (neoResponse.getHttpStatus() >= 400) {
      return McpToolRouter.wrapAsErrorContent(McpToolRouterSupport
          .toMcpHandlerError(neoResponse.getBody(), neoResponse.getHttpStatus()));
    }
    return McpToolRouter.wrapAsTextContent(neoResponse.getBody());
  }
}
