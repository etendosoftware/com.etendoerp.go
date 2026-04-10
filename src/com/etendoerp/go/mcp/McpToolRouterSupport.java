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

import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

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

  static boolean hasSpecAccess(SFSpec spec, String specType) {
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
    if (entities != null) {
      specObj.put("entities", entities);
    }
    if ("R".equals(specType)) {
      specObj.put("isReport", true);
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

  static Map<String, String> loadVisibilityByColumnId(SFEntity sfEntity) {
    Map<String, String> visibilityByColumnId = new HashMap<>();
    OBCriteria<SFField> fieldCrit = OBDal.getInstance().createCriteria(SFField.class);
    fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
    fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
    for (SFField sfField : fieldCrit.list()) {
      Column adCol = sfField.getADColumn();
      String visibility = (String) sfField.get("visibility");
      if (adCol != null && visibility != null && !visibility.trim().isEmpty()) {
        visibilityByColumnId.put((String) adCol.getId(), visibility.trim());
      }
    }
    return visibilityByColumnId;
  }

  static JSONArray buildSchemaFieldsArray(Tab adTab, Entity dalEntity,
      Map<String, String> visibilityByColumnId, java.util.Set<String> systemColumns,
      java.util.Set<String> selectorRefs) throws JSONException {
    JSONArray fieldsArray = new JSONArray();
    for (Column col : adTab.getTable().getADColumnList()) {
      if (shouldIncludeSchemaColumn(col, systemColumns)) {
        fieldsArray.put(buildSchemaField(col, adTab, dalEntity, visibilityByColumnId, selectorRefs));
      }
    }
    return fieldsArray;
  }

  private static boolean shouldIncludeSchemaColumn(Column col, java.util.Set<String> systemColumns) {
    return col.isActive() && !systemColumns.contains(col.getDBColumnName().toUpperCase());
  }

  private static JSONObject buildSchemaField(Column col, Tab adTab, Entity dalEntity,
      Map<String, String> visibilityByColumnId, java.util.Set<String> selectorRefs)
      throws JSONException {
    String dbColName = col.getDBColumnName();
    String refId = col.getReference() != null ? (String) col.getReference().getId() : null;
    JSONObject fieldObj = new JSONObject();
    fieldObj.put("name", resolvePropertyName(dalEntity, dbColName));
    fieldObj.put("column", dbColName);
    fieldObj.put("label", col.getName());
    fieldObj.put("type", mapColumnType(refId));
    fieldObj.put("required", col.isMandatory());
    fieldObj.put("readOnly", isReadOnlyColumn(adTab, col));
    addDefaultExpression(fieldObj, col);
    addVisibility(fieldObj, visibilityByColumnId.get((String) col.getId()), col.isMandatory());
    addSelectorInfo(fieldObj, refId, selectorRefs);
    return fieldObj;
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
    if (defaultExpr != null && !defaultExpr.trim().isEmpty()) {
      fieldObj.put("defaultExpression", defaultExpr.trim());
    }
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
}
