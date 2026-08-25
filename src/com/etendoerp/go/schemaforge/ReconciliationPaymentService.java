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

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.openbravo.advpaymentmngt.dao.AdvPaymentMngtDao;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;

/**
 * Bank-reconciliation payment registration — same-currency and cross-currency alike. Split out of
 * {@link PaymentRegistrationService} to keep that class under the method-count limit
 * (Sonar S1200); reuses its draft-payment plumbing via package-visible helpers.
 */
final class ReconciliationPaymentService {

  private ReconciliationPaymentService() {
  }

  /**
   * The closely-related inputs of {@link #registerReconciliationPayment(ReconciliationPaymentRequest)}
   * grouped into a single value (Sonar S107): the invoice installment being settled ({@code invoice}
   * + {@code schedule}), the two amounts ({@code paymentAmount} in invoice currency,
   * {@code accountAmount} in account currency) with their {@code rate}, and the booking context
   * ({@code paymentDate}, {@code account}, {@code isReceipt}, {@code chosenMethod}).
   *
   * <p>{@code writeoffDifference} (ETP-4797) writes off whatever {@code paymentAmount} leaves
   * unpaid on the installment, so the invoice is fully settled instead of keeping a residual
   * balance. The caller only sets it for a partial settlement of a single selected invoice.
   */
  record ReconciliationPaymentRequest(Invoice invoice, FIN_PaymentSchedule schedule,
      BigDecimal paymentAmount, BigDecimal accountAmount, BigDecimal rate, Date paymentDate,
      FIN_FinancialAccount account, boolean isReceipt, FIN_PaymentMethod chosenMethod,
      boolean writeoffDifference) {
  }

  /**
   * Registers a bank-reconciliation payment against an invoice installment, in either the same
   * currency as the account ({@code rate} = {@link BigDecimal#ONE}) or a different one. The payment
   * is created for {@code paymentAmount} (invoice currency) and the financial transaction is booked
   * for the exact {@code accountAmount} (account currency) — the caller
   * ({@link ReconciliationFlowSupport}) computes {@code accountAmount} as
   * {@code paymentAmount × rate}, per the "invoice amount times its own exchange rate" contract:
   * the rate comes from the invoice's own exchange rate (see
   * {@link PaymentCurrencyConverter#resolveInvoiceRate}), not from what the statement line happens
   * to carry, so a mismatch between the two settles the invoice correctly and simply leaves the
   * difference unreconciled on the statement line (handled by the caller's remaining-amount check).
   *
   * <p>{@code chosenMethod}, when non-null, is the payment method the user picked in the
   * reconciliation modal (one method for the whole match); validated against the account/direction.
   * When {@code null}, the method is auto-resolved exactly as the simple invoice quick-pay path
   * does (see {@link PaymentRegistrationService#registerPaymentCore}). A cross-currency settlement
   * additionally requires the resolved method to be multi-currency enabled; a PSD2 bank-transfer
   * method (multi-currency disabled by ETP-4503) is rejected with a clear error rather than a
   * cryptic Core failure.
   *
   * <p>A same-currency settlement CAN use the bank-transfer method (e.g. a connected account's
   * imported statement line, reconciled against an invoice). This never goes through PIS — there is
   * no live handshake to defer to, the transaction is for money that has already moved — so {@link
   * PaymentRegistrationService#processOrThrow} always forces it to be created immediately
   * ({@code mayDeferToPis=false}, ETP-4891). Deferring here would leave {@link
   * ReconciliationFlowSupport} with no transaction to match against the statement line at all.
   */
  static FIN_Payment registerReconciliationPayment(ReconciliationPaymentRequest req)
      throws Exception {
    Invoice invoice = req.invoice();
    FIN_PaymentSchedule schedule = req.schedule();
    BigDecimal paymentAmount = req.paymentAmount();
    BigDecimal accountAmount = req.accountAmount();
    BigDecimal rate = req.rate();
    Date paymentDate = req.paymentDate();
    FIN_FinancialAccount account = req.account();
    boolean isReceipt = req.isReceipt();
    FIN_PaymentMethod chosenMethod = req.chosenMethod();

    List<FIN_PaymentScheduleDetail> pendingPSDs =
        PaymentRegistrationService.findPendingPSDs(schedule.getId());
    if (pendingPSDs.isEmpty()) {
      throw new OBException(PaymentRegistrationService.MSG_NO_PENDING_PSD);
    }

    Organization org = invoice.getOrganization();
    boolean crossCurrency = invoice.getCurrency() != null && account.getCurrency() != null
        && !invoice.getCurrency().getId().equals(account.getCurrency().getId());

    FIN_PaymentMethod paymentMethod = resolveMethod(account, invoice, isReceipt, chosenMethod);
    if (crossCurrency) {
      assertMethodMultiCurrency(account, paymentMethod, isReceipt);
    }

    DocumentType docType = PaymentRegistrationService.resolveArApDocType(org, isReceipt);
    PaymentRegistrationService.checkPeriodOpen(invoice, docType, paymentDate);

    FIN_Payment payment = PaymentRegistrationService.createDraftPayment(
        new PaymentRegistrationService.DraftPaymentRequest(new AdvPaymentMngtDao(), isReceipt,
            invoice, paymentMethod, account, paymentDate),
        rate, paymentAmount, accountAmount);
    PaymentRegistrationService.linkPSDsToPayment(pendingPSDs, payment, paymentAmount,
        req.writeoffDifference());
    PaymentRegistrationService.processOrThrow(payment);
    return payment;
  }

  /**
   * The user-chosen method (validated against the account/direction) when one was picked in the
   * reconciliation modal, otherwise the auto-resolved default
   * ({@link PaymentRegistrationService#resolvePaymentMethod}).
   */
  private static FIN_PaymentMethod resolveMethod(FIN_FinancialAccount account, Invoice invoice,
      boolean isReceipt, FIN_PaymentMethod chosenMethod) {
    if (chosenMethod != null) {
      if (!PaymentRegistrationService.isMethodAllowed(account, chosenMethod,
          PaymentRegistrationService.allowProperty(isReceipt))) {
        throw new OBException("The payment method '" + chosenMethod.getName() + "' is not "
            + "configured for this financial account.");
      }
      return chosenMethod;
    }
    FIN_PaymentMethod paymentMethod =
        PaymentRegistrationService.resolvePaymentMethod(account, invoice, isReceipt);
    if (paymentMethod == null) {
      throw new OBException("No payment method configured for this financial account. "
          + "Please configure a payment method in the financial account settings.");
    }
    return paymentMethod;
  }

  /**
   * Ensures the account link for {@code method} in the given direction is flagged multi-currency
   * (payin/payout). Used by the reconciliation cross-currency path so a foreign-currency settlement
   * is rejected with a clear message when the method is single-currency (e.g. a PSD2 bank-transfer
   * method disabled by {@link FinancialAccountSupport#disableMulticurrencyForBankTransfer}).
   */
  private static void assertMethodMultiCurrency(FIN_FinancialAccount account,
      FIN_PaymentMethod method, boolean isReceipt) {
    OBCriteria<FinAccPaymentMethod> crit = OBDal.getInstance()
        .createCriteria(FinAccPaymentMethod.class);
    crit.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
    crit.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_PAYMENTMETHOD, method));
    crit.setMaxResults(1);
    List<FinAccPaymentMethod> links = crit.list();
    boolean multiCurrency = !links.isEmpty() && (isReceipt
        ? Boolean.TRUE.equals(links.get(0).isPayinIsMulticurrency())
        : Boolean.TRUE.equals(links.get(0).isPayoutIsMulticurrency()));
    if (!multiCurrency) {
      throw new OBException("The payment method '" + method.getName() + "' on this account is not "
          + "enabled for multi-currency, so an invoice in a different currency cannot be reconciled "
          + "against it.");
    }
  }
}
