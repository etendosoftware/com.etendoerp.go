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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * The rectification-link step of a SALES return import (ETP-6002), split out of
 * {@link ImportInOutLinesIntoInvoiceHandler} (java:S1448): links the rectificative in
 * {@code C_Invoice_Reverse} to every invoice the planned return lines rectify
 * ({@link ReturnLineRectifiedInvoices}), deduped ({@link #linkIfMissing}, i.e.
 * {@link InvoiceRectificationLinkSupport#ensureReverseLink}), and flushes so a trigger refusal
 * surfaces before any line is created. The handler decides WHEN (sales only, after the plan and
 * before the lines) and how a refusal is answered; this class only does the linking.
 *
 * <p>Not final and with protected seams, so the handler's unit test can replace the DAL reads and
 * writes with a recording double.
 */
class ReturnImportRectificationLinks {

  /**
   * Links {@code invoice} to the invoices the planned return lines rectify.
   *
   * @param invoice        the draft rectificative
   * @param linesById      the return's lines, by id
   * @param plannedLineIds the return lines being imported
   * @return the invoices the rectificative is linked to through these lines, {@code [{id,
   *     documentNo}]} (already-linked ones included)
   * @throws JSONException if the response data cannot be built
   */
  JSONArray link(Invoice invoice, Map<String, ShipmentInOutLine> linesById,
      Collection<String> plannedLineIds) throws JSONException {
    String partnerId = invoice.getBusinessPartner() == null ? null
        : invoice.getBusinessPartner().getId();
    Map<String, Invoice> origins = new LinkedHashMap<>();
    for (String lineId : plannedLineIds) {
      for (Invoice origin : rectifiedInvoicesOf(linesById.get(lineId), partnerId)) {
        if (!origin.getId().equals(invoice.getId())) {
          origins.putIfAbsent(origin.getId(), origin);
        }
      }
    }
    boolean created = false;
    JSONArray linked = new JSONArray();
    for (Invoice origin : origins.values()) {
      created |= linkIfMissing(invoice, origin);
      linked.put(new JSONObject().put("id", origin.getId())
          .put("documentNo", origin.getDocumentNo()));
    }
    if (created) {
      flushLinks();
    }
    return linked;
  }

  /** Seam: the invoices a return line rectifies, for the rectificative's partner. */
  protected List<Invoice> rectifiedInvoicesOf(ShipmentInOutLine returnLine, String partnerId) {
    return ReturnLineRectifiedInvoices.of(returnLine, partnerId);
  }

  /** Seam: the {@code C_Invoice_Reverse} row, created unless the pair is already linked. */
  protected boolean linkIfMissing(Invoice invoice, Invoice origin) {
    return InvoiceRectificationLinkSupport.ensureReverseLink(invoice, origin);
  }

  /** Seam: flushes the links, so the trigger runs before the lines are created. */
  protected void flushLinks() {
    OBDal.getInstance().flush();
  }
}
