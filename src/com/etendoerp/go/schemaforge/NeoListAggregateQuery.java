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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.query.Query;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.service.db.QueryTimeOutUtil;
import org.openbravo.service.json.DataEntityQueryService;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;

/**
 * Whole-set aggregate over the filters of a list GET (ETP-5778).
 *
 * <p>A list response only carries one page, so a total shown next to it (a sidebar summary) would
 * silently cover the loaded rows only. This helper lets an entity customization run ONE grouped
 * HQL query over the very same filter set the list used: the same tab where clause, read
 * predicate, grid criteria and readable org/client/active flags, with sort and paging removed.
 *
 * <p>Generic by design: it names no entity and no property. The customization supplies the select
 * and group-by clauses, which MUST be server-side constants — never text derived from the request —
 * because they are concatenated into the HQL. The row alias is {@link JsonConstants#MAIN_ALIAS}.
 *
 * <p>Handover: {@code NeoCrudHandler.handleDefault} calls {@link #remember} with the exact params it
 * fed the list query, except for {@code _distinct} (FK dropdown) and {@code _summary} (grid summary)
 * requests, which never need an aggregate; a customization's {@code afterHandle} reads them back with {@link #of} and
 * calls {@link #groupBy}. REST single and batch go through that path; MCP lists do not, so
 * {@link #of} is {@code null} there and nothing is aggregated.
 */
public final class NeoListAggregateQuery {

  /** Context attribute holding the immutable copy of the list fetch parameters. */
  static final String ATTRIBUTE = NeoListAggregateQuery.class.getName() + ".params";

  private static final String QUERY_PROFILE = "grid";

  /** Params that shape the page or the order, never the filtered set. */
  private static final Set<String> NON_FILTER_PARAMS = Set.of(JsonConstants.SORTBY_PARAMETER,
      JsonConstants.SUMMARY_PARAMETER, JsonConstants.DISTINCT_PARAMETER,
      JsonConstants.TARGETRECORDID_PARAMETER);

  private final Map<String, String> params;

  private NeoListAggregateQuery(Map<String, String> params) {
    this.params = params;
  }

  /**
   * Stores an immutable copy of the params a list GET fetched with, for the customization's
   * {@code afterHandle}. A {@code null} context or params is ignored.
   *
   * @param context the request context
   * @param dalParams the DAL params the list query was built from
   */
  public static void remember(NeoContext context, Map<String, String> dalParams) {
    if (context == null || dalParams == null) {
      return;
    }
    Map<String, String> copy = new LinkedHashMap<>(dalParams);
    NON_FILTER_PARAMS.forEach(copy::remove);
    context.setAttribute(ATTRIBUTE, Collections.unmodifiableMap(copy));
  }

  /**
   * Returns the aggregate query for this request's list GET.
   *
   * @param context the request context
   * @return the query, or {@code null} when this request did not go through the default list path
   */
  @SuppressWarnings("unchecked")
  public static NeoListAggregateQuery of(NeoContext context) {
    if (context == null) {
      return null;
    }
    Object stored = context.getAttribute(ATTRIBUTE);
    return stored instanceof Map ? new NeoListAggregateQuery((Map<String, String>) stored) : null;
  }

  /**
   * The remembered params without sort/summary/distinct/target-record. Exposed for tests.
   *
   * @return an unmodifiable view of the filter params
   */
  public Map<String, String> filterParams() {
    return params;
  }

  /**
   * Runs {@code select <selectClause> from <entity> as e <filters> group by <groupByClause>} over
   * the filtered set.
   *
   * @param selectClause server-side constant select expressions, aliased on {@code e}
   * @param groupByClause server-side constant group-by expressions
   * @return one {@code Object[]} per group, in select order
   */
  public List<Object[]> groupBy(String selectClause, String groupByClause) {
    DataEntityQueryService service = new FilterQueryServiceFactory().create(params);
    // Order matters: the where clause is what fills the named parameters.
    String where = service.getWhereClause();
    OBQuery<BaseOBObject> obq = OBDal.getInstance()
        .createQuery(service.getEntityName(), where);
    // Same flags DataEntityQueryService.count() applies, so the aggregate counts what the grid counts.
    obq.setFilterOnReadableClients(service.isFilterOnReadableClients());
    obq.setFilterOnReadableOrganization(service.isFilterOnReadableOrganizations());
    if (service.getQueryBuilder().hasOrganizationParameter()) {
      obq.setFilterOnReadableOrganization(false);
    }
    if (!service.isFilterOnActive()) {
      obq.setFilterOnActive(false);
    }
    obq.setNamedParameters(service.getQueryBuilder().getNamedParameters());
    obq.setSelectClause(selectClause);

    // The first Hibernate Query is intentionally discarded: it only yields the query string and,
    // as a side effect, registers the readable org/client parameters on obq.
    String hql = obq.createQuery(Object[].class).getQueryString() + " group by " + groupByClause;
    Query<Object[]> query = OBDal.getInstance().getSession().createQuery(hql, Object[].class);
    bind(query, obq.getNamedParameters());
    QueryTimeOutUtil.getInstance().setQueryTimeOut(query, QUERY_PROFILE);
    return query.list();
  }

  private static void bind(Query<?> query, Map<String, Object> named) {
    if (named == null) {
      return;
    }
    for (Map.Entry<String, Object> entry : named.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Collection<?>) {
        query.setParameterList(entry.getKey(), (Collection<?>) value);
      } else if (value instanceof String[]) {
        query.setParameterList(entry.getKey(), (String[]) value);
      } else {
        query.setParameter(entry.getKey(), value);
      }
    }
  }

  /**
   * {@link DefaultJsonDataService#createSetQueryService(Map, boolean)} is protected and is the only
   * place that turns grid params (criteria, where clause, org filter) into a query service, so
   * reaching it needs a subclass.
   */
  private static final class FilterQueryServiceFactory extends DefaultJsonDataService {
    DataEntityQueryService create(Map<String, String> params) {
      return createSetQueryService(new java.util.HashMap<>(params), true);
    }
  }
}
