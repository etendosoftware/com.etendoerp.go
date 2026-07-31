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
import java.util.Map;
import java.util.TreeSet;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Schema/field-metadata building for MCP discovery (neo_schema and related tools).
 *
 * <p>Extracted from {@link McpToolRouterSupport} (ETP-4510, Sonar S1448 — "too many
 * methods") — this class owns AD_Column → JSON field mapping: type/selector inference,
 * visibility, defaults, business-critical flags, button/process metadata, and the
 * per-entity field metadata load (visibility + businessCritical) used by neo_schema.</p>
 */
final class McpSchemaFieldBuilder {

  private McpSchemaFieldBuilder() {
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
    fieldCrit.add(Restrictions.eq(
        SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
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
    fieldCrit.add(Restrictions.eq(
        SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
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

  /**
   * Loads the per-field precondition requirement declared on {@code ETGO_SF_ENTITY.preconditions}
   * (ETP-4275). Returns a map keyed by NEO field (DAL property) name whose value is the rule's
   * {@code requiredWhen} condition, or an empty string when the field is unconditionally required.
   * Requirements are aggregated across all processes (a field required by any process is
   * reported); an unconditional rule wins over a conditional one for the same field.
   *
   * <p>{@code neo_schema} uses this to proactively signal {@code userRequired} to the agent, so it
   * does not have to discover the requirement by hitting the runtime process gate
   * ({@code NeoProcessPreconditionValidator}) — the two layers share this single declaration.</p>
   */
  static Map<String, String> loadPreconditionRequirements(SFEntity sfEntity) {
    Map<String, String> requiredWhenByField = new HashMap<>();
    if (sfEntity == null) {
      return requiredWhenByField;
    }
    Object raw;
    try {
      raw = sfEntity.get("preconditions");
    } catch (Exception e) {
      // Column not present in the runtime model yet (pre-migration) → no requirements.
      return requiredWhenByField;
    }
    if (raw == null || raw.toString().trim().isEmpty()) {
      return requiredWhenByField;
    }
    try {
      JSONObject byProcess = new JSONObject(raw.toString());
      java.util.Iterator<?> processIds = byProcess.keys();
      while (processIds.hasNext()) {
        JSONArray rules = byProcess.optJSONArray((String) processIds.next());
        if (rules != null) {
          collectFieldRequirements(rules, requiredWhenByField);
        }
      }
    } catch (JSONException e) {
      // Malformed declaration → fail open (no proactive signal); the runtime gate still guards.
      return new HashMap<>();
    }
    return requiredWhenByField;
  }

  private static void collectFieldRequirements(JSONArray rules, Map<String, String> out) {
    for (int i = 0; i < rules.length(); i++) {
      JSONObject rule = rules.optJSONObject(i);
      String field = rule == null ? null : rule.optString("field", null);
      if (field != null && !field.trim().isEmpty()) {
        String requiredWhen = rule.optString("requiredWhen", "");
        requiredWhen = requiredWhen == null ? "" : requiredWhen.trim();
        String existing = out.get(field);
        if (existing == null || requiredWhen.isEmpty()) {
          out.put(field, requiredWhen);
        }
      }
    }
  }

  /**
   * Applies the precondition-derived requirement to a field's schema. When the field is named in
   * {@code requiredWhenByField} it is flagged {@code userRequired: true}; a non-empty condition is
   * surfaced as {@code requiredWhen} so the agent knows the requirement is conditional. Mirrors
   * the runtime enforcement in {@code NeoProcessPreconditionValidator}.
   */
  static void applyPreconditionRequirement(JSONObject fieldObj, String propName,
      Map<String, String> requiredWhenByField) throws JSONException {
    if (propName == null || !requiredWhenByField.containsKey(propName)) {
      return;
    }
    fieldObj.put("userRequired", true);
    String requiredWhen = requiredWhenByField.get(propName);
    if (requiredWhen != null && !requiredWhen.trim().isEmpty()) {
      fieldObj.put("requiredWhen", requiredWhen.trim());
    }
  }

  /**
   * Overlays the precondition-derived requirement onto every field of an already-built schema
   * array (see {@link #applyPreconditionRequirement}). Applied as a post-processing pass by the
   * caller so the array/field builders keep their original parameter budget (Sonar S107) and this
   * overlay stays independently unit-testable.
   */
  static void applyPreconditionRequirements(JSONArray fieldsArray,
      Map<String, String> requiredWhenByField) throws JSONException {
    if (fieldsArray == null || requiredWhenByField.isEmpty()) {
      return;
    }
    for (int i = 0; i < fieldsArray.length(); i++) {
      JSONObject field = fieldsArray.optJSONObject(i);
      if (field != null) {
        applyPreconditionRequirement(field, field.optString("name", null), requiredWhenByField);
      }
    }
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
    addActionValues(fieldObj, col);
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

  /**
   * Emit the discrete values a list-backed button accepts, plus the parameter name they
   * travel under (ETP-4285).
   *
   * <p>A button column whose {@code AD_Reference_Value_ID} points at a list reference (e.g.
   * {@code C_Order.DocAction} → "Order_Document Action") has a closed value set. Without it an
   * agent sees the button but cannot know that {@code CO} books the document, nor that the
   * chosen value must be sent as {@code parameters.docAction}. Buttons with no reference value
   * — most process buttons ({@code Processing}, {@code CopyFrom}, {@code Calculate_Promotions}
   * …) — are left untouched.</p>
   *
   * <p>The emitted list is the full <em>active</em> AD list, which is deliberately broader than
   * what is legal for a given document in a given state: AD does not model the state machine.
   * Which value applies when is per-window judgement and travels in the field's
   * {@code agentPrompt} (see {@code docs/decisions-reference.md}), not in this generic layer.</p>
   *
   * <p>Sorted by value because {@link NeoSelectorService#getListLabels} returns an unordered
   * map — a stable schema is easier to diff, cache and assert on.</p>
   *
   * @param fieldObj the field object being built, mutated in place
   * @param col      the button AD column
   */
  private static void addActionValues(JSONObject fieldObj, Column col) throws JSONException {
    org.openbravo.model.ad.domain.Reference listRef = col.getReferenceSearchKey();
    if (listRef == null) {
      return;
    }
    Map<String, String> labels = NeoSelectorService.getListLabels((String) listRef.getId());
    if (labels == null || labels.isEmpty()) {
      return;
    }
    JSONArray values = new JSONArray();
    for (String value : new TreeSet<>(labels.keySet())) {
      JSONObject entry = new JSONObject();
      entry.put("value", value);
      entry.put("label", labels.get(value));
      values.put(entry);
    }
    fieldObj.put(McpConstants.KEY_ACTION_VALUES, values);
    fieldObj.put(McpConstants.KEY_ACTION_PARAMETER, McpConstants.PARAM_DOC_ACTION);
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
}
