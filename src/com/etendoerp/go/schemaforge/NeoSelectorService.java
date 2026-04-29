package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.Query;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.ApplicationUtils;
import org.openbravo.client.application.window.ApplicationDictionaryCachedStructures;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.data.FieldProvider;
import org.openbravo.erpCommon.utility.ComboTableData;
import org.openbravo.erpCommon.utility.FieldProviderFactory;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.ReferencedTable;
import org.openbravo.model.ad.domain.Validation;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.userinterface.selector.Selector;
import org.openbravo.userinterface.selector.SelectorField;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

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
  private static final String PARAM_PRICE_LIST = "priceList";

  /** AD_Reference base IDs for FK types — shared across Neo* and MCP classes. */
  public static final String REF_TABLE = "18";
  public static final String REF_TABLEDIR = "19";
  public static final String REF_SEARCH = "30";
  public static final String REF_LIST = "17";
  public static final String REF_OBUISEL = "95E2A8B50A254B2AAE6774B8C2F28120";

  // JSON field name constants
  private static final String PARAM_SEARCH = "search";
  private static final String FIELD_LABEL = "label";
  private static final String AD_ORG_ID = "AD_Org_ID";
  private static final String PROP_ORGANIZATION = "organization";

  // Session-level params resolved server-side (should not appear in selectorParams)
  static final java.util.Set<String> SESSION_PARAMS = new java.util.HashSet<>(
      java.util.Arrays.asList(AD_ORG_ID, "AD_Client_ID", "AD_User_ID", "AD_Role_ID"));


  private NeoSelectorService() {
  }

  /**
   * List all available selectors for an entity.
   * Only returns fields that are included and have a FK reference type.
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
   * @param specId     the ETGO_SF_Spec ID
   * @param entityName the entity name within the spec
   * @param columnName the DB column name (e.g., C_BPartner_ID)
   * @param search     optional search text (filters on display property)
   * @param limit      page size (default 20, max 100)
   * @param offset     page offset (default 0)
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
      int safeOffset = Math.max(offset, 0);

      String refId = getBaseReferenceId(column);
      boolean isObuisel = hasObuiselSelector(column);
      boolean isList = isListReference(refId);
      NeoResponse invalidReference = validateReferenceType(columnName, refId, isObuisel, isList);
      if (invalidReference != null) {
        return invalidReference;
      }
      if (isList) {
        return resolveListSelector(column, search, safeLimit, safeOffset, contextParams);
      }
      if (!isObuisel && shouldUseCoreComboSelector(sourceEntity, column, refId)) {
        log.info("[ComboSelector] routing {} via core ComboTableData (SQL validation rule)",
            column.getDBColumnName());
        return resolveClassicSelectorWithCoreCombo(
            sourceEntity, column, search, safeLimit, safeOffset, contextParams);
      }

      SelectorMeta meta = resolveTarget(column, refId);
      if (meta == null) {
        return NeoResponse.error(500,
            "Could not resolve target for: " + columnName);
      }

      String validationFilter = SelectorQueryBuilder.resolveValidationFilter(
          column, meta.entityName, contextParams);
      String contextOrganizationId = resolveContextOrganizationId(sourceEntity, contextParams);
      String filterAlias = resolveFilterAlias(meta);
      String combinedFilter = buildCombinedFilter(
          column, meta, contextParams, validationFilter, filterAlias);
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

  private static String buildCombinedFilter(Column column, SelectorMeta meta,
      Map<String, String> contextParams, String validationFilter, String filterAlias) {
    return combineFilters(
        remapFilterAlias(validationFilter, filterAlias),
        remapFilterAlias(NeoSelectorPolicy.resolveReferenceOverrideFilter(
            column != null && column.getReferenceSearchKey() != null
                ? column.getReferenceSearchKey().getId()
                : null),
            filterAlias),
        NeoSelectorPolicy.resolveContextParamFilter(meta.entityName, contextParams, filterAlias));
  }

  private static NeoResponse executeSelectorQuery(SelectorMeta meta, String search, int limit, int offset,
      String contextOrganizationId, String combinedFilter, Map<String, String> contextParams) throws Exception {
    if (meta.isRich) {
      String ctxAlias = (meta.isCustomQuery && meta.entityAlias != null) ? meta.entityAlias : "e";
      String ctxParamFilter =
          NeoSelectorPolicy.resolveContextParamFilter(meta.entityName, contextParams, ctxAlias);
      return executeRichQuery(
          meta, search, limit, offset, combineFilters(combinedFilter, ctxParamFilter),
          contextOrganizationId);
    }
    String ctxParamFilter =
        NeoSelectorPolicy.resolveContextParamFilter(meta.entityName, contextParams, "e");
    return executeQuery(
        meta, search, limit, offset, combineFilters(combinedFilter, ctxParamFilter),
        contextOrganizationId);
  }

  private static NeoResponse enrichProductSelectorIfNeeded(NeoResponse selectorResult, SelectorMeta meta,
      Map<String, String> contextParams) {
    boolean isProductSelector = "ProductByPriceAndWarehouse".equals(meta.entityName)
        || "Product".equals(meta.entityName);
    if (isProductSelector
        && contextParams != null
        && contextParams.containsKey(PARAM_PRICE_LIST)
        && selectorResult.getHttpStatus() == 200) {
      return NeoSelectorPolicy.enrichProductSelectorWithPrices(
          selectorResult, contextParams.get(PARAM_PRICE_LIST));
    }
    return selectorResult;
  }

  /**
   * Execute the paginated query against the target entity (simple selectors).
   */
  private static NeoResponse executeQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter,
      String contextOrganizationId) throws Exception {

    StringBuilder hql = new StringBuilder();
    Map<String, Object> queryParams = new HashMap<>();
    NeoSelectorExecutionHelper.appendResolvedWhereClause(hql, queryParams, meta.whereClause);
    NeoSelectorExecutionHelper.appendLiteralFilter(hql, validationFilter);
    NeoSelectorExecutionHelper.appendSelectorOrganizationFilter(hql, queryParams, meta,
        contextOrganizationId);
    NeoSelectorExecutionHelper.appendSimpleSearchFilter(hql, meta.displayProperty, search);

    // Prefix with alias "as e" so OBQuery registers the entity alias
    String whereStr = NeoSelectorExecutionHelper.buildSimpleWhereClause(hql);

    // Count query
    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, whereStr);
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, queryParams);
    if (StringUtils.isNotBlank(search)) {
      countQuery.setNamedParameter(PARAM_SEARCH,
          "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    // Data query with ordering and pagination
    String orderBy = " ORDER BY e." + meta.displayProperty;
    String dataWhere = whereStr + orderBy;

    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance()
        .createQuery(meta.entityName, dataWhere);
    NeoSelectorExecutionHelper.bindNamedParameters(dataQuery, queryParams);
    if (StringUtils.isNotBlank(search)) {
      dataQuery.setNamedParameter(PARAM_SEARCH,
          "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    // Build results
    Entity entityDef = ModelProvider.getInstance().getEntity(meta.entityName);
    JSONArray items = new JSONArray();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      item.put("id", SelectorQueryBuilder.normalizeEntityId(bob.getId().toString()));
      if (meta.displayProperty != null && meta.displayProperty.contains(".")) {
        Object labelValue = resolvePropertyValue(bob, meta.displayProperty, entityDef);
        item.put(FIELD_LABEL, labelValue != null ? labelValue : bob.getIdentifier());
      } else {
        item.put(FIELD_LABEL, bob.getIdentifier());
      }
      items.put(item);
    }

    return SelectorQueryBuilder.buildSelectorResponse(items, new JSONArray(), totalCount, limit, offset);
  }

  /**
   * Execute a rich (OBUISEL) selector query with multi-column response.
   */
  private static NeoResponse executeRichQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter,
      String contextOrganizationId) throws Exception {

    if (meta.isCustomQuery && StringUtils.isNotBlank(meta.customHql)) {
      return executeCustomHqlQuery(meta, search, limit, offset, validationFilter, contextOrganizationId);
    }
    // Custom query flag set but no HQL defined: fall through to standard query

    String alias = "e";
    SelectorQueryBuilder.HqlWithParams whereClause = SelectorQueryBuilder.buildRichQueryWhereClause(
        meta, search, validationFilter, alias, contextOrganizationId);
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();

    // Count query
    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, whereClause.getHql());
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, whereClause.getParams());
    if (hasSearch) {
      countQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    // Data query with ordering and pagination
    String dataWhere = whereClause.getHql() + " ORDER BY " + alias + "." + meta.displayProperty;
    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance()
        .createQuery(meta.entityName, dataWhere);
    NeoSelectorExecutionHelper.bindNamedParameters(dataQuery, whereClause.getParams());
    if (hasSearch) {
      dataQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    JSONArray columns = SelectorQueryBuilder.buildGridColumnMetadata(meta.gridFields);

    Entity entityDef = ModelProvider.getInstance().getEntity(meta.entityName);
    JSONArray items = new JSONArray();
    List<String> entityIds = new ArrayList<>();
    boolean useValueProperty = meta.valueProperty != null && !meta.valueProperty.equals("id");
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      String itemId = resolveRichItemId(bob, meta, entityDef);
      item.put("id", itemId);
      item.put(FIELD_LABEL, bob.getIdentifier());
      entityIds.add(itemId);
      entityIds.add(bob.getId().toString()); // keep view PK for aux HQL resolution

      for (RichFieldMeta fieldMeta : meta.gridFields) {
        Object value = resolvePropertyValue(bob, fieldMeta.property, entityDef);
        item.put(fieldMeta.propertyKey, value != null ? value : JSONObject.NULL);
      }
      SelectorAuxResolver.appendAuxFields(item, bob, meta.auxFields);
      items.put(item);
    }

    return SelectorQueryBuilder.buildSelectorResponse(items, columns, totalCount, limit, offset);
  }

  /**
   * Execute a custom HQL selector query using the full HQL from the Selector definition.
   * Custom HQL selectors define their own FROM clause (e.g., "FROM Product AS p WHERE ...").
   * We append additional filters and use Session.createQuery for the full HQL.
   */
  @SuppressWarnings("unchecked")
  private static NeoResponse executeCustomHqlQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter,
      String contextOrganizationId) throws Exception {

    String alias = meta.entityAlias;
    String rawHql = meta.customHql.replace("@additional_filters@", "1=1");

    // Extract position of the FROM clause (may be preceded by space/newline/tab)
    java.util.regex.Matcher fromMatcher = Pattern.compile("\\sFROM\\s",
        Pattern.CASE_INSENSITIVE).matcher(rawHql);
    if (!fromMatcher.find()) {
      throw new IllegalArgumentException(
          "Custom HQL does not contain a FROM clause: " + rawHql);
    }
    int fromIdx = fromMatcher.start();
    String fromOnwards = rawHql.substring(fromIdx);

    // Build the FROM…WHERE…filters portion
    SelectorQueryBuilder.HqlWithParams fromClause = SelectorQueryBuilder.buildCustomHqlFromClause(
        fromOnwards, alias, meta, validationFilter, search, contextOrganizationId);
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();

    // Parse SELECT column aliases to build a name→index map
    String selectPart = rawHql.substring(0, fromIdx).trim();
    String[] selectExprs = selectPart.replaceFirst("(?i)^select\\s+", "").split(",");
    Map<String, Integer> colIndexMap = SelectorQueryBuilder.buildSelectColumnIndexMap(selectExprs);

    // Count query
    String countHql = "SELECT COUNT(" + alias + ")" + fromClause.getHql();
    org.hibernate.query.Query<Long> countQuery = OBDal.getInstance()
        .getSession().createQuery(countHql, Long.class);
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, fromClause.getParams());
    if (hasSearch) {
      countQuery.setParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    Long countResult = countQuery.uniqueResult();
    int totalCount = (countResult != null) ? countResult.intValue() : 0;

    // Data query — use the ORIGINAL select columns + our filters
    String dataHql = selectPart + fromClause.getHql() + " ORDER BY " + alias + "."
        + meta.displayProperty;
    org.hibernate.query.Query<?> dataQuery = OBDal.getInstance()
        .getSession().createQuery(dataHql);
    NeoSelectorExecutionHelper.bindNamedParameters(dataQuery, fromClause.getParams());
    if (hasSearch) {
      dataQuery.setParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResults(limit);
    dataQuery.setFirstResult(offset);

    Integer idColIdx = SelectorQueryBuilder.resolveIdColumnIndex(meta, alias, colIndexMap, selectExprs);
    JSONArray columns = SelectorQueryBuilder.buildGridColumnMetadata(meta.gridFields);

    Entity entityDef = ModelProvider.getInstance().getEntity(meta.entityName);
    JSONArray items = new JSONArray();
    List<String> entityIds = new ArrayList<>();
    for (Object rawRow : dataQuery.list()) {
      Object[] row = (rawRow instanceof Object[]) ? (Object[]) rawRow : new Object[]{ rawRow };
      JSONObject item = new JSONObject();

      String recordId = SelectorQueryBuilder.extractRecordId(row, idColIdx);
      item.put("id", recordId);
      entityIds.add(recordId);
      item.put(FIELD_LABEL,
          SelectorQueryBuilder.extractDisplayLabel(row, colIndexMap, meta.displayProperty, entityDef, recordId));
      SelectorQueryBuilder.mapGridFieldsToItem(item, row, colIndexMap, meta.gridFields);
      items.put(item);
    }

    // Resolve auxiliary fields that are only obtainable via the original HQL SELECT
    boolean hasHqlOnlyAux = meta.auxFields.stream()
        .anyMatch(af -> StringUtils.isBlank(af.property) && StringUtils.isNotBlank(af.hqlAlias));
    if (hasHqlOnlyAux && !entityIds.isEmpty()) {
      SelectorAuxResolver.resolveAuxFieldsViaHql(items, entityIds, rawHql, fromIdx, alias, meta);
    }

    return SelectorQueryBuilder.buildSelectorResponse(items, columns, totalCount, limit, offset);
  }

  /**
   * Resolve the item ID for a rich (OBUISEL) selector row.
   *
   * <p>When the selector's valueProperty is a dot-path (e.g. {@code "product.id"}), the
   * composite entity ID (e.g. {@code warehouseId + productPriceId} for
   * {@code ProductByPriceAndWarehouse}) does NOT contain the FK value in its last 32 chars.
   * We must walk the property path to get the real FK value instead of blindly normalizing
   * the composite ID.
   *
   * <p>Falls back to {@link SelectorQueryBuilder#normalizeEntityId} when valueProperty is
   * absent, {@code "id"}, or the path cannot be resolved.
   */
  private static String resolveRichItemId(BaseOBObject bob, SelectorMeta meta, Entity entityDef) {
    if (meta.valueProperty != null && !"id".equals(meta.valueProperty)) {
      Object val = resolvePropertyValue(bob, meta.valueProperty, entityDef);
      if (val != null) {
        return val.toString();
      }
    }
    return SelectorQueryBuilder.normalizeEntityId(bob.getId().toString());
  }

  /**
   * Resolve a potentially dotted property path on a BaseOBObject.
   * E.g., "product.name" navigates bob.get("product").get("name").
   */
  private static Object resolvePropertyValue(BaseOBObject bob,
      String propertyPath, Entity entityDef) {
    try {
      String[] parts = propertyPath.split("\\.");
      Object current = bob;
      for (String part : parts) {
        if (current == null) {
          return null;
        }
        if (current instanceof BaseOBObject) {
          current = ((BaseOBObject) current).get(part);
        } else {
          return current;
        }
      }
      // If the final value is a BaseOBObject, return its identifier
      if (current instanceof BaseOBObject) {
        return ((BaseOBObject) current).getIdentifier();
      }
      return current;
    } catch (Exception e) {
      log.debug("Could not resolve property {} on {}: {}",
          propertyPath, bob.getId(), e.getMessage());
      return null;
    }
  }

  private static String resolveContextOrganizationId(SFEntity sourceEntity,
      Map<String, String> contextParams) {
    if (contextParams == null) {
      return null;
    }
    String organizationId = StringUtils.trimToNull(contextParams.get(AD_ORG_ID));
    if (organizationId == null) {
      organizationId = StringUtils.trimToNull(contextParams.get("inpadOrgId"));
    }
    if (organizationId == null) {
      organizationId = resolveOrgFromParentRecord(sourceEntity, contextParams.get("parentId"));
    }
    if ("0".equals(organizationId)) {
      return null;
    }
    return organizationId;
  }

  private static String resolveOrgFromParentRecord(SFEntity sourceEntity, String parentId) {
    if (sourceEntity == null || StringUtils.isBlank(parentId)) {
      return null;
    }
    try {
      Tab childTab = sourceEntity.getADTab();
      if (childTab == null) {
        return null;
      }
      if (childTab.getTabLevel() == null || childTab.getTabLevel() <= 0) {
        if (childTab.getTable() == null) {
          return null;
        }
        Entity selfEntity = ModelProvider.getInstance().getEntityByTableId(childTab.getTable().getId());
        if (selfEntity == null || !selfEntity.hasProperty(PROP_ORGANIZATION)) {
          return null;
        }
        BaseOBObject selfRecord = OBDal.getInstance().get(selfEntity.getName(), parentId);
        if (selfRecord == null) {
          return null;
        }
        Object organization = selfRecord.get(PROP_ORGANIZATION);
        if (organization instanceof BaseOBObject) {
          Object organizationId = ((BaseOBObject) organization).getId();
          return organizationId != null ? organizationId.toString() : null;
        }
        return organization != null ? organization.toString() : null;
      }
      Tab parentTab = KernelUtils.getInstance().getParentTab(childTab);
      if (parentTab == null || parentTab.getTable() == null) {
        return null;
      }
      String parentProperty = ApplicationUtils.getParentProperty(childTab, parentTab);
      if (StringUtils.isBlank(parentProperty)) {
        return null;
      }
      Entity parentEntity = ModelProvider.getInstance().getEntityByTableId(parentTab.getTable().getId());
      if (parentEntity == null || !parentEntity.hasProperty(PROP_ORGANIZATION)) {
        return null;
      }
      BaseOBObject parentRecord = OBDal.getInstance().get(parentEntity.getName(), parentId);
      if (parentRecord == null) {
        return null;
      }
      Object organization = parentRecord.get(PROP_ORGANIZATION);
      if (organization instanceof BaseOBObject) {
        Object organizationId = ((BaseOBObject) organization).getId();
        return organizationId != null ? organizationId.toString() : null;
      }
      return organization != null ? organization.toString() : null;
    } catch (Exception e) {
      log.debug("Could not resolve parent organization for selector context: {}", e.getMessage());
      return null;
    }
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
   * Get the base reference ID (18, 19, or 30) checking parent references.
   */
  static java.util.Set<String> getSessionParams() {
    return SESSION_PARAMS;
  }

  static String getBaseReferenceId(Column column) {
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
   * Returns {@code true} if the given AD_Reference ID represents a list reference type (List=17).
   */
  static boolean isListReference(String refId) {
    return REF_LIST.equals(refId);
  }

  /**
   * Route classic FK references with SQL validation rules through the core combo SQL path instead
   * of translating SQL into HQL.
   */
  private static boolean shouldUseCoreComboSelector(SFEntity sourceEntity, Column column,
      String refId) {
    return sourceEntity != null
        && isFkReference(refId)
        && hasSqlValidationRule(column)
        && resolveComboField(sourceEntity, column) != null;
  }

  private static boolean hasSqlValidationRule(Column column) {
    Validation validation = column != null ? column.getValidation() : null;
    return validation != null && "S".equalsIgnoreCase(validation.getType());
  }

  /**
   * Execute a classic FK selector using the same ComboTableData SQL flow used by core.
   *
   * <p>ComboTableData does not expose an exact count API. We fetch one extra row so pagination can
   * determine whether another page exists, and return the minimum total count compatible with that
   * page, mirroring the core combo datasource behaviour.
   */
  private static NeoResponse resolveClassicSelectorWithCoreCombo(SFEntity sourceEntity,
      Column column, String search, int limit, int offset, Map<String, String> contextParams)
      throws Exception {
    Field field = resolveComboField(sourceEntity, column);
    if (field == null) {
      return NeoResponse.error(500,
          "Could not resolve AD_Field for SQL validation selector: " + column.getDBColumnName());
    }

    // ComboTableData.getVars() always reads from RequestContext.get().getVariablesSecureApp().
    // In NEO/JWT flow, that is not populated from the token — we must set it explicitly,
    // same pattern as NeoProcessService.ensureRequestContextVars().
    OBContext obCtx = OBContext.getOBContext();
    VariablesSecureApp vars = CalloutRequestBuilder.buildCalloutVars(obCtx,
        sourceEntity.getADTab());
    RequestContext.get().setVariableSecureApp(vars);

    ApplicationDictionaryCachedStructures cachedStructures = WeldUtils
        .getInstanceFromStaticBeanManager(ApplicationDictionaryCachedStructures.class);
    ComboTableData comboTableData = cachedStructures.getComboTableData(field);

    Map<String, String> selectorParams = buildComboSelectorParams(sourceEntity, contextParams);

     selectorParams.put("CLIENT_LIST", OBContext.getOBContext().getCurrentClient().getId());
    selectorParams.put("ORG_LIST", Arrays.stream(OBContext.getOBContext().getReadableOrganizations()).collect(Collectors.joining(",")));

    log.info("[ComboSelector] column={} selectorParams={}", column.getDBColumnName(), selectorParams);

    String windowId = field.getTab() != null && field.getTab().getWindow() != null
        ? field.getTab().getWindow().getId()
        : null;
    Map<String, String> resolvedParams = comboTableData.fillSQLParametersIntoMap(
        new DalConnectionProvider(false), vars, new FieldProviderFactory(selectorParams), windowId,
        null);
    log.info("[ComboSelector] resolvedParams={}", resolvedParams);

    if (StringUtils.isNotBlank(search)) {
      resolvedParams.put("FILTER_VALUE", search);
    }

    FieldProvider[] rawRows = comboTableData.select(new DalConnectionProvider(false), resolvedParams,
        false, offset, offset + limit);
    log.info("[ComboSelector] column={} rawRows={} offset={} limit={} resolvedParams={}",
        column.getDBColumnName(), rawRows.length, offset, limit, resolvedParams);

    boolean hasMore = rawRows.length > limit;
    int visibleRows = hasMore ? limit : rawRows.length;
    int totalCount = hasMore ? offset + limit + 1 : offset + visibleRows;

    JSONArray items = new JSONArray();
    for (int index = 0; index < visibleRows; index++) {
      FieldProvider row = rawRows[index];
      JSONObject item = new JSONObject();
      item.put("id", row.getField("ID"));
      item.put(FIELD_LABEL, row.getField("NAME"));
      items.put(item);
    }

    return SelectorQueryBuilder.buildSelectorResponse(items, new JSONArray(), totalCount, limit,
        offset);
  }

  private static Map<String, String> buildComboSelectorParams(SFEntity sourceEntity,
      Map<String, String> contextParams) {
    Map<String, String> selectorParams = new HashMap<>();
    if (contextParams != null) {
      selectorParams.putAll(contextParams);
    }

    String resolvedOrganizationId = resolveContextOrganizationId(sourceEntity, contextParams);
    copyIfAbsent(selectorParams, "AD_Org_ID", resolvedOrganizationId);
    copyIfAbsent(selectorParams, "inpadOrgId", resolvedOrganizationId);

    // Normalise casing variants so ComboTableData can find them by their canonical names
    copyIfAbsent(selectorParams, "IsSOTrx", selectorParams.get("isSOTrx"));
    copyIfAbsent(selectorParams, "isSOTrx", selectorParams.get("IsSOTrx"));

    // If IsSOTrx is still absent, derive it from the AD_Window.isSalesTransaction() flag.
    // The NeoEndpoint URL already carries the spec name (e.g. "sales-order"), which maps to
    // an SFSpec → SFEntity → AD_Tab → AD_Window that knows whether it is a SO/PO window.
    if (!selectorParams.containsKey("IsSOTrx") || StringUtils.isBlank(selectorParams.get("IsSOTrx"))) {
      String windowIsSOTrx = resolveIsSOTrxFromWindow(sourceEntity);
      if (windowIsSOTrx != null) {
        selectorParams.put("IsSOTrx", windowIsSOTrx);
        selectorParams.put("isSOTrx", windowIsSOTrx);
      }
    }

    copyIfAbsent(selectorParams, "IsReceipt", selectorParams.get("isReceipt"));
    copyIfAbsent(selectorParams, "isReceipt", selectorParams.get("IsReceipt"));
    copyIfAbsent(selectorParams, "FIN_ISRECEIPT", selectorParams.get("FIN_ISRECEIPT"));
    copyIfAbsent(selectorParams, "FIN_ISRECEIPT", selectorParams.get("isReceipt"));
    copyIfAbsent(selectorParams, "priceList", selectorParams.get("PriceList"));
    copyIfAbsent(selectorParams, "PriceList", selectorParams.get("priceList"));
    return selectorParams;
  }

  /**
   * Resolve the IsSOTrx value ("Y"/"N") from the AD_Window associated with the given SFEntity.
   * AD_Window.IsSOTrx indicates whether the window is a Sales Order (Y) or Purchase Order (N)
   * transaction window. This allows SQL validation rules that reference @IsSOTrx@ to work
   * correctly even when the client does not explicitly pass the parameter.
   *
   * @return "Y", "N", or null if the window cannot be determined
   */
  private static String resolveIsSOTrxFromWindow(SFEntity sourceEntity) {
    try {
      if (sourceEntity == null) {
        return null;
      }
      Tab tab = sourceEntity.getADTab();
      if (tab == null) {
        return null;
      }
      Window window = tab.getWindow();
      if (window == null) {
        return null;
      }
      Boolean isSalesTransaction = window.isSalesTransaction();
      if (isSalesTransaction == null) {
        return null;
      }
      return isSalesTransaction ? "Y" : "N";
    } catch (Exception e) {
      log.debug("Could not resolve IsSOTrx from window for entity {}: {}",
          sourceEntity != null ? sourceEntity.getName() : "null", e.getMessage());
      return null;
    }
  }

  private static void copyIfAbsent(Map<String, String> target, String key, String value) {
    if (StringUtils.isBlank(key) || StringUtils.isBlank(value) || target.containsKey(key)) {
      return;
    }
    target.put(key, value);
  }

  private static Field resolveComboField(SFEntity sourceEntity, Column column) {
    if (sourceEntity == null || column == null || sourceEntity.getADTab() == null) {
      return null;
    }

    OBCriteria<Field> criteria = OBDal.getInstance().createCriteria(Field.class);
    criteria.add(Restrictions.eq(Field.PROPERTY_TAB, sourceEntity.getADTab()));
    criteria.add(Restrictions.eq("column", column));
    criteria.add(Restrictions.eq("active", true));
    criteria.setMaxResults(1);

    List<Field> fields = criteria.list();
    return fields.isEmpty() ? null : fields.get(0);
  }

  /**
   * Resolve list values for a List reference (AD_Reference type 17).
   * Queries AD_REF_LIST using the column's AD_Reference_Value_ID (referenceSearchKey).
   */
  @SuppressWarnings("unchecked")
  private static NeoResponse resolveListSelector(Column column, String search,
      int limit, int offset, Map<String, String> contextParams) throws Exception {

    org.openbravo.model.ad.domain.Reference listRef = column.getReferenceSearchKey();
    if (listRef == null) {
      // Fallback: use the column's own reference (for inline list definitions)
      listRef = column.getReference();
    }

    String valRuleSql = SelectorQueryBuilder.resolveValidationSql(column, contextParams);

    // Use separate criteria for count/data because count() mutates projection state.
    OBCriteria<org.openbravo.model.ad.domain.List> countCrit = OBDal.getInstance()
        .createCriteria(org.openbravo.model.ad.domain.List.class);
    countCrit.add(Restrictions.eq(
        org.openbravo.model.ad.domain.List.PROPERTY_REFERENCE + ".id",
        listRef.getId()));
    countCrit.add(Restrictions.eq(
        org.openbravo.model.ad.domain.List.PROPERTY_ACTIVE, true));
    if (valRuleSql != null) {
      countCrit.add(Restrictions.sqlRestriction(valRuleSql));
    }
    if (StringUtils.isNotBlank(search)) {
      countCrit.add(Restrictions.ilike(
          org.openbravo.model.ad.domain.List.PROPERTY_NAME,
          "%" + search + "%"));
    }
    int totalCount = countCrit.count();

    OBCriteria<org.openbravo.model.ad.domain.List> dataCrit = OBDal.getInstance()
        .createCriteria(org.openbravo.model.ad.domain.List.class);
    dataCrit.add(Restrictions.eq(
        org.openbravo.model.ad.domain.List.PROPERTY_REFERENCE + ".id",
        listRef.getId()));
    dataCrit.add(Restrictions.eq(
        org.openbravo.model.ad.domain.List.PROPERTY_ACTIVE, true));
    if (valRuleSql != null) {
      dataCrit.add(Restrictions.sqlRestriction(valRuleSql));
    }
    if (StringUtils.isNotBlank(search)) {
      dataCrit.add(Restrictions.ilike(
          org.openbravo.model.ad.domain.List.PROPERTY_NAME,
          "%" + search + "%"));
    }
    dataCrit.addOrderBy(
        org.openbravo.model.ad.domain.List.PROPERTY_SEQUENCENUMBER, true);
    dataCrit.setFirstResult(offset);
    dataCrit.setMaxResults(limit);

    JSONArray items = new JSONArray();
    for (org.openbravo.model.ad.domain.List listItem : dataCrit.list()) {
      JSONObject item = new JSONObject();
      item.put("id", listItem.getSearchKey());
      item.put(FIELD_LABEL, listItem.getName());
      items.put(item);
    }
    return SelectorQueryBuilder.buildSelectorResponse(items, new JSONArray(), totalCount, limit, offset);
  }

  /**
   * Load all active list entries for an AD_Reference of type List (17).
   *
   * @param referenceId the AD_Reference_Value_ID of the List reference
   * @return Map from searchKey (e.g. "GENERIC") to display name (e.g. "Use Generic Account No.")
   */
  @SuppressWarnings("unchecked")
  public static Map<String, String> getListLabels(String referenceId) {
    Map<String, String> labels = new HashMap<>();
    try {
      OBCriteria<org.openbravo.model.ad.domain.List> crit = OBDal.getInstance()
          .createCriteria(org.openbravo.model.ad.domain.List.class);
      crit.add(Restrictions.eq(
          org.openbravo.model.ad.domain.List.PROPERTY_REFERENCE + ".id", referenceId));
      crit.add(Restrictions.eq(
          org.openbravo.model.ad.domain.List.PROPERTY_ACTIVE, true));
      for (org.openbravo.model.ad.domain.List item : crit.list()) {
        labels.put(item.getSearchKey(), item.getName());
      }
    } catch (Exception e) {
      log.debug("Could not load list labels for reference {}: {}", referenceId, e.getMessage());
    }
    return labels;
  }

  /**
   * Check if a column has an associated OBUISEL_Selector.
   * Checks both referenceSearchKey and the column's own reference.
   */
  static boolean hasObuiselSelector(Column column) {
    return findObuiselSelector(column) != null;
  }

  /**
   * Find the OBUISEL_Selector for a column, if any.
   * First checks referenceSearchKey, then falls back to the column's reference.
   */
  private static Selector findObuiselSelector(Column column) {
    // First: check via referenceSearchKey (AD_Reference_Value_ID)
    org.openbravo.model.ad.domain.Reference refSearchKey =
        column.getReferenceSearchKey();
    if (refSearchKey != null) {
      Selector sel = findSelectorByReference(refSearchKey.getId());
      if (sel != null) {
        return sel;
      }
    }

    // Fallback: check the column's own reference
    String refId = column.getReference().getId();
    if (!REF_TABLE.equals(refId) && !REF_TABLEDIR.equals(refId)
        && !REF_SEARCH.equals(refId)) {
      // Only check if it's not a base type (base types don't have OBUISEL)
      Selector sel = findSelectorByReference(refId);
      if (sel != null) {
        return sel;
      }
    }

    return null;
  }

  /**
   * Query OBUISEL_Selector by AD_Reference_ID.
   */
  private static Selector findSelectorByReference(String referenceId) {
    try {
      OBCriteria<Selector> crit = OBDal.getInstance()
          .createCriteria(Selector.class);
      crit.add(Restrictions.eq(Selector.PROPERTY_REFERENCE + ".id",
          referenceId));
      crit.add(Restrictions.eq(Selector.PROPERTY_ACTIVE, true));
      crit.setMaxResults(1);
      return (Selector) crit.uniqueResult();
    } catch (Exception e) {
      log.debug("Error looking up OBUISEL_Selector for ref {}: {}",
          referenceId, e.getMessage());
      return null;
    }
  }

  /**
   * Resolve the target entity, display property, and optional where clause.
   * Priority: OBUISEL_Selector first, then TableDir / AD_Ref_Table.
   */
  static SelectorMeta resolveTarget(Column column, String baseRefId) {
    // 1. Check OBUISEL_Selector first
    Selector obuisel = findObuiselSelector(column);
    if (obuisel != null) {
      return resolveObuiselSelector(obuisel);
    }

    // 2. Fall back to classic resolution
    if (REF_TABLEDIR.equals(baseRefId)) {
      return resolveTableDir(column);
    } else {
      // Table (18) or Search (30): use AD_Ref_Table
      SelectorMeta meta = resolveRefTable(column);
      if (meta == null && column.getDBColumnName().endsWith("_ID")) {
        // AD_Ref_Table missing — fall back to TableDir convention as last resort
        // (e.g. M_Locator_ID → M_Locator when no AD_Ref_Table or OBUISEL_Selector exists)
        log.debug("No AD_Ref_Table for {}, trying TableDir fallback", column.getDBColumnName());
        return resolveTableDir(column);
      }
      return meta;
    }
  }

  /**
   * Resolve an OBUISEL_Selector into a SelectorMeta with rich field info.
   */
  private static SelectorMeta resolveObuiselSelector(Selector selector) {
    try {
      // Check for custom query and retrieve custom HQL if present
      boolean isCustom = Boolean.TRUE.equals(selector.isCustomQuery());
      String customHql = isCustom ? selector.getHQL() : null;
      String entityAlias = selector.getEntityAlias();
      if (StringUtils.isBlank(entityAlias)) {
        entityAlias = "e";
      }

      Table targetTable = selector.getTable();
      if (targetTable == null) {
        log.warn("OBUISEL_Selector {} has no target table",
            selector.getName());
        return null;
      }

      Entity targetEntity = ModelProvider.getInstance()
          .getEntityByTableName(targetTable.getDBTableName());
      if (targetEntity == null) {
        log.warn("No entity for OBUISEL table: {}",
            targetTable.getDBTableName());
        return null;
      }

      // Resolve display property from displayfield
      String displayProp;
      SelectorField displayField = selector.getDisplayfield();
      if (displayField != null && StringUtils.isNotBlank(displayField.getProperty())) {
        displayProp = displayField.getProperty();
      } else {
        displayProp = findIdentifierProperty(targetEntity);
      }

      // Resolve value property from valuefield
      String valueProp = "id";
      SelectorField valueField = selector.getValuefield();
      if (valueField != null && StringUtils.isNotBlank(valueField.getProperty())) {
        valueProp = valueField.getProperty();
      }

      // Get where clause
      String whereClause = selector.getHQLWhereClause();
      if (StringUtils.isBlank(whereClause)) {
        whereClause = null;
      }

      // Load and classify selector fields
      List<SelectorField> selectorFields = selector.getOBUISELSelectorFieldList();
      ObuiselFieldLists fieldLists = classifySelectorFields(selectorFields);
      List<RichFieldMeta> gridFields = fieldLists.gridFields;
      List<String> searchableProps = fieldLists.searchableProps;
      List<AuxFieldMeta> auxFields = fieldLists.auxFields;

      // Sort grid fields by sortNo
      gridFields.sort((a, b) -> Long.compare(a.sortNo, b.sortNo));

      return new SelectorMeta.Builder(targetEntity.getName(), displayProp)
          .whereClause(whereClause)
          .isRich(true)
          .isCustomQuery(isCustom)
          .valueProperty(valueProp)
          .gridFields(gridFields)
          .searchableProperties(searchableProps)
          .customHql(customHql)
          .entityAlias(entityAlias)
          .auxFields(auxFields)
          .build();

    } catch (Exception e) {
      log.warn("Could not resolve OBUISEL_Selector {}: {}",
          selector.getName(), e.getMessage());
      return null;
    }
  }

  /**
   * Classify a list of OBUISEL selector fields into grid columns, searchable properties,
   * and auxiliary output fields.
   */
  private static ObuiselFieldLists classifySelectorFields(List<SelectorField> selectorFields) {
    List<RichFieldMeta> gridFields = new ArrayList<>();
    List<String> searchableProps = new ArrayList<>();
    List<AuxFieldMeta> auxFields = new ArrayList<>();

    for (SelectorField sf : selectorFields) {
      if (!Boolean.TRUE.equals(sf.isActive())) {
        continue;
      }
      collectAuxField(sf, auxFields);
      collectGridAndSearchFields(sf, gridFields, searchableProps);
    }
    return new ObuiselFieldLists(gridFields, searchableProps, auxFields);
  }

  /**
   * Add an auxiliary (outfield) entry to {@code auxFields} if the selector field qualifies.
   */
  private static void collectAuxField(SelectorField sf, List<AuxFieldMeta> auxFields) {
    if (Boolean.TRUE.equals(sf.isOutfield()) && StringUtils.isNotBlank(sf.getSuffix())) {
      String alias = sf.getDisplayColumnAlias();
      auxFields.add(new AuxFieldMeta(
          sf.getSuffix(),
          alias != null ? alias.toLowerCase() : null,
          sf.getName(),
          sf.getProperty()));
    }
  }

  /**
   * Pattern for "safe" HQL path fragments that can be inlined into a search filter.
   * Accepts bare property names ({@code name}) and dotted paths ({@code bp.name},
   * {@code contact.businessPartner.name}). Rejects anything containing spaces,
   * operators, parentheses, commas, quotes, function calls, etc., because those
   * cannot be safely inlined into an HQL predicate without parsing.
   */
  private static final java.util.regex.Pattern SAFE_HQL_PATH =
      java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.]*$");

  /**
   * Add grid-column and searchable-property entries for a selector field.
   *
   * <p>Fields with a non-blank {@code property} follow the standard path.
   * For custom-HQL selectors, searchable fields commonly have {@code property=''}
   * and define their HQL fragment in {@code clause_left_part} (e.g. {@code bp.name},
   * {@code bp.searchKey}). When {@code property} is blank but {@code clause_left_part}
   * is a safe HQL path, it is used as the searchable fragment so the {@code q} filter
   * still applies. Complex expressions (functions, arithmetic, subqueries) are rejected
   * to avoid emitting broken HQL — we skip search on those columns rather than guess.
   *
   * <p>Fields whose resolved fragment ends with {@code _identifier} are excluded from
   * search (virtual DAL property, not Hibernate-mapped).
   */
  private static void collectGridAndSearchFields(SelectorField sf,
      List<RichFieldMeta> gridFields, List<String> searchableProps) {
    String prop = sf.getProperty();
    String searchFragment = resolveSearchableFragment(prop, sf.getClauseLeftPart());

    if (StringUtils.isNotBlank(prop) && Boolean.TRUE.equals(sf.isShowingrid())) {
      String propKey = getLastSegment(prop);
      Long sortNo = sf.getSortno();
      gridFields.add(new RichFieldMeta(propKey, sf.getName(), prop,
          sortNo != null ? sortNo : 0L));
    }
    if (Boolean.TRUE.equals(sf.isSearchinsuggestionbox())
        && StringUtils.isNotBlank(searchFragment)
        && !searchFragment.endsWith("_identifier")) {
      searchableProps.add(searchFragment);
    }
  }

  /**
   * Resolve the HQL fragment used as the searchable property for a selector field.
   *
   * <ul>
   *   <li>Prefers {@code property} when non-blank (standard selectors).</li>
   *   <li>Falls back to {@code clauseLeftPart} when the property is blank and the
   *       clause is a simple HQL path (see {@link #SAFE_HQL_PATH}).</li>
   *   <li>Returns {@code null} when no safe fragment can be derived.</li>
   * </ul>
   *
   * <p>Package-private for unit testing.
   */
  static String resolveSearchableFragment(String property, String clauseLeftPart) {
    if (StringUtils.isNotBlank(property)) {
      return property;
    }
    if (StringUtils.isBlank(clauseLeftPart)) {
      return null;
    }
    String trimmed = clauseLeftPart.trim();
    if (SAFE_HQL_PATH.matcher(trimmed).matches()) {
      return trimmed;
    }
    log.debug("Skipping search on selector field with unsafe clause_left_part: {}", trimmed);
    return null;
  }

  /**
   * Get the last segment of a dotted property path.
   * E.g., "product.name" -> "name", "id" -> "id".
   */
  private static String getLastSegment(String propertyPath) {
    int lastDot = propertyPath.lastIndexOf('.');
    if (lastDot >= 0 && lastDot < propertyPath.length() - 1) {
      return propertyPath.substring(lastDot + 1);
    }
    return propertyPath;
  }

  /**
   * TableDir convention: column name = {TableName}_ID.
   * Target table is derived from column name.
   */
  private static SelectorMeta resolveTableDir(Column column) {
    String colName = column.getDBColumnName();
    if (!colName.endsWith("_ID")) {
      log.warn("TableDir column doesn't end with _ID: {}", colName);
      return null;
    }

    String tableName = colName.substring(0, colName.length() - 3);

    try {
      Entity targetEntity = ModelProvider.getInstance()
          .getEntityByTableName(tableName);
      if (targetEntity == null) {
        log.warn("No entity found for table: {}", tableName);
        return null;
      }

      // Find the identifier property for display
      String displayProp = findIdentifierProperty(targetEntity);

      return new SelectorMeta(
          targetEntity.getName(),
          displayProp,
          null // no where clause for TableDir
      );
    } catch (Exception e) {
      log.warn("Could not resolve TableDir for {}: {}", colName, e.getMessage());
      return null;
    }
  }

  /**
   * Table/Search: use AD_Ref_Table to find target table and display column.
   */
  private static SelectorMeta resolveRefTable(Column column) {
    org.openbravo.model.ad.domain.Reference refValue = column.getReferenceSearchKey();
    if (refValue == null) {
      log.warn("Column {} has no AD_Reference_Value", column.getDBColumnName());
      return null;
    }

    try {
      // Query AD_Ref_Table for this reference
      OBCriteria<ReferencedTable> refTableCrit =
          OBDal.getInstance().createCriteria(ReferencedTable.class);
      refTableCrit.add(Restrictions.eq(
          ReferencedTable.PROPERTY_REFERENCE + ".id", refValue.getId()));
      refTableCrit.setMaxResults(1);

      ReferencedTable refTable =
          (ReferencedTable) refTableCrit.uniqueResult();

      if (refTable == null) {
        log.warn("No AD_Ref_Table found for reference: {}", refValue.getId());
        return null;
      }

      Table targetTable = refTable.getTable();
      Column displayCol = refTable.getDisplayedColumn();

      Entity targetEntity = ModelProvider.getInstance()
          .getEntityByTableName(targetTable.getDBTableName());
      if (targetEntity == null) {
        log.warn("No entity for table: {}", targetTable.getDBTableName());
        return null;
      }

      // Resolve display property from column
      String displayProp;
      if (displayCol != null) {
        Property prop = targetEntity.getPropertyByColumnName(
            displayCol.getDBColumnName());
        displayProp = prop != null ? prop.getName() : "name";
      } else {
        displayProp = findIdentifierProperty(targetEntity);
      }

      // Get optional where clause
      String whereClause = refTable.getHqlwhereclause();
      if (StringUtils.isBlank(whereClause)) {
        whereClause = null;
      }

      return new SelectorMeta(targetEntity.getName(), displayProp, whereClause);

    } catch (Exception e) {
      log.warn("Could not resolve ref table for {}: {}",
          column.getDBColumnName(), e.getMessage());
      return null;
    }
  }

  /**
   * Find the first identifier property of an entity.
   * Falls back to "name" or "id" if no identifier is found.
   */
  static String findIdentifierProperty(Entity entity) {
    for (Property prop : entity.getIdentifierProperties()) {
      if (!prop.isPrimitive()) {
        continue;
      }
      return prop.getName();
    }
    // Fallback: try common property names
    if (entity.hasProperty("name")) {
      return "name";
    }
    if (entity.hasProperty("searchKey")) {
      return "searchKey";
    }
    return "id";
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
      SelectorMeta meta = resolveTarget(column, refId);
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
