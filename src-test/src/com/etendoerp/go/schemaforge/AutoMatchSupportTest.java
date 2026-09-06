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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

import com.etendoerp.go.schemaforge.data.MatchRule;

/**
 * Unit tests for {@link AutoMatchSupport} — covers {@link AutoMatchSupport#matchByKey} (the 1:N
 * signal-grouping core), {@link AutoMatchSupport#classifyPendingLine} (state classification for
 * the left-panel filter), {@link AutoMatchSupport#matchFallback} (the automatch preview) and
 * {@link BankStatementsSupport#mergeMatchGroups} (sub-line collapsing for the statement-lines
 * panel).
 *
 * <p><b>Scope split with {@code NearMatchSupportTest}.</b> The ETP-4965 1:1 near-match search moved
 * to {@link NearMatchSupport} when {@code AutoMatchSupport} hit the Sonar per-class method limit, so
 * {@code findNearMatch}, {@code differenceTolerance} and {@code dayDistance} are unit-tested there.
 * What stays here is everything that reads them through a method of THIS class: the §5.1
 * classification matrix as {@code classifyPendingLine} reports it, the precedence of a 1:N signal
 * group over a near match, the shared accumulators across a pass, and the automatch preview. The
 * overlap is deliberate — the search finding a candidate and the classifier reporting the right
 * state are two different contracts, and either can break without the other.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class AutoMatchSupportTest {

  private static final BigDecimal TOL = new BigDecimal("0.01");

  /** Wire keys {@code txnToJson} emits; the production constants are private to the class. */
  private static final String KEY_DESCRIPTION = "description";
  private static final String KEY_PARTNER_NAME = "partnerName";
  private static final String KEY_MATCH_LEVEL = "matchLevel";
  private static final String KEY_OPERATIONS = "operations";
  /** Filler text for fixtures whose description is not what the test is about. */
  private static final String ANY_DESCRIPTION = "desc";

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
   * ETP-4965 regression — Core's WEAK level now classifies {@code suggested}, NOT
   * {@code difference}.
   *
   * <p>Core's {@code StandardMatchingAlgorithm} searches by EXACT amount and EXACT date; WEAK only
   * means the payment REFERENCE did not also match. Amount and date agree, so there is no deviation
   * to post — and after this ticket "Con diferencia" means exactly one thing: a real amount and/or
   * date deviation that is still inside the account's tolerances. Routing WEAK there polluted the
   * filter with matches that had nothing to adjust.
   *
   * <p>Like the STRONG branch, the WEAK branch must also feed BOTH accumulators, so the transaction
   * it claims is not offered again to a later line of the same amount in the same pass.
   */
  @Test
  public void testClassifyPendingLineStandardAlgorithmWeakMatchReturnsSuggested() {
    FIN_FinancialAccount account = accountWithAlgorithm("com.example.DummyAlgo");
    FIN_BankStatementLine line = pendingLine("Transfer ACME", "", "");

    FIN_FinaccTransaction weakTxn = mock(FIN_FinaccTransaction.class);
    lenient().when(weakTxn.getId()).thenReturn("T-WEAK");
    FIN_MatchedTransaction matched = mock(FIN_MatchedTransaction.class);
    when(matched.getTransaction()).thenReturn(weakTxn);
    // Any level that is not STRONG and not NOMATCH — historically the "difference" path.
    when(matched.getMatchLevel()).thenReturn("WEAK");

    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new java.util.ArrayList<>();

    try (MockedConstruction<FIN_MatchingTransaction> mc =
        mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
            when(m.match(any(), any())).thenReturn(matched))) {
      String state = AutoMatchSupport.classifyPendingLine(account, line,
          Collections.emptyList(), AutoMatchSupport.DEFAULT_DATE_TOL_DAYS, BigDecimal.ZERO,
          usedTxnIds, excludedTxns);

      assertEquals(AutoMatchSupport.STATE_SUGGESTED, state);
      assertTrue("a WEAK match must consume its transaction like a STRONG one does",
          usedTxnIds.contains("T-WEAK"));
      assertTrue(excludedTxns.contains(weakTxn));
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
  // standardMatch — excluded-list forwarding (ETP-4971)
  // ---------------------------------------------------------------------------

  /**
   * standardMatch passes the EXACT {@code excluded} list instance into the constructed matcher's
   * {@code match(line, excluded)} call — the mechanism {@code buildAutoMatch} relies on to
   * accumulate consumed transactions across pending lines of the same amount.
   */
  @Test
  public void testStandardMatchForwardsExcludedListInstance() throws Exception {
    FIN_FinancialAccount account = accountWithAlgorithm("com.example.DummyAlgo");
    FIN_BankStatementLine line = pendingLine("Transfer ACME", "", "");
    List<FIN_FinaccTransaction> excluded = new java.util.ArrayList<>();

    FIN_MatchedTransaction matched = mock(FIN_MatchedTransaction.class);
    when(matched.getTransaction()).thenReturn(mock(FIN_FinaccTransaction.class));
    when(matched.getMatchLevel()).thenReturn(FIN_MatchedTransaction.STRONG);

    try (MockedConstruction<FIN_MatchingTransaction> mc =
        mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
            when(m.match(same(line), same(excluded))).thenReturn(matched))) {
      FIN_MatchedTransaction result = AutoMatchSupport.standardMatch(
          account, line, AutoMatchSupport.DEFAULT_DATE_TOL_DAYS, excluded);

      assertEquals(matched, result);
      verify(mc.constructed().get(0)).match(same(line), same(excluded));
    }
  }

  // ---------------------------------------------------------------------------
  // classifyPendingLine (7-arg, shared usedTxnIds/excludedTxns accumulator)
  // ---------------------------------------------------------------------------

  /**
   * Two calls to the 7-arg classifyPendingLine sharing the SAME usedTxnIds/excludedTxns
   * accumulators: the first line claims transaction T1 (state {@code suggested}, both
   * accumulators updated with it); the second line of the identical amount — because T1 is now in
   * excludedTxns — gets no standard match at all, mirroring the exhaustion buildAutoMatch itself
   * applies across pending lines sharing an amount.
   */
  @Test
  public void testClassifyPendingLineSharedAccumulatorExhaustsCandidate() {
    FIN_FinancialAccount account = accountWithAlgorithm("com.example.DummyAlgo");
    FIN_BankStatementLine line1 = bslLine("L1", "50.00", "0.00");
    FIN_BankStatementLine line2 = bslLine("L2", "50.00", "0.00");

    FIN_FinaccTransaction t1 = mock(FIN_FinaccTransaction.class);
    lenient().when(t1.getId()).thenReturn("T1");
    lenient().when(t1.getDepositAmount()).thenReturn(new BigDecimal("50.00"));
    lenient().when(t1.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    lenient().when(t1.getTransactionDate()).thenReturn(null);

    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new java.util.ArrayList<>();

    try (MockedConstruction<FIN_MatchingTransaction> mc =
        mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
            when(m.match(any(), any())).thenAnswer(invocation -> {
              List<FIN_FinaccTransaction> excluded = invocation.getArgument(1);
              FIN_MatchedTransaction result = mock(FIN_MatchedTransaction.class);
              if (!excluded.contains(t1)) {
                lenient().when(result.getTransaction()).thenReturn(t1);
                lenient().when(result.getMatchLevel()).thenReturn(FIN_MatchedTransaction.STRONG);
              } else {
                lenient().when(result.getMatchLevel()).thenReturn(FIN_MatchedTransaction.NOMATCH);
              }
              return result;
            }))) {
      String firstState = AutoMatchSupport.classifyPendingLine(account, line1,
          Collections.emptyList(), AutoMatchSupport.DEFAULT_DATE_TOL_DAYS, BigDecimal.ZERO,
          usedTxnIds, excludedTxns);

      assertEquals(AutoMatchSupport.STATE_SUGGESTED, firstState);
      assertTrue(usedTxnIds.contains("T1"));
      assertTrue(excludedTxns.contains(t1));

      String secondState = AutoMatchSupport.classifyPendingLine(account, line2,
          Collections.emptyList(), AutoMatchSupport.DEFAULT_DATE_TOL_DAYS, BigDecimal.ZERO,
          usedTxnIds, excludedTxns);

      assertEquals(AutoMatchSupport.STATE_PENDING, secondState);
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
   * ETP-4502 iteration 5: a PARTIAL group must expose the id of its pending remainder sub-line as
   * {@code remainderLineId} — the sub-line the UI reconciles the rest of the line against. The
   * first UNMATCHED sub-line wins; here the matched 53.24 sub-line is the group head (no
   * remainderLineId) and the 46.76 pending one contributes it.
   *
   * @throws Exception if building the JSON line objects fails
   */
  @Test
  public void testMergeMatchGroupsPartialExposesRemainderLineId() throws Exception {
    JSONObject txnA = new JSONObject().put("id", "T1").put("amount", "53.24");
    JSONObject matchedSub = line("L1", "GRP-PARTIAL", "53.24", "53.24", "0.00", true);
    matchedSub.put("txns", new JSONArray().put(txnA));
    JSONObject pendingSub = line("L2", "GRP-PARTIAL", "46.76", "46.76", "0.00", false);

    JSONArray input = new JSONArray();
    input.put(matchedSub);
    input.put(pendingSub);

    JSONArray result = BankStatementsSupport.mergeMatchGroups(input);

    assertEquals(1, result.length());
    JSONObject merged = result.getJSONObject(0);
    assertEquals("PARTIAL", merged.getString("reconcileStatus"));
    // The remainder is the pending sub-line, not the matched head.
    assertEquals("L2", merged.getString("remainderLineId"));
  }

  /**
   * ETP-4502 iteration 5: a fully RECONCILED group (every sub-line matched) has no pending
   * remainder, so it must NOT carry a {@code remainderLineId}.
   *
   * @throws Exception if building the JSON line objects fails
   */
  @Test
  public void testMergeMatchGroupsFullyReconciledHasNoRemainderLineId() throws Exception {
    JSONObject txnA = new JSONObject().put("id", "T1").put("amount", "60.00");
    JSONObject txnB = new JSONObject().put("id", "T2").put("amount", "40.00");
    JSONObject subA = line("L1", "GRP-FULL", "60.00", "60.00", "0.00", true);
    subA.put("txns", new JSONArray().put(txnA));
    JSONObject subB = line("L2", "GRP-FULL", "40.00", "40.00", "0.00", true);
    subB.put("txns", new JSONArray().put(txnB));

    JSONArray input = new JSONArray();
    input.put(subA);
    input.put(subB);

    JSONArray result = BankStatementsSupport.mergeMatchGroups(input);

    assertEquals(1, result.length());
    JSONObject merged = result.getJSONObject(0);
    assertEquals("RECONCILED", merged.getString("reconcileStatus"));
    assertFalse(merged.has("remainderLineId"));
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

  /**
   * lineToJson serializes id, trimmed text fields, a signed amount, and the canonical NEO wire
   * datetime in the server's own zone (ETP-5100) — no trailing {@code Z}.
   *
   * <p>The transaction date is supplied as a CIVIL value ({@link Timestamp#valueOf} reads the
   * literal in the default zone), not as the epoch instant {@code new Date(0L)} this test used
   * to pass. A business date is a civil datum, and pairing an instant input with a UTC-rendered
   * expectation only ever worked because the old formatter forced UTC; this pairing holds in any
   * timezone.
   */
  @Test
  public void testLineToJsonSerializesFields() throws Exception {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn("L1");
    when(line.getDescription()).thenReturn("  Bank fee  ");
    when(line.getReferenceNo()).thenReturn(" REF-1 ");
    when(line.getCramount()).thenReturn(new BigDecimal("100.00"));
    when(line.getDramount()).thenReturn(new BigDecimal("0.00"));
    when(line.getTransactionDate()).thenReturn(Timestamp.valueOf("2026-09-01 21:43:02"));

    JSONObject json = AutoMatchSupport.lineToJson(line);

    assertEquals("L1", json.getString("id"));
    assertEquals("Bank fee", json.getString(KEY_DESCRIPTION));
    assertEquals("REF-1", json.getString("referenceNo"));
    assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(json.getString("amount"))));
    // Under the old UTC formatter this came out as 2026-09-02T00:43:02Z in UTC-3 — the next
    // calendar day — and the React range filter then dropped the row.
    assertEquals("2026-09-01T21:43:02", json.getString("date"));
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
    assertEquals("", json.getString(KEY_DESCRIPTION));
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
  // txnToJson — description and partnerName (ETP-4965 round 3)
  //
  // A payment number alone ("1000181") identifies nothing to the person approving an automatch
  // batch: the suggestion row has to carry what the Movimientos list shows. Both fields are always
  // emitted — never absent — so a consumer can read them unconditionally.
  // ---------------------------------------------------------------------------

  /**
   * The transaction's OWN description and business partner win when it has them, and both are
   * trimmed. The partner precedence deliberately mirrors {@code partnerKey}'s, so a row cannot be
   * grouped under one partner and labelled with another.
   */
  @Test
  public void testTxnToJsonPrefersTheTransactionsOwnDescriptionAndPartner() throws Exception {
    FIN_Payment payment = paymentWith(" PAY-1 ", " the payment's own description ",
        partnerNamed("Payment Partner SL"));
    FIN_FinaccTransaction t =
        describedTxn("T-DESC", "  Factura 10000215.  ", partnerNamed("  ACME Corp  "), payment);

    JSONObject json = AutoMatchSupport.txnToJson(t);

    assertEquals("the transaction's own text wins over the payment's",
        "Factura 10000215.", json.getString(KEY_DESCRIPTION));
    assertEquals("and so does its own partner", "ACME Corp", json.getString(KEY_PARTNER_NAME));
  }

  /**
   * A payment-backed movement usually carries its text on the PAYMENT rather than on itself, so a
   * blank transaction description falls back to the payment's. Blank, not just null: an
   * all-whitespace description is exactly as unhelpful in the UI as a missing one.
   */
  @Test
  public void testTxnToJsonFallsBackToThePaymentsDescriptionWhenTheTransactionsIsBlank()
      throws Exception {
    FIN_Payment payment = paymentWith("PAY-2", "  Cobro de ACME  ", null);
    FIN_FinaccTransaction t = describedTxn("T-BLANK", "   ", null, payment);

    JSONObject json = AutoMatchSupport.txnToJson(t);

    assertEquals("Cobro de ACME", json.getString(KEY_DESCRIPTION));
  }

  /**
   * Neither side has a description: the key is still emitted, as an empty string. A consumer must
   * never have to distinguish "absent" from "empty" — and a null here would print "null" in the
   * suggestion row.
   */
  @Test
  public void testTxnToJsonEmptyDescriptionWhenNeitherSideHasOne() throws Exception {
    FIN_FinaccTransaction withPayment =
        describedTxn("T-NONE-1", null, null, paymentWith("PAY-3", null, null));
    FIN_FinaccTransaction bare = describedTxn("T-NONE-2", null, null, null);

    assertTrue(AutoMatchSupport.txnToJson(withPayment).has(KEY_DESCRIPTION));
    assertEquals("", AutoMatchSupport.txnToJson(withPayment).getString(KEY_DESCRIPTION));
    assertEquals("a transaction with no payment at all must not NPE either",
        "", AutoMatchSupport.txnToJson(bare).getString(KEY_DESCRIPTION));
  }

  /**
   * The partner falls back to the PAYMENT's when the transaction carries none — the same precedence
   * {@code partnerKey} uses for 1:N grouping.
   */
  @Test
  public void testTxnToJsonFallsBackToThePaymentsPartnerWhenTheTransactionHasNone()
      throws Exception {
    FIN_Payment payment =
        paymentWith("PAY-4", ANY_DESCRIPTION, partnerNamed(" Payment Partner SL "));
    FIN_FinaccTransaction t = describedTxn("T-BP-VIA-PAY", ANY_DESCRIPTION, null, payment);

    assertEquals("Payment Partner SL",
        AutoMatchSupport.txnToJson(t).getString(KEY_PARTNER_NAME));
  }

  /** No partner on either side: an empty string, and the key is still present. */
  @Test
  public void testTxnToJsonEmptyPartnerNameWhenNeitherSideHasOne() throws Exception {
    FIN_FinaccTransaction withPayment =
        describedTxn("T-NOBP-1", ANY_DESCRIPTION, null,
            paymentWith("PAY-5", ANY_DESCRIPTION, null));
    FIN_FinaccTransaction bare = describedTxn("T-NOBP-2", ANY_DESCRIPTION, null, null);

    assertTrue(AutoMatchSupport.txnToJson(withPayment).has(KEY_PARTNER_NAME));
    assertEquals("", AutoMatchSupport.txnToJson(withPayment).getString(KEY_PARTNER_NAME));
    assertEquals("", AutoMatchSupport.txnToJson(bare).getString(KEY_PARTNER_NAME));
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

  /**
   * incrementMatchCount bumps the counter through the DAL and saves the rule.
   *
   * <p>It used to be a raw {@code UPDATE ... WHERE etgo_match_rule_id = ?}. The id arrives in the
   * request body and that statement had no {@code ad_client_id} predicate, so it bumped another
   * tenant's counter; going through {@link OBCriteria} gets the readable-client filter for free
   * (ETP-4950).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testIncrementMatchCountBumpsTheCounterThroughTheDal() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      OBCriteria<MatchRule> criteria = mock(OBCriteria.class);
      MatchRule rule = mock(MatchRule.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.createCriteria(MatchRule.class)).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(rule);
      when(rule.getMatchCount()).thenReturn(4L);

      AutoMatchSupport.incrementMatchCount("R1");

      verify(rule).setMatchCount(5L);
      verify(dal).save(rule);
    }
  }

  /** A null counter starts from zero rather than throwing on unboxing. */
  @Test
  @SuppressWarnings("unchecked")
  public void testIncrementMatchCountTreatsNullCounterAsZero() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      OBCriteria<MatchRule> criteria = mock(OBCriteria.class);
      MatchRule rule = mock(MatchRule.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.createCriteria(MatchRule.class)).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(rule);
      when(rule.getMatchCount()).thenReturn(null);

      AutoMatchSupport.incrementMatchCount("R1");

      verify(rule).setMatchCount(1L);
    }
  }

  /**
   * A rule the current tenant cannot see is left alone — nothing is saved. This is the tenant-leak
   * guard: the DAL filters the criteria by readable client, so a foreign rule id resolves to null.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testIncrementMatchCountSkipsARuleTheTenantCannotSee() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      OBCriteria<MatchRule> criteria = mock(OBCriteria.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.createCriteria(MatchRule.class)).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      AutoMatchSupport.incrementMatchCount("FOREIGN-RULE");

      verify(dal, never()).save(any());
    }
  }

  /** incrementMatchCount swallows DB errors (best-effort, never throws). */
  @Test
  public void testIncrementMatchCountSwallowsError() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.createCriteria(MatchRule.class)).thenThrow(new IllegalStateException("boom"));

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
  // matchFallback — excludedTxns accumulation (ETP-4971)
  // ---------------------------------------------------------------------------

  /**
   * matchFallback appends the resolved signal-group transactions to the {@code excludedTxns}
   * accumulator too, not only {@code usedTxnIds} — so a later pending line of the same amount
   * cannot be offered one of these same transactions again by the standard 1:1 algorithm either.
   */
  @Test
  public void testMatchFallbackAppendsSignalGroupToExcludedTxns() throws Exception {
    FIN_BankStatementLine line = bslLine("L1", "150.00", "0.00");

    BusinessPartner bp = mock(BusinessPartner.class);
    lenient().when(bp.getId()).thenReturn("BP-1");
    FIN_FinaccTransaction t1 = txnWithPartner("T1", "100.00", bp);
    FIN_FinaccTransaction t2 = txnWithPartner("T2", "50.00", bp);

    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new java.util.ArrayList<>();
    JSONArray groups = new JSONArray();

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

      int[] delta = AutoMatchSupport.matchFallback("ACC-1", line, usedTxnIds, excludedTxns,
          Collections.emptyList(), groups, AutoMatchSupport.DEFAULT_DATE_TOL_DAYS,
          BigDecimal.ZERO);

      assertEquals(2, delta[0]);
      assertEquals(0, delta[1]);
      assertTrue(usedTxnIds.contains("T1"));
      assertTrue(usedTxnIds.contains("T2"));
      assertTrue(excludedTxns.contains(t1));
      assertTrue(excludedTxns.contains(t2));
      assertEquals(1, groups.length());
    }
  }

  /**
   * The automatch PREVIEW must offer the date-only near match at 0% amount tolerance too — the
   * user-visible half of the "0% is not a master switch" contract (§5.2). Every account ships with
   * a 3-day date tolerance, so this pass proposes groups on accounts that never configured a
   * percentage; that is intended, because the group it emits carries a ZERO difference and
   * therefore cannot produce any accounting entry when applied.
   *
   * <p>Uses the same 6-arg-equivalent 0% call the untouched overload makes, so a regression back to
   * "null tolerance means stop searching" fails here as well as in
   * {@code NearMatchSupportTest#testZeroAmountToleranceStillDetectsADateOnlyDeviation}.
   */
  @Test
  public void testMatchFallbackOffersADateOnlyNearMatchAtZeroTolerance() throws Exception {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-FB-DATE", "100.00", NO_DEBIT, today);
    FIN_FinaccTransaction twoDaysEarlier = nearTxn(T_NEAR, "100.00", daysFrom(today, -2));

    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new java.util.ArrayList<>();
    JSONArray groups = new JSONArray();

    try (MockedStatic<OBDal> obDal =
        mockUnreconciledPool(Collections.singletonList(twoDaysEarlier))) {
      int[] delta = AutoMatchSupport.matchFallback(NEAR_ACC, line, usedTxnIds, excludedTxns,
          Collections.emptyList(), groups, DATE_TOL_DAYS, BigDecimal.ZERO);

      assertEquals("the near match links one operation", 1, delta[0]);
      assertEquals("nothing is created — a date-only difference posts nothing", 0, delta[1]);
      assertEquals(1, groups.length());
      assertTrue(usedTxnIds.contains(T_NEAR));
      assertTrue(excludedTxns.contains(twoDaysEarlier));
      assertEquals("the proposed group carries no amount difference",
          0, new BigDecimal(groups.getJSONObject(0).getString("difference"))
              .compareTo(BigDecimal.ZERO));
      // The flag describes WHY the group was proposed, not what applying it writes: a date-only
      // near match creates nothing, yet the modal still has to paint it as a difference rather
      // than as a plain suggestion. Reading `difference != 0` instead would lose exactly this row.
      assertTrue("a date-only near match is still flagged as one",
          groups.getJSONObject(0).optBoolean(AutoMatchSupport.KEY_NEAR_MATCH, false));
    }
  }

  /**
   * The AMOUNT half of the near-match preview: a 27.00 line against a 26.62 movement (0.38 = 1.41%,
   * inside the 5% tolerance) is proposed as a near match AND counted as a creation, because
   * applying it also posts the 0.38 leftover to the account's GL Item Difference
   * ({@code ReconciliationDifferenceSupport.applyInlineDifference}).
   *
   * <p>Reporting only the link is the bug this asserts against: the modal promised one movement
   * and the batch created two. The {@code nearMatch} flag is asserted alongside the delta because
   * both halves feed the same footer — one drives the badge, the other the "will create" count.
   *
   * @throws Exception if the DAL stubbing or the JSON assertions fail
   */
  @Test
  public void testMatchFallbackAmountNearMatchIsFlaggedAndCountsAsACreation() throws Exception {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-FB-AMT", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction deviating = nearTxn(T_NEAR, NEAR_AMOUNT, today);

    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new java.util.ArrayList<>();
    JSONArray groups = new JSONArray();

    try (MockedStatic<OBDal> obDal =
        mockUnreconciledPool(Collections.singletonList(deviating))) {
      int[] delta = AutoMatchSupport.matchFallback(NEAR_ACC, line, usedTxnIds, excludedTxns,
          Collections.emptyList(), groups, DATE_TOL_DAYS, PCT_FIVE);

      assertEquals("the near match links one operation", 1, delta[0]);
      assertEquals("the leftover posting is a creation too", 1, delta[1]);
      assertEquals(1, groups.length());

      JSONObject group = groups.getJSONObject(0);
      assertTrue("the group is flagged as a near match",
          group.optBoolean(AutoMatchSupport.KEY_NEAR_MATCH, false));
      assertEquals("the 0.38 leftover travels on the group", 0,
          new BigDecimal("0.38").compareTo(new BigDecimal(group.getString("difference"))));
      assertTrue(usedTxnIds.contains(T_NEAR));
      assertTrue(excludedTxns.contains(deviating));
    }
  }

  /**
   * <b>The trap {@link AutoMatchSupport#KEY_NEAR_MATCH} exists to avoid.</b> A 1:N signal group is
   * allowed to close with a non-zero {@code difference} — {@link AutoMatchSupport#signalGroupTolerance}
   * grants rounding slack on the SUM — and that is NOT a near match: nothing is posted for it, and
   * the modal must not offer a difference row or bump its create count.
   *
   * <p>So a consumer that infers the badge from {@code difference != 0} instead of reading the flag
   * regresses here: 49.00 + 50.00 against a 100.00 line leaves 1.00 on a group that must come back
   * unflagged, with a {@code {2, 0}} delta.
   *
   * @throws Exception if the DAL stubbing or the JSON assertions fail
   */
  @Test
  public void testMatchFallbackSignalGroupWithRoundingSlackIsNotANearMatch() throws Exception {
    FIN_BankStatementLine line = bslLine("L-SLACK", "100.00", NO_DEBIT);

    BusinessPartner bp = mock(BusinessPartner.class);
    lenient().when(bp.getId()).thenReturn("BP-SLACK");
    // 99.00 against 100.00: a 1.00 gap, inside the 5.00 slack a 5% tolerance grants on the sum.
    FIN_FinaccTransaction t1 = txnWithPartner("T-S1", "49.00", bp);
    FIN_FinaccTransaction t2 = txnWithPartner("T-S2", "50.00", bp);

    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new java.util.ArrayList<>();
    JSONArray groups = new JSONArray();

    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Arrays.asList(t1, t2))) {
      int[] delta = AutoMatchSupport.matchFallback("ACC-SLACK", line, usedTxnIds, excludedTxns,
          Collections.emptyList(), groups, DATE_TOL_DAYS, PCT_FIVE);

      assertEquals("both operations are linked", 2, delta[0]);
      assertEquals("a 1:N group creates nothing, whatever its rounding slack", 0, delta[1]);
      assertEquals(1, groups.length());

      JSONObject group = groups.getJSONObject(0);
      assertEquals("the rounding slack IS carried as a difference", 0,
          new BigDecimal("1.00").compareTo(new BigDecimal(group.getString("difference"))));
      assertFalse("a 1:N signal group is never a near match",
          group.optBoolean(AutoMatchSupport.KEY_NEAR_MATCH, false));
      assertFalse("and the key is not emitted at all",
          group.has(AutoMatchSupport.KEY_NEAR_MATCH));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-4965 round 3 — the reported 14,52 case, and the labelling that follows from it
  //
  // Reported: a 14,52 statement line dated 04/09 was matched against a 14,52 movement dated 01/09
  // while TWO 14,52 movements dated 04/09 sat unused. Cause: findNearMatch excluded the exact-exact
  // candidate outright, on the assumption that Core's pass 1 had already claimed it. Core's own
  // criteria are narrower, so when it does not match, the BEST candidate was invisible and the
  // line silently got a worse one.
  //
  // Two consequences follow, and both are asserted below:
  //   1. eligibility is the tolerance alone; RANKING picks the winner (gap → date → oldest);
  //   2. the label is read off the candidate that won, via deviatesFrom — so an exact hit reached
  //      through this path is a plain suggestion, not a "Con diferencia".
  //
  // (2) has to hold in BOTH places that label a line, or the left panel's badge contradicts the
  // automatch modal's for the very same line. That is what the testClassifyAndMatchFallbackAgree*
  // pair pins: one fixture, both readers, one verdict.
  // ═══════════════════════════════════════════════════════════════════════════

  /** The reported amount: all three candidates carry it, so only the date can rank them. */
  private static final String REPORTED_AMOUNT = "14.52";
  private static final String T_SEP01 = "T-01-09";
  private static final String T_SEP04_EARLY = "T-04-09-08H";
  private static final String T_SEP04_LATE = "T-04-09-20H";

  /**
   * <b>THE regression guard for the whole of round 3</b>, end to end through
   * {@link AutoMatchSupport#matchFallback} — the method that builds what the automatch modal shows.
   *
   * <p>A 14,52 line of 04/09 against three 14,52 movements: one of 01/09 (inside the 3-day window)
   * and two of 04/09. The 04/09 movements are strictly better — zero date distance — and between
   * them the OLDER one wins. Under the round-1 code all three had a zero amount gap AND the two
   * same-day ones had a zero date distance too, so all three were rejected as "not a reportable
   * deviation" except the 01/09 one, which is exactly the wrong answer the user reported.
   *
   * <p>The pool order defeats both naive readings at once: the 01/09 movement is offered FIRST (so
   * "keep the first eligible hit" reproduces the reported bug) and the LATER 04/09 movement
   * precedes the earlier one (so "keep the first same-day hit" picks the wrong one of the two).
   * The failure message names the date that was actually picked, because that is the only thing
   * that tells the two wrong answers apart.
   *
   * @throws Exception if the DAL stubbing or the JSON assertions fail
   */
  @Test
  public void testReportedCasePicksTheOlderSameDayMovementNotTheThreeDayOldOne() throws Exception {
    Date lineDate = dayAt(2026, 9, 4, 12);
    FIN_BankStatementLine line = datedLine("L-14-52", REPORTED_AMOUNT, NO_DEBIT, lineDate);
    FIN_FinaccTransaction sep01 = nearTxn(T_SEP01, REPORTED_AMOUNT, dayAt(2026, 9, 1, 15));
    FIN_FinaccTransaction sep04Early =
        nearTxn(T_SEP04_EARLY, REPORTED_AMOUNT, dayAt(2026, 9, 4, 8));
    FIN_FinaccTransaction sep04Late = nearTxn(T_SEP04_LATE, REPORTED_AMOUNT, dayAt(2026, 9, 4, 20));

    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new java.util.ArrayList<>();
    JSONArray groups = new JSONArray();

    try (MockedStatic<OBDal> obDal =
        mockUnreconciledPool(Arrays.asList(sep01, sep04Late, sep04Early))) {
      int[] delta = AutoMatchSupport.matchFallback(NEAR_ACC, line, usedTxnIds, excludedTxns,
          Collections.emptyList(), groups, DATE_TOL_DAYS, PCT_FIVE);

      assertEquals("exactly one group is proposed for the line", 1, groups.length());
      JSONObject group = groups.getJSONObject(0);
      JSONObject op = group.getJSONArray(KEY_OPERATIONS).getJSONObject(0);

      assertEquals("the 14,52 line of 04/09 was matched against the movement dated "
              + op.getString("date") + ", but the OLDER of the two 04/09 movements must win",
          T_SEP04_EARLY, op.getString("id"));
      assertFalse("an exact hit deviates in nothing, so the group carries no nearMatch flag",
          group.has(AutoMatchSupport.KEY_NEAR_MATCH));
      assertEquals("nor Core's WEAK diagnostics vocabulary",
          FIN_MatchedTransaction.STRONG, group.getString(KEY_MATCH_LEVEL));
      assertEquals("one operation to link", 1, delta[0]);
      assertEquals("and nothing to create — there is no leftover to post", 0, delta[1]);
      assertTrue("the winner is claimed by id", usedTxnIds.contains(T_SEP04_EARLY));
      assertTrue("and fed back to Core's matcher", excludedTxns.contains(sep04Early));
    }
  }

  /**
   * The exact hit as a delta, isolated from the ranking: one candidate, no deviation at all →
   * {@code {1, 0}}, no {@code nearMatch} key, STRONG. The third row of the delta matrix, whose
   * other two rows ({@code {1, 1}} for an amount deviation, {@code {1, 0}} for a date-only one) are
   * asserted just above.
   *
   * @throws Exception if the DAL stubbing or the JSON assertions fail
   */
  @Test
  public void testMatchFallbackExactHitIsAPlainSuggestionAndCreatesNothing() throws Exception {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-FB-EXACT", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction exact = nearTxn(T_NEAR, LINE_CREDIT, today);

    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new java.util.ArrayList<>();
    JSONArray groups = new JSONArray();

    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(exact))) {
      int[] delta = AutoMatchSupport.matchFallback(NEAR_ACC, line, usedTxnIds, excludedTxns,
          Collections.emptyList(), groups, DATE_TOL_DAYS, PCT_FIVE);

      assertEquals("the exact hit links one operation", 1, delta[0]);
      assertEquals("and creates nothing", 0, delta[1]);
      JSONObject group = groups.getJSONObject(0);
      assertFalse(group.has(AutoMatchSupport.KEY_NEAR_MATCH));
      assertEquals(FIN_MatchedTransaction.STRONG, group.getString(KEY_MATCH_LEVEL));
      assertEquals("no leftover travels on the group", 0, BigDecimal.ZERO
          .compareTo(new BigDecimal(group.getString(AutoMatchSupport.STATE_DIFFERENCE))));
      assertTrue(usedTxnIds.contains(T_NEAR));
    }
  }

  // ---------------------------------------------------------------------------
  // deviatesFrom — the single predicate both labelling paths read
  // ---------------------------------------------------------------------------

  /** A 26.62 movement on the line's own date deviates: 0.38 is a real amount gap. */
  @Test
  public void testDeviatesFromIsTrueForAnAmountOnlyDeviation() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-DEV-AMT", LINE_CREDIT, NO_DEBIT, today);

    assertTrue(AutoMatchSupport.deviatesFrom(line, nearTxn(T_NEAR, NEAR_AMOUNT, today)));
  }

  /**
   * An exact amount two calendar days away deviates too. It posts nothing when applied — that is a
   * separate question, decided by the group's {@code difference} — but the match is still not a
   * plain suggestion, so the badge must say so.
   */
  @Test
  public void testDeviatesFromIsTrueForADateOnlyDeviation() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-DEV-DATE", LINE_CREDIT, NO_DEBIT, today);

    assertTrue(AutoMatchSupport.deviatesFrom(line,
        nearTxn(T_NEAR, LINE_CREDIT, daysFrom(today, -2))));
  }

  /** Both axes off at once is still one boolean: deviating. */
  @Test
  public void testDeviatesFromIsTrueWhenBothAxesDeviate() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-DEV-BOTH", LINE_CREDIT, NO_DEBIT, today);

    assertTrue(AutoMatchSupport.deviatesFrom(line,
        nearTxn(T_NEAR, NEAR_AMOUNT, daysFrom(today, 2))));
  }

  /**
   * Neither axis deviates — the case the whole round-3 change exists to let through. Asserted
   * twice: once on identical timestamps, and once on two DIFFERENT times of the SAME calendar day.
   * The date axis is counted in calendar days, and a millisecond reading sneaking back in here
   * would repaint every imported statement line as "Con diferencia".
   */
  @Test
  public void testDeviatesFromIsFalseWhenNeitherAxisDeviates() {
    Date today = new Date();
    FIN_BankStatementLine sameInstant = datedLine("L-NODEV", LINE_CREDIT, NO_DEBIT, today);
    assertFalse(AutoMatchSupport.deviatesFrom(sameInstant, nearTxn(T_NEAR, LINE_CREDIT, today)));

    FIN_BankStatementLine afternoon =
        datedLine("L-NODEV-TIME", LINE_CREDIT, NO_DEBIT, dayAt(2026, 8, 28, 13));
    assertFalse("13:00 and 00:00 of one calendar day are not a date deviation",
        AutoMatchSupport.deviatesFrom(afternoon,
            nearTxn(T_NEAR, LINE_CREDIT, dayAt(2026, 8, 28, 0))));
  }

  // ---------------------------------------------------------------------------
  // classifyPendingLine <-> matchFallback must label the same pair the same way
  // ---------------------------------------------------------------------------

  /**
   * Both labelling paths over ONE fixture. {@code classifyPendingLine} paints the left-panel badge
   * and {@code matchFallback} builds the automatch modal's group; they reach the near match through
   * different code and must agree, or the same line reads "Sugerido" in one place and "Con
   * diferencia" in the other.
   *
   * <p>Fresh accumulators per call on purpose: each reader is asked about the line independently,
   * which is what happens in production (the panel is built by one request and the preview by
   * another).
   *
   * @return the state {@code classifyPendingLine} assigned; the group {@code matchFallback}
   *     proposed is appended to {@code groups}
   * @throws Exception if the DAL stubbing or the group building fails
   */
  private static String classifyAndPreview(FIN_BankStatementLine line,
      List<FIN_FinaccTransaction> pool, JSONArray groups) throws Exception {
    try (MockedConstruction<FIN_MatchingTransaction> mc = mockNoStandardMatch();
        MockedStatic<OBDal> obDal = mockUnreconciledPool(pool)) {
      String state = AutoMatchSupport.classifyPendingLine(nearAccount(), line,
          Collections.emptyList(), DATE_TOL_DAYS, PCT_FIVE, new HashSet<>(),
          new java.util.ArrayList<>());
      AutoMatchSupport.matchFallback(NEAR_ACC, line, new HashSet<>(), new java.util.ArrayList<>(),
          Collections.emptyList(), groups, DATE_TOL_DAYS, PCT_FIVE);
      return state;
    }
  }

  /**
   * Agreement, exact hit: {@code matchFallback} emits NO {@code nearMatch} flag, so
   * {@code classifyPendingLine} must say {@code suggested}. Before round 3 the classifier returned
   * {@code difference} for anything the near-match pass found, whatever it found — which is how a
   * badge could contradict the modal it is supposed to summarise.
   *
   * @throws Exception if the DAL stubbing or the JSON assertions fail
   */
  @Test
  public void testClassifyAndMatchFallbackAgreeOnAnExactHit() throws Exception {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-AGREE-EXACT", LINE_CREDIT, NO_DEBIT, today);
    JSONArray groups = new JSONArray();

    String state =
        classifyAndPreview(line, Collections.singletonList(nearTxn(T_NEAR, LINE_CREDIT, today)),
            groups);

    assertFalse("the modal offers this as a plain suggestion",
        groups.getJSONObject(0).has(AutoMatchSupport.KEY_NEAR_MATCH));
    assertEquals("so the left panel must not badge it as a difference",
        AutoMatchSupport.STATE_SUGGESTED, state);
  }

  /**
   * Agreement, amount deviation: {@code matchFallback} DOES flag it, so
   * {@code classifyPendingLine} must say {@code difference}. The negative twin of the test above —
   * together they pin the biconditional rather than one direction of it, so "always suggested"
   * cannot pass both.
   *
   * @throws Exception if the DAL stubbing or the JSON assertions fail
   */
  @Test
  public void testClassifyAndMatchFallbackAgreeOnAnAmountDeviation() throws Exception {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-AGREE-AMT", LINE_CREDIT, NO_DEBIT, today);
    JSONArray groups = new JSONArray();

    String state =
        classifyAndPreview(line, Collections.singletonList(nearTxn(T_NEAR, NEAR_AMOUNT, today)),
            groups);

    assertTrue("the modal flags this one as a near match",
        groups.getJSONObject(0).optBoolean(AutoMatchSupport.KEY_NEAR_MATCH, false));
    assertEquals("so the left panel must badge it as a difference",
        AutoMatchSupport.STATE_DIFFERENCE, state);
  }

  // ---------------------------------------------------------------------------
  // signalGroupTolerance (formerly computeAmountTolerance)
  // ---------------------------------------------------------------------------

  /**
   * When the percentage is 0 the floor tolerance (0.01) is returned, preserving the
   * behaviour that existed before per-account tolerances were introduced.
   */
  @Test
  public void testSignalGroupToleranceZeroPctReturnsFloor() {
    BigDecimal result = AutoMatchSupport.signalGroupTolerance(
        new BigDecimal("100.00"), BigDecimal.ZERO);
    assertEquals(0, new BigDecimal("0.01").compareTo(result));
  }

  /**
   * 2% of 100.00 = 2.00 which exceeds the floor of 0.01, so the derived value is returned.
   */
  @Test
  public void testSignalGroupTolerance2PctOf100Returns2() {
    BigDecimal result = AutoMatchSupport.signalGroupTolerance(
        new BigDecimal("100.00"), new BigDecimal("2"));
    assertEquals(0, new BigDecimal("2.00").compareTo(result));
  }

  /**
   * 10% of 50.00 = 5.00 which exceeds the floor of 0.01, so the derived value is returned.
   */
  @Test
  public void testSignalGroupTolerance10PctOf50Returns5() {
    BigDecimal result = AutoMatchSupport.signalGroupTolerance(
        new BigDecimal("50.00"), new BigDecimal("10"));
    assertEquals(0, new BigDecimal("5.00").compareTo(result));
  }

  /**
   * A null percentage behaves the same as 0 — the floor tolerance is returned.
   */
  @Test
  public void testSignalGroupToleranceNullPctReturnsFloor() {
    BigDecimal result = AutoMatchSupport.signalGroupTolerance(
        new BigDecimal("100.00"), null);
    assertEquals(0, new BigDecimal("0.01").compareTo(result));
  }

  /**
   * <b>Cross-class contrast, half two of two.</b> {@link AutoMatchSupport#signalGroupTolerance} and
   * {@link NearMatchSupport#differenceTolerance} read the SAME {@code EM_ETGO_Amount_Tolerance}
   * column with deliberately opposite conventions, and the ETP-4965 split put them in two different
   * classes — which makes them easier, not harder, to confuse. Asserting the divergence explicitly
   * is the point: collapsing them back into one method is the support trap the rename came to
   * remove.
   *
   * <p>This half reads from the {@code AutoMatchSupport} side, so it fails when the 1:N rounding
   * slack loses its one-cent floor or starts returning null. Its twin,
   * {@code NearMatchSupportTest#testDifferenceToleranceIsNotSignalGroupTolerance}, reads from the
   * other side and fails when the POSTING gate is given a floor. Whichever class a future change
   * touches, one of the two runs — that is why the pair survived the move instead of collapsing
   * into a single test in one file.
   */
  @Test
  public void testSignalGroupToleranceIsNotDifferenceTolerance() {
    BigDecimal target = new BigDecimal("27.00");
    // 0% → one cent of rounding slack for a 1:N SUM here, but nothing may be POSTED there.
    assertEquals(0, new BigDecimal("0.01")
        .compareTo(AutoMatchSupport.signalGroupTolerance(target, BigDecimal.ZERO)));
    assertNull(NearMatchSupport.differenceTolerance(target, BigDecimal.ZERO));
    // A percentage below the floor is raised to 0.01 for the sum, but NOT for the posting gate.
    assertEquals(0, new BigDecimal("0.01")
        .compareTo(AutoMatchSupport.signalGroupTolerance(target, new BigDecimal("0.001"))));
    assertEquals(0, BigDecimal.ZERO
        .compareTo(NearMatchSupport.differenceTolerance(target, new BigDecimal("0.001"))));
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

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-4965 — the §5.1 classification matrix, as classifyPendingLine reports it
  //
  // The whole matrix, row by row, with the ticket's own numbers: a 27.00 statement line, a 26.62
  // movement (0.38 = 1.41% deviation), a 5% amount tolerance and the default 3-day date tolerance.
  //
  // These assert the STATE the left-panel filter paints. The search that feeds them —
  // NearMatchSupport.findNearMatch, its tolerances, ordering and accumulator contract — is unit-
  // tested in NearMatchSupportTest; a row asserted on both sides is asserted twice on purpose.
  //
  //   amount dev | date dev            | state
  //   -----------+---------------------+-------------
  //   0          | 0                   | suggested
  //   0          | > 0, <= date tol    | difference
  //   > 0, <= tol| 0                   | difference
  //   > 0, <= tol| > 0, <= date tol    | difference
  //   > tol      | any                 | pending
  //   any        | > date tol          | pending
  //
  // The two tolerances are INDEPENDENT: amount 0% only collapses the third and fourth rows onto
  // "exact amount", it does not switch the second row off. See the pair of 0% tests below.
  //
  // ROUND 3 changed how row 1 is REACHED, not what it says. The near-match pass used to skip the
  // exact-exact candidate entirely, so row 1 could only ever come from Core's pass 1; when Core
  // missed the pair (its criteria are narrower) the line silently got a worse candidate instead.
  // The pass now returns exact hits too and the state is read off deviatesFrom, so row 1 has a
  // second, equally valid route into it — see the testClassifyAndMatchFallbackAgree* pair.
  // ═══════════════════════════════════════════════════════════════════════════

  private static final String LINE_CREDIT = "27.00";
  private static final String NO_DEBIT = "0.00";
  /** 26.62 against 27.00 — a 0.38 deviation, i.e. 1.41%, inside the 5% tolerance. */
  private static final String NEAR_AMOUNT = "26.62";
  private static final BigDecimal PCT_FIVE = new BigDecimal("5");
  private static final int DATE_TOL_DAYS = 3;
  private static final String NEAR_ACC = "ACC-NEAR";
  private static final String T_NEAR = "T-NEAR";

  private static Date daysFrom(Date base, int days) {
    Calendar cal = Calendar.getInstance();
    cal.setTime(base);
    cal.add(Calendar.DAY_OF_MONTH, days);
    return cal.getTime();
  }

  /** A statement line with a fixed amount and transaction date. */
  private static FIN_BankStatementLine datedLine(String id, String credit, String debit, Date date) {
    FIN_BankStatementLine line = bslLine(id, credit, debit);
    lenient().when(line.getTransactionDate()).thenReturn(date);
    lenient().when(line.getBpartnername()).thenReturn("");
    return line;
  }

  /** A bare unreconciled transaction (no partner, no reference) with an amount and a date. */
  private static FIN_FinaccTransaction nearTxn(String id, String amount, Date date) {
    BigDecimal amt = new BigDecimal(amount);
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    lenient().when(t.getId()).thenReturn(id);
    lenient().when(t.getBusinessPartner()).thenReturn(null);
    lenient().when(t.getFinPayment()).thenReturn(null);
    lenient().when(t.getDepositAmount())
        .thenReturn(amt.signum() >= 0 ? amt : BigDecimal.ZERO);
    lenient().when(t.getPaymentAmount())
        .thenReturn(amt.signum() >= 0 ? BigDecimal.ZERO : amt.abs());
    lenient().when(t.getTransactionDate()).thenReturn(date);
    return t;
  }

  /**
   * Mocks the DAL seam {@code loadUnreconciledSameSign} reaches through, so {@code findSignalGroup}
   * and the near-match pass {@code classifyPendingLine} delegates to both see {@code pool} as the
   * account's whole unreconciled set. Caller closes it.
   */
  private static MockedStatic<OBDal> mockUnreconciledPool(List<FIN_FinaccTransaction> pool) {
    MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    lenient().when(dal.getSession()).thenReturn(session);
    @SuppressWarnings("unchecked")
    org.hibernate.query.Query<FIN_FinaccTransaction> query =
        mock(org.hibernate.query.Query.class);
    lenient().when(session.createQuery(anyString(), eq(FIN_FinaccTransaction.class)))
        .thenReturn(query);
    lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
    lenient().when(query.list()).thenReturn(pool);
    return obDal;
  }

  /**
   * Core's standard algorithm finds nothing — the only way to reach
   * {@link NearMatchSupport#findNearMatch}, since {@code StandardMatchingAlgorithm} searches by
   * EXACT amount AND EXACT date and therefore never sees a deviating movement at all (that is the
   * bug this ticket fixes). Caller closes it.
   */
  private static MockedConstruction<FIN_MatchingTransaction> mockNoStandardMatch() {
    return mockConstruction(FIN_MatchingTransaction.class, (m, ctx) -> {
      FIN_MatchedTransaction nomatch = mock(FIN_MatchedTransaction.class);
      lenient().when(nomatch.getTransaction()).thenReturn(mock(FIN_FinaccTransaction.class));
      lenient().when(nomatch.getMatchLevel()).thenReturn(FIN_MatchedTransaction.NOMATCH);
      when(m.match(any(), any())).thenReturn(nomatch);
    });
  }

  /** The account under test: has an algorithm configured (so standardMatch runs) and an id. */
  private static FIN_FinancialAccount nearAccount() {
    FIN_FinancialAccount account = accountWithAlgorithm("com.example.DummyAlgo");
    lenient().when(account.getId()).thenReturn(NEAR_ACC);
    return account;
  }

  /** Classifies {@code line} against {@code pool} with the ticket's tolerances, fresh accumulators. */
  private static String classifyAgainst(FIN_BankStatementLine line,
      List<FIN_FinaccTransaction> pool) {
    try (MockedConstruction<FIN_MatchingTransaction> mc = mockNoStandardMatch();
        MockedStatic<OBDal> obDal = mockUnreconciledPool(pool)) {
      return AutoMatchSupport.classifyPendingLine(nearAccount(), line, Collections.emptyList(),
          DATE_TOL_DAYS, PCT_FIVE, new HashSet<>(), new java.util.ArrayList<>());
    }
  }

  /**
   * Matrix row 1 — no deviation at all is a SUGGESTION, not a difference. Core's standard algorithm
   * finds the pair here, so the classifier reports it as suggested and never reaches the near-match
   * pass.
   *
   * <p>Round 3 added the OTHER way into this row: when Core does NOT find the pair, the near-match
   * pass returns it and the classifier reads {@link AutoMatchSupport#deviatesFrom} to label it —
   * still {@code suggested}. That route is asserted in
   * {@link #testClassifyAndMatchFallbackAgreeOnAnExactHit}; the search-level half is
   * {@code NearMatchSupportTest#testExactAmountExactDateIsStillReturnedSoRankingCanSeeIt}.
   */
  @Test
  public void testExactAmountExactDateIsSuggested() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-EXACT", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction exact = nearTxn(T_NEAR, LINE_CREDIT, today);

    FIN_MatchedTransaction matched = mock(FIN_MatchedTransaction.class);
    lenient().when(matched.getTransaction()).thenReturn(exact);
    lenient().when(matched.getMatchLevel()).thenReturn(FIN_MatchedTransaction.STRONG);
    try (MockedConstruction<FIN_MatchingTransaction> mc =
        mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
            when(m.match(any(), any())).thenReturn(matched))) {
      assertEquals(AutoMatchSupport.STATE_SUGGESTED,
          AutoMatchSupport.classifyPendingLine(nearAccount(), line, Collections.emptyList(),
              DATE_TOL_DAYS, PCT_FIVE, new HashSet<>(), new java.util.ArrayList<>()));
    }
  }

  /**
   * Matrix row 2 — the amount matches to the cent but the movement is 2 days away (tolerance 3).
   * Core cannot see it (it searches by exact date), so without
   * {@link NearMatchSupport#findNearMatch} this line is stuck on "Pendiente" forever.
   */
  @Test
  public void testExactAmountTwoDaysApartIsDifference() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-DATE-ONLY", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction twoDaysLater = nearTxn(T_NEAR, LINE_CREDIT, daysFrom(today, 2));

    assertEquals(AutoMatchSupport.STATE_DIFFERENCE,
        classifyAgainst(line, Collections.singletonList(twoDaysLater)));
  }

  /**
   * Matrix row 3 — the ticket's own reported case: a 27.00 line against a 26.62 movement of the
   * same date. 0.38 is 1.41% of the line, inside the 5% tolerance.
   */
  @Test
  public void testAmountWithinToleranceSameDateIsDifference() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-AMT", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, today);

    assertEquals(AutoMatchSupport.STATE_DIFFERENCE,
        classifyAgainst(line, Collections.singletonList(near)));
  }

  /** Matrix row 4 — both deviations present, each within its own tolerance. */
  @Test
  public void testAmountAndDateBothWithinToleranceIsDifference() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-BOTH", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, daysFrom(today, 2));

    assertEquals(AutoMatchSupport.STATE_DIFFERENCE,
        classifyAgainst(line, Collections.singletonList(near)));
  }

  /**
   * Matrix row 5 — a 20.00 movement deviates by 7.00 from the 27.00 line, far past the 1.35 limit.
   * The automatch must not propose it: an out-of-tolerance gap is not an adjustment, it is a
   * different document. The search-level twin is
   * {@code NearMatchSupportTest#testAmountOutsideToleranceIsNeverANearMatch}.
   */
  @Test
  public void testAmountOutsideToleranceStaysPending() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-OUT-AMT", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction far = nearTxn(T_NEAR, "20.00", today);

    assertEquals(AutoMatchSupport.STATE_PENDING,
        classifyAgainst(line, Collections.singletonList(far)));
  }

  /**
   * Matrix row 6 — an EXACT amount 4 days away with a 3-day tolerance is still out of reach. The
   * date window is a hard bound, not a preference.
   */
  @Test
  public void testExactAmountOutsideDateWindowStaysPending() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-OUT-DATE", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction fourDaysLater = nearTxn(T_NEAR, LINE_CREDIT, daysFrom(today, 4));

    assertEquals(AutoMatchSupport.STATE_PENDING,
        classifyAgainst(line, Collections.singletonList(fourDaysLater)));
  }

  /**
   * A 0% amount tolerance bounds the AMOUNT dimension and only that one: no amount deviation is
   * ever accepted, so the ticket's own 26.62-against-27.00 case is not detected at all and the line
   * stays Pendiente. This is what guarantees an account that never configured a percentage can
   * never receive an automatic accounting entry — {@link NearMatchSupport#differenceTolerance}
   * returns null and {@code findNearMatch} degrades to "exact amount only", never to "any amount"
   * (both asserted in {@code NearMatchSupportTest#testZeroAmountToleranceRejectsAnAmountDeviation}).
   *
   * <p>It is NOT a master switch over the whole feature; see the date-only twin below.
   */
  @Test
  public void testZeroAmountToleranceRejectsAnAmountDeviation() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-TOL0-AMT", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, today);

    try (MockedConstruction<FIN_MatchingTransaction> mc = mockNoStandardMatch();
        MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(near))) {
      assertEquals(AutoMatchSupport.STATE_PENDING,
          AutoMatchSupport.classifyPendingLine(nearAccount(), line, Collections.emptyList(),
              DATE_TOL_DAYS, BigDecimal.ZERO, new HashSet<>(), new java.util.ArrayList<>()));
    }
  }

  /**
   * <b>DETECTION is not POSTING — the distinction this whole ticket turns on.</b>
   *
   * <p>The two account fields govern independent dimensions. {@code EM_ETGO_Amount_Tolerance}
   * bounds how far the AMOUNT may drift and is the only thing that can authorise an accounting
   * entry; {@code EM_ETGO_Date_Tolerance} bounds how many days apart the two may be, defaults to 3
   * on every account ever created, and stays in force at 0% amount tolerance. A date-only deviation
   * creates no accounting entry at all, so the safety reasoning behind 0% simply does not apply to
   * it.
   *
   * <p>The canonical case: a 100.00 line of the 28th against a 100.00 movement of the 26th, on an
   * account at 0% amount / 3 days, classifies as a DIFFERENCE — and reconciling it posts nothing,
   * because the gap is zero (the posting side is asserted in
   * {@code ReconciliationDifferenceSupportTest}). This is the user-visible end of the contract; the
   * search-level requirement that {@link NearMatchSupport#findNearMatch} keep searching with a null
   * {@code amtTolerance} instead of bailing out is asserted in
   * {@code NearMatchSupportTest#testZeroAmountToleranceStillDetectsADateOnlyDeviation}.
   */
  @Test
  public void testZeroAmountToleranceStillDetectsADateOnlyDeviation() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-TOL0-DATE", "100.00", NO_DEBIT, today);
    FIN_FinaccTransaction twoDaysEarlier = nearTxn(T_NEAR, "100.00", daysFrom(today, -2));

    try (MockedConstruction<FIN_MatchingTransaction> mc = mockNoStandardMatch();
        MockedStatic<OBDal> obDal =
            mockUnreconciledPool(Collections.singletonList(twoDaysEarlier))) {
      assertEquals(AutoMatchSupport.STATE_DIFFERENCE,
          AutoMatchSupport.classifyPendingLine(nearAccount(), line, Collections.emptyList(),
              DATE_TOL_DAYS, BigDecimal.ZERO, new HashSet<>(), new java.util.ArrayList<>()));
    }
  }

  /**
   * The accumulator contract, applied to the new path.
   *
   * <p>Two pending lines of the SAME amount classified in ONE pass share {@code usedTxnIds} /
   * {@code excludedTxns}. There is a single candidate movement, so the first line claims it and
   * gets {@code difference}; the second must fall through to {@code pending}. Getting this wrong
   * makes the left panel's "Con diferencia" counter promise more than an actual automatch run can
   * apply — exactly the defect the ETP-4951 refactor introduced these accumulators to prevent.
   */
  @Test
  public void testNearMatchHonoursSharedAccumulatorAcrossLines() {
    Date today = new Date();
    FIN_BankStatementLine line1 = datedLine("L-ACC-1", LINE_CREDIT, NO_DEBIT, today);
    FIN_BankStatementLine line2 = datedLine("L-ACC-2", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction onlyCandidate = nearTxn(T_NEAR, NEAR_AMOUNT, today);

    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new java.util.ArrayList<>();

    try (MockedConstruction<FIN_MatchingTransaction> mc = mockNoStandardMatch();
        MockedStatic<OBDal> obDal =
            mockUnreconciledPool(Collections.singletonList(onlyCandidate))) {
      FIN_FinancialAccount account = nearAccount();

      String first = AutoMatchSupport.classifyPendingLine(account, line1, Collections.emptyList(),
          DATE_TOL_DAYS, PCT_FIVE, usedTxnIds, excludedTxns);
      assertEquals(AutoMatchSupport.STATE_DIFFERENCE, first);
      assertTrue("a near-match must consume its candidate in BOTH accumulators",
          usedTxnIds.contains(T_NEAR));
      assertTrue(excludedTxns.contains(onlyCandidate));

      String second = AutoMatchSupport.classifyPendingLine(account, line2, Collections.emptyList(),
          DATE_TOL_DAYS, PCT_FIVE, usedTxnIds, excludedTxns);
      assertEquals("the only candidate is already claimed — the second line cannot reuse it",
          AutoMatchSupport.STATE_PENDING, second);
    }
  }

  /**
   * Order of precedence: a 1:N signal group still wins over a 1:1 near-match. Two same-partner
   * movements summing EXACTLY to the line are a better answer than one movement that is merely
   * close, and they post nothing.
   */
  @Test
  public void testSignalGroupTakesPrecedenceOverNearMatch() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-PREC", LINE_CREDIT, NO_DEBIT, today);
    BusinessPartner bp = mock(BusinessPartner.class);
    lenient().when(bp.getId()).thenReturn("BP-PREC");
    FIN_FinaccTransaction half1 = txnWithPartnerAndDate("T-H1", "13.50", bp, today);
    FIN_FinaccTransaction half2 = txnWithPartnerAndDate("T-H2", "13.50", bp, today);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, today);

    assertEquals(AutoMatchSupport.STATE_SUGGESTED,
        classifyAgainst(line, Arrays.asList(half1, half2, near)));
  }

  /**
   * The outflow twin of the reference case: a -27.00 line against a -26.62 payment. Sign handling
   * is the highest-risk part of this feature, so the negative direction is asserted end to end and
   * not assumed to follow from the positive one.
   */
  @Test
  public void testOutflowLineWithinToleranceIsDifference() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-OUTFLOW", NO_DEBIT, LINE_CREDIT, today);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, "-26.62", today);

    assertEquals(AutoMatchSupport.STATE_DIFFERENCE,
        classifyAgainst(line, Collections.singletonList(near)));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-4965 — the date axis is CALENDAR DAYS, not elapsed milliseconds
  //
  // Every date column this feature reads is a timestamp, not a date: FIN_BankStatementLine.datetrx,
  // FIN_FinaccTransaction.statementdate and .dateacct are all `timestamp without time zone`, and
  // rows carrying a real time component exist in production (imported statements above all). A
  // millisecond distance therefore turns 13:00 and 00:00 of the SAME day into a "date deviation",
  // which silently demotes matrix row 1 (exact amount, exact date = SUGGESTED) to "con diferencia"
  // and makes the tie-break order candidates by clock time instead of by day.
  //
  // NearMatchSupport.dayDistance is what implements the day-based reading, and NearMatchSupportTest
  // pins it directly (including the tie-break, whose candidates the clock and the calendar order
  // differently, and the null-date convention). The two tests below are the consequence side that
  // only classifyPendingLine can show: the STATE a same-day timestamp pair produces.
  //
  // NOTE: they say nothing about withinDateWindow, which still measures N days as N x 24h. That is
  // pre-existing, shared with findSignalGroup/standardMatch, and out of this ticket's scope. Every
  // date pair used here sits comfortably inside the 3-day window under EITHER reading, so fixing
  // that window later cannot break these tests.
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * A local-zone instant at a named calendar day and hour, with minutes, seconds and millis zeroed.
   * Goes through {@code Calendar}, i.e. the very zone {@code ZoneId.systemDefault()} resolves to in
   * the production epoch-day conversion, so the calendar day a test names is the calendar day the
   * code under test sees on any CI host.
   *
   * <p>Late August on purpose: no inhabited time zone shifts its DST offset there, in either
   * hemisphere, so no test below can start failing because a host runs in Santiago or Sydney.
   */
  private static Date dayAt(int year, int month, int dayOfMonth, int hour) {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(year, month - 1, dayOfMonth, hour, 0, 0);
    return cal.getTime();
  }

  /**
   * <b>Regression, matrix row 1, as the user sees it.</b> A statement line at 13:00 and its
   * movement at 00:00 of the SAME calendar day, for the very same amount, are ZERO days apart — a
   * plain suggestion. Measured in millis they are 13 hours apart, which reads as a date deviation
   * and hands the exact match over to the near-match pass: the line then shows "Con diferencia" for
   * a match that deviates in nothing at all, and the filter stops meaning what its name says.
   * Nothing in the timestamps is unusual — importers routinely stamp a real time on the statement
   * side and midnight on the movement side.
   *
   * <p>The unit underneath ({@link NearMatchSupport#dayDistance} returning 0, and the search
   * treating the pair as non-deviating) is asserted in
   * {@code NearMatchSupportTest#testSameCalendarDayDifferentTimesExactAmountDeviatesInNothing}.
   * This test is the half that made the bug visible: the STATE the left panel would have shown.
   */
  @Test
  public void testSameCalendarDayDifferentTimesExactAmountIsNotADifference() {
    Date lineAfternoon = dayAt(2026, 8, 28, 13);
    Date movementMidnight = dayAt(2026, 8, 28, 0);
    FIN_BankStatementLine line = datedLine("L-SAMEDAY-EXACT", LINE_CREDIT, NO_DEBIT, lineAfternoon);
    FIN_FinaccTransaction exact = nearTxn(T_NEAR, LINE_CREDIT, movementMidnight);

    // With Core blinded, round 3 makes this a POSITIVE assertion: the near-match pass now returns
    // the pair (it must, or an exact hit Core missed stays invisible) and labels it from the
    // deviation it actually has — none. Before round 3 the pass hid the pair and the line fell
    // through to `pending`, so only "not a difference" could be asserted here.
    try (MockedConstruction<FIN_MatchingTransaction> mc = mockNoStandardMatch();
        MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(exact))) {
      String state = AutoMatchSupport.classifyPendingLine(nearAccount(), line,
          Collections.emptyList(), DATE_TOL_DAYS, PCT_FIVE, new HashSet<>(),
          new java.util.ArrayList<>());

      assertNotEquals("a same-day exact match is not a difference",
          AutoMatchSupport.STATE_DIFFERENCE, state);
      assertEquals("it is the plain suggestion the near-match pass found",
          AutoMatchSupport.STATE_SUGGESTED, state);
    }

    // And what it positively is, in production: Core's standard algorithm does find this pair, so
    // the line reads "suggested" — row 1 of the matrix, reached with a real time component present.
    FIN_MatchedTransaction matched = mock(FIN_MatchedTransaction.class);
    lenient().when(matched.getTransaction()).thenReturn(exact);
    lenient().when(matched.getMatchLevel()).thenReturn(FIN_MatchedTransaction.STRONG);
    try (MockedConstruction<FIN_MatchingTransaction> mc =
        mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
            when(m.match(any(), any())).thenReturn(matched))) {
      assertEquals(AutoMatchSupport.STATE_SUGGESTED,
          AutoMatchSupport.classifyPendingLine(nearAccount(), line, Collections.emptyList(),
              DATE_TOL_DAYS, PCT_FIVE, new HashSet<>(), new java.util.ArrayList<>()));
    }
  }

  /**
   * The other side of that fix: collapsing the date to a calendar day must not collapse the AMOUNT
   * with it. A 27.00 line at 09:00 against a 26.62 movement at 19:00 of the same day is still a
   * difference — the deviation was never the date, it is the 0.38. Guards against "fixing" row 1 by
   * turning it into "same day means never a difference", which would silently delete the ticket's
   * own reported case (matrix row 3). The search-level twin is
   * {@code NearMatchSupportTest#testSameCalendarDayDifferentTimesAmountDeviationIsStillANearMatch}.
   */
  @Test
  public void testSameCalendarDayDifferentTimesAmountDeviationIsStillDifference() {
    Date lineMorning = dayAt(2026, 8, 28, 9);
    Date movementEvening = dayAt(2026, 8, 28, 19);
    FIN_BankStatementLine line = datedLine("L-SAMEDAY-AMT", LINE_CREDIT, NO_DEBIT, lineMorning);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, movementEvening);

    assertEquals(AutoMatchSupport.STATE_DIFFERENCE,
        classifyAgainst(line, Collections.singletonList(near)));
  }

  // ---------------------------------------------------------------------------
  // Builders
  // ---------------------------------------------------------------------------

  /** A bank-statement line mock with a credit/debit amount and a fixed id (no date). */
  private static FIN_BankStatementLine bslLine(String id, String credit, String debit) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    lenient().when(line.getId()).thenReturn(id);
    lenient().when(line.getCramount()).thenReturn(new BigDecimal(credit));
    lenient().when(line.getDramount()).thenReturn(new BigDecimal(debit));
    lenient().when(line.getDescription()).thenReturn(ANY_DESCRIPTION);
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

  /** A business partner mock that answers only to {@code getName()}. */
  private static BusinessPartner partnerNamed(String name) {
    BusinessPartner bp = mock(BusinessPartner.class);
    lenient().when(bp.getName()).thenReturn(name);
    return bp;
  }

  /** A payment mock carrying a document number, a description and (optionally) a partner. */
  private static FIN_Payment paymentWith(String documentNo, String description,
      BusinessPartner bp) {
    FIN_Payment p = mock(FIN_Payment.class);
    lenient().when(p.getDocumentNo()).thenReturn(documentNo);
    lenient().when(p.getDescription()).thenReturn(description);
    lenient().when(p.getBusinessPartner()).thenReturn(bp);
    return p;
  }

  /**
   * A transaction mock for the {@code txnToJson} description/partner tests: its own description and
   * partner may each be absent, so the fallback to the payment's can be exercised independently.
   * A fixed 10.00 deposit keeps the amount out of the way of what these tests are about.
   */
  private static FIN_FinaccTransaction describedTxn(String id, String description,
      BusinessPartner bp, FIN_Payment payment) {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    lenient().when(t.getId()).thenReturn(id);
    lenient().when(t.getDescription()).thenReturn(description);
    lenient().when(t.getBusinessPartner()).thenReturn(bp);
    lenient().when(t.getFinPayment()).thenReturn(payment);
    lenient().when(t.getDepositAmount()).thenReturn(new BigDecimal("10.00"));
    lenient().when(t.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    lenient().when(t.getTransactionDate()).thenReturn(null);
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
