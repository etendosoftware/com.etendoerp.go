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
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_ACTION;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.getContext;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.singleParam;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubProviderLookup;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

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
}
