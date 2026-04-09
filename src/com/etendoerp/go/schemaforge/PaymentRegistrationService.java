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
import java.util.Date;
import java.util.List;
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
import org.openbravo.model.common.businesspartner.BusinessPartner;
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
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Invoice not found");
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

    // FIX #3 — currency compatibility: reject multi-currency until exchange rate UI is added
    Currency invoiceCurrency = invoice.getCurrency();
    Currency accountCurrency = account.getCurrency();
    if (invoiceCurrency != null && accountCurrency != null
        && !invoiceCurrency.getId().equals(accountCurrency.getId())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "The selected account currency (" + accountCurrency.getISOCode()
              + ") does not match the invoice currency (" + invoiceCurrency.getISOCode()
              + "). Multi-currency payments must be processed from Etendo Classic.");
    }

    List<FIN_PaymentScheduleDetail> pendingPSDs = findPendingPSDs(scheduleId);
    if (pendingPSDs.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "No pending payment schedule details found for this installment");
    }

    BusinessPartner bp = invoice.getBusinessPartner();
    Organization org = invoice.getOrganization();

    // FIX #2 — resolve the payment method valid for this account
    FIN_PaymentMethod paymentMethod = resolvePaymentMethod(account, invoice, isReceipt);
    if (paymentMethod == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "No payment method configured for this financial account. "
              + "Please configure a payment method in the financial account settings.");
    }

    String docTypeCode = isReceipt ? "ARR" : "APP";
    DocumentType docType = FIN_Utility.getDocumentType(org, docTypeCode);
    if (docType == null) {
      throw new OBException(
          "Document type for " + (isReceipt ? "Receipts (ARR)" : "Payments (APP)")
              + " not found for the organization.");
    }

    // FIX #1 — check accounting period is open
    checkPeriodOpen(invoice, docType, paymentDate);

    String docNo = FIN_Utility.getDocumentNo(docType, "FIN_Payment");
    VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(OBContext.getOBContext());
    RequestContext.get().setVariableSecureApp(vars);

    FIN_Payment payment = new AdvPaymentMngtDao().getNewPayment(
        isReceipt, org, docType, docNo, bp, paymentMethod, account,
        "0", paymentDate, "", invoiceCurrency, BigDecimal.ONE, amount);

    payment.setAmount(amount);
    // FIX #3 — use the proper helper instead of hardcoding 1:1
    FIN_AddPayment.setFinancialTransactionAmountAndRate(null, payment, BigDecimal.ONE, amount);
    OBDal.getInstance().save(payment);
    OBDal.getInstance().flush();

    linkPSDsToPayment(pendingPSDs, payment, amount);

    OBError result = FIN_AddPayment.processPayment(vars,
        new DalConnectionProvider(false), "P", payment, "");
    OBDal.getInstance().flush();

    if ("Error".equalsIgnoreCase(result.getType())) {
      throw new OBException(result.getMessage());
    }

    JSONObject data = new JSONObject();
    data.put("id", payment.getId());
    data.put("documentNo", payment.getDocumentNo());
    data.put("amount", payment.getAmount());
    data.put("status", result.getType());
    data.put("message", result.getMessage());

    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);

    return NeoResponse.created(wrapper);
  }

  // ─── ACCOUNTS: return accounts compatible with the invoice's org ───────────

  /**
   * Returns financial accounts in the natural org tree of the invoice.
   * Includes the default payment method per account for the UI to display.
   */
  static NeoResponse handleListAccounts(NeoContext context, boolean isReceipt) {
    String invoiceId = context.getRecordId();
    if (StringUtils.isBlank(invoiceId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invoice ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
        if (invoice == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Invoice not found");
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

        String allowProp = isReceipt
            ? FinAccPaymentMethod.PROPERTY_PAYINALLOW
            : FinAccPaymentMethod.PROPERTY_PAYOUTALLOW;

        JSONArray arr = new JSONArray();
        for (FIN_FinancialAccount acc : crit.list()) {
          // Only include accounts that have at least one valid payment method configured
          OBCriteria<FinAccPaymentMethod> methodCrit = OBDal.getInstance()
              .createCriteria(FinAccPaymentMethod.class);
          methodCrit.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, acc));
          methodCrit.add(Restrictions.eq(allowProp, Boolean.TRUE));
          methodCrit.setMaxResults(1);
          List<FinAccPaymentMethod> methods = methodCrit.list();
          if (methods.isEmpty()) {
            continue; // skip accounts with no valid payment methods
          }

          JSONObject item = new JSONObject();
          item.put("id", acc.getId());
          item.put("label", acc.getName());
          if (acc.getCurrency() != null) {
            item.put("currency", acc.getCurrency().getISOCode());
            item.put("currencyId", acc.getCurrency().getId());
          }
          // Expose default payment method name (informational, not sent back in registerPayment)
          FIN_PaymentMethod defaultMethod = methods.get(0).getPaymentMethod();
          if (defaultMethod != null) {
            item.put("defaultPaymentMethod", defaultMethod.getName());
          }
          arr.put(item);
        }

        JSONObject resp = new JSONObject();
        resp.put("items", arr);
        resp.put("totalCount", arr.length());
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

  // ─── PAYMENTS: list payments linked to an invoice ──────────────────────────

  @SuppressWarnings("unchecked")
  static NeoResponse handleListPayments(NeoContext context) {
    String invoiceId = context.getRecordId();
    if (StringUtils.isBlank(invoiceId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invoice ID is required");
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
          JSONObject item = new JSONObject();
          item.put("id", p.getId());
          item.put("documentNo", p.getDocumentNo());
          item.put("amount", p.getAmount());
          item.put("paymentDate", p.getPaymentDate() != null
              ? JsonUtils.createDateFormat().format(p.getPaymentDate()) : null);
          item.put("status", p.getStatus());
          item.put("receipt", p.isReceipt());
          if (p.getAccount() != null) {
            item.put("accountId", p.getAccount().getId());
            item.put("accountName", p.getAccount().getName());
            item.put("accountCurrency", p.getAccount().getCurrency() != null
                ? p.getAccount().getCurrency().getISOCode() : null);
          }
          if (p.getPaymentMethod() != null) {
            item.put("paymentMethod", p.getPaymentMethod().getName());
          }
          arr.put(item);
        }

        JSONObject data = new JSONObject();
        data.put("data", arr);
        data.put("count", arr.length());
        JSONObject wrapper = new JSONObject();
        wrapper.put("response", data);
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

  // ─── PRIVATE HELPERS ───────────────────────────────────────────────────────

  /**
   * FIX #1 — Validates that the accounting period is open for the given payment date.
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
        return; // org without accounting — no period restriction
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
      // Non-fatal: let the payment proceed and let Etendo's internal checks handle it
    }
  }

  /**
   * FIX #2 — Resolves the payment method to use, based on the financial account's configuration.
   *
   * Priority:
   *  1. Invoice's payment method, if configured for the account (FinAccPaymentMethod)
   *  2. First active payment method configured for the account
   *  3. null (caller must handle)
   */
  private static FIN_PaymentMethod resolvePaymentMethod(FIN_FinancialAccount account,
      Invoice invoice, boolean isReceipt) {

    FIN_PaymentMethod invoiceMethod = invoice.getPaymentMethod();
    if (invoiceMethod == null && invoice.getBusinessPartner() != null) {
      invoiceMethod = invoice.getBusinessPartner().getPaymentMethod();
    }

    String allowProp = isReceipt
        ? FinAccPaymentMethod.PROPERTY_PAYINALLOW
        : FinAccPaymentMethod.PROPERTY_PAYOUTALLOW;

    // Try invoice's own payment method first
    if (invoiceMethod != null) {
      OBCriteria<FinAccPaymentMethod> crit = OBDal.getInstance()
          .createCriteria(FinAccPaymentMethod.class);
      crit.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
      crit.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_PAYMENTMETHOD, invoiceMethod));
      crit.add(Restrictions.eq(allowProp, Boolean.TRUE));
      crit.setMaxResults(1);
      if (!crit.list().isEmpty()) {
        return invoiceMethod;
      }
    }

    // Fall back to first valid method for this account
    OBCriteria<FinAccPaymentMethod> fallback = OBDal.getInstance()
        .createCriteria(FinAccPaymentMethod.class);
    fallback.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
    fallback.add(Restrictions.eq(allowProp, Boolean.TRUE));
    fallback.setMaxResults(1);
    List<FinAccPaymentMethod> methods = fallback.list();
    if (!methods.isEmpty()) {
      return methods.get(0).getPaymentMethod();
    }

    return null;
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

  @SuppressWarnings("unchecked")
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
