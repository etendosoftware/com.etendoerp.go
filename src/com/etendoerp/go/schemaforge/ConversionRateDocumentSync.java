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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 *
 * <p><b>Savepoint contract (ETP-5547).</b> Every write entry point runs its statements under a
 * JDBC savepoint ({@link JdbcSavepoints}) on the request's shared connection and rolls back to
 * it when a statement fails, before rethrowing. The callers swallow the exception (the
 * rate-doc sync is a best-effort side effect of an invoice save), but PostgreSQL marks the whole transaction as
 * aborted on the first failed statement — without the savepoint the request would still answer
 * 2xx and then lose every other write at commit time. That is exactly what happened when Core's
 * {@code c_conversion_rate_document_trg} rejected an UPDATE on a posted invoice with
 * {@code @20501@}: a confirmed payment and a cloned invoice vanished silently.
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
      BigDecimal foreignAmount) throws SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    JdbcSavepoints.run(conn, () -> doUpsert(conn, invoice, orgCurrencyId, docRate, foreignAmount));
  }

  private static void doUpsert(Connection conn, Invoice invoice, String orgCurrencyId,
      BigDecimal docRate, BigDecimal foreignAmount) throws SQLException {
    List<ExistingRow> existing = findConversionRateDocuments(conn, invoice.getId(), orgCurrencyId);
    if (existing.isEmpty()) {
      insertRow(conn, invoice, orgCurrencyId, docRate, foreignAmount);
      return;
    }
    // Most recent row (ORDER BY created DESC) is updated in place, including its
    // currency — self-healing any stray duplicates left by the pre-ETP-4836 bug by
    // deleting every other row found for this invoice.
    ExistingRow keep = existing.get(0);
    String docCurrencyId = invoice.getCurrency().getId();
    if (keep.matches(docCurrencyId, docRate, foreignAmount)) {
      // ETP-5547: nothing changed — skip the UPDATE. This hook runs on every header write, so
      // a no-op UPDATE is both wasted work and one more chance to hit the posted-document trigger.
      log.debug("[ETP-5547] C_Conversion_Rate_Document {} already up to date for invoice {}",
          keep.id(), invoice.getId());
    } else {
      updateConversionRateDocument(conn, keep.id(), docCurrencyId, docRate, foreignAmount);
    }
    for (int i = 1; i < existing.size(); i++) {
      String staleId = existing.get(i).id();
      deleteConversionRateDocument(conn, staleId);
      log.info("[ETP-4836] Deleted stale duplicate C_Conversion_Rate_Document {} for invoice {}",
          staleId, invoice.getId());
    }
  }

  /**
   * Removes every {@code C_Conversion_Rate_Document} row for this invoice+org-currency pair.
   * Called when the invoice's currency is switched back to the org currency: at that point no
   * exchange rate applies anymore, so a row from an earlier foreign-currency state would be
   * stale and must not be left behind (ETP-4836 — QA regression: switching a foreign currency
   * to another foreign currency correctly replaced the row, but switching back to the org
   * currency silently no-op'd instead of clearing it).
   */
  static void deleteAllForInvoice(String invoiceId, String orgCurrencyId) throws SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    JdbcSavepoints.run(conn, () -> {
      List<ExistingRow> existing = findConversionRateDocuments(conn, invoiceId, orgCurrencyId);
      for (ExistingRow row : existing) {
        deleteConversionRateDocument(conn, row.id());
        log.info("[ETP-4836] Deleted C_Conversion_Rate_Document {} for invoice {} (doc currency"
            + " now matches org currency)", row.id(), invoiceId);
      }
    });
  }

  /**
   * An existing {@code C_Conversion_Rate_Document} row, with the values the upsert compares
   * against before deciding whether an UPDATE is needed.
   */
  record ExistingRow(String id, String currencyId, BigDecimal rate, BigDecimal foreignAmount) {

    /** {@code true} when this row already holds exactly the given values (BigDecimal by value). */
    boolean matches(String otherCurrencyId, BigDecimal otherRate, BigDecimal otherForeignAmount) {
      return Objects.equals(currencyId, otherCurrencyId)
          && sameAmount(rate, otherRate)
          && sameAmount(foreignAmount, otherForeignAmount);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
      if (a == null || b == null) {
        return a == null && b == null;
      }
      return a.compareTo(b) == 0;
    }
  }

  private static List<ExistingRow> findConversionRateDocuments(Connection conn, String invoiceId,
      String orgCurrencyId) throws SQLException {
    String sql =
        "SELECT c_conversion_rate_document_id, c_currency_id, rate, foreign_amount"
      + " FROM c_conversion_rate_document"
      + " WHERE c_invoice_id = ? AND c_currency_id_to = ? ORDER BY created DESC";
    List<ExistingRow> rows = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      ps.setString(2, orgCurrencyId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(new ExistingRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
              rs.getBigDecimal(4)));
        }
      }
    }
    return rows;
  }

  private static void updateConversionRateDocument(Connection conn, String recordId,
      String docCurrencyId, BigDecimal docRate, BigDecimal foreignAmount) throws SQLException {
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
      throws SQLException {
    String sql = "DELETE FROM c_conversion_rate_document WHERE c_conversion_rate_document_id = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, recordId);
      ps.executeUpdate();
    }
  }

  /**
   * Package-visible: also reused by {@link InvoiceFromOrderSupport} to avoid duplicating
   * this insert for the order→invoice rate-propagation path (ETP-4027). Runs under its own
   * savepoint (ETP-5547) because that caller also swallows the exception.
   */
  static void insertConversionRateDocument(Connection conn, Invoice invoice,
      String orgCurrencyId, BigDecimal docRate, BigDecimal foreignAmount) throws SQLException {
    JdbcSavepoints.run(conn, () -> insertRow(conn, invoice, orgCurrencyId, docRate, foreignAmount));
  }

  private static void insertRow(Connection conn, Invoice invoice, String orgCurrencyId,
      BigDecimal docRate, BigDecimal foreignAmount) throws SQLException {
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
