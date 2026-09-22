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
import com.etendoerp.go.schemaforge.NeoExtensionChannel;
import com.etendoerp.go.schemaforge.NeoExtensionDispatcher;
import com.etendoerp.go.schemaforge.NeoExtensionOutcome;
import com.etendoerp.go.schemaforge.NeoExtensionRequest;
import com.etendoerp.go.schemaforge.NeoExtensionResult;
import com.etendoerp.go.schemaforge.NeoExtensionSurface;
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

  private static final String HTTP_METHOD_GET = "GET";

  private McpHookExecutor() {
  }

  /**
   * What a READ-surface dispatch leaves the MCP read pipeline to do next (ETP-5415, T3).
   *
   * <p>A read cannot borrow the write sites' "a non-null hook response is the answer, return it"
   * contract: an MCP read still has to project, flatten and (for {@code neo_get}) attach the record
   * URL, and returning a handler's {@link NeoResponse} body verbatim would hand the agent core's
   * wrapped envelope instead of the flat shape every other read produces. So a successful hook
   * response <b>replaces the body the pipeline carries on with</b>, and only a {@code >= 400} one
   * short-circuits — through the same error funnel as every other MCP hook result.</p>
   *
   * @param body     the response body the remaining read stages must operate on
   * @param mcpError a ready-to-return MCP error result, or {@code null} when there is none
   */
  record ReadHookOutcome(JSONObject body, JSONObject mcpError) {
  }

  /**
   * Run the entity customization's READ post-phase over an MCP read result (ETP-5415, T3).
   *
   * <p>Until this existed, {@code neo_list} and {@code neo_get} were the only NEO surfaces that
   * reached no customization at all: {@code OrderLineHandler.afterHandle} injects {@code
   * productCode} into every sales-order line a REST read returns, and an MCP read of the same rows
   * simply did not have it — with nothing in the response, and no line in the log, to say a hook
   * had been skipped.</p>
   *
   * <p><b>Where this sits in the pipeline is deliberate.</b> The caller invokes it after
   * {@code NeoFieldFilter#filterGetResponse} and before {@code McpQuerySupport.applyProjection},
   * which is exactly the REST read's own ordering: {@code NeoCrudHandler.handleDefault} filters the
   * body and {@code NeoServletSupport.handleWithHooks} then runs {@code afterHandle} over the
   * filtered result. Projection has no REST counterpart — it is the caller's explicit
   * {@code fields:[…]} whitelist — and running the hook before it keeps that whitelist the last
   * word on what the agent asked for, rather than letting a customization silently re-widen a
   * response the caller narrowed. It also runs before {@code flattenCoreResponse}, because a
   * handler reads its rows through {@code NeoHandlerUtils.extractGetDataArray}, which requires the
   * {@code response.data} wrapper core produced and an HTTP method of {@code GET}.</p>
   *
   * <p><b>The pre phase is not routed here.</b> A REST read runs {@code handle} as well, and a
   * customization that fully serves a read from its pre-hook therefore still behaves differently
   * over MCP. Invoking {@code handle} on a read would let a customization short-circuit or rewrite
   * an MCP query, which is a materially larger change than injecting fields into a result; it is
   * left for a later, deliberate step.</p>
   *
   * @param specName     the spec being read
   * @param entityName   the entity being read
   * @param recordId     the record for {@code neo_get}, {@code null} for {@code neo_list} — the
   *                     value customizations branch on to tell a single-record read from a list
   * @param adTab        the entity's AD tab
   * @param sfEntity     the entity configuration, whose {@code Java_Qualifier} is resolved
   * @param responseJson the filtered, still-wrapped body produced by the generic read
   * @return the body to carry on with, or the MCP error to return
   * @throws JSONException when a {@code >= 400} hook response cannot be converted
   */
  static ReadHookOutcome runReadHook(String specName, String entityName, String recordId,
      Tab adTab, SFEntity sfEntity, JSONObject responseJson) throws JSONException {
    NeoContext ctx = buildHookContext(specName, entityName, HTTP_METHOD_GET, recordId, null,
        adTab, sfEntity);
    NeoExtensionRequest request = NeoExtensionRequest.builder()
        .qualifier(sfEntity.getJavaQualifier())
        .specName(specName)
        .entityName(entityName)
        .surface(NeoExtensionSurface.READ)
        .channel(NeoExtensionChannel.MCP)
        .context(ctx)
        .build();
    // Resolution goes through the MCP resolver, as every other MCP hook site does — NOT the one
    // the REST paths use. The two are not equivalent (see NeoExtensionDispatcher); keeping the
    // channel's own resolver is what makes this step a routing change and not a resolution change.
    NeoExtensionResult result = NeoExtensionDispatcher.dispatch(request
        .post(resolveEntityHandler(sfEntity))
        .withPreviousResult(NeoResponse.ok(responseJson)));
    NeoResponse response = result.response();
    if (response == null) {
      // The customization declined, or mutated the body in place — which is what an injection like
      // OrderLineHandler's productCode does — so the body the caller already holds is the answer.
      return new ReadHookOutcome(responseJson, null);
    }
    if (response.getHttpStatus() >= 400) {
      return new ReadHookOutcome(responseJson, neoResponseToMcpResult(response));
    }
    return new ReadHookOutcome(
        response.getBody() != null ? response.getBody() : responseJson, null);
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
        .endpointType(NeoEndpointType.CRUD)
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
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(actionName)
        .build();
  }

  /**
   * Run the entity hook's pre-phase. Returns an MCP result to short-circuit to
   * write (a validation error, or a handler that fully handled the request such
   * as a soft-archive on DELETE), or {@code null} to proceed with generic
   * persistence. The handler may have mutated the request body in place.
   */
  static JSONObject runPreHook(NeoHandler handler, NeoContext ctx) throws JSONException {
    if (handler == null) {
      return null;
    }
    NeoResponse pre = handler.handle(ctx);
    return pre != null ? neoResponseToMcpResult(pre) : null;
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
   * Run the entity hook's post-phase through {@link NeoExtensionDispatcher} (ETP-5415).
   *
   * <p>Same contract as {@link #runPostHook(NeoHandler, NeoContext, JSONObject)}, and deliberately
   * the same body: the dispatcher only resolves nothing and invokes {@code afterHandle} on the
   * instance the pre phase already resolved, so the MCP-specific parts — the
   * {@code NeoResponse.ok(responseJson)} wrapper, and above all the ETP-5262 audit-token refresh,
   * which patches the <b>response body in place</b> when the handler declines and the response
   * object when it does not — stay here. The REST dispatcher refreshes differently (see
   * {@code NeoServletSupport.runPostHook}); that difference is pre-existing and is preserved on
   * purpose in this step.</p>
   *
   * @param request      the post-phase request, carrying the instance the pre phase resolved; a
   *                     {@code null} customization yields {@code null} with no refresh, exactly as
   *                     the {@code handler == null} guard of the original overload does
   * @param responseJson the response produced by generic persistence
   * @return an MCP result when the handler replaced the response, or {@code null} to keep it
   * @throws JSONException when the handler's response cannot be converted
   */
  static JSONObject runPostHook(NeoExtensionRequest request, JSONObject responseJson)
      throws JSONException {
    NeoExtensionResult result = NeoExtensionDispatcher.dispatch(
        request.withPreviousResult(NeoResponse.ok(responseJson)));
    if (result.outcome() == NeoExtensionOutcome.NO_CUSTOMIZATION) {
      return null;
    }
    NeoContext ctx = request.context();
    NeoResponse post = result.response();
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
