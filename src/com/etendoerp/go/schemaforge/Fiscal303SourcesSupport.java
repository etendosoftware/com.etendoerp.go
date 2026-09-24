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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.ScrollableResults;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat303.es.api.CashVATOperationType;
import org.openbravo.module.aeat303.es.report.v2014.AEAT303Report2014Dao;

/**
 * Builds the per-invoice {@code sources} rows returned alongside the AEAT 303 boxes by
 * {@code GET /neo/fiscal303/boxes} — on behalf of {@link Fiscal303BoxesHandler}, which
 * constructs one instance of this class and delegates to it from {@code computeBoxes(...)}.
 *
 * <p>Extracted verbatim from {@link Fiscal303BoxesHandler} (ETP-4755) purely to keep that class's
 * method count under the SonarQube {@code java:S1448} threshold, mirroring how the
 * "submission" concern was already split out into {@link Fiscal303SubmissionSupport}. This class
 * is exactly the "invoice sources" concern: iterating {@code C_INVOICETAX} for the tracked tax
 * rates and grouping the results into one row per (invoice, declaration side). {@code owner}'s
 * package-private members are reachable here because this class lives in the same package, not
 * because of any inheritance relationship.</p>
 *
 * <p><b>Grouping (ETP-5456):</b> an AEAT-mandated intra-EU acquisition (adquisición
 * intracomunitaria) — and, identically, a domestic reverse-charge/ISP operation — posts the SAME
 * taxable base/tax amount TWICE on the SAME purchase invoice: once as output/devengado (e.g. box
 * 10/11, {@code VAT_SALES_EU}) and once as input/soportado deductible (e.g. box 36/37 {@code
 * Intracommunity_Goods}). Each box legitimately needs the FULL individual line amount once — that
 * casilla computation path ({@code fillGroupBoxes}/{@code applyPercentageSplit} in {@link
 * Fiscal303BoxesHandler}) is untouched by this class. This class used to sum every matching {@code
 * C_INVOICETAX} line of an invoice into ONE row, which doubled the displayed base/vat for exactly
 * this case; a later fix halved paired lines back down, which worked only because the paired box
 * numbers were hardcoded ({@code REVERSE_CHARGE_PAIRED_BOXES}, since removed).
 *
 * <p>The current, generic approach groups by {@code (invoice, box set)} instead of by invoice
 * alone — see {@link #collectSources}. A line's box set is whatever {@code rateToBoxes} (already
 * covering every tax family) resolves for its rate, redirected through {@link
 * #correctiveBoxesFor} when the invoice is corrective. Two lines of the same invoice that land in
 * the same box set still merge into one row (a domestic invoice with several rates on the same
 * deduction box pair, say); two lines that land in DIFFERENT box sets — the intra-EU/ISP
 * devengado+deducible case — produce two rows with the SAME base/vat, one per side. No box list is
 * hardcoded: any future operation with the same both-sides-of-the-declaration shape is handled
 * automatically because it necessarily uses two different box sets already known to {@code
 * rateToBoxes}.</p>
 */
class Fiscal303SourcesSupport {

  /**
   * ETP-5456 (subtask "las casillas ... factura rectificativa ... como en una normal"): the box
   * numbers a NORMAL sales invoice's tax rate maps to for régimen general / intra-EU / ISP
   * operations — {@code fillSalesBoxes}'s {@code VAT_SALES_GENERAL} split (7/9, 4/6, 1/3, 150/152,
   * 165/167), {@code VAT_SALES_EU} (10/11) and {@code VAT_SALES_ISP} (12/13). When the invoice
   * carrying the tax line is itself corrective/rectificativa, {@link
   * Fiscal303BoxesHandler#fillSalesBoxes} routes the SAME tax rates to box 14/15 instead (via
   * {@code fillMemoCorrectiveBoxPair(b, helper, modificacionBases, 14, 15)}, where {@code
   * modificacionBases} is exactly {@code salesGeneral + euRates + ispRates}). This class's
   * per-invoice "Casillas" column must mirror that redirection — see {@link
   * #correctiveBoxesFor(List)}.
   */
  private static final java.util.Set<Integer> SALES_GENERAL_EU_ISP_BOXES =
      java.util.Set.of(1, 3, 4, 6, 7, 9, 10, 11, 12, 13, 150, 152, 165, 167);

  /** Box 14/15 — "Modificación bases y cuotas" (régimen general): see {@link
   *  #SALES_GENERAL_EU_ISP_BOXES}. */
  private static final List<Integer> SALES_GENERAL_CORRECTIVE_BOXES = List.of(14, 15);

  /**
   * The {@code VAT_SALES_EC} (recargo de equivalencia) box pairs — 19/21, 22/24, 156/158, and
   * either 16/18 or 168/170 depending on form version (see {@code vatEcBoxes}). When the invoice
   * is corrective, {@code fillSalesBoxes} redirects these same tax rates ({@code ecTaxes}) to box
   * 25/26 via {@code fillMemoCorrectiveBoxPair(b, helper, ecTaxes, 25, 26)}.
   */
  private static final java.util.Set<Integer> SALES_EC_BOXES =
      java.util.Set.of(16, 18, 19, 21, 22, 24, 156, 158, 168, 170);

  /** Box 25/26 — "Modificaciones bases y cuotas del recargo de equivalencia": see
   *  {@link #SALES_EC_BOXES}. */
  private static final List<Integer> SALES_EC_CORRECTIVE_BOXES = List.of(25, 26);

  /**
   * The deductible-purchase box pairs {@code fillPurchaseBoxes} assigns per operation type —
   * Normal_Operations (28/29), Investment_Goods (30/31), Import_Goods (32/33),
   * Import_Investment_Goods (34/35), Intracommunity_Goods (36/37) and Intracommunity_Investments
   * (38/39). When the invoice is corrective, {@code fillPurchaseBoxes} redirects the UNION of all
   * of them ({@code rectificacionDeduccionesTaxes}) to box 40/41 via {@code
   * fillMemoCorrectiveBoxPair(b, helper, rectificacionDeduccionesTaxes, 40, 41)}.
   */
  private static final java.util.Set<Integer> PURCHASE_DEDUCTION_BOXES =
      java.util.Set.of(28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39);

  /** Box 40/41 — "Rectificación de deducciones": see {@link #PURCHASE_DEDUCTION_BOXES}. */
  private static final List<Integer> PURCHASE_CORRECTIVE_BOXES = List.of(40, 41);

  /**
   * Document base types the classic engine treats as "invoice-shaped" — i.e. documents whose
   * corrective-vs-normal nature is decided by the SIGN of the tax line, not by the document type
   * itself: {@code ARI} (sales invoice), {@code API} (purchase invoice) and {@code ARI_RM}
   * (reversed sales invoice). Mirrors the first branch of {@code org.openbravo.module.aeat303.es
   * .util.AEAT303CalculationsHelper#calculateCorrectiveOperations}. Everything else that is
   * corrective ({@code ARC}/{@code APC} credit memos, and any reversal document type) is
   * corrective unconditionally.
   */
  private static final java.util.Set<String> INVOICE_SHAPED_DOC_CATEGORIES =
      java.util.Set.of("ARI", "API", "ARI_RM");

  private final Fiscal303BoxesHandler owner;

  Fiscal303SourcesSupport(Fiscal303BoxesHandler owner) {
    this.owner = owner;
  }

  /**
   * Iterates C_INVOICETAX for all tracked tax rates and builds one source row per (invoice,
   * declaration side) — see class javadoc for why an invoice is not always exactly one row.
   */
  List<Map<String, Object>> collectSources(
      Organization org, List<Period> periods,
      AEAT303Report2014Dao dao303,
      Map<String, List<Integer>> rateToBoxes) {

    if (rateToBoxes.isEmpty()) return Collections.emptyList();

    List<TaxRate> allRates = buildRatesList(rateToBoxes);
    // ETP-5456: keyed by (invoice id, display box set) instead of invoice id alone — see class
    // javadoc. Two lines of the same invoice that resolve to the SAME display boxes still merge
    // into one row; lines that resolve to different box sets (the intra-EU/ISP devengado +
    // deducible case) produce one row per set.
    Map<String, Map<String, Object>> byGroup = new LinkedHashMap<>();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    ScrollableResults sr = dao303.getInvoiceTax(
        org, allRates, periods, CashVATOperationType.ONLY_NONCASHVAT);
    try {
      while (sr.next()) {
        InvoiceTax it = (InvoiceTax) sr.get(0);
        Invoice inv   = it.getInvoice();
        // `boxes` is the rate's NORMAL-operation box pair (rateToBoxes is built once from the tax
        // rate's static category, with no awareness of whether THIS invoice is corrective);
        // `displayBoxes` redirects it through the corrective family when the invoice itself is
        // corrective — see correctiveBoxesFor/isCorrectiveInvoiceTax. displayBoxes is what both
        // the grouping key and the row's visible "Casillas"/"type" are derived from, since it is
        // where the money actually lands on the aggregate totals.
        List<Integer> boxes = rateToBoxes.get(it.getTax().getId());
        if (boxes != null) {
          List<Integer> displayBoxes =
              isCorrectiveInvoiceTax(it, inv) ? correctiveBoxesFor(boxes) : boxes;
          String groupKey = inv.getId() + "|" + displayBoxes;
          Map<String, Object> row =
              byGroup.computeIfAbsent(groupKey, k -> buildNewInvoiceRow(inv, sdf, displayBoxes));
          accumulateInvoiceTax(row, it);
        }
        OBDal.getInstance().getSession().evict(it);
        OBDal.getInstance().getSession().evict(inv);
      }
    } finally {
      sr.close();
    }

    List<Map<String, Object>> result = new ArrayList<>(byGroup.values());
    result.forEach(this::finalizeInvoiceRow);
    return result;
  }

  private List<TaxRate> buildRatesList(Map<String, List<Integer>> rateToBoxes) {
    List<TaxRate> allRates = new ArrayList<>();
    for (String id : rateToBoxes.keySet()) {
      TaxRate tr = OBDal.getInstance().get(TaxRate.class, id);
      if (tr != null) allRates.add(tr);
    }
    return allRates;
  }

  private Map<String, Object> buildNewInvoiceRow(Invoice inv, SimpleDateFormat sdf,
      List<Integer> displayBoxes) {
    Map<String, Object> r = new LinkedHashMap<>();
    // ETP-5393 Bug A: the invoice's own id must travel into the row so the frontend has a
    // collision-free React key. `ref` (documentno) is NOT unique across AR/AP — sales and
    // purchase invoice numbering sequences are independent and can legitimately coincide.
    r.put("id",    inv.getId());
    r.put("ref",   inv.getDocumentNo());
    r.put("date",  sdf.format(inv.getInvoiceDate()));
    r.put("accountingDate", inv.getAccountingDate() != null ? sdf.format(inv.getAccountingDate()) : null);
    // ETP-5456: the "side" of the declaration is now derived from which box family this row's
    // OWN casillas belong to, not from the invoice's isSOTrx/document category — a single
    // intra-EU/ISP invoice produces one row per side (see class javadoc), so the side cannot be a
    // property of the invoice alone. Frontend translates this machine key — see FmTabContent.jsx.
    r.put("type",  isPurchaseSideBoxes(displayBoxes) ? "deductible" : "accrued");
    r.put("party", inv.getBusinessPartner() != null ? inv.getBusinessPartner().getName() : "");
    r.put("base",  BigDecimal.ZERO);
    r.put("vat",   BigDecimal.ZERO);
    r.put(Fiscal303BoxesHandler.BOXES, new java.util.LinkedHashSet<>(displayBoxes));
    return r;
  }

  /**
   * Whether {@code displayBoxes} belongs to the deductible/soportado (purchase) side of the
   * declaration rather than the devengado (sales) side — generic over the box FAMILY, not a
   * fixed intra-EU/ISP list: any box in {@link #PURCHASE_DEDUCTION_BOXES} or {@link
   * #PURCHASE_CORRECTIVE_BOXES} means deductible; everything this class ever assigns as {@code
   * displayBoxes} (see {@link #correctiveBoxesFor}) is one or the other, never mixed.
   */
  private boolean isPurchaseSideBoxes(List<Integer> displayBoxes) {
    for (Integer bx : displayBoxes) {
      if (PURCHASE_DEDUCTION_BOXES.contains(bx) || PURCHASE_CORRECTIVE_BOXES.contains(bx)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Accumulates one {@code C_INVOICETAX} line into its row's base/vat, reproducing the sign the
   * classic engine gives it in the real AEAT 303 file (ETP-5456, PM-reported bug: a corrective
   * invoice with a negative base was shown positive in this tab). Mirrors {@code
   * org.openbravo.module.aeat303.es.util.AEAT303CalculationsHelper#calculateNormalOperations /
   * #calculateCorrectiveOperations}: the casilla always receives {@code .abs()} of the amount,
   * and the SIGN comes from which bucket the line is routed to (normal adds, corrective
   * subtracts) — a choice driven by {@code taxableAmount.signum()}. Reproducing that line by
   * line: the base keeps {@code taxableAmount}'s own sign untouched, and the tax magnitude is
   * {@code .abs()}'d — a reverse-charge line's rate is modeled as e.g. {@code -21%}, which is an
   * artifact of how the rate is stored, not a business sign — then re-signed to match the base.
   */
  private void accumulateInvoiceTax(Map<String, Object> row, InvoiceTax it) {
    BigDecimal base = it.getTaxableAmount() != null ? it.getTaxableAmount() : BigDecimal.ZERO;
    BigDecimal tax  = it.getTaxAmount()     != null ? it.getTaxAmount().abs() : BigDecimal.ZERO;
    if (base.signum() < 0) {
      tax = tax.negate();
    }
    // ETP-5456 code review (Alex, B1): accumulate at full precision here and round exactly ONCE,
    // in finalizeInvoiceRow, after every line has been added, to avoid intermediate-rounding
    // drift across a ".xx5" boundary.
    row.put("base", ((BigDecimal) row.get("base")).add(base));
    row.put("vat",  ((BigDecimal) row.get("vat")).add(tax));
  }

  /**
   * Redirects a tax rate's NORMAL-operation box pair to the corrective box pair it lands in on
   * the aggregate totals when the invoice is corrective/rectificativa (or a credit memo). Falls
   * back to {@code normalBoxes} unchanged if it belongs to none of the three known families —
   * defensive only, should not happen for any box {@link #accumulateInvoiceTax} ever sees.
   */
  private List<Integer> correctiveBoxesFor(List<Integer> normalBoxes) {
    for (Integer bx : normalBoxes) {
      if (SALES_GENERAL_EU_ISP_BOXES.contains(bx)) return SALES_GENERAL_CORRECTIVE_BOXES;
      if (SALES_EC_BOXES.contains(bx)) return SALES_EC_CORRECTIVE_BOXES;
      if (PURCHASE_DEDUCTION_BOXES.contains(bx)) return PURCHASE_CORRECTIVE_BOXES;
    }
    return normalBoxes;
  }

  /**
   * Whether the invoice carrying this tax line is corrective/rectificativa, for the purpose of
   * this per-invoice "Casillas" display label ONLY.
   *
   * <p>This MUST mirror {@code org.openbravo.module.aeat303.es.util.AEAT303CalculationsHelper
   * #calculateCorrectiveOperations} EXACTLY, because the aggregate box totals of this same tab —
   * and, more importantly, the real AEAT 303 file produced by the classic module
   * ({@code AEAT303Report2014} / {@code AEAT303SubmissionService}) — classify a line with that
   * rule. The generated file is the source of truth; a per-invoice "Casillas" label that used a
   * different signal (e.g. the {@code C_DocType.EM_ETSG_IsRectificative} flag) diverges from the
   * file for a POSITIVE-amount rectificativa: the file counts it as a normal operation
   * (10/11/36/37) while the label would show the corrective boxes (14/15, 40/41).
   *
   * <p>The classic rule, verbatim:
   * <ul>
   *   <li>a reversal document type, or doc base type {@code ARC}/{@code APC} (credit memo) →
   *       always corrective;</li>
   *   <li>doc base type {@code ARI}/{@code API}/{@code ARI_RM} → corrective iff the line's
   *       taxable amount is negative;</li>
   *   <li>anything else → not corrective.</li>
   * </ul>
   */
  private boolean isCorrectiveInvoiceTax(InvoiceTax it, Invoice inv) {
    final String docBaseType = inv.getDocumentType().getDocumentCategory();
    final boolean isReversal = Boolean.TRUE.equals(inv.getDocumentType().isReversal());
    if (isReversal || "ARC".equals(docBaseType) || "APC".equals(docBaseType)) {
      return true;
    }
    if (INVOICE_SHAPED_DOC_CATEGORIES.contains(docBaseType)) {
      return it.getTaxableAmount() != null && it.getTaxableAmount().signum() < 0;
    }
    return false;
  }

  void finalizeInvoiceRow(Map<String, Object> row) {
    @SuppressWarnings("unchecked")
    java.util.LinkedHashSet<Integer> bSet =
        (java.util.LinkedHashSet<Integer>) row.get(Fiscal303BoxesHandler.BOXES);
    List<Integer> sorted = new ArrayList<>(bSet);
    Collections.sort(sorted);
    StringBuilder sb = new StringBuilder();
    for (Integer bx : sorted) {
      if (sb.length() > 0) {
        sb.append(",");
      }
      sb.append(bx);
    }
    row.put(Fiscal303BoxesHandler.BOXES, sb.toString());
    // ETP-5456 (Alex, B1): base/vat arrive here at full accumulation precision (see
    // accumulateInvoiceTax) — round each exactly once, here, before deriving total from the
    // already-rounded values so base + vat == total always holds on the rounded figures shown.
    BigDecimal base = owner.round((BigDecimal) row.get("base"));
    BigDecimal vat  = owner.round((BigDecimal) row.get("vat"));
    row.put("base", base);
    row.put("vat",  vat);
    row.put("total", owner.round(base.add(vat)));
  }
}
