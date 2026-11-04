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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.BankBounceDetector.AccountData;
import com.etendoerp.go.schemaforge.BankBounceDetector.Candidate;
import com.etendoerp.go.schemaforge.BankBounceDetector.Pair;
import com.etendoerp.go.schemaforge.BankBounceDetector.StatementLine;
import com.etendoerp.go.schemaforge.BankBouncePairService.Detection;
import com.etendoerp.go.schemaforge.BankBouncePairService.Validated;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * ETP-5581 — the bounce-pair KPI events (issue #15): {@code backend_bank_bounce_pair_decided},
 * {@code backend_bank_bounce_pair_restored} and {@code backend_bank_bounce_suggestions_applied}.
 *
 * <p><b>Flag-owned.</b> Only reached from code that already runs with
 * {@code bank-bounce-suggestions} on (the action evaluated {@link BankBounceSuggestionsFlag} once
 * and refused, or skipped, everything here when it was off), so every event says
 * {@code flag_enabled: true}.</p>
 *
 * <p><b>What a decision carries</b> is what detection PROPOSED for the pair, read from the
 * action's own {@link Detection} (the same detection {@code autoMatch} and {@code pendingLines}
 * run): its primary signal ({@code pis_link > return_text > counterparty}), how many signals it
 * met, its confidence, whether it was ambiguous or chosen by statement adjacency, and whether it
 * came preselected ({@code high}). A pair the user discarded from among the candidates of an
 * ambiguous one carries that candidate's signals and {@code chosen_among_alternatives: true}. A
 * pair discarded that detection did not propose at all (sent through the API or the MCP) carries
 * the signals of its two lines, {@code review} and not preselected, so every discard that can be
 * restored has its decision event.</p>
 *
 * <p><b>Legal rule.</b> No record id, amount, counterparty or statement text ever leaves this
 * class: the only identifier is the tenant as {@code account_id} ({@code AD_Client_ID} of the
 * account), the name the backend telemetry uses today ({@code ContactTypeTelemetry}).
 * {@code environment} and {@code $insert_id} are added to every backend event by the Mixpanel
 * sink.</p>
 *
 * <p><b>Best effort, after the write.</b> Building an event never throws into the action, and
 * sending happens on one bounded background thread, so neither a slow Mixpanel nor a telemetry
 * failure can fail, slow or roll back a discard. Successful decisions are handed to
 * {@link NeoBatchScope#runAfterCommit} (dropped if a batch rolls back); a {@code failed} decision is
 * about a write that did not happen and is sent at once.</p>
 */
final class BankBounceKpiTelemetry {

  private static final Logger log = LogManager.getLogger(BankBounceKpiTelemetry.class);

  /** {@code applySuggestions} body key: the SPA's decisions on the pairs it showed but did not apply. */
  static final String KEY_BOUNCE_DECISIONS = "bounceDecisions";
  static final String KEY_GROUP_KEY = "groupKey";
  static final String KEY_DECISION = "decision";

  static final String DECISION_DISCARDED = "discarded";
  static final String DECISION_UNCHECKED = "unchecked";
  static final String DECISION_SENT_TO_PANEL = "sent_to_panel";
  static final String DECISION_FAILED = "failed";

  static final String SURFACE_MODAL = "modal";
  static final String SURFACE_PANEL = "panel";

  static final String CHANNEL_UI = "ui";
  static final String CHANNEL_MCP = "mcp";

  static final String PROP_ACCOUNT_ID = "account_id";
  static final String PROP_CHANNEL = "channel";
  static final String PROP_FLAG_ENABLED = "flag_enabled";
  static final String PROP_DETECTION_SIGNAL = "detection_signal";
  static final String PROP_SIGNALS_COUNT = "signals_count";
  static final String PROP_CONFIDENCE = "confidence";
  static final String PROP_AMBIGUOUS = "ambiguous";
  static final String PROP_ADJACENT = "adjacent";
  static final String PROP_PRESELECTED = "preselected";
  static final String PROP_DECISION = "decision";
  static final String PROP_SURFACE = "surface";
  static final String PROP_CHOSEN = "chosen_among_alternatives";
  static final String PROP_DAYS_SINCE_DECIDED = "days_since_decided";
  static final String PROP_PAIRS_SHOWN = "pairs_shown";
  static final String PROP_PAIRS_DISCARDED = "pairs_discarded";
  static final String PROP_PAIRS_UNCHECKED = "pairs_unchecked";
  static final String PROP_PAIRS_SENT_TO_PANEL = "pairs_sent_to_panel";
  static final String PROP_PRESELECTION_CHANGED = "preselection_changed";

  private static final int QUEUE_CAPACITY = 1000;
  private static final ThreadPoolExecutor SENDER = buildSender();
  private static final AtomicReference<NeoTelemetryService> TELEMETRY_SERVICE =
      new AtomicReference<>();

  /** Utility class: static members only, never instantiated. */
  private BankBounceKpiTelemetry() {
  }

  // ---------------------------------------------------------------------------
  // What detection proposed
  // ---------------------------------------------------------------------------

  /**
   * What detection proposed for a decided pair: the shape the decision event carries.
   *
   * @param signal         primary signal, or {@code null} when the pair has none
   * @param signalsCount   how many signals the pair met (0 to 3)
   * @param confidence     {@code high} or {@code review}, as proposed
   * @param ambiguous      whether detection left the original for the user to choose
   * @param adjacent       whether statement adjacency chose the original or the candidates
   * @param preselected    whether the pair came checked (proposed {@code high})
   * @param chosen         whether the user picked one candidate of an ambiguous pair
   */
  record Proposal(String signal, int signalsCount, String confidence, boolean ambiguous,
      boolean adjacent, boolean preselected, boolean chosen) {

    /**
     * The pair exactly as detection proposed it (its preferred candidate's signals when
     * ambiguous).
     *
     * @param pair the detected pair
     * @return the proposal
     */
    static Proposal of(Pair pair) {
      return new Proposal(pair.primarySignal(), pair.signals().size(), pair.confidence(),
          pair.ambiguous(), pair.adjacent(), pair.high(), false);
    }

    /**
     * One candidate of an ambiguous pair, chosen by the user.
     *
     * @param pair      the ambiguous pair
     * @param candidate the chosen candidate
     * @return the proposal, with the candidate's signals
     */
    static Proposal ofCandidate(Pair pair, Candidate candidate) {
      return new Proposal(BankBounceDetector.primarySignalOf(candidate.signals()),
          candidate.signals().size(), pair.confidence(), true, pair.adjacent(), pair.high(), true);
    }

    /**
     * A pair detection did not propose, discarded anyway: the signals of its two lines,
     * {@code review}, not preselected.
     *
     * @param signals the signals of the two lines
     * @return the proposal
     */
    static Proposal notProposed(List<String> signals) {
      List<String> safe = signals != null ? signals : Collections.emptyList();
      return new Proposal(BankBounceDetector.primarySignalOf(safe), safe.size(),
          BankBounceDetector.CONFIDENCE_REVIEW, false, false, false, false);
    }
  }

  /**
   * A detected pair and what it means for one decision.
   *
   * @param pair     the detected pair
   * @param proposal the proposal the decision event carries
   */
  record Match(Pair pair, Proposal proposal) {
  }

  /**
   * The detected pair a decision is about. With two lines: the pair whose bounce is one of them and
   * whose original, or one of whose candidates, is the other, in either order. With one line (a
   * panel dismissal before an original is chosen): the first pair that touches it in any role.
   *
   * @param detected the pairs detected for the action, may be {@code null}
   * @param lineId   one line of the pair
   * @param otherId  the other line, or {@code null}
   * @return the match, or {@code null} when detection did not propose such a pair
   */
  static Match find(List<Pair> detected, String lineId, String otherId) {
    if (detected == null || StringUtils.isBlank(lineId)) {
      return null;
    }
    for (Pair p : detected) {
      Match match = otherId == null ? touching(p, lineId) : between(p, lineId, otherId);
      if (match != null) {
        return match;
      }
    }
    return null;
  }

  /**
   * The match when {@code pair} touches {@code lineId} in any role.
   *
   * @param pair   a detected pair
   * @param lineId the line
   * @return the match, or {@code null}
   */
  private static Match touching(Pair pair, String lineId) {
    return pair.lineIds().contains(lineId) ? new Match(pair, Proposal.of(pair)) : null;
  }

  /**
   * The match when {@code pair} is the pair of the two lines, in either order.
   *
   * @param pair    a detected pair
   * @param lineId  one line
   * @param otherId the other line
   * @return the match, or {@code null}
   */
  private static Match between(Pair pair, String lineId, String otherId) {
    String bounce = pair.bounce().id();
    String other;
    if (bounce.equals(lineId)) {
      other = otherId;
    } else if (bounce.equals(otherId)) {
      other = lineId;
    } else {
      return null;
    }
    if (pair.original() != null && pair.original().id().equals(other)) {
      return new Match(pair, Proposal.of(pair));
    }
    for (Candidate c : pair.candidates()) {
      if (c.line().id().equals(other)) {
        return new Match(pair, Proposal.ofCandidate(pair, c));
      }
    }
    return null;
  }

  /**
   * The detected pair with this {@code groupKey} (the key {@code autoMatch} gave it).
   *
   * @param detected the pairs detected for the action, may be {@code null}
   * @param groupKey the key
   * @return the pair, or {@code null}
   */
  static Pair byGroupKey(List<Pair> detected, String groupKey) {
    if (detected == null || StringUtils.isBlank(groupKey)) {
      return null;
    }
    for (Pair p : detected) {
      if (groupKey.equals(p.groupKey())) {
        return p;
      }
    }
    return null;
  }

  /**
   * What detection proposed for a pair that was just discarded: the detected pair, or the
   * signals of its two lines when detection did not propose it.
   *
   * @param detection the action's detection, already read by the validation
   * @param pair      the validated pair
   * @return the match ({@code pair()} is {@code null} for a pair detection did not propose)
   * @throws Exception when the detection cannot be read
   */
  static Match discardedMatch(Detection detection, Validated pair) throws Exception {
    String originalId = pair.original().getId();
    String bounceId = pair.bounce().getId();
    Match match = find(detection.pairs(), originalId, bounceId);
    if (match != null) {
      return match;
    }
    AccountData data = detection.data();
    StatementLine original = data.find(originalId);
    StatementLine bounce = data.find(bounceId);
    List<String> signals = original != null && bounce != null
        ? BankBounceDetector.signals(data, original, bounce)
        : Collections.emptyList();
    return new Match(null, Proposal.notProposed(signals));
  }

  // ---------------------------------------------------------------------------
  // Event properties (pure)
  // ---------------------------------------------------------------------------

  /**
   * The properties of {@code backend_bank_bounce_pair_decided}.
   *
   * @param proposal  what detection proposed, or {@code null} when unknown (a {@code failed}
   *                  decision on a pair the action's detection no longer sees)
   * @param decision  {@code discarded}, {@code unchecked}, {@code sent_to_panel} or {@code failed}
   * @param surface   {@code modal} or {@code panel}
   * @param accountId the tenant, or {@code null}
   * @param channel   {@code ui} or {@code mcp}
   * @return the properties, in a stable order
   */
  static Map<String, Object> decidedProperties(Proposal proposal, String decision,
      String surface, String accountId, String channel) {
    Map<String, Object> properties = base(accountId, channel);
    if (proposal != null) {
      properties.put(PROP_DETECTION_SIGNAL, proposal.signal());
      properties.put(PROP_SIGNALS_COUNT, proposal.signalsCount());
      properties.put(PROP_CONFIDENCE, proposal.confidence());
      properties.put(PROP_AMBIGUOUS, proposal.ambiguous());
      properties.put(PROP_ADJACENT, proposal.adjacent());
      properties.put(PROP_PRESELECTED, proposal.preselected());
      properties.put(PROP_CHOSEN, proposal.chosen());
    }
    properties.put(PROP_DECISION, decision);
    properties.put(PROP_SURFACE, surface);
    return properties;
  }

  /**
   * The properties of {@code backend_bank_bounce_pair_restored}.
   *
   * {@code preselected} is {@code true} exactly when the stored confidence is {@code high}: the
   * stored confidence is {@code high} only for a pair detection proposed exactly as high, i.e. a
   * pair that came preselected, so the KPI can subtract restorations from the preselected
   * discards.
   *
   * @param signal            the stored {@code EM_ETGO_Bounce_Signal}, or {@code null}
   * @param confidence        the stored {@code EM_ETGO_Bounce_Confidence}, or {@code null}
   * @param daysSinceDecided  UTC calendar days since {@code EM_ETGO_Bounce_Discarded}, or
   *                          {@code null}
   * @param accountId         the tenant, or {@code null}
   * @param channel           {@code ui} or {@code mcp}
   * @return the properties, in a stable order
   */
  static Map<String, Object> restoredProperties(String signal, String confidence,
      Long daysSinceDecided, String accountId, String channel) {
    Map<String, Object> properties = base(accountId, channel);
    properties.put(PROP_DETECTION_SIGNAL, signal);
    properties.put(PROP_CONFIDENCE, confidence);
    properties.put(PROP_PRESELECTED, BankBounceDetector.CONFIDENCE_HIGH.equals(confidence));
    properties.put(PROP_DAYS_SINCE_DECIDED, daysSinceDecided);
    return properties;
  }

  /**
   * UTC calendar days between the discard and {@code now}, never negative: the KPI collector
   * subtracts them from the restore's UTC day to find the decision's UTC day, so a discard at
   * 23:59 UTC restored at 00:01 UTC the next day is 1, not 0.
   *
   * @param discardedAt when the pair was discarded, or {@code null}
   * @param now         the restore instant
   * @return the days, or {@code null} when the discard time is unknown
   */
  static Long daysBetween(Date discardedAt, Instant now) {
    if (discardedAt == null || now == null) {
      return null;
    }
    LocalDate decided = LocalDate.ofInstant(discardedAt.toInstant(), ZoneOffset.UTC);
    LocalDate restored = LocalDate.ofInstant(now, ZoneOffset.UTC);
    return Math.max(0L, ChronoUnit.DAYS.between(decided, restored));
  }

  /**
   * The properties every event of this class shares: the tenant, the channel and the flag.
   *
   * @param accountId the tenant, or {@code null} (then omitted)
   * @param channel   {@code ui} or {@code mcp}
   * @return a new mutable map
   */
  private static Map<String, Object> base(String accountId, String channel) {
    Map<String, Object> properties = new LinkedHashMap<>();
    if (accountId != null) {
      properties.put(PROP_ACCOUNT_ID, accountId);
    }
    properties.put(PROP_CHANNEL, channel);
    properties.put(PROP_FLAG_ENABLED, true);
    return properties;
  }

  /**
   * The channel of the running request, folded to the two values the KPI segments by:
   * {@code mcp} for an MCP tool call or MCP batch, {@code ui} for everything else (the server
   * cannot tell the SPA from another REST client, see {@link NeoWriteChannel}). Read on the
   * request thread.
   *
   * @return {@code ui} or {@code mcp}
   */
  static String channel() {
    String current = NeoWriteChannel.current();
    return current != null && current.startsWith(NeoWriteChannel.MCP) ? CHANNEL_MCP : CHANNEL_UI;
  }

  /**
   * The tenant of the account: its {@code AD_Client_ID}, else the request's client.
   *
   * @param account the financial account, or {@code null}
   * @return the client id, or {@code null}
   */
  static String accountId(FIN_FinancialAccount account) {
    if (account != null && account.getClient() != null) {
      return account.getClient().getId();
    }
    OBContext context = OBContext.getOBContext();
    return context != null && context.getCurrentClient() != null
        ? context.getCurrentClient().getId()
        : null;
  }

  // ---------------------------------------------------------------------------
  // Panel: discardPair, dismissPair
  // ---------------------------------------------------------------------------

  /**
   * A pair was discarded from the panel ({@code discardPair}, UI or MCP): one {@code discarded}
   * decision, surface {@code panel}, after commit. Never throws.
   *
   * @param detection the action's detection, read by the validation
   * @param account   the account of the action
   * @param pair      the discarded pair
   */
  static void panelDiscarded(Detection detection, FIN_FinancialAccount account, Validated pair) {
    try {
      Match match = discardedMatch(detection, pair);
      schedule(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED,
          decidedProperties(match.proposal(), DECISION_DISCARDED, SURFACE_PANEL,
              accountId(account), channel()), accountId(account));
    } catch (Exception e) {
      log.debug("Bounce KPI: could not build the panel discard event: {}", e.getMessage());
    }
  }

  /**
   * A panel discard was refused. Every business refusal (409: a line already reconciled,
   * discarded or taken, a payment...) is a {@code failed} decision, with the proposal properties
   * when the action's detection sees the pair and without them otherwise — the same rule as the
   * modal ({@link ModalDecisions#accept}). Malformed requests (400 / 404) emit nothing. Sent at
   * once: the refusal rolls the action back. Never throws.
   *
   * @param detection     the action's detection
   * @param account       the account of the action
   * @param lineId        the request's {@code statementLineId}
   * @param counterpartId the request's {@code counterpartLineId}
   * @param refusal       the refusal answered
   */
  static void panelFailed(Detection detection, FIN_FinancialAccount account, String lineId,
      String counterpartId, NeoResponse refusal) {
    if (!isBusinessRefusal(refusal)) {
      return;
    }
    try {
      Match match = find(detection.pairs(), lineId, counterpartId);
      sendNow(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED,
          decidedProperties(match != null ? match.proposal() : null, DECISION_FAILED, SURFACE_PANEL,
              accountId(account), channel()), accountId(account));
    } catch (Exception e) {
      log.debug("Bounce KPI: could not build the panel failure event: {}", e.getMessage());
    }
  }

  /**
   * "No es un rebote" in the panel ({@code dismissPair}): one {@code unchecked} decision, surface
   * {@code panel}, for the detected pair the lines name. Nothing is written, so it is sent at
   * once. A pair detection does not propose (anymore) emits nothing. Never throws.
   *
   * @param detection     the action's detection
   * @param account       the account of the action
   * @param lineId        the line selected in the panel (bounce, original or candidate)
   * @param counterpartId the other line as the card shows it, or {@code null}
   */
  static void panelDismissed(Detection detection, FIN_FinancialAccount account, String lineId,
      String counterpartId) {
    try {
      Match match = find(detection.pairs(), lineId, counterpartId);
      if (match == null) {
        return;
      }
      sendNow(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED,
          decidedProperties(match.proposal(), DECISION_UNCHECKED, SURFACE_PANEL,
              accountId(account), channel()), accountId(account));
    } catch (Exception e) {
      log.debug("Bounce KPI: could not build the dismissal event: {}", e.getMessage());
    }
  }

  /**
   * Whether a refusal is a business refusal of the pair (409), not a malformed request.
   *
   * @param refusal the refusal, or {@code null}
   * @return {@code true} for a 409
   */
  static boolean isBusinessRefusal(NeoResponse refusal) {
    return refusal != null && refusal.getHttpStatus() == HttpServletResponse.SC_CONFLICT;
  }

  // ---------------------------------------------------------------------------
  // restorePair
  // ---------------------------------------------------------------------------

  /**
   * The link columns of a line about to be restored, read before the restore clears them.
   *
   * @param signal      stored signal, or {@code null}
   * @param confidence  stored confidence, or {@code null}
   * @param discardedAt stored discard time, or {@code null}
   */
  record RestoredPair(String signal, String confidence, Date discardedAt) {

    /**
     * Reads the link columns of {@code line}. Never throws: a column that cannot be read is
     * {@code null}.
     *
     * @param line the line being restored with its counterpart
     * @return the snapshot
     */
    static RestoredPair of(FIN_BankStatementLine line) {
      try {
        Object signal = StatementLineDiscard.get(line, BankBouncePairService.COL_SIGNAL);
        Object confidence = StatementLineDiscard.get(line, BankBouncePairService.COL_CONFIDENCE);
        Object at = StatementLineDiscard.get(line, BankBouncePairService.COL_DISCARDED_AT);
        return new RestoredPair(signal instanceof String ? (String) signal : null,
            confidence instanceof String ? (String) confidence : null,
            at instanceof Date ? (Date) at : null);
      } catch (RuntimeException e) {
        log.debug("Bounce KPI: could not read the link of a restored line: {}", e.getMessage());
        return new RestoredPair(null, null, null);
      }
    }
  }

  /**
   * A linked pair was restored ({@code restorePair}): one {@code backend_bank_bounce_pair_restored}
   * after commit. Never throws.
   *
   * @param account  the account of the action
   * @param restored the link the pair had
   */
  static void pairRestored(FIN_FinancialAccount account, RestoredPair restored) {
    try {
      schedule(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_RESTORED,
          restoredProperties(restored.signal(), restored.confidence(),
              daysBetween(restored.discardedAt(), Instant.now()), accountId(account), channel()),
          accountId(account));
    } catch (RuntimeException e) {
      log.debug("Bounce KPI: could not build the restore event: {}", e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // applySuggestions: the modal
  // ---------------------------------------------------------------------------

  /**
   * The bounce-pair decisions of one {@code applySuggestions} call: every pair applied
   * ({@code discarded}, or {@code failed} on a 409) as it is processed, then the pairs not applied.
   * From the SPA ({@code channel ui}) those are the ones it reports in {@code bounceDecisions}
   * ({@code unchecked} / {@code sent_to_panel}), resolved by {@code groupKey} through the call's
   * detection; an unknown key, an unknown decision or a pair already applied in the call is
   * ignored, never an error. An agent ({@code channel mcp}) cannot send {@code bounceDecisions}:
   * when it applied at least one bounce-pair group, every preselected pair of the call's
   * detection it left out — not applied, and with no line taken by a group of the call — is an
   * {@code unchecked} decision; when it applied none, it did not look at the bounces and nothing
   * is sent. {@link #finish} sends one decision per pair plus the summary event. {@link #NONE}
   * (flag off) does nothing.
   */
  static final class ModalDecisions implements BankBouncePairService.PairOutcomes {

    /** The flag is off: nothing is recorded or sent. */
    static final ModalDecisions NONE = new ModalDecisions(null, null, null);

    private final Detection detection;
    private final FIN_FinancialAccount account;
    private final JSONArray reported;
    private final String channel;
    private final List<Map<String, Object>> decided = new ArrayList<>();
    private final Set<String> decidedKeys = new HashSet<>();
    private int appliedGroups;
    private int discarded;
    private int unchecked;
    private int sentToPanel;
    private boolean preselectionChanged;

    /**
     * The decisions of one call.
     *
     * @param detection the call's detection, {@code null} with the flag off
     * @param account   the account of the call
     * @param reported  the body's {@code bounceDecisions}, or {@code null}
     */
    private ModalDecisions(Detection detection, FIN_FinancialAccount account, JSONArray reported) {
      this.detection = detection;
      this.account = account;
      this.reported = reported;
      this.channel = detection != null ? channel() : null;
    }

    /**
     * The decisions of an {@code applySuggestions} call.
     *
     * @param detection the call's detection, shared with the bounce-pair groups; {@code null} when
     *                  {@code bank-bounce-suggestions} is off
     * @param account   the account of the call
     * @param body      the request body
     * @return the recorder, or {@link #NONE} with the flag off
     */
    static ModalDecisions of(Detection detection, FIN_FinancialAccount account, JSONObject body) {
      if (detection == null) {
        return NONE;
      }
      return new ModalDecisions(detection, account,
          body != null ? body.optJSONArray(KEY_BOUNCE_DECISIONS) : null);
    }

    /**
     * Reads the call's detection now when it will be needed to resolve the pairs not applied —
     * the SPA reported decisions, or an agent applied at least one bounce-pair group — so it sees
     * the lines as they were offered: called after the bounce groups and BEFORE the transaction
     * groups are matched (they would take their lines out of the detection). A no-op otherwise.
     * Never throws.
     */
    void prime() {
      boolean reportedAny = reported != null && reported.length() > 0;
      if (detection == null || !(reportedAny || agentReviewed())) {
        return;
      }
      try {
        detection.pairs();
      } catch (Exception e) {
        log.debug("Bounce KPI: could not read the detection: {}", e.getMessage());
      }
    }

    /**
     * Records the outcome of one applied bounce-pair group. Never throws.
     *
     * @param lineId        the group's {@code statementLineId}
     * @param counterpartId the group's {@code counterpartLineId}
     * @param outcome       the discarded pair, or the refusal
     */
    @Override
    public void accept(String lineId, String counterpartId, Validated outcome) {
      if (detection == null || outcome == null) {
        return;
      }
      appliedGroups++;
      try {
        if (outcome.error() == null) {
          addDecision(discardedMatch(detection, outcome), DECISION_DISCARDED);
        } else if (isBusinessRefusal(outcome.error())) {
          Match match = find(detection.pairs(), lineId, counterpartId);
          addDecision(match != null ? match : new Match(null, null), DECISION_FAILED);
        }
      } catch (Exception e) {
        log.debug("Bounce KPI: could not record an applied pair: {}", e.getMessage());
      }
    }

    /**
     * Whether an agent submitted at least one bounce-pair group: only then are the preselected
     * pairs it left out its {@code unchecked} decisions.
     *
     * @return {@code true} for an MCP call with at least one bounce-pair group
     */
    private boolean agentReviewed() {
      return CHANNEL_MCP.equals(channel) && appliedGroups > 0;
    }

    /**
     * Resolves the pairs not applied (the SPA's reported decisions, or an agent's left-out
     * preselected pairs), then sends one decision event per pair and, when any pair was decided,
     * the summary. Called once the call succeeded. Never throws.
     *
     * @param takenLineIds every line a group of the call took (bounce pairs and transaction
     *                     groups), or {@code null}
     */
    void finish(Collection<String> takenLineIds) {
      if (detection == null) {
        return;
      }
      try {
        recordReported();
        recordAgentOmissions(takenLineIds);
        if (decided.isEmpty()) {
          return;
        }
        String accountId = accountId(account);
        for (Map<String, Object> properties : decided) {
          schedule(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED, properties, accountId);
        }
        schedule(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_SUGGESTIONS_APPLIED, summary(accountId),
            accountId);
      } catch (Exception e) {
        log.debug("Bounce KPI: could not send the modal events: {}", e.getMessage());
      }
    }

    /**
     * Records the SPA's {@code bounceDecisions} entries that name a detected pair not applied in
     * this call; every other entry is ignored.
     *
     * @throws Exception when the detection cannot be read
     */
    private void recordReported() throws Exception {
      if (reported == null) {
        return;
      }
      for (int i = 0; i < reported.length(); i++) {
        JSONObject entry = reported.optJSONObject(i);
        String key = entry != null ? StringUtils.trimToNull(entry.optString(KEY_GROUP_KEY)) : null;
        String decision = entry != null ? entry.optString(KEY_DECISION) : null;
        if (key == null || decidedKeys.contains(key) || !(DECISION_UNCHECKED.equals(decision)
            || DECISION_SENT_TO_PANEL.equals(decision))) {
          continue;
        }
        Pair pair = byGroupKey(detection.pairs(), key);
        if (pair != null) {
          addDecision(new Match(pair, Proposal.of(pair)), decision);
        }
      }
    }

    /**
     * An agent's {@code applySuggestions} that submitted at least one bounce-pair group: every
     * preselected (high, never ambiguous) pair of the call's detection that was not applied and
     * none of whose lines a group of the call took is an {@code unchecked} decision. A no-op for
     * the SPA and for an agent call with no bounce-pair group.
     *
     * @param takenLineIds every line a group of the call took, or {@code null}
     * @throws Exception when the detection cannot be read
     */
    private void recordAgentOmissions(Collection<String> takenLineIds) throws Exception {
      if (!agentReviewed()) {
        return;
      }
      Collection<String> taken = takenLineIds != null ? takenLineIds : Collections.emptySet();
      List<Pair> detected = detection.pairs();
      if (detected == null) {
        return;
      }
      for (Pair pair : detected) {
        if (pair.high() && !pair.ambiguous() && !decidedKeys.contains(pair.groupKey())
            && Collections.disjoint(pair.lineIds(), taken)) {
          addDecision(new Match(pair, Proposal.of(pair)), DECISION_UNCHECKED);
        }
      }
    }

    /**
     * Records one decision and updates the summary counters.
     *
     * @param match    the pair and its proposal ({@code proposal} {@code null} when unknown)
     * @param decision the decision
     */
    private void addDecision(Match match, String decision) {
      if (match.pair() != null) {
        decidedKeys.add(match.pair().groupKey());
      }
      Proposal proposal = match.proposal();
      decided.add(decidedProperties(proposal, decision, SURFACE_MODAL, accountId(account),
          channel));
      boolean applied = DECISION_DISCARDED.equals(decision) || DECISION_FAILED.equals(decision);
      if (DECISION_DISCARDED.equals(decision)) {
        discarded++;
      } else if (DECISION_UNCHECKED.equals(decision)) {
        unchecked++;
      } else if (DECISION_SENT_TO_PANEL.equals(decision)) {
        sentToPanel++;
      }
      if (proposal != null && proposal.preselected() != applied) {
        preselectionChanged = true;
      }
    }

    /**
     * The properties of {@code backend_bank_bounce_suggestions_applied}.
     *
     * @param accountId the tenant, or {@code null}
     * @return the properties
     */
    private Map<String, Object> summary(String accountId) {
      Map<String, Object> properties = base(accountId, channel);
      properties.put(PROP_PAIRS_SHOWN, decided.size());
      properties.put(PROP_PAIRS_DISCARDED, discarded);
      properties.put(PROP_PAIRS_UNCHECKED, unchecked);
      properties.put(PROP_PAIRS_SENT_TO_PANEL, sentToPanel);
      properties.put(PROP_PRESELECTION_CHANGED, preselectionChanged);
      return properties;
    }
  }

  // ---------------------------------------------------------------------------
  // Sending
  // ---------------------------------------------------------------------------

  /**
   * Sends after the request's commit ({@link NeoBatchScope#runAfterCommit}: at once outside a
   * batch, after the batch commits inside one, never when it rolls back).
   *
   * @param event      the event name
   * @param properties its properties
   * @param clientId   the tenant for the server log, or {@code null}
   */
  private static void schedule(String event, Map<String, Object> properties, String clientId) {
    NeoBatchScope.runAfterCommit(() -> sendNow(event, properties, clientId));
  }

  /**
   * Hands the event to the background sender; drops it when the queue is full. The service is
   * resolved on the calling thread, so an event always goes to the service current when it was
   * decided.
   *
   * @param event      the event name
   * @param properties its properties
   * @param clientId   the tenant for the server log, or {@code null}
   */
  private static void sendNow(String event, Map<String, Object> properties, String clientId) {
    try {
      NeoTelemetryService service = telemetry();
      SENDER.execute(() -> service.emit(event, properties, clientId));
    } catch (RejectedExecutionException e) {
      log.debug("Bounce KPI telemetry queue full; dropping {}", event);
    }
  }

  /**
   * Package-private seam for tests; pass {@code null} to restore the runtime service.
   *
   * @param service the service events are emitted through, or {@code null}
   */
  static void setTelemetryService(NeoTelemetryService service) {
    TELEMETRY_SERVICE.set(service);
  }

  /**
   * The telemetry service, built once from the runtime configuration.
   *
   * @return the service
   */
  private static NeoTelemetryService telemetry() {
    NeoTelemetryService local = TELEMETRY_SERVICE.get();
    if (local == null) {
      synchronized (BankBounceKpiTelemetry.class) {
        local = TELEMETRY_SERVICE.get();
        if (local == null) {
          local = NeoTelemetryService.runtime();
          TELEMETRY_SERVICE.set(local);
        }
      }
    }
    return local;
  }

  /**
   * One daemon thread with a bounded queue: telemetry never blocks a request.
   *
   * @return the executor
   */
  private static ThreadPoolExecutor buildSender() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
          Thread thread = new Thread(runnable, "bank-bounce-kpi-telemetry");
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }
}
