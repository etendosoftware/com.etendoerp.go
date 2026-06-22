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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.advpaymentmngt.utility.FIN_MatchedTransaction;
import org.openbravo.advpaymentmngt.utility.FIN_MatchingTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

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
    JSONObject l1 = line("L1", "", "100.00", "100.00", "0.00");
    JSONObject l2 = line("L2", "", "50.00", "0.00", "50.00");
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

    JSONObject l1 = line("L1", "GRP-1", "100.00", "100.00", "0.00");
    l1.put("txns", new JSONArray().put(txnA));

    JSONObject l2 = line("L2", "GRP-1", "50.00", "50.00", "0.00");
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
  }

  /**
   * Three sub-lines sharing the same matchGroupId: all three collapse into a single merged line
   * with three entries in {@code txns} and the summed amounts.
   *
   * @throws Exception if building the JSON line objects fails
   */
  @Test
  public void testMergeMatchGroupsThreeSubLinesAllMerge() throws Exception {
    JSONObject l1 = line("L1", "GRP-X", "40.00", "40.00", "0.00");
    l1.put("txns", new JSONArray().put(new JSONObject().put("id", "T1")));
    JSONObject l2 = line("L2", "GRP-X", "30.00", "30.00", "0.00");
    l2.put("txns", new JSONArray().put(new JSONObject().put("id", "T2")));
    JSONObject l3 = line("L3", "GRP-X", "30.00", "30.00", "0.00");
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

  /** Builds a minimal statement-line JSONObject for mergeMatchGroups tests. */
  private static JSONObject line(String id, String groupId, String amount, String in, String out)
      throws Exception {
    JSONObject o = new JSONObject();
    o.put("id", id);
    o.put("matchGroupId", groupId);
    o.put("amount", amount);
    o.put("in", in);
    o.put("out", out);
    o.put("matched", false);
    o.put("txns", new JSONArray());
    return o;
  }
}
