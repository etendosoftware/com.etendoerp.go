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

import static com.etendoerp.go.schemaforge.PaymentRegistrationService.MSG_NO_PENDING_PSD;
import static com.etendoerp.go.schemaforge.PaymentRegistrationService.MSG_PAYMENT_NOT_FOUND;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.hibernate.criterion.Restrictions;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FIN_Payment_Credit;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;

/**
 * Confirm / edit-in-place / delete operations on a DRAFT {@link FIN_Payment} (Borrador). Split out
 * of {@link PaymentRegistrationService} to keep that class under the method-count limit
 * (Sonar S1200).
 */
final class PaymentDraftEditService {

  private PaymentDraftEditService() {
  }

  /** Processes a previously-saved draft payment (Borrador → Depositado). */
  static NeoResponse confirmDraftPayment(String paymentId) throws Exception {
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    if (payment == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_PAYMENT_NOT_FOUND);
    }
    PaymentRegistrationService.processOrThrow(payment);
    return PaymentRegistrationService.builtPaymentResponse(payment);
  }

  /**
   * Deletes a DRAFT payment (Borrador) — never a processed one, which is read-only. Unlike editing
   * in place, the payment itself IS removed here (no reason to keep its id/documentNo). Reverses
   * whatever this draft holds so nothing is left dangling: consumed accumulated credit (its source's
   * {@code usedCredit} is restored, the {@code FIN_Payment_Credit} link removed), payment-owned
   * credit/refund PSDs (deleted outright), and the document's own installment PSD (released back to
   * pending via Core's own reconciliation, {@link FIN_AddPayment#updatePaymentDetail}, so the
   * invoice becomes payable again instead of being left fragmented).
   *
   * <p>The detach above is the step Classic's own "Remove Payment" performs by REACTIVATING first;
   * a bare {@code PaymentRemovalUtil.remove()} on a still-linked draft would hit a FK constraint
   * ("associated with other existing elements"). Once the children are detached, we hand the final
   * deletion to the module's {@link PaymentRemovalUtil#remove} so it stays the single source of
   * truth for payment removal (its invoice-recompute becomes a harmless no-op here, since our
   * release already restored the schedule).
   */
  static NeoResponse deleteDraftPayment(String paymentId) {
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    if (payment == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_PAYMENT_NOT_FOUND);
    }
    if (isPaymentProcessed(payment)) {
      throw new OBException("Cannot delete a processed payment");
    }
    reverseConsumedCredit(payment);
    releaseInstallmentDetails(payment);
    removeCreditOwnedDetails(payment);
    PaymentRemovalUtil.remove(payment);
    OBDal.getInstance().flush();
    return NeoResponse.noContent();
  }

  /**
   * Releases the document's own installment {@link FIN_PaymentScheduleDetail} — linked to this
   * draft — back to pending, via {@link FIN_AddPayment#updatePaymentDetail} with a zero amount:
   * Core's own "editing an existing link" branch folds the whole amount back into the sibling
   * outstanding fragment it tracks, so the invoice becomes payable again without leaving a
   * fragmented/duplicated schedule. Self-contained: also deletes the now-empty document PSD and
   * its {@link FIN_PaymentDetail} (they carry no value once zeroed), so the caller doesn't need to
   * assume the in-memory collections resync after the zeroing.
   */
  private static void releaseInstallmentDetails(FIN_Payment payment) {
    for (FIN_PaymentDetail detail : payment.getFINPaymentDetailList()) {
      for (FIN_PaymentScheduleDetail psd : detail.getFINPaymentScheduleDetailList()) {
        if (psd.getInvoicePaymentSchedule() != null || psd.getOrderPaymentSchedule() != null) {
          FIN_AddPayment.updatePaymentDetail(psd, payment, BigDecimal.ZERO, false);
        }
      }
    }
    OBDal.getInstance().flush();
    for (FIN_PaymentDetail detail : new ArrayList<>(payment.getFINPaymentDetailList())) {
      List<FIN_PaymentScheduleDetail> psds = new ArrayList<>(detail.getFINPaymentScheduleDetailList());
      boolean isDocumentDetail = psds.stream()
          .anyMatch(psd -> psd.getInvoicePaymentSchedule() != null || psd.getOrderPaymentSchedule() != null);
      if (!isDocumentDetail) {
        continue; // payment-owned (credit/refund) details are handled by removeCreditOwnedDetails.
      }
      for (FIN_PaymentScheduleDetail psd : psds) {
        detail.getFINPaymentScheduleDetailList().remove(psd);
        OBDal.getInstance().remove(psd);
      }
      payment.getFINPaymentDetailList().remove(detail);
      OBDal.getInstance().remove(detail);
    }
    OBDal.getInstance().flush();
  }

  /**
   * Guards that {@code existing} is an editable DRAFT (throws → HTTP 400 when processed), clears
   * what THIS draft owns outright (consumed credit, payment-owned credit/refund PSDs), and re-sets
   * its date/account/method/amount. Deliberately leaves the document's own installment
   * {@link FIN_PaymentScheduleDetail} LINKED to this payment — {@link #reapplyLinkedInstallmentPSD}
   * adjusts it in place afterwards via Core's own reconciliation instead of detaching/re-searching
   * it, which avoids re-implementing Core's split/merge bookkeeping by hand. Returns the SAME
   * {@link FIN_Payment} (id + documentNo unchanged).
   */
  static FIN_Payment prepareEditableDraft(FIN_Payment existing,
      FIN_PaymentMethod paymentMethod, FIN_FinancialAccount account, Date paymentDate,
      BigDecimal cash) {
    if (isPaymentProcessed(existing)) {
      throw new OBException("Cannot edit a processed payment");
    }
    reverseConsumedCredit(existing);
    removeCreditOwnedDetails(existing);
    reapplyDraftBasics(existing, paymentMethod, account, paymentDate, cash);
    return existing;
  }

  /**
   * True when the payment is no longer an editable draft: it has been processed (its bank
   * transaction / accounting exist), so it is read-only.
   */
  private static boolean isPaymentProcessed(FIN_Payment payment) {
    return Boolean.TRUE.equals(payment.isProcessed());
  }

  /**
   * Gives back the accumulated credit this draft consumed from its source payments (decrementing
   * their {@code usedCredit}) and removes the {@code FIN_Payment_Credit} links, mirroring the create
   * path's {@link PaymentCreditConsumer#consume} but in reverse.
   */
  private static void reverseConsumedCredit(FIN_Payment payment) {
    OBCriteria<FIN_Payment_Credit> crit = OBDal.getInstance()
        .createCriteria(FIN_Payment_Credit.class);
    crit.add(Restrictions.eq(FIN_Payment_Credit.PROPERTY_PAYMENT, payment));
    for (FIN_Payment_Credit link : crit.list()) {
      FIN_Payment source = link.getCreditPaymentUsed();
      if (source != null) {
        BigDecimal restored = PaymentRegistrationService.nullToZero(source.getUsedCredit())
            .subtract(PaymentRegistrationService.nullToZero(link.getAmount()));
        source.setUsedCredit(restored.max(BigDecimal.ZERO));
        OBDal.getInstance().save(source);
      }
      OBDal.getInstance().remove(link);
    }
  }

  /**
   * Removes the payment's payment-owned {@link FIN_PaymentDetail}/{@link FIN_PaymentScheduleDetail}
   * rows (accumulated credit / over-payment / G/L — no invoice or order schedule): these were
   * created by this draft and are re-created fresh by {@link PaymentCreditConsumer#consume} on
   * re-apply. The document's own installment/credit-memo schedule detail is left untouched (still
   * linked) — {@link #reapplyLinkedInstallmentPSD} adjusts it afterwards.
   */
  private static void removeCreditOwnedDetails(FIN_Payment payment) {
    for (FIN_PaymentDetail detail : new ArrayList<>(payment.getFINPaymentDetailList())) {
      List<FIN_PaymentScheduleDetail> psds = detail.getFINPaymentScheduleDetailList();
      boolean isDocumentDetail = psds.stream()
          .anyMatch(psd -> psd.getInvoicePaymentSchedule() != null || psd.getOrderPaymentSchedule() != null);
      if (isDocumentDetail) {
        continue;
      }
      for (FIN_PaymentScheduleDetail psd : new ArrayList<>(psds)) {
        OBDal.getInstance().remove(psd);
      }
      payment.getFINPaymentDetailList().remove(detail);
      OBDal.getInstance().remove(detail);
    }
    OBDal.getInstance().flush();
  }

  /**
   * Adjusts the document's own installment {@link FIN_PaymentScheduleDetail} — already linked to
   * this reused draft payment — to the new funded amount. Delegates to
   * {@link FIN_AddPayment#updatePaymentDetail}, which (because the PSD is already linked to THIS
   * payment) takes its "editing an existing link" branch: it grows or shrinks the link and folds
   * the difference into the sibling outstanding fragment Core itself tracks, exactly like Classic's
   * own Add Payment screen does when you edit a saved, unprocessed payment. This avoids
   * re-implementing that split/merge bookkeeping by hand. Returns the amount actually applied.
   *
   * @throws OBException if no installment PSD for {@code scheduleId} is linked to this payment
   *     (should not happen for a draft created by this same service).
   */
  static BigDecimal reapplyLinkedInstallmentPSD(FIN_Payment payment, String scheduleId,
      BigDecimal funds) {
    FIN_PaymentScheduleDetail linkedPsd = findLinkedInstallmentPSD(payment, scheduleId);
    if (linkedPsd == null) {
      throw new OBException(MSG_NO_PENDING_PSD);
    }
    // The schedule's total amount never changes — it's the cap on how much this payment can
    // apply to it (the rest stays in Core's own outstanding fragment for the same schedule).
    BigDecimal scheduleTotal = linkedPsd.getInvoicePaymentSchedule().getAmount();
    BigDecimal invoiceApplied = funds.min(scheduleTotal).max(BigDecimal.ZERO);
    FIN_AddPayment.updatePaymentDetail(linkedPsd, payment, invoiceApplied, false);
    return invoiceApplied;
  }

  /** The document's own installment PSD, already linked to {@code payment}, for {@code scheduleId}. */
  private static FIN_PaymentScheduleDetail findLinkedInstallmentPSD(FIN_Payment payment, String scheduleId) {
    for (FIN_PaymentDetail detail : payment.getFINPaymentDetailList()) {
      for (FIN_PaymentScheduleDetail psd : detail.getFINPaymentScheduleDetailList()) {
        FIN_PaymentSchedule sched = psd.getInvoicePaymentSchedule();
        if (sched != null && scheduleId.equals(sched.getId())) {
          return psd;
        }
      }
    }
    return null;
  }

  /** Re-sets the editable header basics on the reused draft, mirroring {@code createDraftPayment}. */
  private static void reapplyDraftBasics(FIN_Payment payment, FIN_PaymentMethod paymentMethod,
      FIN_FinancialAccount account, Date paymentDate, BigDecimal amount) {
    payment.setPaymentDate(paymentDate);
    payment.setAccount(account);
    payment.setPaymentMethod(paymentMethod);
    payment.setAmount(amount);
    FIN_AddPayment.setFinancialTransactionAmountAndRate(null, payment, BigDecimal.ONE, amount);
    OBDal.getInstance().save(payment);
    OBDal.getInstance().flush();
  }
}
