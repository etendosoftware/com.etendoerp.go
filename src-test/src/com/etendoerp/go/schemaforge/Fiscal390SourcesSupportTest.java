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
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.taxreportlauncher.TaxReportParameter;

/**
 * Unit tests of the Modelo 390 Facturas tab ({@link Fiscal390SourcesSupport}): the rows come from
 * the same box walk as {@link Fiscal390BoxesCalculator}, so for every box the lines attributed to
 * it add up to the box value — the acceptance criterion of ETP-5716 — plus the filters, totals,
 * pagination and the submitted-declaration answer of {@code GET /fiscal390/sources}.
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal390SourcesSupport
 * @covers com.etendoerp.go.schemaforge.Fiscal390BoxesCalculator
 */
public class Fiscal390SourcesSupportTest {

  // ── fake engine ─────────────────────────────────────────────────────

  private static final class Param {
    final TaxReportParameter parameter = mock(TaxReportParameter.class);
    final List<TaxRate> taxes = new ArrayList<>();
    final Map<TaxRate, List<Fiscal390SourcesSupport.SourceLine>> lines = new HashMap<>();
  }

  /** Serves the same lines both as a {@code LineEngine} and as a plain calculator engine. */
  private static final class FakeLineEngine implements Fiscal390SourcesSupport.LineEngine {
    final Map<String, List<Param>> groups = new HashMap<>();
    final Map<String, TaxRate> taxesById = new HashMap<>();

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

    /** The tax rate {@code id} (shared across parameters, as in the classic configuration). */
    TaxRate tax(String id, String rate) {
      return taxesById.computeIfAbsent(id, k -> {
        TaxRate t = mock(TaxRate.class);
        when(t.getId()).thenReturn(id);
        when(t.getRate()).thenReturn(new BigDecimal(rate));
        return t;
      });
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
    public List<Fiscal390SourcesSupport.SourceLine> lines(TaxReportParameter parameter,
        List<TaxRate> taxes) {
      Param p = find(parameter);
      List<Fiscal390SourcesSupport.SourceLine> out = new ArrayList<>();
      for (TaxRate t : taxes) {
        out.addAll(p.lines.getOrDefault(t, Collections.emptyList()));
      }
      return out;
    }

    /** The engine the boxes endpoint would see: each figure is the sum of its lines. */
    Fiscal390BoxesCalculator.Engine summing() {
      FakeLineEngine self = this;
      return new Fiscal390BoxesCalculator.Engine() {
        @Override
        public List<TaxReportParameter> outputParameters(String groupKey) {
          return self.outputParameters(groupKey);
        }

        @Override
        public List<TaxRate> taxes(TaxReportParameter parameter) {
          return self.taxes(parameter);
        }

        @Override
        public BigDecimal[] amounts(TaxReportParameter parameter, List<TaxRate> taxes) {
          BigDecimal base = BigDecimal.ZERO;
          BigDecimal cuota = BigDecimal.ZERO;
          for (Fiscal390SourcesSupport.SourceLine l : self.lines(parameter, taxes)) {
            base = base.add(l.base());
            cuota = cuota.add(l.cuota());
          }
          return new BigDecimal[] { base, cuota };
        }
      };
    }
  }

  private static int seq = 0;

  /** Adds a line of invoice {@code doc} with tax {@code tax} to parameter {@code p}. */
  private static Fiscal390SourcesSupport.SourceLine line(Param p, TaxRate tax, String lineId,
      String doc, boolean sales, String party, String base, String cuota) {
    if (!p.taxes.contains(tax)) {
      p.taxes.add(tax);
    }
    Fiscal390SourcesSupport.SourceLine l = new Fiscal390SourcesSupport.SourceLine(lineId,
        "INV-" + doc, doc, new GregorianCalendar(2026, 0, 1 + (seq++ % 28)).getTime(),
        new GregorianCalendar(2026, 0, 2).getTime(), sales, party, "AR Invoice", tax.getId(),
        "IVA " + tax.getRate().stripTrailingZeros().toPlainString() + "%", tax.getRate(),
        new BigDecimal(base), new BigDecimal(cuota));
    p.lines.computeIfAbsent(tax, k -> new ArrayList<>()).add(l);
    return l;
  }

  /**
   * A year with: split sales by rate (two invoices at 21 %, one at 10 %, a credit memo at 21 %),
   * an unsupported 3 % rate, the [33]/[34] total, a split purchase element with Total, a
   * corrective purchase, an intra-EU acquisition feeding both sides and two volume boxes (one of
   * them negative, which classic skips).
   */
  private static FakeLineEngine typicalYear() {
    FakeLineEngine e = new FakeLineEngine();
    TaxRate s21 = e.tax("S21", "21");
    TaxRate s10 = e.tax("S10", "10");
    TaxRate s3 = e.tax("S3", "3");
    TaxRate p21 = e.tax("P21", "21");
    TaxRate p10 = e.tax("P10", "10");
    TaxRate ue = e.tax("UE21", "21");

    Param ordinary = e.add(Fiscal390BoxesCalculator.VAT_SALES, "VAT_SALES_ORDINARY",
        "RegOrdinario", true, false);
    line(ordinary, s21, "IT1", "F001", true, "Acme", "1000.00", "210.00");
    line(ordinary, s21, "IT2", "F002", true, "Beta", "500.00", "105.00");
    line(ordinary, s21, "IT3", "AB01", true, "Acme", "-100.00", "-21.00");
    line(ordinary, s10, "IT4", "F003", true, "Gamma", "200.00", "20.00");
    line(ordinary, s3, "IT5", "F004", true, "Delta", "50.00", "1.50"); // unsupported rate
    Param total = e.add(Fiscal390BoxesCalculator.VAT_SALES, "VAT_SALES_TOTAL",
        "TotalBasesyCuotasIVA", false, false);
    line(total, s21, "IT1", "F001", true, "Acme", "1000.00", "210.00");
    line(total, s21, "IT2", "F002", true, "Beta", "500.00", "105.00");
    line(total, s21, "IT3", "AB01", true, "Acme", "-100.00", "-21.00");
    line(total, s10, "IT4", "F003", true, "Gamma", "200.00", "20.00");
    Param intraSales = e.add(Fiscal390BoxesCalculator.VAT_SALES, "VAT_SALES_EU_GOODS",
        "AdqIntracomBienes", true, false);
    line(intraSales, ue, "IT9", "C900", false, "EuroSupplier", "300.00", "63.00");

    Param purchase = e.add(Fiscal390BoxesCalculator.VAT_PURCHASE, "VAT_PURCHASE_NORMAL",
        "OpInterioresBienesServiciosCorrientes", true, true);
    line(purchase, p21, "IT6", "C001", false, "Supplier A", "400.00", "84.00");
    line(purchase, p10, "IT7", "C002", false, "Supplier B", "100.00", "10.00");
    Param rectif = e.add(Fiscal390BoxesCalculator.VAT_PURCHASE, "VAT_PURCHASE_MOD_BASESYCUOTAS",
        "RectifDeducciones", false, false);
    line(rectif, p21, "IT8", "C003", false, "Supplier A", "-50.00", "-10.50");
    Param intraPurchase = e.add(Fiscal390BoxesCalculator.VAT_PURCHASE, "VAT_PURCHASE_EU_GOODS",
        "AdqIntracomunitariasBienesCorrientes", true, true);
    line(intraPurchase, ue, "IT9", "C900", false, "EuroSupplier", "300.00", "63.00");

    Param general = e.add(Fiscal390BoxesCalculator.VOLUME_OPERATIONS, "GENERAL_OPERATIONS",
        "OpRegGeneral", false, false);
    line(general, s21, "IT1", "F001", true, "Acme", "1000.00", "210.00");
    line(general, s21, "IT2", "F002", true, "Beta", "500.00", "105.00");
    line(general, s10, "IT4", "F003", true, "Gamma", "200.00", "20.00");
    Param property = e.add(Fiscal390BoxesCalculator.VOLUME_OPERATIONS, "NON_COMMON_OPERATIONS",
        "EntregasBienesInmuebles", false, false);
    line(property, s21, "IT3", "AB01", true, "Acme", "-100.00", "-21.00"); // negative: skipped
    return e;
  }

  private static BigDecimal sumForBox(List<Fiscal390SourcesSupport.Row> rows, int box) {
    BigDecimal sum = BigDecimal.ZERO;
    for (Fiscal390SourcesSupport.Row r : rows) {
      BigDecimal v = r.boxAmounts.get(box);
      if (v != null) {
        sum = sum.add(v);
      }
    }
    return sum;
  }

  private static Fiscal390SourcesSupport.Row row(List<Fiscal390SourcesSupport.Row> rows,
      String id) {
    for (Fiscal390SourcesSupport.Row r : rows) {
      if (id.equals(r.id)) {
        return r;
      }
    }
    fail("no row " + id);
    return null;
  }

  // ── acceptance criterion: per-box sums ──────────────────────────────

  /** For every box filled from invoices, its rows add up to what the calculator computes. */
  @Test
  public void testPerBoxSumsEqualCalculatorBoxValues() {
    FakeLineEngine e = typicalYear();
    Fiscal390BoxesCalculator.Result expected =
        Fiscal390BoxesCalculator.compute(e.summing(), Collections.emptyMap());

    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(e);

    assertFalse(expected.invoiceBoxes.isEmpty());
    assertEquals(expected.invoiceBoxes, c.boxes.keySet());
    for (Integer box : expected.invoiceBoxes) {
      BigDecimal value = expected.boxes.get(box);
      assertEquals("box " + box, 0, value.compareTo(sumForBox(c.rows, box)));
      assertEquals("box " + box, 0, value.compareTo(c.boxes.get(box)));
    }
  }

  /** The rows feed only invoice boxes — never a derived total such as [47]/[64]/[65]/[108]. */
  @Test
  public void testRowsNeverFeedDerivedBoxes() {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(typicalYear());
    for (Fiscal390SourcesSupport.Row r : c.rows) {
      for (int derived : new int[] { 47, 64, 65, 84, 86, 108 }) {
        assertFalse(r.id + " feeds " + derived, r.boxAmounts.containsKey(derived));
      }
    }
  }

  /** A line of a rate the 390 does not admit is left out, as its figure is left out of the boxes. */
  @Test
  public void testUnsupportedRateLineIsNotListed() {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(typicalYear());
    for (Fiscal390SourcesSupport.Row r : c.rows) {
      assertFalse("IT5".equals(r.line.invoiceTaxId()));
    }
  }

  /** A sales line feeds its rate boxes, the [33]/[34] total and the volume box, in one row. */
  @Test
  public void testOneRowPerLineWithEveryBoxItFeeds() {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(typicalYear());
    Fiscal390SourcesSupport.Row it1 = row(c.rows, "IT1");

    assertEquals(0, new BigDecimal("1000").compareTo(it1.base));
    assertEquals(0, new BigDecimal("210").compareTo(it1.cuota));
    assertEquals(0, new BigDecimal("1000").compareTo(it1.boxAmounts.get(5)));
    assertEquals(0, new BigDecimal("210").compareTo(it1.boxAmounts.get(6)));
    assertEquals(0, new BigDecimal("1000").compareTo(it1.boxAmounts.get(99)));
    assertTrue(it1.boxAmounts.size() >= 4); // rate pair, [33]/[34], [99]
  }

  /** A credit memo line keeps its negative sign, and the skipped negative volume base is absent. */
  @Test
  public void testCreditMemoLineIsNegativeAndSkippedVolumeIsAbsent() {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(typicalYear());
    Fiscal390SourcesSupport.Row memo = row(c.rows, "IT3");

    assertEquals(0, new BigDecimal("-100").compareTo(memo.base));
    assertEquals(0, new BigDecimal("-100").compareTo(memo.boxAmounts.get(5)));
    Integer propertyBox = Fiscal390BoxLayout.box("EntregasBienesInmuebles");
    assertFalse(memo.boxAmounts.containsKey(propertyBox));
  }

  /** An intra-EU acquisition feeds both an accrued and a deductible box, in one purchase row. */
  @Test
  public void testIntraEuAcquisitionFeedsBothSides() {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(typicalYear());
    Fiscal390SourcesSupport.Row it9 = row(c.rows, "IT9");

    assertEquals(Fiscal390BoxLayout.box("AdqIntracomBienes", "Tipo21", "BI"),
        it9.boxAmounts.keySet().iterator().next());
    assertTrue(it9.boxAmounts.containsKey(
        Fiscal390BoxLayout.box("AdqIntracomunitariasBienesCorrientes", "Total", "Cuota")));
    assertFalse(it9.line.sales());
    // ETP-5716 — Tipo as the 303: the row belongs to both sides of the declaration.
    assertEquals(new java.util.TreeSet<>(java.util.List.of("accrued", "deductible")),
        Fiscal390SourcesSupport.sidesOf(it9));
  }

  /** Tipo of a plain sale / purchase; a filter by side keeps an intra-EU row on both. */
  @Test
  public void testSidesAndTheirFilter() throws Exception {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(typicalYear());
    assertEquals(java.util.Set.of("accrued"), Fiscal390SourcesSupport.sidesOf(row(c.rows, "IT1")));
    assertEquals(java.util.Set.of("deductible"),
        Fiscal390SourcesSupport.sidesOf(row(c.rows, "IT6")));

    JSONObject accrued = Fiscal390SourcesSupport.buildResponse(c,
        query(null, Fiscal390SourcesSupport.SIDE_ACCRUED, null, null, 1, 0));
    assertEquals(5, accrued.getInt("count")); // IT1, IT2, IT3, IT4 + IT9 (intra-EU)
    JSONObject deductible = Fiscal390SourcesSupport.buildResponse(c,
        query(null, Fiscal390SourcesSupport.SIDE_DEDUCTIBLE, null, null, 1, 0));
    assertEquals(4, deductible.getInt("count")); // IT6, IT7, IT8 + IT9
    JSONArray rows = deductible.getJSONArray("rows");
    boolean sawBoth = false;
    for (int i = 0; i < rows.length(); i++) {
      JSONArray sides = rows.getJSONObject(i).getJSONArray("sides");
      sawBoth |= sides.length() == 2;
    }
    assertTrue(sawBoth);
  }

  /** Cash-VAT lines (one per payment) of the same invoice tax add up into one row. */
  @Test
  public void testSeveralLinesOfTheSameInvoiceTaxAreSummed() {
    FakeLineEngine e = new FakeLineEngine();
    TaxRate s21 = e.tax("S21", "21");
    Param ordinary = e.add(Fiscal390BoxesCalculator.VAT_SALES, "VAT_SALES_ORDINARY",
        "RegOrdinario", true, false);
    line(ordinary, s21, "IT1", "F001", true, "Acme", "60.00", "12.60");
    line(ordinary, s21, "IT1", "F001", true, "Acme", "40.00", "8.40");

    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(e);

    assertEquals(1, c.rows.size());
    assertEquals(0, new BigDecimal("100").compareTo(c.rows.get(0).base));
    assertEquals(0, new BigDecimal("21").compareTo(c.rows.get(0).cuota));
  }

  /** No configured parameter: no rows, no boxes. */
  @Test
  public void testEmptyConfigurationYieldsNothing() {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(new FakeLineEngine());
    assertTrue(c.rows.isEmpty());
    assertTrue(c.boxes.isEmpty());
  }

  // ── response: filters, totals, pagination ───────────────────────────

  private static Fiscal390SourcesSupport.Query query(Integer box, String type, String tax,
      String q, int page, int size) {
    return new Fiscal390SourcesSupport.Query(box, type, tax, q, page, size);
  }

  /** Filtered by a box, the "box" total equals that box value. */
  @Test
  public void testBoxFilterTotalEqualsBoxValue() throws Exception {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(typicalYear());
    for (Map.Entry<Integer, BigDecimal> box : c.boxes.entrySet()) {
      JSONObject res = Fiscal390SourcesSupport.buildResponse(c,
          query(box.getKey(), null, null, null, 1, 0));
      assertEquals("box " + box.getKey(), 0,
          box.getValue().compareTo(new BigDecimal(res.getJSONObject("totals").getString("box"))));
    }
  }

  @Test
  public void testTypeTaxAndSearchFilters() throws Exception {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(typicalYear());

    JSONObject sales = Fiscal390SourcesSupport.buildResponse(c,
        query(null, Fiscal390SourcesSupport.TYPE_SALES, null, null, 1, 0));
    JSONArray rows = sales.getJSONArray("rows");
    for (int i = 0; i < rows.length(); i++) {
      assertEquals("sales", rows.getJSONObject(i).getString("type"));
    }
    assertEquals(4, sales.getInt("count")); // IT1, IT2, IT3, IT4

    JSONObject byTax = Fiscal390SourcesSupport.buildResponse(c, query(null, null, "S10", null, 1, 0));
    assertEquals(1, byTax.getInt("count"));
    assertEquals("F003", byTax.getJSONArray("rows").getJSONObject(0).getString("ref"));

    JSONObject search = Fiscal390SourcesSupport.buildResponse(c, query(null, null, null, "acme", 1, 0));
    assertEquals(2, search.getInt("count")); // F001 and AB01
    assertEquals("900.00", search.getJSONObject("totals").getString("base"));
    assertNull(search.getJSONObject("totals").opt("box"));
  }

  @Test
  public void testPaginationKeepsTotalsOfEveryFilteredRow() throws Exception {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(typicalYear());
    JSONObject all = Fiscal390SourcesSupport.buildResponse(c, Fiscal390SourcesSupport.Query.ALL);
    JSONObject page2 = Fiscal390SourcesSupport.buildResponse(c, query(null, null, null, null, 2, 3));

    assertEquals(all.getInt("count"), page2.getInt("count"));
    assertEquals(3, page2.getJSONArray("rows").length());
    assertEquals(all.getJSONArray("rows").getJSONObject(3).getString("id"),
        page2.getJSONArray("rows").getJSONObject(0).getString("id"));
    assertEquals(all.getJSONObject("totals").getString("base"),
        page2.getJSONObject("totals").getString("base"));
    JSONObject beyond = Fiscal390SourcesSupport.buildResponse(c, query(null, null, null, null, 99, 3));
    assertEquals(0, beyond.getJSONArray("rows").length());
  }

  @Test
  public void testRowJsonShapeAndFacets() throws Exception {
    Fiscal390SourcesSupport.Collected c = Fiscal390SourcesSupport.collect(typicalYear());
    JSONObject res = Fiscal390SourcesSupport.buildResponse(c, query(null, null, "S10", null, 1, 50));
    JSONObject r = res.getJSONArray("rows").getJSONObject(0);

    assertEquals("IT4", r.getString("id"));
    assertEquals("INV-F003", r.getString("invoiceId"));
    assertEquals("IVA 10%", r.getString("tax"));
    assertEquals("10", r.getString("rate"));
    assertEquals("200.00", r.getString("base"));
    assertEquals("20.00", r.getString("vat"));
    assertEquals("220.00", r.getString("total"));
    assertEquals("2026-01-02", r.getString("accountingDate"));
    assertTrue(r.getString("boxes").matches("\\d+(,\\d+)*"));
    assertEquals("200.00", r.getJSONObject("boxAmounts").getString("3"));
    assertFalse(res.getBoolean("submitted"));
    assertEquals(c.boxes.size(), res.getJSONObject("boxes").length());
    assertEquals(5, res.getJSONArray("taxes").length()); // S21 S10 P21 P10 UE21 (not S3)
  }

  @Test
  public void testSubmittedResponseHasNoDetail() throws Exception {
    JSONObject res = Fiscal390SourcesSupport.submittedResponse(Fiscal390SourcesSupport.Query.ALL);
    assertTrue(res.getBoolean("submitted"));
    assertEquals(0, res.getJSONArray("rows").length());
    assertEquals(0, res.getInt("count"));
  }

  // ── request parsing ─────────────────────────────────────────────────

  private static HttpServletRequest request(String... kv) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    for (int i = 0; i < kv.length; i += 2) {
      when(req.getParameter(kv[i])).thenReturn(kv[i + 1]);
    }
    return req;
  }

  @Test
  public void testQueryFromRequest() {
    Fiscal390SourcesSupport.Query q = Fiscal390SourcesSupport.Query.from(request(
        "box", "05", "type", "sales", "tax", "S21", "q", " acme ", "page", "2", "pageSize", "20"));
    assertEquals(Integer.valueOf(5), q.box());
    assertEquals("sales", q.type());
    assertEquals("S21", q.taxId());
    assertEquals("acme", q.search());
    assertEquals(2, q.page());
    assertEquals(20, q.pageSize());

    Fiscal390SourcesSupport.Query defaults = Fiscal390SourcesSupport.Query.from(request());
    assertNull(defaults.box());
    assertEquals(1, defaults.page());
    assertEquals(Fiscal390SourcesSupport.DEFAULT_PAGE_SIZE, defaults.pageSize());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testQueryRejectsTooLargePage() {
    Fiscal390SourcesSupport.Query.from(request("pageSize", "100000"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testQueryRejectsNonNumericBox() {
    Fiscal390SourcesSupport.Query.from(request("box", "abc"));
  }

  // ── classic CSV capture ─────────────────────────────────────────────

  /** The classic helper's CSV lines are copied into plain data, with the amounts it signed. */
  @Test
  public void testCapturingCsvCopiesTheLine() {
    InvoiceTax it = mock(InvoiceTax.class);
    Invoice inv = mock(Invoice.class);
    TaxRate tax = mock(TaxRate.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    DocumentType dt = mock(DocumentType.class);
    when(it.getId()).thenReturn("IT1");
    when(it.getInvoice()).thenReturn(inv);
    when(it.getTax()).thenReturn(tax);
    when(inv.getId()).thenReturn("INV1");
    when(inv.getDocumentNo()).thenReturn("F001");
    when(inv.isSalesTransaction()).thenReturn(true);
    when(inv.getBusinessPartner()).thenReturn(bp);
    when(inv.getDocumentType()).thenReturn(dt);
    when(bp.getName()).thenReturn("Acme");
    when(dt.getIdentifier()).thenReturn("AR Invoice");
    when(tax.getId()).thenReturn("S21");
    when(tax.getName()).thenReturn("IVA 21%");
    when(tax.getRate()).thenReturn(new BigDecimal("21"));

    Fiscal390SourcesSupport.CapturingCsv csv = new Fiscal390SourcesSupport.CapturingCsv();
    csv.addAEAT390CSVLine(it, new BigDecimal("-100.00"), new BigDecimal("-21.00"),
        mock(TaxReportParameter.class));

    assertEquals(1, csv.lines.size());
    Fiscal390SourcesSupport.SourceLine l = csv.lines.get(0);
    assertEquals("IT1", l.invoiceTaxId());
    assertEquals("F001", l.documentNo());
    assertTrue(l.sales());
    assertEquals("Acme", l.party());
    assertEquals("S21", l.taxId());
    assertEquals(0, new BigDecimal("-100").compareTo(l.base()));
    assertEquals(0, new BigDecimal("-21").compareTo(l.cuota()));
  }
}
