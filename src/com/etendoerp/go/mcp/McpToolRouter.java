package com.etendoerp.go.mcp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
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
import com.etendoerp.go.schemaforge.data.SFField;
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
          case "neo_schema":
            return handleSchema(specName, arguments);
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

    // MCP: accept all valid table columns from AI agents, not just SF-configured ones.
    // filterWriteRequest strips fields not in ETGO_SF_FIELD writableFields, which is
    // too restrictive for MCP where AI agents need to set any valid column.
    JSONObject filteredBody = mapFieldsToDalProperties(fields, adTab);

    // Snapshot user-provided fields BEFORE callout cascade can overwrite them.
    // Callouts derive dependent fields (e.g. product → tax, UOM) and may reset them
    // to sentinel "0" even when the user explicitly provided valid values.
    JSONObject userProvided = new JSONObject(filteredBody.toString());

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

    // Restore user-provided fields that callouts may have overwritten with sentinels.
    // User intent takes precedence over callout-derived values.
    Iterator<String> userKeys = userProvided.keys();
    while (userKeys.hasNext()) {
      String key = userKeys.next();
      if ("parentId".equals(key)) {
        continue;
      }
      filteredBody.put(key, userProvided.get(key));
    }

    // Fix FK sentinel values: "0" is a UI-level sentinel (means "not yet set") that can't
    // go through the DAL as an entity reference. Replace with a real value from the body
    // when possible (e.g. documentType="0" -> copy from transactionDocument), or remove.
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(adTab.getTable().getId());
    resolveFkSentinels(filteredBody, dalEntity);

    // Coerce string values to proper JSON types expected by the DAL (Long, BigDecimal, Boolean).
    // Callout cascade returns all values as strings, but DefaultJsonDataService/JsonToDataConverter
    // expects JSON numbers and booleans for numeric/boolean DAL properties.
    coerceFieldTypes(filteredBody, dalEntity);

    // Validate mandatory fields before insert — return structured error matching neo_schema contract
    JSONArray missingFields = validateMandatoryFields(filteredBody, adTab, dalEntity);
    if (missingFields.length() > 0) {
      JSONObject errorObj = new JSONObject();
      errorObj.put("error", "Missing required fields that could not be auto-resolved");
      errorObj.put("missingFields", missingFields);
      errorObj.put("hint", "Provide these fields in the request, or use neo_selectors to find valid values for foreignKey fields");
      return wrapAsErrorContent(errorObj.toString(2));
    }

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

    // MCP: accept all valid table columns from AI agents
    JSONObject filteredBody = mapFieldsToDalProperties(fields, adTab);

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
   * Resolves the AD_Column from the dictionary (AD_Tab → AD_Table → AD_Column),
   * bypassing ETGO_SF_FIELD so all FK columns are queryable — not just included ones.
   */
  private JSONObject handleSelectors(String specName, JSONObject args) throws Exception {
    validateArgs(args, "entity", "column");

    String entityName = args.getString("entity");
    String columnName = args.getString("column");
    String query = args.optString("query", null);

    SFSpec spec = findSpecOrThrow(specName);
    SFEntity sfEntity = findEntityOrThrow(spec.getId(), entityName);
    Tab adTab = getAdTabOrThrow(sfEntity, entityName);

    // Find the AD_Column by DB column name or DAL property name (field name from schema)
    Column adColumn = null;
    for (Column col : adTab.getTable().getADColumnList()) {
      if (col.getDBColumnName().equalsIgnoreCase(columnName)) {
        adColumn = col;
        break;
      }
    }

    // Fallback: resolve by DAL property name (e.g. "businessPartner" → "C_BPartner_ID")
    if (adColumn == null) {
      Entity dalEntity = ModelProvider.getInstance().getEntityByTableName(adTab.getTable().getDBTableName());
      if (dalEntity != null) {
        for (Column col : adTab.getTable().getADColumnList()) {
          try {
            Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
            if (prop != null && prop.getName().equalsIgnoreCase(columnName)) {
              adColumn = col;
              break;
            }
          } catch (Exception ignored) {
            // Column not mappable to property
          }
        }
      }
    }

    if (adColumn == null) {
      throw new IllegalArgumentException("Column not found in table: " + columnName);
    }

    NeoResponse neoResponse = NeoSelectorService.querySelectorByColumn(
        adColumn, columnName, query, 50, 0, new HashMap<>());

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

  // ── neo_schema ─────────────────────────────────────────────────────────

  // AD_Reference IDs for selector types (FK references)
  private static final java.util.Set<String> SELECTOR_REFS = new java.util.HashSet<>(
      java.util.Arrays.asList("19", "18", "30", "95E2A8B50A254B2AAE6774B8C2F28120"));

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
    validateArgs(args, "entity");

    String entityName = args.getString("entity");

    SFSpec spec = findSpecOrThrow(specName);
    SFEntity sfEntity = findEntityOrThrow(spec.getId(), entityName);
    Tab adTab = getAdTabOrThrow(sfEntity, entityName);

    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());

    // Load SF_FIELD visibility map: AD_COLUMN_ID -> visibility string
    Map<String, String> visibilityByColumnId = new HashMap<>();
    OBCriteria<SFField> fieldCrit = OBDal.getInstance().createCriteria(SFField.class);
    fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
    fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
    for (SFField sfField : fieldCrit.list()) {
      Column adCol = sfField.getADColumn();
      if (adCol != null) {
        String vis = (String) sfField.get("visibility");
        if (vis != null && !vis.trim().isEmpty()) {
          visibilityByColumnId.put((String) adCol.getId(), vis.trim());
        }
      }
    }

    // Build fields array from AD_Column (dictionary-driven, like the classic UI)
    JSONArray fieldsArray = new JSONArray();
    for (Column col : adTab.getTable().getADColumnList()) {
      if (!col.isActive()) {
        continue;
      }
      String dbColName = col.getDBColumnName();
      if (SYSTEM_COLUMNS.contains(dbColName.toUpperCase())) {
        continue;
      }

      // Resolve DAL property name (camelCase, matches GET response keys)
      String propName = dbColName;
      if (dalEntity != null) {
        try {
          Property prop = dalEntity.getPropertyByColumnName(dbColName);
          if (prop != null) {
            propName = prop.getName();
          }
        } catch (Exception ignored) {
          // keep dbColName as fallback
        }
      }

      String refId = col.getReference() != null
          ? (String) col.getReference().getId() : null;

      JSONObject fieldObj = new JSONObject();
      fieldObj.put("name", propName);
      fieldObj.put("column", dbColName);
      fieldObj.put("label", col.getName());
      fieldObj.put("type", mapColumnType(refId));
      fieldObj.put("required", col.isMandatory());

      // A field is readOnly if: it's a PK, a DocumentNo, or has an automatic sequence
      String tableName = adTab.getTable().getDBTableName();
      String expectedPK = tableName + "_ID";
      boolean isReadOnly = expectedPK.equalsIgnoreCase(dbColName)
          || "DocumentNo".equalsIgnoreCase(dbColName)
          || (Boolean.TRUE.equals(col.isUseAutomaticSequence()));
      fieldObj.put("readOnly", isReadOnly);

      // Default value expression (so agent knows what the system auto-fills)
      String defaultExpr = col.getDefaultValue();
      if (defaultExpr != null && !defaultExpr.trim().isEmpty()) {
        fieldObj.put("defaultExpression", defaultExpr.trim());
      }

      // Visibility from ETGO_SF_FIELD (editable, readOnly, system, discarded)
      String visibility = visibilityByColumnId.get((String) col.getId());
      if (visibility != null) {
        fieldObj.put("visibility", visibility);
        // userRequired: only true when field is editable AND mandatory in the AD dictionary
        boolean userRequired = "editable".equals(visibility) && col.isMandatory();
        fieldObj.put("userRequired", userRequired);
      }

      // FK selector info
      boolean hasSelector = refId != null && SELECTOR_REFS.contains(refId);
      if (hasSelector) {
        fieldObj.put("hasSelector", true);
        fieldObj.put("selectorType", mapSelectorType(refId));
      }

      fieldsArray.put(fieldObj);
    }

    // Build entity schema
    JSONObject entitySchema = new JSONObject();
    entitySchema.put("spec", specName);
    entitySchema.put("entity", entityName);
    entitySchema.put("table", adTab.getTable().getDBTableName());

    // Methods from SFEntity config
    JSONArray methods = new JSONArray();
    if (Boolean.TRUE.equals(sfEntity.isGet()) || Boolean.TRUE.equals(sfEntity.isGetByID())) {
      methods.put("GET");
    }
    if (Boolean.TRUE.equals(sfEntity.isPost())) {
      methods.put("POST");
    }
    if (Boolean.TRUE.equals(sfEntity.isPut())) {
      methods.put("PUT");
    }
    if (Boolean.TRUE.equals(sfEntity.isDelete())) {
      methods.put("DELETE");
    }
    entitySchema.put("methods", methods);
    entitySchema.put("fields", fieldsArray);
    entitySchema.put("fieldCount", fieldsArray.length());

    // Usage hints
    entitySchema.put("hint",
        "Fields with userRequired=true: MUST be provided in neo_create. "
        + "Fields with visibility=system are auto-derived by Etendo callouts — omit them. "
        + "Fields with visibility=discarded are excluded — do not send them. "
        + "Fields with readOnly=true are auto-generated (DocumentNo, IDs). "
        + "Use neo_selectors for FK fields with hasSelector=true.");

    return wrapAsTextContent(entitySchema.toString(2));
  }

  private String mapColumnType(String refId) {
    if (refId == null) return "string";
    switch (refId) {
      case "10": case "14": case "34": return "string";
      case "11": case "22": case "29": case "12":
      case "800008": case "800019": return "number";
      case "20": return "boolean";
      case "15": return "date";
      case "16": return "datetime";
      case "24": return "time";
      case "28": return "button";
      case "17": return "list";
      case "13": return "id";
      case "19": case "18": case "30":
      case "95E2A8B50A254B2AAE6774B8C2F28120": return "foreignKey";
      default: return "string";
    }
  }

  private String mapSelectorType(String refId) {
    if (refId == null) return null;
    switch (refId) {
      case "19": return "TableDir";
      case "18": return "Table";
      case "30": return "Search";
      case "95E2A8B50A254B2AAE6774B8C2F28120": return "OBUISEL";
      default: return null;
    }
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
   * Map user-provided fields to DAL property names without SF-field filtering.
   * Accepts both DAL property names ("businessPartner") and DB column names
   * ("C_BPartner_ID"), resolving all to their DAL property equivalents.
   * This allows MCP AI agents to set any valid column on the table.
   */
  private JSONObject mapFieldsToDalProperties(JSONObject fields, Tab adTab)
      throws JSONException {
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(adTab.getTable().getId());
    JSONObject mapped = new JSONObject();

    Iterator<String> keys = fields.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = fields.get(key);

      // Try as DAL property name first
      Property prop = dalEntity.getProperty(key, false);
      if (prop != null) {
        mapped.put(key, value);
        continue;
      }

      // Try as DB column name
      prop = dalEntity.getPropertyByColumnName(key, false);
      if (prop != null) {
        mapped.put(prop.getName(), value);
        continue;
      }

      // Pass through unknown keys (parentId, etc.)
      mapped.put(key, value);
    }
    return mapped;
  }

  /**
   * Replace FK sentinel values ("0") in the body with real values.
   * The DAL's JsonToDataConverter tries to load entities by ID, and "0" is not a valid UUID.
   * In Etendo, "0" means "not yet determined" — the real value comes from a related field
   * (e.g. C_DocType_ID copies from C_DocTypeTarget_ID). For each sentinel, we find another
   * property in the body that targets the same entity and has a real value.
   */
  /**
   * Validate that all mandatory columns have a value in the body before insert.
   * Returns a JSONArray of missing fields using the same structure as neo_schema
   * (name, column, type, hasSelector) so the model knows exactly what to provide.
   */
  private JSONArray validateMandatoryFields(JSONObject body, Tab adTab, Entity dalEntity) {
    JSONArray missing = new JSONArray();
    if (dalEntity == null) {
      return missing;
    }

    for (Column col : adTab.getTable().getADColumnList()) {
      if (!col.isActive() || !col.isMandatory()) {
        continue;
      }
      // Skip primary key column (auto-generated by DAL)
      if (col.getDBColumnName().equalsIgnoreCase(adTab.getTable().getDBTableName() + "_ID")) {
        continue;
      }
      String dbColName = col.getDBColumnName();
      if (SYSTEM_COLUMNS.contains(dbColName.toUpperCase())) {
        continue;
      }

      Property prop = null;
      try {
        prop = dalEntity.getPropertyByColumnName(dbColName);
      } catch (Exception ignored) {
        continue;
      }
      if (prop == null) {
        continue;
      }

      String propName = prop.getName();
      if (body.has(propName) && !body.isNull(propName)) {
        Object val = body.opt(propName);
        if (val instanceof String && !((String) val).isEmpty()) {
          continue;
        }
        if (!(val instanceof String)) {
          continue;
        }
      }

      // This mandatory field is missing — build schema-compatible descriptor
      try {
        JSONObject fieldInfo = new JSONObject();
        fieldInfo.put("name", propName);
        fieldInfo.put("column", dbColName);

        String refId = col.getReference() != null ? col.getReference().getId() : null;
        boolean isFK = SELECTOR_REFS.contains(refId);
        fieldInfo.put("type", isFK ? "foreignKey" : "other");
        if (isFK) {
          fieldInfo.put("hasSelector", true);
        }
        fieldInfo.put("label", col.getName());
        missing.put(fieldInfo);
      } catch (Exception ignored) {
        // skip
      }
    }
    return missing;
  }

  /**
   * Coerce string values in the body to the proper JSON types expected by the DAL.
   * Callout cascade and session defaults return everything as strings, but
   * DefaultJsonDataService expects JSON numbers for Long/BigDecimal properties
   * and JSON booleans for Boolean properties.
   */
  private void coerceFieldTypes(JSONObject body, Entity dalEntity) {
    if (body == null || dalEntity == null) {
      return;
    }
    List<String> keys = new ArrayList<>();
    Iterator<String> it = body.keys();
    while (it.hasNext()) {
      keys.add(it.next());
    }
    for (String key : keys) {
      Property prop = dalEntity.getProperty(key, false);
      if (prop == null || !prop.isPrimitive()) {
        continue;
      }
      Object val = body.opt(key);
      if (!(val instanceof String)) {
        continue;
      }
      String strVal = (String) val;
      if (strVal.isEmpty()) {
        continue;
      }
      try {
        Class<?> type = prop.getPrimitiveObjectType();
        if (type == Long.class) {
          body.put(key, Long.parseLong(strVal.contains(".") ? strVal.substring(0, strVal.indexOf('.')) : strVal));
        } else if (type == java.math.BigDecimal.class) {
          body.put(key, new java.math.BigDecimal(strVal));
        } else if (type == Boolean.class) {
          body.put(key, "Y".equalsIgnoreCase(strVal) || "true".equalsIgnoreCase(strVal));
        }
      } catch (Exception e) {
        log.debug("Could not coerce field {} value '{}': {}", key, strVal, e.getMessage());
      }
    }
  }

  private void resolveFkSentinels(JSONObject body, Entity dalEntity) throws JSONException {
    // First pass: collect all sentinels and all real FK values by target entity
    Map<String, String> sentinelProps = new HashMap<>(); // propName -> targetEntityName
    Map<String, String> realValues = new HashMap<>();    // targetEntityName -> value

    Iterator<String> keys = body.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Property prop = dalEntity.getProperty(key, false);
      if (prop == null || prop.isPrimitive() || prop.getTargetEntity() == null) {
        continue;
      }
      String targetEntity = prop.getTargetEntity().getName();
      String value = body.optString(key, "");
      if ("0".equals(value)) {
        sentinelProps.put(key, targetEntity);
      } else if (!value.isEmpty()) {
        realValues.put(targetEntity, value);
      }
    }

    // Second pass: replace sentinels with real values from same-target-entity fields
    for (Map.Entry<String, String> entry : sentinelProps.entrySet()) {
      String propName = entry.getKey();
      String targetEntity = entry.getValue();
      String realValue = realValues.get(targetEntity);
      if (realValue != null) {
        body.put(propName, realValue);
        log.debug("Resolved FK sentinel: {} = {} (from sibling targeting {})",
            propName, realValue, targetEntity);
      } else {
        // No sibling with real value — remove to avoid DAL error. The column must
        // either have a DB default or be nullable; if not, the INSERT will fail.
        body.remove(propName);
        log.warn("Removed FK sentinel '0' for {} — no sibling value found for {}",
            propName, targetEntity);
      }
    }
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
