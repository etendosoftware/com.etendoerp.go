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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.taxreportlauncher.TaxReportParameter;

/**
 * Unit tests for the Modelo 390 box computation (ETP-5716): the walk over the classic tax-report
 * configuration ({@link Fiscal390BoxesCalculator}, with a fake {@link
 * Fiscal390BoxesCalculator.Engine} instead of the database), the box layout table
 * ({@link Fiscal390BoxLayout}) and the computed boxes 47/64/65/84-96/108
 * ({@link Fiscal390DerivedBoxes}), whose formulas mirror classic {@code AEAT390Report2014} and
 * {@code AEAT390Report2021#generateTXTPage6}.
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal390BoxesCalculator
 * @covers com.etendoerp.go.schemaforge.Fiscal390DerivedBoxes
 * @covers com.etendoerp.go.schemaforge.Fiscal390BoxLayout
 */
public class Fiscal390BoxesCalculatorTest {

  // ── fake engine ─────────────────────────────────────────────────────

  /** A configured output parameter with its taxes and the amounts each rate yields. */
  private static final class Param {
    final TaxReportParameter parameter = mock(TaxReportParameter.class);
    final List<TaxRate> taxes = new ArrayList<>();
    final Map<TaxRate, BigDecimal[]> amounts = new HashMap<>();
  }

  private static final class FakeEngine implements Fiscal390BoxesCalculator.Engine {
    final Map<String, List<Param>> groups = new HashMap<>();

    Param add(String group, String key, String element, boolean split, boolean total) {
      Param p = new Param();
      when(p.parameter.getSearchKey()).thenReturn(key);
      when(p.parameter.getAeat390XmlElement()).thenReturn(element);
      when(p.parameter.isAeat390Issplit()).thenReturn(split);
      when(p.parameter.isAeat390Istotal()).thenReturn(total);
      when(p.parameter.isAeat390IsBIAndCuota()).thenReturn(false);
      groups.computeIfAbsent(group, g -> new ArrayList<>()).add(p);
      return p;
    }

    @Override
    public List<TaxReportParameter> outputParameters(String groupKey) {
      List<TaxReportParameter> out = new ArrayList<>();
      for (Param p : groups.getOrDefault(groupKey, Collections.emptyList())) {
        out.add(p.parameter);
      }
      return out;
    }

    private Param find(TaxReportParameter parameter) {
      for (List<Param> ps : groups.values()) {
        for (Param p : ps) {
          if (p.parameter == parameter) {
            return p;
          }
        }
      }
      throw new IllegalStateException("unknown parameter");
    }

    @Override
    public List<TaxRate> taxes(TaxReportParameter parameter) {
      return find(parameter).taxes;
    }

    @Override
    public BigDecimal[] amounts(TaxReportParameter parameter, List<TaxRate> taxes) {
      Param p = find(parameter);
      BigDecimal base = BigDecimal.ZERO;
      BigDecimal cuota = BigDecimal.ZERO;
      for (TaxRate t : taxes) {
        BigDecimal[] a = p.amounts.get(t);
        base = base.add(a[0]);
        cuota = cuota.add(a[1]);
      }
      return new BigDecimal[] { base, cuota };
    }
  }

  private static void tax(Param p, String rate, String base, String cuota) {
    TaxRate t = mock(TaxRate.class);
    when(t.getRate()).thenReturn(new BigDecimal(rate));
    p.taxes.add(t);
    p.amounts.put(t, new BigDecimal[] { new BigDecimal(base), new BigDecimal(cuota) });
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static void assertBox(Fiscal390BoxesCalculator.Result r, int box, String expected) {
    assertEquals("box " + box, bd(expected).setScale(2), r.boxes.get(box));
  }

  private static Map<String, BigDecimal> inputs(String... kv) {
    Map<String, BigDecimal> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], bd(kv[i + 1]));
    }
    return m;
  }

  /** A small but complete year: sales by rate, totals, RE, deductions, volume. */
  private static FakeEngine typicalYear() {
    FakeEngine e = new FakeEngine();
    Param ordinary = e.add(Fiscal390BoxesCalculator.VAT_SALES, "VAT_SALES_ORDINARY",
        "RegOrdinario", true, false);
    tax(ordinary, "21", "1000", "210");
    tax(ordinary, "21", "500", "105"); // two taxes of the same rate add up
    tax(ordinary, "10", "200", "20");
    Param re = e.add(Fiscal390BoxesCalculator.VAT_SALES, "VAT_SALES_EC", "RecargoEquivalencia",
        true, false);
    tax(re, "5.2", "100", "5.20");
    Param total = e.add(Fiscal390BoxesCalculator.VAT_SALES, "VAT_SALES_TOTAL",
        "TotalBasesyCuotasIVA", false, false);
    tax(total, "21", "1700", "335");
    Param modRe = e.add(Fiscal390BoxesCalculator.VAT_SALES, "VAT_SALES_EC_MOD_BASESYCUOTAS",
        "ModRecargoEquivalencia", false, false);
    tax(modRe, "5.2", "-10", "-0.52");

    Param purchase = e.add(Fiscal390BoxesCalculator.VAT_PURCHASE, "VAT_PURCHASE_NORMAL",
        "OpInterioresBienesServiciosCorrientes", true, true);
    tax(purchase, "21", "400", "84");
    tax(purchase, "10", "100", "10");
    Param rectif = e.add(Fiscal390BoxesCalculator.VAT_PURCHASE, "VAT_PURCHASE_MOD_BASESYCUOTAS",
        "RectifDeducciones", false, false);
    tax(rectif, "21", "-50", "-10.50");

    Param general = e.add(Fiscal390BoxesCalculator.VOLUME_OPERATIONS, "GENERAL_OPERATIONS",
        "OpRegGeneral", false, false);
    tax(general, "21", "1700", "335");
    Param property = e.add(Fiscal390BoxesCalculator.VOLUME_OPERATIONS, "NON_COMMON_OPERATIONS",
        "EntregasBienesInmuebles", false, false);
    tax(property, "21", "300", "63");
    return e;
  }

  // ── invoice boxes ───────────────────────────────────────────────────

  /** Split parameters land one base/cuota pair per rate, same-rate taxes summed. */
  @Test
  public void testSplitParameterPlacesEachRateInItsBoxes() {
    Fiscal390BoxesCalculator.Result r =
        Fiscal390BoxesCalculator.compute(typicalYear(), inputs());

    assertBox(r, 5, "1500");
    assertBox(r, 6, "315");
    assertBox(r, 3, "200");
    assertBox(r, 4, "20");
    assertBox(r, 601, "100");
    assertBox(r, 602, "5.20");
    assertTrue(r.warnings.isEmpty());
  }

  /** A split purchase element with aeat390Istotal also gets its Total pair. */
  @Test
  public void testSplitPurchaseWithTotalPlacesTotalBoxes() {
    Fiscal390BoxesCalculator.Result r =
        Fiscal390BoxesCalculator.compute(typicalYear(), inputs());

    assertBox(r, 605, "400");
    assertBox(r, 606, "84");
    assertBox(r, 603, "100");
    assertBox(r, 604, "10");
    assertBox(r, 48, "500");
    assertBox(r, 49, "94");
    assertBox(r, 639, "-50");
    assertBox(r, 62, "-10.50");
  }

  /**
   * [47] = [34] + RE cuotas + [44]; [64] = split Total cuota + non-split cuota; [65] = [47]-[64].
   */
  @Test
  public void testTotalsFollowClassicFormulas() {
    Fiscal390BoxesCalculator.Result r =
        Fiscal390BoxesCalculator.compute(typicalYear(), inputs());

    assertBox(r, 47, "339.68"); // 335 + 5.20 - 0.52
    assertBox(r, 64, "83.50");  // 94 - 10.50
    assertBox(r, 65, "256.18");
  }

  /** [64] ignores a split element whose parameter is not aeat390Istotal (classic reads Total). */
  @Test
  public void testSplitPurchaseWithoutTotalDoesNotDeduct() {
    FakeEngine e = new FakeEngine();
    Param purchase = e.add(Fiscal390BoxesCalculator.VAT_PURCHASE, "P", 
        "OpInterioresBienesServiciosCorrientes", true, false);
    tax(purchase, "21", "400", "84");

    Fiscal390BoxesCalculator.Result r = Fiscal390BoxesCalculator.compute(e, inputs());

    assertBox(r, 606, "84");
    assertNull(r.boxes.get(49));
    assertBox(r, 64, "0");
  }

  /** VAT_SALES_MOD_BASESYCUOTAS and _2 share the ModBasesyCuotas element: they add up. */
  @Test
  public void testParametersSharingAnElementAddUp() {
    FakeEngine e = new FakeEngine();
    tax(e.add(Fiscal390BoxesCalculator.VAT_SALES, "VAT_SALES_MOD_BASESYCUOTAS",
        "ModBasesyCuotas", false, false), "21", "-100", "-21");
    tax(e.add(Fiscal390BoxesCalculator.VAT_SALES, "VAT_SALES_MOD_BASESYCUOTAS_2",
        "ModBasesyCuotas", false, false), "21", "40", "8.40");

    Fiscal390BoxesCalculator.Result r = Fiscal390BoxesCalculator.compute(e, inputs());

    assertBox(r, 29, "-60");
    assertBox(r, 30, "-12.60");
  }

  /** A rate classic cannot represent is reported, never placed nor totalled. */
  @Test
  public void testUnsupportedRateIsReportedAndLeftOut() {
    FakeEngine e = new FakeEngine();
    Param p = e.add(Fiscal390BoxesCalculator.VAT_PURCHASE, "VAT_PURCHASE_NORMAL",
        "OpInterioresBienesServiciosCorrientes", true, true);
    tax(p, "3", "100", "3");
    tax(p, "21", "100", "21");

    Fiscal390BoxesCalculator.Result r = Fiscal390BoxesCalculator.compute(e, inputs());

    assertEquals(1, r.warnings.size());
    assertEquals(Fiscal390BoxesCalculator.WARNING_UNSUPPORTED_RATE, r.warnings.get(0).get("code"));
    assertEquals("VAT_PURCHASE_NORMAL", r.warnings.get(0).get("parameter"));
    assertEquals("3.00", r.warnings.get(0).get("rate"));
    assertBox(r, 49, "21");
  }

  /** A supported rate with no box for that element is reported but kept in the totals. */
  @Test
  public void testRateWithoutBoxIsReportedButTotalled() {
    FakeEngine e = new FakeEngine();
    Param p = e.add(Fiscal390BoxesCalculator.VAT_PURCHASE, "P",
        "OpInterioresBienesServiciosCorrientes", true, true);
    tax(p, "1", "100", "1"); // Tipo1 is valid in classic but not printed for this element

    Fiscal390BoxesCalculator.Result r = Fiscal390BoxesCalculator.compute(e, inputs());

    assertEquals(Fiscal390BoxesCalculator.WARNING_NO_BOX, r.warnings.get(0).get("code"));
    assertBox(r, 49, "1");
  }

  /** Zero-amount rates and parameters with no taxes produce no box. */
  @Test
  public void testZeroAmountsAndEmptyParametersAreSkipped() {
    FakeEngine e = new FakeEngine();
    tax(e.add(Fiscal390BoxesCalculator.VAT_SALES, "A", "RegOrdinario", true, false),
        "21", "0", "0");
    e.add(Fiscal390BoxesCalculator.VAT_SALES, "B", "IVAdevengadoInversionSP", false, false);

    Fiscal390BoxesCalculator.Result r = Fiscal390BoxesCalculator.compute(e, inputs());

    assertNull(r.boxes.get(5));
    assertNull(r.boxes.get(27));
  }

  /** Volume: base only, [106]/[107] subtracted in [108], a negative base dropped. */
  @Test
  public void testVolumeOfOperationsAndBox108() {
    FakeEngine e = typicalYear();
    tax(e.add(Fiscal390BoxesCalculator.VOLUME_OPERATIONS, "SALES_EU_EXEMPT",
        "EntregasIntracomunitariasExentas", false, false), "0", "-80", "0");

    Fiscal390BoxesCalculator.Result r = Fiscal390BoxesCalculator.compute(e,
        inputs("390OPSUJETASCONOSS", "10", "390OPSINTRAGRUPO", "20", "390REAGYP", "30",
            "390USED_GOODS", "40", "390TRAVEL_AGENCY", "50", "390OTHER_UEMENBER", "999"));

    assertBox(r, 99, "1700");
    assertBox(r, 106, "300");
    assertNull("negative base is dropped, as in classic", r.boxes.get(103));
    // 1700 - 300 + 10 + 20 + 30 + 40 + 50; [112] is echoed but not added
    assertBox(r, 108, "1550");
    assertBox(r, 112, "999");
  }

  /** BI-and-cuota taxable parameters place the TipoX pair. */
  @Test
  public void testTaxableAmountWithBiAndCuota() {
    FakeEngine e = new FakeEngine();
    Param p = e.add(Fiscal390BoxesCalculator.SPECIFIC_OPERATIONS, "SP_CASHVAT_SALES",
        "EntregasCriterioCajaBase", false, false);
    when(p.parameter.isAeat390IsBIAndCuota()).thenReturn(true);
    tax(p, "21", "100", "21");

    Fiscal390BoxesCalculator.Result r = Fiscal390BoxesCalculator.compute(e, inputs());

    assertBox(r, 654, "100");
    assertBox(r, 655, "21");
  }

  // ── derived boxes ───────────────────────────────────────────────────

  /** Liquidation mode: [84] = 65+658-661, [86] = 84+659-85, inputs echoed. */
  @Test
  public void testLiquidationModeBoxes84To86() {
    Fiscal390BoxesCalculator.Result r = Fiscal390BoxesCalculator.compute(typicalYear(),
        inputs("LIQANUAL_VAT_LAW_REGULARIZATION", "10", "LIQANUAL_NoCurrentTypeAMOUNT", "6.18",
            "LIQANUAL_VAT_IMPORT", "40", "LIQANUAL_COMPENSATION", "100"));

    assertEquals(Fiscal390DerivedBoxes.MODE_LIQUIDATION, r.mode);
    assertBox(r, 84, "260");   // 256.18 + 10 - 6.18
    assertBox(r, 85, "100");
    assertBox(r, 86, "200");   // 260 + 40 - 100
    assertBox(r, 658, "10");
    assertBox(r, 659, "40");
    assertBox(r, 661, "6.18");
    assertNull(r.boxes.get(92));
  }

  /** [85] is only printed from 0.01 up, as classic's generateLiqAnual. */
  @Test
  public void testCompensationBelowOneCentIsNotPrinted() {
    Map<Integer, BigDecimal> boxes = new HashMap<>();
    Fiscal390DerivedBoxes.apply(boxes, new Fiscal390DerivedBoxes.InvoiceFigures(),
        inputs("LIQANUAL_COMPENSATION", "0.001"));

    assertNull(boxes.get(85));
  }

  /** Territory mode: [84] = 65+658, [92] = 84*87/100, [94] = 92+659-93; no [85]/[86]. */
  @Test
  public void testTerritoryModeBoxes84To94() {
    Fiscal390BoxesCalculator.Result r = Fiscal390BoxesCalculator.compute(typicalYear(),
        inputs("COMMON_TERRITORY", "60", "NAVARRA", "40", "LIQANUAL_VAT_LAW_REGULARIZATION",
            "10", "LIQANUAL_NoCurrentTypeAMOUNT", "6.18", "LIQANUAL_VAT_IMPORT", "40",
            "COMMON_TERRITORY_COMPENSATION", "5"));

    assertEquals(Fiscal390DerivedBoxes.MODE_TERRITORY, r.mode);
    assertBox(r, 84, "266.18");  // 256.18 + 10, no 661 subtraction here
    assertBox(r, 87, "60");
    assertBox(r, 91, "40");
    assertBox(r, 92, "159.71");  // 266.18 * 60 / 100 = 159.708 -> HALF_UP
    assertBox(r, 93, "5");
    assertBox(r, 94, "194.71");  // 159.71 + 40 - 5
    assertNull(r.boxes.get(85));
    assertNull(r.boxes.get(86));
  }

  /** Any positive territory percentage switches the mode; zeros do not. */
  @Test
  public void testTerritoryModeDetection() {
    assertFalse(Fiscal390DerivedBoxes.isTerritoryMode(inputs("COMMON_TERRITORY", "0")));
    assertTrue(Fiscal390DerivedBoxes.isTerritoryMode(inputs("ALAVA", "1")));
  }

  /** [95]/[96] sum the monthly results; [97]/[98]/[662] are echoed. */
  @Test
  public void testResultsOfTheYear() {
    Map<Integer, BigDecimal> boxes = new HashMap<>();
    Fiscal390DerivedBoxes.apply(boxes, new Fiscal390DerivedBoxes.InvoiceFigures(),
        inputs("INGRESAR_01", "10", "INGRESAR_12", "15.5", "DEVOLVER_03", "7",
            "TOTAL_LASTPERIOD", "3", "TOTAL_LASTPERIOD_DEVOLVER", "4",
            "LIQANUAL_ENDCOMPENSATION", "9"));

    assertEquals(bd("25.50"), boxes.get(95));
    assertEquals(bd("7.00"), boxes.get(96));
    assertEquals(bd("3.00"), boxes.get(97));
    assertEquals(bd("4.00"), boxes.get(98));
    assertEquals(bd("9.00"), boxes.get(662));
  }

  /** With no TotalBasesyCuotasIVA figure, [47] is the RE cuotas only (classic NPE fallback). */
  @Test
  public void testBox47WithoutTotalBasesIsRecargoOnly() {
    Fiscal390DerivedBoxes.InvoiceFigures f = new Fiscal390DerivedBoxes.InvoiceFigures();
    f.recargoCuotas = bd("5.20");
    f.modRecargoCuota = bd("-1");
    Map<Integer, BigDecimal> boxes = new HashMap<>();
    Fiscal390DerivedBoxes.apply(boxes, f, inputs());

    assertEquals(bd("5.20"), boxes.get(47));
  }

  /** Inputs: decimal comma accepted, blanks and unknown keys dropped. */
  @Test
  public void testParseInputs() {
    Map<String, String> raw = new LinkedHashMap<>();
    raw.put("LIQANUAL_VAT_IMPORT", "1.234,5".replace(".", ""));
    raw.put("COMMON_TERRITORY", " ");
    raw.put("ISSUPPLEMENTARY", "Y");

    Map<String, BigDecimal> parsed = Fiscal390DerivedBoxes.parseInputs(raw);

    assertEquals(Collections.singletonMap("LIQANUAL_VAT_IMPORT", bd("1234.5")), parsed);
  }

  /** A non-numeric amount is a caller error naming the input. */
  @Test
  public void testParseInputsRejectsNonNumeric() {
    try {
      Fiscal390DerivedBoxes.parseInputs(Collections.singletonMap("INGRESAR_01", "abc"));
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("INGRESAR_01"));
    }
  }

  // ── layout ──────────────────────────────────────────────────────────

  /** Spot checks against classic's TXT writers (AEAT390Report2015..2025). */
  @Test
  public void testLayoutMatchesClassicTxtWriters() {
    assertEquals(Integer.valueOf(1), Fiscal390BoxLayout.box("RegOrdinario", "Tipo4", "BI"));
    assertEquals(Integer.valueOf(6), Fiscal390BoxLayout.box("RegOrdinario", "Tipo21", "Cuota"));
    assertEquals(Integer.valueOf(700), Fiscal390BoxLayout.box("RegOrdinario", "Tipo0", "BI"));
    assertEquals(Integer.valueOf(667), Fiscal390BoxLayout.box("RegOrdinario", "Tipo2", "BI"));
    assertEquals(Integer.valueOf(632),
        Fiscal390BoxLayout.box("AdqIntracomunitariasBienesInversion", "Tipo10", "Cuota"));
    assertEquals(Integer.valueOf(62), Fiscal390BoxLayout.box("RectifDeducciones", "TipoX", "Cuota"));
    assertEquals(Integer.valueOf(99), Fiscal390BoxLayout.box("OpRegGeneral"));
    assertNull(Fiscal390BoxLayout.box("RegOrdinario", "Tipo8", "BI"));
  }

  /**
   * Every box of the reference-data box list of the 2025 output parameters is reachable (the
   * reverse — layout boxes the metadata omits — is expected, see Fiscal390BoxLayout javadoc).
   */
  @Test
  public void testLayoutCoversReferenceDataBoxLists() {
    Map<String, List<Integer>> reference = new LinkedHashMap<>();
    reference.put("RegOrdinario", Arrays.asList(1, 2, 3, 4, 5, 6, 667, 668, 669, 670, 700, 701,
        702, 703));
    reference.put("RecargoEquivalencia", Arrays.asList(35, 36, 41, 42, 599, 600, 601, 602, 663,
        664, 665, 666, 691, 692, 693, 694));
    reference.put("AdqIntracomServicios", Arrays.asList(545, 546, 547, 548, 551, 552, 687, 688,
        689, 690, 720, 721, 722, 723));
    reference.put("OpInterioresBienesServiciosCorrientes", Arrays.asList(190, 191, 603, 604, 605,
        606, 695, 696, 697, 698, 724, 725));
    for (Map.Entry<String, List<Integer>> e : reference.entrySet()) {
      java.util.Set<Integer> layout = new java.util.HashSet<>();
      for (Map.Entry<String, Integer> l : Fiscal390BoxLayout.all().entrySet()) {
        if (l.getKey().startsWith(e.getKey() + ".")) {
          layout.add(l.getValue());
        }
      }
      assertTrue(e.getKey() + " " + layout, layout.containsAll(e.getValue()));
    }
  }

  /** Rate to classic setter suffix, truncated to 2 decimals like classic. */
  @Test
  public void testTipoFor() {
    assertEquals("Tipo21", Fiscal390BoxesCalculator.tipoFor(bd("21.000")));
    assertEquals("Tipo05", Fiscal390BoxesCalculator.tipoFor(bd("-0.5")));
    assertEquals("Tipo52", Fiscal390BoxesCalculator.tipoFor(bd("5.2")));
    assertNull(Fiscal390BoxesCalculator.tipoFor(bd("3")));
    assertNull(Fiscal390BoxesCalculator.tipoFor(null));
  }
}
