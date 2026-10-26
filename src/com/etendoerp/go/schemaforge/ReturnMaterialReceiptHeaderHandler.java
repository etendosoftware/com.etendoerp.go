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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

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
 *
 * <p>Extends {@link AbstractReturnDocumentHeaderHandler}, which owns the {@code postingService}
 * injection point and the {@code handle()} wiring shared with {@link ReturnToVendorShipmentHeaderHandler}.
 */
@Named("returnMaterialReceiptHeaderHandler")
public class ReturnMaterialReceiptHeaderHandler extends AbstractReturnDocumentHeaderHandler {

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
  /** ETP-5381: lists the confirmed invoices this return document can rectify. */
  private static final String ACTION_RECTIFIABLE_INVOICES = "rectifiableInvoices";
  private static final String ACTION_DOCUMENT_ACTION = "documentAction";
  private static final String ERR_RECORD_ID_REQUIRED = "Record ID is required";

  /**
   * The {@code etendo_action} id of an action that is not about a return receipt: the handler never
   * reads it, but the tool requires one.
   */
  private static final String ID_NOT_USED = "Not used — the list is not tied to a return receipt. "
      + "Pass \"_\".";
  private static final String FLOW = " Flow to create a customer return (the UI's): create the "
      + "returnMaterialReceipt header with etendo_create (businessPartner and partnerAddress; the "
      + "rest is defaulted — etendo_schema view:\"create\" is the authority on what it needs); "
      + "availableShipments -> availableShipmentLines -> importShipmentLines on the new header (it "
      + "may take lines from several shipments of the same customer); "
      + "confirm it with the DocAction button (docAction CO) — this does NOT create the factura "
      + "rectificativa; then, as a SEPARATE step, rectifiableInvoices -> createReturnInvoice to "
      + "refund it with the factura rectificativa. To return from ONE shipment "
      + "without this flow, call createReturn on that goods shipment: it creates the draft "
      + "return with its lines linked in one step.";

  /**
   * The handler-served actions published to agents (ETP-5704), in flow order. The SPA reaches the
   * same handlers through the ACTION sub-endpoint; the MCP validates a call against these before the
   * handler runs, the REST path is not judged by them.
   */
  private static final Map<String, NeoActionContract> ACTION_CONTRACTS = buildActionContracts();

  @Override
  public Map<String, NeoActionContract> actionContracts() {
    return ACTION_CONTRACTS;
  }

  /**
   * {@inheritDoc} The source shipments, the return invoices and the counters {@link #afterHandle}
   * adds to every GET record (ETP-5714).
   */
  @Override
  public Set<String> responseEnrichedFields() {
    return ReturnDocumentAvailability.enrichedFields(FIELD_SOURCE_SHIPMENTS,
        FIELD_SOURCE_SHIPMENT_DOC_NO);
  }

  private static Map<String, NeoActionContract> buildActionContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(ACTION_AVAILABLE_SHIPMENTS, NeoActionContract.read(ACTION_AVAILABLE_SHIPMENTS,
        "Lists the completed goods shipments of a customer that still have quantity left to "
            + "return: {data:[{id, documentNo, movementDate, businessPartner}]}. Step 1." + FLOW,
        NeoActionContract.Param.required("businessPartner", NeoActionContract.TYPE_STRING,
            "Id of the customer (C_BPartner_ID) of the return."))
        .withIdDescription(ID_NOT_USED));
    contracts.put(ACTION_AVAILABLE_LINES, NeoActionContract.read(ACTION_AVAILABLE_LINES,
        "Lists the lines of a shipment that can still be returned: {data:[{id, product, uOM, "
            + "movementQuantity}]}, where movementQuantity is the quantity still returnable (what "
            + "other non-voided returns, drafts included, already hold is subtracted). Step 2."
            + FLOW,
        NeoActionContract.Param.required("shipmentId", NeoActionContract.TYPE_STRING,
            "Id of a shipment returned by availableShipments."))
        .withIdDescription(ID_NOT_USED));
    contracts.put(ACTION_IMPORT_LINES, NeoActionContract.write(ACTION_IMPORT_LINES,
        "Adds return lines to a draft return receipt, each copied from a shipment line (product, "
            + "UOM, the link to the line it returns). Lines without a sourceLineId or with a "
            + "returnQuantity of 0 or less are skipped. More than what is still returnable of a "
            + "source line (the same line listed twice counts both) answers 422 "
            + "returnQuantityExceedsAvailable naming the product, the requested and the available "
            + "quantity, and adds nothing. Returns {data:{importedCount}}. Step 3." + FLOW,
        NeoActionContract.Param.array("lines", NeoActionContract.TYPE_OBJECT, true,
            "Each {sourceLineId, returnQuantity}: sourceLineId is the id of a line from "
                + "availableShipmentLines, returnQuantity a positive number not above its "
                + "movementQuantity."))
        .withIdDescription("The id of the draft return receipt the lines are added to.")
        .availableWhen(DocumentAvailability.WHEN_DRAFT));
    contracts.put(ACTION_RECTIFIABLE_INVOICES, NeoActionContract.read(ACTION_RECTIFIABLE_INVOICES,
        "Lists the confirmed sales invoices a completed return receipt can rectify, a page at a "
            + "time: {data:{invoices:[{id, documentNo, invoiceDate, grandTotalAmount, currency, "
            + "businessPartner, suggested}], hasMore, startRow, hasReturnInvoice, "
            + "suggestedInvoiceIds}}. Step 5, after the return is confirmed." + FLOW,
        NeoActionContract.Param.optional("startRow", NeoActionContract.TYPE_NUMBER,
            "Offset of the first invoice of the page. Default 0."),
        NeoActionContract.Param.optional("pageSize", NeoActionContract.TYPE_NUMBER,
            "Invoices per page. Default 80; the server clamps it to 1..500."),
        NeoActionContract.Param.optional("search", NeoActionContract.TYPE_STRING,
            "Case-insensitive fragment of the invoice document number or customer name."))
        .withIdDescription("The id of the completed return receipt.")
        .availableWhen(DocumentAvailability.WHEN_COMPLETED));
    contracts.put(ACTION_CREATE_RETURN_INVOICE, NeoActionContract.write(
        ACTION_CREATE_RETURN_INVOICE,
        "Creates the factura rectificativa (corrective sales invoice, ARI rectificative) of a "
            + "completed return receipt, links it to the invoices it rectifies and confirms it: "
            + "the invoice is left COMPLETED. Confirming the return does NOT do this; it is this "
            + "separate call. Refused with 400 when the receipt is not completed. A second call "
            + "is NOT refused and creates a second factura rectificativa, so check "
            + "hasReturnInvoice (in rectifiableInvoices) or returnInvoices (on the return header) "
            + "first; the answer lists, in previousReturnInvoices, the ones the return already "
            + "had before this call. Refused with 422 returnInvoiceWithoutAmount, creating "
            + "nothing, when a product has no price to refund. Returns {id, documentNo, "
            + "documentStatus, documentType, isSalesTransaction, grandTotalAmount, totalLines, "
            + "linkedReturnId, returnDocumentNo, rectifiedInvoices:[{id, documentNo}], "
            + "previousReturnInvoices:[{id, documentNo, documentStatus}]}; the amounts are "
            + "negative, it refunds. Step 6." + FLOW,
        NeoActionContract.Param.array("originInvoices", NeoActionContract.TYPE_STRING, false,
            "Ids of the invoices it rectifies, from rectifiableInvoices. Default: the invoice "
                + "auto-detected from the returned shipment lines; refused when none is found."))
        .withIdDescription("The id of the completed return receipt.")
        .availableWhen(DocumentAvailability.WHEN_COMPLETED));
    contracts.putAll(GoodsPostingContracts.CONTRACTS);
    contracts.put(ReturnCompletionContract.CONTRACT.getName(), ReturnCompletionContract.CONTRACT);
    return Collections.unmodifiableMap(contracts);
  }

  @Override
  protected NeoResponse continueHandling(NeoContext context) {
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

    return dispatchAction(context);
  }

  /**
   * Routes one POST action to its handler. Every other HTTP method, and any action this
   * window does not own, falls through to NEO's default processing by returning {@code null}
   * — the same outcome as before, with the method tested once instead of per branch.
   */
  private NeoResponse dispatchAction(NeoContext context) {
    if (!"POST".equals(context.getHttpMethod())) {
      return null;
    }
    String action = context.getFieldName();
    if (action == null) {
      return null;
    }
    switch (action) {
      case ACTION_IMPORT_LINES:
        return handleImportShipmentLines(context);
      case ACTION_AVAILABLE_SHIPMENTS:
        return handleAvailableShipments(context);
      case ACTION_AVAILABLE_LINES:
        return handleAvailableShipmentLines(context);
      case ACTION_CREATE_RETURN_INVOICE:
        return handleCreateReturnInvoice(context);
      case ACTION_RECTIFIABLE_INVOICES:
        return handleRectifiableInvoices(context);
      case ACTION_DOCUMENT_ACTION:
        NeoHandlerUtils.reanchorLinesToHeaderWarehouse(context.getRecordId(), log);
        return null; // let NEO native process handle completion
      default:
        return null;
    }
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
  @Override
  protected void mirrorAccountingDate(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      NeoHandlerUtils.mirrorFieldValue(context.getRequestBody(), FIELD_MOVEMENT_DATE, FIELD_ACCOUNTING_DATE);
    }
  }

  @SuppressWarnings("java:S2077")
  private NeoResponse handleImportShipmentLines(NeoContext context) {
    String receiptId = context.getRecordId();
    if (receiptId == null || receiptId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
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

        return ReturnShipmentUtils.importReturnLines(receipt, receiptId, requestedLines);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (NeoQuotaCreateCheck.Refused e) {
      return e.toResponse();
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

  /**
   * ETP-5381: returns the confirmed invoices this return receipt can rectify, so the UI can make
   * the user pick one before the rectificative invoice is created and confirmed.
   *
   * <p>Also reports which one was auto-detected, so the modal can preselect it, and whether the
   * return document already has an invoice — enough for the UI to disable the option rather than
   * let the user walk into a 409.
   */
  private NeoResponse handleRectifiableInvoices(NeoContext context) {
    String receiptId = context.getRecordId();
    if (receiptId == null || receiptId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      try {
        return RectifiableInvoiceUtils.buildRectifiableInvoicesResponse(context, receiptId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Could not list rectifiable invoices for receipt {}: {}", receiptId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error listing rectifiable invoices for receipt {}: {}", receiptId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while listing the invoices available to rectify");
    }
  }

  private NeoResponse handleCreateReturnInvoice(NeoContext context) {
    String receiptId = context.getRecordId();
    if (receiptId == null || receiptId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
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
            receipt.getClient(), receipt.getOrganization().getId(), "ARI", true, false, true);
        if (docType == null) {
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "No rectificative invoice document type found for this organization");
        }

        // ETP-5381 / ETP-5712: which invoices are rectified, the rectificativas the return already
        // had, the zero-amount refusal, then create, link and confirm — in that order, see the
        // shared body.
        return ReturnInvoiceSupport.createReturnInvoice(receipt, receiptId, lines, docType, true,
            context, createDraftInvoiceHandler);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (NeoQuotaCreateCheck.Refused e) {
      return e.toResponse();
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
