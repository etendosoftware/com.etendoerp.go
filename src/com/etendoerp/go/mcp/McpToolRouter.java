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

import static com.etendoerp.go.mcp.McpToolResponses.buildRoutingErrorBody;
import static com.etendoerp.go.mcp.McpToolResponses.buildUnexpectedErrorBody;
import static com.etendoerp.go.mcp.McpToolResponses.deleteConfirmation;
import static com.etendoerp.go.mcp.McpToolResponses.imageToolResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.erpCommon.utility.PropertyNotFoundException;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.AmortizationPlanService;
import com.etendoerp.go.schemaforge.util.NeoRecordVersion;
import com.etendoerp.go.schemaforge.BatchService;
import com.etendoerp.go.schemaforge.NeoActionRecordGuard;
import com.etendoerp.go.schemaforge.NeoCommercialLinePolicy;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoReportContract;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoExtensionChannel;
import com.etendoerp.go.schemaforge.NeoExtensionDispatcher;
import com.etendoerp.go.schemaforge.NeoExtensionRequest;
import com.etendoerp.go.schemaforge.NeoExtensionResult;
import com.etendoerp.go.schemaforge.NeoExtensionSurface;
import com.etendoerp.go.schemaforge.NeoDefaultsService;
import com.etendoerp.go.schemaforge.DocTypeResolver;
import com.etendoerp.go.schemaforge.NeoFieldFilter;
import com.etendoerp.go.schemaforge.NeoMandatoryDefaultsService;
import com.etendoerp.go.schemaforge.NeoParentTabFilterResolver;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoReadPredicates;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoVectorSearchEndpoint;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.selector.policy.NeoSelectorPolicy;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoCrudHelper;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * Routes MCP tool calls to appropriate NEO Headless handlers.
 * <p>
 * Replicates the same data access patterns as {@code NeoServlet} (findSpec, findEntity,
 * DefaultJsonDataService, NeoFieldFilter) so that MCP tool calls produce identical results
 * to the REST API. Each handler returns an MCP-formatted result object with a "content"
 * array of text blocks.
 * <p>
 * Tool routing:
 * <ul>
 *   <li>{@code etendo_discover} — list all accessible specs</li>
 *   <li>{@code etendo_list} — list records (GET)</li>
 *   <li>{@code etendo_get} — get single record by ID</li>
 *   <li>{@code etendo_create} — create a record (POST)</li>
 *   <li>{@code etendo_update} — update a record (PUT)</li>
 *   <li>{@code etendo_delete} — delete a record (DELETE)</li>
 *   <li>{@code etendo_selectors} — query FK selector values</li>
 *   <li>{@code etendo_defaults} — get default field values for new records</li>
 *   <li>{@code generate_*} — report generation tools</li>
 *   <li>All other names — process execution tools</li>
 * </ul>
 */
public class McpToolRouter {

  private static final Logger log = LogManager.getLogger(McpToolRouter.class);
  /** Shared by the three funnels that wrap a failure into MCP content (java:S1192). */
  private static final String ERROR_BUILDING_CONTENT = "Error building MCP error content";
  private static final String ACCESS_DENIED_FOR_CURRENT_ROLE_SUFFIX = "' for current role";
  /** OBPreference property name holding the optional Context7 API token. */
  static final String PREF_CONTEXT7_TOKEN = "ETGO_Context7Token";
  private static final String HTTP_METHOD_GET = "GET";
  private static final String HTTP_METHOD_POST = "POST";
  private static final String HTTP_METHOD_PUT = "PUT";
  private static final String HTTP_METHOD_DELETE = "DELETE";
  /** DAL property names the line-policy injection keys off (IMP-15). */
  /** The batch tool's own name, used by the router switch and in its refusal envelopes. */
  private static final String TOOL_NEO_BATCH = "etendo_batch";
  private static final String FIELD_PRODUCT = "product";
  private static final String FIELD_UOM = "uOM";


  /**
   * Route a tool call to its handler.
   * <p>
   * For CRUD tools (etendo_list, etendo_get, etc.), the spec name is extracted from the
   * "spec" argument. For process and report tools, the spec name is derived from
   * the tool name itself via {@link ToolRegistry#resolveSpecName}.
   *
   * @param toolName  MCP tool name (e.g. "etendo_list", "complete_order")
   * @param arguments tool arguments (may be null)
   * @param scopes    OAuth2 scopes granted to this call
   * @return MCP result object with "content" array
   */
  public JSONObject route(String toolName, JSONObject arguments, java.util.Set<String> scopes) {
    String renamedTo = McpRoutingException.renamedToolName(toolName);
    if (renamedTo != null) {
      // ETP-5602: answer a removed neo_<x> name with its new name before anything else — the
      // default branch would read it as a process tool and refuse it for an unrelated reason.
      return wrapAsErrorContent(
          buildRoutingErrorBody(McpRoutingException.toolRenamed(toolName, renamedTo), toolName));
    }
    McpAuthorizationService.authorizeToolCall(toolName, scopes);
    // Vector target authorization must run in the caller's role context. The regular MCP
    // handlers use admin mode for DAL metadata and therefore cannot safely host this check.
    // Dispatching before setAdminMode preserves AD_Window/entity organization isolation.
    if (McpConstants.TOOL_NEO_VECTOR_SEARCH.equals(toolName)) {
      return handleVectorSearch(arguments);
    }
    try {
      OBContext.setAdminMode();
      try {
        // IMP-40: refuse an argument the tool does not declare, instead of dropping it in silence.
        // Checked here, once, for every tool that has a fixed argument set — a per-handler check
        // is a check somebody forgets to add to the next handler.
        rejectUnknownArguments(toolName, arguments);

        // Resolve spec name from tool name or arguments
        String specName = ToolRegistry.resolveSpecName(toolName, arguments);
        authorizeSpecAccess(specName, resolveAccessMethod(toolName));

        switch (toolName) {
          case "etendo_discover":
            return handleDiscover();
          case "etendo_list":
            return handleList(specName, arguments);
          case "etendo_get":
            return handleGet(specName, arguments);
          case "etendo_create":
            return handleCreate(specName, arguments);
          case "etendo_update":
            return handleUpdate(specName, arguments);
          case "etendo_delete":
            return handleDelete(specName, arguments);
          case "etendo_selectors":
            return handleSelectors(specName, arguments);
          case "etendo_defaults":
            return handleDefaults(specName, arguments);
          case "etendo_schema":
            return handleSchema(specName, arguments);
          case TOOL_NEO_BATCH:
            // Withdrawing it from tools/list is not enough: an agent that learned the name
            // elsewhere would still reach the handler, and a silent success on a path we chose
            // not to maintain is worse than the refusal.
            if (!McpConstants.batchToolEnabled()) {
              return wrapAsErrorContent(McpRouterErrorBodies.batchDisabled());
            }
            return handleBatch(arguments);
          case "etendo_action":
            return handleAction(specName, arguments);
          case McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN:
            return handleGenerateAmortizationPlan(arguments);
          case McpConstants.TOOL_NEO_WIDGET:
            return McpWidgetHandler.handle(arguments);
          // B3: addresses no spec and touches no business data — it only reports on the API itself.
          case McpConstants.TOOL_NEO_FEEDBACK:
            return McpFeedbackTool.handle(arguments);
          case McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD:
            return imageToolResult(McpImageTools.requestUpload(arguments));
          case McpConstants.TOOL_NEO_UPLOAD_IMAGE:
            return imageToolResult(McpImageTools.uploadImage(arguments));
          case McpConstants.TOOL_NEO_GET_IMAGE_UPLOAD:
            return imageToolResult(McpImageTools.getUpload(arguments));
          case "docs":
            return handleDocs(arguments);
          default:
            // Check if it's a report tool (generate_*)
            if (toolName.startsWith(McpConstants.GENERATE_PREFIX)) {
              return handleReport(specName, arguments);
            }
            // Otherwise it's a process tool
            return handleProcess(specName, arguments);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (McpRoutingException e) {
      // ETP-4793 / IMP-17: a spec/entity that does not exist already knows its own envelope,
      // including the self-correcting `available` list (evidence B20).
      // ETP-5639: keyed on the refusal's own code. It used to say "addressed something that does
      // not exist" for every code — read-only fields, disabled methods and missing parentIds too.
      log.warn(routingRejectionLogLine(toolName, e));
      return wrapAsErrorContent(buildRoutingErrorBody(e, toolName));
    } catch (SecurityException e) {
      // An authorization refusal is a permanent answer for this role, not a server failure. It
      // used to fall into the generic handler below and surface as 500 server_error, whose own
      // hint invites no retry but whose status class does: a client with a retry-on-5xx rule
      // loops forever on a decision that will never change.
      log.warn("MCP tool '{}' refused for the current role: {} session={}", toolName,
          e.getMessage(), McpUsageTelemetry.sessionForLog());
      return wrapAsErrorContent(McpRouterErrorBodies.forbidden(toolName, e.getMessage()));
    } catch (org.openbravo.base.exception.OBSecurityException e) {
      // Openbravo's own refusal does NOT extend SecurityException, so without this clause it
      // reached the generic handler and answered 500 for the same kind of decision.
      log.warn("MCP tool '{}' refused by the platform for the current role: {} session={}",
          toolName, e.getMessage(), McpUsageTelemetry.sessionForLog());
      return wrapAsErrorContent(McpRouterErrorBodies.forbidden(toolName, e.getMessage()));
    } catch (Exception e) {
      log.error("Error routing MCP tool '{}' session={}", toolName,
          McpUsageTelemetry.sessionForLog(), e);
      return wrapAsErrorContent(buildUnexpectedErrorBody(toolName, e));
    }
  }

  /**
   * The single WARN line a routing refusal leaves in the log, built from its error code.
   *
   * @param toolName the tool that was called
   * @param e        the refusal
   * @return e.g. {@code MCP tool 'neo_update' rejected (read_only_field): Field 'x' is read-only…
   *         session=<key>}
   */
  static String routingRejectionLogLine(String toolName, McpRoutingException e) {
    return "MCP tool '" + toolName + "' rejected (" + e.getErrorCode() + "): " + e.getMessage()
        + " session=" + McpUsageTelemetry.sessionForLog();
  }

  /** Route semantic search through the same authenticated DB Extended contract as REST. */
  private JSONObject handleVectorSearch(JSONObject arguments) {
    String query = arguments == null ? null : arguments.optString(McpConstants.PARAM_QUERY, null);
    String targets = McpArgumentUtils.joinStringArray(
        arguments == null ? null : arguments.optJSONArray("targets"));
    if (StringUtils.isBlank(targets)) {
      // IMP-41: `targets` is optional, and omitting it means "search everywhere I may read".
      // The MCP surface exposes no `namespaces` alternative, so demanding a target up front asked
      // the agent for the one thing a natural-language question does not come with.
      java.util.Optional<List<String>> allowed = NeoVectorSearchEndpoint.authorizedTargetKeys();
      if (allowed.isPresent() && allowed.get().isEmpty()) {
        return wrapAsErrorContent(buildNoSearchableTargetsBody());
      }
      // Absent means the catalogue could not be read at all, which is not the same as "you may
      // search nothing": leave targets null so the endpoint decides, as it did before IMP-41.
      targets = allowed.map(keys -> String.join(",", keys)).orElse(null);
    }
    NeoResponse response = new NeoVectorSearchEndpoint().handle(query, null, targets,
        McpArgumentUtils.optionalString(arguments, "topK"),
        McpArgumentUtils.optionalString(arguments, "minScore"),
        McpArgumentUtils.optionalString(arguments, "maxScore"), null);
    // ETP-5306: the JSONObject overloads, so the body is sanitised before it is rendered.
    JSONObject body = response.getBody();
    return response.getHttpStatus() >= 400 ? wrapAsErrorContent(body) : wrapAsTextContent(body);
  }

  /**
   * The refusal for a role that can read no search target at all (IMP-41).
   *
   * <p>Said plainly and with a next step, because the alternative is worse than useless: an empty
   * target list would reach the endpoint as "no targets and no namespaces" and come back as a
   * generic 400 about a missing parameter, sending the agent to re-send the same call with
   * invented target names.</p>
   *
   * @return the error envelope
   */
  private static JSONObject buildNoSearchableTargetsBody() {
    try {
      JSONObject envelope = new JSONObject();
      envelope.put(McpConstants.KEY_STATUS, McpConstants.STATUS_FORBIDDEN);
      envelope.put(McpConstants.KEY_ERROR, "no_searchable_vector_targets");
      envelope.put(McpConstants.KEY_DETAIL, "Semantic search is configured on this instance, but "
          + "your role cannot read any of its indexes.");
      envelope.put(McpConstants.KEY_TOOL, McpConstants.TOOL_NEO_VECTOR_SEARCH);
      envelope.put(McpConstants.KEY_HINT, "Do not retry with other target names — none would work. "
          + "Use etendo_list or etendo_selectors to find the record instead.");
      return envelope;
    } catch (JSONException e) {
      throw new McpToolException(ERROR_BUILDING_CONTENT, e);
    }
  }

  // ── docs (Context7 documentation lookup) ──────────────────────────────

  /**
   * Handle the {@code docs} tool: fetch documentation from Context7 for the
   * {@code etendosoftware/etendo-go-docs} library, filtered by a topic.
   * <p>
   * Delegates to {@link #handleDocs(JSONObject, Context7DocsClient)} with a default
   * client. Tests should call the package-private overload with a mocked client.
   *
   * @param arguments tool arguments ({@code topic} required, {@code tokens} and
   *                  {@code type} optional)
   * @return MCP text content with the docs body, or error content on failure
   */
  private JSONObject handleDocs(JSONObject arguments) {
    return handleDocs(arguments, new Context7DocsClient());
  }

  /**
   * Package-private seam for the {@code docs} tool so unit tests can inject a mocked
   * {@link Context7DocsClient} and exercise the success path without the network.
   *
   * @param arguments tool arguments ({@code topic} required, {@code tokens} and
   *                  {@code type} optional)
   * @param client    the Context7 client to use for the lookup
   * @return MCP text content with the docs body, a friendly message when no docs are
   *         found, or error content on failure
   */
  JSONObject handleDocs(JSONObject arguments, Context7DocsClient client) {
    String topic = arguments != null ? arguments.optString("topic", null) : null;
    if (StringUtils.isBlank(topic)) {
      return wrapAsErrorContent("The 'topic' argument is required for the docs tool.");
    }
    int tokens = arguments != null
        ? arguments.optInt("tokens", Context7DocsClient.DEFAULT_TOKENS)
        : Context7DocsClient.DEFAULT_TOKENS;
    String type = arguments != null
        ? arguments.optString("type", Context7DocsClient.DEFAULT_TYPE)
        : Context7DocsClient.DEFAULT_TYPE;

    String apiKey = resolveContext7Token();
    try {
      String body = client.fetchDocs(topic, tokens, type, apiKey);
      if (StringUtils.isBlank(body)) {
        return wrapAsTextContent("No documentation found for topic '" + topic + "'.");
      }
      // ETP-5306: the recipe surface states the record-reference format too, for an agent that
      // came here without reading etendo_schema. One constant, declared once per response, instead
      // of a `$ref` field repeated on every row.
      return wrapAsTextContent(McpConstants.RECORD_REF_NOTE + "\n\n" + body);
    } catch (Exception e) {
      log.error("Error fetching docs for topic '{}' session={}", topic,
          McpUsageTelemetry.sessionForLog(), e);
      return wrapAsErrorContent("Error fetching docs: " + e.getMessage());
    }
  }

  /**
   * Resolve the optional Context7 API token from the {@code ETGO_Context7Token} OBPreference.
   * <p>
   * Runs within the {@code OBContext.setAdminMode()} scope of {@link #route} and uses the
   * current context (client, org, user, role; window = null). If no preference is defined
   * or it is blank, returns {@code null} so the docs lookup proceeds unauthenticated.
   * The token value is never logged.
   *
   * @return the configured token, or {@code null} when none is set
   */
  String resolveContext7Token() {
    OBContext ctx = OBContext.getOBContext();
    try {
      String value = Preferences.getPreferenceValue(
          PREF_CONTEXT7_TOKEN, true,
          ctx.getCurrentClient(), ctx.getCurrentOrganization(),
          ctx.getUser(), ctx.getRole(), null);
      return StringUtils.trimToNull(value);
    } catch (PropertyNotFoundException e) {
      // No preference defined → unauthenticated call.
      return null;
    } catch (PropertyException e) {
      log.warn("Could not read preference {}: {}", PREF_CONTEXT7_TOKEN, e.getMessage());
      return null;
    }
  }

  // ── etendo_discover ──────────────────────────────────────────────────────

  /**
   * List all active specs the current user can access.
   * Replicates NeoServlet.handleDiscovery() logic.
   */
  private JSONObject handleDiscover() throws Exception {
    OBCriteria<SFSpec> specCriteria = OBDal.getInstance().createCriteria(SFSpec.class);
    specCriteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    specCriteria.add(Restrictions.eq(SFSpec.PROPERTY_SHOWINMCP, true));
    specCriteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
    List<SFSpec> allSpecs = specCriteria.list();

    JSONArray specsArray = new JSONArray();
    for (SFSpec spec : allSpecs) {
      String specType = spec.getSpecType();
      if (McpToolRouterSupport.hasSpecAccess(spec, specType)) {
        // ETP-4254: load the included entities ONCE per W spec — the entity summary, the
        // caller-derived primaryEntity (IMP-9/ETP-4601) and the spec-level readOnly marker are
        // all derived from this same list, so none of them costs an extra query.
        List<SFEntity> includedEntities = "W".equals(specType)
            ? McpToolRouterSupport.listIncludedEntities(spec.getId()) : null;
        JSONArray entities = "W".equals(specType)
            ? McpToolRouterSupport.buildEntitySummaryArray(includedEntities) : null;
        // IMP-9: derived here (not inside buildDiscoverSpec) so that method stays DAL-free —
        // handleDiscover already runs in the live/admin OBContext resolving tab levels needs.
        String primaryEntity = "W".equals(specType)
            ? McpToolRouterSupport.resolvePrimaryEntityName(includedEntities)
            : null;
        specsArray.put(McpToolRouterSupport.buildDiscoverSpec(
            spec, specType, entities, primaryEntity, includedEntities));
      }
    }

    JSONObject result = new JSONObject();
    result.put("specs", specsArray);
    result.put("count", specsArray.length());
    result.put("guidance", McpToolRouterSupport.buildDocsGuidance());
    // ETP-5200: how to build an app link, advertised once per session instead of on every row.
    // Omitted entirely when no public app base URL is configured — see McpRecordUrls.
    JSONObject app = McpRecordUrls.buildAppMetadata();
    if (app != null) {
      result.put(McpRecordUrls.KEY_APP, app);
    }
    return wrapAsTextContent(result);
  }

  // ── etendo_list ──────────────────────────────────────────────────────────

  /**
   * List records from a spec entity. Replicates NeoServlet.handleDefault() GET logic.
   */
  private JSONObject handleList(String specName, JSONObject args) throws Exception {
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY);

    String entityName = args.getString(McpConstants.PARAM_ENTITY);
    int limit = args.optInt("limit", 100);
    int offset = args.optInt("offset", 0);
    String orderBy = args.optString("orderBy", null);
    JSONObject filters = args.optJSONObject("filters");
    String parentId = McpArgumentUtils.optionalString(args, McpConstants.PARAM_PARENT_ID);

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);

    // IMP-40: a child entity is readable only through its parent. The gate itself is not new —
    // McpParentScope has carried VERB_LIST since it was written, and etendo_discover advertises
    // "parentRequiredFor":["list","get",...] on 89 entities — but nothing ever enforced it here,
    // and etendo_list had no parentId argument to satisfy it with. So an agent that asked for one
    // order's lines got EVERY order's lines, with nothing in the response to say the scope had
    // been dropped: a confident, wrong answer that reads exactly like a correct one. Worse than a
    // refusal, because the caller then acts on rows belonging to records it never asked about.
    filters = scopeListToParent(specName, entityName, sfEntity, parentId, filters);

    // ETP-5405 + ETP-5415 (D13): the read's PRE phase, which must run before the tab is demanded.
    // A customization that answers here is serving the whole list itself — over REST that has
    // always been so (NeoHookDispatcher invokes the pre-hook before anything touches the tab), and
    // it is how the 16 tab-less entities render in the SPA while MCP returned 500.
    //
    // Position is ETP-5405's and is load-bearing: AFTER the parent gate above, so a provider
    // cannot serve rows belonging to a parent the caller never asked about (IMP-40), and before
    // getAdTabOrThrow, so a tab-less entity is reached at all.
    JSONObject handled = McpHookExecutor.runReadProvider(specName, entityName, null, sfEntity,
        McpHookExecutor.buildReadProviderParams(filters, parentId, offset, limit, orderBy));
    if (handled != null) {
      return handled;
    }

    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);
    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();
    NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(sfEntity, dalEntityName);

    Map<String, String> params = McpWriteRequestSupport.buildBaseParams(adTab, dalEntityName);
    params.put(JsonConstants.STARTROW_PARAMETER, String.valueOf(offset));
    params.put(JsonConstants.ENDROW_PARAMETER, String.valueOf(offset + limit - 1));

    if (StringUtils.isNotBlank(orderBy)) {
      params.put(JsonConstants.SORTBY_PARAMETER, orderBy);
    }

    // Apply filters as where clause
    if (filters != null && filters.length() > 0) {
      String whereClause = McpQuerySupport.buildWhereFromFilters(filters, adTab, sfEntity, log);
      if (StringUtils.isNotBlank(whereClause)) {
        params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, whereClause);
        params.put(JsonConstants.USE_ALIAS, "true");
      }
    }

    // Apply tab-level HQL where clause. ETP-5542: a child tab's clause can carry a placeholder for
    // its parent record (Bin Contents: `e.storageBin.id=@Locator.id@`). REST fills it with the
    // parent id; passing it on verbatim here matched nothing and returned an empty list with a 200.
    String tabWhere = NeoParentTabFilterResolver.resolveTabWhere(adTab, parentId);
    if (StringUtils.isNotBlank(tabWhere)) {
      String existing = params.get(JsonConstants.WHERE_AND_FILTER_CLAUSE);
      if (StringUtils.isNotBlank(existing)) {
        params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE,
            "(" + tabWhere + ") and (" + existing + ")");
      } else {
        params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, tabWhere);
        params.put(JsonConstants.USE_ALIAS, "true");
      }
    }

    // ETP-5009: the customization's read predicates, ANDed in exactly as the REST list GET and
    // its ?_distinct= fetch do (NeoReadPredicates), so a row a customization excludes from the
    // query is excluded here too — rather than only from the page its afterHandle was handed.
    String readPredicate = NeoReadPredicates.resolve(McpHookExecutor.buildReadHookContext(
        specName, entityName, null, adTab, sfEntity,
        McpHookExecutor.buildReadProviderParams(filters, parentId, offset, limit, orderBy)),
        NeoExtensionChannel.MCP);
    if (StringUtils.isNotBlank(readPredicate)) {
      params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, NeoReadPredicates.and(
          params.get(JsonConstants.WHERE_AND_FILTER_CLAUSE), readPredicate));
      params.put(JsonConstants.USE_ALIAS, "true");
    }

    String result = jsonService.fetch(params);
    JSONObject responseJson = new JSONObject(result);

    // Check for errors
    JSONObject error = McpWriteRequestSupport.checkJsonServiceError(responseJson, McpConstants.SEE_ALSO_READING);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    // Apply field filtering
    fieldFilter.filterGetResponse(responseJson);

    // ETP-5415: the entity's customization gets its READ post-phase here, which is the stage an
    // MCP read simply did not have — a REST list of sales-order lines carried OrderLineHandler's
    // injected `productCode` and this one did not, with no error and no log line to say so.
    // Placed after field filtering and before projection on purpose: that is the REST read's own
    // ordering (NeoCrudHandler filters, then handleWithHooks runs afterHandle), and it leaves the
    // caller's explicit `fields:[…]` whitelist as the last word. See McpHookExecutor#runReadHook.
    McpHookExecutor.ReadHookOutcome readHook = McpHookExecutor.runReadHook(
        specName, entityName, null, adTab, sfEntity, responseJson);
    if (readHook.mcpError() != null) {
      return readHook.mcpError();
    }
    responseJson = readHook.body();

    // IMP-2: optional projection — explicit `fields:[...]` or view:"summary". No-op when neither
    // is present, so the default returns every column.
    // IMP-18: the filter is passed in so an unknown requested name is reported, not dropped.
    McpQuerySupport.applyProjection(responseJson, args, sfEntity, adTab, fieldFilter);

    // IMP-5 clause (iii): flatten last, so projection and field filtering keep operating on the
    // wrapped shape core produced and only the body handed to the agent changes.
    return wrapAsTextContent(
        McpToolRouterSupport.flattenCoreResponse(responseJson));
  }

  // ── etendo_get ───────────────────────────────────────────────────────────

  /**
   * Get a single record by ID.
   */
  private JSONObject handleGet(String specName, JSONObject args) throws Exception {
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY, "id");

    String entityName = args.getString(McpConstants.PARAM_ENTITY);
    String recordId = args.getString("id");

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);

    // ETP-5405 + ETP-5415 (D13): see the twin call in handleList. recordId is what lets a
    // customization tell a single-record read from a list, exactly as it does in the post phase.
    JSONObject handled = McpHookExecutor.runReadProvider(specName, entityName, recordId, sfEntity,
        McpHookExecutor.buildReadProviderParams(null, null, null, null, null));
    if (handled != null) {
      return handled;
    }

    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);
    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();
    NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(sfEntity, dalEntityName);

    Map<String, String> params = McpWriteRequestSupport.buildBaseParams(adTab, dalEntityName);
    params.put(JsonConstants.ID, recordId);

    String result = jsonService.fetch(params);
    JSONObject responseJson = new JSONObject(result);

    JSONObject error = McpWriteRequestSupport.checkJsonServiceError(responseJson, McpConstants.SEE_ALSO_READING);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    // IMP-5: a get-by-id that matched nothing comes back as {data:[], status:0} — a
    // success-looking empty result. Translate it into an explicit, machine-detectable
    // not-found so the agent can tell "not found" from "empty match" and self-correct.
    if (McpToolRouterSupport.isEmptySuccessResult(responseJson)) {
      return wrapAsErrorContent(
          McpToolRouterSupport.buildNotFoundError(specName, entityName, recordId));
    }

    fieldFilter.filterGetResponse(responseJson);

    // ETP-5415: READ post-phase, at the same stage as in handleList — see that call site and
    // McpHookExecutor#runReadHook for why it sits between field filtering and projection. The
    // record id is passed on, because that is the value a customization branches on to tell a
    // single-record read from a list (e.g. ReactivatePaymentHandler#isSingleRecordGet).
    McpHookExecutor.ReadHookOutcome readHook = McpHookExecutor.runReadHook(
        specName, entityName, recordId, adTab, sfEntity, responseJson);
    if (readHook.mcpError() != null) {
      return readHook.mcpError();
    }
    responseJson = readHook.body();

    // IMP-2: optional projection — explicit `fields:[...]` or view:"summary".
    // IMP-18: unknown requested names are reported as unknownFields — lifted to the top level by
    // the flatten below, along with the rest of the wrapper's contents.
    McpQuerySupport.applyProjection(responseJson, args, sfEntity, adTab, fieldFilter);

    // IMP-5 clause (iii): see handleList — flatten last, after every stage that reads the wrapper.
    JSONObject flat = McpToolRouterSupport.flattenCoreResponse(responseJson);
    // ETP-5200: the link the agent hands the user. Header records only — a line has no app page.
    McpRecordUrls.addRecordUrl(flat, specName, recordId,
        McpToolRouterSupport.isPrimaryTab(adTab));
    return wrapAsTextContent(flat);
  }

  // ── etendo_create ────────────────────────────────────────────────────────

  /**
   * Refuses any top-level argument the tool does not declare (IMP-40).
   *
   * <p>Only tools with a fixed argument set are guarded; {@link ToolRegistry#declaredArgumentNames}
   * returns {@code null} for the rest (process and report tools, whose parameters come from the AD
   * process definition) and they are left alone rather than guessed at.</p>
   *
   * <p>The first unknown name is reported rather than all of them: the caller has to correct the
   * call either way, and naming one keeps the message short enough to act on.</p>
   *
   * @param toolName  the tool being called
   * @param arguments the arguments as received, may be {@code null}
   */
  private static void rejectUnknownArguments(String toolName, JSONObject arguments) {
    if (arguments == null) {
      return;
    }
    java.util.Optional<java.util.Set<String>> maybeDeclared =
        ToolRegistry.declaredArgumentNames(toolName);
    if (!maybeDeclared.isPresent()) {
      return;
    }
    java.util.Set<String> declared = maybeDeclared.get();
    Iterator<String> keys = arguments.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!declared.contains(key)) {
        throw McpRoutingException.unknownArgument(key, toolName,
            new java.util.ArrayList<>(declared));
      }
    }
  }

  /**
   * Applies the parent gate to a list call and returns the filters to run with (IMP-40).
   *
   * <p>Three outcomes. For an entity that is not a gated child, the filters pass through
   * untouched. (A child gated by its tab where clause — {@code McpParentScope.Kind#TAB_WHERE} —
   * has no parent field: it is refused without {@code parentId} and otherwise passes through, the
   * clause doing the scoping.) For a gated child with no {@code parentId}, the call is refused with
   * the same {@code parent_required} envelope {@code etendo_defaults} already uses — one wording,
   * one copy.
   * For a gated child with a {@code parentId}, the parent field is added to the filters, so the
   * scope is enforced by the query rather than trusted.</p>
   *
   * <p>An explicit filter on the parent field is left alone: it is the pre-existing way to do
   * this, it says the same thing, and breaking it would be a regression for no gain.</p>
   *
   * @param specName   the spec being listed
   * @param entityName the entity being listed
   * @param sfEntity   the resolved SchemaForge entity
   * @param parentId   the parent id supplied by the caller, may be blank
   * @param filters    the caller's filters, may be {@code null}
   * @return the filters to apply, possibly a new object carrying the parent scope
   * @throws JSONException if the filters cannot be extended
   */
  private JSONObject scopeListToParent(String specName, String entityName, SFEntity sfEntity,
      String parentId, JSONObject filters) throws JSONException {
    McpParentScope.Scope scope = McpParentScope.forEntity(sfEntity);
    if (!scope.requiresParentFor(McpParentSection.VERB_LIST)) {
      return filters;
    }
    String parentField = scope.getParentField();
    if (parentField != null && filters != null && filters.has(parentField)
        && !filters.isNull(parentField)) {
      return filters;
    }
    if (StringUtils.isBlank(parentId)) {
      throw McpRoutingException.parentRequired(specName, entityName,
          scope.getParentEntity(), parentField);
    }
    if (parentField == null) {
      // A TAB_WHERE child: the tab's where clause carries the parent placeholder, filled from
      // parentId further down (NeoParentTabFilterResolver.resolveTabWhere). There is no field to
      // add to the filters, and the query is still scoped to the parent.
      return filters;
    }
    JSONObject scoped = filters == null ? new JSONObject() : new JSONObject(filters.toString());
    scoped.put(parentField, parentId);
    return scoped;
  }

  /**
   * Create a new record.
   */
  private JSONObject handleCreate(String specName, JSONObject args) throws Exception {
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY, McpConstants.PARAM_FIELDS);

    String entityName = args.getString(McpConstants.PARAM_ENTITY);
    JSONObject fields = args.getJSONObject(McpConstants.PARAM_FIELDS);

    // IMP-40: accept parentId as a top-level argument, the way every other parent-aware tool
    // takes it. It used to be read only out of `fields`, so an agent that followed the shape
    // etendo_defaults/etendo_list/etendo_get taught it had its parent link silently dropped — and then
    // got a 422 for parent-derived fields it was never told to send. Copied into `fields`
    // rather than handled separately so there stays exactly ONE downstream reader of it (the
    // resolveParentFK block below); the top-level argument wins, because it is the declared one.
    if (args.has(McpConstants.PARAM_PARENT_ID) && !args.isNull(McpConstants.PARAM_PARENT_ID)) {
      String topLevelParentId = args.getString(McpConstants.PARAM_PARENT_ID);
      if (StringUtils.isNotBlank(topLevelParentId)) {
        fields.put(McpConstants.PARAM_PARENT_ID, topLevelParentId);
      }
    }

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);
    // ETP-4254: entity-level method gate, the same ETGO_SF_ENTITY flags the REST CRUD path
    // enforces. hasSpecAccess above is role-level only, so without this an agent could write
    // to an entity configured read-only (which etendo_discover already reports as readOnly).
    McpToolRouterSupport.requireMethodEnabled(spec, sfEntity, HTTP_METHOD_POST);
    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);
    // ETP-5558: a child whose parent cannot be identified is refused here, before any body
    // transform — with or without parentId. Past this point injectMandatoryDefaults would fill the
    // unmappable link on its own and attach the record to a parent nobody chose.
    McpWriteRequestSupport.requireApplicableParent(sfEntity,
        fields.optString(McpConstants.PARAM_PARENT_ID, null));

    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();
    NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(sfEntity, dalEntityName);

    Map<String, String> params = McpWriteRequestSupport.buildBaseParams(adTab, dalEntityName);

    // IMP-39: the spec's exclusions are honoured here. This used to accept every valid table
    // column "not just SF-configured ones", which is what let a curated-out field be written and
    // filtered while etendo_get denied it existed. A column with no ETGO_SF_FIELD row is still
    // accepted — only an explicit exclusion is refused.
    // IMP-18: the keys this write did not recognise, reported on the way out rather than dropped.
    java.util.Set<String> unknownWriteFields = new java.util.TreeSet<>();
    // client/organization are resolved from the session, never from the payload. The report is
    // empty unless the caller sent a different tenant than its own.
    JSONObject serverOwnedFields = new JSONObject();
    JSONObject filteredBody = McpWriteRequestSupport.mapFieldsToDalProperties(fields, adTab,
        sfEntity, unknownWriteFields, serverOwnedFields);

    // Hoisted so both FK-by-name resolution (IMP-4, below) and the sentinel/coercion passes
    // further down share one DAL entity lookup.
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(adTab.getTable().getId());

    // ETP-5184: image fields are validated before FK-by-name resolution, and that order is the
    // whole point. An Image BLOB column is an FK to AD_Image, so resolveFkNames below would take a
    // base64 blob or a URL for a display name and answer "no record named …" for a table the agent
    // cannot search — a dead end. Here it gets told which tool produces a valid id instead.
    JSONObject imageError = McpImageFieldSupport.validateImageFields(filteredBody, adTab, dalEntity);
    if (imageError != null) {
      return wrapAsErrorContent(imageError);
    }

    // IMP-4: resolve FK-by-name search strings (e.g. businessPartner:"Acme Corp") into real
    // record ids before anything downstream touches them. A value that already looks like an id
    // is left untouched. See McpFkResolver's class javadoc for the selector-context limitation.
    // ETP-5535: a child's selectors also see its parent record, as etendo_selectors' parentContext
    // would carry it — a line's tax rule reads the header's order date.
    JSONObject fkError = McpFkResolver.resolveFkNames(filteredBody, dalEntity, adTab,
        McpSelectorContextHelper.buildSelectorContextParams(
            McpParentSelectorContext.selectorArgs(sfEntity, dalEntity, filteredBody, null, null),
            adTab), log);
    if (fkError != null) {
      return wrapAsErrorContent(fkError);
    }

    // Snapshot user-provided fields BEFORE callout cascade can overwrite them.
    // Callouts derive dependent fields (e.g. product → tax, UOM) and may reset them
    // to sentinel "0" even when the user explicitly provided valid values.
    JSONObject userProvided = new JSONObject(filteredBody.toString());

    // Resolve parentId if present. An unmappable parent was already refused above (ETP-5558).
    String parentIdValue = null;
    if (filteredBody.has(McpConstants.PARAM_PARENT_ID)) {
      parentIdValue = filteredBody.getString(McpConstants.PARAM_PARENT_ID);
      filteredBody.remove(McpConstants.PARAM_PARENT_ID);
      McpWriteRequestSupport.resolveParentFK(adTab, filteredBody, parentIdValue, log, sfEntity);
    }

    // Inject mandatory defaults
    NeoContext ctx = NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod(HTTP_METHOD_POST)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .build();
    NeoMandatoryDefaultsService.injectMandatoryDefaults(filteredBody, adTab, ctx, parentIdValue);

    // Restore user-provided fields that callouts may have overwritten with sentinels.
    // User intent takes precedence over callout-derived values.
    Iterator<String> userKeys = userProvided.keys();
    while (userKeys.hasNext()) {
      String key = userKeys.next();
      if (McpConstants.PARAM_PARENT_ID.equals(key)) {
        continue;
      }
      filteredBody.put(key, userProvided.get(key));
    }

    // Keep MCP creates aligned with the REST create path. The create schema intentionally hides
    // system document-type fields, but mandatory defaults may still inject the generic "Standard
    // Order" target before the tab-specific subtype is known. Resolve the canonical type from
    // the active tab (sales quotations use the quotation subtype) and apply it to both
    // transactionDocument and documentType. Explicit values remain protected unless the tab has
    // an authoritative subtype filter.
    DocTypeResolver.reapplyDocTypeFromTabFilter(filteredBody, adTab, ctx,
        NeoCrudHelper.snapshotBodyFields(userProvided));

    // userProvided is the pre-defaults snapshot, so it is the only reliable witness of whether the
    // agent actually chose a uOM.
    injectLineUomIfApplicable(filteredBody, dalEntity, userProvided.has(FIELD_UOM));

    // ETP-5184: and the same witness decides whether the agent chose a price. The callout cannot
    // derive one here — its price inputs are the product selector's aux values, which the shared
    // create path resolves without any price-list context — so a line created through MCP comes out
    // at 0. Resolve it from the parent document's price list instead. See McpLinePriceInjector for
    // why this compensation lives in the MCP layer rather than in the shared path.
    McpLinePriceInjector.injectIfMissing(filteredBody, dalEntity, sfEntity,
        NeoCrudHelper.snapshotBodyFields(userProvided), log);

    // ETP-5335: the bill-to address is mandatory in AD, hidden from view:"create" as a system
    // field, and derivable by nobody — the callout branch that would fill it reads a selector aux
    // value this selector does not declare. Derive it from the business partner before the
    // mandatory check below rejects the write for a field the agent was never offered. See
    // McpBillToInjector for why this compensation lives in the MCP layer.
    McpBillToInjector.injectIfMissing(filteredBody, adTab, dalEntity, log);

    // Fix FK sentinel values: "0" is a UI-level sentinel (means "not yet set") that can't
    // go through the DAL as an entity reference. Replace with a real value from the body
    // when possible (e.g. documentType="0" -> copy from transactionDocument); keep it when "0" is
    // a real record of the target (the "*" organization), else remove.
    McpWriteRequestSupport.resolveFkSentinels(filteredBody, dalEntity, log);

    // Coerce string values to proper JSON types expected by the DAL (Long, BigDecimal, Boolean).
    // Callout cascade returns all values as strings, but DefaultJsonDataService/JsonToDataConverter
    // expects JSON numbers and booleans for numeric/boolean DAL properties.
    // IMP-24: userProvided is the pre-defaults snapshot, so it is also the only witness of which
    // date the agent actually sent. A server-injected default in a bad shape must not become a 422
    // the agent cannot act on.
    JSONArray invalidDates = McpWriteRequestSupport.coerceFieldTypes(filteredBody, dalEntity, userProvided, log);
    if (invalidDates.length() > 0) {
      return wrapAsErrorContent(McpWriteRequestSupport.buildInvalidDatesError(invalidDates));
    }

    // Validate mandatory fields before insert — return structured error matching etendo_schema contract
    JSONArray missingFields = McpWriteRequestSupport.validateMandatoryFields(filteredBody, adTab, dalEntity, SYSTEM_COLUMNS, SELECTOR_REFS, sfEntity, log);
    if (missingFields.length() > 0) {
      // IMP-5: stable machine-detectable code + status so the agent can distinguish an
      // "invalid write" from a "server error"; the human text moves to `detail`, and the
      // existing `missingFields`/`hint` guidance is preserved.
      JSONObject errorObj = new JSONObject();
      errorObj.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
      errorObj.put(McpConstants.KEY_ERROR, McpConstants.ERROR_VALIDATION);
      errorObj.put(McpConstants.KEY_DETAIL, "Missing required fields that could not be auto-resolved");
      errorObj.put("missingFields", missingFields);
      errorObj.put("hint", "Provide these fields in the request, or use etendo_selectors to find valid values for foreignKey fields");
      errorObj.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
      return wrapAsErrorContent(errorObj);
    }

    // Run the entity's NeoHandler pre-hook (parity with the REST CRUD path): it may
    // validate and mutate filteredBody (e.g. inject derived FK values) before persist.
    // ETP-5415: routed through NeoExtensionDispatcher so this write is traced like the REST
    // ones. Resolution is unchanged — the MCP channel still resolves via NeoHandlerLookup, which
    // is NOT the resolver the REST paths use — and so is the pre-hook contract: a NeoResponse
    // short-circuits, null proceeds to generic persistence, and the handler may have mutated the
    // body in place. The other five MCP hook sites are deliberately left alone in this step.
    NeoContext hookCtx = McpHookExecutor.buildHookContext(specName, entityName, HTTP_METHOD_POST, null, filteredBody, adTab, sfEntity);
    NeoExtensionRequest customizationRequest = NeoExtensionRequest.builder()
        .qualifier(sfEntity.getJavaQualifier())
        .specName(specName)
        .entityName(entityName)
        .surface(NeoExtensionSurface.CREATE)
        .channel(NeoExtensionChannel.MCP)
        .context(hookCtx)
        .build();
    NeoExtensionResult preDispatch = NeoExtensionDispatcher.dispatch(customizationRequest);
    NeoHandler handler = preDispatch.customization();
    if (preDispatch.response() != null) {
      return McpHookExecutor.neoResponseToMcpResult(preDispatch.response());
    }

    // Wrap for DefaultJsonDataService
    String wrappedBody = McpToolRouterSupport.wrapForSmartclient(filteredBody, dalEntityName, null, log);
    String result = jsonService.add(params, wrappedBody);
    JSONObject responseJson = new JSONObject(result);

    // userProvided is still the pre-defaults snapshot here (ETP-4793 / IMP-24's witness),
    // so it also answers "did the caller actually send this field" for a 422's fieldErrors —
    // a key absent from it can only have been filled in afterwards, by injectMandatoryDefaults
    // or the callout cascade above, never by the caller.
    JSONObject error = McpWriteRequestSupport.checkJsonServiceError(responseJson,
        McpConstants.SEE_ALSO_WRITING, NeoCrudHelper.snapshotBodyFields(userProvided));
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    fieldFilter.filterGetResponse(responseJson);

    JSONObject postHookResult =
        McpHookExecutor.runPostHook(customizationRequest.post(handler), responseJson);
    if (postHookResult != null) {
      return postHookResult;
    }

    // IMP-5 clause (iii): the post-hook still sees core's wrapped body, for parity with the REST
    // CRUD path a handler was written against; only the body handed to the agent is flattened.
    JSONObject flat = McpToolRouterSupport.flattenCoreResponse(responseJson);
    // ETP-5200: the id only exists in the response here, so it is read back from the flat body.
    McpRecordUrls.addRecordUrl(flat, specName, null,
        McpToolRouterSupport.isPrimaryTab(adTab));
    McpWriteRequestSupport.reportUnknownFields(flat, unknownWriteFields);
    // IMP-45: `ctx` is the context the callout cascade ran under, so it carries any field where the
    // caller's value stood and a callout had derived a different one. Create only — an update
    // carries no defaults invitation, and there is no cascade of this shape behind it.
    McpWriteRequestSupport.reportSupersededDefaults(flat, ctx.getSupersededDefaults());
    McpWriteRequestSupport.reportServerOwnedFields(flat, serverOwnedFields);
    return wrapAsTextContent(flat);
  }

  // ── etendo_update ────────────────────────────────────────────────────────

  /**
   * Update an existing record.
   */
  private JSONObject handleUpdate(String specName, JSONObject args) throws Exception {
    // ETP-5073 / DOC-04: `updated` joins the required set. Core's optimistic-locking check only
    // runs when the write payload carries it (JsonToDataConverter#setData guards on the key being
    // present), so an omission does not fail loudly — it silently disables the check and lets this
    // write overwrite a concurrent edit. Refusing the call is therefore the only safe answer, and
    // validateArgs already produces the 422 envelope that names the missing argument.
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY, "id",
        McpConstants.PARAM_FIELDS, McpConstants.PARAM_UPDATED);

    String entityName = args.getString(McpConstants.PARAM_ENTITY);
    String recordId = args.getString("id");
    JSONObject fields = args.getJSONObject(McpConstants.PARAM_FIELDS);
    String updated = args.getString(McpConstants.PARAM_UPDATED);

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);
    // ETP-4254: etendo_update maps to PUT, exactly as resolveAccessMethod does for the
    // role-level check — so the entity-level flag consulted here is ISPUT.
    McpToolRouterSupport.requireMethodEnabled(spec, sfEntity, HTTP_METHOD_PUT);
    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);

    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();
    NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(sfEntity, dalEntityName);

    Map<String, String> params = McpWriteRequestSupport.buildBaseParams(adTab, dalEntityName);

    // IMP-39: same exclusion gate as handleCreate — the write verbs and the read projection must
    // agree about which fields exist.
    // IMP-18: same reporting as handleCreate - a misspelt key is named, not swallowed.
    java.util.Set<String> unknownWriteFields = new java.util.TreeSet<>();
    // Same tenant rule as handleCreate. An update that moved client/organization would relocate
    // an existing record into another tenant, which is the same hole from the other direction.
    JSONObject serverOwnedFields = new JSONObject();
    JSONObject filteredBody = McpWriteRequestSupport.mapFieldsToDalProperties(fields, adTab,
        sfEntity, unknownWriteFields, serverOwnedFields);

    // Unlike handleCreate this path never runs injectMandatoryDefaults (see the IMP-16 note
    // further down), so every key filteredBody carries at this point is the caller's own — this
    // snapshot is a fixed reference set for a 422's fieldErrors, not a "before defaults" one.
    Set<String> updateUserProvidedFields = NeoCrudHelper.snapshotBodyFields(filteredBody);

    // IMP-4: resolve FK-by-name search strings before persist (mirrors handleCreate).
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(adTab.getTable().getId());

    // ETP-5184: same pre-FK image guard as handleCreate, and for the same reason — see there.
    JSONObject imageError = McpImageFieldSupport.validateImageFields(filteredBody, adTab, dalEntity);
    if (imageError != null) {
      return wrapAsErrorContent(imageError);
    }

    JSONObject fkError = McpFkResolver.resolveFkNames(filteredBody, dalEntity, adTab,
        McpSelectorContextHelper.buildSelectorContextParams(null, adTab), log);
    if (fkError != null) {
      return wrapAsErrorContent(fkError);
    }

    // ETP-4793 / IMP-16: the same coercion pass handleCreate runs, and for the same reason. Until
    // this call site existed the date branch was unreachable from etendo_update, so the agent's raw
    // string went straight to the DAL's lenient parser: orderDate "09-08-2026" was accepted under
    // status 0 and stored as 0015-02-16. The defect was never in the coercer — it was in the caller,
    // which is why IMP-16 read as fixed on emit and still corrupted on write. Unlike handleCreate
    // this path does not re-run injectMandatoryDefaults, so the caller's own value is the only
    // source of a non-canonical date here; that also makes it the only thing left to repair — and,
    // for IMP-24, the reason this path needs no separate witness: every key is the caller's, so
    // `null` says so directly rather than passing a copy of the body to be compared against itself.
    JSONArray invalidDates = McpWriteRequestSupport.coerceFieldTypes(filteredBody, dalEntity, null, log);
    if (invalidDates.length() > 0) {
      return wrapAsErrorContent(McpWriteRequestSupport.buildInvalidDatesError(invalidDates));
    }

    // Run the entity's NeoHandler pre-hook (parity with the REST CRUD path).
    // ETP-5415: routed through NeoExtensionDispatcher so this write is traced. Resolution and the
    // pre-hook contract are unchanged — any non-null response still short-circuits.
    NeoContext hookCtx = McpHookExecutor.buildHookContext(specName, entityName, HTTP_METHOD_PUT, recordId, filteredBody, adTab, sfEntity);
    NeoExtensionRequest extensionRequest = NeoExtensionRequest.builder()
        .qualifier(sfEntity.getJavaQualifier())
        .specName(specName)
        .entityName(entityName)
        .surface(NeoExtensionSurface.UPDATE)
        .channel(NeoExtensionChannel.MCP)
        .context(hookCtx)
        .build();
    NeoExtensionResult preDispatch = NeoExtensionDispatcher.dispatch(extensionRequest);
    NeoHandler handler = preDispatch.customization();
    if (preDispatch.response() != null) {
      return McpHookExecutor.neoResponseToMcpResult(preDispatch.response());
    }

    // ETP-5073 / DOC-04: the conflict is detected before the write, for the same reason the REST
    // path does it there — core's refusal reaches us as translated prose with nothing stable to
    // key on. See NeoRecordVersion.
    // Named requestRoute, not route: this class already has a route(..) method and a local of
    // that name would read like it.
    String requestRoute = NeoRecordVersion.routeOf(HTTP_METHOD_PUT, specName, entityName,
        recordId);
    if (NeoRecordVersion.isStale(dalEntityName, recordId, updated, requestRoute)) {
      return wrapAsErrorContent(McpWriteRequestSupport.buildStaleRecordError());
    }

    // ETP-5073 / DOC-04: injected here, deliberately last, so it never passes through the type
    // coercion pass above. That pass canonicalises date and datetime strings, and rewriting this
    // value by even a second would make every update look like a conflict, since the check
    // compares it for exact equality against the stored timestamp. It is also not a field the
    // caller is editing: core reads it, compares it, and then overwrites the column with its own
    // stamp on save. Keeping it out of the caller's field map is what makes that distinction
    // visible in the tool schema.
    filteredBody.put(McpConstants.PARAM_UPDATED, updated);

    // Wrap for DefaultJsonDataService with record ID
    String wrappedBody = McpToolRouterSupport.wrapForSmartclient(filteredBody, dalEntityName, recordId, log);
    String result = jsonService.update(params, wrappedBody);
    JSONObject responseJson = new JSONObject(result);

    JSONObject error = McpWriteRequestSupport.checkJsonServiceError(responseJson,
        McpConstants.SEE_ALSO_WRITING, updateUserProvidedFields);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    fieldFilter.filterGetResponse(responseJson);

    JSONObject postHookResult =
        McpHookExecutor.runPostHook(extensionRequest.post(handler), responseJson);
    if (postHookResult != null) {
      return postHookResult;
    }

    // IMP-5 clause (iii): the post-hook still sees core's wrapped body, for parity with the REST
    // CRUD path a handler was written against; only the body handed to the agent is flattened.
    JSONObject flat = McpToolRouterSupport.flattenCoreResponse(responseJson);
    McpWriteRequestSupport.reportUnknownFields(flat, unknownWriteFields);
    McpWriteRequestSupport.reportServerOwnedFields(flat, serverOwnedFields);
    return wrapAsTextContent(flat);
  }

  // ── etendo_delete ────────────────────────────────────────────────────────

  /**
   * Delete a record by ID.
   */
  private JSONObject handleDelete(String specName, JSONObject args) throws Exception {
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY, "id");

    String entityName = args.getString(McpConstants.PARAM_ENTITY);
    String recordId = args.getString("id");

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);
    // ETP-4254: entity-level DELETE flag, refused before any DAL work happens.
    McpToolRouterSupport.requireMethodEnabled(spec, sfEntity, HTTP_METHOD_DELETE);
    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);

    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();

    Map<String, String> params = McpWriteRequestSupport.buildBaseParams(adTab, dalEntityName);
    params.put(JsonConstants.ID, recordId);

    // Run the entity's NeoHandler pre-hook (parity with the REST CRUD path). A
    // handler may fully handle the delete (e.g. a soft-archive) or reject it.
    // ETP-5415: routed through NeoExtensionDispatcher. Note this surface has NO post-hook — the
    // delete returns its own payload and never calls afterHandle. Preserved as-is; see D14.
    NeoContext hookCtx = McpHookExecutor.buildHookContext(specName, entityName, HTTP_METHOD_DELETE, recordId, null, adTab, sfEntity);
    NeoExtensionResult preDispatch = NeoExtensionDispatcher.dispatch(NeoExtensionRequest.builder()
        .qualifier(sfEntity.getJavaQualifier())
        .specName(specName)
        .entityName(entityName)
        .surface(NeoExtensionSurface.DELETE)
        .channel(NeoExtensionChannel.MCP)
        .context(hookCtx)
        .build());
    // ETP-5474: DELETE-specific mapping, so a handler answering 204 No Content gets the same
    // confirmation as the generic path below instead of an empty `{}`.
    JSONObject preHookResult = McpHookExecutor.deletePreHookResult(preDispatch.response(), recordId);
    if (preHookResult != null) {
      return preHookResult;
    }

    String result = jsonService.remove(params);
    JSONObject responseJson = new JSONObject(result);

    JSONObject error = McpWriteRequestSupport.checkJsonServiceError(responseJson, McpConstants.SEE_ALSO_WRITING);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    return deleteConfirmation(recordId);
  }

  // ── etendo_selectors ─────────────────────────────────────────────────────

  /**
   * Query FK selector values for a column.
   * Resolves the AD_Column from the dictionary (AD_Tab → AD_Table → AD_Column),
   * bypassing ETGO_SF_FIELD so all FK columns are queryable — not just included ones.
   *
   * Supports optional recordContext for dependent selectors:
   * - partnerAddress: { "businessPartner": "..." }
   * - priceList: { "isSOTrx": "Y" } (auto-derived from window category if omitted)
   * - tax: { "invoiceDate": "2026-05-12", "priceList": "..." }
   * Also supports parentContext for child selectors that depend on header values.
   */
  private JSONObject handleSelectors(String specName, JSONObject args) throws Exception {
    // IMP-8: accept `field` as an alias for the canonical `column` argument so the natural
    // first-try call shape succeeds instead of failing on a missing-argument error.
    McpToolRouterSupport.aliasArg(args, McpConstants.PARAM_FIELD, McpConstants.PARAM_COLUMN);
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY);
    if (args == null || !args.has(McpConstants.PARAM_COLUMN)
        || args.isNull(McpConstants.PARAM_COLUMN)) {
      // Self-correcting error (IMP-8): name the expected key and the accepted alias.
      throw new IllegalArgumentException("Missing required argument: 'column' (the FK field "
          + "name, e.g. \"businessPartner\"). You may also pass it as 'field'.");
    }

    String entityName = args.getString(McpConstants.PARAM_ENTITY);
    String columnName = args.getString(McpConstants.PARAM_COLUMN);
    String query = args.optString(McpConstants.PARAM_QUERY, null);

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);
    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);

    // Find the AD_Column by DB column name or DAL property name (field name from schema)
    Entity dalEntity = ModelProvider.getInstance()
      .getEntityByTableName(adTab.getTable().getDBTableName());
    Column adColumn = McpSchemaFieldBuilder.findColumn(adTab, columnName, dalEntity);
    if (adColumn == null) {
      // ETP-5368: an entity may expose columns of a table other than its own tab's — the address
      // wrapper is backed by C_BPartner_Location while country and region live in C_Location. The
      // REST selector endpoint has consulted this policy since it was written; the MCP resolved
      // against the tab's table alone and so answered "Column not found in table: region" for a
      // field the handler accepts and the SPA's own selector returns.
      adColumn = NeoSelectorPolicy.resolveVirtualSelectorColumn(sfEntity, columnName);
    }
    if (adColumn == null) {
      // ETP-5558: the caller's mistake, not the server's — a 422 naming the selector columns.
      throw McpRoutingException.unknownSelectorColumn(columnName, entityName,
          McpSelectorContextHelper.selectorColumnNames(adTab, dalEntity, SELECTOR_REFS));
    }

    // Build contextParams from recordContext and window category
    Map<String, String> contextParams = McpSelectorContextHelper.buildSelectorContextParams(
        args, adTab);

    // ETP-5415: the SELECTOR surface, which until now reached an entity's customization over REST
    // and over no other channel. The consequence was not a missing feature but a silently
    // different answer: PaymentMethodSelectorSupport filters the candidate list down to the
    // pay-in or pay-out methods and fails closed when it cannot tell which — over MCP none of
    // that ran, so the agent was served every payment method of both directions and had no way to
    // know the list was wrong. Same dispatcher, same hook order as the REST path
    // (NeoHookDispatcher.executeHookChain): a pre-hook response replaces the query but is still
    // offered to the post-hook, because a customization that produces the whole candidate list is
    // exactly the one that may also want to enrich it.
    NeoExtensionRequest selectorRequest = NeoExtensionRequest.builder()
        .qualifier(sfEntity.getJavaQualifier())
        .specName(specName)
        .entityName(entityName)
        .surface(NeoExtensionSurface.SELECTOR)
        .channel(NeoExtensionChannel.MCP)
        .context(McpHookExecutor.buildSelectorHookContext(
            specName, entityName, columnName, contextParams, adTab, sfEntity))
        .build();

    NeoExtensionResult preDispatch = NeoExtensionDispatcher.dispatch(selectorRequest);

    NeoResponse neoResponse;
    if (preDispatch.response() != null) {
      neoResponse = preDispatch.response();
    } else {
      // ETP-5368: hand the source entity over rather than the column alone. See the javadoc on the
      // overload — passing null disables organisation context and every source-scoped selector
      // policy, which is how the MCP and the SPA ended up serving different candidate sets.
      neoResponse = NeoSelectorService.querySelectorByColumn(
          sfEntity, adColumn, columnName, query, 50, 0, contextParams);
    }

    if (preDispatch.customization() != null) {
      NeoExtensionResult postDispatch = NeoExtensionDispatcher.dispatch(
          selectorRequest.post(preDispatch.customization()).withPreviousResult(neoResponse));
      if (postDispatch.response() != null) {
        neoResponse = postDispatch.response();
      }
    }

    NeoResponse response = McpSelectorContextHelper.withDiagnostics(
        neoResponse, columnName, contextParams);
    return McpHookExecutor.neoResponseToMcpResult(response);
  }

  // ── etendo_defaults ──────────────────────────────────────────────────────

  /**
   * Get default field values for creating a new record.
   * Supports optional parentId for child entity defaults.
   */
  private JSONObject handleDefaults(String specName, JSONObject args) throws Exception {
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY);

    String entityName = args.getString(McpConstants.PARAM_ENTITY);
    String parentId = args.optString(McpConstants.PARAM_PARENT_ID, null);
    String assetId = args.optString(McpConstants.PARAM_ASSET_ID, null);

    // Build queryParams so NeoHandler implementations (e.g. AmortizationHeaderHandler)
    // can read named request params via NeoContext.getQueryParams().
    Map<String, String> queryParams = new HashMap<>();
    if (parentId != null) {
      queryParams.put(McpConstants.PARAM_PARENT_ID, parentId);
    }
    if (assetId != null) {
      queryParams.put(McpConstants.PARAM_ASSET_ID, assetId);
    }

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);
    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);

    // ETP-5558: defaults only exist to prepare a create; where MCP_CONFIG.verbs hides the create,
    // answer the same 405 etendo_create and view:"create" give instead of a starting point for a
    // record the agent cannot write.
    McpToolRouterSupport.requireVerbNotHidden(spec, sfEntity, HTTP_METHOD_POST);

    // ETP-5184: etendo_defaults on a child entity without parentId does not fail — it silently omits
    // every field whose default expression reads from the parent (the parent's warehouse, its
    // price-list version, its next line number). The agent then sends a create built on defaults
    // that were never resolved, and the create is the thing that fails, one call too late and with
    // a message about the wrong field. Refuse here instead, where the fix is a single argument.
    McpParentScope.Scope parentScope = McpParentScope.forEntity(sfEntity);
    if (parentScope.requiresParentFor(McpParentSection.VERB_CREATE)
        && StringUtils.isBlank(parentId)) {
      throw McpRoutingException.parentRequired(specName, entityName,
          parentScope.getParentEntity(), parentScope.getParentField());
    }

    NeoContext ctx = NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod(HTTP_METHOD_GET)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .queryParams(queryParams)
        .build();

    NeoResponse neoResponse = NeoDefaultsService.resolveDefaults(ctx, parentId);

    // Fire the entity's afterHandle hook for the DEFAULTS endpoint, mirroring the
    // REST path (NeoSubEndpointDispatcher → NeoHookDispatcher). This allows handlers
    // like AmortizationHeaderHandler to compute dynamic defaults (e.g. the header name
    // from assetId) over MCP, just as they do over REST.
    // ETP-5415: routed through NeoExtensionDispatcher for the trace and for the annotation-first
    // resolution order. This surface is post-only — no pre-hook exists here — and it builds its
    // context with buildDefaultsHookContext, not buildHookContext. Both preserved.
    NeoExtensionRequest defaultsRequest = NeoExtensionRequest.builder()
        .qualifier(sfEntity.getJavaQualifier())
        .specName(specName)
        .entityName(entityName)
        .surface(NeoExtensionSurface.DEFAULTS)
        .channel(NeoExtensionChannel.MCP)
        .context(McpHookExecutor.buildDefaultsHookContext(
            specName, entityName, adTab, sfEntity, queryParams))
        .build();
    NeoHandler handler = NeoExtensionDispatcher.resolveOnly(defaultsRequest);
    if (handler != null) {
      NeoExtensionResult afterDispatch = NeoExtensionDispatcher.dispatch(
          defaultsRequest.post(handler).withPreviousResult(neoResponse));
      if (afterDispatch.response() != null) {
        neoResponse = afterDispatch.response();
      }
    }

    // IMP-7: optional lean/grouped view. Without `view` (or view=full) the flat response is
    // returned unchanged; grouped/minimal split writable defaults (confirm) from server-managed
    // compliance flags so the agent isn't buried under ~65 columns it should never touch.
    String view = args.optString(McpDefaultsView.PARAM_VIEW, null);
    if (McpDefaultsView.isGroupingView(view) && neoResponse.getHttpStatus() < 400
        && neoResponse.getBody() != null) {
      java.util.Set<String> editable =
          McpQuerySupport.editablePropertyNames(sfEntity, adTab);
      neoResponse = NeoResponse.ok(
          McpDefaultsView.apply(neoResponse.getBody(), editable, view));
    }

    return McpHookExecutor.neoResponseToMcpResult(neoResponse);
  }

  // ── etendo_schema ─────────────────────────────────────────────────────────

  // AD_Reference ID for OBUISEL selectors (extends the base FK refs from NeoSelectorService)
  private static final java.util.Set<String> SELECTOR_REFS = new java.util.HashSet<>(
      java.util.Arrays.asList(NeoSelectorService.REF_TABLEDIR, NeoSelectorService.REF_TABLE,
          NeoSelectorService.REF_SEARCH, NeoSelectorService.REF_OBUISEL));

  // System/audit columns excluded from schema (auto-managed by Etendo)
  private static final java.util.Set<String> SYSTEM_COLUMNS = new java.util.HashSet<>(
      java.util.Arrays.asList(
          "AD_CLIENT_ID", "AD_ORG_ID", "ISACTIVE",
          "CREATED", "CREATEDBY", "UPDATED", "UPDATEDBY"));

  /**
   * Get the field schema for an entity from the AD dictionary.
   * Reads AD_Column metadata directly (same source as the Etendo classic UI form),
   * so the agent sees exactly the same fields the UI would show.
   */
  private JSONObject handleSchema(String specName, JSONObject args) throws Exception {
    // ETP-5468: a report spec whose handler declares named actions (bank-reconciliation) is
    // answered with its action catalog BEFORE the generic path, which rejects every SPEC_TYPE=R
    // spec as not CRUD-capable (resolveIncludedEntityOrExplain). Null for every other spec, which
    // then takes the unchanged path below.
    JSONObject declaredActionsSchema =
        McpReportActionsSchema.reportSpecActionsSchema(specName, args);
    if (declaredActionsSchema != null) {
      return declaredActionsSchema;
    }
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY);

    String entityName = args.getString(McpConstants.PARAM_ENTITY);

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);
    // ETP-5468: an entity whose handler declares named actions (bank-reconciliation) has no
    // field payload of its own — its AD tab is only there for role gating, and dumping that tab's
    // columns and buttons would advertise actions this entity does not serve. Its schema IS the
    // action catalog, whatever view was asked for.
    // ETP-5535 / ETP-5558: only when it has no field payload. A window entity whose customization
    // declares actions next to its AD buttons (sales-quotation/quotation, the invoice payment
    // actions) keeps its schema, and its declared actions join the AD buttons in view:"actions"
    // below; every other view is unchanged.
    Map<String, NeoActionContract> declaredActions = McpDeclaredActions.of(sfEntity);
    if (McpReportActionsSchema.isActionOnlyEntity(sfEntity, declaredActions)) {
      return wrapAsTextContent(
          McpActionsView.buildDeclaredResponse(specName, entityName, declaredActions,
              McpActionsSection.forEntity(sfEntity)));
    }
    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);

    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());

    // ETP-5184: resolved once, ahead of the view dispatch, because view:"create" returns early and
    // needs the same answer the full response publishes.
    McpParentScope.Scope parentScope = McpParentScope.forEntity(sfEntity);
    // ETP-5184: the entity-level agentPrompt (ETGO_SF_ENTITY.AGENT_PROMPT) used to reach
    // etendo_discover only, and discover is the catalogue an agent reads once at the start of a
    // session. etendo_schema is what it reads immediately before writing, so guidance that only lives
    // in discover is guidance the agent has already paged out. Resolved here, ahead of the view
    // dispatch, because view:"create" returns early and needs the same value. Trimmed and
    // blank-checked exactly as McpSupportInternals does, so an empty column emits no key.
    String entityAgentPrompt = StringUtils.trimToNull(sfEntity.getAgentPrompt());

    McpSchemaFieldBuilder.FieldMetadata fieldMetadata =
        McpSchemaFieldBuilder.loadFieldMetadata(sfEntity);
    Map<String, String> promptByColumnId =
        McpSchemaFieldBuilder.loadPromptByColumnId(sfEntity);
    Map<String, String> requiredWhenByField =
        McpSchemaFieldBuilder.loadPreconditionRequirements(sfEntity);
    JSONArray fieldsArray = McpSchemaFieldBuilder.buildSchemaFieldsArray(adTab, dalEntity,
        fieldMetadata, promptByColumnId, SYSTEM_COLUMNS, SELECTOR_REFS);
    // ETP-5368: an entity whose caller-facing fields live in a second table gets them appended
    // here, before every projection below, so view:"create" and view:"full" cannot disagree about
    // whether an address has a country. Empty for every entity without a wrapper policy.
    McpSchemaFieldBuilder.appendVirtualFields(fieldsArray,
        McpSchemaFieldBuilder.buildVirtualFieldsArray(sfEntity, adTab, SELECTOR_REFS));
    McpSchemaFieldBuilder.applyPreconditionRequirements(fieldsArray, requiredWhenByField);
    // IMP-1: overlay clean, localized labels + one-line descriptions from AD_Field so the agent
    // sees "SII Description" instead of the raw AD_Column name "EM_Aeatsii_Descripcion_Sii".
    McpSchemaFieldBuilder.applyCuratedLabels(fieldsArray,
        McpSchemaFieldBuilder.loadFieldLabels(adTab, NeoLanguage.currentCode()));
    // ETP-5558: MCP_CONFIG.actions shapes the buttons here, before any projection, so view:"full"
    // and its fields:[...] whitelist describe the same buttons view:"actions" does. Shaping only
    // the actions view let a blind agent read Void off the full view of a payment and offer it.
    McpActionsSection.View actionsConfig = McpActionsSection.forEntity(sfEntity);
    Set<String> excludedActions = McpDeclaredActions.excludedOf(sfEntity);
    fieldsArray = McpActionsView.applyConfig(fieldsArray, actionsConfig, excludedActions);
    // ETP-5587: a button its customization declares a contract for is described by that contract
    // (the parameter it reads, the values the SPA offers), not by its AD reference list.
    McpActionsView.describeDeclaredButtons(fieldsArray, declaredActions);

    // IMP-28 clause 4: computed off the full field array, before any view/fields narrowing
    // below, so a caller passing fields:[...] does not skew what the entity as a whole
    // supports. See the "methods" section for why this gates POST/PUT.
    boolean entityHasWritableField = McpSchemaResponseHints.hasAnyAgentSuppliableField(fieldsArray);

    // One dispatch point for every projection, so the views cannot drift apart. All of them are
    // pure post-filters on the fully-decorated fieldsArray above — no extra DAL access. Omitting
    // both `view` and `fields` returns the full response, byte-for-byte as before.
    String view = args.optString(McpActionsView.PARAM_VIEW, null);
    // IMP-6: view:"actions" collapses the dump down to the callable buttons/processes.
    if (McpActionsView.isActionsView(view)) {
      return wrapAsTextContent(McpActionsView.buildResponse(specName, entityName, fieldsArray,
          declaredActions, actionsConfig, excludedActions));
    }
    // IMP-12: view:"create" keeps only what the agent may actually send, split into
    // required/optional. 157 fields / 62 kB on sales-invoice/header collapses to the handful that
    // are the agent's to decide — the full response exceeds the client's token limit outright.
    if (McpSchemaCreateView.isCreateView(view)) {
      // ETP-5558: a create the MCP hides has no create contract to publish — answering with one
      // would draw the agent into the write it is about to be refused.
      McpToolRouterSupport.requireVerbNotHidden(spec, sfEntity, HTTP_METHOD_POST);
      // ETP-5184: ask the scope, not the tab level. tabLevel > 0 also catches the entities that
      // share their parent's record (contacts/customer and friends are all C_BPartner, 1:1), where
      // telling the agent to pass a parentId would send it looking for an argument that does not
      // apply. requiresParentFor("create") is the precise question the hint answers.
      boolean isChildEntity = parentScope.requiresParentFor(McpParentSection.VERB_CREATE);
      // ETP-5368: union the AD/etendo_defaults answer with the fields a wrapper handler resolves
      // itself. Both mean the same thing to the caller — "the server has this, do not ask the
      // user" — and only the second one knows that an address wrapper builds its own C_Location.
      // ETP-5535: the second set also carries what the entity's customization declares the create
      // callout cascade derives from another field of the body
      // (NeoHandler#serverResolvedCreateFields). The etendo_create pre-check deliberately does not skip
      // those: it runs after the cascade, so they are present there unless the cascade failed.
      Set<String> serverResolved = new HashSet<>(
          McpSchemaResponseHints.serverDefaultedNames(specName, entityName, adTab, sfEntity));
      serverResolved.addAll(McpServerResolvedFields.forCreate(sfEntity));
      return wrapAsTextContent(McpSchemaCreateView
          .buildResponse(specName, entityName, fieldsArray, serverResolved, isChildEntity,
              entityAgentPrompt));
    }
    // IMP-44: everything below is the full dump, and reaching it now requires having asked for
    // it. Omitting `view` used to land here — 39.5 kB on sales-order/header against 5.4 kB for
    // view:"create" — with the advice to use the cheaper projection delivered as a hint at the
    // bottom of the response the caller had already paid for. That advice has also been in this
    // tool's description since 2026-08-06 and three independent blind agents still took the full
    // route first, which is why the fix is the argument rather than more wording. The same check
    // catches an unrecognised value: view:"summary" is real on etendo_list/etendo_get and used to fall
    // through to here, answering a request for less with the largest response in the tool.
    if (!McpSchemaCreateView.isFullView(view)) {
      throw McpRoutingException.schemaViewRequired(view);
    }

    // IMP-12: fields:[…] — an explicit whitelist, for an agent that already knows what it wants.
    // Unmatched names are echoed back rather than dropped in silence (cf. IMP-18).
    Set<String> requestedFields = McpFieldProjection.parseFields(
        args.optJSONArray(McpSchemaCreateView.PARAM_FIELDS));
    JSONArray unknownFields = McpSchemaCreateView.unknownFields(fieldsArray, requestedFields);
    fieldsArray = McpSchemaCreateView.applyFieldWhitelist(fieldsArray, requestedFields);

    // Build entity schema
    JSONObject entitySchema = new JSONObject();
    entitySchema.put("spec", specName);
    entitySchema.put("entity", entityName);
    entitySchema.put("table", adTab.getTable().getDBTableName());
    // ETP-5184: alongside spec/entity/table rather than buried near the hint — for a
    // handler-backed entity this is the only place the AD-derived contract below can be
    // contradicted, so it must be read before the field list, not after it.
    if (entityAgentPrompt != null) {
      entitySchema.put("agentPrompt", entityAgentPrompt);
    }

    // Methods from SFEntity config
    JSONArray methods = new JSONArray();
    if (Boolean.TRUE.equals(sfEntity.isGet()) || Boolean.TRUE.equals(sfEntity.isGetByID())) {
      methods.put(HTTP_METHOD_GET);
    }
    // IMP-28 clause 4: ETGO_SF_ENTITY can enable POST/PUT while every individual field is
    // configured read-only (live evidence: product/stock — M_Storage_Detail is a computed
    // ledger, not a user-editable record — advertised methods:["GET","POST","PUT","DELETE"]
    // alongside view:"create" returning zero required/optional fields). Advertising a write
    // method an agent cannot actually use is worse than silence: it spends the write attempt
    // (and, post clause 2, gets rejected) before the agent learns anything. Gate POST/PUT on
    // "at least one field the agent may actually set", in addition to the raw entity flag.
    // DELETE is untouched — deleting a record never requires any field to be writable.
    // ETP-5558: through McpMethodPolicy, so a verb MCP_CONFIG.verbs hides is not advertised here
    // while the write verbs refuse it.
    if (McpMethodPolicy.isMethodEnabled(sfEntity, HTTP_METHOD_POST) && entityHasWritableField) {
      methods.put(HTTP_METHOD_POST);
    }
    if (McpMethodPolicy.isMethodEnabled(sfEntity, HTTP_METHOD_PUT) && entityHasWritableField) {
      methods.put(HTTP_METHOD_PUT);
    }
    if (McpMethodPolicy.isMethodEnabled(sfEntity, HTTP_METHOD_DELETE)) {
      methods.put(HTTP_METHOD_DELETE);
    }
    entitySchema.put("methods", methods);

    // ETP-5184: how this entity is addressed, in the same keys etendo_discover uses — isChild,
    // parentEntity, parentField, parentRequiredFor. etendo_schema is where an agent goes to learn how
    // to call something, so it is the one place the parent requirement must not be a surprise
    // discovered by getting a 422. Emitted only for child entities; a header tab adds nothing.
    McpParentScope.publishInto(entitySchema, parentScope);
    McpParentScope.publishConfigError(entitySchema, sfEntity);

    McpNamedFilters.publishInto(entitySchema, sfEntity.getNamedFilters());

    entitySchema.put("fields", fieldsArray);
    entitySchema.put("fieldCount", fieldsArray.length());
    if (unknownFields.length() > 0) {
      entitySchema.put("unknownFields", unknownFields);
    }

    // Usage hints
    // IMP-28: `visibility` is the authoritative key for what you may send — `readOnly` is ORed
    // from a structural check plus curated data plus visibility itself, so a field can be
    // readOnly:true for a reason visibility does not spell out; read visibility first. A
    // read-only field is not necessarily a dead end: when its value is derived from another
    // entity, `writableVia` names where to set it instead of silently giving up.
    // ETP-5184: said in prose as well as in parentRequiredFor, because this hint is the paragraph
    // an agent actually reads before its first call on an unfamiliar entity.
    // getParentEntity() can be null even for a RESOLVED scope — the parent tab exists and the FK is
    // identified, but that tab is not an included entity of this spec, so there is no name the
    // agent could call. Say "the parent record" rather than the literal "null".
    String parentHint = McpSchemaResponseHints.parentHint(parentScope);
    entitySchema.put("hint", parentHint
        + "Call etendo_schema with view:\"create\" to get only the fields you may send, already split "
        + "into required/optional — this full response is far larger than you need. "
        + "Fields with userRequired=true: MUST be provided in etendo_create. "
        + "Fields with visibility=system are auto-derived by Etendo callouts — omit them. "
        + "Fields with visibility=discarded are excluded — do not send them. "
        + "visibility=readOnly means you cannot set this field here — trust visibility over "
        + "readOnly, which only reports whether the value is locked, not why. "
        + "Fields with readOnly=true cannot be set by you: this covers auto-generated "
        + "identifiers (DocumentNo, IDs) as well as values derived/maintained elsewhere. "
        + "When such a field carries a writableVia pointer, it names the spec/entity where "
        + "the value is actually writable — call etendo_schema with view:\"create\" there instead "
        + "of giving up. "
        + "Use etendo_selectors for FK fields with hasSelector=true. "
        + "Fields with businessCritical=true carry core business data (amounts, categories, "
        + "key dates) — you MUST confirm these values with the user before creating or "
        + "modifying records. "
        // ETP-5306: stated here because etendo_schema is where an agent learns what a row looks
        // like, and the prebuilt reference field it used to read no longer exists.
        + McpConstants.RECORD_REF_NOTE);

    return wrapAsTextContent(entitySchema);
  }

  static String mapColumnTypeStatic(String refId) {
    return McpSchemaFieldBuilder.mapColumnType(refId);
  }

  static String mapSelectorTypeStatic(String refId) {
    return McpSchemaFieldBuilder.mapSelectorType(refId);
  }

  // ── etendo_batch ─────────────────────────────────────────────────────────

  /**
   * Execute a transactional batch of create operations across specs.
   * Delegates to {@link BatchService#executeBatch(JSONArray,
   * BatchService.OperationPreprocessor)} which owns the OBDal transaction lifecycle and returns a
   * JSONObject describing success (committed) or failure (rolled back), calling back into
   * {@link #preprocessBatchOperation} for each operation's MCP body transforms.
   *
   * <p>Package-private to keep the unit test free of reflection.</p>
   *
   * <p>OBDal session ownership: {@code BatchService} calls
   * {@code commitAndClose()} / {@code rollbackAndClose()} on the shared session.
   * That is safe here because {@link #route} performs no further DAL work after
   * this method returns — the only remaining step is
   * {@code OBContext.restorePreviousMode()} in the {@code finally} block.</p>
   */
  JSONObject handleBatch(JSONObject args) {
    if (args == null) {
      return wrapAsErrorContent("operations must be a non-empty array");
    }
    JSONArray operations = args.optJSONArray("operations");
    if (operations == null || operations.length() == 0) {
      return wrapAsErrorContent("operations must be a non-empty array");
    }
    try {
      // Per-spec access check: a single batch can mix specs from different
      // windows, and the top-level authorizeSpecAccess(null) on etendo_batch is a
      // no-op. Authorise each distinct spec before any DAL work happens so an
      // LLM agent cannot smuggle writes into a spec it lacks CRUD access to.
      // Every batch operation is a create (BatchService#processOperation only
      // ever calls createRecord — there is no update/delete op type), so this
      // is a write-tier ("POST") check: a read-only AD_Window_Access role must
      // be denied here exactly as it would be for a direct etendo_create call.
      java.util.Set<String> seen = new java.util.HashSet<>();
      for (int i = 0; i < operations.length(); i++) {
        JSONObject op = operations.optJSONObject(i);
        if (op == null) {
          continue;
        }
        String specName = op.optString("spec", null);
        if (StringUtils.isNotBlank(specName) && seen.add(specName)) {
          authorizeSpecAccess(specName, HTTP_METHOD_POST);
        }
      }
      // IMP-15 / ETP-5415: the body transforms that make etendo_batch accept exactly what etendo_create
      // accepts — FK-by-name and legacy-numeric ids, the UoM derivation, the bill-to and
      // line-price injections. They run per operation, from inside the batch loop, because that is
      // the first point where a $ref is resolved and a parentRef's parent exists; running them
      // over the whole array beforehand made every parent-dependent injection abstain in silence.
      // See BatchService#executeBatch(JSONArray, OperationPreprocessor).
      JSONObject result = BatchService.forBatchOnly()
          .executeBatch(operations, this::preprocessBatchOperation);
      if (!result.optBoolean("committed", false)) {
        // IMP-15: rewrite the failure in place into the IMP-5 envelope, so an agent gets a stable
        // error code instead of the raw DAL sub-response BatchService forwards to REST callers.
        McpBatchEnvelope.toMcpBatchFailure(result);
      }
      return wrapAsTextContent(result);
    } catch (SecurityException e) {
      log.warn("etendo_batch access denied session={}", McpUsageTelemetry.sessionForLog(), e);
      return wrapAsErrorContent(e.getMessage());
    } catch (Exception e) {
      log.error("Error executing etendo_batch session={}", McpUsageTelemetry.sessionForLog(), e);
      return wrapAsErrorContent("Error executing etendo_batch: " + e.getMessage());
    }
  }

  /**
   * Derive a commercial line's unit of measure from its product when the body omits it (IMP-15).
   * <p>
   * The MCP write verbs are the second and third create path in this module, and neither reaches
   * {@code NeoCrudHandler#executePostCreate} — where the REST path runs this same injection. Without
   * it, a line body that {@code etendo_schema} reports as complete is rejected by the {@code C_OrderLine}
   * trigger with AD message 20111, {@code "Unit of Measure mismatch (product/transaction)"}: the
   * trigger compares {@code M_PRODUCT.C_UOM_ID} against the row's {@code C_UOM_ID}, and {@code uOM}
   * is a {@code system}-visibility field, so no agent-visible contract ever mentions it.
   * <p>
   * Guarded on the entity actually declaring {@code uOM}, so an unrelated entity that happens to
   * carry a {@code product} field is never handed a property its table does not have. The policy
   * narrows it further to transactional document lines — declaring {@code uOM} is necessary but not
   * sufficient (see {@code NeoCommercialLinePolicy}).
   *
   * @param body            the DAL-shaped body, mutated in place
   * @param dalEntity       the target entity, used to confirm the property exists
   * @param userProvidedUom whether the caller itself sent a {@code uOM}. A {@code uOM} already
   *                        sitting in {@code body} does not imply this: the mandatory-defaults
   *                        pass preselects one from the combo, and that guess must lose to the
   *                        product — see {@code NeoCommercialLinePolicy}.
   */
  private void injectLineUomIfApplicable(JSONObject body, Entity dalEntity,
      boolean userProvidedUom) {
    if (body == null || dalEntity == null || !body.has(FIELD_PRODUCT)
        || !dalEntity.hasProperty(FIELD_UOM)
        || body.optString(FIELD_PRODUCT, "").startsWith(BatchService.REF_PREFIX)) {
      return;
    }
    NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(body, dalEntity, userProvidedUom);
  }

  /**
   * The MCP write-path transforms, applied to one batch operation immediately before it is written
   * (IMP-15, moved per-operation by ETP-5415).
   *
   * <p>Mirrors what {@code handleCreate} does for a single record, and now from the same starting
   * state: {@code BatchService} calls this once every {@code $ref} in the body is resolved and the
   * op's parent — created by an earlier operation — actually exists. It used to run over the whole
   * operations array before the batch opened its transaction, which meant a {@code $ref} was still
   * a placeholder and a {@code parentRef} pointed at nothing, so every parent-dependent injection
   * below abstained without saying so. The visible symptom was a batched order line persisted at
   * price 0 while the identical line created with a literal parent id priced correctly.
   *
   * <p>An op whose spec/entity cannot be resolved is left untouched rather than rejected here, so
   * {@code BatchService} still reports it with its own {@code failedAt} pointer instead of this
   * method changing the error shape for malformed input.
   *
   * <p>The body is mutated in place, so what this leaves behind is what gets persisted.
   *
   * @param op the operation about to be written
   * @return {@code null} when the body is ready to write, or the full batch outcome envelope built
   *         by {@link McpBatchEnvelope#toMcpBatchPreflightFailure(JSONObject, int, String)},
   *         which carries {@code committed:false} and the {@code failedAt} pointer so the agent
   *         reads this rejection exactly as it reads a failure from inside the batch (IMP-5
   *         clause (i))
   */
  private JSONObject preprocessBatchOperation(BatchService.OperationContext op)
      throws JSONException {
    JSONObject body = op.body();
    if (body == null || StringUtils.isBlank(op.specName())
        || StringUtils.isBlank(op.entityName())) {
      return null;
    }
    // Snapshotted BEFORE any injection below, because McpLinePriceInjector asks whether the AGENT
    // supplied a price — after an injection the body can no longer answer that. Safe to take here
    // even though ref substitution already ran: that replaces values, it never adds a field.
    Set<String> agentProvided = NeoCrudHelper.snapshotBodyFields(body);
    Tab adTab;
    Entity dalEntity;
    SFEntity sfEntity;
    SFSpec spec;
    try {
      spec = McpToolRouterSupport.findActiveSpecByName(op.specName());
      sfEntity = McpToolRouterSupport.findIncludedEntity(spec.getId(), op.entityName());
      adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, op.entityName());
      dalEntity = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
    } catch (Exception e) {
      log.debug("etendo_batch transforms skipped op {} ({}/{}): {}", op.index(), op.specName(),
          op.entityName(), e.getMessage());
      return null;
    }
    // ETP-5415: the curation gates, FIRST — before any injection, so they judge only what the
    // agent sent. etendo_create applies them inside mapFieldsToDalProperties; etendo_batch never calls
    // that method, so until now a batch could write a field the spec publishes as read-only that
    // etendo_create refuses with 422 (measured live on sales-order/lines: salesOrder). A batch more
    // permissive than a single create is the divergence class this ticket removes, and it only
    // became reachable when the tool was re-enabled. Refusals surface through the same batch
    // envelope as every other pre-write rejection.
    //
    // ETP-5558: the parent gate first of all. BatchService maps parentId/parentRef on its own and
    // never reaches resolveParentFK, so without this a batched child whose parent cannot be
    // identified — with or without a parentRef — is written with a link the defaults picked: the
    // payment-out/lines corruption, through the other door.
    //
    // ETP-5558: and the method gate before it — a create MCP_CONFIG.verbs hides is refused here
    // with the same 405 etendo_create returns (BatchService's own check reads only the raw flag).
    try {
      McpToolRouterSupport.requireMethodEnabled(spec, sfEntity, HTTP_METHOD_POST);
      McpWriteRequestSupport.requireApplicableParent(sfEntity, op.parentId());
      McpWriteRequestSupport.applyWriteGatesToDalBody(body, adTab, sfEntity, dalEntity);
    } catch (McpRoutingException e) {
      // toEnvelope(), not buildRoutingErrorBody(): the latter serialises to a String for a
      // single-tool response, and the batch envelope needs the object to nest under 'error'.
      JSONObject gateError = e.toEnvelope();
      gateError.put(McpConstants.KEY_TOOL, TOOL_NEO_BATCH);
      return McpBatchEnvelope.toMcpBatchPreflightFailure(gateError, op.index(), op.opId());
    }

    // This runs before any defaults pass has touched the body, so here a present uOM really is
    // the caller's own.
    injectLineUomIfApplicable(body, dalEntity, body.has(FIELD_UOM));
    // ETP-5535: the same parent selector context as handleCreate. The op's parent exists by now
    // (see above). It is read from op.parentId(), not from the body: a parentRef op carries its
    // parent nowhere in the body yet — BatchService injects it only when the record is created —
    // exactly the reason McpLinePriceInjector below takes op.parentId() too. A placeholder that is
    // somehow still unresolved is skipped and the op resolves with the pre-ETP-5535 context.
    JSONObject fkError = McpFkResolver.resolveFkNames(body, dalEntity, adTab,
        McpSelectorContextHelper.buildSelectorContextParams(
            McpParentSelectorContext.selectorArgs(sfEntity, dalEntity, body, op.parentId(),
                value -> value.startsWith(BatchService.REF_PREFIX)),
            adTab), log,
        value -> value.startsWith(BatchService.REF_PREFIX));
    if (fkError != null) {
      return McpBatchEnvelope.toMcpBatchPreflightFailure(fkError, op.index(), op.opId());
    }
    // ETP-5335: same derivation etendo_create runs, and it must run here too — etendo_batch never
    // reaches handleCreate, so without this a batched document is persisted with a null bill-to
    // instead of being refused, and the failure only surfaces later when C_INVOICE_CREATE copies
    // that null into C_Invoice.C_BPartner_Location_ID (NOT NULL). Placed after the FK pre-pass so
    // a business partner given by name is already an id. See McpBillToInjector.
    McpBillToInjector.injectIfMissing(body, adTab, dalEntity, log);

    // ETP-5415 (T6a): without it a batched commercial line is persisted at price 0 — the shared
    // create path derives a line's price from the product selector's aux values, which carry no
    // price-list context, so nothing downstream fills it and nothing complains. That silent-zero
    // shape is the divergence class that had etendo_batch switched off (ETP-5335); leaving it while
    // re-enabling the tool would have shipped the same defect back. Placed last, matching
    // handleCreate's order. See McpLinePriceInjector.
    McpLinePriceInjector.injectIfMissing(body, dalEntity, sfEntity, op.parentId(), agentProvided,
        log);

    // ETP-5415 (T6a): the remaining two steps etendo_create ran and etendo_batch did not, both named in
    // McpConstants#batchToolEnabled() as reasons the tool was switched off.
    //
    // FK sentinels first: "0" is a UI-level "not yet set" that the DAL cannot take as a reference,
    // so it must be resolved or dropped before the write, exactly as etendo_create does.
    McpWriteRequestSupport.resolveFkSentinels(body, dalEntity, log);

    // Then image fields. This one REFUSES rather than repairs, so it runs last among the body
    // transforms and reports through the same envelope as the FK failure above — one condition
    // must not have two shapes depending on which funnel caught it (IMP-5).
    JSONObject imageError = McpImageFieldSupport.validateImageFields(body, adTab, dalEntity);
    if (imageError != null) {
      return McpBatchEnvelope.toMcpBatchPreflightFailure(imageError, op.index(), op.opId());
    }
    return null;
  }

  // ── etendo_action ────────────────────────────────────────────────────────

  /**
   * Fire a button action on a record and return the structured process result.
   * <p>
   * Resolves the SFEntity from spec+entity arguments, validates the action column
   * exists (delegating to {@link NeoButtonActionHelper#executeButtonActionCore}),
   * then maps the NeoResponse body to MCP result keys:
   * <ul>
   *   <li>{@code processResult} — status from the process response (success|error|warning)</li>
   *   <li>{@code processMessage} — translated message from the process response</li>
   * </ul>
   * A validation or process error surfaces as {@code processResult: "error"} with
   * a descriptive {@code processMessage} — it is never swallowed.
   * <p>
   * Runs the entity's {@link NeoHandler} pre/post hooks around the action, matching the REST
   * action path ({@code NeoSubEndpointDispatcher.handleHookedSubEndpoint} with
   * {@code NeoEndpointType.ACTION}). A pre-hook {@link NeoResponse} short-circuits before the
   * process is fired; the post-hook may replace the result. Without this parity, completing a
   * document over MCP would skip handler logic the UI executes (ETP-4285).
   */
  JSONObject handleAction(String specName, JSONObject args) throws Exception {
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY, "id", "action");

    String entityName = args.getString(McpConstants.PARAM_ENTITY);
    String recordId = args.getString("id");
    String actionName = args.getString("action");
    JSONObject parameters = args.optJSONObject(McpConstants.PARAM_PARAMETERS);

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.findIncludedEntity(spec.getId(), entityName);
    // ETP-5558: judged before the customization runs — an action MCP_CONFIG.actions hides or
    // redirects is refused, and a declared one is validated against its contract (422 naming the
    // wrong key). The contract also says which HTTP method the handler answers it on.
    NeoActionContract declared = McpDeclaredActions.precheck(sfEntity, actionName, parameters);
    String httpMethod = declared != null ? declared.getHttpMethod()
        : NeoActionContract.DEFAULT_HTTP_METHOD;
    // ETP-5587: a contract that describes an AD button is run under its declared name, whichever
    // spelling the agent typed — the customization discriminates on that name.
    if (declared != null) {
      actionName = declared.getName();
    }
    // ETP-5558: another tenant's record is a 404 before the customization or the button sees it,
    // the same check the REST action path runs (NeoHookDispatcher).
    NeoResponse foreignRecord = NeoActionRecordGuard.refusalFor(sfEntity, recordId);
    if (foreignRecord != null) {
      return McpHookExecutor.neoResponseToMcpResult(foreignRecord);
    }

    // The body object is shared with executeButtonActionCore on purpose, so a handler that
    // normalizes or injects the action value is honoured by the process call that follows —
    // the same contract the REST path gives handlers.
    JSONObject actionParams = actionBody(declared, parameters);
    // ETP-5415: routed through NeoExtensionDispatcher. The action name is carried in the context
    // by buildActionHookContext; the dispatcher does not route on it, so a handler that serves
    // several buttons still discriminates internally, exactly as today.
    NeoContext hookCtx = McpHookExecutor.buildActionHookContext(specName, entityName, recordId,
        actionName, actionParams, sfEntity.getADTab(), sfEntity, httpMethod);
    NeoExtensionRequest actionRequest = NeoExtensionRequest.builder()
        .qualifier(sfEntity.getJavaQualifier())
        .specName(specName)
        .entityName(entityName)
        .surface(NeoExtensionSurface.ACTION)
        .channel(NeoExtensionChannel.MCP)
        .context(hookCtx)
        .build();
    NeoExtensionResult preDispatch = NeoExtensionDispatcher.dispatch(actionRequest);
    NeoHandler handler = preDispatch.customization();
    if (preDispatch.response() != null) {
      return McpHookExecutor.neoResponseToMcpResult(preDispatch.response());
    }

    NeoResponse neoResponse = NeoButtonActionHelper.executeButtonActionCore(
        sfEntity, recordId, actionName, actionParams);

    JSONObject actionResult = McpToolRouterSupport.mapNeoResponseToActionResult(neoResponse);

    if (neoResponse.getHttpStatus() >= 400) {
      if (!actionResult.has(McpConstants.KEY_PROCESS_RESULT)) {
        actionResult.put(McpConstants.KEY_PROCESS_RESULT, McpConstants.KEY_ERROR);
      }
      if (!actionResult.has(McpConstants.KEY_PROCESS_MESSAGE)) {
        actionResult.put(McpConstants.KEY_PROCESS_MESSAGE,
            "Request failed with HTTP status " + neoResponse.getHttpStatus());
      }
      return wrapAsErrorContent(actionResult);
    }

    // Post-hook only on the success path, mirroring handleCreate/handleUpdate, which both
    // return early on error before runPostHook.
    JSONObject postHookResult =
        McpHookExecutor.runPostHook(actionRequest.post(handler), actionResult);
    if (postHookResult != null) {
      return postHookResult;
    }

    return wrapAsTextContent(actionResult);
  }

  /**
   * The request body a {@code etendo_action} call reaches the customization and the button with: the
   * call's parameters, wrapped under {@code fieldValues} when the declared contract says its
   * customization reads them there — the body the SPA's process dialog posts (ETP-5587).
   *
   * @param declared   the action's declared contract, or {@code null}
   * @param parameters the call's parameters, may be {@code null}
   * @return the body; never {@code null}
   * @throws JSONException if the body cannot be built
   */
  static JSONObject actionBody(NeoActionContract declared, JSONObject parameters)
      throws JSONException {
    JSONObject flat = parameters != null ? parameters : new JSONObject();
    if (declared == null || !declared.isFieldValuesBody()) {
      return flat;
    }
    JSONObject body = new JSONObject();
    body.put(McpConstants.KEY_FIELD_VALUES, flat);
    return body;
  }

  // ── etendo_generate_amortization_plan ────────────────────────────────────

  /**
   * Handles the {@code etendo_generate_amortization_plan} MCP tool call.
   * Delegates to {@link AmortizationPlanService#generatePlan(String)}.
   *
   * @param arguments tool arguments containing {@code assetId}
   * @return MCP result object
   */
  private JSONObject handleGenerateAmortizationPlan(JSONObject arguments) throws Exception {
    String assetId = arguments != null ? arguments.optString("assetId", null) : null;
    NeoResponse response = AmortizationPlanService.generatePlan(assetId);
    if (response == null) {
      return wrapAsErrorContent("Internal error: service returned a null response");
    }
    if (response.getHttpStatus() >= 400) {
      // ETP-5306: the body goes through the JSONObject overload so it is sanitised like every
      // other tool result; only the no-body fallback is prose.
      return response.getBody() != null
          ? wrapAsErrorContent(response.getBody())
          : wrapAsErrorContent("Error generating amortization plan");
    }
    return wrapAsTextContent(response.getBody());
  }

  // ── Process execution ─────────────────────────────────────────────────

  /**
   * Execute a process-type spec.
   */
  private JSONObject handleProcess(String specName, JSONObject args) throws Exception {
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);

    Process adProcess = spec.getProcess();
    if (adProcess == null) {
      return wrapAsErrorContent("Process spec '" + specName + "' has no linked AD_Process");
    }

    // Check RBAC
    if (!NeoAccessUtils.hasProcessAccess(adProcess.getId())) {
      return wrapAsErrorContent("Access denied to process '" + specName
          + ACCESS_DENIED_FOR_CURRENT_ROLE_SUFFIX);
    }

    JSONObject parameters = args != null ? args.optJSONObject(McpConstants.PARAM_PARAMETERS) : null;
    NeoResponse neoResponse = NeoProcessService.executeProcess(adProcess, parameters);
    return McpHookExecutor.neoResponseToMcpResult(neoResponse);
  }

  // ── Report generation ─────────────────────────────────────────────────

  /**
   * Generate a report through its NEO-native report handler (ETP-4255).
   *
   * <p>Etendo Go/NEO/MCP no longer execute Jasper/AD_Process reports. A report spec is
   * callable only when it is backed by a NEO report handler ({@code NeoHandler} bean
   * matched by the entity's {@code Java_Qualifier}); the handler returns report data as
   * JSON. When the spec has no NEO-native handler it is non-callable: this returns the
   * exact same {@code not_configured_for_report_generation} message shown by discover.</p>
   */
  private JSONObject handleReport(String specName, JSONObject args) throws Exception {
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);

    // First included entity declaring a NEO report handler qualifier, or null.
    SFEntity reportEntity = null;
    for (SFEntity entity : McpToolRouterSupport.listIncludedEntities(spec.getId())) {
      if (StringUtils.isNotBlank(entity.getJavaQualifier())) {
        reportEntity = entity;
        break;
      }
    }
    // ETP-5415: this site RESOLVES but never dispatches — the handler is consulted for its report
    // contract, no hook is invoked. So it uses resolveOnly and emits no trace: a "dispatched" line
    // here would claim something that did not happen. It still needs the annotation-first order,
    // or an annotated report generator would be invisible to etendo_report while working elsewhere.
    NeoHandler handler = reportEntity != null
        ? NeoExtensionDispatcher.resolveOnly(NeoExtensionRequest.builder()
            .qualifier(reportEntity.getJavaQualifier())
            .specName(specName)
            .entityName(reportEntity.getName())
            .surface(NeoExtensionSurface.UNKNOWN)
            .channel(NeoExtensionChannel.MCP)
            .build())
        : null;
    if (handler == null) {
      // Non-callable report: identical message to etendo_discover. Not an error path.
      return wrapAsTextContent(
          NeoReportCallability.buildNotConfiguredResponse(specName));
    }

    // The handler's own declaration is the authority on what it accepts, and it is the same
    // object ToolRegistry built the tool schema from — so what the agent was shown and what it
    // is judged against cannot drift (ETP-4793 / IMP-19). A handler that declares no report
    // contract is not a report generator: it gets the not-configured answer rather than a POST
    // it can only reject.
    Optional<NeoReportContract> contract = NeoReportCallability.contractOf(handler,
        reportEntity.getJavaQualifier());
    if (contract.isEmpty()) {
      return wrapAsTextContent(
          NeoReportCallability.buildNotConfiguredResponse(specName));
    }

    JSONObject parameters = args != null ? args.optJSONObject(McpConstants.PARAM_PARAMETERS) : null;
    if (parameters == null) {
      parameters = new JSONObject();
    }

    JSONObject contractError = validateReportRequest(contract.get(), parameters,
        args != null ? args.optString(McpConstants.PARAM_FORMAT, null) : null);
    if (contractError != null) {
      return wrapAsErrorContent(contractError);
    }

    NeoContext ctx = NeoContext.builder()
        .specName(specName)
        .entityName(reportEntity.getName())
        .httpMethod(HTTP_METHOD_POST)
        .requestBody(parameters)
        .sfEntity(reportEntity)
        .obContext(OBContext.getOBContext())
        .build();
    NeoResponse neoResponse = handler.handle(ctx);
    if (neoResponse == null) {
      return wrapAsTextContent(
          NeoReportCallability.buildNotConfiguredResponse(specName));
    }
    return McpHookExecutor.neoResponseToMcpResult(neoResponse);
  }

  /**
   * Check a {@code generate_*} call against the handler's declared contract (ETP-4793 / IMP-19).
   *
   * <p>Two things used to fail silently or opaquely. A missing mandatory parameter reached the
   * handler and came back as its own ad-hoc 400 ({@code "dateFrom and dateTo are required"}) —
   * true, but in a shape no agent can branch on, and only for the handlers that bothered to
   * check. An unsupported {@code format} was not checked at all: the argument was declared in the
   * schema and never read, so a request for a PDF was answered with JSON and nothing said so.
   * Both now fail here, in the flat envelope the rest of the MCP surface uses
   * ({@code status}/{@code error}/{@code detail}), before the handler runs.</p>
   *
   * @param contract the handler's declared contract
   * @param params   the {@code parameters} object as submitted
   * @param format   the requested format, may be {@code null}
   * @return the error body to return, or {@code null} when the request satisfies the contract
   */
  private JSONObject validateReportRequest(NeoReportContract contract, JSONObject params,
      String format) throws JSONException {
    if (!contract.supportsFormat(format)) {
      JSONObject error = new JSONObject();
      error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
      error.put(McpConstants.KEY_ERROR, McpConstants.ERROR_VALIDATION);
      error.put(McpConstants.KEY_DETAIL,
          "Output format '" + format + "' is not served by this report");
      error.put(McpConstants.PARAM_FIELD, McpConstants.PARAM_FORMAT);
      error.put("supportedFormats", new JSONArray(contract.getFormats()));
      error.put(McpConstants.KEY_HINT, "Etendo returns report data as JSON; it does not render documents. "
          + "Omit 'format' or pass '" + contract.getDefaultFormat() + "'.");
      return error;
    }

    JSONArray missing = new JSONArray();
    for (String name : contract.getRequiredParameterNames()) {
      // An empty string is as absent as a missing key here: every handler reads these with
      // optString(name, "") and treats "" as unset, so accepting it would only move the failure
      // back into the handler's own error path.
      if (StringUtils.isBlank(params.optString(name, ""))) {
        missing.put(name);
      }
    }
    if (missing.length() == 0) {
      return null;
    }

    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    error.put(McpConstants.KEY_ERROR, McpConstants.ERROR_VALIDATION);
    error.put(McpConstants.KEY_DETAIL, "Missing required report parameters");
    error.put("missingParameters", missing);
    error.put(McpConstants.KEY_HINT, "These are declared in this tool's parameters schema, with their expected "
        + "types and accepted values.");
    return error;
  }

  /**
   * Read-tier ({@code GET}) spec authorization. Prefer
   * {@link #authorizeSpecAccess(String, String)} whenever the caller knows the
   * MCP tool's write intent.
   */
  private void authorizeSpecAccess(String specName) throws Exception {
    authorizeSpecAccess(specName, HTTP_METHOD_GET);
  }

  /**
   * Authorizes the current role against {@code specName} for the given HTTP-method
   * equivalent, enforcing the read-only vs. full-access {@code AD_Window_Access}
   * tiering (ETP-4510) via {@link McpToolRouterSupport#hasSpecAccess(SFSpec, String, String)}.
   *
   * @param specName   the spec name resolved from the tool call (blank/{@code null} is a no-op —
   *                   some tools, e.g. {@code etendo_discover}, have no single spec to authorize)
   * @param httpMethod the HTTP-method equivalent of the MCP operation being authorized
   *                   (e.g. {@code "POST"} for {@code etendo_create})
   */
  private void authorizeSpecAccess(String specName, String httpMethod) throws Exception {
    if (StringUtils.isBlank(specName)) {
      return;
    }
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    if (!McpToolRouterSupport.hasSpecAccess(spec, spec.getSpecType(), httpMethod)) {
      throw new SecurityException("Access denied to spec '" + specName
          + ACCESS_DENIED_FOR_CURRENT_ROLE_SUFFIX);
    }
  }

  /**
   * Maps an MCP tool name to the HTTP-method equivalent used for
   * {@code AD_Window_Access} read/write tiering (ETP-4510). Mutating CRUD tools map to
   * their REST NEO Headless counterpart; every other tool (reads, process/report
   * execution, discovery) is treated as a read for window-access purposes — process and
   * report access are authorized separately and are unaffected by this method string.
   *
   * @param toolName the MCP tool name (e.g. {@code "etendo_create"})
   * @return {@code "POST"}, {@code "PUT"}, or {@code "DELETE"} for the corresponding
   *         mutating tool; {@code "GET"} for everything else
   */
  private static String resolveAccessMethod(String toolName) {
    switch (toolName) {
      case "etendo_create":
        return HTTP_METHOD_POST;
      case "etendo_update":
        return HTTP_METHOD_PUT;
      case "etendo_delete":
        return HTTP_METHOD_DELETE;
      default:
        return HTTP_METHOD_GET;
    }
  }

  // ── MCP content formatting ────────────────────────────────────────────

  /**
   * Wrap a JSON body as MCP tool result content.
   *
   * <p>ETP-5306: this overload, not {@link #wrapAsTextContent(String)}, is what every tool that
   * answers with JSON must call. It is the single egress where {@link McpResponseSanitizer} removes
   * the keys an MCP client may not receive — today {@code $ref}, which Gemini treats as a pointer
   * into {@code function_response.parts} and which made it reject every response carrying a record.
   * Rendering the body here rather than at the call site is what makes that guarantee total: a
   * caller that hands over the object cannot forget the sanitisation, and the object is sanitised
   * before it is serialised, so no number is re-parsed on the way out (see the class javadoc of
   * {@link McpResponseSanitizer}).</p>
   *
   * @param body the tool-result body ({@code null} renders as an empty object)
   * @return MCP text content
   */
  static JSONObject wrapAsTextContent(JSONObject body) {
    try {
      return wrapAsTextContent(McpResponseSanitizer.render(body));
    } catch (JSONException e) {
      throw new McpToolException("Error building MCP content", e);
    }
  }

  /**
   * Wrap a JSON error body as MCP tool result content with the {@code isError} flag.
   *
   * <p>ETP-5306: the error counterpart of {@link #wrapAsTextContent(JSONObject)}, sanitised for the
   * same reason — an error envelope can echo a record (a stale-record conflict, a handler's own
   * body), and a single surviving {@code $ref} rejects the whole response.</p>
   *
   * @param body the error body ({@code null} renders as an empty object)
   * @return MCP error content
   */
  static JSONObject wrapAsErrorContent(JSONObject body) {
    try {
      return wrapAsErrorContent(McpResponseSanitizer.render(body));
    } catch (JSONException e) {
      throw new McpToolException(ERROR_BUILDING_CONTENT, e);
    }
  }

  /**
   * Wrap a text string as MCP tool result content.
   *
   * <p>For bodies that are genuinely text (the {@code docs} passthrough, a prose message). Anything
   * that is a JSON document must go through {@link #wrapAsTextContent(JSONObject)} instead, which
   * is where response sanitisation happens.</p>
   */
  static JSONObject wrapAsTextContent(String text) {
    try {
      JSONObject content = new JSONObject();
      content.put("type", "text");
      content.put("text", text);

      JSONObject result = new JSONObject();
      JSONArray contentArray = new JSONArray();
      contentArray.put(content);
      result.put("content", contentArray);
      return result;
    } catch (JSONException e) {
      // Should never happen with string values
      throw new McpToolException("Error building MCP content", e);
    }
  }

  /**
   * Wrap an error message as MCP tool result content with isError flag.
   */
  static JSONObject wrapAsErrorContent(String message) {
    try {
      JSONObject content = new JSONObject();
      content.put("type", "text");
      content.put("text", message);

      JSONObject result = new JSONObject();
      JSONArray contentArray = new JSONArray();
      contentArray.put(content);
      result.put("content", contentArray);
      result.put("isError", true);
      return result;
    } catch (JSONException e) {
      throw new McpToolException(ERROR_BUILDING_CONTENT, e);
    }
  }
}
