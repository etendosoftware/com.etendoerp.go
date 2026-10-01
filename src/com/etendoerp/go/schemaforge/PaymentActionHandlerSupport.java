package com.etendoerp.go.schemaforge;

import javax.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;

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
  private static final String PIS_RETRY_ACTION = "retryPisPayment";
  private static final String CONFIRM_ACTION = "confirmPayment";
  private static final String DELETE_ACTION = "deletePayment";

  /**
   * The PIS actions this support serves to the SPA, plus the invoice's PSD2 button, all excluded
   * from agents (ETP-5558, product decision): a bank-initiated payment ends in an authorization
   * only a person can give.
   */
  static final Set<String> AGENT_EXCLUDED_ACTIONS = Set.of(PIS_SUPPLIER_ACCOUNTS_ACTION,
      PIS_TEMPLATES_ACTION, PIS_STATUS_ACTION, PIS_CANCEL_ACTION, PIS_RETRY_ACTION,
      "psd2GenerateBankPayment");

  private static final String FIELD_PAYMENT_ID = "paymentId";
  private static final String FIELD_SCHEDULE_ID = "scheduleId";
  private static final String FIELD_AMOUNT = "actual_payment";
  private static final String FIELD_DATE = "payment_date";
  private static final String FIELD_ACCOUNT = "fin_financial_account_id";

  private PaymentActionHandlerSupport() {
  }

  /**
   * The payment actions this support serves, declared for agents (ETP-5558, FR-1).
   *
   * <p>The SPA has called these from the invoice panel all along; the declaration makes them
   * discoverable and typed through MCP ({@code neo_schema view:"actions"}, {@code neo_discover}),
   * where they used to be invisible — which is why agents built payments by hand. The REST
   * behaviour is untouched: nothing on the REST path reads these contracts.</p>
   *
   * <p>The PIS actions ({@code pisSupplierAccounts}, {@code pisTemplates}, {@code pisPaymentStatus},
   * {@code cancelPisPayment}, {@code retryPisPayment}) and the {@code pis} body key are deliberately
   * left out: a bank-initiated payment ends in an authorization only a person can give, so PIS is
   * excluded from the agent surface (product decision). The MCP validates every call against
   * these contracts before the handler runs, so an undeclared key such as {@code pis} is refused
   * (422); the PIS actions themselves are refused (405) through {@link #AGENT_EXCLUDED_ACTIONS},
   * which both invoice headers return from {@code agentExcludedActions()}.</p>
   *
   * @param isReceipt {@code true} for collections (sales invoices), {@code false} for payments
   * @return the contracts, in presentation order
   */
  static Map<String, NeoActionContract> actionContracts(boolean isReceipt) {
    String doc = isReceipt ? "sales invoice" : "purchase invoice";
    String money = isReceipt ? "collection" : "payment";
    String idDescription = "the id of the " + doc + " the " + money + " is for";
    String spec = isReceipt ? "sales-invoice" : "purchase-invoice";
    String installments = "neo_list(spec:'" + spec + "', entity:'paymentPlan', parentId:<invoice "
        + "id>) lists the invoice's installments with their outstanding amount";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(ACTION_NAME, NeoActionContract.write(ACTION_NAME,
        "Registers a " + money + " against one installment of this " + doc + ", exactly as the "
            + "invoice's payment panel does. This is the way to pay or collect an invoice — "
            + "payments are never created by hand. With process 'confirm' the " + money
            + " is processed and applied; with 'draft' it is saved for later and can be confirmed "
            + "with confirmPayment or edited by calling this again with paymentId. Returns the "
            + money + " {id, documentNo, amount, status, processed}. Before calling it: "
            + installments + " (the scheduleId); invoiceAccounts gives a valid account and the "
            + "methods it accepts.",
        Param.required(FIELD_SCHEDULE_ID, NeoActionContract.TYPE_STRING,
            "Id of the invoice installment (FIN_Payment_Schedule) being paid: "
                + installments + "."),
        Param.required(FIELD_AMOUNT, NeoActionContract.TYPE_NUMBER,
            "Amount of the " + money + ", in the invoice currency (the same currency as the "
                + "installment's outstanding amount). When the account is in another currency, "
                + "the account " + (isReceipt ? "receives" : "pays") + " this amount × "
                + "conversionRate. Less than the outstanding amount is a partial " + money
                + "; more is an overpayment and then overpaymentAction decides what happens "
                + "with the excess."),
        Param.required(FIELD_DATE, NeoActionContract.TYPE_DATE,
            "Date of the " + money + " (yyyy-MM-dd)."),
        Param.required(FIELD_ACCOUNT, NeoActionContract.TYPE_STRING,
            "Id of the financial account the money " + (isReceipt ? "arrives in" : "leaves from")
                + ". invoiceAccounts lists the valid ones, with the payment methods each accepts."),
        Param.optional("fin_paymentmethod_id", NeoActionContract.TYPE_STRING,
            "Id of the payment method. Must be one the chosen account accepts (invoiceAccounts → "
                + "paymentMethodIds). Default: the invoice's own payment method."),
        // Required for agents only (ETP-5558): the handler reads paymentId, conversionRate and
        // writeoffDifference only when the body carries process (or another advanced key), and
        // silently ignores them otherwise (§4.12.9). Requiring it here keeps REST unchanged.
        Param.requiredOptions("process",
            "'confirm' processes and applies the " + money + "; 'draft' only saves it, to confirm "
                + "later with confirmPayment. Always send it: paymentId, conversionRate and "
                + "writeoffDifference only take effect with it.",
            List.of("draft", "confirm")),
        Param.optional(FIELD_PAYMENT_ID, NeoActionContract.TYPE_STRING,
            "Id of an existing DRAFT " + money + " to edit in place instead of creating a new "
                + "one (same id and document number). A processed " + money + " cannot be edited."),
        Param.array("creditSources", NeoActionContract.TYPE_OBJECT, false,
            "Existing credit of the business partner to apply, each item either "
                + "{\"kind\":\"credit\",\"paymentId\":<payment with credit>,\"use\":<amount>} "
                + "for accumulated credit, or {\"kind\":\"abono\",\"psdId\":<credit-note "
                + "detail>,\"use\":<amount>} for a credit note. invoiceCreditSources lists both "
                + "kinds with their available amount ('avail'). Default: none."),
        Param.options("overpaymentAction",
            "What to do with an amount above the outstanding: 'leave-credit' keeps it as credit "
                + "of the business partner (default), 'refund' returns it.",
            List.of("leave-credit", "refund")),
        Param.optional("conversionRate", NeoActionContract.TYPE_NUMBER,
            "Exchange rate from the invoice currency to the account currency. Required, and "
                + "positive, when the invoice and account currencies differ; ignored otherwise. "
                + "currencyOptions lists the currencies with a rate for the invoice date."),
        Param.optional("writeoffDifference", NeoActionContract.TYPE_BOOLEAN,
            "true writes off the difference between the amount and the outstanding, closing the "
                + "installment. The write-off is capped by the account's writeoffLimit "
                + "(invoiceAccounts): a larger difference is refused. Default: false."))
        .withIdDescription(idDescription));
    contracts.put(CONFIRM_ACTION, NeoActionContract.write(CONFIRM_ACTION,
        "Processes a DRAFT " + money + " of this invoice (one registered with process 'draft'). "
            + "Returns the processed " + money + ".",
        Param.required(FIELD_PAYMENT_ID, NeoActionContract.TYPE_STRING,
            "Id of the draft " + money + " (invoicePayments).")).withIdDescription(idDescription));
    contracts.put(DELETE_ACTION, NeoActionContract.write(DELETE_ACTION,
        "Deletes a DRAFT " + money + " of this invoice. A processed one cannot be deleted.",
        Param.required(FIELD_PAYMENT_ID, NeoActionContract.TYPE_STRING,
            "Id of the draft " + money + " (invoicePayments).")).withIdDescription(idDescription));
    contracts.put(LIST_ACTION, NeoActionContract.read(LIST_ACTION,
        "Lists the " + money + "s already registered against this invoice: id, documentNo, "
            + "amount, status, processed, appliedToInvoice, account, conversionRate. A draft's id "
            + "is what confirmPayment, deletePayment and registerPayment's paymentId take.")
        .withIdDescription(idDescription));
    contracts.put(ACCOUNTS_ACTION, NeoActionContract.read(ACCOUNTS_ACTION,
        "Lists the financial accounts a " + money + " of this invoice can use: id, label, "
            + "currency, writeoffLimit, paymentMethodIds (the methods each accepts) and the "
            + "default method.").withIdDescription(idDescription));
    contracts.put(METHODS_ACTION, NeoActionContract.read(METHODS_ACTION,
        "Lists the payment methods available for a " + money + " of this invoice: id, label.")
        .withIdDescription(idDescription));
    contracts.put(CREDIT_SOURCES_ACTION, NeoActionContract.read(CREDIT_SOURCES_ACTION,
        "Lists the business partner's credit that can fund a " + money + " of this invoice, in "
            + "the invoice currency: {kind:'credit', paymentId} for accumulated credit and "
            + "{kind:'abono', psdId} for credit notes, each with its available amount 'avail'. "
            + "Pass them to registerPayment's creditSources.",
        Param.optional("editPaymentId", NeoActionContract.TYPE_STRING,
            "When editing a draft, its id: the credit that draft already holds is counted as "
                + "available again.")).withIdDescription(idDescription));
    return contracts;
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
    boolean isDelete = DELETE_ACTION.equals(fieldName);
    if ((!isConfirm && !isDelete && !ACTION_NAME.equals(fieldName))
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

    // Validate inputs BEFORE opening an admin session, so malformed requests
    // return 400 without requiring a DB context.
    NeoResponse validationError = validateBody(body, fieldName);
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
      return PaymentCreditSourcesService.handleListCreditSources(context, isReceipt);
    }
    if (PIS_STATUS_ACTION.equals(fieldName)) {
      return PisPaymentService.handlePisPaymentStatus(context);
    }
    if (PIS_SUPPLIER_ACCOUNTS_ACTION.equals(fieldName)) {
      return PisPaymentService.handleListSupplierBankAccounts(context);
    }
    if (PIS_TEMPLATES_ACTION.equals(fieldName)) {
      return PisPaymentService.handlePisTemplates();
    }
    if (PIS_CANCEL_ACTION.equals(fieldName)) {
      return PisPaymentService.handleCancelPisPayment(context);
    }
    if (PIS_RETRY_ACTION.equals(fieldName)) {
      return PisDeferredPaymentService.handleRetryPisPayment(context);
    }
    return null;
  }

  /** Validates the required body fields; returns an error response, or null when valid. */
  private static NeoResponse validateBody(JSONObject body, String fieldName) {
    if (CONFIRM_ACTION.equals(fieldName) || DELETE_ACTION.equals(fieldName)) {
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
          return PaymentDraftEditService.confirmDraftPayment(body.optString(FIELD_PAYMENT_ID, null));
        }
        if (DELETE_ACTION.equals(fieldName)) {
          return PaymentDraftEditService.deleteDraftPayment(body.optString(FIELD_PAYMENT_ID, null));
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
