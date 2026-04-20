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
 * NeoHandler for the Purchase Order header entity.
 *
 * Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code createGoodsReceipt} → {@link CreateGoodsReceiptHandler}</li>
 *   <li>{@code createPurchaseInvoice} → {@link CreatePurchaseInvoiceHandler}</li>
 * </ul>
 *
 * Other actions (e.g. {@code documentAction}) return {@code null} here and
 * fall through to the default AD process execution path.
 */
@Named("purchaseOrderHeaderHandler")
public class PurchaseOrderHeaderHandler implements NeoHandler {

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse result = cloneRecordHandler.handle(context);
    if (result != null) {
      return result;
    }
    result = new CreateGoodsReceiptHandler().handle(context);
    if (result != null) {
      return result;
    }
    return new CreatePurchaseInvoiceHandler().handle(context);
  }
}
