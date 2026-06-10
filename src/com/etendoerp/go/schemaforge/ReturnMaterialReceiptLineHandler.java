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
 * Injects {@code orderQuantity} (original delivered qty from canceled source line)
 * and {@code productCode} (M_Product.Value / search key) into every GET response.
 */
@Named("returnMaterialReceiptLineHandler")
public class ReturnMaterialReceiptLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnMaterialReceiptLineHandler.class);

  private static final class LineData {
    final BigDecimal qty;
    final String productCode;
    LineData(BigDecimal qty, String productCode) {
      this.qty = qty;
      this.productCode = productCode;
    }
  }

  @Override
  public NeoResponse handle(NeoContext context) {
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
      Map<String, LineData> lineDataMap = fetchLineData(lineIds);
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String id = rec.optString("id", null);
        LineData ld = lineDataMap.get(id);
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

  @SuppressWarnings("java:S2077")
  private Map<String, LineData> fetchLineData(List<String> lineIds) {
    Map<String, LineData> result = new HashMap<>();
    if (lineIds.isEmpty()) {
      return result;
    }
    String placeholders = lineIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT l.M_InOutLine_ID, COALESCE(orig.MovementQty, l.QuantityOrder) AS effective_qty, p.Value AS product_code " +
        "FROM M_InOutLine l " +
        "LEFT JOIN M_InOutLine orig ON orig.M_InOutLine_ID = l.Canceled_Inoutline_ID " +
        "LEFT JOIN M_Product p ON p.M_Product_ID = l.M_Product_ID " +
        "WHERE l.M_InOutLine_ID IN (" + placeholders + ")";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < lineIds.size(); i++) {
        ps.setString(i + 1, lineIds.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString(1), new LineData(rs.getBigDecimal(2), rs.getString(3)));
        }
      }
    } catch (Exception e) {
      log.warn("Error fetching line data for return receipt lines: {}", e.getMessage());
    }
    return result;
  }
}
