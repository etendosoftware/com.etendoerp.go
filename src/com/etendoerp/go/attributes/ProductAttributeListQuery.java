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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.hibernate.FlushMode;
import org.hibernate.query.Query;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoListPage;
import com.etendoerp.go.schemaforge.data.ETGOAttrDef;

/**
 * Filter and sort the Product list by an attribute value through NEO (ETP-5977, H9): the DAL side. Parsing is
 * {@link ProductAttributeListRequest}, the HQL is {@link ProductAttributeListHql}; this class resolves the definitions
 * the request names, runs the two queries and returns the page. A request with no attribute parameter is not touched.
 */
public final class ProductAttributeListQuery {

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
    if (!ProductAttributeListRequest.applies(q)) {
      return null;
    }
    ProductAttributeListRequest request = ProductAttributeListRequest.parse(q, ProductAttributeListQuery::definition);
    OBContext obContext = OBContext.getOBContext();
    ProductAttributeListHql hql = ProductAttributeListHql.build(request, readPredicate,
        Arrays.asList(obContext.getReadableClients()), Arrays.asList(obContext.getReadableOrganizations()));

    int start = parseInt(q.get("_startRow"), 0);
    int size = Math.min(Math.max(parseInt(q.get("_endRow"), 99) - start + 1, 1), MAX_PAGE);

    Query<String> ids = OBDal.getInstance().getSession().createQuery(hql.getIdsHql(), String.class);
    bind(ids, hql.getIdsBinds());
    ids.setFirstResult(start);
    ids.setMaxResults(size);
    ids.setHibernateFlushMode(FlushMode.MANUAL);
    List<String> page = ids.list();

    long total;
    if ("false".equalsIgnoreCase(q.get("_noCount"))) {
      Query<Long> count = OBDal.getInstance().getSession().createQuery(hql.getCountHql(), Long.class);
      bind(count, hql.getCountBinds());
      count.setHibernateFlushMode(FlushMode.MANUAL);
      total = count.uniqueResult();
    } else {
      // Same inference core makes without an exact count: what was read, plus one when the page is full.
      total = start + page.size() + (page.size() >= size ? 1 : 0);
    }
    return new NeoListPage(page, total);
  }

  /** The definition must exist, be active, target Product and belong to a client the session may read. */
  private static ProductAttributeListRequest.Definition definition(String id) {
    ETGOAttrDef def = OBDal.getInstance().get(ETGOAttrDef.class, id);
    boolean usable = def != null && def.isActive()
        && ProductAttributeRules.PRODUCT_TABLE_NAME.equalsIgnoreCase(def.getTable().getDBTableName())
        && Arrays.asList(OBContext.getOBContext().getReadableClients()).contains(def.getClient().getId());
    if (!usable) {
      throw new IllegalArgumentException("The attribute definition is not an active definition of this client");
    }
    return new ProductAttributeListRequest.Definition(def.getId(), "N".equals(def.getDataType()));
  }

  private static int parseInt(String value, int fallback) {
    try {
      return value == null ? fallback : Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static void bind(Query<?> query, Map<String, Object> binds) {
    for (Map.Entry<String, Object> e : binds.entrySet()) {
      if (e.getValue() instanceof Collection) {
        query.setParameterList(e.getKey(), (Collection<?>) e.getValue());
      } else {
        query.setParameter(e.getKey(), e.getValue());
      }
    }
  }
}
