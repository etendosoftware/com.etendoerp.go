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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.hook.BankStatementLineFilterContext.Usage;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

import com.etendoerp.psd2.bank.integration.data.PisPayment;

/**
 * {@code discardPair} and {@code restorePair} of the bounce pairs (ETP-5581): the validation order
 * of the coded refusals, what a discard writes on BOTH lines (the PSD2 flag and button, kept in
 * sync, and the four {@code EM_ETGO_Bounce_*} link columns), that a refusal writes nothing, the
 * flag gate on the discard and on the restore, and the restore that always acts on the pair.
 *
 * <p>The DAL model is simulated column by column: {@link ModelProvider} answers a property for each
 * column the running model "has", and each line keeps its values in a map, so the service writes
 * through {@link StatementLineDiscard#set} exactly as in production. {@link BankBounceDetector}
 * runs for real over stubbed queries (PSD2 payments, open movements), so the roles, the signal and
 * the payment guard are the production ones.
 *
 * @covers com.etendoerp.go.schemaforge.BankBouncePairService
 * @covers com.etendoerp.go.schemaforge.StatementLineDiscard
 * @covers com.etendoerp.go.schemaforge.BankBounceErrors
 */
@DisplayName("Bounce pairs — discardPair / restorePair")
class BankBouncePairDiscardTest {

  private static final String ACCOUNT_ID = "ACC-1";
  private static final String OTHER_ACCOUNT_ID = "ACC-2";
  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final int DATE_TOL_DAYS = 3;

  /** Column name (upper case) → DAL property name: what the running model maps. */
  private final Map<String, String> mappedColumns = new HashMap<>();

  private MockedStatic<ModelProvider> modelStatic;
  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<BankBounceSuggestionsFlag> flag;
  private MockedStatic<StatementLineFilter> filter;

  private OBDal dal;
  private ReconciliationHandler handler;
  private FIN_FinancialAccount account;
  private FIN_FinancialAccount otherAccount;
  private final List<FIN_BankStatementLine> pending = new ArrayList<>();
  private final List<Object[]> pisRows = new ArrayList<>();
  private final List<Object[]> openMovementRows = new ArrayList<>();
  private final List<String> scheduleDetailPaymentIds = new ArrayList<>();
  private final Set<FIN_BankStatementLine> saved = new HashSet<>();

  @BeforeEach
  void setUp() {
    for (String column : new String[] { StatementLineDiscard.COL_IS_DISCARDED,
        StatementLineDiscard.COL_DISCARD_LINE, StatementLineDiscard.COL_TRANSACTION_IDENTIFIER,
        BankBouncePairService.COL_PAIR_LINE, BankBouncePairService.COL_DISCARDED_AT,
        BankBouncePairService.COL_SIGNAL, BankBouncePairService.COL_CONFIDENCE,
        ReactivationSupport.COL_MATCH_GROUP }) {
      mappedColumns.put(column.toUpperCase(Locale.ROOT), propertyNameOf(column));
    }

    ModelProvider model = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    when(model.getEntity(FIN_BankStatementLine.ENTITY_NAME)).thenReturn(entity);
    when(entity.getPropertyByColumnName(anyString(), eq(false))).thenAnswer(inv -> {
      String name = mappedColumns.get(((String) inv.getArgument(0)).toUpperCase(Locale.ROOT));
      if (name == null) {
        return null;
      }
      Property property = mock(Property.class);
      when(property.getName()).thenReturn(name);
      return property;
    });
    modelStatic = mockStatic(ModelProvider.class);
    modelStatic.when(ModelProvider::getInstance).thenReturn(model);

    dal = mock(OBDal.class);
    doAnswer(inv -> saved.add(inv.getArgument(0))).when(dal).save(any());
    stubQuery(PisPayment.class, Object[].class, pisRows);
    stubQuery(FIN_FinaccTransaction.class, Object[].class, openMovementRows);
    stubQuery(FIN_PaymentScheduleDetail.class, String.class, scheduleDetailPaymentIds);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);

    // No AD_MESSAGE text in a unit test: the coded refusal falls back to the key.
    messages = mockStatic(OBMessageUtils.class);
    flag = mockStatic(BankBounceSuggestionsFlag.class);
    flag.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(true);
    // No BankStatementLineFilterHook deployed: nothing is excluded unless a test says so.
    filter = mockStatic(StatementLineFilter.class);

    account = account(ACCOUNT_ID);
    otherAccount = account(OTHER_ACCOUNT_ID);
    handler = mock(ReconciliationHandler.class);
    when(handler.loadAccount(ACCOUNT_ID)).thenReturn(account);
    when(handler.loadTolerances(ACCOUNT_ID)).thenReturn(
        new BigDecimal[] { BigDecimal.valueOf(DATE_TOL_DAYS), BigDecimal.ZERO });
    when(handler.loadPendingLines(ACCOUNT_ID)).thenReturn(pending);
  }

  @AfterEach
  void tearDown() {
    filter.close();
    flag.close();
    messages.close();
    dalStatic.close();
    modelStatic.close();
  }

  // ---------------------------------------------------------------------------
  // discardPair
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("discardPair — success")
  class DiscardSuccess {

    @Test
    @DisplayName("both lines are discarded, the PSD2 button follows the flag and each links the other")
    void discardsAndLinksBothLines() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME SL", "TRANSFER TO ACME");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(2), "ACME SL", "ABONO");

      NeoResponse response = BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = data(response);
      assertTrue(data.getBoolean("discarded"));
      assertEquals("L-OUT", data.getString("statementLineId"));
      assertEquals("L-IN", data.getString("counterpartLineId"));
      assertEquals(BankBounceDetector.SIGNAL_COUNTERPARTY, data.getString("signal"));
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, data.getString("confidence"));

      for (Line[] side : new Line[][] { { original, bounce }, { bounce, original } }) {
        Line line = side[0];
        assertEquals(Boolean.TRUE, line.value(StatementLineDiscard.COL_IS_DISCARDED));
        assertEquals(StatementLineDiscard.BUTTON_DISCARDED,
            line.value(StatementLineDiscard.COL_DISCARD_LINE),
            "the PSD2 button must say Restore while the line is discarded");
        assertSame(side[1].mock, line.value(BankBouncePairService.COL_PAIR_LINE));
        assertNotNull(line.value(BankBouncePairService.COL_DISCARDED_AT));
        assertEquals(BankBounceDetector.SIGNAL_COUNTERPARTY,
            line.value(BankBouncePairService.COL_SIGNAL));
        assertEquals(BankBounceDetector.CONFIDENCE_REVIEW,
            line.value(BankBouncePairService.COL_CONFIDENCE));
        verify(dal).save(line.mock);
      }
      assertEquals(original.value(BankBouncePairService.COL_DISCARDED_AT),
          bounce.value(BankBouncePairService.COL_DISCARDED_AT),
          "both lines share one discard time");
      verify(dal, Mockito.atLeastOnce()).flush();
    }

    @Test
    @DisplayName("a PSD2 payment link is stored as pis_link/high, whichever line the request names first")
    void storesTheDetectedSignalWhateverTheRequestOrder() throws Exception {
      Line original = pendingLine("L-OUT", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line bounce = pendingLine("L-IN", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });

      // The bounce is named first: the service still resolves the roles from the data.
      NeoResponse response = BankBouncePairService.discardPair(handler,
          body(bounce.id, original.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals(BankBounceDetector.SIGNAL_PIS_LINK, data(response).getString("signal"));
      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, data(response).getString("confidence"));
      assertSame(bounce.mock, original.value(BankBouncePairService.COL_PAIR_LINE));
      assertSame(original.mock, bounce.value(BankBouncePairService.COL_PAIR_LINE));
      assertEquals(BankBounceDetector.SIGNAL_PIS_LINK,
          original.value(BankBouncePairService.COL_SIGNAL));
    }

    @Test
    @DisplayName("a pair with no signal is accepted with a null signal and review confidence")
    void pairWithoutSignalIsAcceptedForReview() throws Exception {
      Line original = pendingLine("L-OUT", "-12.00", DAY, "ALPHA", "PAYMENT");
      Line bounce = pendingLine("L-IN", "12.00", DAY.plusDays(5), "BETA", "CREDIT");

      NeoResponse response = BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id));

      assertEquals(200, response.getHttpStatus());
      assertSame(JSONObject.NULL, data(response).get("signal"));
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, data(response).getString("confidence"));
      assertNull(original.value(BankBouncePairService.COL_SIGNAL));
      assertEquals(Boolean.TRUE, bounce.value(StatementLineDiscard.COL_IS_DISCARDED));
    }

    @Test
    @DisplayName("the bounce may come exactly 60 days after the original")
    void sixtyDaysIsInsideTheWindow() throws Exception {
      Line original = pendingLine("L-OUT", "-30.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "30.00", DAY.plusDays(BankBounceDetector.WINDOW_DAYS),
          "ACME", "Y");

      assertEquals(200, BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id)).getHttpStatus());
    }
    @Test
    @DisplayName("an adjacent pair stores the signal and confidence of the rules, never high for adjacency")
    void adjacentPairStoresTheRulesSignalAndConfidence() throws Exception {
      // L-FAR is an eligible original too, but only L-OUT is L-IN's statement neighbour.
      Line far = pendingLine("L-FAR", "-50.00", DAY, "ACME", "TRANSFER TO ACME");
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "TRANSFER TO ACME");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "DEVOLUCION");
      onStatement("ST-1", far, original, bounce);

      NeoResponse response = BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals(BankBounceDetector.SIGNAL_RETURN_TEXT, data(response).getString("signal"));
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, data(response).getString("confidence"),
          "bounce wording a day later stays review although adjacency chose the original");
      for (Line line : new Line[] { original, bounce }) {
        assertEquals(BankBounceDetector.SIGNAL_RETURN_TEXT,
            line.value(BankBouncePairService.COL_SIGNAL));
        assertEquals(BankBounceDetector.CONFIDENCE_REVIEW,
            line.value(BankBouncePairService.COL_CONFIDENCE));
      }
      assertNothingWritten(far);
    }

    @Test
    @DisplayName("an adjacent ambiguous pair is discarded on the neighbour the user chose, for review")
    void adjacentAmbiguousPairOnTheChosenNeighbour() throws Exception {
      Line before = pendingLine("L-BEFORE", "-0.61", DAY, "", "TRANSFERENCIA INMEDIATA");
      Line bounce = pendingLine("L-RET", "0.61", DAY, "", "RETROCESIÓN TRANSFERENCIA INMEDIATA");
      Line after = pendingLine("L-AFTER", "-0.61", DAY, "", "TRANSFERENCIA INMEDIATA");
      onStatement("ST-1", before, bounce, after);

      NeoResponse response = BankBouncePairService.discardPair(handler,
          body(after.id, bounce.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals("L-AFTER", data(response).getString("statementLineId"));
      assertEquals(BankBounceDetector.SIGNAL_RETURN_TEXT, data(response).getString("signal"));
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, data(response).getString("confidence"),
          "the pair came out of an ambiguous one: review, not the high of same-day wording");
      assertSame(bounce.mock, after.value(BankBouncePairService.COL_PAIR_LINE));
      assertSame(after.mock, bounce.value(BankBouncePairService.COL_PAIR_LINE));
      assertEquals(Boolean.TRUE, after.value(StatementLineDiscard.COL_IS_DISCARDED));
      assertNothingWritten(before);
    }
  }

  @Nested
  @DisplayName("discardPair — refusals, each in its order, nothing written")
  class DiscardRefusals {

    @Test
    @DisplayName("flag off → 403 BOUNCE_SUGGESTIONS_OFF before anything is read")
    void flagOffRefusesAndReadsNothing() throws Exception {
      flag.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(false);
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");

      NeoResponse response = BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id));

      assertCoded(response, 403, BankBounceErrors.CODE_SUGGESTIONS_OFF);
      verify(handler, never()).loadAccount(anyString());
      verify(handler, never()).loadLine(anyString());
      assertNothingWritten(original, bounce);
    }

    @Test
    @DisplayName("LINE_DISCARDED: a line already discarded wins over every later check")
    void alreadyDiscardedWinsOverEverything() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      // Already discarded AND inactive AND same sign: the first check answers.
      Line other = pendingLine("L-OTHER", "-50.00", DAY.plusDays(1), "ACME", "Y");
      other.values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      when(other.mock.isActive()).thenReturn(false);

      NeoResponse response = BankBouncePairService.discardPair(handler,
          body(original.id, other.id));

      assertCoded(response, 409, StatementLineDiscard.CODE_LINE_DISCARDED);
      assertEquals("ETGO_StatementLineDiscarded", response.getBody().getJSONObject("error")
          .getJSONArray(NeoProcessService.MESSAGE_KEYS).getString(0));
      assertNothingWritten(original, other);
    }

    @Test
    @DisplayName("DISCARD_NOT_ALLOWED: a discarded line that is reconciled counts as reconciled")
    void discardedButReconciledLineCountsAsReconciled() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      // Discarded while the flag was on, then reconciled while it was off.
      Line other = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      other.values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      when(other.mock.getFinancialAccountTransaction()).thenReturn(
          mock(FIN_FinaccTransaction.class));

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, other.id)), 409,
          BankBounceErrors.CODE_DISCARD_NOT_ALLOWED);
      assertNothingWritten(original, other);
    }


    @Test
    @DisplayName("DISCARD_NOT_ALLOWED: a matched PSD2-discarded line, through the real hook condition")
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void realHookMatchedDiscardedLineIsNotAllowed() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line matched = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      matched.values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      when(matched.mock.getFinancialAccountTransaction()).thenReturn(
          mock(FIN_FinaccTransaction.class));
      Map<String, Line> byId = Map.of(original.id, original, matched.id, matched);
      filter.close();
      filter = mockStatic(StatementLineFilter.class, Mockito.CALLS_REAL_METHODS);
      org.openbravo.advpaymentmngt.hook.BankStatementLineFilterHook psd2Like =
          (alias, context, params) -> alias + ".psd2IsDiscarded = false";
      OBQuery lineQuery = mock(OBQuery.class);
      Map<String, Object>[] bound = new Map[1];
      doAnswer(inv -> {
        bound[0] = inv.getArgument(0);
        return inv.getMock();
      }).when(lineQuery).setNamedParameters(any());
      // The database evaluates "not (psd2IsDiscarded = false)" on the one line asked for.
      when(lineQuery.count()).thenAnswer(inv -> Boolean.TRUE.equals(
          byId.get((String) bound[0].get("etgoLineId")).value(StatementLineDiscard.COL_IS_DISCARDED))
              ? 1 : 0);
      when(dal.createQuery(eq(FIN_BankStatementLine.class), anyString())).thenReturn(lineQuery);
      try (MockedStatic<org.openbravo.base.weld.WeldUtils> weld =
          mockStatic(org.openbravo.base.weld.WeldUtils.class)) {
        weld.when(() -> org.openbravo.base.weld.WeldUtils.getInstances(
            org.openbravo.advpaymentmngt.hook.BankStatementLineFilterHook.class))
            .thenReturn(List.of(psd2Like));

        assertCoded(BankBouncePairService.discardPair(handler, body(original.id, matched.id)), 409,
            BankBounceErrors.CODE_DISCARD_NOT_ALLOWED);
      }
    }

    @Test
    @DisplayName("LINE_DISCARDED: a line another module's hook excludes counts as discarded")
    void hookExcludedLineIsDiscarded() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      filter.when(() -> StatementLineFilter.isExcluded(bounce.mock, Usage.AUTO_MATCH_CANDIDATES))
          .thenReturn(true);

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, bounce.id)), 409,
          StatementLineDiscard.CODE_LINE_DISCARDED);
      assertNothingWritten(original, bounce);
    }

    @Test
    @DisplayName("DISCARD_NOT_ALLOWED: a reconciled line, before LINE_NOT_PENDING and the shape")
    void reconciledLineIsNotDiscardable() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line other = pendingLine("L-OTHER", "-50.00", DAY.plusDays(1), "ACME", "Y");
      when(other.mock.getFinancialAccountTransaction()).thenReturn(
          mock(FIN_FinaccTransaction.class));
      when(other.mock.isActive()).thenReturn(false);

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, other.id)), 409,
          BankBounceErrors.CODE_DISCARD_NOT_ALLOWED);
      assertNothingWritten(original, other);
    }

    @Test
    @DisplayName("DISCARD_NOT_ALLOWED: a line the PSD2 synchronization did not import")
    void nonPsd2LineIsNotDiscardable() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line manual = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      manual.values.remove(prop(StatementLineDiscard.COL_TRANSACTION_IDENTIFIER));

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, manual.id)), 409,
          BankBounceErrors.CODE_DISCARD_NOT_ALLOWED);
      assertNothingWritten(original, manual);
    }

    @Test
    @DisplayName("DISCARD_NOT_ALLOWED: the PSD2 discard columns are not in the running model")
    void psd2DiscardNotInstalled() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      mappedColumns.remove(StatementLineDiscard.COL_IS_DISCARDED.toUpperCase(Locale.ROOT));

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, bounce.id)), 409,
          BankBounceErrors.CODE_DISCARD_NOT_ALLOWED);
      assertNothingWritten(original, bounce);
    }

    @Test
    @DisplayName("LINE_NOT_PENDING: an inactive line, before the shape checks")
    void inactiveLineIsNotPending() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line other = pendingLine("L-OTHER", "-50.00", DAY.plusDays(1), "ACME", "Y");
      when(other.mock.isActive()).thenReturn(false);

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, other.id)), 409,
          BankBounceErrors.CODE_LINE_NOT_PENDING);
      assertNothingWritten(original, other);
    }

    @Test
    @DisplayName("LINE_NOT_PENDING: a line on a draft statement")
    void draftStatementLineIsNotPending() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      when(bounce.mock.getBankStatement().isProcessed()).thenReturn(false);

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, bounce.id)), 409,
          BankBounceErrors.CODE_LINE_NOT_PENDING);
      assertNothingWritten(original, bounce);
    }

    @Test
    @DisplayName("LINE_NOT_PENDING: a line inside a split (match) group")
    void splitGroupLineIsNotPending() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      bounce.values.put(prop(ReactivationSupport.COL_MATCH_GROUP), "GROUP-1");

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, bounce.id)), 409,
          BankBounceErrors.CODE_LINE_NOT_PENDING);
      assertNothingWritten(original, bounce);
    }

    @Test
    @DisplayName("LINE_NOT_PENDING: both checks pass but the line is not an auto-match candidate")
    void lineMissingFromThePendingPoolIsNotPending() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      pending.remove(bounce.mock);

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, bounce.id)), 409,
          BankBounceErrors.CODE_LINE_NOT_PENDING);
      assertNothingWritten(original, bounce);
    }

    @Test
    @DisplayName("SIGN_MISMATCH: same sign, before the amount check")
    void sameSignWinsOverAmount() throws Exception {
      Line a = pendingLine("L-A", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-B", "-49.00", DAY.plusDays(1), "ACME", "Y");

      assertCoded(BankBouncePairService.discardPair(handler, body(a.id, b.id)), 409,
          BankBounceErrors.CODE_SIGN_MISMATCH);
      assertNothingWritten(a, b);
    }

    @Test
    @DisplayName("SIGN_MISMATCH: a zero-amount line has no sign")
    void zeroAmountIsASignMismatch() throws Exception {
      Line a = pendingLine("L-A", "0.00", DAY, "ACME", "X");
      Line b = pendingLine("L-B", "0.00", DAY.plusDays(1), "ACME", "Y");

      assertCoded(BankBouncePairService.discardPair(handler, body(a.id, b.id)), 409,
          BankBounceErrors.CODE_SIGN_MISMATCH);
    }

    @Test
    @DisplayName("AMOUNT_MISMATCH: opposite sign, different amount, before the payment guard")
    void differentAmountWinsOverPayment() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "49.99", DAY.plusDays(1), "ACME", "Y");
      openMovement("FT-1", "PAY-1", DAY, "50.00", "ACME");

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, bounce.id)), 409,
          BankBounceErrors.CODE_AMOUNT_MISMATCH);
      assertNothingWritten(original, bounce);
    }

    @Test
    @DisplayName("DISCARD_NOT_ALLOWED: the bounce 61 days after the original, before the payment guard")
    void outsideTheWindowWinsOverPayment() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00",
          DAY.plusDays(BankBounceDetector.WINDOW_DAYS + 1L), "ACME", "Y");
      openMovement("FT-1", "PAY-1", DAY, "50.00", "ACME");

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, bounce.id)), 409,
          BankBounceErrors.CODE_DISCARD_NOT_ALLOWED);
      assertNothingWritten(original, bounce);
    }

    @Test
    @DisplayName("HAS_PAYMENT: the original is the bank movement of a real ERP payment")
    void lineWithAPaymentIsRefused() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      openMovement("FT-1", "PAY-1", DAY, "50.00", "ACME");

      NeoResponse response = BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id));

      assertCoded(response, 409, BankBounceErrors.CODE_HAS_PAYMENT);
      assertEquals("ETGO_BounceHasPayment", response.getBody().getJSONObject("error")
          .getJSONArray(NeoProcessService.MESSAGE_KEYS).getString(0));
      assertNothingWritten(original, bounce);
    }

    @Test
    @DisplayName("400/404 before the coded checks: same id, missing id, unknown line, foreign line")
    void requestErrors() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line foreign = pendingLine("L-FOREIGN", "50.00", DAY.plusDays(1), "ACME", "Y");
      when(foreign.mock.getBankStatement().getAccount()).thenReturn(otherAccount);

      assertEquals(400, BankBouncePairService.discardPair(handler,
          body(original.id, original.id)).getHttpStatus());
      assertEquals(400, BankBouncePairService.discardPair(handler,
          new JSONObject().put("financialAccountId", ACCOUNT_ID)
              .put("statementLineId", original.id)).getHttpStatus());
      assertEquals(404, BankBouncePairService.discardPair(handler,
          body(original.id, "L-NOPE")).getHttpStatus());
      assertEquals(400, BankBouncePairService.discardPair(handler,
          body(original.id, foreign.id)).getHttpStatus());
      assertEquals(400, BankBouncePairService.discardPair(handler,
          body(original.id, foreign.id).put("financialAccountId", "ACC-NOPE")).getHttpStatus());
      assertNothingWritten(original, foreign);
    }
  }

  // ---------------------------------------------------------------------------
  // restorePair
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("restorePair — always the pair, gated by the flag like the discard")
  class Restore {

    @Test
    @DisplayName("flag off → 403 BOUNCE_SUGGESTIONS_OFF before anything is read, nothing restored")
    void flagOffRefusesAndRestoresNothing() throws Exception {
      flag.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(false);
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      discardedPair(a, b);

      NeoResponse response = BankBouncePairService.restorePair(handler, restoreBody(b.id));

      assertCoded(response, 403, BankBounceErrors.CODE_SUGGESTIONS_OFF);
      verify(handler, never()).loadAccount(anyString());
      verify(handler, never()).loadLine(anyString());
      assertEquals(Boolean.TRUE, a.value(StatementLineDiscard.COL_IS_DISCARDED));
      assertEquals(Boolean.TRUE, b.value(StatementLineDiscard.COL_IS_DISCARDED));
      verify(dal, never()).save(any());
    }

    @Test
    @DisplayName("restores both lines and clears the four link columns on both")
    void restoresBothLinesAndClearsTheLink() throws Exception {
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      discardedPair(a, b);

      NeoResponse response = BankBouncePairService.restorePair(handler, restoreBody(b.id));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = data(response);
      assertEquals(List.of("L-IN", "L-OUT"), strings(data.getJSONArray("restored")));
      assertEquals("L-IN", data.getString("lineId"));
      assertEquals("L-OUT", data.getString("counterpartLineId"));
      for (Line line : new Line[] { a, b }) {
        assertEquals(Boolean.FALSE, line.value(StatementLineDiscard.COL_IS_DISCARDED));
        assertEquals(StatementLineDiscard.BUTTON_NOT_DISCARDED,
            line.value(StatementLineDiscard.COL_DISCARD_LINE));
        for (String column : linkColumns()) {
          assertNull(line.value(column), column + " must be cleared on " + line.id);
        }
        verify(dal).save(line.mock);
      }
    }

    @Test
    @DisplayName("a discarded line with no link (discarded in Classic) is restored alone")
    void unlinkedDiscardedLineRestoresAlone() throws Exception {
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line untouched = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      a.values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      a.values.put(prop(StatementLineDiscard.COL_DISCARD_LINE), "Y");
      untouched.values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);

      NeoResponse response = BankBouncePairService.restorePair(handler, restoreBody(a.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals(List.of("L-OUT"), strings(data(response).getJSONArray("restored")));
      assertSame(JSONObject.NULL, data(response).get("counterpartLineId"));
      assertEquals(Boolean.FALSE, a.value(StatementLineDiscard.COL_IS_DISCARDED));
      assertEquals(Boolean.TRUE, untouched.value(StatementLineDiscard.COL_IS_DISCARDED),
          "a line that is not linked to it is not restored");
      verify(dal, never()).save(untouched.mock);
    }

    @Test
    @DisplayName("a line neither discarded nor linked is a 200 no-op that restores nothing")
    void plainLineIsANoOp() throws Exception {
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");

      NeoResponse response = BankBouncePairService.restorePair(handler, restoreBody(a.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals(0, data(response).getJSONArray("restored").length());
      assertSame(JSONObject.NULL, data(response).get("counterpartLineId"));
      assertFalse(a.values.containsKey(prop(StatementLineDiscard.COL_IS_DISCARDED)),
          "the discard flag is not written on a line that was not discarded");
      assertFalse(a.values.containsKey(prop(StatementLineDiscard.COL_DISCARD_LINE)));
    }

    @Test
    @DisplayName("a counterpart of another account is neither restored nor reported")
    void foreignCounterpartIsLeftAlone() throws Exception {
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line foreign = pendingLine("L-FOREIGN", "50.00", DAY.plusDays(1), "ACME", "Y");
      when(foreign.mock.getBankStatement().getAccount()).thenReturn(otherAccount);
      discardedPair(a, foreign);

      NeoResponse response = BankBouncePairService.restorePair(handler, restoreBody(a.id));

      assertEquals(List.of("L-OUT"), strings(data(response).getJSONArray("restored")));
      assertSame(JSONObject.NULL, data(response).get("counterpartLineId"));
      assertEquals(Boolean.TRUE, foreign.value(StatementLineDiscard.COL_IS_DISCARDED));
    }

    @Test
    @DisplayName("the bounce link columns not mapped yet: the line is still restored")
    void restoresWithoutTheLinkColumns() throws Exception {
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      a.values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      for (String column : linkColumns()) {
        mappedColumns.remove(column.toUpperCase(Locale.ROOT));
      }

      NeoResponse response = BankBouncePairService.restorePair(handler, restoreBody(a.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals(Boolean.FALSE, a.value(StatementLineDiscard.COL_IS_DISCARDED));
    }

    @Test
    @DisplayName("refusals: no lineId 400, unknown line 404, PSD2 not installed 409 — nothing written")
    void refusals() throws Exception {
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      a.values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);

      assertEquals(400, BankBouncePairService.restorePair(handler,
          new JSONObject().put("financialAccountId", ACCOUNT_ID)).getHttpStatus());
      assertEquals(404, BankBouncePairService.restorePair(handler, restoreBody("L-NOPE"))
          .getHttpStatus());
      mappedColumns.remove(StatementLineDiscard.COL_DISCARD_LINE.toUpperCase(Locale.ROOT));
      assertCoded(BankBouncePairService.restorePair(handler, restoreBody(a.id)), 409,
          BankBounceErrors.CODE_DISCARD_NOT_ALLOWED);
      assertEquals(Boolean.TRUE, a.value(StatementLineDiscard.COL_IS_DISCARDED));
      verify(dal, never()).save(any());
    }
  }

  // ---------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------

  /** A simulated statement line: the mock plus the column values it holds, by property name. */
  private final class Line {
    final String id;
    final FIN_BankStatementLine mock;
    final Map<String, Object> values = new HashMap<>();

    Line(String id, FIN_BankStatementLine mock) {
      this.id = id;
      this.mock = mock;
    }

    Object value(String column) {
      return values.get(prop(column));
    }
  }

  /** A PSD2-imported, active, unreconciled line of the account on a processed statement. */
  private Line pendingLine(String id, String signedAmount, LocalDate day, String partner,
      String description) {
    FIN_BankStatementLine mock = mock(FIN_BankStatementLine.class);
    Line line = new Line(id, mock);
    BigDecimal amount = new BigDecimal(signedAmount);
    when(mock.getId()).thenReturn(id);
    when(mock.getCramount()).thenReturn(amount.signum() > 0 ? amount : BigDecimal.ZERO);
    when(mock.getDramount()).thenReturn(amount.signum() < 0 ? amount.negate() : BigDecimal.ZERO);
    when(mock.getTransactionDate()).thenReturn(date(day));
    when(mock.getLineNo()).thenReturn((long) (pending.size() + 1) * 10L);
    when(mock.getDescription()).thenReturn(description);
    when(mock.getBpartnername()).thenReturn(partner);
    when(mock.isActive()).thenReturn(true);
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    when(statement.isProcessed()).thenReturn(true);
    when(statement.getAccount()).thenReturn(account);
    when(mock.getBankStatement()).thenReturn(statement);
    when(mock.get(anyString())).thenAnswer(inv -> line.values.get(inv.getArgument(0)));
    doAnswer(inv -> line.values.put(inv.getArgument(0), inv.getArgument(1)))
        .when(mock).set(anyString(), any());
    line.values.put(prop(StatementLineDiscard.COL_TRANSACTION_IDENTIFIER), "TX-" + id);
    when(handler.loadLine(id)).thenReturn(mock);
    pending.add(mock);
    return line;
  }

  /** Puts the lines on one statement, in the given order (their line numbers already follow it). */
  private static void onStatement(String statementId, Line... lines) {
    for (Line line : lines) {
      when(line.mock.getBankStatement().getId()).thenReturn(statementId);
    }
  }

  /** Marks {@code a} and {@code b} discarded and linked, as a previous discardPair left them. */
  private void discardedPair(Line a, Line b) {
    for (Line[] side : new Line[][] { { a, b }, { b, a } }) {
      side[0].values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      side[0].values.put(prop(StatementLineDiscard.COL_DISCARD_LINE), "Y");
      side[0].values.put(prop(BankBouncePairService.COL_PAIR_LINE), side[1].mock);
      side[0].values.put(prop(BankBouncePairService.COL_DISCARDED_AT), new Date());
      side[0].values.put(prop(BankBouncePairService.COL_SIGNAL), "counterparty");
      side[0].values.put(prop(BankBouncePairService.COL_CONFIDENCE), "review");
    }
  }

  /** An unreconciled movement of the account carrying a processed payment of {@code amount} out. */
  private void openMovement(String id, String paymentId, LocalDate day, String paidOut,
      String partner) {
    openMovementRows.add(new Object[] { id, paymentId, date(day), BigDecimal.ZERO,
        new BigDecimal(paidOut), partner });
    scheduleDetailPaymentIds.add(paymentId);
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private <E extends BaseOBObject, T> void stubQuery(Class<E> entity,
      Class<T> resultType, List<?> rows) {
    OBQuery obQuery = mock(OBQuery.class);
    Query hibernateQuery = mock(Query.class);
    when(dal.createQuery(eq(entity), anyString())).thenReturn(obQuery);
    when(obQuery.createQuery(resultType)).thenReturn(hibernateQuery);
    when(hibernateQuery.list()).thenAnswer(inv -> new ArrayList<>(rows));
  }

  private static FIN_FinancialAccount account(String id) {
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getId()).thenReturn(id);
    return acc;
  }

  private static Date date(LocalDate day) {
    return Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  private static String propertyNameOf(String column) {
    return "p_" + column.toLowerCase(Locale.ROOT);
  }

  private static String prop(String column) {
    return propertyNameOf(column);
  }

  private static String[] linkColumns() {
    return new String[] { BankBouncePairService.COL_PAIR_LINE,
        BankBouncePairService.COL_DISCARDED_AT, BankBouncePairService.COL_SIGNAL,
        BankBouncePairService.COL_CONFIDENCE };
  }

  private static JSONObject body(String lineId, String counterpartId) throws Exception {
    return new JSONObject().put("financialAccountId", ACCOUNT_ID)
        .put("statementLineId", lineId).put("counterpartLineId", counterpartId);
  }

  private static JSONObject restoreBody(String lineId) throws Exception {
    return new JSONObject().put("financialAccountId", ACCOUNT_ID).put("lineId", lineId);
  }

  private static JSONObject data(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  private static List<String> strings(JSONArray array) throws Exception {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      out.add(array.getString(i));
    }
    out.sort(null);
    return out;
  }

  private static void assertCoded(NeoResponse response, int status, String code)
      throws Exception {
    assertEquals(status, response.getHttpStatus());
    assertEquals(code, response.getBody().getString("code"));
    assertEquals(code, response.getBody().getJSONObject("error").getString("code"));
  }

  /** No column of either line was written and nothing was saved. */
  private void assertNothingWritten(Line... lines) {
    for (Line line : lines) {
      verify(line.mock, never()).set(anyString(), any());
      verify(dal, never()).save(line.mock);
    }
  }
}
