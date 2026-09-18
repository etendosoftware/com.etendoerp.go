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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat303.es.api.InvoiceType;
import org.openbravo.module.aeat303.es.report.v2014.AEAT303Report2014Dao;
import org.openbravo.module.aeat303.es.util.AEAT303CalculationsHelper;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.TaxReportParameter;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;

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
 *   <li>{@link Fiscal303SourcesSupport#finalizeInvoiceRow} — boxes set is sorted and
 *       serialised as a comma-separated string, and the total is base+vat.</li>
 *   <li>{@code handle()} routing — wrong HTTP method and wrong entity name both
 *       respond 405; missing query parameters respond 400.</li>
 * </ul>
 */
public class Fiscal303BoxesHandlerTest {

  private Fiscal303BoxesHandler handler;
  private FiscalDeclCrudHandler declHandler;

  @org.junit.Before
  public void setUp() {
    handler = new Fiscal303BoxesHandler(null);
    declHandler = new FiscalDeclCrudHandler(null);
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

  /** 1.40 % EC surcharge → boxes 19/21 regardless of form version. */
  @Test
  public void testVatEc140MapsToBoxes19And21() {
    assertEquals(Arrays.asList(19, 21), handler.vatEcBoxes(pct("1.40"), false));
    assertEquals(Arrays.asList(19, 21), handler.vatEcBoxes(pct("1.40"), true));
  }

  /** 5.20 % EC surcharge → boxes 22/24 regardless of form version. */
  @Test
  public void testVatEc520MapsToBoxes22And24() {
    assertEquals(Arrays.asList(22, 24), handler.vatEcBoxes(pct("5.20"), false));
    assertEquals(Arrays.asList(22, 24), handler.vatEcBoxes(pct("5.20"), true));
  }

  /** 0.50 % EC: pre-Oct 2024 → boxes 16/18; Oct 2024+ → boxes 168/170. */
  @Test
  public void testVatEc050OldFormMapsToBoxes16And18() {
    assertEquals(Arrays.asList(16, 18), handler.vatEcBoxes(pct("0.50"), false));
  }

  @Test
  public void testVatEc050NewFormMapsToBoxes168And170() {
    assertEquals(Arrays.asList(168, 170), handler.vatEcBoxes(pct("0.50"), true));
  }

  /** 0.26 % EC (new Oct 2024): only valid in new form → boxes 168/170. */
  @Test
  public void testVatEc026NewFormMapsToBoxes168And170() {
    assertEquals(Arrays.asList(168, 170), handler.vatEcBoxes(pct("0.26"), true));
  }

  @Test
  public void testVatEc026OldFormReturnsEmpty() {
    assertTrue(handler.vatEcBoxes(pct("0.26"), false).isEmpty());
  }

  /** 1.00 % EC (new Oct 2024): only valid in new form → boxes 16/18. */
  @Test
  public void testVatEc100NewFormMapsToBoxes16And18() {
    assertEquals(Arrays.asList(16, 18), handler.vatEcBoxes(pct("1.00"), true));
  }

  @Test
  public void testVatEc100OldFormReturnsEmpty() {
    assertTrue(handler.vatEcBoxes(pct("1.00"), false).isEmpty());
  }

  /** 1.75 % EC surcharge → boxes 156/158 regardless of form version. */
  @Test
  public void testVatEc175MapsToBoxes156And158() {
    assertEquals(Arrays.asList(156, 158), handler.vatEcBoxes(pct("1.75"), false));
    assertEquals(Arrays.asList(156, 158), handler.vatEcBoxes(pct("1.75"), true));
  }

  /** Unrecognised EC percentage must return empty, not throw. */
  @Test
  public void testVatEcUnknownPercentReturnsEmpty() {
    assertTrue(handler.vatEcBoxes(pct("3.00"), false).isEmpty());
    assertTrue(handler.vatEcBoxes(pct("3.00"), true).isEmpty());
  }

  /** Null input must return empty, not throw NPE. */
  @Test
  public void testVatEcNullReturnsEmpty() {
    assertTrue(handler.vatEcBoxes(null, false).isEmpty());
    assertTrue(handler.vatEcBoxes(null, true).isEmpty());
  }

  // ── isOct2024OrLater ─────────────────────────────────────────────────────

  @Test
  public void testIsOct2024OrLater_quarterly() {
    assertFalse(Fiscal303BoxesHandler.isOct2024OrLater(2024, "T1"));
    assertFalse(Fiscal303BoxesHandler.isOct2024OrLater(2024, "T2"));
    assertFalse(Fiscal303BoxesHandler.isOct2024OrLater(2024, "T3"));
    assertTrue(Fiscal303BoxesHandler.isOct2024OrLater(2024, "T4"));
  }

  @Test
  public void testIsOct2024OrLater_monthly() {
    assertFalse(Fiscal303BoxesHandler.isOct2024OrLater(2024, "9"));
    assertTrue(Fiscal303BoxesHandler.isOct2024OrLater(2024, "10"));
    assertTrue(Fiscal303BoxesHandler.isOct2024OrLater(2024, "11"));
    assertTrue(Fiscal303BoxesHandler.isOct2024OrLater(2024, "12"));
  }

  @Test
  public void testIsOct2024OrLater_years() {
    assertFalse(Fiscal303BoxesHandler.isOct2024OrLater(2023, "T4"));
    assertFalse(Fiscal303BoxesHandler.isOct2024OrLater(2022, "T4"));
    assertTrue(Fiscal303BoxesHandler.isOct2024OrLater(2025, "T1"));
    assertTrue(Fiscal303BoxesHandler.isOct2024OrLater(2026, "T1"));
  }

  // ── finalizeInvoiceRow ────────────────────────────────────────────────────

  /**
   * Box numbers must be sorted in ascending order in the output string so the
   * frontend can display them predictably regardless of insertion order.
   */
  @Test
  public void testFinalizeRowSortsBoxesAscending() {
    Map<String, Object> row = buildRow(pct("100.00"), pct("21.00"), 9, 7);
    handler.sourcesSupport.finalizeInvoiceRow(row);
    assertEquals("7,9", row.get("boxes"));
  }

  /**
   * Total = base + vat, rounded to 2 decimal places.
   */
  @Test
  public void testFinalizeRowComputesTotal() {
    Map<String, Object> row = buildRow(pct("100.00"), pct("21.00"), 7);
    handler.sourcesSupport.finalizeInvoiceRow(row);
    assertTrue("total should be 121.00",
        new BigDecimal("121.00").compareTo((BigDecimal) row.get("total")) == 0);
  }

  /** A single box number must be serialised without any comma. */
  @Test
  public void testFinalizeRowSingleBox() {
    Map<String, Object> row = buildRow(pct("500.00"), pct("50.00"), 29);
    handler.sourcesSupport.finalizeInvoiceRow(row);
    assertEquals("29", row.get("boxes"));
  }

  /**
   * A row with no contributing boxes (e.g. an exempt invoice not tracked in
   * any box) must produce an empty string, not null or a trailing comma.
   */
  @Test
  public void testFinalizeRowNoBoxesProducesEmptyString() {
    Map<String, Object> row = buildRow(BigDecimal.ZERO, BigDecimal.ZERO);
    handler.sourcesSupport.finalizeInvoiceRow(row);
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
   * A GET request to an unknown entity name must be rejected with 404.
   */
  @Test
  public void testHandleRejectsUnknownEntityName() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    h.handle("invoices", "GET", mock(HttpServletRequest.class), res);
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
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
          handler.vatEcBoxes(pct(rate), false).isEmpty());
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
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

    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
        .thenReturn(amounts("1000.00", "210.00"))
        .thenReturn(amounts("500.00", "105.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    BigDecimal box46 = result.boxes.get(46);
    assertTrue("box46 must be non-null", box46 != null);
    assertTrue("box[66] must equal box[46]", box46.compareTo(result.boxes.get(66)) == 0);
    assertTrue("box[69] must equal box[46]", box46.compareTo(result.boxes.get(69)) == 0);
    assertTrue("box[71] must equal box[46]", box46.compareTo(result.boxes.get(71)) == 0);
  }

  // ── computeBoxes — ETP-5393 Bug F (Modificación/Rectificación box pairs) ──────────────────
  //
  // Boxes 14/15 ("Modificación bases y cuotas"), 25/26 ("Modificaciones bases y cuotas del
  // recargo de equivalencia") and 40/41 ("Rectificación de deducciones") always rendered blank
  // in the Go preview because this handler never computed them at all. The classic engine
  // (org.openbravo.module.aeat303.es) has always derived them from corrective/credit-memo
  // invoices only (InvoiceType.ONLY_MEMO_AND_CORRECTIVE) over the union of the TaxRates already
  // resolved for the "normal" boxes:
  //   - 14/15: VAT_SALES_GENERAL ∪ VAT_SALES_EU ∪ VAT_SALES_ISP taxRates
  //     (AEAT303Report2014#generateSalesLines, ~lines 424-513)
  //   - 25/26: VAT_SALES_EC taxRates
  //     (AEAT303Report2014#generateSalesLines, ~lines 556-568)
  //   - 40/41: union of every VAT_PURCHASE group's taxRates (Normal_Operations,
  //     Investment_Goods, Import_Goods, Import_Investment_Goods, Intracommunity_Goods,
  //     Intracommunity_Investments) (AEAT303Report2014#generatePurchaseLines, ~lines 618-689)

  /**
   * Box 14/15 must be populated from the SAME TaxRates already resolved for VAT_SALES_GENERAL
   * (box 1/3, 4% here), computed with InvoiceType.ONLY_MEMO_AND_CORRECTIVE rather than
   * ONLY_NORMAL (the InvoiceType used for the base box 1/3 itself).
   */
  @Test
  public void testComputeBoxes_modificacionBasesYCuotas_mapsToBoxes14and15() {
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
        .thenReturn(amounts("1000.00", "40.00"));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_MEMO_AND_CORRECTIVE)))
        .thenReturn(amounts("-200.00", "-8.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("-200.00", result.boxes.get(14));
    assertBd("-8.00",   result.boxes.get(15));
  }

  /**
   * Box 25/26 must be populated from the EC (recargo equivalencia) TaxRates, computed with
   * InvoiceType.ONLY_MEMO_AND_CORRECTIVE, and box 26 (cuota) must roll into the box[27] total
   * exactly like its sibling accrued-cuota boxes (15, 24, ...) already do.
   */
  @Test
  public void testComputeBoxes_modificacionRecargo_mapsToBoxes25and26_andRollsIntoBox27() {
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
        .thenReturn(amounts("100.00", "5.20"));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_MEMO_AND_CORRECTIVE)))
        .thenReturn(amounts("50.00", "2.60"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("50.00", result.boxes.get(25));
    assertBd("2.60",  result.boxes.get(26));
    // box[24] (5.20% cuota, ONLY_NORMAL) = 5.20; box[26] (mod. recargo cuota, MEMO_AND_CORRECTIVE) = 2.60
    assertBd("7.80",  result.boxes.get(27));
  }

  /**
   * Box 40/41 must be populated from the union of every VAT_PURCHASE group's TaxRates
   * (here: Normal_Operations only, to keep the mock setup focused), computed with
   * InvoiceType.ONLY_MEMO_AND_CORRECTIVE, and box 41 (cuota) must roll into box[45].
   */
  @Test
  public void testComputeBoxes_rectificacionDeducciones_mapsToBoxes40and41_andRollsIntoBox45() {
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
        .thenReturn(amounts("1000.00", "210.00"));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_MEMO_AND_CORRECTIVE)))
        .thenReturn(amounts("-100.00", "-21.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("-100.00", result.boxes.get(40));
    assertBd("-21.00",  result.boxes.get(41));
    // box[29] (normal operations cuota, ONLY_NORMAL) = 210.00; box[41] (rectificación, MEMO_AND_CORRECTIVE) = -21.00
    assertBd("189.00",  result.boxes.get(45));
  }

  /**
   * When no sales tax rates exist at all (VAT_SALES_GENERAL/EU/ISP params all null), boxes
   * 14/15 must simply stay absent — no NPE from calling calculateAmountsMap with an empty
   * TaxRate union.
   */
  @Test
  public void testComputeBoxes_noSalesTaxRates_box14and15StayAbsent_noNpe() {
    Organization org = mock(Organization.class);
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("test-report-id");
    List<Period> periods = Collections.emptyList();
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);
    when(dao303.getTaxReportParameter(any(TaxReport.class), anyString(), anyString()))
        .thenReturn(null);

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertNull("box[14] must stay absent when there is no sales activity at all",
        result.boxes.get(14));
    assertNull("box[15] must stay absent when there is no sales activity at all",
        result.boxes.get(15));
  }

  // ── computeBoxes — regression: base boxes must use ONLY_NORMAL, never ALL ────────────────
  //
  // Bug found while validating ETP-5393 Bug F against a real AEAT XML/paper form: the base
  // box groups (07/09, 04/06, 01/03, 150/152, 165/167, 22/24, 19/21, 156/158, 16/18, 168/170,
  // 28/29...39) were computed with InvoiceType.ALL (normal + corrective already netted
  // together), while boxes 14/15, 25/26 and 40/41 SEPARATELY add the corrective-only delta
  // (InvoiceType.ONLY_MEMO_AND_CORRECTIVE) on top. Summing both into the box[27]/[45]/[46]
  // totals double-counted the corrective effect. The classic engine (AEAT303Report2014,
  // every year-override 2015→2026) always computes these same base boxes with
  // InvoiceType.ONLY_NORMAL. Fix: applyPercentageSplit/fillGroupBoxes now use ONLY_NORMAL.

  /**
   * VAT_SALES_GENERAL (box 7/9) must invoke calculateAmountsMap with ONLY_NORMAL, and must
   * NEVER invoke it with ALL — regression guard for the double-counted corrective VAT bug.
   */
  @Test
  public void testComputeBoxes_sale21pct_usesOnlyNormalInvoiceType_notAll() {
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
        .thenReturn(amounts("1000.00", "210.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("1000.00", result.boxes.get(7));
    assertBd("210.00",  result.boxes.get(9));
    verify(helper).calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL));
    verify(helper, never()).calculateAmountsMap(any(), eq(InvoiceType.ALL));
  }

  /**
   * VAT_PURCHASE Normal_Operations (box 28/29, via fillGroupBoxes) must also invoke
   * calculateAmountsMap with ONLY_NORMAL, never ALL.
   */
  @Test
  public void testComputeBoxes_purchaseNormalOps_usesOnlyNormalInvoiceType_notAll() {
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
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
        .thenReturn(amounts("5000.00", "1050.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("5000.00", result.boxes.get(28));
    assertBd("1050.00", result.boxes.get(29));
    verify(helper).calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL));
    verify(helper, never()).calculateAmountsMap(any(), eq(InvoiceType.ALL));
  }

  /**
   * With both a normal-only base box (ONLY_NORMAL) and a corrective delta (box 14/15,
   * ONLY_MEMO_AND_CORRECTIVE) present, box[27] must be their plain SUM — box 9 + box 15 —
   * and must NOT also subtract/double-apply the corrective delta a second time. Before the
   * fix, box 9 itself was computed with InvoiceType.ALL (which already nets in the corrective
   * activity), so summing it with the separately-computed box 15 corrective delta
   * double-counted the correction. This test pins the correct additive relationship.
   */
  @Test
  public void testComputeBoxes_box27IsPlainSumOfNormalAndCorrective_noDoubleCounting() {
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
    // box 9 (normal-only cuota) = 210.00
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
        .thenReturn(amounts("1000.00", "210.00"));
    // box 15 (corrective-only cuota delta) = -8.00
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_MEMO_AND_CORRECTIVE)))
        .thenReturn(amounts("-200.00", "-8.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertBd("210.00", result.boxes.get(9));
    assertBd("-8.00",  result.boxes.get(15));
    // 210.00 + (-8.00) = 202.00 — plain sum, not a further-adjusted/double-counted value.
    assertBd("202.00", result.boxes.get(27));
  }

  // ── computePreOct2024EcDominantRate — regression: dominant-rate selection must also use
  // ONLY_NORMAL, matching classic AEAT303Report2023#manage005062Percent (total0/05/062Percent-
  // TaxBaseAmount there are themselves computed from calculateAmountsMap(..., ONLY_NORMAL) in
  // generateSalesLines). Box 17 is a "which rate dominates" selector over NORMAL-invoice
  // activity only — a rate with only corrective activity and no normal invoices this period is
  // correctly excluded from being dominant, exactly like classic.

  /**
   * {@link Fiscal303BoxesHandler#computePreOct2024EcDominantRate} must invoke
   * {@code calculateAmountsMap} with {@code ONLY_NORMAL}, never {@code ALL} — regression guard
   * mirroring the base-box fix applied to {@code applyPercentageSplit}/{@code fillGroupBoxes}.
   */
  @Test
  public void testComputePreOct2024EcDominantRate_usesOnlyNormalInvoiceType_notAll() {
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    TaxRate rate = mock(TaxRate.class);
    when(rate.getRate()).thenReturn(new BigDecimal("0.50"));
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
        .thenReturn(amounts("1000.00", "5.00"));

    BigDecimal dominant =
        handler.computePreOct2024EcDominantRate(helper, Collections.singletonList(rate));

    assertBd("0.50", dominant);
    verify(helper).calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL));
    verify(helper, never()).calculateAmountsMap(any(), eq(InvoiceType.ALL));
  }

  /**
   * A rate that only has corrective/credit-memo activity this period (ONLY_NORMAL amounts are
   * zero for it) must NOT be selected as dominant, even if its raw corrective volume is large —
   * box 17 only ever ranks NORMAL-invoice bases, exactly like the classic engine. This pins the
   * ONLY_NORMAL fix's interaction with the "no normal invoices at this rate" edge case.
   */
  @Test
  public void testComputePreOct2024EcDominantRate_rateWithOnlyCorrectiveActivity_neverWins() {
    AEAT303CalculationsHelper helper = mock(AEAT303CalculationsHelper.class);
    TaxRate zeroRate = mock(TaxRate.class);
    when(zeroRate.getRate()).thenReturn(BigDecimal.ZERO);
    TaxRate halfRate = mock(TaxRate.class);
    when(halfRate.getRate()).thenReturn(new BigDecimal("0.50"));

    // 0% rate: no normal invoices this period at all (only a credit memo exists upstream, not
    // modeled here since ONLY_NORMAL is the only invoice type this method ever asks for) —
    // ONLY_NORMAL comes back zero.
    when(helper.calculateAmountsMap(argThat(list -> list != null && list.contains(zeroRate)),
        eq(InvoiceType.ONLY_NORMAL))).thenReturn(amounts("0.00", "0.00"));
    // 0.50% rate: small but genuine normal-invoice activity.
    when(helper.calculateAmountsMap(argThat(list -> list != null && list.contains(halfRate)),
        eq(InvoiceType.ONLY_NORMAL))).thenReturn(amounts("10.00", "0.05"));

    BigDecimal dominant =
        handler.computePreOct2024EcDominantRate(helper, Arrays.asList(zeroRate, halfRate));

    assertBd("0.50", dominant);
  }

  // ── computeBoxes — regression: a rate with ONLY corrective activity (no normal invoices at
  // all for it this period) must leave its base box absent while the corrective delta box
  // (14/15/25/26/40/41) still carries the full corrective amount. ────────────────────────────

  /**
   * VAT_SALES_GENERAL 21% with zero normal-invoice activity this period (only a credit memo
   * exists at that rate) must leave box[7]/box[9] absent — {@code addToBox} skips zero-valued
   * writes — while box[14]/box[15] still carry the full corrective delta, and box[27] reflects
   * only that corrective amount (no base-box contribution to double-count against).
   */
  @Test
  public void testComputeBoxes_onlyCorrectiveActivityAtRate_baseBoxAbsent_correctiveBoxCarriesFull() {
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
    // No normal invoices at this rate this period.
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
        .thenReturn(amounts("0.00", "0.00"));
    // Only a credit memo, at -100.00 base / -21.00 cuota.
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_MEMO_AND_CORRECTIVE)))
        .thenReturn(amounts("-100.00", "-21.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertNull("box[7] must stay absent — no normal invoices at this rate", result.boxes.get(7));
    assertNull("box[9] must stay absent — no normal invoices at this rate", result.boxes.get(9));
    assertBd("-100.00", result.boxes.get(14));
    assertBd("-21.00",  result.boxes.get(15));
    // box[27] = 0 (absent box[9]) + (-21.00) (box[15]) = -21.00 — the corrective amount alone,
    // not double-counted against a phantom base-box contribution.
    assertBd("-21.00", result.boxes.get(27));
  }

  // ── computeBoxes — boundary: a period with ONLY corrective invoices (zero normal invoices
  // across sales and purchases) must still balance — the totals must reflect only the
  // corrective deltas, negative values must round-trip correctly, and nothing should NPE. ────

  /**
   * Sales-side 21% has only a corrective credit memo (net negative), purchase-side 21% has
   * only a corrective credit memo too (net negative deduction). box[27]/[45]/[46]/[66]/[69]/[71]
   * must all resolve from the corrective deltas alone, without throwing, and box[46] (a
   * negative "a compensar" result here) must mirror correctly into 66/69/71.
   */
  @Test
  public void testComputeBoxes_periodWithOnlyCorrectiveInvoices_totalsBalanceWithoutNormalActivity() {
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

    // Zero normal-invoice activity everywhere this period.
    when(helper.calculateAmountsMap(any(), eq(InvoiceType.ONLY_NORMAL)))
        .thenReturn(amounts("0.00", "0.00"));
    // Sales-side corrective credit memo: -500.00 base / -105.00 cuota (box 14/15).
    // Purchase-side corrective credit memo: -200.00 base / -42.00 cuota (box 40/41).
    when(helper.calculateAmountsMap(
        argThat(list -> list != null && list.contains(salesRate) && !list.contains(purchRate)),
        eq(InvoiceType.ONLY_MEMO_AND_CORRECTIVE))).thenReturn(amounts("-500.00", "-105.00"));
    when(helper.calculateAmountsMap(
        argThat(list -> list != null && list.contains(purchRate)),
        eq(InvoiceType.ONLY_MEMO_AND_CORRECTIVE))).thenReturn(amounts("-200.00", "-42.00"));

    ComputeResult result = handler.computeBoxes(org, taxReport, periods, helper, dao303);

    assertNull("box[7]/[9] must stay absent — no normal sales activity", result.boxes.get(7));
    assertNull(result.boxes.get(9));
    assertNull("box[28]/[29] must stay absent — no normal purchase activity", result.boxes.get(28));
    assertNull(result.boxes.get(29));
    assertBd("-105.00", result.boxes.get(15));
    assertBd("-42.00",  result.boxes.get(41));
    assertBd("-105.00", result.boxes.get(27));  // accrued: only box 15 contributes
    assertBd("-42.00",  result.boxes.get(45));  // deductible: only box 41 contributes
    assertBd("-63.00",  result.boxes.get(46));  // 27 - 45 = -105 - (-42) = -63
    assertBd("-63.00",  result.boxes.get(66));
    assertBd("-63.00",  result.boxes.get(69));
    assertBd("-63.00",  result.boxes.get(71));
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

  // ── resolveDeclType — AEAT letter code contract ────────────────────────────

  /**
   * Each accepted AEAT code must be returned unchanged — all 7 letters the frontend's
   * {@code TIPO_DECLARACION_FIELD} exposes. {@code D} (Devolución) and {@code X} (Devolución
   * transferencia extranjero) were the BLOCKER fix (ETP-4456): before it, both silently fell back
   * to {@code "N"}, corrupting the generated file's declaration type and, downstream, discarding
   * the IBAN {@code AEAT303Report2014} requires for those two types.
   */
  @Test
  public void testResolveDeclType_acceptedCodes() {
    for (String code : new String[]{"I", "C", "V", "U", "G", "D", "X"}) {
      assertEquals("Expected " + code + " to pass through unchanged",
          code, Fiscal303BoxesHandler.resolveDeclType(code));
    }
  }

  /**
   * Dedicated regression test for the ETP-4456 BLOCKER fix: {@code D} and {@code X} must each
   * resolve to themselves, not {@code "N"}. Kept separate from {@link
   * #testResolveDeclType_acceptedCodes} (which already covers them as part of the full 7-code
   * loop) so a future accidental removal of just these two codes fails with an unambiguous test
   * name rather than a generic loop failure.
   */
  @Test
  public void testResolveDeclType_dAndXAcceptedAfterBlockerFix() {
    assertEquals("D", Fiscal303BoxesHandler.resolveDeclType("D"));
    assertEquals("X", Fiscal303BoxesHandler.resolveDeclType("X"));
  }

  /**
   * ETP-5187 hardening: a null tipo must be REJECTED, never silently resolved to "N" — the
   * declaration type must always be explicit, never guessed.
   */
  @Test
  public void testResolveDeclType_nullIsRejected() {
    try {
      Fiscal303BoxesHandler.resolveDeclType(null);
      fail("Expected IllegalArgumentException for null tipo");
    } catch (IllegalArgumentException e) {
      assertTrue("Message must mention the missing/invalid declaration type",
          e.getMessage().contains("declaration type"));
    }
  }

  /** ETP-5187 hardening: an empty string must be rejected, never silently resolved to "N". */
  @Test
  public void testResolveDeclType_emptyIsRejected() {
    try {
      Fiscal303BoxesHandler.resolveDeclType("");
      fail("Expected IllegalArgumentException for empty tipo");
    } catch (IllegalArgumentException e) {
      assertTrue("Message must mention the missing/invalid declaration type",
          e.getMessage().contains("declaration type"));
    }
  }

  /**
   * ETP-5187 hardening: an unknown code (old Spanish alias) must be rejected, never silently
   * mapped to "N".
   */
  @Test
  public void testResolveDeclType_unknownAliasIsRejected() {
    for (String alias : new String[]{"ingresar", "compensar", "devolver"}) {
      try {
        Fiscal303BoxesHandler.resolveDeclType(alias);
        fail("Expected IllegalArgumentException for alias " + alias);
      } catch (IllegalArgumentException e) {
        // expected
      }
    }
  }

  /**
   * ETP-5187 hardening: a genuinely invented single-letter code (not among the 8 accepted AEAT
   * letters) must be rejected — confirms the accepted whitelist did not accidentally loosen into
   * a catch-all.
   */
  @Test
  public void testResolveDeclType_inventedLetterIsRejected() {
    for (String code : new String[]{"Z", "Q"}) {
      try {
        Fiscal303BoxesHandler.resolveDeclType(code);
        fail("Expected IllegalArgumentException for code " + code);
      } catch (IllegalArgumentException e) {
        // expected
      }
    }
  }

  /**
   * "N" (Resultado cero / sin actividad) is a genuinely selectable frontend option. ETP-5187
   * makes it an explicitly accepted code — no longer just an accident of the removed
   * catch-all fallback — so it must still resolve to itself exactly like before.
   */
  @Test
  public void testResolveDeclType_literalNIsExplicitlyAccepted() {
    assertEquals("N", Fiscal303BoxesHandler.resolveDeclType("N"));
  }

  // ── DEFAULT_STATUS contract ────────────────────────────────────────────────

  /** The hard-coded default status must be the locale-neutral English value "draft". */
  @Test
  public void testDefaultStatusIsDraft() {
    assertEquals("draft", FiscalDeclCrudHandler.DEFAULT_STATUS);
  }

  /**
   * {@code declToJson} must use DEFAULT_STATUS ("draft") when the declaration status is null,
   * so that GET serialization is never locale-specific.
   */
  @Test
  public void testDeclToJson_nullStatusFallsBackToDraft() throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("test-id");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_MODEL)).thenReturn("303");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_YEAR)).thenReturn(2026L);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_PERIOD)).thenReturn("T1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_TYPE)).thenReturn("O");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn(null);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_FILE_NAME)).thenReturn(null);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_FILE_EXTERNAL)).thenReturn(false);
    when(decl.get("updated")).thenReturn(null);

    JSONObject json = declHandler.declToJson(decl);
    assertEquals("draft", json.getString("status"));
  }

  /**
   * {@code declToJson} must preserve an explicit "submitted" status without
   * overwriting it with the default.
   */
  @Test
  public void testDeclToJson_explicitStatusIsPreserved() throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("test-id");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_MODEL)).thenReturn("303");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_YEAR)).thenReturn(2026L);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_PERIOD)).thenReturn("T1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_TYPE)).thenReturn("O");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("submitted");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_FILE_NAME)).thenReturn(null);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_FILE_EXTERNAL)).thenReturn(false);
    when(decl.get("updated")).thenReturn(null);

    JSONObject json = declHandler.declToJson(decl);
    assertEquals("submitted", json.getString("status"));
  }

  // ── resolveTaxReport (system-org lookup, via reflection) ──────────────────

  /**
   * Regression test for ETP-4177: records stored at ad_org_id='0' (system level)
   * must be found. The old implementation used {@code eq(org, orgId)}, which would
   * silently return nothing when the TaxReport was registered under org='0' rather
   * than the calling org — causing OBException at runtime with no clear error.
   *
   * The fix uses {@code in(org, [orgId, "0"])} so both org-specific and system-level
   * records are matched.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolveTaxReport_systemOrgRecordFound() throws Exception {
    TaxReport report = mock(TaxReport.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<TaxReport> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(TaxReport.class)).thenReturn(crit);
      when(crit.add(any(Criterion.class))).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      // The criteria returns a TaxReport whose org is "0" (system level).
      when(crit.list()).thenReturn(Collections.singletonList(report));

      TaxReport result = handler.resolveTaxReport("org-abc", "AEAT303_Q_2025");
      assertSame("Expected the system-level TaxReport to be returned", report, result);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testResolveTaxReport_orgSpecificRecordFound() throws Exception {
    TaxReport report = mock(TaxReport.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<TaxReport> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(TaxReport.class)).thenReturn(crit);
      when(crit.add(any(Criterion.class))).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      // The criteria returns a TaxReport registered directly under the calling org.
      when(crit.list()).thenReturn(Collections.singletonList(report));

      TaxReport result = handler.resolveTaxReport("org-xyz", "AEAT303_M_2025");
      assertSame("Expected the org-specific TaxReport to be returned", report, result);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testResolveTaxReport_notFoundThrowsOBException() throws Exception {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<TaxReport> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(TaxReport.class)).thenReturn(crit);
      when(crit.add(any(Criterion.class))).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      when(crit.list()).thenReturn(Collections.emptyList());

      try {
        handler.resolveTaxReport("org-nf", "AEAT303_Q_2024");
        fail("Expected OBException when no TaxReport is found");
      } catch (OBException e) {
        assertTrue("Message must contain orgId", e.getMessage().contains("org-nf"));
        assertTrue("Message must contain searchKey", e.getMessage().contains("AEAT303_Q_2024"));
      }
    }
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
