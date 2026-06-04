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
import java.util.Iterator;
import java.util.List;

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
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBDateUtils;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.service.json.JsonUtils;

/**
 * Creates and processes a {@link FIN_Payment} replicating Etendo Classic's "Add Payment" process,
 * for the embedded payment workspace of the New Movement wizard.
 *
 * <p>Mirrors the sequence of {@code org.openbravo.advpaymentmngt.actionHandler.AddPaymentActionHandler}:
 * create the payment, link the selected invoice payment-schedule details (with optional write-off),
 * add the G/L item lines, register the over-payment as a generated-credit schedule detail, process
 * the payment with action {@code "P"} (which auto-creates the {@code FIN_FinaccTransaction}), and —
 * when the over-payment action is a refund — create and process the refund payment.
 */
final class AddPaymentService {

  private static final Logger log = LogManager.getLogger(AddPaymentService.class);

  private AddPaymentService() {
  }

  /**
   * Body shape (from the PaymentForm snapshot + movement basics):
   * <pre>
   * {
   *   "FIN_Financial_Account_ID": "...",
   *   "isReceipt": true,                 // doc === 'in'
   *   "bpartnerId": "...",               // tercero (required)
   *   "paymentMethodId": "...",
   *   "amount": 25.00,                   // totals.pago
   *   "paymentDate": "2026-06-03",
   *   "referenceNo": "...",
   *   "description": "...",
   *   "organizationId": "...",           // optional (movement org dimension)
   *   "selectedInvoices": { "<psdId>": 18.03, ... },
   *   "writeoffs": { "<psdId>": false, ... },
   *   "glItems": [ { "glItemId": "...", "receivedIn": 0, "paidOut": 0 }, ... ],
   *   "overpaymentAction": "leave-credit" | "refund" | null
   * }
   * </pre>
   */
  static NeoResponse doAddPayment(JSONObject body) throws Exception {
    // ── Parse + validate primitives ─────────────────────────────────────────
    String accountId = body.optString("FIN_Financial_Account_ID", null);
    if (StringUtils.isBlank(accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Missing FIN_Financial_Account_ID");
    }
    String bpartnerId = body.optString("bpartnerId", null);
    if (StringUtils.isBlank(bpartnerId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "A contact (bpartnerId) is required to register a payment");
    }
    boolean isReceipt = body.optBoolean("isReceipt", true);

    BigDecimal amount;
    try {
      amount = new BigDecimal(body.optString("amount", ""));
    } catch (NumberFormatException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Invalid amount: " + body.optString("amount", ""));
    }
    if (amount.signum() <= 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Amount must be greater than 0");
    }

    Date paymentDate;
    try {
      paymentDate = JsonUtils.createDateFormat().parse(body.optString("paymentDate", ""));
    } catch (ParseException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Invalid paymentDate: " + body.optString("paymentDate", ""));
    }

    // ── Resolve entities ────────────────────────────────────────────────────
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Financial account not found");
    }
    BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, bpartnerId);
    if (bp == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Contact not found");
    }
    Currency currency = account.getCurrency();

    Organization org = account.getOrganization();
    String organizationId = body.optString("organizationId", null);
    if (StringUtils.isNotBlank(organizationId)) {
      Organization movementOrg = OBDal.getInstance().get(Organization.class, organizationId);
      if (movementOrg != null) {
        org = movementOrg;
      }
    }

    FIN_PaymentMethod paymentMethod = resolvePaymentMethod(account,
        body.optString("paymentMethodId", null), isReceipt);
    if (paymentMethod == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "No valid payment method for this financial account");
    }

    DocumentType docType = FIN_Utility.getDocumentType(org, isReceipt ? "ARR" : "APP");
    if (docType == null) {
      throw new OBException("Document type for " + (isReceipt ? "Receipts (ARR)" : "Payments (APP)")
          + " not found for the organization.");
    }
    checkPeriodOpen(org, docType, paymentDate);

    VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(OBContext.getOBContext());
    RequestContext.get().setVariableSecureApp(vars);
    DalConnectionProvider conn = new DalConnectionProvider(false);
    AdvPaymentMngtDao dao = new AdvPaymentMngtDao();

    // ── Create the payment ──────────────────────────────────────────────────
    String docNo = FIN_Utility.getDocumentNo(docType, "FIN_Payment");
    String referenceNo = body.optString("referenceNo", "");
    FIN_Payment payment = dao.getNewPayment(isReceipt, org, docType, docNo, bp, paymentMethod,
        account, "0", paymentDate, referenceNo, currency, BigDecimal.ONE, amount);
    payment.setAmount(amount);
    String description = body.optString("description", null);
    if (StringUtils.isNotBlank(description)) {
      payment.setDescription(description);
    }
    FIN_AddPayment.setFinancialTransactionAmountAndRate(null, payment, BigDecimal.ONE, amount);
    OBDal.getInstance().save(payment);
    OBDal.getInstance().flush();

    // ── Link selected invoices (with optional write-off) ────────────────────
    linkInvoices(payment, body.optJSONObject("selectedInvoices"), body.optJSONObject("writeoffs"));

    // ── Add G/L item lines ──────────────────────────────────────────────────
    addGlItems(payment, body.optJSONArray("glItems"), isReceipt);

    // ── Over-payment: register the leftover as a generated-credit detail ─────
    BigDecimal assigned = assignedAmount(payment);
    BigDecimal leftover = amount.subtract(assigned);
    boolean overpaid = leftover.signum() > 0;
    if (overpaid) {
      FIN_PaymentScheduleDetail creditPsd = dao.getNewPaymentScheduleDetail(org, leftover);
      dao.getNewPaymentDetail(payment, creditPsd, leftover, BigDecimal.ZERO, false, null);
    }
    OBDal.getInstance().flush();

    // ── Process (creates the FIN_FinaccTransaction, posts, generates credit) ─
    OBError result = FIN_AddPayment.processPayment(vars, conn, "P", payment, "");
    OBDal.getInstance().flush();
    if ("Error".equalsIgnoreCase(result.getType())) {
      throw new OBException(result.getMessage());
    }

    // ── Refund the over-payment when requested ──────────────────────────────
    boolean doRefund = overpaid && "refund".equals(body.optString("overpaymentAction", null));
    FIN_Payment refundPayment = null;
    if (doRefund) {
      refundPayment = FIN_AddPayment.createRefundPayment(conn, vars, payment, leftover.negate(), null);
      OBError refundResult = FIN_AddPayment.processPayment(vars, conn, "P", refundPayment, "",
          "(" + payment.getId() + ")");
      OBDal.getInstance().flush();
      if ("Error".equalsIgnoreCase(refundResult.getType())) {
        throw new OBException(refundResult.getMessage());
      }
    }

    // ── Response ────────────────────────────────────────────────────────────
    JSONObject data = new JSONObject();
    data.put("id", payment.getId());
    data.put("documentNo", payment.getDocumentNo());
    data.put("status", payment.getStatus());
    if (refundPayment != null) {
      data.put("refundPaymentId", refundPayment.getId());
      data.put("refundDocumentNo", refundPayment.getDocumentNo());
    }
    return NeoResponse.createdWithData(data);
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────

  /** Sum of the payment's current detail amounts (mirrors Classic's assignedAmount). */
  private static BigDecimal assignedAmount(FIN_Payment payment) {
    BigDecimal total = BigDecimal.ZERO;
    for (FIN_PaymentDetail detail : payment.getFINPaymentDetailList()) {
      total = total.add(detail.getAmount());
    }
    return total;
  }

  /** Links each selected invoice payment-schedule detail to the payment. */
  private static void linkInvoices(FIN_Payment payment, JSONObject selectedInvoices,
      JSONObject writeoffs) {
    if (selectedInvoices == null) {
      return;
    }
    Iterator<?> keys = selectedInvoices.keys();
    while (keys.hasNext()) {
      String psdId = (String) keys.next();
      BigDecimal amt;
      try {
        amt = new BigDecimal(selectedInvoices.optString(psdId, "0"));
      } catch (NumberFormatException e) {
        continue;
      }
      if (amt.signum() == 0) {
        continue;
      }
      FIN_PaymentScheduleDetail psd = OBDal.getInstance().get(FIN_PaymentScheduleDetail.class, psdId);
      if (psd == null) {
        throw new OBException("Invoice installment not found: " + psdId);
      }
      boolean isWriteoff = writeoffs != null && writeoffs.optBoolean(psdId, false);
      FIN_AddPayment.updatePaymentDetail(psd, payment, amt, isWriteoff);
    }
  }

  /** Adds the G/L item lines, computing the signed amount the same way Classic does. */
  private static void addGlItems(FIN_Payment payment, JSONArray glItems, boolean isReceipt)
      throws Exception {
    if (glItems == null) {
      return;
    }
    for (int i = 0; i < glItems.length(); i++) {
      JSONObject line = glItems.getJSONObject(i);
      String glItemId = line.optString("glItemId", null);
      if (StringUtils.isBlank(glItemId)) {
        continue;
      }
      BigDecimal received = new BigDecimal(line.optString("receivedIn", "0"));
      BigDecimal paid = new BigDecimal(line.optString("paidOut", "0"));
      BigDecimal glAmount = isReceipt ? received.subtract(paid) : paid.subtract(received);
      if (glAmount.signum() == 0) {
        continue;
      }
      GLItem glItem = OBDal.getInstance().get(GLItem.class, glItemId);
      if (glItem == null) {
        throw new OBException("G/L item not found: " + glItemId);
      }
      FIN_AddPayment.saveGLItem(payment, glAmount, glItem);
    }
  }

  /**
   * Resolves the payment method to use: the requested one when it is valid for the account,
   * otherwise the first method configured for the account in the right direction.
   */
  private static FIN_PaymentMethod resolvePaymentMethod(FIN_FinancialAccount account,
      String paymentMethodId, boolean isReceipt) {
    String allowProp = isReceipt
        ? FinAccPaymentMethod.PROPERTY_PAYINALLOW
        : FinAccPaymentMethod.PROPERTY_PAYOUTALLOW;

    if (StringUtils.isNotBlank(paymentMethodId)) {
      FIN_PaymentMethod requested = OBDal.getInstance().get(FIN_PaymentMethod.class, paymentMethodId);
      if (requested != null && isMethodValid(account, requested, allowProp)) {
        return requested;
      }
    }

    OBCriteria<FinAccPaymentMethod> fallback = OBDal.getInstance()
        .createCriteria(FinAccPaymentMethod.class);
    fallback.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
    fallback.add(Restrictions.eq(allowProp, Boolean.TRUE));
    fallback.setMaxResults(1);
    List<FinAccPaymentMethod> methods = fallback.list();
    return methods.isEmpty() ? null : methods.get(0).getPaymentMethod();
  }

  private static boolean isMethodValid(FIN_FinancialAccount account, FIN_PaymentMethod method,
      String allowProp) {
    OBCriteria<FinAccPaymentMethod> crit = OBDal.getInstance()
        .createCriteria(FinAccPaymentMethod.class);
    crit.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
    crit.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_PAYMENTMETHOD, method));
    crit.add(Restrictions.eq(allowProp, Boolean.TRUE));
    crit.setMaxResults(1);
    return !crit.list().isEmpty();
  }

  /** Validates the accounting period is open for the payment date (mirrors Classic). */
  private static void checkPeriodOpen(Organization org, DocumentType docType, Date paymentDate) {
    try {
      Organization legalEntity = OBContext.getOBContext()
          .getOrganizationStructureProvider(org.getClient().getId())
          .getLegalEntityOrBusinessUnit(org);
      boolean withAccounting = legalEntity != null && legalEntity.getOrganizationType() != null
          && legalEntity.getOrganizationType().isLegalEntityWithAccounting();
      if (!withAccounting) {
        return;
      }
      String docBaseType = docType.getDocumentCategory();
      if (!FIN_Utility.isPeriodOpen(org.getClient().getId(), docBaseType, org.getId(),
          OBDateUtils.formatDate(paymentDate))) {
        throw new OBException(OBMessageUtils.messageBD("PeriodNotAvailable"));
      }
    } catch (OBException e) {
      throw e;
    } catch (Exception e) {
      log.warn("Could not check period open for org {}: {}", org.getId(), e.getMessage());
    }
  }
}
