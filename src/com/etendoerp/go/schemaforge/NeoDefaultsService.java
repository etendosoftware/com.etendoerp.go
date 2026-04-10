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
  private static final int MAX_CALLOUT_CHAIN_DEPTH = 5;
  private static final String FIELD_VALUE = "value";
  private static final String VALUE_KEY = FIELD_VALUE;
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

    String defaultExpr = adColumn.getDefaultValue();
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
   */
  private static Object resolveFromPrefsOrDocType(Column adColumn, VariablesSecureApp vars,
      DalConnectionProvider conn, String windowId, String dbColumnName, NeoContext ctx) {
    String colUpper = dbColumnName.toUpperCase();
    if (colUpper.endsWith("_ID") && colUpper.contains("DOCTYPE")) {
      return resolveDefaultDocTypeId(adColumn, ctx);
    }
    String fromPrefs = Utility.getDefault(conn, vars, dbColumnName, "", windowId, "");
    if (fromPrefs != null && !fromPrefs.isEmpty()) {
      return fromPrefs;
    }
    String docTypeId = resolveDefaultDocTypeId(adColumn, ctx);
    if (docTypeId != null) {
      return docTypeId;
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

    String cacheKey = userId + "|" + roleId + "|" + orgId + "|" + warehouseId;
    VariablesSecureApp cached = varsCache.getIfPresent(cacheKey);
    if (cached != null) {
      return cached;
    }

    VariablesSecureApp vars = NeoCalloutService.buildVars(obContext);
    vars.setSessionValue("#Date",
        new SimpleDateFormat(DATE_FORMAT).format(new Date()));

    varsCache.put(cacheKey, vars);
    return vars;
  }

  /**
   * Resolve the default C_DocType ID for columns referencing document type.
   */
  private static String resolveDefaultDocTypeId(Column col, NeoContext ctx) {
    String colName = col.getDBColumnName().toUpperCase();
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

      String[] subTypeFilters = parseSubTypeFilters(ctx);
      return queryDefaultDocType(clientId, docBaseType, isSOTrx,
          subTypeFilters[0], subTypeFilters[1], colName);
    } catch (Exception e) {
      log.debug("Could not resolve default doctype for {}: {}", colName, e.getMessage());
      return null;
    }
  }

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
    return new String[]{ subTypeFilter, subTypeExclude };
  }

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

  private static String resolveIsSOTrxDefault(
      org.openbravo.model.ad.datamodel.Table table, NeoContext ctx) {
    for (Column c : table.getADColumnList()) {
      if ("IsSOTrx".equalsIgnoreCase(c.getDBColumnName())) {
        return resolveIsSOTrxFromColumn(c, ctx);
      }
    }
    return "Y";
  }

  private static String resolveIsSOTrxFromColumn(Column col, NeoContext ctx) {
    String defaultVal = col.getDefaultValue();
    if (defaultVal == null || defaultVal.trim().isEmpty()) {
      return "Y";
    }
    defaultVal = defaultVal.trim();

    String literal = parseIsSOTrxLiteral(defaultVal);
    if (literal != null) {
      return literal;
    }

    if (defaultVal.contains("@") && ctx != null) {
      String resolved = resolveIsSOTrxFromContext(defaultVal, ctx);
      if (resolved != null) {
        return resolved;
      }
    }

    return "Y";
  }

  private static String parseIsSOTrxLiteral(String value) {
    if ("Y".equals(value) || "'Y'".equals(value)) {
      return "Y";
    }
    if ("N".equals(value) || "'N'".equals(value)) {
      return "N";
    }
    return null;
  }

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

    return null;
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
      Map<String, Object> parentValues = loadParentValues(adTab, parentId);
      MandatoryDefaultContext mCtx = new MandatoryDefaultContext(parentId, vars, conn,
          windowId, ctx, parentValues);

      for (Column col : adTab.getTable().getADColumnList()) {
        if (!col.isActive() || !col.isMandatory()) {
          continue;
        }
        injectMandatoryDefaultForColumn(body, dalEntity, col, mCtx);
      }

      // Fallback 3: run callout cascade with all fields in body.
      // Callouts configured in AD_Column derive dependent fields (e.g. BP → address,
      // price list, payment terms) without hardcoding any field relationships.
      executeCalloutCascadeForCreate(ctx, adTab, body);

    } catch (Exception e) {
      log.error("Error injecting mandatory defaults for tab {}: {}",
          adTab.getName(), e.getMessage(), e);
    }
  }

  /**
   * Bundles the resolution infrastructure needed for mandatory default injection.
   * Passed as a single parameter instead of 5 separate arguments.
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
      if (tryInjectFromSession(body, propName, col.getDBColumnName(), mCtx.vars)) {
        return;
      }
      if (tryInjectFromParentValues(body, dalEntity, propName, col, mCtx.parentValues)) {
        return;
      }
      injectSafeTypeDefault(body, propName, col);
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
        body.put(propName, resolved);
        log.debug("Injected mandatory default: {} = {}", propName, resolved);
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
  private static boolean tryInjectFromSession(JSONObject body, String propName,
      String dbColName, VariablesSecureApp vars) {
    try {
      String fromSession = vars.getSessionValue("#" + dbColName);
      if (fromSession == null || fromSession.isEmpty()) {
        fromSession = vars.getSessionValue(dbColName);
      }
      if (fromSession != null && !fromSession.isEmpty()) {
        body.put(propName, fromSession);
        log.debug("Injected from session context: {} = {}", propName, fromSession);
        return true;
      }
    } catch (Exception e) {
      log.debug("Could not read session value for {}: {}", dbColName, e.getMessage());
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
      body.put(propName, value);
      // Keep selector labels populated for FK fallbacks (same behavior as /defaults).
      tryInjectIdentifier(body, dalEntity, propName, value);
      log.debug("Injected parent fallback default: {} = {}", propName, value);
      return true;
    } catch (Exception e) {
      log.debug("Could not inject parent fallback for {}: {}", propName, e.getMessage());
      return false;
    }
  }

  /**
   * Inject a safe zero or false default for numeric and boolean columns (Fallback 2).
   * Leaves other column types untouched.
   */
  private static void injectSafeTypeDefault(JSONObject body, String propName, Column col) {
    try {
      String refId = col.getReference() != null ? col.getReference().getId() : null;
      if ("22".equals(refId) || "29".equals(refId) || "12".equals(refId) || "11".equals(refId)) {
        body.put(propName, 0);
        log.debug("Injected numeric zero default: {} = 0", propName);
      } else if ("20".equals(refId)) {
        body.put(propName, false);
        log.debug("Injected boolean default: {} = false", propName);
      }
    } catch (Exception e) {
      log.debug("Could not inject safe type default for {}: {}", propName, e.getMessage());
    }
  }

  /**
   * Run callout cascade on all fields present in the body during record creation.
   * This derives dependent fields via AD-configured callouts (e.g. setting businessPartner
   * triggers callouts that fill partnerAddress, priceList, paymentTerms, etc.)
   * without hardcoding any field relationships.
   */
  private static void executeCalloutCascadeForCreate(NeoContext ctx, Tab adTab, JSONObject body) {
    try {
      Set<String> emptySeqFields = new HashSet<>();
      CalloutCascadeResult cascadeResult = executeCalloutCascade(ctx, adTab, body, emptySeqFields);

      if (cascadeResult != null && cascadeResult.hasResults()) {
        log.info("[NEO-CREATE] Callout cascade derived {} field updates",
            cascadeResult.updatedFieldCount());
      }
    } catch (Exception e) {
      log.warn("[NEO-CREATE] Callout cascade failed (non-fatal): {}", e.getMessage());
    }
  }

  /**
   * Resolve selector auxiliary values for a FK field.
   * Finds the AD_Column for the given fieldName on this tab, then delegates to
   * NeoSelectorService.resolveSelectorAuxForId to fetch aux values (_PSTD, _UOM, etc.).
   */
  private static JSONObject resolveSelectorAuxValues(Tab adTab, String fieldName,
      String value) {
    if (adTab == null || fieldName == null || value == null || value.isEmpty()) {
      return null;
    }
    try {
      Entity entity = ModelProvider.getInstance()
          .getEntityByTableId(adTab.getTable().getId());
      if (entity == null) {
        return null;
      }

      Property prop = entity.getProperty(fieldName, false);
      if (prop == null || prop.getColumnId() == null || prop.isPrimitive()) {
        return null;
      }

      Column adColumn = OBDal.getInstance().get(Column.class, prop.getColumnId());
      if (adColumn == null) {
        return null;
      }

      JSONObject aux = NeoSelectorService.resolveSelectorAuxForId(adColumn, fieldName, value);
      if (aux != null && aux.length() > 0) {
        log.info("[NEO-DEFAULTS] Selector aux for '{}': {}", fieldName, aux);
      }
      return aux;
    } catch (Exception e) {
      log.warn("[NEO-DEFAULTS] Failed to resolve selector aux for field '{}': {}",
          fieldName, e.getMessage());
      return null;
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
        CalloutFieldContext cCtx = new CalloutFieldContext(formState, defaults, seqFields,
            result, nextPending);

        for (String fieldName : pendingFields) {
          Object value = formState.opt(fieldName);
          if (value == null || JSONObject.NULL.equals(value)) {
            continue;
          }
          processCalloutForField(ctx, adTab, fieldName, value, cCtx);
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
   * Run one callout-cascade iteration and return the next set of pending fields.
   */
  private static Set<String> executeCascadeIteration(Set<String> pendingFields,
      NeoContext ctx, Tab adTab, JSONObject formState, JSONObject defaults,
      Set<String> skipFields, CalloutCascadeResult result) {
    Set<String> nextPending = new LinkedHashSet<>();
    CalloutFieldContext cCtx = new CalloutFieldContext(formState, defaults,
        java.util.Collections.emptySet(), result, nextPending);

    for (String fieldName : pendingFields) {
      if (skipFields != null && skipFields.contains(fieldName)) {
        continue;
      }
      Object value = formState.opt(fieldName);
      if (value == null || JSONObject.NULL.equals(value)) {
        continue;
      }
      processCalloutForField(ctx, adTab, fieldName, value, cCtx);
    }
    return nextPending;
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
      if (NeoCalloutService.resolveCallout(adTab, fieldName) != null) {
        fieldsWithCallouts.add(fieldName);
      }
    }
    return fieldsWithCallouts;
  }

  /**
   * Bundles the mutable cascade state threaded through the callout cascade loop.
   * Groups formState, defaults, seqFields, result, and nextPending into one object.
   */
  private static class CalloutFieldContext {
    final JSONObject formState;
    final JSONObject defaults;
    final Set<String> seqFields;
    final CalloutCascadeResult result;
    final Set<String> nextPending;

    CalloutFieldContext(JSONObject formState, JSONObject defaults, Set<String> seqFields,
        CalloutCascadeResult result, Set<String> nextPending) {
      this.formState = formState;
      this.defaults = defaults;
      this.seqFields = seqFields;
      this.result = result;
      this.nextPending = nextPending;
    }
  }

  /**
   * Execute the callout for a single field and merge the results into the running state.
   */
  private static void processCalloutForField(NeoContext ctx, Tab adTab, String fieldName,
      Object value, CalloutFieldContext cCtx) {
    try {
      JSONObject calloutRequest = buildCalloutRequest(adTab, fieldName, value, cCtx.formState);
      NeoResponse calloutResponse = NeoCalloutService.executeCallout(ctx, calloutRequest);
      if (calloutResponse == null || calloutResponse.getHttpStatus() != 200) {
        log.debug("[NEO-DEFAULTS] Callout for '{}' failed or returned non-200", fieldName);
        return;
      }

      JSONObject calloutBody = calloutResponse.getBody();
      if (calloutBody == null) {
        return;
      }

      mergeCalloutUpdates(calloutBody, cCtx.formState, cCtx.defaults, cCtx.seqFields,
          adTab, cCtx.result, cCtx.nextPending);
      mergeCalloutCombos(calloutBody, cCtx.formState, cCtx.defaults, cCtx.result);

      JSONArray messages = calloutBody.optJSONArray("messages");
      if (messages != null) {
        cCtx.result.mergeMessages(messages);
      }

    } catch (Exception e) {
      log.warn("[NEO-DEFAULTS] Callout cascade error for field '{}': {}", fieldName, e.getMessage());
    }
  }

  /**
   * Build the callout request JSON, including auxiliary selector values if available.
   */
  private static JSONObject buildCalloutRequest(Tab adTab, String fieldName, Object value,
      JSONObject formState) throws Exception {
    JSONObject calloutRequest = new JSONObject();
    calloutRequest.put("field", fieldName);
    calloutRequest.put(FIELD_VALUE, value);
    calloutRequest.put("formState", formState);

    // Resolve selector auxiliary values for FK fields.
    // Classic UI passes _PLIST, _PSTD, _PLIM, _UOM, _CURR from selector response.
    // Without these, callouts like SL_Order_Product can't resolve prices.
    JSONObject auxValues = resolveSelectorAuxValues(adTab, fieldName, value.toString());
    if (auxValues != null && auxValues.length() > 0) {
      calloutRequest.put("auxiliaryValues", auxValues);
      log.info("[NEO-DEFAULTS] Resolved {} aux values for field '{}'",
          auxValues.length(), fieldName);
    }
    return calloutRequest;
  }

  /**
   * Merge callout update results into the running form state and defaults.
   * Fields whose value changed and have their own callout are added to nextPending.
   */
  private static void mergeCalloutUpdates(JSONObject calloutBody, JSONObject formState,
      JSONObject defaults, Set<String> seqFields, Tab adTab,
      CalloutCascadeResult result, Set<String> nextPending) throws Exception {
    JSONObject updates = calloutBody.optJSONObject("updates");
    if (updates == null) {
      return;
    }
    result.mergeUpdates(updates);
    Iterator<String> updateKeys = updates.keys();
    while (updateKeys.hasNext()) {
      String updatedField = updateKeys.next();
      JSONObject updateObj = updates.optJSONObject(updatedField);
      if (updateObj == null || !updateObj.has(FIELD_VALUE)) {
        continue;
      }
      Object newValue = updateObj.get(FIELD_VALUE);
      Object oldValue = formState.opt(updatedField);
      formState.put(updatedField, newValue);
      defaults.put(updatedField, newValue);

      // Queue the updated field for the next iteration if its value changed and it has a callout
      if (valueChanged(oldValue, newValue) && !seqFields.contains(updatedField)
          && NeoCalloutService.resolveCallout(adTab, updatedField) != null) {
        nextPending.add(updatedField);
      }
    }
  }

  /**
   * Merge callout combo results into the running form state and defaults.
   * Applies the selected combo value so subsequent callouts see it.
   */
  private static void mergeCalloutCombos(JSONObject calloutBody, JSONObject formState,
      JSONObject defaults, CalloutCascadeResult result) throws Exception {
    JSONObject combos = calloutBody.optJSONObject("combos");
    if (combos == null) {
      return;
    }
    result.mergeCombos(combos);
    Iterator<String> comboKeys = combos.keys();
    while (comboKeys.hasNext()) {
      String comboField = comboKeys.next();
      JSONObject comboObj = combos.optJSONObject(comboField);
      if (comboObj == null || !comboObj.has("selected")) {
        continue;
      }
      Object selectedValue = comboObj.get("selected");
      if (selectedValue != null && !JSONObject.NULL.equals(selectedValue)) {
        formState.put(comboField, selectedValue);
        defaults.put(comboField, selectedValue);
        log.debug("[NEO-DEFAULTS] Applied combo selected value: {} = {}", comboField, selectedValue);
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
  static class CalloutCascadeResult {
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
