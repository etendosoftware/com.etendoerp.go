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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.service.json.DataResolvingMode;
import org.openbravo.service.json.DataToJsonConverter;

import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;

/**
 * The line-price rules of the sales order and sales quotation line customizations (ETP-5528).
 *
 * <p>Live-verified values used throughout (Fernet list: standard 18, 21 % VAT, qty 10): no discount
 * → 18 / 180 / 217.80; discount 5 → 17.10 / 171 / 206.91; an explicit unitPrice 20 wins.
 *
 * <p>{@code applyDiscount}'s re-fire itself runs the real {@code SL_Order_Amt} and is covered by the
 * cascade helper's trigger-field tests; what is pinned here is every decision around it, which is
 * where the live bugs were (a discount applied over an explicit price, a discount 0 that did not
 * restore the list price, a stale gross kept as if it were the caller's).
 *
 * <p>QA reopen (CP-7, CP-8, CP-9): an update of the quantity alone left a stale gross, a product
 * with no list price was created with {@code listPrice 0}, and an update of the price alone kept
 * the old discount. Each has its own section below, with the form's own payload as a no-op row.
 *
 * @covers com.etendoerp.go.schemaforge.OrderLineDiscountSupport
 */
class OrderLineDiscountSupportTest {

  private static final String SPEC = "sales-order";
  private static final String ENTITY = "lines";
  private static final String TAX_ID = "TAX21";

  private static NeoContext ctx(String method, NeoEndpointType type, JSONObject body) {
    return NeoContext.builder()
        .specName(SPEC)
        .entityName(ENTITY)
        .httpMethod(method)
        .endpointType(type)
        .requestBody(body)
        .build();
  }

  /** A comma-separated key list as a set; blank is the empty set. */
  private static Set<String> keys(String csv) {
    return csv.isEmpty() ? new HashSet<>()
        : Arrays.stream(csv.split(",")).collect(Collectors.toSet());
  }

  // ── shouldApplyDiscount: the form's rule ───────────────────────────────

  static Stream<Arguments> discountRule() {
    return Stream.of(
        Arguments.of("no unitPrice, discount 5", "{\"discount\":5}", "18", true),
        Arguments.of("no unitPrice, discount 0 restores the list price", "{\"discount\":0}", "18",
            true),
        Arguments.of("unitPrice equals the list price at another scale",
            "{\"discount\":5,\"unitPrice\":18}", new BigDecimal("18.00"), true),
        Arguments.of("unitPrice already discounted", "{\"discount\":5,\"unitPrice\":\"17.10\"}",
            "18", false),
        Arguments.of("explicit unitPrice above the list price wins",
            "{\"discount\":5,\"unitPrice\":20}", "18", false),
        Arguments.of("unitPrice equals the list price with discount 0",
            "{\"discount\":0,\"unitPrice\":18}", "18", false),
        Arguments.of("non-zero grossUnitPrice is an explicit price",
            "{\"discount\":5,\"grossUnitPrice\":21.78}", "18", false),
        Arguments.of("zero grossUnitPrice follows the unitPrice rule (applies)",
            "{\"discount\":5,\"unitPrice\":18,\"grossUnitPrice\":0}", "18", true),
        Arguments.of("zero grossUnitPrice follows the unitPrice rule (explicit price)",
            "{\"discount\":5,\"unitPrice\":20,\"grossUnitPrice\":0}", "18", false),
        Arguments.of("discount null", "{\"discount\":null}", "18", false),
        Arguments.of("discount absent", "{\"unitPrice\":18}", "18", false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("discountRule")
  void shouldApplyDiscount(String label, String body, Object listPrice, boolean expected)
      throws Exception {
    assertEquals(expected,
        OrderLineDiscountSupport.shouldApplyDiscount(new JSONObject(body), listPrice), label);
  }

  // ── alignStandardPriceWithCurrentPrice: the callout's INPUT only ──────

  /**
   * The stored line carries the undiscounted standard price (18 / gross 21.78); the callout must
   * measure the discount against the line's current price instead, or a discount going back to 0
   * compares 0 with 0 and changes nothing.
   */
  static Stream<Arguments> standardPriceAlignment() {
    return Stream.of(
        Arguments.of("numeric unitPrice becomes the standard price", "{\"unitPrice\":17.1}",
            "17.1", "21.78"),
        Arguments.of("numeric unitPrice as a string", "{\"unitPrice\":\"16.20\"}", "16.2",
            "21.78"),
        Arguments.of("absent unitPrice keeps the standard price", "{}", "18", "21.78"),
        Arguments.of("non-numeric unitPrice keeps the standard price", "{\"unitPrice\":\"abc\"}",
            "18", "21.78"),
        Arguments.of("zero grossUnitPrice keeps the gross standard price",
            "{\"unitPrice\":17.1,\"grossUnitPrice\":0}", "17.1", "21.78"),
        Arguments.of("non-zero grossUnitPrice becomes the gross standard price",
            "{\"unitPrice\":17.1,\"grossUnitPrice\":20.69}", "17.1", "20.69"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("standardPriceAlignment")
  void alignStandardPriceWithCurrentPrice(String label, String prices, String expectedStd,
      String expectedGrossStd) throws Exception {
    JSONObject formState = new JSONObject(prices)
        .put(OrderLineDiscountSupport.FIELD_STANDARD_PRICE, "18")
        .put(OrderLineDiscountSupport.FIELD_GROSS_STANDARD_PRICE, "21.78");

    OrderLineDiscountSupport.alignStandardPriceWithCurrentPrice(formState);

    assertAmount(label, expectedStd, formState, OrderLineDiscountSupport.FIELD_STANDARD_PRICE);
    assertAmount(label, expectedGrossStd, formState,
        OrderLineDiscountSupport.FIELD_GROSS_STANDARD_PRICE);
  }

  // ── setStandardPriceOnCreate: every guard abstains before any read ────

  static Stream<Arguments> standardPriceGuards() {
    String full = "{\"product\":\"P1\",\"salesOrder\":\"O1\",\"unitPrice\":20}";
    return Stream.of(
        Arguments.of("null context", null, null, null),
        Arguments.of("PATCH", "PATCH", NeoEndpointType.CRUD, full),
        Arguments.of("PUT", "PUT", NeoEndpointType.CRUD, full),
        Arguments.of("non-CRUD POST", "POST", NeoEndpointType.CALLOUT, full),
        Arguments.of("null body", "POST", NeoEndpointType.CRUD, null),
        Arguments.of("no product", "POST", NeoEndpointType.CRUD,
            "{\"salesOrder\":\"O1\",\"unitPrice\":20}"),
        Arguments.of("no parent order", "POST", NeoEndpointType.CRUD,
            "{\"product\":\"P1\",\"unitPrice\":20}"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("standardPriceGuards")
  void setStandardPriceOnCreateGuards(String label, String method, NeoEndpointType type,
      String body) throws Exception {
    JSONObject json = body == null ? null : new JSONObject(body);
    NeoContext context = method == null ? null : ctx(method, type, json);

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
      OrderLineDiscountSupport.setStandardPriceOnCreate(context);

      dal.verifyNoInteractions();
    }
    if (json != null) {
      assertEquals(new JSONObject(body).toString(), json.toString(),
          label + ": the body must be untouched");
    }
  }

  // ── deriveAmountsOnCreate: the stale rule on lineGrossAmount ──────────

  /** Fernet, qty 10 at 18, 21 % VAT: the policy derives lineGrossAmount 217.80. */
  static Stream<Arguments> grossOnCreate() {
    String line = "\"orderedQuantity\":10,\"unitPrice\":18,\"tax\":\"" + TAX_ID + "\"";
    return Stream.of(
        Arguments.of("absent → derived", "POST", "{" + line + "}", "217.80"),
        Arguments.of("zero → derived", "POST", "{" + line + ",\"lineGrossAmount\":0}", "217.80"),
        Arguments.of("equal at scale → derived", "POST",
            "{" + line + ",\"lineGrossAmount\":217.804}", "217.80"),
        Arguments.of("the caller's value → kept", "POST",
            "{" + line + ",\"lineGrossAmount\":200}", "200"),
        Arguments.of("discounted price (17.10) → derived over it", "POST",
            "{\"orderedQuantity\":10,\"unitPrice\":17.1,\"tax\":\"" + TAX_ID + "\"}", "206.91"),
        Arguments.of("no tax → untouched", "POST", "{\"orderedQuantity\":10,\"unitPrice\":18}",
            null),
        Arguments.of("null tax → untouched", "POST",
            "{\"orderedQuantity\":10,\"unitPrice\":18,\"tax\":null}", null),
        Arguments.of("PATCH → untouched", "PATCH", "{" + line + "}", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("grossOnCreate")
  void deriveAmountsOnCreate(String label, String method, String body, String expectedGross)
      throws Exception {
    JSONObject json = new JSONObject(body);

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      OrderLineDiscountSupport.deriveAmountsOnCreate(ctx(method, NeoEndpointType.CRUD, json));
    }

    assertAmount(label, expectedGross, json, OrderLineDiscountSupport.FIELD_LINE_GROSS_AMOUNT);
    assertAmount(label, null, json, OrderLineDiscountSupport.FIELD_LINE_NET_AMOUNT);
  }

  // ── serverDerivedAmounts: the create-side stale rule, judged by value ─

  /** qty 10 × 18, 21 % VAT: the undiscounted price yields net 180 and gross 217.80. */
  static Stream<Arguments> serverDerived() {
    return Stream.of(
        Arguments.of("net = qty × price → server-derived",
            "\"lineNetAmount\":180,\"lineGrossAmount\":200", true, false),
        Arguments.of("net 150 → the caller's", "\"lineNetAmount\":150,\"lineGrossAmount\":200",
            false, false),
        Arguments.of("net 0 → server-derived", "\"lineNetAmount\":0,\"lineGrossAmount\":200",
            true, false),
        Arguments.of("gross 0 → server-derived", "\"lineNetAmount\":150,\"lineGrossAmount\":0",
            false, true),
        Arguments.of("gross equal to the derived 217.80 → server-derived",
            "\"lineNetAmount\":150,\"lineGrossAmount\":217.8", false, true),
        Arguments.of("both absent → server-derived", "", true, true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("serverDerived")
  void serverDerivedAmounts(String label, String amounts, boolean netDerived,
      boolean grossDerived) throws Exception {
    String line = "\"orderedQuantity\":10,\"unitPrice\":18,\"tax\":\"" + TAX_ID + "\"";
    JSONObject body = new JSONObject("{" + line + (amounts.isEmpty() ? "" : "," + amounts) + "}");

    Set<String> derived;
    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      derived = OrderLineDiscountSupport.serverDerivedAmounts(body);
    }

    assertEquals(netDerived, derived.contains(OrderLineDiscountSupport.FIELD_LINE_NET_AMOUNT),
        label + ": lineNetAmount");
    assertEquals(grossDerived, derived.contains(OrderLineDiscountSupport.FIELD_LINE_GROSS_AMOUNT),
        label + ": lineGrossAmount");
  }

  // ── protectedFields: what the discount re-fire may not overwrite ──────

  static Stream<Arguments> protectedFieldRows() {
    return Stream.of(
        Arguments.of("unitPrice is re-derived, other caller keys are kept",
            "{\"discount\":5,\"unitPrice\":18,\"orderedQuantity\":10}", "",
            "discount,orderedQuantity"),
        Arguments.of("a zero grossUnitPrice is re-derived",
            "{\"discount\":5,\"grossUnitPrice\":0}", "", "discount"),
        Arguments.of("a non-zero grossUnitPrice is the caller's",
            "{\"discount\":5,\"grossUnitPrice\":21.78}", "", "discount,grossUnitPrice"),
        Arguments.of("stale amounts are re-derived",
            "{\"discount\":5,\"lineNetAmount\":180,\"lineGrossAmount\":0}",
            "lineNetAmount,lineGrossAmount", "discount"),
        Arguments.of("a caller's amount stays protected next to a stale one",
            "{\"discount\":5,\"lineNetAmount\":150,\"lineGrossAmount\":0}", "lineGrossAmount",
            "discount,lineNetAmount"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("protectedFieldRows")
  void protectedFields(String label, String body, String stale, String expected)
      throws Exception {
    assertEquals(keys(expected),
        OrderLineDiscountSupport.protectedFields(new JSONObject(body), keys(stale)), label);
  }

  // ── dropStaleGrossAfterRefire: only a server-derived gross goes ───────

  static Stream<Arguments> staleGrossRows() {
    return Stream.of(
        Arguments.of("stale gross is removed", "lineGrossAmount", null),
        Arguments.of("the caller's gross is kept", "", "200"),
        Arguments.of("a stale net does not remove the gross", "lineNetAmount", "200"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("staleGrossRows")
  void dropStaleGrossAfterRefire(String label, String stale, String expectedGross)
      throws Exception {
    JSONObject body = new JSONObject("{\"lineNetAmount\":\"171\",\"lineGrossAmount\":200}");

    OrderLineDiscountSupport.dropStaleGrossAfterRefire(body, keys(stale));

    assertAmount(label, expectedGross, body, OrderLineDiscountSupport.FIELD_LINE_GROSS_AMOUNT);
    assertEquals("171", body.opt(OrderLineDiscountSupport.FIELD_LINE_NET_AMOUNT),
        label + ": lineNetAmount is the callout's and stays");
  }

  // ── deriveAmountsOnUpdate: never over what the caller sent ────────────

  /** Stored line: qty 10 at 18, 21 % VAT, a stale stored gross 217.80. */
  static Stream<Arguments> amountsOnUpdate() {
    return Stream.of(
        Arguments.of("absent from the sent body → derived over the re-fired price",
            "{\"discount\":5,\"unitPrice\":\"17.10\"}", "{\"discount\":5}", "206.91"),
        Arguments.of("sent by the caller → kept",
            "{\"discount\":5,\"unitPrice\":\"17.10\",\"lineGrossAmount\":200}",
            "{\"discount\":5,\"lineGrossAmount\":200}", "200"),
        Arguments.of("patched quantity wins over the stored one",
            "{\"discount\":5,\"orderedQuantity\":20,\"unitPrice\":\"17.10\"}",
            "{\"discount\":5,\"orderedQuantity\":20}", "413.82"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("amountsOnUpdate")
  void deriveAmountsOnUpdate(String label, String body, String sent, String expectedGross)
      throws Exception {
    JSONObject json = new JSONObject(body);
    JSONObject formState = new JSONObject("{\"orderedQuantity\":10,\"unitPrice\":18,\"tax\":\""
        + TAX_ID + "\",\"lineGrossAmount\":217.8}");

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      OrderLineDiscountSupport.deriveAmountsOnUpdate(json, formState, new JSONObject(sent));
    }

    assertAmount(label, expectedGross, json, OrderLineDiscountSupport.FIELD_LINE_GROSS_AMOUNT);
    assertAmount(label, null, json, OrderLineDiscountSupport.FIELD_LINE_NET_AMOUNT);
  }

  /**
   * ETP-5703 (ETP-5528 merge safety): the 3-argument overload rounds to the line's currency
   * precision on its own. (a) No {@code currency} on the line → 2 decimals, and the currency is
   * never looked up (no DAL read of {@code Currency}).
   */
  @org.junit.jupiter.api.Test
  void deriveAmountsOnUpdate_noCurrency_roundsToTwoWithoutCurrencyLookup() throws Exception {
    JSONObject json = new JSONObject("{\"discount\":5,\"unitPrice\":\"17.10\"}");
    JSONObject formState = new JSONObject("{\"orderedQuantity\":10,\"unitPrice\":18,\"tax\":\""
        + TAX_ID + "\"}");

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      OBDal instance = OBDal.getInstance();
      OrderLineDiscountSupport.deriveAmountsOnUpdate(json, formState,
          new JSONObject("{\"discount\":5}"));
      org.mockito.Mockito.verify(instance, org.mockito.Mockito.never())
          .get(org.mockito.ArgumentMatchers.eq(org.openbravo.model.common.currency.Currency.class),
              any());
    }

    assertEquals(0, new BigDecimal("206.91").compareTo(
        new BigDecimal(String.valueOf(json.get(OrderLineDiscountSupport.FIELD_LINE_GROSS_AMOUNT)))));
  }

  /**
   * (b) A line in a currency with precision 0 rounds to whole units: 10 × 17.10 at 21 % = 206.91
   * → 207. The lookup is stubbed at {@code NeoCurrencyPrecision.scaleOfCurrencyId}.
   */
  @org.junit.jupiter.api.Test
  void deriveAmountsOnUpdate_lineCurrencyPrecisionZero_roundsToUnits() throws Exception {
    JSONObject json = new JSONObject("{\"discount\":5,\"unitPrice\":\"17.10\"}");
    JSONObject formState = new JSONObject("{\"orderedQuantity\":10,\"unitPrice\":18,\"tax\":\""
        + TAX_ID + "\",\"currency\":\"jpy-id\"}");

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0);
        MockedStatic<NeoCurrencyPrecision> precision = mockStatic(NeoCurrencyPrecision.class,
            org.mockito.Mockito.CALLS_REAL_METHODS)) {
      precision.when(() -> NeoCurrencyPrecision.scaleOfCurrencyId("jpy-id")).thenReturn(0);
      OrderLineDiscountSupport.deriveAmountsOnUpdate(json, formState,
          new JSONObject("{\"discount\":5}"));
    }

    assertEquals(0, new BigDecimal("207").compareTo(
        new BigDecimal(String.valueOf(json.get(OrderLineDiscountSupport.FIELD_LINE_GROSS_AMOUNT)))));
  }

  // ── coerceDerivedValues (private): through applyDiscount on a create ──

  /**
   * The re-fire is stubbed to write {@code calloutWrites} into the body, and the type coercion to
   * turn every string it is handed into a number. Only the strings the callout wrote, and that
   * differ from the body before the re-fire, may reach it.
   */
  static Stream<Arguments> derivedCoercion() {
    return Stream.of(
        Arguments.of("callout strings are coerced",
            "{\"unitPrice\":\"17.10\",\"lineNetAmount\":\"171\"}", "unitPrice,lineNetAmount"),
        Arguments.of("a string equal to the body before is not coerced again",
            "{\"unitPrice\":\"17.10\",\"tax\":\"" + TAX_ID + "\"}", "unitPrice"),
        Arguments.of("a non-string callout value is not coerced", "{\"unitPrice\":17.1}", ""));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("derivedCoercion")
  void applyDiscountCoercesOnlyDerivedStrings(String label, String calloutWrites,
      String expectedCoerced) throws Exception {
    JSONObject body = new JSONObject("{\"discount\":5,\"listPrice\":18,\"unitPrice\":18,"
        + "\"orderedQuantity\":10,\"tax\":\"" + TAX_ID + "\",\"product\":\"P1\"}");
    JSONObject writes = new JSONObject(calloutWrites);
    Tab tab = mock(Tab.class);
    NeoContext context = NeoContext.builder()
        .specName(SPEC)
        .entityName(ENTITY)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .adTab(tab)
        .build();
    Set<String> coerced = new HashSet<>();

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0);
        MockedStatic<NeoDefaultsCascadeHelper> cascade = mockStatic(NeoDefaultsCascadeHelper.class);
        MockedStatic<NeoTypeCoercionHelper> coercion = mockStatic(NeoTypeCoercionHelper.class)) {
      cascade.when(() -> NeoDefaultsCascadeHelper.executeCalloutsForTriggerFields(any(), any(),
          any(), any(), any(), any(), any())).thenAnswer(inv -> {
            JSONObject target = inv.getArgument(3);
            Iterator<String> it = writes.keys();
            while (it.hasNext()) {
              String key = it.next();
              target.put(key, writes.get(key));
            }
            return null;
          });
      coercion.when(() -> NeoTypeCoercionHelper.coerceTypes(any(JSONObject.class), anyString()))
          .thenAnswer(inv -> {
            JSONObject data = inv.getArgument(0);
            Iterator<String> it = data.keys();
            while (it.hasNext()) {
              String key = it.next();
              coerced.add(key);
              data.put(key, new BigDecimal(data.getString(key)));
            }
            return null;
          });

      OrderLineDiscountSupport.applyDiscount(context);
    }

    assertEquals(keys(expectedCoerced), coerced, label);
    for (String key : coerced) {
      assertInstanceOf(BigDecimal.class, body.get(key), label + ": " + key + " written back");
    }
    assertEquals(TAX_ID, body.get(OrderLineDiscountSupport.FIELD_TAX),
        label + ": the caller's tax is untouched");
    assertEquals("P1", body.get(OrderLineDiscountSupport.FIELD_PRODUCT),
        label + ": the caller's product is untouched");
  }

  // ── CP-8: a product with no list price gets its standard price as list price, as in the form ──

  static Stream<Arguments> listPriceFromStandardRows() {
    return Stream.of(
        Arguments.of("no catalog list price, priced at the standard price → the standard price",
            "{\"unitPrice\":50,\"listPrice\":0}", "0", "50", "50"),
        Arguments.of("listPrice absent → the standard price", "{\"unitPrice\":50}", "0", "50",
            "50"),
        Arguments.of("no list price entry at all → the standard price", "{\"unitPrice\":50}", null,
            "50", "50"),
        Arguments.of("the form already sends it → untouched",
            "{\"unitPrice\":50,\"listPrice\":50}", "0", "50", null),
        Arguments.of("a line priced at 0 by the user → untouched",
            "{\"unitPrice\":0,\"listPrice\":0}", "0", "50", null),
        Arguments.of("the caller's own price → untouched", "{\"unitPrice\":60,\"listPrice\":0}",
            "0", "50", null),
        Arguments.of("the catalog has a list price → untouched",
            "{\"unitPrice\":18,\"listPrice\":0}", "18", "18", null),
        Arguments.of("no standard price either → untouched", "{\"unitPrice\":0}", "0", "0", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("listPriceFromStandardRows")
  void listPriceFromStandard(String label, String body, String catalogList, String catalogStd,
      String expected) throws Exception {
    BigDecimal listPrice = OrderLineDiscountSupport.listPriceFromStandard(new JSONObject(body),
        catalogList == null ? null : new BigDecimal(catalogList), new BigDecimal(catalogStd));

    assertEquals(expected == null ? null : new BigDecimal(expected), listPrice, label);
  }

  /**
   * QA quotations 1000010 (MCP) vs 1000009 (UI), "Servicio de instalación": price list entry with
   * list price 0 and standard price 50. The MCP body (after {@code McpLinePriceInjector}) must end
   * as the UI one does: list 50, standard 50. The UI body is the no-op row.
   */
  static Stream<Arguments> createWithoutCatalogListPrice() {
    return Stream.of(
        Arguments.of("etendo_create after the price injector",
            "{\"product\":\"P1\",\"salesOrder\":\"O1\",\"unitPrice\":50,\"listPrice\":0}"),
        Arguments.of("the form's add-line POST (unchanged)",
            "{\"product\":\"P1\",\"parentId\":\"O1\",\"unitPrice\":50,\"listPrice\":50,"
                + "\"discount\":0}"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("createWithoutCatalogListPrice")
  void setStandardPriceOnCreateFillsTheListPriceAsTheForm(String label, String body)
      throws Exception {
    JSONObject json = new JSONObject(body);
    OBDal dal = mock(OBDal.class);
    Product product = mock(Product.class);
    Order order = mock(Order.class);
    PriceList priceList = mock(PriceList.class);
    ProductPrice price = mock(ProductPrice.class);
    Date orderDate = new Date();
    when(dal.get(Product.class, "P1")).thenReturn(product);
    when(dal.get(Order.class, "O1")).thenReturn(order);
    when(order.getOrderDate()).thenReturn(orderDate);
    when(order.getPriceList()).thenReturn(priceList);
    when(order.isSalesTransaction()).thenReturn(Boolean.TRUE);
    when(priceList.isPriceIncludesTax()).thenReturn(Boolean.FALSE);
    when(price.getListPrice()).thenReturn(BigDecimal.ZERO);
    when(price.getStandardPrice()).thenReturn(new BigDecimal("50"));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<FinancialUtils> financial = mockStatic(FinancialUtils.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      financial.when(() -> FinancialUtils.getProductPrice(eq(product), eq(orderDate),
          anyBoolean(), eq(priceList), eq(false))).thenReturn(price);

      OrderLineDiscountSupport.setStandardPriceOnCreate(ctx("POST", NeoEndpointType.CRUD, json));
    }

    assertAmount(label, "50", json, OrderLineDiscountSupport.FIELD_LIST_PRICE);
    assertAmount(label, "50", json, OrderLineDiscountSupport.FIELD_STANDARD_PRICE);
    assertAmount(label, "50", json, OrderLineDiscountSupport.FIELD_UNIT_PRICE);
  }

  // ── CP-7: an update of quantity, price or tax refreshes the gross ─────

  private static final String LINE_ID = "L1";

  /**
   * QA quotation 1000008 line 10, Monitor: list 300, discount 20 → price 240, qty 10, 21 % VAT,
   * with the stale gross QA saw (1161.60).
   */
  private static final String STORED_MONITOR_LINE = "{\"orderedQuantity\":10,\"listPrice\":300,"
      + "\"discount\":20,\"unitPrice\":240,\"tax\":\"" + TAX_ID + "\",\"lineNetAmount\":2400,"
      + "\"lineGrossAmount\":1161.6}";

  /**
   * Stubs {@code dal} to answer the stored line {@value #LINE_ID}, and opens a static
   * {@link OBProvider} whose converter reads it as {@code stored}. Every mock is a local stubbed on
   * its own line (no nested stubbing); the caller closes the returned mock.
   */
  private static MockedStatic<OBProvider> storedLine(OBDal dal, String stored) throws Exception {
    OrderLine line = mock(OrderLine.class);
    DataToJsonConverter converter = mock(DataToJsonConverter.class);
    OBProvider provider = mock(OBProvider.class);
    JSONObject storedJson = new JSONObject(stored);
    when(dal.get(OrderLine.class, LINE_ID)).thenReturn(line);
    when(converter.toJsonObject(line, DataResolvingMode.FULL)).thenReturn(storedJson);
    when(provider.get(DataToJsonConverter.class)).thenReturn(converter);
    MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
    providerMock.when(OBProvider::getInstance).thenReturn(provider);
    return providerMock;
  }

  private static NeoContext updateCtx(String method, JSONObject body, Tab adTab) {
    return NeoContext.builder()
        .specName(SPEC)
        .entityName(ENTITY)
        .httpMethod(method)
        .endpointType(NeoEndpointType.CRUD)
        .recordId(LINE_ID)
        .requestBody(body)
        .adTab(adTab)
        .build();
  }

  static Stream<Arguments> amountsOnInputUpdate() {
    return Stream.of(
        Arguments.of("CP-7: quantity alone → gross over the new quantity (6 × 240 × 1.21)",
            "PATCH", "{\"orderedQuantity\":6}", false, "1742.40", 1),
        Arguments.of("PUT behaves as PATCH", "PUT", "{\"orderedQuantity\":6}", false, "1742.40",
            1),
        Arguments.of("form side panel: applyUnitPrice abstains, the gross reuses its read",
            "PATCH", "{\"orderedQuantity\":6,\"unitPrice\":240}", true, "1742.40", 1),
        Arguments.of("a gross the caller sent is kept, nothing read", "PATCH",
            "{\"orderedQuantity\":6,\"lineGrossAmount\":1000}", false, "1000", 0),
        Arguments.of("no amount input in the patch → untouched, nothing read", "PATCH",
            "{\"description\":\"x\"}", false, null, 0));
  }

  /**
   * Through {@code applyLineRules}: no discount, so the discount re-fire never runs. With a tab,
   * {@code applyUnitPrice} reads the stored line and abstains (the price is the one the line
   * already has); the stored line is read at most once per request either way.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("amountsOnInputUpdate")
  void updateOfAnAmountInputRefreshesTheGross(String label, String method, String body,
      boolean withTab, String expectedGross, int expectedReads) throws Exception {
    JSONObject json = new JSONObject(body);
    OBDal dal = mock(OBDal.class);
    Tab tab = withTab ? mock(Tab.class) : null;

    try (MockedStatic<OBDal> dalMock = LineAmountTestSupport.taxRate(21.0, dal);
        MockedStatic<OBProvider> provider = storedLine(dal, STORED_MONITOR_LINE);
        MockedStatic<NeoDefaultsCascadeHelper> cascade = mockStatic(NeoDefaultsCascadeHelper.class)) {
      OrderLineDiscountSupport.applyLineRules(updateCtx(method, json, tab));

      cascade.verifyNoInteractions();
    }

    assertAmount(label, expectedGross, json, OrderLineDiscountSupport.FIELD_LINE_GROSS_AMOUNT);
    assertAmount(label, null, json, OrderLineDiscountSupport.FIELD_LINE_NET_AMOUNT);
    verify(dal, times(expectedReads)).get(OrderLine.class, LINE_ID);
  }

  /**
   * ETP-6012: a public API {@code PATCH} of the quantity alone. {@code lineGrossAmount} is read-only
   * on REST, so the save derives it again over the body: the body must carry the stored price and
   * tax, or the gross is zeroed (no price) or taken at 0 % (no tax).
   */
  @Test
  void sparseUpdateCarriesTheStoredPriceAndTax() throws Exception {
    JSONObject json = new JSONObject("{\"orderedQuantity\":3}");
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> dalMock = LineAmountTestSupport.taxRate(21.0, dal);
        MockedStatic<OBProvider> provider = storedLine(dal, STORED_MONITOR_LINE)) {
      OrderLineDiscountSupport.applyLineRules(updateCtx("PATCH", json, null));
    }

    assertEquals(3, json.getInt(OrderLineDiscountSupport.FIELD_ORDERED_QTY), "the caller's quantity is kept");
    assertEquals(240, json.getInt(OrderLineDiscountSupport.FIELD_UNIT_PRICE));
    assertEquals(TAX_ID, json.getString(OrderLineDiscountSupport.FIELD_TAX));
    verify(dal, times(1)).get(OrderLine.class, LINE_ID);
  }

  @Test
  void sparseUpdateNeverOverwritesAnInputTheCallerSent() throws Exception {
    JSONObject json = new JSONObject("{\"orderedQuantity\":3,\"tax\":\"OTHER\"}");
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> dalMock = LineAmountTestSupport.taxRate(21.0, dal);
        MockedStatic<OBProvider> provider = storedLine(dal, STORED_MONITOR_LINE)) {
      OrderLineDiscountSupport.completeAmountInputsOnUpdate(updateCtx("PATCH", json, null));
    }

    assertEquals("OTHER", json.getString(OrderLineDiscountSupport.FIELD_TAX));
    assertEquals(240, json.getInt(OrderLineDiscountSupport.FIELD_UNIT_PRICE));
  }

  /**
   * On a tax-included line the stored gross price would win the gross derivation, so a new net
   * price must not carry it; a new quantity still does.
   */
  @Test
  void aNewUnitPriceNeverCarriesTheStoredGrossPrice() throws Exception {
    String taxIncludedLine = "{\"orderedQuantity\":2,\"unitPrice\":10,\"grossUnitPrice\":12.1,"
        + "\"tax\":\"" + TAX_ID + "\"}";
    JSONObject priceOnly = new JSONObject("{\"unitPrice\":20}");
    JSONObject qtyOnly = new JSONObject("{\"orderedQuantity\":3}");
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> dalMock = LineAmountTestSupport.taxRate(21.0, dal);
        MockedStatic<OBProvider> provider = storedLine(dal, taxIncludedLine)) {
      OrderLineDiscountSupport.completeAmountInputsOnUpdate(updateCtx("PATCH", priceOnly, null));
      OrderLineDiscountSupport.completeAmountInputsOnUpdate(updateCtx("PATCH", qtyOnly, null));
    }

    assertFalse(priceOnly.has(OrderLineDiscountSupport.FIELD_GROSS_UNIT_PRICE));
    assertEquals(2, priceOnly.getInt(OrderLineDiscountSupport.FIELD_ORDERED_QTY));
    assertEquals(12.1, qtyOnly.getDouble(OrderLineDiscountSupport.FIELD_GROSS_UNIT_PRICE));
  }

  @Test
  void updateWithoutAnAmountInputCarriesNothingAndReadsNothing() throws Exception {
    JSONObject json = new JSONObject("{\"description\":\"x\"}");
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> dalMock = LineAmountTestSupport.taxRate(21.0, dal);
        MockedStatic<OBProvider> provider = storedLine(dal, STORED_MONITOR_LINE)) {
      OrderLineDiscountSupport.completeAmountInputsOnUpdate(updateCtx("PATCH", json, null));
    }

    assertEquals(1, json.length());
    verify(dal, times(0)).get(OrderLine.class, LINE_ID);
  }

  // ── CP-9: an update of the price alone derives the discount ───────────

  /** Stored: list 300, discount 20, price 240, qty 5. */
  static Stream<Arguments> discountFromPriceRule() {
    return Stream.of(
        Arguments.of("CP-9: a new price alone → derive the discount", "{\"unitPrice\":270}",
            true),
        Arguments.of("back to the list price → derive the discount", "{\"unitPrice\":300}", true),
        Arguments.of("form side panel, quantity edited: the price it computes is the current one",
            "{\"orderedQuantity\":6,\"unitPrice\":240}", false),
        Arguments.of("form side panel, list price edited: idem over the new list price",
            "{\"listPrice\":200,\"unitPrice\":160}", false),
        Arguments.of("form float rounding, within half a cent", "{\"unitPrice\":240.000001}",
            false),
        Arguments.of("form inline edit carries the discount",
            "{\"discount\":20,\"unitPrice\":270}", false),
        Arguments.of("explicit gross price", "{\"unitPrice\":270,\"grossUnitPrice\":326.7}",
            false),
        Arguments.of("no list price", "{\"listPrice\":0,\"unitPrice\":270}", false),
        Arguments.of("no unitPrice", "{\"orderedQuantity\":6}", false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("discountFromPriceRule")
  void shouldDeriveDiscountFromPrice(String label, String patch, boolean expected)
      throws Exception {
    JSONObject body = new JSONObject(patch);
    JSONObject formState = new JSONObject(
        "{\"orderedQuantity\":5,\"listPrice\":300,\"discount\":20,\"unitPrice\":240}");
    Iterator<String> keys = body.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      formState.put(key, body.get(key));
    }

    assertEquals(expected, OrderLineDiscountSupport.shouldDeriveDiscountFromPrice(body, formState),
        label);
  }

  /**
   * QA CP-9: line at list 300, discount 20, qty 5 → {@code etendo_update unitPrice:270}. The
   * re-fire is stubbed with what {@code SL_Order_Amt} answers on {@code inppriceactual} (discount
   * 10, net 1350, gross {@code grossUnitPrice × qty} = 0 on a net list); pinned here is that it is
   * fired for {@code unitPrice}, keeps the caller's price, reads the new price as its standard
   * price, and that the gross is then derived: 1350 × 1.21 = 1633.50.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("priceUpdateMethods")
  void updateOfThePriceAloneDerivesTheDiscount(String method) throws Exception {
    JSONObject body = new JSONObject("{\"unitPrice\":270}");
    JSONObject calloutWrites = new JSONObject(
        "{\"discount\":\"10\",\"lineNetAmount\":\"1350\",\"lineGrossAmount\":\"0\"}");
    OBDal dal = mock(OBDal.class);
    Tab tab = mock(Tab.class);
    List<Object> triggers = new ArrayList<>();
    Set<String> protectedKeys = new HashSet<>();
    List<Object> inputStandardPrice = new ArrayList<>();

    try (MockedStatic<OBDal> dalMock = LineAmountTestSupport.taxRate(21.0, dal);
        MockedStatic<OBProvider> provider = storedLine(dal, "{\"orderedQuantity\":5,"
            + "\"listPrice\":300,\"discount\":20,\"unitPrice\":240,\"standardPrice\":300,"
            + "\"tax\":\"" + TAX_ID + "\",\"lineGrossAmount\":1452}");
        MockedStatic<NeoDefaultsCascadeHelper> cascade = mockStatic(NeoDefaultsCascadeHelper.class);
        MockedStatic<NeoTypeCoercionHelper> coercion = mockStatic(NeoTypeCoercionHelper.class)) {
      cascade.when(() -> NeoDefaultsCascadeHelper.executeCalloutsForTriggerFields(any(), any(),
          any(), any(), any(), any(), any())).thenAnswer(inv -> {
            JSONObject formState = inv.getArgument(2);
            inputStandardPrice.add(formState.opt(OrderLineDiscountSupport.FIELD_STANDARD_PRICE));
            triggers.addAll(inv.<Collection<Object>>getArgument(4));
            protectedKeys.addAll(inv.<Set<String>>getArgument(5));
            JSONObject target = inv.getArgument(3);
            Iterator<String> it = calloutWrites.keys();
            while (it.hasNext()) {
              String key = it.next();
              if (!protectedKeys.contains(key)) {
                target.put(key, calloutWrites.get(key));
              }
            }
            return null;
          });
      coercion.when(() -> NeoTypeCoercionHelper.coerceTypes(any(JSONObject.class), anyString()))
          .thenAnswer(inv -> {
            JSONObject data = inv.getArgument(0);
            Iterator<String> it = data.keys();
            while (it.hasNext()) {
              String key = it.next();
              data.put(key, new BigDecimal(data.getString(key)));
            }
            return null;
          });

      OrderLineDiscountSupport.applyLineRules(updateCtx(method, body, tab));
    }

    assertEquals(Arrays.asList(OrderLineDiscountSupport.FIELD_UNIT_PRICE), triggers,
        "the re-fire is for the price");
    assertTrue(protectedKeys.contains(OrderLineDiscountSupport.FIELD_UNIT_PRICE),
        "the caller's price is protected");
    assertEquals(0, new BigDecimal("270").compareTo(
        new BigDecimal(String.valueOf(inputStandardPrice.get(0)))),
        "the callout reads the new price as its standard price, or it misreads the discount");
    assertAmount("unitPrice", "270", body, OrderLineDiscountSupport.FIELD_UNIT_PRICE);
    assertAmount("discount", "10", body, OrderLineDiscountSupport.FIELD_DISCOUNT);
    assertAmount("lineNetAmount", "1350", body, OrderLineDiscountSupport.FIELD_LINE_NET_AMOUNT);
    assertAmount("lineGrossAmount", "1633.50", body,
        OrderLineDiscountSupport.FIELD_LINE_GROSS_AMOUNT);
  }

  static Stream<String> priceUpdateMethods() {
    return Stream.of("PATCH", "PUT");
  }

  // ── ETP-5881: a line sent without a price takes the price list's, on every channel ──

  static Stream<Arguments> createWithoutPrice() {
    return Stream.of(
        Arguments.of("REST: only product, quantity and parent → catalog price",
            "{\"product\":\"P1\",\"parentId\":\"O1\",\"orderedQuantity\":2}", "12", "12", "12"),
        Arguments.of("unitPrice 0 counts as not sent",
            "{\"product\":\"P1\",\"parentId\":\"O1\",\"unitPrice\":0}", "12", "12", "12"),
        Arguments.of("the caller's price wins (list price left as sent)",
            "{\"product\":\"P1\",\"parentId\":\"O1\",\"unitPrice\":9}", "9", null, "12"),
        Arguments.of("an explicit gross price wins",
            "{\"product\":\"P1\",\"parentId\":\"O1\",\"grossUnitPrice\":14}", null, null,
            "12"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("createWithoutPrice")
  void setStandardPriceOnCreatePricesALineSentWithoutPrice(String label, String body,
      String expectedUnit, String expectedList, String expectedStd) throws Exception {
    JSONObject json = new JSONObject(body);
    OBDal dal = mock(OBDal.class);
    Product product = mock(Product.class);
    Order order = mock(Order.class);
    PriceList priceList = mock(PriceList.class);
    ProductPrice price = mock(ProductPrice.class);
    Date orderDate = new Date();
    when(dal.get(Product.class, "P1")).thenReturn(product);
    when(dal.get(Order.class, "O1")).thenReturn(order);
    when(order.getOrderDate()).thenReturn(orderDate);
    when(order.getPriceList()).thenReturn(priceList);
    when(order.isSalesTransaction()).thenReturn(Boolean.TRUE);
    when(priceList.isPriceIncludesTax()).thenReturn(Boolean.FALSE);
    when(price.getListPrice()).thenReturn(new BigDecimal("12"));
    when(price.getStandardPrice()).thenReturn(new BigDecimal("12"));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<FinancialUtils> financial = mockStatic(FinancialUtils.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      financial.when(() -> FinancialUtils.getProductPrice(eq(product), eq(orderDate),
          anyBoolean(), eq(priceList), eq(false))).thenReturn(price);

      OrderLineDiscountSupport.setStandardPriceOnCreate(ctx("POST", NeoEndpointType.CRUD, json));
    }

    assertAmount(label, expectedUnit, json, OrderLineDiscountSupport.FIELD_UNIT_PRICE);
    assertAmount(label, expectedStd, json, OrderLineDiscountSupport.FIELD_STANDARD_PRICE);
    assertAmount(label, expectedUnit != null && expectedList != null ? expectedList : null, json,
        OrderLineDiscountSupport.FIELD_LIST_PRICE);
  }

  @org.junit.jupiter.api.Test
  void setStandardPriceOnCreatePricesAPurchaseLineFromThePurchaseList() throws Exception {
    JSONObject json = new JSONObject("{\"product\":\"P1\",\"parentId\":\"O1\"}");
    OBDal dal = mock(OBDal.class);
    Product product = mock(Product.class);
    Order order = mock(Order.class);
    PriceList priceList = mock(PriceList.class);
    ProductPrice price = mock(ProductPrice.class);
    Date orderDate = new Date();
    when(dal.get(Product.class, "P1")).thenReturn(product);
    when(dal.get(Order.class, "O1")).thenReturn(order);
    when(order.getOrderDate()).thenReturn(orderDate);
    when(order.getPriceList()).thenReturn(priceList);
    when(order.isSalesTransaction()).thenReturn(Boolean.FALSE);
    when(priceList.isPriceIncludesTax()).thenReturn(Boolean.FALSE);
    when(price.getListPrice()).thenReturn(new BigDecimal("7"));
    when(price.getStandardPrice()).thenReturn(new BigDecimal("6"));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<FinancialUtils> financial = mockStatic(FinancialUtils.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      financial.when(() -> FinancialUtils.getProductPrice(eq(product), eq(orderDate),
          eq(false), eq(priceList), eq(false))).thenReturn(price);

      OrderLineDiscountSupport.setStandardPriceOnCreate(ctx("POST", NeoEndpointType.CRUD, json));
    }

    assertAmount("purchase", "6", json, OrderLineDiscountSupport.FIELD_UNIT_PRICE);
    assertAmount("purchase", "7", json, OrderLineDiscountSupport.FIELD_LIST_PRICE);
    assertAmount("purchase", "6", json, OrderLineDiscountSupport.FIELD_STANDARD_PRICE);
  }

  /** A 100% discount on the client's list price is a price of 0, not a missing price. */
  @org.junit.jupiter.api.Test
  void setStandardPriceOnCreateKeepsAFullDiscountAtZero() throws Exception {
    JSONObject json = new JSONObject(
        "{\"product\":\"P1\",\"parentId\":\"O1\",\"listPrice\":20,\"discount\":100}");
    OBDal dal = mock(OBDal.class);
    Product product = mock(Product.class);
    Order order = mock(Order.class);
    PriceList priceList = mock(PriceList.class);
    ProductPrice price = mock(ProductPrice.class);
    Date orderDate = new Date();
    when(dal.get(Product.class, "P1")).thenReturn(product);
    when(dal.get(Order.class, "O1")).thenReturn(order);
    when(order.getOrderDate()).thenReturn(orderDate);
    when(order.getPriceList()).thenReturn(priceList);
    when(order.isSalesTransaction()).thenReturn(Boolean.TRUE);
    when(priceList.isPriceIncludesTax()).thenReturn(Boolean.FALSE);
    when(price.getListPrice()).thenReturn(new BigDecimal("15"));
    when(price.getStandardPrice()).thenReturn(new BigDecimal("12"));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<FinancialUtils> financial = mockStatic(FinancialUtils.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      financial.when(() -> FinancialUtils.getProductPrice(eq(product), eq(orderDate),
          eq(true), eq(priceList), eq(false))).thenReturn(price);

      OrderLineDiscountSupport.setStandardPriceOnCreate(ctx("POST", NeoEndpointType.CRUD, json));
    }

    assertAmount("full discount", "0", json, OrderLineDiscountSupport.FIELD_UNIT_PRICE);
    assertAmount("full discount", "20", json, OrderLineDiscountSupport.FIELD_LIST_PRICE);
    assertAmount("full discount", "12", json, OrderLineDiscountSupport.FIELD_STANDARD_PRICE);
  }

  @org.junit.jupiter.api.Test
  void setStandardPriceOnCreateKeepsTheCallersPrice() throws Exception {
    JSONObject json = new JSONObject(
        "{\"product\":\"P1\",\"parentId\":\"O1\",\"unitPrice\":9}");
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      OrderLineDiscountSupport.setStandardPriceOnCreate(ctx("POST", NeoEndpointType.CRUD, json));
    }
    assertAmount("kept", "9", json, OrderLineDiscountSupport.FIELD_UNIT_PRICE);
  }

  static Stream<Arguments> clientListPrice() {
    return Stream.of(
        Arguments.of("list 20, no discount", "{\"listPrice\":20}", "20"),
        Arguments.of("list 20, discount 10", "{\"listPrice\":20,\"discount\":10}", "18"),
        Arguments.of("unitPrice 0 counts as not sent", "{\"listPrice\":20,\"unitPrice\":0}",
            "20"),
        Arguments.of("the caller's unitPrice wins", "{\"listPrice\":20,\"unitPrice\":15}", "15"),
        Arguments.of("an explicit gross price wins", "{\"listPrice\":20,\"grossUnitPrice\":24}",
            null));
  }

  /** ETP-5881: the client's listPrice is the price; the price list is not applied to it. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("clientListPrice")
  void setStandardPriceOnCreateUsesTheClientListPrice(String label, String extra, String expectedUnit)
      throws Exception {
    JSONObject json = new JSONObject(extra);
    json.put("product", "P1").put("parentId", "O1");
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      OrderLineDiscountSupport.setStandardPriceOnCreate(ctx("POST", NeoEndpointType.CRUD, json));
    }
    assertAmount(label, expectedUnit, json, OrderLineDiscountSupport.FIELD_UNIT_PRICE);
    assertAmount(label, "20", json, OrderLineDiscountSupport.FIELD_LIST_PRICE);
  }
}
