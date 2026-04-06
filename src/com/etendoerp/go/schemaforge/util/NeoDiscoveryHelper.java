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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.ElementTrl;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Static helpers for discovery and spec-describe endpoints.
 */
public final class NeoDiscoveryHelper {

  private static final Logger log = LogManager.getLogger(NeoDiscoveryHelper.class);

  private static final Set<String> SELECTOR_REFS = new HashSet<>();

  static {
    SELECTOR_REFS.add("19"); // TableDir
    SELECTOR_REFS.add("18"); // Table
    SELECTOR_REFS.add("30"); // Search
    SELECTOR_REFS.add("95E2A8B50A254B2AAE6774B8C2F28120"); // OBUISEL
  }

  private static final Pattern VALIDATION_PARAM_PATTERN = Pattern.compile("@(\\w+)@");

  private NeoDiscoveryHelper() {
  }

  public static NeoResponse handleDiscovery() {
    try {
      OBCriteria<SFSpec> specCriteria = OBDal.getInstance().createCriteria(SFSpec.class);
      specCriteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
      List<SFSpec> allSpecs = specCriteria.list();

      JSONArray specsArray = new JSONArray();
      for (SFSpec spec : allSpecs) {
        String specType = spec.getSpecType();
        Window specWindow = spec.getADWindow();
        if ("W".equals(specType)) {
          if (specWindow != null && !NeoAccessHelper.hasWindowAccess(specWindow.getId())) {
            continue;
          }
        } else if ("P".equals(specType) || "R".equals(specType)) {
          Process adProcess = NeoAccessHelper.resolveProcess(spec);
          if (adProcess != null && !NeoAccessHelper.hasProcessAccess(adProcess.getId())) {
            continue;
          }
        }
        JSONObject specObj = new JSONObject();
        specObj.put("id", spec.getId());
        specObj.put("name", spec.getName());
        specObj.put("type", specType);
        specObj.put("description", spec.getDescription());
        if ("W".equals(specType)) {
          if (specWindow != null) specObj.put("windowId", specWindow.getId());
          specObj.put("entities", buildEntitySummaryArray(spec.getId()));
        } else if ("P".equals(specType) || "R".equals(specType)) {
          Process adProcess = NeoAccessHelper.resolveProcess(spec);
          if (adProcess != null) specObj.put("processId", adProcess.getId());
          if ("R".equals(specType)) specObj.put("isReport", true);
        }
        Module specModule = spec.getADModule();
        if (specModule != null) specObj.put("moduleId", specModule.getId());
        specsArray.put(specObj);
      }
      JSONObject result = new JSONObject();
      result.put("specs", specsArray);
      return NeoResponse.ok(result);
    } catch (Exception e) {
      log.error("Error in discovery endpoint: {}", e.getMessage(), e);
      return NeoResponse.error(500, "Discovery error: " + e.getMessage());
    }
  }

  public static NeoResponse handleSpecDescribe(SFSpec spec) {
    try {
      String specId = spec.getId();
      String specType = spec.getSpecType();
      JSONObject result = new JSONObject();
      result.put("id", spec.getId());
      result.put("name", spec.getName());
      result.put("type", specType);
      result.put("description", spec.getDescription());
      Module specModule = spec.getADModule();
      if (specModule != null) result.put("moduleId", specModule.getId());

      OBCriteria<SFEntity> entityCriteria = OBDal.getInstance().createCriteria(SFEntity.class);
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
      entityCriteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
      List<SFEntity> entities = entityCriteria.list();

      JSONArray entitiesArray = new JSONArray();
      for (SFEntity entity : entities) {
        JSONObject entityObj = new JSONObject();
        entityObj.put("id", entity.getId());
        entityObj.put("name", entity.getName());
        entityObj.put("methods", buildMethodsArray(entity));
        Tab adTab = entity.getADTab();
        if (adTab != null) {
          entityObj.put("tabLevel", adTab.getTabLevel());
          entityObj.put("tabId", adTab.getId());
        }
        entityObj.put("isGet", Boolean.TRUE.equals(entity.isGet()));
        entityObj.put("isGetbyid", Boolean.TRUE.equals(entity.isGetByID()));
        entityObj.put("isPost", Boolean.TRUE.equals(entity.isPost()));
        entityObj.put("isPut", Boolean.TRUE.equals(entity.isPut()));
        entityObj.put("isPatch", Boolean.TRUE.equals(entity.isPatch()));
        entityObj.put("isDelete", Boolean.TRUE.equals(entity.isDelete()));
        entityObj.put("fields", buildFieldsArray(entity.getId()));
        entitiesArray.put(entityObj);
      }
      result.put("entities", entitiesArray);
      return NeoResponse.ok(result);
    } catch (Exception e) {
      log.error("Error describing spec '{}': {}", spec.getName(), e.getMessage(), e);
      return NeoResponse.error(500, "Spec describe error: " + e.getMessage());
    }
  }

  public static JSONArray buildEntitySummaryArray(String specId) throws Exception {
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

  public static JSONArray buildMethodsArray(SFEntity entity) {
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

  public static JSONArray buildFieldsArray(String entityId) throws Exception {
    Language lang = OBContext.getOBContext().getLanguage();
    OBCriteria<SFField> criteria = OBDal.getInstance().createCriteria(SFField.class);
    criteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
    List<SFField> fields = criteria.list();
    JSONArray arr = new JSONArray();
    for (SFField field : fields) {
      Column column = field.getADColumn();
      if (column == null) {
        continue;
      }
      String refId = column.getReference() != null
          ? (String) column.getReference().getId() : null;
      JSONObject fieldObj = new JSONObject();
      fieldObj.put("id", field.getId());
      fieldObj.put("columnId", column.getId());
      fieldObj.put("name", column.getDBColumnName());
      fieldObj.put("label", getTranslatedColumnLabel(column, lang));
      fieldObj.put("columnType", mapReferenceToType(refId));
      fieldObj.put("readOnly", Boolean.TRUE.equals(field.isReadOnly()));
      fieldObj.put("included", Boolean.TRUE.equals(field.isIncluded()));
      fieldObj.put("required", column.isMandatory());
      boolean hasSelector = isSelectorReference(refId);
      fieldObj.put("hasSelector", hasSelector);
      if (hasSelector) {
        fieldObj.put("selectorType", mapSelectorType(refId));
        JSONArray selectorParams = extractValidationParams(column);
        if (selectorParams.length() > 0) {
          fieldObj.put("selectorParams", selectorParams);
        }
      }
      arr.put(fieldObj);
    }
    return arr;
  }

  /**
   * Resolve the translated name for a column's AD_Element for the given language.
   * Falls back to the English column name if no translation is found.
   */
  private static String getTranslatedColumnLabel(Column column, Language lang) {
    if (column.getApplicationElement() == null || lang == null) {
      return column.getName();
    }
    OBCriteria<ElementTrl> criteria = OBDal.getInstance().createCriteria(ElementTrl.class);
    criteria.add(Restrictions.eq(ElementTrl.PROPERTY_APPLICATIONELEMENT, column.getApplicationElement()));
    criteria.add(Restrictions.eq(ElementTrl.PROPERTY_LANGUAGE, lang));
    criteria.setMaxResults(1);
    ElementTrl trl = (ElementTrl) criteria.uniqueResult();
    return (trl != null && trl.getName() != null) ? trl.getName() : column.getName();
  }

  private static boolean isSelectorReference(String refId) {
    return refId != null && SELECTOR_REFS.contains(refId);
  }

  public static JSONArray extractValidationParams(Column column) {
    JSONArray params = new JSONArray();
    org.openbravo.model.ad.domain.Validation valRule = column.getValidation();
    if (valRule == null || valRule.getValidationCode() == null) {
      return params;
    }
    Set<String> seen = new HashSet<>();
    Matcher m = VALIDATION_PARAM_PATTERN.matcher(valRule.getValidationCode());
    while (m.find()) {
      String param = m.group(1);
      if (!seen.contains(param)) {
        params.put(param);
        seen.add(param);
      }
    }
    return params;
  }

  public static String mapSelectorType(String refId) {
    if (refId == null) return null;
    switch (refId) {
      case "19":
        return "TableDir";
      case "18":
        return "Table";
      case "30":
        return "Search";
      case "95E2A8B50A254B2AAE6774B8C2F28120":
        return "OBUISEL";
      default:
        return null;
    }
  }

  public static String mapReferenceToType(String refId) {
    if (refId == null) return "string";
    switch (refId) {
      case "10":
      case "14":
      case "34":
        return "string";
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
      default:
        return "string";
    }
  }
}
