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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * Post-hook for the Return Material Receipt header entity (qualifier: return-material-receipt).
 *
 * Enriches every GET response with {@code sourceShipmentDocNo}: the document number
 * of the original Goods Shipment that this receipt reverses. Derived server-side by
 * following {@code M_InOutLine.Canceled_Inoutline_ID → M_InOut.DocumentNo}, so no
 * extra field needs to be stored or exposed on the return receipt itself.
 */
@ApplicationScoped
@Named("return-material-receipt")
public class ReturnMaterialReceiptHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnMaterialReceiptHeaderHandler.class);
  private static final String FIELD_SOURCE_SHIPMENT_DOC_NO = "sourceShipmentDocNo";

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
      List<String> ids = NeoHandlerUtils.collectIds(dataArr);
      Map<String, String> docNoMap = fetchSourceShipmentDocNos(ids);
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String id = rec.optString("id", null);
        String sourceDocNo = docNoMap.get(id);
        if (sourceDocNo != null) {
          rec.put(FIELD_SOURCE_SHIPMENT_DOC_NO, sourceDocNo);
        }
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching return-material-receipt with sourceShipmentDocNo", e);
      return null;
    }
  }

  @SuppressWarnings("java:S2077")
  private Map<String, String> fetchSourceShipmentDocNos(List<String> receiptIds) {
    Map<String, String> result = new HashMap<>();
    if (receiptIds.isEmpty()) {
      return result;
    }
    String placeholders = receiptIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT l.M_InOut_ID, src.DocumentNo " +
        "FROM M_InOutLine l " +
        "JOIN M_InOutLine orig ON orig.M_InOutLine_ID = l.Canceled_Inoutline_ID " +
        "JOIN M_InOut src ON src.M_InOut_ID = orig.M_InOut_ID " +
        "WHERE l.M_InOut_ID IN (" + placeholders + ") " +
        "  AND l.Canceled_Inoutline_ID IS NOT NULL";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < receiptIds.size(); i++) {
        ps.setString(i + 1, receiptIds.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.putIfAbsent(rs.getString(1), rs.getString(2));
        }
      }
    } catch (Exception e) {
      log.warn("Error fetching source shipment doc nos: {}", e.getMessage());
    }
    return result;
  }
}
