/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

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
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.Query;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.application.ApplicationUtils;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.ReferencedTable;
import org.openbravo.model.ad.ui.Tab;
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

  // AD_Reference base IDs for FK types
  private static final String REF_TABLE = "18";
  private static final String REF_TABLEDIR = "19";
  private static final String REF_SEARCH = "30";
  private static final String REF_LIST = "17";

  // Session-level params resolved server-side (should not appear in selectorParams)
  private static final java.util.Set<String> SESSION_PARAMS = new java.util.HashSet<>(
      java.util.Arrays.asList("AD_Org_ID", "AD_Client_ID", "AD_User_ID", "AD_Role_ID"));

  private static final String AD_ORG_ID = "AD_Org_ID";
  private static final String SQL_AND = " AND ";
  private static final String SQL_WHERE = " WHERE ";
  private static final String SQL_ORDER_BY = "ORDER BY";
  private static final String SQL_AS = " as ";
  private static final String SEARCH_PARAM = "search";
  private static final String AUX_IDS_PARAM = "auxIds";
  private static final String PROP_ORGANIZATION = "organization";
  private static final String JSON_ID = "id";
  private static final String JSON_LABEL = "label";
  private static final String JSON_ITEMS = "items";
  private static final String JSON_COLUMNS = "columns";
  private static final String JSON_TOTAL_COUNT = "totalCount";
  private static final String JSON_LIMIT = "limit";
  private static final String JSON_OFFSET = "offset";
  private static final String JSON_HAS_MORE = "hasMore";
  private static final String JSON_AUX = "_aux";
  private static final String JSON_NAME = "name";
  private static final String JSON_SORT_NO = "sortNo";
  private static final Pattern FROM_CLAUSE_PATTERN = Pattern.compile("\\sFROM\\s",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern WHERE_CLAUSE_PATTERN = Pattern.compile("\\sWHERE\\s",
      Pattern.CASE_INSENSITIVE);

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

        String refId = getBaseReferenceId(column);
        // Check for OBUISEL, classic FK reference, or List reference (type 17)
        boolean isObuisel = hasObuiselSelector(column);
        boolean isList = isListReference(refId);
        if (!isObuisel && !isFkReference(refId) && !isList) {
          continue;
        }

        if (isList) {
          JSONObject item = new JSONObject();
          item.put("columnName", column.getDBColumnName());
          item.put("referenceType", "List");
          item.put("type", "list");
          selectors.put(item);
          continue;
        }

        SelectorMeta meta = resolveTarget(column, refId);
        if (meta == null) {
          continue;
        }

        JSONObject item = new JSONObject();
        item.put("columnName", column.getDBColumnName());
        if (meta.isRich) {
          item.put("referenceType", "OBUISEL");
          item.put("type", "rich");
        } else {
          item.put("referenceType", refId.equals(REF_TABLE) ? "Table"
              : refId.equals(REF_TABLEDIR) ? "TableDir" : "Search");
          item.put("type", "simple");
        }
        item.put("targetEntity", meta.entityName);
        item.put("displayProperty", meta.displayProperty);

        // Include auxiliary field info if present
        if (meta.auxFields != null && !meta.auxFields.isEmpty()) {
          JSONArray auxArray = new JSONArray();
          for (AuxFieldMeta af : meta.auxFields) {
            JSONObject auxItem = new JSONObject();
            auxItem.put("suffix", af.suffix);
            auxItem.put("name", af.name);
            auxArray.put(auxItem);
          }
          item.put("auxFields", auxArray);
        }

        // Include selectorParams from validation rule (only non-session params)
        org.openbravo.model.ad.domain.Validation valRule = column.getValidation();
        if (valRule != null && StringUtils.isNotBlank(valRule.getValidationCode())) {
          JSONArray params = new JSONArray();
          // Use a pattern that captures the optional # prefix to identify session params
          Pattern paramExtract = Pattern.compile("@(#?)(\\w+)@");
          Matcher m = paramExtract.matcher(valRule.getValidationCode());
          java.util.Set<String> seen = new java.util.HashSet<>();
          while (m.find()) {
            boolean isSession = "#".equals(m.group(1));
            String param = m.group(2);
            // Skip session-level params (resolved server-side) and standard context params
            if (!isSession && !SESSION_PARAMS.contains(param) && seen.add(param)) {
              params.put(param);
            }
          }
          if (params.length() > 0) {
            item.put("selectorParams", params);
          }
        }

        selectors.put(item);
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
      if (sfField == null) {
        return NeoResponse.error(404,
            "Field not found or not included: " + columnName);
      }

      Column column = sfField.getADColumn();
      if (column == null) {
        return NeoResponse.error(500,
            "Could not resolve AD_Column for field: " + columnName);
      }

      String refId = getBaseReferenceId(column);
      boolean isObuisel = hasObuiselSelector(column);
      boolean isList = isListReference(refId);
      if (!isObuisel && !isFkReference(refId) && !isList) {
        return NeoResponse.error(400,
            "Field is not a FK reference: " + columnName);
      }

      // List references (type 17) query AD_REF_LIST directly
      if (isList) {
        return resolveListSelector(column, search, limit, offset);
      }

      SelectorMeta meta = resolveTarget(column, refId);
      if (meta == null) {
        return NeoResponse.error(500,
            "Could not resolve target for: " + columnName);
      }

      // Resolve validation rule filter from context params
      String validationFilter = resolveValidationFilter(column, meta.entityName, contextParams);
      String organizationFilter = resolveOrgFilter(entity, column, meta, contextParams);
      String combinedFilter = combineFilters(validationFilter, organizationFilter);

      // Build and execute query
      if (meta.isRich) {
        return executeRichQuery(meta, search, limit, offset, combinedFilter);
      }
      return executeQuery(meta, search, limit, offset, combinedFilter);

    } catch (Exception e) {
      log.error("Error querying selector {}/{}", entityName, columnName, e);
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

    // Apply natural org tree filter — mirrors Classic Etendo lookup behavior
    String orgFilter = buildNaturalOrgFilter(meta.entityName, "e");
    if (orgFilter != null) {
      if (hql.length() > 0) {
        hql.append(" AND ");
      }
      hql.append(orgFilter);
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
      countQuery.setNamedParameter("search",
          "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    // Data query with ordering and pagination
    String orderBy = " ORDER BY e." + meta.displayProperty;
    String dataWhere = whereStr + orderBy;

    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance()
        .createQuery(meta.entityName, dataWhere);
    if (StringUtils.isNotBlank(search)) {
      dataQuery.setNamedParameter("search",
          "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    // Build results
    JSONArray items = new JSONArray();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      item.put(JSON_ID, bob.getId());
      item.put(JSON_LABEL, bob.getIdentifier());
      items.put(item);
    }

    JSONObject result = new JSONObject();
    result.put(JSON_ITEMS, items);
    result.put(JSON_COLUMNS, new JSONArray());
    result.put(JSON_TOTAL_COUNT, totalCount);
    result.put(JSON_LIMIT, limit);
    result.put(JSON_OFFSET, offset);
    result.put(JSON_HAS_MORE, (offset + limit) < totalCount);
    return NeoResponse.ok(result);
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
    StringBuilder hql = new StringBuilder();

    // Apply where clause from OBUISEL_Selector with @param@ substitution
    if (StringUtils.isNotBlank(meta.whereClause)) {
      String resolved = resolveObuiselParams(meta.whereClause);
      hql.append(resolved);
    }

    // Apply validation rule filter (resolved from context params)
    if (StringUtils.isNotBlank(validationFilter)) {
      if (hql.length() > 0) {
        hql.append(SQL_AND);
      }
      hql.append(validationFilter);
    }

    // Apply natural org tree filter — mirrors Classic Etendo lookup behavior
    String orgFilter = buildNaturalOrgFilter(meta.entityName, alias);
    if (orgFilter != null) {
      if (hql.length() > 0) {
        hql.append(" AND ");
      }
      hql.append(orgFilter);
    }

    // Search filter: OR across all searchable properties
    if (StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty()) {
      if (hql.length() > 0) {
        hql.append(SQL_AND);
      }
      hql.append("(");
      for (int i = 0; i < meta.searchableProperties.size(); i++) {
        if (i > 0) {
          hql.append(" OR ");
        }
        String prop = meta.searchableProperties.get(i);
        hql.append("lower(COALESCE(cast(").append(alias).append(".")
            .append(prop).append(" as string), '')) LIKE :search");
      }
      hql.append(")");
    }

    // Prefix with alias "as e" so OBQuery registers the entity alias
    String whereStr = hql.length() > 0
        ? "as " + alias + " where " + hql.toString()
        : "as " + alias;

    // Count query
    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, whereStr);
    if (StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty()) {
      countQuery.setNamedParameter("search",
          "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    // Data query with ordering and pagination
    String orderBy = " ORDER BY " + alias + "." + meta.displayProperty;
    String dataWhere = whereStr + orderBy;

    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance()
        .createQuery(meta.entityName, dataWhere);
    if (StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty()) {
      dataQuery.setNamedParameter("search",
          "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    // Build column metadata
    JSONArray columns = new JSONArray();
    for (RichFieldMeta fieldMeta : meta.gridFields) {
      JSONObject col = new JSONObject();
      col.put(JSON_NAME, fieldMeta.propertyKey);
      col.put(JSON_LABEL, fieldMeta.label);
      col.put(JSON_SORT_NO, fieldMeta.sortNo);
      columns.put(col);
    }

    // Build results with all grid fields
    Entity entityDef = ModelProvider.getInstance()
        .getEntity(meta.entityName);
    JSONArray items = new JSONArray();
    List<String> entityIds = new ArrayList<>();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      item.put(JSON_ID, bob.getId());
      item.put(JSON_LABEL, bob.getIdentifier());
      entityIds.add(bob.getId().toString());

      // Add all grid fields
      for (RichFieldMeta fieldMeta : meta.gridFields) {
        Object value = resolvePropertyValue(bob, fieldMeta.property, entityDef);
        item.put(fieldMeta.propertyKey, value != null ? value : JSONObject.NULL);
      }

      // Resolve auxiliary fields that have a DAL property path
      appendAuxFields(item, bob, meta.auxFields);

      items.put(item);
    }

    JSONObject result = new JSONObject();
    result.put(JSON_ITEMS, items);
    result.put(JSON_COLUMNS, columns);
    result.put(JSON_TOTAL_COUNT, totalCount);
    result.put(JSON_LIMIT, limit);
    result.put(JSON_OFFSET, offset);
    result.put(JSON_HAS_MORE, (offset + limit) < totalCount);
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
    CustomHqlContext context = prepareCustomHqlContext(meta, search, validationFilter);
    int totalCount = executeCustomCountQuery(context, search);
    Query<?> dataQuery = buildCustomDataQuery(context, meta, search, limit, offset);
    Integer idColIdx = resolveIdColumnIndex(context.colIndexMap, meta.valueProperty,
        context.alias, context.selectExprs);

    Entity entityDef = ModelProvider.getInstance().getEntity(meta.entityName);
    List<String> entityIds = new ArrayList<>();
    JSONArray items = buildItemsFromRows(dataQuery.list(), context.colIndexMap, idColIdx,
        meta, entityDef, entityIds);

    if (hasHqlOnlyAuxFields(meta) && !entityIds.isEmpty()) {
      resolveAuxFieldsViaHql(items, entityIds, context.rawHql, context.fromIdx,
          context.alias, meta);
    }

    return buildSelectorResponse(items, buildColumnsJson(meta.gridFields), totalCount, limit, offset);
  }

  /**
   * Build the filtered FROM clause by appending WHERE/AND conditions for
   * validation filter, org filter, search filter, and OBUISEL where clause.
   */
  private static String buildFilteredFromClause(String fromOnwards, SelectorMeta meta,
      String validationFilter, String search, String alias) {
    StringBuilder baseHql = new StringBuilder(fromOnwards);
    boolean hasWhere = containsWhereClause(fromOnwards);

    // Apply where clause from OBUISEL_Selector config with @param@ substitution
    if (StringUtils.isNotBlank(meta.whereClause)) {
      String resolved = resolveObuiselParams(meta.whereClause);
      hasWhere = appendCondition(baseHql, hasWhere, resolved);
    }

    // Apply validation rule filter (resolved from context params)
    if (StringUtils.isNotBlank(validationFilter)) {
      hasWhere = appendCondition(baseHql, hasWhere, validationFilter);
    }

    // Apply readable organizations filter
    OBContext ctx = OBContext.getOBContext();
    String[] readableOrgs = ctx.getReadableOrganizations();
    if (readableOrgs != null && readableOrgs.length > 0) {
      hasWhere = appendCondition(baseHql, hasWhere,
          buildOrganizationFilter(alias, readableOrgs));
    }

    // Search filter across searchable properties
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();
    if (hasSearch) {
      appendCondition(baseHql, hasWhere,
          buildSearchFilter(alias, meta.searchableProperties));
    }

    return baseHql.toString();
  }

  /**
   * Parse SELECT column expressions into a map of lowercase alias to column index.
   * Handles "expr as alias" and bare "expr.property" forms.
   */
  private static Map<String, Integer> parseSelectAliases(String[] selectExprs) {
    Map<String, Integer> colIndexMap = new HashMap<>();
    for (int i = 0; i < selectExprs.length; i++) {
      colIndexMap.put(extractSelectAlias(selectExprs[i]).toLowerCase(), i);
    }
    return colIndexMap;
  }

  /**
   * Resolve the column index that holds the entity ID in the SELECT expression list.
   * Tries: valueProp → "id" → {alias}.id prefix scan.
   */
  private static Integer resolveIdColumnIndex(Map<String, Integer> colIndexMap,
      String valueProperty, String alias, String[] selectExprs) {
    String valuePropLower = (valueProperty != null ? valueProperty : "id").toLowerCase();
    Integer idColIdx = colIndexMap.get(valuePropLower);
    if (idColIdx == null) {
      idColIdx = colIndexMap.get("id");
    }
    if (idColIdx == null) {
      String idPrefix = alias.toLowerCase() + ".id";
      for (int i = 0; i < selectExprs.length; i++) {
        if (isEntityIdSelectExpression(selectExprs[i], idPrefix)) {
          idColIdx = i;
          break;
        }
      }
    }
    return idColIdx;
  }

  /**
   * Build the items JSONArray from raw query result rows, extracting IDs, labels,
   * and grid field values. Populates entityIds list as a side effect.
   */
  private static JSONArray buildItemsFromRows(List<?> rows, Map<String, Integer> colIndexMap,
      Integer idColIdx, SelectorMeta meta, Entity entityDef,
      List<String> entityIds) throws Exception {
    JSONArray items = new JSONArray();
    for (Object rawRow : rows) {
      items.put(buildItemFromRow(rawRow, colIndexMap, idColIdx, meta, entityDef, entityIds));
    }
    return items;
  }

  private static JSONObject buildItemFromRow(Object rawRow, Map<String, Integer> colIndexMap,
      Integer idColIdx, SelectorMeta meta, Entity entityDef, List<String> entityIds) throws Exception {
    Object[] row = normalizeRow(rawRow);
    String recordId = extractRecordId(row, idColIdx);
    JSONObject item = new JSONObject();
    item.put(JSON_ID, recordId);
    item.put(JSON_LABEL, extractDisplayLabel(row, colIndexMap, meta, entityDef, recordId));
    entityIds.add(recordId);
    appendGridFieldValues(item, row, colIndexMap, meta.gridFields);
    return item;
  }

  private static Object[] normalizeRow(Object rawRow) {
    return rawRow instanceof Object[] ? (Object[]) rawRow : new Object[]{ rawRow };
  }

  private static void appendGridFieldValues(JSONObject item, Object[] row,
      Map<String, Integer> colIndexMap, List<RichFieldMeta> gridFields) throws Exception {
    for (RichFieldMeta fieldMeta : gridFields) {
      appendGridFieldValue(item, row, colIndexMap, fieldMeta);
    }
  }

  private static void appendGridFieldValue(JSONObject item, Object[] row,
      Map<String, Integer> colIndexMap, RichFieldMeta fieldMeta) throws Exception {
    Integer columnIndex = colIndexMap.get(fieldMeta.propertyKey.toLowerCase());
    if (columnIndex == null || columnIndex >= row.length) {
      return;
    }
    item.put(fieldMeta.propertyKey, toJsonValue(row[columnIndex]));
  }

  private static Object toJsonValue(Object value) {
    if (value instanceof BaseOBObject) {
      return ((BaseOBObject) value).getIdentifier();
    }
    return value != null ? value : JSONObject.NULL;
  }

  /**
   * Extract the record ID from a query result row, handling BaseOBObject and composite IDs.
   */
  private static String extractRecordId(Object[] row, Integer idColIdx) {
    Object idVal;
    if (idColIdx != null && idColIdx < row.length) {
      idVal = row[idColIdx];
    } else {
      idVal = row[0];
    }
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
   * Extract the display label for a row from the column index map or by loading the entity.
   */
  private static String extractDisplayLabel(Object[] row, Map<String, Integer> colIndexMap,
      SelectorMeta meta, Entity entityDef, String recordId) {
    String label = "";
    Integer displayIdx = colIndexMap.get(meta.displayProperty.toLowerCase());
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
        if (entity != null) {
          label = entity.getIdentifier();
        }
      } catch (Exception e) {
        log.debug("Could not load entity identifier for record {}: {}",
            recordId, e.getMessage());
      }
    }
    return label;
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
      AuxQueryContext context = buildAuxQueryContext(rawHql, fromIdx, entityAlias, meta);
      if (context == null) {
        return;
      }
      Query<Object[]> auxQuery = OBDal.getInstance()
          .getSession().createQuery(context.hql, Object[].class);
      auxQuery.setParameterList(AUX_IDS_PARAM, entityIds);
      mergeAuxMapIntoItems(items,
          buildAuxMapFromRows(auxQuery.list(), context.idPosition, context.auxAliasPositions));
    } catch (Exception e) {
      log.warn("Could not resolve aux fields via HQL: {}", e.getMessage());
    }
  }

  private static CustomHqlContext prepareCustomHqlContext(SelectorMeta meta,
      String search, String validationFilter) {
    String rawHql = meta.customHql.replace("@additional_filters@", "1=1");
    int fromIdx = findFromClauseIndex(rawHql);
    String selectPart = rawHql.substring(0, fromIdx).trim();
    String[] selectExprs = extractSelectExpressions(selectPart);
    String fromClause = buildFilteredFromClause(rawHql.substring(fromIdx), meta,
        validationFilter, search, meta.entityAlias);

    CustomHqlContext context = new CustomHqlContext();
    context.alias = meta.entityAlias;
    context.rawHql = rawHql;
    context.fromIdx = fromIdx;
    context.selectPart = selectPart;
    context.selectExprs = selectExprs;
    context.colIndexMap = parseSelectAliases(selectExprs);
    context.hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();
    context.fromClause = fromClause;
    return context;
  }

  private static int executeCustomCountQuery(CustomHqlContext context, String search) {
    Query<Long> countQuery = OBDal.getInstance().getSession().createQuery(
        "SELECT COUNT(" + context.alias + ")" + context.fromClause, Long.class);
    bindSearchParameter(countQuery, context.hasSearch, search);
    return countQuery.uniqueResult().intValue();
  }

  private static Query<?> buildCustomDataQuery(CustomHqlContext context, SelectorMeta meta,
      String search, int limit, int offset) {
    Query<?> dataQuery = OBDal.getInstance().getSession().createQuery(
        context.selectPart + context.fromClause + " ORDER BY " + context.alias + "."
            + meta.displayProperty);
    bindSearchParameter(dataQuery, context.hasSearch, search);
    dataQuery.setMaxResults(limit);
    dataQuery.setFirstResult(offset);
    return dataQuery;
  }

  private static JSONArray buildColumnsJson(List<RichFieldMeta> gridFields) throws Exception {
    JSONArray columns = new JSONArray();
    for (RichFieldMeta fieldMeta : gridFields) {
      JSONObject col = new JSONObject();
      col.put(JSON_NAME, fieldMeta.propertyKey);
      col.put(JSON_LABEL, fieldMeta.label);
      col.put(JSON_SORT_NO, fieldMeta.sortNo);
      columns.put(col);
    }
    return columns;
  }

  private static NeoResponse buildSelectorResponse(JSONArray items, JSONArray columns,
      int totalCount, int limit, int offset) throws Exception {
    JSONObject result = new JSONObject();
    result.put(JSON_ITEMS, items);
    result.put(JSON_COLUMNS, columns);
    result.put(JSON_TOTAL_COUNT, totalCount);
    result.put(JSON_LIMIT, limit);
    result.put(JSON_OFFSET, offset);
    result.put(JSON_HAS_MORE, (offset + limit) < totalCount);
    return NeoResponse.ok(result);
  }

  private static boolean hasHqlOnlyAuxFields(SelectorMeta meta) {
    return meta.auxFields.stream()
        .anyMatch(af -> StringUtils.isBlank(af.property) && StringUtils.isNotBlank(af.hqlAlias));
  }

  private static int findFromClauseIndex(String rawHql) {
    Matcher fromMatcher = FROM_CLAUSE_PATTERN.matcher(rawHql);
    if (!fromMatcher.find()) {
      throw new IllegalArgumentException("Custom HQL does not contain a FROM clause: " + rawHql);
    }
    return fromMatcher.start();
  }

  private static void bindSearchParameter(Query<?> query, boolean hasSearch, String search) {
    if (hasSearch) {
      query.setParameter(SEARCH_PARAM, "%" + search.toLowerCase() + "%");
    }
  }

  private static boolean appendCondition(StringBuilder hql, boolean hasWhere, String condition) {
    hql.append(hasWhere ? SQL_AND : SQL_WHERE).append(condition);
    return true;
  }

  private static boolean containsWhereClause(String hql) {
    return WHERE_CLAUSE_PATTERN.matcher(hql).find();
  }

  private static String buildOrganizationFilter(String alias, String[] readableOrgs) {
    StringBuilder filter = new StringBuilder(alias).append(".organization.id IN (");
    for (int i = 0; i < readableOrgs.length; i++) {
      if (i > 0) {
        filter.append(", ");
      }
      filter.append("'").append(readableOrgs[i]).append("'");
    }
    return filter.append(")").toString();
  }

  private static String buildSearchFilter(String alias, List<String> searchableProperties) {
    StringBuilder filter = new StringBuilder("(");
    for (int i = 0; i < searchableProperties.size(); i++) {
      if (i > 0) {
        filter.append(" OR ");
      }
      filter.append("lower(COALESCE(cast(").append(alias).append(".")
          .append(searchableProperties.get(i)).append(" as string), '')) LIKE :")
          .append(SEARCH_PARAM);
    }
    return filter.append(")").toString();
  }

  private static AuxQueryContext buildAuxQueryContext(String rawHql, int fromIdx,
      String entityAlias, SelectorMeta meta) {
    String selectClause = rawHql.substring(0, fromIdx);
    List<String> aliases = extractSelectAliases(extractSelectExpressions(selectClause));
    if (aliases.isEmpty()) {
      log.debug("Could not parse SELECT aliases from custom HQL");
      return null;
    }

    int idPosition = resolveAuxIdPosition(aliases, meta);
    if (idPosition < 0) {
      log.debug("Could not find ID column in custom HQL SELECT aliases: {}", aliases);
      return null;
    }

    Map<String, Integer> auxAliasPositions = resolveAuxAliasPositions(aliases, meta.auxFields);
    if (auxAliasPositions.isEmpty()) {
      return null;
    }

    String auxHql = buildAuxHql(selectClause, rawHql.substring(fromIdx), entityAlias);
    return new AuxQueryContext(auxHql, idPosition, auxAliasPositions);
  }

  private static List<String> extractSelectAliases(String[] selectExprs) {
    List<String> aliases = new ArrayList<>();
    for (String selectExpr : selectExprs) {
      aliases.add(extractSelectAlias(selectExpr).toLowerCase());
    }
    return aliases;
  }

  private static String[] extractSelectExpressions(String selectClause) {
    List<String> expressions = splitTopLevelComma(stripSelectKeyword(selectClause));
    return expressions.toArray(new String[0]);
  }

  private static String stripSelectKeyword(String selectClause) {
    return selectClause.trim().replaceFirst("(?i)^select\\s+", "");
  }

  private static List<String> splitTopLevelComma(String code) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;

    for (int i = 0; i < code.length(); i++) {
      char ch = code.charAt(i);
      if (ch == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
      } else if (ch == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
      } else if (!inSingleQuote && !inDoubleQuote) {
        if (ch == '(') {
          depth++;
        } else if (ch == ')' && depth > 0) {
          depth--;
        } else if (ch == ',' && depth == 0) {
          addSelectExpression(parts, current);
          continue;
        }
      }
      current.append(ch);
    }

    addSelectExpression(parts, current);
    return parts;
  }

  private static void addSelectExpression(List<String> parts, StringBuilder current) {
    String expression = current.toString().trim();
    if (StringUtils.isNotBlank(expression)) {
      parts.add(expression);
    }
    current.setLength(0);
  }

  private static String extractSelectAlias(String selectExpr) {
    String alias = extractAliasFromAsClause(selectExpr);
    if (alias != null) {
      return alias;
    }
    int dotIdx = selectExpr.lastIndexOf('.');
    return dotIdx >= 0 ? selectExpr.substring(dotIdx + 1).trim() : selectExpr.trim();
  }

  private static String extractAliasFromAsClause(String selectExpr) {
    String lower = selectExpr.toLowerCase();
    int asIndex = lower.lastIndexOf(SQL_AS);
    if (asIndex < 0) {
      return null;
    }
    return selectExpr.substring(asIndex + SQL_AS.length()).trim();
  }

  private static boolean isEntityIdSelectExpression(String selectExpr, String idPrefix) {
    return stripSelectAlias(selectExpr).equalsIgnoreCase(idPrefix);
  }

  private static String stripSelectAlias(String selectExpr) {
    String lower = selectExpr.toLowerCase();
    int asIndex = lower.lastIndexOf(SQL_AS);
    return asIndex >= 0 ? selectExpr.substring(0, asIndex).trim() : selectExpr.trim();
  }

  private static int resolveAuxIdPosition(List<String> aliases, SelectorMeta meta) {
    String valueAlias = meta.valueProperty != null ? meta.valueProperty.toLowerCase() : "id";
    int idPosition = aliases.indexOf(valueAlias);
    if (idPosition >= 0) {
      return idPosition;
    }
    for (int i = 0; i < aliases.size(); i++) {
      String alias = aliases.get(i);
      if (alias.endsWith("id") && alias.length() <= 6) {
        return i;
      }
    }
    return -1;
  }

  private static Map<String, Integer> resolveAuxAliasPositions(List<String> aliases,
      List<AuxFieldMeta> auxFields) {
    Map<String, Integer> auxAliasPositions = new HashMap<>();
    for (AuxFieldMeta auxField : auxFields) {
      if (StringUtils.isNotBlank(auxField.property) || StringUtils.isBlank(auxField.hqlAlias)) {
        continue;
      }
      int position = aliases.indexOf(auxField.hqlAlias.toLowerCase());
      if (position >= 0) {
        auxAliasPositions.put(auxField.suffix, position);
      }
    }
    return auxAliasPositions;
  }

  private static String buildAuxHql(String selectClause, String fromOnwards, String entityAlias) {
    String baseHql = stripTrailingOrderBy(selectClause + fromOnwards);
    String filteredHql = baseHql + (containsWhereClause(baseHql) ? SQL_AND : SQL_WHERE)
        + entityAlias + ".id IN (:" + AUX_IDS_PARAM + ")";
    return resolveObuiselParams(filteredHql);
  }

  private static String stripTrailingOrderBy(String hql) {
    int orderByIdx = hql.toUpperCase().lastIndexOf(SQL_ORDER_BY);
    return orderByIdx > 0 ? hql.substring(0, orderByIdx) : hql;
  }

  private static Map<String, JSONObject> buildAuxMapFromRows(List<Object[]> rows,
      int idPosition, Map<String, Integer> auxAliasPositions) throws Exception {
    Map<String, JSONObject> auxMap = new HashMap<>();
    for (Object[] row : rows) {
      String rowId = extractAuxRowId(row, idPosition);
      if (rowId == null) {
        continue;
      }
      JSONObject aux = buildAuxJson(row, auxAliasPositions);
      if (aux.length() > 0) {
        auxMap.put(rowId, aux);
      }
    }
    return auxMap;
  }

  private static String extractAuxRowId(Object[] row, int idPosition) {
    if (row.length <= idPosition || row[idPosition] == null) {
      return null;
    }
    return row[idPosition].toString();
  }

  private static JSONObject buildAuxJson(Object[] row,
      Map<String, Integer> auxAliasPositions) throws Exception {
    JSONObject aux = new JSONObject();
    for (Map.Entry<String, Integer> entry : auxAliasPositions.entrySet()) {
      int position = entry.getValue();
      if (position < row.length && row[position] != null) {
        aux.put(entry.getKey(), row[position].toString());
      }
    }
    return aux;
  }

  private static void mergeAuxMapIntoItems(JSONArray items,
      Map<String, JSONObject> auxMap) throws Exception {
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      JSONObject aux = auxMap.get(item.optString(JSON_ID));
      if (aux == null) {
        continue;
      }
      mergeAuxJson(item, aux);
    }
  }

  private static void mergeAuxJson(JSONObject item, JSONObject aux) throws Exception {
    JSONObject existing = item.optJSONObject(JSON_AUX);
    if (existing == null) {
      item.put(JSON_AUX, aux);
      return;
    }
    @SuppressWarnings("unchecked")
    java.util.Iterator<String> auxKeysIter = aux.keys();
    while (auxKeysIter.hasNext()) {
      String key = auxKeysIter.next();
      existing.put(key, aux.get(key));
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
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, entityName));
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

  private static boolean isFkReference(String refId) {
    return REF_TABLE.equals(refId) || REF_TABLEDIR.equals(refId)
        || REF_SEARCH.equals(refId);
  }

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

    // Apply column-level validation rule (mirrors Classic UI filtering via AD_Val_Rule).
    // Rules reference the "value" column of AD_REF_LIST (e.g. UPPER(Value)<>'SPANISH').
    // Only apply static rules — skip any rule that contains @variable@ placeholders.
    String valRuleSql = null;
    org.openbravo.model.ad.domain.Validation valRule = column.getValidation();
    if (valRule != null && StringUtils.isNotBlank(valRule.getValidationCode())) {
      String ruleCode = valRule.getValidationCode().trim();
      if (!ruleCode.contains("@")) {
        valRuleSql = ruleCode;
      }
    }

    // Use separate criteria objects for count and data — OBCriteria.count() modifies
    // the criteria projection state, so reusing the same object for list() returns wrong data.
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
      // Use the searchKey (stored DB value) as the id, and name as the label
      item.put("id", listItem.getSearchKey());
      item.put("label", listItem.getName());
      items.put(item);
    }

    JSONObject result = new JSONObject();
    result.put("items", items);
    result.put("columns", new JSONArray());
    result.put("totalCount", totalCount);
    result.put("limit", limit);
    result.put("offset", offset);
    result.put("hasMore", (offset + limit) < totalCount);
    return NeoResponse.ok(result);
  }

  /**
   * Returns a map of searchKey → name for all active entries of a List reference.
   * Used to enrich GET responses with $_{@literal identifier} labels for List reference fields.
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

      // Get where clause; strip SQL-only constructs not valid in HQL
      String whereClause = sanitizeAdWhereClause(selector.getHQLWhereClause());

      // Load selector fields
      List<SelectorField> selectorFields = selector.getOBUISELSelectorFieldList();

      List<RichFieldMeta> gridFields = new ArrayList<>();
      List<String> searchableProps = new ArrayList<>();
      List<AuxFieldMeta> auxFields = new ArrayList<>();

      for (SelectorField sf : selectorFields) {
        if (!Boolean.TRUE.equals(sf.isActive())) {
          continue;
        }
        String prop = sf.getProperty();

        // Auxiliary output fields (isOutField=Y with a suffix like "_LOC")
        if (Boolean.TRUE.equals(sf.isOutfield())
            && StringUtils.isNotBlank(sf.getSuffix())) {
          String alias = sf.getDisplayColumnAlias();
          auxFields.add(new AuxFieldMeta(
              sf.getSuffix(),
              alias != null ? alias.toLowerCase() : null,
              sf.getName(),
              prop));
          // Aux fields may or may not have a property; continue processing
        }

        if (StringUtils.isBlank(prop) || prop.contains("_identifier")) {
          continue;
        }

        // Grid columns
        if (Boolean.TRUE.equals(sf.isShowingrid())) {
          String propKey = getLastSegment(prop);
          Long sortNo = sf.getSortno();
          gridFields.add(new RichFieldMeta(
              propKey, sf.getName(), prop,
              sortNo != null ? sortNo : 0L));
        }

        // Searchable properties
        if (Boolean.TRUE.equals(sf.isSearchinsuggestionbox())) {
          searchableProps.add(prop);
        }
      }

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

      // Get optional where clause; strip SQL-only constructs not valid in HQL
      String whereClause = sanitizeAdWhereClause(refTable.getHqlwhereclause());

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
      "AD_ISORGINCLUDED|AD_ISCHILDORGINCLUDED|AD_ROLE_ORGACCESS", Pattern.CASE_INSENSITIVE);

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
    allParams.put(AD_ORG_ID, ctx.getCurrentOrganization().getId());
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

  /** Package-private for testing. */
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
    return String.join(SQL_AND, parts);
  }

  /**
   * Applies an organization filter to selectors whose target entity is org-aware.
   * When the caller provides {@code AD_Org_ID} in context, that org is used directly.
   * Otherwise, the filter is derived from the parent record's organization (child tabs only,
   * i.e. tabLevel &gt; 0).
   * Returns {@code null} (no filter) if no org context is available or if the target entity
   * does not have an {@code organization} property.
   *
   * <p>This compensates for the fact that {@code AD_ISORGINCLUDED} validation clauses are
   * stripped by {@link #sanitizeAdWhereClause} because they are SQL-only and not valid in HQL.
   */
  private static String resolveOrgFilter(SFEntity sourceEntity,
      Column sourceColumn, SelectorMeta targetMeta, Map<String, String> contextParams) {
    if (sourceEntity == null || sourceColumn == null || targetMeta == null) {
      return null;
    }

    Entity targetEntity = ModelProvider.getInstance().getEntity(targetMeta.entityName);
    if (targetEntity == null || !targetEntity.hasProperty(PROP_ORGANIZATION)) {
      return null;
    }

    String organizationId = null;
    if (contextParams != null) {
      organizationId = contextParams.get(AD_ORG_ID);
      if (StringUtils.isBlank(organizationId)) {
        organizationId = resolveOrgFromParentRecord(sourceEntity, contextParams.get("parentId"));
      }
    }
    if (StringUtils.isBlank(organizationId)) {
      return null;
    }

    String safeOrgId = organizationId.replace("'", "''");
    log.debug("Applying organization filter {} to selector {}", safeOrgId,
        sourceColumn.getDBColumnName());
    return "e.organization.id='" + safeOrgId + "'";
  }

  private static String resolveOrgFromParentRecord(SFEntity sourceEntity, String parentId) {
    if (sourceEntity == null || StringUtils.isBlank(parentId)) {
      return null;
    }

    try {
      Tab childTab = sourceEntity.getADTab();
      if (childTab == null || childTab.getTabLevel() == null || childTab.getTabLevel() <= 0) {
        return null;
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

  /**
   * Split a SQL WHERE clause into top-level AND segments.
   * Respects parentheses nesting so that "A AND (B OR C AND D) AND E"
   * splits into ["A", "(B OR C AND D)", "E"], not into inner parts.
   */
  /**
   * Strip SQL-only constructs from an AD where clause before appending to HQL.
   * Splits on top-level AND, drops any clause that references SQL_ONLY_FUNCTIONS
   * (e.g. AD_ROLE_ORGACCESS, which is not a mapped Hibernate entity).
   * OBDal's own bindings (_dal_readableOrganizations_dal_) already enforce org/client access.
   */
  private static String sanitizeAdWhereClause(String whereClause) {
    if (StringUtils.isBlank(whereClause)) {
      return null;
    }
    List<String> clauses = splitTopLevelAnd(whereClause);
    List<String> kept = new ArrayList<>();
    for (String clause : clauses) {
      String trimmed = clause.trim();
      if (StringUtils.isBlank(trimmed)) {
        // skip blank clauses
      } else if (SQL_ONLY_FUNCTIONS.matcher(trimmed).find()) {
        log.debug("Skipping AD where clause with SQL-only construct: {}", trimmed);
      } else {
        kept.add(trimmed);
      }
    }
    if (kept.isEmpty()) {
      return null;
    }
    return String.join(SQL_AND, kept);
  }

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

  private static class CustomHqlContext {
    String alias;
    String rawHql;
    int fromIdx;
    String selectPart;
    String[] selectExprs;
    Map<String, Integer> colIndexMap;
    boolean hasSearch;
    String fromClause;
  }

  private static class AuxQueryContext {
    final String hql;
    final int idPosition;
    final Map<String, Integer> auxAliasPositions;

    AuxQueryContext(String hql, int idPosition, Map<String, Integer> auxAliasPositions) {
      this.hql = hql;
      this.idPosition = idPosition;
      this.auxAliasPositions = auxAliasPositions;
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
   * Builds an HQL fragment "alias.organization.id IN ('orgId1', 'orgId2', ...)" using the
   * natural org tree of the active org in the current OBContext. This mirrors Classic Etendo
   * lookup behavior — FK selectors show records accessible from the current org upward in the
   * hierarchy, not all organizations accessible to the role.
   *
   * Returns null if: the entity has no "organization" property, the org is not set, or the
   * entity is defined at "*" scope only.
   */
  private static String buildNaturalOrgFilter(String entityName, String alias) {
    try {
      Entity entityDef = ModelProvider.getInstance().getEntity(entityName);
      // Only apply org filter if entity has "organization" property
      entityDef.getProperty("organization");
    } catch (Exception e) {
      return null;
    }
    try {
      OBContext ctx = OBContext.getOBContext();
      if (ctx == null || ctx.getCurrentOrganization() == null) {
        return null;
      }
      String activeOrgId = ctx.getCurrentOrganization().getId();
      OrganizationStructureProvider osp = ctx.getOrganizationStructureProvider();
      java.util.Set<String> naturalTree = osp.getNaturalTree(activeOrgId);
      naturalTree.add("0"); // always include the * org
      if (naturalTree.isEmpty()) {
        return null;
      }
      StringBuilder filter = new StringBuilder(alias).append(".organization.id IN (");
      boolean first = true;
      for (String orgId : naturalTree) {
        if (!first) {
          filter.append(", ");
        }
        filter.append("'").append(orgId).append("'");
        first = false;
      }
      filter.append(")");
      return filter.toString();
    } catch (Exception e) {
      log.warn("Could not build natural org filter for entity {}: {}", entityName, e.getMessage());
      return null;
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
}
