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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.LoginUtils;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.sequences.SequenceUtils;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Service for resolving default values when creating a new record via NEO Headless.
 *
 * Delegates to Etendo's existing static utilities (Utility.getDefault, Utility.getDocumentNo,
 * Utility.parseContext) rather than reimplementing default resolution logic. A VariablesSecureApp
 * bridge is built from OBContext to satisfy the utility method signatures.
 *
 * Resolves:
 * - Literal values ("DR", "N", "0")
 * - Session context variables (@#AD_Org_ID@, @#Date@, etc.) via Utility.getDefault
 * - Preferences via Utility.getPreference (called internally by getDefault)
 * - Comma-separated fallback expressions (handled by Utility.getDefault)
 * - SQL default expressions (@SQL=...) via direct execution with Utility.parseContext
 * - Sequence/DocumentNo previews via Utility.getDocumentNo (updateNext=false)
 * - IsActive = true (always, NEO-specific behavior)
 * - Link-to-parent columns (from parentId query parameter, NEO-specific behavior)
 *
 * Endpoint: GET /sws/neo/{specName}/{entityName}/defaults
 */
public class NeoDefaultsService {

  private static final Logger log = LogManager.getLogger(NeoDefaultsService.class);
  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final int MAX_CALLOUT_CHAIN_DEPTH = 5;

  // Cache VariablesSecureApp per user+role+org+warehouse combination to avoid calling
  // LoginUtils.fillSessionArguments (multiple DB queries) on every request.
  // In the future, if session variables need to reflect real-time preference changes,
  // this TTL can be shortened or the cache can be invalidated on preference updates.
  private static final Cache<String, VariablesSecureApp> varsCache = CacheBuilder.newBuilder()
      .maximumSize(100)
      .expireAfterWrite(5, TimeUnit.MINUTES)
      .build();

  private NeoDefaultsService() {
  }

  /**
   * Resolve default values for all included fields of an entity.
   *
   * @param ctx      the NeoContext with spec/entity/tab info
   * @param parentId optional parent record ID for child entities
   * @return NeoResponse with defaults map and metadata
   */
  public static NeoResponse resolveDefaults(NeoContext ctx, String parentId) {
    try {
      OBContext.setAdminMode();
      try {
        JSONObject defaults = new JSONObject();
        JSONArray unresolvedFields = new JSONArray();
        JSONArray sequenceFields = new JSONArray();

        // Build a VariablesSecureApp bridge from OBContext for Etendo utility methods
        VariablesSecureApp vars = buildVariablesSecureApp(ctx.getObContext());
        DalConnectionProvider conn = new DalConnectionProvider(false);

        // Resolve window ID from SFSpec -> AD_Window (needed by Utility.getDefault)
        String windowId = resolveWindowId(ctx.getSfEntity());

        // Load all active, included SFField records for this entity
        OBCriteria<SFField> fieldCrit = OBDal.getInstance().createCriteria(SFField.class);
        fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id",
            ctx.getSfEntity().getId()));
        fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
        fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
        List<SFField> fields = fieldCrit.list();

        // Resolve the DAL entity once for property name lookup (same names as GET responses)
        Entity dalEntity = resolveDalEntity(ctx.getSfEntity());

        for (SFField sfField : fields) {
          Column adColumn = sfField.getADColumn();
          if (adColumn == null) {
            continue;
          }

          String dbColumnName = adColumn.getDBColumnName();
          // Use OBDal property name (e.g., "orderDate" for DateOrdered, "businessPartner"
          // for C_BPartner_ID) — matches GET response keys and frontend field keys
          String propertyName = resolvePropertyName(dalEntity, dbColumnName);

          try {
            Object resolvedValue = resolveFieldDefault(adColumn, parentId, vars, conn, windowId,
                ctx);
            if (resolvedValue != null) {
              defaults.put(propertyName, resolvedValue);

              // Track sequence fields (wrapped in angle brackets by Utility.getDocumentNo)
              if (isSequenceField(adColumn) && resolvedValue instanceof String
                  && ((String) resolvedValue).startsWith("<")) {
                sequenceFields.put(propertyName);
              }
            }
          } catch (Exception e) {
            log.debug("Could not resolve default for column {}: {}",
                dbColumnName, e.getMessage());
            unresolvedFields.put(propertyName);
          }
        }

        // Execute callout cascade for defaulted fields that have callouts configured
        Tab adTab = ctx.getAdTab();
        Set<String> seqFieldSet = new HashSet<>();
        for (int i = 0; i < sequenceFields.length(); i++) {
          seqFieldSet.add(sequenceFields.getString(i));
        }

        CalloutCascadeResult cascadeResult = null;
        if (adTab != null) {
          cascadeResult = executeCalloutCascade(ctx, adTab, defaults, seqFieldSet);
        }

        // Build response
        JSONObject response = new JSONObject();
        response.put("defaults", defaults);

        if (cascadeResult != null && cascadeResult.hasResults()) {
          response.put("calloutResults", cascadeResult.toJSON());
        }

        JSONObject metadata = new JSONObject();
        metadata.put("unresolvedFields", unresolvedFields);
        metadata.put("sequenceFields", sequenceFields);
        if (cascadeResult != null) {
          metadata.put("calloutChainDepth", cascadeResult.chainDepth);
          if (cascadeResult.truncated) {
            metadata.put("calloutChainTruncated", true);
          }
        }
        response.put("metadata", metadata);

        return NeoResponse.ok(response);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error resolving defaults: {}", e.getMessage(), e);
      return NeoResponse.error(500, "Failed to resolve defaults: " + e.getMessage());
    }
  }

  /**
   * Resolve the default value for a single AD_Column.
   * Delegates to Etendo Utility methods for context vars, preferences, and comma fallbacks.
   * Keeps NEO-specific behavior (IsActive, linkToParent) as direct logic.
   *
   * @return the resolved value, or null if no default is configured
   */
  private static Object resolveFieldDefault(Column adColumn, String parentId,
      VariablesSecureApp vars, DalConnectionProvider conn, String windowId, NeoContext ctx) {

    String dbColumnName = adColumn.getDBColumnName();

    // NEO-specific: IsActive always defaults to true
    if ("IsActive".equalsIgnoreCase(dbColumnName)) {
      return true;
    }

    // NEO-specific: Link-to-parent columns use the parentId from query params
    if (adColumn.isLinkToParentColumn() && parentId != null && !parentId.isEmpty()) {
      return parentId;
    }

    // Sequence/DocumentNo fields — use Utility.getDocumentNo for real preview
    if (isSequenceField(adColumn)) {
      String preview = resolveSequencePreview(adColumn, vars, conn, windowId, ctx);
      if (preview != null) {
        return preview;
      }
    }

    // Get the default value expression from AD_Column
    String defaultExpr = adColumn.getDefaultValue();
    if (defaultExpr == null || defaultExpr.trim().isEmpty()) {
      // No column-level default, but Utility.getDefault also checks preferences
      // which may provide a default for this column+window combination
      String fromPrefs = Utility.getDefault(conn, vars, dbColumnName, "", windowId, "");
      if (fromPrefs != null && !fromPrefs.isEmpty()) {
        return fromPrefs;
      }
      return null;
    }

    defaultExpr = defaultExpr.trim();

    // Handle empty-string literal
    if ("\"\"".equals(defaultExpr)) {
      return "";
    }

    // SQL expressions — resolve parameters and execute
    if (defaultExpr.startsWith("@SQL=")) {
      return resolveSQLDefault(defaultExpr, vars, conn, windowId, adColumn);
    }

    // Delegate to Utility.getDefault for all other cases:
    // - Literal values (no @ signs)
    // - Context variables (@#AD_Org_ID@, @#Date@, etc.)
    // - Preferences (checked first by Utility.getDefault)
    // - Comma-separated alternatives (@#Var1@,@#Var2@,literal)
    String resolved = Utility.getDefault(conn, vars, dbColumnName, defaultExpr, windowId, "");

    if (resolved != null && !resolved.isEmpty()) {
      return resolved;
    }

    return null;
  }

  /**
   * Check if a column is a sequence/DocumentNo field.
   * Uses SequenceUtils.isSequence() from Etendo core for the reference-based check,
   * plus the classic DocumentNo/Value detection.
   */
  private static boolean isSequenceField(Column adColumn) {
    // Check via Etendo's SequenceUtils (reference-based sequence configuration)
    if (Boolean.TRUE.equals(SequenceUtils.isSequence(adColumn))) {
      return true;
    }
    // Classic fallback: DocumentNo or Value with automatic sequence
    String dbName = adColumn.getDBColumnName();
    return "DocumentNo".equalsIgnoreCase(dbName)
        || ("Value".equalsIgnoreCase(dbName)
            && Boolean.TRUE.equals(adColumn.isUseAutomaticSequence()));
  }

  /**
   * Generate a preview of the next sequence value without consuming it.
   * Uses Utility.getDocumentNo with updateNext=false for a real preview.
   * Returns the value wrapped in angle brackets (e.g., "<1000234>").
   */
  private static String resolveSequencePreview(Column adColumn, VariablesSecureApp vars,
      DalConnectionProvider conn, String windowId, NeoContext ctx) {
    try {
      String tableName = adColumn.getTable().getDBTableName();
      // At init time we don't know the document type yet, so pass empty strings
      String docNo = Utility.getDocumentNo(conn, vars, windowId, tableName, "", "", false, false);
      if (docNo != null && !docNo.isEmpty()) {
        return "<" + docNo + ">";
      }
      return "<auto>";
    } catch (Exception e) {
      log.debug("Could not generate sequence preview for {}: {}",
          adColumn.getDBColumnName(), e.getMessage());
      return "<auto>";
    }
  }

  /**
   * Resolve a @SQL= default expression.
   * Adapted from UIDefinition.getDefaultValueFromSQLExpression — parses the SQL,
   * resolves @parameter@ tokens via Utility.getContext, and executes the query.
   */
  private static String resolveSQLDefault(String defaultExpr, VariablesSecureApp vars,
      DalConnectionProvider conn, String windowId, Column adColumn) {
    try {
      ArrayList<String> params = new ArrayList<>();
      String sql = parseSQLExpression(defaultExpr, params);

      try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
        int paramIndex = 1;
        for (String parameter : params) {
          String value = Utility.getContext(conn, vars, parameter, windowId);
          ps.setObject(paramIndex++, value);
        }

        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return rs.getString(1);
          }
        }
      }
      return null;
    } catch (Exception e) {
      log.debug("Could not resolve SQL default for column {}: {}",
          adColumn.getDBColumnName(), e.getMessage());
      return null;
    }
  }

  /**
   * Parse a @SQL= expression, extracting parameter names and replacing @param@ tokens with ?.
   * Simplified version of UIDefinition.parseSQL adapted for NEO context.
   *
   * Input: "@SQL=SELECT name FROM ad_org WHERE ad_org_id = '@#AD_Org_ID@'"
   * Output SQL: "SELECT name FROM ad_org WHERE ad_org_id = ?"
   * Output params: ["#AD_Org_ID"]
   */
  private static String parseSQLExpression(String expression, ArrayList<String> paramNames) {
    if (expression == null || expression.trim().isEmpty()) {
      return "";
    }

    String value = expression;

    // Remove @SQL= prefix
    int sqlStart = value.indexOf("@SQL=");
    if (sqlStart >= 0) {
      value = value.substring(sqlStart + 5);
    }

    StringBuilder sqlOut = new StringBuilder();
    int i = value.indexOf("@");

    while (i != -1) {
      // Append everything before the @
      String before = value.substring(0, i);
      // Strip trailing quote if parameter was quoted in SQL (e.g., '@param@')
      if (before.endsWith("'")) {
        before = before.substring(0, before.length() - 1);
      }
      sqlOut.append(before);

      value = value.substring(i + 1);
      int j = value.indexOf("@");
      if (j < 0) {
        // No closing @ — append remaining and stop
        sqlOut.append(value);
        break;
      }

      // Extract token name
      String token = value.substring(0, j);
      paramNames.add(token);
      sqlOut.append("?");

      value = value.substring(j + 1);
      // Strip leading quote after closing @ (e.g., '@param@')
      if (value.startsWith("'")) {
        value = value.substring(1);
      }
      i = value.indexOf("@");
    }

    sqlOut.append(value);
    return sqlOut.toString();
  }

  /**
   * Build a VariablesSecureApp from OBContext, fully populated with ALL session variables.
   * Results are cached by user+role+org+warehouse key to avoid the overhead of
   * {@link LoginUtils#fillSessionArguments} (multiple DB queries) on every request.
   *
   * <p>Delegates to {@link LoginUtils#fillSessionArguments} — the same method that Etendo's
   * classic login uses. This ensures all context variables ($C_Currency_ID, $C_AcctSchema_ID,
   * $Element_*, preferences, accounting dimensions, etc.) are resolved identically to the
   * classic UI, without hardcoding individual variables.</p>
   */
  private static VariablesSecureApp buildVariablesSecureApp(OBContext obContext) {
    String userId = obContext.getUser().getId();
    String clientId = obContext.getCurrentClient().getId();
    String orgId = obContext.getCurrentOrganization().getId();
    String roleId = obContext.getRole().getId();
    String lang = obContext.getLanguage().getLanguage();
    String warehouseId = obContext.getWarehouse() != null
        ? obContext.getWarehouse().getId() : "";

    String cacheKey = userId + "|" + roleId + "|" + orgId + "|" + warehouseId;
    VariablesSecureApp cached = varsCache.getIfPresent(cacheKey);
    if (cached != null) {
      return cached;
    }

    VariablesSecureApp vars = new VariablesSecureApp(userId, clientId, orgId, roleId, lang);
    DalConnectionProvider conn = new DalConnectionProvider(false);

    try {
      LoginUtils.fillSessionArguments(conn, vars, userId, lang, "N",
          roleId, clientId, orgId, warehouseId);
    } catch (Exception e) {
      log.warn("LoginUtils.fillSessionArguments failed, falling back to minimal setup: {}",
          e.getMessage());
      // Minimal fallback — at least set the basics so simple literals resolve
      vars.setSessionValue("#AD_User_ID", userId);
      vars.setSessionValue("#AD_Client_ID", clientId);
      vars.setSessionValue("#AD_Org_ID", orgId);
      vars.setSessionValue("#AD_Role_ID", roleId);
      vars.setSessionValue("#AD_Language", lang);
      vars.setSessionValue("#M_Warehouse_ID", warehouseId);
      vars.setSessionValue("#User_Client", "'" + clientId + "','0'");
      vars.setSessionValue("#Date",
          new SimpleDateFormat(DATE_FORMAT).format(new Date()));
    }

    varsCache.put(cacheKey, vars);
    return vars;
  }

  /**
   * Resolve the window ID from the SFEntity -> SFSpec -> AD_Window chain.
   * Returns empty string if no window is linked (e.g., process specs).
   */
  private static String resolveWindowId(com.etendoerp.go.schemaforge.data.SFEntity sfEntity) {
    try {
      SFSpec spec = sfEntity.getETGOSFSpec();
      if (spec != null) {
        Window window = spec.getADWindow();
        if (window != null) {
          return window.getId();
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve window ID: {}", e.getMessage());
    }
    return "";
  }

  /**
   * Resolve default values for mandatory table columns that are NOT configured in
   * ETGO_SF_FIELD. These are "system" columns with NOT NULL DB constraints that need
   * a value on INSERT (e.g., C_DocType_ID = "0", DateAcct = today, C_Currency_ID).
   *
   * <p>Uses the same resolution logic as the /defaults endpoint (Utility.getDefault,
   * context variables, SQL expressions, preferences), so expressions like @#Date@
   * and @C_Currency_ID@ are resolved correctly.</p>
   *
   * @param body    the filtered request body — columns already present are skipped
   * @param adTab   the AD_Tab for the entity being created
   * @param ctx     the NeoContext with OBContext and spec/entity info
   */
  public static void injectMandatoryDefaults(JSONObject body, Tab adTab, NeoContext ctx) {
    injectMandatoryDefaults(body, adTab, ctx, null);
  }

  public static void injectMandatoryDefaults(JSONObject body, Tab adTab, NeoContext ctx, String parentId) {
    if (body == null || adTab == null || ctx == null) {
      return;
    }
    try {
      Entity dalEntity = ModelProvider.getInstance()
          .getEntityByTableId(adTab.getTable().getId());
      if (dalEntity == null) {
        return;
      }

      // Build resolution infrastructure once for all columns
      VariablesSecureApp vars = buildVariablesSecureApp(ctx.getObContext());
      DalConnectionProvider conn = new DalConnectionProvider(false);
      String windowId = ctx.getSfEntity() != null ? resolveWindowId(ctx.getSfEntity()) : "";

      for (Column col : adTab.getTable().getADColumnList()) {
        if (!col.isActive() || !col.isMandatory()) {
          continue;
        }

        // Resolve the DAL property name
        Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
        if (prop == null) {
          continue;
        }

        String propName = prop.getName();
        // Skip if already present in the body (user or field-filter provided it)
        if (body.has(propName)) {
          continue;
        }

        // Resolve using the same logic as the /defaults endpoint
        try {
          Object resolved = resolveFieldDefault(col, parentId, vars, conn, windowId, ctx);
          if (resolved != null) {
            // Skip FK columns with legacy "0" default — OBDal cannot resolve "0" as an entity ID.
            // These columns (e.g., C_DocType_ID) use "0" to mean "no document type" in classic UI,
            // but OBDal's JSON import expects either a real UUID or null.
            if ("0".equals(String.valueOf(resolved))
                && col.getDBColumnName().toUpperCase().endsWith("_ID")) {
              log.debug("Skipping FK default '0' for {}", propName);
              continue;
            }
            body.put(propName, resolved);
            log.debug("Injected mandatory default: {} = {}", propName, resolved);
          }
        } catch (Exception e) {
          log.debug("Could not resolve mandatory default for {}: {}",
              col.getDBColumnName(), e.getMessage());
        }
      }
    } catch (Exception e) {
      log.error("Error injecting mandatory defaults for tab {}: {}",
          adTab.getName(), e.getMessage(), e);
    }
  }

  /**
   * Resolve the DAL Entity from the SFEntity's linked AD_Tab -> AD_Table.
   * Returns null if the entity cannot be resolved.
   */
  private static Entity resolveDalEntity(
      com.etendoerp.go.schemaforge.data.SFEntity sfEntity) {
    try {
      Tab adTab = sfEntity.getADTab();
      if (adTab != null && adTab.getTable() != null) {
        return ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
      }
    } catch (Exception e) {
      log.debug("Could not resolve DAL entity: {}", e.getMessage());
    }
    return null;
  }

  /**
   * Resolve the OBDal property name for a DB column.
   * Uses ModelProvider to get the same names returned by NeoServlet GET responses
   * (e.g., "orderDate" for DateOrdered, "businessPartner" for C_BPartner_ID).
   * Falls back to toCleanFieldName if ModelProvider lookup fails.
   */
  private static String resolvePropertyName(Entity dalEntity, String dbColumnName) {
    if (dalEntity != null) {
      try {
        Property prop = dalEntity.getPropertyByColumnName(dbColumnName);
        if (prop != null) {
          return prop.getName();
        }
      } catch (Exception e) {
        log.debug("Could not resolve property name for column {}: {}",
            dbColumnName, e.getMessage());
      }
    }
    // Fallback to heuristic if DAL entity is not available
    return NeoCalloutService.toCleanFieldName(dbColumnName);
  }

  // ── Callout cascade ─────────────────────────────────────────────────

  /**
   * Execute callout cascade after default resolution.
   * For each defaulted field that has a callout configured, execute it and merge
   * the results back into the defaults (so subsequent callouts see updated values).
   *
   * @param ctx          the NeoContext
   * @param adTab        the AD_Tab for callout resolution
   * @param defaults     the resolved defaults (modified in place with callout updates)
   * @param seqFields    field names that are sequence previews (skip callouts for these)
   * @return aggregated callout results
   */
  private static CalloutCascadeResult executeCalloutCascade(NeoContext ctx, Tab adTab,
      JSONObject defaults, Set<String> seqFields) {

    CalloutCascadeResult result = new CalloutCascadeResult();

    try {
      // Collect fields that have callouts and non-null defaults
      List<String> fieldsWithCallouts = new ArrayList<>();
      Iterator<String> keys = defaults.keys();
      while (keys.hasNext()) {
        String fieldName = keys.next();
        if (seqFields.contains(fieldName)) {
          continue;
        }
        Object value = defaults.opt(fieldName);
        if (value == null || JSONObject.NULL.equals(value)) {
          continue;
        }
        NeoCalloutService.CalloutInfo info = NeoCalloutService.resolveCallout(adTab, fieldName);
        if (info != null) {
          fieldsWithCallouts.add(fieldName);
        }
      }

      if (fieldsWithCallouts.isEmpty()) {
        return result;
      }

      log.info("[NEO-DEFAULTS] Callout cascade: {} fields have callouts: {}",
          fieldsWithCallouts.size(), fieldsWithCallouts);

      // Build a running form state from current defaults
      JSONObject formState = new JSONObject(defaults.toString());

      // Execute callouts up to MAX_CALLOUT_CHAIN_DEPTH iterations.
      // Each iteration processes all pending fields. If a callout produces updates
      // that affect other fields with callouts, those are queued for the next iteration.
      Set<String> pendingFields = new LinkedHashSet<>(fieldsWithCallouts);
      int depth = 0;

      while (!pendingFields.isEmpty() && depth < MAX_CALLOUT_CHAIN_DEPTH) {
        depth++;
        Set<String> nextPending = new LinkedHashSet<>();

        for (String fieldName : pendingFields) {
          Object value = formState.opt(fieldName);
          if (value == null || JSONObject.NULL.equals(value)) {
            continue;
          }

          try {
            JSONObject calloutRequest = new JSONObject();
            calloutRequest.put("field", fieldName);
            calloutRequest.put("value", value);
            calloutRequest.put("formState", formState);

            NeoResponse calloutResponse = NeoCalloutService.executeCallout(ctx, calloutRequest);
            if (calloutResponse == null || calloutResponse.getHttpStatus() != 200) {
              log.debug("[NEO-DEFAULTS] Callout for '{}' failed or returned non-200", fieldName);
              continue;
            }

            JSONObject calloutBody = calloutResponse.getBody();
            if (calloutBody == null) {
              continue;
            }

            // Merge updates into form state and track which fields changed
            JSONObject updates = calloutBody.optJSONObject("updates");
            if (updates != null) {
              result.mergeUpdates(updates);
              Iterator<String> updateKeys = updates.keys();
              while (updateKeys.hasNext()) {
                String updatedField = updateKeys.next();
                JSONObject updateObj = updates.optJSONObject(updatedField);
                if (updateObj != null && updateObj.has("value")) {
                  Object newValue = updateObj.get("value");
                  Object oldValue = formState.opt(updatedField);

                  formState.put(updatedField, newValue);
                  defaults.put(updatedField, newValue);

                  // If the updated field itself has a callout and the value changed,
                  // queue it for the next iteration
                  if (!valueChanged(oldValue, newValue)) {
                    continue;
                  }
                  if (seqFields.contains(updatedField)) {
                    continue;
                  }
                  NeoCalloutService.CalloutInfo nextInfo =
                      NeoCalloutService.resolveCallout(adTab, updatedField);
                  if (nextInfo != null) {
                    nextPending.add(updatedField);
                  }
                }
              }
            }

            // Merge combos
            JSONObject combos = calloutBody.optJSONObject("combos");
            if (combos != null) {
              result.mergeCombos(combos);
            }

            // Merge messages
            JSONArray messages = calloutBody.optJSONArray("messages");
            if (messages != null) {
              result.mergeMessages(messages);
            }

          } catch (Exception e) {
            log.warn("[NEO-DEFAULTS] Callout cascade error for field '{}': {}",
                fieldName, e.getMessage());
          }
        }

        pendingFields = nextPending;
      }

      result.chainDepth = depth;
      result.truncated = depth >= MAX_CALLOUT_CHAIN_DEPTH && !pendingFields.isEmpty();
      if (result.truncated) {
        log.warn("[NEO-DEFAULTS] Callout cascade reached max depth {} with pending fields: {}",
            MAX_CALLOUT_CHAIN_DEPTH, pendingFields);
      }

    } catch (Exception e) {
      log.error("[NEO-DEFAULTS] Error in callout cascade: {}", e.getMessage(), e);
    }

    return result;
  }

  /**
   * Check if two values are different (for detecting actual changes from callouts).
   */
  private static boolean valueChanged(Object oldValue, Object newValue) {
    if (oldValue == null && newValue == null) {
      return false;
    }
    if (oldValue == null || newValue == null) {
      return true;
    }
    return !oldValue.toString().equals(newValue.toString());
  }

  /**
   * Aggregated result of the callout cascade execution.
   */
  static class CalloutCascadeResult {
    private final JSONObject updates = new JSONObject();
    private final JSONObject combos = new JSONObject();
    private final JSONArray messages = new JSONArray();
    int chainDepth = 0;
    boolean truncated = false;

    boolean hasResults() {
      return updates.length() > 0 || combos.length() > 0 || messages.length() > 0;
    }

    void mergeUpdates(JSONObject newUpdates) {
      Iterator<String> keys = newUpdates.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        try {
          updates.put(key, newUpdates.get(key));
        } catch (Exception e) {
          // skip
        }
      }
    }

    void mergeCombos(JSONObject newCombos) {
      Iterator<String> keys = newCombos.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        try {
          combos.put(key, newCombos.get(key));
        } catch (Exception e) {
          // skip
        }
      }
    }

    void mergeMessages(JSONArray newMessages) {
      for (int i = 0; i < newMessages.length(); i++) {
        try {
          messages.put(newMessages.get(i));
        } catch (Exception e) {
          // skip
        }
      }
    }

    JSONObject toJSON() {
      JSONObject json = new JSONObject();
      try {
        json.put("updates", updates);
        json.put("combos", combos);
        json.put("messages", messages);
      } catch (Exception e) {
        // should never happen
      }
      return json;
    }
  }

}
