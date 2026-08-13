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
 * NeoHandler for the Return to Vendor Shipment line entity.
 *
 * <p>Injects {@code orderQuantity} (original received qty from the canceled goods receipt line)
 * and {@code productCode} (M_Product.Value / search key) into every GET response.
 */
@Named("returnToVendorShipmentLineHandler")
public class ReturnToVendorShipmentLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnToVendorShipmentLineHandler.class);

  private static final String FIELD_MOVEMENT_QUANTITY = "movementQuantity";

  @Override
  public NeoResponse handle(NeoContext context) {
    // Negate movementQuantity on write so the frontend always works with positive values.
    // V- documents store negative quantities in the DB; the UI (like Etendo Classic) shows positive.
    String method = context.getHttpMethod();
    if (("PUT".equals(method) || "PATCH".equals(method) || "POST".equals(method))
        && context.getRequestBody() != null) {
      negateMovQtyIfNeeded(context.getRequestBody(), method);
    }
    return null;
  }

  private void negateMovQtyIfNeeded(JSONObject body, String method) {
    if (body.has(FIELD_MOVEMENT_QUANTITY)) {
      try {
        BigDecimal qty = new BigDecimal(body.get(FIELD_MOVEMENT_QUANTITY).toString());
        if (qty.compareTo(BigDecimal.ZERO) > 0) {
          body.put(FIELD_MOVEMENT_QUANTITY, qty.negate());
        }
      } catch (Exception e) {
        log.warn("Could not negate movementQuantity: {}", e.getMessage());
      }
      // Remove product from PATCH/PUT body so SL_InOutLine_Product callout does not fire
      // and overwrite the user-supplied movementQuantity with the on-hand stock value.
      if (!"POST".equals(method)) {
        body.remove("product");
      }
    }
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
        flipMovQtySignIfNegative(rec);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching return-to-vendor-shipment lines", e);
      return context.getPreviousResult();
    }
  }

  // Return positive movementQuantity to the frontend (V- docs store negative in DB).
  // Etendo Classic displays the absolute value; we match that behaviour here.
  private void flipMovQtySignIfNegative(JSONObject rec) {
    Object mvObj = rec.opt(FIELD_MOVEMENT_QUANTITY);
    if (mvObj == null) {
      return;
    }
    try {
      BigDecimal mv = new BigDecimal(mvObj.toString());
      if (mv.compareTo(BigDecimal.ZERO) < 0) {
        rec.put(FIELD_MOVEMENT_QUANTITY, mv.negate());
      }
    } catch (Exception e) {
      log.warn("Could not flip movementQuantity sign: {}", e.getMessage());
    }
  }

}
