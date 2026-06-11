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

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler for the Purchase Invoice header entity.
 *
 * Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler} (uses {@code CloneInvoiceHook})</li>
 *   <li>{@code registerPayment} / {@code invoicePayments} / {@code invoiceAccounts} → {@link RegisterPaymentOutHandler}</li>
 *   <li>{@code Em_Aeatsii_Send} → {@link SiiSendHandler}</li>
 *   <li>{@code Em_Tbai_Xmlgenerator} → {@link TbaiXmlgeneratorHandler}</li>
 * </ul>
 *
 * <p>Before the Complete action (documentAction=CO), validates that CreditMemo and
 * Reversed Purchase Invoice documents have an origin invoice set, then creates the total
 * discount line. Delegates to {@link TotalDiscountService} via the shared helper in
 * {@link AbstractOrderHeaderHandler}.
 */
@Named("purchaseInvoiceHeaderHandler")
public class PurchaseInvoiceHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceHeaderHandler.class);
  private static final String FIELD_DOCUMENT_ACTION = "documentAction";
  private static final String DOC_AP_CREDIT_MEMO = "AP CreditMemo";
  private static final String DOC_REVERSED_PURCHASE_INVOICE = "Reversed Purchase Invoice";

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

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse rateError = AbstractOrderHeaderHandler.validateExchangeRateBeforeComplete(context);
    if (rateError != null) {
      return rateError;
    }
    NeoResponse validationError = validateOriginInvoiceOnComplete(context);
    if (validationError != null) {
      return validationError;
    }
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, true);
    return NeoHeaderActionRouter.dispatch(
        context,
        cloneRecordHandler,
        registerPaymentOutHandler,
        siiSendHandler,
        tbaiXmlgeneratorHandler);
  }

  private NeoResponse validateOriginInvoiceOnComplete(NeoContext context) {
    if (!isCompleteAction(context)) {
      return null;
    }
    String recordId = context.getRecordId();
    if (recordId == null || recordId.isEmpty()) {
      return null;
    }
    String sql =
        "SELECT dt.name, i.em_etgo_origin_invoice_id " +
        "FROM c_invoice i " +
        "JOIN c_doctype dt ON dt.c_doctype_id = i.c_doctypetarget_id " +
        "WHERE i.c_invoice_id = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, recordId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        String docTypeName = rs.getString(1);
        String originId = rs.getString(2);
        if ((DOC_AP_CREDIT_MEMO.equals(docTypeName) || DOC_REVERSED_PURCHASE_INVOICE.equals(docTypeName))
            && (originId == null || originId.isEmpty())) {
          return NeoResponse.error(400, "Origin Invoice is required for document type: " + docTypeName);
        }
      }
    } catch (Exception e) {
      log.error("Error validating origin invoice before complete for id={}", recordId, e);
    }
    return null;
  }

  private boolean isCompleteAction(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      String method = context.getHttpMethod();
      if (!"PATCH".equals(method) && !"PUT".equals(method)) {
        return false;
      }
      JSONObject body = context.getRequestBody();
      return body != null && "CO".equals(body.optString(FIELD_DOCUMENT_ACTION, ""));
    }
    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && FIELD_DOCUMENT_ACTION.equals(context.getFieldName())) {
      JSONObject body = context.getRequestBody();
      if (body == null) {
        return false;
      }
      JSONObject fieldValues = body.optJSONObject("fieldValues");
      String docAction = fieldValues != null
          ? fieldValues.optString(FIELD_DOCUMENT_ACTION, "")
          : body.optString("docAction", body.optString(FIELD_DOCUMENT_ACTION, ""));
      return "CO".equals(docAction);
    }
    return false;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      if (context.getRecordId() != null) {
        enrichLinkedReceipts(dataArr.getJSONObject(0), context.getRecordId());
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching purchase invoice with linked receipts", e);
      return null;
    }
  }

  @SuppressWarnings("java:S2077")
  private void enrichLinkedReceipts(JSONObject rec, String invoiceId) {
    String sql =
        "SELECT DISTINCT io.m_inout_id, io.documentno, io.docstatus "
        + "FROM c_invoiceline il "
        + "JOIN m_inoutline iol ON ("
        + "  iol.m_inoutline_id = il.m_inoutline_id "
        + "  OR (il.c_orderline_id IS NOT NULL AND iol.c_orderline_id = il.c_orderline_id)"
        + ") "
        + "JOIN m_inout io ON io.m_inout_id = iol.m_inout_id "
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
          receipts.put(receipt);
        }
      }
      rec.put("linkedReceipts", receipts);
    } catch (Exception e) {
      log.warn("Could not enrich linked receipts for invoice {}: {}", invoiceId, e.getMessage());
    }
  }
}
