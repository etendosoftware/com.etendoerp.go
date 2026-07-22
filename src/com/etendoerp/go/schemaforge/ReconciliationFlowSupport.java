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
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;

final class ReconciliationFlowSupport {

  private ReconciliationFlowSupport() {
  }

  static NeoResponse createInvoicePayments(FIN_FinancialAccount account,
      FIN_BankStatementLine line, JSONArray invoiceSpecs, List<String> operationIds,
      BigDecimal tolerance) throws Exception {
    BigDecimal lineAmount = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    boolean isReceipt = lineAmount.signum() >= 0;

    // Multi-currency: when a selected invoice is in a currency other than the account's, amounts
    // cannot be summed across currencies, so this iteration supports a single invoice fully settled
    // by the line. The rate is derived from the two amounts (statement line in account currency ÷
    // invoice outstanding in invoice currency); the same-currency path below is unchanged.
    if (hasForeignInvoice(account, invoiceSpecs)) {
      return createForeignInvoicePayment(account, line, invoiceSpecs, lineAmount, isReceipt,
          operationIds, tolerance);
    }

    BigDecimal remaining = lineAmount.abs();
    for (int i = 0; i < invoiceSpecs.length(); i++) {
      if (remaining.compareTo(tolerance) <= 0) {
        break;
      }
      JSONObject spec = invoiceSpecs.getJSONObject(i);
      String invoiceId = spec.optString("invoiceId", null);
      String scheduleId = spec.optString("scheduleId", null);
      if (StringUtils.isBlank(invoiceId) || StringUtils.isBlank(scheduleId)) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "invoiceId and scheduleId are required for each invoice");
      }
      Invoice invoice = org.openbravo.dal.service.OBDal.getInstance().get(Invoice.class, invoiceId);
      FIN_PaymentSchedule schedule = org.openbravo.dal.service.OBDal.getInstance()
          .get(FIN_PaymentSchedule.class, scheduleId);
      if (invoice == null || schedule == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
            "Invoice or payment schedule not found: " + invoiceId);
      }
      BigDecimal outstanding = nullSafe(schedule.getOutstandingAmount()).abs();
      BigDecimal allocate = remaining.min(outstanding);
      if (allocate.compareTo(tolerance) > 0) {
        FIN_Payment payment = PaymentRegistrationService.registerPaymentCore(
            invoice, schedule, allocate, line.getTransactionDate(), account, isReceipt);
        List<FIN_FinaccTransaction> txns = payment.getFINFinaccTransactionList();
        if (txns.isEmpty()) {
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "Payment did not produce a transaction: " + payment.getId());
        }
        ReactivationSupport.markAutoCreated(txns.get(0));
        operationIds.add(txns.get(0).getId());
        remaining = remaining.subtract(allocate);
      }
    }
    if (remaining.compareTo(tolerance) > 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "The selected invoices do not cover the statement line amount. Remaining: "
              + remaining.toPlainString());
    }
    return null;
  }

  /**
   * True when any selected invoice is in a currency different from the account's. Accounts without
   * a declared currency keep the legacy single-currency behavior (returns false).
   */
  private static boolean hasForeignInvoice(FIN_FinancialAccount account, JSONArray invoiceSpecs) {
    String accountCurrencyId = account.getCurrency() != null ? account.getCurrency().getId() : null;
    if (accountCurrencyId == null) {
      return false;
    }
    for (int i = 0; i < invoiceSpecs.length(); i++) {
      JSONObject spec = invoiceSpecs.optJSONObject(i);
      String invoiceId = spec != null ? spec.optString("invoiceId", null) : null;
      if (StringUtils.isBlank(invoiceId)) {
        continue;
      }
      Invoice invoice = org.openbravo.dal.service.OBDal.getInstance().get(Invoice.class, invoiceId);
      if (invoice != null && invoice.getCurrency() != null
          && !accountCurrencyId.equals(invoice.getCurrency().getId())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Multi-currency reconciliation: a single foreign-currency invoice fully settled by the statement
   * line. Generates the payment in the invoice currency (cancelling the outstanding) and the
   * financial transaction in the account currency (the exact line amount) with the derived
   * conversion rate. Restricted to one invoice — mixing currencies across several invoices under a
   * single line is out of scope for this iteration.
   */
  private static NeoResponse createForeignInvoicePayment(FIN_FinancialAccount account,
      FIN_BankStatementLine line, JSONArray invoiceSpecs, BigDecimal lineAmount, boolean isReceipt,
      List<String> operationIds, BigDecimal tolerance) throws Exception {
    if (invoiceSpecs.length() != 1) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Multi-currency reconciliation supports a single invoice per statement line.");
    }
    JSONObject spec = invoiceSpecs.getJSONObject(0);
    String invoiceId = spec.optString("invoiceId", null);
    String scheduleId = spec.optString("scheduleId", null);
    if (StringUtils.isBlank(invoiceId) || StringUtils.isBlank(scheduleId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "invoiceId and scheduleId are required for each invoice");
    }
    Invoice invoice = org.openbravo.dal.service.OBDal.getInstance().get(Invoice.class, invoiceId);
    FIN_PaymentSchedule schedule = org.openbravo.dal.service.OBDal.getInstance()
        .get(FIN_PaymentSchedule.class, scheduleId);
    if (invoice == null || schedule == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Invoice or payment schedule not found: " + invoiceId);
    }
    BigDecimal outstanding = nullSafe(schedule.getOutstandingAmount()).abs();
    BigDecimal accountAmount = lineAmount.abs();
    if (outstanding.compareTo(tolerance) <= 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "The selected invoice has no outstanding amount to settle.");
    }
    if (accountAmount.compareTo(tolerance) <= 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "The statement line amount is zero; nothing to reconcile.");
    }
    FIN_Payment payment = PaymentRegistrationService.registerReconciliationPaymentMultiCurrency(
        invoice, schedule, outstanding, accountAmount, line.getTransactionDate(), account,
        isReceipt);
    List<FIN_FinaccTransaction> txns = payment.getFINFinaccTransactionList();
    if (txns.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Payment did not produce a transaction: " + payment.getId());
    }
    ReactivationSupport.markAutoCreated(txns.get(0));
    operationIds.add(txns.get(0).getId());
    return null;
  }

  static NeoResponse validateOperations(List<String> operationIds, String accountId,
      FIN_BankStatementLine line, Function<String, FIN_FinaccTransaction> transactionLoader,
      BigDecimal tolerance) {
    BigDecimal opSum = BigDecimal.ZERO;
    for (String opId : operationIds) {
      FIN_FinaccTransaction trx = transactionLoader.apply(opId);
      if (trx == null) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Operation not found: " + opId);
      }
      if (trx.getAccount() == null || !accountId.equals(trx.getAccount().getId())) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Operation does not belong to the financial account: " + opId);
      }
      if (trx.getReconciliation() != null) {
        return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
            "Operation is already reconciled: " + opId);
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
}
