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

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.LineAmountTestSupport.assertAmount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * {@link InvoiceLineAmountSupport#deriveAmountsOnCreate} (ETP-5528): a sales invoice line created
 * through {@code etendo_create} persisted {@code LineNetAmt 0 / Line_Gross_Amount 0}; live after the
 * fix, Fernet at 18, qty 10, 21 % → net 180 / gross 217.80, the same as {@code etendo_batch}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceLineAmountSupport
 */
class InvoiceLineAmountSupportTest {

  private static final String LINE =
      "\"invoicedQuantity\":10,\"unitPrice\":18,\"tax\":\"TAX21\"";

  static Stream<Arguments> amountsOnCreate() {
    return Stream.of(
        Arguments.of("no amounts → both derived", "POST", NeoEndpointType.CRUD,
            "{" + LINE + "}", "180", "217.80"),
        Arguments.of("amounts at 0 → overwritten", "POST", NeoEndpointType.CRUD,
            "{" + LINE + ",\"lineNetAmount\":0,\"grossAmount\":\"0\"}", "180", "217.80"),
        Arguments.of("the caller's net and gross → both kept", "POST", NeoEndpointType.CRUD,
            "{" + LINE + ",\"lineNetAmount\":150,\"grossAmount\":200}", "150", "200"),
        Arguments.of("no tax → untouched", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":10,\"unitPrice\":18}", null, null),
        Arguments.of("blank tax → untouched", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":10,\"unitPrice\":18,\"tax\":\"  \"}", null, null),
        Arguments.of("null tax → untouched", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":10,\"unitPrice\":18,\"tax\":null}", null, null),
        // ETP-5703: deliberately still untouched — the CREATE derivation ignores updates; an update
        // is re-derived by deriveAmountsOnUpdate (tests below), against the stored line.
        Arguments.of("PATCH → untouched by the create derivation", "PATCH", NeoEndpointType.CRUD,
            "{" + LINE + "}", null, null),
        Arguments.of("non-CRUD → untouched", "POST", NeoEndpointType.CALLOUT, "{" + LINE + "}",
            null, null),
        Arguments.of("quantity 0 → no write", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":0,\"unitPrice\":18,\"tax\":\"TAX21\"}", null, null),
        Arguments.of("price 0 → no write", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":10,\"unitPrice\":0,\"tax\":\"TAX21\"}", null, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("amountsOnCreate")
  void deriveAmountsOnCreate(String label, String method, NeoEndpointType type, String body,
      String expectedNet, String expectedGross) throws Exception {
    JSONObject json = new JSONObject(body);
    NeoContext ctx = NeoContext.builder()
        .specName("sales-invoice")
        .entityName("lines")
        .httpMethod(method)
        .endpointType(type)
        .requestBody(json)
        .build();

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      InvoiceLineAmountSupport.deriveAmountsOnCreate(ctx);
    }

    assertAmount(label, expectedNet, json, InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT);
    assertAmount(label, expectedGross, json, InvoiceLineAmountSupport.FIELD_GROSS_AMOUNT);
  }

  /** A stored line: qty 10 at 1.50, net price list, tax TAX21. */
  private static InvoiceLine storedLine() {
    InvoiceLine stored = mock(InvoiceLine.class);
    TaxRate tax = mock(TaxRate.class);
    when(stored.getInvoicedQuantity()).thenReturn(new BigDecimal("10"));
    when(stored.getUnitPrice()).thenReturn(new BigDecimal("1.50"));
    when(stored.getGrossUnitPrice()).thenReturn(BigDecimal.ZERO);
    when(stored.getTax()).thenReturn(tax);
    when(tax.getId()).thenReturn("TAX21");
    return stored;
  }

  private static NeoContext updateContext(JSONObject body) {
    return NeoContext.builder()
        .specName("purchase-invoice")
        .entityName("lines")
        .httpMethod("PUT")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("line-1")
        .requestBody(body)
        .build();
  }

  /**
   * ETP-5703 repro (W3): {@code etendo_update} of a line's quantity kept the old amounts — the
   * update never reached a derivation. A patch of the quantity alone must re-derive both amounts
   * from the stored price and tax, rounded to the currency precision (3 × 1.50 at 21 %: 4.50 /
   * 5.45, not 5.445).
   */
  @Test
  void deriveAmountsOnUpdate_quantityChanged_rederivesRoundedFromStoredLine() throws Exception {
    InvoiceLine stored = storedLine();
    JSONObject body = new JSONObject("{\"invoicedQuantity\":3}");

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      OBDal instance = OBDal.getInstance();
      when(instance.get(InvoiceLine.class, "line-1")).thenReturn(stored);
      InvoiceLineAmountSupport.deriveAmountsOnUpdate(updateContext(body));
    }

    assertEquals(0, new BigDecimal("4.50").compareTo(
        new BigDecimal(String.valueOf(body.get(InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT)))));
    assertEquals(0, new BigDecimal("5.45").compareTo(
        new BigDecimal(String.valueOf(body.get(InvoiceLineAmountSupport.FIELD_GROSS_AMOUNT)))));
  }

  /**
   * A tax-only change re-derives the gross from the stored quantity and price (REST PATCH parity):
   * 10 × 1.50 = 15.00 net, at the new tax's 21 % (the stub answers 21 for any tax) 18.15 gross.
   */
  @Test
  void deriveAmountsOnUpdate_taxOnlyChanged_rederivesGross() throws Exception {
    InvoiceLine stored = storedLine();
    JSONObject body = new JSONObject("{\"tax\":\"TAX-OTHER\"}");

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      OBDal instance = OBDal.getInstance();
      when(instance.get(InvoiceLine.class, "line-1")).thenReturn(stored);
      InvoiceLineAmountSupport.deriveAmountsOnUpdate(updateContext(body));
    }

    assertEquals(0, new BigDecimal("15.00").compareTo(
        new BigDecimal(String.valueOf(body.get(InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT)))));
    assertEquals(0, new BigDecimal("18.15").compareTo(
        new BigDecimal(String.valueOf(body.get(InvoiceLineAmountSupport.FIELD_GROSS_AMOUNT)))));
  }

  /** A quantity equal to the stored one is not a change: the amounts are left alone. */
  @Test
  void deriveAmountsOnUpdate_quantityUnchanged_leavesAmountsAlone() throws Exception {
    InvoiceLine stored = storedLine();
    JSONObject body = new JSONObject("{\"invoicedQuantity\":10}");

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      OBDal instance = OBDal.getInstance();
      when(instance.get(InvoiceLine.class, "line-1")).thenReturn(stored);
      InvoiceLineAmountSupport.deriveAmountsOnUpdate(updateContext(body));
    }

    assertFalse(body.has(InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT));
    assertFalse(body.has(InvoiceLineAmountSupport.FIELD_GROSS_AMOUNT));
  }
}
