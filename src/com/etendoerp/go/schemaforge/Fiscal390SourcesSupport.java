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
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat390.es.api.AEATIVA_ObjectFactory;
import org.openbravo.module.aeat390.es.api.TipoBaseImponibleYCuota;
import org.openbravo.module.aeat390.es.api.cashvat.AEAT390CashVATReadyCalculationsHelper;
import org.openbravo.module.aeat390.es.api.cashvat.AEAT390CashVATReadyDao;
import org.openbravo.module.aeat390.es.csv.AEAT390CSV;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.TaxReportGroup;
import org.openbravo.module.taxreportlauncher.TaxReportParameter;

/**
 * The Modelo 390 "Facturas" tab (ETP-5716): {@code GET /fiscal390/sources}, the invoice-tax lines
 * behind the invoice-derived boxes.
 *
 * <p><b>Same computation as the boxes.</b> The rows are not a second query: the box walk of
 * {@link Fiscal390BoxesCalculator} runs as for {@code GET /fiscal390/boxes}, but every
 * {@link Fiscal390BoxesCalculator.Engine#amounts} figure is built from the lines the classic
 * helper ({@link AEAT390CashVATReadyCalculationsHelper#calculateAmounts}) adds to its CSV — the
 * per-line, already signed and rounded amounts it sums into that figure (posted invoices of the
 * year; normal, negative, reversal and credit-memo lines, cash VAT included; classic's own CSV
 * detail file is built from the same calls). Each {@link Fiscal390BoxesCalculator.Engine#placed}
 * then attributes those lines to the box(es) the figure landed in. So, by construction, for each
 * box the sum of its lines' {@code boxAmounts} equals the box value; a line whose figure fed no
 * box (zero, unsupported rate, negative volume base, no box in the layout) is not listed.
 *
 * <p><b>Rows.</b> One row per {@code C_INVOICETAX} line (and per distinct signed amount, should a
 * line be counted with different amounts by different parameters, e.g. cash VAT), with the boxes
 * it feeds and, per box, the amount it contributes — the base for a base box, the cuota for a
 * cuota box. The same line usually feeds several boxes (the rate box, the element total, a
 * volume-of-operations box; an intra-EU acquisition both an accrued and a deductible box).
 *
 * <p><b>Submitted declarations.</b> As for Modelo 303, the per-invoice detail is not part of the
 * submission snapshot: once presented, the endpoint answers {@code submitted: true} with no rows
 * instead of a live list that could no longer add up to the frozen boxes.
 */
final class Fiscal390SourcesSupport {

  static final String SOURCES = "sources";
  static final String TYPE_SALES = "sales";
  static final String TYPE_PURCHASE = "purchase";
  static final int DEFAULT_PAGE_SIZE = 50;
  /** Largest page a caller may ask for; {@code pageSize=0} asks for every row (CSV export). */
  static final int MAX_PAGE_SIZE = 500;

  private static final String KEY_BOX = "box";
  private static final String KEY_TOTALS = "totals";

  private final Fiscal390BoxesHandler owner;

  Fiscal390SourcesSupport(Fiscal390BoxesHandler owner) {
    this.owner = owner;
  }

  // ── data model ─────────────────────────────────────────────────────────

  /** One line the classic helper adds into a figure: invoice data plus its signed amounts. */
  record SourceLine(String invoiceTaxId, String invoiceId, String documentNo, Date invoiceDate,
      Date accountingDate, boolean sales, String party, String docType, String taxId,
      String taxName, BigDecimal taxRate, BigDecimal base, BigDecimal cuota) {
  }

  /** The {@link Fiscal390BoxesCalculator.Engine} reads, with lines instead of figures. */
  interface LineEngine {
    List<TaxReportParameter> outputParameters(String groupKey);

    List<TaxRate> taxes(TaxReportParameter parameter);

    /** The lines the figure of {@code parameter} for {@code taxes} is the sum of. */
    List<SourceLine> lines(TaxReportParameter parameter, List<TaxRate> taxes);
  }

  /** A row of the tab: one invoice-tax line and the boxes it feeds. */
  static final class Row {
    final SourceLine line;
    final BigDecimal base;
    final BigDecimal cuota;
    final Map<Integer, BigDecimal> boxAmounts = new TreeMap<>();
    String id;

    Row(SourceLine line, BigDecimal base, BigDecimal cuota) {
      this.line = line;
      this.base = base;
      this.cuota = cuota;
    }
  }

  /** Outcome of {@link #collect}: the rows and the invoice-derived boxes they add up to. */
  static final class Collected {
    final List<Row> rows = new ArrayList<>();
    final Map<Integer, BigDecimal> boxes = new TreeMap<>();
  }

  /** Filters and page of a request. {@code pageSize == 0} means every row. */
  record Query(Integer box, String type, String taxId, String search, int page, int pageSize) {
    static final Query ALL = new Query(null, null, null, null, 1, 0);

    static Query from(HttpServletRequest request) {
      String box = request.getParameter(KEY_BOX);
      String page = request.getParameter("page");
      String size = request.getParameter("pageSize");
      int pageSize = StringUtils.isBlank(size) ? DEFAULT_PAGE_SIZE : Integer.parseInt(size.trim());
      if (pageSize < 0 || pageSize > MAX_PAGE_SIZE) {
        throw new IllegalArgumentException("pageSize must be between 0 and " + MAX_PAGE_SIZE);
      }
      int pageNo = StringUtils.isBlank(page) ? 1 : Integer.parseInt(page.trim());
      return new Query(StringUtils.isBlank(box) ? null : Integer.valueOf(box.trim()),
          StringUtils.trimToNull(request.getParameter("type")),
          StringUtils.trimToNull(request.getParameter("tax")),
          StringUtils.trimToNull(request.getParameter("q")), Math.max(1, pageNo), pageSize);
    }
  }

  // ── endpoint ───────────────────────────────────────────────────────────

  /** {@code GET /fiscal390/sources?year=&period=0A[&box=&type=&tax=&q=&page=&pageSize=]}. */
  void handle(String orgId, int year, String period, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    Query query = Query.from(request);
    JSONObject out;
    if (owner.findSubmittedSnapshot(year, period) != null) {
      out = submittedResponse(query);
    } else {
      out = buildResponse(collect(classicLineEngine(orgId, year)), query);
    }
    response.setContentType(AbstractFiscalHandler.JSON_CT);
    response.getWriter().write(out.toString());
  }

  /** The classic-backed {@link LineEngine} of exercise {@code year}. */
  LineEngine classicLineEngine(String orgId, int year) {
    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    if (org == null) {
      throw new OBException("Organization not found: " + orgId);
    }
    TaxReport report = owner.resolveTaxReport390(orgId, year).report;
    AcctSchema acctSchema = owner.resolveAcctSchema();
    List<Period> periods =
        owner.resolvePeriods(orgId, year, AbstractFiscalHandler.ANNUAL_PERIOD);
    return new ClassicLineEngine(org, report, acctSchema, periods);
  }

  // ── computation ────────────────────────────────────────────────────────

  /**
   * Runs the box walk over {@code engine}'s lines and returns the rows with the boxes they feed,
   * plus the invoice-derived box values of that same walk.
   */
  static Collected collect(LineEngine engine) {
    Collected out = new Collected();
    Map<String, Row> byKey = new LinkedHashMap<>();
    Map<TaxReportParameter, List<SourceLine>> captured = new IdentityHashMap<>();
    Fiscal390BoxesCalculator.Engine tracking = new Fiscal390BoxesCalculator.Engine() {
      @Override
      public List<TaxReportParameter> outputParameters(String groupKey) {
        return engine.outputParameters(groupKey);
      }

      @Override
      public List<TaxRate> taxes(TaxReportParameter parameter) {
        return engine.taxes(parameter);
      }

      @Override
      public BigDecimal[] amounts(TaxReportParameter parameter, List<TaxRate> taxes) {
        List<SourceLine> lines = engine.lines(parameter, taxes);
        captured.computeIfAbsent(parameter, k -> new ArrayList<>()).addAll(lines);
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal cuota = BigDecimal.ZERO;
        for (SourceLine l : lines) {
          base = base.add(l.base());
          cuota = cuota.add(l.cuota());
        }
        return new BigDecimal[] { base, cuota };
      }

      @Override
      public void placed(TaxReportParameter parameter, List<TaxRate> taxes, Integer baseBox,
          Integer cuotaBox) {
        attribute(captured.getOrDefault(parameter, Collections.emptyList()), taxes, baseBox,
            cuotaBox, byKey);
      }
    };
    Fiscal390BoxesCalculator.Result result =
        Fiscal390BoxesCalculator.compute(tracking, Collections.emptyMap());
    for (Integer box : result.invoiceBoxes) {
      out.boxes.put(box, result.boxes.get(box));
    }
    out.rows.addAll(byKey.values());
    out.rows.sort(ROW_ORDER);
    assignIds(out.rows);
    return out;
  }

  /**
   * Attributes the lines of a placed figure: they are summed per invoice-tax line (a cash-VAT
   * line can come once per payment) and each sum is added to its row's {@code boxAmounts}.
   */
  private static void attribute(List<SourceLine> lines, List<TaxRate> taxes, Integer baseBox,
      Integer cuotaBox, Map<String, Row> byKey) {
    Set<String> taxIds = new HashSet<>();
    for (TaxRate t : taxes) {
      taxIds.add(t.getId());
    }
    Map<String, SourceLine> first = new LinkedHashMap<>();
    Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
    for (SourceLine l : lines) {
      if (!taxIds.contains(l.taxId())) {
        continue;
      }
      first.putIfAbsent(l.invoiceTaxId(), l);
      BigDecimal[] s = sums.computeIfAbsent(l.invoiceTaxId(),
          k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
      s[0] = s[0].add(l.base());
      s[1] = s[1].add(l.cuota());
    }
    for (Map.Entry<String, BigDecimal[]> e : sums.entrySet()) {
      BigDecimal base = e.getValue()[0];
      BigDecimal cuota = e.getValue()[1];
      String key = e.getKey() + "|" + base.toPlainString() + "|" + cuota.toPlainString();
      Row row = byKey.computeIfAbsent(key, k -> new Row(first.get(e.getKey()), base, cuota));
      if (baseBox != null) {
        row.boxAmounts.merge(baseBox, base, BigDecimal::add);
      }
      if (cuotaBox != null) {
        row.boxAmounts.merge(cuotaBox, cuota, BigDecimal::add);
      }
    }
  }

  private static final Comparator<Row> ROW_ORDER = Comparator
      .comparing((Row r) -> r.line.invoiceDate(), Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(r -> StringUtils.defaultString(r.line.documentNo()))
      .thenComparing(r -> StringUtils.defaultString(r.line.taxName()))
      .thenComparing(r -> StringUtils.defaultString(r.line.invoiceTaxId()));

  /** Row id = the invoice-tax id, suffixed when the same line produced more than one row. */
  private static void assignIds(List<Row> rows) {
    Map<String, Integer> seen = new LinkedHashMap<>();
    for (Row r : rows) {
      String base = StringUtils.defaultString(r.line.invoiceTaxId());
      int n = seen.merge(base, 1, Integer::sum);
      r.id = n == 1 ? base : base + "#" + n;
    }
  }

  // ── response ───────────────────────────────────────────────────────────

  static boolean matches(Row r, Query q) {
    if (q.box() != null && !r.boxAmounts.containsKey(q.box())) {
      return false;
    }
    if (q.type() != null && !q.type().equals(typeOf(r))) {
      return false;
    }
    if (q.taxId() != null && !q.taxId().equals(r.line.taxId())) {
      return false;
    }
    if (q.search() != null) {
      String needle = q.search().toLowerCase(Locale.ROOT);
      return contains(r.line.documentNo(), needle) || contains(r.line.party(), needle)
          || contains(r.line.taxName(), needle);
    }
    return true;
  }

  private static boolean contains(String haystack, String needle) {
    return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
  }

  private static String typeOf(Row r) {
    return r.line.sales() ? TYPE_SALES : TYPE_PURCHASE;
  }

  /**
   * The JSON of the tab:
   * <pre>
   * { "rows":   [ { "id", "invoiceTaxId", "invoiceId", "ref", "date", "accountingDate",
   *                 "type": "sales"|"purchase", "party", "docType", "taxId", "tax", "rate",
   *                 "base", "vat", "total", "boxes": "1,3,99", "boxAmounts": { "1": "100.00", ... } } ],
   *   "count": &lt;rows matching the filters&gt;, "page": 1, "pageSize": 50,
   *   "totals": { "base", "vat", "total", "box": &lt;sum of boxAmounts[box], only with a box filter&gt; },
   *   "boxes":  { "&lt;invoice box&gt;": "&lt;value&gt;", ... },   // unfiltered: the box chips
   *   "taxes":  [ { "id", "name", "rate" } ],                // unfiltered: the Impuesto filter
   *   "submitted": false }
   * </pre>
   * Amounts are strings (plain, 2 decimals), as in the boxes payload.
   */
  static JSONObject buildResponse(Collected c, Query q) throws JSONException {
    List<Row> filtered = new ArrayList<>();
    BigDecimal base = BigDecimal.ZERO;
    BigDecimal vat = BigDecimal.ZERO;
    BigDecimal boxTotal = BigDecimal.ZERO;
    for (Row r : c.rows) {
      if (matches(r, q)) {
        filtered.add(r);
        base = base.add(r.base);
        vat = vat.add(r.cuota);
        if (q.box() != null) {
          boxTotal = boxTotal.add(r.boxAmounts.get(q.box()));
        }
      }
    }
    int from = q.pageSize() == 0 ? 0 : Math.min(filtered.size(), (q.page() - 1) * q.pageSize());
    int to = q.pageSize() == 0 ? filtered.size() : Math.min(filtered.size(), from + q.pageSize());
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    JSONArray rows = new JSONArray();
    for (Row r : filtered.subList(from, to)) {
      rows.put(rowJson(r, sdf));
    }

    JSONObject totals = new JSONObject();
    totals.put("base", amount(base));
    totals.put("vat", amount(vat));
    totals.put("total", amount(base.add(vat)));
    if (q.box() != null) {
      totals.put(KEY_BOX, amount(boxTotal));
    }

    JSONObject root = new JSONObject();
    root.put("rows", rows);
    root.put("count", filtered.size());
    root.put("page", q.page());
    root.put("pageSize", q.pageSize());
    root.put(KEY_TOTALS, totals);
    root.put(Fiscal390BoxesHandler.BOXES, boxesJson(c.boxes));
    root.put("taxes", taxesJson(c.rows));
    root.put("submitted", false);
    return root;
  }

  /** The answer for a presented declaration: no per-invoice detail (see class javadoc). */
  static JSONObject submittedResponse(Query q) throws JSONException {
    JSONObject totals = new JSONObject();
    totals.put("base", amount(BigDecimal.ZERO));
    totals.put("vat", amount(BigDecimal.ZERO));
    totals.put("total", amount(BigDecimal.ZERO));
    JSONObject root = new JSONObject();
    root.put("rows", new JSONArray());
    root.put("count", 0);
    root.put("page", q.page());
    root.put("pageSize", q.pageSize());
    root.put(KEY_TOTALS, totals);
    root.put(Fiscal390BoxesHandler.BOXES, new JSONObject());
    root.put("taxes", new JSONArray());
    root.put("submitted", true);
    return root;
  }

  private static JSONObject rowJson(Row r, SimpleDateFormat sdf) throws JSONException {
    SourceLine l = r.line;
    JSONObject o = new JSONObject();
    o.put("id", r.id);
    o.put("invoiceTaxId", l.invoiceTaxId());
    o.put("invoiceId", l.invoiceId());
    o.put("ref", l.documentNo());
    o.put("date", l.invoiceDate() != null ? sdf.format(l.invoiceDate()) : JSONObject.NULL);
    o.put("accountingDate",
        l.accountingDate() != null ? sdf.format(l.accountingDate()) : JSONObject.NULL);
    o.put("type", typeOf(r));
    o.put("party", StringUtils.defaultString(l.party()));
    o.put("docType", StringUtils.defaultString(l.docType()));
    o.put("taxId", l.taxId());
    o.put("tax", StringUtils.defaultString(l.taxName()));
    o.put("rate", l.taxRate() != null ? l.taxRate().stripTrailingZeros().toPlainString()
        : JSONObject.NULL);
    o.put("base", amount(r.base));
    o.put("vat", amount(r.cuota));
    o.put("total", amount(r.base.add(r.cuota)));
    StringBuilder boxes = new StringBuilder();
    JSONObject boxAmounts = new JSONObject();
    for (Map.Entry<Integer, BigDecimal> e : r.boxAmounts.entrySet()) {
      if (boxes.length() > 0) {
        boxes.append(',');
      }
      boxes.append(e.getKey());
      boxAmounts.put(String.valueOf(e.getKey()), amount(e.getValue()));
    }
    o.put(Fiscal390BoxesHandler.BOXES, boxes.toString());
    o.put("boxAmounts", boxAmounts);
    return o;
  }

  private static JSONObject boxesJson(Map<Integer, BigDecimal> boxes) throws JSONException {
    JSONObject o = new JSONObject();
    for (Map.Entry<Integer, BigDecimal> e : boxes.entrySet()) {
      o.put(String.valueOf(e.getKey()), amount(e.getValue()));
    }
    return o;
  }

  private static JSONArray taxesJson(List<Row> rows) throws JSONException {
    Map<String, SourceLine> byId = new LinkedHashMap<>();
    for (Row r : rows) {
      byId.putIfAbsent(r.line.taxId(), r.line);
    }
    List<SourceLine> taxes = new ArrayList<>(byId.values());
    taxes.sort(Comparator
        .comparing((SourceLine l) -> l.taxRate() != null ? l.taxRate() : BigDecimal.ZERO)
        .thenComparing(l -> StringUtils.defaultString(l.taxName())));
    JSONArray out = new JSONArray();
    for (SourceLine l : taxes) {
      JSONObject t = new JSONObject();
      t.put("id", l.taxId());
      t.put("name", StringUtils.defaultString(l.taxName()));
      t.put("rate", l.taxRate() != null ? l.taxRate().stripTrailingZeros().toPlainString()
          : JSONObject.NULL);
      out.put(t);
    }
    return out;
  }

  private static String amount(BigDecimal v) {
    return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  // ── classic adapter ────────────────────────────────────────────────────

  /**
   * The classic helper with a CSV object that keeps, instead of writing, the lines it is given.
   * The helper clears the Hibernate session every 100 lines, so each line is copied into a
   * {@link SourceLine} the moment it is added.
   */
  static final class CapturingCsv extends AEAT390CSV {
    final List<SourceLine> lines = new ArrayList<>();

    CapturingCsv() {
      super(System.getProperty("java.io.tmpdir"), "etgo-390-sources.csv");
    }

    @Override
    public void addAEAT390CSVLine(InvoiceTax invoiceTax, BigDecimal taxBaseAmt, BigDecimal taxAmt,
        TaxReportParameter taxReportParameter) {
      lines.add(toLine(invoiceTax, taxBaseAmt, taxAmt));
    }

    static SourceLine toLine(InvoiceTax it, BigDecimal base, BigDecimal cuota) {
      Invoice inv = it.getInvoice();
      TaxRate tax = it.getTax();
      return new SourceLine(it.getId(), inv.getId(), inv.getDocumentNo(), inv.getInvoiceDate(),
          inv.getAccountingDate(), Boolean.TRUE.equals(inv.isSalesTransaction()),
          inv.getBusinessPartner() != null ? inv.getBusinessPartner().getName() : "",
          inv.getDocumentType() != null ? inv.getDocumentType().getIdentifier() : "",
          tax.getId(), tax.getName(), tax.getRate(),
          base != null ? base : BigDecimal.ZERO, cuota != null ? cuota : BigDecimal.ZERO);
    }
  }

  /** {@link LineEngine} over the classic 390 DAO and helper — the boxes endpoint's queries. */
  private static final class ClassicLineEngine implements LineEngine {
    private final Organization org;
    private final TaxReport report;
    private final AcctSchema acctSchema;
    private final List<Period> periods;
    private final AEAT390CashVATReadyDao dao = new AEAT390CashVATReadyDao();
    private final CapturingCsv csv = new CapturingCsv();
    private final AEAT390CashVATReadyCalculationsHelper helper;
    private final AEATIVA_ObjectFactory factory =
        new org.openbravo.module.aeat390.es.v2014.bean.ObjectFactory();

    ClassicLineEngine(Organization org, TaxReport report, AcctSchema acctSchema,
        List<Period> periods) {
      this.org = org;
      this.report = report;
      this.acctSchema = acctSchema;
      this.periods = periods;
      this.helper = new AEAT390CashVATReadyCalculationsHelper(dao, csv);
    }

    @Override
    public List<TaxReportParameter> outputParameters(String groupKey) {
      TaxReportGroup group = dao.getTaxReportGroup(report, groupKey);
      return group == null ? Collections.emptyList()
          : dao.getTaxReportOutputParametersOrderBySeqNO(group);
    }

    @Override
    public List<TaxRate> taxes(TaxReportParameter parameter) {
      return dao.get390TaxesOrderByRate(parameter);
    }

    @Override
    public List<SourceLine> lines(TaxReportParameter parameter, List<TaxRate> taxes) {
      csv.lines.clear();
      TipoBaseImponibleYCuota figure =
          helper.calculateAmounts(org, acctSchema, parameter, taxes, periods, factory);
      List<SourceLine> lines = new ArrayList<>(csv.lines);
      warnOnMismatch(parameter, figure, lines);
      return lines;
    }

    /** The helper sums exactly the lines it adds; a difference would mean classic changed. */
    private static void warnOnMismatch(TaxReportParameter parameter,
        TipoBaseImponibleYCuota figure, List<SourceLine> lines) {
      BigDecimal base = BigDecimal.ZERO;
      BigDecimal cuota = BigDecimal.ZERO;
      for (SourceLine l : lines) {
        base = base.add(l.base());
        cuota = cuota.add(l.cuota());
      }
      BigDecimal fBase = figure.getBI() != null ? figure.getBI() : BigDecimal.ZERO;
      BigDecimal fCuota = figure.getCuota() != null ? figure.getCuota() : BigDecimal.ZERO;
      if (fBase.compareTo(base) != 0 || fCuota.compareTo(cuota) != 0) {
        AbstractFiscalHandler.log.warn("Modelo 390 sources: lines of parameter "
            + parameter.getSearchKey() + " add up to " + base + "/" + cuota
            + " but classic computed " + fBase + "/" + fCuota);
      }
    }
  }
}
