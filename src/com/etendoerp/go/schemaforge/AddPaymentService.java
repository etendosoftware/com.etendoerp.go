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
 * the payment (see {@link PaymentRegistrationService#resolveProcessAction}), and — when the
 * over-payment action is a refund — create and process the refund payment.
 *
 * <p>This flow never initiates a PIS handshake (no Salt Edge call, no {@code pisPaymentUrl}), so it
 * always passes {@code mayDeferToPis=false} — a transfer payment OUT gets action {@code "D"}
 * unconditionally, connected account or not (ETP-4891).
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
    boolean isReceipt = body.optBoolean("isReceipt", true);
    BigDecimal amount = parseAmountStrict(body.optString("amount", ""));
    Date paymentDate = parsePaymentDate(body.optString("paymentDate", ""));

    FIN_FinancialAccount account = require(
        OBDal.getInstance().get(FIN_FinancialAccount.class, body.optString("FIN_Financial_Account_ID", null)),
        "Financial account not found");
    BusinessPartner bp = require(
        OBDal.getInstance().get(BusinessPartner.class, body.optString("bpartnerId", null)),
        "A contact (bpartnerId) is required to register a payment");
    Currency currency = account.getCurrency();
    Organization org = resolveOrg(account, body.optString("organizationId", null));

    FIN_PaymentMethod paymentMethod = require(
        resolvePaymentMethod(account, body.optString("paymentMethodId", null), isReceipt),
        "No valid payment method for this financial account");

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

    // ── Link selected invoices + G/L lines ──────────────────────────────────
    linkInvoices(payment, body.optJSONObject("selectedInvoices"), body.optJSONObject("writeoffs"));
    addGlItems(payment, body.optJSONArray("glItems"), isReceipt);

    // ── Process (auto-creates the transaction) + optional refund ────────────
    FIN_Payment refundPayment = processAndRefund(payment, amount,
        body.optString("overpaymentAction", null), org, vars, conn, dao);

    return buildResponse(payment, refundPayment);
  }

  /**
   * Registers the over-payment as a generated-credit detail, processes the payment (see {@link
   * PaymentRegistrationService#resolveProcessAction}), and — when the over-payment action is a
   * refund — creates and processes the refund payment. Returns the refund payment, or {@code null}
   * when none.
   */
  private static FIN_Payment processAndRefund(FIN_Payment payment, BigDecimal amount,
      String overpaymentAction, Organization org, VariablesSecureApp vars,
      DalConnectionProvider conn, AdvPaymentMngtDao dao) throws Exception {
    BigDecimal leftover = amount.subtract(assignedAmount(payment));
    boolean overpaid = leftover.signum() > 0;
    if (overpaid) {
      FIN_PaymentScheduleDetail creditPsd = dao.getNewPaymentScheduleDetail(org, leftover);
      dao.getNewPaymentDetail(payment, creditPsd, leftover, BigDecimal.ZERO, false, null);
    }
    OBDal.getInstance().flush();

    failOnError(FIN_AddPayment.processPayment(vars, conn,
        PaymentRegistrationService.resolveProcessAction(payment, false), payment, ""));
    OBDal.getInstance().flush();

    if (!overpaid || !"refund".equals(overpaymentAction)) {
      return null;
    }
    FIN_Payment refundPayment = FIN_AddPayment.createRefundPayment(conn, vars, payment,
        leftover.negate(), null);
    failOnError(FIN_AddPayment.processPayment(vars, conn,
        PaymentRegistrationService.resolveProcessAction(refundPayment, false), refundPayment, "",
        "(" + payment.getId() + ")"));
    OBDal.getInstance().flush();
    return refundPayment;
  }

  private static void failOnError(OBError result) {
    if ("Error".equalsIgnoreCase(result.getType())) {
      throw new OBException(result.getMessage());
    }
  }

  private static NeoResponse buildResponse(FIN_Payment payment, FIN_Payment refundPayment)
      throws Exception {
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

  /** Parses a strictly-positive amount, throwing OBException (→ HTTP 400) when invalid. */
  private static BigDecimal parseAmountStrict(String raw) {
    BigDecimal amount;
    try {
      amount = new BigDecimal(raw);
    } catch (NumberFormatException e) {
      throw new OBException("Invalid amount: " + raw);
    }
    if (amount.signum() <= 0) {
      throw new OBException("Amount must be greater than 0");
    }
    return amount;
  }

  /** Parses the payment date (yyyy-MM-dd), throwing OBException (→ HTTP 400) when invalid. */
  private static Date parsePaymentDate(String raw) {
    try {
      return JsonUtils.createDateFormat().parse(raw);
    } catch (ParseException e) {
      throw new OBException("Invalid paymentDate: " + raw);
    }
  }

  /** Returns the entity, or throws OBException (→ HTTP 400) with the message when null. */
  private static <T> T require(T entity, String message) {
    if (entity == null) {
      throw new OBException(message);
    }
    return entity;
  }

  /** The movement organization when provided and valid, otherwise the account's. */
  private static Organization resolveOrg(FIN_FinancialAccount account, String organizationId) {
    if (StringUtils.isNotBlank(organizationId)) {
      Organization movementOrg = OBDal.getInstance().get(Organization.class, organizationId);
      if (movementOrg != null) {
        return movementOrg;
      }
    }
    return account.getOrganization();
  }

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
      BigDecimal amt = parseAmountOrNull(selectedInvoices.optString(psdId, "0"));
      if (amt == null || amt.signum() == 0) {
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

  /** Parses a decimal, returning null instead of throwing on malformed input. */
  private static BigDecimal parseAmountOrNull(String raw) {
    try {
      return new BigDecimal(raw);
    } catch (NumberFormatException e) {
      return null;
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
      BigDecimal received = new BigDecimal(line.optString("receivedIn", "0"));
      BigDecimal paid = new BigDecimal(line.optString("paidOut", "0"));
      BigDecimal glAmount = isReceipt ? received.subtract(paid) : paid.subtract(received);
      if (StringUtils.isBlank(glItemId) || glAmount.signum() == 0) {
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
