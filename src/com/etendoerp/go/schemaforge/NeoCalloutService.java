package com.etendoerp.go.schemaforge;

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
import org.openbravo.client.application.window.servlet.CalloutServletConfig;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_callouts.SimpleCallout;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.ModelImplementation;
import org.openbravo.model.ad.ui.Tab;
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

        Tab adTab = ctx.getAdTab();

        // Resolve the column and callout class for the changed field
        CalloutInfo calloutInfo = resolveCallout(adTab, fieldName);
        if (calloutInfo == null) {
          return NeoResponse.error(404,
              "No callout found for field: " + fieldName
                  + " in entity: " + ctx.getEntityName());
        }

        // Build synthetic request parameters
        Map<String, String[]> params = buildRequestParams(
            adTab, fieldName, value, formState, calloutInfo.inpFieldName);

        // Build session attributes from OBContext
        Map<String, Object> sessionAttrs = buildSessionAttributes(ctx.getObContext());

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

        // Transform the callout result to REST format
        JSONObject restResponse = transformResponse(calloutResult, adTab);
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

    // Try to match the field name against column names
    Column matchedColumn = null;
    for (Column col : columns) {
      String dbColName = col.getDBColumnName();
      // Match by exact DB column name
      if (dbColName.equalsIgnoreCase(fieldName)) {
        matchedColumn = col;
        break;
      }
      // Match by clean REST name (camelCase without _ID)
      String cleanName = toCleanFieldName(dbColName);
      if (cleanName.equalsIgnoreCase(fieldName)) {
        matchedColumn = col;
        break;
      }
      // Match by inp name
      String inpName = toInpName(dbColName);
      if (inpName.equalsIgnoreCase(fieldName)) {
        matchedColumn = col;
        break;
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
      String fieldName, Object value, JSONObject formState, String inpFieldName) {

    Map<String, String[]> params = new HashMap<>();

    // Set the trigger field and its value
    params.put("inpLastFieldChanged", new String[]{ inpFieldName });
    params.put(inpFieldName, new String[]{ value != null ? value.toString() : "" });

    // Set tab and window IDs
    params.put("inpTabId", new String[]{ adTab.getId() });
    if (adTab.getWindow() != null) {
      params.put("inpwindowId", new String[]{ adTab.getWindow().getId() });
    }

    // Build column lookup maps (once, for all form state keys)
    Map<String, String> cleanNameToInp = new HashMap<>();
    Map<String, String> dbNameToInp = new HashMap<>();
    if (adTab.getTable() != null) {
      OBCriteria<Column> colCriteria = OBDal.getInstance().createCriteria(Column.class);
      colCriteria.add(Restrictions.eq(Column.PROPERTY_TABLE + ".id", adTab.getTable().getId()));
      colCriteria.add(Restrictions.eq(Column.PROPERTY_ACTIVE, true));
      List<Column> columns = colCriteria.list();
      for (Column col : columns) {
        String dbColName = col.getDBColumnName();
        String inpName = toInpName(dbColName);
        dbNameToInp.put(dbColName.toLowerCase(), inpName);
        cleanNameToInp.put(toCleanFieldName(dbColName).toLowerCase(), inpName);
      }
    }

    // Map form state fields to inp* parameters
    if (formState != null) {
      @SuppressWarnings("unchecked")
      Iterator<String> keys = formState.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        String val = formState.optString(key, "");
        String inpKey = resolveToInpName(key, dbNameToInp, cleanNameToInp);
        params.put(inpKey, new String[]{ val });
      }
    }

    return params;
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
   * Build session attributes from OBContext for VariablesSecureApp.
   * These are the session values that VariablesSecureApp reads via getSessionValue().
   */
  private static Map<String, Object> buildSessionAttributes(OBContext obContext) {
    Map<String, Object> attrs = new HashMap<>();
    if (obContext == null) {
      return attrs;
    }
    attrs.put("#AD_User_ID", obContext.getUser().getId());
    attrs.put("#AD_Role_ID", obContext.getRole().getId());
    attrs.put("#AD_Client_ID", obContext.getCurrentClient().getId());
    attrs.put("#AD_Org_ID", obContext.getCurrentOrganization().getId());
    if (obContext.getWarehouse() != null) {
      attrs.put("#M_Warehouse_ID", obContext.getWarehouse().getId());
    }
    attrs.put("#AD_Language", obContext.getLanguage().getLanguage());
    return attrs;
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
  @SuppressWarnings("unchecked")
  static JSONObject transformResponse(JSONObject calloutResult, Tab adTab) {
    JSONObject response = new JSONObject();

    try {
      JSONObject updates = new JSONObject();
      JSONObject combos = new JSONObject();
      JSONArray messages = new JSONArray();

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

    // Try to find the column whose inp name matches
    if (adTab != null && adTab.getTable() != null) {
      try {
        OBCriteria<Column> colCriteria = OBDal.getInstance().createCriteria(Column.class);
        colCriteria.add(Restrictions.eq(Column.PROPERTY_TABLE + ".id",
            adTab.getTable().getId()));
        colCriteria.add(Restrictions.eq(Column.PROPERTY_ACTIVE, true));
        List<Column> columns = colCriteria.list();

        for (Column col : columns) {
          if (toInpName(col.getDBColumnName()).equals(inpName)) {
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
