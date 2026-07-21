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
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

final class McpToolRouterSupport {

  private McpToolRouterSupport() {
  }

  static SFSpec findActiveSpecByName(String specName) {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_NAME, specName));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    List<SFSpec> results = criteria.list();
    if (results.isEmpty()) {
      throw new OBException("Spec not found: " + specName);
    }
    return results.get(0);
  }

  static SFEntity findIncludedEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, entityName));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    if (results.isEmpty()) {
      throw new OBException("Entity not found: " + entityName);
    }
    return results.get(0);
  }

  /**
   * Resolve an included entity for an entity-CRUD tool (neo_list/get/create/update/delete/
   * selectors/defaults/schema), or throw a descriptive error when the spec cannot expose
   * listable entities.
   *
   * <p>Report-type specs (specType {@code "R"}) expose no CRUD entities, so a bare
   * {@code findIncludedEntity} would surface an opaque {@code "Entity not found: <name>"}
   * (ETP-4257). Instead this guard fires before the entity lookup and explains what the spec
   * is and what to do:</p>
   * <ul>
   *   <li>callable report (NEO-native handler, ETP-4255) → point the agent at the
   *       {@code etendo_generate_<snake>} report tool;</li>
   *   <li>non-callable report → the stable {@link NeoReportCallability#buildNotConfiguredMessage}
   *       {@code not_configured_for_report_generation} text.</li>
   * </ul>
   *
   * <p>Type-W (and any non-R) specs are unaffected: the call delegates to
   * {@link #findIncludedEntity(String, String)}, preserving the existing
   * {@code "Entity not found: <name>"} message for a genuinely wrong entity name.</p>
   *
   * @param spec       the resolved active spec (already found by {@link #findActiveSpecByName})
   * @param entityName the requested entity name
   * @return the matching included {@link SFEntity} for a non-report spec
   * @throws OBException with a descriptive message for a report-type spec, or when the entity
   *                     name does not match an included entity on a non-report spec
   */
  static SFEntity resolveIncludedEntityOrExplain(SFSpec spec, String entityName) {
    if ("R".equals(spec.getSpecType())) {
      if (NeoReportCallability.isReportCallable(spec)) {
        String snakeTool = McpConstants.GENERATE_PREFIX + ToolRegistry.kebabToSnake(spec.getName());
        throw new OBException("Spec '" + spec.getName() + "' is a report type (R) and does not "
            + "expose listable entities. Use the etendo_" + snakeTool
            + " tool to produce this report.");
      }
      throw new OBException(NeoReportCallability.buildNotConfiguredMessage(spec.getName()));
    }
    return findIncludedEntity(spec.getId(), entityName);
  }

  static List<SFEntity> listIncludedEntities(String specId) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
    return criteria.list();
  }

  static JSONArray buildEntitySummaryArray(String specId) throws JSONException {
    JSONArray entities = new JSONArray();
    for (SFEntity entity : listIncludedEntities(specId)) {
      JSONObject item = new JSONObject();
      item.put("name", entity.getName());
      item.put("methods", buildMethodsArray(entity));
      entities.put(item);
    }
    return entities;
  }

  static JSONArray buildMethodsArray(SFEntity entity) {
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

  static String mapColumnType(String refId) {
    if (refId == null) {
      return McpConstants.TYPE_STRING;
    }
    switch (refId) {
      case "10":
      case "14":
      case "34":
        return McpConstants.TYPE_STRING;
      case "11":
      case "22":
      case "29":
      case "12":
      case "800008":
      case "800019":
        return "number";
      case "20":
        return "boolean";
      case "15":
        return "date";
      case "16":
        return "datetime";
      case "24":
        return "time";
      case "28":
        return "button";
      case "17":
        return "list";
      case "13":
        return "id";
      case "19":
      case "18":
      case "30":
      case NeoSelectorService.REF_OBUISEL:
        return "foreignKey";
      default:
        return McpConstants.TYPE_STRING;
    }
  }

  static String mapSelectorType(String refId) {
    if (refId == null) {
      return null;
    }
    switch (refId) {
      case "19":
        return "TableDir";
      case "18":
        return "Table";
      case "30":
        return "Search";
      case NeoSelectorService.REF_OBUISEL:
        return "OBUISEL";
      default:
        return null;
    }
  }

  /**
   * Identify the spec whose entities are handler-backed business widgets rather than
   * CRUD windows (gap G4, ETP-4284). These entities have no {@code AD_Tab}, so the
   * generic CRUD path cannot serve them; they are exposed via the {@code neo_widget}
   * tool instead and must be excluded from the type-W CRUD catalog and discovery.
   *
   * @param spec the spec to test (may be {@code null})
   * @return {@code true} when the spec backs widget handlers
   */
  static boolean isWidgetSpec(SFSpec spec) {
    return spec != null && McpConstants.SPEC_DASHBOARD.equals(spec.getName());
  }

  static boolean hasSpecAccess(SFSpec spec, String specType) {
    // The dashboard/widget spec is not a CRUD window; it is surfaced via neo_widget,
    // never through neo_discover's W catalog (ETP-4284 / G4).
    if (isWidgetSpec(spec)) {
      return false;
    }
    if ("W".equals(specType)) {
      Window window = spec.getADWindow();
      return window == null || NeoAccessUtils.hasWindowAccess(window.getId());
    }
    if ("P".equals(specType) || "R".equals(specType)) {
      Process adProcess = spec.getProcess();
      return adProcess == null || NeoAccessUtils.hasProcessAccess(adProcess.getId());
    }
    return true;
  }

  static JSONObject buildDiscoverSpec(SFSpec spec, String specType, JSONArray entities)
      throws Exception {
    JSONObject specObj = new JSONObject();
    specObj.put("name", spec.getName());
    specObj.put("type", specType);
    if (spec.getDescription() != null) {
      specObj.put(McpConstants.KEY_DESCRIPTION, spec.getDescription());
    }
    String agentPrompt = spec.getAgentPrompt();
    if (agentPrompt != null && !agentPrompt.trim().isEmpty()) {
      specObj.put("agentPrompt", agentPrompt.trim());
    }
    if (entities != null) {
      specObj.put("entities", entities);
    }
    if ("R".equals(specType)) {
      // Report callability is truthful (ETP-4255): a report spec is callable only when it
      // is backed by a NEO-native report handler. Non-callable specs expose a stable
      // not_configured_for_report_generation status + message; Jasper/AD_Process reports
      // are never executable by Etendo Go.
      specObj.put("isReport", true);
      boolean callable = NeoReportCallability.isReportCallable(spec);
      specObj.put("callable", callable);
      if (callable) {
        // Surface the concrete report tool so the agent can call it directly instead of
        // guessing an entity for neo_list (ETP-4257). Client sees it as etendo_<reportTool>.
        specObj.put("reportTool",
            McpConstants.GENERATE_PREFIX + ToolRegistry.kebabToSnake(spec.getName()));
      } else {
        specObj.put("status", NeoReportCallability.STATUS_NOT_CONFIGURED);
        specObj.put("message", NeoReportCallability.buildNotConfiguredMessage(spec.getName()));
      }
    }
    return specObj;
  }

  static Column findColumn(Tab adTab, String columnName, Entity dalEntity) {
    for (Column col : adTab.getTable().getADColumnList()) {
      if (col.getDBColumnName().equalsIgnoreCase(columnName)) {
        return col;
      }
    }
    if (dalEntity == null) {
      return null;
    }
    for (Column col : adTab.getTable().getADColumnList()) {
      try {
        Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
        if (prop != null && prop.getName().equalsIgnoreCase(columnName)) {
          return col;
        }
      } catch (Exception ignored) {
        // Column not mappable to property
      }
    }
    return null;
  }

  /**
   * Loads all SFField rows for the given entity in a single query and returns
   * both the visibility map and the businessCritical map, keyed by AD_COLUMN_ID.
   * Callers that need both maps should use this to avoid a second DB round-trip.
   */
  static FieldMetadata loadFieldMetadata(SFEntity sfEntity) {
    Map<String, String> visibilityByColumnId = new HashMap<>();
    Map<String, Boolean> businessCriticalByColumnId = new HashMap<>();
    OBCriteria<SFField> fieldCrit = OBDal.getInstance().createCriteria(SFField.class);
    fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
    fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
    for (SFField sfField : fieldCrit.list()) {
      Column adCol = sfField.getADColumn();
      if (adCol == null) {
        continue;
      }
      String colId = (String) adCol.getId();
      String visibility = (String) sfField.get("visibility");
      if (visibility != null && !visibility.trim().isEmpty()) {
        visibilityByColumnId.put(colId, visibility.trim());
      }
      Boolean isBusinessCritical = sfField.isBusinessCritical();
      businessCriticalByColumnId.put(colId, Boolean.TRUE.equals(isBusinessCritical));
    }
    return new FieldMetadata(visibilityByColumnId, businessCriticalByColumnId);
  }

  static final class FieldMetadata {
    final Map<String, String> visibilityByColumnId;
    final Map<String, Boolean> businessCriticalByColumnId;

    FieldMetadata(Map<String, String> visibilityByColumnId,
        Map<String, Boolean> businessCriticalByColumnId) {
      this.visibilityByColumnId = visibilityByColumnId;
      this.businessCriticalByColumnId = businessCriticalByColumnId;
    }
  }

  static Map<String, String> loadPromptByColumnId(SFEntity sfEntity) {
    Map<String, String> promptByColumnId = new HashMap<>();
    OBCriteria<SFField> fieldCrit = OBDal.getInstance().createCriteria(SFField.class);
    fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
    fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
    for (SFField sfField : fieldCrit.list()) {
      Column adCol = sfField.getADColumn();
      String prompt = sfField.getAgentPrompt();
      if (adCol != null && prompt != null && !prompt.trim().isEmpty()) {
        promptByColumnId.put((String) adCol.getId(), prompt.trim());
      }
    }
    return promptByColumnId;
  }

  static JSONArray buildSchemaFieldsArray(Tab adTab, Entity dalEntity,
      Map<String, String> visibilityByColumnId, Map<String, Boolean> businessCriticalByColumnId,
      Map<String, String> promptByColumnId,
      java.util.Set<String> systemColumns, java.util.Set<String> selectorRefs) throws JSONException {
    JSONArray fieldsArray = new JSONArray();
    for (Column col : adTab.getTable().getADColumnList()) {
      if (shouldIncludeSchemaColumn(col, systemColumns)) {
        fieldsArray.put(buildSchemaField(col, adTab, dalEntity, visibilityByColumnId,
            businessCriticalByColumnId, promptByColumnId, selectorRefs));
      }
    }
    return fieldsArray;
  }

  private static boolean shouldIncludeSchemaColumn(Column col, java.util.Set<String> systemColumns) {
    return col.isActive() && !systemColumns.contains(col.getDBColumnName().toUpperCase());
  }

  private static JSONObject buildSchemaField(Column col, Tab adTab, Entity dalEntity,
      Map<String, String> visibilityByColumnId, Map<String, Boolean> businessCriticalByColumnId,
      Map<String, String> promptByColumnId,
      java.util.Set<String> selectorRefs) throws JSONException {
    String dbColName = col.getDBColumnName();
    String refId = col.getReference() != null ? (String) col.getReference().getId() : null;
    String type = mapColumnType(refId);
    JSONObject fieldObj = new JSONObject();
    fieldObj.put("name", resolvePropertyName(dalEntity, dbColName));
    fieldObj.put("column", dbColName);
    fieldObj.put("label", col.getName());
    fieldObj.put("type", type);
    fieldObj.put("required", col.isMandatory());
    fieldObj.put("readOnly", isReadOnlyColumn(adTab, col));
    addDefaultExpression(fieldObj, col);
    addVisibility(fieldObj, visibilityByColumnId.get((String) col.getId()), col.isMandatory());
    boolean isBusinessCritical = Boolean.TRUE.equals(
        businessCriticalByColumnId.get((String) col.getId()));
    fieldObj.put("businessCritical", isBusinessCritical);
    addAgentPrompt(fieldObj, promptByColumnId.get((String) col.getId()));
    addSelectorInfo(fieldObj, refId, selectorRefs);
    if ("button".equals(type)) {
      addButtonInfo(fieldObj, col);
    }
    return fieldObj;
  }

  private static void addAgentPrompt(JSONObject fieldObj, String agentPrompt) throws JSONException {
    if (agentPrompt != null && !agentPrompt.trim().isEmpty()) {
      fieldObj.put("agentPrompt", agentPrompt.trim());
    }
  }

  private static void addButtonInfo(JSONObject fieldObj, Column col) throws JSONException {
    fieldObj.put("triggerValue", "Y");
    fieldObj.put("action", col.getDBColumnName());
    fieldObj.put("invokeVia", "neo_action");
    // Resolve process info — mirror NeoButtonActionHelper / NeoProcessService logic
    Process classicProcess = col.getProcess();
    org.openbravo.client.application.Process obuiappProcess = col.getOBUIAPPProcess();
    if (classicProcess == null && obuiappProcess == null) {
      obuiappProcess = NeoAccessHelper.resolveFallbackObuiappProcess(col);
    }
    if (obuiappProcess != null) {
      fieldObj.put("processType", "OBUIAPP");
      String name = obuiappProcess.getName();
      fieldObj.put("processName", name != null ? name : "");
      fieldObj.put("processId", obuiappProcess.getId());
    } else if (classicProcess != null) {
      fieldObj.put("processType", "Classic");
      String name = classicProcess.getName();
      fieldObj.put("processName", name != null ? name : "");
      fieldObj.put("processId", classicProcess.getId());
    }
    // If no process resolved: triggerValue/action/invokeVia already set, omit process fields
  }

  private static String resolvePropertyName(Entity dalEntity, String dbColName) {
    if (dalEntity == null) {
      return dbColName;
    }
    try {
      Property prop = dalEntity.getPropertyByColumnName(dbColName);
      return prop != null ? prop.getName() : dbColName;
    } catch (Exception ignored) {
      return dbColName;
    }
  }

  private static boolean isReadOnlyColumn(Tab adTab, Column col) {
    String dbColName = col.getDBColumnName();
    String expectedPK = adTab.getTable().getDBTableName() + "_ID";
    return expectedPK.equalsIgnoreCase(dbColName)
        || "DocumentNo".equalsIgnoreCase(dbColName)
        || Boolean.TRUE.equals(col.isUseAutomaticSequence());
  }

  private static void addDefaultExpression(JSONObject fieldObj, Column col) throws JSONException {
    String defaultExpr = col.getDefaultValue();
    if (defaultExpr == null || defaultExpr.trim().isEmpty()) {
      return;
    }
    defaultExpr = defaultExpr.trim();
    boolean isLegacyZeroFkSentinel = "0".equals(defaultExpr)
        && col.getDBColumnName().toUpperCase().endsWith("_ID");
    if (isLegacyZeroFkSentinel) {
      // "0" is a legacy AD placeholder meaning "resolve via callout/session logic" — it is not a
      // usable FK value. The resolved value is tenant-scoped (per client/org), so it must never be
      // baked into this structural schema; report shape/format only and point to neo_defaults.
      fieldObj.put("defaultSource", "server");
      fieldObj.put("defaultFormat", "32-char hex ID (FK)");
      fieldObj.put("defaultHint", "Resolved per-tenant at request time — call neo_defaults to get the value");
      return;
    }
    fieldObj.put("defaultExpression", defaultExpr);
  }

  private static void addVisibility(JSONObject fieldObj, String visibility, boolean mandatory)
      throws JSONException {
    if (visibility != null) {
      fieldObj.put("visibility", visibility);
      fieldObj.put("userRequired", "editable".equals(visibility) && mandatory);
    }
  }

  private static void addSelectorInfo(JSONObject fieldObj, String refId,
      java.util.Set<String> selectorRefs) throws JSONException {
    if (refId != null && selectorRefs.contains(refId)) {
      fieldObj.put("hasSelector", true);
      fieldObj.put("selectorType", mapSelectorType(refId));
    }
  }

  static Property resolveMandatoryProperty(Tab adTab, Entity dalEntity, Column col,
      java.util.Set<String> systemColumns) {
    if (!col.isActive() || !col.isMandatory()) {
      return null;
    }
    String dbColName = col.getDBColumnName();
    if (dbColName.equalsIgnoreCase(adTab.getTable().getDBTableName() + "_ID")
        || systemColumns.contains(dbColName.toUpperCase())) {
      return null;
    }
    try {
      return dalEntity.getPropertyByColumnName(dbColName);
    } catch (Exception ignored) {
      return null;
    }
  }

  static boolean isMandatoryValueMissing(JSONObject body, String propName) {
    if (!body.has(propName) || body.isNull(propName)) {
      return true;
    }
    Object value = body.opt(propName);
    return value instanceof String && ((String) value).isEmpty();
  }

  static JSONObject buildMissingFieldInfo(Column col, String propName,
      java.util.Set<String> selectorRefs) throws JSONException {
    JSONObject fieldInfo = new JSONObject();
    String refId = col.getReference() != null ? col.getReference().getId() : null;
    boolean isFK = selectorRefs.contains(refId);
    fieldInfo.put("name", propName);
    fieldInfo.put("column", col.getDBColumnName());
    fieldInfo.put("type", isFK ? "foreignKey" : "other");
    if (isFK) {
      fieldInfo.put("hasSelector", true);
    }
    fieldInfo.put("label", col.getName());
    return fieldInfo;
  }

  static void coercePrimitiveFieldValue(JSONObject body, String key, Property prop,
      org.apache.logging.log4j.Logger log) {
    Object value = body.opt(key);
    if (!(value instanceof String)) {
      return;
    }
    String strVal = (String) value;
    if (strVal.isEmpty()) {
      return;
    }
    try {
      Class<?> type = prop.getPrimitiveObjectType();
      if (type == Long.class) {
        body.put(key,
            Long.parseLong(strVal.contains(".") ? strVal.substring(0, strVal.indexOf('.')) : strVal));
      } else if (type == java.math.BigDecimal.class) {
        body.put(key, new java.math.BigDecimal(strVal));
      } else if (type == Boolean.class) {
        body.put(key, "Y".equalsIgnoreCase(strVal) || "true".equalsIgnoreCase(strVal));
      }
    } catch (Exception e) {
      log.debug("Could not coerce field {} value '{}': {}", key, strVal, e.getMessage());
    }
  }

  // ── Action result mapping (kept here to stay within McpToolRouter method-count limit) ─

  /**
   * Maps a {@link NeoResponse} body to the structured MCP action result keys.
   * Handles both top-level {@code {status, message}} bodies (from
   * {@code NeoProcessService.translate*Result}) and nested
   * {@code {"error":{status, message}}} bodies (from {@code NeoResponse.error()}).
   * Extra keys from the process result are passed through unchanged.
   */
  static JSONObject mapNeoResponseToActionResult(NeoResponse neoResponse) throws JSONException {
    JSONObject actionResult = new JSONObject();
    JSONObject body = neoResponse.getBody();
    if (body == null) {
      return actionResult;
    }
    String status = body.optString(McpConstants.KEY_STATUS, null);
    String message = body.optString(McpConstants.KEY_MESSAGE, null);

    if (status == null && message == null) {
      status = resolveStatusFromErrorBody(body);
      JSONObject errorObj = body.optJSONObject(McpConstants.KEY_ERROR);
      if (errorObj != null) {
        message = errorObj.optString(McpConstants.KEY_MESSAGE, null);
      }
    }

    if (status != null) {
      actionResult.put(McpConstants.KEY_PROCESS_RESULT, status);
    }
    if (message != null) {
      actionResult.put(McpConstants.KEY_PROCESS_MESSAGE, message);
    }
    java.util.Iterator<String> keys = body.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!McpConstants.KEY_STATUS.equals(key) && !McpConstants.KEY_MESSAGE.equals(key)
          && !McpConstants.KEY_ERROR.equals(key)) {
        actionResult.put(key, body.get(key));
      }
    }
    return actionResult;
  }

  /**
   * Resolves the status string from a nested error body produced by
   * {@code NeoResponse.error(int, String)}.
   */
  static String resolveStatusFromErrorBody(JSONObject body) {
    JSONObject errorObj = body.optJSONObject(McpConstants.KEY_ERROR);
    if (errorObj == null) {
      return null;
    }
    String status = errorObj.optString(McpConstants.KEY_STATUS, null);
    if (status != null) {
      return status;
    }
    int errorStatus = errorObj.optInt(McpConstants.KEY_STATUS, -1);
    return errorStatus > 0 ? String.valueOf(errorStatus) : null;
  }

  // ── List filter helpers (kept here to stay within McpToolRouter method-count limit) ─

  /**
   * Build an HQL where clause fragment from MCP filter key-value pairs.
   * Filters are applied as exact-match conditions using the DAL property name.
   */
  static String buildWhereFromFilters(JSONObject filters, Tab adTab,
      org.apache.logging.log4j.Logger log) throws JSONException {
    Entity dalEntity = org.openbravo.base.model.ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEntity == null) {
      return null;
    }

    StringBuilder where = new StringBuilder();
    java.util.Iterator<String> keys = filters.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      String value = filters.getString(key);
      appendFilterCondition(where, dalEntity, key, value, log);
    }
    return where.length() > 0 ? where.toString() : null;
  }

  /**
   * Resolve a single filter key to a DAL property and append an HQL condition.
   */
  private static void appendFilterCondition(StringBuilder where, Entity dalEntity,
      String key, String value, org.apache.logging.log4j.Logger log) {
    Property prop = null;
    try {
      prop = dalEntity.getPropertyByColumnName(key);
    } catch (Exception ignored) {
      try {
        prop = dalEntity.getProperty(key);
      } catch (Exception alsoIgnored) {
        log.debug("Filter column '{}' not found in entity, skipping", key);
      }
    }

    if (prop == null) {
      log.warn("Filter key '{}' could not be resolved to a DAL property, ignoring", key);
      return;
    }

    if (where.length() > 0) {
      where.append(" and ");
    }
    String escaped = value.replace("'", "''");
    if (!prop.isPrimitive()) {
      where.append("e.").append(prop.getName()).append(".id='").append(escaped).append("'");
    } else {
      where.append("e.").append(prop.getName()).append("='").append(escaped).append("'");
    }
  }

  /**
   * Wraps a flat JSON body into the structure expected by DefaultJsonDataService.
   * Identical to NeoServlet.wrapForSmartclient().
   */
  static String wrapForSmartclient(JSONObject filteredBody, String dalEntityName,
      String recordId, org.apache.logging.log4j.Logger log) {
    try {
      JSONObject data = filteredBody != null ? filteredBody : new JSONObject();
      data.put(org.openbravo.service.json.JsonConstants.ENTITYNAME, dalEntityName);
      if (recordId != null) {
        data.put(org.openbravo.service.json.JsonConstants.ID, recordId);
      } else {
        data.put(org.openbravo.service.json.JsonConstants.NEW_INDICATOR, true);
      }

      JSONObject wrapper = new JSONObject();
      wrapper.put(org.openbravo.service.json.JsonConstants.DATA, data);
      return wrapper.toString();
    } catch (Exception e) {
      log.error("Error wrapping body for Smartclient format: {}", e.getMessage(), e);
      return "{}";
    }
  }

  /**
   * Validate that the given required arguments are present and non-null in {@code args}.
   * Shared by {@link McpToolRouter} and {@link McpWidgetHandler} so the contract (and the
   * error messages tests assert on) lives in a single place.
   *
   * @param args     the tool arguments (may be {@code null})
   * @param required the argument keys that must be present
   * @throws IllegalArgumentException when {@code args} is {@code null} or a key is missing
   */
  static void validateArgs(JSONObject args, String... required) {
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
