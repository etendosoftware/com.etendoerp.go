package com.etendoerp.go.schemaforge;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Shared invoice payment ACTION handling for both sales and purchase invoice headers.
 */
final class PaymentActionHandlerSupport {

  private static final String ACTION_NAME = "registerPayment";
  private static final String LIST_ACTION = "invoicePayments";
  private static final String ACCOUNTS_ACTION = "invoiceAccounts";
  private static final String METHODS_ACTION = "invoicePaymentMethods";
  private static final String CREDIT_SOURCES_ACTION = "invoiceCreditSources";
  private static final String PIS_STATUS_ACTION = "pisPaymentStatus";
  private static final String PIS_SUPPLIER_ACCOUNTS_ACTION = "pisSupplierAccounts";
  private static final String PIS_TEMPLATES_ACTION = "pisTemplates";
  private static final String PIS_CANCEL_ACTION = "cancelPisPayment";
  private static final String CONFIRM_ACTION = "confirmPayment";

  private static final String FIELD_PAYMENT_ID = "paymentId";
  private static final String FIELD_SCHEDULE_ID = "scheduleId";
  private static final String FIELD_AMOUNT = "actual_payment";
  private static final String FIELD_DATE = "payment_date";
  private static final String FIELD_ACCOUNT = "fin_financial_account_id";

  private PaymentActionHandlerSupport() {
  }

  static NeoResponse handle(NeoContext context, boolean isReceipt, Logger log) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    String fieldName = context.getFieldName();

    NeoResponse queryResult = routeQuery(context, fieldName, isReceipt);
    if (queryResult != null) {
      return queryResult;
    }

    boolean isConfirm = CONFIRM_ACTION.equals(fieldName);
    if ((!isConfirm && !ACTION_NAME.equals(fieldName)) || !"POST".equals(context.getHttpMethod())) {
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

    // Validate inputs BEFORE opening an admin session, so malformed requests
    // return 400 without requiring a DB context.
    NeoResponse validationError = validateBody(body, isConfirm);
    if (validationError != null) {
      return validationError;
    }

    return executeMutating(fieldName, isReceipt, invoiceId, body, isConfirm, log);
  }

  /** Routes the read-only listing actions; returns null when {@code fieldName} is not one. */
  private static NeoResponse routeQuery(NeoContext context, String fieldName, boolean isReceipt) {
    if (LIST_ACTION.equals(fieldName)) {
      return PaymentRegistrationService.handleListPayments(context);
    }
    if (ACCOUNTS_ACTION.equals(fieldName)) {
      return PaymentRegistrationService.handleListAccounts(context, isReceipt);
    }
    if (METHODS_ACTION.equals(fieldName)) {
      return PaymentRegistrationService.handleListPaymentMethods(context, isReceipt);
    }
    if (CREDIT_SOURCES_ACTION.equals(fieldName)) {
      return PaymentRegistrationService.handleListCreditSources(context, isReceipt);
    }
    if (PIS_STATUS_ACTION.equals(fieldName)) {
      return PaymentRegistrationService.handlePisPaymentStatus(context);
    }
    if (PIS_SUPPLIER_ACCOUNTS_ACTION.equals(fieldName)) {
      return PaymentRegistrationService.handleListSupplierBankAccounts(context, isReceipt);
    }
    if (PIS_TEMPLATES_ACTION.equals(fieldName)) {
      return PaymentRegistrationService.handlePisTemplates(context);
    }
    if (PIS_CANCEL_ACTION.equals(fieldName)) {
      return PaymentRegistrationService.handleCancelPisPayment(context);
    }
    return null;
  }

  /** Validates the required body fields; returns an error response, or null when valid. */
  private static NeoResponse validateBody(JSONObject body, boolean isConfirm) {
    if (isConfirm) {
      if (StringUtils.isBlank(body.optString(FIELD_PAYMENT_ID, null))) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "paymentId is required");
      }
      return null;
    }
    if (StringUtils.isBlank(body.optString(FIELD_SCHEDULE_ID, null))
        || StringUtils.isBlank(body.optString(FIELD_AMOUNT, null))
        || StringUtils.isBlank(body.optString(FIELD_DATE, null))
        || StringUtils.isBlank(body.optString(FIELD_ACCOUNT, null))) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Missing required fields: scheduleId, actual_payment, payment_date, fin_financial_account_id");
    }
    return null;
  }

  /** True when the register body carries advanced (two-step modal) fields. */
  private static boolean isAdvanced(JSONObject body) {
    return body.has("process") || body.has("creditSources")
        || body.has("overpaymentAction") || body.has("fin_paymentmethod_id");
  }

  /** Runs the mutating action inside an admin session with rollback-on-error handling. */
  private static NeoResponse executeMutating(String fieldName, boolean isReceipt,
      String invoiceId, JSONObject body, boolean isConfirm, Logger log) {
    try {
      OBContext.setAdminMode(true);
      try {
        if (isConfirm) {
          return PaymentRegistrationService.confirmDraftPayment(body.optString(FIELD_PAYMENT_ID, null));
        }
        if (isAdvanced(body)) {
          return PaymentRegistrationService.doRegisterPaymentAdvanced(invoiceId, body, isReceipt);
        }
        return PaymentRegistrationService.doRegisterPayment(invoiceId,
            body.optString(FIELD_SCHEDULE_ID, null), body.optString(FIELD_AMOUNT, null),
            body.optString(FIELD_DATE, null), body.optString(FIELD_ACCOUNT, null), isReceipt);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Payment action '{}' failed for invoice {}: {}", fieldName, invoiceId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Error in payment action '{}' for invoice {}: {}", fieldName, invoiceId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while processing the payment");
    }
  }
}
