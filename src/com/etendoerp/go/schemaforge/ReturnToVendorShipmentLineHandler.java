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

import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * NeoHandler for the Return to Vendor Shipment line entity.
 *
 * <p>Injects {@code orderQuantity} (original received qty from the canceled goods receipt line)
 * and {@code productCode} (M_Product.Value / search key) into every GET response.
 *
 * <p>POST (create) pre-hook (ETP-4863): defaults {@code storageBin} to the header
 * {@code M_InOut}'s own warehouse default locator when the create request did not already
 * supply a REAL one — same rationale and shared implementation as {@link
 * GoodsReceiptLineHandler} (ETP-4671) and {@link GoodsShipmentLineHandler}, see {@link
 * NeoHandlerUtils#injectDefaultLocatorIfMissing(JSONObject, Logger)}. This class implements
 * {@link NeoHandler} directly rather than extending {@link AbstractInOutLineHandler} — its GET
 * enrichment (orderQuantity/productCode) and movementQuantity sign handling are return-specific
 * and unrelated to the invoice-line-linking behavior {@code AbstractInOutLineHandler} provides
 * for receipts/shipments — so the locator default is applied directly here via the shared
 * helper instead of inheriting it.
 */
@Named("returnToVendorShipmentLineHandler")
public class ReturnToVendorShipmentLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnToVendorShipmentLineHandler.class);

  private static final String FIELD_MOVEMENT_QUANTITY = "movementQuantity";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context != null && NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equalsIgnoreCase(context.getHttpMethod())) {
      try {
        NeoHandlerUtils.injectDefaultLocatorIfMissing(context.getRequestBody(), log);
      } catch (Exception e) {
        log.warn("[ReturnToVendorShipmentLineHandler] Could not default storageBin: {}",
            e.getMessage(), e);
      }
    }
    // ETP-5313: the stored-negative / exposed-positive rule now lives in ONE place, shared with
    // ReturnMaterialReceiptLineHandler — see ReturnLineQuantityPolicy for why the DB sign is
    // forced on us by core M_INOUT_POST / M_INOUT_TRG_PROV.
    String method = context.getHttpMethod();
    if (("PUT".equals(method) || "PATCH".equals(method) || "POST".equals(method))
        && context.getRequestBody() != null) {
      applyStoredSignAndStripProduct(context.getRequestBody(), method);
    }
    return null;
  }

  private void applyStoredSignAndStripProduct(JSONObject body, String method) {
    if (body.has(FIELD_MOVEMENT_QUANTITY)) {
      ReturnLineQuantityPolicy.applyStoredSignToWriteBody(body, log);
      // Keeps the product of an existing RTV line immutable: a return line mirrors its source
      // line, so an update that also carries a quantity must not repoint it at another product.
      // NOTE: this is NOT what protects the quantity from SL_InOutLine_Product — the NEO CRUD
      // callout cascade only runs on create (NeoCrudHandler#executePostCreate), never on
      // PUT/PATCH.
      if (!"POST".equals(method)) {
        body.remove("product");
      }
    }
  }

  /**
   * Strips the stock-derived {@code movementQuantity} the classic {@code SL_InOutLine_Product}
   * callout echoes back on product selection — the same protection {@link GoodsReceiptLineHandler}
   * (ETP-4671) and {@link GoodsShipmentLineHandler} (ETP-5062) already had (ETP-5336). The
   * {@code product} strip in {@link #handle} does NOT cover this: the NEO CRUD callout cascade
   * only runs on create, while this callout is dispatched by the React form on every product
   * selection. See {@link NeoHandlerUtils#stripStockDerivedMovementQuantity}.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    NeoHandlerUtils.stripStockDerivedMovementQuantity(context, log);
    return null;
  }

  /**
   * ETP-5336: the quantity sign flip runs on EVERY response that carries a line — a
   * {@code POST}/{@code PUT}/{@code PATCH} echo included. Before this it was GET-only, so a
   * PATCH answered with the stored NEGATIVE quantity and the frontend's optimistic row update
   * showed it until the next refetch. The source-document enrichment below stays GET-only on
   * purpose: it is a batch SQL lookup for the grid and must not add a query to every save.
   * Returns {@code null} on a write so the original response (and its status code) is kept —
   * the body is mutated in place, so the flip still reaches the client.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONArray dataArr = NeoHandlerUtils.extractResponseDataArray(context);
      if (dataArr == null || previousResult == null) {
        return null;
      }
      ReturnLineQuantityPolicy.applyDisplaySignToRecords(dataArr, log);
      if (!"GET".equals(context.getHttpMethod())) {
        return null;
      }
      JSONObject body = previousResult.getBody();
      List<String> lineIds = NeoHandlerUtils.collectIds(dataArr);
      Map<String, ReturnShipmentUtils.LineData> lineDataMap = ReturnShipmentUtils.fetchLineData(lineIds, log);
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String id = rec.optString("id", null);
        ReturnShipmentUtils.LineData ld = lineDataMap.get(id);
        if (ld != null) {
          if (ld.qty != null) {
            rec.put("orderQuantity", ld.qty);
          }
          if (ld.productCode != null) {
            rec.put("productCode", ld.productCode);
          }
        }
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching return-to-vendor-shipment lines", e);
      return context.getPreviousResult();
    }
  }

}
