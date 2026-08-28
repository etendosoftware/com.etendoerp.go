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

/**
 * NeoHandler for the Goods Receipt line entity ({@code goodsReceiptLine}).
 *
 * <p>Extends {@link AbstractInOutLineHandler} for the behavior shared with Goods Shipment
 * (invoice-line linking, order/invoice qty and product-code enrichment on GET), and adds two
 * receipt-only fixes (ETP-4671):
 *
 * <h3>POST (create) — pre-hook: default {@code storageBin} to the warehouse's default locator</h3>
 * {@code M_InOutLine.M_Locator_ID} (the {@code storageBin} field) is declared in
 * {@code decisions.json} with {@code form: false} — it is never shown to the user in this
 * window. Its raw AD default ({@code @OnHandLocatorDefault@}) resolves to the locator where the
 * product <em>already</em> has stock, which is the right idea for Goods Shipment (you can only
 * ship from where stock exists) but wrong for a purchase receipt: a brand-new product with zero
 * on-hand stock resolves to nothing, {@code M_Locator_ID} stays {@code NULL}, and the classic
 * {@code M_INOUT_POST} completion procedure then rejects the document with
 * {@code InoutLineWithoutLocator} — regardless of {@code IsSOTrx}. This hook defaults the
 * locator to the receiving warehouse's own default active {@code M_Locator} via
 * {@link NeoHandlerUtils#injectDefaultLocatorIfMissing(JSONObject, Logger)} (the same shared
 * helper {@link GoodsShipmentLineHandler} and {@link ReturnToVendorShipmentLineHandler} use as of
 * ETP-4863), so confirmation succeeds for unstocked products too. An explicit user/import-supplied
 * {@code storageBin} is never overridden.
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

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse parentResult = super.handle(context);
    if (parentResult != null) {
      return parentResult;
    }
    if (context != null && NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equalsIgnoreCase(context.getHttpMethod())) {
      try {
        NeoHandlerUtils.injectDefaultLocatorIfMissing(context.getRequestBody(), log);
      } catch (Exception e) {
        log.warn("[GoodsReceiptLineHandler] Could not default storageBin: {}", e.getMessage(), e);
      }
    }
    return null;
  }

  /**
   * Strips the stock-derived {@code movementQuantity} update that {@code SL_InOutLine_Product}
   * returns on product selection (see class Javadoc), via the helper shared with
   * {@link GoodsShipmentLineHandler#afterCallout} (ETP-5062).
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    NeoHandlerUtils.stripStockDerivedMovementQuantity(context, log);
    return null;
  }
}
