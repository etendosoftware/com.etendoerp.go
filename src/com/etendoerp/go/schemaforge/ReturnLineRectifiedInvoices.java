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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Which invoices a RETURN line rectifies (ETP-6002): the COMPLETED invoices of the given business
 * partner that billed the shipment / receipt line the return line cancels
 * ({@code M_InOutLine.Canceled_Inoutline_ID} → {@code C_InvoiceLine.M_InOutLine_ID}). The one
 * definition of that chain for every caller that links a rectificative to them in
 * {@code C_Invoice_Reverse}: the sales line create ({@link SalesRectificativeLineLinkSupport}) and
 * the return import action ({@link ImportInOutLinesIntoInvoiceHandler}). Direction-neutral: it reads
 * movement and invoice lines only; which side links is the caller's decision.
 *
 * <p>Only the partner's own invoices: {@code C_INVOICE_REVERSE_TRG} refuses any cross-partner link,
 * so one such invoice in the chain (anomalous data) would otherwise refuse the whole write.
 * Nothing for a line that is not a return line, whose original line was never invoiced, or a blank
 * partner.
 */
final class ReturnLineRectifiedInvoices {

  private ReturnLineRectifiedInvoices() {
  }

  /**
   * @param returnLine        the return line, may be {@code null}
   * @param businessPartnerId the rectificative's business partner, may be blank
   * @return the invoices, distinct, in query order; never {@code null}
   */
  static List<Invoice> of(ShipmentInOutLine returnLine, String businessPartnerId) {
    ShipmentInOutLine original = returnLine != null ? returnLine.getCanceledInoutLine() : null;
    if (original == null || StringUtils.isBlank(businessPartnerId)) {
      return new ArrayList<>();
    }
    Map<String, Invoice> invoices = new LinkedHashMap<>();
    for (InvoiceLine billed : OBDal.getInstance()
        .createQuery(InvoiceLine.class,
            "as il where il.goodsShipmentLine.id = :lineId and il.invoice.documentStatus = 'CO'"
                + " and il.invoice.businessPartner.id = :bpId")
        .setNamedParameter("lineId", original.getId())
        .setNamedParameter("bpId", businessPartnerId)
        .list()) {
      Invoice invoice = billed.getInvoice();
      if (invoice != null) {
        invoices.putIfAbsent(invoice.getId(), invoice);
      }
    }
    return new ArrayList<>(invoices.values());
  }
}
