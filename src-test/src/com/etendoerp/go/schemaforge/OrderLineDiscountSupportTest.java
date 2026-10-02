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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

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
}
