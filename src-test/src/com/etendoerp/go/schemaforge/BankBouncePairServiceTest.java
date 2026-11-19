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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.hook.BankStatementLineFilterContext.Usage;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

import com.etendoerp.go.schemaforge.BankBounceDetector.Candidate;
import com.etendoerp.go.schemaforge.BankBounceDetector.Pair;
import com.etendoerp.go.schemaforge.BankBounceDetector.StatementLine;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvent;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;
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
 * @covers com.etendoerp.go.schemaforge.BankBounceKpiTelemetry
 * @covers com.etendoerp.go.schemaforge.ReconciliationHandlerSupport
 * @covers com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService
 */
@DisplayName("Bounce pairs — discardPair / restorePair")
class BankBouncePairServiceTest {

  private static final String ACCOUNT_ID = "ACC-1";
  private static final String OTHER_ACCOUNT_ID = "ACC-2";
  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final int DATE_TOL_DAYS = 3;
  private static final String CLIENT_ID = "CLIENT-1";
  private static final long WAIT_MS = 2000;

  /** Column name (upper case) → DAL property name: what the running model maps. */
  private final Map<String, String> mappedColumns = new HashMap<>();

  private MockedStatic<ModelProvider> modelStatic;
  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<BankBounceSuggestionsFlag> flag;
  private MockedStatic<StatementLineFilter> filter;

  private OBDal dal;
  private Session session;
  private ReconciliationHandler handler;
  private FIN_FinancialAccount account;
  private FIN_FinancialAccount otherAccount;
  private final List<FIN_BankStatementLine> pending = new ArrayList<>();
  private final List<Object[]> pisRows = new ArrayList<>();
  private final List<Object[]> openMovementRows = new ArrayList<>();
  private final List<String> scheduleDetailPaymentIds = new ArrayList<>();
  private final Set<FIN_BankStatementLine> saved = new HashSet<>();
  private NeoTelemetryService telemetry;

  /**
   * Simulates the running model column by column, the DAL with the detection queries and a
   * session (for the line locks), the dictionary, the flag (on) and the line filter (no hook),
   * and a handler mock that resolves the account, its tolerances and its pending lines.
   */
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
    session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
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
    when(handler.loadPendingLines(ACCOUNT_ID, true)).thenReturn(pending);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(account.getClient()).thenReturn(client);
    // The bounce-pair KPI events of every test go to this mock, never to a runtime sink.
    telemetry = mock(NeoTelemetryService.class);
    BankBounceKpiTelemetry.setTelemetryService(telemetry);
  }

  /** Closes every static mock and restores the runtime telemetry service. */
  @AfterEach
  void tearDown() {
    BankBounceKpiTelemetry.setTelemetryService(null);
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

    /** Both lines are discarded, the PSD2 button follows the flag and each links the other. */
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

    /** A PSD2 payment link is stored as pis_link/high, whichever line the request names first. */
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

    /** A pair with no signal is accepted with a null signal and review confidence. */
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

    /** The bounce may come exactly 60 days after the original. */
    @Test
    @DisplayName("the bounce may come exactly 60 days after the original")
    void sixtyDaysIsInsideTheWindow() throws Exception {
      Line original = pendingLine("L-OUT", "-30.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "30.00", DAY.plusDays(BankBounceDetector.WINDOW_DAYS),
          "ACME", "Y");

      assertEquals(200, BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id)).getHttpStatus());
    }
    /** An adjacent pair stores the signal and confidence of the rules, never high for adjacency. */
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

    /** An adjacent ambiguous pair is discarded on the neighbour the user chose, for review. */
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

    /** Flag off → 403 BOUNCE_SUGGESTIONS_OFF before anything is read. */
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

    /** LINE_DISCARDED: a line already discarded wins over every later check. */
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

    /** DISCARD_NOT_ALLOWED: a discarded line that is reconciled counts as reconciled. */
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


    /** DISCARD_NOT_ALLOWED: a matched PSD2-discarded line, through the real hook condition. */
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

    /** LINE_DISCARDED: a line another module's hook excludes counts as discarded. */
    @Test
    @DisplayName("LINE_DISCARDED: a line another module's hook excludes counts as discarded")
    void hookExcludedLineIsDiscarded() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      filter.when(
          () -> StatementLineFilter.isExcluded(true, bounce.mock, Usage.AUTO_MATCH_CANDIDATES))
          .thenReturn(true);

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, bounce.id)), 409,
          StatementLineDiscard.CODE_LINE_DISCARDED);
      assertNothingWritten(original, bounce);
    }

    /** DISCARD_NOT_ALLOWED: a reconciled line, before LINE_NOT_PENDING and the shape. */
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

    /** DISCARD_NOT_ALLOWED: a line the PSD2 synchronization did not import. */
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

    /** DISCARD_NOT_ALLOWED: the PSD2 discard columns are not in the running model. */
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

    /** LINE_NOT_PENDING: an inactive line, before the shape checks. */
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

    /** LINE_NOT_PENDING: a line on a draft statement. */
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

    /** LINE_NOT_PENDING: a line inside a split (match) group. */
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

    /** LINE_NOT_PENDING: both checks pass but the line is not an auto-match candidate. */
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

    /** SIGN_MISMATCH: same sign, before the amount check. */
    @Test
    @DisplayName("SIGN_MISMATCH: same sign, before the amount check")
    void sameSignWinsOverAmount() throws Exception {
      Line a = pendingLine("L-A", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-B", "-49.00", DAY.plusDays(1), "ACME", "Y");

      assertCoded(BankBouncePairService.discardPair(handler, body(a.id, b.id)), 409,
          BankBounceErrors.CODE_SIGN_MISMATCH);
      assertNothingWritten(a, b);
    }

    /** SIGN_MISMATCH: a zero-amount line has no sign. */
    @Test
    @DisplayName("SIGN_MISMATCH: a zero-amount line has no sign")
    void zeroAmountIsASignMismatch() throws Exception {
      Line a = pendingLine("L-A", "0.00", DAY, "ACME", "X");
      Line b = pendingLine("L-B", "0.00", DAY.plusDays(1), "ACME", "Y");

      assertCoded(BankBouncePairService.discardPair(handler, body(a.id, b.id)), 409,
          BankBounceErrors.CODE_SIGN_MISMATCH);
    }

    /** AMOUNT_MISMATCH: opposite sign, different amount, before the payment guard. */
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

    /** DISCARD_NOT_ALLOWED: the bounce 61 days after the original, before the payment guard. */
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

    /** HAS_PAYMENT: the original is the bank movement of a real ERP payment. */
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

    /** 400/404 before the coded checks: same id, missing id, unknown line, foreign line. */
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

    /** Flag off → 403 BOUNCE_SUGGESTIONS_OFF before anything is read, nothing restored. */
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

    /** Restores both lines and clears the four link columns on both. */
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

    /** A discarded line with no link (discarded in Classic) is restored alone. */
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

    /** A line neither discarded nor linked is a 200 no-op that restores nothing. */
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

    /** A counterpart of another account is neither restored nor reported. */
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

    /** The bounce link columns not mapped yet: the line is still restored. */
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

    /** Refusals: no lineId 400, unknown line 404, PSD2 not installed 409 — nothing written. */
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
  // Concurrency, one read per action, stored confidence
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("concurrency: lines locked FOR UPDATE in id order, a pair only when mutually linked")
  class Concurrency {

    /**
     * {@code discardPair}: once both lines are loaded and known to be the account's, they are
     * locked (re-read {@code FOR UPDATE}) in id order — {@code L-IN} before {@code L-OUT}, whatever
     * order the request names them in — and only then is the pair validated (the detection read
     * is the first validation step that touches the database).
     */
    @Test
    @DisplayName("discardPair locks both lines in id order before validating them")
    void discardLocksBothLinesInIdOrderBeforeValidating() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");

      assertEquals(200, BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id)).getHttpStatus());

      InOrder order = inOrder(handler, session);
      order.verify(handler).loadLine("L-OUT");
      order.verify(handler).loadLine("L-IN");
      order.verify(session).refresh(eq(bounce.mock), writeLockOptions());
      order.verify(session).refresh(eq(original.mock), writeLockOptions());
      order.verify(handler).loadTolerances(ACCOUNT_ID);
    }

    /**
     * {@code restorePair}: the line and the counterpart its link names are locked in id order
     * before anything is written.
     */
    @Test
    @DisplayName("restorePair locks the pair in id order before writing")
    void restoreLocksThePairInIdOrderBeforeWriting() throws Exception {
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      discardedPair(a, b);

      assertEquals(200, BankBouncePairService.restorePair(handler, restoreBody(a.id))
          .getHttpStatus());

      InOrder order = inOrder(session, dal);
      order.verify(session).refresh(eq(b.mock), writeLockOptions());
      order.verify(session).refresh(eq(a.mock), writeLockOptions());
      order.verify(dal, Mockito.atLeastOnce()).save(any());
    }

    /**
     * A one-sided link is not a pair: {@code L1} names {@code L2}, but {@code L2} now points at
     * {@code L3} (it was restored and discarded again with another line). Restoring {@code L1}
     * restores it alone; {@code L2} keeps its discard and its own link.
     */
    @Test
    @DisplayName("restorePair restores a line alone when its counterpart does not point back")
    void restoreOfAOneSidedLinkRestoresTheLineAlone() throws Exception {
      Line l1 = pendingLine("L1", "-50.00", DAY, "ACME", "X");
      Line l2 = pendingLine("L2", "50.00", DAY.plusDays(1), "ACME", "Y");
      Line l3 = pendingLine("L3", "-50.00", DAY, "ACME", "Z");
      discardedPair(l2, l3);
      l1.values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      l1.values.put(prop(StatementLineDiscard.COL_DISCARD_LINE), "Y");
      l1.values.put(prop(BankBouncePairService.COL_PAIR_LINE), l2.mock);

      NeoResponse response = BankBouncePairService.restorePair(handler, restoreBody(l1.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals(List.of("L1"), strings(data(response).getJSONArray("restored")));
      assertSame(JSONObject.NULL, data(response).get("counterpartLineId"));
      assertEquals(Boolean.FALSE, l1.value(StatementLineDiscard.COL_IS_DISCARDED));
      assertNull(l1.value(BankBouncePairService.COL_PAIR_LINE));
      assertEquals(Boolean.TRUE, l2.value(StatementLineDiscard.COL_IS_DISCARDED),
          "the other line is not touched");
      assertSame(l3.mock, l2.value(BankBouncePairService.COL_PAIR_LINE));
      verify(dal, never()).save(l2.mock);
    }
  }

  @Nested
  @DisplayName("one read per action: the flag once, the detection once")
  class OneReadPerAction {

    /** {@code discardPair} reads the flag once for the whole action. */
    @Test
    @DisplayName("discardPair reads the flag once")
    void discardReadsTheFlagOnce() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");

      assertEquals(200, BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id)).getHttpStatus());

      flag.verify(BankBounceSuggestionsFlag::isEnabled, Mockito.times(1));
    }

    /** {@code restorePair} reads the flag once for the whole action. */
    @Test
    @DisplayName("restorePair reads the flag once")
    void restoreReadsTheFlagOnce() throws Exception {
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      discardedPair(a, b);

      assertEquals(200, BankBouncePairService.restorePair(handler, restoreBody(a.id))
          .getHttpStatus());

      flag.verify(BankBounceSuggestionsFlag::isEnabled, Mockito.times(1));
    }

    /**
     * Two bounce groups of one {@code applySuggestions} call share one detection: the tolerances
     * and the auto-match candidates are read once, and the flag is not read here at all (the call
     * hands it in).
     */
    @Test
    @DisplayName("applyPairs loads and detects once for all the pairs of the call")
    void applyPairsReadsTheDetectionOnce() throws Exception {
      Line o1 = pendingLine("L-O1", "-50.00", DAY, "ACME", "X");
      Line b1 = pendingLine("L-B1", "50.00", DAY.plusDays(1), "ACME", "Y");
      Line o2 = pendingLine("L-O2", "-70.00", DAY, "BETA", "X");
      Line b2 = pendingLine("L-B2", "70.00", DAY.plusDays(1), "BETA", "Y");
      JSONArray groups = new JSONArray().put(pairGroup(o1.id, b1.id)).put(pairGroup(o2.id, b2.id));
      JSONArray results = new JSONArray();

      int submitted = BankBouncePairService.applyPairs(handler, account, groups, new HashSet<>(),
          results, true);

      assertEquals(2, submitted);
      for (int i = 0; i < results.length(); i++) {
        assertTrue(results.getJSONObject(i).optBoolean("discarded"), results.toString());
      }
      verify(handler, Mockito.times(1)).loadTolerances(ACCOUNT_ID);
      verify(handler, Mockito.times(1)).loadPendingLines(ACCOUNT_ID, true);
      flag.verifyNoInteractions();
    }
  }

  @Nested
  @DisplayName("stored confidence: what the system suggested, not the user's final choice")
  class StoredConfidence {

    /** A pair detection proposed as high, discarded exactly as proposed, is stored {@code high}. */
    @Test
    @DisplayName("the exact pair detection proposed as high is stored high")
    void proposedHighPairIsStoredHigh() throws Exception {
      Line original = pendingLine("L-OUT", "-50.00", DAY, "", "TRANSFERENCIA");
      Line bounce = pendingLine("L-IN", "50.00", DAY, "", "DEVOLUCION TRANSFERENCIA");

      NeoResponse response = BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, data(response).getString("confidence"));
      assertEquals(BankBounceDetector.CONFIDENCE_HIGH,
          original.value(BankBouncePairService.COL_CONFIDENCE));
      assertEquals(BankBounceDetector.SIGNAL_RETURN_TEXT,
          original.value(BankBouncePairService.COL_SIGNAL));
    }

    /**
     * An ambiguous pair resolved in the panel by choosing one of its candidates is stored
     * {@code review}, although its signals (bounce wording on the same day) would be high on their
     * own; the stored signal is still the candidate's.
     */
    @Test
    @DisplayName("an ambiguous pair resolved by choosing a candidate is stored review")
    void ambiguousPairResolvedInThePanelIsStoredReview() throws Exception {
      Line o1 = pendingLine("L-O1", "-50.00", DAY, "", "TRANSFERENCIA");
      Line o2 = pendingLine("L-O2", "-50.00", DAY, "", "TRANSFERENCIA");
      Line bounce = pendingLine("L-IN", "50.00", DAY, "", "DEVOLUCION TRANSFERENCIA");

      NeoResponse response = BankBouncePairService.discardPair(handler, body(o2.id, bounce.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, data(response).getString("confidence"));
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW,
          bounce.value(BankBouncePairService.COL_CONFIDENCE));
      assertEquals(BankBounceDetector.SIGNAL_RETURN_TEXT,
          bounce.value(BankBouncePairService.COL_SIGNAL));
      assertNull(o1.value(StatementLineDiscard.COL_IS_DISCARDED),
          "the other candidate is untouched");
    }

    /**
     * A pair detection did not propose — the return is the statement neighbour of another outgoing
     * line, which detection paired it with — is stored {@code review}, although bounce wording on
     * the same day would make it high in isolation.
     */
    @Test
    @DisplayName("a pair detection did not propose is stored review, whatever its signals")
    void unproposedPairIsStoredReview() throws Exception {
      Line elsewhere = pendingLine("L-O2", "-50.00", DAY, "", "TRANSFERENCIA");
      Line neighbour = pendingLine("L-O1", "-50.00", DAY, "", "TRANSFERENCIA");
      Line bounce = pendingLine("L-IN", "50.00", DAY, "", "DEVOLUCION TRANSFERENCIA");
      onStatement("ST-1", neighbour, bounce);
      onStatement("ST-2", elsewhere);

      NeoResponse response = BankBouncePairService.discardPair(handler,
          body(elsewhere.id, bounce.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, data(response).getString("confidence"));
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW,
          elsewhere.value(BankBouncePairService.COL_CONFIDENCE));
      assertEquals(BankBounceDetector.SIGNAL_RETURN_TEXT,
          elsewhere.value(BankBouncePairService.COL_SIGNAL));
    }
  }

  // ---------------------------------------------------------------------------
  // Bounce-pair KPI telemetry (ETP-5581, issue #15)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("bounce-pair KPI events — what was proposed and what the user decided, no ids")
  class KpiEvents {

    /** A pair proposed as high and discarded in the panel is a preselected discarded decision. */
    @Test
    @DisplayName("discardPair of a proposed high pair emits discarded, preselected, surface panel")
    void panelDiscardOfAHighPair() throws Exception {
      Line original = pendingLine("L-OUT", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line bounce = pendingLine("L-IN", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });

      assertEquals(200, BankBouncePairService.discardPair(handler,
          body(bounce.id, original.id)).getHttpStatus());

      Map<String, Object> props = decided(1).get(0);
      assertEquals(BankBounceKpiTelemetry.DECISION_DISCARDED, props.get("decision"));
      assertEquals(BankBounceKpiTelemetry.SURFACE_PANEL, props.get("surface"));
      assertEquals(BankBounceDetector.SIGNAL_PIS_LINK, props.get("detection_signal"));
      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, props.get("confidence"));
      assertEquals(Boolean.TRUE, props.get("preselected"));
      assertEquals(Boolean.FALSE, props.get("ambiguous"));
      assertEquals(Boolean.FALSE, props.get("chosen_among_alternatives"));
      assertEquals("ui", props.get("channel"));
      assertEquals(Boolean.TRUE, props.get("flag_enabled"));
      assertEquals(CLIENT_ID, props.get("account_id"));
      assertTrue((Integer) props.get("signals_count") >= 1);
      assertNoRecordData(props);
    }

    /** Choosing one candidate of an ambiguous pair is a review decision among alternatives. */
    @Test
    @DisplayName("discarding a chosen candidate of an ambiguous pair is chosen_among_alternatives")
    void panelDiscardOfAChosenCandidate() throws Exception {
      pendingLine("L-O1", "-50.00", DAY, "", "TRANSFERENCIA");
      Line o2 = pendingLine("L-O2", "-50.00", DAY, "", "TRANSFERENCIA");
      Line bounce = pendingLine("L-IN", "50.00", DAY, "", "DEVOLUCION TRANSFERENCIA");

      assertEquals(200, BankBouncePairService.discardPair(handler,
          body(o2.id, bounce.id)).getHttpStatus());

      Map<String, Object> props = decided(1).get(0);
      assertEquals(Boolean.TRUE, props.get("ambiguous"));
      assertEquals(Boolean.TRUE, props.get("chosen_among_alternatives"));
      assertEquals(Boolean.FALSE, props.get("preselected"));
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, props.get("confidence"));
      assertEquals(BankBounceDetector.SIGNAL_RETURN_TEXT, props.get("detection_signal"));
    }

    /** A 409 on a proposed pair is a failed decision; a 400 emits nothing. */
    @Test
    @DisplayName("a 409 on a proposed pair emits failed; a malformed request emits nothing")
    void panelRefusals() throws Exception {
      Line original = pendingLine("L-OUT", "-30.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "30.00", DAY.plusDays(2), "ACME", "Y");
      when(bounce.mock.isActive()).thenReturn(false);

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, bounce.id)), 409,
          BankBounceErrors.CODE_LINE_NOT_PENDING);
      Map<String, Object> props = decided(1).get(0);
      assertEquals(BankBounceKpiTelemetry.DECISION_FAILED, props.get("decision"));
      assertEquals(BankBounceDetector.SIGNAL_COUNTERPARTY, props.get("detection_signal"));

      Mockito.clearInvocations(telemetry);
      assertEquals(400, BankBouncePairService.discardPair(handler,
          body(original.id, original.id)).getHttpStatus());
      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
    }

    /** "No es un rebote" writes nothing and emits unchecked; flag off and unknown pairs emit none. */
    @Test
    @DisplayName("dismissPair writes nothing, emits unchecked from any role, 403 with the flag off")
    void dismissPair() throws Exception {
      pendingLine("L-O1", "-50.00", DAY, "", "TRANSFERENCIA");
      Line o2 = pendingLine("L-O2", "-50.00", DAY, "", "TRANSFERENCIA");
      Line bounce = pendingLine("L-IN", "50.00", DAY, "", "DEVOLUCION TRANSFERENCIA");
      Line stranger = pendingLine("L-X", "-7.00", DAY, "ZETA", "OTHER");

      // The bounce line alone: the ambiguous pair, no candidate chosen.
      NeoResponse response = BankBouncePairService.dismissPair(handler,
          new JSONObject().put("financialAccountId", ACCOUNT_ID).put("statementLineId", bounce.id));
      assertEquals(200, response.getHttpStatus());
      assertTrue(data(response).getBoolean("ok"));
      Map<String, Object> props = decided(1).get(0);
      assertEquals(BankBounceKpiTelemetry.DECISION_UNCHECKED, props.get("decision"));
      assertEquals(BankBounceKpiTelemetry.SURFACE_PANEL, props.get("surface"));
      assertEquals(Boolean.TRUE, props.get("ambiguous"));
      assertEquals(Boolean.FALSE, props.get("chosen_among_alternatives"));
      assertNothingWritten(o2, bounce);

      // A candidate selected, the bounce as counterpart: the candidate was chosen.
      Mockito.clearInvocations(telemetry);
      BankBouncePairService.dismissPair(handler, body(o2.id, bounce.id));
      assertEquals(Boolean.TRUE, decided(1).get(0).get("chosen_among_alternatives"));

      // A line detection pairs with nothing: 200, no event.
      Mockito.clearInvocations(telemetry);
      assertEquals(200, BankBouncePairService.dismissPair(handler,
          new JSONObject().put("financialAccountId", ACCOUNT_ID)
              .put("statementLineId", stranger.id)).getHttpStatus());
      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());

      assertEquals(400, BankBouncePairService.dismissPair(handler,
          new JSONObject().put("financialAccountId", ACCOUNT_ID)).getHttpStatus());
      flag.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(false);
      assertCoded(BankBouncePairService.dismissPair(handler, body(o2.id, bounce.id)), 403,
          BankBounceErrors.CODE_SUGGESTIONS_OFF);
      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
    }

    /** Restoring a linked pair emits the stored signal, confidence and whole days; a lone line none. */
    @Test
    @DisplayName("restorePair of a linked pair emits restored with days_since_decided; alone, nothing")
    void restoreEvents() throws Exception {
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      discardedPair(a, b);
      Date threeDaysAgo = Date.from(java.time.Instant.now().minus(java.time.Duration.ofDays(3)));
      a.values.put(prop(BankBouncePairService.COL_DISCARDED_AT), threeDaysAgo);

      BankBouncePairService.restorePair(handler, restoreBody(a.id));

      ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
      verify(telemetry, timeout(WAIT_MS)).emit(
          eq(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_RESTORED), captor.capture(),
          eq(CLIENT_ID));
      Map<String, Object> props = captor.getValue();
      assertEquals("counterparty", props.get("detection_signal"));
      assertEquals("review", props.get("confidence"));
      assertEquals(3L, props.get("days_since_decided"));
      assertEquals("ui", props.get("channel"));
      assertNoRecordData(props);

      Mockito.clearInvocations(telemetry);
      Line lone = pendingLine("L-LONE", "-9.00", DAY, "ACME", "X");
      lone.values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      BankBouncePairService.restorePair(handler, restoreBody(lone.id));
      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
    }

    /**
     * The modal: an applied high pair, a review pair reported unchecked, an ambiguous pair sent
     * to the panel; an unknown key, an unknown decision and a repeat of the applied pair are
     * ignored. One decision per pair and the summary, preselection unchanged.
     */
    @Test
    @DisplayName("applySuggestions: applied + bounceDecisions give one decision per pair and a summary")
    void modalDecisions() throws Exception {
      Line ao = pendingLine("L-AO", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line ab = pendingLine("L-AB", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });
      pendingLine("L-BO", "-30.00", DAY, "ACME", "X");
      pendingLine("L-BB", "30.00", DAY.plusDays(2), "ACME", "Y");
      pendingLine("L-C1", "-50.00", DAY, "", "TRANSFERENCIA");
      pendingLine("L-C2", "-50.00", DAY, "", "TRANSFERENCIA");
      pendingLine("L-CB", "50.00", DAY, "", "DEVOLUCION TRANSFERENCIA");
      JSONObject request = new JSONObject().put("bounceDecisions", new JSONArray()
          .put(decision("bounce-L-BO-L-BB", "unchecked"))
          .put(decision("bounce-L-CB", "sent_to_panel"))
          .put(decision("bounce-UNKNOWN", "unchecked"))
          .put(decision("bounce-L-BO-L-BB", "maybe"))
          .put(decision("bounce-L-AO-L-AB", "unchecked")));

      Map<String, Object> summary = applyWithDecisions(request, pairGroup(ao.id, ab.id), 3);

      List<Map<String, Object>> decisions = lastDecided;
      assertEquals(List.of("discarded", "unchecked", "sent_to_panel"),
          List.of(decisions.get(0).get("decision"), decisions.get(1).get("decision"),
              decisions.get(2).get("decision")));
      assertEquals(Boolean.TRUE, decisions.get(0).get("preselected"));
      assertEquals(Boolean.FALSE, decisions.get(1).get("preselected"));
      assertEquals(Boolean.TRUE, decisions.get(2).get("ambiguous"));
      for (Map<String, Object> props : decisions) {
        assertEquals(BankBounceKpiTelemetry.SURFACE_MODAL, props.get("surface"));
        assertNoRecordData(props);
      }
      assertEquals(3, summary.get("pairs_shown"));
      assertEquals(1, summary.get("pairs_discarded"));
      assertEquals(1, summary.get("pairs_unchecked"));
      assertEquals(1, summary.get("pairs_sent_to_panel"));
      assertEquals(Boolean.FALSE, summary.get("preselection_changed"));
    }

    /** Unchecking a preselected pair changes the preselection; the same pair twice fails once. */
    @Test
    @DisplayName("applySuggestions: an unchecked high pair changes the preselection; a repeat fails")
    void modalPreselectionChangedAndFailure() throws Exception {
      Line ao = pendingLine("L-AO", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line ab = pendingLine("L-AB", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });
      Line bo = pendingLine("L-BO", "-30.00", DAY, "ACME", "X");
      Line bb = pendingLine("L-BB", "30.00", DAY.plusDays(2), "ACME", "Y");
      JSONObject request = new JSONObject().put("bounceDecisions", new JSONArray()
          .put(decision("bounce-L-AO-L-AB", "unchecked")));

      Map<String, Object> summary = applyWithDecisions(request,
          new JSONArray().put(pairGroup(bo.id, bb.id)).put(pairGroup(bb.id, bo.id)), 3);

      assertEquals(List.of("discarded", "failed", "unchecked"),
          List.of(lastDecided.get(0).get("decision"), lastDecided.get(1).get("decision"),
              lastDecided.get(2).get("decision")));
      assertEquals(Boolean.TRUE, summary.get("preselection_changed"));
      assertEquals(3, summary.get("pairs_shown"));
      assertEquals(1, summary.get("pairs_discarded"));
      assertNull(ao.value(StatementLineDiscard.COL_IS_DISCARDED));
      assertNull(ab.value(StatementLineDiscard.COL_IS_DISCARDED));
    }

    /** With the flag off the modal records and sends nothing. */
    @Test
    @DisplayName("applySuggestions with the flag off sends no bounce event")
    void modalFlagOff() throws Exception {
      Line bo = pendingLine("L-BO", "-30.00", DAY, "ACME", "X");
      Line bb = pendingLine("L-BB", "30.00", DAY.plusDays(2), "ACME", "Y");
      BankBounceKpiTelemetry.ModalDecisions kpi = BankBounceKpiTelemetry.ModalDecisions.of(null,
          account, new JSONObject().put("bounceDecisions", new JSONArray()
              .put(decision("bounce-L-BO-L-BB", "unchecked"))));

      BankBouncePairService.applyPairs(handler, account,
          new JSONArray().put(pairGroup(bo.id, bb.id)), new HashSet<>(), new JSONArray(), null,
          kpi);
      kpi.prime();
      kpi.finish(new HashSet<>());

      assertSame(BankBounceKpiTelemetry.ModalDecisions.NONE, kpi);
      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
    }

    /** An MCP tool call is reported as channel mcp. */
    @Test
    @DisplayName("a discard through the MCP is channel mcp")
    void mcpChannel() throws Exception {
      Line original = pendingLine("L-OUT", "-30.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "30.00", DAY.plusDays(2), "ACME", "Y");
      String previous = NeoWriteChannel.enter(NeoWriteChannel.MCP);
      try {
        BankBouncePairService.discardPair(handler, body(original.id, bounce.id));
      } finally {
        NeoWriteChannel.restore(previous);
      }
      assertEquals("mcp", decided(1).get(0).get("channel"));
    }

    /**
     * An agent that applied a bounce-pair group left out the other preselected pairs: each is an
     * {@code unchecked} decision (surface modal, channel mcp) and counts in the summary; a review
     * pair and a preselected pair whose line a transaction group took are not.
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("MCP applySuggestions with a bounce group: left-out preselected pairs are unchecked")
    void agentLeftOutPreselectedPairsAreUnchecked() throws Exception {
      Line ao = pendingLine("L-AO", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line ab = pendingLine("L-AB", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });
      pendingLine("L-DO", "-40.00", DAY, "", "TRANSFERENCIA");
      pendingLine("L-DB", "40.00", DAY, "", "DEVOLUCION TRANSFERENCIA");
      Line eo = pendingLine("L-EO", "-60.00", DAY, "", "TRANSFERENCIA");
      pendingLine("L-EB", "60.00", DAY, "", "DEVOLUCION TRANSFERENCIA");
      pendingLine("L-BO", "-30.00", DAY, "ACME", "X");
      pendingLine("L-BB", "30.00", DAY.plusDays(2), "ACME", "Y");

      Map<String, Object> summary = agentModal(new JSONArray().put(pairGroup(ao.id, ab.id)),
          eo.id, 2);

      assertEquals(List.of("discarded", "unchecked"),
          List.of(lastDecided.get(0).get("decision"), lastDecided.get(1).get("decision")));
      Map<String, Object> unchecked = lastDecided.get(1);
      assertEquals(Boolean.TRUE, unchecked.get("preselected"));
      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, unchecked.get("confidence"));
      assertEquals(BankBounceKpiTelemetry.SURFACE_MODAL, unchecked.get("surface"));
      assertEquals("mcp", unchecked.get("channel"));
      assertNoRecordData(unchecked);
      assertEquals(2, summary.get("pairs_shown"));
      assertEquals(1, summary.get("pairs_discarded"));
      assertEquals(1, summary.get("pairs_unchecked"));
      assertEquals(Boolean.TRUE, summary.get("preselection_changed"));
    }

    /**
     * An agent that applied no bounce-pair group did not look at the bounces: nothing is sent,
     * although preselected pairs exist.
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("MCP applySuggestions with no bounce group sends no bounce event")
    void agentWithoutBounceGroupsSendsNothing() throws Exception {
      pendingLine("L-DO", "-40.00", DAY, "", "TRANSFERENCIA");
      pendingLine("L-DB", "40.00", DAY, "", "DEVOLUCION TRANSFERENCIA");
      BankBouncePairService.Detection detection =
          new BankBouncePairService.Detection(handler, account, true);
      String previous = NeoWriteChannel.enter(NeoWriteChannel.MCP);
      try {
        BankBounceKpiTelemetry.ModalDecisions kpi =
            BankBounceKpiTelemetry.ModalDecisions.of(detection, account, new JSONObject());
        Set<String> taken = new HashSet<>();
        BankBouncePairService.applyPairs(handler, account, new JSONArray(), taken,
            new JSONArray(), detection, kpi);
        kpi.prime();
        kpi.finish(taken);
      } finally {
        NeoWriteChannel.restore(previous);
      }
      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
      verify(handler, never()).loadPendingLines(anyString(), Mockito.anyBoolean());
    }

    /**
     * The SPA path is unchanged: a preselected pair it neither applied nor reported sends nothing
     * (the SPA reports what it showed in {@code bounceDecisions}).
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("UI applySuggestions does not infer unchecked pairs")
    void uiDoesNotInferUncheckedPairs() throws Exception {
      Line ao = pendingLine("L-AO", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line ab = pendingLine("L-AB", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });
      pendingLine("L-DO", "-40.00", DAY, "", "TRANSFERENCIA");
      pendingLine("L-DB", "40.00", DAY, "", "DEVOLUCION TRANSFERENCIA");

      Map<String, Object> summary = applyWithDecisions(new JSONObject(),
          pairGroup(ao.id, ab.id), 1);

      assertEquals("discarded", lastDecided.get(0).get("decision"));
      assertEquals(1, summary.get("pairs_shown"));
      verify(telemetry, after(300).times(1)).emit(
          eq(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED), anyMap(), any());
    }

    /** A restored pair is {@code preselected} exactly when its stored confidence is high. */
    @Test
    @DisplayName("restored carries preselected = stored confidence is high")
    void restoredCarriesPreselected() throws Exception {
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      discardedPair(a, b);
      a.values.put(prop(BankBouncePairService.COL_CONFIDENCE), "high");
      Line c = pendingLine("L-OUT2", "-60.00", DAY, "BETA", "X");
      Line d = pendingLine("L-IN2", "60.00", DAY.plusDays(1), "BETA", "Y");
      discardedPair(c, d);

      BankBouncePairService.restorePair(handler, restoreBody(a.id));
      BankBouncePairService.restorePair(handler, restoreBody(c.id));

      ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
      verify(telemetry, timeout(WAIT_MS).times(2)).emit(
          eq(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_RESTORED), captor.capture(),
          eq(CLIENT_ID));
      assertEquals(Boolean.TRUE, captor.getAllValues().get(0).get("preselected"));
      assertEquals(Boolean.FALSE, captor.getAllValues().get(1).get("preselected"));
    }

    /** {@code days_since_decided} counts UTC calendar days, whatever the time of day. */
    @Test
    @DisplayName("days_since_decided is UTC calendar days around midnight")
    void daysSinceDecidedIsUtcCalendarDays() {
      Instant justAfterMidnight = Instant.parse("2026-11-03T00:01:00Z");
      assertEquals(1L, BankBounceKpiTelemetry.daysBetween(
          Date.from(Instant.parse("2026-11-02T23:59:00Z")), justAfterMidnight));
      assertEquals(0L, BankBounceKpiTelemetry.daysBetween(
          Date.from(Instant.parse("2026-11-03T00:00:00Z")), Instant.parse("2026-11-03T23:59:59Z")));
      assertEquals(2L, BankBounceKpiTelemetry.daysBetween(
          Date.from(Instant.parse("2026-11-01T23:59:59Z")), justAfterMidnight));
      assertEquals(0L, BankBounceKpiTelemetry.daysBetween(
          Date.from(Instant.parse("2026-11-04T10:00:00Z")), justAfterMidnight),
          "a discard time in the future never gives a negative age");
      assertNull(BankBounceKpiTelemetry.daysBetween(null, justAfterMidnight));
    }

    /**
     * A 409 in the panel on a pair detection does not see is {@code failed} without the proposal
     * properties — the modal's rule.
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("a panel 409 on a pair detection does not see emits failed without the proposal")
    void panelFailureOnAnUnseenPair() throws Exception {
      Line a = pendingLine("L-A", "-30.00", DAY, "ACME", "X");
      Line b = pendingLine("L-B", "-30.00", DAY.plusDays(1), "ACME", "Y");

      assertCoded(BankBouncePairService.discardPair(handler, body(a.id, b.id)), 409,
          BankBounceErrors.CODE_SIGN_MISMATCH);

      Map<String, Object> props = decided(1).get(0);
      assertEquals(BankBounceKpiTelemetry.DECISION_FAILED, props.get("decision"));
      assertEquals(BankBounceKpiTelemetry.SURFACE_PANEL, props.get("surface"));
      assertFalse(props.containsKey("confidence"), props.toString());
      assertFalse(props.containsKey("preselected"), props.toString());
    }
  }

  // ---------------------------------------------------------------------------
  // Bounce-pair KPI: restore, flag off, pair resolution
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("bounce-pair KPI — restore edges, the flag off, which detected pair a decision is")
  class KpiEdges {

    /**
     * A one-sided link is not a pair: restoring {@code L1}, whose counterpart now points at another
     * line, restores it alone and sends no restored event.
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("restorePair of a one-sided link restores the line and sends nothing")
    void restoreOfAOneSidedLinkSendsNothing() throws Exception {
      Line l1 = pendingLine("L1", "-50.00", DAY, "ACME", "X");
      Line l2 = pendingLine("L2", "50.00", DAY.plusDays(1), "ACME", "Y");
      Line l3 = pendingLine("L3", "-50.00", DAY, "ACME", "Z");
      discardedPair(l2, l3);
      l1.values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      l1.values.put(prop(StatementLineDiscard.COL_DISCARD_LINE), "Y");
      l1.values.put(prop(BankBouncePairService.COL_PAIR_LINE), l2.mock);
      l1.values.put(prop(BankBouncePairService.COL_DISCARDED_AT), new Date());

      NeoResponse response = BankBouncePairService.restorePair(handler, restoreBody(l1.id));

      assertEquals(200, response.getHttpStatus());
      assertEquals(List.of("L1"), strings(data(response).getJSONArray("restored")));
      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
    }

    /**
     * {@code days_since_decided} is left out of the submitted event when
     * {@code EM_ETGO_Bounce_Discarded} is null (the sanitizer drops the null), and is a whole
     * number of days, as a {@code Long}, when it is set.
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("restored: days_since_decided absent without a discard time, whole days with one")
    void restoredDaysAreOmittedWithoutADiscardTime() throws Exception {
      NeoTelemetryService service = realTelemetry();
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      discardedPair(a, b);
      a.values.remove(prop(BankBouncePairService.COL_DISCARDED_AT));

      assertEquals(200, BankBouncePairService.restorePair(handler, restoreBody(a.id))
          .getHttpStatus());

      NeoTelemetryEvent unknownAge = awaitSent(1).get(0);
      assertEquals(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_RESTORED, unknownAge.getName());
      assertFalse(unknownAge.getProperties().containsKey("days_since_decided"),
          unknownAge.getProperties().toString());
      assertEquals(RESTORED_KEYS_WITHOUT_DAYS, unknownAge.getProperties().keySet());
      assertTrue(rawProperties(service).get(0).containsKey("days_since_decided"),
          "the builder states the unknown age as null; the sanitizer leaves it out");

      Line c = pendingLine("L-OUT2", "-60.00", DAY, "BETA", "X");
      Line d = pendingLine("L-IN2", "60.00", DAY.plusDays(1), "BETA", "Y");
      discardedPair(c, d);
      c.values.put(prop(BankBouncePairService.COL_DISCARDED_AT),
          Date.from(Instant.now().minus(Duration.ofDays(2))));

      BankBouncePairService.restorePair(handler, restoreBody(c.id));

      NeoTelemetryEvent known = awaitSent(2).get(1);
      assertEquals(2L, known.getProperties().get("days_since_decided"));
      assertEquals(RESTORED_KEYS, known.getProperties().keySet());
    }

    /**
     * With {@code bank-bounce-suggestions} off every panel path answers its 403 and no KPI event
     * is sent from any of them.
     *
     * @throws Exception when a call fails
     */
    @Test
    @DisplayName("flag off: discardPair, restorePair and dismissPair send no KPI event")
    void flagOffSendsNothingFromAnyPanelPath() throws Exception {
      Line original = pendingLine("L-OUT", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line bounce = pendingLine("L-IN", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });
      Line a = pendingLine("L-A", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-B", "50.00", DAY.plusDays(1), "ACME", "Y");
      discardedPair(a, b);
      flag.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(false);

      assertCoded(BankBouncePairService.discardPair(handler, body(original.id, bounce.id)), 403,
          BankBounceErrors.CODE_SUGGESTIONS_OFF);
      assertCoded(BankBouncePairService.restorePair(handler, restoreBody(a.id)), 403,
          BankBounceErrors.CODE_SUGGESTIONS_OFF);
      assertCoded(BankBouncePairService.dismissPair(handler, body(original.id, bounce.id)), 403,
          BankBounceErrors.CODE_SUGGESTIONS_OFF);

      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
      assertNothingWritten(original, bounce, a, b);
    }

    /**
     * A candidate shared by two ambiguous pairs (two adjacent returns that may share a neighbour)
     * dismissed alone resolves to the FIRST detected pair that touches it: the event carries that
     * pair's proposal, not the second's.
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("dismissPair of a candidate shared by two ambiguous pairs is the first pair")
    void dismissOfASharedCandidateIsTheFirstPair() throws Exception {
      pendingLine("L-C1", "-30.00", DAY, "", "X");
      Line shared = pendingLine("L-C2", "-30.00", DAY, "", "X");
      pendingLine("L-C5", "-30.00", DAY, "", "X");
      pendingLine("L-B3", "30.00", DAY, "", "Y");
      pendingLine("L-B4", "30.00", DAY, "", "Y");
      StatementLine c1 = model("L-C1", -30);
      StatementLine c2 = model("L-C2", -30);
      StatementLine c5 = model("L-C5", -30);
      Pair first = new Pair(null, model("L-B3", 30),
          List.of(new Candidate(c1, List.of(BankBounceDetector.SIGNAL_COUNTERPARTY)),
              new Candidate(c2, List.of(BankBounceDetector.SIGNAL_COUNTERPARTY))),
          List.of(BankBounceDetector.SIGNAL_COUNTERPARTY), BankBounceDetector.CONFIDENCE_REVIEW,
          true);
      Pair second = new Pair(null, model("L-B4", 30),
          List.of(new Candidate(c2, List.of(BankBounceDetector.SIGNAL_RETURN_TEXT,
              BankBounceDetector.SIGNAL_COUNTERPARTY)), new Candidate(c5, List.of())),
          List.of(BankBounceDetector.SIGNAL_RETURN_TEXT, BankBounceDetector.SIGNAL_COUNTERPARTY),
          BankBounceDetector.CONFIDENCE_REVIEW, false);

      try (MockedStatic<BankBounceDetector> detector =
          mockStatic(BankBounceDetector.class, Mockito.CALLS_REAL_METHODS)) {
        detector.when(() -> BankBounceDetector.detect(any())).thenReturn(List.of(first, second));

        assertEquals(200, BankBouncePairService.dismissPair(handler,
            new JSONObject().put("financialAccountId", ACCOUNT_ID)
                .put("statementLineId", shared.id)).getHttpStatus());

        Map<String, Object> props = decided(1).get(0);
        assertEquals(BankBounceKpiTelemetry.DECISION_UNCHECKED, props.get("decision"));
        assertEquals(BankBounceDetector.SIGNAL_COUNTERPARTY, props.get("detection_signal"));
        assertEquals(1, props.get("signals_count"));
        assertEquals(Boolean.TRUE, props.get("adjacent"), "the first pair is the adjacent one");
        assertEquals(Boolean.TRUE, props.get("ambiguous"));
        assertEquals(Boolean.FALSE, props.get("chosen_among_alternatives"));
      }
    }

    /**
     * "No es un rebote" on the original of a proposed high pair, with no counterpart: the pair is
     * found from the original alone and the event is that pair's own proposal (preselected, not
     * ambiguous, nothing chosen).
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("dismissPair with only the original line is the pair it belongs to")
    void dismissWithOnlyTheOriginal() throws Exception {
      Line original = pendingLine("L-OUT", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line bounce = pendingLine("L-IN", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });

      NeoResponse response = BankBouncePairService.dismissPair(handler,
          new JSONObject().put("financialAccountId", ACCOUNT_ID)
              .put("statementLineId", original.id));

      assertEquals(200, response.getHttpStatus());
      Map<String, Object> props = decided(1).get(0);
      assertEquals(BankBounceKpiTelemetry.DECISION_UNCHECKED, props.get("decision"));
      assertEquals(BankBounceKpiTelemetry.SURFACE_PANEL, props.get("surface"));
      assertEquals(BankBounceDetector.SIGNAL_PIS_LINK, props.get("detection_signal"));
      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, props.get("confidence"));
      assertEquals(Boolean.TRUE, props.get("preselected"));
      assertEquals(Boolean.FALSE, props.get("ambiguous"));
      assertEquals(Boolean.FALSE, props.get("chosen_among_alternatives"));
      assertNothingWritten(original, bounce);
    }

    /**
     * Two lines of the account that detection does not pair: 200 {@code ok:true}, no event, and
     * nothing written.
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("dismissPair with two lines detection does not pair → 200, no event")
    void dismissOfTwoUnpairedLinesSendsNothing() throws Exception {
      Line original = pendingLine("L-OUT", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line bounce = pendingLine("L-IN", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });
      Line stranger = pendingLine("L-X", "-7.00", DAY, "ZETA", "OTHER");

      NeoResponse response = BankBouncePairService.dismissPair(handler,
          body(stranger.id, bounce.id));

      assertEquals(200, response.getHttpStatus());
      assertTrue(data(response).getBoolean("ok"));
      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
      assertNothingWritten(original, bounce, stranger);
    }
  }

  // ---------------------------------------------------------------------------
  // Bounce-pair KPI: the shape of every event, through the real sanitizer
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("bounce-pair KPI — exact property sets, nothing dropped by the allowlist")
  class KpiEventShape {

    /**
     * The panel decisions — discarded, failed (a 409 on a proposed pair) and unchecked — each
     * carry exactly the decided properties, all of them kept by the real allowlist, channel
     * {@code ui}, and no record data.
     *
     * @throws Exception when a call fails
     */
    @Test
    @DisplayName("panel discarded / failed / unchecked: exact keys, channel ui, nothing dropped")
    void panelDecisionsHaveExactlyTheirProperties() throws Exception {
      NeoTelemetryService service = realTelemetry();
      Line original = pendingLine("L-OUT", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line bounce = pendingLine("L-IN", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });
      Line refusedOriginal = pendingLine("L-RO", "-30.00", DAY, "ACME", "X");
      Line refusedBounce = pendingLine("L-RB", "30.00", DAY.plusDays(2), "ACME", "Y");
      when(refusedBounce.mock.isActive()).thenReturn(false);
      Line dismissedOriginal = pendingLine("L-DO", "-50.00", DAY, "", "TRANSFERENCIA");
      Line dismissedBounce = pendingLine("L-DB", "50.00", DAY, "", "DEVOLUCION TRANSFERENCIA");

      assertEquals(200, BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id)).getHttpStatus());
      awaitSent(1);
      assertEquals(409, BankBouncePairService.discardPair(handler,
          body(refusedOriginal.id, refusedBounce.id)).getHttpStatus());
      awaitSent(2);
      assertEquals(200, BankBouncePairService.dismissPair(handler,
          body(dismissedOriginal.id, dismissedBounce.id)).getHttpStatus());

      List<NeoTelemetryEvent> events = awaitSent(3);
      List<Map<String, Object>> raw = rawProperties(service);
      String[] decisions = { "discarded", "failed", "unchecked" };
      for (int i = 0; i < decisions.length; i++) {
        NeoTelemetryEvent event = events.get(i);
        assertEquals(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED, event.getName());
        assertShape(event, raw.get(i), DECIDED_KEYS);
        assertEquals(decisions[i], event.getProperties().get("decision"));
        assertEquals("panel", event.getProperties().get("surface"));
        assertEquals("ui", event.getProperties().get("channel"));
        assertEquals(CLIENT_ID, event.getClientId());
      }
    }

    /**
     * The restored event carries exactly its properties; inside an MCP tool call its channel is
     * {@code mcp}.
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("restored: exact keys, nothing dropped, channel mcp through the MCP")
    void restoredHasExactlyItsProperties() throws Exception {
      NeoTelemetryService service = realTelemetry();
      Line a = pendingLine("L-OUT", "-50.00", DAY, "ACME", "X");
      Line b = pendingLine("L-IN", "50.00", DAY.plusDays(1), "ACME", "Y");
      discardedPair(a, b);

      String previous = NeoWriteChannel.enter(NeoWriteChannel.MCP);
      try {
        assertEquals(200, BankBouncePairService.restorePair(handler, restoreBody(a.id))
            .getHttpStatus());
      } finally {
        NeoWriteChannel.restore(previous);
      }

      NeoTelemetryEvent event = awaitSent(1).get(0);
      assertEquals(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_RESTORED, event.getName());
      assertShape(event, rawProperties(service).get(0), RESTORED_KEYS);
      assertEquals("mcp", event.getProperties().get("channel"));
    }

    /**
     * The modal through an agent's {@code applySuggestions} (MCP): an applied pair, a group
     * refused 409 on a line an earlier group took (no detected pair, so only the decision and the
     * surface), a pair reported unchecked, and the summary — each with exactly its properties,
     * channel {@code mcp}, nothing dropped.
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("modal decided + summary: exact keys, nothing dropped, channel mcp")
    void modalEventsHaveExactlyTheirProperties() throws Exception {
      NeoTelemetryService service = realTelemetry();
      Line ao = pendingLine("L-AO", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line ab = pendingLine("L-AB", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });
      pendingLine("L-BO", "-30.00", DAY, "ACME", "X");
      pendingLine("L-BB", "30.00", DAY.plusDays(2), "ACME", "Y");
      Line stranger = pendingLine("L-X", "-7.00", DAY, "ZETA", "OTHER");
      JSONObject request = new JSONObject().put("bounceDecisions", new JSONArray()
          .put(decision("bounce-L-BO-L-BB", "unchecked")));

      String previous = NeoWriteChannel.enter(NeoWriteChannel.MCP);
      try {
        runModal(request, new JSONArray().put(pairGroup(ao.id, ab.id))
            .put(pairGroup(ao.id, stranger.id)));
      } finally {
        NeoWriteChannel.restore(previous);
      }

      List<NeoTelemetryEvent> events = awaitSent(4);
      List<Map<String, Object>> raw = rawProperties(service);
      assertShape(events.get(0), raw.get(0), DECIDED_KEYS);
      assertEquals("discarded", events.get(0).getProperties().get("decision"));
      assertShape(events.get(1), raw.get(1), DECIDED_UNKNOWN_PROPOSAL_KEYS);
      assertEquals("failed", events.get(1).getProperties().get("decision"));
      assertShape(events.get(2), raw.get(2), DECIDED_KEYS);
      assertEquals("unchecked", events.get(2).getProperties().get("decision"));
      for (int i = 0; i < 3; i++) {
        assertEquals("modal", events.get(i).getProperties().get("surface"));
      }
      NeoTelemetryEvent summary = events.get(3);
      assertEquals(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_SUGGESTIONS_APPLIED, summary.getName());
      assertShape(summary, raw.get(3), SUMMARY_KEYS);
      assertEquals(3, summary.getProperties().get("pairs_shown"));
      assertEquals(1, summary.getProperties().get("pairs_discarded"));
      assertEquals(1, summary.getProperties().get("pairs_unchecked"));
      assertEquals(0, summary.getProperties().get("pairs_sent_to_panel"));
      for (NeoTelemetryEvent event : events) {
        assertEquals("mcp", event.getProperties().get("channel"));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Bounce-pair KPI: delivery — batches, failures, a full queue
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("bounce-pair KPI — after commit in a batch, best effort, never fails the action")
  class KpiDelivery {

    /**
     * Inside a batch the {@code discarded} and {@code restored} events wait for the commit; an MCP
     * batch reports channel {@code mcp}.
     *
     * @throws Exception when a call fails
     */
    @Test
    @DisplayName("in a batch, discarded and restored are sent only after the commit")
    void successfulDecisionsWaitForTheBatchCommit() throws Exception {
      Line original = pendingLine("L-OUT", "-30.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "30.00", DAY.plusDays(2), "ACME", "Y");
      Line a = pendingLine("L-A", "-50.00", DAY, "BETA", "X");
      Line b = pendingLine("L-B", "50.00", DAY.plusDays(1), "BETA", "Y");
      discardedPair(a, b);

      String channel = NeoWriteChannel.enter(NeoWriteChannel.MCP);
      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        assertEquals(200, BankBouncePairService.discardPair(handler,
            body(original.id, bounce.id)).getHttpStatus());
        assertEquals(200, BankBouncePairService.restorePair(handler, restoreBody(a.id))
            .getHttpStatus());
        verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());

        NeoBatchScope.runAfterCommitActions();
      } finally {
        NeoBatchScope.close(previous);
        NeoWriteChannel.restore(channel);
      }

      Map<String, Object> props = decided(1).get(0);
      assertEquals("discarded", props.get("decision"));
      assertEquals("mcp", props.get("channel"), "an MCP batch is channel mcp");
      verify(telemetry, timeout(WAIT_MS)).emit(
          eq(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_RESTORED), anyMap(), eq(CLIENT_ID));
    }

    /**
     * A batch that rolls back never sends the {@code discarded} event it queued, not even when a
     * later batch on the same thread commits.
     *
     * @throws Exception when a call fails
     */
    @Test
    @DisplayName("in a batch that rolls back, the queued discarded event is dropped")
    void aRolledBackBatchSendsNoDiscardedEvent() throws Exception {
      Line original = pendingLine("L-OUT", "-30.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "30.00", DAY.plusDays(2), "ACME", "Y");

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        assertEquals(200, BankBouncePairService.discardPair(handler,
            body(original.id, bounce.id)).getHttpStatus());
      } finally {
        NeoBatchScope.close(previous);
      }
      NeoBatchScope next = NeoBatchScope.open(false);
      try {
        NeoBatchScope.runAfterCommitActions();
      } finally {
        NeoBatchScope.close(next);
      }

      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
    }

    /**
     * {@code failed} and the dismissal describe no write, so inside a batch they are sent at once,
     * before any commit, and survive the batch's rollback.
     *
     * @throws Exception when a call fails
     */
    @Test
    @DisplayName("in a batch, failed and dismissPair events are sent at once")
    void failedAndDismissedAreSentAtOnceInABatch() throws Exception {
      Line original = pendingLine("L-OUT", "-30.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "30.00", DAY.plusDays(2), "ACME", "Y");
      when(bounce.mock.isActive()).thenReturn(false);
      Line dismissed = pendingLine("L-DO", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      pendingLine("L-DB", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        assertEquals(409, BankBouncePairService.discardPair(handler,
            body(original.id, bounce.id)).getHttpStatus());
        assertEquals(200, BankBouncePairService.dismissPair(handler,
            new JSONObject().put("financialAccountId", ACCOUNT_ID)
                .put("statementLineId", dismissed.id)).getHttpStatus());

        List<Map<String, Object>> before = decided(2);
        assertEquals(List.of("failed", "unchecked"),
            List.of(before.get(0).get("decision"), before.get(1).get("decision")));
      } finally {
        NeoBatchScope.close(previous);
      }
      verify(telemetry, after(300).times(2)).emit(anyString(), anyMap(), any());
    }

    /**
     * A telemetry service that throws on every event never fails nor undoes the action: the
     * discard, the restore, the dismissal and the modal all complete and write what they write,
     * the events were attempted, and the sender keeps delivering afterwards.
     *
     * @throws Exception when a call fails
     */
    @Test
    @DisplayName("a throwing telemetry service never fails discard / restore / dismiss / modal")
    void aThrowingTelemetryNeverFailsTheAction() throws Exception {
      doThrow(new IllegalStateException("mixpanel down")).when(telemetry)
          .emit(anyString(), anyMap(), any());
      Line original = pendingLine("L-OUT", "-30.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "30.00", DAY.plusDays(2), "ACME", "Y");
      Line a = pendingLine("L-A", "-50.00", DAY, "BETA", "X");
      Line b = pendingLine("L-B", "50.00", DAY.plusDays(1), "BETA", "Y");
      discardedPair(a, b);
      Line mo = pendingLine("L-MO", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line mb = pendingLine("L-MB", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });

      assertEquals(200, BankBouncePairService.discardPair(handler,
          body(original.id, bounce.id)).getHttpStatus());
      assertEquals(200, BankBouncePairService.restorePair(handler, restoreBody(a.id))
          .getHttpStatus());
      assertEquals(200, BankBouncePairService.dismissPair(handler, body(mo.id, mb.id))
          .getHttpStatus());
      JSONArray results = runModal(new JSONObject(), new JSONArray().put(pairGroup(mo.id, mb.id)));

      assertEquals(Boolean.TRUE, original.value(StatementLineDiscard.COL_IS_DISCARDED));
      assertEquals(Boolean.FALSE, a.value(StatementLineDiscard.COL_IS_DISCARDED));
      assertTrue(results.getJSONObject(0).getBoolean("discarded"), results.toString());
      assertEquals(Boolean.TRUE, mb.value(StatementLineDiscard.COL_IS_DISCARDED));
      // discarded + restored + unchecked + modal discarded + summary: all attempted.
      verify(telemetry, timeout(WAIT_MS).times(5)).emit(anyString(), anyMap(), any());

      Mockito.doNothing().when(telemetry).emit(anyString(), anyMap(), any());
      Line late = pendingLine("L-LO", "-90.00", DAY, "", "TRANSFERENCIA");
      pendingLine("L-LB", "90.00", DAY, "", "DEVOLUCION TRANSFERENCIA");
      BankBouncePairService.dismissPair(handler, new JSONObject()
          .put("financialAccountId", ACCOUNT_ID).put("statementLineId", late.id));
      verify(telemetry, timeout(WAIT_MS).times(6)).emit(anyString(), anyMap(), any());
    }

    /**
     * The background sender's queue is full (a slow Mixpanel): every event is rejected and
     * dropped, and still the discard, the refused discard, the restore, the dismissal and the
     * modal answer exactly as without telemetry.
     *
     * @throws Exception when a call fails
     */
    @Test
    @DisplayName("a full sender queue drops the events and never fails any action")
    void aFullSenderQueueNeverFailsTheAction() throws Exception {
      Line original = pendingLine("L-OUT", "-30.00", DAY, "ACME", "X");
      Line bounce = pendingLine("L-IN", "30.00", DAY.plusDays(2), "ACME", "Y");
      Line refusedOriginal = pendingLine("L-RO", "-40.00", DAY, "GAMMA", "X");
      Line refusedBounce = pendingLine("L-RB", "40.00", DAY.plusDays(2), "GAMMA", "Y");
      when(refusedBounce.mock.isActive()).thenReturn(false);
      Line a = pendingLine("L-A", "-50.00", DAY, "BETA", "X");
      Line b = pendingLine("L-B", "50.00", DAY.plusDays(1), "BETA", "Y");
      discardedPair(a, b);
      Line mo = pendingLine("L-MO", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line mb = pendingLine("L-MB", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });

      ThreadPoolExecutor sender = sender();
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch started = new CountDownLatch(1);
      try {
        sender.execute(() -> {
          started.countDown();
          awaitQuietly(release);
        });
        assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS), "the sender thread is busy");
        while (sender.getQueue().remainingCapacity() > 0) {
          sender.execute(() -> { });
        }

        assertEquals(200, BankBouncePairService.discardPair(handler,
            body(original.id, bounce.id)).getHttpStatus());
        assertCoded(BankBouncePairService.discardPair(handler,
            body(refusedOriginal.id, refusedBounce.id)), 409,
            BankBounceErrors.CODE_LINE_NOT_PENDING);
        assertEquals(200, BankBouncePairService.restorePair(handler, restoreBody(a.id))
            .getHttpStatus());
        assertEquals(200, BankBouncePairService.dismissPair(handler, body(mo.id, mb.id))
            .getHttpStatus());
        JSONArray results = runModal(new JSONObject(),
            new JSONArray().put(pairGroup(mo.id, mb.id)));

        assertEquals(Boolean.TRUE, original.value(StatementLineDiscard.COL_IS_DISCARDED));
        assertEquals(Boolean.FALSE, a.value(StatementLineDiscard.COL_IS_DISCARDED));
        assertTrue(results.getJSONObject(0).getBoolean("discarded"), results.toString());
      } finally {
        release.countDown();
      }
      awaitDrained(sender);
      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
    }
  }

  // ---------------------------------------------------------------------------
  // dismissPair through its POST route envelope
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("POST ?action=dismissPair — runPostAction envelope, refusals roll back, no write")
  class DismissPairRoute {

    private MockedStatic<OBContext> obContext;

    /** {@code runPostAction} enters and leaves admin mode: the OBContext is mocked statically. */
    @BeforeEach
    void mockContext() {
      obContext = mockStatic(OBContext.class);
    }

    /** Closes the OBContext mock. */
    @AfterEach
    void closeContext() {
      obContext.close();
    }

    /**
     * The route runs {@code dismissPair} inside {@code runPostAction} (admin mode on and off),
     * answers 200 {@code {ok:true}}, does not roll back, records the {@code unchecked} decision
     * and writes nothing on any line.
     *
     * @throws Exception when the call fails
     */
    @Test
    @DisplayName("200 {ok:true} through runPostAction, no rollback, one unchecked event, no write")
    void dismissesThroughTheEnvelope() throws Exception {
      Line original = pendingLine("L-OUT", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line bounce = pendingLine("L-IN", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });
      NeoContext context = routeContext(body(bounce.id, original.id));

      NeoResponse response;
      try (MockedStatic<ReconciliationHandlerSupport> support =
          mockStatic(ReconciliationHandlerSupport.class, Mockito.CALLS_REAL_METHODS)) {
        response = BankBouncePairService.handleDismissPair(handler, context);
        support.verify(() -> ReconciliationHandlerSupport.runPostAction(handler, context,
            BankBouncePairService.ACTION_DISMISS_PAIR));
      }

      assertEquals(200, response.getHttpStatus());
      assertTrue(data(response).getBoolean("ok"));
      obContext.verify(() -> OBContext.setAdminMode(true));
      obContext.verify(OBContext::restorePreviousMode);
      verify(handler, never()).doRollbackAndClose();
      assertEquals(BankBounceKpiTelemetry.DECISION_UNCHECKED, decided(1).get(0).get("decision"));
      assertNothingWritten(original, bounce);
      verify(dal, never()).remove(any());
    }

    /**
     * Every refusal goes back through the envelope's rollback, sends nothing and writes nothing:
     * 403 with the flag off, 400 without {@code statementLineId}, 400 for the same line twice, 404
     * for a line that does not exist, 400 for a line or a counterpart of another account.
     *
     * @throws Exception when a call fails
     */
    @Test
    @DisplayName("403 flag off, 400 missing / same line, 404 missing, 400 foreign: rolled back")
    void refusalsRollBackAndSendNothing() throws Exception {
      Line original = pendingLine("L-OUT", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      Line bounce = pendingLine("L-IN", "80.00", DAY.plusDays(1), "", "ABONO");
      pisRows.add(new Object[] { "E2E777", "PAY-PIS" });
      Line foreign = pendingLine("L-FOREIGN", "-80.00", DAY, "", "SEPA TRANSFER E2E777");
      when(foreign.mock.getBankStatement().getAccount()).thenReturn(otherAccount);
      pending.remove(foreign.mock);

      assertEquals(400, route(new JSONObject().put("financialAccountId", ACCOUNT_ID)
          .put("counterpartLineId", bounce.id)).getHttpStatus());
      assertEquals(400, route(body(bounce.id, bounce.id)).getHttpStatus());
      assertEquals(404, route(body("L-NOPE", bounce.id)).getHttpStatus());
      assertEquals(404, route(body(bounce.id, "L-NOPE")).getHttpStatus());
      assertEquals(400, route(body(foreign.id, bounce.id)).getHttpStatus());
      assertEquals(400, route(body(bounce.id, foreign.id)).getHttpStatus());
      flag.when(BankBounceSuggestionsFlag::isEnabled).thenReturn(false);
      assertCoded(route(body(bounce.id, original.id)), 403, BankBounceErrors.CODE_SUGGESTIONS_OFF);

      verify(handler, Mockito.times(7)).doRollbackAndClose();
      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
      assertNothingWritten(original, bounce, foreign);
    }

    /**
     * Runs {@code dismissPair} through its POST route.
     *
     * @param body the request body
     * @return the answer
     */
    private NeoResponse route(JSONObject body) {
      return BankBouncePairService.handleDismissPair(handler, routeContext(body));
    }

    /**
     * The context of a {@code POST ?action=dismissPair} from the SPA.
     *
     * @param body the request body
     * @return the context
     */
    private NeoContext routeContext(JSONObject body) {
      return NeoContext.builder().httpMethod("POST").requestBody(body)
          .queryParams(Map.of("action", BankBouncePairService.ACTION_DISMISS_PAIR)).build();
    }
  }

  // ---------------------------------------------------------------------------
  // KPI helpers
  // ---------------------------------------------------------------------------

  /** The properties of a decision whose proposal detection gave. */
  private static final Set<String> DECIDED_KEYS = Set.of("account_id", "channel", "flag_enabled",
      "detection_signal", "signals_count", "confidence", "ambiguous", "adjacent", "preselected",
      "chosen_among_alternatives", "decision", "surface");
  /** The properties of a {@code failed} decision on a group detection does not see. */
  private static final Set<String> DECIDED_UNKNOWN_PROPOSAL_KEYS = Set.of("account_id", "channel",
      "flag_enabled", "decision", "surface");
  /** The properties of a restored pair. */
  private static final Set<String> RESTORED_KEYS = Set.of("account_id", "channel", "flag_enabled",
      "detection_signal", "confidence", "preselected", "days_since_decided");
  /** The properties of a restored pair whose discard time is unknown. */
  private static final Set<String> RESTORED_KEYS_WITHOUT_DAYS = Set.of("account_id", "channel",
      "flag_enabled", "detection_signal", "confidence", "preselected");
  /** The properties of the {@code applySuggestions} summary. */
  private static final Set<String> SUMMARY_KEYS = Set.of("account_id", "channel", "flag_enabled",
      "pairs_shown", "pairs_discarded", "pairs_unchecked", "pairs_sent_to_panel",
      "preselection_changed");

  /** The sanitized events the real telemetry service of {@link #realTelemetry} submitted. */
  private final List<NeoTelemetryEvent> sent = new CopyOnWriteArrayList<>();

  /**
   * Routes the KPI events through a REAL {@link NeoTelemetryService} — its allowlist, bounds and
   * null filtering — into {@link #sent}; spied, so the raw properties can be compared with what
   * survived.
   *
   * @return the spied service
   */
  private NeoTelemetryService realTelemetry() {
    NeoTelemetryService service = Mockito.spy(new NeoTelemetryService(sent::add, System::nanoTime));
    BankBounceKpiTelemetry.setTelemetryService(service);
    return service;
  }

  /**
   * Waits until the KPI sender ran everything handed to it so far ({@link #awaitDrained}), then
   * asserts that exactly {@code count} events were submitted. Deterministic: every KPI event of
   * the action is queued on the single sender thread before the action returns, so once a barrier
   * queued after them has run, every one of them has run too — no extra event can still arrive.
   *
   * @param count the expected number of events
   * @return the events, in order
   * @throws Exception when the sender cannot be read or the wait is interrupted
   */
  private List<NeoTelemetryEvent> awaitSent(int count) throws Exception {
    awaitDrained(sender());
    assertEquals(count, sent.size(), "submitted events: " + sent);
    return new ArrayList<>(sent);
  }

  /**
   * The raw properties every KPI event was emitted with, in order.
   *
   * @param service the spied service
   * @return the properties handed to {@code emit}
   */
  private static List<Map<String, Object>> rawProperties(NeoTelemetryService service) {
    ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
    verify(service, Mockito.atLeastOnce()).emit(anyString(), captor.capture(), any());
    return captor.getAllValues();
  }

  /**
   * One submitted event: exactly {@code keys}, every non-null raw property kept unchanged by the
   * allowlist, {@code flag_enabled: true}, and no record data.
   *
   * @param event the submitted event
   * @param raw   the properties it was emitted with
   * @param keys  the exact property names
   */
  private static void assertShape(NeoTelemetryEvent event, Map<String, Object> raw,
      Set<String> keys) {
    Map<String, Object> kept = new LinkedHashMap<>();
    raw.forEach((key, value) -> {
      if (value != null) {
        kept.put(key, value);
      }
    });
    assertEquals(kept, event.getProperties(), "the allowlist dropped or changed a property");
    assertEquals(keys, event.getProperties().keySet());
    assertEquals(Boolean.TRUE, event.getProperties().get("flag_enabled"));
    assertNoRecordData(event.getProperties());
  }

  /**
   * Runs the modal path of {@code applySuggestions} over bounce groups, as the handler does:
   * applyPairs with the call's detection and recorder, then prime and finish.
   *
   * @param request the body (may carry {@code bounceDecisions})
   * @param groups  the groups
   * @return the per-group results
   * @throws Exception when the call fails
   */
  private JSONArray runModal(JSONObject request, JSONArray groups) throws Exception {
    BankBouncePairService.Detection detection =
        new BankBouncePairService.Detection(handler, account, true);
    BankBounceKpiTelemetry.ModalDecisions kpi =
        BankBounceKpiTelemetry.ModalDecisions.of(detection, account, request);
    JSONArray results = new JSONArray();
    Set<String> taken = new HashSet<>();
    BankBouncePairService.applyPairs(handler, account, groups, taken, results, detection, kpi);
    kpi.prime();
    kpi.finish(taken);
    return results;
  }

  /**
   * A detection-model line with no statement.
   *
   * @param id     the line id
   * @param amount the signed amount
   * @return the line
   */
  private static StatementLine model(String id, long amount) {
    return new StatementLine(id, DAY, 10, "", "", BigDecimal.valueOf(amount), true);
  }

  /**
   * The KPI's background sender.
   *
   * @return the executor
   * @throws Exception when it cannot be read
   */
  private static ThreadPoolExecutor sender() throws Exception {
    Field field = BankBounceKpiTelemetry.class.getDeclaredField("SENDER");
    field.setAccessible(true);
    return (ThreadPoolExecutor) field.get(null);
  }

  /**
   * Waits on a latch, giving up after the test timeout.
   *
   * @param latch the latch
   */
  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(WAIT_MS * 5, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits until the sender ran every task queued before this call: a barrier task is put at the
   * tail of its FIFO queue (blocking while a full queue drains, never rejected) and awaited. The
   * sender is single-threaded, so the barrier runs only after everything ahead of it.
   *
   * @param sender the executor
   * @throws InterruptedException when interrupted while waiting
   */
  private static void awaitDrained(ThreadPoolExecutor sender) throws InterruptedException {
    CountDownLatch barrier = new CountDownLatch(1);
    assertTrue(sender.getQueue().offer(barrier::countDown, WAIT_MS * 5, TimeUnit.MILLISECONDS),
        "the sender queue never had room for the barrier");
    // The worker may have timed out while idle: make sure one is there to take the barrier.
    sender.prestartCoreThread();
    assertTrue(barrier.await(WAIT_MS * 5, TimeUnit.MILLISECONDS), "the sender never drained");
  }

  /**
   * Runs the modal path as an agent's {@code applySuggestions} (MCP channel), with a transaction
   * group that took {@code transactionLineId}.
   *
   * @param groups            the bounce-pair groups the agent sent
   * @param transactionLineId a line a transaction group of the call took
   * @param expected          how many decision events to wait for
   * @return the summary event's properties
   * @throws Exception when the call fails
   */
  private Map<String, Object> agentModal(JSONArray groups, String transactionLineId, int expected)
      throws Exception {
    BankBouncePairService.Detection detection =
        new BankBouncePairService.Detection(handler, account, true);
    String previous = NeoWriteChannel.enter(NeoWriteChannel.MCP);
    try {
      BankBounceKpiTelemetry.ModalDecisions kpi =
          BankBounceKpiTelemetry.ModalDecisions.of(detection, account, new JSONObject());
      Set<String> taken = new HashSet<>();
      BankBouncePairService.applyPairs(handler, account, groups, taken, new JSONArray(),
          detection, kpi);
      kpi.prime();
      taken.add(transactionLineId);
      kpi.finish(taken);
    } finally {
      NeoWriteChannel.restore(previous);
    }
    lastDecided = decided(expected);
    ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
    verify(telemetry, timeout(WAIT_MS)).emit(
        eq(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_SUGGESTIONS_APPLIED), captor.capture(),
        eq(CLIENT_ID));
    return captor.getValue();
  }

  /** The decision events captured by the last {@link #applyWithDecisions} call, in order. */
  private List<Map<String, Object>> lastDecided;

  /**
   * Runs the modal path of {@code applySuggestions} over the bounce groups: applyPairs with the
   * call's detection and recorder, then prime and finish, as the handler does.
   *
   * @param request  the body carrying {@code bounceDecisions}
   * @param groups   one group or a JSONArray of groups
   * @param expected how many decision events to wait for
   * @return the summary event's properties
   * @throws Exception when the call fails
   */
  private Map<String, Object> applyWithDecisions(JSONObject request, Object groups, int expected)
      throws Exception {
    JSONArray array = groups instanceof JSONArray ? (JSONArray) groups
        : new JSONArray().put(groups);
    BankBouncePairService.Detection detection =
        new BankBouncePairService.Detection(handler, account, true);
    BankBounceKpiTelemetry.ModalDecisions kpi =
        BankBounceKpiTelemetry.ModalDecisions.of(detection, account, request);
    Set<String> taken = new HashSet<>();
    BankBouncePairService.applyPairs(handler, account, array, taken, new JSONArray(), detection,
        kpi);
    kpi.prime();
    kpi.finish(taken);
    lastDecided = decided(expected);
    ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
    verify(telemetry, timeout(WAIT_MS)).emit(
        eq(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_SUGGESTIONS_APPLIED), captor.capture(),
        eq(CLIENT_ID));
    return captor.getValue();
  }

  /**
   * Waits for exactly {@code count} decision events and returns their properties, in order.
   *
   * @param count the expected number of events
   * @return their properties
   */
  private List<Map<String, Object>> decided(int count) {
    ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
    verify(telemetry, timeout(WAIT_MS).times(count)).emit(
        eq(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED), captor.capture(), any());
    return captor.getAllValues();
  }

  /**
   * A typed captor for event properties.
   *
   * @return the captor
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
  }

  /**
   * One {@code bounceDecisions} entry.
   *
   * @param groupKey the autoMatch groupKey
   * @param decision the decision
   * @return the entry
   * @throws Exception when it cannot be written
   */
  private static JSONObject decision(String groupKey, String decision) throws Exception {
    return new JSONObject().put("groupKey", groupKey).put("decision", decision);
  }

  /**
   * Legal rule: no line id, account id, amount or statement text in an event.
   *
   * @param props the event properties
   */
  private static void assertNoRecordData(Map<String, Object> props) {
    for (Object value : props.values()) {
      String text = String.valueOf(value);
      assertFalse(text.startsWith("L-") || text.contains(ACCOUNT_ID) || text.contains("ACME")
          || text.contains("TRANSFER") || text.contains(".00"), "record data leaked: " + props);
    }
  }

  /**
   * A matcher for lock options that take a write lock ({@code FOR UPDATE}), typed so it selects
   * {@code Session.refresh(Object, LockOptions)}.
   *
   * @return the matcher
   */
  private static LockOptions writeLockOptions() {
    return argThat((LockOptions options) -> options != null
        && options.getLockMode() == LockMode.PESSIMISTIC_WRITE);
  }

  /**
   * An {@code applySuggestions} bounce-pair group.
   *
   * @param lineId        its {@code statementLineId}
   * @param counterpartId its {@code counterpartLineId}
   * @return the group
   * @throws Exception when it cannot be written
   */
  private static JSONObject pairGroup(String lineId, String counterpartId) throws Exception {
    return new JSONObject().put("kind", "bouncePair").put("statementLineId", lineId)
        .put("counterpartLineId", counterpartId);
  }

  // ---------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------

  /** A simulated statement line: the mock plus the column values it holds, by property name. */
  private final class Line {
    final String id;
    final FIN_BankStatementLine mock;
    final Map<String, Object> values = new HashMap<>();

    /**
     * A simulated line.
     *
     * @param id   the line id
     * @param mock the DAL line mock
     */
    Line(String id, FIN_BankStatementLine mock) {
      this.id = id;
      this.mock = mock;
    }

    /**
     * The value the line holds in a column.
     *
     * @param column the column name
     * @return the value, or {@code null}
     */
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

  /**
   * Makes every {@code createQuery} on {@code entity} answer {@code rows} for the given result
   * type, re-read on each call so a test can add rows after the setup.
   *
   * @param entity     the queried entity
   * @param resultType the result type of the projection
   * @param rows       the rows to answer
   * @param <E>        the entity type
   * @param <T>        the result type
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private <E extends BaseOBObject, T> void stubQuery(Class<E> entity,
      Class<T> resultType, List<?> rows) {
    OBQuery obQuery = mock(OBQuery.class);
    Query hibernateQuery = mock(Query.class);
    when(dal.createQuery(eq(entity), anyString())).thenReturn(obQuery);
    when(obQuery.createQuery(resultType)).thenReturn(hibernateQuery);
    when(hibernateQuery.list()).thenAnswer(inv -> new ArrayList<>(rows));
  }

  /**
   * A financial account mock.
   *
   * @param id its id
   * @return the mock
   */
  private static FIN_FinancialAccount account(String id) {
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getId()).thenReturn(id);
    return acc;
  }

  /**
   * Midnight of a day in the default zone.
   *
   * @param day the day
   * @return the date
   */
  private static Date date(LocalDate day) {
    return Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  /**
   * The DAL property name the simulated model maps a column to.
   *
   * @param column the column name
   * @return the property name
   */
  private static String propertyNameOf(String column) {
    return "p_" + column.toLowerCase(Locale.ROOT);
  }

  /**
   * Short for {@link #propertyNameOf}.
   *
   * @param column the column name
   * @return the property name
   */
  private static String prop(String column) {
    return propertyNameOf(column);
  }

  /**
   * The four {@code EM_ETGO_Bounce_*} link columns.
   *
   * @return their names
   */
  private static String[] linkColumns() {
    return new String[] { BankBouncePairService.COL_PAIR_LINE,
        BankBouncePairService.COL_DISCARDED_AT, BankBouncePairService.COL_SIGNAL,
        BankBouncePairService.COL_CONFIDENCE };
  }

  /**
   * A {@code discardPair} body for the account.
   *
   * @param lineId        the {@code statementLineId}
   * @param counterpartId the {@code counterpartLineId}
   * @return the body
   * @throws Exception when it cannot be written
   */
  private static JSONObject body(String lineId, String counterpartId) throws Exception {
    return new JSONObject().put("financialAccountId", ACCOUNT_ID)
        .put("statementLineId", lineId).put("counterpartLineId", counterpartId);
  }

  /**
   * A {@code restorePair} body for the account.
   *
   * @param lineId the {@code lineId}
   * @return the body
   * @throws Exception when it cannot be written
   */
  private static JSONObject restoreBody(String lineId) throws Exception {
    return new JSONObject().put("financialAccountId", ACCOUNT_ID).put("lineId", lineId);
  }

  /**
   * The {@code data} of an enveloped answer.
   *
   * @param response the answer
   * @return its data
   * @throws Exception when it is not there
   */
  private static JSONObject data(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  /**
   * The strings of a JSON array, sorted.
   *
   * @param array the array
   * @return its strings
   * @throws Exception when an element is not a string
   */
  private static List<String> strings(JSONArray array) throws Exception {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      out.add(array.getString(i));
    }
    out.sort(null);
    return out;
  }

  /**
   * Asserts a coded refusal: its HTTP status, and its code at the top level and in {@code error}.
   *
   * @param response the answer
   * @param status   the expected status
   * @param code     the expected code
   * @throws Exception when the body cannot be read
   */
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
