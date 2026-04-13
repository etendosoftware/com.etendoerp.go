package com.etendoerp.go.schemaforge;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import org.openbravo.dal.service.OBQuery;
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

        // Execute callout cascade for defaulted fields that have callouts configured
        Tab adTab = ctx.getAdTab();
        Set<String> seqFieldSet = new HashSet<>();
        for (int i = 0; i < sequenceFields.length(); i++) {
          seqFieldSet.add(sequenceFields.getString(i));
        }

        CalloutCascadeResult cascadeResult = null;
        if (adTab != null) {
          cascadeResult = NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, seqFieldSet);
        }

        // Classic parity: for each MANDATORY FK column that still has no resolved value,
        // auto-pick the first available option ordered alphabetically (same as
        // UIDefinition.getValueInComboReference() lines 713-728 in Classic Etendo).
        // Non-mandatory FKs are intentionally left blank — Classic leaves them empty too.
        for (SFField sfField : fields) {
          Column adColumn = sfField.getADColumn();
          if (adColumn == null || !adColumn.isMandatory()) {
            continue;
          }
          String propName = NeoDefaultsCascadeHelper.resolvePropertyName(
              dalEntity, adColumn.getDBColumnName());
          if (propName == null || defaults.has(propName)) {
            continue;
          }
          tryInjectFirstFromLookup(defaults, dalEntity, propName, adColumn);
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
      return DocTypeResolver.resolveDefaultDocTypeId(adColumn, ctx);
    }
    String fromPrefs = Utility.getDefault(conn, vars, dbColumnName, "", windowId, "");
    if (fromPrefs != null && !fromPrefs.isEmpty()) {
      return fromPrefs;
    }
    String docTypeId = DocTypeResolver.resolveDefaultDocTypeId(adColumn, ctx);
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
      NeoDefaultsCascadeHelper.executeCalloutCascadeForCreate(ctx, adTab, body);

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
      if (tryInjectFirstFromLookup(body, dalEntity, propName, col)) {
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
   * Auto-pick the first available FK option for mandatory FK columns that have no resolved default.
   * Replicates Classic Etendo behavior: UIDefinition.getValueInComboReference() always picks the
   * alphabetically-first combo entry when no explicit default is configured.
   *
   * <p>Applies to TableDir (17), Table (18), Search (30), and OBUISEL_Selector references.
   * Skips non-FK columns and columns where a default was already resolved.
   *
   * @return true if a value was injected, false if not applicable or lookup was empty
   */
  private static boolean tryInjectFirstFromLookup(JSONObject body, Entity dalEntity,
      String propName, Column col) {
    try {
      String baseRefId = NeoSelectorService.getBaseReferenceId(col);
      boolean isFk = NeoSelectorService.isFkReference(baseRefId)
          || NeoSelectorService.REF_OBUISEL.equals(baseRefId);
      if (!isFk) {
        return false;
      }
      SelectorMeta target = NeoSelectorService.resolveTarget(col, baseRefId);
      if (target == null || target.entityName == null) {
        return false;
      }

      // Build an org-scoped query — include org "0" for shared master data
      SelectorQueryBuilder.HqlWithParams orgPredicate =
          SelectorQueryBuilder.buildReadableOrgsPredicate(target.entityName, "e", true);
      StringBuilder where = new StringBuilder("as e where e.active = true");
      if (orgPredicate != null && !orgPredicate.isBlank()) {
        where.append(" and ").append(orgPredicate.getHql());
      }
      Entity targetEntity = ModelProvider.getInstance().getEntity(target.entityName);
      if (targetEntity == null) {
        return false;
      }
      String idProp = NeoSelectorService.findIdentifierProperty(targetEntity);
      if (idProp == null) {
        return false;
      }
      where.append(" order by e.").append(idProp);

      OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(target.entityName,
          where.toString());
      if (orgPredicate != null && !orgPredicate.isBlank()) {
        NeoSelectorExecutionHelper.bindNamedParameters(query, orgPredicate.getParams());
      }
      query.setMaxResult(1);
      List<BaseOBObject> results = query.list();
      if (results.isEmpty()) {
        return false;
      }
      String firstId = (String) results.get(0).getId();
      body.put(propName, firstId);
      tryInjectIdentifier(body, dalEntity, propName, firstId);
      log.debug("Auto-injected first FK option: {} = {}", propName, firstId);
      return true;
    } catch (Exception e) {
      log.debug("Could not auto-pick first FK for {}: {}", col.getDBColumnName(), e.getMessage());
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
}
