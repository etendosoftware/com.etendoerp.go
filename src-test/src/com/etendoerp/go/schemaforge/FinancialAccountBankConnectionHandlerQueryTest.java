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
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.API_KEY;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.CONNECTION_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_ACTION;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_CONNECTION_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_TYPE;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.getContext;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.singleParam;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubObContext;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;

/**
 * Unit tests for the {@link FinancialAccountBankConnectionHandler} GET {@code accounts} and {@code providers}
 * actions (the read/query paths feeding the SPA selection modal and bank picker).
 *
 * <p>The Salt Edge calls are delegated to static helpers, all mocked here. The provider catalog is
 * cached in a static map keyed by client+country, so each provider test uses a distinct country to
 * keep the cache hits/misses isolated across the suite.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountBankConnectionHandlerQueryTest {

  private static final String ACTION_ACCOUNTS = "accounts";
  private static final String ACTION_PROVIDERS = "providers";
  private static final String KEY_NAME = "name";
  private static final String KEY_COUNTRY = "country";
  private static final String KEY_DATA = "data";
  private static final String KEY_CODE = "code";
  private static final String GET = "GET";
  private static final String BANK = "B";
  private static final String SANTANDER = "Banco Santander";
  private static final String BBVA = "BBVA";
  // ETP-5179 — the diagnosis fields of an empty accounts response.
  private static final String KEY_EMPTY_REASON = "emptyReason";
  private static final String KEY_ACCOUNT_CURRENCY = "accountCurrency";
  private static final String REASON_NO_ACCOUNTS = "noAccounts";
  private static final String REASON_TYPE_MISMATCH = "typeMismatch";
  private static final String REASON_ALL_LINKED = "allLinked";
  private static final String REASON_CURRENCY_MISMATCH = "currencyMismatch";
  private static final String USD = "USD";
  private static final String NO_CURRENCY_WITHOUT_MISMATCH =
      "accountCurrency is only reported for a currency mismatch";

  private FinancialAccountBankConnectionHandler handler;

  @Mock
  private FIN_FinancialAccount finAcc;

  @Mock
  private Currency usdCurrency;

  @Before
  public void setUp() {
    handler = spy(new FinancialAccountBankConnectionHandler());
    doNothing().when(handler).doRollbackAndClose();
  }

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  /** accounts without a connection id is rejected with a 400. */
  @Test
  public void testAccountsMissingConnectionIdReturns400() {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_ACCOUNTS);
    params.put(PARAM_TYPE, BANK);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(400, handler.handle(getContext(params)).getHttpStatus());
    }
  }

  /**
   * accounts (no account id, case 2) fetches the bank accounts for a connection, maps each Salt
   * Edge node into a slim row and resolves the provider name when there is something to select.
   */
  @Test
  public void testAccountsMapsNodesAndResolvesProviderName() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_ACCOUNTS);
    params.put(PARAM_CONNECTION_ID, CONNECTION_ID);
    params.put(PARAM_TYPE, BANK);

    JSONArray rawAccounts = new JSONArray().put(new JSONObject()
        .put("id", "SE-ACC-1").put(KEY_NAME, "Cuenta corriente").put("currency_code", "EUR"));
    JSONObject details = new JSONObject().put("provider_name", BBVA).put("provider_code", "bbva");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(rawAccounts);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
          .thenReturn(details);
      // The provider logo fetch is a non-critical extra GET — return empty payload.
      utils.when(() -> BankIntegrationUtils.makeSaltEdgeRequest(eq(GET), any(), anyString(),
          eq(API_KEY))).thenReturn(new JSONObject());
      // No type/currency/unlinked filtering for this case: pass arrays through unchanged.
      passThroughFilters(linkHelper, rawAccounts);

      NeoResponse response = handler.handle(getContext(params));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      JSONArray out = data.getJSONArray(ACTION_ACCOUNTS);
      assertEquals(1, out.length());
      assertEquals("SE-ACC-1", out.getJSONObject(0).getString("saltEdgeAccountId"));
      assertEquals("Cuenta corriente", out.getJSONObject(0).getString(KEY_NAME));
      assertEquals(BBVA, data.getString("providerName"));
    }
  }

  /** accounts with an empty result set omits the provider name (nothing to select). */
  @Test
  public void testAccountsEmptyOmitsProviderName() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_ACCOUNTS);
    params.put(PARAM_CONNECTION_ID, CONNECTION_ID);
    params.put(PARAM_TYPE, BANK);

    JSONArray empty = new JSONArray();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(empty);
      passThroughFilters(linkHelper, empty);

      NeoResponse response = handler.handle(getContext(params));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals(0, data.getJSONArray(ACTION_ACCOUNTS).length());
      assertFalse("no provider resolved for an empty result", data.has("providerName"));
    }
  }

  /** providers with no API key returns an empty catalog (SPA falls back to its static list). */
  @Test
  public void testProvidersNoApiKeyReturnsEmptyCatalog() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_PROVIDERS);
    params.put(KEY_COUNTRY, "FR");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn("");

      NeoResponse response = handler.handle(getContext(params));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals(0, data.getJSONArray(ACTION_PROVIDERS).length());
      assertEquals("FR", data.getString(KEY_COUNTRY));
    }
  }

  /**
   * providers fetches and maps the Salt Edge catalog, then applies the caller's free-text filter
   * so only matching banks are returned.
   */
  @Test
  public void testProvidersFetchesAndAppliesQueryFilter() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_PROVIDERS);
    params.put(KEY_COUNTRY, "PT");
    params.put("q", "santander");

    JSONObject middleware = new JSONObject().put(KEY_DATA, new JSONArray()
        .put(new JSONObject().put(KEY_CODE, "santander").put(KEY_NAME, SANTANDER))
        .put(new JSONObject().put(KEY_CODE, "bbva").put(KEY_NAME, BBVA)));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.makeSaltEdgeRequest(eq(GET), any(), anyString(),
          eq(API_KEY))).thenReturn(middleware);

      NeoResponse response = handler.handle(getContext(params));

      assertEquals(200, response.getHttpStatus());
      JSONArray providers = dataOf(response).getJSONArray(ACTION_PROVIDERS);
      assertEquals(1, providers.length());
      assertEquals(SANTANDER, providers.getJSONObject(0).getString(KEY_NAME));
    }
  }

  /** providers with no country defaults to ES. */
  @Test
  public void testProvidersDefaultsCountryToEs() throws Exception {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn("");

      NeoResponse response = handler.handle(getContext(singleParam(PARAM_ACTION, ACTION_PROVIDERS)));

      assertEquals("ES", dataOf(response).getString(KEY_COUNTRY));
    }
  }

  /**
   * The provider catalog is cached per client+country: a second request for the same country reuses
   * the cached value and does not hit the middleware again.
   */
  @Test
  public void testProvidersAreCachedPerCountry() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_PROVIDERS);
    params.put(KEY_COUNTRY, "IT");

    JSONObject middleware = new JSONObject().put(KEY_DATA, new JSONArray()
        .put(new JSONObject().put(KEY_CODE, "intesa").put(KEY_NAME, "Intesa Sanpaolo")));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.makeSaltEdgeRequest(eq(GET), any(), anyString(),
          eq(API_KEY))).thenReturn(middleware);

      assertTrue(dataOf(handler.handle(getContext(params))).getJSONArray(ACTION_PROVIDERS).length() == 1);
      // Second call: served from cache, middleware invoked only once overall.
      assertTrue(dataOf(handler.handle(getContext(params))).getJSONArray(ACTION_PROVIDERS).length() == 1);

      utils.verify(() -> BankIntegrationUtils.makeSaltEdgeRequest(eq(GET), any(), anyString(),
          eq(API_KEY)), Mockito.times(1));
    }
  }

  // ── ETP-5179: the accounts response must say WHY the list came back empty ──
  //
  // handleAccounts chains three filters over the same variable and answers 200 with an empty list
  // whichever one emptied it, so the SPA could only ever raise a single generic "no compatible
  // accounts" toast: a USD account connected to a EUR-only bank was indistinguishable from a
  // wrong-type or an already-linked one. The payload must now name the FIRST filter that emptied
  // the list, and — only for the currency filter — the ISO code of the account's own currency.

  /** Nothing came back from the bank at all: the reason is the bank, not any of our filters. */
  @Test
  public void testAccountsEmptyFromBankReportsNoAccountsReason() throws Exception {
    JSONArray empty = new JSONArray();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(empty);
      stubFilters(linkHelper, empty, empty, empty);

      NeoResponse response = handler.handle(getContext(accountsParams(null)));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals(0, data.getJSONArray(ACTION_ACCOUNTS).length());
      assertEquals(REASON_NO_ACCOUNTS, data.getString(KEY_EMPTY_REASON));
      assertFalse(NO_CURRENCY_WITHOUT_MISMATCH, data.has(KEY_ACCOUNT_CURRENCY));
    }
  }

  /** The bank returned accounts but none of them matches the Financial Account type. */
  @Test
  public void testAccountsEmptiedByTypeFilterReportsTypeMismatch() throws Exception {
    JSONArray raw = eurNodes();
    JSONArray empty = new JSONArray();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(raw);
      stubFilters(linkHelper, empty, empty, empty);

      NeoResponse response = handler.handle(getContext(accountsParams(null)));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals(0, data.getJSONArray(ACTION_ACCOUNTS).length());
      assertEquals(REASON_TYPE_MISMATCH, data.getString(KEY_EMPTY_REASON));
      assertFalse(NO_CURRENCY_WITHOUT_MISMATCH, data.has(KEY_ACCOUNT_CURRENCY));
    }
  }

  /** Every account of the right type is already linked to another Financial Account. */
  @Test
  public void testAccountsEmptiedByLinkedFilterReportsAllLinked() throws Exception {
    JSONArray raw = eurNodes();
    JSONArray empty = new JSONArray();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(raw);
      stubFilters(linkHelper, raw, empty, empty);

      NeoResponse response = handler.handle(getContext(accountsParams(null)));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals(0, data.getJSONArray(ACTION_ACCOUNTS).length());
      assertEquals(REASON_ALL_LINKED, data.getString(KEY_EMPTY_REASON));
      assertFalse(NO_CURRENCY_WITHOUT_MISMATCH, data.has(KEY_ACCOUNT_CURRENCY));
    }
  }

  /**
   * The reported bug: a USD Financial Account connected to a bank that only exposes EUR accounts.
   * The currency filter is what empties the list, so the response must say so AND carry the ISO
   * code of the account's currency, which is the only piece of information the toast needs to name
   * the cause ("no USD accounts were found at this bank").
   */
  @Test
  public void testAccountsEmptiedByCurrencyFilterReportsCurrencyMismatchWithAccountCurrency()
      throws Exception {
    JSONArray raw = eurNodes();
    JSONArray empty = new JSONArray();
    stubUsdAccount();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(raw);
      stubFilters(linkHelper, raw, raw, empty);

      NeoResponse response = handler.handle(getContext(accountsParams(ACCOUNT_ID)));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals(0, data.getJSONArray(ACTION_ACCOUNTS).length());
      assertEquals(REASON_CURRENCY_MISMATCH, data.getString(KEY_EMPTY_REASON));
      assertEquals(USD, data.getString(KEY_ACCOUNT_CURRENCY));
    }
  }

  /**
   * The cascade is ordered: when several filters would have emptied the list, the FIRST one wins.
   * A wrong-type bank account is not a currency problem, so no currency is reported even though
   * the Financial Account has one and the currency filter would also have returned nothing.
   */
  @Test
  public void testAccountsCascadeReportsTheFirstEmptyingFilter() throws Exception {
    JSONArray raw = eurNodes();
    JSONArray empty = new JSONArray();
    stubUsdAccount();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(raw);
      stubFilters(linkHelper, empty, empty, empty);

      NeoResponse response = handler.handle(getContext(accountsParams(ACCOUNT_ID)));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals(REASON_TYPE_MISMATCH, data.getString(KEY_EMPTY_REASON));
      assertFalse(NO_CURRENCY_WITHOUT_MISMATCH, data.has(KEY_ACCOUNT_CURRENCY));
    }
  }

  /** A non-empty result carries neither diagnosis field: there is nothing to explain. */
  @Test
  public void testAccountsNonEmptyOmitsEmptyReasonAndAccountCurrency() throws Exception {
    JSONArray raw = eurNodes();
    JSONObject details = new JSONObject().put("provider_name", BBVA).put("provider_code", "bbva");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(raw);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
          .thenReturn(details);
      utils.when(() -> BankIntegrationUtils.makeSaltEdgeRequest(eq(GET), any(), anyString(),
          eq(API_KEY))).thenReturn(new JSONObject());
      stubFilters(linkHelper, raw, raw, raw);

      NeoResponse response = handler.handle(getContext(accountsParams(null)));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals(1, data.getJSONArray(ACTION_ACCOUNTS).length());
      assertEquals(BBVA, data.getString("providerName"));
      assertFalse("a non-empty result has no reason to explain", data.has(KEY_EMPTY_REASON));
      assertFalse(NO_CURRENCY_WITHOUT_MISMATCH, data.has(KEY_ACCOUNT_CURRENCY));
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Query params for the {@code accounts} action, optionally scoped to a Financial Account. */
  private static Map<String, String> accountsParams(String accountId) {
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACTION, ACTION_ACCOUNTS);
    params.put(PARAM_CONNECTION_ID, CONNECTION_ID);
    params.put(PARAM_TYPE, BANK);
    if (accountId != null) {
      params.put(PARAM_ACCOUNT_ID, accountId);
    }
    return params;
  }

  /** One EUR bank account as Salt Edge returns it. */
  private static JSONArray eurNodes() throws Exception {
    return new JSONArray().put(new JSONObject()
        .put("id", "SE-ACC-1").put(KEY_NAME, "Cuenta corriente").put("currency_code", "EUR"));
  }

  /** Makes {@link #ACCOUNT_ID} resolve to a USD bank account owned by the current tenant. */
  private void stubUsdAccount() {
    when(usdCurrency.getISOCode()).thenReturn(USD);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    when(finAcc.getType()).thenReturn(BANK);
    when(finAcc.getCurrency()).thenReturn(usdCurrency);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);
  }

  /**
   * Stubs each of the three account filters with its OWN result, so a test can pinpoint which one
   * empties the list. {@link #passThroughFilters} cannot express that: it hands the same array back
   * from every filter.
   */
  private static void stubFilters(MockedStatic<SaltEdgeAccountLinkHelper> linkHelper,
      JSONArray afterTypeFilter, JSONArray afterLinkedFilter, JSONArray afterCurrencyFilter) {
    linkHelper.when(() -> SaltEdgeAccountLinkHelper.filterAccountsByFAType(any(), any()))
        .thenReturn(afterTypeFilter);
    linkHelper.when(() -> SaltEdgeAccountLinkHelper.filterUnlinkedAccounts(any(), any()))
        .thenReturn(afterLinkedFilter);
    linkHelper.when(() -> SaltEdgeAccountLinkHelper.filterAccountsByCurrency(any(), any()))
        .thenReturn(afterCurrencyFilter);
  }

  /** Stubs the three account filters to return the input array unchanged (case-2 path). */
  private static void passThroughFilters(MockedStatic<SaltEdgeAccountLinkHelper> linkHelper,
      JSONArray accounts) {
    linkHelper.when(() -> SaltEdgeAccountLinkHelper.filterAccountsByFAType(any(), anyString()))
        .thenReturn(accounts);
    linkHelper.when(() -> SaltEdgeAccountLinkHelper.filterUnlinkedAccounts(any(), any()))
        .thenReturn(accounts);
  }

  private static JSONObject dataOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }
}
