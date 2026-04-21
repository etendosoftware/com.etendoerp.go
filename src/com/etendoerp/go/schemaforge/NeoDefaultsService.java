package com.etendoerp.go.schemaforge;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
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
  private static final String KEY_UPDATES = "updates";
  private static final String KEY_COMBOS = "combos";

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

        // Build a VariablesSecureApp bridge from OBContext for Etendo utility methods.
        // Pass the AD_Tab so isSOTrx is set in session — @IsSOTrx@ inside @SQL= defaults
        // (e.g. M_PriceList_ID on C_Order) needs it to pick the correct sales/purchase row.
        VariablesSecureApp vars = buildVariablesSecureApp(ctx.getObContext(), ctx.getAdTab());
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
        Entity dalEntity = NeoDefaultsCascadeHelper.resolveDalEntity(ctx.getSfEntity());

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
          String propertyName = NeoDefaultsCascadeHelper.resolvePropertyName(dalEntity, dbColumnName);

          try {
            // ETGO_SF_FIELD.defaultvalue overrides the AD_Column default when set.
            // This allows per-window default expressions (e.g. "@#Date@" for date fields)
            // without modifying the AD_Column metadata.
            String sfFieldDefault = sfField.getDefaultValue();
            Object resolvedValue = resolveFieldDefault(adColumn, parentId, vars, conn, windowId,
                ctx, sfFieldDefault);
            // FIC parity: when no explicit default resolves, combo-style references (TableDir,
            // Table, List) preselect row [0] of their lookup — exactly what
            // UIDefinition.getValueInComboReference does in Etendo Classic. Search/Selector/Tree
            // references are intentionally left empty here (FIC leaves them empty too).
            if (resolvedValue == null) {
              resolvedValue = resolveFirstComboOption(adColumn, ctx);
            }
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
          String propertyName = NeoDefaultsCascadeHelper.resolvePropertyName(dalEntity, dbColumnName);

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

        // Keep cascade enabled for /defaults to preserve the compatibility behavior chosen
        // for this merge: dependent defaults should still be derived during form bootstrap.
        Tab adTab = ctx.getAdTab();
        Set<String> seqFieldSet = new HashSet<>();
        for (int i = 0; i < sequenceFields.length(); i++) {
          seqFieldSet.add(sequenceFields.getString(i));
        }
        if (adTab != null) {
          NeoDefaultsCascadeHelper.executeCalloutCascade(ctx, adTab, defaults, seqFieldSet);
        }

        // Re-apply C_DocTypeTarget_ID from the tab's HQL subtype filter (e.g. sOSubType LIKE 'OB'
        // for Quotation tabs) before the generic FK fallback runs. Without this, the fallback picks
        // the first alphabetically available doctype (Standard Order) instead of the correct one.
        // Mirrors NeoCrudHandler.executePostCalloutCascade on the create path.
        if (adTab != null) {
          DocTypeResolver.reapplyDocTypeFromTabFilter(defaults, adTab, ctx);
        }

        // FK preselection is applied per-column inside pass 1 via resolveFirstComboOption,
        // scoped to FIC combo references (TableDir, Table, List) — mirroring
        // UIDefinition.getValueInComboReference. Search/Selector/Tree references are left empty
        // so the form/UI (or the BP/DocType callout chain) can surface them for user input,
        // matching Etendo Classic FIC behavior on MODE=NEW.
        // The CREATE path keeps its own broader fallback in injectMandatoryDefaults to avoid
        // NOT NULL violations when partial payloads reach persistence.

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
    return resolveFieldDefault(adColumn, parentId, vars, conn, windowId, ctx, null);
  }

  /**
   * Resolve the default value for a single AD_Column, with an optional per-field override
   * expression from ETGO_SF_FIELD.defaultvalue. When {@code sfFieldDefault} is non-blank it
   * takes precedence over the AD_Column default, allowing per-window default customisation
   * (e.g. "@#Date@" on a date field) without touching AD_Column metadata.
   *
   * @param sfFieldDefault override default expression from ETGO_SF_FIELD (may be null)
   * @return the resolved value, or null if no default is configured
   */
  private static Object resolveFieldDefault(Column adColumn, String parentId,
      VariablesSecureApp vars, DalConnectionProvider conn, String windowId, NeoContext ctx,
      String sfFieldDefault) {

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

    // ETGO_SF_FIELD.defaultvalue overrides AD_Column.defaultvalue when set
    String defaultExpr = (sfFieldDefault != null && !sfFieldDefault.trim().isEmpty())
        ? sfFieldDefault.trim()
        : adColumn.getDefaultValue();
    if (defaultExpr == null || defaultExpr.trim().isEmpty()) {
      return resolveFromPrefsOrDocType(adColumn, vars, conn, windowId, dbColumnName, ctx);
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
      return DocTypeResolver.resolveDefaultDocTypeId(adColumn, ctx);
    }
    String fromPrefs = Utility.getPreference(vars, dbColumnName, windowId != null ? windowId : "");
    if (fromPrefs != null && !fromPrefs.isEmpty()) {
      return fromPrefs;
    }
    String docTypeId = DocTypeResolver.resolveDefaultDocTypeId(adColumn, ctx);
    if (docTypeId != null) {
      return docTypeId;
    }
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
            if (colDefault.startsWith("'")) {
              int endQuote = colDefault.indexOf("'", 1);
              if (endQuote > 0) {
                return colDefault.substring(1, endQuote);
              }
            }
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
  @Deprecated
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
   * Delegates to {@link NeoCalloutService#buildVars} and adds caching + #Date.
   *
   * @param obContext the current OBContext containing user, role, org, and warehouse info
   * @return a cached or newly built VariablesSecureApp instance with session variables populated
   */
  public static VariablesSecureApp buildVariablesSecureApp(OBContext obContext) {
    return buildVariablesSecureApp(obContext, null);
  }

  /**
   * Build a VariablesSecureApp from OBContext and an optional AD_Tab, so that the window's
   * IsSOTrx flag is exposed as a session variable for default resolution. Without this,
   * expressions like {@code @IsSOTrx@} inside {@code @SQL=...} defaults (e.g. the
   * {@code M_PriceList_ID} default on {@code C_Order}) resolve to an empty string and pick
   * a purchase pricelist on a sales window.
   *
   * <p>Delegates the session-variable population (including {@code IsSOTrx}) to the shared
   * {@link NeoCalloutService#buildVars(OBContext, Tab)} builder, and layers caching + the
   * {@code #Date} variable on top. The cache key includes the resolved {@code isSOTrx} value
   * so a sales-window entry is not served to a purchase-window caller within the TTL.
   *
   * @param obContext the current OBContext containing user, role, org, and warehouse info
   * @param adTab     the AD_Tab whose window provides the IsSOTrx flag. Pass {@code null}
   *                  when not in a window context (processes, standalone defaults).
   * @return a cached or newly built VariablesSecureApp instance with session variables populated
   */
  public static VariablesSecureApp buildVariablesSecureApp(OBContext obContext, Tab adTab) {
    String userId = obContext.getUser().getId();
    String clientId = obContext.getCurrentClient().getId();
    String roleId = obContext.getRole().getId();
    String orgId = obContext.getCurrentOrganization().getId();
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

    String soTrx = "";
    if (adTab != null && adTab.getWindow() != null
        && adTab.getWindow().isSalesTransaction() != null) {
      soTrx = Boolean.TRUE.equals(adTab.getWindow().isSalesTransaction()) ? "Y" : "N";
    }

    String cacheKey = userId + "|" + roleId + "|" + orgId + "|" + warehouseId + "|" + soTrx;
    VariablesSecureApp cached = varsCache.getIfPresent(cacheKey);
    if (cached != null) {
      return cached;
    }

    // Shared builder handles OBContext session vars + IsSOTrx (both casings) when adTab is set.
    VariablesSecureApp vars = NeoCalloutService.buildVars(obContext, adTab);
    vars.setSessionValue("#Date",
        new SimpleDateFormat(DATE_FORMAT).format(new Date()));

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
      VariablesSecureApp vars = buildVariablesSecureApp(ctx.getObContext(), adTab);
      DalConnectionProvider conn = new DalConnectionProvider(false);
      String windowId = ctx.getSfEntity() != null ? resolveWindowId(ctx.getSfEntity()) : "";
      Map<String, Object> parentValues = loadParentValues(adTab, parentId);
      MandatoryDefaultContext mCtx = new MandatoryDefaultContext(parentId, vars, conn,
          windowId, ctx, parentValues);

      for (Column col : adTab.getTable().getADColumnList()) {
        if (!col.isActive() || !col.isMandatory()) {
          continue;
        }
        // Skip primary key columns — DAL auto-generates UUID PKs.
        // Injecting any value here (including an existing record's ID via FK fallback)
        // would cause DefaultJsonDataService to try to UPDATE instead of INSERT.
        if (Boolean.TRUE.equals(col.isKeyColumn())) {
          continue;
        }
        // Skip audit columns (updated, created, updatedBy, createdBy) — Hibernate manages
        // these automatically via event listeners. Injecting them causes JsonToDataConverter
        // to run a stale-date comparison that fails with a date-format parse error.
        org.openbravo.base.model.Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
        if (prop != null && prop.isAuditInfo()) {
          continue;
        }
        injectMandatoryDefaultForColumn(body, dalEntity, col, mCtx);
      }

      // Fallback 3: run callout cascade with all fields in body.
      // Callouts configured in AD_Column derive dependent fields (e.g. BP → address,
      // price list, payment terms) without hardcoding any field relationships.
      NeoDefaultsCascadeHelper.executeCalloutCascadeForCreate(ctx, adTab, body);

    } catch (Exception e) {
      log.error("Error injecting mandatory defaults for tab {}: {}",
          adTab.getName(), e.getMessage(), e);
    }
  }

  /**
   * Bundles the resolution infrastructure needed for mandatory default injection.
   */
  private static class MandatoryDefaultContext {
    final String parentId;
    final VariablesSecureApp vars;
    final DalConnectionProvider conn;
    final String windowId;
    final NeoContext neoCtx;
    final Map<String, Object> parentValues;

    MandatoryDefaultContext(String parentId, VariablesSecureApp vars,
        DalConnectionProvider conn, String windowId, NeoContext neoCtx,
        Map<String, Object> parentValues) {
      this.parentId = parentId;
      this.vars = vars;
      this.conn = conn;
      this.windowId = windowId;
      this.neoCtx = neoCtx;
      this.parentValues = parentValues != null ? parentValues
          : java.util.Collections.emptyMap();
    }
  }

  /**
   * Attempt to inject a mandatory default value for a single column into the body.
   * Tries field default resolution first, then session context, then safe numeric/boolean fallback.
   */
  private static void injectMandatoryDefaultForColumn(JSONObject body, Entity dalEntity,
      Column col, MandatoryDefaultContext mCtx) {
    try {
      if (isAuditColumn(col)) {
        return;
      }
      Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
      if (prop == null) {
        return;
      }
      String propName = prop.getName();
      // Skip if already present in the body (user or field-filter provided it)
      if (body.has(propName)) {
        return;
      }

      if (tryResolveFieldDefault(body, propName, col, mCtx)) {
        return;
      }
      if (tryInjectFromSession(body, dalEntity, propName, col, mCtx)) {
        return;
      }
      if (tryInjectFromParentValues(body, dalEntity, propName, col, mCtx.parentValues)) {
        return;
      }
      if (tryInjectFallbackFkDefault(body, dalEntity, propName, col, mCtx.neoCtx)) {
        return;
      }
      if (tryInjectFirstFromLookup(body, dalEntity, propName, col, mCtx.neoCtx)) {
        return;
      }
      NeoDefaultsCascadeHelper.injectSafeTypeDefault(body, propName, col);
    } catch (Exception e) {
      log.debug("Could not process mandatory column {}: {}",
          col.getDBColumnName(), e.getMessage());
    }
  }

  /**
   * Try to resolve the field default using the standard resolution logic.
   * Returns true if a value was injected, false otherwise.
   */
  private static boolean tryResolveFieldDefault(JSONObject body, String propName, Column col,
      MandatoryDefaultContext mCtx) {
    try {
      Object resolved = resolveFieldDefault(col, mCtx.parentId, mCtx.vars, mCtx.conn,
          mCtx.windowId, mCtx.neoCtx);
      if (resolved != null) {
        applyResolvedDefault(body, col, propName, resolved, mCtx.neoCtx);
        tryInjectIdentifier(body,
            NeoDefaultsCascadeHelper.resolveDalEntity(mCtx.neoCtx.getSfEntity()),
            propName, body.opt(propName));
        log.debug("Injected mandatory default: {} = {}", propName, body.opt(propName));
        return true;
      }
    } catch (Exception e) {
      log.debug("Could not resolve mandatory default for {}: {}",
          col.getDBColumnName(), e.getMessage());
    }
    return false;
  }

  /**
   * Try to inject a value from session context variables (#ColumnName or ColumnName).
   * Returns true if a value was found and injected, false otherwise.
   */
  private static boolean tryInjectFromSession(JSONObject body, Entity dalEntity, String propName,
      Column col, MandatoryDefaultContext mCtx) {
    try {
      String dbColName = col.getDBColumnName();
      VariablesSecureApp vars = mCtx.vars;
      String fromSession = vars.getSessionValue("#" + dbColName);
      if (fromSession == null || fromSession.isEmpty()) {
        fromSession = vars.getSessionValue(dbColName);
      }
      if (fromSession != null && !fromSession.isEmpty()) {
        applyResolvedDefault(body, col, propName, fromSession, mCtx.neoCtx);
        tryInjectIdentifier(body, dalEntity, propName, body.opt(propName));
        log.debug("Injected from session context: {} = {}", propName, body.opt(propName));
        return true;
      }
    } catch (Exception e) {
      log.debug("Could not read session value for {}: {}", col.getDBColumnName(), e.getMessage());
    }
    return false;
  }

  private static boolean tryInjectFromParentValues(JSONObject body, Entity dalEntity,
      String propName, Column col, Map<String, Object> parentValues) {
    if (parentValues == null || parentValues.isEmpty()) {
      return false;
    }
    String defaultExpr = col.getDefaultValue();
    if (defaultExpr == null || !defaultExpr.matches("^@[A-Za-z_]+@$") ) {
      return false;
    }
    String refCol = defaultExpr.substring(1, defaultExpr.length() - 1).toUpperCase();
    Object value = parentValues.get(refCol);
    if (value == null) {
      return false;
    }
    try {
      applyResolvedDefault(body, col, propName, value, null);
      tryInjectIdentifier(body, dalEntity, propName, body.opt(propName));
      log.debug("Injected parent fallback default: {} = {}", propName, body.opt(propName));
      return true;
    } catch (Exception e) {
      log.debug("Could not inject parent fallback for {}: {}", propName, e.getMessage());
      return false;
    }
  }

  private static boolean tryInjectFallbackFkDefault(JSONObject body, Entity dalEntity,
      String propName, Column col, NeoContext ctx) {
    try {
      if (!col.getDBColumnName().toUpperCase().endsWith("_ID")) {
        return false;
      }
      String fallbackId = resolveFallbackFkDefault(col, ctx);
      if (fallbackId == null || fallbackId.isEmpty()) {
        return false;
      }
      applyResolvedDefault(body, col, propName, fallbackId, ctx);
      tryInjectIdentifier(body, dalEntity, propName, body.opt(propName));
      log.debug("Injected FK fallback default: {} = {}", propName, body.opt(propName));
      return true;
    } catch (Exception e) {
      log.debug("Could not inject fallback FK default for {}: {}",
          col.getDBColumnName(), e.getMessage());
      return false;
    }
  }

  private static void applyResolvedDefault(JSONObject body, Column col,
      String propName, Object resolved, NeoContext ctx) throws Exception {
    if (resolved == null) {
      return;
    }
    // FK columns with legacy "0" default — OBDal cannot resolve "0" as an entity ID.
    // For doctype columns, try to resolve the actual default from C_DocType table.
    if ("0".equals(String.valueOf(resolved))
        && col.getDBColumnName().toUpperCase().endsWith("_ID")) {
      String docTypeId = DocTypeResolver.resolveDefaultDocTypeId(col, ctx);
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
        try {
          valueToStore = new java.math.BigDecimal(strVal);
        } catch (Exception ignored) {
          log.debug("Could not parse '{}' as BigDecimal, keeping as String", strVal);
        }
      } catch (Exception ignored) {
        // Not numeric — keep as String (e.g. status flags, doc numbers)
      }
    }
    body.put(propName, valueToStore);
    log.warn("[NEO-DEFAULTS] {} = {} ({})", propName, valueToStore,
        valueToStore == null ? "null" : valueToStore.getClass().getSimpleName());
  }

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

  private static boolean isAuditColumn(Column col) {
    String colNameUpper = col.getDBColumnName().toUpperCase();
    return "CREATED".equals(colNameUpper)
        || "UPDATED".equals(colNameUpper)
        || "CREATEDBY".equals(colNameUpper)
        || "UPDATEDBY".equals(colNameUpper);
  }

  /**
   * Returns {@code true} for AD_Reference IDs whose Etendo Classic UIDefinition invokes
   * {@code getValueInComboReference} — i.e. combo-style rendering that preselects row [0]
   * when no explicit default is configured.
   *
   * <p>Matches the classes {@code FKComboUIDefinition} (TableDir=19, Table=18) and
   * {@code EnumUIDefinition} (List=17). Returns {@code false} for Search (30),
   * OBUISEL_Selector, Tree, and ID references, which leave the field empty on NEW.</p>
   */
  private static boolean isFICComboReference(String baseRefId) {
    return NeoSelectorService.REF_TABLEDIR.equals(baseRefId)
        || NeoSelectorService.REF_TABLE.equals(baseRefId)
        || NeoSelectorService.REF_LIST.equals(baseRefId);
  }

  /**
   * FIC parity: return the first combo option ID (row [0]) for columns whose AD_Reference
   * renders as a combo in Etendo Classic. Returns {@code null} when the column is not a
   * combo reference, has no lookup rows available, or any error occurs.
   *
   * <p>Routes through {@link NeoSelectorService#querySelectorByColumn} so the same
   * {@code AD_Val_Rule}, reference override, and readable-orgs filters that the selector
   * applies are honored here — exactly like {@code UIDefinition.getValueInComboReference}
   * delegates to {@code ComboTableData}. No entity-specific filters are hardcoded: all
   * contextual behavior (e.g. {@code IsSOPriceList=@IsSOTrx@} for M_PriceList) comes from
   * the column's validation rule.</p>
   *
   * <p>Mirrors {@code UIDefinition.getValueInComboReference} lines 712-718 (FK branch) and
   * 729-750 (List branch).</p>
   */
  private static Object resolveFirstComboOption(Column col, NeoContext ctx) {
    try {
      String baseRefId = NeoSelectorService.getBaseReferenceId(col);
      if (!isFICComboReference(baseRefId)) {
        return null;
      }
      Map<String, String> contextParams = buildFICComboContextParams(ctx);
      NeoResponse selectorResp = NeoSelectorService.querySelectorByColumn(
          col, col.getDBColumnName(), null, 1, 0, contextParams);
      if (selectorResp == null || selectorResp.getHttpStatus() != 200) {
        return null;
      }
      JSONObject body = selectorResp.getBody();
      if (body == null) {
        return null;
      }
      JSONArray items = body.optJSONArray("items");
      if (items == null || items.length() == 0) {
        return null;
      }
      JSONObject first = items.getJSONObject(0);
      // For FK refs the id field is "id"; for List refs resolveListSelector also stores
      // the searchKey under "id". Same key in both paths.
      String id = first.optString("id", null);
      if (id == null || id.isEmpty()) {
        return null;
      }
      return id;
    } catch (Exception e) {
      log.debug("Could not resolve first combo option for {}: {}",
          col.getDBColumnName(), e.getMessage());
      return null;
    }
  }

  /**
   * Build the context-parameter map consumed by {@code SelectorQueryBuilder.resolveValidationSql}
   * when the selector is invoked from the /defaults path. Provides {@code IsSOTrx} / {@code isSOTrx}
   * from the window (both casings, mirroring {@code NeoCalloutService.buildVars}) so validation
   * rules like {@code IsSOPriceList='@IsSOTrx@'} resolve correctly. {@code AD_Org_ID},
   * {@code AD_Client_ID}, {@code AD_User_ID} and {@code AD_Role_ID} are injected automatically
   * by {@code buildValidationParamMap} from the current {@link OBContext}.
   */
  private static Map<String, String> buildFICComboContextParams(NeoContext ctx) {
    Map<String, String> params = new java.util.HashMap<>();
    if (ctx != null && ctx.getAdTab() != null && ctx.getAdTab().getWindow() != null
        && ctx.getAdTab().getWindow().isSalesTransaction() != null) {
      String soTrx =
          Boolean.TRUE.equals(ctx.getAdTab().getWindow().isSalesTransaction()) ? "Y" : "N";
      params.put("IsSOTrx", soTrx);
      params.put("isSOTrx", soTrx);
    }
    return params;
  }

  /**
   * Auto-pick the first available combo option for mandatory FK columns that have no resolved
   * default. Shares the FIC-parity logic with {@link #resolveFirstComboOption}: only columns
   * whose {@code AD_Reference} renders as a combo in Classic (TableDir=19, Table=18, List=17)
   * get preselected, and the lookup is routed through {@link NeoSelectorService#querySelectorByColumn}
   * so {@code AD_Val_Rule} and readable-orgs filters are honored.
   *
   * <p>Search (30), OBUISEL_Selector, and Tree references are intentionally skipped — Classic
   * leaves them empty on NEW as well.</p>
   *
   * @return true if a value was injected, false if the column is not a combo reference or the
   *         selector returned no rows
   */
  private static boolean tryInjectFirstFromLookup(JSONObject body, Entity dalEntity,
      String propName, Column col, NeoContext ctx) {
    Object firstId = resolveFirstComboOption(col, ctx);
    if (firstId == null) {
      return false;
    }
    try {
      body.put(propName, firstId);
      tryInjectIdentifier(body, dalEntity, propName, firstId.toString());
      log.debug("Auto-injected first combo option: {} = {}", propName, firstId);
      return true;
    } catch (Exception e) {
      log.debug("Could not auto-pick first combo option for {}: {}",
          col.getDBColumnName(), e.getMessage());
      return false;
    }
  }

  // ── FK cleanup ─────────────────────────────────────────────────────────

  /**
   * Remove empty-string values from the body for mandatory FK columns.
   * Callouts may set FK fields to "" when they cannot resolve a value (e.g., BP without
   * sales payment term). Removing these allows injectMandatoryDefaults to attempt
   * a fallback resolution on the next pass.
   *
   * @param body  the JSON request body from which empty FK values will be removed
   * @param adTab the AD_Tab containing the table with FK column definitions
   */
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

    int updatedFieldCount() {
      return updates.length();
    }

    void mergeUpdates(JSONObject newUpdates) {
      mergeJsonObjectValues(updates, newUpdates);
    }

    void mergeCombos(JSONObject newCombos) {
      mergeJsonObjectValues(combos, newCombos);
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
        json.put(KEY_UPDATES, updates);
        json.put(KEY_COMBOS, combos);
        json.put("messages", messages);
      } catch (Exception e) {
        // should never happen
      }
      return json;
    }

    private void mergeJsonObjectValues(JSONObject target, JSONObject source) {
      if (source == null) {
        return;
      }
      Iterator<String> keys = source.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        try {
          target.put(key, source.get(key));
        } catch (Exception e) {
          // skip
        }
      }
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
  /**
   * Always recompute grossAmount (LINE_GROSS_AMOUNT) before persisting, covering both
   * tax-inclusive and tax-exclusive price lists.
   *
   * <p><b>Gross price list</b> (grossUnitPrice > 0): {@code grossAmount = grossUnitPrice × qty}.
   * The price already includes tax, so no lookup needed.</p>
   *
   * <p><b>Net price list</b> (grossUnitPrice = 0): {@code grossAmount = lineNetAmount × (1 + rate/100)}.
   * The tax rate is fetched from C_Tax using the 'tax' field present in the body.
   * {@code injectLineNetAmountIfMissing} must run before this method so lineNetAmount is available.</p>
   *
   * <p>Mirrors the behaviour of {@link #injectLineNetAmountIfMissing}: always recomputes to
   * override any stale value (e.g. product callout set grossAmount for qty=1 before the user
   * changed qty).</p>
   */
  public static void injectGrossAmountIfMissing(JSONObject body) {
    if (body == null) {
      return;
    }
    double qty;
    try {
      qty = Double.parseDouble(body.optString("invoicedQuantity", "0"));
    } catch (NumberFormatException e) {
      return;
    }
    if (qty == 0) {
      return;
    }
    double grossUnitPrice = body.optDouble("grossUnitPrice", 0);
    if (grossUnitPrice > 0) {
      // Tax-inclusive price list: grossAmount = grossUnitPrice × qty
      try {
        double computed = grossUnitPrice * qty;
        body.put("grossAmount", computed);
        log.debug("[NEO-DEFAULTS] Computed grossAmount={} from grossUnitPrice={} × qty={}",
            computed, grossUnitPrice, qty);
      } catch (Exception e) {
        log.debug("Could not compute grossAmount: {}", e.getMessage());
      }
      return;
    }
    // Tax-exclusive price list: grossAmount = lineNetAmount × (1 + taxRate / 100)
    double lineNetAmount = body.optDouble("lineNetAmount", 0);
    if (lineNetAmount == 0) {
      return;
    }
    String taxId = body.optString("tax", "");
    if (taxId.isEmpty()) {
      return;
    }
    try {
      double taxRate = fetchTaxRate(taxId);
      double computed = lineNetAmount * (1.0 + taxRate / 100.0);
      body.put("grossAmount", computed);
      log.debug("[NEO-DEFAULTS] Computed grossAmount={} from lineNetAmount={} × (1 + {}%)",
          computed, lineNetAmount, taxRate);
    } catch (Exception e) {
      log.debug("Could not compute grossAmount from tax rate: {}", e.getMessage());
    }
  }

  private static double fetchTaxRate(String taxId) {
    String sql = "SELECT rate FROM c_tax WHERE c_tax_id = ?";
    try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
      ps.setString(1, taxId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getDouble(1);
        }
      }
    } catch (Exception e) {
      log.debug("Could not fetch tax rate for taxId={}: {}", taxId, e.getMessage());
    }
    return 0;
  }

  /**
   * For order/quotation lines, always recompute 'lineGrossAmount' as
   * effectiveUnitPrice × orderedQuantity. Uses grossUnitPrice if present and non-zero,
   * falls back to unitPrice for net-price list quotations where the callout does not
   * populate grossUnitPrice.
   *
   * <p>Always recomputes to override any stale value the product callout may have set
   * for qty=1 (SL_Order_Amt fires on product change before the user sets the quantity).
   */
  public static void injectLineGrossAmountIfMissing(JSONObject body) {
    if (body == null) {
      return;
    }
    double effectivePrice = body.optDouble("grossUnitPrice", 0);
    if (effectivePrice <= 0) {
      effectivePrice = body.optDouble("unitPrice", 0);
    }
    if (effectivePrice <= 0) {
      return;
    }
    double qty;
    try {
      qty = Double.parseDouble(body.optString("orderedQuantity", "0"));
    } catch (NumberFormatException e) {
      return;
    }
    if (qty == 0) {
      return;
    }
    try {
      double computed = effectivePrice * qty;
      body.put("lineGrossAmount", computed);
      log.debug("[NEO-DEFAULTS] Computed lineGrossAmount={} from price={} × qty={}",
          computed, effectivePrice, qty);
    } catch (Exception e) {
      log.debug("Could not compute lineGrossAmount: {}", e.getMessage());
    }
  }

  /**
   * Fallback: when lineNetAmount is absent or zero after the callout cascade, compute it as
   * invoicedQuantity × unitPrice. This covers products where SL_Invoice_Amt throws
   * (e.g. products without a standard cost or price list entry in the sales context,
   * such as products on tax-exclusive price lists where PriceAdjustment fails).
   *
   * <p>Called from both the CREATE path (after executePostCalloutCascade) and the UPDATE path
   * (after filterWriteRequest, before persist). For the UPDATE path the frontend sends all
   * editable line fields including invoicedQuantity and unitPrice, so both values are available
   * even though lineNetAmount itself is stripped by the readOnly filter.</p>
   */
  public static void injectLineNetAmountIfMissing(JSONObject body) {
    if (body == null) {
      return;
    }
    // Always recompute lineNetAmount = invoicedQuantity × unitPrice to override any stale
    // value the product callout may have set for qty=1. lineNetAmount = QtyInvoiced × PriceActual
    // is the invariant for all invoice line types (gross and net price lists alike, since
    // unitPrice always holds the net unit price regardless of the price list configuration).
    double qty;
    try {
      qty = Double.parseDouble(body.optString("invoicedQuantity", "0"));
    } catch (NumberFormatException e) {
      return;
    }
    if (qty == 0) {
      return;
    }
    double unitPrice = body.optDouble("unitPrice", 0);
    if (unitPrice == 0) {
      return;
    }
    try {
      double computed = qty * unitPrice;
      body.put("lineNetAmount", computed);
      log.debug("[NEO-DEFAULTS] Set lineNetAmount={} from qty={} × unitPrice={}",
          computed, qty, unitPrice);
    } catch (Exception e) {
      log.debug("Could not compute lineNetAmount: {}", e.getMessage());
    }
  }

  /**
   * For line entities (e.g. C_InvoiceLine, C_OrderLine), when 'product' is present in the body
   * but 'uOM' (C_UOM_ID) is missing or empty, derive C_UOM_ID from M_Product.C_UOM_ID.
   *
   * This prevents DB trigger failures on INSERT caused by a product/transaction UOM mismatch:
   * the c_invoiceline_trg trigger requires that C_UOM_ID matches the product's UOM, but
   * callouts return uOM as empty (the classic UI auto-fills it via readOnly logic), so NEO
   * must derive it here before persisting.
   */
  public static void injectProductDerivedUomIfMissing(JSONObject body) {
    if (body == null) {
      return;
    }
    String productId = body.optString("product", "");
    if (productId.isEmpty()) {
      return;
    }
    // Only inject if uOM is absent or empty
    String existingUom = body.optString("uOM", "");
    if (!existingUom.isEmpty()) {
      return;
    }
    try {
      String sql = "SELECT C_UOM_ID FROM M_PRODUCT WHERE M_PRODUCT_ID = ?";
      try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
        ps.setString(1, productId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String uomId = rs.getString(1);
            if (uomId != null && !uomId.isEmpty()) {
              body.put("uOM", uomId);
              log.debug("[NEO-DEFAULTS] Injected product-derived uOM={} for product={}", uomId, productId);
            }
          }
        }
      }
    } catch (Exception e) {
      log.debug("Could not inject product-derived UOM for product {}: {}", productId, e.getMessage());
    }
  }
}
