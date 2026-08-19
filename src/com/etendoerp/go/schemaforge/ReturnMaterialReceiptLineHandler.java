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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

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
 */
@Named("returnMaterialReceiptLineHandler")
public class ReturnMaterialReceiptLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnMaterialReceiptLineHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context != null && NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equalsIgnoreCase(context.getHttpMethod())) {
      try {
        NeoHandlerUtils.injectDefaultLocatorIfMissing(context.getRequestBody(), log);
      } catch (Exception e) {
        log.warn("[ReturnMaterialReceiptLineHandler] Could not default storageBin: {}",
            e.getMessage(), e);
      }
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null || previousResult == null) {
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
