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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.TestConstants;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.DocCurrency;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Tax;

/**
 * Real-database specs for the tax and currency lookups of the invoice inbox
 * ({@link PurchaseInvoiceOcrLookups#taxById}, {@code taxDocAmountModes}, {@code taxNames},
 * {@code documentCurrency}, {@code currencyById}): the HQL of the line tax rule (active, valid from
 * the document date, purchase or both, client or System) against real rows, the tenant fence on every
 * one of them, and the currency fallbacks.
 *
 * <p>Nothing is committed: the taxes are inserted with native SQL on the DAL session's own connection
 * (the test transaction) and undone by the rollback, so there is no cleanup to get wrong. The names
 * carry a per-run marker.</p>
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceOcrLookups
 */
public class PurchaseInvoiceOcrLookupsTaxCurrencyIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_ID = TestConstants.Clients.FB_GRP;
  private static final String ORG_ID = TestConstants.Orgs.ESP;
  private static final String SYSTEM = "0";
  private static final String DATE = "2026-11-02";

  private final String marker = "etgo-tax-" + UUID.randomUUID().toString().substring(0, 8);
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

  // ---------------------------------------------------------------- taxById: the line tax rule

  @Test
  public void aPurchaseOrBothTaxValidFromTheDateIsFoundWithItsRate() {
    String purchase = tax("Purchase", "P", "D", "2026-01-01", true, CLIENT_ID, "10");
    String both = tax("Both", "B", "D", "2026-11-02", true, CLIENT_ID, "21");

    Tax foundPurchase = lookups.taxById(purchase, DATE).orElseThrow(AssertionError::new);
    Tax foundBoth = lookups.taxById(both, DATE).orElseThrow(AssertionError::new);

    assertEquals(0, new java.math.BigDecimal("10").compareTo(foundPurchase.rate()));
    assertEquals(0, new java.math.BigDecimal("21").compareTo(foundBoth.rate()));
    assertTrue(foundPurchase.name().contains(marker));
  }

  @Test
  public void aSalesOnlyTaxIsNotFound() {
    assertTrue(lookups.taxById(tax("Sales", "S", "D", "2026-01-01", true, CLIENT_ID, "21"), DATE)
        .isEmpty());
  }

  @Test
  public void anInactiveTaxIsNotFound() {
    assertTrue(lookups.taxById(tax("Off", "P", "D", "2026-01-01", false, CLIENT_ID, "21"), DATE)
        .isEmpty());
  }

  @Test
  public void aTaxValidFromAfterTheDocumentDateIsNotFoundUntilThatDate() {
    String future = tax("Future", "P", "D", "2026-12-01", true, CLIENT_ID, "21");

    assertTrue(lookups.taxById(future, "2026-11-30").isEmpty());
    assertTrue(lookups.taxById(future, "2026-12-01").isPresent());
    assertTrue(lookups.taxById(future, "2027-01-01").isPresent());
  }

  @Test
  public void withoutADateTheRuleIsToday() {
    String today = tax("Today", "P", "D", LocalDate.now().toString(), true, CLIENT_ID, "21");
    String tomorrow = tax("Tomorrow", "P", "D", LocalDate.now().plusDays(1).toString(), true,
        CLIENT_ID, "21");

    assertTrue(lookups.taxById(today, null).isPresent());
    assertTrue(lookups.taxById(tomorrow, null).isEmpty());
    assertTrue(lookups.taxById(today, "not-a-date").isPresent());
  }

  @Test
  public void aTaxOfAnotherClientIsNeverFound() {
    String other = otherClientId();
    String theirs = tax("Theirs", "P", "D", "2026-01-01", true, other, "21");

    assertTrue(lookups.taxById(theirs, DATE).isEmpty());
  }

  @Test
  public void aSystemTaxIsFoundWhenTheDatabaseHasASystemTaxCategory() {
    Assume.assumeTrue("no tax category of the System client in this database",
        categoryIdOf(SYSTEM) != null);
    String system = tax("System", "B", "D", "2026-01-01", true, SYSTEM, "7");

    assertTrue(lookups.taxById(system, DATE).isPresent());
  }

  @Test
  public void anUnknownOrBlankIdIsNotFound() {
    assertTrue(lookups.taxById("NO-SUCH-TAX", DATE).isEmpty());
    assertTrue(lookups.taxById(" ", DATE).isEmpty());
    assertTrue(lookups.taxById(null, DATE).isEmpty());
  }

  // ---------------------------------------------------------------- modes and names

  @Test
  public void theRoundingModeIsLForALineBasedTaxAndDForTheRest() {
    String line = tax("Line", "P", "L", "2026-01-01", true, CLIENT_ID, "21");
    String document = tax("Doc", "P", "D", "2026-01-01", true, CLIENT_ID, "21");

    Map<String, String> modes = lookups.taxDocAmountModes(List.of(line, document, "NOPE"));

    assertEquals("L", modes.get(line));
    assertEquals("D", modes.get(document));
    assertFalse(modes.containsKey("NOPE"));
  }

  @Test
  public void aTaxOfAnotherClientHasNoModeAndNoName() {
    String theirs = tax("Theirs", "P", "L", "2026-01-01", true, otherClientId(), "21");
    String mine = tax("Mine", "P", "L", "2026-01-01", true, CLIENT_ID, "21");

    Map<String, String> modes = lookups.taxDocAmountModes(List.of(theirs, mine));
    Map<String, String> names = lookups.taxNames(List.of(theirs, mine));

    assertEquals(Map.of(mine, "L"), modes);
    assertEquals(List.of(mine), List.copyOf(names.keySet()));
    assertTrue(names.get(mine).contains(marker));
  }

  @Test
  public void aNameIsGivenEvenToATaxThatIsNoLongerValid() {
    String old = tax("Old", "P", "D", "2026-01-01", false, CLIENT_ID, "21");

    assertTrue("the name is for display: no validity rule",
        lookups.taxNames(List.of(old)).containsKey(old));
  }

  // ---------------------------------------------------------------- the document currency

  @Test
  public void withoutAVendorTheCurrencyIsTheOrganizationsElseTheClients() {
    OBContext context = OBContext.getOBContext();
    String expected = context.getCurrentOrganization().getCurrency() != null
        ? context.getCurrentOrganization().getCurrency().getId()
        : context.getCurrentClient().getCurrency() == null ? null
            : context.getCurrentClient().getCurrency().getId();
    Assume.assumeTrue("neither the organization nor the client has a currency", expected != null);

    Optional<DocCurrency> found = lookups.documentCurrency(null);

    assertEquals(expected, found.orElseThrow(AssertionError::new).id());
    assertNotNull(found.get().isoCode());
    assertTrue(found.get().scale() >= 0);
  }

  @Test
  public void anUnknownVendorGetsTheSameCurrencyAsNoVendor() {
    assertEquals(lookups.documentCurrency(null).map(DocCurrency::id),
        lookups.documentCurrency("NO-SUCH-VENDOR").map(DocCurrency::id));
  }

  @Test
  public void aCurrencyByIdCarriesTheStandardPrecisionOfItsRow() {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT c_currency_id, stdprecision FROM c_currency WHERE isactive = 'Y' "
            + "AND stdprecision IS NOT NULL ORDER BY c_currency_id LIMIT 1");
    Object[] row = (Object[]) query.uniqueResult();
    Assume.assumeTrue("no active currency with a precision", row != null);

    DocCurrency found = lookups.currencyById((String) row[0]).orElseThrow(AssertionError::new);

    assertEquals(((Number) row[1]).intValue(), found.scale());
    assertTrue(lookups.currencyById("NO-SUCH-CURRENCY").isEmpty());
  }

  // ---------------------------------------------------------------- helpers

  /**
   * A tax inserted with native SQL (the DAL needs a full tax model to save one), visible to the HQL
   * right away in the same transaction and undone by the rollback.
   */
  private String tax(String label, String salesPurchase, String docTaxAmount, String validFrom,
      boolean active, String clientId, String rate) {
    OBContext.setAdminMode(false);
    try {
      String category = categoryIdOf(clientId);
      assertNotNull("client " + clientId + " must own a tax category", category);
      String id = (String) OBDal.getInstance().getSession()
          .createNativeQuery("SELECT get_uuid()").uniqueResult();
      NativeQuery<?> insert = OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO c_tax (c_tax_id, ad_client_id, ad_org_id, isactive, createdby, updatedby, "
              + "name, validfrom, rate, c_taxcategory_id, sopotype, doctaxamount) "
              + "VALUES (:id, :client, :org, :active, :user, :user, :name, CAST(:valid AS date), "
              + "CAST(:rate AS numeric), :category, :sopo, :mode)");
      insert.setParameter("id", id);
      insert.setParameter("client", clientId);
      insert.setParameter("org", CLIENT_ID.equals(clientId) ? ORG_ID : SYSTEM);
      insert.setParameter("active", active ? "Y" : "N");
      insert.setParameter("user", TestConstants.Users.ADMIN);
      insert.setParameter("name", marker + " " + label);
      insert.setParameter("valid", validFrom);
      insert.setParameter("rate", rate);
      insert.setParameter("category", category);
      insert.setParameter("sopo", salesPurchase);
      insert.setParameter("mode", docTaxAmount);
      insert.executeUpdate();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String categoryIdOf(String clientId) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT c_taxcategory_id FROM c_taxcategory WHERE ad_client_id = :c AND isactive = 'Y' "
            + "ORDER BY isdefault DESC, c_taxcategory_id LIMIT 1");
    query.setParameter("c", clientId);
    return (String) query.uniqueResult();
  }

  /** Any other client that owns a tax category: the fixture the tenant fence is proved against. */
  private static String otherClientId() {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT g.ad_client_id FROM c_taxcategory g JOIN ad_client c ON c.ad_client_id = g.ad_client_id "
            + "WHERE g.ad_client_id NOT IN ('0', :fb) AND g.isactive = 'Y' AND c.isactive = 'Y' "
            + "ORDER BY g.ad_client_id LIMIT 1");
    query.setParameter("fb", CLIENT_ID);
    Object id = query.uniqueResult();
    Assume.assumeTrue("the tenant fence needs a second client with a tax category", id != null);
    return (String) id;
  }
}
