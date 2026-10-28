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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.hql.internal.ast.ASTQueryTranslatorFactory;
import org.hibernate.hql.spi.QueryTranslator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.plm.Product;

/**
 * ETP-5977 POC, H7 and H8: filtering and sorting Product by an attribute in plain HQL, with pagination, against the
 * synthetic dataset of {@code etp-5977-poc/sql/10-dataset.sql} (client A). Also writes the exact SQL Hibernate emits to
 * {@code build/poc/*.sql} so the evidence log measures that SQL and not a hand-written equivalent.
 *
 * @covers com.etendoerp.go.attributes.ProductAttributeRules
 */
public class ProductAttributeQueryIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_A = "23C59575B9CF467C9620760EB255B389";
  private static final Path OUT = Path.of("build", "poc");
  private static final int PAGE = 50;

  private String defNumber;
  private String defText;

  @Before
  public void setUpContext() {
    OBContext.setOBContext("100", "42D0EEB1C66F497A90DD526DC597E6F0", CLIENT_A, "19404EAD144C49A0AF37D54377CF452D");
    OBContext.setAdminMode(false);
    defNumber = singleString("select etgo_attr_def_id from etgo_attr_def where ad_client_id = '" + CLIENT_A + "' and value = 'WINE_n1'");
    defText = singleString("select etgo_attr_def_id from etgo_attr_def where ad_client_id = '" + CLIENT_A + "' and value = 'WINE_t1'");
  }

  @After
  public void leaveAdminMode() {
    while (OBContext.getOBContext() != null && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
  }

  // ---- H7 ----

  @Test
  public void numericRangeFilterReturnsTheExpectedRowsOnEveryPage() throws IOException {
    String where = "as e where exists (select 1 from ETGO_ProductAttrVal v where v.product = e and v.attributeDefinition.id = :d"
        + " and v.numberValue between :a and :b) order by e.id";
    BigDecimal a = new BigDecimal("100");
    BigDecimal b = new BigDecimal("110");
    long expected = nativeCount("select count(*) from etgo_product_attr_val where etgo_attr_def_id = '" + defNumber
        + "' and value_number between 100 and 110");
    assertTrue("the range must match something", expected > PAGE * 3);

    for (int page = 0; page < 4; page++) {
      OBQuery<Product> q = OBDal.getInstance().createQuery(Product.class, where);
      q.setNamedParameter("d", defNumber);
      q.setNamedParameter("a", a);
      q.setNamedParameter("b", b);
      q.setFirstResult(page * PAGE);
      q.setMaxResult(PAGE);
      List<String> actual = ids(q.list());
      List<String> sql = nativeIds("select m_product_id from etgo_product_attr_val where etgo_attr_def_id = '" + defNumber
          + "' and value_number between 100 and 110 order by m_product_id limit " + PAGE + " offset " + page * PAGE);
      assertEquals("page " + page, sql, actual);
    }

    OBQuery<Product> counter = OBDal.getInstance().createQuery(Product.class, where.replace(" order by e.id", ""));
    counter.setNamedParameter("d", defNumber);
    counter.setNamedParameter("a", a);
    counter.setNamedParameter("b", b);
    assertEquals(expected, counter.count());
    capture("h7-number-range", pageQuery(where, "d", defNumber, "a", a, "b", b));
  }

  @Test
  public void textPrefixFilterReturnsTheExpectedRowsOnEveryPage() throws IOException {
    String where = "as e where exists (select 1 from ETGO_ProductAttrVal v where v.product = e and v.attributeDefinition.id = :d"
        + " and v.textValue like :p) order by e.id";
    long expected = nativeCount("select count(*) from etgo_product_attr_val where etgo_attr_def_id = '" + defText
        + "' and value_text like 't1-4%'");
    assertTrue(expected > PAGE * 3);
    for (int page = 0; page < 4; page++) {
      OBQuery<Product> q = OBDal.getInstance().createQuery(Product.class, where);
      q.setNamedParameter("d", defText);
      q.setNamedParameter("p", "t1-4%");
      q.setFirstResult(page * PAGE);
      q.setMaxResult(PAGE);
      assertEquals("page " + page, nativeIds("select m_product_id from etgo_product_attr_val where etgo_attr_def_id = '"
          + defText + "' and value_text like 't1-4%' order by m_product_id limit " + PAGE + " offset " + page * PAGE),
          ids(q.list()));
    }
    OBQuery<Product> counter = OBDal.getInstance().createQuery(Product.class, where.replace(" order by e.id", ""));
    counter.setNamedParameter("d", defText);
    counter.setNamedParameter("p", "t1-4%");
    assertEquals(expected, counter.count());
    capture("h7-text-prefix", pageQuery(where, "d", defText, "p", "t1-4%"));
  }

  // ---- H8 ----

  @Test
  public void sortByAttributeWithLeftJoinWithWalksEveryProductExactlyOnce() throws IOException {
    String hql = "select e from Product e left join e.eTGOProductAttrValList v with v.attributeDefinition.id = :d"
        + " where e.client.id = :c order by v.numberValue, e.id";
    long total = nativeCount("select count(*) from m_product where ad_client_id = '" + CLIENT_A + "'");
    Set<String> seen = new HashSet<>();
    List<String> order = new ArrayList<>();
    int pageSize = 5000;
    for (int offset = 0; offset < total; offset += pageSize) {
      List<Product> page = OBDal.getInstance().getSession().createQuery(hql, Product.class)
          .setParameter("d", defNumber).setParameter("c", CLIENT_A)
          .setFirstResult(offset).setMaxResults(pageSize).list();
      for (Product p : page) {
        assertTrue("duplicate " + p.getId(), seen.add(p.getId()));
        order.add(p.getId());
      }
      OBDal.getInstance().getSession().clear();
    }
    assertEquals("every product exactly once", total, seen.size());

    // Compare the walked order with the same ordering done in SQL: null values are last (PostgreSQL ASC default).
    List<String> sql = nativeIds("select p.m_product_id from m_product p left join etgo_product_attr_val v"
        + " on v.m_product_id = p.m_product_id and v.etgo_attr_def_id = '" + defNumber + "'"
        + " where p.ad_client_id = '" + CLIENT_A + "' order by v.value_number, p.m_product_id");
    assertEquals(sql, order);
    org.hibernate.query.Query<Product> sortQuery = OBDal.getInstance().getSession().createQuery(hql, Product.class)
        .setParameter("d", defNumber).setParameter("c", CLIENT_A);
    capture("h8-sort-number", sortQuery);
    // Mitigation under test: sort the narrow id projection, then load the page's products by id.
    capture("h8-sort-number-ids", OBDal.getInstance().getSession().createQuery(
        "select e.id from Product e left join e.eTGOProductAttrValList v with v.attributeDefinition.id = :d"
            + " where e.client.id = :c order by v.numberValue, e.id", String.class)
        .setParameter("d", defNumber).setParameter("c", CLIENT_A));
  }

  // ---- H4 / H6 read queries (captured for the SQL timing) ----

  @Test
  public void valuesOfOneProductAndDefinitionsOfOneCategoryAreCaptured() throws IOException {
    String product = singleString("select m_product_id from m_product where value = 'POC-A-000001'");
    String category = singleString("select m_product_category_id from m_product where value = 'POC-A-000001'");
    OBQuery<com.etendoerp.go.schemaforge.data.ETGOProductAttrVal> values = OBDal.getInstance().createQuery(
        com.etendoerp.go.schemaforge.data.ETGOProductAttrVal.class,
        "as v where v.product.id = :p order by v.attributeDefinition.sequenceNumber");
    values.setNamedParameter("p", product);
    assertEquals(8, values.list().size());
    capture("h4-values-of-product", values.createQuery(), false);

    OBQuery<com.etendoerp.go.schemaforge.data.ETGOAttrDef> defs = OBDal.getInstance().createQuery(
        com.etendoerp.go.schemaforge.data.ETGOAttrDef.class,
        "as d where d.active = true and exists (select 1 from ETGO_AttrDefCategory c where c.attributeDefinition = d"
            + " and c.productCategory.id = :c and c.active = true) order by d.sequenceNumber");
    defs.setNamedParameter("c", category);
    assertEquals(8, defs.list().size());
    capture("h6-definitions-of-category", defs.createQuery(), false);
  }

  // ---- helpers ----

  private static org.hibernate.query.Query<Product> pageQuery(String where, Object... nameValue) {
    OBQuery<Product> q = OBDal.getInstance().createQuery(Product.class, where);
    for (int i = 0; i < nameValue.length; i += 2) {
      q.setNamedParameter((String) nameValue[i], nameValue[i + 1]);
    }
    return q.createQuery();
  }

  /**
   * Writes {@code build/poc/<name>.sql}: the HQL, then the exact SQL Hibernate emits for it with every bound value
   * inlined (the benchmark appends its own {@code limit}/{@code offset}), and {@code <name>.count.sql} with the same
   * statement as an exact count.
   */
  private void capture(String name, org.hibernate.query.Query<?> query) throws IOException {
    capture(name, query, true);
  }

  private void capture(String name, org.hibernate.query.Query<?> query, boolean withCount) throws IOException {
    String hql = query.getQueryString();
    Files.createDirectories(OUT);
    Files.writeString(OUT.resolve(name + ".sql"), "-- HQL: " + hql + "\n" + toSql(hql, query) + "\n", StandardCharsets.UTF_8);
    if (!withCount) {
      return;
    }
    String countHql = "select count(e) " + hql.substring(hql.indexOf(" from ")).replaceAll("(?i) order by .*$", "");
    Files.writeString(OUT.resolve(name + ".count.sql"), "-- HQL: " + countHql + "\n" + toSql(countHql, query) + "\n",
        StandardCharsets.UTF_8);
  }

  private String toSql(String hql, org.hibernate.query.Query<?> query) {
    SessionFactoryImplementor sf = (SessionFactoryImplementor) OBDal.getInstance().getSession().getSessionFactory();
    QueryTranslator qt = new ASTQueryTranslatorFactory().createQueryTranslator("poc", hql, Collections.emptyMap(), sf, null);
    qt.compile(Collections.emptyMap(), false);
    String sql = qt.getSQLString();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)").matcher(hql);
    List<String> names = new ArrayList<>();
    while (m.find()) {
      names.add(m.group(1));
    }
    StringBuilder out = new StringBuilder();
    int from = 0;
    for (String name : names) {
      int q = sql.indexOf('?', from);
      out.append(sql, from, q).append(literal(query.getParameterValue(name)));
      from = q + 1;
    }
    return out.append(sql.substring(from)).toString();
  }

  private static String literal(Object v) {
    if (v instanceof java.util.Collection) {
      List<String> parts = new ArrayList<>();
      for (Object o : (java.util.Collection<?>) v) {
        parts.add(literal(o));
      }
      return String.join(", ", parts);
    }
    return v instanceof Number ? v.toString() : "'" + v.toString().replace("'", "''") + "'";
  }

  private static List<String> ids(List<Product> products) {
    List<String> r = new ArrayList<>();
    for (Product p : products) {
      r.add(p.getId());
    }
    return r;
  }

  @SuppressWarnings("unchecked")
  private static List<String> nativeIds(String sql) {
    return OBDal.getInstance().getSession().createNativeQuery(sql).list();
  }

  private static long nativeCount(String sql) {
    return ((Number) OBDal.getInstance().getSession().createNativeQuery(sql).uniqueResult()).longValue();
  }

  private static String singleString(String sql) {
    return (String) OBDal.getInstance().getSession().createNativeQuery(sql).uniqueResult();
  }
}
