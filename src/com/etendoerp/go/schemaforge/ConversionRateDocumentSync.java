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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Raw-JDBC persistence for the {@code C_Conversion_Rate_Document} row backing an
 * invoice's Exchange Rates tab. Extracted out of {@link AbstractInvoiceHeaderHandler}
 * (ETP-4836 — that class was at Sonar's 35-method class-size ceiling) so this
 * single-table upsert concern has its own home instead of padding out a header
 * handler that already owns document-type locking, SII/TBAI enrichment, and
 * origin-invoice bookkeeping.
 */
final class ConversionRateDocumentSync {

  private static final Logger log = LogManager.getLogger(ConversionRateDocumentSync.class);

  private ConversionRateDocumentSync() {
    // static helper — no instances
  }

  /**
   * Matches by {@code (c_invoice_id, c_currency_id_to)} only — deliberately NOT by
   * {@code c_currency_id}. The doc currency is the very thing that changes when the
   * user switches the invoice currency more than once; matching on it made every
   * currency switch look like a brand-new pair and left the previous currency's row
   * orphaned (ETP-4836). {@code c_currency_id_to} (the org currency) never changes
   * for a given invoice, so it's the correct — and only needed — join key.
   */
  static void upsert(Invoice invoice, String orgCurrencyId, BigDecimal docRate,
      BigDecimal foreignAmount) throws java.sql.SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    List<String> existingIds = findConversionRateDocumentIds(conn, invoice.getId(), orgCurrencyId);
    if (existingIds.isEmpty()) {
      insertConversionRateDocument(conn, invoice, orgCurrencyId, docRate, foreignAmount);
      return;
    }
    // Most recent row (ORDER BY created DESC) is updated in place, including its
    // currency — self-healing any stray duplicates left by the pre-ETP-4836 bug by
    // deleting every other row found for this invoice.
    String keepId = existingIds.get(0);
    updateConversionRateDocument(conn, keepId, invoice.getCurrency().getId(), docRate, foreignAmount);
    for (int i = 1; i < existingIds.size(); i++) {
      String staleId = existingIds.get(i);
      deleteConversionRateDocument(conn, staleId);
      log.info("[ETP-4836] Deleted stale duplicate C_Conversion_Rate_Document {} for invoice {}",
          staleId, invoice.getId());
    }
  }

  private static List<String> findConversionRateDocumentIds(Connection conn, String invoiceId,
      String orgCurrencyId) throws java.sql.SQLException {
    String sql =
        "SELECT c_conversion_rate_document_id FROM c_conversion_rate_document"
      + " WHERE c_invoice_id = ? AND c_currency_id_to = ? ORDER BY created DESC";
    List<String> ids = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      ps.setString(2, orgCurrencyId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getString(1));
        }
      }
    }
    return ids;
  }

  private static void updateConversionRateDocument(Connection conn, String recordId,
      String docCurrencyId, BigDecimal docRate, BigDecimal foreignAmount) throws java.sql.SQLException {
    String userId = OBContext.getOBContext().getUser().getId();
    String sql =
        "UPDATE c_conversion_rate_document"
      + " SET c_currency_id = ?, rate = ?, foreign_amount = ?, updated = NOW(), updatedby = ?"
      + " WHERE c_conversion_rate_document_id = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, docCurrencyId);
      ps.setBigDecimal(2, docRate);
      if (foreignAmount != null) {
        ps.setBigDecimal(3, foreignAmount);
      } else {
        ps.setNull(3, java.sql.Types.NUMERIC);
      }
      ps.setString(4, userId);
      ps.setString(5, recordId);
      ps.executeUpdate();
      log.info("[ETP-4029] Updated C_Conversion_Rate_Document {} (currency={}, docRate={})",
          recordId, docCurrencyId, docRate);
    }
  }

  private static void deleteConversionRateDocument(Connection conn, String recordId)
      throws java.sql.SQLException {
    String sql = "DELETE FROM c_conversion_rate_document WHERE c_conversion_rate_document_id = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, recordId);
      ps.executeUpdate();
    }
  }

  /** Package-visible: also reused by {@link InvoiceFromOrderSupport} to avoid duplicating
   * this insert for the order→invoice rate-propagation path (ETP-4027). */
  static void insertConversionRateDocument(Connection conn, Invoice invoice,
      String orgCurrencyId, BigDecimal docRate, BigDecimal foreignAmount) throws java.sql.SQLException {
    String newId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    String userId = OBContext.getOBContext().getUser().getId();
    String sql =
        "INSERT INTO c_conversion_rate_document ("
      + " c_conversion_rate_document_id, ad_client_id, ad_org_id, isactive,"
      + " created, createdby, updated, updatedby,"
      + " c_invoice_id, c_currency_id, c_currency_id_to, rate, foreign_amount"
      + ") VALUES (?, ?, ?, 'Y', NOW(), ?, NOW(), ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, newId);
      ps.setString(2, invoice.getClient().getId());
      ps.setString(3, invoice.getOrganization().getId());
      ps.setString(4, userId);
      ps.setString(5, userId);
      ps.setString(6, invoice.getId());
      ps.setString(7, invoice.getCurrency().getId());
      ps.setString(8, orgCurrencyId);
      ps.setBigDecimal(9, docRate);
      if (foreignAmount != null) {
        ps.setBigDecimal(10, foreignAmount);
      } else {
        ps.setNull(10, java.sql.Types.NUMERIC);
      }
      ps.executeUpdate();
      log.info("[ETP-4029] Created C_Conversion_Rate_Document {} for invoice {} (docRate={})",
          newId, invoice.getId(), docRate);
    }
  }
}
