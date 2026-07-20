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
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.ORIGIN;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.PARAM_ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.PARAM_CONNECTION_ID;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.PARAM_TYPE;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.SALT_EDGE_ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.postContext;
import static com.etendoerp.go.schemaforge.Psd2HandlerTestSupport.stubObContext;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;

import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;

/**
 * Unit tests for the ETP-4503 multicurrency wiring in {@link FinancialAccountPsd2Handler}: when a
 * Bank account is connected to PSD2 (the {@code link} and {@code createAndLink} paths, both routed
 * through the private {@code linkAccount} choke point), the handler must disable multicurrency on
 * the account's bank-transfer payment-method link via
 * {@link FinancialAccountSupport#disableMulticurrencyForBankTransfer}. The {@code reconnect} path
 * must NOT — it does not establish a fresh link and leaves multicurrency untouched.
 *
 * <p>The transfer-link disabling logic itself is covered exhaustively in
 * {@link FinancialAccountSupportTest}; here we assert the handler's orchestration:
 * <ul>
 *   <li>{@code link}: exercised with the REAL helper (Bank account + a transfer link that is
 *       multicurrency-ON) so the transfer link ends OFF and a Cash link stays untouched.</li>
 *   <li>{@code createAndLink}: {@link FinancialAccountSupport} is mocked statically to assert the
 *       disable call is issued on the freshly created account.</li>
 *   <li>{@code reconnect}: {@link FinancialAccountSupport} is mocked statically to assert the disable
 *       call is never issued.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountPsd2HandlerMulticurrencyTest {

  private static final String ACTION_LINK = "link";
  private static final String ACTION_CREATE_AND_LINK = "createAndLink";
  private static final String ACTION_RECONNECT = "reconnect";
  private static final String CALLBACK = "/financial-account/psd2-callback";
  private static final String TYPE_BANK = "B";
  private static final String METHOD_TRANSFER = "Transferencia bancaria";
  private static final String METHOD_CASH = "Efectivo";

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

  /**
   * link on a Bank account whose transfer link is multicurrency-ON: the real helper turns both
   * multicurrency columns OFF on that link (transfer link ends {@code N}); a Cash link on the same
   * account is left untouched (stays {@code Y}). AC#2.
   */
  @Test
  public void testLinkDisablesMulticurrencyOnBankTransferLink() throws Exception {
    JSONObject body = linkBody();
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getId()).thenReturn(ACCOUNT_ID);
    when(finAcc.getType()).thenReturn(TYPE_BANK);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    JSONArray nodes = new JSONArray().put(new JSONObject().put("id", SALT_EDGE_ACCOUNT_ID));
    // Only provider_name → resolveProvider returns null (no setPsd2Provider), keeping this focused.
    JSONObject details = new JSONObject().put("provider_name", "BBVA");

    FIN_PaymentMethod transferMethod = mock(FIN_PaymentMethod.class);
    when(transferMethod.getName()).thenReturn(METHOD_TRANSFER);
    FinAccPaymentMethod transferLink = mock(FinAccPaymentMethod.class);
    when(transferLink.getPaymentMethod()).thenReturn(transferMethod);
    when(transferLink.isPayinIsMulticurrency()).thenReturn(Boolean.TRUE);
    when(transferLink.isPayoutIsMulticurrency()).thenReturn(Boolean.TRUE);

    FIN_PaymentMethod cashMethod = mock(FIN_PaymentMethod.class);
    when(cashMethod.getName()).thenReturn(METHOD_CASH);
    FinAccPaymentMethod cashLink = mock(FinAccPaymentMethod.class);
    when(cashLink.getPaymentMethod()).thenReturn(cashMethod);

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
      // Both disableAutomaticWithdrawnForTransferMethod and disableMulticurrencyForBankTransfer
      // iterate the account's FinAccPaymentMethod rows via the same criteria stub.
      stubFinAccPaymentMethods(dal, Arrays.asList(transferLink, cashLink));

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      verify(transferLink).setPayinIsMulticurrency(false);
      verify(transferLink).setPayoutIsMulticurrency(false);
      // Cash link is not a bank-transfer link → multicurrency left as-is (Y).
      verify(cashLink, never()).setPayinIsMulticurrency(anyBoolean());
      verify(cashLink, never()).setPayoutIsMulticurrency(anyBoolean());
    }
  }

  /**
   * createAndLink on a Bank account must issue the disable call on the freshly created account. With
   * {@link FinancialAccountSupport} mocked statically the disable is a no-op stub; the verification is
   * that the handler invokes it (via {@code linkAccount}).
   */
  @Test
  public void testCreateAndLinkInvokesDisableMulticurrency() throws Exception {
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
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeAccountsForConnection(CONNECTION_ID, API_KEY))
          .thenReturn(nodes);
      utils.when(() -> BankIntegrationUtils.getSaltEdgeConnectionDetails(CONNECTION_ID, API_KEY))
          .thenReturn(details);
      support.when(() -> FinancialAccountSupport.findCurrencyByIsoCode("EUR")).thenReturn(currency);
      support.when(() -> FinancialAccountSupport.createAccount(any(), any(), eq(currency),
          anyString(), eq(TYPE_BANK))).thenReturn(created);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.resolveConsentExpiresAt(any(), anyString()))
          .thenReturn(null);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(eq(created),
          eq(SALT_EDGE_ACCOUNT_ID), eq(CONNECTION_ID), any(), any())).thenReturn("");

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubFinAccPaymentMethods(dal, Collections.emptyList());

      NeoResponse response = handler.handle(postContext(ACTION_CREATE_AND_LINK, body));

      assertEquals(201, response.getHttpStatus());
      support.verify(() -> FinancialAccountSupport.disableMulticurrencyForBankTransfer(created));
    }
  }

  /**
   * reconnect must NOT touch multicurrency: it returns a reconnect URL without re-establishing a
   * link, so {@code disableMulticurrencyForBankTransfer} is never called.
   */
  @Test
  public void testReconnectDoesNotInvokeDisableMulticurrency() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);
    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getSaltEdgeConnection()).thenReturn(CONNECTION_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<RequestContext> requestContext = mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<FinancialAccountSupport> support = mockStatic(FinancialAccountSupport.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      stubOrigin(requestContext, ORIGIN);
      stubAnyConnection(obDal, connection);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.reconnectSaltEdgeConnection(eq(CONNECTION_ID),
          eq(API_KEY), eq(ORIGIN + CALLBACK), any()))
          .thenReturn("https://saltedge.example/reconnect");

      NeoResponse response = handler.handle(postContext(ACTION_RECONNECT, body));

      assertEquals(200, response.getHttpStatus());
      support.verify(() -> FinancialAccountSupport.disableMulticurrencyForBankTransfer(any()),
          never());
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
        .put(PARAM_TYPE, TYPE_BANK)
        .put(PARAM_CONNECTION_ID, CONNECTION_ID)
        .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID);
  }

  private static void stubOrigin(MockedStatic<RequestContext> requestContext, String origin) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Origin")).thenReturn(origin);
    RequestContext rc = mock(RequestContext.class);
    when(rc.getRequest()).thenReturn(request);
    requestContext.when(RequestContext::get).thenReturn(rc);
  }

  @SuppressWarnings("unchecked")
  private static void stubAnyConnection(MockedStatic<OBDal> obDal, FinaccConnection result) {
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    OBCriteria<FinaccConnection> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(FinaccConnection.class)).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(result);
  }

  @SuppressWarnings("unchecked")
  private static void stubFinAccPaymentMethods(OBDal dal, List<FinAccPaymentMethod> methods) {
    OBCriteria<FinAccPaymentMethod> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(crit);
    when(crit.add(any())).thenReturn(crit);
    when(crit.list()).thenReturn(methods);
  }
}
