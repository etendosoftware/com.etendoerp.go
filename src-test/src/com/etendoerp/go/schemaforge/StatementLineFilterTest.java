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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.advpaymentmngt.hook.BankStatementLineFilterContext;
import org.openbravo.advpaymentmngt.hook.BankStatementLineFilterContext.Usage;
import org.openbravo.advpaymentmngt.hook.BankStatementLineFilterHook;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for {@link StatementLineFilter}, the Etendo GO side of APRM's
 * {@link BankStatementLineFilterHook}. The real {@code BankStatementLineFilterHelper} runs: only
 * {@link WeldUtils} is mocked, to deploy no hook or one hook, {@link OBDal} to answer the queries
 * and {@link BankBounceSuggestionsFlag}, on unless a case says otherwise. With no hook every method
 * must answer "nothing is excluded" without touching the database; with a hook its condition must
 * reach the query and decide the excluded ids; with the flag off nothing is honoured, hook or not,
 * and the answers are the ones from before ETP-5581.
 *
 * @covers com.etendoerp.go.schemaforge.StatementLineFilter
 */
class StatementLineFilterTest {

  private static final String ACCOUNT_ID = "ACC-1";
  private static final String HOOK_CONDITION = "bsl.etgoTestDiscarded = :hookFlag";

  private MockedStatic<WeldUtils> weld;
  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<BankBounceSuggestionsFlag> flag;
  private OBDal obDal;
  private final List<BankStatementLineFilterContext> hookContexts = new ArrayList<>();

  /** Mocks the hook lookup, the DAL and the flag evaluator (only to prove it is never called). */
  @BeforeEach
  void mockStatics() {
    weld = mockStatic(WeldUtils.class);
    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    // Mocked only to prove it is never called: the callers hand the flag in.
    flag = mockStatic(BankBounceSuggestionsFlag.class);
  }

  /** Closes the static mocks. */
  @AfterEach
  void closeStatics() {
    flag.close();
    obDalStatic.close();
    weld.close();
  }

  /** Deploys no {@code BankStatementLineFilterHook}. */
  private void deployNoHook() {
    weld.when(() -> WeldUtils.getInstances(BankStatementLineFilterHook.class))
        .thenReturn(List.of());
  }

  /** One hook that excludes the lines flagged by a (fictional) column, with one named parameter. */
  private void deployOneHook() {
    BankStatementLineFilterHook hook = (alias, context, params) -> {
      hookContexts.add(context);
      params.put("hookFlag", Boolean.TRUE);
      return alias + ".etgoTestDiscarded = :hookFlag";
    };
    weld.when(() -> WeldUtils.getInstances(BankStatementLineFilterHook.class))
        .thenReturn(List.of(hook));
  }

  /**
   * The financial account of the fixtures.
   *
   * @return the account mock
   */
  private static FIN_FinancialAccount account() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACCOUNT_ID);
    return account;
  }

  /**
   * A line of a statement of {@code account}.
   *
   * @param id      the line id
   * @param account the account
   * @return the line mock
   */
  private static FIN_BankStatementLine lineOf(String id, FIN_FinancialAccount account) {
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    when(statement.getAccount()).thenReturn(account);
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getBankStatement()).thenReturn(statement);
    return line;
  }

  // ── no hook installed ────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("with no hook installed nothing is excluded and nothing is queried")
  class NoHook {

    /** Hql answers the empty condition and adds no parameter. */
    @Test
    @DisplayName("hql answers the empty condition and adds no parameter")
    void emptyCondition() {
      deployNoHook();
      Map<String, Object> params = new HashMap<>();

      assertEquals("",
          StatementLineFilter.hql(true, "bsl", account(), Usage.MATCH_STATEMENT_GRID, params));
      assertTrue(params.isEmpty());
    }

    /** {@code excludedLineIds} is the empty set and runs no query. */
    @Test
    @DisplayName("excludedLineIds is the empty set and runs no query")
    void emptySet() {
      deployNoHook();

      assertTrue(StatementLineFilter.excludedLineIds(account(), Usage.AUTO_MATCH_CANDIDATES)
          .isEmpty());
      verify(obDal, never()).createQuery(eq(FIN_BankStatementLine.class), anyString());
    }

    /** {@code isExcluded} is false and discardedLineError null, with no query. */
    @Test
    @DisplayName("isExcluded is false and discardedLineError null, with no query")
    void nothingExcluded() {
      deployNoHook();
      FIN_BankStatementLine line = lineOf("L1", account());

      assertFalse(StatementLineFilter.isExcluded(true, line, Usage.MATCH_STATEMENT_GRID));
      assertNull(StatementLineFilter.discardedLineError(true, line, Usage.MATCH_STATEMENT_GRID));
      verify(obDal, never()).createQuery(eq(FIN_BankStatementLine.class), anyString());
    }

    /** No account, no line or no statement is never excluded, even with a hook. */
    @Test
    @DisplayName("no account, no line or no statement is never excluded, even with a hook")
    void missingAccountIsNeverExcluded() {
      deployOneHook();
      FIN_BankStatementLine orphan = mock(FIN_BankStatementLine.class);

      assertTrue(StatementLineFilter.excludedLineIds(null, Usage.AUTO_MATCH_CANDIDATES).isEmpty());
      assertFalse(StatementLineFilter.isExcluded(true, null, Usage.MATCH_STATEMENT_GRID));
      assertFalse(StatementLineFilter.isExcluded(true, orphan, Usage.MATCH_STATEMENT_GRID));
      assertTrue(hookContexts.isEmpty(), "no hook is asked without an account");
    }
  }

  // ── one hook installed ───────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("with a hook its condition decides the excluded lines")
  class WithHook {

    /** Hql appends the hook's condition, its parameters and passes account and usage. */
    @Test
    @DisplayName("hql appends the hook's condition, its parameters and passes account and usage")
    void hookConditionAndParameters() {
      deployOneHook();
      FIN_FinancialAccount account = account();
      Map<String, Object> params = new HashMap<>();

      String condition =
          StatementLineFilter.hql(true, "x", account, Usage.AUTO_MATCH_CANDIDATES, params);

      assertEquals(" and (x.etgoTestDiscarded = :hookFlag)", condition);
      assertEquals(Map.of("hookFlag", Boolean.TRUE), params);
      assertSame(account, hookContexts.get(0).getAccount());
      assertEquals(Usage.AUTO_MATCH_CANDIDATES, hookContexts.get(0).getUsage());
      assertNull(hookContexts.get(0).getReconciliation());
    }

    /**
     * {@code excludedLineIds} answers the ids of the account's unmatched lines NOT passing the
     * hook.
     */
    @Test
    @DisplayName("excludedLineIds answers the ids of the account's unmatched lines NOT passing the hook")
    @SuppressWarnings("unchecked")
    void excludedIds() {
      deployOneHook();
      OBQuery<FIN_BankStatementLine> query = mock(OBQuery.class);
      Query<String> ids = mock(Query.class);
      ArgumentCaptor<String> where = ArgumentCaptor.forClass(String.class);
      when(obDal.createQuery(eq(FIN_BankStatementLine.class), where.capture())).thenReturn(query);
      when(query.createQuery(String.class)).thenReturn(ids);
      when(ids.list()).thenReturn(List.of("L1", "L2", "L1"));

      Set<String> excluded =
          StatementLineFilter.excludedLineIds(account(), Usage.AUTO_MATCH_CANDIDATES);

      assertEquals(Set.of("L1", "L2"), excluded);
      String hql = where.getValue();
      assertTrue(hql.contains("bsl.bankStatement.account.id = :etgoAccountId"), hql);
      assertTrue(hql.contains("bsl.financialAccountTransaction is null"), hql);
      assertTrue(hql.endsWith("not (1 = 1 and (" + HOOK_CONDITION + "))"), hql);
      ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
      verify(query).setNamedParameters(params.capture());
      assertEquals(Map.of("hookFlag", Boolean.TRUE, StatementLineFilter.PARAM_ACCOUNT_ID,
          ACCOUNT_ID), params.getValue());
      verify(query).setSelectClause("bsl.id");
    }

    /** An excluded line answers the coded 409 LINE_DISCARDED. */
    @Test
    @DisplayName("an excluded line answers the coded 409 LINE_DISCARDED")
    @SuppressWarnings("unchecked")
    void excludedLineIsRefused() throws Exception {
      deployOneHook();
      OBQuery<FIN_BankStatementLine> query = mock(OBQuery.class);
      ArgumentCaptor<String> where = ArgumentCaptor.forClass(String.class);
      when(obDal.createQuery(eq(FIN_BankStatementLine.class), where.capture())).thenReturn(query);
      when(query.count()).thenReturn(1);
      FIN_BankStatementLine line = lineOf("L1", account());

      try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
        messages.when(() -> OBMessageUtils.messageBD(StatementLineDiscard.MSG_LINE_DISCARDED))
            .thenReturn("La línea está descartada");

        assertTrue(StatementLineFilter.isExcluded(true, line, Usage.MATCH_STATEMENT_GRID));
        NeoResponse refusal =
            StatementLineFilter.discardedLineError(true, line, Usage.MATCH_STATEMENT_GRID);

        assertEquals(409, refusal.getHttpStatus());
        assertEquals(StatementLineDiscard.CODE_LINE_DISCARDED, refusal.getBody().getString("code"));
        assertEquals("La línea está descartada", refusal.getBody().getString("message"));
      }
      assertTrue(where.getValue().startsWith("as bsl where bsl.id = :etgoLineId and not (1 = 1"),
          where.getValue());
      verify(query, org.mockito.Mockito.atLeastOnce()).setFilterOnActive(false);
    }

    /** A line the hook does not exclude may be reconciled. */
    @Test
    @DisplayName("a line the hook does not exclude may be reconciled")
    @SuppressWarnings("unchecked")
    void notExcludedLineIsAllowed() {
      deployOneHook();
      OBQuery<FIN_BankStatementLine> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FIN_BankStatementLine.class), anyString())).thenReturn(query);
      when(query.count()).thenReturn(0);
      FIN_BankStatementLine line = lineOf("L1", account());

      assertFalse(StatementLineFilter.isExcluded(true, line, Usage.MATCH_STATEMENT_GRID));
      assertNull(StatementLineFilter.discardedLineError(true, line, Usage.MATCH_STATEMENT_GRID));
    }

    /** {@code markDiscarded} reads each account once and marks every listed row. */
    @Test
    @DisplayName("markDiscarded reads each account once and marks every listed row")
    @SuppressWarnings("unchecked")
    void markDiscardedMarksRowsPerAccount() throws Exception {
      deployOneHook();
      FIN_FinancialAccount account = account();
      FIN_BankStatement s1 = mock(FIN_BankStatement.class);
      FIN_BankStatement s2 = mock(FIN_BankStatement.class);
      when(s1.getAccount()).thenReturn(account);
      when(s2.getAccount()).thenReturn(account);
      when(obDal.get(FIN_BankStatement.class, "S1")).thenReturn(s1);
      when(obDal.get(FIN_BankStatement.class, "S2")).thenReturn(s2);
      OBQuery<FIN_BankStatementLine> query = mock(OBQuery.class);
      Query<String> ids = mock(Query.class);
      when(obDal.createQuery(eq(FIN_BankStatementLine.class), anyString())).thenReturn(query);
      when(query.createQuery(String.class)).thenReturn(ids);
      when(ids.list()).thenReturn(List.of("L2"));
      JSONArray rows = rows("L1", "L2");

      StatementLineFilter.markDiscarded(true, rows, List.of("S1", "S2"));

      assertFalse(rows.getJSONObject(0).getBoolean(StatementLineDiscard.KEY_DISCARDED));
      assertTrue(rows.getJSONObject(1).getBoolean(StatementLineDiscard.KEY_DISCARDED));
      assertEquals(1, hookContexts.size(), "one query per account, not per statement");
      assertEquals(Usage.MATCH_STATEMENT_GRID, hookContexts.get(0).getUsage());
    }

    /** {@code markDiscarded} never fails the listing: on an error the rows stay unmarked. */
    @Test
    @DisplayName("markDiscarded never fails the listing: on an error the rows stay unmarked")
    void markDiscardedSwallowsErrors() throws Exception {
      deployOneHook();
      when(obDal.get(FIN_BankStatement.class, "S1")).thenThrow(new IllegalStateException("db"));
      JSONArray rows = rows("L1");

      StatementLineFilter.markDiscarded(true, rows, List.of("S1"));

      assertFalse(rows.getJSONObject(0).has(StatementLineDiscard.KEY_DISCARDED));
    }

    /** Discards splits the excluded rows off, answers status=discarded, counts them. */
    @Test
    @DisplayName("discards splits the excluded rows off, answers status=discarded, counts them")
    @SuppressWarnings("unchecked")
    void discardsHonoured() throws Exception {
      deployOneHook();
      OBQuery<FIN_BankStatementLine> query = mock(OBQuery.class);
      Query<String> ids = mock(Query.class);
      when(obDal.createQuery(eq(FIN_BankStatementLine.class), anyString())).thenReturn(query);
      when(query.createQuery(String.class)).thenReturn(ids);
      when(ids.list()).thenReturn(List.of("L2"));

      StatementLineFilter.Discards discards =
          StatementLineFilter.discards(true, account(), Usage.MATCH_STATEMENT_GRID);
      JSONArray[] split = discards.split(rows("L1", "L2"));
      JSONObject counts = new JSONObject();
      discards.putCount(counts, split[1].length());

      assertTrue(discards.isHonoured());
      assertEquals(List.of("L1"), ids(split[0]));
      assertEquals(List.of("L2"), ids(split[1]));
      assertTrue(discards.listsOnlyDiscarded(StatementLineFilter.STATUS_DISCARDED));
      assertFalse(discards.listsOnlyDiscarded("pending"));
      assertEquals(1, counts.getInt(StatementLineDiscard.KEY_DISCARDED));
    }
  }

  // ── bank-bounce-suggestions off ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("with bank-bounce-suggestions off nothing is honoured, hook or not")
  class FlagOff {

    /** Deploys one hook: with the flag off it must not even be asked. */
    @BeforeEach
    void deployTheHook() {
      deployOneHook();
    }

    /** Hql answers the empty condition, adds no parameter and asks no hook. */
    @Test
    @DisplayName("hql answers the empty condition, adds no parameter and asks no hook")
    void noCondition() {
      Map<String, Object> params = new HashMap<>();

      assertEquals("", StatementLineFilter.hql(false, "bsl", account(), Usage.AUTO_MATCH_CANDIDATES,
          params));
      assertTrue(params.isEmpty());
      assertTrue(hookContexts.isEmpty());
    }

    /** {@code isExcluded} is false and discardedLineError null, with no query. */
    @Test
    @DisplayName("isExcluded is false and discardedLineError null, with no query")
    void nothingRefused() {
      FIN_BankStatementLine line = lineOf("L1", account());

      assertFalse(StatementLineFilter.isExcluded(false, line, Usage.MATCH_STATEMENT_GRID));
      assertNull(StatementLineFilter.discardedLineError(false, line, Usage.AUTO_MATCH_CANDIDATES));
      verify(obDal, never()).createQuery(eq(FIN_BankStatementLine.class), anyString());
    }

    /** {@code markDiscarded} puts no discarded marker on any row and reads nothing. */
    @Test
    @DisplayName("markDiscarded puts no discarded marker on any row and reads nothing")
    void noMarker() throws Exception {
      JSONArray rows = rows("L1", "L2");

      StatementLineFilter.markDiscarded(false, rows, List.of("S1"));

      assertFalse(rows.getJSONObject(0).has(StatementLineDiscard.KEY_DISCARDED));
      assertFalse(rows.getJSONObject(1).has(StatementLineDiscard.KEY_DISCARDED));
      verify(obDal, never()).get(eq(FIN_BankStatement.class), anyString());
      verify(obDal, never()).createQuery(eq(FIN_BankStatementLine.class), anyString());
    }

    /** Discards keeps every row, ignores status=discarded and adds no count. */
    @Test
    @DisplayName("discards keeps every row, ignores status=discarded and adds no count")
    void discardsNotHonoured() throws Exception {
      StatementLineFilter.Discards discards =
          StatementLineFilter.discards(false, account(), Usage.MATCH_STATEMENT_GRID);
      JSONArray[] split = discards.split(rows("L1", "L2"));
      JSONObject counts = new JSONObject();
      discards.putCount(counts, split[1].length());

      assertFalse(discards.isHonoured());
      assertEquals(List.of("L1", "L2"), ids(split[0]));
      assertEquals(0, split[1].length());
      assertFalse(discards.listsOnlyDiscarded(StatementLineFilter.STATUS_DISCARDED));
      assertFalse(counts.has(StatementLineDiscard.KEY_DISCARDED));
      verify(obDal, never()).createQuery(eq(FIN_BankStatementLine.class), anyString());
    }
  }

  // ── the flag is handed in, never read ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("the flag is never read here: the action entry evaluates it once and hands it down")
  class FlagHandedIn {

    /**
     * {@code isExcluded} with a hook takes the flag it is handed: on, the hook's condition and one
     * query decide; off, nothing is asked. The evaluator is never called.
     */
    @Test
    @DisplayName("isExcluded takes the flag it is handed and never reads it")
    @SuppressWarnings("unchecked")
    void isExcludedTakesTheFlagItIsHanded() {
      deployOneHook();
      OBQuery<FIN_BankStatementLine> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FIN_BankStatementLine.class), anyString())).thenReturn(query);
      when(query.count()).thenReturn(1);

      assertTrue(StatementLineFilter.isExcluded(true, lineOf("L1", account()),
          Usage.MATCH_STATEMENT_GRID));
      assertFalse(StatementLineFilter.isExcluded(false, lineOf("L1", account()),
          Usage.MATCH_STATEMENT_GRID));

      verify(obDal, times(1)).createQuery(eq(FIN_BankStatementLine.class), anyString());
      flag.verifyNoInteractions();
    }

    /**
     * {@code discards}, {@code hql} and {@code markDiscarded} take the flag they are handed and
     * never call the evaluator.
     */
    @Test
    @DisplayName("discards, hql and markDiscarded take the flag they are handed and never read it")
    @SuppressWarnings("unchecked")
    void theOtherGatesTakeTheFlagTheyAreHanded() throws Exception {
      deployOneHook();
      OBQuery<FIN_BankStatementLine> query = mock(OBQuery.class);
      Query<String> ids = mock(Query.class);
      when(obDal.createQuery(eq(FIN_BankStatementLine.class), anyString())).thenReturn(query);
      when(query.createQuery(String.class)).thenReturn(ids);
      when(ids.list()).thenReturn(List.of("L2"));

      assertTrue(StatementLineFilter.discards(true, account(), Usage.MATCH_STATEMENT_GRID)
          .isHonoured());
      assertFalse(StatementLineFilter.discards(false, account(), Usage.MATCH_STATEMENT_GRID)
          .isHonoured());
      assertEquals("", StatementLineFilter.hql(false, "bsl", account(),
          Usage.AUTO_MATCH_CANDIDATES, new HashMap<>()));
      StatementLineFilter.markDiscarded(false, rows("L1"), List.of("S1"));

      flag.verifyNoInteractions();
    }
  }

  // ── pure row helpers ─────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("row helpers")
  class Rows {

    /** {@code splitRows} keeps order in both halves and skips non-object rows. */
    @Test
    @DisplayName("splitRows keeps order in both halves and skips non-object rows")
    void splitRows() throws Exception {
      JSONArray raw = rows("L1", "L2", "L3", "L4");
      raw.put("not-a-row");

      JSONArray[] split = StatementLineFilter.splitRows(raw, Set.of("L2", "L4"));

      assertEquals(List.of("L1", "L3"), ids(split[0]));
      assertEquals(List.of("L2", "L4"), ids(split[1]));
    }

    /** {@code splitRows} with nothing excluded keeps every row. */
    @Test
    @DisplayName("splitRows with nothing excluded keeps every row")
    void splitRowsNothingExcluded() throws Exception {
      JSONArray[] split = StatementLineFilter.splitRows(rows("L1", "L2"), Set.of());

      assertEquals(List.of("L1", "L2"), ids(split[0]));
      assertEquals(0, split[1].length());
    }

    /** {@code markRows} puts discarded true/false on every row by id. */
    @Test
    @DisplayName("markRows puts discarded true/false on every row by id")
    void markRows() throws Exception {
      JSONArray rows = rows("L1", "L2");
      rows.put(new JSONObject());

      StatementLineFilter.markRows(rows, Set.of("L2"));

      assertFalse(rows.getJSONObject(0).getBoolean(StatementLineDiscard.KEY_DISCARDED));
      assertTrue(rows.getJSONObject(1).getBoolean(StatementLineDiscard.KEY_DISCARDED));
      assertFalse(rows.getJSONObject(2).getBoolean(StatementLineDiscard.KEY_DISCARDED),
          "a row without id is never discarded");
    }

    /**
     * The {@code status=discarded} data: every row marked discarded with nothing reconciled, the
     * total and the lone count, and each row's {@code bounceLink}; the lines are read in ONE query
     * for all the rows, never one read per row.
     */
    @Test
    @DisplayName("discardedLinesData: discarded state, nothing reconciled, the total, the count and each bounceLink")
    @SuppressWarnings("unchecked")
    void discardedLinesData() throws Exception {
      FIN_BankStatementLine linked = mock(FIN_BankStatementLine.class);
      when(linked.getId()).thenReturn("L1");
      FIN_BankStatementLine counterpart = mock(FIN_BankStatementLine.class);
      when(counterpart.getId()).thenReturn("L9");
      when(linked.get("etgoBouncePairLine")).thenReturn(counterpart);
      when(linked.get("etgoBounceSignal")).thenReturn("return_text");
      when(linked.get("etgoBounceConfidence")).thenReturn("high");
      FIN_BankStatementLine alone = mock(FIN_BankStatementLine.class);
      when(alone.getId()).thenReturn("L2");
      OBQuery<FIN_BankStatementLine> query = mock(OBQuery.class);
      ArgumentCaptor<String> where = ArgumentCaptor.forClass(String.class);
      when(obDal.createQuery(eq(FIN_BankStatementLine.class), where.capture())).thenReturn(query);
      when(query.list()).thenReturn(List.of(alone, linked));
      JSONArray rows = rows("L1", "L2");
      rows.getJSONObject(0).put(ReconciliationHandler.KEY_AMOUNT, "-50.25");
      rows.getJSONObject(1).put(ReconciliationHandler.KEY_AMOUNT, "20");

      JSONObject data;
      try (MockedStatic<ModelProvider> model = mockModel()) {
        data = StatementLineFilter.discardedLinesData(rows);
      }

      assertEquals(0, new BigDecimal("-30.25")
          .compareTo(new BigDecimal(data.get(ReconciliationHandler.KEY_TOTAL).toString())));
      assertEquals(2, data.getJSONObject(ReconciliationHandler.KEY_COUNTS)
          .getInt(StatementLineFilter.STATUS_DISCARDED));
      assertEquals(1, data.getJSONObject(ReconciliationHandler.KEY_COUNTS).length(),
          "only the discarded count: this filter does not run the matching engine");
      JSONArray lines = data.getJSONArray("lines");
      for (int i = 0; i < lines.length(); i++) {
        JSONObject row = lines.getJSONObject(i);
        assertEquals("discarded", row.getString("state"));
        assertEquals("discarded", row.getString(ReconciliationHandler.KEY_STATUS));
        assertTrue(row.getBoolean(StatementLineDiscard.KEY_DISCARDED));
        assertEquals(0, row.getInt("reconciledPct"));
        assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(row.get("reconciledAmount").toString())));
      }
      JSONObject link = lines.getJSONObject(0).getJSONObject(StatementLineFilter.KEY_BOUNCE_LINK);
      assertEquals("L9", link.getString(BankBouncePairService.KEY_COUNTERPART_LINE_ID));
      assertEquals("return_text", link.getString("signal"));
      assertEquals("high", link.getString("confidence"));
      assertTrue(link.isNull("discardedAt"));
      assertTrue(lines.getJSONObject(1).isNull(StatementLineFilter.KEY_BOUNCE_LINK),
          "a line discarded on its own has no pair");
      verify(obDal, times(1)).createQuery(eq(FIN_BankStatementLine.class), anyString());
      assertTrue(where.getValue().contains("bsl.id in (:etgoLineIds)"), where.getValue());
      verify(query).setNamedParameter("etgoLineIds", Set.of("L1", "L2"));
      verify(obDal, never()).get(eq(FIN_BankStatementLine.class), anyString());
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  /** A running model that knows the four EM_ETGO_Bounce_* columns under fixed property names. */
  private static MockedStatic<ModelProvider> mockModel() {
    Entity entity = mock(Entity.class);
    Map<String, String> names = Map.of(
        BankBouncePairService.COL_PAIR_LINE, "etgoBouncePairLine",
        BankBouncePairService.COL_DISCARDED_AT, "etgoBounceDiscarded",
        BankBouncePairService.COL_SIGNAL, "etgoBounceSignal",
        BankBouncePairService.COL_CONFIDENCE, "etgoBounceConfidence");
    names.forEach((column, name) -> {
      Property property = mock(Property.class);
      when(property.getName()).thenReturn(name);
      when(entity.getPropertyByColumnName(column, false)).thenReturn(property);
    });
    ModelProvider provider = mock(ModelProvider.class);
    when(provider.getEntity(FIN_BankStatementLine.ENTITY_NAME)).thenReturn(entity);
    MockedStatic<ModelProvider> model = mockStatic(ModelProvider.class);
    model.when(ModelProvider::getInstance).thenReturn(provider);
    return model;
  }

  /**
   * Rows carrying only their ids.
   *
   * @param ids the ids
   * @return the rows
   * @throws Exception when a row cannot be written
   */
  private static JSONArray rows(String... ids) throws Exception {
    JSONArray rows = new JSONArray();
    for (String id : ids) {
      rows.put(new JSONObject().put(ReconciliationHandler.KEY_ID, id));
    }
    return rows;
  }

  /**
   * The ids of the rows, in order.
   *
   * @param rows the rows
   * @return their ids
   * @throws Exception when a row cannot be read
   */
  private static List<String> ids(JSONArray rows) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < rows.length(); i++) {
      ids.add(rows.getJSONObject(i).getString(ReconciliationHandler.KEY_ID));
    }
    return ids;
  }
}
