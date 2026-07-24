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

  /** The body-consuming business method behind a POST action route. */
  @FunctionalInterface
  private interface BodyAction {
    NeoResponse apply(JSONObject body) throws Exception;
  }

  // ---------------------------------------------------------------------------
  // POST action dispatch wrappers (OBContext admin mode + rollback boilerplate)
  // ---------------------------------------------------------------------------

  static NeoResponse handleReconcileGroup(ReconciliationHandler handler, NeoContext context) {
    return runPostAction(handler, context, "reconcileGroup", handler::reconcileGroup);
  }

  static NeoResponse handleApplySuggestions(ReconciliationHandler handler, NeoContext context) {
    return runPostAction(handler, context, "applySuggestions", handler::applySuggestions);
  }

  static NeoResponse handleReactivate(ReconciliationHandler handler, NeoContext context) {
    return runPostAction(handler, context, "reactivate", handler::reactivate);
  }

  static NeoResponse handleRemoveOperation(ReconciliationHandler handler, NeoContext context) {
    return runPostAction(handler, context, "removeOperation", handler::removeOperation);
  }

  /**
   * Shared dispatch envelope for the mutating POST actions: rejects an empty body, runs the action
   * in admin mode, maps a business {@link OBException} to 400 (+rollback) and any other failure to
   * 500 (+rollback), and always restores the previous OBContext mode. Identical to the per-action
   * wrappers this replaced.
   */
  private static NeoResponse runPostAction(ReconciliationHandler handler, NeoContext context,
      String action, BodyAction bodyAction) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_BODY_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      return bodyAction.apply(body);
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
   */
  static void removeSelectedFromReconciliations(ReconciliationHandler handler,
      FIN_FinancialAccount account, Map<String, FIN_Reconciliation> recById,
      Map<String, List<FIN_FinaccTransaction>> selectedByRec)
      throws ReconciliationRemovalException {
    for (Map.Entry<String, FIN_Reconciliation> entry : recById.entrySet()) {
      FIN_Reconciliation r = entry.getValue();
      List<FIN_FinaccTransaction> selForRec = selectedByRec.get(entry.getKey());
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
   * Undoes the whole reconciliation (payment removal), preserving the servlet mapping: a business
   * {@link OBException} propagates unwrapped (→ 400), any other failure is wrapped in a dedicated
   * checked exception (→ 500), never a bare generic {@code throws} (Sonar java:S112).
   */
  private static void undoWholeReconciliation(ReconciliationHandler handler,
      FIN_FinancialAccount account, FIN_Reconciliation r) throws ReconciliationRemovalException {
    try {
      handler.undoReconciliation(account, r, new ArrayList<>(r.getFINFinaccTransactionList()));
    } catch (OBException e) {
      throw e;
    } catch (Exception e) {
      throw new ReconciliationRemovalException(e);
    }
  }

  /**
   * Detaches just the selected transactions (the rest of the reconciliation stays): removes each
   * from its reconciliation and, for an auto-created payment, reverses it. Same mapping contract as
   * {@link #undoWholeReconciliation} — {@link OBException} unwrapped (→ 400), everything else
   * wrapped (→ 500).
   */
  private static void detachSelected(ReconciliationHandler handler,
      List<FIN_FinaccTransaction> selForRec) throws ReconciliationRemovalException {
    for (FIN_FinaccTransaction trx : selForRec) {
      boolean auto = handler.isAutoCreated(trx);
      FIN_Payment payment = auto ? trx.getFinPayment() : null;
      try {
        ReconciliationRemovalUtil.removeTransactionFromReconciliation(trx);
        if (auto && payment != null) {
          PaymentRemovalUtil.reactivateAndRemove(payment);
        } else if (auto) {
          TransactionRemovalUtil.reactivateAndRemove(trx.getId());
        }
      } catch (OBException e) {
        throw e;
      } catch (Exception e) {
        throw new ReconciliationRemovalException(e);
      }
    }
  }
}
