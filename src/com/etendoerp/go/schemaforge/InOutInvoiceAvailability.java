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
import java.util.Collections;
import java.util.Map;

import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * What the goods-receipt and goods-shipment header customizations say about their invoice action
 * on ONE document (ETP-5714): it does not apply when nothing is left to invoice, or when a draft
 * invoice already covers what is left.
 *
 * <p>The answer is the one the action itself gives: {@code createPurchaseInvoice} /
 * {@code createDraftInvoice} on a receipt or shipment refuse with "has nothing left to invoice:
 * every line is already invoiced or covered by a draft invoice" computed by
 * {@link NeoInvoiceSupport#computePendingQtyPerLineOrThrow}. This class asks the same question
 * before the call. Called explicitly by each header handler with its own action name; nothing here
 * selects an entity. Advisory only: the action's own guard stays the enforcement, and a failed
 * lookup propagates to the caller, which answers "nothing derived" and logs it.</p>
 */
final class InOutInvoiceAvailability {

  private InOutInvoiceAvailability() {
  }

  /**
   * The invoice action when it has nothing to do on this completed document.
   *
   * @param subject       the record the agent asked about
   * @param invoiceAction the action that invoices this document
   * @param documentLabel what the document is called in a sentence ("goods receipt")
   * @return {@code invoiceAction → reason}; empty for anything that is not a completed
   *         receipt/shipment, or when something is still left to invoice
   */
  static Map<String, String> unavailable(BaseOBObject subject, String invoiceAction,
      String documentLabel) {
    if (!(subject instanceof ShipmentInOut)) {
      return Collections.emptyMap();
    }
    ShipmentInOut inout = (ShipmentInOut) subject;
    if (!DocumentAvailability.STATUS_COMPLETED.equals(inout.getDocumentStatus())) {
      return Collections.emptyMap();
    }
    Map<String, BigDecimal> leftToInvoice =
        NeoInvoiceSupport.computePendingQtyPerLineOrThrow(inout.getId(), true);
    if (!leftToInvoice.isEmpty()) {
      return Collections.emptyMap();
    }
    boolean draftCovers =
        !NeoInvoiceSupport.computePendingQtyPerLineOrThrow(inout.getId(), false).isEmpty();
    return Map.of(invoiceAction, draftCovers
        ? "A draft invoice already covers what is left to invoice on this " + documentLabel
            + "; complete or delete it instead of creating another one."
        : "Nothing is left to invoice: every line of this " + documentLabel
            + " is already invoiced.");
  }
}
