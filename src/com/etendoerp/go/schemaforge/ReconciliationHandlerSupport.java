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

import static com.etendoerp.go.schemaforge.ReconciliationSupport.docTypeToIsReceipt;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.nullSafe;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.financial.ResetAccounting;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;
import com.etendoerp.payment.removal.util.ReconciliationRemovalUtil;
import com.etendoerp.payment.removal.util.TransactionRemovalUtil;

/**
 * Stateless helpers extracted out of {@link ReconciliationHandler} so that class stays under the
 * Sonar per-class method-count limit (java:S1448) and its {@code buildPendingLines},
 * {@code buildInvoiceCandidates} and {@code removeOperation} methods stay under the cognitive
 * complexity limit (java:S3776). Every method here is a straight move of logic that used to live in
 * the handler; behavior is unchanged.
 *
 * <p>The handler-owned DAL / Classic-layer seams ({@code loadLine}, {@code loadTransaction},
 * {@code checkPeriod}, {@code undoReconciliation}, {@code isAutoCreated}, {@code doRollbackAndClose}
 * and the per-action business methods) are invoked back through the passed {@link
 * ReconciliationHandler} instance, so the unit-test spies that stub those seams keep intercepting
 * them exactly as before.
 */
final class ReconciliationHandlerSupport {

  private static final Logger log = LogManager.getLogger(ReconciliationHandlerSupport.class);

  private ReconciliationHandlerSupport() {
    // utility class — no instances
  }

  private static final String ACTION_RECONCILE_GROUP = "reconcileGroup";
  private static final String ACTION_APPLY_SUGGESTIONS = "applySuggestions";
  private static final String ACTION_REACTIVATE = "reactivate";
  private static final String ACTION_REMOVE_OPERATION = "removeOperation";
  private static final String ACTION_REACTIVATE_SELECTED = "reactivateSelected";
  private static final String ACTION_RECONCILE_DIFFERENCE = "reconcileDifference";

  // ---------------------------------------------------------------------------
  // POST action dispatch wrappers (OBContext admin mode + rollback boilerplate)
  // ---------------------------------------------------------------------------

  static NeoResponse handleReconcileGroup(ReconciliationHandler handler, NeoContext context) {
    return runPostAction(handler, context, ACTION_RECONCILE_GROUP);
  }

  static NeoResponse handleApplySuggestions(ReconciliationHandler handler, NeoContext context) {
    return runPostAction(handler, context, ACTION_APPLY_SUGGESTIONS);
  }

  static NeoResponse handleReactivate(ReconciliationHandler handler, NeoContext context) {
    return runPostAction(handler, context, ACTION_REACTIVATE);
  }

  static NeoResponse handleRemoveOperation(ReconciliationHandler handler, NeoContext context) {
    return runPostAction(handler, context, ACTION_REMOVE_OPERATION);
  }

  static NeoResponse handleReactivateSelected(ReconciliationHandler handler, NeoContext context) {
    return runPostAction(handler, context, ACTION_REACTIVATE_SELECTED);
  }

  static NeoResponse handleReconcileDifference(ReconciliationHandler handler, NeoContext context) {
    return runPostAction(handler, context, ACTION_RECONCILE_DIFFERENCE);
  }

  // ---------------------------------------------------------------------------
  // GET action dispatch wrappers (param reading + admin mode + error mapping)
  // ---------------------------------------------------------------------------

  /**
   * Reads the query params, runs the builder in admin mode and maps any failure to a 500 — the
   * read-only counterpart of {@link #runPostAction}. These live here, next to the POST wrappers, so
   * {@link ReconciliationHandler} stays under the Sonar per-class method limit (java:S1448); the
   * {@code build*} methods they delegate to remain on the handler as test seams.
   */
  static NeoResponse handlePendingLines(ReconciliationHandler handler, NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String accountId = qp != null ? qp.get(ReconciliationHandler.PARAM_ACCOUNT_ID) : null;
    if (StringUtils.isBlank(accountId)) {
      return missingParam(ReconciliationHandler.PARAM_ACCOUNT_ID);
    }
    try {
      OBContext.setAdminMode(true);
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      Set<String> orgs = ReconciliationSupport.accessibleOrgs(
          OBContext.getOBContext().getCurrentOrganization().getId());
      return handler.buildPendingLines(accountId, clientId, orgs, qp);
    } catch (Exception e) {
      log.error("Error building pendingLines for account {}", accountId, e);
      return internalError();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** {@link #handlePendingLines} for the candidates list; branches on {@code kind=invoices}. */
  static NeoResponse handleCandidates(ReconciliationHandler handler, NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String accountId = qp != null ? qp.get(ReconciliationHandler.PARAM_ACCOUNT_ID) : null;
    if (StringUtils.isBlank(accountId)) {
      return missingParam(ReconciliationHandler.PARAM_ACCOUNT_ID);
    }
    String lineId = qp != null ? qp.get(ReconciliationHandler.PARAM_LINE_ID) : null;
    String docType = qp != null ? qp.get(ReconciliationHandler.PARAM_DOC_TYPE) : null;
    String kind = qp != null ? qp.get(ReconciliationHandler.PARAM_KIND) : null;
    String dateFrom = qp != null ? qp.get(ReconciliationHandler.PARAM_DATE_FROM) : null;
    String dateTo = qp != null ? qp.get(ReconciliationHandler.PARAM_DATE_TO) : null;
    try {
      OBContext.setAdminMode(true);
      if (ReconciliationHandler.KIND_INVOICES.equalsIgnoreCase(kind)) {
        return handler.buildInvoiceCandidates(accountId, lineId, docType, dateFrom, dateTo);
      }
      return handler.buildCandidates(accountId, lineId, docType, dateFrom, dateTo);
    } catch (Exception e) {
      log.error("Error building candidates for account {}", accountId, e);
      return internalError();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** {@link #handlePendingLines} for the automatch preview (never mutates data). */
  static NeoResponse handleAutoMatch(ReconciliationHandler handler, NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String accountId = qp != null ? qp.get(ReconciliationHandler.PARAM_ACCOUNT_ID) : null;
    if (StringUtils.isBlank(accountId)) {
      return missingParam(ReconciliationHandler.PARAM_ACCOUNT_ID);
    }
    try {
      OBContext.setAdminMode(true);
      return handler.buildAutoMatch(accountId);
    } catch (Exception e) {
      log.error("Error building autoMatch for account {}", accountId, e);
      return internalError();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Reverses one matched transaction's auto-created movement (or restores its "not cleared" status)
   * as the last cleanup step of {@code ReconciliationHandler.undoReconciliation}. Catches and logs
   * its own failure instead of aborting that loop: Core's reversal utilities commit mid-flow, so a
   * failure on transaction K does not roll back 1..K-1 — aborting would only leave K+1..N unprocessed
   * too, compounding the inconsistency. The reconciliation itself is already undone by the time this
   * runs, so a failed reversal here is a rare, logged, individually-recoverable leftover.
   */
  static void reverseMatchedTransaction(ReconciliationHandler handler, FIN_FinaccTransaction t) {
    try {
      if (handler.isAutoCreated(t)) {
        FIN_Payment payment = t.getFinPayment();
        if (payment != null) {
          PaymentRemovalUtil.reactivateAndRemove(payment);
        } else {
          TransactionRemovalUtil.reactivateAndRemove(t.getId());
        }
      } else {
        ReactivationSupport.restoreNotClearedStatus(t);
      }
    } catch (Exception e) {
      log.error("Failed to reverse the auto-created movement for transaction {} while undoing a "
          + "reconciliation; the reconciliation itself was already undone.", t.getId(), e);
    }
  }

  private static NeoResponse missingParam(String param) {
    return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        ReconciliationHandler.MSG_MISSING_PARAM + param);
  }

  private static NeoResponse internalError() {
    return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        ReconciliationHandler.MSG_INTERNAL_SERVER_ERROR);
  }

  /**
   * Shared dispatch envelope for the mutating POST actions: rejects an empty body, runs the action
   * in admin mode, maps a business {@link OBException} to 400 (+rollback) and any other failure to
   * 500 (+rollback), and always restores the previous OBContext mode. Identical to the per-action
   * wrappers this replaced.
   */
  private static NeoResponse runPostAction(ReconciliationHandler handler, NeoContext context,
      String action) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_BODY_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      return callHandlerAction(handler, action, body);
    } catch (OBException e) {
      log.warn("{} business error: {}", action, e.getMessage());
      handler.doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("{} failed", action, e);
      handler.doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          ReconciliationHandler.MSG_INTERNAL_SERVER_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Runs the named business method on the handler. Translating its bare {@code throws Exception}
   * here — a business {@link OBException} propagates unwrapped (mapped to 400 by {@link
   * #runPostAction}); anything else is wrapped in a dedicated checked {@link
   * ReconciliationActionException} (mapped to 500) — is what lets every dispatch seam avoid a
   * generic {@code throws} (Sonar java:S112). Behavior is identical to the previous method-reference
   * dispatch.
   */
  private static NeoResponse callHandlerAction(ReconciliationHandler handler, String action,
      JSONObject body) throws ReconciliationActionException {
    try {
      switch (action) {
        case ACTION_RECONCILE_GROUP:
          return handler.reconcileGroup(body);
        case ACTION_APPLY_SUGGESTIONS:
          return handler.applySuggestions(body);
        case ACTION_REACTIVATE:
          return handler.reactivate(body);
        case ACTION_REMOVE_OPERATION:
          return handler.removeOperation(body);
        case ACTION_REACTIVATE_SELECTED:
          return handler.reactivateSelected(body);
        // Unlike its siblings this one lands on a support class, not on the handler: the handler
        // sits at the Sonar S1448 method-count ceiling (see ReconciliationDifferenceSupport).
        case ACTION_RECONCILE_DIFFERENCE:
          return ReconciliationDifferenceSupport.reconcileDifference(handler, body);
        default:
          throw new ReconciliationActionException(
              new IllegalArgumentException("Unknown reconciliation action: " + action));
      }
    } catch (OBException | ReconciliationActionException e) {
      throw e;
    } catch (Exception e) {
      throw new ReconciliationActionException(e);
    }
  }

  // ---------------------------------------------------------------------------
  // pendingLines summary (post-merge classification + counts)
  // ---------------------------------------------------------------------------

  /**
   * How much of a statement line is already reconciled, in the same sign convention as its
   * amount: {@code 0} for a fully pending line, the whole amount for a fully reconciled one,
   * something in between for a partial group.
   *
   * <p>MAGNITUDES first, then re-sign. {@code amount} is SIGNED — a withdrawal is negative — while
   * {@code pending} is the unsigned {@code |cramount - dramount|} that
   * {@code BankStatementLinePendingAmountHandler} stores (and that
   * {@code BankStatementsSupport.mergeMatchGroups} sums across a split group's sub-lines). The
   * plain {@code amount - pending} this replaces only held when both happened to share a sign:
   * for a fully pending withdrawal it gave {@code -0.50 - 0.50 = -1.00} instead of {@code 0}, so
   * the UI's {@code ProgressCell} — which draws a bar whenever {@code reconciledAmount != 0} —
   * put a solid "100% reconciled" bar (200%, clamped) on a line with nothing reconciled, right
   * under its "Pendiente" badge. Deposits were correct only by coincidence (ETP-4921).
   *
   * <p>Clamped at zero: {@code pending > |amount|} is a data anomaly, and reporting "nothing
   * reconciled" is the honest reading of it — the alternative flips the sign and draws a bar
   * pointing the wrong way.
   *
   * @param amount  the line's signed amount
   * @param pending the unsigned amount still pending to reconcile
   * @return the reconciled portion, signed like {@code amount}
   */
  static BigDecimal signedReconciledAmount(BigDecimal amount, BigDecimal pending) {
    BigDecimal magnitude = amount.abs().subtract(pending.abs()).max(BigDecimal.ZERO);
    return amount.signum() < 0 ? magnitude.negate() : magnitude;
  }

  /**
   * Derives per-line reconciled amounts / progress and the fine-grained {@code state}, accumulates
   * the running total and the per-state counts, and builds the {@code pendingLines} data envelope
   * body. Extracted verbatim from {@code ReconciliationHandler.buildPendingLines}.
   */
  static JSONObject summarizePendingLines(JSONArray lines, FIN_FinancialAccount account,
      List<MatchRuleEngine.Rule> rules, int pendingDateTolDays, BigDecimal pendingAmtTolPct)
      throws JSONException {
    BigDecimal total = BigDecimal.ZERO;
    Map<String, Integer> counts = AutoMatchSupport.newCounts();
    // Shared across every PENDING row below (same rows/order as ReconciliationHandler.buildAutoMatch
    // iterates), so a transaction already claimed by an earlier line does not also count as
    // "suggested" for a later line of the same amount — see AutoMatchSupport.classifyPendingLine.
    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new ArrayList<>();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject row = lines.getJSONObject(i);
      BigDecimal amount =
          nullSafe(new BigDecimal(row.optString(ReconciliationHandler.KEY_AMOUNT, "0")));
      total = total.add(amount);
      // Reconciled/pending amounts + progress % for the left "Progreso" bar and the right block.
      BigDecimal pending = nullSafe(new BigDecimal(row.optString("pendingAmount", "0")));
      BigDecimal reconciled = signedReconciledAmount(amount, pending);
      row.put("reconciledAmount", reconciled);
      int pct = amount.signum() == 0 ? 0
          : (int) Math.round(reconciled.abs().doubleValue() / amount.abs().doubleValue() * 100.0);
      row.put("reconciledPct", pct);
      // Derive the fine-grained state per merged (logical) line. A PARTIAL group stays in the
      // pending universe (shows under the "Pendiente" filter, counted as pending) but is visibly
      // partial via the progress bar; a fully-pending line is classified as before.
      String recStatus = row.optString("reconcileStatus", "PENDING");
      String state;
      if ("RECONCILED".equals(recStatus)) {
        state = ReconciliationHandler.STATUS_RECONCILED;
        row.put(ReconciliationHandler.KEY_STATUS, ReconciliationHandler.STATUS_RECONCILED);
      } else if ("PARTIAL".equals(recStatus)) {
        state = ReconciliationHandler.STATUS_PENDING;
        row.put(ReconciliationHandler.KEY_STATUS, ReconciliationHandler.STATUS_PENDING);
        row.put("partial", true);
      } else {
        state = AutoMatchSupport.classifyPendingLine(account,
            row.optString(ReconciliationHandler.KEY_ID), rules, pendingDateTolDays,
            pendingAmtTolPct, usedTxnIds, excludedTxns);
        row.put(ReconciliationHandler.KEY_STATUS, ReconciliationHandler.STATUS_PENDING);
      }
      row.put("state", state);
      counts.put("all", counts.get("all") + 1);
      counts.put(state, counts.getOrDefault(state, 0) + 1);
    }
    JSONObject countsJson = new JSONObject();
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      countsJson.put(entry.getKey(), entry.getValue());
    }
    JSONObject data = new JSONObject();
    data.put("lines", lines);
    data.put(ReconciliationHandler.KEY_TOTAL, total);
    data.put(ReconciliationHandler.KEY_COUNTS, countsJson);
    return data;
  }

  // ---------------------------------------------------------------------------
  // invoice-candidates helpers
  // ---------------------------------------------------------------------------

  /**
   * Resolves the flow direction for {@code buildInvoiceCandidates}: the UI's transaction-type
   * selector passes {@code docType} (receipts → sales/Y, payments → purchase/N); when it is blank,
   * the selected line's sign decides ({@code null} when there is no line or a zero-amount line).
   * Extracted verbatim from {@code ReconciliationHandler.buildInvoiceCandidates}.
   */
  static Boolean resolveInvoiceDirection(ReconciliationHandler handler, String docType,
      String lineId) {
    if (StringUtils.isNotBlank(docType)) {
      return "Y".equals(docTypeToIsReceipt(docType));
    }
    FIN_BankStatementLine line = StringUtils.isNotBlank(lineId) ? handler.loadLine(lineId) : null;
    int sign = line != null
        ? nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount())).signum() : 0;
    return sign == 0 ? null : sign > 0;
  }

  /**
   * Appends {@code rate}, {@code amountBase} (= {@code signedAmount × rate}) and
   * {@code baseCurrency} to a foreign-currency invoice candidate row, using the SAME rate source
   * reconciling this invoice would use ({@link PaymentCurrencyConverter#resolveInvoiceRate}).
   * Swallows a missing-rate failure — the row is still usable for reconciliation (the rate is
   * re-resolved then), it just can't preview a EUR-style equivalent up front. Extracted verbatim
   * from {@code ReconciliationHandler}.
   */
  static void appendAccountEquivalent(JSONObject row, String invoiceId,
      FIN_FinancialAccount account, BigDecimal signedAmount) {
    try {
      // tenant-ok: invoiceId comes from INVOICE_CANDIDATES_SQL, already scoped by client and org
      Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
      if (invoice == null) {
        return;
      }
      BigDecimal rate = PaymentCurrencyConverter.resolveInvoiceRate(invoice, account);
      row.put("rate", rate);
      row.put("amountBase", PaymentCurrencyConverter.convertedAmount(signedAmount, rate, account));
      row.put("baseCurrency", account.getCurrency().getISOCode());
    } catch (Exception e) {
      log.debug("No exchange rate available to preview invoice {} in the account currency: {}",
          invoiceId, e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // removeOperation helpers
  // ---------------------------------------------------------------------------

  /** Reads {@code transactionIds[]} from the body, falling back to a single {@code transactionId}. */
  static List<String> readTransactionIds(JSONObject body) {
    List<String> ids = new ArrayList<>();
    JSONArray arr = body.optJSONArray("transactionIds");
    if (arr != null) {
      for (int i = 0; i < arr.length(); i++) {
        String id = StringUtils.trimToNull(arr.optString(i, null));
        if (id != null && !ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    String single = StringUtils.trimToNull(body.optString(ReconciliationHandler.KEY_TRANSACTION_ID,
        null));
    if (ids.isEmpty() && single != null) {
      ids.add(single);
    }
    return ids;
  }

  /**
   * Resolves + validates every selected transaction and GROUPS them by their reconciliation into
   * {@code recById} / {@code selectedByRec}. Returns a non-null {@link NeoResponse} (the verbatim
   * error) on the first invalid transaction, or {@code null} on success. Extracted from
   * {@code ReconciliationHandler.removeOperation}.
   */
  static NeoResponse groupSelectedByReconciliation(ReconciliationHandler handler, String accountId,
      List<String> transactionIds, Map<String, FIN_Reconciliation> recById,
      Map<String, List<FIN_FinaccTransaction>> selectedByRec) {
    for (String id : transactionIds) {
      FIN_FinaccTransaction trx = handler.loadTransaction(id);
      if (trx == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Transaction not found: " + id);
      }
      FIN_Reconciliation r = trx.getReconciliation();
      if (r == null) {
        return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
            "Transaction is not linked to a reconciliation");
      }
      if (r.getAccount() == null || !accountId.equals(r.getAccount().getId())) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "The selected operations do not belong to the financial account");
      }
      recById.putIfAbsent(r.getId(), r);
      selectedByRec.computeIfAbsent(r.getId(), k -> new ArrayList<>()).add(trx);
    }
    return null;
  }

  /**
   * Accounting-period guard (same as reactivate): refuses to undo into a closed period, on any of
   * the affected reconciliations. Returns a non-null {@link NeoResponse} (409) for the first closed
   * period, or {@code null} when all are open. Extracted from
   * {@code ReconciliationHandler.removeOperation}.
   */
  static NeoResponse guardOpenPeriods(ReconciliationHandler handler,
      Collection<FIN_Reconciliation> reconciliations) {
    for (FIN_Reconciliation r : reconciliations) {
      try {
        handler.checkPeriod(r.getClient().getId(), r.getOrganization().getId(),
            r.getEntity().getTableId(), r.getTransactionDate());
      } catch (OBException e) {
        log.warn("removeOperation blocked by closed period for reconciliation {}: {}", r.getId(),
            e.getMessage());
        return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
            "The accounting period is closed and the operation cannot be un-reconciled: "
                + e.getMessage());
      }
    }
    return null;
  }

  /**
   * Per reconciliation: if the selection covers ALL of its transactions, undo the whole
   * reconciliation (payment removal); otherwise detach just the selected ones (the rest stay).
   * Extracted from {@code ReconciliationHandler.removeOperation}.
   *
   * <p>When the selection spans MULTIPLE reconciliations on the same account, processing one of
   * them ({@code undoWholeReconciliation} / {@code detachSelected} both call into Core's {@code
   * removeTransactionFromReconciliation}/{@code undoReconciliation}, which reprocess EVERY draft
   * reconciliation of the whole account, not just the one being handled) can churn the Hibernate
   * session and leave a reconciliation instance captured earlier — at grouping time, before any
   * processing — stale/detached. A later {@code save} on it then collides with the session's
   * freshly-reprocessed copy ({@code NonUniqueObjectException}). So each iteration re-fetches its
   * reconciliation fresh by id right before deciding/dispatching, never carrying the
   * grouping-time instance into a later iteration (same fix already applied per-transaction in
   * {@link #detachSelected}).
   *
   * <p>Neither branch below throws anymore: Core's removal utilities ({@code
   * PaymentRemovalUtil#reactivateAndRemove}) commit mid-flow ({@code
   * SessionHandler#commitAndStart}), so a failure on reconciliation/transaction K does NOT roll
   * back what 1..K-1 already persisted — aborting the rest of the batch on that failure would only
   * leave K+1..N unprocessed too, compounding the inconsistency instead of limiting it. So every
   * unit is attempted regardless of an earlier one's outcome; {@code
   * ReconciliationHandler.removeOperation} re-checks the ACTUAL post-state of every requested
   * transaction afterward (whether it is still linked to a reconciliation) rather than trusting
   * "no exception was thrown", and reports exactly what succeeded and what didn't.
   */
  static void removeSelectedFromReconciliations(ReconciliationHandler handler,
      FIN_FinancialAccount account, Map<String, FIN_Reconciliation> recById,
      Map<String, List<FIN_FinaccTransaction>> selectedByRec,
      Map<String, String> failureReasons) {
    // Unpost EVERY affected document first, in its own pass. ResetAccounting runs native SQL and
    // flushes/clears the Hibernate session, so doing this inside the removal loop below would leave
    // the instances that loop had just captured detached — the reconciliation reports "no current
    // state in the database" and the first transaction Core reloads collides with the stale copy
    // (NonUniqueObjectException). Same hazard this class already documents for its own iterations.
    Set<String> unpostFailed = new HashSet<>();
    for (String recId : recById.keySet()) {
      try {
        unpostBeforeUndo(recId);
      } catch (Exception e) {
        log.error("Could not unpost reconciliation {} before undoing it.", recId, e);
        recordFailure(refetch(selectedByRec.get(recId)), failureReasons, e);
        unpostFailed.add(recId);
      }
    }
    for (String recId : recById.keySet()) {
      // Skip what could not be unposted: the removal would fail too, and its (misleading) message
      // would overwrite the accurate reason recorded above.
      if (unpostFailed.contains(recId)) {
        continue;
      }
      // tenant-ok: recId comes from a reconciliation reached through a validated line
      FIN_Reconciliation r = OBDal.getInstance().get(FIN_Reconciliation.class, recId);
      List<FIN_FinaccTransaction> selForRec = refetch(selectedByRec.get(recId));
      if (coversReconciliation(r, selForRec)) {
        undoWholeReconciliation(handler, account, r, selForRec, failureReasons);
      } else {
        detachSelected(handler, selForRec, failureReasons);
      }
    }
  }

  /**
   * Reloads each transaction by id, so the caller never hands Core an instance captured before the
   * unposting pass churned the session.
   */
  private static List<FIN_FinaccTransaction> refetch(List<FIN_FinaccTransaction> txns) {
    List<FIN_FinaccTransaction> fresh = new ArrayList<>();
    if (txns == null) {
      return fresh;
    }
    for (FIN_FinaccTransaction t : txns) {
      FIN_FinaccTransaction reloaded =
          // tenant-ok: re-read after flush of a transaction already validated by groupSelectedByReconciliation
          OBDal.getInstance().get(FIN_FinaccTransaction.class, t.getId());
      if (reloaded != null) {
        fresh.add(reloaded);
      }
    }
    return fresh;
  }

  /**
   * Removes the reconciliation's accounting entries BEFORE anything tries to reactivate it.
   *
   * <p><b>Why this exists.</b> {@code com.etendoerp.payment.removal}'s
   * {@code Utilities.unPostReconciliation} resets accounting passing the RECONCILIATION's own date
   * as both ends of the range, but Core dates a reconciliation's {@code Fact_Acct} rows with the
   * TRANSACTION's accounting date. Those differ whenever the statement line is older than the day it
   * was reconciled — the normal case. The range then matches nothing, zero entries are deleted, and
   * {@code ResetAccounting} falls into its catch-all {@code throw}, whose only wording is
   * {@code @PeriodClosedForUnPosting@}. The user is told to open a period that was never closed.
   *
   * <p>Resetting first with an OPEN range — exactly what Classic's own unpost button does, and what
   * {@code DocumentPostingService.unpost} already does in this module — leaves the document with no
   * entries, so that narrow-range reset becomes a harmless no-op: {@code ResetAccounting} takes its
   * "record exists but has no facts" branch and returns cleanly instead of throwing. The
   * {@code recordId} argument already scopes the deletion to this one document, so an open range
   * removes nothing extra.
   *
   * <p>A genuinely closed period still fails, and now says so accurately, because the reset it
   * reports on is the one that actually went looking for the entries.
   *
   * <p>This compensates for a defect in another module instead of fixing it there, deliberately:
   * that module is outside this ticket's two repos. The same date-narrowing exists in its
   * {@code unPostPayment}. See the un-reconcile section of
   * {@code docs/generated-custom-windows/financial-account.md}.
   */
  static void unpostBeforeUndo(String reconciliationId) {
    if (StringUtils.isBlank(reconciliationId)) {
      return;
    }
    FIN_Reconciliation rec =
        // tenant-ok: re-read after flush; the id belongs to a reconciliation already resolved above
        OBDal.getInstance().get(FIN_Reconciliation.class, reconciliationId);
    if (rec == null || !"Y".equals(rec.getPosted())) {
      return;
    }
    String clientId = rec.getClient().getId();
    String orgId = rec.getOrganization().getId();
    String tableId = rec.getEntity().getTableId();
    ResetAccounting.delete(clientId, orgId, tableId, reconciliationId, "", "");
    // ResetAccounting issues native SQL and flushes/clears the session, so the instance read above
    // is detached by now. Saving THAT one is what made OBInterceptor report a record with no current
    // state in the database. Re-read before touching the flag.
    FIN_Reconciliation fresh =
        // tenant-ok: re-read after flush; same reconciliation as above
        OBDal.getInstance().get(FIN_Reconciliation.class, reconciliationId);
    if (fresh != null && !"N".equals(fresh.getPosted())) {
      fresh.setPosted("N");
      OBDal.getInstance().save(fresh);
      OBDal.getInstance().flush();
    }
  }

  /**
   * Records why {@code ids} could not be un-reconciled, keyed by transaction id.
   *
   * <p>Without this the reason only ever reached the server log: the caller correctly reported WHICH
   * transactions were still reconciled, but had nothing to say about WHY, so the UI could only show a
   * generic error. A closed accounting period — by far the most common cause, and the one the user
   * can actually act on — was indistinguishable from any other failure.
   */
  private static void recordFailure(List<FIN_FinaccTransaction> affected,
      Map<String, String> failureReasons, Exception cause) {
    String reason = userFacingReason(StringUtils.defaultIfBlank(cause.getMessage(), ""));
    for (FIN_FinaccTransaction t : affected) {
      if (t != null) {
        failureReasons.put(t.getId(), StringUtils.trimToEmpty(reason));
      }
    }
  }

  /** Matches an Etendo message key placeholder, e.g. {@code @PeriodClosedForUnPosting@}. */
  private static final Pattern MESSAGE_KEY = Pattern.compile("@(\\w+)@");

  /**
   * Reduces a Core exception chain to the one sentence a user can act on.
   *
   * <p>Core wraps each cause in untranslated English prose and concatenates the chain with no
   * separators, so the raw message arrives as
   * {@code "Error when removing the transaction from reconciliation.Error when reactivating
   * reconciliation@PeriodClosedForUnPosting@"}. Translating that whole string leaves the English
   * fragments glued to the front of the Spanish text — and this product is used in Spanish by real
   * clients, so shipping those fragments into a toast is a bug, not a cosmetic issue.
   *
   * <p>The only user-facing, translatable part is the {@code @KEY@} placeholder, so that is what
   * gets resolved — the LAST one, since the innermost cause is the specific one. A message with no
   * placeholder (a plain Java error, a database message) has nothing to extract and is translated
   * whole, as before.
   */
  static String userFacingReason(String rawMessage) {
    // Null-tolerant on its own: the only caller normalises the message first, but this is
    // package-private and reusable, so it must not depend on a caller's invariant.
    String raw = StringUtils.defaultString(rawMessage);
    Matcher m = MESSAGE_KEY.matcher(raw);
    String key = null;
    while (m.find()) {
      key = m.group(1);
    }
    if (key != null) {
      String translated = OBMessageUtils.messageBD(key);
      // messageBD echoes the key back when the message is not in the dictionary; that is worse than
      // useless in a toast, so fall through to the full translation in that case.
      if (StringUtils.isNotBlank(translated) && !StringUtils.equals(translated, key)) {
        return translated;
      }
    }
    return OBMessageUtils.translateError(raw).getMessage();
  }

  /**
   * The reason recorded for the first id in {@code failedIds} that has one, or {@code null}.
   *
   * <p>Iterates the FAILED ids rather than the reason map so the message always belongs to a
   * transaction the caller actually reported as failed — a helper may have recorded a reason for a
   * transaction that Core then managed to free anyway, and quoting that one would explain a failure
   * that did not happen.
   */
  static String firstFailureReason(List<String> failedIds, Map<String, String> failureReasons) {
    for (String id : failedIds) {
      String reason = failureReasons.get(id);
      if (StringUtils.isNotBlank(reason)) {
        return reason;
      }
    }
    return null;
  }

  /** True when {@code selForRec} contains every transaction currently in the reconciliation. */
  static boolean coversReconciliation(FIN_Reconciliation r,
      List<FIN_FinaccTransaction> selForRec) {
    Set<String> selIds = new HashSet<>();
    for (FIN_FinaccTransaction t : selForRec) {
      selIds.add(t.getId());
    }
    return r.getFINFinaccTransactionList().stream().allMatch(t -> selIds.contains(t.getId()));
  }

  /**
   * Undoes the whole reconciliation (payment removal). Logs and swallows a failure instead of
   * aborting the caller's per-reconciliation loop — see {@link #removeSelectedFromReconciliations}
   * for why: the caller re-checks the real outcome afterward rather than relying on this throwing.
   */
  private static void undoWholeReconciliation(ReconciliationHandler handler,
      FIN_FinancialAccount account, FIN_Reconciliation r,
      List<FIN_FinaccTransaction> selForRec, Map<String, String> failureReasons) {
    try {
      handler.undoReconciliation(account, r, new ArrayList<>(r.getFINFinaccTransactionList()));
    } catch (Exception e) {
      log.error("Failed to undo reconciliation {}; some of its transactions may remain "
          + "reconciled — the caller reports the actual per-transaction outcome.", r.getId(), e);
      // The undo is a single Core call for the whole document, so its failure applies to every
      // transaction the caller asked about in this reconciliation.
      recordFailure(selForRec, failureReasons, e);
    }
  }

  /**
   * Detaches just the selected transactions (the rest of the reconciliation stays): removes each
   * from its reconciliation and, for an auto-created payment, reverses it.
   *
   * <p>Each removal reactivates and reprocesses the whole reconciliation ({@code
   * FIN_ReconciliationProcess}), which mutates the Hibernate session; a transaction instance loaded
   * up front therefore goes stale/detached after the first iteration and a later {@code save} on it
   * collides with the freshly-loaded persistent copy ({@code NonUniqueObjectException} — only the
   * first of N un-reconciled). So we snapshot the ids and re-fetch each transaction fresh right
   * before detaching it, never carrying an instance across an iteration.
   *
   * <p>A failure on one id is logged and swallowed rather than propagated, so the remaining ids in
   * {@code selForRec} still get attempted — see {@link #removeSelectedFromReconciliations} for why.
   */
  static void detachSelected(ReconciliationHandler handler,
      List<FIN_FinaccTransaction> selForRec, Map<String, String> failureReasons) {
    List<String> ids = new ArrayList<>();
    for (FIN_FinaccTransaction t : selForRec) {
      ids.add(t.getId());
    }
    for (String id : ids) {
      try {
        // tenant-ok: id already cross-checked against the account by groupSelectedByReconciliation
        FIN_FinaccTransaction trx = OBDal.getInstance().get(FIN_FinaccTransaction.class, id);
        // No unposting here: detaching reactivates the whole reconciliation and so meets the same
        // date-narrowed reset, but running it mid-loop would detach the instance just loaded above.
        // Every caller unposts beforehand instead — see removeSelectedFromReconciliations and
        // ReconciliationHandler.reactivate.
        boolean auto = handler.isAutoCreated(trx);
        FIN_Payment payment = auto ? trx.getFinPayment() : null;
        ReconciliationRemovalUtil.removeTransactionFromReconciliation(trx);
        if (auto && payment != null) {
          PaymentRemovalUtil.reactivateAndRemove(payment);
        } else if (auto) {
          TransactionRemovalUtil.reactivateAndRemove(id);
        }
      } catch (Exception e) {
        log.error("Failed to detach transaction {} from its reconciliation; earlier detaches in "
            + "this batch are not rolled back (Core commits mid-flow) — continuing with the rest.",
            id, e);
        recordFailure(Collections.singletonList(
            // tenant-ok: diagnostics only, on an id already validated in this same loop
            OBDal.getInstance().get(FIN_FinaccTransaction.class, id)), failureReasons, e);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // applySuggestions helpers ("T1" batch-header refactor)
  // ---------------------------------------------------------------------------

  /**
   * Matches every prepared group into ONE shared reconciliation and processes it once. Extracted
   * from {@code ReconciliationHandler.applySuggestions} to keep that method's cognitive complexity
   * under the Sonar limit (java:S3776) — every seam below still runs on the SAME {@code handler}
   * instance the caller passes in, so its behavior (and what its unit tests observe/verify) is
   * unchanged.
   *
   * <p>Not atomic across groups: Core's matching services commit mid-flow, so a failure matching
   * group <em>k</em> does not roll back groups {@code 1..k-1} already matched into the same
   * document — it is captured as an error entry in {@code results} and the rest of the batch still
   * proceeds.
   *
   * @param successfulGroups single-element output array; entry 0 is incremented once per group that
   *     matched successfully (the caller needs the final count for its telemetry emit)
   * @return the verbatim error response when the final {@code processReconciliation} call fails
   *     (the batch is aborted at that point), or {@code null} on success
   */
  static NeoResponse matchAndProcessBatch(ReconciliationHandler handler,
      FIN_FinancialAccount account, List<ReconciliationHandler.PreparedGroup> prepared,
      JSONArray results, int[] successfulGroups) throws Exception {
    FIN_Reconciliation rec = handler.getOrCreateDraftReconciliation(account);
    for (ReconciliationHandler.PreparedGroup p : prepared) {
      try {
        handler.matchInto(p.line, p.operationIds, rec);
        successfulGroups[0]++;
        JSONObject ok = new JSONObject();
        ok.put("reconciliationId", rec.getId());
        ok.put(ReconciliationHandler.KEY_STATEMENT_LINE_ID, p.line.getId());
        results.put(ok);
      } catch (Exception e) {
        log.error("Failed to match statement line {} into batch reconciliation {}",
            p.line.getId(), rec.getId(), e);
        results.put(NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Could not match statement line " + p.line.getId() + ": " + e.getMessage()).getBody());
      }
    }
    OBError result = handler.processReconciliation(rec);
    if (result != null && "Error".equalsIgnoreCase(result.getType())) {
      handler.doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, result.getMessage());
    }
    return null;
  }

  /**
   * True when {@code candidateLine}'s own transaction belongs to {@code rec}; when so, it is added
   * to {@code out} (de-duplicated via {@code seenIds}). Extracted from {@code
   * ReconciliationHandler.transactionsOfLineIn}, which calls this once for the line itself and once
   * per ETGO match-group sibling.
   */
  static void addTransactionOwnedByRec(FIN_BankStatementLine candidateLine,
      FIN_Reconciliation rec, List<FIN_FinaccTransaction> out, Set<String> seenIds) {
    FIN_FinaccTransaction t = candidateLine.getFinancialAccountTransaction();
    if (t != null && t.getReconciliation() != null
        && rec.getId().equals(t.getReconciliation().getId()) && seenIds.add(t.getId())) {
      out.add(t);
    }
  }
}
