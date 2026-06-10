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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.hibernate.criterion.Criterion;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.taxreportlauncher.TaxReport;

/**
 * Unit tests for {@link Fiscal349BoxesHandler}.
 *
 * Covers HTTP routing validation only — DB-dependent methods
 * (computeOperators, handleGenerate) are integration-tested separately.
 */
public class Fiscal349BoxesHandlerTest {

  private NeoServlet servlet;
  private Fiscal349BoxesHandler handler;

  @Before
  public void setUp() {
    servlet = mock(NeoServlet.class);
    handler = new Fiscal349BoxesHandler(servlet);
  }

  // ── constructor ───────────────────────────────────────────────────

  @Test
  public void testHandlerInstantiates() {
    assertNotNull(handler);
  }

  // ── unknown entity → 404 ─────────────────────────────────────────

  @Test
  public void testUnknownEntityReturns404() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    handler.handle("unknown_entity", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
  }

  // ── non-GET method → 405 (except POST generate) ──────────────────

  @Test
  public void testPostToOperatorsReturns405() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    handler.handle("operators", "POST", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
  }

  // ── missing year/period → 400 ────────────────────────────────────

  @Test
  public void testMissingYearReturns400() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn(null);
    when(req.getParameter("period")).thenReturn("T1");

    handler.handle("operators", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  @Test
  public void testMissingPeriodReturns400() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter("period")).thenReturn(null);

    handler.handle("operators", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  // ── POST generate is allowed ──────────────────────────────────────

  @Test
  public void testPostToGenerateIsAllowed() throws IOException {
    // POST /fiscal349/generate must NOT return 405 — proceeds to param validation (→ 400).
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    handler.handle("generate", "POST", req, resp);

    org.mockito.Mockito.verify(servlet, org.mockito.Mockito.never())
        .sendError(org.mockito.ArgumentMatchers.eq(resp),
            org.mockito.ArgumentMatchers.eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
            org.mockito.ArgumentMatchers.anyString());
  }

  // ── invalid year → 400 ───────────────────────────────────────────

  @Test
  public void testInvalidYearReturns400() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("notANumber");
    when(req.getParameter("period")).thenReturn("T1");

    handler.handle("operators", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  // ── modified without since → 400 ─────────────────────────────────

  @Test
  public void testModifiedMissingSinceReturns400() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter("period")).thenReturn("T1");
    when(req.getParameter("since")).thenReturn(null);

    handler.handle("modified", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  // ── buildOperatorsArray (pure logic) ──────────────────────────────

  private static Map<String, Object> row(String bpId, String base, String key) {
    Map<String, Object> r = new HashMap<>();
    r.put("BPId", bpId);
    r.put("BPTaxBaseAmount", base == null ? null : new BigDecimal(base));
    r.put("TaxKey", key);
    return r;
  }

  private static Map<String, BigDecimal> emptySummary() {
    Map<String, BigDecimal> s = new LinkedHashMap<>();
    for (String k : Arrays.asList("E", "S", "A", "I")) {
      s.put(k, BigDecimal.ZERO);
    }
    return s;
  }

  @Test
  public void testBuildOperatorsArrayEmitsOperatorAndAccumulatesSummary() throws Exception {
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("ACME");
    when(bp.getTaxID()).thenReturn("B12345678");
    Map<String, BusinessPartner> bpMap = new HashMap<>();
    bpMap.put("bp1", bp);
    Map<String, BigDecimal> summary = emptySummary();

    JSONArray arr = handler.buildOperatorsArray(
        Collections.singletonList(row("bp1", "100.005", "E")), bpMap, summary);

    assertEquals(1, arr.length());
    JSONObject op = arr.getJSONObject(0);
    assertEquals("bp1", op.getString("bpId"));
    assertEquals("B12345678", op.getString("nif"));
    assertEquals("ACME", op.getString("name"));
    assertEquals("E", op.getString("key"));
    assertEquals("100.01", op.getString("base"));            // HALF_UP scale 2
    assertEquals(new BigDecimal("100.005"), summary.get("E")); // accumulated unscaled
  }

  @Test
  public void testBuildOperatorsArraySkipsNullAndZeroBase() throws Exception {
    Map<String, BigDecimal> summary = emptySummary();

    JSONArray arr = handler.buildOperatorsArray(
        Arrays.asList(row("bp1", null, "E"), row("bp2", "0.00", "S")),
        new HashMap<>(), summary);

    assertEquals(0, arr.length());
    assertEquals(BigDecimal.ZERO, summary.get("E"));
    assertEquals(BigDecimal.ZERO, summary.get("S"));
  }

  @Test
  public void testBuildOperatorsArrayFallsBackWhenBpMissing() throws Exception {
    JSONArray arr = handler.buildOperatorsArray(
        Collections.singletonList(row("bp9", "50", "E")), new HashMap<>(), emptySummary());

    JSONObject op = arr.getJSONObject(0);
    assertEquals("bp9", op.getString("name")); // bp not in map → name is the id
    assertEquals("", op.getString("nif"));     // no bp → empty nif
  }

  @Test
  public void testBuildOperatorsArrayHandlesNullKeyAndUnknownKey() throws Exception {
    Map<String, BigDecimal> summary = emptySummary();

    JSONArray arr = handler.buildOperatorsArray(
        Arrays.asList(row("bp1", "10", null), row("bp2", "20", "Z")),
        new HashMap<>(), summary);

    assertEquals(2, arr.length());
    assertEquals("", arr.getJSONObject(0).getString("key")); // null key → ""
    // "Z" is not a known summary bucket → nothing accumulated
    for (BigDecimal v : summary.values()) {
      assertEquals(BigDecimal.ZERO, v);
    }
  }

  @Test
  public void testBuildOperatorsArrayBpWithNullTaxIdYieldsEmptyNif() throws Exception {
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("NoNif SL");
    when(bp.getTaxID()).thenReturn(null);
    Map<String, BusinessPartner> bpMap = new HashMap<>();
    bpMap.put("bp1", bp);

    JSONArray arr = handler.buildOperatorsArray(
        Collections.singletonList(row("bp1", "5", "E")), bpMap, emptySummary());

    assertEquals("", arr.getJSONObject(0).getString("nif"));
    assertEquals("NoNif SL", arr.getJSONObject(0).getString("name"));
  }

  // ── buildInvoiceRow (pure logic) ──────────────────────────────────

  @Test
  public void testBuildInvoiceRowFullInvoice() throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("ACME");
    when(bp.getTaxID()).thenReturn("B1");
    Invoice inv = mock(Invoice.class);
    when(inv.getBusinessPartner()).thenReturn(bp);
    when(inv.getSummedLineAmount()).thenReturn(new BigDecimal("-123.456"));
    when(inv.getDocumentNo()).thenReturn("INV-1");
    when(inv.getInvoiceDate()).thenReturn(new Date(0L)); // 1970-01-01 UTC-ish

    JSONObject r = handler.buildInvoiceRow(inv, "Compra", sdf);

    assertEquals("INV-1", r.getString("ref"));
    assertEquals("Compra", r.getString("type"));
    assertEquals("ACME", r.getString("party"));
    assertEquals("B1", r.getString("nifIva"));
    assertEquals("123.46", r.getString("base")); // abs + HALF_UP scale 2
    assertEquals(sdf.format(new Date(0L)), r.getString("date"));
  }

  @Test
  public void testBuildInvoiceRowNullFieldsUseDefaults() throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    Invoice inv = mock(Invoice.class);
    when(inv.getBusinessPartner()).thenReturn(null);
    when(inv.getSummedLineAmount()).thenReturn(null);
    when(inv.getDocumentNo()).thenReturn("INV-2");
    when(inv.getInvoiceDate()).thenReturn(null);

    JSONObject r = handler.buildInvoiceRow(inv, "Venta", sdf);

    assertEquals("INV-2", r.getString("ref"));
    assertEquals("Venta", r.getString("type"));
    assertEquals("", r.getString("party"));  // null bp
    assertEquals("", r.getString("nifIva")); // null bp
    assertEquals("", r.getString("date"));   // null date
    assertEquals("0", r.getString("base"));  // null amount → ZERO
  }

  @Test
  public void testBuildInvoiceRowBpWithNullTaxId() throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("NoNif");
    when(bp.getTaxID()).thenReturn(null);
    Invoice inv = mock(Invoice.class);
    when(inv.getBusinessPartner()).thenReturn(bp);
    when(inv.getSummedLineAmount()).thenReturn(new BigDecimal("10"));
    when(inv.getDocumentNo()).thenReturn("INV-3");
    when(inv.getInvoiceDate()).thenReturn(null);

    JSONObject r = handler.buildInvoiceRow(inv, "Compra", sdf);

    assertEquals("NoNif", r.getString("party"));
    assertEquals("", r.getString("nifIva"));
  }

  // ── collectInvoices (pure logic) ──────────────────────────────────

  @Test
  public void testCollectInvoicesCombinesPurchaseAndSales() throws Exception {
    Invoice p = mock(Invoice.class);
    when(p.getDocumentNo()).thenReturn("P1");
    when(p.getSummedLineAmount()).thenReturn(new BigDecimal("1"));
    Invoice s = mock(Invoice.class);
    when(s.getDocumentNo()).thenReturn("S1");
    when(s.getSummedLineAmount()).thenReturn(new BigDecimal("2"));

    Set<Invoice> purch = new LinkedHashSet<>(Collections.singletonList(p));
    Set<Invoice> sales = new LinkedHashSet<>(Collections.singletonList(s));

    JSONArray arr = handler.collectInvoices(purch, sales);

    assertEquals(2, arr.length());
    boolean hasCompra = false;
    boolean hasVenta = false;
    for (int i = 0; i < arr.length(); i++) {
      String type = arr.getJSONObject(i).getString("type");
      if ("Compra".equals(type)) hasCompra = true;
      if ("Venta".equals(type)) hasVenta = true;
    }
    assertTrue(hasCompra);
    assertTrue(hasVenta);
  }

  @Test
  public void testCollectInvoicesEmptySetsYieldEmptyArray() throws Exception {
    JSONArray arr = handler.collectInvoices(
        Collections.<Invoice>emptySet(), Collections.<Invoice>emptySet());
    assertEquals(0, arr.length());
  }

  // ── loadBpMap (no-DB branch) ──────────────────────────────────────

  @Test
  public void testLoadBpMapReturnsEmptyWhenNoEligibleRows() {
    // All rows have null/zero base or null BPId → bpIds is empty → no DB query.
    List<Map<String, Object>> rows = Arrays.asList(
        row("bp1", null, "E"),
        row("bp2", "0", "S"),
        row(null, "100", "A"));

    Map<String, BusinessPartner> result = handler.loadBpMap(rows);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  // ── resolveTaxReport349 / findTaxReport ───────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void testResolveTaxReport349QuarterlyPrimaryMatch() {
    TaxReport report = mock(TaxReport.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<TaxReport> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(TaxReport.class)).thenReturn(crit);
      when(crit.add(any(Criterion.class))).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      // Primary search key (AEAT3492010_Q) hits on the first lookup.
      when(crit.list()).thenReturn(Collections.singletonList(report));

      TaxReport result = handler.resolveTaxReport349("org1", "T1");
      assertSame(report, result);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testResolveTaxReport349MonthlyFallbackMatch() {
    TaxReport report = mock(TaxReport.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<TaxReport> primary  = mock(OBCriteria.class);
      OBCriteria<TaxReport> fallback = mock(OBCriteria.class);
      // First createCriteria → primary (AEAT3492010_M, empty), second → fallback (AEAT349_M, hit).
      when(obDal.createCriteria(TaxReport.class)).thenReturn(primary, fallback);
      when(primary.add(any(Criterion.class))).thenReturn(primary);
      when(primary.setMaxResults(1)).thenReturn(primary);
      when(primary.list()).thenReturn(Collections.emptyList());
      when(fallback.add(any(Criterion.class))).thenReturn(fallback);
      when(fallback.setMaxResults(1)).thenReturn(fallback);
      when(fallback.list()).thenReturn(Collections.singletonList(report));

      TaxReport result = handler.resolveTaxReport349("org1", "3"); // monthly period
      assertSame(report, result);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testResolveTaxReport349ThrowsWhenNoneFound() {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<TaxReport> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(TaxReport.class)).thenReturn(crit);
      when(crit.add(any(Criterion.class))).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      when(crit.list()).thenReturn(Collections.emptyList());

      try {
        handler.resolveTaxReport349("org1", "T2");
        fail("Expected OBException when no TaxReport 349 is found");
      } catch (OBException e) {
        assertTrue(e.getMessage().contains("No TaxReport 349"));
      }
    }
  }

  // ── findTaxReport / system-org lookup (ETP-4177) ─────────────────

  /**
   * Regression test for ETP-4177: {@code findTaxReport} must accept records stored at
   * {@code ad_org_id='0'} (system level). The old {@code eq(org, orgId)} predicate would
   * silently return nothing when the TaxReport was registered at org='0', causing
   * {@code resolveTaxReport349} to fall through both search keys and throw OBException.
   *
   * The fix uses {@code in(org, [orgId, "0"])} so system-level records are always found.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testFindTaxReport_systemOrgRecordFound() {
    TaxReport report = mock(TaxReport.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<TaxReport> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(TaxReport.class)).thenReturn(crit);
      when(crit.add(any(Criterion.class))).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      // Criteria returns a TaxReport registered under org='0' (system level).
      when(crit.list()).thenReturn(Collections.singletonList(report));

      // Tested via resolveTaxReport349 which calls findTaxReport internally.
      TaxReport result = handler.resolveTaxReport349("org-abc", "T1");
      assertSame("Expected the system-level TaxReport to be returned", report, result);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testFindTaxReport_orgSpecificRecordFound() {
    TaxReport report = mock(TaxReport.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<TaxReport> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(TaxReport.class)).thenReturn(crit);
      when(crit.add(any(Criterion.class))).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      // Criteria returns a TaxReport registered directly under the calling org.
      when(crit.list()).thenReturn(Collections.singletonList(report));

      TaxReport result = handler.resolveTaxReport349("org-xyz", "T2");
      assertSame("Expected the org-specific TaxReport to be returned", report, result);
    }
  }

  /**
   * When {@code findTaxReport} returns null for both primary and fallback search keys,
   * {@code resolveTaxReport349} must throw {@link OBException} with both the org and
   * period type in the message. This is the "both keys miss" path documented in ETP-4177.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolveTaxReport349_throwsWhenBothKeysMiss() {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<TaxReport> primary  = mock(OBCriteria.class);
      OBCriteria<TaxReport> fallback = mock(OBCriteria.class);
      when(obDal.createCriteria(TaxReport.class)).thenReturn(primary, fallback);
      when(primary.add(any(Criterion.class))).thenReturn(primary);
      when(primary.setMaxResults(1)).thenReturn(primary);
      when(primary.list()).thenReturn(Collections.emptyList());   // AEAT3492010_Q misses
      when(fallback.add(any(Criterion.class))).thenReturn(fallback);
      when(fallback.setMaxResults(1)).thenReturn(fallback);
      when(fallback.list()).thenReturn(Collections.emptyList());  // AEAT349_Q misses too

      try {
        handler.resolveTaxReport349("org-miss", "T3");
        fail("Expected OBException when both search keys return nothing");
      } catch (OBException e) {
        assertTrue("Message must contain orgId", e.getMessage().contains("org-miss"));
        assertTrue("Message must mention period type", e.getMessage().contains("Q"));
      }
    }
  }

  /**
   * When {@code findTaxReport} gets an empty list it must return null (no exception).
   * This is tested indirectly: primary returns empty → resolveTaxReport349 tries fallback.
   * If findTaxReport threw on empty list the fallback call would never be reached and
   * the monthly-fallback test above would also fail.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testFindTaxReport_emptyListReturnsNullNotException() {
    TaxReport fallbackReport = mock(TaxReport.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<TaxReport> primary  = mock(OBCriteria.class);
      OBCriteria<TaxReport> fallback = mock(OBCriteria.class);
      when(obDal.createCriteria(TaxReport.class)).thenReturn(primary, fallback);
      when(primary.add(any(Criterion.class))).thenReturn(primary);
      when(primary.setMaxResults(1)).thenReturn(primary);
      when(primary.list()).thenReturn(Collections.emptyList()); // null → continue to fallback
      when(fallback.add(any(Criterion.class))).thenReturn(fallback);
      when(fallback.setMaxResults(1)).thenReturn(fallback);
      when(fallback.list()).thenReturn(Collections.singletonList(fallbackReport));

      // If findTaxReport threw on empty list, this call would never reach the fallback
      // and would propagate an unexpected exception instead of returning fallbackReport.
      TaxReport result = handler.resolveTaxReport349("org1", "T1");
      assertSame(fallbackReport, result);
    }
  }
}
