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
import java.util.Collection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

/**
 * The discount of a new order line created over MCP, two of the line rules of
 * {@link OrderLineDiscountSupport#applyLineRules}, kept apart so that class stays within Sonar's
 * method budget: the discount a sent price implies ({@link #derive}, ETP-5852), and the discount
 * the defaults cascade left on a line sent with only its list price
 * ({@link #resetCascadeDiscount}, ETP-5528).
 */
final class OrderLineCreateDiscount {

  private static final Logger log = LogManager.getLogger(OrderLineCreateDiscount.class);

  private static final String METHOD_POST = "POST";
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

  /** Context attribute {@link #resetCascadeDiscount} sets when it reset the discount. */
  static final String ATTR_CASCADE_DISCOUNT_RESET = "orderLine.cascadeDiscountReset";

  private OrderLineCreateDiscount() {
  }

  /**
   * The "lone listPrice" create (ETP-5528, live pre-QA): an {@code etendo_create} that sends
   * {@code listPrice} and neither {@code discount} nor a price of its own gets {@code discount 0},
   * the form's value for a "Precio" typed with no discount, so
   * {@link LinePriceListSupport#applyClientListPrice} then prices it at
   * {@code listPrice × (1 − 0 / 100)} and {@link OrderLineDiscountSupport#deriveAmountsOnCreate}
   * derives the amounts from that price.
   *
   * <p><b>Why the body's discount is not the caller's.</b> On {@code etendo_create} the defaults
   * cascade runs before the pre-hook. {@code SL_Order_Product} publishes the product selector's
   * price — 0, the selector has no price-list context — as {@code unitPrice};
   * {@code McpLinePriceInjector} leaves it, as the agent chose a list price; and
   * {@code SL_Order_Amt} then derives {@code discount = (listPrice − 0) / listPrice × 100 = 100}
   * from it. {@code discount} is not a key the agent sent, so the cascade may write it, and the list
   * price was then applied at 100 %: {@code listPrice 100, orderedQuantity 2} persisted discount
   * 100, unit price 0 and amounts 0 — a free line, with no warning. A discount derived from a price
   * of 0 is not a choice.
   *
   * <p><b>MCP {@code etendo_create} only</b> — see {@link #qualifiesForCascadeDiscountReset} for
   * the exact condition. On success the context is marked ({@link #cascadeDiscountWasReset}), so the
   * customization can declare {@code discount} governed and the cascade's 100 is not reported to the
   * agent as a {@code supersededDefaults} value to send back. Total: never throws.
   *
   * @param ctx the pre-hook context; its request body is mutated in place
   */
  static void resetCascadeDiscount(NeoContext ctx) {
    if (!qualifiesForCascadeDiscountReset(ctx)) {
      return;
    }
    try {
      ctx.getRequestBody().put(OrderLineDiscountSupport.FIELD_DISCOUNT, BigDecimal.ZERO);
      ctx.setAttribute(ATTR_CASCADE_DISCOUNT_RESET, Boolean.TRUE);
    } catch (Exception e) {
      log.warn("[ORDER-LINE-PRICE] Could not reset the cascade discount of a new line on {} {}: {}",
          ctx.getSpecName(), ctx.getEntityName(), e.getMessage());
    }
  }

  /**
   * Whether {@link #resetCascadeDiscount} applies to this pre-hook context: an MCP CRUD create that
   * carries {@link NeoContext#ATTR_CLIENT_PROVIDED_FIELDS} — set only by {@code etendo_create}, the
   * one channel that fills defaults before the pre-hook; without it the body is what the caller
   * sent (REST single and batch, {@code etendo_batch}, and so the form, which always sends
   * {@code unitPrice} with {@code listPrice} anyway) — whose agent sent {@code listPrice} and none
   * of {@code discount} (an explicit 100 % stays 100), {@code unitPrice} or {@code grossUnitPrice}
   * (a negotiated price keeps deriving its discount in {@link #derive}), and whose body has a
   * non-zero list price and no price yet ({@code unitPrice} absent or 0, the cascade's 0).
   *
   * <p>Read on the body BEFORE the line is priced: once priced, {@code unitPrice} is the list
   * price, so the governed-field question after the pre-hook asks
   * {@link #cascadeDiscountWasReset} instead of re-evaluating this.
   */
  static boolean qualifiesForCascadeDiscountReset(NeoContext ctx) {
    if (ctx == null || !ctx.isMcpOrigin() || !NeoEndpointType.CRUD.equals(ctx.getEndpointType())
        || !METHOD_POST.equals(ctx.getHttpMethod())) {
      return false;
    }
    Object provided = ctx.getAttribute(NeoContext.ATTR_CLIENT_PROVIDED_FIELDS);
    JSONObject body = ctx.getRequestBody();
    if (!(provided instanceof Collection) || body == null) {
      return false;
    }
    return sentOnlyListPrice((Collection<?>) provided)
        && !LineAmountSupport.isZero(body.opt(OrderLineDiscountSupport.FIELD_LIST_PRICE))
        && LineAmountSupport.isZero(body.opt(OrderLineDiscountSupport.FIELD_UNIT_PRICE));
  }

  /**
   * @return {@code true} when {@link #resetCascadeDiscount} reset this request's discount — the
   *         answer {@code OrderLineCustomization#governedCreateFields} reads after the pre-hook
   */
  static boolean cascadeDiscountWasReset(NeoContext ctx) {
    return ctx != null && Boolean.TRUE.equals(ctx.getAttribute(ATTR_CASCADE_DISCOUNT_RESET));
  }

  /** The agent priced the line by its list price alone: no discount, net or gross price sent. */
  private static boolean sentOnlyListPrice(Collection<?> sent) {
    return sent.contains(OrderLineDiscountSupport.FIELD_LIST_PRICE)
        && !sent.contains(OrderLineDiscountSupport.FIELD_DISCOUNT)
        && !sent.contains(OrderLineDiscountSupport.FIELD_UNIT_PRICE)
        && !sent.contains(OrderLineDiscountSupport.FIELD_GROSS_UNIT_PRICE);
  }

  /**
   * A new line whose body carries a {@code unitPrice} below or above its {@code listPrice} and no
   * {@code discount} gets the discount that price implies,
   * {@code (listPrice − unitPrice) / listPrice × 100} rounded to two decimals — what the form shows
   * when a price is typed over the tariff. An agent that negotiated 29.70 on a list of 33 used to
   * leave a line "29.70, discount 0", so the discount was invisible in the UI and in reports.
   *
   * <p><b>MCP only.</b> The form never needs it — every form write sends {@code discount} (the
   * inline row and the side panel both carry it) — and a REST create keeps the values it sent, as
   * before. A body with a {@code discount} other than 0, a price equal to the list price within
   * half a cent, a zero or missing list price, and an explicit gross price abstain. Total: never
   * throws.
   *
   * @param ctx the pre-hook context; its request body is mutated in place
   */
  static void derive(NeoContext ctx) {
    if (ctx == null || !ctx.isMcpOrigin() || !NeoEndpointType.CRUD.equals(ctx.getEndpointType())
        || !METHOD_POST.equals(ctx.getHttpMethod())) {
      return;
    }
    JSONObject body = ctx.getRequestBody();
    // A zero discount is what the defaults pass and the AD column default put there, so it is not a
    // choice; the form never sends 0 with a price off the list (it sends list × (1 − discount)).
    if (body == null || !LineAmountSupport.isZero(body.opt(OrderLineDiscountSupport.FIELD_DISCOUNT))
        || OrderLineDiscountSupport.sentExplicitGrossPrice(body)) {
      return;
    }
    BigDecimal listPrice =
        LineAmountSupport.decimal(body.opt(OrderLineDiscountSupport.FIELD_LIST_PRICE));
    BigDecimal unitPrice =
        LineAmountSupport.decimal(body.opt(OrderLineDiscountSupport.FIELD_UNIT_PRICE));
    if (listPrice == null || listPrice.signum() == 0 || unitPrice == null
        || unitPrice.subtract(listPrice).abs().compareTo(HALF_CENT) < 0) {
      return;
    }
    try {
      body.put(OrderLineDiscountSupport.FIELD_DISCOUNT, listPrice.subtract(unitPrice)
          .multiply(HUNDRED).divide(listPrice, 2, RoundingMode.HALF_UP));
    } catch (Exception e) {
      log.warn("[ORDER-LINE-PRICE] Could not derive the discount of a new line on {} {}: {}",
          ctx.getSpecName(), ctx.getEntityName(), e.getMessage());
    }
  }
}
