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
import java.util.HashSet;
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
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
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
   * Derives per-line reconciled amounts / progress and the fine-grained {@code state}, accumulates
   * the running total and the per-state counts, and builds the {@code pendingLines} data envelope
   * body. Extracted verbatim from {@code ReconciliationHandler.buildPendingLines}.
   */
  static JSONObject summarizePendingLines(JSONArray lines, FIN_FinancialAccount account,
      List<MatchRuleEngine.Rule> rules, int pendingDateTolDays, BigDecimal pendingAmtTolPct)
      throws JSONException {
    BigDecimal total = BigDecimal.ZERO;
    Map<String, Integer> counts = AutoMatchSupport.newCounts();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject row = lines.getJSONObject(i);
      BigDecimal amount =
          nullSafe(new BigDecimal(row.optString(ReconciliationHandler.KEY_AMOUNT, "0")));
      total = total.add(amount);
      // Reconciled/pending amounts + progress % for the left "Progreso" bar and the right block.
      BigDecimal pending = nullSafe(new BigDecimal(row.optString("pendingAmount", "0")));
      BigDecimal reconciled = amount.subtract(pending);
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
            pendingAmtTolPct);
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
      Map<String, List<FIN_FinaccTransaction>> selectedByRec) {
    for (String recId : recById.keySet()) {
      FIN_Reconciliation r = OBDal.getInstance().get(FIN_Reconciliation.class, recId);
      List<FIN_FinaccTransaction> selForRec = selectedByRec.get(recId);
      if (coversReconciliation(r, selForRec)) {
        undoWholeReconciliation(handler, account, r);
      } else {
        detachSelected(handler, selForRec);
      }
    }
  }

  /** True when {@code selForRec} contains every transaction currently in the reconciliation. */
  private static boolean coversReconciliation(FIN_Reconciliation r,
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
      FIN_FinancialAccount account, FIN_Reconciliation r) {
    try {
      handler.undoReconciliation(account, r, new ArrayList<>(r.getFINFinaccTransactionList()));
    } catch (Exception e) {
      log.error("Failed to undo reconciliation {}; some of its transactions may remain "
          + "reconciled — the caller reports the actual per-transaction outcome.", r.getId(), e);
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
  private static void detachSelected(ReconciliationHandler handler,
      List<FIN_FinaccTransaction> selForRec) {
    List<String> ids = new ArrayList<>();
    for (FIN_FinaccTransaction t : selForRec) {
      ids.add(t.getId());
    }
    for (String id : ids) {
      try {
        FIN_FinaccTransaction trx = OBDal.getInstance().get(FIN_FinaccTransaction.class, id);
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
      }
    }
  }

  // ---------------------------------------------------------------------------
  // reactivateSelected helpers ("Reactivar" — the lightweight un-reconcile)
  // ---------------------------------------------------------------------------

  /**
   * Per reconciliation: the lightweight un-reconcile. Where {@link
   * #removeSelectedFromReconciliations} always ends up DELETING the {@code FIN_Reconciliation}, this
   * returns it to DRAFT and keeps it — Core's plain {@code reactivate} (action {@code "R"}) only sets
   * {@code processed = false} / {@code DR} and touches nothing else, so the statement line keeps its
   * transaction and the transaction keeps its reconciliation. Nothing has to be un-linked or
   * remembered: the line simply reads as pending (its reconciliation is unprocessed) with its own
   * transactions pre-selected, and confirming re-processes that same document.
   *
   * <p>Auto-created movements in the checked set are still fully deleted first (same {@code
   * com.etendoerp.payment.removal} utilities as {@code removeOperation}) — a payment that only existed
   * to back this reconciliation has nothing worth preserving in a draft. When the WHOLE selection is
   * auto-created there is nothing left to keep either, so it falls back to the delete behavior.
   *
   * <p>Same non-aborting resilience as {@link #removeSelectedFromReconciliations}: Core commits
   * mid-flow, so one unit's failure is logged and the batch continues; the caller re-checks the real
   * post-state per transaction.
   */
  static int reactivateSelectedFromReconciliations(ReconciliationHandler handler,
      FIN_FinancialAccount account, Map<String, FIN_Reconciliation> recById,
      Map<String, List<FIN_FinaccTransaction>> selectedByRec) {
    int autoConfirmed = 0;
    for (String recId : recById.keySet()) {
      List<FIN_FinaccTransaction> selForRec = selectedByRec.get(recId);
      List<FIN_FinaccTransaction> autoCreated = new ArrayList<>();
      boolean anyKept = false;
      for (FIN_FinaccTransaction t : selForRec) {
        if (handler.isAutoCreated(t)) {
          autoCreated.add(t);
        } else {
          anyKept = true;
        }
      }
      // Nothing pre-existing to preserve as a draft → same end state as "Desconciliar".
      if (!anyKept) {
        FIN_Reconciliation fresh = OBDal.getInstance().get(FIN_Reconciliation.class, recId);
        if (coversReconciliation(fresh, selForRec)) {
          undoWholeReconciliation(handler, account, fresh);
        } else {
          detachSelected(handler, selForRec);
        }
        continue;
      }
      detachSelected(handler, autoCreated);
      autoConfirmed += reactivateToDraft(account, recId);
    }
    return autoConfirmed;
  }

  /**
   * Core's plain reactivate — reactivate WITHOUT the delete that {@code
   * reactivateAndRemoveReconciliation} chains onto it. Leaves the {@code FIN_Reconciliation} row in
   * place, un-processed, with its transactions and their statement lines still linked.
   *
   * <p>Core only lets ONE reconciliation be editable per account: its reactivate action rejects with
   * "Draft Reconciliation already exists…" when the account already has an unprocessed one. So any
   * pre-existing draft is processed first — the same ordering pre-step {@code undoReconciliation}
   * already performs for exactly this reason, and what the {@code payment.removal} module's own
   * Classic "Reactivate Reconciliation" button does too.
   *
   * @return how many pre-existing drafts had to be confirmed to make room. Non-zero means a line the
   *     user had left pending by an EARLIER "Reactivar" on this account is now reconciled again — an
   *     unavoidable consequence of Core's one-editable-reconciliation rule, which the caller surfaces
   *     in the response so the UI can warn about it instead of letting it happen silently.
   */
  private static int reactivateToDraft(FIN_FinancialAccount account, String recId) {
    try {
      List<FIN_Reconciliation> drafts = ReconciliationRemovalUtil.getDraftReconciliation(account);
      int confirmed = drafts != null ? drafts.size() : 0;
      ReconciliationRemovalUtil.processAllReconciliationInDraft(drafts);
      FIN_Reconciliation fresh = OBDal.getInstance().get(FIN_Reconciliation.class, recId);
      if (fresh != null) {
        ReconciliationRemovalUtil.reactivate(fresh);
      }
      return confirmed;
    } catch (Exception e) {
      log.error("Failed to reactivate reconciliation {} to draft; it stays processed, so its lines "
          + "still read as reconciled — the caller reports it as failed.", recId, e);
      return 0;
    }
  }
}
