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
 * Post-hook for the Return Material Receipt line entity.
 *
 * <p>Injects {@code orderQuantity} (original delivered qty from canceled source line)
 * and {@code productCode} (M_Product.Value / search key) into every GET response.
 *
 * <p>POST (create) pre-hook (ETP-4863): defaults {@code storageBin} to the header
 * {@code M_InOut}'s own warehouse default locator when the create request did not already
 * supply a REAL one — this is the "Devolución de Venta" (RMA of a sale) counterpart of the same
 * fix already applied to {@link GoodsReceiptLineHandler} (ETP-4671), {@link
 * GoodsShipmentLineHandler}, and {@link ReturnToVendorShipmentLineHandler}. See {@link
 * NeoHandlerUtils#injectDefaultLocatorIfMissing(JSONObject, Logger)} for the full rationale.
 * This class implements {@link NeoHandler} directly rather than extending {@link
 * AbstractInOutLineHandler} — same shape as {@link ReturnToVendorShipmentLineHandler} — so the
 * locator default is applied directly here via the shared helper instead of inheriting it.
 *
 * <p>Write/read quantity sign (ETP-5313): {@code movementQuantity} is persisted NEGATIVE and
 * echoed back POSITIVE, exactly like {@link ReturnToVendorShipmentLineHandler}. Both delegate to
 * {@link ReturnLineQuantityPolicy}, which documents why core {@code M_INOUT_POST} leaves no other
 * option. Before this, a completed sales return DECREASED stock instead of restoring it.
 */
@Named("returnMaterialReceiptLineHandler")
public class ReturnMaterialReceiptLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnMaterialReceiptLineHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context == null) {
      return null;
    }
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equalsIgnoreCase(context.getHttpMethod())) {
      try {
        NeoHandlerUtils.injectDefaultLocatorIfMissing(context.getRequestBody(), log);
      } catch (Exception e) {
        log.warn("[ReturnMaterialReceiptLineHandler] Could not default storageBin: {}",
            e.getMessage(), e);
      }
    }
    String method = context.getHttpMethod();
    if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
      ReturnLineQuantityPolicy.applyStoredSignToWriteBody(context.getRequestBody(), log);
    }
    return null;
  }

  /**
   * Strips the stock-derived {@code movementQuantity} the classic {@code SL_InOutLine_Product}
   * callout echoes back on product selection — the same protection {@link GoodsReceiptLineHandler}
   * (ETP-4671) and {@link GoodsShipmentLineHandler} (ETP-5062) already had and this window did
   * not (ETP-5336). On a return line the product's on-hand quantity is meaningless: the quantity
   * is what the customer is sending back, so a callout must never overwrite what the user typed.
   * See {@link NeoHandlerUtils#stripStockDerivedMovementQuantity} for the full rationale.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    NeoHandlerUtils.stripStockDerivedMovementQuantity(context, log);
    return null;
  }

  /**
   * ETP-5336: the quantity sign flip runs on EVERY response that carries a line — a
   * {@code POST}/{@code PUT}/{@code PATCH} echo included — because it describes the record
   * itself. The source-document enrichment below stays GET-only on purpose: it is a batch SQL
   * lookup for the grid, and running it per write would add a query to every keystroke-sized
   * save. Returns {@code null} on a write so the original response (and its status code) is
   * kept — the body is mutated in place, so the flip still reaches the client.
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
      log.error("Error enriching return-material-receipt lines", e);
      return context.getPreviousResult();
    }
  }

}
