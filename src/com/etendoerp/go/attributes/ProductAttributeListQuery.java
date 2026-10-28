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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.FlushMode;
import org.hibernate.query.Query;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoListPage;
import com.etendoerp.go.schemaforge.data.ETGOAttrDef;

/**
 * Filter and sort the Product list by an attribute value through NEO (ETP-5977, H9).
 *
 * <p>Request parameters (all optional; none of them is HQL, every value is validated and bound):
 * <ul>
 *   <li>{@code attrFilterDef} - id of the attribute definition to filter by, with one of
 *   {@code attrFilterMin}/{@code attrFilterMax} (numbers), {@code attrFilterPrefix} (text prefix) or
 *   {@code attrFilterEq};</li>
 *   <li>{@code attrSortDef} - id of the definition to sort by, with {@code attrSortDir} {@code asc} (default) or
 *   {@code desc}. Products without the value are always last.</li>
 * </ul>
 * A request that sends none of them is not touched. The client's {@code existsQuery} criteria operator is
 * not used and not needed.
 */
public final class ProductAttributeListQuery {

  static final String FILTER_DEF = "attrFilterDef";
  static final String FILTER_MIN = "attrFilterMin";
  static final String FILTER_MAX = "attrFilterMax";
  static final String FILTER_PREFIX = "attrFilterPrefix";
  static final String FILTER_EQ = "attrFilterEq";
  static final String SORT_DEF = "attrSortDef";
  static final String SORT_DIR = "attrSortDir";
  private static final String ID_PATTERN = "[0-9A-Fa-f]{32}";
  private static final int MAX_PAGE = 1000;

  private ProductAttributeListQuery() {
  }

  /**
   * @param context       the REST list read context of the Product spec
   * @param readPredicate the entity's own read predicate over alias {@code e} (HQL, server built), or blank
   * @return the page, or {@code null} when the request names no attribute filter or sort
   * @throws IllegalArgumentException when an attribute parameter is invalid
   */
  public static NeoListPage page(NeoContext context, String readPredicate) {
    Map<String, String> q = context.getQueryParams();
    if (q == null || !(q.containsKey(FILTER_DEF) || q.containsKey(SORT_DEF))) {
      return null;
    }
    List<String> where = new ArrayList<>();
    Map<String, Object> binds = new java.util.HashMap<>();
    where.add("e.client.id in (:clients)");
    binds.put("clients", Arrays.asList(OBContext.getOBContext().getReadableClients()));
    where.add("e.organization.id in (:orgs)");
    binds.put("orgs", Arrays.asList(OBContext.getOBContext().getReadableOrganizations()));
    if (StringUtils.isNotBlank(readPredicate)) {
      where.add("(" + readPredicate + ")");
    }
    if (q.containsKey(FILTER_DEF)) {
      where.add(filter(q, binds));
    }
    String whereClause = String.join(" and ", where);

    String join = "";
    String order = " order by e.id";
    if (q.containsKey(SORT_DEF)) {
      ETGOAttrDef def = definition(q.get(SORT_DEF), SORT_DEF);
      String dir = q.getOrDefault(SORT_DIR, "asc").toLowerCase();
      if (!"asc".equals(dir) && !"desc".equals(dir)) {
        throw new IllegalArgumentException(SORT_DIR + " must be asc or desc");
      }
      join = " left join e.eTGOProductAttrValList v with v.attributeDefinition.id = :sortDef";
      binds.put("sortDef", def.getId());
      String column = "N".equals(def.getDataType()) ? "v.numberValue" : "v.textValue";
      order = " order by " + column + " " + dir + " nulls last, e.id";
    }

    int start = parseInt(q.get("_startRow"), 0);
    int size = Math.min(Math.max(parseInt(q.get("_endRow"), 99) - start + 1, 1), MAX_PAGE);

    Query<String> ids = OBDal.getInstance().getSession()
        .createQuery("select e.id from Product e" + join + " where " + whereClause + order, String.class);
    bind(ids, binds);
    ids.setFirstResult(start);
    ids.setMaxResults(size);
    ids.setHibernateFlushMode(FlushMode.MANUAL);
    List<String> page = ids.list();

    long total;
    if ("false".equalsIgnoreCase(q.get("_noCount"))) {
      Query<Long> count = OBDal.getInstance().getSession()
          .createQuery("select count(e.id) from Product e where " + whereClause, Long.class);
      Map<String, Object> countBinds = new java.util.HashMap<>(binds);
      countBinds.remove("sortDef");
      bind(count, countBinds);
      count.setHibernateFlushMode(FlushMode.MANUAL);
      total = count.uniqueResult();
    } else {
      // Same inference core makes without an exact count: what was read, plus one when the page is full.
      total = start + page.size() + (page.size() >= size ? 1 : 0);
    }
    return new NeoListPage(page, total);
  }

  private static String filter(Map<String, String> q, Map<String, Object> binds) {
    ETGOAttrDef def = definition(q.get(FILTER_DEF), FILTER_DEF);
    boolean number = "N".equals(def.getDataType());
    String base = "exists (select 1 from ETGO_ProductAttrVal f where f.product = e and f.attributeDefinition.id = :fd";
    binds.put("fd", def.getId());
    List<String> cond = new ArrayList<>();
    if (q.containsKey(FILTER_MIN) || q.containsKey(FILTER_MAX)) {
      requireType(number, FILTER_MIN + "/" + FILTER_MAX, "numeric");
      if (q.containsKey(FILTER_MIN)) {
        cond.add("f.numberValue >= :fmin");
        binds.put("fmin", decimal(q.get(FILTER_MIN), FILTER_MIN));
      }
      if (q.containsKey(FILTER_MAX)) {
        cond.add("f.numberValue <= :fmax");
        binds.put("fmax", decimal(q.get(FILTER_MAX), FILTER_MAX));
      }
    }
    if (q.containsKey(FILTER_PREFIX)) {
      requireType(!number, FILTER_PREFIX, "text");
      cond.add("f.textValue like :fprefix escape '!'");
      binds.put("fprefix", escapeLike(q.get(FILTER_PREFIX)) + "%");
    }
    if (q.containsKey(FILTER_EQ)) {
      if (number) {
        cond.add("f.numberValue = :feq");
        binds.put("feq", decimal(q.get(FILTER_EQ), FILTER_EQ));
      } else {
        cond.add("f.textValue = :feq");
        binds.put("feq", q.get(FILTER_EQ));
      }
    }
    if (cond.isEmpty()) {
      throw new IllegalArgumentException(FILTER_DEF + " needs " + FILTER_MIN + ", " + FILTER_MAX + ", " + FILTER_PREFIX
          + " or " + FILTER_EQ);
    }
    return base + " and " + String.join(" and ", cond) + ")";
  }

  /** The definition must exist, be active, target Product and belong to a client the session may read. */
  private static ETGOAttrDef definition(String id, String param) {
    if (id == null || !id.matches(ID_PATTERN)) {
      throw new IllegalArgumentException(param + " is not a valid attribute definition id");
    }
    ETGOAttrDef def = OBDal.getInstance().get(ETGOAttrDef.class, id);
    boolean usable = def != null && def.isActive()
        && ProductAttributeRules.PRODUCT_TABLE_NAME.equalsIgnoreCase(def.getTable().getDBTableName())
        && Arrays.asList(OBContext.getOBContext().getReadableClients()).contains(def.getClient().getId());
    if (!usable) {
      throw new IllegalArgumentException(param + " does not name an active attribute definition of this client");
    }
    return def;
  }

  private static void requireType(boolean matches, String param, String expected) {
    if (!matches) {
      throw new IllegalArgumentException(param + " applies to " + expected + " attributes only");
    }
  }

  private static BigDecimal decimal(String value, String param) {
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException | NullPointerException e) {
      throw new IllegalArgumentException(param + " is not a number");
    }
  }

  private static int parseInt(String value, int fallback) {
    try {
      return value == null ? fallback : Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static void bind(Query<?> query, Map<String, Object> binds) {
    for (Map.Entry<String, Object> e : binds.entrySet()) {
      if (e.getValue() instanceof java.util.Collection) {
        query.setParameterList(e.getKey(), (java.util.Collection<?>) e.getValue());
      } else {
        query.setParameter(e.getKey(), e.getValue());
      }
    }
  }
}
