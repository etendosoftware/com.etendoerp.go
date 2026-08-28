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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NeoHandler for the Goods Shipment line entity.
 *
 * <p>Delegates invoice-line linking and order/invoice qty + product-code enrichment to
 * {@link AbstractInOutLineHandler}, and adds a POST (create) pre-hook (ETP-4863): default
 * {@code storageBin} to the header {@code M_InOut}'s own warehouse default locator when the
 * create request did not already supply a REAL one.
 *
 * <p>Without this, confirming a Goods Shipment on the PRINCIPAL warehouse could leave the new
 * line's {@code M_Locator_ID} pointing at a stale, session-cached warehouse instead: the raw AD
 * default ({@code @OnHandLocatorDefault@}) only filters by the header's warehouse when the tab
 * declares the matching {@code AD_AuxiliaryInput}; otherwise it falls back to whatever warehouse
 * happened to be cached in the HTTP session from the last window/document touched — a completely
 * unrelated document. See {@link NeoHandlerUtils#injectDefaultLocatorIfMissing(
 * org.codehaus.jettison.json.JSONObject, Logger)} for the full rationale — the same helper
 * {@link GoodsReceiptLineHandler} (ETP-4671) and {@link ReturnToVendorShipmentLineHandler} use.
 *
 * <p>Callout post-hook: strip stock-derived {@code movementQuantity} (ETP-5062). ETP-4671 gave
 * {@link GoodsReceiptLineHandler} an {@code afterCallout()} override on the reasoning that a
 * purchase receipt's quantity has nothing to do with what is already on hand, and deliberately
 * left Goods Shipment untouched — picking from existing stock was considered a helpful default
 * there. In practice a user can confirm the shipment without noticing the preselected value and
 * move the entire warehouse stock by accident, so ETP-5062 applies the same strip here: a
 * manually-added shipment line must always start at the row's own default (0) instead of
 * silently jumping to the product's on-hand quantity.
 */
@Named("goodsShipmentLineHandler")
public class GoodsShipmentLineHandler extends AbstractInOutLineHandler {

  private static final Logger log = LogManager.getLogger(GoodsShipmentLineHandler.class);

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
        log.warn("[GoodsShipmentLineHandler] Could not default storageBin: {}", e.getMessage(), e);
      }
    }
    return null;
  }

  /**
   * Strips the stock-derived {@code movementQuantity} update that {@code SL_InOutLine_Product}
   * returns on product selection (see class Javadoc), via the helper shared with
   * {@link GoodsReceiptLineHandler#afterCallout}.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    NeoHandlerUtils.stripStockDerivedMovementQuantity(context, log);
    return null;
  }
}
