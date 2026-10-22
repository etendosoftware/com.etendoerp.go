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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * Modelo 390 computed boxes (ETP-5716) — the single place the GO preview derives the boxes the
 * classic engine only computes while it writes the TXT file, plus the boxes that simply echo a
 * user input.
 *
 * <p>Classic ({@code org.openbravo.module.aeat390.es}) computes 47/64/65/108 on the bean
 * ({@code AEAT390Report2014#generateOperacionesRegimenGeneral}, {@code AEAT390Utility
 * .calculateTotalCuotasIVA/calculateSumDeducciones/calculateSumVolOperaciones}) and 84/86/92/94
 * and the final 108 inside {@code AEAT390Report2021#generateTXTPage6}, which mutates the bean
 * while building the file. The formulas below mirror that code:
 * <pre>
 * [47]  = [34] + sum of every recargo-equivalencia cuota + [44]     (only RE cuotas when [34] is empty)
 * [64]  = sum of the deductible cuotas (each split element's Total cuota, each non-split element's cuota)
 *         + [661]
 * [65]  = [47] - [64]
 * LIQUIDATION mode (no territory percentage given):
 *   [84] = [65] + [658]
 *   [85] = LIQANUAL_COMPENSATION (only when &gt;= 0.01)
 *   [86] = [84] + [659] - LIQANUAL_COMPENSATION
 * TERRITORY mode (any of [87]-[91] &gt; 0):
 *   [84] = [65] + [661] + [658]                (classic does not take [661] off this slot)
 *   [92] = round2([84] * [87] / 100)            (HALF_UP)
 *   [94] = [92] + [659] - [93]
 * [95]  = INGRESAR_01 + ... + INGRESAR_12
 * [96]  = DEVOLVER_01 + ... + DEVOLVER_12   (only with ISMONTHLYRETURN = 'Y')
 * [108] = invoice volume ([99]+[653]+[103]+[104]+[105]+[110]+[125]+[126] - [106] - [107])
 *         + [127] + [128] + [101] + [227] + [228]
 * </pre>
 * [661] (cuotas deducibles por resolución firme con tipos no vigentes) is NOT part of the bean's
 * {@code SumDeducciones}/{@code ResRegGeneral}: classic adds it to [64] and takes it off [65] only
 * while writing TXT page 4 ({@code AEAT390Report2018#generateTXTPage4}), and takes it off the
 * liquidation [84]/[86] while writing page 6 ({@code AEAT390Report2021#generateTXTPage6}). The
 * territory [84] (and therefore [92]/[94]) is printed from {@code Administraciones.SumResultados},
 * which never subtracts it — so in territory mode with a [661] the file is internally inconsistent
 * ([84] != [65] + [658]). The preview prints exactly what the file prints for every box it shows.
 * Known classic quirk NOT reproduced: in TERRITORY mode the TXT also prints the liquidation-mode
 * [84]/[86] slots as {@code 0 - [661]}; the preview only reports the territory [84].
 */
final class Fiscal390DerivedBoxes {

  /** Mode of the "Resultado de la liquidación anual" section. */
  static final String MODE_LIQUIDATION = "liquidation";
  static final String MODE_TERRITORY = "territory";

  // AEAT390 TaxReportParameter search keys of the user inputs (390_Report_Tax_Parameters.xml).
  static final String LIQANUAL_COMPENSATION = "LIQANUAL_COMPENSATION";
  static final String LIQANUAL_VAT_LAW_REGULARIZATION = "LIQANUAL_VAT_LAW_REGULARIZATION";
  static final String LIQANUAL_VAT_IMPORT = "LIQANUAL_VAT_IMPORT";
  static final String LIQANUAL_NO_CURRENT_TYPE_AMOUNT = "LIQANUAL_NoCurrentTypeAMOUNT";
  static final String COMMON_TERRITORY = "COMMON_TERRITORY";
  static final String COMMON_TERRITORY_COMPENSATION = "COMMON_TERRITORY_COMPENSATION";
  static final String OPS_SUJETAS_OSS = "390OPSUJETASCONOSS";
  static final String OPS_INTRAGRUPO = "390OPSINTRAGRUPO";
  static final String REAGYP = "390REAGYP";
  static final String USED_GOODS = "390USED_GOODS";
  static final String TRAVEL_AGENCY = "390TRAVEL_AGENCY";
  static final String INGRESAR_PREFIX = "INGRESAR_";
  static final String DEVOLVER_PREFIX = "DEVOLVER_";
  /** Flag input: inscribed in the monthly-refund register (DEVOLVER_xx only count with it). */
  static final String MONTHLY_RETURN_FLAG = "ISMONTHLYRETURN";

  private static final String[] TERRITORY_KEYS =
      { COMMON_TERRITORY, "ALAVA", "GUIPUZCOA", "VIZCAYA", "NAVARRA" };

  /** Inputs that are printed as-is, whatever the mode, keyed by search key. */
  private static final Map<String, Integer> ECHO_BOXES = buildEchoBoxes();
  /** Inputs printed only in TERRITORY mode (the "Administraciones" block). */
  private static final Map<String, Integer> TERRITORY_ECHO_BOXES = buildTerritoryEchoBoxes();

  /** Every input search key this class reads (request/stored inputs are filtered to these). */
  static final Set<String> INPUT_KEYS = buildInputKeys();

  /** Boxes summed with a negative sign into [108] (classic hard-codes the same two getters). */
  static final Set<Integer> VOLUME_SUBTRACTED_BOXES = Set.of(106, 107);

  private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  private Fiscal390DerivedBoxes() {
  }

  /**
   * Figures the derivation needs from the invoice compute, alongside the per-box amounts.
   */
  static final class InvoiceFigures {
    /** Cuota of [34] (TotalBasesyCuotasIVA), or {@code null} when that element is empty. */
    BigDecimal totalCuotas34;
    /** Sum of every recargo-equivalencia cuota (all rates). */
    BigDecimal recargoCuotas = BigDecimal.ZERO;
    /** Cuota of [44] (ModRecargoEquivalencia). */
    BigDecimal modRecargoCuota = BigDecimal.ZERO;
    /** Sum of the deductible cuotas, see [64]. */
    BigDecimal deductibleCuotas = BigDecimal.ZERO;
    /** Signed sum of the invoice-derived volume-of-operations boxes. */
    BigDecimal invoiceVolume = BigDecimal.ZERO;
  }

  /**
   * Adds the computed and echoed boxes to {@code boxes} (in place) and returns the mode used.
   *
   * @param boxes   the invoice-derived boxes; receives the computed ones
   * @param figures the invoice aggregates the formulas need
   * @param inputs  user inputs keyed by AEAT390 search key (see {@link #parseInputs})
   * @return {@link #MODE_LIQUIDATION} or {@link #MODE_TERRITORY}
   */
  static String apply(Map<Integer, BigDecimal> boxes, InvoiceFigures figures,
      Map<String, BigDecimal> inputs) {
    BigDecimal box47 = figures.totalCuotas34 == null ? figures.recargoCuotas
        : figures.totalCuotas34.add(figures.recargoCuotas).add(figures.modRecargoCuota);
    BigDecimal noCurrentType = in(inputs, LIQANUAL_NO_CURRENT_TYPE_AMOUNT);
    // Classic's ResRegGeneral: [47] minus the deductible cuotas, before [661].
    BigDecimal resRegGeneral = box47.subtract(figures.deductibleCuotas);
    BigDecimal box64 = figures.deductibleCuotas.add(noCurrentType);
    BigDecimal box65 = resRegGeneral.subtract(noCurrentType);
    put(boxes, 47, box47);
    put(boxes, 64, box64);
    put(boxes, 65, box65);

    for (Map.Entry<String, Integer> echo : ECHO_BOXES.entrySet()) {
      putIfNonZero(boxes, echo.getValue(), in(inputs, echo.getKey()));
    }

    BigDecimal regularization = in(inputs, LIQANUAL_VAT_LAW_REGULARIZATION);
    BigDecimal vatImport = in(inputs, LIQANUAL_VAT_IMPORT);
    String mode = isTerritoryMode(inputs) ? MODE_TERRITORY : MODE_LIQUIDATION;
    if (MODE_TERRITORY.equals(mode)) {
      for (Map.Entry<String, Integer> echo : TERRITORY_ECHO_BOXES.entrySet()) {
        putIfNonZero(boxes, echo.getValue(), in(inputs, echo.getKey()));
      }
      BigDecimal box84 = resRegGeneral.add(regularization);
      BigDecimal box92 = box84.multiply(in(inputs, COMMON_TERRITORY))
          .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
      put(boxes, 84, box84);
      put(boxes, 92, box92);
      put(boxes, 94, box92.add(vatImport).subtract(in(inputs, COMMON_TERRITORY_COMPENSATION)));
    } else {
      BigDecimal compensation = in(inputs, LIQANUAL_COMPENSATION);
      BigDecimal box84 = box65.add(regularization);
      put(boxes, 84, box84);
      if (compensation.compareTo(ONE_CENT) >= 0) {
        put(boxes, 85, compensation);
      }
      put(boxes, 86, box84.add(vatImport).subtract(compensation));
    }

    putIfNonZero(boxes, 95, sumByPrefix(inputs, INGRESAR_PREFIX));
    putIfNonZero(boxes, 96, sumByPrefix(inputs, DEVOLVER_PREFIX));
    put(boxes, 108, figures.invoiceVolume
        .add(in(inputs, OPS_SUJETAS_OSS))
        .add(in(inputs, OPS_INTRAGRUPO))
        .add(in(inputs, REAGYP))
        .add(in(inputs, USED_GOODS))
        .add(in(inputs, TRAVEL_AGENCY)));
    return mode;
  }

  /**
   * TERRITORY mode is entered as soon as any territory percentage ([87]-[91]) is positive —
   * the same test as classic's {@code AEAT390Report#checkData} ({@code enterAdministracionesGroup}).
   */
  static boolean isTerritoryMode(Map<String, BigDecimal> inputs) {
    for (String key : TERRITORY_KEYS) {
      if (in(inputs, key).compareTo(BigDecimal.ZERO) > 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Parses raw inputs (request query params or the stored {@code manualData.aeatInputs}) into
   * numbers, keeping only {@link #INPUT_KEYS}. Mirrors classic {@code AEAT390Utility
   * .getNumericInput}: a decimal comma is accepted, blank means absent.
   *
   * @throws IllegalArgumentException naming the key when a value is not a number
   */
  static Map<String, BigDecimal> parseInputs(Map<String, String> raw) {
    Map<String, BigDecimal> parsed = new LinkedHashMap<>();
    // DEVOLVER_xx only counts when inscribed in the monthly-refund register (classic
    // AEAT390_MonthlyReturn_Check; review N3): otherwise [96] ignores them.
    boolean monthlyReturn = isMonthlyReturn(raw);
    for (Map.Entry<String, String> e : raw.entrySet()) {
      if (!INPUT_KEYS.contains(e.getKey()) || StringUtils.isBlank(e.getValue())
          || (!monthlyReturn && e.getKey().startsWith(DEVOLVER_PREFIX))) {
        continue;
      }
      try {
        parsed.put(e.getKey(), new BigDecimal(e.getValue().trim().replace(",", ".")));
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
            "Invalid numeric value for Modelo 390 input " + e.getKey() + ": " + e.getValue(), ex);
      }
    }
    return parsed;
  }

  /** {@code ISMONTHLYRETURN = 'Y'}: inscribed in the monthly-refund register. */
  static boolean isMonthlyReturn(Map<String, String> raw) {
    return raw != null && "Y".equals(raw.get(MONTHLY_RETURN_FLAG));
  }

  private static BigDecimal in(Map<String, BigDecimal> inputs, String key) {
    BigDecimal v = inputs.get(key);
    return v != null ? v : BigDecimal.ZERO;
  }

  private static BigDecimal sumByPrefix(Map<String, BigDecimal> inputs, String prefix) {
    BigDecimal sum = BigDecimal.ZERO;
    for (int month = 1; month <= 12; month++) {
      sum = sum.add(in(inputs, prefix + String.format("%02d", month)));
    }
    return sum;
  }

  private static void put(Map<Integer, BigDecimal> boxes, int box, BigDecimal value) {
    boxes.put(box, value.setScale(2, RoundingMode.HALF_UP));
  }

  private static void putIfNonZero(Map<Integer, BigDecimal> boxes, int box, BigDecimal value) {
    if (value.compareTo(BigDecimal.ZERO) != 0) {
      put(boxes, box, value);
    }
  }

  private static Map<String, Integer> buildEchoBoxes() {
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put("LIQANUAL_NoCurrentTypeBASE", 660);
    m.put(LIQANUAL_NO_CURRENT_TYPE_AMOUNT, 661);
    m.put(LIQANUAL_VAT_LAW_REGULARIZATION, 658);
    m.put(LIQANUAL_VAT_IMPORT, 659);
    m.put("TOTAL_LASTPERIOD", 97);
    m.put("TOTAL_LASTPERIOD_DEVOLVER", 98);
    m.put("LIQANUAL_ENDCOMPENSATION", 662);
    m.put(REAGYP, 101);
    m.put(USED_GOODS, 227);
    m.put(TRAVEL_AGENCY, 228);
    m.put(OPS_SUJETAS_OSS, 127);
    m.put(OPS_INTRAGRUPO, 128);
    m.put("OPER_MONTHLYRETURN", 111);
    return Collections.unmodifiableMap(m);
  }

  private static Map<String, Integer> buildTerritoryEchoBoxes() {
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put(COMMON_TERRITORY, 87);
    m.put("ALAVA", 88);
    m.put("GUIPUZCOA", 89);
    m.put("VIZCAYA", 90);
    m.put("NAVARRA", 91);
    m.put(COMMON_TERRITORY_COMPENSATION, 93);
    return Collections.unmodifiableMap(m);
  }

  private static Set<String> buildInputKeys() {
    java.util.Set<String> keys = new java.util.LinkedHashSet<>();
    keys.addAll(ECHO_BOXES.keySet());
    keys.addAll(TERRITORY_ECHO_BOXES.keySet());
    keys.add(LIQANUAL_COMPENSATION);
    for (int month = 1; month <= 12; month++) {
      keys.add(INGRESAR_PREFIX + String.format("%02d", month));
      keys.add(DEVOLVER_PREFIX + String.format("%02d", month));
    }
    return Collections.unmodifiableSet(keys);
  }
}
