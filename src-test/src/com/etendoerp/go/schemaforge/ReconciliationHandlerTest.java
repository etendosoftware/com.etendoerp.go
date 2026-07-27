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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;
import com.etendoerp.payment.removal.util.ReconciliationRemovalUtil;
import com.etendoerp.payment.removal.util.TransactionRemovalUtil;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
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

    // loadRules runs its OWN prepareStatement against the same mocked connection; stub the spy
    // seam so setString(1, ACC_ID) is only invoked once (by the main query).
    doReturn(Collections.emptyList()).when(handler).loadRules(any(), eq(ACC_ID));

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

    // loadRules runs its OWN prepareStatement against the same mocked connection; stub the spy
    // seam so it does not consume the shared rs.next() sequence reserved for the main query.
    doReturn(Collections.emptyList()).when(handler).loadRules(any(), eq(ACC_ID));

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

    // loadRules runs its OWN prepareStatement against the same mocked connection; stub the spy
    // seam so it does not consume the shared rs.next() sequence reserved for the main query.
    doReturn(Collections.emptyList()).when(handler).loadRules(any(), eq(ACC_ID));

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

    // loadRules runs its OWN prepareStatement against the same mocked connection; stub the spy seam
    // so it does not consume the shared rs.next() sequence reserved for the main query.
    doReturn(Collections.emptyList()).when(handler).loadRules(any(), eq(ACC_ID));

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

    // loadRules runs its OWN prepareStatement against the same mocked connection; stub the spy seam
    // so it does not consume the shared rs.next() sequence reserved for the main query.
    doReturn(Collections.emptyList()).when(handler).loadRules(any(), eq(ACC_ID));

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

      handler.buildCandidates(ACC_ID, LINE_ID, "payments", null, null);

      // The draft-reconciliation id is now bound FIRST (NULL for a normal line — the SQL's
      // "OR ft.fin_reconciliation_id = ?" branch then matches nothing), which shifts everything
      // after it by one: account(2), the optional date-range binds(3-6, NULL here), then the
      // docType flag(7) = 'N'.
      verify(ps).setString(1, null);
      verify(ps).setString(2, ACC_ID);
      verify(ps).setString(7, "N");
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
    when(account.getMatchingAlgorithm()).thenReturn(null);

    // loadRules runs its OWN prepareStatement against the same mocked connection; stub the spy
    // seam so it does not consume the shared rs.next() sequence reserved for the main query.
    doReturn(Collections.emptyList()).when(handler).loadRules(any(), eq(ACC_ID));

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
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(Collections.emptyList()).when(handler).loadRules(any(), eq(ACC_ID));

    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn("l1");
    when(line.getCramount()).thenReturn(new BigDecimal("100.00"));
    when(line.getDramount()).thenReturn(BigDecimal.ZERO);
    when(line.getDescription()).thenReturn("Transfer");
    when(line.getReferenceNo()).thenReturn("");
    when(line.getTransactionDate()).thenReturn(null);
    doReturn(Collections.singletonList(line)).when(handler).loadPendingLines(ACC_ID);

    doReturn(new HashSet<>(Arrays.asList("t1"))).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), eq("l1"), anyInt());
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
    doReturn(account).when(handler).loadAccount(ACC_ID);

    MatchRuleEngine.Rule rule = new MatchRuleEngine.Rule("R1", "Fee Rule", 10,
        MatchRuleEngine.COND_CONTAINS, "commission",
        new MatchRuleEngine.RuleOptions("GL-1", "BP-1", null, null, null, null), 0L);
    doReturn(Collections.singletonList(rule)).when(handler).loadRules(any(), eq(ACC_ID));

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
        .suggestedTransactionIds(eq(ACC_ID), eq("l1"), anyInt());

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
      ams.when(() -> AutoMatchSupport.matchFallback(any(), any(), any(), any(), any(),
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

  /** loadRules delegates to the engine and returns its rules. */
  @Test
  public void testLoadRulesDelegatesToEngine() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    List<MatchRuleEngine.Rule> rules = handler.loadRules(conn, ACC_ID);

    assertTrue(rules.isEmpty());
    verify(ps).setString(1, ACC_ID);
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
    // currentBalance reads the account's remaining draft reconciliations — keep it side-effect free.
    try (MockedStatic<ReconciliationRemovalUtil> recUtil =
        mockStatic(ReconciliationRemovalUtil.class)) {
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

  /** A line with no linked transaction (not reconciled) is rejected with a 409, no undo. */
  @Test
  public void testReactivateLineNotReconciledReturns409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
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
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateErrorMidFlowRollsBack() throws Exception {
    reconciledLineSetup();
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

    NeoResponse response = handler.handle(context);

    assertEquals(500, response.getHttpStatus());
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
    // longer throw). Simulate the successful post-removal DB state: no longer linked.
    when(t1.getReconciliation()).thenReturn(null);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doNothing().when(handler).undoReconciliation(any(), any(), any());
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      // removeSelectedFromReconciliations now re-fetches each reconciliation fresh by id right
      // before dispatching it (avoids Hibernate staleness across reconciliations); the re-fetch
      // must return the exact mock the test built expectations on.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
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
    // Only T1 was requested — the post-check only re-checks requested ids. Simulate it succeeded.
    when(t1.getReconciliation()).thenReturn(null);
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
    // Both requested ids are re-checked by the post-check loop — simulate they both succeeded.
    when(t1.getReconciliation()).thenReturn(null);
    when(t2.getReconciliation()).thenReturn(null);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doNothing().when(handler).undoReconciliation(any(), any(), any());
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      // Re-fetched fresh by id right before the coversReconciliation check — must return the same
      // mock the test built expectations on.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
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
    // Both requested ids (T1, T2) are re-checked by the post-check loop — simulate success for both.
    when(t1.getReconciliation()).thenReturn(null);
    when(t2.getReconciliation()).thenReturn(null);
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
    // The three requested ids (T1, T2, T3) are re-checked by the post-check loop — simulate all
    // three succeeded (t4 was never requested, so its state is irrelevant here).
    when(t1.getReconciliation()).thenReturn(null);
    when(t2.getReconciliation()).thenReturn(null);
    when(t3.getReconciliation()).thenReturn(null);
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
    // Post-removal ground truth: T1/T2 genuinely detached (no longer linked); T3's detach silently
    // failed inside Core (still reconciled) — the loop that processes it never aborts nor rethrows.
    when(t1.getReconciliation()).thenReturn(null);
    when(t2.getReconciliation()).thenReturn(null);
    // t3.getReconciliation() keeps returning `rec` (its stub from recWith) — simulating "still
    // reconciled" ground truth.

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
    // Both requested ids (A, B) are re-checked by the post-check loop — simulate both succeeded.
    when(tA.getReconciliation()).thenReturn(null);
    when(tB.getReconciliation()).thenReturn(null);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doNothing().when(handler).undoReconciliation(any(), any(), any());
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
      when(dal.get(FIN_FinaccTransaction.class, "B")).thenReturn(tB);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec1);
      when(dal.get(FIN_Reconciliation.class, "rec-2")).thenReturn(rec2);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());
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
    // Both requested ids (A, B) are re-checked by the post-check loop — simulate both succeeded.
    when(tA.getReconciliation()).thenReturn(null);
    when(tB.getReconciliation()).thenReturn(null);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doNothing().when(handler).undoReconciliation(any(), any(), any());
    doReturn(mock(FIN_BankStatementLine.class)).when(handler).normalizeReactivatedMatchGroup(any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      // Each reconciliation is re-fetched fresh by id right before dispatching — stub both.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
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

  // ── reactivateSelected ("Reactivar": keep the reconciliation as a DRAFT) ───────
  // The lighter sibling of removeOperation: instead of deleting the reconciliation, Core's PLAIN
  // reactivate sets it back to draft, leaving EVERY link intact (line→transaction and
  // transaction→reconciliation both survive). Only auto-created payments in the selection are still
  // deleted — there is nothing worth preserving in a draft for a payment that only existed to back
  // this reconciliation. Post-check ground truth is therefore "the reconciliation is no longer
  // processed" ({@code rec.isProcessed() == false}), NOT "the transaction lost its reconciliation".

  /** Marks the reconciliation as back-to-draft, i.e. the reactivate succeeded. */
  private void markReactivated(FIN_Reconciliation rec) {
    when(rec.isProcessed()).thenReturn(false);
  }

  /**
   * All-pre-existing selection: nothing is deleted, the reconciliation is simply reactivated to
   * draft via Core's PLAIN {@code reactivate} — never {@code reactivateAndRemoveReconciliation}
   * (which would delete it) and never the per-transaction detach.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedAllPreExistingReactivatesToDraft() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_Reconciliation rec = recWith("rec-1", t1, t2);
    markReactivated(rec);
    wireLoads(t1, t2);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(false).when(handler).isAutoCreated(t1);
    doReturn(false).when(handler).isAutoCreated(t2);

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class)) {
      // reactivateToDraft re-fetches the reconciliation fresh by id before calling Core.
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1", "T2"));

      // Core's PLAIN reactivate — the reconciliation is KEPT, only set back to draft.
      recUtil.verify(() -> ReconciliationRemovalUtil.reactivate(rec));
      recUtil.verify(() -> ReconciliationRemovalUtil.reactivateAndRemoveReconciliation(any()), never());
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()), never());
      payUtil.verify(() -> PaymentRemovalUtil.reactivateAndRemove(any()), never());
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("reactivated"));
    assertEquals(2, data.getJSONArray("transactionIds").length());
    assertEquals(0, data.getJSONArray("failedTransactionIds").length());
    // No pre-existing draft was open on the account (getDraftReconciliation → empty), so nothing
    // was silently confirmed to make room and the UI shows the plain success toast.
    assertEquals(0, data.getInt("autoConfirmedDrafts"));
    // The whole-reconciliation delete path is never taken.
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /**
   * Core allows only ONE editable reconciliation per account, so reactivating one has to confirm
   * whichever drafts were already open — silently re-reconciling a line the user had left pending by
   * an earlier "Reactivar". Those drafts are counted and surfaced as {@code autoConfirmedDrafts} so
   * the UI can warn instead of letting it happen silently.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedCountsAutoConfirmedPreExistingDrafts() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_Reconciliation rec = recWith("rec-1", t1);
    markReactivated(rec);
    wireLoads(t1);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(false).when(handler).isAutoCreated(t1);
    // Two drafts were already open on the account before this reactivate.
    List<FIN_Reconciliation> preExistingDrafts =
        Arrays.asList(mock(FIN_Reconciliation.class), mock(FIN_Reconciliation.class));

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(preExistingDrafts);

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1"));

      // Those drafts are the ones Core forces us to confirm to free the single editable slot.
      recUtil.verify(() -> ReconciliationRemovalUtil.processAllReconciliationInDraft(preExistingDrafts));
      recUtil.verify(() -> ReconciliationRemovalUtil.reactivate(rec));
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(data.getBoolean("reactivated"));
    // The count the UI turns into its "another reconciliation was confirmed" warning.
    assertEquals(2, data.getInt("autoConfirmedDrafts"));
  }

  /**
   * The count is only correct if the draft list is READ BEFORE it is processed — once
   * {@code processAllReconciliationInDraft} runs, those reconciliations are no longer drafts, so a
   * later read would report 0. Pins that ordering, and that Core's plain {@code reactivate} runs
   * after both (it needs the editable slot freed first).
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedReadsDraftsBeforeProcessingThem() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_Reconciliation rec = recWith("rec-1", t1);
    markReactivated(rec);
    wireLoads(t1);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(false).when(handler).isAutoCreated(t1);
    List<FIN_Reconciliation> preExistingDrafts =
        Arrays.asList(mock(FIN_Reconciliation.class));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(preExistingDrafts);

      handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1"));

      // read → process → reactivate, in that order. (For a static mock, the ordering "mock" identity
      // is the class itself; InOrder#verify(MockedStatic, Verification) does the ordered check.)
      InOrder inOrder = Mockito.inOrder(ReconciliationRemovalUtil.class);
      inOrder.verify(recUtil, () -> ReconciliationRemovalUtil.getDraftReconciliation(any()));
      inOrder.verify(recUtil,
          () -> ReconciliationRemovalUtil.processAllReconciliationInDraft(preExistingDrafts));
      inOrder.verify(recUtil, () -> ReconciliationRemovalUtil.reactivate(rec));
    }
  }

  /**
   * Mixed selection: the auto-created transaction's payment is deleted (nothing to preserve for it),
   * the pre-existing one is kept, and the reconciliation is still reactivated to draft once.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedMixedDeletesAutoCreatedAndStillReactivates() throws Exception {
    FIN_FinaccTransaction auto = txnWithId("T-AUTO");
    FIN_FinaccTransaction kept = txnWithId("T-KEPT");
    FIN_Reconciliation rec = recWith("rec-1", auto, kept);
    markReactivated(rec);
    FIN_Payment payment = mock(FIN_Payment.class);
    when(auto.getFinPayment()).thenReturn(payment);
    wireLoads(auto, kept);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(true).when(handler).isAutoCreated(auto);
    doReturn(false).when(handler).isAutoCreated(kept);

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<TransactionRemovalUtil> trxUtil = mockStatic(TransactionRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      // detachSelected re-fetches each auto-created transaction fresh by id.
      when(dal.get(FIN_FinaccTransaction.class, "T-AUTO")).thenReturn(auto);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T-AUTO", "T-KEPT"));

      // Only the auto-created one is detached + its payment reversed...
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(auto));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(kept), never());
      payUtil.verify(() -> PaymentRemovalUtil.reactivateAndRemove(payment));
      trxUtil.verify(() -> TransactionRemovalUtil.reactivateAndRemove(anyString()), never());
      // ...and the reconciliation is still reactivated (kept) exactly once, never deleted.
      recUtil.verify(() -> ReconciliationRemovalUtil.reactivate(rec));
      recUtil.verify(() -> ReconciliationRemovalUtil.reactivateAndRemoveReconciliation(any()), never());
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject mixedData = response.getBody().getJSONObject("response").getJSONObject("data");
    assertTrue(mixedData.getBoolean("reactivated"));
    assertEquals(0, mixedData.getInt("autoConfirmedDrafts"));
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /**
   * An all-auto-created selection covering the WHOLE reconciliation has nothing pre-existing to
   * preserve as a draft, so it degrades to the "Desconciliar" end state: the whole-reconciliation
   * undo. Core's plain {@code reactivate} is never called.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedAllAutoCreatedCoveringFallsBackToUndo() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_Reconciliation rec = recWith("rec-1", t1, t2);
    List<FIN_FinaccTransaction> snapshot = rec.getFINFinaccTransactionList();
    markReactivated(rec);
    wireLoads(t1, t2);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(true).when(handler).isAutoCreated(t1);
    doReturn(true).when(handler).isAutoCreated(t2);
    doNothing().when(handler).undoReconciliation(any(), any(), any());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      // The !anyKept branch re-fetches the reconciliation for the coversReconciliation check.
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1", "T2"));

      // Nothing to keep → delete semantics, so the draft-preserving reactivate never runs.
      recUtil.verify(() -> ReconciliationRemovalUtil.reactivate(any()), never());
    }

    assertEquals(200, response.getHttpStatus());
    verify(handler).undoReconciliation(any(), eq(rec), eq(snapshot));
  }

  /**
   * An all-auto-created selection that does NOT cover the reconciliation detaches just those
   * transactions (the rest stay reconciled); the draft-preserving reactivate never runs.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedAllAutoCreatedSubsetDetachesOnly() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1"); // selected, auto-created
    FIN_FinaccTransaction t2 = txnWithId("T2"); // NOT selected → does not cover the rec
    FIN_Reconciliation rec = recWith("rec-1", t1, t2);
    markReactivated(rec);
    wireLoads(t1, t2);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(true).when(handler).isAutoCreated(t1);

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<TransactionRemovalUtil> trxUtil = mockStatic(TransactionRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

      response = handler.reactivateSelected(removeBody(ACC_ID, LINE_ID, "T1"));

      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(t1));
      recUtil.verify(() -> ReconciliationRemovalUtil.reactivate(any()), never());
      // Auto-created with no payment → the transaction itself is reversed.
      trxUtil.verify(() -> TransactionRemovalUtil.reactivateAndRemove("T1"));
      payUtil.verify(() -> PaymentRemovalUtil.reactivateAndRemove(any()), never());
    }

    assertEquals(200, response.getHttpStatus());
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /**
   * Partial-outcome reporting: a reconciliation whose reactivate did not take effect (it still
   * reports {@code isProcessed() == true}, e.g. Core refused because another draft already exists on
   * the account) is reported per-transaction — HTTP stays 200, {@code reactivated} is false, and the
   * ids are split across {@code transactionIds} / {@code failedTransactionIds}.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReactivateSelectedReportsPerTransactionFailure() throws Exception {
    FIN_FinaccTransaction okTxn = txnWithId("T-OK");
    FIN_FinaccTransaction badTxn = txnWithId("T-BAD");
    FIN_Reconciliation recOk = recWith("rec-ok", okTxn);
    FIN_Reconciliation recBad = recWith("rec-bad", badTxn);
    when(recOk.isProcessed()).thenReturn(false);  // reactivate took effect
    when(recBad.isProcessed()).thenReturn(true);  // still processed → reported as failed
    wireLoads(okTxn, badTxn);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(false).when(handler).isAutoCreated(okTxn);
    doReturn(false).when(handler).isAutoCreated(badTxn);

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Reconciliation.class, "rec-ok")).thenReturn(recOk);
      when(dal.get(FIN_Reconciliation.class, "rec-bad")).thenReturn(recBad);
      recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(any()))
          .thenReturn(Collections.emptyList());

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
      recUtil.verify(() -> ReconciliationRemovalUtil.reactivate(any()), never());
    }

    assertEquals(409, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("period is closed"));
  }

  // ── draftReconciliationOf: the single "is this line in Reactivar state?" detector ──

  /** A line whose transaction's reconciliation is UNPROCESSED is in "Reactivar" (draft) state. */
  @Test
  public void testDraftReconciliationOfReturnsUnprocessedRec() {
    FIN_FinaccTransaction trx = txnWithId("T1");
    FIN_Reconciliation rec = recWith("rec-1", trx);
    when(rec.isProcessed()).thenReturn(false);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, trx);

    assertEquals(rec, handler.draftReconciliationOf(line));
  }

  /** A PROCESSED reconciliation means the line is genuinely reconciled, not awaiting re-confirm. */
  @Test
  public void testDraftReconciliationOfNullWhenRecProcessed() {
    FIN_FinaccTransaction trx = txnWithId("T1");
    FIN_Reconciliation rec = recWith("rec-1", trx);
    when(rec.isProcessed()).thenReturn(true);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, trx);

    assertNull(handler.draftReconciliationOf(line));
  }

  /** A line with no linked transaction is plainly pending — no draft involved. */
  @Test
  public void testDraftReconciliationOfNullWhenLineHasNoTransaction() {
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    assertNull(handler.draftReconciliationOf(line));
    assertNull(handler.draftReconciliationOf(null));
  }

  /** A transaction with no reconciliation at all yields no draft. */
  @Test
  public void testDraftReconciliationOfNullWhenTransactionHasNoRec() {
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getReconciliation()).thenReturn(null);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, trx);

    assertNull(handler.draftReconciliationOf(line));
  }

  // ── reprocessDraftIfAlreadyMatched: re-confirming a Reactivar-ed line ──────────

  /**
   * A line NOT in "Reactivar" state short-circuits to {@code null}, so {@code compose} continues
   * down its normal build-a-fresh-reconciliation path. (That normal path is already asserted
   * end-to-end by the {@code reconcileGroup} happy-path tests, which verify
   * {@code addNewDraftReconciliation} runs.)
   *
   * @throws Exception if the seams fail
   */
  @Test
  public void testReprocessDraftIfAlreadyMatchedReturnsNullForNonDraftLine() throws Exception {
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);

    assertNull(handler.reprocessDraftIfAlreadyMatched(line, Arrays.asList("T1")));
    verify(handler, never()).processReconciliation(any());
  }

  /**
   * Re-confirming the SAME set the draft already holds must PROCESS that same document — no
   * re-linking, no new reconciliation, no re-match — and echo the draft's own id.
   *
   * @throws Exception if the seams fail
   */
  @Test
  public void testReprocessDraftIfAlreadyMatchedSameSelectionProcessesSameDraft() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_Reconciliation draft = recWith("rec-draft", t1, t2);
    when(draft.isProcessed()).thenReturn(false);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, t1);
    when(line.getId()).thenReturn(LINE_ID);
    OBError ok = mock(OBError.class);
    when(ok.getType()).thenReturn("Success");
    doReturn(ok).when(handler).processReconciliation(draft);

    NeoResponse response;
    try (MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      response = handler.reprocessDraftIfAlreadyMatched(line, Arrays.asList("T1", "T2"));
      // The draft is reused, never discarded.
      recUtil.verify(() -> ReconciliationRemovalUtil.reactivateAndRemoveReconciliation(any()), never());
    }

    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals("rec-draft", data.getString("reconciliationId"));
    assertEquals(LINE_ID, data.getJSONArray("lineIds").getString(0));
    // The SAME draft is processed; no fresh reconciliation and no re-matching happen.
    verify(handler).processReconciliation(draft);
    verify(handler, never()).addNewDraftReconciliation(any());
    verify(handler, never()).matchBankStatementLine(any(), any(), any());
  }

  /**
   * A CHANGED selection cannot reuse the draft: it is properly discarded
   * ({@code reactivateAndRemoveReconciliation}) and {@code null} is returned so the caller composes
   * a fresh reconciliation.
   *
   * @throws Exception if the seams fail
   */
  @Test
  public void testReprocessDraftIfAlreadyMatchedDifferentSelectionDiscardsDraft() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_Reconciliation draft = recWith("rec-draft", t1);
    when(draft.isProcessed()).thenReturn(false);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, t1);

    NeoResponse response;
    try (MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class)) {
      // Selection now also includes T2 → differs from the draft's {T1}.
      response = handler.reprocessDraftIfAlreadyMatched(line, Arrays.asList("T1", "T2"));
      recUtil.verify(() -> ReconciliationRemovalUtil.reactivateAndRemoveReconciliation(draft));
    }

    // null → the caller falls through to the normal compose path.
    assertNull(response);
    verify(handler, never()).processReconciliation(any());
  }

  /**
   * When processing the reused draft fails, the transaction is rolled back and the Core message is
   * surfaced as a 400 (same contract as {@code compose}'s own error branch).
   *
   * @throws Exception if the seams fail
   */
  @Test
  public void testReprocessDraftIfAlreadyMatchedProcessErrorRollsBack() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_Reconciliation draft = recWith("rec-draft", t1);
    when(draft.isProcessed()).thenReturn(false);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, t1);
    OBError err = mock(OBError.class);
    when(err.getType()).thenReturn("Error");
    when(err.getMessage()).thenReturn("@APRM_DraftReconciliationExists@");
    doReturn(err).when(handler).processReconciliation(draft);

    NeoResponse response = handler.reprocessDraftIfAlreadyMatched(line, Arrays.asList("T1"));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("APRM_DraftReconciliationExists"));
    verify(handler).doRollbackAndClose();
  }

  // ── buildCandidates on a Reactivar-ed line: editable list, own txns pre-selected ──

  /**
   * A "Reactivar"-ed line keeps its transaction link, but it is NOT reconciled — so
   * {@code buildCandidates} must NOT take the read-only linked-movements shortcut (which is what a
   * genuinely reconciled line gets). It runs the normal SQL path instead, and force-adds the draft's
   * own transaction ids to the suggested set so they come back pre-selected.
   *
   * <p>Asserted via the observable branch decision: the SQL path is reached (the mocked
   * {@link PreparedStatement} is executed and its rows are returned) and the draft's transaction is
   * flagged {@code suggested} even though {@code suggestedTransactionIds} returned nothing.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testCandidatesForReactivatedLineIsEditableAndPreSelectsDraftTxns() throws Exception {
    FIN_FinaccTransaction draftTxn = txnWithId("t1");
    FIN_Reconciliation draft = recWith("rec-draft", draftTxn);
    when(draft.isProcessed()).thenReturn(false);
    // The line still points at its transaction (Core's reactivate leaves every link intact).
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, draftTxn);
    doReturn(line).when(handler).loadLine(LINE_ID);
    // Nothing suggested by the standard algorithm — any `suggested` flag must come from the draft.
    doReturn(new HashSet<String>()).when(handler)
        .suggestedTransactionIds(eq(ACC_ID), eq(LINE_ID), anyInt());

    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_finacc_transaction_id")).thenReturn("t1");
    when(rs.getTimestamp("statementdate")).thenReturn(null);
    when(rs.getString("document_no")).thenReturn("PAY-1");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("50.00"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<AutoMatchSupport> ams = mockStatic(AutoMatchSupport.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);
      // A non-null selected line makes buildCandidates consult the signal-group matcher; keep it
      // inert so the only `suggested` flag can come from the draft (same stub style as
      // testBuildCandidatesPreMarksSignalGroup).
      ams.when(() -> AutoMatchSupport.findSignalGroup(eq(ACC_ID), eq(line), any(), any(), anyInt()))
          .thenReturn(Collections.emptyList());

      NeoResponse response = handler.buildCandidates(ACC_ID, LINE_ID, null, null, null);

      JSONArray candidates = response.getBody().getJSONObject("response").getJSONObject("data")
          .getJSONArray("candidates");
      // The read-only path returns linked movements WITHOUT running this SQL; getting a row back
      // proves the editable SQL path was taken.
      assertEquals(1, candidates.length());
      assertEquals("t1", candidates.getJSONObject(0).getString(ReconciliationHandler.KEY_ID));
      // Pre-selected purely because it belongs to the draft.
      assertTrue(candidates.getJSONObject(0).getBoolean("suggested"));
      // The draft's reconciliation id is bound FIRST so the SQL's OR-branch exposes its (still
      // linked, still RPPC) transactions; the account moves to the second slot.
      verify(ps).setString(1, "rec-draft");
      verify(ps).setString(2, ACC_ID);
    }
  }

  // ── Re-confirming a "Reactivar"-ed line end-to-end, and the 3 widened guards ───
  // Core's reactivate keeps every link intact, so three guards used to read "has a link" as "is
  // reconciled" and rejected a reactivated line with a 409. Each now additionally requires the
  // reconciliation to be a PROCESSED one. These tests pin both halves: the reactivated line now
  // flows through, and a genuinely reconciled one is still refused.

  /**
   * Builds a line in "Reactivar" state: still matched to {@code txnId}, whose reconciliation
   * {@code recId} is an UNPROCESSED draft holding exactly that one transaction. Wires
   * {@code loadAccount}/{@code loadLine}/{@code loadTransaction} so {@code reconcileGroup} can run
   * end-to-end, and returns the draft.
   */
  private FIN_Reconciliation reactivatedLineSetup(String recId, String txnId, BigDecimal amount) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_Reconciliation draft = mock(FIN_Reconciliation.class);
    when(draft.getId()).thenReturn(recId);
    when(draft.isProcessed()).thenReturn(false);
    FIN_FinaccTransaction draftTxn = trxFor(ACC_ID, amount, BigDecimal.ZERO, draft);
    when(draftTxn.getId()).thenReturn(txnId);
    when(draft.getFINFinaccTransactionList()).thenReturn(Arrays.asList(draftTxn));
    FIN_BankStatementLine line = lineFor(ACC_ID, amount, BigDecimal.ZERO, draftTxn);
    // lineFor does not stub getId(), which the reprocess path echoes back in `lineIds`.
    when(line.getId()).thenReturn(LINE_ID);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(draftTxn).when(handler).loadTransaction(txnId);
    return draft;
  }

  /**
   * END-TO-END: {@code reconcileGroup} on a "Reactivar"-ed line, re-confirming the SAME selection.
   * It must NOT 409 at any of the three guards, and must take
   * {@code reprocessDraftIfAlreadyMatched}'s same-selection path — processing THAT draft instead of
   * composing a new reconciliation (Core would refuse the transactions, which are not free, and the
   * draft would be orphaned).
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupOnReactivatedLineReprocessesSameDraft() throws Exception {
    FIN_Reconciliation draft = reactivatedLineSetup("rec-draft", "t1", new BigDecimal("100.00"));
    OBError ok = mock(OBError.class);
    when(ok.getType()).thenReturn("Success");
    doReturn(ok).when(handler).processReconciliation(draft);

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    // Not rejected: neither the line guard nor validateOperations treats the draft as reconciled.
    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    // The SAME draft is confirmed — not a freshly composed reconciliation.
    assertEquals("rec-draft", data.getString("reconciliationId"));
    assertEquals(1, data.getJSONArray("lineIds").length());
    assertEquals(LINE_ID, data.getJSONArray("lineIds").getString(0));
    verify(handler).processReconciliation(draft);
    verify(handler, never()).addNewDraftReconciliation(any());
    verify(handler, never()).matchBankStatementLine(any(), any(), any());
  }

  /**
   * Guard 1 regression ({@code reconcileGroup}): a genuinely reconciled line — its reconciliation is
   * PROCESSED — is still refused with a 409. Proves the draft exemption did not blow the guard open.
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupStillRejectsProcessedReconciliationWith409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
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
   * Guard 2 regression ({@code applyGroup}): the same processed-reconciliation case, recorded as a
   * per-group error in the {@code results} array (applySuggestions reports per group rather than
   * failing the whole request).
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testApplySuggestionsStillRecordsAlreadyReconciledForProcessedRec() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
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
   * Guard 3 regression ({@code ReconciliationFlowSupport.validateOperations}): the exemption is
   * scoped to the line's OWN draft. An operation hanging off a DIFFERENT reconciliation is still a
   * 409 conflict, even though the line itself is legitimately in "Reactivar" state (so guard 1 lets
   * it through and validateOperations is actually reached).
   *
   * @throws Exception if building the body or stubbing the seams fails
   */
  @Test
  public void testReconcileGroupStillRejectsOperationOnAnotherReconciliation() throws Exception {
    // The line is in Reactivar state on its own draft "rec-draft" (holding "t1").
    reactivatedLineSetup("rec-draft", "t1", new BigDecimal("100.00"));
    // ...but the requested operation belongs to a DIFFERENT reconciliation.
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
}
