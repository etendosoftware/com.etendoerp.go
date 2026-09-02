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

import static com.etendoerp.go.schemaforge.ReconciliationSupport.belongsToAccount;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.nullSafe;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Posts the unreconciled remainder of a PARTIALLY reconciled bank-statement line against an
 * accounting concept (GL item), so the line closes instead of staying pending forever.
 *
 * <p>Extracted out of {@link ReconciliationHandler} (Sonar S1448 — that class sits at 34 methods
 * against a threshold of 35) rather than added there, following the same arrangement as
 * {@link ReconciliationWriteoffSupport} and {@link ReconciliationHandlerSupport}. Every DAL /
 * Classic-layer call routes back through the passed handler so the unit-test spies keep working.
 *
 * <p><b>Why the order of operations is the whole safety mechanism.</b> A returned
 * {@code NeoResponse.error(...)} does NOT roll back — it commits. {@code DalThreadHandler.doFinal}
 * only takes the rollback branch when an exception escapes the filter chain, and
 * {@code ReconciliationHandlerSupport.runPostAction} catches everything and <i>returns</i> a
 * response. So any write performed before a returned 400/409 is persisted for good. Every
 * validation that can fail therefore runs BEFORE the single write in
 * {@link #createDifferenceTransaction}.
 *
 * <p><b>The remainder is its own physical row.</b> A partially reconciled <i>logical</i> line is
 * several {@code FIN_BankStatementLine} rows sharing a match-group id (Core's
 * {@code splitBankStatementLine} clones the leftover onto a new row). The frontend already sends
 * that row's id — {@code remainderLineId} — as {@code statementLineId}. Its own signed
 * {@code cramount - dramount} IS the remainder, which is why this class never reads
 * {@code EM_ETGO_Pending_Amount}: that column is {@code abs()}-valued, so using it would post a
 * deposit for an outflow difference, and it is observer-maintained (absent on older rows).
 *
 * <p><b>Tolerance semantics — deliberate divergence, documented on purpose.</b> The gate reuses the
 * per-account {@code EM_ETGO_Amount_Tolerance} percentage, and reads an unset/zero percentage as
 * "no difference may be posted", i.e. the action is inert until an administrator configures it.
 * Note that {@code AutoMatchSupport.signalGroupTolerance} reads the SAME column with the opposite
 * convention (zero means "one cent of slack, never zero"). Two meanings for one field is a support
 * trap, so the 400 message spells out the configured percentage and the resulting limit.
 */
final class ReconciliationDifferenceSupport {

  private static final Logger log = LogManager.getLogger(ReconciliationDifferenceSupport.class);

  /** Below half a cent there is nothing worth posting — mirrors {@code CashCloseSupport.isBalanced}. */
  private static final BigDecimal NEGLIGIBLE = new BigDecimal("0.005");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private static final String KEY_GL_ITEM_ID = "glItemId";
  private static final String KEY_DESCRIPTION = "description";
  private static final String KEY_OPERATION_IDS = "operationIds";
  private static final String KEY_REMAINDER_LINE_ID = "remainderLineId";

  private static final String MSG_NOT_PARTIAL =
      "This action closes the pending remainder of a partially reconciled statement line. "
          + "This line has nothing reconciled against it yet.";
  private static final String MSG_NOTHING_TO_POST =
      "There is no pending difference on this statement line.";
  private static final String MSG_REACTIVATED =
      "This statement line was reactivated and has more than one pending portion. Re-confirm the "
          + "reconciliation before posting a difference.";
  /** Dictionary key for the auto-created difference movement's description. */
  private static final String MSG_DIFFERENCE_DESCRIPTION = "ETGO_ReconciliationDifference";
  private static final String MSG_LOCK_BUSY =
      "Another reconciliation is already in progress for this statement line. Try again.";

  private ReconciliationDifferenceSupport() {
    // utility class — no instances
  }

  /**
   * The match group of a partially reconciled line, reduced to the four numbers this action needs.
   *
   * @param groupTotal the ORIGINAL (pre-split) logical line amount — the sum over the group, which
   *     is invariant across every split because Core moves amount between the matched row and its
   *     clone. This, never the target row's own amount, is the tolerance denominator.
   * @param remainder the target row's own signed amount
   * @param pendingCount how many rows of the group are still unmatched (1 in the normal case)
   * @param remainderLineId id of the first unmatched row, echoed back on a 409 so the client can
   *     retarget itself
   */
  record GroupSnapshot(BigDecimal groupTotal, BigDecimal remainder, int pendingCount,
      String remainderLineId) {
  }

  /**
   * Creates the GL-item adjustment for the remainder and reconciles the line with it.
   *
   * @param handler the owning handler, used for every DAL / Classic seam
   * @param body {@code {financialAccountId, statementLineId, glItemId?, description?}} —
   *     deliberately NO amount: the remainder is recomputed server-side and a client-sent amount is
   *     never read
   * @return 201 with the reconciliation envelope, or the first failing validation's error
   */
  static NeoResponse reconcileDifference(ReconciliationHandler handler, JSONObject body)
      throws Exception {
    String accountId = body.optString(ReconciliationHandler.KEY_FINANCIAL_ACCOUNT_ID, null);
    String lineId = body.optString(ReconciliationHandler.KEY_STATEMENT_LINE_ID, null);
    if (StringUtils.isBlank(accountId) || StringUtils.isBlank(lineId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "financialAccountId and statementLineId are required");
    }

    // Serialize concurrent postings on this row BEFORE anything else. Core does not reject a
    // re-match: APRM_MatchingUtility silently unmatches the previous one, so two racing requests
    // would leave an orphan processed transaction. A lock is not a data write, so this stays inside
    // the read-only phase.
    if (!lockStatementLine(lineId)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, MSG_LOCK_BUSY);
    }

    Preflight pre = preflight(handler, accountId, lineId, body);
    if (pre.error() != null) {
      return pre.error();
    }

    // ===== every validation above is read-only. The first write is the next statement. =====
    // Same default as the inline path: the modal's description field is optional, so without this
    // the manual action creates the very same nameless movement in the Movements list.
    String trxId = handler.createTransactionForRule(pre.account(), pre.line(), differenceSpec(
        pre.glItemId(), pre.remainder(),
        defaultDifferenceDescription(pre.description(), pre.glItemId())));

    NeoResponse delegated = handler.reconcileGroup(
        reconcileGroupBody(accountId, lineId, trxId));
    if (delegated == null
        || delegated.getHttpStatus() >= HttpServletResponse.SC_BAD_REQUEST) {
      // Unreachable for a synthesized single-operation body (see class javadoc), but a returned
      // error COMMITS, so a guard added to reconcileGroup later would otherwise leak the processed
      // adjustment. Roll back defensively rather than rely on that invariant holding forever.
      rollbackQuietly(handler);
      return delegated != null ? delegated
          : NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              ReconciliationHandler.MSG_INTERNAL_SERVER_ERROR);
    }
    enrichResponse(delegated, trxId, pre.remainder(), pre.glItemId());
    return delegated;
  }

  /** Either an error to return verbatim, or the fully validated inputs of the write. */
  private record Preflight(NeoResponse error, FIN_FinancialAccount account,
      FIN_BankStatementLine line, BigDecimal remainder, String glItemId, String description) {

    static Preflight failed(NeoResponse error) {
      return new Preflight(error, null, null, null, null, null);
    }
  }

  /**
   * Every read-only guard, in order. Split out of {@link #reconcileDifference} to keep both methods
   * under the cognitive-complexity limit (Sonar java:S3776).
   */
  private static Preflight preflight(ReconciliationHandler handler, String accountId,
      String lineId, JSONObject body) {
    FIN_FinancialAccount account = handler.loadAccount(accountId);
    if (account == null) {
      return Preflight.failed(NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_ACCOUNT_NOT_FOUND + accountId));
    }
    FIN_BankStatementLine line = handler.loadLine(lineId);
    if (line == null) {
      return Preflight.failed(NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          ReconciliationHandler.MSG_STATEMENT_LINE_NOT_FOUND + lineId));
    }
    if (!belongsToAccount(line, accountId)) {
      return Preflight.failed(NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_LINE_NOT_IN_ACCOUNT));
    }

    String groupId = ReactivationSupport.readMatchGroupId(line);
    GroupSnapshot snap = groupId == null ? null
        : summarizeGroup(handler.loadMatchGroupLines(line.getBankStatement(), groupId), lineId);
    NeoResponse stateError = checkLineState(line, snap);
    if (stateError != null) {
      return Preflight.failed(stateError);
    }

    NeoResponse toleranceError = checkTolerance(handler, accountId, snap);
    if (toleranceError != null) {
      return Preflight.failed(toleranceError);
    }

    String glItemId = effectiveGlItemId(body.optString(KEY_GL_ITEM_ID, null), account);
    NeoResponse glItemError = checkGlItem(glItemId, body.optString(KEY_GL_ITEM_ID, null));
    if (glItemError != null) {
      return Preflight.failed(glItemError);
    }

    return new Preflight(null, account, line, snap.remainder(), glItemId,
        StringUtils.trimToNull(body.optString(KEY_DESCRIPTION, null)));
  }

  /**
   * The line must be a partially reconciled group with exactly one pending portion and a remainder
   * worth posting. Returns {@code null} when it is.
   */
  private static NeoResponse checkLineState(FIN_BankStatementLine line, GroupSnapshot snap) {
    if (line.getFinancialAccountTransaction() != null) {
      // The caller named a row that is already matched — most likely the merged group HEAD instead
      // of the remainder. Echo the remainder id so the client can retarget; never silently
      // retarget here, because a POST naming row X must not mutate row Y.
      return alreadyReconciled(snap);
    }
    if (snap == null || snap.pendingCount() == 0) {
      // pendingCount == 0 on a tagged line means loadMatchGroupLines came back empty — the match
      // group is unidentifiable (typically the EM_ETGO_Match_Group_ID property is not in the model).
      // Refusing as "not partial" is the honest answer: claiming it was reactivated with several
      // pending portions would describe the opposite of what happened.
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_NOT_PARTIAL);
    }
    if (snap.pendingCount() > 1) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, MSG_REACTIVATED);
    }
    // groupTotal - remainder is what has already been reconciled. Zero means nothing was matched
    // yet, so there is no "difference" to speak of — and the tolerance denominator would collapse
    // onto the numerator, which for a percentage >= 100 would authorise posting an entire line.
    if (isNegligible(snap.groupTotal().subtract(snap.remainder()))) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_NOT_PARTIAL);
    }
    if (isNegligible(snap.remainder())) {
      // Without this guard a zero amount would reach createTransactionForRule, which treats a zero
      // spec amount as "not supplied" and substitutes the WHOLE line amount.
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_NOTHING_TO_POST);
    }
    return null;
  }

  private static NeoResponse alreadyReconciled(GroupSnapshot snap) {
    try {
      JSONObject error = new JSONObject();
      error.put("message", ReconciliationHandler.MSG_LINE_ALREADY_RECONCILED);
      error.put(ReconciliationHandler.KEY_STATUS, HttpServletResponse.SC_CONFLICT);
      JSONObject payload = new JSONObject();
      payload.put("error", error);
      if (snap != null && StringUtils.isNotBlank(snap.remainderLineId())) {
        payload.put(KEY_REMAINDER_LINE_ID, snap.remainderLineId());
      }
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, payload);
    } catch (Exception e) {
      log.debug("Could not build the already-reconciled payload", e);
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          ReconciliationHandler.MSG_LINE_ALREADY_RECONCILED);
    }
  }

  /** Server-side tolerance enforcement — the UI gate is a convenience, not the boundary. */
  private static NeoResponse checkTolerance(ReconciliationHandler handler, String accountId,
      GroupSnapshot snap) {
    BigDecimal pct = handler.loadTolerances(accountId)[1];
    BigDecimal limit = differenceLimit(snap.groupTotal(), pct);
    if (withinTolerance(snap.remainder(), limit)) {
      return null;
    }
    return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        "The pending difference (" + snap.remainder().abs().toPlainString()
            + ") exceeds the tolerance configured for this financial account ("
            + nullSafe(pct).toPlainString() + "% of " + snap.groupTotal().abs().toPlainString()
            + " = " + limit.toPlainString() + "). Raise the amount tolerance in Edit account, or "
            + "reconcile the remainder against a transaction.");
  }

  private static NeoResponse checkGlItem(String effectiveGlItemId, String requestedGlItemId) {
    if (StringUtils.isBlank(effectiveGlItemId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "There is a pending difference on this statement line and no accounting concept was "
              + "given for it. Choose one, or configure a GL Item Difference in Edit account.");
    }
    // Only a client-supplied id needs an existence check: the account-derived one is a live
    // reference that cannot dangle.
    if (StringUtils.isNotBlank(requestedGlItemId)
        && OBDal.getInstance().get(GLItem.class, requestedGlItemId) == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "GL item not found: " + requestedGlItemId);
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // ETP-4965 — inline difference posting, shared by the manual and automatch paths
  // ---------------------------------------------------------------------------

  /** Error code the frontend keys on to open its accounting-concept picker and retry. */
  static final String CODE_GL_ITEM_REQUIRED = "GL_ITEM_REQUIRED";

  private static final String MSG_GL_ITEM_REQUIRED =
      "This match leaves a difference and the financial account has no GL Item Difference "
          + "configured. Choose an accounting concept, or configure one in Edit account.";

  /**
   * Closes a within-tolerance 1:1 difference AT THE MOMENT OF RECONCILING, by creating the
   * compensating GL-item movement and adding it to {@code operationIds} so the operations sum
   * EXACTLY to the line.
   *
   * <p><b>Why a compensating movement rather than letting the remainder survive.</b> Core splits the
   * line either way ({@code APRM_MatchingUtility} chains the operations and clones the leftover),
   * but with the gap funded both halves end up matched: the group's pending amount reaches zero,
   * {@code BankStatementsSupport.mergeSubLineIntoHead} reports RECONCILED, and the user sees one
   * closed line. Without it the leftover stays as a pending physical row that nothing can settle —
   * the "stuck on Pendiente" bug of ETP-4965.
   *
   * <p><b>Order of operations is the safety mechanism.</b> A returned {@code NeoResponse.error} does
   * NOT roll back — it commits (see this class's header javadoc). Every branch that can fail is
   * therefore evaluated before {@link ReconciliationHandler#createTransactionForRule}, the single
   * write. The one case where a write already happened upstream is the invoice path, whose
   * {@code payInvoices} runs before this helper is reached; there the rejection rolls back
   * explicitly rather than leaving orphan payments committed.
   *
   * @param operationIds mutated in place on success — the new movement's id is appended
   * @param rollbackOnReject whether a rejection must roll the transaction back. TRUE only for the
   *     manual path when invoices were paid upstream, since that is the one case with uncommitted
   *     writes and a single request to undo. MUST stay false for the automatch batch: there the
   *     rejection is per group, sibling groups have already been prepared, and rolling back would
   *     discard their work and close the session the rest of the loop still needs.
   * @return {@code null} to let the caller carry on (nothing to post, out of scope, or posted
   *         successfully), or the error response to return verbatim
   */
  static NeoResponse applyInlineDifference(ReconciliationHandler handler,
      FIN_FinancialAccount account, FIN_BankStatementLine line, List<String> operationIds,
      JSONObject body, boolean rollbackOnReject) throws Exception {
    BigDecimal lineAmount = signedLineAmount(line);
    BigDecimal opSum = BigDecimal.ZERO;
    for (String opId : operationIds) {
      // validateOperations already rejected unresolvable ids upstream; the guard just keeps this
      // helper safe to call from any future ordering.
      FIN_FinaccTransaction trx = handler.loadTransaction(opId);
      if (trx != null) {
        opSum = opSum.add(ReconciliationSupport.signedAmount(trx));
      }
    }
    BigDecimal gap = lineAmount.subtract(opSum);

    // Nothing to post. This is also where a DATE-only deviation lands: the amount balances, so no
    // accounting entry is created and no GL item is required — the date affects classification and
    // the automatch proposal, never the posting.
    if (isNegligible(gap)) {
      return null;
    }
    // Over-coverage (the operations exceed the line). Out of scope for ETP-4965 and already
    // rejected upstream by validateOperations; never post our way out of it.
    if (gap.signum() != lineAmount.signum()) {
      return null;
    }
    // Nothing was actually matched, so there is no near-match to complete — only an empty (or
    // zero-amount) selection. Same reasoning as reconcileDifference's MSG_NOT_PARTIAL guard: without
    // it the gap equals the whole line, and a tolerance of 100% or more would authorise posting the
    // entire statement line to the difference concept.
    if (isNegligible(opSum)) {
      return null;
    }
    BigDecimal limit = NearMatchSupport.differenceTolerance(lineAmount, handler.loadTolerances(
        account.getId())[1]);
    // Tolerance unset (0% = disabled) or the gap is bigger than it: leave the pre-existing partial
    // split behaviour exactly as it was.
    if (limit == null || gap.abs().compareTo(limit) > 0) {
      return null;
    }

    String requestedGlItemId = body != null ? body.optString(KEY_GL_ITEM_ID, null) : null;
    String glItemId = effectiveGlItemId(requestedGlItemId, account);
    if (StringUtils.isBlank(glItemId)) {
      return glItemRequired(handler, gap, rollbackOnReject);
    }
    // A client-supplied id that does not resolve is a different failure from "none configured": it
    // must NOT come back as GL_ITEM_REQUIRED, or the UI would reopen the picker on the very id the
    // user just chose.
    NeoResponse glError = checkGlItem(glItemId, requestedGlItemId);
    if (glError != null) {
      if (rollbackOnReject) {
        rollbackQuietly(handler);
      }
      return glError;
    }

    String description = body != null ? StringUtils.trimToNull(body.optString(KEY_DESCRIPTION, null))
        : null;
    String trxId = handler.createTransactionForRule(account, line,
        differenceSpec(glItemId, gap, defaultDifferenceDescription(description, glItemId)));
    if (StringUtils.isNotBlank(trxId)) {
      operationIds.add(trxId);
    }
    return null;
  }

  /**
   * A description the difference movement can be recognised by in the Movements list.
   *
   * <p>Without one, {@code createTransactionForRule} falls back to the statement line's description
   * — and an imported line very often has none, which is how a bare 0,38 row with nothing but an
   * amount ends up in the list, indistinguishable from a real movement.
   *
   * <p>The text is resolved from the message dictionary so it arrives in the user's language;
   * {@code messageBD} echoes the key back when the message is not installed, so that case degrades
   * to the accounting concept's own name, which is at least meaningful and already localized.
   *
   * @param requested the description explicitly supplied by the caller, which always wins
   */
  static String defaultDifferenceDescription(String requested, String glItemId) {
    if (StringUtils.isNotBlank(requested)) {
      return requested;
    }
    String label = OBMessageUtils.messageBD(MSG_DIFFERENCE_DESCRIPTION);
    if (StringUtils.isNotBlank(label) && !StringUtils.equals(label, MSG_DIFFERENCE_DESCRIPTION)) {
      return label;
    }
    GLItem glItem = StringUtils.isNotBlank(glItemId)
        ? OBDal.getInstance().get(GLItem.class, glItemId) : null;
    return glItem != null ? glItem.getName() : null;
  }

  /**
   * The 400 that tells the client to pick an accounting concept, shaped like
   * {@link #alreadyReconciled} so the {@code code} and the amount survive alongside the message —
   * the manual panel opens its concept picker on that code, and the automatch modal counts the
   * group as failed and offers a link to Edit account.
   *
   * <p>{@code rollbackOnReject} covers the manual invoice path, where {@code payInvoices} has
   * already written payments by the time this is reached and a plain error return would commit
   * them. It is deliberately NOT set for the automatch batch — see {@link #applyInlineDifference}.
   */
  private static NeoResponse glItemRequired(ReconciliationHandler handler, BigDecimal gap,
      boolean rollbackOnReject) {
    if (rollbackOnReject) {
      rollbackQuietly(handler);
    }
    try {
      JSONObject error = new JSONObject();
      error.put("message", MSG_GL_ITEM_REQUIRED);
      error.put(ReconciliationHandler.KEY_STATUS, HttpServletResponse.SC_BAD_REQUEST);
      JSONObject payload = new JSONObject();
      payload.put("error", error);
      payload.put("code", CODE_GL_ITEM_REQUIRED);
      payload.put("differenceAmount", gap.toPlainString());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, payload);
    } catch (Exception e) {
      log.debug("Could not build the GL-item-required payload", e);
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_GL_ITEM_REQUIRED);
    }
  }

  // ---------------------------------------------------------------------------
  // Pure helpers (no DAL, no statics — unit-tested directly)
  // ---------------------------------------------------------------------------

  /**
   * Reduces a match group to {@link GroupSnapshot}. Inactive rows are skipped: {@code
   * loadMatchGroupLines} does not filter them, and one would inflate {@code groupTotal} and thereby
   * loosen the tolerance gate.
   */
  static GroupSnapshot summarizeGroup(List<FIN_BankStatementLine> siblings, String targetLineId) {
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal remainder = BigDecimal.ZERO;
    int pending = 0;
    String firstPendingId = null;
    if (siblings == null) {
      return new GroupSnapshot(total, remainder, pending, null);
    }
    for (FIN_BankStatementLine s : siblings) {
      if (s == null || !s.isActive()) {
        continue;
      }
      BigDecimal amount = signedLineAmount(s);
      total = total.add(amount);
      if (s.getFinancialAccountTransaction() == null) {
        pending++;
        if (firstPendingId == null) {
          firstPendingId = s.getId();
        }
      }
      if (targetLineId != null && targetLineId.equals(s.getId())) {
        remainder = amount;
      }
    }
    return new GroupSnapshot(total, remainder, pending, firstPendingId);
  }

  /** Signed amount of a statement line: {@code cramount - dramount}. */
  static BigDecimal signedLineAmount(FIN_BankStatementLine line) {
    return nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
  }

  /**
   * The cap on the remainder: a percentage of the ORIGINAL line amount, never of the remainder
   * itself. An unset or non-positive percentage yields zero, which disables the action — see the
   * tolerance note on the class javadoc.
   */
  static BigDecimal differenceLimit(BigDecimal groupTotal, BigDecimal amtTolPct) {
    if (amtTolPct == null || amtTolPct.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return nullSafe(groupTotal).abs().multiply(amtTolPct)
        .divide(HUNDRED, 2, RoundingMode.HALF_UP);
  }

  static boolean withinTolerance(BigDecimal remainder, BigDecimal limit) {
    return nullSafe(remainder).abs().compareTo(nullSafe(limit)) <= 0;
  }

  static boolean isNegligible(BigDecimal amount) {
    return nullSafe(amount).abs().compareTo(NEGLIGIBLE) < 0;
  }

  /** The request's GL item when given, else the account's configured difference concept. */
  static String effectiveGlItemId(String requestedGlItemId, FIN_FinancialAccount account) {
    if (StringUtils.isNotBlank(requestedGlItemId)) {
      return requestedGlItemId;
    }
    GLItem fallback = account != null ? account.getAprmGlitemDiff() : null;
    return fallback != null ? fallback.getId() : null;
  }

  /**
   * The {@code createTransactionForRule} spec. The amount is always emitted non-zero (the callers
   * guarantee it via {@link #isNegligible}) because a {@code "0"} would make that builder fall back
   * to the whole line amount.
   */
  static JSONObject differenceSpec(String glItemId, BigDecimal remainder, String description)
      throws Exception {
    JSONObject spec = new JSONObject();
    spec.put(KEY_GL_ITEM_ID, glItemId);
    spec.put(ReconciliationHandler.KEY_AMOUNT, remainder.toPlainString());
    if (StringUtils.isNotBlank(description)) {
      spec.put(KEY_DESCRIPTION, description);
    }
    return spec;
  }

  /** The synthesized single-operation body handed to {@code reconcileGroup}. */
  static JSONObject reconcileGroupBody(String accountId, String lineId, String trxId)
      throws Exception {
    JSONObject body = new JSONObject();
    body.put(ReconciliationHandler.KEY_FINANCIAL_ACCOUNT_ID, accountId);
    body.put(ReconciliationHandler.KEY_STATEMENT_LINE_ID, lineId);
    JSONArray ops = new JSONArray();
    ops.put(trxId);
    body.put(KEY_OPERATION_IDS, ops);
    return body;
  }

  // ---------------------------------------------------------------------------
  // DAL / JDBC
  // ---------------------------------------------------------------------------

  /**
   * Takes a row lock on the statement line so two concurrent difference postings cannot both
   * proceed. Uses the DAL session's own connection (as {@code loadTolerances} does), so the lock
   * lives exactly as long as the request's transaction.
   *
   * @return {@code false} when the row is already locked by another transaction
   */
  private static boolean lockStatementLine(String lineId) {
    String sql = "SELECT fin_finacc_transaction_id FROM fin_bankstatementline"
        + " WHERE fin_bankstatementline_id = ? FOR UPDATE NOWAIT"; // NOSONAR java:S2077
    try (PreparedStatement ps =
        OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, lineId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
      }
      return true;
    } catch (Exception e) {
      log.warn("Could not lock statement line {} for a difference posting: {}", lineId,
          e.getMessage());
      return false;
    }
  }

  private static void rollbackQuietly(ReconciliationHandler handler) {
    try {
      handler.doRollbackAndClose();
    } catch (Exception e) {
      log.warn("Rollback after a failed difference reconciliation did not complete", e);
    }
  }

  /** Adds the adjustment's own ids to the delegated envelope, defensively. */
  private static void enrichResponse(NeoResponse response, String trxId, BigDecimal remainder,
      String glItemId) {
    try {
      JSONObject data = response.getBody() != null
          ? response.getBody().optJSONObject("response") : null;
      data = data != null ? data.optJSONObject("data") : null;
      if (data == null) {
        return;
      }
      data.put(ReconciliationHandler.KEY_TRANSACTION_ID, trxId);
      data.put("differenceAmount", remainder.toPlainString());
      data.put(KEY_GL_ITEM_ID, glItemId);
    } catch (Exception e) {
      log.debug("Could not enrich the difference reconciliation response", e);
    }
  }
}
