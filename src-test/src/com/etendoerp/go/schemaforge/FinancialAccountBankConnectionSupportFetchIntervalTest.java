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

import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubProviderLookup;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;

import org.apache.logging.log4j.Level;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.usageevents.LogCapture;
import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for {@link FinancialAccountBankConnectionSupport#maxFetchIntervalOf}, the ETP-5181
 * lookup behind the "Importar desde" advisory: the number of days of history the account's PSD2
 * provider actually publishes, or null when there is no usable limit to advise against.
 *
 * <p>The limit is resolved from the CONNECTION's {@code providerCode}, deliberately not from the
 * {@code FIN_FinancialAccount.psd2Provider} FK, so the field-level hint can never quote a
 * different number from the warning the synchronization itself prints (the PSD2 module resolves
 * the provider the same way).
 *
 * <p>Every "no limit" answer is a distinct branch and each is pinned separately, because the whole
 * point of the method is that it NEVER invents a value: {@code fetchAndRegisterProvider} already
 * writes a made-up 90 when the provider details call fails, and a second default here would make
 * an unpublished limit indistinguishable from a real one.
 *
 * <p>The three cheap guards (null connection, transactions not handled, blank provider code) are
 * asserted to short-circuit BEFORE any DAL work, via {@code verifyNoInteractions} on the mocked
 * {@link OBDal} — that ordering is what keeps {@code GET status} from paying for a criteria query
 * on every account that cannot sync anyway.
 *
 * <p>ETP-6151: {@code maxFetchIntervalOfProvider} is the same lookup keyed by a bare provider code
 * ({@code GET accounts} has no connection yet), {@code maxFetchIntervalOfProviderOrNull} its total
 * form, and {@code addFetchIntervalAdvisory} the one envelope the three write actions answer the
 * advisory with.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountBankConnectionSupport
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountBankConnectionSupportFetchIntervalTest {

  private static final String PROVIDER_CODE = "bbva";

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  /** A missing connection means the account cannot sync at all — nothing to advise about. */
  @Test
  public void testNullConnectionHasNoLimit() {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(null));
      obDal.verifyNoInteractions();
    }
  }

  /**
   * A connection that does not handle transactions never imports movements, so the import range
   * is irrelevant to it. Guarded before the provider lookup so the query is not paid for.
   */
  @Test
  public void testConnectionNotHandlingTransactionsHasNoLimit() {
    FinaccConnection connection = connection(Boolean.FALSE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
      obDal.verifyNoInteractions();
    }
  }

  /**
   * {@code isHandlesTransactions()} is a boxed {@link Boolean} and is nullable in the DB, so the
   * check has to be {@code !Boolean.TRUE.equals(...)}: a plain {@code !flag} would unbox null and
   * throw an NPE straight out of {@code GET status}.
   */
  @Test
  public void testNullHandlesTransactionsFlagHasNoLimit() {
    FinaccConnection connection = connection(null, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
      obDal.verifyNoInteractions();
    }
  }

  /** A blank provider code cannot resolve a catalog row; short-circuited before the query. */
  @Test
  public void testBlankProviderCodeHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, "   ");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
      obDal.verifyNoInteractions();
    }
  }

  /** Same guard, null rather than whitespace. */
  @Test
  public void testNullProviderCodeHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, null);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
      obDal.verifyNoInteractions();
    }
  }

  /** The provider code is set but no catalog row matches it (never synced) — advise nothing. */
  @Test
  public void testUnknownProviderHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, null);

      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /**
   * The provider exists but published no interval. This must stay null rather than fall back to
   * 90: the SPA reads a missing value as "advise nothing", and a guessed default would advise
   * against dates the bank may well serve.
   */
  @Test
  public void testUnpublishedLimitHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(null));

      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /** Zero days is not a real limit; advising against it would flag every past date. */
  @Test
  public void testZeroLimitHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(BigDecimal.ZERO));

      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /** Same for a negative value, which the DECIMAL(10,0) column does not prevent. */
  @Test
  public void testNegativeLimitHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(BigDecimal.valueOf(-30)));

      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /**
   * The happy path: a published positive limit comes back as a plain {@link Integer}.
   *
   * <p>The narrowing lives here rather than in the caller on purpose. The DAL hands back a
   * {@code BigDecimal}, and jettison's {@code JSONObject.getInt()} unwraps one happily — so a
   * bridge that put the raw BigDecimal on the payload would emit {@code 90.0} while every
   * int-shaped assertion still passed, and the SPA would render "90.0 días". Returning
   * {@code Integer} makes that unrepresentable.
   */
  @Test
  public void testPublishedLimitIsReturned() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(BigDecimal.valueOf(90)));

      assertEquals(Integer.valueOf(90),
          FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /**
   * A {@code DECIMAL(10,0)} column can still arrive carrying a scale ({@code 90.00}), which is a
   * different {@code BigDecimal} from {@code 90} under {@code equals}. The narrowing must flatten
   * it to the same int rather than let the stored scale reach the wire.
   */
  @Test
  public void testScaledColumnValueIsNarrowedToAnInt() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(new BigDecimal("90.00")));

      assertEquals(Integer.valueOf(90),
          FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /**
   * A provider that serves MORE than the regulation's 90-day baseline must be reported at its own
   * value. This is the case a hardcoded 90 anywhere in the chain would silently break.
   */
  @Test
  public void testLimitAboveTheRegulatoryBaselineIsNotClamped() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(BigDecimal.valueOf(730)));

      assertEquals(Integer.valueOf(730),
          FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  // ── ETP-5805: findImportFromBeyondFetchInterval ───────────────────────────

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

  private static Date utcMidnight(LocalDate day) {
    return Date.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
  }

  /** Runs the rule with an active connection whose provider publishes {@code days} (null = none). */
  private static FinancialAccountBankConnectionSupport.FetchIntervalExceeded evaluate(
      BigDecimal days, Date importFrom) {
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<SaltEdgeAccountLinkHelper> helper = mockStatic(SaltEdgeAccountLinkHelper.class)) {
      helper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
          .thenReturn(connection);
      stubProviderLookup(obDal, provider(days));
      return FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(finAcc,
          importFrom, TODAY);
    }
  }

  /** The exact boundary day (today - N) does not warn: the comparison is strict. */
  @Test
  public void testImportFromOnTheBoundaryDayDoesNotWarn() {
    assertNull(evaluate(BigDecimal.valueOf(90), utcMidnight(TODAY.minusDays(90))));
  }

  /** One day older than the boundary warns and carries the limit and the earliest usable day. */
  @Test
  public void testImportFromOneDayBeyondTheBoundaryWarns() {
    FinancialAccountBankConnectionSupport.FetchIntervalExceeded out =
        evaluate(BigDecimal.valueOf(90), utcMidnight(TODAY.minusDays(91)));
    assertNotNull(out);
    assertEquals(90, out.getDays());
    assertEquals(TODAY.minusDays(90), out.getEarliestDate());
  }

  /** A date inside the window does not warn. */
  @Test
  public void testImportFromWithinTheWindowDoesNotWarn() {
    assertNull(evaluate(BigDecimal.valueOf(90), utcMidnight(TODAY.minusDays(10))));
  }

  /** No import-from date, nothing to advise. */
  @Test
  public void testNullImportFromDoesNotWarn() {
    assertNull(evaluate(BigDecimal.valueOf(90), null));
  }

  /** An undeclared (null) or zero limit never warns. */
  @Test
  public void testUndeclaredOrZeroLimitDoesNotWarn() {
    Date ancient = utcMidnight(TODAY.minusYears(5));
    assertNull(evaluate(null, ancient));
    assertNull(evaluate(BigDecimal.ZERO, ancient));
  }

  /** An account with no active connection has no limit. */
  @Test
  public void testNoActiveConnectionDoesNotWarn() {
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    try (MockedStatic<SaltEdgeAccountLinkHelper> helper = mockStatic(SaltEdgeAccountLinkHelper.class)) {
      helper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
          .thenReturn(null);
      assertNull(FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(finAcc,
          utcMidnight(TODAY.minusYears(5)), TODAY));
    }
  }

  /** The rule is total: a lookup failure means "say nothing", never an exception. */
  @Test
  public void testLookupFailureDoesNotThrow() {
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    try (MockedStatic<SaltEdgeAccountLinkHelper> helper = mockStatic(SaltEdgeAccountLinkHelper.class)) {
      helper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
          .thenThrow(new IllegalStateException("boom"));
      assertNull(FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(finAcc,
          utcMidnight(TODAY.minusYears(5)), TODAY));
    }
  }

  /** The translated text carries the days and the earliest date as the language writes it. */
  @Test
  public void testExceededTextIsTranslatedAndFormatted() {
    FinancialAccountBankConnectionSupport.FetchIntervalExceeded exceeded =
        new FinancialAccountBankConnectionSupport.FetchIntervalExceeded(730, LocalDate.of(2024, 10, 19));
    String en = FinancialAccountBankConnectionSupport.fetchIntervalExceededText(exceeded, "en_US");
    String es = FinancialAccountBankConnectionSupport.fetchIntervalExceededText(exceeded, "es_ES");
    org.junit.Assert.assertTrue(en, en.contains("730") && en.contains("2024-10-19"));
    org.junit.Assert.assertTrue(es, es.contains("730") && es.contains("19/10/2024")
        && es.contains("d\u00edas"));
  }

  /**
   * The calendar-day form is time-zone independent: under a zone ahead of UTC the boundary day does
   * not warn and the day before it does (the PUT hook feeds it the day from the request string).
   */
  @Test
  public void testCalendarDayRuleIsIndependentOfDefaultTimeZone() {
    java.util.TimeZone original = java.util.TimeZone.getDefault();
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Madrid"));
    try {
      FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
      FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);
      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<SaltEdgeAccountLinkHelper> helper = mockStatic(SaltEdgeAccountLinkHelper.class)) {
        helper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
            .thenReturn(connection);
        stubProviderLookup(obDal, provider(BigDecimal.valueOf(90)));
        assertNull(FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(finAcc,
            TODAY.minusDays(90), TODAY));
        assertNotNull(FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(finAcc,
            TODAY.minusDays(91), TODAY));
      }
    } finally {
      java.util.TimeZone.setDefault(original);
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static FinaccConnection connection(Boolean handlesTransactions, String providerCode) {
    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.isHandlesTransactions()).thenReturn(handlesTransactions);
    when(connection.getProviderCode()).thenReturn(providerCode);
    return connection;
  }

  private static Provider provider(BigDecimal maxFetchInterval) {
    Provider provider = mock(Provider.class);
    when(provider.getMaxFetchInterval()).thenReturn(maxFetchInterval);
    return provider;
  }

  // ── ETP-6151: maxFetchIntervalOfProvider / maxFetchIntervalOfProviderOrNull ──

  /** A blank or null code cannot resolve a catalog row: null, before any DAL work. */
  @Test
  public void testProviderLimitOfABlankCodeIsNullWithoutAQuery() {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOfProvider(null));
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOfProvider("  "));
      obDal.verifyNoInteractions();
    }
  }

  /** A registered provider's positive limit comes back as an int; an unknown one as null. */
  @Test
  public void testProviderLimitIsReadFromTheRegisteredRow() {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(new BigDecimal("180.00")));
      assertEquals(Integer.valueOf(180),
          FinancialAccountBankConnectionSupport.maxFetchIntervalOfProvider(PROVIDER_CODE));
    }
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, null);
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOfProvider(PROVIDER_CODE));
    }
  }

  /**
   * The total form answers the same value on success, and null plus a warning naming the provider
   * when the lookup throws: in {@code GET accounts} the limit is an extra, never a failure.
   */
  @Test
  public void testProviderLimitOrNullSwallowsALookupFailure() {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(BigDecimal.valueOf(90)));
      assertEquals(Integer.valueOf(90),
          FinancialAccountBankConnectionSupport.maxFetchIntervalOfProviderOrNull(PROVIDER_CODE));
    }
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        LogCapture log = LogCapture.of(FinancialAccountBankConnectionSupport.class)) {
      obDal.when(OBDal::getInstance).thenThrow(new IllegalStateException("no session"));

      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOfProviderOrNull(PROVIDER_CODE));
      assertEquals(1, log.messages(Level.WARN).size());
      String warning = log.messages(Level.WARN).get(0);
      assertTrue(warning, warning.contains(PROVIDER_CODE) && warning.contains("no session"));
    }
  }

  // ── ETP-6151: addFetchIntervalAdvisory ─────────────────────────────────────

  /**
   * Runs {@code addFetchIntervalAdvisory} on a fresh {@code okData} answer with the rule stubbed to
   * {@code exceeded} for 2019-03-01, for a request of the given origin.
   *
   * @param exceeded what the fetch-interval rule reports, or {@code null} for "within the history"
   * @param mcp      whether the request comes from the MCP
   * @return the annotated answer's body
   */
  private static JSONObject advisedBody(
      FinancialAccountBankConnectionSupport.FetchIntervalExceeded exceeded, boolean mcp)
      throws Exception {
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    Date importFrom = utcMidnight(LocalDate.of(2019, 3, 1));
    NeoContext context = mock(NeoContext.class);
    when(context.isMcpOrigin()).thenReturn(mcp);
    NeoResponse response = FinancialAccountBankConnectionSupport.okData(new JSONObject());
    try (MockedStatic<NeoLanguage> language = mockStatic(NeoLanguage.class);
        MockedStatic<FinancialAccountBankConnectionSupport> support = mockStatic(
            FinancialAccountBankConnectionSupport.class, Mockito.CALLS_REAL_METHODS)) {
      language.when(NeoLanguage::currentCode).thenReturn("en_US");
      support.when(() -> FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(
          eq(finAcc), eq(importFrom))).thenReturn(exceeded);
      FinancialAccountBankConnectionSupport.addFetchIntervalAdvisory(response, context, finAcc,
          importFrom);
    }
    return response.getBody();
  }

  /**
   * An import-from date beyond the history adds ONE {@code {type: "warning", text}} message with
   * the limit and the earliest usable day: at the top level for the SPA, inside {@code response}
   * for an MCP caller, and never in both.
   */
  @Test
  public void testAdvisoryIsAddedWhereTheOriginReadsIt() throws Exception {
    FinancialAccountBankConnectionSupport.FetchIntervalExceeded exceeded =
        new FinancialAccountBankConnectionSupport.FetchIntervalExceeded(90, TODAY.minusDays(90));
    for (boolean mcp : new boolean[] {false, true}) {
      JSONObject body = advisedBody(exceeded, mcp);

      JSONObject holder = mcp ? body.getJSONObject("response") : body;
      JSONObject other = mcp ? body : body.getJSONObject("response");
      JSONArray messages = holder.getJSONArray("messages");
      assertEquals("mcp=" + mcp, 1, messages.length());
      assertEquals("mcp=" + mcp, "warning", messages.getJSONObject(0).getString("type"));
      String text = messages.getJSONObject(0).getString("text");
      assertTrue(text, text.contains("90") && text.contains(TODAY.minusDays(90).toString()));
      assertFalse("mcp=" + mcp, other.has("messages"));
    }
  }

  /** Within the history (the rule says nothing), the answer is left untouched. */
  @Test
  public void testAdvisoryIsNotAddedWithinTheHistory() throws Exception {
    JSONObject body = advisedBody(null, false);

    assertFalse(body.has("messages"));
    assertFalse(body.getJSONObject("response").has("messages"));
  }
}
