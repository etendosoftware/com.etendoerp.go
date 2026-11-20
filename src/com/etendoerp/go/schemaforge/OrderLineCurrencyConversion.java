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

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * The currency of a new order line and the conversion of its price-list prices into it (ETP-6177),
 * for the order line customizations that call it through
 * {@link OrderLineDiscountSupport#setStandardPriceOnCreate}: {@link SalesOrderLineHandler},
 * {@link PurchaseOrderLineHandler} and {@link SalesQuotationLineHandler}.
 *
 * <p><b>What the form does.</b> When the saved order's currency is not its organization's, the SPA
 * multiplies the price of a product picked into a new line by the order's org→document rate
 * ({@code EM_ETGO_Currency_Rate}, the rate the header shows as "USD — 1,47"), rounds it to 2
 * decimals, gives the line the order's currency and sends that converted price
 * ({@code applyProductCurrencyConversion} / {@code convertOptimisticPrice} in
 * {@code detailViewHelpers.jsx} / {@code DetailView.jsx}). A line created through REST, batch or
 * MCP without a price was priced from the price list unconverted, in the price list's currency, and
 * kept that currency while the order was labelled in another one.
 *
 * <p><b>The rule, server side.</b> A price taken from the order's price list is converted when the
 * price list's currency is the organization's currency (the "from" side of the order's rate) and
 * the order's currency is another one, by the order's own rate: {@code price × rate}, rounded
 * HALF_UP to the order currency's price precision ({@code C_Currency.PricePrecision}, 2 for EUR/USD,
 * the SPA's fixed 2). Every price of the line converts on its own (list, standard, unit, limit), so
 * the discount between list and unit price is the one the price list implies, as on an
 * org-currency order. A price the caller sent is the caller's, in the order's currency, and is never
 * converted — the SPA already sends converted prices, so it is not converted twice.
 *
 * <p><b>Abstains</b> (identity conversion) when the order, its currency, its price list or the
 * price list's currency cannot be read, when the price list is already in the order's currency,
 * when the price list is in a third currency (the order's rate does not convert from it), or when
 * the order has no positive rate — the header's own guard ({@link DocumentCurrencyRate}) refuses
 * foreign-currency orders without one. Total: never throws.
 *
 * <p>Entity knowledge ({@code C_Order}, its rate column), reached only from the order line
 * customizations, never from a shared write path.
 */
final class OrderLineCurrencyConversion {

  private static final Logger log = LogManager.getLogger(OrderLineCurrencyConversion.class);

  /** The SPA's fixed rounding, used when the currency has no price precision. */
  static final int DEFAULT_PRICE_SCALE = 2;

  private static final OrderLineCurrencyConversion NONE = new OrderLineCurrencyConversion(null,
      null, DEFAULT_PRICE_SCALE);

  private final String orderCurrencyId;
  private final BigDecimal rate;
  private final int scale;

  private OrderLineCurrencyConversion(String orderCurrencyId, BigDecimal rate, int scale) {
    this.orderCurrencyId = orderCurrencyId;
    this.rate = rate;
    this.scale = scale;
  }

  /**
   * The conversion that applies to the price-list prices of a new line of {@code order}.
   *
   * @return never {@code null}; {@link #converts()} tells whether it changes anything
   */
  static OrderLineCurrencyConversion forOrder(Order order) {
    try {
      return resolve(order);
    } catch (Exception e) {
      log.warn("[ETP-6177] Could not resolve the currency conversion of a new order line: {}",
          e.getMessage());
      return NONE;
    }
  }

  private static OrderLineCurrencyConversion resolve(Order order) {
    Currency orderCurrency = order == null ? null : order.getCurrency();
    if (orderCurrency == null) {
      return NONE;
    }
    int scale = priceScale(orderCurrency);
    OrderLineCurrencyConversion lineCurrencyOnly = new OrderLineCurrencyConversion(
        orderCurrency.getId(), null, scale);
    PriceList priceList = order.getPriceList();
    Currency listCurrency = priceList == null ? null : priceList.getCurrency();
    if (listCurrency == null || orderCurrency.getId().equals(listCurrency.getId())) {
      return lineCurrencyOnly;
    }
    String orgCurrencyId = order.getOrganization() == null ? null
        : OBCurrencyUtils.getOrgCurrency(order.getOrganization().getId());
    if (!listCurrency.getId().equals(orgCurrencyId)) {
      log.debug("[ETP-6177] Price list {} is in neither the order's nor the organization's "
          + "currency; its prices are not converted", priceList.getIdentifier());
      return lineCurrencyOnly;
    }
    BigDecimal orderRate = order.getETGOCurrencyRate();
    if (orderRate == null || orderRate.signum() <= 0) {
      return lineCurrencyOnly;
    }
    return new OrderLineCurrencyConversion(orderCurrency.getId(), orderRate, scale);
  }

  private static int priceScale(Currency currency) {
    Long precision = currency.getPricePrecision();
    return precision == null || precision < 0 ? DEFAULT_PRICE_SCALE : precision.intValue();
  }

  /** @return {@code true} when price-list prices change under this conversion */
  boolean converts() {
    return rate != null;
  }

  /**
   * {@code amount} in the order's currency: {@code amount × rate} at the price scale, or
   * {@code amount} itself when nothing converts or the amount is {@code null}.
   */
  BigDecimal convert(BigDecimal amount) {
    if (amount == null || rate == null) {
      return amount;
    }
    return amount.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
  }

  /**
   * Gives the line the order's currency, as the form does: a line is always in its order's
   * currency. Leaves the body alone when the order currency is unknown.
   */
  void applyLineCurrency(JSONObject body) throws JSONException {
    if (body != null && orderCurrencyId != null) {
      body.put(OrderLineDiscountSupport.FIELD_CURRENCY, orderCurrencyId);
    }
  }

  /**
   * Replaces each of {@code fields} whose body value is exactly the raw price-list amount with its
   * converted value. For a body priced from the price list by someone else before this rule — the
   * MCP compensation, which writes the raw prices — never for a price the caller sent.
   *
   * @param raw the price-list amounts, in the same order as {@code fields}
   */
  void convertRawPrices(JSONObject body, String[] fields, BigDecimal[] raw) throws JSONException {
    if (body == null || !converts()) {
      return;
    }
    for (int i = 0; i < fields.length; i++) {
      if (raw[i] != null && raw[i].signum() != 0
          && OrderLineDiscountSupport.sameAmount(body.opt(fields[i]), raw[i])) {
        body.put(fields[i], convert(raw[i]));
      }
    }
  }
}
