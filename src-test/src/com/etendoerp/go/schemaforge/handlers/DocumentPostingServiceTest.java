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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.HashMap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.erpCommon.ad_forms.AcctSchema;
import org.openbravo.erpCommon.ad_forms.AcctServer;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.financial.ResetAccounting;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.businesspartner.CategoryAccounts;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductAccounts;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.procurement.ReceiptInvoiceMatch;

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

  /**
   * ETP-4706: when {@code AcctServer} fails with {@code STATUS_InvalidAccount} and no entity
   * detail (core Etendo's own generic fallback — see {@link DocumentPostingService}'s
   * {@code enrichWithFailingEntity} javadoc), the message must be enriched with the Business
   * Partner / BP Group resolved from {@code AcctServer.C_BPartner_ID} so a person diagnosing an
   * "account not configured" gap does not have to grep server logs to find them.
   */
  @Test
  public void postEnrichesInvalidAccountMessageWithBusinessPartnerDetail() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = mock(AcctServer.class);
    acct.errors = 1;
    acct.C_BPartner_ID = "bp-1";
    when(acct.post(anyString(), eq(false), any(), any(), any())).thenReturn(true);
    when(acct.getStatus()).thenReturn(AcctServer.STATUS_InvalidAccount);
    OBError err = new OBError();
    err.setMessage("Account could not be found.");
    when(acct.getMessageResult()).thenReturn(err);

    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("Fernet Branca S.A.");
    Category bpGroup = mock(Category.class);
    when(bpGroup.getName()).thenReturn("Proveedores Generales");
    when(bp.getBusinessPartnerCategory()).thenReturn(bpGroup);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(BusinessPartner.class, "bp-1")).thenReturn(bp);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      // Real AD_MESSAGE (ETGO_InvalidAccountBpAndGroup) catalog text — see AD_MESSAGE.xml.
      msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvalidAccountBpAndGroup"))
          .thenReturn("(Business Partner: @bpName@, BP Group: @bpGroup@)");

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("Account could not be found."));
      assertTrue(r.message().contains("Fernet Branca S.A."));
      assertTrue(r.message().contains("Proveedores Generales"));
      assertTrue(r.message()
          .contains("(Business Partner: Fernet Branca S.A., BP Group: Proveedores Generales)"));
    }
  }

  /**
   * ETP-4706: the Business Partner / BP Group enrichment text must be resolved from the
   * {@code AD_MESSAGE} catalog via {@code OBMessageUtils.messageBD} — the same pattern used by
   * sibling handlers ({@code PriceListHeaderHandler}, {@code AbstractInvoiceHeaderHandler}) —
   * instead of a hardcoded Java {@code String.format} literal. This is proven by mocking
   * {@code messageBD} to return templates that do NOT match the old literal English wording: if
   * the production code still built the string with {@code String.format}, this assertion would
   * fail because the mocked catalog text would never appear in the result.
   */
  @Test
  public void postEnrichesInvalidAccountMessageViaMessageCatalogWhenGroupPresent() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = mock(AcctServer.class);
    acct.errors = 1;
    acct.C_BPartner_ID = "bp-1";
    when(acct.post(anyString(), eq(false), any(), any(), any())).thenReturn(true);
    when(acct.getStatus()).thenReturn(AcctServer.STATUS_InvalidAccount);
    OBError err = new OBError();
    err.setMessage("Account could not be found.");
    when(acct.getMessageResult()).thenReturn(err);

    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("Fernet Branca S.A.");
    Category bpGroup = mock(Category.class);
    when(bpGroup.getName()).thenReturn("Proveedores Generales");
    when(bp.getBusinessPartnerCategory()).thenReturn(bpGroup);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(BusinessPartner.class, "bp-1")).thenReturn(bp);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvalidAccountBpAndGroup"))
          .thenReturn("[[CATALOG bp=@bpName@ group=@bpGroup@]]");

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("Account could not be found."));
      assertTrue(r.message().contains("[[CATALOG bp=Fernet Branca S.A. group=Proveedores Generales]]"));
    }
  }

  /** Same as above, for the no-group fallback message key. */
  @Test
  public void postEnrichesInvalidAccountMessageViaMessageCatalogWhenGroupAbsent() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = mock(AcctServer.class);
    acct.errors = 1;
    acct.C_BPartner_ID = "bp-1";
    when(acct.post(anyString(), eq(false), any(), any(), any())).thenReturn(true);
    when(acct.getStatus()).thenReturn(AcctServer.STATUS_InvalidAccount);
    OBError err = new OBError();
    err.setMessage("Account could not be found.");
    when(acct.getMessageResult()).thenReturn(err);

    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("Fernet Branca S.A.");
    when(bp.getBusinessPartnerCategory()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(BusinessPartner.class, "bp-1")).thenReturn(bp);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvalidAccountBpOnly"))
          .thenReturn("[[CATALOG bp=@bpName@]]");

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("Account could not be found."));
      assertTrue(r.message().contains("[[CATALOG bp=Fernet Branca S.A.]]"));
    }
  }

  /** Non-InvalidAccount failures already carry their own detailed message; leave them untouched. */
  @Test
  public void postDoesNotEnrichMessageWhenStatusIsNotInvalidAccount() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = mock(AcctServer.class);
    acct.errors = 1;
    acct.C_BPartner_ID = "bp-1";
    when(acct.post(anyString(), eq(false), any(), any(), any())).thenReturn(true);
    when(acct.getStatus()).thenReturn(AcctServer.STATUS_PeriodClosed);
    OBError err = new OBError();
    err.setMessage("Period is closed.");
    when(acct.getMessageResult()).thenReturn(err);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertEquals("Period is closed.", r.message());
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

  /**
   * ETP-4706 repro: a failed post must surface its real message as a flat top-level
   * {@code message} field, e.g. {@code {"success":false,"message":"Account could not be found."}}
   * — exactly the body NEO Headless returned for a Goods Receipt whose Business Partner Group was
   * missing its Not-Invoiced-Receipts account. Before the fix, {@code handleAction} passed
   * {@code body.toString()} into the {@code NeoResponse.error(int, String)} overload, which wraps
   * the whole JSON string as a nested {@code error.message} instead of sending the flat body — so
   * the frontend's plain {@code body.message} lookup found nothing and fell back to the HTTP
   * reason phrase ("Unprocessable Entity" for a 422), which is what actually reached the user
   * instead of the real accounting error.
   */
  @Test
  public void handleActionReturnsFlatMessageBodyWhenPostFails() throws Exception {
    DocumentPostingService svc = new DocumentPostingService() {
      @Override
      public PostResult post(String tableId, String recordId) {
        return new PostResult(false, "Account could not be found.");
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

    assertEquals(422, resp.getHttpStatus());
    assertFalse(resp.getBody().getBoolean("success"));
    assertEquals("Account could not be found.", resp.getBody().getString("message"));
  }

  /** Builds an {@code AcctServer} mock ready for the InvalidAccount + BP + BP Group enrichment path. */
  private static AcctServer stubAcctServerForBpGroupEnrichment() throws Exception {
    AcctServer acct = mock(AcctServer.class);
    acct.errors = 1;
    acct.C_BPartner_ID = "bp-1";
    when(acct.post(anyString(), eq(false), any(), any(), any())).thenReturn(true);
    when(acct.getStatus()).thenReturn(AcctServer.STATUS_InvalidAccount);
    OBError err = new OBError();
    err.setMessage("Account could not be found.");
    when(acct.getMessageResult()).thenReturn(err);
    return acct;
  }

  /** Stubs {@code OBDal.getInstance().get(BusinessPartner.class, "bp-1")} with a BP that has a BP Group. */
  private static BusinessPartner stubBusinessPartnerWithGroup(OBDal obDal) {
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("Fernet Branca S.A.");
    Category bpGroup = mock(Category.class);
    when(bpGroup.getId()).thenReturn("bp-group-1");
    when(bpGroup.getName()).thenReturn("Proveedores Generales");
    when(bp.getBusinessPartnerCategory()).thenReturn(bpGroup);
    when(obDal.get(BusinessPartner.class, "bp-1")).thenReturn(bp);
    return bp;
  }

  /** Stubs {@code OBDal.getInstance().get(BusinessPartner.class, "bp-1")} with a BP that has NO BP Group. */
  private static BusinessPartner stubBusinessPartnerWithoutGroup(OBDal obDal) {
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("Fernet Branca S.A.");
    when(bp.getBusinessPartnerCategory()).thenReturn(null);
    when(obDal.get(BusinessPartner.class, "bp-1")).thenReturn(bp);
    return bp;
  }

  /** Stubs the two message-catalog keys used by the BP+Group and missing-accounts enrichment. */
  private static void stubBpGroupAndMissingAccountsMessages(MockedStatic<OBMessageUtils> msgMock) {
    msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvalidAccountBpAndGroup"))
        .thenReturn("(Business Partner: @bpName@, BP Group: @bpGroup@)");
    msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvalidAccountMissingBpGroupAccounts"))
        .thenReturn("(Missing account setup on BP Group: @missingAccounts@)");
  }

  /** Mocks {@code OBDal.getInstance().createCriteria(CategoryAccounts.class)} to return {@code row}. */
  @SuppressWarnings("unchecked")
  private static void stubCategoryAccountsCriteria(OBDal obDal, CategoryAccounts row) {
    OBCriteria<CategoryAccounts> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(CategoryAccounts.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(row);
  }

  /** A {@code CategoryAccounts} row with all 6 curated columns configured (non-null). */
  private static CategoryAccounts fullyConfiguredCategoryAccounts() {
    CategoryAccounts row = mock(CategoryAccounts.class);
    AccountingCombination combo = mock(AccountingCombination.class);
    when(row.getNonInvoicedReceipts()).thenReturn(combo);
    when(row.getNonInvoicedReceivables()).thenReturn(combo);
    when(row.getCustomerReceivablesNo()).thenReturn(combo);
    when(row.getVendorLiability()).thenReturn(combo);
    when(row.getCustomerPrepayment()).thenReturn(combo);
    when(row.getVendorPrepayment()).thenReturn(combo);
    return row;
  }

  /**
   * ETP-5175: a fully-configured BP Group (all 6 curated {@code C_BP_Group_Acct} columns
   * non-null) must NOT add any "missing account setup" text — no behavior change from the
   * pre-existing BP+Group enrichment. Also proves {@code getVendorLiability()} participates in
   * the uniform check with the other 5 columns (it never fires for real data since the DB column
   * is {@code NOT NULL}, but the code path is exercised the same way for all 6).
   */
  @Test
  public void postDoesNotAddMissingAccountsDetailWhenAllCuratedColumnsAreConfigured() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);
    stubCategoryAccountsCriteria(obDal, fullyConfiguredCategoryAccounts());

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("(Business Partner: Fernet Branca S.A., BP Group: Proveedores Generales)"));
      assertFalse(r.message().contains("Missing account setup"));
    }
  }

  /**
   * ETP-5175: when exactly one curated column is unconfigured for the failing BP Group +
   * accounting schema, the message is enriched with that single column's EN label.
   */
  @Test
  public void postAddsMissingAccountsDetailForSingleMissingColumn() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);

    CategoryAccounts row = fullyConfiguredCategoryAccounts();
    when(row.getNonInvoicedReceipts()).thenReturn(null);
    stubCategoryAccountsCriteria(obDal, row);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("(Missing account setup on BP Group: Non-Invoiced Receipts)"));
    }
  }

  /**
   * ETP-5175: when several curated columns are unconfigured, all their EN labels are listed,
   * comma-separated, in the declared column order.
   */
  @Test
  public void postAddsMissingAccountsDetailForMultipleMissingColumns() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);

    CategoryAccounts row = fullyConfiguredCategoryAccounts();
    when(row.getNonInvoicedReceipts()).thenReturn(null);
    when(row.getVendorPrepayment()).thenReturn(null);
    stubCategoryAccountsCriteria(obDal, row);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message()
          .contains("(Missing account setup on BP Group: Non-Invoiced Receipts, Vendor Prepayment)"));
    }
  }

  /**
   * ETP-5175: when the {@code C_BP_Group_Acct} row does not exist at all for the BP Group +
   * accounting schema (no configuration whatsoever), all 6 curated column labels are reported as
   * missing, in declared order — including {@code getVendorLiability()} ("Vendor Liability"),
   * which is checked uniformly with the other 5 columns even though the underlying DB column is
   * {@code NOT NULL} in production and this specific null-check structurally cannot fire for real
   * data.
   */
  @Test
  public void postAddsMissingAccountsDetailForAllColumnsWhenNoCategoryAccountsRowExists() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);
    stubCategoryAccountsCriteria(obDal, null);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("(Missing account setup on BP Group: "
          + "Non-Invoiced Receipts, Non-Invoiced Receivables, Customer Receivables No., "
          + "Vendor Liability, Customer Prepayment, Vendor Prepayment)"));
    }
  }

  /**
   * ETP-5175: when the accounting schema cannot be resolved from {@code AcctServer.m_as} (left
   * unset by a mock, or an empty array), the missing-accounts lookup is skipped defensively — the
   * message stays exactly the pre-existing BP+Group-only text, and {@code OBDal.createCriteria} is
   * never even invoked.
   */
  @Test
  public void postSkipsMissingAccountsDetailWhenAcctSchemaIsUnresolvable() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    // acct.m_as intentionally left null (the mock's default) — schema cannot be resolved.

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvalidAccountBpAndGroup"))
          .thenReturn("(Business Partner: @bpName@, BP Group: @bpGroup@)");

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("(Business Partner: Fernet Branca S.A., BP Group: Proveedores Generales)"));
      assertFalse(r.message().contains("Missing account setup"));
      verify(obDal, never()).createCriteria(CategoryAccounts.class);
    }
  }

  /**
   * ETP-5175: an EMPTY (not just null) {@code acct.m_as} array must also be treated as
   * "schema unresolvable" — same defensive skip as the null case above, no
   * {@code ArrayIndexOutOfBoundsException}. Distinct scenario from
   * {@code postSkipsMissingAccountsDetailWhenAcctSchemaIsUnresolvable} (null array): this proves
   * the {@code length > 0} guard, not just the {@code != null} guard.
   */
  @Test
  public void postSkipsMissingAccountsDetailWhenAcctSchemaArrayIsEmpty() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    acct.m_as = new AcctSchema[0];

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvalidAccountBpAndGroup"))
          .thenReturn("(Business Partner: @bpName@, BP Group: @bpGroup@)");

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("(Business Partner: Fernet Branca S.A., BP Group: Proveedores Generales)"));
      assertFalse(r.message().contains("Missing account setup"));
      verify(obDal, never()).createCriteria(CategoryAccounts.class);
    }
  }

  /** Mocks {@code OBDal.getInstance().createCriteria(ProductAccounts.class)} to return {@code row}. */
  @SuppressWarnings("unchecked")
  private static void stubProductAccountsCriteria(OBDal obDal, ProductAccounts row) {
    OBCriteria<ProductAccounts> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(ProductAccounts.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(row);
  }

  /** A {@code ProductAccounts} row with both curated columns configured (non-null). */
  private static ProductAccounts fullyConfiguredProductAccounts() {
    ProductAccounts row = mock(ProductAccounts.class);
    AccountingCombination combo = mock(AccountingCombination.class);
    when(row.getProductExpense()).thenReturn(combo);
    when(row.getInvoicePriceVariance()).thenReturn(combo);
    return row;
  }

  /**
   * Stubs {@code OBDal.getInstance().get(ReceiptInvoiceMatch.class, matchInvId)} to return a
   * {@code M_MatchInv} whose product resolves to id {@code "product-1"}.
   */
  private static void stubReceiptInvoiceMatchWithProduct(OBDal obDal, String matchInvId) {
    Product product = mock(Product.class);
    when(product.getId()).thenReturn("product-1");
    ReceiptInvoiceMatch match = mock(ReceiptInvoiceMatch.class);
    when(match.getProduct()).thenReturn(product);
    when(obDal.get(ReceiptInvoiceMatch.class, matchInvId)).thenReturn(match);
  }

  /** Stubs the {@code M_Product_Acct} missing-accounts message-catalog key (ETP-5175). */
  private static void stubMissingProductAccountsMessage(MockedStatic<OBMessageUtils> msgMock) {
    msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvalidAccountMissingProductAccounts"))
        .thenReturn("(Missing account setup on Product: @missingAccounts@)");
  }

  /**
   * ETP-5175: on a Matched Purchase Invoice ({@code AcctServer.DOCTYPE_MatMatchInv}) whose
   * product accounting ({@code M_Product_Acct}) is fully configured, no "Missing account setup on
   * Product" text is added — no behavior change. Runs alongside a BP Group scenario to prove both
   * enrichments coexist correctly: the BP+Group detail is present, the product addendum is not.
   */
  @Test
  public void postDoesNotAddMissingProductAccountsDetailWhenFullyConfiguredOnMatchedPurchaseInvoice()
      throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    acct.DocumentType = AcctServer.DOCTYPE_MatMatchInv;
    acct.Record_ID = "matchinv-1";
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);
    stubCategoryAccountsCriteria(obDal, fullyConfiguredCategoryAccounts());
    stubReceiptInvoiceMatchWithProduct(obDal, "matchinv-1");
    stubProductAccountsCriteria(obDal, fullyConfiguredProductAccounts());

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);
      stubMissingProductAccountsMessage(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("(Business Partner: Fernet Branca S.A., BP Group: Proveedores Generales)"));
      assertFalse(r.message().contains("Missing account setup on Product"));
    }
  }

  /**
   * ETP-5175: when only {@code getInvoicePriceVariance()} is unconfigured on the {@code
   * M_Product_Acct} row, the addendum names that single column.
   */
  @Test
  public void postAddsMissingProductAccountsDetailForSingleMissingColumn() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    acct.DocumentType = AcctServer.DOCTYPE_MatMatchInv;
    acct.Record_ID = "matchinv-1";
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);
    stubCategoryAccountsCriteria(obDal, fullyConfiguredCategoryAccounts());
    stubReceiptInvoiceMatchWithProduct(obDal, "matchinv-1");

    ProductAccounts row = fullyConfiguredProductAccounts();
    when(row.getInvoicePriceVariance()).thenReturn(null);
    stubProductAccountsCriteria(obDal, row);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);
      stubMissingProductAccountsMessage(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("(Missing account setup on Product: Invoice Price Variance)"));
    }
  }

  /**
   * ETP-5175: when no {@code M_Product_Acct} row exists at all for the product + accounting
   * schema, both curated columns are reported missing, in declared order — mirroring the sibling
   * BP-Group "no row" behavior.
   */
  @Test
  public void postAddsMissingProductAccountsDetailForAllColumnsWhenNoProductAccountsRowExists()
      throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    acct.DocumentType = AcctServer.DOCTYPE_MatMatchInv;
    acct.Record_ID = "matchinv-1";
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);
    stubCategoryAccountsCriteria(obDal, fullyConfiguredCategoryAccounts());
    stubReceiptInvoiceMatchWithProduct(obDal, "matchinv-1");
    stubProductAccountsCriteria(obDal, null);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);
      stubMissingProductAccountsMessage(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message()
          .contains("(Missing account setup on Product: Product Expense, Invoice Price Variance)"));
    }
  }

  /**
   * ETP-5175 gate correctness: {@code M_Product_Acct} is a Matched-Purchase-Invoice-only check —
   * {@code DocMatchInv#createFact} is the only accounting doc handler that resolves these
   * columns. For any other document type (here {@code AcctServer.DOCTYPE_ARInvoice}), the product
   * lookup must not fire at all: not just absent from the message text, but the underlying DAL
   * calls ({@code get(ReceiptInvoiceMatch.class, ...)} / {@code createCriteria(ProductAccounts.class)})
   * genuinely never execute.
   */
  @Test
  public void postSkipsMissingProductAccountsDetailForNonMatchedPurchaseInvoiceDocType()
      throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    acct.DocumentType = AcctServer.DOCTYPE_ARInvoice;
    acct.Record_ID = "invoice-1";
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);
    stubCategoryAccountsCriteria(obDal, fullyConfiguredCategoryAccounts());

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);
      stubMissingProductAccountsMessage(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("(Business Partner: Fernet Branca S.A., BP Group: Proveedores Generales)"));
      assertFalse(r.message().contains("Missing account setup on Product"));
      verify(obDal, never()).get(ReceiptInvoiceMatch.class, "invoice-1");
      verify(obDal, never()).createCriteria(ProductAccounts.class);
    }
  }

  /**
   * ETP-5175 fail-closed: same regression class as {@code
   * postKeepsBpGroupDetailWhenMissingAccountsLookupThrows}, mirrored on the product-lookup side.
   * {@code resolveMissingProductAccountsDetail} runs UNCONDITIONALLY before the BP-Group branching
   * inside {@code resolveBusinessPartnerDetail}'s outer try — if it were not caught locally, a
   * thrown {@code M_MatchInv} lookup would discard the already-resolved BP + BP Group detail along
   * with it. Proves the outer detail survives and only the product addendum is skipped.
   */
  @Test
  public void postKeepsBpGroupDetailWhenMissingProductAccountsLookupThrows() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    acct.DocumentType = AcctServer.DOCTYPE_MatMatchInv;
    acct.Record_ID = "matchinv-1";
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);
    stubCategoryAccountsCriteria(obDal, fullyConfiguredCategoryAccounts());
    when(obDal.get(ReceiptInvoiceMatch.class, "matchinv-1")).thenThrow(new RuntimeException("DB error"));

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);
      stubMissingProductAccountsMessage(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("Account could not be found."));
      // Fixed pattern (mirrors the BP-Group-side fail-closed test): the already-built BP+Group
      // detail survives a failure in the product-accounts lookup, because that lookup fails
      // closed on its own instead of unwinding the outer try block.
      assertTrue(r.message().contains("Fernet Branca S.A."));
      assertTrue(r.message().contains("Proveedores Generales"));
      assertFalse(r.message().contains("Missing account setup on Product"));
    }
  }

  /**
   * ETP-5175 BUG FIX (QA finding, see QA report / BUG-1): {@code resolveMissingAccountsDetail}
   * used to run INSIDE the same outer {@code try} block in {@code resolveBusinessPartnerDetail}
   * that already built the BP+Group {@code detail} string, so a thrown {@code CategoryAccounts}
   * criteria query (e.g. a transient DB error, an OBDal/Hibernate mapping issue) discarded the
   * ALREADY-SUCCESSFULLY-BUILT BP+Group detail along with it — degrading the pre-existing,
   * working ETP-4706 enrichment (Business Partner name + BP Group name) down to the bare
   * accounting-engine message, solely because of a failure in the optional missing-accounts
   * lookup. {@code resolveMissingAccountsDetail} now fails closed on its own (catches locally and
   * returns {@code null}), so this proves the outer BP+Group detail survives and only the
   * missing-accounts addendum is skipped.
   */
  @Test
  public void postKeepsBpGroupDetailWhenMissingAccountsLookupThrows() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);
    when(obDal.createCriteria(CategoryAccounts.class)).thenThrow(new RuntimeException("DB error"));

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("Account could not be found."));
      // Fixed: the already-built BP+Group detail survives a failure in the missing-accounts
      // lookup, because that lookup now fails closed on its own instead of unwinding the outer
      // try block.
      assertTrue(r.message().contains("Fernet Branca S.A."));
      assertTrue(r.message().contains("Proveedores Generales"));
      assertFalse(r.message().contains("Missing account setup"));
    }
  }

  /**
   * ETP-5175 QA finding (Alex review W1): the {@code appendDetail(bpOnly, productAccountsDetail)}
   * branch in {@code resolveBusinessPartnerDetail} — "BP has NO BP Group" composed with "MXI
   * product accounts missing" — was a reachable code path with ZERO test coverage: every other
   * ETP-5175 test uses {@link #stubBusinessPartnerWithGroup}. Proves the two independent addenda
   * compose correctly even when the BP-Group one has nothing to contribute: the base message stays
   * the {@code ETGO_InvalidAccountBpOnly} template (no BP-Group text at all, since {@code
   * resolveMissingAccountsDetail} is never reached when {@code bpGroup == null}), with the product
   * addendum appended after it.
   */
  @Test
  public void postAddsMissingProductAccountsDetailOnBpOnlyMessageWhenGroupAbsent() throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    acct.DocumentType = AcctServer.DOCTYPE_MatMatchInv;
    acct.Record_ID = "matchinv-1";
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithoutGroup(obDal);
    stubReceiptInvoiceMatchWithProduct(obDal, "matchinv-1");

    ProductAccounts row = fullyConfiguredProductAccounts();
    when(row.getInvoicePriceVariance()).thenReturn(null);
    stubProductAccountsCriteria(obDal, row);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvalidAccountBpOnly"))
          .thenReturn("(Business Partner: @bpName@)");
      stubMissingProductAccountsMessage(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      // BP-only baseline (no BP Group / no "Missing account setup on BP Group" text at all)...
      assertTrue(r.message().contains("(Business Partner: Fernet Branca S.A.)"));
      assertFalse(r.message().contains("BP Group"));
      assertFalse(r.message().contains("Missing account setup on BP Group"));
      // ...plus the independent product-accounts addendum, appended after it.
      assertTrue(r.message().contains("(Missing account setup on Product: Invoice Price Variance)"));
      assertTrue(r.message().indexOf("(Business Partner: Fernet Branca S.A.)")
          < r.message().indexOf("(Missing account setup on Product:"));
    }
  }

  /**
   * ETP-5175 (Alex review suggestion, optional): proves both independent addenda can fire
   * SIMULTANEOUSLY on the same MXI failure — the BP has a BP Group AND that group is missing a
   * curated {@code C_BP_Group_Acct} column, AND the MXI product is also missing a curated {@code
   * M_Product_Acct} column. No existing test (pre- or post-ETP-5175) exercised this combination;
   * every other scenario keeps one side fully configured.
   */
  @Test
  public void postAddsBothBpGroupAndProductAccountsDetailWhenBothAreMissingSimultaneously()
      throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    acct.DocumentType = AcctServer.DOCTYPE_MatMatchInv;
    acct.Record_ID = "matchinv-1";
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);

    CategoryAccounts bpGroupRow = fullyConfiguredCategoryAccounts();
    when(bpGroupRow.getNonInvoicedReceipts()).thenReturn(null);
    stubCategoryAccountsCriteria(obDal, bpGroupRow);

    stubReceiptInvoiceMatchWithProduct(obDal, "matchinv-1");
    ProductAccounts productRow = fullyConfiguredProductAccounts();
    when(productRow.getInvoicePriceVariance()).thenReturn(null);
    stubProductAccountsCriteria(obDal, productRow);

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);
      stubMissingProductAccountsMessage(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("(Business Partner: Fernet Branca S.A., BP Group: Proveedores Generales)"));
      assertTrue(r.message().contains("(Missing account setup on BP Group: Non-Invoiced Receipts)"));
      assertTrue(r.message().contains("(Missing account setup on Product: Invoice Price Variance)"));
    }
  }

  /**
   * ETP-5175 adversarial edge case: a Matched Purchase Invoice ({@code acct.Record_ID}) that does
   * NOT resolve to any {@code ReceiptInvoiceMatch} at all (a deleted/orphaned record — a
   * data-integrity gap, not an accounting-setup gap) must degrade to "nothing reported", exactly
   * as {@link DocumentPostingService#resolveMissingProductAccounts} javadocs claim — not an
   * exception, and not a false-positive "all columns missing" report. {@code OBDal.get(...)}
   * returns {@code null} by default on an unstubbed mock, so this also proves the {@code
   * ProductAccounts} criteria query is never even attempted once the product cannot be resolved.
   */
  @Test
  public void postSkipsMissingProductAccountsDetailWhenReceiptInvoiceMatchDoesNotResolve()
      throws Exception {
    DocumentPostingService svc = new DocumentPostingService();

    ConnectionProvider conn = mock(ConnectionProvider.class);
    Connection con = mock(Connection.class);
    when(conn.getTransactionConnection()).thenReturn(con);

    AcctServer acct = stubAcctServerForBpGroupEnrichment();
    acct.DocumentType = AcctServer.DOCTYPE_MatMatchInv;
    acct.Record_ID = "orphaned-matchinv-1";
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getC_AcctSchema_ID()).thenReturn("schema-1");
    acct.m_as = new AcctSchema[] { schema };

    OBDal obDal = mock(OBDal.class);
    stubBusinessPartnerWithGroup(obDal);
    stubCategoryAccountsCriteria(obDal, fullyConfiguredCategoryAccounts());
    // obDal.get(ReceiptInvoiceMatch.class, "orphaned-matchinv-1") intentionally left unstubbed —
    // Mockito default is null, simulating a deleted/orphaned M_MatchInv record.

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<AcctServer> acctStatic = mockStatic(AcctServer.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubObContext(obc);
      acctStatic.when(() -> AcctServer.get(anyString(), anyString(), anyString(), any(ConnectionProvider.class)))
          .thenReturn(acct);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      stubBpGroupAndMissingAccountsMessages(msgMock);
      stubMissingProductAccountsMessage(msgMock);

      DocumentPostingService.PostResult r = svc.post("318", "rec-1", conn);

      assertFalse(r.ok());
      assertTrue(r.message().contains("(Business Partner: Fernet Branca S.A., BP Group: Proveedores Generales)"));
      assertFalse(r.message().contains("Missing account setup on Product"));
      verify(obDal, never()).createCriteria(ProductAccounts.class);
    }
  }
}
