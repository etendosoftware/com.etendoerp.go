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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

import com.etendoerp.go.schemaforge.BankBounceDetector.Candidate;
import com.etendoerp.go.schemaforge.BankBounceDetector.Pair;
import com.etendoerp.go.schemaforge.BankBounceDetector.StatementLine;

/**
 * The bounce-pair suggestions of the reconciliation ({@code bank-bounce-suggestions}, ETP-5581):
 * how {@code autoMatch}, the {@code pendingLines} panel and {@code applySuggestions} compose the
 * detected pairs with the transaction groups, and that with the flag off every answer is the one
 * the reconciliation gave before the feature existed.
 *
 * <p>The detection itself ({@link BankBounceDetector}) and the pair validation
 * ({@link BankBouncePairService#validate}) have their own tests; here they are stubbed at their
 * static boundary so each case pins the composition: which lines a pair reserves, which pairs are
 * offered, what the answers carry and in what order {@code applySuggestions} writes.
 *
 * <p>Statement adjacency reaches the offering only through the pairs: two adjacent ambiguous pairs
 * may share a neighbour and are both offered, narrowing keeps {@code adjacent}, and every group and
 * annotation carries it.
 *
 * <p>Kept apart from {@code ReconciliationHandlerTest} (6,500 lines covering every other route):
 * this class is the flag's test, the one {@code flags-registry.json} names for it.
 *
 * @covers com.etendoerp.go.schemaforge.BankBounceSuggestions
 * @covers com.etendoerp.go.schemaforge.ReconciliationHandler
 * @covers com.etendoerp.go.schemaforge.ReconciliationHandlerSupport
 * @covers com.etendoerp.go.schemaforge.BankBouncePairService
 * @covers com.etendoerp.go.schemaforge.ReconciliationFlowSupport
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ReconciliationHandlerBounceSuggestionsTest {

  private static final String ACC_ID = "acc-1";
  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  /** The detection-model lines of the fixtures, by id. */
  private static final StatementLine O1 = model("O1", -100, DAY);
  private static final StatementLine B1 = model("B1", 100, DAY);
  private static final StatementLine O2 = model("O2", -50, DAY);
  private static final StatementLine B2 = model("B2", 50, DAY.plusDays(3));
  private static final StatementLine B3 = model("B3", 30, DAY.plusDays(2));
  private static final StatementLine C1 = model("C1", -30, DAY);
  private static final StatementLine C2 = model("C2", -30, DAY.plusDays(1));

  /** High pair: bounce wording on the same day. */
  private static final Pair HIGH = new Pair(O1, B1, Collections.emptyList(),
      List.of(BankBounceDetector.SIGNAL_RETURN_TEXT), BankBounceDetector.CONFIDENCE_HIGH);
  /** Review pair: counterparty only, a few days later. */
  private static final Pair REVIEW = new Pair(O2, B2, Collections.emptyList(),
      List.of(BankBounceDetector.SIGNAL_COUNTERPARTY), BankBounceDetector.CONFIDENCE_REVIEW);
  /** Ambiguous review pair: two equally good originals for B3. */
  private static final Pair AMBIGUOUS = new Pair(null, B3,
      List.of(new Candidate(C1, List.of(BankBounceDetector.SIGNAL_COUNTERPARTY)),
          new Candidate(C2, Collections.emptyList())),
      List.of(BankBounceDetector.SIGNAL_COUNTERPARTY), BankBounceDetector.CONFIDENCE_REVIEW);

  private ReconciliationHandler handler;
  private FIN_FinancialAccount account;

  @Before
  public void setUp() {
    handler = spy(new ReconciliationHandler());
    doNothing().when(handler).doRollbackAndClose();
    doReturn(new BigDecimal[] { BigDecimal.valueOf(3), BigDecimal.ZERO })
        .when(handler).loadTolerances(any());
    account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));
  }

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------------------
  // fixtures
  // ---------------------------------------------------------------------------

  private static StatementLine model(String id, long amount, LocalDate date) {
    return new StatementLine(id, date, 10, "", "", BigDecimal.valueOf(amount), true);
  }

  /** A DAL line the auto-match loop and {@code AutoMatchSupport.lineToJson} can read. */
  private static FIN_BankStatementLine dalLine(String id, long amount) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getCramount()).thenReturn(amount > 0 ? BigDecimal.valueOf(amount) : BigDecimal.ZERO);
    when(line.getDramount()).thenReturn(amount < 0 ? BigDecimal.valueOf(-amount) : BigDecimal.ZERO);
    when(line.getDescription()).thenReturn("desc " + id);
    when(line.getReferenceNo()).thenReturn("");
    when(line.getBpartnername()).thenReturn("Partner " + id);
    return line;
  }

  /** A pending line of {@link #ACC_ID} on a processed statement, as {@code prepareGroup} reads it. */
  private static FIN_BankStatementLine ownedLine(String id, long amount) {
    FIN_BankStatementLine line = dalLine(id, amount);
    FIN_BankStatement bs = mock(FIN_BankStatement.class);
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getId()).thenReturn(ACC_ID);
    when(bs.getAccount()).thenReturn(acc);
    when(bs.isProcessed()).thenReturn(Boolean.TRUE);
    when(line.getBankStatement()).thenReturn(bs);
    return line;
  }

  private static FIN_FinaccTransaction trx(String id, long amount) {
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getId()).thenReturn(ACC_ID);
    when(trx.getId()).thenReturn(id);
    when(trx.getAccount()).thenReturn(acc);
    when(trx.getDepositAmount()).thenReturn(amount > 0 ? BigDecimal.valueOf(amount) : BigDecimal.ZERO);
    when(trx.getPaymentAmount()).thenReturn(amount < 0 ? BigDecimal.valueOf(-amount) : BigDecimal.ZERO);
    return trx;
  }

  /** A plan over the given pairs, with no DAL lines (the line JSON then carries only the id). */
  private static BankBounceSuggestions.Plan plan(Pair... pairs) {
    return new BankBounceSuggestions.Plan(Arrays.asList(pairs), Collections.emptyMap(), true);
  }

  /** A transaction group as the auto-match loop builds it: only its line id matters here. */
  private static JSONObject transactionGroup(String lineId) throws Exception {
    return new JSONObject()
        .put("statementLine", new JSONObject().put("id", lineId))
        .put("operations", new JSONArray());
  }

  /** A pending-panel row as the merged SQL rows reach {@code summarizePendingLines}. */
  private static JSONObject row(String id, long amount) throws Exception {
    return new JSONObject().put("id", id).put("amount", BigDecimal.valueOf(amount).toPlainString())
        .put("pendingAmount", BigDecimal.valueOf(amount).toPlainString())
        .put("reconcileStatus", "PENDING");
  }

  private static JSONObject data(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  private static Set<String> ids(List<Pair> pairs) {
    Set<String> out = new HashSet<>();
    for (Pair p : pairs) {
      out.add(p.groupKey());
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Plan: precedence of the pairs
  // ---------------------------------------------------------------------------

  /** Only the high pairs reserve lines before transaction matching. */
  @Test
  public void highPairsReserveTheirTwoLinesAndReviewPairsReserveNothing() {
    assertEquals(new HashSet<>(Arrays.asList("O1", "B1")),
        plan(HIGH, REVIEW, AMBIGUOUS).reservedLineIds());
  }

  /**
   * A plain review pair is offered only when none of its lines was taken by a transaction group;
   * an ambiguous pair only loses the candidates that were taken.
   */
  @Test
  public void reviewPairsAreOfferedOnlyWhenNoneOfTheirLinesIsTaken() {
    BankBounceSuggestions.Plan plan = plan(HIGH, REVIEW, AMBIGUOUS);

    assertEquals(Arrays.asList(HIGH, REVIEW, AMBIGUOUS), plan.offered(Collections.emptySet()));
    assertEquals(Arrays.asList(HIGH, AMBIGUOUS), plan.offered(Set.of("O2")));
    // A taken candidate original is dropped from the ambiguous pair; the one left makes it a
    // plain review pair on that candidate.
    assertEquals(Arrays.asList(HIGH, REVIEW, new Pair(C1, B3, Collections.emptyList(),
        List.of(BankBounceDetector.SIGNAL_COUNTERPARTY), BankBounceDetector.CONFIDENCE_REVIEW)),
        plan.offered(Set.of("C2")));
  }

  /**
   * A high pair is always offered and takes precedence over a review pair sharing a line; between
   * two review pairs the earlier one wins. The caller's taken set is not modified.
   */
  @Test
  public void highPairsComeFirstAndOverlappingReviewPairsLoseToTheEarlierOne() {
    Pair reviewOnHighLine = new Pair(O1, B2, Collections.emptyList(),
        List.of(BankBounceDetector.SIGNAL_COUNTERPARTY), BankBounceDetector.CONFIDENCE_REVIEW);
    Pair reviewOnO2Again = new Pair(O2, B3, Collections.emptyList(),
        List.of(BankBounceDetector.SIGNAL_COUNTERPARTY), BankBounceDetector.CONFIDENCE_REVIEW);
    BankBounceSuggestions.Plan plan = plan(reviewOnHighLine, REVIEW, HIGH, reviewOnO2Again);
    Set<String> taken = new HashSet<>();

    assertEquals(Arrays.asList(HIGH, REVIEW), plan.offered(taken));
    assertTrue("offered must not write into the caller's set", taken.isEmpty());
  }

  /** With the flag off nothing is detected, reserved or offered, and the panel gets no view. */
  @Test
  public void flagOffPlanDetectsNothingAndHasNoPanelView() {
    try (MockedStatic<BankBounceDetector> detector = mockStatic(BankBounceDetector.class)) {
      BankBounceSuggestions.Plan plan = BankBounceSuggestions.plan(false, account,
          () -> {
            throw new AssertionError("the candidate lines are not read with the flag off");
          }, 3);

      assertSame(BankBounceSuggestions.Plan.NONE, plan);
      assertFalse(plan.enabled());
      assertTrue(plan.reservedLineIds().isEmpty());
      assertNull(BankBounceSuggestions.panelView(plan));
      detector.verifyNoInteractions();
    }
  }

  // ---------------------------------------------------------------------------
  // autoMatch
  // ---------------------------------------------------------------------------

  /**
   * The autoMatch fixture: pending lines O1, B1 (a high pair), O2, B2 (a review pair) and T. O2 and
   * T each match a movement; every other line finds nothing.
   */
  private List<FIN_BankStatementLine> stubAutoMatchLines() {
    List<FIN_BankStatementLine> lines = Arrays.asList(dalLine("O1", -100), dalLine("B1", 100),
        dalLine("O2", -50), dalLine("B2", 50), dalLine("T", 70));
    doReturn(lines).when(handler).loadPendingLines(ACC_ID);
    doReturn(new HashSet<>()).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), anyString(), anyInt(), any());
    doReturn(new HashSet<>(Collections.singleton("t-o2"))).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), eq("O2"), anyInt(), any());
    doReturn(new HashSet<>(Collections.singleton("t-t"))).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), eq("T"), anyInt(), any());
    FIN_FinaccTransaction to2 = trx("t-o2", -50);
    FIN_FinaccTransaction tt = trx("t-t", 70);
    doReturn(to2).when(handler).loadTransaction("t-o2");
    doReturn(tt).when(handler).loadTransaction("t-t");
    return lines;
  }

  /** Runs autoMatch with the flag in the given state and the detector answering {@code pairs}. */
  private NeoResponse runAutoMatch(boolean flag, List<Pair> pairs,
      MockedStatic<ReconciliationKpiTelemetry> telemetry,
      MockedStatic<BankBounceDetector> detector) throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<BankBounceSuggestionsFlag> flagMock = mockStatic(BankBounceSuggestionsFlag.class);
        MockedStatic<AutoMatchSupport> support =
            mockStatic(AutoMatchSupport.class, CALLS_REAL_METHODS)) {
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      flagMock.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(flag);
      detector.when(() -> BankBounceDetector.detectQuietly(any(), anyList(), anyInt()))
          .thenReturn(pairs);
      support.when(() -> AutoMatchSupport.matchFallback(anyString(), any(), anySet(), anyList(),
          anyList(), any(JSONArray.class), any(AutoMatchSupport.MatchSettings.class)))
          .thenReturn(new int[] { 0, 0 });
      // Stubbing records an invocation; only what buildAutoMatch calls must be verifiable.
      detector.clearInvocations();
      return handler.buildAutoMatch(ACC_ID);
    }
  }

  /**
   * Flag on: the high pair's lines are skipped by transaction matching, the review pair whose
   * original was matched to a movement is not offered, every transaction group is tagged
   * {@code kind:"transaction"}, and the bounce group follows them. {@code groupsFound} and the
   * telemetry count the two transaction groups only; {@code kpis.bouncePairs} counts the pair.
   */
  @Test
  public void autoMatchReservesHighPairsTagsTransactionGroupsAndAppendsTheBouncePair()
      throws Exception {
    stubAutoMatchLines();
    NeoResponse response;
    try (MockedStatic<ReconciliationKpiTelemetry> telemetry =
        mockStatic(ReconciliationKpiTelemetry.class);
        MockedStatic<BankBounceDetector> detector =
            mockStatic(BankBounceDetector.class, CALLS_REAL_METHODS)) {
      response = runAutoMatch(true, Arrays.asList(HIGH, REVIEW), telemetry, detector);
      telemetry.verify(() -> ReconciliationKpiTelemetry.emitBankMatchAttempted(5, 2, 2));
      detector.verify(() -> BankBounceDetector.detectQuietly(eq(account), anyList(), eq(3)));
    }

    verify(handler, never()).suggestedTransactionIds(eq(ACC_ID), eq("O1"), anyInt(), any());
    verify(handler, never()).suggestedTransactionIds(eq(ACC_ID), eq("B1"), anyInt(), any());
    verify(handler).suggestedTransactionIds(eq(ACC_ID), eq("B2"), anyInt(), any());

    assertEquals(200, response.getHttpStatus());
    JSONObject data = data(response);
    JSONArray groups = data.getJSONArray("groups");
    assertEquals(3, groups.length());
    assertEquals("transaction", groups.getJSONObject(0).getString("kind"));
    assertEquals("O2", groups.getJSONObject(0).getJSONObject("statementLine").getString("id"));
    assertEquals("transaction", groups.getJSONObject(1).getString("kind"));
    assertEquals("T", groups.getJSONObject(1).getJSONObject("statementLine").getString("id"));

    JSONObject bounce = groups.getJSONObject(2);
    assertEquals("bouncePair", bounce.getString("kind"));
    assertEquals("bounce-O1-B1", bounce.getString("groupKey"));
    assertEquals("bounce", bounce.getString("origin"));
    assertEquals("O1", bounce.getJSONObject("statementLine").getString("id"));
    assertEquals("Partner O1", bounce.getJSONObject("statementLine").getString("partnerName"));
    assertEquals("B1", bounce.getJSONObject("counterpartLine").getString("id"));
    assertEquals(0, bounce.getJSONArray("candidates").length());
    assertFalse(bounce.getBoolean("ambiguous"));
    assertEquals("high", bounce.getString("confidence"));
    assertEquals("return_text", bounce.getString("signal"));
    assertEquals(0, bounce.getJSONArray("operations").length());
    assertFalse(bounce.getBoolean("isNew"));

    JSONObject kpis = data.getJSONObject("kpis");
    assertEquals(5, kpis.getInt("pendingLines"));
    assertEquals(2, kpis.getInt("groupsFound"));
    assertEquals(2, kpis.getInt("opsToLink"));
    assertEquals(1, kpis.getInt("bouncePairs"));
  }

  /** A review pair none of whose lines is matched is offered after the transaction groups. */
  @Test
  public void autoMatchOffersAnUntakenReviewPairAfterTheTransactionGroups() throws Exception {
    stubAutoMatchLines();
    // O2 no longer matches a movement, so the review pair O2/B2 is untouched.
    doReturn(new HashSet<>()).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), eq("O2"), anyInt(), any());
    NeoResponse response;
    try (MockedStatic<ReconciliationKpiTelemetry> telemetry =
        mockStatic(ReconciliationKpiTelemetry.class);
        MockedStatic<BankBounceDetector> detector =
            mockStatic(BankBounceDetector.class, CALLS_REAL_METHODS)) {
      response = runAutoMatch(true, Arrays.asList(HIGH, REVIEW), telemetry, detector);
      telemetry.verify(() -> ReconciliationKpiTelemetry.emitBankMatchAttempted(5, 1, 1));
    }

    JSONArray groups = data(response).getJSONArray("groups");
    assertEquals(3, groups.length());
    assertEquals("transaction", groups.getJSONObject(0).getString("kind"));
    assertEquals("bounce-O1-B1", groups.getJSONObject(1).getString("groupKey"));
    JSONObject review = groups.getJSONObject(2);
    assertEquals("bounce-O2-B2", review.getString("groupKey"));
    assertEquals("review", review.getString("confidence"));
    assertEquals("counterparty", review.getString("signal"));
    JSONObject kpis = data(response).getJSONObject("kpis");
    assertEquals(1, kpis.getInt("groupsFound"));
    assertEquals(2, kpis.getInt("bouncePairs"));
  }

  /** An ambiguous pair is offered with no original and its candidate originals listed. */
  @Test
  public void appendGroupsWritesAnAmbiguousPairWithItsCandidates() throws Exception {
    JSONArray groups = new JSONArray();
    JSONObject kpis = new JSONObject();

    BankBounceSuggestions.appendGroups(plan(AMBIGUOUS), groups, kpis);

    JSONObject group = groups.getJSONObject(0);
    assertEquals("bounce-B3", group.getString("groupKey"));
    assertTrue(group.isNull("statementLine"));
    assertEquals("B3", group.getJSONObject("counterpartLine").getString("id"));
    assertTrue(group.getBoolean("ambiguous"));
    JSONArray candidates = group.getJSONArray("candidates");
    assertEquals(2, candidates.length());
    assertEquals("C1", candidates.getJSONObject(0).getJSONObject("line").getString("id"));
    assertEquals("counterparty", candidates.getJSONObject(0).getString("signal"));
    assertEquals("C2", candidates.getJSONObject(1).getJSONObject("line").getString("id"));
    assertTrue(candidates.getJSONObject(1).isNull("signal"));
    assertEquals(0, candidates.getJSONObject(1).getJSONArray("signals").length());
    assertEquals(1, kpis.getInt("bouncePairs"));
  }

  /**
   * Flag off: the detector is never asked, no line is reserved, and the answer is the
   * pre-ETP-5581 one — the same as the flag-on answer with no pair, minus the {@code kind} tags and
   * {@code kpis.bouncePairs}.
   */
  @Test
  public void autoMatchWithTheFlagOffIsTheAnswerFromBeforeTheFeature() throws Exception {
    stubAutoMatchLines();
    NeoResponse off;
    NeoResponse onWithoutPairs;
    try (MockedStatic<ReconciliationKpiTelemetry> telemetry =
        mockStatic(ReconciliationKpiTelemetry.class);
        MockedStatic<BankBounceDetector> detector =
            mockStatic(BankBounceDetector.class, CALLS_REAL_METHODS)) {
      off = runAutoMatch(false, Arrays.asList(HIGH, REVIEW), telemetry, detector);
      detector.verify(() -> BankBounceDetector.detectQuietly(any(), anyList(), anyInt()), never());
      telemetry.verify(() -> ReconciliationKpiTelemetry.emitBankMatchAttempted(5, 2, 2));
      onWithoutPairs = runAutoMatch(true, Collections.emptyList(), telemetry, detector);
    }

    // The high pair's lines went through transaction matching as before.
    verify(handler, times(2)).suggestedTransactionIds(eq(ACC_ID), eq("O1"), anyInt(), any());

    JSONObject offData = data(off);
    JSONArray offGroups = offData.getJSONArray("groups");
    assertEquals(2, offGroups.length());
    for (int i = 0; i < offGroups.length(); i++) {
      assertFalse("no kind with the flag off", offGroups.getJSONObject(i).has("kind"));
    }
    JSONObject offKpis = offData.getJSONObject("kpis");
    assertFalse(offKpis.has("bouncePairs"));
    assertEquals(new HashSet<>(Arrays.asList("pendingLines", "groupsFound", "opsToLink",
        "willCreate")), keys(offKpis));

    JSONObject onData = data(onWithoutPairs);
    JSONArray onGroups = onData.getJSONArray("groups");
    for (int i = 0; i < onGroups.length(); i++) {
      onGroups.getJSONObject(i).remove("kind");
    }
    onData.getJSONObject("kpis").remove("bouncePairs");
    assertEquals(offData.toString(), onData.toString());
  }

  private static Set<String> keys(JSONObject json) {
    Set<String> out = new HashSet<>();
    for (Iterator<?> it = json.keys(); it.hasNext();) {
      out.add((String) it.next());
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // pendingLines panel: agreement with autoMatch
  // ---------------------------------------------------------------------------

  /**
   * Runs {@code summarizePendingLines} over the rows with the given view; rows not in
   * {@code states} classify as {@code pending}. Returns the ids actually classified.
   */
  private List<String> summarize(JSONArray rows, BankBounceSuggestions.PanelView view,
      Map<String, String> states, JSONObject[] out) throws Exception {
    List<String> classified = new ArrayList<>();
    try (MockedStatic<AutoMatchSupport> support =
        mockStatic(AutoMatchSupport.class, CALLS_REAL_METHODS)) {
      support.when(() -> AutoMatchSupport.classifyPendingLine(any(FIN_FinancialAccount.class),
          anyString(), anyList(), anyInt(), any(BigDecimal.class), anySet(), anyList()))
          .thenAnswer(inv -> {
            String id = inv.getArgument(1);
            classified.add(id);
            return states.getOrDefault(id, AutoMatchSupport.STATE_PENDING);
          });
      out[0] = ReconciliationHandlerSupport.summarizePendingLines(rows, account,
          Collections.emptyList(), 3, BigDecimal.ZERO, view);
    }
    return classified;
  }

  private static JSONArray panelRows() throws Exception {
    return new JSONArray(Arrays.asList(row("O1", -100), row("B1", 100), row("O2", -50),
        row("B2", 50), row("B3", 30), row("C1", -30), row("C2", -30), row("T", 70)));
  }

  private static Map<String, JSONObject> byId(JSONArray rows) throws Exception {
    Map<String, JSONObject> out = new HashMap<>();
    for (int i = 0; i < rows.length(); i++) {
      out.put(rows.getJSONObject(i).getString("id"), rows.getJSONObject(i));
    }
    return out;
  }

  /**
   * The panel and the modal see the same pairs: with O2 taken by a movement, both offer the high
   * pair and the ambiguous one and drop the review pair O2/B2. The panel's {@code bounce} rows are
   * exactly the lines of the offered pairs, and their distinct group keys are the modal's
   * {@code kpis.bouncePairs}.
   */
  @Test
  public void panelBounceStateAndCountsAgreeWithTheAutoMatchPairs() throws Exception {
    BankBounceSuggestions.Plan plan = plan(HIGH, REVIEW, AMBIGUOUS);

    // Modal side: the transaction loop took O2 (and T).
    JSONArray groups = new JSONArray(Arrays.asList(transactionGroup("O2"), transactionGroup("T")));
    JSONObject kpis = new JSONObject();
    BankBounceSuggestions.appendGroups(plan, groups, kpis);

    // Panel side: the same lines, O2 and T classified as matchable.
    JSONArray rows = panelRows();
    Map<String, String> states = new HashMap<>();
    states.put("O2", AutoMatchSupport.STATE_SUGGESTED);
    states.put("T", AutoMatchSupport.STATE_SUGGESTED);
    JSONObject[] out = new JSONObject[1];
    List<String> classified =
        summarize(rows, BankBounceSuggestions.panelView(plan), states, out);

    // The high pair's lines are classified bounce up front, consuming no movement.
    assertFalse(classified.contains("O1"));
    assertFalse(classified.contains("B1"));

    Map<String, JSONObject> rowsById = byId(rows);
    Set<String> bounceRows = new HashSet<>();
    Set<String> panelKeys = new HashSet<>();
    for (JSONObject r : rowsById.values()) {
      if ("bounce".equals(r.getString("state"))) {
        bounceRows.add(r.getString("id"));
        panelKeys.add(r.getJSONObject("bouncePair").getString("groupKey"));
      }
    }
    assertEquals(new HashSet<>(Arrays.asList("O1", "B1", "B3", "C1", "C2")), bounceRows);
    assertEquals(ids(plan.offered(Set.of("O2", "T"))), panelKeys);
    assertEquals(kpis.getInt("bouncePairs"), panelKeys.size());
    Set<String> modalKeys = new HashSet<>();
    for (int i = 0; i < groups.length(); i++) {
      if ("bouncePair".equals(groups.getJSONObject(i).getString("kind"))) {
        modalKeys.add(groups.getJSONObject(i).getString("groupKey"));
      }
    }
    assertEquals(modalKeys, panelKeys);

    JSONObject counts = out[0].getJSONObject("counts");
    assertEquals(8, counts.getInt("all"));
    assertEquals(5, counts.getInt("bounce"));
    assertEquals(2, counts.getInt("suggested"));
    // B2 only: B3, C1 and C2 moved from pending to bounce.
    assertEquals(1, counts.getInt("pending"));
    assertEquals("pending", rowsById.get("B2").getString("state"));
    assertFalse(rowsById.get("B2").has("bouncePair"));
    assertFalse(rowsById.get("O2").has("bouncePair"));
    // A bounce row is still a pending line for the status filter.
    assertEquals(ReconciliationHandler.STATUS_PENDING, rowsById.get("O1").getString("status"));
  }

  /** The {@code bouncePair} annotation of each role points at the right counterpart. */
  @Test
  public void panelAnnotatesTheOriginalBounceAndCandidateRoles() throws Exception {
    JSONArray rows = panelRows();
    JSONObject[] out = new JSONObject[1];
    summarize(rows, BankBounceSuggestions.panelView(plan(HIGH, AMBIGUOUS)),
        Collections.emptyMap(), out);
    Map<String, JSONObject> rowsById = byId(rows);

    JSONObject original = rowsById.get("O1").getJSONObject("bouncePair");
    assertEquals("original", original.getString("role"));
    assertEquals("bounce-O1-B1", original.getString("groupKey"));
    assertEquals("B1", original.getJSONObject("counterpartLine").getString("id"));
    assertFalse(original.getBoolean("ambiguous"));
    assertEquals("high", original.getString("confidence"));
    assertEquals("return_text", original.getString("signal"));

    JSONObject bounce = rowsById.get("B1").getJSONObject("bouncePair");
    assertEquals("bounce", bounce.getString("role"));
    assertEquals("O1", bounce.getJSONObject("counterpartLine").getString("id"));
    assertEquals(0, bounce.getJSONArray("candidates").length());

    JSONObject ambiguousBounce = rowsById.get("B3").getJSONObject("bouncePair");
    assertEquals("bounce", ambiguousBounce.getString("role"));
    assertTrue(ambiguousBounce.getBoolean("ambiguous"));
    assertTrue(ambiguousBounce.isNull("counterpartLine"));
    JSONArray candidates = ambiguousBounce.getJSONArray("candidates");
    assertEquals(2, candidates.length());
    assertEquals("C1", candidates.getJSONObject(0).getJSONObject("line").getString("id"));
    assertEquals("C2", candidates.getJSONObject(1).getJSONObject("line").getString("id"));

    JSONObject c1 = rowsById.get("C1").getJSONObject("bouncePair");
    assertEquals("candidate", c1.getString("role"));
    assertEquals("bounce-B3", c1.getString("groupKey"));
    assertEquals("B3", c1.getJSONObject("counterpartLine").getString("id"));
    assertEquals("counterparty", c1.getString("signal"));
    assertEquals(1, c1.getJSONArray("signals").length());
    JSONObject c2 = rowsById.get("C2").getJSONObject("bouncePair");
    assertEquals("candidate", c2.getString("role"));
    assertTrue(c2.isNull("signal"));
    assertEquals(0, c2.getJSONArray("signals").length());

    assertEquals(5, out[0].getJSONObject("counts").getInt("bounce"));
  }

  /**
   * Repro of a real statement (ETP-5581): on one day, three 0,61 transfers out (L120, L140, L160)
   * and two returns with bank bounce wording (L130, L150). With no statement position the ambiguous
   * assignment applies, so the detection yields one ambiguous pair per return, both listing the
   * same three transfers. Both returns must be offered, in the modal and in the panel: candidates
   * the two pairs share do not block each other. Before the fix only the first pair was offered and
   * L150 disappeared from both answers. (With the real statement positions, adjacency narrows the
   * two pairs to [L120, L140] and [L140, L160], which still share L140: the same offering path.)
   */
  @Test
  public void ambiguousPairsSharingTheirCandidatesAreAllOffered() throws Exception {
    LocalDate day = LocalDate.of(2026, 8, 24);
    BankBounceDetector.AccountData detection = new BankBounceDetector.AccountData(null, 3);
    detection.lines.addAll(Arrays.asList(
        new StatementLine("L120", day, 120, "TRANSFERENCIA INMEDIATA A FAVOR DE Galder Romo", "",
            new BigDecimal("-0.61"), true),
        new StatementLine("L130", day, 130, "RETROCESIÓN TRANSFERENCIA INMEDIATA", "",
            new BigDecimal("0.61"), true),
        new StatementLine("L140", day, 140,
            "TRANSFERENCIA INMEDIATA A FAVOR DE Distribuciones Iberia 10000268-2", "",
            new BigDecimal("-0.61"), true),
        new StatementLine("L150", day, 150, "RETROCESIÓN TRANSFERENCIA INMEDIATA", "",
            new BigDecimal("0.61"), true),
        new StatementLine("L160", day, 160,
            "TRANSFERENCIA INMEDIATA A FAVOR DE Distribuciones Iberia 10000268-1", "",
            new BigDecimal("-0.61"), true)));
    List<Pair> detected = BankBounceDetector.detect(detection);
    assertEquals(2, detected.size());
    assertTrue(detected.get(0).ambiguous());
    assertTrue(detected.get(1).ambiguous());
    BankBounceSuggestions.Plan plan =
        new BankBounceSuggestions.Plan(detected, Collections.emptyMap(), true);

    // Modal: both returns are offered.
    JSONArray groups = new JSONArray();
    JSONObject kpis = new JSONObject();
    BankBounceSuggestions.appendGroups(plan, groups, kpis);
    Set<String> modalBounces = new HashSet<>();
    for (int i = 0; i < groups.length(); i++) {
      JSONObject group = groups.getJSONObject(i);
      modalBounces.add(group.getJSONObject("counterpartLine").getString("id"));
      assertEquals("each return keeps its three candidate transfers", 3,
          group.getJSONArray("candidates").length());
    }
    assertEquals(new HashSet<>(Arrays.asList("L130", "L150")), modalBounces);
    assertEquals(2, kpis.getInt("bouncePairs"));

    // Panel: the same two pairs, every line in the bounce state and counted once.
    JSONArray rows = new JSONArray(Arrays.asList(row("L120", -1), row("L130", 1),
        row("L140", -1), row("L150", 1), row("L160", -1)));
    JSONObject[] out = new JSONObject[1];
    summarize(rows, BankBounceSuggestions.panelView(plan), Collections.emptyMap(), out);
    Map<String, JSONObject> rowsById = byId(rows);
    Set<String> panelKeys = new HashSet<>();
    for (JSONObject r : rowsById.values()) {
      assertEquals(r.getString("id"), "bounce", r.getString("state"));
      panelKeys.add(r.getJSONObject("bouncePair").getString("groupKey"));
    }
    for (String bounceId : Arrays.asList("L130", "L150")) {
      JSONObject marker = rowsById.get(bounceId).getJSONObject("bouncePair");
      assertEquals("bounce", marker.getString("role"));
      assertEquals("bounce-" + bounceId, marker.getString("groupKey"));
      assertEquals(3, marker.getJSONArray("candidates").length());
    }
    for (String candidateId : Arrays.asList("L120", "L140", "L160")) {
      JSONObject marker = rowsById.get(candidateId).getJSONObject("bouncePair");
      assertEquals("candidate", marker.getString("role"));
      assertTrue(modalBounces.contains(marker.getJSONObject("counterpartLine").getString("id")));
    }
    assertEquals(new HashSet<>(Arrays.asList("bounce-L130", "bounce-L150")), panelKeys);
    assertEquals(kpis.getInt("bouncePairs"), panelKeys.size());
    assertEquals(5, out[0].getJSONObject("counts").getInt("bounce"));
    assertEquals(0, out[0].getJSONObject("counts").getInt("pending"));
  }

  /**
   * The real statement with its positions (one {@code FIN_BankStatement}): adjacency narrows the two
   * returns to [L120, L140] and [L140, L160], which share L140. Both are offered in the modal and
   * in the panel; the shared row is annotated once, by the first pair, and the distinct group keys
   * of the bounce rows still equal {@code kpis.bouncePairs}.
   */
  @Test
  public void adjacentAmbiguousPairsSharingANeighbourAreBothOffered() throws Exception {
    LocalDate day = LocalDate.of(2026, 8, 24);
    BankBounceDetector.AccountData detection = new BankBounceDetector.AccountData(null, 3);
    String[][] statement = { { "L120", "-0.61", "TRANSFERENCIA INMEDIATA" },
        { "L130", "0.61", "RETROCESIÓN TRANSFERENCIA INMEDIATA" },
        { "L140", "-0.61", "TRANSFERENCIA INMEDIATA" },
        { "L150", "0.61", "RETROCESIÓN TRANSFERENCIA INMEDIATA" },
        { "L160", "-0.61", "TRANSFERENCIA INMEDIATA" } };
    for (String[] l : statement) {
      detection.lines.add(new StatementLine(l[0], day, Long.parseLong(l[0].substring(1)), l[2],
          "", new BigDecimal(l[1]), true, "ST-BBVA"));
    }
    List<Pair> detected = BankBounceDetector.detect(detection);
    assertEquals(2, detected.size());
    BankBounceSuggestions.Plan plan =
        new BankBounceSuggestions.Plan(detected, Collections.emptyMap(), true);

    JSONArray groups = new JSONArray();
    JSONObject kpis = new JSONObject();
    BankBounceSuggestions.appendGroups(plan, groups, kpis);
    assertEquals(2, groups.length());
    String[][] expected = { { "L130", "L120", "L140" }, { "L150", "L140", "L160" } };
    for (int i = 0; i < expected.length; i++) {
      JSONObject group = groups.getJSONObject(i);
      assertEquals(expected[i][0], group.getJSONObject("counterpartLine").getString("id"));
      assertTrue(group.getBoolean("ambiguous"));
      assertTrue(group.getBoolean("adjacent"));
      JSONArray candidates = group.getJSONArray("candidates");
      assertEquals(2, candidates.length());
      assertEquals(expected[i][1], candidates.getJSONObject(0).getJSONObject("line").getString("id"));
      assertEquals(expected[i][2], candidates.getJSONObject(1).getJSONObject("line").getString("id"));
    }
    assertEquals(2, kpis.getInt("bouncePairs"));

    JSONArray rows = new JSONArray(Arrays.asList(row("L120", -1), row("L130", 1),
        row("L140", -1), row("L150", 1), row("L160", -1)));
    JSONObject[] out = new JSONObject[1];
    summarize(rows, BankBounceSuggestions.panelView(plan), Collections.emptyMap(), out);
    Map<String, JSONObject> rowsById = byId(rows);
    Set<String> bounceKeys = new HashSet<>();
    for (String bounceId : Arrays.asList("L130", "L150")) {
      JSONObject marker = rowsById.get(bounceId).getJSONObject("bouncePair");
      assertEquals("bounce", marker.getString("role"));
      assertEquals(2, marker.getJSONArray("candidates").length());
      assertTrue(marker.getBoolean("adjacent"));
      bounceKeys.add(marker.getString("groupKey"));
    }
    JSONObject shared = rowsById.get("L140").getJSONObject("bouncePair");
    assertEquals("candidate", shared.getString("role"));
    assertEquals("annotated by the first pair", "bounce-L130", shared.getString("groupKey"));
    assertEquals("bounce-L150",
        rowsById.get("L160").getJSONObject("bouncePair").getString("groupKey"));
    assertEquals(kpis.getInt("bouncePairs"), bounceKeys.size());
    assertEquals(5, out[0].getJSONObject("counts").getInt("bounce"));
  }

  /**
   * Plain pairs are settled before ambiguous ones whatever the input order: the plain pair keeps
   * the candidate it shares with an ambiguous pair, which is narrowed to the candidate left.
   */
  @Test
  public void plainPairsAreSettledBeforeAmbiguousOnesWhateverTheOrder() {
    StatementLine b5 = model("B5", 30, DAY.plusDays(2));
    Pair plainOnC1 = new Pair(C1, b5, Collections.emptyList(),
        List.of(BankBounceDetector.SIGNAL_COUNTERPARTY), BankBounceDetector.CONFIDENCE_REVIEW);
    Pair narrowed = new Pair(C2, B3, Collections.emptyList(), Collections.emptyList(),
        BankBounceDetector.CONFIDENCE_REVIEW);

    assertEquals(Arrays.asList(narrowed, plainOnC1),
        plan(AMBIGUOUS, plainOnC1).offered(Collections.emptySet()));
    assertEquals(Arrays.asList(plainOnC1, narrowed),
        plan(plainOnC1, AMBIGUOUS).offered(Collections.emptySet()));
  }

  /**
   * Two ambiguous pairs left with the same single candidate: the first becomes a plain pair on it
   * and the second, with nothing left, is dropped.
   */
  @Test
  public void twoAmbiguousPairsLeftWithOneSharedCandidateOfferOnlyTheFirst() {
    StatementLine x = model("X", -30, DAY);
    StatementLine y = model("Y", -30, DAY);
    StatementLine z = model("Z", -30, DAY);
    StatementLine b4 = model("B4", 30, DAY.plusDays(1));
    Pair first = new Pair(null, B3, List.of(new Candidate(x, List.of()), new Candidate(y,
        List.of())), List.of(), BankBounceDetector.CONFIDENCE_REVIEW);
    Pair second = new Pair(null, b4, List.of(new Candidate(x, List.of()), new Candidate(z,
        List.of())), List.of(), BankBounceDetector.CONFIDENCE_REVIEW);

    List<Pair> offered = plan(first, second).offered(Set.of("Y", "Z"));

    assertEquals(1, offered.size());
    assertSame(x, offered.get(0).original());
    assertSame(B3, offered.get(0).bounce());
    assertFalse(offered.get(0).ambiguous());
  }

  /** An ambiguous pair whose bounce, or every candidate, is taken is not offered. */
  @Test
  public void anAmbiguousPairWithItsBounceOrAllItsCandidatesTakenIsDropped() {
    assertTrue(plan(AMBIGUOUS).offered(Set.of("B3")).isEmpty());
    assertTrue(plan(AMBIGUOUS).offered(Set.of("C1", "C2")).isEmpty());
    Pair plainOnB3 = new Pair(model("C3", -30, DAY), B3, Collections.emptyList(),
        List.of(BankBounceDetector.SIGNAL_COUNTERPARTY), BankBounceDetector.CONFIDENCE_REVIEW);
    assertEquals("a plain pair taking the bounce first drops the ambiguous one",
        Collections.singletonList(plainOnB3),
        plan(AMBIGUOUS, plainOnB3).offered(Collections.emptySet()));
  }

  /** Narrowing an adjacent ambiguous pair to one candidate keeps {@code adjacent}. */
  @Test
  public void narrowingAnAdjacentAmbiguousPairKeepsAdjacent() {
    Pair adjacent = new Pair(null, B3, AMBIGUOUS.candidates(), AMBIGUOUS.signals(),
        BankBounceDetector.CONFIDENCE_REVIEW, true);

    List<Pair> offered = plan(adjacent).offered(Set.of("C2"));

    assertEquals(1, offered.size());
    assertSame(C1, offered.get(0).original());
    assertFalse(offered.get(0).ambiguous());
    assertTrue(offered.get(0).adjacent());
    assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, offered.get(0).confidence());
  }

  /** {@code adjacent} is on every bouncePair group and every annotation role, true or false. */
  @Test
  public void adjacentIsOnEveryGroupAndEveryAnnotationRole() throws Exception {
    Pair adjacentHigh = new Pair(O1, B1, Collections.emptyList(),
        List.of(BankBounceDetector.SIGNAL_RETURN_TEXT), BankBounceDetector.CONFIDENCE_HIGH, true);
    BankBounceSuggestions.Plan plan = plan(adjacentHigh, AMBIGUOUS);

    JSONArray groups = new JSONArray();
    BankBounceSuggestions.appendGroups(plan, groups, new JSONObject());
    Map<String, Boolean> byKey = new HashMap<>();
    for (int i = 0; i < groups.length(); i++) {
      JSONObject group = groups.getJSONObject(i);
      assertTrue(group.getString("groupKey"), group.has("adjacent"));
      byKey.put(group.getString("groupKey"), group.getBoolean("adjacent"));
    }
    assertEquals(Boolean.TRUE, byKey.get("bounce-O1-B1"));
    assertEquals(Boolean.FALSE, byKey.get("bounce-B3"));

    JSONArray rows = panelRows();
    JSONObject[] out = new JSONObject[1];
    summarize(rows, BankBounceSuggestions.panelView(plan), Collections.emptyMap(), out);
    Map<String, JSONObject> rowsById = byId(rows);
    String[][] roles = { { "O1", "original", "true" }, { "B1", "bounce", "true" },
        { "B3", "bounce", "false" }, { "C1", "candidate", "false" },
        { "C2", "candidate", "false" } };
    for (String[] r : roles) {
      JSONObject marker = rowsById.get(r[0]).getJSONObject("bouncePair");
      assertEquals(r[0], r[1], marker.getString("role"));
      assertTrue(r[0], marker.has("adjacent"));
      assertEquals(r[0], Boolean.parseBoolean(r[2]), marker.getBoolean("adjacent"));
    }
  }

  /** With no view (flag off) no row gets a bounce state or annotation and there is no count. */
  @Test
  public void panelWithoutAViewHasNoBounceStateAnnotationOrCount() throws Exception {
    JSONArray rows = panelRows();
    JSONObject[] out = new JSONObject[1];
    List<String> classified = summarize(rows, null, Collections.emptyMap(), out);

    assertEquals(8, classified.size());
    for (int i = 0; i < rows.length(); i++) {
      assertEquals("pending", rows.getJSONObject(i).getString("state"));
      assertFalse(rows.getJSONObject(i).has("bouncePair"));
    }
    JSONObject counts = out[0].getJSONObject("counts");
    assertFalse(counts.has("bounce"));
    assertEquals(8, counts.getInt("pending"));
  }

  // ---------------------------------------------------------------------------
  // applySuggestions
  // ---------------------------------------------------------------------------

  private static JSONObject bounceGroup(String lineId, String counterpartId) throws Exception {
    return new JSONObject().put("kind", "bouncePair").put("statementLineId", lineId)
        .put("counterpartLineId", counterpartId);
  }

  private static JSONObject txnGroup(String kind, String lineId, String... ops) throws Exception {
    JSONObject group = new JSONObject().put("statementLineId", lineId)
        .put("operationIds", new JSONArray(Arrays.asList(ops)));
    if (kind != null) {
      group.put("kind", kind);
    }
    return group;
  }

  private static JSONObject applyBody(JSONObject... groups) throws Exception {
    return new JSONObject().put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray(Arrays.asList(groups)));
  }

  private FIN_Reconciliation stubCompose() throws Exception {
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-1");
    doReturn(rec).when(handler).addNewDraftReconciliation(any());
    doReturn(rec).when(handler).getOrCreateDraftReconciliation(any());
    doNothing().when(handler).matchBankStatementLine(any(), any(), any());
    doNothing().when(handler).tagMatchGroup(any());
    OBError result = mock(OBError.class);
    when(result.getType()).thenReturn("Success");
    when(result.getMessage()).thenReturn("msg");
    doReturn(result).when(handler).processReconciliation(rec);
    return rec;
  }

  /** Stubs the pair validation of O1/B1 (and any pair named in {@code pairs}) as passing. */
  private static void validatesAsPassing(MockedStatic<BankBouncePairService> service,
      List<String> events, String... pairs) {
    for (int i = 0; i < pairs.length; i += 2) {
      String a = pairs[i];
      String b = pairs[i + 1];
      service.when(() -> BankBouncePairService.validate(any(), any(), eq(a), eq(b)))
          .thenReturn(new BankBouncePairService.Validated(mock(FIN_BankStatementLine.class),
              mock(FIN_BankStatementLine.class), "return_text", "high", null));
    }
    service.when(() -> BankBouncePairService.discard(any())).thenAnswer(inv -> {
      events.add("discard");
      return null;
    });
  }

  /**
   * A mixed batch: the bounce pair is validated and discarded BEFORE the transaction group is even
   * loaded, although it was submitted after it; both succeed, and the telemetry counts the one
   * transaction group only.
   */
  @Test
  public void applySuggestionsDiscardsBouncePairsFirstThenReconcilesTransactionGroups()
      throws Exception {
    List<String> events = new ArrayList<>();
    FIN_BankStatementLine lineT = ownedLine("T", 70);
    doAnswer(inv -> {
      events.add("load:" + inv.getArgument(0));
      return lineT;
    }).when(handler).loadLine("T");
    doReturn(trx("t-t", 70)).when(handler).loadTransaction("t-t");
    FIN_Reconciliation rec = stubCompose();

    NeoResponse response;
    try (MockedStatic<BankBounceSuggestionsFlag> flag = mockStatic(BankBounceSuggestionsFlag.class);
        MockedStatic<BankBouncePairService> service =
            mockStatic(BankBouncePairService.class, CALLS_REAL_METHODS);
        MockedStatic<StatementLineFilter> filter = mockStatic(StatementLineFilter.class);
        MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class)) {
      flag.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(true);
      validatesAsPassing(service, events, "O1", "B1");

      response = handler.applySuggestions(applyBody(txnGroup("transaction", "T", "t-t"),
          bounceGroup("O1", "B1")));

      telemetry.verify(
          () -> ReconciliationKpiTelemetry.emitReconciliationMatchEvaluated(1, 1, 1));
      service.verify(() -> BankBouncePairService.discard(any()), times(1));
    }

    assertEquals(Arrays.asList("discard", "load:T"), events);
    assertEquals(201, response.getHttpStatus());
    JSONObject data = data(response);
    assertEquals(2, data.getInt("applied"));
    JSONObject first = data.getJSONArray("results").getJSONObject(0);
    assertEquals("bouncePair", first.getString("kind"));
    assertTrue(first.getBoolean("discarded"));
    assertEquals("O1", first.getString("statementLineId"));
    assertEquals("B1", first.getString("counterpartLineId"));
    JSONObject second = data.getJSONArray("results").getJSONObject(1);
    assertFalse(second.has("error"));
    verify(handler).matchBankStatementLine(eq(lineT), any(), eq(rec));
  }

  /**
   * A line checked in a bounce pair and again in a transaction group is discarded once and the
   * transaction group is refused with a 409, before anything is matched.
   */
  @Test
  public void applySuggestionsRefusesATransactionGroupOnALineABouncePairTook() throws Exception {
    List<String> events = new ArrayList<>();
    FIN_BankStatementLine lineO1 = ownedLine("O1", -100);
    doReturn(lineO1).when(handler).loadLine("O1");
    doReturn(trx("t-1", -100)).when(handler).loadTransaction("t-1");
    stubCompose();

    NeoResponse response;
    try (MockedStatic<BankBounceSuggestionsFlag> flag = mockStatic(BankBounceSuggestionsFlag.class);
        MockedStatic<BankBouncePairService> service =
            mockStatic(BankBouncePairService.class, CALLS_REAL_METHODS);
        MockedStatic<StatementLineFilter> filter = mockStatic(StatementLineFilter.class);
        MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class)) {
      flag.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(true);
      validatesAsPassing(service, events, "O1", "B1");

      response = handler.applySuggestions(applyBody(bounceGroup("O1", "B1"),
          txnGroup(null, "O1", "t-1")));

      telemetry.verify(
          () -> ReconciliationKpiTelemetry.emitReconciliationMatchEvaluated(1, 1, 0));
    }

    assertEquals(Collections.singletonList("discard"), events);
    JSONArray results = data(response).getJSONArray("results");
    assertEquals(2, results.length());
    JSONObject refused = results.getJSONObject(1);
    assertEquals(409, refused.getJSONObject("error").getInt("status"));
    assertEquals(ReconciliationHandler.MSG_LINE_ALREADY_RECONCILED + ": O1",
        refused.getJSONObject("error").getString("message"));
    assertEquals("O1", refused.getString("statementLineId"));
    verify(handler, never()).matchBankStatementLine(any(), any(), any());
  }

  /** Two bounce groups sharing a line: the second is refused 409 LINE_NOT_PENDING, unvalidated. */
  @Test
  public void applySuggestionsRefusesASecondBouncePairOnATakenLine() throws Exception {
    List<String> events = new ArrayList<>();
    NeoResponse response;
    try (MockedStatic<BankBounceSuggestionsFlag> flag = mockStatic(BankBounceSuggestionsFlag.class);
        MockedStatic<BankBouncePairService> service =
            mockStatic(BankBouncePairService.class, CALLS_REAL_METHODS);
        MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class)) {
      flag.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(true);
      validatesAsPassing(service, events, "O1", "B1", "O2", "B1");

      response = handler.applySuggestions(applyBody(bounceGroup("O1", "B1"),
          bounceGroup("O2", "B1")));

      service.verify(() -> BankBouncePairService.validate(any(), any(), eq("O2"), eq("B1")),
          never());
      telemetry.verify(
          () -> ReconciliationKpiTelemetry.emitReconciliationMatchEvaluated(0, 0, 0));
    }

    assertEquals(Collections.singletonList("discard"), events);
    JSONArray results = data(response).getJSONArray("results");
    assertEquals(2, results.length());
    JSONObject refused = results.getJSONObject(1);
    assertEquals(BankBounceErrors.CODE_LINE_NOT_PENDING, refused.getString("code"));
    assertEquals(409, refused.getJSONObject("error").getInt("status"));
    assertEquals("bouncePair", refused.getString("kind"));
    assertEquals("O2", refused.getString("statementLineId"));
  }

  /** A group of an unknown kind is refused 400 without loading its line. */
  @Test
  public void applySuggestionsRefusesAnUnknownGroupKind() throws Exception {
    NeoResponse response;
    try (MockedStatic<BankBounceSuggestionsFlag> flag = mockStatic(BankBounceSuggestionsFlag.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class)) {
      response = handler.applySuggestions(applyBody(txnGroup("splitPayment", "T", "t-t")));

      telemetry.verify(
          () -> ReconciliationKpiTelemetry.emitReconciliationMatchEvaluated(1, 1, 0));
      // No bounce group: the flag is not even read.
      flag.verifyNoInteractions();
    }

    assertEquals(201, response.getHttpStatus());
    JSONObject result = data(response).getJSONArray("results").getJSONObject(0);
    assertEquals(400, result.getJSONObject("error").getInt("status"));
    assertEquals("Unsupported group kind: splitPayment",
        result.getJSONObject("error").getString("message"));
    assertEquals("T", result.getString("statementLineId"));
    verify(handler, never()).loadLine(anyString());
  }

  /**
   * Flag off: every bounce group is answered 403 {@code BOUNCE_SUGGESTIONS_OFF}, tagged with its
   * lines; nothing is validated, discarded, matched or reconciled; the flag is read once.
   */
  @Test
  public void applySuggestionsWithTheFlagOffRefusesEachBounceGroupAndWritesNothing()
      throws Exception {
    stubCompose();
    NeoResponse response;
    try (MockedStatic<BankBounceSuggestionsFlag> flag = mockStatic(BankBounceSuggestionsFlag.class);
        MockedStatic<BankBouncePairService> service =
            mockStatic(BankBouncePairService.class, CALLS_REAL_METHODS);
        MockedStatic<StatementLineDiscard> discard =
            mockStatic(StatementLineDiscard.class, CALLS_REAL_METHODS);
        MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class)) {
      flag.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(false);

      response = handler.applySuggestions(applyBody(bounceGroup("O1", "B1"),
          bounceGroup("O2", "B2")));

      flag.verify(BankBounceSuggestionsFlag::isEnabled, times(1));
      service.verify(() -> BankBouncePairService.validate(any(), any(), anyString(), anyString()),
          never());
      service.verify(() -> BankBouncePairService.discard(any()), never());
      discard.verify(() -> StatementLineDiscard.setDiscarded(any(), Mockito.anyBoolean()), never());
      telemetry.verify(
          () -> ReconciliationKpiTelemetry.emitReconciliationMatchEvaluated(0, 0, 0));
    }

    assertEquals(201, response.getHttpStatus());
    JSONObject data = data(response);
    assertEquals(2, data.getInt("applied"));
    JSONArray results = data.getJSONArray("results");
    String[][] pairs = { { "O1", "B1" }, { "O2", "B2" } };
    for (int i = 0; i < pairs.length; i++) {
      JSONObject r = results.getJSONObject(i);
      assertEquals(BankBounceErrors.CODE_SUGGESTIONS_OFF, r.getString("code"));
      assertEquals(403, r.getJSONObject("error").getInt("status"));
      assertEquals("bouncePair", r.getString("kind"));
      assertEquals(pairs[i][0], r.getString("statementLineId"));
      assertEquals(pairs[i][1], r.getString("counterpartLineId"));
    }
    verify(handler, never()).loadLine(anyString());
    verify(handler, never()).getOrCreateDraftReconciliation(any());
    verify(handler, never()).matchBankStatementLine(any(), any(), any());
    verify(handler, never()).processReconciliation(any());
  }
}
