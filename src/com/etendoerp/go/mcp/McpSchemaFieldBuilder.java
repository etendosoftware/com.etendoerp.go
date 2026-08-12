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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.model.ad.ui.FieldTrl;
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

  static final String KEY_DEFAULT_EXPRESSION = "defaultExpression";
  static final String KEY_DEFAULT_SOURCE = "defaultSource";
  static final String KEY_USER_REQUIRED = "userRequired";
  static final String VISIBILITY_EDITABLE = "editable";
  static final String VISIBILITY_DISCARDED = "discarded";
  static final String TYPE_BUTTON = McpActionsView.TYPE_BUTTON;
  static final String KEY_INVOKE_VIA = "invokeVia";
  static final String KEY_INVOKABLE = "invokable";
  static final String KEY_NOT_INVOKABLE_REASON = "notInvokableReason";
  /** AD column of the accounting trigger, present on every accountable document. */
  private static final String COLUMN_POSTED = "Posted";
  private static final String EM_PREFIX = "EM_";

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
      String visibility = sfField.getVisibility();
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

  /**
   * Loads clean, localized {@code {label, description}} pairs for the tab's fields, keyed by
   * upper-cased DB column name. The label comes from {@code AD_Field.name} and the one-line
   * description from {@code AD_Field.description} — both translated into {@code langCode} via
   * {@code ADFieldTrl} when a translation exists — so {@code neo_schema} surfaces the same
   * functional label the Etendo UI shows instead of the raw {@code AD_Column} name
   * (e.g. "SII Description" rather than "EM_Aeatsii_Descripcion_Sii"). (IMP-1, ref §7.1)
   *
   * <p>A column may back more than one field in the tab; the first active field wins (the tab's
   * field list is sequence-ordered). Never throws — on any failure the base-language label is used,
   * and columns with no field fall back to the {@code AD_Column} name kept by the array builder.</p>
   */
  static Map<String, String[]> loadFieldLabels(Tab adTab, String langCode) {
    Map<String, String[]> byColumn = new HashMap<>();
    if (adTab == null) {
      return byColumn;
    }
    Map<String, Field> fieldByColumnName = new HashMap<>();
    List<String> fieldIds = new ArrayList<>();
    for (Field field : adTab.getADFieldList()) {
      if (!Boolean.TRUE.equals(field.isActive()) || field.getColumn() == null) {
        continue;
      }
      String colName = field.getColumn().getDBColumnName().toUpperCase();
      if (fieldByColumnName.putIfAbsent(colName, field) == null) {
        fieldIds.add((String) field.getId());
      }
    }
    Map<String, String[]> trlById = loadFieldTranslations(fieldIds, langCode);
    for (Map.Entry<String, Field> entry : fieldByColumnName.entrySet()) {
      Field field = entry.getValue();
      String[] trl = trlById.get((String) field.getId());
      String label = trl != null && StringUtils.isNotBlank(trl[0]) ? trl[0] : field.getName();
      String description = trl != null && StringUtils.isNotBlank(trl[1])
          ? trl[1] : field.getDescription();
      byColumn.put(entry.getKey(), new String[] { label, description });
    }
    return byColumn;
  }

  /**
   * Resolves the {@code {name, description}} translation of the given AD_Field ids into
   * {@code langCode} from {@code ADFieldTrl}. Runs in admin mode ({@code ADFieldTrl} is not
   * readable under a restricted NEO role) and never throws — an empty map means callers use the
   * base-language field text. Mirrors the {@code *_Trl} lookup convention in {@code NeoTrl}.
   */
  private static Map<String, String[]> loadFieldTranslations(List<String> fieldIds,
      String langCode) {
    Map<String, String[]> byId = new HashMap<>();
    if (fieldIds.isEmpty() || StringUtils.isBlank(langCode)) {
      return byId;
    }
    boolean adminMode = false;
    try {
      OBContext.setAdminMode(true);
      adminMode = true;
      OBCriteria<FieldTrl> crit = OBDal.getInstance().createCriteria(FieldTrl.class);
      crit.add(Restrictions.in(FieldTrl.PROPERTY_FIELD + ".id", fieldIds));
      crit.add(Restrictions.eq(FieldTrl.PROPERTY_LANGUAGE + ".language", langCode));
      for (FieldTrl trl : crit.list()) {
        byId.put((String) trl.getField().getId(),
            new String[] { trl.getName(), trl.getDescription() });
      }
    } catch (Exception e) {
      // Translation table not readable / language missing → fall back to base-language labels.
      return new HashMap<>();
    } finally {
      if (adminMode) {
        OBContext.restorePreviousMode();
      }
    }
    return byId;
  }

  /**
   * Overlays clean, localized labels and one-line descriptions (see {@link #loadFieldLabels}) onto
   * an already-built schema array, keyed by each field's {@code column}. Applied as a
   * post-processing pass so the array/field builders keep their parameter budget (Sonar S107) and
   * this overlay stays independently unit-testable. A blank label/description leaves the existing
   * value untouched. (IMP-1)
   */
  static void applyCuratedLabels(JSONArray fieldsArray, Map<String, String[]> labelsByColumn)
      throws JSONException {
    if (fieldsArray == null || labelsByColumn == null || labelsByColumn.isEmpty()) {
      return;
    }
    for (int i = 0; i < fieldsArray.length(); i++) {
      overlayCuratedLabel(fieldsArray.optJSONObject(i), labelsByColumn);
    }
  }

  /**
   * Overlay a single field's curated label/description (helper for {@link #applyCuratedLabels}).
   * Returns early — leaving the field untouched — when the field, its {@code column}, or a matching
   * curated entry is absent, or when the curated value is blank.
   */
  private static void overlayCuratedLabel(JSONObject field, Map<String, String[]> labelsByColumn)
      throws JSONException {
    if (field == null) {
      return;
    }
    String column = field.optString("column", null);
    if (column == null) {
      return;
    }
    String[] labelDesc = labelsByColumn.get(column.toUpperCase());
    if (labelDesc == null) {
      return;
    }
    if (StringUtils.isNotBlank(labelDesc[0])) {
      field.put(McpConstants.KEY_LABEL, labelDesc[0]);
    }
    if (StringUtils.isNotBlank(labelDesc[1])) {
      field.put(McpConstants.KEY_DESCRIPTION, labelDesc[1]);
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
    boolean isButton = TYPE_BUTTON.equals(type);
    JSONObject fieldObj = new JSONObject();
    fieldObj.put("name", resolvePropertyName(dalEntity, dbColName));
    fieldObj.put("column", dbColName);
    fieldObj.put(McpConstants.KEY_LABEL, col.getName());
    fieldObj.put("type", type);
    if (!isButton) {
      // IMP-21: a button carries no payload value, so AD's NOT NULL flag says nothing about what
      // the agent must send. Emitting it made 10 of the 22 sales-invoice actions claim
      // required:true right next to an honest userRequired:false. See addButtonInfo.
      fieldObj.put("required", col.isMandatory());
    }
    fieldObj.put("readOnly", isReadOnlyColumn(adTab, col));
    addDefaultExpression(fieldObj, col);
    String visibility = visibilityByColumnId.get((String) col.getId());
    addVisibility(fieldObj, visibility, !isButton && col.isMandatory());
    boolean isBusinessCritical = Boolean.TRUE.equals(
        businessCriticalByColumnId.get((String) col.getId()));
    addAgentPrompt(fieldObj, promptByColumnId.get((String) col.getId()));
    addSelectorInfo(fieldObj, refId, selectorRefs);
    if (isButton) {
      addButtonInfo(fieldObj, col, visibility);
      isBusinessCritical = isBusinessCritical || isCriticalAction(fieldObj, dbColName);
    }
    fieldObj.put("businessCritical", isBusinessCritical);
    return fieldObj;
  }

  private static void addAgentPrompt(JSONObject fieldObj, String agentPrompt) throws JSONException {
    if (agentPrompt != null && !agentPrompt.trim().isEmpty()) {
      fieldObj.put("agentPrompt", agentPrompt.trim());
    }
  }

  /**
   * Describes a {@code type:"button"} column as an invokable action (IMP-6's {@code
   * view:"actions"} catalog is a filter over these).
   *
   * <p><b>IMP-21 — {@code invokeVia} is now a claim, not a decoration.</b> It used to be written
   * unconditionally, so the sales-invoice catalog advertised all 22 buttons as callable via
   * {@code neo_action} even though 17 were curated {@code visibility:"discarded"} and one
   * ({@code CreateFrom}) resolves no process at all — there is nothing for {@code neo_action} to
   * run. An agent had no way to tell the 22 apart. Now a button carries {@code
   * invokeVia:"neo_action"} only when it really is invokable, and otherwise says so explicitly
   * with {@code invokable:false} plus a machine-readable {@code notInvokableReason}. The button
   * still appears in the catalog — knowing an action exists but is out of scope is useful; being
   * told it is callable when it is not is not.</p>
   *
   * @param fieldObj   the field object being built, mutated in place
   * @param col        the button AD column
   * @param visibility the curated visibility for this column, or {@code null} when uncurated
   */
  private static void addButtonInfo(JSONObject fieldObj, Column col, String visibility)
      throws JSONException {
    fieldObj.put("triggerValue", "Y");
    fieldObj.put("action", col.getDBColumnName());
    addActionValues(fieldObj, col);
    // Resolve process info — mirror NeoButtonActionHelper / NeoProcessService logic
    Process classicProcess = col.getProcess();
    org.openbravo.client.application.Process obuiappProcess = col.getOBUIAPPProcess();
    if (classicProcess == null && obuiappProcess == null) {
      obuiappProcess = NeoAccessHelper.resolveFallbackObuiappProcess(col);
    }
    String processName = null;
    if (obuiappProcess != null) {
      fieldObj.put("processType", "OBUIAPP");
      processName = obuiappProcess.getName();
      fieldObj.put("processName", processName != null ? processName : "");
      fieldObj.put("processId", obuiappProcess.getId());
    } else if (classicProcess != null) {
      fieldObj.put("processType", "Classic");
      processName = classicProcess.getName();
      fieldObj.put("processName", processName != null ? processName : "");
      fieldObj.put("processId", classicProcess.getId());
    }
    applyActionLabelFallback(fieldObj, col, processName);
    addInvokability(fieldObj, visibility, processName != null);
  }

  /**
   * Declares whether {@code neo_action} can actually run this button (IMP-21).
   *
   * <p>Two independent blockers, reported in the order an agent would care about: a curated
   * {@code discarded} means the action was deliberately kept out of this window's agent surface,
   * and a missing process means AD has nothing wired behind the column. An uncurated button (no
   * {@code visibility} row at all) with a process is treated as invokable — that is the
   * pre-IMP-21 behaviour and the only safe default, since absence of curation is not a decision.
   * </p>
   */
  private static void addInvokability(JSONObject fieldObj, String visibility, boolean hasProcess)
      throws JSONException {
    String blocker = null;
    if (VISIBILITY_DISCARDED.equals(visibility)) {
      blocker = "discarded: this action is not part of the curated agent surface for this window";
    } else if (!hasProcess) {
      blocker = "no process: the AD button column has no process wired behind it";
    }
    if (blocker == null) {
      fieldObj.put(KEY_INVOKE_VIA, "neo_action");
      return;
    }
    fieldObj.put(KEY_INVOKABLE, false);
    fieldObj.put(KEY_NOT_INVOKABLE_REASON, blocker);
  }

  /**
   * Replaces a raw module-extension column name with something an agent can read (IMP-21).
   *
   * <p>A button's label defaults to {@code AD_Column.name}, which for a column contributed by a
   * module is the machine name the module author typed — {@code EM_Aeatsii_Dup},
   * {@code EM_Psd2_Generate Bank Payment}. Core buttons are unaffected because their column names
   * are already functional ({@code "Copy from"}, {@code "Document Action"}), and any button that
   * has an {@code AD_Field} in the tab is overwritten later by {@link #applyCuratedLabels} — so
   * this only ever fires for the module buttons that no tab field describes, which is exactly
   * where the raw names were surfacing.</p>
   *
   * <p>The process name is preferred over a mechanically de-prefixed column name because it is a
   * label a human wrote for this very action; the de-prefixed name is the last resort.</p>
   */
  private static void applyActionLabelFallback(JSONObject fieldObj, Column col, String processName)
      throws JSONException {
    String dbColName = col.getDBColumnName();
    if (!StringUtils.startsWithIgnoreCase(dbColName, EM_PREFIX)) {
      return;
    }
    if (StringUtils.isNotBlank(processName)) {
      fieldObj.put(McpConstants.KEY_LABEL, processName.trim());
      return;
    }
    String humanized = humanizeExtensionColumn(col.getName());
    if (StringUtils.isNotBlank(humanized)) {
      fieldObj.put(McpConstants.KEY_LABEL, humanized);
    }
  }

  /**
   * {@code "EM_Psd2_Generate Bank Payment"} → {@code "Generate Bank Payment"}: drops the
   * {@code EM_<module>_} extension prefix and turns the remaining underscores into spaces.
   */
  static String humanizeExtensionColumn(String rawName) {
    if (StringUtils.isBlank(rawName)) {
      return null;
    }
    String name = rawName.trim();
    if (!StringUtils.startsWithIgnoreCase(name, EM_PREFIX)) {
      return name;
    }
    // EM_<module>_<rest> — drop both the marker and the module prefix that follows it.
    int moduleEnd = name.indexOf('_', EM_PREFIX.length());
    String rest = moduleEnd < 0 ? name.substring(EM_PREFIX.length()) : name.substring(moduleEnd + 1);
    rest = rest.replace('_', ' ').trim();
    return rest.isEmpty() ? null : rest;
  }

  /**
   * Derives {@code businessCritical} for an action that curation left unflagged (IMP-21).
   *
   * <p>{@code ETGO_SF_FIELD.isBusinessCritical} has no producer for buttons: it is {@code N} on
   * every button column in the instance, so the flag was emitted {@code false} on all 22
   * sales-invoice actions and never discriminated anywhere. {@code false} is not a neutral
   * default — it reads as "nobody needs to think before firing this", which is the opposite of
   * true for the two actions that change a document's legal and accounting state.</p>
   *
   * <p>Both signals below are structural properties of core AD, not per-window judgement (which
   * belongs in {@code decisions.json} — see {@link #addActionValues}): a button bound to the
   * shared {@code docAction} list drives the document state machine, and {@code Posted} is the
   * accounting trigger present on every accountable document. Curation still wins — this only
   * fills the gap, it never clears a flag someone set.</p>
   */
  private static boolean isCriticalAction(JSONObject fieldObj, String dbColName) {
    return fieldObj.has(McpConstants.KEY_ACTION_PARAMETER)
        || COLUMN_POSTED.equalsIgnoreCase(dbColName);
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
      fieldObj.put(KEY_DEFAULT_SOURCE, "server");
      fieldObj.put("defaultFormat", "32-char hex ID (FK)");
      fieldObj.put("defaultHint", "Resolved per-tenant at request time — call neo_defaults to get the value");
      return;
    }
    fieldObj.put(KEY_DEFAULT_EXPRESSION, defaultExpr);
  }

  /**
   * @return {@code true} when this already-built descriptor is one the agent may legitimately send on
   *     a create: {@code editable} visibility and not read-only. Used by {@link McpSchemaCreateView}
   *     so the create projection can never admit a sequence-generated or computed field.
   */
  static boolean isAgentSuppliable(JSONObject fieldObj) {
    return fieldObj != null
        && VISIBILITY_EDITABLE.equals(fieldObj.optString("visibility", null))
        && !fieldObj.optBoolean("readOnly", false);
  }

  /**
   * Flags {@code userRequired} — "the agent MUST supply this in neo_create", exactly as the
   * {@code neo_schema} hint promises.
   *
   * <p>Being mandatory in AD is necessary but <b>not</b> sufficient: a mandatory column that carries
   * a default is filled by the session, the server or the declaring module, so demanding it from the
   * agent asks for a value we already have. On {@code sales-invoice/header} that was 5 of 11 flagged
   * fields — the invoice date ({@code @#Date@}), the currency ({@code @C_Currency_ID@}) and three
   * SII/VeriFactu compliance booleans defaulting to {@code N} — leaving 6 that genuinely are the
   * agent's to decide (IMP-12 §5).
   *
   * <p>Order matters: {@link #addDefaultExpression} runs immediately before this method
   * ({@code buildFieldObject}), so the default keys are already on {@code fieldObj} when we read
   * them. A precondition can still force the flag back on afterwards, via
   * {@link #applyPreconditionRequirement} — an explicit business rule outranks a column default.
   */
  private static void addVisibility(JSONObject fieldObj, String visibility, boolean mandatory)
      throws JSONException {
    if (visibility != null) {
      fieldObj.put("visibility", visibility);
      fieldObj.put(KEY_USER_REQUIRED, VISIBILITY_EDITABLE.equals(visibility) && mandatory
          && !hasSuppliedDefault(fieldObj));
    }
  }

  /**
   * @return {@code true} when something other than the agent already provides this field's value:
   *     either a literal/session AD default ({@code defaultExpression}) or the legacy {@code "0"} FK
   *     sentinel that {@link #addDefaultExpression} reports as {@code defaultSource:"server"}.
   */
  private static boolean hasSuppliedDefault(JSONObject fieldObj) {
    return fieldObj.has(KEY_DEFAULT_EXPRESSION) || fieldObj.has(KEY_DEFAULT_SOURCE);
  }

  private static void addSelectorInfo(JSONObject fieldObj, String refId,
      java.util.Set<String> selectorRefs) throws JSONException {
    if (refId != null && selectorRefs.contains(refId)) {
      fieldObj.put("hasSelector", true);
      fieldObj.put("selectorType", mapSelectorType(refId));
    }
  }
}
