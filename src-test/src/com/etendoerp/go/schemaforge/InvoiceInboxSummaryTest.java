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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * The summary columns of an inbox row follow its document: the extracted name and tax id (capped to
 * their columns), the vendor link (only when linked), the number (empty when it does not fit), the
 * date, the total and the first duplicate. It never touches the status, the error code or the flags.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxSummary
 */
class InvoiceInboxSummaryTest {

  private static final String VENDOR = "vendor";
  private static final String HEADER = "header";
  private static final String STATE = "state";

  private MockedStatic<OBDal> dalStatic;
  private OBDal dal;
  private BusinessPartner partner;
  private Invoice invoice;
  private IncomingDocument row;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    partner = mock(BusinessPartner.class);
    invoice = mock(Invoice.class);
    when(dal.get(BusinessPartner.class, "V1")).thenReturn(partner);
    when(dal.get(Invoice.class, "INV1")).thenReturn(invoice);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    row = mock(IncomingDocument.class);
  }

  @AfterEach
  void tearDown() {
    dalStatic.close();
  }

  /** A document with a linked vendor, a date, a total and a number, the usual output of the worker. */
  private static JSONObject document() throws JSONException {
    return new JSONObject().put(HEADER, new JSONObject()
        .put(VENDOR, new JSONObject().put(STATE, "linked").put("id", "V1").put("extracted",
            new JSONObject().put("name", "  Acme S.L. ").put("taxId", "ESB81639719")))
        .put("documentNo", "F-2026/001").put("invoiceDate", "2026-10-30").put("totalAmount", 121.5)
        .put("duplicate", new JSONObject().put(STATE, "none").put("invoices", new JSONArray())));
  }

  private static JSONObject header(JSONObject document) throws JSONException {
    return document.getJSONObject(HEADER);
  }

  @Test
  @DisplayName("copies the header into the columns: name, tax id, vendor, number, date and total")
  void copiesTheHeader() throws Exception {
    InvoiceInboxSummary.syncFrom(document(), row);

    verify(row).setExtractedName("Acme S.L.");
    verify(row).setExtractedTaxID("ESB81639719");
    verify(row).setBusinessPartner(partner);
    verify(row).setOrderReference("F-2026/001");
    verify(row).setGrandTotalAmount(BigDecimal.valueOf(121.5));
    ArgumentCaptor<Date> date = ArgumentCaptor.forClass(Date.class);
    verify(row).setInvoiceDate(date.capture());
    assertEquals(LocalDate.of(2026, 10, 30), date.getValue().toInstant()
        .atZone(ZoneId.systemDefault()).toLocalDate());
    verify(row).setDuplicateOfInvoice(null);
  }

  @Test
  @DisplayName("the extracted tax id is cut to its 30-character column, the name to 255")
  void longTextsAreTruncated() throws Exception {
    JSONObject document = document();
    header(document).getJSONObject(VENDOR).put("extracted", new JSONObject()
        .put("name", "N".repeat(300)).put("taxId", "T".repeat(45)));

    InvoiceInboxSummary.syncFrom(document, row);

    verify(row).setExtractedTaxID("T".repeat(30));
    verify(row).setExtractedName("N".repeat(255));
  }

  @Test
  @DisplayName("an empty name or tax id leaves the column empty, not blank")
  void emptyTextsAreNull() throws Exception {
    JSONObject document = document();
    header(document).getJSONObject(VENDOR).put("extracted", new JSONObject()
        .put("name", "   ").put("taxId", JSONObject.NULL));

    InvoiceInboxSummary.syncFrom(document, row);

    verify(row).setExtractedName(null);
    verify(row).setExtractedTaxID(null);
  }

  @Test
  @DisplayName("the vendor column is set only for a linked vendor: ambiguous, new and none leave it empty")
  void onlyALinkedVendorIsStored() throws Exception {
    for (String state : new String[] { "ambiguous", "new", "none" }) {
      IncomingDocument other = mock(IncomingDocument.class);
      JSONObject document = document();
      header(document).getJSONObject(VENDOR).put(STATE, state);

      InvoiceInboxSummary.syncFrom(document, other);

      verify(other).setBusinessPartner(null);
    }
    IncomingDocument noVendor = mock(IncomingDocument.class);
    JSONObject document = document();
    header(document).remove(VENDOR);

    InvoiceInboxSummary.syncFrom(document, noVendor);

    verify(noVendor).setBusinessPartner(null);
    verify(noVendor).setExtractedName(null);
  }

  @Test
  @DisplayName("a number of up to 20 characters is stored; a longer one leaves the column empty")
  void theReferenceMustFitItsColumn() throws Exception {
    JSONObject fits = document();
    header(fits).put("documentNo", "A".repeat(20));
    InvoiceInboxSummary.syncFrom(fits, row);
    verify(row).setOrderReference("A".repeat(20));

    IncomingDocument tooLong = mock(IncomingDocument.class);
    JSONObject document = document();
    header(document).put("documentNo", "A".repeat(21));
    InvoiceInboxSummary.syncFrom(document, tooLong);
    verify(tooLong).setOrderReference(null);

    IncomingDocument blank = mock(IncomingDocument.class);
    JSONObject empty = document();
    header(empty).put("documentNo", "  ");
    InvoiceInboxSummary.syncFrom(empty, blank);
    verify(blank).setOrderReference(null);
  }

  @Test
  @DisplayName("a missing or malformed date, or a missing total, leaves the column empty")
  void missingDateAndTotalAreNull() throws Exception {
    for (Object date : new Object[] { JSONObject.NULL, "31/02/2026", "" }) {
      IncomingDocument other = mock(IncomingDocument.class);
      JSONObject document = document();
      header(document).put("invoiceDate", date).put("totalAmount", JSONObject.NULL);

      InvoiceInboxSummary.syncFrom(document, other);

      verify(other).setInvoiceDate(null);
      verify(other).setGrandTotalAmount(null);
    }
    IncomingDocument absent = mock(IncomingDocument.class);
    JSONObject document = document();
    header(document).remove("invoiceDate");
    header(document).remove("totalAmount");

    InvoiceInboxSummary.syncFrom(document, absent);

    verify(absent).setInvoiceDate(null);
    verify(absent).setGrandTotalAmount(null);
  }

  @Test
  @DisplayName("the duplicate column holds the first duplicate invoice")
  void theFirstDuplicateIsStored() throws Exception {
    JSONObject document = document();
    header(document).put("duplicate", new JSONObject().put(STATE, "found").put("invoices",
        new JSONArray().put(new JSONObject().put("id", "INV1")).put(new JSONObject().put("id", "INV2"))));

    InvoiceInboxSummary.syncFrom(document, row);

    verify(row).setDuplicateOfInvoice(invoice);
    verify(dal, never()).get(eq(Invoice.class), eq("INV2"));
  }

  @Test
  @DisplayName("a document without a header leaves the row exactly as it was")
  void noHeaderTouchesNothing() throws Exception {
    InvoiceInboxSummary.syncFrom(new JSONObject(), row);

    verifyNoInteractions(row);
  }

  @Test
  @DisplayName("syncing never writes the status, the error code or the adjusted and reviewed flags")
  void onlyTheSummaryColumnsAreWritten() throws Exception {
    InvoiceInboxSummary.syncFrom(document(), row);

    verify(row, never()).setStatus(any());
    verify(row, never()).setErrorCode(any());
    verify(row, never()).setAdjusted(any());
    verify(row, never()).setReviewed(any());
    verify(row, never()).setCurrentData(any());
  }

  // ------------------------------------------------------------------ the totals warning

  private static Currency currency(long precision) {
    Currency currency = mock(Currency.class);
    when(currency.getStandardPrecision()).thenReturn(precision);
    return currency;
  }

  /** One line of 100 at 21% (lines total 121) with the given total source and printed total. */
  private static JSONObject printed(String source, Object total) throws JSONException {
    JSONObject header = new JSONObject().put("totalSource", source);
    if (total != null) {
      header.put("totalAmount", total);
    }
    return new JSONObject().put(HEADER, header).put("lines", new JSONArray().put(
        new JSONObject().put("quantity", 1).put("unitPrice", 100).put("taxRate", 21)));
  }

  @Test
  @DisplayName("a printed total equal to the lines total, or one cent away, does not warn")
  void aPrintedTotalWithinOneMinimalUnitDoesNotWarn() throws Exception {
    assertFalse(InvoiceInboxSummary.totalMismatch(printed("printed", 121), null));
    assertFalse(InvoiceInboxSummary.totalMismatch(printed("printed", 121.01), null));
    assertFalse(InvoiceInboxSummary.totalMismatch(printed("printed", 120.99), null));
  }

  @Test
  @DisplayName("a printed total two cents or more away warns, a large gap too")
  void aPrintedTotalBeyondOneMinimalUnitWarns() throws Exception {
    assertTrue(InvoiceInboxSummary.totalMismatch(printed("printed", 121.02), null));
    assertTrue(InvoiceInboxSummary.totalMismatch(printed("printed", 119.98), null));
    assertTrue(InvoiceInboxSummary.totalMismatch(printed("printed", 5000), null));
  }

  @Test
  @DisplayName("the printed total is rounded HALF_UP at the currency scale before comparing")
  void thePrintedTotalIsRoundedAtTheScaleBeforeComparing() throws Exception {
    assertFalse(InvoiceInboxSummary.totalMismatch(printed("printed", 121.014), null));
    assertTrue(InvoiceInboxSummary.totalMismatch(printed("printed", 121.016), null));
  }

  @Test
  @DisplayName("the minimal unit is the one of the currency: whole units for 0 decimals, 3 for KWD")
  void theBoundaryFollowsTheCurrencyScale() throws Exception {
    assertFalse(InvoiceInboxSummary.totalMismatch(printed("printed", 122), currency(0)));
    assertTrue(InvoiceInboxSummary.totalMismatch(printed("printed", 123), currency(0)));
    assertFalse(InvoiceInboxSummary.totalMismatch(printed("printed", 121.001), currency(3)));
    assertTrue(InvoiceInboxSummary.totalMismatch(printed("printed", 121.002), currency(3)));
  }

  @Test
  @DisplayName("a currency without precision reads as two decimals")
  void aCurrencyWithoutPrecisionIsTwoDecimals() throws Exception {
    Currency noPrecision = mock(Currency.class);
    when(noPrecision.getStandardPrecision()).thenReturn(null);

    assertFalse(InvoiceInboxSummary.totalMismatch(printed("printed", 121.01), noPrecision));
    assertTrue(InvoiceInboxSummary.totalMismatch(printed("printed", 121.02), noPrecision));
  }

  @Test
  @DisplayName("only a printed total is compared: a lines total, or none, never warns")
  void onlyAPrintedTotalIsCompared() throws Exception {
    assertFalse(InvoiceInboxSummary.totalMismatch(printed("lines", 999), null));
    assertFalse(InvoiceInboxSummary.totalMismatch(printed("printed", null), null));
    assertFalse(InvoiceInboxSummary.totalMismatch(printed("printed", JSONObject.NULL), null));
    assertFalse(InvoiceInboxSummary.totalMismatch(printed("printed", "abc"), null));
    assertFalse(InvoiceInboxSummary.totalMismatch(new JSONObject(), null));
    assertFalse(InvoiceInboxSummary.totalMismatch(null, null));
  }

  @Test
  @DisplayName("negative totals compare with their sign: a sign difference is a mismatch")
  void aSignDifferenceIsAMismatch() throws Exception {
    JSONObject credit = new JSONObject().put(HEADER, new JSONObject().put("totalSource", "printed")
        .put("totalAmount", -121)).put("lines", new JSONArray().put(
            new JSONObject().put("quantity", -1).put("unitPrice", 100).put("taxRate", 21)));
    assertFalse(InvoiceInboxSummary.totalMismatch(credit, null));

    credit.getJSONObject(HEADER).put("totalAmount", 121);
    assertTrue(InvoiceInboxSummary.totalMismatch(credit, null));
  }

  @Test
  @DisplayName("a printed total with no lines compares against zero")
  void noLinesCompareAgainstZero() throws Exception {
    JSONObject noLines = new JSONObject().put(HEADER, new JSONObject()
        .put("totalSource", "printed").put("totalAmount", 0.01));
    assertFalse(InvoiceInboxSummary.totalMismatch(noLines, null));

    noLines.getJSONObject(HEADER).put("totalAmount", 0.02);
    assertTrue(InvoiceInboxSummary.totalMismatch(noLines, null));
  }

  @Test
  @DisplayName("newProducts counts the lines whose product is in state new")
  void newProductsCountsTheNewLines() throws Exception {
    JSONArray lines = new JSONArray()
        .put(new JSONObject().put("product", new JSONObject().put(STATE, "new")))
        .put(new JSONObject().put("product", new JSONObject().put(STATE, "linked")))
        .put(new JSONObject().put("product", new JSONObject().put(STATE, "new")))
        .put(new JSONObject()).put("not-an-object");

    assertEquals(2, InvoiceInboxSummary.newProducts(lines));
    assertEquals(0, InvoiceInboxSummary.newProducts(null));
    assertEquals(0, InvoiceInboxSummary.newProducts(new JSONArray()));
  }

  @Test
  @DisplayName("syncFrom writes the currency, the warning and the new products count")
  void syncFromWritesTheDerivedColumns() throws Exception {
    Currency eur = currency(2);
    when(dal.get(Currency.class, "EUR")).thenReturn(eur);
    JSONObject document = printed("printed", 130);
    document.getJSONObject(HEADER).put("currencyId", "EUR");
    document.getJSONArray("lines").getJSONObject(0)
        .put("product", new JSONObject().put(STATE, "new"));

    InvoiceInboxSummary.syncFrom(document, row);

    verify(row).setCurrency(eur);
    verify(row).setTotalMismatch(true);
    verify(row).setNewProducts(1L);
  }

  @Test
  @DisplayName("syncFrom clears the warning when the printed total follows the lines")
  void syncFromClearsTheWarning() throws Exception {
    JSONObject document = printed("printed", 121);
    document.getJSONObject(HEADER).put("currencyId", "EUR");
    Currency eur = currency(2);
    when(dal.get(Currency.class, "EUR")).thenReturn(eur);

    InvoiceInboxSummary.syncFrom(document, row);

    verify(row).setTotalMismatch(false);
    verify(row).setNewProducts(0L);
  }

  /** Runs syncFrom and returns what it stored in the list total column. */
  private BigDecimal storedTotal(JSONObject document, Currency currency) {
    if (currency != null) {
      when(dal.get(Currency.class, "CUR")).thenReturn(currency);
      try {
        document.getJSONObject(HEADER).put("currencyId", "CUR");
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
    }
    IncomingDocument target = mock(IncomingDocument.class);
    InvoiceInboxSummary.syncFrom(document, target);
    ArgumentCaptor<BigDecimal> total = ArgumentCaptor.forClass(BigDecimal.class);
    verify(target).setGrandTotalAmount(total.capture());
    return total.getValue();
  }

  @Test
  @DisplayName("the list total is the LINES total, not the printed one, and the header keeps the printed")
  void theLinesTotalWinsOverThePrinted() throws Exception {
    JSONObject document = printed("printed", 130);

    assertEquals(0, new BigDecimal("121.00").compareTo(storedTotal(document, null)));
    assertEquals(130, document.getJSONObject(HEADER).getInt("totalAmount"));
  }

  @Test
  @DisplayName("a lines-sourced document stores its lines total too, and a null printed total changes nothing")
  void linesSourceAndNullPrinted() throws Exception {
    assertEquals(0, new BigDecimal("121.00").compareTo(storedTotal(printed("lines", 999), null)));
    assertEquals(0, new BigDecimal("121.00")
        .compareTo(storedTotal(printed("printed", JSONObject.NULL), null)));
  }

  @Test
  @DisplayName("a document with no lines keeps the header total, or null")
  void noLinesFallBackToTheHeaderTotal() throws Exception {
    JSONObject noLines = new JSONObject().put(HEADER, new JSONObject()
        .put("totalSource", "printed").put("totalAmount", 77.5));
    assertEquals(0, new BigDecimal("77.5").compareTo(storedTotal(noLines, null)));

    JSONObject none = new JSONObject().put(HEADER, new JSONObject().put("totalSource", "printed"));
    assertEquals(null, storedTotal(none, null));
  }

  @Test
  @DisplayName("the list total is rounded to the currency scale: 0 and 3 decimals")
  void theListTotalFollowsTheCurrencyScale() throws Exception {
    assertEquals(new BigDecimal("121"), storedTotal(printed("printed", 130), currency(0)));
    assertEquals(new BigDecimal("121.000"), storedTotal(printed("printed", 130), currency(3)));
  }

  @Test
  @DisplayName("a credit note stores a negative lines total")
  void aCreditStoresANegativeTotal() throws Exception {
    JSONObject credit = new JSONObject().put(HEADER, new JSONObject().put("totalSource", "printed")
        .put("totalAmount", -130)).put("lines", new JSONArray().put(
            new JSONObject().put("quantity", -1).put("unitPrice", 100).put("taxRate", 21)));

    assertEquals(0, new BigDecimal("-121.00").compareTo(storedTotal(credit, null)));
  }
}
