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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Post-hook for the Return Material Receipt header entity.
 *
 * Injects {@code sourceShipmentDocNo} and {@code sourceShipments} into every
 * GET response, and handles the {@code importShipmentLines} action.
 */
@Named("returnMaterialReceiptHeaderHandler")
public class ReturnMaterialReceiptHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnMaterialReceiptHeaderHandler.class);
  private static final String FIELD_SOURCE_SHIPMENT_DOC_NO = "sourceShipmentDocNo";
  private static final String FIELD_SOURCE_SHIPMENTS = "sourceShipments";
  private static final String ACTION_IMPORT_LINES = "importShipmentLines";
  private static final String ACTION_AVAILABLE_SHIPMENTS = "availableShipments";
  private static final String ACTION_AVAILABLE_LINES = "availableShipmentLines";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    String action = context.getFieldName();
    String method = context.getHttpMethod();
    if (ACTION_IMPORT_LINES.equals(action) && "POST".equals(method)) {
      return handleImportShipmentLines(context);
    }
    if (ACTION_AVAILABLE_SHIPMENTS.equals(action) && "POST".equals(method)) {
      return handleAvailableShipments(context);
    }
    if (ACTION_AVAILABLE_LINES.equals(action) && "POST".equals(method)) {
      return handleAvailableShipmentLines(context);
    }
    return null;
  }

  private NeoResponse handleImportShipmentLines(NeoContext context) {
    String receiptId = context.getRecordId();
    if (receiptId == null || receiptId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut receipt = OBDal.getInstance().get(ShipmentInOut.class, receiptId);
        if (receipt == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Return receipt not found: " + receiptId);
        }

        JSONObject body = context.getRequestBody();
        JSONArray requestedLines = body != null ? body.optJSONArray("lines") : null;
        if (requestedLines == null || requestedLines.length() == 0) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "No lines specified");
        }

        long nextLineNo = fetchMaxLineNo(receiptId) + 10;
        int imported = 0;
        // preserve insertion order so the single-shipment check is deterministic
        Map<String, String> sourceShipmentDocNos = new LinkedHashMap<>();

        for (int i = 0; i < requestedLines.length(); i++) {
          JSONObject req = requestedLines.getJSONObject(i);
          String sourceLineId = req.optString("sourceLineId", null);
          BigDecimal qty = BigDecimal.valueOf(req.optDouble("returnQuantity", 0));
          if (sourceLineId == null || qty.compareTo(BigDecimal.ZERO) <= 0) continue;

          ShipmentInOutLine sourceLine = OBDal.getInstance().get(ShipmentInOutLine.class, sourceLineId);
          if (sourceLine == null) continue;

          ShipmentInOutLine retLine = OBProvider.getInstance().get(ShipmentInOutLine.class);
          retLine.setClient(receipt.getClient());
          retLine.setOrganization(receipt.getOrganization());
          retLine.setShipmentReceipt(receipt);
          retLine.setLineNo(nextLineNo);
          retLine.setProduct(sourceLine.getProduct());
          retLine.setUOM(sourceLine.getUOM());
          retLine.setMovementQuantity(qty);
          retLine.setCanceledInoutLine(sourceLine);
          if (sourceLine.getStorageBin() != null) {
            retLine.setStorageBin(sourceLine.getStorageBin());
          }
          OBDal.getInstance().save(retLine);
          nextLineNo += 10;
          imported++;

          ShipmentInOut sourceShipment = sourceLine.getShipmentReceipt();
          if (sourceShipment != null) {
            sourceShipmentDocNos.put(sourceShipment.getId(), sourceShipment.getDocumentNo());
          }
        }

        OBDal.getInstance().flush();

        // If all lines come from one shipment, fill the header field
        if (sourceShipmentDocNos.size() == 1) {
          storeSourceShipmentDocNo(receiptId, sourceShipmentDocNos.values().iterator().next());
        }

        JSONObject data = new JSONObject();
        data.put("importedCount", imported);
        JSONObject responseData = new JSONObject();
        responseData.put("data", data);
        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);
        return NeoResponse.ok(wrapper);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error importing shipment lines into return receipt {}: {}", receiptId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while importing lines");
    }
  }

  @SuppressWarnings("java:S2077")
  private NeoResponse handleAvailableShipments(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String bpId = body != null ? body.optString("businessPartner", null) : null;
    if (bpId == null || bpId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "businessPartner param is required");
    }
    try {
      String sql =
          "SELECT DISTINCT h.M_InOut_ID, h.DocumentNo, h.MovementDate, " +
          "  bp.Name AS bp_name, h.C_BPartner_ID " +
          "FROM M_InOut h " +
          "JOIN C_BPartner bp ON bp.C_BPartner_ID = h.C_BPartner_ID " +
          "JOIN C_DocType dt ON dt.C_DocType_ID = h.C_DocType_ID " +
          "JOIN M_InOutLine l ON l.M_InOut_ID = h.M_InOut_ID " +
          "LEFT JOIN ( " +
          "  SELECT rl.Canceled_Inoutline_ID, SUM(ABS(rl.MovementQty)) AS ret_qty " +
          "  FROM M_InOutLine rl " +
          "  JOIN M_InOut rh ON rh.M_InOut_ID = rl.M_InOut_ID " +
          "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND rh.DocStatus = 'CO' " +
          "  GROUP BY rl.Canceled_Inoutline_ID " +
          ") ret ON ret.Canceled_Inoutline_ID = l.M_InOutLine_ID " +
          "WHERE h.C_BPartner_ID = ? " +
          "AND h.DocStatus = 'CO' " +
          "AND dt.IsSOTrx = 'Y' AND dt.IsReturn = 'N' " +
          "AND l.MovementQty > COALESCE(ret.ret_qty, 0) " +
          "ORDER BY h.MovementDate DESC";

      JSONArray data = new JSONArray();
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, bpId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            JSONObject row = new JSONObject();
            row.put("id", rs.getString(1));
            row.put("documentNo", rs.getString(2));
            row.put("movementDate", rs.getString(3));
            row.put("businessPartner$_identifier", rs.getString(4));
            row.put("businessPartner", rs.getString(5));
            data.put(row);
          }
        }
      }
      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);
    } catch (Exception e) {
      log.error("Error fetching available shipments for BP {}: {}", bpId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while fetching available shipments");
    }
  }

  @SuppressWarnings("java:S2077")
  private NeoResponse handleAvailableShipmentLines(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String shipmentId = body != null ? body.optString("shipmentId", null) : null;
    if (shipmentId == null || shipmentId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "shipmentId param is required");
    }
    try {
      String sql =
          "SELECT l.M_InOutLine_ID, l.M_Product_ID, p.Name AS product_name, l.C_UOM_ID, " +
          "  l.MovementQty - COALESCE(ret.ret_qty, 0) AS available_qty " +
          "FROM M_InOutLine l " +
          "JOIN M_Product p ON p.M_Product_ID = l.M_Product_ID " +
          "LEFT JOIN ( " +
          "  SELECT rl.Canceled_Inoutline_ID, SUM(ABS(rl.MovementQty)) AS ret_qty " +
          "  FROM M_InOutLine rl " +
          "  JOIN M_InOut rh ON rh.M_InOut_ID = rl.M_InOut_ID " +
          "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND rh.DocStatus = 'CO' " +
          "  GROUP BY rl.Canceled_Inoutline_ID " +
          ") ret ON ret.Canceled_Inoutline_ID = l.M_InOutLine_ID " +
          "WHERE l.M_InOut_ID = ? " +
          "AND l.MovementQty > COALESCE(ret.ret_qty, 0) " +
          "ORDER BY l.Line";

      JSONArray data = new JSONArray();
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, shipmentId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            JSONObject row = new JSONObject();
            row.put("id", rs.getString(1));
            row.put("product", rs.getString(2));
            row.put("product$_identifier", rs.getString(3));
            row.put("uOM", rs.getString(4));
            row.put("movementQuantity", rs.getBigDecimal(5));
            data.put(row);
          }
        }
      }
      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);
    } catch (Exception e) {
      log.error("Error fetching available lines for shipment {}: {}", shipmentId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while fetching available lines");
    }
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

      Map<String, String> docNoMap = fetchSourceDocNos(ids);
      Map<String, List<JSONObject>> shipmentsMap = fetchSourceShipments(ids);

      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String id = rec.optString("id", null);

        String sourceDocNo = docNoMap.get(id);
        if (sourceDocNo != null) {
          rec.put(FIELD_SOURCE_SHIPMENT_DOC_NO, sourceDocNo);
        }

        List<JSONObject> shipments = shipmentsMap.getOrDefault(id, Collections.emptyList());
        JSONArray shipmentsArr = new JSONArray();
        for (JSONObject s : shipments) {
          shipmentsArr.put(s);
        }
        rec.put(FIELD_SOURCE_SHIPMENTS, shipmentsArr);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching return-material-receipt header", e);
      return null;
    }
  }

  @SuppressWarnings("java:S2077")
  private Map<String, String> fetchSourceDocNos(List<String> receiptIds) {
    Map<String, String> result = new HashMap<>();
    if (receiptIds.isEmpty()) return result;
    String placeholders = receiptIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT M_InOut_ID, em_etgo_sourceshipmentdocno " +
        "FROM M_InOut " +
        "WHERE M_InOut_ID IN (" + placeholders + ") " +
        "  AND em_etgo_sourceshipmentdocno IS NOT NULL";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < receiptIds.size(); i++) ps.setString(i + 1, receiptIds.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) result.put(rs.getString(1), rs.getString(2));
      }
    } catch (Exception e) {
      log.warn("Error fetching sourceShipmentDocNo: {}", e.getMessage());
    }
    return result;
  }

  @SuppressWarnings("java:S2077")
  private Map<String, List<JSONObject>> fetchSourceShipments(List<String> receiptIds) {
    Map<String, List<JSONObject>> result = new HashMap<>();
    if (receiptIds.isEmpty()) return result;
    String placeholders = receiptIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT DISTINCT l.M_InOut_ID, src.M_InOut_ID, src.DocumentNo " +
        "FROM M_InOutLine l " +
        "JOIN M_InOutLine orig ON orig.M_InOutLine_ID = l.Canceled_Inoutline_ID " +
        "JOIN M_InOut src ON src.M_InOut_ID = orig.M_InOut_ID " +
        "WHERE l.M_InOut_ID IN (" + placeholders + ") " +
        "  AND l.Canceled_Inoutline_ID IS NOT NULL";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < receiptIds.size(); i++) ps.setString(i + 1, receiptIds.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String receiptId = rs.getString(1);
          try {
            JSONObject ship = new JSONObject();
            ship.put("id", rs.getString(2));
            ship.put("documentNo", rs.getString(3));
            result.computeIfAbsent(receiptId, k -> new ArrayList<>()).add(ship);
          } catch (Exception je) {
            log.warn("Error building sourceShipment JSON: {}", je.getMessage());
          }
        }
      }
    } catch (Exception e) {
      log.warn("Error fetching source shipments: {}", e.getMessage());
    }
    return result;
  }

  @SuppressWarnings("java:S2077")
  private void storeSourceShipmentDocNo(String receiptId, String sourceDocNo) {
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE M_InOut SET em_etgo_sourceshipmentdocno = ? WHERE M_InOut_ID = ?")) {
        ps.setString(1, sourceDocNo);
        ps.setString(2, receiptId);
        ps.executeUpdate();
      }
    } catch (Exception e) {
      log.warn("Could not store sourceShipmentDocNo on receipt {}: {}", receiptId, e.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private long fetchMaxLineNo(String receiptId) {
    String sql = "SELECT COALESCE(MAX(Line), 0) FROM M_InOutLine WHERE M_InOut_ID = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, receiptId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong(1);
      }
    } catch (Exception e) {
      log.warn("Could not fetch max lineNo for receipt {}: {}", receiptId, e.getMessage());
    }
    return 0;
  }
}
