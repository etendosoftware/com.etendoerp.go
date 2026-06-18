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

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse rateError = AbstractOrderHeaderHandler.validateExchangeRateBeforeComplete(context);
    if (rateError != null) {
      return rateError;
    }
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      NeoResponse lockError = validateDocTypeLock(context);
      if (lockError != null) {
        return lockError;
      }
    }
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, true);
    return NeoHeaderActionRouter.dispatch(context, cloneRecordHandler, registerPaymentHandler,
        siiSendHandler, tbaiXmlgeneratorHandler, createInvoiceShipmentHandler);
  }

  /**
   * Adjusts grandTotalAmount / outstandingAmount for draft invoices with a total discount, and
   * injects {@code tbaiSyncEstado} (latest sync status from {@code tbai_syncinvoice}) into every
   * record so the frontend can display it without a separate inSet GET request to the TBAI spec.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
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

  /**
   * {@inheritDoc}
   *
   * <p>AR invoice subtype rules:
   * <ul>
   *   <li>{@code ARC} → {@code NC}</li>
   *   <li>{@code ARI_RM} → {@code DEV}</li>
   *   <li>otherwise → {@code FAC}</li>
   * </ul>
   */
  @Override
  protected String resolveSubtype(String docTypeId) {
    if (StringUtils.isBlank(docTypeId)) {
      return SUBTYPE_FAC;
    }
    try {
      DocumentType dt = OBDal.getInstance().get(DocumentType.class, docTypeId);
      if (dt == null) {
        return SUBTYPE_FAC;
      }
      String category = dt.getDocumentCategory();
      if ("ARC".equals(category)) {
        return SUBTYPE_NC;
      }
      if ("ARI_RM".equals(category)) {
        return SUBTYPE_DEV;
      }
    } catch (Exception e) {
      log.debug("Could not resolve AR subtype for docType {}: {}", docTypeId, e.getMessage());
    }
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
    double grand = rec.optDouble("grandTotalAmount", 0.0);
    if (grand > 0) {
      rec.put("grandTotalAmount", -grand);
    }
    double outstanding = rec.optDouble("outstandingAmount", 0.0);
    if (outstanding > 0) {
      rec.put("outstandingAmount", -outstanding);
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
          retReceipt.put("documentNo", rs.getString("ret_doc"));
          retReceipt.put("documentStatus", rs.getString("ret_status"));
          rec.put("sourceReturnReceipt", retReceipt);

          String origInvId = rs.getString("inv_id");
          if (origInvId != null) {
            JSONObject sourceInvoice = new JSONObject();
            sourceInvoice.put("id", origInvId);
            sourceInvoice.put("documentNo", rs.getString("inv_doc"));
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
    double grand = invoice.optDouble("grandTotalAmount", 0.0);
    invoice.put("grandTotalAmount", roundHalfUp(grand * factor));
    double outstanding = invoice.optDouble("outstandingAmount", 0.0);
    invoice.put("outstandingAmount", roundHalfUp(outstanding * factor));
  }

  private static double roundHalfUp(double value) {
    return Math.round(value * 100.0) / 100.0;
  }
}
