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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * Read-only enrichments of the invoice DETAIL record that only query the database by id (no
 * handler state): {@code tbaiConfigActive}, {@code hasRectifications} and {@code linkedOrders}.
 * Called from the sales and purchase invoice header handlers' {@code afterHandle()}; split out of
 * {@link AbstractInvoiceHeaderHandler} to keep that class within the method-count limit. Logs
 * under the handler's own logger, as before.
 */
final class InvoiceDetailEnrichment {

  private static final Logger log = LogManager.getLogger(AbstractInvoiceHeaderHandler.class);

  private InvoiceDetailEnrichment() {
  }

  /**
   * ETP-4783: Injects the synthetic {@code tbaiConfigActive} field into the GET response
   * so the header form's {@code displayLogic} for {@code tbaiReverseinvoicecode} can
   * evaluate it client-side.
   *
   * <p>Queries {@code tbai_config} for an active record in the same org as the invoice.
   * Injects {@code 'Y'} when found, {@code 'N'} otherwise. The backend ignores this
   * synthetic field in PATCH bodies ({@code NeoFieldFilter} drops unknown fields).
   */
  static void enrichTbaiConfigActive(JSONObject rec) {
    String value = "N"; // default: TBAI not active
    try {
      String orgId = rec.isNull("organization") ? null : rec.optString("organization", null);
      if (orgId != null && !orgId.isEmpty()) {
        String sql = "SELECT 1 FROM tbai_config WHERE ad_org_id = ? AND isactive = 'Y' LIMIT 1";
        Connection conn = OBDal.getReadOnlyInstance().getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
          ps.setString(1, orgId);
          try (ResultSet rs = ps.executeQuery()) {
            value = rs.next() ? "Y" : "N";
          }
        }
      }
    } catch (Exception e) {
      log.debug("[ETP-4783] enrichTbaiConfigActive: {}", e.getMessage());
    }
    try {
      rec.put("tbaiConfigActive", value);
    } catch (Exception ignored) {
      // best-effort: field stays absent on failure, which displayLogicJs treats as shown (safe default for TBAI orgs)
    }
  }

  /**
   * Injects {@code hasRectifications} (boolean) into the record.
   * True when the invoice has one or more records in {@code C_Invoice_Reverse}.
   * Only meaningful in detail view (single record); do not call for list responses.
   */
  @SuppressWarnings("java:S2077")
  static void enrichHasRectifications(JSONObject rec, String invoiceId) throws Exception {
    if (StringUtils.isBlank(invoiceId)) {
      rec.put("hasRectifications", false);
      return;
    }
    boolean result = false;
    try {
      String sql = "SELECT 1 FROM c_invoice_reverse WHERE c_invoice_id = ? AND isactive = 'Y' LIMIT 1";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, invoiceId);
        try (ResultSet rs = ps.executeQuery()) {
          result = rs.next();
        }
      }
    } catch (Exception e) {
      log.warn("Could not check hasRectifications for invoice {}: {}", invoiceId, e.getMessage());
    }
    rec.put("hasRectifications", result);
  }

  /**
   * Injects {@code linkedOrders} into the invoice detail record (ETP-5828): every order one of the
   * invoice's lines comes from, through {@code C_InvoiceLine.C_OrderLine_ID}. The header's
   * {@code C_Order_ID} cannot answer this — since ETP-5381 it is blanked when the lines come from
   * more than one order (Classic's rule) — so an invoice built from several orders would show none
   * of them as related documents. Same placement and contract as the sales handler's
   * {@code linkedShipments}: detail GET only, best-effort (the key stays absent on failure).
   *
   * <p>Each entry carries what the related-documents order chip renders: {@code id},
   * {@code documentNo}, {@code documentStatus}, {@code grandTotalAmount},
   * {@code currency$_identifier} (ISO code), {@code orderDate} ({@code yyyy-MM-dd}) and
   * {@code isQuotation} ({@code DocSubTypeSO = 'OB'}: a sales invoice can come from a quotation).
   *
   * @param rec
   *     the detail record to enrich
   * @param invoiceId
   *     the invoice id
   */
  @SuppressWarnings("java:S2077") // constant SQL, every value is bound
  static void enrichLinkedOrders(JSONObject rec, String invoiceId) {
    if (StringUtils.isBlank(invoiceId)) {
      return;
    }
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(AbstractInvoiceHeaderHandler.LINKED_ORDERS_SQL)) {
      ps.setString(1, invoiceId);
      ps.setString(2, TotalDiscountService.DISCOUNT_PRODUCT_ID);
      JSONArray orders = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject o = new JSONObject();
          o.put("id", rs.getString(1));
          o.put("documentNo", rs.getString(2));
          o.put("documentStatus", rs.getString(3));
          BigDecimal total = rs.getBigDecimal(4);
          o.put(AbstractInvoiceHeaderHandler.FIELD_GRAND_TOTAL_AMOUNT, total != null ? total : BigDecimal.ZERO);
          String iso = rs.getString(5);
          o.put("currency$_identifier", iso != null ? iso : JSONObject.NULL);
          java.sql.Date ordered = rs.getDate(6);
          if (ordered != null) {
            o.put("orderDate", ordered.toString());
          }
          o.put("isQuotation", "OB".equals(rs.getString(7)));
          orders.put(o);
        }
      }
      rec.put(AbstractInvoiceHeaderHandler.FIELD_LINKED_ORDERS, orders);
    } catch (Exception e) {
      log.warn("Could not enrich linked orders for invoice {}: {}", invoiceId, e.getMessage());
    }
  }
}
