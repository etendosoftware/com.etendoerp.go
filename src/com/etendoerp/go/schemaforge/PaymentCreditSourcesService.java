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

import static com.etendoerp.go.schemaforge.PaymentRegistrationService.KEY_KIND;
import static com.etendoerp.go.schemaforge.PaymentRegistrationService.KEY_PAYMENT_ID;
import static com.etendoerp.go.schemaforge.PaymentRegistrationService.KEY_PSD_ID;
import static com.etendoerp.go.schemaforge.PaymentRegistrationService.KEY_RECEIPT;
import static com.etendoerp.go.schemaforge.PaymentRegistrationService.KIND_ABONO;
import static com.etendoerp.go.schemaforge.PaymentRegistrationService.KIND_CREDIT;
import static com.etendoerp.go.schemaforge.PaymentRegistrationService.MSG_INVOICE_ID_REQUIRED;
import static com.etendoerp.go.schemaforge.PaymentRegistrationService.MSG_INVOICE_NOT_FOUND;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FIN_Payment_Credit;
import org.openbravo.service.json.JsonUtils;

/**
 * Consumable funding sources for an invoice's business partner: accumulated credit
 * (generatedCredit minus usedCredit) and pending credit-memo / return payment-schedule
 * details ("abono"). Split out of {@link PaymentRegistrationService} to keep that class
 * under the method-count limit (Sonar S1200).
 */
final class PaymentCreditSourcesService {

  private static final Logger log = LogManager.getLogger(PaymentCreditSourcesService.class);

  /** Body field on {@code invoiceCreditSources}: the draft being edited, so its own consumption
   *  is added back into each source's {@code avail} and its already-used abono PSDs are relisted. */
  private static final String FIELD_EDIT_PAYMENT_ID = "editPaymentId";

  private PaymentCreditSourcesService() {
  }

  /**
   * Lists the consumable funding sources for the invoice's business partner:
   *   - 'abono'  : pending credit-memo / return payment-schedule details (amount &lt; 0)
   *   - 'credit' : available accumulated credit lines (generatedCredit minus usedCredit)
   */
  static NeoResponse handleListCreditSources(NeoContext context, boolean isReceipt) {
    String invoiceId = context.getRecordId();
    if (StringUtils.isBlank(invoiceId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_INVOICE_ID_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
        if (invoice == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_INVOICE_NOT_FOUND);
        }
        if (invoice.getBusinessPartner() == null) {
          return PaymentRegistrationService.itemsResponse(new JSONArray());
        }
        String bpId = invoice.getBusinessPartner().getId();
        // Editing a draft: add its own consumption back in, so sources it is already using
        // show their "as if this draft didn't exist" availability and stay in the list even
        // if fully consumed by it — letting the modal re-check them.
        String editPaymentId = context.getRequestBody() != null
            ? context.getRequestBody().optString(FIELD_EDIT_PAYMENT_ID, null) : null;
        List<DatedSource> sources = new ArrayList<>();
        collectAbonoSources(sources, bpId, invoiceId, isReceipt, editPaymentId);
        collectAccumulatedCredit(sources, bpId, isReceipt, editPaymentId);
        // Merge both kinds into a single list ordered by each row's own date — invoice
        // date for saldo a favor (abono), payment date for credit — most recent first.
        // The two kinds are NOT grouped separately; they interleave by date. Reversing
        // must happen INSIDE nullsLast (reverseOrder), not around the whole comparator —
        // wrapping .reversed() around nullsLast(...) flips its null handling too, sending
        // null dates first instead of last.
        sources.sort(Comparator.comparing(
            (DatedSource s) -> s.date, Comparator.nullsLast(Comparator.reverseOrder())));
        JSONArray arr = new JSONArray();
        for (DatedSource s : sources) {
          arr.put(s.item);
        }
        return PaymentRegistrationService.itemsResponse(arr);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error listing credit sources for invoice {}: {}", invoiceId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to list credit sources");
    }
  }

  /** Pairs a credit-source JSON item with the raw date used to sort it against the other kind. */
  private static final class DatedSource {
    private final Date date;
    private final JSONObject item;

    private DatedSource(Date date, JSONObject item) {
      this.date = date;
      this.item = item;
    }
  }

  /**
   * Collects pending credit-memo / return PSDs (negative amount) of the BP, plus — when editing a
   * draft ({@code editPaymentId} present) — any such PSD that draft ALREADY consumed (its
   * {@code paymentDetails} is no longer null, so it would otherwise vanish from this list once
   * used), so the edit modal can keep showing and re-checking it.
   */
  private static void collectAbonoSources(List<DatedSource> sources, String bpId, String invoiceId,
      boolean isReceipt, String editPaymentId) throws Exception {
    String hql = "select psd from FIN_Payment_ScheduleDetail psd "
        + "where psd.invoicePaymentSchedule.invoice.businessPartner.id = :bp "
        + "and psd.invoicePaymentSchedule.invoice.salesTransaction = :receipt "
        + "and psd.paymentDetails is null and psd.amount < 0 "
        + "and psd.invoicePaymentSchedule.invoice.id <> :inv "
        + "order by psd.invoicePaymentSchedule.invoice.invoiceDate desc";
    List<FIN_PaymentScheduleDetail> abonos = OBDal.getInstance().getSession()
        .createQuery(hql, FIN_PaymentScheduleDetail.class)
        .setParameter("bp", bpId)
        .setParameter(KEY_RECEIPT, isReceipt)
        .setParameter("inv", invoiceId)
        .setMaxResults(50)
        .list();
    for (FIN_PaymentScheduleDetail psd : abonos) {
      addAbonoSource(sources, psd);
    }
    if (StringUtils.isNotBlank(editPaymentId)) {
      for (FIN_PaymentScheduleDetail psd : abonosUsedByDraft(editPaymentId)) {
        addAbonoSource(sources, psd);
      }
    }
  }

  /** Credit-memo / return PSDs (negative amount) already linked to the draft being edited. */
  private static List<FIN_PaymentScheduleDetail> abonosUsedByDraft(String editPaymentId) {
    String hql = "select psd from FIN_Payment_ScheduleDetail psd "
        + "where psd.paymentDetails.finPayment.id = :pay and psd.amount < 0 "
        + "and psd.invoicePaymentSchedule is not null";
    return OBDal.getInstance().getSession()
        .createQuery(hql, FIN_PaymentScheduleDetail.class)
        .setParameter("pay", editPaymentId)
        .list();
  }

  private static void addAbonoSource(List<DatedSource> sources, FIN_PaymentScheduleDetail psd) throws JSONException {
    Invoice ncInv = psd.getInvoicePaymentSchedule().getInvoice();
    JSONObject item = new JSONObject();
    item.put("id", psd.getId());
    item.put(KEY_KIND, KIND_ABONO);
    item.put(KEY_PSD_ID, psd.getId());
    item.put("doc", ncInv.getDocumentNo());
    item.put("date", ncInv.getInvoiceDate() != null
        ? JsonUtils.createDateFormat().format(ncInv.getInvoiceDate()) : null);
    item.put("note", ncInv.getDocumentType() != null ? ncInv.getDocumentType().getName() : "");
    item.put("avail", psd.getAmount().abs());
    sources.add(new DatedSource(ncInv.getInvoiceDate(), item));
  }

  /**
   * Collects accumulated-credit payments of the BP with available credit (generated minus used),
   * plus — when editing a draft ({@code editPaymentId} present) — that draft's own consumption
   * added back into each source's {@code avail} (so it shows "as if this draft didn't exist" and
   * stays listed even if fully consumed by it, letting the modal re-check it).
   */
  private static void collectAccumulatedCredit(List<DatedSource> sources, String bpId,
      boolean isReceipt, String editPaymentId) throws Exception {
    boolean editing = StringUtils.isNotBlank(editPaymentId);
    // While editing, the strict ">0" filter would hide a source THIS draft fully consumed —
    // fetch unfiltered and apply the (draft-adjusted) avail>0 check in Java instead.
    String hql = "select p from FIN_Payment p "
        + "where p.businessPartner.id = :bp and p.receipt = :receipt "
        + (editing ? "" : "and (coalesce(p.generatedCredit, 0) - coalesce(p.usedCredit, 0)) > 0 ")
        + "order by p.paymentDate desc";
    List<FIN_Payment> credits = OBDal.getInstance().getSession()
        .createQuery(hql, FIN_Payment.class)
        .setParameter("bp", bpId)
        .setParameter(KEY_RECEIPT, isReceipt)
        .setMaxResults(50)
        .list();
    for (FIN_Payment src : credits) {
      BigDecimal avail = PaymentRegistrationService.nullToZero(src.getGeneratedCredit())
          .subtract(PaymentRegistrationService.nullToZero(src.getUsedCredit()));
      if (editing) {
        avail = avail.add(creditUsedByDraft(editPaymentId, src));
      }
      if (avail.signum() <= 0) {
        // Defensive: the HQL already excludes fully-consumed credit, but never
        // expose a zero/negative-availability row if one slips through.
        continue;
      }
      JSONObject item = new JSONObject();
      item.put("id", src.getId());
      item.put(KEY_KIND, KIND_CREDIT);
      item.put(KEY_PAYMENT_ID, src.getId());
      item.put("doc", src.getDocumentNo());
      item.put("date", src.getPaymentDate() != null
          ? JsonUtils.createDateFormat().format(src.getPaymentDate()) : null);
      item.put("note", src.getDescription());
      item.put("avail", avail);
      sources.add(new DatedSource(src.getPaymentDate(), item));
    }
  }

  /** Amount of {@code source}'s credit the draft {@code editPaymentId} currently consumes (0 if none). */
  private static BigDecimal creditUsedByDraft(String editPaymentId, FIN_Payment source) {
    OBCriteria<FIN_Payment_Credit> crit = OBDal.getInstance().createCriteria(FIN_Payment_Credit.class);
    crit.add(Restrictions.eq(FIN_Payment_Credit.PROPERTY_PAYMENT + ".id", editPaymentId));
    crit.add(Restrictions.eq(FIN_Payment_Credit.PROPERTY_CREDITPAYMENTUSED, source));
    crit.setMaxResults(1);
    FIN_Payment_Credit link = (FIN_Payment_Credit) crit.uniqueResult();
    return link != null ? PaymentRegistrationService.nullToZero(link.getAmount()) : BigDecimal.ZERO;
  }
}
