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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Utility for linking draft invoice lines to a freshly created shipment/receipt
 * inout line. Mirrors the UPDATE that the canonical {@code m_inout_create}
 * stored procedure performs in classic when generating a shipment/receipt from
 * an order. Without this step, {@code m_inout_post} can't create
 * {@code m_matchsi}/{@code m_matchinv} when the inout is later completed,
 * leaving the delivery-status column at 0% on the related invoices.
 */
final class InvoiceLineLinker {

  /**
   * Etendo's "System" user id. Used as the {@code UpdatedBy} fallback when no
   * user is present in the current {@link OBContext} (e.g. background
   * processes), matching the convention used elsewhere in Etendo.
   */
  private static final String SYSTEM_USER_ID = "0";

  private InvoiceLineLinker() {
  }

  /**
   * Links any draft invoice line that points to {@code orderLineId} and is
   * still unlinked to {@code newInoutLine}. Idempotent: rows already linked
   * to a different inout line are left untouched.
   *
   * <p>Caller is expected to have flushed the new inout line before calling
   * this method, so {@code newInoutLine.getId()} returns a persisted id.
   */
  static void linkPendingInvoiceLinesToInout(ShipmentInOutLine newInoutLine, String orderLineId) {
    OBDal.getInstance().getSession()
        .createNativeQuery(
            "UPDATE C_InvoiceLine "
            + "SET M_InOutLine_ID = :inoutLineId, "
            + "    Updated = now(), "
            + "    UpdatedBy = :userId "
            + "WHERE C_OrderLine_ID = :orderLineId "
            + "  AND M_InOutLine_ID IS NULL")
        .setParameter("inoutLineId", newInoutLine.getId())
        .setParameter("userId", currentUserIdOrSystem())
        .setParameter("orderLineId", orderLineId)
        .executeUpdate();
  }

  /**
   * Backfills the inout link on every draft invoice line of the given invoice
   * whose order line already has at least one shipment/receipt line. Mirrors
   * the lookup that {@code c_invoice_create} performs in classic when an
   * invoice is generated from an order, so the matching tables get populated
   * regardless of which document was created first (invoice or shipment).
   */
  static void linkInvoiceLinesToExistingInouts(String invoiceId) {
    OBDal.getInstance().getSession()
        .createNativeQuery(
            "UPDATE C_InvoiceLine il "
            + "SET M_InOutLine_ID = ("
            + "    SELECT MAX(iol.M_InOutLine_ID) "
            + "    FROM M_InOutLine iol "
            + "    WHERE iol.C_OrderLine_ID = il.C_OrderLine_ID), "
            + "    Updated = now(), "
            + "    UpdatedBy = :userId "
            + "WHERE il.C_Invoice_ID = :invoiceId "
            + "  AND il.M_InOutLine_ID IS NULL "
            + "  AND il.C_OrderLine_ID IS NOT NULL "
            + "  AND EXISTS ("
            + "    SELECT 1 FROM M_InOutLine iol "
            + "    WHERE iol.C_OrderLine_ID = il.C_OrderLine_ID)")
        .setParameter("userId", currentUserIdOrSystem())
        .setParameter("invoiceId", invoiceId)
        .executeUpdate();
  }

  /**
   * Returns the current user's id, or the System user id when the context has
   * no user (background tasks, system-init paths). Avoids NPEs at the call
   * sites of {@link #linkPendingInvoiceLinesToInout}.
   */
  private static String currentUserIdOrSystem() {
    OBContext ctx = OBContext.getOBContext();
    if (ctx == null) return SYSTEM_USER_ID;
    User user = ctx.getUser();
    return user != null ? user.getId() : SYSTEM_USER_ID;
  }
}
