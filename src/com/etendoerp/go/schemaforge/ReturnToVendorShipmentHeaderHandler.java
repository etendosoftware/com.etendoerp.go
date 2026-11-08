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
 * NeoHandler for the Return to Vendor Shipment header entity.
 *
 * <p>Handles custom actions: {@code availableReceipts}, {@code availableReceiptLines},
 * {@code importReceiptLines}, {@code createReturnInvoice}, and {@code cloneRecord}.
 *
 * <p>Injects {@code sourceReceiptDocNo}, {@code sourceReceipts}, {@code returnInvoices},
 * {@code linesCount} and {@code invoiceStatus} into every GET response via {@code afterHandle}.
 * {@code issuerOrg} is additionally injected on detail GETs only (ETP-4939), via the shared
 * {@link NeoHandlerUtils#enrichIssuerOrg}, also used by {@link GoodsShipmentHeaderHandler}.
 *
 * <p>Extends {@link AbstractReturnDocumentHeaderHandler}, which owns the {@code postingService}
 * injection point and the {@code handle()} wiring shared with {@link ReturnMaterialReceiptHeaderHandler}.
 */
@Named("returnToVendorShipmentHeaderHandler")
public class ReturnToVendorShipmentHeaderHandler extends AbstractReturnDocumentHeaderHandler {

  private static final Logger log = LogManager.getLogger(ReturnToVendorShipmentHeaderHandler.class);

  @Inject
  CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Inject
  NeoCloneRecordHandler cloneRecordHandler;

  private static final String FIELD_SOURCE_RECEIPT_DOC_NO = "sourceReceiptDocNo";
  private static final String FIELD_SOURCE_RECEIPTS = "sourceReceipts";
  private static final String FIELD_BUSINESS_PARTNER = "businessPartner";
  private static final String FIELD_MOVEMENT_DATE = "movementDate";
  private static final String FIELD_ACCOUNTING_DATE = "accountingDate";
  private static final String ACTION_IMPORT_LINES = "importReceiptLines";
  private static final String ACTION_AVAILABLE_RECEIPTS = "availableReceipts";
  private static final String ACTION_AVAILABLE_LINES = "availableReceiptLines";
  private static final String ACTION_CREATE_RETURN_INVOICE = "createReturnInvoice";
  /** ETP-5381: lists the confirmed invoices this return document can rectify. */
  private static final String ACTION_RECTIFIABLE_INVOICES = "rectifiableInvoices";
  private static final String ACTION_DOCUMENT_ACTION = "documentAction";
  private static final String ERR_RECORD_ID_REQUIRED = "Record ID is required";

  /**
   * The {@code etendo_action} id of an action that is not about a return shipment: the handler
   * never reads it, but the tool requires one.
   */
  private static final String ID_NOT_USED = "Not used — the list is not tied to a return "
      + "shipment. Pass \"_\".";
  private static final String FLOW = " Flow to create a return to a vendor (the UI's): create the "
      + "returnToVendorShipment header with etendo_create (businessPartner and partnerAddress; the "
      + "rest is defaulted — etendo_schema view:\"create\" is the authority on what it needs); "
      + "availableReceipts -> availableReceiptLines -> importReceiptLines on the new header (it "
      + "may take lines from several receipts of the same vendor); confirm it with the DocAction "
      + "button (docAction CO) — this does NOT create the factura rectificativa; then, as a "
      + "SEPARATE step, rectifiableInvoices -> createReturnInvoice to refund it with the factura "
      + "rectificativa. To return from ONE receipt without this flow, call "
      + "createPurchaseReturn on that goods receipt: it creates the draft return with its lines "
      + "linked in one step.";

  /**
   * The handler-served actions published to agents (ETP-5711), in flow order — the mirror of the
   * sales return's. The SPA reaches the same handlers through the ACTION sub-endpoint; the MCP
   * validates a call against these before the handler runs, the REST path is not judged by them.
   * {@code documentAction} (CO only, {@link InOutCompletionContract}) is served by NEO's native
   * process: {@link #dispatchAction} only re-anchors the lines to the header warehouse first.
   */
  private static final Map<String, NeoActionContract> ACTION_CONTRACTS = buildActionContracts();

  @Override
  public Map<String, NeoActionContract> actionContracts() {
    return ACTION_CONTRACTS;
  }

  /**
   * {@inheritDoc} The source receipts, the return invoices and the counters {@link #afterHandle}
   * adds to every GET record (ETP-5714).
   */
  @Override
  public Set<String> responseEnrichedFields() {
    return ReturnDocumentAvailability.enrichedFields(FIELD_SOURCE_RECEIPTS,
        FIELD_SOURCE_RECEIPT_DOC_NO);
  }

  /**
   * The returnable lines of one receipt: the quantity still returnable (column 5) and the
   * quantity the receipt line received (column 6, ETP-6054). Returns are counted by the rule of
   * {@link ReturnableQuantity}, so this list, the 201 of createPurchaseReturn and the goods
   * receipt's returnStatus cannot disagree about what is already returned.
   */
  static final String AVAILABLE_LINES_SQL =
      "SELECT l.M_InOutLine_ID, l.M_Product_ID, p.Name AS product_name, l.C_UOM_ID, "
          + "  l.MovementQty - COALESCE(ret.ret_qty, 0) AS available_qty, "
          + "  l.MovementQty AS received_qty "
          + "FROM M_InOutLine l "
          + "JOIN M_InOut h ON h.M_InOut_ID = l.M_InOut_ID "
          + "JOIN M_Product p ON p.M_Product_ID = l.M_Product_ID "
          + "LEFT JOIN ( "
          + "  SELECT rl.Canceled_Inoutline_ID, SUM(ABS(rl.MovementQty)) AS ret_qty "
          + "  FROM " + ReturnableQuantity.COUNTED_RETURN_LINES_FROM + " "
          + "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND "
          + ReturnableQuantity.COUNTED_RETURN_LINES_FILTER + " "
          + "  GROUP BY rl.Canceled_Inoutline_ID "
          + ") ret ON ret.Canceled_Inoutline_ID = l.M_InOutLine_ID "
          + "WHERE l.M_InOut_ID = ? "
          + "AND h.C_BPartner_ID = ? "
          + "AND l.MovementQty > COALESCE(ret.ret_qty, 0) "
          + "ORDER BY l.Line";

  private static Map<String, NeoActionContract> buildActionContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(ACTION_AVAILABLE_RECEIPTS, NeoActionContract.read(ACTION_AVAILABLE_RECEIPTS,
        "Lists the completed goods receipts of a vendor that still have quantity left to return: "
            + "{data:[{id, documentNo, movementDate, businessPartner}]}. Step 1." + FLOW,
        NeoActionContract.Param.required(FIELD_BUSINESS_PARTNER, NeoActionContract.TYPE_STRING,
            "Id of the vendor (C_BPartner_ID) of the return."))
        .withIdDescription(ID_NOT_USED));
    contracts.put(ACTION_AVAILABLE_LINES, NeoActionContract.read(ACTION_AVAILABLE_LINES,
        "Lists the lines of a goods receipt that can still be returned: {data:[{id, product, uOM, "
            + "movementQuantity, receivedQuantity}]}, where movementQuantity is the quantity still "
            + "returnable (what other non-voided returns, drafts included, already hold is "
            + "subtracted) and receivedQuantity is what the receipt line received. Step 2."
            + FLOW,
        NeoActionContract.Param.required("receiptId", NeoActionContract.TYPE_STRING,
            "Id of a receipt returned by availableReceipts."),
        NeoActionContract.Param.required(FIELD_BUSINESS_PARTNER, NeoActionContract.TYPE_STRING,
            "Id of the vendor of the return, the same one sent to availableReceipts."))
        .withIdDescription(ID_NOT_USED));
    contracts.put(ACTION_IMPORT_LINES, NeoActionContract.write(ACTION_IMPORT_LINES,
        "Adds return lines to a draft return shipment, each copied from a goods receipt line "
            + "(product, UOM, the link to the line it returns); the lines of several receipts of "
            + "the same vendor can be added, one call each. Lines without a sourceLineId or with "
            + "a returnQuantity of 0 or less are skipped. More than what is still returnable of a "
            + "source line (the same line listed twice counts both) answers 422 "
            + "returnQuantityExceedsAvailable naming the product, the requested and the available "
            + "quantity, and adds nothing. Returns {data:{importedCount}}. Step 3." + FLOW,
        NeoActionContract.Param.array("lines", NeoActionContract.TYPE_OBJECT, true,
            "Each {sourceLineId, returnQuantity}: sourceLineId is the id of a line from "
                + "availableReceiptLines, returnQuantity a positive number not above its "
                + "movementQuantity."))
        .withIdDescription("The id of the draft return shipment the lines are added to.")
        .availableWhen(DocumentAvailability.WHEN_DRAFT));
    contracts.put(ACTION_RECTIFIABLE_INVOICES, NeoActionContract.read(ACTION_RECTIFIABLE_INVOICES,
        "Lists the confirmed purchase invoices a completed return shipment can rectify, a page at "
            + "a time: {data:{invoices:[{id, documentNo, invoiceDate, grandTotalAmount, currency, "
            + "businessPartner, suggested}], hasMore, startRow, hasReturnInvoice, "
            + "suggestedInvoiceIds}}. Step 5, after the return is confirmed." + FLOW,
        NeoActionContract.Param.optional("startRow", NeoActionContract.TYPE_NUMBER,
            "Offset of the first invoice of the page. Default 0."),
        NeoActionContract.Param.optional("pageSize", NeoActionContract.TYPE_NUMBER,
            "Invoices per page. Default 80; the server clamps it to 1..500."),
        NeoActionContract.Param.optional("search", NeoActionContract.TYPE_STRING,
            "Case-insensitive fragment of the invoice document number or vendor name."))
        .withIdDescription("The id of the completed return shipment.")
        .availableWhen(DocumentAvailability.WHEN_COMPLETED));
    contracts.put(ACTION_CREATE_RETURN_INVOICE, NeoActionContract.write(
        ACTION_CREATE_RETURN_INVOICE,
        "Creates the factura rectificativa (corrective purchase invoice, API rectificative) of a "
            + "completed return shipment, links it to the invoices it rectifies and confirms it: "
            + "the invoice is left COMPLETED. Confirming the return does NOT do this; it is this "
            + "separate call. Refused with 400 when the shipment is not completed. A second call "
            + "is refused with 409 returnInvoiceAlreadyExists, creating nothing, "
            + "when the return already has a non-voided factura rectificativa (one per return, "
            + "as on the screen; see hasReturnInvoice in rectifiableInvoices or returnInvoices on "
            + "the return header), unless allowDuplicate:true is passed; the refusal lists the "
            + "existing ones in previousReturnInvoices, and with allowDuplicate:true the answer "
            + "lists the ones the return had before this call. Refused with 422 "
            + "returnInvoiceWithoutAmount, creating nothing, when a product has no price to refund. Returns {id, documentNo, "
            + "documentStatus, documentType, isSalesTransaction, grandTotalAmount, totalLines, "
            + "linkedReturnId, returnDocumentNo, rectifiedInvoices:[{id, documentNo}], "
            + "previousReturnInvoices:[{id, documentNo, documentStatus}]}; the amounts are "
            + "negative, it refunds. Step 6." + FLOW,
        NeoActionContract.Param.array("originInvoices", NeoActionContract.TYPE_STRING, false,
            "Ids of the invoices it rectifies, from rectifiableInvoices. Default: the invoice "
                + "auto-detected from the returned receipt lines; refused when none is found."),
        NeoActionContract.Param.optional(ReturnShipmentUtils.PARAM_ALLOW_DUPLICATE,
            NeoActionContract.TYPE_BOOLEAN,
            "Only the JSON boolean true creates a second factura rectificativa for a return "
                + "that already has one; anything else (absent, false, a string, a number) keeps "
                + "the one-per-return refusal. Use it only when a second one is really intended."))
        .withIdDescription("The id of the completed return shipment.")
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
      NeoHandlerUtils.injectReturnDocType(context, "MMR", false);
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
        return handleImportReceiptLines(context);
      case ACTION_AVAILABLE_RECEIPTS:
        return handleAvailableReceipts(context);
      case ACTION_AVAILABLE_LINES:
        return handleAvailableReceiptLines(context);
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

  // ---------------------------------------------------------------------------
  // Action: importReceiptLines
  // ---------------------------------------------------------------------------

  private NeoResponse handleImportReceiptLines(NeoContext context) {
    String returnId = context.getRecordId();
    if (returnId == null || returnId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
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

        return ReturnShipmentUtils.importReturnLines(returnDoc, returnId, requestedLines);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (NeoQuotaCreateCheck.Refused e) {
      return e.toResponse();
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
      String sql = AVAILABLE_LINES_SQL;

      JSONArray data = new JSONArray();
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, receiptId);
        ps.setString(2, bpId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            JSONObject row = ReturnShipmentUtils.buildAvailableLineRow(rs);
            row.put("receivedQuantity", rs.getBigDecimal(6));
            data.put(row);
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

  /**
   * ETP-5381: returns the confirmed invoices this return shipment can rectify, so the UI can make
   * the user pick one before the rectificative invoice is created and confirmed. Mirror of the
   * sales-side handler.
   */
  private NeoResponse handleRectifiableInvoices(NeoContext context) {
    String returnId = context.getRecordId();
    if (returnId == null || returnId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      try {
        return RectifiableInvoiceUtils.buildRectifiableInvoicesResponse(context, returnId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Could not list rectifiable invoices for shipment {}: {}", returnId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error listing rectifiable invoices for shipment {}: {}", returnId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while listing the invoices available to rectify");
    }
  }

  private NeoResponse handleCreateReturnInvoice(NeoContext context) {
    String returnId = context.getRecordId();
    if (returnId == null || returnId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
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

        // ETP-4737: resolves the unified "Factura Rectificativa" (API + isRectificative) doc
        // type, replacing the legacy plain-APC ("AP CreditMemo") lookup.
        DocumentType docType = ReturnShipmentUtils.findReturnDocTypeForOrg(
            returnDoc.getClient(), returnDoc.getOrganization().getId(), "API", false, false, true);
        if (docType == null) {
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "No rectificative invoice document type found for this organization");
        }

        // ETP-5381 / ETP-5712: which invoices are rectified, the rectificativas the return already
        // had, the zero-amount refusal, then create, link and confirm — in that order, see the
        // shared body.
        return ReturnInvoiceSupport.createReturnInvoice(returnDoc, returnId, lines, docType, false,
            context, createDraftInvoiceHandler);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (NeoQuotaCreateCheck.Refused e) {
      return e.toResponse();
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

  /**
   * Mirrors the single visible {@code movementDate} field into the hidden
   * {@code accountingDate} field on the request body, unconditionally, before the default CRUD
   * path persists it (ETP-4531 pattern, applied here for ETP-4737). The user never sees or
   * edits accountingDate directly — this window's {@code decisions.json} marks it
   * {@code discarded} — so without this mirror a bare POST-create (e.g. "+ Nuevo" directly on
   * this window, not via the goods-receipt "Crear Devolución" wizard, which sets both dates
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
        // ETP-4939: only enrich issuerOrg on a detail GET (single record), never on a
        // grid load — otherwise this fires one extra query per row (N+1).
        if (id != null && context.getRecordId() != null) {
          NeoHandlerUtils.enrichIssuerOrg(rec, id);
        }
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching return-to-vendor-shipment header", e);
      return null;
    }
  }

}
