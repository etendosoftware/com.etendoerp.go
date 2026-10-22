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

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/**
 * The one rule the order-line and invoice-line customizations share when a line is created without
 * a price (ETP-5881): take the product's price from the parent document's price list, as the
 * product callout does in the SPA. REST (single and batch) runs no callout at that point, so a line
 * sent with only a product and a quantity persisted price 0.
 *
 * <p>Entity knowledge stays out of here. The caller resolves the {@link ProductPrice} of its own
 * parent document and passes its own field names; this class only decides whether the body asks for
 * a price and writes it. It never overrides a price the client sent: a non-zero {@code unitPrice} or
 * {@code grossUnitPrice} in the body ends the rule (a zero is what the rule replaces, the same
 * reading {@code McpLinePriceInjector} has). The list price is written only when the catalog has one
 * and the body carries none.
 */
final class LinePriceListSupport {

  static final String FIELD_GROSS_UNIT_PRICE = "grossUnitPrice";

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final int PRICE_SCALE = 6;

  private LinePriceListSupport() {
  }

  /**
   * @return {@code true} when the body carries no price of the caller's: {@code unitPrice} and
   *         {@code grossUnitPrice} are each absent, null or zero
   */
  static boolean hasNoPrice(JSONObject body, String unitPriceKey) {
    return body != null && LineAmountSupport.isZero(body.opt(unitPriceKey))
        && LineAmountSupport.isZero(body.opt(FIELD_GROSS_UNIT_PRICE));
  }

  /**
   * A body with a non-zero {@code listPrice} and no price of the caller's — the one price the public
   * API exposes as writable — gets {@code unitPrice = listPrice × (1 − discount / 100)}, 6 decimals,
   * as the form does when "Precio" is edited. The price list is not consulted: the caller's list
   * price is the price. A discount outside 0..100 writes nothing (the line's own validation answers
   * it) and still counts as handled.
   *
   * @return {@code true} when the body carries a list price and no price, so the caller must not
   *         apply the price list
   */
  static boolean applyClientListPrice(JSONObject body, String unitPriceKey, String listPriceKey,
      String discountKey) throws JSONException {
    if (!hasNoPrice(body, unitPriceKey) || LineAmountSupport.isZero(body.opt(listPriceKey))) {
      return false;
    }
    BigDecimal list = LineAmountSupport.decimal(body.opt(listPriceKey));
    BigDecimal discount = LineAmountSupport.decimal(body.opt(discountKey));
    if (discount == null) {
      discount = BigDecimal.ZERO;
    }
    if (discount.signum() >= 0 && discount.compareTo(HUNDRED) <= 0) {
      body.put(unitPriceKey, list.multiply(HUNDRED.subtract(discount))
          .divide(HUNDRED, PRICE_SCALE, RoundingMode.HALF_UP));
    }
    return true;
  }

  /**
   * Writes the standard price of {@code catalog} as the unit price when the body has none, and its
   * list price as the list price when the catalog has one and the body does not. Abstains on a
   * null catalog entry or one with no standard price. Total for a well-formed body.
   *
   * @return {@code true} when the unit price was written
   */
  static boolean applyCatalogPrice(JSONObject body, ProductPrice catalog, String unitPriceKey,
      String listPriceKey) throws JSONException {
    if (catalog == null || !hasNoPrice(body, unitPriceKey)) {
      return false;
    }
    BigDecimal standard = catalog.getStandardPrice();
    if (standard == null || standard.signum() == 0) {
      return false;
    }
    body.put(unitPriceKey, standard);
    BigDecimal list = catalog.getListPrice();
    if (list != null && list.signum() != 0 && LineAmountSupport.isZero(body.opt(listPriceKey))) {
      body.put(listPriceKey, list);
    }
    return true;
  }
}
