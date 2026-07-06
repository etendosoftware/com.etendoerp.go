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
 * NeoHandler for the Return to Vendor Shipment header entity.
 *
 * <p>Handles custom actions: {@code availableReceipts}, {@code availableReceiptLines},
 * {@code importReceiptLines}, {@code createReturnInvoice}, and {@code cloneRecord}.
 *
 * <p>Injects {@code sourceReceiptDocNo}, {@code sourceReceipts}, {@code returnInvoices},
 * {@code linesCount} and {@code invoiceStatus} into every GET response via {@code afterHandle}.
 */
@Named("returnToVendorShipmentHeaderHandler")
public class ReturnToVendorShipmentHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnToVendorShipmentHeaderHandler.class);

  @Inject
  CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Inject
  NeoCloneRecordHandler cloneRecordHandler;

  private static final String FIELD_SOURCE_RECEIPT_DOC_NO = "sourceReceiptDocNo";
  private static final String FIELD_SOURCE_RECEIPTS = "sourceReceipts";
  private static final String FIELD_BUSINESS_PARTNER = "businessPartner";
  private static final String ACTION_IMPORT_LINES = "importReceiptLines";
  private static final String ACTION_AVAILABLE_RECEIPTS = "availableReceipts";
  private static final String ACTION_AVAILABLE_LINES = "availableReceiptLines";
  private static final String ACTION_CREATE_RETURN_INVOICE = "createReturnInvoice";
  private static final String ACTION_DOCUMENT_ACTION = "documentAction";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equals(context.getHttpMethod())
        && context.getRecordId() == null) {
      NeoHandlerUtils.injectReturnDocType(context, "MMR", false);
    }

    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse cloneResponse = cloneRecordHandler.handle(context);
    if (cloneResponse != null) return cloneResponse;

    String action = context.getFieldName();
    String method = context.getHttpMethod();
    if (ACTION_IMPORT_LINES.equals(action) && "POST".equals(method)) {
      return handleImportReceiptLines(context);
    }
    if (ACTION_AVAILABLE_RECEIPTS.equals(action) && "POST".equals(method)) {
      return handleAvailableReceipts(context);
    }
    if (ACTION_AVAILABLE_LINES.equals(action) && "POST".equals(method)) {
      return handleAvailableReceiptLines(context);
    }
    if (ACTION_CREATE_RETURN_INVOICE.equals(action) && "POST".equals(method)) {
      return handleCreateReturnInvoice(context);
    }
    if (ACTION_DOCUMENT_ACTION.equals(action) && "POST".equals(method)) {
      fillMissingStorageBins(context.getRecordId());
      return null; // let NEO native process handle completion
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Action: importReceiptLines
  // ---------------------------------------------------------------------------

  private NeoResponse handleImportReceiptLines(NeoContext context) {
    String returnId = context.getRecordId();
    if (returnId == null || returnId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut returnDoc = OBDal.getInstance().get(ShipmentInOut.class, returnId);
        if (returnDoc == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
              "Return to vendor shipment not found: " + returnId);
        }

        JSONObject body = context.getRequestBody();
        JSONArray requestedLines = body != null ? body.optJSONArray("lines") : null;
        if (requestedLines == null || requestedLines.length() == 0) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "No lines specified");
        }

        long nextLineNo = ReturnShipmentUtils.fetchMaxLineNo(returnId) + 10;
        int imported = 0;

        for (int i = 0; i < requestedLines.length(); i++) {
          JSONObject req = requestedLines.getJSONObject(i);
          String sourceLineId = req.optString("sourceLineId", null);
          BigDecimal qty = new BigDecimal(req.optString("returnQuantity", "0"));
          ShipmentInOutLine sourceLine = sourceLineId != null
              ? OBDal.getInstance().get(ShipmentInOutLine.class, sourceLineId) : null;
          if (sourceLineId == null || qty.compareTo(BigDecimal.ZERO) <= 0 || sourceLine == null) continue;

          ReturnShipmentUtils.buildAndSaveReturnLine(returnDoc, sourceLine, nextLineNo, qty.negate());
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
      log.error("Error importing receipt lines into return shipment {}: {}", returnId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while importing lines");
    }
  }

  // ---------------------------------------------------------------------------
  // Action: availableReceipts
  // ---------------------------------------------------------------------------

  @SuppressWarnings("java:S2077")
  private NeoResponse handleAvailableReceipts(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String bpId = body != null ? body.optString(FIELD_BUSINESS_PARTNER, null) : null;
    if (bpId == null || bpId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "businessPartner param is required");
    }
    try {
      // Select completed purchase receipts (V+, IsSOTrx='N') that still have
      // unreturned quantities available for this vendor.
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
          "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND rh.DocStatus NOT IN ('VO')" +
          "  GROUP BY rl.Canceled_Inoutline_ID " +
          ") ret ON ret.Canceled_Inoutline_ID = l.M_InOutLine_ID " +
          "WHERE h.C_BPartner_ID = ? " +
          "AND h.DocStatus = 'CO' " +
          "AND dt.IsSOTrx = 'N' AND dt.IsReturn = 'N' " +
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
      log.error("Error fetching available receipts for BP {}: {}", bpId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while fetching available receipts");
    }
  }

  // ---------------------------------------------------------------------------
  // Action: availableReceiptLines
  // ---------------------------------------------------------------------------

  @SuppressWarnings("java:S2077")
  private NeoResponse handleAvailableReceiptLines(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String receiptId = body != null ? body.optString("receiptId", null) : null;
    String bpId = body != null ? body.optString(FIELD_BUSINESS_PARTNER, null) : null;
    if (receiptId == null || receiptId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "receiptId param is required");
    }
    if (bpId == null || bpId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "businessPartner param is required");
    }
    try {
      String sql =
          "SELECT l.M_InOutLine_ID, l.M_Product_ID, p.Name AS product_name, l.C_UOM_ID, " +
          "  l.MovementQty - COALESCE(ret.ret_qty, 0) AS available_qty " +
          "FROM M_InOutLine l " +
          "JOIN M_InOut h ON h.M_InOut_ID = l.M_InOut_ID " +
          "JOIN M_Product p ON p.M_Product_ID = l.M_Product_ID " +
          "LEFT JOIN ( " +
          "  SELECT rl.Canceled_Inoutline_ID, SUM(ABS(rl.MovementQty)) AS ret_qty " +
          "  FROM M_InOutLine rl " +
          "  JOIN M_InOut rh ON rh.M_InOut_ID = rl.M_InOut_ID " +
          "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND rh.DocStatus NOT IN ('VO')" +
          "  GROUP BY rl.Canceled_Inoutline_ID " +
          ") ret ON ret.Canceled_Inoutline_ID = l.M_InOutLine_ID " +
          "WHERE l.M_InOut_ID = ? " +
          "AND h.C_BPartner_ID = ? " +
          "AND l.MovementQty > COALESCE(ret.ret_qty, 0) " +
          "ORDER BY l.Line";

      JSONArray data = new JSONArray();
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, receiptId);
        ps.setString(2, bpId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            data.put(ReturnShipmentUtils.buildAvailableLineRow(rs));
          }
        }
      }
      return ReturnShipmentUtils.wrapOkData(data);
    } catch (Exception e) {
      log.error("Error fetching available lines for receipt {}: {}", receiptId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while fetching available receipt lines");
    }
  }

  // ---------------------------------------------------------------------------
  // Action: createReturnInvoice  (Reversed Purchase Invoice — API + isReturn)
  // ---------------------------------------------------------------------------

  private NeoResponse handleCreateReturnInvoice(NeoContext context) {
    String returnId = context.getRecordId();
    if (returnId == null || returnId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut returnDoc = OBDal.getInstance().get(ShipmentInOut.class, returnId);
        if (returnDoc == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Return shipment not found");
        }
        if (!"CO".equals(returnDoc.getDocumentStatus())) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "Return shipment must be completed before creating a return invoice");
        }

        List<ShipmentInOutLine> lines = returnDoc.getMaterialMgmtShipmentInOutLineList()
            .stream().filter(l -> l.getProduct() != null).collect(Collectors.toList());
        if (lines.isEmpty()) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "No product lines in this return shipment");
        }

        DocumentType docType = ReturnShipmentUtils.findReturnDocTypeForOrg(
            returnDoc.getOrganization().getId(), "APC", false, false);
        if (docType == null) {
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "No AP CreditMemo document type (APC) found for this organization");
        }

        Invoice sourceInvoice = ReturnShipmentUtils.findSourceInvoice(lines);
        Invoice invoice = ReturnShipmentUtils.buildReturnInvoiceHeader(returnDoc, docType, sourceInvoice, false);
        OBDal.getInstance().save(invoice);
        OBDal.getInstance().flush();
        return ReturnShipmentUtils.finalizeReturnInvoice(invoice, lines, createDraftInvoiceHandler);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Return invoice creation rejected for shipment {}: {}", returnId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating return invoice for shipment {}: {}", returnId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the return invoice");
    }
  }

  // ---------------------------------------------------------------------------
  // Storage bin fill (pre-completion safety net)
  // ---------------------------------------------------------------------------

  private void fillMissingStorageBins(String returnId) {
    if (returnId == null) return;
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut returnDoc = OBDal.getInstance().get(ShipmentInOut.class, returnId);
        if (returnDoc != null) {
          ReturnShipmentUtils.assignBinsToLines(returnDoc);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not fill missing storage bins for return shipment {}: {}",
          returnId, e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle: inject sourceReceipts, sourceReceiptDocNo, returnInvoices,
  //              linesCount into every GET response
  // ---------------------------------------------------------------------------

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      List<String> ids = NeoHandlerUtils.collectIds(dataArr);

      Map<String, List<JSONObject>> receiptsMap = ReturnShipmentUtils.fetchSourceDocuments(ids);
      Map<String, List<JSONObject>> returnInvoicesMap = ReturnShipmentUtils.fetchReturnInvoices(ids);
      Map<String, Integer> lineCountMap = ReturnShipmentUtils.fetchLineCounts(ids);
      Map<String, Integer> invoiceStatusMap = ReturnShipmentUtils.fetchInvoiceStatuses(ids);

      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String id = rec.optString("id", null);
        ReturnShipmentUtils.enrichReturnRecord(rec, id, receiptsMap,
            FIELD_SOURCE_RECEIPTS, FIELD_SOURCE_RECEIPT_DOC_NO,
            returnInvoicesMap, lineCountMap, invoiceStatusMap);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching return-to-vendor-shipment header", e);
      return null;
    }
  }

}
