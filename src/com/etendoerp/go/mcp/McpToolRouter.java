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
import com.etendoerp.go.schemaforge.NeoCommercialLinePolicy;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoReportContract;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoDefaultsService;
import com.etendoerp.go.schemaforge.DocTypeResolver;
import com.etendoerp.go.schemaforge.NeoFieldFilter;
import com.etendoerp.go.schemaforge.NeoMandatoryDefaultsService;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoProcessService;
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
 *   <li>{@code neo_discover} — list all accessible specs</li>
 *   <li>{@code neo_list} — list records (GET)</li>
 *   <li>{@code neo_get} — get single record by ID</li>
 *   <li>{@code neo_create} — create a record (POST)</li>
 *   <li>{@code neo_update} — update a record (PUT)</li>
 *   <li>{@code neo_delete} — delete a record (DELETE)</li>
 *   <li>{@code neo_selectors} — query FK selector values</li>
 *   <li>{@code neo_defaults} — get default field values for new records</li>
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
  private static final String FIELD_PRODUCT = "product";
  private static final String FIELD_UOM = "uOM";


  /**
   * Route a tool call to its handler.
   * <p>
   * For CRUD tools (neo_list, neo_get, etc.), the spec name is extracted from the
   * "spec" argument. For process and report tools, the spec name is derived from
   * the tool name itself via {@link ToolRegistry#resolveSpecName}.
   *
   * @param toolName  MCP tool name (e.g. "neo_list", "complete_order")
   * @param arguments tool arguments (may be null)
   * @param scopes    OAuth2 scopes granted to this call
   * @return MCP result object with "content" array
   */
  public JSONObject route(String toolName, JSONObject arguments, java.util.Set<String> scopes) {
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
          case "neo_discover":
            return handleDiscover();
          case "neo_list":
            return handleList(specName, arguments);
          case "neo_get":
            return handleGet(specName, arguments);
          case "neo_create":
            return handleCreate(specName, arguments);
          case "neo_update":
            return handleUpdate(specName, arguments);
          case "neo_delete":
            return handleDelete(specName, arguments);
          case "neo_selectors":
            return handleSelectors(specName, arguments);
          case "neo_defaults":
            return handleDefaults(specName, arguments);
          case "neo_schema":
            return handleSchema(specName, arguments);
          case "neo_batch":
            // Withdrawing it from tools/list is not enough: an agent that learned the name
            // elsewhere would still reach the handler, and a silent success on a path we chose
            // not to maintain is worse than the refusal.
            if (!McpConstants.BATCH_TOOL_ENABLED) {
              return wrapAsErrorContent(McpRouterErrorBodies.batchDisabled());
            }
            return handleBatch(arguments);
          case "neo_action":
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
      log.warn("MCP tool '{}' addressed something that does not exist: {}", toolName, e.getMessage());
      return wrapAsErrorContent(buildRoutingErrorBody(e, toolName));
    } catch (SecurityException e) {
      // An authorization refusal is a permanent answer for this role, not a server failure. It
      // used to fall into the generic handler below and surface as 500 server_error, whose own
      // hint invites no retry but whose status class does: a client with a retry-on-5xx rule
      // loops forever on a decision that will never change.
      log.warn("MCP tool '{}' refused for the current role: {}", toolName, e.getMessage());
      return wrapAsErrorContent(McpRouterErrorBodies.forbidden(toolName, e.getMessage()));
    } catch (org.openbravo.base.exception.OBSecurityException e) {
      // Openbravo's own refusal does NOT extend SecurityException, so without this clause it
      // reached the generic handler and answered 500 for the same kind of decision.
      log.warn("MCP tool '{}' refused by the platform for the current role: {}",
          toolName, e.getMessage());
      return wrapAsErrorContent(McpRouterErrorBodies.forbidden(toolName, e.getMessage()));
    } catch (Exception e) {
      log.error("Error routing MCP tool '{}'", toolName, e);
      return wrapAsErrorContent(buildUnexpectedErrorBody(toolName, e));
    }
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
          + "Use neo_list or neo_selectors to find the record instead.");
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
      // came here without reading neo_schema. One constant, declared once per response, instead
      // of a `$ref` field repeated on every row.
      return wrapAsTextContent(McpConstants.RECORD_REF_NOTE + "\n\n" + body);
    } catch (Exception e) {
      log.error("Error fetching docs for topic '{}'", topic, e);
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

  // ── neo_discover ──────────────────────────────────────────────────────

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

  // ── neo_list ──────────────────────────────────────────────────────────

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
    // McpParentScope has carried VERB_LIST since it was written, and neo_discover advertises
    // "parentRequiredFor":["list","get",...] on 89 entities — but nothing ever enforced it here,
    // and neo_list had no parentId argument to satisfy it with. So an agent that asked for one
    // order's lines got EVERY order's lines, with nothing in the response to say the scope had
    // been dropped: a confident, wrong answer that reads exactly like a correct one. Worse than a
    // refusal, because the caller then acts on rows belonging to records it never asked about.
    filters = scopeListToParent(specName, entityName, sfEntity, parentId, filters);

    // ETP-5405: a tab-less entity is served by its handler, exactly as it is over REST. Runs after
    // the parent gate above so the scope check still applies, and before the tab is demanded.
    JSONObject handled = McpTablessReadDispatcher.run(specName, entityName, null, sfEntity,
        McpTablessReadDispatcher.buildParams(filters, parentId, offset, limit, orderBy));
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

    // Apply tab-level HQL where clause
    String tabWhere = adTab.getHqlwhereclause();
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

    String result = jsonService.fetch(params);
    JSONObject responseJson = new JSONObject(result);

    // Check for errors
    JSONObject error = McpWriteRequestSupport.checkJsonServiceError(responseJson, McpConstants.SEE_ALSO_READING);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    // Apply field filtering
    fieldFilter.filterGetResponse(responseJson);

    // IMP-2: optional projection — explicit `fields:[...]` or view:"summary". No-op when neither
    // is present, so the default returns every column.
    // IMP-18: the filter is passed in so an unknown requested name is reported, not dropped.
    McpQuerySupport.applyProjection(responseJson, args, sfEntity, adTab, fieldFilter);

    // IMP-5 clause (iii): flatten last, so projection and field filtering keep operating on the
    // wrapped shape core produced and only the body handed to the agent changes.
    return wrapAsTextContent(
        McpToolRouterSupport.flattenCoreResponse(responseJson));
  }

  // ── neo_get ───────────────────────────────────────────────────────────

  /**
   * Get a single record by ID.
   */
  private JSONObject handleGet(String specName, JSONObject args) throws Exception {
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY, "id");

    String entityName = args.getString(McpConstants.PARAM_ENTITY);
    String recordId = args.getString("id");

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);

    // ETP-5405: see McpTablessReadDispatcher.run — the handler answers before the tab
    // is demanded.
    JSONObject handled = McpTablessReadDispatcher.run(specName, entityName, recordId, sfEntity,
        McpTablessReadDispatcher.buildParams(null, null, null, null, null));
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

  // ── neo_create ────────────────────────────────────────────────────────

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
   * untouched. For a gated child with no {@code parentId}, the call is refused with the same
   * {@code parent_required} envelope {@code neo_defaults} already uses — one wording, one copy.
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
    if (filters != null && filters.has(parentField) && !filters.isNull(parentField)) {
      return filters;
    }
    if (StringUtils.isBlank(parentId)) {
      throw McpRoutingException.parentRequired(specName, entityName,
          scope.getParentEntity(), parentField);
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
    // neo_defaults/neo_list/neo_get taught it had its parent link silently dropped — and then
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
    // to an entity configured read-only (which neo_discover already reports as readOnly).
    McpToolRouterSupport.requireMethodEnabled(spec, sfEntity, HTTP_METHOD_POST);
    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);

    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();
    NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(sfEntity, dalEntityName);

    Map<String, String> params = McpWriteRequestSupport.buildBaseParams(adTab, dalEntityName);

    // IMP-39: the spec's exclusions are honoured here. This used to accept every valid table
    // column "not just SF-configured ones", which is what let a curated-out field be written and
    // filtered while neo_get denied it existed. A column with no ETGO_SF_FIELD row is still
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
    JSONObject fkError = McpFkResolver.resolveFkNames(filteredBody, dalEntity, adTab,
        McpSelectorContextHelper.buildSelectorContextParams(null, adTab), log);
    if (fkError != null) {
      return wrapAsErrorContent(fkError);
    }

    // Snapshot user-provided fields BEFORE callout cascade can overwrite them.
    // Callouts derive dependent fields (e.g. product → tax, UOM) and may reset them
    // to sentinel "0" even when the user explicitly provided valid values.
    JSONObject userProvided = new JSONObject(filteredBody.toString());

    // Resolve parentId if present
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
    // when possible (e.g. documentType="0" -> copy from transactionDocument), or remove.
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

    // Validate mandatory fields before insert — return structured error matching neo_schema contract
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
      errorObj.put("hint", "Provide these fields in the request, or use neo_selectors to find valid values for foreignKey fields");
      errorObj.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
      return wrapAsErrorContent(errorObj);
    }

    // Run the entity's NeoHandler pre-hook (parity with the REST CRUD path): it may
    // validate and mutate filteredBody (e.g. inject derived FK values) before persist.
    NeoHandler handler = McpHookExecutor.resolveEntityHandler(sfEntity);
    NeoContext hookCtx = McpHookExecutor.buildHookContext(specName, entityName, HTTP_METHOD_POST, null, filteredBody, adTab, sfEntity);
    JSONObject preHookResult = McpHookExecutor.runPreHook(handler, hookCtx);
    if (preHookResult != null) {
      return preHookResult;
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

    JSONObject postHookResult = McpHookExecutor.runPostHook(handler, hookCtx, responseJson);
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

  // ── neo_update ────────────────────────────────────────────────────────

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
    // ETP-4254: neo_update maps to PUT, exactly as resolveAccessMethod does for the
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
    // this call site existed the date branch was unreachable from neo_update, so the agent's raw
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
    NeoHandler handler = McpHookExecutor.resolveEntityHandler(sfEntity);
    NeoContext hookCtx = McpHookExecutor.buildHookContext(specName, entityName, HTTP_METHOD_PUT, recordId, filteredBody, adTab, sfEntity);
    JSONObject preHookResult = McpHookExecutor.runPreHook(handler, hookCtx);
    if (preHookResult != null) {
      return preHookResult;
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

    JSONObject postHookResult = McpHookExecutor.runPostHook(handler, hookCtx, responseJson);
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

  // ── neo_delete ────────────────────────────────────────────────────────

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
    NeoHandler handler = McpHookExecutor.resolveEntityHandler(sfEntity);
    NeoContext hookCtx = McpHookExecutor.buildHookContext(specName, entityName, HTTP_METHOD_DELETE, recordId, null, adTab, sfEntity);
    JSONObject preHookResult = McpHookExecutor.runPreHook(handler, hookCtx);
    if (preHookResult != null) {
      return preHookResult;
    }

    String result = jsonService.remove(params);
    JSONObject responseJson = new JSONObject(result);

    JSONObject error = McpWriteRequestSupport.checkJsonServiceError(responseJson, McpConstants.SEE_ALSO_WRITING);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    JSONObject deleteResult = new JSONObject();
    deleteResult.put("deleted", true);
    deleteResult.put("id", recordId);
    return wrapAsTextContent(deleteResult);
  }

  // ── neo_selectors ─────────────────────────────────────────────────────

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
      throw new IllegalArgumentException("Column not found in table: " + columnName);
    }

    // Build contextParams from recordContext and window category
    Map<String, String> contextParams = McpSelectorContextHelper.buildSelectorContextParams(
        args, adTab);

    // ETP-5368: hand the source entity over rather than the column alone. See the javadoc on the
    // overload — passing null disables organisation context and every source-scoped selector
    // policy, which is how the MCP and the SPA ended up serving different candidate sets.
    NeoResponse neoResponse = NeoSelectorService.querySelectorByColumn(
        sfEntity, adColumn, columnName, query, 50, 0, contextParams);

    NeoResponse response = McpSelectorContextHelper.withDiagnostics(
        neoResponse, columnName, contextParams);
    return McpHookExecutor.neoResponseToMcpResult(response);
  }

  // ── neo_defaults ──────────────────────────────────────────────────────

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

    // ETP-5184: neo_defaults on a child entity without parentId does not fail — it silently omits
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
    NeoHandler handler = McpHookExecutor.resolveEntityHandler(sfEntity);
    if (handler != null) {
      NeoContext hookCtx = McpHookExecutor.buildDefaultsHookContext(
          specName, entityName, adTab, sfEntity, queryParams);
      hookCtx.setPreviousResult(neoResponse);
      NeoResponse afterResult = handler.afterHandle(hookCtx);
      if (afterResult != null) {
        neoResponse = afterResult;
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

  // ── neo_schema ─────────────────────────────────────────────────────────

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
    JSONObject declaredActionsSchema = reportSpecActionsSchema(specName, args);
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
    Map<String, NeoActionContract> declaredActions = declaredActionsOf(sfEntity);
    if (!declaredActions.isEmpty()) {
      return wrapAsTextContent(
          McpActionsView.buildDeclaredResponse(specName, entityName, declaredActions));
    }
    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);

    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());

    // ETP-5184: resolved once, ahead of the view dispatch, because view:"create" returns early and
    // needs the same answer the full response publishes.
    McpParentScope.Scope parentScope = McpParentScope.forEntity(sfEntity);
    // ETP-5184: the entity-level agentPrompt (ETGO_SF_ENTITY.AGENT_PROMPT) used to reach
    // neo_discover only, and discover is the catalogue an agent reads once at the start of a
    // session. neo_schema is what it reads immediately before writing, so guidance that only lives
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
      return wrapAsTextContent(
          McpActionsView.buildResponse(specName, entityName, fieldsArray));
    }
    // IMP-12: view:"create" keeps only what the agent may actually send, split into
    // required/optional. 157 fields / 62 kB on sales-invoice/header collapses to the handful that
    // are the agent's to decide — the full response exceeds the client's token limit outright.
    if (McpSchemaCreateView.isCreateView(view)) {
      // ETP-5184: ask the scope, not the tab level. tabLevel > 0 also catches the entities that
      // share their parent's record (contacts/customer and friends are all C_BPartner, 1:1), where
      // telling the agent to pass a parentId would send it looking for an argument that does not
      // apply. requiresParentFor("create") is the precise question the hint answers.
      boolean isChildEntity = parentScope.requiresParentFor(McpParentSection.VERB_CREATE);
      // ETP-5368: union the AD/neo_defaults answer with the fields a wrapper handler resolves
      // itself. Both mean the same thing to the caller — "the server has this, do not ask the
      // user" — and only the second one knows that an address wrapper builds its own C_Location.
      Set<String> serverResolved = new HashSet<>(
          McpSchemaResponseHints.serverDefaultedNames(specName, entityName, adTab, sfEntity));
      serverResolved.addAll(NeoSelectorPolicy.serverResolvedFieldNames(sfEntity));
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
    // catches an unrecognised value: view:"summary" is real on neo_list/neo_get and used to fall
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
    if (Boolean.TRUE.equals(sfEntity.isPost()) && entityHasWritableField) {
      methods.put(HTTP_METHOD_POST);
    }
    if (Boolean.TRUE.equals(sfEntity.isPut()) && entityHasWritableField) {
      methods.put(HTTP_METHOD_PUT);
    }
    if (Boolean.TRUE.equals(sfEntity.isDelete())) {
      methods.put(HTTP_METHOD_DELETE);
    }
    entitySchema.put("methods", methods);

    // ETP-5184: how this entity is addressed, in the same keys neo_discover uses — isChild,
    // parentEntity, parentField, parentRequiredFor. neo_schema is where an agent goes to learn how
    // to call something, so it is the one place the parent requirement must not be a surprise
    // discovered by getting a 422. Emitted only for child entities; a header tab adds nothing.
    McpParentScope.publishInto(entitySchema, parentScope);

    // Named business filters (ETP-4601): advertise the spec's hand-authored status filters,
    // each keyed by name, so the agent can discover them instead of guessing. Only the
    // name/label/description are exposed — the HQL where fragment stays server-side.
    JSONArray namedFilters = McpNamedFilters.describe(sfEntity.getNamedFilters());
    if (namedFilters.length() > 0) {
      entitySchema.put("namedFilters", namedFilters);
    }

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
        + "Call neo_schema with view:\"create\" to get only the fields you may send, already split "
        + "into required/optional — this full response is far larger than you need. "
        + "Fields with userRequired=true: MUST be provided in neo_create. "
        + "Fields with visibility=system are auto-derived by Etendo callouts — omit them. "
        + "Fields with visibility=discarded are excluded — do not send them. "
        + "visibility=readOnly means you cannot set this field here — trust visibility over "
        + "readOnly, which only reports whether the value is locked, not why. "
        + "Fields with readOnly=true cannot be set by you: this covers auto-generated "
        + "identifiers (DocumentNo, IDs) as well as values derived/maintained elsewhere. "
        + "When such a field carries a writableVia pointer, it names the spec/entity where "
        + "the value is actually writable — call neo_schema with view:\"create\" there instead "
        + "of giving up. "
        + "Use neo_selectors for FK fields with hasSelector=true. "
        + "Fields with businessCritical=true carry core business data (amounts, categories, "
        + "key dates) — you MUST confirm these values with the user before creating or "
        + "modifying records. "
        // ETP-5306: stated here because neo_schema is where an agent learns what a row looks
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

  // ── neo_batch ─────────────────────────────────────────────────────────

  /**
   * Execute a transactional batch of create operations across specs.
   * Delegates to {@link BatchService#executeBatch(JSONArray)} which owns the
   * OBDal transaction lifecycle and returns a JSONObject describing success
   * (committed) or failure (rolled back).
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
      // windows, and the top-level authorizeSpecAccess(null) on neo_batch is a
      // no-op. Authorise each distinct spec before any DAL work happens so an
      // LLM agent cannot smuggle writes into a spec it lacks CRUD access to.
      // Every batch operation is a create (BatchService#processOperation only
      // ever calls createRecord — there is no update/delete op type), so this
      // is a write-tier ("POST") check: a read-only AD_Window_Access role must
      // be denied here exactly as it would be for a direct neo_create call.
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
      // IMP-15: resolve FK-by-name / legacy-numeric-id values in every op body before the
      // transaction opens, so neo_batch accepts exactly the formats neo_create does. Without this
      // the batch path handed the raw value to the DAL, which failed with an import-set error
      // naming the value it could not resolve — a different contract for the same field.
      JSONObject fkError = resolveBatchFkNames(operations);
      if (fkError != null) {
        // IMP-5 clause (i): reported through the same outcome envelope as a failure inside
        // executeBatch, and as text rather than error content for the same reason — one condition
        // must not have two shapes depending on which funnel caught it.
        return wrapAsTextContent(fkError);
      }
      JSONObject result = BatchService.forBatchOnly().executeBatch(operations);
      if (!result.optBoolean("committed", false)) {
        // IMP-15: rewrite the failure in place into the IMP-5 envelope, so an agent gets a stable
        // error code instead of the raw DAL sub-response BatchService forwards to REST callers.
        McpToolRouterSupport.toMcpBatchFailure(result);
      }
      return wrapAsTextContent(result);
    } catch (SecurityException e) {
      log.warn("neo_batch access denied", e);
      return wrapAsErrorContent(e.getMessage());
    } catch (Exception e) {
      log.error("Error executing neo_batch", e);
      return wrapAsErrorContent("Error executing neo_batch: " + e.getMessage());
    }
  }

  /**
   * Derive a commercial line's unit of measure from its product when the body omits it (IMP-15).
   * <p>
   * The MCP write verbs are the second and third create path in this module, and neither reaches
   * {@code NeoCrudHandler#executePostCreate} — where the REST path runs this same injection. Without
   * it, a line body that {@code neo_schema} reports as complete is rejected by the {@code C_OrderLine}
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
   * Run the shared FK resolver — and the shared line-policy injection — over every operation body of
   * a batch (IMP-15).
   * <p>
   * Mirrors what {@code handleCreate} does for a single record, with two batch-specific rules:
   * <ul>
   *   <li>{@code "$ref:<opId>"} placeholders are skipped — the op they point at has not run yet, so
   *       the value is resolvable as neither an id nor a name (see {@code BatchService#REF_PREFIX}).</li>
   *   <li>An op whose spec/entity cannot be resolved is left untouched instead of erroring here, so
   *       {@code BatchService} still reports it with its own {@code failedAt} pointer rather than
   *       this pass changing the error shape for malformed input.</li>
   * </ul>
   * Bodies are mutated in place, so the resolved ids are what {@code executeBatch} persists.
   *
   * @param operations the {@code operations} array from the tool call
   * @return {@code null} when every body resolved, or — for the first op that failed — the full batch
   *         outcome envelope built by
   *         {@link McpToolRouterSupport#toMcpBatchPreflightFailure(JSONObject, int, String)}, which
   *         carries {@code committed:false} and the {@code failedAt} pointer so the agent reads this
   *         rejection exactly as it reads a failure from inside the batch (IMP-5 clause (i))
   */
  private JSONObject resolveBatchFkNames(JSONArray operations) throws JSONException {
    for (int i = 0; i < operations.length(); i++) {
      JSONObject fkError = resolveBatchOpFkNames(operations, i);
      if (fkError != null) {
        return fkError;
      }
    }
    return null;
  }

  /**
   * Run the FK pre-pass for a single batch operation (extracted from {@link #resolveBatchFkNames}
   * so the loop there carries a single exit point, not one {@code continue} per skip reason).
   *
   * @return the batch outcome envelope when this op's FK resolution failed, or {@code null} when
   *         the op was skipped (malformed, unresolved spec/entity) or resolved cleanly
   */
  private JSONObject resolveBatchOpFkNames(JSONArray operations, int i) throws JSONException {
    JSONObject op = operations.optJSONObject(i);
    if (op == null) {
      return null;
    }
    JSONObject body = op.optJSONObject("body");
    String specName = op.optString("spec", null);
    String entityName = op.optString(McpConstants.PARAM_ENTITY, null);
    if (body == null || StringUtils.isBlank(specName) || StringUtils.isBlank(entityName)) {
      return null;
    }
    Tab adTab;
    Entity dalEntity;
    try {
      SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
      SFEntity sfEntity = McpToolRouterSupport.findIncludedEntity(spec.getId(), entityName);
      adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);
      dalEntity = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
    } catch (Exception e) {
      log.debug("neo_batch FK pre-pass skipped op {} ({}/{}): {}", i, specName, entityName,
          e.getMessage());
      return null;
    }
    // This pre-pass runs on the raw operation body, before any defaults pass has touched it, so
    // here a present uOM really is the caller's own.
    injectLineUomIfApplicable(body, dalEntity, body.has(FIELD_UOM));
    JSONObject fkError = McpFkResolver.resolveFkNames(body, dalEntity, adTab,
        McpSelectorContextHelper.buildSelectorContextParams(null, adTab), log,
        value -> value.startsWith(BatchService.REF_PREFIX));
    if (fkError != null) {
      return McpToolRouterSupport.toMcpBatchPreflightFailure(fkError, i,
          op.optString("id", null));
    }
    // ETP-5335: same derivation neo_create runs, and it must run here too — neo_batch never
    // reaches handleCreate, so without this a batched document is persisted with a null bill-to
    // instead of being refused, and the failure only surfaces later when C_INVOICE_CREATE copies
    // that null into C_Invoice.C_BPartner_Location_ID (NOT NULL). Placed after the FK pre-pass so
    // a business partner given by name is already an id. See McpBillToInjector.
    McpBillToInjector.injectIfMissing(body, adTab, dalEntity, log);
    return null;
  }

  /**
   * {@code neo_schema} for a report spec whose handler declares named actions (ETP-5468): the
   * action catalog of the requested entity. When {@code entity} is omitted and exactly one included
   * entity declares actions, that one is used; with none or several, {@code null} lets the generic
   * path answer (its "Missing required argument: entity" stays the error).
   *
   * @return the catalog, or {@code null} when the spec is not such a spec / the entity declares none
   */
  private static JSONObject reportSpecActionsSchema(String specName, JSONObject args)
      throws JSONException {
    SFSpec spec;
    try {
      spec = McpToolRouterSupport.findActiveSpecByName(specName);
    } catch (Exception e) {
      return null;
    }
    if (spec == null || !"R".equals(spec.getSpecType())) {
      return null;
    }
    // Only a spec that actually declares actions is handled here. Every other report spec returns
    // before any entity lookup, so its neo_schema answer stays exactly the generic path's (e.g.
    // the 422 pointing at its generate_* tool) — BUG-4.
    if (!NeoActionContract.resolve(spec).isPresent()) {
      return null;
    }
    String requested = args != null
        ? StringUtils.trimToNull(args.optString(McpConstants.PARAM_ENTITY, null)) : null;
    SFEntity target = null;
    Map<String, NeoActionContract> contracts = java.util.Collections.emptyMap();
    if (requested != null) {
      target = McpToolRouterSupport.findIncludedEntity(spec.getId(), requested);
      contracts = target != null ? declaredActionsOf(target) : contracts;
    } else {
      int declaring = 0;
      for (SFEntity candidate : McpToolRouterSupport.listIncludedEntities(spec.getId())) {
        Map<String, NeoActionContract> c = declaredActionsOf(candidate);
        if (!c.isEmpty()) {
          declaring++;
          target = candidate;
          contracts = c;
        }
      }
      if (declaring != 1) {
        return null;
      }
    }
    if (target == null || contracts.isEmpty()) {
      return null;
    }
    return wrapAsTextContent(
        McpActionsView.buildDeclaredResponse(specName, target.getName(), contracts));
  }

  /**
   * The named actions the entity's handler declares (ETP-5468), or an empty map. Looked up quietly:
   * a CDI failure must not break {@code neo_schema} for an ordinary entity.
   */
  private static Map<String, NeoActionContract> declaredActionsOf(SFEntity sfEntity) {
    NeoHandler handler = NeoHandlerLookup.byQualifierQuietly(sfEntity.getJavaQualifier());
    Map<String, NeoActionContract> contracts = handler != null ? handler.actionContracts() : null;
    return contracts != null ? contracts : java.util.Collections.emptyMap();
  }

  // ── neo_action ────────────────────────────────────────────────────────

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

    // The body object is shared with executeButtonActionCore on purpose, so a handler that
    // normalizes or injects the action value is honoured by the process call that follows —
    // the same contract the REST path gives handlers.
    JSONObject actionParams = parameters != null ? parameters : new JSONObject();
    NeoHandler handler = McpHookExecutor.resolveEntityHandler(sfEntity);
    NeoContext hookCtx = McpHookExecutor.buildActionHookContext(specName, entityName, recordId,
        actionName, actionParams, sfEntity.getADTab(), sfEntity);
    JSONObject preHookResult = McpHookExecutor.runPreHook(handler, hookCtx);
    if (preHookResult != null) {
      return preHookResult;
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
    JSONObject postHookResult = McpHookExecutor.runPostHook(handler, hookCtx, actionResult);
    if (postHookResult != null) {
      return postHookResult;
    }

    return wrapAsTextContent(actionResult);
  }

  // ── neo_generate_amortization_plan ────────────────────────────────────

  /**
   * Handles the {@code neo_generate_amortization_plan} MCP tool call.
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
    NeoHandler handler = reportEntity != null
        ? McpHookExecutor.resolveEntityHandler(reportEntity) : null;
    if (handler == null) {
      // Non-callable report: identical message to neo_discover. Not an error path.
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
      error.put(McpConstants.KEY_HINT, "Etendo Go returns report data as JSON; it does not render documents. "
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
   *                   some tools, e.g. {@code neo_discover}, have no single spec to authorize)
   * @param httpMethod the HTTP-method equivalent of the MCP operation being authorized
   *                   (e.g. {@code "POST"} for {@code neo_create})
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
   * @param toolName the MCP tool name (e.g. {@code "neo_create"})
   * @return {@code "POST"}, {@code "PUT"}, or {@code "DELETE"} for the corresponding
   *         mutating tool; {@code "GET"} for everything else
   */
  private static String resolveAccessMethod(String toolName) {
    switch (toolName) {
      case "neo_create":
        return HTTP_METHOD_POST;
      case "neo_update":
        return HTTP_METHOD_PUT;
      case "neo_delete":
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
