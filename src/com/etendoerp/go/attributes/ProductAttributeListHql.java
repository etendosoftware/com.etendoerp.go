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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.attributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * Builds the HQL of the attribute-aware Product list (ETP-5977, H9) from a parsed
 * {@link ProductAttributeListRequest}. Pure: no session, no request. Every request value is a bind parameter; the only
 * text spliced into the HQL is the server-built read predicate passed in by the caller.
 */
public final class ProductAttributeListHql {

  private final String idsHql;
  private final String countHql;
  private final Map<String, Object> idsBinds;
  private final Map<String, Object> countBinds;

  private ProductAttributeListHql(String idsHql, String countHql, Map<String, Object> idsBinds,
      Map<String, Object> countBinds) {
    this.idsHql = idsHql;
    this.countHql = countHql;
    this.idsBinds = idsBinds;
    this.countBinds = countBinds;
  }

  /**
   * @param request       the parsed request
   * @param readPredicate the entity's own read predicate over alias {@code e} (server built), or blank
   * @param clients       the readable client ids of the session
   * @param orgs          the readable organization ids of the session
   */
  public static ProductAttributeListHql build(ProductAttributeListRequest request, String readPredicate,
      Collection<String> clients, Collection<String> orgs) {
    Map<String, Object> binds = new HashMap<>();
    List<String> where = new ArrayList<>();
    where.add("e.client.id in (:clients)");
    binds.put("clients", clients);
    where.add("e.organization.id in (:orgs)");
    binds.put("orgs", orgs);
    if (StringUtils.isNotBlank(readPredicate)) {
      where.add("(" + readPredicate + ")");
    }
    if (request.getFilterDefinition() != null) {
      where.add(filter(request, binds));
    }
    String whereClause = String.join(" and ", where);
    Map<String, Object> countBinds = new HashMap<>(binds);

    String join = "";
    String order = " order by e.id";
    if (request.getSortDefinition() != null) {
      join = " left join e.eTGOProductAttrValList v with v.attributeDefinition.id = :sortDef";
      binds.put("sortDef", request.getSortDefinition().getId());
      String column = request.getSortDefinition().isNumber() ? "v.numberValue" : "v.textValue";
      order = " order by " + column + (request.isDescending() ? " desc" : " asc") + " nulls last, e.id";
    }
    return new ProductAttributeListHql("select e.id from Product e" + join + " where " + whereClause + order,
        "select count(e.id) from Product e where " + whereClause, binds, countBinds);
  }

  private static String filter(ProductAttributeListRequest r, Map<String, Object> binds) {
    binds.put("fd", r.getFilterDefinition().getId());
    List<String> cond = new ArrayList<>();
    if (r.getMin() != null) {
      cond.add("f.numberValue >= :fmin");
      binds.put("fmin", r.getMin());
    }
    if (r.getMax() != null) {
      cond.add("f.numberValue <= :fmax");
      binds.put("fmax", r.getMax());
    }
    if (r.getPrefix() != null) {
      cond.add("f.textValue like :fprefix escape '!'");
      binds.put("fprefix", escapeLike(r.getPrefix()) + "%");
    }
    if (r.getEquals() != null) {
      boolean number = r.getFilterDefinition().isNumber();
      cond.add((number ? "f.numberValue" : "f.textValue") + " = :feq");
      binds.put("feq", number ? new BigDecimal(r.getEquals().trim()) : r.getEquals());
    }
    return "exists (select 1 from ETGO_ProductAttrVal f where f.product = e and f.attributeDefinition.id = :fd and "
        + String.join(" and ", cond) + ")";
  }

  static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  public String getIdsHql() {
    return idsHql;
  }

  public String getCountHql() {
    return countHql;
  }

  public Map<String, Object> getIdsBinds() {
    return idsBinds;
  }

  public Map<String, Object> getCountBinds() {
    return countBinds;
  }
}
