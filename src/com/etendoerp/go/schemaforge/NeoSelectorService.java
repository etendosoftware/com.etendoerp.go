package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.ReferencedTable;
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

  /** AD_Reference base IDs for FK types — shared across Neo* and MCP classes. */
  public static final String REF_TABLE = "18";
  public static final String REF_TABLEDIR = "19";
  public static final String REF_SEARCH = "30";
  public static final String REF_LIST = "17";
  public static final String REF_OBUISEL = "95E2A8B50A254B2AAE6774B8C2F28120";

  // JSON field name constants
  private static final String PARAM_SEARCH = "search";
  private static final String FIELD_LABEL = "label";

  // Session-level params resolved server-side (should not appear in selectorParams)
  private static final java.util.Set<String> SESSION_PARAMS = new java.util.HashSet<>(
      java.util.Arrays.asList("AD_Org_ID", "AD_Client_ID", "AD_User_ID", "AD_Role_ID"));

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
        JSONObject item = buildSelectorItemForField(field);
        if (item != null) {
          selectors.put(item);
        }
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
   * Resolve and build the JSON selector descriptor for a single {@link SFField}.
   * Returns {@code null} when the field has no column, is not a FK reference, or the
   * target selector cannot be resolved — all cases that should be silently skipped.
   */
  private static JSONObject buildSelectorItemForField(SFField field) throws Exception {
    Column column = field.getADColumn();
    if (column == null) {
      return null;
    }
    String refId = getBaseReferenceId(column);
    if (isListReference(refId)) {
      return buildListSelectorItem(column);
    }
    boolean isObuisel = hasObuiselSelector(column);
    if (!isObuisel && !isFkReference(refId)) {
      return null;
    }
    SelectorMeta meta = resolveTarget(column, refId);
    if (meta == null) {
      return null;
    }
    return buildSelectorItem(column, refId, meta);
  }

  /**
   * Build the JSON descriptor object for a List reference selector.
   */
  private static JSONObject buildListSelectorItem(Column column) throws Exception {
    JSONObject item = new JSONObject();
    item.put("columnName", column.getDBColumnName());
    item.put("referenceType", "List");
    item.put("type", "list");
    return item;
  }

  /**
   * Build the JSON descriptor object for a single selector field.
   * Extracts reference type, aux fields, and selector params from the column metadata.
   */
  private static JSONObject buildSelectorItem(Column column, String refId,
      SelectorMeta meta) throws Exception {
    JSONObject item = new JSONObject();
    item.put("columnName", column.getDBColumnName());
    if (meta.isRich) {
      item.put("referenceType", "OBUISEL");
      item.put("type", "rich");
    } else {
      String referenceType;
      if (refId.equals(REF_TABLE)) {
        referenceType = "Table";
      } else if (refId.equals(REF_TABLEDIR)) {
        referenceType = "TableDir";
      } else {
        referenceType = "Search";
      }
      item.put("referenceType", referenceType);
      item.put("type", "simple");
    }
    item.put("targetEntity", meta.entityName);
    item.put("displayProperty", meta.displayProperty);

    if (meta.auxFields != null && !meta.auxFields.isEmpty()) {
      JSONArray auxArray = buildAuxFieldsArray(meta.auxFields);
      item.put("auxFields", auxArray);
    }

    JSONArray params = extractSelectorParams(column);
    if (params.length() > 0) {
      item.put("selectorParams", params);
    }
    return item;
  }

  /**
   * Build the JSON array describing auxiliary output fields for a selector.
   */
  private static JSONArray buildAuxFieldsArray(List<AuxFieldMeta> auxFields) throws Exception {
    JSONArray auxArray = new JSONArray();
    for (AuxFieldMeta af : auxFields) {
      JSONObject auxItem = new JSONObject();
      auxItem.put("suffix", af.suffix);
      auxItem.put("name", af.name);
      auxArray.put(auxItem);
    }
    return auxArray;
  }

  /**
   * Extract non-session {@code @Param@} references from the column's validation rule.
   * Session-level params (prefixed with {@code #}) and standard context params are excluded.
   *
   * @param column the AD_Column whose validation rule is inspected
   * @return JSONArray of resolvable parameter names; empty if none
   */
  private static JSONArray extractSelectorParams(Column column) {
    JSONArray params = new JSONArray();
    org.openbravo.model.ad.domain.Validation valRule = column.getValidation();
    if (valRule == null || StringUtils.isBlank(valRule.getValidationCode())) {
      return params;
    }
    Pattern paramExtract = Pattern.compile("@(#?)(\\w+)@");
    Matcher m = paramExtract.matcher(valRule.getValidationCode());
    java.util.Set<String> seen = new java.util.HashSet<>();
    while (m.find()) {
      boolean isSession = "#".equals(m.group(1));
      String param = m.group(2);
      if (!isSession && !SESSION_PARAMS.contains(param) && seen.add(param)) {
        params.put(param);
      }
    }
    return params;
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
      if (sfField == null) {
        return NeoResponse.error(404,
            "Field not found or not included: " + columnName);
      }

      Column column = sfField.getADColumn();
      if (column == null) {
        return NeoResponse.error(500,
            "Could not resolve AD_Column for field: " + columnName);
      }

      return querySelectorByColumn(column, columnName, search, limit, offset, contextParams);

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

      String refId = getBaseReferenceId(column);
      boolean isObuisel = hasObuiselSelector(column);
      boolean isList = isListReference(refId);
      if (!isObuisel && !isFkReference(refId) && !isList) {
        return NeoResponse.error(400,
            "Field is not a FK reference: " + columnName);
      }
      if (isList) {
        return resolveListSelector(column, search, limit, offset);
      }

      SelectorMeta meta = resolveTarget(column, refId);
      if (meta == null) {
        return NeoResponse.error(500,
            "Could not resolve target for: " + columnName);
      }

      // Resolve validation rule filter from context params
      String validationFilter = SelectorQueryBuilder.resolveValidationFilter(
          column, meta.entityName, contextParams);

      // Build and execute query
      if (meta.isRich) {
        return executeRichQuery(meta, search, limit, offset, validationFilter);
      }
      return executeQuery(meta, search, limit, offset, validationFilter);

    } catch (Exception e) {
      log.error("Error querying selector by column {}", columnName, e);
      return NeoResponse.error(500, e.getMessage());
    }
  }

  /**
   * Execute the paginated query against the target entity (simple selectors).
   */
  private static NeoResponse executeQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter) throws Exception {

    StringBuilder hql = new StringBuilder();

    // Apply where clause from AD_Ref_Table if present
    if (StringUtils.isNotBlank(meta.whereClause)) {
      hql.append(meta.whereClause);
    }

    // Apply validation rule filter (resolved from context params)
    if (StringUtils.isNotBlank(validationFilter)) {
      if (hql.length() > 0) {
        hql.append(SelectorQueryBuilder.SQL_AND);
      }
      hql.append(validationFilter);
    }

    // Apply readable org filter for org-aware entities (including "*" org 0)
    String readableOrgsFilter = SelectorQueryBuilder.buildReadableOrgsPredicate(
        meta.entityName, "e", true);
    if (StringUtils.isNotBlank(readableOrgsFilter)) {
      if (hql.length() > 0) {
        hql.append(SelectorQueryBuilder.SQL_AND);
      }
      hql.append(readableOrgsFilter);
    }

    // Search filter on display property
    if (StringUtils.isNotBlank(search)) {
      if (hql.length() > 0) {
        hql.append(SelectorQueryBuilder.SQL_AND);
      }
      hql.append("lower(e.").append(meta.displayProperty)
          .append(") LIKE :search");
    }

    // Prefix with alias "as e" so OBQuery registers the entity alias
    String whereStr = hql.length() > 0 ? "as e where " + hql.toString() : "as e";

    // Count query
    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, whereStr);
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
    if (StringUtils.isNotBlank(search)) {
      dataQuery.setNamedParameter(PARAM_SEARCH,
          "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    // Build results
    JSONArray items = new JSONArray();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      item.put("id", bob.getId());
      item.put(FIELD_LABEL, bob.getIdentifier());
      items.put(item);
    }

    return SelectorQueryBuilder.buildSelectorResponse(items, new JSONArray(), totalCount, limit, offset);
  }

  /**
   * Execute a rich (OBUISEL) selector query with multi-column response.
   */
  private static NeoResponse executeRichQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter) throws Exception {

    if (meta.isCustomQuery && StringUtils.isNotBlank(meta.customHql)) {
      return executeCustomHqlQuery(meta, search, limit, offset, validationFilter);
    }
    // Custom query flag set but no HQL defined: fall through to standard query

    String alias = "e";
    String whereStr = SelectorQueryBuilder.buildRichQueryWhereClause(meta, search, validationFilter, alias);
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();

    // Count query
    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, whereStr);
    if (hasSearch) {
      countQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    // Data query with ordering and pagination
    String dataWhere = whereStr + " ORDER BY " + alias + "." + meta.displayProperty;
    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance()
        .createQuery(meta.entityName, dataWhere);
    if (hasSearch) {
      dataQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    JSONArray columns = SelectorQueryBuilder.buildGridColumnMetadata(meta.gridFields);

    Entity entityDef = ModelProvider.getInstance().getEntity(meta.entityName);
    JSONArray items = new JSONArray();
    List<String> entityIds = new ArrayList<>();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      item.put("id", bob.getId());
      item.put(FIELD_LABEL, bob.getIdentifier());
      entityIds.add(bob.getId().toString());

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
      String search, int limit, int offset, String validationFilter) throws Exception {

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
    String fromClause = SelectorQueryBuilder.buildCustomHqlFromClause(
        fromOnwards, alias, meta, validationFilter, search);
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();

    // Parse SELECT column aliases to build a name→index map
    String selectPart = rawHql.substring(0, fromIdx).trim();
    String[] selectExprs = selectPart.replaceFirst("(?i)^select\\s+", "").split(",");
    Map<String, Integer> colIndexMap = SelectorQueryBuilder.buildSelectColumnIndexMap(selectExprs);

    // Count query
    String countHql = "SELECT COUNT(" + alias + ")" + fromClause;
    org.hibernate.query.Query<Long> countQuery = OBDal.getInstance()
        .getSession().createQuery(countHql, Long.class);
    if (hasSearch) {
      countQuery.setParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    Long countResult = countQuery.uniqueResult();
    int totalCount = (countResult != null) ? countResult.intValue() : 0;

    // Data query — use the ORIGINAL select columns + our filters
    String dataHql = selectPart + fromClause + " ORDER BY " + alias + "." + meta.displayProperty;
    org.hibernate.query.Query<?> dataQuery = OBDal.getInstance()
        .getSession().createQuery(dataHql);
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
  private static String getBaseReferenceId(Column column) {
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
  private static boolean isListReference(String refId) {
    return REF_LIST.equals(refId);
  }

  /**
   * Resolve list values for a List reference (AD_Reference type 17).
   * Queries AD_REF_LIST using the column's AD_Reference_Value_ID (referenceSearchKey).
   */
  @SuppressWarnings("unchecked")
  private static NeoResponse resolveListSelector(Column column, String search,
      int limit, int offset) throws Exception {

    org.openbravo.model.ad.domain.Reference listRef = column.getReferenceSearchKey();
    if (listRef == null) {
      // Fallback: use the column's own reference (for inline list definitions)
      listRef = column.getReference();
    }

    // Apply static validation rule clauses only (skip context-based @param@ rules).
    String valRuleSql = null;
    org.openbravo.model.ad.domain.Validation valRule = column.getValidation();
    if (valRule != null && StringUtils.isNotBlank(valRule.getValidationCode())) {
      String ruleCode = valRule.getValidationCode().trim();
      if (!ruleCode.contains("@")) {
        valRuleSql = ruleCode;
      }
    }

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
  private static boolean hasObuiselSelector(Column column) {
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
  private static SelectorMeta resolveTarget(Column column, String baseRefId) {
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
      return resolveRefTable(column);
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
   * Add grid-column and searchable-property entries for a selector field that has a property path.
   * Fields with a blank property are skipped.
   * Fields whose property ends with {@code _identifier} are excluded from search
   * (virtual DAL property, not Hibernate-mapped).
   */
  private static void collectGridAndSearchFields(SelectorField sf,
      List<RichFieldMeta> gridFields, List<String> searchableProps) {
    String prop = sf.getProperty();
    if (StringUtils.isBlank(prop)) {
      return;
    }
    if (Boolean.TRUE.equals(sf.isShowingrid())) {
      String propKey = getLastSegment(prop);
      Long sortNo = sf.getSortno();
      gridFields.add(new RichFieldMeta(propKey, sf.getName(), prop,
          sortNo != null ? sortNo : 0L));
    }
    if (Boolean.TRUE.equals(sf.isSearchinsuggestionbox()) && !prop.endsWith("_identifier")) {
      searchableProps.add(prop);
    }
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
  private static String findIdentifierProperty(Entity entity) {
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
