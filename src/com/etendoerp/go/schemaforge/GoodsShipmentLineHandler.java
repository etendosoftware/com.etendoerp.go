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
 * Post-hook for the Goods Shipment line entity.
 *
 * Enriches each GET response with:
 * - {@code orderLineQty} — the originally ordered quantity from {@code C_OrderLine.QtyOrdered}.
 *   {@code QuantityOrder} on {@code M_InOutLine} is often unpopulated so the order line is authoritative.
 * - {@code productCode} — the product search key ({@code M_Product.Value}).
 */
@Named("goodsShipmentLineHandler")
public class GoodsShipmentLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GoodsShipmentLineHandler.class);
  private static final String FIELD_ORDER_LINE_QTY  = "orderLineQty";
  private static final String FIELD_ORDER_QUANTITY  = "orderQuantity";
  private static final String FIELD_PRODUCT_CODE    = "productCode";

  private static final class LineData {
    final BigDecimal orderedQty;
    final String     productCode;
    LineData(BigDecimal orderedQty, String productCode) {
      this.orderedQty  = orderedQty;
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
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      List<String> lineIds = NeoHandlerUtils.collectIds(dataArr);
      Map<String, LineData> dataMap = fetchLineData(lineIds);
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject line = dataArr.getJSONObject(i);
        String id = line.optString("id", null);
        LineData ld = dataMap.get(id);
        if (ld == null) {
          continue;
        }
        if (ld.orderedQty != null) {
          line.put(FIELD_ORDER_LINE_QTY, ld.orderedQty);
          line.put(FIELD_ORDER_QUANTITY, ld.orderedQty);
        }
        if (ld.productCode != null && !ld.productCode.isEmpty()) {
          line.put(FIELD_PRODUCT_CODE, ld.productCode);
        }
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching goods shipment lines", e);
      return null;
    }
  }

  // placeholders contains only "?" literals — no injection risk.
  @SuppressWarnings("java:S2077")
  private Map<String, LineData> fetchLineData(List<String> lineIds) {
    Map<String, LineData> result = new HashMap<>();
    if (lineIds.isEmpty()) {
      return result;
    }
    String placeholders = lineIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT il.m_inoutline_id, ol.qtyordered, p.value "
        + "FROM m_inoutline il "
        + "LEFT JOIN c_orderline ol ON ol.c_orderline_id = il.c_orderline_id "
        + "LEFT JOIN m_product p ON p.m_product_id = il.m_product_id "
        + "WHERE il.m_inoutline_id IN (" + placeholders + ")";
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
      log.error("DB error fetching line data for goods shipment lines", e);
    }
    return result;
  }
}
