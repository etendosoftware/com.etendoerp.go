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

import javax.inject.Named;

/**
 * Creates a Return Material Receipt (C-) from a completed Goods Shipment (sales).
 * Invoked as:
 *   POST /sws/neo/goods-shipment/goodsShipment/{shipmentId}/action/createReturn
 *
 * Request body: { "lines": [{ "lineId": "...", "returnQuantity": 3 }] }
 * Response:     { "response": { "data": { "id": "...", "documentNo": "..." } } }
 *
 * Shared logic lives in {@link NeoReturnReceiptService}.
 * For purchase returns see CreatePurchaseReturnHandler (future).
 */
@Named("createReturnReceiptHandler")
public class CreateReturnReceiptHandler implements NeoHandler {

  @Override
  public NeoResponse handle(NeoContext context) {
    return NeoReturnReceiptService.createReturn(context, "goods-shipment", true, "C-");
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }
}
