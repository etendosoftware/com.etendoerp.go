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

import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.API_KEY;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.CONNECTION_ID;
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
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;

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

  // ── helpers ───────────────────────────────────────────────────────────────

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
