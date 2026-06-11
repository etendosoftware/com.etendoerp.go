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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler for the Sales Invoice header entity.
 * <p>
 * Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler} (uses {@code CloneInvoiceHook})</li>
 *   <li>{@code registerPayment} / {@code invoicePayments} / {@code invoiceAccounts} → {@link RegisterPaymentHandler}</li>
 *   <li>{@code Em_Aeatsii_Send} → {@link SiiSendHandler}</li>
 *   <li>{@code Em_Tbai_Xmlgenerator} → {@link TbaiXmlgeneratorHandler}</li>
 * </ul>
 *
 * <p>Before the Complete action (documentAction=CO), creates the total discount line so it is
 * included in the completed invoice. Delegates to {@link TotalDiscountService} via the shared
 * helper in {@link AbstractOrderHeaderHandler}.
 */
@Named("salesInvoiceHeaderHandler")
public class SalesInvoiceHeaderHandler implements NeoHandler {

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

  /**
   * Rounds a monetary value to 2 decimal places using half-up rounding.
   *
   * @param value
   *     the raw computed amount
   * @return the value rounded to 2 decimal places
   */
  private static double roundHalfUp(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  /**
   * Pre-hook: creates the total-discount line before the Complete action and routes all other
   * ACTION requests to the appropriate downstream handler.
   *
   * @param context
   *     the current request context
   * @return the response from the matched downstream handler, or null if none matched
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse rateError = AbstractOrderHeaderHandler.validateExchangeRateBeforeComplete(context);
    if (rateError != null) {
      return rateError;
    }
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, true);
    return NeoHeaderActionRouter.dispatch(context, cloneRecordHandler, registerPaymentHandler, siiSendHandler,
        tbaiXmlgeneratorHandler);
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
      JSONObject wrapper = body.optJSONObject("response");
      if (wrapper == null) {
        return null;
      }
      JSONArray data = wrapper.optJSONArray("data");
      if (data == null || data.length() == 0) {
        return null;
      }
      for (int i = 0; i < data.length(); i++) {
        JSONObject rec = data.getJSONObject(i);
        applyTotalDiscountToRecord(rec);
        if (context.getRecordId() != null) {
          enrichSourceInvoice(rec, context.getRecordId());
        }
      }
      TbaiSyncStatusInjector.inject(data);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error post-processing sales invoice GET response", e);
      return null;
    }
  }

  /**
   * Applies the total-discount factor to {@code grandTotalAmount} and {@code outstandingAmount}
   * in the given record. Skips confirmed invoices ({@code processed=true}) and records with no
   * positive discount.
   *
   * @param invoice
   *     a single invoice record from the response data array; modified in-place
   * @throws Exception
   *     if a JSON read or write operation fails
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

    double grandTotal = invoice.optDouble("grandTotalAmount", 0.0);
    invoice.put("grandTotalAmount", roundHalfUp(grandTotal * factor));

    double outstanding = invoice.optDouble("outstandingAmount", 0.0);
    invoice.put("outstandingAmount", roundHalfUp(outstanding * factor));
  }
}
