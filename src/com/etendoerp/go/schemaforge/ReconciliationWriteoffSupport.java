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

import java.math.BigDecimal;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;

/**
 * Write-off-limit validation for the {@code reconcileGroup} invoice-payment path (ETP-4797).
 * Extracted out of {@link ReconciliationHandler} (Sonar S1448 — too many methods) rather than
 * added there.
 */
final class ReconciliationWriteoffSupport {

  private ReconciliationWriteoffSupport() {
  }

  /**
   * The invoice leg of {@code reconcileGroup}: pays each selected unpaid invoice (creating the
   * payment and auto-creating its transaction) and appends the new transaction ids to
   * {@code operationIds}, so the standard reconcile matches them to the line. A no-op returning
   * {@code null} when no invoice is selected. {@code invoiceSpecs} is the caller's already-parsed
   * {@code invoices} array (parsed once, in {@code reconcileGroup}); the remaining options are read
   * from {@code body}.
   *
   * <p>{@code paymentMethodId} is the single method chosen in the reconciliation modal, applied to
   * every invoice payment created here — an already-existing transaction (operationIds) keeps its
   * own. {@code writeoffDifference} (ETP-4797) is opt-in, off by default: it writes off the
   * shortfall when the line settles the invoice for less than its outstanding amount, so the
   * invoice is fully paid instead of keeping a residual balance; the UI only offers it for a single
   * selected invoice.
   *
   * <p>Moved here from {@code reconcileGroup} (ETP-5472) to keep that method under the Sonar
   * cognitive-complexity limit (java:S3776) once it gained the up-front operation-id check.
   *
   * <p><b>Explicit conversion (ETP-5657).</b> When the body carries any of {@code actualPayment},
   * {@code conversionRate} or {@code convertedAmount}, the invoices are paid by
   * {@link ReconciliationConversionSupport} instead (the user states what the bank moved, Classic's
   * Add Payment parity) and {@link #payInvoices} is not called. Without them, nothing changes.
   * Those fields only describe the invoice leg: sent with no invoice they are refused with
   * {@link ReconciliationConversionSupport#MSG_NOT_COMBINABLE} (400) rather than silently ignored —
   * an agent sending {@code operationIds + convertedAmount} would otherwise get a 201 that booked
   * nothing of what it asked for.
   *
   * @return {@code null} on success or when there is nothing to pay, else the error to return
   */
  static NeoResponse payInvoicesFromBody(FIN_FinancialAccount account, FIN_BankStatementLine line,
      JSONArray invoiceSpecs, JSONObject body, List<String> operationIds, BigDecimal tolerance)
      throws Exception {
    ReconciliationConversionSupport.ExplicitConversion conversion =
        ReconciliationConversionSupport.parse(body);
    if (invoiceSpecs == null || invoiceSpecs.length() == 0) {
      return conversion == null ? null
          : NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              ReconciliationConversionSupport.MSG_NOT_COMBINABLE);
    }
    String paymentMethodId = body.optString("paymentMethodId", null);
    boolean writeoffDifference = body.optBoolean("writeoffDifference", false);
    if (conversion != null) {
      return ReconciliationConversionSupport.payInvoices(
          new ReconciliationConversionSupport.ConversionPaymentContext(account, line, invoiceSpecs,
              operationIds, paymentMethodId, writeoffDifference),
          conversion);
    }
    return payInvoices(account, line, invoiceSpecs, operationIds, tolerance, paymentMethodId,
        writeoffDifference);
  }

  /**
   * Validates the write-off limit and, if acceptable, delegates to
   * {@link ReconciliationFlowSupport#createInvoicePayments}. Returns {@code null} on success, a
   * {@link NeoResponse} error otherwise — same null-means-proceed contract as its callee, so
   * {@code reconcileGroup} only needs one nested check instead of two (Sonar S3776).
   */
  static NeoResponse payInvoices(FIN_FinancialAccount account, FIN_BankStatementLine line,
      JSONArray invoiceSpecs, List<String> operationIds, BigDecimal tolerance,
      String paymentMethodId, boolean writeoffDifference) throws Exception {
    NeoResponse limitError = assertWithinWriteoffLimit(account, writeoffDifference, line,
        invoiceSpecs);
    if (limitError != null) {
      return limitError;
    }
    return ReconciliationFlowSupport.createInvoicePayments(
        account, line, invoiceSpecs, operationIds, tolerance, paymentMethodId, writeoffDifference);
  }

  /**
   * Rejects a write-off larger than the account's configured limit (ETP-4797). Returns {@code null}
   * when the request is acceptable.
   *
   * <p>Enforced server-side as well as in the UI: the toggle being disabled is a convenience, not a
   * boundary.
   *
   * <p><b>Deliberate divergence from Classic.</b> Classic applies this check only when the
   * {@code WriteOffLimitPreference} preference is set to {@code 'Y'}, and its JS compares
   * {@code totalWriteOffAmount > data.writeofflimit} — so an unset or zero limit blocks EVERY
   * write-off. {@code Writeofflimit} has no default and is not mandatory, and the preference does
   * not exist in this instance, so copying that literally would disable the feature everywhere it
   * is not explicitly configured. Here an unset or zero limit means "no limit", and only a
   * configured positive limit can reject.
   */
  private static NeoResponse assertWithinWriteoffLimit(FIN_FinancialAccount account,
      boolean writeoffDifference, FIN_BankStatementLine line, JSONArray invoiceSpecs) {
    if (!writeoffDifference) {
      return null;
    }
    BigDecimal limit = account.getWriteofflimit();
    if (limit == null || limit.signum() <= 0) {
      return null;
    }
    BigDecimal lineAmount = nullSafeAmount(line.getCramount())
        .subtract(nullSafeAmount(line.getDramount())).abs();
    BigDecimal selectedOutstanding = sumSelectedOutstanding(invoiceSpecs);
    BigDecimal difference = selectedOutstanding.subtract(lineAmount);
    if (difference.compareTo(limit) > 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "The difference to write off (" + difference.toPlainString() + ") exceeds the write-off "
              + "limit configured for this financial account (" + limit.toPlainString() + ").");
    }
    return null;
  }

  /** Total outstanding of the selected invoice installments, in their own currency. */
  private static BigDecimal sumSelectedOutstanding(JSONArray invoiceSpecs) {
    BigDecimal total = BigDecimal.ZERO;
    for (int i = 0; i < invoiceSpecs.length(); i++) {
      String scheduleId = invoiceSpecs.optJSONObject(i) == null
          ? null
          : invoiceSpecs.optJSONObject(i).optString("scheduleId", null);
      if (StringUtils.isBlank(scheduleId)) {
        continue;
      }
      // Tenant-guarded, and it has to be guarded HERE rather than relying on the loadOwned in
      // ReconciliationFlowSupport: the write-off limit is asserted BEFORE the payment is created, so
      // this read runs first. Unguarded, a foreign scheduleId got its outstanding amount added to
      // the total, and the "exceeds the write-off limit (N)" rejection then reported N — leaking the
      // exact pending amount of another tenant's invoice instalment (ETP-4950).
      FIN_PaymentSchedule schedule =
          TenantOwnership.loadOwned(FIN_PaymentSchedule.class, scheduleId);
      if (schedule != null) {
        total = total.add(nullSafeAmount(schedule.getOutstandingAmount()).abs());
      }
    }
    return total;
  }

  private static BigDecimal nullSafeAmount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
