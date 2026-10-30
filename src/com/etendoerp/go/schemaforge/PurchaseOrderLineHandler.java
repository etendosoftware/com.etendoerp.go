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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

/**
 * Customization of purchase-order/lines (ETP-5881, ETP-6012, ETP-5852). On REST it keeps the
 * purchase line's own rules: the prices of a created line from the order's (purchase) price list
 * ({@link OrderLineDiscountSupport#setStandardPriceOnCreate}) and the stored amount inputs a sparse
 * update needs ({@link OrderLineDiscountSupport#completeAmountInputsOnUpdate}, ETP-6012); the other
 * sales line rules stay off there on purpose. Over MCP it runs every line rule of
 * {@link OrderLineCustomization} ({@link OrderLineDiscountSupport#applyLineRules}).
 *
 * <p>ETP-5881 created it to price a new purchase line from the order's price list
 * ({@link OrderLineDiscountSupport#setStandardPriceOnCreate}) and kept the other order line rules
 * off. ETP-5852 runs all of them for agents, as on sales lines: production agents reported a
 * {@code discount} on a purchase line that was stored but never turned into a price, and a line
 * created with an explicit {@code unitPrice} that kept {@code listPrice = 0}, so no discount had a
 * base. The purchase form already derives price and discount itself before it saves.
 *
 * <p><b>Binding.</b> Same as {@link SalesOrderLineHandler}: resolved by {@link NeoExtension} ahead
 * of the row's {@code Java_Qualifier}, which stays {@code orderLineHandler} for the readers that
 * look it up directly. No {@code @Named}, for the same reason.
 */
@NeoExtension(spec = "purchase-order", entity = "lines")
public class PurchaseOrderLineHandler extends OrderLineCustomization {

  /**
   * MCP: every line rule, as on sales lines. Any other channel: the purchase line's own pricing of
   * a created line and the stored amount inputs of a sparse update, exactly as before ETP-5852.
   */
  @Override
  protected void applyRules(NeoContext context) {
    if (context.isMcpOrigin()) {
      super.applyRules(context);
      return;
    }
    OrderLineDiscountSupport.setStandardPriceOnCreate(context);
    OrderLineDiscountSupport.completeAmountInputsOnUpdate(context);
  }
}
