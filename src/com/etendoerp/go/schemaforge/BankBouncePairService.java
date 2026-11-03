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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.hook.BankStatementLineFilterContext.Usage;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.BankBounceDetector.AccountData;
import com.etendoerp.go.schemaforge.BankBounceDetector.Candidate;
import com.etendoerp.go.schemaforge.BankBounceDetector.Pair;
import com.etendoerp.go.schemaforge.BankBounceDetector.StatementLine;

/**
 * Discards and restores bounce pairs (ETP-5581): {@code POST bank-reconciliation?action=discardPair},
 * the {@code bouncePair} groups of {@code applySuggestions} and
 * {@code POST bank-reconciliation?action=restorePair}.
 *
 * <p><b>Always by pair.</b> Etendo GO never discards or restores a single line: discarding marks
 * BOTH lines discarded and links each to the other; restoring a line restores its linked
 * counterpart too and clears the link on both. The single-line discard stays a Classic feature
 * (PSD2's Discard / Restore button).
 *
 * <p><b>What a discard writes</b>, on both lines, in the request's transaction: the PSD2 flag and
 * button ({@link StatementLineDiscard#setDiscarded}, kept in sync exactly as PSD2's own action does)
 * and the four link columns of this module — {@code EM_ETGO_Bounce_Pair_Line_ID} (the counterpart;
 * the foreign key is set to null when that line is deleted), {@code EM_ETGO_Bounce_Discarded} (when),
 * {@code EM_ETGO_Bounce_Signal} (the primary signal, or null for a pair with none) and
 * {@code EM_ETGO_Bounce_Confidence}. The columns are written through their column names
 * ({@link StatementLineDiscard#set}), so this compiles before the DAL is regenerated.
 *
 * <p><b>Gating.</b> {@code discardPair}, {@code restorePair} and the {@code bouncePair} groups
 * require {@link BankBounceSuggestionsFlag} (403 {@code BOUNCE_SUGGESTIONS_OFF} otherwise): with
 * the flag off Etendo does not honour discarded lines at all ({@link StatementLineFilter}), so
 * there is nothing to discard or restore from here.
 *
 * <p><b>Validation</b>, shared by both discard paths, in this order: the two ids differ and both
 * lines belong to the account; the PSD2 discard is installed; neither line is already excluded from
 * reconciliation ({@code LINE_DISCARDED}; a discarded line matched to a transaction counts as
 * reconciled instead); both are unreconciled PSD2 lines
 * ({@code DISCARD_NOT_ALLOWED}); both are in the pending pool — active, on a processed statement,
 * outside any split group ({@code LINE_NOT_PENDING}); opposite sign ({@code SIGN_MISMATCH}) and the
 * exact amount ({@code AMOUNT_MISMATCH}); the later line within
 * {@value BankBounceDetector#WINDOW_DAYS} days of the earlier ({@code DISCARD_NOT_ALLOWED}); and no
 * real ERP payment on either line ({@code HAS_PAYMENT}, {@link BankBouncePaymentGuard}). A pair the
 * detection did not propose (no signal) is accepted when it passes all of this: the user chose it.
 */
final class BankBouncePairService {

  static final String ACTION_DISCARD_PAIR = "discardPair";
  static final String ACTION_RESTORE_PAIR = "restorePair";

  static final String KEY_COUNTERPART_LINE_ID = "counterpartLineId";
  static final String KEY_LINE_ID = "lineId";
  private static final String KEY_DISCARDED = "discarded";
  private static final String KEY_RESTORED = "restored";
  private static final String KEY_SIGNAL = "signal";
  private static final String KEY_CONFIDENCE = "confidence";

  /** Self-reference to the counterpart line; {@code ON DELETE SET NULL}. */
  static final String COL_PAIR_LINE = "EM_ETGO_Bounce_Pair_Line_ID";
  /** When the pair was discarded. */
  static final String COL_DISCARDED_AT = "EM_ETGO_Bounce_Discarded";
  /** Primary signal of the pair: {@code pis_link}, {@code return_text} or {@code counterparty}. */
  static final String COL_SIGNAL = "EM_ETGO_Bounce_Signal";
  /** Confidence of the pair: {@code high} or {@code review}. */
  static final String COL_CONFIDENCE = "EM_ETGO_Bounce_Confidence";

  /** Utility class: static members only, never instantiated. */
  private BankBouncePairService() {
  }

  /**
   * A validated pair, or the refusal.
   *
   * @param original   the original line (the earlier one)
   * @param bounce     the line that reverses it
   * @param signal     its primary signal, or {@code null}
   * @param confidence its confidence
   * @param error      the refusal, or {@code null} when the pair can be discarded
   */
  record Validated(FIN_BankStatementLine original, FIN_BankStatementLine bounce, String signal,
      String confidence, NeoResponse error) {

    /**
     * A refusal.
     *
     * @param error the refusal
     * @return the result
     */
    static Validated failed(NeoResponse error) {
      return new Validated(null, null, null, null, error);
    }
  }

  // ---------------------------------------------------------------------------
  // Routes
  // ---------------------------------------------------------------------------

  /**
   * {@code POST discardPair}: the shared POST envelope of the reconciliation actions (admin mode,
   * rollback on any refusal, {@code ReconciliationHandlerSupport.runPostAction}) around
   * {@link #discardPair(ReconciliationHandler, JSONObject)}. Used by the SPA route and by
   * {@code etendo_action}.
   *
   * @param handler the reconciliation handler
   * @param context the request
   * @return the action's answer
   */
  static NeoResponse handleDiscardPair(ReconciliationHandler handler, NeoContext context) {
    return ReconciliationHandlerSupport.runPostAction(handler, context, ACTION_DISCARD_PAIR);
  }

  /**
   * {@code POST restorePair}: the same envelope around
   * {@link #restorePair(ReconciliationHandler, JSONObject)}. Gated by the flag, like the discard.
   *
   * @param handler the reconciliation handler
   * @param context the request
   * @return the action's answer
   */
  static NeoResponse handleRestorePair(ReconciliationHandler handler, NeoContext context) {
    return ReconciliationHandlerSupport.runPostAction(handler, context, ACTION_RESTORE_PAIR);
  }

  // ---------------------------------------------------------------------------
  // discardPair
  // ---------------------------------------------------------------------------

  /**
   * {@code POST discardPair {financialAccountId, statementLineId, counterpartLineId}}: discards a
   * pair from the reconciliation panel, also once the user picked the original of an ambiguous
   * pair. Requires the flag.
   *
   * @param handler the reconciliation handler, for its DAL seams
   * @param body    the request body
   * @return 200 with {@code {discarded:true, statementLineId, counterpartLineId, signal,
   *         confidence}}, or the refusal
   * @throws Exception when the account data cannot be read
   */
  static NeoResponse discardPair(ReconciliationHandler handler, JSONObject body) throws Exception {
    if (!BankBounceSuggestionsFlag.isEnabled()) {
      return BankBounceErrors.suggestionsOff();
    }
    FIN_FinancialAccount account = requireAccount(handler, body);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_ACCOUNT_NOT_FOUND
              + body.optString(ReconciliationHandler.KEY_FINANCIAL_ACCOUNT_ID, ""));
    }
    String lineId = body.optString(ReconciliationHandler.KEY_STATEMENT_LINE_ID, null);
    String counterpartId = body.optString(KEY_COUNTERPART_LINE_ID, null);
    NeoResponse missing = missingIds(lineId, counterpartId);
    if (missing != null) {
      return missing;
    }
    Validated pair = validate(handler, account, lineId, counterpartId);
    if (pair.error() != null) {
      return pair.error();
    }
    discard(pair);
    return ReconciliationSupport.envelope(discardedJson(lineId, counterpartId, pair));
  }

  // ---------------------------------------------------------------------------
  // applySuggestions: bouncePair groups
  // ---------------------------------------------------------------------------

  /**
   * Whether an {@code applySuggestions} group is a bounce pair.
   *
   * @param group the group
   * @return {@code true} for {@code kind:"bouncePair"}
   */
  static boolean isBouncePairGroup(JSONObject group) {
    return group != null && BankBounceSuggestions.KIND_BOUNCE_PAIR.equals(
        group.optString(BankBounceSuggestions.KEY_KIND, ""));
  }

  /**
   * The pre-step of {@code applySuggestions}: validates and discards every
   * {@code {kind:"bouncePair", statementLineId, counterpartLineId}} group, BEFORE any transaction
   * group is prepared and in the same request transaction. Every line it discards joins
   * {@code takenLineIds}, the set the transaction groups are then prepared against, so a line
   * checked in two groups is refused the second time (409). Each pair succeeds or fails on its own;
   * a refusal is appended to {@code results} tagged with its lines and kind. With the flag off,
   * every such group is refused with the 403 and nothing is written.
   *
   * @param handler      the reconciliation handler, for its DAL seams
   * @param account      the account of the call, already resolved
   * @param groups       every submitted group
   * @param takenLineIds the lines already taken in this call, updated
   * @param results      the per-group results, appended to
   * @return how many bounce-pair groups were submitted
   * @throws Exception when the account data cannot be read or a result cannot be written
   */
  static int applyPairs(ReconciliationHandler handler, FIN_FinancialAccount account,
      JSONArray groups, Set<String> takenLineIds, JSONArray results) throws Exception {
    Boolean enabled = null;
    int submitted = 0;
    for (int i = 0; i < groups.length(); i++) {
      JSONObject group = groups.optJSONObject(i);
      if (!isBouncePairGroup(group)) {
        continue;
      }
      submitted++;
      if (enabled == null) {
        enabled = BankBounceSuggestionsFlag.isEnabled();
      }
      String lineId = group.optString(ReconciliationHandler.KEY_STATEMENT_LINE_ID, null);
      String counterpartId = group.optString(KEY_COUNTERPART_LINE_ID, null);
      NeoResponse refusal = Boolean.TRUE.equals(enabled)
          ? applyOne(handler, account, lineId, counterpartId, takenLineIds)
          : BankBounceErrors.suggestionsOff();
      results.put(refusal != null ? tagged(refusal.getBody(), lineId, counterpartId)
          : tagged(new JSONObject().put(KEY_DISCARDED, true), lineId, counterpartId));
    }
    return submitted;
  }

  /**
   * Validates and discards one bounce-pair group of {@code applySuggestions}.
   *
   * @param handler       the reconciliation handler
   * @param account       the account of the call
   * @param lineId        the group's {@code statementLineId}
   * @param counterpartId the group's {@code counterpartLineId}
   * @param takenLineIds  the lines already taken in this call, updated on success
   * @return the refusal, or {@code null} when the pair was discarded
   * @throws Exception when the account data cannot be read
   */
  private static NeoResponse applyOne(ReconciliationHandler handler, FIN_FinancialAccount account,
      String lineId, String counterpartId, Set<String> takenLineIds) throws Exception {
    NeoResponse missing = missingIds(lineId, counterpartId);
    if (missing != null) {
      return missing;
    }
    if (takenLineIds.contains(lineId) || takenLineIds.contains(counterpartId)) {
      return BankBounceErrors.conflict(BankBounceErrors.CODE_LINE_NOT_PENDING);
    }
    Validated pair = validate(handler, account, lineId, counterpartId);
    if (pair.error() != null) {
      return pair.error();
    }
    discard(pair);
    takenLineIds.add(lineId);
    takenLineIds.add(counterpartId);
    return null;
  }

  /**
   * Stamps a result entry with the lines and kind of its group, so the client can tell which
   * suggestion it answers.
   *
   * @param entry         the result entry (a refusal body or a success)
   * @param lineId        the group's {@code statementLineId}
   * @param counterpartId the group's {@code counterpartLineId}
   * @return the entry
   * @throws JSONException when it cannot be written
   */
  private static JSONObject tagged(JSONObject entry, String lineId, String counterpartId)
      throws JSONException {
    JSONObject out = entry != null ? entry : new JSONObject();
    out.put(BankBounceSuggestions.KEY_KIND, BankBounceSuggestions.KIND_BOUNCE_PAIR);
    out.put(ReconciliationHandler.KEY_STATEMENT_LINE_ID, StringUtils.defaultString(lineId));
    out.put(KEY_COUNTERPART_LINE_ID, StringUtils.defaultString(counterpartId));
    return out;
  }

  // ---------------------------------------------------------------------------
  // restorePair
  // ---------------------------------------------------------------------------

  /**
   * {@code POST restorePair {financialAccountId, lineId}}: restores the line AND its linked
   * counterpart, and clears the link on both. Requires the flag (403 {@code BOUNCE_SUGGESTIONS_OFF}
   * before anything is read otherwise): with it off Etendo does not honour discarded lines at all,
   * so there is nothing to undo here; a line can still be restored in Classic with PSD2's own
   * Discard / Restore button.
   *
   * <p>A discarded line with no link (discarded one by one in Classic) is restored alone: there is
   * no pair to keep consistent. A line that is not discarded and has no link is left as it is and
   * the answer lists nothing restored, so repeating the call is harmless.
   *
   * @param handler the reconciliation handler, for its DAL seams
   * @param body    the request body
   * @return 200 with {@code {restored:[ids], lineId, counterpartLineId}}, or the refusal
   * @throws JSONException when the answer cannot be written
   */
  static NeoResponse restorePair(ReconciliationHandler handler, JSONObject body)
      throws JSONException {
    if (!BankBounceSuggestionsFlag.isEnabled()) {
      return BankBounceErrors.suggestionsOff();
    }
    FIN_FinancialAccount account = requireAccount(handler, body);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_ACCOUNT_NOT_FOUND
              + body.optString(ReconciliationHandler.KEY_FINANCIAL_ACCOUNT_ID, ""));
    }
    String lineId = body.optString(KEY_LINE_ID, null);
    if (StringUtils.isBlank(lineId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_MISSING_PARAM + KEY_LINE_ID);
    }
    FIN_BankStatementLine line = handler.loadLine(lineId);
    NeoResponse lineError = ownedLineError(line, lineId, account);
    if (lineError != null) {
      return lineError;
    }
    if (!StatementLineDiscard.isSupported()) {
      return BankBounceErrors.conflict(BankBounceErrors.CODE_DISCARD_NOT_ALLOWED);
    }
    FIN_BankStatementLine counterpart = linkedCounterpart(line, account);
    List<String> restored = new ArrayList<>();
    restoreOne(line, restored);
    if (counterpart != null) {
      restoreOne(counterpart, restored);
    }
    OBDal.getInstance().flush();
    JSONObject data = new JSONObject();
    data.put(KEY_RESTORED, new JSONArray(restored));
    data.put(KEY_LINE_ID, lineId);
    data.put(KEY_COUNTERPART_LINE_ID, counterpart != null ? counterpart.getId() : JSONObject.NULL);
    return ReconciliationSupport.envelope(data);
  }

  /**
   * The counterpart the line is linked to, when it belongs to the same account.
   *
   * @param line    the line
   * @param account the account of the call
   * @return the counterpart, or {@code null}
   */
  private static FIN_BankStatementLine linkedCounterpart(FIN_BankStatementLine line,
      FIN_FinancialAccount account) {
    Object linked = StatementLineDiscard.get(line, COL_PAIR_LINE);
    if (!(linked instanceof FIN_BankStatementLine)) {
      return null;
    }
    FIN_BankStatementLine counterpart = (FIN_BankStatementLine) linked;
    return ReconciliationSupport.belongsToAccount(counterpart, account.getId()) ? counterpart
        : null;
  }

  /**
   * The pair a discarded line was discarded with, for the {@code status=discarded} listing:
   * {@code {counterpartLineId, discardedAt, signal, confidence}}, or {@link JSONObject#NULL} when
   * the line has no link (discarded on its own in Classic) or the link columns are not mapped yet.
   * Read-only, so not gated by the flag.
   *
   * @param line the line, or {@code null}
   * @return the link, or {@link JSONObject#NULL}
   * @throws JSONException when it cannot be written
   */
  static Object linkJson(FIN_BankStatementLine line) throws JSONException {
    Object counterpart = StatementLineDiscard.get(line, COL_PAIR_LINE);
    if (!(counterpart instanceof FIN_BankStatementLine)) {
      return JSONObject.NULL;
    }
    Object discardedAt = StatementLineDiscard.get(line, COL_DISCARDED_AT);
    JSONObject json = new JSONObject();
    json.put(KEY_COUNTERPART_LINE_ID, ((FIN_BankStatementLine) counterpart).getId());
    json.put("discardedAt", discardedAt instanceof Date
        ? ReconciliationSupport.formatDate(new java.sql.Timestamp(((Date) discardedAt).getTime()))
        : JSONObject.NULL);
    json.put(KEY_SIGNAL, nullable(StatementLineDiscard.get(line, COL_SIGNAL)));
    json.put(KEY_CONFIDENCE, nullable(StatementLineDiscard.get(line, COL_CONFIDENCE)));
    return json;
  }

  /**
   * {@code value}, or {@link JSONObject#NULL} when it is {@code null}.
   *
   * @param value the value
   * @return a JSON-safe value
   */
  private static Object nullable(Object value) {
    return value != null ? value : JSONObject.NULL;
  }

  /**
   * Restores one line when it is discarded and clears its link columns.
   *
   * @param line     the line
   * @param restored the ids of the restored lines, appended to
   */
  private static void restoreOne(FIN_BankStatementLine line, List<String> restored) {
    if (StatementLineDiscard.isDiscarded(line)) {
      StatementLineDiscard.setDiscarded(line, false);
      restored.add(line.getId());
    }
    clearLink(line);
    OBDal.getInstance().save(line);
  }

  /**
   * Clears the four link columns, when the running model has them. Not saved.
   *
   * @param line the line
   */
  static void clearLink(FIN_BankStatementLine line) {
    for (String column : new String[] { COL_PAIR_LINE, COL_DISCARDED_AT, COL_SIGNAL,
        COL_CONFIDENCE }) {
      if (StatementLineDiscard.property(column) != null) {
        StatementLineDiscard.set(line, column, null);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Validation and write
  // ---------------------------------------------------------------------------

  /**
   * Validates a pair for discard (see the class Javadoc for the order) and resolves its roles,
   * primary signal and confidence.
   *
   * @param handler       the reconciliation handler
   * @param account       the account, already resolved
   * @param lineId        one line
   * @param counterpartId the other line
   * @return the validated pair, or the refusal
   * @throws Exception when the account data cannot be read
   */
  static Validated validate(ReconciliationHandler handler, FIN_FinancialAccount account,
      String lineId, String counterpartId) throws Exception {
    if (lineId.equals(counterpartId)) {
      return Validated.failed(NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "statementLineId and counterpartLineId must be two different lines"));
    }
    FIN_BankStatementLine a = handler.loadLine(lineId);
    FIN_BankStatementLine b = handler.loadLine(counterpartId);
    NeoResponse owned = firstError(ownedLineError(a, lineId, account),
        ownedLineError(b, counterpartId, account));
    if (owned != null) {
      return Validated.failed(owned);
    }
    if (!StatementLineDiscard.isSupported()) {
      return Validated.failed(BankBounceErrors.conflict(BankBounceErrors.CODE_DISCARD_NOT_ALLOWED));
    }
    NeoResponse lineError = firstError(lineError(a), lineError(b), shapeError(a, b));
    if (lineError != null) {
      return Validated.failed(lineError);
    }
    OBDal.getInstance().flush();
    int dateTolDays = handler.loadTolerances(account.getId())[0].intValue();
    AccountData data = BankBounceDetector.load(account,
        handler.loadPendingLines(account.getId()), dateTolDays);
    StatementLine la = data.find(a.getId());
    StatementLine lb = data.find(b.getId());
    if (la == null || lb == null) {
      // Not among the auto-match candidates although every check above passed.
      return Validated.failed(BankBounceErrors.conflict(BankBounceErrors.CODE_LINE_NOT_PENDING));
    }
    boolean aFirst = BankBounceDetector.isOriginalOf(data, la, lb)
        || (!BankBounceDetector.isOriginalOf(data, lb, la) && !la.date().isAfter(lb.date()));
    StatementLine original = aFirst ? la : lb;
    StatementLine bounce = aFirst ? lb : la;
    if (!BankBounceDetector.withinWindow(original.date(), bounce.date())) {
      return Validated.failed(BankBounceErrors.conflict(BankBounceErrors.CODE_DISCARD_NOT_ALLOWED));
    }
    List<Pair> detected = BankBounceDetector.detect(data);
    String blocker = BankBouncePaymentGuard.blocker(data, otherPairLines(detected, la, lb), la, lb);
    if (blocker != null) {
      return Validated.failed(BankBounceErrors.conflict(blocker));
    }
    String[] signalAndConfidence = signalAndConfidence(data, detected, original, bounce);
    return new Validated(aFirst ? a : b, aFirst ? b : a, signalAndConfidence[0],
        signalAndConfidence[1], null);
  }

  /**
   * The lines of the detected pairs that touch neither line of the requested pair: they are taken
   * by other pairs, so the payment guard does not count them as free.
   *
   * @param detected the pairs detected over the same data
   * @param a        one requested line
   * @param b        the other
   * @return the line ids
   */
  private static Set<String> otherPairLines(List<Pair> detected, StatementLine a,
      StatementLine b) {
    Set<String> ids = new HashSet<>();
    for (Pair p : detected) {
      List<String> lines = p.lineIds();
      if (!lines.contains(a.id()) && !lines.contains(b.id())) {
        ids.addAll(lines);
      }
    }
    return ids;
  }

  /**
   * The primary signal and confidence to store: those the detection offered for this exact pair
   * (an ambiguous pair stays {@code review} once its original is chosen), else the detection's
   * rules applied to the two lines (a pair with no signal stores none, for review).
   *
   * @param data     the loaded data
   * @param detected the pairs detected over the same data
   * @param original the original
   * @param bounce   the bounce
   * @return {@code [signal or null, confidence]}
   */
  private static String[] signalAndConfidence(AccountData data, List<Pair> detected,
      StatementLine original, StatementLine bounce) {
    for (Pair p : detected) {
      if (!p.bounce().id().equals(bounce.id())) {
        continue;
      }
      if (p.original() != null && p.original().id().equals(original.id())) {
        return new String[] { p.primarySignal(), p.confidence() };
      }
      for (Candidate c : p.candidates()) {
        if (c.line().id().equals(original.id())) {
          return new String[] { BankBounceDetector.primarySignalOf(c.signals()),
              p.confidence() };
        }
      }
    }
    List<String> signals = BankBounceDetector.signals(data, original, bounce);
    String confidence = signals.isEmpty() ? BankBounceDetector.CONFIDENCE_REVIEW
        : BankBounceDetector.confidence(data, original, bounce, signals);
    return new String[] { BankBounceDetector.primarySignalOf(signals), confidence };
  }

  /**
   * Discards both lines of a validated pair and links each to the other, then flushes.
   *
   * @param pair the validated pair
   */
  static void discard(Validated pair) {
    Date now = new Date();
    link(pair.original(), pair.bounce(), now, pair);
    link(pair.bounce(), pair.original(), now, pair);
    OBDal.getInstance().flush();
  }

  /**
   * Discards one line of a pair and points it at the other. Saved, not flushed.
   *
   * @param line        the line
   * @param counterpart the other line of the pair
   * @param now         the discard time, shared by both lines
   * @param pair        the validated pair
   */
  private static void link(FIN_BankStatementLine line, FIN_BankStatementLine counterpart,
      Date now, Validated pair) {
    StatementLineDiscard.setDiscarded(line, true);
    StatementLineDiscard.set(line, COL_PAIR_LINE, counterpart);
    StatementLineDiscard.set(line, COL_DISCARDED_AT, now);
    StatementLineDiscard.set(line, COL_SIGNAL, pair.signal());
    StatementLineDiscard.set(line, COL_CONFIDENCE, pair.confidence());
    OBDal.getInstance().save(line);
  }

  /**
   * The refusal for a line that cannot take part in a discard, or {@code null}: excluded already
   * ({@code LINE_DISCARDED}), reconciled or not imported by PSD2 ({@code DISCARD_NOT_ALLOWED}),
   * outside the pending pool ({@code LINE_NOT_PENDING}). Only an unmatched line can be refused as
   * discarded: a line matched to a transaction (a discarded line reconciled while the flag was off)
   * counts as reconciled, so neither its PSD2 flag nor the hooks are consulted for it — PSD2's hook
   * condition looks at the discard flag alone and would call it discarded.
   *
   * @param line the line, owned by the account
   * @return the refusal, or {@code null}
   */
  private static NeoResponse lineError(FIN_BankStatementLine line) {
    boolean unmatched = line.getFinancialAccountTransaction() == null;
    if (unmatched && (StatementLineDiscard.isDiscarded(line)
        || StatementLineFilter.isExcluded(line, Usage.AUTO_MATCH_CANDIDATES))) {
      return StatementLineDiscard.discardedError();
    }
    if (!unmatched || StatementLineDiscard.transactionIdentifier(line) == null) {
      return BankBounceErrors.conflict(BankBounceErrors.CODE_DISCARD_NOT_ALLOWED);
    }
    if (!Boolean.TRUE.equals(line.isActive()) || ReconciliationSupport.isOnDraftStatement(line)
        || StringUtils.isNotBlank(ReactivationSupport.readMatchGroupId(line))) {
      return BankBounceErrors.conflict(BankBounceErrors.CODE_LINE_NOT_PENDING);
    }
    return null;
  }

  /**
   * Opposite sign ({@code SIGN_MISMATCH}), then the exact amount ({@code AMOUNT_MISMATCH}).
   *
   * @param a one line
   * @param b the other
   * @return the refusal, or {@code null}
   */
  static NeoResponse shapeError(FIN_BankStatementLine a, FIN_BankStatementLine b) {
    BigDecimal left = signedAmount(a);
    BigDecimal right = signedAmount(b);
    if (left.signum() == 0 || left.signum() != -right.signum()) {
      return BankBounceErrors.conflict(BankBounceErrors.CODE_SIGN_MISMATCH);
    }
    if (left.abs().compareTo(right.abs()) != 0) {
      return BankBounceErrors.conflict(BankBounceErrors.CODE_AMOUNT_MISMATCH);
    }
    return null;
  }

  /**
   * {@code cramount - dramount}.
   *
   * @param line the line
   * @return its signed amount
   */
  private static BigDecimal signedAmount(FIN_BankStatementLine line) {
    return ReconciliationSupport.nullSafe(line.getCramount())
        .subtract(ReconciliationSupport.nullSafe(line.getDramount()));
  }

  /**
   * 404 for a line that does not exist, 400 for one of another account, {@code null} otherwise.
   *
   * @param line    the loaded line, or {@code null}
   * @param lineId  the requested id
   * @param account the account of the call
   * @return the refusal, or {@code null}
   */
  private static NeoResponse ownedLineError(FIN_BankStatementLine line, String lineId,
      FIN_FinancialAccount account) {
    if (line == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          ReconciliationHandler.MSG_STATEMENT_LINE_NOT_FOUND + lineId);
    }
    if (!ReconciliationSupport.belongsToAccount(line, account.getId())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_LINE_NOT_IN_ACCOUNT);
    }
    return null;
  }

  /**
   * 400 when either id of the pair is missing.
   *
   * @param lineId        the {@code statementLineId}
   * @param counterpartId the {@code counterpartLineId}
   * @return the refusal, or {@code null}
   */
  private static NeoResponse missingIds(String lineId, String counterpartId) {
    if (StringUtils.isBlank(lineId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_MISSING_PARAM + ReconciliationHandler.KEY_STATEMENT_LINE_ID);
    }
    if (StringUtils.isBlank(counterpartId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_MISSING_PARAM + KEY_COUNTERPART_LINE_ID);
    }
    return null;
  }

  /**
   * The account named by {@code financialAccountId}, resolved for the tenant by the handler.
   *
   * @param handler the reconciliation handler
   * @param body    the request body
   * @return the account, or {@code null}
   */
  private static FIN_FinancialAccount requireAccount(ReconciliationHandler handler,
      JSONObject body) {
    String accountId = body.optString(ReconciliationHandler.KEY_FINANCIAL_ACCOUNT_ID, null);
    return StringUtils.isBlank(accountId) ? null : handler.loadAccount(accountId);
  }

  /**
   * The first non-null refusal, in argument order.
   *
   * @param errors the candidate refusals
   * @return the first refusal, or {@code null}
   */
  private static NeoResponse firstError(NeoResponse... errors) {
    for (NeoResponse e : errors) {
      if (e != null) {
        return e;
      }
    }
    return null;
  }

  /**
   * The answer of a discard.
   *
   * @param lineId        the requested {@code statementLineId}
   * @param counterpartId the requested {@code counterpartLineId}
   * @param pair          the validated pair
   * @return the data
   * @throws JSONException when it cannot be written
   */
  private static JSONObject discardedJson(String lineId, String counterpartId, Validated pair)
      throws JSONException {
    JSONObject data = new JSONObject();
    data.put(KEY_DISCARDED, true);
    data.put(ReconciliationHandler.KEY_STATEMENT_LINE_ID, lineId);
    data.put(KEY_COUNTERPART_LINE_ID, counterpartId);
    data.put(KEY_SIGNAL, pair.signal() != null ? pair.signal() : JSONObject.NULL);
    data.put(KEY_CONFIDENCE, pair.confidence());
    return data;
  }
}
