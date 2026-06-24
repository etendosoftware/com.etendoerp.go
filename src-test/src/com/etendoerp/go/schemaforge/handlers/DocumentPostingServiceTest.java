/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.HashMap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.erpCommon.ad_forms.AcctServer;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.financial.ResetAccounting;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

/**
 * Unit tests for {@link DocumentPostingService}.
 *
 * <p>The public {@code post(String, String)} method builds its own
 * {@link ConnectionProvider} (a real {@code DalConnectionProvider}), which would require a live
 * database. To keep these tests DB-free while genuinely exercising the post/commit/rollback logic,
 * the tests drive the package-private seam {@code post(String, String, ConnectionProvider)} with a
 * mocked {@link ConnectionProvider}. The public API stays exactly as specified.</p>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class DocumentPostingServiceTest {

  /** Stubs OBContext.getOBContext() to return client/org/user mocks with ids set. */
  private static void stubObContext(MockedStatic<OBContext> obc) {
    OBContext ctx = mock(OBContext.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    User user = mock(User.class);
    when(client.getId()).thenReturn("test-client-id");
    when(org.getId()).thenReturn("test-org-id");
    when(user.getId()).thenReturn("test-user-id");
    when(ctx.getCurrentClient()).thenReturn(client);
    when(ctx.getCurrentOrganization()).thenReturn(org);
    when(ctx.getUser()).thenReturn(user);
    obc.when(OBContext::getOBContext).thenReturn(ctx);
  }

  @Test
  public void postReturnsOkWhenAcctServerSucceeds() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = mock(AcctServer.class);
    acct.errors = 0;
    when(acct.post(eq("rec-1"), eq(false), any(), any(), any())).thenReturn(true);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class)) {
      stubObContext(obc);
      acctStatic
          .when(() -> AcctServer.get(eq("259"), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);

      DocumentPostingService.PostResult r = svc.post("259", "rec-1", conn);

      assertTrue(r.ok());
      // Success path must commit, not roll back.
      conn.releaseCommitConnection(con);
    }
  }

  @Test
  public void postReturnsFailureWhenAcctServerReportsErrors() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = mock(AcctServer.class);
    acct.errors = 2;
    when(acct.post(eq("rec-1"), eq(false), any(), any(), any())).thenReturn(true);
    OBError err = new OBError();
    err.setMessage("boom");
    when(acct.getMessageResult()).thenReturn(err);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class)) {
      stubObContext(obc);
      acctStatic
          .when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);

      DocumentPostingService.PostResult r = svc.post("259", "rec-1", conn);

      assertFalse(r.ok());
    }
  }

  @Test
  public void unpostReturnsOkWhenResetAccountingRuns() {
    DocumentPostingService svc = new DocumentPostingService();
    HashMap<String, Integer> counts = new HashMap<>();
    counts.put("deleted", 3);
    counts.put("updated", 1);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBContext> obc = mockStatic(OBContext.class)) {
      stubObContext(obc);
      ra.when(() -> ResetAccounting.delete(anyString(), anyString(), eq("259"), eq("rec-1"), eq(""), eq("")))
          .thenReturn(counts);

      DocumentPostingService.PostResult r = svc.unpost("259", "rec-1");

      assertTrue(r.ok());
    }
  }

  @Test
  public void handleActionReturnsNullForNonAction() {
    DocumentPostingService svc = new DocumentPostingService();
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);

    assertNull(svc.handleAction(ctx));
  }

  @Test
  public void postReturnsFailureWhenAcctServerIsNull() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(null);

      DocumentPostingService.PostResult r = svc.post("999", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("No accounting engine"));
    }
  }

  @Test
  public void postReturnsFailureOnConnectionException() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    when(conn.getTransactionConnection()).thenThrow(new RuntimeException("DB down"));

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class)) {
      stubObContext(obc);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
    }
  }

  @Test
  public void handleActionReturnsNullForUnknownActionName() {
    DocumentPostingService svc = new DocumentPostingService();
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("delete");

    assertNull(svc.handleAction(ctx));
  }

  @Test
  public void handleActionDelegatesPostAndReturns200() {
    DocumentPostingService svc = new DocumentPostingService() {
      @Override
      public PostResult post(String tableId, String recordId) {
        return new PostResult(true, "posted");
      }
    };

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(table.getId()).thenReturn("318");
    when(tab.getTable()).thenReturn(table);

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("post");
    when(ctx.getAdTab()).thenReturn(tab);
    when(ctx.getRecordId()).thenReturn("rec-1");

    NeoResponse resp = svc.handleAction(ctx);

    assertNotNull(resp);
    assertEquals(200, resp.getHttpStatus());
  }

  @Test
  public void handleActionDelegatesUnpostAndReturns200() {
    DocumentPostingService svc = new DocumentPostingService() {
      @Override
      public PostResult unpost(String tableId, String recordId) {
        return new PostResult(true, "unposted");
      }
    };

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(table.getId()).thenReturn("318");
    when(tab.getTable()).thenReturn(table);

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("unpost");
    when(ctx.getAdTab()).thenReturn(tab);
    when(ctx.getRecordId()).thenReturn("rec-1");

    NeoResponse resp = svc.handleAction(ctx);

    assertNotNull(resp);
    assertEquals(200, resp.getHttpStatus());
  }

  @Test
  public void unpostReturnsFailureOnException() {
    DocumentPostingService svc = new DocumentPostingService();

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBContext> obc = mockStatic(OBContext.class)) {
      stubObContext(obc);
      ra.when(() -> ResetAccounting.delete(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("DB error"));

      DocumentPostingService.PostResult r = svc.unpost("259", "rec-1");

      assertFalse(r.ok());
      assertNotNull(r.message());
    }
  }

  @Test
  public void postReturnsDefaultMessageWhenAcctServerResultIsNull() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = mock(AcctServer.class);
    acct.errors = 1;
    when(acct.post(anyString(), eq(false), any(), any(), any())).thenReturn(true);
    when(acct.getMessageResult()).thenReturn(null);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertEquals("Posting failed", r.message());
    }
  }

  @Test
  public void postReturnsDefaultMessageWhenAcctServerResultMessageIsEmpty() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = mock(AcctServer.class);
    acct.errors = 1;
    when(acct.post(anyString(), eq(false), any(), any(), any())).thenReturn(true);
    OBError emptyErr = new OBError();
    emptyErr.setMessage("");
    when(acct.getMessageResult()).thenReturn(emptyErr);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertEquals("Posting failed", r.message());
    }
  }

  @Test
  public void rollbackQuietlyIgnoresRollbackException() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);
    doThrow(new RuntimeException("rollback failed")).when(conn).releaseRollbackConnection(con);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenThrow(new RuntimeException("acct engine failed"));

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertNotNull(r.message());
    }
  }

  @Test
  public void handleActionReturns422WhenPostFails() {
    DocumentPostingService svc = new DocumentPostingService() {
      @Override
      public PostResult post(String tableId, String recordId) {
        return new PostResult(false, "Posting failed");
      }
    };

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(table.getId()).thenReturn("318");
    when(tab.getTable()).thenReturn(table);

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("post");
    when(ctx.getAdTab()).thenReturn(tab);
    when(ctx.getRecordId()).thenReturn("rec-1");

    NeoResponse resp = svc.handleAction(ctx);

    assertNotNull(resp);
    assertEquals(422, resp.getHttpStatus());
  }
}
