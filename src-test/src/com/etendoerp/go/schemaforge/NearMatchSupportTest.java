/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

/**
 * Unit tests for {@link NearMatchSupport} — the 1:1 "near match" of ETP-4965 and the two helpers it
 * is built from: {@link NearMatchSupport#differenceTolerance} (the posting threshold) and
 * {@link NearMatchSupport#dayDistance} (the date axis, counted in calendar days).
 *
 * <p><b>Scope split with {@code AutoMatchSupportTest}.</b> Everything here exercises the search
 * itself, at the unit level. The §5.1 classification matrix — the same six rows read through
 * {@link AutoMatchSupport#classifyPendingLine}, which is what actually paints the left-panel filter
 * — stays in {@code AutoMatchSupportTest}, because that is the method under test there. Several
 * scenarios are therefore asserted on BOTH sides on purpose: the pair is the contract (the search
 * finds it / the classifier reports it), and dropping either half would let one of them drift.
 *
 * <p>The tolerances used throughout are the ticket's own: a 27.00 statement line, a 26.62 movement
 * (0.38 = 1.41% deviation), a 5% amount tolerance and the default 3-day date tolerance.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class NearMatchSupportTest {

  private static final BigDecimal LINE_AMOUNT = new BigDecimal("27.00");
  private static final String LINE_CREDIT = "27.00";
  private static final String NO_DEBIT = "0.00";
  /** 26.62 against 27.00 — a 0.38 deviation, i.e. 1.41%, inside the 5% tolerance. */
  private static final String NEAR_AMOUNT = "26.62";
  private static final BigDecimal PCT_FIVE = new BigDecimal("5");
  private static final int DATE_TOL_DAYS = 3;
  private static final String NEAR_ACC = "ACC-NEAR";
  private static final String T_NEAR = "T-NEAR";

  /** The posting threshold for the reference case: 5% of 27.00 = 1.35. */
  private static BigDecimal nearTolerance() {
    return NearMatchSupport.differenceTolerance(LINE_AMOUNT, PCT_FIVE);
  }

  // ---------------------------------------------------------------------------
  // differenceTolerance (ETP-4965) — same column as signalGroupTolerance, OPPOSITE convention
  // ---------------------------------------------------------------------------

  /**
   * 5% of 27.00 = 1.35, rounded HALF_UP to two decimals. This is the ticket's reference case: a
   * 27.00 statement line against a 26.62 movement deviates by 0.38, comfortably inside 1.35.
   */
  @Test
  public void testDifferenceTolerance5PctOf27Returns135() {
    assertEquals(0, new BigDecimal("1.35").compareTo(
        NearMatchSupport.differenceTolerance(new BigDecimal("27.00"), new BigDecimal("5"))));
  }

  /** A negative target (an outflow line) yields the same absolute tolerance as its positive twin. */
  @Test
  public void testDifferenceToleranceUsesAbsoluteTarget() {
    assertEquals(0, new BigDecimal("1.35").compareTo(
        NearMatchSupport.differenceTolerance(new BigDecimal("-27.00"), new BigDecimal("5"))));
  }

  /**
   * A zero / null / negative percentage returns null — never a floor. This threshold authorises an
   * automatic accounting entry, so an unconfigured account must not get one by default.
   *
   * <p>Null means "no amount deviation may be accepted or posted", NOT "the feature is off": the
   * date tolerance keeps working and {@link NearMatchSupport#findNearMatch} keeps searching for
   * exact-amount candidates. See {@link #testZeroAmountToleranceStillDetectsADateOnlyDeviation}.
   */
  @Test
  public void testDifferenceToleranceNullWhenPercentageUnsetOrNonPositive() {
    assertNull(NearMatchSupport.differenceTolerance(new BigDecimal("27.00"), BigDecimal.ZERO));
    assertNull(NearMatchSupport.differenceTolerance(new BigDecimal("27.00"), null));
    assertNull(NearMatchSupport.differenceTolerance(new BigDecimal("27.00"), new BigDecimal("-5")));
  }

  /**
   * <b>Cross-class contrast, half one of two.</b> {@link NearMatchSupport#differenceTolerance} and
   * {@link AutoMatchSupport#signalGroupTolerance} read the SAME {@code EM_ETGO_Amount_Tolerance}
   * column with deliberately opposite conventions, and the split of ETP-4965 put them in two
   * different classes — which makes them easier, not harder, to confuse. Asserting the divergence
   * explicitly is the point: collapsing them back into one method is the support trap the rename
   * came to remove.
   *
   * <p>This half reads from the {@code NearMatchSupport} side, so it fails when the POSTING gate is
   * given a floor. Its twin, {@code AutoMatchSupportTest#testSignalGroupToleranceIsNotDifference
   * Tolerance}, reads from the other side and fails when the 1:N rounding slack loses its floor.
   * Whichever class a future change touches, one of the two runs.
   */
  @Test
  public void testDifferenceToleranceIsNotSignalGroupTolerance() {
    BigDecimal target = new BigDecimal("27.00");
    // 0% → one cent of rounding slack for a 1:N SUM, but nothing may be POSTED.
    assertEquals(0, new BigDecimal("0.01")
        .compareTo(AutoMatchSupport.signalGroupTolerance(target, BigDecimal.ZERO)));
    assertNull(NearMatchSupport.differenceTolerance(target, BigDecimal.ZERO));
    // A percentage below the floor is raised to 0.01 for the sum, but NOT for the posting gate.
    assertEquals(0, new BigDecimal("0.01")
        .compareTo(AutoMatchSupport.signalGroupTolerance(target, new BigDecimal("0.001"))));
    assertEquals(0, BigDecimal.ZERO
        .compareTo(NearMatchSupport.differenceTolerance(target, new BigDecimal("0.001"))));
  }

  // ---------------------------------------------------------------------------
  // findNearMatch — guard clauses
  // ---------------------------------------------------------------------------

  /**
   * No account, no search. The id is what scopes the unreconciled pool, so a blank one would widen
   * the query to the whole instance rather than narrow it to nothing.
   */
  @Test
  public void testBlankAccountIdIsNeverANearMatch() {
    FIN_BankStatementLine line = datedLine("L-NOACC", LINE_CREDIT, NO_DEBIT, new Date());
    assertNull(NearMatchSupport.findNearMatch(null, line, new HashSet<>(), new ArrayList<>(),
        nearTolerance(), DATE_TOL_DAYS));
    assertNull(NearMatchSupport.findNearMatch("  ", line, new HashSet<>(), new ArrayList<>(),
        nearTolerance(), DATE_TOL_DAYS));
  }

  /**
   * A zero-amount line has no sign to match on, so the same-sign pool is meaningless and every
   * candidate would be "within tolerance" of nothing. Bailing out early is the only safe reading.
   */
  @Test
  public void testZeroAmountLineIsNeverANearMatch() {
    FIN_BankStatementLine line = datedLine("L-ZERO", "0.00", NO_DEBIT, new Date());
    assertNull(NearMatchSupport.findNearMatch(NEAR_ACC, line, new HashSet<>(), new ArrayList<>(),
        nearTolerance(), DATE_TOL_DAYS));
  }

  // ---------------------------------------------------------------------------
  // findNearMatch — the search, row by row of the §5.1 matrix
  // ---------------------------------------------------------------------------

  /**
   * <b>Matrix row 1, as reworked in round 3.</b> The exact-exact candidate is RETURNED by the
   * search — the label is decided afterwards, by {@link AutoMatchSupport#deviatesFrom}, not by
   * hiding the candidate.
   *
   * <p>It used to be excluded here, on the assumption that Core's pass 1 had already claimed it.
   * Core's criteria are narrower (exact date AND a corroborating reference), so when it does not
   * match, that exclusion silently hid the BEST candidate and handed the line to a worse one — the
   * 14,52 case this ticket's round 3 came from. Eligibility is now the tolerance gate alone; see
   * {@link #testExactCandidateBeatsADateDeviatingOne} for the consequence that motivated it.
   *
   * <p>The classifier half of this row (the line still reads "suggested", never "difference") lives
   * in {@code AutoMatchSupportTest#testExactAmountExactDateIsSuggested} and in the
   * {@code testClassifyAndMatchFallbackAgree*} pair.
   */
  @Test
  public void testExactAmountExactDateIsStillReturnedSoRankingCanSeeIt() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-EXACT", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction exact = nearTxn(T_NEAR, LINE_CREDIT, today);

    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(exact))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line, new HashSet<>(),
          new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull("an exact hit Core missed must still be findable — hiding it is the bug",
          picked);
      assertEquals(T_NEAR, picked.getId());
      assertFalse("and it deviates in nothing, so the caller labels it a plain suggestion",
          AutoMatchSupport.deviatesFrom(line, picked));
    }
  }

  /**
   * Matrix row 2 — the amount matches to the cent but the movement is 2 days away (tolerance 3).
   * Core cannot see it (it searches by exact date), so without this search the line is stuck on
   * "Pendiente" forever. That is the ETP-4965 bug.
   */
  @Test
  public void testExactAmountTwoDaysApartIsANearMatch() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-DATE-ONLY", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction twoDaysLater = nearTxn(T_NEAR, LINE_CREDIT, daysFrom(today, 2));

    try (MockedStatic<OBDal> obDal =
        mockUnreconciledPool(Collections.singletonList(twoDaysLater))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
          new HashSet<>(), new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull("an exact amount at a non-zero date distance is a real deviation", picked);
      assertEquals(T_NEAR, picked.getId());
    }
  }

  /**
   * Matrix row 3 — the ticket's own reported case: a 27.00 line against a 26.62 movement of the
   * same date. 0.38 is 1.41% of the line, inside the 5% tolerance.
   */
  @Test
  public void testAmountWithinToleranceSameDateIsANearMatch() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-AMT", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, today);

    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(near))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
          new HashSet<>(), new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull(picked);
      assertEquals(T_NEAR, picked.getId());
    }
  }

  /**
   * Matrix row 5 — a 20.00 movement deviates by 7.00 from the 27.00 line, far past the 1.35 limit.
   * An out-of-tolerance gap is not an adjustment, it is a different document.
   */
  @Test
  public void testAmountOutsideToleranceIsNeverANearMatch() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-OUT-AMT", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction far = nearTxn(T_NEAR, "20.00", today);

    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(far))) {
      assertNull(NearMatchSupport.findNearMatch(NEAR_ACC, line, new HashSet<>(), new ArrayList<>(),
          nearTolerance(), DATE_TOL_DAYS));
    }
  }

  /**
   * A 0% amount tolerance bounds the AMOUNT dimension and only that one: no amount deviation is
   * ever accepted, so the ticket's own 26.62-against-27.00 case is not detected at all. This is
   * what guarantees an account that never configured a percentage can never receive an automatic
   * accounting entry — {@code differenceTolerance} returns null and {@code findNearMatch} degrades
   * to "exact amount only", never to "any amount".
   *
   * <p>It is NOT a master switch over the whole feature; see the date-only twin below.
   */
  @Test
  public void testZeroAmountToleranceRejectsAnAmountDeviation() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-TOL0-AMT", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, today);

    assertNull(NearMatchSupport.differenceTolerance(LINE_AMOUNT, BigDecimal.ZERO));
    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(near))) {
      assertNull("0% accepts exact amounts only — a 0.38 gap is not a candidate",
          NearMatchSupport.findNearMatch(NEAR_ACC, line, new HashSet<>(), new ArrayList<>(),
              null, DATE_TOL_DAYS));
    }
  }

  /**
   * <b>DETECTION is not POSTING — the distinction this whole ticket turns on.</b>
   *
   * <p>The two account fields govern independent dimensions. {@code EM_ETGO_Amount_Tolerance}
   * bounds how far the AMOUNT may drift and is the only thing that can authorise an accounting
   * entry; {@code EM_ETGO_Date_Tolerance} bounds how many days apart the two may be, defaults to 3
   * on every account ever created, and stays in force at 0% amount tolerance. A date-only deviation
   * creates no accounting entry at all, so the safety reasoning behind 0% simply does not apply to
   * it.
   *
   * <p>The canonical case: a 100.00 line of the 28th against a 100.00 movement of the 26th, on an
   * account at 0% amount / 3 days, is a DIFFERENCE — and reconciling it posts nothing, because the
   * gap is zero (the posting side is asserted in {@code ReconciliationDifferenceSupportTest}, the
   * classification side in {@code AutoMatchSupportTest}). {@code findNearMatch} must therefore keep
   * searching when {@code amtTolerance} is null, demanding a zero amount gap, instead of bailing
   * out.
   */
  @Test
  public void testZeroAmountToleranceStillDetectsADateOnlyDeviation() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-TOL0-DATE", "100.00", NO_DEBIT, today);
    FIN_FinaccTransaction twoDaysEarlier = nearTxn(T_NEAR, "100.00", daysFrom(today, -2));

    assertNull("no percentage is configured, so nothing may ever be posted",
        NearMatchSupport.differenceTolerance(new BigDecimal("100.00"), BigDecimal.ZERO));

    try (MockedStatic<OBDal> obDal =
        mockUnreconciledPool(Collections.singletonList(twoDaysEarlier))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
          new HashSet<>(), new ArrayList<>(), null, DATE_TOL_DAYS);

      assertNotNull("the date window is still in force at 0% amount tolerance", picked);
      assertEquals(T_NEAR, picked.getId());
    }
  }

  /**
   * An outflow movement can never settle an inflow line, however close the numbers look. Reusing
   * the same-sign pool is what guarantees this; asserting it stops a future refactor from widening
   * the search to {@code abs()} comparisons.
   *
   * <p>Asserted at BOTH tolerances on purpose. At 0% the search no longer stops early, it merely
   * demands an exact amount — so a mirror-image movement of the very same magnitude is exactly the
   * candidate that a sign-blind implementation would now start accepting. What rejects it is the
   * same-sign pool, never the tolerance.
   */
  @Test
  public void testOppositeSignTransactionIsNeverANearMatch() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-SIGN", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction outflow = nearTxn(T_NEAR, "-26.62", today);

    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(outflow))) {
      assertNull(NearMatchSupport.findNearMatch(NEAR_ACC, line, new HashSet<>(), new ArrayList<>(),
          nearTolerance(), DATE_TOL_DAYS));
    }

    FIN_FinaccTransaction mirrorImage = nearTxn("T-MIRROR", "-27.00", daysFrom(today, 2));
    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(mirrorImage))) {
      assertNull("an exact-magnitude OPPOSITE-sign movement is not a zero-gap candidate",
          NearMatchSupport.findNearMatch(NEAR_ACC, line, new HashSet<>(), new ArrayList<>(),
              null, DATE_TOL_DAYS));
    }
  }

  // ---------------------------------------------------------------------------
  // findNearMatch — the accumulator contract
  // ---------------------------------------------------------------------------

  /** A transaction already claimed by an earlier line (usedTxnIds) is not offered again. */
  @Test
  public void testFindNearMatchSkipsUsedTransactionIds() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-USED", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, today);

    Set<String> usedTxnIds = new HashSet<>();
    usedTxnIds.add(T_NEAR);
    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(near))) {
      assertNull(NearMatchSupport.findNearMatch(NEAR_ACC, line, usedTxnIds, new ArrayList<>(),
          nearTolerance(), DATE_TOL_DAYS));
    }
  }

  /**
   * Same exclusion through the OTHER accumulator. Both lists must be honoured: {@code usedTxnIds}
   * is what this package's own passes fill, {@code excludedTxns} is what Core's matcher is fed, and
   * a candidate present in either one is already spoken for.
   */
  @Test
  public void testFindNearMatchSkipsExcludedTransactions() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-EXCL", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, today);

    List<FIN_FinaccTransaction> excludedTxns = new ArrayList<>();
    excludedTxns.add(near);
    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(near))) {
      assertNull(NearMatchSupport.findNearMatch(NEAR_ACC, line, new HashSet<>(), excludedTxns,
          nearTolerance(), DATE_TOL_DAYS));
    }
  }

  /**
   * The write half of the accumulator contract: on a hit the winner is recorded in BOTH lists, so
   * the next line of the same pass cannot be offered the same movement.
   *
   * <p>Asserted here at the unit level and again end-to-end through the classifier in
   * {@code AutoMatchSupportTest#testNearMatchHonoursSharedAccumulatorAcrossLines}. Getting this
   * wrong makes the left panel's "Con diferencia" counter promise more than an actual automatch run
   * can apply — the defect the ETP-4951 refactor introduced these accumulators to prevent.
   */
  @Test
  public void testFindNearMatchClaimsTheWinnerInBothAccumulators() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-CLAIM", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, today);

    Set<String> usedTxnIds = new HashSet<>();
    List<FIN_FinaccTransaction> excludedTxns = new ArrayList<>();
    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(near))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line, usedTxnIds,
          excludedTxns, nearTolerance(), DATE_TOL_DAYS);

      assertNotNull(picked);
      assertTrue("the winner must be claimed by id", usedTxnIds.contains(T_NEAR));
      assertTrue("the winner must also be fed back to Core's matcher", excludedTxns.contains(near));
    }
  }

  /**
   * Ordering contract: smallest amount deviation wins; ties are broken by the smallest date
   * distance. Here 26.90 (dev 0.10) beats 26.62 (dev 0.38) even though the latter is on the line's
   * own date, and between the two 26.90 movements the closer one wins.
   */
  @Test
  public void testFindNearMatchPrefersSmallestAmountDeviationThenClosestDate() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-ORDER", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction sameDayBigGap = nearTxn("T-BIG-GAP", NEAR_AMOUNT, today);
    FIN_FinaccTransaction farDaySmallGap = nearTxn("T-SMALL-FAR", "26.90", daysFrom(today, 3));
    FIN_FinaccTransaction sameDaySmallGap = nearTxn("T-SMALL-NEAR", "26.90", daysFrom(today, 1));

    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(
        Arrays.asList(sameDayBigGap, farDaySmallGap, sameDaySmallGap))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
          new HashSet<>(), new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull(picked);
      assertEquals("T-SMALL-NEAR", picked.getId());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-4965 round 3 — RANKING is what picks the winner, not eligibility
  //
  // Round 1 gated the search on "is this deviation worth reporting", which excluded the exact-exact
  // candidate outright on the assumption that Core's pass 1 had already claimed it. Core's criteria
  // are narrower — exact date AND a corroborating reference — so whenever it does not match, that
  // exclusion hid the BEST candidate and the line silently got a worse one.
  //
  // Eligibility is now the amount tolerance alone (plus the pool's own sign/date filtering) and the
  // ORDER decides: smaller amount gap → smaller date distance → older transaction date. The five
  // tests below pin one comparison each, so a failure names which rung of the ladder broke.
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * <b>The round-3 regression, at the search level.</b> An exact candidate must beat one that
   * deviates only in date. Under the old {@code isReportableDeviation} gate the exact one was not
   * even eligible, so the date-deviating movement won by default — which is precisely how a 14,52
   * line of 04/09 ended up matched against a 14,52 movement of 01/09.
   */
  @Test
  public void testExactCandidateBeatsADateDeviatingOne() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-EXACT-WINS", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction dateDeviating = nearTxn("T-DATE-DEV", LINE_CREDIT, daysFrom(today, -2));
    FIN_FinaccTransaction exact = nearTxn("T-EXACT", LINE_CREDIT, today);

    // Pool order deliberately offers the WORSE candidate first, so "keep the first hit" fails here.
    try (MockedStatic<OBDal> obDal =
        mockUnreconciledPool(Arrays.asList(dateDeviating, exact))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
          new HashSet<>(), new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull(picked);
      assertEquals("the same-day exact movement must win over the 2-day-old one; got " + at(picked),
          "T-EXACT", picked.getId());
    }
  }

  /**
   * The amount axis OUTRANKS the date axis. A 26.90 movement two days away (gap 0.10) beats a
   * 26.62 movement on the line's own date (gap 0.38): a smaller amount deviation means a smaller
   * accounting entry, and the date is only the tie-break underneath it.
   */
  @Test
  public void testSmallerAmountGapBeatsACloserDate() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-GAP-FIRST", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction sameDayBigGap = nearTxn("T-SAMEDAY-038", NEAR_AMOUNT, today);
    FIN_FinaccTransaction farDaySmallGap = nearTxn("T-2DAYS-010", "26.90", daysFrom(today, -2));

    try (MockedStatic<OBDal> obDal =
        mockUnreconciledPool(Arrays.asList(sameDayBigGap, farDaySmallGap))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
          new HashSet<>(), new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull(picked);
      assertEquals("a 0.10 gap two days away beats a 0.38 gap on the same day; got " + at(picked),
          "T-2DAYS-010", picked.getId());
    }
  }

  /**
   * <b>The third tie-break, added in round 3.</b> Two candidates identical on BOTH ranked axes —
   * same amount, same calendar day — used to be resolved by whichever the pool happened to yield
   * first, an answer that could change between two runs over unchanged data. The OLDER transaction
   * wins now: it matches how the rest of the reconciliation allocates (oldest first) and it is
   * stable.
   *
   * <p>Asserted in both pool orders, because an order-dependent winner is exactly the defect this
   * tie-break removes and one ordering alone cannot see it.
   */
  @Test
  public void testFullTieIsBrokenByTheOlderTransactionDate() {
    Date lineNoon = dayAt(2026, 8, 28, 12);
    FIN_BankStatementLine line = datedLine("L-FULL-TIE", LINE_CREDIT, NO_DEBIT, lineNoon);
    FIN_FinaccTransaction morning = nearTxn("T-08H", LINE_CREDIT, dayAt(2026, 8, 28, 8));
    FIN_FinaccTransaction evening = nearTxn("T-20H", LINE_CREDIT, dayAt(2026, 8, 28, 20));

    // The premise, asserted rather than assumed: nothing but the timestamp can separate these two.
    assertEquals(0L, NearMatchSupport.dayDistance(lineNoon, morning.getTransactionDate()));
    assertEquals(0L, NearMatchSupport.dayDistance(lineNoon, evening.getTransactionDate()));

    for (List<FIN_FinaccTransaction> pool
        : Arrays.asList(Arrays.asList(evening, morning), Arrays.asList(morning, evening))) {
      try (MockedStatic<OBDal> obDal = mockUnreconciledPool(pool)) {
        FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
            new HashSet<>(), new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

        assertNotNull(picked);
        assertEquals("on a full tie the OLDER movement wins, in either pool order; got "
            + at(picked), "T-08H", picked.getId());
      }
    }
  }

  /**
   * A candidate past the amount tolerance is not merely outranked, it is not a candidate at all: a
   * 20.00 movement deviates by 7.00 from the 27.00 line, far past the 1.35 limit, and must lose to
   * an eligible 26.62 even though it sits earlier in the pool.
   *
   * <p>Distinct from {@link #testAmountOutsideToleranceIsNeverANearMatch}, which proves the lone
   * out-of-tolerance candidate yields nothing. This one proves the gate still runs when there IS
   * something to rank — the case a ranking-only refactor could drop.
   */
  @Test
  public void testCandidateOutsideTheAmountToleranceLosesToAnEligibleOne() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-OUT-VS-IN", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction outOfTolerance = nearTxn("T-20-00", "20.00", today);
    FIN_FinaccTransaction eligible = nearTxn(T_NEAR, NEAR_AMOUNT, daysFrom(today, -2));

    try (MockedStatic<OBDal> obDal =
        mockUnreconciledPool(Arrays.asList(outOfTolerance, eligible))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
          new HashSet<>(), new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull(picked);
      assertEquals("a 7.00 gap is out of tolerance whatever its date; got " + at(picked),
          T_NEAR, picked.getId());
    }
  }

  /**
   * The date window is a hard bound, not a preference: an EXACT amount 4 days away with a 3-day
   * tolerance is never returned, however perfect the amount is. Enforced by the pool loader
   * ({@code loadUnreconciledSameSign}), which is why the mock stubs Hibernate rather than the
   * loader — the real filtering has to run for this to mean anything.
   */
  @Test
  public void testCandidateOutsideTheDateWindowIsNeverReturned() {
    Date today = new Date();
    FIN_BankStatementLine line = datedLine("L-OUT-WINDOW", LINE_CREDIT, NO_DEBIT, today);
    FIN_FinaccTransaction fourDaysLater = nearTxn(T_NEAR, LINE_CREDIT, daysFrom(today, 4));

    try (MockedStatic<OBDal> obDal =
        mockUnreconciledPool(Collections.singletonList(fourDaysLater))) {
      assertNull("4 days is outside a 3-day window — an exact amount does not buy an exception",
          NearMatchSupport.findNearMatch(NEAR_ACC, line, new HashSet<>(), new ArrayList<>(),
              nearTolerance(), DATE_TOL_DAYS));
    }
  }

  /** Renders the picked candidate's own date, so a ranking failure names what was chosen. */
  private static String at(FIN_FinaccTransaction txn) {
    if (txn == null) {
      return "nothing";
    }
    Date date = txn.getTransactionDate();
    return txn.getId() + " dated "
        + (date == null ? "(no date)" : new SimpleDateFormat("dd/MM/yyyy HH:mm").format(date));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-4965 — the date axis is CALENDAR DAYS, not elapsed milliseconds
  //
  // Every date column this feature reads is a timestamp, not a date: FIN_BankStatementLine.datetrx,
  // FIN_FinaccTransaction.statementdate and .dateacct are all `timestamp without time zone`, and
  // rows carrying a real time component exist in production (imported statements above all). A
  // millisecond distance therefore turns 13:00 and 00:00 of the SAME day into a "date deviation",
  // which silently demotes matrix row 1 (exact amount, exact date = SUGGESTED) to "con diferencia"
  // and makes the tie-break order candidates by clock time instead of by day.
  //
  // dayDistance was private before the ETP-4965 split, so these consequences could only be reached
  // through findNearMatch. It is package-private now, so each test below pins the unit directly AND
  // through the search — the direct assertion says what the number is, the behavioural one says
  // what it costs to get it wrong, and it was the behavioural half that caught the reported bug.
  //
  // NOTE: they say nothing about AutoMatchSupport.withinDateWindow, which still measures N days as
  // N x 24h. That is pre-existing, shared with findSignalGroup/standardMatch, and out of this
  // ticket's scope. Every date pair used here sits comfortably inside the 3-day window under EITHER
  // reading, so fixing that window later cannot break these tests.
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * The unit itself, on the three readings that differ: hours within one calendar day are zero days
   * apart however far apart the clocks are, a boundary crossed is a day whether it took 47 hours or
   * one minute, and the result is an absolute value.
   */
  @Test
  public void testDayDistanceCountsCalendarDaysNotElapsedMillis() {
    Date midnight = dayAt(2026, 8, 28, 0);
    Date lateSameDay = dayAt(2026, 8, 28, 23);
    Date nextDayLate = dayAt(2026, 8, 29, 23);
    Date twoDaysBackLate = dayAt(2026, 8, 26, 23);

    assertEquals("23 hours inside one calendar day is still the same day",
        0L, NearMatchSupport.dayDistance(midnight, lateSameDay));
    assertEquals("47 elapsed hours across ONE calendar boundary is one day",
        1L, NearMatchSupport.dayDistance(midnight, nextDayLate));
    assertEquals("25 elapsed hours across TWO calendar boundaries is two days",
        2L, NearMatchSupport.dayDistance(midnight, twoDaysBackLate));
    assertEquals("the distance is absolute, so the argument order cannot matter",
        NearMatchSupport.dayDistance(midnight, nextDayLate),
        NearMatchSupport.dayDistance(nextDayLate, midnight));
  }

  /**
   * The documented null-date convention, pinned as a unit so that changing it has to be a decision
   * rather than an accident. A missing date yields zero, i.e. "same day" — an undated row carries
   * no evidence of a date deviation, and reporting one would invent it.
   */
  @Test
  public void testDayDistanceWithANullDateIsZero() {
    Date some = dayAt(2026, 8, 28, 11);
    assertEquals(0L, NearMatchSupport.dayDistance(some, null));
    assertEquals(0L, NearMatchSupport.dayDistance(null, some));
    assertEquals(0L, NearMatchSupport.dayDistance(null, null));
  }

  /**
   * <b>Regression, matrix row 1.</b> A statement line at 13:00 and its movement at 00:00 of the
   * SAME calendar day, for the very same amount, are ZERO days apart — a plain suggestion. Measured
   * in millis they are 13 hours apart, which reads as a date deviation: the line then shows "Con
   * diferencia" for a match that deviates in nothing at all, and the filter stops meaning what its
   * name says. Nothing in the timestamps is unusual — importers routinely stamp a real time on the
   * statement side and midnight on the movement side.
   *
   * <p>Round 3 moved WHERE this is decided, not WHAT is decided: the search now returns the pair
   * and {@link AutoMatchSupport#deviatesFrom} is what must read zero deviation on it. Both halves
   * are asserted, since a millisecond reading sneaking back into either one produces the same
   * user-visible bug. The classifier half — the same pair read through {@code classifyPendingLine},
   * where the state is painted — is asserted in {@code AutoMatchSupportTest}.
   */
  @Test
  public void testSameCalendarDayDifferentTimesExactAmountDeviatesInNothing() {
    Date lineAfternoon = dayAt(2026, 8, 28, 13);
    Date movementMidnight = dayAt(2026, 8, 28, 0);
    FIN_BankStatementLine line = datedLine("L-SAMEDAY-EXACT", LINE_CREDIT, NO_DEBIT, lineAfternoon);
    FIN_FinaccTransaction exact = nearTxn(T_NEAR, LINE_CREDIT, movementMidnight);

    assertEquals("13:00 and 00:00 of one calendar day are zero days apart",
        0L, NearMatchSupport.dayDistance(lineAfternoon, movementMidnight));

    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(exact))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line, new HashSet<>(),
          new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull("the pair is a candidate — the time component is not a deviation", picked);
      assertFalse("13 hours inside one calendar day must not read as a date deviation",
          AutoMatchSupport.deviatesFrom(line, picked));
    }
  }

  /**
   * The other side of that fix: collapsing the date to a calendar day must not collapse the AMOUNT
   * with it. A 27.00 line at 09:00 against a 26.62 movement at 19:00 of the same day is still a
   * near match — the deviation was never the date, it is the 0.38. Guards against "fixing" row 1 by
   * turning it into "same day means never a difference", which would silently delete the ticket's
   * own reported case (matrix row 3).
   */
  @Test
  public void testSameCalendarDayDifferentTimesAmountDeviationIsStillANearMatch() {
    Date lineMorning = dayAt(2026, 8, 28, 9);
    Date movementEvening = dayAt(2026, 8, 28, 19);
    FIN_BankStatementLine line = datedLine("L-SAMEDAY-AMT", LINE_CREDIT, NO_DEBIT, lineMorning);
    FIN_FinaccTransaction near = nearTxn(T_NEAR, NEAR_AMOUNT, movementEvening);

    assertEquals("the two timestamps are the same calendar day",
        0L, NearMatchSupport.dayDistance(lineMorning, movementEvening));

    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(near))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
          new HashSet<>(), new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull("the zero-gap exclusion is about the AMOUNT gap; 0.38 is not zero", picked);
      assertEquals(T_NEAR, picked.getId());
    }
  }

  /**
   * <b>Regression, tie-break.</b> When two candidates carry the IDENTICAL amount deviation, the
   * closer one wins — and "closer" is counted in calendar days, the unit the account's date
   * tolerance is expressed in. Here the clock deliberately disagrees with the calendar: the
   * one-day-away movement is 47 hours off, the two-days-away one only 25. A millisecond distance
   * picks the two-day movement; days pick the one-day movement, which is the answer the user's own
   * "3 days of tolerance" vocabulary promises.
   *
   * <p>Asserted in both pool orders, since the loop keeps the first candidate on a tie and an
   * order-dependent winner would be a different bug wearing this one's clothes.
   */
  @Test
  public void testNearMatchTieBreakCountsCalendarDaysNotElapsedMillis() {
    Date lineMidnight = dayAt(2026, 8, 28, 0);
    FIN_BankStatementLine line = datedLine("L-TIEBREAK", LINE_CREDIT, NO_DEBIT, lineMidnight);
    // Same 0.10 deviation on both, so nothing but the date distance can decide between them.
    Date oneDayAwayDate = dayAt(2026, 8, 29, 23);
    Date twoDaysAwayDate = dayAt(2026, 8, 26, 23);
    FIN_FinaccTransaction oneDayAway = nearTxn("T-1-DAY", "26.90", oneDayAwayDate);
    FIN_FinaccTransaction twoDaysAway = nearTxn("T-2-DAYS", "26.90", twoDaysAwayDate);

    // The premise, asserted rather than assumed: the calendar and the clock disagree here, and it
    // is only because they disagree that the behavioural assertion below can discriminate.
    assertEquals(1L, NearMatchSupport.dayDistance(lineMidnight, oneDayAwayDate));
    assertEquals(2L, NearMatchSupport.dayDistance(lineMidnight, twoDaysAwayDate));
    assertTrue("the nearer candidate in days is the FARTHER one in elapsed millis",
        Math.abs(lineMidnight.getTime() - oneDayAwayDate.getTime())
            > Math.abs(lineMidnight.getTime() - twoDaysAwayDate.getTime()));

    List<List<FIN_FinaccTransaction>> bothOrders = Arrays.asList(
        Arrays.asList(twoDaysAway, oneDayAway), Arrays.asList(oneDayAway, twoDaysAway));
    for (List<FIN_FinaccTransaction> pool : bothOrders) {
      try (MockedStatic<OBDal> obDal = mockUnreconciledPool(pool)) {
        FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
            new HashSet<>(), new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

        assertNotNull(picked);
        assertEquals("one calendar day beats two, however the elapsed hours compare",
            "T-1-DAY", picked.getId());
      }
    }
  }

  /**
   * The null-date convention as the search sees it. An undated movement yields a zero day distance,
   * i.e. "same day", so an exact amount makes it a NON-deviating candidate: findable, but reported
   * as a plain suggestion rather than a difference. An undated row carries no evidence of a date
   * deviation, and reporting one would invent it.
   *
   * <p>That convention is a floor, not an off switch — the second half asserts that a null date
   * still lets a genuine AMOUNT deviation surface.
   */
  @Test
  public void testNullTransactionDateWithExactAmountDeviatesInNothing() {
    Date lineDate = dayAt(2026, 8, 28, 11);
    FIN_BankStatementLine line = datedLine("L-NULL-DATE", LINE_CREDIT, NO_DEBIT, lineDate);
    FIN_FinaccTransaction undatedExact = nearTxn("T-UNDATED-EXACT", LINE_CREDIT, null);

    try (MockedStatic<OBDal> obDal =
        mockUnreconciledPool(Collections.singletonList(undatedExact))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line, new HashSet<>(),
          new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull("a missing date does not disqualify a candidate", picked);
      assertFalse("no date means no date deviation, so an exact amount deviates in nothing",
          AutoMatchSupport.deviatesFrom(line, picked));
    }

    FIN_FinaccTransaction undatedNear = nearTxn("T-UNDATED-NEAR", NEAR_AMOUNT, null);
    try (MockedStatic<OBDal> obDal = mockUnreconciledPool(Collections.singletonList(undatedNear))) {
      FIN_FinaccTransaction picked = NearMatchSupport.findNearMatch(NEAR_ACC, line,
          new HashSet<>(), new ArrayList<>(), nearTolerance(), DATE_TOL_DAYS);

      assertNotNull("a missing date does not disable the amount search", picked);
      assertEquals("T-UNDATED-NEAR", picked.getId());
    }
  }

  // ---------------------------------------------------------------------------
  // Builders
  // ---------------------------------------------------------------------------

  /**
   * A local-zone instant at a named calendar day and hour, with minutes, seconds and millis zeroed.
   * Goes through {@code Calendar}, i.e. the very zone {@code ZoneId.systemDefault()} resolves to in
   * the production epoch-day conversion, so the calendar day a test names is the calendar day the
   * code under test sees on any CI host.
   *
   * <p>Late August on purpose: no inhabited time zone shifts its DST offset there, in either
   * hemisphere, so no test above can start failing because a host runs in Santiago or Sydney.
   */
  private static Date dayAt(int year, int month, int dayOfMonth, int hour) {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(year, month - 1, dayOfMonth, hour, 0, 0);
    return cal.getTime();
  }

  private static Date daysFrom(Date base, int days) {
    Calendar cal = Calendar.getInstance();
    cal.setTime(base);
    cal.add(Calendar.DAY_OF_MONTH, days);
    return cal.getTime();
  }

  /** A statement line with a fixed amount and transaction date. */
  private static FIN_BankStatementLine datedLine(String id, String credit, String debit, Date date) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    lenient().when(line.getId()).thenReturn(id);
    lenient().when(line.getCramount()).thenReturn(new BigDecimal(credit));
    lenient().when(line.getDramount()).thenReturn(new BigDecimal(debit));
    lenient().when(line.getDescription()).thenReturn("desc");
    lenient().when(line.getReferenceNo()).thenReturn("");
    lenient().when(line.getBpartnername()).thenReturn("");
    lenient().when(line.getTransactionDate()).thenReturn(date);
    return line;
  }

  /** A bare unreconciled transaction (no partner, no reference) with an amount and a date. */
  private static FIN_FinaccTransaction nearTxn(String id, String amount, Date date) {
    BigDecimal amt = new BigDecimal(amount);
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    lenient().when(t.getId()).thenReturn(id);
    lenient().when(t.getBusinessPartner()).thenReturn(null);
    lenient().when(t.getFinPayment()).thenReturn(null);
    lenient().when(t.getDepositAmount())
        .thenReturn(amt.signum() >= 0 ? amt : BigDecimal.ZERO);
    lenient().when(t.getPaymentAmount())
        .thenReturn(amt.signum() >= 0 ? BigDecimal.ZERO : amt.abs());
    lenient().when(t.getTransactionDate()).thenReturn(date);
    return t;
  }

  /**
   * Mocks the DAL seam {@code AutoMatchSupport.loadUnreconciledSameSign} reaches through, so
   * {@code findNearMatch} sees {@code pool} as the account's whole unreconciled set. Note this
   * mocks HIBERNATE, not {@code AutoMatchSupport}: the sign/date/status filtering of the real
   * loader still runs, which is what makes the opposite-sign and out-of-window assertions above
   * mean something. Caller closes it.
   */
  private static MockedStatic<OBDal> mockUnreconciledPool(List<FIN_FinaccTransaction> pool) {
    MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    lenient().when(dal.getSession()).thenReturn(session);
    @SuppressWarnings("unchecked")
    org.hibernate.query.Query<FIN_FinaccTransaction> query =
        mock(org.hibernate.query.Query.class);
    lenient().when(session.createQuery(anyString(), eq(FIN_FinaccTransaction.class)))
        .thenReturn(query);
    lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
    lenient().when(query.list()).thenReturn(pool);
    return obDal;
  }
}
