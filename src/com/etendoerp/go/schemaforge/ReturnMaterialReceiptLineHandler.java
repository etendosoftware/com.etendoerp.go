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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * Post-hook for the Return Material Receipt line entity.
 *
 * Injects {@code orderQuantity} into every GET response by reading
 * MovementQty from the canceled source line
 * (M_InOutLine.Canceled_Inoutline_ID → M_InOutLine.MovementQty).
 * This gives the UI "Cant. entregada original" without touching the
 * QuantityOrder column (which Etendo reserves for order-UOM quantities).
 */
@Named("returnMaterialReceiptLineHandler")
public class ReturnMaterialReceiptLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnMaterialReceiptLineHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      List<String> lineIds = NeoHandlerUtils.collectIds(dataArr);
      Map<String, BigDecimal> originalQtyMap = fetchOriginalQtys(lineIds);
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String id = rec.optString("id", null);
        BigDecimal qty = originalQtyMap.get(id);
        if (qty != null) {
          rec.put("orderQuantity", qty);
        }
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching return-material-receipt lines with orderQuantity", e);
      return null;
    }
  }

  @SuppressWarnings("java:S2077")
  private Map<String, BigDecimal> fetchOriginalQtys(List<String> lineIds) {
    Map<String, BigDecimal> result = new HashMap<>();
    if (lineIds.isEmpty()) {
      return result;
    }
    String placeholders = lineIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT l.M_InOutLine_ID, orig.MovementQty " +
        "FROM M_InOutLine l " +
        "JOIN M_InOutLine orig ON orig.M_InOutLine_ID = l.Canceled_Inoutline_ID " +
        "WHERE l.M_InOutLine_ID IN (" + placeholders + ") " +
        "  AND l.Canceled_Inoutline_ID IS NOT NULL";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < lineIds.size(); i++) {
        ps.setString(i + 1, lineIds.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString(1), rs.getBigDecimal(2));
        }
      }
    } catch (Exception e) {
      log.warn("Error fetching original qty for return receipt lines: {}", e.getMessage());
    }
    return result;
  }
}
