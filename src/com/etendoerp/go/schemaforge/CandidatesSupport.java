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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.ReconciliationSupport.bindDateRange;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.envelope;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.formatDate;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.nullSafe;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Read-only candidate-listing helpers backing the {@code candidates} action of
 * {@link ReconciliationHandler}: the linked-movements list for a reconciled line and the per-type
 * counts of the right-panel transaction-type selector. Extracted to keep the handler class under
 * the Sonar method-count limit.
 */
final class CandidatesSupport {

  private static final Logger log = LogManager.getLogger(CandidatesSupport.class);

  private static final String KEY_ID = "id";
  private static final String KEY_DATE = "date";
  private static final String KEY_AMOUNT = "amount";
  private static final String KEY_STATUS = "status";
  private static final String KEY_DOCUMENT_NO = "documentNo";
  private static final String KEY_PARTNER_NAME = "partnerName";
  private static final String KEY_PENDING_BALANCE = "pendingBalance";
  private static final String KEY_SUGGESTED = "suggested";
  private static final String COL_PARTNER_NAME = "partner_name";
  private static final String KEY_COUNTS = "counts";
  private static final String SQL_VARCHAR = "varchar";
  private static final String CNT_RECEIPTS = "receipts";
  private static final String CNT_PAYMENTS = "payments";
  private static final String CNT_SALES_INVOICES = "salesInvoices";
  private static final String CNT_PURCHASE_INVOICES = "purchaseInvoices";
  private static final String STATUS_PENDING = "pending";
  private static final String STATUS_RECONCILED = "reconciled";
  private static final String ACTION_CANDIDATES = "candidates";

  /**
   * Movements already linked to a reconciled statement line (panel right, read-only). Returns the
   * line's own transaction (1:1) plus every transaction of its 1:N match group, so the merged
   * reconciled line shows exactly the movements it groups — and nothing else. {@code lineId} is
   * bound twice (the line itself, and the group sub-query).
   */
  private static final String LINKED_TXNS_SQL =
      "SELECT ft.fin_finacc_transaction_id,"
          + "       ft.statementdate,"
          + "       COALESCE(fp.documentno, '') AS document_no,"
          + "       COALESCE(bp.name, '') AS partner_name,"
          + "       COALESCE(ft.depositamt, 0) - COALESCE(ft.paymentamt, 0) AS amount"
          + "  FROM fin_bankstatementline bsl"
          + "  JOIN fin_finacc_transaction ft ON ft.fin_finacc_transaction_id = bsl.fin_finacc_transaction_id"
          + "  LEFT JOIN fin_payment fp ON fp.fin_payment_id = ft.fin_payment_id"
          + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = COALESCE(ft.c_bpartner_id, fp.c_bpartner_id)"
          + " WHERE bsl.fin_finacc_transaction_id IS NOT NULL"
          + "   AND ( bsl.fin_bankstatementline_id = ?"
          + "         OR ( COALESCE(bsl.em_etgo_match_group_id, '') <> ''"
          + "              AND bsl.em_etgo_match_group_id ="
          + "                  (SELECT em_etgo_match_group_id FROM fin_bankstatementline"
          + "                    WHERE fin_bankstatementline_id = ?) ) )"
          + " ORDER BY ft.statementdate ASC";

  /** Per-isreceipt count of reconcilable transactions of the account (for the type selector). */
  private static final String TXN_COUNTS_SQL =
      "SELECT COALESCE(fp.isreceipt, '') AS is_receipt, COUNT(*) AS cnt"
          + "  FROM fin_finacc_transaction ft"
          + "  LEFT JOIN fin_payment fp ON fp.fin_payment_id = ft.fin_payment_id"
          + " WHERE ft.fin_reconciliation_id IS NULL"
          + "   AND ft.processed = 'Y'"
          + "   AND ft.status <> 'RPPC'"
          + "   AND ft.fin_financial_account_id = ?"
          + "   AND (CAST(? AS date) IS NULL OR ft.statementdate >= ?)"
          + "   AND (CAST(? AS date) IS NULL OR ft.statementdate <= ?)"
          + " GROUP BY fp.isreceipt";

  /** Per-issotrx count of unpaid invoice installments (for the type selector). */
  private static final String INVOICE_COUNTS_SQL =
      "SELECT t.issotrx, COUNT(*) AS cnt FROM ("
          + "  SELECT ps.fin_payment_schedule_id, inv.issotrx"
          + "    FROM fin_payment_scheduledetail psd"
          + "    JOIN fin_payment_schedule ps ON ps.fin_payment_schedule_id = psd.fin_payment_schedule_invoice"
          + "    JOIN c_invoice inv ON inv.c_invoice_id = ps.c_invoice_id"
          + "   WHERE psd.fin_payment_detail_id IS NULL"
          + "     AND inv.docstatus = 'CO'"
          + "     AND inv.ad_client_id = ?"
          + "     AND inv.ad_org_id = ANY (?)"
          + "     AND (CAST(? AS date) IS NULL OR inv.dateinvoiced >= ?)"
          + "     AND (CAST(? AS date) IS NULL OR inv.dateinvoiced <= ?)"
          + "   GROUP BY ps.fin_payment_schedule_id, inv.issotrx"
          + "   HAVING SUM(psd.amount) > 0"
          + " ) t GROUP BY t.issotrx";

  private CandidatesSupport() {
  }

  /**
   * Read-only "linked movements" list for a reconciled line: its 1:1 transaction, or every
   * transaction of its 1:N match group. Same row shape as the candidates list, flagged
   * {@code linked} with a reconciled status so the UI renders the panel read-only.
   */
  static NeoResponse buildLinkedTransactions(String lineId) throws Exception {
    JSONArray candidates = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(LINKED_TXNS_SQL)) {
      ps.setString(1, lineId);
      ps.setString(2, lineId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BigDecimal amount = nullSafe(rs.getBigDecimal(KEY_AMOUNT));
          JSONObject row = new JSONObject();
          row.put(KEY_ID, rs.getString("fin_finacc_transaction_id"));
          row.put(KEY_DATE, formatDate(rs.getTimestamp("statementdate")));
          row.put(KEY_DOCUMENT_NO, StringUtils.trimToEmpty(rs.getString("document_no")));
          row.put(KEY_PARTNER_NAME, StringUtils.trimToEmpty(rs.getString(COL_PARTNER_NAME)));
          row.put(KEY_AMOUNT, amount);
          row.put(KEY_PENDING_BALANCE, amount);
          row.put(KEY_STATUS, STATUS_RECONCILED);
          row.put(KEY_SUGGESTED, false);
          row.put("linked", true);
          candidates.put(row);
        }
      }
    }
    JSONObject data = new JSONObject();
    data.put(ACTION_CANDIDATES, candidates);
    return envelope(data);
  }

  /**
   * Per-type counts for the right-panel "Tipo de transacción" selector: reconcilable transactions
   * split by receipt/payment, plus unpaid sales/purchase invoice installments (account org tree).
   */
  static JSONObject candidateCounts(String accountId, String dateFrom, String dateTo) {
    JSONObject counts = new JSONObject();
    try {
      counts.put(CNT_RECEIPTS, 0);
      counts.put(CNT_PAYMENTS, 0);
      counts.put(CNT_SALES_INVOICES, 0);
      counts.put(CNT_PURCHASE_INVOICES, 0);
      FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
      if (account == null) {
        return counts;
      }
      computeCandidateCounts(counts, account, dateFrom, dateTo);
    } catch (Exception e) {
      // Counts are decorative; never fail the candidates response over them.
      log.debug("Could not compute candidate counts for {}: {}", accountId, e.getMessage());
    }
    return counts;
  }

  private static void computeCandidateCounts(JSONObject counts, FIN_FinancialAccount account,
      String dateFrom, String dateTo) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(TXN_COUNTS_SQL)) {
      ps.setString(1, account.getId());
      bindDateRange(ps, 2, dateFrom, dateTo);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String receipt = rs.getString("is_receipt");
          if ("Y".equals(receipt)) {
            counts.put(CNT_RECEIPTS, rs.getInt("cnt"));
          } else if ("N".equals(receipt)) {
            counts.put(CNT_PAYMENTS, rs.getInt("cnt"));
          }
        }
      }
    }
    OrganizationStructureProvider osp = OBContext.getOBContext()
        .getOrganizationStructureProvider(account.getClient().getId());
    Set<String> orgs = osp.getNaturalTree(account.getOrganization().getId());
    try (PreparedStatement ps = conn.prepareStatement(INVOICE_COUNTS_SQL)) {
      ps.setString(1, account.getClient().getId());
      ps.setArray(2, conn.createArrayOf(SQL_VARCHAR, orgs.toArray(new String[0])));
      bindDateRange(ps, 3, dateFrom, dateTo);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String issotrx = rs.getString("issotrx");
          if ("Y".equals(issotrx)) {
            counts.put(CNT_SALES_INVOICES, rs.getInt("cnt"));
          } else if ("N".equals(issotrx)) {
            counts.put(CNT_PURCHASE_INVOICES, rs.getInt("cnt"));
          }
        }
      }
    }
  }
}
