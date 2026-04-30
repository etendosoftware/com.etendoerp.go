package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.selector.meta.AuxFieldMeta;
import com.etendoerp.go.schemaforge.selector.meta.SelectorContextResolver;
import com.etendoerp.go.schemaforge.selector.meta.SelectorDescriptorResolver;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;
import com.etendoerp.go.schemaforge.selector.policy.NeoSelectorPolicy;

/**
 * Generic dynamic selector service for FK fields.
 *
 * Resolves AD_Reference metadata to serve dropdown/lookup values
 * for Table (18), TableDir (19), Search (30), and OBUISEL_Selector
 * reference types.
 *
 * Only serves selectors for fields that are included (IsIncluded = Y)
 * in the ETGO_SF_Field configuration.
 *
 * Query construction helpers live in {@link SelectorQueryBuilder}.
 * Auxiliary field resolution helpers live in {@link SelectorAuxResolver}.
 */
public class NeoSelectorService {

  private static final Logger log = LogManager.getLogger(NeoSelectorService.class);

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  /** AD_Reference base IDs for FK types — shared across Neo* and MCP classes. */
  public static final String REF_TABLE = "18";
  public static final String REF_TABLEDIR = "19";
  public static final String REF_SEARCH = "30";
  public static final String REF_LIST = "17";
  public static final String REF_OBUISEL = "95E2A8B50A254B2AAE6774B8C2F28120";
  private static final String AD_ORG_ID = "AD_Org_ID";


  // Session-level params resolved server-side (should not appear in selectorParams)
  static final java.util.Set<String> SESSION_PARAMS = new java.util.HashSet<>(
      java.util.Arrays.asList(AD_ORG_ID, "AD_Client_ID", "AD_User_ID", "AD_Role_ID"));


  private NeoSelectorService() {
  }

  /**
   * List all FK-capable selector fields exposed for the given Schema Forge entity.
   *
   * @param specId the ETGO_SF_Spec identifier
   * @param entityName the entity name inside the spec
   * @return selector metadata for included FK fields, or an error response when the entity is missing
   */
  @SuppressWarnings("unchecked")
  public static NeoResponse listSelectors(String specId, String entityName) {
    try {
      // Find the entity record
      SFEntity entity = findEntity(specId, entityName);
      if (entity == null) {
        return NeoResponse.error(404, "Entity not found: " + entityName);
      }

      // Find all included fields for this entity
      OBCriteria<SFField> fieldCrit = OBDal.getInstance().createCriteria(SFField.class);
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entity.getId()));
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
      fieldCrit.addOrderBy(SFField.PROPERTY_SEQNO, true);
      List<SFField> fields = fieldCrit.list();

      JSONArray selectors = new JSONArray();
      for (SFField field : fields) {
        Column column = field.getADColumn();
        if (column == null) {
          continue;
        }
        NeoSelectorExecutionHelper.appendSelectorDescriptor(selectors, column);
      }

      JSONObject result = new JSONObject();
      result.put("selectors", selectors);
      result.put("count", selectors.length());
      return NeoResponse.ok(result);

    } catch (Exception e) {
      log.error("Error listing selectors for {}", entityName, e);
      return NeoResponse.error(500, e.getMessage());
    }
  }

  /**
   * Query selector values for a specific FK field.
   *
   * @param specId the ETGO_SF_Spec ID
   * @param entityName the entity name within the spec
   * @param columnName the DB column name (for example, {@code C_BPartner_ID})
   * @param search optional search text applied to the selector label/property
   * @param limit requested page size; normalized to the selector bounds
   * @param offset requested page offset; negative values are clamped to zero
   * @param contextParams validated request context used for selector policies and validation rules
   * @return a paginated selector response or an error when the field or target metadata cannot be resolved
   */
  @SuppressWarnings("unchecked")
  public static NeoResponse querySelector(String specId, String entityName,
      String columnName, String search, int limit, int offset,
      Map<String, String> contextParams) {
    try {
      if (limit <= 0) {
        limit = DEFAULT_LIMIT;
      }
      if (limit > MAX_LIMIT) {
        limit = MAX_LIMIT;
      }
      if (offset < 0) {
        offset = 0;
      }

      // Find the entity
      SFEntity entity = findEntity(specId, entityName);
      if (entity == null) {
        return NeoResponse.error(404, "Entity not found: " + entityName);
      }

      // Find the specific field by column name
      SFField sfField = findFieldByColumnName(entity.getId(), columnName);
      Column column = sfField != null ? sfField.getADColumn() : null;
      if (column == null) {
        column = NeoSelectorPolicy.resolveVirtualSelectorColumn(entity, columnName);
      }
      if (column == null) {
        return NeoResponse.error(404,
            "Field not found or not included: " + columnName);
      }

      return querySelectorByColumn(entity, column, columnName, search, limit, offset, contextParams);

    } catch (Exception e) {
      log.error("Error querying selector {}/{}", entityName, columnName, e);
      return NeoResponse.error(500, e.getMessage());
    }
  }

  /**
   * Query selector values using an AD_Column directly, bypassing ETGO_SF_FIELD lookup.
   * Used by the MCP layer to resolve FK selectors for ALL dictionary columns,
   * not just those included in ETGO_SF_FIELD configuration.
   *
   * @param column       the AD_Column to query selectors for
   * @param columnName   the DB column name (for error messages)
   * @param search       optional search text
   * @param limit        page size (default 20, max 100)
   * @param offset       page offset (default 0)
   * @param contextParams context parameters for validation rule resolution
   * @return a {@link NeoResponse} with the paginated selector items, or an error response
   */
  public static NeoResponse querySelectorByColumn(Column column, String columnName,
      String search, int limit, int offset, Map<String, String> contextParams) {
    return querySelectorByColumn(null, column, columnName, search, limit, offset, contextParams);
  }

  private static NeoResponse querySelectorByColumn(SFEntity sourceEntity, Column column, String columnName,
      String search, int limit, int offset, Map<String, String> contextParams) {
    try {
      int safeLimit = normalizeLimit(limit);
      int safeOffset = normalizeOffset(offset);

      String refId = getBaseReferenceId(column);
      boolean isObuisel = hasObuiselSelector(column);
      boolean isList = isListReference(refId);
      NeoResponse invalidReference = validateReferenceType(columnName, refId, isObuisel, isList);
      if (invalidReference != null) {
        return invalidReference;
      }
      if (isList) {
        return ListReferenceSelectorExecutor.resolveListSelector(column, search, safeLimit,
            safeOffset, contextParams);
      }
      if (!isObuisel && ComboReferenceSelectorExecutor.shouldUseCoreComboSelector(sourceEntity, column, refId)) {
        log.info("[ComboSelector] routing {} via core ComboTableData (SQL validation rule)",
            column.getDBColumnName());
        return ComboReferenceSelectorExecutor.resolveClassicSelectorWithCoreCombo(
            sourceEntity, column, search, safeLimit, safeOffset, contextParams);
      }

      SelectorMeta meta = resolveTarget(column, refId);
      if (meta == null) {
        return NeoResponse.error(500,
            "Could not resolve target for: " + columnName);
      }

      String validationFilter = SelectorQueryBuilder.resolveValidationFilter(
          column, meta.entityName, contextParams);
      String contextOrganizationId = SelectorContextResolver.resolveContextOrganizationId(sourceEntity, contextParams);
      String filterAlias = resolveFilterAlias(meta);
      String combinedFilter = buildCombinedFilter(
          column, validationFilter, filterAlias);
      NeoResponse selectorResult = executeSelectorQuery(
          meta, search, safeLimit, safeOffset, contextOrganizationId, combinedFilter, contextParams);
      return enrichProductSelectorIfNeeded(selectorResult, meta, contextParams);

    } catch (Exception e) {
      log.error("Error querying selector by column {}", columnName, e);
      return NeoResponse.error(500, e.getMessage());
    }
  }

  private static int normalizeLimit(int limit) {
    if (limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  private static int normalizeOffset(int offset) {
    return Math.max(offset, 0);
  }

  private static NeoResponse validateReferenceType(String columnName, String refId,
      boolean isObuisel, boolean isList) {
    if (!isObuisel && !isFkReference(refId) && !isList) {
      return NeoResponse.error(400, "Field is not a FK reference: " + columnName);
    }
    return null;
  }

  private static String resolveFilterAlias(SelectorMeta meta) {
    return meta.isRich && meta.isCustomQuery && StringUtils.isNotBlank(meta.entityAlias)
        ? meta.entityAlias
        : "e";
  }

  private static String buildCombinedFilter(Column column, String validationFilter,
      String filterAlias) {
    return combineFilters(
        remapFilterAlias(validationFilter, filterAlias),
        remapFilterAlias(NeoSelectorPolicy.resolveReferenceOverrideFilter(
            column != null && column.getReferenceSearchKey() != null
                ? column.getReferenceSearchKey().getId()
                : null),
            filterAlias));
  }

  private static NeoResponse executeSelectorQuery(SelectorMeta meta, String search, int limit, int offset,
      String contextOrganizationId, String combinedFilter, Map<String, String> contextParams) throws Exception {
    Map<String, String> safeContextParams = contextParams != null ? contextParams : Collections.emptyMap();
    String ctxAlias = (meta.isRich && meta.isCustomQuery && StringUtils.isNotBlank(meta.entityAlias))
        ? meta.entityAlias
        : "e";
    String ctxParamFilter = NeoSelectorPolicy.resolveContextParamFilter(
        meta.entityName, safeContextParams, ctxAlias);
    Map<String, Object> ctxFilterParams = new HashMap<>();
    String priceListId = safeContextParams.get("priceList");
    if (StringUtils.isNotBlank(priceListId) && ctxParamFilter != null
        && ctxParamFilter.contains(":priceListId")) {
      ctxFilterParams.put("priceListId", priceListId);
    }
    return SelectorQueryExecutor.execute(
        meta, search, limit, offset, combineFilters(combinedFilter, ctxParamFilter),
        contextOrganizationId, ctxFilterParams);
  }

  private static NeoResponse enrichProductSelectorIfNeeded(NeoResponse selectorResult, SelectorMeta meta,
      Map<String, String> contextParams) {
    return NeoSelectorPolicy.enrichSelectorResult(selectorResult, meta, contextParams);
  }



  // ---- Resolution helpers ----

  private static SFEntity findEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, entityName, MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  private static SFField findFieldByColumnName(String entityId,
      String columnName) {
    OBCriteria<SFField> criteria = OBDal.getInstance().createCriteria(SFField.class);
    criteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    criteria.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
    criteria.createAlias(SFField.PROPERTY_ADCOLUMN, "col");
    criteria.add(Restrictions.eq("col." + Column.PROPERTY_DBCOLUMNNAME, columnName));
    criteria.setMaxResults(1);
    List<SFField> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }


  /**
   * Return the set of server-side session parameter names excluded from client selector requirements.
   *
   * @return session parameter names known to be resolved on the server
   */
  public static java.util.Set<String> getSessionParams() {
    return SESSION_PARAMS;
  }

  /**
   * Resolve the normalized base reference identifier for a selector column.
   *
   * @param column AD column being inspected
   * @return the effective base reference identifier, following parent references when needed
   */
  public static String getBaseReferenceId(Column column) {
    String refId = column.getReference().getId();

    // Check if this is 17, 18, 19, or 30 directly
    if (REF_LIST.equals(refId)) {
      return refId;
    }

    // Check if this is 18, 19, or 30 directly
    if (REF_TABLE.equals(refId) || REF_TABLEDIR.equals(refId)
        || REF_SEARCH.equals(refId)) {
      return refId;
    }

    // Check parent reference
    org.openbravo.model.ad.domain.Reference ref = column.getReference();
    if (ref.getParentReference() != null) {
      String parentId = ref.getParentReference().getId();
      if (REF_LIST.equals(parentId) || REF_TABLE.equals(parentId) || REF_TABLEDIR.equals(parentId)
          || REF_SEARCH.equals(parentId)) {
        return parentId;
      }
    }

    return refId;
  }

  /**
   * Returns {@code true} if the given AD_Reference ID represents a classic FK reference type
   * (Table=18, TableDir=19, or Search=30).
   *
   * @param refId the AD_Reference ID to check
   * @return {@code true} if the reference is a FK type, {@code false} otherwise
   */
  public static boolean isFkReference(String refId) {
    return REF_TABLE.equals(refId) || REF_TABLEDIR.equals(refId)
        || REF_SEARCH.equals(refId);
  }

  /**
   * Returns {@code true} when the reference identifier represents an AD List selector.
   *
   * @param refId AD reference identifier
   * @return {@code true} for list references, {@code false} otherwise
   */
  public static boolean isListReference(String refId) {
    return REF_LIST.equals(refId);
  }

  /**
   * Load active label mappings for an AD reference list.
   *
   * @param referenceId the AD_Reference_Value_ID of the list reference
   * @return a map from search key to display label for active list entries
   */
  public static Map<String, String> getListLabels(String referenceId) {
    return ListReferenceSelectorExecutor.getListLabels(referenceId);
  }


  /**
   * Check whether the given column resolves to an OBUISEL selector definition.
   *
   * @param column AD column being inspected
   * @return {@code true} when an active OBUISEL selector exists for the column
   */
  public static boolean hasObuiselSelector(Column column) {
    return SelectorDescriptorResolver.hasObuiselSelector(column);
  }

  /**
   * Resolve selector metadata for one AD column.
   *
   * @param column AD column being resolved
   * @param baseRefId normalized base reference identifier
   * @return resolved selector metadata, or {@code null} when no target can be resolved
   */
  public static SelectorMeta resolveTarget(Column column, String baseRefId) {
    return SelectorDescriptorResolver.resolveTarget(column, baseRefId);
  }

  /**
   * Resolve a safe searchable fragment from selector field metadata.
   *
   * @param property DAL property defined on the selector field
   * @param clauseLeftPart custom HQL clause fragment used when the property is blank
   * @return a safe searchable fragment, or {@code null} when none can be derived
   */
  public static String resolveSearchableFragment(String property, String clauseLeftPart) {
    return SelectorDescriptorResolver.resolveSearchableFragment(property, clauseLeftPart);
  }

  /**
   * Find the preferred identifier property for a DAL entity.
   *
   * @param entity target DAL entity
   * @return preferred identifier property name, falling back to common defaults
   */
  public static String findIdentifierProperty(Entity entity) {
    return SelectorDescriptorResolver.findIdentifierProperty(entity);
  }

  static String combineFilters(String... filters) {
    List<String> parts = new ArrayList<>();
    for (String filter : filters) {
      if (StringUtils.isNotBlank(filter)) {
        parts.add(filter);
      }
    }
    if (parts.isEmpty()) {
      return null;
    }
    return String.join(SelectorQueryBuilder.SQL_AND, parts);
  }



  private static String remapFilterAlias(String filter, String alias) {
    if (StringUtils.isBlank(filter) || StringUtils.isBlank(alias) || "e".equals(alias)) {
      return filter;
    }
    return filter.replaceAll("(?<![\\w.])e\\.", Matcher.quoteReplacement(alias + "."));
  }

  /**
   * Resolve selector auxiliary values (_aux) for a specific record ID.
   * Used by the callout cascade to provide aux values (prices, UOM, currency)
   * that classic Etendo UI passes from the selector response.
   *
   * @param column    the AD_Column with the selector reference
   * @param fieldName the REST field name (e.g., "product")
   * @param recordId  the selected record ID
   * @return JSONObject with keys like "product_PSTD", "product_UOM", or null if no aux
   */
  public static JSONObject resolveSelectorAuxForId(Column column, String fieldName,
      String recordId) {
    if (column == null || recordId == null || recordId.isEmpty()) {
      return null;
    }
    try {
      String refId = getBaseReferenceId(column);
      SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, refId);
      if (meta == null || meta.auxFields == null || meta.auxFields.isEmpty()) {
        return null;
      }

      // For rich (OBUISEL) selectors with custom HQL, query via HQL
      if (meta.isRich && meta.isCustomQuery && StringUtils.isNotBlank(meta.customHql)) {
        return SelectorAuxResolver.resolveAuxViaHql(meta, fieldName, recordId);
      }

      // For DAL-resolvable aux fields, load the entity and read properties.
      BaseOBObject bob = SelectorAuxResolver.loadEntityForAux(meta, recordId);
      if (bob == null) {
        log.debug("No record found in {} for value {} (valueProperty={})",
            meta.entityName, recordId, meta.valueProperty);
        return null;
      }

      JSONObject result = new JSONObject();
      for (AuxFieldMeta af : meta.auxFields) {
        Object auxVal = SelectorAuxResolver.resolveAuxFieldValue(bob, af);
        if (auxVal != null) {
          result.put(fieldName + af.suffix, auxVal.toString());
        }
      }
      return result.length() > 0 ? result : null;

    } catch (Exception e) {
      log.debug("Could not resolve selector aux for {} / {}: {}",
          fieldName, recordId, e.getMessage());
      return null;
    }
  }
}
