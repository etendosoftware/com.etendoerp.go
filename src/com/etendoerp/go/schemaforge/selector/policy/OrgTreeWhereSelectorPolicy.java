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
package com.etendoerp.go.schemaforge.selector.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-5707: resolves the organization-tree filter of the product-by-price-and-warehouse selector
 * once per request instead of once per row of its view.
 *
 * <p>The dictionary where clause of that selector asks
 * {@code AD_ORG_ISINNATURALTREE(e.orgwarehouse, :org, :client) = 'Y'} and the same function over
 * {@code e.productPrice.organization.id}. The view has one row per product × price × warehouse, so
 * the function runs for every one of them (thousands, and again in each of the count, page and rows
 * queries). Resolving the tree once gives the same rows for a fraction of the cost:
 * <ul>
 *   <li>the warehouse predicate becomes {@code e.warehouse.id in (:warehouseIds)}: a membership on
 *   the warehouse id the planner can push down to the warehouse table. The same predicate written
 *   over {@code orgwarehouse} stays as slow as the function, because that column is an expression
 *   of the left-joined warehouse;</li>
 *   <li>the price-organization predicate becomes {@code e.productPrice.organization.id in
 *   (:orgIds)}.</li>
 * </ul>
 *
 * <p>Both lists are produced by the very same database function, evaluated once per distinct
 * organization/warehouse, with the organization and client the original predicate was bound to, so
 * the selected rows are identical. A predicate whose parameters cannot be read is left untouched.
 */
public final class OrgTreeWhereSelectorPolicy {

  private static final String ENTITY_PRODUCT_BY_PRICE_AND_WAREHOUSE = "ProductByPriceAndWarehouse";
  private static final String PARAM_WAREHOUSE_IDS = "sfOrgTreeWarehouseIds";
  private static final String PARAM_ORG_IDS = "sfOrgTreeOrgIds";
  private static final String NEVER_TRUE = "1 = 0";

  private static final String SQL_WAREHOUSES_IN_TREE =
      "SELECT w.m_warehouse_id FROM m_warehouse w"
          + " WHERE w.ad_client_id IN (:clientId, '0') AND w.isactive = 'Y'"
          + " AND ad_org_isinnaturaltree(COALESCE(w.ad_org_id, '0'), :orgId, :clientId) = 'Y'";
  private static final String SQL_ORGS_IN_TREE =
      "SELECT o.ad_org_id FROM ad_org o"
          + " WHERE o.ad_client_id IN (:clientId, '0')"
          + " AND ad_org_isinnaturaltree(o.ad_org_id, :orgId, :clientId) = 'Y'";

  private static final OrgTreeResolver DATABASE_RESOLVER = new DatabaseOrgTreeResolver();

  private OrgTreeWhereSelectorPolicy() {
  }

  /** Where a tree of organizations is turned into plain id lists. */
  interface OrgTreeResolver {
    /**
     * Ids of the active warehouses whose organization is in the natural tree of {@code orgId}.
     *
     * @param orgId organization whose natural tree is resolved
     * @param clientId client the tree belongs to
     * @return the warehouse ids, empty when none is in the tree
     */
    List<String> warehouseIdsInTree(String orgId, String clientId);

    /**
     * Ids of the organizations in the natural tree of {@code orgId}.
     *
     * @param orgId organization whose natural tree is resolved
     * @param clientId client the tree belongs to
     * @return the organization ids, empty when none is in the tree
     */
    List<String> orgIdsInTree(String orgId, String clientId);
  }

  /** The rewritten where clause and the named parameters it needs. */
  public static final class Rewrite {
    private final String hql;
    private final Map<String, Object> params;

    Rewrite(String hql, Map<String, Object> params) {
      this.hql = hql;
      this.params = params;
    }

    /** The where clause with the organization-tree predicates resolved. */
    public String getHql() {
      return hql;
    }

    /** The named parameters of {@link #getHql()}: the original ones minus any no longer used. */
    public Map<String, Object> getParams() {
      return params;
    }
  }

  /**
   * Rewrite the organization-tree predicates of the resolved dictionary where clause.
   *
   * @param entityName target DAL entity name of the selector
   * @param alias HQL alias of the selector entity
   * @param hql dictionary where clause, with its context placeholders already bound to named
   *     parameters
   * @param params the named parameters of {@code hql}
   * @return the rewritten clause, or {@code null} when this policy does not apply or nothing
   *     matched
   */
  public static Rewrite rewrite(String entityName, String alias, String hql,
      Map<String, Object> params) {
    return rewrite(entityName, alias, hql, params, DATABASE_RESOLVER);
  }

  static Rewrite rewrite(String entityName, String alias, String hql,
      Map<String, Object> params, OrgTreeResolver resolver) {
    if (!ENTITY_PRODUCT_BY_PRICE_AND_WAREHOUSE.equals(entityName) || StringUtils.isBlank(hql)
        || StringUtils.isBlank(alias) || params == null) {
      return null;
    }
    Map<String, Object> rewrittenParams = new HashMap<>(params);
    String result = rewritePredicate(hql, treeFunctionOver(alias + ".orgwarehouse"),
        alias + ".warehouse.id", PARAM_WAREHOUSE_IDS, params, rewrittenParams,
        id -> resolver.warehouseIdsInTree(id[0], id[1]));
    result = rewritePredicate(result, treeFunctionOver(alias + ".productPrice.organization.id"),
        alias + ".productPrice.organization.id", PARAM_ORG_IDS, params, rewrittenParams,
        id -> resolver.orgIdsInTree(id[0], id[1]));
    if (result.equals(hql)) {
      return null;
    }
    final String rewrittenHql = result;
    rewrittenParams.keySet().removeIf(name -> params.containsKey(name)
        && !usesParameter(rewrittenHql, name));
    return new Rewrite(rewrittenHql, rewrittenParams);
  }

  private static Pattern treeFunctionOver(String propertyPath) {
    return Pattern.compile("AD_ORG_ISINNATURALTREE\\(\\s*" + Pattern.quote(propertyPath)
        + "\\s*,\\s*:(\\w+)\\s*,\\s*:(\\w+)\\s*\\)\\s*=\\s*'Y'", Pattern.CASE_INSENSITIVE);
  }

  /**
   * Replace each occurrence of {@code function} by a membership in the ids it resolves to. The
   * occurrence is left as it was when either of its parameters is not a string bound in
   * {@code params}.
   */
  private static String rewritePredicate(String hql, Pattern function, String memberProperty,
      String paramPrefix, Map<String, Object> params, Map<String, Object> rewrittenParams,
      Function<String[], List<String>> idsInTree) {
    Matcher matcher = function.matcher(hql);
    StringBuilder out = new StringBuilder();
    int last = 0;
    int occurrence = 0;
    while (matcher.find()) {
      Object org = params.get(matcher.group(1));
      Object client = params.get(matcher.group(2));
      if (!(org instanceof String) || !(client instanceof String)) {
        continue;
      }
      out.append(hql, last, matcher.start());
      List<String> ids = idsInTree.apply(new String[] { (String) org, (String) client });
      if (ids.isEmpty()) {
        out.append(NEVER_TRUE);
      } else {
        String paramName = paramPrefix + occurrence++;
        rewrittenParams.put(paramName, ids);
        out.append(memberProperty).append(" in (:").append(paramName).append(')');
      }
      last = matcher.end();
    }
    out.append(hql, last, hql.length());
    return out.toString();
  }

  private static boolean usesParameter(String hql, String name) {
    return Pattern.compile(":" + Pattern.quote(name) + "(?!\\w)").matcher(hql).find();
  }

  /** The tree as the database sees it: the same function the dictionary clause calls. */
  private static final class DatabaseOrgTreeResolver implements OrgTreeResolver {

    @Override
    public List<String> warehouseIdsInTree(String orgId, String clientId) {
      return query(SQL_WAREHOUSES_IN_TREE, orgId, clientId);
    }

    @Override
    public List<String> orgIdsInTree(String orgId, String clientId) {
      return query(SQL_ORGS_IN_TREE, orgId, clientId);
    }

    private static List<String> query(String sql, String orgId, String clientId) {
      @SuppressWarnings("rawtypes")
      NativeQuery nq = OBDal.getInstance().getSession().createNativeQuery(sql);
      nq.setParameter("orgId", orgId);
      nq.setParameter("clientId", clientId);
      List<String> ids = new ArrayList<>();
      for (Object row : nq.list()) {
        ids.add(String.valueOf(row));
      }
      return Collections.unmodifiableList(ids);
    }
  }
}
