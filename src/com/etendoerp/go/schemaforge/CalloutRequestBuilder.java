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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Builds the synthetic HTTP request parameter map required to execute an AD Callout
 * via NEO Headless.
 *
 * <p>Extracted from {@link NeoCalloutService} to keep that class within SonarQube's
 * method-count limit. All methods are stateless and package-visible so that
 * NeoCalloutService can delegate to them without changing the public API.</p>
 */
class CalloutRequestBuilder {

  private static final Logger log = LogManager.getLogger(CalloutRequestBuilder.class);

  private CalloutRequestBuilder() {
  }

  // ── Inner holder ───────────────────────────────────────────────────

  /**
   * Holder for the three column-lookup maps and the full column list for a tab.
   * Built once and shared across form-state mapping, default-filling, and aux-value resolution.
   */
  static class ColumnLookupMaps {
    final Map<String, String> propertyNameToInp = new HashMap<>();
    final Map<String, String> cleanNameToInp = new HashMap<>();
    final Map<String, String> dbNameToInp = new HashMap<>();
    List<Column> columns = Collections.emptyList();
  }

  // ── Entry point ────────────────────────────────────────────────────

  /**
   * Build the request parameters map from the form state.
   * Maps form state keys to inp* format and adds callout metadata params.
   * Loads columns once and builds a lookup map for efficient resolution.
   */
  static Map<String, String[]> buildRequestParams(Tab adTab,
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

  // ── Column lookup maps ─────────────────────────────────────────────

  /**
   * Build the column lookup maps for the given tab's table.
   * Returns an empty holder (all maps empty, columns empty list) if the tab has no table.
   */
  static ColumnLookupMaps buildColumnLookupMaps(Tab adTab) {
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
      String inpName = NeoCalloutService.toInpName(dbColName);
      maps.dbNameToInp.put(dbColName.toLowerCase(), inpName);
      maps.cleanNameToInp.put(NeoCalloutService.toCleanFieldName(dbColName).toLowerCase(), inpName);
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

  // ── Form state mapping ─────────────────────────────────────────────

  /**
   * Map form state fields to their inp* parameter names and add them to params.
   * Skips $_identifier companion keys and never overwrites the trigger field.
   */
  @SuppressWarnings("unchecked")
  static void mapFormStateToParams(JSONObject formState, String inpFieldName,
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

  // ── Default filling ────────────────────────────────────────────────

  /**
   * Fill any column not yet in params with its AD default value.
   * Classic Etendo UI sends every field in callout requests; NEO's formState may be sparse.
   */
  static void fillMissingColumnDefaults(Tab adTab, OBContext obCtx,
      List<Column> columns, Map<String, String[]> params) {
    if (adTab.getTable() == null) {
      return;
    }
    try {
      VariablesSecureApp vars = buildCalloutVars(obCtx, adTab);
      DalConnectionProvider conn = new DalConnectionProvider(false);
      String windowId = adTab.getWindow() != null ? adTab.getWindow().getId() : "";
      for (Column col : columns) {
        String inpName = NeoCalloutService.toInpName(col.getDBColumnName());
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

  // ── Parent tab injection ───────────────────────────────────────────

  /**
   * For child tabs, inject the parent record ID and all parent fields into params.
   * Classic Etendo UI sends ALL header fields when executing child-tab callouts.
   */
  static void injectParentTabParams(Tab adTab, JSONObject formState,
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
    String inpParentKey = NeoCalloutService.toInpName(parentKeyCol);
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
      BaseOBObject bob = (BaseOBObject) parentRecord;

      int injected = 0;
      for (Column col : parentTab.getTable().getADColumnList()) {
        if (!col.isActive()) {
          continue;
        }
        String dbColName = col.getDBColumnName();
        String inpName = NeoCalloutService.toInpName(dbColName);
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
      Entity parentEntity, BaseOBObject bob,
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
    if (val instanceof BaseOBObject) {
      return ((BaseOBObject) val).getId().toString();
    }
    return val.toString();
  }

  // ── Auxiliary values ───────────────────────────────────────────────

  /**
   * Map auxiliary selector values (e.g., businessPartner_LOC -> inpcBpartnerId_LOC) into params.
   * These are extra values from OBUISEL selectors that callouts may depend on.
   */
  @SuppressWarnings("unchecked")
  static void mapAuxValuesToParams(JSONObject auxValues, ColumnLookupMaps maps,
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

  // ── Session / vars helpers ─────────────────────────────────────────

  /**
   * Build a VariablesSecureApp populated with the full session context.
   * Uses LoginUtils.fillSessionArguments (same as classic login) so that
   * Utility.getDefault can resolve context variables like @#AD_Org_ID@, @IsSOTrx@, etc.
   */
  static VariablesSecureApp buildCalloutVars(OBContext obCtx, Tab adTab) {
    VariablesSecureApp vars = NeoCalloutService.buildVars(obCtx);

    // Set window-level isSOTrx so expressions like @IsSOTrx@ resolve correctly
    if (adTab != null && adTab.getWindow() != null) {
      String soTrx = Boolean.TRUE.equals(adTab.getWindow().isSalesTransaction()) ? "Y" : "N";
      vars.setSessionValue("isSOTrx", soTrx);
    }

    return vars;
  }

  // ── Name resolution helpers ────────────────────────────────────────

  /**
   * Resolve a form state key to its inp* name using pre-built lookup maps.
   */
  static String resolveToInpName(String key,
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
}
