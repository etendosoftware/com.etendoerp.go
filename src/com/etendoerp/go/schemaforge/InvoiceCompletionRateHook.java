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
 * Blocks the completion of a foreign-currency invoice that has no exchange rate of its own, for
 * every completion that runs through {@code ProcessInvoiceUtil}: the NEO/headless one
 * ({@code InvoiceCompletionService}, so REST, batch and MCP) and the classic backoffice. Delegates
 * to {@link InvoiceExchangeRateValidator}; the refusal is the translated
 * {@code ETGO_ExchangeRateMissingOnComplete} (ETP-5840). The invoice header pre-hooks refuse the
 * NEO completions earlier with the same text, before the total-discount line is recalculated.
 */
@ApplicationScoped
public class InvoiceCompletionRateHook implements ProcessInvoiceHook {

  private static final String ACTION_COMPLETE = "CO";
  private static final String ERROR_TYPE = "Error";

  @Override
  public OBError preProcess(Invoice invoice, String strDocAction) {
    if (!ACTION_COMPLETE.equals(strDocAction)) {
      return null;
    }
    String message = InvoiceExchangeRateValidator.checkRateForCompletion(invoice);
    if (message == null) {
      return null;
    }
    OBError error = new OBError();
    error.setType(ERROR_TYPE);
    error.setTitle(OBMessageUtils.messageBD(ERROR_TYPE));
    error.setMessage(message);
    return error;
  }

  @Override
  public OBError postProcess(Invoice invoice, String strDocAction) {
    return null;
  }
}
