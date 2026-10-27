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
 * Customization of purchase-order/lines (ETP-5881, ETP-5852): the behaviour lives in
 * {@link OrderLineCustomization}; this class only binds it to the entity.
 *
 * <p>ETP-5881 created it to price a new purchase line from the order's price list
 * ({@link OrderLineDiscountSupport#setStandardPriceOnCreate}) and kept the other order line rules
 * off. ETP-5852 extends it to all of them, as on sales lines: production agents reported a
 * {@code discount} on a purchase line that was stored but never turned into a price, and a line
 * created with an explicit {@code unitPrice} that kept {@code listPrice = 0}, so no discount had a
 * base. The purchase form applies the same discount and amount rules as the sales one.
 *
 * <p><b>Binding.</b> Same as {@link SalesOrderLineHandler}: resolved by {@link NeoExtension} ahead
 * of the row's {@code Java_Qualifier}, which stays {@code orderLineHandler} for the readers that
 * look it up directly. No {@code @Named}, for the same reason.
 */
@NeoExtension(spec = "purchase-order", entity = "lines")
public class PurchaseOrderLineHandler extends OrderLineCustomization {
}
