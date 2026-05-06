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

import javax.inject.Inject;
import javax.inject.Named;

/**
 * NeoHandler for the Sales Quotation header entity.
 *
 * Dispatches custom ACTION requests:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code createDraftInvoice} / {@code checkDraftInvoice} / {@code listInvoices} → {@link CreateDraftInvoiceHandler}</li>
 *   <li>{@code rejectQuotation} → {@link RejectQuotationHandler}</li>
 *   <li>{@code createRejectReason} → {@link CreateRejectReasonHandler}</li>
 * </ul>
 */
@Named("salesQuotationHeaderHandler")
public class SalesQuotationHeaderHandler implements NeoHandler {

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse result = cloneRecordHandler.handle(context);
    if (result != null) {
      return result;
    }
    result = new RejectQuotationHandler().handle(context);
    if (result != null) {
      return result;
    }
    result = new CreateRejectReasonHandler().handle(context);
    if (result != null) {
      return result;
    }
    return new CreateDraftInvoiceHandler().handle(context);
  }
}
