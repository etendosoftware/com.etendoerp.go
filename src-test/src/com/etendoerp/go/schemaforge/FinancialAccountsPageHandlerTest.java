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
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
import java.util.LinkedHashMap;
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
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.FinancialAccountsPageHandler.AccountRow;
import com.etendoerp.go.schemaforge.FinancialAccountsPageHandler.Currency;

/**
 * Mockito-driven unit tests for {@link FinancialAccountsPageHandler}.
 *
 * <p>Strategy: spy the handler and stub the {@code loadAccounts} /
 * {@code loadPendingByAccount} seams so the response builder runs over
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
   * {@code loadPendingByAccount}) without touching the real implementation.
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
   * {@code pendingCount} is propagated from the loader map, and that both
   * loader seams are invoked exactly once with the client/orgs filter.
   *
   * @throws Exception
   *     if the stubbed loaders or JSON envelope inspection fails
   */
  @Test
  public void testBuildPayloadAssemblesEnvelopeAndDelegatesToLoaders() throws Exception {
    List<AccountRow> accounts = Arrays.asList(
        account("acc-1", "BBVA", "B", new BigDecimal("1500.00"), "EUR"));
    Map<String, Integer> pending = new LinkedHashMap<>();
    pending.put("acc-1", 4);
    Set<String> withTransactions = Collections.singleton("acc-1");

    doReturn(accounts).when(handler).loadAccounts(eq(CLIENT_ID), eq(ORGS));
    doReturn(pending).when(handler).loadPendingByAccount(eq(CLIENT_ID), eq(ORGS));
    doReturn(withTransactions).when(handler).loadAccountsWithTransactions(eq(CLIENT_ID), eq(ORGS));

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

    verify(handler).loadAccounts(CLIENT_ID, ORGS);
    verify(handler).loadPendingByAccount(CLIENT_ID, ORGS);
    verify(handler).loadAccountsWithTransactions(CLIENT_ID, ORGS);
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
    doReturn(Collections.emptyMap()).when(handler).loadPendingByAccount(eq(CLIENT_ID), eq(ORGS));
    doReturn(Collections.emptySet()).when(handler)
        .loadAccountsWithTransactions(eq(CLIENT_ID), eq(ORGS));

    NeoResponse response = handler.buildPayload(CLIENT_ID, ORGS);

    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertFalse("account with no registered transactions serialises hasTransactions=false",
        data.getJSONArray("accounts").getJSONObject(0).getBoolean("hasTransactions"));
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
    JSONObject summary = handler.buildSummary(Collections.emptyList(), Collections.emptyMap());

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

    JSONObject summary = handler.buildSummary(accounts, Collections.emptyMap());

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
   * Accounts that appear in the map with a zero count are ignored.
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

    Map<String, Integer> pendingByAccount = new HashMap<>();
    pendingByAccount.put("acc-1", 12);
    pendingByAccount.put("acc-2", 0);
    pendingByAccount.put("acc-3", 1);

    JSONObject summary = handler.buildSummary(accounts, pendingByAccount);
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

    JSONObject summary = handler.buildSummary(accounts, Collections.emptyMap());
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

    JSONArray arr = handler.buildAccountsArray(accounts, Collections.emptyMap(),
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
    Map<String, Integer> pendingByAccount = new HashMap<>();
    pendingByAccount.put("acc-1", 7);

    JSONArray arr = handler.buildAccountsArray(accounts, pendingByAccount, Collections.emptySet());
    assertEquals(7, arr.getJSONObject(0).getInt("pendingCount"));
  }

  /**
   * Verifies that {@code buildAccountsArray()} serialises the PSD2 masked card
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

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(card), Collections.emptyMap(),
        Collections.emptySet());
    JSONObject row = arr.getJSONObject(0);
    assertEquals("CA", row.getString("type"));
    assertEquals("**** **** **** 1234", row.getString("maskedPan"));
  }

  /**
   * Verifies that {@code buildAccountsArray()} emits the {@code psd2Connected} flag for each row:
   * an account with an active PSD2 connection serialises {@code true}, one without serialises
   * {@code false}. The UI uses this to show the "Conectado" badge on the account card.
   *
   * @throws Exception
   *     if the JSON traversal fails
   */
  @Test
  public void testBuildAccountsArrayEmitsPsd2ConnectedFlag() throws Exception {
    AccountRow connected = account("acc-1", "BBVA PSD2", "B", new BigDecimal("100.00"), "EUR");
    connected.psd2Connected = true;
    AccountRow offline = account("acc-2", "Caja manual", "B", new BigDecimal("0.00"), "EUR");

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(connected, offline),
        Collections.emptyMap(), Collections.emptySet());

    assertEquals(2, arr.length());
    assertTrue("connected account serialises psd2Connected=true",
        arr.getJSONObject(0).getBoolean("psd2Connected"));
    assertFalse("offline account serialises psd2Connected=false",
        arr.getJSONObject(1).getBoolean("psd2Connected"));
  }

  /**
   * Verifies that {@code loadAccounts()} maps column 11 ({@code em_psd2_connection_status}) to the
   * {@code psd2Connected} flag: {@code 'CO'} (connected) → true, any other value → false.
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadAccountsMapsPsd2ConnectionStatus() throws Exception {
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
      assertTrue("'CO' maps to psd2Connected=true", rows.get(0).psd2Connected);
      assertFalse("non-'CO' maps to psd2Connected=false", rows.get(1).psd2Connected);
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

    JSONArray arr = handler.buildAccountsArray(accounts, Collections.emptyMap(),
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

    Map<String, Integer> pendingByAccount = new HashMap<>();
    pendingByAccount.put("acc-1", 2);
    // Archived accounts carry pending lines too, but they must not be counted.
    pendingByAccount.put("acc-2", 9);
    pendingByAccount.put("acc-3", 5);

    JSONObject summary = handler.buildSummary(accounts, pendingByAccount);

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

      AccountRow second = rows.get(1);
      assertEquals("acc-2", second.id);
      assertEquals(0, BigDecimal.ZERO.compareTo(second.currentBalance));
      assertFalse("second row is not default", second.isDefault);
      assertFalse("second row maps column 9 'N' to inactive", second.active);

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

  // ── loadPendingByAccount() ───────────────────────────────────────────────

  /**
   * Verifies that {@code loadPendingByAccount} returns a map keyed by
   * financial account id with the pending-line count read from column 2.
   * Multiple rows are aggregated correctly and the SQL bind parameters are
   * the client id and the org array.
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadPendingByAccountReturnsCountsKeyedByAccountId() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    Array orgArray = mock(Array.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(eq("varchar"), any())).thenReturn(orgArray);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString(1)).thenReturn("acc-1", "acc-2");
    when(rs.getInt(2)).thenReturn(12, 3);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      Map<String, Integer> result = handler.loadPendingByAccount(CLIENT_ID, ORGS);

      assertEquals(2, result.size());
      assertEquals(Integer.valueOf(12), result.get("acc-1"));
      assertEquals(Integer.valueOf(3), result.get("acc-2"));
      verify(ps).setString(1, CLIENT_ID);
      verify(ps).setArray(2, orgArray);
    }
  }

  /**
   * Verifies that {@code loadPendingByAccount} returns an empty map when there
   * are no rows to read — accounts with zero pending lines simply do not
   * appear in the response of the SQL query.
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadPendingByAccountReturnsEmptyMapWhenResultSetEmpty() throws Exception {
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

      Map<String, Integer> result = handler.loadPendingByAccount(CLIENT_ID, ORGS);

      assertTrue("expected empty pending map", result.isEmpty());
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

    JSONArray arr = handler.buildAccountsArray(accounts, Collections.emptyMap(),
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

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(acc), Collections.emptyMap(),
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

    JSONArray arr = handler.buildAccountsArray(Arrays.asList(withHistory, withoutHistory),
        Collections.emptyMap(), accountsWithTransactions);

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

    JSONArray arr = handler.buildAccountsArray(accounts, Collections.emptyMap(),
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