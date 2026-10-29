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

/**
 * Customization of purchase-order/lines (ETP-5881): everything {@link OrderLineHandler} does, plus
 * the prices of a created line from the order's (purchase) price list
 * ({@link OrderLineDiscountSupport#setStandardPriceOnCreate}): the unit price when the line was
 * sent without one, and always the standard price, as the form's {@code SL_Order_Product} does.
 * The other sales order rules (discount, amounts) are not applied: purchase lines were on the
 * plain parent on purpose. Only {@link OrderLineDiscountSupport#completeAmountInputsOnUpdate} is
 * (ETP-6012): a sparse update must carry the line's price and tax so its gross is not stored at 0
 * or at a 0 % rate.
 *
 * <p><b>Binding.</b> Resolved by {@link NeoExtension} on every channel, ahead of the row's
 * {@code Java_Qualifier}, which stays {@code orderLineHandler} for the readers that look it up
 * directly — the same arrangement, and the same {@code warnOnQualifierDisagreement} ERROR line, as
 * {@link SalesOrderLineHandler}. No {@code @Named}: the parent's name is not inherited.
 */
@NeoExtension(spec = "purchase-order", entity = "lines")
public class PurchaseOrderLineHandler extends OrderLineHandler {

  /** Runs the parent's pre-hook first — a response from it still short-circuits — then prices. */
  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse response = super.handle(context);
    if (response != null) {
      return response;
    }
    OrderLineDiscountSupport.setStandardPriceOnCreate(context);
    OrderLineDiscountSupport.completeAmountInputsOnUpdate(context);
    return null;
  }
}
