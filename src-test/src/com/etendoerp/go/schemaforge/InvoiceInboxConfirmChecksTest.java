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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.pricing.pricelist.PriceList;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Tax;

/**
 * What {@code confirm} judges before it writes anything: the verdict (an invalid document moves per
 * the status policy, a document whose only gaps are the live ones is refused with their own code and
 * the row left as it is), the references the document points at, the price list, the duplicate of
 * the same vendor and number, and the address of a linked vendor.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmChecks
 */
class InvoiceInboxConfirmChecksTest {

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<InvoiceInboxLiveChecks> live;
  private MockedStatic<InvoiceInboxMarkReviewed> markReviewed;
  private MockedStatic<OBCurrencyUtils> currencyUtils;
  private OBDal dal;
  private IncomingDocument row;
  private OcrLookups lookups;
  private NeoResponse notValid;

  @BeforeEach
  void setUp() throws Exception {
    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    messages = mockStatic(OBMessageUtils.class);
    live = mockStatic(InvoiceInboxLiveChecks.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    markReviewed = mockStatic(InvoiceInboxMarkReviewed.class);
    currencyUtils = mockStatic(OBCurrencyUtils.class);
    notValid = NeoResponse.error(422, "not valid");
    markReviewed.when(() -> InvoiceInboxMarkReviewed.notValid(any(), any())).thenReturn(notValid);
    row = mock(IncomingDocument.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-1");
    when(row.getOrganization()).thenReturn(org);
    lookups = mock(OcrLookups.class);
    when(lookups.vendorById(anyString())).thenReturn(Optional.of(new Party("V1", "Acme")));
    when(lookups.productById(anyString())).thenReturn(Optional.of(new Party("P1", "Prod")));
    when(lookups.productCategoryById(anyString())).thenReturn(Optional.of(new Party("C1", "Cat")));
    when(lookups.uomById(anyString())).thenReturn(Optional.of(new Party("U1", "Unit")));
    when(lookups.taxById(anyString(), any())).thenReturn(Optional.of(
        new Tax("T21", "21%", new java.math.BigDecimal("21"))));
    when(lookups.duplicates(anyString(), anyString())).thenReturn(new Duplicates(true, List.of()));
    when(lookups.vendorAddress(anyString())).thenReturn(new Address(AddressState.PRESENT, "LOC1"));
    valid();
  }

  @AfterEach
  void tearDown() {
    currencyUtils.close();
    markReviewed.close();
    live.close();
    messages.close();
    dalStatic.close();
  }

  private void verdict(InvoiceInboxValidator.Result result) {
    live.when(() -> InvoiceInboxLiveChecks.verdict(any(), any())).thenReturn(result);
  }

  private void valid() {
    verdict(new InvoiceInboxValidator.Result(List.of(), null));
  }

  private static InvoiceInboxValidator.Result invalid(String code, String... missing) {
    return new InvoiceInboxValidator.Result(List.of(missing), code);
  }

  private static JSONObject document(String vendorJson, String linesJson) throws Exception {
    return new JSONObject("{header:{vendor:" + vendorJson + ",documentNo:'F-1',"
        + "invoiceDate:'2026-11-02'},lines:" + linesJson + "}");
  }

  private static final String LINKED = "{state:'linked',id:'V1'}";
  private static final String NEW_VENDOR = "{state:'new',extracted:{name:'Acme'}}";
  private static final String PLAIN_LINE = "[{index:0,product:{state:'linked',id:'P1'},"
      + "tax:{id:'T21'}}]";

  private NeoResponse preflight(JSONObject document) throws Exception {
    return InvoiceInboxConfirmChecks.preflight(row, document, Optional.of(lookups));
  }

  private static void assertRefusal(NeoResponse response, int status, String code)
      throws Exception {
    assertEquals(status, response.getHttpStatus());
    assertEquals(code, response.getBody().getJSONObject("error").getString("code"));
  }

  /** The purchase price list the linked vendor carries, tax included or not. */
  private PriceList vendorPriceList(boolean includesTax) {
    BusinessPartner partner = mock(BusinessPartner.class);
    PriceList list = mock(PriceList.class);
    when(list.isPriceIncludesTax()).thenReturn(includesTax);
    when(partner.getPurchasePricelist()).thenReturn(list);
    when(dal.get(BusinessPartner.class, "V1")).thenReturn(partner);
    return list;
  }

  @SuppressWarnings("unchecked")
  private void defaultPurchaseList(PriceList list) {
    BusinessPartner partner = mock(BusinessPartner.class);
    when(dal.get(BusinessPartner.class, "V1")).thenReturn(partner);
    OBCriteria<PriceList> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(PriceList.class)).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(list);
  }

  // ------------------------------------------------------------------ the verdict

  @Test
  @DisplayName("an invalid document is 422 not_valid through the status policy, before anything else")
  void anInvalidDocumentMovesTheRow() throws Exception {
    InvoiceInboxValidator.Result verdict = invalid("MISSING_FIELDS", "header.documentNo");
    verdict(verdict);

    assertSame(notValid, preflight(document(LINKED, PLAIN_LINE)));

    markReviewed.verify(() -> InvoiceInboxMarkReviewed.notValid(row, verdict));
    verify(dal, never()).get(eq(BusinessPartner.class), anyString());
    verify(lookups, never()).duplicates(anyString(), anyString());
  }

  @Test
  @DisplayName("an invalid document gets not_valid BEFORE price_list_tax_included, even on a tax-included list")
  void notValidBeatsThePriceList() throws Exception {
    vendorPriceList(true);
    verdict(invalid("MISSING_FIELDS", "line[0].product"));

    assertSame(notValid, preflight(document(LINKED, PLAIN_LINE)));
  }

  @Test
  @DisplayName("only the vendor address missing: vendor_address_missing, the row is left as it is")
  void onlyTheVendorAddressMissing() throws Exception {
    verdict(invalid("MISSING_FIELDS", "vendorAddress"));

    assertRefusal(preflight(document(NEW_VENDOR, "[]")), 422, "vendor_address_missing");

    markReviewed.verifyNoInteractions();
  }

  @Test
  @DisplayName("only the exchange rate missing: missing_exchange_rate with the two currencies, row untouched")
  void onlyTheExchangeRateMissing() throws Exception {
    verdict(invalid("MISSING_FIELDS", "exchangeRate"));
    currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG-1")).thenReturn("EUR-ID");
    live.when(() -> InvoiceInboxLiveChecks.documentCurrencyId(any(), any(), eq("ORG-1")))
        .thenReturn("USD-ID");
    Currency eur = mock(Currency.class);
    when(eur.getISOCode()).thenReturn("EUR");
    Currency usd = mock(Currency.class);
    when(usd.getISOCode()).thenReturn("USD");
    when(dal.get(Currency.class, "EUR-ID")).thenReturn(eur);
    when(dal.get(Currency.class, "USD-ID")).thenReturn(usd);

    NeoResponse response = preflight(document(LINKED, PLAIN_LINE));

    assertRefusal(response, 422, "missing_exchange_rate");
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("from USD to EUR"), response.getBody().toString());
    markReviewed.verifyNoInteractions();
  }

  @Test
  @DisplayName("both live codes: the vendor address is told first")
  void theVendorAddressWinsOverTheRate() throws Exception {
    verdict(invalid("MISSING_FIELDS", "vendorAddress", "exchangeRate"));

    assertRefusal(preflight(document(NEW_VENDOR, "[]")), 422, "vendor_address_missing");
  }

  @Test
  @DisplayName("a live code next to another gap, or under another error code, moves the row as not_valid")
  void aLiveCodeWithAnotherGapIsNotValid() throws Exception {
    verdict(invalid("MISSING_FIELDS", "vendorAddress", "header.documentNo"));
    assertSame(notValid, preflight(document(NEW_VENDOR, "[]")));

    verdict(invalid("REFERENCE_TOO_LONG", "vendorAddress"));
    assertSame(notValid, preflight(document(NEW_VENDOR, "[]")));

    verdict(invalid("FOREIGN_RECEIVER", "receiver"));
    assertSame(notValid, preflight(document(NEW_VENDOR, "[]")));
  }

  @Test
  @DisplayName("a valid document of a type without lookups is confirmed as valid, nothing else is judged")
  void withoutLookupsThereIsNothingElseToJudge() throws Exception {
    assertNull(InvoiceInboxConfirmChecks.preflight(row, document(LINKED, PLAIN_LINE),
        Optional.empty()));
  }

  @Test
  @DisplayName("a valid, clean document passes")
  void aCleanDocumentPasses() throws Exception {
    vendorPriceList(false);

    assertNull(preflight(document(LINKED, PLAIN_LINE)));
  }

  // ------------------------------------------------------------------ the references

  @Test
  @DisplayName("a linked vendor that no longer resolves for the tenant is vendor_address_missing")
  void aStaleVendor() throws Exception {
    when(lookups.vendorById("V1")).thenReturn(Optional.empty());

    assertRefusal(preflight(document(LINKED, PLAIN_LINE)), 422, "vendor_address_missing");

    markReviewed.verifyNoInteractions();
  }

  @Test
  @DisplayName("a stale product, category, unit or tax is 422 not_valid naming each line, the row untouched")
  void staleReferencesAreNamedByLine() throws Exception {
    when(lookups.productById("P-OLD")).thenReturn(Optional.empty());
    when(lookups.productCategoryById("C-OLD")).thenReturn(Optional.empty());
    when(lookups.taxById(eq("T-OLD"), any())).thenReturn(Optional.empty());
    String lines = "[{index:0,product:{state:'linked',id:'P-OLD'},tax:{id:'T21'}},"
        + "{index:1,product:{state:'new',new:{categoryId:'C-OLD',uomId:'U1'}},tax:{id:'T21'}},"
        + "{index:2,product:{state:'linked',id:'P1'},tax:{id:'T-OLD'}}]";

    NeoResponse response = preflight(document(NEW_VENDOR, lines));

    assertRefusal(response, 422, "not_valid");
    assertEquals("[\"line[0].product\",\"line[1].product\",\"line[2].tax\"]",
        response.getBody().getJSONObject("error").getJSONArray("missing").toString());
    markReviewed.verifyNoInteractions();
    verify(dal, never()).save(any());
  }

  @Test
  @DisplayName("a new product with a stale unit is stale; a line with no tax id asks nothing about the tax")
  void aStaleUnitAndNoTax() throws Exception {
    when(lookups.uomById("U-OLD")).thenReturn(Optional.empty());
    String lines = "[{index:3,product:{state:'new',new:{categoryId:'C1',uomId:'U-OLD'}}}]";

    NeoResponse response = preflight(document(NEW_VENDOR, lines));

    assertRefusal(response, 422, "not_valid");
    assertEquals("[\"line[3].product\"]", response.getBody().getJSONObject("error")
        .getJSONArray("missing").toString());
    verify(lookups, never()).taxById(anyString(), any());
  }

  // ------------------------------------------------------------------ the price list

  @Test
  @DisplayName("a linked vendor whose purchase price list includes tax is refused, nothing created, row untouched")
  void aTaxIncludedVendorList() throws Exception {
    vendorPriceList(true);

    assertRefusal(preflight(document(LINKED, PLAIN_LINE)), 422, "price_list_tax_included");

    markReviewed.verifyNoInteractions();
  }

  @Test
  @DisplayName("a vendor without a purchase list uses the default purchase list, both ways")
  void theDefaultPurchaseList() throws Exception {
    PriceList taxIncluded = mock(PriceList.class);
    when(taxIncluded.isPriceIncludesTax()).thenReturn(true);
    defaultPurchaseList(taxIncluded);
    assertRefusal(preflight(document(LINKED, PLAIN_LINE)), 422, "price_list_tax_included");

    PriceList net = mock(PriceList.class);
    when(net.isPriceIncludesTax()).thenReturn(false);
    defaultPurchaseList(net);
    assertNull(preflight(document(LINKED, PLAIN_LINE)));

    defaultPurchaseList(null);
    assertNull(preflight(document(LINKED, PLAIN_LINE)));
  }

  @Test
  @DisplayName("a new vendor has no price list yet: never asked, never refused")
  void aNewVendorIsNotAffected() throws Exception {
    assertNull(preflight(document(NEW_VENDOR, "[]")));

    verify(dal, never()).get(eq(BusinessPartner.class), anyString());
    verify(dal, never()).createCriteria(PriceList.class);
  }

  // ------------------------------------------------------------------ duplicates and address

  @Test
  @DisplayName("an existing invoice of the same vendor and number is 422 duplicate_invoice with that invoice")
  void aDuplicate() throws Exception {
    vendorPriceList(false);
    when(lookups.duplicates("V1", "F-1")).thenReturn(new Duplicates(true,
        List.of(new Duplicate("INV-9", "F-1"), new Duplicate("INV-10", "F-1"))));

    NeoResponse response = preflight(document(LINKED, PLAIN_LINE));

    assertRefusal(response, 422, "duplicate_invoice");
    JSONObject invoice = response.getBody().getJSONObject("error").getJSONObject("invoice");
    assertEquals("INV-9", invoice.getString("id"));
    assertEquals("F-1", invoice.getString("documentNo"));
  }

  @Test
  @DisplayName("an unknown duplicate check, or a new vendor, or a blank number, never blocks")
  void theDuplicateCheckOnlyBlocksWhenKnown() throws Exception {
    vendorPriceList(false);
    when(lookups.duplicates(anyString(), anyString())).thenReturn(new Duplicates(false,
        List.of(new Duplicate("INV-9", "F-1"))));
    assertNull(preflight(document(LINKED, PLAIN_LINE)));

    org.mockito.Mockito.clearInvocations(lookups);
    assertNull(preflight(document(NEW_VENDOR, "[]")));
    verify(lookups, never()).duplicates(anyString(), anyString());

    JSONObject blankNumber = document(LINKED, PLAIN_LINE);
    blankNumber.getJSONObject("header").put("documentNo", "  ");
    when(lookups.duplicates(anyString(), anyString())).thenReturn(new Duplicates(true,
        List.of(new Duplicate("INV-9", "F-1"))));
    assertNull(preflight(blankNumber));
  }

  @Test
  @DisplayName("a linked vendor whose address disappeared since the review is vendor_address_missing")
  void theAddressOfALinkedVendorIsAskedAgain() throws Exception {
    vendorPriceList(false);
    when(lookups.vendorAddress("V1")).thenReturn(new Address(AddressState.MISSING, null));

    assertRefusal(preflight(document(LINKED, PLAIN_LINE)), 422, "vendor_address_missing");

    when(lookups.vendorAddress("V1")).thenReturn(new Address(AddressState.UNKNOWN, null));
    assertNull(preflight(document(LINKED, PLAIN_LINE)));
  }

  @Test
  @DisplayName("the duplicate is told before the vendor address")
  void duplicateBeforeAddress() throws Exception {
    vendorPriceList(false);
    when(lookups.vendorAddress("V1")).thenReturn(new Address(AddressState.MISSING, null));
    when(lookups.duplicates("V1", "F-1")).thenReturn(new Duplicates(true,
        List.of(new Duplicate("INV-9", "F-1"))));

    assertRefusal(preflight(document(LINKED, PLAIN_LINE)), 422, "duplicate_invoice");
  }
}
