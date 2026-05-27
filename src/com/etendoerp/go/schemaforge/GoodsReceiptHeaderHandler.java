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
 * NeoHandler for the Goods Receipt header entity.
 *
 * <p>Routes custom ACTION requests:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code createPurchaseInvoice} → {@link CreatePurchaseInvoiceHandler}</li>
 *   <li>{@code createPurchaseReturn} → {@link CreatePurchaseReturnHandler}</li>
 * </ul>
 */
@Named("goodsReceiptHeaderHandler")
public class GoodsReceiptHeaderHandler implements NeoHandler {

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private CreatePurchaseInvoiceHandler createPurchaseInvoiceHandler;

  @Inject
  private CreatePurchaseReturnHandler createPurchaseReturnHandler;

  @Override
  public NeoResponse handle(NeoContext context) {
    return NeoHeaderActionRouter.dispatch(context,
        cloneRecordHandler, createPurchaseInvoiceHandler, createPurchaseReturnHandler);
  }
}
