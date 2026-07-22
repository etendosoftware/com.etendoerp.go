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
 * Bank-reconciliation multi-currency payment registration. Split out of
 * {@link PaymentRegistrationService} to keep that class under the method-count limit
 * (Sonar S1200); reuses its draft-payment plumbing via package-visible helpers.
 */
final class ReconciliationPaymentService {

  private ReconciliationPaymentService() {
  }

  /**
   * Bank-reconciliation multi-currency variant of {@link PaymentRegistrationService#registerPaymentCore}:
   * fully settles the invoice outstanding ({@code amount}, in the invoice currency) while booking
   * the financial transaction for the exact statement-line amount ({@code accountAmount}, in the
   * account currency). The conversion rate is derived from the two amounts (see
   * {@link PaymentCurrencyConverter#derivedRate}) so the transaction reconciles against the bank
   * statement to the cent. Requires the resolved payment method to be multi-currency enabled for
   * the direction; a PSD2 bank-transfer method (multi-currency disabled by ETP-4503) is rejected
   * with a clear error rather than a cryptic Core failure. Used only when the invoice and account
   * currencies differ; the same-currency path stays on
   * {@link PaymentRegistrationService#registerPaymentCore}.
   */
  static FIN_Payment registerReconciliationPaymentMultiCurrency(Invoice invoice,
      FIN_PaymentSchedule schedule, BigDecimal amount, BigDecimal accountAmount, Date paymentDate,
      FIN_FinancialAccount account, boolean isReceipt) throws Exception {

    List<FIN_PaymentScheduleDetail> pendingPSDs =
        PaymentRegistrationService.findPendingPSDs(schedule.getId());
    if (pendingPSDs.isEmpty()) {
      throw new OBException(PaymentRegistrationService.MSG_NO_PENDING_PSD);
    }

    Organization org = invoice.getOrganization();

    FIN_PaymentMethod paymentMethod =
        PaymentRegistrationService.resolvePaymentMethod(account, invoice, isReceipt);
    if (paymentMethod == null) {
      throw new OBException("No payment method configured for this financial account. "
          + "Please configure a payment method in the financial account settings.");
    }
    assertMethodMultiCurrency(account, paymentMethod, isReceipt);

    DocumentType docType = PaymentRegistrationService.resolveArApDocType(org, isReceipt);
    PaymentRegistrationService.checkPeriodOpen(invoice, docType, paymentDate);

    BigDecimal rate = PaymentCurrencyConverter.derivedRate(amount, accountAmount);
    FIN_Payment payment = PaymentRegistrationService.createDraftPayment(
        new PaymentRegistrationService.DraftPaymentRequest(new AdvPaymentMngtDao(), isReceipt,
            invoice, paymentMethod, account, paymentDate),
        rate, amount, accountAmount);
    PaymentRegistrationService.linkPSDsToPayment(pendingPSDs, payment, amount);
    PaymentRegistrationService.processOrThrow(payment);
    return payment;
  }

  /**
   * Ensures the account link for {@code method} in the given direction is flagged multi-currency
   * (payin/payout). Used by the reconciliation multi-currency path so a foreign-currency settlement
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
