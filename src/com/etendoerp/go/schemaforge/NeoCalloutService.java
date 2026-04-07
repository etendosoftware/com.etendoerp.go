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
        Object value = requestBody.opt("value");
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

    // Build column lookup maps (once, for all form state keys and default resolution)
    // Include OBDal property names for mapping frontend keys (e.g., "orderDate" -> "inpdateordered")
    Map<String, String> propertyNameToInp = new HashMap<>();
    Map<String, String> cleanNameToInp = new HashMap<>();
    Map<String, String> dbNameToInp = new HashMap<>();
    List<Column> columns = Collections.emptyList();
    if (adTab.getTable() != null) {
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
      columns = colCriteria.list();
      for (Column col : columns) {
        String dbColName = col.getDBColumnName();
        String inpName = toInpName(dbColName);
        dbNameToInp.put(dbColName.toLowerCase(), inpName);
        cleanNameToInp.put(toCleanFieldName(dbColName).toLowerCase(), inpName);
        // Map OBDal property name (what the frontend sends)
        if (dalEntity != null) {
          try {
            Property prop = dalEntity.getPropertyByColumnName(dbColName);
            if (prop != null) {
              propertyNameToInp.put(prop.getName().toLowerCase(), inpName);
            }
          } catch (Exception ignored) {
            // Not all columns have DAL properties
          }
        }
      }
    }

    // Map form state fields to inp* parameters.
    // IMPORTANT: do not overwrite the trigger field (inpFieldName) — the `value` param
    // is authoritative. The formState may contain a stale value (e.g., search query text
    // instead of the selected ID).
    if (formState != null) {
      @SuppressWarnings("unchecked")
      Iterator<String> keys = formState.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        // Skip $_identifier companion keys
        if (key.contains("$_identifier")) continue;
        String val = formState.optString(key, "");
        // Try OBDal property name first, then fall back to clean/db names
        String inpKey = propertyNameToInp.get(key.toLowerCase());
        if (inpKey == null) {
          inpKey = resolveToInpName(key, dbNameToInp, cleanNameToInp);
        }
        // Never overwrite the trigger field — its value comes from the `value` parameter
        if (inpKey.equals(inpFieldName)) continue;
        params.put(inpKey, new String[]{ val });
      }
    }

    // Fill missing columns with their AD defaults so callouts see all fields.
    // In the classic UI every form field is present in the callout request, even on a new
    // record. NEO's formState may be sparse (only defaults + user input), so we fill the
    // gaps using Utility.getDefault — the same resolver the classic UI uses.
    if (adTab.getTable() != null) {
      try {
        VariablesSecureApp vars = buildCalloutVars(obCtx, adTab);
        DalConnectionProvider conn = new DalConnectionProvider(false);
        String windowId = adTab.getWindow() != null ? adTab.getWindow().getId() : "";
        for (Column col : columns) {
          String inpName = toInpName(col.getDBColumnName());
          if (params.containsKey(inpName)) {
            continue;
          }
          String resolved = "";
          try {
            String defaultExpr = col.getDefaultValue();
            resolved = Utility.getDefault(conn, vars, col.getDBColumnName(),
                defaultExpr != null ? defaultExpr.trim() : "", windowId, "");
          } catch (Exception e) {
            log.debug("Could not resolve default for column {}: {}",
                col.getDBColumnName(), e.getMessage());
          }
          params.put(inpName, new String[]{ resolved != null ? resolved : "" });
        }
      } catch (Exception e) {
        log.warn("Could not fill callout defaults: {}", e.getMessage());
      }
    }

    // For child tabs, inject the parent record ID so callouts can access the parent entity.
    // E.g., SL_Order_Product reads inpcOrderId to get the order's price list.
    Long tabLevel = adTab.getTabLevel();
    log.info("[NEO-CALLOUT] Tab '{}' level={}, formState has id={}", adTab.getName(), tabLevel,
        formState != null && formState.has("id"));
    if (tabLevel != null && tabLevel > 0 && formState != null) {
      Tab parentTab = findParentTab(adTab);
      log.info("[NEO-CALLOUT] Parent tab: {}", parentTab != null ? parentTab.getName() : "null");
      if (parentTab != null && parentTab.getTable() != null) {
        String parentKeyCol = parentTab.getTable().getDBTableName() + "_ID";
        String inpParentKey = toInpName(parentKeyCol);
        log.info("[NEO-CALLOUT] Parent key: {} -> {}, already in params: {}", parentKeyCol, inpParentKey, params.containsKey(inpParentKey));
        // The frontend passes the parent header's "id" in formState
        // Override even if already set (defaults may have resolved it to empty)
        String existingVal = params.containsKey(inpParentKey) ? params.get(inpParentKey)[0] : "";
        if (formState.has("id") && (existingVal == null || existingVal.isEmpty())) {
          String parentId = formState.optString("id", "");
          params.put(inpParentKey, new String[]{ parentId });
          log.info("[NEO-CALLOUT] Injected parent ID: {} = {}", inpParentKey, parentId);
        } else if (params.containsKey(inpParentKey)) {
          log.info("[NEO-CALLOUT] Parent key already set: {} = {}", inpParentKey, params.get(inpParentKey)[0]);
        }
      }
    }

    // Process auxiliary values (e.g., businessPartner_LOC -> inpcBpartnerId_LOC)
    // These are extra values from OBUISEL selectors that callouts may depend on.
    if (auxValues != null) {
      @SuppressWarnings("unchecked")
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
          String inpBase = propertyNameToInp.get(baseName.toLowerCase());
          if (inpBase == null) {
            inpBase = resolveToInpName(baseName, dbNameToInp, cleanNameToInp);
          }
          params.put(inpBase + suffix, new String[]{ auxVal });
        }
      }
    }

    return params;
  }

  /**
   * Build a VariablesSecureApp populated with the full session context.
   * Uses LoginUtils.fillSessionArguments (same as classic login) so that
   * Utility.getDefault can resolve context variables like @#AD_Org_ID@, @IsSOTrx@, etc.
   */
  private static VariablesSecureApp buildCalloutVars(OBContext obCtx, Tab adTab) {
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
    }

    // Set window-level isSOTrx so expressions like @IsSOTrx@ resolve correctly
    if (adTab.getWindow() != null) {
      String soTrx = Boolean.TRUE.equals(adTab.getWindow().isSalesTransaction()) ? "Y" : "N";
      vars.setSessionValue("isSOTrx", soTrx);
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
        JSONObject entry = updates.optJSONObject(fieldName);
        if (entry == null || entry.has("_identifier")) continue;
        Object val = entry.opt("value");
        if (val == null || "".equals(val)) continue;
        String strVal = String.valueOf(val);
        // Only resolve for IDs (32-char hex or numeric)
        if (!strVal.matches("[0-9A-Fa-f]{32}") && !strVal.matches("\\d+")) continue;

        // Find the column for this property name
        try {
          Property prop = dalEntity.getProperty(fieldName);
          if (prop == null || prop.getTargetEntity() == null) continue;
          // Look up the record to get its identifier
          BaseOBObject referenced = OBDal.getInstance().get(
              prop.getTargetEntity().getName(), strVal);
          if (referenced != null) {
            String identifier = referenced.getIdentifier();
            if (identifier != null && !identifier.isEmpty()) {
              entry.put("_identifier", identifier);
            }
          }
        } catch (Exception e) {
          log.debug("Could not resolve _identifier for {}: {}", fieldName, e.getMessage());
        }
      }
    } catch (Exception e) {
      log.debug("Error resolving FK identifiers: {}", e.getMessage());
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

      Iterator<String> keys = calloutResult.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        JSONObject fieldResult = calloutResult.optJSONObject(key);
        if (fieldResult == null) {
          continue;
        }

        // Handle special keys
        if ("MESSAGE".equals(key) || "WARNING".equals(key)
            || "ERROR".equals(key) || "INFO".equals(key)
            || "SUCCESS".equals(key)) {
          JSONObject msg = new JSONObject();
          msg.put("type", key);
          msg.put("text", fieldResult.optString("value", ""));
          messages.put(msg);
          continue;
        }

        // Skip JSEXECUTE (not applicable in REST context)
        if ("JSEXECUTE".equals(key)) {
          continue;
        }

        // Handle _R suffix keys (display representation for combo fields).
        // E.g., "inpcDoctypetargetId_R" contains the label for "inpcDoctypetargetId".
        // Store them temporarily and merge after the main loop.
        if (key.endsWith("_R")) {
          String baseInpKey = key.substring(0, key.length() - 2);
          String baseClean = inpToCleanName(baseInpKey, adTab);
          // Store: we'll merge after the main loop
          if (!rDisplayNames.containsKey(baseClean)) {
            rDisplayNames.put(baseClean, fieldResult.optString("value", ""));
          }
          continue;
        }

        // Determine if this is a combo update (has entries) or simple field update
        boolean hasEntries = fieldResult.has("entries");
        String cleanName = inpToCleanName(key, adTab);

        if (hasEntries) {
          // Combo update
          JSONObject comboObj = new JSONObject();
          if (fieldResult.has("value")) {
            comboObj.put("selected", fieldResult.opt("value"));
          }
          JSONArray rawEntries = fieldResult.optJSONArray("entries");
          if (rawEntries != null) {
            JSONArray cleanEntries = new JSONArray();
            for (int i = 0; i < rawEntries.length(); i++) {
              JSONObject rawEntry = rawEntries.optJSONObject(i);
              if (rawEntry != null && rawEntry.length() > 0) {
                JSONObject cleanEntry = new JSONObject();
                cleanEntry.put("id",
                    rawEntry.optString(JsonConstants.ID, ""));
                cleanEntry.put("identifier",
                    rawEntry.optString(JsonConstants.IDENTIFIER, ""));
                cleanEntries.put(cleanEntry);
              }
            }
            comboObj.put("entries", cleanEntries);
          }
          combos.put(cleanName, comboObj);
        } else {
          // Simple field update
          JSONObject updateObj = new JSONObject();
          updateObj.put("value", fieldResult.opt("value"));
          updates.put(cleanName, updateObj);
        }
      }

      // Merge _R display names as _identifier into their base field update entries.
      // E.g., "inpcDoctypetargetId_R" → "Purchase Order" merges into
      // transactionDocument: { value: "...", _identifier: "Purchase Order" }
      for (Map.Entry<String, String> rEntry : rDisplayNames.entrySet()) {
        String baseKey = rEntry.getKey();
        String displayName = rEntry.getValue();
        if (updates.has(baseKey) && StringUtils.isNotBlank(displayName)) {
          updates.getJSONObject(baseKey).put("_identifier", displayName);
        }
      }

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
