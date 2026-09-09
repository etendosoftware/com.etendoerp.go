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
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_CONNECTION_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.SALT_EDGE_ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.postContext;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubObContext;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;


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
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;

import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;

/**
 * Regression guard for the ETP-5084 reversal in {@link FinancialAccountBankConnectionHandler}:
 * connecting a Bank account to its bank must leave the multicurrency flags on the account's
 * payment-method links completely untouched.
 *
 * <p>ETP-4503 used to clear {@code payin/payout_ismulticurrency} on the bank-transfer link at that
 * exact moment, on the premise that a transfer can only be instructed in the account's own currency
 * and therefore cannot settle a foreign invoice. ETP-5084 removed that: a PIS transfer converts the
 * invoice amount to the account currency before instructing the bank, so a cross-currency transfer
 * is a supported operation and the transfer link is multicurrency like every other payment method.
 * (Data-fix R29 re-enables it on accounts connected before the change.)
 *
 * <p>The test therefore asserts a NEGATIVE: neither the transfer link nor any other link is written
 * to. It is kept rather than deleted because the removed behavior was subtle and invisible from the
 * UI — a well-meaning reintroduction would silently break cross-currency transfers and
 * cross-currency reconciliation again.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountBankConnectionHandlerMulticurrencyTest {

  private static final String ACTION_LINK = "link";
  private static final String TYPE_BANK = "B";
  private static final String METHOD_TRANSFER = "Transferencia bancaria";
  private static final String METHOD_CASH = "Efectivo";

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

  /**
   * link on a Bank account whose transfer link is multicurrency-ON: the flags survive the
   * connection. Runs against the REAL FinancialAccountSupport (only OBDal is stubbed), so it would
   * fail the moment any disabling logic is reintroduced on this path.
   */
  @Test
  public void testLinkLeavesMulticurrencyUntouched() throws Exception {
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
      // disableAutomaticWithdrawnForTransferMethod (ETP-4891, still in force) iterates the
      // account's FinAccPaymentMethod rows through this same criteria stub.
      stubFinAccPaymentMethods(dal, Arrays.asList(transferLink, cashLink));

      NeoResponse response = handler.handle(postContext(ACTION_LINK, body));

      assertEquals(200, response.getHttpStatus());
      // ETP-5084: no link's multicurrency flags are written, in either direction, on either link.
      verify(transferLink, never()).setPayinIsMulticurrency(anyBoolean());
      verify(transferLink, never()).setPayoutIsMulticurrency(anyBoolean());
      verify(cashLink, never()).setPayinIsMulticurrency(anyBoolean());
      verify(cashLink, never()).setPayoutIsMulticurrency(anyBoolean());
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static JSONObject linkBody() throws Exception {
    return new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_CONNECTION_ID, CONNECTION_ID)
        .put("saltEdgeAccountId", SALT_EDGE_ACCOUNT_ID);
  }

  @SuppressWarnings("unchecked")
  private static void stubFinAccPaymentMethods(OBDal dal, List<FinAccPaymentMethod> methods) {
    OBCriteria<FinAccPaymentMethod> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(crit);
    when(crit.add(any())).thenReturn(crit);
    when(crit.list()).thenReturn(methods);
  }
}
