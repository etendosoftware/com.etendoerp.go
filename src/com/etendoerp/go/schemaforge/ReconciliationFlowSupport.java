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

import static com.etendoerp.go.schemaforge.ReconciliationSupport.nullSafe;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.signedAmount;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

final class ReconciliationFlowSupport {

  private static final String FIELD_INVOICE_ID = "invoiceId";

  private ReconciliationFlowSupport() {
  }

  /**
   * The inputs of {@link #settleInvoice} that stay constant across every invoice spec of a single
   * {@link #createInvoicePayments} call, grouped into one value (Sonar S107): the target
   * {@code account} and statement {@code line}, the resolved direction ({@code isReceipt}), the
   * user-chosen {@code chosenMethod} (may be {@code null}), the growing {@code operationIds}
   * accumulator and the amount {@code tolerance}. The per-invoice varying arguments ({@code spec}
   * and {@code remaining}) stay as explicit parameters.
   */
  private record InvoiceSettlementContext(FIN_FinancialAccount account, FIN_BankStatementLine line,
      boolean isReceipt, FIN_PaymentMethod chosenMethod, List<String> operationIds,
      BigDecimal tolerance) {
  }

  /**
   * Allocates the statement line across one or more selected invoices, possibly a mix of
   * currencies (ETP-4502 iteration 2): each invoice's outstanding is converted to the account
   * currency via its own exchange rate (see {@link PaymentCurrencyConverter#resolveInvoiceRate};
   * {@link BigDecimal#ONE} when the currencies already match, so the same-currency behavior is
   * unchanged) before being allocated against the remaining line amount — the same greedy,
   * possibly-partial allocation the same-currency flow always used, generalized to convert each
   * invoice's share. {@code paymentMethodId}, when present, is the single payment method the user
   * picked in the reconciliation modal, applied to every invoice payment this call creates (an
   * already-existing transaction selected via {@code operationIds} keeps its own payment/method
   * untouched — see {@link #validateOperations}).
   *
   * <p>The selected invoices may settle LESS than the line (e.g. a 100 line matched to a single
   * 60 invoice): the invoice-derived transaction id joins {@code operationIds} same as any
   * pre-existing one, and the caller's {@link #validateOperations} + Core's own
   * {@code matchBankStatementLine}/{@code splitBankStatementLine} — already relied on for the
   * existing-transaction path — split the line into a reconciled portion and a new pending
   * remainder, exactly as they already do when the user picks an existing transaction smaller
   * than the line. Over-covering the line is impossible by construction: each invoice only ever
   * absorbs {@code remaining.min(outstandingBase)}, so {@code remaining} can never go negative.
   *
   * <p>The one case still rejected: none of the selected invoices settled anything at all (e.g.
   * every one of them already has zero outstanding — a stale/already-paid selection). That is
   * not a legitimate partial match, just a selection that accomplishes nothing, so it is still
   * reported as the same "do not cover" 400 rather than silently succeeding as a no-op.
   */
  static NeoResponse createInvoicePayments(FIN_FinancialAccount account,
      FIN_BankStatementLine line, JSONArray invoiceSpecs, List<String> operationIds,
      BigDecimal tolerance, String paymentMethodId) throws Exception {
    FIN_PaymentMethod chosenMethod = resolveChosenMethod(paymentMethodId);

    BigDecimal lineAmount = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    boolean isReceipt = lineAmount.signum() >= 0;
    BigDecimal startingRemaining = lineAmount.abs();
    BigDecimal remaining = startingRemaining;

    InvoiceSettlementContext ctx = new InvoiceSettlementContext(account, line, isReceipt,
        chosenMethod, operationIds, tolerance);
    for (int i = 0; i < invoiceSpecs.length() && remaining.compareTo(tolerance) > 0; i++) {
      SettlementOutcome outcome = settleInvoice(ctx, invoiceSpecs.getJSONObject(i), remaining);
      if (outcome.error() != null) {
        return outcome.error();
      }
      remaining = outcome.remaining();
    }
    // Only flag "nothing consumed" when there was actually something to consume — a genuinely
    // zero-amount line has nothing to allocate in the first place and should succeed as a no-op
    // (matches the same-currency zero-line contrast case below), not be reported as a failed
    // selection.
    if (startingRemaining.compareTo(tolerance) > 0
        && remaining.compareTo(startingRemaining) == 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "The selected invoices do not cover the statement line amount. Remaining: "
              + remaining.toPlainString());
    }
    return null;
  }

  /** The method named by {@code paymentMethodId}, or {@code null} when none was chosen. */
  private static FIN_PaymentMethod resolveChosenMethod(String paymentMethodId) {
    if (StringUtils.isBlank(paymentMethodId)) {
      return null;
    }
    FIN_PaymentMethod method = OBDal.getInstance().get(FIN_PaymentMethod.class, paymentMethodId);
    if (method == null) {
      throw new OBException("Payment method not found: " + paymentMethodId);
    }
    return method;
  }

  /**
   * Outcome of settling one invoice spec against the remaining line amount: either the updated
   * {@code remaining} (with {@code error} null), or a {@code error} response to return verbatim
   * (with {@code remaining} unchanged). Exactly one meaning applies at a time.
   */
  private record SettlementOutcome(BigDecimal remaining, NeoResponse error) {
  }

  /**
   * Settles one invoice spec against {@code remaining} (in the account currency): converts its
   * outstanding via its own exchange rate (see
   * {@link PaymentCurrencyConverter#resolveInvoiceRate}; {@link BigDecimal#ONE} when the
   * currencies already match), allocates whatever of {@code remaining} it can absorb — fully or
   * partially, same greedy allocation the same-currency flow always used — and registers the
   * payment. Extracted from {@link #createInvoicePayments} to keep its cognitive complexity under
   * the Sonar limit (S3776).
   */
  private static SettlementOutcome settleInvoice(InvoiceSettlementContext ctx, JSONObject spec,
      BigDecimal remaining) throws Exception {
    FIN_FinancialAccount account = ctx.account();
    FIN_BankStatementLine line = ctx.line();
    boolean isReceipt = ctx.isReceipt();
    FIN_PaymentMethod chosenMethod = ctx.chosenMethod();
    List<String> operationIds = ctx.operationIds();
    BigDecimal tolerance = ctx.tolerance();
    String invoiceId = spec.optString(FIELD_INVOICE_ID, null);
    String scheduleId = spec.optString("scheduleId", null);
    if (StringUtils.isBlank(invoiceId) || StringUtils.isBlank(scheduleId)) {
      return new SettlementOutcome(remaining, NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "invoiceId and scheduleId are required for each invoice"));
    }
    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    FIN_PaymentSchedule schedule = OBDal.getInstance().get(FIN_PaymentSchedule.class, scheduleId);
    if (invoice == null || schedule == null) {
      return new SettlementOutcome(remaining, NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Invoice or payment schedule not found: " + invoiceId));
    }

    BigDecimal outstanding = nullSafe(schedule.getOutstandingAmount()).abs();
    BigDecimal rate = PaymentCurrencyConverter.resolveInvoiceRate(invoice, account);
    BigDecimal outstandingBase = PaymentCurrencyConverter.convertedAmount(outstanding, rate, account);
    BigDecimal allocateBase = remaining.min(outstandingBase);
    if (allocateBase.compareTo(tolerance) <= 0) {
      return new SettlementOutcome(remaining, null);
    }

    boolean fullSettlement = allocateBase.compareTo(outstandingBase) >= 0;
    BigDecimal paymentAmount = fullSettlement
        ? outstanding
        : PaymentCurrencyConverter.invoiceAmountFor(allocateBase, rate, invoice.getCurrency());
    BigDecimal txnAmount = PaymentCurrencyConverter.convertedAmount(paymentAmount, rate, account);

    FIN_Payment payment = ReconciliationPaymentService.registerReconciliationPayment(
        new ReconciliationPaymentService.ReconciliationPaymentRequest(invoice, schedule,
            paymentAmount, txnAmount, rate, line.getTransactionDate(), account, isReceipt,
            chosenMethod));
    List<FIN_FinaccTransaction> txns = payment.getFINFinaccTransactionList();
    if (txns.isEmpty()) {
      return new SettlementOutcome(remaining,
          NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "Payment did not produce a transaction: " + payment.getId()));
    }
    ReactivationSupport.markAutoCreated(txns.get(0));
    operationIds.add(txns.get(0).getId());
    return new SettlementOutcome(remaining.subtract(txnAmount), null);
  }

  static NeoResponse validateOperations(List<String> operationIds, String accountId,
      FIN_BankStatementLine line, Function<String, FIN_FinaccTransaction> transactionLoader,
      BigDecimal tolerance) {
    BigDecimal opSum = BigDecimal.ZERO;
    String ownDraftId = ownDraftIdOf(line);
    for (String opId : operationIds) {
      FIN_FinaccTransaction trx = transactionLoader.apply(opId);
      NeoResponse opError = validateOperation(trx, opId, accountId, ownDraftId);
      if (opError != null) {
        return opError;
      }
      opSum = opSum.add(signedAmount(trx));
    }
    BigDecimal lineAmount = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    int lineSign = lineAmount.signum();
    boolean sameDirection = opSum.signum() == 0 || lineSign == 0 || opSum.signum() == lineSign;
    boolean withinLine = opSum.abs().compareTo(lineAmount.abs().add(tolerance)) <= 0;
    if (!sameDirection || !withinLine) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "The selected operations (" + opSum.toPlainString()
              + ") exceed the statement line amount (" + lineAmount.toPlainString()
              + "). Operations can match part of the line but not exceed it.");
    }
    return null;
  }

  /**
   * The id of the line's OWN unprocessed ("Reactivar"-ed) reconciliation, or {@code null} when the
   * line is unmatched or its reconciliation is already processed. Those transactions legitimately
   * still carry that reconciliation — re-confirming them is the whole point of that action.
   */
  private static String ownDraftIdOf(FIN_BankStatementLine line) {
    FIN_Reconciliation ownDraft = line != null && line.getFinancialAccountTransaction() != null
        ? line.getFinancialAccountTransaction().getReconciliation()
        : null;
    return ownDraft != null && !ownDraft.isProcessed() ? ownDraft.getId() : null;
  }

  /**
   * Per-operation guards: it must exist, belong to {@code accountId}, and be free — i.e. carry no
   * reconciliation other than the line's own draft ({@code ownDraftId}). Returns the verbatim error
   * response, or {@code null} when the operation is valid.
   */
  private static NeoResponse validateOperation(FIN_FinaccTransaction trx, String opId,
      String accountId, String ownDraftId) {
    if (trx == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Operation not found: " + opId);
    }
    if (trx.getAccount() == null || !accountId.equals(trx.getAccount().getId())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Operation does not belong to the financial account: " + opId);
    }
    // Compared from ownDraftId outwards so a reconciliation with a null id (defensive: never in a
    // real DB, but reachable via mocks) still rejects instead of throwing.
    FIN_Reconciliation trxRec = trx.getReconciliation();
    if (trxRec != null && (ownDraftId == null || !ownDraftId.equals(trxRec.getId()))) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Operation is already reconciled: " + opId);
    }
    return null;
  }
}
