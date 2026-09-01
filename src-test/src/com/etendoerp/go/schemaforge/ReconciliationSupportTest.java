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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Mockito-driven unit tests for {@link ReconciliationSupport}, the stateless helper bundle extracted
 * from {@link ReconciliationHandler}. Every method is package-private and is exercised here directly
 * (same package). No DB or OBContext is required: the only collaborators are a mocked
 * {@link PreparedStatement} and mocked DAL entities.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>envelope: wraps data in the standard {@code response.data} NEO envelope.</li>
 *   <li>formatDate: null → empty; non-null → ISO-8601 UTC string.</li>
 *   <li>nullSafe: null → ZERO; value kept (replaces the old handler-level test).</li>
 *   <li>bindDateRange: blank → setNull x4; set → setDate at the right indices; returns idx + 4.</li>
 *   <li>docTypeToIsReceipt: payments (any case) → 'N'; everything else → 'Y'.</li>
 *   <li>readOperationIds: missing array → empty; blanks/nulls skipped.</li>
 *   <li>belongsToAccount: matching id only; null statement/account → false.</li>
 *   <li>signedAmount: deposit - payment, each null-safe.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ReconciliationSupportTest {

  private static final String ACC_ID = "acc-1";
  private static final String OTHER_ACC = "acc-2";

  // ── envelope ─────────────────────────────────────────────────────────────────

  /**
   * envelope wraps the payload in {@code { response: { data } }} and returns a 200 NeoResponse whose
   * body exposes the original object under response.data.
   *
   * @throws Exception if building the JSON envelope fails
   */
  @Test
  public void testEnvelopeWrapsDataInResponseData() throws Exception {
    JSONObject data = new JSONObject().put("foo", "bar").put("n", 7);

    NeoResponse response = ReconciliationSupport.envelope(data);

    assertEquals(200, response.getHttpStatus());
    JSONObject wrapped = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals("bar", wrapped.getString("foo"));
    assertEquals(7, wrapped.getInt("n"));
  }

  // ── formatDate ───────────────────────────────────────────────────────────────

  /** A null timestamp formats to the empty string. */
  @Test
  public void testFormatDateNullReturnsEmpty() {
    assertEquals("", ReconciliationSupport.formatDate(null));
  }

  /**
   * A non-null timestamp formats to an ISO-8601 UTC string. Epoch 0 must render exactly
   * {@code 1970-01-01T00:00:00Z}, and the output must match the {@code yyyy-MM-dd'T'HH:mm:ss'Z'}
   * shape.
   */
  @Test
  public void testFormatDateNonNullReturnsIsoUtc() {
    String formatted = ReconciliationSupport.formatDate(new Timestamp(0L));
    assertEquals("1970-01-01T00:00:00Z", formatted);
    assertTrue(formatted.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"));
  }

  // ── nullSafe ─────────────────────────────────────────────────────────────────

  /** A null BigDecimal is coerced to ZERO; a present value is returned unchanged. */
  @Test
  public void testNullSafe() {
    assertEquals(BigDecimal.ZERO, ReconciliationSupport.nullSafe(null));
    BigDecimal value = new BigDecimal("12.34");
    assertEquals(value, ReconciliationSupport.nullSafe(value));
  }

  // ── bindDateRange ────────────────────────────────────────────────────────────

  /**
   * Both bounds blank → the four params are bound as SQL NULL (no-op clause) and the next free index
   * is {@code idx + 4}.
   *
   * @throws Exception if the mocked JDBC binding fails
   */
  @Test
  public void testBindDateRangeBothBlankBindsNull() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);

    int next = ReconciliationSupport.bindDateRange(ps, 4, "", null);

    assertEquals(8, next);
    verify(ps, times(4)).setNull(anyInt(), eq(Types.DATE));
    verify(ps).setNull(4, Types.DATE);
    verify(ps).setNull(5, Types.DATE);
    verify(ps).setNull(6, Types.DATE);
    verify(ps).setNull(7, Types.DATE);
  }

  /**
   * Both bounds set → dateFrom is bound at the first two indices and dateTo at the next two, and the
   * next free index is {@code idx + 4}.
   *
   * @throws Exception if the mocked JDBC binding fails
   */
  @Test
  public void testBindDateRangeBothSetBindsDates() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);

    int next = ReconciliationSupport.bindDateRange(ps, 1, "2026-01-01", "2026-01-31");

    assertEquals(5, next);
    verify(ps).setDate(1, Date.valueOf("2026-01-01"));
    verify(ps).setDate(2, Date.valueOf("2026-01-01"));
    verify(ps).setDate(3, Date.valueOf("2026-01-31"));
    verify(ps).setDate(4, Date.valueOf("2026-01-31"));
  }

  // ── docTypeToIsReceipt ───────────────────────────────────────────────────────

  /** "payments" (any case) maps to the receipt flag 'N'. */
  @Test
  public void testDocTypeToIsReceiptPaymentsReturnsN() {
    assertEquals("N", ReconciliationSupport.docTypeToIsReceipt("payments"));
    assertEquals("N", ReconciliationSupport.docTypeToIsReceipt("PAYMENTS"));
    assertEquals("N", ReconciliationSupport.docTypeToIsReceipt("Payments"));
  }

  /** Anything other than "payments" — including null — maps to 'Y'. */
  @Test
  public void testDocTypeToIsReceiptOtherReturnsY() {
    assertEquals("Y", ReconciliationSupport.docTypeToIsReceipt(null));
    assertEquals("Y", ReconciliationSupport.docTypeToIsReceipt("receipts"));
    assertEquals("Y", ReconciliationSupport.docTypeToIsReceipt("salesInvoices"));
  }

  // ── readOperationIds ─────────────────────────────────────────────────────────

  /**
   * A body without an operationIds array yields an empty list.
   *
   * @throws Exception if reading the JSON body fails
   */
  @Test
  public void testReadOperationIdsMissingArrayReturnsEmpty() throws Exception {
    assertTrue(ReconciliationSupport.readOperationIds(new JSONObject()).isEmpty());
  }

  /**
   * Blank and null entries are skipped; only the non-blank ids survive in order.
   *
   * @throws Exception if reading the JSON body fails
   */
  @Test
  public void testReadOperationIdsSkipsBlanksAndNulls() throws Exception {
    JSONArray arr = new JSONArray().put("a").put("").put("b").put(JSONObject.NULL);
    JSONObject body = new JSONObject().put("operationIds", arr);

    List<String> ids = ReconciliationSupport.readOperationIds(body);

    assertEquals(2, ids.size());
    assertEquals("a", ids.get(0));
    assertEquals("b", ids.get(1));
  }

  // ── belongsToAccount ─────────────────────────────────────────────────────────

  private FIN_BankStatementLine lineWithAccount(String accountId) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    FIN_BankStatement bs = mock(FIN_BankStatement.class);
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getId()).thenReturn(accountId);
    when(bs.getAccount()).thenReturn(acc);
    when(line.getBankStatement()).thenReturn(bs);
    return line;
  }

  /** A line whose bank statement account id matches the target belongs to that account. */
  @Test
  public void testBelongsToAccountMatchingIdReturnsTrue() {
    assertTrue(ReconciliationSupport.belongsToAccount(lineWithAccount(ACC_ID), ACC_ID));
  }

  /** A line tied to a different account does not belong to the target account. */
  @Test
  public void testBelongsToAccountDifferentIdReturnsFalse() {
    assertFalse(ReconciliationSupport.belongsToAccount(lineWithAccount(OTHER_ACC), ACC_ID));
  }

  /** A line with no bank statement never belongs to any account. */
  @Test
  public void testBelongsToAccountNullStatementReturnsFalse() {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getBankStatement()).thenReturn(null);
    assertFalse(ReconciliationSupport.belongsToAccount(line, ACC_ID));
  }

  /** A bank statement with no account never belongs to any account. */
  @Test
  public void testBelongsToAccountNullAccountReturnsFalse() {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    FIN_BankStatement bs = mock(FIN_BankStatement.class);
    when(bs.getAccount()).thenReturn(null);
    when(line.getBankStatement()).thenReturn(bs);
    assertFalse(ReconciliationSupport.belongsToAccount(line, ACC_ID));
  }

  // ── signedAmount ─────────────────────────────────────────────────────────────

  private FIN_FinaccTransaction trxWith(BigDecimal deposit, BigDecimal payment) {
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getDepositAmount()).thenReturn(deposit);
    when(trx.getPaymentAmount()).thenReturn(payment);
    return trx;
  }

  /** A deposit-heavy transaction yields a positive signed amount (deposit - payment). */
  @Test
  public void testSignedAmountDepositMinusPayment() {
    BigDecimal signed =
        ReconciliationSupport.signedAmount(trxWith(new BigDecimal("100"), new BigDecimal("30")));
    assertEquals(0, new BigDecimal("70").compareTo(signed));
  }

  /** A null deposit is treated as zero, so a payment-only transaction is negative. */
  @Test
  public void testSignedAmountNullDepositIsNegativePayment() {
    BigDecimal signed = ReconciliationSupport.signedAmount(trxWith(null, new BigDecimal("40")));
    assertEquals(0, new BigDecimal("-40").compareTo(signed));
  }

  /** Both amounts null → signed amount is zero. */
  @Test
  public void testSignedAmountBothNullIsZero() {
    BigDecimal signed = ReconciliationSupport.signedAmount(trxWith(null, null));
    assertEquals(0, BigDecimal.ZERO.compareTo(signed));
  }

  // ---------------------------------------------------------------------------
  // signedReconciledAmount (ETP-4921) — the left panel's "Progreso" bar
  // ---------------------------------------------------------------------------

  private static void assertReconciled(String expected, String amount, String pending) {
    BigDecimal actual = ReconciliationHandlerSupport.signedReconciledAmount(
        new BigDecimal(amount), new BigDecimal(pending));
    assertEquals(amount + " - " + pending, 0, new BigDecimal(expected).compareTo(actual));
  }

  /**
   * THE BUG. `amount` is signed but `pendingAmount` is the unsigned |cr - dr| the line handler
   * stores, so the old `amount.subtract(pending)` gave -1.00 for a fully pending 0.50 withdrawal.
   * Anything non-zero makes ProgressCell draw a bar, and the resulting 200% clamped to 100 drew a
   * SOLID one — "fully reconciled" under a "Pendiente" badge. Values taken from the live rows of
   * the Santander account that surfaced it.
   */
  @Test
  public void testReconciledIsZeroForAFullyPendingWithdrawal() {
    assertReconciled("0", "-0.50", "0.50");
    assertReconciled("0", "-1.21", "1.21");
    assertReconciled("0", "-0.30", "0.30");
  }

  /** Deposits were correct only by coincidence — both signs happened to match. Still correct. */
  @Test
  public void testReconciledIsZeroForAFullyPendingDeposit() {
    assertReconciled("0", "10.00", "10.00");
    assertReconciled("0", "0.30", "0.30");
  }

  /** A matched line stores pending = 0, so the whole amount is reconciled, sign included. */
  @Test
  public void testReconciledIsTheWholeAmountWhenNothingIsPending() {
    assertReconciled("-0.50", "-0.50", "0");
    assertReconciled("10.00", "10.00", "0");
  }

  /** The case the bar exists for: a partial group keeps the sign of its amount. */
  @Test
  public void testReconciledIsThePartialPortionForAPartialGroup() {
    assertReconciled("53.24", "100", "46.76");
    assertReconciled("-53.24", "-100", "46.76");
  }

  /** pending > |amount| is a data anomaly; reporting "nothing reconciled" beats a flipped bar. */
  @Test
  public void testReconciledClampsAtZeroWhenPendingExceedsTheAmount() {
    assertReconciled("0", "-0.50", "5.00");
    assertReconciled("0", "0.50", "5.00");
  }

  /** A zero-amount line has nothing to reconcile either way. */
  @Test
  public void testReconciledIsZeroForAZeroAmountLine() {
    assertReconciled("0", "0", "0");
  }
}
