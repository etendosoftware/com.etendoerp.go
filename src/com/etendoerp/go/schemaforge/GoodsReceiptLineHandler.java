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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

/**
 * NeoHandler for the Goods Receipt line entity ({@code goodsReceiptLine}).
 *
 * <p>Extends {@link AbstractInOutLineHandler} for the behavior shared with Goods Shipment
 * (invoice-line linking, order/invoice qty and product-code enrichment on GET), and adds one
 * receipt-only fix (ETP-4671):
 *
 * <h3>Callout post-hook: strip stock-derived {@code movementQuantity}</h3>
 * The classic {@code SL_InOutLine_Product} callout (shared by every {@code M_InOutLine}-based
 * window, per its own source comment) echoes back the product selector's on-hand-stock
 * auxiliary value as the new {@code movementQuantity} whenever the line is not created from an
 * order import. That default makes sense for a shipment (pick from what's in stock) but not for
 * a receipt, where the quantity being received has nothing to do with what is already on hand.
 * This hook removes {@code movementQuantity} from the callout response so the frontend keeps the
 * row's own value ({@code decisions.json} defaults it to {@code 1}, editable by the user)
 * instead of silently overwriting it with the stock quantity.
 */
@Named("goodsReceiptLineHandler")
public class GoodsReceiptLineHandler extends AbstractInOutLineHandler {

  private static final Logger log = LogManager.getLogger(GoodsReceiptLineHandler.class);
  private static final String FIELD_MOVEMENT_QUANTITY = "movementQuantity";

  /**
   * Strips the stock-derived {@code movementQuantity} update that {@code SL_InOutLine_Product}
   * returns on product selection (see class Javadoc). Mutates {@code previousResult} in place —
   * the same convention {@link InventoryLineHandler#afterCallout} uses to override values — and
   * returns {@code null} so the dispatcher's additive-only merge never runs.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    if (context == null || !NeoEndpointType.CALLOUT.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    JSONObject updates = previous.getBody().optJSONObject("updates");
    if (updates != null && updates.has(FIELD_MOVEMENT_QUANTITY)) {
      updates.remove(FIELD_MOVEMENT_QUANTITY);
      log.debug("[GoodsReceiptLineHandler] Stripped stock-derived movementQuantity from callout "
          + "response (ETP-4671)");
    }
    return null;
  }
}
