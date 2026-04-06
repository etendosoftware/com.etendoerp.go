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

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.advpaymentmngt.dao.AdvPaymentMngtDao;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
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
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.service.json.JsonUtils;

/**
 * NeoHandler that registers a payment against a Sales Invoice installment.
 * Invoked as:
 *   POST /sws/neo/sales-invoice/header/{invoiceId}/action/registerPayment
 *
 * Required params: scheduleId, actual_payment, payment_date, fin_financial_account_id
 *
 * Resolves pending Payment Schedule Details from the given schedule,
 * creates a FIN_Payment, links it to the PSDs, and processes it.
 */
@Named("registerPaymentHandler")
public class RegisterPaymentHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(RegisterPaymentHandler.class);
  private static final String ACTION_NAME = "registerPayment";
  private static final String LIST_ACTION = "invoicePayments";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    String fieldName = context.getFieldName();

    // GET/POST invoicePayments — list payments linked to this invoice
    if (LIST_ACTION.equals(fieldName)) {
      return handleListPayments(context);
    }

    if (!ACTION_NAME.equals(fieldName)
        || !"POST".equals(context.getHttpMethod())) {
      return null;
    }

    String invoiceId = context.getRecordId();
    if (StringUtils.isBlank(invoiceId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invoice ID is required");
    }

    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Request body is required");
    }

    String scheduleId = body.optString("scheduleId", null);
    String strAmount = body.optString("actual_payment", null);
    String strDate = body.optString("payment_date", null);
    String accountId = body.optString("fin_financial_account_id", null);

    if (StringUtils.isBlank(scheduleId) || StringUtils.isBlank(strAmount)
        || StringUtils.isBlank(strDate) || StringUtils.isBlank(accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Missing required fields: scheduleId, actual_payment, payment_date, fin_financial_account_id");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        return doRegisterPayment(invoiceId, scheduleId, strAmount, strDate, accountId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Payment registration failed for invoice {}: {}", invoiceId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Error registering payment for invoice {}: {}", invoiceId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while registering the payment");
    }
  }

  private NeoResponse doRegisterPayment(String invoiceId, String scheduleId,
      String strAmount, String strDate, String accountId) throws Exception {

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
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid amount format: " + strAmount);
    }
    Date paymentDate;
    try {
      paymentDate = JsonUtils.createDateFormat().parse(strDate);
    } catch (java.text.ParseException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid date format: " + strDate);
    }
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Financial account not found");
    }

    List<FIN_PaymentScheduleDetail> pendingPSDs = findPendingPSDs(scheduleId);
    if (pendingPSDs.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "No pending payment schedule details found for this installment");
    }

    BusinessPartner bp = invoice.getBusinessPartner();
    Organization org = invoice.getOrganization();
    Currency currency = invoice.getCurrency();
    FIN_PaymentMethod paymentMethod = invoice.getPaymentMethod();
    if (paymentMethod == null && bp != null) {
      paymentMethod = bp.getPaymentMethod();
    }
    if (paymentMethod == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Payment method not found for the invoice or business partner");
    }

    DocumentType docType = FIN_Utility.getDocumentType(org, "ARR");
    if (docType == null) {
      throw new OBException("Document type for Receipts (ARR) not found for the organization.");
    }
    String docNo = FIN_Utility.getDocumentNo(docType, "FIN_Payment");

    VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(OBContext.getOBContext());
    RequestContext.get().setVariableSecureApp(vars);

    FIN_Payment payment = new AdvPaymentMngtDao().getNewPayment(
        true, org, docType, docNo, bp, paymentMethod, account,
        "0", paymentDate, "", currency, BigDecimal.ONE, amount);

    payment.setAmount(amount);
    payment.setFinancialTransactionAmount(amount);
    payment.setFinancialTransactionConvertRate(BigDecimal.ONE);
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

  private void linkPSDsToPayment(List<FIN_PaymentScheduleDetail> psds,
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
  private NeoResponse handleListPayments(NeoContext context) {
    String invoiceId = context.getRecordId();
    if (StringUtils.isBlank(invoiceId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invoice ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        // Find all payments linked to this invoice via PaymentScheduleDetail chain
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
            String acctName = p.getAccount().getName();
            String acctCurrency = p.getAccount().getCurrency() != null
                ? p.getAccount().getCurrency().getISOCode() : null;
            item.put("accountName", acctName);
            item.put("accountCurrency", acctCurrency);
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

  @SuppressWarnings("unchecked")
  private List<FIN_PaymentScheduleDetail> findPendingPSDs(String scheduleId) {
    OBCriteria<FIN_PaymentScheduleDetail> criteria = OBDal.getInstance()
        .createCriteria(FIN_PaymentScheduleDetail.class);
    criteria.add(Restrictions.eq(
        FIN_PaymentScheduleDetail.PROPERTY_INVOICEPAYMENTSCHEDULE + ".id", scheduleId));
    criteria.add(Restrictions.isNull(FIN_PaymentScheduleDetail.PROPERTY_PAYMENTDETAILS));
    criteria.addOrderBy(FIN_PaymentScheduleDetail.PROPERTY_AMOUNT, false);
    return criteria.list();
  }
}
