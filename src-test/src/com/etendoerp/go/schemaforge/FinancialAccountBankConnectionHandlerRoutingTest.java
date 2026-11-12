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

import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_ACCOUNT_IDS;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_ACTION;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.account;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.getContext;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.postContext;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.singleParam;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubProviderLookup;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubSyncLock;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.syncStatusBody;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.AccountSyncLock;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;

/**
 * Unit tests for {@link FinancialAccountBankConnectionHandler} request routing and the GET {@code status}
 * action.
 *
 * <p>The handler enters admin mode through the static {@link OBContext} (not a seam), so every
 * {@code handle()} call is wrapped in a {@code mockStatic(OBContext.class)} to keep the CI thread
 * free of a live session. The {@code loadAccount} DAL seam and {@code doRollbackAndClose} are
 * stubbed on a Mockito spy so no database is touched.
 *
 * <p>Scenarios: GET/POST dispatch and the unknown-action / wrong-method branches; status mapping
 * for connected and disconnected accounts; account-not-found → 404; the ETP-5181
 * {@code maxFetchInterval} advisory field; the OBException → 400 and the generic Exception → 500
 * translations (both rollback).
 *
 * <p>{@code syncStatus} (ETP-6154), the other status read, as POST {@code {financialAccountIds: []}}
 * or GET {@code ?financialAccountIds=a,b} with the same answer: missing body, array or parameter →
 * 400; more than {@code MAX_SYNC_STATUS_IDS} distinct ids → 400; blank and repeated ids are
 * dropped before any account is read, and ids the caller cannot read (foreign, unknown) are
 * omitted;
 * {@code inProgress} comes from the account sync lock probe; {@code lastResult} is the newer of the
 * latest connection's last success and last failure.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountBankConnectionHandler
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountBankConnectionHandlerRoutingTest {

  private static final String ACTION_STATUS = "status";
  private static final String KEY_MAX_FETCH_INTERVAL = "maxFetchInterval";
  private static final String PROVIDER_CODE = "bbva";

  private FinancialAccountBankConnectionHandler handler;

  @Before
  public void setUp() {
    handler = spy(new FinancialAccountBankConnectionHandler());
    doNothing().when(handler).doRollbackAndClose();
  }

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  /** A non-GET, non-POST method is rejected with a 405. */
  @Test
  public void testUnsupportedMethodReturns405() {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("DELETE");
    when(context.getQueryParams()).thenReturn(singleParam(PARAM_ACTION, ACTION_STATUS));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(405, handler.handle(context).getHttpStatus());
    }
  }

  /** A GET without a recognised action is rejected with a 400. */
  @Test
  public void testGetUnknownActionReturns400() {
    NeoContext context = getContext(singleParam(PARAM_ACTION, "nope"));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(400, handler.handle(context).getHttpStatus());
    }
  }

  /** A POST without a recognised action is rejected with a 400. */
  @Test
  public void testPostUnknownActionReturns400() {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("POST");
    when(context.getQueryParams()).thenReturn(singleParam(PARAM_ACTION, "nope"));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(400, handler.handle(context).getHttpStatus());
    }
  }

  /** A null query-param map (so a null action) on GET still yields a 400, not an NPE. */
  @Test
  public void testGetNullParamsReturns400() {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("GET");
    when(context.getQueryParams()).thenReturn(null);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(400, handler.handle(context).getHttpStatus());
    }
  }

  /** GET status on a missing account resolves to a 404. */
  @Test
  public void testStatusAccountNotFoundReturns404() {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_STATUS);
    params.put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    doReturn(null).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(404, handler.handle(getContext(params)).getHttpStatus());
    }
  }

  /**
   * GET status on a connected account (bank connection status {@code CO}) maps {@code connected}
   * to true and exposes the connection's provider/scopes when a {@link FinaccConnection} exists.
   */
  @Test
  public void testStatusConnectedAccountReturnsConnectedTrue() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_STATUS);
    params.put(PARAM_ACCOUNT_ID, ACCOUNT_ID);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getPSD2ConnectionStatus()).thenReturn("CO");
    when(finAcc.getPSD2SaltEdgeAccountID()).thenReturn("SE-1");
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getProviderName()).thenReturn("Banco Santander");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
          .thenReturn(connection);

      NeoResponse response = handler.handle(getContext(params));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertTrue(data.getBoolean("connected"));
      assertEquals("Banco Santander", data.getString("providerName"));
    }
  }

  /**
   * GET status on a disconnected account (no bank connection status, no {@link FinaccConnection})
   * maps {@code connected} to false and omits the connection-only fields.
   */
  @Test
  public void testStatusDisconnectedAccountReturnsConnectedFalse() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_STATUS);
    params.put(PARAM_ACCOUNT_ID, ACCOUNT_ID);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getPSD2ConnectionStatus()).thenReturn(null);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
          .thenReturn(null);

      NeoResponse response = handler.handle(getContext(params));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertFalse(data.getBoolean("connected"));
      assertFalse("connection-only field omitted when no connection", data.has("providerName"));
      assertFalse("never linked → not reconnectable", data.getBoolean("reconnectable"));
    }
  }

  /**
   * GET status on a soft-disconnected account (ETP-4764): the connection was deactivated rather
   * than deleted, so the account is no longer {@code connected} but keeps its Salt Edge link and
   * must be reported as {@code reconnectable}, which is what lets the SPA offer "Reconectar"
   * instead of a from-scratch connect flow.
   */
  @Test
  public void testStatusSoftDisconnectedAccountReturnsReconnectableTrue() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_STATUS);
    params.put(PARAM_ACCOUNT_ID, ACCOUNT_ID);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getPSD2ConnectionStatus()).thenReturn("CD");
    when(finAcc.getPSD2SaltEdgeAccountID()).thenReturn("SE-1");
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      // The soft path leaves the connection inactive, so the "active connection" lookup misses it.
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
          .thenReturn(null);

      NeoResponse response = handler.handle(getContext(params));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertFalse(data.getBoolean("connected"));
      assertTrue(data.getBoolean("reconnectable"));
    }
  }

  // ── ETP-5089: a connection that is still "active" but has stopped refreshing ─────────────

  /**
   * GET status on a connected account whose active connection is stale reports {@code stale=true}
   * together with the bank's own failure message, so the SPA can say why. The account stays
   * {@code connected} and is NOT {@code reconnectable}: that flag means "deactivated, can be
   * revived" and the SPA uses it to block transfers, which a still-linked account must not get.
   */
  @Test
  public void testStatusStaleConnectionReportsStaleWithMessageAndStaysConnected() throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();
    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getLastFailMessage()).thenReturn("Error interno del banco.");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
          .thenReturn(connection);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.isConnectionStale(connection))
          .thenReturn(true);

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertTrue(data.getBoolean("connected"));
      assertTrue(data.getBoolean("stale"));
      assertEquals("Error interno del banco.", data.getString("staleMessage"));
      assertFalse("a stale connection is not a deactivated one", data.getBoolean("reconnectable"));
    }
  }

  /** A healthy connection reports {@code stale=false} and carries no stale message. */
  @Test
  public void testStatusHealthyConnectionReportsNotStaleAndOmitsMessage() throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();
    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getLastFailMessage()).thenReturn("an old failure");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
          .thenReturn(connection);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.isConnectionStale(connection))
          .thenReturn(false);

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertFalse(data.getBoolean("stale"));
      assertFalse("the failure text only travels while the connection is stale",
          data.has("staleMessage"));
    }
  }

  /** A stale connection with no failure text still reports {@code stale=true}, without the message. */
  @Test
  public void testStatusStaleConnectionWithoutMessageOmitsIt() throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();
    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getLastFailMessage()).thenReturn(null);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
          .thenReturn(connection);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.isConnectionStale(connection))
          .thenReturn(true);

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertTrue(data.getBoolean("stale"));
      assertFalse(data.has("staleMessage"));
    }
  }

  /** With no active connection there is nothing to be stale: the field is simply absent. */
  @Test
  public void testStatusWithoutActiveConnectionOmitsStale() throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
          .thenReturn(null);

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertFalse(data.has("stale"));
    }
  }

  // ── ETP-5181: the provider's max fetch interval on GET status ─────────────

  /**
   * The SPA's "Importar desde" advisory needs the provider's published limit, so {@code GET
   * status} exposes it as {@code maxFetchInterval}.
   *
   * <p>Asserted with {@code getInt}, not {@code getDouble}/{@code getString}: the column is a
   * {@code DECIMAL(10,0)} and the value must cross the wire as a plain integer ({@code 90}, never
   * {@code 90.0}) — the frontend compares it with {@code > 0} and feeds it into a day count, and a
   * BigDecimal leaking through would render "90.0 días".
   */
  @Test
  public void testStatusExposesProviderMaxFetchInterval() throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();
    FinaccConnection connection = transactionalConnection();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubActiveConnection(linkHelper, finAcc, connection);
      stubProviderLookup(obDal, provider(BigDecimal.valueOf(90)));

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertTrue("the advisory needs the limit on GET status", data.has(KEY_MAX_FETCH_INTERVAL));
      assertEquals(90, data.getInt(KEY_MAX_FETCH_INTERVAL));
      // getInt() would happily unwrap a BigDecimal, so the stored type is asserted directly:
      // the value has to serialize as `90`, never `90.0`, or the SPA renders "90.0 días".
      assertTrue("maxFetchInterval must serialize as a plain int",
          data.get(KEY_MAX_FETCH_INTERVAL) instanceof Integer);
    }
  }

  /**
   * With no active connection the account cannot synchronize at all — {@code
   * fetchAccountTransactions} fails with {@code PSD2_NoActiveConnectionForAccount} long before any
   * interval check — so there is nothing to advise about and the key must be absent.
   *
   * <p>This pins the deliberate placement INSIDE the {@code connection != null} block. It is also
   * why the key is omitted rather than set to JSON null: the SPA tests
   * {@code status.maxFetchInterval === undefined} and simply advises nothing.
   */
  @Test
  public void testStatusOmitsMaxFetchIntervalWhenNoActiveConnection() throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubActiveConnection(linkHelper, finAcc, null);

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertFalse("no connection ⇒ nothing to advise about",
          data.has(KEY_MAX_FETCH_INTERVAL));
      // The provider catalog is not queried either — the guard runs before any DAL work.
      obDal.verifyNoInteractions();
    }
  }

  /**
   * A connection that does not handle transactions never imports movements, so its import range
   * is irrelevant and the key is omitted. The provider lookup must not even run — the flag is
   * checked first precisely so {@code GET status} does not pay for a criteria query per account.
   */
  @Test
  public void testStatusOmitsMaxFetchIntervalWhenConnectionDoesNotHandleTransactions()
      throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();
    FinaccConnection connection = transactionalConnection();
    when(connection.isHandlesTransactions()).thenReturn(Boolean.FALSE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubActiveConnection(linkHelper, finAcc, connection);

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertFalse(data.has(KEY_MAX_FETCH_INTERVAL));
      obDal.verifyNoInteractions();
    }
  }

  /**
   * The provider record exists but published no interval. The key must be absent rather than
   * defaulted to 90 — the SPA would otherwise advise against dates the bank may well serve.
   */
  @Test
  public void testStatusOmitsMaxFetchIntervalWhenLimitNull() throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();
    FinaccConnection connection = transactionalConnection();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubActiveConnection(linkHelper, finAcc, connection);
      stubProviderLookup(obDal, provider(null));

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertFalse("an unpublished limit must never be defaulted",
          data.has(KEY_MAX_FETCH_INTERVAL));
    }
  }

  /** Zero days is not a real limit: advising against it would flag every date in the past. */
  @Test
  public void testStatusOmitsMaxFetchIntervalWhenLimitNotPositive() throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();
    FinaccConnection connection = transactionalConnection();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubActiveConnection(linkHelper, finAcc, connection);
      stubProviderLookup(obDal, provider(BigDecimal.ZERO));

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertFalse(data.has(KEY_MAX_FETCH_INTERVAL));
    }
  }

  /** A business error (OBException) is translated to a 400 and rolls back. */
  @Test
  public void testOBExceptionTranslatesTo400() {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_STATUS);
    params.put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    doThrowOnLoad(new OBException("bad data"));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(400, handler.handle(getContext(params)).getHttpStatus());
      verify(handler).doRollbackAndClose();
    }
  }

  /** An unexpected runtime failure is translated to a 500 and rolls back. */
  @Test
  public void testGenericExceptionTranslatesTo500() {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_STATUS);
    params.put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    doThrowOnLoad(new RuntimeException("boom"));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(500, handler.handle(getContext(params)).getHttpStatus());
      verify(handler).doRollbackAndClose();
    }
  }

  // ── ETP-5582: lastSyncDate on GET status ──────────────────────────────────

  /** GET status exposes the account's last successful sync as an ISO instant. */
  @Test
  public void testStatusReturnsLastSyncDateWhenSynced() throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();
    when(finAcc.getPSD2LastSyncDate())
        .thenReturn(java.util.Date.from(java.time.Instant.parse("2026-10-05T10:30:00Z")));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubActiveConnection(linkHelper, finAcc, null);

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertEquals("2026-10-05T10:30:00Z", data.getString("lastSyncDate"));
    }
  }

  /** A never-synced account yields an explicit JSON null (key kept), not a missing key. */
  @Test
  public void testStatusReturnsNullLastSyncDateWhenNeverSynced() throws Exception {
    FIN_FinancialAccount finAcc = connectedAccount();
    when(finAcc.getPSD2LastSyncDate()).thenReturn(null);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubActiveConnection(linkHelper, finAcc, null);

      JSONObject data = dataOf(handler.handle(getContext(statusParams())));

      assertTrue(data.has("lastSyncDate"));
      assertTrue(data.isNull("lastSyncDate"));
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void doThrowOnLoad(RuntimeException toThrow) {
    Mockito.doThrow(toThrow).when(handler).loadAccount(ACCOUNT_ID);
  }

  /** The GET query params for {@code status} on {@link BankConnectionHandlerTestSupport#ACCOUNT_ID}. */
  private static Map<String, String> statusParams() {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_STATUS);
    params.put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    return params;
  }

  /** A live (status {@code CO}) bank-linked account, already wired into the loadAccount seam. */
  private FIN_FinancialAccount connectedAccount() {
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getPSD2ConnectionStatus()).thenReturn("CO");
    when(finAcc.getPSD2SaltEdgeAccountID()).thenReturn("SE-1");
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);
    return finAcc;
  }

  /** A connection that DOES handle transactions and carries a provider code. */
  private static FinaccConnection transactionalConnection() {
    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.isHandlesTransactions()).thenReturn(Boolean.TRUE);
    when(connection.getProviderCode()).thenReturn(PROVIDER_CODE);
    return connection;
  }

  private static Provider provider(BigDecimal maxFetchInterval) {
    Provider provider = mock(Provider.class);
    when(provider.getMaxFetchInterval()).thenReturn(maxFetchInterval);
    return provider;
  }

  private static void stubActiveConnection(MockedStatic<SaltEdgeAccountLinkHelper> linkHelper,
      FIN_FinancialAccount finAcc, FinaccConnection connection) {
    linkHelper.when(() -> SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc))
        .thenReturn(connection);
  }

  private static JSONObject dataOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  // ── POST syncStatus (ETP-6154) ─────────────────────────────────────────────

  private static final String ACTION_SYNC_STATUS = "syncStatus";
  private static final String OTHER_ACCOUNT_ID = "FA-002";
  private static final String FOREIGN_ACCOUNT_ID = "FA-FOREIGN";
  private static final Instant EARLIER = Instant.parse("2026-11-01T08:00:00Z");
  private static final Instant LATER = Instant.parse("2026-11-02T09:30:00Z");

  /** syncStatus without a body is a 400 (business error), and the transaction is rolled back. */
  @Test
  public void testSyncStatusWithoutBodyReturns400() throws Exception {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountSyncLock> locks = mockStatic(AccountSyncLock.class)) {
      NeoResponse response = handler.handle(postContext(ACTION_SYNC_STATUS, null));

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().toString().contains("Missing request body"));
      verify(handler).doRollbackAndClose();
      locks.verifyNoInteractions();
    }
  }

  /** syncStatus without the ids array (or with a non-array value) is a 400 naming the key. */
  @Test
  public void testSyncStatusWithoutIdsReturns400() throws Exception {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountSyncLock> locks = mockStatic(AccountSyncLock.class)) {
      NeoResponse missing = handler.handle(postContext(ACTION_SYNC_STATUS, new JSONObject()));
      NeoResponse notArray = handler.handle(postContext(ACTION_SYNC_STATUS,
          new JSONObject().put(PARAM_ACCOUNT_IDS, ACCOUNT_ID)));

      assertEquals(400, missing.getHttpStatus());
      assertTrue(missing.getBody().toString().contains(PARAM_ACCOUNT_IDS));
      assertEquals(400, notArray.getHttpStatus());
      verify(handler, never()).loadAccount(anyString());
      locks.verifyNoInteractions();
    }
  }

  /**
   * An id the caller cannot read (foreign or unknown) and a blank id are simply omitted, never an
   * error (no existence oracle); a repeated id is reported once and probed once.
   */
  @Test
  public void testSyncStatusOmitsUnreadableIdsAndCollapsesRepeatedOnes() throws Exception {
    FIN_FinancialAccount finAcc = account(ACCOUNT_ID);
    doReturn(null).when(handler).loadAccount(anyString());
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountSyncLock> locks = mockStatic(AccountSyncLock.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      AccountSyncLock lock = stubSyncLock(locks);

      NeoResponse response = handler.handle(postContext(ACTION_SYNC_STATUS,
          syncStatusBody(ACCOUNT_ID, FOREIGN_ACCOUNT_ID, ACCOUNT_ID, "  ")));

      assertEquals(200, response.getHttpStatus());
      JSONObject accounts = dataOf(response).getJSONObject("accounts");
      assertEquals(1, accounts.length());
      assertTrue(accounts.has(ACCOUNT_ID));
      assertFalse(accounts.has(FOREIGN_ACCOUNT_ID));
      verify(lock).heldAmong(List.of(ACCOUNT_ID));
      verify(handler, times(1)).loadAccount(ACCOUNT_ID);
      verify(handler, times(1)).loadAccount(FOREIGN_ACCOUNT_ID);
      verify(handler, never()).loadAccount(isNull());
    }
  }

  /**
   * The ids are read from the POST body array or the GET comma-separated parameter, trimmed, with
   * blanks dropped and repeats collapsed in first-seen order; absent → null (the caller's 400).
   */
  @Test
  public void testSyncStatusIdsAreTrimmedDeduplicatedAndOrdered() throws Exception {
    NeoContext post = postContext(ACTION_SYNC_STATUS,
        syncStatusBody(" " + OTHER_ACCOUNT_ID, ACCOUNT_ID, "", OTHER_ACCOUNT_ID, "  "));
    NeoContext get = getContext(syncStatusQuery(ACCOUNT_ID + ", ," + OTHER_ACCOUNT_ID + ","
        + ACCOUNT_ID));
    NeoContext postWithoutArray = postContext(ACTION_SYNC_STATUS,
        new JSONObject());
    NeoContext getWithoutParam = getContext(singleParam(PARAM_ACTION, ACTION_SYNC_STATUS));

    assertEquals(List.of(OTHER_ACCOUNT_ID, ACCOUNT_ID),
        List.copyOf(FinancialAccountBankConnectionHandler.syncStatusIds(post)));
    assertEquals(List.of(ACCOUNT_ID, OTHER_ACCOUNT_ID),
        List.copyOf(FinancialAccountBankConnectionHandler.syncStatusIds(get)));
    assertEquals(Set.of(), FinancialAccountBankConnectionHandler.syncStatusIds(
        getContext(syncStatusQuery(" , "))));
    assertNull(FinancialAccountBankConnectionHandler.syncStatusIds(postWithoutArray));
    assertNull(FinancialAccountBankConnectionHandler.syncStatusIds(getWithoutParam));
  }

  /**
   * Over {@code MAX_SYNC_STATUS_IDS} distinct ids is a 400 before any account is read or probed;
   * exactly the maximum is served. Repeats do not count towards the cap.
   */
  @Test
  public void testSyncStatusCapsTheDistinctIds() throws Exception {
    int max = FinancialAccountBankConnectionHandler.MAX_SYNC_STATUS_IDS;
    assertEquals(200, max);
    doReturn(null).when(handler).loadAccount(anyString());

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountSyncLock> locks = mockStatic(AccountSyncLock.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubSyncLock(locks);

      NeoResponse tooMany = handler.handle(postContext(
          ACTION_SYNC_STATUS, syncStatusBody(distinctIds(max + 1))));
      assertEquals(400, tooMany.getHttpStatus());
      assertTrue(tooMany.getBody().toString().contains(
          FinancialAccountBankConnectionHandler.MSG_TOO_MANY_SYNC_STATUS_IDS));
      verify(handler, never()).loadAccount(anyString());
      locks.verify(AccountSyncLock::getInstance, never());

      String[] maxWithRepeats = new String[max + 5];
      System.arraycopy(distinctIds(max), 0, maxWithRepeats, 0, max);
      for (int i = max; i < maxWithRepeats.length; i++) {
        maxWithRepeats[i] = "ID-0";
      }
      NeoResponse atMax = handler.handle(postContext(
          ACTION_SYNC_STATUS, syncStatusBody(maxWithRepeats)));
      assertEquals(200, atMax.getHttpStatus());
      verify(handler, times(max)).loadAccount(anyString());
    }
  }

  /**
   * {@code GET ?action=syncStatus&financialAccountIds=a,b} answers exactly what the POST form
   * answers for the same ids.
   */
  @Test
  public void testSyncStatusGetFormAnswersLikeThePostForm() throws Exception {
    FIN_FinancialAccount running = account(ACCOUNT_ID);
    FIN_FinancialAccount idle = account(OTHER_ACCOUNT_ID);
    when(running.getPSD2LastSyncDate()).thenReturn(Date.from(EARLIER));
    doReturn(running).when(handler).loadAccount(ACCOUNT_ID);
    doReturn(idle).when(handler).loadAccount(OTHER_ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountSyncLock> locks = mockStatic(AccountSyncLock.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubSyncLock(locks, ACCOUNT_ID);

      NeoResponse viaGet = handler.handle(
          getContext(syncStatusQuery(ACCOUNT_ID + "," + OTHER_ACCOUNT_ID)));
      NeoResponse viaPost = handler.handle(postContext(
          ACTION_SYNC_STATUS, syncStatusBody(ACCOUNT_ID, OTHER_ACCOUNT_ID)));

      assertEquals(200, viaGet.getHttpStatus());
      JSONObject getAccounts = dataOf(viaGet).getJSONObject("accounts");
      JSONObject postAccounts = dataOf(viaPost).getJSONObject("accounts");
      assertTrue(getAccounts.getJSONObject(ACCOUNT_ID).getBoolean("inProgress"));
      assertFalse(getAccounts.getJSONObject(OTHER_ACCOUNT_ID).getBoolean("inProgress"));
      assertEquals(postAccounts.toString(), getAccounts.toString());
    }
  }

  /** GET syncStatus without the ids parameter is a 400 naming it, and reads no account. */
  @Test
  public void testSyncStatusGetWithoutIdsReturns400() throws Exception {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountSyncLock> locks = mockStatic(AccountSyncLock.class)) {
      NeoResponse response = handler.handle(getContext(singleParam(PARAM_ACTION,
          ACTION_SYNC_STATUS)));

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().toString().contains(PARAM_ACCOUNT_IDS));
      verify(handler, never()).loadAccount(anyString());
      locks.verifyNoInteractions();
    }
  }

  /**
   * GET query params of a {@code syncStatus} request.
   *
   * @param ids the raw comma-separated {@code financialAccountIds} value
   * @return the query param map
   */
  private static Map<String, String> syncStatusQuery(String ids) {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_SYNC_STATUS);
    params.put(PARAM_ACCOUNT_IDS, ids);
    return params;
  }

  /**
   * {@code count} distinct ids, {@code ID-0} to {@code ID-<count-1>}.
   *
   * @param count how many
   * @return the ids
   */
  private static String[] distinctIds(int count) {
    String[] ids = new String[count];
    for (int i = 0; i < count; i++) {
      ids[i] = "ID-" + i;
    }
    return ids;
  }

  /** inProgress is true exactly for the accounts whose sync lock the probe reports as held. */
  @Test
  public void testSyncStatusReportsInProgressFromTheSyncLock() throws Exception {
    FIN_FinancialAccount running = account(ACCOUNT_ID);
    FIN_FinancialAccount idle = account(OTHER_ACCOUNT_ID);
    when(running.getPSD2LastSyncDate()).thenReturn(Date.from(EARLIER));
    doReturn(running).when(handler).loadAccount(ACCOUNT_ID);
    doReturn(idle).when(handler).loadAccount(OTHER_ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountSyncLock> locks = mockStatic(AccountSyncLock.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubSyncLock(locks, ACCOUNT_ID);

      JSONObject accounts = dataOf(handler.handle(postContext(ACTION_SYNC_STATUS,
          syncStatusBody(ACCOUNT_ID, OTHER_ACCOUNT_ID)))).getJSONObject("accounts");

      JSONObject runningEntry = accounts.getJSONObject(ACCOUNT_ID);
      JSONObject idleEntry = accounts.getJSONObject(OTHER_ACCOUNT_ID);
      assertTrue(runningEntry.getBoolean("inProgress"));
      assertEquals(EARLIER.toString(), runningEntry.getString("lastSyncDate"));
      assertFalse(idleEntry.getBoolean("inProgress"));
      assertTrue(idleEntry.isNull("lastSyncDate"));
      // No connection at all: lastResult is present with every field null.
      JSONObject lastResult = idleEntry.getJSONObject("lastResult");
      assertTrue(lastResult.isNull("status"));
      assertTrue(lastResult.isNull("message"));
      assertTrue(lastResult.isNull("at"));
    }
  }

  /**
   * lastResult is read from the account's latest bank connection: a failure newer than the last
   * success reports ERROR with the bank's message and when it happened.
   */
  @Test
  public void testSyncStatusReportsTheLatestConnectionFailure() throws Exception {
    FIN_FinancialAccount finAcc = account(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);
    FinaccConnection connection = connectionWith(EARLIER, LATER, "Bank is down");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountSyncLock> locks = mockStatic(AccountSyncLock.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubSyncLock(locks);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getLatestConnectionForFinAcc(finAcc))
          .thenReturn(connection);

      JSONObject lastResult = dataOf(handler.handle(postContext(ACTION_SYNC_STATUS,
          syncStatusBody(ACCOUNT_ID)))).getJSONObject("accounts").getJSONObject(ACCOUNT_ID)
          .getJSONObject("lastResult");

      assertEquals("ERROR", lastResult.getString("status"));
      assertEquals("Bank is down", lastResult.getString("message"));
      assertEquals(LATER.toString(), lastResult.getString("at"));
    }
  }

  /**
   * The lastResult branches: success newer than failure (or tied) is OK with a null message; a
   * failure with no success is ERROR, with a null message when the bank gave a blank one; neither
   * recorded (or no connection) leaves every field null.
   */
  @Test
  public void testLastResultPicksTheNewerOfSuccessAndFailure() throws Exception {
    JSONObject successNewer = FinancialAccountBankConnectionHandler
        .lastResultJson(connectionWith(LATER, EARLIER, "old failure"));
    JSONObject tie = FinancialAccountBankConnectionHandler
        .lastResultJson(connectionWith(LATER, LATER, "same instant"));
    JSONObject onlyFailure = FinancialAccountBankConnectionHandler
        .lastResultJson(connectionWith(null, EARLIER, "  "));
    JSONObject never = FinancialAccountBankConnectionHandler
        .lastResultJson(connectionWith(null, null, null));
    JSONObject noConnection = FinancialAccountBankConnectionHandler.lastResultJson(null);

    assertEquals("OK", successNewer.getString("status"));
    assertTrue(successNewer.isNull("message"));
    assertEquals(LATER.toString(), successNewer.getString("at"));
    assertEquals("OK", tie.getString("status"));
    assertEquals("ERROR", onlyFailure.getString("status"));
    assertTrue(onlyFailure.isNull("message"));
    assertEquals(EARLIER.toString(), onlyFailure.getString("at"));
    for (JSONObject empty : new JSONObject[] {never, noConnection}) {
      assertTrue(empty.isNull("status"));
      assertTrue(empty.isNull("message"));
      assertTrue(empty.isNull("at"));
    }
  }

  /**
   * A bank connection with the given fetch health (ETP-5089 fields).
   *
   * @param successAt   when it last reached the bank successfully, or null
   * @param failAt      when it last failed to, or null
   * @param failMessage the bank's message of that failure, or null
   * @return the connection mock
   */
  private static FinaccConnection connectionWith(Instant successAt, Instant failAt,
      String failMessage) {
    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getLastSuccessAt()).thenReturn(successAt != null ? Date.from(successAt) : null);
    when(connection.getLastFailAt()).thenReturn(failAt != null ? Date.from(failAt) : null);
    when(connection.getLastFailMessage()).thenReturn(failMessage);
    return connection;
  }
}
