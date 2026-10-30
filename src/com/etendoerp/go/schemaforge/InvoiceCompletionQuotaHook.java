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

import javax.enterprise.context.ApplicationScoped;

import org.openbravo.advpaymentmngt.ProcessInvoiceHook;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.invoice.Invoice;

/**
 * ETP-6060 — refuses completing an invoice that would take its tenant over a {@code block} plan
 * quota, for every completion that runs through {@code ProcessInvoiceUtil}: NEO (REST, batch and
 * MCP all complete through {@link InvoiceCompletionService}) and the classic backoffice.
 *
 * <p>A resource that counts completed invoices ({@code e.documentStatus in ('CO', 'CL')}) does not
 * match the invoice when it is created as a draft, so the create check never refuses it. The check
 * runs in {@link #preProcess}, before {@code C_Invoice_Post}: in {@code postProcess} the other hooks
 * may already have sent the invoice to a tax authority (TicketBAI does), and the hooks run in no
 * guaranteed order. The question is asked of the invoice as the completion will leave it
 * ({@link NeoQuotaCreateCheck#requireAllowedStatusChange}); nothing is written.
 *
 * <p>The process answers a refusal with the hook's {@link OBError}; {@link InvoiceCompletionService}
 * also needs the 403 {@code QUOTA_EXCEEDED} body, which this hook hands over per thread
 * ({@link #takeRefusal}). Every {@link #preProcess} starts by clearing it, so a refusal left by a
 * backoffice completion, which nobody takes, never reaches a later NEO completion.
 */
@ApplicationScoped
public class InvoiceCompletionQuotaHook implements ProcessInvoiceHook {

  private static final String ACTION_COMPLETE = "CO";
  private static final String STATUS_COMPLETED = "CO";
  private static final String ERROR_TYPE = "Error";
  private static final ThreadLocal<NeoQuotaCreateCheck.Refused> REFUSAL = new ThreadLocal<>();

  @Override
  public OBError preProcess(Invoice invoice, String strDocAction) {
    REFUSAL.remove();
    if (!ACTION_COMPLETE.equals(strDocAction)) {
      return null;
    }
    try {
      NeoQuotaCreateCheck.requireAllowedStatusChange(invoice, Invoice.PROPERTY_DOCUMENTSTATUS,
          STATUS_COMPLETED);
      return null;
    } catch (NeoQuotaCreateCheck.Refused refused) {
      REFUSAL.set(refused);
      OBError error = new OBError();
      error.setType(ERROR_TYPE);
      error.setTitle(OBMessageUtils.messageBD(ERROR_TYPE));
      error.setMessage(refused.getMessage());
      return error;
    }
  }

  @Override
  public OBError postProcess(Invoice invoice, String strDocAction) {
    return null;
  }

  /**
   * The refusal of the last completion on this thread, cleared as it is read.
   *
   * @return the refusal, or null when the last completion was not refused by a quota
   */
  static NeoQuotaCreateCheck.Refused takeRefusal() {
    NeoQuotaCreateCheck.Refused refused = REFUSAL.get();
    REFUSAL.remove();
    return refused;
  }
}
