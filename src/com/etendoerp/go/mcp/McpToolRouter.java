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

import java.util.Collections;
import java.util.HashMap;
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
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.go.schemaforge.util.NeoErrorSanitizer;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoReportContract;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoDefaultsService;
import com.etendoerp.go.schemaforge.NeoFieldFilter;
import com.etendoerp.go.schemaforge.NeoMandatoryDefaultsService;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
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
    try {
      OBContext.setAdminMode();
      try {
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
            return handleBatch(arguments);
          case "neo_action":
            return handleAction(specName, arguments);
          case McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN:
            return handleGenerateAmortizationPlan(arguments);
          case McpConstants.TOOL_NEO_WIDGET:
            return McpWidgetHandler.handle(arguments);
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
    } catch (Exception e) {
      log.error("Error routing MCP tool '{}'", toolName, e);
      return wrapAsErrorContent(buildUnexpectedErrorBody(toolName, e));
    }
  }

  /**
   * Render a routing failure, falling back to the old prose line only if the envelope cannot be
   * serialised (ETP-4793 / IMP-17).
   */
  private String buildRoutingErrorBody(McpRoutingException e, String toolName) {
    try {
      JSONObject envelope = e.toEnvelope();
      envelope.put(McpConstants.KEY_TOOL, toolName);
      return envelope.toString(2);
    } catch (JSONException jsonEx) {
      log.error("Could not build routing error envelope for '{}'", toolName, jsonEx);
      return "Error executing " + toolName + ": " + e.getMessage();
    }
  }

  /**
   * Render anything else thrown out of a tool call as the IMP-5 envelope (ETP-4793 / IMP-17).
   *
   * <p>This is the last leak IMP-5 left open: every unanticipated failure came back as the bare line
   * {@code "Error executing neo_list: …"} (evidence C14), so an agent could not tell a mistake it
   * could fix from a server fault it could not, and had to parse prose to find out. The code is
   * deliberately {@code server_error} rather than {@code validation_error}: if the router could have
   * told the caller what to change, one of the typed paths above would already have done it, and
   * inviting a retry-with-corrections here would send the agent round a loop that cannot terminate.
   * The message is sanitised on the way out — an unexpected failure is exactly where a DB internal
   * or a row dump would otherwise reach the client.</p>
   */
  private String buildUnexpectedErrorBody(String toolName, Exception e) {
    try {
      JSONObject envelope = new JSONObject();
      envelope.put(McpConstants.KEY_STATUS, McpConstants.STATUS_SERVER_ERROR);
      envelope.put(McpConstants.KEY_ERROR, McpConstants.ERROR_SERVER);
      envelope.put(McpConstants.KEY_DETAIL, NeoErrorSanitizer.sanitize(e));
      envelope.put(McpConstants.KEY_TOOL, toolName);
      envelope.put(McpConstants.KEY_HINT, "This is a server-side failure, not a bad request — "
          + "re-sending the same call with corrected values will not help.");
      return envelope.toString(2);
    } catch (JSONException jsonEx) {
      log.error("Could not build error envelope for '{}'", toolName, jsonEx);
      return "Error executing " + toolName + ": " + e.getMessage();
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
      return wrapAsTextContent(body);
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
    return wrapAsTextContent(result.toString(2));
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

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);
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
      return wrapAsErrorContent(error.toString(2));
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
        McpToolRouterSupport.flattenCoreResponse(responseJson).toString(2));
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
      return wrapAsErrorContent(error.toString(2));
    }

    // IMP-5: a get-by-id that matched nothing comes back as {data:[], status:0} — a
    // success-looking empty result. Translate it into an explicit, machine-detectable
    // not-found so the agent can tell "not found" from "empty match" and self-correct.
    if (McpToolRouterSupport.isEmptySuccessResult(responseJson)) {
      return wrapAsErrorContent(
          McpToolRouterSupport.buildNotFoundError(specName, entityName, recordId).toString(2));
    }

    fieldFilter.filterGetResponse(responseJson);

    // IMP-2: optional projection — explicit `fields:[...]` or view:"summary".
    // IMP-18: unknown requested names are reported as unknownFields — lifted to the top level by
    // the flatten below, along with the rest of the wrapper's contents.
    McpQuerySupport.applyProjection(responseJson, args, sfEntity, adTab, fieldFilter);

    // IMP-5 clause (iii): see handleList — flatten last, after every stage that reads the wrapper.
    return wrapAsTextContent(
        McpToolRouterSupport.flattenCoreResponse(responseJson).toString(2));
  }

  // ── neo_create ────────────────────────────────────────────────────────

  /**
   * Create a new record.
   */
  private JSONObject handleCreate(String specName, JSONObject args) throws Exception {
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY, McpConstants.PARAM_FIELDS);

    String entityName = args.getString(McpConstants.PARAM_ENTITY);
    JSONObject fields = args.getJSONObject(McpConstants.PARAM_FIELDS);

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

    // MCP: accept all valid table columns from AI agents, not just SF-configured ones.
    // filterWriteRequest strips fields not in ETGO_SF_FIELD writableFields, which is
    // too restrictive for MCP where AI agents need to set any valid column.
    JSONObject filteredBody = McpWriteRequestSupport.mapFieldsToDalProperties(fields, adTab);

    // Hoisted so both FK-by-name resolution (IMP-4, below) and the sentinel/coercion passes
    // further down share one DAL entity lookup.
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(adTab.getTable().getId());

    // IMP-4: resolve FK-by-name search strings (e.g. businessPartner:"Acme Corp") into real
    // record ids before anything downstream touches them. A value that already looks like an id
    // is left untouched. See McpFkResolver's class javadoc for the selector-context limitation.
    JSONObject fkError = McpFkResolver.resolveFkNames(filteredBody, dalEntity, adTab,
        McpSelectorContextHelper.buildSelectorContextParams(null, adTab), log);
    if (fkError != null) {
      return wrapAsErrorContent(fkError.toString(2));
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
      McpWriteRequestSupport.resolveParentFK(adTab, filteredBody, parentIdValue, log);
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

    // userProvided is the pre-defaults snapshot, so it is the only reliable witness of whether the
    // agent actually chose a uOM.
    injectLineUomIfApplicable(filteredBody, dalEntity, userProvided.has(FIELD_UOM));

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
      return wrapAsErrorContent(McpWriteRequestSupport.buildInvalidDatesError(invalidDates).toString(2));
    }

    // Validate mandatory fields before insert — return structured error matching neo_schema contract
    JSONArray missingFields = McpWriteRequestSupport.validateMandatoryFields(filteredBody, adTab, dalEntity, SYSTEM_COLUMNS, SELECTOR_REFS, log);
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
      return wrapAsErrorContent(errorObj.toString(2));
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

    JSONObject error = McpWriteRequestSupport.checkJsonServiceError(responseJson, McpConstants.SEE_ALSO_WRITING);
    if (error != null) {
      return wrapAsErrorContent(error.toString(2));
    }

    fieldFilter.filterGetResponse(responseJson);

    JSONObject postHookResult = McpHookExecutor.runPostHook(handler, hookCtx, responseJson);
    if (postHookResult != null) {
      return postHookResult;
    }

    // IMP-5 clause (iii): the post-hook still sees core's wrapped body, for parity with the REST
    // CRUD path a handler was written against; only the body handed to the agent is flattened.
    return wrapAsTextContent(
        McpToolRouterSupport.flattenCoreResponse(responseJson).toString(2));
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

    // MCP: accept all valid table columns from AI agents
    JSONObject filteredBody = McpWriteRequestSupport.mapFieldsToDalProperties(fields, adTab);

    // IMP-4: resolve FK-by-name search strings before persist (mirrors handleCreate).
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(adTab.getTable().getId());
    JSONObject fkError = McpFkResolver.resolveFkNames(filteredBody, dalEntity, adTab,
        McpSelectorContextHelper.buildSelectorContextParams(null, adTab), log);
    if (fkError != null) {
      return wrapAsErrorContent(fkError.toString(2));
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
      return wrapAsErrorContent(McpWriteRequestSupport.buildInvalidDatesError(invalidDates).toString(2));
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
    if (NeoRecordVersion.isStale(dalEntityName, recordId, updated)) {
      return wrapAsErrorContent(McpWriteRequestSupport.buildStaleRecordError().toString(2));
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

    JSONObject error = McpWriteRequestSupport.checkJsonServiceError(responseJson, McpConstants.SEE_ALSO_WRITING);
    if (error != null) {
      return wrapAsErrorContent(error.toString(2));
    }

    fieldFilter.filterGetResponse(responseJson);

    JSONObject postHookResult = McpHookExecutor.runPostHook(handler, hookCtx, responseJson);
    if (postHookResult != null) {
      return postHookResult;
    }

    // IMP-5 clause (iii): the post-hook still sees core's wrapped body, for parity with the REST
    // CRUD path a handler was written against; only the body handed to the agent is flattened.
    return wrapAsTextContent(
        McpToolRouterSupport.flattenCoreResponse(responseJson).toString(2));
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
      return wrapAsErrorContent(error.toString(2));
    }

    JSONObject deleteResult = new JSONObject();
    deleteResult.put("deleted", true);
    deleteResult.put("id", recordId);
    return wrapAsTextContent(deleteResult.toString(2));
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
      throw new IllegalArgumentException("Column not found in table: " + columnName);
    }

    // Build contextParams from recordContext and window category
    Map<String, String> contextParams = McpSelectorContextHelper.buildSelectorContextParams(
        args, adTab);

    NeoResponse neoResponse = NeoSelectorService.querySelectorByColumn(
        adColumn, columnName, query, 50, 0, contextParams);

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
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_ENTITY);

    String entityName = args.getString(McpConstants.PARAM_ENTITY);

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity sfEntity = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, entityName);
    Tab adTab = McpWriteRequestSupport.getAdTabOrThrow(sfEntity, entityName);

    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());

    McpSchemaFieldBuilder.FieldMetadata fieldMetadata =
        McpSchemaFieldBuilder.loadFieldMetadata(sfEntity);
    Map<String, String> promptByColumnId =
        McpSchemaFieldBuilder.loadPromptByColumnId(sfEntity);
    Map<String, String> requiredWhenByField =
        McpSchemaFieldBuilder.loadPreconditionRequirements(sfEntity);
    JSONArray fieldsArray = McpSchemaFieldBuilder.buildSchemaFieldsArray(adTab, dalEntity,
        fieldMetadata.visibilityByColumnId, fieldMetadata.businessCriticalByColumnId,
        fieldMetadata.readOnlyByColumnId, promptByColumnId, SYSTEM_COLUMNS, SELECTOR_REFS);
    McpSchemaFieldBuilder.applyPreconditionRequirements(fieldsArray, requiredWhenByField);
    // IMP-1: overlay clean, localized labels + one-line descriptions from AD_Field so the agent
    // sees "SII Description" instead of the raw AD_Column name "EM_Aeatsii_Descripcion_Sii".
    McpSchemaFieldBuilder.applyCuratedLabels(fieldsArray,
        McpSchemaFieldBuilder.loadFieldLabels(adTab, NeoLanguage.currentCode()));

    // IMP-28 clause 4: computed off the full field array, before any view/fields narrowing
    // below, so a caller passing fields:[...] does not skew what the entity as a whole
    // supports. See the "methods" section for why this gates POST/PUT.
    boolean entityHasWritableField = hasAnyAgentSuppliableField(fieldsArray);

    // One dispatch point for every projection, so the views cannot drift apart. All of them are
    // pure post-filters on the fully-decorated fieldsArray above — no extra DAL access. Omitting
    // both `view` and `fields` returns the full response, byte-for-byte as before.
    String view = args.optString(McpActionsView.PARAM_VIEW, null);
    // IMP-6: view:"actions" collapses the dump down to the callable buttons/processes.
    if (McpActionsView.isActionsView(view)) {
      return wrapAsTextContent(
          McpActionsView.buildResponse(specName, entityName, fieldsArray).toString(2));
    }
    // IMP-12: view:"create" keeps only what the agent may actually send, split into
    // required/optional. 157 fields / 62 kB on sales-invoice/header collapses to the handful that
    // are the agent's to decide — the full response exceeds the client's token limit outright.
    if (McpSchemaCreateView.isCreateView(view)) {
      boolean isChildEntity = adTab.getTabLevel() != null && adTab.getTabLevel() > 0;
      return wrapAsTextContent(McpSchemaCreateView
          .buildResponse(specName, entityName, fieldsArray,
              serverDefaultedNames(specName, entityName, adTab, sfEntity), isChildEntity)
          .toString(2));
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
    entitySchema.put("hint",
        "Call neo_schema with view:\"create\" to get only the fields you may send, already split "
        + "into required/optional — this full response is far larger than you need. "
        + "Fields with userRequired=true: MUST be provided in neo_create. "
        + "Fields with visibility=system are auto-derived by Etendo callouts — omit them. "
        + "Fields with visibility=discarded are excluded — do not send them. "
        + "visibility=readOnly means you cannot set this field here — trust visibility over "
        + "readOnly, which only reports whether the value is locked, not why. "
        + "Fields with readOnly=true cannot be set by you: this covers auto-generated "
        + "identifiers (DocumentNo, IDs) as well as values derived/maintained elsewhere. "
        + "When such a field carries a writableVia pointer, it names the spec/entity where "
        + "the value is actually writable — call neo_schema there instead of giving up. "
        + "Use neo_selectors for FK fields with hasSelector=true. "
        + "Fields with businessCritical=true carry core business data (amounts, categories, "
        + "key dates) — you MUST confirm these values with the user before creating or "
        + "modifying records.");

    return wrapAsTextContent(entitySchema.toString(2));
  }

  /**
   * Whether at least one descriptor in the array is one an agent may actually write —
   * i.e. {@link McpSchemaFieldBuilder#isAgentSuppliable} — used by IMP-28 clause 4 to decide
   * whether the entity's advertised {@code methods} may include POST/PUT.
   *
   * @param fieldsArray the full, undecorated field array (before any {@code view}/{@code fields}
   *     narrowing) so a caller's whitelist request does not skew the entity-wide answer
   */
  private static boolean hasAnyAgentSuppliableField(JSONArray fieldsArray) {
    if (fieldsArray == null) {
      return false;
    }
    for (int i = 0; i < fieldsArray.length(); i++) {
      JSONObject field = fieldsArray.optJSONObject(i);
      if (field != null && McpSchemaFieldBuilder.isAgentSuppliable(field)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Names {@code neo_defaults} already resolves a value for, so {@code view:"create"} can demote
   * them out of {@code required} (IMP-12 §11.2).
   *
   * <p>The static {@code userRequired} rule can only see {@code AD_Column.DefaultValue}, which is an
   * incomplete proxy for "the server will supply this": on {@code sales-invoice/header} four of the
   * six fields it reports as required ({@code transactionDocument}, {@code paymentMethod},
   * {@code paymentTerms}, {@code priceList}) carry no column default yet are resolved at runtime
   * from session preferences, the business partner's configuration, or an AD callout. Asking the
   * agent for them is asking the user for something Etendo already knows.</p>
   *
   * <p>This costs one defaults resolution, paid <b>only</b> when {@code view:"create"} is requested
   * — the default response and {@code view:"actions"} are untouched. Resolution is best-effort: any
   * failure falls back to the static rule (an over-reported {@code required} field is a worse
   * answer, not a broken one), so a schema call never fails because of the cross-check.</p>
   */
  private static Set<String> serverDefaultedNames(String specName, String entityName, Tab adTab,
      SFEntity sfEntity) {
    try {
      NeoContext ctx = NeoContext.builder()
          .specName(specName)
          .entityName(entityName)
          .httpMethod(HTTP_METHOD_GET)
          .adTab(adTab)
          .sfEntity(sfEntity)
          .obContext(OBContext.getOBContext())
          .queryParams(new HashMap<>())
          .build();
      NeoResponse defaults = NeoDefaultsService.resolveDefaults(ctx, null);
      if (defaults == null || defaults.getHttpStatus() >= 400) {
        return Collections.emptySet();
      }
      return McpSchemaCreateView.resolvedDefaultNames(defaults.getBody());
    } catch (Exception e) {
      log.warn("neo_schema view:create could not resolve defaults for {}/{}; falling back to the "
          + "AD_Column.DefaultValue rule", specName, entityName, e);
      return Collections.emptySet();
    }
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
        return wrapAsTextContent(fkError.toString(2));
      }
      JSONObject result = BatchService.forBatchOnly().executeBatch(operations);
      if (!result.optBoolean("committed", false)) {
        // IMP-15: rewrite the failure in place into the IMP-5 envelope, so an agent gets a stable
        // error code instead of the raw DAL sub-response BatchService forwards to REST callers.
        McpToolRouterSupport.toMcpBatchFailure(result);
      }
      return wrapAsTextContent(result.toString(2));
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
    return null;
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
      return wrapAsErrorContent(actionResult.toString(2));
    }

    // Post-hook only on the success path, mirroring handleCreate/handleUpdate, which both
    // return early on error before runPostHook.
    JSONObject postHookResult = McpHookExecutor.runPostHook(handler, hookCtx, actionResult);
    if (postHookResult != null) {
      return postHookResult;
    }

    return wrapAsTextContent(actionResult.toString(2));
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
      return wrapAsErrorContent(
          response.getBody() != null ? response.getBody().toString() : "Error generating amortization plan");
    }
    return wrapAsTextContent(
        response.getBody() != null ? response.getBody().toString(2) : "{}");
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
          NeoReportCallability.buildNotConfiguredResponse(specName).toString(2));
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
          NeoReportCallability.buildNotConfiguredResponse(specName).toString(2));
    }

    JSONObject parameters = args != null ? args.optJSONObject(McpConstants.PARAM_PARAMETERS) : null;
    if (parameters == null) {
      parameters = new JSONObject();
    }

    JSONObject contractError = validateReportRequest(contract.get(), parameters,
        args != null ? args.optString(McpConstants.PARAM_FORMAT, null) : null);
    if (contractError != null) {
      return wrapAsErrorContent(contractError.toString(2));
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
          NeoReportCallability.buildNotConfiguredResponse(specName).toString(2));
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
   * Wrap a text string as MCP tool result content.
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
      throw new McpToolException("Error building MCP error content", e);
    }
  }
}
