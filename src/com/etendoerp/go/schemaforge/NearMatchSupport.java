package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

/**
 * The 1:1 "near match" of ETP-4965: a statement line and a single transaction that agree only
 * WITHIN the financial account's configured tolerances, on amount, on date, or on both.
 *
 * <p>Extracted from {@link AutoMatchSupport} to keep that class under the Sonar per-class
 * method-count limit (java:S1448), the same arrangement {@link ReconciliationFlowSupport} and
 * {@link ReconciliationDifferenceSupport} already use. The cut is by responsibility, not merely by
 * count: everything here answers "is there a tolerated 1:1 match, and how far off is it", while
 * {@code AutoMatchSupport} keeps exact matching, 1:N signal grouping, classification and the JSON
 * group builders.
 *
 * <p><b>Why this exists at all.</b> Core's {@code StandardMatchingAlgorithm} searches by EXACT
 * amount and EXACT date, and Etendo GO's own date tolerance is only a post-filter over what Core
 * already found — so nothing else in the codebase widens a 1:1 search on either axis. The 1:N
 * {@link AutoMatchSupport#findSignalGroup} is the only other tolerance-aware path and it discards
 * any partition with fewer than two transactions, so a lone 26.62 against a 27.00 line could never
 * surface. That is the ETP-4965 bug.
 */
final class NearMatchSupport {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private NearMatchSupport() {
    // utility class — no instances
  }

  /**
   * The maximum amount deviation a 1:1 near match may carry and still be posted to the account's
   * difference GL item, as {@code abs(target) * pct/100}.
   *
   * <p>Returns {@code null} when {@code pct} is absent or non-positive: 0% means the AMOUNT
   * dimension is disabled, not that the feature is off — see {@link #findNearMatch}, which still
   * searches with a zero-gap requirement. Deliberately unlike
   * {@link AutoMatchSupport#signalGroupTolerance}, which reads the very same
   * {@code EM_ETGO_Amount_Tolerance} column with the opposite convention because it is only
   * rounding slack for a 1:N sum and authorises no accounting entry. Two names for two purposes:
   * never swap them. No floor here either, for the same reason.
   */
  static BigDecimal differenceTolerance(BigDecimal target, BigDecimal pct) {
    if (pct == null || pct.signum() <= 0) {
      return null;
    }
    return target.abs().multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
  }

  /**
   * The single best near match for {@code line}: one unreconciled, same-sign transaction inside the
   * date-tolerance window whose amount deviates by no more than {@code amtTolerance}.
   *
   * <p>Searches the {@link AutoMatchSupport#loadUnreconciledSameSign} pool directly, which already
   * filters by sign, date window and {@code status <> 'RPPC'} — the only code in the module that
   * widens the search by date at all.
   *
   * <p><b>The exact-exact case is deliberately excluded.</b> Zero amount deviation AND zero date
   * deviation is a plain suggestion, not a difference — the first row of the classification matrix.
   * An exact AMOUNT at a non-zero date distance still counts: the deviation is real, it just
   * produces no accounting entry.
   *
   * <p><b>Accumulator contract.</b> {@code usedTxnIds} and {@code excludedTxns} are shared across
   * every line of one classification/automatch pass. This method filters on BOTH and, on a hit,
   * records the winner in BOTH — exactly as the STRONG and signal-group branches do — so a
   * transaction already claimed by an earlier line is never offered again and the left panel cannot
   * count more differences than an automatch run could actually apply.
   *
   * @param amtTolerance the biggest amount deviation to accept, or {@code null} for "no amount
   *     deviation at all". {@code null} does NOT disable the search and is emphatically NOT
   *     unlimited: the two tolerances govern independent dimensions. An account at 0% still has its
   *     date tolerance — 3 days by default, so every account has some — and a date-only deviation
   *     posts nothing, so the safety reason for 0% does not apply to it. What 0% guarantees is that
   *     no amount deviation is ever accepted, hence no accounting entry is ever created.
   * @return the best candidate, or {@code null} when none qualifies
   */
  static FIN_FinaccTransaction findNearMatch(String accountId, FIN_BankStatementLine line,
      Set<String> usedTxnIds, List<FIN_FinaccTransaction> excludedTxns, BigDecimal amtTolerance,
      int dateTolDays) {
    if (StringUtils.isBlank(accountId)) {
      return null;
    }
    // No configured percentage means "exact amount only", never "any amount".
    BigDecimal maxGap = amtTolerance != null ? amtTolerance : BigDecimal.ZERO;
    BigDecimal target = ReconciliationSupport.nullSafe(line.getCramount())
        .subtract(ReconciliationSupport.nullSafe(line.getDramount()));
    if (target.signum() == 0) {
      return null;
    }
    Set<String> excludedIds = claimedIds(usedTxnIds, excludedTxns);
    java.util.Date lineDate = line.getTransactionDate();
    FIN_FinaccTransaction best = null;
    BigDecimal bestGap = null;
    long bestDateDistance = Long.MAX_VALUE;
    for (FIN_FinaccTransaction candidate : AutoMatchSupport.loadUnreconciledSameSign(
        accountId, target, excludedIds, dateTolDays, lineDate)) {
      BigDecimal gap = target.subtract(AutoMatchSupport.txnSignedAmount(candidate)).abs();
      long dateDistance = dayDistance(lineDate, candidate.getTransactionDate());
      if (!isReportableDeviation(gap, dateDistance, maxGap)) {
        continue;
      }
      if (isBetter(gap, dateDistance, bestGap, bestDateDistance)) {
        best = candidate;
        bestGap = gap;
        bestDateDistance = dateDistance;
      }
    }
    if (best != null) {
      usedTxnIds.add(best.getId());
      excludedTxns.add(best);
    }
    return best;
  }

  /** Every transaction id already claimed in this pass, from both halves of the accumulator. */
  private static Set<String> claimedIds(Set<String> usedTxnIds,
      List<FIN_FinaccTransaction> excludedTxns) {
    Set<String> ids = new HashSet<>(usedTxnIds);
    for (FIN_FinaccTransaction t : excludedTxns) {
      if (t != null) {
        ids.add(t.getId());
      }
    }
    return ids;
  }

  /**
   * Whether this candidate's deviation is one worth reporting: inside the amount tolerance, and not
   * the exact-exact case, which is a plain suggestion rather than a difference (matrix row 1).
   */
  private static boolean isReportableDeviation(BigDecimal gap, long dateDistance,
      BigDecimal maxGap) {
    if (gap.compareTo(maxGap) > 0) {
      return false;
    }
    return gap.signum() != 0 || dateDistance != 0;
  }

  /**
   * The ordering: smallest amount deviation wins, and a tie on that is broken by the closest date.
   * A null {@code bestGap} means nothing has been chosen yet.
   */
  private static boolean isBetter(BigDecimal gap, long dateDistance, BigDecimal bestGap,
      long bestDateDistance) {
    if (bestGap == null) {
      return true;
    }
    int byGap = gap.compareTo(bestGap);
    return byGap < 0 || (byGap == 0 && dateDistance < bestDateDistance);
  }

  /**
   * Absolute distance in CALENDAR DAYS between two dates; 0 when either is absent.
   *
   * <p>Deliberately not a millisecond difference. {@code FIN_BankStatementLine.datetrx} and
   * {@code FIN_FinaccTransaction.statementdate} are {@code timestamp} columns, and rows carrying a
   * real time component do exist (imported statements in particular). Subtracting raw millis would
   * make a statement line at 13:00 and a movement at 00:00 of the SAME day look like a deviation, so
   * an exact same-day match would be classified "with difference" instead of "with suggestion" —
   * matrix row 1, silently wrong. Comparing epoch days also makes the tie-break mean what it says,
   * and is immune to DST (a "day" is not always 86.4M ms).
   *
   * <p>A null date yields 0, i.e. "same day": combined with the zero-gap exclusion above, an undated
   * transaction with an exact amount is treated as an exact-exact match and is therefore never
   * offered as a near match. That is the conservative reading — an undated row carries no evidence
   * of a date deviation to report.
   */
  static long dayDistance(java.util.Date a, java.util.Date b) {
    if (a == null || b == null) {
      return 0L;
    }
    return Math.abs(toEpochDay(a) - toEpochDay(b));
  }

  /**
   * Epoch day of {@code d} in the JVM's zone. Built from the raw millis so it is safe for
   * {@code java.sql.Date}, whose own {@code toInstant()} throws.
   */
  private static long toEpochDay(java.util.Date d) {
    return Instant.ofEpochMilli(d.getTime()).atZone(ZoneId.systemDefault()).toLocalDate()
        .toEpochDay();
  }
}
