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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Modelo 390 box layout (ETP-5716): which AEAT box ("casilla") each figure computed from a
 * {@code TaxReportParameter} lands in.
 *
 * <p>The classic engine ({@code org.openbravo.module.aeat390.es}) never stores this mapping as
 * data: it fills a JAXB bean ({@code AEATIVA2014}) by reflection — element
 * {@code TaxReportParameter.aeat390XmlElement}, then {@code setTipo<rate>}/{@code setTipoX}/
 * {@code setTotal} — and its TXT writers ({@code AEAT390Report2015}..{@code AEAT390Report2025},
 * methods {@code generateTXTPage*}) print each bean getter at a fixed position labelled with the
 * box number. This table is that bean-path to box relation, extracted mechanically from every
 * {@code formatNumericAmount*} call of the 2015-2025 TXT writers (the effective class chain of
 * {@code AEAT390_2025_Y}), later versions winning. Across those versions no bean path ever moved
 * to a different box — new rates/boxes were only added — so one cumulative table serves every
 * layout year.
 *
 * <p>Keys:
 * <ul>
 *   <li>{@code <element>.<Tipo>.<BI|Cuota>} — base / cuota of an element of
 *       {@code RegGeneral.BaseImponibleyCuota} or {@code RegGeneral.Deducciones}. {@code <Tipo>}
 *       is the classic setter suffix for the tax rate ({@code Tipo21}, {@code Tipo05}, ...),
 *       {@code TipoX} for a non-split element, {@code Total} for a split element's total.</li>
 *   <li>{@code <element>} — base-only element of {@code VolOperaciones}/{@code OpEspecificas}
 *       ({@code <element>.TipoX.<BI|Cuota>} when the parameter is {@code aeat390IsBIAndCuota}).</li>
 * </ul>
 * Computed totals (47, 64, 65, 84, 86, 92, 94, 108) are not here — see
 * {@link Fiscal390DerivedBoxes}.
 *
 * <p>Note: box 632 is where classic writes {@code AdqIntracomunitariasBienesInversion.Tipo10.Cuota};
 * the reference data lists "662" for that parameter, a typo in metadata that is never read.
 *
 * <p>Box 112 ({@code EntregasBienesInstalacionOtrosEM}) is deliberately absent: only the 2015 and
 * 2018 page-6 writers print it, and {@code AEAT390Report2021#generateTXTPage6} replaced that page
 * without calling them, so no layout from 2021 on (nor the AEAT DR390e2025 record design) has it.
 */
final class Fiscal390BoxLayout {

  static final String BI = "BI";
  static final String CUOTA = "Cuota";
  static final String TIPO_X = "TipoX";
  static final String TOTAL = "Total";

  private static final Map<String, Integer> LAYOUT = Collections.unmodifiableMap(build());

  private Fiscal390BoxLayout() {
  }

  /**
   * The box of a split/non-split element amount, or {@code null} when the layout has no box for
   * it (e.g. a tax rate the 390 form does not print for that element).
   */
  static Integer box(String element, String tipo, String part) {
    return LAYOUT.get(element + "." + tipo + "." + part);
  }

  /** The box of a base-only element of the taxable-amount groups, or {@code null}. */
  static Integer box(String element) {
    return LAYOUT.get(element);
  }

  /** Read-only view of the whole table (tests). */
  static Map<String, Integer> all() {
    return LAYOUT;
  }

  private static void put(Map<String, Integer> m, String key, int box) {
    m.put(key, box);
  }

  @SuppressWarnings("java:S138") // a flat data table: one line per box, splitting it adds nothing
  private static Map<String, Integer> build() {
    Map<String, Integer> m = new HashMap<>();
    // AdqCriterioCajaBase
    put(m, "AdqCriterioCajaBase.TipoX.BI", 656);
    put(m, "AdqCriterioCajaBase.TipoX.Cuota", 657);
    // AdqInterioresExentas
    put(m, "AdqInterioresExentas", 230);
    // AdqIntracomBienes
    put(m, "AdqIntracomBienes.Tipo4.BI", 21);
    put(m, "AdqIntracomBienes.Tipo4.Cuota", 22);
    put(m, "AdqIntracomBienes.Tipo10.BI", 23);
    put(m, "AdqIntracomBienes.Tipo10.Cuota", 24);
    put(m, "AdqIntracomBienes.Tipo21.BI", 25);
    put(m, "AdqIntracomBienes.Tipo21.Cuota", 26);
    put(m, "AdqIntracomBienes.Tipo2.BI", 683);
    put(m, "AdqIntracomBienes.Tipo2.Cuota", 684);
    put(m, "AdqIntracomBienes.Tipo75.BI", 685);
    put(m, "AdqIntracomBienes.Tipo75.Cuota", 686);
    put(m, "AdqIntracomBienes.Tipo0.BI", 716);
    put(m, "AdqIntracomBienes.Tipo0.Cuota", 717);
    put(m, "AdqIntracomBienes.Tipo5.BI", 718);
    put(m, "AdqIntracomBienes.Tipo5.Cuota", 719);
    // AdqIntracomServicios
    put(m, "AdqIntracomServicios.Tipo4.BI", 545);
    put(m, "AdqIntracomServicios.Tipo4.Cuota", 546);
    put(m, "AdqIntracomServicios.Tipo10.BI", 547);
    put(m, "AdqIntracomServicios.Tipo10.Cuota", 548);
    put(m, "AdqIntracomServicios.Tipo21.BI", 551);
    put(m, "AdqIntracomServicios.Tipo21.Cuota", 552);
    put(m, "AdqIntracomServicios.Tipo2.BI", 687);
    put(m, "AdqIntracomServicios.Tipo2.Cuota", 688);
    put(m, "AdqIntracomServicios.Tipo75.BI", 689);
    put(m, "AdqIntracomServicios.Tipo75.Cuota", 690);
    put(m, "AdqIntracomServicios.Tipo0.BI", 720);
    put(m, "AdqIntracomServicios.Tipo0.Cuota", 721);
    put(m, "AdqIntracomServicios.Tipo5.BI", 722);
    put(m, "AdqIntracomServicios.Tipo5.Cuota", 723);
    // AdqIntracomunitariasBienesCorrientes
    put(m, "AdqIntracomunitariasBienesCorrientes.Total.BI", 56);
    put(m, "AdqIntracomunitariasBienesCorrientes.Total.Cuota", 57);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo4.BI", 214);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo4.Cuota", 215);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo7.BI", 216);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo7.Cuota", 217);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo16.BI", 218);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo16.Cuota", 219);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo8.BI", 579);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo8.Cuota", 580);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo18.BI", 581);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo18.Cuota", 582);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo10.BI", 627);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo10.Cuota", 628);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo21.BI", 629);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo21.Cuota", 630);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo5.BI", 736);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo5.Cuota", 737);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo2.BI", 765);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo2.Cuota", 766);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo75.BI", 767);
    put(m, "AdqIntracomunitariasBienesCorrientes.Tipo75.Cuota", 768);
    // AdqIntracomunitariasBienesInversion
    put(m, "AdqIntracomunitariasBienesInversion.Total.BI", 58);
    put(m, "AdqIntracomunitariasBienesInversion.Total.Cuota", 59);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo4.BI", 220);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo4.Cuota", 221);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo7.BI", 222);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo7.Cuota", 223);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo16.BI", 224);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo16.Cuota", 225);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo8.BI", 583);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo8.Cuota", 584);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo18.BI", 585);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo18.Cuota", 586);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo10.BI", 631);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo10.Cuota", 632);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo21.BI", 633);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo21.Cuota", 634);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo5.BI", 738);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo5.Cuota", 739);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo2.BI", 769);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo2.Cuota", 770);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo75.BI", 771);
    put(m, "AdqIntracomunitariasBienesInversion.Tipo75.Cuota", 772);
    // AdqIntracomunitariasExentas
    put(m, "AdqIntracomunitariasExentas", 109);
    // AdqIntracomunitariasServicios
    put(m, "AdqIntracomunitariasServicios.Tipo4.BI", 587);
    put(m, "AdqIntracomunitariasServicios.Tipo4.Cuota", 588);
    put(m, "AdqIntracomunitariasServicios.Tipo7.BI", 589);
    put(m, "AdqIntracomunitariasServicios.Tipo7.Cuota", 590);
    put(m, "AdqIntracomunitariasServicios.Tipo8.BI", 591);
    put(m, "AdqIntracomunitariasServicios.Tipo8.Cuota", 592);
    put(m, "AdqIntracomunitariasServicios.Tipo16.BI", 593);
    put(m, "AdqIntracomunitariasServicios.Tipo16.Cuota", 594);
    put(m, "AdqIntracomunitariasServicios.Tipo18.BI", 595);
    put(m, "AdqIntracomunitariasServicios.Tipo18.Cuota", 596);
    put(m, "AdqIntracomunitariasServicios.Total.BI", 597);
    put(m, "AdqIntracomunitariasServicios.Total.Cuota", 598);
    put(m, "AdqIntracomunitariasServicios.Tipo10.BI", 635);
    put(m, "AdqIntracomunitariasServicios.Tipo10.Cuota", 636);
    put(m, "AdqIntracomunitariasServicios.Tipo21.BI", 637);
    put(m, "AdqIntracomunitariasServicios.Tipo21.Cuota", 638);
    put(m, "AdqIntracomunitariasServicios.Tipo5.BI", 740);
    put(m, "AdqIntracomunitariasServicios.Tipo5.Cuota", 741);
    put(m, "AdqIntracomunitariasServicios.Tipo2.BI", 773);
    put(m, "AdqIntracomunitariasServicios.Tipo2.Cuota", 774);
    put(m, "AdqIntracomunitariasServicios.Tipo75.BI", 775);
    put(m, "AdqIntracomunitariasServicios.Tipo75.Cuota", 776);
    // BasesIVASoportadoNoDeducible
    put(m, "BasesIVASoportadoNoDeducible", 232);
    // ComRegAgricGanadPesca
    put(m, "ComRegAgricGanadPesca.TipoX.BI", 60);
    put(m, "ComRegAgricGanadPesca.TipoX.Cuota", 61);
    // EntregasBienesInmuebles
    put(m, "EntregasBienesInmuebles", 106);
    // EntregasBienesInversion
    put(m, "EntregasBienesInversion", 107);
    // EntregasCriterioCajaBase
    put(m, "EntregasCriterioCajaBase.TipoX.BI", 654);
    put(m, "EntregasCriterioCajaBase.TipoX.Cuota", 655);
    // EntregasInteriores
    put(m, "EntregasInteriores", 113);
    // EntregasIntracomunitariasExentas
    put(m, "EntregasIntracomunitariasExentas", 103);
    // ExportacionesExentasConDrchoDeduccion
    put(m, "ExportacionesExentasConDrchoDeduccion", 104);
    // IVAdevengadoInversionSP
    put(m, "IVAdevengadoInversionSP.TipoX.BI", 27);
    put(m, "IVAdevengadoInversionSP.TipoX.Cuota", 28);
    // ImportacionesBienesCorrientes
    put(m, "ImportacionesBienesCorrientes.Total.BI", 52);
    put(m, "ImportacionesBienesCorrientes.Total.Cuota", 53);
    put(m, "ImportacionesBienesCorrientes.Tipo4.BI", 202);
    put(m, "ImportacionesBienesCorrientes.Tipo4.Cuota", 203);
    put(m, "ImportacionesBienesCorrientes.Tipo7.BI", 204);
    put(m, "ImportacionesBienesCorrientes.Tipo7.Cuota", 205);
    put(m, "ImportacionesBienesCorrientes.Tipo16.BI", 206);
    put(m, "ImportacionesBienesCorrientes.Tipo16.Cuota", 207);
    put(m, "ImportacionesBienesCorrientes.Tipo8.BI", 571);
    put(m, "ImportacionesBienesCorrientes.Tipo8.Cuota", 572);
    put(m, "ImportacionesBienesCorrientes.Tipo18.BI", 573);
    put(m, "ImportacionesBienesCorrientes.Tipo18.Cuota", 574);
    put(m, "ImportacionesBienesCorrientes.Tipo10.BI", 619);
    put(m, "ImportacionesBienesCorrientes.Tipo10.Cuota", 620);
    put(m, "ImportacionesBienesCorrientes.Tipo21.BI", 621);
    put(m, "ImportacionesBienesCorrientes.Tipo21.Cuota", 622);
    put(m, "ImportacionesBienesCorrientes.Tipo5.BI", 732);
    put(m, "ImportacionesBienesCorrientes.Tipo5.Cuota", 733);
    put(m, "ImportacionesBienesCorrientes.Tipo2.BI", 757);
    put(m, "ImportacionesBienesCorrientes.Tipo2.Cuota", 758);
    put(m, "ImportacionesBienesCorrientes.Tipo75.BI", 759);
    put(m, "ImportacionesBienesCorrientes.Tipo75.Cuota", 760);
    // ImportacionesBienesInversion
    put(m, "ImportacionesBienesInversion.Total.BI", 54);
    put(m, "ImportacionesBienesInversion.Total.Cuota", 55);
    put(m, "ImportacionesBienesInversion.Tipo4.BI", 208);
    put(m, "ImportacionesBienesInversion.Tipo4.Cuota", 209);
    put(m, "ImportacionesBienesInversion.Tipo7.BI", 210);
    put(m, "ImportacionesBienesInversion.Tipo7.Cuota", 211);
    put(m, "ImportacionesBienesInversion.Tipo16.BI", 212);
    put(m, "ImportacionesBienesInversion.Tipo16.Cuota", 213);
    put(m, "ImportacionesBienesInversion.Tipo8.BI", 575);
    put(m, "ImportacionesBienesInversion.Tipo8.Cuota", 576);
    put(m, "ImportacionesBienesInversion.Tipo18.BI", 577);
    put(m, "ImportacionesBienesInversion.Tipo18.Cuota", 578);
    put(m, "ImportacionesBienesInversion.Tipo10.BI", 623);
    put(m, "ImportacionesBienesInversion.Tipo10.Cuota", 624);
    put(m, "ImportacionesBienesInversion.Tipo21.BI", 625);
    put(m, "ImportacionesBienesInversion.Tipo21.Cuota", 626);
    put(m, "ImportacionesBienesInversion.Tipo5.BI", 734);
    put(m, "ImportacionesBienesInversion.Tipo5.Cuota", 735);
    put(m, "ImportacionesBienesInversion.Tipo2.BI", 761);
    put(m, "ImportacionesBienesInversion.Tipo2.Cuota", 762);
    put(m, "ImportacionesBienesInversion.Tipo75.BI", 763);
    put(m, "ImportacionesBienesInversion.Tipo75.Cuota", 764);
    // ImportacionesExentas
    put(m, "ImportacionesExentas", 231);
    // ModBasesyCuotas
    put(m, "ModBasesyCuotas.TipoX.BI", 29);
    put(m, "ModBasesyCuotas.TipoX.Cuota", 30);
    // ModBasesyCuotasConcursoAcreedores
    put(m, "ModBasesyCuotasConcursoAcreedores.TipoX.BI", 31);
    put(m, "ModBasesyCuotasConcursoAcreedores.TipoX.Cuota", 32);
    // ModBasesyCuotasOpIntragrupo
    put(m, "ModBasesyCuotasOpIntragrupo.TipoX.BI", 649);
    put(m, "ModBasesyCuotasOpIntragrupo.TipoX.Cuota", 650);
    // ModRecargoEquivalencia
    put(m, "ModRecargoEquivalencia.TipoX.BI", 43);
    put(m, "ModRecargoEquivalencia.TipoX.Cuota", 44);
    // ModRecargoEquivalenciaConcursoAcreedores
    put(m, "ModRecargoEquivalenciaConcursoAcreedores.TipoX.BI", 45);
    put(m, "ModRecargoEquivalenciaConcursoAcreedores.TipoX.Cuota", 46);
    // OpExentasSinDrchoDeduccion
    put(m, "OpExentasSinDrchoDeduccion", 105);
    // OpInterioresBienesInversion
    put(m, "OpInterioresBienesInversion.Total.BI", 50);
    put(m, "OpInterioresBienesInversion.Total.Cuota", 51);
    put(m, "OpInterioresBienesInversion.Tipo4.BI", 196);
    put(m, "OpInterioresBienesInversion.Tipo4.Cuota", 197);
    put(m, "OpInterioresBienesInversion.Tipo7.BI", 198);
    put(m, "OpInterioresBienesInversion.Tipo7.Cuota", 199);
    put(m, "OpInterioresBienesInversion.Tipo16.BI", 200);
    put(m, "OpInterioresBienesInversion.Tipo16.Cuota", 201);
    put(m, "OpInterioresBienesInversion.Tipo8.BI", 563);
    put(m, "OpInterioresBienesInversion.Tipo8.Cuota", 564);
    put(m, "OpInterioresBienesInversion.Tipo18.BI", 565);
    put(m, "OpInterioresBienesInversion.Tipo18.Cuota", 566);
    put(m, "OpInterioresBienesInversion.Tipo10.BI", 611);
    put(m, "OpInterioresBienesInversion.Tipo10.Cuota", 612);
    put(m, "OpInterioresBienesInversion.Tipo21.BI", 613);
    put(m, "OpInterioresBienesInversion.Tipo21.Cuota", 614);
    put(m, "OpInterioresBienesInversion.Tipo5.BI", 728);
    put(m, "OpInterioresBienesInversion.Tipo5.Cuota", 729);
    put(m, "OpInterioresBienesInversion.Tipo2.BI", 749);
    put(m, "OpInterioresBienesInversion.Tipo2.Cuota", 750);
    put(m, "OpInterioresBienesInversion.Tipo75.BI", 751);
    put(m, "OpInterioresBienesInversion.Tipo75.Cuota", 752);
    // OpInterioresBienesServiciosCorrientes
    put(m, "OpInterioresBienesServiciosCorrientes.Total.BI", 48);
    put(m, "OpInterioresBienesServiciosCorrientes.Total.Cuota", 49);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo4.BI", 190);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo4.Cuota", 191);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo7.BI", 192);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo7.Cuota", 193);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo16.BI", 194);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo16.Cuota", 195);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo8.BI", 555);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo8.Cuota", 556);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo18.BI", 557);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo18.Cuota", 558);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo10.BI", 603);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo10.Cuota", 604);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo21.BI", 605);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo21.Cuota", 606);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo2.BI", 695);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo2.Cuota", 696);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo75.BI", 697);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo75.Cuota", 698);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo5.BI", 724);
    put(m, "OpInterioresBienesServiciosCorrientes.Tipo5.Cuota", 725);
    // OpIntragrupo
    put(m, "OpIntragrupo.Tipo4.BI", 500);
    put(m, "OpIntragrupo.Tipo4.Cuota", 501);
    put(m, "OpIntragrupo.Tipo10.BI", 502);
    put(m, "OpIntragrupo.Tipo10.Cuota", 503);
    put(m, "OpIntragrupo.Tipo21.BI", 504);
    put(m, "OpIntragrupo.Tipo21.Cuota", 505);
    // OpIntragrupoBienesInversion
    put(m, "OpIntragrupoBienesInversion.Tipo4.BI", 514);
    put(m, "OpIntragrupoBienesInversion.Tipo4.Cuota", 515);
    put(m, "OpIntragrupoBienesInversion.Tipo7.BI", 516);
    put(m, "OpIntragrupoBienesInversion.Tipo7.Cuota", 517);
    put(m, "OpIntragrupoBienesInversion.Tipo16.BI", 518);
    put(m, "OpIntragrupoBienesInversion.Tipo16.Cuota", 519);
    put(m, "OpIntragrupoBienesInversion.Total.BI", 520);
    put(m, "OpIntragrupoBienesInversion.Total.Cuota", 521);
    put(m, "OpIntragrupoBienesInversion.Tipo8.BI", 567);
    put(m, "OpIntragrupoBienesInversion.Tipo8.Cuota", 568);
    put(m, "OpIntragrupoBienesInversion.Tipo18.BI", 569);
    put(m, "OpIntragrupoBienesInversion.Tipo18.Cuota", 570);
    put(m, "OpIntragrupoBienesInversion.Tipo10.BI", 615);
    put(m, "OpIntragrupoBienesInversion.Tipo10.Cuota", 616);
    put(m, "OpIntragrupoBienesInversion.Tipo21.BI", 617);
    put(m, "OpIntragrupoBienesInversion.Tipo21.Cuota", 618);
    // OpIntragrupoCorrientes
    put(m, "OpIntragrupoCorrientes.Tipo4.BI", 506);
    put(m, "OpIntragrupoCorrientes.Tipo4.Cuota", 507);
    put(m, "OpIntragrupoCorrientes.Tipo7.BI", 508);
    put(m, "OpIntragrupoCorrientes.Tipo7.Cuota", 509);
    put(m, "OpIntragrupoCorrientes.Tipo16.BI", 510);
    put(m, "OpIntragrupoCorrientes.Tipo16.Cuota", 511);
    put(m, "OpIntragrupoCorrientes.Total.BI", 512);
    put(m, "OpIntragrupoCorrientes.Total.Cuota", 513);
    put(m, "OpIntragrupoCorrientes.Tipo8.BI", 559);
    put(m, "OpIntragrupoCorrientes.Tipo8.Cuota", 560);
    put(m, "OpIntragrupoCorrientes.Tipo18.BI", 561);
    put(m, "OpIntragrupoCorrientes.Tipo18.Cuota", 562);
    put(m, "OpIntragrupoCorrientes.Tipo10.BI", 607);
    put(m, "OpIntragrupoCorrientes.Tipo10.Cuota", 608);
    put(m, "OpIntragrupoCorrientes.Tipo21.BI", 609);
    put(m, "OpIntragrupoCorrientes.Tipo21.Cuota", 610);
    // OpNoSujetas
    put(m, "OpNoSujetas", 110);
    // OpNoSujetasOSS
    put(m, "OpNoSujetasOSS", 126);
    // OpRegEspAgViajes
    put(m, "OpRegEspAgViajes", 228);
    // OpRegEspAgricPescGanad
    put(m, "OpRegEspAgricPescGanad", 101);
    // OpRegEspCriterioCaja
    put(m, "OpRegEspCriterioCaja", 653);
    // OpRegEspRecEquivalencia
    put(m, "OpRegEspRecEquivalencia", 102);
    // OpRegGeneral
    put(m, "OpRegGeneral", 99);
    // OpRegSimplificado
    put(m, "OpRegSimplificado", 100);
    // OpSujISP
    put(m, "OpSujISP", 125);
    // OpSujetas
    put(m, "OpSujetas", 111);
    // RecargoEquivalencia
    put(m, "RecargoEquivalencia.Tipo05.BI", 35);
    put(m, "RecargoEquivalencia.Tipo05.Cuota", 36);
    put(m, "RecargoEquivalencia.Tipo175.BI", 41);
    put(m, "RecargoEquivalencia.Tipo175.Cuota", 42);
    put(m, "RecargoEquivalencia.Tipo14.BI", 599);
    put(m, "RecargoEquivalencia.Tipo14.Cuota", 600);
    put(m, "RecargoEquivalencia.Tipo52.BI", 601);
    put(m, "RecargoEquivalencia.Tipo52.Cuota", 602);
    put(m, "RecargoEquivalencia.Tipo0.BI", 663);
    put(m, "RecargoEquivalencia.Tipo0.Cuota", 664);
    put(m, "RecargoEquivalencia.Tipo062.BI", 665);
    put(m, "RecargoEquivalencia.Tipo062.Cuota", 666);
    put(m, "RecargoEquivalencia.Tipo026.BI", 691);
    put(m, "RecargoEquivalencia.Tipo026.Cuota", 692);
    put(m, "RecargoEquivalencia.Tipo1.BI", 693);
    put(m, "RecargoEquivalencia.Tipo1.Cuota", 694);
    // RectifDeducciones
    put(m, "RectifDeducciones.TipoX.Cuota", 62);
    put(m, "RectifDeducciones.TipoX.BI", 639);
    // RectifOpIntragrupo
    put(m, "RectifOpIntragrupo.TipoX.BI", 651);
    put(m, "RectifOpIntragrupo.TipoX.Cuota", 652);
    // RegAgViajes
    put(m, "RegAgViajes.Tipo21.BI", 13);
    put(m, "RegAgViajes.Tipo21.Cuota", 14);
    // RegBienesUsados
    put(m, "RegBienesUsados.Tipo4.BI", 7);
    put(m, "RegBienesUsados.Tipo4.Cuota", 8);
    put(m, "RegBienesUsados.Tipo10.BI", 9);
    put(m, "RegBienesUsados.Tipo10.Cuota", 10);
    put(m, "RegBienesUsados.Tipo21.BI", 11);
    put(m, "RegBienesUsados.Tipo21.Cuota", 12);
    // RegCriterioCaja
    put(m, "RegCriterioCaja.Tipo4.BI", 643);
    put(m, "RegCriterioCaja.Tipo4.Cuota", 644);
    put(m, "RegCriterioCaja.Tipo10.BI", 645);
    put(m, "RegCriterioCaja.Tipo10.Cuota", 646);
    put(m, "RegCriterioCaja.Tipo21.BI", 647);
    put(m, "RegCriterioCaja.Tipo21.Cuota", 648);
    put(m, "RegCriterioCaja.Tipo2.BI", 675);
    put(m, "RegCriterioCaja.Tipo2.Cuota", 676);
    put(m, "RegCriterioCaja.Tipo75.BI", 677);
    put(m, "RegCriterioCaja.Tipo75.Cuota", 678);
    put(m, "RegCriterioCaja.Tipo0.BI", 708);
    put(m, "RegCriterioCaja.Tipo0.Cuota", 709);
    put(m, "RegCriterioCaja.Tipo5.BI", 710);
    put(m, "RegCriterioCaja.Tipo5.Cuota", 711);
    // RegOrdinario
    put(m, "RegOrdinario.Tipo4.BI", 1);
    put(m, "RegOrdinario.Tipo4.Cuota", 2);
    put(m, "RegOrdinario.Tipo10.BI", 3);
    put(m, "RegOrdinario.Tipo10.Cuota", 4);
    put(m, "RegOrdinario.Tipo21.BI", 5);
    put(m, "RegOrdinario.Tipo21.Cuota", 6);
    put(m, "RegOrdinario.Tipo2.BI", 667);
    put(m, "RegOrdinario.Tipo2.Cuota", 668);
    put(m, "RegOrdinario.Tipo75.BI", 669);
    put(m, "RegOrdinario.Tipo75.Cuota", 670);
    put(m, "RegOrdinario.Tipo0.BI", 700);
    put(m, "RegOrdinario.Tipo0.Cuota", 701);
    put(m, "RegOrdinario.Tipo5.BI", 702);
    put(m, "RegOrdinario.Tipo5.Cuota", 703);
    // RegularizInversiones
    put(m, "RegularizInversiones", 63);
    // RegularizPorcProrrata
    put(m, "RegularizPorcProrrata", 522);
    // ServInversionSP
    put(m, "ServInversionSP", 523);
    // TotalBasesyCuotasIVA
    put(m, "TotalBasesyCuotasIVA.TipoX.BI", 33);
    put(m, "TotalBasesyCuotasIVA.TipoX.Cuota", 34);
    return m;
  }
}
