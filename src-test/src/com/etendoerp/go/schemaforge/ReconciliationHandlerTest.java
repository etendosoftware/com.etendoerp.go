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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;
import com.etendoerp.payment.removal.util.ReconciliationRemovalUtil;
import com.etendoerp.payment.removal.util.TransactionRemovalUtil;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.advpaymentmngt.dao.TransactionsDao;
import org.openbravo.advpaymentmngt.process.FIN_TransactionProcess;
import org.openbravo.advpaymentmngt.utility.FIN_MatchedTransaction;
import org.openbravo.advpaymentmngt.utility.FIN_MatchingTransaction;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.financial.ResetAccounting;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
import org.openbravo.model.financialmgmt.payment.MatchingAlgorithm;

/**
 * Mockito-driven unit tests for {@link ReconciliationHandler} (T6).
 *
 * <p>The handler exposes four custom action routes
 * ({@code pendingLines}, {@code candidates}, {@code reconcileGroup}, {@code reactivate}) and falls
 * through to the generic CRUD for any other request. Strategy: spy the handler
 * and stub the package-private DAL / Classic seams ({@code loadAccount},
 * {@code loadLine}, {@code loadTransaction}, {@code addNewDraftReconciliation},
 * {@code matchBankStatementLine}, {@code processReconciliation},
 * {@code accessibleOrgs}, {@code doRollbackAndClose}) so every path runs without
 * a database or a live OBContext. SQL-bound paths mock {@link OBDal} statically
 * and drive a fake {@link ResultSet}.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Routing: unknown method/action → null passthrough.</li>
 *   <li>pendingLines: empty + non-empty result; param binding (account, client,
 *       org array, optional date/text filters).</li>
 *   <li>candidates: suggested flag from the DAO; docType filter; no lineId → no
 *       suggestions.</li>
 *   <li>reconcileGroup: 1:1 + 1:N happy paths; wrong-account 400; sum-mismatch
 *       rejection; error rollback.</li>
 *   <li>reactivate: happy path; not-reconciled / closed-period / missing-body
 *       400; already-reconciled 409.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ReconciliationHandlerTest {

  private static final String ACC_ID = "acc-1";
  private static final String OTHER_ACC = "acc-2";
  private static final String LINE_ID = "line-1";
  private static final String CLIENT_ID = "client-1";
  private static final String ORG_ID = "org-1";

  private ReconciliationHandler handler;

  @Before
  public void setUp() {
    handler = spy(new ReconciliationHandler());
    doNothing().when(handler).doRollbackAndClose();
    // loadTolerances uses a raw JDBC connection unavailable in unit tests.
    // Stub it to return the default values (3 days, 0%) so every test that
    // exercises buildPendingLines / buildCandidates / buildAutoMatch stays
    // deterministic without setting up a second PreparedStatement mock.
    doReturn(new BigDecimal[]{BigDecimal.valueOf(3), BigDecimal.ZERO})
        .when(handler).loadTolerances(any());
  }

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── routing ────────────────────────────────────────────────────────────────

  private NeoContext getContext(String action, Map<String, String> extraParams) {
    Map<String, String> qp = new HashMap<>();
    if (action != null) {
      qp.put("action", action);
    }
    if (extraParams != null) {
      qp.putAll(extraParams);
    }
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("GET");
    when(context.getQueryParams()).thenReturn(qp);
    return context;
  }

  /** A request with no recognised action falls through to generic CRUD (null). */
  @Test
  public void testHandleUnknownActionReturnsNull() {
    assertNull(handler.handle(getContext("somethingElse", null)));
  }

  /** A POST without the reconcileGroup action falls through (null). */
  @Test
  public void testHandlePostWithoutActionReturnsNull() {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("POST");
    when(context.getQueryParams()).thenReturn(Collections.emptyMap());
    assertNull(handler.handle(context));
  }

  // ── pendingLines ─────────────────────────────────────────────────────────

  private void stubConnection(OBDal dal, PreparedStatement ps, ResultSet rs) throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(anyString(), any())).thenReturn(null);
    when(ps.executeQuery()).thenReturn(rs);
  }

  /**
   * pendingLines with no rows returns an empty list and a zero total.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testPendingLinesEmpty() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    // loadRules now goes through the DAL, not this mocked connection; stub the spy
    // seam so setString(1, ACC_ID) is only invoked once (by the main query).
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);
      // The envelope reports how many reconciliations of the account are already in draft (Core
      // allows one editable at a time), so the UI can warn up front that a "Reactivar" will confirm
      // it. Deliberately server-computed, NOT derived from `lines`.
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Arrays.asList(mock(FIN_Reconciliation.class), mock(FIN_Reconciliation.class)));

      NeoResponse response = handler.buildPendingLines(ACC_ID, CLIENT_ID,
          new HashSet<>(Arrays.asList(ORG_ID)), Collections.emptyMap());

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(0, data.getJSONArray("lines").length());
      assertEquals(0, new BigDecimal(data.getString("total")).compareTo(BigDecimal.ZERO));
      // Two drafts open → reported even though no LINE was returned by the filtered query.
      assertEquals(2, data.getInt("draftReconciliationCount"));
      // account + client + org array are always bound (3 params, no filters).
      verify(ps).setString(1, ACC_ID);
      verify(ps).setString(2, CLIENT_ID);
    }
  }

  /**
   * pendingLines with two rows returns both and sums their amounts.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testPendingLinesNonEmptySumsTotal() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString("fin_bankstatementline_id")).thenReturn("l1", "l2");
    when(rs.getTimestamp("datetrx")).thenReturn(null);
    when(rs.getString("description")).thenReturn("DESC1", "DESC2");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("100.00"), new BigDecimal("19.51"));

    // loadRules now goes through the DAL, not this mocked connection; stub the spy
    // seam so it does not consume the shared rs.next() sequence reserved for the main query.
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);
      // getDraftReconciliation left UNSTUBBED → returns null, exercising draftCount's null branch.
      NeoResponse response = handler.buildPendingLines(ACC_ID, CLIENT_ID,
          new HashSet<>(Arrays.asList(ORG_ID)), Collections.emptyMap());

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(2, data.getJSONArray("lines").length());
      assertEquals(0,
          new BigDecimal("119.51").compareTo(new BigDecimal(data.getString("total"))));
      assertEquals("pending", data.getJSONArray("lines").getJSONObject(0).getString("status"));
      // No drafts (null list) → 0, so the UI shows no up-front warning.
      assertEquals(0, data.getInt("draftReconciliationCount"));
    }
  }

  /**
   * The split sub-lines of a single 1:N reconciliation share a {@code match_group_id}; the handler
   * must collapse them into ONE line (amounts summed) and count the group as a single reconciled
   * entry. Both rows are reconciled, so the matching engine is short-circuited and never invoked.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testPendingLinesMergesMatchGroup() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    // Two split sub-lines of the SAME 1:N reconcile group.
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString("fin_bankstatementline_id")).thenReturn("l1", "l2");
    when(rs.getTimestamp("datetrx")).thenReturn(null);
    when(rs.getString("description")).thenReturn("Pago", "Pago");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("25.30"), new BigDecimal("25.30"));
    // Both reconciled → state short-circuits to "reconciled" (no classifyPendingLine call).
    when(rs.getString("line_status")).thenReturn("reconciled", "reconciled");
    // Same non-blank group id → the two rows must merge into the first occurrence.
    when(rs.getString("match_group_id")).thenReturn("G1", "G1");

    // loadRules now goes through the DAL, not this mocked connection; stub the spy
    // seam so it does not consume the shared rs.next() sequence reserved for the main query.
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        // The envelope's draftReconciliationCount goes through ReconciliationRemovalUtil; mock it so
        // the real criteria query is not attempted against the mocked OBDal.
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);

      NeoResponse response = handler.buildPendingLines(ACC_ID, CLIENT_ID,
          new HashSet<>(Arrays.asList(ORG_ID)), Collections.emptyMap());

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");

      // The two sub-lines collapse into a single merged line.
      JSONArray lines = data.getJSONArray("lines");
      assertEquals(1, lines.length());

      // Amounts summed: 25.30 + 25.30 == 50.60 (both on the merged line and on the response total).
      JSONObject merged = lines.getJSONObject(0);
      assertEquals(0, new BigDecimal("50.60").compareTo(new BigDecimal(merged.getString("amount"))));
      assertEquals(0, new BigDecimal("50.60").compareTo(new BigDecimal(data.getString("total"))));

      // The group is counted once as reconciled, not twice.
      assertEquals(1, data.getJSONObject("counts").getInt("reconciled"));
      assertEquals("reconciled", merged.getString("status"));
    }
  }

  /**
   * Optional dateFrom/dateTo/q filters bind extra params after the base three.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testPendingLinesBindsOptionalFilters() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    Map<String, String> filters = new HashMap<>();
    filters.put("dateFrom", "2026-01-01");
    filters.put("dateTo", "2026-12-31");
    filters.put("q", "DHL");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        // The envelope's draftReconciliationCount goes through ReconciliationRemovalUtil; mock it so
        // the real criteria query is not attempted against the mocked OBDal.
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);
      // loadRules now goes through the DAL, not this mocked connection; stub the spy seam.
      doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

      handler.buildPendingLines(ACC_ID, CLIENT_ID, new HashSet<>(Arrays.asList(ORG_ID)), filters);

      // base: account(1), client(2); date range binds positions 4 and 5; text 6.
      verify(ps).setDate(eq(4), any());
      verify(ps).setDate(eq(5), any());
      verify(ps).setString(6, "%dhl%");
    }
  }

  // ── pendingLines: draftReconciliationCount (ETP-4502 "Reactivar" warning) ──────
  // Core allows only ONE editable (draft) reconciliation per account, so a non-zero count means the
  // next "Reactivar" will first CONFIRM that draft. The envelope reports it so the confirm dialog can
  // warn up front. Deliberately server-computed via ReactivationSupport.draftCount(account) and NOT
  // derived from the returned `lines` (those are date/status filtered, so an off-screen draft would be
  // missed). The 2-drafts case is asserted by testPendingLinesEmpty and the null-list case by
  // testPendingLinesNonEmptySumsTotal; the two boundaries below complete the helper's branches.

  /**
   * An account with NO open draft (Core returns an empty list, the normal steady state) reports 0, so
   * the UI shows the plain confirm dialog with no warning. Distinct from the {@code null} case: here
   * the lookup succeeded and genuinely found nothing.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testPendingLinesDraftCountZeroWhenNoDraftsOpen() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    // loadRules now goes through the DAL, not this mocked connection; stub the spy seam
    // so it does not consume the shared rs.next() sequence reserved for the main query.
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      NeoResponse response = handler.buildPendingLines(ACC_ID, CLIENT_ID,
          new HashSet<>(Arrays.asList(ORG_ID)), Collections.emptyMap());

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(0, data.getInt("draftReconciliationCount"));
    }
  }

  /**
   * The count is DECORATIVE: when the draft lookup blows up, {@code draftCount} swallows it and
   * degrades to 0 rather than failing the whole pendingLines response — the panel is the main screen
   * of the window and must still render. Asserts both halves: the count is 0 AND the real payload
   * ({@code lines} / {@code total} / {@code counts}) is fully intact.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testPendingLinesDraftCountFailureDegradesToZeroKeepingPayload() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_bankstatementline_id")).thenReturn("l1");
    when(rs.getTimestamp("datetrx")).thenReturn(null);
    when(rs.getString("description")).thenReturn("DESC1");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("42.00"));
    when(rs.getString("line_status")).thenReturn("PENDING");
    when(rs.getString("match_group_id")).thenReturn("");

    // draftCount logs account.getId() on its degradation path, so the account must be a real object.
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    // loadRules now goes through the DAL, not this mocked connection; stub the spy seam
    // so it does not consume the shared rs.next() sequence reserved for the main query.
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenThrow(new OBException("Draft reconciliation lookup failed"));

      NeoResponse response = handler.buildPendingLines(ACC_ID, CLIENT_ID,
          new HashSet<>(Arrays.asList(ORG_ID)), Collections.emptyMap());

      // The response still succeeds — the failure never escapes draftCount.
      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(0, data.getInt("draftReconciliationCount"));

      // ...and the payload the panel actually needs is untouched.
      assertEquals(1, data.getJSONArray("lines").length());
      assertEquals("l1", data.getJSONArray("lines").getJSONObject(0).getString("id"));
      assertEquals(0, new BigDecimal("42.00").compareTo(new BigDecimal(data.getString("total"))));
      JSONObject counts = data.getJSONObject("counts");
      assertEquals(1, counts.getInt("all"));
      assertEquals(1, counts.getInt("pending"));
    }
  }

  // ── candidates ─────────────────────────────────────────────────────────────

  /**
   * candidates marks the transactions the DAO suggests for the selected line.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testCandidatesMarksSuggested() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString("fin_finacc_transaction_id")).thenReturn("t1", "t2");
    when(rs.getTimestamp("statementdate")).thenReturn(null);
    when(rs.getString("document_no")).thenReturn("PAY-1", "PAY-2");
    when(rs.getString("partner_name")).thenReturn("DHL", "ACME");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("50.00"), new BigDecimal("75.00"));

    // t1 is suggested, t2 is not.
    doReturn(new HashSet<>(Arrays.asList("t1"))).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), eq(LINE_ID), anyInt());

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);

      // buildCandidates now resolves the account for the ownership gate (ETP-4950).
      doReturn(mock(FIN_FinancialAccount.class)).when(handler).loadAccount(ACC_ID);
      NeoResponse response = handler.buildCandidates(ACC_ID, LINE_ID, null, null, null);

      JSONArray candidates =
          response.getBody().getJSONObject("response").getJSONObject("data").getJSONArray("candidates");
      assertEquals(2, candidates.length());
      assertTrue(candidates.getJSONObject(0).getBoolean("suggested"));
      assertFalse(candidates.getJSONObject(1).getBoolean("suggested"));
      assertEquals("pending", candidates.getJSONObject(0).getString("status"));
    }
  }

  /**
   * A docType filter binds the isreceipt flag as an extra SQL parameter.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testCandidatesDocTypeFilterBindsIsReceipt() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);
    doReturn(new HashSet<String>()).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), eq(LINE_ID), anyInt());

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);

      // buildCandidates now resolves the account for the ownership gate (ETP-4950).
      doReturn(mock(FIN_FinancialAccount.class)).when(handler).loadAccount(ACC_ID);
      handler.buildCandidates(ACC_ID, LINE_ID, "payments", null, null);

      // The account id is the first bound parameter these days, followed by the four
      // date-range bounds and then the docType flag. There is no leading reconciliation id
      // parameter any more, since the old draftRec branch of the candidates query is gone.
      verify(ps).setString(1, ACC_ID);
      verify(ps).setString(6, "N");
    }
  }

  /** Without a lineId no transactions are flagged suggested. */
  @Test
  public void testSuggestedTransactionIdsNoLineReturnsEmpty() {
    assertTrue(handler.suggestedTransactionIds(ACC_ID, null).isEmpty());
    assertTrue(handler.suggestedTransactionIds(ACC_ID, "").isEmpty());
  }

  /**
   * When the account has no matching algorithm configured, the standard-algorithm path is skipped
   * and no suggestion is produced (graceful, no crash) — the Classic algorithm is never bypassed
   * with relaxed criteria.
   */
  @Test
  public void testSuggestedTransactionIdsNoAlgorithmReturnsEmpty() {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getMatchingAlgorithm()).thenReturn(null);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_BankStatementLine.class, LINE_ID)).thenReturn(line);

      Set<String> ids = handler.suggestedTransactionIds(ACC_ID, LINE_ID);
      assertTrue(ids.isEmpty());
    }
  }

  // ── reconcileGroup ─────────────────────────────────────────────────────────

  private FIN_BankStatementLine lineFor(String accountId, BigDecimal credit, BigDecimal debit,
      FIN_FinaccTransaction matched) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    FIN_BankStatement bs = mock(FIN_BankStatement.class);
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getId()).thenReturn(accountId);
    when(bs.getAccount()).thenReturn(acc);
    when(line.getBankStatement()).thenReturn(bs);
    when(line.getCramount()).thenReturn(credit);
    when(line.getDramount()).thenReturn(debit);
    when(line.getFinancialAccountTransaction()).thenReturn(matched);
    return line;
  }

  private FIN_BankStatementLine groupedLine(String id, FIN_BankStatement statement, String groupId,
      BigDecimal credit, BigDecimal debit, FIN_FinaccTransaction matched) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getBankStatement()).thenReturn(statement);
    when(line.getCramount()).thenReturn(credit);
    when(line.getDramount()).thenReturn(debit);
    when(line.getFinancialAccountTransaction()).thenReturn(matched);
    when(line.get("matchGroupId")).thenReturn(groupId);
    return line;
  }

  private MockedStatic<ModelProvider> mockMatchGroupProperty() {
    MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class);
    ModelProvider provider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    mp.when(ModelProvider::getInstance).thenReturn(provider);
    when(provider.getEntity(FIN_BankStatementLine.ENTITY_NAME)).thenReturn(entity);
    when(entity.getPropertyByColumnName(eq("EM_ETGO_Match_Group_ID"), eq(false))).thenReturn(prop);
    when(prop.getName()).thenReturn("matchGroupId");
    return mp;
  }

  private FIN_FinaccTransaction trxFor(String accountId, BigDecimal deposit, BigDecimal payment,
      FIN_Reconciliation reconciliation) {
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getId()).thenReturn(accountId);
    when(trx.getAccount()).thenReturn(acc);
    when(trx.getDepositAmount()).thenReturn(deposit);
    when(trx.getPaymentAmount()).thenReturn(payment);
    when(trx.getReconciliation()).thenReturn(reconciliation);
    return trx;
  }

  private JSONObject reconcileBody(String accountId, String lineId, String... opIds)
      throws Exception {
    JSONArray ops = new JSONArray();
    for (String id : opIds) {
      ops.put(id);
    }
    return new JSONObject()
        .put("financialAccountId", accountId)
        .put("statementLineId", lineId)
        .put("operationIds", ops);
  }

  private void stubReconciliationCompose(FIN_Reconciliation rec, String type) throws Exception {
    doReturn(rec).when(handler).addNewDraftReconciliation(any());
    // applySuggestions shares ONE reconciliation across the whole batch via
    // getOrCreateDraftReconciliation (T1 batch-header refactor) instead of calling
    // addNewDraftReconciliation directly per group; stub both seams so this helper still covers
    // both the manual reconcileGroup→compose path and the applySuggestions→batch path.
    doReturn(rec).when(handler).getOrCreateDraftReconciliation(any());
    doNothing().when(handler).matchBankStatementLine(any(), any(), any());
    OBError result = mock(OBError.class);
    when(result.getType()).thenReturn(type);
    when(result.getMessage()).thenReturn("msg");
    doReturn(result).when(handler).processReconciliation(rec);
  }

  /**
   * A 1:1 match whose amounts agree composes the services and returns 201.
   *
   * @throws Exception if building the reconcile body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupHappy1to1() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-1");
    when(rec.getEndingBalance()).thenReturn(new BigDecimal("100.00"));

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    stubReconciliationCompose(rec, "Success");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals("rec-1", data.getString("reconciliationId"));
    assertEquals(1, data.getJSONArray("lineIds").length());
    verify(handler).matchBankStatementLine(eq(line), any(), eq(rec));
    // A single operation produces no split worth grouping → no match-group tag.
    verify(handler, never()).tagMatchGroup(any());
  }

  /**
   * A 1:N match whose operations sum exactly to the line amount returns 201.
   *
   * @throws Exception if building the reconcile body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupHappy1toN() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("150.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t1 = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t2 = trxFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-2");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(t1).when(handler).loadTransaction("t1");
    doReturn(t2).when(handler).loadTransaction("t2");
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1", "t2"));

    assertEquals(201, response.getHttpStatus());
    // 1:N reconcile tags the original line so the split sub-lines inherit the group id.
    verify(handler).tagMatchGroup(line);
  }

  /**
   * Regression for the "Ejemplo 100" bug: a SINGLE operation that only partially covers the
   * line (e.g. a 100.00 line matched to a lone 53.24 invoice/transaction) still makes Core split
   * the line into a reconciled portion plus a pending remainder — so it must be tagged with a
   * match-group id just like a 1:N match, even though {@code operationIds.size() == 1}.
   *
   * @throws Exception if building the reconcile body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupSingleOperationPartialMatchTagsMatchGroup() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("53.24"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-3");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(201, response.getHttpStatus());
    // A single-operation PARTIAL match still splits the line in Core → must be tagged.
    verify(handler).tagMatchGroup(line);
  }

  /**
   * {@code willSplitLine} never splits when there is nothing to match against.
   */
  @Test
  public void testWillSplitLineEmptyOperationsReturnsFalse() {
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);

    assertFalse(handler.willSplitLine(line, java.util.Collections.emptyList()));
  }

  /**
   * A single operation id that no longer resolves to a transaction (e.g. stale/removed) is
   * treated as a zero-amount operation, so a non-zero line is considered a partial match that
   * will split.
   */
  @Test
  public void testWillSplitLineSingleOperationMissingTransactionTreatedAsPartial() {
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    doReturn(null).when(handler).loadTransaction("t-missing");

    assertTrue(handler.willSplitLine(line, java.util.Collections.singletonList("t-missing")));
  }

  /**
   * An operation that belongs to another account is rejected with a 400.
   *
   * @throws Exception if building the reconcile body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupWrongAccountReturns400() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(OTHER_ACC, new BigDecimal("100.00"), BigDecimal.ZERO, null);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).addNewDraftReconciliation(any());
  }

  /**
   * Operations that EXCEED the line amount are rejected with a 400 (over-reconciliation is not
   * supported). Operations summing to LESS than the line are allowed as a partial match.
   *
   * @throws Exception if building the reconcile body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupSumMismatchReturns400() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    // Operation is 130.00 → exceeds the 100.00 line → reject (over-reconciliation).
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("130.00"), BigDecimal.ZERO, null);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("exceed the statement line amount"));
    verify(handler, never()).addNewDraftReconciliation(any());
  }

  /**
   * An operation already linked to a reconciliation is rejected with a 409.
   *
   * @throws Exception if building the reconcile body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupAlreadyReconciledReturns409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation existing = mock(FIN_Reconciliation.class);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, existing);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(409, response.getHttpStatus());
  }

  /**
   * A statement line that is already reconciled is rejected with a 409.
   *
   * @throws Exception if building the reconcile body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupLineAlreadyReconciledReturns409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_FinaccTransaction alreadyMatched = mock(FIN_FinaccTransaction.class);
    FIN_BankStatementLine line =
        lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, alreadyMatched);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(409, response.getHttpStatus());
  }

  /**
   * An unknown statement line yields a 404.
   *
   * @throws Exception if building the reconcile body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupMissingLineReturns404() throws Exception {
    doReturn(mock(FIN_FinancialAccount.class)).when(handler).loadAccount(ACC_ID);
    doReturn(null).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(404, response.getHttpStatus());
  }

  /**
   * A line belonging to a different account is rejected with a 400.
   *
   * @throws Exception if building the reconcile body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupLineWrongAccountReturns400() throws Exception {
    FIN_BankStatementLine line = lineFor(OTHER_ACC, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    doReturn(mock(FIN_FinancialAccount.class)).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(400, response.getHttpStatus());
  }

  /**
   * An empty operationIds list is rejected with a 400 before any lookup.
   *
   * @throws Exception if building the reconcile body fails
   */
  @Test
  public void testReconcileGroupEmptyOperationsReturns400() throws Exception {
    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID));
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  /**
   * A processReconciliation error rolls back and surfaces a 400 with the message.
   *
   * @throws Exception if building the reconcile body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupProcessErrorRollsBackTo400() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    stubReconciliationCompose(rec, "Error");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(400, response.getHttpStatus());
    verify(handler).doRollbackAndClose();
  }

  // ── applySuggestions ─────────────────────────────────────────────────────────

  /**
   * A rule-origin group carrying a createPayment spec materializes the GL-item transaction via
   * {@code createTransactionForRule} and reconciles the resulting transaction against the line.
   *
   * @throws Exception if building the request body or stubbing the seams fails
   */
  @Test
  public void testApplySuggestionsCreatesTransactionForRuleGroup() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, BigDecimal.ZERO, new BigDecimal("12.50"), null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-9");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn("T-NEW").when(handler).createTransactionForRule(eq(account), eq(line), any());
    // The created transaction must balance the line (-12.50) so validateOperations passes.
    doReturn(trxFor(ACC_ID, BigDecimal.ZERO, new BigDecimal("12.50"), null))
        .when(handler).loadTransaction("T-NEW");
    stubReconciliationCompose(rec, "Success");

    JSONObject createPayment = new JSONObject()
        .put("glItemId", "GL-1").put("ruleId", "R1").put("amount", "-12.50");
    JSONObject group = new JSONObject()
        .put("statementLineId", LINE_ID)
        .put("operationIds", new JSONArray())
        .put("createPayment", createPayment);
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(group));

    NeoResponse response = handler.applySuggestions(body);

    assertEquals(201, response.getHttpStatus());
    verify(handler).createTransactionForRule(eq(account), eq(line), any());
    // The created transaction id is the one reconciled against the line.
    verify(handler).matchBankStatementLine(eq(line), argThat(ops -> ops.contains("T-NEW")), eq(rec));
  }

  // ── buildPendingLines: state + counts (T7) ────────────────────────────────────

  /**
   * Each line row must include a {@code state} field and the response must include a {@code counts}
   * object with per-state tallies. Two pending lines → counts.pending == 2, counts.all == 2.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildPendingLinesIncludesStateAndCounts() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString("fin_bankstatementline_id")).thenReturn("l1", "l2");
    when(rs.getTimestamp("datetrx")).thenReturn(null);
    when(rs.getString("description")).thenReturn("DESC1", "DESC2");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("100.00"), new BigDecimal("50.00"));
    // Not reconciled → state is driven by classifyPendingLine (no algorithm → no rule → pending).
    when(rs.getString("line_status")).thenReturn("PENDING", "PENDING");
    when(rs.getString("partner_name")).thenReturn("", "");
    when(rs.getString("reference_no")).thenReturn("", "");
    when(rs.getString("match_group_id")).thenReturn("", "");

    // classifyPendingLine calls OBDal.get for the line, then checks the account's algorithm.
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getMatchingAlgorithm()).thenReturn(null);

    // loadRules now goes through the DAL, not this mocked connection; stub the spy
    // seam so it does not consume the shared rs.next() sequence reserved for the main query.
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);

      // loadAccount is a seam on the spy — return our mock account.
      doReturn(account).when(handler).loadAccount(ACC_ID);

      // classifyPendingLine(account, lineId, rules) calls OBDal.get for the line.
      FIN_BankStatementLine line1 = mock(FIN_BankStatementLine.class);
      FIN_BankStatementLine line2 = mock(FIN_BankStatementLine.class);
      when(dal.get(FIN_BankStatementLine.class, "l1")).thenReturn(line1);
      when(dal.get(FIN_BankStatementLine.class, "l2")).thenReturn(line2);
      when(line1.getDescription()).thenReturn("DESC1");
      when(line1.getReferenceNo()).thenReturn("");
      when(line1.getBpartnername()).thenReturn("");
      when(line2.getDescription()).thenReturn("DESC2");
      when(line2.getReferenceNo()).thenReturn("");
      when(line2.getBpartnername()).thenReturn("");

      NeoResponse response = handler.buildPendingLines(ACC_ID, CLIENT_ID,
          new HashSet<>(Arrays.asList(ORG_ID)), Collections.emptyMap());

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");

      // Every row must carry a "state" field.
      JSONArray lines = data.getJSONArray("lines");
      assertEquals(2, lines.length());
      assertTrue(lines.getJSONObject(0).has("state"));
      assertTrue(lines.getJSONObject(1).has("state"));

      // counts object must be present with at least "all" and "pending" tallies.
      JSONObject counts = data.getJSONObject("counts");
      assertEquals(2, counts.getInt("all"));
      assertEquals(2, counts.getInt("pending"));
    }
  }

  // ── createTransactionForRule (T7) ─────────────────────────────────────────────

  /**
   * A positive (deposit) amount → the transaction type must be BPD (Cobro). The handler should
   * set depositAmount = abs(amount) and paymentAmount = 0.
   *
   * @throws Exception if building the spec or stubbing the static mocks fails
   */
  @Test
  public void testCreateTransactionForRulePositiveAmountUsesBPD() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    Organization org = mock(Organization.class);
    when(line.getOrganization()).thenReturn(org);
    when(line.getDescription()).thenReturn("Bank fee");
    when(line.getTransactionDate()).thenReturn(null);
    when(line.getCramount()).thenReturn(new BigDecimal("100.00"));
    when(line.getDramount()).thenReturn(BigDecimal.ZERO);

    GLItem glItem = mock(GLItem.class);
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("TRX-NEW-1");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<FIN_TransactionProcess> trxProcess =
            mockStatic(FIN_TransactionProcess.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(GLItem.class, "GL-001")).thenReturn(glItem);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinaccTransaction.class)).thenReturn(trx);

      // AutoMatchSupport.nextTransactionLineNo uses OBDal.getConnection().
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getLong(1)).thenReturn(10L);

      trxProcess.when(() ->
          FIN_TransactionProcess.doTransactionProcess(anyString(), eq(trx)))
          .thenAnswer(inv -> null);

      JSONObject spec = new JSONObject()
          .put("glItemId", "GL-001")
          .put("bpartnerId", "")
          .put("amount", "100.00");

      String txnId = handler.createTransactionForRule(account, line, spec);

      assertEquals("TRX-NEW-1", txnId);
      // Verify the transaction was configured as a deposit (BPD).
      verify(trx).setTransactionType("BPD");
      verify(trx).setDepositAmount(new BigDecimal("100.00"));
      verify(trx).setPaymentAmount(BigDecimal.ZERO);
    }
  }

  /**
   * A negative amount → the transaction type must be BPW (Pago). The handler should set
   * paymentAmount = abs(amount) and depositAmount = 0.
   *
   * @throws Exception if building the spec or stubbing the static mocks fails
   */
  @Test
  public void testCreateTransactionForRuleNegativeAmountUsesBPW() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    Organization org = mock(Organization.class);
    when(line.getOrganization()).thenReturn(org);
    when(line.getDescription()).thenReturn("Fee payment");
    when(line.getTransactionDate()).thenReturn(null);
    when(line.getCramount()).thenReturn(BigDecimal.ZERO);
    when(line.getDramount()).thenReturn(new BigDecimal("50.00"));

    GLItem glItem = mock(GLItem.class);
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("TRX-NEW-2");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<FIN_TransactionProcess> trxProcess =
            mockStatic(FIN_TransactionProcess.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(GLItem.class, "GL-002")).thenReturn(glItem);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinaccTransaction.class)).thenReturn(trx);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getLong(1)).thenReturn(10L);

      trxProcess.when(() ->
          FIN_TransactionProcess.doTransactionProcess(anyString(), eq(trx)))
          .thenAnswer(inv -> null);

      JSONObject spec = new JSONObject()
          .put("glItemId", "GL-002")
          .put("bpartnerId", "")
          .put("amount", "-50.00");

      String txnId = handler.createTransactionForRule(account, line, spec);

      assertEquals("TRX-NEW-2", txnId);
      // Negative amount → withdrawal (BPW).
      verify(trx).setTransactionType("BPW");
      verify(trx).setPaymentAmount(new BigDecimal("50.00"));
      verify(trx).setDepositAmount(BigDecimal.ZERO);
    }
  }

  // ── routing: autoMatch + applySuggestions ────────────────────────────────────

  /** A POST applySuggestions with no body returns a 400 (body required). */
  @Test
  public void testHandleApplySuggestionsNoBodyReturns400() {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("POST");
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "applySuggestions");
    when(context.getQueryParams()).thenReturn(qp);
    when(context.getRequestBody()).thenReturn(null);
    NeoResponse response = handler.handle(context);
    assertEquals(400, response.getHttpStatus());
  }

  /** A POST reconcileGroup with no body returns a 400 (body required). */
  @Test
  public void testHandleReconcileGroupNoBodyReturns400() {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("POST");
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "reconcileGroup");
    when(context.getQueryParams()).thenReturn(qp);
    when(context.getRequestBody()).thenReturn(null);
    NeoResponse response = handler.handle(context);
    assertEquals(400, response.getHttpStatus());
  }

  /** autoMatch without an accountId is rejected with a 400 before touching the DB. */
  @Test
  public void testAutoMatchMissingAccountReturns400() {
    NeoResponse response = handler.handle(getContext("autoMatch", null));
    assertEquals(400, response.getHttpStatus());
  }

  /** candidates without an accountId is rejected with a 400. */
  @Test
  public void testCandidatesMissingAccountReturns400() {
    NeoResponse response = handler.handle(getContext("candidates", null));
    assertEquals(400, response.getHttpStatus());
  }

  /** pendingLines without an accountId is rejected with a 400. */
  @Test
  public void testPendingLinesMissingAccountReturns400() {
    NeoResponse response = handler.handle(getContext("pendingLines", null));
    assertEquals(400, response.getHttpStatus());
  }

  // ── suggestedTransactionIds: full standard-algorithm path ─────────────────────

  /**
   * When the account's matching algorithm returns a STRONG match, suggestedTransactionIds returns
   * the matched transaction id (the standard Classic algorithm is used as-is).
   */
  @Test
  public void testSuggestedTransactionIdsStrongMatchReturnsId() {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    MatchingAlgorithm algo = mock(MatchingAlgorithm.class);
    when(algo.getJavaClassName()).thenReturn("com.example.Algo");
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getMatchingAlgorithm()).thenReturn(algo);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    FIN_FinaccTransaction matchedTxn = mock(FIN_FinaccTransaction.class);
    when(matchedTxn.getId()).thenReturn("T-MATCH");
    FIN_MatchedTransaction matched = mock(FIN_MatchedTransaction.class);
    when(matched.getTransaction()).thenReturn(matchedTxn);
    when(matched.getMatchLevel()).thenReturn(FIN_MatchedTransaction.STRONG);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedConstruction<FIN_MatchingTransaction> mc =
            mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
                when(m.match(eq(line), any())).thenReturn(matched))) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_BankStatementLine.class, LINE_ID)).thenReturn(line);

      Set<String> ids = handler.suggestedTransactionIds(ACC_ID, LINE_ID);

      assertTrue(ids.contains("T-MATCH"));
    }
  }

  /** A missing statement line yields an empty suggestion set. */
  @Test
  public void testSuggestedTransactionIdsMissingLineReturnsEmpty() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_BankStatementLine.class, LINE_ID)).thenReturn(null);

      assertTrue(handler.suggestedTransactionIds(ACC_ID, LINE_ID).isEmpty());
    }
  }

  /**
   * The 4-arg {@code suggestedTransactionIds} overload forwards the exact {@code excluded} list
   * instance into {@code matcher.match(line, excluded)} — the mechanism {@code buildAutoMatch}
   * relies on to accumulate consumed transactions across pending lines of the same amount.
   */
  @Test
  public void testSuggestedTransactionIdsForwardsExcludedList() throws Exception {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    MatchingAlgorithm algo = mock(MatchingAlgorithm.class);
    when(algo.getJavaClassName()).thenReturn("com.example.Algo");
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getMatchingAlgorithm()).thenReturn(algo);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    List<FIN_FinaccTransaction> excluded =
        new ArrayList<>(Collections.singletonList(mock(FIN_FinaccTransaction.class)));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedConstruction<FIN_MatchingTransaction> mc =
            mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
                when(m.match(same(line), same(excluded))).thenReturn(null))) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_BankStatementLine.class, LINE_ID)).thenReturn(line);

      Set<String> ids = handler.suggestedTransactionIds(ACC_ID, LINE_ID, 3, excluded);

      assertTrue(ids.isEmpty());
      verify(mc.constructed().get(0)).match(same(line), same(excluded));
    }
  }

  // ── buildCandidates: 1:N signal-group pre-marking ─────────────────────────────

  /**
   * When the selected line equals a signal group's sum, buildCandidates pre-marks every operation
   * of that group as suggested (not only a single 1:1 standard match).
   */
  @Test
  public void testBuildCandidatesPreMarksSignalGroup() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_finacc_transaction_id")).thenReturn("g1");
    when(rs.getTimestamp("statementdate")).thenReturn(null);
    when(rs.getString("document_no")).thenReturn("PAY-G");
    when(rs.getString("partner_name")).thenReturn("ACME");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("60.00"));

    doReturn(new HashSet<String>()).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), eq(LINE_ID), anyInt());
    FIN_BankStatementLine selectedLine = mock(FIN_BankStatementLine.class);
    doReturn(selectedLine).when(handler).loadLine(LINE_ID);

    FIN_FinaccTransaction g1 = mock(FIN_FinaccTransaction.class);
    when(g1.getId()).thenReturn("g1");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<AutoMatchSupport> ams = mockStatic(AutoMatchSupport.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);
      ams.when(() -> AutoMatchSupport.findSignalGroup(eq(ACC_ID), eq(selectedLine), any(), any(),
              anyInt()))
          .thenReturn(Arrays.asList(g1));

      // buildCandidates now resolves the account for the ownership gate (ETP-4950).
      doReturn(mock(FIN_FinancialAccount.class)).when(handler).loadAccount(ACC_ID);
      NeoResponse response = handler.buildCandidates(ACC_ID, LINE_ID, null, null, null);

      JSONArray candidates =
          response.getBody().getJSONObject("response").getJSONObject("data").getJSONArray("candidates");
      assertEquals(1, candidates.length());
      assertTrue(candidates.getJSONObject(0).getBoolean("suggested"));
    }
  }

  // ── buildAutoMatch: preview over pending lines ────────────────────────────────

  /** An autoMatch over a missing account returns a 400. */
  @Test
  public void testBuildAutoMatchMissingAccountReturns400() throws Exception {
    doReturn(null).when(handler).loadAccount(ACC_ID);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      NeoResponse response = handler.buildAutoMatch(ACC_ID);
      assertEquals(400, response.getHttpStatus());
    }
  }

  /**
   * buildAutoMatch produces a 1:1 standard group for a line whose standard algorithm suggests a
   * transaction, and reports the KPIs (one line, one group, one op to link).
   */
  @Test
  public void testBuildAutoMatch1to1StandardGroup() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn("l1");
    when(line.getCramount()).thenReturn(new BigDecimal("100.00"));
    when(line.getDramount()).thenReturn(BigDecimal.ZERO);
    when(line.getDescription()).thenReturn("Transfer");
    when(line.getReferenceNo()).thenReturn("");
    when(line.getTransactionDate()).thenReturn(null);
    doReturn(Collections.singletonList(line)).when(handler).loadPendingLines(ACC_ID);

    doReturn(new HashSet<>(Arrays.asList("t1"))).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), eq("l1"), anyInt(), any());
    FIN_FinaccTransaction t1 = mock(FIN_FinaccTransaction.class);
    when(t1.getId()).thenReturn("t1");
    when(t1.getDepositAmount()).thenReturn(new BigDecimal("100.00"));
    when(t1.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    when(t1.getTransactionDate()).thenReturn(null);
    when(t1.getFinPayment()).thenReturn(null);
    doReturn(t1).when(handler).loadTransaction("t1");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      NeoResponse response = handler.buildAutoMatch(ACC_ID);

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(1, data.getJSONArray("groups").length());
      JSONObject kpis = data.getJSONObject("kpis");
      assertEquals(1, kpis.getInt("pendingLines"));
      assertEquals(1, kpis.getInt("groupsFound"));
      assertEquals(1, kpis.getInt("opsToLink"));
      assertEquals(0, kpis.getInt("willCreate"));
      telemetry.verify(() -> ReconciliationKpiTelemetry.emitBankMatchAttempted(1, 1, 1));
    }
  }

  /**
   * buildAutoMatch falls back to a rule-origin "new" group (createPayment) when neither the 1:1
   * standard algorithm nor a 1:N signal group matches; the willCreate KPI is incremented.
   */
  @Test
  public void testBuildAutoMatchRuleFallbackCreatesGroup() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    MatchRuleEngine.Rule rule = new MatchRuleEngine.Rule("R1", "Fee Rule", 10,
        MatchRuleEngine.COND_CONTAINS, "commission",
        new MatchRuleEngine.RuleOptions("GL-1", "BP-1", null, null, null, null), 0L);
    doReturn(Collections.singletonList(rule)).when(handler).loadRules(eq(ACC_ID));

    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn("l1");
    when(line.getCramount()).thenReturn(BigDecimal.ZERO);
    when(line.getDramount()).thenReturn(new BigDecimal("12.50"));
    when(line.getDescription()).thenReturn("Bank commission fee");
    when(line.getReferenceNo()).thenReturn("");
    when(line.getBpartnername()).thenReturn("");
    when(line.getTransactionDate()).thenReturn(null);
    doReturn(Collections.singletonList(line)).when(handler).loadPendingLines(ACC_ID);

    // No 1:1 standard suggestion.
    doReturn(new HashSet<String>()).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), eq("l1"), anyInt(), any());

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<AutoMatchSupport> ams = mockStatic(AutoMatchSupport.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      // matchFallback composes the (stubbed) leaf helpers below; run its real body so the
      // orchestration is exercised while findSignalGroup/buildRuleGroup stay stubbed.
      // The ETP-4965 near-match pass is NOT stubbed here (it lives in NearMatchSupport, outside this
      // MockedStatic) and must not fire, or the rule branch below is never reached. It cannot:
      // NearMatchSupport.findNearMatch reads its candidate pool through
      // AutoMatchSupport.loadUnreconciledSameSign, which this MockedStatic answers with an empty
      // list. If that loader ever moves out of AutoMatchSupport, stub NearMatchSupport here too.
      ams.when(() -> AutoMatchSupport.matchFallback(any(), any(), any(), any(), any(), any(),
              anyInt(), any()))
          .thenCallRealMethod();
      // No 1:N signal group → forces the rule-engine branch.
      ams.when(() -> AutoMatchSupport.findSignalGroup(any(), any(), any(), any(), anyInt()))
          .thenReturn(Collections.emptyList());
      ams.when(() -> AutoMatchSupport.buildRuleGroup(eq(line), eq(rule), any()))
          .thenReturn(new JSONObject().put("isNew", true).put("groupKey", "l1-rule-R1"));

      NeoResponse response = handler.buildAutoMatch(ACC_ID);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(1, data.getJSONArray("groups").length());
      assertEquals(1, data.getJSONObject("kpis").getInt("willCreate"));
      assertEquals(0, data.getJSONObject("kpis").getInt("opsToLink"));
    }
  }

  // ── buildAutoMatch: same-amount exhaustion regression (ETP-4971) ──────────────

  private FIN_FinancialAccount accountWithMatchingAlgorithm() {
    MatchingAlgorithm algo = mock(MatchingAlgorithm.class);
    when(algo.getJavaClassName()).thenReturn("com.example.Algo");
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getMatchingAlgorithm()).thenReturn(algo);
    return account;
  }

  private FIN_BankStatementLine autoMatchLine(String id, String amount) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getCramount()).thenReturn(new BigDecimal(amount));
    when(line.getDramount()).thenReturn(BigDecimal.ZERO);
    when(line.getDescription()).thenReturn("");
    when(line.getReferenceNo()).thenReturn("");
    when(line.getBpartnername()).thenReturn("");
    when(line.getTransactionDate()).thenReturn(null);
    return line;
  }

  private FIN_FinaccTransaction autoMatchTxn(String id, String amount) {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    when(t.getId()).thenReturn(id);
    when(t.getDepositAmount()).thenReturn(new BigDecimal(amount));
    when(t.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    when(t.getTransactionDate()).thenReturn(null);
    when(t.getFinPayment()).thenReturn(null);
    return t;
  }

  /**
   * Mocks {@link FIN_MatchingTransaction} construction to mirror Core's real
   * {@code MatchTransactionDao} semantics: {@code result.removeAll(excluded)} then return the
   * first remaining same-amount candidate, or NOMATCH once the pool is exhausted. Candidates in
   * {@code pool} are matched against the constructed call's line by signed amount.
   */
  private MockedConstruction<FIN_MatchingTransaction> mockPoolMatching(
      List<FIN_FinaccTransaction> pool) {
    return mockConstruction(FIN_MatchingTransaction.class, (m, ctx) ->
        when(m.match(any(), any())).thenAnswer(invocation -> {
          FIN_BankStatementLine argLine = invocation.getArgument(0);
          List<FIN_FinaccTransaction> excluded = invocation.getArgument(1);
          BigDecimal target = argLine.getCramount().subtract(argLine.getDramount());
          for (FIN_FinaccTransaction candidate : pool) {
            BigDecimal candidateAmount =
                candidate.getDepositAmount().subtract(candidate.getPaymentAmount());
            if (candidateAmount.compareTo(target) == 0 && !excluded.contains(candidate)) {
              FIN_MatchedTransaction found = mock(FIN_MatchedTransaction.class);
              when(found.getTransaction()).thenReturn(candidate);
              when(found.getMatchLevel()).thenReturn(FIN_MatchedTransaction.STRONG);
              return found;
            }
          }
          FIN_MatchedTransaction none = mock(FIN_MatchedTransaction.class);
          when(none.getMatchLevel()).thenReturn(FIN_MatchedTransaction.NOMATCH);
          return none;
        }));
  }

  /**
   * Regression for ETP-4971: two pending lines of the identical amount must each get their OWN
   * 1:1 suggestion when two same-amount transactions are available for them — not the same
   * transaction offered to both. Fails against the pre-fix code, which called Core's matcher with
   * an EMPTY excluded list for every line, so Core kept returning the same transaction for both
   * lines and the per-line usedTxnIds filter then just discarded the duplicate, leaving the
   * second line with no suggestion.
   */
  @Test
  public void testBuildAutoMatchTwoLinesSameAmountProduceTwoGroups() throws Exception {
    FIN_FinancialAccount account = accountWithMatchingAlgorithm();
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    FIN_BankStatementLine l1 = autoMatchLine("l1", "1.00");
    FIN_BankStatementLine l2 = autoMatchLine("l2", "1.00");
    doReturn(Arrays.asList(l1, l2)).when(handler).loadPendingLines(ACC_ID);

    FIN_FinaccTransaction t1 = autoMatchTxn("t1", "1.00");
    FIN_FinaccTransaction t2 = autoMatchTxn("t2", "1.00");
    doReturn(t1).when(handler).loadTransaction("t1");
    doReturn(t2).when(handler).loadTransaction("t2");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class);
        MockedConstruction<FIN_MatchingTransaction> mc = mockPoolMatching(Arrays.asList(t1, t2))) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(dal.get(FIN_BankStatementLine.class, "l1")).thenReturn(l1);
      when(dal.get(FIN_BankStatementLine.class, "l2")).thenReturn(l2);

      NeoResponse response = handler.buildAutoMatch(ACC_ID);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      JSONArray groups = data.getJSONArray("groups");
      assertEquals(2, groups.length());
      assertEquals(2, data.getJSONObject("kpis").getInt("opsToLink"));
      String firstOpId =
          groups.getJSONObject(0).getJSONArray("operations").getJSONObject(0).getString("id");
      String secondOpId =
          groups.getJSONObject(1).getJSONArray("operations").getJSONObject(0).getString("id");
      assertTrue(Arrays.asList("t1", "t2").containsAll(Arrays.asList(firstOpId, secondOpId)));
      assertFalse("the two suggestions must not collide on the same transaction",
          firstOpId.equals(secondOpId));
    }
  }

  /** Same regression as above, scaled to three pending lines / three candidates of 50.00. */
  @Test
  public void testBuildAutoMatchThreeLinesSameAmountProduceThreeGroups() throws Exception {
    FIN_FinancialAccount account = accountWithMatchingAlgorithm();
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    FIN_BankStatementLine l1 = autoMatchLine("l1", "50.00");
    FIN_BankStatementLine l2 = autoMatchLine("l2", "50.00");
    FIN_BankStatementLine l3 = autoMatchLine("l3", "50.00");
    doReturn(Arrays.asList(l1, l2, l3)).when(handler).loadPendingLines(ACC_ID);

    FIN_FinaccTransaction t1 = autoMatchTxn("t1", "50.00");
    FIN_FinaccTransaction t2 = autoMatchTxn("t2", "50.00");
    FIN_FinaccTransaction t3 = autoMatchTxn("t3", "50.00");
    doReturn(t1).when(handler).loadTransaction("t1");
    doReturn(t2).when(handler).loadTransaction("t2");
    doReturn(t3).when(handler).loadTransaction("t3");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class);
        MockedConstruction<FIN_MatchingTransaction> mc =
            mockPoolMatching(Arrays.asList(t1, t2, t3))) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(dal.get(FIN_BankStatementLine.class, "l1")).thenReturn(l1);
      when(dal.get(FIN_BankStatementLine.class, "l2")).thenReturn(l2);
      when(dal.get(FIN_BankStatementLine.class, "l3")).thenReturn(l3);

      NeoResponse response = handler.buildAutoMatch(ACC_ID);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      JSONArray groups = data.getJSONArray("groups");
      assertEquals(3, groups.length());
      assertEquals(3, data.getJSONObject("kpis").getInt("opsToLink"));
      Set<String> opIds = new HashSet<>();
      for (int i = 0; i < groups.length(); i++) {
        opIds.add(
            groups.getJSONObject(i).getJSONArray("operations").getJSONObject(0).getString("id"));
      }
      assertEquals(3, opIds.size());
    }
  }

  /**
   * A unique-amount line is unaffected by the exhaustion fix; only the duplicated-amount lines
   * multiply into distinct groups.
   */
  @Test
  public void testBuildAutoMatchMixedAmountsOnlyDuplicatesMultiply() throws Exception {
    FIN_FinancialAccount account = accountWithMatchingAlgorithm();
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    FIN_BankStatementLine lUnique = autoMatchLine("lUnique", "30.00");
    FIN_BankStatementLine lDup1 = autoMatchLine("lDup1", "10.00");
    FIN_BankStatementLine lDup2 = autoMatchLine("lDup2", "10.00");
    doReturn(Arrays.asList(lUnique, lDup1, lDup2)).when(handler).loadPendingLines(ACC_ID);

    FIN_FinaccTransaction tUnique = autoMatchTxn("tUnique", "30.00");
    FIN_FinaccTransaction tDupA = autoMatchTxn("tDupA", "10.00");
    FIN_FinaccTransaction tDupB = autoMatchTxn("tDupB", "10.00");
    doReturn(tUnique).when(handler).loadTransaction("tUnique");
    doReturn(tDupA).when(handler).loadTransaction("tDupA");
    doReturn(tDupB).when(handler).loadTransaction("tDupB");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class);
        MockedConstruction<FIN_MatchingTransaction> mc =
            mockPoolMatching(Arrays.asList(tUnique, tDupA, tDupB))) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(dal.get(FIN_BankStatementLine.class, "lUnique")).thenReturn(lUnique);
      when(dal.get(FIN_BankStatementLine.class, "lDup1")).thenReturn(lDup1);
      when(dal.get(FIN_BankStatementLine.class, "lDup2")).thenReturn(lDup2);

      NeoResponse response = handler.buildAutoMatch(ACC_ID);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      JSONArray groups = data.getJSONArray("groups");
      assertEquals(3, groups.length());
      assertEquals(3, data.getJSONObject("kpis").getInt("opsToLink"));

      Set<String> dupOpIds = new HashSet<>();
      boolean uniqueGroupFound = false;
      for (int i = 0; i < groups.length(); i++) {
        String opId =
            groups.getJSONObject(i).getJSONArray("operations").getJSONObject(0).getString("id");
        if ("tUnique".equals(opId)) {
          uniqueGroupFound = true;
        } else {
          dupOpIds.add(opId);
        }
      }
      assertTrue(uniqueGroupFound);
      assertEquals(2, dupOpIds.size());
    }
  }

  /**
   * When fewer same-amount transactions exist than pending lines of that amount, the lines past
   * the exhausted candidate pool are left WITHOUT a fabricated match — no signal-group or rule
   * fallback applies here, so the second line simply does not appear in the result.
   */
  @Test
  public void testBuildAutoMatchExhaustedCandidatesLeavesLaterLineUnsuggested() throws Exception {
    FIN_FinancialAccount account = accountWithMatchingAlgorithm();
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));

    FIN_BankStatementLine l1 = autoMatchLine("l1", "1.00");
    FIN_BankStatementLine l2 = autoMatchLine("l2", "1.00");
    doReturn(Arrays.asList(l1, l2)).when(handler).loadPendingLines(ACC_ID);

    FIN_FinaccTransaction t1 = autoMatchTxn("t1", "1.00");
    doReturn(t1).when(handler).loadTransaction("t1");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationKpiTelemetry> telemetry =
            mockStatic(ReconciliationKpiTelemetry.class);
        MockedConstruction<FIN_MatchingTransaction> mc =
            mockPoolMatching(Collections.singletonList(t1))) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(dal.get(FIN_BankStatementLine.class, "l1")).thenReturn(l1);
      when(dal.get(FIN_BankStatementLine.class, "l2")).thenReturn(l2);
      // findSignalGroup's real DB path (no fallback candidate exists for line 2 either).
      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);
      @SuppressWarnings("unchecked")
      org.hibernate.query.Query<FIN_FinaccTransaction> query =
          mock(org.hibernate.query.Query.class);
      when(session.createQuery(anyString(), eq(FIN_FinaccTransaction.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(Collections.emptyList());

      NeoResponse response = handler.buildAutoMatch(ACC_ID);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      JSONArray groups = data.getJSONArray("groups");
      assertEquals(1, groups.length());
      assertEquals(1, data.getJSONObject("kpis").getInt("opsToLink"));
      assertEquals("t1",
          groups.getJSONObject(0).getJSONArray("operations").getJSONObject(0).getString("id"));
    }
  }

  // ── applySuggestions: validation branches ─────────────────────────────────────

  /** applySuggestions without a financialAccountId returns a 400. */
  @Test
  public void testApplySuggestionsMissingAccountReturns400() throws Exception {
    NeoResponse response = handler.applySuggestions(new JSONObject());
    assertEquals(400, response.getHttpStatus());
  }

  /** applySuggestions for an unknown account returns a 400. */
  @Test
  public void testApplySuggestionsUnknownAccountReturns400() throws Exception {
    doReturn(null).when(handler).loadAccount(ACC_ID);
    JSONObject body = new JSONObject().put("financialAccountId", ACC_ID);
    NeoResponse response = handler.applySuggestions(body);
    assertEquals(400, response.getHttpStatus());
  }

  /** applySuggestions with an empty groups array returns a 400. */
  @Test
  public void testApplySuggestionsEmptyGroupsReturns400() throws Exception {
    doReturn(mock(FIN_FinancialAccount.class)).when(handler).loadAccount(ACC_ID);
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray());
    NeoResponse response = handler.applySuggestions(body);
    assertEquals(400, response.getHttpStatus());
  }

  /**
   * applySuggestions with a plain 1:N operationIds group (no createPayment) composes the standard
   * reconciliation services and returns 201.
   */
  @Test
  public void testApplySuggestionsPlainGroupReconciles() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("150.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t1 = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t2 = trxFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-5");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(t1).when(handler).loadTransaction("t1");
    doReturn(t2).when(handler).loadTransaction("t2");
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    JSONObject group = new JSONObject()
        .put("statementLineId", LINE_ID)
        .put("operationIds", new JSONArray().put("t1").put("t2"));
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(group));

    NeoResponse response;
    try (MockedStatic<ReconciliationKpiTelemetry> telemetry =
        mockStatic(ReconciliationKpiTelemetry.class)) {
      response = handler.applySuggestions(body);
      telemetry.verify(
          () -> ReconciliationKpiTelemetry.emitReconciliationMatchEvaluated(1, 1, 1));
    }

    assertEquals(201, response.getHttpStatus());
    verify(handler).matchBankStatementLine(eq(line), argThat(ops ->
        ops.contains("t1") && ops.contains("t2")), eq(rec));
  }

  /**
   * A group whose statement line is already reconciled is reported as a 409 in the per-group
   * results, while the overall response is still 201 (best-effort batch apply).
   */
  @Test
  public void testApplySuggestionsGroupLineAlreadyReconciledRecorded() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_FinaccTransaction already = mock(FIN_FinaccTransaction.class);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("10.00"), BigDecimal.ZERO, already);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    JSONObject group = new JSONObject()
        .put("statementLineId", LINE_ID)
        .put("operationIds", new JSONArray().put("t1"));
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(group));

    NeoResponse response;
    try (MockedStatic<ReconciliationKpiTelemetry> telemetry =
        mockStatic(ReconciliationKpiTelemetry.class)) {
      response = handler.applySuggestions(body);
      telemetry.verify(
          () -> ReconciliationKpiTelemetry.emitReconciliationMatchEvaluated(1, 1, 0));
    }

    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals(1, data.getInt("applied"));
    // The single result records the 409 line-already-reconciled error.
    JSONObject result = data.getJSONArray("results").getJSONObject(0);
    assertTrue(result.getJSONObject("error").getString("message").contains("already reconciled"));
  }

  /** A group with no statementLineId records a 400 error in the results. */
  @Test
  public void testApplySuggestionsGroupMissingLineIdRecordsError() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    JSONObject group = new JSONObject().put("operationIds", new JSONArray().put("t1"));
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(group));

    NeoResponse response = handler.applySuggestions(body);

    assertEquals(201, response.getHttpStatus());
    JSONObject result = response.getBody().getJSONObject("response").getJSONObject("data")
        .getJSONArray("results").getJSONObject(0);
    assertTrue(result.getJSONObject("error").getString("message").contains("statementLineId"));
  }

  /** A group with no operations and no createPayment records a 400 error in the results. */
  @Test
  public void testApplySuggestionsGroupNoOperationsRecordsError() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("10.00"), BigDecimal.ZERO, null);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    JSONObject group = new JSONObject()
        .put("statementLineId", LINE_ID)
        .put("operationIds", new JSONArray());
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(group));

    NeoResponse response = handler.applySuggestions(body);

    JSONObject result = response.getBody().getJSONObject("response").getJSONObject("data")
        .getJSONArray("results").getJSONObject(0);
    assertTrue(result.getJSONObject("error").getString("message").contains("At least one operation"));
  }

  // ── loadPendingLines / loadRules seams ────────────────────────────────────────

  /**
   * loadRules delegates to the engine, passing the account through and returning its rules.
   *
   * <p>The seam no longer takes a {@code Connection}: since ETP-4950 the engine loads through the
   * DAL so the readable-client / readable-organization filter is applied by the framework and cannot
   * be skipped by a caller.
   */
  @Test
  public void testLoadRulesDelegatesToEngine() {
    try (MockedStatic<MatchRuleEngine> engine = mockStatic(MatchRuleEngine.class)) {
      engine.when(() -> MatchRuleEngine.loadRules(ACC_ID)).thenReturn(Collections.emptyList());

      List<MatchRuleEngine.Rule> rules = handler.loadRules(ACC_ID);

      assertTrue(rules.isEmpty());
      engine.verify(() -> MatchRuleEngine.loadRules(ACC_ID));
    }
  }

  /** loadPendingLines binds the account id and returns the session query results. */
  @Test
  public void testLoadPendingLinesQueriesSession() {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);
      @SuppressWarnings("unchecked")
      org.hibernate.query.Query<FIN_BankStatementLine> query =
          mock(org.hibernate.query.Query.class);
      when(session.createQuery(anyString(), eq(FIN_BankStatementLine.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(new ArrayList<>(Arrays.asList(line)));

      List<FIN_BankStatementLine> result = handler.loadPendingLines(ACC_ID);

      assertEquals(1, result.size());
      verify(query).setParameter("accountId", ACC_ID);
    }
  }

  // ── buildInvoiceCandidates: invoice-mode right panel ──────────────────────────

  /**
   * Builds a {@link FIN_FinancialAccount} mock carrying a client + organization, as the invoice
   * candidates query needs both to scope the SQL by client and natural org tree.
   */
  private FIN_FinancialAccount accountWithClientOrg(String clientId, String orgId) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(account.getClient()).thenReturn(client);
    when(account.getOrganization()).thenReturn(org);
    return account;
  }

  /**
   * Stubs {@code OBContext.getOBContext().getOrganizationStructureProvider(client).getNaturalTree(org)}
   * so {@code buildInvoiceCandidates} can resolve the accessible org tree without a live context.
   */
  private void stubNaturalTree(MockedStatic<OBContext> obContext, String clientId, String orgId,
      Set<String> tree) {
    OBContext ctx = mock(OBContext.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    when(osp.getNaturalTree(orgId)).thenReturn(tree);
    when(ctx.getOrganizationStructureProvider(clientId)).thenReturn(osp);
    obContext.when(OBContext::getOBContext).thenReturn(ctx);
  }

  /**
   * An inflow line (positive amount) lists sales invoices: the query binds {@code issotrx='Y'} and
   * each candidate carries {@code kind="invoice"}, its ids and a positively-signed amount that
   * matches the line direction (a receipt).
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildInvoiceCandidatesInflowBindsSalesAndSignsPositive() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_payment_schedule_id")).thenReturn("PS-1");
    when(rs.getString("c_invoice_id")).thenReturn("INV-1");
    when(rs.getString("documentno")).thenReturn("DOC-1");
    when(rs.getTimestamp("invoicedate")).thenReturn(null);
    when(rs.getString("partner_name")).thenReturn("ACME");
    when(rs.getBigDecimal("outstanding")).thenReturn(new BigDecimal("100.00"));

    // Inflow line: cramount > dramount → positive direction (receipt / sales invoices).
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    doReturn(line).when(handler).loadLine(LINE_ID);
    FIN_FinancialAccount account = accountWithClientOrg(CLIENT_ID, ORG_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);
      stubNaturalTree(obContext, CLIENT_ID, ORG_ID, new HashSet<>(Arrays.asList(ORG_ID)));

      NeoResponse response = handler.buildInvoiceCandidates(ACC_ID, LINE_ID, null, null, null);

      assertEquals(200, response.getHttpStatus());
      JSONArray candidates = response.getBody().getJSONObject("response")
          .getJSONObject("data").getJSONArray("candidates");
      assertEquals(1, candidates.length());
      JSONObject row = candidates.getJSONObject(0);
      assertEquals("invoice", row.getString("kind"));
      assertEquals("INV-1", row.getString("invoiceId"));
      assertEquals("PS-1", row.getString("scheduleId"));
      assertTrue(row.getBoolean("isReceipt"));
      // Inflow → amount keeps the line's positive sign.
      assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(row.getString("amount"))));
      // A sales invoice query binds issotrx = 'Y' first, then the client id.
      verify(ps).setString(1, "Y");
      verify(ps).setString(2, CLIENT_ID);
    }
  }

  /**
   * An outflow line (negative amount) lists purchase invoices: the query binds {@code issotrx='N'}
   * and the candidate amount is negatively signed (a payment), matching the line direction.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildInvoiceCandidatesOutflowBindsPurchaseAndSignsNegative() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_payment_schedule_id")).thenReturn("PS-2");
    when(rs.getString("c_invoice_id")).thenReturn("INV-2");
    when(rs.getString("documentno")).thenReturn("DOC-2");
    when(rs.getTimestamp("invoicedate")).thenReturn(null);
    when(rs.getString("partner_name")).thenReturn("SUPPLIER");
    when(rs.getBigDecimal("outstanding")).thenReturn(new BigDecimal("75.00"));

    // Outflow line: dramount > cramount → negative direction (payment / purchase invoices).
    FIN_BankStatementLine line = lineFor(ACC_ID, BigDecimal.ZERO, new BigDecimal("75.00"), null);
    doReturn(line).when(handler).loadLine(LINE_ID);
    FIN_FinancialAccount account = accountWithClientOrg(CLIENT_ID, ORG_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);
      stubNaturalTree(obContext, CLIENT_ID, ORG_ID, new HashSet<>(Arrays.asList(ORG_ID)));

      NeoResponse response = handler.buildInvoiceCandidates(ACC_ID, LINE_ID, null, null, null);

      JSONObject row = response.getBody().getJSONObject("response")
          .getJSONObject("data").getJSONArray("candidates").getJSONObject(0);
      assertFalse(row.getBoolean("isReceipt"));
      // Outflow → amount carries the line's negative sign.
      assertEquals(0, new BigDecimal("-75.00").compareTo(new BigDecimal(row.getString("amount"))));
      // A purchase invoice query binds issotrx = 'N'.
      verify(ps).setString(1, "N");
    }
  }

  /**
   * A zero-amount line has no determinable direction, so no invoice candidates are listed (and the
   * query is never executed).
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildInvoiceCandidatesZeroAmountReturnsEmpty() throws Exception {
    FIN_BankStatementLine line = lineFor(ACC_ID, BigDecimal.ZERO, BigDecimal.ZERO, null);
    doReturn(line).when(handler).loadLine(LINE_ID);
    FIN_FinancialAccount account = accountWithClientOrg(CLIENT_ID, ORG_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.buildInvoiceCandidates(ACC_ID, LINE_ID, null, null, null);

      assertEquals(200, response.getHttpStatus());
      JSONArray candidates = response.getBody().getJSONObject("response")
          .getJSONObject("data").getJSONArray("candidates");
      assertEquals(0, candidates.length());
    }
  }

  /**
   * With no selected line the direction is unknown, so no invoice candidates are listed.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildInvoiceCandidatesNullLineReturnsEmpty() throws Exception {
    FIN_FinancialAccount account = accountWithClientOrg(CLIENT_ID, ORG_ID);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = handler.buildInvoiceCandidates(ACC_ID, null, null, null, null);

      assertEquals(200, response.getHttpStatus());
      assertEquals(0, response.getBody().getJSONObject("response")
          .getJSONObject("data").getJSONArray("candidates").length());
    }
  }

  // ── reconcileGroup with invoices: create payment then reconcile ───────────────

  /**
   * Builds an invoice {@code reconcileGroup} body: {@code { financialAccountId, statementLineId,
   * invoices:[{invoiceId, scheduleId}] }}.
   */
  private JSONObject invoiceReconcileBody(String accountId, String lineId, String invoiceId,
      String scheduleId) throws Exception {
    JSONArray invoices = new JSONArray()
        .put(new JSONObject().put("invoiceId", invoiceId).put("scheduleId", scheduleId));
    return new JSONObject()
        .put("financialAccountId", accountId)
        .put("statementLineId", lineId)
        .put("invoices", invoices);
  }

  /**
   * A reconcileGroup carrying an invoice whose outstanding covers the line registers a payment
   * (which auto-creates a finacc transaction), then reconciles that new transaction against the
   * line and returns 201.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupWithInvoiceCreatesPaymentAndReconciles() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    when(line.getTransactionDate()).thenReturn(null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-inv");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    // The payment auto-creates a transaction (T-INV) that balances the line (+100.00).
    FIN_FinaccTransaction createdTxn = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    when(createdTxn.getId()).thenReturn("T-INV");
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getFINFinaccTransactionList()).thenReturn(Collections.singletonList(createdTxn));
    doReturn(createdTxn).when(handler).loadTransaction("T-INV");
    stubReconciliationCompose(rec, "Success");

    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    when(schedule.getOutstandingAmount()).thenReturn(new BigDecimal("100.00"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationPaymentService> rps =
            mockStatic(ReconciliationPaymentService.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "INV-1")).thenReturn(invoice);
      when(dal.get(FIN_PaymentSchedule.class, "PS-1")).thenReturn(schedule);
      // Mock the whole reconciliation-payment seam (as ReconciliationFlowSupportForeignInvoiceTest
      // does): the real registerReconciliationPayment calls findPendingPSDs/createDraftPayment,
      // which are unavailable in a unit test.
      rps.when(() -> ReconciliationPaymentService.registerReconciliationPayment(any()))
          .thenReturn(payment);

      NeoResponse response = handler.reconcileGroup(invoiceReconcileBody(ACC_ID, LINE_ID,
          "INV-1", "PS-1"));

      assertEquals(201, response.getHttpStatus());
      // The auto-created transaction id is the one reconciled against the line.
      verify(handler).matchBankStatementLine(eq(line),
          argThat(ops -> ops.contains("T-INV")), eq(rec));
    }
  }

  /**
   * A reconcileGroup whose selected invoices contribute NOTHING to the statement line (every
   * selected installment already has zero outstanding) is rejected with a 400 ("do not cover").
   *
   * <p>Note: a genuinely PARTIAL invoice (e.g. 40 of a 100 line) is no longer a 400 — since
   * ETP-4502 iteration 2 the backend settles that portion and splits the remainder, so the "do not
   * cover" guard in {@code createInvoicePayments} only fires when the whole selection consumes
   * nothing ({@code remaining == startingRemaining}). A zero-outstanding schedule is that
   * zero-consumption case: {@code allocateBase <= tolerance} short-circuits {@code settleInvoice}
   * before any payment is created, so {@code remaining} stays at the full line amount.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupWithInvoiceInsufficientOutstandingReturns400() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    // Line needs 100.00 but the selected installment has 0 outstanding → nothing consumed → reject.
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    when(line.getTransactionDate()).thenReturn(null);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    // Kept but never reached: with a zero-outstanding schedule settleInvoice short-circuits before
    // registerReconciliationPayment, so this stub is harmless (see class note on the guard).
    FIN_FinaccTransaction createdTxn = trxFor(ACC_ID, new BigDecimal("40.00"), BigDecimal.ZERO, null);
    when(createdTxn.getId()).thenReturn("T-INV");
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getFINFinaccTransactionList()).thenReturn(Collections.singletonList(createdTxn));

    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    when(schedule.getOutstandingAmount()).thenReturn(BigDecimal.ZERO);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationPaymentService> rps =
            mockStatic(ReconciliationPaymentService.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "INV-1")).thenReturn(invoice);
      when(dal.get(FIN_PaymentSchedule.class, "PS-1")).thenReturn(schedule);
      rps.when(() -> ReconciliationPaymentService.registerReconciliationPayment(any()))
          .thenReturn(payment);

      NeoResponse response = handler.reconcileGroup(invoiceReconcileBody(ACC_ID, LINE_ID,
          "INV-1", "PS-1"));

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message").contains("do not cover"));
      // The line is never reconciled when the invoices cover nothing.
      verify(handler, never()).addNewDraftReconciliation(any());
    }
  }

  /**
   * PARTIAL invoice coverage SUCCEEDS (ETP-4502 iteration 2): a 100.00 line reconciled against an
   * invoice whose outstanding is only 40.00 settles that 40.00 portion — a payment (and its
   * auto-created transaction) for 40.00 is created and reconciled against the line, leaving a 60.00
   * pending remainder — and returns 201. This is the complement of
   * {@link #testReconcileGroupWithInvoiceInsufficientOutstandingReturns400}: the "do not cover" 400
   * fires only when the selection consumes NOTHING (zero outstanding), NOT for a genuine partial.
   *
   * <p>Flow (verified by reading {@code settleInvoice}/{@code createInvoicePayments}/{@code
   * validateOperations}): {@code allocateBase = min(remaining 100, outstandingBase 40) = 40 >
   * tolerance} → payment created (mocked seam) → {@code remaining} 100 → 60 → {@code
   * createInvoicePayments} returns null (60 != startingRemaining 100, so no "do not cover") →
   * {@code validateOperations} allows the within-line partial (|40| <= |100|) → {@code compose} →
   * 201. The 40-of-100 match splits the line, so {@code willSplitLine} is true and {@code compose}
   * evaluates {@code readMatchGroupId}/{@code tagMatchGroup} — both fully exception-guarded, so they
   * are no-ops here without a live model.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupWithInvoicePartialCoverageReconcilesRemainderPending()
      throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    when(line.getTransactionDate()).thenReturn(null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-inv-partial");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    // The payment auto-creates a transaction (T-INV) for the settled portion (+40.00).
    FIN_FinaccTransaction createdTxn = trxFor(ACC_ID, new BigDecimal("40.00"), BigDecimal.ZERO, null);
    when(createdTxn.getId()).thenReturn("T-INV");
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getFINFinaccTransactionList()).thenReturn(Collections.singletonList(createdTxn));
    doReturn(createdTxn).when(handler).loadTransaction("T-INV");
    stubReconciliationCompose(rec, "Success");

    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    // Invoice outstanding (40.00) is LESS than the line (100.00) → partial settlement, not a 400.
    when(schedule.getOutstandingAmount()).thenReturn(new BigDecimal("40.00"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationPaymentService> rps =
            mockStatic(ReconciliationPaymentService.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "INV-1")).thenReturn(invoice);
      when(dal.get(FIN_PaymentSchedule.class, "PS-1")).thenReturn(schedule);
      rps.when(() -> ReconciliationPaymentService.registerReconciliationPayment(any()))
          .thenReturn(payment);

      NeoResponse response = handler.reconcileGroup(invoiceReconcileBody(ACC_ID, LINE_ID,
          "INV-1", "PS-1"));

      assertEquals(201, response.getHttpStatus());
      // The 40.00 auto-created transaction is reconciled against the line (remainder stays pending).
      verify(handler).matchBankStatementLine(eq(line),
          argThat(ops -> ops.contains("T-INV")), eq(rec));
    }
  }

  /**
   * applyGroup amount guard: a plain operations group whose signed amounts EXCEED the statement
   * line amount records a 400 ("exceed the statement line amount") in the per-group result (same
   * over-reconciliation guard the manual reconcileGroup path applies). Operations that sum to LESS
   * than the line are allowed (partial match), so the rejected case must over-shoot the line.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testApplySuggestionsPlainGroupAmountMismatchRecordsError() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    // Line is 150.00 but the operations sum to 180.00 (100 + 80) → exceeds the line → reject.
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("150.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t1 = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t2 = trxFor(ACC_ID, new BigDecimal("80.00"), BigDecimal.ZERO, null);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(t1).when(handler).loadTransaction("t1");
    doReturn(t2).when(handler).loadTransaction("t2");

    JSONObject group = new JSONObject()
        .put("statementLineId", LINE_ID)
        .put("operationIds", new JSONArray().put("t1").put("t2"));
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(group));

    NeoResponse response = handler.applySuggestions(body);

    // Batch apply is best-effort → overall 201, but the per-group result carries the 400.
    assertEquals(201, response.getHttpStatus());
    JSONObject result = response.getBody().getJSONObject("response").getJSONObject("data")
        .getJSONArray("results").getJSONObject(0);
    assertTrue(result.getJSONObject("error").getString("message")
        .contains("exceed the statement line amount"));
    // The over-reconciling group is never reconciled.
    verify(handler, never()).addNewDraftReconciliation(any());
    verify(handler, never()).getOrCreateDraftReconciliation(any());
  }

  // ── getOrCreateDraftReconciliation: reuse-or-create the batch's shared header ────

  /**
   * When the account already has an open draft reconciliation, it is reused as-is —
   * {@code addNewDraftReconciliation} must never run.
   */
  @Test
  public void testGetOrCreateDraftReconciliationReusesExistingDraft() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_Reconciliation existingDraft = mock(FIN_Reconciliation.class);

    try (MockedStatic<TransactionsDao> dao = mockStatic(TransactionsDao.class)) {
      dao.when(() -> TransactionsDao.getLastReconciliation(account, "N")).thenReturn(existingDraft);

      FIN_Reconciliation result = handler.getOrCreateDraftReconciliation(account);

      assertEquals(existingDraft, result);
      verify(handler, never()).addNewDraftReconciliation(any());
    }
  }

  /**
   * When the account has no open draft, a fresh one is created via
   * {@code addNewDraftReconciliation} and returned.
   */
  @Test
  public void testGetOrCreateDraftReconciliationCreatesWhenNoneOpen() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_Reconciliation fresh = mock(FIN_Reconciliation.class);
    doReturn(fresh).when(handler).addNewDraftReconciliation(account);

    try (MockedStatic<TransactionsDao> dao = mockStatic(TransactionsDao.class)) {
      dao.when(() -> TransactionsDao.getLastReconciliation(account, "N")).thenReturn(null);

      FIN_Reconciliation result = handler.getOrCreateDraftReconciliation(account);

      assertEquals(fresh, result);
      verify(handler).addNewDraftReconciliation(account);
    }
  }

  // ── applySuggestions: ONE shared reconciliation for the whole batch (T1) ────────

  /**
   * The core regression test for the batch-header refactor: TWO valid groups (different statement
   * lines) in one {@code applySuggestions} call must share a SINGLE {@link FIN_Reconciliation} —
   * {@code getOrCreateDraftReconciliation}/{@code addNewDraftReconciliation} runs only ONCE for the
   * whole batch (not once per group, as the old per-group {@code compose()} used to), {@code
   * matchBankStatementLine} runs once per group, {@code processReconciliation} runs only ONCE at the
   * end, and every success entry in {@code results[]} carries the SAME {@code reconciliationId}.
   */
  @Test
  public void testApplySuggestionsTwoValidGroupsShareOneReconciliation() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line1 = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_BankStatementLine line2 = lineFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t1 = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t2 = trxFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-shared");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line1).when(handler).loadLine("line-1a");
    doReturn(line2).when(handler).loadLine("line-1b");
    doReturn(t1).when(handler).loadTransaction("t1");
    doReturn(t2).when(handler).loadTransaction("t2");
    doNothing().when(handler).tagMatchGroup(any());

    try (MockedStatic<TransactionsDao> dao = mockStatic(TransactionsDao.class)) {
      dao.when(() -> TransactionsDao.getLastReconciliation(account, "N")).thenReturn(null);
      doReturn(rec).when(handler).addNewDraftReconciliation(account);
      doNothing().when(handler).matchBankStatementLine(any(), any(), any());
      OBError ok = mock(OBError.class);
      when(ok.getType()).thenReturn("Success");
      doReturn(ok).when(handler).processReconciliation(rec);

      JSONObject group1 = new JSONObject()
          .put("statementLineId", "line-1a")
          .put("operationIds", new JSONArray().put("t1"));
      JSONObject group2 = new JSONObject()
          .put("statementLineId", "line-1b")
          .put("operationIds", new JSONArray().put("t2"));
      JSONObject body = new JSONObject()
          .put("financialAccountId", ACC_ID)
          .put("groups", new JSONArray().put(group1).put(group2));

      NeoResponse response = handler.applySuggestions(body);

      assertEquals(201, response.getHttpStatus());
      // Shared header created exactly once for the whole batch, not once per group.
      verify(handler, times(1)).getOrCreateDraftReconciliation(account);
      verify(handler, times(1)).addNewDraftReconciliation(account);
      verify(handler, times(1)).matchBankStatementLine(eq(line1), any(), eq(rec));
      verify(handler, times(1)).matchBankStatementLine(eq(line2), any(), eq(rec));
      verify(handler, times(1)).processReconciliation(rec);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(2, data.getInt("applied"));
      JSONArray results = data.getJSONArray("results");
      assertEquals("rec-shared", results.getJSONObject(0).getString("reconciliationId"));
      assertEquals("rec-shared", results.getJSONObject(1).getString("reconciliationId"));
    }
  }

  /**
   * One invalid group (missing {@code statementLineId}) mixed with one valid group in the same
   * batch: the invalid entry's error lands in {@code results[]} without ever touching the shared
   * reconciliation, and the valid one still succeeds normally against it.
   */
  @Test
  public void testApplySuggestionsMixedValidAndInvalidGroupBatch() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t1 = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-mixed");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(t1).when(handler).loadTransaction("t1");
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    // Invalid: no statementLineId at all.
    JSONObject invalidGroup = new JSONObject().put("operationIds", new JSONArray().put("t1"));
    JSONObject validGroup = new JSONObject()
        .put("statementLineId", LINE_ID)
        .put("operationIds", new JSONArray().put("t1"));
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(invalidGroup).put(validGroup));

    NeoResponse response = handler.applySuggestions(body);

    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    JSONArray results = data.getJSONArray("results");
    assertEquals(2, results.length());
    assertTrue(results.getJSONObject(0).getJSONObject("error").getString("message")
        .contains("statementLineId"));
    assertEquals("rec-mixed", results.getJSONObject(1).getString("reconciliationId"));
    // The shared reconciliation is created exactly once — the invalid group never reaches it.
    verify(handler, times(1)).getOrCreateDraftReconciliation(account);
    verify(handler, times(1)).matchBankStatementLine(eq(line), any(), eq(rec));
  }

  /**
   * {@code matchInto} (via {@code matchBankStatementLine}) throws for ONE group mid-batch while a
   * second group succeeds: the failure is captured as an error entry in {@code results[]} instead of
   * propagating out of {@code applySuggestions}, and the shared document is still processed once at
   * the end (the batch is not aborted).
   */
  @Test
  public void testApplySuggestionsOneGroupThrowsMidBatchStillProcessesTheRest() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine failingLine =
        lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    when(failingLine.getId()).thenReturn("line-fail");
    FIN_BankStatementLine okLine = lineFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t1 = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t2 = trxFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-partial");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(failingLine).when(handler).loadLine("line-fail");
    doReturn(okLine).when(handler).loadLine("line-ok");
    doReturn(t1).when(handler).loadTransaction("t1");
    doReturn(t2).when(handler).loadTransaction("t2");
    doNothing().when(handler).tagMatchGroup(any());
    doReturn(rec).when(handler).getOrCreateDraftReconciliation(account);
    // matchBankStatementLine throws only for the failing line; succeeds for the other.
    doThrow(new RuntimeException("Core matching blew up"))
        .when(handler).matchBankStatementLine(eq(failingLine), any(), eq(rec));
    doNothing().when(handler).matchBankStatementLine(eq(okLine), any(), eq(rec));
    OBError ok = mock(OBError.class);
    when(ok.getType()).thenReturn("Success");
    doReturn(ok).when(handler).processReconciliation(rec);

    JSONObject failGroup = new JSONObject()
        .put("statementLineId", "line-fail")
        .put("operationIds", new JSONArray().put("t1"));
    JSONObject okGroup = new JSONObject()
        .put("statementLineId", "line-ok")
        .put("operationIds", new JSONArray().put("t2"));
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(failGroup).put(okGroup));

    NeoResponse response = handler.applySuggestions(body);

    // No exception propagates out of applySuggestions.
    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    JSONArray results = data.getJSONArray("results");
    assertEquals(2, results.length());
    assertTrue(results.getJSONObject(0).getJSONObject("error").getString("message")
        .contains("line-fail"));
    assertEquals("rec-partial", results.getJSONObject(1).getString("reconciliationId"));
    // The shared document is still processed once at the end — the batch is not aborted.
    verify(handler, times(1)).processReconciliation(rec);
  }

  // ── reactivate (un-reconcile a single statement line, T8 part 1) ──────────────

  /** Builds a reactivate body: {@code { financialAccountId, statementLineId }}. */
  private JSONObject reactivateBody(String accountId, String lineId) throws Exception {
    return new JSONObject()
        .put("financialAccountId", accountId)
        .put("statementLineId", lineId);
  }

  /**
   * Builds a reconciliation that is reachable from the line's transaction and carries the metadata
   * the period guard and balance helpers read ({@code client}, {@code organization},
   * {@code entity.tableId}, {@code transactionDate}). The line's bank statement belongs to
   * {@link #ACC_ID} so {@code belongsToAccount} passes. The reconciliation's transaction list is
   * stubbed so {@code reactivate} can snapshot it and hand it to {@code undoReconciliation}.
   */
  private FIN_Reconciliation reconciledLineSetup() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-react");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(rec.getClient()).thenReturn(client);
    when(rec.getOrganization()).thenReturn(org);
    when(rec.getTransactionDate()).thenReturn(null);
    Entity entity = mock(Entity.class);
    when(entity.getTableId()).thenReturn("TBL-1");
    when(rec.getEntity()).thenReturn(entity);

    // line.financialAccountTransaction → trx → reconciliation chain.
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getReconciliation()).thenReturn(rec);
    // The reconciliation groups exactly this line's transaction — the snapshot reactivate undoes.
    when(rec.getFINFinaccTransactionList()).thenReturn(Collections.singletonList(trx));
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, trx);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    return rec;
  }

  /**
   * Happy path: a reconciled line resolves its reconciliation from the line's transaction, snapshots
   * the reconciliation's transaction list and hands it to the single undo seam
   * {@code undoReconciliation}, then returns a 200 envelope with {@code reactivated:true}. The
   * flow order is checkPeriod (success) → undoReconciliation.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateHappyPathRunsUndoSeam() throws Exception {
    FIN_Reconciliation rec = reconciledLineSetup();
    List<FIN_FinaccTransaction> snapshot = rec.getFINFinaccTransactionList();
    FIN_BankStatementLine normalized = mock(FIN_BankStatementLine.class);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doNothing().when(handler).undoReconciliation(any(), any(), any());
    doReturn(normalized).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    // reactivate unposts the document before capturing anything and then RE-READS the
    // reconciliation by id (ResetAccounting clears the session, so the instance resolved from the
    // line's transaction is detached from that point on). The re-read must yield the same mock the
    // expectations below are built on. currentBalance reads the account's remaining draft
    // reconciliations — keep it side-effect free.
    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-react")).thenReturn(rec);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil =
            mockStatic(ReconciliationRemovalUtil.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      response = handler.reactivate(reactivateBody(ACC_ID, LINE_ID));
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("reactivated"));
    assertEquals(LINE_ID, data.getString("statementLineId"));

    // undoReconciliation is invoked exactly once with the snapshot of the reconciliation list.
    verify(handler).undoReconciliation(any(), eq(rec), eq(snapshot));
    // Order: the period guard runs before the undo. InOrder only over seams that still exist.
    InOrder inOrder = Mockito.inOrder(handler);
    inOrder.verify(handler).checkPeriod(any(), any(), any(), any());
    inOrder.verify(handler).undoReconciliation(any(), any(), any());
    inOrder.verify(handler).normalizeReactivatedMatchGroup(any());
    verify(handler, never()).doRollbackAndClose();
  }

  /**
   * A shared-header batch (see {@link ReconciliationHandler#applySuggestions}) can hold
   * transactions from OTHER statement lines too. Reactivating ONE line whose reconciliation also
   * holds a transaction belonging to a DIFFERENT line must scope the undo to just the clicked
   * line's own transaction — {@code ReconciliationHandlerSupport#detachSelected} (partial path),
   * NEVER {@code undoReconciliation} — leaving the other line's transaction still reconciled.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivatePartialCoverageDetachesOnlyClickedLineTransactions() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-shared");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(rec.getClient()).thenReturn(client);
    when(rec.getOrganization()).thenReturn(org);
    when(rec.getTransactionDate()).thenReturn(null);
    Entity entity = mock(Entity.class);
    when(entity.getTableId()).thenReturn("TBL-1");
    when(rec.getEntity()).thenReturn(entity);

    // Two transactions share ONE reconciliation, each backing a DIFFERENT statement line.
    FIN_FinaccTransaction ownTrx = txnWithId("T-OWN");
    when(ownTrx.getReconciliation()).thenReturn(rec);
    FIN_FinaccTransaction otherLineTrx = txnWithId("T-OTHER");
    when(otherLineTrx.getReconciliation()).thenReturn(rec);
    when(rec.getFINFinaccTransactionList()).thenReturn(Arrays.asList(ownTrx, otherLineTrx));

    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, ownTrx);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());
    doReturn(false).when(handler).isAutoCreated(ownTrx);

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil =
            mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T-OWN")).thenReturn(ownTrx);
      // reactivate re-reads the reconciliation by id after unposting it — the session is cleared by
      // then, so the instance reached through the line's transaction can no longer be trusted.
      when(dal.get(FIN_Reconciliation.class, "rec-shared")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      response = handler.reactivate(reactivateBody(ACC_ID, LINE_ID));

      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(ownTrx));
      recUtil.verify(
          () -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(otherLineTrx),
          never());
    }

    assertEquals(200, response.getHttpStatus());
    // The whole-reconciliation delete path is never taken — only the clicked line's own
    // transaction is detached, the other line's transaction is left reconciled.
    verify(handler, never()).undoReconciliation(any(), any(), any());
    verify(handler).normalizeReactivatedMatchGroup(line);
  }

  /**
   * The whole-line reactivate does not go through {@code removeSelectedFromReconciliations}, so it
   * carries its own copy of the same contract: unpost FIRST, then re-read everything.
   *
   * <p>This is the path that produced, on the live environment,
   * {@code OBInterceptor WARN: FIN_Reconciliation(...) is detected as not new but it does not have a
   * current state in the database} followed by
   * {@code NonUniqueObjectException: ... [FIN_Finacc_Transaction#...]}. {@code ResetAccounting.delete}
   * runs native SQL and flushes/clears the Hibernate session, so the reconciliation resolved from the
   * line's transaction — and the line itself — are detached the moment it returns. Handing those
   * detached instances to Core is what collided with the copies Core reloaded.
   *
   * <p>Three things are pinned, and each regresses on its own: the reset happens at all and over an
   * OPEN date range scoped to this document (passing the reconciliation's own date is the original
   * ETP-4965 defect); the reconciliation handed to the undo is the RE-READ instance, not the one the
   * line pointed at; and the line is re-read too. The fixture answers the two reads of each with two
   * DISTINCT mocks carrying the same identity, which is the only way a mock can tell "re-read" from
   * "reused".
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateUnpostsFirstAndUndoesTheReReadReconciliation() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_FinaccTransaction staleTrx = txnWithId("T1");
    FIN_FinaccTransaction freshTrx = txnWithId("T1");
    FIN_Reconciliation staleRec = recWith("rec-react", staleTrx);
    FIN_Reconciliation freshRec = recWith("rec-react", freshTrx);
    when(staleRec.getPosted()).thenReturn("Y"); // a POSTED document — otherwise there is no reset
    when(freshRec.getPosted()).thenReturn("Y");
    FIN_BankStatementLine staleLine =
        lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, staleTrx);
    FIN_BankStatementLine freshLine =
        lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, freshTrx);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    // The first read is the pre-reset instance the request resolved; every read after it is the copy
    // the session re-materialises.
    doReturn(staleLine, freshLine).when(handler).loadLine(LINE_ID);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doNothing().when(handler).undoReconciliation(any(), any(), any());
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-react")).thenReturn(freshRec);

    NeoResponse response;
    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil =
            mockStatic(ReconciliationRemovalUtil.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      response = handler.reactivate(reactivateBody(ACC_ID, LINE_ID));

      // Scoped to this one document, over an OPEN range — both ends asserted literally, never
      // through anyString(): a date there is the defect this compensates.
      ra.verify(() -> ResetAccounting.delete(eq(CLIENT_ID), eq(ORG_ID), eq("TBL-1"),
          eq("rec-react"), eq(""), eq("")));
      ra.verify(() -> ResetAccounting.delete(anyString(), anyString(), anyString(), anyString(),
          anyString(), anyString()), times(1));
    }

    assertEquals(200, response.getHttpStatus());
    assertNotSame("the fixture must model two distinct instances of the same record", staleRec,
        freshRec);
    // The undo receives the RE-READ reconciliation and its transactions…
    verify(handler).undoReconciliation(any(), eq(freshRec),
        eq(Collections.singletonList(freshTrx)));
    // …never the detached ones the request started from.
    verify(handler, never()).undoReconciliation(any(), eq(staleRec), any());
    // The line is re-read after the reset for the same reason.
    verify(handler, times(2)).loadLine(LINE_ID);
  }

  // ── transactionsOfLineIn: the clicked line's own transactions within a shared rec ──

  /** No match group: a line's own transaction on {@code rec} is returned as the sole result. */
  @Test
  public void testTransactionsOfLineInNoGroupReturnsOwnTransaction() {
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-1");
    FIN_FinaccTransaction trx = txnWithId("T1");
    when(trx.getReconciliation()).thenReturn(rec);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, trx);

    List<FIN_FinaccTransaction> result = handler.transactionsOfLineIn(line, rec);

    assertEquals(1, result.size());
    assertEquals(trx, result.get(0));
  }

  /**
   * Match-group siblings: only the transactions that actually belong to {@code rec} are returned —
   * a sibling whose own transaction hangs off a DIFFERENT reconciliation is excluded.
   */
  @Test
  public void testTransactionsOfLineInSiblingsOnlyOwnRecIncluded() {
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-1");
    FIN_Reconciliation otherRec = mock(FIN_Reconciliation.class);
    when(otherRec.getId()).thenReturn("rec-other");

    FIN_FinaccTransaction ownTrx = txnWithId("T1");
    when(ownTrx.getReconciliation()).thenReturn(rec);
    FIN_BankStatementLine anchor = groupedLine("L1", statement, "GRP-1",
        new BigDecimal("25.00"), BigDecimal.ZERO, ownTrx);

    FIN_FinaccTransaction siblingSameRec = txnWithId("T2");
    when(siblingSameRec.getReconciliation()).thenReturn(rec);
    FIN_BankStatementLine sibling1 = groupedLine("L2", statement, "GRP-1",
        new BigDecimal("25.00"), BigDecimal.ZERO, siblingSameRec);

    FIN_FinaccTransaction siblingOtherRec = txnWithId("T3");
    when(siblingOtherRec.getReconciliation()).thenReturn(otherRec);
    FIN_BankStatementLine sibling2 = groupedLine("L3", statement, "GRP-1",
        new BigDecimal("25.00"), BigDecimal.ZERO, siblingOtherRec);

    doReturn(Arrays.asList(anchor, sibling1, sibling2))
        .when(handler).loadMatchGroupLines(statement, "GRP-1");

    try (MockedStatic<ModelProvider> mp = mockMatchGroupProperty()) {
      List<FIN_FinaccTransaction> result = handler.transactionsOfLineIn(anchor, rec);

      assertEquals(2, result.size());
      assertTrue(result.contains(ownTrx));
      assertTrue(result.contains(siblingSameRec));
      assertFalse(result.contains(siblingOtherRec));
    }
  }

  /** The line's OWN transaction belongs to a DIFFERENT reconciliation than {@code rec}: excluded. */
  @Test
  public void testTransactionsOfLineInOwnTransactionOnDifferentRecExcluded() {
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-1");
    FIN_Reconciliation otherRec = mock(FIN_Reconciliation.class);
    when(otherRec.getId()).thenReturn("rec-other");
    FIN_FinaccTransaction trx = txnWithId("T1");
    when(trx.getReconciliation()).thenReturn(otherRec);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, trx);

    List<FIN_FinaccTransaction> result = handler.transactionsOfLineIn(line, rec);

    assertTrue(result.isEmpty());
  }

  /** A line with no linked transaction (not reconciled) is rejected with a 409, no undo. */
  @Test
  public void testReactivateLineNotReconciledReturns409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    // matched == null → line is not reconciled.
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reactivate(reactivateBody(ACC_ID, LINE_ID));

    assertEquals(409, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("not reconciled"));
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /**
   * Regression: the line IS reconciled (carries a transaction) but that transaction has no
   * reconciliation. This is exactly the second-attempt error: the first reactivate left the line
   * pointing at a transaction whose reconciliation was already undone. The handler returns a 409 and
   * never runs the undo seam.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateLineNotLinkedToReconciliationReturns409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    // trx != null but trx.getReconciliation() == null.
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getReconciliation()).thenReturn(null);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, trx);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reactivate(reactivateBody(ACC_ID, LINE_ID));

    assertEquals(409, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("not linked to a reconciliation"));
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /**
   * When the accounting period is closed, the {@code checkPeriod} seam throws an OBException; the
   * handler maps it to a 409 and never runs the undo seam.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateClosedPeriodReturns409() throws Exception {
    reconciledLineSetup();
    doThrow(new OBException("Period closed"))
        .when(handler).checkPeriod(any(), any(), any(), any());

    NeoResponse response = handler.reactivate(reactivateBody(ACC_ID, LINE_ID));

    assertEquals(409, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("period is closed"));
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /**
   * An error mid-flow (the undo seam throws) propagates out of {@code reactivate}; the route wrapper
   * {@code handleReactivate} rolls back via {@code doRollbackAndClose} and returns a 500.
   *
   * <p>This is the only reactivate test that drives the handler through the public
   * {@code handle(context)} instead of calling the action method directly, so it is the only one
   * that runs {@code ReconciliationHandlerSupport.runPostAction}'s dispatch envelope. That envelope
   * opens with {@code OBContext.setAdminMode(true)}, which needs a live DAL session factory; with
   * none, it throws an NPE that the envelope's own catch-all converts into exactly the 500 +
   * {@code doRollbackAndClose} this test asserts — so the status assertion would pass while the
   * flow never reached the undo seam at all. {@code OBContext} is therefore stubbed to no-ops: the
   * only statics this path touches are {@code setAdminMode} / {@code restorePreviousMode}
   * ({@code reactivate} itself never reads {@code OBContext.getOBContext()}), so blanking the class
   * lets the flow run without a session while keeping the assertions honest — the 500 can only come
   * from the injected mid-flow failure, and {@code undoReconciliation} was genuinely attempted.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateErrorMidFlowRollsBack() throws Exception {
    FIN_Reconciliation rec = reconciledLineSetup();
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doThrow(new RuntimeException("boom")).when(handler).undoReconciliation(any(), any(), any());

    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("POST");
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "reactivate");
    when(context.getQueryParams()).thenReturn(qp);
    JSONObject body = new JSONObject();
    try {
      body.put("financialAccountId", ACC_ID).put("statementLineId", LINE_ID);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    when(context.getRequestBody()).thenReturn(body);

    // OBDal is mocked so the 500 can only come from the undo seam. Without it the unmocked
    // OBDal.getInstance() the re-read now goes through would throw first, and the test would pass
    // for a reason that has nothing to do with the failure it claims to describe.
    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-react")).thenReturn(rec);
    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      response = handler.handle(context);
      // Proves the request really entered the dispatch envelope: without this the assertions below
      // would also be satisfied by a failure raised before the action was ever dispatched.
      obContext.verify(() -> OBContext.setAdminMode(true));
      obContext.verify(() -> OBContext.restorePreviousMode());
    }

    assertEquals(500, response.getHttpStatus());
    verify(handler).undoReconciliation(any(), any(), any());
    verify(handler).doRollbackAndClose();
  }

  /** reactivate without financialAccountId/statementLineId is rejected with a 400 before any load. */
  @Test
  public void testReactivateMissingParamsReturns400() throws Exception {
    NeoResponse response = handler.reactivate(new JSONObject());
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  /** A POST reactivate with no body returns a 400 (body required). */
  @Test
  public void testHandleReactivateNoBodyReturns400() {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("POST");
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "reactivate");
    when(context.getQueryParams()).thenReturn(qp);
    when(context.getRequestBody()).thenReturn(null);
    NeoResponse response = handler.handle(context);
    assertEquals(400, response.getHttpStatus());
  }

  /** An unknown statement line on reactivate yields a 404. */
  @Test
  public void testReactivateMissingLineReturns404() throws Exception {
    doReturn(mock(FIN_FinancialAccount.class)).when(handler).loadAccount(ACC_ID);
    doReturn(null).when(handler).loadLine(LINE_ID);
    NeoResponse response = handler.reactivate(reactivateBody(ACC_ID, LINE_ID));
    assertEquals(404, response.getHttpStatus());
  }

  // ── removeOperation (bulk / single un-reconcile, ETP-4502) ───────────────────

  /**
   * Builds a removeOperation body with the new {@code transactionIds[]} contract:
   * {@code { financialAccountId, statementLineId, transactionIds: [...] }}. When no ids are given
   * the {@code transactionIds} key is omitted (missing/empty case).
   */
  private JSONObject removeBody(String accountId, String lineId, String... transactionIds)
      throws Exception {
    JSONObject b = new JSONObject()
        .put("financialAccountId", accountId)
        .put("statementLineId", lineId);
    if (transactionIds != null && transactionIds.length > 0) {
      JSONArray arr = new JSONArray();
      for (String id : transactionIds) {
        arr.put(id);
      }
      b.put("transactionIds", arr);
    }
    return b;
  }

  /** Builds a body using the legacy single {@code transactionId} key (readTransactionIds fallback). */
  private JSONObject removeBodySingle(String accountId, String lineId, String transactionId)
      throws Exception {
    return new JSONObject()
        .put("financialAccountId", accountId)
        .put("statementLineId", lineId)
        .put("transactionId", transactionId);
  }

  // The translated cause the removal helpers record when Core refuses an un-reconcile. A closed
  // accounting period is by far the commonest, and the only one the user can act on — which is why
  // it has to travel with the 200 instead of staying in the server log.

  /** The Etendo message key Core embeds when the period of the document being unposted is closed. */
  private static final String PERIOD_CLOSED_KEY = "PeriodClosedForUnPosting";

  /**
   * The es_ES AD_Message text for {@link #PERIOD_CLOSED_KEY}, verbatim from the database. Kept in
   * Spanish on purpose: the product is used in Spanish by real clients, and asserting on this exact
   * sentence is what proves Core's English wrapper prose did not travel with it to the client.
   */
  private static final String PERIOD_CLOSED_TRANSLATED =
      "Periodo Cerrado. No se puede descontabilizar un documento en un periodo cerrado";

  /**
   * The untranslated English prose Core wraps each cause in, concatenated with no separator at all
   * — copied verbatim from the live server log.
   */
  private static final String CORE_WRAPPER_PROSE =
      "Error when removing the transaction from reconciliation."
          + "Error when reactivating reconciliation";

  /** The raw exception message Core actually threw, exactly as it reached the handler. */
  private static final String RAW_CORE_CHAIN = CORE_WRAPPER_PROSE + "@" + PERIOD_CLOSED_KEY + "@";

  /**
   * What translating {@link #RAW_CORE_CHAIN} as a WHOLE produces: the placeholder is resolved but
   * the English prose survives in front of it. Wired into every stub below so a regression to
   * whole-string translation shows up as a failed assertion instead of a null.
   */
  private static final String WHOLE_STRING_TRANSLATION =
      CORE_WRAPPER_PROSE + PERIOD_CLOSED_TRANSLATED;

  /**
   * Stubs {@code OBMessageUtils.translateError} to yield {@code message}. The real one resolves the
   * text against AD_Message through a live {@code DalConnectionProvider}, unavailable here. This is
   * the FALLBACK path only — a raw message carrying an {@code @KEY@} placeholder is resolved
   * through {@link #stubMessageBd} instead.
   */
  private void stubTranslateError(MockedStatic<OBMessageUtils> msgMock, String message) {
    OBError translated = mock(OBError.class);
    when(translated.getMessage()).thenReturn(message);
    msgMock.when(() -> OBMessageUtils.translateError(anyString())).thenReturn(translated);
  }

  /**
   * Stubs the AD_Message dictionary lookup for one key — what the handler's failure-reason path
   * consults when Core's raw message carries an {@code @KEY@} placeholder, which is the shape it
   * really has in production.
   */
  private void stubMessageBd(MockedStatic<OBMessageUtils> msgMock, String key, String text) {
    msgMock.when(() -> OBMessageUtils.messageBD(key)).thenReturn(text);
  }

  /** A transaction mock carrying an id (needed for the coversAll set membership check). */
  private FIN_FinaccTransaction txnWithId(String id) {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    when(t.getId()).thenReturn(id);
    return t;
  }

  /**
   * Builds a reconciliation belonging to {@link #ACC_ID}, carrying the metadata the period guard
   * reads ({@code client}, {@code organization}, {@code entity.tableId}, {@code transactionDate}),
   * and grouping exactly the given transactions (each wired back to this reconciliation).
   */
  private FIN_Reconciliation recWith(String recId, FIN_FinaccTransaction... txns) {
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn(recId);
    FIN_FinancialAccount recAcc = mock(FIN_FinancialAccount.class);
    when(recAcc.getId()).thenReturn(ACC_ID);
    when(rec.getAccount()).thenReturn(recAcc);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(rec.getClient()).thenReturn(client);
    when(rec.getOrganization()).thenReturn(org);
    when(rec.getTransactionDate()).thenReturn(null);
    Entity entity = mock(Entity.class);
    when(entity.getTableId()).thenReturn("TBL-1");
    when(rec.getEntity()).thenReturn(entity);
    List<FIN_FinaccTransaction> list = new ArrayList<>();
    for (FIN_FinaccTransaction t : txns) {
      when(t.getReconciliation()).thenReturn(rec);
      list.add(t);
    }
    when(rec.getFINFinaccTransactionList()).thenReturn(list);
    return rec;
  }

  /** Stubs {@code loadAccount}/{@code loadLine} (line belongs to ACC_ID) + {@code loadTransaction}. */
  private FIN_FinancialAccount wireLoads(FIN_FinaccTransaction... loadable) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO,
        loadable.length > 0 ? loadable[0] : null);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    for (FIN_FinaccTransaction t : loadable) {
      // Hoist the id out of the stubbing chain: calling t.getId() (a mock method) as the argument
      // WHILE .when(handler)... is mid-record trips Mockito's UnfinishedStubbingException.
      String tid = t.getId();
      doReturn(t).when(handler).loadTransaction(tid);
    }
    return account;
  }

  /**
   * Stubs the by-id reload every selected transaction goes through before it reaches Core.
   *
   * <p>{@code ReconciliationHandlerSupport#removeSelectedFromReconciliations} now unposts ALL the
   * affected documents in one pass first, and {@code ResetAccounting} runs native SQL that
   * flushes/clears the Hibernate session — so the instances captured at grouping time are detached
   * by the time the removal pass runs, and it re-loads each one by id instead of carrying them over.
   *
   * <p>An unstubbed reload answers {@code null}, which silently drops that transaction from the
   * batch and flips a fully-covered selection into the subset branch. So every test whose selection
   * actually reaches the removal has to wire this, exactly as it already wires the reconciliation's
   * own re-fetch.
   */
  private void wireRefetch(OBDal dal, FIN_FinaccTransaction... txns) {
    for (FIN_FinaccTransaction t : txns) {
      // Hoist the id out of the stubbing chain: reading it while when(...) is mid-record trips
      // Mockito's UnfinishedStubbingException (same reason as wireLoads).
      String tid = t.getId();
      when(dal.get(FIN_FinaccTransaction.class, tid)).thenReturn(t);
    }
  }

  /**
   * Models the DB state TRANSITION that {@code removeOperation}'s outcome report reads as ground
   * truth, instead of hard-wiring only its END state.
   *
   * <p>{@code removeOperation} reads {@code trx.getReconciliation()} twice per requested id, with
   * OPPOSITE expectations. The PRE-check ({@code ReconciliationHandlerSupport
   * #groupSelectedByReconciliation}) needs it NON-null: a transaction that carries no reconciliation
   * is rejected up front with a 409 "Transaction is not linked to a reconciliation", before anything
   * is removed. The POST-check needs it NULL, since that is what proves the removal actually went
   * through (Core's removal utilities commit mid-flow and the removal loops no longer throw, so "no
   * exception" is not evidence). A flat {@code when(t.getReconciliation()).thenReturn(null)} only
   * satisfies the second and makes the whole action abort at the first guard; positional stubbing
   * ({@code thenReturn(rec, null)}) would be brittle, since how many reads happen in between varies
   * per test and per branch.
   *
   * <p>So each transaction answers {@code rec} until a latch is flipped by whichever seam actually
   * frees it in production — Core's per-transaction detach on the subset path (see
   * {@link ReconciliationHandlerTest#freeOnDetach}) or the whole-reconciliation
   * {@code undoReconciliation} seam on the coversAll path (see
   * {@link ReconciliationHandlerTest#freeOnUndo}) — exactly mirroring the state change the
   * post-check is designed to observe.
   */
  private static final class RemovalState {

    private final Map<FIN_FinaccTransaction, AtomicBoolean> latches = new HashMap<>();
    private final Set<FIN_FinaccTransaction> permanentlyLinked = new HashSet<>();

    /**
     * Declares a transaction whose removal DOES take effect: {@code getReconciliation()} answers
     * {@code rec} while the pre-check runs, then {@code null} once the freeing seam has run.
     */
    void linkedUntilFreed(FIN_FinaccTransaction txn, FIN_Reconciliation rec) {
      AtomicBoolean latch = new AtomicBoolean(false);
      latches.put(txn, latch);
      when(txn.getReconciliation()).thenAnswer(inv -> latch.get() ? null : rec);
    }

    /**
     * Declares a transaction whose removal silently does NOT take effect (Core logged and swallowed
     * an internal error, so the DB state is unchanged): it keeps the reconciliation {@code recWith}
     * wired even after being handed to a removal seam. That is the state the handler must report as
     * failed.
     */
    void staysLinked(FIN_FinaccTransaction txn) {
      permanentlyLinked.add(txn);
    }

    /**
     * The effect of a removal seam on {@code txn}. A transaction declared via
     * {@link #staysLinked(FIN_FinaccTransaction)} is deliberately left linked; one that was never
     * declared at all is a test-wiring bug, so it fails loudly instead of silently no-op'ing.
     */
    void free(FIN_FinaccTransaction txn) {
      if (permanentlyLinked.contains(txn)) {
        return;
      }
      AtomicBoolean latch = latches.get(txn);
      assertNotNull("transaction was handed to a removal seam but never declared via "
          + "RemovalState.linkedUntilFreed/staysLinked", latch);
      latch.set(true);
    }
  }

  /**
   * Subset path: Core's per-transaction detach frees the transaction it is handed, so the handler's
   * post-check reads it as genuinely un-reconciled.
   */
  private void freeOnDetach(RemovalState state, MockedStatic<ReconciliationRemovalUtil> recUtil) {
    recUtil.when(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()))
        .thenAnswer(inv -> {
          state.free(inv.getArgument(0));
          return true;
        });
  }

  /**
   * coversAll path: the whole reconciliation is undone, so EVERY transaction handed to the seam
   * loses it at once. Replaces the plain {@code doNothing()} stub of the same seam.
   */
  private void freeOnUndo(RemovalState state) throws Exception {
    doAnswer(inv -> {
      for (FIN_FinaccTransaction t : inv.<List<FIN_FinaccTransaction>>getArgument(2)) {
        state.free(t);
      }
      return null;
    }).when(handler).undoReconciliation(any(), any(), any());
  }

  /**
   * A single id that is the ONLY transaction of the reconciliation → coversAll → whole-line undo
   * ({@code undoReconciliation} + {@code normalizeReactivatedMatchGroup}), never the per-transaction
   * detach. The response echoes the {@code transactionIds[]} array.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationSingleCoversAllDelegatesToUndo() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_Reconciliation rec = recWith("rec-1", t1);
    List<FIN_FinaccTransaction> snapshot = rec.getFINFinaccTransactionList();
    wireLoads(t1);
    // removeOperation's post-check re-fetches each requested transaction and reads its ACTUAL
    // getReconciliation() state (ground truth, since Core commits mid-flow and the removal loops no
    // longer throw). Model the TRANSITION, not just the end state: T1 stays linked for the pre-check
    // that runs first and is freed by the undo seam (see RemovalState).
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(t1, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    freeOnUndo(state);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      // removeSelectedFromReconciliations now re-fetches each reconciliation fresh by id right
      // before dispatching it (avoids Hibernate staleness across reconciliations); the re-fetch
      // must return the exact mock the test built expectations on.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, t1);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1"));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()), never());
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("removed"));
    assertEquals(1, data.getJSONArray("transactionIds").length());
    assertEquals("T1", data.getJSONArray("transactionIds").getString(0));
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
    verify(handler).undoReconciliation(any(), eq(rec), eq(snapshot));
    verify(handler).normalizeReactivatedMatchGroup(any());
  }

  /**
   * The legacy single {@code transactionId} body still works (readTransactionIds fallback): one of N
   * → subset → {@code removeTransactionFromReconciliation} + {@code PaymentRemovalUtil} for the
   * auto-created payment; the other transaction stays reconciled, no whole-line undo.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationSingleIdFallbackSubsetReversesPayment() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_Reconciliation rec = recWith("rec-1", t1, t2);
    FIN_Payment payment = mock(FIN_Payment.class);
    when(t1.getFinPayment()).thenReturn(payment);
    wireLoads(t1, t2);
    // Only T1 was requested — the post-check only re-checks requested ids. It stays linked for the
    // pre-check and is freed by its detach (T2 keeps recWith's link: it was never selected).
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(t1, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(true).when(handler).isAutoCreated(t1);
    // normalizeReactivatedMatchGroup runs once at the end of every success path.
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<TransactionRemovalUtil> trxUtil = mockStatic(TransactionRemovalUtil.class)) {
      // detachSelected re-fetches each selected transaction fresh by id (avoids Hibernate stale
      // instances); removeSelectedFromReconciliations ALSO re-fetches the reconciliation itself
      // fresh by id right before the coversReconciliation check — both re-fetches must return the
      // same mocks the test wired.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      freeOnDetach(state, recUtil);
      response = handler.removeOperation(removeBodySingle(ACC_ID, LINE_ID, "T1"));

      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(t1));
      payUtil.verify(() -> PaymentRemovalUtil.reactivateAndRemove(payment));
      trxUtil.verify(() -> TransactionRemovalUtil.reactivateAndRemove(anyString()), never());
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("removed"));
    assertEquals(1, data.getJSONArray("transactionIds").length());
    assertEquals("T1", data.getJSONArray("transactionIds").getString(0));
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
    verify(handler, never()).undoReconciliation(any(), any(), any());
    verify(handler).normalizeReactivatedMatchGroup(any());
  }

  /**
   * {@code transactionIds} = ALL of N → coversAll → whole-line undo (never the per-transaction
   * detach), regardless of how many docs the group has.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationAllIdsCoversAllDelegatesToUndo() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_Reconciliation rec = recWith("rec-1", t1, t2);
    List<FIN_FinaccTransaction> snapshot = rec.getFINFinaccTransactionList();
    wireLoads(t1, t2);
    // Both requested ids are re-checked by the post-check loop — both stay linked for the pre-check
    // and are freed together by the whole-reconciliation undo.
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(t1, rec);
    state.linkedUntilFreed(t2, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    freeOnUndo(state);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      // Re-fetched fresh by id right before the coversReconciliation check — must return the same
      // mock the test built expectations on.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, t1, t2);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1", "T2"));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()), never());
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("removed"));
    assertEquals(2, data.getJSONArray("transactionIds").length());
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
    verify(handler).undoReconciliation(any(), eq(rec), eq(snapshot));
    verify(handler).normalizeReactivatedMatchGroup(any());
  }

  /**
   * {@code transactionIds} = a SUBSET of N → per-transaction detach loop: each selected transaction
   * is removed from the reconciliation, the auto-created one's payment reversed, the pre-existing one
   * only detached; the unselected transaction is left untouched (no whole-line undo).
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationSubsetLoopsPerTransaction() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1"); // selected, auto-created
    FIN_FinaccTransaction t2 = txnWithId("T2"); // selected, pre-existing
    FIN_FinaccTransaction t3 = txnWithId("T3"); // NOT selected → group not fully covered
    FIN_Reconciliation rec = recWith("rec-1", t1, t2, t3);
    FIN_Payment payment = mock(FIN_Payment.class);
    when(t1.getFinPayment()).thenReturn(payment);
    wireLoads(t1, t2, t3);
    // Both requested ids (T1, T2) are re-checked by the post-check loop — both stay linked for the
    // pre-check and are freed by their own detach. T3 was never selected, so it keeps recWith's link.
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(t1, rec);
    state.linkedUntilFreed(t2, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(true).when(handler).isAutoCreated(t1);
    doReturn(false).when(handler).isAutoCreated(t2);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<TransactionRemovalUtil> trxUtil = mockStatic(TransactionRemovalUtil.class)) {
      // detachSelected re-fetches each selected transaction fresh by id; removeSelectedFromRecon-
      // ciliations ALSO re-fetches the reconciliation itself right before the coversReconciliation
      // check — both re-fetches must return the same mocks the test wired.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
      when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(t2);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      freeOnDetach(state, recUtil);
      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1", "T2"));

      // Both selected transactions detached; only t1 (auto) has its payment reversed.
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(t1));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(t2));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(t3), never());
      payUtil.verify(() -> PaymentRemovalUtil.reactivateAndRemove(payment));
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("removed"));
    assertEquals(2, data.getJSONArray("transactionIds").length());
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
    verify(handler, never()).undoReconciliation(any(), any(), any());
    verify(handler).normalizeReactivatedMatchGroup(any());
  }

  /**
   * Regression for the bulk-un-reconcile bug where only the FIRST of N selected transactions was
   * removed: {@code detachSelected} used to loop over the pre-loaded {@code FIN_FinaccTransaction}
   * instances, but each removal reprocesses the whole reconciliation and churns the Hibernate
   * session, so the 2nd..Nth pre-loaded instances went stale and the next removal died on a
   * {@code NonUniqueObjectException}. The fix snapshots the ids and RE-FETCHES each transaction
   * fresh by id inside the loop.
   *
   * <p>This test selects 3 (of 4) auto-created transactions in one reconciliation (coversRec false
   * → subset detach path) and asserts {@code removeTransactionFromReconciliation} runs exactly 3
   * times (once per selected id) and the response echoes all 3 ids. It FAILS against the old
   * loop-over-instances code (only the first removal succeeds) and PASSES against the re-fetch code.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationSubsetRemovesAllSelectedNotJustFirst() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_FinaccTransaction t3 = txnWithId("T3");
    FIN_FinaccTransaction t4 = txnWithId("T4"); // NOT selected → coversRec is false → subset path
    FIN_Reconciliation rec = recWith("rec-1", t1, t2, t3, t4);
    // All three selected transactions are auto-created payments.
    FIN_Payment p1 = mock(FIN_Payment.class);
    FIN_Payment p2 = mock(FIN_Payment.class);
    FIN_Payment p3 = mock(FIN_Payment.class);
    when(t1.getFinPayment()).thenReturn(p1);
    when(t2.getFinPayment()).thenReturn(p2);
    when(t3.getFinPayment()).thenReturn(p3);
    wireLoads(t1, t2, t3, t4);
    // The three requested ids (T1, T2, T3) are re-checked by the post-check loop — each stays linked
    // for the pre-check and is freed by its own detach, so all three come back as removed (t4 was
    // never requested, so its state is irrelevant here).
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(t1, rec);
    state.linkedUntilFreed(t2, rec);
    state.linkedUntilFreed(t3, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(true).when(handler).isAutoCreated(t1);
    doReturn(true).when(handler).isAutoCreated(t2);
    doReturn(true).when(handler).isAutoCreated(t3);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class)) {
      // The fix re-fetches each selected transaction fresh by id — stub every one. The enclosing
      // reconciliation is ALSO re-fetched fresh right before the coversReconciliation check.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
      when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(t2);
      when(dal.get(FIN_FinaccTransaction.class, "T3")).thenReturn(t3);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      freeOnDetach(state, recUtil);

      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1", "T2", "T3"));

      // The core regression assertion: ALL three selected transactions are detached, not just the
      // first — one removeTransactionFromReconciliation per selected id.
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()),
          times(3));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(t4), never());
      // Each auto-created payment is reversed (3 of them).
      payUtil.verify(() -> PaymentRemovalUtil.reactivateAndRemove(any()), times(3));
    }

    assertEquals(200, response.getHttpStatus());
    verify(handler, never()).undoReconciliation(any(), any(), any());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("removed"));
    JSONArray removedIds = data.getJSONArray("transactionIds");
    assertEquals(3, removedIds.length());
    java.util.List<String> ids = new ArrayList<>();
    for (int i = 0; i < removedIds.length(); i++) {
      ids.add(removedIds.getString(i));
    }
    assertTrue(ids.containsAll(Arrays.asList("T1", "T2", "T3")));
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
  }

  /**
   * Regression pinning the "never report total failure when part of the batch actually succeeded"
   * contract — the core behavioral fix from the investigation of a real Tomcat
   * {@code NonUniqueObjectException}/partial-commit bug the user hit. Core's own removal utilities
   * ({@code PaymentRemovalUtil.reactivateAndRemove}) commit mid-flow ({@code
   * SessionHandler#commitAndStart}), so when un-reconciling several transactions in one request, an
   * item failing partway through does NOT roll back the items that already succeeded — yet the
   * handler used to report a blanket error/success without checking. The fix never aborts the removal
   * loops on one item's failure and, afterward, RE-CHECKS the real DB state of every requested
   * transaction ({@code trx.getReconciliation() == null} → genuinely removed; non-null → still
   * reconciled, i.e. failed) instead of trusting "no exception was thrown".
   *
   * <p>Selects 3 (of 4) transactions in one reconciliation (subset detach path). T1/T2 end up with
   * {@code getReconciliation() == null} (simulating a successful detach); T3 KEEPS its non-null
   * {@code getReconciliation()} (simulating Core's reversal logged-and-swallowed an internal error
   * for that one — the DB state is unchanged). Asserts the HTTP status is STILL a success envelope
   * (200/201 — no exception ever propagates, that is the whole point), {@code removed} is
   * {@code false} (not everything succeeded), {@code transactionIds} contains exactly T1/T2, and
   * {@code failedTransactionIds} contains exactly T3.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationPartialFailureReportsAccurateOutcome() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1"); // will succeed
    FIN_FinaccTransaction t2 = txnWithId("T2"); // will succeed
    FIN_FinaccTransaction t3 = txnWithId("T3"); // will silently fail (Core swallowed an error)
    FIN_FinaccTransaction t4 = txnWithId("T4"); // NOT selected → coversRec is false → subset path
    FIN_Reconciliation rec = recWith("rec-1", t1, t2, t3, t4);
    wireLoads(t1, t2, t3, t4);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(false).when(handler).isAutoCreated(t1);
    doReturn(false).when(handler).isAutoCreated(t2);
    doReturn(false).when(handler).isAutoCreated(t3);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());
    // Post-removal ground truth, modelled as the real TRANSITION: all three are linked while the
    // pre-check runs (otherwise the whole action 409s before removing anything), then T1/T2 are
    // genuinely detached by their removal while T3's detach silently fails inside Core (it stays
    // reconciled) — the loop that processes it never aborts nor rethrows.
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(t1, rec);
    state.linkedUntilFreed(t2, rec);
    state.staysLinked(t3);

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
      when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(t2);
      when(dal.get(FIN_FinaccTransaction.class, "T3")).thenReturn(t3);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      freeOnDetach(state, recUtil);

      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1", "T2", "T3"));
    }

    // The whole point: no exception propagates — still a normal success envelope, never a blanket
    // error, even though one of the three items did not actually go through.
    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertFalse(data.getBoolean("removed"));

    JSONArray removedIds = data.getJSONArray("transactionIds");
    assertEquals(2, removedIds.length());
    List<String> removed = new ArrayList<>();
    for (int i = 0; i < removedIds.length(); i++) {
      removed.add(removedIds.getString(i));
    }
    assertTrue(removed.containsAll(Arrays.asList("T1", "T2")));
    assertFalse(removed.contains("T3"));

    JSONArray failedIds = data.getJSONArray("failedTransactionIds");
    assertEquals(1, failedIds.length());
    assertEquals("T3", failedIds.getString(0));
  }

  /**
   * A line reconciled in several steps has MULTIPLE reconciliations in the same match group. The
   * selection is grouped by reconciliation and each handled independently: {@code rec1} (only txnA)
   * is fully covered → whole-reconciliation undo; {@code rec2} (txnB + txnC, only B selected) is
   * partially covered → per-transaction detach of B (C untouched). {@code normalizeReactivatedMatch
   * Group} runs once at the very end. This is the case the old "different reconciliations → 400"
   * rejection used to (wrongly) block.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationMultipleReconciliationsHandledIndependently() throws Exception {
    FIN_FinaccTransaction tA = txnWithId("A");
    FIN_FinaccTransaction tB = txnWithId("B");
    FIN_FinaccTransaction tC = txnWithId("C");
    FIN_Reconciliation rec1 = recWith("rec-1", tA);          // fully covered by the selection
    List<FIN_FinaccTransaction> rec1Snapshot = rec1.getFINFinaccTransactionList();
    FIN_Reconciliation rec2 = recWith("rec-2", tB, tC);       // only B selected → partial
    wireLoads(tA, tB, tC);
    // Both requested ids (A, B) are re-checked by the post-check loop. Each stays linked for the
    // pre-check and is freed by the seam its own branch uses: A by rec1's whole-reconciliation undo,
    // B by its per-transaction detach in rec2.
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(tA, rec1);
    state.linkedUntilFreed(tB, rec2);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    freeOnUndo(state);
    doReturn(false).when(handler).isAutoCreated(tB);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<TransactionRemovalUtil> trxUtil = mockStatic(TransactionRemovalUtil.class)) {
      // Only rec2's B goes through detachSelected (re-fetched fresh by id); A takes the undo path.
      // removeSelectedFromReconciliations now re-fetches EACH reconciliation fresh by id right
      // before dispatching it — stub both, returning the exact mocks the test built.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, tA, tB);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec1);
      when(dal.get(FIN_Reconciliation.class, "rec-2")).thenReturn(rec2);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      freeOnDetach(state, recUtil);
      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "A", "B"));

      // rec2 partial: only B detached, C left untouched. rec1 takes the whole-reconciliation undo,
      // so A is NOT individually detached.
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(tB));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(tC), never());
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(tA), never());
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("removed"));
    assertEquals(2, data.getJSONArray("transactionIds").length());
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
    // rec1 fully covered → its whole reconciliation is undone.
    verify(handler).undoReconciliation(any(), eq(rec1), eq(rec1Snapshot));
    // The match-group collapse happens exactly once, after all reconciliations are processed.
    verify(handler).normalizeReactivatedMatchGroup(any());
  }

  /**
   * Regression for the SECOND {@code NonUniqueObjectException} in bulk un-reconcile — this one
   * across RECONCILIATIONS rather than across transactions within one (the bug the per-transaction
   * fix in {@code detachSelected} did not cover). Bulk-un-reconciling ALL lines at once can span
   * MULTIPLE {@code FIN_Reconciliation} records, each fully covered by the selection (the
   * "coversAll"/{@code undoWholeReconciliation} branch for both). Both {@code
   * ReconciliationRemovalUtil.removeTransactionFromReconciliation} (used by {@code detachSelected})
   * and {@code handler.undoReconciliation} (used by {@code undoWholeReconciliation}) call Core's
   * {@code getDraftReconciliation}/{@code processAllReconciliationInDraft} internally, which
   * reprocess EVERY draft reconciliation of the WHOLE ACCOUNT as a side effect — not just the one
   * being handled. So processing reconciliation #1 in the loop could silently touch/reprocess
   * reconciliation #2's session state, leaving the #2 instance captured earlier — at grouping time,
   * before any processing — stale, so a later {@code save} on it collided with the session's fresh
   * copy.
   *
   * <p>The fix re-fetches EACH reconciliation fresh right before dispatching it (instead of reusing
   * the instance cached in {@code recById} at grouping time). This test selects two SEPARATE,
   * fully-covered reconciliations and asserts {@code undoReconciliation} runs exactly twice — once
   * per reconciliation — with the response echoing every transaction id from both. It FAILS against
   * the old cached-instance loop (a stale rec1/rec2 read at grouping time) — in a real Hibernate
   * session this dies with a {@code NonUniqueObjectException} on the second reconciliation, though a
   * pure-mock unit test cannot reproduce that exception itself; the observable proxy here is that
   * the fix's fresh-fetch contract is exercised via the {@code OBDal.get(FIN_Reconciliation.class,
   * id)} stubs — and PASSES against the re-fetch code.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationMultipleReconciliationsAllCoveredEachUndoneIndependently()
      throws Exception {
    FIN_FinaccTransaction tA = txnWithId("A");
    FIN_FinaccTransaction tB = txnWithId("B");
    FIN_Reconciliation recA = recWith("rec-A", tA); // fully covered by the selection
    FIN_Reconciliation recB = recWith("rec-B", tB); // fully covered by the selection
    List<FIN_FinaccTransaction> recASnapshot = recA.getFINFinaccTransactionList();
    List<FIN_FinaccTransaction> recBSnapshot = recB.getFINFinaccTransactionList();
    wireLoads(tA, tB);
    // Both requested ids (A, B) are re-checked by the post-check loop. Each stays linked for the
    // pre-check and is freed by the undo of ITS OWN reconciliation, so a per-reconciliation outcome
    // is observable — a single shared flag would not distinguish the two undos.
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(tA, recA);
    state.linkedUntilFreed(tB, recB);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    freeOnUndo(state);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      // Each reconciliation is re-fetched fresh by id right before dispatching — stub both.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, tA, tB);
      when(dal.get(FIN_Reconciliation.class, "rec-A")).thenReturn(recA);
      when(dal.get(FIN_Reconciliation.class, "rec-B")).thenReturn(recB);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "A", "B"));

      // Neither reconciliation takes the per-transaction detach path — both are fully covered.
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()), never());
    }

    assertEquals(200, response.getHttpStatus());
    // Each fully-covered reconciliation is undone independently — once per reconciliation, not just
    // the first one processed.
    verify(handler, times(2)).undoReconciliation(any(), any(), any());
    verify(handler).undoReconciliation(any(), eq(recA), eq(recASnapshot));
    verify(handler).undoReconciliation(any(), eq(recB), eq(recBSnapshot));
    verify(handler).normalizeReactivatedMatchGroup(any());

    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("removed"));
    JSONArray removedIds = data.getJSONArray("transactionIds");
    assertEquals(2, removedIds.length());
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < removedIds.length(); i++) {
      ids.add(removedIds.getString(i));
    }
    assertTrue(ids.containsAll(Arrays.asList("A", "B")));
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
  }

  /**
   * A closed accounting period makes the {@code checkPeriod} seam throw an OBException; the handler
   * maps it to a 409 and touches neither the detach nor the undo path.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationClosedPeriodReturns409() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    recWith("rec-1", t1, t2);
    wireLoads(t1, t2);
    doThrow(new OBException("Period closed")).when(handler).checkPeriod(any(), any(), any(), any());

    NeoResponse response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1"));

    assertEquals(409, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("period is closed"));
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /**
   * A transaction whose {@code getReconciliation()} is null is not reconciled: 409, no undo/detach.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationTransactionNotReconciledReturns409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("T1");
    when(trx.getReconciliation()).thenReturn(null);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, trx);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("T1");

    NeoResponse response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1"));

    assertEquals(409, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("not linked to a reconciliation"));
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /**
   * A transaction whose reconciliation belongs to ANOTHER financial account is rejected with a 400.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationTransactionOfAnotherAccountReturns400() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_FinaccTransaction trx = txnWithId("T1");
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-1");
    FIN_FinancialAccount otherAcc = mock(FIN_FinancialAccount.class);
    when(otherAcc.getId()).thenReturn(OTHER_ACC);
    when(rec.getAccount()).thenReturn(otherAcc);
    when(trx.getReconciliation()).thenReturn(rec);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, trx);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("T1");

    NeoResponse response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1"));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("do not belong to the financial account"));
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /** removeOperation with no transaction ids at all is rejected with a 400 before any load. */
  @Test
  public void testRemoveOperationMissingIdsReturns400() throws Exception {
    NeoResponse response = handler.removeOperation(removeBody(ACC_ID, LINE_ID));
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  // ── reactivateSelected ("Reactivar": same detach/undo mechanics as removeOperation) ──
  // Rewritten for the batch-header refactor (T1): a shared reconciliation can hold OTHER lines'
  // transactions too, so there is no longer a reconciliation-wide DRAFT state to hand back to the
  // user. reactivateSelected now delegates to the EXACT SAME helper "Desconciliar" uses —
  // ReconciliationHandlerSupport#removeSelectedFromReconciliations — reactivate the reconciliation,
  // detach just the selected transactions, and re-confirm it immediately (the document stays
  // Completed, never left in draft), then calls normalizeReactivatedMatchGroup. The response no
  // longer carries an "autoConfirmedDrafts" field, and the success/failure ground truth is the same
  // one removeOperation already uses: {@code trx.getReconciliation() == null} per requested id.

  /**
   * Full coverage (every transaction of the reconciliation is selected) → the whole-reconciliation
   * undo ({@code undoReconciliation}), never the per-transaction detach or Core's plain
   * {@code reactivate} (that mechanism no longer exists).
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedAllCoveredDelegatesToUndo() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_Reconciliation rec = recWith("rec-1", t1, t2);
    List<FIN_FinaccTransaction> snapshot = rec.getFINFinaccTransactionList();
    wireLoads(t1, t2);
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(t1, rec);
    state.linkedUntilFreed(t2, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    freeOnUndo(state);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, t1, t2);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1", "T2"));

      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()), never());
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("reactivated"));
    assertEquals(2, data.getJSONArray("transactionIds").length());
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
    // "autoConfirmedDrafts" no longer exists in the response at all.
    assertFalse(data.has("autoConfirmedDrafts"));
    verify(handler).undoReconciliation(any(), eq(rec), eq(snapshot));
    verify(handler).normalizeReactivatedMatchGroup(any());
  }

  /**
   * A subset selection (mixed auto-created + pre-existing, a third transaction left untouched):
   * per-transaction detach loop — the auto-created one's payment is reversed, the pre-existing one
   * is only detached, the un-selected transaction stays reconciled. The whole-reconciliation undo
   * never runs.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedSubsetDetachesOnlySelected() throws Exception {
    FIN_FinaccTransaction auto = txnWithId("T-AUTO"); // selected, auto-created
    FIN_FinaccTransaction kept = txnWithId("T-KEPT"); // selected, pre-existing
    FIN_FinaccTransaction untouched = txnWithId("T-UNTOUCHED"); // NOT selected
    FIN_Reconciliation rec = recWith("rec-1", auto, kept, untouched);
    FIN_Payment payment = mock(FIN_Payment.class);
    when(auto.getFinPayment()).thenReturn(payment);
    wireLoads(auto, kept, untouched);
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(auto, rec);
    state.linkedUntilFreed(kept, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(true).when(handler).isAutoCreated(auto);
    doReturn(false).when(handler).isAutoCreated(kept);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T-AUTO")).thenReturn(auto);
      when(dal.get(FIN_FinaccTransaction.class, "T-KEPT")).thenReturn(kept);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      freeOnDetach(state, recUtil);

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T-AUTO", "T-KEPT"));

      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(auto));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(kept));
      recUtil.verify(
          () -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(untouched), never());
      payUtil.verify(() -> PaymentRemovalUtil.reactivateAndRemove(payment));
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("reactivated"));
    assertEquals(2, data.getJSONArray("transactionIds").length());
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
    verify(handler, never()).undoReconciliation(any(), any(), any());
    verify(handler).normalizeReactivatedMatchGroup(any());
  }

  /**
   * A selected auto-created (rule-origin) transaction with NO backing payment is reversed via
   * {@code TransactionRemovalUtil.reactivateAndRemove}, not {@code PaymentRemovalUtil} — same branch
   * {@code removeOperation}/{@code detachSelected} already covers.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedAutoCreatedWithoutPaymentReversesTransaction() throws Exception {
    FIN_FinaccTransaction auto = txnWithId("T1"); // selected, auto-created, no payment
    FIN_FinaccTransaction untouched = txnWithId("T2"); // NOT selected → does not cover the rec
    FIN_Reconciliation rec = recWith("rec-1", auto, untouched);
    wireLoads(auto, untouched);
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(auto, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(true).when(handler).isAutoCreated(auto);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<TransactionRemovalUtil> trxUtil = mockStatic(TransactionRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(auto);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      freeOnDetach(state, recUtil);

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1"));

      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(auto));
      trxUtil.verify(() -> TransactionRemovalUtil.reactivateAndRemove("T1"));
      payUtil.verify(() -> PaymentRemovalUtil.reactivateAndRemove(any()), never());
    }

    assertEquals(200, response.getHttpStatus());
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /**
   * Reused as-is from {@code removeOperation}: {@code reactivateSelected} now calls
   * {@code normalizeReactivatedMatchGroup(line)} at the end of every success path — it did NOT do
   * this before the refactor (the reconciliation used to stay linked-but-draft, so there was nothing
   * to collapse back).
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedNormalizesMatchGroupOnSuccess() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_Reconciliation rec = recWith("rec-1", t1);
    wireLoads(t1);
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(t1, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(false).when(handler).isAutoCreated(t1);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, t1);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      freeOnUndo(state);

      handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1"));
    }

    verify(handler).normalizeReactivatedMatchGroup(any());
  }

  /**
   * Regression pinning the same "never report total failure when part of the batch actually
   * succeeded" contract {@code removeOperation} already has: ground truth is the real post-state
   * ({@code trx.getReconciliation() == null}), not any {@code isProcessed()} read on the
   * reconciliation.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedReportsPerTransactionFailure() throws Exception {
    FIN_FinaccTransaction okTxn = txnWithId("T-OK");
    FIN_FinaccTransaction badTxn = txnWithId("T-BAD");
    FIN_FinaccTransaction untouched = txnWithId("T-UNTOUCHED");
    FIN_Reconciliation rec = recWith("rec-1", okTxn, badTxn, untouched);
    wireLoads(okTxn, badTxn, untouched);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(false).when(handler).isAutoCreated(okTxn);
    doReturn(false).when(handler).isAutoCreated(badTxn);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());
    // OK detaches for real; BAD stays linked (Core logged-and-swallowed an internal error) — same
    // TRANSITION modelling removeOperation's equivalent regression test uses.
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(okTxn, rec);
    state.staysLinked(badTxn);

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T-OK")).thenReturn(okTxn);
      when(dal.get(FIN_FinaccTransaction.class, "T-BAD")).thenReturn(badTxn);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      freeOnDetach(state, recUtil);

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T-OK", "T-BAD"));
    }

    // No exception propagates — the batch reports the real per-transaction outcome.
    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertFalse(data.getBoolean("reactivated"));
    assertEquals(1, data.getJSONArray("transactionIds").length());
    assertEquals("T-OK", data.getJSONArray("transactionIds").getString(0));
    assertEquals(1, data.getJSONArray("failedTransactionIds").length());
    assertEquals("T-BAD", data.getJSONArray("failedTransactionIds").getString(0));
  }

  /** reactivateSelected with no transaction ids is rejected with a 400 before any load. */
  @Test
  public void testReactivateSelectedMissingIdsReturns400() throws Exception {
    NeoResponse response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID));
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  /**
   * A closed accounting period is refused with a 409 by the SHARED {@code guardOpenPeriods} helper,
   * before anything is reactivated — identical to {@code removeOperation}.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedClosedPeriodReturns409() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    recWith("rec-1", t1);
    wireLoads(t1);
    doThrow(new OBException("Period closed")).when(handler).checkPeriod(any(), any(), any(), any());

    NeoResponse response;
    try (MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1"));
      recUtil.verify(
          () -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()), never());
    }

    assertEquals(409, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("period is closed"));
  }

  // ── failure REASON travelling with the 200 (un-reconcile / reactivate) ───────
  // The removal helpers swallow a per-unit exception on purpose (Core commits mid-flow, so aborting
  // the batch would only leave the rest unprocessed on top of the failure), and the handler already
  // reports the real per-transaction outcome — that part was never broken. What was missing is the
  // CAUSE: the swallowed exception only reached the server log, so the client knew WHICH ids failed
  // but not WHY and fell back to a generic message. Note the up-front `guardOpenPeriods` does NOT
  // make these cases unreachable: it checks the RECONCILIATION's period, while the failure modelled
  // here is one Core raises deeper in the undo (the underlying payment's own posting period), which
  // is exactly why the reason has to be carried out of the catch block.

  /**
   * {@code removeOperation}: the whole-document undo throws, the transaction consequently stays
   * reconciled, and the 200 carries BOTH the failed id and the translated cause.
   *
   * <p>The simulated failure carries the REAL raw message Core throws — English wrapper prose
   * concatenated with no separator in front of an {@code @KEY@} placeholder — so the end-to-end
   * assertion is that the client receives the dictionary sentence alone.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationUndoFailureCarriesTheReasonWithTheFailedIds() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_Reconciliation rec = recWith("rec-1", t1); // single txn → coversAll → undo path
    wireLoads(t1);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doThrow(new OBException(RAW_CORE_CHAIN)).when(handler)
        .undoReconciliation(any(), any(), any());
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());
    // The undo failed, so T1 keeps the reconciliation recWith already wired — the post-check reads
    // that as "still reconciled" and reports it as failed.

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, t1);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      stubMessageBd(msgMock, PERIOD_CLOSED_KEY, PERIOD_CLOSED_TRANSLATED);
      stubTranslateError(msgMock, WHOLE_STRING_TRANSLATION);

      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1"));
    }

    // Still a 200 with the accurate per-transaction outcome — the response shape is unchanged.
    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertFalse(data.getBoolean("removed"));
    assertEquals(0, data.getJSONArray("transactionIds").length());
    assertEquals(1, data.getJSONArray("failedTransactionIds").length());
    assertEquals("T1", data.getJSONArray("failedTransactionIds").getString(0));
    // …plus the new part: the cause the client shows verbatim — the dictionary sentence only, with
    // none of Core's English wrapper prose glued in front of it.
    assertTrue(data.has("failureReason"));
    assertEquals(PERIOD_CLOSED_TRANSLATED, data.getString("failureReason"));
    assertFalse("Core's English wrapper prose must not reach the client",
        data.getString("failureReason").contains("Error when reactivating reconciliation"));
  }

  /**
   * {@code removeOperation}, subset path: one detach throws, another succeeds. The partial outcome
   * still carries a reason, and it is the one recorded for the id actually reported as failed.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationPartialFailureCarriesTheReasonOfTheFailedId() throws Exception {
    FIN_FinaccTransaction ok = txnWithId("T-OK");
    FIN_FinaccTransaction bad = txnWithId("T-BAD");
    FIN_FinaccTransaction untouched = txnWithId("T-UNTOUCHED"); // keeps the selection a subset
    FIN_Reconciliation rec = recWith("rec-1", ok, bad, untouched);
    wireLoads(ok, bad, untouched);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(false).when(handler).isAutoCreated(any());
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(ok, rec);
    state.staysLinked(bad);

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T-OK")).thenReturn(ok);
      when(dal.get(FIN_FinaccTransaction.class, "T-BAD")).thenReturn(bad);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      stubMessageBd(msgMock, PERIOD_CLOSED_KEY, PERIOD_CLOSED_TRANSLATED);
      stubTranslateError(msgMock, WHOLE_STRING_TRANSLATION);
      // T-OK really detaches; T-BAD's detach throws inside Core and is swallowed by the helper.
      recUtil.when(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()))
          .thenAnswer(inv -> {
            if (inv.getArgument(0) == bad) {
              throw new OBException(RAW_CORE_CHAIN);
            }
            state.free(inv.getArgument(0));
            return true;
          });

      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T-OK", "T-BAD"));
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertFalse(data.getBoolean("removed"));
    assertEquals(1, data.getJSONArray("transactionIds").length());
    assertEquals("T-OK", data.getJSONArray("transactionIds").getString(0));
    assertEquals(1, data.getJSONArray("failedTransactionIds").length());
    assertEquals("T-BAD", data.getJSONArray("failedTransactionIds").getString(0));
    assertEquals(PERIOD_CLOSED_TRANSLATED, data.getString("failureReason"));
  }

  /**
   * Nothing failed → the key is ABSENT from the payload, not present-and-empty. A client that reads
   * {@code result.failureReason} to decide whether to attach a toast description must see
   * {@code undefined}, so an empty-string key would render an empty description box.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationFullSuccessOmitsTheFailureReasonKey() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_Reconciliation rec = recWith("rec-1", t1);
    wireLoads(t1);
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(t1, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    freeOnUndo(state);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, t1);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1"));
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("removed"));
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
    assertFalse("a successful un-reconcile must not carry a failureReason key at all",
        data.has("failureReason"));
  }

  /**
   * A failure whose exception carries no usable message must not manufacture an empty reason: the
   * ids still travel, the key does not. An empty string here would reach the client as a truthy-
   * looking-but-blank description.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testRemoveOperationBlankFailureMessageOmitsTheFailureReasonKey() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_Reconciliation rec = recWith("rec-1", t1);
    wireLoads(t1);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doThrow(new OBException()).when(handler).undoReconciliation(any(), any(), any());
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, t1);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      // Translating a blank message yields nothing usable — the real OBMessageUtils behaviour for
      // an exception with a null message.
      stubTranslateError(msgMock, null);

      response = handler.removeOperation(removeBody(ACC_ID, LINE_ID, "T1"));
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertFalse(data.getBoolean("removed"));
    assertEquals(1, data.getJSONArray("failedTransactionIds").length());
    assertFalse("a blank cause must not ship as an empty failureReason",
        data.has("failureReason"));
  }

  /**
   * {@code reactivateSelected} carries the reason exactly like {@code removeOperation} — same
   * accumulator, same key, same 200 envelope. Only the copy the client picks differs.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedUndoFailureCarriesTheReasonWithTheFailedIds() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_Reconciliation rec = recWith("rec-1", t1, t2); // whole selection → coversAll → undo path
    wireLoads(t1, t2);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doThrow(new OBException(RAW_CORE_CHAIN)).when(handler)
        .undoReconciliation(any(), any(), any());
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, t1, t2);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      stubMessageBd(msgMock, PERIOD_CLOSED_KEY, PERIOD_CLOSED_TRANSLATED);
      stubTranslateError(msgMock, WHOLE_STRING_TRANSLATION);

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1", "T2"));
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertFalse(data.getBoolean("reactivated"));
    assertEquals(2, data.getJSONArray("failedTransactionIds").length());
    // One Core call for the whole document → every requested id has the same recorded cause, so the
    // reason is available whichever id the post-check happened to report first.
    assertEquals(PERIOD_CLOSED_TRANSLATED, data.getString("failureReason"));
    assertFalse("Core's English wrapper prose must not reach the client",
        data.getString("failureReason").contains("Error when removing the transaction"));
  }

  /**
   * The reactivate mirror of {@link #testRemoveOperationFullSuccessOmitsTheFailureReasonKey}: no
   * failure, no key.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedFullSuccessOmitsTheFailureReasonKey() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_Reconciliation rec = recWith("rec-1", t1, t2);
    wireLoads(t1, t2);
    RemovalState state = new RemovalState();
    state.linkedUntilFreed(t1, rec);
    state.linkedUntilFreed(t2, rec);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    freeOnUndo(state);
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      wireRefetch(dal, t1, t2);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1", "T2"));
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("reactivated"));
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
    assertFalse("a successful reactivate must not carry a failureReason key at all",
        data.has("failureReason"));
  }

  // ── buildCandidates on ANY linked line: unconditionally read-only ────────────
  // The old "Reactivar leaves the reconciliation in draft, the line stays editable with its own
  // transactions pre-selected" branch is gone: buildCandidates no longer has a draftRec-aware SQL
  // path or CANDIDATES_SQL OR-clause at all. A line with ANY linked transaction — draft or
  // processed reconciliation, it no longer matters — goes straight to the read-only
  // CandidatesSupport.buildLinkedTransactions(lineId) shortcut.

  /**
   * A line whose transaction is linked routes to the read-only linked-transactions path
   * unconditionally — never the SQL/suggested-candidates path — regardless of whether that
   * transaction's reconciliation is processed or still draft.
   *
   * @throws Exception if the seams fail
   */
  @Test
  public void testCandidatesLineWithAnyLinkedTransactionTakesReadOnlyPath() throws Exception {
    FIN_FinaccTransaction linkedTxn = txnWithId("t1");
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, linkedTxn);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse expected = NeoResponse.ok(new JSONObject());
    try (MockedStatic<CandidatesSupport> cs = mockStatic(CandidatesSupport.class)) {
      cs.when(() -> CandidatesSupport.buildLinkedTransactions(LINE_ID)).thenReturn(expected);

      // buildCandidates now resolves the account for the ownership gate (ETP-4950).
      doReturn(mock(FIN_FinancialAccount.class)).when(handler).loadAccount(ACC_ID);
      NeoResponse response = handler.buildCandidates(ACC_ID, LINE_ID, null, null, null);

      // Reaching this line without an NPE/exception from an unmocked OBDal SQL path already proves
      // the SQL branch was never taken; the explicit verify pins the actual dispatch.
      assertNotNull(response);
      cs.verify(() -> CandidatesSupport.buildLinkedTransactions(LINE_ID));
    }
  }

  // ── reconcileGroup / applySuggestions: the simplified "already reconciled" guards ──
  // The "Reactivar leaves the reconciliation in DRAFT with the link intact" exemption these guards
  // used to carry is gone entirely — that state can no longer occur (see above). Each guard is back
  // to its plain form: any linked transaction (draft or processed, doesn't matter) is rejected.

  /**
   * A line whose transaction hangs off an UNPROCESSED (draft) reconciliation is rejected with the
   * SAME plain 409 as one hanging off a processed one — {@code reconcileGroup}'s guard is simply
   * {@code line.getFinancialAccountTransaction() != null}, unconditional on {@code isProcessed()}.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupStillRejectsUnprocessedDraftLinkedLineWith409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_Reconciliation draft = mock(FIN_Reconciliation.class);
    when(draft.isProcessed()).thenReturn(false);
    FIN_FinaccTransaction matched = mock(FIN_FinaccTransaction.class);
    when(matched.getReconciliation()).thenReturn(draft);
    FIN_BankStatementLine line =
        lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, matched);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(409, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("already reconciled"));
    verify(handler, never()).addNewDraftReconciliation(any());
  }

  /**
   * Guard regression ({@code reconcileGroup}): a genuinely reconciled line — its reconciliation is
   * PROCESSED — is still refused with a 409.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupStillRejectsProcessedReconciliationWith409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_Reconciliation processed = mock(FIN_Reconciliation.class);
    when(processed.isProcessed()).thenReturn(true);
    FIN_FinaccTransaction matched = mock(FIN_FinaccTransaction.class);
    when(matched.getReconciliation()).thenReturn(processed);
    FIN_BankStatementLine line =
        lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, matched);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(409, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("already reconciled"));
    verify(handler, never()).addNewDraftReconciliation(any());
  }

  /**
   * Guard regression ({@code prepareGroup}, the per-group validation {@code applySuggestions} now
   * folds into — there is no standalone {@code applyGroup} method any more): the same
   * processed-reconciliation case, recorded as a per-group error in the {@code results} array
   * (applySuggestions reports per group rather than failing the whole request).
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testApplySuggestionsStillRecordsAlreadyReconciledForProcessedRec() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_Reconciliation processed = mock(FIN_Reconciliation.class);
    when(processed.isProcessed()).thenReturn(true);
    FIN_FinaccTransaction matched = mock(FIN_FinaccTransaction.class);
    when(matched.getReconciliation()).thenReturn(processed);
    FIN_BankStatementLine line =
        lineFor(ACC_ID, new BigDecimal("10.00"), BigDecimal.ZERO, matched);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    JSONObject group = new JSONObject()
        .put("statementLineId", LINE_ID)
        .put("operationIds", new JSONArray().put("t1"));
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(group));

    NeoResponse response = handler.applySuggestions(body);

    JSONObject result = response.getBody().getJSONObject("response").getJSONObject("data")
        .getJSONArray("results").getJSONObject(0);
    assertTrue(result.getJSONObject("error").getString("message").contains("already reconciled"));
    verify(handler, never()).addNewDraftReconciliation(any());
  }

  /**
   * {@code ReconciliationFlowSupport}'s per-operation guard: {@code ownDraftId}/{@code ownDraftIdOf}
   * are gone entirely (deleted along with the "Reactivar leaves it linked-but-draft" mechanism), so
   * the check is now simply {@code trx.getReconciliation() != null}. An operation hanging off ANY
   * other reconciliation is still a 409 conflict, even though the line itself is genuinely
   * UNRECONCILED (guard 1 lets it through, so {@code validateOperations} is actually reached).
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupStillRejectsOperationOnAnotherReconciliation() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    // The requested operation belongs to a DIFFERENT reconciliation.
    FIN_Reconciliation otherRec = mock(FIN_Reconciliation.class);
    when(otherRec.getId()).thenReturn("rec-other");
    FIN_FinaccTransaction foreignTxn =
        trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, otherRec);
    doReturn(foreignTxn).when(handler).loadTransaction("t-other");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t-other"));

    assertEquals(409, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("Operation is already reconciled"));
    verify(handler, never()).addNewDraftReconciliation(any());
    verify(handler, never()).processReconciliation(any());
  }

  /** Reactivating an ETGO split group merges its unmatched siblings back into one physical line. */
  @Test
  public void testNormalizeReactivatedMatchGroupMergesSiblings() throws Exception {
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    when(statement.getId()).thenReturn("BST-1");
    when(statement.isProcessed()).thenReturn(Boolean.TRUE);
    FIN_BankStatementLine anchor = groupedLine("L1", statement, "GRP-1",
        new BigDecimal("25.30"), BigDecimal.ZERO, null);
    FIN_BankStatementLine sibling = groupedLine("L2", statement, "GRP-1",
        new BigDecimal("25.30"), BigDecimal.ZERO, null);
    doReturn(Arrays.asList(anchor, sibling)).when(handler).loadMatchGroupLines(statement, "GRP-1");

    try (MockedStatic<ModelProvider> mp = mockMatchGroupProperty();
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      FIN_BankStatementLine result = handler.normalizeReactivatedMatchGroup(anchor);

      assertEquals(anchor, result);
      verify(statement).setProcessed(false);
      verify(statement).setProcessed(true);
      verify(dal).remove(sibling);
      verify(anchor).setCramount(new BigDecimal("50.60"));
      verify(anchor).setDramount(BigDecimal.ZERO);
      verify(anchor).setFinancialAccountTransaction(null);
      verify(anchor).setMatchingtype(null);
      verify(anchor).setMatchedDocument(null);
      verify(anchor).set("matchGroupId", null);
    }
  }

  /** A split group is left untouched when any sibling still points at a transaction. */
  @Test
  public void testNormalizeReactivatedMatchGroupSkipsWhenSiblingStillLinked() throws Exception {
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    when(statement.getId()).thenReturn("BST-1");
    FIN_FinaccTransaction linked = mock(FIN_FinaccTransaction.class);
    when(linked.getId()).thenReturn("T-LINKED");
    FIN_BankStatementLine anchor = groupedLine("L1", statement, "GRP-1",
        new BigDecimal("25.30"), BigDecimal.ZERO, null);
    FIN_BankStatementLine sibling = groupedLine("L2", statement, "GRP-1",
        new BigDecimal("25.30"), BigDecimal.ZERO, linked);
    doReturn(Arrays.asList(anchor, sibling)).when(handler).loadMatchGroupLines(statement, "GRP-1");

    try (MockedStatic<ModelProvider> mp = mockMatchGroupProperty();
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      FIN_BankStatementLine result = handler.normalizeReactivatedMatchGroup(anchor);

      assertEquals(anchor, result);
      verify(statement, never()).setProcessed(false);
      verify(dal, never()).remove(any(FIN_BankStatementLine.class));
      verify(anchor, never()).setCramount(any());
      verify(anchor, never()).set("matchGroupId", null);
    }
  }

  /** A lone residual matchGroupId is harmless: the line is kept and the marker cleared. */
  @Test
  public void testNormalizeReactivatedMatchGroupSingletonClearsMarker() throws Exception {
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    FIN_BankStatementLine anchor = groupedLine("L1", statement, "GRP-1",
        new BigDecimal("50.60"), BigDecimal.ZERO, null);
    doReturn(Collections.singletonList(anchor)).when(handler).loadMatchGroupLines(statement, "GRP-1");

    try (MockedStatic<ModelProvider> mp = mockMatchGroupProperty();
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      FIN_BankStatementLine result = handler.normalizeReactivatedMatchGroup(anchor);

      assertEquals(anchor, result);
      verify(anchor).set("matchGroupId", null);
      verify(dal).save(anchor);
      verify(statement, never()).setProcessed(false);
      verify(dal, never()).remove(any(FIN_BankStatementLine.class));
    }
  }

  /**
   * {@code markAutoCreated} sets the runtime-resolved {@code EM_ETGO_Auto_Created} property to
   * {@code true}, and {@code isAutoCreated} reads it back via the same property.
   */
  @Test
  public void testMarkAndIsAutoCreatedRoundTrip() {
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(prop.getName()).thenReturn("eTGOAutoCreated");
    when(entity.getPropertyByColumnName(eq("EM_ETGO_Auto_Created"), eq(false))).thenReturn(prop);

    try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class)) {
      ModelProvider provider = mock(ModelProvider.class);
      mp.when(ModelProvider::getInstance).thenReturn(provider);
      when(provider.getEntity(FIN_FinaccTransaction.ENTITY_NAME)).thenReturn(entity);

      // markAutoCreated sets the flag via the resolved property name.
      ReactivationSupport.markAutoCreated(trx);
      verify(trx).set("eTGOAutoCreated", Boolean.TRUE);

      // isAutoCreated reads the same property back.
      when(trx.get("eTGOAutoCreated")).thenReturn(Boolean.TRUE);
      assertTrue(handler.isAutoCreated(trx));

      when(trx.get("eTGOAutoCreated")).thenReturn(Boolean.FALSE);
      assertFalse(handler.isAutoCreated(trx));
    }
  }

  // ── undoReconciliation: restoreNotClearedStatus by direction ──────────────────

  /**
   * Regression: the payment.removal module's {@code reactivateAndRemoveReconciliation} leaves a kept
   * (non-auto-created) DEPOSIT transaction in {@code PWNC} ("Withdrawn not cleared") instead of
   * {@code RDNC} ("Deposited not cleared"). {@code undoReconciliation} must re-set every kept
   * transaction's status by DIRECTION via {@code restoreNotClearedStatus}: inflow
   * (depositAmount &ge; paymentAmount) → {@code RDNC}; outflow → {@code PWNC}. The fix must be
   * idempotent — a transaction already in the correct status is NOT re-written.
   *
   * <p>Confirmed empirically with a 26.40 line reconciled against two 13.20 receipts.
   *
   * @throws Exception if the mocked seams fail
   */
  @Test
  public void testUndoReconciliationRestoresNotClearedStatusByDirection() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);

    // 1) inflow stuck wrong: deposit 25.30, payment 0, status PWNC → must be re-set to RDNC.
    FIN_FinaccTransaction inflowWrong = mock(FIN_FinaccTransaction.class);
    when(inflowWrong.getDepositAmount()).thenReturn(new BigDecimal("25.30"));
    when(inflowWrong.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    when(inflowWrong.getStatus()).thenReturn("PWNC");

    // 2) outflow stuck wrong: deposit 0, payment 10.00, status RDNC → must be re-set to PWNC.
    FIN_FinaccTransaction outflowWrong = mock(FIN_FinaccTransaction.class);
    when(outflowWrong.getDepositAmount()).thenReturn(BigDecimal.ZERO);
    when(outflowWrong.getPaymentAmount()).thenReturn(new BigDecimal("10.00"));
    when(outflowWrong.getStatus()).thenReturn("RDNC");

    // 3) inflow already correct: deposit 5.00, payment 0, status RDNC → no redundant write.
    FIN_FinaccTransaction inflowOk = mock(FIN_FinaccTransaction.class);
    when(inflowOk.getDepositAmount()).thenReturn(new BigDecimal("5.00"));
    when(inflowOk.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    when(inflowOk.getStatus()).thenReturn("RDNC");

    List<FIN_FinaccTransaction> matched =
        Arrays.asList(inflowWrong, outflowWrong, inflowOk);

    // All three are kept (manually matched), so the loop routes them to restoreNotClearedStatus.
    doReturn(false).when(handler).isAutoCreated(any());

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil =
            mockStatic(ReconciliationRemovalUtil.class)) {
      // The reconciliation-level undo seams are no-ops (their behavior is the module's, not ours).
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      recUtil.when(() -> ReconciliationRemovalUtil.processAllReconciliationInDraft(any()))
          .thenAnswer(inv -> null);
      recUtil.when(() -> ReconciliationRemovalUtil.reactivateAndRemoveReconciliation(any()))
          .thenAnswer(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      // unmatchBankStatementLine: createCriteria(...).add(...).setMaxResults(...).uniqueResult() → null.
      @SuppressWarnings("unchecked")
      org.openbravo.dal.service.OBCriteria<FIN_BankStatementLine> crit =
          mock(org.openbravo.dal.service.OBCriteria.class);
      when(dal.createCriteria(FIN_BankStatementLine.class)).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setMaxResults(eq(1))).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(null);

      // OBDal.getInstance().save(...) is a no-op.
      doNothing().when(dal).save(any());

      handler.undoReconciliation(account, rec, matched);

      // 1) inflow stuck wrong → restored to RDNC.
      verify(inflowWrong).setStatus("RDNC");
      // 2) outflow stuck wrong → restored to PWNC.
      verify(outflowWrong).setStatus("PWNC");
      // 3) inflow already correct → never re-written (idempotent).
      verify(inflowOk, never()).setStatus(anyString());
    }
  }

  /**
   * Exercises {@code undoReconciliation}'s REAL (unstubbed) body — every other {@code
   * removeOperation} test stubs it away wholesale via the spy, so this is the one place the new
   * {@code reverseMatchedTransaction} resilience (extracted private method, ETP-4502 bulk-un-
   * reconcile partial-commit fix) actually runs. Core's {@code PaymentRemovalUtil.reactivateAndRemove}
   * commits mid-flow, so a failure reversing ONE matched transaction's auto-created payment must NOT
   * abort the loop — the remaining matched transactions still get processed (here, a second, kept
   * transaction still has its "not cleared" status restored), and {@code undoReconciliation} itself
   * must return normally (the exception is caught + logged inside {@code reverseMatchedTransaction},
   * never propagated).
   *
   * @throws Exception if the mocked seams fail
   */
  @Test
  public void testUndoReconciliationContinuesAfterOneReversalFailure() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);

    // 1) auto-created transaction whose payment reversal FAILS (Core throws mid-flow).
    FIN_FinaccTransaction txnAutoFails = mock(FIN_FinaccTransaction.class);
    FIN_Payment payment = mock(FIN_Payment.class);
    when(txnAutoFails.getFinPayment()).thenReturn(payment);
    doReturn(true).when(handler).isAutoCreated(txnAutoFails);

    // 2) a SECOND, kept (non-auto-created) transaction stuck in the wrong "not cleared" status —
    // must still be restored even though transaction #1 (processed first) failed.
    FIN_FinaccTransaction txnKept = mock(FIN_FinaccTransaction.class);
    when(txnKept.getDepositAmount()).thenReturn(new BigDecimal("25.30"));
    when(txnKept.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    when(txnKept.getStatus()).thenReturn("PWNC");
    doReturn(false).when(handler).isAutoCreated(txnKept);

    List<FIN_FinaccTransaction> matched = Arrays.asList(txnAutoFails, txnKept);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class)) {
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      recUtil.when(() -> ReconciliationRemovalUtil.processAllReconciliationInDraft(any()))
          .thenAnswer(inv -> null);
      recUtil.when(() -> ReconciliationRemovalUtil.reactivateAndRemoveReconciliation(any()))
          .thenAnswer(inv -> null);
      // Core's own reversal utility throws for the first (auto-created) transaction.
      payUtil.when(() -> PaymentRemovalUtil.reactivateAndRemove(payment))
          .thenThrow(new RuntimeException("boom — Core reversal failed mid-flow"));

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      org.openbravo.dal.service.OBCriteria<FIN_BankStatementLine> crit =
          mock(org.openbravo.dal.service.OBCriteria.class);
      when(dal.createCriteria(FIN_BankStatementLine.class)).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setMaxResults(eq(1))).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(null);
      doNothing().when(dal).save(any());

      // Must NOT throw: reverseMatchedTransaction catches+logs its own failure.
      handler.undoReconciliation(account, rec, matched);

      // The failing reversal was attempted...
      payUtil.verify(() -> PaymentRemovalUtil.reactivateAndRemove(payment));
    }

    // ...and the loop continued: the SECOND matched transaction was still processed despite the
    // first one's reversal failure (no abort).
    verify(txnKept).setStatus("RDNC");
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-4965 — reconcileGroup / applySuggestions post a within-tolerance gap
  //
  // End-to-end over the seams, complementing the unit-level coverage of
  // ReconciliationDifferenceSupport.applyInlineDifference in its own test class. What matters
  // here is WHERE the hook sits in the flow: after validateOperations (so over-coverage is still
  // rejected first) and before the line is matched (so the difference transaction joins the same
  // match, leaving the line RECONCILED rather than split and stuck).
  //
  // The default loadTolerances stub in setUp() is (3 days, 0%) — POSTING off, since 0% makes
  // differenceTolerance null — so every test that needs a posting re-stubs 5% explicitly. It does
  // NOT switch detection off: the 3-day window still applies, and a date-only difference reaches
  // reconcileGroup with a zero gap and posts nothing (testReconcileGroupDateOnlyDeviation...).
  // ═══════════════════════════════════════════════════════════════════════════

  private static final String GL_DIFF_ID = "GL-DIFF-1";
  private static final String TRX_DIFF_ID = "TRX-DIFF-1";
  /** AD_Message key the auto-created difference movement's description is resolved from. */
  private static final String DIFFERENCE_MESSAGE_KEY = "ETGO_ReconciliationDifference";
  private static final String DIFFERENCE_DESCRIPTION = "Reconciliation difference";
  private static final String CODE_GL_ITEM_REQUIRED = "GL_ITEM_REQUIRED";

  /** Re-stubs the account tolerances to 3 days / 5%, i.e. the inline difference posting ENABLED. */
  private void withFivePercentTolerance() {
    doReturn(new BigDecimal[]{BigDecimal.valueOf(3), new BigDecimal("5")})
        .when(handler).loadTolerances(any());
  }

  /** An account carrying a configured difference concept (EM_Aprm_Glitem_Diff). */
  private FIN_FinancialAccount accountWithDifferenceGlItem() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    GLItem glItem = mock(GLItem.class);
    when(glItem.getId()).thenReturn(GL_DIFF_ID);
    when(account.getAprmGlitemDiff()).thenReturn(glItem);
    return account;
  }

  /** An account with NO difference concept configured — the {@code GL_ITEM_REQUIRED} case. */
  private FIN_FinancialAccount accountWithoutDifferenceGlItem() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getAprmGlitemDiff()).thenReturn(null);
    return account;
  }

  /**
   * True when {@code spec} carries an {@code amount} equal to {@code expected}.
   *
   * <p>Exists to keep the {@code argThat} lambdas below from THROWING. A Mockito argument matcher
   * that raises (as {@code new BigDecimal(...)} does on an absent or blank string) aborts the whole
   * verification with a {@code NumberFormatException}, hiding the real failure behind a stack
   * trace — and the absent-amount spec is precisely the regression these matchers exist to catch.
   * A malformed or missing amount must simply not match.
   */
  private static boolean specAmountIs(JSONObject spec, String expected) {
    String raw = spec == null ? null : spec.optString("amount", null);
    if (raw == null || raw.trim().isEmpty()) {
      return false;
    }
    try {
      return new BigDecimal(expected).compareTo(new BigDecimal(raw.trim())) == 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * The ticket's headline case, end to end: a 27.00 line reconciled against a 26.62 movement. The
   * 0.38 gap (1.41%, inside 5%) is posted to the account's concept as ONE new transaction, whose id
   * joins the match — so the line ends RECONCILED instead of split into a 0.38 remainder that no
   * user can close.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupWithinTolerancePostsDifferenceAndReconciles() throws Exception {
    withFivePercentTolerance();
    FIN_FinancialAccount account = accountWithDifferenceGlItem();
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("27.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("26.62"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction diffTrx = trxFor(ACC_ID, new BigDecimal("0.38"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-diff");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    doReturn(diffTrx).when(handler).loadTransaction(TRX_DIFF_ID);
    doReturn(TRX_DIFF_ID).when(handler).createTransactionForRule(any(), any(), any());
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    NeoResponse response;
    // applyInlineDifference resolves the difference movement's description through the message
    // dictionary, so every path that reaches the write has to stub the AD_Message lookup.
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubMessageBd(msgMock, DIFFERENCE_MESSAGE_KEY, DIFFERENCE_DESCRIPTION);
      response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));
    }

    assertEquals(201, response.getHttpStatus());
    // Exactly ONE difference transaction, for the gap, against the account's concept.
    verify(handler, times(1)).createTransactionForRule(eq(account), eq(line), argThat(spec ->
        GL_DIFF_ID.equals(spec.optString("glItemId")) && specAmountIs(spec, "0.38")));
    // Both the original movement AND the adjustment are matched into the line in one go.
    verify(handler).matchBankStatementLine(eq(line),
        argThat(ops -> ops.contains("t1") && ops.contains(TRX_DIFF_ID)), eq(rec));
  }

  /**
   * The outflow direction of the case above. The gap keeps the LINE's sign, so
   * {@code createTransactionForRule} derives a Pago (BPW) and not a Cobro — asserting this at the
   * handler level too, because a backwards accounting entry is the worst possible outcome of an
   * automatic posting and the sign travels through two layers to get here.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupWithinToleranceOutflowPostsNegativeDifference() throws Exception {
    withFivePercentTolerance();
    FIN_FinancialAccount account = accountWithDifferenceGlItem();
    FIN_BankStatementLine line = lineFor(ACC_ID, BigDecimal.ZERO, new BigDecimal("27.00"), null);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, BigDecimal.ZERO, new BigDecimal("26.62"), null);
    FIN_FinaccTransaction diffTrx = trxFor(ACC_ID, BigDecimal.ZERO, new BigDecimal("0.38"), null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-diff-out");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    doReturn(diffTrx).when(handler).loadTransaction(TRX_DIFF_ID);
    doReturn(TRX_DIFF_ID).when(handler).createTransactionForRule(any(), any(), any());
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    NeoResponse response;
    // applyInlineDifference resolves the difference movement's description through the message
    // dictionary, so every path that reaches the write has to stub the AD_Message lookup.
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubMessageBd(msgMock, DIFFERENCE_MESSAGE_KEY, DIFFERENCE_DESCRIPTION);
      response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));
    }

    assertEquals(201, response.getHttpStatus());
    verify(handler).createTransactionForRule(any(), any(), argThat(spec ->
        specAmountIs(spec, "-0.38")));
  }

  /**
   * A within-tolerance gap on an account with NO configured concept, and none in the body, is a 400
   * carrying {@code GL_ITEM_REQUIRED} so the client can open its concept picker — and NOTHING is
   * written. A returned error commits, so the guard has to precede the write.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupWithinToleranceWithoutGlItemReturns400() throws Exception {
    withFivePercentTolerance();
    FIN_FinancialAccount account = accountWithoutDifferenceGlItem();
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("27.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("26.62"), BigDecimal.ZERO, null);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(400, response.getHttpStatus());
    assertEquals(CODE_GL_ITEM_REQUIRED, response.getBody().getString("code"));
    assertEquals(0, new BigDecimal("0.38")
        .compareTo(new BigDecimal(response.getBody().getString("differenceAmount"))));
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    verify(handler, never()).matchBankStatementLine(any(), any(), any());
    verify(handler, never()).addNewDraftReconciliation(any());
  }

  /**
   * The retry: the same request with the concept the user picked in the modal succeeds and posts
   * against THAT concept, not the account's (which does not exist here anyway).
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupRetryWithGlItemInBodyPostsTheDifference() throws Exception {
    withFivePercentTolerance();
    FIN_FinancialAccount account = accountWithoutDifferenceGlItem();
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("27.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("26.62"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction diffTrx = trxFor(ACC_ID, new BigDecimal("0.38"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-diff-retry");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    doReturn(diffTrx).when(handler).loadTransaction(TRX_DIFF_ID);
    doReturn(TRX_DIFF_ID).when(handler).createTransactionForRule(any(), any(), any());
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    JSONObject body = reconcileBody(ACC_ID, LINE_ID, "t1").put("glItemId", "GL-CHOSEN");
    NeoResponse response;
    // OBDal is mocked (with the requested concept resolving) so this passes whether or not the
    // implementation adds the client-supplied-id existence check `checkGlItem` performs.
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(GLItem.class, "GL-CHOSEN")).thenReturn(mock(GLItem.class));
      stubMessageBd(msgMock, DIFFERENCE_MESSAGE_KEY, DIFFERENCE_DESCRIPTION);
      response = handler.reconcileGroup(body);
    }

    assertEquals(201, response.getHttpStatus());
    verify(handler).createTransactionForRule(any(), any(), argThat(spec ->
        "GL-CHOSEN".equals(spec.optString("glItemId"))));
  }

  /**
   * A DATE-only deviation: the amounts balance exactly, so there is nothing to post. The line
   * reconciles the ordinary way and the account needs no concept at all — the date affects the
   * classification and the automatch proposal, never the accounting.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupDateOnlyDeviationCreatesNoDifferenceTransaction() throws Exception {
    withFivePercentTolerance();
    FIN_FinancialAccount account = accountWithoutDifferenceGlItem();
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("27.00"), BigDecimal.ZERO, null);
    // Same amount, different date — the date never reaches this layer, and that is the point.
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("27.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-date-only");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    stubReconciliationCompose(rec, "Success");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(201, response.getHttpStatus());
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    verify(handler).matchBankStatementLine(eq(line), argThat(ops -> ops.size() == 1), eq(rec));
  }

  /**
   * A gap far outside the tolerance keeps the pre-existing behaviour verbatim: the movement is
   * matched, Core splits the line and leaves a pending remainder. No posting, no error — this
   * ticket must not change what a 53.24-of-100.00 match does today.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupOutsideToleranceKeepsThePartialSplitUnchanged() throws Exception {
    withFivePercentTolerance();
    FIN_FinancialAccount account = accountWithDifferenceGlItem();
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("53.24"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-partial");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(201, response.getHttpStatus());
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    // Still tagged for the split, exactly as testReconcileGroupSingleOperationPartialMatch... asserts.
    verify(handler).tagMatchGroup(line);
  }

  /**
   * Over-coverage — a movement BIGGER than the line — is out of scope and must stay rejected by
   * {@code validateOperations}, which runs BEFORE the difference hook. The inline posting must not
   * "helpfully" absorb the excess into a negative adjustment.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupOverCoverageStillRejectedAndPostsNothing() throws Exception {
    withFivePercentTolerance();
    FIN_FinancialAccount account = accountWithDifferenceGlItem();
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("27.00"), BigDecimal.ZERO, null);
    // 27.20 exceeds the line by 0.20 — inside 5% in magnitude, but the wrong direction entirely.
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("27.20"), BigDecimal.ZERO, null);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("exceed the statement line amount"));
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    verify(handler, never()).addNewDraftReconciliation(any());
  }

  /**
   * <b>The invoice path leaves writes behind, so its 400 must roll back.</b> {@code payInvoices}
   * runs before the gap is known: by the time the missing concept is discovered, a payment and its
   * transaction are already persisted, and a returned {@code NeoResponse.error(...)} COMMITS them.
   * {@code doRollbackAndClose()} therefore has to run before the 400, or a rejected reconciliation
   * silently leaves a stray payment against the invoice.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupInvoicePathRollsBackBeforeGlItemRequired() throws Exception {
    withFivePercentTolerance();
    FIN_FinancialAccount account = accountWithoutDifferenceGlItem();
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("27.00"), BigDecimal.ZERO, null);
    when(line.getTransactionDate()).thenReturn(null);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    // The invoice payment auto-creates a 26.62 transaction, leaving a 0.38 gap on the line.
    FIN_FinaccTransaction createdTxn =
        trxFor(ACC_ID, new BigDecimal("26.62"), BigDecimal.ZERO, null);
    when(createdTxn.getId()).thenReturn("T-INV");
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getFINFinaccTransactionList()).thenReturn(Collections.singletonList(createdTxn));
    doReturn(createdTxn).when(handler).loadTransaction("T-INV");

    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    when(schedule.getOutstandingAmount()).thenReturn(new BigDecimal("26.62"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationPaymentService> rps =
            mockStatic(ReconciliationPaymentService.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "INV-1")).thenReturn(invoice);
      when(dal.get(FIN_PaymentSchedule.class, "PS-1")).thenReturn(schedule);
      rps.when(() -> ReconciliationPaymentService.registerReconciliationPayment(any()))
          .thenReturn(payment);

      NeoResponse response = handler.reconcileGroup(
          invoiceReconcileBody(ACC_ID, LINE_ID, "INV-1", "PS-1"));

      assertEquals(400, response.getHttpStatus());
      assertEquals(CODE_GL_ITEM_REQUIRED, response.getBody().getString("code"));
      // No payment or transaction may survive a rejected request.
      verify(handler).doRollbackAndClose();
      verify(handler, never()).createTransactionForRule(any(), any(), any());
      verify(handler, never()).matchBankStatementLine(any(), any(), any());
    }
  }

  /**
   * The automatch batch cannot ask for a concept line by line, so a within-tolerance group on an
   * account with none configured fails as a per-group entry in {@code results[]} carrying
   * {@code GL_ITEM_REQUIRED} — which is exactly the shape {@code AutoMatchSuggestionModal} already
   * splits on. The envelope stays 201 (best-effort batch), the other groups still apply, and
   * nothing is written for the failing one.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testApplySuggestionsWithinToleranceWithoutGlItemFailsOnlyThatGroup() throws Exception {
    withFivePercentTolerance();
    FIN_FinancialAccount account = accountWithoutDifferenceGlItem();
    // Group 1: a 0.38 gap, inside tolerance, but no concept to post it to → GL_ITEM_REQUIRED.
    FIN_BankStatementLine gapLine =
        lineFor(ACC_ID, new BigDecimal("27.00"), BigDecimal.ZERO, null);
    when(gapLine.getId()).thenReturn("line-gap");
    FIN_FinaccTransaction gapTrx = trxFor(ACC_ID, new BigDecimal("26.62"), BigDecimal.ZERO, null);
    // Group 2: balances exactly → unaffected, applies normally.
    FIN_BankStatementLine okLine = lineFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, null);
    when(okLine.getId()).thenReturn("line-ok");
    FIN_FinaccTransaction okTrx = trxFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-batch");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(gapLine).when(handler).loadLine("line-gap");
    doReturn(okLine).when(handler).loadLine("line-ok");
    doReturn(gapTrx).when(handler).loadTransaction("t-gap");
    doReturn(okTrx).when(handler).loadTransaction("t-ok");
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    JSONObject gapGroup = new JSONObject()
        .put("statementLineId", "line-gap")
        .put("operationIds", new JSONArray().put("t-gap"));
    JSONObject okGroup = new JSONObject()
        .put("statementLineId", "line-ok")
        .put("operationIds", new JSONArray().put("t-ok"));
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(gapGroup).put(okGroup));

    NeoResponse response = handler.applySuggestions(body);

    // Best-effort batch: the envelope is 201 even though one group failed.
    assertEquals(201, response.getHttpStatus());
    JSONArray results = response.getBody().getJSONObject("response").getJSONObject("data")
        .getJSONArray("results");
    assertEquals(2, results.length());
    assertEquals(CODE_GL_ITEM_REQUIRED, results.getJSONObject(0).getString("code"));
    assertEquals("rec-batch", results.getJSONObject(1).getString("reconciliationId"));
    // Nothing was posted, and only the healthy group reached the shared reconciliation.
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    verify(handler, never()).matchBankStatementLine(eq(gapLine), any(), any());
    verify(handler).matchBankStatementLine(eq(okLine), any(), eq(rec));
  }

  /**
   * The automatch batch DOES post the difference when the account has a concept configured — the
   * failure above is about the missing configuration, not about the batch path refusing to adjust.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testApplySuggestionsWithinTolerancePostsDifferenceWhenConfigured() throws Exception {
    withFivePercentTolerance();
    FIN_FinancialAccount account = accountWithDifferenceGlItem();
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("27.00"), BigDecimal.ZERO, null);
    when(line.getId()).thenReturn(LINE_ID);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("26.62"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction diffTrx = trxFor(ACC_ID, new BigDecimal("0.38"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-batch-diff");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    doReturn(diffTrx).when(handler).loadTransaction(TRX_DIFF_ID);
    doReturn(TRX_DIFF_ID).when(handler).createTransactionForRule(any(), any(), any());
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    JSONObject group = new JSONObject()
        .put("statementLineId", LINE_ID)
        .put("operationIds", new JSONArray().put("t1"));
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(group));

    NeoResponse response;
    // applyInlineDifference resolves the difference movement's description through the message
    // dictionary, so every path that reaches the write has to stub the AD_Message lookup.
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubMessageBd(msgMock, DIFFERENCE_MESSAGE_KEY, DIFFERENCE_DESCRIPTION);
      response = handler.applySuggestions(body);
    }

    assertEquals(201, response.getHttpStatus());
    verify(handler).createTransactionForRule(eq(account), eq(line), argThat(spec ->
        GL_DIFF_ID.equals(spec.optString("glItemId"))));
    verify(handler).matchBankStatementLine(eq(line),
        argThat(ops -> ops.contains("t1") && ops.contains(TRX_DIFF_ID)), eq(rec));
  }

  // ── ETP-5121: a reconciled line survives its statement's reactivation ─────
  //
  // "Reactivar" on a processed bank statement only clears FIN_BankStatement.Processed (see
  // BankStatementsHandler.reactivateStatement, whose own invariant is covered by
  // BankStatementsHandlerTest): it does NOT clear
  // FIN_BankStatementLine.FIN_FinAcc_Transaction_ID, and it does not detach that transaction from
  // its FIN_Reconciliation. So a line that was reconciled before the reactivation is still
  // genuinely reconciled afterwards, and has to keep showing under the panel's "reconciled"
  // filter. PENDING_LINES_SQL used to gate the WHOLE query on bs.processed = 'Y', which dropped
  // EVERY line of a reactivated statement — the reconciled one disappeared from the panel under
  // any filter, and with it the only way to un-reconcile it from the UI.
  //
  // This suite drives a mocked ResultSet, so it is structurally BLIND to the WHERE clause: a
  // ResultSet stub happily returns rows the real query would have filtered out. The first two
  // tests therefore assert the SHAPE of the SQL the handler actually hands to the JDBC driver,
  // which is the only place the regression is observable without a database.

  /** Whitespace-collapsed gate that must replace the unconditional {@code bs.processed = 'Y'}. */
  private static final String SQL_PROCESSED_GATE_WITH_RECONCILED_EXCEPTION =
      "AND (bs.processed = 'Y' OR (bsl.fin_finacc_transaction_id IS NOT NULL "
          + "AND COALESCE(rec.processed, 'N') = 'Y'))";
  /** The pre-ETP-5121 unconditional gate — its presence IS the regression. */
  private static final String SQL_UNCONDITIONAL_PROCESSED_GATE =
      "AND bs.processed = 'Y' AND bs.fin_financial_account_id = ?";
  /** The join the reconciled-line exception reads {@code rec.processed} through. */
  private static final String SQL_RECONCILIATION_JOIN =
      "LEFT JOIN fin_reconciliation rec ON rec.fin_reconciliation_id = ft.fin_reconciliation_id";
  /** Start of the {@code bs.processed} gate, in whitespace-collapsed form. */
  private static final String SQL_GATE_START = "AND (bs.processed";
  /** First predicate after the gate, used to bound it when slicing the WHERE clause. */
  private static final String SQL_GATE_END = "AND bs.fin_financial_account_id";

  @Mock private Connection pendingSqlConn;
  @Mock private PreparedStatement pendingSqlPs;
  @Mock private ResultSet pendingSqlRs;
  @Mock private OBDal pendingSqlDal;

  /**
   * Wires the class-level JDBC mocks so {@code buildPendingLines} runs offline, and stubs
   * {@code loadRules} away so the main query is the ONLY {@code prepareStatement} call (the rules
   * query would otherwise share the same connection mock and break the capture).
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  private void stubPendingLinesJdbc() throws Exception {
    doReturn(Collections.emptyList()).when(handler).loadRules(eq(ACC_ID));
    when(pendingSqlDal.getConnection()).thenReturn(pendingSqlConn);
    when(pendingSqlConn.prepareStatement(anyString())).thenReturn(pendingSqlPs);
    when(pendingSqlConn.createArrayOf(anyString(), any())).thenReturn(null);
    when(pendingSqlPs.executeQuery()).thenReturn(pendingSqlRs);
  }

  /**
   * Runs {@code buildPendingLines} against the class-level JDBC mocks.
   *
   * @return the envelope's {@code data} object
   * @throws Exception if the mocked JDBC interaction fails
   */
  private JSONObject runPendingLines() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil =
            mockStatic(ReconciliationRemovalUtil.class)) {
      obDal.when(OBDal::getInstance).thenReturn(pendingSqlDal);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
      NeoResponse response = handler.buildPendingLines(ACC_ID, CLIENT_ID,
          new HashSet<>(Arrays.asList(ORG_ID)), Collections.emptyMap());
      assertEquals(200, response.getHttpStatus());
      return response.getBody().getJSONObject("response").getJSONObject("data");
    }
  }

  /**
   * Captures the query the handler prepared, with every run of whitespace collapsed to a single
   * space so the assertions do not depend on the source's string-concatenation indentation.
   *
   * @return the whitespace-normalised SQL
   * @throws Exception if the mocked JDBC interaction fails
   */
  private String capturedPendingLinesSql() throws Exception {
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(pendingSqlConn).prepareStatement(sql.capture());
    return sql.getValue().replaceAll("\\s+", " ").trim();
  }

  /**
   * The reconciled-line exception must be part of the {@code bs.processed} gate: without it, every
   * line of a statement returned to draft is filtered out of {@code pendingLines} and the panel
   * loses the reconciled one entirely (ETP-5121).
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testPendingLinesSqlKeepsReconciledLinesOfAReactivatedStatement() throws Exception {
    stubPendingLinesJdbc();
    when(pendingSqlRs.next()).thenReturn(false);

    runPendingLines();

    String sql = capturedPendingLinesSql();
    assertTrue("the processed gate must carry the already-reconciled exception (ETP-5121); got: "
        + sql, sql.contains(SQL_PROCESSED_GATE_WITH_RECONCILED_EXCEPTION));
    assertFalse("bs.processed = 'Y' must NOT gate the whole query: a reactivated statement "
            + "(processed = 'N') would drop its reconciled lines from the panel (ETP-5121)",
        sql.contains(SQL_UNCONDITIONAL_PROCESSED_GATE));
  }

  /**
   * The exception is deliberately NARROW: it requires a PROCESSED reconciliation, exactly like the
   * {@code line_status = 'reconciled'} label a few lines above it in the same query. A line whose
   * reconciliation is back in DRAFT is functionally un-confirmed, so it must keep falling back to
   * the pending pool instead of being dragged in by a mere transaction link.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testPendingLinesSqlExceptionRequiresAProcessedReconciliation() throws Exception {
    stubPendingLinesJdbc();
    when(pendingSqlRs.next()).thenReturn(false);

    runPendingLines();

    String sql = capturedPendingLinesSql();
    int from = sql.indexOf(SQL_GATE_START);
    int to = sql.indexOf(SQL_GATE_END);
    assertTrue("the bs.processed gate must still be present and precede the account filter; got: "
        + sql, from >= 0 && to > from);
    String gate = sql.substring(from, to);

    assertTrue("the exception must require a linked transaction: " + gate,
        gate.contains("bsl.fin_finacc_transaction_id IS NOT NULL"));
    assertTrue("the exception must require the reconciliation to be PROCESSED, so a line whose "
        + "reconciliation is back in draft stays pending: " + gate,
        gate.contains("COALESCE(rec.processed, 'N') = 'Y'"));
    // The predicate is unreadable without the join that exposes rec.processed.
    assertTrue("the reconciliation join must remain in the query: " + sql,
        sql.contains(SQL_RECONCILIATION_JOIN));
    // Same predicate on the SELECT side — the exception mirrors the reconciled label by design, so
    // the WHERE clause can never admit a line the SELECT would label 'pending'.
    assertTrue("line_status must keep labelling a draft reconciliation as pending: " + sql,
        sql.contains("OR COALESCE(rec.processed, 'N') = 'N' THEN 'pending'"));
  }

  /**
   * Row-mapping half of CP-1: a {@code line_status = 'reconciled'} row keeps
   * {@code matched}/{@code reconcileStatus}/{@code state} = reconciled and is counted in its own
   * bucket, so the panel's "Conciliadas" chip reads 1 and its filter has a row to show. A pending
   * row alongside it must not be dragged into that bucket.
   *
   * <p>The mocked ResultSet is what the FIXED query hands back for a statement that is still
   * processed. Note the gate's exception is narrow on purpose: once the statement is reactivated
   * only the reconciled row comes back — the unmatched sibling is genuinely not reconcilable while
   * its statement is a draft. That filtering is asserted by the two SQL-shape tests above; this
   * one owns the mapping.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testPendingLinesLabelsAndCountsAReconciledRowSeparately() throws Exception {
    stubPendingLinesJdbc();
    when(pendingSqlRs.next()).thenReturn(true, true, false);
    when(pendingSqlRs.getString("fin_bankstatementline_id")).thenReturn("line-rec", "line-pend");
    when(pendingSqlRs.getTimestamp("datetrx")).thenReturn(null);
    when(pendingSqlRs.getString("description")).thenReturn("Cobro conciliado", "Cargo suelto");
    when(pendingSqlRs.getBigDecimal("amount"))
        .thenReturn(new BigDecimal("100.00"), new BigDecimal("40.00"));
    // The reconciled line still points at its transaction, and that transaction still hangs off a
    // PROCESSED reconciliation — the state a statement "Reactivar" leaves behind untouched.
    when(pendingSqlRs.getString("line_status")).thenReturn("reconciled", "pending");
    when(pendingSqlRs.getString("fin_finacc_transaction_id")).thenReturn("trx-1", null);
    when(pendingSqlRs.getString("draft_reconciliation_id")).thenReturn("", "");
    when(pendingSqlRs.getString("match_group_id")).thenReturn("", "");

    JSONObject data = runPendingLines();

    JSONArray lines = data.getJSONArray("lines");
    assertEquals("both rows must be listed", 2, lines.length());

    JSONObject reconciled = lines.getJSONObject(0);
    assertTrue("the pre-existing reconciliation is untouched by the reactivation",
        reconciled.getBoolean("matched"));
    assertEquals("RECONCILED", reconciled.getString("reconcileStatus"));
    assertEquals("reconciled", reconciled.getString("state"));

    JSONObject pending = lines.getJSONObject(1);
    assertFalse("the unmatched line must not be reported as reconciled",
        pending.getBoolean("matched"));
    assertEquals("PENDING", pending.getString("reconcileStatus"));

    // The filter chips are driven by these counts, so the "Conciliadas" chip must show 1, not 0.
    JSONObject counts = data.getJSONObject(ReconciliationHandler.KEY_COUNTS);
    assertEquals(2, counts.getInt("all"));
    assertEquals(1, counts.getInt("reconciled"));
  }
}
