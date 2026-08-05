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
 * NeoHandler for the Purchase Invoice header entity.
 *
 * <p>Extends {@link AbstractInvoiceHeaderHandler} to inherit shared document-type-lock
 * enforcement, origin-invoice persistence, and GET enrichment logic.
 *
 * <p>Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code registerPayment} / {@code invoicePayments} / {@code invoiceAccounts} → {@link RegisterPaymentOutHandler}</li>
 *   <li>{@code Em_Aeatsii_Send} → {@link SiiSendHandler}</li>
 *   <li>{@code Em_Tbai_Xmlgenerator} → {@link TbaiXmlgeneratorHandler}</li>
 * </ul>
 *
 * <p>Before the Complete action (documentAction=CO), creates the total discount line.
 * Delegates to {@link TotalDiscountService} via the shared helper in
 * {@link AbstractOrderHeaderHandler}. While still in draft, every GET response (list and
 * detail) has {@code grandTotalAmount} / {@code outstandingAmount} adjusted for a pending total
 * discount not yet materialized as a real line — see
 * {@link AbstractInvoiceHeaderHandler#applyTotalDiscountToRecord}.
 *
 * <p>Subtype resolution for AP invoices (ETP-4737 — unified "Factura Rectificativa"):
 * <ul>
 *   <li>{@code EM_Etsg_Isrectificative = 'Y'} → RECTIFICATIVA (new unified type)</li>
 *   <li>legacy fallback — {@code APC} (Credit Note) or {@code API} + isReturn (Return Invoice) → RECTIFICATIVA</li>
 *   <li>otherwise → FAC (Standard Invoice)</li>
 * </ul>
 */
@Named("purchaseInvoiceHeaderHandler")
public class PurchaseInvoiceHeaderHandler extends AbstractInvoiceHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceHeaderHandler.class);

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private RegisterPaymentOutHandler registerPaymentOutHandler;

  @Inject
  private SiiSendHandler siiSendHandler;

  @Inject
  private TbaiXmlgeneratorHandler tbaiXmlgeneratorHandler;

  @Inject
  private TotalDiscountService totalDiscountService;

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
    NeoHandlerUtils.mirrorAccountingDate(context, "invoiceDate", "accountingDate");
    captureOriginInvoice(context);
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
    return NeoHeaderActionRouter.dispatch(
        context,
        currencyOptionsHandler,
        cloneRecordHandler,
        registerPaymentOutHandler,
        siiSendHandler,
        tbaiXmlgeneratorHandler);
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

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    autoCreateOrUpdateConversionRateDocument(context);
    try {
      // POST/PUT/PATCH: persist origin invoice relationship after the record is saved.
      // ETP-4737 fix: the ImportFromSourceInvoiceModal.afterImport hook links via a PATCH
      // (not POST/PUT), which this condition previously excluded — the origin-invoice link
      // was silently never persisted for that flow (confirmed empty C_Invoice_Reverse table).
      if (NeoEndpointType.CRUD.equals(context.getEndpointType())
          && NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
        persistOriginInvoice(context);
      }

      // GET: enrich response with virtual fields
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        applyTotalDiscountToRecord(rec);
        enrichInvoiceSubtype(rec, getInvoiceSubtypeKey());
      }
      if (context.getRecordId() != null) {
        JSONObject rec = dataArr.getJSONObject(0);
        enrichLinkedReceipts(rec, context.getRecordId());
        enrichOriginInvoice(rec, context.getRecordId());
        enrichDocTypeLocked(rec);
        enrichIsRectificative(rec);
        enrichHasRectifications(rec, context.getRecordId());
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching purchase invoice", e);
      return null;
    }
  }

  @Override
  protected TotalDiscountService getTotalDiscountService() {
    return totalDiscountService;
  }

  // ---------------------------------------------------------------------------
  // AP-specific subtype resolution
  // ---------------------------------------------------------------------------

  /**
   * {@inheritDoc} AP: {@code EM_Etsg_Isrectificative = 'Y'} (ETP-4737 unified "Factura
   * Rectificativa") → RECTIFICATIVA; legacy fallback for pre-existing invoices — APC (Credit
   * Note) or API+isReturn (Return Invoice) → RECTIFICATIVA; otherwise FAC.
   */
  @Override
  protected String classifyDocType(DocumentType dt) {
    if (RectificativeSupport.isRectificative(dt)) {
      return SUBTYPE_RECTIFICATIVA;
    }
    String category = dt.getDocumentCategory();
    if ("APC".equals(category)) return SUBTYPE_RECTIFICATIVA;
    if ("API".equals(category) && Boolean.TRUE.equals(dt.isReturn())) return SUBTYPE_RECTIFICATIVA;
    return SUBTYPE_FAC;
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code "apInvoiceSubtype"}
   */
  @Override
  protected String getInvoiceSubtypeKey() {
    return "apInvoiceSubtype";
  }

  // ---------------------------------------------------------------------------
  // AP-specific GET enrichment
  // ---------------------------------------------------------------------------


  @SuppressWarnings("java:S2077")
  private void enrichLinkedReceipts(JSONObject rec, String invoiceId) {
    String sql =
        "SELECT DISTINCT io.m_inout_id, io.documentno, io.docstatus, dt.isreturn "
        + "FROM c_invoiceline il "
        + "JOIN m_inoutline iol ON ("
        + "  iol.m_inoutline_id = il.m_inoutline_id "
        + "  OR (il.m_inoutline_id IS NULL AND il.c_orderline_id IS NOT NULL AND iol.c_orderline_id = il.c_orderline_id)"
        + ") "
        + "JOIN m_inout io ON io.m_inout_id = iol.m_inout_id "
        + "JOIN c_doctype dt ON dt.c_doctype_id = io.c_doctype_id "
        + "WHERE il.c_invoice_id = ? AND il.isactive = 'Y' "
        + "  AND io.isactive = 'Y' AND io.docstatus NOT IN ('VO','CL') "
        + "  AND io.issotrx = 'N'";
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      JSONArray receipts = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject receipt = new JSONObject();
          receipt.put("id", rs.getString(1));
          receipt.put("documentNo", rs.getString(2));
          receipt.put("documentStatus", rs.getString(3));
          receipt.put("isReturn", "Y".equals(rs.getString(4)));
          receipts.put(receipt);
        }
      }
      rec.put("linkedReceipts", receipts);
    } catch (Exception e) {
      log.warn("Could not enrich linked receipts for invoice {}: {}", invoiceId, e.getMessage());
    }
  }
}
