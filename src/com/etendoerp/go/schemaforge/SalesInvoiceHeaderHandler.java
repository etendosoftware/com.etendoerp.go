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

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * NeoHandler for the Sales Invoice header entity.
 *
 * <p>Extends {@link AbstractInvoiceHeaderHandler} to inherit shared document-type-lock
 * enforcement and GET enrichment logic.
 *
 * <p>Subtype resolution for AR invoices:
 * <ul>
 *   <li>{@code ARC} → NC (Credit Memo)</li>
 *   <li>{@code ARI_RM} → DEV (Return Invoice)</li>
 *   <li>otherwise → FAC (Standard Invoice)</li>
 * </ul>
 *
 * <p>GET enrichment injects {@code arInvoiceSubtype} into every record (list and detail)
 * and negates {@code grandTotalAmount} / {@code outstandingAmount} for NC and DEV subtypes.
 * {@code docTypeLocked} is injected only in detail-view responses.
 *
 * <p>Dispatches custom ACTION requests:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code registerPayment} / {@code invoicePayments} / {@code invoiceAccounts} → {@link RegisterPaymentHandler}</li>
 *   <li>{@code Em_Aeatsii_Send} → {@link SiiSendHandler}</li>
 *   <li>{@code Em_Tbai_Xmlgenerator} → {@link TbaiXmlgeneratorHandler}</li>
 * </ul>
 */
@Named("salesInvoiceHeaderHandler")
public class SalesInvoiceHeaderHandler extends AbstractInvoiceHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(SalesInvoiceHeaderHandler.class);

  private static final String FIELD_GRAND_TOTAL_AMOUNT = "grandTotalAmount";
  private static final String FIELD_OUTSTANDING_AMOUNT = "outstandingAmount";
  private static final String FIELD_DOCUMENT_NO = "documentNo";

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private RegisterPaymentHandler registerPaymentHandler;

  @Inject
  private SiiSendHandler siiSendHandler;

  @Inject
  private TbaiXmlgeneratorHandler tbaiXmlgeneratorHandler;

  @Inject
  private TotalDiscountService totalDiscountService;

  @Inject
  private CreateInvoiceShipmentHandler createInvoiceShipmentHandler;

  @Inject
  private DocumentPostingService postingService;

  @Inject
  private CurrencyOptionsHandler currencyOptionsHandler;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    mirrorAccountingDate(context);
    NeoResponse posting = postingService != null ? postingService.handleAction(context) : null;
    if (posting != null) {
      return posting;
    }
    NeoResponse lineQtyError = validateLineQtyBeforeComplete(context);
    if (lineQtyError != null) {
      return lineQtyError;
    }
    // Must run BEFORE completeInvoiceIfNeeded: the discount line has to reflect the final set of
    // product lines before ProcessInvoiceUtil.process() completes/posts the document (ETP-4388).
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, true);
    NeoResponse completionResponse = completeInvoiceIfNeeded(context);
    if (completionResponse != null) {
      return completionResponse;
    }
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      NeoResponse lockError = validateDocTypeLock(context);
      if (lockError != null) {
        return lockError;
      }
    }
    return NeoHeaderActionRouter.dispatch(context, currencyOptionsHandler, cloneRecordHandler,
        registerPaymentHandler, siiSendHandler, tbaiXmlgeneratorHandler, createInvoiceShipmentHandler);
  }

  /**
   * Post-callout hook: blocks callout-driven currency updates and appends an exchange-rate
   * warning when the user directly changes the invoice currency (ETP-4029), and blocks
   * callout-driven document type updates once the invoice has been saved (ETP-4535). Mirrors
   * {@code AbstractOrderHeaderHandler#afterCallout}.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    return handleInvoiceAfterCallout(context);
  }

  /**
   * Adjusts grandTotalAmount / outstandingAmount for draft invoices with a total discount, and
   * injects {@code tbaiSyncEstado} (latest sync status from {@code tbai_syncinvoice}) into every
   * record so the frontend can display it without a separate inSet GET request to the TBAI spec.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    autoCreateOrUpdateConversionRateDocument(context);
    if (!"GET".equals(context.getHttpMethod()) || !NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = prev.getBody();
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null || dataArr.length() == 0) {
        return null;
      }
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        enrichInvoiceSubtype(rec, getInvoiceSubtypeKey());
        applyAmountNegationForCredit(rec);
        applyTotalDiscountToRecord(rec);
      }
      if (context.getRecordId() != null) {
        JSONObject rec = dataArr.getJSONObject(0);
        enrichSourceInvoice(rec, context.getRecordId());
        enrichDocTypeLocked(rec);
        enrichIsRectificative(rec);
        enrichHasRectifications(rec, context.getRecordId());
        enrichLinkedShipments(rec, context.getRecordId());
      }
      TbaiSyncStatusInjector.inject(dataArr);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching sales invoice response", e);
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // AR-specific subtype resolution
  // ---------------------------------------------------------------------------

  /** {@inheritDoc} AR: ARC → NC, ARI_RM → DEV, otherwise FAC. */
  @Override
  protected String classifyDocType(DocumentType dt) {
    String category = dt.getDocumentCategory();
    if ("ARC".equals(category)) return SUBTYPE_NC;
    if ("ARI_RM".equals(category)) return SUBTYPE_DEV;
    return SUBTYPE_FAC;
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code "arInvoiceSubtype"}
   */
  @Override
  protected String getInvoiceSubtypeKey() {
    return "arInvoiceSubtype";
  }

  // ---------------------------------------------------------------------------
  // Amount adjustments
  // ---------------------------------------------------------------------------

  /**
   * Negates {@code grandTotalAmount} and {@code outstandingAmount} for credit memo (NC) and
   * return invoice (DEV) records. Credit instruments represent money owed to the customer,
   * so amounts are displayed as negative in the list.
   */
  private void applyAmountNegationForCredit(JSONObject rec) throws Exception {
    String subtype = rec.optString(getInvoiceSubtypeKey(), SUBTYPE_FAC);
    if (!SUBTYPE_NC.equals(subtype) && !SUBTYPE_DEV.equals(subtype)) {
      return;
    }
    double grand = rec.optDouble(FIELD_GRAND_TOTAL_AMOUNT, 0.0);
    if (grand > 0) {
      rec.put(FIELD_GRAND_TOTAL_AMOUNT, -grand);
    }
    double outstanding = rec.optDouble(FIELD_OUTSTANDING_AMOUNT, 0.0);
    if (outstanding > 0) {
      rec.put(FIELD_OUTSTANDING_AMOUNT, -outstanding);
    }
  }

  /**
   * Applies the etgoTotalDiscount factor to {@code grandTotalAmount} and {@code outstandingAmount}
   * for draft invoices. Skips confirmed invoices and records with no positive discount.
   */
  /**
   * For return invoices, injects:
   * - {@code sourceReturnReceipt}: the return receipt that originated this invoice
   * - {@code sourceInvoice}: the original invoice being reversed
   * Both are traced via C_InvoiceLine → M_InOutLine chains.
   * Returns nothing for regular invoices (no Canceled_Inoutline_ID on their lines).
   */
  @SuppressWarnings("java:S2077")
  private void enrichSourceInvoice(JSONObject rec, String invoiceId) {
    String sql =
        "SELECT DISTINCT " +
        "  ret.M_InOut_ID AS ret_id, ret.DocumentNo AS ret_doc, ret.DocStatus AS ret_status, " +
        "  orig_i.C_Invoice_ID AS inv_id, orig_i.DocumentNo AS inv_doc " +
        "FROM C_InvoiceLine il " +
        "JOIN M_InOutLine ret_line ON ret_line.M_InOutLine_ID = il.M_InOutLine_ID " +
        "JOIN M_InOut ret ON ret.M_InOut_ID = ret_line.M_InOut_ID " +
        "LEFT JOIN M_InOutLine orig_line ON orig_line.M_InOutLine_ID = ret_line.Canceled_Inoutline_ID " +
        "LEFT JOIN C_InvoiceLine orig_il ON orig_il.M_InOutLine_ID = orig_line.M_InOutLine_ID " +
        "LEFT JOIN C_Invoice orig_i ON orig_i.C_Invoice_ID = orig_il.C_Invoice_ID " +
        "  AND orig_i.DocStatus != 'VO' " +
        "WHERE il.C_Invoice_ID = ? AND ret_line.Canceled_Inoutline_ID IS NOT NULL " +
        "ORDER BY orig_i.DateInvoiced DESC LIMIT 1";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          JSONObject retReceipt = new JSONObject();
          retReceipt.put("id", rs.getString("ret_id"));
          retReceipt.put(FIELD_DOCUMENT_NO, rs.getString("ret_doc"));
          retReceipt.put("documentStatus", rs.getString("ret_status"));
          rec.put("sourceReturnReceipt", retReceipt);

          String origInvId = rs.getString("inv_id");
          if (origInvId != null) {
            JSONObject sourceInvoice = new JSONObject();
            sourceInvoice.put("id", origInvId);
            sourceInvoice.put(FIELD_DOCUMENT_NO, rs.getString("inv_doc"));
            rec.put("sourceInvoice", sourceInvoice);
          }
        }
      }
    } catch (Exception e) {
      log.warn("Could not enrich return invoice relations for {}: {}", invoiceId, e.getMessage());
    }
  }

  private void applyTotalDiscountToRecord(JSONObject invoice) throws Exception {
    if (invoice.optBoolean("processed", false)) {
      return;
    }
    double discountPct = invoice.optDouble("etgoTotalDiscount", 0.0);
    if (discountPct <= 0.0) {
      return;
    }
    double factor = 1.0 - discountPct / 100.0;
    double grand = invoice.optDouble(FIELD_GRAND_TOTAL_AMOUNT, 0.0);
    invoice.put(FIELD_GRAND_TOTAL_AMOUNT, roundHalfUp(grand * factor));
    double outstanding = invoice.optDouble(FIELD_OUTSTANDING_AMOUNT, 0.0);
    invoice.put(FIELD_OUTSTANDING_AMOUNT, roundHalfUp(outstanding * factor));
  }

  private static double roundHalfUp(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  /**
   * Injects {@code linkedShipments} into the invoice detail record.
   * Finds all sales shipments (M_InOut) whose lines are referenced by the invoice's
   * C_InvoiceLine.M_InOutLine_ID. Covers invoices created directly from a shipment
   * (standalone or via-order), where the native process always populates M_InOutLine_ID.
   *
   * <p>Joins C_DocType to expose {@code isReturn}, the actual discriminator between a
   * regular delivery and a customer return: M_InOut.MovementType is NOT usable for this
   * (see M_INOUT_TRG_PROV.xml) — it only ever takes 'C-' for every sales-side movement
   * (shipments AND returns alike) or 'V+' for purchase-side, never branching on
   * C_DocType.IsReturn. ETP-4534.</p>
   */
  @SuppressWarnings("java:S2077")
  private void enrichLinkedShipments(JSONObject rec, String invoiceId) {
    String sql =
        "SELECT DISTINCT io.m_inout_id, io.documentno, io.docstatus, io.movementtype, dt.isreturn " +
        "FROM c_invoiceline il " +
        "JOIN m_inoutline iol ON (" +
        "  iol.m_inoutline_id = il.m_inoutline_id " +
        "  OR (il.m_inoutline_id IS NULL AND il.c_orderline_id IS NOT NULL AND iol.c_orderline_id = il.c_orderline_id)" +
        ") " +
        "JOIN m_inout io ON io.m_inout_id = iol.m_inout_id " +
        "JOIN c_doctype dt ON dt.c_doctype_id = io.c_doctype_id " +
        "WHERE il.c_invoice_id = ? AND il.isactive = 'Y' " +
        "  AND io.isactive = 'Y' AND io.docstatus NOT IN ('VO','CL') " +
        "  AND io.issotrx = 'Y'";
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      JSONArray shipments = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject s = new JSONObject();
          s.put("id", rs.getString(1));
          s.put(FIELD_DOCUMENT_NO, rs.getString(2));
          s.put("documentStatus", rs.getString(3));
          s.put("movementType", rs.getString(4));
          s.put("isReturn", "Y".equalsIgnoreCase(rs.getString(5)));
          shipments.put(s);
        }
      }
      rec.put("linkedShipments", shipments);
    } catch (Exception e) {
      log.warn("Could not enrich linked shipments for invoice {}: {}", invoiceId, e.getMessage());
    }
  }
}
