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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.DocCurrency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.ad.system.Client;

/**
 * The validation that needs the database, on top of the pure validator: a vendor without a usable
 * address (a linked one whose address is missing; a new one that prints none or a country that
 * matches nothing) and a document currency with no exchange rate on the invoice date. Both are total:
 * a lookup that cannot be made adds nothing. Also the currency of a document without a stored one.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxLiveChecks
 */
class InvoiceInboxLiveChecksTest {

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBCurrencyUtils> currencyUtils;
  private MockedStatic<DocumentCurrencyRate> rates;
  private MockedStatic<InvoiceInboxReview> review;
  private OcrLookups lookups;
  private IncomingDocument row;
  private NativeQuery<?> countriesQuery;

  @BeforeEach
  @SuppressWarnings({ "unchecked", "rawtypes" })
  void setUp() {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    countriesQuery = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn((NativeQuery) countriesQuery);
    when(((NativeQuery) countriesQuery).list()).thenReturn(List.<Object[]>of(
        new Object[] { "C_ES", "ES", "Spain" }, new Object[] { "C_FR", "FR", "France" }));
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    currencyUtils = mockStatic(OBCurrencyUtils.class);
    rates = mockStatic(DocumentCurrencyRate.class);
    review = mockStatic(InvoiceInboxReview.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    lookups = mock(OcrLookups.class);
    review.when(() -> InvoiceInboxReview.lookupsFor(any())).thenReturn(Optional.of(lookups));
    row = mock(IncomingDocument.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-1");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("C1");
    when(row.getOrganization()).thenReturn(org);
    when(row.getClient()).thenReturn(client);
    when(row.getTargetType()).thenReturn("PURCHASE_INVOICE");
  }

  @AfterEach
  void tearDown() {
    review.close();
    rates.close();
    currencyUtils.close();
    dalStatic.close();
  }

  private static JSONObject document(String vendorJson, String headerExtra) throws Exception {
    return new JSONObject("{header:{vendor:" + vendorJson + ",invoiceDate:'2026-11-02'"
        + headerExtra + "},lines:[]}");
  }

  private static final String LINKED_OK = "{state:'linked',id:'V1',addressState:'PRESENT'}";

  // ------------------------------------------------------------------ the vendor address

  @Test
  @DisplayName("a linked vendor is usable unless its stored address state is MISSING")
  void aLinkedVendor() throws Exception {
    assertTrue(InvoiceInboxLiveChecks.vendorAddressUsable(new JSONObject(LINKED_OK)));
    assertTrue(InvoiceInboxLiveChecks.vendorAddressUsable(
        new JSONObject("{state:'linked',id:'V1',addressState:'UNKNOWN'}")));
    assertTrue(InvoiceInboxLiveChecks.vendorAddressUsable(new JSONObject("{state:'linked',id:'V1'}")));
    assertFalse(InvoiceInboxLiveChecks.vendorAddressUsable(
        new JSONObject("{state:'linked',id:'V1',addressState:'MISSING'}")));
  }

  @Test
  @DisplayName("a vendor that is neither linked nor new, or none at all, adds no address gap")
  void otherVendorStates() throws Exception {
    assertTrue(InvoiceInboxLiveChecks.vendorAddressUsable(null));
    assertTrue(InvoiceInboxLiveChecks.vendorAddressUsable(new JSONObject("{state:'none'}")));
    assertTrue(InvoiceInboxLiveChecks.vendorAddressUsable(new JSONObject("{state:'ambiguous'}")));
  }

  @Test
  @DisplayName("a new vendor needs a printed address part; a blank country is Spain without asking the list")
  void aNewVendor() throws Exception {
    assertFalse(InvoiceInboxLiveChecks.vendorAddressUsable(
        new JSONObject("{state:'new',extracted:{name:'Acme',address:' '}}")));
    assertFalse(InvoiceInboxLiveChecks.vendorAddressUsable(new JSONObject("{state:'new'}")));
    assertTrue(InvoiceInboxLiveChecks.vendorAddressUsable(
        new JSONObject("{state:'new',extracted:{address:'Calle 1'}}")));
    verify(countriesQuery, never()).list();
  }

  @Test
  @DisplayName("a printed country that matches the list is usable, one that matches nothing is not")
  void aNewVendorsCountry() throws Exception {
    assertTrue(InvoiceInboxLiveChecks.vendorAddressUsable(
        new JSONObject("{state:'new',extracted:{city:'Paris',country:'France'}}")));
    assertTrue(InvoiceInboxLiveChecks.vendorAddressUsable(
        new JSONObject("{state:'new',extracted:{city:'Madrid',country:'es'}}")));
    assertFalse(InvoiceInboxLiveChecks.vendorAddressUsable(
        new JSONObject("{state:'new',extracted:{city:'Oslo',country:'Norway'}}")));
  }

  @Test
  @DisplayName("the countries are read with their id, ISO code and name")
  void theCountries() {
    List<InvoiceInboxConfirmSeeds.CountryRow> rows = InvoiceInboxLiveChecks.countries();

    assertEquals(2, rows.size());
    assertEquals("C_ES", InvoiceInboxConfirmSeeds.countryId("ES", rows));
    assertEquals("C_FR", InvoiceInboxConfirmSeeds.countryId("france", rows));
  }

  // ------------------------------------------------------------------ the exchange rate

  private void currencies(String orgCurrency, String documentCurrency) {
    currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG-1")).thenReturn(orgCurrency);
    when(lookups.documentCurrency(any(), eq("ORG-1"))).thenReturn(documentCurrency == null
        ? Optional.empty() : Optional.of(new DocCurrency(documentCurrency, "X", null, 2)));
  }

  private void rateIs(BigDecimal rate) {
    rates.when(() -> DocumentCurrencyRate.lookupRate(any(), any(), any(), any(), any()))
        .thenReturn(rate);
  }

  @Test
  @DisplayName("a foreign currency with no rate on the invoice date adds exchangeRate")
  void aMissingRate() throws Exception {
    currencies("EUR", "USD");
    rateIs(null);

    assertEquals(List.of("exchangeRate"),
        InvoiceInboxLiveChecks.extraMissing(document(LINKED_OK, ""), row));

    rates.verify(() -> DocumentCurrencyRate.lookupRate("EUR", "USD", LocalDate.of(2026, 11, 2),
        "ORG-1", "C1"));
  }

  @Test
  @DisplayName("a rate that exists, the organization's own currency, or no known currency adds nothing")
  void noGapWhenTheRateExistsOrIsNotNeeded() throws Exception {
    currencies("EUR", "USD");
    rateIs(new BigDecimal("1.08"));
    assertTrue(InvoiceInboxLiveChecks.extraMissing(document(LINKED_OK, ""), row).isEmpty());

    currencies("EUR", "EUR");
    rateIs(null);
    assertTrue(InvoiceInboxLiveChecks.extraMissing(document(LINKED_OK, ""), row).isEmpty());

    currencies("EUR", null);
    assertTrue(InvoiceInboxLiveChecks.extraMissing(document(LINKED_OK, ""), row).isEmpty());

    currencies(null, "USD");
    assertTrue(InvoiceInboxLiveChecks.extraMissing(document(LINKED_OK, ""), row).isEmpty());
  }

  @Test
  @DisplayName("without a valid invoice date the rate cannot be asked and adds nothing")
  void noDateNoRateCheck() throws Exception {
    currencies("EUR", "USD");
    rateIs(null);

    assertTrue(InvoiceInboxLiveChecks.extraMissing(new JSONObject(
        "{header:{vendor:" + LINKED_OK + ",invoiceDate:null},lines:[]}"), row).isEmpty());
    assertTrue(InvoiceInboxLiveChecks.extraMissing(new JSONObject(
        "{header:{vendor:" + LINKED_OK + ",invoiceDate:'not a date'},lines:[]}"), row).isEmpty());
  }

  @Test
  @DisplayName("the codes come in order, vendor address then exchange rate")
  void theOrderOfTheCodes() throws Exception {
    currencies("EUR", "USD");
    rateIs(null);

    assertEquals(List.of("vendorAddress", "exchangeRate"), InvoiceInboxLiveChecks.extraMissing(
        document("{state:'linked',id:'V1',addressState:'MISSING'}", ""), row));
  }

  @Test
  @DisplayName("a lookup that fails adds nothing: the check is total")
  void aFailingLookupAddsNothing() throws Exception {
    currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency(anyString()))
        .thenThrow(new IllegalStateException("no session"));

    assertTrue(InvoiceInboxLiveChecks.extraMissing(document(LINKED_OK, ""), row).isEmpty());
    assertTrue(InvoiceInboxLiveChecks.extraMissing(new JSONObject("{}"), row).isEmpty());
    assertTrue(InvoiceInboxLiveChecks.extraMissing(null, row).isEmpty());
  }

  @Test
  @DisplayName("the verdict is the pure one plus the live codes, as MISSING_FIELDS")
  void theVerdictCarriesTheLiveCodes() throws Exception {
    currencies("EUR", null);

    InvoiceInboxValidator.Result verdict = InvoiceInboxLiveChecks.verdict(
        document("{state:'linked',id:'V1',addressState:'MISSING'}", ""), row);

    assertFalse(verdict.isValid());
    assertEquals("MISSING_FIELDS", verdict.getErrorCode());
    assertTrue(verdict.getMissing().contains("vendorAddress"), verdict.getMissing().toString());
  }

  // ------------------------------------------------------------------ the document currency

  @Test
  @DisplayName("the stored currency wins, nothing is looked up")
  void theStoredCurrency() throws Exception {
    JSONObject header = new JSONObject("{currencyId:'JPY',vendor:" + LINKED_OK + "}");

    assertEquals("JPY", InvoiceInboxLiveChecks.documentCurrencyId(header, row, "ORG-1"));

    verify(lookups, never()).documentCurrency(any(), any());
  }

  @Test
  @DisplayName("without a stored currency it is derived with the LINKED vendor only, and the row's organization")
  void theDerivedCurrency() throws Exception {
    when(lookups.documentCurrency("V1", "ORG-1")).thenReturn(
        Optional.of(new DocCurrency("USD", "USD", "$", 2)));
    when(lookups.documentCurrency(null, "ORG-1")).thenReturn(
        Optional.of(new DocCurrency("EUR", "EUR", null, 2)));

    assertEquals("USD", InvoiceInboxLiveChecks.documentCurrencyId(
        new JSONObject("{vendor:" + LINKED_OK + "}"), row, "ORG-1"));
    assertEquals("EUR", InvoiceInboxLiveChecks.documentCurrencyId(
        new JSONObject("{vendor:{state:'ambiguous',id:'V1'}}"), row, "ORG-1"));
    assertEquals("EUR", InvoiceInboxLiveChecks.documentCurrencyId(new JSONObject("{}"), row,
        "ORG-1"));
  }

  @Test
  @DisplayName("no lookups for the document type, or no derivable currency, is null")
  void noCurrencyIsNull() throws Exception {
    when(lookups.documentCurrency(any(), any())).thenReturn(Optional.empty());
    assertNull(InvoiceInboxLiveChecks.documentCurrencyId(new JSONObject("{}"), row, "ORG-1"));

    review.when(() -> InvoiceInboxReview.lookupsFor(any())).thenReturn(Optional.empty());
    assertNull(InvoiceInboxLiveChecks.documentCurrencyId(new JSONObject("{}"), row, "ORG-1"));
  }
}
