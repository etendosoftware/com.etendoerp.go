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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.BankBounceDetector.Candidate;
import com.etendoerp.go.schemaforge.BankBounceDetector.Pair;

/**
 * Puts the detected bounce pairs into the two reconciliation answers that show them (ETP-5581):
 * the {@code autoMatch} groups and the {@code pendingLines} panel. Only reached when
 * {@link BankBounceSuggestionsFlag} is on; with the flag off both answers are unchanged, byte for
 * byte.
 *
 * <p><b>Same lines in both answers.</b> Both run the detection over the same auto-match candidate
 * lines and apply the same precedence, so the panel's {@code bounce} count and the modal's
 * {@code kpis.bouncePairs} agree:
 * <ol>
 *   <li>a {@code high} pair reserves its two lines BEFORE transaction matching: the auto-match loop
 *       skips them and the panel classifies them {@code bounce} without consuming a movement;</li>
 *   <li>a {@code review} pair is offered AFTER transaction matching, and only when the lines it
 *       would take are free: its bounce and, for a plain pair, its original — not taken by a
 *       transaction group, by a high pair or by another plain review pair. An ambiguous pair needs
 *       its bounce free and at least one candidate original left; the candidates taken elsewhere
 *       are dropped from it (one left makes it a plain pair), and the candidates it shares with
 *       another ambiguous pair are offered in both ({@link Plan#offered}).</li>
 * </ol>
 *
 * <p><b>Group shape</b> ({@code kind:"bouncePair"}): {@code groupKey}, {@code origin:"bounce"},
 * {@code statementLine} (the original, {@code null} when ambiguous), {@code counterpartLine} (the
 * bounce line), {@code candidates} (the candidate originals of an ambiguous pair, each with its
 * {@code signals}; empty otherwise), {@code ambiguous}, {@code adjacent} (the original, or the two
 * candidates, are the bounce's neighbours in its statement; it never raises the confidence),
 * {@code confidence}, {@code signals}, {@code signal} (the primary one), {@code operations:[]},
 * {@code isNew:false} and {@code difference:0}. Every transaction group gains
 * {@code kind:"transaction"}.
 */
final class BankBounceSuggestions {

  static final String KEY_KIND = "kind";
  static final String KIND_TRANSACTION = "transaction";
  static final String KIND_BOUNCE_PAIR = "bouncePair";

  /** State of a pending line that belongs to an offered pair, and its count key. */
  static final String STATE_BOUNCE = "bounce";
  /** Row annotation of {@code pendingLines}. */
  static final String KEY_BOUNCE_PAIR = "bouncePair";
  /** KPI of {@code autoMatch}. */
  static final String KPI_BOUNCE_PAIRS = "bouncePairs";

  static final String ROLE_ORIGINAL = "original";
  static final String ROLE_BOUNCE = "bounce";
  static final String ROLE_CANDIDATE = "candidate";

  private static final String KEY_ID = "id";
  private static final String KEY_GROUP_KEY = "groupKey";
  private static final String KEY_STATEMENT_LINE = "statementLine";
  private static final String KEY_COUNTERPART_LINE = "counterpartLine";
  private static final String KEY_CANDIDATES = "candidates";
  private static final String KEY_CONFIDENCE = "confidence";
  private static final String KEY_SIGNALS = "signals";
  private static final String KEY_SIGNAL = "signal";
  private static final String KEY_AMBIGUOUS = "ambiguous";
  private static final String KEY_ADJACENT = "adjacent";
  private static final String KEY_STATE = "state";

  /** Utility class: static members only, never instantiated. */
  private BankBounceSuggestions() {
  }

  /**
   * The pairs detected in one run, with the DAL lines they were read from.
   */
  static final class Plan {

    /** The plan of a request with the flag off: nothing reserved, nothing offered. */
    static final Plan NONE = new Plan(Collections.emptyList(), Collections.emptyMap(), false);

    private final List<Pair> pairs;
    private final Map<String, FIN_BankStatementLine> linesById;
    private final boolean enabled;

    /**
     * A plan over the pairs of one detection run.
     *
     * @param pairs     the detected pairs
     * @param linesById the auto-match candidate lines by id
     * @param enabled   whether the flag is on for this request
     */
    Plan(List<Pair> pairs, Map<String, FIN_BankStatementLine> linesById, boolean enabled) {
      this.pairs = pairs;
      this.linesById = linesById;
      this.enabled = enabled;
    }

    /**
     * Whether the flag is on for this request, i.e. whether the answers carry bounce data.
     *
     * @return {@code true} when bounce data is added
     */
    boolean enabled() {
      return enabled;
    }

    /**
     * The lines of the high-confidence pairs, reserved before transaction matching.
     *
     * @return the reserved line ids
     */
    Set<String> reservedLineIds() {
      Set<String> ids = new HashSet<>();
      for (Pair p : pairs) {
        if (p.high()) {
          ids.addAll(p.lineIds());
        }
      }
      return ids;
    }

    /**
     * The pairs that are offered once transaction matching has taken {@code taken}. A pair is
     * blocked only by the lines it would really take, settled in this order:
     * <ol>
     *   <li>every high pair is offered and takes its two lines;</li>
     *   <li>a plain review pair is offered when neither its bounce nor its original is used, and
     *       then takes both;</li>
     *   <li>an ambiguous pair is offered when its bounce is not used and at least one candidate is
     *       left once the used lines are removed ({@link #offeredAmbiguous}). It takes its bounce
     *       only, so ambiguous pairs that share candidate originals do not block each other.</li>
     * </ol>
     * The plain pairs are settled before the ambiguous ones, so the original of an offered pair is
     * never also listed as a candidate of another. {@code taken} is not modified.
     *
     * @param taken the lines taken by transaction groups
     * @return the offered pairs: high first, then the review pairs in detection order
     */
    List<Pair> offered(Set<String> taken) {
      Set<String> used = new HashSet<>(taken);
      Pair[] chosen = new Pair[pairs.size()];
      for (int i = 0; i < pairs.size(); i++) {
        Pair p = pairs.get(i);
        if (p.high()) {
          chosen[i] = p;
          used.addAll(p.lineIds());
        }
      }
      for (int i = 0; i < pairs.size(); i++) {
        Pair p = pairs.get(i);
        if (!p.high() && !p.ambiguous()) {
          chosen[i] = offeredPlain(p, used);
        }
      }
      for (int i = 0; i < pairs.size(); i++) {
        Pair p = pairs.get(i);
        if (p.ambiguous()) {
          chosen[i] = offeredAmbiguous(p, used);
        }
      }
      return highFirst(chosen);
    }

    /**
     * A plain review pair is offered when neither its bounce nor its original is used; it then
     * marks both used.
     *
     * @param p    a plain (non-ambiguous) review pair
     * @param used the lines already used, updated
     * @return {@code p}, or {@code null} when a line of it is used
     */
    private static Pair offeredPlain(Pair p, Set<String> used) {
      if (used.contains(p.bounce().id()) || used.contains(p.original().id())) {
        return null;
      }
      used.addAll(p.lineIds());
      return p;
    }

    /**
     * The offered pairs in answer order: the high ones, then the others, each in detection order.
     *
     * @param chosen the pair offered for each detected pair, {@code null} where none is
     * @return the offered pairs
     */
    private static List<Pair> highFirst(Pair[] chosen) {
      List<Pair> offered = new ArrayList<>();
      for (Pair p : chosen) {
        if (p != null && p.high()) {
          offered.add(p);
        }
      }
      for (Pair p : chosen) {
        if (p != null && !p.high()) {
          offered.add(p);
        }
      }
      return offered;
    }

    /**
     * How an ambiguous pair is offered once {@code used} is taken: {@code null} when its bounce is
     * used or no candidate is left; otherwise the pair over its remaining candidates
     * ({@link BankBounceDetector#ofCandidates}, so exactly one left makes it a plain review pair on
     * that candidate). It marks its bounce used, and its original when it became a plain pair; the
     * remaining candidates stay free for the other ambiguous pairs.
     *
     * @param p    an ambiguous pair
     * @param used the lines already used, updated
     * @return the pair as offered, or {@code null}
     */
    private static Pair offeredAmbiguous(Pair p, Set<String> used) {
      if (used.contains(p.bounce().id())) {
        return null;
      }
      List<Candidate> left = new ArrayList<>();
      for (Candidate c : p.candidates()) {
        if (!used.contains(c.line().id())) {
          left.add(c);
        }
      }
      Pair offered = left.size() == p.candidates().size() ? p
          : BankBounceDetector.ofCandidates(p.bounce(), left, p.adjacent());
      if (offered == null) {
        return null;
      }
      used.add(offered.bounce().id());
      if (!offered.ambiguous()) {
        used.add(offered.original().id());
      }
      return offered;
    }
  }

  /**
   * Detects the pairs of the account when the flag is on.
   *
   * @param enabled      whether the flag is on for this request
   * @param account      the financial account, already resolved for the tenant
   * @param candidates   the auto-match candidate lines of the account
   *                     ({@code ReconciliationHandler.loadPendingLines}); only read when the flag
   *                     is on
   * @param dateTolDays  the account's date tolerance, in days
   * @return the plan; {@link Plan#NONE} with the flag off
   */
  static Plan plan(boolean enabled, FIN_FinancialAccount account,
      Supplier<List<FIN_BankStatementLine>> candidates, int dateTolDays) {
    if (!enabled || account == null) {
      return Plan.NONE;
    }
    List<FIN_BankStatementLine> pendingLines = candidates.get();
    Map<String, FIN_BankStatementLine> byId = new HashMap<>();
    for (FIN_BankStatementLine line : pendingLines) {
      byId.put(line.getId(), line);
    }
    return new Plan(BankBounceDetector.detectQuietly(account, pendingLines, dateTolDays), byId,
        true);
  }

  // ---------------------------------------------------------------------------
  // autoMatch
  // ---------------------------------------------------------------------------

  /**
   * Adds the offered pairs to the {@code autoMatch} answer: tags every transaction group
   * {@code kind:"transaction"}, appends one {@code bouncePair} group per offered pair and sets
   * {@code kpis.bouncePairs}. A no-op with the flag off.
   *
   * @param plan   the plan of this run
   * @param groups the transaction groups built by the auto-match loop
   * @param kpis   the answer's KPIs
   * @throws JSONException when the answer cannot be written
   */
  static void appendGroups(Plan plan, JSONArray groups, JSONObject kpis) throws JSONException {
    if (!plan.enabled()) {
      return;
    }
    Set<String> taken = new HashSet<>();
    for (int i = 0; i < groups.length(); i++) {
      JSONObject group = groups.optJSONObject(i);
      if (group == null) {
        continue;
      }
      group.put(KEY_KIND, KIND_TRANSACTION);
      JSONObject line = group.optJSONObject(KEY_STATEMENT_LINE);
      if (line != null) {
        taken.add(line.optString(KEY_ID, ""));
      }
    }
    List<Pair> offered = plan.offered(taken);
    for (Pair p : offered) {
      groups.put(groupJson(plan, p));
    }
    kpis.put(KPI_BOUNCE_PAIRS, offered.size());
  }

  /**
   * One {@code bouncePair} group.
   *
   * @param plan the plan, for the DAL lines
   * @param p    the pair
   * @return the group
   * @throws JSONException when it cannot be written
   */
  private static JSONObject groupJson(Plan plan, Pair p) throws JSONException {
    JSONObject group = new JSONObject();
    group.put(KEY_KIND, KIND_BOUNCE_PAIR);
    group.put(KEY_GROUP_KEY, p.groupKey());
    group.put("origin", "bounce");
    group.put(KEY_STATEMENT_LINE,
        p.original() != null ? lineJson(plan, p.original().id()) : JSONObject.NULL);
    group.put(KEY_COUNTERPART_LINE, lineJson(plan, p.bounce().id()));
    group.put(KEY_CANDIDATES, candidatesJson(plan, p));
    group.put(KEY_AMBIGUOUS, p.ambiguous());
    group.put(KEY_ADJACENT, p.adjacent());
    putSignals(group, p);
    group.put("operations", new JSONArray());
    group.put("isNew", false);
    group.put("difference", BigDecimal.ZERO);
    return group;
  }

  // ---------------------------------------------------------------------------
  // pendingLines
  // ---------------------------------------------------------------------------

  /**
   * The panel's view of a plan, or {@code null} with the flag off (the panel then runs exactly as
   * before).
   *
   * @param plan the plan of this request
   * @return the view, or {@code null}
   */
  static PanelView panelView(Plan plan) {
    return plan.enabled() ? new PanelView(plan) : null;
  }

  /**
   * The panel's view of a plan: which rows {@code summarizePendingLines} classifies
   * {@code bounce} up front, and the post-classification step that offers the review pairs and
   * annotates the rows.
   */
  static final class PanelView {

    private final Plan plan;
    private final Set<String> reserved;

    /**
     * The panel view of a plan; the reserved lines are computed once.
     *
     * @param plan the plan of this request
     */
    PanelView(Plan plan) {
      this.plan = plan;
      this.reserved = plan.reservedLineIds();
    }

    /**
     * Whether the row belongs to a high pair and is classified {@code bounce} without running the
     * transaction classification (so it consumes no movement, as in the auto-match loop).
     *
     * @param lineId the row id
     * @return {@code true} for a reserved line
     */
    boolean reserved(String lineId) {
      return reserved.contains(lineId);
    }

    /**
     * After classification: a line classified anything but {@code pending} counts as taken by a
     * transaction group, exactly as in the auto-match loop; the review pairs left untouched become
     * {@code bounce} too. Every row of an offered pair gets {@code bouncePair}, and
     * {@code counts.bounce} is set.
     *
     * <p>Only the rows of this answer are seen: under a date or text filter a review pair whose
     * other line is outside the filter is judged on its visible lines alone.
     *
     * @param lines  the classified rows
     * @param counts the per-state counts, updated
     * @throws JSONException when a row cannot be written
     */
    void finish(JSONArray lines, Map<String, Integer> counts) throws JSONException {
      Map<String, JSONObject> rowsById = new HashMap<>();
      Set<String> taken = new HashSet<>();
      for (int i = 0; i < lines.length(); i++) {
        JSONObject row = lines.optJSONObject(i);
        if (row == null) {
          continue;
        }
        String id = row.optString(KEY_ID, "");
        rowsById.put(id, row);
        String state = row.optString(KEY_STATE, "");
        if (!reserved(id) && !AutoMatchSupport.STATE_PENDING.equals(state)
            && !STATE_BOUNCE.equals(state)) {
          taken.add(id);
        }
      }
      for (Pair p : plan.offered(taken)) {
        for (Map.Entry<String, JSONObject> marked : markers(plan, p).entrySet()) {
          JSONObject row = rowsById.get(marked.getKey());
          if (row == null || row.has(KEY_BOUNCE_PAIR)) {
            continue;
          }
          String previous = row.optString(KEY_STATE, "");
          if (!STATE_BOUNCE.equals(previous)) {
            counts.put(previous, Math.max(0, counts.getOrDefault(previous, 0) - 1));
            row.put(KEY_STATE, STATE_BOUNCE);
          }
          row.put(KEY_BOUNCE_PAIR, marked.getValue());
        }
      }
      // Counted from the rows rather than incrementally, so the count always matches the states.
      counts.put(STATE_BOUNCE, countBounceRows(lines));
    }

    /**
     * How many rows end in the {@code bounce} state.
     *
     * @param lines the rows
     * @return the count
     */
    private static int countBounceRows(JSONArray lines) {
      int n = 0;
      for (int i = 0; i < lines.length(); i++) {
        JSONObject row = lines.optJSONObject(i);
        if (row != null && STATE_BOUNCE.equals(row.optString(KEY_STATE, ""))) {
          n++;
        }
      }
      return n;
    }
  }

  /**
   * The {@code bouncePair} annotation of every line of a pair, by line id: the original and each
   * candidate point at the bounce line; the bounce line points at its original, or lists the
   * candidates when the pair is ambiguous.
   *
   * @param plan the plan, for the DAL lines
   * @param p    the pair
   * @return the annotation by line id
   * @throws JSONException when it cannot be written
   */
  private static Map<String, JSONObject> markers(Plan plan, Pair p) throws JSONException {
    Map<String, JSONObject> out = new HashMap<>();
    JSONObject bounce = marker(p, ROLE_BOUNCE);
    bounce.put(KEY_COUNTERPART_LINE,
        p.original() != null ? lineJson(plan, p.original().id()) : JSONObject.NULL);
    bounce.put(KEY_CANDIDATES, candidatesJson(plan, p));
    out.put(p.bounce().id(), bounce);
    if (p.original() != null) {
      JSONObject original = marker(p, ROLE_ORIGINAL);
      original.put(KEY_COUNTERPART_LINE, lineJson(plan, p.bounce().id()));
      out.put(p.original().id(), original);
    }
    for (Candidate c : p.candidates()) {
      JSONObject candidate = marker(p, ROLE_CANDIDATE);
      candidate.put(KEY_COUNTERPART_LINE, lineJson(plan, p.bounce().id()));
      candidate.put(KEY_SIGNALS, new JSONArray(c.signals()));
      candidate.put(KEY_SIGNAL, nullable(BankBounceDetector.primarySignalOf(c.signals())));
      out.put(c.line().id(), candidate);
    }
    return out;
  }

  /**
   * The fields every annotation of a pair shares.
   *
   * @param p    the pair
   * @param role the role of the annotated line
   * @return the annotation
   * @throws JSONException when it cannot be written
   */
  private static JSONObject marker(Pair p, String role) throws JSONException {
    JSONObject json = new JSONObject();
    json.put(KEY_GROUP_KEY, p.groupKey());
    json.put("role", role);
    json.put(KEY_AMBIGUOUS, p.ambiguous());
    json.put(KEY_ADJACENT, p.adjacent());
    putSignals(json, p);
    return json;
  }

  // ---------------------------------------------------------------------------
  // JSON helpers
  // ---------------------------------------------------------------------------

  /**
   * Puts {@code confidence}, {@code signals} and {@code signal} of the pair.
   *
   * @param json the target
   * @param p    the pair
   * @throws JSONException when it cannot be written
   */
  private static void putSignals(JSONObject json, Pair p) throws JSONException {
    json.put(KEY_CONFIDENCE, p.confidence());
    json.put(KEY_SIGNALS, new JSONArray(p.signals()));
    json.put(KEY_SIGNAL, nullable(p.primarySignal()));
  }

  /**
   * The candidate originals of an ambiguous pair, each {@code {line, signals, signal}}.
   *
   * @param plan the plan, for the DAL lines
   * @param p    the pair
   * @return the candidates, empty when the pair is not ambiguous
   * @throws JSONException when it cannot be written
   */
  private static JSONArray candidatesJson(Plan plan, Pair p) throws JSONException {
    JSONArray out = new JSONArray();
    for (Candidate c : p.candidates()) {
      JSONObject json = new JSONObject();
      json.put("line", lineJson(plan, c.line().id()));
      json.put(KEY_SIGNALS, new JSONArray(c.signals()));
      json.put(KEY_SIGNAL, nullable(BankBounceDetector.primarySignalOf(c.signals())));
      out.put(json);
    }
    return out;
  }

  /**
   * The line as the auto-match groups show it ({@link AutoMatchSupport#lineToJson}) plus the
   * counterparty the bank printed, which the {@code counterparty} signal compares.
   *
   * @param plan   the plan, for the DAL lines
   * @param lineId the line id
   * @return the line JSON
   * @throws JSONException when it cannot be written
   */
  private static JSONObject lineJson(Plan plan, String lineId) throws JSONException {
    FIN_BankStatementLine line = plan.linesById.get(lineId);
    if (line == null) {
      JSONObject json = new JSONObject();
      json.put(KEY_ID, lineId);
      return json;
    }
    JSONObject json = AutoMatchSupport.lineToJson(line);
    json.put("partnerName", StringUtils.trimToEmpty(line.getBpartnername()));
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
}
