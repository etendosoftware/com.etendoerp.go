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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.stream.Stream;

import java.util.Date;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.ProductPrice;
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

  // ── ETP-5710 follow-up: the grid's "Precio" is listPrice ──────────────────────

  /**
   * The grid column "Precio" of an invoice line is {@code listPrice}; the form edits it and derives
   * {@code unitPrice} from it, so a form-created line at 50 has {@code listPrice 50}. An agent that
   * sent only {@code unitPrice 50} left {@code listPrice 0} (the price injector abstains when the
   * price is not the product's standard one): net and gross were right (100 / 121.00) but the grid
   * showed 0,00. With a price, no discount and no list price, the price is the list price.
   */
  static Stream<Arguments> listPriceOnCreate() {
    return Stream.of(
        // label, body, expected listPrice (null: absent), unitPrice, net, gross
        Arguments.of("explicit price, no list price → list = price (2 x 50, 21 %)",
            "{\"invoicedQuantity\":2,\"unitPrice\":50" + TAX + "}", "50", "50", "100", "121"),
        Arguments.of("explicit price, list price 0 → list = price",
            "{\"invoicedQuantity\":2,\"unitPrice\":50,\"listPrice\":0" + TAX + "}", "50", "50",
            "100", "121"),
        Arguments.of("explicit price, discount 0 → list = price",
            "{\"invoicedQuantity\":2,\"unitPrice\":50,\"etgoDiscount\":0" + TAX + "}", "50", "50",
            "100", "121"),
        Arguments.of("explicit price and explicit list price → both untouched",
            "{\"invoicedQuantity\":2,\"unitPrice\":50,\"listPrice\":60" + TAX + "}", "60", "50",
            "100", "121"),
        Arguments.of("price equals the resolved list price (standard price) → unchanged",
            "{\"invoicedQuantity\":2,\"unitPrice\":50,\"listPrice\":50" + TAX + "}", "50", "50",
            "100", "121"),
        Arguments.of("discount present → the discount rule as before (list = price, price discounted)",
            "{\"invoicedQuantity\":2,\"unitPrice\":50,\"etgoDiscount\":10" + TAX + "}", "50", "45",
            "90", "108.90"),
        Arguments.of("an explicit gross price → the list price is left alone",
            "{\"invoicedQuantity\":2,\"unitPrice\":50,\"grossUnitPrice\":60.5" + TAX + "}", null, "50",
            "100", null),
        Arguments.of("discount 100 → list = price, price 0, net and gross 0",
            "{\"invoicedQuantity\":2,\"unitPrice\":50,\"etgoDiscount\":100" + TAX + "}", "50", "0",
            "0", "0"),
        Arguments.of("a negative price (a return) → list = price, signs kept",
            "{\"invoicedQuantity\":2,\"unitPrice\":-50" + TAX + "}", "-50", "-50", "-100", "-121"),
        Arguments.of("no price → no list price invented",
            "{\"invoicedQuantity\":2" + TAX + "}", null, null, null, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("listPriceOnCreate")
  void listPriceFollowsAnExplicitPriceOnCreate(String label, String body, String listPrice,
      String unitPrice, String net, String gross) throws Exception {
    JSONObject json = new JSONObject(body);
    NeoContext ctx = NeoContext.builder().specName("purchase-invoice").entityName("lines")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(json).build();

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      InvoiceLineAmountSupport.deriveAmountsOnCreate(ctx);
    }

    if (listPrice == null) {
      assertFalse(json.has(InvoiceLineAmountSupport.FIELD_LIST_PRICE), label + ": " + json);
    } else {
      assertAmount(label, listPrice, json, InvoiceLineAmountSupport.FIELD_LIST_PRICE);
    }
    if (unitPrice != null) {
      assertAmount(label, unitPrice, json, InvoiceLineAmountSupport.FIELD_UNIT_PRICE);
    }
    if (net != null) {
      assertAmount(label, net, json, InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT);
    }
    if (gross != null) {
      assertAmount(label, gross, json, InvoiceLineAmountSupport.FIELD_GROSS_AMOUNT);
    }
  }

  static Stream<Arguments> listPriceOnUpdate() {
    return Stream.of(
        // label, stored line, patch, expected listPrice written (null: not written)
        Arguments.of("price set on a line whose list price is 0, no discount → list = price",
            LineAmountTestSupport.storedInvoiceLine("2", "0", "0", "0", "0", "TAX21"),
            "{\"unitPrice\":50}", "50"),
        Arguments.of("a line created at 50 / list 50, no discount, repriced to 60 → list follows (the form)",
            LineAmountTestSupport.storedInvoiceLine("2", "50", "50", "0", "0", "TAX21"),
            "{\"unitPrice\":60}", "60"),
        Arguments.of("repriced with an explicit list price in the patch → the caller's list price",
            LineAmountTestSupport.storedInvoiceLine("2", "50", "50", "0", "0", "TAX21"),
            "{\"unitPrice\":60,\"listPrice\":65}", "65"),
        Arguments.of("a discounted line repriced → discount kept, list worked back (ETP-5703)",
            LineAmountTestSupport.storedInvoiceLine("2", "22.5", "25", "10", "0", "TAX21"),
            "{\"unitPrice\":30}", "33.333333"),
        Arguments.of("legacy discounted line (list 0, discount 2): the price edit does not invent a list",
            LineAmountTestSupport.storedInvoiceLine("10", "24.5", "0", "2", "0", "TAX21"),
            "{\"unitPrice\":30}", null),
        Arguments.of("an explicit listPrice 0 with the price is read as absent → list = price",
            LineAmountTestSupport.storedInvoiceLine("2", "0", "0", "0", "0", "TAX21"),
            "{\"unitPrice\":50,\"listPrice\":0}", "50"),
        Arguments.of("a stored non-zero gross unit price → the helper does not fire",
            LineAmountTestSupport.storedInvoiceLine("2", "0", "0", "0", "60.5", "TAX21"),
            "{\"unitPrice\":50}", null),
        Arguments.of("a patch that only clears the discount does not re-price → no list invented",
            LineAmountTestSupport.storedInvoiceLine("10", "24.5", "0", "2", "0", "TAX21"),
            "{\"etgoDiscount\":0}", null),
        Arguments.of("unitPrice null in the patch → not a re-price",
            LineAmountTestSupport.storedInvoiceLine("2", "0", "0", "0", "0", "TAX21"),
            "{\"unitPrice\":null}", null),
        Arguments.of("the SAME unit price re-sent → the list price is not touched (the form only moves it when Precio is edited)",
            LineAmountTestSupport.storedInvoiceLine("2", "90", "100", "0", "0", "TAX21"),
            "{\"unitPrice\":90}", null),
        Arguments.of("a legacy line (list 100, price 90) repriced to 80 → list follows",
            LineAmountTestSupport.storedInvoiceLine("2", "90", "100", "0", "0", "TAX21"),
            "{\"unitPrice\":80}", "80"),
        Arguments.of("the same price re-sent as a number with other scale → still the same price",
            LineAmountTestSupport.storedInvoiceLine("2", "90", "100", "0", "0", "TAX21"),
            "{\"unitPrice\":\"90.000000\"}", null),
        Arguments.of("an explicit list price in the patch is the caller's",
            LineAmountTestSupport.storedInvoiceLine("2", "0", "0", "0", "0", "TAX21"),
            "{\"unitPrice\":50,\"listPrice\":55}", "55"),
        Arguments.of("a patch that does not re-price leaves the list price alone",
            LineAmountTestSupport.storedInvoiceLine("2", "50", "0", "0", "0", "TAX21"),
            "{\"invoicedQuantity\":3}", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("listPriceOnUpdate")
  void listPriceFollowsAnExplicitPriceOnUpdate(String label, InvoiceLine stored, String patch,
      String listPrice) throws Exception {
    JSONObject json = new JSONObject(patch);
    NeoContext ctx = NeoContext.builder().specName("purchase-invoice").entityName("lines")
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .recordId(LineAmountTestSupport.STORED_LINE_ID).requestBody(json).build();

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0, stored)) {
      InvoiceLineAmountSupport.deriveAmountsOnUpdate(ctx);
    }

    if (listPrice == null) {
      assertFalse(json.has(InvoiceLineAmountSupport.FIELD_LIST_PRICE), label + ": " + json);
    } else {
      assertAmount(label, listPrice, json, InvoiceLineAmountSupport.FIELD_LIST_PRICE);
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
        Arguments.of("a new price on a discounted line is the caller's (list worked back)",
            storedLine(),
            "{\"unitPrice\":30}", null, "300", "363"),
        Arguments.of("tax change → gross follows, net does not move", storedLine(),
            "{\"tax\":\"TAX10\"}", null, "245", "269.50"),
        Arguments.of("price set on a line with list price 0 → list follows, price and net final",
            LineAmountTestSupport.storedInvoiceLine("2", "0", "0", "0", "0", "TAX21"),
            "{\"unitPrice\":50}", "50", "100", "121"),
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

  // ── ETP-5703: without a discount the price IS the list price (the form's "Precio") ──

  /** 3 x 1.50, no discount, VAT 10 %: invoice FV1000156 of the QA case CP-B3. */
  private static InvoiceLine undiscounted() {
    return LineAmountTestSupport.storedInvoiceLine("3", "1.5", "1.5", "0", "0", "TAX10");
  }

  /** 3 x 1.35 (list 1.50, 10 %), VAT 10 %. */
  private static InvoiceLine discounted() {
    return LineAmountTestSupport.storedInvoiceLine("3", "1.35", "1.5", "10", "0", "TAX10");
  }

  static Stream<Arguments> pricesOnUpdate() {
    return Stream.of(
        // label, stored, patch, unitPrice, listPrice (null: as sent, or absent), net, gross
        Arguments.of("unitPrice only → the list price follows (CP-B3)", undiscounted(),
            "{\"unitPrice\":1.25}", null, "1.25", "3.75", "4.13"),
        Arguments.of("listPrice only → the price follows", undiscounted(),
            "{\"listPrice\":1.25}", "1.25", null, "3.75", "4.13"),
        Arguments.of("unitPrice 0 → list price 0 (the form saves a Precio of 0)", undiscounted(),
            "{\"unitPrice\":0}", null, "0", "0", "0"),
        Arguments.of("discount taken to 0 alone → the price is the stored list price",
            discounted(), "{\"etgoDiscount\":0}", "1.5", null, "4.50", "4.95"),
        Arguments.of("discount 0 alone on a legacy line without list price → price kept",
            LineAmountTestSupport.storedInvoiceLine("10", "25", "0", "0", "0", "TAX10"),
            "{\"etgoDiscount\":0}", null, null, "250", "275"),
        Arguments.of("both prices sent and different → both are the caller's", undiscounted(),
            "{\"unitPrice\":1.25,\"listPrice\":1.5}", null, null, "3.75", "4.13"),
        Arguments.of("SPA side panel, Precio edited → no-op", undiscounted(),
            "{\"listPrice\":1.25,\"unitPrice\":1.25}", null, null, "3.75", "4.13"),
        Arguments.of("SPA side panel, quantity edited → no-op", undiscounted(),
            "{\"invoicedQuantity\":5,\"unitPrice\":1.5}", null, null, "7.50", "8.25"),
        Arguments.of("SPA side panel, quantity edited on a discounted line → no-op",
            discounted(), "{\"invoicedQuantity\":5,\"unitPrice\":1.35}", null, null, "6.75",
            "7.43"),
        // ETP-5714's alignListPriceWithPrice re-writes the stored list price (1.50) into the body
        Arguments.of("SPA side panel, discount taken to 0 → list stays 1.50", discounted(),
            "{\"etgoDiscount\":0,\"unitPrice\":1.5}", null, "1.5", "4.50", "4.95"),
        // ── with a discount: a new net price keeps it and works the list price back ──
        Arguments.of("discounted line, unitPrice only → list = 1.25 / 0.9, discount kept",
            discounted(), "{\"unitPrice\":1.25}", null, "1.388889", "3.75", "4.13"),
        Arguments.of("discounted line, the computed price within half a cent (SPA) → no-op",
            discounted(), "{\"unitPrice\":1.349999}", null, null, "4.05", "4.46"),
        Arguments.of("100 % discount, unitPrice only → nothing to work back, list kept",
            LineAmountTestSupport.storedInvoiceLine("3", "0", "1.5", "100", "0", "TAX10"),
            "{\"unitPrice\":1.25}", null, null, "3.75", "4.13"),
        Arguments.of("discounted line, unitPrice + etgoDiscount → list kept (existing rule)",
            discounted(), "{\"unitPrice\":1.25,\"etgoDiscount\":10}", null, null, "3.75",
            "4.13"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pricesOnUpdate")
  void priceAndListPriceStayCoherentOnUpdate(String label, InvoiceLine stored,
      String patch, String unitPrice, String listPrice, String net, String gross)
      throws Exception {
    JSONObject json = new JSONObject(patch);
    JSONObject sent = new JSONObject(patch);
    NeoContext ctx = NeoContext.builder().specName("sales-invoice").entityName("lines")
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .recordId(LineAmountTestSupport.STORED_LINE_ID).requestBody(json).build();

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(10.0, stored)) {
      InvoiceLineAmountSupport.deriveAmountsOnUpdate(ctx);
    }

    assertPriceOrUntouched(label, unitPrice, sent, json, InvoiceLineAmountSupport.FIELD_UNIT_PRICE);
    assertPriceOrUntouched(label, listPrice, sent, json, InvoiceLineAmountSupport.FIELD_LIST_PRICE);
    assertAmount(label, net, json, InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT);
    assertAmount(label, gross, json, InvoiceLineAmountSupport.FIELD_GROSS_AMOUNT);
  }

  /**
   * {@code expected} (compared exactly: a price keeps 6 decimals) when given; otherwise the key
   * keeps exactly what was sent, or stays absent.
   */
  private static void assertPriceOrUntouched(String label, String expected, JSONObject sent,
      JSONObject body, String key) {
    if (expected != null) {
      assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(
          body.opt(key)))), label + ": " + key + " expected " + expected + ", was " + body.opt(key));
    } else if (sent.has(key)) {
      assertEquals(String.valueOf(sent.opt(key)), String.valueOf(body.opt(key)),
          label + ": " + key + " as sent");
    } else {
      assertFalse(body.has(key), label + ": " + key + " untouched, was " + body.opt(key));
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

  // ── ETP-5881: a line sent without a price takes the invoice price list's ──

  static Stream<Arguments> createWithoutPrice() {
    return Stream.of(
        Arguments.of("only product, quantity and parent → catalog price",
            "{\"product\":\"P1\",\"parentId\":\"I1\",\"invoicedQuantity\":2}", "12", "12"),
        Arguments.of("MCP-style invoice key", "{\"product\":\"P1\",\"invoice\":\"I1\"}", "12",
            "12"),
        Arguments.of("unitPrice 0 counts as not sent",
            "{\"product\":\"P1\",\"parentId\":\"I1\",\"unitPrice\":0}", "12", "12"),
        Arguments.of("the caller's price wins",
            "{\"product\":\"P1\",\"parentId\":\"I1\",\"unitPrice\":9}", "9", null),
        Arguments.of("an explicit gross price wins",
            "{\"product\":\"P1\",\"parentId\":\"I1\",\"grossUnitPrice\":14}", null, null),
        Arguments.of("no product → untouched", "{\"parentId\":\"I1\"}", null, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("createWithoutPrice")
  void setPriceFromPriceListOnCreate(String label, String body, String expectedUnit,
      String expectedList) throws Exception {
    JSONObject json = new JSONObject(body);
    OBDal dal = mock(OBDal.class);
    Product product = mock(Product.class);
    Invoice invoice = mock(Invoice.class);
    PriceList priceList = mock(PriceList.class);
    ProductPrice price = mock(ProductPrice.class);
    Date invoiceDate = new Date();
    when(dal.get(Product.class, "P1")).thenReturn(product);
    when(dal.get(Invoice.class, "I1")).thenReturn(invoice);
    when(invoice.getInvoiceDate()).thenReturn(invoiceDate);
    when(invoice.getPriceList()).thenReturn(priceList);
    when(invoice.isSalesTransaction()).thenReturn(Boolean.TRUE);
    when(priceList.isPriceIncludesTax()).thenReturn(Boolean.FALSE);
    when(price.getListPrice()).thenReturn(new BigDecimal("12"));
    when(price.getStandardPrice()).thenReturn(new BigDecimal("12"));
    NeoContext ctx = NeoContext.builder().specName("sales-invoice").entityName("lines")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(json).build();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<FinancialUtils> financial = mockStatic(FinancialUtils.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      financial.when(() -> FinancialUtils.getProductPrice(eq(product), eq(invoiceDate),
          anyBoolean(), eq(priceList), eq(false))).thenReturn(price);

      InvoiceLineAmountSupport.setPriceFromPriceListOnCreate(ctx);
    }

    assertAmount(label, expectedUnit, json, InvoiceLineAmountSupport.FIELD_UNIT_PRICE);
    assertAmount(label, expectedUnit != null && expectedList != null ? expectedList : null, json,
        InvoiceLineAmountSupport.FIELD_LIST_PRICE);
  }

  @Test
  void setPriceFromPriceListOnCreateAbstainsOnATaxIncludedList() throws Exception {
    JSONObject json = new JSONObject("{\"product\":\"P1\",\"parentId\":\"I1\"}");
    OBDal dal = mock(OBDal.class);
    Invoice invoice = mock(Invoice.class);
    PriceList priceList = mock(PriceList.class);
    when(dal.get(Product.class, "P1")).thenReturn(mock(Product.class));
    when(dal.get(Invoice.class, "I1")).thenReturn(invoice);
    when(invoice.getInvoiceDate()).thenReturn(new Date());
    when(invoice.getPriceList()).thenReturn(priceList);
    when(priceList.isPriceIncludesTax()).thenReturn(Boolean.TRUE);
    NeoContext ctx = NeoContext.builder().specName("sales-invoice").entityName("lines")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(json).build();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      InvoiceLineAmountSupport.setPriceFromPriceListOnCreate(ctx);
    }

    assertAmount("tax-included list", null, json, InvoiceLineAmountSupport.FIELD_UNIT_PRICE);
  }

  /** ETP-5881: the client's listPrice is the price; the price list is not applied to it. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("clientListPrice")
  void setPriceFromPriceListOnCreateUsesTheClientListPrice(String label, String extra,
      String expectedUnit) throws Exception {
    JSONObject json = new JSONObject(extra);
    json.put("product", "P1").put("parentId", "I1");
    NeoContext ctx = NeoContext.builder().specName("sales-invoice").entityName("lines")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(json).build();
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      InvoiceLineAmountSupport.setPriceFromPriceListOnCreate(ctx);
      dalMock.verifyNoInteractions();
    }
    assertAmount(label, expectedUnit, json, InvoiceLineAmountSupport.FIELD_UNIT_PRICE);
  }

  static Stream<Arguments> clientListPrice() {
    return Stream.of(
        Arguments.of("list 20, no discount", "{\"listPrice\":20}", "20"),
        Arguments.of("list 20, discount 10", "{\"listPrice\":20,\"etgoDiscount\":10}", "18"),
        Arguments.of("unitPrice 0 counts as not sent", "{\"listPrice\":20,\"unitPrice\":0}",
            "20"),
        Arguments.of("the caller's unitPrice wins", "{\"listPrice\":20,\"unitPrice\":15}", "15"),
        Arguments.of("discount out of range → untouched", "{\"listPrice\":20,\"etgoDiscount\":120}",
            null));
  }

  // ── ETP-5710 point 2: taxableAmount (TaxBaseAmt) is the final net, after the discount ──

  static Stream<Arguments> taxableOnCreate() {
    return Stream.of(
        // label, body, expected taxableAmount
        Arguments.of("discount 2 % → 245, not the stale 250",
            "{\"invoicedQuantity\":10,\"unitPrice\":25,\"etgoDiscount\":2" + TAX
                + ",\"taxableAmount\":250}", "245"),
        Arguments.of("absent → derived", "{\"invoicedQuantity\":10,\"unitPrice\":18" + TAX + "}",
            "180"),
        Arguments.of("stale 0 → derived", "{\"invoicedQuantity\":10,\"unitPrice\":18" + TAX
            + ",\"taxableAmount\":0}", "180"),
        Arguments.of("100 % discount → 0 is written, not the undiscounted 250",
            "{\"invoicedQuantity\":10,\"unitPrice\":25,\"etgoDiscount\":100" + TAX
                + ",\"taxableAmount\":250}", "0"),
        Arguments.of("price 0 → 0", "{\"invoicedQuantity\":10,\"unitPrice\":0" + TAX
            + ",\"taxableAmount\":250}", "0"),
        Arguments.of("the caller's own net is followed", "{\"invoicedQuantity\":10,\"unitPrice\":18"
            + TAX + ",\"lineNetAmount\":150}", "150"),
        Arguments.of("price 0 with a surviving body net → equals that stored net",
            "{\"invoicedQuantity\":10,\"unitPrice\":0" + TAX + ",\"lineNetAmount\":99}", "99"),
        Arguments.of("return line keeps its sign", "{\"invoicedQuantity\":-10,\"unitPrice\":18"
            + TAX + "}", "-180"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("taxableOnCreate")
  void taxableAmountIsTheFinalNetOnCreate(String label, String body, String expected)
      throws Exception {
    for (String spec : new String[] {"sales-invoice", "purchase-invoice"}) {
      JSONObject json = new JSONObject(body);
      NeoContext ctx = NeoContext.builder().specName(spec).entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(json).build();

      try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
        InvoiceLineAmountSupport.deriveAmountsOnCreate(ctx);
      }

      assertAmount(label + " / " + spec, expected, json,
          InvoiceLineAmountSupport.FIELD_TAXABLE_AMOUNT);
    }
  }

  static Stream<Arguments> taxableOnUpdate() {
    return Stream.of(
        // label, patch, expected taxableAmount (null: not written)
        Arguments.of("quantity only → 490", "{\"invoicedQuantity\":20}", "490"),
        Arguments.of("discount only → 225", "{\"etgoDiscount\":10}", "225"),
        Arguments.of("price only (list) → 294", "{\"listPrice\":30}", "294"),
        Arguments.of("100 % discount → 0", "{\"etgoDiscount\":100}", "0"),
        Arguments.of("the caller's own net is followed", "{\"invoicedQuantity\":20,\"lineNetAmount\":480}",
            "480"),
        Arguments.of("an explicit taxableAmount is the caller's",
            "{\"invoicedQuantity\":20,\"taxableAmount\":123}", "123"),
        Arguments.of("nothing the amounts depend on changes → untouched",
            "{\"project\":\"P1\"}", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("taxableOnUpdate")
  void taxableAmountFollowsTheNetOnUpdate(String label, String patch, String expected)
      throws Exception {
    for (String spec : new String[] {"sales-invoice", "purchase-invoice"}) {
      JSONObject json = new JSONObject(patch);
      NeoContext ctx = NeoContext.builder().specName(spec).entityName("lines")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .recordId(LineAmountTestSupport.STORED_LINE_ID).requestBody(json).build();

      try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0, storedLine())) {
        InvoiceLineAmountSupport.deriveAmountsOnUpdate(ctx);
      }

      if (expected == null) {
        assertFalse(json.has(InvoiceLineAmountSupport.FIELD_TAXABLE_AMOUNT), label);
      } else {
        assertAmount(label + " / " + spec, expected, json,
            InvoiceLineAmountSupport.FIELD_TAXABLE_AMOUNT);
      }
    }
  }
}
