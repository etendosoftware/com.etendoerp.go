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

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.ModelProvider;

/**
 * Stateless helpers shared across {@link BankStatementsHandler}: statement
 * status derivation, null-safe / parsing utilities and ISO date formatting.
 *
 * <p>Extracted from the handler so it stays under the per-class method-count
 * limit; every method here is pure (no DB, no OBContext) and trivially testable.
 */
public final class BankStatementsSupport {

  private static final Logger log = LogManager.getLogger(BankStatementsSupport.class);

  private static final String FIELD_AMOUNT = "amount";

  /** JSON/SQL key for the bank-statement-line description field. */
  static final String FIELD_DESCRIPTION = "description";

  /** JSON keys / reconcile-status codes reused across rows — extracted to satisfy Sonar S1192. */
  private static final String KEY_MATCHED = "matched";
  private static final String STATUS_RECONCILED = "RECONCILED";
  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_PARTIAL = "PARTIAL";
  private static final String KEY_PENDING_AMOUNT = "pendingAmount";
  private static final String KEY_REMAINDER_LINE_ID = "remainderLineId";

  // Cached result of the C43 column existence check (null = not yet checked).
  private static volatile Boolean c43DescColumn;

  /**
   * SQL expression for the description column of {@code FIN_BankStatementLine}.
   * Prefers {@code bsl.description}; when the optional C43 module column
   * {@code bsl.em_c43_description} exists (detected via {@link ModelProvider},
   * database-agnostic), falls back to it so C43-imported statements show the
   * concept text that Classic stores in that extension column.
   *
   * <p>Result is cached after the first call (the module set is fixed at runtime).
   *
   * @return SQL expression string, never {@code null}
   */
  public static String descriptionExpr() {
    Boolean cached = c43DescColumn;
    if (cached == null) {
      try {
        org.openbravo.base.model.Entity entity =
            ModelProvider.getInstance().getEntityByTableName("FIN_BankStatementLine");
        cached = entity != null && entity.getProperties().stream()
            .anyMatch(p -> "em_c43_description".equalsIgnoreCase(p.getColumnName()));
      } catch (Exception e) {
        log.debug("Could not check C43 column existence via ModelProvider; defaulting to false", e);
        cached = Boolean.FALSE;
      }
      c43DescColumn = cached;
    }
    return Boolean.TRUE.equals(cached)
        ? "COALESCE(NULLIF(TRIM(bsl.description), ''), NULLIF(TRIM(bsl.em_c43_description), ''), '')"
        : "COALESCE(bsl.description, '')";
  }

  /**
   * Maps one {@code LINES_SQL_HEAD} result row to the line JSON contract.
   * Extracted here (away from BankStatementsHandler) to keep the handler
   * within the per-class method-count limit.
   *
   * @param rs an open {@link java.sql.ResultSet} positioned on the current row
   * @return a JSON object representing the bank-statement line
   * @throws Exception if any ResultSet accessor throws
   */
  public static JSONObject mapLineRow(ResultSet rs) throws Exception {
    BigDecimal credit = nullSafeBigDecimal(rs.getBigDecimal("cramount"));
    BigDecimal debit  = nullSafeBigDecimal(rs.getBigDecimal("dramount"));
    JSONObject row = new JSONObject();
    row.put("id", rs.getString("fin_bankstatementline_id"));
    row.put("lineNo", rs.getLong("line"));
    row.put("date", formatDate(rs.getTimestamp("datetrx")));
    row.put(FIELD_DESCRIPTION, StringUtils.trimToEmpty(rs.getString(FIELD_DESCRIPTION)));
    row.put("reference",      StringUtils.trimToEmpty(rs.getString("referenceno")));
    row.put("bpartnerName",   StringUtils.trimToEmpty(rs.getString("bpartnername")));
    row.put("bpartnerId",     StringUtils.trimToEmpty(rs.getString("c_bpartner_id")));
    row.put("bpartnerFkName", StringUtils.trimToEmpty(rs.getString("bpartner_fk_name")));
    row.put("glItemId",       StringUtils.trimToEmpty(rs.getString("c_glitem_id")));
    row.put("glItemName",     StringUtils.trimToEmpty(rs.getString("glitem_name")));
    row.put("in",     credit);
    row.put("out",    debit);
    BigDecimal amount = credit.subtract(debit);
    row.put(FIELD_AMOUNT, amount);
    boolean matched = rs.getString("fin_finacc_transaction_id") != null;
    row.put(KEY_MATCHED, matched);
    // Per-row reconcile status/pending amount — the seed that mergeSubLineIntoHead accumulates
    // across a match group's physical rows (see there for why a group needs this instead of the
    // plain `matched` flag once a partial match is involved). pendingAmount comes from the persisted
    // EM_ETGO_Pending_Amount column (maintained by BankStatementLinePendingAmountHandler) so this
    // view and the reconciliation-tab pending-lines view share a single source of truth.
    row.put("reconcileStatus", matched ? STATUS_RECONCILED : STATUS_PENDING);
    row.put(KEY_PENDING_AMOUNT, nullSafeBigDecimal(rs.getBigDecimal("em_etgo_pending_amount")));
    // 1:N reconcile group (option B): split sub-lines share this id so they can be re-grouped.
    row.put("matchGroupId", StringUtils.trimToEmpty(rs.getString("em_etgo_match_group_id")));
    row.put("txns", buildLineTxns(rs, matched));
    return row;
  }

  /**
   * Collapses the split sub-lines of a 1:N reconciliation back into a single display line.
   * Sub-lines that share a non-blank {@code matchGroupId} are merged into the first occurrence:
   * their {@code txns[]} are concatenated and their {@code in}/{@code out}/{@code amount} summed,
   * so the line shows the original amount and ALL the transactions it was reconciled against.
   * Lines without a match group pass through unchanged, preserving order.
   *
   * @param lines the raw lines array from the statement-lines query
   * @return a new array with 1:N split sub-lines collapsed into their group head
   * @throws JSONException if JSON access fails
   */
  public static JSONArray mergeMatchGroups(JSONArray lines) throws JSONException {
    JSONArray result = new JSONArray();
    Map<String, JSONObject> heads = new LinkedHashMap<>();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.getJSONObject(i);
      String groupId = line.optString("matchGroupId", "");
      JSONObject head = StringUtils.isBlank(groupId) ? null : heads.get(groupId);
      if (StringUtils.isBlank(groupId) || head == null) {
        // Lines without a group, or the first occurrence of a group, pass through as-is.
        if (StringUtils.isNotBlank(groupId)) {
          heads.put(groupId, line);
          // If the group's head sub-line is itself the pending remainder, it is the line the UI
          // reconciles the rest against (see remainderLineId below).
          if (!line.optBoolean(KEY_MATCHED, false)) {
            line.put(KEY_REMAINDER_LINE_ID, line.optString("id"));
          }
        }
        result.put(line);
      } else {
        mergeSubLineIntoHead(head, line);
      }
    }
    return result;
  }

  /**
   * Appends the txns of {@code line} into {@code head} and accumulates in/out/amount/
   * pendingAmount, recomputing the group's overall {@code reconcileStatus}.
   *
   * <p>A match group can legitimately end up PARTIAL, not just PENDING/RECONCILED: e.g. a 100
   * line matched to a single 53.24 invoice reconciles that portion in full and leaves a 46.76
   * pending remainder as a second physical sub-line (same {@code matchGroupId}) — this merges
   * both back into ONE display row carrying the original 100 total, a 46.76 {@code pendingAmount},
   * and {@code reconcileStatus: "PARTIAL"}, instead of showing two unrelated-looking rows (see
   * ETP-4502 iteration 4).
   */
  private static void mergeSubLineIntoHead(JSONObject head, JSONObject line) throws JSONException {
    JSONArray headTxns = head.optJSONArray("txns");
    if (headTxns == null) {
      headTxns = new JSONArray();
      head.put("txns", headTxns);
    }
    JSONArray lineTxns = line.optJSONArray("txns");
    if (lineTxns != null) {
      for (int j = 0; j < lineTxns.length(); j++) {
        headTxns.put(lineTxns.get(j));
      }
    }
    head.put("in", jsonBigDecimal(head, "in").add(jsonBigDecimal(line, "in")));
    head.put("out", jsonBigDecimal(head, "out").add(jsonBigDecimal(line, "out")));
    head.put(FIELD_AMOUNT, jsonBigDecimal(head, FIELD_AMOUNT).add(jsonBigDecimal(line, FIELD_AMOUNT)));
    head.put(KEY_PENDING_AMOUNT,
        jsonBigDecimal(head, KEY_PENDING_AMOUNT).add(jsonBigDecimal(line, KEY_PENDING_AMOUNT)));
    // Remember the group's pending remainder sub-line (first unmatched one wins) — the UI reconciles
    // the rest of the line against it (ETP-4502 iteration 5). Additive; consumers that don't need it
    // (imported-statements view) simply ignore it.
    if (StringUtils.isBlank(head.optString(KEY_REMAINDER_LINE_ID, ""))
        && !line.optBoolean(KEY_MATCHED, false)) {
      head.put(KEY_REMAINDER_LINE_ID, line.optString("id"));
    }
    // The merged group is reconciled only while it still carries transactions AND nothing is left
    // pending. After a reactivate the sub-lines keep the match-group tag but lose their
    // transaction, so deriving status from the accumulated txns/pendingAmount (instead of forcing
    // RECONCILED) lets the group correctly fall back to PARTIAL/PENDING.
    boolean anyMatched = headTxns.length() > 0;
    boolean fullyCovered = jsonBigDecimal(head, KEY_PENDING_AMOUNT).signum() == 0;
    String status;
    if (!anyMatched) {
      status = STATUS_PENDING;
    } else if (fullyCovered) {
      status = STATUS_RECONCILED;
    } else {
      status = STATUS_PARTIAL;
    }
    head.put("reconcileStatus", status);
    head.put(KEY_MATCHED, STATUS_RECONCILED.equals(status));
  }

  private static BigDecimal jsonBigDecimal(JSONObject o, String key) {
    Object v = o.opt(key);
    if (v == null) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(v.toString());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private BankStatementsSupport() {
    // utility class — no instances
  }

  /**
   * Three-state status derived from how many of the statement's lines are
   * already matched to a financial-account transaction:
   * {@code matched == 0} → PENDING; {@code 0 < matched < total} → PARTIAL;
   * {@code matched == total > 0} → RECONCILED; empty statement → PENDING.
   *
   * @param lineCount    total number of lines in the statement
   * @param matchedCount number of those lines already matched to a transaction
   * @return one of {@code "PENDING"}, {@code "PARTIAL"} or {@code "RECONCILED"}
   */
  public static String deriveStatementStatus(int lineCount, int matchedCount) {
    if (lineCount == 0 || matchedCount == 0) return STATUS_PENDING;
    if (matchedCount >= lineCount) return STATUS_RECONCILED;
    return STATUS_PARTIAL;
  }

  /**
   * Status that also accounts for whether the statement has been processed.
   * An unprocessed statement is a draft regardless of its matching state;
   * once processed, the matching-based status applies.
   *
   * @param processed    whether the statement's Processed flag is set
   * @param lineCount    total number of lines in the statement
   * @param matchedCount number of those lines already matched to a transaction
   * @return {@code "DRAFT"} when not processed, otherwise one of
   *         {@code "PENDING"}, {@code "PARTIAL"} or {@code "RECONCILED"}
   */
  public static String deriveStatementStatus(boolean processed, int lineCount, int matchedCount) {
    if (!processed) return "DRAFT";
    return deriveStatementStatus(lineCount, matchedCount);
  }

  /**
   * Returns {@link BigDecimal#ZERO} for {@code null}, otherwise the value as-is.
   *
   * @param value the amount to normalise (may be {@code null})
   * @return {@code value}, or {@link BigDecimal#ZERO} when it is {@code null}
   */
  public static BigDecimal nullSafeBigDecimal(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * Formats a timestamp as an ISO-8601 UTC instant.
   *
   * @param ts the timestamp to format (may be {@code null})
   * @return the ISO-8601 UTC string (e.g. {@code 2026-06-04T10:00:00Z}), or {@code ""} when {@code ts} is {@code null}
   */
  public static String formatDate(Timestamp ts) {
    if (ts == null) return "";
    return ISO_UTC.format(Instant.ofEpochMilli(ts.getTime()));
  }

  /**
   * Parses an ISO-8601 instant (e.g. {@code 2026-06-04T00:00:00Z}) sent by the frontend as UTC
   * midnight for a chosen calendar day (see {@code ManualStatementModal.jsx}'s {@code toIsoUtc}: it
   * deliberately picks UTC midnight so the calendar day survives regardless of the caller's
   * timezone). Returns midnight of that SAME calendar day in the server's own timezone, not the
   * verbatim instant: {@code statementdate}/{@code datetrx} are {@code timestamp without time zone}
   * columns, so persisting the raw UTC instant lets the server's offset shift the stored literal —
   * on a UTC-negative server (e.g. America/Argentina/Cordoba, UTC-3) {@code 2026-07-22T00:00:00Z}
   * would land as {@code 2026-07-21 21:00:00}, a day early, and every payment/transaction created
   * from that statement line (via {@code line.getTransactionDate()}) would inherit the wrong day.
   * Re-anchoring to the server zone here keeps the stored value consistent with other date-only
   * columns (e.g. {@code C_Invoice.dateinvoiced}), which are never round-tripped through a UTC ISO
   * string in the first place.
   *
   * @param iso      the ISO-8601 instant string to parse (may be {@code null}/blank)
   * @param fallback the value to return when {@code iso} is blank or unparseable
   * @return the parsed {@link Date}, or {@code fallback} on blank/invalid input
   */
  public static Date parseIsoDate(String iso, Date fallback) {
    if (StringUtils.isBlank(iso)) return fallback;
    try {
      LocalDate calendarDay = Instant.parse(iso).atZone(ZoneOffset.UTC).toLocalDate();
      return Date.from(calendarDay.atStartOfDay(ZoneId.systemDefault()).toInstant());
    } catch (Exception e) {
      return fallback;
    }
  }

  /**
   * Parses a plain decimal string into a non-null {@link BigDecimal}.
   *
   * @param raw the decimal string to parse (may be {@code null}/blank)
   * @return the parsed amount, or {@link BigDecimal#ZERO} on blank/invalid input
   */
  public static BigDecimal parseAmount(String raw) {
    if (StringUtils.isBlank(raw)) return BigDecimal.ZERO;
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  /**
   * Truncates {@code s} to at most {@code max} characters.
   *
   * @param s   the string to truncate
   * @param max the maximum number of characters to keep
   * @return {@code s} unchanged when shorter than {@code max}, otherwise its first {@code max} characters
   */
  public static String truncate(String s, int max) {
    return s.length() > max ? s.substring(0, max) : s;
  }

  /**
   * Collects the requested statement ids: the comma-separated {@code multi}
   * (used by the multi-statement CSV export) takes precedence, falling back to
   * the single {@code single} id used by the inline/detail line views.
   *
   * @param multi  comma-separated statement ids, or blank
   * @param single a single statement id, used when {@code multi} is blank
   * @return the parsed, trimmed ids (possibly empty, never {@code null})
   */
  public static List<String> parseStatementIds(String multi, String single) {
    List<String> ids = new ArrayList<>();
    if (StringUtils.isNotBlank(multi)) {
      for (String id : multi.split(",")) {
        if (StringUtils.isNotBlank(id)) {
          ids.add(id.trim());
        }
      }
    } else if (StringUtils.isNotBlank(single)) {
      ids.add(single.trim());
    }
    return ids;
  }

  /**
   * True when a manual statement line carries no meaningful data (no
   * description / counterparty / G-L item / reference and zero amounts).
   *
   * @param l the line JSON from the create/update request body
   * @return whether the line is effectively empty
   */
  public static boolean isBlankLine(JSONObject l) {
    return StringUtils.isBlank(l.optString(FIELD_DESCRIPTION, null))
        && StringUtils.isBlank(l.optString("bpartnerName", null))
        && StringUtils.isBlank(l.optString("bpartnerId", null))
        && StringUtils.isBlank(l.optString("glItemId", null))
        && StringUtils.isBlank(l.optString("reference", null))
        && parseAmount(l.optString("in", null)).signum() == 0
        && parseAmount(l.optString("out", null)).signum() == 0;
  }

  /**
   * Builds the {@code txns[]} array for a statement line from the joined
   * transaction columns of the lines query. The relationship is 1:1 today, so
   * the array has 0 or 1 element; the shape is kept array-based so a future 1:N
   * reconciliation only changes the query, not the contract.
   *
   * @param rs      the lines result set positioned on the current row
   * @param matched whether the line has a linked financial-account transaction
   * @return the transactions array (empty when {@code matched} is false)
   * @throws Exception if reading the result set or building the JSON fails
   */
  public static JSONArray buildLineTxns(ResultSet rs, boolean matched) throws Exception {
    JSONArray txns = new JSONArray();
    if (!matched) {
      return txns;
    }
    JSONObject t = new JSONObject();
    t.put("transactionId", StringUtils.trimToEmpty(rs.getString("fin_finacc_transaction_id")));
    t.put("documentNo", StringUtils.trimToEmpty(rs.getString("txn_documentno")));
    t.put("date", formatDate(rs.getTimestamp("txn_date")));
    t.put("contact", StringUtils.trimToEmpty(rs.getString("txn_contact")));
    t.put(FIELD_DESCRIPTION, StringUtils.trimToEmpty(rs.getString("txn_description")));
    t.put("trxType", StringUtils.trimToEmpty(rs.getString("txn_trxtype")));
    t.put("paymentStatus", StringUtils.trimToEmpty(rs.getString("txn_status")));
    t.put(FIELD_AMOUNT, nullSafeBigDecimal(rs.getBigDecimal("txn_amount")));
    t.put("paymentId", StringUtils.trimToEmpty(rs.getString("txn_payment_id")));
    t.put("paymentIsReceipt", StringUtils.trimToEmpty(rs.getString("txn_payment_isreceipt")));
    // Whether the reconcile flow auto-created this transaction's payment (invoice settlement) — the
    // per-item un-reconcile ("desvincular") uses it to decide whether removing it also reverses a
    // payment and restores the invoice to unpaid. See ETP-4502 iteration 5.
    t.put("autoCreated", "Y".equalsIgnoreCase(StringUtils.trimToEmpty(rs.getString("txn_auto_created"))));
    txns.put(t);
    return txns;
  }
}
