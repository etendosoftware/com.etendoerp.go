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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

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

  private final Fiscal303BoxesHandler handler = new Fiscal303BoxesHandler(null);

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
    assertEquals(Arrays.asList(150, 152), handler.vatGeneralBoxes(BigDecimal.ZERO));
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
    assertEquals(new BigDecimal("121.00"), row.get("total"));
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

  // ── helpers ───────────────────────────────────────────────────────────────

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
