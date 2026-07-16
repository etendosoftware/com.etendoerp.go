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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.advpaymentmngt.process.FIN_TransactionProcess;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.Costcenter;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.project.Project;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;
import com.etendoerp.payment.removal.util.TransactionRemovalUtil;

/**
 * Mockito-driven unit tests for the ETP-4500 lifecycle actions of
 * {@link FinancialAccountTransactionsHandler}: {@code process}, {@code reactivate}, {@code delete},
 * {@code post} (accounting) and {@code update}, plus the {@code create}-with-{@code process} and
 * accounting-dimension branches and the {@code processed}/FK-id mapping in the list loader.
 *
 * <p>Every action is driven through the public {@code handle(ctx)} entry point (real routing);
 * the Classic / payment-removal / posting layers are static- or construction-mocked so nothing
 * touches a live DB. Kept in a sibling class (rather than the 1400-line
 * {@link FinancialAccountTransactionsHandlerTest}) to avoid churning that file.
 */
// Silent runner: clearMocks() wipes the inline mock maker registry after each test to keep the
// shared single-JVM test heap flat; the strict runner would then fail its post-run inspection.
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountTransactionsLifecycleTest {

  private FinancialAccountTransactionsHandler handler;

  @Before
  public void setUp() {
    handler = new FinancialAccountTransactionsHandler();
  }

  /**
   * Releases the inline-mock references Mockito retains for every mock created in a test. Without
   * this they accumulate across the whole module suite (single test JVM) and push the fork past its
   * heap limit (OOM guard).
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── ctx / fixture helpers ────────────────────────────────────────────────

  /** Builds a POST {@code ?action=<action>} context carrying the given body. */
  private static NeoContext postActionCtx(String action, JSONObject body) {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("action", action);
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getQueryParams()).thenReturn(qp);
    when(ctx.getRequestBody()).thenReturn(body);
    return ctx;
  }

  /** A financial account with the client / organization / currency / id links create needs. */
  private static FIN_FinancialAccount mockAccount() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getClient()).thenReturn(mock(Client.class));
    when(account.getOrganization()).thenReturn(mock(Organization.class));
    when(account.getCurrency()).thenReturn(mock(Currency.class));
    when(account.getId()).thenReturn("acc-1");
    return account;
  }

  /** A JDBC connection whose single-row result set answers {@code nextLineNo} with {@code line}. */
  private static Connection mockNextLineConn(long line) throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getLong("next_line")).thenReturn(line);
    return conn;
  }

  /** A minimal request body that passes create validation (BPD deposit of 100). */
  private static JSONObject goodCreateBody() throws Exception {
    return new JSONObject()
        .put("FIN_Financial_Account_ID", "acc-1")
        .put("trxType", "BPD")
        .put("depositAmount", "100")
        .put("paymentAmount", "0")
        .put("description", "Sample");
  }

  // ─────────────────────────────────────────────────────────────────────────
  // action=process
  // ─────────────────────────────────────────────────────────────────────────

  /** A null body short-circuits to 400 before any DB access. */
  @Test
  public void testProcessNullBodyReturnsBadRequest() {
    assertEquals(400, handler.handle(postActionCtx("process", null)).getHttpStatus());
  }

  /** An unknown transaction id yields a 404. */
  @Test
  public void testProcessTransactionNotFoundReturns404() throws Exception {
    JSONObject body = new JSONObject().put("id", "missing");
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinaccTransaction.class), eq("missing"))).thenReturn(null);

      NeoResponse r = handler.handle(postActionCtx("process", body));
      assertEquals(404, r.getHttpStatus());
    }
  }

  /**
   * The happy path delegates to Classic's {@link FIN_TransactionProcess#doTransactionProcess} with
   * the {@code "P"} action and returns 200 with {@code response.data.success == true}.
   */
  @Test
  public void testProcessDelegatesToClassicAndReturnsSuccess() throws Exception {
    JSONObject body = new JSONObject().put("id", "tx-1");
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-1");
    when(trx.getStatus()).thenReturn("RPPC");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<FIN_TransactionProcess> txProc = mockStatic(FIN_TransactionProcess.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinaccTransaction.class), eq("tx-1"))).thenReturn(trx);

      NeoResponse r = handler.handle(postActionCtx("process", body));

      assertEquals(200, r.getHttpStatus());
      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertTrue(data.getBoolean("success"));
      assertEquals("tx-1", data.getString("id"));
      txProc.verify(() -> FIN_TransactionProcess.doTransactionProcess("P", trx));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // action=reactivate
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Reactivate delegates to the payment-removal module's
   * {@link TransactionRemovalUtil#reactivate(FIN_FinaccTransaction)} and returns 200.
   */
  @Test
  public void testReactivateDelegatesToRemovalUtil() throws Exception {
    JSONObject body = new JSONObject().put("id", "tx-1");
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-1");
    when(trx.getStatus()).thenReturn("RPAE");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<TransactionRemovalUtil> removal = mockStatic(TransactionRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      // Fetched once by loadTransactionFromBody and once again after reactivate — same trx.
      when(dal.get(eq(FIN_FinaccTransaction.class), anyString())).thenReturn(trx);

      NeoResponse r = handler.handle(postActionCtx("reactivate", body));

      assertEquals(200, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("response").getJSONObject("data").getBoolean("success"));
      removal.verify(() -> TransactionRemovalUtil.reactivate(trx));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // action=delete
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Deleting a DRAFT (not processed) transaction removes it directly via the DAL and never calls the
   * payment-removal module.
   */
  @Test
  public void testDeleteDraftRemovesDirectly() throws Exception {
    JSONObject body = new JSONObject().put("id", "tx-1");
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-1");
    when(trx.isProcessed()).thenReturn(false);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<TransactionRemovalUtil> removal = mockStatic(TransactionRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinaccTransaction.class), eq("tx-1"))).thenReturn(trx);

      NeoResponse r = handler.handle(postActionCtx("delete", body));

      assertEquals(200, r.getHttpStatus());
      verify(dal).remove(trx);
      verify(dal).flush();
      removal.verifyNoInteractions();
    }
  }

  /**
   * Deleting a PROCESSED transaction routes through the payment-removal module's
   * {@link TransactionRemovalUtil#reactivateAndRemove(String)} (undoing posting / reconciliation).
   */
  @Test
  public void testDeleteProcessedReactivatesAndRemoves() throws Exception {
    JSONObject body = new JSONObject().put("id", "tx-1");
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-1");
    when(trx.isProcessed()).thenReturn(true);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<TransactionRemovalUtil> removal = mockStatic(TransactionRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinaccTransaction.class), eq("tx-1"))).thenReturn(trx);

      NeoResponse r = handler.handle(postActionCtx("delete", body));

      assertEquals(200, r.getHttpStatus());
      removal.verify(() -> TransactionRemovalUtil.reactivateAndRemove("tx-1"));
      verify(dal, never()).remove(any());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // action=post (accounting)
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Posting delegates to {@link DocumentPostingService}; a successful {@code PostResult} maps to 200
   * with {@code response.data.success == true}.
   */
  @Test
  public void testPostAccountingSuccessReturns200() throws Exception {
    JSONObject body = new JSONObject().put("id", "tx-1");
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-1");

    Entity entity = mock(Entity.class);
    when(entity.getTableId()).thenReturn("table-1");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
         MockedConstruction<DocumentPostingService> posting = mockConstruction(
             DocumentPostingService.class,
             (m, c) -> when(m.post(any(), any()))
                 .thenReturn(new DocumentPostingService.PostResult(true, "Document posted")))) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinaccTransaction.class), eq("tx-1"))).thenReturn(trx);
      ModelProvider mp = mock(ModelProvider.class);
      modelProvider.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntity(anyString())).thenReturn(entity);

      NeoResponse r = handler.handle(postActionCtx("post", body));

      assertEquals(200, r.getHttpStatus());
      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertTrue(data.getBoolean("success"));
    }
  }

  /** A failed {@code PostResult} maps to 422. */
  @Test
  public void testPostAccountingFailureReturns422() throws Exception {
    JSONObject body = new JSONObject().put("id", "tx-1");
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-1");

    Entity entity = mock(Entity.class);
    when(entity.getTableId()).thenReturn("table-1");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
         MockedConstruction<DocumentPostingService> posting = mockConstruction(
             DocumentPostingService.class,
             (m, c) -> when(m.post(any(), any()))
                 .thenReturn(new DocumentPostingService.PostResult(false, "No accounting engine")))) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinaccTransaction.class), eq("tx-1"))).thenReturn(trx);
      ModelProvider mp = mock(ModelProvider.class);
      modelProvider.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntity(anyString())).thenReturn(entity);

      NeoResponse r = handler.handle(postActionCtx("post", body));

      assertEquals(422, r.getHttpStatus());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // action=update
  // ─────────────────────────────────────────────────────────────────────────

  /** A POSTED transaction is fully locked — update returns 400 and never persists. */
  @Test
  public void testUpdatePostedTransactionRejected() throws Exception {
    JSONObject body = new JSONObject().put("id", "tx-1").put("description", "changed");
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getPosted()).thenReturn("Y");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinaccTransaction.class), eq("tx-1"))).thenReturn(trx);

      NeoResponse r = handler.handle(postActionCtx("update", body));

      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("reactivate"));
      verify(dal, never()).save(any());
    }
  }

  /**
   * A PROCESSED (not posted) transaction only exposes the "safe" fields: G/L item, dimensions,
   * description and dates are applied, but amount / type / status stay locked, and {@code
   * process:true} is ignored (no Classic processing).
   */
  @Test
  public void testUpdateProcessedAppliesOnlyDimensions() throws Exception {
    JSONObject body = new JSONObject()
        .put("id", "tx-1")
        .put("description", "edited")
        .put("glItemId", "gl-1")
        .put("projectId", "pj-1")
        .put("costcenterId", "cc-1")
        .put("productId", "pr-1")
        .put("process", true);

    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-1");
    when(trx.getPosted()).thenReturn("N");
    when(trx.isProcessed()).thenReturn(true);

    GLItem gl = mock(GLItem.class);
    Project project = mock(Project.class);
    Costcenter cc = mock(Costcenter.class);
    Product product = mock(Product.class);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<FIN_TransactionProcess> txProc = mockStatic(FIN_TransactionProcess.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinaccTransaction.class), eq("tx-1"))).thenReturn(trx);
      when(dal.get(eq(GLItem.class), eq("gl-1"))).thenReturn(gl);
      when(dal.get(eq(Project.class), eq("pj-1"))).thenReturn(project);
      when(dal.get(eq(Costcenter.class), eq("cc-1"))).thenReturn(cc);
      when(dal.get(eq(Product.class), eq("pr-1"))).thenReturn(product);

      NeoResponse r = handler.handle(postActionCtx("update", body));

      assertEquals(200, r.getHttpStatus());
      // Safe (editable-while-processed) fields are applied.
      verify(trx).setDescription("edited");
      verify(trx).setGLItem(gl);
      verify(trx).setProject(project);
      verify(trx).setCostCenter(cc);
      verify(trx).setProduct(product);
      // Locked fields are never touched, and process:true is ignored.
      verify(trx, never()).setDepositAmount(any());
      verify(trx, never()).setStatus(anyString());
      verify(trx, never()).setTransactionType(anyString());
      txProc.verifyNoInteractions();
    }
  }

  /**
   * A DRAFT (not processed) transaction runs the full editable-fields path; {@code process:true}
   * then confirms it via Classic's {@link FIN_TransactionProcess}.
   */
  @Test
  public void testUpdateDraftAppliesAllFieldsAndProcesses() throws Exception {
    JSONObject body = new JSONObject()
        .put("id", "tx-1")
        .put("trxType", "BPD")
        .put("depositAmount", "50")
        .put("paymentAmount", "0")
        .put("process", true);

    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-1");
    when(trx.getPosted()).thenReturn("N");
    when(trx.isProcessed()).thenReturn(false);
    when(trx.getCurrency()).thenReturn(mock(Currency.class));

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<FIN_TransactionProcess> txProc = mockStatic(FIN_TransactionProcess.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinaccTransaction.class), eq("tx-1"))).thenReturn(trx);

      NeoResponse r = handler.handle(postActionCtx("update", body));

      assertEquals(200, r.getHttpStatus());
      verify(trx).setTransactionType("BPD");
      verify(trx).setDepositAmount(new java.math.BigDecimal("50"));
      verify(trx).setStatus("RPAE");
      txProc.verify(() -> FIN_TransactionProcess.doTransactionProcess("P", trx));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // create with process
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * {@code create} with {@code process:true} ("Confirmar") persists the transaction and then
   * processes it (Borrador → Procesado) via Classic. Also verifies the accounting-dimension
   * references and the {@code processed=false} flag applied by {@code applyEditableFields}.
   */
  @Test
  public void testCreateWithProcessAppliesDimensionsAndProcesses() throws Exception {
    JSONObject body = goodCreateBody()
        .put("projectId", "pj-1")
        .put("costcenterId", "cc-1")
        .put("productId", "pr-1")
        .put("process", true);

    FIN_FinancialAccount account = mockAccount();
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-new");
    when(trx.getTransactionType()).thenReturn("BPD");
    when(trx.getStatus()).thenReturn("RPAE");

    Project project = mock(Project.class);
    Costcenter cc = mock(Costcenter.class);
    Product product = mock(Product.class);
    Connection conn = mockNextLineConn(30L);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
         MockedStatic<FIN_TransactionProcess> txProc = mockStatic(FIN_TransactionProcess.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("acc-1"))).thenReturn(account);
      when(dal.get(eq(Project.class), eq("pj-1"))).thenReturn(project);
      when(dal.get(eq(Costcenter.class), eq("cc-1"))).thenReturn(cc);
      when(dal.get(eq(Product.class), eq("pr-1"))).thenReturn(product);
      when(dal.getConnection()).thenReturn(conn);

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinaccTransaction.class)).thenReturn(trx);

      NeoResponse r = handler.handle(postActionCtx("create", body));

      assertEquals(201, r.getHttpStatus());
      verify(trx).setProject(project);
      verify(trx).setCostCenter(cc);
      verify(trx).setProduct(product);
      verify(trx).setProcessed(false);
      txProc.verify(() -> FIN_TransactionProcess.doTransactionProcess("P", trx));
    }
  }

  /** {@code create} without {@code process} ("Guardar") leaves the transaction Draft — Classic is never called. */
  @Test
  public void testCreateWithoutProcessDoesNotProcess() throws Exception {
    JSONObject body = goodCreateBody(); // no "process" key

    FIN_FinancialAccount account = mockAccount();
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-new");
    when(trx.getTransactionType()).thenReturn("BPD");
    when(trx.getStatus()).thenReturn("RPAE");
    Connection conn = mockNextLineConn(10L);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
         MockedStatic<FIN_TransactionProcess> txProc = mockStatic(FIN_TransactionProcess.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("acc-1"))).thenReturn(account);
      when(dal.getConnection()).thenReturn(conn);

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinaccTransaction.class)).thenReturn(trx);

      NeoResponse r = handler.handle(postActionCtx("create", body));

      assertEquals(201, r.getHttpStatus());
      txProc.verifyNoInteractions();
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // loadEnabledDimensions — product ("PR") element mapping + order
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * The chart-of-accounts element type {@code PR} maps to the {@code product} UI dimension key
   * (guards the DIM_BY_ELEMENT "PR" → product + DIM_ORDER fix).
   */
  @Test
  public void testLoadEnabledDimensionsMapsProductElement() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("elementtype")).thenReturn("PR");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      JSONArray enabled = handler.loadEnabledDimensions("acc-1");

      boolean hasProduct = false;
      for (int i = 0; i < enabled.length(); i++) {
        if ("product".equals(enabled.getString(i))) hasProduct = true;
      }
      assertTrue("enabledDimensions must contain 'product' for element type PR", hasProduct);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // loadTransactions — processed flag + FK ids
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * A row maps the {@code processed_flag} column ("Y" → true) and the FK id columns
   * (glItemId / bpartnerId / projectId / costcenterId / productId) into the emitted JSON, so the
   * edit modal can prefill its selectors and drive the Borrador state.
   */
  @Test
  public void testLoadTransactionsMapsProcessedFlagAndFkIds() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_finacc_transaction_id")).thenReturn("TRX-9");
    when(rs.getString("processed_flag")).thenReturn("Y");
    when(rs.getString("gl_item_id")).thenReturn("gl-1");
    when(rs.getString("bpartner_id")).thenReturn("bp-1");
    when(rs.getString("project_id")).thenReturn("pj-1");
    when(rs.getString("costcenter_id")).thenReturn("cc-1");
    when(rs.getString("product_id")).thenReturn("pr-1");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      JSONObject row = handler.loadTransactions("acc-1").getJSONObject(0);

      assertTrue(row.getBoolean("processed"));
      assertEquals("gl-1", row.getString("glItemId"));
      assertEquals("bp-1", row.getString("bpartnerId"));
      assertEquals("pj-1", row.getString("projectId"));
      assertEquals("cc-1", row.getString("costcenterId"));
      assertEquals("pr-1", row.getString("productId"));
    }
  }

  /** The {@code processed_flag} column "N" maps to {@code processed == false}. */
  @Test
  public void testLoadTransactionsProcessedFlagFalseWhenNotY() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_finacc_transaction_id")).thenReturn("TRX-10");
    when(rs.getString("processed_flag")).thenReturn("N");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      JSONObject row = handler.loadTransactions("acc-1").getJSONObject(0);

      assertTrue(!row.getBoolean("processed"));
    }
  }
}
