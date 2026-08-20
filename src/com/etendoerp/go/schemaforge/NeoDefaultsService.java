package com.etendoerp.go.schemaforge;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;
import com.etendoerp.sequences.SequenceUtils;

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
  private static final String LOG_SEQUENCE_PREVIEW_FAILURE = "Could not generate sequence preview for {}: {}";

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
        JSONArray sequenceFields = new JSONArray();

        // Build a VariablesSecureApp bridge from OBContext for Etendo utility methods.
        // Pass the AD_Tab so isSOTrx is set in session — @IsSOTrx@ inside @SQL= defaults
        // (e.g. M_PriceList_ID on C_Order) needs it to pick the correct sales/purchase row.
        VariablesSecureApp vars = buildVariablesSecureApp(ctx.getObContext(), ctx.getAdTab());
        DalConnectionProvider conn = new DalConnectionProvider(false);

        // Resolve window ID from SFSpec -> AD_Window (needed by Utility.getDefault)
        String windowId = resolveWindowId(ctx.getSfEntity());

        // Evaluate the tab's auxiliary inputs into the session BEFORE resolving column defaults.
        // A column default may reference an auxiliary input by name — e.g. the GL Journal line
        // Description defaults to @DESCRIPTION1@, where the Lines tab's DESCRIPTION1 aux input
        // selects the parent journal's description. Classic does this in
        // FormInitializationComponent.computeAuxiliaryInputs; without it @DESCRIPTION1@ resolves
        // to empty in NEO. Loading parent values here also lets @ParentColumn@ tokens inside the
        // aux SQL (e.g. @GL_Journal_ID@) resolve from the parent record.
        Map<String, Object> auxParentValues = NeoParentValuesLoader.load(ctx.getAdTab(), parentId);
        NeoAuxiliaryInputResolver.injectAuxiliaryInputs(ctx.getAdTab(), windowId, vars, conn,
            auxParentValues);

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
        JSONArray unresolvedFields = new JSONArray();

        List<SFField> sequenceSFFields = new ArrayList<>();
        JSONObject defaults = new JSONObject();

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
            // For combo-style references (TableDir/Table/List) with no explicit default,
            // mirror FIC parity and preselect the first available option. Search-type
            // references (ref 30, OBUISEL) are excluded by resolveFirstComboOption, so
            // Contact/BP fields remain empty. The genuinely dangerous fallback that picked
            // the first record for ANY FK column (tryInjectFallbackFkDefault) was removed
            // in ETP-3894 — only that one auto-picked Search-type fields silently.
            // Readonly SFFields are gated out: the user cannot correct an auto-picked value
            // in a hidden/readonly field, so preselecting "the first row of the referenced
            // table" is always wrong for them (e.g. self-referential FKs like
            // Replacedorder_ID would silently mark every new document as a replacement).
            applyDefaultWithComboFallback(ctx, sfField, resolvedValue, adColumn, defaults, propertyName, dalEntity);
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
            String preview;
            preview = NeoSequencePreviewHelper.resolveSequencePreviewForColumn(
                adColumn, vars, conn, windowId, docTypeTargetId, docTypeId);
            if (preview != null) {
              defaults.put(propertyName, preview);
              sequenceFields.put(propertyName);
            }
          } catch (Exception e) {
            log.debug(LOG_SEQUENCE_PREVIEW_FAILURE,
                dbColumnName, e.getMessage());
            unresolvedFields.put(propertyName);
          }
        }

        // Keep cascade enabled for /defaults to preserve the compatibility behavior chosen
        // for this merge: dependent defaults should still be derived during form bootstrap.
        Tab adTab = applyCascadeAndResolveTab(ctx, sequenceFields, defaults);

        // ETP-3894: FK preselection is intentionally disabled. Mandatory FKs without an
        // explicit AD_Column default / ETGO_SF_FIELD default / session value / parent value
        // are left empty so the user is forced to make an explicit selection and Save fails
        // with MISSING_REQUIRED_FIELDS instead of silently picking the first lookup row.
        // The CREATE path keeps its own broader fallback in injectMandatoryDefaults to avoid
        // NOT NULL violations when partial payloads reach persistence.

        // Pass 3: resolve defaults for mandatory columns NOT in ETGO_SF_FIELD.
        // These are "hidden required" fields (e.g. transactionDocument, priceList) that
        // the agent needs to see in the /defaults response even though they are not
        // exposed in the UI. Without this, the agent may omit them on create and hit
        // NOT NULL or MISSING_REQUIRED_FIELDS errors.
        Set<String> sfFieldColumns = getSfFieldColumns(fields);
        NeoHiddenMandatoryDefaultsResolver.resolve(
            new NeoHiddenMandatoryDefaultsResolver.Request(defaults, dalEntity, adTab)
                .withParentValues(NeoParentValuesLoader.load(adTab, parentId))
                .withDefaultResolver((column, parentValues) -> {
                  Object resolved = resolveFieldDefault(new FieldDefaultRequest(column, parentId,
                      vars, conn, windowId, ctx).withParentValues(parentValues));
                  return resolveOrFirstComboOption(ctx, column, resolved);
                })
                .withIdentifierInjector(NeoDefaultsService::tryInjectIdentifier)
                .withSfFieldColumns(sfFieldColumns));

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

  private static @Nullable Object resolveOrFirstComboOption(NeoContext ctx, Column column, Object resolved) {
    return resolved != null ? resolved : resolveFirstComboOption(column, ctx);
  }

  private static void applyDefaultWithComboFallback(NeoContext ctx, SFField sfField, Object resolvedValue,
      Column adColumn, JSONObject defaults, String propertyName, Entity dalEntity) throws JSONException {
    if (resolvedValue == null && !Boolean.TRUE.equals(sfField.isReadOnly())) {
      resolvedValue = resolveFirstComboOption(adColumn, ctx);
    }
    if (resolvedValue != null) {
      // Coerce "Y"/"N" string defaults to JSON boolean for Yes/No (boolean) properties.
      // Reuses the same Boolean detection that NeoTypeCoercionHelper.coerceField uses on the
      // create path: check prop.getPrimitiveObjectType() == Boolean.class (line 156 of that file).
      resolvedValue = coerceBooleanDefault(dalEntity, propertyName, resolvedValue);
      defaults.put(propertyName, resolvedValue);
      // For FK fields, also inject $_identifier so selectors display the label, not the ID
      tryInjectIdentifier(defaults, dalEntity, propertyName, resolvedValue);
    }
  }

  /**
   * If {@code value} is the string {@code "Y"} or {@code "N"} and the DAL property for
   * {@code propertyName} is a {@link Boolean} primitive type, returns the corresponding
   * {@code Boolean} ({@code true} for "Y", {@code false} for "N"/"anything else").
   * In all other cases the original value is returned unchanged.
   *
   * <p>This mirrors the coercion applied on the create path by
   * {@code NeoTypeCoercionHelper.coerceField} (Boolean branch, line ~157).
   */
  private static Object coerceBooleanDefault(Entity dalEntity, String propertyName, Object value) {
    if (dalEntity == null || !(value instanceof String)) {
      return value;
    }
    try {
      Property prop = dalEntity.getProperty(propertyName);
      if (prop != null && prop.isPrimitive()) {
        Class<?> type = prop.getPrimitiveObjectType();
        if (type != null && Boolean.class.isAssignableFrom(type)) {
          String strVal = (String) value;
          return "Y".equals(strVal) || "true".equalsIgnoreCase(strVal);
        }
      }
    } catch (Exception e) {
      log.debug("Could not coerce boolean default for property '{}': {}", propertyName, e.getMessage());
    }
    return value;
  }

  private static @NonNull Set<String> getSfFieldColumns(List<SFField> fields) {
    Set<String> sfFieldColumns = new HashSet<>();
    if (fields != null) {
      for (SFField sfField : fields) {
        if (sfField == null) {
          continue;
        }
        Column adColumn = sfField.getADColumn();
        String dbColumnName = adColumn != null ? adColumn.getDBColumnName() : null;
        if (dbColumnName != null) {
          sfFieldColumns.add(dbColumnName.toUpperCase(Locale.ROOT));
        }
      }
    }
    return sfFieldColumns;
  }

  private static @Nullable Tab applyCascadeAndResolveTab(NeoContext ctx, JSONArray sequenceFields,
      JSONObject defaults) throws JSONException {
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
    return adTab;
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
    return resolveFieldDefault(new FieldDefaultRequest(adColumn, parentId, vars, conn, windowId,
        ctx));
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
    return resolveFieldDefault(new FieldDefaultRequest(adColumn, parentId, vars, conn, windowId,
        ctx).withSfFieldDefault(sfFieldDefault));
  }

  private static final class FieldDefaultRequest {
    private final Column adColumn;
    private final String parentId;
    private final VariablesSecureApp vars;
    private final DalConnectionProvider conn;
    private final String windowId;
    private final NeoContext ctx;
    private String sfFieldDefault;
    private Map<String, Object> parentValues;

    private FieldDefaultRequest(Column adColumn, String parentId, VariablesSecureApp vars,
        DalConnectionProvider conn, String windowId, NeoContext ctx) {
      this.adColumn = Objects.requireNonNull(adColumn, "adColumn is required");
      this.parentId = parentId;
      this.vars = vars;
      this.conn = conn;
      this.windowId = windowId;
      this.ctx = ctx;
    }

    private FieldDefaultRequest withSfFieldDefault(String sfFieldDefault) {
      this.sfFieldDefault = sfFieldDefault;
      return this;
    }

    private FieldDefaultRequest withParentValues(Map<String, Object> parentValues) {
      this.parentValues = parentValues;
      return this;
    }
  }

  private static Object resolveFieldDefault(FieldDefaultRequest request) {
    Column adColumn = request.adColumn;
    String dbColumnName = adColumn.getDBColumnName();

    // NEO-specific: IsActive always defaults to true
    if ("IsActive".equalsIgnoreCase(dbColumnName)) {
      return true;
    }

    // NEO-specific: Link-to-parent columns use the parentId from query params.
    // Guard: only apply parentId to the column whose referenced entity matches the parent
    // tab's table. Some child tabs have multiple isParent='Y' columns (e.g. A_Amortizationline
    // has both A_Amortization_ID and A_Asset_ID flagged as parent). Without this guard, all
    // of them would receive the header id, which is wrong for any FK that references a
    // different table (A_Asset in this case). Falls through to normal default resolution
    // when the column references a different entity than the parent tab's table.
    if (Boolean.TRUE.equals(adColumn.isLinkToParentColumn())
        && request.parentId != null && !request.parentId.isEmpty()
        && isColumnReferencingParentTab(adColumn, request.ctx)) {
      return request.parentId;
    }

    // Sequence/DocumentNo fields — use Utility.getDocumentNo for real preview
    if (isSequenceField(adColumn)) {
      String preview = NeoSequencePreviewHelper.resolveSequencePreview(adColumn, request.vars,
          request.conn, request.windowId, request.ctx);
      if (preview != null) {
        return preview;
      }
    }

    // ETGO_SF_FIELD.defaultvalue overrides AD_Column.defaultvalue when set
    String defaultExpr = (request.sfFieldDefault != null
        && !request.sfFieldDefault.trim().isEmpty())
        ? request.sfFieldDefault.trim()
        : adColumn.getDefaultValue();
    if (defaultExpr == null || defaultExpr.trim().isEmpty()) {
      return resolveFromPrefsOrDocType(adColumn, request.vars, request.conn, request.windowId,
          dbColumnName, request.ctx);
    }

    return resolveNonEmptyDefaultExpr(defaultExpr.trim(), adColumn, dbColumnName, request);
  }

  /**
   * Resolves a non-blank AD_Column/ETGO_SF_FIELD default expression, once the early
   * NEO-specific cases (IsActive, link-to-parent, sequence) have already been ruled out
   * by {@link #resolveFieldDefault(FieldDefaultRequest)}. Extracted to keep that method's
   * cognitive complexity within SonarQube's limit — pure extraction, no behavior change.
   */
  private static Object resolveNonEmptyDefaultExpr(String defaultExpr, Column adColumn,
      String dbColumnName, FieldDefaultRequest request) {
    // Handle empty-string literal
    if ("\"\"".equals(defaultExpr)) {
      return "";
    }

    // SQL expressions — resolve parameters and execute
    if (defaultExpr.startsWith("@SQL=")) {
      return NeoDefaultsSqlHelper.resolveSQLDefault(defaultExpr, request.vars, request.conn,
          request.windowId, adColumn, request.parentValues);
    }

    // List-reference columns (AD_Reference_ID = "17") with a pure literal default (no "@"
    // context/preference token) must return that literal verbatim. Their AD_Ref_List values
    // are opaque codes — often all-digit strings like "000000000000000" (see Invoicegrouping,
    // a 15-digit binary code) — and Utility.getDefault treats a plain literal as a numeric
    // candidate, collapsing it to "0" and losing the leading zeros / length. That produces a
    // value that matches none of the column's real AD_Ref_List entries.
    if (!defaultExpr.contains("@") && isListReference(adColumn)) {
      return defaultExpr;
    }

    // Delegate to Utility.getDefault for all other cases:
    // - Literal values (no @ signs)
    // - Context variables (@#AD_Org_ID@, @#Date@, etc.)
    // - Preferences (checked first by Utility.getDefault)
    // - Comma-separated alternatives (@#Var1@,@#Var2@,literal)
    String resolved = Utility.getDefault(request.conn, request.vars, dbColumnName, defaultExpr,
        request.windowId, "");

    if (resolved != null && !resolved.isEmpty()) {
      return resolved;
    }

    return null;
  }

  /**
   * AD_Reference id for the "List" reference type (fixed, pre-defined AD_Ref_List values).
   */
  private static final String REFERENCE_ID_LIST = "17";

  /**
   * Returns true if the column's reference is the "List" type — a fixed set of AD_Ref_List
   * codes, as opposed to a numeric (Integer/Amount/Quantity) or Search/TableDir reference.
   */
  private static boolean isListReference(Column adColumn) {
    return adColumn.getReference() != null
        && REFERENCE_ID_LIST.equals(adColumn.getReference().getId());
  }

  /**
   * Returns true only when the given column's referenced entity (target entity via DAL model)
   * matches the parent tab's table entity.
   *
   * <p>Some child tabs have multiple {@code isParent='Y'} columns — for example,
   * {@code A_Amortizationline} flags both {@code A_Amortization_ID} (the true parent FK to the
   * header document) and {@code A_Asset_ID} (an additional FK to A_Asset) as parent columns in AD.
   * Without this check every {@code isParent} column would receive the header id, which is wrong
   * for {@code A_Asset_ID} whose referenced table is {@code A_Asset}, not {@code A_Amortization}.
   *
   * <p>Falls back to {@code true} (permissive) when the parent tab cannot be determined (root
   * tab, missing context, or resolution error), preserving the pre-fix behavior for normal cases.
   *
   * @param adColumn the AD column being evaluated
   * @param ctx      the NeoContext providing the child tab
   * @return {@code true} if {@code parentId} should be applied to this column
   */
  private static boolean isColumnReferencingParentTab(Column adColumn, NeoContext ctx) {
    try {
      Tab childTab = ctx != null ? ctx.getAdTab() : null;
      if (childTab == null) {
        return true; // no tab context — fall back to legacy behavior
      }
      Tab parentTab = org.openbravo.client.kernel.KernelUtils.getInstance()
          .getParentTab(childTab);
      if (parentTab == null || parentTab.getTable() == null) {
        return true; // root tab or no parent — only one parent possible, allow it
      }
      Entity parentEntity = ModelProvider.getInstance()
          .getEntityByTableId(parentTab.getTable().getId());
      if (parentEntity == null) {
        return true;
      }
      // Resolve the DAL property for this column and compare its target entity
      Entity childEntity = ModelProvider.getInstance()
          .getEntityByTableId(childTab.getTable().getId());
      if (childEntity == null) {
        return true;
      }
      Property prop = childEntity.getPropertyByColumnName(adColumn.getDBColumnName(), false);
      if (prop == null || prop.getTargetEntity() == null) {
        return true; // non-FK or unresolvable — preserve legacy behavior
      }
      return parentEntity.equals(prop.getTargetEntity());
    } catch (Exception e) {
      log.debug("Could not determine parent entity for column {}: {}",
          adColumn.getDBColumnName(), e.getMessage());
      return true; // on error, preserve legacy behavior
    }
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
      String dbDefault = NeoDefaultsSqlHelper.resolveDbColumnDefault(
          adColumn.getTable().getDBTableName(), dbColumnName);
      if (dbDefault != null) {
        return dbDefault;
      }
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
   * Preview for transactional sequences (new AD_Sequence mechanism, detected via
   * SequenceUtils.isSequence). Looks up the sequence by column + current organization and
   * returns the current nextAssignedNumber without consuming it.
   */
  static String resolveTransactionalSequencePreview(Column adColumn) {
    try {
      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
      OBCriteria<Sequence> crit = OBDal.getInstance().createCriteria(Sequence.class);
      crit.add(Restrictions.eq(Sequence.PROPERTY_COLUMN, adColumn));
      crit.add(Restrictions.eq(Sequence.PROPERTY_ORGANIZATION,
          OBDal.getInstance().get(Organization.class, orgId)));
      crit.setMaxResults(1);
      Sequence seq = (Sequence) crit.uniqueResult();
      if (seq != null) {
        return "<" + seq.getNextAssignedNumber() + ">";
      }
    } catch (Exception e) {
      log.debug(LOG_SEQUENCE_PREVIEW_FAILURE, adColumn.getDBColumnName(), e.getMessage());
    }
    return null;
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
    // The shared builder pulls identity + number-format vars from
    // NeoSessionVarsCache and applies per-tab IsSOTrx on a fresh
    // VariablesSecureApp, so there is no need for a per-call cache here. #Date
    // changes daily and is intentionally NOT cached: we set it per request.
    VariablesSecureApp vars = NeoCalloutService.buildVars(obContext, adTab);
    vars.setSessionValue("#Date",
        new SimpleDateFormat(DATE_FORMAT).format(new Date()));
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
      VariablesSecureApp vars = buildVariablesSecureApp(ctx.getObContext(), adTab);
      DalConnectionProvider conn = new DalConnectionProvider(false);
      String windowId = ctx.getSfEntity() != null ? resolveWindowId(ctx.getSfEntity()) : "";
      Map<String, Object> parentValues = NeoParentValuesLoader.load(adTab, parentId);

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
      for (Column col : adTab.getTable().getADColumnList()) {
        if (!col.isActive()) {
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
        injectMandatoryDefaultForColumn(body, dalEntity, col, mCtx, col.isMandatory());
      }

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
   * and passes it via {@link FieldDefaultRequest#withSfFieldDefault} so that
   * {@link #resolveFieldDefault(FieldDefaultRequest)} honours window-level customisations
   * (e.g. {@code calculateType = "TI"}) exactly as the {@code /defaults} endpoint does.
   * Columns that have no ETGO_SF_FIELD entry receive {@code null}, preserving the existing
   * AD_Column fallback behaviour unchanged.
   *
   * @return true if a value was injected, false otherwise
   */
  private static boolean tryResolveFieldDefault(JSONObject body, String propName, Column col,
      MandatoryDefaultContext mCtx, Property prop) {
    try {
      // Look up the ETGO_SF_FIELD override for this column (null if not configured)
      String sfFieldDefault = mCtx.sfFieldDefaults.get(
          col.getDBColumnName().toUpperCase(Locale.ROOT));
      Object resolved = resolveFieldDefault(new FieldDefaultRequest(col, mCtx.parentId, mCtx.vars,
          mCtx.conn, mCtx.windowId, mCtx.neoCtx)
          .withSfFieldDefault(sfFieldDefault)
          .withParentValues(mCtx.parentValues));
      if (resolved != null) {
        applyResolvedDefault(body, col, propName, resolved, mCtx.neoCtx, prop);
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
      tryInjectIdentifier(body, dalEntity, propName, body.opt(propName));
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
   * <p>Uses the same OBCriteria query as {@link #resolveDefaults} (active + included SFFields
   * for the entity) so the CREATE path and the {@code /defaults} endpoint see the same set of
   * per-window overrides. If {@code ctx.getSfEntity()} is null or no SFFields are found an
   * empty map is returned — columns without an entry fall back to the AD_Column default,
   * preserving existing behaviour.</p>
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

  // ---------------------------------------------------------------------------
  // FIC combo preselection helpers (restored in ETP-3894 correction)
  // These only fire for combo-style references (TableDir/Table/List) — they
  // mirror Etendo Classic FIC parity and are safe because they exclude Search
  // and OBUISEL references via isFICComboReference + hasObuiselSelector.
  // ---------------------------------------------------------------------------

  private static boolean isFICComboReference(String baseRefId) {
    return NeoSelectorService.REF_TABLEDIR.equals(baseRefId)
        || NeoSelectorService.REF_TABLE.equals(baseRefId)
        || NeoSelectorService.REF_LIST.equals(baseRefId);
  }

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

  private static Object resolveFirstComboOption(Column col, NeoContext ctx) {
    try {
      String baseRefId = NeoSelectorService.getBaseReferenceId(col);
      if (!isFICComboReference(baseRefId)) {
        return null;
      }
      if (NeoSelectorService.hasObuiselSelector(col)) {
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

  // ── Background entity defaults (non-HTTP callers) ───────────────────────
  // ETP-4888: header-builder methods that construct a document (Invoice,
  // ShipmentInOut, ...) directly via OBProvider/manual setters — instead of
  // going through NeoCrudHandler's HTTP "new record" path — never triggered
  // this class's declared-derivation resolution. Any field whose value comes
  // purely from a contract.json derivation (callout/fromConfig/lookup) and is
  // never set by hand in the builder was silently left null (e.g. SII/SIF
  // fields like etsgDateOperation, aeatsiiFechaRegCont). This section gives
  // those background callers a non-HTTP entry point into the same resolution
  // pass that /defaults already exposes over HTTP.

  /**
   * Resolves declared field derivations (contract.json defaults/callouts/lookups configured via
   * ETGO_SF_FIELD) for a NEO-registered spec/entity and applies them onto a header entity built
   * directly by a background Java caller — i.e. one that constructs its bean via
   * {@code OBProvider}/manual setters instead of going through the normal NEO CRUD "new record"
   * HTTP path (which calls {@link #resolveDefaults} automatically via {@code GET .../defaults}
   * during form bootstrap). Without this, any field whose value comes purely from a declared
   * derivation — never set by hand in the builder — is silently left {@code null}.
   *
   * <p>Only fields the caller left blank are touched: a property already carrying a non-blank
   * value on {@code entity} is never overwritten, mirroring the "skip if already present" rule
   * {@link #injectMandatoryDefaults} applies to the request body on the HTTP create path — so
   * fields the builder set explicitly (order, business partner, currency, accounting date, etc.)
   * always win over a generic derivation. Primitive properties are coerced and set directly;
   * FK-typed (non-primitive) properties are resolved via {@code OBDal.getInstance().get(target,
   * id)} — mirroring the same lookup {@link #tryInjectIdentifier} performs on the HTTP path — and
   * only set when the referenced record actually exists, otherwise the field is left untouched.
   *
   * <p>Failures anywhere in this method (missing spec/entity, resolution error, coercion
   * failure) are swallowed and logged — a background document-creation flow must never fail
   * because an optional declared default could not be resolved.
   *
   * @param specName   NEO spec name (kebab-case, e.g. {@code "sales-invoice"})
   * @param entityName NEO entity name within the spec (e.g. {@code "header"})
   * @param entity     the already-populated header entity to enrich; a no-op if {@code null}
   * @param parentId   optional id of the source document (order/shipment/receipt) this entity is
   *                   being created from, used for {@code fromParent}-style derivations
   */
  public static void applyDeclaredDefaultsToBackgroundEntity(String specName, String entityName,
      BaseOBObject entity, String parentId) {
    if (entity == null || StringUtils.isBlank(specName) || StringUtils.isBlank(entityName)) {
      return;
    }
    try {
      JSONObject defaults = resolveBackgroundDefaults(specName, entityName, parentId);
      if (defaults == null || defaults.length() == 0) {
        return;
      }
      Entity dalEntity = entity.getEntity();
      Iterator<String> keys = defaults.keys();
      while (keys.hasNext()) {
        String propName = keys.next();
        if (propName.endsWith("$_identifier")) {
          continue;
        }
        applyDeclaredDefaultIfMissing(entity, dalEntity, propName, defaults.opt(propName));
      }
    } catch (Exception e) {
      log.error("Could not apply declared defaults for {}/{}: {}", specName, entityName,
          e.getMessage(), e);
    }
  }

  /**
   * Looks up the SFSpec/SFEntity/AD_Tab for {@code specName}/{@code entityName} and delegates to
   * {@link #resolveDefaults}, returning just the {@code defaults} JSON object (or {@code null}
   * if the spec/entity cannot be resolved or the underlying call fails).
   */
  private static JSONObject resolveBackgroundDefaults(String specName, String entityName,
      String parentId) throws JSONException {
    SFSpec spec = NeoServletSupport.findSpec(specName);
    if (spec == null) {
      log.debug("No SFSpec found for '{}' — skipping background declared-default resolution",
          specName);
      return null;
    }
    SFEntity sfEntity = findBackgroundEntity(spec.getId(), entityName);
    if (sfEntity == null) {
      log.debug("No SFEntity '{}' found for spec '{}' — skipping background declared-default "
          + "resolution", entityName, specName);
      return null;
    }
    Tab adTab = sfEntity.getADTab();
    NeoContext ctx = NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .endpointType(NeoEndpointType.CRUD)
        .build();

    NeoResponse response = resolveDefaults(ctx, parentId);
    if (response == null || response.getHttpStatus() != 200 || response.getBody() == null) {
      return null;
    }
    return response.getBody().optJSONObject("defaults");
  }

  /**
   * Finds an active, included {@link SFEntity} by parent spec ID and entity name. The same
   * five-line criteria query is already duplicated across {@code NeoServlet#findEntity},
   * {@code BatchService#findEntity} and other siblings (pre-existing pattern, not introduced
   * here) — kept as a local private copy since this is the only non-HTTP caller of
   * {@link #resolveDefaults} and consolidating all of those copies is out of scope for this fix.
   */
  private static SFEntity findBackgroundEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, entityName, MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Applies a single resolved declared-default value onto {@code entity}, unless the property is
   * already carrying a non-blank value. All failures are swallowed and logged at debug level — an
   * unresolvable single field must never abort the rest of the pass.
   *
   * <p>FK-typed (non-primitive) properties are resolved via {@link #resolveFkDefaultTarget}, the
   * same {@code OBDal.getInstance().get(targetEntity, id)} lookup {@link #tryInjectIdentifier}
   * already uses on the HTTP {@code /defaults} path to turn a resolved id string into a display
   * identifier. If the referenced bean cannot be found (invalid id, unresolvable target entity),
   * the field is skipped and logged exactly like the previous primitive-only gate did — this
   * method never throws out of a failed FK resolution.
   */
  private static void applyDeclaredDefaultIfMissing(BaseOBObject entity, Entity dalEntity,
      String propName, Object rawValue) {
    if (rawValue == null || dalEntity == null) {
      return;
    }
    try {
      Property prop = dalEntity.getProperty(propName);
      if (prop == null) {
        return;
      }
      if (!isBlankValue(entity.get(propName))) {
        return; // caller already set this field explicitly — never clobber it
      }
      if (!prop.isPrimitive()) {
        applyDeclaredFkDefaultIfMissing(entity, prop, propName, rawValue);
        return;
      }
      Object coerced = coercePrimitiveDefault(prop, rawValue);
      if (coerced != null) {
        entity.set(propName, coerced);
        log.debug("Applied declared default on background entity {}: {} = {}",
            entity.getEntityName(), propName, coerced);
      }
    } catch (Exception e) {
      log.debug("Could not apply declared default for property '{}': {}", propName,
          e.getMessage());
    }
  }

  /**
   * FK-typed counterpart of the primitive branch in {@link #applyDeclaredDefaultIfMissing}.
   * {@code rawValue} is expected to be the id string a {@code @SQL=} lookup derivation resolves
   * to (e.g. {@code aeatsii_description_id} for {@code aeatsiiDescription}/
   * {@code aeatsiiPurDescription}) — mirrors what {@link #tryInjectIdentifier} already does with
   * such a value on the HTTP {@code /defaults} path, minus the {@code $_identifier} companion key
   * (background entities are never read back through the JSON selector UI, so there's no
   * identifier field to populate).
   *
   * <p>Resolution failures (blank id, no target entity, record not found, DAL error) are logged
   * at debug level and the field is left untouched — matching the "never abort the rest of the
   * pass" contract of the caller.
   */
  private static void applyDeclaredFkDefaultIfMissing(BaseOBObject entity, Property prop,
      String propName, Object rawValue) {
    BaseOBObject resolved = resolveFkDefaultTarget(prop, rawValue);
    if (resolved == null) {
      log.debug("Could not resolve FK declared default '{}' = '{}' on background entity {}",
          propName, rawValue, entity.getEntityName());
      return;
    }
    entity.set(propName, resolved);
    log.debug("Applied FK declared default on background entity {}: {} = {}",
        entity.getEntityName(), propName, resolved.getId());
  }

  /**
   * Resolves a raw id string to a DAL bean of {@code prop}'s target entity, or {@code null} if
   * the value is blank, the property has no target entity, or no record with that id exists.
   * Same lookup {@link #tryInjectIdentifier} performs (line ~360): {@code
   * OBDal.getInstance().get(targetEntity.getName(), id)}.
   */
  private static BaseOBObject resolveFkDefaultTarget(Property prop, Object rawValue) {
    if (rawValue == null) {
      return null;
    }
    String idStr = rawValue.toString().trim();
    if (idStr.isEmpty()) {
      return null;
    }
    Entity targetEntity = prop.getTargetEntity();
    if (targetEntity == null) {
      return null;
    }
    try {
      return OBDal.getInstance().get(targetEntity.getName(), idStr);
    } catch (Exception e) {
      log.debug("Could not resolve FK target '{}' for id '{}': {}", targetEntity.getName(),
          idStr, e.getMessage());
      return null;
    }
  }

  private static boolean isBlankValue(Object current) {
    if (current == null) {
      return true;
    }
    return current instanceof String && ((String) current).isEmpty();
  }

  /**
   * Coerces a raw declared-default value (as returned by {@link #resolveDefaults}, typically a
   * {@code String}) to the Java type the given primitive DAL property expects.
   *
   * <p>Date properties are parsed with the same {@code yyyy-MM-dd} format used for the
   * {@code @#Date@} session variable ({@link #DATE_FORMAT}) — {@link NeoTypeCoercionHelper}
   * does not cover dates, since on the HTTP path date coercion is handled downstream by
   * {@code DefaultJsonDataService}. Other numeric/boolean types delegate to
   * {@link NeoTypeCoercionHelper#coerceField}, the same coercion the HTTP create path uses via
   * {@code NeoTypeCoercionHelper.coerceTypes}.
   */
  private static Object coercePrimitiveDefault(Property prop, Object rawValue) {
    Class<?> type = prop.getPrimitiveObjectType();
    if (type == null) {
      return null;
    }
    if (Date.class.isAssignableFrom(type)) {
      return coerceDateDefault(prop, rawValue);
    }
    if (rawValue instanceof String) {
      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(prop.getEntity(), prop.getName(), (String) rawValue,
          coerced);
      return coerced.getOrDefault(prop.getName(), rawValue);
    }
    return rawValue; // already correctly typed (e.g. Boolean from coerceBooleanDefault)
  }

  private static Object coerceDateDefault(Property prop, Object rawValue) {
    if (rawValue instanceof Date) {
      return rawValue;
    }
    if (rawValue instanceof String && !((String) rawValue).isEmpty()) {
      try {
        return new SimpleDateFormat(DATE_FORMAT).parse((String) rawValue);
      } catch (java.text.ParseException e) {
        log.debug("Could not parse date default '{}' for property {}: {}",
            rawValue, prop.getName(), e.getMessage());
      }
    }
    return null;
  }

}
