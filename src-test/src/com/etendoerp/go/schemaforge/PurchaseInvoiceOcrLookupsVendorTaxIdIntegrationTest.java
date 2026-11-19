/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.test.base.TestConstants;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * Real-database specs for the vendor lookups the invoice inbox matcher relies on
 * ({@link PurchaseInvoiceOcrLookups#vendorsByTaxId}, {@code vendorTaxId}, {@code vendorById}):
 * the HQL over the stored tax id (with or without a VAT prefix, however it was formatted), the
 * vendor/active filters, and above all the tenant fence: another client's vendor is never returned,
 * not even when it carries the very same tax id.
 *
 * <p>Nothing is committed: the fixtures live in the test transaction and are rolled back, so there is
 * no cleanup to get wrong. The tax ids are drawn per run ({@code Q<7 digits>J}, which is not a
 * VAT prefix) so they cannot collide with the vendors of the local database (it holds two vendors
 * with the same real tax id).</p>
 *
 * <p>The other client is any active client other than the system and the F&amp;B group that owns a
 * business partner category; the test fails with a clear message if the database has none.</p>
 *
 * <p>The selector-backed lookups ({@code searchVendors}, {@code vendorAddress}) are also proved here,
 * in the admin mode the invoice inbox worker runs them in: the Schema Forge configuration tables
 * (spec, entity, field) read by the selector fail for a business role outside admin mode, which
 * silently emptied the matcher. The same calls in normal mode are deliberately NOT asserted: whether
 * they work depends on the role's entity access, which is exactly what the worker must not rely on.</p>
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceOcrLookups
 */
public class PurchaseInvoiceOcrLookupsVendorTaxIdIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_ID = TestConstants.Clients.FB_GRP;
  private static final String ORG_ID = TestConstants.Orgs.ESP;
  private static final String SYSTEM = "0";
  private static final int LIMIT = 10;

  private final String marker = "etgo-taxid-" + UUID.randomUUID().toString().substring(0, 8);
  private final String taxBase = "Q" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_999_999)) + "J";
  private final PurchaseInvoiceOcrLookups lookups = new PurchaseInvoiceOcrLookups();

  @Before
  public void prepareContext() {
    OBContext.clearAdminModeStack();
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, CLIENT_ID,
        ORG_ID);
  }

  @After
  public void cleanUp() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext((OBContext) null);
  }

  // ---------------------------------------------------------------- by tax id

  @Test
  public void aVendorStoredWithoutPrefixIsFoundWithOrWithoutOne() {
    BusinessPartner vendor = vendor("Alpha", taxBase, true, true, CLIENT_ID);

    assertEquals(List.of(vendor.getId()), ids(lookups.vendorsByTaxId(taxBase, LIMIT)));
    assertEquals(List.of(vendor.getId()), ids(lookups.vendorsByTaxId("ES" + taxBase, LIMIT)));
  }

  @Test
  public void aVendorStoredWithPrefixIsFoundWithoutOne() {
    BusinessPartner vendor = vendor("Alpha", "ES" + taxBase, true, true, CLIENT_ID);

    assertEquals(List.of(vendor.getId()), ids(lookups.vendorsByTaxId(taxBase, LIMIT)));
    assertEquals(List.of(vendor.getId()), ids(lookups.vendorsByTaxId("ES" + taxBase, LIMIT)));
  }

  @Test
  public void aStoredIdWrittenWithSeparatorsIsStillFound() {
    String formatted = taxBase.substring(0, 1) + "-" + taxBase.substring(1, 4) + "." + taxBase.substring(4,
        7) + " " + taxBase.substring(7);
    BusinessPartner vendor = vendor("Alpha", formatted, true, true, CLIENT_ID);

    assertEquals(List.of(vendor.getId()), ids(lookups.vendorsByTaxId(taxBase, LIMIT)));
  }

  @Test
  public void twoVendorsSharingATaxIdAreBothReturnedByName() {
    BusinessPartner second = vendor("Beta", taxBase, true, true, CLIENT_ID);
    BusinessPartner first = vendor("Alpha", "ES" + taxBase, true, true, CLIENT_ID);

    assertEquals(List.of(first.getId(), second.getId()), ids(lookups.vendorsByTaxId(taxBase, LIMIT)));
  }

  @Test
  public void anotherTaxIdMatchesNothing() {
    vendor("Alpha", taxBase, true, true, CLIENT_ID);

    assertTrue(lookups.vendorsByTaxId("Q0000000X", LIMIT).isEmpty());
  }

  @Test
  public void theLimitCapsTheAnswer() {
    vendor("Alpha", taxBase, true, true, CLIENT_ID);
    vendor("Beta", taxBase, true, true, CLIENT_ID);
    vendor("Gamma", taxBase, true, true, CLIENT_ID);

    assertEquals(2, lookups.vendorsByTaxId(taxBase, 2).size());
  }

  @Test
  public void anInactiveVendorAndAPartnerThatIsNotAVendorAreNotReturned() {
    vendor("Inactive", taxBase, true, false, CLIENT_ID);
    vendor("CustomerOnly", taxBase, false, true, CLIENT_ID);

    assertTrue(lookups.vendorsByTaxId(taxBase, LIMIT).isEmpty());
  }

  // ---------------------------------------------------------------- tenant fence

  @Test
  public void aVendorOfAnotherClientIsNeverReturnedEvenWithTheSameTaxId() {
    String otherClient = otherClientId();
    BusinessPartner mine = vendor("Mine", taxBase, true, true, CLIENT_ID);
    BusinessPartner theirs = vendor("Theirs", taxBase, true, true, otherClient);

    List<String> found = ids(lookups.vendorsByTaxId(taxBase, LIMIT));

    assertEquals(List.of(mine.getId()), found);
    assertFalse(found.contains(theirs.getId()));
  }

  @Test
  public void whenOnlyAnotherClientHoldsTheTaxIdTheAnswerIsEmpty() {
    vendor("Theirs", taxBase, true, true, otherClientId());

    assertTrue(lookups.vendorsByTaxId(taxBase, LIMIT).isEmpty());
  }

  @Test
  public void theStoredTaxIdOfAnotherClientsVendorIsNotReadAndThatOfMineIs() {
    BusinessPartner mine = vendor("Mine", " " + taxBase + " ", true, true, CLIENT_ID);
    BusinessPartner theirs = vendor("Theirs", taxBase, true, true, otherClientId());

    assertEquals(taxBase, lookups.vendorTaxId(mine.getId()));
    assertEquals("", lookups.vendorTaxId(theirs.getId()));
    assertEquals("", lookups.vendorTaxId("NO-SUCH-VENDOR"));
  }

  @Test
  public void aVendorOfAnotherClientIsNotFoundById() {
    BusinessPartner mine = vendor("Mine", taxBase, true, true, CLIENT_ID);
    BusinessPartner theirs = vendor("Theirs", taxBase, true, true, otherClientId());

    assertTrue(lookups.vendorById(mine.getId()).isPresent());
    assertTrue(lookups.vendorById(theirs.getId()).isEmpty());
  }

  // ---------------------------------------------------------------- selector-backed lookups, admin mode

  @Test
  public void searchVendorsByNameFindsMyVendorInAdminMode() {
    BusinessPartner mine = vendor("Selectable", taxBase, true, true, CLIENT_ID);

    List<String> found = inAdminMode(() -> ids(lookups.searchVendors(marker, LIMIT)));

    assertTrue("the selector must return the vendor created in this client: " + found,
        found.contains(mine.getId()));
  }

  @Test
  public void vendorAddressIsPresentWithItsLocationInAdminMode() {
    BusinessPartner mine = vendor("WithAddress", taxBase, true, true, CLIENT_ID);
    String locationId = location(mine.getId());

    Address address = inAdminMode(() -> lookups.vendorAddress(mine.getId()));

    assertEquals(AddressState.PRESENT, address.state());
    assertEquals(locationId, address.locationId());
  }

  @Test
  public void vendorAddressIsMissingForAVendorWithoutLocations() {
    BusinessPartner mine = vendor("NoAddress", taxBase, true, true, CLIENT_ID);

    Address address = inAdminMode(() -> lookups.vendorAddress(mine.getId()));

    assertEquals(AddressState.MISSING, address.state());
  }

  @Test
  public void searchVendorsNeverReturnsAnotherClientsVendorInAdminMode() {
    BusinessPartner mine = vendor("Mine", taxBase, true, true, CLIENT_ID);
    BusinessPartner theirs = vendor("Theirs", taxBase, true, true, otherClientId());

    List<String> found = inAdminMode(() -> ids(lookups.searchVendors(marker, LIMIT)));

    assertTrue("my vendor is returned: " + found, found.contains(mine.getId()));
    assertFalse("admin mode skips only the entity-access check, never the client filter",
        found.contains(theirs.getId()));
  }

  /** Runs the call in admin mode, as the inbox worker does; skips when the spec is not in this database. */
  private static <T> T inAdminMode(java.util.function.Supplier<T> call) {
    OBContext.setAdminMode(true);
    try {
      Assume.assumeTrue("the purchase-invoice spec is not in this database",
          NeoServletSupport.findSpec("purchase-invoice") != null);
      return call.get();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** An active bill-to location for the partner, native SQL like {@link #vendor}; returns its id. */
  private String location(String partnerId) {
    OBContext.setAdminMode(false);
    try {
      String locationId = (String) OBDal.getInstance().getSession()
          .createNativeQuery("SELECT get_uuid()").uniqueResult();
      String partnerLocationId = (String) OBDal.getInstance().getSession()
          .createNativeQuery("SELECT get_uuid()").uniqueResult();
      String country = (String) OBDal.getInstance().getSession().createNativeQuery(
          "SELECT c_country_id FROM c_country WHERE isactive = 'Y' ORDER BY c_country_id LIMIT 1")
          .uniqueResult();
      assertNotNull("the database must have a country", country);
      NativeQuery<?> insertLocation = OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO c_location (c_location_id, ad_client_id, ad_org_id, isactive, createdby, "
              + "updatedby, address1, c_country_id) "
              + "VALUES (:id, :client, :org, 'Y', :user, :user, :address, :country)");
      insertLocation.setParameter("id", locationId);
      insertLocation.setParameter("client", CLIENT_ID);
      insertLocation.setParameter("org", ORG_ID);
      insertLocation.setParameter("user", TestConstants.Users.ADMIN);
      insertLocation.setParameter("address", marker + " street");
      insertLocation.setParameter("country", country);
      insertLocation.executeUpdate();
      NativeQuery<?> insertPartnerLocation = OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO c_bpartner_location (c_bpartner_location_id, ad_client_id, ad_org_id, "
              + "isactive, createdby, updatedby, name, isbillto, isshipto, ispayfrom, isremitto, "
              + "c_bpartner_id, c_location_id) "
              + "VALUES (:id, :client, :org, 'Y', :user, :user, :name, 'Y', 'Y', 'Y', 'Y', "
              + ":partner, :location)");
      insertPartnerLocation.setParameter("id", partnerLocationId);
      insertPartnerLocation.setParameter("client", CLIENT_ID);
      insertPartnerLocation.setParameter("org", ORG_ID);
      insertPartnerLocation.setParameter("user", TestConstants.Users.ADMIN);
      insertPartnerLocation.setParameter("name", marker + " location");
      insertPartnerLocation.setParameter("partner", partnerId);
      insertPartnerLocation.setParameter("location", locationId);
      insertPartnerLocation.executeUpdate();
      return partnerLocationId;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------- helpers

  private static List<String> ids(List<Party> parties) {
    return parties.stream().map(Party::id).collect(Collectors.toList());
  }

  /**
   * A vendor in the given client, inserted with native SQL on the DAL session's own connection.
   * The DAL cannot create it: C_BPartner.EM_Etgo_Identifier is filled by a transactional sequence
   * that only exists for the product tenants, so saving a partner in F&amp;B (or any other client)
   * fails in the generator. The row is visible to the HQL right away (same transaction) and is
   * undone by the rollback in {@link #cleanUp()}.
   */
  private BusinessPartner vendor(String label, String taxId, boolean vendor, boolean active,
      String clientId) {
    OBContext.setAdminMode(false);
    try {
      String id = (String) OBDal.getInstance().getSession()
          .createNativeQuery("SELECT get_uuid()").uniqueResult();
      NativeQuery<?> insert = OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO c_bpartner (c_bpartner_id, ad_client_id, ad_org_id, isactive, createdby, "
              + "updatedby, value, name, taxid, isvendor, iscustomer, c_bp_group_id) "
              + "VALUES (:id, :client, :org, :active, :user, :user, :value, :name, :tax, :vendor, "
              + ":customer, :cat)");
      insert.setParameter("id", id);
      insert.setParameter("client", clientId);
      insert.setParameter("org", CLIENT_ID.equals(clientId) ? ORG_ID : SYSTEM);
      insert.setParameter("active", active ? "Y" : "N");
      insert.setParameter("user", TestConstants.Users.ADMIN);
      insert.setParameter("value", marker + "-" + label + "-" + clientId.substring(0, 4));
      insert.setParameter("name", marker + " " + label);
      insert.setParameter("tax", taxId);
      insert.setParameter("vendor", vendor ? "Y" : "N");
      insert.setParameter("customer", vendor ? "N" : "Y");
      insert.setParameter("cat", categoryIdOf(clientId));
      insert.executeUpdate();
      return OBDal.getInstance().get(BusinessPartner.class, id);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String categoryIdOf(String clientId) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT c_bp_group_id FROM c_bp_group WHERE ad_client_id = :c AND isactive = 'Y' "
            + "ORDER BY isdefault DESC, c_bp_group_id LIMIT 1");
    query.setParameter("c", clientId);
    Object id = query.uniqueResult();
    assertNotNull("client " + clientId + " must own a business partner category", id);
    return (String) id;
  }

  /** Any other client that can own a partner: the fixture the fence is proved against. */
  private static String otherClientId() {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT g.ad_client_id FROM c_bp_group g JOIN ad_client c ON c.ad_client_id = g.ad_client_id "
            + "WHERE g.ad_client_id NOT IN ('0', :fb) AND g.isactive = 'Y' AND c.isactive = 'Y' "
            + "ORDER BY g.ad_client_id LIMIT 1");
    query.setParameter("fb", CLIENT_ID);
    Object id = query.uniqueResult();
    assertNotNull("the tenant fence needs a second client with a business partner category", id);
    return (String) id;
  }
}
