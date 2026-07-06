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
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.advpaymentmngt.dao.AdvPaymentMngtDao;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBDateUtils;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.service.json.JsonUtils;

import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;

/**
 * Shared payment registration logic for both sales-invoice and purchase-invoice handlers.
 *
 * Adds three critical validations missing from the original handlers:
 *   1. Accounting period open check (FIN_Utility.isPeriodOpen)
 *   2. Payment method resolution per financial account (FinAccPaymentMethod)
 *   3. Currency compatibility check + proper financial transaction amounts
 */
final class PaymentRegistrationService {

  private static final Logger log = LogManager.getLogger(PaymentRegistrationService.class);

  // Error messages — package-visible: shared with PisPaymentService.
  static final String MSG_INVOICE_NOT_FOUND = "Invoice not found";
  static final String MSG_INVOICE_ID_REQUIRED = "Invoice ID is required";
  private static final String MSG_NO_PENDING_PSD =
      "No pending payment schedule details found for this installment";

  // JSON response keys
  private static final String KEY_DOCUMENT_NO = "documentNo";
  private static final String KEY_AMOUNT = "amount";
  // Package-visible: shared with PisPaymentService.
  static final String KEY_STATUS = "status";
  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";
  private static final String KEY_ITEMS = "items";
  private static final String KEY_TOTAL_COUNT = "totalCount";
  private static final String KEY_RECEIPT = "receipt";
  private static final String KEY_LABEL = "label";

  // PIS (bank transfer via Salt Edge) — only the flag used by this class' own registration flow;
  // the rest of the PIS request/response keys live in PisPaymentService.
  private static final String FIELD_PIS = "pis";
  private static final String KEY_VIA_PIS = "viaPis";

  // OBError type returned by FIN_AddPayment.processPayment on failure
  private static final String STATUS_ERROR = "Error";

  private PaymentRegistrationService() {
  }

  // ─── MAIN: register payment ────────────────────────────────────────────────

  /**
   * Creates and processes a payment against an invoice installment.
   *
   * @param invoiceId  the invoice record ID
   * @param scheduleId the FIN_PaymentSchedule ID (installment)
   * @param strAmount  the payment amount as string
   * @param strDate    the payment date in JsonUtils format (yyyy-MM-dd)
   * @param accountId  the FIN_FinancialAccount ID
   * @param isReceipt  true for sales invoices (ARR), false for purchase invoices (APP)
   */
  static NeoResponse doRegisterPayment(String invoiceId, String scheduleId,
      String strAmount, String strDate, String accountId, boolean isReceipt) throws Exception {

    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    if (invoice == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_INVOICE_NOT_FOUND);
    }

    FIN_PaymentSchedule schedule = OBDal.getInstance().get(FIN_PaymentSchedule.class, scheduleId);
    if (schedule == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Payment schedule not found");
    }

    BigDecimal amount;
    try {
      amount = new BigDecimal(strAmount);
    } catch (NumberFormatException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Invalid amount format: " + strAmount);
    }

    Date paymentDate;
    try {
      paymentDate = JsonUtils.createDateFormat().parse(strDate);
    } catch (ParseException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Invalid date format: " + strDate);
    }

    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Financial account not found");
    }

    try {
      FIN_Payment payment = registerPaymentCore(invoice, schedule, amount, paymentDate, account,
          isReceipt);
      return builtPaymentResponse(payment);
    } catch (OBException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Core payment creation + processing against an invoice installment, returning the persisted,
   * processed {@link FIN_Payment}. Processing auto-creates the FIN_FinaccTransaction in the
   * account (type BPD/BPW per ARR/APP). Throws {@link OBException} on any business validation
   * failure (currency mismatch, no pending installment, no payment method, missing doc type,
   * closed period, processing error). Shared by {@link #doRegisterPayment} (sales/purchase invoice
   * handlers) and the bank-reconciliation "pay invoice" flow. Callers pass already-loaded,
   * non-null entities.
   */
  static FIN_Payment registerPaymentCore(Invoice invoice, FIN_PaymentSchedule schedule,
      BigDecimal amount, Date paymentDate, FIN_FinancialAccount account, boolean isReceipt)
      throws Exception {

    assertCurrencyMatch(invoice.getCurrency(), account.getCurrency());

    List<FIN_PaymentScheduleDetail> pendingPSDs = findPendingPSDs(schedule.getId());
    if (pendingPSDs.isEmpty()) {
      throw new OBException(MSG_NO_PENDING_PSD);
    }

    Organization org = invoice.getOrganization();

    FIN_PaymentMethod paymentMethod = resolvePaymentMethod(account, invoice, isReceipt);
    if (paymentMethod == null) {
      throw new OBException("No payment method configured for this financial account. "
          + "Please configure a payment method in the financial account settings.");
    }

    DocumentType docType = resolveArApDocType(org, isReceipt);
    checkPeriodOpen(invoice, docType, paymentDate);

    FIN_Payment payment = createDraftPayment(new AdvPaymentMngtDao(), isReceipt, invoice,
        paymentMethod, account, paymentDate, amount);
    linkPSDsToPayment(pendingPSDs, payment, amount);
    processOrThrow(payment);
    return payment;
  }

  // ─── ACCOUNTS: return accounts compatible with the invoice's org ───────────

  /**
   * Returns financial accounts in the natural org tree of the invoice.
   * Includes the default payment method per account for the UI to display.
   */
  static NeoResponse handleListAccounts(NeoContext context, boolean isReceipt) {
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

        OrganizationStructureProvider osp = OBContext.getOBContext()
            .getOrganizationStructureProvider(invoice.getClient().getId());
        Set<String> naturalTree = osp.getNaturalTree(invoice.getOrganization().getId());

        OBCriteria<FIN_FinancialAccount> crit = OBDal.getInstance()
            .createCriteria(FIN_FinancialAccount.class);
        crit.setFilterOnReadableOrganization(false);
        if (!naturalTree.isEmpty()) {
          crit.add(Restrictions.in(
              FIN_FinancialAccount.PROPERTY_ORGANIZATION + ".id", naturalTree));
        }
        crit.addOrderBy(FIN_FinancialAccount.PROPERTY_NAME, true);

        String allowProp = allowProperty(isReceipt);
        Currency invoiceCurrency = invoice.getCurrency();

        JSONArray arr = new JSONArray();
        for (FIN_FinancialAccount acc : crit.list()) {
          appendAccountItem(arr, acc, allowProp, invoiceCurrency);
        }
        JSONObject resp = new JSONObject();
        resp.put(KEY_ITEMS, arr);
        resp.put(KEY_TOTAL_COUNT, arr.length());
        FIN_PaymentMethod invoiceMethod = resolveInvoiceMethod(invoice);
        if (invoiceMethod != null) {
          resp.put("defaultMethodId", invoiceMethod.getId());
        }
        String bpAccountId = businessPartnerAccountId(invoice, isReceipt);
        if (bpAccountId != null) {
          resp.put("bpPreferredAccountId", bpAccountId);
        }
        return new NeoResponse(200, resp);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error listing accounts for invoice {}: {}", invoiceId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to list accounts for invoice");
    }
  }

  /**
   * Appends one account item if it has at least one valid payment method for the direction
   * and its currency matches the invoice's (accounts with no currency are always kept).
   */
  private static void appendAccountItem(JSONArray arr, FIN_FinancialAccount acc, String allowProp,
      Currency invoiceCurrency) throws Exception {
    if (acc.getCurrency() != null && invoiceCurrency != null
        && !acc.getCurrency().getId().equals(invoiceCurrency.getId())) {
      return;
    }
    OBCriteria<FinAccPaymentMethod> methodCrit = OBDal.getInstance()
        .createCriteria(FinAccPaymentMethod.class);
    methodCrit.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, acc));
    methodCrit.add(Restrictions.eq(allowProp, Boolean.TRUE));
    List<FinAccPaymentMethod> methods = methodCrit.list();
    if (methods.isEmpty()) {
      return;
    }
    JSONObject item = new JSONObject();
    item.put("id", acc.getId());
    item.put(KEY_LABEL, acc.getName());
    if (acc.getCurrency() != null) {
      item.put("currency", acc.getCurrency().getISOCode());
      item.put("currencyId", acc.getCurrency().getId());
    }
    item.put("psd2Connected", BankIntegrationConstants.FA_CONNECTION_STATUS_CONNECTED
        .equals(acc.getPSD2ConnectionStatus()));
    if (acc.getPSD2CardNumber() != null) {
      item.put("maskedPan", acc.getPSD2CardNumber());
    }
    JSONArray methodIds = new JSONArray();
    JSONArray defaultForMethodIds = new JSONArray();
    for (FinAccPaymentMethod fapm : methods) {
      if (fapm.getPaymentMethod() == null) {
        continue;
      }
      methodIds.put(fapm.getPaymentMethod().getId());
      if (Boolean.TRUE.equals(fapm.isDefault())) {
        defaultForMethodIds.put(fapm.getPaymentMethod().getId());
      }
    }
    item.put("paymentMethodIds", methodIds);
    item.put("defaultForMethodIds", defaultForMethodIds);
    FIN_PaymentMethod defaultMethod = methods.get(0).getPaymentMethod();
    if (defaultMethod != null) {
      item.put("defaultPaymentMethod", defaultMethod.getName());
    }
    arr.put(item);
  }

  /**
   * The business partner's preferred financial account for the given direction
   * (its "Account" for receipts, "PO Financial Account" for payments), mirroring
   * Classic's {@code AddPaymentDefaultValuesHandler} priority before the
   * {@code FinAccPaymentMethod.default} flag.
   */
  private static String businessPartnerAccountId(Invoice invoice, boolean isReceipt) {
    if (invoice.getBusinessPartner() == null) {
      return null;
    }
    FIN_FinancialAccount bpAccount = isReceipt
        ? invoice.getBusinessPartner().getAccount()
        : invoice.getBusinessPartner().getPOFinancialAccount();
    return bpAccount != null ? bpAccount.getId() : null;
  }

  // ─── PAYMENTS: list payments linked to an invoice ──────────────────────────

  static NeoResponse handleListPayments(NeoContext context) {
    String invoiceId = context.getRecordId();
    if (StringUtils.isBlank(invoiceId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_INVOICE_ID_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      try {
        String hql = "select distinct pd.finPayment "
            + "from FIN_Payment_Detail pd "
            + "join pd.fINPaymentScheduleDetailList psd "
            + "where psd.invoicePaymentSchedule.invoice.id = :invoiceId "
            + "order by pd.finPayment.paymentDate desc";
        List<FIN_Payment> invoicePayments = OBDal.getInstance().getSession()
            .createQuery(hql, FIN_Payment.class)
            .setParameter("invoiceId", invoiceId)
            .setMaxResults(50)
            .list();

        JSONArray arr = new JSONArray();
        for (FIN_Payment p : invoicePayments) {
          arr.put(paymentListItem(p));
        }

        JSONObject data = new JSONObject();
        data.put(KEY_DATA, arr);
        data.put("count", arr.length());
        JSONObject wrapper = new JSONObject();
        wrapper.put(KEY_RESPONSE, data);
        return new NeoResponse(200, wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error listing payments for invoice {}: {}", invoiceId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to list invoice payments");
    }
  }

  private static JSONObject paymentListItem(FIN_Payment p) throws Exception {
    JSONObject item = new JSONObject();
    item.put("id", p.getId());
    item.put(KEY_DOCUMENT_NO, p.getDocumentNo());
    item.put(KEY_AMOUNT, p.getAmount());
    item.put("paymentDate", p.getPaymentDate() != null
        ? JsonUtils.createDateFormat().format(p.getPaymentDate()) : null);
    item.put(KEY_STATUS, p.getStatus());
    item.put("processed", p.isProcessed());
    item.put(KEY_RECEIPT, p.isReceipt());
    if (p.getAccount() != null) {
      item.put("accountId", p.getAccount().getId());
      item.put("accountName", p.getAccount().getName());
      item.put("accountCurrency", p.getAccount().getCurrency() != null
          ? p.getAccount().getCurrency().getISOCode() : null);
    }
    if (p.getPaymentMethod() != null) {
      item.put("paymentMethod", p.getPaymentMethod().getName());
    }
    // A linked PSD2_PIS_PAYMENT row means this payment was initiated through the Salt Edge PIS
    // flow (this popup), not just a manually-recorded bank transfer — surfaced in the SPA's
    // payment history as a "Realizado vía PSD2" badge. PisPayment is a plain DAL entity (no
    // PSD2-module method needed), so this is queried directly here.
    item.put(KEY_VIA_PIS, PisPaymentService.hasLinkedPisPayment(p));
    return item;
  }

  // ─── PAYMENT METHODS: list methods valid for the invoice's accounts ────────

  /**
   * Lists the distinct payment methods configured (in the invoice's direction)
   * for financial accounts in the natural org tree of the invoice.
   */
  static NeoResponse handleListPaymentMethods(NeoContext context, boolean isReceipt) {
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
        Set<String> naturalTree = OBContext.getOBContext()
            .getOrganizationStructureProvider(invoice.getClient().getId())
            .getNaturalTree(invoice.getOrganization().getId());

        OBCriteria<FinAccPaymentMethod> crit = OBDal.getInstance()
            .createCriteria(FinAccPaymentMethod.class);
        crit.setFilterOnReadableOrganization(false);
        crit.add(Restrictions.eq(allowProperty(isReceipt), Boolean.TRUE));

        Map<String, String> distinct = new LinkedHashMap<>();
        for (FinAccPaymentMethod fapm : crit.list()) {
          collectMethodInTree(distinct, fapm, naturalTree);
        }

        JSONArray arr = new JSONArray();
        for (Map.Entry<String, String> e : distinct.entrySet()) {
          JSONObject item = new JSONObject();
          item.put("id", e.getKey());
          item.put(KEY_LABEL, e.getValue());
          arr.put(item);
        }
        return itemsResponse(arr);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error listing payment methods for invoice {}: {}", invoiceId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to list payment methods");
    }
  }

  /** Adds the method behind {@code fapm} to {@code distinct} when its account is in the org tree. */
  private static void collectMethodInTree(Map<String, String> distinct, FinAccPaymentMethod fapm,
      Set<String> naturalTree) {
    FIN_FinancialAccount acc = fapm.getAccount();
    if (acc == null || acc.getOrganization() == null
        || (!naturalTree.isEmpty() && !naturalTree.contains(acc.getOrganization().getId()))) {
      return;
    }
    FIN_PaymentMethod pm = fapm.getPaymentMethod();
    if (pm != null && !distinct.containsKey(pm.getId())) {
      distinct.put(pm.getId(), pm.getName());
    }
  }

  // ─── CREDIT SOURCES: consumable credit / saldo a favor of the BP ───────────

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
          return itemsResponse(new JSONArray());
        }
        String bpId = invoice.getBusinessPartner().getId();
        List<DatedSource> sources = new ArrayList<>();
        collectAbonoSources(sources, bpId, invoiceId, isReceipt);
        collectAccumulatedCredit(sources, bpId, isReceipt);
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
        return itemsResponse(arr);
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

  /** Collects pending credit-memo / return PSDs (negative amount) of the BP. */
  private static void collectAbonoSources(List<DatedSource> sources, String bpId, String invoiceId,
      boolean isReceipt) throws Exception {
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
      Invoice ncInv = psd.getInvoicePaymentSchedule().getInvoice();
      JSONObject item = new JSONObject();
      item.put("id", psd.getId());
      item.put("kind", "abono");
      item.put("psdId", psd.getId());
      item.put("doc", ncInv.getDocumentNo());
      item.put("date", ncInv.getInvoiceDate() != null
          ? JsonUtils.createDateFormat().format(ncInv.getInvoiceDate()) : null);
      item.put("note", ncInv.getDocumentType() != null ? ncInv.getDocumentType().getName() : "");
      item.put("avail", psd.getAmount().abs());
      sources.add(new DatedSource(ncInv.getInvoiceDate(), item));
    }
  }

  /** Collects accumulated-credit payments of the BP with available credit (generated minus used). */
  private static void collectAccumulatedCredit(List<DatedSource> sources, String bpId,
      boolean isReceipt) throws Exception {
    String hql = "select p from FIN_Payment p "
        + "where p.businessPartner.id = :bp and p.receipt = :receipt "
        + "and (coalesce(p.generatedCredit, 0) - coalesce(p.usedCredit, 0)) > 0 "
        + "order by p.paymentDate desc";
    List<FIN_Payment> credits = OBDal.getInstance().getSession()
        .createQuery(hql, FIN_Payment.class)
        .setParameter("bp", bpId)
        .setParameter(KEY_RECEIPT, isReceipt)
        .setMaxResults(50)
        .list();
    for (FIN_Payment src : credits) {
      BigDecimal avail = nullToZero(src.getGeneratedCredit()).subtract(nullToZero(src.getUsedCredit()));
      if (avail.signum() <= 0) {
        // Defensive: the HQL already excludes fully-consumed credit, but never
        // expose a zero/negative-availability row if one slips through.
        continue;
      }
      JSONObject item = new JSONObject();
      item.put("id", src.getId());
      item.put("kind", "credit");
      item.put("paymentId", src.getId());
      item.put("doc", src.getDocumentNo());
      item.put("date", src.getPaymentDate() != null
          ? JsonUtils.createDateFormat().format(src.getPaymentDate()) : null);
      item.put("note", src.getDescription());
      item.put("avail", avail);
      sources.add(new DatedSource(src.getPaymentDate(), item));
    }
  }

  // ─── ADVANCED: draft/confirm + payment method + credit consumption ─────────

  /**
   * Two-step modal payment registration. Mirrors the proven Add-Payment sequence:
   * create the payment, consume the selected credit/abono PSDs as negative details,
   * apply the cash-funded portion to the invoice installment, and — when confirming —
   * register any over-payment as generated credit (or refund) and process it.
   *
   * Body: {@code scheduleId, actual_payment, payment_date, fin_financial_account_id,
   * fin_paymentmethod_id?, process('draft'|'confirm'), creditSources[], overpaymentAction?,
   * pis?}. On {@code process='draft'} the payment is created but NOT processed (stays DR).
   *
   * <p>When {@code pis=true} the payment is registered as a real bank transfer through the
   * PSD2 / Salt Edge PIS integration: the {@link FIN_Payment} is created, linked and PROCESSED to
   * status {@code PPM} ("Payment Made") — applied to the invoice but with NO
   * {@code FIN_Finacc_Transaction} yet (the transfer method's Automatic flags are cleared by §2b).
   * The bank transaction is created only once Salt Edge confirms execution, by the PSD2 module's
   * own {@code PisPaymentCallback}. See {@link PisPaymentService#applyOverpaymentAndInitiatePis}.
   */
  static NeoResponse doRegisterPaymentAdvanced(String invoiceId, JSONObject body, boolean isReceipt)
      throws Exception {

    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    if (invoice == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_INVOICE_NOT_FOUND);
    }
    String scheduleId = body.optString("scheduleId", null);
    if (OBDal.getInstance().get(FIN_PaymentSchedule.class, scheduleId) == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Payment schedule not found");
    }
    BigDecimal cash;
    try {
      cash = new BigDecimal(body.optString("actual_payment", ""));
    } catch (NumberFormatException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid amount format");
    }
    Date paymentDate;
    try {
      paymentDate = JsonUtils.createDateFormat().parse(body.optString("payment_date", ""));
    } catch (ParseException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid date format");
    }
    FIN_FinancialAccount account = OBDal.getInstance()
        .get(FIN_FinancialAccount.class, body.optString("fin_financial_account_id", null));
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Financial account not found");
    }

    boolean doProcess = !"draft".equalsIgnoreCase(body.optString("process", "confirm"));
    String overpaymentAction = body.optString("overpaymentAction", null);
    boolean pis = body.optBoolean(FIELD_PIS, false);

    assertCurrencyMatch(invoice.getCurrency(), account.getCurrency());

    Organization org = invoice.getOrganization();
    FIN_PaymentMethod paymentMethod = resolveRequestedMethod(
        account, invoice, isReceipt, body.optString("fin_paymentmethod_id", null));
    if (paymentMethod == null) {
      throw new OBException("No payment method configured for this financial account.");
    }
    DocumentType docType = resolveArApDocType(org, isReceipt);
    checkPeriodOpen(invoice, docType, paymentDate);

    JSONObject pisInput = null;
    if (pis) {
      PisPaymentService.validatePisEligibility(account, paymentMethod, invoice);
      pisInput = PisPaymentService.extractPisInput(body);
    }

    AdvPaymentMngtDao dao = new AdvPaymentMngtDao();
    FIN_Payment payment = createDraftPayment(dao, isReceipt, invoice,
        paymentMethod, account, paymentDate, cash);

    BigDecimal totalFunded = PaymentCreditConsumer.consume(payment, body.optJSONArray("creditSources"));

    List<FIN_PaymentScheduleDetail> pendingPSDs = findPendingPSDs(scheduleId);
    if (pendingPSDs.isEmpty()) {
      throw new OBException(MSG_NO_PENDING_PSD);
    }
    BigDecimal funds = cash.add(totalFunded);
    BigDecimal invoiceApplied = sumAmounts(pendingPSDs).min(funds).max(BigDecimal.ZERO);
    linkPSDsToPayment(pendingPSDs, payment, invoiceApplied);
    OBDal.getInstance().save(payment);
    OBDal.getInstance().flush();

    // Draft: created and linked but NOT processed — no transaction, no accounting.
    if (doProcess) {
      if (pis) {
        return PisPaymentService.applyOverpaymentAndInitiatePis(payment, dao, org, funds,
            invoiceApplied, pisInput, overpaymentAction);
      }
      applyOverpaymentAndProcess(payment, dao, org, funds, invoiceApplied, overpaymentAction);
    }
    return builtPaymentResponse(payment);
  }

  /** Processes a previously-saved draft payment (Borrador → Depositado). */
  static NeoResponse confirmDraftPayment(String paymentId) throws Exception {
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    if (payment == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Payment not found");
    }
    processOrThrow(payment);
    return builtPaymentResponse(payment);
  }

  // ─── ADVANCED HELPERS ──────────────────────────────────────────────────────

  /** Registers the over-payment (generated credit or refund) and processes the payment. */
  private static void applyOverpaymentAndProcess(FIN_Payment payment, AdvPaymentMngtDao dao,
      Organization org, BigDecimal funds, BigDecimal invoiceApplied, String overpaymentAction)
      throws Exception {
    VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(OBContext.getOBContext());
    RequestContext.get().setVariableSecureApp(vars);
    DalConnectionProvider conn = new DalConnectionProvider(false);

    BigDecimal leftover = funds.subtract(invoiceApplied);
    boolean overpaid = leftover.compareTo(BigDecimal.ZERO) > 0;
    if (overpaid) {
      FIN_PaymentScheduleDetail creditPsd = dao.getNewPaymentScheduleDetail(org, leftover);
      dao.getNewPaymentDetail(payment, creditPsd, leftover, BigDecimal.ZERO, false, null);
      OBDal.getInstance().flush();
    }

    failOnError(FIN_AddPayment.processPayment(vars, conn, "P", payment, ""));
    OBDal.getInstance().flush();

    if (overpaid && "refund".equalsIgnoreCase(overpaymentAction)) {
      FIN_Payment refund = FIN_AddPayment.createRefundPayment(conn, vars, payment,
          leftover.negate(), null);
      failOnError(FIN_AddPayment.processPayment(vars, conn, "P", refund, "",
          "(" + payment.getId() + ")"));
      OBDal.getInstance().flush();
    }
  }

  /**
   * Resolves the payment method: the explicitly-requested one when valid for the
   * account, otherwise the invoice/account default (see {@link #resolvePaymentMethod}).
   */
  private static FIN_PaymentMethod resolveRequestedMethod(FIN_FinancialAccount account,
      Invoice invoice, boolean isReceipt, String requestedId) {
    if (StringUtils.isNotBlank(requestedId)) {
      FIN_PaymentMethod requested = OBDal.getInstance().get(FIN_PaymentMethod.class, requestedId);
      if (requested != null && isMethodAllowed(account, requested, allowProperty(isReceipt))) {
        return requested;
      }
    }
    return resolvePaymentMethod(account, invoice, isReceipt);
  }

  // ─── SHARED HELPERS ─────────────────────────────────────────────────────────

  /** Builds the standard {response:{data:{id,documentNo,amount,status,processed}}} envelope. */
  private static NeoResponse builtPaymentResponse(FIN_Payment payment) throws Exception {
    return wrapCreatedData(basePaymentData(payment));
  }

  /** Package-visible: also used by {@link PisPaymentService#applyOverpaymentAndInitiatePis}. */
  static JSONObject basePaymentData(FIN_Payment payment) throws Exception {
    JSONObject data = new JSONObject();
    data.put("id", payment.getId());
    data.put(KEY_DOCUMENT_NO, payment.getDocumentNo());
    data.put(KEY_AMOUNT, payment.getAmount());
    data.put(KEY_STATUS, payment.getStatus());
    data.put("processed", payment.isProcessed());
    return data;
  }

  /** Package-visible: also used by {@link PisPaymentService#applyOverpaymentAndInitiatePis}. */
  static NeoResponse wrapCreatedData(JSONObject data) throws Exception {
    JSONObject responseData = new JSONObject();
    responseData.put(KEY_DATA, data);
    JSONObject wrapper = new JSONObject();
    wrapper.put(KEY_RESPONSE, responseData);
    return NeoResponse.created(wrapper);
  }

  /**
   * Builds the standard {items:[...], totalCount:n} listing envelope. Package-visible: also
   * used by {@link PisPaymentService}'s listing actions.
   */
  static NeoResponse itemsResponse(JSONArray arr) throws Exception {
    JSONObject resp = new JSONObject();
    resp.put(KEY_ITEMS, arr);
    resp.put(KEY_TOTAL_COUNT, arr.length());
    return new NeoResponse(200, resp);
  }

  /** Rejects multi-currency payments (no exchange-rate UI yet). */
  private static void assertCurrencyMatch(Currency invoiceCurrency, Currency accountCurrency) {
    if (invoiceCurrency != null && accountCurrency != null
        && !invoiceCurrency.getId().equals(accountCurrency.getId())) {
      throw new OBException("The selected account currency (" + accountCurrency.getISOCode()
          + ") does not match the invoice currency (" + invoiceCurrency.getISOCode()
          + "). Multi-currency payments must be processed from Etendo Classic.");
    }
  }

  /** Resolves the ARR (receipts) / APP (payments) document type for the org, or throws. */
  private static DocumentType resolveArApDocType(Organization org, boolean isReceipt) {
    DocumentType docType = FIN_Utility.getDocumentType(org, isReceipt ? "ARR" : "APP");
    if (docType == null) {
      throw new OBException("Document type for " + (isReceipt ? "Receipts (ARR)" : "Payments (APP)")
          + " not found for the organization.");
    }
    return docType;
  }

  /** Creates and persists a draft FIN_Payment (not processed yet) with its transaction amount. */
  private static FIN_Payment createDraftPayment(AdvPaymentMngtDao dao, boolean isReceipt,
      Invoice invoice, FIN_PaymentMethod paymentMethod, FIN_FinancialAccount account,
      Date paymentDate, BigDecimal amount) throws Exception {
    DocumentType docType = resolveArApDocType(invoice.getOrganization(), isReceipt);
    String docNo = FIN_Utility.getDocumentNo(docType, "FIN_Payment");
    VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(OBContext.getOBContext());
    RequestContext.get().setVariableSecureApp(vars);
    FIN_Payment payment = dao.getNewPayment(isReceipt, invoice.getOrganization(), docType, docNo,
        invoice.getBusinessPartner(), paymentMethod, account, "0", paymentDate, "",
        invoice.getCurrency(), BigDecimal.ONE, amount);
    payment.setAmount(amount);
    FIN_AddPayment.setFinancialTransactionAmountAndRate(null, payment, BigDecimal.ONE, amount);
    OBDal.getInstance().save(payment);
    OBDal.getInstance().flush();
    return payment;
  }

  /** Processes the payment with action "P" and throws on a business error. */
  private static void processOrThrow(FIN_Payment payment) throws Exception {
    VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(OBContext.getOBContext());
    RequestContext.get().setVariableSecureApp(vars);
    failOnError(FIN_AddPayment.processPayment(vars, new DalConnectionProvider(false),
        "P", payment, ""));
    OBDal.getInstance().flush();
  }

  /** Package-visible: also used by {@link PisPaymentService#applyOverpaymentAndInitiatePis}. */
  static void failOnError(OBError result) {
    if (STATUS_ERROR.equalsIgnoreCase(result.getType())) {
      throw new OBException(result.getMessage());
    }
  }

  private static String allowProperty(boolean isReceipt) {
    return isReceipt
        ? FinAccPaymentMethod.PROPERTY_PAYINALLOW
        : FinAccPaymentMethod.PROPERTY_PAYOUTALLOW;
  }

  private static BigDecimal nullToZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal sumAmounts(List<FIN_PaymentScheduleDetail> psds) {
    BigDecimal total = BigDecimal.ZERO;
    for (FIN_PaymentScheduleDetail psd : psds) {
      total = total.add(psd.getAmount());
    }
    return total;
  }

  /**
   * Validates that the accounting period is open for the given payment date.
   * Mirrors Classic's AddPaymentActionHandler check.
   */
  private static void checkPeriodOpen(Invoice invoice, DocumentType docType, Date paymentDate) {
    try {
      OrganizationStructureProvider osp = OBContext.getOBContext()
          .getOrganizationStructureProvider(invoice.getClient().getId());
      Organization legalEntity = osp.getLegalEntityOrBusinessUnit(invoice.getOrganization());

      boolean orgLegalWithAccounting = legalEntity != null
          && legalEntity.getOrganizationType() != null
          && legalEntity.getOrganizationType().isLegalEntityWithAccounting();

      if (!orgLegalWithAccounting) {
        return;
      }

      String docBaseType = docType != null ? docType.getDocumentCategory() : "";
      String strDate = OBDateUtils.formatDate(paymentDate);

      if (!FIN_Utility.isPeriodOpen(invoice.getClient().getId(), docBaseType,
          invoice.getOrganization().getId(), strDate)) {
        throw new OBException(OBMessageUtils.messageBD("PeriodNotAvailable"));
      }
    } catch (OBException e) {
      throw e;
    } catch (Exception e) {
      log.warn("Could not check period open for invoice {}: {}", invoice.getId(), e.getMessage());
    }
  }

  /**
   * Resolves the payment method to use, based on the financial account's configuration.
   * Priority: invoice's own method (if valid for the account), else first valid method, else null.
   */
  private static FIN_PaymentMethod resolvePaymentMethod(FIN_FinancialAccount account,
      Invoice invoice, boolean isReceipt) {

    FIN_PaymentMethod invoiceMethod = resolveInvoiceMethod(invoice);

    String allowProp = allowProperty(isReceipt);
    if (invoiceMethod != null && isMethodAllowed(account, invoiceMethod, allowProp)) {
      return invoiceMethod;
    }

    OBCriteria<FinAccPaymentMethod> fallback = OBDal.getInstance()
        .createCriteria(FinAccPaymentMethod.class);
    fallback.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
    fallback.add(Restrictions.eq(allowProp, Boolean.TRUE));
    fallback.setMaxResults(1);
    List<FinAccPaymentMethod> methods = fallback.list();
    return methods.isEmpty() ? null : methods.get(0).getPaymentMethod();
  }

  /** The invoice's own payment method, falling back to its business partner's. */
  private static FIN_PaymentMethod resolveInvoiceMethod(Invoice invoice) {
    FIN_PaymentMethod invoiceMethod = invoice.getPaymentMethod();
    if (invoiceMethod == null && invoice.getBusinessPartner() != null) {
      invoiceMethod = invoice.getBusinessPartner().getPaymentMethod();
    }
    return invoiceMethod;
  }

  /** True when {@code method} is configured for {@code account} in the given direction. */
  private static boolean isMethodAllowed(FIN_FinancialAccount account, FIN_PaymentMethod method,
      String allowProp) {
    OBCriteria<FinAccPaymentMethod> crit = OBDal.getInstance()
        .createCriteria(FinAccPaymentMethod.class);
    crit.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
    crit.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_PAYMENTMETHOD, method));
    crit.add(Restrictions.eq(allowProp, Boolean.TRUE));
    crit.setMaxResults(1);
    return !crit.list().isEmpty();
  }

  private static void linkPSDsToPayment(List<FIN_PaymentScheduleDetail> psds,
      FIN_Payment payment, BigDecimal amount) {
    BigDecimal remaining = amount;
    for (FIN_PaymentScheduleDetail psd : psds) {
      if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
        break;
      }
      BigDecimal assignAmount = remaining.min(psd.getAmount());
      FIN_AddPayment.updatePaymentDetail(psd, payment, assignAmount, false);
      remaining = remaining.subtract(assignAmount);
    }
  }

  private static List<FIN_PaymentScheduleDetail> findPendingPSDs(String scheduleId) {
    OBCriteria<FIN_PaymentScheduleDetail> criteria = OBDal.getInstance()
        .createCriteria(FIN_PaymentScheduleDetail.class);
    criteria.add(Restrictions.eq(
        FIN_PaymentScheduleDetail.PROPERTY_INVOICEPAYMENTSCHEDULE + ".id", scheduleId));
    criteria.add(Restrictions.isNull(FIN_PaymentScheduleDetail.PROPERTY_PAYMENTDETAILS));
    criteria.addOrderBy(FIN_PaymentScheduleDetail.PROPERTY_AMOUNT, false);
    return criteria.list();
  }
}
