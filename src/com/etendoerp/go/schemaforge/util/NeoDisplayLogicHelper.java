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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.expression.OBScriptEngine;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.application.DynamicExpressionParser;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.DimensionDisplayUtility;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Static helpers for display logic and readOnly logic evaluation.
 */
public final class NeoDisplayLogicHelper {

  private static final Logger log = LogManager.getLogger(NeoDisplayLogicHelper.class);

  private static final String OB_UTILITIES_SHIM =
      "var OB = { Utilities: { "
      + "getValue: function(obj, key) { return obj != null ? obj[key] : null; }, "
      + "Date: { JSToOB: function(v) { return v; } } }, "
      + "Format: { date: '' } };";

  private NeoDisplayLogicHelper() {
  }

  public static NeoResponse handleEvaluateDisplay(SFSpec spec,
      com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo pathInfo,
      HttpServletRequest request) {
    try {
      SFEntity sfEntity = findEntity(spec.getId(), pathInfo.entityName);
      if (sfEntity == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
            "Entity not found: " + pathInfo.entityName);
      }
      Tab tab = sfEntity.getADTab();
      if (tab == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Entity has no linked AD_Tab: " + pathInfo.entityName);
      }
      JSONObject fieldValues = new JSONObject();
      try {
        String body = new String(
            request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (body != null && !body.trim().isEmpty()) {
          JSONObject bodyJson = new JSONObject(body);
          if (bodyJson.has("fieldValues")) {
            fieldValues = bodyJson.getJSONObject("fieldValues");
          }
        }
      } catch (Exception e) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid request body");
      }
      Map<String, Object> evalContext = buildEvalContext(fieldValues);
      JSONObject visibility = new JSONObject();
      JSONObject readOnly = new JSONObject();
      List<Field> fields = tab.getADFieldList();
      for (Field field : fields) {
        if (!field.isActive()) {
          continue;
        }
        String propertyName = getPropertyName(field);
        String displayLogic = field.getDisplayLogic();
        if (displayLogic != null && !displayLogic.trim().isEmpty()) {
          boolean isVisible = evaluateExpression(displayLogic, tab, field, evalContext, false);
          visibility.put(propertyName, isVisible);
        }
        Column column = field.getColumn();
        if (column != null) {
          String readOnlyLogic = column.getReadOnlyLogic();
          if (readOnlyLogic != null && !readOnlyLogic.trim().isEmpty()) {
            boolean isReadOnly = evaluateExpression(readOnlyLogic, tab, field, evalContext, true);
            readOnly.put(propertyName, isReadOnly);
          }
        }
      }
      JSONObject result = new JSONObject();
      result.put("visibility", visibility);
      result.put("readOnly", readOnly);
      return NeoResponse.ok(result);
    } catch (Exception e) {
      log.error("Error evaluating display logic for {}/{}", pathInfo.specName,
          pathInfo.entityName, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Error evaluating display logic: " + e.getMessage());
    }
  }

  public static boolean evaluateExpression(String expression, Tab tab, Field field,
      Map<String, Object> evalContext, boolean isReadOnlyLogic) {
    try {
      DynamicExpressionParser parser = new DynamicExpressionParser(expression, tab, field);
      String jsExpr = parser.getJSExpression();
      if (jsExpr == null || jsExpr.trim().isEmpty()) {
        return true;
      }
      List<String> sessionAttrs = parser.getSessionAttributes();
      if (!sessionAttrs.isEmpty()) {
        try {
          OBContext obCtx = OBContext.getOBContext();
          DalConnectionProvider conn = new DalConnectionProvider(false);
          VariablesSecureApp vars = new VariablesSecureApp(
              obCtx.getUser().getId(),
              obCtx.getCurrentClient().getId(),
              obCtx.getCurrentOrganization().getId(),
              obCtx.getRole().getId(),
              obCtx.getLanguage().getLanguage());
          for (String attr : sessionAttrs) {
            if (!evalContext.containsKey(attr)) {
              evalContext.put(attr, Utility.getContext(conn, vars, attr, ""));
            }
          }
        } catch (Exception e) {
          log.debug("Could not resolve session attributes for expression '{}': {}",
              expression, e.getMessage());
        }
      }
      String contextPreamble = buildJsObjectPreamble("context", evalContext, true);
      @SuppressWarnings("unchecked")
      Map<String, Object> currentValues = (Map<String, Object>) evalContext.get("currentValues");
      String cvPreamble = buildJsObjectPreamble("currentValues", currentValues, false);
      String fullScript = OB_UTILITIES_SHIM + "\n" + contextPreamble + "\n" + cvPreamble + "\n" + jsExpr;
      Object result = OBScriptEngine.getInstance().eval(fullScript, evalContext);
      return Boolean.TRUE.equals(result);
    } catch (Exception e) {
      log.warn("Failed to evaluate expression: {} for field: {}",
          expression, field != null ? field.getName() : "tab-level", e);
      return true;
    }
  }

  @SuppressWarnings("unchecked")
  public static String buildJsObjectPreamble(String varName, Map<String, Object> map,
      boolean skipSelf) {
    org.codehaus.jettison.json.JSONObject obj = new org.codehaus.jettison.json.JSONObject();
    if (map != null) {
      for (Map.Entry<String, Object> e : map.entrySet()) {
        String key = e.getKey();
        if (skipSelf && ("context".equals(key) || "currentValues".equals(key))) {
          continue;
        }
        Object val = e.getValue();
        if (val == null || val instanceof Map) {
          continue;
        }
        try {
          obj.put(key, val.toString());
        } catch (Exception ex) {
          // skip un-serializable entries
        }
      }
    }
    return "var " + varName + " = " + obj.toString() + ";";
  }

  public static Map<String, Object> buildEvalContext(JSONObject fieldValues) {
    Map<String, Object> ctx = new HashMap<>();
    Map<String, Object> currentValues = new HashMap<>();
    @SuppressWarnings("unchecked")
    java.util.Iterator<String> keys = fieldValues.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = fieldValues.opt(key);
      currentValues.put(key, value == JSONObject.NULL ? null : value);
    }
    ctx.put("currentValues", currentValues);
    ctx.putAll(currentValues);
    OBContext obCtx = OBContext.getOBContext();
    ctx.put("AD_Org_ID", obCtx.getCurrentOrganization().getId());
    ctx.put("AD_Client_ID", obCtx.getCurrentClient().getId());
    ctx.put("AD_Role_ID", obCtx.getRole().getId());
    ctx.put("AD_User_ID", obCtx.getUser().getId());
    try {
      org.openbravo.model.ad.system.Client client = OBDal.getInstance()
          .get(org.openbravo.model.ad.system.Client.class, obCtx.getCurrentClient().getId());
      boolean isCentrally = client.isAcctdimCentrallyMaintained();
      ctx.put(DimensionDisplayUtility.IsAcctDimCentrally, isCentrally ? "Y" : "N");
      if (isCentrally) {
        Map<String, String> acctDimMap = DimensionDisplayUtility
            .getAccountingDimensionConfiguration(client);
        ctx.putAll(acctDimMap);
      } else {
        DalConnectionProvider conn = new DalConnectionProvider(false);
        VariablesSecureApp vars = new VariablesSecureApp(
            obCtx.getUser().getId(),
            obCtx.getCurrentClient().getId(),
            obCtx.getCurrentOrganization().getId(),
            obCtx.getRole().getId(),
            obCtx.getLanguage().getLanguage());
        String[] elements = { "MC", "AY", "OT", "AS", "CC", "U1", "U2", "PJ", "BU", "PR", "BP", "OO" };
        for (String el : elements) {
          String key = "$Element_" + el;
          ctx.put(key, Utility.getContext(conn, vars, key, ""));
        }
      }
      String transactionDocId = currentValues.containsKey("transactionDocument")
          ? String.valueOf(currentValues.get("transactionDocument"))
          : "";
      if (!transactionDocId.isEmpty() && !transactionDocId.equals("null")) {
        org.openbravo.model.common.enterprise.DocumentType docType = OBDal.getInstance()
            .get(org.openbravo.model.common.enterprise.DocumentType.class, transactionDocId);
        if (docType != null && docType.getDocumentCategory() != null) {
          currentValues.put("DOCBASETYPE", docType.getDocumentCategory());
          ctx.put("DOCBASETYPE", docType.getDocumentCategory());
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve accounting dimension context: {}", e.getMessage());
    }
    return ctx;
  }

  public static String getPropertyName(Field field) {
    Column column = field.getColumn();
    if (column == null) {
      return field.getName();
    }
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(column.getTable().getId());
    if (dalEntity != null) {
      Property prop = dalEntity.getPropertyByColumnName(column.getDBColumnName());
      if (prop != null) {
        return prop.getName();
      }
    }
    return column.getDBColumnName();
  }

  private static SFEntity findEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, entityName));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    java.util.List<SFEntity> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }
}
