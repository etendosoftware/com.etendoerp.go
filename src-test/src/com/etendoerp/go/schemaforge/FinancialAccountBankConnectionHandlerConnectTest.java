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
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.ORIGIN;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_ACCOUNT_ID;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.postContext;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubObContext;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

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
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;

import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;

/**
 * Unit tests for the {@link FinancialAccountBankConnectionHandler} POST {@code connect}, {@code reconnect}
 * and {@code disconnect} actions.
 *
 * <p>{@code connect} reads the request {@code Origin} header (via {@link RequestContext}) to build
 * the SPA {@code return_to} and preselects the account's {@code psd2Provider} when present. The
 * Salt Edge calls are delegated to static helpers, all mocked here; the DAL {@code loadAccount}
 * seam is stubbed on a spy.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountBankConnectionHandlerConnectTest {

  private static final String CALLBACK = "/financial-account/bank-connection-callback";
  private static final String ACTION_CONNECT = "connect";
  private static final String ACTION_RECONNECT = "reconnect";
  private static final String ACTION_DISCONNECT = "disconnect";
  private static final String CONNECT_URL = "https://saltedge.example/connect/abc";

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
   * connect with no account id builds {@code return_to} from the Origin header and preselects no
   * provider (null), returning the Salt Edge connect URL.
   */
  @Test
  public void testConnectWithoutAccountPreselectsNoProvider() throws Exception {
    JSONObject body = new JSONObject();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<RequestContext> requestContext = mockStatic(RequestContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      stubObContext(obContext);
      stubOrigin(requestContext, ORIGIN);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.createSaltEdgeConnection(eq(API_KEY),
          eq(ORIGIN + CALLBACK), isNull())).thenReturn(CONNECT_URL);

      NeoResponse response = handler.handle(postContext(ACTION_CONNECT, body));

      assertEquals(200, response.getHttpStatus());
      assertEquals(CONNECT_URL, dataOf(response).getString("connectUrl"));
    }
  }

  /**
   * connect with an account that already remembers a provider preselects that {@link Provider} so
   * the Salt Edge widget opens the bank's login directly.
   */
  @Test
  public void testConnectWithAccountPreselectsItsProvider() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);

    Provider provider = mock(Provider.class);
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getPsd2Provider()).thenReturn(provider);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<RequestContext> requestContext = mockStatic(RequestContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      stubObContext(obContext);
      stubOrigin(requestContext, ORIGIN);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.createSaltEdgeConnection(eq(API_KEY),
          eq(ORIGIN + CALLBACK), eq(provider))).thenReturn(CONNECT_URL);

      NeoResponse response = handler.handle(postContext(ACTION_CONNECT, body));

      assertEquals(200, response.getHttpStatus());
      assertEquals(CONNECT_URL, dataOf(response).getString("connectUrl"));
    }
  }

  /** A trailing slash on the Origin is stripped before the callback path is appended. */
  @Test
  public void testConnectStripsTrailingSlashFromOrigin() throws Exception {
    JSONObject body = new JSONObject();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<RequestContext> requestContext = mockStatic(RequestContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      stubObContext(obContext);
      stubOrigin(requestContext, ORIGIN + "/");
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.createSaltEdgeConnection(eq(API_KEY),
          eq(ORIGIN + CALLBACK), isNull())).thenReturn(CONNECT_URL);

      assertEquals(200, handler.handle(postContext(ACTION_CONNECT, body)).getHttpStatus());
    }
  }

  /** A missing Origin (and Referer) header surfaces as a 400 business error. */
  @Test
  public void testConnectMissingOriginReturns400() {
    JSONObject body = new JSONObject();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<RequestContext> requestContext = mockStatic(RequestContext.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      stubObContext(obContext);
      utils.when(() -> BankIntegrationUtils.getPsd2ApiKey(any())).thenReturn(API_KEY);
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getHeader("Origin")).thenReturn(null);
      when(request.getHeader("Referer")).thenReturn(null);
      RequestContext rc = mock(RequestContext.class);
      when(rc.getRequest()).thenReturn(request);
      requestContext.when(RequestContext::get).thenReturn(rc);

      assertEquals(400, handler.handle(postContext(ACTION_CONNECT, body)).getHttpStatus());
    }
  }

  /** reconnect returns the Salt Edge reconnect URL when the account has a connection. */
  @Test
  public void testReconnectReturnsReconnectUrl() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);
    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getSaltEdgeConnection()).thenReturn(CONNECTION_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<RequestContext> requestContext = mockStatic(RequestContext.class);
        MockedStatic<org.openbravo.dal.service.OBDal> obDal =
            mockStatic(org.openbravo.dal.service.OBDal.class);
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      stubOrigin(requestContext, ORIGIN);
      stubAnyConnection(obDal, connection);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)).thenReturn(API_KEY);
      utils.when(() -> BankIntegrationUtils.reconnectSaltEdgeConnection(eq(CONNECTION_ID),
          eq(API_KEY), eq(ORIGIN + CALLBACK), any())).thenReturn("https://saltedge.example/reconnect");

      NeoResponse response = handler.handle(postContext(ACTION_RECONNECT, body));

      assertEquals(200, response.getHttpStatus());
      assertEquals("https://saltedge.example/reconnect", dataOf(response).getString("reconnectUrl"));
    }
  }

  /** reconnect on an account with no connection is rejected with a 400. */
  @Test
  public void testReconnectWithoutConnectionReturns400() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<org.openbravo.dal.service.OBDal> obDal =
            mockStatic(org.openbravo.dal.service.OBDal.class)) {
      stubObContext(obContext);
      stubAnyConnection(obDal, null);

      assertEquals(400, handler.handle(postContext(ACTION_RECONNECT, body)).getHttpStatus());
    }
  }

  /** reconnect on a missing account resolves to a 404. */
  @Test
  public void testReconnectMissingAccountReturns404() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    doReturn(null).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(404, handler.handle(postContext(ACTION_RECONNECT, body)).getHttpStatus());
    }
  }

  /**
   * disconnect delegates to the helper and reports the boolean result. On a successful disconnect
   * (ETP-4406) the handler also restores {@code Automatic Withdrawn} on the account's transfer
   * payment method(s); this account has none, so that restore is a no-op — the empty
   * {@code FinAccPaymentMethod} criteria is stubbed so it does not hit a real Hibernate session.
   * The transfer-restore behavior itself is covered in {@code FinancialAccountBankConnectionHandlerLinkTest}.
   */
  @Test
  public void testDisconnectReturnsHelperResult() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.disconnectFinancialAccount(finAcc))
          .thenReturn(true);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubFinAccPaymentMethods(dal, Collections.emptyList());

      NeoResponse response = handler.handle(postContext(ACTION_DISCONNECT, body));

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("disconnected"));
    }
  }

  /** disconnect on a missing account resolves to a 404. */
  @Test
  public void testDisconnectMissingAccountReturns404() throws Exception {
    JSONObject body = new JSONObject().put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    doReturn(null).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(404, handler.handle(postContext(ACTION_DISCONNECT, body)).getHttpStatus());
    }
  }

  /** A POST with no request body short-circuits with a 400 (missing body). */
  @Test
  public void testDisconnectMissingBodyReturns400() {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      assertEquals(400, handler.handle(postContext(ACTION_DISCONNECT, null)).getHttpStatus());
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static void stubOrigin(MockedStatic<RequestContext> requestContext, String origin) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Origin")).thenReturn(origin);
    RequestContext rc = mock(RequestContext.class);
    when(rc.getRequest()).thenReturn(request);
    requestContext.when(RequestContext::get).thenReturn(rc);
  }

  @SuppressWarnings("unchecked")
  private static void stubAnyConnection(
      MockedStatic<org.openbravo.dal.service.OBDal> obDal, FinaccConnection result) {
    org.openbravo.dal.service.OBDal dal = mock(org.openbravo.dal.service.OBDal.class);
    obDal.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);
    org.openbravo.dal.service.OBCriteria<FinaccConnection> criteria =
        mock(org.openbravo.dal.service.OBCriteria.class);
    when(dal.createCriteria(FinaccConnection.class)).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(result);
  }

  /**
   * Stubs {@code dal.createCriteria(FinAccPaymentMethod.class)} so the ETP-4406 restore step run at
   * the end of a successful {@code disconnect} returns the given rows instead of hitting a real
   * Hibernate session. Pass an empty list when the transfer-restore behavior is not under test.
   */
  @SuppressWarnings("unchecked")
  private static void stubFinAccPaymentMethods(OBDal dal, List<FinAccPaymentMethod> methods) {
    OBCriteria<FinAccPaymentMethod> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(crit);
    when(crit.add(any())).thenReturn(crit);
    when(crit.list()).thenReturn(methods);
  }

  private static JSONObject dataOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }
}
