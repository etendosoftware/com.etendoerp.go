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
 * The customization shared by the order line entities that opt in (ETP-5528, ETP-5852):
 * {@link SalesOrderLineHandler} ({@code sales-order/lines}), {@link SalesQuotationLineHandler}
 * ({@code sales-quotation/quotationLine}) and {@link PurchaseOrderLineHandler}
 * ({@code purchase-order/lines}). Everything {@link OrderLineHandler} does, plus the order line's
 * own price rules (see {@link OrderLineDiscountSupport}). All three are {@code C_OrderLine}, with
 * the same column callouts and trigger, so the rules are the same; purchase lines joined in
 * ETP-5852 after production agents reported a discount that was stored but never applied.
 *
 * <p>Abstract and not annotated, so it is never a bean nor an index entry: each concrete subclass
 * carries its own {@link NeoExtension} (the annotation is not repeatable and not inherited), and
 * {@code NeoExtensionIndex} reads it off the bean class.
 */
abstract class OrderLineCustomization extends OrderLineHandler {

  /**
   * Runs the parent's pre-hook first — a response from it still short-circuits — then the line
   * rules of {@link OrderLineDiscountSupport#applyLineRules}: the standard (and, when missing, list)
   * price of a new line, a discount its price does not reflect yet, the discount a new price
   * implies, and the gross amount of a new or updated line. The order lives there, once.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse response = super.handle(context);
    if (response != null) {
      return response;
    }
    applyRules(context);
    return null;
  }

  /**
   * The line rules this entity runs once the parent's pre-hook let the request through; by
   * default all of them ({@link OrderLineDiscountSupport#applyLineRules}). A subclass narrows them
   * per channel.
   *
   * @param context the pre-hook context; its request body is mutated in place
   */
  protected void applyRules(NeoContext context) {
    OrderLineDiscountSupport.applyLineRules(context);
  }

  /**
   * The tax and the net amount of a new line are the server's to derive
   * ({@link OrderLineDiscountSupport#SERVER_RESOLVED_CREATE_FIELDS}, ETP-5535 / ETP-5528): the
   * create callout cascade fires {@code SL_Order_Product} for the product — which sets the tax from
   * the product's tax category, the header's order date and the organization, exactly what the UI
   * does when a product is picked — and {@code SL_Order_Amt} from the price it publishes, which
   * sets {@code lineNetAmount}; the {@code C_OrderLine} trigger recomputes the net amount on insert
   * anyway. {@code product} is itself required, so the source is always there. Declared here so
   * {@code etendo_schema(view:"create")} stops listing them as required: an agent sent looking for a
   * tax by name finds one the server would not have picked, and one that computes the net amount
   * itself computes a value the trigger discards.
   *
   * <p>Both entities are the same table, with the same column callouts and trigger. On
   * {@code sales-order/lines} {@code lineNetAmount} is published read-only ({@code system}) and
   * never appears in {@code view:"create"}, so there the declaration only changes {@code tax};
   * it is kept so it stays true of the entity rather than of its current field visibility.
   *
   * <p>A caller may still send a tax; the cascade keeps a value the caller provided.
   */
  @Override
  public Set<String> serverResolvedCreateFields() {
    return OrderLineDiscountSupport.SERVER_RESOLVED_CREATE_FIELDS;
  }
}
