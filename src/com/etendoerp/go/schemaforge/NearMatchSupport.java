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
   * <p><b>An exact-exact candidate IS returned</b> — same amount, same day — and outranks every
   * other. It used to be skipped, on the assumption that pass 1 (Core's standard algorithm) had
   * already claimed it; Core's criteria are narrower, so when it does not match, skipping hid the
   * best candidate and the line was handed a worse one. This method only ranks; the CALLER labels
   * the result from the deviation it actually has ({@link AutoMatchSupport#deviatesFrom}), so an
   * exact hit is still reported as a plain suggestion and never as a difference.
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
    java.util.Date bestTxnDate = null;
    for (FIN_FinaccTransaction candidate : AutoMatchSupport.loadUnreconciledSameSign(
        accountId, target, excludedIds, dateTolDays, lineDate)) {
      BigDecimal gap = target.subtract(AutoMatchSupport.txnSignedAmount(candidate)).abs();
      long dateDistance = dayDistance(lineDate, candidate.getTransactionDate());
      if (!isEligibleCandidate(gap, maxGap)) {
        continue;
      }
      if (isBetter(gap, dateDistance, candidate.getTransactionDate(),
          bestGap, bestDateDistance, bestTxnDate)) {
        best = candidate;
        bestGap = gap;
        bestDateDistance = dateDistance;
        bestTxnDate = candidate.getTransactionDate();
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
   * Whether a candidate is eligible at all: its amount gap must be within tolerance. An EXACT
   * candidate (zero gap, same day) qualifies too.
   *
   * <p>It used to be excluded, on the assumption that pass 1 — Core's standard algorithm — had
   * already claimed it. When Core does not match (its own criteria are narrower: exact date AND
   * reference, or a fallback that a differing reference can still miss), that assumption silently
   * hid the BEST candidate and handed the line to a worse one: a 14,52 statement line of 04/09 was
   * matched against a 14,52 movement of 01/09 while two same-amount, same-day movements sat
   * unused. Ranking decides now — see {@link #isBetter} — and the caller labels the result from the
   * deviation it actually has, so an exact hit is still reported as a plain suggestion.
   */
  private static boolean isEligibleCandidate(BigDecimal gap, BigDecimal maxGap) {
    return gap.compareTo(maxGap) <= 0;
  }

  /**
   * Ranks candidates: smallest amount gap first, then closest date, then OLDEST transaction.
   *
   * <p>The last tie-break is not cosmetic. With two identical same-day movements the winner used to
   * be whichever the pool happened to yield first — an unstable answer that could change between
   * two runs over unchanged data. Oldest-first also matches how the rest of the reconciliation
   * allocates (invoices are consumed oldest first) and is what a user expects when two payments are
   * indistinguishable.
   */
  private static boolean isBetter(BigDecimal gap, long dateDistance, java.util.Date txnDate,
      BigDecimal bestGap, long bestDateDistance, java.util.Date bestTxnDate) {
    if (bestGap == null) {
      return true;
    }
    int byGap = gap.compareTo(bestGap);
    if (byGap != 0) {
      return byGap < 0;
    }
    if (dateDistance != bestDateDistance) {
      return dateDistance < bestDateDistance;
    }
    if (txnDate == null || bestTxnDate == null) {
      return false;
    }
    return txnDate.before(bestTxnDate);
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
   * <p>A null date yields 0, i.e. "same day", so an undated transaction with an exact amount counts
   * as an exact match and is reported as a plain suggestion, never as a difference. That is the
   * conservative reading — an undated row carries no evidence of a date deviation. Note the
   * consequence for {@link #isBetter}'s oldest-first tie-break: it cannot order two undated
   * candidates, so between those the pool order still decides.
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
