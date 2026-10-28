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

import java.util.Map;

/**
 * Customization of sales-invoice/lines (ETP-5528): everything {@link InvoiceLineHandler} does, plus
 * the discounted price and the amounts of a written line — {@code lineNetAmount} and
 * {@code grossAmount} — which {@code etendo_create} never derives through the shared path, and the
 * recalculation of those amounts on an update (see {@link InvoiceLineAmountSupport}; ETP-5710 added
 * the discount and the update, and {@link PurchaseInvoiceLineHandler} shares them).
 *
 * <p><b>Binding.</b> Resolved by {@link NeoExtension} on every channel, ahead of the row's
 * {@code Java_Qualifier}. The row still carries {@code invoiceLineHandler}, on purpose: several
 * readers look the qualifier up directly instead of going through the dispatcher (the MCP read
 * post-hook, the REST CRUD gate, the field filter, {@code protectedCreateCalloutFields}), and they
 * keep resolving the parent class — whose behaviour this class inherits unchanged — so those
 * surfaces behave exactly as before. The price is the ERROR line
 * {@code NeoExtensionIndex.warnOnQualifierDisagreement} logs on each resolution, the same as the
 * order-line customizations; see {@code neo-headless.md} §4.12.20.
 *
 * <p>No {@code @Named}: the parent's {@code invoiceLineHandler} name is not inherited, and a second
 * bean carrying it would make the name ambiguous. The per-request state the parent carries from
 * {@code handle} into {@code afterHandle} (the imported source line) is kept: every channel runs
 * both hooks on the one instance its pre-dispatch resolved. {@code purchase-invoice/lines} has its
 * own customization, {@link PurchaseInvoiceLineHandler}, which shares these amount rules and adds
 * the {@code defaultTax} action (ETP-5585).
 *
 * <p>On a create into a sales rectificative, writes the {@code C_Invoice_Reverse} link the line
 * implies (ETP-6002, {@link SalesRectificativeLineLinkSupport}) — sales only: the purchase side
 * still links through its header PATCH.
 */
@NeoExtension(spec = "sales-invoice", entity = "lines")
public class SalesInvoiceLineHandler extends InvoiceLineHandler {

  /**
   * Runs the parent's pre-hook first — a response from it still short-circuits — then prices and
   * derives the amounts of a created line, or recalculates those of an updated one. The order
   * matters: on a return invoice the parent negates the quantity, and the amounts are derived from
   * the negated one.
   *
   * <p>Last, once every check has passed, a line created on a rectificative from a source invoice
   * line or a return line links the invoice to the one it rectifies (ETP-6002, see
   * {@link SalesRectificativeLineLinkSupport}). Before that, a line created from an order line
   * is bound to that order line's oldest shipment line still pending to invoice
   * ({@link SalesInvoiceShipmentLineResolver}, ETP-6002 IMP-1, the shared rule of
   * {@link OrderLineMovementAllocation}). {@code sourceInvoiceLineId} is read before the parent
   * runs because the parent removes it from the body.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    String sourceInvoiceLineId = SalesRectificativeLineLinkSupport.peekSourceInvoiceLineId(context);
    NeoResponse response = super.handle(context);
    if (response != null) {
      return response;
    }
    // Before the amounts: a line sent without a price takes the price list's (ETP-5881).
    InvoiceLineAmountSupport.setPriceFromPriceListOnCreate(context);
    response = InvoiceLineAmountSupport.deriveAmountsOnWrite(context);
    if (response != null) {
      return response;
    }
    SalesInvoiceShipmentLineResolver.bindPendingShipmentLineOnCreate(context, sourceInvoiceLineId);
    return SalesRectificativeLineLinkSupport.linkOnCreate(context, sourceInvoiceLineId);
  }

  /**
   * Publishes {@code sourceInvoiceLineId} to agents ({@code etendo_schema(view:"create")}) and lets
   * the MCP write verbs accept it without reporting it as an unknown field (ETP-6002). It is not a
   * spec field: the parent's pre-hook removes it from the body and persists it after the create,
   * and {@link SalesRectificativeLineLinkSupport} links the rectificative to the source line's
   * invoice from it.
   */
  @Override
  public Map<String, String> createDirectives() {
    return Map.of(SalesRectificativeLineLinkSupport.FIELD_SOURCE_INVOICE_LINE_ID,
        "Optional. The id of the sales invoice line this line copies, when this invoice is a "
            + "rectificative (factura rectificativa) correcting that one. Recorded on the new line, "
            + "and links this invoice to the source line's invoice as the invoice it rectifies, so "
            + "no separate link step is needed. An id that names no invoice line is refused (422 "
            + "sourceInvoiceLineNotFound). Create only.");
  }
}
