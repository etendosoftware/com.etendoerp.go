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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.service.json.JsonUtils;

/**
 * What the invoice payment actions do differently for an agent (ETP-5558, FR-2, FR-5, FR-9,
 * FR-11, FR-12). Reached only when the call comes through MCP ({@link NeoContext#isMcpOrigin()});
 * the REST path and the SPA never get here, so their behaviour is unchanged.
 *
 * <p>The SPA does part of the work client-side — it resolves the installment, asks what to do with
 * an overpayment, only offers the methods the chosen account accepts — and re-reads the invoice
 * after saving. An agent has none of that, so the backend does it for it:</p>
 * <ul>
 *   <li>{@link #checkRegister} fills in or refuses what the SPA would have settled before the
 *       call: the installment, the overpayment decision and the method/account pairing;</li>
 *   <li>{@link #withAgentDefaults} makes {@code invoiceAccounts} say which method each account
 *       would actually use;</li>
 *   <li>{@link #enrich} answers with the outcome the SPA reads back on its own (the invoice's new
 *       outstanding amount, credit, write-off; what a delete removed).</li>
 * </ul>
 */
final class PaymentAgentSupport {

  static final int SC_UNPROCESSABLE = 422;

  private static final String KEY_SCHEDULE_ID = "scheduleId";
  private static final String KEY_PAYMENT_ID = "paymentId";
  private static final String KEY_METHOD_ID = "fin_paymentmethod_id";
  private static final String KEY_OVERPAYMENT = "overpaymentAction";
  private static final String KEY_DEFAULT_METHOD = "defaultMethodId";
  private static final String KEY_METHOD_IDS = "paymentMethodIds";
  private static final String KEY_ID = "id";
  private static final String KEY_NAME = "name";
  private static final String KEY_OUTSTANDING = "outstandingAmount";
  private static final String KEY_DATA = "data";
  private static final String KEY_RESPONSE = "response";

  private PaymentAgentSupport() {
  }

  // ─── registerPayment: what the SPA settles before the call ─────────────────

  /**
   * Fills in the installment when it is unambiguous and refuses, with what the agent needs to
   * retry, a call the SPA would never have sent. Runs inside the admin session of the mutating
   * action and before anything is written. Returns {@code null} when the call may proceed —
   * including when an id does not resolve: the service then answers its own 404/400.
   *
   * @param invoiceId the invoice in the URL
   * @param body      the register body; {@code scheduleId} is added to it when resolved here
   * @param isReceipt {@code true} for collections, {@code false} for payments
   * @return a 404/422 refusal, or {@code null}
   */
  static NeoResponse checkRegister(String invoiceId, JSONObject body, boolean isReceipt)
      throws JSONException {
    Invoice invoice = TenantOwnership.loadOwned(Invoice.class, invoiceId);
    if (invoice == null) {
      return null;
    }
    NeoResponse refusal = resolveSchedule(invoice, body);
    if (refusal == null) {
      refusal = checkMethod(body, isReceipt);
    }
    if (refusal == null) {
      refusal = checkOverpayment(invoice, body, isReceipt);
    }
    return refusal;
  }

  /**
   * FR-2: {@code scheduleId} is optional for an agent. Editing a draft, it is the installment the
   * draft already pays; otherwise the invoice's only pending installment. Several pending
   * installments are refused with the list, since picking one would be a guess.
   */
  private static NeoResponse resolveSchedule(Invoice invoice, JSONObject body)
      throws JSONException {
    if (StringUtils.isNotBlank(body.optString(KEY_SCHEDULE_ID, null))) {
      return null;
    }
    String editId = body.optString(KEY_PAYMENT_ID, null);
    List<FIN_PaymentSchedule> candidates;
    if (StringUtils.isNotBlank(editId)) {
      FIN_Payment draft = PaymentOwnership.invoicePayment(editId, invoice.getId());
      candidates = draft == null ? List.of() : schedulesPaidBy(draft, invoice.getId());
      if (candidates.isEmpty()) {
        // Not a draft paying an installment of this invoice: the same 404 as an unknown id, not
        // the "no pending installment" of a fresh payment.
        return NeoResponse.error(404, PaymentRegistrationService.MSG_PAYMENT_NOT_FOUND);
      }
    } else {
      candidates = pendingSchedules(invoice);
    }
    if (candidates.size() == 1) {
      body.put(KEY_SCHEDULE_ID, candidates.get(0).getId());
      return null;
    }
    if (candidates.isEmpty()) {
      return NeoResponse.error(SC_UNPROCESSABLE, PaymentRegistrationService.MSG_NO_PENDING_PSD);
    }
    JSONArray installments = new JSONArray();
    for (FIN_PaymentSchedule schedule : candidates) {
      JSONObject item = new JSONObject();
      item.put(KEY_ID, schedule.getId());
      item.put(KEY_OUTSTANDING, pendingAmount(schedule));
      if (schedule.getDueDate() != null) {
        item.put("dueDate", JsonUtils.createDateFormat().format(schedule.getDueDate()));
      }
      installments.put(item);
    }
    return refusal("This invoice has " + candidates.size() + " pending installments; send "
        + "scheduleId with the one being paid (see 'installments').", "installments",
        installments);
  }

  /**
   * FR-5: a method the account does not accept is refused with the ones it does. The service
   * itself silently falls back to the account's default, which leaves the agent believing the
   * method it asked for was used.
   */
  private static NeoResponse checkMethod(JSONObject body, boolean isReceipt) throws JSONException {
    String methodId = body.optString(KEY_METHOD_ID, null);
    if (StringUtils.isBlank(methodId)) {
      return null;
    }
    FIN_FinancialAccount account = TenantOwnership.loadOwned(FIN_FinancialAccount.class,
        body.optString("fin_financial_account_id", null));
    if (account == null) {
      return null;
    }
    String allowProp = PaymentRegistrationService.allowProperty(isReceipt);
    FIN_PaymentMethod method = TenantOwnership.loadOwned(FIN_PaymentMethod.class, methodId);
    if (method != null && PaymentRegistrationService.isMethodAllowed(account, method, allowProp)) {
      return null;
    }
    JSONArray valid = new JSONArray();
    Map<String, List<FinAccPaymentMethod>> byAccount =
        PaymentAccountMethodsLoader.loadAllowedMethodsByAccount(List.of(account), allowProp);
    for (FinAccPaymentMethod fapm : byAccount.getOrDefault(account.getId(), List.of())) {
      if (fapm.getPaymentMethod() != null) {
        JSONObject item = new JSONObject();
        item.put(KEY_ID, fapm.getPaymentMethod().getId());
        item.put(KEY_NAME, fapm.getPaymentMethod().getName());
        valid.put(item);
      }
    }
    return refusal("The financial account '" + account.getName() + "' does not accept the "
        + "payment method " + methodId + "; send one of 'validMethods', or omit "
        + KEY_METHOD_ID + " to use the account's default.", "validMethods", valid);
  }

  /**
   * FR-9: funding above what the installment can take. Same capacity the service applies: the
   * pending details of the installment, or its whole amount when a draft is edited (see
   * {@code PaymentDraftEditService#reapplyLinkedInstallmentPSD}).
   *
   * <p>Where the UI allows an overpayment at all — a collection whose invoice is in the
   * organization's currency, the SPA's {@code canLeaveCredit} — it needs an explicit
   * {@code overpaymentAction}: without one the service keeps the excess as credit, which for an
   * agent is usually a typo in the amount. Everywhere else (a payment, or a collection in another
   * currency) the UI offers no way to resolve an excess but lowering the amount, so neither does
   * the MCP, whatever {@code overpaymentAction} says.</p>
   */
  private static NeoResponse checkOverpayment(Invoice invoice, JSONObject body, boolean isReceipt)
      throws JSONException {
    FIN_PaymentSchedule schedule = PaymentOwnership.scheduleOf(
        body.optString(KEY_SCHEDULE_ID, null), invoice);
    BigDecimal cash = parseAmount(body.optString("actual_payment", null));
    if (schedule == null || cash == null) {
      return null;
    }
    boolean editing = StringUtils.isNotBlank(body.optString(KEY_PAYMENT_ID, null));
    BigDecimal capacity = cents(editing ? schedule.getAmount() : pendingAmount(schedule));
    BigDecimal funds = cents(cash.add(
        PaymentCreditConsumer.requestedFunding(body.optJSONArray("creditSources"))));
    BigDecimal excess = funds.subtract(capacity);
    if (excess.signum() <= 0) {
      return null;
    }
    String overrun = "The " + funds.toPlainString() + " funding this payment (actual_payment plus "
        + "creditSources) exceeds the installment's outstanding " + capacity.toPlainString()
        + " by " + excess.toPlainString() + ".";
    NeoResponse response;
    if (!overpaymentAllowed(invoice, isReceipt)) {
      response = NeoResponse.error(SC_UNPROCESSABLE, overrun + " An overpayment is only possible "
          + "on a collection whose invoice is in the organization's currency; lower actual_payment "
          + "(plus creditSources) to at most the outstanding amount.");
    } else if (StringUtils.isBlank(body.optString(KEY_OVERPAYMENT, null))) {
      response = refusal(overrun + " Send overpaymentAction 'leave-credit' to keep the excess as "
          + "credit of the business partner or 'refund' to return it, or lower actual_payment.",
          "allowedValues", new JSONArray(List.of("leave-credit", "refund")));
    } else {
      return null;
    }
    JSONObject error = response.getBody().getJSONObject("error");
    error.put(KEY_OUTSTANDING, capacity);
    error.put("excess", excess);
    return response;
  }

  /**
   * The SPA's {@code canLeaveCredit} ({@code NewPaymentEntryModal}): a collection, and the invoice
   * in the currency of the session's organization ({@code /session → currencyCode}, resolved with
   * {@code OBCurrencyUtils.getOrgCurrency}). An organization with no resolvable currency allows
   * none.
   */
  static boolean overpaymentAllowed(Invoice invoice, boolean isReceipt) {
    if (!isReceipt || invoice.getCurrency() == null) {
      return false;
    }
    String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(
        OBContext.getOBContext().getCurrentOrganization().getId());
    return orgCurrencyId != null && orgCurrencyId.equals(invoice.getCurrency().getId());
  }

  // ─── invoiceAccounts: the method each account would really use ─────────────

  /**
   * FR-5: in the REST answer, {@code defaultMethodId} is the invoice's own method, which may be one
   * no listed account accepts, and each account's {@code defaultPaymentMethod} is just the first
   * method by name. For an agent each account gets the {@code defaultMethodId} that
   * {@code registerPayment} would use on it without {@code fin_paymentmethod_id} — the same order
   * as {@code PaymentRegistrationService#resolvePaymentMethod}: the invoice's method when the
   * account accepts it, else the account's flagged default, else its first method by name. The
   * invoice's method moves to {@code invoiceMethodId}, with whether any account accepts it.
   *
   * @param response the {@code invoiceAccounts} answer; anything but a 200 passes through
   * @return the same response, its body rewritten
   */
  static NeoResponse withAgentDefaults(NeoResponse response) throws JSONException {
    if (response == null || response.getHttpStatus() != 200 || response.getBody() == null) {
      return response;
    }
    JSONObject body = response.getBody();
    String invoiceMethodId = body.optString(KEY_DEFAULT_METHOD, null);
    JSONArray items = body.optJSONArray("items");
    boolean accepted = false;
    for (int i = 0; items != null && i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      JSONArray methodIds = item.optJSONArray(KEY_METHOD_IDS);
      boolean acceptsInvoiceMethod = contains(methodIds, invoiceMethodId);
      accepted |= acceptsInvoiceMethod;
      String effective = acceptsInvoiceMethod ? invoiceMethodId
          : first(item.optJSONArray("defaultForMethodIds"), first(methodIds, null));
      item.remove("defaultPaymentMethod");
      if (effective != null) {
        item.put(KEY_DEFAULT_METHOD, effective);
      }
    }
    body.remove(KEY_DEFAULT_METHOD);
    if (invoiceMethodId != null) {
      body.put("invoiceMethodId", invoiceMethodId);
      body.put("invoiceMethodAccepted", accepted);
    }
    return response;
  }

  // ─── the answer: what the SPA reads back on its own ───────────────────────

  /**
   * The draft a {@code deletePayment} is about to remove, captured before it is gone.
   *
   * @return {@code {id, documentNo, amount, status}}, or {@code null} when the id does not
   *         resolve (the delete then answers its own 404)
   */
  static JSONObject describeDraft(String paymentId, String invoiceId) throws Exception {
    FIN_Payment payment = PaymentOwnership.invoicePayment(paymentId, invoiceId);
    return payment == null ? null : PaymentRegistrationService.basePaymentData(payment);
  }

  /**
   * FR-11, FR-12: adds the outcome to a successful payment action. {@code registerPayment} and
   * {@code confirmPayment} get the invoice's new state, the method used, the credit used and
   * generated and the write-off; {@code deletePayment} answers 200 with what it removed instead
   * of an empty 204.
   *
   * @param deleted for {@code deletePayment}, what {@link #describeDraft} captured; else ignored
   * @return the enriched response; an error passes through
   */
  static NeoResponse enrich(String action, NeoResponse result, String invoiceId, JSONObject body,
      JSONObject deleted) throws Exception {
    if (result == null || result.getHttpStatus() >= 300) {
      return result;
    }
    if (deleted != null) {
      JSONObject answer = new JSONObject();
      answer.put("deleted", deleted);
      answer.put("invoice", invoiceState(invoiceId));
      return NeoResponse.ok(answer);
    }
    JSONObject resultBody = result.getBody();
    JSONObject response = resultBody == null ? null : resultBody.optJSONObject(KEY_RESPONSE);
    JSONObject data = response == null ? null : response.optJSONObject(KEY_DATA);
    FIN_Payment payment = data == null ? null
        : OBDal.getInstance().get(FIN_Payment.class, data.optString(KEY_ID, null));
    if (payment == null) {
      return result;
    }
    if (payment.getPaymentMethod() != null) {
      JSONObject method = new JSONObject();
      method.put(KEY_ID, payment.getPaymentMethod().getId());
      method.put(KEY_NAME, payment.getPaymentMethod().getName());
      data.put("paymentMethod", method);
    }
    if ("registerPayment".equals(action)) {
      data.put("creditUsed",
          PaymentCreditConsumer.requestedFunding(body.optJSONArray("creditSources")));
    }
    BigDecimal generated = PaymentRegistrationService.nullToZero(payment.getGeneratedCredit());
    data.put("creditGenerated", generated);
    data.put("creditAvailable",
        generated.subtract(PaymentRegistrationService.nullToZero(payment.getUsedCredit())));
    data.put("writeoffAmount", writeoffOf(payment));
    data.put("invoice", invoiceState(invoiceId));
    if (!Boolean.TRUE.equals(payment.isProcessed())) {
      data.put("note", "Draft: nothing is applied to the invoice until confirmPayment.");
    }
    return result;
  }

  /**
   * The plain result of a successful action whose outcome could not be added, saying so with
   * {@code enriched:false} so the agent re-reads instead of assuming the fields are missing.
   * An empty body (the delete's 204) passes through unchanged.
   */
  static NeoResponse markNotEnriched(NeoResponse result) {
    JSONObject body = result == null ? null : result.getBody();
    if (body == null) {
      return result;
    }
    try {
      JSONObject response = body.optJSONObject(KEY_RESPONSE);
      JSONObject data = response == null ? null : response.optJSONObject(KEY_DATA);
      (data != null ? data : body).put("enriched", false);
    } catch (JSONException e) {
      // Best effort: the result itself is what matters.
    }
    return result;
  }

  // ─── helpers ───────────────────────────────────────────────────────────────

  private static JSONObject invoiceState(String invoiceId) throws JSONException {
    JSONObject state = new JSONObject();
    // A fresh read, not session.refresh(): Core updates these amounts while processing, and a
    // scalar query reads what was written without touching the cached entity.
    Object[] row = OBDal.getInstance().getSession()
        .createQuery("select i.documentNo, i.outstandingAmount, i.totalPaid, i.paymentComplete"
            + " from Invoice i where i.id = :id", Object[].class)
        .setParameter("id", invoiceId)
        .uniqueResult();
    if (row == null) {
      return state;
    }
    state.put(KEY_ID, invoiceId);
    state.put("documentNo", row[0]);
    state.put(KEY_OUTSTANDING, row[1]);
    state.put("totalPaid", row[2]);
    state.put("paymentComplete", Boolean.TRUE.equals(row[3]));
    return state;
  }

  private static BigDecimal writeoffOf(FIN_Payment payment) {
    BigDecimal total = BigDecimal.ZERO;
    for (FIN_PaymentDetail detail : payment.getFINPaymentDetailList()) {
      total = total.add(PaymentRegistrationService.nullToZero(detail.getWriteoffAmount()));
    }
    return total;
  }

  /** The invoice's installments with an unpaid detail, in due-date order. */
  private static List<FIN_PaymentSchedule> pendingSchedules(Invoice invoice) {
    List<FIN_PaymentSchedule> pending = new ArrayList<>();
    for (FIN_PaymentSchedule schedule : invoice.getFINPaymentScheduleList()) {
      if (pendingAmount(schedule).signum() > 0) {
        pending.add(schedule);
      }
    }
    pending.sort(Comparator.comparing(FIN_PaymentSchedule::getDueDate,
        Comparator.nullsLast(Comparator.naturalOrder())));
    return pending;
  }

  /** The invoice's installments a draft already pays. */
  private static List<FIN_PaymentSchedule> schedulesPaidBy(FIN_Payment draft, String invoiceId) {
    List<FIN_PaymentSchedule> paid = new ArrayList<>();
    for (FIN_PaymentDetail detail : draft.getFINPaymentDetailList()) {
      for (FIN_PaymentScheduleDetail psd : detail.getFINPaymentScheduleDetailList()) {
        FIN_PaymentSchedule schedule = psd.getInvoicePaymentSchedule();
        if (schedule != null && schedule.getInvoice() != null
            && invoiceId.equals(schedule.getInvoice().getId()) && !paid.contains(schedule)) {
          paid.add(schedule);
        }
      }
    }
    return paid;
  }

  /** What is still unpaid on an installment: its details not yet linked to a payment. */
  static BigDecimal pendingAmount(FIN_PaymentSchedule schedule) {
    BigDecimal total = BigDecimal.ZERO;
    for (FIN_PaymentScheduleDetail psd
        : schedule.getFINPaymentScheduleDetailInvoicePaymentScheduleList()) {
      if (psd.getPaymentDetails() == null && psd.getAmount() != null) {
        total = total.add(psd.getAmount());
      }
    }
    return total;
  }

  private static NeoResponse refusal(String message, String listKey, JSONArray list)
      throws JSONException {
    NeoResponse response = NeoResponse.error(SC_UNPROCESSABLE, message);
    response.getBody().getJSONObject("error").put(listKey, list);
    return response;
  }

  private static BigDecimal parseAmount(String raw) {
    if (StringUtils.isBlank(raw)) {
      return null;
    }
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // Scale hardcoded to 2 to mirror the SPA, which rounds these amounts to cents. Wrong for
  // currencies with 0 or 3 decimals (JPY, KWD). The fix is the currency's standard precision on
  // both sides, SPA and server, changed together - never on one side alone.
  private static BigDecimal cents(BigDecimal amount) {
    return PaymentRegistrationService.nullToZero(amount).setScale(2, RoundingMode.HALF_UP);
  }

  private static boolean contains(JSONArray array, String value) {
    for (int i = 0; value != null && array != null && i < array.length(); i++) {
      if (value.equals(array.optString(i, null))) {
        return true;
      }
    }
    return false;
  }

  private static String first(JSONArray array, String fallback) {
    return array != null && array.length() > 0 ? array.optString(0, fallback) : fallback;
  }
}
