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

import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.API_KEY;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.CONNECTION_ID;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.PARAM_ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.PARAM_CONNECTION_ID;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.PARAM_TYPE;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.SALT_EDGE_ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.postContext;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.stubObContext;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;

/**
 * Unit tests for the {@link FinancialAccountPsd2Handler} POST {@code link}, {@code createAndLink},
 * {@code sync} and {@code import-settings} actions (the write paths).
 *
 * <p>Linking is delegated to {@link SaltEdgeAccountLinkHelper}; account creation is delegated to
 * {@link FinancialAccountSupport}. Both are mocked statically so the tests assert only this
 * handler's orchestration and response envelope.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountPsd2HandlerLinkTest {

  private static final String LINK_WARNING = "consent expires soon";
  private static final String ACTION_LINK = "link";
  private static final String ACTION_CREATE_AND_LINK = "createAndLink";

  private FinancialAccountPsd2Handler handler;

  @Before
  public void setUp() {
    handler = spy(new FinancialAccountPsd2Handler());
    doNothing().when(handler).doRollbackAndClose();
  }

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  /** link with any blank required param is rejected with a 400 before any account lookup. */
  @Test
  public void testLinkMissingParamReturns400() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_CONNECTION_ID, CONNECTION_ID);
    // saltEdgeAccountId missing.

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(400, handler.handle(postContext(ACTION_LINK, body)).getHttpStatus());
    }
  }

  /** link on a missing account resolves to a 404. */
  @Test
  public void testLinkMissingAccountReturns404() throws Exception {
    JSONObject body = linkBody();
    doReturn(null).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(404, handler.handle(postContext(ACTION_LINK, body)).getHttpStatus());
    }
  }

  /** link when the chosen Salt Edge account is not in the connection resolves to a 404. */
  @Test
  public void testLinkUnknownSaltEdgeAccountReturns404() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)).thenReturn(API_KEY);
      // Connection has a different account than the one requested.
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(new JSONArray().put(new JSONObject().put("id", "OTHER")));

      assertEquals(404, handler.handle(postContext(ACTION_LINK, body)).getHttpStatus());
    }
  }

  /** link happy path returns linked=true plus the warning from the link helper. */
  @Test
  public void testLinkHappyReturnsLinkedAndWarning() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    JSONArray nodes = new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID));
    JSONObject details = new JSONObject().put("provider_name", "BBVA");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(nodes);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
          .thenReturn(details);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.resolveConsentExpiresAt(eq(details),
          eq(API_KEY))).thenReturn(null);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any())).thenReturn(LINK_WARNING);

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertTrue(data.getBoolean("linked"));
      assertEquals(ACCOUNT_ID, data.getString("financialAccountId"));
      assertEquals(LINK_WARNING, data.getString("warning"));
    }
  }

  /** createAndLink with a missing required param is rejected with a 400. */
  @Test
  public void testCreateAndLinkMissingParamReturns400() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_TYPE, "B").put(PARAM_CONNECTION_ID, CONNECTION_ID);
    // saltEdgeAccountId missing.

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(400, handler.handle(postContext(ACTION_CREATE_AND_LINK, body)).getHttpStatus());
    }
  }

  /** createAndLink with an unsupported currency code is rejected with a 400. */
  @Test
  public void testCreateAndLinkUnsupportedCurrencyReturns400() throws Exception {
    JSONObject body = createAndLinkBody();
    JSONArray nodes = new JSONArray().put(new JSONObject()
        .put("id", SALT_EDGE_ACCOUNT_ID).put("currency_code", "ZZZ"));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<FinancialAccountSupport> support =
            mockStatic(FinancialAccountSupport.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(nodes);
      support.when(() -> FinancialAccountSupport.findCurrencyByIsoCode("ZZZ")).thenReturn(null);

      assertEquals(400, handler.handle(postContext(ACTION_CREATE_AND_LINK, body)).getHttpStatus());
    }
  }

  /**
   * createAndLink happy path creates the FA from the chosen Salt Edge account, links it and returns
   * a 201 Created with the new account id and name.
   */
  @Test
  public void testCreateAndLinkHappyReturns201() throws Exception {
    JSONObject body = createAndLinkBody();
    JSONArray nodes = new JSONArray().put(new JSONObject()
        .put("id", SALT_EDGE_ACCOUNT_ID).put("name", "Ahorro").put("currency_code", "EUR"));
    JSONObject details = new JSONObject().put("provider_name", "BBVA");
    Currency currency = mock(Currency.class);
    FIN_FinancialAccount created = mock(FIN_FinancialAccount.class);
    when(created.getId()).thenReturn("FA-NEW");
    when(created.getName()).thenReturn("BBVA - Ahorro");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<FinancialAccountSupport> support = mockStatic(FinancialAccountSupport.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(nodes);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
          .thenReturn(details);
      support.when(() -> FinancialAccountSupport.findCurrencyByIsoCode("EUR")).thenReturn(currency);
      support.when(() -> FinancialAccountSupport.createAccount(any(), any(), eq(currency),
          anyString(), eq("B"))).thenReturn(created);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.resolveConsentExpiresAt(any(), anyString()))
          .thenReturn(null);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(created),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any())).thenReturn("");

      NeoResponse response = handler.handle(postContext(ACTION_CREATE_AND_LINK, body));

      assertEquals(201, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals("FA-NEW", data.getString("financialAccountId"));
      assertEquals("BBVA - Ahorro", data.getString("name"));
    }
  }

  /** sync returns the status and trimmed messages produced by fetchAccountTransactions. */
  @Test
  public void testSyncReturnsStatusAndMessage() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.fetchAccountTransactions(eq(finAcc), any()))
          .thenAnswer(invocation -> {
            StringBuilder sb = invocation.getArgument(1);
            sb.append("  Imported 5 statements  ");
            return "OK";
          });

      NeoResponse response = handler.handle(postContext("sync", body));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals("OK", data.getString("status"));
      assertEquals("Imported 5 statements", data.getString("message"));
    }
  }

  /** sync on a missing account resolves to a 404. */
  @Test
  public void testSyncMissingAccountReturns404() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    doReturn(null).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(404, handler.handle(postContext("sync", body)).getHttpStatus());
    }
  }

  /** import-settings updates the provided fields, persists the account and returns saved=true. */
  @Test
  public void testImportSettingsUpdatesProvidedFields() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("importFromDate", "2026-01-01")
        .put("statementGrouping", "MONTHLY");
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.handle(postContext("import-settings", body));

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("saved"));
      verify(finAcc).setPSD2ImportFromDate(any());
      verify(finAcc).setPSD2StatementFrequency("MONTHLY");
      verify(finAcc, Mockito.never()).setPSD2ImportToDate(any());
      verify(dal).save(finAcc);
      verify(dal).flush();
    }
  }

  /** import-settings on a missing account resolves to a 404. */
  @Test
  public void testImportSettingsMissingAccountReturns404() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    doReturn(null).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(404, handler.handle(postContext("import-settings", body)).getHttpStatus());
    }
  }

  // ── extractFetchScopes (via link happy path) ──────────────────────────────

  /**
   * {@code extractFetchScopes} must read the scopes from {@code last_attempt.fetch_scopes}, NOT
   * from the root {@code fetch_scopes} key. This regression guard verifies that the correct nested
   * path is used: when {@code last_attempt} is present and carries a non-blank {@code fetch_scopes},
   * that value is forwarded into the {@link LinkAccountData} that reaches
   * {@code SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount}.
   */
  @Test
  public void testExtractFetchScopesReadsFromLastAttemptNested() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    JSONArray nodes = new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID));
    JSONObject lastAttempt = new JSONObject().put("fetch_scopes", "accounts,transactions");
    JSONObject details = new JSONObject()
        .put("provider_name", "BBVA")
        .put("last_attempt", lastAttempt);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(nodes);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
          .thenReturn(details);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.resolveConsentExpiresAt(any(), anyString()))
          .thenReturn(null);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(),
          argThat(d -> d != null && "accounts,transactions".equals(d.fetchScopes))))
          .thenReturn("");

      // OBDal for resolveProvider (findProviderByCode criteria — returns null so setPsd2Provider
      // is not called, keeping this test focused on extractFetchScopes only).
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Provider> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Provider.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      // The argThat above only matches when fetchScopes == "accounts,transactions",
      // so this verify acts as the assertion.
      linkHelper.verify(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(),
          argThat(d -> d != null && "accounts,transactions".equals(d.fetchScopes))));
    }
  }

  /**
   * When {@code details} has no {@code last_attempt} key, {@code extractFetchScopes} returns an
   * empty string — no NPE and no fallback to the root-level {@code fetch_scopes} (which Salt Edge
   * does NOT set on connection objects).
   */
  @Test
  public void testExtractFetchScopesMissingLastAttemptReturnsEmpty() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    JSONArray nodes = new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID));
    // details has root-level fetch_scopes but NO last_attempt — must NOT read the root key.
    JSONObject details = new JSONObject()
        .put("provider_name", "BBVA")
        .put("fetch_scopes", "accounts,transactions");   // root key — should be ignored

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(nodes);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
          .thenReturn(details);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.resolveConsentExpiresAt(any(), anyString()))
          .thenReturn(null);
      // Only the empty-fetchScopes call must be made — root key must NOT be used.
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(),
          argThat(d -> d != null && "".equals(d.fetchScopes))))
          .thenReturn("");

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Provider> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Provider.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      linkHelper.verify(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(),
          argThat(d -> d != null && "".equals(d.fetchScopes))));
    }
  }

  // ── linkAccount: setPsd2Provider (ETP-4097 fix) ───────────────────────────

  /**
   * When the Salt Edge connection details carry a {@code provider_code} that resolves to an existing
   * {@link Provider} record in the DB, {@code linkAccount} must call {@code finAcc.setPsd2Provider}
   * with that provider so the "Bank Provider" field is populated (mirrors classic AisConnectionCallback).
   */
  @Test
  public void testLinkAccountSetsPsd2ProviderWhenProviderFound() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    JSONArray nodes = new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID));
    JSONObject details = new JSONObject()
        .put("provider_name", "BBVA")
        .put("provider_code", "bbva");

    Provider provider = mock(Provider.class);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(nodes);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
          .thenReturn(details);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.resolveConsentExpiresAt(any(), anyString()))
          .thenReturn(null);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(any(), any(),
          any(), any(), any())).thenReturn("");

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Provider> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Provider.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      // Provider found by code — resolveProvider returns it.
      when(criteria.uniqueResult()).thenReturn(provider);
      doNothing().when(dal).save(finAcc);

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      // setPsd2Provider must be called with the resolved provider.
      verify(finAcc).setPsd2Provider(provider);
      verify(dal).save(finAcc);
    }
  }

  /**
   * When the provider code is blank, {@code resolveProvider} returns null and {@code setPsd2Provider}
   * must NOT be called — the "Bank Provider" field is left empty rather than crashing.
   */
  @Test
  public void testLinkAccountDoesNotSetPsd2ProviderWhenProviderCodeBlank() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    JSONArray nodes = new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID));
    // No provider_code in details → resolveProvider returns null.
    JSONObject details = new JSONObject().put("provider_name", "BBVA");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(nodes);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
          .thenReturn(details);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.resolveConsentExpiresAt(any(), anyString()))
          .thenReturn(null);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(any(), any(),
          any(), any(), any())).thenReturn("");

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      // No provider_code → setPsd2Provider must NOT be called.
      verify(finAcc, never()).setPsd2Provider(any());
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static JSONObject linkBody() throws Exception {
    return new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_CONNECTION_ID, CONNECTION_ID)
        .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID);
  }

  private static JSONObject createAndLinkBody() throws Exception {
    return new JSONObject()
        .put(PARAM_TYPE, "B")
        .put(PARAM_CONNECTION_ID, CONNECTION_ID)
        .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID);
  }

  private static JSONObject dataOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }
}
