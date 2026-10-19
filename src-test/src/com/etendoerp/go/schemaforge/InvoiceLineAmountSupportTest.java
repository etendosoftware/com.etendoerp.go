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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.InvoiceLine;

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
        Arguments.of("no tax → net only, gross left to the create cascade", "POST",
            NeoEndpointType.CRUD, "{\"invoicedQuantity\":10,\"unitPrice\":18}", "180", null),
        Arguments.of("blank tax → net only", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":10,\"unitPrice\":18,\"tax\":\"  \"}", "180", null),
        Arguments.of("null tax → net only", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":10,\"unitPrice\":18,\"tax\":null}", "180", null),
        Arguments.of("PATCH → untouched", "PATCH", NeoEndpointType.CRUD, "{" + LINE + "}", null,
            null),
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

  // ── ETP-5710: the line discount (etgoDiscount) ────────────────────────────────

  private static final String TAX = ",\"tax\":\"TAX21\"";

  static Stream<Arguments> discountOnCreate() {
    return Stream.of(
        // label, body, unitPrice, listPrice, net, gross
        Arguments.of("price sent, no list price: the price is the list price (10 x 25, 2 %)",
            "{\"invoicedQuantity\":10,\"unitPrice\":25,\"etgoDiscount\":2" + TAX + "}",
            "24.5", "25", "245", "296.45"),
        Arguments.of("price resolved by the server (unitPrice == listPrice 11, 2 %)",
            "{\"invoicedQuantity\":1,\"unitPrice\":11,\"listPrice\":11,\"etgoDiscount\":2"
                + TAX + "}", "10.78", "11", "10.78", "13.04"),
        Arguments.of("a net the cascade derived from the undiscounted price is replaced",
            "{\"invoicedQuantity\":1,\"unitPrice\":11,\"listPrice\":11,\"etgoDiscount\":2,"
                + "\"lineNetAmount\":11,\"grossAmount\":13.31" + TAX + "}",
            "10.78", "11", "10.78", "13.04"),
        Arguments.of("SPA body, price already discounted + list price: no double discount",
            "{\"invoicedQuantity\":10,\"unitPrice\":24.5,\"listPrice\":25,\"etgoDiscount\":2"
                + TAX + "}", "24.5", "25", "245", "296.45"),
        Arguments.of("no price, list price only: the price is derived from it",
            "{\"invoicedQuantity\":10,\"listPrice\":20,\"etgoDiscount\":10" + TAX + "}",
            "18", "20", "180", "217.80"),
        Arguments.of("an explicit gross price wins: no discount applied",
            "{\"invoicedQuantity\":10,\"unitPrice\":25,\"grossUnitPrice\":30,"
                + "\"etgoDiscount\":2" + TAX + "}", "25", null, "250", null),
        Arguments.of("discount 0: the price is the price",
            "{\"invoicedQuantity\":10,\"unitPrice\":25,\"etgoDiscount\":0" + TAX + "}",
            "25", null, "250", "302.50"),
        Arguments.of("a different price than the list price is the caller's",
            "{\"invoicedQuantity\":10,\"unitPrice\":22,\"listPrice\":25,\"etgoDiscount\":2"
                + TAX + "}", "22", "25", "220", "266.20"),
        Arguments.of("a return line (negative quantity) keeps the sign",
            "{\"invoicedQuantity\":-10,\"unitPrice\":25,\"etgoDiscount\":2" + TAX + "}",
            "24.5", "25", "-245", null),
        Arguments.of("no tax: net only",
            "{\"invoicedQuantity\":1,\"unitPrice\":11,\"listPrice\":11,\"etgoDiscount\":2}",
            "10.78", "11", "10.78", null),
        Arguments.of("100 % (price 0 on purpose): net and gross 0, no amounts sent",
            "{\"invoicedQuantity\":1,\"unitPrice\":10,\"listPrice\":10,\"etgoDiscount\":100"
                + TAX + "}", "0", "10", "0", "0"),
        Arguments.of("100 %: the amounts the cascade derived from the undiscounted price are replaced",
            "{\"invoicedQuantity\":1,\"unitPrice\":10,\"listPrice\":10,\"etgoDiscount\":100,"
                + "\"lineNetAmount\":10,\"grossAmount\":12.1" + TAX + "}", "0", "10", "0", "0"),
        Arguments.of("100 %: an amount the caller chose is kept",
            "{\"invoicedQuantity\":1,\"unitPrice\":10,\"listPrice\":10,\"etgoDiscount\":100,"
                + "\"lineNetAmount\":7" + TAX + "}", "0", "10", "7", "0"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("discountOnCreate")
  void discountIsAppliedOnCreate(String label, String body, String unitPrice, String listPrice,
      String net, String gross) throws Exception {
    JSONObject json = new JSONObject(body);
    NeoContext ctx = NeoContext.builder().specName("purchase-invoice").entityName("lines")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(json).build();

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      InvoiceLineAmountSupport.deriveAmountsOnCreate(ctx);
    }

    assertAmount(label, unitPrice, json, InvoiceLineAmountSupport.FIELD_UNIT_PRICE);
    if (listPrice != null) {
      assertAmount(label, listPrice, json, InvoiceLineAmountSupport.FIELD_LIST_PRICE);
    }
    assertAmount(label, net, json, InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT);
    if (gross != null && !"-245".equals(net)) {
      assertAmount(label, gross, json, InvoiceLineAmountSupport.FIELD_GROSS_AMOUNT);
    }
  }

  // ── ETP-5710: update (PATCH / PUT) overlays the patch on the stored line ──────

  /** 10 x 24.50 (list 25, 2 %), 21 %: the line case 1 of the ticket should have stored. */
  private static InvoiceLine storedLine() {
    return LineAmountTestSupport.storedInvoiceLine("10", "24.5", "25", "2", "0", "TAX21");
  }

  static Stream<Arguments> updates() {
    return Stream.of(
        // label, stored line, patch, expected unitPrice (null: not written), net, gross
        Arguments.of("quantity 10 to 20 → 490", storedLine(), "{\"invoicedQuantity\":20}", null,
            "490", "592.90"),
        Arguments.of("discount 2 to 10 → the price follows the list price", storedLine(),
            "{\"etgoDiscount\":10}", "22.5", "225", "272.25"),
        Arguments.of("list price changes → price re-derived with the stored discount",
            storedLine(), "{\"listPrice\":30}", "29.4", "294", "355.74"),
        Arguments.of("a price different from the list price is the caller's", storedLine(),
            "{\"unitPrice\":30}", null, "300", "363"),
        Arguments.of("tax change → gross follows, net does not move", storedLine(),
            "{\"tax\":\"TAX10\"}", null, "245", "269.50"),
        Arguments.of("legacy line (price 25, no list price): price kept, quantity re-derives net",
            LineAmountTestSupport.storedInvoiceLine("10", "25", "0", "2", "0", "TAX21"),
            "{\"invoicedQuantity\":20}", null, "500", "605"),
        Arguments.of("an amount the caller sent is kept", storedLine(),
            "{\"invoicedQuantity\":20,\"lineNetAmount\":480}", null, "480", "592.90"),
        Arguments.of("quantity 0 → net 0", storedLine(), "{\"invoicedQuantity\":0}", null, "0",
            null),
        Arguments.of("discount 10 to 100 → price 0, net 0, gross 0",
            LineAmountTestSupport.storedInvoiceLine("1", "9", "10", "10", "0", "TAX21"),
            "{\"etgoDiscount\":100}", "0", "0", "0"),
        Arguments.of("quantity edit on a line already at 100 % → net stays 0, price untouched",
            LineAmountTestSupport.storedInvoiceLine("1", "0", "10", "100", "0", "TAX21"),
            "{\"invoicedQuantity\":5}", null, "0", "0"),
        Arguments.of("quantity edit on a free line (price 0, no discount) → net 0",
            LineAmountTestSupport.storedInvoiceLine("1", "0", "0", "0", "0", "TAX21"),
            "{\"invoicedQuantity\":5}", null, "0", "0"),
        Arguments.of("nothing the amounts depend on changes → untouched", storedLine(),
            "{\"project\":\"P1\"}", null, null, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("updates")
  void amountsAreRecalculatedOnUpdate(String label, InvoiceLine stored, String patch,
      String unitPrice, String net, String gross) throws Exception {
    JSONObject json = new JSONObject(patch);
    NeoContext ctx = NeoContext.builder().specName("purchase-invoice").entityName("lines")
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(
            LineAmountTestSupport.STORED_LINE_ID).requestBody(json).build();

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(patch.contains("TAX10") ? 10.0
        : 21.0, stored)) {
      InvoiceLineAmountSupport.deriveAmountsOnUpdate(ctx);
    }

    if (unitPrice == null && !patch.contains("unitPrice")) {
      assertFalse(json.has(InvoiceLineAmountSupport.FIELD_UNIT_PRICE), label + ": price untouched");
    } else if (unitPrice != null) {
      assertAmount(label, unitPrice, json, InvoiceLineAmountSupport.FIELD_UNIT_PRICE);
    }
    assertAmount(label, net, json, InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT);
    if (gross != null) {
      assertAmount(label, gross, json, InvoiceLineAmountSupport.FIELD_GROSS_AMOUNT);
    }
  }

  /**
   * ETP-5703: the recalculated amounts are written rounded to the invoice currency's precision, as
   * {@link java.math.BigDecimal}s — 3 x 1.50 at 21 % is 4.50 / 5.45, never the unrounded 5.445 the
   * policy used to write. {@code assertAmount} rounds before comparing, so this asserts exactly.
   */
  @Test
  void updateWritesAmountsRoundedToTheCurrencyPrecision() throws Exception {
    JSONObject json = new JSONObject("{\"invoicedQuantity\":3}");
    NeoContext ctx = NeoContext.builder().specName("purchase-invoice").entityName("lines")
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .recordId(LineAmountTestSupport.STORED_LINE_ID).requestBody(json).build();

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0,
        LineAmountTestSupport.storedInvoiceLine("10", "1.5", "0", "0", "0", "TAX21"))) {
      InvoiceLineAmountSupport.deriveAmountsOnUpdate(ctx);
    }

    assertEquals("4.50", json.get(InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT).toString());
    assertEquals("5.45", json.get(InvoiceLineAmountSupport.FIELD_GROSS_AMOUNT).toString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("notAnUpdate")
  void onlyAnUpdateOfAnExistingLineIsRecalculated(String label, String method,
      NeoEndpointType type, String recordId) throws Exception {
    JSONObject json = new JSONObject("{\"invoicedQuantity\":20}");
    NeoContext ctx = NeoContext.builder().httpMethod(method).endpointType(type)
        .recordId(recordId).requestBody(json).build();

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0, storedLine())) {
      InvoiceLineAmountSupport.deriveAmountsOnUpdate(ctx);
    }

    assertFalse(json.has(InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT), label);
  }

  static Stream<Arguments> notAnUpdate() {
    return Stream.of(
        Arguments.of("POST", "POST", NeoEndpointType.CRUD, LineAmountTestSupport.STORED_LINE_ID),
        Arguments.of("GET", "GET", NeoEndpointType.CRUD, LineAmountTestSupport.STORED_LINE_ID),
        Arguments.of("non-CRUD", "PATCH", NeoEndpointType.CALLOUT,
            LineAmountTestSupport.STORED_LINE_ID),
        Arguments.of("line not found", "PATCH", NeoEndpointType.CRUD, "UNKNOWN"),
        Arguments.of("no record id", "PATCH", NeoEndpointType.CRUD, null));
  }

  // ── ETP-5710 review: the discount must stay in [0, 100] ───────────────────────

  static Stream<Arguments> discountRange() {
    return Stream.of(
        // label, method, body, rejected, expected unitPrice (when accepted)
        Arguments.of("150 on create → rejected", "POST",
            "{\"invoicedQuantity\":10,\"unitPrice\":25,\"etgoDiscount\":150}", true, null),
        Arguments.of("-10 on create → rejected", "POST",
            "{\"invoicedQuantity\":10,\"unitPrice\":25,\"etgoDiscount\":-10}", true, null),
        Arguments.of("100 on create → accepted, price 0", "POST",
            "{\"invoicedQuantity\":10,\"unitPrice\":25,\"etgoDiscount\":100}", false, "0"),
        Arguments.of("0 on create → accepted, price kept", "POST",
            "{\"invoicedQuantity\":10,\"unitPrice\":25,\"etgoDiscount\":0}", false, "25"),
        Arguments.of("null on create → unchanged", "POST",
            "{\"invoicedQuantity\":10,\"unitPrice\":25,\"etgoDiscount\":null}", false, "25"),
        Arguments.of("absent on create → unchanged", "POST",
            "{\"invoicedQuantity\":10,\"unitPrice\":25}", false, "25"),
        Arguments.of("150 on PATCH → rejected", "PATCH", "{\"etgoDiscount\":150}", true, null),
        Arguments.of("-10 on PUT → rejected", "PUT", "{\"etgoDiscount\":-10}", true, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("discountRange")
  void discountOutsideZeroToHundredIsRefused(String label, String method, String body,
      boolean rejected, String unitPrice) throws Exception {
    JSONObject json = new JSONObject(body);
    NeoContext ctx = NeoContext.builder().specName("purchase-invoice").entityName("lines")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .recordId("POST".equals(method) ? null : LineAmountTestSupport.STORED_LINE_ID)
        .requestBody(json).build();

    NeoResponse response;
    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0, storedLine())) {
      response = InvoiceLineAmountSupport.deriveAmountsOnWrite(ctx);
    }

    if (rejected) {
      assertNotNull(response, label);
      assertEquals(422, response.getHttpStatus(), label);
      JSONObject error = response.getBody().getJSONObject("error");
      assertEquals(InvoiceLineAmountSupport.ERROR_INVALID_DISCOUNT, error.getString("code"));
      assertEquals("invalidLineDiscount", error.getString("code"));
      assertTrue(error.getString("message").contains("between 0 and 100"), error.getString("message"));
      assertFalse(json.has(InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT), label + ": untouched");
    } else {
      assertNull(response, label);
      assertAmount(label, unitPrice, json, InvoiceLineAmountSupport.FIELD_UNIT_PRICE);
    }
  }
}
