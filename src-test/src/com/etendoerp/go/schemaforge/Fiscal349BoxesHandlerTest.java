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

import java.util.Collection;

import org.codehaus.jettison.json.JSONArray;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.query.Query;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.tax.TaxRate;
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

  @Test
  public void testBuildInvoiceRowFullInvoice() throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("ACME");
    when(bp.getTaxID()).thenReturn("B1");
    Invoice inv = mock(Invoice.class);
    when(inv.getId()).thenReturn("inv-1");
    when(inv.getBusinessPartner()).thenReturn(bp);
    when(inv.getSummedLineAmount()).thenReturn(new BigDecimal("-123.456"));
    when(inv.getDocumentNo()).thenReturn("INV-1");
    when(inv.getInvoiceDate()).thenReturn(new Date(0L)); // 1970-01-01 UTC-ish

    Map<String, String> invoiceKeys = new HashMap<>();
    invoiceKeys.put("inv-1", "A");

    JSONObject r = handler.buildInvoiceRow(inv, "Compra", sdf, invoiceKeys);

    assertEquals("INV-1", r.getString("ref"));
    assertEquals("Compra", r.getString("type"));
    assertEquals("ACME", r.getString("party"));
    assertEquals("B1", r.getString("nifIva"));
    assertEquals("123.46", r.getString("base")); // abs + HALF_UP scale 2
    assertEquals(sdf.format(new Date(0L)), r.getString("date"));
    assertEquals("A", r.getString("key")); // resolved from invoiceKeys map
  }

  @Test
  public void testBuildInvoiceRowNullFieldsUseDefaults() throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    Invoice inv = mock(Invoice.class);
    when(inv.getId()).thenReturn("inv-2");
    when(inv.getBusinessPartner()).thenReturn(null);
    when(inv.getSummedLineAmount()).thenReturn(null);
    when(inv.getDocumentNo()).thenReturn("INV-2");
    when(inv.getInvoiceDate()).thenReturn(null);

    JSONObject r = handler.buildInvoiceRow(inv, "Venta", sdf, new HashMap<>());

    assertEquals("INV-2", r.getString("ref"));
    assertEquals("Venta", r.getString("type"));
    assertEquals("", r.getString("party"));  // null bp
    assertEquals("", r.getString("nifIva")); // null bp
    assertEquals("", r.getString("date"));   // null date
    assertEquals("0", r.getString("base"));  // null amount → ZERO
    assertEquals("", r.getString("key"));    // no entry in invoiceKeys → ""
  }

  @Test
  public void testBuildInvoiceRowBpWithNullTaxId() throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getName()).thenReturn("NoNif");
    when(bp.getTaxID()).thenReturn(null);
    Invoice inv = mock(Invoice.class);
    when(inv.getId()).thenReturn("inv-3");
    when(inv.getBusinessPartner()).thenReturn(bp);
    when(inv.getSummedLineAmount()).thenReturn(new BigDecimal("10"));
    when(inv.getDocumentNo()).thenReturn("INV-3");
    when(inv.getInvoiceDate()).thenReturn(null);

    JSONObject r = handler.buildInvoiceRow(inv, "Compra", sdf, null);

    assertEquals("NoNif", r.getString("party"));
    assertEquals("", r.getString("nifIva"));
    assertEquals("", r.getString("key")); // null invoiceKeys map → graceful ""
  }

  // ── collectInvoices (pure logic) ──────────────────────────────────

  @Test
  public void testCollectInvoicesCombinesPurchaseAndSales() throws Exception {
    Invoice p = mock(Invoice.class);
    when(p.getId()).thenReturn("p1");
    when(p.getDocumentNo()).thenReturn("P1");
    when(p.getSummedLineAmount()).thenReturn(new BigDecimal("1"));
    Invoice s = mock(Invoice.class);
    when(s.getId()).thenReturn("s1");
    when(s.getDocumentNo()).thenReturn("S1");
    when(s.getSummedLineAmount()).thenReturn(new BigDecimal("2"));

    Set<Invoice> purch = new LinkedHashSet<>(Collections.singletonList(p));
    Set<Invoice> sales = new LinkedHashSet<>(Collections.singletonList(s));
    Map<String, String> invoiceKeys = new HashMap<>();
    invoiceKeys.put("p1", "A");
    invoiceKeys.put("s1", "E");

    JSONArray arr = handler.collectInvoices(purch, sales, invoiceKeys);

    assertEquals(2, arr.length());
    boolean hasCompra = false;
    boolean hasVenta = false;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject row = arr.getJSONObject(i);
      String type = row.getString("type");
      if ("Compra".equals(type)) {
        hasCompra = true;
        assertEquals("A", row.getString("key"));
      }
      if ("Venta".equals(type)) {
        hasVenta = true;
        assertEquals("E", row.getString("key"));
      }
    }
    assertTrue(hasCompra);
    assertTrue(hasVenta);
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

  // ── resolveInvoiceKeys (ETP-4755) ───────────────────────────────────

  /**
   * Installs the OBDal→Session→Query chain for the scalar per-invoice-key HQL and returns
   * the mocked Query so tests can control {@code list()}. Unlike {@link #mockRectifQuery},
   * this HQL also binds a scalar named parameter ({@code taxReportId}) alongside the two
   * list parameters.
   */
  @SuppressWarnings("unchecked")
  private static Query<Object[]> mockInvoiceKeysQuery(MockedStatic<OBDal> dalMock) {
    OBDal obDal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(obDal);
    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    Query<Object[]> query = mock(Query.class);
    when(session.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.setParameterList(anyString(), any(Collection.class))).thenReturn(query);
    return query;
  }

  @Test
  public void testResolveInvoiceKeysEmptyInvoicesOrTaxRatesSkipsTheQuery() {
    // No OBDal static mock installed: if the empty/null guard did not short-circuit,
    // the HQL query would hit the real (unavailable) DAL and throw.
    Invoice inv = mock(Invoice.class);
    Set<Invoice> invoices = new LinkedHashSet<>(Collections.singletonList(inv));
    TaxRate rate = mock(TaxRate.class);

    Map<String, String> emptyInvoices = handler.resolveInvoiceKeys(
        Collections.<Invoice>emptySet(), Collections.singletonList(rate), "tr1");
    Map<String, String> emptyRates = handler.resolveInvoiceKeys(
        invoices, Collections.<TaxRate>emptyList(), "tr1");
    Map<String, String> nullInvoices = handler.resolveInvoiceKeys(null, Collections.singletonList(rate), "tr1");
    Map<String, String> nullRates = handler.resolveInvoiceKeys(invoices, null, "tr1");

    assertNotNull(emptyInvoices);
    assertTrue(emptyInvoices.isEmpty());
    assertTrue(emptyRates.isEmpty());
    assertTrue(nullInvoices.isEmpty());
    assertTrue(nullRates.isEmpty());
  }

  @Test
  public void testResolveInvoiceKeysMapsInvoiceIdToKey() {
    Invoice inv = mock(Invoice.class);
    Set<Invoice> invoices = new LinkedHashSet<>(Collections.singletonList(inv));
    TaxRate rate = mock(TaxRate.class);
    Object[] row = { "inv-1", "E", 3L };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockInvoiceKeysQuery(dalMock);
      when(query.list()).thenReturn(Collections.singletonList(row));

      Map<String, String> result = handler.resolveInvoiceKeys(
          invoices, Collections.singletonList(rate), "tr1");

      assertEquals(1, result.size());
      assertEquals("E", result.get("inv-1"));
    }
  }

  /**
   * Edge case documented on {@link Fiscal349BoxesHandler#resolveInvoiceKeys}: when a single
   * invoice groups into more than one key (e.g. lines with different tax rates), the key with
   * the most matching InvoiceTax lines wins.
   */
  @Test
  public void testResolveInvoiceKeysPicksKeyWithMoreMatchingLinesOnMultiKeyInvoice() {
    Invoice inv = mock(Invoice.class);
    Set<Invoice> invoices = new LinkedHashSet<>(Collections.singletonList(inv));
    TaxRate rate = mock(TaxRate.class);
    // Same invoice id appears twice, once per key — "I" has more matching lines than "A".
    Object[] rowA = { "inv-1", "A", 1L };
    Object[] rowI = { "inv-1", "I", 4L };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockInvoiceKeysQuery(dalMock);
      when(query.list()).thenReturn(Arrays.asList(rowA, rowI));

      Map<String, String> result = handler.resolveInvoiceKeys(
          invoices, Collections.singletonList(rate), "tr1");

      assertEquals(1, result.size());
      assertEquals("I", result.get("inv-1")); // 4 lines beats 1 line
    }
  }

  /**
   * Exact-tie case documented on {@link Fiscal349BoxesHandler#resolveInvoiceKeys}: when two
   * keys for the same invoice have an EQUAL InvoiceTax line count, the alphabetically first
   * key wins. The HQL's {@code order by i.id, trp.tributaryKey.name} guarantees rows for the
   * same invoice arrive key-ascending, so this test feeds the mocked {@code list()} in that
   * same order ("A" before "S") to faithfully simulate what the real ORDER BY produces.
   */
  @Test
  public void testResolveInvoiceKeysExactTieKeepsAlphabeticallyFirstKey() {
    Invoice inv = mock(Invoice.class);
    Set<Invoice> invoices = new LinkedHashSet<>(Collections.singletonList(inv));
    TaxRate rate = mock(TaxRate.class);
    // Same invoice id, equal line counts — "A" sorts before "S" and is returned first by
    // the HQL's order by trp.tributaryKey.name, so "A" must win the tie deterministically.
    Object[] rowA = { "inv-1", "A", 2L };
    Object[] rowS = { "inv-1", "S", 2L };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockInvoiceKeysQuery(dalMock);
      when(query.list()).thenReturn(Arrays.asList(rowA, rowS));

      Map<String, String> result = handler.resolveInvoiceKeys(
          invoices, Collections.singletonList(rate), "tr1");

      assertEquals(1, result.size());
      assertEquals("A", result.get("inv-1")); // exact tie → first-encountered (alphabetical) wins
    }
  }

  @Test
  public void testResolveInvoiceKeysMultipleInvoices() {
    Invoice inv1 = mock(Invoice.class);
    Invoice inv2 = mock(Invoice.class);
    Set<Invoice> invoices = new LinkedHashSet<>(Arrays.asList(inv1, inv2));
    TaxRate rate = mock(TaxRate.class);
    Object[] row1 = { "inv-1", "S", 2L };
    Object[] row2 = { "inv-2", "A", 1L };

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      Query<Object[]> query = mockInvoiceKeysQuery(dalMock);
      when(query.list()).thenReturn(Arrays.asList(row1, row2));

      Map<String, String> result = handler.resolveInvoiceKeys(
          invoices, Collections.singletonList(rate), "tr1");

      assertEquals(2, result.size());
      assertEquals("S", result.get("inv-1"));
      assertEquals("A", result.get("inv-2"));
    }
  }
}
