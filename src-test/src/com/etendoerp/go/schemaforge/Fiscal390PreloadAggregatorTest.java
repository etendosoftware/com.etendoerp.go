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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.structure.BaseOBObject;

import com.etendoerp.go.schemaforge.Fiscal390PreloadAggregator.Decl303;
import com.etendoerp.go.schemaforge.Fiscal390PreloadAggregator.Result;

/**
 * Modelo 390 preload from the year's Modelo 303 declarations and the cuadre (ETP-5716).
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal390PreloadAggregator
 * @covers com.etendoerp.go.schemaforge.Fiscal390PreloadSupport
 */
public class Fiscal390PreloadAggregatorTest {

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static Map<Integer, BigDecimal> boxes(Object... kv) {
    Map<Integer, BigDecimal> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((Integer) kv[i], bd(String.valueOf(kv[i + 1])));
    }
    return m;
  }

  /** A submitted 303 whose raw [27] accrued / [29] deductible give the result, plus overrides. */
  private static Decl303 decl(String period, String accrued, String deductible, String tipo,
      Map<Integer, BigDecimal> overrides) {
    Decl303 d = new Decl303("id-" + period, period, "submitted", true, 0);
    d.source = Fiscal390PreloadSupport.SOURCE_SNAPSHOT;
    d.tipo = tipo;
    d.boxes = Fiscal390PreloadAggregator.effectiveBoxes(boxes(27, accrued, 29, deductible),
        overrides);
    return d;
  }

  private static Decl303 decl(String period, String accrued, String deductible, String tipo) {
    return decl(period, accrued, deductible, tipo, Collections.emptyMap());
  }

  private static String in(Result r, String key) {
    BigDecimal v = r.inputs.get(key);
    return v != null ? v.toPlainString() : null;
  }

  private static boolean hasWarning(Result r, String code) {
    return r.warnings.stream().anyMatch(w -> code.equals(w.get("code")));
  }

  // ── effective 303 boxes ─────────────────────────────────────────────

  @Test
  public void testEffectiveBoxesDeriveResultWithOverrides() {
    Map<Integer, BigDecimal> b = Fiscal390PreloadAggregator.effectiveBoxes(
        boxes(27, "300", 29, "100"), boxes(65, "50", 78, "-5", 77, "10"));
    assertEquals(bd("100.00"), b.get(45));
    assertEquals(bd("200.00"), b.get(46));
    assertEquals(bd("100.00"), b.get(66)); // 200 × 50 %
    assertEquals(BigDecimal.ZERO, b.get(78)); // unsigned box floored at 0
    assertEquals(bd("110.00"), b.get(69)); // 66 + 77 − 78
    assertEquals(bd("110.00"), b.get(71));
  }

  /** A rectificativa subtracts the replaced declaration in [70]: [69] stays the full result. */
  @Test
  public void testRectificativaUsesBox69NotBox71() {
    Decl303 d = decl("T1", "500", "100", "I", boxes(70, "300"));
    assertEquals(bd("400.00"), Fiscal390PreloadAggregator.periodResult(d));
    assertEquals(bd("100.00"), d.boxes.get(71));
    assertEquals(Fiscal390PreloadAggregator.KIND_PAY, Fiscal390PreloadAggregator.kindOf(d));
  }

  // ── selection ───────────────────────────────────────────────────────

  @Test
  public void testLatestSubmittedWinsPerPeriod() {
    Decl303 ordinary = new Decl303("a", "T1", "submitted", true, 0);
    Decl303 substitute = new Decl303("b", "T1", "submitted_ack", true, 1);
    Decl303 draftOnTop = new Decl303("c", "T1", "draft", false, 2);
    Decl303 onlyDraftOld = new Decl303("d", "T2", "draft", false, 0);
    Decl303 onlyDraftNew = new Decl303("e", "T2", "draft", false, 1);
    Decl303 invalid = new Decl303("f", "0A", "draft", false, 0);

    List<Decl303> winners = Fiscal390PreloadAggregator.selectWinners(
        Arrays.asList(draftOnTop, onlyDraftOld, ordinary, substitute, onlyDraftNew, invalid));

    assertEquals(2, winners.size());
    assertSame(substitute, winners.get(0));
    assertEquals(2, substitute.superseded);
    assertSame(onlyDraftNew, winners.get(1));
  }

  // ── aggregation ─────────────────────────────────────────────────────

  /** Quarterly year: payments to the quarter's closing month, last-period credit to [97]. */
  @Test
  public void testQuarterlyYearPreload() {
    Result r = Fiscal390PreloadAggregator.aggregate(Arrays.asList(
        decl("T4", "0", "20", "C"), decl("T1", "100", "0", "I"), decl("T2", "50", "0", "U"),
        decl("T3", "0", "30", "C")), false);

    assertEquals(Fiscal390PreloadAggregator.PERIODICITY_QUARTERLY, r.periodicity);
    assertTrue(r.missingPeriods.isEmpty());
    assertEquals("T4", r.lastPeriod);
    assertEquals("100.00", in(r, "INGRESAR_03"));
    assertEquals("50.00", in(r, "INGRESAR_06"));
    assertEquals("0.00", in(r, "INGRESAR_09"));
    assertEquals("0.00", in(r, "INGRESAR_12"));
    assertNull(in(r, "DEVOLVER_12"));
    assertEquals("20.00", in(r, "TOTAL_LASTPERIOD"));
    assertEquals("0.00", in(r, "TOTAL_LASTPERIOD_DEVOLVER"));
    assertEquals("30.00", in(r, "LIQANUAL_ENDCOMPENSATION")); // T3 credit, never applied
    assertEquals("0.00", in(r, "LIQANUAL_COMPENSATION"));
    assertEquals(bd("150.00"), r.totals.get(95));
    assertEquals(bd("100.00"), r.expectedResult); // 150 − 20 − 30
    assertFalse(r.territory);
    assertFalse(hasWarning(r, Fiscal390PreloadAggregator.W_MISSING));
  }

  /** Monthly register: every refund (the last one included) is a DEVOLVER_xx, [98] stays 0. */
  @Test
  public void testMonthlyRegisterRefunds() {
    List<Decl303> months = new ArrayList<>();
    for (int m = 1; m <= 12; m++) {
      String code = Fiscal390PreloadAggregator.monthCode(m);
      months.add(m == 5 || m == 12 ? decl(code, "0", "40", "D") : decl(code, "10", "0", "I"));
    }
    Result r = Fiscal390PreloadAggregator.aggregate(months, true);

    assertEquals(Fiscal390PreloadAggregator.PERIODICITY_MONTHLY, r.periodicity);
    assertEquals("40.00", in(r, "DEVOLVER_05"));
    assertEquals("40.00", in(r, "DEVOLVER_12"));
    assertEquals("0.00", in(r, "TOTAL_LASTPERIOD_DEVOLVER"));
    assertEquals(bd("100.00"), r.totals.get(95));
    assertEquals(bd("80.00"), r.totals.get(96));
    assertEquals(bd("20.00"), r.expectedResult);
  }

  /** Without the monthly register only the last period can be a refund ([98]). */
  @Test
  public void testRefundWithoutRegister() {
    Result r = Fiscal390PreloadAggregator.aggregate(Arrays.asList(
        decl("T1", "0", "15", "D"), decl("T2", "0", "0", "N"), decl("T3", "10", "0", "I"),
        decl("T4", "0", "25", "D")), false);

    assertEquals("25.00", in(r, "TOTAL_LASTPERIOD_DEVOLVER"));
    assertEquals("0.00", in(r, "TOTAL_LASTPERIOD"));
    assertEquals("15.00", in(r, "LIQANUAL_ENDCOMPENSATION")); // T1 refund read as a credit
    assertTrue(hasWarning(r, Fiscal390PreloadAggregator.W_REFUND_WITHOUT_REGISTER));
    assertEquals(bd("-30.00"), r.expectedResult); // 10 − 25 − 15
  }

  /**
   * [85] / [662]: each [78] consumes the prior-year carry ([110] of the first 303) first. The
   * expected result equals the annual result Σ[66] − [85] (no [659]/[68]/[108]).
   */
  @Test
  public void testCompensationsOldestFirst() {
    Result r = Fiscal390PreloadAggregator.aggregate(Arrays.asList(
        decl("T1", "200", "0", "I", boxes(110, "100", 78, "60")),
        decl("T2", "0", "50", "C"),
        decl("T3", "200", "0", "I", boxes(78, "70")),
        decl("T4", "100", "0", "I")), false);

    assertTrue(r.carryOverKnown);
    assertEquals("100.00", in(r, "LIQANUAL_COMPENSATION")); // 60 + 40
    assertEquals("20.00", in(r, "LIQANUAL_ENDCOMPENSATION")); // 50 − 30
    assertEquals(bd("370.00"), r.totals.get(95)); // 140 + 130 + 100
    BigDecimal annual = bd("450").subtract(bd("100")); // Σ[66] − [85]
    assertEquals(annual.setScale(2), r.expectedResult);
    assertFalse(hasWarning(r, Fiscal390PreloadAggregator.W_PRIOR_CARRY_UNKNOWN));
  }

  /** No [110] on the first 303: every [78] is taken as prior-year, and it is reported. */
  @Test
  public void testUnknownCarryTakesEveryCompensationAsPriorYear() {
    Result r = Fiscal390PreloadAggregator.aggregate(Arrays.asList(
        decl("T1", "0", "50", "C"), decl("T2", "100", "0", "I", boxes(78, "50")),
        decl("T3", "0", "0", "N"), decl("T4", "0", "0", "N")), false);

    assertFalse(r.carryOverKnown);
    assertEquals("50.00", in(r, "LIQANUAL_COMPENSATION"));
    assertEquals("50.00", in(r, "LIQANUAL_ENDCOMPENSATION"));
    assertTrue(hasWarning(r, Fiscal390PreloadAggregator.W_PRIOR_CARRY_UNKNOWN));
    // the identity still holds: 50 (T2) − 50 ([662]) = Σ[66] (−50 + 100) − [85] (50)
    assertEquals(bd("0.00"), r.expectedResult);
  }

  @Test
  public void testMissingPeriodsAndNoLastPeriod() {
    Result r = Fiscal390PreloadAggregator.aggregate(Arrays.asList(
        decl("T1", "10", "0", "I"), decl("T3", "10", "0", "I")), false);

    assertEquals(Arrays.asList("T2", "T4"), r.missingPeriods);
    assertNull(r.lastPeriod);
    assertTrue(hasWarning(r, Fiscal390PreloadAggregator.W_MISSING));
    assertTrue(hasWarning(r, Fiscal390PreloadAggregator.W_LAST_PERIOD_MISSING));
    assertNull(in(r, "TOTAL_LASTPERIOD")); // unknown, not 0
    assertNull(in(r, "TOTAL_LASTPERIOD_DEVOLVER"));
    assertEquals("10.00", in(r, "INGRESAR_09"));
  }

  /** A year that switched to monthly: a quarter covers its three months. */
  @Test
  public void testMixedPeriodicityCoverage() {
    List<Decl303> decls = new ArrayList<>(Arrays.asList(decl("T1", "10", "0", "I")));
    for (int m = 4; m <= 11; m++) {
      decls.add(decl(Fiscal390PreloadAggregator.monthCode(m), "1", "0", "I"));
    }
    Result r = Fiscal390PreloadAggregator.aggregate(decls, false);
    assertEquals(Fiscal390PreloadAggregator.PERIODICITY_MONTHLY, r.periodicity);
    assertEquals(Collections.singletonList("12"), r.missingPeriods);
  }

  @Test
  public void testNoDeclarations() {
    Result r = Fiscal390PreloadAggregator.aggregate(Collections.emptyList(), false);
    assertEquals(Arrays.asList("T1", "T2", "T3", "T4"), r.missingPeriods);
    assertTrue(r.inputs.isEmpty());
    assertNull(r.expectedResult);
  }

  /** Territory percentages come from the LAST 303; the prior-year offset goes to [93]. */
  @Test
  public void testTerritoryFromLastPeriod() {
    Result r = Fiscal390PreloadAggregator.aggregate(Arrays.asList(
        decl("T1", "0", "0", "N", boxes(89, "30")), decl("T2", "0", "0", "N"),
        decl("T3", "0", "0", "N"), decl("T4", "100", "0", "I", boxes(65, "60", 92, "40"))),
        false);

    assertTrue(r.territory);
    assertEquals("60.00", in(r, "COMMON_TERRITORY"));
    assertEquals("40.00", in(r, "NAVARRA"));
    assertEquals("0.00", in(r, "ALAVA"));
    assertEquals("0.00", in(r, "COMMON_TERRITORY_COMPENSATION"));
    assertNull(in(r, "LIQANUAL_COMPENSATION"));
    assertEquals("60.00", in(r, "INGRESAR_12")); // 100 × 60 % (state share)
  }

  @Test
  public void testDraftsAndFailedComputesAreReported() {
    Decl303 draft = decl("T1", "10", "0", "I");
    Decl303 asDraft = new Decl303(draft.id, "T1", "draft", false, 0);
    asDraft.source = Fiscal390PreloadSupport.SOURCE_LIVE;
    asDraft.boxes = draft.boxes;
    Decl303 failed = new Decl303("x", "T2", "draft", false, 0);
    failed.source = Fiscal390PreloadSupport.SOURCE_ERROR;

    Result r = Fiscal390PreloadAggregator.aggregate(Arrays.asList(asDraft, failed), false);

    assertTrue(hasWarning(r, Fiscal390PreloadAggregator.W_DRAFT));
    assertTrue(hasWarning(r, Fiscal390PreloadAggregator.W_COMPUTE_FAILED));
    assertEquals(Arrays.asList("T3", "T4"), r.missingPeriods); // failed T2 still filed
    assertEquals(bd("10.00"), r.totals.get(95));
  }

  // ── cuadre ──────────────────────────────────────────────────────────

  @Test
  public void testReconcileIsExactAtCentPrecision() {
    // No tolerance (the AEAT publishes none): both sides rounded to cents, then equal.
    assertEquals("ok", Fiscal390PreloadAggregator.reconcile(bd("100.004"), bd("100.00")).get("status"));
    Map<String, Object> diff = Fiscal390PreloadAggregator.reconcile(bd("99.99"), bd("100.00"));
    assertEquals("difference", diff.get("status"));
    assertEquals(bd("-0.01"), diff.get("difference"));
    assertFalse(diff.containsKey("tolerance"));
    assertEquals("no303", Fiscal390PreloadAggregator.reconcile(bd("1"), null).get("status"));
  }

  // ── support: parsing and payload ────────────────────────────────────

  @Test
  public void testBoxMapParsesNumbersAndSkipsGarbage() throws Exception {
    Map<Integer, BigDecimal> m = Fiscal390PreloadSupport.boxMap(new JSONObject(
        "{\"78\":12.5,\"110\":\"1234,5\",\"65\":\"\",\"x\":\"1\",\"70\":\"abc\",\"77\":null}"));
    assertEquals(bd("12.5"), m.get(78));
    assertEquals(bd("1234.5"), m.get(110));
    assertEquals(2, m.size());
    assertTrue(Fiscal390PreloadSupport.boxMap(null).isEmpty());
  }

  /** A submitted 303 uses its snapshot boxes; a draft is computed live; overrides apply to both. */
  @Test
  public void testResolveBoxesSnapshotVersusLive() throws Exception {
    Fiscal390PreloadSupport support = new Fiscal390PreloadSupport(mock(Fiscal390BoxesHandler.class));
    support.live303 = (org, year, period) -> boxes(27, "40");

    BaseOBObject submittedRow = mock(BaseOBObject.class);
    when(submittedRow.get(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT))
        .thenReturn("{\"boxes\":{\"27\":\"100.00\"}}");
    when(submittedRow.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA)).thenReturn(
        "{\"identification\":{\"tipo_declaracion\":\"U\"},\"manualOverrides\":{\"78\":10}}");
    Decl303 submitted = new Decl303("s", "T1", "submitted", true, 0);
    support.resolveBoxes(submitted, submittedRow, "org", 2026);
    assertEquals(Fiscal390PreloadSupport.SOURCE_SNAPSHOT, submitted.source);
    assertEquals("U", submitted.tipo);
    assertEquals(bd("90.00"), submitted.boxes.get(69));

    BaseOBObject draftRow = mock(BaseOBObject.class);
    Decl303 draft = new Decl303("d", "T2", "draft", false, 0);
    support.resolveBoxes(draft, draftRow, "org", 2026);
    assertEquals(Fiscal390PreloadSupport.SOURCE_LIVE, draft.source);
    assertNull(draft.tipo);
    assertEquals(bd("40.00"), draft.boxes.get(69));
  }

  @Test
  public void testResolveBoxesReportsAFailedCompute() {
    Fiscal390BoxesHandler handler = mock(Fiscal390BoxesHandler.class);
    when(handler.userMessage(any())).thenReturn("No TaxReport");
    Fiscal390PreloadSupport support = new Fiscal390PreloadSupport(handler);
    support.live303 = (org, year, period) -> {
      throw new IllegalStateException("boom");
    };
    Decl303 d = new Decl303("d", "T2", "draft", false, 0);
    support.resolveBoxes(d, mock(BaseOBObject.class), "org", 2026);
    assertEquals(Fiscal390PreloadSupport.SOURCE_ERROR, d.source);
    assertEquals("No TaxReport", d.error);
  }

  @Test
  public void testPayloadJson() throws Exception {
    Decl303 t4 = decl("T4", "0", "20", "C");
    t4.superseded = 1;
    Result r = Fiscal390PreloadAggregator.aggregate(Arrays.asList(
        decl("T1", "100", "0", "I"), decl("T2", "0", "0", "N"), decl("T3", "0", "0", "N"), t4),
        false);

    JSONObject json = Fiscal390PreloadSupport.toJson(r, 2026, false);

    assertEquals(2026, json.getInt("year"));
    assertEquals("quarterly", json.getString("periodicity"));
    assertEquals(4, json.getJSONArray("declarations").length());
    JSONObject last = json.getJSONArray("declarations").getJSONObject(3);
    assertEquals("T4", last.getString("period"));
    assertEquals("12", last.getString("month"));
    assertEquals("compensate", last.getString("kind"));
    assertEquals("-20.00", last.getString("result"));
    assertEquals(1, last.getInt("superseded"));
    assertEquals("20.00", json.getJSONObject("inputs").getString("TOTAL_LASTPERIOD"));
    assertEquals("100.00", json.getJSONObject("totals").getString("95"));
    assertEquals("80.00", json.getString("expectedResult"));
    assertFalse(json.has("tolerance"));
    assertEquals("T4", json.getString("lastPeriod"));
  }

  // ── applyPreload: the 303 preload applied server-side (ETP-5716, AEAT PAR016) ──

  /** An empty / never-preloaded input takes the 303 value; zero clears; others stay. */
  @Test
  public void testApplyPreloadFillsUntouchedInputs() {
    Map<String, String> inputs = new HashMap<>();
    inputs.put("LIQANUAL_VAT_IMPORT", "7");      // not a preload key: untouched
    inputs.put("INGRESAR_03", "0");              // zero counts as empty
    Map<String, BigDecimal> fresh = new HashMap<>();
    fresh.put("INGRESAR_03", new BigDecimal("300"));
    fresh.put("TOTAL_LASTPERIOD", new BigDecimal("12.5"));
    fresh.put("LIQANUAL_ENDCOMPENSATION", BigDecimal.ZERO);
    Map<String, String> out = Fiscal390PreloadAggregator.applyPreload(inputs, null, fresh);
    assertEquals("300.00", out.get("INGRESAR_03"));
    assertEquals("12.50", out.get("TOTAL_LASTPERIOD"));
    assertFalse(out.containsKey("LIQANUAL_ENDCOMPENSATION"));
    assertEquals("7", out.get("LIQANUAL_VAT_IMPORT"));
  }

  /** PAR016 repro: aeatPreloaded {} saved before the 303s existed still takes them. */
  @Test
  public void testApplyPreloadWithAnEmptyPreviousPreload() {
    Map<String, BigDecimal> fresh = Map.of("INGRESAR_03", new BigDecimal("300"));
    Map<String, String> out = Fiscal390PreloadAggregator.applyPreload(
        Map.of("ISSUPPLEMENTARY", "N"), Collections.emptyMap(), fresh);
    assertEquals("300.00", out.get("INGRESAR_03"));
  }

  /** A value still equal to the previous preload follows the new 303s; a user edit wins. */
  @Test
  public void testApplyPreloadKeepsUserOverrides() {
    Map<String, String> inputs = new HashMap<>();
    inputs.put("INGRESAR_03", "300.00");   // = previous preload → follows the 303s
    inputs.put("INGRESAR_06", "999");      // edited by the user → kept
    inputs.put("INGRESAR_09", "");         // cleared by the user although preloaded → kept empty
    Map<String, String> previous = Map.of("INGRESAR_03", "300", "INGRESAR_06", "600",
        "INGRESAR_09", "900");
    Map<String, BigDecimal> fresh = Map.of("INGRESAR_03", new BigDecimal("310"),
        "INGRESAR_06", new BigDecimal("610"), "INGRESAR_09", new BigDecimal("910"));
    Map<String, String> out = Fiscal390PreloadAggregator.applyPreload(inputs, previous, fresh);
    assertEquals("310.00", out.get("INGRESAR_03"));
    assertEquals("999", out.get("INGRESAR_06"));
    assertEquals("", out.get("INGRESAR_09"));
    assertSame(null, Fiscal390PreloadAggregator.applyPreload(null, null, null).get("x"));
  }
}
