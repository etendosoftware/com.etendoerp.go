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
   * Delegates to {@link NeoCalloutService#buildVars} and adds caching + #Date.
   */
  private static VariablesSecureApp buildVariablesSecureApp(OBContext obContext) {
    String userId = obContext.getUser().getId();
    String roleId = obContext.getRole().getId();
    String orgId = obContext.getCurrentOrganization().getId();
    String warehouseId = obContext.getWarehouse() != null
        ? obContext.getWarehouse().getId() : "";

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

      for (Column col : adTab.getTable().getADColumnList()) {
        if (!col.isActive() || !col.isMandatory()) {
          continue;
        }
        injectMandatoryDefaultForColumn(body, dalEntity, col, parentId, vars, conn, windowId, ctx);
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
   * Attempt to inject a mandatory default value for a single column into the body.
   * Tries field default resolution first, then session context, then safe numeric/boolean fallback.
   */
  private static void injectMandatoryDefaultForColumn(JSONObject body, Entity dalEntity,
      Column col, String parentId, VariablesSecureApp vars, DalConnectionProvider conn,
      String windowId, NeoContext ctx) {
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

      if (tryResolveFieldDefault(body, propName, col, parentId, vars, conn, windowId, ctx)) {
        return;
      }
      if (tryInjectFromSession(body, propName, col.getDBColumnName(), vars)) {
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
      String parentId, VariablesSecureApp vars, DalConnectionProvider conn, String windowId,
      NeoContext ctx) {
    try {
      Object resolved = resolveFieldDefault(col, parentId, vars, conn, windowId, ctx);
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

        for (String fieldName : pendingFields) {
          Object value = formState.opt(fieldName);
          if (value == null || JSONObject.NULL.equals(value)) {
            continue;
          }
          processCalloutForField(ctx, adTab, fieldName, value, formState, defaults,
              seqFields, result, nextPending);
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
   * Collect all fields from the defaults map that have a callout configured
   * and a non-null value, excluding sequence preview fields.
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
   * Execute the callout for a single field and merge the results into the running state.
   */
  private static void processCalloutForField(NeoContext ctx, Tab adTab, String fieldName,
      Object value, JSONObject formState, JSONObject defaults, Set<String> seqFields,
      CalloutCascadeResult result, Set<String> nextPending) {
    try {
      JSONObject calloutRequest = buildCalloutRequest(adTab, fieldName, value, formState);
      NeoResponse calloutResponse = NeoCalloutService.executeCallout(ctx, calloutRequest);
      if (calloutResponse == null || calloutResponse.getHttpStatus() != 200) {
        log.debug("[NEO-DEFAULTS] Callout for '{}' failed or returned non-200", fieldName);
        return;
      }

      JSONObject calloutBody = calloutResponse.getBody();
      if (calloutBody == null) {
        return;
      }

      mergeCalloutUpdates(calloutBody, formState, defaults, seqFields, adTab, result, nextPending);
      mergeCalloutCombos(calloutBody, formState, defaults, result);

      JSONArray messages = calloutBody.optJSONArray("messages");
      if (messages != null) {
        result.mergeMessages(messages);
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

}
