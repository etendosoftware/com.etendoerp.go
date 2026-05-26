/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat303.es.api.InvoiceType;
import org.openbravo.module.aeat303.es.report.v2014.AEAT303Report2014Dao;
import org.openbravo.module.aeat303.es.util.AEAT303CalculationsHelper;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.TaxReportParameter;

import com.etendoerp.go.schemaforge.Fiscal303BoxesHandler.BoxGroupConfig;
import com.etendoerp.go.schemaforge.Fiscal303BoxesHandler.ComputeResult;

/**
 * Unit tests for {@link Fiscal303BoxesHandler}.
 *
 * <p>Covers the three pure-logic method groups that were introduced during the
 * Sonar refactor:
 * <ul>
 *   <li>{@link BoxGroupConfig} and {@link ComputeResult} value objects.</li>
 *   <li>{@link Fiscal303BoxesHandler#vatGeneralBoxes} — all Spanish VAT percentages
 *       for the general regime map to the correct box pairs.</li>
 *   <li>{@link Fiscal303BoxesHandler#vatEcBoxes} — all recargo-de-equivalencia
 *       percentages map to the correct box pairs.</li>
 *   <li>{@link Fiscal303BoxesHandler#finalizeInvoiceRow} — boxes set is sorted and
 *       serialised as a comma-separated string, and the total is base+vat.</li>
 *   <li>{@code handle()} routing — wrong HTTP method and wrong entity name both
 *       respond 405; missing query parameters respond 400.</li>
 * </ul>
 */
public class Fiscal303BoxesHandlerTest {

  private Fiscal303BoxesHandler handler;

  @org.junit.Before
  public void setUp() {
    handler = new Fiscal303BoxesHandler(null);
  }

  // ── BoxGroupConfig ────────────────────────────────────────────────────────

  /**
   * All seven fields passed to the constructor must be retrievable via the
   * corresponding public final fields. There are no setters, so construction
   * is the only mutation path.
   */
  @Test
  public void testBoxGroupConfigStoresAllFields() {
    BoxGroupConfig cfg = new BoxGroupConfig(
        "VAT_SALES", "VAT_SALES_EU", "Purchase", "No", "Yes", 10, 11);
    assertEquals("VAT_SALES",    cfg.groupKey);
    assertEquals("VAT_SALES_EU", cfg.paramKey);
    assertEquals("Purchase",     cfg.taxType);
    assertEquals("No",           cfg.equivCharge);
    assertEquals("Yes",          cfg.intracom);
    assertEquals(10,             cfg.baseBox);
    assertEquals(11,             cfg.taxBox);
  }

  // ── ComputeResult ─────────────────────────────────────────────────────────

  /**
   * {@link ComputeResult} is a transparent data holder — both fields must
   * survive the constructor unchanged (no defensive copies).
   */
  @Test
  public void testComputeResultStoresBoxesAndSources() {
    Map<Integer, BigDecimal> boxes = Collections.singletonMap(46, new BigDecimal("1234.56"));
    List<Map<String, Object>> sources = Collections.emptyList();
    ComputeResult result = new ComputeResult(boxes, sources);
    assertEquals(boxes,   result.boxes);
    assertEquals(sources, result.sources);
  }

  // ── vatGeneralBoxes ───────────────────────────────────────────────────────

  /**
   * 21 % is the standard Spanish VAT rate — the largest bucket and the most
   * common in practice. Must map to boxes 7 (base) and 9 (tax).
   */
  @Test
  public void testVatGeneral21MapsToBoxes7And9() {
    assertEquals(Arrays.asList(7, 9), handler.vatGeneralBoxes(pct("21.00")));
  }

  /** 10 % reduced rate → boxes 4/6. */
  @Test
  public void testVatGeneral10MapsToBoxes4And6() {
    assertEquals(Arrays.asList(4, 6), handler.vatGeneralBoxes(pct("10.00")));
  }

  /** 7 % transitional rate (pre-2012 reduced) → boxes 4/6. */
  @Test
  public void testVatGeneral7MapsToBoxes4And6() {
    assertEquals(Arrays.asList(4, 6), handler.vatGeneralBoxes(pct("7.00")));
  }

  /** 8 % transitional rate (pre-2012 reduced) → boxes 4/6. */
  @Test
  public void testVatGeneral8MapsToBoxes4And6() {
    assertEquals(Arrays.asList(4, 6), handler.vatGeneralBoxes(pct("8.00")));
  }

  /** 4 % super-reduced rate → boxes 1/3. */
  @Test
  public void testVatGeneral4MapsToBoxes1And3() {
    assertEquals(Arrays.asList(1, 3), handler.vatGeneralBoxes(pct("4.00")));
  }

  /** 5 % (transitional super-reduced) → boxes 1/3. */
  @Test
  public void testVatGeneral5MapsToBoxes1And3() {
    assertEquals(Arrays.asList(1, 3), handler.vatGeneralBoxes(pct("5.00")));
  }

  /** 0 % exempt-but-traceable operations → boxes 150/152. */
  @Test
  public void testVatGeneral0MapsToBoxes150And152() {
    assertEquals(Arrays.asList(150, 152), handler.vatGeneralBoxes(pct("0.00")));
  }

  /** 2 % (new reduced rate introduced 2023) → boxes 165/167. */
  @Test
  public void testVatGeneral2MapsToBoxes165And167() {
    assertEquals(Arrays.asList(165, 167), handler.vatGeneralBoxes(pct("2.00")));
  }

  /**
   * An unknown percentage must return an empty list so the caller can skip the
   * entry rather than crash or silently assign it to a wrong box.
   */
  @Test
  public void testVatGeneralUnknownPercentReturnsEmpty() {
    assertTrue(handler.vatGeneralBoxes(pct("99.00")).isEmpty());
  }

  /** Null input must return empty, not throw NPE. */
  @Test
  public void testVatGeneralNullReturnsEmpty() {
    assertTrue(handler.vatGeneralBoxes(null).isEmpty());
  }

  // ── vatEcBoxes ────────────────────────────────────────────────────────────

  /** 1.40 % EC surcharge (standard rate) → boxes 19/21. */
  @Test
  public void testVatEc140MapsToBoxes19And21() {
    assertEquals(Arrays.asList(19, 21), handler.vatEcBoxes(pct("1.40")));
  }

  /** 5.20 % EC surcharge (standard rate) → boxes 22/24. */
  @Test
  public void testVatEc520MapsToBoxes22And24() {
    assertEquals(Arrays.asList(22, 24), handler.vatEcBoxes(pct("5.20")));
  }

  /** 0.50 % EC surcharge (reduced rate) → boxes 16/18. */
  @Test
  public void testVatEc050MapsToBoxes16And18() {
    assertEquals(Arrays.asList(16, 18), handler.vatEcBoxes(pct("0.50")));
  }

  /** 1.75 % EC surcharge (new reduced rate 2023) → boxes 156/158. */
  @Test
  public void testVatEc175MapsToBoxes156And158() {
    assertEquals(Arrays.asList(156, 158), handler.vatEcBoxes(pct("1.75")));
  }

  /** Unrecognised EC percentage must return empty, not throw. */
  @Test
  public void testVatEcUnknownPercentReturnsEmpty() {
    assertTrue(handler.vatEcBoxes(pct("3.00")).isEmpty());
  }

  /** Null input must return empty, not throw NPE. */
  @Test
  public void testVatEcNullReturnsEmpty() {
    assertTrue(handler.vatEcBoxes(null).isEmpty());
  }

  // ── finalizeInvoiceRow ────────────────────────────────────────────────────

  /**
   * Box numbers must be sorted in ascending order in the output string so the
   * frontend can display them predictably regardless of insertion order.
   */
  @Test
  public void testFinalizeRowSortsBoxesAscending() {
    Map<String, Object> row = buildRow(pct("100.00"), pct("21.00"), 9, 7);
    handler.finalizeInvoiceRow(row);
    assertEquals("7,9", row.get("boxes"));
  }

  /**
   * Total = base + vat, rounded to 2 decimal places.
   */
  @Test
  public void testFinalizeRowComputesTotal() {
    Map<String, Object> row = buildRow(pct("100.00"), pct("21.00"), 7);
    handler.finalizeInvoiceRow(row);
    assertTrue("total should be 121.00",
        new BigDecimal("121.00").compareTo((BigDecimal) row.get("total")) == 0);
  }

  /** A single box number must be serialised without any comma. */
  @Test
  public void testFinalizeRowSingleBox() {
    Map<String, Object> row = buildRow(pct("500.00"), pct("50.00"), 29);
    handler.finalizeInvoiceRow(row);
    assertEquals("29", row.get("boxes"));
  }

  /**
   * A row with no contributing boxes (e.g. an exempt invoice not tracked in
   * any box) must produce an empty string, not null or a trailing comma.
   */
  @Test
  public void testFinalizeRowNoBoxesProducesEmptyString() {
    Map<String, Object> row = buildRow(BigDecimal.ZERO, BigDecimal.ZERO);
    handler.finalizeInvoiceRow(row);
    assertEquals("", row.get("boxes"));
  }

  // ── handle() routing ─────────────────────────────────────────────────────

  /**
   * Any HTTP method other than GET must be rejected with 405.
   * The entity name check is secondary — reject on method first.
   */
  @Test
  public void testHandleRejectsNonGetMethod() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    h.handle("boxes", "POST", mock(HttpServletRequest.class), res);
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
  }

  /**
   * A GET request to an entity name other than {@code "boxes"} must be rejected
   * with 405. The Fiscal303 handler owns only the {@code /fiscal303/boxes}
   * sub-path; anything else falls outside its contract.
   */
  @Test
  public void testHandleRejectsUnknownEntityName() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    h.handle("invoices", "GET", mock(HttpServletRequest.class), res);
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
  }

  /**
   * A valid GET to {@code "boxes"} with no {@code year} or {@code period}
   * parameters must respond 400 before reaching the DB layer.
   */
  @Test
  public void testHandleRejectsMissingQueryParams() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    // Mockito default: getParameter returns null — triggers the missing-param guard
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    h.handle("boxes", "GET", req, res);
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  /**
   * POST to /fiscal303/generate must be rejected with 405 — only GET is supported.
   */
  @Test
  public void testHandleGenerateRejectsPostMethod() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    h.handle("generate", "POST", mock(HttpServletRequest.class), res);
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
  }

  /**
   * GET /fiscal303/generate with no year param must be rejected with 400
   * before reaching the DB layer.
   */
  @Test
  public void testHandleGenerateMissingYearReturns400() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    // getParameter returns null by default — year is missing
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    h.handle("generate", "GET", req, res);
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  /**
   * GET /fiscal303/generate with year but no period must be rejected with 400.
   */
  @Test
  public void testHandleGenerateMissingPeriodReturns400() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    // period is null (default)
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    h.handle("generate", "GET", req, res);
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  // ── no-gaps coverage ─────────────────────────────────────────────────────

  /**
   * Iterates over all 8 known VAT general rates and verifies that none returns
   * an empty box list. This acts as a deletion guard — if any case is accidentally
   * removed from the switch/map inside {@code vatGeneralBoxes}, this test fails.
   */
  @Test
  public void testVatGeneralBoxesAllRates_NoGaps() {
    String[] knownRates = { "21.00", "10.00", "7.00", "8.00", "4.00", "5.00", "0.00", "2.00" };
    for (String rate : knownRates) {
      assertFalse(
          "vatGeneralBoxes returned empty for rate " + rate,
          handler.vatGeneralBoxes(pct(rate)).isEmpty());
    }
  }

  /**
   * Iterates over all 4 known recargo-de-equivalencia rates and verifies that
   * none returns an empty box list. Guards against accidental deletion of an EC
   * mapping entry.
   */
  @Test
  public void testVatEcBoxesAllRates_NoGaps() {
    String[] ecRates = { "1.40", "5.20", "0.50", "1.75" };
    for (String rate : ecRates) {
      assertFalse(
          "vatEcBoxes returned empty for rate " + rate,
          handler.vatEcBoxes(pct(rate)).isEmpty());
    }
  }

  // ── computeBoxes (mock-based integration) ─────────────────────────────────

  /**
   * VAT_SALES_GENERAL at 21% must map base to box 7, tax to box 9.
   * box[27] (accrued) must equal the tax amount; box[45] must be 0; box[46] = box[27].
   */
  @Test
  public void testComputeBoxes_sale21pct_mapsToBoxes7and9() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_GENERAL")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "210.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(7));
    assertBd("210.00",  result.boxes.get(9));
    assertBd("210.00",  result.boxes.get(27));
    assertBd("0.00",    result.boxes.get(45));
    assertBd("210.00",  result.boxes.get(46));
  }

  /**
   * VAT_SALES_GENERAL at 10% must map base to box 4, tax to box 6.
   */
  @Test
  public void testComputeBoxes_sale10pct_mapsToBoxes4and6() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("10"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_GENERAL")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "100.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(4));
    assertBd("100.00",  result.boxes.get(6));
    assertBd("100.00",  result.boxes.get(27));
    assertBd("0.00",    result.boxes.get(45));
    assertBd("100.00",  result.boxes.get(46));
  }

  /**
   * VAT_SALES_GENERAL at 4% must map base to box 1, tax to box 3.
   */
  @Test
  public void testComputeBoxes_sale4pct_mapsToBoxes1and3() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("4"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_GENERAL")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "40.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(1));
    assertBd("40.00",   result.boxes.get(3));
    assertBd("40.00",   result.boxes.get(27));
    assertBd("0.00",    result.boxes.get(45));
    assertBd("40.00",   result.boxes.get(46));
  }

  /**
   * VAT_SALES_GENERAL at 0% must map base to box 150.
   * addToBox skips zero values, so box 152 must be absent.
   */
  @Test
  public void testComputeBoxes_sale0pct_mapsToBox150_box152Absent() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("0"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_GENERAL")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "0.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(150));
    assertNull(result.boxes.get(152));
  }

  /**
   * VAT_SALES_GENERAL at 2% must map base to box 165, tax to box 167.
   */
  @Test
  public void testComputeBoxes_sale2pct_mapsToBoxes165and167() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("2"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_GENERAL")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "20.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(165));
    assertBd("20.00",   result.boxes.get(167));
    assertBd("20.00",   result.boxes.get(27));
    assertBd("0.00",    result.boxes.get(45));
    assertBd("20.00",   result.boxes.get(46));
  }

  /**
   * VAT_SALES_EC at 5.20% must map base to box 22, tax to box 24.
   */
  @Test
  public void testComputeBoxes_saleEc520pct_mapsToBoxes22and24() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("5.20"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_EC")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "52.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(22));
    assertBd("52.00",   result.boxes.get(24));
  }

  /**
   * VAT_SALES_EC at 1.40% must map base to box 19, tax to box 21.
   */
  @Test
  public void testComputeBoxes_saleEc140pct_mapsToBoxes19and21() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("1.40"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_EC")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "14.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(19));
    assertBd("14.00",   result.boxes.get(21));
  }

  /**
   * VAT_SALES_EC at 0.50% must map base to box 16, tax to box 18.
   */
  @Test
  public void testComputeBoxes_saleEc050pct_mapsToBoxes16and18() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("0.50"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_EC")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "5.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(16));
    assertBd("5.00",    result.boxes.get(18));
  }

  /**
   * VAT_SALES_EC at 1.75% must map base to box 156, tax to box 158.
   */
  @Test
  public void testComputeBoxes_saleEc175pct_mapsToBoxes156and158() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("1.75"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_EC")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "17.50"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(156));
    assertBd("17.50",   result.boxes.get(158));
  }

  /**
   * VAT_SALES_EU (intracom acquisition, buyer self-assesses) must map to boxes 10/11.
   * box[27] must equal the tax amount.
   */
  @Test
  public void testComputeBoxes_saleEu_mapsToBoxes10and11() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_EU")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("800.00", "168.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("800.00",  result.boxes.get(10));
    assertBd("168.00",  result.boxes.get(11));
    assertBd("168.00",  result.boxes.get(27));
  }

  /**
   * VAT_SALES_ISP (inversion sujeto pasivo) must map to boxes 12/13.
   * box[27] must equal the tax amount.
   */
  @Test
  public void testComputeBoxes_saleIsp_mapsToBoxes12and13() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_ISP")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("600.00", "126.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("600.00",  result.boxes.get(12));
    assertBd("126.00",  result.boxes.get(13));
    assertBd("126.00",  result.boxes.get(27));
  }

  /**
   * VAT_PURCHASE Normal_Operations must map to boxes 28/29.
   * box[45] (deductible) must equal the tax; box[27] must be 0; box[46] must be negative.
   */
  @Test
  public void testComputeBoxes_purchaseNormalOps_mapsToBoxes28and29() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_PURCHASE"), eq("Normal_Operations")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("5000.00", "1050.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("5000.00",  result.boxes.get(28));
    assertBd("1050.00",  result.boxes.get(29));
    assertBd("1050.00",  result.boxes.get(45));
    assertBd("0.00",     result.boxes.get(27));
    assertBd("-1050.00", result.boxes.get(46));
  }

  /**
   * VAT_PURCHASE Investment_Goods must map to boxes 30/31.
   */
  @Test
  public void testComputeBoxes_purchaseInvestmentGoods_mapsToBoxes30and31() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_PURCHASE"), eq("Investment_Goods")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("2000.00", "420.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("2000.00", result.boxes.get(30));
    assertBd("420.00",  result.boxes.get(31));
  }

  /**
   * VAT_PURCHASE Import_Goods must map to boxes 32/33.
   */
  @Test
  public void testComputeBoxes_purchaseImportGoods_mapsToBoxes32and33() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_PURCHASE"), eq("Import_Goods")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("500.00", "105.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("500.00", result.boxes.get(32));
    assertBd("105.00", result.boxes.get(33));
  }

  /**
   * VAT_PURCHASE Intracommunity_Goods must map to boxes 36/37.
   */
  @Test
  public void testComputeBoxes_purchaseIntracomGoods_mapsToBoxes36and37() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_PURCHASE"), eq("Intracommunity_Goods")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "210.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(36));
    assertBd("210.00",  result.boxes.get(37));
  }

  /**
   * Difference/IntracommunitySales must populate box 59 (base only, taxBox=0).
   * box[93] must mirror box[59] when box[59] > 0.
   */
  @Test
  public void testComputeBoxes_intracommunitySales_mapsToBox59andMirror93() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("0"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("Difference"), eq("IntracommunitySales")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("2300.00", "0.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("2300.00", result.boxes.get(59));
    assertBd("2300.00", result.boxes.get(93));
  }

  /**
   * Difference/ExportsAndOperations must populate box 60 (base only, taxBox=0).
   * box[94] must mirror box[60] when box[60] > 0.
   */
  @Test
  public void testComputeBoxes_exportsAndOps_mapsToBox60andMirror94() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter param = mock(TaxReportParameter.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("0"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("Difference"), eq("ExportsAndOperations")))
        .thenReturn(param);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(param)))
        .thenReturn(Collections.singletonList(rate));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("3600.00", "0.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("3600.00", result.boxes.get(60));
    assertBd("3600.00", result.boxes.get(94));
  }

  /**
   * With a 21% sale and a Normal_Operations purchase active simultaneously,
   * box[27] = 210, box[45] = 105, box[46] = 105.
   * Mockito sequential thenReturn handles the two calculateAmountsMap call order:
   * (1) split-loop for VAT_SALES_GENERAL, (2) fillGroupBoxes for VAT_PURCHASE.
   */
  @Test
  public void testComputeBoxes_salesAndPurchase_correctTotals() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter salesParam = mock(TaxReportParameter.class);
    TaxRate salesRate = mock(TaxRate.class);
    when(salesRate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_GENERAL")))
        .thenReturn(salesParam);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(salesParam)))
        .thenReturn(Collections.singletonList(salesRate));

    TaxReportParameter purchParam = mock(TaxReportParameter.class);
    TaxRate purchRate = mock(TaxRate.class);
    when(purchRate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_PURCHASE"), eq("Normal_Operations")))
        .thenReturn(purchParam);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(purchParam)))
        .thenReturn(Collections.singletonList(purchRate));

    // Call order: (1) applyPercentageSplit for VAT_SALES_GENERAL, (2) fillGroupBoxes for Normal_Operations
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "210.00"))
        .thenReturn(amounts("500.00", "105.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("210.00", result.boxes.get(27));
    assertBd("105.00", result.boxes.get(45));
    assertBd("105.00", result.boxes.get(46));
  }

  /**
   * Result boxes 66, 69, and 71 must all mirror box 46 exactly.
   */
  @Test
  public void testComputeBoxes_resultBoxes66_69_71_mirrorBox46() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    TaxReportParameter salesParam = mock(TaxReportParameter.class);
    TaxRate salesRate = mock(TaxRate.class);
    when(salesRate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_SALES"), eq("VAT_SALES_GENERAL")))
        .thenReturn(salesParam);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(salesParam)))
        .thenReturn(Collections.singletonList(salesRate));

    TaxReportParameter purchParam = mock(TaxReportParameter.class);
    TaxRate purchRate = mock(TaxRate.class);
    when(purchRate.getRate()).thenReturn(new BigDecimal("21"));
    when(dao303.getTaxReportParameter(eq(taxReport), eq("VAT_PURCHASE"), eq("Normal_Operations")))
        .thenReturn(purchParam);
    when(dao303.get303Taxes(eq("test-report-id"), anyString(), anyString(), anyString(), eq(purchParam)))
        .thenReturn(Collections.singletonList(purchRate));

    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ALL)))
        .thenReturn(amounts("1000.00", "210.00"))
        .thenReturn(amounts("500.00", "105.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    BigDecimal box46 = result.boxes.get(46);
    assertTrue("box46 must be non-null", box46 != null);
    assertTrue("box[66] must equal box[46]", box46.compareTo(result.boxes.get(66)) == 0);
    assertTrue("box[69] must equal box[46]", box46.compareTo(result.boxes.get(69)) == 0);
    assertTrue("box[71] must equal box[46]", box46.compareTo(result.boxes.get(71)) == 0);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static Map<String, BigDecimal> amounts(String base, String tax) {
    Map<String, BigDecimal> m = new HashMap<>();
    m.put("TaxBaseAmount", new BigDecimal(base));
    m.put("TaxAmount",     new BigDecimal(tax));
    return m;
  }

  // ── /fiscal303/modified routing ──────────────────────────────────────────

  /**
   * POST to /fiscal303/modified must be rejected with 405 — only GET is supported.
   */
  @Test
  public void testHandleModifiedRejectsPostMethod() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    h.handle("modified", "POST", mock(HttpServletRequest.class), res);
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
  }

  /**
   * GET /fiscal303/modified with no year param must return 400.
   */
  @Test
  public void testHandleModifiedMissingYearReturns400() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    // year is null (default), period is null (default)
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    h.handle("modified", "GET", req, res);
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  /**
   * GET /fiscal303/modified with year+period but no since must return 400
   * before reaching the DB layer (OBContext is never touched).
   */
  @Test
  public void testHandleModifiedMissingSinceReturns400() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter("period")).thenReturn("T2");
    // since is null (default)
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    h.handle("modified", "GET", req, res);
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  private static void assertBd(String expected, BigDecimal actual) {
    assertTrue("Expected " + expected + " but got " + actual,
        new BigDecimal(expected).compareTo(actual) == 0);
  }

  private static BigDecimal pct(String value) {
    return new BigDecimal(value);
  }

  private static Map<String, Object> buildRow(BigDecimal base, BigDecimal vat,
      Integer... boxNums) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("base", base);
    row.put("vat",  vat);
    row.put("boxes", new LinkedHashSet<>(Arrays.asList(boxNums)));
    return row;
  }
}
