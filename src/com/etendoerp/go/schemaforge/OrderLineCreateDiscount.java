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
import org.codehaus.jettison.json.JSONObject;

/**
 * The discount a new order line's price implies, one of the line rules of
 * {@link OrderLineDiscountSupport#applyLineRules} (ETP-5852), kept apart so that class stays
 * within Sonar's method budget.
 */
final class OrderLineCreateDiscount {

  private static final Logger log = LogManager.getLogger(OrderLineCreateDiscount.class);

  private static final String METHOD_POST = "POST";
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

  private OrderLineCreateDiscount() {
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
