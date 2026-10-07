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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.ConversionRateDoc;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;

/**
 * DB-free unit tests for {@link ReconciliationFlowSupport#createInvoicePayments}, covering the
 * ETP-4502 iteration-2 behavior: a statement line can now be allocated across MULTIPLE invoices of
 * possibly different currencies (the old single-foreign-invoice restriction is gone — every invoice
 * is settled through the same greedy, possibly-partial allocation, with its own currency converted
 * via {@link PaymentCurrencyConverter#resolveInvoiceRate}), and an optional {@code paymentMethodId}
 * is resolved once up front via {@link FIN_PaymentMethod}.
 *
 * <p>Most tests here exercise the validation/rejection paths — those that return (or throw) before
 * any DAL write — reachable by mocking {@link OBDal#get}/{@code createCriteria} alone. The one
 * exception is {@link #singleInvoice_partialCoverage_nowSucceedsAndLeavesPendingRemainder()}, which
 * additionally mocks the static {@code ReconciliationPaymentService.registerReconciliationPayment}
 * (a simple static-method-only final class, same shape as {@code OBDal}) so the ETP-4502
 * iteration-2 "partial coverage now succeeds" happy path can be asserted without a full
 * OBBaseTest/integration harness (draft-payment creation, {@code processOrThrow}, and transaction
 * persistence themselves remain uncovered here, same limitation the original test documented).
 *
 * <p>Account currency is {@code USD}; a foreign invoice is {@code EUR}/{@code GBP}; a same-currency
 * invoice is {@code USD}.
 *
 * <p>Edge cases covered ({@code >= 3} required):
 * <ul>
 *   <li>blank scheduleId → 400 "scheduleId"</li>
 *   <li>invoice/schedule not found → 404</li>
 *   <li>a zero-outstanding invoice is silently skipped (no dedicated 400); the overall line still
 *       ends up unresolved → 400 "do not cover" (NOT an "outstanding" specific message anymore)</li>
 *   <li>a zero-amount statement line short-circuits to {@code null} immediately, even with a foreign
 *       invoice queued — the invoice is never even looked up (no per-invoice zero-line 400 anymore)
 *       </li>
 *   <li>multiple invoices of different currencies under one line no longer hit the old
 *       "single invoice" 400 — both are evaluated and the final rejection (when it happens) is the
 *       generic coverage message</li>
 *   <li>an unresolvable {@code paymentMethodId} throws {@link OBException} (bubbles up, not a 400)</li>
 *   <li>a blank/null {@code paymentMethodId} skips resolution — legacy zero-line behavior unchanged
 *       </li>
 *   <li>same-currency zero line → legacy path, returns {@code null}</li>
 *   <li>{@code loadInstallment} (ETP-5657): a schedule of ANOTHER invoice → 404 (also on the
 *       default path, before any payment); a schedule with no invoice, or of the same invoice,
 *       resolves</li>
 *   <li>{@code collectTransaction}: a payment without a transaction → 500 on the default path</li>
 * </ul>
 *
 * @covers com.etendoerp.go.schemaforge.ReconciliationFlowSupport
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciliationFlowSupportForeignInvoiceTest {

  private static final String ACCOUNT_CURRENCY = "USD";
  private static final String FOREIGN_CURRENCY = "EUR";
  private static final String OTHER_FOREIGN_CURRENCY = "GBP";
  private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  @Mock
  private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<ReconciliationPaymentService> reconciliationPaymentServiceMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    reconciliationPaymentServiceMock = mockStatic(ReconciliationPaymentService.class);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    reconciliationPaymentServiceMock.close();
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static Currency currency(String id) {
    Currency c = mock(Currency.class);
    when(c.getId()).thenReturn(id);
    return c;
  }

  private FIN_FinancialAccount account(String currencyId) {
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    // The Currency mock must be fully built (its own when/thenReturn completed) BEFORE opening
    // acc.getCurrency()'s stub — nesting an unrelated when(...) inside a pending thenReturn(...)
    // argument throws Mockito's UnfinishedStubbingException.
    Currency cur = currencyId == null ? null : currency(currencyId);
    when(acc.getCurrency()).thenReturn(cur);
    return acc;
  }

  private Invoice invoice(String id, String currencyId) {
    Invoice inv = mock(Invoice.class);
    Currency cur = currency(currencyId);
    when(inv.getId()).thenReturn(id);
    when(inv.getCurrency()).thenReturn(cur);
    when(inv.getDocumentNo()).thenReturn(id);
    when(obDal.get(eq(Invoice.class), eq(id))).thenReturn(inv);
    return inv;
  }

  private FIN_PaymentSchedule schedule(String id, String outstanding) {
    FIN_PaymentSchedule sch = mock(FIN_PaymentSchedule.class);
    when(sch.getOutstandingAmount())
        .thenReturn(outstanding == null ? null : new BigDecimal(outstanding));
    when(obDal.get(eq(FIN_PaymentSchedule.class), eq(id))).thenReturn(sch);
    return sch;
  }

  /** Statement line with the given credit / debit amounts (line amount = cr - dr). */
  private static FIN_BankStatementLine line(String cr, String dr) {
    FIN_BankStatementLine l = mock(FIN_BankStatementLine.class);
    when(l.getCramount()).thenReturn(cr == null ? null : new BigDecimal(cr));
    when(l.getDramount()).thenReturn(dr == null ? null : new BigDecimal(dr));
    return l;
  }

  private static JSONObject spec(String invoiceId, String scheduleId) throws JSONException {
    JSONObject o = new JSONObject();
    if (invoiceId != null) {
      o.put("invoiceId", invoiceId);
    }
    if (scheduleId != null) {
      o.put("scheduleId", scheduleId);
    }
    return o;
  }

  private static JSONArray specs(JSONObject... items) {
    JSONArray arr = new JSONArray();
    for (JSONObject o : items) {
      arr.put(o);
    }
    return arr;
  }

  private static String message(NeoResponse resp) throws JSONException {
    return resp.getBody().getJSONObject("error").getString("message");
  }

  /**
   * Stubs {@code OBDal.getInstance().createCriteria(ConversionRateDoc.class).list()} so any foreign
   * invoice's {@link PaymentCurrencyConverter#resolveInvoiceRate} resolves via the document-rate
   * path (no {@code FinancialUtils} mocking needed) instead of NPE-ing on an unstubbed criteria.
   */
  @SuppressWarnings("unchecked")
  private void stubDocumentRate(String rate) {
    OBCriteria<ConversionRateDoc> crit = mock(OBCriteria.class);
    ConversionRateDoc doc = mock(ConversionRateDoc.class);
    when(doc.getRate()).thenReturn(new BigDecimal(rate));
    when(crit.list()).thenReturn(List.of(doc));
    when(obDal.createCriteria(ConversionRateDoc.class)).thenReturn(crit);
  }

  // ── tests ────────────────────────────────────────────────────────────────

  /**
   * A single invoice with a blank scheduleId is rejected with 400 (field validation runs before any
   * invoice/schedule lookup, so no currency handling is involved).
   */
  @Test
  void singleInvoice_missingScheduleId_returns400() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", FOREIGN_CURRENCY);
    FIN_BankStatementLine bsl = line("100", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", null));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, null, false);

    assertEquals(400, resp.getHttpStatus());
    assertTrue(message(resp).contains("scheduleId"), message(resp));
  }

  /**
   * When the payment schedule cannot be loaded, the path returns 404 (also before any currency
   * conversion is attempted).
   */
  @Test
  void singleInvoice_scheduleNotFound_returns404() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", FOREIGN_CURRENCY);
    // no schedule stubbed for "sch-missing" -> OBDal.get returns null
    FIN_BankStatementLine bsl = line("100", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-missing"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, null, false);

    assertEquals(404, resp.getHttpStatus());
  }

  /**
   * A same-currency invoice whose schedule has zero outstanding is silently skipped inside
   * {@code settleInvoice} (the {@code allocateBase <= tolerance} early return) — there is no
   * dedicated "outstanding" 400 anymore. Since the line amount is not otherwise covered, the loop
   * still ends with the generic "do not cover" rejection.
   */
  @Test
  void singleInvoice_zeroOutstanding_skippedThenRejectedAsInsufficientCoverage() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", ACCOUNT_CURRENCY);
    schedule("sch-1", "0");
    FIN_BankStatementLine bsl = line("100", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, null, false);

    assertEquals(400, resp.getHttpStatus());
    assertTrue(message(resp).toLowerCase().contains("do not cover"), message(resp));
    assertFalse(message(resp).toLowerCase().contains("outstanding"), message(resp));
    assertTrue(operationIds.isEmpty());
  }

  /**
   * A zero-amount statement line now short-circuits to {@code null} immediately (the for-loop
   * condition {@code remaining > tolerance} is false from the start), even when a foreign-currency
   * invoice is queued — the invoice is never even looked up. This replaces the old per-invoice
   * "statement line is zero" 400.
   */
  @Test
  void zeroLineAmount_withForeignInvoiceQueued_shortCircuitsToNullWithoutLookingUpTheInvoice() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    // Deliberately do NOT stub "inv-1" via the invoice() helper's OBDal.get — if the code looked it
    // up, obDal.get would return null and the invoice/schedule-not-found 404 branch would fire
    // instead of null, so this also proves the invoice lookup never happens.
    FIN_BankStatementLine bsl = line("0", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, null, false);

    assertNull(resp, "a zero-amount line should short-circuit before any invoice lookup");
    assertTrue(operationIds.isEmpty());
  }

  /**
   * Two invoices of DIFFERENT currencies under a single line are now both evaluated in the loop
   * (the old "single invoice" restriction is gone). Both have zero outstanding, so both are
   * skipped, and the final rejection is the generic coverage message — critically NOT the removed
   * "single invoice" error.
   */
  @Test
  void multipleInvoicesOfDifferentCurrencies_bothEvaluated_noSingleInvoiceRestriction() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", FOREIGN_CURRENCY);
    invoice("inv-2", OTHER_FOREIGN_CURRENCY);
    schedule("sch-1", "0");
    schedule("sch-2", "0");
    stubDocumentRate("1.1"); // lets resolveInvoiceRate resolve for both foreign invoices
    FIN_BankStatementLine bsl = line("100", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"), spec("inv-2", "sch-2"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, null, false);

    assertEquals(400, resp.getHttpStatus());
    assertTrue(message(resp).toLowerCase().contains("do not cover"), message(resp));
    assertFalse(message(resp).toLowerCase().contains("single invoice"), message(resp));
    assertTrue(operationIds.isEmpty());
  }

  /**
   * Contrast case: a same-currency invoice under a zero-amount line short-circuits to {@code null}
   * (success/no-op), exactly like the foreign case above — currency no longer changes this behavior.
   */
  @Test
  void sameCurrencyZeroLine_takesLegacyPath_returnsNull() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", ACCOUNT_CURRENCY);
    FIN_BankStatementLine bsl = line("0", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, null, false);

    assertNull(resp, "same-currency zero line should not hit any rejection path");
    assertTrue(operationIds.isEmpty());
  }

  /**
   * An account with no declared currency keeps legacy behavior: with a zero-amount line, the result
   * is still {@code null} regardless of the queued invoice's currency.
   */
  @Test
  void accountWithoutCurrency_zeroLine_returnsNull() throws Exception {
    FIN_FinancialAccount acc = account(null);
    FIN_BankStatementLine bsl = line("0", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, null, false);

    assertNull(resp);
  }

  // ── paymentMethodId resolution ───────────────────────────────────────────

  /**
   * A {@code paymentMethodId} that does not resolve to an existing {@link FIN_PaymentMethod} makes
   * {@code resolveChosenMethod} throw {@link OBException} — this happens up front, before the
   * invoice loop, so it bubbles up as a thrown exception rather than a returned {@link NeoResponse}.
   */
  @Test
  void paymentMethodId_doesNotResolve_throwsOBException() throws JSONException {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    FIN_BankStatementLine bsl = line("100", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();
    // obDal.get(FIN_PaymentMethod.class, "pm-missing") is unstubbed -> returns null by default.

    assertThrows(OBException.class, () -> ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, "pm-missing", false));
  }

  /**
   * A blank {@code paymentMethodId} skips resolution entirely — legacy (pre-ETP-4502-iteration-2)
   * behavior is unchanged: a zero-amount line still short-circuits to {@code null}.
   */
  @Test
  void paymentMethodId_blank_skipsResolution_legacyBehaviorUnchanged() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    FIN_BankStatementLine bsl = line("0", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, "", false);

    assertNull(resp);
  }

  /**
   * A {@code paymentMethodId} that DOES resolve does not itself change the outcome of an otherwise
   * zero-amount line — it is only carried forward into payment creation on the (unmockable) happy
   * path. This proves resolution succeeding doesn't throw and doesn't short-circuit differently.
   */
  @Test
  void paymentMethodId_resolves_zeroLineStillShortCircuitsToNull() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(obDal.get(eq(FIN_PaymentMethod.class), eq("pm-1"))).thenReturn(method);
    FIN_BankStatementLine bsl = line("0", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, "pm-1", false);

    assertNull(resp);
  }

  // ── ETP-4502 iteration 2: partial coverage now succeeds ─────────────────

  /**
   * A single same-currency invoice whose outstanding (60) is LESS than the statement line (100) no
   * longer hits the "do not cover" 400 (the pre-relaxation behavior): {@code settleInvoice} fully
   * settles the invoice (60 &lt;= remaining) and the loop ends with {@code remaining} (40) different
   * from {@code startingRemaining} (100), so the method returns {@code null} (success) and leaves the
   * new transaction id in {@code operationIds} for the caller's {@code validateOperations} +
   * Core's own {@code matchBankStatementLine}/{@code splitBankStatementLine} to split the line into a
   * reconciled 60 portion and a new pending 40 remainder — exactly as documented on
   * {@link ReconciliationFlowSupport#createInvoicePayments}.
   *
   * <p>{@code ReconciliationPaymentService.registerReconciliationPayment} is mocked (statically,
   * mirroring the {@code OBDal} mock above) since it internally touches
   * {@code AdvPaymentMngtDao}/{@code FIN_AddPayment}/Hibernate and is not exercisable at the unit
   * level; it is stubbed to return a payment with exactly one finacc transaction, matching what a
   * real settlement produces.
   */
  @Test
  void singleInvoice_partialCoverage_nowSucceedsAndLeavesPendingRemainder() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", ACCOUNT_CURRENCY);
    schedule("sch-1", "60");
    FIN_BankStatementLine bsl = line("100", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    FIN_FinaccTransaction txn = mock(FIN_FinaccTransaction.class);
    when(txn.getId()).thenReturn("txn-1");
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getFINFinaccTransactionList()).thenReturn(List.of(txn));
    reconciliationPaymentServiceMock
        .when(() -> ReconciliationPaymentService.registerReconciliationPayment(any()))
        .thenReturn(payment);

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE, null, false);

    assertNull(resp, "an invoice settling less than the line should now succeed, not 400");
    assertEquals(1, operationIds.size());
    assertEquals("txn-1", operationIds.get(0));
  }

  // ── loadInstallment: the schedule must belong to the named invoice (ETP-5657) ──

  /** A schedule of {@code owner} (or of no invoice when {@code owner} is null). */
  private FIN_PaymentSchedule scheduleOf(String id, String outstanding, Invoice owner) {
    FIN_PaymentSchedule sch = schedule(id, outstanding);
    when(sch.getInvoice()).thenReturn(owner);
    return sch;
  }

  @Test
  void loadInstallment_scheduleOfAnotherInvoice_returns404NamingTheRequestedInvoice()
      throws Exception {
    invoice("inv-1", FOREIGN_CURRENCY);
    Invoice other = invoice("inv-2", FOREIGN_CURRENCY);
    scheduleOf("sch-2", "987.65", other);

    ReconciliationFlowSupport.InvoiceInstallment installment =
        ReconciliationFlowSupport.loadInstallment(spec("inv-1", "sch-2"));

    assertEquals(404, installment.error().getHttpStatus());
    assertEquals("Invoice or payment schedule not found: inv-1", message(installment.error()));
    assertFalse(String.valueOf(installment.error().getBody()).contains("987.65"));
    assertNull(installment.invoice());
    assertNull(installment.schedule());
  }

  @Test
  void loadInstallment_scheduleWithoutInvoice_stillResolves() throws Exception {
    Invoice inv = invoice("inv-1", FOREIGN_CURRENCY);
    FIN_PaymentSchedule sch = scheduleOf("sch-1", "40.91", null);

    ReconciliationFlowSupport.InvoiceInstallment installment =
        ReconciliationFlowSupport.loadInstallment(spec("inv-1", "sch-1"));

    assertNull(installment.error());
    assertSame(inv, installment.invoice());
    assertSame(sch, installment.schedule());
  }

  @Test
  void loadInstallment_scheduleOfTheSameInvoice_resolves() throws Exception {
    Invoice inv = invoice("inv-1", FOREIGN_CURRENCY);
    FIN_PaymentSchedule sch = scheduleOf("sch-1", "40.91", inv);

    ReconciliationFlowSupport.InvoiceInstallment installment =
        ReconciliationFlowSupport.loadInstallment(spec("inv-1", "sch-1"));

    assertNull(installment.error());
    assertSame(inv, installment.invoice());
    assertSame(sch, installment.schedule());
  }

  @Test
  void loadInstallment_blankInvoiceId_returns400() throws Exception {
    ReconciliationFlowSupport.InvoiceInstallment installment =
        ReconciliationFlowSupport.loadInstallment(spec(null, "sch-1"));

    assertEquals(400, installment.error().getHttpStatus());
    assertEquals("invoiceId and scheduleId are required for each invoice",
        message(installment.error()));
  }

  /**
   * The schedule-to-invoice check also guards the default (greedy) path: a schedule of another
   * invoice is refused before any payment is registered, instead of paying one invoice while
   * settling the instalment of a different one.
   */
  @Test
  void defaultPath_scheduleOfAnotherInvoice_returns404AndPaysNothing() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", ACCOUNT_CURRENCY);
    Invoice other = invoice("inv-2", ACCOUNT_CURRENCY);
    scheduleOf("sch-2", "60", other);
    FIN_BankStatementLine bsl = line("100", "0");
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, specs(spec("inv-1", "sch-2")), operationIds, TOLERANCE, null, false);

    assertEquals(404, resp.getHttpStatus());
    assertEquals("Invoice or payment schedule not found: inv-1", message(resp));
    assertTrue(operationIds.isEmpty());
    reconciliationPaymentServiceMock.verify(
        () -> ReconciliationPaymentService.registerReconciliationPayment(any()), never());
  }

  @Test
  void defaultPath_paymentWithoutTransaction_returns500() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    Invoice inv = invoice("inv-1", ACCOUNT_CURRENCY);
    scheduleOf("sch-1", "60", inv);
    FIN_BankStatementLine bsl = line("100", "0");
    List<String> operationIds = new ArrayList<>();
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn("pay-1");
    when(payment.getFINFinaccTransactionList()).thenReturn(List.of());
    reconciliationPaymentServiceMock
        .when(() -> ReconciliationPaymentService.registerReconciliationPayment(any()))
        .thenReturn(payment);

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, specs(spec("inv-1", "sch-1")), operationIds, TOLERANCE, null, false);

    assertEquals(500, resp.getHttpStatus());
    assertEquals("Payment did not produce a transaction: pay-1", message(resp));
    assertTrue(operationIds.isEmpty());
  }
}
