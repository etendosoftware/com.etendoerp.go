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
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductCategory;
import org.openbravo.base.weld.test.WeldBaseTest;

import com.etendoerp.go.schemaforge.data.ETGOAttrDef;
import com.etendoerp.go.schemaforge.data.ETGOAttrDefCategory;
import com.etendoerp.go.schemaforge.data.ETGOProductAttrVal;

/**
 * ETP-5977 POC, H2 and H5 against the real database: the DAL entities, the cascade from Product to
 * its attribute values, and every value and lifecycle rule enforced by
 * {@link ProductAttributeValueEventHandler} and {@link AttributeDefinitionEventHandler}.
 *
 * <p>Fixtures are committed (the cascade and FK checks need real commits) and removed by marker in
 * {@link #cleanUp()} with native SQL.
 *
 * @covers com.etendoerp.go.attributes.ProductAttributeValueEventHandler
 * @covers com.etendoerp.go.attributes.AttributeDefinitionEventHandler
 */
public class ProductAttributeValueRulesIntegrationTest extends WeldBaseTest {

  private static final String USER = "100";
  private static final String ROLE_A = "42D0EEB1C66F497A90DD526DC597E6F0";
  private static final String CLIENT_A = "23C59575B9CF467C9620760EB255B389";
  private static final String ORG_A = "19404EAD144C49A0AF37D54377CF452D";
  private static final String ROLE_B = "4028E6C72959682B01295A071429011E";
  private static final String CLIENT_B = "4028E6C72959682B01295A070852010D";
  private static final String ORG_B = "43D590B4814049C6B85C6545E8264E37";
  private static final String MARK = "ETP5977IT";

  private String catWine;
  private String catElec;
  private String defText;
  private String defNum;

  @Before
  public void setUpFixtures() {
    cleanUp();
    asClientA();
    catWine = newCategory(MARK + "-WINE");
    catElec = newCategory(MARK + "-ELEC");
    defText = newDefinition("t", "T", catWine);
    defNum = newDefinition("n", "N", catWine);
    commit();
  }

  @After
  public void cleanUp() {
    OBDal.getInstance().rollbackAndClose();
    leaveAdminMode();
    exec("delete from etgo_product_attr_val where m_product_id in (select m_product_id from m_product where value like '" + MARK + "%')");
    exec("delete from etgo_product_attr_val where etgo_attr_def_id in (select etgo_attr_def_id from etgo_attr_def where value like '" + MARK + "%')");
    exec("delete from etgo_attr_def_category where etgo_attr_def_id in (select etgo_attr_def_id from etgo_attr_def where value like '" + MARK + "%')");
    exec("delete from etgo_attr_def where value like '" + MARK + "%'");
    exec("delete from m_product where value like '" + MARK + "%'");
    exec("delete from m_product_category where value like '" + MARK + "%'");
    leaveAdminMode();
  }

  private static void leaveAdminMode() {
    while (OBContext.getOBContext() != null && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
  }

  // ---- H2 ----

  @Test
  public void deletingAProductThroughDalRemovesItsValues() {
    String product = newProduct("P1", catWine);
    newValue(product, defText, "red", null);
    newValue(product, defNum, null, new BigDecimal("12.5"));
    commit();
    assertEquals(2L, count("select count(*) from etgo_product_attr_val where m_product_id = '" + product + "'"));

    OBDal.getInstance().remove(OBDal.getInstance().get(Product.class, product));
    commit();

    assertEquals(0L, count("select count(*) from etgo_product_attr_val where m_product_id = '" + product + "'"));
  }

  @Test
  public void aDirectSqlDeleteOfAProductIsCascadedByTheForeignKey() {
    String product = newProduct("P2", catWine);
    newValue(product, defText, "red", null);
    commit();
    exec("delete from m_product where m_product_id = '" + product + "'");
    assertEquals(0L, count("select count(*) from etgo_product_attr_val where m_product_id = '" + product + "'"));
  }

  @Test
  public void noOrphanValuesExistAfterTheCascades() {
    assertEquals(0L, count("select count(*) from etgo_product_attr_val v left join m_product p on p.m_product_id = v.m_product_id where p.m_product_id is null"));
  }

  @Test
  public void theProductExposesItsValuesAsAChildCollection() {
    String product = newProduct("P3", catWine);
    newValue(product, defText, "red", null);
    commit();
    Product loaded = OBDal.getInstance().get(Product.class, product);
    assertEquals(1, loaded.getETGOProductAttrValList().size());
  }

  // ---- H5: value rules ----

  @Test
  public void aTextValueForATextDefinitionIsAccepted() {
    String product = newProduct("P4", catWine);
    newValue(product, defText, "red", null);
    commit();
  }

  @Test
  public void aNumberInATextDefinitionIsRejected() {
    String product = newProduct("P5", catWine);
    commit();
    assertRejected("needs a text value", () -> newValue(product, defText, null, BigDecimal.ONE));
  }

  @Test
  public void aTextInANumberDefinitionIsRejected() {
    String product = newProduct("P6", catWine);
    commit();
    assertRejected("needs a number value", () -> newValue(product, defNum, "x", null));
  }

  @Test
  public void bothValueColumnsSetIsRejected() {
    String product = newProduct("P7", catWine);
    commit();
    assertRejected("Only one of", () -> newValue(product, defNum, "x", BigDecimal.ONE));
  }

  @Test
  public void aDefinitionNotScopedToTheProductCategoryIsRejected() {
    String product = newProduct("P8", catElec);
    commit();
    assertRejected("does not apply to the category", () -> newValue(product, defText, "red", null));
  }

  @Test
  public void aDefinitionFromAnotherClientIsRejected() {
    String product = newProduct("P9", catWine);
    commit();
    asClientB();
    String foreignCat = newCategory(MARK + "-B");
    String foreignDef = newDefinition("tb", "T", foreignCat);
    commit();
    asClientA();
    // The DAL read filter would hide the client B definition from a client A session; the handler must still refuse it.
    assertRejected("another client", () -> newValue(product, foreignDef, "x", null));
  }

  @Test
  public void aDefinitionOnAnotherEntityIsRejected() {
    ETGOAttrDef def = OBDal.getInstance().get(ETGOAttrDef.class, defText);
    def.setTable(OBDal.getInstance().get(Table.class, "259")); // C_Order
    assertRejected("only support M_Product", () -> OBDal.getInstance().flush());
  }

  @Test
  public void aValueCannotBeMovedToAnotherProduct() {
    String p1 = newProduct("P10", catWine);
    String p2 = newProduct("P11", catWine);
    String value = newValue(p1, defText, "red", null);
    commit();
    ETGOProductAttrVal v = OBDal.getInstance().get(ETGOProductAttrVal.class, value);
    v.setProduct(OBDal.getInstance().get(Product.class, p2));
    assertRejected("cannot be changed", () -> OBDal.getInstance().flush());
  }

  // ---- H5: lifecycle ----

  @Test
  public void changingTheTypeOfADefinitionWithValuesIsBlocked() {
    String product = newProduct("P12", catWine);
    newValue(product, defText, "red", null);
    commit();
    ETGOAttrDef def = OBDal.getInstance().get(ETGOAttrDef.class, defText);
    def.setDataType("N");
    assertRejected("cannot change once it has values", () -> OBDal.getInstance().flush());
  }

  @Test
  public void changingTheTypeOfADefinitionWithoutValuesIsAllowed() {
    ETGOAttrDef def = OBDal.getInstance().get(ETGOAttrDef.class, defNum);
    def.setDataType("T");
    commit();
  }

  @Test
  public void removingACategoryFromTheScopeKeepsValuesAndRefusesNewWrites() {
    String product = newProduct("P13", catWine);
    String keep = newValue(product, defText, "red", null);
    String other = newProduct("P14", catWine);
    commit();
    exec("delete from etgo_attr_def_category where etgo_attr_def_id = '" + defText + "'");
    OBDal.getInstance().getSession().clear();

    assertEquals(1L, count("select count(*) from etgo_product_attr_val where etgo_product_attr_val_id = '" + keep + "'"));
    assertRejected("does not apply to the category", () -> newValue(other, defText, "white", null));
    ETGOProductAttrVal kept = OBDal.getInstance().get(ETGOProductAttrVal.class, keep);
    kept.setTextValue("changed");
    assertRejected("does not apply to the category", () -> OBDal.getInstance().flush());
  }

  @Test
  public void movingAProductToAnotherCategoryKeepsItsValuesWriteProtected() {
    String product = newProduct("P15", catWine);
    String value = newValue(product, defText, "red", null);
    commit();
    exec("update m_product set m_product_category_id = '" + catElec + "' where m_product_id = '" + product + "'");
    OBDal.getInstance().getSession().clear();

    assertEquals(1L, count("select count(*) from etgo_product_attr_val where etgo_product_attr_val_id = '" + value + "'"));
    ETGOProductAttrVal v = OBDal.getInstance().get(ETGOProductAttrVal.class, value);
    v.setTextValue("changed");
    assertRejected("does not apply to the category", () -> OBDal.getInstance().flush());
  }

  @Test
  public void aDeactivatedDefinitionKeepsValuesReadOnly() {
    String product = newProduct("P16", catWine);
    String value = newValue(product, defText, "red", null);
    commit();
    exec("update etgo_attr_def set isactive = 'N' where etgo_attr_def_id = '" + defText + "'");
    OBDal.getInstance().getSession().clear();

    assertEquals(1L, count("select count(*) from etgo_product_attr_val where etgo_product_attr_val_id = '" + value + "'"));
    ETGOProductAttrVal v = OBDal.getInstance().get(ETGOProductAttrVal.class, value);
    v.setTextValue("changed");
    assertRejected("is not active", () -> OBDal.getInstance().flush());
  }

  @Test
  public void deletingADefinitionWithValuesIsBlockedByTheForeignKey() {
    String product = newProduct("P17", catWine);
    newValue(product, defText, "red", null);
    commit();
    try {
      exec("delete from etgo_attr_def where etgo_attr_def_id = '" + defText + "'");
      fail("the foreign key should block the delete");
    } catch (RuntimeException expected) {
      assertTrue(messageChain(expected), messageChain(expected).contains("etgo_pav_def_fk"));
    }
  }

  @Test
  public void aMandatoryDefinitionIsNotEnforcedOnProductSave() {
    ETGOAttrDef def = OBDal.getInstance().get(ETGOAttrDef.class, defText);
    def.setMandatory(true);
    newProduct("P18", catWine);
    commit(); // saving a product without its mandatory value succeeds: informational in the POC
  }

  // ---- helpers ----

  private String newCategory(String value) {
    ProductCategory c = OBProvider.getInstance().get(ProductCategory.class);
    c.setSearchKey(value);
    c.setName(value);
    c.setPlannedMargin(BigDecimal.ZERO);
    OBDal.getInstance().save(c);
    return c.getId();
  }

  private String newDefinition(String code, String type, String category) {
    ETGOAttrDef d = OBProvider.getInstance().get(ETGOAttrDef.class);
    d.setSearchKey(MARK + "_" + code + "_" + UUID.randomUUID().toString().substring(0, 6));
    d.setName(d.getSearchKey());
    d.setTable(OBDal.getInstance().get(Table.class, "208"));
    d.setDataType(type);
    d.setMandatory(false);
    OBDal.getInstance().save(d);
    ETGOAttrDefCategory s = OBProvider.getInstance().get(ETGOAttrDefCategory.class);
    s.setAttributeDefinition(d);
    s.setProductCategory(OBDal.getInstance().get(ProductCategory.class, category));
    OBDal.getInstance().save(s);
    return d.getId();
  }

  private String newProduct(String code, String category) {
    Product base = (Product) OBDal.getInstance().getSession()
        .createQuery("from Product p where p.client.id = :c and p.searchKey not like 'POC-%' and p.searchKey not like :m",
            Product.class)
        .setParameter("c", CLIENT_A).setParameter("m", MARK + "%").setMaxResults(1).uniqueResult();
    Product p = (Product) DalUtil.copy(base, false);
    p.setSearchKey(MARK + "-" + code);
    p.setName(MARK + "-" + code);
    p.setProductCategory(OBDal.getInstance().get(ProductCategory.class, category));
    OBDal.getInstance().save(p);
    return p.getId();
  }

  private String newValue(String product, String def, String text, BigDecimal number) {
    ETGOProductAttrVal v = OBProvider.getInstance().get(ETGOProductAttrVal.class);
    v.setProduct(OBDal.getInstance().get(Product.class, product));
    v.setAttributeDefinition(OBDal.getInstance().get(ETGOAttrDef.class, def));
    v.setTextValue(text);
    v.setNumberValue(number);
    OBDal.getInstance().save(v);
    OBDal.getInstance().flush();
    return v.getId();
  }

  /** NEO runs in admin mode, so the tests do too: the new tables have no window and no role entity grant. */
  private static void asClientA() {
    OBContext.setOBContext(USER, ROLE_A, CLIENT_A, ORG_A);
    OBContext.setAdminMode(false);
  }

  private static void asClientB() {
    OBContext.setOBContext(USER, ROLE_B, CLIENT_B, ORG_B);
    OBContext.setAdminMode(false);
  }

  private void commit() {
    OBDal.getInstance().commitAndClose();
    asClientA();
  }

  private void assertRejected(String expectedMessagePart, Runnable action) {
    try {
      action.run();
      OBDal.getInstance().flush();
      fail("expected a rejection containing: " + expectedMessagePart);
    } catch (RuntimeException e) {
      assertTrue(messageChain(e), messageChain(e).contains(expectedMessagePart));
      OBDal.getInstance().rollbackAndClose();
      asClientA();
    }
  }

  private static String messageChain(Throwable e) {
    StringBuilder chain = new StringBuilder();
    for (Throwable t = e; t != null; t = t.getCause()) {
      chain.append(t.getMessage()).append(" | ");
    }
    return chain.toString();
  }

  @SuppressWarnings("unchecked")
  private long count(String sql) {
    NativeQuery<Object> q = OBDal.getInstance().getSession().createNativeQuery(sql);
    List<Object> r = q.list();
    return ((Number) r.get(0)).longValue();
  }

  private void exec(String sql) {
    OBDal.getInstance().getSession().createNativeQuery(sql).executeUpdate();
    OBDal.getInstance().commitAndClose();
  }
}
