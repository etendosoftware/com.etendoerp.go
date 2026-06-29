/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.utility.FIN_MatchedTransaction;
import org.openbravo.advpaymentmngt.utility.FIN_MatchingTransaction;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Static helpers for the {@code autoMatch} and {@code applySuggestions} actions of
 * {@link ReconciliationHandler}. Extracted to keep the handler class under the Sonar
 * method-count threshold.
 */
final class AutoMatchSupport {

  private static final Logger log = LogManager.getLogger(AutoMatchSupport.class);

  private static final String KEY_ID = "id";
  private static final String KEY_DATE = "date";
  private static final String KEY_AMOUNT = "amount";
  private static final String KEY_IS_NEW = "isNew";
  private static final String KEY_GROUP_KEY = "groupKey";
  private static final String KEY_STATEMENT_LINE = "statementLine";
  private static final String KEY_OPERATIONS = "operations";
  private static final String KEY_ORIGIN = "origin";

  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  /** Caps the partner/reference subset search to keep the preview predictable and bounded. */
  private static final int MAX_SIGNAL_SUBSET_SIZE = 12;
  private static final BigDecimal SIGNAL_MATCH_TOLERANCE = new BigDecimal("0.01");
  static final int DEFAULT_DATE_TOL_DAYS = 3;

  private AutoMatchSupport() {
  }

  // ---------------------------------------------------------------------------
  // 1:N signal-based grouping (chosen approach: shared signal)
  // ---------------------------------------------------------------------------

  /**
   * Finds a 1:N group of unreconciled transactions that share a signal and whose signed amounts
   * sum to the bank-statement line amount within {@code tolerance}. The signal is tried in order:
   * business partner first, then payment reference. Within each signal block, the matcher first
   * accepts a whole-group exact sum; if that fails, it tries a bounded subset search inside that
   * same partner/reference block. This still avoids arbitrary cross-partner combinations while
   * covering common cases like two same-partner payments of 13.20 matching a 26.40 bank line.
   *
   * @return the matching transactions (size &gt;= 2), or an empty list when none qualifies
   */
  static List<FIN_FinaccTransaction> findSignalGroup(String accountId, FIN_BankStatementLine line,
      java.util.Set<String> usedTxnIds, BigDecimal tolerance) {
    return findSignalGroup(accountId, line, usedTxnIds, tolerance, DEFAULT_DATE_TOL_DAYS);
  }

  static List<FIN_FinaccTransaction> findSignalGroup(String accountId, FIN_BankStatementLine line,
      java.util.Set<String> usedTxnIds, BigDecimal tolerance, int dateTolDays) {
    BigDecimal target = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    if (target.signum() == 0) {
      return Collections.emptyList();
    }
    java.util.Date lineDate = line.getTransactionDate();
    List<FIN_FinaccTransaction> pool =
        loadUnreconciledSameSign(accountId, target, usedTxnIds, dateTolDays, lineDate);
    // Try grouping by business partner, then by payment reference.
    List<FIN_FinaccTransaction> byPartner =
        matchByKey(pool, target, tolerance, AutoMatchSupport::partnerKey);
    if (!byPartner.isEmpty()) {
      return byPartner;
    }
    return matchByKey(pool, target, tolerance, AutoMatchSupport::referenceKey);
  }

  private static List<FIN_FinaccTransaction> loadUnreconciledSameSign(String accountId,
      BigDecimal target, java.util.Set<String> usedTxnIds) {
    return loadUnreconciledSameSign(accountId, target, usedTxnIds, DEFAULT_DATE_TOL_DAYS, null);
  }

  private static List<FIN_FinaccTransaction> loadUnreconciledSameSign(String accountId,
      BigDecimal target, java.util.Set<String> usedTxnIds, int dateToleranceDays,
      java.util.Date lineDate) {
    String hql = "select ft from " + FIN_FinaccTransaction.ENTITY_NAME + " as ft"
        + " where ft.account.id = :acc"
        + "   and ft.reconciliation is null"
        + "   and ft.processed = true"
        + "   and ft.status <> 'RPPC'";
    List<FIN_FinaccTransaction> all = OBDal.getInstance().getSession()
        .createQuery(hql, FIN_FinaccTransaction.class)
        .setParameter("acc", accountId)
        .list();
    List<FIN_FinaccTransaction> pool = new ArrayList<>();
    for (FIN_FinaccTransaction t : all) {
      if (usedTxnIds.contains(t.getId())) {
        continue;
      }
      BigDecimal amt = nullSafe(t.getDepositAmount()).subtract(nullSafe(t.getPaymentAmount()));
      if (amt.signum() == target.signum()
          && withinDateWindow(lineDate, t.getTransactionDate(), dateToleranceDays)) {
        pool.add(t);
      }
    }
    return pool;
  }

  /** Returns true if the difference between {@code a} and {@code b} is within {@code days}. */
  static boolean withinDateWindow(java.util.Date a, java.util.Date b, int days) {
    if (a == null || b == null) {
      return true;
    }
    long diffMs = Math.abs(a.getTime() - b.getTime());
    return diffMs <= (long) days * 86_400_000L;
  }

  /**
   * Computes the effective amount tolerance as max(SIGNAL_MATCH_TOLERANCE, abs(target) * pct/100).
   * When {@code pct} is zero the floor tolerance is returned (preserving the current behaviour).
   */
  static BigDecimal computeAmountTolerance(BigDecimal target, BigDecimal pct) {
    if (pct == null || pct.signum() == 0) {
      return SIGNAL_MATCH_TOLERANCE;
    }
    BigDecimal derived = target.abs().multiply(pct)
        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    return derived.max(SIGNAL_MATCH_TOLERANCE);
  }

  /**
   * Partitions {@code pool} by the given signal key and returns the first partition with at least
   * two transactions whose signed amounts sum to {@code target} within {@code tolerance}. If the
   * full partition does not match, tries a bounded subset search inside that same partition.
   */
  static List<FIN_FinaccTransaction> matchByKey(List<FIN_FinaccTransaction> pool,
      BigDecimal target, BigDecimal tolerance, Function<FIN_FinaccTransaction, String> keyFn) {
    Map<String, List<FIN_FinaccTransaction>> groups = new LinkedHashMap<>();
    for (FIN_FinaccTransaction t : pool) {
      String key = keyFn.apply(t);
      if (StringUtils.isBlank(key)) {
        continue;
      }
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
    }
    for (List<FIN_FinaccTransaction> group : groups.values()) {
      if (group.size() < 2) {
        continue;
      }
      BigDecimal sum = BigDecimal.ZERO;
      for (FIN_FinaccTransaction t : group) {
        sum = sum.add(nullSafe(t.getDepositAmount()).subtract(nullSafe(t.getPaymentAmount())));
      }
      if (target.subtract(sum).abs().compareTo(tolerance) <= 0) {
        return group;
      }
      List<FIN_FinaccTransaction> subset = subsetMatch(group, target, tolerance);
      if (!subset.isEmpty()) {
        return subset;
      }
    }
    return Collections.emptyList();
  }

  /**
   * Finds a subset of {@code group} whose signed amounts sum to the target within tolerance.
   * Search is bounded so automatch stays fast and predictable on large partner/reference pools.
   */
  private static List<FIN_FinaccTransaction> subsetMatch(List<FIN_FinaccTransaction> group,
      BigDecimal target, BigDecimal tolerance) {
    if (group.size() < 2 || group.size() > MAX_SIGNAL_SUBSET_SIZE) {
      return Collections.emptyList();
    }
    BigDecimal targetAbs = target.abs();
    BigDecimal totalRemainingAbs = BigDecimal.ZERO;
    for (FIN_FinaccTransaction t : group) {
      totalRemainingAbs = totalRemainingAbs.add(txnSignedAmount(t).abs());
    }
    List<FIN_FinaccTransaction> picked = new ArrayList<>();
    if (subsetMatchDfs(group, 0, targetAbs, tolerance, BigDecimal.ZERO, totalRemainingAbs, picked)) {
      return new ArrayList<>(picked);
    }
    return Collections.emptyList();
  }

  /** Depth-first bounded subset search over one same-signal group. */
  private static boolean subsetMatchDfs(List<FIN_FinaccTransaction> group, int index,
      BigDecimal targetAbs, BigDecimal tolerance, BigDecimal pickedAbs, BigDecimal remainingAbs,
      List<FIN_FinaccTransaction> picked) {
    if (picked.size() >= 2 && targetAbs.subtract(pickedAbs).abs().compareTo(tolerance) <= 0) {
      return true;
    }
    if (index >= group.size()) {
      return false;
    }
    if (pickedAbs.compareTo(targetAbs.add(tolerance)) > 0) {
      return false;
    }
    if (pickedAbs.add(remainingAbs).compareTo(targetAbs.subtract(tolerance)) < 0) {
      return false;
    }

    FIN_FinaccTransaction current = group.get(index);
    BigDecimal amtAbs = txnSignedAmount(current).abs();
    BigDecimal nextRemainingAbs = remainingAbs.subtract(amtAbs);

    picked.add(current);
    if (subsetMatchDfs(group, index + 1, targetAbs, tolerance, pickedAbs.add(amtAbs),
        nextRemainingAbs, picked)) {
      return true;
    }
    picked.remove(picked.size() - 1);

    return subsetMatchDfs(group, index + 1, targetAbs, tolerance, pickedAbs,
        nextRemainingAbs, picked);
  }

  private static BigDecimal txnSignedAmount(FIN_FinaccTransaction t) {
    return nullSafe(t.getDepositAmount()).subtract(nullSafe(t.getPaymentAmount()));
  }

  private static String partnerKey(FIN_FinaccTransaction t) {
    if (t.getBusinessPartner() != null) {
      return "bp:" + t.getBusinessPartner().getId();
    }
    if (t.getFinPayment() != null && t.getFinPayment().getBusinessPartner() != null) {
      return "bp:" + t.getFinPayment().getBusinessPartner().getId();
    }
    return null;
  }

  private static String referenceKey(FIN_FinaccTransaction t) {
    if (t.getFinPayment() != null && StringUtils.isNotBlank(t.getFinPayment().getReferenceNo())) {
      return "ref:" + t.getFinPayment().getReferenceNo().trim();
    }
    return null;
  }

  /** Builds a 1:N group JSON from a bank-statement line and its matched transactions. */
  static JSONObject buildMultiGroup(FIN_BankStatementLine line, List<FIN_FinaccTransaction> txns)
      throws JSONException {
    JSONObject group = new JSONObject();
    StringBuilder key = new StringBuilder(line.getId());
    JSONArray ops = new JSONArray();
    BigDecimal opSum = BigDecimal.ZERO;
    for (FIN_FinaccTransaction t : txns) {
      key.append('-').append(t.getId());
      ops.put(txnToJson(t));
      opSum = opSum.add(nullSafe(t.getDepositAmount()).subtract(nullSafe(t.getPaymentAmount())));
    }
    group.put(KEY_GROUP_KEY, key.toString());
    group.put(KEY_STATEMENT_LINE, lineToJson(line));
    group.put(KEY_OPERATIONS, ops);
    group.put(KEY_ORIGIN, "standard");
    BigDecimal lineAmt = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    group.put(STATE_DIFFERENCE, lineAmt.subtract(opSum));
    group.put(KEY_IS_NEW, false);
    return group;
  }

  // ---------------------------------------------------------------------------
  // Group builders
  // ---------------------------------------------------------------------------

  static JSONObject buildStandardGroup(FIN_BankStatementLine line,
      FIN_FinaccTransaction txn, String matchLevel) throws JSONException {
    JSONObject group = new JSONObject();
    group.put(KEY_GROUP_KEY, line.getId() + "-" + txn.getId());
    group.put(KEY_STATEMENT_LINE, lineToJson(line));
    JSONArray ops = new JSONArray();
    ops.put(txnToJson(txn));
    group.put(KEY_OPERATIONS, ops);
    group.put(KEY_ORIGIN, "standard");
    group.put("matchLevel", StringUtils.defaultIfBlank(matchLevel, ""));
    BigDecimal lineAmt = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    BigDecimal opAmt = nullSafe(txn.getDepositAmount()).subtract(nullSafe(txn.getPaymentAmount()));
    group.put(STATE_DIFFERENCE, lineAmt.subtract(opAmt));
    group.put(KEY_IS_NEW, false);
    return group;
  }

  static JSONObject buildRuleGroup(FIN_BankStatementLine line,
      MatchRuleEngine.Rule rule, List<MatchRuleEngine.Rule> alternatives) throws JSONException {
    JSONObject group = new JSONObject();
    group.put(KEY_GROUP_KEY, line.getId() + "-rule-" + rule.id);
    group.put(KEY_STATEMENT_LINE, lineToJson(line));
    boolean isNew = StringUtils.isNotBlank(rule.glItemId);
    group.put(KEY_IS_NEW, isNew);
    group.put(KEY_ORIGIN, "rule");
    group.put("ruleName", rule.name);
    BigDecimal lineAmt = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    group.put(STATE_DIFFERENCE, BigDecimal.ZERO);

    JSONArray ops = new JSONArray();
    if (isNew) {
      JSONObject proposedOp = new JSONObject();
      proposedOp.put(KEY_ID, "new");
      proposedOp.put("glItemId", StringUtils.defaultIfBlank(rule.glItemId, ""));
      proposedOp.put("bpartnerId", StringUtils.defaultIfBlank(rule.bpartnerId, ""));
      proposedOp.put(KEY_AMOUNT, lineAmt);
      proposedOp.put(KEY_IS_NEW, true);
      ops.put(proposedOp);
    }
    group.put(KEY_OPERATIONS, ops);

    JSONArray altArray = new JSONArray();
    for (MatchRuleEngine.Rule alt : alternatives) {
      JSONObject altJson = new JSONObject();
      altJson.put(KEY_ID, alt.id);
      altJson.put("name", alt.name);
      altJson.put("priority", alt.priority);
      altArray.put(altJson);
    }
    group.put("alternatives", altArray);

    if (isNew) {
      JSONObject cp = new JSONObject();
      cp.put("ruleId", rule.id);
      cp.put("glItemId", StringUtils.defaultIfBlank(rule.glItemId, ""));
      cp.put("bpartnerId", StringUtils.defaultIfBlank(rule.bpartnerId, ""));
      cp.put("transactionTypeId", StringUtils.defaultIfBlank(rule.transactionTypeId, ""));
      cp.put(KEY_AMOUNT, lineAmt);
      group.put("createPayment", cp);
    }
    return group;
  }

  static JSONObject lineToJson(FIN_BankStatementLine line) throws JSONException {
    JSONObject j = new JSONObject();
    j.put(KEY_ID, line.getId());
    j.put(KEY_DATE, formatDate(line.getTransactionDate() != null
        ? new Timestamp(line.getTransactionDate().getTime()) : null));
    j.put("description", StringUtils.trimToEmpty(line.getDescription()));
    j.put("referenceNo", StringUtils.trimToEmpty(line.getReferenceNo()));
    j.put(KEY_AMOUNT, nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount())));
    return j;
  }

  static JSONObject txnToJson(FIN_FinaccTransaction txn) throws JSONException {
    JSONObject j = new JSONObject();
    j.put(KEY_ID, txn.getId());
    j.put(KEY_DATE, formatDate(txn.getTransactionDate() != null
        ? new Timestamp(txn.getTransactionDate().getTime()) : null));
    j.put("documentNo",
        txn.getFinPayment() != null ? StringUtils.trimToEmpty(txn.getFinPayment().getDocumentNo()) : "");
    j.put(KEY_AMOUNT, nullSafe(txn.getDepositAmount()).subtract(nullSafe(txn.getPaymentAmount())));
    j.put(KEY_IS_NEW, false);
    return j;
  }

  // ---------------------------------------------------------------------------
  // Line classification (left-panel state + counts)
  // ---------------------------------------------------------------------------

  static final String STATE_SUGGESTED = "suggested";
  static final String STATE_BY_RULE = "byRule";
  static final String STATE_DIFFERENCE = "difference";
  static final String STATE_PENDING = "pending";

  /**
   * Classifies a pending bank-statement line as if the matching engine had run:
   * <ul>
   *   <li>{@code suggested} — the standard algorithm returns a STRONG match</li>
   *   <li>{@code difference} — the standard algorithm returns a weaker match (a suggestion that
   *       is not a perfect/strong match)</li>
   *   <li>{@code byRule} — no standard match, but a matching rule applies</li>
   *   <li>{@code pending} — nothing matches</li>
   * </ul>
   */
  /** Zeroed per-state counters for the left-panel filter (insertion-ordered). */
  static Map<String, Integer> newCounts() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("all", 0);
    counts.put(STATE_PENDING, 0);
    counts.put(STATE_SUGGESTED, 0);
    counts.put(STATE_BY_RULE, 0);
    counts.put(STATE_DIFFERENCE, 0);
    counts.put("reconciled", 0);
    return counts;
  }

  /** Loads the line by id and classifies it; returns {@code pending} when the line is gone. */
  static String classifyPendingLine(FIN_FinancialAccount account, String lineId,
      List<MatchRuleEngine.Rule> rules) {
    FIN_BankStatementLine line = OBDal.getInstance().get(FIN_BankStatementLine.class, lineId);
    if (line == null) {
      return STATE_PENDING;
    }
    return classifyPendingLine(account, line, rules);
  }

  static String classifyPendingLine(FIN_FinancialAccount account, String lineId,
      List<MatchRuleEngine.Rule> rules, int dateTolDays, BigDecimal amtTolPct) {
    FIN_BankStatementLine line = OBDal.getInstance().get(FIN_BankStatementLine.class, lineId);
    if (line == null) {
      return STATE_PENDING;
    }
    return classifyPendingLine(account, line, rules, dateTolDays, amtTolPct);
  }

  static String classifyPendingLine(FIN_FinancialAccount account, FIN_BankStatementLine line,
      List<MatchRuleEngine.Rule> rules) {
    return classifyPendingLine(account, line, rules, DEFAULT_DATE_TOL_DAYS, BigDecimal.ZERO);
  }

  static String classifyPendingLine(FIN_FinancialAccount account, FIN_BankStatementLine line,
      List<MatchRuleEngine.Rule> rules, int dateTolDays, BigDecimal amtTolPct) {
    String level = standardMatchLevel(account, line, dateTolDays);
    if (FIN_MatchedTransaction.STRONG.equals(level)) {
      return STATE_SUGGESTED;
    }
    if (account != null && StringUtils.isNotBlank(account.getId())) {
      BigDecimal target = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
      BigDecimal amtTol = computeAmountTolerance(target, amtTolPct);
      if (!findSignalGroup(account.getId(), line, new HashSet<>(), amtTol, dateTolDays).isEmpty()) {
        return STATE_SUGGESTED;
      }
    }
    if (level != null) {
      return STATE_DIFFERENCE;
    }
    String desc = StringUtils.trimToEmpty(line.getDescription());
    String ref = StringUtils.trimToEmpty(line.getReferenceNo());
    String partner = StringUtils.trimToEmpty(line.getBpartnername());
    if (MatchRuleEngine.evaluate(desc, ref, partner, rules).isMatched()) {
      return STATE_BY_RULE;
    }
    return STATE_PENDING;
  }

  /** Match level the account's standard algorithm assigns to the line, or {@code null} if none. */
  static String standardMatchLevel(FIN_FinancialAccount account, FIN_BankStatementLine line) {
    return standardMatchLevel(account, line, DEFAULT_DATE_TOL_DAYS);
  }

  static String standardMatchLevel(FIN_FinancialAccount account, FIN_BankStatementLine line,
      int dateTolDays) {
    if (account == null || account.getMatchingAlgorithm() == null
        || StringUtils.isBlank(account.getMatchingAlgorithm().getJavaClassName())) {
      return null;
    }
    try {
      FIN_MatchingTransaction matcher =
          new FIN_MatchingTransaction(account.getMatchingAlgorithm().getJavaClassName());
      FIN_MatchedTransaction matched = matcher.match(line, new ArrayList<>());
      if (matched != null && matched.getTransaction() != null
          && !FIN_MatchedTransaction.NOMATCH.equals(matched.getMatchLevel())) {
        if (!withinDateWindow(line.getTransactionDate(),
            matched.getTransaction().getTransactionDate(), dateTolDays)) {
          return null;
        }
        return matched.getMatchLevel();
      }
    } catch (Exception e) {
      log.debug("Standard match level failed for line {}: {}", line.getId(), e.getMessage());
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Transaction helpers
  // ---------------------------------------------------------------------------

  /** Next line number for a new transaction of the account (max + 10). */
  static long nextTransactionLineNo(String accountId) {
    String sql = "SELECT COALESCE(MAX(line), 0) + 10 FROM fin_finacc_transaction"
        + " WHERE fin_financial_account_id = ?"; // NOSONAR java:S2077
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 10L;
      }
    } catch (Exception e) {
      log.warn("Could not compute next transaction line for account {}", accountId, e);
      return 10L;
    }
  }

  /** Increments {@code ETGO_MATCH_RULE.matchcount} for the given rule id. Best-effort (non-fatal). */
  static void incrementMatchCount(String ruleId) {
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE etgo_match_rule SET matchcount = matchcount + 1 WHERE etgo_match_rule_id = ?")) { // NOSONAR java:S2077
        ps.setString(1, ruleId);
        ps.executeUpdate();
      }
    } catch (Exception e) {
      log.warn("Could not increment matchCount for rule {}", ruleId, e);
    }
  }

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  private static String formatDate(Timestamp ts) {
    return ts == null ? "" : ISO_UTC.format(Instant.ofEpochMilli(ts.getTime()));
  }

  static BigDecimal nullSafe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * Passes 1b (1:N signal grouping) and 2 (rule engine) of the autoMatch preview — evaluated only
   * when the standard 1:1 algorithm did not match. Appends any group it finds to {@code groups} and
   * marks the consumed transactions in {@code usedTxnIds}.
   *
   * @return int[2] where [0] = opsToLink increment, [1] = willCreate increment
   */
  static int[] matchFallback(String accountId, FIN_BankStatementLine line,
      Set<String> usedTxnIds, List<MatchRuleEngine.Rule> rules, JSONArray groups)
      throws JSONException {
    return matchFallback(accountId, line, usedTxnIds, rules, groups,
        DEFAULT_DATE_TOL_DAYS, BigDecimal.ZERO);
  }

  static int[] matchFallback(String accountId, FIN_BankStatementLine line,
      Set<String> usedTxnIds, List<MatchRuleEngine.Rule> rules, JSONArray groups,
      int dateTolDays, BigDecimal amtTolPct) throws JSONException {
    BigDecimal target = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    BigDecimal amtTol = computeAmountTolerance(target, amtTolPct);
    List<FIN_FinaccTransaction> signalGroup =
        findSignalGroup(accountId, line, usedTxnIds, amtTol, dateTolDays);
    if (!signalGroup.isEmpty()) {
      signalGroup.forEach(t -> usedTxnIds.add(t.getId()));
      groups.put(buildMultiGroup(line, signalGroup));
      return new int[]{signalGroup.size(), 0};
    }
    MatchRuleEngine.MatchResult ruleResult = MatchRuleEngine.evaluate(
        StringUtils.trimToEmpty(line.getDescription()),
        StringUtils.trimToEmpty(line.getReferenceNo()),
        StringUtils.trimToEmpty(line.getBpartnername()), rules);
    if (ruleResult.isMatched()) {
      JSONObject ruleGroup = buildRuleGroup(
          line, ruleResult.primary, ruleResult.alternatives);
      groups.put(ruleGroup);
      return Boolean.TRUE.equals(ruleGroup.opt(KEY_IS_NEW))
          ? new int[]{0, 1} : new int[]{1, 0};
    }
    return new int[]{0, 0};
  }
}
