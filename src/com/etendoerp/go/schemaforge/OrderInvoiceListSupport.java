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

import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Shared implementation of the {@code listInvoices} GET action: lists the invoices linked to an
 * order, either through the invoice header ({@code C_Invoice.C_Order_ID}) or only through the
 * invoice lines ({@code C_InvoiceLine.C_OrderLine_ID}). The sales/purchase split is the structural
 * {@code IsSOTrx} flag, passed by the caller; this class names no entity.
 */
final class OrderInvoiceListSupport {

  private static final Logger log = LogManager.getLogger(OrderInvoiceListSupport.class);
  private static final String ERR_RECORD_ID_REQUIRED = "Record ID is required";
  private static final String PARAM_ORDER_ID = "orderId";
  private static final String PARAM_SALES_TRX = "salesTrx";

  private OrderInvoiceListSupport() {
  }

  /**
   * @param context
   *     the current NEO request context (record id = order id)
   * @param salesTransaction
   *     {@code true} to list sales invoices, {@code false} for purchase invoices
   * @return the {@code {response:{data:[...]}}} payload, or an error response
   */
  static NeoResponse listInvoices(NeoContext context, boolean salesTransaction) {
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      try {
        // Find invoices via order lines (covers all creation flows)
        String hql = "SELECT DISTINCT i FROM Invoice i JOIN i.invoiceLineList il "
            + "WHERE il.salesOrderLine.salesOrder.id = :orderId "
            + "AND i.salesTransaction = :salesTrx ORDER BY i.invoiceDate DESC";
        List<Invoice> invoices = OBDal.getInstance().getSession().createQuery(hql, Invoice.class)
            .setParameter(PARAM_ORDER_ID, recordId).setParameter(PARAM_SALES_TRX, salesTransaction)
            .setMaxResults(100).list();

        // Also include invoices with C_Order_ID set directly (created via our action)
        // that may not have lines (edge case: empty invoice)
        String hqlDirect = "FROM Invoice i WHERE i.salesOrder.id = :orderId "
            + "AND i.salesTransaction = :salesTrx ORDER BY i.invoiceDate DESC";
        List<Invoice> directInvoices = OBDal.getInstance().getSession().createQuery(hqlDirect, Invoice.class)
            .setParameter(PARAM_ORDER_ID, recordId).setParameter(PARAM_SALES_TRX, salesTransaction)
            .setMaxResults(100).list();

        // Merge both lists, deduplicate by ID
        Map<String, Invoice> merged = new LinkedHashMap<>();
        for (Invoice i : invoices) {
          merged.put(i.getId(), i);
        }
        for (Invoice i : directInvoices) {
          merged.putIfAbsent(i.getId(), i);
        }

        JSONArray arr = new JSONArray();
        for (Invoice inv : merged.values()) {
          JSONObject item = new JSONObject();
          item.put("id", inv.getId());
          item.put("documentNo", inv.getDocumentNo());
          item.put("documentStatus", inv.getDocumentStatus());
          item.put("grandTotalAmount", inv.getGrandTotalAmount() != null ? inv.getGrandTotalAmount() : 0);
          // ETP-5527 — same key the CRUD list returns, so the related-documents chip formats
          // the amount in the invoice's own currency (form and preview read this field).
          Currency currency = inv.getCurrency();
          item.put("currency$_identifier", currency != null ? currency.getISOCode() : JSONObject.NULL);
          if (inv.getInvoiceDate() != null) {
            item.put("invoiceDate", new SimpleDateFormat("yyyy-MM-dd").format(inv.getInvoiceDate()));
          }
          arr.put(item);
        }

        JSONObject responseData = new JSONObject();
        responseData.put("data", arr);
        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);
        return new NeoResponse(200, wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error listing invoices for order {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }
}
