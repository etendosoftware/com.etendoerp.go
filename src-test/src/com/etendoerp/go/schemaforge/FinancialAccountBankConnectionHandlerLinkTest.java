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
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_PERMANENT_DELETION;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_CONNECTION_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_TYPE;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.SALT_EDGE_ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.postContext;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubObContext;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;

import com.etendoerp.go.schemaforge.handlers.FinancialAccountAccountingDefaultsSupport;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.AccountTransactionsSync;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.ProviderCatalogUtils;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;

/**
 * Unit tests for the {@link FinancialAccountBankConnectionHandler} POST {@code link}, {@code createAndLink},
 * {@code sync} and {@code import-settings} actions (the write paths).
 *
 * <p>Linking is delegated to {@link SaltEdgeAccountLinkHelper}; account creation is delegated to
 * {@link FinancialAccountSupport}. Both are mocked statically so the tests assert only this
 * handler's orchestration and response envelope.
 *
 * <p>{@code link} and {@code createAndLink} require {@code importFromDate} and
 * {@code statementGrouping}: both are validated before any lookup (400 with the exact text),
 * written on the account before the Salt Edge link, and an import-from date older than the bank's
 * history adds the same top-level {@code messages} warning as {@code import-settings}.
 * {@code import-settings} validates the configuration it would RESULT in and writes only the keys
 * it is sent.
 *
 * <p>{@code sync} (ETP-6154): a sync another one already runs answers {@code IN_PROGRESS} with the
 * translated "please wait" text of the {@code neo_hints} catalog, a lock that cannot be taken
 * answers {@code ERROR} with {@code code: BANK_SYNC_LOCK_UNAVAILABLE}, the PSD2 module's
 * {@code Success} goes on the wire as {@code OK}, and a run whose duplicate check failed keeps its
 * status but carries {@code code: BANK_SYNC_DUPLICATE_CHECK_FAILED} and the translated text.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountBankConnectionHandler
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountBankConnectionHandlerLinkTest {

  private static final String LINK_WARNING = "consent expires soon";
  private static final String ACTION_LINK = "link";
  private static final String ACTION_CREATE_AND_LINK = "createAndLink";
  private static final String ACTION_DISCONNECT = "disconnect";
  /** The handler's inverted-range rejection, matched by exact text in the SPA. */

  private FinancialAccountBankConnectionHandler handler;
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
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
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

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubFinAccPaymentMethods(dal, Collections.emptyList());

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
   * a 201 Created with the new account id and name. Also verifies the ETP-4331-adjacent fix: the
   * newly created account is handed to the shared provisioning seam
   * {@link FinancialAccountSupport#provisionNewAccount} (previously this Salt Edge-created path
   * bypassed entirely the wiring the generic CRUD hook does for offline-created accounts).
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
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      stubCreateAndLinkHappyPath(utils, support, linkHelper, obDal, nodes, details, "B", currency,
          created);

      NeoResponse response = handler.handle(postContext(ACTION_CREATE_AND_LINK, body));

      assertEquals(201, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals("FA-NEW", data.getString("financialAccountId"));
      assertEquals("BBVA - Ahorro", data.getString("name"));
      support.verify(() -> FinancialAccountSupport.provisionNewAccount(created));
    }
  }

  /**
   * createAndLink for a Card-type ({@code CA}) account is provisioned too — the fix applies
   * regardless of the chosen account type, not just Bank. Worth its own case because the type is
   * what selects both the payment-method list and the deposit/withdrawal accounts inside the seam,
   * so a type-conditional call site here would silently under-provision cards only.
   */
  @Test
  public void testCreateAndLinkCardTypeAlsoProvisionsCreatedAccount() throws Exception {
    JSONObject body = withImportSettings(new JSONObject()
        .put(PARAM_TYPE, "CA")
        .put(PARAM_CONNECTION_ID, CONNECTION_ID)
        .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID));
    JSONArray nodes = new JSONArray().put(new JSONObject()
        .put("id", SALT_EDGE_ACCOUNT_ID).put("name", "Tarjeta Oro").put("currency_code", "EUR"));
    JSONObject details = new JSONObject().put("provider_name", "BBVA");
    Currency currency = mock(Currency.class);
    FIN_FinancialAccount created = mock(FIN_FinancialAccount.class);
    when(created.getId()).thenReturn("FA-CARD-NEW");
    when(created.getName()).thenReturn("BBVA - Tarjeta Oro");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<FinancialAccountSupport> support = mockStatic(FinancialAccountSupport.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      stubCreateAndLinkHappyPath(utils, support, linkHelper, obDal, nodes, details, "CA", currency,
          created);

      NeoResponse response = handler.handle(postContext(ACTION_CREATE_AND_LINK, body));

      assertEquals(201, response.getHttpStatus());
      support.verify(() -> FinancialAccountSupport.provisionNewAccount(created));
    }
  }

  /**
   * ETP-5207 regression guard for the <b>two-path divergence</b>. There are two ways to create a
   * financial account in Etendo GO:
   *
   * <ul>
   *   <li>manual ("sin conexión") — generic NEO CRUD, then {@code FinancialAccountHandler
   *       .afterHandle}'s POST branch;</li>
   *   <li>bank connection ("CONNECT ACCOUNT" / Salt Edge) — this {@code createAndLink} action.</li>
   * </ul>
   *
   * <p>They used to duplicate the provisioning call list, and they drifted twice: ETP-4872's
   * accounting defaults never reached this flow, and ETP-5207's first pass didn't either, while a
   * comment here claimed to "mirror the manual flow". The consequence was not a missing default but
   * a <b>wrong</b> one: {@code FinancialAccountSupport.createAccount} flushes, which fires core's
   * AFTER INSERT trigger {@code FIN_FINANCIAL_ACCOUNT_TRG}, and that trigger creates the
   * {@code fin_financial_account_acct} row already seeding {@code FIN_IN_CLEAR_ACCT} /
   * {@code FIN_OUT_CLEAR_ACCT} (DAL {@code clearedPaymentAccount} /
   * {@code clearedPaymentAccountOUT}) with the ledger asset account. Those must end up EMPTY,
   * because a non-null cleared account is exactly what makes {@code DocFINReconciliation
   * #getDocumentConfirmation} queue a reconciliation for posting. So a connected account was born
   * with 57200000 in both fields while a manually created one came up empty — reproduced in the
   * running app against a Fake Demo Bank connection.
   *
   * <p>The divergence is now structurally impossible because both flows call the one shared seam,
   * {@link FinancialAccountSupport#provisionNewAccount}. This test pins that decomposition from
   * <b>both</b> sides, which is what the sibling happy-path tests above do not do:
   *
   * <ul>
   *   <li>the seam IS called with the created account — the connect path provisions at all;</li>
   *   <li>the individual steps are NOT called inline here. This is the half-a-mirror bug class
   *       itself: re-inlining one step at this call site (rather than adding it to the seam) is
   *       exactly how the two lists drifted apart twice, and it is invisible to a test that only
   *       checks the steps happened.</li>
   * </ul>
   *
   * <p>What provisioning consists of is deliberately NOT asserted here — that lives at the seam, in
   * {@code FinancialAccountSupportTest#testProvisionNewAccountPerformsPaymentMethodsThenAccounting}.
   * The two tests are a pair: this one alone would pass against a gutted {@code provisionNewAccount}.
   */
  @Test
  public void testCreateAndLinkProvisionsOnlyThroughTheSharedSeam() throws Exception {
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
        MockedStatic<FinancialAccountAccountingDefaultsSupport> acctDefaults =
            mockStatic(FinancialAccountAccountingDefaultsSupport.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      stubCreateAndLinkHappyPath(utils, support, linkHelper, obDal, nodes, details, "B", currency,
          created);

      NeoResponse response = handler.handle(postContext(ACTION_CREATE_AND_LINK, body));

      assertEquals(201, response.getHttpStatus());
      // Routes through the seam — the same one the manual path uses.
      support.verify(() -> FinancialAccountSupport.provisionNewAccount(created));
      // ...and does NOT reach past it to the individual steps. Note the seam itself is mocked out
      // here, so any invocation of these two could only come from an inlined call in the handler.
      support.verify(() -> FinancialAccountSupport.assignDefaultPaymentMethods(any()), never());
      acctDefaults.verify(() -> FinancialAccountAccountingDefaultsSupport
          .applyDefaultAccountingConfiguration(any()), never());
    }
  }

  // ── import settings at link time (link / createAndLink) ───────────────────

  /**
   * link writes BOTH import settings on the account before the Salt Edge link runs, so they are
   * committed with it and no synchronization ever runs on the new link without an import-from date.
   * The check runs inside the link call itself: an {@link AssertionError} there escapes the
   * handler's {@code catch (Exception)} and fails this test instead of turning into a 500.
   */
  @Test
  public void testLinkWritesTheImportSettingsBeforeLinking() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      stubLinkHappyPath(utils, linkHelper, obDal, finAcc);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any())).thenAnswer(invocation -> {
            verifyImportSettingsWritten(finAcc);
            return LINK_WARNING;
          });

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      linkHelper.verify(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any()));
    }
  }

  /**
   * link without one of the import settings, or with an invalid one, answers 400 with the exact
   * validation text BEFORE the account is loaded or Salt Edge is called. A malformed date is a 400,
   * never a 500.
   */
  @Test
  public void testLinkWithMissingOrInvalidImportSettingsReturns400BeforeAnyLookup()
      throws Exception {
    for (String[] invalid : invalidImportSettings()) {
      JSONObject body = new JSONObject()
          .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
          .put(PARAM_CONNECTION_ID, CONNECTION_ID)
          .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID);
      putIfNotNull(body, "importFromDate", invalid[0]);
      putIfNotNull(body, "statementGrouping", invalid[1]);

      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
          MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
              mockStatic(SaltEdgeAccountLinkHelper.class)) {
        NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

        assertEquals(invalid[2], 400, response.getHttpStatus());
        assertEquals(invalid[2], invalid[2], errorMessageOf(response));
        utils.verifyNoInteractions();
        linkHelper.verifyNoInteractions();
      }
    }
    verify(handler, never()).loadAccount(anyString());
  }

  /**
   * link with an import-from date later than the import-to date already stored on the account does
   * not refuse: the stale import-to is cleared (set to null) together with the other settings and
   * the link goes on. A stored import-to that is not older than the new date is left alone.
   */
  @Test
  public void testLinkClearsStaleImportToAndProceeds() throws Exception {
    JSONObject body = linkBody(); // importFromDate 2026-01-01
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    when(finAcc.getPSD2ImportToDate())
        .thenReturn(FinancialAccountBankConnectionSupport.parseDate("2025-12-31"));
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);
    JSONArray nodes = new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID));
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
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any())).thenReturn(LINK_WARNING);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubFinAccPaymentMethods(dal, Collections.emptyList());

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("linked"));
      verify(finAcc).setPSD2ImportToDate(null);
      verify(finAcc).setPSD2ImportFromDate(
          FinancialAccountBankConnectionSupport.parseDate("2026-01-01"));
      verify(finAcc).setPSD2StatementFrequency("1BD");
    }
  }

  /**
   * link answers the non-blocking fetch-interval advisory when the import-from date is older than
   * the bank's history: a REST caller gets it at the top level as
   * {@code messages: [{type: "warning", text}]}, and the link itself still succeeds.
   */
  @Test
  public void testLinkBeyondFetchIntervalAddsTopLevelWarning() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<FinancialAccountBankConnectionSupport> support = mockStatic(
            FinancialAccountBankConnectionSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubObContext(obContext);
      stubLinkHappyPath(utils, linkHelper, obDal, finAcc);
      stubFetchIntervalExceeded(support, finAcc);

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("linked"));
      assertTopLevelFetchIntervalWarning(response);
    }
  }

  /**
   * createAndLink validates both import settings BEFORE anything else: a missing or invalid value
   * answers 400 with the exact text, and no Salt Edge call is made and no account is ever created,
   * so a bad request never leaves an orphan account behind.
   */
  @Test
  public void testCreateAndLinkWithInvalidImportSettingsNeverCreatesTheAccount()
      throws Exception {
    for (String[] invalid : invalidImportSettings()) {
      JSONObject body = new JSONObject()
          .put(PARAM_TYPE, "B")
          .put(PARAM_CONNECTION_ID, CONNECTION_ID)
          .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID);
      putIfNotNull(body, "importFromDate", invalid[0]);
      putIfNotNull(body, "statementGrouping", invalid[1]);

      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
          MockedStatic<FinancialAccountSupport> support =
              mockStatic(FinancialAccountSupport.class)) {
        stubObContext(obContext);

        NeoResponse response = handler.handle(postContext(ACTION_CREATE_AND_LINK, body));

        assertEquals(invalid[2], 400, response.getHttpStatus());
        assertEquals(invalid[2], invalid[2], errorMessageOf(response));
        utils.verifyNoInteractions();
        support.verify(() -> FinancialAccountSupport.createAccount(any(), any(), any(), any(),
            any()), never());
      }
    }
  }

  /**
   * createAndLink writes both import settings on the NEW account before linking it, and answers
   * the same top-level fetch-interval advisory as link when the date is older than the bank's
   * history.
   */
  @Test
  public void testCreateAndLinkWritesTheImportSettingsBeforeLinkingAndWarns() throws Exception {
    JSONObject body = createAndLinkBody();
    JSONArray nodes = new JSONArray().put(new JSONObject()
        .put("id", SALT_EDGE_ACCOUNT_ID).put("name", "Ahorro").put("currency_code", "EUR"));
    JSONObject details = new JSONObject().put("provider_name", "BBVA");
    Currency currency = mock(Currency.class);
    FIN_FinancialAccount created = mock(FIN_FinancialAccount.class);
    when(created.getId()).thenReturn("FA-NEW");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<FinancialAccountSupport> support = mockStatic(FinancialAccountSupport.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<FinancialAccountBankConnectionSupport> bankSupport = mockStatic(
            FinancialAccountBankConnectionSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubObContext(obContext);
      stubCreateAndLinkHappyPath(utils, support, linkHelper, obDal, nodes, details, "B", currency,
          created);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(created),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any())).thenAnswer(invocation -> {
            verifyImportSettingsWritten(created);
            return "";
          });
      stubFetchIntervalExceeded(bankSupport, created);

      NeoResponse response = handler.handle(postContext(ACTION_CREATE_AND_LINK, body));

      assertEquals(201, response.getHttpStatus());
      linkHelper.verify(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(created),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any()));
      assertTopLevelFetchIntervalWarning(response);
    }
  }

  /** sync returns the status and trimmed messages produced by fetchAccountTransactionsWithResult. */
  @Test
  public void testSyncReturnsStatusAndMessage() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountTransactionsSync> syncStatic =
            mockStatic(AccountTransactionsSync.class)) {
      stubObContext(obContext);
      syncStatic.when(() -> AccountTransactionsSync.fetchAccountTransactionsWithResult(eq(finAcc),
          any())).thenAnswer(invocation -> {
            StringBuilder sb = invocation.getArgument(1);
            sb.append("  Imported 5 statements  ");
            return new AccountTransactionsSync.SyncResult("OK", false);
          });

      NeoResponse response = handler.handle(postContext("sync", body));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = dataOf(response);
      assertEquals("OK", data.getString("status"));
      assertEquals("Imported 5 statements", data.getString("message"));
    }
  }

  /** sync echoes the account's freshly stamped last sync date (ETP-5582). */
  @Test
  public void testSyncReturnsLastSyncDateWhenStamped() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getPSD2LastSyncDate())
        .thenReturn(java.util.Date.from(java.time.Instant.parse("2026-10-06T09:15:00Z")));
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountTransactionsSync> syncStatic =
            mockStatic(AccountTransactionsSync.class)) {
      stubObContext(obContext);
      syncStatic.when(() -> AccountTransactionsSync.fetchAccountTransactionsWithResult(eq(finAcc),
          any())).thenReturn(new AccountTransactionsSync.SyncResult("OK", false));

      JSONObject data = dataOf(handler.handle(postContext("sync", body)));

      assertEquals("2026-10-06T09:15:00Z", data.getString("lastSyncDate"));
    }
  }

  /** sync on an account that never synced (failed run) returns JSON null, key present. */
  @Test
  public void testSyncReturnsNullLastSyncDateWhenNotStamped() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getPSD2LastSyncDate()).thenReturn(null);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<AccountTransactionsSync> syncStatic =
            mockStatic(AccountTransactionsSync.class)) {
      stubObContext(obContext);
      syncStatic.when(() -> AccountTransactionsSync.fetchAccountTransactionsWithResult(eq(finAcc),
          any())).thenReturn(new AccountTransactionsSync.SyncResult("ERROR", false));

      JSONObject data = dataOf(handler.handle(postContext("sync", body)));

      assertTrue(data.has("lastSyncDate"));
      assertTrue(data.isNull("lastSyncDate"));
    }
  }

  /**
   * A sync another sync of the account already runs answers 200 IN_PROGRESS with the "please
   * wait" text in the request language (the PSD2 AD_MESSAGE is English only), never the PSD2
   * buffer, and still echoes the last sync date.
   */
  @Test
  public void testSyncInProgressAnswersThePleaseWaitTextInTheRequestLanguage() throws Exception {
    JSONObject spanish = syncData(BankIntegrationConstants.SYNC_IN_PROGRESS, "es_ES");
    JSONObject english = syncData(BankIntegrationConstants.SYNC_IN_PROGRESS, "en_US");

    assertEquals("IN_PROGRESS", spanish.getString("status"));
    assertEquals("Trayendo extractos, por favor espera\u2026",
        spanish.getString("message"));
    assertFalse(spanish.has("code"));
    assertTrue(spanish.has("lastSyncDate"));
    assertTrue(spanish.isNull("lastSyncDate"));
    assertEquals("IN_PROGRESS", english.getString("status"));
    assertEquals("Fetching statements, please wait\u2026", english.getString("message"));
    assertTrue(english.has("lastSyncDate"));
    assertTrue(english.isNull("lastSyncDate"));
  }

  /**
   * A sync whose lock cannot be taken answers a retriable ERROR carrying
   * {@code code: BANK_SYNC_LOCK_UNAVAILABLE} and the translated "try again" text.
   */
  @Test
  public void testSyncLockUnavailableAnswersARetriableErrorCode() throws Exception {
    JSONObject spanish = syncData(BankIntegrationConstants.SYNC_LOCK_UNAVAILABLE, "es_ES");
    JSONObject english = syncData(BankIntegrationConstants.SYNC_LOCK_UNAVAILABLE, "en_US");

    assertEquals("ERROR", spanish.getString("status"));
    assertEquals("BANK_SYNC_LOCK_UNAVAILABLE", spanish.getString("code"));
    assertEquals("No se pudieron traer los extractos en este momento. Por favor, "
        + "int\u00e9ntalo de nuevo en unos instantes.", spanish.getString("message"));
    assertEquals("BANK_SYNC_LOCK_UNAVAILABLE", english.getString("code"));
    assertEquals("The statements could not be fetched right now. Please try again in a few "
        + "moments.", english.getString("message"));
    assertTrue(spanish.has("lastSyncDate"));
    assertTrue(spanish.isNull("lastSyncDate"));
    assertTrue(english.has("lastSyncDate"));
    assertTrue(english.isNull("lastSyncDate"));
  }

  /**
   * A finished sync reports the PSD2 module's {@code Success} as {@code OK} on the wire, keeps
   * WARNING and ERROR unchanged, and carries the PSD2 messages, trimmed.
   */
  @Test
  public void testSyncMapsThePsd2SuccessToOk() throws Exception {
    JSONObject success = syncData(BankIntegrationConstants.SUCCESS, "es_ES");
    JSONObject warning = syncData(BankIntegrationConstants.WARNING, "es_ES");

    assertEquals("OK", success.getString("status"));
    assertEquals("PSD2 buffer", success.getString("message"));
    assertFalse(success.has("code"));
    assertEquals("WARNING", warning.getString("status"));
    assertEquals("OK", FinancialAccountBankConnectionHandler.wireSyncStatus("Success"));
    assertEquals("ERROR", FinancialAccountBankConnectionHandler.wireSyncStatus("ERROR"));
    assertEquals("IN_PROGRESS", FinancialAccountBankConnectionHandler.wireSyncStatus("IN_PROGRESS"));
  }

  /**
   * A run whose "already imported?" check failed keeps its ERROR status and answers the stable
   * {@code BANK_SYNC_DUPLICATE_CHECK_FAILED} code with the translated "retry" text in the request
   * language, instead of the English PSD2 buffer.
   */
  @Test
  public void testSyncDuplicateCheckFailedOnErrorAnswersTheCodeAndTranslatedText()
      throws Exception {
    JSONObject spanish = syncData(BankIntegrationConstants.ERROR, true, "es_ES");
    JSONObject english = syncData(BankIntegrationConstants.ERROR, true, "en_US");

    assertEquals("ERROR", spanish.getString("status"));
    assertEquals("BANK_SYNC_DUPLICATE_CHECK_FAILED", spanish.getString("code"));
    assertEquals("No se pudo comprobar si algunos movimientos bancarios ya estaban importados, "
        + "as\u00ed que no se importaron para evitar duplicados. Por favor, int\u00e9ntalo de nuevo "
        + "en unos instantes.", spanish.getString("message"));
    assertEquals("ERROR", english.getString("status"));
    assertEquals("BANK_SYNC_DUPLICATE_CHECK_FAILED", english.getString("code"));
    assertEquals("Some bank movements could not be checked against the ones already imported, so "
        + "they were not imported to avoid duplicates. Please try again in a few moments.",
        english.getString("message"));
    assertTrue(english.has("lastSyncDate"));
  }

  /**
   * When another connection still imported movements the run ends in WARNING: the status stays
   * WARNING and the duplicate-check code is still answered. A Success with the flag set is
   * reported as OK on the wire, with the code.
   */
  @Test
  public void testSyncDuplicateCheckFailedOnWarningKeepsWarningWithTheCode() throws Exception {
    JSONObject warning = syncData(BankIntegrationConstants.WARNING, true, "en_US");
    JSONObject success = syncData(BankIntegrationConstants.SUCCESS, true, "en_US");

    assertEquals("WARNING", warning.getString("status"));
    assertEquals("BANK_SYNC_DUPLICATE_CHECK_FAILED", warning.getString("code"));
    assertFalse(warning.getString("message").contains("PSD2 buffer"));
    assertEquals("OK", success.getString("status"));
    assertEquals("BANK_SYNC_DUPLICATE_CHECK_FAILED", success.getString("code"));
  }

  /**
   * Without a duplicate-check failure there is no {@code code}; the sync that did not run
   * (IN_PROGRESS, lock unavailable) answers as before, whatever the flag says.
   */
  @Test
  public void testSyncWithoutDuplicateCheckFailureHasNoCodeAndNotRunOutcomesAreUnchanged()
      throws Exception {
    JSONObject error = syncData(BankIntegrationConstants.ERROR, false, "en_US");
    JSONObject inProgress = syncData(BankIntegrationConstants.SYNC_IN_PROGRESS, true, "en_US");
    JSONObject unavailable = syncData(BankIntegrationConstants.SYNC_LOCK_UNAVAILABLE, true, "en_US");

    assertEquals("ERROR", error.getString("status"));
    assertFalse(error.has("code"));
    assertEquals("PSD2 buffer", error.getString("message"));
    assertEquals("IN_PROGRESS", inProgress.getString("status"));
    assertFalse(inProgress.has("code"));
    assertEquals("Fetching statements, please wait\u2026", inProgress.getString("message"));
    assertEquals("ERROR", unavailable.getString("status"));
    assertEquals("BANK_SYNC_LOCK_UNAVAILABLE", unavailable.getString("code"));
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
        .put("statementGrouping", "1BM");
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
      verify(finAcc).setPSD2StatementFrequency("1BM");
      verify(finAcc, Mockito.never()).setPSD2ImportToDate(any());
      verify(dal).save(finAcc);
      verify(dal).flush();
    }
  }

  /**
   * ETP-5181 — an "Importar desde" far beyond the provider's published {@code max_fetch_interval}
   * is still saved, byte-for-byte, and is NOT rejected with a 400.
   *
   * <p>This pins the product decision behind the whole ticket: advise, but allow saving. The range
   * is applied as a LOCAL filter over whatever the provider returns (Salt Edge ignores
   * from_date/to_date — see {@code BankIntegrationUtils.buildSaltEdgeTransactionsEndpoint}), so an
   * over-long range loses nothing inside the window that IS available; the notice exists only to
   * stop the user expecting history the bank will never hand over. Without this test the next
   * person to read the advisory naturally "completes" it into a hard validation here, next to the
   * ETP-5104 inverted-range guard, and silently breaks every account whose history predates its
   * provider's window.
   *
   * <p>The stored value is captured rather than matched with {@code any()} so a "helpful" clamp to
   * the earliest served day fails too, not just an outright rejection.
   */
  @Test
  public void testImportSettingsAcceptsRangeBeyondFetchInterval() throws Exception {
    // Years beyond any published interval, and deliberately with no upper bound so the ETP-5104
    // inverted-range guard cannot be what accepts or rejects this request.
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("importFromDate", "2019-03-01")
        .put("statementGrouping", "1BD");
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.handle(postContext("import-settings", body));

      assertEquals("an out-of-range import date is advisory, never a 400", 200,
          response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("saved"));

      ArgumentCaptor<Date> stored = ArgumentCaptor.forClass(Date.class);
      verify(finAcc).setPSD2ImportFromDate(stored.capture());
      assertEquals("the requested date must be stored unchanged, not clamped",
          FinancialAccountBankConnectionSupport.parseDate("2019-03-01"), stored.getValue());
      verify(dal).save(finAcc);
      verify(dal).flush();
    }
  }

  /**
   * Flag {@code psd2-statement-import-settings} OFF — link neither requires nor reads the import
   * keys: a body without them links (200), and keys that would be refused with the flag on are
   * ignored, never stored.
   */
  @Test
  public void testLinkFlagOffIgnoresTheImportSettingsKeys() throws Exception {
    flagOff();
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_CONNECTION_ID, CONNECTION_ID)
        .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID)
        .put("importFromDate", "not-a-date")
        .put("statementGrouping", "MONTHLY");
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      stubLinkHappyPath(utils, linkHelper, obDal, finAcc);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any())).thenReturn(LINK_WARNING);

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      verify(finAcc, never()).setPSD2ImportFromDate(any());
      verify(finAcc, never()).setPSD2ImportToDate(any());
      verify(finAcc, never()).setPSD2StatementFrequency(any());
      assertFalse(response.getBody().has("messages"));
    }
  }

  /**
   * Flag OFF — createAndLink does not require the import keys either, and writes none of them on
   * the new account.
   */
  @Test
  public void testCreateAndLinkFlagOffIgnoresTheImportSettingsKeys() throws Exception {
    flagOff();
    JSONObject body = new JSONObject()
        .put(PARAM_TYPE, "B")
        .put(PARAM_CONNECTION_ID, CONNECTION_ID)
        .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID);
    JSONArray nodes = new JSONArray().put(new JSONObject()
        .put("id", SALT_EDGE_ACCOUNT_ID).put("name", "Ahorro").put("currency_code", "EUR"));
    JSONObject details = new JSONObject().put("provider_name", "BBVA");
    Currency currency = mock(Currency.class);
    FIN_FinancialAccount created = mock(FIN_FinancialAccount.class);
    when(created.getId()).thenReturn("FA-NEW");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<FinancialAccountSupport> support = mockStatic(FinancialAccountSupport.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      stubCreateAndLinkHappyPath(utils, support, linkHelper, obDal, nodes, details, "B", currency,
          created);

      NeoResponse response = handler.handle(postContext(ACTION_CREATE_AND_LINK, body));

      assertEquals(201, response.getHttpStatus());
      verify(created, never()).setPSD2ImportFromDate(any());
      verify(created, never()).setPSD2StatementFrequency(any());
      assertFalse(response.getBody().has("messages"));
    }
  }

  /**
   * Flag OFF — import-settings keeps the pre-ETP-6151 validation: nothing is required, so a body
   * with only an import-from date saves on an account with no grouping stored, and only the keys
   * carried are written.
   */
  @Test
  public void testImportSettingsFlagOffRequiresNothing() throws Exception {
    flagOff();
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("importFromDate", "2026-01-01");
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.handle(postContext("import-settings", body));

      assertEquals(200, response.getHttpStatus());
      verify(finAcc).setPSD2ImportFromDate(
          FinancialAccountBankConnectionSupport.parseDate("2026-01-01"));
      verify(finAcc, never()).setPSD2StatementFrequency(any());
      verify(dal).save(finAcc);
    }
  }

  /**
   * Flag OFF — an import-settings body that would be refused with the flag on (blank import-from
   * date, grouping outside the codes) is accepted: no required-field and no grouping validation.
   */
  @Test
  public void testImportSettingsFlagOffAcceptsWhatTheFlagOnRefuses() throws Exception {
    flagOff();
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("statementGrouping", "MONTHLY");
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

      NeoResponse response = handler.handle(postContext("import-settings", body));

      assertEquals(200, response.getHttpStatus());
      verify(finAcc).setPSD2StatementFrequency("MONTHLY");
    }
  }

  /**
   * Flag OFF — a malformed date is not turned into the coded 400: it fails as before ETP-6151
   * (the raw parse error, a 500).
   */
  @Test
  public void testImportSettingsFlagOffMalformedDateKeepsTheOldFailure() throws Exception {
    flagOff();
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("importFromDate", "01/01/2026");
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

      NeoResponse response = handler.handle(postContext("import-settings", body));

      assertEquals(500, response.getHttpStatus());
      verify(finAcc, never()).setPSD2ImportFromDate(any());
    }
  }

  /** Flag ON and OFF are evaluated once per link request (the gate is one call, not many). */
  @Test
  public void testLinkEvaluatesTheFlagOncePerRequest() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      stubLinkHappyPath(utils, linkHelper, obDal, finAcc);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any())).thenReturn(LINK_WARNING);

      handler.handle(postContext(ACTION_LINK, body));

      flagMock.verify(Psd2ImportSettingsFlag::isEnabled, Mockito.times(1));
    }
  }

  /**
   * ETP-5805 — import-settings saves AND carries the non-blocking fetch-interval advisory in the
   * {@code messages} envelope (inside {@code response} for an MCP-origin request).
   */
  @Test
  public void testImportSettingsBeyondFetchIntervalAddsWarning() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("importFromDate", "2019-03-01")
        .put("statementGrouping", "1BD");
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<FinancialAccountBankConnectionSupport> support = mockStatic(
            FinancialAccountBankConnectionSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubObContext(obContext);
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      support.when(() -> FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(
          eq(finAcc), any())).thenReturn(new FinancialAccountBankConnectionSupport.FetchIntervalExceeded(
              90, java.time.LocalDate.of(2026, 7, 21)));
      com.etendoerp.go.schemaforge.NeoContext ctx = postContext("import-settings", body);
      org.mockito.Mockito.when(ctx.isMcpOrigin()).thenReturn(true);

      NeoResponse response = handler.handle(ctx);

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("saved"));
      org.codehaus.jettison.json.JSONObject message = response.getBody().getJSONObject("response")
          .getJSONArray("messages").getJSONObject(0);
      assertEquals("warning", message.getString("type"));
    }
  }

  /**
   * ETP-5805 — import-settings evaluates the RESULTING range (ETP-5104 logic): a body carrying only
   * importToDate still warns when the stored importFrom is beyond the fetch interval.
   */
  @Test
  public void testImportSettingsOnlyImportToEvaluatesStoredImportFrom() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("importToDate", "2026-10-01");
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    Date storedFrom = FinancialAccountBankConnectionSupport.parseDate("2019-03-01");
    org.mockito.Mockito.when(finAcc.getPSD2ImportFromDate()).thenReturn(storedFrom);
    // ETP-6151: the saved configuration needs a grouping; the stored one (the column default).
    org.mockito.Mockito.when(finAcc.getPSD2StatementFrequency()).thenReturn("1BD");
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<FinancialAccountBankConnectionSupport> support = mockStatic(
            FinancialAccountBankConnectionSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubObContext(obContext);
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      support.when(() -> FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(
          eq(finAcc), eq(storedFrom))).thenReturn(
              new FinancialAccountBankConnectionSupport.FetchIntervalExceeded(
                  90, java.time.LocalDate.of(2026, 7, 21)));

      com.etendoerp.go.schemaforge.NeoContext ctx = postContext("import-settings", body);
      org.mockito.Mockito.when(ctx.isMcpOrigin()).thenReturn(true);

      NeoResponse response = handler.handle(ctx);

      assertEquals(200, response.getHttpStatus());
      assertEquals("warning", response.getBody().getJSONObject("response")
          .getJSONArray("messages").getJSONObject(0).getString("type"));
    }
  }

  /** ETP-5805 - a from-date within the provider's history saves and adds no {@code messages}. */
  @Test
  public void testImportSettingsWithinFetchIntervalAddsNoMessages() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("importFromDate", "2026-10-01")
        .put("statementGrouping", "1BD");
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<FinancialAccountBankConnectionSupport> support = mockStatic(
            FinancialAccountBankConnectionSupport.class, Mockito.CALLS_REAL_METHODS)) {
      stubObContext(obContext);
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      support.when(() -> FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(
          eq(finAcc), any())).thenReturn(null);
      com.etendoerp.go.schemaforge.NeoContext ctx = postContext("import-settings", body);
      org.mockito.Mockito.when(ctx.isMcpOrigin()).thenReturn(true);

      NeoResponse response = handler.handle(ctx);

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("saved"));
      assertFalse(response.getBody().has("messages"));
      assertFalse(response.getBody().getJSONObject("response").has("messages"));
    }
  }

  /**
   * ETP-5805 - envelope placement: an MCP-origin request gets the warning inside {@code response},
   * a REST-origin one at the body root (the same {@code NeoHints.addMessage} contract as the PUT hook).
   */
  @Test
  public void testImportSettingsWarningPlacementFollowsOrigin() throws Exception {
    for (boolean mcp : new boolean[] {true, false}) {
      JSONObject body = new JSONObject()
          .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
          .put("importFromDate", "2019-03-01")
          .put("statementGrouping", "1BD");
      FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
      doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<FinancialAccountBankConnectionSupport> support = mockStatic(
              FinancialAccountBankConnectionSupport.class, Mockito.CALLS_REAL_METHODS)) {
        stubObContext(obContext);
        obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
        support.when(() -> FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(
            eq(finAcc), any())).thenReturn(
                new FinancialAccountBankConnectionSupport.FetchIntervalExceeded(
                    90, java.time.LocalDate.of(2026, 7, 21)));
        com.etendoerp.go.schemaforge.NeoContext ctx = postContext("import-settings", body);
        org.mockito.Mockito.when(ctx.isMcpOrigin()).thenReturn(mcp);

        JSONObject out = handler.handle(ctx).getBody();

        JSONObject holder = mcp ? out.getJSONObject("response") : out;
        JSONObject other = mcp ? out : out.getJSONObject("response");
        assertEquals("mcp=" + mcp, "warning",
            holder.getJSONArray("messages").getJSONObject(0).getString("type"));
        assertFalse("mcp=" + mcp, other.has("messages"));
      }
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

  /**
   * import-settings refuses a grouping outside the four PSD2 codes with a 400 and writes nothing.
   * Before, any string was stored and only failed, silently, on the next synchronization.
   */
  @Test
  public void testImportSettingsInvalidGroupingReturns400AndWritesNothing() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("importFromDate", "2026-01-01")
        .put("statementGrouping", "MONTHLY");

    assertImportSettingsRejected(body, mock(FIN_FinancialAccount.class),
        BankImportSettingsRules.MSG_STATEMENT_GROUPING_INVALID);
  }

  /** A malformed import-from date is a 400 with its own text (it used to be a 500). */
  @Test
  public void testImportSettingsMalformedImportFromReturns400() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("importFromDate", "01/01/2026")
        .put("statementGrouping", "1BD");

    assertImportSettingsRejected(body, mock(FIN_FinancialAccount.class),
        BankImportSettingsRules.MSG_IMPORT_FROM_DATE_INVALID);
  }

  /** A malformed import-to date is a 400 with the import-to text, not the import-from one. */
  @Test
  public void testImportSettingsMalformedImportToReturns400() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("importToDate", "2026-02-30");

    assertImportSettingsRejected(body, storedSettings("2026-01-01", "1BD"),
        BankImportSettingsRules.MSG_IMPORT_TO_DATE_INVALID);
  }

  /**
   * The configuration being saved must keep an import-from date: a body that omits it on an
   * account that has none stored (linked before the date was required) is refused, and so is a
   * body that clears it with a blank value.
   */
  @Test
  public void testImportSettingsWithoutResultingImportFromReturns400() throws Exception {
    assertImportSettingsRejected(new JSONObject()
            .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
            .put("statementGrouping", "1BW"),
        storedSettings(null, "1BD"),
        BankImportSettingsRules.MSG_IMPORT_FROM_DATE_REQUIRED);
    assertImportSettingsRejected(new JSONObject()
            .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
            .put("importFromDate", ""),
        storedSettings("2026-01-01", "1BD"),
        BankImportSettingsRules.MSG_IMPORT_FROM_DATE_REQUIRED);
  }

  /**
   * Same for the grouping: omitted on an account with none stored, or cleared with a blank value,
   * the resulting configuration has no grouping and is refused.
   */
  @Test
  public void testImportSettingsWithoutResultingGroupingReturns400() throws Exception {
    assertImportSettingsRejected(new JSONObject()
            .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
            .put("importFromDate", "2026-01-01"),
        storedSettings(null, null),
        BankImportSettingsRules.MSG_STATEMENT_GROUPING_REQUIRED);
    assertImportSettingsRejected(new JSONObject()
            .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
            .put("statementGrouping", " "),
        storedSettings("2026-01-01", "1BD"),
        BankImportSettingsRules.MSG_STATEMENT_GROUPING_REQUIRED);
  }

  /**
   * Only the keys the body carries are written: a body with just the grouping, on an account that
   * already has an import-from date, updates the grouping and leaves both dates untouched.
   */
  @Test
  public void testImportSettingsWritesOnlyTheSentKeys() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put("statementGrouping", "1BW");
    FIN_FinancialAccount finAcc = storedSettings("2026-01-01", "1BD");
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.handle(postContext("import-settings", body));

      assertEquals(200, response.getHttpStatus());
      verify(finAcc).setPSD2StatementFrequency("1BW");
      verify(finAcc, never()).setPSD2ImportFromDate(any());
      verify(finAcc, never()).setPSD2ImportToDate(any());
      verify(dal).save(finAcc);
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
      stubFinAccPaymentMethods(dal, Collections.emptyList());

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
      stubFinAccPaymentMethods(dal, Collections.emptyList());

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
      stubFinAccPaymentMethods(dal, Collections.emptyList());

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      // setPsd2Provider must be called with the resolved provider.
      verify(finAcc).setPsd2Provider(provider);
      verify(dal).save(finAcc);
    }
  }

  /**
   * When the provider is not found locally, {@code resolveProvider} falls through to
   * {@code fetchAndRegisterProvider}, which fetches the full provider details from Salt Edge and
   * must forward {@code logo_url} to {@code upsertProvider} — this is what lets the account list
   * show the real bank logo instead of the generic icon (ETP-4764 follow-up), and it must not
   * wait for the scheduled catalog sync in the psd2 module to populate it.
   */
  @Test
  public void testLinkAccountRegistersNewProviderWithLogoUrl() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    JSONArray nodes = new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID));
    JSONObject details = new JSONObject()
        .put("provider_name", "BBVA")
        .put("provider_code", "bbva");

    String logoUrl = "https://cdn.saltedge.com/bank_icons/bbva.png";
    JSONObject providerData = new JSONObject()
        .put("name", "BBVA")
        .put("max_fetch_interval", 90)
        .put("logo_url", logoUrl);
    JSONObject providerResponse = new JSONObject().put("data", providerData);

    Provider registeredProvider = mock(Provider.class);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<ProviderCatalogUtils> catalog = mockStatic(ProviderCatalogUtils.class);
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
      utils.when(() -> BankIntegrationUtils.makeSaltEdgeRequest(eq("GET"), isNull(), anyString(),
          eq(API_KEY))).thenReturn(providerResponse);
      catalog.when(() -> ProviderCatalogUtils.upsertProvider("bbva", "BBVA",
          BigDecimal.valueOf(90), logoUrl)).thenReturn(registeredProvider);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Provider> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Provider.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      // Not found locally — resolveProvider must fetch and register it.
      when(criteria.uniqueResult()).thenReturn(null);
      doNothing().when(dal).save(finAcc);
      stubFinAccPaymentMethods(dal, Collections.emptyList());

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      catalog.verify(() -> ProviderCatalogUtils.upsertProvider("bbva", "BBVA",
          BigDecimal.valueOf(90), logoUrl));
      verify(finAcc).setPsd2Provider(registeredProvider);
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
      stubFinAccPaymentMethods(dal, Collections.emptyList());

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      // No provider_code → setPsd2Provider must NOT be called.
      verify(finAcc, never()).setPsd2Provider(any());
    }
  }

  // ── Automatic Withdrawn is NOT touched by connect/disconnect (ETP-4891) ────
  //
  // Until ETP-4891 this handler cleared Automatic Withdrawn on connect and restored it to true on
  // a permanent disconnect (ETP-4406). Both are gone: the flag is now an invariant of the
  // bank-transfer payment METHOD — always off — seeded by sampledata, enforced at link creation by
  // FinancialAccountSupport.createLink, and repaired on existing tenants by data-fix R24. These
  // tests pin the absence of the old behavior, because the restore in particular was a live bug:
  // an account that was connected and then disconnected silently went back to auto-withdrawing.

  /**
   * Linking an account to its bank must NOT write {@code Automatic Withdrawn} on any of its
   * payment-method rows, not even a transfer one. The flag no longer depends on the account's
   * connection state, so a connect that still cleared it would be dead weight at best and, paired
   * with the removed restore, a source of drift.
   */
  @Test
  public void testLinkAccountDoesNotTouchAutomaticWithdrawn() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    JSONArray nodes = new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID));
    // No provider_code in details → resolveProvider returns null, keeping this test focused on
    // the transfer payment method behavior only.
    JSONObject details = new JSONObject().put("provider_name", "BBVA");

    FIN_PaymentMethod transferMethod = mock(FIN_PaymentMethod.class);
    when(transferMethod.getName()).thenReturn("Transferencia bancaria");
    FinAccPaymentMethod transferFapm = mock(FinAccPaymentMethod.class);
    when(transferFapm.getPaymentMethod()).thenReturn(transferMethod);

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
      stubFinAccPaymentMethods(dal, Collections.singletonList(transferFapm));

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      verify(transferFapm, never()).setAutomaticWithdrawn(anyBoolean());
      verify(transferFapm, never()).setAutomaticDeposit(anyBoolean());
    }
  }

  /**
   * A non-transfer row (e.g. Cash) is untouched by a connect too — it always was, and stays so.
   */
  @Test
  public void testLinkAccountLeavesNonTransferPaymentMethodUnchanged() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    JSONArray nodes = new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID));
    JSONObject details = new JSONObject().put("provider_name", "BBVA");

    FIN_PaymentMethod cashMethod = mock(FIN_PaymentMethod.class);
    when(cashMethod.getName()).thenReturn("Efectivo");
    FinAccPaymentMethod cashFapm = mock(FinAccPaymentMethod.class);
    when(cashFapm.getPaymentMethod()).thenReturn(cashMethod);

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
      stubFinAccPaymentMethods(dal, Collections.singletonList(cashFapm));

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      verify(cashFapm, never()).setAutomaticWithdrawn(anyBoolean());
      verify(cashFapm, never()).setAutomaticDeposit(anyBoolean());
      verify(dal, never()).save(cashFapm);
    }
  }

  /**
   * The ETP-4891 regression guard. A <b>permanent</b> disconnect used to set {@code Automatic
   * Withdrawn} back to {@code true} on the transfer method — which is exactly how a transfer
   * account ended up auto-creating its {@code FIN_Finacc_Transaction} again after having been
   * connected once. It must now leave the flag alone, and the response must still report the
   * disconnect faithfully.
   */
  @Test
  public void testPermanentDisconnectDoesNotRestoreAutomaticWithdrawn() throws Exception {
    JSONObject body = disconnectBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    FIN_PaymentMethod transferMethod = mock(FIN_PaymentMethod.class);
    when(transferMethod.getName()).thenReturn("Transferencia bancaria");
    FinAccPaymentMethod transferFapm = mock(FinAccPaymentMethod.class);
    when(transferFapm.getPaymentMethod()).thenReturn(transferMethod);

    FinaccConnection connection = mock(FinaccConnection.class);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      // ETP-5097: resolved via getLatestConnectionForFinAcc (matches any status), not the
      // disconnectFinancialAccount wrapper, whose lookup only matches an "AC" connection.
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getLatestConnectionForFinAcc(finAcc))
          .thenReturn(connection);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.disconnectConnection(connection, true))
          .thenReturn(false);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubFinAccPaymentMethods(dal, Collections.singletonList(transferFapm));

      NeoResponse response = handler.handle(postContext(ACTION_DISCONNECT, body));

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("disconnected"));
      // The whole point of ETP-4891: no write in either direction.
      verify(transferFapm, never()).setAutomaticWithdrawn(anyBoolean());
      verify(transferFapm, never()).setAutomaticDeposit(anyBoolean());
      verify(dal, never()).save(transferFapm);
    }
  }

  /**
   * A permanent-deletion request against an account with nothing left to disconnect (no
   * {@link FinaccConnection} record, no stale Salt Edge id) must surface a real error — not the
   * false {@code 200}/{@code disconnected=false} success this used to report before ETP-5097.
   * The payment-method flag is equally untouched either way.
   */
  @Test
  public void testDisconnectReportsFailureAndTouchesNothing() throws Exception {
    JSONObject body = disconnectBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    FIN_PaymentMethod transferMethod = mock(FIN_PaymentMethod.class);
    when(transferMethod.getName()).thenReturn("Transferencia bancaria");
    FinAccPaymentMethod transferFapm = mock(FinAccPaymentMethod.class);
    when(transferFapm.getPaymentMethod()).thenReturn(transferMethod);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      // No FinaccConnection record and no Salt Edge id on the account (both unstubbed → null):
      // there is genuinely nothing to disconnect.
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getLatestConnectionForFinAcc(finAcc))
          .thenReturn(null);

      NeoResponse response = handler.handle(postContext(ACTION_DISCONNECT, body));

      assertEquals(404, response.getHttpStatus());
      verify(transferFapm, never()).setAutomaticWithdrawn(anyBoolean());
    }
  }

  /**
   * A <b>soft</b> disconnect (ETP-4764) deactivates the connection but keeps the Salt Edge link,
   * so the account stays reconnectable — which is the state the payment modal now blocks a transfer
   * on (ETP-4891). It must not write {@code Automatic Withdrawn} either.
   */
  @Test
  public void testSoftDisconnectDoesNotTouchAutomaticWithdrawn() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_PERMANENT_DELETION, false);
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getPSD2SaltEdgeAccountID()).thenReturn("SE-ACC-001");
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    FIN_PaymentMethod transferMethod = mock(FIN_PaymentMethod.class);
    FinAccPaymentMethod transferFapm = mock(FinAccPaymentMethod.class);
    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getConnectionStatus()).thenReturn("AC");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getLatestConnectionForFinAcc(finAcc))
          .thenReturn(connection);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.disconnectConnection(connection, false))
          .thenReturn(true);

      NeoResponse response = handler.handle(postContext(ACTION_DISCONNECT, body));

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("disconnected"));
      assertTrue(dataOf(response).getBoolean("reconnectable"));
      verify(transferFapm, never()).setAutomaticWithdrawn(anyBoolean());
      verify(transferMethod, never()).getName();
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Body for a <b>permanent</b> disconnect (the path that used to restore Automatic Withdrawn). */
  private static JSONObject disconnectBody() throws Exception {
    return new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_PERMANENT_DELETION, true);
  }

  private static JSONObject linkBody() throws Exception {
    return withImportSettings(new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_CONNECTION_ID, CONNECTION_ID)
        .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID));
  }

  private static JSONObject createAndLinkBody() throws Exception {
    return withImportSettings(new JSONObject()
        .put(PARAM_TYPE, "B")
        .put(PARAM_CONNECTION_ID, CONNECTION_ID)
        .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID));
  }

  /** ETP-6151: {@code link} and {@code createAndLink} require both import settings. */
  private static JSONObject withImportSettings(JSONObject body) throws Exception {
    return body.put("importFromDate", "2026-01-01").put("statementGrouping", "1BD");
  }

  private static JSONObject dataOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  /**
   * The text of an error answer ({@code error.message}).
   *
   * @param response the 4xx / 5xx answer
   * @return the message
   */
  private static String errorMessageOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  /**
   * The import settings {@code link} and {@code createAndLink} must refuse, as
   * {@code {importFromDate, statementGrouping, expected error text}} ({@code null} = key omitted).
   *
   * @return the cases
   */
  private static List<String[]> invalidImportSettings() {
    return List.of(
        new String[] {null, "1BD",
            BankImportSettingsRules.MSG_IMPORT_FROM_DATE_REQUIRED},
        new String[] {"2026-13-01", "1BD",
            BankImportSettingsRules.MSG_IMPORT_FROM_DATE_INVALID},
        new String[] {"2026-01-01", null,
            BankImportSettingsRules.MSG_STATEMENT_GROUPING_REQUIRED},
        new String[] {"2026-01-01", "MONTHLY",
            BankImportSettingsRules.MSG_STATEMENT_GROUPING_INVALID});
  }

  /**
   * Puts {@code value} under {@code key} unless it is {@code null} (the key is then omitted).
   *
   * @param body  the body to extend
   * @param key   the key
   * @param value the value, or {@code null}
   */
  private static void putIfNotNull(JSONObject body, String key, String value) throws Exception {
    if (value != null) {
      body.put(key, value);
    }
  }

  /**
   * Verifies that both import settings of {@link #withImportSettings} were written on
   * {@code finAcc}: import-from 2026-01-01 (UTC midnight) and grouping {@code 1BD}.
   *
   * @param finAcc the account the handler is linking
   */
  private static void verifyImportSettingsWritten(FIN_FinancialAccount finAcc) {
    verify(finAcc).setPSD2ImportFromDate(
        FinancialAccountBankConnectionSupport.parseDate("2026-01-01"));
    verify(finAcc).setPSD2StatementFrequency("1BD");
  }

  /**
   * Stubs what {@code link} needs past validation: the API key, the connection's accounts (which
   * contain {@link BankConnectionHandlerTestSupport#SALT_EDGE_ACCOUNT_ID}), its details, a link
   * answering {@link #LINK_WARNING}, and an empty payment-method sweep.
   *
   * @param finAcc the account being linked
   */
  private static void stubLinkHappyPath(MockedStatic<BankIntegrationUtils> utils,
      MockedStatic<SaltEdgeAccountLinkHelper> linkHelper, MockedStatic<OBDal> obDal,
      FIN_FinancialAccount finAcc) throws Exception {
    JSONObject details = new JSONObject().put("provider_name", "BBVA");
    linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc))
        .thenReturn(API_KEY);
    utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
        .thenReturn(new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID)));
    utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
        .thenReturn(details);
    linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(finAcc),
        eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any())).thenReturn(LINK_WARNING);
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    stubFinAccPaymentMethods(dal, Collections.emptyList());
  }

  /**
   * Makes the fetch-interval rule report that {@code finAcc}'s import-from date (2026-01-01, the
   * one of {@link #withImportSettings}) is beyond a 90-day history.
   *
   * @param support the open MockedStatic over the support class, with real methods
   * @param finAcc  the account whose advisory is evaluated
   */
  private static void stubFetchIntervalExceeded(
      MockedStatic<FinancialAccountBankConnectionSupport> support, FIN_FinancialAccount finAcc) {
    // Resolved outside the stubbing lambda: parseDate is a method of the class mocked here, so
    // calling it inside would be recorded as part of the stubbing.
    Date importFrom = FinancialAccountBankConnectionSupport.parseDate("2026-01-01");
    support.when(() -> FinancialAccountBankConnectionSupport.findImportFromBeyondFetchInterval(
        eq(finAcc), eq(importFrom)))
        .thenReturn(new FinancialAccountBankConnectionSupport.FetchIntervalExceeded(
            90, java.time.LocalDate.of(2026, 7, 21)));
  }

  /**
   * Asserts the REST-origin advisory envelope: one top-level {@code messages} entry
   * {@code {type: "warning", text}} quoting the 90 days, and none inside {@code response}.
   *
   * @param response the successful link answer
   */
  private static void assertTopLevelFetchIntervalWarning(NeoResponse response) throws Exception {
    JSONArray messages = response.getBody().getJSONArray("messages");
    assertEquals(1, messages.length());
    assertEquals("warning", messages.getJSONObject(0).getString("type"));
    String text = messages.getJSONObject(0).getString("text");
    assertTrue(text, text.contains("90"));
    assertFalse(response.getBody().getJSONObject("response").has("messages"));
  }

  /**
   * A mock account with the given stored import-from date and grouping.
   *
   * @param importFrom the stored import-from day ({@code yyyy-MM-dd}), or {@code null}
   * @param grouping   the stored grouping, or {@code null}
   * @return the account
   */
  private static FIN_FinancialAccount storedSettings(String importFrom, String grouping) {
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getPSD2ImportFromDate())
        .thenReturn(FinancialAccountBankConnectionSupport.parseDate(importFrom));
    when(finAcc.getPSD2StatementFrequency()).thenReturn(grouping);
    return finAcc;
  }

  /**
   * Runs import-settings with {@code body} on {@code finAcc} and asserts a 400 with
   * {@code expectedMessage}, with nothing written, saved or flushed.
   *
   * @param body            the request body
   * @param finAcc          the account the request resolves to
   * @param expectedMessage the exact error text
   */
  private void assertImportSettingsRejected(JSONObject body, FIN_FinancialAccount finAcc,
      String expectedMessage) throws Exception {
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.handle(postContext("import-settings", body));

      assertEquals(expectedMessage, 400, response.getHttpStatus());
      assertEquals(expectedMessage, errorMessageOf(response));
      verify(finAcc, never()).setPSD2ImportFromDate(any());
      verify(finAcc, never()).setPSD2ImportToDate(any());
      verify(finAcc, never()).setPSD2StatementFrequency(any());
      verify(dal, never()).save(any());
      verify(dal, never()).flush();
    }
  }

  /**
   * Runs {@code sync} on a found account whose fetch answers {@code fetchStatus} (after writing
   * "  PSD2 buffer  " to the PSD2 message buffer) in the given request language, and returns the
   * answer's data after checking it is a 200.
   *
   * @param fetchStatus the status {@code fetchAccountTransactionsWithResult} returns
   * @param language    the request language ({@code NeoLanguage.currentCode()})
   * @return the {@code response.data} of the answer
   */
  private JSONObject syncData(String fetchStatus, String language) throws Exception {
    return syncData(fetchStatus, false, language);
  }

  /**
   * Same as {@link #syncData(String, String)}, with the run's duplicate-check flag.
   *
   * @param fetchStatus          the status the sync returns
   * @param duplicateCheckFailed whether the "already imported?" check failed during the run
   * @param language             the request language
   * @return the {@code response.data} of the answer
   */
  private JSONObject syncData(String fetchStatus, boolean duplicateCheckFailed, String language)
      throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<NeoLanguage> lang = mockStatic(NeoLanguage.class);
        MockedStatic<AccountTransactionsSync> syncStatic =
            mockStatic(AccountTransactionsSync.class)) {
      stubObContext(obContext);
      lang.when(NeoLanguage::currentCode).thenReturn(language);
      syncStatic.when(() -> AccountTransactionsSync.fetchAccountTransactionsWithResult(eq(finAcc),
          any())).thenAnswer(invocation -> {
            StringBuilder sb = invocation.getArgument(1);
            sb.append("  PSD2 buffer  ");
            return new AccountTransactionsSync.SyncResult(fetchStatus, duplicateCheckFailed);
          });

      NeoResponse response = handler.handle(postContext("sync", body));

      assertEquals(200, response.getHttpStatus());
      syncStatic.verify(() -> AccountTransactionsSync.fetchAccountTransactionsWithResult(eq(finAcc),
          any()));
      return dataOf(response);
    }
  }

  /**
   * Stubs everything {@code createAndLink} needs to get past the Salt Edge fetches, the currency
   * lookup and the account creation, so a test can assert only what happens <i>after</i> the
   * account exists. Shared by the three {@code createAndLink} happy-path tests, whose setup is
   * otherwise byte-identical apart from the account {@code type} (Sonar duplication gate).
   *
   * @param type the account type {@code createAccount} is expected to be called with ({@code B},
   *     {@code C} or {@code CA}); the stub only matches that value, so a handler that passed a
   *     different type would get a {@code null} account back and fail the calling test
   */
  private static void stubCreateAndLinkHappyPath(MockedStatic<BankIntegrationUtils> utils,
      MockedStatic<FinancialAccountSupport> support,
      MockedStatic<SaltEdgeAccountLinkHelper> linkHelper, MockedStatic<OBDal> obDal,
      JSONArray nodes, JSONObject details, String type, Currency currency,
      FIN_FinancialAccount created) {
    utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
    utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
        .thenReturn(nodes);
    utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
        .thenReturn(details);
    support.when(() -> FinancialAccountSupport.findCurrencyByIsoCode("EUR")).thenReturn(currency);
    support.when(() -> FinancialAccountSupport.createAccount(any(), any(), eq(currency),
        anyString(), eq(type))).thenReturn(created);
    linkHelper.when(() -> SaltEdgeAccountLinkHelper.resolveConsentExpiresAt(any(), anyString()))
        .thenReturn(null);
    linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(created),
        eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any())).thenReturn("");

    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    stubFinAccPaymentMethods(dal, Collections.emptyList());
  }

  /**
   * Stubs {@code dal.createCriteria(FinAccPaymentMethod.class)} so any payment-method sweep reached
   * from {@code linkAccount} returns the given rows instead of hitting a real Hibernate session.
   * Every test that reaches {@code linkAccount} must call this (with an empty list when the
   * behavior is not under test), otherwise the unstubbed {@code OBDal.getInstance()} call blows up
   * and the handler's catch-all turns it into a 500.
   *
   * <p>Kept after ETP-5084 removed the multicurrency sweep that originally needed it: the stub is
   * cheap, and it is what would catch a reintroduced sweep instead of letting it NPE.
   */
  @SuppressWarnings("unchecked")
  private static OBCriteria<FinAccPaymentMethod> stubFinAccPaymentMethods(OBDal dal,
      List<FinAccPaymentMethod> methods) {
    OBCriteria<FinAccPaymentMethod> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(crit);
    when(crit.add(any())).thenReturn(crit);
    when(crit.list()).thenReturn(methods);
    return crit;
  }
}
