package com.etendoerp.go.schemaforge;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Resolves and injects default values for mandatory (NOT NULL) table columns that are not
 * covered by the request body on the NEO Headless "create record" HTTP path.
 *
 * <p>Extracted from {@link NeoDefaultsService} (ETP-4978 merge block) to keep that class under
 * SonarQube's method-count threshold. This class implements only the {@link
 * NeoDefaultsService#injectMandatoryDefaults} family — the create-time mandatory-column
 * injection pass — as opposed to {@link NeoDefaultsService#resolveDefaults}, which resolves
 * defaults for the {@code GET .../defaults} form-bootstrap endpoint. The two flows share a few
 * low-level helpers ({@code resolveFieldDefault}, {@code tryInjectIdentifier}, {@code
 * resolveWindowId}, {@code resolveFirstComboOption}), which remain package-private members of
 * {@link NeoDefaultsService} and are called from here by qualified reference.
 */
public class NeoMandatoryDefaultsService {

  private static final Logger log = LogManager.getLogger(NeoMandatoryDefaultsService.class);

  private NeoMandatoryDefaultsService() {
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
    injectMandatoryDefaults(body, adTab, ctx, null, true);
  }

  public static void injectMandatoryDefaults(JSONObject body, Tab adTab, NeoContext ctx, String parentId) {
    injectMandatoryDefaults(body, adTab, ctx, parentId, true);
  }

  /**
   * Injects missing mandatory default values into a create payload.
   *
   * <p>This overload lets callers decide whether the default injection pass should
   * also execute the trailing callout cascade. Use {@code runCascade=false} when
   * the caller runs the cascade immediately afterward.</p>
   *
   * @param body       the filtered request body — columns already present are skipped
   * @param adTab      the AD_Tab for the entity being created
   * @param ctx        the NeoContext with OBContext and spec/entity info
   * @param parentId   optional parent record id used for child-tab defaults
   * @param runCascade whether to run the trailing callout cascade after column iteration.
   *                   Set to {@code false} when the caller will run the cascade explicitly
   *                   right after, to avoid duplicating the (expensive) cascade pass.
   */
  public static void injectMandatoryDefaults(JSONObject body, Tab adTab, NeoContext ctx,
      String parentId, boolean runCascade) {
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
      VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(ctx.getObContext(), adTab);
      DalConnectionProvider conn = new DalConnectionProvider(false);
      String windowId = ctx.getSfEntity() != null ? NeoDefaultsService.resolveWindowId(ctx.getSfEntity()) : "";
      Map<String, Object> parentValues = NeoParentValuesLoader.load(adTab, parentId);

      // ETP-4783: Inject ISSOTRX so that @issotrx@ in @SQL= defaults (e.g. aeatsiiDescripcionSii)
      // resolves to the correct sales/purchase value rather than falling back to Utility.getContext,
      // which returns empty in the NEO Headless session context (no Classic window session variable
      // is set). The same lookup is done by SelectorContextResolver.resolveIsSOTrxFromWindow.
      injectIsSOTrxFromTab(parentValues, adTab);

      // Build a map of ETGO_SF_FIELD per-window default overrides, keyed by DB column name
      // (upper-case). This mirrors the sfFieldDefault lookup that resolveDefaults already does
      // so that the CREATE path honours the same window-level defaults as the /defaults endpoint.
      Map<String, String> sfFieldDefaults = buildSfFieldDefaultsMap(ctx);

      MandatoryDefaultContext mCtx = new MandatoryDefaultContext(parentId, vars, conn,
          windowId, ctx, parentValues, sfFieldDefaults);

      // ETP-4274: iterate ALL active columns, not only mandatory ones. Non-mandatory
      // columns that have a genuine resolvable default (e.g. C_Currency_ID from
      // @C_Currency_ID@) must be injected on create to reach parity with /defaults —
      // otherwise the create path silently drops them. Mandatory-ness is passed down so
      // the aggressive NOT-NULL safety fallbacks stay gated to mandatory columns only.
      injectDefaultsForActiveColumns(body, adTab, dalEntity, mCtx);

      // Fallback 3: run callout cascade with all fields in body.
      // Callouts configured in AD_Column derive dependent fields (e.g. BP → address,
      // price list, payment terms) without hardcoding any field relationships.
      // Skipped when the caller will run the cascade explicitly right after, to avoid
      // duplicating the (expensive) cascade pass.
      if (runCascade) {
        NeoDefaultsCascadeHelper.executeCalloutCascadeForCreate(ctx, adTab, body);
      }

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
    /**
     * Per-column ETGO_SF_FIELD default expressions, keyed by DB column name (upper-case).
     * Built once from the entity's SFField list so that injectMandatoryDefaults honours the
     * same per-window defaults that /defaults already returns via resolveFieldDefault.
     * Columns without an ETGO_SF_FIELD entry are absent from this map → null is passed to
     * resolveFieldDefault → AD_Column default is used (no behaviour change for them).
     */
    final Map<String, String> sfFieldDefaults;

    MandatoryDefaultContext(String parentId, VariablesSecureApp vars,
        DalConnectionProvider conn, String windowId, NeoContext neoCtx,
        Map<String, Object> parentValues, Map<String, String> sfFieldDefaults) {
      this.parentId = parentId;
      this.vars = vars;
      this.conn = conn;
      this.windowId = windowId;
      this.neoCtx = neoCtx;
      this.parentValues = parentValues != null ? parentValues
          : java.util.Collections.emptyMap();
      this.sfFieldDefaults = sfFieldDefaults != null ? sfFieldDefaults
          : java.util.Collections.emptyMap();
    }
  }

  /**
   * Injects the ISSOTRX flag derived from the AD Window's isSalesTransaction field into
   * the given parent-values map, overwriting any existing value. No-op when the tab or
   * window is null or when isSalesTransaction is not set. Extracted from
   * {@link #injectMandatoryDefaults} to reduce cognitive complexity (S3776).
   */
  private static void injectIsSOTrxFromTab(Map<String, Object> parentValues, Tab adTab) {
    if (adTab == null || adTab.getWindow() == null) {
      return;
    }
    Boolean isSalesTrx = adTab.getWindow().isSalesTransaction();
    if (isSalesTrx != null) {
      parentValues.put("ISSOTRX", isSalesTrx ? "Y" : "N");
    }
  }

  /**
   * Iterates all columns of the given tab's table and injects a mandatory default for
   * each active, non-key, non-audit column. Extracted from {@link #injectMandatoryDefaults}
   * to reduce cognitive complexity (S3776). Behavior is identical to the inline loop.
   */
  private static void injectDefaultsForActiveColumns(JSONObject body, Tab adTab,
      Entity dalEntity, MandatoryDefaultContext mCtx) {
    for (Column col : adTab.getTable().getADColumnList()) {
      if (shouldSkipColumn(col, dalEntity)) {
        continue;
      }
      injectMandatoryDefaultForColumn(body, dalEntity, col, mCtx, col.isMandatory());
    }
  }

  /**
   * Returns true when the given column must be skipped by {@link #injectDefaultsForActiveColumns}:
   * inactive columns, primary key columns (DAL auto-generates UUID PKs), or audit columns
   * (updated, created, updatedBy, createdBy — Hibernate manages these). Extracted to collapse
   * multiple {@code continue} guards into a single one (S135).
   */
  private static boolean shouldSkipColumn(Column col, Entity dalEntity) {
    if (!col.isActive()) {
      return true;
    }
    if (Boolean.TRUE.equals(col.isKeyColumn())) {
      return true;
    }
    Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
    return prop != null && prop.isAuditInfo();
  }

  /**
   * Attempt to inject a default value for a single column into the body.
   * Tries field default resolution first, then session context, then parent values. For
   * mandatory columns only, falls back to combo first-option preselection and a safe
   * numeric/boolean default to avoid NOT NULL violations.
   *
   * @param mandatory whether the column is NOT-NULL (mandatory). Non-mandatory columns
   *                  run only the genuine default-resolution passes and stop; the
   *                  aggressive NOT-NULL fallbacks are gated behind this flag (ETP-4274).
   */
  private static void injectMandatoryDefaultForColumn(JSONObject body, Entity dalEntity,
      Column col, MandatoryDefaultContext mCtx, boolean mandatory) {
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

      if (tryResolveFieldDefault(body, propName, col, mCtx, prop)) {
        return;
      }
      if (tryInjectFromSession(body, dalEntity, propName, col, mCtx, prop)) {
        return;
      }
      if (tryInjectFromParentValues(body, dalEntity, propName, col, mCtx.parentValues, prop)) {
        return;
      }
      // ETP-4274: non-mandatory columns stop after the genuine default-resolution passes
      // above. Do NOT apply the combo first-option preselection or the safe-type fallback
      // — those exist only to avoid NOT NULL violations on mandatory columns. Applying
      // them to optional columns would over-inject (and reintroduce the kind of silent FK
      // pick removed in ETP-3894).
      if (!mandatory) {
        return;
      }
      // ETP-3894: tryInjectFallbackFkDefault was removed here — it silently picked the
      // first active record for ANY column ending in _ID (including Search-type FKs like
      // C_BPartner_ID / Contact) without checking the reference type. That caused
      // documents to be saved with the wrong business partner when the user clicked Save
      // without choosing one. tryInjectFirstFromLookup is kept because it only fires for
      // combo-style references (TableDir/Table/List), matching legitimate FIC parity.
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
   *
   * <p>Looks up the ETGO_SF_FIELD per-window default override from {@code mCtx.sfFieldDefaults}
   * and passes it via {@code FieldDefaultRequest#withSfFieldDefault} so that {@code
   * NeoDefaultsService#resolveFieldDefault(FieldDefaultRequest)} honours window-level
   * customisations (e.g. {@code calculateType = "TI"}) exactly as the {@code /defaults}
   * endpoint does. Columns that have no ETGO_SF_FIELD entry receive {@code null}, preserving
   * the existing AD_Column fallback behaviour unchanged.
   *
   * @return true if a value was injected, false otherwise
   */
  private static boolean tryResolveFieldDefault(JSONObject body, String propName, Column col,
      MandatoryDefaultContext mCtx, Property prop) {
    try {
      // Look up the ETGO_SF_FIELD override for this column (null if not configured)
      String sfFieldDefault = mCtx.sfFieldDefaults.get(
          col.getDBColumnName().toUpperCase(Locale.ROOT));
      Object resolved = NeoDefaultsService.resolveFieldDefault(
          new NeoDefaultsService.FieldDefaultRequest(col, mCtx.parentId, mCtx.vars,
              mCtx.conn, mCtx.windowId, mCtx.neoCtx)
              .withSfFieldDefault(sfFieldDefault)
              .withParentValues(mCtx.parentValues));
      if (resolved != null) {
        applyResolvedDefault(body, col, propName, resolved, mCtx.neoCtx, prop);
        NeoDefaultsService.tryInjectIdentifier(body,
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
      Column col, MandatoryDefaultContext mCtx, Property prop) {
    try {
      String dbColName = col.getDBColumnName();
      VariablesSecureApp vars = mCtx.vars;
      String fromSession = vars.getSessionValue("#" + dbColName);
      if (fromSession == null || fromSession.isEmpty()) {
        fromSession = vars.getSessionValue(dbColName);
      }
      if (fromSession != null && !fromSession.isEmpty()) {
        applyResolvedDefault(body, col, propName, fromSession, mCtx.neoCtx, prop);
        NeoDefaultsService.tryInjectIdentifier(body, dalEntity, propName, body.opt(propName));
        log.debug("Injected from session context: {} = {}", propName, body.opt(propName));
        return true;
      }
    } catch (Exception e) {
      log.debug("Could not read session value for {}: {}", col.getDBColumnName(), e.getMessage());
    }
    return false;
  }

  private static boolean tryInjectFromParentValues(JSONObject body, Entity dalEntity,
      String propName, Column col, Map<String, Object> parentValues, Property prop) {
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
      applyResolvedDefault(body, col, propName, value, null, prop);
      NeoDefaultsService.tryInjectIdentifier(body, dalEntity, propName, body.opt(propName));
      log.debug("Injected parent fallback default: {} = {}", propName, body.opt(propName));
      return true;
    } catch (Exception e) {
      log.debug("Could not inject parent fallback for {}: {}", propName, e.getMessage());
      return false;
    }
  }

  private static void applyResolvedDefault(JSONObject body, Column col,
      String propName, Object resolved, NeoContext ctx, Property prop) throws Exception {
    if (resolved == null) {
      return;
    }
    // FK columns with a legacy "no selection" sentinel default ("0" or "-1") — OBDal cannot
    // resolve either as a real entity ID (ETP-4904: "-1" is Etendo Classic's UI sentinel for
    // "nothing selected" and is never meant to be persisted as a real FK value; it blew up NEO
    // Headless inserts with "New object ... refered to but not present in the import set").
    // For doctype columns, try to resolve the actual default from C_DocType table first.
    String resolvedStr = String.valueOf(resolved);
    if (("0".equals(resolvedStr) || "-1".equals(resolvedStr))
        && col.getDBColumnName().toUpperCase().endsWith("_ID")) {
      String docTypeId = DocTypeResolver.resolveDefaultDocTypeId(col, ctx);
      if (docTypeId != null) {
        body.put(propName, docTypeId);
        log.debug("Resolved doctype default for {}: {}", propName, docTypeId);
        return;
      }
      log.debug("Skipping FK default '{}' for {}", resolvedStr, propName);
      return;
    }
    // Coerce numeric String defaults to their proper Java type so DAL validation passes.
    // SQL defaults (e.g. lineNo from COALESCE(MAX(Line),0)+10) arrive as String from rs.getString().
    // The target type is decided by the DAL property, NOT by the column name: a String property
    // whose default happens to be all digits (e.g. a List-reference code like
    // BusinessPartner.invoiceGrouping = "000000000000000") must stay a String — coercing it to a
    // number corrupts the value and fails the List-reference validator (ETP-4668). Mirrors the
    // type check in NeoTypeCoercionHelper.coerceField used on the create path.
    Object valueToStore = resolved;
    if (resolved instanceof String && isNumericProperty(prop)) {
      valueToStore = coerceNumericStringToPropertyType((String) resolved, prop);
    }
    body.put(propName, valueToStore);
    log.debug("[NEO-DEFAULTS] {} = {} ({})", propName, valueToStore,
        valueToStore == null ? "null" : valueToStore.getClass().getSimpleName());
  }

  /**
   * True when the DAL property is a primitive numeric type ({@link java.math.BigDecimal},
   * {@link Long} or {@link Integer}). FK (non-primitive) and String properties return false, so
   * their String defaults are never coerced to numbers — the fix for ETP-4668, where an all-digit
   * List-reference code on a String property was silently turned into a number.
   */
  private static boolean isNumericProperty(Property prop) {
    if (prop == null || !prop.isPrimitive()) {
      return false;
    }
    Class<?> type = prop.getPrimitiveObjectType();
    return type != null
        && (java.math.BigDecimal.class.isAssignableFrom(type)
            || Long.class.isAssignableFrom(type)
            || Integer.class.isAssignableFrom(type));
  }

  /**
   * Coerces a numeric String default to the exact Java type declared by the DAL property,
   * mirroring {@link com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper#coerceField}. If the
   * string cannot be parsed as that numeric type the original String is returned unchanged.
   */
  private static Object coerceNumericStringToPropertyType(String resolved, Property prop) {
    String strVal = resolved.trim();
    try {
      Class<?> type = prop.getPrimitiveObjectType();
      if (java.math.BigDecimal.class.isAssignableFrom(type)) {
        return new java.math.BigDecimal(strVal);
      }
      if (Long.class.isAssignableFrom(type)) {
        return new java.math.BigDecimal(strVal).longValue();
      }
      if (Integer.class.isAssignableFrom(type)) {
        return Integer.parseInt(strVal);
      }
    } catch (Exception ex) {
      log.debug("Could not coerce '{}' to numeric type for property {}: keeping as String — {}",
          strVal, prop.getName(), ex.getMessage());
    }
    return resolved;
  }

  private static boolean isAuditColumn(Column col) {
    String colNameUpper = col.getDBColumnName().toUpperCase();
    return "CREATED".equals(colNameUpper)
        || "UPDATED".equals(colNameUpper)
        || "CREATEDBY".equals(colNameUpper)
        || "UPDATEDBY".equals(colNameUpper);
  }

  /**
   * Build a map of ETGO_SF_FIELD per-window default expressions, keyed by DB column name
   * (upper-case). Only non-blank {@code ETGO_SF_FIELD.defaultvalue} entries are included.
   *
   * <p>Uses the same OBCriteria query as {@link NeoDefaultsService#resolveDefaults} (active +
   * included SFFields for the entity) so the CREATE path and the {@code /defaults} endpoint see
   * the same set of per-window overrides. If {@code ctx.getSfEntity()} is null or no SFFields
   * are found an empty map is returned — columns without an entry fall back to the AD_Column
   * default, preserving existing behaviour.</p>
   *
   * @param ctx the NeoContext whose sfEntity provides the entity ID
   * @return map of DB_COLUMN_NAME.toUpperCase() → ETGO_SF_FIELD.defaultvalue
   */
  private static Map<String, String> buildSfFieldDefaultsMap(NeoContext ctx) {
    Map<String, String> result = new HashMap<>();
    if (ctx == null || ctx.getSfEntity() == null) {
      return result;
    }
    // ETGO_SF_FIELD rows are System data (client 0). The CREATE path runs as the end-user's
    // client (e.g. a tenant role), which cannot see them under the normal client filter — so
    // the query must run in admin mode, mirroring resolveDefaults. Without this the map comes
    // back empty for tenant users and the create falls back to the AD_Column default.
    OBContext.setAdminMode(true);
    try {
      OBCriteria<SFField> fieldCrit = OBDal.getInstance().createCriteria(SFField.class);
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id",
          ctx.getSfEntity().getId()));
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
      List<SFField> fields = fieldCrit.list();
      for (SFField sfField : fields) {
        Column adColumn = sfField.getADColumn();
        if (adColumn == null) {
          continue;
        }
        String defaultValue = sfField.getDefaultValue();
        if (defaultValue != null && !defaultValue.trim().isEmpty()) {
          result.put(adColumn.getDBColumnName().toUpperCase(Locale.ROOT), defaultValue.trim());
        }
      }
    } catch (Exception e) {
      log.debug("Could not build sfFieldDefaults map for entity {}: {}",
          ctx.getSfEntity().getId(), e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
    return result;
  }

  /**
   * ETP-3894: tryInjectFallbackFkDefault's replacement — only fires for combo-style references
   * (TableDir/Table/List), matching legitimate FIC parity, via {@code
   * NeoDefaultsService#resolveFirstComboOption}.
   */
  private static boolean tryInjectFirstFromLookup(JSONObject body, Entity dalEntity,
      String propName, Column col, NeoContext ctx) {
    Object firstId = NeoDefaultsService.resolveFirstComboOption(col, ctx);
    if (firstId == null) {
      return false;
    }
    try {
      body.put(propName, firstId);
      NeoDefaultsService.tryInjectIdentifier(body, dalEntity, propName, firstId.toString());
      log.debug("Auto-injected first combo option: {} = {}", propName, firstId);
      return true;
    } catch (Exception e) {
      log.debug("Could not auto-pick first combo option for {}: {}",
          col.getDBColumnName(), e.getMessage());
      return false;
    }
  }

}
