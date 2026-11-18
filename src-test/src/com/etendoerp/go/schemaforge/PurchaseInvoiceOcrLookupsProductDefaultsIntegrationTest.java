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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.TestConstants;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * Real-database specs for what a NEW product of an inbox line is pre-filled with
 * ({@link PurchaseInvoiceOcrLookups#defaultProductCategory}, {@code uomByPrintedUnit},
 * {@code defaultUom}): the organization tree rule of the AD default of the category, the fence of
 * the tenant on every lookup, and the order tenant, System, none.
 *
 * <p>Nothing is committed: the categories and units are inserted with native SQL on the DAL
 * session's own connection and undone by the rollback. The organization tree is the one of the
 * F&amp;B sample client (Group, then Spain, then two regions; the US branch is a sibling of
 * Spain). The ids of the inserted categories start with {@code FFFFFFFF}, above every real id, so
 * the "greatest id" rule picks them whenever they are eligible, whatever the database holds.</p>
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceOcrLookups
 */
public class PurchaseInvoiceOcrLookupsProductDefaultsIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_ID = TestConstants.Clients.FB_GRP;
  private static final String OTHER_CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String GROUP = "19404EAD144C49A0AF37D54377CF452D";
  private static final String SPAIN = "B843C30461EA4501935CB1D125C9C25A";
  private static final String NORTH = "E443A31992CB4635AFCAEABE7183CE85";
  private static final String SOUTH = "DC206C91AA6A4897B44DA897936E0EC3";
  private static final String US = "2E60544D37534C0B89E765FE29BC0B43";
  private static final String STAR = "0";
  private static final String SYSTEM = "0";

  private final String marker = "etgo-defaults-" + UUID.randomUUID().toString().substring(0, 8);
  private final PurchaseInvoiceOcrLookups lookups = new PurchaseInvoiceOcrLookups();
  private int counter;
  private String before;

  @Before
  public void prepareContext() {
    OBContext.clearAdminModeStack();
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, CLIENT_ID,
        TestConstants.Orgs.ESP);
    before = snapshot();
  }

  @After
  public void cleanUp() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, CLIENT_ID,
        TestConstants.Orgs.ESP);
    try {
      assertEquals("the spec left a trace in the database", before, snapshot());
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ---------------------------------------------------------------- defaultProductCategory

  @Test
  public void theCategoryOfTheOwnOrganizationIsTheDefault() {
    String own = category(NORTH, CLIENT_ID, "Y", "N", "Y");

    assertEquals(own, defaultFor(NORTH));
  }

  @Test
  public void aCategoryOfAParentOrganizationIsInheritedByTheChildren() {
    String parent = category(SPAIN, CLIENT_ID, "Y", "N", "Y");
    String group = category(GROUP, CLIENT_ID, "Y", "N", "Y");

    // the greatest id of the whole parent chain wins: both are eligible for North
    assertEquals(String.valueOf(parent.compareTo(group) > 0 ? parent : group), defaultFor(NORTH));
    assertEquals(String.valueOf(parent.compareTo(group) > 0 ? parent : group), defaultFor(SOUTH));
    assertEquals(String.valueOf(parent.compareTo(group) > 0 ? parent : group), defaultFor(SPAIN));
  }

  @Test
  public void theStarOrganizationCountsAsAParentOfEveryOrganization() {
    String star = category(STAR, CLIENT_ID, "Y", "N", "Y");

    assertEquals(star, defaultFor(NORTH));
    assertEquals(star, defaultFor(US));
  }

  @Test
  public void aCategoryOfASiblingOrAChildOrganizationIsNotInherited() {
    String sibling = category(SOUTH, CLIENT_ID, "Y", "N", "Y");
    String child = category(NORTH, CLIENT_ID, "Y", "N", "Y");

    assertNotEquals(sibling, defaultFor(NORTH));
    assertNotEquals(child, defaultFor(SPAIN));
    assertNotEquals(child, defaultFor(SOUTH));
    assertEquals(sibling, defaultFor(SOUTH));
  }

  @Test
  public void onlyActiveNonSummaryDefaultCategoriesAreEligible() {
    String notDefault = category(NORTH, CLIENT_ID, "N", "N", "Y");
    String summary = category(NORTH, CLIENT_ID, "Y", "Y", "Y");
    String inactive = category(NORTH, CLIENT_ID, "Y", "N", "N");

    String found = defaultFor(NORTH);

    assertNotEquals(notDefault, found);
    assertNotEquals(summary, found);
    assertNotEquals(inactive, found);
  }

  @Test
  public void aCategoryOfAnotherClientIsNeverTheDefault() {
    String theirs = category(STAR, OTHER_CLIENT, "Y", "N", "Y");

    assertNotEquals(theirs, defaultFor(NORTH));
  }

  @Test
  public void twoDefaultsInTheSameScopeGiveTheGreatestId() {
    String first = category(NORTH, CLIENT_ID, "Y", "N", "Y");
    String second = category(NORTH, CLIENT_ID, "Y", "N", "Y");

    assertEquals(first.compareTo(second) > 0 ? first : second, defaultFor(NORTH));
  }

  @Test
  public void withoutAnOrganizationTheCurrentOneOfTheSessionIsUsed() {
    String spain = category(SPAIN, CLIENT_ID, "Y", "N", "Y");

    // the session organization is Spain: a blank document organization falls back to it
    Optional<Party> found = lookups.defaultProductCategory(null);

    assertTrue(found.isPresent());
    assertEquals(spain, found.get().id());
  }

  @Test
  public void theSessionOrganizationDoesNotChangeTheAnswerForTheOrganizationOfTheRow() {
    String north = category(NORTH, CLIENT_ID, "Y", "N", "Y");
    String sessionOnly = category(US, CLIENT_ID, "Y", "N", "Y");

    // the session is in Spain, the row belongs to North: North's default, never the US one
    assertEquals(north, defaultFor(NORTH));
    assertNotEquals(sessionOnly, defaultFor(NORTH));
  }

  // ---------------------------------------------------------------- uomByPrintedUnit

  @Test
  public void aPrintedUnitResolvesToTheUnitOfTheTenant() {
    String unit = uom(CLIENT_ID, "ZQ" + counter(), "N");

    Optional<Party> found = lookups.uomByPrintedUnit(symbolOf(unit));

    assertTrue(found.isPresent());
    assertEquals(unit, found.get().id());
  }

  @Test
  public void aPrintedUnitNeverResolvesToTheUnitOfAnotherClient() {
    String theirs = uom(OTHER_CLIENT, "ZR" + counter(), "N");

    assertTrue(lookups.uomByPrintedUnit(symbolOf(theirs)).isEmpty());
  }

  @Test
  public void aSystemUnitIsFoundAndTheTenantOneWinsOverItWhenBothSpellTheSame() {
    String system = uom(SYSTEM, "ZS" + counter(), "N");
    String printed = symbolOf(system);

    assertEquals(system, lookups.uomByPrintedUnit(printed).orElseThrow(AssertionError::new).id());

    String tenant = uom(CLIENT_ID, printed, "N");
    assertEquals(tenant, lookups.uomByPrintedUnit(printed).orElseThrow(AssertionError::new).id());
  }

  // ---------------------------------------------------------------- defaultUom

  @Test
  public void theDefaultUnitIsTheTenantsFirstThenSystemsAndNeverAnotherClients() {
    Optional<Party> system = lookups.defaultUom();
    // the F&B sample has no default unit of its own: the System one is taken
    assertTrue(system.isPresent());
    assertEquals("100", system.get().id());

    uom(OTHER_CLIENT, "ZT" + counter(), "Y");
    assertEquals("100", lookups.defaultUom().orElseThrow(AssertionError::new).id());

    String tenant = uom(CLIENT_ID, "ZU" + counter(), "Y");
    assertEquals(tenant, lookups.defaultUom().orElseThrow(AssertionError::new).id());
  }

  // ---------------------------------------------------------------- fixtures

  private String defaultFor(String organizationId) {
    return lookups.defaultProductCategory(organizationId).map(Party::id).orElse("<none>");
  }

  private int counter() {
    return ++counter;
  }

  /** Inserts a product category with an id above every real one, in the test transaction. */
  private String category(String org, String client, String isDefault, String summary,
      String active) {
    String id = String.format("FFFFFFFF%024d", (long) (counter + 1) * 7919L
        + Math.abs(marker.hashCode()) % 100000L);
    counter++;
    OBContext.setAdminMode(false);
    try {
      NativeQuery<?> insert = OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO m_product_category (m_product_category_id, ad_client_id, ad_org_id,"
              + " isactive, createdby, updatedby, value, name, isdefault, plannedmargin,"
              + " issummary) VALUES (:id, :client, :org, :active, :user, :user, :name, :name,"
              + " :isdefault, 0, :summary)");
      insert.setParameter("id", id);
      insert.setParameter("client", client);
      insert.setParameter("org", org);
      insert.setParameter("active", active);
      insert.setParameter("user", TestConstants.Users.ADMIN);
      insert.setParameter("name", marker + counter);
      insert.setParameter("isdefault", isDefault);
      insert.setParameter("summary", summary);
      insert.executeUpdate();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Inserts a unit whose symbol and EDI code are the given text, in the test transaction. */
  private String uom(String client, String symbol, String isDefault) {
    String id = (String) OBDal.getInstance().getSession().createNativeQuery("SELECT get_uuid()")
        .uniqueResult();
    OBContext.setAdminMode(false);
    try {
      NativeQuery<?> insert = OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO c_uom (c_uom_id, ad_client_id, ad_org_id, isactive, createdby, updatedby,"
              + " x12de355, name, uomsymbol, stdprecision, costingprecision, isdefault,"
              + " useinproduction) VALUES (:id, :client, '0', 'Y', :user, :user, :edi, :name,"
              + " :symbol, 2, 2, :isdefault, 'N')");
      insert.setParameter("id", id);
      insert.setParameter("client", client);
      insert.setParameter("user", TestConstants.Users.ADMIN);
      insert.setParameter("edi", "ZZ");
      insert.setParameter("name", marker + " " + symbol + " " + (client.length() > 4 ? client.substring(0, 4) : client));
      insert.setParameter("symbol", symbol);
      insert.setParameter("isdefault", isDefault);
      insert.executeUpdate();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String symbolOf(String unitId) {
    return (String) OBDal.getInstance().getSession().createNativeQuery(
        "SELECT uomsymbol FROM c_uom WHERE c_uom_id = :id").setParameter("id", unitId)
        .uniqueResult();
  }

  private static String snapshot() {
    return String.valueOf(OBDal.getInstance().getSession().createNativeQuery(
        "SELECT (SELECT count(*) FROM m_product_category) || '/' || (SELECT count(*) FROM c_uom)")
        .uniqueResult());
  }
}
