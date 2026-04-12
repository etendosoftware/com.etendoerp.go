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
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import org.openbravo.base.structure.BaseOBObject;
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
  private static final String VALUE_KEY = "value";
  private static final int MAX_CALLOUT_CHAIN_DEPTH = 5;
  private static final String KEY_UPDATES = "updates";
  private static final java.util.regex.Pattern SUBTYPE_NOT_LIKE_PATTERN =
      java.util.regex.Pattern.compile("sOSubType\\s+NOT\\s+LIKE\\s+'(\\w+)'",
          java.util.regex.Pattern.CASE_INSENSITIVE);
  private static final java.util.regex.Pattern SUBTYPE_LIKE_PATTERN =
      java.util.regex.Pattern.compile("sOSubType\\s+LIKE\\s+'(\\w+)'",
          java.util.regex.Pattern.CASE_INSENSITIVE);

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
   * <p>Uses a two-pass approach to mirror Etendo Classic's FormInitializationComponent behavior:
   * <ol>
   *   <li>Pass 1 — all non-sequence fields (including doctype columns like C_DocTypeTarget_ID)
   *   <li>Pass 2 — sequence/DocumentNo fields, using the doctypes resolved in pass 1
   * </ol>
   * Classic processes C_DocTypeTarget_ID before DocumentNo and writes the result back to
   * RequestContext so that UIDefinition.getFieldProperties can read the correct doctype when
   * calling Utility.getDocumentNo. We reproduce the same ordering guarantee here by deferring
   * sequence fields to a second pass after all doctype defaults are known.
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

        // --- Pass 1: non-sequence fields (doctype columns included) ---
        // Sequence fields are deferred so that when we compute DocumentNo in pass 2 we can
        // pass the already-resolved C_DocTypeTarget_ID / C_DocType_ID values to
        // Utility.getDocumentNo — exactly as FormInitializationComponent does.
        List<SFField> sequenceSFFields = new ArrayList<>();
        for (SFField sfField : fields) {
          Column adColumn = sfField.getADColumn();
          if (adColumn == null) {
            continue;
          }
          if (isSequenceField(adColumn)) {
            sequenceSFFields.add(sfField);  // defer to pass 2
            continue;
          }

          String dbColumnName = adColumn.getDBColumnName();
          String propertyName = resolvePropertyName(dalEntity, dbColumnName);

          try {
            Object resolvedValue = resolveFieldDefault(adColumn, parentId, vars, conn, windowId,
                ctx);
            if (resolvedValue != null) {
              defaults.put(propertyName, resolvedValue);
              // For FK fields, also inject $_identifier so selectors display the label, not the ID
              tryInjectIdentifier(defaults, dalEntity, propertyName, resolvedValue);
            }
          } catch (Exception e) {
            log.debug("Could not resolve default for column {}: {}",
                dbColumnName, e.getMessage());
            unresolvedFields.put(propertyName);
          }
        }

        // --- Pass 2: sequence/DocumentNo fields with doctype from pass 1 ---
        // Reads C_DocTypeTarget_ID and C_DocType_ID from the defaults already built,
        // then calls Utility.getDocumentNo(conn, vars, windowId, tableName,
        //   docTypeTargetId, docTypeId, false, false)
        // — the same call that UIDefinition.getFieldProperties line 210 makes.
        String[] docTypeIds = resolveDocTypeIdsFromDefaults(defaults, dalEntity);
        String docTypeTargetId = docTypeIds[0];
        String docTypeId = docTypeIds[1];

        for (SFField sfField : sequenceSFFields) {
          Column adColumn = sfField.getADColumn();
          String dbColumnName = adColumn.getDBColumnName();
          String propertyName = resolvePropertyName(dalEntity, dbColumnName);

          try {
            String preview = resolveSequencePreviewWithDocType(
                adColumn, vars, conn, windowId, docTypeTargetId, docTypeId);
            if (preview != null) {
              defaults.put(propertyName, preview);
              sequenceFields.put(propertyName);
            }
          } catch (Exception e) {
            log.debug("Could not generate sequence preview for {}: {}",
                dbColumnName, e.getMessage());
            unresolvedFields.put(propertyName);
          }
        }

        // NOTE: The callout cascade is intentionally NOT run here.
        // Running callouts in the defaults endpoint pre-fills billing/preference fields from
        // org-level AD_Preferences (price list, payment method, payment terms, account, blocking
        // flags, vendor flag, etc.) — resulting in a form that looks "used" even for new records.
        // Callouts should respond to user-initiated field changes via the live /callout endpoint,
        // not fire automatically when the user opens a blank form.
        // The two-pass defaults system (pass 1 → doctype, pass 2 → sequence with doctype) already
        // covers the only valid cross-field dependency in defaults without needing callouts.

        // Build response
        JSONObject response = new JSONObject();
        response.put("defaults", defaults);

        JSONObject metadata = new JSONObject();
        metadata.put("unresolvedFields", unresolvedFields);
        metadata.put("sequenceFields", sequenceFields);
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
   * For FK (non-primitive) properties, looks up the referenced record by ID and injects its
   * display name as {@code propertyName$_identifier} so selectors show a label instead of a
   * raw ID string. Silently skips if the property is primitive, the entity is not found, or
   * the lookup fails.
   */
  private static void tryInjectIdentifier(JSONObject defaults, Entity dalEntity,
      String propertyName, Object resolvedValue) {
    if (dalEntity == null || resolvedValue == null) {
      return;
    }
    try {
      Property prop = dalEntity.getProperty(propertyName);
      if (prop == null || prop.isPrimitive()) {
        return;
      }
      Entity targetEntity = prop.getTargetEntity();
      if (targetEntity == null) {
        return;
      }
      BaseOBObject obj = OBDal.getInstance().get(targetEntity.getName(), resolvedValue.toString());
      if (obj != null) {
        defaults.put(propertyName + "$_identifier", obj.getIdentifier());
      }
    } catch (Exception e) {
      log.debug("Could not resolve identifier for default field '{}': {}", propertyName,
          e.getMessage());
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

    // NEO-specific overrides (IsActive, link-to-parent, sequences)
    Object neoOverride = resolveNeoSpecificDefault(adColumn, parentId, vars, conn, windowId, ctx);
    if (neoOverride != null) {
      return neoOverride;
    }

    // Get the default value expression from AD_Column
    String defaultExpr = adColumn.getDefaultValue();
    if (defaultExpr == null || defaultExpr.trim().isEmpty()) {
      return resolveFromPrefsOrDocType(adColumn, vars, conn, windowId, dbColumnName, ctx);
    }

    return resolveFromExpression(defaultExpr.trim(), adColumn, vars, conn, windowId, dbColumnName);
  }

  /**
   * Resolve NEO-specific default overrides: IsActive, link-to-parent, and sequence fields.
   * Returns null if no NEO-specific override applies.
   */
  private static Object resolveNeoSpecificDefault(Column adColumn, String parentId,
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

    return null;
  }

  /**
   * Resolve default from preferences or doctype when no column-level default expression exists.
   *
   * <p>For doctype columns (*DOCTYPE*_ID), Utility.getDefault is intentionally skipped and
   * replaced with Utility.getPreference. LoginUtils.fillSessionArguments stores an arbitrary
   * C_DocType record in the session variable {@code #C_DocTypeTarget_ID} (the first isDefault='Y'
   * row from DefaultValuesData, with no stable ORDER BY). Utility.getDefault reads that session
   * variable and returns a wrong doctype (e.g. "Inventory Move" instead of "AR Invoice").
   * Utility.getPreference only checks real AD_Preference records (P|window|col and P|col keys),
   * which are window-scoped and therefore correct. After the preference check, the context-aware
   * resolveDefaultDocTypeId is used as fallback.</p>
   */
  private static Object resolveFromPrefsOrDocType(Column adColumn, VariablesSecureApp vars,
      DalConnectionProvider conn, String windowId, String dbColumnName, NeoContext ctx) {
    String colUpper = dbColumnName.toUpperCase();
    if (colUpper.endsWith("_ID") && colUpper.contains("DOCTYPE")) {
      // For doctype columns skip Utility.getDefault entirely and resolve from DB context.
      // Utility.getDefault reads #C_DocTypeTarget_ID (set by LoginUtils to an arbitrary default
      // from DefaultValuesData — e.g. "Inventory Move") and Utility.getPreference may return
      // stale AD_Preference values pointing to deleted doctypes.
      // Classic validates preferences against the filtered combo list; we cannot replicate that
      // here, so we go straight to resolveDefaultDocTypeId which queries c_doctype live with the
      // correct docBaseType, isSOTrx and org filters.
      return resolveDefaultDocTypeId(adColumn, ctx);
    }
    // For columns without AD_Column.DefaultValue, resolve only explicit preferences.
    // Do NOT use Utility.getDefault(..., "") here because it also falls back to session
    // variables (#COLUMN / $COLUMN), which can contain SmartClient temporary import keys
    // like "100_BusinessPartner" and break POST create payloads.
    String fromPrefs = Utility.getPreference(vars, dbColumnName, windowId != null ? windowId : "");
    if (fromPrefs != null && !fromPrefs.isEmpty()) {
      return fromPrefs;
    }
    String docTypeId = resolveDefaultDocTypeId(adColumn, ctx);
    if (docTypeId != null) {
      return docTypeId;
    }
    // Fallback: read DB-level column default from information_schema when AD has no expression.
    // Handles mandatory boolean/char columns whose AD_Column.DefaultValue is null/empty but
    // the DB column has a DEFAULT clause (e.g., IsEmployee DEFAULT 'N', IsProspect DEFAULT 'Y').
    if (!colUpper.endsWith("_ID") && adColumn.getTable() != null) {
      String dbDefault = resolveDbColumnDefault(
          adColumn.getTable().getDBTableName(), dbColumnName);
      if (dbDefault != null) {
        return dbDefault;
      }
    }
    return null;
  }

  /**
   * Read the DB-level column DEFAULT from {@code information_schema.columns}.
   * Used as a last-resort fallback when {@code AD_Column.DefaultValue} is null/empty and
   * no preference or doctype default can be resolved.
   *
   * <p>Parses PostgreSQL's {@code column_default} format:
   * <ul>
   *   <li>{@code 'N'::bpchar} → {@code N}</li>
   *   <li>{@code 'Y'::character varying} → {@code Y}</li>
   *   <li>{@code '1'::character varying} → {@code 1}</li>
   *   <li>{@code 0} (numeric literal) → {@code 0}</li>
   * </ul>
   *
   * @param tableName  DB table name (matched case-insensitively)
   * @param columnName DB column name (matched case-insensitively)
   * @return parsed default value string, or {@code null} if not found or not parseable
   */
  private static String resolveDbColumnDefault(String tableName, String columnName) {
    try {
      String sql = "SELECT column_default FROM information_schema.columns "
          + "WHERE LOWER(table_name) = LOWER(?) AND LOWER(column_name) = LOWER(?)";
      try (PreparedStatement ps =
          OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
        ps.setString(1, tableName);
        ps.setString(2, columnName);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String colDefault = rs.getString(1);
            if (colDefault == null || colDefault.isEmpty()) {
              return null;
            }
            // Parse PostgreSQL column_default format, e.g. 'N'::bpchar
            if (colDefault.startsWith("'")) {
              int endQuote = colDefault.indexOf("'", 1);
              if (endQuote > 0) {
                return colDefault.substring(1, endQuote);
              }
            }
            // Numeric literals like 0 or 0.00
            String stripped = colDefault.split("::")[0].trim();
            if (!stripped.isEmpty()) {
              return stripped;
            }
          }
        }
      }
    } catch (SQLException e) {
      log.debug("Could not read DB-level column default for {}.{}: {}",
          tableName, columnName, e.getMessage());
    }
    return null;
  }

  /**
   * Resolve default from a column-level default expression (literal, SQL, context variable, etc.).
   */
  private static Object resolveFromExpression(String defaultExpr, Column adColumn,
      VariablesSecureApp vars, DalConnectionProvider conn, String windowId, String dbColumnName) {
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
   * Generate a sequence preview using the doctype IDs already resolved in pass 1.
   *
   * <p>Mirrors UIDefinition.getFieldProperties line 210 exactly:
   * {@code Utility.getDocumentNo(conn, vars, windowId, tableName, docTypeTarget, docType, false, false)}
   * Classic reads docTypeTarget from RequestContext (set when C_DocTypeTarget_ID was processed
   * before DocumentNo). We pass those values explicitly after resolving them in pass 1.
   */
  private static String resolveSequencePreviewWithDocType(Column adColumn,
      VariablesSecureApp vars, DalConnectionProvider conn, String windowId,
      String docTypeTargetId, String docTypeId) {
    try {
      String tableName = adColumn.getTable().getDBTableName();
      String docNo = Utility.getDocumentNo(conn, vars, windowId, tableName,
          docTypeTargetId, docTypeId, false, false);
      if (docNo != null && !docNo.isEmpty()) {
        return "<" + docNo + ">";
      }
      return null;
    } catch (Exception e) {
      log.debug("Could not generate sequence preview for {}: {}",
          adColumn.getDBColumnName(), e.getMessage());
      return null;
    }
  }

  /**
   * Extract resolved C_DocTypeTarget_ID and C_DocType_ID values from the defaults built in pass 1.
   * Returns a two-element array: [docTypeTargetId, docTypeId], either may be empty string.
   */
  private static String[] resolveDocTypeIdsFromDefaults(JSONObject defaults, Entity dalEntity) {
    String docTypeTargetId = "";
    String docTypeId = "";
    if (dalEntity != null) {
      try {
        Property p = dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID");
        if (p != null) {
          docTypeTargetId = defaults.optString(p.getName(), "");
        }
      } catch (Exception ignored) {
      }
      try {
        Property p = dalEntity.getPropertyByColumnName("C_DocType_ID");
        if (p != null) {
          String candidate = defaults.optString(p.getName(), "");
          // Skip the legacy "0" placeholder — it is not a real doctype ID
          if (!"0".equals(candidate)) {
            docTypeId = candidate;
          }
        }
      } catch (Exception ignored) {
      }
    }
    return new String[]{ docTypeTargetId, docTypeId };
  }

  /**
   * Generate a preview of the next sequence value without consuming it.
   * Uses Utility.getDocumentNo with updateNext=false for a real preview.
   * Returns the value wrapped in angle brackets (e.g., "<1000234>").
   *
   * @deprecated Use {@link #resolveSequencePreviewWithDocType} from resolveDefaults pass 2.
   *   This method passes empty doctype strings and is only kept for callers outside the
   *   two-pass defaults flow (e.g., injectMandatoryDefaults).
   */
  private static String resolveSequencePreview(Column adColumn, VariablesSecureApp vars,
      DalConnectionProvider conn, String windowId, NeoContext ctx) {
    try {
      String tableName = adColumn.getTable().getDBTableName();
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
  static VariablesSecureApp buildVariablesSecureApp(OBContext obContext) {
    String userId = obContext.getUser().getId();
    String clientId = obContext.getCurrentClient().getId();
    String orgId = obContext.getCurrentOrganization().getId();
    String roleId = obContext.getRole().getId();
    String lang = obContext.getLanguage().getLanguage();
    String warehouseId = obContext.getWarehouse() != null
        ? obContext.getWarehouse().getId() : "";

    // When the session org is "*" (id=0), mandatory defaults like AD_Org_ID would resolve
    // to "0" which OBDal rejects for business documents (C_Order, M_InOut, etc.).
    // A role with "*" access has implicit access to all orgs, so we safely pick the
    // first real org of the client to produce valid defaults.
    if ("0".equals(orgId)) {
      String realOrgId = resolveFirstOrgForClient(clientId);
      if (realOrgId != null) {
        orgId = realOrgId;
        log.debug("Context org is '*' (0); resolved first real org {} for defaults", realOrgId);
      }
    }

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

      VariablesSecureApp vars = buildVariablesSecureApp(ctx.getObContext());
      DalConnectionProvider conn = new DalConnectionProvider(false);
      String windowId = ctx.getSfEntity() != null ? resolveWindowId(ctx.getSfEntity()) : "";
      Map<String, Object> parentValues = loadParentValues(adTab, parentId);

      for (Column col : adTab.getTable().getADColumnList()) {
        injectColumnDefaultIfMissing(body, col, dalEntity, parentId, parentValues,
            vars, conn, windowId, ctx);
      }
    } catch (Exception e) {
      log.error("Error injecting mandatory defaults for tab {}: {}",
          adTab.getName(), e.getMessage(), e);
    }
  }

  /**
   * Inject the mandatory default for a single column into the body if it is missing.
   * Skips inactive, non-mandatory, unresolvable, or already-present fields.
   * Also skips Etendo audit/traceable columns (Created, Updated, CreatedBy, UpdatedBy)
   * because they are auto-managed by OBInterceptor on every save. Injecting them with
   * wrong timestamp formats causes OBException in JsonToDataConverter.setData() during
   * the optimistic-locking check for new records.
   */
  private static void injectColumnDefaultIfMissing(JSONObject body, Column col,
      Entity dalEntity, String parentId, Map<String, Object> parentValues,
      VariablesSecureApp vars, DalConnectionProvider conn,
      String windowId, NeoContext ctx) {
    if (!col.isActive() || !col.isMandatory()) {
      return;
    }
    // Skip audit/traceable columns — auto-managed by OBInterceptor on every save.
    // Injecting Created/Updated timestamps (resolved as date-only strings from @#Date@)
    // breaks the optimistic-locking check in JsonToDataConverter.setData() which expects
    // a full ISO 8601 datetime. CreatedBy/UpdatedBy FKs are also handled by OBInterceptor.
    String colNameUpper = col.getDBColumnName().toUpperCase();
    if ("CREATED".equals(colNameUpper) || "UPDATED".equals(colNameUpper)
        || "CREATEDBY".equals(colNameUpper) || "UPDATEDBY".equals(colNameUpper)) {
      return;
    }
    Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
    if (prop == null || body.has(prop.getName())) {
      return;
    }
    try {
      Object resolved = resolveFieldDefault(col, parentId, vars, conn, windowId, ctx);
      if (resolved == null) {
        resolved = resolveFromParentValues(col, parentValues);
      }
      // Fallback for mandatory doctype columns with no resolved default
      if (resolved == null) {
        String docTypeId = resolveDefaultDocTypeId(col, ctx);
        if (docTypeId != null) {
          resolved = docTypeId;
        }
      }
      // Last-resort fallback for any mandatory FK still unresolved — find a valid record
      if (resolved == null && col.getDBColumnName().toUpperCase().endsWith("_ID")) {
        String fallbackId = resolveFallbackFkDefault(col, ctx);
        if (fallbackId != null) {
          resolved = fallbackId;
        }
      }
      applyResolvedDefault(body, col, prop.getName(), resolved, ctx);
    } catch (Exception e) {
      log.debug("Could not resolve mandatory default for {}: {}",
          col.getDBColumnName(), e.getMessage());
    }
  }

  /**
   * If the column's default expression is a single @FieldName@ token, look it up
   * in the parent record values map. Returns null if not found.
   */
  private static Object resolveFromParentValues(Column col, Map<String, Object> parentValues) {
    if (parentValues.isEmpty()) {
      return null;
    }
    String defaultExpr = col.getDefaultValue();
    if (defaultExpr == null || !defaultExpr.matches("^@[A-Za-z_]+@$")) {
      return null;
    }
    String refCol = defaultExpr.substring(1, defaultExpr.length() - 1).toUpperCase();
    Object value = parentValues.get(refCol);
    if (value != null) {
      log.debug("Resolved @{}@ from parent record: {}", refCol, value);
    }
    return value;
  }

  /**
   * Put the resolved default into the body, unless it is a legacy FK "0" default.
   * For FK columns referencing C_DocType with "0" default, attempts to resolve the
   * actual default document type from the database.
   */
  private static void applyResolvedDefault(JSONObject body, Column col,
      String propName, Object resolved, NeoContext ctx) throws Exception {
    if (resolved == null) {
      return;
    }
    // FK columns with legacy "0" default — OBDal cannot resolve "0" as an entity ID.
    // For doctype columns, try to resolve the actual default from C_DocType table.
    if ("0".equals(String.valueOf(resolved))
        && col.getDBColumnName().toUpperCase().endsWith("_ID")) {
      String docTypeId = resolveDefaultDocTypeId(col, ctx);
      if (docTypeId != null) {
        body.put(propName, docTypeId);
        log.debug("Resolved doctype default for {}: {}", propName, docTypeId);
        return;
      }
      log.debug("Skipping FK default '0' for {}", propName);
      return;
    }
    // Coerce numeric String defaults to their proper Java type so DAL validation passes.
    // SQL defaults (e.g. lineNo from COALESCE(MAX(Line),0)+10) arrive as String from rs.getString().
    // Non-FK numeric columns must be Long or BigDecimal — never String — when handed to the DAL.
    Object valueToStore = resolved;
    if (resolved instanceof String && !col.getDBColumnName().toUpperCase().endsWith("_ID")) {
      String strVal = ((String) resolved).trim();
      try {
        valueToStore = new java.math.BigDecimal(strVal).longValueExact();
      } catch (ArithmeticException ae) {
        // Has fractional part — store as BigDecimal
        try { valueToStore = new java.math.BigDecimal(strVal); } catch (Exception ignored) { }
      } catch (Exception ignored) {
        // Not numeric — keep as String (e.g. status flags, doc numbers)
      }
    }
    body.put(propName, valueToStore);
    log.warn("[NEO-DEFAULTS] {} = {} ({})", propName, valueToStore,
        valueToStore == null ? "null" : valueToStore.getClass().getSimpleName());
  }

  /**
   * Last-resort fallback: resolve a mandatory FK column by querying the referenced table
   * for the first active record matching the current client. Prefers records marked as
   * default (isDefault='Y') when such a column exists in the target table.
   *
   * @param col the mandatory FK column with no resolved default
   * @return the record ID, or null if no fallback could be found
   */
  private static String resolveFallbackFkDefault(Column col, NeoContext ctx) {
    try {
      // Determine the referenced table from the column's reference
      org.openbravo.model.ad.datamodel.Table refTable = null;

      // For TableDir references, the table name is derived from the column name
      // (e.g., C_PaymentTerm_ID → C_PaymentTerm)
      String dbColName = col.getDBColumnName();
      if (dbColName.toUpperCase().endsWith("_ID")) {
        String tableName = dbColName.substring(0, dbColName.length() - 3);
        OBCriteria<org.openbravo.model.ad.datamodel.Table> tblCrit =
            OBDal.getInstance().createCriteria(org.openbravo.model.ad.datamodel.Table.class);
        tblCrit.add(Restrictions.eq("dBTableName", tableName));
        tblCrit.setMaxResults(1);
        refTable = (org.openbravo.model.ad.datamodel.Table) tblCrit.uniqueResult();
      }

      if (refTable == null) {
        return null;
      }

      OBContext obCtx = OBContext.getOBContext();
      String clientId = obCtx.getCurrentClient().getId();
      String keyColumn = refTable.getDBTableName() + "_ID";

      // Check if the target table has IsDefault and Name columns
      boolean hasIsDefault = false;
      boolean hasName = false;
      for (Column c : refTable.getADColumnList()) {
        String cn = c.getDBColumnName();
        if ("IsDefault".equalsIgnoreCase(cn)) hasIsDefault = true;
        if ("Name".equalsIgnoreCase(cn)) hasName = true;
      }

      StringBuilder sql = new StringBuilder();
      sql.append("SELECT t.").append(keyColumn);
      sql.append(" FROM ").append(refTable.getDBTableName()).append(" t");
      sql.append(" WHERE t.IsActive = 'Y'");
      sql.append(" AND t.AD_Client_ID = ?");
      if ("M_PriceList".equalsIgnoreCase(refTable.getDBTableName())
          && ctx != null && ctx.getAdTab() != null) {
        boolean isSO = Boolean.TRUE.equals(ctx.getAdTab().getWindow().isSalesTransaction());
        sql.append(" AND t.issopricelist = '").append(isSO ? "Y" : "N").append("'");
      }
      sql.append(" ORDER BY ");
      if (hasIsDefault) {
        sql.append("t.IsDefault DESC, ");
      }
      sql.append(hasName ? "t.Name ASC" : "t." + keyColumn + " ASC");

      try (PreparedStatement ps = OBDal.getInstance().getConnection(false)
          .prepareStatement(sql.toString())) {
        ps.setMaxRows(1);
        ps.setString(1, clientId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String id = rs.getString(1);
            log.warn("Fallback FK default for {}: {} (table={}) — no explicit default configured",
                dbColName, id, refTable.getDBTableName());
            return id;
          }
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve fallback FK default for {}: {}",
          col.getDBColumnName(), e.getMessage());
    }
    return null;
  }

  /**
   * Re-apply the correct doctype after the callout cascade.
   * Callouts like SE_Order_Organization set transactionDocument to the org's default doctype,
   * which may not match the tab's HQL filter (e.g., Standard Order instead of Quotation).
   * This method resolves the correct doctype based on the tab filter and overwrites both
   * documentType and transactionDocument in the body.
   *
   * @param body  the JSON request body to update with the correct doctype values
   * @param adTab the AD_Tab containing the table with doctype columns
   * @param ctx   the NeoContext with spec/entity info for doctype resolution
   */
  public static void reapplyDocTypeFromTabFilter(JSONObject body, Tab adTab, NeoContext ctx) {
    if (body == null || adTab == null || ctx == null) return;
    try {
      Column docTypeTargetCol = findDocTypeTargetColumn(adTab);
      if (docTypeTargetCol == null) {
        return;
      }
      String correctId = resolveDefaultDocTypeId(docTypeTargetCol, ctx);
      if (correctId != null) {
        applyDocTypeToBody(body, adTab, correctId);
      }
    } catch (Exception e) {
      log.debug("Error reapplying doctype: {}", e.getMessage());
    }
  }

  /**
   * Find the C_DocTypeTarget_ID column in the tab's table.
   */
  private static Column findDocTypeTargetColumn(Tab adTab) {
    for (Column col : adTab.getTable().getADColumnList()) {
      if ("C_DocTypeTarget_ID".equalsIgnoreCase(col.getDBColumnName())) {
        return col;
      }
    }
    return null;
  }

  /**
   * Apply the resolved doctype ID to both transactionDocument and documentType properties.
   */
  private static void applyDocTypeToBody(JSONObject body, Tab adTab, String docTypeId)
      throws Exception {
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(adTab.getTable().getId());
    if (dalEntity == null) {
      return;
    }
    Property targetProp = dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID");
    Property typeProp = dalEntity.getPropertyByColumnName("C_DocType_ID");
    if (targetProp != null) {
      body.put(targetProp.getName(), docTypeId);
      log.debug("Reapplied transactionDocument={}", docTypeId);
    }
    if (typeProp != null) {
      body.put(typeProp.getName(), docTypeId);
      log.debug("Reapplied documentType={}", docTypeId);
    }
  }

  /**
   * Remove empty-string values from the body for mandatory FK columns.
   * Callouts may set FK fields to "" when they cannot resolve a value (e.g., BP without
   * sales payment term). Removing these allows injectMandatoryDefaults to attempt
   * a fallback resolution on the next pass.
   *
   * @param body  the JSON request body from which empty FK values will be removed
   * @param adTab the AD_Tab containing the table with FK column definitions
   */
  public static void removeEmptyFkValues(JSONObject body, Tab adTab) {
    if (body == null || adTab == null || adTab.getTable() == null) {
      return;
    }
    try {
      Entity dalEntity = ModelProvider.getInstance()
          .getEntityByTableId(adTab.getTable().getId());
      if (dalEntity == null) {
        return;
      }
      for (Column col : adTab.getTable().getADColumnList()) {
        removeEmptyFkValueForColumn(body, col, dalEntity);
      }
    } catch (Exception e) {
      log.debug("Error removing empty FK values: {}", e.getMessage());
    }
  }

  /**
   * Remove the empty FK value for a single column if applicable.
   */
  private static void removeEmptyFkValueForColumn(JSONObject body, Column col, Entity dalEntity) {
    if (!col.isActive() || !col.isMandatory()) {
      return;
    }
    String dbColName = col.getDBColumnName();
    if (!dbColName.toUpperCase().endsWith("_ID")) {
      return;
    }
    Property prop = dalEntity.getPropertyByColumnName(dbColName);
    if (prop == null || !body.has(prop.getName())) {
      return;
    }
    Object val = body.opt(prop.getName());
    if (val instanceof String && ((String) val).trim().isEmpty()) {
      body.remove(prop.getName());
      log.debug("Removed empty FK value for mandatory field: {}", prop.getName());
    }
  }

  /**
   * Resolve a default C_DocType ID for a column that references the C_DocType table.
   * Returns null if the column does not reference C_DocType or no suitable default is found.
   *
   * <p>The resolution strategy:
   * <ol>
   *   <li>Check if the column references C_DocType (by column name convention)</li>
   *   <li>Determine the transaction type (sales vs. purchase) from the IsSOTrx column
   *       default in the same table</li>
   *   <li>Determine the document base type from the table name and transaction type</li>
   *   <li>Query for the default doctype matching client, isSOTrx, and docbasetype</li>
   * </ol>
   *
   * @param col the AD_Column to resolve a doctype default for
   * @return the C_DocType_ID string, or null if not applicable or not found
   */
  private static String resolveDefaultDocTypeId(Column col, NeoContext ctx) {
    String colName = col.getDBColumnName().toUpperCase();
    // Only handle columns that reference C_DocType (naming convention: *DOCTYPE*_ID)
    if (!colName.endsWith("_ID") || !colName.contains("DOCTYPE")) {
      return null;
    }

    try {
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String isSOTrx = resolveIsSOTrxDefault(col.getTable(), ctx);
      String docBaseType = resolveDocBaseType(col.getTable().getDBTableName(), isSOTrx);
      if (docBaseType == null) {
        log.debug("Could not determine DocBaseType for table {} — skipping doctype resolution",
            col.getTable().getDBTableName());
        return null;
      }

      // Extract DocSubTypeSO constraint from the tab's HQL where clause
      String[] subTypeFilters = parseSubTypeFilters(ctx);

      return queryDefaultDocType(clientId, docBaseType, isSOTrx,
          subTypeFilters[0], subTypeFilters[1], colName);

    } catch (Exception e) {
      log.debug("Could not resolve default doctype for {}: {}", colName, e.getMessage());
    }
    return null;
  }

  /**
   * Parse DocSubTypeSO filter and exclude values from the tab's HQL where clause.
   * Returns a two-element array: [subTypeFilter, subTypeExclude], either may be null.
   */
  private static String[] parseSubTypeFilters(NeoContext ctx) {
    String subTypeFilter = null;
    String subTypeExclude = null;
    if (ctx != null && ctx.getSfEntity() != null && ctx.getSfEntity().getADTab() != null) {
      String tabWhere = ctx.getSfEntity().getADTab().getHqlwhereclause();
      if (tabWhere != null) {
        java.util.regex.Matcher m = SUBTYPE_NOT_LIKE_PATTERN.matcher(tabWhere);
        if (m.find()) {
          subTypeExclude = m.group(1);
        } else {
          m = SUBTYPE_LIKE_PATTERN.matcher(tabWhere);
          if (m.find()) {
            subTypeFilter = m.group(1);
          }
        }
      }
    }
    return new String[] { subTypeFilter, subTypeExclude };
  }

  /**
   * Query the C_DocType table for the default document type matching the given criteria.
   * Uses PreparedStatement placeholders for all dynamic values to prevent SQL injection.
   */
  private static String queryDefaultDocType(String clientId, String docBaseType,
      String isSOTrx, String subTypeFilter, String subTypeExclude, String colName)
      throws Exception {
    OBContext obCtx = OBContext.getOBContext();
    if (obCtx == null || obCtx.getCurrentOrganization() == null) {
      return null;
    }
    String orgId = obCtx.getCurrentOrganization().getId();

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT dt.C_DocType_ID FROM C_DocType dt ");
    sql.append("WHERE dt.IsActive = 'Y' ");
    sql.append("AND dt.AD_Client_ID = ? ");
    sql.append("AND dt.DocBaseType = ? ");
    sql.append("AND dt.IsSOTrx = ? ");
    // Filter by org: doctype must be in the current org tree or shared (org 0)
    sql.append("AND (dt.AD_Org_ID = '0' OR AD_ISORGINCLUDED(?, dt.AD_Org_ID, ?) <> '-1') ");

    if (subTypeFilter != null) {
      sql.append("AND dt.DocSubTypeSO = ? ");
    } else if (subTypeExclude != null) {
      sql.append("AND (dt.DocSubTypeSO IS NULL OR dt.DocSubTypeSO != ?) ");
    }
    sql.append("ORDER BY dt.IsDefault DESC, dt.Name ASC");

    try (PreparedStatement ps = OBDal.getInstance().getConnection(false)
        .prepareStatement(sql.toString())) {
      int paramIndex = 1;
      ps.setString(paramIndex++, clientId);
      ps.setString(paramIndex++, docBaseType);
      ps.setString(paramIndex++, isSOTrx);
      ps.setString(paramIndex++, orgId);
      ps.setString(paramIndex++, clientId);
      if (subTypeFilter != null) {
        ps.setString(paramIndex++, subTypeFilter);
      } else if (subTypeExclude != null) {
        ps.setString(paramIndex++, subTypeExclude);
      }

      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String docTypeId = rs.getString(1);
          log.debug("Resolved default doctype for {} (DocBaseType={}, IsSOTrx={}): {}",
              colName, docBaseType, isSOTrx, docTypeId);
          return docTypeId;
        }
      }
    }

    log.debug("No matching doctype found for {} (DocBaseType={}, IsSOTrx={})",
        colName, docBaseType, isSOTrx);
    return null;
  }

  /**
   * Determine the IsSOTrx default value from the table's columns.
   * Looks for an IsSOTrx column and returns its default value ('Y' or 'N').
   * Handles context variables like @IsSOTrx@ by resolving via Utility.getDefault.
   * Falls back to 'Y' (sales) if no IsSOTrx column exists or cannot be resolved.
   */
  private static String resolveIsSOTrxDefault(
      org.openbravo.model.ad.datamodel.Table table, NeoContext ctx) {
    for (Column c : table.getADColumnList()) {
      if ("IsSOTrx".equalsIgnoreCase(c.getDBColumnName())) {
        return resolveIsSOTrxFromColumn(c, ctx);
      }
    }
    // Table has no IsSOTrx column — default to 'Y'
    return "Y";
  }

  /**
   * Resolve the IsSOTrx value from the column's default expression.
   * Handles literal values, context variables, and fallback.
   */
  private static String resolveIsSOTrxFromColumn(Column col, NeoContext ctx) {
    String defaultVal = col.getDefaultValue();
    if (defaultVal == null || defaultVal.trim().isEmpty()) {
      return "Y";
    }
    defaultVal = defaultVal.trim();

    // Literal 'Y' or 'N' — return directly
    String literal = parseIsSOTrxLiteral(defaultVal);
    if (literal != null) {
      return literal;
    }

    // Context variable (e.g., @IsSOTrx@) — resolve via Utility.getDefault
    if (defaultVal.contains("@") && ctx != null) {
      String resolved = resolveIsSOTrxFromContext(defaultVal, ctx);
      if (resolved != null) {
        return resolved;
      }
    }

    // Fallback
    return "Y";
  }

  /**
   * Parse a literal IsSOTrx value. Returns "Y" or "N" if recognized, null otherwise.
   */
  private static String parseIsSOTrxLiteral(String value) {
    if ("Y".equals(value) || "'Y'".equals(value)) {
      return "Y";
    }
    if ("N".equals(value) || "'N'".equals(value)) {
      return "N";
    }
    return null;
  }

  /**
   * Resolve an IsSOTrx context variable expression via Utility.getDefault.
   */
  private static String resolveIsSOTrxFromContext(String defaultVal, NeoContext ctx) {
    try {
      VariablesSecureApp vars = buildVariablesSecureApp(ctx.getObContext());
      DalConnectionProvider conn = new DalConnectionProvider(false);
      String windowId = resolveWindowId(ctx.getSfEntity());
      String resolved = Utility.getDefault(conn, vars, "IsSOTrx", defaultVal, windowId, "");
      if ("Y".equals(resolved) || "N".equals(resolved)) {
        log.debug("Resolved IsSOTrx context var '{}' to '{}'", defaultVal, resolved);
        return resolved;
      }
    } catch (Exception e) {
      log.debug("Could not resolve IsSOTrx context var: {}", e.getMessage());
    }
    return null;
  }

  /**
   * Map a table name and transaction type to the corresponding document base type.
   * Covers the standard Etendo transactional tables.
   *
   * @param tableName the DB table name (e.g., "C_Order", "C_Invoice")
   * @param isSOTrx   "Y" for sales, "N" for purchase
   * @return the DocBaseType code (e.g., "SOO", "POO") or null if unknown
   */
  private static String resolveDocBaseType(String tableName, String isSOTrx) {
    boolean isSales = "Y".equals(isSOTrx);
    String upper = tableName.toUpperCase();

    if ("C_ORDER".equals(upper)) {
      return isSales ? "SOO" : "POO";
    }
    if ("C_INVOICE".equals(upper)) {
      return isSales ? "ARI" : "API";
    }
    if ("M_INOUT".equals(upper)) {
      return isSales ? "MMS" : "MMR";
    }
    if ("C_PAYMENT".equals(upper)) {
      return isSales ? "ARR" : "APP";
    }
    if ("M_MOVEMENT".equals(upper)) {
      return "MMM";
    }
    if ("M_INVENTORY".equals(upper)) {
      return "MMI";
    }
    if ("C_BANKSTATEMENT".equals(upper)) {
      return "CMB";
    }

    // Unknown table — cannot determine DocBaseType
    return null;
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
  public static CalloutCascadeResult executeCalloutCascade(NeoContext ctx, Tab adTab,
      JSONObject defaults, Set<String> seqFields) {

    CalloutCascadeResult result = new CalloutCascadeResult();

    try {
      List<String> fieldsWithCallouts = collectFieldsWithCallouts(defaults, seqFields, adTab);
      if (fieldsWithCallouts.isEmpty()) {
        return result;
      }

      log.info("[NEO-DEFAULTS] Callout cascade: {} fields have callouts: {}",
          fieldsWithCallouts.size(), fieldsWithCallouts);

      JSONObject formState = new JSONObject(defaults.toString());
      Set<String> pendingFields = new LinkedHashSet<>(fieldsWithCallouts);
      int depth = 0;

      while (!pendingFields.isEmpty() && depth < MAX_CALLOUT_CHAIN_DEPTH) {
        depth++;
        pendingFields = executeCascadeIteration(
            pendingFields, ctx, adTab, formState, defaults, seqFields, result);
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
   * Cascade callouts starting from the result of an interactive field-change callout.
   *
   * When a callout (e.g., SL_Order_Product) returns fields that themselves have callouts
   * (e.g., tax → SL_Order_Amt), this method chains those secondary callouts so the
   * frontend receives a fully-resolved result in a single request.
   *
   * Typical case: gross price lists (istaxincluded=Y).
   *   SL_Order_Product sets tax + grossUnitPrice (not unitPrice).
   *   SL_Order_Amt is then cascaded for tax → derives unitPrice from grossUnitPrice - taxes.
   *
   * @param ctx              the NeoContext (spec/entity/tab)
   * @param adTab            the AD_Tab for callout resolution
   * @param triggerField     the original field that was changed by the user (excluded from cascade)
   * @param originalFormState the form state that was sent with the original callout request
   * @param calloutResponse  the REST response from the initial callout (with "updates" / "combos")
   * @return aggregated cascade results (to be merged into the original callout response)
   */
  public static CalloutCascadeResult cascadeInteractiveCallout(
      NeoContext ctx, Tab adTab, String triggerField,
      JSONObject originalFormState, JSONObject calloutResponse) {

    CalloutCascadeResult result = new CalloutCascadeResult();
    if (ctx == null || adTab == null || calloutResponse == null) {
      return result;
    }

    try {
      // Build a working formState: original fields + values set by the initial callout
      JSONObject cascadeFormState = new JSONObject(
          originalFormState != null ? originalFormState.toString() : "{}");

      // Collect fields returned by the initial callout that have further callouts
      Set<String> skipFields = new HashSet<>();
      skipFields.add(triggerField);

      Set<String> pendingFields = collectCalloutPendingFields(
          calloutResponse, cascadeFormState, skipFields, adTab);

      if (pendingFields.isEmpty()) {
        return result;
      }

      log.debug("[NEO-CALLOUT] Interactive cascade: {} field(s) queued after '{}': {}",
          pendingFields.size(), triggerField, pendingFields);

      int depth = 0;
      while (!pendingFields.isEmpty() && depth < MAX_CALLOUT_CHAIN_DEPTH) {
        depth++;
        pendingFields = executeCascadeIteration(
            pendingFields, ctx, adTab, cascadeFormState, cascadeFormState, skipFields, result);
      }

    } catch (Exception e) {
      log.warn("[NEO-CALLOUT] Interactive cascade failed for trigger '{}': {}",
          triggerField, e.getMessage(), e);
    }

    return result;
  }

  /**
   * Collect fields from a callout response that have further callouts, updating cascadeFormState.
   */
  private static Set<String> collectCalloutPendingFields(JSONObject calloutResponse,
      JSONObject cascadeFormState, Set<String> skipFields, Tab adTab)
      throws org.codehaus.jettison.json.JSONException {
    Set<String> pendingFields = new LinkedHashSet<>();
    JSONObject updates = calloutResponse.optJSONObject(KEY_UPDATES);
    if (updates == null) {
      return pendingFields;
    }
    @SuppressWarnings("unchecked")
    Iterator<String> keys = updates.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      JSONObject entry = updates.optJSONObject(key);
      if (entry == null) {
        continue;
      }
      Object val = entry.opt(VALUE_KEY);
      if (val == null || JSONObject.NULL.equals(val) || "".equals(String.valueOf(val))) {
        continue;
      }
      cascadeFormState.put(key, val);
      if (!skipFields.contains(key) && NeoCalloutService.resolveCallout(adTab, key) != null) {
        pendingFields.add(key);
      }
    }
    return pendingFields;
  }

  /**
   * Collect field names that have non-null defaults and configured callouts.
   */
  private static List<String> collectFieldsWithCallouts(JSONObject defaults,
      Set<String> seqFields, Tab adTab) {
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
    return fieldsWithCallouts;
  }

  /**
   * Execute one iteration of the callout cascade, processing all pending fields.
   * Returns the set of fields that need processing in the next iteration.
   */
  private static Set<String> executeCascadeIteration(Set<String> pendingFields,
      NeoContext ctx, Tab adTab, JSONObject formState, JSONObject defaults,
      Set<String> seqFields, CalloutCascadeResult result) {
    Set<String> nextPending = new LinkedHashSet<>();

    for (String fieldName : pendingFields) {
      executeSingleCallout(fieldName, ctx, adTab, formState, defaults,
          seqFields, result, nextPending);
    }

    return nextPending;
  }

  /**
   * Execute a single callout for one field and merge results into the cascade state.
   */
  private static void executeSingleCallout(String fieldName, NeoContext ctx, Tab adTab,
      JSONObject formState, JSONObject defaults, Set<String> seqFields,
      CalloutCascadeResult result, Set<String> nextPending) {
    Object value = formState.opt(fieldName);
    if (value == null || JSONObject.NULL.equals(value)) {
      return;
    }

    try {
      JSONObject calloutRequest = new JSONObject();
      calloutRequest.put("field", fieldName);
      calloutRequest.put(VALUE_KEY, value);
      calloutRequest.put("formState", formState);

      NeoResponse calloutResponse = NeoCalloutService.executeCallout(ctx, calloutRequest);
      if (calloutResponse == null || calloutResponse.getHttpStatus() != 200) {
        log.debug("[NEO-DEFAULTS] Callout for '{}' failed or returned non-200", fieldName);
        return;
      }

      JSONObject calloutBody = calloutResponse.getBody();
      if (calloutBody == null) {
        return;
      }

      mergeCalloutResults(calloutBody, formState, defaults, seqFields, adTab, result, nextPending);

    } catch (Exception e) {
      log.warn("[NEO-DEFAULTS] Callout cascade error for field '{}': {}",
          fieldName, e.getMessage());
    }
  }

  /**
   * Merge updates, combos, and messages from a callout response into the cascade state.
   */
  private static void mergeCalloutResults(JSONObject calloutBody, JSONObject formState,
      JSONObject defaults, Set<String> seqFields, Tab adTab,
      CalloutCascadeResult result, Set<String> nextPending) throws Exception {
    JSONObject updates = calloutBody.optJSONObject("updates");
    if (updates != null) {
      result.mergeUpdates(updates);
      mergeUpdatesIntoFormState(updates, formState, defaults, seqFields, adTab, nextPending);
    }

    JSONObject combos = calloutBody.optJSONObject("combos");
    if (combos != null) {
      result.mergeCombos(combos);
    }

    JSONArray messages = calloutBody.optJSONArray("messages");
    if (messages != null) {
      result.mergeMessages(messages);
    }
  }

  /**
   * Merge field updates into the form state and defaults, queuing changed fields
   * that have callouts for the next cascade iteration.
   */
  private static void mergeUpdatesIntoFormState(JSONObject updates, JSONObject formState,
      JSONObject defaults, Set<String> seqFields, Tab adTab,
      Set<String> nextPending) throws Exception {
    Iterator<String> updateKeys = updates.keys();
    while (updateKeys.hasNext()) {
      String updatedField = updateKeys.next();
      JSONObject updateObj = updates.optJSONObject(updatedField);
      if (updateObj == null || !updateObj.has(VALUE_KEY)) {
        continue;
      }
      Object newValue = updateObj.get(VALUE_KEY);
      Object oldValue = formState.opt(updatedField);

      // Never let a callout overwrite a sequence/DocumentNo field — the bracket-wrapped
      // preview value must survive intact so DefaultJsonDataService can consume the real
      // sequence on save (or pass 2 of resolveDefaults can set the correct preview).
      if (seqFields.contains(updatedField)) {
        continue;
      }

      formState.put(updatedField, newValue);
      defaults.put(updatedField, newValue);

      if (!valueChanged(oldValue, newValue)) {
        continue;
      }
      NeoCalloutService.CalloutInfo nextInfo =
          NeoCalloutService.resolveCallout(adTab, updatedField);
      if (nextInfo != null) {
        nextPending.add(updatedField);
      }
    }
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
  public static class CalloutCascadeResult {
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

  private static Map<String, Object> loadParentValues(Tab adTab, String parentId) {
    Map<String, Object> parentValues = new java.util.HashMap<>();
    if (parentId != null && !parentId.isEmpty() && adTab.getTabLevel() > 0) {
      try {
        Tab parentTab = adTab.getWindow().getADTabList().stream()
            .filter(t -> t.getTabLevel() == adTab.getTabLevel() - 1 && t.isActive())
            .findFirst().orElse(null);
        if (parentTab != null) {
          Entity parentEntity = ModelProvider.getInstance()
              .getEntityByTableId(parentTab.getTable().getId());
          if (parentEntity != null) {
            BaseOBObject parentRecord = OBDal.getInstance().get(parentEntity.getName(), parentId);
            if (parentRecord != null) {
              for (Property p : parentEntity.getProperties()) {
                Object val = parentRecord.get(p.getName());
                if (val != null) {
                  // Store by DB column name (uppercase) for matching @ColumnName@ expressions
                  String colName = p.getColumnName();
                  if (colName != null) {
                    parentValues.put(colName.toUpperCase(), val instanceof BaseOBObject
                        ? ((BaseOBObject) val).getId().toString() : val);
                  }
                }
              }
              log.debug("Loaded {} parent values from {} for child defaults",
                  parentValues.size(), parentEntity.getName());
            }
          }
        }
      } catch (Exception e) {
        log.debug("Could not load parent record for defaults: {}", e.getMessage());
      }
    }
    return parentValues;
  }

  /**
   * Returns the ID of the first active non-system organization for the given client.
   * Used when the session context org is "0" (the "*" all-orgs pseudo-org) so that
   * mandatory FK defaults like AD_Org_ID resolve to a real org rather than "0",
   * which OBDal rejects for business documents.
   *
   * A role with access to "*" has implicit access to all orgs, so using any active
   * org of the client is safe.
   *
   * @param clientId the AD_Client_ID of the current session
   * @return the first org ID ordered by name, or null if none found
   */
  public static String resolveFirstOrgForClient(String clientId) {
    try {
      String sql = "SELECT AD_Org_ID FROM AD_Org"
          + " WHERE AD_Client_ID = ? AND IsActive = 'Y' AND AD_Org_ID != '0'"
          + " ORDER BY Name LIMIT 1";
      try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
        ps.setString(1, clientId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return rs.getString(1);
          }
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve first org for client {}: {}", clientId, e.getMessage());
    }
    return null;
  }

}
