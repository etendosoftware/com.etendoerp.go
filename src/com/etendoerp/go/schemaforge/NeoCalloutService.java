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

package com.etendoerp.go.schemaforge;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.application.window.servlet.CalloutServletConfig;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.base.secureApp.LoginUtils;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.erpCommon.ad_callouts.SimpleCallout;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.ModelImplementation;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.service.json.JsonConstants;

/**
 * Service for executing AD Callouts via the NEO Headless API.
 *
 * Receives a REST request with a changed field name and form state,
 * resolves the callout class from AD metadata, builds a synthetic
 * HttpServletRequest, executes the callout, and transforms the
 * result into a clean REST response.
 *
 * Endpoint: POST /sws/neo/{specName}/{entityName}/callout
 */
public class NeoCalloutService {

  private static final Logger log = LogManager.getLogger(NeoCalloutService.class);
  private static final String VALUE_KEY = "value";
  private static final String IDENTIFIER_KEY = "_identifier";
  private static final String FIELD_ENTRIES = "entries";

  private NeoCalloutService() {
  }

  /**
   * Execute a callout for a field change event.
   *
   * @param ctx         the NeoContext with spec/entity/tab info
   * @param requestBody JSON with "field", "value", and "formState"
   * @return NeoResponse with updates, combos, and messages
   */
  public static NeoResponse executeCallout(NeoContext ctx, JSONObject requestBody) {
    try {
      OBContext.setAdminMode();
      try {
        String fieldName = requestBody.getString("field");
        Object value = requestBody.opt(VALUE_KEY);
        JSONObject formState = requestBody.optJSONObject("formState");
        if (formState == null) {
          formState = new JSONObject();
        }
        JSONObject auxValues = requestBody.optJSONObject("auxiliaryValues");

        Tab adTab = ctx.getAdTab();

        // Resolve the column and callout class for the changed field
        CalloutInfo calloutInfo = resolveCallout(adTab, fieldName);
        if (calloutInfo == null) {
          // No callout for this field — return empty response (not an error)
          log.info("[NEO-CALLOUT] No callout found for field '{}' on tab '{}'",
              fieldName, adTab.getName());
          JSONObject emptyResponse = new JSONObject();
          emptyResponse.put("updates", new JSONObject());
          emptyResponse.put("combos", new JSONObject());
          emptyResponse.put("messages", new JSONArray());
          return NeoResponse.ok(emptyResponse);
        }
        log.info("[NEO-CALLOUT] Found callout '{}' for field '{}' (inp: {}, column: {})",
            calloutInfo.className, fieldName, calloutInfo.inpFieldName, calloutInfo.columnName);

        // Build synthetic request parameters
        Map<String, String[]> params = buildRequestParams(
            adTab, fieldName, value, formState, calloutInfo.inpFieldName,
            auxValues);

        // Build session attributes from a fully populated VariablesSecureApp
        // (includes #GROUPSEPARATOR|qtyEdition, #DECIMALSEPARATOR|qtyEdition, etc.)
        Map<String, Object> sessionAttrs = buildSessionAttributes(ctx.getObContext(), adTab);

        // Create synthetic request and set up RequestContext
        SyntheticHttpServletRequest syntheticRequest =
            new SyntheticHttpServletRequest(params, sessionAttrs);

        RequestContext requestContext = RequestContext.get();
        requestContext.setRequest(syntheticRequest);

        // Instantiate and execute the callout
        Class<?> calloutClass = Class.forName(calloutInfo.className);
        Object calloutObject = calloutClass.getDeclaredConstructor().newInstance();

        if (!(calloutObject instanceof SimpleCallout)) {
          return NeoResponse.error(400,
              "Callout class is not a SimpleCallout: " + calloutInfo.className);
        }

        SimpleCallout calloutInstance = (SimpleCallout) calloutObject;

        // Initialize with a servlet config (required by DelegateConnectionProvider)
        try {
          CalloutServletConfig config = new CalloutServletConfig(
              calloutInfo.className, RequestContext.getServletContext());
          calloutInstance.init(config);
        } catch (Exception e) {
          log.debug("Could not initialize callout servlet config, proceeding without it: {}",
              e.getMessage());
        }

        JSONObject calloutResult = calloutInstance.executeSimpleCallout(requestContext);
        log.info("[NEO-CALLOUT] Raw callout result: {}", calloutResult != null ? calloutResult.toString().substring(0, Math.min(500, calloutResult.toString().length())) : "null");

        // Transform the callout result to REST format
        JSONObject restResponse = transformResponse(calloutResult, adTab);
        log.info("[NEO-CALLOUT] Transformed response: {}", restResponse.toString().substring(0, Math.min(500, restResponse.toString().length())));
        return NeoResponse.ok(restResponse);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (ClassNotFoundException e) {
      log.error("Callout class not found: {}", e.getMessage());
      return NeoResponse.error(500, "Callout class not found: " + e.getMessage());
    } catch (Exception e) {
      log.error("Error executing callout: {}", e.getMessage(), e);
      return NeoResponse.error(500, "Callout execution failed: " + e.getMessage());
    }
  }

  // ── Callout resolution ─────────────────────────────────────────────

  /**
   * Information about a resolved callout: class name and inp field name.
   */
  static class CalloutInfo {
    final String className;
    final String inpFieldName;
    final String columnName;

    CalloutInfo(String className, String inpFieldName, String columnName) {
      this.className = className;
      this.inpFieldName = inpFieldName;
      this.columnName = columnName;
    }
  }

  /**
   * Resolve the callout class for a given field name on a tab.
   * Looks up the AD_Column by matching the field name (clean REST name
   * or DB column name), then resolves the callout Java class.
   *
   * @param adTab     the AD_Tab
   * @param fieldName the clean field name from the REST request
   * @return CalloutInfo or null if no callout is configured
   */
  static CalloutInfo resolveCallout(Tab adTab, String fieldName) {
    if (adTab == null || adTab.getTable() == null) {
      return null;
    }

    String tableId = adTab.getTable().getId();

    // Find columns for this table
    OBCriteria<Column> colCriteria = OBDal.getInstance().createCriteria(Column.class);
    colCriteria.add(Restrictions.eq(Column.PROPERTY_TABLE + ".id", tableId));
    colCriteria.add(Restrictions.eq(Column.PROPERTY_ACTIVE, true));
    List<Column> columns = colCriteria.list();

    // Try to match the field name against column names.
    // First try OBDal property name (e.g., "businessPartner" for C_BPartner_ID) — this
    // is what the frontend sends, matching the names used in GET responses.
    Column matchedColumn = null;
    try {
      Entity dalEntity = ModelProvider.getInstance().getEntityByTableId(tableId);
      if (dalEntity != null) {
        for (Column col : columns) {
          Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
          if (prop != null && prop.getName().equals(fieldName)) {
            matchedColumn = col;
            break;
          }
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve property name for field '{}': {}", fieldName, e.getMessage());
    }

    // Fallback: try DB column name, clean REST name, and inp name
    if (matchedColumn == null) {
      for (Column col : columns) {
        String dbColName = col.getDBColumnName();
        if (dbColName.equalsIgnoreCase(fieldName)) {
          matchedColumn = col;
          break;
        }
        String cleanName = toCleanFieldName(dbColName);
        if (cleanName.equalsIgnoreCase(fieldName)) {
          matchedColumn = col;
          break;
        }
        String inpName = toInpName(dbColName);
        if (inpName.equalsIgnoreCase(fieldName)) {
          matchedColumn = col;
          break;
        }
      }
    }

    if (matchedColumn == null) {
      log.debug("No column found matching field name '{}' in table {}",
          fieldName, tableId);
      return null;
    }

    if (matchedColumn.getCallout() == null) {
      log.debug("Column '{}' has no callout configured", matchedColumn.getDBColumnName());
      return null;
    }

    // Resolve callout class name from AD_ModelImplementation
    List<ModelImplementation> implementations =
        matchedColumn.getCallout().getADModelImplementationList();
    if (implementations == null || implementations.isEmpty()) {
      log.warn("Callout '{}' for column '{}' has no model implementation",
          matchedColumn.getCallout().getName(), matchedColumn.getDBColumnName());
      return null;
    }

    String className = implementations.get(0).getJavaClassName();
    if (StringUtils.isBlank(className)) {
      log.warn("Callout implementation for column '{}' has no Java class name",
          matchedColumn.getDBColumnName());
      return null;
    }

    String inpName = toInpName(matchedColumn.getDBColumnName());
    return new CalloutInfo(className, inpName, matchedColumn.getDBColumnName());
  }

  // ── Field name mapping ─────────────────────────────────────────────

  /**
   * Convert a DB column name to the inp* format used by Etendo callouts.
   * Replicates the logic of Sqlc.TransformaNombreColumna with "inp" prefix.
   *
   * Examples:
   *   C_BPartner_ID  -> inpcBpartnerId
   *   DocumentNo     -> inpdocumentno
   *   IsActive       -> inpisactive
   *   M_Warehouse_ID -> inpmWarehouseId
   */
  public static String toInpName(String columnName) {
    return "inp" + transformColumnName(columnName);
  }

  /**
   * Replicate Sqlc.TransformaNombreColumna: remove underscores,
   * lowercase first char, uppercase char after underscore, lowercase rest.
   */
  static String transformColumnName(String columnName) {
    if (columnName == null || columnName.isEmpty()) {
      return "";
    }
    int len = columnName.length();
    StringBuilder result = new StringBuilder(len);
    boolean underscore = false;
    for (int i = 0; i < len; i++) {
      char c = columnName.charAt(i);
      if (i == 0) {
        result.append(Character.toLowerCase(c));
      } else {
        if (c == '_') {
          underscore = true;
        } else {
          if (underscore) {
            result.append(Character.toUpperCase(c));
            underscore = false;
          } else {
            result.append(Character.toLowerCase(c));
          }
        }
      }
    }
    return result.toString();
  }

  /**
   * Convert a DB column name to a clean REST field name.
   * Drops the _ID suffix and converts to camelCase.
   *
   * Examples:
   *   C_BPartner_ID  -> businessPartner (simplified)
   *   DocumentNo     -> documentNo
   *   M_Warehouse_ID -> warehouse
   *
   * This is a simplified version; the SFField.name from Schema Forge
   * would be more accurate in practice.
   */
  static String toCleanFieldName(String columnName) {
    if (columnName == null) {
      return "";
    }
    String name = columnName;
    // Remove the _ID suffix
    if (name.endsWith("_ID")) {
      name = name.substring(0, name.length() - 3);
    }
    // Remove table prefix (e.g., C_ or M_ or AD_)
    if (name.length() > 2 && name.charAt(1) == '_') {
      name = name.substring(2);
    } else if (name.length() > 3 && name.charAt(2) == '_') {
      name = name.substring(3);
    }
    // Convert to camelCase
    return transformColumnName(name);
  }

  /**
   * Try to reverse-map a clean REST field name back to a DB column name.
   * This is a best-effort heuristic; the SFField mapping would be more reliable.
   */
  static String fromCleanFieldName(String cleanName, Tab adTab) {
    if (adTab == null || adTab.getTable() == null) {
      return cleanName;
    }
    // Try to find the column whose clean name matches
    OBCriteria<Column> colCriteria = OBDal.getInstance().createCriteria(Column.class);
    colCriteria.add(Restrictions.eq(Column.PROPERTY_TABLE + ".id", adTab.getTable().getId()));
    colCriteria.add(Restrictions.eq(Column.PROPERTY_ACTIVE, true));
    List<Column> columns = colCriteria.list();

    for (Column col : columns) {
      if (toCleanFieldName(col.getDBColumnName()).equalsIgnoreCase(cleanName)) {
        return col.getDBColumnName();
      }
    }
    return cleanName;
  }

  // ── Request building ───────────────────────────────────────────────

  /**
   * Build the request parameters map from the form state.
   * Maps form state keys to inp* format and adds callout metadata params.
   * Loads columns once and builds a lookup map for efficient resolution.
   */
  /**
   * Holder for the three column-lookup maps and the full column list for a tab.
   * Built once and shared across form-state mapping, default-filling, and aux-value resolution.
   */
  private static class ColumnLookupMaps {
    final Map<String, String> propertyNameToInp = new HashMap<>();
    final Map<String, String> cleanNameToInp = new HashMap<>();
    final Map<String, String> dbNameToInp = new HashMap<>();
    List<Column> columns = Collections.emptyList();
  }

  /**
   * Build the column lookup maps for the given tab's table.
   * Returns an empty holder (all maps empty, columns empty list) if the tab has no table.
   */
  private static ColumnLookupMaps buildColumnLookupMaps(Tab adTab) {
    ColumnLookupMaps maps = new ColumnLookupMaps();
    if (adTab.getTable() == null) {
      return maps;
    }
    String tableId = adTab.getTable().getId();
    Entity dalEntity = null;
    try {
      dalEntity = ModelProvider.getInstance().getEntityByTableId(tableId);
    } catch (Exception e) {
      log.debug("Could not resolve DAL entity for table: {}", e.getMessage());
    }
    OBCriteria<Column> colCriteria = OBDal.getInstance().createCriteria(Column.class);
    colCriteria.add(Restrictions.eq(Column.PROPERTY_TABLE + ".id", tableId));
    colCriteria.add(Restrictions.eq(Column.PROPERTY_ACTIVE, true));
    maps.columns = colCriteria.list();
    for (Column col : maps.columns) {
      String dbColName = col.getDBColumnName();
      String inpName = toInpName(dbColName);
      maps.dbNameToInp.put(dbColName.toLowerCase(), inpName);
      maps.cleanNameToInp.put(toCleanFieldName(dbColName).toLowerCase(), inpName);
      if (dalEntity != null) {
        try {
          Property prop = dalEntity.getPropertyByColumnName(dbColName);
          if (prop != null) {
            maps.propertyNameToInp.put(prop.getName().toLowerCase(), inpName);
          }
        } catch (Exception ignored) {
          // Not all columns have DAL properties
        }
      }
    }
    return maps;
  }

  /**
   * Map form state fields to their inp* parameter names and add them to params.
   * Skips $_identifier companion keys and never overwrites the trigger field.
   */
  @SuppressWarnings("unchecked")
  private static void mapFormStateToParams(JSONObject formState, String inpFieldName,
      ColumnLookupMaps maps, Map<String, String[]> params) {
    if (formState == null) {
      return;
    }
    Iterator<String> keys = formState.keys();
    while (keys.hasNext()) {
      mapFormStateEntry(keys.next(), formState, inpFieldName, maps, params);
    }
  }

  /**
   * Maps a single form-state key to its inp* parameter name and adds it to params,
   * skipping identifier companion keys and the trigger field.
   */
  private static void mapFormStateEntry(String key, JSONObject formState, String inpFieldName,
      ColumnLookupMaps maps, Map<String, String[]> params) {
    // Skip $_identifier companion keys
    if (key.contains("$_identifier")) {
      return;
    }
    String val = formState.optString(key, "");
    // Try OBDal property name first, then fall back to clean/db names
    String inpKey = maps.propertyNameToInp.get(key.toLowerCase());
    if (inpKey == null) {
      inpKey = resolveToInpName(key, maps.dbNameToInp, maps.cleanNameToInp);
    }
    // Never overwrite the trigger field — its value comes from the `value` parameter
    if (inpKey.equals(inpFieldName)) {
      return;
    }
    params.put(inpKey, new String[]{ val });
  }

  /**
   * Fill any column not yet in params with its AD default value.
   * Classic Etendo UI sends every field in callout requests; NEO's formState may be sparse.
   */
  private static void fillMissingColumnDefaults(Tab adTab, OBContext obCtx,
      List<Column> columns, Map<String, String[]> params) {
    if (adTab.getTable() == null) {
      return;
    }
    try {
      VariablesSecureApp vars = buildCalloutVars(obCtx, adTab);
      DalConnectionProvider conn = new DalConnectionProvider(false);
      String windowId = adTab.getWindow() != null ? adTab.getWindow().getId() : "";
      for (Column col : columns) {
        String inpName = toInpName(col.getDBColumnName());
        if (params.containsKey(inpName)) {
          continue;
        }
        String resolved = resolveColumnDefault(conn, vars, col, windowId);
        params.put(inpName, new String[]{ resolved });
      }
    } catch (Exception e) {
      log.warn("Could not fill callout defaults: {}", e.getMessage());
    }
  }

  /**
   * Resolve a single column's default value using Utility.getDefault.
   * Returns empty string if resolution fails.
   */
  private static String resolveColumnDefault(DalConnectionProvider conn,
      VariablesSecureApp vars, Column col, String windowId) {
    try {
      String defaultExpr = col.getDefaultValue();
      String resolved = Utility.getDefault(conn, vars, col.getDBColumnName(),
          defaultExpr != null ? defaultExpr.trim() : "", windowId, "");
      return resolved != null ? resolved : "";
    } catch (Exception e) {
      log.debug("Could not resolve default for column {}: {}",
          col.getDBColumnName(), e.getMessage());
      return "";
    }
  }

  /**
   * For child tabs, inject the parent record ID and all parent fields into params.
   * Classic Etendo UI sends ALL header fields when executing child-tab callouts.
   */
  private static void injectParentTabParams(Tab adTab, JSONObject formState,
      Map<String, String[]> params) {
    Long tabLevel = adTab.getTabLevel();
    log.info("[NEO-CALLOUT] Tab '{}' level={}, formState has id={}", adTab.getName(), tabLevel,
        formState != null && formState.has("id"));
    if (tabLevel == null || tabLevel <= 0 || formState == null) {
      return;
    }
    Tab parentTab = findParentTab(adTab);
    log.info("[NEO-CALLOUT] Parent tab: {}", parentTab != null ? parentTab.getName() : "null");
    if (parentTab == null || parentTab.getTable() == null) {
      return;
    }
    String parentKeyCol = parentTab.getTable().getDBTableName() + "_ID";
    String inpParentKey = toInpName(parentKeyCol);
    log.info("[NEO-CALLOUT] Parent key: {} -> {}, already in params: {}",
        parentKeyCol, inpParentKey, params.containsKey(inpParentKey));
    injectParentId(formState, inpParentKey, params);
    String parentId = params.containsKey(inpParentKey) ? params.get(inpParentKey)[0] : null;
    if (parentId != null && !parentId.isEmpty()) {
      injectParentRecordFields(parentTab, parentId, params);
    }
  }

  /**
   * Inject the parent record ID into params if not already set to a non-empty value.
   */
  private static void injectParentId(JSONObject formState, String inpParentKey,
      Map<String, String[]> params) {
    String existingVal = params.containsKey(inpParentKey) ? params.get(inpParentKey)[0] : "";
    if (formState.has("id") && (existingVal == null || existingVal.isEmpty())) {
      String parentId = formState.optString("id", "");
      params.put(inpParentKey, new String[]{ parentId });
      log.info("[NEO-CALLOUT] Injected parent ID: {} = {}", inpParentKey, parentId);
    } else if (params.containsKey(inpParentKey)) {
      log.info("[NEO-CALLOUT] Parent key already set: {} = {}", inpParentKey,
          params.get(inpParentKey)[0]);
    }
  }

  /**
   * Map auxiliary selector values (e.g., businessPartner_LOC -> inpcBpartnerId_LOC) into params.
   * These are extra values from OBUISEL selectors that callouts may depend on.
   */
  @SuppressWarnings("unchecked")
  private static void mapAuxValuesToParams(JSONObject auxValues, ColumnLookupMaps maps,
      Map<String, String[]> params) {
    if (auxValues == null) {
      return;
    }
    Iterator<String> auxKeys = auxValues.keys();
    while (auxKeys.hasNext()) {
      String key = auxKeys.next();   // e.g., "businessPartner_LOC"
      String auxVal = auxValues.optString(key, "");
      // Split into base field name + suffix
      int suffixStart = key.lastIndexOf('_');
      if (suffixStart > 0) {
        String baseName = key.substring(0, suffixStart);  // "businessPartner"
        String suffix = key.substring(suffixStart);        // "_LOC"
        // Resolve base field to inp format
        String inpBase = maps.propertyNameToInp.get(baseName.toLowerCase());
        if (inpBase == null) {
          inpBase = resolveToInpName(baseName, maps.dbNameToInp, maps.cleanNameToInp);
        }
        params.put(inpBase + suffix, new String[]{ auxVal });
      }
    }
  }

  private static Map<String, String[]> buildRequestParams(Tab adTab,
      String fieldName, Object value, JSONObject formState, String inpFieldName,
      JSONObject auxValues) {

    Map<String, String[]> params = new HashMap<>();

    // Set the trigger field and its value
    params.put("inpLastFieldChanged", new String[]{ inpFieldName });
    params.put(inpFieldName, new String[]{ value != null ? value.toString() : "" });

    // Set tab and window IDs
    params.put("inpTabId", new String[]{ adTab.getId() });
    if (adTab.getWindow() != null) {
      params.put("inpwindowId", new String[]{ adTab.getWindow().getId() });
    }

    // Inject essential context params from OBContext (security boundary — always authoritative)
    OBContext obCtx = OBContext.getOBContext();
    params.put("inpadOrgId", new String[]{ obCtx.getCurrentOrganization().getId() });
    params.put("inpadClientId", new String[]{ obCtx.getCurrentClient().getId() });
    String isSOTrx = adTab.getWindow() != null
        && Boolean.TRUE.equals(adTab.getWindow().isSalesTransaction()) ? "Y" : "N";
    params.put("isSOTrx", new String[]{ isSOTrx });
    if (obCtx.getWarehouse() != null) {
      params.put("inpmWarehouseId", new String[]{ obCtx.getWarehouse().getId() });
    }

    // Build column lookup maps once (for form-state mapping, default-filling, aux-value resolution)
    ColumnLookupMaps maps = buildColumnLookupMaps(adTab);

    // Map form state fields to inp* parameters (skips trigger field and $_identifier keys)
    mapFormStateToParams(formState, inpFieldName, maps, params);

    // Fill missing columns with their AD defaults so callouts see all fields
    fillMissingColumnDefaults(adTab, obCtx, maps.columns, params);

    // For child tabs, inject the parent record ID and fields
    injectParentTabParams(adTab, formState, params);

    // Process auxiliary values (e.g., businessPartner_LOC -> inpcBpartnerId_LOC)
    mapAuxValuesToParams(auxValues, maps, params);

    return params;
  }

  /**
   * Build a VariablesSecureApp populated with the full session context.
   * Uses LoginUtils.fillSessionArguments (same as classic login) so that
   * Utility.getDefault can resolve context variables like @#AD_Org_ID@, @IsSOTrx@, etc.
   */
  private static VariablesSecureApp buildCalloutVars(OBContext obCtx, Tab adTab) {
    VariablesSecureApp vars = buildVars(obCtx);

    // Set window-level isSOTrx so expressions like @IsSOTrx@ resolve correctly
    if (adTab != null && adTab.getWindow() != null) {
      String soTrx = Boolean.TRUE.equals(adTab.getWindow().isSalesTransaction()) ? "Y" : "N";
      vars.setSessionValue("isSOTrx", soTrx);
    }

    return vars;
  }

  /**
   * Build a VariablesSecureApp from OBContext with full session population.
   * Shared by NeoCalloutService and NeoDefaultsService.
   */
  static VariablesSecureApp buildVars(OBContext obCtx) {
    String userId = obCtx.getUser().getId();
    String clientId = obCtx.getCurrentClient().getId();
    String orgId = obCtx.getCurrentOrganization().getId();
    String roleId = obCtx.getRole().getId();
    String lang = obCtx.getLanguage().getLanguage();
    String warehouseId = obCtx.getWarehouse() != null
        ? obCtx.getWarehouse().getId() : "";

    VariablesSecureApp vars = new VariablesSecureApp(userId, clientId, orgId, roleId, lang);
    DalConnectionProvider conn = new DalConnectionProvider(false);

    try {
      LoginUtils.fillSessionArguments(conn, vars, userId, lang, "N",
          roleId, clientId, orgId, warehouseId);
    } catch (Exception e) {
      log.debug("LoginUtils.fillSessionArguments failed: {}", e.getMessage());
      vars.setSessionValue("#AD_User_ID", userId);
      vars.setSessionValue("#AD_Client_ID", clientId);
      vars.setSessionValue("#AD_Org_ID", orgId);
      vars.setSessionValue("#AD_Role_ID", roleId);
      vars.setSessionValue("#AD_Language", lang);
      vars.setSessionValue("#M_Warehouse_ID", warehouseId);
      vars.setSessionValue("#User_Client", "'" + clientId + "','0'");
    }

    return vars;
  }

  /**
   * Resolve a form state key to its inp* name using pre-built lookup maps.
   */
  private static String resolveToInpName(String key,
      Map<String, String> dbNameToInp, Map<String, String> cleanNameToInp) {
    // Already in inp format
    if (key.startsWith("inp")) {
      return key;
    }

    // Match by DB column name (case-insensitive)
    String resolved = dbNameToInp.get(key.toLowerCase());
    if (resolved != null) {
      return resolved;
    }

    // Match by clean REST name (case-insensitive)
    resolved = cleanNameToInp.get(key.toLowerCase());
    if (resolved != null) {
      return resolved;
    }

    // Fallback: prefix with inp and lowercase
    return "inp" + Character.toLowerCase(key.charAt(0)) + key.substring(1);
  }

  /**
   * Build session attributes for the synthetic request.
   * Replicates the same two-step process as the classic login:
   *   1. fillSessionArguments → identity, preferences, context variables
   *   2. readNumberFormat    → #GROUPSEPARATOR|qtyEdition, #DECIMALSEPARATOR|qtyEdition, etc.
   * VariablesBase.getSessionValue reads these as UPPERCASE keys from the session.
   */
  private static Map<String, Object> buildSessionAttributes(OBContext obContext, Tab adTab) {
    Map<String, Object> attrs = new HashMap<>();
    if (obContext == null) {
      return attrs;
    }

    // Step 1: Build VariablesSecureApp with fillSessionArguments (same as classic login)
    VariablesSecureApp vars = buildCalloutVars(obContext, adTab);

    // Step 2: Read number formats from Format.xml (same as classic login)
    try {
      org.openbravo.base.ConfigParameters config =
          org.openbravo.base.ConfigParameters.retrieveFrom(RequestContext.getServletContext());
      LoginUtils.readNumberFormat(vars, config.getFormatPath());
    } catch (Exception e) {
      log.debug("[NEO-CALLOUT] Could not read Format.xml: {}", e.getMessage());
    }

    // Copy all session values from vars to the attrs map.
    // VariablesSecureApp without a real HttpSession stores values in an internal
    // sessionAttributes map (field in VariablesBase). Read known keys that callouts need.
    String[] knownKeys = {
        "#AD_User_ID", "#AD_Role_ID", "#AD_Client_ID", "#AD_Org_ID",
        "#M_Warehouse_ID", "#AD_Language", "#AD_Session_ID",
        "#User_Client", "#Date", "#AD_JavaDateFormat", "#AD_JavaDateTimeFormat",
    };
    for (String key : knownKeys) {
      String val = vars.getSessionValue(key);
      if (val != null && !val.isEmpty()) {
        attrs.put(key.toUpperCase(), val);
      }
    }

    // Copy number format values for all standard format names
    String[] formatNames = {"qtyEdition", "euroEdition", "priceEdition", "integerEdition",
        "generalQtyEdition", "euroInform"};
    for (String fmt : formatNames) {
      String gs = vars.getSessionValue("#GroupSeparator|" + fmt);
      String ds = vars.getSessionValue("#DecimalSeparator|" + fmt);
      String fo = vars.getSessionValue("#FormatOutput|" + fmt);
      if (!gs.isEmpty()) attrs.put("#GROUPSEPARATOR|" + fmt.toUpperCase(), gs);
      if (!ds.isEmpty()) attrs.put("#DECIMALSEPARATOR|" + fmt.toUpperCase(), ds);
      if (!fo.isEmpty()) attrs.put("#FORMATOUTPUT|" + fmt.toUpperCase(), fo);
    }

    return attrs;
  }

  /**
   * Find the parent tab of a child tab by walking the window's tab list in sequence order.
   * Returns the nearest tab with a lower tab level that precedes the child tab.
   */
  private static Tab findParentTab(Tab childTab) {
    if (childTab.getWindow() == null) {
      return null;
    }
    int childLevel = childTab.getTabLevel() != null ? childTab.getTabLevel().intValue() : 0;
    List<Tab> tabs = childTab.getWindow().getADTabList();
    tabs.sort(Comparator.comparingLong(Tab::getSequenceNumber));
    Tab parent = null;
    for (Tab t : tabs) {
      if (t.getId().equals(childTab.getId())) {
        break;
      }
      int level = t.getTabLevel() != null ? t.getTabLevel().intValue() : 0;
      if (level < childLevel) {
        parent = t;
      }
    }
    return parent;
  }

  /**
   * Load the parent record from DB and inject its column values as inp* params.
   * Classic Etendo UI always sends header fields when executing child-tab callouts.
   * Without these, callouts like SL_Order_Product can't resolve prices (needs M_PriceList_ID).
   */
  private static void injectParentRecordFields(Tab parentTab, String parentId,
      Map<String, String[]> params) {
    try {
      Entity parentEntity = ModelProvider.getInstance()
          .getEntityByTableId(parentTab.getTable().getId());
      if (parentEntity == null) {
        return;
      }
      Object parentRecord = OBDal.getInstance().get(parentEntity.getName(), parentId);
      if (parentRecord == null) {
        log.debug("[NEO-CALLOUT] Parent record not found: {} / {}", parentEntity.getName(), parentId);
        return;
      }
      org.openbravo.base.structure.BaseOBObject bob =
          (org.openbravo.base.structure.BaseOBObject) parentRecord;

      int injected = 0;
      for (Column col : parentTab.getTable().getADColumnList()) {
        if (!col.isActive()) {
          continue;
        }
        String dbColName = col.getDBColumnName();
        String inpName = toInpName(dbColName);
        // Don't overwrite params already set by formState or child-tab defaults
        if (!params.containsKey(inpName)
            && injectSingleParentField(params, parentEntity, bob, dbColName, inpName)) {
          injected++;
        }
      }
      log.info("[NEO-CALLOUT] Injected {} parent record fields from {}", injected, parentEntity.getName());
    } catch (Exception e) {
      log.warn("[NEO-CALLOUT] Failed to inject parent record fields: {}", e.getMessage());
    }
  }

  /**
   * Resolve and inject a single parent record field into the params map.
   * Returns true if the field was successfully injected, false otherwise.
   */
  private static boolean injectSingleParentField(Map<String, String[]> params,
      Entity parentEntity, org.openbravo.base.structure.BaseOBObject bob,
      String dbColName, String inpName) {
    try {
      Property prop = parentEntity.getPropertyByColumnName(dbColName);
      if (prop == null) {
        return false;
      }
      Object val = bob.get(prop.getName());
      String strVal = resolveFieldValueAsString(val);
      params.put(inpName, new String[]{ strVal });
      return true;
    } catch (Exception e) {
      log.debug("[NEO-CALLOUT] Could not read parent field {}: {}", dbColName, e.getMessage());
      return false;
    }
  }

  /**
   * Convert a field value to its string representation.
   * For FK references (BaseOBObject), returns the record ID.
   * For null values, returns an empty string.
   */
  private static String resolveFieldValueAsString(Object val) {
    if (val == null) {
      return "";
    }
    if (val instanceof org.openbravo.base.structure.BaseOBObject) {
      return ((org.openbravo.base.structure.BaseOBObject) val).getId().toString();
    }
    return val.toString();
  }

  // ── Response transformation ────────────────────────────────────────

  /**
   * Transform the raw callout result JSON into the clean REST response format.
   *
   * Callout result structure (from SimpleCallout.CalloutInfo):
   * {
   *   "inpcBpartnerId": { "value": "...", "classicValue": "..." },
   *   "inpmWarehouseId": { "value": "...", "classicValue": "...", "entries": [...] },
   *   "MESSAGE": { "value": "text", "classicValue": "text" },
   *   "JSEXECUTE": { "value": "...", "classicValue": "..." }
   * }
   *
   * REST response format:
   * {
   *   "updates": { "fieldName": { "value": "...", "identifier": "..." } },
   *   "combos": { "fieldName": { "selected": "...", "entries": [...] } },
   *   "messages": [ { "type": "WARNING", "text": "..." } ]
   * }
   */
  /**
   * For FK update fields that have a value but no _identifier, look up the display name
   * from the DB so the frontend can show the correct label immediately.
   */
  private static void resolveIdentifiersForFkUpdates(JSONObject updates, Tab adTab) {
    if (updates == null || adTab == null || adTab.getTable() == null) {
      return;
    }
    try {
      Entity dalEntity = ModelProvider.getInstance()
          .getEntityByTableId(adTab.getTable().getId());
      if (dalEntity == null) return;

      Iterator<String> keys = updates.keys();
      while (keys.hasNext()) {
        String fieldName = keys.next();
        resolveIdentifierForField(updates.optJSONObject(fieldName), fieldName, dalEntity);
      }
    } catch (Exception e) {
      log.debug("Error resolving FK identifiers: {}", e.getMessage());
    }
  }

  /**
   * Resolve the _identifier for a single FK update field entry.
   * Looks up the referenced record by ID and sets the display identifier.
   */
  private static void resolveIdentifierForField(JSONObject entry, String fieldName,
      Entity dalEntity) {
    if (entry == null || entry.has(IDENTIFIER_KEY)) {
      return;
    }
    Object val = entry.opt(VALUE_KEY);
    if (val == null || "".equals(val)) {
      return;
    }
    String strVal = String.valueOf(val);
    // Only resolve for IDs (32-char hex or numeric)
    if (!strVal.matches("[0-9A-Fa-f]{32}") && !strVal.matches("\\d+")) {
      return;
    }

    try {
      Property prop = dalEntity.getProperty(fieldName);
      if (prop == null || prop.getTargetEntity() == null) {
        return;
      }
      // Look up the record to get its identifier
      BaseOBObject referenced = OBDal.getInstance().get(
          prop.getTargetEntity().getName(), strVal);
      if (referenced != null) {
        String identifier = referenced.getIdentifier();
        if (identifier != null && !identifier.isEmpty()) {
          entry.put(IDENTIFIER_KEY, identifier);
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve _identifier for {}: {}", fieldName, e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  static JSONObject transformResponse(JSONObject calloutResult, Tab adTab) {
    JSONObject response = new JSONObject();

    try {
      JSONObject updates = new JSONObject();
      JSONObject combos = new JSONObject();
      JSONArray messages = new JSONArray();
      Map<String, String> rDisplayNames = new java.util.HashMap<>();

      if (calloutResult == null) {
        response.put("updates", updates);
        response.put("combos", combos);
        response.put("messages", messages);
        return response;
      }

      classifyCalloutFields(calloutResult, adTab, updates, combos, messages, rDisplayNames);

      mergeRDisplayNames(rDisplayNames, updates);

      // Resolve _identifier for FK fields where the callout only returned an ID.
      // This avoids the frontend showing stale labels until the record is saved/reloaded.
      resolveIdentifiersForFkUpdates(updates, adTab);

      response.put("updates", updates);
      response.put("combos", combos);
      response.put("messages", messages);

    } catch (Exception e) {
      log.error("Error transforming callout response: {}", e.getMessage(), e);
      try {
        response.put("error", "Failed to transform callout response: " + e.getMessage());
      } catch (Exception ignored) {
        // Ignore JSON exception in error handling
      }
    }

    return response;
  }

  /**
   * Iterate over all fields in the raw callout result and classify them into
   * updates, combos, messages, or _R display-name entries.
   */
  @SuppressWarnings("unchecked")
  private static void classifyCalloutFields(JSONObject calloutResult, Tab adTab,
      JSONObject updates, JSONObject combos, JSONArray messages,
      Map<String, String> rDisplayNames) throws Exception {
    Iterator<String> keys = calloutResult.keys();
    while (keys.hasNext()) {
      classifyCalloutField(keys.next(), calloutResult, adTab, updates, combos, messages, rDisplayNames);
    }
  }

  /**
   * Classifies a single callout result field into updates, combos, messages, or _R display-name
   * entries. Fields with null value, JSEXECUTE keys, or unrecognized types are skipped silently.
   */
  private static void classifyCalloutField(String key, JSONObject calloutResult, Tab adTab,
      JSONObject updates, JSONObject combos, JSONArray messages,
      Map<String, String> rDisplayNames) throws Exception {
    JSONObject fieldResult = calloutResult.optJSONObject(key);
    if (fieldResult == null) {
      return;
    }
    if (isMessageKey(key)) {
      messages.put(buildMessageEntry(key, fieldResult));
      return;
    }
    if ("JSEXECUTE".equals(key)) {
      // Not applicable in REST context
      return;
    }
    if (key.endsWith("_R")) {
      collectRDisplayName(key, fieldResult, adTab, rDisplayNames);
      return;
    }
    classifyFieldUpdate(key, fieldResult, adTab, updates, combos);
  }

  /**
   * Return true if the key represents a message-level response (WARNING, ERROR, etc.).
   */
  private static boolean isMessageKey(String key) {
    return "MESSAGE".equals(key) || "WARNING".equals(key)
        || "ERROR".equals(key) || "INFO".equals(key) || "SUCCESS".equals(key);
  }

  /**
   * Build a message JSON entry from a message-level field result.
   */
  private static JSONObject buildMessageEntry(String type, JSONObject fieldResult) throws Exception {
    JSONObject msg = new JSONObject();
    msg.put("type", type);
    msg.put("text", fieldResult.optString(VALUE_KEY, ""));
    return msg;
  }

  /**
   * Store a _R display name entry for later merging into the base field's _identifier.
   * E.g., "inpcDoctypetargetId_R" → "Purchase Order" is stored for "transactionDocument".
   */
  private static void collectRDisplayName(String key, JSONObject fieldResult, Tab adTab,
      Map<String, String> rDisplayNames) {
    String baseInpKey = key.substring(0, key.length() - 2);
    String baseClean = inpToCleanName(baseInpKey, adTab);
    if (!rDisplayNames.containsKey(baseClean)) {
      rDisplayNames.put(baseClean, fieldResult.optString(VALUE_KEY, ""));
    }
  }

  /**
   * Classify a field result as either a combo update (has entries) or a simple field update,
   * and add it to the appropriate output map.
   */
  private static void classifyFieldUpdate(String key, JSONObject fieldResult, Tab adTab,
      JSONObject updates, JSONObject combos) throws Exception {
    String cleanName = inpToCleanName(key, adTab);
    if (fieldResult.has(FIELD_ENTRIES)) {
      combos.put(cleanName, buildComboEntry(fieldResult));
    } else {
      JSONObject updateObj = new JSONObject();
      updateObj.put(VALUE_KEY, fieldResult.opt(VALUE_KEY));
      updates.put(cleanName, updateObj);
    }
  }

  /**
   * Build a clean combo entry from the raw callout combo field result.
   * Maps entries to {id, identifier} pairs.
   */
  private static JSONObject buildComboEntry(JSONObject fieldResult) throws Exception {
    JSONObject comboObj = new JSONObject();
    if (fieldResult.has(VALUE_KEY)) {
      comboObj.put("selected", fieldResult.opt(VALUE_KEY));
    }
    JSONArray rawEntries = fieldResult.optJSONArray(FIELD_ENTRIES);
    if (rawEntries != null) {
      JSONArray cleanEntries = new JSONArray();
      for (int i = 0; i < rawEntries.length(); i++) {
        JSONObject rawEntry = rawEntries.optJSONObject(i);
        if (rawEntry != null && rawEntry.length() > 0) {
          JSONObject cleanEntry = new JSONObject();
          cleanEntry.put("id", rawEntry.optString(JsonConstants.ID, ""));
          cleanEntry.put("identifier", rawEntry.optString(JsonConstants.IDENTIFIER, ""));
          cleanEntries.put(cleanEntry);
        }
      }
      comboObj.put(FIELD_ENTRIES, cleanEntries);
    }
    return comboObj;
  }

  /**
   * Merge _R display names as _identifier into their base field update entries.
   * E.g., "inpcDoctypetargetId_R" → "Purchase Order" merges into
   * transactionDocument: { value: "...", _identifier: "Purchase Order" }
   */
  private static void mergeRDisplayNames(Map<String, String> rDisplayNames,
      JSONObject updates) throws Exception {
    for (Map.Entry<String, String> rEntry : rDisplayNames.entrySet()) {
      String baseKey = rEntry.getKey();
      String displayName = rEntry.getValue();
      if (updates.has(baseKey) && StringUtils.isNotBlank(displayName)) {
        updates.getJSONObject(baseKey).put(IDENTIFIER_KEY, displayName);
      }
    }
  }

  /**
   * Convert an inp* name back to a clean REST field name.
   * Strips the "inp" prefix and tries to find the matching column.
   */
  private static String inpToCleanName(String inpName, Tab adTab) {
    if (inpName == null || !inpName.startsWith("inp")) {
      return inpName;
    }

    // Try to find the column whose inp name matches, then resolve its OBDal property name
    if (adTab != null && adTab.getTable() != null) {
      try {
        String tableId = adTab.getTable().getId();
        Entity dalEntity = ModelProvider.getInstance().getEntityByTableId(tableId);

        OBCriteria<Column> colCriteria = OBDal.getInstance().createCriteria(Column.class);
        colCriteria.add(Restrictions.eq(Column.PROPERTY_TABLE + ".id", tableId));
        colCriteria.add(Restrictions.eq(Column.PROPERTY_ACTIVE, true));
        List<Column> columns = colCriteria.list();

        for (Column col : columns) {
          if (toInpName(col.getDBColumnName()).equals(inpName)) {
            // Use OBDal property name (matches GET response keys and frontend field keys)
            if (dalEntity != null) {
              Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
              if (prop != null) {
                return prop.getName();
              }
            }
            return toCleanFieldName(col.getDBColumnName());
          }
        }
      } catch (Exception e) {
        log.debug("Error looking up column for inp name '{}': {}", inpName, e.getMessage());
      }
    }

    // Fallback: just strip "inp" prefix
    String stripped = inpName.substring(3);
    if (!stripped.isEmpty()) {
      return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }
    return stripped;
  }
}
