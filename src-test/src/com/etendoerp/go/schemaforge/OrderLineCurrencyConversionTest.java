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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Unit tests for {@link OrderLineCurrencyConversion}: when the price-list prices of a new order line
 * convert into the order's currency, how they round, and when the conversion abstains.
 *
 * @covers com.etendoerp.go.schemaforge.OrderLineCurrencyConversion
 */
class OrderLineCurrencyConversionTest {

  private static final String ORG_ID = "ORG";
  private static final String[] FIELDS = {"unitPrice", "listPrice", "priceLimit"};

  private static Currency currency(String id, Long precision) {
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(id);
    when(currency.getPricePrecision()).thenReturn(precision);
    return currency;
  }

  private static PriceList priceList(Currency currency) {
    PriceList priceList = mock(PriceList.class);
    when(priceList.getCurrency()).thenReturn(currency);
    return priceList;
  }

  private static Order order(Currency orderCurrency, PriceList priceList, String rate) {
    Order order = mock(Order.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(order.getOrganization()).thenReturn(org);
    when(order.getCurrency()).thenReturn(orderCurrency);
    when(order.getPriceList()).thenReturn(priceList);
    when(order.getETGOCurrencyRate()).thenReturn(rate == null ? null : new BigDecimal(rate));
    return order;
  }

  private static OrderLineCurrencyConversion resolve(Order order, String orgCurrencyId) {
    try (MockedStatic<OBCurrencyUtils> utils = mockStatic(OBCurrencyUtils.class)) {
      utils.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID)).thenReturn(orgCurrencyId);
      return OrderLineCurrencyConversion.forOrder(order);
    }
  }

  private static OrderLineCurrencyConversion usdOverEur(String rate) {
    Currency eur = currency("EUR", 2L);
    Currency usd = currency("USD", 2L);
    return resolve(order(usd, priceList(eur), rate), "EUR");
  }

  private static void assertAbstains(OrderLineCurrencyConversion conversion) {
    assertFalse(conversion.converts());
    assertEquals(new BigDecimal("12.345"), conversion.convert(new BigDecimal("12.345")));
  }

  @Test
  void convertsByTheOrderRateAtThePricePrecisionHalfUp() {
    OrderLineCurrencyConversion conversion = usdOverEur("1.47");

    assertTrue(conversion.converts());
    assertEquals(new BigDecimal("17.64"), conversion.convert(new BigDecimal("12")));
    // 10.005 * 1.5 = 15.0075 -> 15.01 (HALF_UP)
    assertEquals(new BigDecimal("15.01"),
        usdOverEur("1.5").convert(new BigDecimal("10.005")));
    // 1 * 1.005 = 1.005 -> 1.01, HALF_UP and not the banker's 1.00
    assertEquals(new BigDecimal("1.01"), usdOverEur("1.005").convert(BigDecimal.ONE));
  }

  @Test
  void convertKeepsANullAmount() {
    assertNull(usdOverEur("1.47").convert(null));
  }

  @Test
  void aZeroScaleCurrencyRoundsToIntegers() {
    Currency eur = currency("EUR", 2L);
    Currency jpy = currency("JPY", 0L);

    OrderLineCurrencyConversion conversion = resolve(order(jpy, priceList(eur), "160.5"), "EUR");

    assertTrue(conversion.converts());
    // 12 * 160.5 = 1926 ; 0.5 * 160.5 = 80.25 -> 80
    assertEquals(new BigDecimal("1926"), conversion.convert(new BigDecimal("12")));
    assertEquals(new BigDecimal("80"), conversion.convert(new BigDecimal("0.5")));
  }

  @Test
  void aMissingOrNegativePricePrecisionFallsBackToTwoDecimals() {
    Currency eur = currency("EUR", 2L);
    Currency noPrecision = currency("XXX", null);
    Currency negative = currency("YYY", -1L);

    assertEquals(new BigDecimal("1.23"), resolve(order(noPrecision, priceList(eur), "1.2345"),
        "EUR").convert(BigDecimal.ONE));
    assertEquals(new BigDecimal("1.23"), resolve(order(negative, priceList(eur), "1.2345"),
        "EUR").convert(BigDecimal.ONE));
  }

  @Test
  void abstainsWithoutAnOrder() {
    assertAbstains(resolve(null, "EUR"));
  }

  @Test
  void abstainsWithoutAnOrderCurrency() {
    Currency eur = currency("EUR", 2L);

    assertAbstains(resolve(order(null, priceList(eur), "1.47"), "EUR"));
  }

  @Test
  void abstainsWithoutAPriceList() {
    assertAbstains(resolve(order(currency("USD", 2L), null, "1.47"), "EUR"));
  }

  @Test
  void abstainsWhenThePriceListHasNoCurrency() {
    assertAbstains(resolve(order(currency("USD", 2L), priceList(null), "1.47"), "EUR"));
  }

  @Test
  void abstainsOnAnOrderInTheOrganizationCurrency() {
    Currency eur = currency("EUR", 2L);

    assertAbstains(resolve(order(eur, priceList(eur), "1.47"), "EUR"));
  }

  @Test
  void abstainsWhenThePriceListIsInAThirdCurrency() {
    Currency gbp = currency("GBP", 2L);
    Currency usd = currency("USD", 2L);

    // the organization works in EUR: the order's EUR->USD rate does not convert from GBP
    assertAbstains(resolve(order(usd, priceList(gbp), "1.47"), "EUR"));
  }

  @Test
  void abstainsWhenTheOrganizationCurrencyIsUnknown() {
    assertAbstains(usdOverEurWithOrgCurrency(null));
  }

  private static OrderLineCurrencyConversion usdOverEurWithOrgCurrency(String orgCurrencyId) {
    return resolve(order(currency("USD", 2L), priceList(currency("EUR", 2L)), "1.47"),
        orgCurrencyId);
  }

  @Test
  void abstainsWithoutAPositiveRate() {
    assertAbstains(usdOverEur(null));
    assertAbstains(usdOverEur("0"));
    assertAbstains(usdOverEur("-1.5"));
  }

  @Test
  void neverThrowsWhenTheOrderCannotBeRead() {
    Order order = mock(Order.class);
    when(order.getCurrency()).thenThrow(new IllegalStateException("detached"));

    OrderLineCurrencyConversion conversion = OrderLineCurrencyConversion.forOrder(order);

    assertAbstains(conversion);
    JSONObject body = new JSONObject();
    assertDoesNotThrowApply(conversion, body);
    assertFalse(body.has("currency"));
  }

  private static void assertDoesNotThrowApply(OrderLineCurrencyConversion conversion,
      JSONObject body) {
    assertDoesNotThrow(() -> conversion.applyLineCurrency(body));
  }

  @Test
  void applyLineCurrencyPutsTheOrderCurrencyEvenWhenNothingConverts() throws Exception {
    Currency eur = currency("EUR", 2L);
    JSONObject body = new JSONObject();

    resolve(order(eur, priceList(eur), null), "EUR").applyLineCurrency(body);

    assertEquals("EUR", body.getString(OrderLineDiscountSupport.FIELD_CURRENCY));
  }

  @Test
  void applyLineCurrencyToleratesANullBody() {
    assertDoesNotThrow(() -> usdOverEur("1.47").applyLineCurrency(null));
  }

  @Test
  void convertRawPricesConvertsOnlyTheValuesMatchingTheInjectedOnes() throws Exception {
    JSONObject body = new JSONObject("{\"unitPrice\":12,\"listPrice\":9,\"priceLimit\":10}");
    BigDecimal[] raw = {new BigDecimal("12"), new BigDecimal("12"), new BigDecimal("10")};

    usdOverEur("1.47").convertRawPrices(body, FIELDS, raw);

    assertEquals(0, new BigDecimal("17.64").compareTo(new BigDecimal(body.get("unitPrice")
        .toString())));
    // the caller's own list price (9 != 12) is untouched
    assertEquals(9, body.getInt("listPrice"));
    assertEquals(0, new BigDecimal("14.70").compareTo(new BigDecimal(body.get("priceLimit")
        .toString())));
  }

  @Test
  void convertRawPricesSkipsNullAndZeroRawAmountsAndAbsentFields() throws Exception {
    JSONObject body = new JSONObject("{\"unitPrice\":0}");
    BigDecimal[] raw = {BigDecimal.ZERO, null, new BigDecimal("10")};

    usdOverEur("1.47").convertRawPrices(body, FIELDS, raw);

    assertEquals(0, body.getInt("unitPrice"));
    assertFalse(body.has("listPrice"));
    assertFalse(body.has("priceLimit"));
  }

  @Test
  void convertRawPricesLeavesTheBodyAloneWhenNothingConverts() throws Exception {
    JSONObject body = new JSONObject("{\"unitPrice\":12}");
    BigDecimal[] raw = {new BigDecimal("12"), null, null};

    usdOverEur(null).convertRawPrices(body, FIELDS, raw);
    usdOverEur("1.47").convertRawPrices(null, FIELDS, raw);

    assertEquals(12, body.getInt("unitPrice"));
  }
}
