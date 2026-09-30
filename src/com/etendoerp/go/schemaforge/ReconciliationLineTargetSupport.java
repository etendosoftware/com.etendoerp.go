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

import static com.etendoerp.go.schemaforge.ReconciliationSupport.belongsToAccount;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

/**
 * ETP-5472 — decides WHICH statement line a reconcile request actually operates on, and reports
 * what a successful match left behind.
 *
 * <p>Extracted out of {@link ReconciliationHandler} and {@link ReconciliationHandlerSupport} (both
 * at the Sonar java:S1448 method-count ceiling) rather than added there, the same arrangement as
 * {@link ReconciliationDifferenceSupport} and {@link ReconciliationDraftGuard}. Every DAL seam
 * ({@code loadLine}, {@code loadMatchGroupLines}, {@code normalizeReactivatedMatchGroup},
 * {@code loadTransaction}) is called back through the passed handler, so unit-test spies keep
 * intercepting them.
 *
 * <p>Three states a requested line can be in, besides plain pending:
 * <ul>
 *   <li><b>Reconciled head of a partial group.</b> Its transaction belongs to a PROCESSED
 *       reconciliation and a sibling sharing its {@code EM_ETGO_Match_Group_ID} is still unmatched
 *       (the remainder Core's split cloned off). {@code pendingLines} lists the logical line by its
 *       head id, so a caller — an agent in particular — naturally sends the head back. The request
 *       is redirected to the remainder instead of answering a 409 that nothing in the listing lets
 *       the caller act on.</li>
 *   <li><b>Fully reconciled.</b> Same as above with no remainder: the 409 stays, built by
 *       {@link ReconciliationDifferenceSupport#alreadyReconciled(String, String)}.</li>
 *   <li><b>Stuck.</b> Linked to a transaction that has NO reconciliation at all.
 *       {@code PENDING_LINES_SQL} lists such a line as pending, yet every write path used to refuse
 *       it — reconcileGroup as "already reconciled", undoReconciliation as "not linked to a
 *       reconciliation" — so it could never leave that state. It is healed: the line is detached
 *       and returns to the pending pool. The transaction (and the payment behind it) is KEPT: it is
 *       a real movement, and it becomes an ordinary candidate again (a transaction left in
 *       {@code RPPC} is put back to "not cleared", which the candidates query requires).</li>
 *   <li><b>Held by a draft.</b> Linked to a transaction that sits in an unprocessed (draft)
 *       reconciliation — whichever flow created it, Classic's Match Statement or ours. That draft is
 *       unconfirmed work, and the same policy as {@link ReconciliationDraftGuard} applies: it is
 *       never emptied, removed or discarded without a human decision. The request is refused with
 *       409 {@link #draftHoldsLineMessage(String)} BEFORE anything is written, and the line stays
 *       as it is until somebody reviews that draft.</li>
 * </ul>
 */
final class ReconciliationLineTargetSupport {

  private static final Logger log = LogManager.getLogger(ReconciliationLineTargetSupport.class);

  static final String KEY_PARTIAL = "partial";
  static final String KEY_PENDING_AMOUNT = "pendingAmount";
  static final String KEY_HEALED = "healed";

  /** "Payment cleared": the status the candidates query excludes (ReconciliationHandler). */
  private static final String STATUS_CLEARED = "RPPC";

  /**
   * Refusal text for a line whose transaction sits in an unconfirmed draft reconciliation. Split
   * around the draft's document number because it is a wire contract: the SPA translates it with a
   * parameterized matcher ({@code matchDraftHoldsLine} in schema_forge
   * {@code tools/app-shell/src/lib/backendErrors.js}) — keep both halves in sync with it.
   * Deliberately distinct from {@link ReconciliationDraftGuard#MSG_FOREIGN_DRAFT_PREFIX}, whose
   * wording is about undoing a reconciliation.
   */
  static final String MSG_DRAFT_HOLDS_LINE_PREFIX = "Reconciliation ";
  static final String MSG_DRAFT_HOLDS_LINE_SUFFIX =
      " is an unconfirmed draft that already holds this line."
          + " Review it before reconciling the line again.";

  private ReconciliationLineTargetSupport() {
  }

  /**
   * Either the line a write must operate on, or the error to return verbatim. Exactly one of the
   * two is non-null.
   */
  record Target(FIN_BankStatementLine line, NeoResponse error) {

    static Target of(FIN_BankStatementLine line) {
      return new Target(line, null);
    }

    static Target failed(NeoResponse error) {
      return new Target(null, error);
    }
  }

  /**
   * Resolves the line {@code reconcileGroup} / {@code applySuggestions} must match against.
   *
   * <p>A pending line is returned unchanged; the reconciled head of a partial group is redirected
   * to its pending remainder; a fully reconciled line gets the 409; a line held by a draft
   * reconciliation gets the 409 {@link #draftHoldsLineMessage(String)}; a stuck line (transaction
   * with no reconciliation) is healed (see the class javadoc) and then goes through
   * {@code normalizeReactivatedMatchGroup}, so a split group whose sub-lines are all free again
   * collapses back into one line before the match.
   *
   * <p>The heal is the only write here, and every guard that can refuse it runs first: the draft
   * statement check (a draft statement is not reconcilable, so there is no point freeing a line of
   * it) and the draft-reconciliation check. A later refusal of the same request is rolled back by
   * {@code ReconciliationHandlerSupport.runPostAction}, so a heal only persists together with the
   * match it enabled. The one exception is an {@code applySuggestions} group rejected after its
   * heal: that batch does not roll back per group, so the line stays freed — which is the state
   * {@code pendingLines} was already reporting for it.
   *
   * @param handler         the owning handler, for every DAL seam
   * @param line            the requested line, already tenant-resolved and checked against the
   *                        account
   * @param conflictMessage message of the 409 for a fully reconciled line (the two callers word it
   *                        differently and the frontend matches both by text)
   * @return the line to operate on, or the refusal
   */
  static Target resolveForMatch(ReconciliationHandler handler, FIN_BankStatementLine line,
      String conflictMessage) throws Exception {
    FIN_FinaccTransaction trx = line.getFinancialAccountTransaction();
    if (trx == null) {
      return Target.of(line);
    }
    if (isProcessed(trx.getReconciliation())) {
      String remainderId = pendingRemainderId(handler, line);
      FIN_BankStatementLine remainder =
          remainderId != null ? handler.loadLine(remainderId) : null;
      if (remainder == null) {
        return Target.failed(
            ReconciliationDifferenceSupport.alreadyReconciled(conflictMessage, remainderId));
      }
      log.info("Statement line {} is the reconciled head of a partial group; operating on its "
          + "pending remainder {} instead.", line.getId(), remainderId);
      return Target.of(remainder);
    }
    if (ReconciliationSupport.isOnDraftStatement(line)) {
      return Target.failed(NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          ReconciliationHandler.MSG_LINE_ON_DRAFT_STATEMENT));
    }
    NeoResponse healError = healStuckLine(handler, line);
    if (healError != null) {
      return Target.failed(healError);
    }
    return Target.of(handler.normalizeReactivatedMatchGroup(line));
  }

  /**
   * The line id the read-only {@code candidates} listing must be built for: the pending remainder
   * when {@code lineId} is the reconciled head of a partial group, otherwise {@code lineId} itself.
   *
   * <p>Never heals: {@code candidates} is a GET and must not write. A stuck or draft-held line keeps
   * its current listing; {@code reconcileGroup} then heals the former and refuses the latter.
   */
  static String candidateLineId(ReconciliationHandler handler, String accountId, String lineId) {
    if (StringUtils.isBlank(lineId)) {
      return lineId;
    }
    FIN_BankStatementLine line = handler.loadLine(lineId);
    if (line == null || !belongsToAccount(line, accountId)
        || line.getFinancialAccountTransaction() == null
        || !isProcessed(line.getFinancialAccountTransaction().getReconciliation())) {
      return lineId;
    }
    String remainderId = pendingRemainderId(handler, line);
    return remainderId != null ? remainderId : lineId;
  }

  /**
   * Tells the caller of {@code candidates} that the listing was built for another line, by adding
   * {@code remainderLineId} to {@code response.data}. A no-op when nothing was redirected or the
   * response carries no data envelope (an error).
   */
  static NeoResponse withRedirect(NeoResponse listed, String requestedLineId,
      String effectiveLineId) {
    if (listed == null || StringUtils.equals(requestedLineId, effectiveLineId)) {
      return listed;
    }
    try {
      JSONObject data = listed.getBody() != null
          ? listed.getBody().optJSONObject("response") : null;
      data = data != null ? data.optJSONObject("data") : null;
      if (data != null) {
        data.put(ReconciliationDifferenceSupport.KEY_REMAINDER_LINE_ID, effectiveLineId);
      }
    } catch (JSONException e) {
      log.debug("Could not flag the candidates redirect to line {}", effectiveLineId, e);
    }
    return listed;
  }

  /**
   * Frees a stuck line — linked to a transaction with no reconciliation at all — so it returns to
   * the pending pool.
   *
   * <p>A line whose transaction sits in ANY reconciliation that is not processed (a draft) is NOT
   * freed, whether that draft holds only this line's group or other movements too: a draft is
   * unconfirmed work, and — the same policy as {@link ReconciliationDraftGuard} — it is never
   * emptied or removed without a human decision. That case is refused with the 409
   * {@link #draftHoldsLineMessage(String)}, BEFORE anything is written.
   *
   * <p>Every match-group sibling ({@code EM_ETGO_Match_Group_ID}) whose transaction has no
   * reconciliation either is freed together, so the group can collapse back into one line; a
   * sibling in a draft or a processed reconciliation is left alone. The transactions are kept (see
   * {@link #detach}).
   *
   * @return {@code null} when the line was freed (or had no transaction), or the 409 refusing a
   *         draft-held line
   */
  static NeoResponse healStuckLine(ReconciliationHandler handler, FIN_BankStatementLine line) {
    FIN_FinaccTransaction trx = line.getFinancialAccountTransaction();
    if (trx == null) {
      return null;
    }
    FIN_Reconciliation draft = trx.getReconciliation();
    if (draft != null) {
      log.warn("Not freeing statement line {}: its transaction {} sits in the unconfirmed draft "
          + "reconciliation {} ({}).", line.getId(), trx.getId(), draft.getId(),
          draft.getDocumentNo());
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          draftHoldsLineMessage(draft.getDocumentNo()));
    }
    // ===== every guard above is read-only. The first write is the next statement. =====
    for (FIN_BankStatementLine stuck : groupLines(handler, line)) {
      FIN_FinaccTransaction t = stuck.getFinancialAccountTransaction();
      if (t != null && t.getReconciliation() == null) {
        detach(stuck, t);
      }
    }
    OBDal.getInstance().flush();
    return null;
  }

  /**
   * The 409 text for a line held by a draft reconciliation, as {@link #MSG_DRAFT_HOLDS_LINE_PREFIX}
   * + {@code documentNo} + {@link #MSG_DRAFT_HOLDS_LINE_SUFFIX}.
   *
   * @param documentNo the draft's document number
   */
  static String draftHoldsLineMessage(String documentNo) {
    return MSG_DRAFT_HOLDS_LINE_PREFIX + documentNo + MSG_DRAFT_HOLDS_LINE_SUFFIX;
  }

  /**
   * The {@code undoReconciliation} answer for a line whose transaction has no reconciliation: there
   * is nothing to undo, so the line is just freed and the call succeeds — the caller's intent, "put
   * this line back to pending", is what happens. The movement is kept (see the class javadoc).
   */
  static NeoResponse reactivateStuckLine(ReconciliationHandler handler,
      FIN_FinancialAccount account, FIN_BankStatementLine line) throws Exception {
    String statementLineId = line.getId();
    NeoResponse healError = healStuckLine(handler, line);
    if (healError != null) {
      return healError;
    }
    handler.normalizeReactivatedMatchGroup(line);
    JSONObject data = new JSONObject();
    data.put("reactivated", true);
    data.put(KEY_HEALED, true);
    data.put(ReconciliationHandler.KEY_STATEMENT_LINE_ID, statementLineId);
    data.put(ReconciliationHandler.KEY_UPDATED_BALANCE, ReactivationSupport.currentBalance(account));
    return ReconciliationSupport.envelope(data);
  }

  /**
   * Adds {@code partial}, {@code pendingAmount} and — when partial — {@code remainderLineId} to a
   * successful match's {@code data}. A 201 used to look identical whether the line was closed or
   * only partly covered, and an agent read the partial one as done.
   *
   * <p>Read from Core's real state AFTER processing, never predicted from the request: the line is
   * partial only when a pending remainder row actually exists in its match group, and
   * {@code pendingAmount} is that row's own signed {@code cramount - dramount}. A prediction
   * (line minus operations) was wrong whenever the operations overshot the line within the
   * tolerance {@code validateOperations} accepts: it reported {@code partial:true} with a tiny
   * negative amount and no remainder to continue with.
   *
   * @param lineId the matched line; re-read here because processing the reconciliation churns the
   *               session, so the caller's instance may be stale. The original row keeps its id and
   *               becomes the matched portion; the remainder is the clone Core's split created,
   *               carrying the same match-group id.
   */
  static void putMatchOutcome(ReconciliationHandler handler, JSONObject data, String lineId)
      throws JSONException {
    FIN_BankStatementLine remainder = pendingRemainder(handler, handler.loadLine(lineId));
    boolean partial = remainder != null;
    data.put(KEY_PARTIAL, partial);
    data.put(KEY_PENDING_AMOUNT, partial
        ? ReconciliationDifferenceSupport.signedLineAmount(remainder) : BigDecimal.ZERO);
    if (partial) {
      data.put(ReconciliationDifferenceSupport.KEY_REMAINDER_LINE_ID, remainder.getId());
    }
  }

  /**
   * Id of the first active, unmatched row of {@code line}'s match group (the pending remainder), or
   * {@code null} when the line carries no group or the whole group is matched. Same rule as
   * {@code BankStatementsSupport.mergeMatchGroups} and {@code reconcileDifference}.
   */
  static String pendingRemainderId(ReconciliationHandler handler, FIN_BankStatementLine line) {
    FIN_BankStatementLine remainder = pendingRemainder(handler, line);
    return remainder != null ? remainder.getId() : null;
  }

  /** The row {@link #pendingRemainderId} names, or {@code null}. One group load. */
  private static FIN_BankStatementLine pendingRemainder(ReconciliationHandler handler,
      FIN_BankStatementLine line) {
    String groupId = line != null ? ReactivationSupport.readMatchGroupId(line) : null;
    if (groupId == null || line.getBankStatement() == null) {
      return null;
    }
    List<FIN_BankStatementLine> siblings =
        handler.loadMatchGroupLines(line.getBankStatement(), groupId);
    String remainderId =
        ReconciliationDifferenceSupport.summarizeGroup(siblings, null).remainderLineId();
    if (remainderId == null) {
      return null;
    }
    for (FIN_BankStatementLine sibling : siblings) {
      if (sibling != null && remainderId.equals(sibling.getId())) {
        return sibling;
      }
    }
    return null;
  }

  /** {@code line} plus its match-group siblings (the siblings list already includes it). */
  private static List<FIN_BankStatementLine> groupLines(ReconciliationHandler handler,
      FIN_BankStatementLine line) {
    List<FIN_BankStatementLine> group = new ArrayList<>();
    group.add(line);
    String groupId = ReactivationSupport.readMatchGroupId(line);
    if (groupId != null && line.getBankStatement() != null) {
      for (FIN_BankStatementLine sibling
          : handler.loadMatchGroupLines(line.getBankStatement(), groupId)) {
        if (sibling != null && !line.getId().equals(sibling.getId())) {
          group.add(sibling);
        }
      }
    }
    return group;
  }

  /**
   * Unlinks {@code line} from {@code t} (a transaction with no reconciliation) and resets its match
   * fields, as Core's {@code APRM_MatchingUtility.unmatch} does — without that method's own split
   * merge, which would fight {@code normalizeReactivatedMatchGroup}.
   *
   * <p>The transaction is kept. When it was left in {@code RPPC} ("cleared") it is put back to
   * "not cleared" by direction via {@link ReactivationSupport#restoreNotClearedStatus}: the
   * candidates query excludes {@code RPPC}, so otherwise the freed movement could never be matched
   * again.
   */
  private static void detach(FIN_BankStatementLine line, FIN_FinaccTransaction t) {
    log.info("Auto-healing statement line {}: detaching it from transaction {} (no "
        + "reconciliation); the transaction is kept.", line.getId(), t.getId());
    line.setFinancialAccountTransaction(null);
    line.setMatchingtype(null);
    line.setMatchedDocument(null);
    OBDal.getInstance().save(line);
    if (STATUS_CLEARED.equals(t.getStatus())) {
      ReactivationSupport.restoreNotClearedStatus(t);
    }
  }

  private static boolean isProcessed(FIN_Reconciliation rec) {
    return rec != null && Boolean.TRUE.equals(rec.isProcessed());
  }
}
