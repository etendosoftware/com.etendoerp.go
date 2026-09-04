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
import static com.etendoerp.go.schemaforge.ReconciliationSupport.readOperationIds;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.signedAmount;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
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
      BigDecimal tolerance, boolean writeoffDifference) {
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
      BigDecimal tolerance, String paymentMethodId, boolean writeoffDifference) throws Exception {
    FIN_PaymentMethod chosenMethod = resolveChosenMethod(paymentMethodId);

    BigDecimal lineAmount = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    boolean isReceipt = lineAmount.signum() >= 0;
    BigDecimal startingRemaining = lineAmount.abs();
    BigDecimal remaining = startingRemaining;

    InvoiceSettlementContext ctx = new InvoiceSettlementContext(account, line, isReceipt,
        chosenMethod, operationIds, tolerance, writeoffDifference);
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
    // Tenant guard: the id comes from the request body (ETP-4950).
    FIN_PaymentMethod method = TenantOwnership.loadOwned(FIN_PaymentMethod.class, paymentMethodId);
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
    // Both ids come from the request body and this method goes on to register a REAL payment
    // against them, so a foreign id must resolve to nothing rather than to another tenant's
    // invoice (ETP-4950).
    Invoice invoice = TenantOwnership.loadOwned(Invoice.class, invoiceId);
    FIN_PaymentSchedule schedule = TenantOwnership.loadOwned(FIN_PaymentSchedule.class, scheduleId);
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
            chosenMethod, ctx.writeoffDifference() && !fullSettlement));
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
    for (String opId : operationIds) {
      FIN_FinaccTransaction trx = transactionLoader.apply(opId);
      NeoResponse opError = validateOperation(trx, opId, accountId);
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
   * Per-operation guards: it must exist, belong to {@code accountId}, and be free — i.e. carry no
   * reconciliation at all. Returns the verbatim error response, or {@code null} when the operation
   * is valid.
   */
  private static NeoResponse validateOperation(FIN_FinaccTransaction trx, String opId,
      String accountId) {
    if (trx == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Operation not found: " + opId);
    }
    if (trx.getAccount() == null || !accountId.equals(trx.getAccount().getId())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Operation does not belong to the financial account: " + opId);
    }
    if (trx.getReconciliation() != null) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Operation is already reconciled: " + opId);
    }
    return null;
  }

  /**
   * Composes the standard Etendo reconciliation services for a 1:N manual match: creates a fresh
   * draft reconciliation, matches into it, and processes it. Never reimplements the matching logic.
   * Extracted verbatim from {@code ReconciliationHandler.compose} so that class stays under the
   * Sonar per-class method-count limit (java:S1448); every DAL/Classic seam still runs on the
   * caller's {@code handler} instance, so behavior — and test stubbing — is unchanged.
   */
  static NeoResponse compose(ReconciliationHandler handler, FIN_FinancialAccount account,
      FIN_BankStatementLine line, List<String> operationIds) throws Exception {
    FIN_Reconciliation rec = handler.addNewDraftReconciliation(account);
    handler.matchInto(line, operationIds, rec);
    OBError result = handler.processReconciliation(rec);
    if (result != null && "Error".equalsIgnoreCase(result.getType())) {
      handler.doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, result.getMessage());
    }

    JSONObject data = new JSONObject();
    data.put("reconciliationId", rec.getId());
    JSONArray lineIds = new JSONArray();
    lineIds.put(line.getId());
    data.put("lineIds", lineIds);
    data.put(ReconciliationHandler.KEY_UPDATED_BALANCE, nullSafe(rec.getEndingBalance()));
    return NeoResponse.createdWithData(data);
  }

  /**
   * Validates one {@code applySuggestions} group and, on success, appends a
   * {@link ReconciliationHandler.PreparedGroup} to {@code out} (the resolved line + final operation
   * ids, invoice/rule payments already created). Returns the verbatim error response on the first
   * failed guard, or {@code null} on success. Deliberately does NOT match or touch any
   * {@code FIN_Reconciliation} — that happens once the caller has a single shared document for the
   * whole batch.
   *
   * <p>Extracted verbatim from {@code ReconciliationHandler.prepareGroup} so that class stays under
   * the Sonar per-class method-count limit (java:S1448); every DAL seam still runs on the caller's
   * {@code handler} instance, so behavior — and test stubbing — is unchanged.
   */
  static NeoResponse prepareGroup(ReconciliationHandler handler, FIN_FinancialAccount account,
      JSONObject groupEntry, List<ReconciliationHandler.PreparedGroup> out) throws Exception {
    String statementLineId = groupEntry.optString(ReconciliationHandler.KEY_STATEMENT_LINE_ID, null);
    if (StringUtils.isBlank(statementLineId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "statementLineId is required");
    }

    FIN_BankStatementLine line = handler.loadLine(statementLineId);
    // Ownership: the line must belong to the account this batch is reconciling. Every other entry
    // point already did this — reconcileGroup, reactivate, reactivateSelected and
    // reconcileDifference. applySuggestions was the one path that skipped it, so a line from
    // another account, another tenant's included, could be matched in against transactions of this
    // one. See ETP-4950.
    if (line == null || !ReconciliationSupport.belongsToAccount(line, account.getId())) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          ReconciliationHandler.MSG_STATEMENT_LINE_NOT_FOUND + statementLineId);
    }
    if (line.getFinancialAccountTransaction() != null) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Statement line is already reconciled: " + statementLineId);
    }

    List<String> operationIds = readOperationIds(groupEntry);

    // When a rule group requires creating a new transaction, do that first.
    JSONObject createPaymentSpec = groupEntry.optJSONObject("createPayment");
    if (createPaymentSpec != null && StringUtils.isNotBlank(createPaymentSpec.optString("glItemId", null))) {
      String newTxnId = handler.createTransactionForRule(account, line, createPaymentSpec);
      if (StringUtils.isNotBlank(newTxnId)) {
        operationIds = new ArrayList<>(operationIds);
        operationIds.add(newTxnId);
        // Increment the rule's matchCount.
        String ruleId = createPaymentSpec.optString("ruleId", null);
        if (StringUtils.isNotBlank(ruleId)) {
          AutoMatchSupport.incrementMatchCount(ruleId);
        }
      }
    }

    if (operationIds.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "At least one operation is required for line: " + statementLineId);
    }

    // Operations (including any just-created rule transaction) may match part of the line but must
    // not EXCEED it — the same over-reconciliation guard the manual reconcileGroup path applies.
    NeoResponse opError = validateOperations(
        operationIds, account.getId(), line, handler::loadTransaction,
        ReconciliationHandler.TOLERANCE);
    if (opError != null) {
      return opError;
    }

    // ETP-4965: same inline difference funding the manual path applies, so an automatch group whose
    // near-match leaves a within-tolerance gap reconciles fully instead of splitting. A group whose
    // account has no GL Item Difference is rejected HERE, before anything is matched into the shared
    // reconciliation — its error travels back in applySuggestions' results[] and the suggestion
    // modal reports it as a failed group. A mass run cannot ask for a concept line by line, so it
    // must never post one blindly. `groupEntry` doubles as the body: an automatch group carries no
    // glItemId, so the account's own concept is what gets used.
    NeoResponse diffError = ReconciliationDifferenceSupport.applyInlineDifference(
        handler, account, line, operationIds, groupEntry, false);
    if (diffError != null) {
      return diffError;
    }

    out.add(new ReconciliationHandler.PreparedGroup(line, operationIds));
    return null;
  }
}
