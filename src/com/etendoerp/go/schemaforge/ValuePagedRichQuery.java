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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * ETP-5707: pages a view-backed rich selector BY VALUE, as ETP-5703 does for custom HQL.
 *
 * <p>The row-per-value collapse used to be {@code e.id in (select min(e.id) … group by value)}: the
 * whole filter twice, and a plan that re-evaluated the grouped subquery once per outer row (40 s for
 * 1000 products × 8 warehouses). Here the filter runs once per query and the three steps share one
 * unit, the value: (1) {@code count(distinct value)} is the {@code totalCount}; (2) one page of
 * distinct values, {@code group by value order by min(display), value}, with offset/limit; (3) the
 * rows of those values, of which the one with the lowest id is kept, the row the old {@code min(id)}
 * chose, so the {@code _aux} values do not change.
 *
 * <p>The filtered HQL comes from {@link OBQuery} (readable client/organization/active filters
 * already inlined) and is extended on the session, because an {@code OBQuery} where clause cannot
 * carry a {@code group by}. Rows whose value is null are left out of every step: they cannot be
 * selected, and {@code count(distinct)} would otherwise disagree with the grouped page.
 */
final class ValuePagedRichQuery {

  private static final Logger log = LogManager.getLogger(ValuePagedRichQuery.class);
  private static final String PARAM_PAGE_VALUES = "sfPageValues";

  private ValuePagedRichQuery() {
  }

  /** A filtered HQL ready to be extended, with every named parameter it needs. */
  private static final class RichHql {
    final String hql;
    final Map<String, Object> params;

    RichHql(String hql, Map<String, Object> params) {
      this.hql = hql;
      this.params = params;
    }
  }

  static NeoResponse execute(SelectorMeta meta, String alias,
      SelectorQueryBuilder.HqlWithParams whereClause, String search, int limit, int offset,
      Map<String, Object> extraFilterParams) throws Exception {
    String valueExpr = alias + "." + meta.valueProperty;
    Map<String, Object> params = collectParams(meta, whereClause, search, extraFilterParams);
    String where = andCondition(whereClause.getHql(), valueExpr + " is not null");

    int totalCount = countDistinctValues(meta, where, valueExpr, params);
    List<String> pageValues = totalCount > 0 && offset < totalCount
        ? pageOfValues(meta, alias, where, valueExpr, params, limit, offset)
        : Collections.<String>emptyList();
    JSONArray items = pageValues.isEmpty() ? new JSONArray()
        : SelectorQueryExecutor.buildRichItems(
            firstRowOfEachValue(meta, alias, where, valueExpr, params, pageValues), meta);
    return SelectorResponseSupport.buildSelectorResponse(items,
        SelectorResponseSupport.buildGridColumnMetadata(meta.gridFields), totalCount, limit, offset);
  }

  /** The where's own parameters, the caller's extra ones and the search pattern when there is a search. */
  private static Map<String, Object> collectParams(SelectorMeta meta,
      SelectorQueryBuilder.HqlWithParams whereClause, String search,
      Map<String, Object> extraFilterParams) {
    Map<String, Object> params = new HashMap<>(whereClause.getParams());
    if (extraFilterParams != null) {
      params.putAll(extraFilterParams);
    }
    if (StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty()) {
      params.put(SelectorQueryExecutor.PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    return params;
  }

  private static int countDistinctValues(SelectorMeta meta, String where, String valueExpr,
      Map<String, Object> params) {
    RichHql countHql = buildRichHql(meta, where, "count(distinct " + valueExpr + ")", params);
    org.hibernate.query.Query<Long> countQuery = OBDal.getInstance().getSession()
        .createQuery(countHql.hql, Long.class);
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, countHql.params);
    Long count = countQuery.uniqueResult();
    return count != null ? count.intValue() : 0;
  }

  /**
   * One page of distinct values, ordered by the first display value of each and then the value, so
   * the order is total and stable across pages. {@code min()} is only valid on a primitive property;
   * an association display (the price view's identifier is the product itself) orders by the value
   * alone, as it did.
   */
  private static List<String> pageOfValues(SelectorMeta meta, String alias, String where,
      String valueExpr, Map<String, Object> params, int limit, int offset) {
    RichHql pageHql = buildRichHql(meta, where, valueExpr, params);
    String order = displayIsPrimitivePath(meta)
        ? "min(" + alias + "." + meta.displayProperty + "), " + valueExpr
        : valueExpr;
    org.hibernate.query.Query<?> pageQuery = OBDal.getInstance().getSession()
        .createQuery(pageHql.hql + " group by " + valueExpr + SelectorQueryExecutor.ORDER_BY + order);
    NeoSelectorExecutionHelper.bindNamedParameters(pageQuery, pageHql.params);
    pageQuery.setMaxResults(limit);
    pageQuery.setFirstResult(offset);
    List<String> values = new ArrayList<>();
    for (Object value : pageQuery.list()) {
      values.add(SelectorQueryExecutor.idValueAsString(value));
    }
    return values;
  }

  /** The first (lowest id) row of each of the page's values, in the order of {@code pageValues}. */
  private static List<BaseOBObject> firstRowOfEachValue(SelectorMeta meta, String alias,
      String where, String valueExpr, Map<String, Object> params, List<String> pageValues) {
    RichHql rowsHql = buildRichHql(meta, where, null, params);
    org.hibernate.query.Query<?> rowsQuery = OBDal.getInstance().getSession()
        .createQuery(rowsHql.hql + SelectorQueryBuilder.SQL_AND + valueExpr + " in (:"
            + PARAM_PAGE_VALUES + ")" + SelectorQueryExecutor.ORDER_BY + valueExpr + ", " + alias
            + ".id");
    NeoSelectorExecutionHelper.bindNamedParameters(rowsQuery, rowsHql.params);
    rowsQuery.setParameterList(PARAM_PAGE_VALUES, pageValues);
    Map<String, BaseOBObject> firstRowOfValue = new LinkedHashMap<>();
    for (Object row : rowsQuery.list()) {
      BaseOBObject bob = (BaseOBObject) row;
      firstRowOfValue.putIfAbsent(SelectorQueryExecutor.resolveRichItemId(bob, meta), bob);
    }
    List<BaseOBObject> pageRows = new ArrayList<>();
    for (String value : pageValues) {
      BaseOBObject bob = firstRowOfValue.get(value);
      if (bob != null) {
        pageRows.add(bob);
      }
    }
    return pageRows;
  }

  /**
   * The filtered HQL of {@code where} with an optional select clause, plus every named parameter it
   * uses (the caller's and the readable-client/organization ones {@link OBQuery} adds).
   */
  private static RichHql buildRichHql(SelectorMeta meta, String where, String selectClause,
      Map<String, Object> params) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(meta.entityName, where);
    NeoSelectorExecutionHelper.bindNamedParameters(query, params);
    if (selectClause != null) {
      query.setSelectClause(selectClause);
    }
    String hql = query.createQuery(Object.class).getQueryString();
    return new RichHql(hql, new HashMap<>(query.getNamedParameters()));
  }

  /**
   * {@code baseWhere} ({@code "as e"} or {@code "as e where <conditions>"}) with one more AND-ed
   * condition; the existing conditions are parenthesised so an {@code or} inside them cannot
   * swallow it.
   */
  static String andCondition(String baseWhere, String condition) {
    final String whereKeyword = " where ";
    int whereIdx = StringUtils.indexOfIgnoreCase(baseWhere, whereKeyword);
    if (whereIdx < 0) {
      return baseWhere + whereKeyword + condition;
    }
    return baseWhere.substring(0, whereIdx + whereKeyword.length()) + "("
        + baseWhere.substring(whereIdx + whereKeyword.length()) + ")" + SelectorQueryBuilder.SQL_AND
        + condition;
  }

  /** Whether the display property, possibly a dotted path, ends in a primitive property. */
  static boolean displayIsPrimitivePath(SelectorMeta meta) {
    if (meta == null || StringUtils.isBlank(meta.displayProperty)) {
      return false;
    }
    try {
      Entity entity = ModelProvider.getInstance().getEntity(meta.entityName);
      Property property = entity != null
          ? DalUtil.getPropertyFromPath(entity, meta.displayProperty) : null;
      return property != null && property.isPrimitive();
    } catch (RuntimeException e) {
      log.debug("[ETP-5707] Display property {} of {} not resolvable; ordering by value: {}",
          meta.displayProperty, meta.entityName, e.getMessage());
      return false;
    }
  }
}
