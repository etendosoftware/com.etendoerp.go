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
 * NeoHandler for the Sales Order header entity.
 *
 * Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code createShipment} → {@link CreateShipmentHandler}</li>
 *   <li>{@code createDraftInvoice} / {@code checkDraftInvoice} / {@code listInvoices} → {@link CreateDraftInvoiceHandler}</li>
 * </ul>
 *
 * GET post-hook (hasLinkedDocuments annotation) is inherited from
 * {@link AbstractOrderHeaderHandler}.
 */
@Named("salesOrderHeaderHandler")
public class SalesOrderHeaderHandler extends AbstractOrderHeaderHandler {

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private CreateShipmentHandler createShipmentHandler;

  @Inject
  private CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Override
  public NeoResponse handle(NeoContext context) {
    return NeoHeaderActionRouter.dispatch(
        context,
        cloneRecordHandler,
        createShipmentHandler,
        createDraftInvoiceHandler);
  }
}
