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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.advpaymentmngt.utility.FIN_MatchedTransaction;
import org.openbravo.advpaymentmngt.utility.FIN_MatchingTransaction;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

/**
 * Unit tests for {@link AutoMatchSupport} — covers {@link AutoMatchSupport#matchByKey} (the 1:N
 * signal-grouping core), {@link AutoMatchSupport#classifyPendingLine} (state classification for
 * the left-panel filter), and {@link BankStatementsSupport#mergeMatchGroups} (sub-line collapsing
 * for the statement-lines panel).
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class AutoMatchSupportTest {

  private static final BigDecimal TOL = new BigDecimal("0.01");

  /** Builds a mock transaction with a deposit-minus-payment net amount and a signal key. */
  private static FIN_FinaccTransaction txn(String id, String amount, String key) {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    lenient().when(t.getId()).thenReturn(id);
    BigDecimal amt = new BigDecimal(amount);
    if (amt.signum() >= 0) {
      lenient().when(t.getDepositAmount()).thenReturn(amt);
      lenient().when(t.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    } else {
      lenient().when(t.getDepositAmount()).thenReturn(BigDecimal.ZERO);
      lenient().when(t.getPaymentAmount()).thenReturn(amt.abs());
    }
    KEYS.put(t, key);
    return t;
  }

  private static final java.util.Map<FIN_FinaccTransaction, String> KEYS = new java.util.HashMap<>();
  private static final Function<FIN_FinaccTransaction, String> KEY_FN = KEYS::get;

  /** A partner group whose members sum to the target amount is returned in full. */
  @Test
  public void testMatchByKeyFullPartnerGroupSumsReturnsGroup() {
    FIN_FinaccTransaction a = txn("a", "100.00", "bp:1");
    FIN_FinaccTransaction b = txn("b", "50.00", "bp:1");
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("150.00"), TOL, KEY_FN);

    assertEquals(2, result.size());
    assertTrue(result.contains(a));
    assertTrue(result.contains(b));
  }

  /** A partner group whose members do not sum to the target amount yields no match. */
  @Test
  public void testMatchByKeyGroupSumDoesNotMatchReturnsEmpty() {
    FIN_FinaccTransaction a = txn("a", "100.00", "bp:1");
    FIN_FinaccTransaction b = txn("b", "30.00", "bp:1");
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("150.00"), TOL, KEY_FN);

    assertTrue(result.isEmpty());
  }

  /**
   * When the full same-key partition over-shoots the target, matchByKey may still return an exact
   * subset from that same partition.
   */
  @Test
  public void testMatchByKeyFindsExactSubsetInsidePartition() {
    FIN_FinaccTransaction a = txn("a", "95.59", "bp:1");
    FIN_FinaccTransaction b = txn("b", "13.20", "bp:1");
    FIN_FinaccTransaction c = txn("c", "13.20", "bp:1");
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b, c);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("26.40"), TOL, KEY_FN);

    assertEquals(2, result.size());
    assertTrue(result.contains(b));
    assertTrue(result.contains(c));
  }

  /** A single-member group is a 1:1 case and is ignored even when it matches the amount. */
  @Test
  public void testMatchByKeySingletonGroupIgnored() {
    // A single transaction is a 1:1 case, not a 1:N group — must be ignored even if it matches.
    FIN_FinaccTransaction a = txn("a", "150.00", "bp:1");
    List<FIN_FinaccTransaction> pool = Arrays.asList(a);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("150.00"), TOL, KEY_FN);

    assertTrue(result.isEmpty());
  }

  /** Among several partner partitions, only the one that sums to the target is returned. */
  @Test
  public void testMatchByKeyPicksTheMatchingPartitionAmongSeveral() {
    FIN_FinaccTransaction a = txn("a", "100.00", "bp:1");   // bp:1 sums 100, no match alone
    FIN_FinaccTransaction b = txn("b", "70.00", "bp:2");
    FIN_FinaccTransaction c = txn("c", "30.00", "bp:2");    // bp:2 sums 100 → match
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b, c);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("100.00"), TOL, KEY_FN);

    assertEquals(2, result.size());
    assertTrue(result.contains(b));
    assertTrue(result.contains(c));
  }

  /** A group sum within the tolerance band is treated as a match. */
  @Test
  public void testMatchByKeyWithinTolerance() {
    FIN_FinaccTransaction a = txn("a", "100.00", "bp:1");
    FIN_FinaccTransaction b = txn("b", "50.005", "bp:1");
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("150.00"), TOL, KEY_FN);

    assertEquals(2, result.size());
  }

  /** Transactions with a blank/null signal key are skipped (never grouped). */
  @Test
  public void testMatchByKeyBlankKeySkipped() {
    FIN_FinaccTransaction a = txn("a", "100.00", null);
    FIN_FinaccTransaction b = txn("b", "50.00", null);
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("150.00"), TOL, KEY_FN);

    assertTrue(result.isEmpty());
  }

  // ---------------------------------------------------------------------------
  // classifyPendingLine
  // ---------------------------------------------------------------------------

  /** A null matching algorithm on the account → standardMatchLevel returns null → check rules. */
  @Test
  public void testClassifyPendingLineNoAlgorithmNoRuleMatchReturnsPending() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getMatchingAlgorithm()).thenReturn(null);
    FIN_BankStatementLine line = pendingLine("Bank fee May", "", "");

    String state = AutoMatchSupport.classifyPendingLine(account, line, Collections.emptyList());

    assertEquals(AutoMatchSupport.STATE_PENDING, state);
  }

  /**
   * Account has no algorithm configured (null) and a matching rule exists: the engine returns a
   * rule match → state must be {@code byRule}.
   */
  @Test
  public void testClassifyPendingLineNoAlgorithmRuleMatchesReturnsByRule() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getMatchingAlgorithm()).thenReturn(null);
    FIN_BankStatementLine line = pendingLine("Bank commission fee", "", "");
    List<MatchRuleEngine.Rule> rules = Collections.singletonList(
        new MatchRuleEngine.Rule("R1", "Fee Rule", 10,
            MatchRuleEngine.COND_CONTAINS, "commission",
            new MatchRuleEngine.RuleOptions("GL-001", "BP-001", null, null, null, null), 0L));

    String state = AutoMatchSupport.classifyPendingLine(account, line, rules);

    assertEquals(AutoMatchSupport.STATE_BY_RULE, state);
  }

  /**
   * Standard algorithm returns a STRONG match → state must be {@code suggested}.
   */
  @Test
  public void testClassifyPendingLineStandardAlgorithmStrongMatchReturnsSuggested() {
    FIN_FinancialAccount account = accountWithAlgorithm("com.example.DummyAlgo");
    FIN_BankStatementLine line = pendingLine("Transfer ACME", "", "");

    FIN_MatchedTransaction matched = mock(FIN_MatchedTransaction.class);
    // Use a non-null mock transaction so the null guard passes.
    when(matched.getTransaction()).thenReturn(mock(FIN_FinaccTransaction.class));
    when(matched.getMatchLevel()).thenReturn(FIN_MatchedTransaction.STRONG);

    try (MockedConstruction<FIN_MatchingTransaction> mc =
        mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
            when(m.match(line, new java.util.ArrayList<>())).thenReturn(matched))) {
      String state = AutoMatchSupport.classifyPendingLine(
          account, line, Collections.emptyList());
      assertEquals(AutoMatchSupport.STATE_SUGGESTED, state);
    }
  }

  /**
   * Even without a 1:1 strong match, a line is classified as suggested when the 1:N signal matcher
   * finds an exact same-partner subset for it.
   */
  @Test
  public void testClassifyPendingLineSignalGroupReturnsSuggested() {
    FIN_FinancialAccount account = accountWithAlgorithm("com.example.DummyAlgo");
    when(account.getId()).thenReturn("ACC-1");
    FIN_BankStatementLine line = bslLine("L1", "26.40", "0.00");

    FIN_MatchedTransaction matched = mock(FIN_MatchedTransaction.class);
    when(matched.getTransaction()).thenReturn(mock(FIN_FinaccTransaction.class));
    when(matched.getMatchLevel()).thenReturn(FIN_MatchedTransaction.NOMATCH);

    BusinessPartner bp = mock(BusinessPartner.class);
    lenient().when(bp.getId()).thenReturn("BP-1");
    FIN_FinaccTransaction t1 = txnWithPartner("T1", "95.59", bp);
    FIN_FinaccTransaction t2 = txnWithPartner("T2", "13.20", bp);
    FIN_FinaccTransaction t3 = txnWithPartner("T3", "13.20", bp);

    try (MockedConstruction<FIN_MatchingTransaction> mc =
            mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
                when(m.match(line, new java.util.ArrayList<>())).thenReturn(matched));
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);
      @SuppressWarnings("unchecked")
      org.hibernate.query.Query<FIN_FinaccTransaction> query = mock(org.hibernate.query.Query.class);
      when(session.createQuery(anyString(), eq(FIN_FinaccTransaction.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(Arrays.asList(t1, t2, t3));

      String state = AutoMatchSupport.classifyPendingLine(account, line, Collections.emptyList());

      assertEquals(AutoMatchSupport.STATE_SUGGESTED, state);
    }
  }

  /**
   * Standard algorithm returns a non-STRONG, non-NOMATCH level → state must be {@code difference}.
   */
  @Test
  public void testClassifyPendingLineStandardAlgorithmWeakMatchReturnsDifference() {
    FIN_FinancialAccount account = accountWithAlgorithm("com.example.DummyAlgo");
    FIN_BankStatementLine line = pendingLine("Transfer ACME", "", "");

    FIN_MatchedTransaction matched = mock(FIN_MatchedTransaction.class);
    when(matched.getTransaction()).thenReturn(mock(FIN_FinaccTransaction.class));
    // Any level that is not STRONG and not NOMATCH → difference path.
    when(matched.getMatchLevel()).thenReturn("WEAK");

    try (MockedConstruction<FIN_MatchingTransaction> mc =
        mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
            when(m.match(line, new java.util.ArrayList<>())).thenReturn(matched))) {
      String state = AutoMatchSupport.classifyPendingLine(
          account, line, Collections.emptyList());
      assertEquals(AutoMatchSupport.STATE_DIFFERENCE, state);
    }
  }

  /**
   * Standard algorithm finds no match (NOMATCH level) and no rule applies → {@code pending}.
   */
  @Test
  public void testClassifyPendingLineNoStandardMatchNoRuleReturnsPending() {
    FIN_FinancialAccount account = accountWithAlgorithm("com.example.DummyAlgo");
    FIN_BankStatementLine line = pendingLine("Unknown txn", "", "");

    FIN_MatchedTransaction matched = mock(FIN_MatchedTransaction.class);
    when(matched.getTransaction()).thenReturn(mock(FIN_FinaccTransaction.class));
    when(matched.getMatchLevel()).thenReturn(FIN_MatchedTransaction.NOMATCH);

    try (MockedConstruction<FIN_MatchingTransaction> mc =
        mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
            when(m.match(line, new java.util.ArrayList<>())).thenReturn(matched))) {
      String state = AutoMatchSupport.classifyPendingLine(
          account, line, Collections.emptyList());
      assertEquals(AutoMatchSupport.STATE_PENDING, state);
    }
  }

  // ---------------------------------------------------------------------------
  // mergeMatchGroups (BankStatementsSupport)
  // ---------------------------------------------------------------------------

  /**
   * Lines with a blank or missing matchGroupId pass through unchanged.
   *
   * @throws Exception if building the JSON line objects fails
   */
  @Test
  public void testMergeMatchGroupsBlankGroupIdPassThrough() throws Exception {
    JSONObject l1 = line("L1", "", "100.00", "100.00", "0.00", true);
    JSONObject l2 = line("L2", "", "50.00", "0.00", "50.00", true);
    JSONArray input = new JSONArray();
    input.put(l1);
    input.put(l2);

    JSONArray result = BankStatementsSupport.mergeMatchGroups(input);

    assertEquals(2, result.length());
    assertEquals("L1", result.getJSONObject(0).getString("id"));
    assertEquals("L2", result.getJSONObject(1).getString("id"));
  }

  /**
   * Two sub-lines sharing the same matchGroupId: the second is absorbed into the first.
   * The result has one entry whose {@code txns} contains both transactions and whose
   * {@code in}/{@code out}/{@code amount} are summed.
   *
   * @throws Exception if building the JSON line objects fails
   */
  @Test
  public void testMergeMatchGroupsTwoSubLinesMergeIntoOne() throws Exception {
    JSONObject txnA = new JSONObject().put("id", "T1").put("amount", "100.00");
    JSONObject txnB = new JSONObject().put("id", "T2").put("amount", "50.00");

    JSONObject l1 = line("L1", "GRP-1", "100.00", "100.00", "0.00", true);
    l1.put("txns", new JSONArray().put(txnA));

    JSONObject l2 = line("L2", "GRP-1", "50.00", "50.00", "0.00", true);
    l2.put("txns", new JSONArray().put(txnB));

    JSONArray input = new JSONArray();
    input.put(l1);
    input.put(l2);

    JSONArray result = BankStatementsSupport.mergeMatchGroups(input);

    assertEquals(1, result.length());
    JSONObject merged = result.getJSONObject(0);
    // The head line is l1; the amounts are the sum of both sub-lines.
    assertEquals(0, new BigDecimal("150.00").compareTo(new BigDecimal(merged.getString("in"))));
    assertEquals(0, new BigDecimal("150.00").compareTo(new BigDecimal(merged.getString("amount"))));
    // Both transactions are present in the merged txns array.
    JSONArray txns = merged.getJSONArray("txns");
    assertEquals(2, txns.length());
    // Both sub-lines were fully matched (no pending remainder) → group is RECONCILED.
    assertEquals("RECONCILED", merged.getString("reconcileStatus"));
    assertTrue(merged.getBoolean("matched"));
  }

  /**
   * Three sub-lines sharing the same matchGroupId: all three collapse into a single merged line
   * with three entries in {@code txns} and the summed amounts.
   *
   * @throws Exception if building the JSON line objects fails
   */
  @Test
  public void testMergeMatchGroupsThreeSubLinesAllMerge() throws Exception {
    JSONObject l1 = line("L1", "GRP-X", "40.00", "40.00", "0.00", true);
    l1.put("txns", new JSONArray().put(new JSONObject().put("id", "T1")));
    JSONObject l2 = line("L2", "GRP-X", "30.00", "30.00", "0.00", true);
    l2.put("txns", new JSONArray().put(new JSONObject().put("id", "T2")));
    JSONObject l3 = line("L3", "GRP-X", "30.00", "30.00", "0.00", true);
    l3.put("txns", new JSONArray().put(new JSONObject().put("id", "T3")));

    JSONArray input = new JSONArray();
    input.put(l1);
    input.put(l2);
    input.put(l3);

    JSONArray result = BankStatementsSupport.mergeMatchGroups(input);

    assertEquals(1, result.length());
    JSONObject merged = result.getJSONObject(0);
    assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(merged.getString("in"))));
    assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(merged.getString("amount"))));
    assertEquals(3, merged.getJSONArray("txns").length());
    assertTrue(merged.getBoolean("matched"));
    assertEquals("RECONCILED", merged.getString("reconcileStatus"));
  }

  /**
   * Regression for the "Ejemplo 100" bug (ETP-4502 iteration 4): a single-operation PARTIAL
   * match splits the line into a matched sub-line (53.24, has a txn) and a pending remainder
   * sub-line (46.76, no txn) sharing the same matchGroupId. The merged display row must show the
   * original 100.00 total, {@code reconcileStatus: "PARTIAL"}, {@code matched: false}, the 46.76
   * pendingAmount, and only the ONE real transaction (not a phantom second one).
   *
   * @throws Exception if building the JSON line objects fails
   */
  @Test
  public void testMergeMatchGroupsPartialMatchYieldsPartialStatusAndPendingAmount()
      throws Exception {
    JSONObject txnA = new JSONObject().put("id", "T1").put("amount", "53.24");

    JSONObject matchedSub = line("L1", "GRP-PARTIAL", "53.24", "53.24", "0.00", true);
    matchedSub.put("txns", new JSONArray().put(txnA));

    // Pending remainder: not matched, no txns (default empty array from the helper), and its own
    // pendingAmount equals its own amount.
    JSONObject pendingSub = line("L2", "GRP-PARTIAL", "46.76", "46.76", "0.00", false);

    JSONArray input = new JSONArray();
    input.put(matchedSub);
    input.put(pendingSub);

    JSONArray result = BankStatementsSupport.mergeMatchGroups(input);

    assertEquals(1, result.length());
    JSONObject merged = result.getJSONObject(0);
    assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(merged.getString("in"))));
    assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(merged.getString("amount"))));
    assertEquals("PARTIAL", merged.getString("reconcileStatus"));
    assertFalse(merged.getBoolean("matched"));
    assertEquals(0,
        new BigDecimal("46.76").compareTo(new BigDecimal(merged.getString("pendingAmount"))));
    JSONArray txns = merged.getJSONArray("txns");
    assertEquals(1, txns.length());
    assertEquals("T1", txns.getJSONObject(0).getString("id"));
  }

  /**
   * When neither sub-line in a match group is matched (no txns at all), the merged group stays
   * PENDING — the defensive branch of the new status-derivation logic.
   *
   * @throws Exception if building the JSON line objects fails
   */
  @Test
  public void testMergeMatchGroupsNeitherSubLineMatchedYieldsPendingStatus() throws Exception {
    JSONObject l1 = line("L1", "GRP-NONE", "60.00", "60.00", "0.00", false);
    JSONObject l2 = line("L2", "GRP-NONE", "40.00", "40.00", "0.00", false);

    JSONArray input = new JSONArray();
    input.put(l1);
    input.put(l2);

    JSONArray result = BankStatementsSupport.mergeMatchGroups(input);

    assertEquals(1, result.length());
    JSONObject merged = result.getJSONObject(0);
    assertEquals("PENDING", merged.getString("reconcileStatus"));
    assertFalse(merged.getBoolean("matched"));
  }

  // ---------------------------------------------------------------------------
  // Private helpers for classifyPendingLine tests
  // ---------------------------------------------------------------------------

  private static FIN_BankStatementLine pendingLine(String description, String referenceNo,
      String bpartnerName) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    lenient().when(line.getId()).thenReturn("L-TEST");
    lenient().when(line.getDescription()).thenReturn(description);
    lenient().when(line.getReferenceNo()).thenReturn(referenceNo);
    lenient().when(line.getBpartnername()).thenReturn(bpartnerName);
    lenient().when(line.getCramount()).thenReturn(BigDecimal.ZERO);
    lenient().when(line.getDramount()).thenReturn(BigDecimal.ZERO);
    return line;
  }

  private static FIN_FinancialAccount accountWithAlgorithm(String javaClassName) {
    org.openbravo.model.financialmgmt.payment.MatchingAlgorithm algo =
        mock(org.openbravo.model.financialmgmt.payment.MatchingAlgorithm.class);
    lenient().when(algo.getJavaClassName()).thenReturn(javaClassName);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    lenient().when(account.getMatchingAlgorithm()).thenReturn(algo);
    return account;
  }

  /**
   * Builds a minimal statement-line JSONObject for mergeMatchGroups tests, mirroring the real
   * per-row contract produced by {@link BankStatementsSupport#mapLineRow}: a matched row has
   * {@code pendingAmount: 0} and {@code reconcileStatus: "RECONCILED"}; an unmatched row's
   * {@code pendingAmount} equals its own {@code amount} and {@code reconcileStatus: "PENDING"}.
   * Callers add a {@code txns} entry afterwards for matched sub-lines; unmatched sub-lines keep
   * the default empty {@code txns} array.
   */
  private static JSONObject line(String id, String groupId, String amount, String in, String out,
      boolean matched) throws Exception {
    JSONObject o = new JSONObject();
    o.put("id", id);
    o.put("matchGroupId", groupId);
    o.put("amount", amount);
    o.put("in", in);
    o.put("out", out);
    o.put("matched", matched);
    o.put("reconcileStatus", matched ? "RECONCILED" : "PENDING");
    o.put("pendingAmount", matched ? "0.00" : amount);
    o.put("txns", new JSONArray());
    return o;
  }

  // ---------------------------------------------------------------------------
  // nullSafe
  // ---------------------------------------------------------------------------

  /** nullSafe maps a null amount to zero and preserves a present value. */
  @Test
  public void testNullSafe() {
    assertEquals(0, AutoMatchSupport.nullSafe(null).compareTo(BigDecimal.ZERO));
    assertEquals(0, AutoMatchSupport.nullSafe(new BigDecimal("7.5")).compareTo(new BigDecimal("7.5")));
  }

  // ---------------------------------------------------------------------------
  // newCounts
  // ---------------------------------------------------------------------------

  /** newCounts returns all expected zeroed buckets in insertion order. */
  @Test
  public void testNewCountsAllZeroed() {
    java.util.Map<String, Integer> counts = AutoMatchSupport.newCounts();
    assertEquals(Integer.valueOf(0), counts.get("all"));
    assertEquals(Integer.valueOf(0), counts.get(AutoMatchSupport.STATE_PENDING));
    assertEquals(Integer.valueOf(0), counts.get(AutoMatchSupport.STATE_SUGGESTED));
    assertEquals(Integer.valueOf(0), counts.get(AutoMatchSupport.STATE_BY_RULE));
    assertEquals(Integer.valueOf(0), counts.get(AutoMatchSupport.STATE_DIFFERENCE));
    assertEquals(Integer.valueOf(0), counts.get("reconciled"));
  }

  // ---------------------------------------------------------------------------
  // lineToJson / txnToJson (pure JSON builders)
  // ---------------------------------------------------------------------------

  /** lineToJson serializes id, trimmed text fields, a signed amount, and a formatted UTC date. */
  @Test
  public void testLineToJsonSerializesFields() throws Exception {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn("L1");
    when(line.getDescription()).thenReturn("  Bank fee  ");
    when(line.getReferenceNo()).thenReturn(" REF-1 ");
    when(line.getCramount()).thenReturn(new BigDecimal("100.00"));
    when(line.getDramount()).thenReturn(new BigDecimal("0.00"));
    when(line.getTransactionDate()).thenReturn(new Date(0L));

    JSONObject json = AutoMatchSupport.lineToJson(line);

    assertEquals("L1", json.getString("id"));
    assertEquals("Bank fee", json.getString("description"));
    assertEquals("REF-1", json.getString("referenceNo"));
    assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(json.getString("amount"))));
    assertEquals("1970-01-01T00:00:00Z", json.getString("date"));
  }

  /** lineToJson with a null transaction date produces an empty date string. */
  @Test
  public void testLineToJsonNullDateProducesEmptyString() throws Exception {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn("L2");
    when(line.getDescription()).thenReturn(null);
    when(line.getReferenceNo()).thenReturn(null);
    when(line.getCramount()).thenReturn(BigDecimal.ZERO);
    when(line.getDramount()).thenReturn(new BigDecimal("40.00"));
    when(line.getTransactionDate()).thenReturn(null);

    JSONObject json = AutoMatchSupport.lineToJson(line);

    assertEquals("", json.getString("date"));
    assertEquals("", json.getString("description"));
    assertEquals(0, new BigDecimal("-40.00").compareTo(new BigDecimal(json.getString("amount"))));
  }

  /** txnToJson serializes the transaction id, document number, net amount, and isNew=false. */
  @Test
  public void testTxnToJsonWithPaymentSerializesDocumentNo() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getDocumentNo()).thenReturn(" PAY-9 ");
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    when(t.getId()).thenReturn("T9");
    when(t.getFinPayment()).thenReturn(payment);
    when(t.getDepositAmount()).thenReturn(new BigDecimal("75.00"));
    when(t.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    when(t.getTransactionDate()).thenReturn(new Date(0L));

    JSONObject json = AutoMatchSupport.txnToJson(t);

    assertEquals("T9", json.getString("id"));
    assertEquals("PAY-9", json.getString("documentNo"));
    assertEquals(0, new BigDecimal("75.00").compareTo(new BigDecimal(json.getString("amount"))));
    assertFalse(json.getBoolean("isNew"));
  }

  /** txnToJson with no payment yields an empty documentNo (no NPE). */
  @Test
  public void testTxnToJsonNoPaymentEmptyDocumentNo() throws Exception {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    when(t.getId()).thenReturn("T0");
    when(t.getFinPayment()).thenReturn(null);
    when(t.getDepositAmount()).thenReturn(BigDecimal.ZERO);
    when(t.getPaymentAmount()).thenReturn(new BigDecimal("20.00"));
    when(t.getTransactionDate()).thenReturn(null);

    JSONObject json = AutoMatchSupport.txnToJson(t);

    assertEquals("", json.getString("documentNo"));
    assertEquals(0, new BigDecimal("-20.00").compareTo(new BigDecimal(json.getString("amount"))));
  }

  // ---------------------------------------------------------------------------
  // buildStandardGroup
  // ---------------------------------------------------------------------------

  /** buildStandardGroup composes the group key, single operation, match level, and difference. */
  @Test
  public void testBuildStandardGroup() throws Exception {
    FIN_BankStatementLine line = bslLine("L1", "100.00", "0.00");
    FIN_FinaccTransaction txn = txnWithPayment("T1", "100.00", "0.00", "DOC-1");

    JSONObject group = AutoMatchSupport.buildStandardGroup(line, txn, FIN_MatchedTransaction.STRONG);

    assertEquals("L1-T1", group.getString("groupKey"));
    assertEquals("standard", group.getString("origin"));
    assertEquals(FIN_MatchedTransaction.STRONG, group.getString("matchLevel"));
    assertFalse(group.getBoolean("isNew"));
    assertEquals(1, group.getJSONArray("operations").length());
    // line 100, op 100 → difference 0.
    assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(group.getString("difference"))));
  }

  /** buildStandardGroup with a blank match level falls back to an empty string. */
  @Test
  public void testBuildStandardGroupBlankMatchLevel() throws Exception {
    FIN_BankStatementLine line = bslLine("L1", "100.00", "0.00");
    FIN_FinaccTransaction txn = txnWithPayment("T1", "90.00", "0.00", "DOC-1");

    JSONObject group = AutoMatchSupport.buildStandardGroup(line, txn, null);

    assertEquals("", group.getString("matchLevel"));
    // line 100, op 90 → difference 10.
    assertEquals(0, new BigDecimal("10.00").compareTo(new BigDecimal(group.getString("difference"))));
  }

  // ---------------------------------------------------------------------------
  // buildMultiGroup (1:N)
  // ---------------------------------------------------------------------------

  /** buildMultiGroup concatenates a composite key, lists every operation, and sums the difference. */
  @Test
  public void testBuildMultiGroup() throws Exception {
    FIN_BankStatementLine line = bslLine("L5", "150.00", "0.00");
    FIN_FinaccTransaction t1 = txnWithPayment("T1", "100.00", "0.00", "DOC-1");
    FIN_FinaccTransaction t2 = txnWithPayment("T2", "50.00", "0.00", "DOC-2");

    JSONObject group = AutoMatchSupport.buildMultiGroup(line, Arrays.asList(t1, t2));

    assertEquals("L5-T1-T2", group.getString("groupKey"));
    assertEquals("standard", group.getString("origin"));
    assertFalse(group.getBoolean("isNew"));
    assertEquals(2, group.getJSONArray("operations").length());
    // line 150, ops sum 150 → difference 0.
    assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(group.getString("difference"))));
  }

  // ---------------------------------------------------------------------------
  // buildRuleGroup
  // ---------------------------------------------------------------------------

  /**
   * A rule carrying a GL item produces a "new" group with a proposed operation, a createPayment
   * spec, and the listed alternatives.
   */
  @Test
  public void testBuildRuleGroupWithGlItemIsNew() throws Exception {
    FIN_BankStatementLine line = bslLine("L7", "0.00", "12.50");
    MatchRuleEngine.Rule rule = new MatchRuleEngine.Rule("R1", "Fee Rule", 10,
        MatchRuleEngine.COND_CONTAINS, "fee",
        new MatchRuleEngine.RuleOptions("GL-1", "BP-1", "TT-1", null, null, null), 0L);
    MatchRuleEngine.Rule alt = new MatchRuleEngine.Rule("R2", "Alt Rule", 20,
        MatchRuleEngine.COND_CONTAINS, "bank",
        new MatchRuleEngine.RuleOptions(null, null, null, null, null, null), 0L);

    JSONObject group = AutoMatchSupport.buildRuleGroup(line, rule, Collections.singletonList(alt));

    assertEquals("L7-rule-R1", group.getString("groupKey"));
    assertEquals("rule", group.getString("origin"));
    assertEquals("Fee Rule", group.getString("ruleName"));
    assertTrue(group.getBoolean("isNew"));
    assertEquals(1, group.getJSONArray("operations").length());
    assertTrue(group.getJSONArray("operations").getJSONObject(0).getBoolean("isNew"));
    assertEquals(1, group.getJSONArray("alternatives").length());
    assertEquals("R2", group.getJSONArray("alternatives").getJSONObject(0).getString("id"));
    JSONObject cp = group.getJSONObject("createPayment");
    assertEquals("R1", cp.getString("ruleId"));
    assertEquals("GL-1", cp.getString("glItemId"));
  }

  /** A rule with no GL item produces a non-new group with no operations and no createPayment. */
  @Test
  public void testBuildRuleGroupWithoutGlItemNotNew() throws Exception {
    FIN_BankStatementLine line = bslLine("L8", "0.00", "12.50");
    MatchRuleEngine.Rule rule = new MatchRuleEngine.Rule("R3", "Plain Rule", 10,
        MatchRuleEngine.COND_CONTAINS, "fee",
        new MatchRuleEngine.RuleOptions(null, null, null, null, null, null), 0L);

    JSONObject group = AutoMatchSupport.buildRuleGroup(line, rule, Collections.emptyList());

    assertFalse(group.getBoolean("isNew"));
    assertEquals(0, group.getJSONArray("operations").length());
    assertFalse(group.has("createPayment"));
    assertEquals(0, group.getJSONArray("alternatives").length());
  }

  // ---------------------------------------------------------------------------
  // standardMatchLevel — exception path
  // ---------------------------------------------------------------------------

  /** When the matching algorithm throws, standardMatchLevel swallows it and returns null. */
  @Test
  public void testStandardMatchLevelAlgorithmThrowsReturnsNull() {
    FIN_FinancialAccount account = accountWithAlgorithm("com.example.BoomAlgo");
    FIN_BankStatementLine line = pendingLine("Transfer", "", "");

    try (MockedConstruction<FIN_MatchingTransaction> mc =
        mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
            when(m.match(any(), any())).thenThrow(new RuntimeException("boom")))) {
      assertNull(AutoMatchSupport.standardMatchLevel(account, line));
    }
  }

  /** A null account yields a null standard match level (no algorithm configured). */
  @Test
  public void testStandardMatchLevelNullAccountReturnsNull() {
    assertNull(AutoMatchSupport.standardMatchLevel(null, pendingLine("x", "", "")));
  }

  // ---------------------------------------------------------------------------
  // nextTransactionLineNo / incrementMatchCount — JDBC paths
  // ---------------------------------------------------------------------------

  /** nextTransactionLineNo returns the value computed by the SQL (max + 10). */
  @Test
  public void testNextTransactionLineNoReturnsComputedValue() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getLong(1)).thenReturn(30L);

      assertEquals(30L, AutoMatchSupport.nextTransactionLineNo("ACC-1"));
      verify(ps).setString(1, "ACC-1");
    }
  }

  /** When the line-number query throws, nextTransactionLineNo degrades to the default of 10. */
  @Test
  public void testNextTransactionLineNoOnErrorReturnsDefault() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenThrow(new java.sql.SQLException("boom"));

      assertEquals(10L, AutoMatchSupport.nextTransactionLineNo("ACC-1"));
    }
  }

  /** incrementMatchCount issues the UPDATE bound to the rule id. */
  @Test
  public void testIncrementMatchCountExecutesUpdate() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);

      AutoMatchSupport.incrementMatchCount("R1");

      verify(ps).setString(1, "R1");
      verify(ps).executeUpdate();
    }
  }

  /** incrementMatchCount swallows DB errors (best-effort, never throws). */
  @Test
  public void testIncrementMatchCountSwallowsError() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenThrow(new java.sql.SQLException("boom"));

      // Must not throw.
      AutoMatchSupport.incrementMatchCount("R1");
    }
  }

  // ---------------------------------------------------------------------------
  // findSignalGroup — orchestration over the unreconciled pool
  // ---------------------------------------------------------------------------

  /** A zero-amount statement line short-circuits findSignalGroup to an empty list. */
  @Test
  public void testFindSignalGroupZeroAmountReturnsEmpty() {
    FIN_BankStatementLine line = bslLine("L1", "50.00", "50.00");
    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.findSignalGroup("ACC-1", line, new HashSet<>(), TOL);
    assertTrue(result.isEmpty());
  }

  /**
   * Two same-partner unreconciled transactions whose signed amounts sum to the line amount are
   * returned as a 1:N partner group; the used-txn set is honoured.
   */
  @Test
  public void testFindSignalGroupPartnerGroupMatches() {
    FIN_BankStatementLine line = bslLine("L1", "150.00", "0.00");

    BusinessPartner bp = mock(BusinessPartner.class);
    lenient().when(bp.getId()).thenReturn("BP-1");
    FIN_FinaccTransaction t1 = txnWithPartner("T1", "100.00", bp);
    FIN_FinaccTransaction t2 = txnWithPartner("T2", "50.00", bp);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);
      @SuppressWarnings("unchecked")
      org.hibernate.query.Query<FIN_FinaccTransaction> query = mock(org.hibernate.query.Query.class);
      when(session.createQuery(anyString(), eq(FIN_FinaccTransaction.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(Arrays.asList(t1, t2));

      List<FIN_FinaccTransaction> result =
          AutoMatchSupport.findSignalGroup("ACC-1", line, new HashSet<>(), TOL);

      assertEquals(2, result.size());
      assertTrue(result.contains(t1));
      assertTrue(result.contains(t2));
    }
  }

  /**
   * When no partner key groups, findSignalGroup falls back to the payment reference signal and
   * returns the reference group that sums to the target.
   */
  @Test
  public void testFindSignalGroupFallsBackToReferenceKey() {
    FIN_BankStatementLine line = bslLine("L1", "150.00", "0.00");

    FIN_FinaccTransaction t1 = txnWithReference("T1", "100.00", "INV-77");
    FIN_FinaccTransaction t2 = txnWithReference("T2", "50.00", "INV-77");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);
      @SuppressWarnings("unchecked")
      org.hibernate.query.Query<FIN_FinaccTransaction> query = mock(org.hibernate.query.Query.class);
      when(session.createQuery(anyString(), eq(FIN_FinaccTransaction.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(Arrays.asList(t1, t2));

      List<FIN_FinaccTransaction> result =
          AutoMatchSupport.findSignalGroup("ACC-1", line, new HashSet<>(), TOL);

      assertEquals(2, result.size());
    }
  }

  /**
   * A same-partner pool may contain extra transactions; findSignalGroup should still return the
   * exact same-partner subset that balances the line.
   */
  @Test
  public void testFindSignalGroupPartnerSubsetMatchesInsideLargerPool() {
    FIN_BankStatementLine line = bslLine("L1", "26.40", "0.00");

    BusinessPartner bp = mock(BusinessPartner.class);
    lenient().when(bp.getId()).thenReturn("BP-1");
    FIN_FinaccTransaction t1 = txnWithPartner("T1", "95.59", bp);
    FIN_FinaccTransaction t2 = txnWithPartner("T2", "13.20", bp);
    FIN_FinaccTransaction t3 = txnWithPartner("T3", "13.20", bp);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);
      @SuppressWarnings("unchecked")
      org.hibernate.query.Query<FIN_FinaccTransaction> query = mock(org.hibernate.query.Query.class);
      when(session.createQuery(anyString(), eq(FIN_FinaccTransaction.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(Arrays.asList(t1, t2, t3));

      List<FIN_FinaccTransaction> result =
          AutoMatchSupport.findSignalGroup("ACC-1", line, new HashSet<>(), TOL);

      assertEquals(2, result.size());
      assertTrue(result.contains(t2));
      assertTrue(result.contains(t3));
    }
  }

  /** A transaction already in the used-txn set is excluded from the candidate pool. */
  @Test
  public void testFindSignalGroupSkipsUsedTransactions() {
    FIN_BankStatementLine line = bslLine("L1", "150.00", "0.00");

    BusinessPartner bp = mock(BusinessPartner.class);
    lenient().when(bp.getId()).thenReturn("BP-1");
    FIN_FinaccTransaction t1 = txnWithPartner("T1", "100.00", bp);
    FIN_FinaccTransaction t2 = txnWithPartner("T2", "50.00", bp);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);
      @SuppressWarnings("unchecked")
      org.hibernate.query.Query<FIN_FinaccTransaction> query = mock(org.hibernate.query.Query.class);
      when(session.createQuery(anyString(), eq(FIN_FinaccTransaction.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(Arrays.asList(t1, t2));

      // T1 is already used → only T2 remains → no 1:N group.
      List<FIN_FinaccTransaction> result =
          AutoMatchSupport.findSignalGroup("ACC-1", line, new HashSet<>(Arrays.asList("T1")), TOL);

      assertTrue(result.isEmpty());
    }
  }

  // ---------------------------------------------------------------------------
  // computeAmountTolerance
  // ---------------------------------------------------------------------------

  /**
   * When the percentage is 0 the floor tolerance (0.01) is returned, preserving the
   * behaviour that existed before per-account tolerances were introduced.
   */
  @Test
  public void testComputeAmountToleranceZeroPctReturnsFloor() {
    BigDecimal result = AutoMatchSupport.computeAmountTolerance(
        new BigDecimal("100.00"), BigDecimal.ZERO);
    assertEquals(0, new BigDecimal("0.01").compareTo(result));
  }

  /**
   * 2% of 100.00 = 2.00 which exceeds the floor of 0.01, so the derived value is returned.
   */
  @Test
  public void testComputeAmountTolerance2PctOf100Returns2() {
    BigDecimal result = AutoMatchSupport.computeAmountTolerance(
        new BigDecimal("100.00"), new BigDecimal("2"));
    assertEquals(0, new BigDecimal("2.00").compareTo(result));
  }

  /**
   * 10% of 50.00 = 5.00 which exceeds the floor of 0.01, so the derived value is returned.
   */
  @Test
  public void testComputeAmountTolerance10PctOf50Returns5() {
    BigDecimal result = AutoMatchSupport.computeAmountTolerance(
        new BigDecimal("50.00"), new BigDecimal("10"));
    assertEquals(0, new BigDecimal("5.00").compareTo(result));
  }

  /**
   * A null percentage behaves the same as 0 — the floor tolerance is returned.
   */
  @Test
  public void testComputeAmountToleranceNullPctReturnsFloor() {
    BigDecimal result = AutoMatchSupport.computeAmountTolerance(
        new BigDecimal("100.00"), null);
    assertEquals(0, new BigDecimal("0.01").compareTo(result));
  }

  // ---------------------------------------------------------------------------
  // withinDateWindow
  // ---------------------------------------------------------------------------

  /**
   * Two dates two days apart are within a 3-day window.
   */
  @Test
  public void testWithinDateWindow2DaysApartWithin3DaysWindow() {
    Calendar cal = Calendar.getInstance();
    Date base = cal.getTime();
    cal.add(Calendar.DAY_OF_MONTH, 2);
    Date plus2 = cal.getTime();
    assertTrue(AutoMatchSupport.withinDateWindow(base, plus2, 3));
  }

  /**
   * Two dates four days apart fall outside a 3-day window.
   */
  @Test
  public void testWithinDateWindow4DaysApartOutside3DaysWindow() {
    Calendar cal = Calendar.getInstance();
    Date base = cal.getTime();
    cal.add(Calendar.DAY_OF_MONTH, 4);
    Date plus4 = cal.getTime();
    assertFalse(AutoMatchSupport.withinDateWindow(base, plus4, 3));
  }

  /**
   * Same date with a zero-day window is within range (diff = 0 &lt;= 0).
   */
  @Test
  public void testWithinDateWindowSameDateZeroDaysWindow() {
    Date same = new Date();
    assertTrue(AutoMatchSupport.withinDateWindow(same, same, 0));
  }

  /**
   * A null date on either side always returns true (missing date means no constraint).
   */
  @Test
  public void testWithinDateWindowNullDateAlwaysTrue() {
    assertTrue(AutoMatchSupport.withinDateWindow(null, new Date(), 3));
    assertTrue(AutoMatchSupport.withinDateWindow(new Date(), null, 3));
    assertTrue(AutoMatchSupport.withinDateWindow(null, null, 3));
  }

  // ---------------------------------------------------------------------------
  // findSignalGroup — date tolerance (5-arg overload)
  // ---------------------------------------------------------------------------

  /**
   * With dateTolDays=0 only transactions on exactly the same date as the line are included.
   * A same-partner pair where one transaction is on a different day is excluded, leaving no
   * 1:N group to return.
   */
  @Test
  public void testFindSignalGroupZeroDateTolExcludesOtherDayTransactions() {
    // Line date: today.
    Calendar cal = Calendar.getInstance();
    Date lineDate = cal.getTime();

    // Build the line BSL with that date.
    FIN_BankStatementLine line = bslLine("L-DATE", "150.00", "0.00");
    lenient().when(line.getTransactionDate()).thenReturn(lineDate);

    // Same partner, but one is tomorrow — should be excluded when dateTolDays=0.
    BusinessPartner bp = mock(BusinessPartner.class);
    lenient().when(bp.getId()).thenReturn("BP-DATE");
    FIN_FinaccTransaction today = txnWithPartnerAndDate("T-TODAY", "100.00", bp, lineDate);
    cal.add(Calendar.DAY_OF_MONTH, 1);
    Date tomorrow = cal.getTime();
    FIN_FinaccTransaction tmrw = txnWithPartnerAndDate("T-TOMORROW", "50.00", bp, tomorrow);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);
      @SuppressWarnings("unchecked")
      org.hibernate.query.Query<FIN_FinaccTransaction> query =
          mock(org.hibernate.query.Query.class);
      when(session.createQuery(anyString(), eq(FIN_FinaccTransaction.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(Arrays.asList(today, tmrw));

      // Zero-day tolerance: tomorrow's transaction is outside the window → pool has only "today".
      // One-element pool → matchByKey requires >= 2 → empty result.
      List<FIN_FinaccTransaction> result =
          AutoMatchSupport.findSignalGroup("ACC-DATE", line, new HashSet<>(),
              new BigDecimal("0.01"), 0);

      assertTrue("zero-day window should exclude transactions from other days",
          result.isEmpty());
    }
  }

  // ---------------------------------------------------------------------------
  // Builders for the new tests
  // ---------------------------------------------------------------------------

  /** A bank-statement line mock with a credit/debit amount and a fixed id (no date). */
  private static FIN_BankStatementLine bslLine(String id, String credit, String debit) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    lenient().when(line.getId()).thenReturn(id);
    lenient().when(line.getCramount()).thenReturn(new BigDecimal(credit));
    lenient().when(line.getDramount()).thenReturn(new BigDecimal(debit));
    lenient().when(line.getDescription()).thenReturn("desc");
    lenient().when(line.getReferenceNo()).thenReturn("");
    lenient().when(line.getTransactionDate()).thenReturn(null);
    return line;
  }

  /** A transaction mock carrying a FIN_Payment with the given documentNo. */
  private static FIN_FinaccTransaction txnWithPayment(String id, String deposit, String payment,
      String documentNo) {
    FIN_Payment p = mock(FIN_Payment.class);
    lenient().when(p.getDocumentNo()).thenReturn(documentNo);
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    lenient().when(t.getId()).thenReturn(id);
    lenient().when(t.getFinPayment()).thenReturn(p);
    lenient().when(t.getDepositAmount()).thenReturn(new BigDecimal(deposit));
    lenient().when(t.getPaymentAmount()).thenReturn(new BigDecimal(payment));
    lenient().when(t.getTransactionDate()).thenReturn(null);
    return t;
  }

  /** A positive-amount transaction mock attached to a business partner (partner signal). */
  private static FIN_FinaccTransaction txnWithPartner(String id, String amount, BusinessPartner bp) {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    lenient().when(t.getId()).thenReturn(id);
    lenient().when(t.getBusinessPartner()).thenReturn(bp);
    lenient().when(t.getFinPayment()).thenReturn(null);
    lenient().when(t.getDepositAmount()).thenReturn(new BigDecimal(amount));
    lenient().when(t.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    return t;
  }

  /** A positive-amount transaction mock attached to a payment with a reference number. */
  private static FIN_FinaccTransaction txnWithReference(String id, String amount, String reference) {
    FIN_Payment p = mock(FIN_Payment.class);
    lenient().when(p.getBusinessPartner()).thenReturn(null);
    lenient().when(p.getReferenceNo()).thenReturn(reference);
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    lenient().when(t.getId()).thenReturn(id);
    lenient().when(t.getBusinessPartner()).thenReturn(null);
    lenient().when(t.getFinPayment()).thenReturn(p);
    lenient().when(t.getDepositAmount()).thenReturn(new BigDecimal(amount));
    lenient().when(t.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    return t;
  }

  /** A positive-amount transaction mock attached to a partner and carrying a specific date. */
  private static FIN_FinaccTransaction txnWithPartnerAndDate(String id, String amount,
      BusinessPartner bp, Date date) {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    lenient().when(t.getId()).thenReturn(id);
    lenient().when(t.getBusinessPartner()).thenReturn(bp);
    lenient().when(t.getFinPayment()).thenReturn(null);
    lenient().when(t.getDepositAmount()).thenReturn(new BigDecimal(amount));
    lenient().when(t.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    lenient().when(t.getTransactionDate()).thenReturn(date);
    return t;
  }
}
