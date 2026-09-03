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
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.PARAM_PERMANENT_DELETION;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.postContext;
import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubObContext;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;

/**
 * Regression suite for ETP-5097 — "Borrar conexión bancaria" silently did nothing when run
 * against an account that had already been soft-disconnected.
 *
 * <p>Root cause: {@code handleDisconnect} used to resolve the connection through
 * {@link SaltEdgeAccountLinkHelper#disconnectFinancialAccount(FIN_FinancialAccount, boolean)},
 * whose internal lookup ({@code getActiveConnectionForFinAcc}) only matches a connection whose
 * local status is still {@code "AC"}. A soft disconnect leaves the connection in status
 * {@code "IN"}, so a subsequent permanent-deletion request against the same account found no
 * connection, deleted nothing, and still reported a {@code 200} success.
 *
 * <p>The fix resolves the connection with
 * {@link SaltEdgeAccountLinkHelper#getLatestConnectionForFinAcc} (matches any status) and
 * dispatches through {@link SaltEdgeAccountLinkHelper#disconnectConnection(FinaccConnection, boolean)}
 * — the exact entry point Classic's own "Disconnect Connection" process uses, which never
 * suffered this bug because it receives the {@link FinaccConnection} directly from the grid
 * instead of re-resolving it by status.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountBankConnectionHandlerDisconnectTest {

  private static final String ACTION_DISCONNECT = "disconnect";

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
   * ETP-5097 regression: a permanent-deletion request against an account whose connection is
   * already soft-disconnected (status {@code "IN"}) must still resolve the connection and delete
   * it. Before the fix, the handler called {@code disconnectFinancialAccount}, which never
   * reaches {@code disconnectConnection} at all in this scenario — so asserting that call is
   * exactly the seam that used to be silently skipped.
   */
  @Test
  public void testPermanentDeleteResolvesSoftDisconnectedConnectionAndDeletesIt() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_PERMANENT_DELETION, true);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getConnectionStatus()).thenReturn("IN");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getLatestConnectionForFinAcc(finAcc))
          .thenReturn(connection);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.disconnectConnection(connection, true))
          .thenReturn(false);

      NeoResponse response = handler.handle(postContext(ACTION_DISCONNECT, body));

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("disconnected"));
      linkHelper.verify(() -> SaltEdgeAccountLinkHelper.disconnectConnection(connection, true));
      linkHelper.verify(() -> SaltEdgeAccountLinkHelper.disconnectFinancialAccount(finAcc, true),
          never());
    }
  }

  /**
   * A soft-disconnect request repeated against a connection that is already inactive locally
   * must be a no-op — it must not re-send the Salt Edge "mark inactive" call on every click of an
   * already-deactivated account — while still reporting success and {@code reconnectable}.
   */
  @Test
  public void testSoftDisconnectOnAlreadyInactiveConnectionIsIdempotent() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_PERMANENT_DELETION, false);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    when(finAcc.getPSD2SaltEdgeAccountID()).thenReturn("SE-ACC-001");
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getConnectionStatus()).thenReturn("IN");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getLatestConnectionForFinAcc(finAcc))
          .thenReturn(connection);

      NeoResponse response = handler.handle(postContext(ACTION_DISCONNECT, body));

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("disconnected"));
      assertTrue(dataOf(response).getBoolean("reconnectable"));
      linkHelper.verify(() -> SaltEdgeAccountLinkHelper.disconnectConnection(connection, false),
          never());
    }
  }

  /**
   * When the {@link FinaccConnection} row is already gone but the Financial Account still
   * carries a stale Salt Edge link (e.g. a previous permanent delete that removed the row without
   * finishing the cleanup), a permanent request must clear the FA's PSD2 fields directly instead
   * of reporting there was nothing to do. Otherwise the account stays falsely
   * {@code reconnectable} forever and blocked from hard-delete by
   * {@code FinancialAccountDeleteSupport#hasBankConnection}.
   */
  @Test
  public void testPermanentDeleteCleansOrphanedLinkWhenConnectionRowAlreadyGone() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_PERMANENT_DELETION, true);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    String[] saltEdgeId = { "STALE-SE-ACC" };
    when(finAcc.getPSD2SaltEdgeAccountID()).thenAnswer(inv -> saltEdgeId[0]);
    doAnswer(inv -> {
      saltEdgeId[0] = inv.getArgument(0);
      return null;
    }).when(finAcc).setPSD2SaltEdgeAccountID(isNull());
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getLatestConnectionForFinAcc(finAcc))
          .thenReturn(null);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.handle(postContext(ACTION_DISCONNECT, body));

      assertEquals(200, response.getHttpStatus());
      assertTrue(dataOf(response).getBoolean("disconnected"));
      assertTrue(dataOf(response).getBoolean("permanent"));
      assertFalse(dataOf(response).getBoolean("reconnectable"));
      verify(finAcc).setPSD2SaltEdgeAccountID(null);
      verify(finAcc).setPSD2ConnectionStatus(BankIntegrationConstants.FA_CONNECTION_STATUS_DISCONNECTED);
      verify(finAcc).setPsd2Provider(null);
      verify(finAcc).setPSD2CardNumber(null);
      verify(dal, times(1)).save(finAcc);
      verify(dal, times(1)).flush();
    }
  }

  /**
   * If a permanent-deletion request leaves the Financial Account still linked afterward — the
   * operation did not actually complete for whatever reason — the handler must report a real
   * error. Before ETP-5097 this exact situation returned a false {@code 200} success.
   */
  @Test
  public void testPermanentDeleteReportsErrorWhenAccountStillLinkedAfterOperation() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_PERMANENT_DELETION, true);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    // Unaffected by the (mocked) disconnectConnection call — simulates an operation that did
    // not actually clear the link.
    when(finAcc.getPSD2SaltEdgeAccountID()).thenReturn("SE-ACC-STILL-LINKED");
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.getConnectionStatus()).thenReturn("AC");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getLatestConnectionForFinAcc(finAcc))
          .thenReturn(connection);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.disconnectConnection(connection, true))
          .thenReturn(false);

      NeoResponse response = handler.handle(postContext(ACTION_DISCONNECT, body));

      assertEquals(500, response.getHttpStatus());
    }
  }

  /** No connection record and no stale Salt Edge id: genuinely nothing to disconnect → 404. */
  @Test
  public void testDisconnectWithNoConnectionAndNoLinkReturns404() throws Exception {
    JSONObject body = new JSONObject()
        .put(PARAM_ACCOUNT_ID, ACCOUNT_ID)
        .put(PARAM_PERMANENT_DELETION, false);

    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    doReturn(finAcc).when(handler).loadAccount(ACCOUNT_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<SaltEdgeAccountLinkHelper> linkHelper =
            mockStatic(SaltEdgeAccountLinkHelper.class)) {
      stubObContext(obContext);
      linkHelper.when(() -> SaltEdgeAccountLinkHelper.getLatestConnectionForFinAcc(finAcc))
          .thenReturn(null);

      NeoResponse response = handler.handle(postContext(ACTION_DISCONNECT, body));

      assertEquals(404, response.getHttpStatus());
    }
  }

  private static JSONObject dataOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }
}
