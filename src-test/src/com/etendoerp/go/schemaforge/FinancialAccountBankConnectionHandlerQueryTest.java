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
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubProviderLookup;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;

/**
 * Unit tests for the {@link FinancialAccountBankConnectionHandler} GET {@code accounts} and {@code providers}
 * actions (the read/query paths feeding the SPA selection modal and bank picker).
 *
 * <p>The Salt Edge calls are delegated to static helpers, all mocked here. The provider catalog is
 * cached in a static map keyed by client+country, so each provider test uses a distinct country to
 * keep the cache hits/misses isolated across the suite.
 *
 * <p>ETP-6151: a non-empty {@code accounts} answer carries {@code maxFetchInterval}, the days of
 * history the bank serves, as an int: the registered {@code PSD2_PROVIDER} limit when it declares
 * one, otherwise the {@code max_fetch_interval} of the same single Salt Edge {@code providers/{code}}
 * call that serves the logo, and omitted when neither declares one or the lookups fail.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountBankConnectionHandler
 * @covers com.etendoerp.go.schemaforge.FinancialAccountBankConnectionSupport
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
  // ETP-6151 — the bank's history limit on a non-empty accounts response.
  private static final String KEY_MAX_FETCH_INTERVAL = "maxFetchInterval";
  private static final String PROVIDER_CODE = "bbva";
  private static final String LOGO_URL = "https://cdn.saltedge.com/bank_icons/bbva.png";
  private static final String NO_CURRENCY_WITHOUT_MISMATCH =
      "accountCurrency is only reported for a currency mismatch";

  private FinancialAccountBankConnectionHandler handler;

  @Mock
  private FIN_FinancialAccount finAcc;

  @Mock
  private Currency usdCurrency;
  /** ETP-6151: the {@code psd2-statement-import-settings} gate; ON by default in this class. */
  private MockedStatic<Psd2ImportSettingsFlag> flagMock;

  @Before
  public void setUp() {
    // ETP-6151: the cases of this class exercise the flag-ON behaviour; flag-OFF ones call flagOff().
    flagMock = mockStatic(Psd2ImportSettingsFlag.class);
    flagMock.when(Psd2ImportSettingsFlag::isEnabled).thenReturn(true);
    handler = spy(new FinancialAccountBankConnectionHandler());
    doNothing().when(handler).doRollbackAndClose();
  }

  @After
  public void clearMocks() {
    if (flagMock != null) {
      flagMock.close();
    }
    Mockito.framework().clearInlineMocks();
  }

  /**
   * Switches the ETP-6151 gate OFF for the rest of the test: the pre-ETP-6151 behaviour.
   */
  private void flagOff() {
    flagMock.when(Psd2ImportSettingsFlag::isEnabled).thenReturn(false);
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
      assertFalse("no history limit for an empty result", data.has(KEY_MAX_FETCH_INTERVAL));
      utils.verify(() -> BankIntegrationUtils.makeSaltEdgeRequest(any(), any(), any(), any()),
          never());
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

  // ── ETP-6151: maxFetchInterval on GET accounts ─────────────────────────────

  /**
   * The registered {@code PSD2_PROVIDER} limit wins over Salt Edge's: it is the number the
   * {@code status} action and the synchronization's own warning name. Logo and limit both come
   * from ONE Salt Edge call, and the limit goes on the wire as an int.
   */
  @Test
  public void testAccountsRegisteredLimitWinsOverSaltEdge() throws Exception {
    JSONObject data = accountsWithProvider(registered(BigDecimal.valueOf(90)),
        saltEdgeProvider(365), PROVIDER_CODE);

    assertEquals(90, data.get(KEY_MAX_FETCH_INTERVAL));
    assertEquals(LOGO_URL, data.getString("providerLogoUrl"));
  }

  /**
   * A bank never linked on this instance has no {@code PSD2_PROVIDER} row yet: the limit comes from
   * the Salt Edge answer's {@code max_fetch_interval}.
   */
  @Test
  public void testAccountsFallsBackToSaltEdgeWhenProviderNotRegistered() throws Exception {
    JSONObject data = accountsWithProvider(null, saltEdgeProvider(180), PROVIDER_CODE);

    assertEquals(180, data.get(KEY_MAX_FETCH_INTERVAL));
  }

  /** A registered row that declares no limit (0) falls back to Salt Edge too. */
  @Test
  public void testAccountsFallsBackToSaltEdgeWhenRegisteredLimitIsZero() throws Exception {
    JSONObject data = accountsWithProvider(registered(BigDecimal.ZERO), saltEdgeProvider(180),
        PROVIDER_CODE);

    assertEquals(180, data.get(KEY_MAX_FETCH_INTERVAL));
  }

  /**
   * When neither source declares a limit (Salt Edge answers 0 or no {@code max_fetch_interval}),
   * the field is OMITTED, not null, so the SPA reads {@code undefined} and stays silent.
   */
  @Test
  public void testAccountsOmitsTheLimitWhenNoSourceDeclaresIt() throws Exception {
    JSONObject withoutField = new JSONObject()
        .put(KEY_DATA, new JSONObject().put("logo_url", LOGO_URL));
    for (JSONObject saltEdge : new JSONObject[] {saltEdgeProvider(0), withoutField}) {
      JSONObject data = accountsWithProvider(registered(BigDecimal.ZERO), saltEdge, PROVIDER_CODE);

      assertFalse(saltEdge.toString(), data.has(KEY_MAX_FETCH_INTERVAL));
      assertEquals(LOGO_URL, data.getString("providerLogoUrl"));
    }
  }

  /**
   * A failing Salt Edge call leaves the logo empty and, with no registered row, the limit unknown;
   * the account list itself is still answered.
   */
  @Test
  public void testAccountsSurvivesASaltEdgeProviderFailure() throws Exception {
    JSONObject data = accountsWithProvider(null, null, PROVIDER_CODE);

    assertEquals(1, data.getJSONArray(ACTION_ACCOUNTS).length());
    assertEquals("", data.getString("providerLogoUrl"));
    assertFalse(data.has(KEY_MAX_FETCH_INTERVAL));
  }

  /**
   * The provider lookup throwing is not a failure of the list either: it is treated as "not
   * registered" and the Salt Edge value is used.
   */
  @Test
  public void testAccountsFallsBackToSaltEdgeWhenTheProviderLookupThrows() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenThrow(new IllegalStateException("no session"));

      JSONObject data = accountsData(saltEdgeProvider(120), PROVIDER_CODE);

      assertEquals(1, data.getJSONArray(ACTION_ACCOUNTS).length());
      assertEquals(120, data.get(KEY_MAX_FETCH_INTERVAL));
    }
  }

  /**
   * Flag {@code psd2-statement-import-settings} OFF — {@code GET accounts} answers the logo through
   * the one plain {@code providers/{code}} call it always made, with no {@code maxFetchInterval}
   * (neither the registered limit nor the Salt Edge one) and without the AIS-fields query.
   */
  @Test
  public void testAccountsFlagOffAnswersTheLogoOnlyThroughTheOldCall() throws Exception {
    flagOff();
    ArgumentCaptor<String> endpoint = ArgumentCaptor.forClass(String.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      stubProviderLookup(obDal, registered(new BigDecimal("90")));

      JSONObject data = accountsData(utils, saltEdgeProvider(90), "fake_bank");

      assertFalse(data.has(KEY_MAX_FETCH_INTERVAL));
      assertEquals(LOGO_URL, data.getString("providerLogoUrl"));
      utils.verify(() -> BankIntegrationUtils.makeSaltEdgeRequest(eq(GET), any(),
          endpoint.capture(), eq(API_KEY)), times(1));
    }
    assertTrue(endpoint.getValue(), endpoint.getValue().endsWith("providers/fake_bank"));
  }

  /**
   * Exactly ONE Salt Edge request serves logo and limit, to {@code providers/{code}} with the code
   * URL-encoded and {@code include_ais_fields=true} — {@code max_fetch_interval} is an AIS field,
   * so without that flag the fallback would silently find nothing.
   */
  @Test
  public void testAccountsAsksSaltEdgeOnceWithAisFieldsAndAnEncodedCode() throws Exception {
    String code = "fake bank & co/es";
    ArgumentCaptor<String> endpoint = ArgumentCaptor.forClass(String.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      stubProviderLookup(obDal, null);

      JSONObject data = accountsData(utils, saltEdgeProvider(90), code);

      assertEquals(90, data.get(KEY_MAX_FETCH_INTERVAL));
      utils.verify(() -> BankIntegrationUtils.makeSaltEdgeRequest(eq(GET), any(),
          endpoint.capture(), eq(API_KEY)), times(1));
    }
    String url = endpoint.getValue();
    assertTrue(url, url.contains("providers/fake+bank+%26+co%2Fes?"));
    assertTrue(url, url.contains("include_ais_fields=true"));
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

  /**
   * A Salt Edge {@code providers/{code}} answer carrying {@link #LOGO_URL} and the given limit.
   *
   * @param maxFetchInterval the {@code max_fetch_interval} the answer declares
   * @return the answer
   */
  private static JSONObject saltEdgeProvider(int maxFetchInterval) throws Exception {
    return new JSONObject().put(KEY_DATA, new JSONObject()
        .put("logo_url", LOGO_URL).put("max_fetch_interval", maxFetchInterval));
  }

  /**
   * A registered {@code PSD2_PROVIDER} row declaring {@code maxFetchInterval}.
   *
   * @param maxFetchInterval the stored limit, possibly zero
   * @return the provider
   */
  private static Provider registered(BigDecimal maxFetchInterval) {
    Provider provider = mock(Provider.class);
    when(provider.getMaxFetchInterval()).thenReturn(maxFetchInterval);
    return provider;
  }

  /**
   * Runs {@code GET accounts} (one EUR account to select) with the provider lookup resolving to
   * {@code registeredRow} and Salt Edge answering {@code saltEdgeAnswer}.
   *
   * @param registeredRow  the {@code PSD2_PROVIDER} row, or {@code null} when not registered
   * @param saltEdgeAnswer the {@code providers/{code}} answer, or {@code null} to make it throw
   * @param providerCode   the connection's provider code
   * @return the {@code response.data} of the 200 answer
   */
  private JSONObject accountsWithProvider(Provider registeredRow, JSONObject saltEdgeAnswer,
      String providerCode) throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, registeredRow);
      return accountsData(saltEdgeAnswer, providerCode);
    }
  }

  /**
   * Same as {@link #accountsData(MockedStatic, JSONObject, String)}, with its own
   * {@link BankIntegrationUtils} mock. The provider lookup must already be stubbed.
   */
  private JSONObject accountsData(JSONObject saltEdgeAnswer, String providerCode)
      throws Exception {
    try (MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      return accountsData(utils, saltEdgeAnswer, providerCode);
    }
  }

  /**
   * Runs {@code GET accounts} for one EUR account of a connection whose provider code is
   * {@code providerCode}, with Salt Edge's {@code providers/{code}} answering
   * {@code saltEdgeAnswer}. The provider lookup must already be stubbed.
   *
   * @param utils          the open MockedStatic over {@link BankIntegrationUtils}
   * @param saltEdgeAnswer the answer, or {@code null} to make the call throw
   * @param providerCode   the connection's provider code
   * @return the {@code response.data} of the answer, after checking it is a 200
   */
  private JSONObject accountsData(MockedStatic<BankIntegrationUtils> utils,
      JSONObject saltEdgeAnswer, String providerCode) throws Exception {
    JSONArray raw = eurNodes();
    JSONObject details = new JSONObject().put("provider_name", BBVA)
        .put("provider_code", providerCode);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(raw);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
          .thenReturn(details);
      if (saltEdgeAnswer == null) {
        utils.when(() -> BankIntegrationUtils.makeSaltEdgeRequest(eq(GET), any(), anyString(),
            eq(API_KEY))).thenThrow(new IllegalStateException("Salt Edge down"));
      } else {
        utils.when(() -> BankIntegrationUtils.makeSaltEdgeRequest(eq(GET), any(), anyString(),
            eq(API_KEY))).thenReturn(saltEdgeAnswer);
      }
      stubFilters(linkHelper, raw, raw, raw);

      NeoResponse response = handler.handle(getContext(accountsParams(null)));

      assertEquals(200, response.getHttpStatus());
      return dataOf(response);
    }
  }
}
