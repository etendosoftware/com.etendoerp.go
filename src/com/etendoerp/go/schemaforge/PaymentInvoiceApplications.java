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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FIN_Payment_Credit;

/**
 * How a payment maps onto the invoices it touches.
 *
 * <p>Three questions with one subject: which invoice a payment can be edited against, how much of
 * it lands on a given invoice, and which credits it is consuming. They were split out of
 * {@link PaymentRegistrationService} — which registers payments rather than describing them, and
 * had grown past its authorized method count.
 *
 * <p>Public for {@code invoiceIdsByPayment}, which the payment window's handler calls from another
 * package to tell each row which invoice it can be edited against.
 */
public final class PaymentInvoiceApplications {

  private static final Logger log = LogManager.getLogger(PaymentInvoiceApplications.class);

  private PaymentInvoiceApplications() {
  }

  /**
   * The invoice each of {@code paymentIds} was applied to, when there is exactly one.
   *
   * <p>Lets the payment window open the invoice's own payment editor for a draft, instead of the
   * yes/no confirm dialog that is all it can offer today — that window has no form of its own
   * ({@code hideFormCard}, every header field {@code form: false}), so without this there is no way
   * to correct a payment before confirming it (ETP-4895 follow-up).
   *
   * <p>Only <b>positive</b> applications count. A payment that also consumes a credit carries a
   * negative application against the credit note's own installment; that is the credit being spent,
   * not a second invoice being paid, and the editor already models it as a credit source.
   *
   * <p>Absent for anything that is not exactly one invoice — an empty shell with no application at
   * all, or the genuine multi-invoice payment this design does not cover. The caller falls back to
   * the confirm dialog there.
   *
   * @param paymentIds the payments on the response; may be empty
   * @return payment id → invoice id, resolved in a single query
   */
  public static Map<String, String> invoiceIdsByPayment(Collection<String> paymentIds) {
    if (paymentIds == null || paymentIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, Set<String>> byPayment = new HashMap<>();
    try {
      OBContext.setAdminMode(true);
      try {
        String hql = "select distinct pd.finPayment.id, psd.invoicePaymentSchedule.invoice.id "
            + "from FIN_Payment_Detail pd "
            + "join pd.fINPaymentScheduleDetailList psd "
            + "where pd.finPayment.id in :paymentIds "
            + "and psd.invoicePaymentSchedule is not null "
            + "and psd.amount > 0";
        List<Object[]> rows = OBDal.getInstance().getSession()
            .createQuery(hql, Object[].class)
            .setParameterList("paymentIds", paymentIds)
            .list();
        for (Object[] row : rows) {
          byPayment.computeIfAbsent((String) row[0], k -> new HashSet<>()).add((String) row[1]);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not resolve the invoices of the listed payments: {}", e.getMessage());
      return Collections.emptyMap();
    }
    Map<String, String> single = new HashMap<>();
    byPayment.forEach((paymentId, invoiceIds) -> {
      if (invoiceIds.size() == 1) {
        single.put(paymentId, invoiceIds.iterator().next());
      }
    });
    return single;
  }

  /**
   * Net amount {@code p} applies against {@code invoiceId}'s payment schedules: the sum of its
   * schedule details linked to that invoice. Positive when paying the invoice, negative when
   * consuming it as a credit note / return.
   */
  static BigDecimal appliedToInvoice(FIN_Payment p, String invoiceId) {
    BigDecimal total = BigDecimal.ZERO;
    for (FIN_PaymentDetail detail : p.getFINPaymentDetailList()) {
      for (FIN_PaymentScheduleDetail psd : detail.getFINPaymentScheduleDetailList()) {
        FIN_PaymentSchedule sched = psd.getInvoicePaymentSchedule();
        if (sched != null && sched.getInvoice() != null
            && invoiceId.equals(sched.getInvoice().getId())) {
          total = total.add(PaymentRegistrationService.nullToZero(psd.getAmount()));
        }
      }
    }
    return total;
  }

  /**
   * Reconstructs the credit/abono sources {@code payment} (a draft) is currently consuming, in the
   * same shape the frontend sends when registering ({@code {kind, paymentId|psdId, use}}), so the
   * edit modal can re-check the sources the draft already applied.
   */
  static JSONArray creditSourcesUsedByPayment(FIN_Payment payment) throws Exception {
    JSONArray arr = new JSONArray();
    OBCriteria<FIN_Payment_Credit> crit = OBDal.getInstance().createCriteria(FIN_Payment_Credit.class);
    crit.add(Restrictions.eq(FIN_Payment_Credit.PROPERTY_PAYMENT, payment));
    for (FIN_Payment_Credit link : crit.list()) {
      JSONObject used = new JSONObject();
      used.put(PaymentRegistrationService.KEY_KIND, PaymentRegistrationService.KIND_CREDIT);
      used.put(PaymentRegistrationService.KEY_PAYMENT_ID, link.getCreditPaymentUsed().getId());
      used.put(PaymentRegistrationService.KEY_USE, link.getAmount());
      arr.put(used);
    }
    for (FIN_PaymentDetail detail : payment.getFINPaymentDetailList()) {
      for (FIN_PaymentScheduleDetail psd : detail.getFINPaymentScheduleDetailList()) {
        if (psd.getAmount().signum() < 0) {
          JSONObject used = new JSONObject();
          used.put(PaymentRegistrationService.KEY_KIND, PaymentRegistrationService.KIND_ABONO);
          used.put(PaymentRegistrationService.KEY_PSD_ID, psd.getId());
          used.put(PaymentRegistrationService.KEY_USE, psd.getAmount().abs());
          arr.put(used);
        }
      }
    }
    return arr;
  }
}
