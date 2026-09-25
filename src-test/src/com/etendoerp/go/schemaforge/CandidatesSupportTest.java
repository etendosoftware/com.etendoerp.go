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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
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
 *       empty result set yields an empty list; a movement whose stored foreign currency differs
 *       from the account currency is re-expressed in its original currency (ETP-5450).</li>
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

  // ── buildLinkedTransactions: foreign-currency original (ETP-5450) ───────────
  //
  // A linked movement whose transaction stored a foreign-currency trio (foreign_currency_id,
  // foreign_amount, foreign_convert_rate) different from the account currency is re-expressed in
  // its original document currency, with the account-currency amount kept as amountBase — the same
  // keys a pending foreign-invoice row carries, so the panel renders both identically.

  private static final String USD = "USD";
  private static final String EUR = "EUR";
  private static final String USD_ID = "cur-usd";

  /**
   * Stubs one linked-movement row: the base columns every row carries plus the foreign-currency
   * columns of {@code LINKED_TXNS_SQL}. Any argument may be {@code null} to model a missing value.
   */
  private ResultSet linkedRow(BigDecimal amount, BigDecimal foreignAmount, BigDecimal rate,
      String foreignIso, String accountIso) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_finacc_transaction_id")).thenReturn("t1");
    when(rs.getTimestamp("statementdate")).thenReturn(null);
    when(rs.getString("document_no")).thenReturn("PAY-1");
    when(rs.getString("partner_name")).thenReturn("ACME");
    when(rs.getBigDecimal("amount")).thenReturn(amount);
    when(rs.getBigDecimal("foreign_amount")).thenReturn(foreignAmount);
    when(rs.getBigDecimal("foreign_convert_rate")).thenReturn(rate);
    when(rs.getString("foreign_currency_id")).thenReturn(USD_ID);
    when(rs.getString("foreign_currency_iso")).thenReturn(foreignIso);
    when(rs.getString("account_currency_iso")).thenReturn(accountIso);
    return rs;
  }

  /** Runs buildLinkedTransactions over {@code rs} and returns its single candidate row. */
  private JSONObject runLinked(ResultSet rs) throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      JSONArray candidates = candidatesOf(CandidatesSupport.buildLinkedTransactions(LINE_ID));
      assertEquals(1, candidates.length());
      return candidates.getJSONObject(0);
    }
  }

  private static void assertAmount(String expected, JSONObject row, String key) throws Exception {
    assertEquals(key + " was " + row.get(key), 0,
        new BigDecimal(expected).compareTo(new BigDecimal(row.getString(key))));
  }

  /** Asserts none of the foreign-original keys were added (same-currency shape). */
  private static void assertNoForeignKeys(JSONObject row) {
    assertFalse(row.has("amountBase"));
    assertFalse(row.has("currency"));
    assertFalse(row.has("currencyId"));
    assertFalse(row.has("baseCurrency"));
    assertFalse(row.has("rate"));
  }

  /**
   * A USD receipt on a EUR account: amount/pendingBalance carry the original USD document amount,
   * amountBase the EUR amount, and currency/currencyId/baseCurrency/rate describe the pair.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsExpressesForeignRowInOriginalCurrency() throws Exception {
    JSONObject row = runLinked(linkedRow(new BigDecimal("29.03"), new BigDecimal("42.67"),
        new BigDecimal("0.6803"), USD, EUR));

    assertAmount("42.67", row, "amount");
    assertAmount("42.67", row, "pendingBalance");
    assertAmount("29.03", row, "amountBase");
    assertEquals(USD, row.getString("currency"));
    assertEquals(USD_ID, row.getString("currencyId"));
    assertEquals(EUR, row.getString("baseCurrency"));
    assertAmount("0.6803", row, "rate");
    // The read-only linked shape is untouched.
    assertEquals("reconciled", row.getString("status"));
    assertTrue(row.getBoolean("linked"));
  }

  /**
   * A payment (negative account amount): the stored foreign_amount is unsigned, so it takes the
   * sign of the account-currency amount.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsForeignPaymentIsNegative() throws Exception {
    JSONObject row = runLinked(linkedRow(new BigDecimal("-29.03"), new BigDecimal("42.67"),
        new BigDecimal("0.6803"), USD, EUR));

    assertAmount("-42.67", row, "amount");
    assertAmount("-42.67", row, "pendingBalance");
    assertAmount("-29.03", row, "amountBase");
  }

  /**
   * A negative stored foreign_amount on a receipt is still re-signed from the account amount (the
   * sign always follows the account-currency amount, never the stored value).
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsForeignSignFollowsAccountAmount() throws Exception {
    JSONObject row = runLinked(linkedRow(new BigDecimal("29.03"), new BigDecimal("-42.67"),
        null, USD, EUR));

    assertAmount("42.67", row, "amount");
  }

  /**
   * No stored foreign amount: the row keeps the account-currency shape.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsWithoutForeignAmountIsUnchanged() throws Exception {
    JSONObject row = runLinked(linkedRow(new BigDecimal("29.03"), null,
        new BigDecimal("0.6803"), USD, EUR));

    assertAmount("29.03", row, "amount");
    assertAmount("29.03", row, "pendingBalance");
    assertNoForeignKeys(row);
  }

  /**
   * Foreign ISO equal to the account ISO (Core may store the trio even for a same-currency
   * payment): not a foreign row.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsSameCurrencyIsUnchanged() throws Exception {
    JSONObject row = runLinked(linkedRow(new BigDecimal("29.03"), new BigDecimal("29.03"),
        BigDecimal.ONE, EUR, EUR));

    assertAmount("29.03", row, "amount");
    assertNoForeignKeys(row);
  }

  /**
   * A null or blank foreign ISO (no foreign currency stored, or the join found nothing): not a
   * foreign row, even with a foreign amount present.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsNullOrBlankForeignIsoIsUnchanged() throws Exception {
    JSONObject nullIso = runLinked(linkedRow(new BigDecimal("29.03"), new BigDecimal("42.67"),
        new BigDecimal("0.6803"), null, EUR));
    assertAmount("29.03", nullIso, "amount");
    assertNoForeignKeys(nullIso);

    JSONObject blankIso = runLinked(linkedRow(new BigDecimal("29.03"), new BigDecimal("42.67"),
        new BigDecimal("0.6803"), "   ", EUR));
    assertAmount("29.03", blankIso, "amount");
    assertNoForeignKeys(blankIso);
  }

  /**
   * A null or blank account ISO (the {@code acur} join found no currency): the pair cannot be
   * described, so the row keeps the account-currency shape instead of emitting a blank
   * {@code baseCurrency}.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsNullOrBlankAccountIsoIsUnchanged() throws Exception {
    JSONObject nullIso = runLinked(linkedRow(new BigDecimal("29.03"), new BigDecimal("42.67"),
        new BigDecimal("0.6803"), USD, null));
    assertAmount("29.03", nullIso, "amount");
    assertAmount("29.03", nullIso, "pendingBalance");
    assertNoForeignKeys(nullIso);

    JSONObject blankIso = runLinked(linkedRow(new BigDecimal("29.03"), new BigDecimal("42.67"),
        new BigDecimal("0.6803"), USD, "  "));
    assertAmount("29.03", blankIso, "amount");
    assertAmount("29.03", blankIso, "pendingBalance");
    assertNoForeignKeys(blankIso);
  }

  /**
   * A foreign row without a stored rate gets every foreign key except {@code rate}.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsForeignWithoutRateOmitsRate() throws Exception {
    JSONObject row = runLinked(linkedRow(new BigDecimal("29.03"), new BigDecimal("42.67"),
        null, USD, EUR));

    assertAmount("42.67", row, "amount");
    assertAmount("42.67", row, "pendingBalance");
    assertAmount("29.03", row, "amountBase");
    assertEquals(USD, row.getString("currency"));
    assertEquals(USD_ID, row.getString("currencyId"));
    assertEquals(EUR, row.getString("baseCurrency"));
    assertFalse(row.has("rate"));
  }

  /**
   * The mocked ResultSet is blind to the query, so assert the SQL actually prepared selects the
   * stored foreign-currency trio and joins both currencies — otherwise every column read above
   * would be null against a real database and the feature would silently never trigger.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testLinkedTransactionsSqlSelectsForeignCurrencyColumns() throws Exception {
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

      CandidatesSupport.buildLinkedTransactions(LINE_ID);

      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(conn).prepareStatement(captor.capture());
      String sql = captor.getValue().replaceAll("\\s+", " ");
      assertTrue(sql, sql.contains("ft.foreign_amount"));
      assertTrue(sql, sql.contains("ft.foreign_convert_rate"));
      assertTrue(sql, sql.contains("ft.foreign_currency_id"));
      assertTrue(sql, sql.contains("fcur.iso_code AS foreign_currency_iso"));
      assertTrue(sql, sql.contains("acur.iso_code AS account_currency_iso"));
      assertTrue(sql, sql.contains(
          "LEFT JOIN c_currency fcur ON fcur.c_currency_id = ft.foreign_currency_id"));
      assertTrue(sql, sql.contains(
          "LEFT JOIN c_currency acur ON acur.c_currency_id = ft.c_currency_id"));
    }
  }

  // ── buildLinkedTransactions: QA edge cases (ETP-5450) ───────────────────────
  //
  // Multi-row groups: appendForeignOriginal reads some columns only for foreign rows, so a
  // sequential (thenReturn(a, b, c)) stub would drift between rows. These tests drive the mocked
  // ResultSet from a per-row column map indexed by a cursor that advances on next().

  /** One linked-movement row as a column map (null values model SQL NULL). */
  private static Map<String, Object> linkedData(String id, String amount, String foreignAmount,
      String rate, String foreignId, String foreignIso, String accountIso) {
    Map<String, Object> row = new HashMap<>();
    row.put("fin_finacc_transaction_id", id);
    row.put("document_no", "DOC-" + id);
    row.put("partner_name", "BP-" + id);
    row.put("amount", amount == null ? null : new BigDecimal(amount));
    row.put("foreign_amount", foreignAmount == null ? null : new BigDecimal(foreignAmount));
    row.put("foreign_convert_rate", rate == null ? null : new BigDecimal(rate));
    row.put("foreign_currency_id", foreignId);
    row.put("foreign_currency_iso", foreignIso);
    row.put("account_currency_iso", accountIso);
    return row;
  }

  /** A ResultSet over {@code rows}, answering every column read from the current row's map. */
  private static ResultSet resultSetOf(List<Map<String, Object>> rows) throws Exception {
    int[] cursor = { -1 };
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenAnswer(inv -> ++cursor[0] < rows.size());
    when(rs.getString(anyString()))
        .thenAnswer(inv -> (String) rows.get(cursor[0]).get(inv.<String> getArgument(0)));
    when(rs.getBigDecimal(anyString()))
        .thenAnswer(inv -> (BigDecimal) rows.get(cursor[0]).get(inv.<String> getArgument(0)));
    when(rs.getTimestamp(anyString())).thenReturn(null);
    return rs;
  }

  private JSONArray runLinkedRows(List<Map<String, Object>> rows) throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = resultSetOf(rows);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      return candidatesOf(CandidatesSupport.buildLinkedTransactions(LINE_ID));
    }
  }

  /**
   * A 1:N match group mixing a USD receipt, a EUR bank fee (payment-less movement: no foreign data
   * at all) and a GBP payment: each row is re-expressed independently, with its own currency and
   * sign, and the same-currency fee keeps the plain account-currency shape — no key leaks from one
   * row into the next.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsMixedCurrencyGroupIsPerRow() throws Exception {
    JSONArray rows = runLinkedRows(List.of(
        linkedData("t-usd", "29.03", "42.67", "0.6803", USD_ID, USD, EUR),
        linkedData("t-fee", "-2.50", null, null, null, null, EUR),
        linkedData("t-gbp", "-12.00", "10.00", "1.2", "cur-gbp", "GBP", EUR)));

    assertEquals(3, rows.length());

    JSONObject usd = rows.getJSONObject(0);
    assertEquals("t-usd", usd.getString("id"));
    assertAmount("42.67", usd, "amount");
    assertAmount("29.03", usd, "amountBase");
    assertEquals(USD, usd.getString("currency"));
    assertEquals(USD_ID, usd.getString("currencyId"));

    JSONObject fee = rows.getJSONObject(1);
    assertEquals("t-fee", fee.getString("id"));
    assertAmount("-2.50", fee, "amount");
    assertAmount("-2.50", fee, "pendingBalance");
    assertNoForeignKeys(fee);

    JSONObject gbp = rows.getJSONObject(2);
    assertEquals("t-gbp", gbp.getString("id"));
    assertAmount("-10.00", gbp, "amount");
    assertAmount("-10.00", gbp, "pendingBalance");
    assertAmount("-12.00", gbp, "amountBase");
    assertEquals("GBP", gbp.getString("currency"));
    assertEquals("cur-gbp", gbp.getString("currencyId"));
    assertEquals(EUR, gbp.getString("baseCurrency"));
    assertAmount("1.2", gbp, "rate");
  }

  /**
   * Non-EUR account (USD) with a EUR payment — the real Core shape: 500 EUR stored as
   * foreign_amount, 1250 USD deposit, rate 2.5. Nothing is EUR-hardcoded: the document currency is
   * EUR and the base currency is the account's USD.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsNonEurAccount() throws Exception {
    JSONObject row = runLinkedRows(List.of(
        linkedData("t1", "1250", "500", "2.5", "cur-eur", EUR, USD))).getJSONObject(0);

    assertAmount("500", row, "amount");
    assertAmount("500", row, "pendingBalance");
    assertAmount("1250", row, "amountBase");
    assertEquals(EUR, row.getString("currency"));
    assertEquals("cur-eur", row.getString("currencyId"));
    assertEquals(USD, row.getString("baseCurrency"));
    assertAmount("2.5", row, "rate");
  }

  /**
   * A zero account-currency amount (deposit = payment) with a stored foreign amount: signum 0 is
   * treated as non-negative, so the foreign amount is emitted positive and amountBase stays 0.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsZeroAmountForeignIsNonNegative() throws Exception {
    JSONObject row = runLinkedRows(List.of(
        linkedData("t1", "0", "5.00", "0.6803", USD_ID, USD, EUR))).getJSONObject(0);

    assertAmount("5.00", row, "amount");
    assertAmount("0", row, "amountBase");
    assertEquals(USD, row.getString("currency"));
  }

  /**
   * A null account-currency amount is normalised to 0 before the foreign re-expression, so a
   * foreign row never throws on a missing deposit/payment (the SQL COALESCEs, this guards the Java).
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testBuildLinkedTransactionsNullAmountForeignDoesNotThrow() throws Exception {
    JSONObject row = runLinkedRows(List.of(
        linkedData("t1", null, "5.00", null, USD_ID, USD, EUR))).getJSONObject(0);

    assertAmount("5.00", row, "amount");
    assertAmount("0", row, "amountBase");
    assertFalse(row.has("rate"));
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
      // The account id comes from the request, so it is resolved through TenantOwnership, which
      // checks it against these two sets (ETP-4950). Mockito defaults them to EMPTY arrays, which
      // would make the account invisible and silently zero every count.
      when(ctx.getReadableClients()).thenReturn(new String[] {CLIENT_ID});
      when(ctx.getReadableOrganizations()).thenReturn(new String[] {ORG_ID});
      obContext.when(OBContext::getOBContext).thenReturn(ctx);

      JSONObject counts = CandidatesSupport.candidateCounts(ACC_ID, null, null);

      assertEquals(3, counts.getInt("receipts"));
      assertEquals(5, counts.getInt("payments"));
      assertEquals(2, counts.getInt("salesInvoices"));
      assertEquals(4, counts.getInt("purchaseInvoices"));
    }
  }

  /**
   * Both count queries filter TIMESTAMP columns ({@code ft.statementdate}, {@code inv.dateinvoiced}),
   * so the upper bound must be an EXCLUSIVE {@code < next day}, not {@code <= dateTo} — a {@code <=}
   * bound would silently drop rows with a non-zero time of day on the last day of the range
   * (ETP-5449), same reasoning as {@code ReconciliationHandlerTest}'s SQL-shape tests.
   *
   * @throws Exception if the mocked JDBC interaction fails
   */
  @Test
  public void testCandidateCountsSqlUsesExclusiveUpperBoundForDateTo() throws Exception {
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
    when(txnRs.next()).thenReturn(false);
    ResultSet invRs = mock(ResultSet.class);
    when(invRs.next()).thenReturn(false);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinancialAccount.class, ACC_ID)).thenReturn(account);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(conn.createArrayOf(anyString(), any())).thenReturn(null);
      when(ps.executeQuery()).thenReturn(txnRs, invRs);

      OBContext ctx = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(ctx.getOrganizationStructureProvider(CLIENT_ID)).thenReturn(osp);
      when(ctx.getReadableClients()).thenReturn(new String[] {CLIENT_ID});
      when(ctx.getReadableOrganizations()).thenReturn(new String[] {ORG_ID});
      obContext.when(OBContext::getOBContext).thenReturn(ctx);

      CandidatesSupport.candidateCounts(ACC_ID, "2026-01-01", "2026-01-31");

      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(conn, times(2)).prepareStatement(sql.capture());
      String txnSql = sql.getAllValues().get(0);
      String invSql = sql.getAllValues().get(1);
      assertTrue("TXN_COUNTS_SQL dateTo must be exclusive; got: " + txnSql,
          txnSql.contains("ft.statementdate < CAST(? AS date) + 1"));
      assertFalse("TXN_COUNTS_SQL must not truncate via <=; got: " + txnSql,
          txnSql.contains("ft.statementdate <= ?"));
      assertTrue("INVOICE_COUNTS_SQL dateTo must be exclusive; got: " + invSql,
          invSql.contains("inv.dateinvoiced < CAST(? AS date) + 1"));
      assertFalse("INVOICE_COUNTS_SQL must not truncate via <=; got: " + invSql,
          invSql.contains("inv.dateinvoiced <= ?"));
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
