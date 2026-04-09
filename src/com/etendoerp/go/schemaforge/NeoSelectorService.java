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
import org.openbravo.dal.core.OBContext;
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
 */
public class NeoSelectorService {

  private static final Logger log = LogManager.getLogger(NeoSelectorService.class);

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  /** AD_Reference base IDs for FK types — shared across Neo* and MCP classes. */
  public static final String REF_TABLE = "18";
  public static final String REF_TABLEDIR = "19";
  public static final String REF_SEARCH = "30";

  // SQL fragment constants (used in HQL/SQL construction)
  private static final String SQL_AND = " AND ";
  private static final String SQL_WHERE = " WHERE ";

  // JSON field name constants
  private static final String PARAM_SEARCH = "search";
  private static final String FIELD_LABEL = "label";
  private static final String FIELD_ITEMS = "items";
  private static final String FIELD_COLUMNS = "columns";
  private static final String FIELD_TOTAL_COUNT = "totalCount";
  private static final String FIELD_LIMIT = "limit";
  private static final String FIELD_OFFSET = "offset";
  private static final String FIELD_HAS_MORE = "hasMore";

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
      if (!isObuisel && !isFkReference(refId)) {
        return NeoResponse.error(400,
            "Field is not a FK reference: " + columnName);
      }

      SelectorMeta meta = resolveTarget(column, refId);
      if (meta == null) {
        return NeoResponse.error(500,
            "Could not resolve target for: " + columnName);
      }

      // Resolve validation rule filter from context params
      String validationFilter = resolveValidationFilter(column, meta.entityName, contextParams);

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
        hql.append(SQL_AND);
      }
      hql.append(validationFilter);
    }

    // Search filter on display property
    if (StringUtils.isNotBlank(search)) {
      if (hql.length() > 0) {
        hql.append(SQL_AND);
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

    return buildSelectorResponse(items, new JSONArray(), totalCount, limit, offset);
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
    String whereStr = buildRichQueryWhereClause(meta, search, validationFilter, alias);
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

    JSONArray columns = buildGridColumnMetadata(meta.gridFields);

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
      appendAuxFields(item, bob, meta.auxFields);
      items.put(item);
    }

    return buildSelectorResponse(items, columns, totalCount, limit, offset);
  }

  /**
   * Build the OBQuery where-string for a standard rich (OBUISEL) query.
   * Incorporates the OBUISEL where clause, validation filter, and search predicate.
   * Returns "as e [where ...]" ready to pass to {@link OBDal#createQuery}.
   */
  private static String buildRichQueryWhereClause(SelectorMeta meta,
      String search, String validationFilter, String alias) {
    StringBuilder hql = new StringBuilder();

    if (StringUtils.isNotBlank(meta.whereClause)) {
      hql.append(resolveObuiselParams(meta.whereClause));
    }

    if (StringUtils.isNotBlank(validationFilter)) {
      if (hql.length() > 0) {
        hql.append(SQL_AND);
      }
      hql.append(validationFilter);
    }

    if (StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty()) {
      if (hql.length() > 0) {
        hql.append(SQL_AND);
      }
      hql.append("(");
      for (int i = 0; i < meta.searchableProperties.size(); i++) {
        if (i > 0) {
          hql.append(" OR ");
        }
        hql.append("lower(COALESCE(cast(").append(alias).append(".")
            .append(meta.searchableProperties.get(i)).append(" as string), '')) LIKE :search");
      }
      hql.append(")");
    }

    return hql.length() > 0
        ? "as " + alias + " where " + hql
        : "as " + alias;
  }

  /**
   * Build the column-metadata JSONArray from a list of grid field descriptors.
   */
  private static JSONArray buildGridColumnMetadata(List<RichFieldMeta> gridFields)
      throws Exception {
    JSONArray columns = new JSONArray();
    for (RichFieldMeta fieldMeta : gridFields) {
      JSONObject col = new JSONObject();
      col.put("name", fieldMeta.propertyKey);
      col.put(FIELD_LABEL, fieldMeta.label);
      col.put("sortNo", fieldMeta.sortNo);
      columns.put(col);
    }
    return columns;
  }

  /**
   * Build the standard paginated selector response JSON.
   */
  private static NeoResponse buildSelectorResponse(JSONArray items, JSONArray columns,
      int totalCount, int limit, int offset) throws Exception {
    JSONObject result = new JSONObject();
    result.put(FIELD_ITEMS, items);
    result.put(FIELD_COLUMNS, columns);
    result.put(FIELD_TOTAL_COUNT, totalCount);
    result.put(FIELD_LIMIT, limit);
    result.put(FIELD_OFFSET, offset);
    result.put(FIELD_HAS_MORE, (offset + limit) < totalCount);
    return NeoResponse.ok(result);
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
    String fromClause = buildCustomHqlFromClause(
        fromOnwards, alias, meta, validationFilter, search);
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();

    // Parse SELECT column aliases to build a name→index map
    String selectPart = rawHql.substring(0, fromIdx).trim();
    String[] selectExprs = selectPart.replaceFirst("(?i)^select\\s+", "").split(",");
    Map<String, Integer> colIndexMap = buildSelectColumnIndexMap(selectExprs);

    // Count query
    String countHql = "SELECT COUNT(" + alias + ")" + fromClause;
    org.hibernate.query.Query<Long> countQuery = OBDal.getInstance()
        .getSession().createQuery(countHql, Long.class);
    if (hasSearch) {
      countQuery.setParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.uniqueResult().intValue();

    // Data query — use the ORIGINAL select columns + our filters
    String dataHql = selectPart + fromClause + " ORDER BY " + alias + "." + meta.displayProperty;
    org.hibernate.query.Query<?> dataQuery = OBDal.getInstance()
        .getSession().createQuery(dataHql);
    if (hasSearch) {
      dataQuery.setParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResults(limit);
    dataQuery.setFirstResult(offset);

    Integer idColIdx = resolveIdColumnIndex(meta, alias, colIndexMap, selectExprs);
    JSONArray columns = buildGridColumnMetadata(meta.gridFields);

    Entity entityDef = ModelProvider.getInstance().getEntity(meta.entityName);
    JSONArray items = new JSONArray();
    List<String> entityIds = new ArrayList<>();
    for (Object rawRow : dataQuery.list()) {
      Object[] row = (rawRow instanceof Object[]) ? (Object[]) rawRow : new Object[]{ rawRow };
      JSONObject item = new JSONObject();

      String recordId = extractRecordId(row, idColIdx);
      item.put("id", recordId);
      entityIds.add(recordId);
      item.put(FIELD_LABEL,
          extractDisplayLabel(row, colIndexMap, meta.displayProperty, entityDef, recordId));
      mapGridFieldsToItem(item, row, colIndexMap, meta.gridFields);
      items.put(item);
    }

    // Resolve auxiliary fields that are only obtainable via the original HQL SELECT
    boolean hasHqlOnlyAux = meta.auxFields.stream()
        .anyMatch(af -> StringUtils.isBlank(af.property) && StringUtils.isNotBlank(af.hqlAlias));
    if (hasHqlOnlyAux && !entityIds.isEmpty()) {
      resolveAuxFieldsViaHql(items, entityIds, rawHql, fromIdx, alias, meta);
    }

    return buildSelectorResponse(items, columns, totalCount, limit, offset);
  }

  /**
   * Build the FROM-onwards HQL fragment for a custom-HQL selector query.
   * Appends the OBUISEL where clause, validation filter, readable-orgs filter,
   * and (if applicable) a search predicate across the selector's searchable properties.
   *
   * @return the complete FROM clause string, ready to use in COUNT and data queries
   */
  private static String buildCustomHqlFromClause(String fromOnwards, String alias,
      SelectorMeta meta, String validationFilter, String search) {
    StringBuilder baseHql = new StringBuilder(fromOnwards);
    boolean hasWhere = Pattern.compile("\\sWHERE\\s", Pattern.CASE_INSENSITIVE)
        .matcher(fromOnwards).find();

    if (StringUtils.isNotBlank(meta.whereClause)) {
      baseHql.append(hasWhere ? SQL_AND : SQL_WHERE);
      baseHql.append(resolveObuiselParams(meta.whereClause));
      hasWhere = true;
    }

    if (StringUtils.isNotBlank(validationFilter)) {
      baseHql.append(hasWhere ? SQL_AND : SQL_WHERE);
      baseHql.append(validationFilter);
      hasWhere = true;
    }

    hasWhere = appendReadableOrgsFilter(baseHql, alias, hasWhere);
    appendCustomSearchFilter(baseHql, meta.searchableProperties, alias, search, hasWhere);

    return baseHql.toString();
  }

  /**
   * Append a readable-organizations IN filter to an HQL builder.
   * Reads the current organizations from {@link OBContext} and appends
   * {@code alias.organization.id IN ('org1', 'org2', ...)} if any exist.
   *
   * @return {@code true} if a WHERE clause is now present (was already present, or was just added)
   */
  private static boolean appendReadableOrgsFilter(StringBuilder hql, String alias,
      boolean hasWhere) {
    String[] readableOrgs = OBContext.getOBContext().getReadableOrganizations();
    if (readableOrgs == null || readableOrgs.length == 0) {
      return hasWhere;
    }
    hql.append(hasWhere ? SQL_AND : SQL_WHERE);
    hql.append(alias).append(".organization.id IN (");
    for (int i = 0; i < readableOrgs.length; i++) {
      if (i > 0) {
        hql.append(", ");
      }
      hql.append("'").append(readableOrgs[i]).append("'");
    }
    hql.append(")");
    return true;
  }

  /**
   * Append a full-text search predicate across all searchable properties.
   * Emits an OR clause: {@code (lower(COALESCE(cast(alias.prop as string), '')) LIKE :search)}.
   * No-op when {@code search} is blank or {@code searchableProps} is empty.
   */
  private static void appendCustomSearchFilter(StringBuilder hql,
      List<String> searchableProps, String alias, String search, boolean hasWhere) {
    if (StringUtils.isBlank(search) || searchableProps.isEmpty()) {
      return;
    }
    hql.append(hasWhere ? SQL_AND : SQL_WHERE).append("(");
    for (int i = 0; i < searchableProps.size(); i++) {
      if (i > 0) {
        hql.append(" OR ");
      }
      hql.append("lower(COALESCE(cast(").append(alias).append(".")
          .append(searchableProps.get(i)).append(" as string), '')) LIKE :search");
    }
    hql.append(")");
  }

  /**
   * Build a column-alias to index map from an array of SELECT expression strings.
   * Handles "expr as alias" notation and bare "table.column" expressions.
   */
  private static Map<String, Integer> buildSelectColumnIndexMap(String[] selectExprs) {
    Map<String, Integer> colIndexMap = new HashMap<>();
    for (int i = 0; i < selectExprs.length; i++) {
      String expr = selectExprs[i].trim();
      String colAlias;
      java.util.regex.Matcher asMatcher = Pattern.compile("\\s+as\\s+(\\w+)\\s*$",
          Pattern.CASE_INSENSITIVE).matcher(expr);
      if (asMatcher.find()) {
        colAlias = asMatcher.group(1);
      } else {
        int dotIdx = expr.lastIndexOf('.');
        colAlias = dotIdx >= 0 ? expr.substring(dotIdx + 1).trim() : expr.trim();
      }
      colIndexMap.put(colAlias.toLowerCase(), i);
    }
    return colIndexMap;
  }

  /**
   * Resolve the column index that holds the record ID in a custom HQL SELECT result.
   * Checks the value property alias, then "id", then scans for "{alias}.id" prefix.
   */
  private static Integer resolveIdColumnIndex(SelectorMeta meta, String alias,
      Map<String, Integer> colIndexMap, String[] selectExprs) {
    String valuePropLower = (meta.valueProperty != null ? meta.valueProperty : "id").toLowerCase();
    Integer idColIdx = colIndexMap.get(valuePropLower);
    if (idColIdx == null) {
      idColIdx = colIndexMap.get("id");
    }
    if (idColIdx == null) {
      String idPrefix = alias.toLowerCase() + ".id";
      for (int i = 0; i < selectExprs.length; i++) {
        if (selectExprs[i].trim().toLowerCase().startsWith(idPrefix)) {
          idColIdx = i;
          break;
        }
      }
    }
    return idColIdx;
  }

  /**
   * Extract and normalize the record ID from an HQL Object[] row.
   * Handles BaseOBObject, composite 64-char UUIDs, and plain string values.
   */
  private static String extractRecordId(Object[] row, Integer idColIdx) {
    Object idVal = (idColIdx != null && idColIdx < row.length) ? row[idColIdx] : row[0];
    String recordId = idVal instanceof BaseOBObject
        ? ((BaseOBObject) idVal).getId().toString()
        : String.valueOf(idVal);
    // OBUISEL selectors may return composite IDs (e.g., warehouseId + productId = 64 hex chars).
    // Etendo entity UUIDs are always 32 hex chars. Extract the actual entity ID (last 32 chars).
    if (recordId != null && recordId.length() == 64 && recordId.matches("[0-9A-Fa-f]{64}")) {
      recordId = recordId.substring(32);
    }
    return recordId;
  }

  /**
   * Extract the display label from an HQL Object[] row.
   * Falls back to loading the entity by ID if the label column is missing or empty.
   */
  private static String extractDisplayLabel(Object[] row, Map<String, Integer> colIndexMap,
      String displayProperty, Entity entityDef, String recordId) {
    String label = "";
    Integer displayIdx = colIndexMap.get(displayProperty.toLowerCase());
    if (displayIdx == null) {
      displayIdx = colIndexMap.get("productname");
    }
    if (displayIdx != null && displayIdx < row.length) {
      Object dispVal = row[displayIdx];
      label = dispVal != null ? dispVal.toString() : "";
    }
    if (label.isEmpty()) {
      try {
        BaseOBObject entity = OBDal.getInstance().get(entityDef.getName(), recordId);
        if (entity != null) label = entity.getIdentifier();
      } catch (Exception ignored) {
        // Ignored: fallback handled below
      }
    }
    return label;
  }

  /**
   * Map rich selector grid fields from an HQL Object[] row into a JSON item.
   * Resolves BaseOBObject values to their identifier strings.
   */
  private static void mapGridFieldsToItem(JSONObject item, Object[] row,
      Map<String, Integer> colIndexMap, List<RichFieldMeta> gridFields) throws Exception {
    for (RichFieldMeta fieldMeta : gridFields) {
      Integer colIdx = colIndexMap.get(fieldMeta.propertyKey.toLowerCase());
      if (colIdx != null && colIdx < row.length) {
        Object value = row[colIdx];
        if (value instanceof BaseOBObject) {
          item.put(fieldMeta.propertyKey, ((BaseOBObject) value).getIdentifier());
        } else {
          item.put(fieldMeta.propertyKey, value != null ? value : JSONObject.NULL);
        }
      }
    }
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

  /**
   * Resolve auxiliary field values for a given entity object.
   * Uses the DAL property path from the selector field to navigate the entity graph.
   * For list properties (e.g., businessPartnerLocationList), returns the first entry's ID.
   */
  private static void appendAuxFields(JSONObject item, BaseOBObject bob,
      List<AuxFieldMeta> auxFields) {
    if (auxFields == null || auxFields.isEmpty()) {
      return;
    }
    try {
      JSONObject aux = new JSONObject();
      for (AuxFieldMeta af : auxFields) {
        Object auxVal = resolveAuxFieldValue(bob, af);
        if (auxVal != null) {
          aux.put(af.suffix, auxVal.toString());
        }
      }
      if (aux.length() > 0) {
        item.put("_aux", aux);
      }
    } catch (Exception e) {
      log.debug("Error resolving aux fields for {}: {}", bob.getId(), e.getMessage());
    }
  }

  /**
   * Resolve a single auxiliary field value from a BaseOBObject.
   * Navigates the DAL property path. For FK/list properties, returns the ID.
   */
  private static Object resolveAuxFieldValue(BaseOBObject bob, AuxFieldMeta af) {
    // Try DAL property path if available
    if (StringUtils.isNotBlank(af.property)) {
      try {
        String[] parts = af.property.split("\\.");
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
        if (current instanceof BaseOBObject) {
          return ((BaseOBObject) current).getId();
        }
        if (current instanceof List) {
          List<?> list = (List<?>) current;
          if (!list.isEmpty() && list.get(0) instanceof BaseOBObject) {
            return ((BaseOBObject) list.get(0)).getId();
          }
        }
        return current;
      } catch (Exception e) {
        log.debug("Could not resolve aux property {} on {}: {}",
            af.property, bob.getId(), e.getMessage());
      }
    }
    // No property path — aux value must be resolved via HQL (see resolveAuxFieldsViaHql)
    return null;
  }

  /**
   * Resolve auxiliary field values that lack a DAL property by re-executing
   * the original OBUISEL custom HQL SELECT for the already-fetched entity IDs.
   *
   * The original HQL includes computed/joined columns (e.g., "bploc.id as locationid")
   * that are not accessible via DAL. This method:
   * 1. Parses the SELECT clause to build an alias→position map
   * 2. Finds the position of the entity ID column (by valueFieldAlias or entity alias + ".id")
   * 3. Executes the original SELECT with an IN(:ids) filter as Object[]
   * 4. Merges the aux values back into the item JSONArray by matching IDs
   */
  @SuppressWarnings("unchecked")
  private static void resolveAuxFieldsViaHql(JSONArray items, List<String> entityIds,
      String rawHql, int fromIdx, String entityAlias, SelectorMeta meta) {
    try {
      String selectClause = rawHql.substring(0, fromIdx);
      String fromOnwards = rawHql.substring(fromIdx);

      // Parse ordered alias list from SELECT: "expr as aliasname"
      List<String> aliases = parseSelectAliases(selectClause);
      if (aliases.isEmpty()) {
        log.debug("Could not parse SELECT aliases from custom HQL");
        return;
      }

      int idPos = findIdPositionInAliases(aliases, meta.valueProperty);
      if (idPos < 0) {
        log.debug("Could not find ID column in custom HQL SELECT aliases: {}", aliases);
        return;
      }

      Map<String, Integer> auxAliasPos = buildHqlAuxAliasPositionMap(meta.auxFields, aliases);
      if (auxAliasPos.isEmpty()) {
        return;
      }

      // Build and execute the aux query filtered by the already-fetched IDs
      String auxHql = buildAuxIdListQuery(selectClause + fromOnwards, entityAlias);
      auxHql = resolveObuiselParams(auxHql);

      org.hibernate.query.Query<Object[]> auxQuery = OBDal.getInstance()
          .getSession().createQuery(auxHql, Object[].class);
      auxQuery.setParameterList("auxIds", entityIds);

      Map<String, JSONObject> auxMap = buildAuxResultMap(auxQuery.list(), idPos, auxAliasPos);
      mergeAuxIntoItems(items, auxMap);

    } catch (Exception e) {
      log.warn("Could not resolve aux fields via HQL: {}", e.getMessage());
    }
  }

  /**
   * Parse the ordered list of lowercase alias names from a SELECT clause.
   * Handles optional DISTINCT and "expr as alias" notation.
   */
  private static List<String> parseSelectAliases(String selectClause) {
    java.util.regex.Matcher aliasMatcher = Pattern.compile(
        "(?:,|SELECT(?:\\s+DISTINCT)?)\\s+(.+?)\\s+[Aa][Ss]\\s+(\\w+)",
        Pattern.DOTALL).matcher(selectClause);
    List<String> aliases = new ArrayList<>();
    while (aliasMatcher.find()) {
      aliases.add(aliasMatcher.group(2).toLowerCase());
    }
    return aliases;
  }

  /**
   * Find the position of the ID column in a SELECT alias list.
   * Checks the value-property alias first, then falls back to short aliases ending in "id".
   * Returns -1 if not found.
   */
  private static int findIdPositionInAliases(List<String> aliases, String valueProperty) {
    String valueAlias = valueProperty != null ? valueProperty.toLowerCase() : "id";
    int idPos = aliases.indexOf(valueAlias);
    if (idPos >= 0) {
      return idPos;
    }
    for (int i = 0; i < aliases.size(); i++) {
      if (aliases.get(i).endsWith("id") && aliases.get(i).length() <= 6) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Build a suffix→column-position map for auxiliary fields that have an HQL alias
   * but no DAL property path.
   */
  private static Map<String, Integer> buildHqlAuxAliasPositionMap(
      List<AuxFieldMeta> auxFields, List<String> aliases) {
    Map<String, Integer> auxAliasPos = new java.util.HashMap<>();
    for (AuxFieldMeta af : auxFields) {
      if (StringUtils.isBlank(af.property) && StringUtils.isNotBlank(af.hqlAlias)) {
        int pos = aliases.indexOf(af.hqlAlias.toLowerCase());
        if (pos >= 0) {
          auxAliasPos.put(af.suffix, pos);
        }
      }
    }
    return auxAliasPos;
  }

  /**
   * Prepare an HQL query string from a raw HQL by stripping ORDER BY and appending
   * an IN(:auxIds) entity ID filter, ready for aux-field resolution.
   */
  private static String buildAuxIdListQuery(String rawHql, String entityAlias) {
    // Remove ORDER BY (not needed for aux lookup)
    int orderByIdx = rawHql.toUpperCase().lastIndexOf("ORDER BY");
    String hql = orderByIdx > 0 ? rawHql.substring(0, orderByIdx) : rawHql;

    boolean hasWhere = Pattern.compile("\\sWHERE\\s", Pattern.CASE_INSENSITIVE)
        .matcher(hql).find();
    return hql + (hasWhere ? SQL_AND : SQL_WHERE) + entityAlias + ".id IN (:auxIds)";
  }

  /**
   * Build an ID-to-aux-values map from HQL Object[] query results.
   */
  private static Map<String, JSONObject> buildAuxResultMap(List<Object[]> rows,
      int idPos, Map<String, Integer> auxAliasPos) throws Exception {
    Map<String, JSONObject> auxMap = new java.util.HashMap<>();
    for (Object[] row : rows) {
      if (row.length <= idPos || row[idPos] == null) {
        continue;
      }
      String rowId = row[idPos].toString();
      JSONObject aux = new JSONObject();
      for (Map.Entry<String, Integer> entry : auxAliasPos.entrySet()) {
        int pos = entry.getValue();
        if (pos < row.length && row[pos] != null) {
          aux.put(entry.getKey(), row[pos].toString());
        }
      }
      if (aux.length() > 0) {
        auxMap.put(rowId, aux);
      }
    }
    return auxMap;
  }

  /**
   * Merge auxiliary field values from auxMap into the corresponding items in the JSONArray.
   * Merges with any existing {@code _aux} object already present on the item.
   */
  @SuppressWarnings("unchecked")
  private static void mergeAuxIntoItems(JSONArray items, Map<String, JSONObject> auxMap)
      throws Exception {
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      String itemId = item.optString("id");
      JSONObject aux = auxMap.get(itemId);
      if (aux == null) {
        continue;
      }
      JSONObject existing = item.optJSONObject("_aux");
      if (existing != null) {
        java.util.Iterator<String> auxKeysIter = aux.keys();
        while (auxKeysIter.hasNext()) {
          String key = auxKeysIter.next();
          existing.put(key, aux.get(key));
        }
      } else {
        item.put("_aux", aux);
      }
    }
  }

  /** Pattern matching @param@ placeholders in OBUISEL clauses. */
  private static final Pattern PARAM_PATTERN = Pattern.compile("@([A-Za-z_]+)@");

  /**
   * Replace @param@ placeholders in OBUISEL where/HQL clauses with values from OBContext.
   * Known context params (AD_Org_ID, AD_Client_ID, AD_User_ID, AD_Role_ID) are resolved
   * case-insensitively. Unknown params (e.g. @inpmWarehouseId@) that depend on form context
   * are replaced with NULL since NEO selectors don't have that context yet.
   */
  private static String resolveObuiselParams(String whereClause) {
    OBContext ctx = OBContext.getOBContext();
    java.util.Map<String, String> knownParams = new java.util.HashMap<>();
    knownParams.put("ad_org_id", "'" + ctx.getCurrentOrganization().getId() + "'");
    knownParams.put("ad_client_id", "'" + ctx.getCurrentClient().getId() + "'");
    knownParams.put("ad_user_id", "'" + ctx.getUser().getId() + "'");
    knownParams.put("ad_role_id", "'" + ctx.getRole().getId() + "'");
    // Common aliases
    knownParams.put("client", "'" + ctx.getCurrentClient().getId() + "'");

    java.util.regex.Matcher m = PARAM_PATTERN.matcher(whereClause);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String paramName = m.group(1);
      String resolved = knownParams.get(paramName.toLowerCase());
      if (resolved != null) {
        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(resolved));
      } else {
        // Unknown context param — replace with NULL so the condition
        // evaluates safely rather than crashing with '@' parse error
        log.debug("Unresolved OBUISEL param @{}@ replaced with NULL", paramName);
        m.appendReplacement(sb, "NULL");
      }
    }
    m.appendTail(sb);
    return sb.toString();
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

    // Check if this is 18, 19, or 30 directly
    if (REF_TABLE.equals(refId) || REF_TABLEDIR.equals(refId)
        || REF_SEARCH.equals(refId)) {
      return refId;
    }

    // Check parent reference
    org.openbravo.model.ad.domain.Reference ref = column.getReference();
    if (ref.getParentReference() != null) {
      String parentId = ref.getParentReference().getId();
      if (REF_TABLE.equals(parentId) || REF_TABLEDIR.equals(parentId)
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

      return new SelectorMeta(
          targetEntity.getName(),
          displayProp,
          whereClause,
          true, // isRich
          isCustom,
          valueProp,
          gridFields,
          searchableProps,
          customHql,
          entityAlias,
          auxFields
      );

    } catch (Exception e) {
      log.warn("Could not resolve OBUISEL_Selector {}: {}",
          selector.getName(), e.getMessage());
      return null;
    }
  }

  /** Container for the three field lists produced by classifySelectorFields. */
  private static class ObuiselFieldLists {
    final List<RichFieldMeta> gridFields;
    final List<String> searchableProps;
    final List<AuxFieldMeta> auxFields;

    ObuiselFieldLists(List<RichFieldMeta> gridFields, List<String> searchableProps,
        List<AuxFieldMeta> auxFields) {
      this.gridFields = gridFields;
      this.searchableProps = searchableProps;
      this.auxFields = auxFields;
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

  // Matches @Param@ and @#Param@ (session-level variables in Etendo)
  private static final Pattern VALIDATION_PARAM = Pattern.compile("@#?(\\w+)@");

  // SQL functions that have no HQL equivalent and must cause a clause to be skipped
  private static final Pattern SQL_ONLY_FUNCTIONS = Pattern.compile(
      "AD_ISORGINCLUDED|AD_ISCHILDORGINCLUDED", Pattern.CASE_INSENSITIVE);

  /**
   * Resolve the column's validation rule into an HQL filter using context params.
   *
   * Validation rules are SQL-style clauses like:
   *   C_DocType.DocBaseType IN ('SOO', 'POO') AND C_DocType.IsSOTrx='@IsSOTrx@'
   *   AND (AD_ISORGINCLUDED(@AD_Org_ID@, ...) <> '-1' OR ...)
   *
   * This method applies a best-effort strategy:
   * 1. Splits the validation code into top-level AND clauses
   * 2. For each clause, checks if all @Param@ placeholders can be resolved
   * 3. Includes clauses where all params are available (or that have no params)
   * 4. Skips clauses with unresolvable params or SQL-only functions
   * 5. Converts TABLE.COLUMN references to DAL property paths (e.property)
   *
   * This ensures static filters (e.g., DocBaseType IN ('POO')) are always applied
   * even when context-dependent clauses cannot be resolved.
   */
  private static String resolveValidationFilter(Column column, String targetEntityName,
      Map<String, String> contextParams) {
    org.openbravo.model.ad.domain.Validation valRule = column.getValidation();
    if (valRule == null || StringUtils.isBlank(valRule.getValidationCode())) {
      return null;
    }

    String code = valRule.getValidationCode().trim();

    // Build combined param map: session variables + caller-provided context params
    Map<String, String> allParams = new java.util.HashMap<>();
    OBContext ctx = OBContext.getOBContext();
    allParams.put("AD_Org_ID", ctx.getCurrentOrganization().getId());
    allParams.put("AD_Client_ID", ctx.getCurrentClient().getId());
    allParams.put("AD_User_ID", ctx.getUser().getId());
    allParams.put("AD_Role_ID", ctx.getRole().getId());
    if (contextParams != null) {
      allParams.putAll(contextParams);
    }

    // Split into top-level AND clauses, respecting parentheses depth
    List<String> clauses = splitTopLevelAnd(code);

    List<String> resolvedClauses = new ArrayList<>();
    for (String clause : clauses) {
      String trimmed = clause.trim();
      if (StringUtils.isBlank(trimmed)) {
        continue;
      }

      // Skip clauses containing SQL-only functions (no HQL equivalent)
      if (SQL_ONLY_FUNCTIONS.matcher(trimmed).find()) {
        log.debug("Skipping validation clause with SQL-only function: {}", trimmed);
        continue;
      }

      // Check if all @Param@ in this clause can be resolved
      Matcher paramMatcher = VALIDATION_PARAM.matcher(trimmed);
      boolean allResolvable = true;
      while (paramMatcher.find()) {
        String paramName = paramMatcher.group(1);
        if (!allParams.containsKey(paramName)) {
          allResolvable = false;
          break;
        }
      }
      if (!allResolvable) {
        log.debug("Skipping validation clause with unresolvable params: {}", trimmed);
        continue;
      }

      // Replace @Param@ and @#Param@ with sanitized values
      StringBuffer resolved = new StringBuffer();
      paramMatcher = VALIDATION_PARAM.matcher(trimmed);
      while (paramMatcher.find()) {
        String paramName = paramMatcher.group(1);
        String value = allParams.get(paramName).replace("'", "''");
        paramMatcher.appendReplacement(resolved,
            Matcher.quoteReplacement("'" + value + "'"));
      }
      paramMatcher.appendTail(resolved);

      resolvedClauses.add(resolved.toString());
    }

    if (resolvedClauses.isEmpty()) {
      return null;
    }

    // Join resolved clauses back with AND
    String joined = String.join(SQL_AND, resolvedClauses);

    // Convert SQL TABLE.COLUMN references to HQL e.property paths
    String hqlFilter = convertSqlToHql(joined, targetEntityName);
    return hqlFilter;
  }

  /**
   * Split a SQL WHERE clause into top-level AND segments.
   * Respects parentheses nesting so that "A AND (B OR C AND D) AND E"
   * splits into ["A", "(B OR C AND D)", "E"], not into inner parts.
   */
  private static List<String> splitTopLevelAnd(String code) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    String upper = code.toUpperCase();

    for (int i = 0; i < code.length(); i++) {
      char c = code.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (depth == 0 && i + 3 < code.length()) {
        // Check for top-level AND (preceded/followed by whitespace or newline)
        if (upper.substring(i, i + 3).equals("AND")
            && (i == 0 || !Character.isLetterOrDigit(code.charAt(i - 1)))
            && !Character.isLetterOrDigit(code.charAt(i + 3))) {
          parts.add(code.substring(start, i).trim());
          start = i + 3;
        }
      }
    }
    // Add the last segment
    String last = code.substring(start).trim();
    if (!last.isEmpty()) {
      parts.add(last);
    }
    return parts;
  }

  /**
   * Convert a SQL-style validation clause to HQL.
   * Replaces TABLE.COLUMN with e.dalProperty, handling FK columns (_ID -> .id).
   * Also converts bare column names (without table prefix) to e.property paths.
   *
   * Example: "C_DocType.DocBaseType IN ('POO')" -> "e.documentType IN ('POO')"
   * Example: "docsubtypeso like 'OB'" -> "e.sOSubType like 'OB'"
   */
  private static String convertSqlToHql(String sqlClause, String targetEntityName) {
    try {
      Entity targetEntity = ModelProvider.getInstance().getEntity(targetEntityName);
      if (targetEntity == null) {
        return sqlClause;
      }

      String tableName = targetEntity.getTableName();

      // Step 1: Replace TABLE.COLUMN patterns with e.property
      Pattern tableColPattern = Pattern.compile(
          Pattern.quote(tableName) + "\\.(\\w+)", Pattern.CASE_INSENSITIVE);
      Matcher m = tableColPattern.matcher(sqlClause);

      StringBuffer result = new StringBuffer();
      while (m.find()) {
        String dbColName = m.group(1);
        String replacement = resolveColumnToHql(targetEntity, dbColName);
        m.appendReplacement(result, Matcher.quoteReplacement(replacement));
      }
      m.appendTail(result);
      String afterTableRef = result.toString();

      // Step 2: Replace bare column names that are NOT already prefixed with "e."
      // Match word tokens that could be column names (not inside quotes, not SQL keywords)
      Pattern bareColPattern = Pattern.compile("(?<![.'\"])\\b(\\w+)\\b(?!\\s*\\()");
      m = bareColPattern.matcher(afterTableRef);
      result = new StringBuffer();
      java.util.Set<String> sqlKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
          "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN",
          "COALESCE", "CAST", "AS", "SELECT", "FROM", "WHERE", "ORDER", "BY",
          "ASC", "DESC", "TRUE", "FALSE", "ESCAPE", "e"));
      while (m.find()) {
        String token = m.group(1);
        // Skip SQL keywords, string literals, numbers, and already-resolved "e." references
        if (sqlKeywords.contains(token.toUpperCase())
            || token.matches("\\d+")
            || (m.start() > 0 && afterTableRef.charAt(m.start() - 1) == '.')) {
          continue;
        }
        try {
          Property prop = targetEntity.getPropertyByColumnName(token);
          if (prop != null) {
            String replacement = resolveColumnToHql(targetEntity, token);
            m.appendReplacement(result, Matcher.quoteReplacement(replacement));
          }
        } catch (Exception ignored) {
          // Not a column name, leave as-is
        }
      }
      m.appendTail(result);
      return result.toString();
    } catch (Exception e) {
      log.warn("Could not convert SQL to HQL for entity {}: {}", targetEntityName, e.getMessage());
      return sqlClause;
    }
  }

  /**
   * Resolve a single DB column name to an HQL "e.property" path.
   */
  private static String resolveColumnToHql(Entity targetEntity, String dbColName) {
    Property prop = targetEntity.getPropertyByColumnName(dbColName);
    if (prop != null) {
      if (!prop.isPrimitive() && prop.getTargetEntity() != null) {
        return "e." + prop.getName() + ".id";
      }
      return "e." + prop.getName();
    }
    return "e." + dbColName;
  }

  /**
   * Holds resolved target metadata for a selector.
   */
  private static class SelectorMeta {
    final String entityName;
    final String displayProperty;
    final String whereClause;
    final boolean isRich;
    final boolean isCustomQuery;
    final String valueProperty;
    final List<RichFieldMeta> gridFields;
    final List<String> searchableProperties;
    final String customHql;
    final String entityAlias;
    final List<AuxFieldMeta> auxFields;

    /** Constructor for simple selectors (TableDir, Table, Search). */
    SelectorMeta(String entityName, String displayProperty, String whereClause) {
      this(entityName, displayProperty, whereClause,
          false, false, "id",
          new ArrayList<>(), new ArrayList<>(),
          null, "e", new ArrayList<>());
    }

    /** Constructor for rich (OBUISEL) selectors. */
    SelectorMeta(String entityName, String displayProperty, String whereClause,
        boolean isRich, boolean isCustomQuery, String valueProperty,
        List<RichFieldMeta> gridFields, List<String> searchableProperties,
        String customHql, String entityAlias, List<AuxFieldMeta> auxFields) {
      this.entityName = entityName;
      this.displayProperty = displayProperty;
      this.whereClause = whereClause;
      this.isRich = isRich;
      this.isCustomQuery = isCustomQuery;
      this.valueProperty = valueProperty;
      this.gridFields = gridFields;
      this.searchableProperties = searchableProperties;
      this.customHql = customHql;
      this.entityAlias = entityAlias;
      this.auxFields = auxFields;
    }
  }

  /**
   * Metadata for a single field in a rich selector grid.
   */
  private static class RichFieldMeta {
    final String propertyKey;  // last segment of property path
    final String label;        // display name
    final String property;     // full DAL property path
    final long sortNo;

    RichFieldMeta(String propertyKey, String label, String property, long sortNo) {
      this.propertyKey = propertyKey;
      this.label = label;
      this.property = property;
      this.sortNo = sortNo;
    }
  }

  /**
   * Metadata for an auxiliary output field in an OBUISEL selector.
   * Auxiliary fields are defined with isOutField=Y and a SUFFIX (e.g., "_LOC").
   * They provide extra data (like a default location ID) alongside the selected value.
   */
  private static class AuxFieldMeta {
    final String suffix;              // e.g., "_LOC", "_CON"
    final String hqlAlias;            // e.g., "locationid" (from displayColumnAlias)
    final String name;                // display name of the field
    final String property;            // DAL property path (if available)

    AuxFieldMeta(String suffix, String hqlAlias, String name, String property) {
      this.suffix = suffix;
      this.hqlAlias = hqlAlias;
      this.name = name;
      this.property = property;
    }
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
        return resolveAuxViaHql(meta, fieldName, recordId);
      }

      // For DAL-resolvable aux fields, load the entity and read properties.
      BaseOBObject bob = loadEntityForAux(meta, recordId);
      if (bob == null) {
        log.debug("No record found in {} for value {} (valueProperty={})",
            meta.entityName, recordId, meta.valueProperty);
        return null;
      }

      JSONObject result = new JSONObject();
      for (AuxFieldMeta af : meta.auxFields) {
        Object auxVal = resolveAuxFieldValue(bob, af);
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

  /**
   * Load the entity object needed to resolve DAL aux field values.
   * If valueProperty is "id", loads directly. Otherwise, queries by property value.
   */
  private static BaseOBObject loadEntityForAux(SelectorMeta meta, String recordId) {
    if ("id".equals(meta.valueProperty)) {
      return (BaseOBObject) OBDal.getInstance().get(meta.entityName, recordId);
    }
    // Query by the value property path (e.g., "product.id" for ProductByPriceAndWarehouse)
    try {
      String hql = "from " + meta.entityName + " where " + meta.valueProperty + " = :val";
      org.hibernate.query.Query<?> q = OBDal.getInstance().getSession().createQuery(hql);
      q.setParameter("val", recordId);
      q.setMaxResults(1);
      List<?> results = q.list();
      if (!results.isEmpty()) {
        return (BaseOBObject) results.get(0);
      }
    } catch (Exception e) {
      log.debug("Could not query {} by {}: {}", meta.entityName, meta.valueProperty, e.getMessage());
    }
    return null;
  }

  /**
   * Resolve aux values via the original OBUISEL custom HQL for a single record.
   */
  @SuppressWarnings("unchecked")
  private static JSONObject resolveAuxViaHql(SelectorMeta meta, String fieldName,
      String recordId) {
    try {
      String hql = meta.customHql;
      String selectClause = hql.substring(0, hql.toUpperCase().indexOf(" FROM "));
      String[] selectParts = selectClause.replaceFirst("(?i)^\\s*select\\s+", "").split(",");
      Map<String, Integer> aliasPos = buildAuxAliasPositionMap(selectParts);

      String fullHql = buildAuxHqlWithIdFilter(hql, selectClause, meta.entityAlias);
      return executeAuxHqlQuery(fullHql, recordId, aliasPos, meta.auxFields, fieldName);

    } catch (Exception e) {
      log.debug("Could not resolve aux via HQL for {}: {}", fieldName, e.getMessage());
      return null;
    }
  }

  /**
   * Build an alias-to-index map from the SELECT parts of a custom HQL clause.
   */
  private static Map<String, Integer> buildAuxAliasPositionMap(String[] selectParts) {
    Map<String, Integer> aliasPos = new java.util.HashMap<>();
    for (int i = 0; i < selectParts.length; i++) {
      String part = selectParts[i].trim();
      int asIdx = part.toLowerCase().lastIndexOf(" as ");
      if (asIdx >= 0) {
        String alias = part.substring(asIdx + 4).trim().replace("\"", "");
        aliasPos.put(alias.toLowerCase(), i);
      }
    }
    return aliasPos;
  }

  /**
   * Append an entity ID filter to the HQL and return the full query string.
   */
  private static String buildAuxHqlWithIdFilter(String hql, String selectClause,
      String entityAlias) {
    String fromClause = hql.substring(hql.toUpperCase().indexOf(" FROM "));
    String fullHql = selectClause + fromClause;
    if (fullHql.toUpperCase().contains(SQL_WHERE)) {
      fullHql += SQL_AND + entityAlias + ".id = :recordId";
    } else {
      fullHql += SQL_WHERE + entityAlias + ".id = :recordId";
    }
    return fullHql;
  }

  /**
   * Execute an aux HQL query and map results to a JSON object keyed by fieldName + suffix.
   */
  private static JSONObject executeAuxHqlQuery(String fullHql, String recordId,
      Map<String, Integer> aliasPos, List<AuxFieldMeta> auxFields,
      String fieldName) throws Exception {
    org.hibernate.query.Query<?> query = OBDal.getInstance().getSession().createQuery(fullHql);
    query.setParameter("recordId", recordId);
    query.setMaxResults(1);

    List<?> results = query.list();
    if (results.isEmpty()) {
      return null;
    }

    Object row = results.get(0);
    Object[] cols = row instanceof Object[] ? (Object[]) row : new Object[]{ row };

    JSONObject result = new JSONObject();
    for (AuxFieldMeta af : auxFields) {
      Integer pos = aliasPos.get(af.hqlAlias.toLowerCase());
      if (pos != null && pos < cols.length && cols[pos] != null) {
        Object val = cols[pos];
        if (val instanceof BaseOBObject) {
          val = ((BaseOBObject) val).getId();
        }
        result.put(fieldName + af.suffix, val.toString());
      }
    }
    return result.length() > 0 ? result : null;
  }
}
