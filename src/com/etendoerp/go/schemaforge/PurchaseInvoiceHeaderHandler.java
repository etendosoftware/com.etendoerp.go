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
 * {@link AbstractOrderHeaderHandler}.
 *
 * <p>Subtype resolution for AP invoices:
 * <ul>
 *   <li>{@code APC} → NC (Credit Note)</li>
 *   <li>{@code API} + isReturn → DEV (Return Invoice)</li>
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
    NeoResponse posting = postingService != null ? postingService.handleAction(context) : null;
    if (posting != null) {
      return posting;
    }
    NeoResponse lineQtyError = validateLineQtyBeforeComplete(context);
    if (lineQtyError != null) {
      return lineQtyError;
    }
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      NeoResponse lockError = validateDocTypeLock(context);
      if (lockError != null) {
        return lockError;
      }
      NeoResponse originError = validateOriginInvoiceRequired(context);
      if (originError != null) {
        return originError;
      }
    }
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, true);
    return NeoHeaderActionRouter.dispatch(
        context,
        currencyOptionsHandler,
        cloneRecordHandler,
        registerPaymentOutHandler,
        siiSendHandler,
        tbaiXmlgeneratorHandler);
  }

  /**
   * Post-callout hook (ETP-4029): blocks callout-driven currency updates and appends an
   * exchange-rate warning when the user directly changes the invoice currency. Mirrors
   * {@code AbstractOrderHeaderHandler#afterCallout}.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    return handleCurrencyAfterCallout(context);
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    autoCreateOrUpdateConversionRateDocument(context);
    try {
      // POST/PUT: persist origin invoice relationship after the record is saved
      if (NeoEndpointType.CRUD.equals(context.getEndpointType())
          && ("POST".equals(context.getHttpMethod()) || "PUT".equals(context.getHttpMethod()))) {
        persistOriginInvoice(context);
      }

      // GET: enrich response with virtual fields
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      if (context.getRecordId() != null) {
        JSONObject rec = dataArr.getJSONObject(0);
        enrichLinkedReceipts(rec, context.getRecordId());
        enrichOriginInvoice(rec, context.getRecordId());
        enrichInvoiceSubtype(rec, getInvoiceSubtypeKey());
        enrichDocTypeLocked(rec);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching purchase invoice", e);
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // AP-specific subtype resolution
  // ---------------------------------------------------------------------------

  /** {@inheritDoc} AP: APC → NC, API+isReturn → DEV, otherwise FAC. */
  @Override
  protected String classifyDocType(DocumentType dt) {
    String category = dt.getDocumentCategory();
    if ("APC".equals(category)) return SUBTYPE_NC;
    if ("API".equals(category) && Boolean.TRUE.equals(dt.isReturn())) return SUBTYPE_DEV;
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
