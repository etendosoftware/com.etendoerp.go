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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.service.json.JsonConstants;
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

  /** {@code 202 Accepted}: the handler queued the request; for DELETE, not yet deleted. */
  private static final int HTTP_ACCEPTED = 202;
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
   * Run the read's <b>pre</b> phase, before the generic query exists (ETP-5415, D13).
   *
   * <p>This is the provider half of the read surface: a customization whose {@code handle} returns
   * a response on a GET is serving the whole read itself, and the generic path must not run. REST
   * has always worked this way — {@code NeoHookDispatcher.executeHookChain} invokes the pre-hook
   * before anything touches the tab — and MCP did not, which had one loud consequence and one
   * quiet one.</p>
   *
   * <p><b>The loud one:</b> the 16 entities with no {@code AD_Tab}
   * ({@code ETGO_SF_ENTITY.ad_tab_id IS NULL}, every one carrying a working {@code java_qualifier})
   * answer over REST and returned HTTP 500 over MCP — {@code getAdTabOrThrow} fired before any
   * hook could decline the generic path. The dashboard widgets, {@code contacts/bp-stats}, the
   * three reports and {@code not-posted-documents} all render in the SPA and were unreachable to
   * an agent, while {@code neo_discover} advertised them and the {@code not_found} hint steered
   * callers straight into them.</p>
   *
   * <p><b>The quiet one, which is the reason this is not just a bug fix:</b> the asymmetry applied
   * to <em>every</em> entity, not only the tab-less ones. A customization that serves a read from
   * its pre-hook worked in the app and silently did nothing for an agent — no error, no trace, the
   * generic answer instead of the intended one. That is the failure mode this ticket exists to
   * remove, and it was the last surface still carrying it.</p>
   *
   * <p>The tab is read leniently here ({@code sfEntity.getADTab()}, which may be {@code null})
   * rather than through {@code getAdTabOrThrow}, precisely so a tab-less entity reaches its
   * customization. That matches what REST passes: {@code NeoHookDispatcher.buildHookContext} also
   * takes the tab straight off the entity and tolerates {@code null}.</p>
   *
   * <p><b>Query params.</b> A provider reads its inputs from {@code getQueryParams()} — the REST
   * read fills that from the query string ({@code NeoServlet.extractQueryParams}). MCP has no
   * query string, so {@link #buildReadProviderParams} builds the map from the tool's own
   * arguments. The {@code filters} pass-through is what gives the agent a way to supply a named
   * input at all: {@code bp-stats} needs a {@code businessPartnerId} that no MCP argument
   * otherwise carries, so
   * {@code neo_list(contacts, bp-stats, filters:{businessPartnerId:"…"})} is its call shape.</p>
   *
   * <p><b>Relation to ETP-5405.</b> That ticket fixed the same 500 with
   * {@code McpTablessReadDispatcher}, which ran this phase only when {@code getADTab() == null}.
   * This one runs it always, which is what closes D13: over REST the pre-hook runs on every read,
   * so restricting it to tab-less entities would have left a customization that serves a read of
   * a tabbed entity working in the app and silently inert for an agent. ETP-5405's call position
   * and its parameter builder are kept verbatim; only the gate and the resolver changed — the
   * latter so an {@code @NeoExtension} class is seen and the dispatch is traced.</p>
   *
   * @param specName   the spec being read
   * @param entityName the entity being read
   * @param recordId   the record for {@code neo_get}, {@code null} for {@code neo_list} — the
   *                   value customizations branch on to tell a single-record read from a list
   * @param sfEntity   the entity configuration, whose {@code Java_Qualifier} is resolved
   * @param queryParams the provider's inputs, built by {@link #buildReadProviderParams}; never
   *                   {@code null}
   * @return the MCP result when the customization served the read, or {@code null} to carry on
   *         with the generic path
   * @throws JSONException when the customization's response cannot be converted
   */
  static JSONObject runReadProvider(String specName, String entityName, String recordId,
      SFEntity sfEntity, Map<String, String> queryParams) throws JSONException {
    NeoContext ctx = NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod(HTTP_METHOD_GET)
        .recordId(recordId)
        .adTab(sfEntity.getADTab())
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(queryParams)
        .build();
    NeoExtensionResult result = NeoExtensionDispatcher.dispatch(NeoExtensionRequest.builder()
        .qualifier(sfEntity.getJavaQualifier())
        .specName(specName)
        .entityName(entityName)
        .surface(NeoExtensionSurface.READ)
        .channel(NeoExtensionChannel.MCP)
        .context(ctx)
        .build());
    NeoResponse response = result.response();
    return response != null ? neoResponseToMcpResult(response) : null;
  }

  /**
   * Build the provider's query-param map. Never returns {@code null}: the REST read always passes
   * a map ({@code NeoServlet.extractQueryParams}), so a provider written against REST
   * dereferences one without a guard — {@code ContactsBpStatsHandler:71-72} is exactly that
   * shape, and a {@code null} there reaches the agent as a 500.
   *
   * <p>Carried over from {@code McpTablessReadDispatcher.buildParams} (ETP-5405) when that class
   * was folded into this one. Paging and ordering are part of it on purpose: a provider serves the
   * whole read, so nothing downstream is left to apply {@code startRow}/{@code endRow} for it.</p>
   *
   * @param filters  the tool's {@code filters} object, or {@code null}
   * @param parentId the parent scope, or {@code null}
   * @param offset   first row, paired with {@code limit}; both {@code null} to omit paging
   * @param limit    page size, paired with {@code offset}
   * @param orderBy  sort expression, or {@code null}
   * @return a mutable map, never {@code null}
   */
  static Map<String, String> buildReadProviderParams(JSONObject filters, String parentId,
      Integer offset, Integer limit, String orderBy) {
    Map<String, String> params = new HashMap<>();
    if (offset != null && limit != null) {
      params.put(JsonConstants.STARTROW_PARAMETER, String.valueOf(offset));
      params.put(JsonConstants.ENDROW_PARAMETER, String.valueOf(offset + limit - 1));
    }
    if (StringUtils.isNotBlank(orderBy)) {
      params.put(JsonConstants.SORTBY_PARAMETER, orderBy);
    }
    if (StringUtils.isNotBlank(parentId)) {
      params.put(McpConstants.PARAM_PARENT_ID, parentId);
    }
    if (filters == null) {
      return params;
    }
    // Scalars only. A filter value that is itself an object or an array is an operator form
    // ({"gt": …}, a list of ids) belonging to the generic query language; flattening one into a
    // string would hand the provider something that looks like a value and is not.
    Iterator<String> keys = filters.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = filters.opt(key);
      if (value != null && !(value instanceof JSONObject) && !(value instanceof JSONArray)) {
        params.put(key, String.valueOf(value));
      }
    }
    return params;
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
   * <p><b>The pre phase is not routed here</b> — it runs earlier, in
   * {@link #runReadProvider}, because a pre-hook that serves the whole read has to be offered the
   * chance before the generic query is built, not after. See that method for why.</p>
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
   * Build the {@link NeoContext} for the SELECTOR endpoint hook (ETP-5415).
   *
   * <p>Mirrors what the REST path passes on
   * {@code GET /sws/neo/{spec}/{entity}/selector/{field}}
   * ({@code NeoHookDispatcher.buildHookContext} with {@code endpointType=SELECTOR}), so a
   * selector queried through MCP reaches the entity's customization with the same shape the SPA
   * produces. {@code fieldName} is the whole of the routing: an entity has one customization but
   * as many selectors as it has foreign keys, and the field name is what tells them apart — see
   * {@code PaymentMethodSelectorSupport.handleIfPaymentMethodSelector}, which declines every
   * field but its own, and {@code ProductPriceHandler.afterHandle}, which enriches only
   * {@code priceListVersion}.</p>
   *
   * <p>{@code adTab} matters here beyond mere parity: it is how
   * {@code DirectionFallback.WINDOW} resolves pay-in vs pay-out
   * ({@code ctx.getAdTab().getWindow().isSalesTransaction()}). Without it that support fails
   * closed with a 422 rather than guessing — which is the correct behaviour, but a needless one
   * when the tab is right there.</p>
   *
   * @param specName      the spec that owns the entity
   * @param entityName    the entity that owns the FK field
   * @param fieldName     the selector field as passed to {@code neo_selectors}, e.g.
   *                      {@code paymentMethod}
   * @param contextParams the validated selector context params, exposed as query params so a
   *                      customization reads them the way the REST path lets it
   * @param adTab         the entity's AD tab, may be {@code null} for tab-less entities
   * @param sfEntity      the entity configuration
   * @return a NeoContext with {@code endpointType=SELECTOR} and {@code httpMethod=GET}
   */
  static NeoContext buildSelectorHookContext(String specName, String entityName, String fieldName,
      Map<String, String> contextParams, Tab adTab, SFEntity sfEntity) {
    return NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod("GET")
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName(fieldName)
        .queryParams(contextParams)
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
    return buildActionHookContext(specName, entityName, recordId, actionName, params, adTab,
        sfEntity, "POST");
  }

  /**
   * Same as {@link #buildActionHookContext(String, String, String, String, JSONObject, Tab,
   * SFEntity)} under the HTTP method a declared action is served on (ETP-5558: {@code
   * currencyOptions} only answers {@code GET}).
   */
  @SuppressWarnings("java:S107") // the seven context values plus the method; a builder would only rename them
  static NeoContext buildActionHookContext(String specName, String entityName, String recordId,
      String actionName, JSONObject params, Tab adTab, SFEntity sfEntity, String httpMethod) {
    return NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod(httpMethod)
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
    return deletePreHookResult(handler.handle(ctx), recordId);
  }

  /**
   * Maps a DELETE pre-hook response to an MCP result with the {@link #runDeletePreHook} rules, for
   * callers that obtained the response elsewhere (ETP-5415: {@code neo_delete} runs its pre-hook
   * through {@code NeoExtensionDispatcher}).
   *
   * @param pre      the pre-hook response, may be {@code null}
   * @param recordId the id of the record being deleted, echoed in the confirmation
   * @return an MCP result to short-circuit with, or {@code null} to proceed with generic removal
   */
  static JSONObject deletePreHookResult(NeoResponse pre, String recordId) throws JSONException {
    if (pre == null) {
      return null;
    }
    int status = pre.getHttpStatus();
    JSONObject body = pre.getBody();
    if (status >= 200 && status < 300 && status != HTTP_ACCEPTED
        && (body == null || body.length() == 0)) {
      return McpToolResponses.deleteConfirmation(recordId);
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
