package com.etendoerp.go.mcp;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoDefaultsService;
import com.etendoerp.go.schemaforge.NeoFieldFilter;
import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoReportService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

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

  /**
   * Route a tool call to its handler.
   * <p>
   * For CRUD tools (neo_list, neo_get, etc.), the spec name is extracted from the
   * "spec" argument. For process and report tools, the spec name is derived from
   * the tool name itself via {@link ToolRegistry#resolveSpecName}.
   *
   * @param toolName  MCP tool name (e.g. "neo_list", "complete_order")
   * @param arguments tool arguments (may be null)
   * @return MCP result object with "content" array
   */
  public JSONObject route(String toolName, JSONObject arguments) {
    try {
      OBContext.setAdminMode();
      try {
        // Resolve spec name from tool name or arguments
        String specName = ToolRegistry.resolveSpecName(toolName, arguments);

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
          default:
            // Check if it's a report tool (generate_*)
            if (toolName.startsWith("generate_")) {
              return handleReport(specName, arguments);
            }
            // Otherwise it's a process tool
            return handleProcess(specName, arguments);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error routing MCP tool '{}': {}", toolName, e.getMessage(), e);
      return wrapAsErrorContent("Error executing " + toolName + ": " + e.getMessage());
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
    specCriteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
    List<SFSpec> allSpecs = specCriteria.list();

    JSONArray specsArray = new JSONArray();
    for (SFSpec spec : allSpecs) {
      String specType = spec.getSpecType();
      String name = spec.getName();

      // RBAC check
      if ("W".equals(specType)) {
        Window window = spec.getADWindow();
        if (window != null && !NeoAccessUtils.hasWindowAccess(window.getId())) {
          continue;
        }
      } else if ("P".equals(specType) || "R".equals(specType)) {
        Process adProcess = spec.getProcess();
        if (adProcess != null && !NeoAccessUtils.hasProcessAccess(adProcess.getId())) {
          continue;
        }
      }

      JSONObject specObj = new JSONObject();
      specObj.put("name", name);
      specObj.put("type", specType);
      if (spec.getDescription() != null) {
        specObj.put("description", spec.getDescription());
      }

      if ("W".equals(specType)) {
        specObj.put("entities", buildEntitySummaryArray(spec.getId()));
      } else if ("R".equals(specType)) {
        specObj.put("isReport", true);
      }

      specsArray.put(specObj);
    }

    JSONObject result = new JSONObject();
    result.put("specs", specsArray);
    result.put("count", specsArray.length());
    return wrapAsTextContent(result.toString(2));
  }

  // ── neo_list ──────────────────────────────────────────────────────────

  /**
   * List records from a spec entity. Replicates NeoServlet.handleDefault() GET logic.
   */
  private JSONObject handleList(String specName, JSONObject args) throws Exception {
    validateArgs(args, "entity");

    String entityName = args.getString("entity");
    int limit = args.optInt("limit", 100);
    int offset = args.optInt("offset", 0);
    String orderBy = args.optString("orderBy", null);
    JSONObject filters = args.optJSONObject("filters");

    SFSpec spec = findSpecOrThrow(specName);
    SFEntity sfEntity = findEntityOrThrow(spec.getId(), entityName);
    Tab adTab = getAdTabOrThrow(sfEntity, entityName);

    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();
    NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(sfEntity, dalEntityName);

    Map<String, String> params = buildBaseParams(adTab, dalEntityName);
    params.put(JsonConstants.STARTROW_PARAMETER, String.valueOf(offset));
    params.put(JsonConstants.ENDROW_PARAMETER, String.valueOf(offset + limit - 1));

    if (StringUtils.isNotBlank(orderBy)) {
      params.put(JsonConstants.SORTBY_PARAMETER, orderBy);
    }

    // Apply filters as where clause
    if (filters != null && filters.length() > 0) {
      String whereClause = buildWhereFromFilters(filters, adTab);
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
    String error = checkJsonServiceError(responseJson);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    // Apply field filtering
    fieldFilter.filterGetResponse(responseJson);

    return wrapAsTextContent(responseJson.toString(2));
  }

  // ── neo_get ───────────────────────────────────────────────────────────

  /**
   * Get a single record by ID.
   */
  private JSONObject handleGet(String specName, JSONObject args) throws Exception {
    validateArgs(args, "entity", "id");

    String entityName = args.getString("entity");
    String recordId = args.getString("id");

    SFSpec spec = findSpecOrThrow(specName);
    SFEntity sfEntity = findEntityOrThrow(spec.getId(), entityName);
    Tab adTab = getAdTabOrThrow(sfEntity, entityName);

    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();
    NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(sfEntity, dalEntityName);

    Map<String, String> params = buildBaseParams(adTab, dalEntityName);
    params.put(JsonConstants.ID, recordId);

    String result = jsonService.fetch(params);
    JSONObject responseJson = new JSONObject(result);

    String error = checkJsonServiceError(responseJson);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    fieldFilter.filterGetResponse(responseJson);

    return wrapAsTextContent(responseJson.toString(2));
  }

  // ── neo_create ────────────────────────────────────────────────────────

  /**
   * Create a new record.
   */
  private JSONObject handleCreate(String specName, JSONObject args) throws Exception {
    validateArgs(args, "entity", "fields");

    String entityName = args.getString("entity");
    JSONObject fields = args.getJSONObject("fields");

    SFSpec spec = findSpecOrThrow(specName);
    SFEntity sfEntity = findEntityOrThrow(spec.getId(), entityName);
    Tab adTab = getAdTabOrThrow(sfEntity, entityName);

    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();
    NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(sfEntity, dalEntityName);

    Map<String, String> params = buildBaseParams(adTab, dalEntityName);

    // Filter out non-included and read-only fields
    JSONObject filteredBody = fieldFilter.filterWriteRequest(fields);

    // Resolve parentId if present
    String parentIdValue = null;
    if (filteredBody.has("parentId")) {
      parentIdValue = filteredBody.getString("parentId");
      filteredBody.remove("parentId");
      resolveParentFK(adTab, filteredBody, parentIdValue);
    }

    // Inject mandatory defaults
    NeoContext ctx = NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod("POST")
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .build();
    NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, ctx, parentIdValue);

    // Wrap for DefaultJsonDataService
    String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, null);
    String result = jsonService.add(params, wrappedBody);
    JSONObject responseJson = new JSONObject(result);

    String error = checkJsonServiceError(responseJson);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    fieldFilter.filterGetResponse(responseJson);

    return wrapAsTextContent(responseJson.toString(2));
  }

  // ── neo_update ────────────────────────────────────────────────────────

  /**
   * Update an existing record.
   */
  private JSONObject handleUpdate(String specName, JSONObject args) throws Exception {
    validateArgs(args, "entity", "id", "fields");

    String entityName = args.getString("entity");
    String recordId = args.getString("id");
    JSONObject fields = args.getJSONObject("fields");

    SFSpec spec = findSpecOrThrow(specName);
    SFEntity sfEntity = findEntityOrThrow(spec.getId(), entityName);
    Tab adTab = getAdTabOrThrow(sfEntity, entityName);

    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();
    NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(sfEntity, dalEntityName);

    Map<String, String> params = buildBaseParams(adTab, dalEntityName);

    // Filter out non-included and read-only fields
    JSONObject filteredBody = fieldFilter.filterWriteRequest(fields);

    // Wrap for DefaultJsonDataService with record ID
    String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, recordId);
    String result = jsonService.update(params, wrappedBody);
    JSONObject responseJson = new JSONObject(result);

    String error = checkJsonServiceError(responseJson);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    fieldFilter.filterGetResponse(responseJson);

    return wrapAsTextContent(responseJson.toString(2));
  }

  // ── neo_delete ────────────────────────────────────────────────────────

  /**
   * Delete a record by ID.
   */
  private JSONObject handleDelete(String specName, JSONObject args) throws Exception {
    validateArgs(args, "entity", "id");

    String entityName = args.getString("entity");
    String recordId = args.getString("id");

    SFSpec spec = findSpecOrThrow(specName);
    SFEntity sfEntity = findEntityOrThrow(spec.getId(), entityName);
    Tab adTab = getAdTabOrThrow(sfEntity, entityName);

    String dalEntityName = adTab.getTable().getName();
    DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();

    Map<String, String> params = buildBaseParams(adTab, dalEntityName);
    params.put(JsonConstants.ID, recordId);

    String result = jsonService.remove(params);
    JSONObject responseJson = new JSONObject(result);

    String error = checkJsonServiceError(responseJson);
    if (error != null) {
      return wrapAsErrorContent(error);
    }

    JSONObject deleteResult = new JSONObject();
    deleteResult.put("deleted", true);
    deleteResult.put("id", recordId);
    return wrapAsTextContent(deleteResult.toString(2));
  }

  // ── neo_selectors ─────────────────────────────────────────────────────

  /**
   * Query FK selector values for a column.
   */
  private JSONObject handleSelectors(String specName, JSONObject args) throws Exception {
    validateArgs(args, "entity", "column");

    String entityName = args.getString("entity");
    String column = args.getString("column");
    String query = args.optString("query", null);

    SFSpec spec = findSpecOrThrow(specName);

    NeoResponse neoResponse = NeoSelectorService.querySelector(
        spec.getId(), entityName, column,
        query, 50, 0, new HashMap<>());

    return neoResponseToMcpResult(neoResponse);
  }

  // ── neo_defaults ──────────────────────────────────────────────────────

  /**
   * Get default field values for creating a new record.
   */
  private JSONObject handleDefaults(String specName, JSONObject args) throws Exception {
    validateArgs(args, "entity");

    String entityName = args.getString("entity");

    SFSpec spec = findSpecOrThrow(specName);
    SFEntity sfEntity = findEntityOrThrow(spec.getId(), entityName);
    Tab adTab = getAdTabOrThrow(sfEntity, entityName);

    NeoContext ctx = NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod("GET")
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .build();

    NeoResponse neoResponse = NeoDefaultsService.resolveDefaults(ctx, null);
    return neoResponseToMcpResult(neoResponse);
  }

  // ── Process execution ─────────────────────────────────────────────────

  /**
   * Execute a process-type spec.
   */
  private JSONObject handleProcess(String specName, JSONObject args) throws Exception {
    SFSpec spec = findSpecOrThrow(specName);

    Process adProcess = spec.getProcess();
    if (adProcess == null) {
      return wrapAsErrorContent("Process spec '" + specName + "' has no linked AD_Process");
    }

    // Check RBAC
    if (!NeoAccessUtils.hasProcessAccess(adProcess.getId())) {
      return wrapAsErrorContent("Access denied to process '" + specName + "' for current role");
    }

    JSONObject parameters = args != null ? args.optJSONObject("parameters") : null;
    NeoResponse neoResponse = NeoProcessService.executeProcess(adProcess, parameters);
    return neoResponseToMcpResult(neoResponse);
  }

  // ── Report generation ─────────────────────────────────────────────────

  /**
   * Generate a report. Returns the report description (binary output cannot be
   * sent via MCP text content, so we describe what would be generated and provide
   * the parameters needed to call the REST endpoint directly).
   */
  private JSONObject handleReport(String specName, JSONObject args) throws Exception {
    SFSpec spec = findSpecOrThrow(specName);

    Process adProcess = spec.getProcess();
    if (adProcess == null) {
      return wrapAsErrorContent("Report spec '" + specName + "' has no linked AD_Process");
    }

    // Check RBAC
    if (!NeoAccessUtils.hasProcessAccess(adProcess.getId())) {
      return wrapAsErrorContent("Access denied to report '" + specName + "' for current role");
    }

    String format = args != null ? args.optString("format", "pdf") : "pdf";
    JSONObject parameters = args != null ? args.optJSONObject("parameters") : null;

    // Generate report to byte array and return base64 or description
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      NeoReportService.generateReport(adProcess, parameters,
          format.toUpperCase(), baos);

      // For MCP, encode as base64 so the client can save the file
      byte[] reportBytes = baos.toByteArray();
      String base64 = java.util.Base64.getEncoder().encodeToString(reportBytes);

      JSONObject reportResult = new JSONObject();
      reportResult.put("format", format);
      reportResult.put("sizeBytes", reportBytes.length);
      reportResult.put("base64", base64);
      reportResult.put("filename", specName + "." + format.toLowerCase());

      return wrapAsTextContent(reportResult.toString());
    } catch (Exception e) {
      log.error("Error generating report '{}': {}", specName, e.getMessage(), e);
      // Fall back to describe
      NeoResponse describeResponse = NeoReportService.describeReport(adProcess);
      JSONObject fallback = new JSONObject();
      fallback.put("error", "Report generation failed: " + e.getMessage());
      fallback.put("description", describeResponse.getBody());
      fallback.put("hint", "Use the REST endpoint POST /sws/neo/" + specName
          + " with exportType and params to generate the report via HTTP");
      return wrapAsErrorContent(fallback.toString(2));
    }
  }

  // ── Spec/entity resolution helpers ────────────────────────────────────

  /**
   * Find an active spec by name or throw.
   * Same query pattern as NeoServlet.findSpec().
   */
  private SFSpec findSpecOrThrow(String specName) throws Exception {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_NAME, specName));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    List<SFSpec> results = criteria.list();
    if (results.isEmpty()) {
      throw new IllegalArgumentException("Spec not found: " + specName);
    }
    return results.get(0);
  }

  /**
   * Find an active, included entity within a spec or throw.
   * Same query pattern as NeoServlet.findEntity().
   */
  private SFEntity findEntityOrThrow(String specId, String entityName) throws Exception {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, entityName));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    if (results.isEmpty()) {
      throw new IllegalArgumentException("Entity not found: " + entityName);
    }
    return results.get(0);
  }

  /**
   * Get the AD_Tab linked to an entity, or throw if not linked.
   */
  private Tab getAdTabOrThrow(SFEntity sfEntity, String entityName) throws Exception {
    Tab tab = sfEntity.getADTab();
    if (tab == null) {
      throw new IllegalArgumentException("No AD_Tab linked to entity: " + entityName);
    }
    return tab;
  }

  // ── DefaultJsonDataService helpers ────────────────────────────────────

  /**
   * Build the base parameter map for DefaultJsonDataService calls.
   */
  private Map<String, String> buildBaseParams(Tab adTab, String dalEntityName) {
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.ENTITYNAME, dalEntityName);
    params.put(JsonConstants.TAB_PARAMETER, adTab.getId());
    params.put(JsonConstants.WINDOW_ID, adTab.getWindow().getId());
    params.put(JsonConstants.NO_ACTIVE_FILTER, "true");
    return params;
  }

  /**
   * Build an HQL where clause fragment from MCP filter key-value pairs.
   * Filters are applied as exact-match conditions using the DAL property name.
   */
  private String buildWhereFromFilters(JSONObject filters, Tab adTab) throws JSONException {
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEntity == null) {
      return null;
    }

    StringBuilder where = new StringBuilder();
    Iterator<String> keys = filters.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      String value = filters.getString(key);

      // Try to resolve as a DAL property
      Property prop = null;
      try {
        prop = dalEntity.getPropertyByColumnName(key);
      } catch (Exception ignored) {
        // Try by property name
        try {
          prop = dalEntity.getProperty(key);
        } catch (Exception alsoIgnored) {
          log.debug("Filter column '{}' not found in entity, skipping", key);
          continue;
        }
      }

      if (where.length() > 0) {
        where.append(" and ");
      }

      if (prop != null && !prop.isPrimitive()) {
        // FK reference — match on .id
        where.append("e.").append(prop.getName()).append(".id='")
            .append(value.replace("'", "''")).append("'");
      } else if (prop != null) {
        where.append("e.").append(prop.getName()).append("='")
            .append(value.replace("'", "''")).append("'");
      } else {
        // No resolved property — skip to prevent HQL injection
        log.warn("Filter key '{}' could not be resolved to a DAL property, ignoring", key);
        continue;
      }
    }
    return where.length() > 0 ? where.toString() : null;
  }

  /**
   * Wraps a flat JSON body into the structure expected by DefaultJsonDataService.
   * Identical to NeoServlet.wrapForSmartclient().
   */
  private String wrapForSmartclient(JSONObject filteredBody, String dalEntityName,
      String recordId) {
    try {
      JSONObject data = filteredBody != null ? filteredBody : new JSONObject();
      data.put(JsonConstants.ENTITYNAME, dalEntityName);
      if (recordId != null) {
        data.put(JsonConstants.ID, recordId);
      } else {
        data.put(JsonConstants.NEW_INDICATOR, true);
      }

      JSONObject wrapper = new JSONObject();
      wrapper.put(JsonConstants.DATA, data);
      return wrapper.toString();
    } catch (Exception e) {
      log.error("Error wrapping body for Smartclient format: {}", e.getMessage(), e);
      return "{}";
    }
  }

  /**
   * Resolve parentId to the actual FK property name on child tabs.
   * Replicates the same logic from NeoServlet's POST handler.
   */
  private void resolveParentFK(Tab adTab, JSONObject body, String parentIdValue)
      throws JSONException {
    if (adTab.getTabLevel() == null || adTab.getTabLevel() <= 0) {
      return;
    }

    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEntity == null) {
      return;
    }

    for (Column col : adTab.getTable().getADColumnList()) {
      if (col.isLinkToParentColumn() && col.isActive()) {
        try {
          Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
          if (prop != null) {
            body.put(prop.getName(), parentIdValue);
            break;
          }
        } catch (Exception ignored) {
          // Column not mappable to property
        }
      }
    }
  }

  /**
   * Check if a DefaultJsonDataService response contains an error.
   * Returns error message if found, null otherwise.
   */
  private String checkJsonServiceError(JSONObject responseJson) throws JSONException {
    JSONObject innerResponse = responseJson.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
    if (innerResponse == null) {
      return null;
    }

    int status = innerResponse.optInt(JsonConstants.RESPONSE_STATUS, 0);
    if (status == JsonConstants.RPCREQUEST_STATUS_FAILURE) {
      if (innerResponse.has(JsonConstants.RESPONSE_ERROR)) {
        return innerResponse.getJSONObject(JsonConstants.RESPONSE_ERROR)
            .optString("message", "Operation failed");
      }
      return "Operation failed";
    }
    if (status == JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR) {
      return "Validation error: " + innerResponse.toString();
    }
    return null;
  }

  // ── Entity summary for discovery ──────────────────────────────────────

  /**
   * Build entity summary array for a spec (used by neo_discover).
   * Same logic as NeoServlet.buildEntitySummaryArray().
   */
  private JSONArray buildEntitySummaryArray(String specId) throws Exception {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
    List<SFEntity> entities = criteria.list();

    JSONArray arr = new JSONArray();
    for (SFEntity entity : entities) {
      JSONObject obj = new JSONObject();
      obj.put("name", entity.getName());
      obj.put("methods", buildMethodsArray(entity));
      arr.put(obj);
    }
    return arr;
  }

  private JSONArray buildMethodsArray(SFEntity entity) {
    JSONArray methods = new JSONArray();
    if (Boolean.TRUE.equals(entity.isGet()) || Boolean.TRUE.equals(entity.isGetByID())) {
      methods.put("GET");
    }
    if (Boolean.TRUE.equals(entity.isPost())) {
      methods.put("POST");
    }
    if (Boolean.TRUE.equals(entity.isPut())) {
      methods.put("PUT");
    }
    if (Boolean.TRUE.equals(entity.isPatch())) {
      methods.put("PATCH");
    }
    if (Boolean.TRUE.equals(entity.isDelete())) {
      methods.put("DELETE");
    }
    return methods;
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
      throw new RuntimeException("Error building MCP content", e);
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
      throw new RuntimeException("Error building MCP error content", e);
    }
  }

  /**
   * Convert a NeoResponse to MCP result format.
   * Successful responses become text content; error responses set isError.
   */
  private JSONObject neoResponseToMcpResult(NeoResponse neoResponse) throws JSONException {
    if (neoResponse.getHttpStatus() >= 400) {
      String errorText = neoResponse.getBody() != null
          ? neoResponse.getBody().toString(2)
          : "Request failed with status " + neoResponse.getHttpStatus();
      return wrapAsErrorContent(errorText);
    }

    String text = neoResponse.getBody() != null
        ? neoResponse.getBody().toString(2)
        : "{}";
    return wrapAsTextContent(text);
  }

  // ── Validation helpers ────────────────────────────────────────────────

  /**
   * Validate that required arguments are present.
   */
  private void validateArgs(JSONObject args, String... required) throws IllegalArgumentException {
    if (args == null) {
      throw new IllegalArgumentException("Missing arguments");
    }
    for (String key : required) {
      if (!args.has(key) || args.isNull(key)) {
        throw new IllegalArgumentException("Missing required argument: " + key);
      }
    }
  }
}
