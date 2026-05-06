/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.expression.OBScriptEngine;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.application.DynamicExpressionParser;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Handles display-logic and readOnly-logic evaluation for NEO Headless.
 *
 * <p>POST /sws/neo/{specName}/{entityName}/evaluate-display
 *
 * <p>Uses Etendo's DynamicExpressionParser to resolve session variables, preferences,
 * accounting dimensions, auxiliary inputs, and server-expanded macros.
 * Injects an OB.Utilities shim so the SmartClient-dependent JS output can be
 * evaluated by bare Rhino/OBScriptEngine.
 */
class NeoDisplayLogicHandler {

  private static final Logger log = LogManager.getLogger(NeoDisplayLogicHandler.class);

  /**
   * Minimal shim for SmartClient functions used by DynamicExpressionParser output.
   * DynamicExpressionParser generates JS like:
   *   OB.Utilities.getValue(currentValues, 'documentStatus') === 'CO'
   *   OB.Utilities.Date.JSToOB(OB.Utilities.getValue(currentValues,'orderDate'), OB.Format.date)
   *
   * These functions don't exist in a bare Rhino context. The shim provides:
   *   - getValue(obj, key) -> obj[key] (null-safe property accessor)
   *   - Date.JSToOB(value, format) -> value (pass-through; display logic only compares strings)
   *   - OB.Format.date -> empty string (unused by pass-through JSToOB)
   */
  private static final String OB_UTILITIES_SHIM =
      "var OB = { Utilities: { "
      + "getValue: function(obj, key) { return obj != null ? obj[key] : null; }, "
      + "Date: { JSToOB: function(v) { return v; } } }, "
      + "Format: { date: '' } };";

  private static final String ERR_ENTITY_NOT_FOUND = "Entity not found: ";
  private static final String ERR_NO_LINKED_TAB = "Entity has no linked AD_Tab: ";

  /**
   * Evaluates displayLogic and readOnlyLogic expressions for all fields of a tab.
   *
   * POST /sws/neo/{specName}/{entityName}/evaluate-display
   */
  NeoResponse handleEvaluateDisplay(SFSpec spec, NeoServlet.NeoPathInfo pathInfo,
      HttpServletRequest request) {
    try {
      SFEntity sfEntity = findEntity(spec.getId(), pathInfo.entityName);
      if (sfEntity == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
            ERR_ENTITY_NOT_FOUND + pathInfo.entityName);
      }

      Tab tab = sfEntity.getADTab();
      if (tab == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            ERR_NO_LINKED_TAB + pathInfo.entityName);
      }

      JSONObject fieldValues = parseFieldValuesFromRequest(request);
      if (fieldValues == null) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid request body");
      }

      Map<String, Object> evalContext = buildEvalContext(fieldValues);
      JSONObject result = evaluateAllFieldExpressions(tab, evalContext);
      return NeoResponse.ok(result);

    } catch (Exception e) {
      log.error("Error evaluating display logic for {}/{}", pathInfo.specName,
          pathInfo.entityName, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Error evaluating display logic: " + e.getMessage());
    }
  }

  /**
   * Evaluates displayLogic and readOnlyLogic for all active fields in the given tab.
   * Returns a JSON object with "visibility" and "readOnly" maps keyed by DAL property name.
   */
  private JSONObject evaluateAllFieldExpressions(Tab tab, Map<String, Object> evalContext)
      throws Exception {
    JSONObject visibility = new JSONObject();
    JSONObject readOnly = new JSONObject();
    for (Field field : tab.getADFieldList()) {
      if (!field.isActive()) {
        continue;
      }
      String propertyName = getPropertyName(field);
      String displayLogic = field.getDisplayLogic();
      if (displayLogic != null && !displayLogic.trim().isEmpty()) {
        visibility.put(propertyName, evaluateExpression(displayLogic, tab, field, evalContext));
      }
      Column column = field.getColumn();
      if (column != null) {
        String readOnlyLogic = column.getReadOnlyLogic();
        if (readOnlyLogic != null && !readOnlyLogic.trim().isEmpty()) {
          readOnly.put(propertyName, evaluateExpression(readOnlyLogic, tab, field, evalContext));
        }
      }
    }
    JSONObject result = new JSONObject();
    result.put("visibility", visibility);
    result.put("readOnly", readOnly);
    return result;
  }

  /**
   * Evaluates a single display logic or readOnly logic expression.
   * 4-step pipeline: preprocess -> parse -> shim -> eval.
   *
   * @param expression   the raw AD expression
   * @param tab          the AD_Tab for context resolution
   * @param field        the AD_Field for field-type detection (may be null for tab-level)
   * @param evalContext  the evaluation context with field values and session data
   * @return evaluation result; on failure: true for display (show), true for readOnly (lock)
   */
  private boolean evaluateExpression(String expression, Tab tab, Field field,
      Map<String, Object> evalContext) {
    try {
      // Step 1: Replace system preferences and macros (static, pre-parser)
      String preprocessed = DynamicExpressionParser
          .replaceSystemPreferencesInDisplayLogic(expression);

      // Step 2: Parse expression -- resolves session vars, auxiliary inputs,
      // field references, accounting dimensions
      DynamicExpressionParser parser =
          new DynamicExpressionParser(preprocessed, tab, field);
      String jsExpr = parser.getJSExpression();

      // Step 3: Prepend OB.Utilities shim so Rhino can evaluate
      // SmartClient-dependent code (OB.Utilities.getValue, OB.Utilities.Date.JSToOB)
      String fullScript = OB_UTILITIES_SHIM + "\n" + jsExpr;

      // Step 4: Evaluate using Rhino (sandboxed)
      Object result = OBScriptEngine.getInstance().eval(fullScript, evalContext);
      return Boolean.TRUE.equals(result);

    } catch (Exception e) {
      log.warn("Failed to evaluate expression: {} for field: {}",
          expression, field != null ? field.getName() : "tab-level", e);
      // Safe defaults: true for both — show the field (visible) and lock it (read-only)
      return true;
    }
  }

  /**
   * Parses the "fieldValues" object from the request body for evaluate-display requests.
   * Returns an empty JSONObject if the body is absent or has no "fieldValues" key.
   * Returns null if the body is present but unparseable (signals a 400 error to the caller).
   */
  private JSONObject parseFieldValuesFromRequest(HttpServletRequest request) {
    try {
      String body = new String(
          request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (body == null || body.trim().isEmpty()) {
        return new JSONObject();
      }
      JSONObject bodyJson = new JSONObject(body);
      return bodyJson.has("fieldValues")
          ? bodyJson.getJSONObject("fieldValues")
          : new JSONObject();
    } catch (Exception e) {
      log.debug("Failed to parse evaluate-display request body: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Builds the evaluation context from request field values and session data.
   * Field values are stored both as top-level entries (for context.xxx references)
   * and under "currentValues" key (for OB.Utilities.getValue(currentValues, 'xxx')).
   */
  private Map<String, Object> buildEvalContext(JSONObject fieldValues) {
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

    // Also add field values at top level for auxiliary inputs and session vars
    // that resolve to context.xxx (not OB.Utilities.getValue)
    ctx.putAll(currentValues);

    // Add session context
    OBContext obCtx = OBContext.getOBContext();
    ctx.put("AD_Org_ID", obCtx.getCurrentOrganization().getId());
    ctx.put("AD_Client_ID", obCtx.getCurrentClient().getId());
    ctx.put("AD_Role_ID", obCtx.getRole().getId());
    ctx.put("AD_User_ID", obCtx.getUser().getId());

    // Resolve $Element_* accounting dimension preferences.
    // DynamicExpressionParser generates JS referencing context.$Element_XX
    // for displayLogic expressions like @$Element_MC@='Y'.
    resolveAccountingDimensions(ctx, obCtx);

    // Add a "context" object alias so DynamicExpressionParser's JS output
    // (context.xxx references) can resolve against our eval context
    ctx.put("context", ctx);

    return ctx;
  }

  /**
   * Resolves $Element_* accounting dimension preferences and adds them to the eval context.
   */
  private void resolveAccountingDimensions(Map<String, Object> ctx, OBContext obCtx) {
    try {
      DalConnectionProvider conn = new DalConnectionProvider(false);
      VariablesSecureApp vars = new VariablesSecureApp(
          obCtx.getUser().getId(),
          obCtx.getCurrentClient().getId(),
          obCtx.getCurrentOrganization().getId(),
          obCtx.getRole().getId(),
          obCtx.getLanguage().getLanguage());
      String[] elements = { "MC", "AY", "OT", "AS", "CC", "U1", "U2", "PJ", "BU", "PR" };
      for (String el : elements) {
        String key = "$Element_" + el;
        String value = Utility.getContext(conn, vars, key, "");
        ctx.put(key, value);
      }
    } catch (Exception e) {
      log.debug("Could not resolve $Element_* preferences: {}", e.getMessage());
    }
  }

  /**
   * Maps a Field to its DAL property name, matching NeoFieldFilter conventions.
   * Uses ModelProvider to resolve Column -> Entity -> Property -> name.
   *
   * Examples:
   *   C_BPARTNER_ID  -> businessPartner
   *   DOCSTATUS      -> documentStatus
   *   GRANDTOTAL     -> grandTotalAmount
   */
  private String getPropertyName(Field field) {
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

  private SFEntity findEntity(String specId, String entityName) {
    org.openbravo.dal.service.OBCriteria<SFEntity> criteria =
        org.openbravo.dal.service.OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(org.hibernate.criterion.Restrictions.eq(
        SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(org.hibernate.criterion.Restrictions.ilike(
        SFEntity.PROPERTY_NAME, entityName, org.hibernate.criterion.MatchMode.EXACT));
    criteria.add(org.hibernate.criterion.Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(org.hibernate.criterion.Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    java.util.List<SFEntity> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }
}
