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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

import java.util.Collection;

import org.codehaus.jettison.json.JSONArray;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.query.Query;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat349.es.AEAT3492010ReportDao;
import org.openbravo.module.taxreportlauncher.TaxReport;

/**
 * Unit tests for {@link Fiscal349BoxesHandler}.
 *
 * Covers HTTP routing validation and the pure helpers. computeOperators is exercised here only
 * for its contact/phone fallbacks, over an empty period with the DAO construction-mocked; its
 * aggregation over real invoices and handleGenerate are integration-tested separately.
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal349GenerateSupport
 * @covers com.etendoerp.go.schemaforge.Fiscal349BoxesHandler
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

  // ── rectificative operator rows (ETP-5027) ────────────────────────

  /** A corrective row exactly as AEAT3492010ReportDao returns it: an UNSIGNED magnitude. */
  private static Map<String, Object> daoCorrectiveRow(String bpId, String magnitude, String key) {
    return daoCorrectiveRow(bpId, magnitude, key, "2026", "1T");
  }

  private static Map<String, Object> daoCorrectiveRow(String bpId, String magnitude, String key,
      String year, String period) {
    Map<String, Object> r = row(bpId, magnitude, key);
    r.put("BPFormerAmount", new BigDecimal("100"));
    r.put("Year", year);
    r.put("Period", period);
    return r;
  }

  // ── declaredYear / declaredPeriod on operator rows (ETP-5027, QA F4) ──

  /**
   * The DAO groups corrective rows by {@code (BPId, TaxKey, Year, Period)}, so correcting the
   * same partner's 2025/T1 and 2025/T2 sales of goods in ONE declaration produces two rows that
   * differ only by period. Dropping {@code Year}/{@code Period} left the frontend unable to tell
   * them apart, which collided its React keys AND its row selection (ticking one ticked both).
   */
  @Test
  public void testCorrectiveRowsCarryTheDeclaredYearAndPeriod() throws Exception {
    List<Map<String, Object>> signed = Fiscal349BoxesHandler.toSignedDeltaRows(Arrays.asList(
        daoCorrectiveRow("bp1", "30", "E", "2025", "1T"),
        daoCorrectiveRow("bp1", "50", "E", "2025", "2T")));

    JSONArray arr = handler.appendOperators(
        new JSONArray(), signed, new HashMap<>(), emptySummary(), true);

    assertEquals(2, arr.length());
    assertEquals("2025", arr.getJSONObject(0).getString("declaredYear"));
    assertEquals("1T",   arr.getJSONObject(0).getString("declaredPeriod"));
    assertEquals("2025", arr.getJSONObject(1).getString("declaredYear"));
    assertEquals("2T",   arr.getJSONObject(1).getString("declaredPeriod"));
  }

  /**
   * Regular rows come from {@code getTaxBaseAmountPerBusinessPartner}, which groups by
   * {@code (BPId, TaxKey)} only and carries no {@code Year}/{@code Period}. They must keep
   * exactly the JSON shape they had — the keys are emitted only when present.
   */
  @Test
  public void testRegularRowsCarryNoDeclaredYearOrPeriod() throws Exception {
    JSONArray arr = handler.buildOperatorsArray(
        Collections.singletonList(row("bp1", "10", "E")), new HashMap<>(), emptySummary());

    assertFalse(arr.getJSONObject(0).has("declaredYear"));
    assertFalse(arr.getJSONObject(0).has("declaredPeriod"));
  }

  /** A blank or missing Year/Period is omitted rather than emitted as "". */
  @Test
  public void testBlankDeclaredYearAndPeriodAreOmitted() throws Exception {
    List<Map<String, Object>> signed = Fiscal349BoxesHandler.toSignedDeltaRows(
        Collections.singletonList(daoCorrectiveRow("bp1", "30", "E", "  ", null)));

    JSONArray arr = handler.appendOperators(
        new JSONArray(), signed, new HashMap<>(), emptySummary(), true);

    assertFalse(arr.getJSONObject(0).has("declaredYear"));
    assertFalse(arr.getJSONObject(0).has("declaredPeriod"));
  }

  /**
   * Pins the sign convention, which is the whole point of reporting a delta.
   *
   * <p>{@code getCorrectiveTaxBaseAmountPerBusinessPartner} computes {@code BPTaxBaseAmount} as
   * {@code sum(it.taxableAmount * -1)}, so a credit note for 3 units of a 10.00 product (line
   * amount {@code -30}) arrives as {@code +30} — an unsigned magnitude, not a delta.
   * {@code AEAT3492010Report.generateLine2_Corrections} writes
   * {@code BPFormerAmount - BPTaxBaseAmount} as the corrected base and {@code BPFormerAmount} as
   * the previously declared one, so {@code corrected - former == -BPTaxBaseAmount}. We therefore
   * negate, and the identity holds in BOTH directions.
   */
  @Test
  public void testToSignedDeltaRowsNegatesTheAeatMagnitude() {
    List<Map<String, Object>> signed = Fiscal349BoxesHandler.toSignedDeltaRows(Arrays.asList(
        daoCorrectiveRow("bp1", "30", "E"),    // reduction of 30 → delta -30
        daoCorrectiveRow("bp2", "-30", "S")));  // upward correction  → delta +30

    assertEquals(new BigDecimal("-30"), signed.get(0).get("BPTaxBaseAmount"));
    assertEquals(new BigDecimal("30"),  signed.get(1).get("BPTaxBaseAmount"));
    // Everything else on the row is carried through untouched.
    assertEquals("bp1",  signed.get(0).get("BPId"));
    assertEquals("E",    signed.get(0).get("TaxKey"));
    assertEquals("2026", signed.get(0).get("Year"));
  }

  /** A null amount survives as null so {@code appendOperators} can skip the row. */
  @Test
  public void testToSignedDeltaRowsPassesNullAmountThrough() {
    List<Map<String, Object>> signed = Fiscal349BoxesHandler.toSignedDeltaRows(
        Collections.singletonList(daoCorrectiveRow("bp1", null, "E")));

    assertEquals(1, signed.size());
    assertNull(signed.get(0).get("BPTaxBaseAmount"));
  }

  /** The DAO's own maps must not be mutated — rows are copied. */
  @Test
  public void testToSignedDeltaRowsDoesNotMutateInput() {
    Map<String, Object> original = daoCorrectiveRow("bp1", "30", "E");
    Fiscal349BoxesHandler.toSignedDeltaRows(Collections.singletonList(original));

    assertEquals(new BigDecimal("30"), original.get("BPTaxBaseAmount"));
  }

  @Test
  public void testToSignedDeltaRowsHandlesNullList() {
    assertEquals(0, Fiscal349BoxesHandler.toSignedDeltaRows(null).size());
  }

  /**
   * End-to-end for the sign: a reduction must surface as a NEGATIVE {@code base} on the operator
   * row and a NEGATIVE rectificative subtotal. Nothing on the way out may clamp or {@code abs()}
   * it — the zero-base skip in {@code appendOperators} must drop only genuine no-ops.
   */
  @Test
  public void testReductionYieldsNegativeBaseAndNegativeSubtotal() throws Exception {
    Map<String, BigDecimal> rectificative = emptySummary();
    List<Map<String, Object>> signed = Fiscal349BoxesHandler.toSignedDeltaRows(
        Collections.singletonList(daoCorrectiveRow("bp1", "30", "E")));

    JSONArray arr = handler.appendOperators(
        new JSONArray(), signed, new HashMap<>(), rectificative, true);

    assertEquals(1, arr.length());
    assertEquals("-30.00", arr.getJSONObject(0).getString("base"));
    assertTrue(arr.getJSONObject(0).getBoolean("rectificative"));
    assertEquals(new BigDecimal("-30"), rectificative.get("E"));

    JSONObject subtotal = Fiscal349BoxesHandler.buildKeyTotals(rectificative);
    assertEquals("-30.00", subtotal.getString("totalE"));
  }

  /**
   * A correction whose delta nets to zero is a no-op and is skipped, exactly like a zero regular
   * base — but a negative one is NOT, which is the case the skip could plausibly have swallowed.
   */
  @Test
  public void testZeroDeltaIsSkippedButNegativeIsKept() throws Exception {
    Map<String, BigDecimal> rectificative = emptySummary();
    List<Map<String, Object>> signed = Fiscal349BoxesHandler.toSignedDeltaRows(Arrays.asList(
        daoCorrectiveRow("bp-noop", "0", "E"),
        daoCorrectiveRow("bp-real", "5", "E")));

    JSONArray arr = handler.appendOperators(
        new JSONArray(), signed, new HashMap<>(), rectificative, true);

    assertEquals(1, arr.length());
    assertEquals("bp-real", arr.getJSONObject(0).getString("bpId"));
    assertEquals("-5.00", arr.getJSONObject(0).getString("base"));
    assertEquals(new BigDecimal("-5"), rectificative.get("E"));
  }

  /**
   * Regression guard for ETP-5027. Corrective invoices are stripped out before the per-BP
   * aggregation, so their business partners never reached the Operadores array. They are now
   * appended to the SAME array — tagged {@code rectificative: true} so the UI can badge them
   * and so the existing key filter/search picks them up — but with their OWN totals map.
   *
   * <p>The load-bearing assertion is the last one: the pre-existing {@code summary} totals must
   * be byte-for-byte what they were before correctives were emitted at all. The AEAT treats
   * correctives as a separate record type (registro tipo 2); folding a signed delta into the
   * regular subtotal would silently understate the declared base.
   */
  @Test
  public void testAppendOperatorsTagsRectificativeRowsWithoutTouchingSummary() throws Exception {
    Map<String, BigDecimal> summary       = emptySummary();
    Map<String, BigDecimal> rectificative = emptySummary();

    JSONArray arr = handler.buildOperatorsArray(
        Collections.singletonList(row("bp1", "100", "E")), new HashMap<>(), summary);
    handler.appendOperators(arr,
        Fiscal349BoxesHandler.toSignedDeltaRows(
            Collections.singletonList(daoCorrectiveRow("bp2", "40", "E"))),
        new HashMap<>(), rectificative, true);

    assertEquals(2, arr.length());
    assertEquals("bp1", arr.getJSONObject(0).getString("bpId"));
    assertFalse(arr.getJSONObject(0).getBoolean("rectificative"));
    assertEquals("bp2", arr.getJSONObject(1).getString("bpId"));
    assertTrue(arr.getJSONObject(1).getBoolean("rectificative"));
    assertEquals("-40.00", arr.getJSONObject(1).getString("base"));

    // Corrective deltas land in their own subtotal only...
    assertEquals(new BigDecimal("-40"), rectificative.get("E"));
    // ...and the regular summary is exactly the non-corrective total, unchanged and unreduced.
    assertEquals(new BigDecimal("100"), summary.get("E"));
    assertEquals(BigDecimal.ZERO, summary.get("S"));
    assertEquals(BigDecimal.ZERO, summary.get("A"));
    assertEquals(BigDecimal.ZERO, summary.get("I"));
  }

  /**
   * A period with no corrective invoices must leave the response identical to before ETP-5027:
   * no extra rows, an all-zero rectificative subtotal, and an untouched summary.
   */
  @Test
  public void testAppendOperatorsWithNoCorrectivesChangesNothing() throws Exception {
    Map<String, BigDecimal> summary       = emptySummary();
    Map<String, BigDecimal> rectificative = emptySummary();

    JSONArray arr = handler.buildOperatorsArray(
        Collections.singletonList(row("bp1", "100", "S")), new HashMap<>(), summary);
    handler.appendOperators(arr, Collections.<Map<String, Object>>emptyList(),
        new HashMap<>(), rectificative, true);

    assertEquals(1, arr.length());
    assertEquals(new BigDecimal("100"), summary.get("S"));
    for (BigDecimal v : rectificative.values()) {
      assertEquals(BigDecimal.ZERO, v);
    }
  }

  /**
   * Rows produced by the regular path must be explicitly flagged {@code rectificative: false}
   * rather than omitting the field — the frontend badges off this flag, and a missing key
   * would make every regular operator ambiguous.
   */
  @Test
  public void testBuildOperatorsArrayFlagsRegularRowsAsNonRectificative() throws Exception {
    JSONArray arr = handler.buildOperatorsArray(
        Collections.singletonList(row("bp1", "10", "E")), new HashMap<>(), emptySummary());

    assertTrue(arr.getJSONObject(0).has("rectificative"));
    assertFalse(arr.getJSONObject(0).getBoolean("rectificative"));
  }

  /**
   * The rectificative subtotal is emitted as its own object with EXACTLY the same
   * {@code totalE/S/A/I} shape as {@code summary}.
   *
   * <p>ETP-5027 (QA F1): it must NOT carry a {@code total} grand figure. E/S are sales and A/I
   * are purchases, so their sum is not a meaningful quantity — netting a sales correction
   * against a purchase correction would render {@code 0,00} for two real, non-cancelling
   * corrections. Both subtotals now go through the single no-grand-total shape.
   */
  @Test
  public void testBuildKeyTotalsShapeHasNoGrandTotal() throws Exception {
    Map<String, BigDecimal> totals = emptySummary();
    totals.put("E", new BigDecimal("-30.005"));
    totals.put("S", new BigDecimal("12"));

    JSONObject json = Fiscal349BoxesHandler.buildKeyTotals(totals);
    assertEquals("-30.01", json.getString("totalE")); // HALF_UP, away from zero
    assertEquals("12.00",  json.getString("totalS"));
    assertEquals("0.00",   json.getString("totalA"));
    assertEquals("0.00",   json.getString("totalI"));
    assertFalse(json.has("total"));
  }

  /**
   * The exact regression: a sales correction of -30 and a purchase correction of +30 must not
   * collapse into a single "0" figure. With no grand total the two remain visible per key.
   */
  @Test
  public void testOffsettingSalesAndPurchaseCorrectionsStayVisible() throws Exception {
    Map<String, BigDecimal> totals = emptySummary();
    totals.put("E", new BigDecimal("-30"));
    totals.put("A", new BigDecimal("30"));

    JSONObject json = Fiscal349BoxesHandler.buildKeyTotals(totals);
    assertEquals("-30.00", json.getString("totalE"));
    assertEquals("30.00",  json.getString("totalA"));
    assertFalse(json.has("total"));
  }

  // ── buildOperatorsArray VIES status mapping (ETP-4755) ─────────────

  @Test
  public void testBuildOperatorsArrayViesValidStatus() throws Exception {
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("ACME");
    when(bp.getTaxID()).thenReturn("B1");
    when(bp.getOBTIKVIESStatus()).thenReturn("V");
    Map<String, BusinessPartner> bpMap = new HashMap<>();
    bpMap.put("bp1", bp);

    JSONArray arr = handler.buildOperatorsArray(
        Collections.singletonList(row("bp1", "10", "E")), bpMap, emptySummary());

    assertEquals("valid", arr.getJSONObject(0).getString("vies"));
  }

  @Test
  public void testBuildOperatorsArrayViesInvalidStatus() throws Exception {
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("ACME");
    when(bp.getTaxID()).thenReturn("B1");
    when(bp.getOBTIKVIESStatus()).thenReturn("I");
    Map<String, BusinessPartner> bpMap = new HashMap<>();
    bpMap.put("bp1", bp);

    JSONArray arr = handler.buildOperatorsArray(
        Collections.singletonList(row("bp1", "10", "E")), bpMap, emptySummary());

    assertEquals("invalid", arr.getJSONObject(0).getString("vies"));
  }

  @Test
  public void testBuildOperatorsArrayViesPendingForNullBlankOrPStatus() throws Exception {
    BusinessPartner bpNull = mock(BusinessPartner.class);
    when(bpNull.getName()).thenReturn("Null status");
    when(bpNull.getOBTIKVIESStatus()).thenReturn(null);

    BusinessPartner bpBlank = mock(BusinessPartner.class);
    when(bpBlank.getName()).thenReturn("Blank status");
    when(bpBlank.getOBTIKVIESStatus()).thenReturn("");

    BusinessPartner bpP = mock(BusinessPartner.class);
    when(bpP.getName()).thenReturn("P status");
    when(bpP.getOBTIKVIESStatus()).thenReturn("P");

    Map<String, BusinessPartner> bpMap = new HashMap<>();
    bpMap.put("bp1", bpNull);
    bpMap.put("bp2", bpBlank);
    bpMap.put("bp3", bpP);

    JSONArray arr = handler.buildOperatorsArray(
        Arrays.asList(row("bp1", "10", "E"), row("bp2", "10", "S"), row("bp3", "10", "A")),
        bpMap, emptySummary());

    for (int i = 0; i < arr.length(); i++) {
      assertEquals("pending", arr.getJSONObject(i).getString("vies"));
    }
  }

  @Test
  public void testBuildOperatorsArrayViesPendingWhenBpMissing() throws Exception {
    JSONArray arr = handler.buildOperatorsArray(
        Collections.singletonList(row("bp-missing", "10", "E")), new HashMap<>(), emptySummary());

    assertEquals("pending", arr.getJSONObject(0).getString("vies")); // graceful null-bp fallback
  }

  // ── buildInvoiceRow (pure logic) ──────────────────────────────────

  private static Date day(String yyyyMmDd) throws Exception {
    return new SimpleDateFormat("yyyy-MM-dd").parse(yyyyMmDd);
  }

  private static Invoice invoice(String id, String docNo, String amount) {
    Invoice inv = mock(Invoice.class);
    when(inv.getId()).thenReturn(id);
    when(inv.getDocumentNo()).thenReturn(docNo);
    when(inv.getSummedLineAmount()).thenReturn(amount != null ? new BigDecimal(amount) : null);
    return inv;
  }

  private static Map<String, Map<String, BigDecimal>> keyBases(String invId, String... keyAndBase) {
    Map<String, BigDecimal> bases = new LinkedHashMap<>();
    for (int i = 0; i < keyAndBase.length; i += 2) {
      bases.put(keyAndBase[i], new BigDecimal(keyAndBase[i + 1]));
    }
    Map<String, Map<String, BigDecimal>> m = new HashMap<>();
    m.put(invId, bases);
    return m;
  }

  @Test
  public void testBuildInvoiceRowFullInvoice() throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("ACME");
    when(bp.getTaxID()).thenReturn("B1");
    Invoice inv = invoice("inv-1", "INV-1", "-123.456");
    when(inv.getBusinessPartner()).thenReturn(bp);
    when(inv.getInvoiceDate()).thenReturn(day("2026-03-15"));
    when(inv.getAccountingDate()).thenReturn(day("2026-03-31"));

    JSONObject r = handler.buildInvoiceRow(inv, "Compra", sdf, "A", null);

    assertEquals("inv-1", r.getString("id"));
    assertEquals("INV-1", r.getString("ref"));
    assertEquals("Compra", r.getString("type"));
    assertEquals("ACME", r.getString("party"));
    assertEquals("B1", r.getString("nifIva"));
    // null keyBase → summed line amount, HALF_UP, SIGN KEPT (ETP-5597 CP-20: a credit note is negative)
    assertEquals("-123.46", r.getString("base"));
    assertEquals("2026-03-15", r.getString("date"));
    assertEquals("2026-03-31", r.getString("accountingDate"));
    assertEquals("A", r.getString("key"));
  }

  @Test
  public void testBuildInvoiceRowNullFieldsUseDefaults() throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    Invoice inv = invoice("inv-2", "INV-2", null);
    when(inv.getBusinessPartner()).thenReturn(null);
    when(inv.getInvoiceDate()).thenReturn(null);
    when(inv.getAccountingDate()).thenReturn(null);

    JSONObject r = handler.buildInvoiceRow(inv, "Venta", sdf, null, null);

    assertEquals("inv-2", r.getString("id"));
    assertEquals("INV-2", r.getString("ref"));
    assertEquals("Venta", r.getString("type"));
    assertEquals("", r.getString("party"));  // null bp
    assertEquals("", r.getString("nifIva")); // null bp
    assertEquals("", r.getString("date"));   // null date → ""
    assertFalse(r.has("accountingDate"));    // null accounting date → key absent
    assertEquals("0", r.getString("base"));  // null amount → ZERO
    assertEquals("", r.getString("key"));    // unresolved key → ""
  }

  @Test
  public void testBuildInvoiceRowBpWithNullTaxId() throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("NoNif");
    when(bp.getTaxID()).thenReturn(null);
    Invoice inv = invoice("inv-3", "INV-3", "10");
    when(inv.getBusinessPartner()).thenReturn(bp);

    JSONObject r = handler.buildInvoiceRow(inv, "Compra", sdf, null, null);

    assertEquals("NoNif", r.getString("party"));
    assertEquals("", r.getString("nifIva"));
    assertEquals("", r.getString("key"));
  }

  /** ETP-5597: a per-key base (mixed invoice) replaces the invoice-level summed line amount. */
  @Test
  public void testBuildInvoiceRowKeyBaseOverridesSummedLineAmount() throws Exception {
    Invoice inv = invoice("inv-4", "INV-4", "1000");

    JSONObject r = handler.buildInvoiceRow(inv, "Venta", new SimpleDateFormat("yyyy-MM-dd"),
        "S", new BigDecimal("-250.005"));

    assertEquals("-250.01", r.getString("base")); // HALF_UP of the signed key base, not 1000
    assertEquals("S", r.getString("key"));
  }

  // ── collectInvoices (pure logic) ──────────────────────────────────

  @Test
  public void testCollectInvoicesCombinesPurchaseAndSales() throws Exception {
    Invoice p = invoice("p1", "P1", "1");
    Invoice s = invoice("s1", "S1", "2");

    Set<Invoice> purch = new LinkedHashSet<>(Collections.singletonList(p));
    Set<Invoice> sales = new LinkedHashSet<>(Collections.singletonList(s));
    Map<String, Map<String, BigDecimal>> keys = new HashMap<>(keyBases("p1", "A", "99"));
    keys.putAll(keyBases("s1", "E", "99"));

    JSONArray arr = handler.collectInvoices(purch, sales, keys);

    assertEquals(2, arr.length());
    JSONObject compra = arr.getJSONObject(0);
    JSONObject venta = arr.getJSONObject(1);
    assertEquals("Compra", compra.getString("type"));
    assertEquals("A", compra.getString("key"));
    assertEquals("p1", compra.getString("id"));
    assertEquals("Venta", venta.getString("type"));
    assertEquals("E", venta.getString("key"));
    assertEquals("s1", venta.getString("id"));
  }

  /**
   * ETP-5597: a single-key invoice keeps exactly its old row — one row, invoice-level base
   * (summed line amount), even though the per-key map carries a (possibly different) base.
   */
  @Test
  public void testCollectInvoicesSingleKeyInvoiceKeepsInvoiceLevelBase() throws Exception {
    Invoice s = invoice("s1", "S1", "120.00");

    JSONArray arr = handler.collectInvoices(Collections.<Invoice>emptySet(),
        new LinkedHashSet<>(Collections.singletonList(s)), keyBases("s1", "E", "77.00"));

    assertEquals(1, arr.length());
    assertEquals("E", arr.getJSONObject(0).getString("key"));
    assertEquals("120.00", arr.getJSONObject(0).getString("base"));
  }

  /** ETP-5597: a sale mixing goods (E) and services (S) backs both keys, each with its own base. */
  @Test
  public void testCollectInvoicesMixedSaleEmitsOneRowPerKeyWithItsBase() throws Exception {
    Invoice s = invoice("s1", "S1", "1000.00");
    when(s.getAccountingDate()).thenReturn(day("2026-02-28"));

    JSONArray arr = handler.collectInvoices(Collections.<Invoice>emptySet(),
        new LinkedHashSet<>(Collections.singletonList(s)),
        keyBases("s1", "E", "600.00", "S", "400.00"));

    assertEquals(2, arr.length());
    JSONObject e = arr.getJSONObject(0);
    JSONObject sv = arr.getJSONObject(1);
    assertEquals("E", e.getString("key"));
    assertEquals("600.00", e.getString("base"));
    assertEquals("S", sv.getString("key"));
    assertEquals("400.00", sv.getString("base"));
    for (JSONObject row : Arrays.asList(e, sv)) {
      assertEquals("s1", row.getString("id"));
      assertEquals("S1", row.getString("ref"));
      assertEquals("Venta", row.getString("type"));
      assertEquals("2026-02-28", row.getString("accountingDate"));
    }
  }

  /**
   * ETP-5597 (CP-20): a rectifying invoice (credit note) keeps its NEGATIVE base in "Facturas
   * origen" — single-key (invoice-level summed line amount) and mixed (per-key, already halved
   * for a purchase) alike. The operator rows and the AEAT file aggregate these lines signed, so an
   * abs() here showed a credit note as if it added to the base.
   */
  @Test
  public void testCollectInvoicesKeepsTheNegativeBaseOfACreditNote() throws Exception {
    Invoice single = invoice("p1", "AB-1", "-80.00");
    Invoice mixed  = invoice("p2", "AB-2", "-300.00");
    Map<String, Map<String, BigDecimal>> keys = new HashMap<>(keyBases("p1", "A", "-40.00"));
    keys.putAll(keyBases("p2", "A", "-100.00", "I", "-50.00"));

    JSONArray arr = handler.collectInvoices(
        new LinkedHashSet<>(Arrays.asList(single, mixed)), Collections.<Invoice>emptySet(), keys);

    assertEquals(3, arr.length());
    assertEquals("-80.00", arr.getJSONObject(0).getString("base"));
    assertEquals("-100.00", arr.getJSONObject(1).getString("base"));
    assertEquals("-50.00", arr.getJSONObject(2).getString("base"));
  }

  /**
   * ETP-5597: a purchase mixing goods (A) and services (I) backs both keys. The bases arrive
   * already halved by resolveInvoiceKeyBases' purchase rule (tax amount != 0 → taxable / 2), so
   * the rows carry them as-is and not the invoice's summed line amount.
   */
  @Test
  public void testCollectInvoicesMixedPurchaseEmitsOneRowPerKeyWithItsBase() throws Exception {
    Invoice p = invoice("p1", "P1", "300.00");

    JSONArray arr = handler.collectInvoices(new LinkedHashSet<>(Collections.singletonList(p)),
        Collections.<Invoice>emptySet(), keyBases("p1", "A", "100.00", "I", "50.00"));

    assertEquals(2, arr.length());
    assertEquals("A", arr.getJSONObject(0).getString("key"));
    assertEquals("100.00", arr.getJSONObject(0).getString("base"));
    assertEquals("I", arr.getJSONObject(1).getString("key"));
    assertEquals("50.00", arr.getJSONObject(1).getString("base"));
    assertEquals("Compra", arr.getJSONObject(1).getString("type"));
    assertEquals("p1", arr.getJSONObject(0).getString("id"));
    assertEquals("p1", arr.getJSONObject(1).getString("id"));
  }

  /** An invoice with no resolved key still yields one row, with key "" and its own base. */
  @Test
  public void testCollectInvoicesUnresolvedInvoiceKeepsOneRowWithEmptyKey() throws Exception {
    Invoice noEntry = invoice("s1", "S1", "10");
    Invoice emptyEntry = invoice("s2", "S2", "20");
    Map<String, Map<String, BigDecimal>> keys = new HashMap<>();
    keys.put("s2", new LinkedHashMap<>());

    JSONArray arr = handler.collectInvoices(Collections.<Invoice>emptySet(),
        new LinkedHashSet<>(Arrays.asList(noEntry, emptyEntry)), keys);
    JSONArray nullMap = handler.collectInvoices(Collections.<Invoice>emptySet(),
        new LinkedHashSet<>(Collections.singletonList(noEntry)), null);

    assertEquals(2, arr.length());
    assertEquals("", arr.getJSONObject(0).getString("key"));
    assertEquals("10.00", arr.getJSONObject(0).getString("base"));
    assertEquals("", arr.getJSONObject(1).getString("key"));
    assertEquals("20.00", arr.getJSONObject(1).getString("base"));
    assertEquals(1, nullMap.length());
    assertEquals("", nullMap.getJSONObject(0).getString("key"));
  }

  @Test
  public void testCollectInvoicesEmptySetsYieldEmptyArray() throws Exception {
    JSONArray arr = handler.collectInvoices(
        Collections.<Invoice>emptySet(), Collections.<Invoice>emptySet(), new HashMap<>());
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

  // ── collectRectifications (ETP-4404) ──────────────────────────────

  /**
   * Installs the OBDal→Session→Query chain for the scalar ReversedInvoices HQL
   * and returns the mocked Query so tests can control {@code list()}.
   */
  @SuppressWarnings("unchecked")
  private static Query<Object[]> mockRectifQuery(MockedStatic<OBDal> dalMock) {
    OBDal obDal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(obDal);
    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    Query<Object[]> query = mock(Query.class);
    when(session.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
    when(query.setParameterList(anyString(), any(Collection.class))).thenReturn(query);
    return query;
  }

  @Test
  public void testCollectRectificationsEmptyAndNullSetsSkipTheQuery() throws Exception {
    // No OBDal static mock is installed: if the empty/null guard did not
    // short-circuit, the HQL query would hit the real (unavailable) DAL and throw.
    JSONArray arr = handler.collectRectifications(
        Collections.<Invoice>emptySet(), null);

    assertEquals(0, arr.length());
  }

  @Test
  public void testCollectRectificationsMapsRowKeysAndScalesAmounts() throws Exception {
    Invoice corrective = mock(Invoice.class);
    Set<Invoice> purch = new LinkedHashSet<>(Collections.singletonList(corrective));
    Object[] row = {
        "NC-01", new Date(0L), "Acme Corp", "B12345678",
        "10000067", "2025", "1T",
        new BigDecimal("1500.005"),  // baseProducts → HALF_UP scale 2
        null                          // baseServices → null → 0.00
    };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockRectifQuery(dalMock);
      when(query.list()).thenReturn(Collections.singletonList(row));

      JSONArray arr = handler.collectRectifications(purch, Collections.<Invoice>emptySet());

      assertEquals(1, arr.length());
      JSONObject r = arr.getJSONObject(0);
      assertEquals("NC-01", r.getString("ref"));
      assertEquals(new SimpleDateFormat("yyyy-MM-dd").format(new Date(0L)), r.getString("date"));
      assertEquals("Compra", r.getString("type"));
      assertEquals("Acme Corp", r.getString("party"));
      assertEquals("B12345678", r.getString("nifIva"));
      assertEquals("10000067", r.getString("originalRef"));
      assertEquals("2025", r.getString("declaredYear"));
      assertEquals("1T", r.getString("declaredPeriod"));
      assertEquals("1500.01", r.getString("baseProducts")); // HALF_UP to 2 decimals
      assertEquals("0.00", r.getString("baseServices"));    // null → ZERO scaled
    }
  }

  @Test
  public void testCollectRectificationsNullScalarsFallBackToEmptyStrings() throws Exception {
    Invoice corrective = mock(Invoice.class);
    Set<Invoice> sales = new LinkedHashSet<>(Collections.singletonList(corrective));
    Object[] row = { null, null, null, null, null, null, null, null, null };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockRectifQuery(dalMock);
      when(query.list()).thenReturn(Collections.singletonList(row));

      JSONArray arr = handler.collectRectifications(Collections.<Invoice>emptySet(), sales);

      JSONObject r = arr.getJSONObject(0);
      assertEquals("", r.getString("ref"));
      assertEquals("", r.getString("date"));
      assertEquals("", r.getString("party"));
      assertEquals("", r.getString("nifIva"));
      assertEquals("", r.getString("originalRef"));
      assertEquals("", r.getString("declaredYear"));
      assertEquals("", r.getString("declaredPeriod"));
      assertEquals("0.00", r.getString("baseProducts"));
      assertEquals("0.00", r.getString("baseServices"));
    }
  }

  @Test
  public void testCollectRectificationsTypeIsCompraForPurchaseAndVentaForSales() throws Exception {
    Invoice purchInv = mock(Invoice.class);
    Invoice salesInv = mock(Invoice.class);
    Set<Invoice> purch = new LinkedHashSet<>(Collections.singletonList(purchInv));
    Set<Invoice> sales = new LinkedHashSet<>(Collections.singletonList(salesInv));
    Object[] purchRow = { "P-1", null, null, null, null, null, null, null, null };
    Object[] salesRow = { "S-1", null, null, null, null, null, null, null, null };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockRectifQuery(dalMock);
      // First list() serves the purchase set, second serves the sales set.
      when(query.list()).thenReturn(
          Collections.singletonList(purchRow), Collections.singletonList(salesRow));

      JSONArray arr = handler.collectRectifications(purch, sales);

      assertEquals(2, arr.length());
      assertEquals("P-1", arr.getJSONObject(0).getString("ref"));
      assertEquals("Compra", arr.getJSONObject(0).getString("type"));
      assertEquals("S-1", arr.getJSONObject(1).getString("ref"));
      assertEquals("Venta", arr.getJSONObject(1).getString("type"));
    }
  }

  // ── resolveInvoiceKeyBases (ETP-4755, ETP-5597) ─────────────────────

  /**
   * Installs the OBDal→Session→Query chain for the per-invoice key/base HQL and returns the
   * mocked Query so tests can control {@code list()}. When {@code hql} is non-null, the HQL text
   * passed to {@code createQuery} is captured into it.
   */
  @SuppressWarnings("unchecked")
  private static Query<Object[]> mockInvoiceKeysQuery(MockedStatic<OBDal> dalMock,
      String[] hql) {
    OBDal obDal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(obDal);
    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    Query<Object[]> query = mock(Query.class);
    when(session.createQuery(anyString(), eq(Object[].class))).thenAnswer(inv -> {
      if (hql != null) {
        hql[0] = inv.getArgument(0);
      }
      return query;
    });
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.setParameterList(anyString(), any(Collection.class))).thenReturn(query);
    return query;
  }

  private static Query<Object[]> mockInvoiceKeysQuery(MockedStatic<OBDal> dalMock) {
    return mockInvoiceKeysQuery(dalMock, null);
  }

  @Test
  public void testResolveInvoiceKeyBasesEmptyInvoicesOrTaxRatesSkipsTheQuery() {
    // No OBDal static mock installed: if the empty/null guard did not short-circuit,
    // the HQL query would hit the real (unavailable) DAL and throw.
    Invoice inv = mock(Invoice.class);
    Set<Invoice> invoices = new LinkedHashSet<>(Collections.singletonList(inv));
    TaxRate rate = mock(TaxRate.class);

    assertTrue(handler.resolveInvoiceKeyBases(
        Collections.<Invoice>emptySet(), Collections.singletonList(rate), "tr1", false).isEmpty());
    assertTrue(handler.resolveInvoiceKeyBases(
        invoices, Collections.<TaxRate>emptyList(), "tr1", true).isEmpty());
    assertTrue(handler.resolveInvoiceKeyBases(
        null, Collections.singletonList(rate), "tr1", false).isEmpty());
    assertTrue(handler.resolveInvoiceKeyBases(invoices, null, "tr1", true).isEmpty());
  }

  @Test
  public void testResolveInvoiceKeyBasesMapsInvoiceIdToKeyAndBase() {
    Set<Invoice> invoices = new LinkedHashSet<>(Collections.singletonList(mock(Invoice.class)));
    Object[] row = { "inv-1", "E", new BigDecimal("150.00") };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockInvoiceKeysQuery(dalMock);
      when(query.list()).thenReturn(Collections.singletonList(row));

      Map<String, Map<String, BigDecimal>> result = handler.resolveInvoiceKeyBases(
          invoices, Collections.singletonList(mock(TaxRate.class)), "tr1", false);

      assertEquals(1, result.size());
      assertEquals(Collections.singletonMap("E", new BigDecimal("150.00")), result.get("inv-1"));
      verify(query).setParameter("taxReportId", "tr1");
    }
  }

  /**
   * ETP-5597: an invoice whose tax lines map to two keys keeps BOTH, each with its own base, in
   * the ascending key order the HQL's {@code order by} delivers (replaces the old "most lines
   * wins" / tie-break single-key resolution).
   */
  @Test
  public void testResolveInvoiceKeyBasesKeepsEveryKeyOfAMixedInvoice() {
    Set<Invoice> invoices = new LinkedHashSet<>(Collections.singletonList(mock(Invoice.class)));
    Object[] rowE = { "inv-1", "E", new BigDecimal("600.00") };
    Object[] rowS = { "inv-1", "S", new BigDecimal("400.00") };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockInvoiceKeysQuery(dalMock);
      when(query.list()).thenReturn(Arrays.asList(rowE, rowS));

      Map<String, Map<String, BigDecimal>> result = handler.resolveInvoiceKeyBases(
          invoices, Collections.singletonList(mock(TaxRate.class)), "tr1", false);

      assertEquals(1, result.size());
      Map<String, BigDecimal> bases = result.get("inv-1");
      assertEquals(Arrays.asList("E", "S"), new ArrayList<>(bases.keySet()));
      assertEquals(new BigDecimal("600.00"), bases.get("E"));
      assertEquals(new BigDecimal("400.00"), bases.get("S"));
    }
  }

  @Test
  public void testResolveInvoiceKeyBasesMultipleInvoices() {
    Set<Invoice> invoices = new LinkedHashSet<>(
        Arrays.asList(mock(Invoice.class), mock(Invoice.class)));
    Object[] row1 = { "inv-1", "S", new BigDecimal("2") };
    Object[] row2 = { "inv-2", "A", new BigDecimal("1") };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockInvoiceKeysQuery(dalMock);
      when(query.list()).thenReturn(Arrays.asList(row1, row2));

      Map<String, Map<String, BigDecimal>> result = handler.resolveInvoiceKeyBases(
          invoices, Collections.singletonList(mock(TaxRate.class)), "tr1", true);

      assertEquals(2, result.size());
      assertEquals(Collections.singletonMap("S", new BigDecimal("2")), result.get("inv-1"));
      assertEquals(Collections.singletonMap("A", new BigDecimal("1")), result.get("inv-2"));
    }
  }

  /**
   * ETP-5597: purchases use the AEAT3492010ReportDao amount rule — a tax line with a non-zero tax
   * amount contributes half its taxable amount — while sales sum the taxable amount as-is. The
   * arithmetic runs in the HQL, so this pins the expression each side sends.
   */
  @Test
  public void testResolveInvoiceKeyBasesHalvesPurchaseBaseWhenTaxAmountIsNonZero() {
    Set<Invoice> invoices = new LinkedHashSet<>(Collections.singletonList(mock(Invoice.class)));
    String[] purchaseHql = new String[1];
    String[] salesHql = new String[1];

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      when(mockInvoiceKeysQuery(dalMock, purchaseHql).list()).thenReturn(Collections.emptyList());
      handler.resolveInvoiceKeyBases(
          invoices, Collections.singletonList(mock(TaxRate.class)), "tr1", true);

      when(mockInvoiceKeysQuery(dalMock, salesHql).list()).thenReturn(Collections.emptyList());
      handler.resolveInvoiceKeyBases(
          invoices, Collections.singletonList(mock(TaxRate.class)), "tr1", false);
    }

    assertTrue(purchaseHql[0].contains("sum(case it.taxAmount when 0 then "
        + "coalesce(it.taxableAmount, 0) else (coalesce(it.taxableAmount, 0) / 2) end)"));
    assertTrue(salesHql[0].contains("sum(coalesce(it.taxableAmount, 0))"));
    assertFalse(salesHql[0].contains("/ 2"));
    assertTrue(salesHql[0].contains("group by i.id, trp.tributaryKey.name"));
  }

  /**
   * Rows with a null key are dropped; a non-BigDecimal sum (the /2 branch may widen the type) is
   * converted, a null sum counts as zero, and repeated (invoice, key) rows are added up.
   */
  @Test
  public void testResolveInvoiceKeyBasesSkipsNullKeyAndNormalisesAmounts() {
    Set<Invoice> invoices = new LinkedHashSet<>(Collections.singletonList(mock(Invoice.class)));
    Object[] nullKey = { "inv-1", null, new BigDecimal("99") };
    Object[] doubleSum = { "inv-1", "A", Double.valueOf(12.5) };
    Object[] repeated = { "inv-1", "A", new BigDecimal("7.5") };
    Object[] nullSum = { "inv-2", "I", null };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockInvoiceKeysQuery(dalMock);
      when(query.list()).thenReturn(Arrays.asList(nullKey, doubleSum, repeated, nullSum));

      Map<String, Map<String, BigDecimal>> result = handler.resolveInvoiceKeyBases(
          invoices, Collections.singletonList(mock(TaxRate.class)), "tr1", true);

      assertEquals(Collections.singleton("A"), result.get("inv-1").keySet());
      assertEquals(0, new BigDecimal("20").compareTo(result.get("inv-1").get("A")));
      assertEquals(BigDecimal.ZERO, result.get("inv-2").get("I"));
    }
  }

  // ── guardNotAlreadySubmitted (generate only, ETP-5438) ────────────────
  //
  // "Block re-presentation once already submitted... stop recalculating invoices" — these cover
  // the backend defense-in-depth half of that: /fiscal349/generate takes no declaration id (only
  // org/year/period) and, before this fix, had no notion of any declaration's status at all, so
  // a direct/raw call could silently regenerate an already-presented declaration even with the
  // frontend button hidden. The /fiscal349/operators read is intentionally NOT gated — the
  // frontend freezes a submitted declaration from a once-per-session compute that needs it.

  @SuppressWarnings("unchecked")
  @Test
  public void testGuardNotAlreadySubmittedNoDeclarationYetDoesNotThrow() {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      when(query.list()).thenReturn(Collections.emptyList());

      handler.guardNotAlreadySubmitted("org1", 2026, "T1"); // must not throw
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testGuardNotAlreadySubmittedReadyDoesNotThrow() {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      BaseOBObject decl = declWithSeqAndStatus(0L, "ready");
      when(query.list()).thenReturn(Collections.singletonList(decl));

      handler.guardNotAlreadySubmitted("org1", 2026, "T1"); // must not throw
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testGuardNotAlreadySubmittedDraftDoesNotThrow() {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      BaseOBObject decl = declWithSeqAndStatus(0L, "draft");
      when(query.list()).thenReturn(Collections.singletonList(decl));

      handler.guardNotAlreadySubmitted("org1", 2026, "T1"); // must not throw
    }
  }

  @SuppressWarnings("unchecked")
  @Test(expected = AbstractFiscalHandler.AlreadySubmittedException.class)
  public void testGuardNotAlreadySubmittedSubmittedThrows() {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      BaseOBObject decl = declWithSeqAndStatus(0L, "submitted");
      when(query.list()).thenReturn(Collections.singletonList(decl));

      handler.guardNotAlreadySubmitted("org1", 2026, "T1");
    }
  }

  @SuppressWarnings("unchecked")
  @Test(expected = AbstractFiscalHandler.AlreadySubmittedException.class)
  public void testGuardNotAlreadySubmittedSubmittedExtThrows() {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      BaseOBObject decl = declWithSeqAndStatus(0L, "submitted_ext");
      when(query.list()).thenReturn(Collections.singletonList(decl));

      handler.guardNotAlreadySubmitted("org1", 2026, "T1");
    }
  }

  @SuppressWarnings("unchecked")
  @Test(expected = AbstractFiscalHandler.AlreadySubmittedException.class)
  public void testGuardNotAlreadySubmittedSubmittedAckThrows() {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      BaseOBObject decl = declWithSeqAndStatus(0L, "submitted_ack");
      when(query.list()).thenReturn(Collections.singletonList(decl));

      handler.guardNotAlreadySubmitted("org1", 2026, "T1");
    }
  }

  /**
   * A period with more than one declaration (the rectificativa flow — an older one was
   * presented, a newer draft was opened for the same period afterward) must gate on the LATEST
   * one (highest DECL_SEQ), never an older, already-submitted one — otherwise a fresh
   * rectificativa draft could never compute at all. Rows are handed to the mock out of DECL_SEQ
   * order on purpose, to prove the result does not depend on list iteration order.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testGuardNotAlreadySubmittedGatesOnLatestDeclSeqNotFirstInList() {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      BaseOBObject newerDraft = declWithSeqAndStatus(1L, "draft");
      BaseOBObject olderSubmitted = declWithSeqAndStatus(0L, "submitted");
      when(query.list()).thenReturn(Arrays.asList(newerDraft, olderSubmitted));

      handler.guardNotAlreadySubmitted("org1", 2026, "T1"); // must not throw — latest (seq 1) is draft
    }
  }

  // ── dispatch() wiring: reads open, generate 409 (ETP-5438) ────────────

  /**
   * ETP-5438 — {@code operators} stays available for a submitted declaration. A legacy submitted
   * declaration without a snapshot keeps the live compute (no data-fix, product decision).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testDispatchOperatorsComputesLiveWhenSubmittedWithoutSnapshot() throws Exception {
    Fiscal349BoxesHandler h = org.mockito.Mockito.spy(handler);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(body));
    JSONObject computed = new JSONObject();
    computed.put("operators", new JSONArray());
    org.mockito.Mockito.doReturn(computed).when(h).computeOperators("org1", 2026, "T1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      BaseOBObject decl = declWithSeqAndStatus(0L, "submitted_ack");
      when(query.list()).thenReturn(Collections.singletonList(decl));

      h.dispatch("operators", "org1", 2026, "T1", mock(HttpServletRequest.class), resp);
    }

    verify(servlet, org.mockito.Mockito.never())
        .sendError(any(), org.mockito.ArgumentMatchers.anyInt(), anyString());
    verify(h).computeOperators("org1", 2026, "T1");
    org.junit.Assert.assertEquals(computed.toString(), body.toString());
  }

  /**
   * ETP-5438 — a submitted declaration WITH a snapshot is served from it and the live compute is
   * never reached.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testDispatchOperatorsServesSnapshotWithoutComputingWhenSubmitted() throws Exception {
    Fiscal349BoxesHandler h = org.mockito.Mockito.spy(handler);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(body));
    String snapshot = "{\"operators\":[{\"nif\":\"FR1\",\"base\":\"10.00\"}],\"summary\":{}}";

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      BaseOBObject decl = declWithSeqAndStatus(0L, "submitted");
      when(decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT)).thenReturn(snapshot);
      when(query.list()).thenReturn(Collections.singletonList(decl));

      h.dispatch("operators", "org1", 2026, "T1", mock(HttpServletRequest.class), resp);
    }

    verify(h, org.mockito.Mockito.never())
        .computeOperators(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString());
    org.junit.Assert.assertEquals(new JSONObject(snapshot).toString(), body.toString());
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testDispatchGenerateReturns409WhenAlreadySubmitted() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      BaseOBObject decl = declWithSeqAndStatus(0L, "submitted");
      when(query.list()).thenReturn(Collections.singletonList(decl));

      handler.dispatch("generate", "org1", 2026, "T1", req, resp);
    }

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_CONFLICT), anyString());
    // The real generator (writeGeneratedFile) never ran — no file attachment was ever set up.
    verify(resp, org.mockito.Mockito.never())
        .setHeader(eq("Content-Disposition"), anyString());
  }

  /**
   * ETP-5438 review W1 — a {@code *} session (org {@code "0"}): the snapshot lookup queries the
   * org the declaration is stored under, not the effective leaf org the compute uses.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testDispatchOperatorsLooksSnapshotUpWithSessionOrgNotEffectiveOrg() throws Exception {
    Fiscal349BoxesHandler h = org.mockito.Mockito.spy(handler);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(body));
    String snapshot = "{\"operators\":[],\"summary\":{}}";

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockClient(ctxMock, "client1", "0");
      OBQuery<BaseOBObject> query = mockDeclQuery(dalMock);
      BaseOBObject decl = declWithSeqAndStatus(0L, "submitted");
      when(decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT)).thenReturn(snapshot);
      when(query.list()).thenReturn(Collections.singletonList(decl));

      h.dispatch("operators", "leaf-org", 2026, "T1", mock(HttpServletRequest.class), resp);

      verify(query).setNamedParameter("orgId", "0");
    }
    verify(h, org.mockito.Mockito.never())
        .computeOperators(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString());
    org.junit.Assert.assertEquals(new JSONObject(snapshot).toString(), body.toString());
  }

  // ── test helpers (ETP-5438) ───────────────────────────────────────────

  private static void mockClient(MockedStatic<OBContext> ctxMock, String clientId) {
    mockClient(ctxMock, clientId, "org1");
  }

  /** Same, with an explicit SESSION org (the org declarations are stored under). */
  private static void mockClient(MockedStatic<OBContext> ctxMock, String clientId,
      String sessionOrgId) {
    OBContext ctx = mock(OBContext.class);
    ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(ctx.getCurrentClient()).thenReturn(client);
    org.openbravo.model.common.enterprise.Organization org =
        mock(org.openbravo.model.common.enterprise.Organization.class);
    when(org.getId()).thenReturn(sessionOrgId);
    when(ctx.getCurrentOrganization()).thenReturn(org);
  }

  @SuppressWarnings("unchecked")
  private static OBQuery<BaseOBObject> mockDeclQuery(MockedStatic<OBDal> dalMock) {
    OBDal obDal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(obDal);
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
        .thenReturn(query);
    return query;
  }

  private static BaseOBObject declWithSeqAndStatus(long declSeq, String status) {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(declSeq);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn(status);
    return decl;
  }

  // ── resolveCurrentUserContactName (ETP-5456) ───────────────────────────
  //
  // Extracted so Fiscal349GenerateSupport#applyContactParams (generation time, existing) and
  // computeOperators's new read-only contactFallback (frontend pre-generation validation) resolve
  // the "contact" fallback through the EXACT same one-liner instead of each repeating
  // `OBContext.getOBContext().getUser().getName()`. Since the ETP-5456 java:S1448 follow-up moved
  // this method (with its whole file-name/contact/org-data resolution cluster) out of
  // Fiscal349BoxesHandler into Fiscal349GenerateSupport, it now lives there as a package-private
  // `static` method — still invoked via reflection here for consistency with this file's other
  // setAccessible-based access, even though the new class no longer requires `private`.

  @Test
  public void testResolveCurrentUserContactNameReturnsTheLoggedInUsersName() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      OBContext ctx = mock(OBContext.class);
      User user = mock(User.class);
      when(user.getName()).thenReturn("Ada Lovelace");
      when(ctx.getUser()).thenReturn(user);
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);

      String result = invokeResolveCurrentUserContactName();

      assertEquals("Ada Lovelace", result);
    }
  }

  @Test
  public void testResolveCurrentUserContactNamePropagatesANullUserName() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      OBContext ctx = mock(OBContext.class);
      User user = mock(User.class);
      when(user.getName()).thenReturn(null);
      when(ctx.getUser()).thenReturn(user);
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);

      assertNull(invokeResolveCurrentUserContactName());
    }
  }

  // ── applyContactParams — AEAT 349 type-1 "Persona de contacto" width (ETP-5597, CP-19) ──
  //
  // AEAT3492010Report#generateLine1 writes the contact into a fixed 40-character slot via
  // OBTL_Utility.format(contact, 40, ...) WITHOUT truncating it first (unlike the BP name in the
  // type-2 records, which goes through trunk(..., 40)). Any value longer than 40 made the whole
  // generation fail with "longitud esperada 40" and no file. The most common trigger is the
  // blank-field fallback — the logged-in AD_User's name, which on Etendo GO tenants is often the
  // e-mail-based username — but a long typed value hits it too.

  private static final String LONG_CONTACT =
      "qa.contact.user+etp5597-tenant-test@example.domain"; // 50 chars, synthetic

  @Test
  public void testApplyContactParamsFitsATypedContactIntoTheAeat40CharSlot() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("phone")).thenReturn("600123123");
    when(request.getParameter("contact")).thenReturn(LONG_CONTACT);
    Map<String, String> params = new HashMap<>();

    new Fiscal349GenerateSupport().applyContactParams(request, "ORG", params);

    assertEquals(LONG_CONTACT.substring(0, 40), params.get("Contact"));
  }

  @Test
  public void testApplyContactParamsFitsTheCurrentUserFallbackIntoTheAeat40CharSlot() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("phone")).thenReturn("600123123");
    when(request.getParameter("contact")).thenReturn("");
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      OBContext ctx = mock(OBContext.class);
      User user = mock(User.class);
      when(user.getName()).thenReturn(LONG_CONTACT);
      when(ctx.getUser()).thenReturn(user);
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
      Map<String, String> params = new HashMap<>();

      new Fiscal349GenerateSupport().applyContactParams(request, "ORG", params);

      assertEquals(LONG_CONTACT.substring(0, 40), params.get("Contact"));
    }
  }

  @Test
  public void testApplyContactParamsTrimsAndKeepsAShortContactUnchanged() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("phone")).thenReturn("600123123");
    when(request.getParameter("contact")).thenReturn("  Ada Lovelace  ");
    Map<String, String> params = new HashMap<>();

    new Fiscal349GenerateSupport().applyContactParams(request, "ORG", params);

    assertEquals("Ada Lovelace", params.get("Contact"));
    assertEquals("600123123", params.get("Phone"));
  }

  // ── applyContactParams — AEAT 349 type-1 "Teléfono de contacto" width (ETP-5597) ──
  //
  // Same trap as the contact, on the 9-digit phone slot: generateLine1 formats the phone with
  // OBTL_Utility.format(phone, 9, '0', ...) without truncating, so a phone longer than 9 characters
  // aborted generation with "longitud esperada 9". The modal now limits typed input to 9 digits,
  // but the blank-field fallback (the org contact's phone in AD_OrgInformation) is free text such
  // as "+34 600 123 123".

  @Test
  public void testApplyContactParamsFitsTheOrgPhoneFallbackIntoTheAeat9DigitSlot() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("phone")).thenReturn("");
    when(request.getParameter("contact")).thenReturn("Ada Lovelace");
    Fiscal349GenerateSupport support = spy(new Fiscal349GenerateSupport());
    doReturn("+34 600 123 123").when(support).resolveOrgPhone("ORG");
    Map<String, String> params = new HashMap<>();

    support.applyContactParams(request, "ORG", params);

    assertEquals("600123123", params.get("Phone"));
  }

  @Test
  public void testApplyContactParamsStripsSeparatorsFromATypedPhone() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("phone")).thenReturn("600 12-31.23");
    when(request.getParameter("contact")).thenReturn("Ada Lovelace");
    Map<String, String> params = new HashMap<>();

    new Fiscal349GenerateSupport().applyContactParams(request, "ORG", params);

    assertEquals("600123123", params.get("Phone"));
  }

  @Test
  public void testFitAeatPhoneKeepsTheLastNineDigitsAndToleratesBlanks() {
    assertEquals("600123123", Fiscal349GenerateSupport.fitAeatPhone("0034600123123"));
    assertEquals("943123456", Fiscal349GenerateSupport.fitAeatPhone("943123456"));
    assertEquals("12345", Fiscal349GenerateSupport.fitAeatPhone(" 12345 "));
    assertEquals("", Fiscal349GenerateSupport.fitAeatPhone("ext."));
    assertNull(Fiscal349GenerateSupport.fitAeatPhone(null));
  }


  // ── fitAeatContact boundaries (ETP-5597, CP-19) ──

  @Test
  public void testFitAeatContactKeepsExactly40CharactersAsIs() {
    String forty = "a".repeat(40);
    assertEquals(forty, Fiscal349GenerateSupport.fitAeatContact(forty));
  }

  @Test
  public void testFitAeatContactTrimsTheTrailingBlankLeftByTheCutAt40() {
    // 41 characters whose 40th is a space: the cut keeps 40, the trailing blank is trimmed → 39.
    String value = "b".repeat(39) + " c";
    assertEquals(41, value.length());

    String result = Fiscal349GenerateSupport.fitAeatContact(value);

    assertEquals("b".repeat(39), result);
    assertEquals(39, result.length());
  }

  @Test
  public void testFitAeatContactReturnsNullForANullContact() {
    assertNull(Fiscal349GenerateSupport.fitAeatContact(null));
  }

  @Test
  public void testApplyContactParamsFallsBackToTheCurrentUserForAWhitespaceOnlyContact() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("phone")).thenReturn("600123123");
    when(request.getParameter("contact")).thenReturn("   ");
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      stubCurrentUserName(ctxMock, "  " + LONG_CONTACT);
      Map<String, String> params = new HashMap<>();

      new Fiscal349GenerateSupport().applyContactParams(request, "ORG", params);

      assertEquals(LONG_CONTACT.substring(0, 40), params.get("Contact"));
    }
  }

  @Test
  public void testApplyContactParamsOmitsContactWhenTypedAndFallbackAreBothNull() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("phone")).thenReturn("600123123");
    when(request.getParameter("contact")).thenReturn(null);
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      stubCurrentUserName(ctxMock, null);
      Map<String, String> params = new HashMap<>();

      new Fiscal349GenerateSupport().applyContactParams(request, "ORG", params);

      assertFalse(params.containsKey("Contact"));
      assertEquals("600123123", params.get("Phone"));
    }
  }

  // ── computeOperators — contactFallback / phoneFallback normalisation (ETP-5597) ──
  //
  // The read-only fallbacks the frontend validates against must be the SAME values generation
  // writes into the type-1 record, so they go through fitAeatContact / fitAeatPhone too.

  @Test
  public void testComputeOperatorsCutsALongUserNameContactFallbackTo40() throws Exception {
    JSONObject root = computeOperatorsWithFallbacks(LONG_CONTACT, "600123123");

    assertEquals(LONG_CONTACT.substring(0, 40), root.getString("contactFallback"));
  }

  @Test
  public void testComputeOperatorsNormalisesAnInternationalOrgPhoneFallback() throws Exception {
    JSONObject root = computeOperatorsWithFallbacks("Contact Person", "+34 600 123 123");

    assertEquals("600123123", root.getString("phoneFallback"));
    assertEquals("Contact Person", root.getString("contactFallback"));
  }

  @Test
  public void testComputeOperatorsReportsAnEmptyPhoneFallbackForAPhoneWithNoDigits()
      throws Exception {
    JSONObject root = computeOperatorsWithFallbacks("Contact Person", "ext.");

    assertEquals("", root.getString("phoneFallback"));
  }

  @Test
  public void testComputeOperatorsReportsEmptyFallbacksWhenNothingResolves() throws Exception {
    JSONObject root = computeOperatorsWithFallbacks(null, null);

    assertEquals("", root.getString("contactFallback"));
    assertEquals("", root.getString("phoneFallback"));
  }

  private static void stubCurrentUserName(MockedStatic<OBContext> ctxMock, String name) {
    OBContext ctx = mock(OBContext.class);
    User user = mock(User.class);
    when(user.getName()).thenReturn(name);
    when(ctx.getUser()).thenReturn(user);
    ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
  }

  /**
   * Runs {@code computeOperators} for a period with no 349 invoices (the DAO is a construction
   * mock, so every collection it returns is empty) and the given current-user name / org phone,
   * so the only thing under test is how the two fallbacks are normalised into the root JSON.
   */
  private static JSONObject computeOperatorsWithFallbacks(String userName, String orgPhone)
      throws Exception {
    try (MockedConstruction<Fiscal349GenerateSupport> supportMock =
             mockConstruction(Fiscal349GenerateSupport.class, (support, context) -> {
               when(support.resolveOrgNif("ORG")).thenReturn("B00000000");
               when(support.resolveOrgPhone("ORG")).thenReturn(orgPhone);
             });
         MockedConstruction<AEAT3492010ReportDao> daoMock =
             mockConstruction(AEAT3492010ReportDao.class);
         MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      Organization org = mock(Organization.class);
      when(org.getName()).thenReturn("Test Org");
      OBDal dal = mock(OBDal.class);
      when(dal.get(Organization.class, "ORG")).thenReturn(org);
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      stubCurrentUserName(ctxMock, userName);

      TaxReport taxReport = mock(TaxReport.class);
      when(taxReport.getId()).thenReturn("TR349");
      Fiscal349BoxesHandler h = spy(new Fiscal349BoxesHandler(mock(NeoServlet.class)));
      doReturn(taxReport).when(h).resolveTaxReport349("ORG", "T1");
      doReturn(mock(AcctSchema.class)).when(h).resolveAcctSchema();
      doReturn(Collections.singletonList(mock(Period.class)))
          .when(h).resolvePeriods("ORG", 2026, "T1");

      JSONObject root = h.computeOperators("ORG", 2026, "T1");

      assertEquals(0, root.getJSONArray("operators").length());
      return root;
    }
  }

  /**
   * Reflection helper for {@link Fiscal349GenerateSupport}'s {@code static}
   * {@code resolveCurrentUserContactName()}. Kept local to this test class — nothing else needs
   * to call it directly, since {@code computeOperators}'s use of it is covered structurally (this
   * same helper is what {@code computeOperators} calls to fill {@code contactFallback} — see the
   * class-level Javadoc on {@code resolveCurrentUserContactName} in {@link
   * Fiscal349GenerateSupport}), and {@code computeOperators} as a whole remains DB-integration-
   * tested separately per this file's own top comment.
   */
  private static String invokeResolveCurrentUserContactName() throws Exception {
    Method m = Fiscal349GenerateSupport.class.getDeclaredMethod("resolveCurrentUserContactName");
    m.setAccessible(true);
    return (String) m.invoke(null);
  }
}
