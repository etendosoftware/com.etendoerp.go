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
 * Customization of sales-invoice/lines (ETP-5528): everything {@link InvoiceLineHandler} does, plus
 * the amounts of a new line — {@code lineNetAmount} and {@code grossAmount} — which
 * {@code etendo_create} never derives through the shared path (see {@link InvoiceLineAmountSupport}).
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
 * both hooks on the one instance its pre-dispatch resolved. {@code purchase-invoice/lines} is not
 * annotated and stays on {@link InvoiceLineHandler} alone.
 */
@NeoExtension(spec = "sales-invoice", entity = "lines")
public class SalesInvoiceLineHandler extends InvoiceLineHandler {

  /**
   * Runs the parent's pre-hook first — a response from it still short-circuits — then derives the
   * amounts of a new line. The order matters: on a return invoice the parent negates the quantity,
   * and the amounts are derived from the negated one.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse response = super.handle(context);
    if (response != null) {
      return response;
    }
    InvoiceLineAmountSupport.deriveAmountsOnCreate(context);
    return null;
  }
}
