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

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.RemittanceLineCancel;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;

import com.etendoerp.go.schemaforge.RemittanceReturnState.LineState;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;
import com.etendoerp.payment.removal.util.TransactionRemovalUtil;

/**
 * ETP-6247 — returns («devoluciones») of a collected remittance, registered by hand, line by line:
 * the bank did not collect an invoice. Two declared actions of the remittance header
 * ({@link RemittanceHeaderHandler}), both behind {@code sepa-remittances}:
 * <ul>
 *   <li>{@value #ACTION_STATE} (read): what each line is, and whether it can be returned
 *       ({@link RemittanceReturnState});</li>
 *   <li>{@value #ACTION_RETURN} (write): returns lines, all or nothing.</li>
 * </ul>
 *
 * <p><b>What a return does</b>, in ONE transaction, with the remittance module's own code
 * ({@link RemittanceReturnProcess}, the module's {@code AutomatedReturnProcess}):
 * <ol>
 *   <li>the settlement's bank transaction — the module's settlement deposits the payment into the
 *       remittance account — is reactivated and removed
 *       ({@link TransactionRemovalUtil#reactivateAndRemove}); the module's undo refuses a payment
 *       that still has one, and its caller ignores that refusal;</li>
 *   <li>the module undoes each settlement, creates ONE negative payment for the total, writes the
 *       return rows and voids the original payments: the invoices' dues are pending again and the
 *       remittance status becomes {@code CP}/{@code PP} (it stays partial even when every line is
 *       returned: the status counts return rows).</li>
 * </ol>
 * Before touching anything every line is validated ({@link RemittanceReturnState}); after the run
 * the outcome is verified, because the module swallows some of its own errors. Any failure rolls
 * back everything: 422 with a translated key.
 *
 * <p>The payment is the returnable unit: with grouping {@code GGPD} one payment covers several
 * lines, and a return must name all of them ({@code paymentLineIds} of {@code returnState}).
 * Reconciled or posted settlements are refused; so is a payment (not collection) remittance.
 */
final class RemittanceReturnAction {

  private static final Logger log = LogManager.getLogger(RemittanceReturnAction.class);

  static final String ACTION_STATE = "returnState";
  static final String ACTION_RETURN = "returnLines";
  static final String P_LINE_IDS = "lineIds";
  static final String P_RETURN_DATE = "returnDate";

  static final String MSG_NOT_ALLOWED = "ETGO_RemittanceReturnNotAllowed";
  static final String MSG_LINE_NOT_FOUND = "ETGO_RemittanceReturnLineNotFound";
  /** Every requested line id was blank: nothing to return. */
  static final String MSG_NO_LINES = "ETGO_RemittanceReturnNoLines";
  static final String MSG_DATE_INVALID = "ETGO_RemittanceReturnDateInvalid";
  static final String MSG_FAILED = "ETGO_RemittanceReturnFailed";
  /** Reason for a payment shared with lines the request did not name. */
  static final String SHARED_PAYMENT = "SHARED_PAYMENT";

  private static final int SC_UNPROCESSABLE = NeoActionContract.SC_UNPROCESSABLE;
  private static final String POST = "POST";
  private static final String KEY_PROBLEMS = "problems";

  private RemittanceReturnAction() {
  }

  /** Declares the two actions on the remittance header. */
  static void addContracts(Map<String, NeoActionContract> contracts) {
    contracts.put(ACTION_STATE, NeoActionContract.read(ACTION_STATE,
        "What each line of the remittance is as far as returns go. Returns collection (true for a "
            + "collection remittance), hasReturns and lines: an object keyed by line id with "
            + "state (PENDING not processed, PROCESSED processed but not settled, SETTLED "
            + "collected, RETURNED), returnable, reasonCode when it is not returnable "
            + "(NOT_PROCESSED, NOT_SETTLED, ALREADY_RETURNED, NOT_A_COLLECTION, POSTED, "
            + "RECONCILED), returnDate (of a returned line), paymentId and paymentLineIds (the "
            + "lines that share the payment: a return must include all of them).")
        .withIdDescription("the id of the remittance"));
    contracts.put(ACTION_RETURN, NeoActionContract.write(ACTION_RETURN,
        "Registers the return of collected lines (the bank did not collect them), all or "
            + "nothing: reverses the settlement and its bank transaction, voids the payments of "
            + "the lines (their invoice dues are pending again) and moves the remittance to "
            + "partially collected. Only for a collection remittance, lines settled and not "
            + "posted or reconciled; a payment shared by several lines is returned with all of "
            + "them. Returns status, returned (line ids), returnPaymentId and "
            + "returnPaymentDocumentNo. A refusal lists problems per line.",
        Param.array(P_LINE_IDS, NeoActionContract.TYPE_STRING, true,
            "Ids of the remittance lines to return."),
        Param.optional(P_RETURN_DATE, NeoActionContract.TYPE_DATE,
            "Return date, yyyy-MM-dd, not in the future. Default: today."))
        .withIdDescription("the id of the remittance")
        .withFieldValuesBody());
  }

  /** @return whether {@code action} is one of this class's actions */
  static boolean serves(String action) {
    return ACTION_STATE.equals(action) || ACTION_RETURN.equals(action);
  }

  /**
   * Runs one of the actions: 405 for {@value #ACTION_RETURN} that is not a POST, the contract's 422
   * for a malformed call, 404 for an unknown remittance. {@value #ACTION_RETURN} locks the
   * remittance row ({@link RemittanceHeaderHandler#lockRemittance}) before validating any line, so
   * two concurrent returns cannot both pass the check.
   *
   * @param handler the remittance header customization (its seams)
   * @param context the action request
   * @return the answer
   */
  static NeoResponse handle(RemittanceHeaderHandler handler, NeoContext context) {
    String action = context.getFieldName();
    if (ACTION_RETURN.equals(action) && !POST.equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          action + " only supports POST");
    }
    OBContext.setAdminMode(true);
    try {
      JSONObject body = context.getRequestBody() != null ? context.getRequestBody()
          : new JSONObject();
      JSONObject params = handler.actionParams(action, body);
      NeoResponse invalid = NeoActionContract.validate(handler.actionContracts(), action, params);
      if (invalid != null) {
        return invalid;
      }
      Remittance remittance = handler.loadRemittance(context.getRecordId());
      if (remittance == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Remittance not found");
      }
      if (ACTION_STATE.equals(action)) {
        return NeoResponse.ok(state(remittance));
      }
      // Row lock BEFORE the line states are read: two concurrent returns of the same line would
      // otherwise both see it SETTLED and return it twice.
      handler.lockRemittance(remittance);
      return returnLines(handler, remittance, params);
    } catch (Exception e) {
      log.error("remittance action {} failed on {}", action, context.getRecordId(), e);
      NeoErrorResponses.rollbackUnlessBatchOwned();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + action + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------------------
  // returnState
  // ---------------------------------------------------------------------------

  /**
   * The {@value #ACTION_STATE} answer.
   *
   * @param remittance the remittance
   * @return {@code {collection, hasReturns, lines: {lineId: {...}}}}
   */
  static JSONObject state(Remittance remittance) throws JSONException {
    JSONObject lines = new JSONObject();
    for (LineState line : RemittanceReturnState.of(remittance).values()) {
      JSONObject entry = new JSONObject();
      entry.put("state", line.state);
      entry.put("returnable", line.returnable());
      entry.put("reasonCode", line.returnable() ? JSONObject.NULL : line.reasonCode);
      entry.put(P_RETURN_DATE, line.returnDate != null ? line.returnDate : JSONObject.NULL);
      entry.put("paymentId", line.paymentId != null ? line.paymentId : JSONObject.NULL);
      entry.put("paymentLineIds", new JSONArray(line.paymentLineIds));
      lines.put(line.lineId, entry);
    }
    JSONObject body = new JSONObject();
    body.put("collection", RemittanceReturnState.isCollection(remittance));
    body.put("hasReturns", RemittanceReturnState.hasReturns(remittance));
    body.put("lines", lines);
    return body;
  }

  // ---------------------------------------------------------------------------
  // returnLines
  // ---------------------------------------------------------------------------

  /**
   * «Registrar devolución».
   *
   * @param handler    the remittance header customization (its seams)
   * @param remittance a remittance of the tenant
   * @param params     {@value #P_LINE_IDS}, {@value #P_RETURN_DATE}
   * @return the answer, or 422 (also when every requested line id is blank)
   * @throws JSONException when the answer body cannot be built
   */
  static NeoResponse returnLines(RemittanceHeaderHandler handler, Remittance remittance,
      JSONObject params) throws JSONException {
    String date = returnDate(handler, params);
    if (date == null) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_DATE_INVALID);
    }
    Set<String> requested = lineIds(params);
    if (requested.isEmpty()) {
      // Through handle() the contract already refuses blank ids; this guards a direct caller,
      // which would otherwise run a return of nothing.
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_NO_LINES);
    }
    Map<String, LineState> states = RemittanceReturnState.of(remittance);
    for (String id : requested) {
      if (!states.containsKey(id)) {
        return NeoMessageText.error(SC_UNPROCESSABLE, MSG_LINE_NOT_FOUND, id);
      }
    }
    JSONArray problems = problemsOf(remittance, requested, states);
    if (problems.length() > 0) {
      NeoResponse refusal = NeoMessageText.error(SC_UNPROCESSABLE, MSG_NOT_ALLOWED,
          problems.length());
      JSONObject error = refusal.getBody() != null
          ? refusal.getBody().optJSONObject("error")
          : null;
      if (error != null) {
        error.put(KEY_PROBLEMS, problems);
      }
      return refusal;
    }
    return execute(remittance, requested, states, date);
  }

  /** The problems of the request, one per line that cannot be returned; empty when none. */
  private static JSONArray problemsOf(Remittance remittance, Set<String> requested,
      Map<String, LineState> states) throws JSONException {
    Map<String, RemittanceLine> lines = new LinkedHashMap<>();
    for (RemittanceLine line : RemittanceFileReadiness.activeLines(remittance)) {
      lines.put(line.getId(), line);
    }
    JSONArray problems = new JSONArray();
    for (String id : requested) {
      LineState line = states.get(id);
      String reason = line.returnable() ? sharedReason(line, requested) : line.reasonCode;
      if (reason != null) {
        problems.put(problem(lines.get(id), line, reason));
      }
    }
    return problems;
  }

  private static String sharedReason(LineState line, Set<String> requested) {
    return requested.containsAll(line.paymentLineIds) ? null : SHARED_PAYMENT;
  }

  private static JSONObject problem(RemittanceLine line, LineState state, String reason)
      throws JSONException {
    String docNo = documentNoOf(line);
    JSONObject problem = new JSONObject();
    problem.put("lineId", state.lineId);
    problem.put("reasonCode", reason);
    problem.put("documentNo", docNo != null ? docNo : JSONObject.NULL);
    // Object[] on purpose: a lone String second argument would bind to text(key, LANGUAGE, ...).
    Object[] args = {docNo != null ? docNo : "-"};
    problem.put("message", NeoMessageText.text(reasonKey(reason), args));
    return problem;
  }

  /** The catalog key of a reason, {@code ETGO_RemittanceReturnReason<Camel>}. */
  static String reasonKey(String reason) {
    StringBuilder key = new StringBuilder("ETGO_RemittanceReturnReason");
    for (String part : reason.split("_")) {
      key.append(part.charAt(0)).append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
    }
    return key.toString();
  }

  private static String documentNoOf(RemittanceLine line) {
    if (line == null || line.getPaymentScheduleDetail() == null
        || line.getPaymentScheduleDetail().getInvoicePaymentSchedule() == null
        || line.getPaymentScheduleDetail().getInvoicePaymentSchedule().getInvoice() == null) {
      return null;
    }
    return line.getPaymentScheduleDetail().getInvoicePaymentSchedule().getInvoice().getDocumentNo();
  }

  private static Set<String> lineIds(JSONObject params) {
    Set<String> ids = new LinkedHashSet<>();
    JSONArray array = params.optJSONArray(P_LINE_IDS);
    for (int i = 0; array != null && i < array.length(); i++) {
      String id = StringUtils.trimToNull(array.optString(i, null));
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }

  /** The return date as {@code yyyy-MM-dd}: today when absent; {@code null} when invalid or future. */
  private static String returnDate(RemittanceHeaderHandler handler, JSONObject params) {
    LocalDate today = handler.today();
    String raw = StringUtils.trimToNull(params.optString(P_RETURN_DATE, null));
    if (raw == null) {
      return today.toString();
    }
    try {
      LocalDate date = LocalDate.parse(raw);
      return date.isAfter(today) ? null : date.toString();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static NeoResponse execute(Remittance remittance, Set<String> requested,
      Map<String, LineState> states, String date) throws JSONException {
    String remittanceId = remittance.getId();
    // The Classic helpers below read RequestContext.get().getVariablesSecureApp(), which a NEO
    // request does not guarantee: pushed here, restored on close (as NeoProcessService does).
    try (NeoProcessService.RequestContextScope ignored =
        NeoProcessService.pushRequestContextVars()) {
      Set<String> paymentIds = new LinkedHashSet<>();
      for (String id : requested) {
        paymentIds.add(states.get(id).paymentId);
      }
      FIN_PaymentMethod method =
          RemittanceSepaProvisioning.ensureReturnPaymentMethod(remittance.getClient());
      OBDal.getInstance().flush();
      List<String> transactionIds = transactionIdsOf(paymentIds);
      String methodId = method.getId();
      // Reactivating a transaction may clear the session: everything below is re-read by id.
      for (String transactionId : transactionIds) {
        TransactionRemovalUtil.reactivateAndRemove(transactionId);
      }
      JSONObject body = runModuleReturn(remittanceId, paymentIds, requested, methodId, date);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("returnLines failed on remittance {}", remittanceId, e);
      String text = StringUtils.defaultIfBlank(
          NeoMessageTranslator.safeParseTranslation(e.getMessage()), "-");
      // Translated first: the rollback closes the session.
      NeoErrorResponses.rollbackUnlessBatchOwned();
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_FAILED, text);
    }
  }

  private static List<String> transactionIdsOf(Set<String> paymentIds) {
    List<String> ids = new ArrayList<>();
    for (String paymentId : paymentIds) {
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
      for (FIN_FinaccTransaction transaction : payment.getFINFinaccTransactionList()) {
        ids.add(transaction.getId());
      }
    }
    return ids;
  }

  /** Runs the module's return and verifies what it did; throws when the outcome is not complete. */
  private static JSONObject runModuleReturn(String remittanceId, Set<String> paymentIds,
      Set<String> requested, String methodId, String date) throws Exception {
    Remittance remittance = OBDal.getInstance().get(Remittance.class, remittanceId);
    Map<String, RemittanceLineCancel> cancels = RemittanceReturnState.settlementRows(remittance);
    JSONArray cancelIds = new JSONArray();
    FIN_Payment reference = null;
    for (String paymentId : paymentIds) {
      RemittanceLineCancel cancel = cancels.get(paymentId);
      if (cancel == null) {
        throw new IllegalStateException("No settlement row for payment " + paymentId);
      }
      cancelIds.put(cancel.getId());
      reference = reference != null ? reference : cancel.getPayment();
    }
    FIN_PaymentMethod method = OBDal.getInstance().get(FIN_PaymentMethod.class, methodId);
    FIN_Payment negative = new RemittanceReturnProcess(method).returnPayments(cancelIds, date,
        remittance, reference);
    OBDal.getInstance().flush();
    String negativeId = negative.getId();
    String negativeNo = negative.getDocumentNo();
    verify(remittanceId, paymentIds);
    Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittanceId);
    JSONObject body = new JSONObject();
    Object status = reloaded.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS);
    body.put("status", status != null ? status : JSONObject.NULL);
    body.put("returned", new JSONArray(requested));
    body.put("returnPaymentId", negativeId);
    body.put("returnPaymentDocumentNo", negativeNo != null ? negativeNo : JSONObject.NULL);
    return body;
  }

  /** The module swallows some of its errors: the payments must be void and the settlements gone. */
  private static void verify(String remittanceId, Set<String> paymentIds) {
    Remittance remittance = OBDal.getInstance().get(Remittance.class, remittanceId);
    Map<String, RemittanceLineCancel> cancels = RemittanceReturnState.settlementRows(remittance);
    for (String paymentId : paymentIds) {
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
      if (!RemittanceReturnState.PAYMENT_VOID.equals(payment.getStatus())
          || cancels.containsKey(paymentId)) {
        throw new IllegalStateException("The return of payment " + payment.getDocumentNo()
            + " did not complete");
      }
    }
    if (!RemittanceReturnState.hasReturns(remittance)) {
      throw new IllegalStateException("The return rows were not written");
    }
  }
}
