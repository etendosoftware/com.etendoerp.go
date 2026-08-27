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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.geography.Country;

import com.etendoerp.go.schemaforge.FinancialAccountsPageHandler.AccountRow;
import com.etendoerp.go.schemaforge.FinancialAccountsPageHandler.Currency;

/**
 * Mockito-driven unit tests for {@link FinancialAccountsPageHandler}.
 *
 * <p>Strategy: spy the handler and stub the {@code loadAccounts} /
 * {@code loadAccountsWithTransactions} seams so the response builder runs over
 * deterministic in-memory fixtures, without hitting the DB or OBContext.
 * The {@code handle()} HTTP routing path is covered with a {@link NeoContext}
 * mock that only overrides the HTTP method.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Empty input → zeroed summary, empty accounts array.</li>
 *   <li>Mixed currencies → byCurrency aggregates per ISO code.</li>
 *   <li>Pending counter ignores zero entries and counts each account once.</li>
 *   <li>Suggestion engine counters stay at 0 (ETBR_Match_Suggestion lands in T5).</li>
 *   <li>{@code handle()} returns 405 on non-GET and never touches the loaders.</li>
 *   <li>{@code buildPayload()} envelope shape matches the contract the UI hook consumes.</li>
 * </ul>
 */
// Silent runner: the strict runner inspects mocks/spies after the class runs to
// report unnecessary stubbings, but clearMocks() (below) wipes the inline mock
// maker registry after each test, so that inspection would fail with
// NotAMockException. Silent skips it while keeping @Mock injection.
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountsPageHandlerTest {

  private static final String CLIENT_ID = "23C59575B9CF467C9620760EB255B389";
  private static final Set<String> ORGS = new HashSet<>(Arrays.asList(
      "0", "E443A31992CB4635AFCAEABE7183CE85"));

  private FinancialAccountsPageHandler handler;

  /**
   * Initializes a Mockito spy of the handler before each test so individual
   * methods can stub the DB-bound seams ({@code loadAccounts},
   * {@code loadAccountsWithTransactions}) without touching the real implementation.
   */
  @Before
  public void setUp() {
    handler = spy(new FinancialAccountsPageHandler());
  }

  /**
   * Releases the references the Mockito inline mock maker retains for every
   * mock created in a test. Without this, those references survive until GC and
   * accumulate across the whole module suite (which runs in a single test JVM),
   * pushing the fork past its heap limit. Clearing them after each test keeps
   * the heap flat without dropping any test or touching the build config.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── handle() routing ─────────────────────────────────────────────────────

  /**
   * Verifies that {@code handle()} short-circuits non-GET requests with a 405
   * status and never calls {@code buildPayload()}, leaving the DB layer
   * untouched on methods that should not even be accepted by the spec.
   *
   * @throws Exception
   *     if the Mockito verification fails
   */
  @Test
  public void testHandleRejectsNonGetMethods() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("POST");

    NeoResponse response = handler.handle(ctx);

    assertEquals(405, response.getHttpStatus());
    verify(handler, never()).buildPayload(anyString(), anySet());
  }

  // ── buildPayload() envelope ──────────────────────────────────────────────

  /**
   * Verifies that {@code buildPayload()} wraps the data in the
   * {@code response.data} envelope expected by the UI hook, that
   * {@code pendingCount} is propagated from the row's {@code EM_ETGO_Pending_Count}
   * stored computed column, and that both loader seams are invoked exactly once
   * with the client/orgs filter.
   *
   * @throws Exception
   *     if the stubbed loaders or JSON envelope inspection fails
   */
  @Test
  public void testBuildPayloadAssemblesEnvelopeAndDelegatesToLoaders() throws Exception {
    AccountRow bbva = account("acc-1", "BBVA", "B", new BigDecimal("1500.00"), "EUR");
    // Set on the row, the way loadAccounts() reads it out of the stored computed column,
    // instead of the loader map buildPayload used to consult.
    bbva.pendingCount = 4;
    List<AccountRow> accounts = Arrays.asList(bbva);
    Set<String> withTransactions = Collections.singleton("acc-1");

    doReturn(accounts).when(handler).loadAccounts(eq(CLIENT_ID), eq(ORGS));
    doReturn(withTransactions).when(handler).loadAccountsWithTransactions(eq(CLIENT_ID), eq(ORGS));

    // ETP-4896: buildPayload also attaches the countryIbanRules catalog, built by
    // FinancialAccountCountrySupport straight from OBDal (not a spied seam on this handler).
    FinancialAccountCountrySupport.clearIbanRulesCacheForTests();
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> countryCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(countryCriteria);
      when(countryCriteria.list()).thenReturn(Collections.emptyList());

      NeoResponse response = handler.buildPayload(CLIENT_ID, ORGS);

      assertEquals(200, response.getHttpStatus());
      JSONObject body = response.getBody();
      assertNotNull("response envelope must exist", body.optJSONObject("response"));
      JSONObject data = body.getJSONObject("response").getJSONObject("data");
      assertEquals(1, data.getJSONArray("accounts").length());
      assertEquals(4, data.getJSONArray("accounts").getJSONObject(0).getInt("pendingCount"));
      assertTrue("account with a registered transaction serialises hasTransactions=true",
          data.getJSONArray("accounts").getJSONObject(0).getBoolean("hasTransactions"));
      assertNotNull("summary present", data.optJSONObject("summary"));
      assertTrue("countryIbanRules is a sibling of accounts/summary, not per-account",
          data.has("countryIbanRules"));

      verify(handler).loadAccounts(CLIENT_ID, ORGS);
      verify(handler).loadAccountsWithTransactions(CLIENT_ID, ORGS);
    }
  }

  /**
   * Verifies the counterpart of {@link #testBuildPayloadAssemblesEnvelopeAndDelegatesToLoaders}:
   * when {@code loadAccountsWithTransactions} returns a set that does NOT contain the account,
   * the envelope serialises {@code hasTransactions=false} for it — the common case for a
   * freshly-created account with no movement history yet.
   *
   * @throws Exception if the stubbed loaders or JSON envelope inspection fails
   */
  @Test
  public void testBuildPayloadEmitsHasTransactionsFalseWhenAccountHasNoTransactions()
      throws Exception {
    List<AccountRow> accounts = Arrays.asList(
        account("acc-1", "BBVA", "B", new BigDecimal("1500.00"), "EUR"));

    doReturn(accounts).when(handler).loadAccounts(eq(CLIENT_ID), eq(ORGS));
    doReturn(Collections.emptySet()).when(handler)
        .loadAccountsWithTransactions(eq(CLIENT_ID), eq(ORGS));

    FinancialAccountCountrySupport.clearIbanRulesCacheForTests();
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> countryCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(countryCriteria);
      when(countryCriteria.list()).thenReturn(Collections.emptyList());

      NeoResponse response = handler.buildPayload(CLIENT_ID, ORGS);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertFalse("account with no registered transactions serialises hasTransactions=false",
          data.getJSONArray("accounts").getJSONObject(0).getBoolean("hasTransactions"));
    }
  }

  // ── buildSummary() ───────────────────────────────────────────────────────

  /**
   * Verifies that {@code buildSummary()} on empty input produces a fully zeroed
   * summary: total balance is 0, the currency breakdown is empty and every
   * pending counter is 0. This is the response a freshly-onboarded user gets
   * before they create any account.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testEmptyInputProducesZeroedSummary() throws Exception {
    JSONObject summary = handler.buildSummary(Collections.emptyList());

    assertEquals(0, new BigDecimal(summary.getString("totalBalance")).compareTo(BigDecimal.ZERO));
    assertEquals(0, summary.getJSONArray("byCurrency").length());

    JSONObject pending = summary.getJSONObject("pending");
    assertEquals(0, pending.getInt("accountsWithPending"));
    assertEquals(0, pending.getInt("suggestionsReady"));
    assertEquals(0, pending.getInt("byRule"));
  }

  /**
   * Verifies that accounts denominated in different ISO codes are aggregated
   * separately in the {@code byCurrency} array — EUR balances combine into one
   * entry, USD balances into another — without leaking across currencies.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testMixedCurrenciesAggregateByIsoCode() throws Exception {
    List<AccountRow> accounts = Arrays.asList(
        account("acc-1", "BBVA", "B", new BigDecimal("1000.00"), "EUR"),
        account("acc-2", "Caja Madrid", "C", new BigDecimal("250.50"), "EUR"),
        account("acc-3", "Citibank USD", "B", new BigDecimal("4000.00"), "USD"));

    JSONObject summary = handler.buildSummary(accounts);

    assertEquals(0,
        new BigDecimal("5250.50").compareTo(new BigDecimal(summary.getString("totalBalance"))));

    JSONArray byCurrency = summary.getJSONArray("byCurrency");
    Map<String, BigDecimal> totals = new HashMap<>();
    for (int i = 0; i < byCurrency.length(); i++) {
      JSONObject entry = byCurrency.getJSONObject(i);
      totals.put(entry.getString("currencyIso"), new BigDecimal(entry.getString("total")));
    }
    assertEquals(0, new BigDecimal("1250.50").compareTo(totals.get("EUR")));
    assertEquals(0, new BigDecimal("4000.00").compareTo(totals.get("USD")));
  }

  /**
   * Verifies the {@code accountsWithPending} counter: only accounts whose
   * pending-line count is strictly positive contribute, and each contributing
   * account is counted exactly once regardless of how many lines it has.
   * A row whose stored count is zero is ignored.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testPendingCounterIgnoresZeroEntriesAndCountsOncePerAccount() throws Exception {
    List<AccountRow> accounts = Arrays.asList(
        account("acc-1", "BBVA", "B", new BigDecimal("100"), "EUR"),
        account("acc-2", "Caja", "C", new BigDecimal("0"), "EUR"),
        account("acc-3", "Card", "T", new BigDecimal("50"), "USD"));

    accounts.get(0).pendingCount = 12;
    accounts.get(1).pendingCount = 0;
    accounts.get(2).pendingCount = 1;

    JSONObject summary = handler.buildSummary(accounts);
    assertEquals(2, summary.getJSONObject("pending").getInt("accountsWithPending"));
  }

  /**
   * Verifies that negative balances (e.g. credit-card debt or overdrafts) are
   * summed using signed arithmetic. The total balance must reflect the net
   * position across all accounts and the currency entry must still appear once.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testTotalBalanceHandlesNegativeBalances() throws Exception {
    List<AccountRow> accounts = Arrays.asList(
        account("acc-1", "BBVA", "B", new BigDecimal("100.00"), "EUR"),
        account("acc-2", "Overdraft", "B", new BigDecimal("-250.00"), "EUR"));

    JSONObject summary = handler.buildSummary(accounts);
    assertEquals(0,
        new BigDecimal("-150.00").compareTo(new BigDecimal(summary.getString("totalBalance"))));
    assertTrue(summary.getJSONArray("byCurrency").length() == 1);
  }

  // ── buildAccountsArray() ─────────────────────────────────────────────────

  /**
   * Verifies that {@code buildAccountsArray()} emits every field expected by
   * the UI for a row and defaults {@code pendingCount} to 0 when the pending
   * map has no entry for an account. This is the common path for accounts that
   * have no bank statement lines yet.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayDefaultsPendingCountToZero() throws Exception {
    List<AccountRow> accounts = Arrays.asList(
        account("acc-1", "BBVA", "B", new BigDecimal("100.00"), "EUR"));

    JSONArray arr = handler.buildAccountsArray(accounts,
        Collections.emptySet());
    assertEquals(1, arr.length());
    JSONObject row = arr.getJSONObject(0);
    assertEquals("acc-1", row.getString("id"));
    assertEquals("BBVA", row.getString("name"));
    assertEquals("B", row.getString("type"));
    assertEquals("EUR", row.getString("currencyIso"));
    assertEquals(0, row.getInt("pendingCount"));
    assertFalse(row.getBoolean("isDefault"));
    assertFalse("account absent from the transactions set serialises hasTransactions=false",
        row.getBoolean("hasTransactions"));
    assertEquals("the account() fixture has no country — serialises as \"\", not \"null\" "
        + "(ETP-4896)", "", row.getString("countryId"));
    assertEquals("", row.getString("countryIso"));
    assertEquals("", row.getString("countryName"));
  }

  /** A row WITH a country (ETP-4896) serialises countryId/countryIso/countryName from it. */
  @Test
  public void testBuildAccountsArrayEmitsCountryWhenRowHasOne() throws Exception {
    AccountRow withCountry = account("acc-1", "BBVA", "B", new BigDecimal("100.00"), "EUR");
    withCountry.country = new FinancialAccountsPageHandler.CountryRef("106", "ES", "Spain");

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(withCountry),
        Collections.emptySet());

    JSONObject row = arr.getJSONObject(0);
    assertEquals("106", row.getString("countryId"));
    assertEquals("ES", row.getString("countryIso"));
    assertEquals("Spain", row.getString("countryName"));
  }

  /**
   * Verifies that {@code buildAccountsArray()} serialises {@code swiftCode} (ETP-4896 QA
   * follow-up). The edit modal opened from the account DETAIL page reads its record from this R
   * spec, so this key is what keeps its BIC/SWIFT field from rendering empty on an account that
   * has one stored.
   *
   * @throws Exception if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayEmitsSwiftCode() throws Exception {
    AccountRow withSwift = account("acc-1", "BBVA", "B", new BigDecimal("100.00"), "EUR");
    withSwift.swiftCode = "BBVAESMM";
    AccountRow withoutSwift = account("acc-2", "Caja", "C", new BigDecimal("0.00"), "EUR");

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(withSwift, withoutSwift),
        Collections.emptySet());

    assertEquals("BBVAESMM", arr.getJSONObject(0).getString("swiftCode"));
    assertEquals("an account with no BIC serialises as \"\", not the literal \"null\"",
        "", arr.getJSONObject(1).getString("swiftCode"));
  }

  /**
   * Verifies that {@code buildAccountsArray()} serialises {@code providerLogoUrl} per row, and
   * that an account with no bank provider (the default, e.g. cash accounts) serialises it as an
   * empty string rather than omitting the key or emitting {@code null} — the SPA's avatar
   * component reads it unconditionally.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArraySerialisesProviderLogoUrl() throws Exception {
    AccountRow withLogo = account("acc-1", "BBVA", "B", new BigDecimal("100.00"), "EUR");
    withLogo.providerLogoUrl = "https://cdn.saltedge.com/bank_icons/bbva.png";
    AccountRow withoutProvider = account("acc-2", "Caja", "C", new BigDecimal("0.00"), "EUR");

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(withLogo, withoutProvider), Collections.emptySet());

    assertEquals("https://cdn.saltedge.com/bank_icons/bbva.png",
        arr.getJSONObject(0).getString("providerLogoUrl"));
    assertEquals("", arr.getJSONObject(1).getString("providerLogoUrl"));
  }

  /**
   * Verifies that when the pending map carries a positive count for an
   * account, that count is serialised faithfully into the row's
   * {@code pendingCount} field so the UI can render the "Conciliar (N)" pill
   * with the right number.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArraySerialisesPendingCountWhenAvailable() throws Exception {
    List<AccountRow> accounts = Arrays.asList(
        account("acc-1", "BBVA", "B", new BigDecimal("100.00"), "EUR"));
    accounts.get(0).pendingCount = 7;

    // The R spec keeps the flat JSON key `pendingCount` even though the column behind it is
    // EM_ETGO_Pending_Count: the detail view and the funds-transfer picker read that name.
    JSONArray arr = handler.buildAccountsArray(accounts, Collections.emptySet());
    assertEquals(7, arr.getJSONObject(0).getInt("pendingCount"));
  }

  /**
   * Verifies that {@code buildAccountsArray()} serialises the masked card
   * number (column {@code EM_PSD2_Masked_Pan}) into the row's {@code maskedPan}
   * field so the UI can show it under a card account's type.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayEmitsMaskedPanForCard() throws Exception {
    AccountRow card = new AccountRow("acc-9", "Tarjeta", "CA", new BigDecimal("0.00"),
        new Currency(currencyId("EUR"), "EUR"), "", false);
    card.maskedPan = "**** **** **** 1234";

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(card),
        Collections.emptySet());
    JSONObject row = arr.getJSONObject(0);
    assertEquals("CA", row.getString("type"));
    assertEquals("**** **** **** 1234", row.getString("maskedPan"));
  }

  /**
   * Verifies that {@code buildAccountsArray()} emits the {@code bankConnected} flag for each row:
   * an account with an active bank connection serialises {@code true}, one without serialises
   * {@code false}. The UI uses this to show the "Conectado" badge on the account card.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayEmitsBankConnectedFlag() throws Exception {
    AccountRow connected = account("acc-1", "BBVA Bank", "B", new BigDecimal("100.00"), "EUR");
    connected.bankConnected = true;
    AccountRow offline = account("acc-2", "Caja manual", "B", new BigDecimal("0.00"), "EUR");

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(connected, offline), Collections.emptySet());

    assertEquals(2, arr.length());
    assertTrue("connected account serialises bankConnected=true",
        arr.getJSONObject(0).getBoolean("bankConnected"));
    assertFalse("offline account serialises bankConnected=false",
        arr.getJSONObject(1).getBoolean("bankConnected"));
  }

  /**
   * Verifies that {@code loadAccounts()} maps column 11 ({@code em_psd2_connection_status}) to the
   * {@code bankConnected} flag: {@code 'CO'} (connected) → true, any other value → false.
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadAccountsMapsBankConnectionStatus() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(Array.class));
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString(1)).thenReturn("acc-1", "acc-2");
    when(rs.getString(8)).thenReturn("N", "N");
    when(rs.getString(9)).thenReturn("Y", "Y");
    // Column 11 (em_psd2_connection_status): first connected ('CO'), second pending ('IN').
    when(rs.getString(11)).thenReturn("CO", "IN");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      List<AccountRow> rows = handler.loadAccounts(CLIENT_ID, ORGS);

      assertEquals(2, rows.size());
      assertTrue("'CO' maps to bankConnected=true", rows.get(0).bankConnected);
      assertFalse("non-'CO' maps to bankConnected=false", rows.get(1).bankConnected);
    }
  }

  /**
   * Verifies that {@code loadAccounts()} maps column 17 ({@code prov.logo_url}, from the
   * {@code psd2_provider} LEFT JOIN) into {@link AccountRow#providerLogoUrl}: present for an
   * account whose provider has a logo, blank for one whose provider row has none (LEFT JOIN
   * returns SQL NULL, not a missing row — most accounts have no provider at all).
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadAccountsMapsProviderLogoUrl() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(Array.class));
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString(1)).thenReturn("acc-1", "acc-2");
    when(rs.getString(8)).thenReturn("N", "N");
    when(rs.getString(9)).thenReturn("Y", "Y");
    // Column 17 (prov.logo_url): first has a logo, second's provider row has none (SQL NULL).
    when(rs.getString(17)).thenReturn("https://cdn.saltedge.com/bank_icons/bbva.png", null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      List<AccountRow> rows = handler.loadAccounts(CLIENT_ID, ORGS);

      assertEquals(2, rows.size());
      assertEquals("https://cdn.saltedge.com/bank_icons/bbva.png", rows.get(0).providerLogoUrl);
      assertEquals("", rows.get(1).providerLogoUrl);
    }
  }

  /**
   * Verifies that {@code loadAccounts()} maps columns 12 ({@code em_etgo_date_tolerance}) and 13
   * ({@code em_etgo_amount_tolerance}) via COALESCE into the {@link AccountRow#dateTolerance} and
   * {@link AccountRow#amountTolerance} fields. The COALESCE in the SQL means the DB never returns
   * NULL for these columns, but the Java side also guards for null (column 13 is BigDecimal).
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadAccountsReadsToleranceColumns() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(java.sql.Array.class));
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("acc-tol");
    when(rs.getString(8)).thenReturn("N");
    when(rs.getString(9)).thenReturn("Y");
    when(rs.getString(11)).thenReturn("CO");
    // Column 12: dateTolerance = 5 (non-default)
    when(rs.getInt(12)).thenReturn(5);
    // Column 13: amountTolerance = 2.50 (non-default)
    when(rs.getBigDecimal(13)).thenReturn(new BigDecimal("2.50"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      List<AccountRow> rows = handler.loadAccounts(CLIENT_ID, ORGS);

      assertEquals(1, rows.size());
      AccountRow row = rows.get(0);
      assertEquals("dateTolerance column 12 read correctly", 5, row.dateTolerance);
      assertEquals("amountTolerance column 13 read correctly",
          0, new BigDecimal("2.50").compareTo(row.amountTolerance));
    }
  }

  /**
   * Verifies that {@code loadAccounts()} maps columns 14 ({@code em_aprm_glitem_diff}) and 15
   * (the joined {@code c_glitem.name}) into {@link AccountRow#glItemDifferenceId} and {@link
   * AccountRow#glItemDifferenceName} (ETP-4795), and that an account with no GL item configured
   * (both columns blank) degrades to the empty-string defaults rather than {@code null}.
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadAccountsReadsGlItemDifferenceColumns() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(java.sql.Array.class));
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString(1)).thenReturn("acc-caja", "acc-banco");
    when(rs.getString(8)).thenReturn("N", "N");
    when(rs.getString(9)).thenReturn("Y", "Y");
    when(rs.getString(11)).thenReturn("IN", "CO");
    // acc-caja has a GL Item Difference configured; acc-banco has none.
    when(rs.getString(14)).thenReturn("gli-diff-1", "");
    when(rs.getString(15)).thenReturn("Diferencias de caja", "");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      List<AccountRow> rows = handler.loadAccounts(CLIENT_ID, ORGS);

      assertEquals(2, rows.size());
      assertEquals("gli-diff-1", rows.get(0).glItemDifferenceId);
      assertEquals("Diferencias de caja", rows.get(0).glItemDifferenceName);
      assertEquals("", rows.get(1).glItemDifferenceId);
      assertEquals("", rows.get(1).glItemDifferenceName);
    }
  }

  /**
   * Verifies that {@code buildAccountsArray()} always emits {@code glItemDifferenceId} and
   * {@code glItemDifferenceName} (ETP-4795), even when the {@link AccountRow} default (blank) is
   * never overwritten by the loader — the Edit Account modal reads both unconditionally.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayEmitsGlItemDifference() throws Exception {
    AccountRow row = account("acc-1", "Caja", "C", new BigDecimal("100.00"), "EUR");
    row.glItemDifferenceId = "gli-diff-1";
    row.glItemDifferenceName = "Diferencias de caja";

    JSONArray arr = handler.buildAccountsArray(Collections.singletonList(row), Collections.emptySet());

    assertEquals(1, arr.length());
    JSONObject json = arr.getJSONObject(0);
    assertEquals("gli-diff-1", json.getString("glItemDifferenceId"));
    assertEquals("Diferencias de caja", json.getString("glItemDifferenceName"));
  }

  /**
   * Verifies that {@code buildAccountsArray()} emits the {@code active} flag for
   * each row: active accounts serialise {@code true}, archived ones serialise
   * {@code false}, so the UI can split them into the normal and "inactive"
   * views. Both rows are always listed regardless of their state.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayEmitsActiveFlag() throws Exception {
    List<AccountRow> accounts = Arrays.asList(
        account("acc-1", "BBVA", "B", new BigDecimal("100.00"), "EUR"),
        inactiveAccount("acc-2", "Santander Cerrada", "B", new BigDecimal("0.00"), "EUR"));

    JSONArray arr = handler.buildAccountsArray(accounts,
        Collections.emptySet());

    assertEquals(2, arr.length());
    assertTrue("active account serialises active=true", arr.getJSONObject(0).getBoolean("active"));
    assertFalse("archived account serialises active=false", arr.getJSONObject(1).getBoolean("active"));
  }

  /**
   * Verifies that {@code buildSummary()} aggregates only active accounts: the
   * total balance, the {@code byCurrency} breakdown and the
   * {@code accountsWithPending} counter must all ignore archived rows even when
   * those rows carry a balance and a positive pending count. This keeps the
   * sidebar widgets aligned with the active accounts shown in the default view.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildSummaryExcludesInactiveAccounts() throws Exception {
    List<AccountRow> accounts = Arrays.asList(
        account("acc-1", "BBVA", "B", new BigDecimal("1000.00"), "EUR"),
        inactiveAccount("acc-2", "Santander Cerrada", "B", new BigDecimal("500.00"), "EUR"),
        inactiveAccount("acc-3", "Citibank Cerrada", "B", new BigDecimal("4000.00"), "USD"));

    accounts.get(0).pendingCount = 2;
    // Archived accounts carry pending lines too, but they must not be counted.
    accounts.get(1).pendingCount = 9;
    accounts.get(2).pendingCount = 5;

    JSONObject summary = handler.buildSummary(accounts);

    // Only the active EUR account contributes to the total.
    assertEquals(0,
        new BigDecimal("1000.00").compareTo(new BigDecimal(summary.getString("totalBalance"))));

    // The archived USD account must not create a USD currency entry.
    JSONArray byCurrency = summary.getJSONArray("byCurrency");
    assertEquals(1, byCurrency.length());
    assertEquals("EUR", byCurrency.getJSONObject(0).getString("currencyIso"));
    assertEquals(0,
        new BigDecimal("1000.00").compareTo(new BigDecimal(byCurrency.getJSONObject(0).getString("total"))));

    // Only the active account with pending lines is counted.
    assertEquals(1, summary.getJSONObject("pending").getInt("accountsWithPending"));
  }

  // ── Default afterHandle hooks ────────────────────────────────────────────

  /**
   * Verifies that the handler does not override the default {@link NeoHandler}
   * post-hooks: {@code afterHandle} and {@code afterCallout} both return
   * {@code null} so the dispatcher keeps the upstream response untouched.
   */
  @Test
  public void testAfterHandleHooksReturnNullByDefault() {
    NeoContext ctx = mock(NeoContext.class);
    assertNull("afterHandle is not overridden", handler.afterHandle(ctx));
    assertNull("afterCallout is not overridden", handler.afterCallout(ctx));
  }

  // ── handle() GET happy / error paths ─────────────────────────────────────

  /**
   * Verifies the full {@code handle()} GET happy path: the static OBContext
   * accessors return the mocked client/organization, the spied
   * {@code accessibleOrgs} provides the org tree, and the returned
   * NeoResponse is the one produced by {@code buildPayload}. The static
   * {@code setAdminMode} / {@code restorePreviousMode} are also verified to be
   * invoked exactly once each.
   *
   * @throws Exception
   *     if any of the mocked chains fails
   */
  @Test
  public void testHandleGetReturnsBuiltPayload() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");

    OBContext realCtx = mock(OBContext.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(org.getId()).thenReturn("root-org");
    when(realCtx.getCurrentClient()).thenReturn(client);
    when(realCtx.getCurrentOrganization()).thenReturn(org);

    NeoResponse expected = NeoResponse.ok(new JSONObject());
    doReturn(ORGS).when(handler).accessibleOrgs("root-org");
    doReturn(expected).when(handler).buildPayload(CLIENT_ID, ORGS);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(realCtx);

      NeoResponse response = handler.handle(ctx);

      assertSame("returns the payload built by buildPayload", expected, response);
      obContextMock.verify(() -> OBContext.setAdminMode(true));
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * Verifies the {@code handle()} error path: when any downstream call throws,
   * the handler catches the exception and returns a 500 NeoResponse rather
   * than propagating. The admin mode is still restored in the {@code finally}
   * block.
   *
   * @throws Exception
   *     if any of the mocked chains fails
   */
  @Test
  public void testHandleGetReturnsServerErrorWhenLoaderThrows() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");

    OBContext realCtx = mock(OBContext.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(org.getId()).thenReturn("root-org");
    when(realCtx.getCurrentClient()).thenReturn(client);
    when(realCtx.getCurrentOrganization()).thenReturn(org);

    doReturn(ORGS).when(handler).accessibleOrgs("root-org");
    doThrow(new RuntimeException("boom")).when(handler).buildPayload(CLIENT_ID, ORGS);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(realCtx);

      NeoResponse response = handler.handle(ctx);

      assertEquals(500, response.getHttpStatus());
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  // ── loadAccounts() ───────────────────────────────────────────────────────

  /**
   * Verifies that {@code loadAccounts} maps every column of the result set
   * into an {@link AccountRow} fixture: id, name, type, balance, currency,
   * IBAN and the {@code isDefault} flag are read in the expected positions
   * and the SQL bind parameters are set with the client id and the org array.
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadAccountsMapsResultSetRowsIntoAccountFixtures() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    Array orgArray = mock(Array.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(orgArray);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, true, false);

    when(rs.getString(1)).thenReturn("acc-1", "acc-2");
    when(rs.getString(2)).thenReturn("BBVA", "Caja");
    when(rs.getString(3)).thenReturn("B", "C");
    when(rs.getBigDecimal(4)).thenReturn(new BigDecimal("1500.00"), null);
    when(rs.getString(5)).thenReturn("102", "102");
    when(rs.getString(6)).thenReturn("EUR", "EUR");
    when(rs.getString(7)).thenReturn("ES12...", "");
    when(rs.getString(8)).thenReturn("Y", "N");
    // Column 9 (fa.isactive): first row active ("Y"), second archived ("N").
    when(rs.getString(9)).thenReturn("Y", "N");
    // Columns 19-21 (ETP-4896, appended at the END of the SELECT — see ACCOUNTS_SQL's own
    // comment on why): first row has a country (a Bank account with an IBAN), second does not
    // (e.g. a Cash account, or a Bank account never given one).
    when(rs.getString(19)).thenReturn("106", null);
    when(rs.getString(20)).thenReturn("ES", null);
    when(rs.getString(21)).thenReturn("Spain", null);
    // Column 22: EM_ETGO_Pending_Count, the stored computed column, appended after the
    // ETP-4896 country block for the same reason — every column here is read BY POSITION.
    // COALESCEd in the SQL, so getInt never sees a NULL.
    when(rs.getInt(22)).thenReturn(4, 0);
    // Column 23: fa.swiftcode (ETP-4896 QA follow-up), appended last for the same positional
    // reason. First row has a BIC, second has none (a Cash account, or a Bank account without one).
    when(rs.getString(23)).thenReturn("BBVAESMM", null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      List<AccountRow> rows = handler.loadAccounts(CLIENT_ID, ORGS);

      assertEquals(2, rows.size());
      AccountRow first = rows.get(0);
      assertEquals("acc-1", first.id);
      assertEquals("BBVA", first.name);
      assertEquals("B", first.type);
      assertEquals(0, new BigDecimal("1500.00").compareTo(first.currentBalance));
      assertEquals("EUR", first.currency.iso);
      assertTrue("first row is default", first.isDefault);

      assertTrue("first row maps column 9 'Y' to active", first.active);
      assertNotNull("first row maps columns 19-21 into a CountryRef", first.country);
      assertEquals("106", first.country.id);
      assertEquals("ES", first.country.iso);
      assertEquals("Spain", first.country.name);
      assertEquals("first row maps column 22 into pendingCount", 4, first.pendingCount);
      assertEquals("first row maps column 23 into swiftCode", "BBVAESMM", first.swiftCode);

      AccountRow second = rows.get(1);
      assertEquals("acc-2", second.id);
      assertEquals(0, BigDecimal.ZERO.compareTo(second.currentBalance));
      assertFalse("second row is not default", second.isDefault);
      assertFalse("second row maps column 9 'N' to inactive", second.active);
      assertNull("a null column 19 (no C_Country_ID) leaves row.country null, not a CountryRef "
          + "full of blanks", second.country);
      assertEquals("a zero column 22 is a real zero, not a missing value", 0,
          second.pendingCount);
      assertEquals("a null column 23 becomes \"\", never the literal \"null\"",
          "", second.swiftCode);

      verify(ps).setString(1, CLIENT_ID);
      verify(ps).setArray(2, orgArray);
    }
  }

  /**
   * Verifies that {@code loadAccounts} returns an empty list when the result
   * set is empty — guards the early-exit branch of the loop.
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadAccountsReturnsEmptyListWhenResultSetEmpty() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(Array.class));
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      List<AccountRow> rows = handler.loadAccounts(CLIENT_ID, ORGS);

      assertTrue("expected empty list", rows.isEmpty());
    }
  }

  // ── Tolerance fields (dateTolerance / amountTolerance) ───────────────────

  /**
   * Verifies that {@code buildAccountsArray()} serialises the {@code dateTolerance} and
   * {@code amountTolerance} fields from the {@link AccountRow} into the row JSON so the
   * reconciliation UI can read the per-account matching settings without a second request.
   * The default values (3 days, 0%) are emitted when the DB columns return NULL (COALESCE).
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayEmitsToleranceFieldsWithDefaults() throws Exception {
    // AccountRow constructor initialises dateTolerance=3 and amountTolerance=0 — the
    // COALESCE defaults the DB returns when the columns are NULL.
    List<AccountRow> accounts = Arrays.asList(
        account("acc-tol", "BBVA", "B", new BigDecimal("100.00"), "EUR"));

    JSONArray arr = handler.buildAccountsArray(accounts,
        Collections.emptySet());

    JSONObject row = arr.getJSONObject(0);
    assertTrue("dateTolerance field must be present", row.has("dateTolerance"));
    assertTrue("amountTolerance field must be present", row.has("amountTolerance"));
    assertEquals("default dateTolerance is 3 days", 3, row.getInt("dateTolerance"));
    assertEquals("default amountTolerance is 0",
        0, BigDecimal.ZERO.compareTo(new BigDecimal(row.getString("amountTolerance"))));
  }

  /**
   * Verifies that a non-default tolerance (e.g. set by the user in the account settings)
   * is faithfully propagated into the JSON row — the field is not hard-coded to the default.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayEmitsCustomToleranceValues() throws Exception {
    AccountRow acc = account("acc-custom-tol", "Caja", "C", new BigDecimal("50.00"), "EUR");
    acc.dateTolerance = 7;
    acc.amountTolerance = new BigDecimal("1.50");

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(acc),
        Collections.emptySet());

    JSONObject row = arr.getJSONObject(0);
    assertEquals("custom dateTolerance serialised", 7, row.getInt("dateTolerance"));
    assertEquals("custom amountTolerance serialised",
        0, new BigDecimal("1.50").compareTo(new BigDecimal(row.getString("amountTolerance"))));
  }

  // ── hasTransactions flag (ETP-4530) ──────────────────────────────────────

  /**
   * Verifies that {@code buildAccountsArray()} serialises {@code hasTransactions} per row from
   * the {@code accountsWithTransactions} set: an id present in the set serialises {@code true},
   * one absent serialises {@code false}. The frontend uses this (ETP-4530) to lock the Currency
   * field once an account has real movement history, so both branches must be exercised.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayEmitsHasTransactionsFlag() throws Exception {
    AccountRow withHistory = account("acc-1", "BBVA", "B", new BigDecimal("100.00"), "EUR");
    AccountRow withoutHistory = account("acc-2", "Caja nueva", "C", new BigDecimal("0.00"), "EUR");
    Set<String> accountsWithTransactions = Collections.singleton("acc-1");

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(withHistory, withoutHistory), accountsWithTransactions);

    assertEquals(2, arr.length());
    assertTrue("account with registered transactions serialises hasTransactions=true",
        arr.getJSONObject(0).getBoolean("hasTransactions"));
    assertFalse("account with no registered transactions serialises hasTransactions=false",
        arr.getJSONObject(1).getBoolean("hasTransactions"));
  }

  /**
   * Verifies that an empty {@code accountsWithTransactions} set (the common case — no account
   * in scope has any movement yet) leaves every row's {@code hasTransactions} as {@code false}.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayHasTransactionsFalseWhenSetEmpty() throws Exception {
    List<AccountRow> accounts = Arrays.asList(
        account("acc-1", "BBVA", "B", new BigDecimal("100.00"), "EUR"),
        account("acc-2", "Caja", "C", new BigDecimal("0.00"), "EUR"));

    JSONArray arr = handler.buildAccountsArray(accounts,
        Collections.emptySet());

    assertFalse(arr.getJSONObject(0).getBoolean("hasTransactions"));
    assertFalse(arr.getJSONObject(1).getBoolean("hasTransactions"));
  }

  // ── loadAccountsWithTransactions() ───────────────────────────────────────

  /**
   * Verifies that {@code loadAccountsWithTransactions} returns the set of account ids read from
   * column 1 of the result set, and that the SQL bind parameters are the client id and the org
   * array — mirroring the other two loaders' seam contract.
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadAccountsWithTransactionsReturnsIdsFromResultSet() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    Array orgArray = mock(Array.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(orgArray);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString(1)).thenReturn("acc-1", "acc-3");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      Set<String> result = handler.loadAccountsWithTransactions(CLIENT_ID, ORGS);

      assertEquals(2, result.size());
      assertTrue("acc-1 must be present", result.contains("acc-1"));
      assertTrue("acc-3 must be present", result.contains("acc-3"));
      verify(ps).setString(1, CLIENT_ID);
      verify(ps).setArray(2, orgArray);
    }
  }

  /**
   * Verifies that {@code loadAccountsWithTransactions} returns an empty set when no account in
   * scope has any active transaction — the state of a freshly-onboarded client.
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadAccountsWithTransactionsReturnsEmptySetWhenResultSetEmpty()
      throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(Array.class));
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      Set<String> result = handler.loadAccountsWithTransactions(CLIENT_ID, ORGS);

      assertTrue("expected empty set", result.isEmpty());
    }
  }

  // ── loadDeleteBlockersByAccount() (ETP-4871) ─────────────────────────────

  /**
   * Verifies that {@code loadDeleteBlockersByAccount} maps each SQL reason code to the exact same
   * {@code REASON_*} wording {@link FinancialAccountDeleteSupport} uses for the DELETE 409
   * message — the two must never drift apart, since {@code FinancialAccountHandler#deleteAccount} and this
   * batched, page-scoped loader independently name the same blockers to two different UI surfaces
   * (the 409 error and the list's {@code deleteBlockedReason} field).
   *
   * @throws Exception if the mocked JDBC chain fails
   */
  @Test
  public void testLoadDeleteBlockersByAccountReasonWordingMatchesHandlerConstants() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(Array.class));
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("acc-1");
    when(rs.getString(2)).thenReturn("TRANSACTIONS");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      Map<String, List<String>> result = handler.loadDeleteBlockersByAccount(CLIENT_ID, ORGS);

      // Same constant the DELETE 409 message uses for the "has transactions" blocker.
      assertEquals(Collections.singletonList(FinancialAccountDeleteSupport.REASON_TRANSACTIONS),
          result.get("acc-1"));
    }
  }

  /**
   * Verifies that reasons are aggregated per account id, deduplicated (a duplicate row for the
   * same account+reason — e.g. two active transactions — must not repeat the reason text), and
   * that different accounts do not leak reasons into one another.
   *
   * @throws Exception if the mocked JDBC chain fails
   */
  @Test
  public void testLoadDeleteBlockersByAccountAggregatesMultipleReasonsWithoutDuplicates()
      throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(Array.class));
    when(ps.executeQuery()).thenReturn(rs);
    // acc-1 appears twice for TRANSACTIONS (e.g. two active transaction rows) and once for
    // BANK_CONNECTION; acc-2 appears once for RECONCILIATIONS.
    when(rs.next()).thenReturn(true, true, true, true, false);
    when(rs.getString(1)).thenReturn("acc-1", "acc-1", "acc-1", "acc-2");
    when(rs.getString(2)).thenReturn("TRANSACTIONS", "TRANSACTIONS", "BANK_CONNECTION", "RECONCILIATIONS");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      Map<String, List<String>> result = handler.loadDeleteBlockersByAccount(CLIENT_ID, ORGS);

      assertEquals(2, result.size());
      List<String> acc1Reasons = result.get("acc-1");
      // The duplicate TRANSACTIONS row is deduplicated, so acc-1 has exactly two distinct
      // reasons, not three.
      assertEquals(2, acc1Reasons.size());
      assertTrue(acc1Reasons.contains(FinancialAccountDeleteSupport.REASON_TRANSACTIONS));
      assertTrue(acc1Reasons.contains(FinancialAccountDeleteSupport.REASON_BANK_CONNECTION));
      assertEquals(Collections.singletonList(FinancialAccountDeleteSupport.REASON_RECONCILIATIONS),
          result.get("acc-2"));
    }
  }

  /**
   * Verifies that a reason code the reason-map does not recognise (e.g. a future branch added to
   * the SQL without a matching entry in {@code DELETE_BLOCKER_REASON_BY_CODE}) is defensively
   * skipped rather than producing a phantom blocker with a {@code null} reason string.
   *
   * @throws Exception if the mocked JDBC chain fails
   */
  @Test
  public void testLoadDeleteBlockersByAccountIgnoresUnrecognizedReasonCode() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(Array.class));
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("acc-1");
    when(rs.getString(2)).thenReturn("SOME_FUTURE_CODE_NOT_YET_MAPPED");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      Map<String, List<String>> result = handler.loadDeleteBlockersByAccount(CLIENT_ID, ORGS);

      assertTrue("an unrecognized code must not produce a phantom blocker", result.isEmpty());
    }
  }

  /**
   * Verifies that {@code loadDeleteBlockersByAccount} returns an empty map when no row in scope
   * would block a hard delete — the state of a freshly-created, untouched account.
   *
   * @throws Exception if the mocked JDBC chain fails
   */
  @Test
  public void testLoadDeleteBlockersByAccountReturnsEmptyMapWhenNoRows() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(Array.class));
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      assertTrue(handler.loadDeleteBlockersByAccount(CLIENT_ID, ORGS).isEmpty());
    }
  }

  /**
   * Verifies that all ten {@code UNION ALL} branches of {@code DELETE_BLOCKERS_BY_ACCOUNT_SQL} are
   * bound with (clientId, orgs) — ten {@code setString} + ten {@code setArray} calls reusing a
   * single {@code java.sql.Array} instance. A missed branch would leave a placeholder unset
   * and the driver would throw at execution time.
   *
   * @throws Exception if the mocked JDBC chain fails
   */
  @Test
  public void testLoadDeleteBlockersByAccountBindsAllTenUnionBranches() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    Array orgArray = mock(Array.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(orgArray);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      handler.loadDeleteBlockersByAccount(CLIENT_ID, ORGS);

      verify(ps, times(10)).setString(anyInt(), eq(CLIENT_ID));
      verify(ps, times(10)).setArray(anyInt(), eq(orgArray));
      // One array built and bound ten times, not ten equivalent arrays.
      verify(conn, times(1)).createArrayOf(eq("varchar"), any());
    }
  }

  // ── nullSafeBigDecimal() helper ──────────────────────────────────────────

  /**
   * Verifies that {@code nullSafeBigDecimal} converts a {@code null} input
   * into {@link BigDecimal#ZERO} — the contract relied on by the result-set
   * mapper to keep summaries arithmetic-safe.
   */
  @Test
  public void testNullSafeBigDecimalReturnsZeroOnNullInput() {
    assertEquals(0, BigDecimal.ZERO.compareTo(
        FinancialAccountsPageHandler.nullSafeBigDecimal(null)));
  }

  /**
   * Verifies that {@code nullSafeBigDecimal} returns the input untouched when
   * it is non-null — non-zero balances must flow through without coercion.
   */
  @Test
  public void testNullSafeBigDecimalReturnsValueWhenNotNull() {
    BigDecimal value = new BigDecimal("42.50");
    assertSame(value, FinancialAccountsPageHandler.nullSafeBigDecimal(value));
  }

  // ── Fixtures ─────────────────────────────────────────────────────────────

  /**
   * Builds a minimal {@link AccountRow} fixture for the in-memory tests. The
   * IBAN and {@code isDefault} flag are set to fixed values that are not
   * relevant for the assertions; the meaningful inputs are the id, name, type,
   * balance and currency.
   *
   * @param id
   *     unique identifier of the simulated FIN_Financial_Account
   * @param name
   *     human-readable name shown in the UI
   * @param type
   *     account type code (B = Bank, C = Cash, T = Card)
   * @param balance
   *     current balance to surface in the summary
   * @param currencyIso
   *     ISO 4217 code used for the currency breakdown
   * @return a fixture row ready to be passed to the response builders
   */
  private static AccountRow account(String id, String name, String type, BigDecimal balance,
      String currencyIso) {
    return new AccountRow(id, name, type, balance,
        new Currency(currencyId(currencyIso), currencyIso),
        "ES1200000000000000000001", false);
  }

  /**
   * Builds an archived (inactive) {@link AccountRow} fixture. The constructor
   * signature is unchanged (7 params, always active by default), so the
   * {@code active} flag is flipped after construction exactly as the loader
   * does from column 9 of the result set.
   *
   * @param id
   *     unique identifier of the simulated FIN_Financial_Account
   * @param name
   *     human-readable name shown in the UI
   * @param type
   *     account type code (B = Bank, C = Cash, T = Card)
   * @param balance
   *     current balance (must be excluded from the summary totals)
   * @param currencyIso
   *     ISO 4217 code used for the currency breakdown
   * @return a fixture row flagged as archived/inactive
   */
  private static AccountRow inactiveAccount(String id, String name, String type, BigDecimal balance,
      String currencyIso) {
    AccountRow row = account(id, name, type, balance, currencyIso);
    row.active = false;
    return row;
  }

  /**
   * Resolves a synthetic Etendo {@code C_Currency_ID} for the ISO codes used
   * by the fixtures. Returns "0" for unknown codes since the value is not
   * inspected by the assertions.
   *
   * @param iso
   *     ISO 4217 currency code
   * @return the synthetic currency id matching the test fixtures
   */
  private static String currencyId(String iso) {
    switch (iso) {
      case "EUR":
        return "102";
      case "USD":
        return "100";
      default:
        return "0";
    }
  }
}