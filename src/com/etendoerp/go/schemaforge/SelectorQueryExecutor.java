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
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

/**
 * Executes resolved selector query plans against DAL/HQL and maps rows into selector responses.
 */
final class SelectorQueryExecutor {

  private static final Logger log = LogManager.getLogger(SelectorQueryExecutor.class);
  private static final String PARAM_SEARCH = "_selectorSearch";
  private static final String FIELD_LABEL = "label";

  private SelectorQueryExecutor() {
  }

  static NeoResponse execute(SelectorMeta meta, String search, int limit, int offset,
      String validationFilter, String contextOrganizationId) throws Exception {
    if (meta.isRich) {
      return executeRichQuery(meta, search, limit, offset, validationFilter, contextOrganizationId);
    }
    return executeQuery(meta, search, limit, offset, validationFilter, contextOrganizationId);
  }

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

    String whereStr = NeoSelectorExecutionHelper.buildSimpleWhereClause(hql);

    OBQuery<BaseOBObject> countQuery = OBDal.getInstance().createQuery(meta.entityName, whereStr);
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, queryParams);
    if (StringUtils.isNotBlank(search)) {
      countQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    String dataWhere = whereStr + " ORDER BY e." + meta.displayProperty;
    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance().createQuery(meta.entityName, dataWhere);
    NeoSelectorExecutionHelper.bindNamedParameters(dataQuery, queryParams);
    if (StringUtils.isNotBlank(search)) {
      dataQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    JSONArray items = new JSONArray();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      item.put("id", SelectorQueryBuilder.normalizeEntityId(bob.getId().toString()));
      if (meta.displayProperty != null && meta.displayProperty.contains(".")) {
        Object labelValue = resolvePropertyValue(bob, meta.displayProperty);
        item.put(FIELD_LABEL, labelValue != null ? labelValue : bob.getIdentifier());
      } else {
        item.put(FIELD_LABEL, bob.getIdentifier());
      }
      items.put(item);
    }

    return SelectorQueryBuilder.buildSelectorResponse(items, new JSONArray(), totalCount, limit, offset);
  }

  private static NeoResponse executeRichQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter,
      String contextOrganizationId) throws Exception {

    if (meta.isCustomQuery && StringUtils.isNotBlank(meta.customHql)) {
      return executeCustomHqlQuery(meta, search, limit, offset, validationFilter, contextOrganizationId);
    }

    String alias = "e";
    SelectorQueryBuilder.HqlWithParams whereClause = SelectorQueryBuilder.buildRichQueryWhereClause(
        meta, search, validationFilter, alias, contextOrganizationId);
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();

    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, whereClause.getHql());
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, whereClause.getParams());
    if (hasSearch) {
      countQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    String dataWhere = whereClause.getHql() + " ORDER BY " + alias + "." + meta.displayProperty;
    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance().createQuery(meta.entityName, dataWhere);
    NeoSelectorExecutionHelper.bindNamedParameters(dataQuery, whereClause.getParams());
    if (hasSearch) {
      dataQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    JSONArray columns = SelectorQueryBuilder.buildGridColumnMetadata(meta.gridFields);
    JSONArray items = new JSONArray();
    List<String> entityIds = new ArrayList<>();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      String itemId = resolveRichItemId(bob, meta);
      item.put("id", itemId);
      item.put(FIELD_LABEL, bob.getIdentifier());
      entityIds.add(itemId);
      entityIds.add(bob.getId().toString());

      for (RichFieldMeta fieldMeta : meta.gridFields) {
        Object value = resolvePropertyValue(bob, fieldMeta.property);
        item.put(fieldMeta.propertyKey, value != null ? value : JSONObject.NULL);
      }
      SelectorAuxResolver.appendAuxFields(item, bob, meta.auxFields);
      items.put(item);
    }

    return SelectorQueryBuilder.buildSelectorResponse(items, columns, totalCount, limit, offset);
  }

  @SuppressWarnings("unchecked")
  private static NeoResponse executeCustomHqlQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter,
      String contextOrganizationId) throws Exception {

    String alias = meta.entityAlias;
    String rawHql = meta.customHql.replace("@additional_filters@", "1=1");
    java.util.regex.Matcher fromMatcher = Pattern.compile("\\sFROM\\s",
        Pattern.CASE_INSENSITIVE).matcher(rawHql);
    if (!fromMatcher.find()) {
      throw new IllegalArgumentException("Custom HQL does not contain a FROM clause: " + rawHql);
    }
    int fromIdx = fromMatcher.start();
    String fromOnwards = rawHql.substring(fromIdx);

    SelectorQueryBuilder.HqlWithParams fromClause = SelectorQueryBuilder.buildCustomHqlFromClause(
        fromOnwards, alias, meta, validationFilter, search, contextOrganizationId);
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();

    String selectPart = rawHql.substring(0, fromIdx).trim();
    String[] selectExprs = selectPart.replaceFirst("(?i)^select\\s+", "").split(",");
    Map<String, Integer> colIndexMap = SelectorQueryBuilder.buildSelectColumnIndexMap(selectExprs);

    String countHql = "SELECT COUNT(" + alias + ")" + fromClause.getHql();
    org.hibernate.query.Query<Long> countQuery = OBDal.getInstance()
        .getSession().createQuery(countHql, Long.class);
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, fromClause.getParams());
    if (hasSearch) {
      countQuery.setParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    Long countResult = countQuery.uniqueResult();
    int totalCount = (countResult != null) ? countResult.intValue() : 0;

    String dataHql = selectPart + fromClause.getHql() + " ORDER BY " + alias + "."
        + meta.displayProperty;
    org.hibernate.query.Query<?> dataQuery = OBDal.getInstance().getSession().createQuery(dataHql);
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
          SelectorQueryBuilder.extractDisplayLabel(row, colIndexMap, meta.displayProperty,
              entityDef, recordId));
      SelectorQueryBuilder.mapGridFieldsToItem(item, row, colIndexMap, meta.gridFields);
      items.put(item);
    }

    boolean hasHqlOnlyAux = meta.auxFields.stream()
        .anyMatch(af -> StringUtils.isBlank(af.property) && StringUtils.isNotBlank(af.hqlAlias));
    if (hasHqlOnlyAux && !entityIds.isEmpty()) {
      SelectorAuxResolver.resolveAuxFieldsViaHql(items, entityIds, rawHql, fromIdx, alias, meta);
    }

    return SelectorQueryBuilder.buildSelectorResponse(items, columns, totalCount, limit, offset);
  }

  private static String resolveRichItemId(BaseOBObject bob, SelectorMeta meta) {
    if (meta.valueProperty != null && !"id".equals(meta.valueProperty)) {
      Object val = resolvePropertyValue(bob, meta.valueProperty);
      if (val != null) {
        return val.toString();
      }
    }
    return SelectorQueryBuilder.normalizeEntityId(bob.getId().toString());
  }

  private static Object resolvePropertyValue(BaseOBObject bob, String propertyPath) {
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
}
