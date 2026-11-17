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

package com.etendoerp.go.schemaforge.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Candidate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Tax;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;

/**
 * The document the inbox worker stores as {@code CURRENT_DATA}: header (vendor, number, date, total,
 * duplicate, receiver), lines and notices, built from a sanitized extraction through the lookups
 * alone. A document addressed to another company is not matched at all.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.InboxMatcher
 * @covers com.etendoerp.go.schemaforge.ocr.InboxDocument
 */
class InboxMatcherTest {

  private static final String HEADER = "header";
  private static final String VENDOR = "vendor";
  private static final String STATE = "state";
  private static final String RECEIVER = "receiver";
  private static final String DUPLICATE = "duplicate";
  private static final String TAX_ID_RAW = "tax_id_raw";
  private static final String DOCUMENT_NO = "document_no";
  private static final String INVOICE_DATE = "invoice_date";
  private static final String TOTAL = "total_amount";
  private static final String TOTAL_AMOUNT = "totalAmount";
  private static final String LINE_ITEMS = "line_items";
  private static final String VENDOR_TAX_ID = "B81639719";
  private static final String ORG_TAX_ID = "A28000727";

  private InboxFakeLookups lookups;

  @BeforeEach
  void setUp() {
    lookups = new InboxFakeLookups();
    lookups.byTaxId.put(VENDOR_TAX_ID, List.of(new Party("V1", "Acme S.L.")));
    lookups.products = List.of(List.of(new Candidate("P1", "Widget", "99")));
    lookups.taxByRate.put("21", new TaxAnswer(true, new Tax("T21", "IVA 21%", new BigDecimal("21")),
        List.of()));
  }

  private static JSONObject line(String description, Object quantity, Object price, Object rate)
      throws JSONException {
    return new JSONObject().put("description", description).put("quantity", quantity)
        .put("unit_price", price).put("tax_rate", rate).put("tax_label", "IVA");
  }

  /** A readable invoice to the organization, from a vendor the lookups know. */
  private static JSONObject extraction() throws JSONException {
    return new JSONObject().put("vendor_name", "Acme S.L.").put("tax_id", "")
        .put(DOCUMENT_NO, "F-1").put(INVOICE_DATE, "30/10/2026").put(TOTAL, 121.0)
        .put("vendor_address", "Calle 1").put("vendor_postal_code", "28001")
        .put("vendor_city", "Madrid").put("vendor_country", "ES")
        .put("vendor_email", "a@acme.es").put("vendor_phone", "600")
        .put("issuer", new JSONObject().put("name", "Acme S.L.").put(TAX_ID_RAW, VENDOR_TAX_ID))
        .put(RECEIVER, new JSONObject().put(TAX_ID_RAW, ORG_TAX_ID))
        .put(LINE_ITEMS, new JSONArray().put(line("Widget", 2, 50, 21)));
  }

  private JSONObject build(JSONObject extraction) throws Exception {
    return new InboxMatcher(lookups).build(extraction);
  }

  private static JSONObject header(JSONObject document) throws JSONException {
    return document.getJSONObject(HEADER);
  }

  private static List<String> notices(JSONObject document) throws JSONException {
    JSONArray array = document.getJSONArray("notices");
    List<String> out = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      out.add(array.getString(i));
    }
    return out;
  }

  // ---------------------------------------------------------------- the happy path

  @Test
  @DisplayName("a readable invoice becomes version 1 of the document: linked vendor, header, one line, no notices")
  void aReadableInvoiceIsBuilt() throws Exception {
    JSONObject document = build(extraction());

    assertEquals(1, document.getInt("version"));
    assertTrue(notices(document).isEmpty());
    assertFalse(document.has("validation"), "the validator writes that block, not the matcher");
    JSONObject header = header(document);
    assertEquals("linked", header.getJSONObject(VENDOR).getString(STATE));
    assertEquals("V1", header.getJSONObject(VENDOR).getString("id"));
    assertEquals("PRESENT", header.getJSONObject(VENDOR).getString("addressState"));
    assertEquals("LOC1", header.getJSONObject(VENDOR).getString("locationId"));
    assertEquals("F-1", header.getString("documentNo"));
    assertEquals("2026-10-30", header.getString("invoiceDate"));
    assertEquals("30/10/2026", header.getString("invoiceDateRaw"));
    assertEquals(121.0, header.getDouble(TOTAL_AMOUNT), 0.0);
    assertEquals("printed", header.getString("totalSource"));
    assertEquals("none", header.getJSONObject(DUPLICATE).getString(STATE));
    assertEquals("match", header.getJSONObject(RECEIVER).getString("check"));
    assertEquals(ORG_TAX_ID, header.getJSONObject(RECEIVER).getString("orgTaxId"));
    JSONArray lines = document.getJSONArray("lines");
    assertEquals(1, lines.length());
    assertEquals("linked", lines.getJSONObject(0).getJSONObject("product").getString(STATE));
    assertEquals("resolved", lines.getJSONObject(0).getJSONObject("tax").getString(STATE));
  }

  @Test
  @DisplayName("the extracted vendor keeps everything read: name, tax id, address, contact")
  void theExtractedVendorKeepsWhatWasRead() throws Exception {
    JSONObject extracted = header(build(extraction())).getJSONObject(VENDOR)
        .getJSONObject("extracted");

    assertEquals("Acme S.L.", extracted.getString("name"));
    assertEquals(VENDOR_TAX_ID, extracted.getString("taxId"));
    assertEquals("Calle 1", extracted.getString("address"));
    assertEquals("28001", extracted.getString("postalCode"));
    assertEquals("Madrid", extracted.getString("city"));
    assertEquals("ES", extracted.getString("country"));
    assertEquals("a@acme.es", extracted.getString("email"));
    assertEquals("600", extracted.getString("phone"));
  }

  @Test
  @DisplayName("the vendor name falls back to the issuer's, the tax id to the legacy field")
  void vendorFieldsFallBack() throws Exception {
    JSONObject extraction = extraction();
    extraction.put("vendor_name", "").put("issuer", new JSONObject().put("name", "Issuer Name")
        .put(TAX_ID_RAW, "")).put("tax_id", "Q1234567J");

    JSONObject extracted = header(build(extraction)).getJSONObject(VENDOR)
        .getJSONObject("extracted");

    assertEquals("Issuer Name", extracted.getString("name"));
    assertEquals("Q1234567J", extracted.getString("taxId"));
  }

  // ---------------------------------------------------------------- receiver

  @Test
  @DisplayName("a document addressed to another company is not matched: nothing is looked up, only what was read is kept")
  void aForeignReceiverIsNotMatched() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(RECEIVER, new JSONObject().put(TAX_ID_RAW, "X9999999A"));

    JSONObject document = build(extraction);

    JSONObject header = header(document);
    assertEquals("mismatch", header.getJSONObject(RECEIVER).getString("check"));
    assertEquals("none", header.getJSONObject(VENDOR).getString(STATE));
    assertEquals(VENDOR_TAX_ID, header.getJSONObject(VENDOR).getJSONObject("extracted")
        .getString("taxId"));
    assertEquals(0, document.getJSONArray("lines").length());
    assertTrue(lookups.taxIdSearches.isEmpty() && lookups.nameSearches.isEmpty());
    assertTrue(lookups.addressAsks.isEmpty() && lookups.duplicateAsks.isEmpty());
    assertTrue(lookups.productAsks.isEmpty() && lookups.taxAsks.isEmpty());
    assertEquals("F-1", header.getString("documentNo"), "the number read is still kept");
    assertEquals("none", header.getJSONObject(DUPLICATE).getString(STATE));
  }

  @Test
  @DisplayName("a receiver that prints no tax id is a notice, not an error, and the organization is not asked")
  void anAbsentReceiverIsANotice() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(RECEIVER, new JSONObject().put(TAX_ID_RAW, ""));

    JSONObject document = build(extraction);

    assertEquals(List.of("receiver_tax_id_absent"), notices(document));
    assertEquals("absent", header(document).getJSONObject(RECEIVER).getString("check"));
    assertEquals(0, lookups.orgTaxIdAsks);
    assertEquals("linked", header(document).getJSONObject(VENDOR).getString(STATE));
  }

  @Test
  @DisplayName("an organization without a tax id is a notice: the receiver cannot be checked")
  void anOrganizationWithoutTaxIdIsANotice() throws Exception {
    lookups.orgTaxId = "";

    JSONObject document = build(extraction());

    assertEquals(List.of("org_tax_id_missing"), notices(document));
    assertEquals("no-org-tax-id", header(document).getJSONObject(RECEIVER).getString("check"));
  }

  @Test
  @DisplayName("a receiver id equal to the issuer's (the two blocks confused) concludes nothing and is not foreign")
  void aReceiverSameAsIssuerIsNotForeign() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(RECEIVER, new JSONObject().put(TAX_ID_RAW, "ES" + VENDOR_TAX_ID));

    JSONObject document = build(extraction);

    assertEquals("same-as-issuer", header(document).getJSONObject(RECEIVER).getString("check"));
    assertEquals("linked", header(document).getJSONObject(VENDOR).getString(STATE));
    assertTrue(notices(document).isEmpty());
  }

  // ---------------------------------------------------------------- vendor address

  @Test
  @DisplayName("a linked vendor without an address is a notice, and every line waits for it")
  void aMissingAddressLeavesTaxesPending() throws Exception {
    lookups.address = new Address(AddressState.MISSING, null);

    JSONObject document = build(extraction());

    assertEquals(List.of("vendor_address_missing"), notices(document));
    JSONObject vendor = header(document).getJSONObject(VENDOR);
    assertEquals("MISSING", vendor.getString("addressState"));
    assertTrue(vendor.isNull("locationId"));
    assertEquals("pending_vendor", document.getJSONArray("lines").getJSONObject(0)
        .getJSONObject("tax").getString(STATE));
    assertTrue(lookups.taxAsks.isEmpty());
  }

  @Test
  @DisplayName("an address lookup that failed is UNKNOWN without a notice")
  void anUnknownAddressIsNotANotice() throws Exception {
    lookups.address = new Address(AddressState.UNKNOWN, null);

    JSONObject document = build(extraction());

    assertTrue(notices(document).isEmpty());
    assertEquals("UNKNOWN", header(document).getJSONObject(VENDOR).getString("addressState"));
  }

  @Test
  @DisplayName("a vendor that is not linked has no address, no duplicate check and pending taxes")
  void anUnlinkedVendorAsksNothingAboutAddressOrDuplicates() throws Exception {
    lookups.byTaxId.clear();
    lookups.byName = List.of();

    JSONObject document = build(extraction());

    JSONObject vendor = header(document).getJSONObject(VENDOR);
    assertEquals("new", vendor.getString(STATE));
    assertEquals("UNKNOWN", vendor.getString("addressState"));
    assertTrue(vendor.isNull("locationId"));
    assertTrue(lookups.addressAsks.isEmpty());
    assertTrue(lookups.duplicateAsks.isEmpty());
    assertEquals("none", header(document).getJSONObject(DUPLICATE).getString(STATE));
    assertEquals("pending_vendor", document.getJSONArray("lines").getJSONObject(0)
        .getJSONObject("tax").getString(STATE));
  }

  // ---------------------------------------------------------------- duplicates

  @Test
  @DisplayName("an existing invoice of the same vendor and number is found and listed")
  void aDuplicateIsFound() throws Exception {
    lookups.duplicates = new Duplicates(true, List.of(new Duplicate("INV1", "1000007"),
        new Duplicate("INV2", null)));

    JSONObject duplicate = header(build(extraction())).getJSONObject(DUPLICATE);

    assertEquals("found", duplicate.getString(STATE));
    assertEquals(List.of("V1|F-1"), lookups.duplicateAsks);
    JSONArray invoices = duplicate.getJSONArray("invoices");
    assertEquals("INV1", invoices.getJSONObject(0).getString("id"));
    assertEquals("1000007", invoices.getJSONObject(0).getString("documentNo"));
    assertEquals("", invoices.getJSONObject(1).getString("documentNo"));
  }

  @Test
  @DisplayName("a duplicate lookup that could not be made is unknown, with a notice")
  void anUnknownDuplicateCheckIsANotice() throws Exception {
    lookups.duplicates = new Duplicates(false, List.of());

    JSONObject document = build(extraction());

    assertEquals("unknown", header(document).getJSONObject(DUPLICATE).getString(STATE));
    assertEquals(List.of("duplicate_check_unknown"), notices(document));
  }

  @Test
  @DisplayName("without a document number no duplicate is asked")
  void noNumberNoDuplicateCheck() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(DOCUMENT_NO, "  ");

    JSONObject document = build(extraction);

    assertTrue(lookups.duplicateAsks.isEmpty());
    assertEquals("none", header(document).getJSONObject(DUPLICATE).getString(STATE));
  }

  // ---------------------------------------------------------------- total

  @Test
  @DisplayName("the printed total is used when it is above zero")
  void thePrintedTotalWins() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(TOTAL, 999.99);

    JSONObject header = header(build(extraction));

    assertEquals(999.99, header.getDouble(TOTAL_AMOUNT), 0.0);
    assertEquals("printed", header.getString("totalSource"));
  }

  @Test
  @DisplayName("without a printed total (null or zero) it is computed from the lines with their tax")
  void theTotalFallsBackToTheLines() throws Exception {
    for (Object printed : new Object[] { JSONObject.NULL, 0 }) {
      JSONObject extraction = extraction();
      extraction.put(TOTAL, printed);
      extraction.put(LINE_ITEMS, new JSONArray().put(line("Widget", 2, 50, 21))
          .put(line("Other", 1, 10, JSONObject.NULL)).put(line("No price", 3, JSONObject.NULL, 21)));
      lookups.products = List.of(List.of(), List.of(), List.of());

      JSONObject header = header(build(extraction));

      assertEquals(131.0, header.getDouble(TOTAL_AMOUNT), 0.0, String.valueOf(printed));
      assertEquals("lines", header.getString("totalSource"));
    }
  }

  @Test
  @DisplayName("ETP-6050: a printed NEGATIVE total is honoured (a credit document), whatever the lines add up to")
  void aPrintedNegativeTotalIsKept() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(TOTAL, -4.0);

    JSONObject header = header(build(extraction));

    assertEquals(-4.0, header.getDouble(TOTAL_AMOUNT), 0.0);
    assertEquals("printed", header.getString("totalSource"));
  }

  @Test
  @DisplayName("the computed total is rounded half up to cents")
  void theComputedTotalIsRounded() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(TOTAL, JSONObject.NULL);
    extraction.put(LINE_ITEMS, new JSONArray().put(line("Widget", 1, 10.005, JSONObject.NULL)));

    assertEquals(10.01, header(build(extraction)).getDouble(TOTAL_AMOUNT), 0.0);
  }

  @Test
  @DisplayName("ETP-6050: the computed total applies the line discount (percent read, amount converted)")
  void theTotalAppliesTheLineDiscount() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(TOTAL, JSONObject.NULL);
    extraction.put(LINE_ITEMS, new JSONArray().put(line("Widget", 2, 50, 21)
        .put("discount_percent", 10).put("discount_amount", JSONObject.NULL)));

    JSONObject document = build(extraction);

    // 2 x 50 less 10 % = 90.00, tax 21 % = 18.90
    assertEquals(108.9, header(document).getDouble(TOTAL_AMOUNT), 0.0);
    assertEquals(10.0, document.getJSONArray("lines").getJSONObject(0).getDouble("discount"), 0.0);

    JSONObject byAmount = extraction();
    byAmount.put(TOTAL, JSONObject.NULL);
    byAmount.put(LINE_ITEMS, new JSONArray().put(line("Widget", 2, 50, 21)
        .put("discount_percent", JSONObject.NULL).put("discount_amount", 10)));
    assertEquals(108.9, header(build(byAmount)).getDouble(TOTAL_AMOUNT), 0.0);
  }

  @Test
  @DisplayName("ETP-6050: a printed total is kept as printed whatever the discount")
  void aPrintedTotalIgnoresTheDiscount() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(LINE_ITEMS, new JSONArray().put(line("Widget", 2, 50, 21)
        .put("discount_percent", 10)));

    JSONObject header = header(build(extraction));

    assertEquals(121.0, header.getDouble(TOTAL_AMOUNT), 0.0);
    assertEquals("printed", header.getString("totalSource"));
  }

  @Test
  @DisplayName("ETP-6050: the total rounds twice, like GO: the price after the discount to six decimals, then the line to cents")
  void theTotalRoundsInTwoSteps() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(TOTAL, JSONObject.NULL);
    extraction.put(LINE_ITEMS, new JSONArray().put(line("Widget", 48324, 0.2771, 0)
        .put("discount_percent", 77.1721)));
    lookups.taxByRate.put("0", new TaxAnswer(true, new Tax("T0", "IVA 0%", BigDecimal.ZERO),
        List.of()));

    // rounded once the product would be 3056.79
    assertEquals(3056.78, header(build(extraction)).getDouble(TOTAL_AMOUNT), 0.0);
  }

  @Test
  @DisplayName("ETP-6050: the tax is rounded per rate on the sum of the nets, not per line")
  void theTaxIsRoundedPerRate() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(TOTAL, JSONObject.NULL);
    extraction.put(LINE_ITEMS, new JSONArray().put(line("A", 1, 0.05, 10)).put(line("B", 1, 0.05, 10)));
    lookups.products = List.of(List.of(), List.of());

    // base 0.10, tax 0.01 (per line 0.005 would round to 0.01 twice = 0.02)
    assertEquals(0.11, header(build(extraction)).getDouble(TOTAL_AMOUNT), 0.0);
  }

  @Test
  @DisplayName("no printed total and nothing to add up leaves the total null, sourced from the lines")
  void noTotalAtAll() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(TOTAL, JSONObject.NULL);
    extraction.remove(LINE_ITEMS);

    JSONObject document = build(extraction);

    assertTrue(header(document).isNull(TOTAL_AMOUNT));
    assertEquals("lines", header(document).getString("totalSource"));
    assertEquals(0, document.getJSONArray("lines").length());
    assertEquals("linked", header(document).getJSONObject(VENDOR).getString(STATE),
        "the vendor is still matched without lines");
  }

  // ---------------------------------------------------------------- currency, scale, rounding mode

  private JSONObject withoutPrintedTotal(JSONObject extraction, JSONObject... lines)
      throws Exception {
    extraction.put(TOTAL, JSONObject.NULL);
    JSONArray array = new JSONArray();
    for (JSONObject line : lines) {
      array.put(line);
    }
    extraction.put(LINE_ITEMS, array);
    return extraction;
  }

  @Test
  @DisplayName("ETP-6050: the header stores the currency of the linked vendor and the computed total follows its decimals (3)")
  void theComputedTotalFollowsTheScaleOfTheVendorCurrency() throws Exception {
    lookups.currency = java.util.Optional.of(new OcrLookups.DocCurrency("KWD", "KWD", "K", 3));

    JSONObject header = header(build(withoutPrintedTotal(extraction(),
        line("Widget", 1, 0.12345, 21))));

    assertEquals("KWD", header.getString("currencyId"));
    assertEquals(List.of("V1"), lookups.currencyAsks, "asked with the linked vendor");
    // net 0.123, tax 21 % = 0.026
    assertEquals(0.149, header.getDouble(TOTAL_AMOUNT), 0.0);
  }

  @Test
  @DisplayName("ETP-6050: a currency without decimals rounds the computed total to whole units")
  void aCurrencyWithoutDecimalsRoundsTheComputedTotalToUnits() throws Exception {
    lookups.currency = java.util.Optional.of(new OcrLookups.DocCurrency("JPY", "JPY", "Y", 0));

    JSONObject header = header(build(withoutPrintedTotal(extraction(),
        line("Widget", 3, 0.5, 21))));

    // net 1.5 -> 2, tax round0(0.42) = 0
    assertEquals(2.0, header.getDouble(TOTAL_AMOUNT), 0.0);
    assertEquals("JPY", header.getString("currencyId"));
  }

  @Test
  @DisplayName("ETP-6050: a currency that cannot be established leaves no currencyId and rounds to cents")
  void withoutACurrencyTheTotalIsInCentsAndNoCurrencyIsStored() throws Exception {
    JSONObject header = header(build(withoutPrintedTotal(extraction(),
        line("Widget", 1, 10.005, JSONObject.NULL))));

    assertFalse(header.has("currencyId"));
    assertEquals(10.01, header.getDouble(TOTAL_AMOUNT), 0.0);
  }

  @Test
  @DisplayName("ETP-6050: a vendor that is not linked asks the document currency with no vendor")
  void anUnlinkedVendorAsksTheCurrencyWithoutAVendor() throws Exception {
    lookups.byTaxId.clear();
    lookups.byName = List.of();

    build(extraction());

    assertEquals(1, lookups.currencyAsks.size());
    assertNull(lookups.currencyAsks.get(0));
  }

  @Test
  @DisplayName("ETP-6050: a printed total is kept at its own cents, at the default scale")
  void aPrintedTotalKeepsItsCentsAtTheDefaultScale() throws Exception {
    JSONObject extraction = extraction();
    extraction.put(TOTAL, 121.55);

    JSONObject header = header(build(extraction));

    assertEquals(121.55, header.getDouble(TOTAL_AMOUNT), 0.0);
    assertEquals("printed", header.getString("totalSource"));
  }

  @Test
  @DisplayName("ETP-6050: a printed total is never touched by the currency (its decimals are the document's, not the currency's)")
  void aPrintedTotalIsNotRoundedToTheScaleOfTheCurrency() throws Exception {
    lookups.currency = java.util.Optional.of(new OcrLookups.DocCurrency("JPY", "JPY", "Y", 0));
    JSONObject extraction = extraction();
    extraction.put(TOTAL, 121.55);

    JSONObject header = header(build(extraction));

    assertEquals(121.55, header.getDouble(TOTAL_AMOUNT), 0.0);
  }

  @Test
  @DisplayName("ETP-6050: the lines carry the rounding mode of their tax and the computed total follows it")
  void theLinesAreStampedAndTheTotalFollowsALineBasedTax() throws Exception {
    lookups.taxModes.put("T21", "L");
    // Two lines of net 0.13 (0.125 rounded half up) at 21 %. Per line the tax is 0.0273, which
    // rounds to 0.03, so the lines add up to 0.06. On the sum, 0.26 at 21 % is 0.0546, which rounds
    // to 0.05.
    lookups.products = List.of(List.of(), List.of());

    JSONObject document = build(withoutPrintedTotal(extraction(),
        line("A", 1, 0.125, 21), line("B", 1, 0.125, 21)));

    JSONArray lines = document.getJSONArray("lines");
    assertEquals("L", lines.getJSONObject(0).getJSONObject("tax").getString("docTaxAmount"));
    assertEquals("L", lines.getJSONObject(1).getJSONObject("tax").getString("docTaxAmount"));
    assertEquals(0.32, header(document).getDouble(TOTAL_AMOUNT), 0.0);
    assertEquals(1, lookups.taxModeAsks.size(), "one lookup for the whole document");
  }

  @Test
  @DisplayName("ETP-6050: a tax with no known mode is document based and a pending tax is not stamped")
  void anUnknownModeIsDocumentBasedAndAPendingTaxIsNotStamped() throws Exception {
    lookups.products = List.of(List.of(), List.of());
    lookups.taxByRate.put("7", new TaxAnswer(true, null, List.of()));

    JSONObject document = build(withoutPrintedTotal(extraction(),
        line("A", 1, 0.125, 21), line("B", 1, 10, 7)));

    JSONArray lines = document.getJSONArray("lines");
    assertEquals("D", lines.getJSONObject(0).getJSONObject("tax").getString("docTaxAmount"));
    assertFalse(lines.getJSONObject(1).getJSONObject("tax").has("docTaxAmount"));
  }

  @Test
  @DisplayName("ETP-6050: negative quantities and prices are kept as read and add up as a credit")
  void negativeQuantitiesAndPricesAreKeptAndAddedUp() throws Exception {
    lookups.products = List.of(List.of(), List.of());

    JSONObject document = build(withoutPrintedTotal(extraction(),
        line("Credit", -2, 50, 21), line("Refund", 1, -10, 21)));

    JSONArray lines = document.getJSONArray("lines");
    assertEquals(-2.0, lines.getJSONObject(0).getDouble("quantity"), 0.0);
    assertEquals(-10.0, lines.getJSONObject(1).getDouble("unitPrice"), 0.0);
    // net -110.00, tax -23.10
    assertEquals(-133.1, header(document).getDouble(TOTAL_AMOUNT), 0.0);
    assertEquals("lines", header(document).getString("totalSource"));
  }

  @Test
  @DisplayName("ETP-6050: a zero quantity is a line of zero, so the total is 0 and not null")
  void aZeroQuantityLineGivesATotalOfZero() throws Exception {
    JSONObject header = header(build(withoutPrintedTotal(extraction(),
        line("Free", 0, 50, 21))));

    assertEquals(0.0, header.getDouble(TOTAL_AMOUNT), 0.0);
  }

  // ---------------------------------------------------------------- date

  @Test
  @DisplayName("dates are read as ISO, d/M/yyyy and d-M-yy; the raw text is always kept")
  void datesAreParsed() throws Exception {
    String[][] cases = { { "2026-10-30", "2026-10-30" }, { "30/10/2026", "2026-10-30" },
        { "5-3-26", "2026-03-05" }, { "  30/10/2026 ", "2026-10-30" } };
    for (String[] c : cases) {
      JSONObject extraction = extraction();
      extraction.put(INVOICE_DATE, c[0]);

      JSONObject header = header(build(extraction));

      assertEquals(c[1], header.getString("invoiceDate"), c[0]);
      assertEquals(c[0].trim(), header.getString("invoiceDateRaw"));
    }
  }

  @Test
  @DisplayName("a date that does not exist or is not a date is null, never a guess")
  void unparseableDatesAreNull() throws Exception {
    for (Object raw : new Object[] { "31/02/2026", "next Tuesday", "", "2026-13-45", JSONObject.NULL }) {
      JSONObject extraction = extraction();
      extraction.put(INVOICE_DATE, raw);

      JSONObject header = header(build(extraction));

      assertTrue(header.isNull("invoiceDate"), String.valueOf(raw));
    }
  }

  @Test
  @DisplayName("the invoice date is handed to the tax lookup, and null when it could not be read")
  void theDateReachesTheTaxLookup() throws Exception {
    build(extraction());
    JSONObject undated = extraction();
    undated.put(INVOICE_DATE, "someday");
    build(undated);

    assertEquals("2026-10-30", lookups.taxDates.get(0));
    assertNull(lookups.taxDates.get(1));
  }
}
