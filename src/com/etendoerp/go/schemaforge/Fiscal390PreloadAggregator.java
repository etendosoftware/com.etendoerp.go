/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Modelo 390 preload from the Modelo 303 self-assessments of the exercise, and the "cuadre"
 * (reconciliation) of the annual result with them (ETP-5716). Pure: no DAL, no request — the
 * caller ({@link Fiscal390PreloadSupport}) loads the declarations and their boxes.
 *
 * <p><b>Which 303 counts for a period.</b> A period can have several declarations (the
 * rectificativa flow, {@code DECL_SEQ}). The winner is the latest SUBMITTED one; a period with no
 * submitted declaration uses its latest draft ({@link #selectWinners}).
 *
 * <p><b>The figure of a period</b> is box [69] of the winner, after its manual overrides and the
 * 303 derivation ({@link #effectiveBoxes}, a port of the frontend {@code recomputeDerivedBoxes}):
 * the full result of the period after the compensation [78] it applied. [71] is not used because a
 * rectificativa subtracts in [70] what the replaced declaration already declared — [69] is the
 * period's complete result either way (an ordinary declaration has [70] = [109] = 0, so [69] =
 * [71]).
 *
 * <p><b>303 → 390 mapping</b> (AEAT "Instrucciones del modelo 390", block 9 and boxes 85/93):
 * <pre>
 *   [69] &gt; 0                        → INGRESAR_&lt;end month&gt;          (Σ = [95])
 *   [69] &lt; 0, tipo D/V/X (refund)    → monthly register: DEVOLVER_&lt;end month&gt; (Σ = [96])
 *                                     otherwise, last period: [98] TOTAL_LASTPERIOD_DEVOLVER
 *   [69] &lt; 0, otherwise (compensate) → last period: [97] TOTAL_LASTPERIOD
 *                                     earlier period: a credit generated in the year (pool)
 *   Σ [78], oldest credits first     → [85] LIQANUAL_COMPENSATION (prior-year credits applied),
 *                                     or [93] COMMON_TERRITORY_COMPENSATION in territory mode
 *   in-year credits never applied    → [662] LIQANUAL_ENDCOMPENSATION
 *   last 303 [65] / [89] [90] [91] [92] → [87] COMMON_TERRITORY / [88] ALAVA / [89] GUIPUZCOA /
 *                                       [90] VIZCAYA / [91] NAVARRA, only when Σ 303[89..92] &gt; 0
 * </pre>
 * A quarter's amount goes to its closing month (T1 → 03 … T4 → 12): classic only has per-month
 * inputs and AEAT only reads their sums. A refund outside the last period without the monthly
 * register cannot be a 390 refund (classic {@code AEAT390_MonthlyReturn_Check}); it is treated as a
 * credit to compensate and reported ({@code REFUND_WITHOUT_MONTHLY_REGISTER}).
 *
 * <p><b>[85] / [662] — credits, oldest first.</b> AEAT: [85] are the credits "generadas en
 * ejercicios anteriores y aplicadas en el ejercicio"; [662] the credits "generadas en el ejercicio
 * en alguno de los períodos distintos del último cuando no se hubiesen aplicado". Both come from
 * one walk in period order: each period's [78] consumes the prior-year carry first (the first
 * 303's [110], "cuotas a compensar pendientes de periodos anteriores"), then the credits generated
 * earlier in the year. When the first 303 carries no [110] the carry is unknown and every [78] is
 * taken as prior-year (the literal AEAT text, "consignadas en la casilla 78");
 * {@code PRIOR_CARRY_UNKNOWN} reports it.
 *
 * <p><b>Cuadre.</b> {@code expectedResult = [95] − [96] − [97] − [98] − [662]}, all from the 303s.
 * Over the year Σ[69] = Σ([66] + [77] + [68] + [108]) − Σ[78], and the annual result is
 * [86] = [84] + [659] − [85] (territory: [94] = [92] + [659] − [93]); with [84] ≈ Σ([66] + [68] +
 * [108]) and [659] ≈ Σ[77] both sides reduce to Σ[69] + (in-year credits applied), so a 390 that
 * matches its 303s has {@code annualResult = expectedResult}. The 303 figures are the common
 * territory's share ([66] = [64] × [65] %), hence [94] in territory mode.
 */
final class Fiscal390PreloadAggregator {

  /**
   * Largest |annual result − expected result| still reported as "cuadra". Business rule pending
   * confirmation (Jira ETP-5716: "más de 1,00 €"); the frontend mirrors it as
   * {@code RECONCILIATION_TOLERANCE_390}.
   */
  static final BigDecimal RECONCILIATION_TOLERANCE = new BigDecimal("1.00");

  static final String PERIODICITY_MONTHLY = "monthly";
  static final String PERIODICITY_QUARTERLY = "quarterly";

  static final String KIND_PAY = "pay";
  static final String KIND_REFUND = "refund";
  static final String KIND_COMPENSATE = "compensate";
  static final String KIND_ZERO = "zero";

  static final String W_MISSING = "MISSING_303";
  static final String W_LAST_PERIOD_MISSING = "LAST_PERIOD_MISSING";
  static final String W_DRAFT = "DRAFT_303";
  static final String W_PRIOR_CARRY_UNKNOWN = "PRIOR_CARRY_UNKNOWN";
  static final String W_REFUND_WITHOUT_REGISTER = "REFUND_WITHOUT_MONTHLY_REGISTER";
  static final String W_COMPUTE_FAILED = "COMPUTE_FAILED";

  /** 303 declaration types that ask for a refund (Devolución, cuenta corriente, extranjero). */
  static final Set<String> REFUND_TYPES =
      Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("D", "V", "X")));

  /** 303 boxes AEAT defines as unsigned (frontend {@code NEGATIVE_NOT_ALLOWED_BOXES}). */
  static final Set<Integer> NON_NEGATIVE_303_BOXES =
      Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(111, 77, 70, 78, 109, 110)));

  private static final int[] DEDUCTIBLE_303_BOXES = { 29, 31, 33, 35, 37, 39, 41, 42, 43, 44 };
  private static final int[] FORAL_303_BOXES = { 89, 90, 91, 92 };
  private static final String[] FORAL_390_KEYS = { "ALAVA", "GUIPUZCOA", "VIZCAYA", "NAVARRA" };
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final int MONTHS = 12;
  private static final int LAST_MONTH = 12;

  static final String INGRESAR_PREFIX = "INGRESAR_";
  static final String DEVOLVER_PREFIX = "DEVOLVER_";
  static final String TOTAL_LASTPERIOD = "TOTAL_LASTPERIOD";
  static final String TOTAL_LASTPERIOD_DEVOLVER = "TOTAL_LASTPERIOD_DEVOLVER";
  static final String END_COMPENSATION = "LIQANUAL_ENDCOMPENSATION";

  private Fiscal390PreloadAggregator() {
  }

  /** One Modelo 303 declaration as the aggregation sees it. */
  static final class Decl303 {
    final String id;
    final String period;
    final String status;
    final boolean submitted;
    final long seq;
    /** {@code snapshot} (frozen at submission), {@code live} (computed now) or {@code error}. */
    String source;
    /** Effective boxes (raw + overrides + derivation, {@link #effectiveBoxes}); empty on error. */
    Map<Integer, BigDecimal> boxes = Collections.emptyMap();
    /** {@code identification.tipo_declaracion}, or {@code null}. */
    String tipo;
    /** Message of a failed live compute ({@code source = error}). */
    String error;
    /** How many other declarations of the same period this one replaced. */
    int superseded;

    Decl303(String id, String period, String status, boolean submitted, long seq) {
      this.id = id;
      this.period = period;
      this.status = status;
      this.submitted = submitted;
      this.seq = seq;
    }
  }

  /** The aggregation of the year. */
  static final class Result {
    String periodicity = PERIODICITY_QUARTERLY;
    final List<Decl303> declarations = new ArrayList<>();
    final List<String> missingPeriods = new ArrayList<>();
    String lastPeriod;
    /** 390 input key → amount (2 decimals). Only the keys the 303s determine. */
    final Map<String, BigDecimal> inputs = new LinkedHashMap<>();
    /** AEAT box → amount: 85 (or 93), 95, 96, 97, 98, 662. */
    final Map<Integer, BigDecimal> totals = new LinkedHashMap<>();
    boolean territory;
    boolean carryOverKnown = true;
    /** [95] − [96] − [97] − [98] − [662]; {@code null} when the year has no usable 303. */
    BigDecimal expectedResult;
    final List<Map<String, String>> warnings = new ArrayList<>();
  }

  // ── Selection ───────────────────────────────────────────────────────────

  /**
   * One declaration per period: the latest submitted one (highest {@code seq}), else the latest
   * draft. Sets {@link Decl303#superseded}. Periods are returned in calendar order.
   */
  static List<Decl303> selectWinners(Collection<Decl303> all) {
    Map<String, List<Decl303>> byPeriod = new HashMap<>();
    for (Decl303 d : all) {
      if (endMonth(d.period) > 0) {
        byPeriod.computeIfAbsent(d.period, k -> new ArrayList<>()).add(d);
      }
    }
    List<Decl303> winners = new ArrayList<>();
    for (List<Decl303> group : byPeriod.values()) {
      Decl303 best = null;
      for (Decl303 d : group) {
        if (best == null || better(d, best)) {
          best = d;
        }
      }
      best.superseded = group.size() - 1;
      winners.add(best);
    }
    winners.sort(PERIOD_ORDER);
    return winners;
  }

  private static boolean better(Decl303 candidate, Decl303 current) {
    if (candidate.submitted != current.submitted) {
      return candidate.submitted;
    }
    return candidate.seq > current.seq;
  }

  /** Calendar order: by closing month, a quarter before a month closing on the same month. */
  static final Comparator<Decl303> PERIOD_ORDER = Comparator
      .comparingInt((Decl303 d) -> endMonth(d.period))
      .thenComparing(d -> d.period.startsWith("T") ? 0 : 1);

  // ── Periods ─────────────────────────────────────────────────────────────

  /** Closing month (1-12) of a 303 period code ({@code T1}-{@code T4}, {@code 01}-{@code 12}); 0 if invalid. */
  static int endMonth(String period) {
    if (period == null) {
      return 0;
    }
    try {
      if (period.length() == 2 && period.charAt(0) == 'T') {
        int q = Integer.parseInt(period.substring(1));
        return q >= 1 && q <= 4 ? q * 3 : 0;
      }
      if (period.length() == 2) {
        int m = Integer.parseInt(period);
        return m >= 1 && m <= MONTHS ? m : 0;
      }
    } catch (NumberFormatException e) {
      return 0;
    }
    return 0;
  }

  private static Set<Integer> coveredMonths(String period) {
    int end = endMonth(period);
    Set<Integer> out = new TreeSet<>();
    if (end == 0) {
      return out;
    }
    int start = period.startsWith("T") ? end - 2 : end;
    for (int m = start; m <= end; m++) {
      out.add(m);
    }
    return out;
  }

  static String monthCode(int month) {
    return month < 10 ? "0" + month : String.valueOf(month);
  }

  // ── 303 boxes ───────────────────────────────────────────────────────────

  /**
   * The boxes a 303 declaration shows: {@code raw} (backend compute or submission snapshot) with
   * {@code overrides} ({@code manualData.manualOverrides}) on top, the unsigned boxes floored at 0
   * and [45] [46] [64] [66] [69] [71] re-derived — the Java port of the frontend
   * {@code applyOverrides} + {@code recomputeDerivedBoxes} (fiscalModelsUtils.js):
   * <pre>
   *   45 = Σ(29,31,33,35,37,39,41,42,43,44)   46 = 27 − 45   64 = 46 + 58 + 76
   *   66 = 64 × 65 / 100 (65 defaults to 100)  69 = 66 + 77 − 78 + 68 + 108
   *   71 = 69 − 70 + 109 − 112
   * </pre>
   */
  static Map<Integer, BigDecimal> effectiveBoxes(Map<Integer, BigDecimal> raw,
      Map<Integer, BigDecimal> overrides) {
    Map<Integer, BigDecimal> b = new HashMap<>(raw != null ? raw : Collections.emptyMap());
    if (overrides != null) {
      b.putAll(overrides);
    }
    for (Integer box : NON_NEGATIVE_303_BOXES) {
      BigDecimal v = b.get(box);
      if (v != null && v.signum() < 0) {
        b.put(box, BigDecimal.ZERO);
      }
    }
    BigDecimal box65 = b.containsKey(65) ? b.get(65) : HUNDRED;
    BigDecimal box45 = BigDecimal.ZERO;
    for (int n : DEDUCTIBLE_303_BOXES) {
      box45 = box45.add(get(b, n));
    }
    box45 = r2(box45);
    BigDecimal box46 = r2(get(b, 27).subtract(box45));
    BigDecimal box64 = r2(box46.add(get(b, 58)).add(get(b, 76)));
    BigDecimal box66 = r2(box64.multiply(box65).divide(HUNDRED, 10, RoundingMode.HALF_UP));
    BigDecimal box69 = r2(box66.add(get(b, 77)).subtract(get(b, 78)).add(get(b, 68))
        .add(get(b, 108)));
    BigDecimal box71 = r2(box69.subtract(get(b, 70)).add(get(b, 109)).subtract(get(b, 112)));
    b.put(45, box45);
    b.put(46, box46);
    b.put(64, box64);
    b.put(66, box66);
    b.put(69, box69);
    b.put(71, box71);
    return b;
  }

  private static BigDecimal get(Map<Integer, BigDecimal> b, int box) {
    BigDecimal v = b.get(box);
    return v != null ? v : BigDecimal.ZERO;
  }

  static BigDecimal r2(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP);
  }

  /** The figure of a period ({@code [69]}, see the class comment). */
  static BigDecimal periodResult(Decl303 d) {
    return get(d.boxes, 69);
  }

  /** {@code pay} / {@code refund} / {@code compensate} / {@code zero} for a period's [69]. */
  static String kindOf(Decl303 d) {
    int sign = periodResult(d).signum();
    if (sign > 0) {
      return KIND_PAY;
    }
    if (sign == 0) {
      return KIND_ZERO;
    }
    return d.tipo != null && REFUND_TYPES.contains(d.tipo) ? KIND_REFUND : KIND_COMPENSATE;
  }

  // ── Aggregation ─────────────────────────────────────────────────────────

  /**
   * Aggregates the period winners ({@link #selectWinners}) of the exercise.
   *
   * @param winners       one declaration per period, any order
   * @param monthlyReturn the 390 declarant is in the monthly refund register
   *                      ({@code ISMONTHLYRETURN = Y})
   */
  static Result aggregate(List<Decl303> winners, boolean monthlyReturn) {
    Result r = new Result();
    List<Decl303> ordered = new ArrayList<>(winners);
    ordered.sort(PERIOD_ORDER);
    r.declarations.addAll(ordered);

    Set<Integer> covered = new TreeSet<>();
    boolean anyMonthly = false;
    for (Decl303 d : ordered) {
      covered.addAll(coveredMonths(d.period));
      anyMonthly |= !d.period.startsWith("T");
    }
    r.periodicity = anyMonthly ? PERIODICITY_MONTHLY : PERIODICITY_QUARTERLY;
    fillMissing(r, covered, anyMonthly);

    Decl303 last = null;
    for (Decl303 d : ordered) {
      if (endMonth(d.period) == LAST_MONTH && !"error".equals(d.source)) {
        last = d;
      }
    }
    r.lastPeriod = last != null ? last.period : null;
    if (last == null) {
      r.warnings.add(warning(W_LAST_PERIOD_MISSING, null));
    }
    reportDraftsAndFailures(r, ordered);

    List<Decl303> usable = new ArrayList<>();
    for (Decl303 d : ordered) {
      if (!"error".equals(d.source)) {
        usable.add(d);
      }
    }
    if (usable.isEmpty()) {
      return r;
    }
    r.territory = last != null && isTerritory(last);

    BigDecimal[] ingresar = zeros(MONTHS + 1);
    BigDecimal[] devolver = zeros(MONTHS + 1);
    BigDecimal box97 = BigDecimal.ZERO;
    BigDecimal box98 = BigDecimal.ZERO;
    BigDecimal yearPool = BigDecimal.ZERO;
    BigDecimal priorApplied = BigDecimal.ZERO;
    BigDecimal priorLeft = priorCarry(usable.get(0));
    r.carryOverKnown = priorLeft != null;
    BigDecimal totalApplied = BigDecimal.ZERO;

    for (Decl303 d : usable) {
      // [78] of this period: the prior-year carry first, then this year's earlier credits.
      BigDecimal applied = get(d.boxes, 78);
      totalApplied = totalApplied.add(applied);
      BigDecimal fromPrior = priorLeft == null ? applied : applied.min(priorLeft);
      if (priorLeft != null) {
        priorLeft = priorLeft.subtract(fromPrior);
      }
      priorApplied = priorApplied.add(fromPrior);
      yearPool = yearPool.subtract(applied.subtract(fromPrior)).max(BigDecimal.ZERO);

      int month = endMonth(d.period);
      boolean isLast = d == last;
      BigDecimal amount = periodResult(d).abs();
      String kind = kindOf(d);
      if (KIND_PAY.equals(kind)) {
        ingresar[month] = ingresar[month].add(amount);
      } else if (KIND_REFUND.equals(kind) && monthlyReturn) {
        devolver[month] = devolver[month].add(amount);
      } else if (KIND_REFUND.equals(kind) && isLast) {
        box98 = box98.add(amount);
      } else if (!KIND_ZERO.equals(kind)) {
        if (KIND_REFUND.equals(kind)) {
          r.warnings.add(warning(W_REFUND_WITHOUT_REGISTER, d.period));
        }
        if (isLast) {
          box97 = box97.add(amount);
        } else {
          yearPool = yearPool.add(amount);
        }
      }
    }
    if (!r.carryOverKnown && totalApplied.signum() > 0) {
      r.warnings.add(warning(W_PRIOR_CARRY_UNKNOWN, null));
    }

    BigDecimal box95 = BigDecimal.ZERO;
    BigDecimal box96 = BigDecimal.ZERO;
    for (int m = 1; m <= MONTHS; m++) {
      box95 = box95.add(ingresar[m]);
      box96 = box96.add(devolver[m]);
      r.inputs.put(INGRESAR_PREFIX + monthCode(m), r2(ingresar[m]));
      if (monthlyReturn) {
        r.inputs.put(DEVOLVER_PREFIX + monthCode(m), r2(devolver[m]));
      }
    }
    String compensationKey = r.territory ? Fiscal390DerivedBoxes.COMMON_TERRITORY_COMPENSATION
        : Fiscal390DerivedBoxes.LIQANUAL_COMPENSATION;
    r.inputs.put(compensationKey, r2(priorApplied));
    r.inputs.put(END_COMPENSATION, r2(yearPool));
    if (last != null) {
      r.inputs.put(TOTAL_LASTPERIOD, r2(box97));
      r.inputs.put(TOTAL_LASTPERIOD_DEVOLVER, r2(box98));
      if (r.territory) {
        putTerritory(r, last);
      }
    }

    r.totals.put(r.territory ? 93 : 85, r2(priorApplied));
    r.totals.put(95, r2(box95));
    r.totals.put(96, r2(box96));
    r.totals.put(97, r2(box97));
    r.totals.put(98, r2(box98));
    r.totals.put(662, r2(yearPool));
    r.expectedResult = r2(box95.subtract(box96).subtract(box97).subtract(box98).subtract(yearPool));
    return r;
  }

  /** The first 303's [110] (prior-year credits pending at the start of the year), or {@code null}. */
  private static BigDecimal priorCarry(Decl303 first) {
    BigDecimal v = first.boxes.get(110);
    return v != null ? v.max(BigDecimal.ZERO) : null;
  }

  private static void fillMissing(Result r, Set<Integer> covered, boolean monthly) {
    if (monthly) {
      for (int m = 1; m <= MONTHS; m++) {
        if (!covered.contains(m)) {
          r.missingPeriods.add(monthCode(m));
        }
      }
    } else {
      for (int q = 1; q <= 4; q++) {
        if (!covered.contains(q * 3)) {
          r.missingPeriods.add("T" + q);
        }
      }
    }
    if (!r.missingPeriods.isEmpty()) {
      r.warnings.add(warning(W_MISSING, String.join(",", r.missingPeriods)));
    }
  }

  private static void reportDraftsAndFailures(Result r, List<Decl303> ordered) {
    List<String> drafts = new ArrayList<>();
    for (Decl303 d : ordered) {
      if ("error".equals(d.source)) {
        r.warnings.add(warning(W_COMPUTE_FAILED, d.period));
      } else if (!d.submitted) {
        drafts.add(d.period);
      }
    }
    if (!drafts.isEmpty()) {
      r.warnings.add(warning(W_DRAFT, String.join(",", drafts)));
    }
  }

  /** The last 303 files to several administrations: any foral percentage [89]-[92] &gt; 0. */
  static boolean isTerritory(Decl303 last) {
    for (int box : FORAL_303_BOXES) {
      if (get(last.boxes, box).signum() > 0) {
        return true;
      }
    }
    return false;
  }

  private static void putTerritory(Result r, Decl303 last) {
    BigDecimal common = last.boxes.containsKey(65) ? last.boxes.get(65) : HUNDRED;
    r.inputs.put(Fiscal390DerivedBoxes.COMMON_TERRITORY, r2(common));
    for (int i = 0; i < FORAL_303_BOXES.length; i++) {
      r.inputs.put(FORAL_390_KEYS[i], r2(get(last.boxes, FORAL_303_BOXES[i])));
    }
  }

  private static BigDecimal[] zeros(int n) {
    BigDecimal[] out = new BigDecimal[n];
    Arrays.fill(out, BigDecimal.ZERO);
    return out;
  }

  private static Map<String, String> warning(String code, String periods) {
    Map<String, String> w = new LinkedHashMap<>();
    w.put("code", code);
    if (periods != null) {
      w.put("periods", periods);
    }
    return w;
  }

  // ── Cuadre ──────────────────────────────────────────────────────────────

  /**
   * The cuadre of {@code annualResult} ([86], or [94] in territory mode) with
   * {@code expectedResult} ({@link Result#expectedResult}): {@code status} {@code ok} /
   * {@code difference} (|difference| &gt; {@link #RECONCILIATION_TOLERANCE}) / {@code no303}
   * (nothing to compare with), and {@code difference = annualResult − expectedResult}. For the
   * incidents phase ("Diferencia con los 303").
   */
  static Map<String, Object> reconcile(BigDecimal annualResult, BigDecimal expectedResult) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (expectedResult == null || annualResult == null) {
      out.put("status", "no303");
      return out;
    }
    BigDecimal diff = r2(annualResult.subtract(expectedResult));
    out.put("status", diff.abs().compareTo(RECONCILIATION_TOLERANCE) > 0 ? "difference" : "ok");
    out.put("difference", diff);
    out.put("tolerance", RECONCILIATION_TOLERANCE);
    return out;
  }
}
