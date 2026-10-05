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

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

/**
 * Scopes the ids an invoice payment action receives to the invoice in the URL and the caller's
 * tenant (ETP-5558).
 *
 * <p>The invoice payment actions run in admin mode and used to resolve their ids with a bare
 * {@code OBDal.get}, which adds no readable-client/organization predicate — so a known draft id
 * could be confirmed or deleted through any invoice, across tenants. {@link TenantOwnership} answers
 * "may this caller see the row"; this class adds "does the row belong to this invoice". Both answer
 * {@code null} for a refusal, so the callers keep their existing 404, indistinguishable from a
 * missing row.</p>
 */
final class PaymentOwnership {

  private PaymentOwnership() {
  }

  /**
   * The payment {@code paymentId} names, when the caller's tenant can read it and at least one of
   * its schedule details is against an installment of {@code invoiceId}.
   *
   * @param paymentId the id from the request body
   * @param invoiceId the invoice in the URL
   * @return the payment, or {@code null} when blank, unknown, another tenant's or another invoice's
   */
  static FIN_Payment invoicePayment(String paymentId, String invoiceId) {
    FIN_Payment payment = TenantOwnership.loadOwned(FIN_Payment.class, paymentId);
    return payment != null && appliesToInvoice(payment, invoiceId) ? payment : null;
  }

  /**
   * {@code paymentId} when it is a payment of the invoice the caller may read, otherwise
   * {@code null} — for a read that only uses the id as a hint (the credit a draft being edited
   * already holds), where an unusable one is ignored rather than refused.
   */
  static String ownDraftIdOrNull(String paymentId, String invoiceId) {
    return invoicePayment(paymentId, invoiceId) != null ? paymentId : null;
  }

  /**
   * The installment {@code scheduleId} names, when it is one of {@code invoice}'s own.
   *
   * @return the installment, or {@code null} when blank, unknown, another tenant's or another
   *         invoice's
   */
  static FIN_PaymentSchedule scheduleOf(String scheduleId, Invoice invoice) {
    FIN_PaymentSchedule schedule = TenantOwnership.loadOwned(FIN_PaymentSchedule.class,
        scheduleId);
    if (schedule == null || invoice == null || schedule.getInvoice() == null) {
      return null;
    }
    return StringUtils.equals(schedule.getInvoice().getId(), invoice.getId()) ? schedule : null;
  }

  /**
   * The 404 for an installment or an edited draft that is not this invoice's, or {@code null}.
   *
   * @param body       the request body; its {@code paymentId}, when present, is the draft edited
   * @param invoice    the invoice, already owned by the caller
   * @param scheduleId the installment the request pays
   * @return the refusal, or {@code null} when both belong to the invoice
   */
  static NeoResponse refusalFor(JSONObject body, Invoice invoice, String scheduleId) {
    // ETP-5558: the installment must be one of THIS invoice's, readable by the caller.
    if (scheduleOf(scheduleId, invoice) == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Payment schedule not found");
    }
    // ETP-5558: the draft being edited is checked HERE, before any side effect — a PIS confirm
    // instructs the bank transfer long before resolveOrCreatePayment runs, so a foreign or stale
    // id must not get that far (money would move and only the replay would answer 404).
    String requestedEditId = body.optString(PaymentRegistrationService.KEY_PAYMENT_ID, null);
    if (StringUtils.isNotBlank(requestedEditId)
        && invoicePayment(requestedEditId, invoice.getId()) == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          PaymentRegistrationService.MSG_PAYMENT_NOT_FOUND);
    }
    return null;
  }

  /** @return whether both have a business partner and it is the same one */
  static boolean sameBusinessPartner(BusinessPartner a, BusinessPartner b) {
    return a != null && b != null && StringUtils.equals(a.getId(), b.getId());
  }

  private static boolean appliesToInvoice(FIN_Payment payment, String invoiceId) {
    if (StringUtils.isBlank(invoiceId) || payment.getFINPaymentDetailList() == null) {
      return false;
    }
    for (FIN_PaymentDetail detail : payment.getFINPaymentDetailList()) {
      for (FIN_PaymentScheduleDetail psd : detail.getFINPaymentScheduleDetailList()) {
        FIN_PaymentSchedule schedule = psd.getInvoicePaymentSchedule();
        Invoice invoice = schedule == null ? null : schedule.getInvoice();
        if (invoice != null && invoiceId.equals(invoice.getId())) {
          return true;
        }
      }
    }
    return false;
  }
}
