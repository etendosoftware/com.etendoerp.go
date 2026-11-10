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

import static com.etendoerp.go.schemaforge.LineAmountSupport.decimal;
import static com.etendoerp.go.schemaforge.LineAmountSupport.isZero;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_DISCOUNT;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_GROSS_UNIT_PRICE;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_LINE_GROSS_AMOUNT;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_LIST_PRICE;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_ORDERED_QTY;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_TAX;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_UNIT_PRICE;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.sameAmount;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;

/**
 * Which order line rule a write body asks for: the pure predicates over the request body (and,
 * on an update, the stored line overlaid with it) that {@link OrderLineDiscountSupport} reads
 * before it re-fires a callout or derives an amount. No DAL, no callout, no mutation — every
 * method answers from the JSON it is given. Kept apart so {@link OrderLineDiscountSupport} stays
 * within Sonar's method budget (ETP-5528); the rules themselves are documented there.
 */
final class OrderLineBodyRules {

  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  /** The inputs the line amounts are derived from; a patch that changes one refreshes them. */
  static final List<String> AMOUNT_INPUTS = Collections.unmodifiableList(Arrays.asList(
      FIELD_ORDERED_QTY, FIELD_UNIT_PRICE, FIELD_GROSS_UNIT_PRICE, FIELD_TAX));

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /**
   * How far an update's {@code unitPrice} may be from {@code listPrice × (1 − discount / 100)} and
   * still be the price the line already has (the form rounds it with {@code toFixed(6)} over a
   * float).
   */
  private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

  private OrderLineBodyRules() {
  }

  /**
   * The form's rule (see the class javadoc): the body carries a discount, no explicit gross price,
   * and either no {@code unitPrice}, or a non-zero discount with {@code unitPrice} equal to the
   * undiscounted {@code listPrice}. Amounts compare by value, scale-insensitive
   * ({@code 18 == 18.00}).
   *
   * @param body      the write body
   * @param listPrice the line's list price — the body's on a create, the stored line overlaid with
   *                  the patch on an update
   */
  static boolean shouldApplyDiscount(JSONObject body, Object listPrice) {
    if (!hasValue(body, FIELD_DISCOUNT) || sentExplicitGrossPrice(body)) {
      return false;
    }
    if (!hasValue(body, FIELD_UNIT_PRICE)) {
      return true;
    }
    return !isZero(body.opt(FIELD_DISCOUNT)) && sameAmount(body.opt(FIELD_UNIT_PRICE), listPrice);
  }

  /**
   * Whether an update patch changes the list price ("Precio") and leaves the net price to the
   * server (CP-13 of ETP-5528): it carries a {@code listPrice}, no {@code unitPrice} key, no
   * {@code discount} key and no explicit gross price. {@code etendo_update listPrice:300} on a line
   * at list 200, discount 10, qty 10 kept {@code unitPrice 180} and net 1800 (expected 270 / 2700):
   * no rule reacted to {@code listPrice}. Such a patch now takes the
   * {@link OrderLineDiscountSupport#applyDiscount} re-fire with the line's current discount,
   * exactly as a patch carrying {@code listPrice} and {@code discount} already did.
   *
   * <p>The form never matches it: both UI line writes send {@code unitPrice} computed from the list
   * price and the discount, and the inline edit sends {@code discount} too.
   */
  static boolean changesOnlyListPrice(JSONObject patch) {
    return hasValue(patch, FIELD_LIST_PRICE) && !patch.has(FIELD_UNIT_PRICE)
        && !patch.has(FIELD_DISCOUNT) && !sentExplicitGrossPrice(patch);
  }

  /**
   * The discount the stored line has, read from the update's form state; 0 when it has none or it
   * is not a number, as a new line's default.
   */
  static BigDecimal storedDiscount(JSONObject formState) {
    BigDecimal discount = formState == null ? null : decimal(formState.opt(FIELD_DISCOUNT));
    return discount == null ? BigDecimal.ZERO : discount;
  }

  /**
   * A non-zero {@code grossUnitPrice} is a price the caller chose. A zero one is not: it is what a
   * net price list carries ({@code NeoCommercialLinePolicy.normalizeOrderLineSelectorPriceMapping}
   * zeroes it) and what the defaults pass injects.
   */
  static boolean sentExplicitGrossPrice(JSONObject body) {
    return hasValue(body, FIELD_GROSS_UNIT_PRICE) && !isZero(body.opt(FIELD_GROSS_UNIT_PRICE));
  }

  /**
   * Whether an update's {@code unitPrice} is a new price whose discount must be derived: the patch
   * carries a {@code unitPrice}, no {@code discount} key and no explicit gross price; the line
   * (stored, overlaid with the patch) has a list price; and the price is NOT the one the line's
   * current list price and discount already give — {@code listPrice × (1 − discount / 100)},
   * within half a cent.
   *
   * <p>That last clause is what keeps the form out: the side-panel save sends the edited keys
   * plus exactly that computed {@code unitPrice} (from the open line's {@code listPrice} and
   * {@code discount}), and the inline edit sends the whole row, {@code discount} included. Half a
   * cent absorbs the form's {@code toFixed(6)} float rounding. A line with no list price (a
   * tax-included list carries its list price on the gross side) is left alone, as the discount
   * re-fire leaves it.
   *
   * @param patch     the update body
   * @param formState the stored line overlaid with the patch; {@code null} abstains
   */
  static boolean shouldDeriveDiscountFromPrice(JSONObject patch, JSONObject formState) {
    if (formState == null || !hasValue(patch, FIELD_UNIT_PRICE) || patch.has(FIELD_DISCOUNT)
        || sentExplicitGrossPrice(patch)) {
      return false;
    }
    BigDecimal listPrice = decimal(formState.opt(FIELD_LIST_PRICE));
    BigDecimal unitPrice = decimal(patch.opt(FIELD_UNIT_PRICE));
    if (listPrice == null || listPrice.signum() == 0 || unitPrice == null) {
      return false;
    }
    BigDecimal discount = decimal(formState.opt(FIELD_DISCOUNT));
    BigDecimal current = discount == null ? listPrice
        : listPrice.subtract(listPrice.multiply(discount).divide(HUNDRED));
    return unitPrice.subtract(current).abs().compareTo(HALF_CENT) >= 0;
  }

  /**
   * Whether an update patch changes an input of the line's amounts — it carries
   * {@code orderedQuantity}, {@code unitPrice}, {@code grossUnitPrice} or {@code tax} — and leaves
   * {@code lineGrossAmount} to the server (a patch that sends it is the caller's, and is kept).
   */
  static boolean changesAmountInput(JSONObject patch) {
    if (patch == null || patch.has(FIELD_LINE_GROSS_AMOUNT)) {
      return false;
    }
    for (String input : AMOUNT_INPUTS) {
      if (patch.has(input)) {
        return true;
      }
    }
    return false;
  }

  /** A {@code PUT} or {@code PATCH}: the request is an update of a stored line. */
  static boolean isUpdate(String method) {
    return METHOD_PUT.equals(method) || METHOD_PATCH.equals(method);
  }

  /** The body carries {@code key} with a non-null value. */
  static boolean hasValue(JSONObject body, String key) {
    return body != null && body.has(key) && !body.isNull(key);
  }
}
