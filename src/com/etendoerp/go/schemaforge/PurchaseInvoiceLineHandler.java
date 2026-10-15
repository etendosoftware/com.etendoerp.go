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
 * Customization of purchase-invoice/lines (ETP-5710): everything {@link InvoiceLineHandler} does,
 * plus the discounted price and the amounts of a written line — {@code lineNetAmount} and
 * {@code grossAmount} — which nothing on the server derived for a purchase line (a line an agent
 * wrote persisted {@code LineNetAmt 0}, so the header total, the tax rows and the payment plan came
 * out wrong). The rules live in {@link InvoiceLineAmountSupport}, shared with
 * {@link SalesInvoiceLineHandler}.
 *
 * <p><b>Binding.</b> Resolved by {@link NeoExtension} on every channel, ahead of the row's
 * {@code Java_Qualifier} ({@code NeoExtensionDispatcher.resolve} asks {@code NeoExtensionIndex}
 * first and falls back to the qualifier only on a miss). The row still carries
 * {@code invoiceLineHandler}, on purpose and exactly as {@link SalesInvoiceLineHandler}'s does:
 * several readers look the qualifier up directly instead of going through the dispatcher, and they
 * keep resolving the parent class, whose behaviour this class inherits unchanged. The price is the
 * ERROR line {@code NeoExtensionIndex.warnOnQualifierDisagreement} logs on each resolution; see
 * {@code neo-headless.md} §4.12.20. No {@code @Named}: the parent's name is not inherited and a
 * second bean carrying it would make it ambiguous.
 */
@NeoExtension(spec = "purchase-invoice", entity = "lines")
public class PurchaseInvoiceLineHandler extends InvoiceLineHandler {

  /**
   * Runs the parent's pre-hook first — a response from it still short-circuits — then prices and
   * derives the amounts of a created line, or recalculates those of an updated one. The order
   * matters: on a return invoice the parent negates the quantity, and the amounts are derived from
   * the negated one.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse response = super.handle(context);
    if (response != null) {
      return response;
    }
    return InvoiceLineAmountSupport.deriveAmountsOnWrite(context);
  }
}
