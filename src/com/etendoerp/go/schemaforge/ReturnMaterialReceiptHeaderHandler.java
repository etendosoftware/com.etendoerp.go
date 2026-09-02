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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Post-hook for the Return Material Receipt header entity.
 *
 * Injects {@code sourceShipmentDocNo}, {@code sourceShipments}, {@code returnInvoices},
 * {@code linesCount} and {@code invoiceStatus} into every GET response, and handles the
 * {@code importShipmentLines} action.
 *
 * <p>{@code issuerOrg} is additionally injected on detail GETs only (ETP-5124, mirroring the
 * ETP-4939 pattern), via the shared {@link NeoHandlerUtils#enrichIssuerOrg}, also used by
 * {@link GoodsShipmentHeaderHandler} and {@link ReturnToVendorShipmentHeaderHandler}.
 */
@Named("returnMaterialReceiptHeaderHandler")
public class ReturnMaterialReceiptHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnMaterialReceiptHeaderHandler.class);

  @Inject
  CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Inject
  NeoCloneRecordHandler cloneRecordHandler;

  private static final String FIELD_SOURCE_SHIPMENT_DOC_NO = "sourceShipmentDocNo";
  private static final String FIELD_SOURCE_SHIPMENTS = "sourceShipments";
  private static final String FIELD_MOVEMENT_DATE = "movementDate";
  private static final String FIELD_ACCOUNTING_DATE = "accountingDate";
  private static final String ACTION_IMPORT_LINES = "importShipmentLines";
  private static final String ACTION_AVAILABLE_SHIPMENTS = "availableShipments";
  private static final String ACTION_AVAILABLE_LINES = "availableShipmentLines";
  private static final String ACTION_CREATE_RETURN_INVOICE = "createReturnInvoice";
  private static final String ACTION_DOCUMENT_ACTION = "documentAction";

  @Override
  public NeoResponse handle(NeoContext context) {
    mirrorAccountingDate(context);

    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equals(context.getHttpMethod())
        && context.getRecordId() == null) {
      NeoHandlerUtils.injectReturnDocType(context, "MMS", true);
    }

    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse cloneResponse = cloneRecordHandler.handle(context);
    if (cloneResponse != null) return cloneResponse;

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
    if (ACTION_CREATE_RETURN_INVOICE.equals(action) && "POST".equals(method)) {
      return handleCreateReturnInvoice(context);
    }
    if (ACTION_DOCUMENT_ACTION.equals(action) && "POST".equals(method)) {
      NeoHandlerUtils.reanchorLinesToHeaderWarehouse(context.getRecordId(), log);
      return null; // let NEO native process handle completion
    }
    return null;
  }

  /**
   * Mirrors the single visible {@code movementDate} field into the hidden
   * {@code accountingDate} field on the request body, unconditionally, before the default CRUD
   * path persists it (ETP-4531 pattern, applied here for ETP-4737). The user never sees or
   * edits accountingDate directly — this window's {@code decisions.json} marks it
   * {@code discarded} — so without this mirror a bare POST-create (e.g. "+ Nuevo" directly on
   * this window, not via the goods-shipment "Crear Devolución" wizard, which sets both dates
   * explicitly in {@link NeoCommercialDocumentFactory}) leaves accountingDate unset, and it
   * falls back to whatever default the persistence layer applies instead of the document's own
   * movement date.
   */
  static void mirrorAccountingDate(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      NeoHandlerUtils.mirrorFieldValue(context.getRequestBody(), FIELD_MOVEMENT_DATE, FIELD_ACCOUNTING_DATE);
    }
  }

  @SuppressWarnings("java:S2077")
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

        long nextLineNo = ReturnShipmentUtils.fetchMaxLineNo(receiptId) + 10;
        int imported = 0;

        for (int i = 0; i < requestedLines.length(); i++) {
          JSONObject req = requestedLines.getJSONObject(i);
          String sourceLineId = req.optString("sourceLineId", null);
          BigDecimal qty = BigDecimal.valueOf(req.optDouble("returnQuantity", 0));
          ShipmentInOutLine sourceLine = sourceLineId != null
              ? OBDal.getInstance().get(ShipmentInOutLine.class, sourceLineId) : null;
          if (sourceLineId == null || qty.compareTo(BigDecimal.ZERO) <= 0 || sourceLine == null) continue;

          ReturnShipmentUtils.buildAndSaveReturnLine(receipt, sourceLine, nextLineNo, qty);
          nextLineNo += 10;
          imported++;
        }

        OBDal.getInstance().flush();

        JSONObject data = new JSONObject();
        data.put("importedCount", imported);
        return ReturnShipmentUtils.wrapOkData(data);

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
          "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND rh.DocStatus NOT IN ('VO') " +
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
            data.put(ReturnShipmentUtils.buildAvailableDocumentRow(rs));
          }
        }
      }
      return ReturnShipmentUtils.wrapOkData(data);
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
          "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND rh.DocStatus NOT IN ('VO') " +
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
            data.put(ReturnShipmentUtils.buildAvailableLineRow(rs));
          }
        }
      }
      return ReturnShipmentUtils.wrapOkData(data);
    } catch (Exception e) {
      log.error("Error fetching available lines for shipment {}: {}", shipmentId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while fetching available lines");
    }
  }

  private NeoResponse handleCreateReturnInvoice(NeoContext context) {
    String receiptId = context.getRecordId();
    if (receiptId == null || receiptId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut receipt = OBDal.getInstance().get(ShipmentInOut.class, receiptId);
        if (receipt == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Receipt not found");
        }
        if (!"CO".equals(receipt.getDocumentStatus())) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "Receipt must be completed before creating a return invoice");
        }

        List<ShipmentInOutLine> lines = receipt.getMaterialMgmtShipmentInOutLineList()
            .stream().filter(l -> l.getProduct() != null).collect(Collectors.toList());
        if (lines.isEmpty()) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "No product lines in this receipt");
        }

        // ETP-4737: resolves the unified "Factura Rectificativa" (ARI + isRectificative) doc
        // type, replacing the legacy ARI_RM ("Return Material Sales Invoice") lookup.
        DocumentType docType = ReturnShipmentUtils.findReturnDocTypeForOrg(
            receipt.getOrganization().getId(), "ARI", true, false, true);
        if (docType == null) {
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "No rectificative invoice document type found for this organization");
        }

        Invoice sourceInvoice = ReturnShipmentUtils.findSourceInvoice(lines);
        Invoice invoice = ReturnShipmentUtils.buildReturnInvoiceHeader(receipt, docType, sourceInvoice, true);
        OBDal.getInstance().save(invoice);
        OBDal.getInstance().flush();
        return ReturnShipmentUtils.finalizeReturnInvoice(invoice, lines, createDraftInvoiceHandler);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Return invoice creation rejected for receipt {}: {}", receiptId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating return invoice for receipt {}: {}", receiptId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the return invoice");
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

      Map<String, List<JSONObject>> shipmentsMap = ReturnShipmentUtils.fetchSourceDocuments(ids);
      Map<String, List<JSONObject>> returnInvoicesMap = ReturnShipmentUtils.fetchReturnInvoices(ids);
      Map<String, Integer> lineCountMap = ReturnShipmentUtils.fetchLineCounts(ids);
      Map<String, Integer> invoiceStatusMap = ReturnShipmentUtils.fetchInvoiceStatuses(ids);

      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String id = rec.optString("id", null);
        ReturnShipmentUtils.enrichReturnRecord(rec, id, shipmentsMap,
            FIELD_SOURCE_SHIPMENTS, FIELD_SOURCE_SHIPMENT_DOC_NO,
            returnInvoicesMap, lineCountMap, invoiceStatusMap);
        // ETP-5124: only enrich issuerOrg on a detail GET (single record), never on a
        // grid load — otherwise this fires one extra query per row (N+1). Mirrors the
        // ETP-4939 pattern already applied in ReturnToVendorShipmentHeaderHandler.
        if (id != null && context.getRecordId() != null) {
          NeoHandlerUtils.enrichIssuerOrg(rec, id);
        }
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching return-material-receipt header", e);
      return null;
    }
  }

}
