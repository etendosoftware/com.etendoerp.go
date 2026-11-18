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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * The check between the inbox model and the Etendo engine before the created invoice is completed:
 * the currency of the document, and the grand total at the scale of the invoice currency with a
 * tolerance of one minimal unit per distinct tax. A larger gap is drift and refuses; the printed
 * total is never compared.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmVerify
 */
class InvoiceInboxConfirmVerifyTest {

  private MockedStatic<OBMessageUtils> messages;

  @BeforeEach
  void setUp() {
    messages = Mockito.mockStatic(OBMessageUtils.class);
  }

  @AfterEach
  void tearDown() {
    messages.close();
  }

  private static Currency currency(String id, Long precision) {
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(id);
    when(currency.getStandardPrecision()).thenReturn(precision);
    return currency;
  }

  /** An invoice whose lines carry the given tax ids and whose grand total is {@code total}. */
  private static Invoice invoice(Currency currency, String total, String... taxIds) {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getCurrency()).thenReturn(currency);
    when(invoice.getGrandTotalAmount()).thenReturn(total == null ? null : new BigDecimal(total));
    java.util.ArrayList<InvoiceLine> lines = new java.util.ArrayList<>();
    for (String taxId : taxIds) {
      InvoiceLine line = mock(InvoiceLine.class);
      TaxRate tax = mock(TaxRate.class);
      when(tax.getId()).thenReturn(taxId);
      when(line.getTax()).thenReturn(taxId == null ? null : tax);
      lines.add(line);
    }
    when(invoice.getInvoiceLineList()).thenReturn(lines);
    return invoice;
  }

  private static JSONArray lines(String json) throws Exception {
    return new JSONArray(json);
  }

  private static final String ONE_LINE = "[{quantity:1,unitPrice:100,taxRate:21}]";
  private static final String HALF_LINES =
      "[{quantity:1,unitPrice:0.5,taxRate:21},{quantity:1,unitPrice:0.5,taxRate:21}]";

  private static void verify(Invoice invoice, JSONObject header, String lines) throws Exception {
    InvoiceInboxConfirmVerify.verify(invoice, header, lines(lines));
  }

  @Test
  @DisplayName("an invoice in another currency than the document stored is refused with both in the message")
  void aCurrencyMismatchRefuses() throws Exception {
    Invoice invoice = invoice(currency("USD", 2L), "121.00", "T1");

    assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice, new JSONObject("{currencyId:'EUR'}"), ONE_LINE));
  }

  @Test
  @DisplayName("the same currency, or none stored in the header, passes the currency check")
  void theSameOrNoStoredCurrencyPasses() throws Exception {
    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), "121.00", "T1"),
        new JSONObject("{currencyId:'EUR'}"), ONE_LINE));
    assertDoesNotThrow(() -> verify(invoice(currency("USD", 2L), "121.00", "T1"),
        new JSONObject("{}"), ONE_LINE));
  }

  @Test
  @DisplayName("an invoice with no grand total yet is not compared")
  void noGrandTotalIsNotCompared() throws Exception {
    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), null, "T1"),
        new JSONObject("{}"), ONE_LINE));
  }

  @Test
  @DisplayName("a document with no priced line has no model total to compare")
  void noModelNoComparison() throws Exception {
    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), "999.00", "T1"),
        new JSONObject("{}"), "[{description:'x'}]"));
  }

  @Test
  @DisplayName("one tax: the engine may differ by one minimal unit, two units refuse with both numbers")
  void oneTaxToleratesOneUnit() throws Exception {
    JSONObject header = new JSONObject("{}");

    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), "121.00", "T1"), header, ONE_LINE));
    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), "121.01", "T1"), header, ONE_LINE));
    InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice(currency("EUR", 2L), "121.02", "T1"), header, ONE_LINE));
    assertTrue(stop.getMessage().contains("121.02") && stop.getMessage().contains("121.00"),
        stop.getMessage());
    assertEquals("total_mismatch", stop.detail().code());
    assertEquals("121.02", stop.detail().params().get("invoice"));
    assertEquals("121.00", stop.detail().params().get("document"));
  }

  @Test
  @DisplayName("two different tax records with the same rate: 0.21 vs 0.22 accepted, a gap beyond two units refused")
  void twoTaxesTolerateTwoUnits() throws Exception {
    JSONObject header = new JSONObject("{}");

    // bases 0.50 + 0.50 at 21%: the model taxes the sum (0.21), the engine each base (0.11 + 0.11)
    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), "1.22", "T1", "T2"), header,
        HALF_LINES));
    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), "1.23", "T1", "T2"), header,
        HALF_LINES));
    assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice(currency("EUR", 2L), "1.24", "T1", "T2"), header, HALF_LINES));
    // the same gap with one tax record is a refusal already at two units
    assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice(currency("EUR", 2L), "1.23", "T1", "T1"), header, HALF_LINES));
  }

  @Test
  @DisplayName("the same tax id on several lines counts once; lines without a tax count for none")
  void distinctTaxesAreCountedByIdAndNullsIgnored() throws Exception {
    JSONObject header = new JSONObject("{}");

    assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice(currency("EUR", 2L), "121.02", "T1", "T1", null), header, ONE_LINE));
    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), "121.02", "T1", "T2"), header,
        ONE_LINE));
    assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice(currency("EUR", 2L), "121.02"), header, ONE_LINE));
  }

  @Test
  @DisplayName("the scale is the one of the invoice currency: whole units for JPY, thousandths for KWD")
  void theScaleFollowsTheInvoiceCurrency() throws Exception {
    JSONObject header = new JSONObject("{}");

    assertDoesNotThrow(() -> verify(invoice(currency("JPY", 0L), "122", "T1"), header, ONE_LINE));
    assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice(currency("JPY", 0L), "123", "T1"), header, ONE_LINE));
    assertDoesNotThrow(() -> verify(invoice(currency("KWD", 3L), "121.001", "T1"), header,
        ONE_LINE));
    assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice(currency("KWD", 3L), "121.002", "T1"), header, ONE_LINE));
  }

  @Test
  @DisplayName("a currency without precision, or none, reads as two decimals")
  void noPrecisionIsTwoDecimals() throws Exception {
    JSONObject header = new JSONObject("{}");

    assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice(currency("EUR", null), "121.02", "T1"), header, ONE_LINE));
    assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice(null, "121.02", "T1"), header, ONE_LINE));
  }

  @Test
  @DisplayName("a negative credit document compares with its sign")
  void aNegativeCredit() throws Exception {
    String credit = "[{quantity:-1,unitPrice:100,taxRate:21}]";
    JSONObject header = new JSONObject("{}");

    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), "-121.00", "T1"), header, credit));
    assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> verify(invoice(currency("EUR", 2L), "121.00", "T1"), header, credit));
  }

  @Test
  @DisplayName("a 6-decimal discount is computed by the model as the engine does")
  void aSixDecimalDiscount() throws Exception {
    // unit6 = 100 x (1 - 0.333333) = 66.6667 -> net of 3 = 200.00 (round2(3 x 66.6667)), tax 42.00
    String discounted = "[{quantity:3,unitPrice:100,discount:33.3333,taxRate:21}]";

    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), "242.00", "T1"),
        new JSONObject("{}"), discounted));
  }

  @Test
  @DisplayName("the printed total of the header is never compared")
  void thePrintedTotalIsNeverCompared() throws Exception {
    JSONObject header = new JSONObject("{totalSource:'printed',totalAmount:5000}");

    assertDoesNotThrow(() -> verify(invoice(currency("EUR", 2L), "121.00", "T1"), header,
        ONE_LINE));
  }
}
