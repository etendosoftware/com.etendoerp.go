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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.taxreportlauncher.TaxReportParameter;

/**
 * Modelo 390 box computation from the classic tax-report configuration (ETP-5716) — the GO
 * counterpart of {@code Fiscal303BoxesHandler#computeBoxes}, free of database access so it can be
 * unit-tested: every read goes through an {@link Engine}.
 *
 * <p>It walks the same {@code TaxReportGroup}s, in the same way, as the classic engine
 * ({@code AEAT390CashVATReadyReport#exploreRegGeneralGroups/exploreTaxableAmountGroups}):
 * <ul>
 *   <li>{@value #VAT_SALES} / {@value #VAT_PURCHASE} — each output parameter's taxes, split by tax
 *       rate when {@code aeat390Issplit} (one base/cuota pair per rate, plus the element total
 *       when {@code aeat390Istotal}), otherwise one base/cuota pair ({@code TipoX}). Parameters
 *       sharing an XML element (VAT_SALES_MOD_BASESYCUOTAS + _2) add up, as in classic.</li>
 *   <li>{@value #VOLUME_OPERATIONS} / {@value #SPECIFIC_OPERATIONS} — the base only (base and
 *       cuota when {@code aeat390IsBIAndCuota}), skipped when the base is negative, as in
 *       classic.</li>
 * </ul>
 * Each figure is placed in its box through {@link Fiscal390BoxLayout}; the computed boxes are
 * then added by {@link Fiscal390DerivedBoxes}.
 *
 * <p>Instead of failing like classic on an unsupported tax rate, the preview reports it in
 * {@link Result#warnings} ({@code UNSUPPORTED_RATE}, figure left out), and a supported rate the
 * 390 form does not print for that element as {@code NO_BOX} (figure kept in the totals, which
 * classic would also do, but not placed in any box).
 */
final class Fiscal390BoxesCalculator {

  static final String VAT_SALES = "VAT_SALES";
  static final String VAT_PURCHASE = "VAT_PURCHASE";
  static final String VOLUME_OPERATIONS = "VOLUME_OPERATIONS";
  static final String SPECIFIC_OPERATIONS = "SPECIFIC_OPERATIONS";

  static final String WARNING_UNSUPPORTED_RATE = "UNSUPPORTED_RATE";
  static final String WARNING_NO_BOX = "NO_BOX";

  // Classic bean elements the [47] formula reads (AEAT390Utility.calculateTotalCuotasIVA).
  private static final String ELEMENT_TOTAL_BASES_CUOTAS = "TotalBasesyCuotasIVA";
  private static final String ELEMENT_RECARGO = "RecargoEquivalencia";
  private static final String ELEMENT_MOD_RECARGO = "ModRecargoEquivalencia";

  /**
   * Tax-rate percentage (scale 2) to classic setter suffix — the same table as classic
   * {@code AEAT390ReflectionUtility.taxRateToSetterMap}.
   */
  private static final Map<BigDecimal, String> RATE_TO_TIPO = buildRateToTipo();

  /** Database access the computation needs. */
  interface Engine {
    /** Output ({@code type = 'O'}) parameters of a group, by sequence; empty when no group. */
    List<TaxReportParameter> outputParameters(String groupKey);

    /** The taxes mapped to a parameter, ordered by rate. */
    List<TaxRate> taxes(TaxReportParameter parameter);

    /** {@code {base, cuota}} of posted invoices of the year for those taxes (never null). */
    BigDecimal[] amounts(TaxReportParameter parameter, List<TaxRate> taxes);
  }

  /** Outcome of {@link #compute}. */
  static final class Result {
    final Map<Integer, BigDecimal> boxes = new TreeMap<>();
    final Fiscal390DerivedBoxes.InvoiceFigures figures = new Fiscal390DerivedBoxes.InvoiceFigures();
    final List<Map<String, String>> warnings = new ArrayList<>();
    String mode;
  }

  private Fiscal390BoxesCalculator() {
  }

  /**
   * Computes every box: invoice figures through {@code engine}, then the derived boxes from
   * {@code inputs}. Amounts are rounded to 2 decimals (HALF_UP) only at the end.
   */
  static Result compute(Engine engine, Map<String, BigDecimal> inputs) {
    Result r = new Result();
    Map<Integer, BigDecimal> raw = new HashMap<>();
    exploreRegGeneral(engine, VAT_SALES, raw, r);
    exploreRegGeneral(engine, VAT_PURCHASE, raw, r);
    exploreTaxableAmounts(engine, VOLUME_OPERATIONS, raw, r);
    exploreTaxableAmounts(engine, SPECIFIC_OPERATIONS, raw, r);
    for (Map.Entry<Integer, BigDecimal> e : raw.entrySet()) {
      r.boxes.put(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP));
    }
    r.mode = Fiscal390DerivedBoxes.apply(r.boxes, r.figures, inputs);
    return r;
  }

  private static void exploreRegGeneral(Engine engine, String groupKey,
      Map<Integer, BigDecimal> raw, Result r) {
    boolean deductible = VAT_PURCHASE.equals(groupKey);
    for (TaxReportParameter p : engine.outputParameters(groupKey)) {
      String element = p.getAeat390XmlElement();
      if (StringUtils.isBlank(element)) {
        continue;
      }
      List<TaxRate> taxes = engine.taxes(p);
      if (taxes.isEmpty()) {
        continue;
      }
      if (Boolean.TRUE.equals(p.isAeat390Issplit())) {
        exploreSplitParameter(engine, p, element, taxes, deductible, raw, r);
      } else {
        BigDecimal[] amounts = engine.amounts(p, taxes);
        if (isZero(amounts)) {
          continue;
        }
        place(raw, r, p, element, Fiscal390BoxLayout.TIPO_X, amounts, null);
        if (deductible) {
          r.figures.deductibleCuotas = r.figures.deductibleCuotas.add(amounts[1]);
        }
        trackSalesTotals(r.figures, element, amounts[1]);
      }
    }
  }

  private static void exploreSplitParameter(Engine engine, TaxReportParameter p, String element,
      List<TaxRate> taxes, boolean deductible, Map<Integer, BigDecimal> raw, Result r) {
    BigDecimal totalBase = BigDecimal.ZERO;
    BigDecimal totalCuota = BigDecimal.ZERO;
    for (Map.Entry<BigDecimal, List<TaxRate>> byRate : splitByRate(taxes).entrySet()) {
      BigDecimal[] amounts = engine.amounts(p, byRate.getValue());
      if (isZero(amounts)) {
        continue;
      }
      String tipo = RATE_TO_TIPO.get(byRate.getKey());
      if (tipo == null) {
        r.warnings.add(warning(WARNING_UNSUPPORTED_RATE, p, byRate.getKey()));
        continue;
      }
      place(raw, r, p, element, tipo, amounts, byRate.getKey());
      totalBase = totalBase.add(amounts[0]);
      totalCuota = totalCuota.add(amounts[1]);
      if (ELEMENT_RECARGO.equals(element)) {
        r.figures.recargoCuotas = r.figures.recargoCuotas.add(amounts[1]);
      }
    }
    boolean withTotal = Boolean.TRUE.equals(p.isAeat390Istotal())
        && (totalBase.signum() != 0 || totalCuota.signum() != 0);
    if (withTotal) {
      place(raw, r, p, element, Fiscal390BoxLayout.TOTAL,
          new BigDecimal[] { totalBase, totalCuota }, null);
    }
    // calculateSumDeducciones reads a split element's Total — absent when not aeat390Istotal.
    if (deductible && withTotal) {
      r.figures.deductibleCuotas = r.figures.deductibleCuotas.add(totalCuota);
    }
  }

  private static void trackSalesTotals(Fiscal390DerivedBoxes.InvoiceFigures f, String element,
      BigDecimal cuota) {
    if (ELEMENT_TOTAL_BASES_CUOTAS.equals(element)) {
      f.totalCuotas34 = f.totalCuotas34 == null ? cuota : f.totalCuotas34.add(cuota);
    } else if (ELEMENT_MOD_RECARGO.equals(element)) {
      f.modRecargoCuota = f.modRecargoCuota.add(cuota);
    }
  }

  private static void exploreTaxableAmounts(Engine engine, String groupKey,
      Map<Integer, BigDecimal> raw, Result r) {
    boolean volume = VOLUME_OPERATIONS.equals(groupKey);
    for (TaxReportParameter p : engine.outputParameters(groupKey)) {
      String element = p.getAeat390XmlElement();
      List<TaxRate> taxes = engine.taxes(p);
      if (StringUtils.isBlank(element) || taxes.isEmpty()) {
        continue;
      }
      BigDecimal[] amounts = engine.amounts(p, taxes);
      // Classic keeps the figure only when the base is >= 0.
      if (amounts[0].signum() < 0) {
        continue;
      }
      if (Boolean.TRUE.equals(p.isAeat390IsBIAndCuota())) {
        place(raw, r, p, element, Fiscal390BoxLayout.TIPO_X, amounts, null);
        continue;
      }
      Integer box = Fiscal390BoxLayout.box(element);
      if (box == null) {
        r.warnings.add(warning(WARNING_NO_BOX, p, null));
        continue;
      }
      raw.merge(box, amounts[0], BigDecimal::add);
      if (volume) {
        BigDecimal signed = Fiscal390DerivedBoxes.VOLUME_SUBTRACTED_BOXES.contains(box)
            ? amounts[0].negate() : amounts[0];
        r.figures.invoiceVolume = r.figures.invoiceVolume.add(signed);
      }
    }
  }

  private static void place(Map<Integer, BigDecimal> raw, Result r, TaxReportParameter p,
      String element, String tipo, BigDecimal[] amounts, BigDecimal rate) {
    Integer baseBox = Fiscal390BoxLayout.box(element, tipo, Fiscal390BoxLayout.BI);
    Integer cuotaBox = Fiscal390BoxLayout.box(element, tipo, Fiscal390BoxLayout.CUOTA);
    if (baseBox == null && cuotaBox == null) {
      r.warnings.add(warning(WARNING_NO_BOX, p, rate));
      return;
    }
    if (baseBox != null) {
      raw.merge(baseBox, amounts[0], BigDecimal::add);
    }
    if (cuotaBox != null) {
      raw.merge(cuotaBox, amounts[1], BigDecimal::add);
    }
  }

  /**
   * Groups taxes by absolute rate truncated to 2 decimals — the key classic's setter table uses.
   */
  static Map<BigDecimal, List<TaxRate>> splitByRate(List<TaxRate> taxes) {
    Map<BigDecimal, List<TaxRate>> map = new LinkedHashMap<>();
    for (TaxRate t : taxes) {
      BigDecimal rate = t.getRate() != null ? t.getRate() : BigDecimal.ZERO;
      map.computeIfAbsent(rate.abs().setScale(2, RoundingMode.DOWN), k -> new ArrayList<>()).add(t);
    }
    return map;
  }

  /** Classic setter suffix for a rate ({@code Tipo21}), or {@code null} when unsupported. */
  static String tipoFor(BigDecimal rate) {
    return rate == null ? null : RATE_TO_TIPO.get(rate.abs().setScale(2, RoundingMode.DOWN));
  }

  private static boolean isZero(BigDecimal[] amounts) {
    return amounts[0].signum() == 0 && amounts[1].signum() == 0;
  }

  private static Map<String, String> warning(String code, TaxReportParameter p, BigDecimal rate) {
    Map<String, String> w = new LinkedHashMap<>();
    w.put("code", code);
    w.put("parameter", p.getSearchKey());
    if (rate != null) {
      w.put("rate", rate.toPlainString());
    }
    return w;
  }

  private static Map<BigDecimal, String> buildRateToTipo() {
    Map<BigDecimal, String> m = new HashMap<>();
    String[][] table = { { "0", "Tipo0" }, { "0.26", "Tipo026" }, { "0.5", "Tipo05" },
        { "0.62", "Tipo062" }, { "1", "Tipo1" }, { "1.4", "Tipo14" }, { "1.75", "Tipo175" },
        { "2", "Tipo2" }, { "4", "Tipo4" }, { "5", "Tipo5" }, { "5.2", "Tipo52" },
        { "7", "Tipo7" }, { "7.5", "Tipo75" }, { "8", "Tipo8" }, { "10", "Tipo10" },
        { "16", "Tipo16" }, { "18", "Tipo18" }, { "21", "Tipo21" } };
    for (String[] row : table) {
      m.put(new BigDecimal(row[0]).setScale(2, RoundingMode.DOWN), row[1]);
    }
    return m;
  }
}
