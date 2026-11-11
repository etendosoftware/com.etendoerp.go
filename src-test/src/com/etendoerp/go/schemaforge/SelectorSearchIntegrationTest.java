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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.test.base.OBBaseTest;
import org.openbravo.test.base.TestConstants.Clients;
import org.openbravo.test.base.TestConstants.Languages;
import org.openbravo.test.base.TestConstants.Orgs;
import org.openbravo.test.base.TestConstants.Roles;
import org.openbravo.test.base.TestConstants.Users;

/**
 * ETP-6103 — the selector search runs as {@code (upper(col) LIKE :searchLoose AND lower(col) LIKE :search)} so a trigram index
 * on {@code upper(col)} can serve it. Checked here against the real model and database: which
 * fragments are searched as a plain string column, that both predicate forms are valid HQL, and
 * that the translated-name branch still matches accented text in any case.
 *
 * @covers com.etendoerp.go.schemaforge.SelectorOrgFilter
 * @covers com.etendoerp.go.schemaforge.SelectorQueryExecutor
 */
public class SelectorSearchIntegrationTest extends OBBaseTest {
  /** M_Product.C_UOM_ID: a plain TableDir over C_UOM, which has C_UOM_Trl. */
  private static final String PRODUCT_UOM_COLUMN = "1760";
  private static final String BP = "BusinessPartner";

  @Before
  public void setUpAdminContext() {
    // the request locale: the translated-name branch searches in this language
    OBContext.setOBContext(Users.ADMIN, Roles.FB_GRP_ADMIN, Clients.FB_GRP, Orgs.MAIN,
        Languages.ES_ES_ISOCODE);
    OBContext.setAdminMode(false);
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.restorePreviousMode();
  }

  @Test
  public void stringPropertyIsResolvedOnTheRealModel() {
    assertTrue("plain string column", SelectorOrgFilter.isStringProperty(BP, "e", "e.name"));
    assertTrue("string column through a foreign key",
        SelectorOrgFilter.isStringProperty(BP, "e", "e.businessPartnerCategory.name"));
    assertFalse("a foreign key is not a string",
        SelectorOrgFilter.isStringProperty(BP, "e", "e.businessPartnerCategory"));
    assertFalse("a number is not a string", SelectorOrgFilter.isStringProperty(BP, "e", "e.creditLimit"));
    assertFalse("another alias is unknown", SelectorOrgFilter.isStringProperty(BP, "e", "bp.name"));
    assertFalse("an unknown property", SelectorOrgFilter.isStringProperty(BP, "e", "e.noSuchProperty"));
    assertFalse("no entity given", SelectorOrgFilter.isStringProperty(null, "e", "e.name"));
  }

  @Test
  public void customSearchOnStringAndNonStringFragmentsRuns() {
    BusinessPartner partner = OBDal.getInstance()
        .createCriteria(BusinessPartner.class)
        .add(Restrictions.isNotNull(BusinessPartner.PROPERTY_NAME))
        .setMaxResults(1)
        .list()
        .get(0);
    StringBuilder where = new StringBuilder("as e");
    SelectorOrgFilter.appendCustomSearchFilter(where, List.of("e.name", "e.creditLimit"), "e",
        "x", false, BP);
    String hql = where.toString();
    assertTrue(hql, hql.contains("(upper(e.name) LIKE :searchLoose AND lower(e.name) LIKE :search)"));
    assertTrue(hql, hql.contains("(upper(COALESCE(cast(e.creditLimit as string), '')) LIKE :searchLoose AND lower(COALESCE(cast(e.creditLimit as string), '')) LIKE :search)"));

    // a lower-case search finds the upper-case name, through the string branch
    OBQuery<BusinessPartner> query = OBDal.getInstance().createQuery(BusinessPartner.class, hql);
    query.setNamedParameter("search", SelectorOrgFilter.searchPattern(partner.getName()));
    query.setNamedParameter("searchLoose", SelectorOrgFilter.searchLoosePattern(partner.getName()));
    assertTrue("the partner is found by its name in another case", query.count() >= 1);
  }

  @Test
  public void translatedSearchMatchesAccentedTextInAnyCase() throws Exception {
    OBCriteria<UOM> criteria = OBDal.getInstance().createCriteria(UOM.class);
    criteria.add(Restrictions.eq(UOM.PROPERTY_NAME, "Cubic Meter"));
    criteria.setMaxResults(1);
    UOM cubicMeter = (UOM) criteria.uniqueResult();
    Assume.assumeTrue("the Cubic Meter unit exists", cubicMeter != null);
    Assume.assumeTrue("it is translated to Spanish with an accent",
        "Metro cúbico".equals(spanishName(cubicMeter)));

    for (String search : new String[] { "CÚBICO", "cúbico", "Metro Cúb", "cubic meter" }) {
      assertTrue("'" + search + "' finds Cubic Meter",
          searchIds(search).contains(cubicMeter.getId()));
    }
  }

  /**
   * On a C-collation database upper()/lower() only fold ASCII. The ETP-6103 form
   * {@code upper(x) LIKE upper(:s)} then misses accented text; the two-part predicate does not.
   * Native SQL because HQL cannot express COLLATE.
   */
  @Test
  public void twoPartPredicateFindsAccentedTextUnderCCollation() {
    String text = "Metro cúbico";
    String search = "CÚBICO";
    Object broken = OBDal.getInstance().getSession()
        .createNativeQuery("select (upper(cast(:t as text) collate \"C\") like upper(cast(:s as text)))")
        .setParameter("t", text).setParameter("s", "%" + search + "%").uniqueResult();
    assertFalse("documents the ETP-6103 bug: upper() leaves the accent alone under C", (Boolean) broken);
    Object fixed = OBDal.getInstance().getSession()
        .createNativeQuery("select (upper(cast(:t as text) collate \"C\") like :loose"
            + " and lower(cast(:t as text) collate \"C\") like :exact)")
        .setParameter("t", text)
        .setParameter("loose", SelectorOrgFilter.searchLoosePattern(search))
        .setParameter("exact", SelectorOrgFilter.searchPattern(search)).uniqueResult();
    assertTrue("two-part predicate matches under C collation", (Boolean) fixed);
  }

  private static String spanishName(UOM uom) {
    return (String) OBDal.getInstance()
        .getSession()
        .createQuery("select t.name from UOMTrl t where t.uOM.id = :id and t.language.language = 'es_ES'")
        .setParameter("id", uom.getId())
        .uniqueResult();
  }

  private static List<String> searchIds(String search) throws Exception {
    Column column = OBDal.getInstance().get(Column.class, PRODUCT_UOM_COLUMN);
    NeoResponse response = NeoSelectorService.querySelectorByColumn(column,
        column.getDBColumnName(), search, 100, 0, new HashMap<>());
    assertEquals("selector must answer: " + response.getBody(), 200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray("items");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      ids.add(item.getString("id"));
    }
    return ids;
  }
}
