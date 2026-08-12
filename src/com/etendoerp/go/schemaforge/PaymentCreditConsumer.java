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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.advpaymentmngt.process.FIN_PaymentProcess;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

/**
 * Consumes the funding sources selected in the two-step Cobros/Pagos modal,
 * applying each to the {@link FIN_Payment} being registered:
 *   - 'credit' (accumulated credit): Classic's used-credit mechanism
 *     ({@code setUsedCredit} + {@link FIN_PaymentProcess#linkCreditPayment}) on the
 *     source payment — it must NOT be re-linked as a detail (it is already paid).
 *   - 'abono' (any invoice with a negative total, ETP-4841 — the document type is
 *     irrelevant): linked as a negative invoice payment detail.
 */
final class PaymentCreditConsumer {

  private PaymentCreditConsumer() {
  }

  /**
   * Consumes the selected funding sources, returning the total amount they fund.
   */
  static BigDecimal consume(FIN_Payment payment, JSONArray creditSources) {
    BigDecimal totalFunded = BigDecimal.ZERO;
    if (creditSources == null) {
      return totalFunded;
    }
    for (int i = 0; i < creditSources.length(); i++) {
      totalFunded = totalFunded.add(consumeOne(payment, creditSources.optJSONObject(i)));
    }
    return totalFunded;
  }

  /** Consumes a single funding source, returning the amount it funded (0 when skipped). */
  private static BigDecimal consumeOne(FIN_Payment payment, JSONObject src) {
    if (src == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal use = parsePositiveAmount(src.optString("use", "0"));
    if (use == null) {
      return BigDecimal.ZERO;
    }
    if ("credit".equals(src.optString("kind", ""))) {
      return consumeAccumulatedCredit(payment, src.optString("paymentId", null), use);
    }
    return consumeAbono(payment, src.optString("psdId", null), use);
  }

  /** Consumes accumulated credit from a source payment via the used-credit mechanism. */
  private static BigDecimal consumeAccumulatedCredit(FIN_Payment payment, String paymentId,
      BigDecimal use) {
    if (StringUtils.isBlank(paymentId)) {
      return BigDecimal.ZERO;
    }
    FIN_Payment creditPayment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    if (creditPayment == null) {
      throw new OBException("Credit payment not found: " + paymentId);
    }
    BigDecimal prev = creditPayment.getUsedCredit() == null
        ? BigDecimal.ZERO : creditPayment.getUsedCredit();
    creditPayment.setUsedCredit(prev.add(use));
    FIN_PaymentProcess.linkCreditPayment(payment, use, creditPayment);
    OBDal.getInstance().save(creditPayment);
    return use;
  }

  /** Consumes a negative-total invoice's "saldo a favor" by linking its PSD as a negative detail. */
  private static BigDecimal consumeAbono(FIN_Payment payment, String psdId, BigDecimal use) {
    if (StringUtils.isBlank(psdId)) {
      return BigDecimal.ZERO;
    }
    FIN_PaymentScheduleDetail psd = OBDal.getInstance().get(FIN_PaymentScheduleDetail.class, psdId);
    if (psd == null) {
      throw new OBException("Credit source not found: " + psdId);
    }
    validateAbonoEligible(payment, psd);
    FIN_AddPayment.updatePaymentDetail(psd, payment, use.negate(), false);
    return use;
  }

  /**
   * Rejects a "saldo a favor" PSD whose invoice total is not negative — the selector only ever
   * offers negative-total invoices, but nothing stops a crafted request from sending an
   * arbitrary {@code psdId}.
   *
   * <p>ETP-4841: the document type is deliberately NOT checked. Any invoice with a negative
   * total is a usable credit whatever its type, and a POSITIVE Factura Rectificativa is a
   * payable correction that this sign check already rejects on its own.
   *
   * <p>Skipped when {@code psd} is already linked to the payment being registered/edited: it was
   * validated when originally consumed, and the edit modal must be able to re-save an older
   * draft without being locked out by a rule introduced later.
   */
  private static void validateAbonoEligible(FIN_Payment payment, FIN_PaymentScheduleDetail psd) {
    FIN_PaymentDetail existingLink = psd.getPaymentDetails();
    if (existingLink != null && existingLink.getFinPayment() != null
        && existingLink.getFinPayment().getId().equals(payment.getId())) {
      return;
    }
    Invoice invoice = psd.getInvoicePaymentSchedule() != null
        ? psd.getInvoicePaymentSchedule().getInvoice() : null;
    if (invoice == null) {
      throw new OBException("Credit source not found: " + psd.getId());
    }
    boolean negativeTotal = invoice.getGrandTotalAmount() != null
        && invoice.getGrandTotalAmount().signum() < 0;
    if (!negativeTotal) {
      throw new OBException(
          "Credit source is not an eligible negative-total invoice: " + psd.getId());
    }
  }

  /** Parses a strictly-positive amount, returning null for blank/invalid/non-positive input. */
  private static BigDecimal parsePositiveAmount(String raw) {
    try {
      BigDecimal value = new BigDecimal(raw);
      return value.compareTo(BigDecimal.ZERO) > 0 ? value : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
