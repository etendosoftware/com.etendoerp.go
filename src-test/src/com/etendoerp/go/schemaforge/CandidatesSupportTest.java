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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Mockito-driven unit tests for {@link CandidatesSupport}, the candidate-listing helper bundle
 * extracted from {@link ReconciliationHandler}. Both helpers run raw SQL through the DAL connection,
 * so each test mocks {@code OBDal} (and {@code OBContext} for the org tree) and drives a fake
 * {@link ResultSet}.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>buildLinkedTransactions: maps each linked-movement row to the read-only candidate shape;
 *       empty result set yields an empty list.</li>
 *   <li>candidateCounts: unknown account short-circuits to all-zero counts; a real account computes
 *       per-receipt and per-issotrx counts; any failure is swallowed (counts are decorative).</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class CandidatesSupportTest {

  private static final String ACC_ID = "acc-1";
  private static final String CLIENT_ID = "client-1";
  private static final String ORG_ID = "org-1";
  private static final String LINE_ID = "line-1";

  private JSONArray candidatesOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data")
        .getJSONArray("candidates");
  }

  // ── buildLinkedTransactions ────────────────────────────────────────────────

  /**
   * Each row of the linked-movements query becomes a read-only candidate: reconciled status,
   * {@code linked:true}, {@code suggested:false}, and pendingBalance equal to the amount. The line id
   * is bound twice (the line itself and the match-group sub-query).
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsMapsRows() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_finacc_transaction_id")).thenReturn("t1");
    when(rs.getTimestamp("statementdate")).thenReturn(null);
    when(rs.getString("document_no")).thenReturn("PAY-1");
    when(rs.getString("partner_name")).thenReturn("ACME");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("50.00"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      NeoResponse response = CandidatesSupport.buildLinkedTransactions(LINE_ID);

      JSONArray candidates = candidatesOf(response);
      assertEquals(1, candidates.length());
      JSONObject row = candidates.getJSONObject(0);
      assertEquals("t1", row.getString("id"));
      assertEquals("PAY-1", row.getString("documentNo"));
      assertEquals("ACME", row.getString("partnerName"));
      assertEquals("reconciled", row.getString("status"));
      assertTrue(row.getBoolean("linked"));
      assertFalse(row.getBoolean("suggested"));
      assertEquals(0, new BigDecimal("50.00").compareTo(new BigDecimal(row.getString("amount"))));
      // lineId is bound twice (the line, and the match-group sub-query).
      verify(ps).setString(1, LINE_ID);
      verify(ps).setString(2, LINE_ID);
    }
  }

  /**
   * An empty result set yields an empty candidates array (still a valid 200 envelope).
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsEmpty() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      NeoResponse response = CandidatesSupport.buildLinkedTransactions(LINE_ID);

      assertEquals(200, response.getHttpStatus());
      assertEquals(0, candidatesOf(response).length());
    }
  }

  // ── candidateCounts ─────────────────────────────────────────────────────────

  /** An unknown account short-circuits before any query and returns all-zero counts. */
  @Test
  public void testCandidateCountsUnknownAccountReturnsZeros() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinancialAccount.class, ACC_ID)).thenReturn(null);

      JSONObject counts = CandidatesSupport.candidateCounts(ACC_ID, null, null);

      assertEquals(0, counts.getInt("receipts"));
      assertEquals(0, counts.getInt("payments"));
      assertEquals(0, counts.getInt("salesInvoices"));
      assertEquals(0, counts.getInt("purchaseInvoices"));
    }
  }

  /**
   * With a real account, candidateCounts runs both count queries: the transaction query splits by
   * receipt flag (receipts/payments) and the invoice query splits by issotrx (sales/purchase).
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testCandidateCountsComputesPerType() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getClient()).thenReturn(client);
    when(account.getOrganization()).thenReturn(org);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(org.getId()).thenReturn(ORG_ID);

    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet txnRs = mock(ResultSet.class);
    when(txnRs.next()).thenReturn(true, true, false);
    when(txnRs.getString("is_receipt")).thenReturn("Y", "N");
    when(txnRs.getInt("cnt")).thenReturn(3, 5);
    ResultSet invRs = mock(ResultSet.class);
    when(invRs.next()).thenReturn(true, true, false);
    when(invRs.getString("issotrx")).thenReturn("Y", "N");
    when(invRs.getInt("cnt")).thenReturn(2, 4);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinancialAccount.class, ACC_ID)).thenReturn(account);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(conn.createArrayOf(anyString(), any())).thenReturn(null);
      // First query → transaction counts, second query → invoice counts.
      when(ps.executeQuery()).thenReturn(txnRs, invRs);

      OBContext ctx = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(ctx.getOrganizationStructureProvider(CLIENT_ID)).thenReturn(osp);
      obContext.when(OBContext::getOBContext).thenReturn(ctx);

      JSONObject counts = CandidatesSupport.candidateCounts(ACC_ID, null, null);

      assertEquals(3, counts.getInt("receipts"));
      assertEquals(5, counts.getInt("payments"));
      assertEquals(2, counts.getInt("salesInvoices"));
      assertEquals(4, counts.getInt("purchaseInvoices"));
    }
  }

  /** Counts are decorative: a failure mid-query is swallowed and zeroed counts are returned. */
  @Test
  public void testCandidateCountsSwallowsErrors() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinancialAccount.class, ACC_ID)).thenReturn(account);
      when(dal.getConnection()).thenThrow(new RuntimeException("boom"));

      JSONObject counts = CandidatesSupport.candidateCounts(ACC_ID, null, null);

      assertEquals(0, counts.getInt("receipts"));
      assertEquals(0, counts.getInt("payments"));
    }
  }
}
