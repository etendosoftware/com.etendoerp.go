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

import static com.etendoerp.go.schemaforge.ReconciliationSupport.nullSafe;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

import com.etendoerp.psd2.bank.integration.data.PisPayment;

/**
 * Detects bounce pairs among the pending bank statement lines of an account (ETP-5581): an
 * outgoing (or incoming) line and the line that reverses it — opposite sign, EXACT amount, the
 * bounce dated on or after the original and at most {@value #WINDOW_DAYS} days later.
 *
 * <p>Trimmed from the first iteration's {@code ReturnDetectionSupport} (commit {@code 1a5140cb9}):
 * no learned text, no {@code EM_ETGO_RETURN_*} columns, no reconciled originals and a fixed window.
 * Only gated callers reach it ({@link BankBounceSuggestionsFlag}).
 *
 * <p><b>Candidate lines.</b> Only lines that are pending (unmatched, on a processed statement), not
 * excluded by any {@code BankStatementLineFilterHook} (so never a discarded line), outside any split
 * match group and carrying the PSD2 transaction id: the lines PSD2 itself would let the user
 * discard. The caller passes the auto-match candidates
 * ({@code ReconciliationHandler.loadPendingLines}), which already apply the hook; every other
 * pending line of that list still takes part in the payment attribution of
 * {@link BankBouncePaymentGuard}, because it can claim a payment.
 *
 * <p><b>Signals</b> — amount and sign alone never propose a pair:
 * <ul>
 *   <li>{@value #SIGNAL_PIS_LINK}: the original is an outflow whose description ends with the
 *       end-to-end id of a PSD2-initiated payment of the same account;</li>
 *   <li>{@value #SIGNAL_RETURN_TEXT}: the bounce carries bank bounce wording
 *       ({@link BankBounceLineText});</li>
 *   <li>{@value #SIGNAL_COUNTERPARTY}: both lines name the same counterparty.</li>
 * </ul>
 * Confidence is {@value #CONFIDENCE_HIGH} with bounce wording on the same day, or with a PIS link
 * backed by timing (within the account's date tolerance) or by wording — and only when the pair is
 * not ambiguous; anything else is {@value #CONFIDENCE_REVIEW}. An ambiguous pair (several equally
 * eligible originals) is always for review, names no original and carries its candidate originals.
 *
 * <p><b>Never a real payment.</b> A pair is not proposed when either line has a payment in the ERP
 * ({@link BankBouncePaymentGuard#blocker}): discarding it would leave that payment, and the invoice
 * it settles, without the bank movement that proves it.
 *
 * <p><b>Reads.</b> Besides the lines the caller already holds: the PSD2 payments of the account, the
 * open payment movements in the candidates' date range and the schedule-detail count of their
 * payments — three bounded OBQuery reads, none when no line is a candidate. Detection itself runs in
 * memory and never writes. No entity identity: everything here is the structure of a statement line
 * and of the account's payments.
 */
final class BankBounceDetector {

  private static final Logger log = LogManager.getLogger(BankBounceDetector.class);

  static final String SIGNAL_PIS_LINK = "pis_link";
  static final String SIGNAL_RETURN_TEXT = "return_text";
  static final String SIGNAL_COUNTERPARTY = "counterparty";

  /** Signals in priority order: the first present is the pair's primary signal. */
  static final List<String> SIGNAL_PRIORITY =
      List.of(SIGNAL_PIS_LINK, SIGNAL_RETURN_TEXT, SIGNAL_COUNTERPARTY);

  static final String CONFIDENCE_HIGH = "high";
  static final String CONFIDENCE_REVIEW = "review";

  /**
   * How many days after the original a bounce may be dated. A constant (the first iteration read it
   * from the account); it bounds every detection read.
   */
  static final int WINDOW_DAYS = 60;

  /**
   * How far back from the account's newest candidate line a candidate is still looked at, so one
   * stale pending line years old cannot drag the whole history into the reads.
   */
  static final int DETECTION_HORIZON_DAYS = 365;

  /** Status of a movement whose payment was cleared at the bank: not an open movement. */
  private static final String STATUS_CLEARED = "RPPC";

  /** Utility class: static members only, never instantiated. */
  private BankBounceDetector() {
  }

  // ---------------------------------------------------------------------------
  // Model
  // ---------------------------------------------------------------------------

  /**
   * One pending statement line as the detection reads it.
   *
   * @param id          the line id
   * @param date        its calendar day
   * @param lineNo      its line number, for a stable order
   * @param description its description
   * @param partnerName the counterparty name the bank printed
   * @param amount      {@code cramount - dramount}
   * @param candidate   whether it may be part of a pair (PSD2 line outside any split group)
   */
  record StatementLine(String id, LocalDate date, long lineNo, String description,
      String partnerName, BigDecimal amount, boolean candidate) {

    /**
     * Whether the line takes money out of the account.
     *
     * @return {@code true} for a negative amount
     */
    boolean outflow() {
      return amount.signum() < 0;
    }
  }

  /**
   * An unreconciled, processed movement of the account that carries a processed payment.
   *
   * @param id          the movement id
   * @param paymentId   its payment
   * @param date        its statement date
   * @param amount      {@code deposit - payment}
   * @param partnerName the payment's business partner name
   * @param detailCount how many schedule details (invoice installments) the payment settles
   */
  record OpenTransaction(String id, String paymentId, LocalDate date, BigDecimal amount,
      String partnerName, int detailCount) {
  }

  /**
   * A candidate original of an ambiguous pair, with the signals it shares with the bounce.
   *
   * @param line    the candidate original
   * @param signals its signals with the bounce
   */
  record Candidate(StatementLine line, List<String> signals) {
  }

  /**
   * A detected pair.
   *
   * @param original   the original line, {@code null} when the pair is ambiguous
   * @param bounce     the line that reverses it
   * @param candidates the candidate originals of an ambiguous pair, in preference order; empty
   *                   otherwise
   * @param signals    the signals of the pair (of the preferred candidate when ambiguous)
   * @param confidence {@value #CONFIDENCE_HIGH} or {@value #CONFIDENCE_REVIEW}
   */
  record Pair(StatementLine original, StatementLine bounce, List<Candidate> candidates,
      List<String> signals, String confidence) {

    /**
     * Whether the original is left for the user to choose.
     *
     * @return {@code true} when there is no single original
     */
    boolean ambiguous() {
      return original == null;
    }

    /**
     * Whether the pair is offered checked.
     *
     * @return {@code true} for a high-confidence pair
     */
    boolean high() {
      return CONFIDENCE_HIGH.equals(confidence);
    }

    /**
     * The first present signal by {@link #SIGNAL_PRIORITY}.
     *
     * @return the primary signal, or {@code null} when there is none
     */
    String primarySignal() {
      return primarySignalOf(signals);
    }

    /**
     * Every line the pair touches: the bounce plus the original, or plus every candidate.
     *
     * @return the line ids, bounce first
     */
    List<String> lineIds() {
      List<String> ids = new ArrayList<>();
      ids.add(bounce.id());
      if (original != null) {
        ids.add(original.id());
      }
      for (Candidate c : candidates) {
        ids.add(c.line().id());
      }
      return ids;
    }

    /**
     * Stable key of the pair in the auto-match answer.
     *
     * @return {@code bounce-<original>-<bounce>}, or {@code bounce-<bounce>} when ambiguous
     */
    String groupKey() {
      return original != null ? "bounce-" + original.id() + "-" + bounce.id()
          : "bounce-" + bounce.id();
    }
  }

  /**
   * Everything read for one account: the pending lines plus the lookups the signals and the payment
   * guard need. Memoizes per-line answers for one detection run.
   */
  static final class AccountData {
    final FIN_FinancialAccount account;
    final int dateTolDays;
    final List<StatementLine> lines = new ArrayList<>();
    final Map<String, String> pisPaymentByEndToEnd = new HashMap<>();
    final List<OpenTransaction> openTransactions = new ArrayList<>();
    private final Map<String, Boolean> wordingByLine = new HashMap<>();
    private final Map<String, BankBouncePaymentGuard.Attribution> attributionByLine =
        new HashMap<>();
    private Set<String> pisClaimed;

    /**
     * Empty data for one account; {@link BankBounceDetector#load} fills it.
     *
     * @param account     the financial account
     * @param dateTolDays the account's date tolerance, in days
     */
    AccountData(FIN_FinancialAccount account, int dateTolDays) {
      this.account = account;
      this.dateTolDays = Math.max(0, dateTolDays);
    }

    /**
     * The ERP payment attributed to the line, memoized; see
     * {@link BankBouncePaymentGuard#attribute}.
     *
     * @param line the line
     * @return its attribution
     */
    BankBouncePaymentGuard.Attribution attributionOf(StatementLine line) {
      return attributionByLine.computeIfAbsent(line.id(),
          id -> BankBouncePaymentGuard.attribute(this, line));
    }

    /**
     * The payments some line links to through its PSD2 end-to-end id.
     *
     * @return the payment ids, memoized
     */
    Set<String> pisClaimedPayments() {
      if (pisClaimed == null) {
        pisClaimed = new HashSet<>();
        for (StatementLine l : lines) {
          String payment = pisPaymentId(this, l);
          if (payment != null) {
            pisClaimed.add(payment);
          }
        }
      }
      return pisClaimed;
    }

    /**
     * The loaded line with that id.
     *
     * @param lineId the id
     * @return the line, or {@code null}
     */
    StatementLine find(String lineId) {
      for (StatementLine l : lines) {
        if (l.id().equals(lineId)) {
          return l;
        }
      }
      return null;
    }
  }

  /**
   * One raw assignment of the pairing, before the payment guard: the chosen original, the bounce,
   * whether its group was ambiguous and the free originals the bounce could take.
   */
  private record Assignment(StatementLine original, StatementLine bounce, boolean ambiguous,
      List<StatementLine> freeOriginals) {
  }

  // ---------------------------------------------------------------------------
  // Entry points
  // ---------------------------------------------------------------------------

  /**
   * Reads what the detection needs for {@code pendingLines} of {@code account}. Every query is
   * scoped by the account, which the caller must have resolved for the tenant.
   *
   * @param account      the financial account
   * @param pendingLines the auto-match candidate lines of the account (hook already applied)
   * @param dateTolDays  the account's date tolerance, in days
   * @return the loaded data; with no candidate line, no query runs
   */
  static AccountData load(FIN_FinancialAccount account, List<FIN_BankStatementLine> pendingLines,
      int dateTolDays) {
    AccountData data = new AccountData(account, dateTolDays);
    for (FIN_BankStatementLine line : pendingLines) {
      data.lines.add(toStatementLine(line));
    }
    LocalDate[] range = candidateRange(data);
    if (range.length == 0) {
      return data;
    }
    loadPisPayments(data, range[0].minusDays(WINDOW_DAYS));
    loadOpenTransactions(data, range[0].minusDays(data.dateTolDays),
        range[1].plusDays(data.dateTolDays + 1L));
    return data;
  }

  /**
   * Detects the pairs of the loaded data that pass the payment guard.
   *
   * @param data the loaded data
   * @return the pairs, in statement order of their bounce line
   */
  static List<Pair> detect(AccountData data) {
    List<Assignment> assignments = assign(data);
    Set<String> paired = new HashSet<>();
    for (Assignment a : assignments) {
      paired.add(a.original().id());
      paired.add(a.bounce().id());
    }
    List<Pair> pairs = new ArrayList<>();
    for (Assignment a : assignments) {
      Pair pair = guarded(data, a, paired);
      if (pair != null) {
        pairs.add(pair);
      }
    }
    return pairs;
  }

  /**
   * {@link #load} plus {@link #detect} for the read paths that only decorate their answer with the
   * pairs: a failure is logged and yields no pair, never a failed answer.
   *
   * @param account      the financial account
   * @param pendingLines the auto-match candidate lines of the account
   * @param dateTolDays  the account's date tolerance, in days
   * @return the pairs, or an empty list
   */
  static List<Pair> detectQuietly(FIN_FinancialAccount account,
      List<FIN_BankStatementLine> pendingLines, int dateTolDays) {
    try {
      return detect(load(account, pendingLines, dateTolDays));
    } catch (Exception e) {
      log.warn("Could not detect bounce pairs for account {}: {}",
          account != null ? account.getId() : null, e.getMessage());
      return Collections.emptyList();
    }
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * Maps a DAL line to the detection model.
   *
   * @param line the line
   * @return the model line
   */
  static StatementLine toStatementLine(FIN_BankStatementLine line) {
    BigDecimal amount = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    boolean candidate = StatementLineDiscard.transactionIdentifier(line) != null
        && StringUtils.isBlank(ReactivationSupport.readMatchGroupId(line));
    return new StatementLine(line.getId(), dayOf(line.getTransactionDate()),
        line.getLineNo() != null ? line.getLineNo() : 0L,
        StringUtils.trimToEmpty(line.getDescription()),
        StringUtils.trimToEmpty(line.getBpartnername()), amount, candidate);
  }

  /**
   * {@code [oldest, newest]} candidate day, the oldest bounded to {@value #DETECTION_HORIZON_DAYS}
   * days before the newest; empty when no line is a candidate.
   *
   * @param data the loaded lines
   * @return the range, or an empty array
   */
  private static LocalDate[] candidateRange(AccountData data) {
    LocalDate oldest = null;
    LocalDate newest = null;
    for (StatementLine l : data.lines) {
      if (l.candidate()) {
        oldest = oldest == null || l.date().isBefore(oldest) ? l.date() : oldest;
        newest = newest == null || l.date().isAfter(newest) ? l.date() : newest;
      }
    }
    if (oldest == null) {
      return new LocalDate[0];
    }
    LocalDate horizon = newest.minusDays(DETECTION_HORIZON_DAYS);
    return new LocalDate[] { oldest.isBefore(horizon) ? horizon : oldest, newest };
  }

  /**
   * The PSD2-initiated payments of the account by end-to-end id, those created from {@code from}
   * on (a payment is initiated before its outflow is booked).
   *
   * @param data the data to fill
   * @param from the earliest creation day read
   */
  private static void loadPisPayments(AccountData data, LocalDate from) {
    Map<String, Object> params = new HashMap<>();
    params.put("etgoAccountId", data.account.getId());
    params.put("etgoFrom", toTimestamp(from));
    OBQuery<PisPayment> query = OBDal.getInstance().createQuery(PisPayment.class,
        "as p where p." + PisPayment.PROPERTY_FINANCIALACCOUNT + ".id = :etgoAccountId"
            + " and p." + PisPayment.PROPERTY_ENDTOEND + " is not null"
            + " and p." + PisPayment.PROPERTY_PAYMENT + " is not null"
            + " and p." + PisPayment.PROPERTY_CREATIONDATE + " >= :etgoFrom");
    query.setNamedParameters(params);
    query.setSelectClause("p." + PisPayment.PROPERTY_ENDTOEND + ", p."
        + PisPayment.PROPERTY_PAYMENT + ".id");
    for (Object[] row : query.createQuery(Object[].class).list()) {
      String endToEnd = StringUtils.upperCase(StringUtils.trimToEmpty((String) row[0]));
      if (!endToEnd.isEmpty()) {
        data.pisPaymentByEndToEnd.put(endToEnd, (String) row[1]);
      }
    }
  }

  /**
   * The unreconciled, processed movements of the account that carry a processed payment: those
   * dated in {@code [from, toExclusive)}, plus those of the PSD2 payments, which are reached by
   * their link and not by their date. Then the schedule-detail count of their payments.
   *
   * @param data        the data to fill
   * @param from        the earliest statement day read
   * @param toExclusive the day after the latest statement day read
   */
  private static void loadOpenTransactions(AccountData data, LocalDate from,
      LocalDate toExclusive) {
    Map<String, Object> params = new HashMap<>();
    params.put("etgoAccountId", data.account.getId());
    params.put("etgoFrom", toTimestamp(from));
    params.put("etgoTo", toTimestamp(toExclusive));
    String byDate = "(ft." + FIN_FinaccTransaction.PROPERTY_TRANSACTIONDATE + " >= :etgoFrom"
        + " and ft." + FIN_FinaccTransaction.PROPERTY_TRANSACTIONDATE + " < :etgoTo)";
    Set<String> pisPayments = new HashSet<>(data.pisPaymentByEndToEnd.values());
    if (!pisPayments.isEmpty()) {
      params.put("etgoPisPayments", pisPayments);
      byDate = "(" + byDate + " or fp.id in (:etgoPisPayments))";
    }
    OBQuery<FIN_FinaccTransaction> query = OBDal.getInstance().createQuery(
        FIN_FinaccTransaction.class,
        "as ft join ft." + FIN_FinaccTransaction.PROPERTY_FINPAYMENT + " as fp"
            + " left join fp.businessPartner as bp"
            + " where ft." + FIN_FinaccTransaction.PROPERTY_ACCOUNT + ".id = :etgoAccountId"
            + " and ft." + FIN_FinaccTransaction.PROPERTY_RECONCILIATION + " is null"
            + " and ft." + FIN_FinaccTransaction.PROPERTY_PROCESSED + " = true"
            + " and ft." + FIN_FinaccTransaction.PROPERTY_STATUS + " <> '" + STATUS_CLEARED + "'"
            + " and fp.processed = true"
            + " and " + byDate);
    query.setNamedParameters(params);
    query.setSelectClause("ft.id, fp.id, ft." + FIN_FinaccTransaction.PROPERTY_TRANSACTIONDATE
        + ", ft." + FIN_FinaccTransaction.PROPERTY_DEPOSITAMOUNT
        + ", ft." + FIN_FinaccTransaction.PROPERTY_PAYMENTAMOUNT + ", bp.name");
    List<Object[]> rows = query.createQuery(Object[].class).list();
    Map<String, Integer> detailCounts = detailCounts(rows);
    for (Object[] row : rows) {
      String paymentId = (String) row[1];
      BigDecimal amount = nullSafe((BigDecimal) row[3]).subtract(nullSafe((BigDecimal) row[4]));
      data.openTransactions.add(new OpenTransaction((String) row[0], paymentId,
          row[2] != null ? dayOf((java.util.Date) row[2]) : null, amount,
          StringUtils.trimToEmpty((String) row[5]), detailCounts.getOrDefault(paymentId, 0)));
    }
  }

  /**
   * How many schedule details (invoice installments) each payment of {@code rows} settles.
   *
   * @param rows the open-movement rows, payment id in column 1
   * @return the count by payment id
   */
  private static Map<String, Integer> detailCounts(List<Object[]> rows) {
    Set<String> paymentIds = new HashSet<>();
    for (Object[] row : rows) {
      paymentIds.add((String) row[1]);
    }
    Map<String, Integer> counts = new HashMap<>();
    if (paymentIds.isEmpty()) {
      return counts;
    }
    OBQuery<FIN_PaymentScheduleDetail> query = OBDal.getInstance().createQuery(
        FIN_PaymentScheduleDetail.class,
        "as psd where psd." + FIN_PaymentScheduleDetail.PROPERTY_PAYMENTDETAILS
            + ".finPayment.id in (:etgoPaymentIds)");
    query.setNamedParameter("etgoPaymentIds", paymentIds);
    query.setSelectClause("psd." + FIN_PaymentScheduleDetail.PROPERTY_PAYMENTDETAILS
        + ".finPayment.id");
    for (String paymentId : query.createQuery(String.class).list()) {
      counts.merge(paymentId, 1, Integer::sum);
    }
    return counts;
  }

  // ---------------------------------------------------------------------------
  // Pairing
  // ---------------------------------------------------------------------------

  /**
   * Pairs the candidate lines. Bounces are taken in date order and assigned per group of bounces
   * sharing (date, signed amount): within a group, the originals with no ERP payment are paired
   * first, so when there are more originals than bounces the one left free is the one the ERP can
   * still reconcile normally (the attempt that went through).
   *
   * @param data the loaded data
   * @return the assignments, before the payment guard
   */
  private static List<Assignment> assign(AccountData data) {
    Map<String, List<StatementLine>> eligibleByBounce = eligibleOriginals(data);
    Map<String, List<StatementLine>> groups = new LinkedHashMap<>();
    for (StatementLine r : data.lines) {
      if (eligibleByBounce.containsKey(r.id())) {
        groups.computeIfAbsent(r.date() + "|" + r.amount().stripTrailingZeros().toPlainString(),
            k -> new ArrayList<>()).add(r);
      }
    }
    Set<String> assigned = new HashSet<>();
    List<Assignment> out = new ArrayList<>();
    for (List<StatementLine> group : groups.values()) {
      assignGroup(data, group, eligibleByBounce, assigned, out);
    }
    return out;
  }

  /**
   * For every candidate line, the candidate lines that can be its original with at least one
   * signal. Lines are indexed by magnitude first — only those can pair — which keeps this linear in
   * the number of lines instead of quadratic.
   *
   * @param data the loaded data
   * @return the eligible originals by bounce line id, in statement order
   */
  private static Map<String, List<StatementLine>> eligibleOriginals(AccountData data) {
    Map<String, List<StatementLine>> byMagnitude = new HashMap<>();
    for (StatementLine l : data.lines) {
      if (l.candidate()) {
        byMagnitude.computeIfAbsent(magnitudeKey(l), k -> new ArrayList<>()).add(l);
      }
    }
    Map<String, List<StatementLine>> eligibleByBounce = new LinkedHashMap<>();
    for (StatementLine r : data.lines) {
      if (!r.candidate()) {
        continue;
      }
      List<StatementLine> originals = new ArrayList<>();
      for (StatementLine o : byMagnitude.get(magnitudeKey(r))) {
        if (isOriginalOf(data, o, r) && !signals(data, o, r).isEmpty()) {
          originals.add(o);
        }
      }
      if (!originals.isEmpty()) {
        eligibleByBounce.put(r.id(), originals);
      }
    }
    return eligibleByBounce;
  }

  /**
   * Assigns one group of bounces sharing (date, amount) to their preferred free originals. The
   * group is ambiguous when its bounces could take more originals than there are bounces.
   *
   * @param data             the loaded data
   * @param group            the bounces of the group, in statement order
   * @param eligibleByBounce the eligible originals of every bounce
   * @param assigned         the lines already assigned, updated
   * @param out              the assignments, appended to
   */
  private static void assignGroup(AccountData data, List<StatementLine> group,
      Map<String, List<StatementLine>> eligibleByBounce, Set<String> assigned,
      List<Assignment> out) {
    List<StatementLine> bounces = new ArrayList<>();
    for (StatementLine r : group) {
      if (!assigned.contains(r.id())) {
        bounces.add(r);
      }
    }
    Set<String> union = new LinkedHashSet<>();
    Map<String, List<StatementLine>> freeByBounce = new LinkedHashMap<>();
    for (StatementLine r : bounces) {
      List<StatementLine> free = unassigned(eligibleByBounce.get(r.id()), assigned);
      free.sort(originalPreference(data, r));
      free.forEach(o -> union.add(o.id()));
      freeByBounce.put(r.id(), free);
    }
    List<StatementLine[]> chosen = new ArrayList<>();
    for (StatementLine r : bounces) {
      List<StatementLine> free = unassigned(freeByBounce.get(r.id()), assigned);
      if (!free.isEmpty()) {
        StatementLine o = free.get(0);
        assigned.add(o.id());
        assigned.add(r.id());
        chosen.add(new StatementLine[] { o, r });
      }
    }
    boolean ambiguous = union.size() > bounces.size();
    for (StatementLine[] c : chosen) {
      out.add(new Assignment(c[0], c[1], ambiguous, freeByBounce.get(c[1].id())));
    }
  }

  /**
   * The lines of {@code lines} not yet in {@code assigned}, in order.
   *
   * @param lines    the lines
   * @param assigned the assigned ids
   * @return a new list
   */
  private static List<StatementLine> unassigned(List<StatementLine> lines, Set<String> assigned) {
    List<StatementLine> out = new ArrayList<>();
    for (StatementLine l : lines) {
      if (!assigned.contains(l.id())) {
        out.add(l);
      }
    }
    return out;
  }

  /**
   * The absolute amount as a key, so lines that can pair share it.
   *
   * @param line the line
   * @return the key
   */
  private static String magnitudeKey(StatementLine line) {
    return line.amount().abs().stripTrailingZeros().toPlainString();
  }

  /**
   * Originals with no ERP payment first, then the closest in date, then statement order.
   *
   * @param data the loaded data
   * @param r    the bounce
   * @return the comparator
   */
  private static Comparator<StatementLine> originalPreference(AccountData data, StatementLine r) {
    return Comparator
        .comparing((StatementLine o) -> data.attributionOf(o).ref() != null)
        .thenComparing(o -> ChronoUnit.DAYS.between(o.date(), r.date()))
        .thenComparing(StatementLine::date)
        .thenComparingLong(StatementLine::lineNo);
  }

  /**
   * Applies the payment guard to one assignment and builds the pair. A non-ambiguous pair whose
   * lines are blocked is dropped. An ambiguous pair keeps only the candidates that are not blocked:
   * none left drops it, exactly one left makes it a plain pair (still for review), more keep it
   * ambiguous.
   *
   * @param data   the loaded data
   * @param a      the assignment
   * @param paired every line of every assignment, for the guard's free-line count
   * @return the pair, or {@code null} when it must not be proposed
   */
  private static Pair guarded(AccountData data, Assignment a, Set<String> paired) {
    StatementLine r = a.bounce();
    if (!a.ambiguous()) {
      if (BankBouncePaymentGuard.blocker(data, paired, a.original(), r) != null) {
        return null;
      }
      List<String> signals = signals(data, a.original(), r);
      return new Pair(a.original(), r, Collections.emptyList(), signals,
          confidence(data, a.original(), r, signals));
    }
    List<Candidate> candidates = new ArrayList<>();
    for (StatementLine o : a.freeOriginals()) {
      if (BankBouncePaymentGuard.blocker(data, paired, o, r) == null) {
        candidates.add(new Candidate(o, signals(data, o, r)));
      }
    }
    if (candidates.isEmpty()) {
      return null;
    }
    if (candidates.size() == 1) {
      Candidate only = candidates.get(0);
      return new Pair(only.line(), r, Collections.emptyList(), only.signals(),
          CONFIDENCE_REVIEW);
    }
    return new Pair(null, r, candidates, candidates.get(0).signals(), CONFIDENCE_REVIEW);
  }

  // ---------------------------------------------------------------------------
  // Rules
  // ---------------------------------------------------------------------------

  /**
   * Whether {@code o} can be the original of {@code r}: different lines, opposite sign, exact
   * amount, {@code o} dated on or before {@code r}, {@code r} at most {@value #WINDOW_DAYS} days
   * later. On the same day no order is assumed: {@link #originalFirstOnSameDay} decides.
   *
   * @param data the loaded data
   * @param o    the candidate original
   * @param r    the candidate bounce
   * @return whether the two can pair in that order
   */
  static boolean isOriginalOf(AccountData data, StatementLine o, StatementLine r) {
    if (o.id().equals(r.id()) || o.amount().signum() == 0
        || o.amount().signum() != -r.amount().signum()
        || o.amount().abs().compareTo(r.amount().abs()) != 0) {
      return false;
    }
    if (!withinWindow(o.date(), r.date())) {
      return false;
    }
    return !o.date().equals(r.date()) || originalFirstOnSameDay(data, o, r);
  }

  /**
   * Whether {@code bounceDay} is on or after {@code originalDay} and at most {@value #WINDOW_DAYS}
   * days later.
   *
   * @param originalDay the day of the original
   * @param bounceDay   the day of the bounce
   * @return whether the bounce is in the window
   */
  static boolean withinWindow(LocalDate originalDay, LocalDate bounceDay) {
    return !originalDay.isAfter(bounceDay)
        && !bounceDay.isAfter(originalDay.plusDays(WINDOW_DAYS));
  }

  /**
   * Same-day roles: the line with bounce wording is the bounce; else the outflow with a PSD2
   * payment link is the original; else the outflow is (most bounces are rejected outgoing
   * transfers).
   *
   * @param data the loaded data
   * @param o    the candidate original
   * @param r    the candidate bounce
   * @return whether {@code o} is the original
   */
  static boolean originalFirstOnSameDay(AccountData data, StatementLine o, StatementLine r) {
    boolean textO = wording(data, o);
    boolean textR = wording(data, r);
    if (textO != textR) {
      return textR;
    }
    boolean pisO = pisPaymentId(data, o) != null;
    boolean pisR = pisPaymentId(data, r) != null;
    if (pisO != pisR) {
      return pisO;
    }
    return o.outflow();
  }

  /**
   * {@link BankBounceLineText#hasBounceWording} of the line, memoized per detection run.
   *
   * @param data the loaded data
   * @param line the line
   * @return whether its description carries bounce wording
   */
  private static boolean wording(AccountData data, StatementLine line) {
    return data.wordingByLine.computeIfAbsent(line.id(),
        id -> BankBounceLineText.hasBounceWording(line.description()));
  }

  /**
   * The payment an outflow's description links to through its end-to-end id.
   *
   * @param data the loaded data
   * @param line the line
   * @return the payment id, or {@code null}
   */
  static String pisPaymentId(AccountData data, StatementLine line) {
    if (!line.outflow()) {
      return null;
    }
    String token = BankBounceLineText.lastToken(line.description());
    return token.isEmpty() ? null : data.pisPaymentByEndToEnd.get(token);
  }

  /**
   * The detection signals of the pair, in {@link #SIGNAL_PRIORITY} order.
   *
   * @param data the loaded data
   * @param o    the original
   * @param r    the bounce
   * @return the signals, possibly empty
   */
  static List<String> signals(AccountData data, StatementLine o, StatementLine r) {
    List<String> signals = new ArrayList<>();
    if (pisPaymentId(data, o) != null) {
      signals.add(SIGNAL_PIS_LINK);
    }
    if (wording(data, r)) {
      signals.add(SIGNAL_RETURN_TEXT);
    }
    if (BankBounceLineText.sameCounterparty(o.partnerName(), r.partnerName())) {
      signals.add(SIGNAL_COUNTERPARTY);
    }
    return signals;
  }

  /**
   * {@value #CONFIDENCE_HIGH}: bounce wording on the same day, or a PIS link backed by timing or
   * wording — the bounce within the account's date tolerance of the original, or carrying bounce
   * wording. A PIS link alone with a bounce weeks later is {@value #CONFIDENCE_REVIEW}: it only
   * proves the outflow was a PSD2 payment, not that this inflow reversed it. The caller downgrades
   * an ambiguous pair to review.
   *
   * @param data    the loaded data
   * @param o       the original
   * @param r       the bounce
   * @param signals the pair's signals
   * @return the confidence
   */
  static String confidence(AccountData data, StatementLine o, StatementLine r,
      List<String> signals) {
    boolean text = signals.contains(SIGNAL_RETURN_TEXT);
    boolean close = Math.abs(ChronoUnit.DAYS.between(o.date(), r.date())) <= data.dateTolDays;
    boolean pis = signals.contains(SIGNAL_PIS_LINK) && (close || text);
    return pis || (text && o.date().equals(r.date())) ? CONFIDENCE_HIGH : CONFIDENCE_REVIEW;
  }

  /**
   * The first present signal by {@link #SIGNAL_PRIORITY}.
   *
   * @param signals the signals
   * @return the primary signal, or {@code null} when there is none
   */
  static String primarySignalOf(List<String> signals) {
    for (String s : SIGNAL_PRIORITY) {
      if (signals.contains(s)) {
        return s;
      }
    }
    return null;
  }

  /**
   * The calendar day of a statement date in the server's zone ({@code today} when null).
   *
   * @param date the date
   * @return the day
   */
  static LocalDate dayOf(java.util.Date date) {
    return date == null ? LocalDate.now()
        : new Timestamp(date.getTime()).toLocalDateTime().toLocalDate();
  }

  /**
   * Midnight of a day as a timestamp, for a query bound.
   *
   * @param day the day
   * @return the timestamp
   */
  private static Timestamp toTimestamp(LocalDate day) {
    return Timestamp.valueOf(day.atStartOfDay());
  }
}
