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

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Creates a Return Material Receipt (C-) from a completed Goods Shipment (sales).
 * Invoked as:
 *   POST /sws/neo/goods-shipment/goodsShipment/{shipmentId}/action/createReturn
 *
 * Request body (ETP-5711): { "lines": [{ "lineId": "...", "returnQuantity": 3 }], "reason": "..." },
 * both optional: no lines returns everything still returnable. The return is always left in DRAFT.
 * Response:     { "response": { "data": { "id", "documentNo", "documentStatus", "lines": [...] } } }
 *
 * Shared logic lives in {@link NeoReturnReceiptService}.
 * For purchase returns see {@link CreatePurchaseReturnHandler}.
 */
@Named("createReturnReceiptHandler")
public class CreateReturnReceiptHandler implements NeoHandler {

  /**
   * The contract published to agents for {@code createReturn} (ETP-5711), referenced explicitly
   * from {@link GoodsShipmentHeaderHandler#actionContracts()}. REST and the SPA do not judge a call
   * against it; the MCP does.
   */
  static final NeoActionContract CONTRACT = ReturnFromDocumentSupport.contract("createReturn",
      "goods shipment", "return material receipt",
      "complete it with documentAction (docAction CO) to book the stock movement, or add and "
          + "edit lines first through the return-material-receipt window.");

  @Override
  public NeoResponse handle(NeoContext context) {
    return NeoReturnReceiptService.createReturn(context, "goods-shipment", true, "C-");
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }
}
