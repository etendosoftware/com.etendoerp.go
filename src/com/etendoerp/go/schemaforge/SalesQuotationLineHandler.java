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

import java.util.Set;

/**
 * Customization of sales-quotation/quotationLine (ETP-5528): everything
 * {@link OrderLineHandler} does, plus the order line's own price rules: the standard price of a new
 * line and a discount its price does not reflect yet (see {@link OrderLineDiscountSupport}).
 *
 * <p><b>Binding.</b> Resolved by {@link NeoExtension} on every channel, ahead of the row's
 * {@code Java_Qualifier}. The row still carries {@code orderLineHandler}, on purpose: several
 * readers look the qualifier up directly instead of going through the dispatcher (the MCP read
 * post-hook, the REST CRUD gate, the field filter, {@code protectedCreateCalloutFields}), and they
 * keep resolving the parent class — whose behaviour this class inherits unchanged — so those
 * surfaces behave exactly as before. The price is the ERROR line
 * {@code NeoExtensionIndex.warnOnQualifierDisagreement} logs on each resolution; see
 * {@code neo-headless.md} §4.12.20.
 *
 * <p>No {@code @Named}: the parent's {@code orderLineHandler} name is not inherited, and a second
 * bean carrying it would make the name ambiguous. {@code purchase-order/lines} is not annotated and
 * stays on {@link OrderLineHandler} alone.
 *
 * <p>{@link SalesOrderLineHandler} is the same customization for the other entity that opts in.
 * They are two classes only because {@link NeoExtension} is not repeatable; the behaviour lives once, in
 * {@link OrderLineDiscountSupport}.
 */
@NeoExtension(spec = "sales-quotation", entity = "quotationLine")
public class SalesQuotationLineHandler extends OrderLineHandler {

  /** DAL property of {@code C_OrderLine.C_Tax_ID}. */
  private static final String FIELD_TAX = "tax";

  /**
   * Runs the parent's pre-hook first — a response from it still short-circuits — then sets the
   * standard price of a new line, then applies a discount its price does not reflect yet, and
   * finally derives the gross amount of a new line. The order matters: the discount re-fire reads
   * the standard price, and the amounts read the discounted price.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse response = super.handle(context);
    if (response != null) {
      return response;
    }
    OrderLineDiscountSupport.setStandardPriceOnCreate(context);
    OrderLineDiscountSupport.applyDiscount(context);
    OrderLineDiscountSupport.deriveAmountsOnCreate(context);
    return null;
  }

  /**
   * The tax of a new line is the server's to choose (ETP-5535): {@code SL_Order_Product}, which the
   * create callout cascade fires for the product, sets it from the product's tax category, the
   * header's order date and the organization — exactly what the UI does when a product is picked.
   * {@code product} is itself required, so the source is always there. Declared here so
   * {@code neo_schema(view:"create")} stops listing {@code tax} as required: an agent sent looking
   * for a tax by name finds one the server would not have picked, or none at all.
   *
   * <p>A caller may still send a tax; the cascade keeps a value the caller provided.
   */
  @Override
  public Set<String> serverResolvedCreateFields() {
    return Set.of(FIELD_TAX);
  }
}
