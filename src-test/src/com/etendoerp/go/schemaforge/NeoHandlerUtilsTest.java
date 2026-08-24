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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * Unit tests for {@link NeoHandlerUtils}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code extractGetDataArray} — all early-exit paths and the happy path.</li>
 *   <li>{@code collectIds} — normal, empty-id filtering, and empty-array cases.</li>
 *   <li>{@code fetchProductCodesForLines} (ETP-4941) — SKU resolution for order/invoice line
 *       GET enrichment, including the blank-value and DB-error graceful-degradation paths.</li>
 *   <li>{@code injectDefaultLocatorIfMissing} / {@code resolveDefaultLocatorForWarehouse}
 *       (ETP-4863) — direct coverage of the shared locator-defaulting helper. Until this QA
 *       pass, this logic was only exercised indirectly through 4 near-duplicate assertions in
 *       {@code GoodsReceiptLineHandlerTest}, {@code GoodsShipmentLineHandlerTest}, {@code
 *       ReturnToVendorShipmentLineHandlerTest}, and {@code ReturnMaterialReceiptLineHandlerTest}
 *       — this class now tests the actual implementation once, at the right level.</li>
 * </ul>
 */
public class NeoHandlerUtilsTest {

  private static final Logger LOG = LogManager.getLogger(NeoHandlerUtilsTest.class);

  // ── extractGetDataArray ──────────────────────────────────────────────────

  @Test
  public void testExtractGetDataArrayReturnsNullForNonGet() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsNullWhenBodyNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, null))
        .build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsNullWhenNoResponseWrapper() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, new JSONObject()))
        .build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsNullWhenDataArrayMissing() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject());
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsNullWhenDataArrayEmpty() throws Exception {
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsArrayWhenValid() throws Exception {
    JSONArray data = new JSONArray().put(new JSONObject().put("id", "rec-1"));
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", data));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();
    JSONArray result = NeoHandlerUtils.extractGetDataArray(ctx);
    assertNotNull(result);
    assertEquals(1, result.length());
    assertEquals("rec-1", result.getJSONObject(0).getString("id"));
  }

  // ── collectIds ───────────────────────────────────────────────────────────

  @Test
  public void testCollectIdsReturnsAllNonBlankIds() throws Exception {
    JSONArray arr = new JSONArray()
        .put(new JSONObject().put("id", "id1"))
        .put(new JSONObject().put("id", "id2"));
    List<String> ids = NeoHandlerUtils.collectIds(arr);
    assertEquals(2, ids.size());
    assertTrue(ids.contains("id1"));
    assertTrue(ids.contains("id2"));
  }

  @Test
  public void testCollectIdsSkipsBlankAndMissingIds() throws Exception {
    JSONArray arr = new JSONArray()
        .put(new JSONObject().put("id", ""))
        .put(new JSONObject().put("id", "valid"))
        .put(new JSONObject());
    List<String> ids = NeoHandlerUtils.collectIds(arr);
    assertEquals(1, ids.size());
    assertEquals("valid", ids.get(0));
  }

  @Test
  public void testCollectIdsReturnsEmptyListForEmptyArray() throws Exception {
    List<String> ids = NeoHandlerUtils.collectIds(new JSONArray());
    assertTrue(ids.isEmpty());
  }

  // ── fetchProductCodesForLines (ETP-4941) ──────────────────────────────────
  // Shared by OrderLineHandler (c_orderline) and InvoiceLineHandler (c_invoiceline) to inject
  // productCode (M_Product.Value) into GET line responses, mirroring the query
  // AbstractInOutLineHandler already runs for M_InOutLine lines.

  @Test
  public void testFetchProductCodesForLinesReturnsEmptyMapForNullIds() {
    Map<String, String> result =
        NeoHandlerUtils.fetchProductCodesForLines(null, "c_orderline", "c_orderline_id", LOG);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testFetchProductCodesForLinesReturnsEmptyMapForEmptyIds() {
    Map<String, String> result = NeoHandlerUtils.fetchProductCodesForLines(
        Collections.emptyList(), "c_orderline", "c_orderline_id", LOG);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testFetchProductCodesForLinesReturnsSkuPerLineId() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, true, false);
      when(rs.getString(1)).thenReturn("line-1", "line-2");
      when(rs.getString(2)).thenReturn("SKU-001", "SKU-002");

      Map<String, String> result = NeoHandlerUtils.fetchProductCodesForLines(
          List.of("line-1", "line-2"), "c_orderline", "c_orderline_id", LOG);

      assertEquals(2, result.size());
      assertEquals("SKU-001", result.get("line-1"));
      assertEquals("SKU-002", result.get("line-2"));
    }
  }

  @Test
  public void testFetchProductCodesForLinesSkipsBlankValue() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // Product with no SKU (blank Value) must not appear in the result map — the caller's
      // resolveProductCode() fallback then applies (falls to "—", never the line number).
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-no-sku");
      when(rs.getString(2)).thenReturn("");

      Map<String, String> result = NeoHandlerUtils.fetchProductCodesForLines(
          List.of("line-no-sku"), "c_orderline", "c_orderline_id", LOG);

      assertTrue(result.isEmpty());
    }
  }

  /**
   * QA edge case (ETP-4941): when one of the requested line ids does not resolve to a row at
   * all (invalid id, deleted line, or a line whose product was removed) — as opposed to
   * resolving to a row with a blank SKU, covered above — that id must simply be absent from
   * the result map rather than appearing with a null/placeholder value. The caller
   * ({@code enrichProductCode}) relies on {@code Map#get} returning {@code null} for it, which
   * then correctly skips writing {@code productCode} for that line.
   */
  @Test
  public void testFetchProductCodesForLinesOmitsIdsWithNoMatchingRow() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // Two ids requested, but only "line-1" produces a joinable row — "line-missing" (bad id /
      // deleted line / product removed) simply never appears in the ResultSet.
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-1");
      when(rs.getString(2)).thenReturn("SKU-001");

      Map<String, String> result = NeoHandlerUtils.fetchProductCodesForLines(
          List.of("line-1", "line-missing"), "c_orderline", "c_orderline_id", LOG);

      assertEquals(1, result.size());
      assertEquals("SKU-001", result.get("line-1"));
      assertFalse(result.containsKey("line-missing"));
      assertNull(result.get("line-missing"));
    }
  }

  @Test
  public void testFetchProductCodesForLinesReturnsEmptyMapOnDbError() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB unavailable"));

      Map<String, String> result = NeoHandlerUtils.fetchProductCodesForLines(
          List.of("line-1"), "c_invoiceline", "c_invoiceline_id", LOG);

      assertTrue(result.isEmpty());
    }
  }

  @Test
  public void testFetchProductCodesForLinesBuildsSqlAgainstGivenTableAndColumn() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      NeoHandlerUtils.fetchProductCodesForLines(
          List.of("inv-line-1"), "c_invoiceline", "c_invoiceline_id", LOG);

      org.mockito.ArgumentCaptor<String> sqlCaptor =
          org.mockito.ArgumentCaptor.forClass(String.class);
      Mockito.verify(conn).prepareStatement(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue("SQL must query the given line table", sql.contains("c_invoiceline"));
      assertTrue("SQL must filter by the given PK column", sql.contains("c_invoiceline_id"));
      assertTrue("SQL must join m_product", sql.contains("m_product"));
    }
  }

  // ── extractCreatedIdFromPreviousResult (ETP-4029) ────────────────────────

  /**
   * Correct/real shape produced by {@code DefaultJsonDataService.add()}: {@code response.data}
   * is a {@code JSONArray} with a single element — the created record.
   */
  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsIdFromOneElementArray() throws Exception {
    JSONArray data = new JSONArray().put(new JSONObject().put("id", "created-1"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();

    assertEquals("created-1", NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullForEmptyArray() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();

    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = NeoContext.builder().build();
    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenBodyNull() {
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, null))
        .build();
    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenNoResponseWrapper() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, new JSONObject()))
        .build();
    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenDataMissing() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject());
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();
    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  /**
   * Malformed shape: {@code data} present but as a plain {@code JSONObject}, not an array
   * (the OLD, wrong assumption some earlier code relied on). {@code optJSONArray} silently
   * returns {@code null} for a non-array value — the method must fail closed (return null),
   * never throw.
   */
  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenDataIsPlainObjectNotArray()
      throws Exception {
    JSONObject dataAsObject = new JSONObject().put("id", "should-not-be-read");
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", dataAsObject));
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();

    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenFirstElementHasNoId()
      throws Exception {
    JSONArray data = new JSONArray().put(new JSONObject().put("someOtherField", "x"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();

    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsFirstElementIdWhenMultipleElements()
      throws Exception {
    JSONArray data = new JSONArray()
        .put(new JSONObject().put("id", "first-id"))
        .put(new JSONObject().put("id", "second-id"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();

    assertEquals("first-id", NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  // ── ETP-4531: mirrorFieldValue ────────────────────────────────────────────

  @Test
  public void testMirrorFieldValueCopiesSourceIntoTarget() throws Exception {
    JSONObject body = new JSONObject().put("invoiceDate", "2026-07-01");

    NeoHandlerUtils.mirrorFieldValue(body, "invoiceDate", "accountingDate");

    assertEquals("2026-07-01", body.getString("accountingDate"));
  }

  @Test
  public void testMirrorFieldValueOverwritesExistingTargetValue() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoiceDate", "2026-07-10").put("accountingDate", "2026-01-01");

    NeoHandlerUtils.mirrorFieldValue(body, "invoiceDate", "accountingDate");

    assertEquals("2026-07-10", body.getString("accountingDate"));
  }

  @Test
  public void testMirrorFieldValueNoopWhenSourceFieldAbsent() throws Exception {
    JSONObject body = new JSONObject().put("otherField", "x");

    NeoHandlerUtils.mirrorFieldValue(body, "invoiceDate", "accountingDate");

    assertTrue(!body.has("accountingDate"));
  }

  @Test
  public void testMirrorFieldValueNullBodyDoesNotThrow() {
    // Must be a no-op guard: null body is a valid request-body shape to defend against.
    NeoHandlerUtils.mirrorFieldValue(null, "invoiceDate", "accountingDate");
  }

  // ── ETP-4531: isWriteMethod ──────────────────────────────────────────────
  // Regression coverage for the live bug: mirrorAccountingDate() call sites originally
  // hardcoded POST/PUT only, missing PATCH — the exact method the real React UI
  // (useEntity.js#getMethod) sends for every edit-and-save of an EXISTING record.

  @Test
  public void testIsWriteMethodTrueForPost() {
    assertTrue(NeoHandlerUtils.isWriteMethod("POST"));
  }

  @Test
  public void testIsWriteMethodTrueForPut() {
    assertTrue(NeoHandlerUtils.isWriteMethod("PUT"));
  }

  @Test
  public void testIsWriteMethodTrueForPatch() {
    // This is the case the original inline POST/PUT checks in every header handler's
    // mirrorAccountingDate() missed, causing accountingDate to never mirror on a real
    // edit of an existing invoice/order/receipt/shipment.
    assertTrue(NeoHandlerUtils.isWriteMethod("PATCH"));
  }

  @Test
  public void testIsWriteMethodFalseForGet() {
    assertTrue(!NeoHandlerUtils.isWriteMethod("GET"));
  }

  @Test
  public void testIsWriteMethodFalseForDelete() {
    assertTrue(!NeoHandlerUtils.isWriteMethod("DELETE"));
  }

  @Test
  public void testIsWriteMethodFalseForNull() {
    assertTrue(!NeoHandlerUtils.isWriteMethod(null));
  }

  // ── ETP-4863: injectDefaultLocatorIfMissing / resolveDefaultLocatorForWarehouse ──────────
  // Direct unit coverage of the shared locator-defaulting helper (QA edge cases). The 4 line
  // handlers each assert the happy path and the "explicit value present" guard through their
  // own handle() wrapper; the cases below target the helper itself, including branches none
  // of those 4 wrapper tests exercise.

  @Test
  public void testInjectDefaultLocatorIfMissingNullBodyIsNoOp() throws Exception {
    // Must not throw for a null body — same defensive contract as mirrorFieldValue.
    NeoHandlerUtils.injectDefaultLocatorIfMissing(null, LOG);
  }

  @Test
  public void testInjectDefaultLocatorIfMissingBlankParentIdDoesNotInjectLocator()
      throws Exception {
    JSONObject body = new JSONObject().put("parentId", "").put("product", "prod-1");

    NeoHandlerUtils.injectDefaultLocatorIfMissing(body, LOG);

    assertFalse(body.has("storageBin"));
  }

  @Test
  public void testInjectDefaultLocatorIfMissingHeaderNotFoundDoesNotInjectLocator()
      throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-missing");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-missing"))).thenReturn(null);

      NeoHandlerUtils.injectDefaultLocatorIfMissing(body, LOG);

      assertFalse(body.has("storageBin"));
    }
  }

  @Test
  public void testInjectDefaultLocatorIfMissingHeaderWithNullWarehouseDoesNotInjectLocator()
      throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(null);

      NeoHandlerUtils.injectDefaultLocatorIfMissing(body, LOG);

      assertFalse(body.has("storageBin"));
    }
  }

  /**
   * Edge case (QA): the header warehouse has NO active default {@code M_Locator} configured at
   * all (e.g. a brand-new warehouse whose locators were never flagged as default/active). The
   * helper must resolve to {@code null} and leave {@code storageBin} unset rather than throwing
   * — completion will still fail downstream with {@code InoutLineWithoutLocator}, but this
   * method itself must degrade gracefully, not blow up the request.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testInjectDefaultLocatorIfMissingNoLocatorConfiguredForWarehouseDoesNotThrow()
      throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-empty");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      NeoHandlerUtils.injectDefaultLocatorIfMissing(body, LOG);

      assertFalse(body.has("storageBin"));
    }
  }

  @Test
  public void testInjectDefaultLocatorIfMissingOverridesUnresolvedToken() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1")
        .put("storageBin", "@OnHandLocatorDefault@");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-principal");
      when(locator.getId()).thenReturn("loc-default-wh-principal");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      NeoHandlerUtils.injectDefaultLocatorIfMissing(body, LOG);

      assertEquals("loc-default-wh-principal", body.getString("storageBin"));
    }
  }

  @Test
  public void testInjectDefaultLocatorIfMissingHappyPathInjectsHeaderWarehouseLocator()
      throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1").put("product", "prod-1");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-principal");
      when(locator.getId()).thenReturn("loc-default-wh-principal");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      NeoHandlerUtils.injectDefaultLocatorIfMissing(body, LOG);

      assertEquals("loc-default-wh-principal", body.getString("storageBin"));
    }
  }

  /**
   * BUG-1 [HIGH] fix (ETP-4863 QA report — confirmed IN SCOPE by the product owner, not a
   * best-effort fallback): the ticket's "Comportamiento esperado" is an UNCONDITIONAL guarantee
   * — "todas las transacciones deben quedar en el almacén [de cabecera]", with no exception for
   * an already-populated field. An explicit, REAL {@code storageBin} that belongs to a
   * DIFFERENT warehouse than the header's must be corrected to the header warehouse's own
   * default locator, exactly like the "missing" case — not passed through unchecked. This
   * replaces the test that used to PIN the deficient behavior
   * ({@code testInjectDefaultLocatorIfMissingDoesNotValidateExplicitStorageBinWarehouse}, which
   * asserted {@code verifyNoInteractions(dal)} — the header/warehouse lookup being skipped
   * entirely for an explicit value was exactly the bug).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testInjectDefaultLocatorIfMissingCorrectsExplicitStorageBinFromWrongWarehouse()
      throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1")
        .put("storageBin", "loc-from-secondary-warehouse");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse headerWarehouse = mock(Warehouse.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(headerWarehouse);
      when(headerWarehouse.getId()).thenReturn("wh-principal");

      Locator existingLocator = mock(Locator.class);
      Warehouse secondaryWarehouse = mock(Warehouse.class);
      when(dal.get(eq(Locator.class), eq("loc-from-secondary-warehouse")))
          .thenReturn(existingLocator);
      when(existingLocator.getWarehouse()).thenReturn(secondaryWarehouse);
      when(secondaryWarehouse.getId()).thenReturn("wh-secondary");

      Locator defaultLocator = mock(Locator.class);
      when(defaultLocator.getId()).thenReturn("loc-default-wh-principal");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(defaultLocator);

      NeoHandlerUtils.injectDefaultLocatorIfMissing(body, LOG);

      assertEquals("An explicit storageBin belonging to a DIFFERENT warehouse than the "
          + "header's must be corrected to the header warehouse's own default locator — this "
          + "is an unconditional guarantee, not a fill-if-absent default (ETP-4863 BUG-1)",
          "loc-default-wh-principal", body.getString("storageBin"));
    }
  }

  /**
   * Companion to the BUG-1 fix: an explicit {@code storageBin} that already belongs to the
   * header's own warehouse must be LEFT ALONE — the unconditional guarantee is about the
   * warehouse, not about forcing every line onto the warehouse's single "default" locator.
   * This is why {@link InventoryLineHandler}'s "always overwrite" pattern was deliberately NOT
   * replicated here: it would silently discard a deliberate, valid picking-bin choice whenever
   * the user (or an "Import from..." flow) already picked a specific bin inside the correct
   * warehouse.
   */
  @Test
  public void testInjectDefaultLocatorIfMissingKeepsExplicitStorageBinAlreadyInHeaderWarehouse()
      throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1")
        .put("storageBin", "loc-specific-bin-in-principal");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse headerWarehouse = mock(Warehouse.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(headerWarehouse);
      when(headerWarehouse.getId()).thenReturn("wh-principal");

      Locator existingLocator = mock(Locator.class);
      when(dal.get(eq(Locator.class), eq("loc-specific-bin-in-principal")))
          .thenReturn(existingLocator);
      when(existingLocator.getWarehouse()).thenReturn(headerWarehouse);

      NeoHandlerUtils.injectDefaultLocatorIfMissing(body, LOG);

      assertEquals("loc-specific-bin-in-principal", body.getString("storageBin"));
      // No default-locator resolution should even be attempted once the match is confirmed.
      Mockito.verify(dal, Mockito.never()).createCriteria(Locator.class);
    }
  }

  /**
   * Edge case of the BUG-1 fix: an explicit {@code storageBin} that does not resolve to any
   * real {@code M_Locator} at all (bad id, deleted record) must be treated the same as a
   * missing/invalid value and corrected to the header warehouse's default locator — per the
   * fix spec: "o el locator no existe/no es válido".
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testInjectDefaultLocatorIfMissingCorrectsExplicitStorageBinThatDoesNotExist()
      throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1")
        .put("storageBin", "loc-does-not-exist");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse headerWarehouse = mock(Warehouse.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(headerWarehouse);
      when(headerWarehouse.getId()).thenReturn("wh-principal");
      when(dal.get(eq(Locator.class), eq("loc-does-not-exist"))).thenReturn(null);

      Locator defaultLocator = mock(Locator.class);
      when(defaultLocator.getId()).thenReturn("loc-default-wh-principal");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(defaultLocator);

      NeoHandlerUtils.injectDefaultLocatorIfMissing(body, LOG);

      assertEquals("loc-default-wh-principal", body.getString("storageBin"));
    }
  }

  // ── ETP-4863: resolveDefaultLocatorForWarehouse ──────────────────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void testResolveDefaultLocatorForWarehouseReturnsIdWhenFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Locator locator = mock(Locator.class);
      when(locator.getId()).thenReturn("loc-1");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      assertEquals("loc-1", NeoHandlerUtils.resolveDefaultLocatorForWarehouse("wh-1", LOG));
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testResolveDefaultLocatorForWarehouseReturnsNullWhenNoneFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      assertNull(NeoHandlerUtils.resolveDefaultLocatorForWarehouse("wh-1", LOG));
    }
  }

  /**
   * Edge case (QA): if the criteria query itself blows up (e.g. transient DB issue), the method
   * must fail closed — return {@code null} — instead of propagating the exception up into the
   * POST create flow and turning a defaulting nicety into a hard 500.
   */
  @Test
  public void testResolveDefaultLocatorForWarehouseReturnsNullOnException() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.createCriteria(Locator.class)).thenThrow(new RuntimeException("DB unavailable"));

      assertNull(NeoHandlerUtils.resolveDefaultLocatorForWarehouse("wh-1", LOG));
    }
  }

  // ── ETP-4863: anchorLocatorToWarehouse — the 4-step cascade ─────────────────────────────────
  //
  // The business rule is unconditional: a line's bin must ALWAYS belong to the header document's
  // warehouse. This method is the single definition of that rule for every DAL write path, so
  // each step of the cascade and each null-guard is asserted directly here rather than only
  // through a call site.

  /** Step 1 — a candidate already in the header warehouse is returned as-is, with NO query. */
  @Test
  public void testAnchorLocatorKeepsCandidateAlreadyInHeaderWarehouse() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_PRINCIPAL);
      Locator candidate = LocatorTestSupport.mockLocator("loc-principal-A", headerWarehouse);

      assertSame(candidate,
          NeoHandlerUtils.anchorLocatorToWarehouse(candidate, headerWarehouse, LOG));
      Mockito.verify(dal, Mockito.never()).createCriteria(Locator.class);
    }
  }

  /** Step 2 — a candidate from another warehouse is replaced by the default-flagged bin. */
  @Test
  public void testAnchorLocatorReplacesForeignCandidateWithDefaultBin() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_PRINCIPAL);
      Locator foreign = LocatorTestSupport.mockLocator("loc-secondary-A",
          LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_SECONDARY));
      Locator defaultBin = LocatorTestSupport.mockLocator(
          LocatorTestSupport.LOC_PRINCIPAL_DEFAULT, headerWarehouse);
      LocatorTestSupport.stubDefaultLocatorLookup(dal, defaultBin);

      assertSame(defaultBin,
          NeoHandlerUtils.anchorLocatorToWarehouse(foreign, headerWarehouse, LOG));
    }
  }

  /** Step 2 — an absent candidate is filled with the default-flagged bin just the same. */
  @Test
  public void testAnchorLocatorFillsNullCandidateWithDefaultBin() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_PRINCIPAL);
      Locator defaultBin = LocatorTestSupport.mockLocator(
          LocatorTestSupport.LOC_PRINCIPAL_DEFAULT, headerWarehouse);
      LocatorTestSupport.stubDefaultLocatorLookup(dal, defaultBin);

      assertSame(defaultBin, NeoHandlerUtils.anchorLocatorToWarehouse(null, headerWarehouse, LOG));
    }
  }

  /**
   * Step 3 — the header warehouse has bins but NONE flagged default. The foreign candidate must
   * still be replaced, now by the lowest-searchKey active bin. This is the branch that closes the
   * "configuration gap silently keeps a wrong-warehouse bin" hole.
   */
  @Test
  public void testAnchorLocatorFallsBackToAnyActiveBinWhenNoDefaultFlagged() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_PRINCIPAL);
      Locator foreign = LocatorTestSupport.mockLocator("loc-secondary-A",
          LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_SECONDARY));
      Locator anyActive = LocatorTestSupport.mockLocator("loc-principal-any", headerWarehouse);
      LocatorTestSupport.stubLocatorCascade(dal, anyActive);

      Locator result = NeoHandlerUtils.anchorLocatorToWarehouse(foreign, headerWarehouse, LOG);

      assertSame(anyActive, result);
      assertNotSame("a bin from another warehouse must never survive", foreign, result);
    }
  }

  /**
   * Step 4 — the header warehouse has no active bin at all. The result is {@code null}: the
   * foreign candidate is NOT handed back, so no caller can persist a wrong-warehouse bin.
   */
  @Test
  public void testAnchorLocatorReturnsNullRatherThanForeignBinWhenWarehouseHasNoLocator() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_PRINCIPAL);
      Locator foreign = LocatorTestSupport.mockLocator("loc-secondary-A",
          LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_SECONDARY));
      LocatorTestSupport.stubLocatorCascade(dal, null);

      assertNull(NeoHandlerUtils.anchorLocatorToWarehouse(foreign, headerWarehouse, LOG));
    }
  }

  /** Null guard — no header warehouse means nothing to anchor to; the candidate passes through. */
  @Test
  public void testAnchorLocatorReturnsCandidateWhenHeaderWarehouseIsNull() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Locator candidate = LocatorTestSupport.mockLocator("loc-x",
          LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_SECONDARY));

      assertSame(candidate, NeoHandlerUtils.anchorLocatorToWarehouse(candidate, null, LOG));
      assertNull(NeoHandlerUtils.anchorLocatorToWarehouse(null, null, LOG));
      Mockito.verify(dal, Mockito.never()).createCriteria(Locator.class);
    }
  }

  /** Null guard — a header warehouse whose id is null is as unusable as a null warehouse. */
  @Test
  public void testAnchorLocatorReturnsCandidateWhenHeaderWarehouseIdIsNull() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Warehouse headerWarehouse = mock(Warehouse.class);
      when(headerWarehouse.getId()).thenReturn(null);
      Locator candidate = LocatorTestSupport.mockLocator("loc-x",
          LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_SECONDARY));

      assertSame(candidate,
          NeoHandlerUtils.anchorLocatorToWarehouse(candidate, headerWarehouse, LOG));
      Mockito.verify(dal, Mockito.never()).createCriteria(Locator.class);
    }
  }

  /**
   * Null guard — a candidate with no warehouse of its own cannot satisfy step 1, so it is treated
   * as unanchored and corrected instead of blowing up on a null dereference.
   */
  @Test
  public void testAnchorLocatorCorrectsCandidateWithNullWarehouse() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_PRINCIPAL);
      Locator orphan = mock(Locator.class);
      when(orphan.getId()).thenReturn("loc-orphan");
      when(orphan.getWarehouse()).thenReturn(null);
      Locator defaultBin = LocatorTestSupport.mockLocator(
          LocatorTestSupport.LOC_PRINCIPAL_DEFAULT, headerWarehouse);
      LocatorTestSupport.stubDefaultLocatorLookup(dal, defaultBin);

      assertSame(defaultBin,
          NeoHandlerUtils.anchorLocatorToWarehouse(orphan, headerWarehouse, LOG));
    }
  }

  /**
   * {@code locatorBelongsToWarehouse} is the step-1 predicate batch callers reuse to skip the
   * per-line lookup; it must agree with the cascade on every null combination.
   */
  @Test
  public void testLocatorBelongsToWarehouseIsNullSafe() {
    Warehouse warehouse = LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_PRINCIPAL);
    Locator inWarehouse = LocatorTestSupport.mockLocator("loc-a", warehouse);
    Locator elsewhere = LocatorTestSupport.mockLocator("loc-b",
        LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_SECONDARY));
    Locator orphan = mock(Locator.class);
    when(orphan.getWarehouse()).thenReturn(null);

    assertTrue(NeoHandlerUtils.locatorBelongsToWarehouse(inWarehouse, warehouse));
    assertFalse(NeoHandlerUtils.locatorBelongsToWarehouse(elsewhere, warehouse));
    assertFalse(NeoHandlerUtils.locatorBelongsToWarehouse(orphan, warehouse));
    assertFalse(NeoHandlerUtils.locatorBelongsToWarehouse(null, warehouse));
    assertFalse(NeoHandlerUtils.locatorBelongsToWarehouse(inWarehouse, null));
  }

  /**
   * {@code resolveWarehouseAnchorBin} (steps 2–4, hoisted by batch callers) must return exactly
   * what the full cascade would, so memoizing it stays equivalent to calling
   * {@code anchorLocatorToWarehouse} per line.
   */
  @Test
  public void testResolveWarehouseAnchorBinMatchesCascadeAndIsNullSafe() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse warehouse = LocatorTestSupport.mockWarehouse(LocatorTestSupport.WH_PRINCIPAL);
      Locator anyActive = LocatorTestSupport.mockLocator("loc-principal-any", warehouse);
      LocatorTestSupport.stubLocatorCascade(dal, anyActive);

      assertSame(anyActive, NeoHandlerUtils.resolveWarehouseAnchorBin(warehouse, LOG));
      assertNull(NeoHandlerUtils.resolveWarehouseAnchorBin(null, LOG));
    }
  }

  // ── ETP-4863: reanchorLinesToHeaderWarehouse ──────────────────────────────
  //
  // Extracted from the identical "fillMissingStorageBins" wrapper that ReturnMaterialReceipt
  // and ReturnToVendorShipment header handlers each used to carry as their own private copy
  // (admin-mode try/finally around ReturnShipmentUtils.assignBinsToLines + swallow-and-warn),
  // so a 3rd/4th copy for Goods Shipment / Goods Receipt wouldn't trip SonarQube CPD.

  @Test
  public void testReanchorLinesToHeaderWarehouseNoOpForNullId() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      NeoHandlerUtils.reanchorLinesToHeaderWarehouse(null, LOG);
      obDalMock.verifyNoInteractions();
    }
  }

  @Test
  public void testReanchorLinesToHeaderWarehouseDelegatesToAssignBinsToLines() {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<ReturnShipmentUtils> utilsMock = Mockito.mockStatic(ReturnShipmentUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "inout-1")).thenReturn(doc);

      NeoHandlerUtils.reanchorLinesToHeaderWarehouse("inout-1", LOG);

      utilsMock.verify(() -> ReturnShipmentUtils.assignBinsToLines(doc));
      obContextMock.verify(() -> OBContext.setAdminMode(true));
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  @Test
  public void testReanchorLinesToHeaderWarehouseSkipsAssignWhenDocNotFound() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<ReturnShipmentUtils> utilsMock = Mockito.mockStatic(ReturnShipmentUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ShipmentInOut.class, "missing")).thenReturn(null);

      NeoHandlerUtils.reanchorLinesToHeaderWarehouse("missing", LOG);

      utilsMock.verifyNoInteractions();
    }
  }

  @Test
  public void testReanchorLinesToHeaderWarehouseSwallowsException() {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenThrow(new RuntimeException("db down"));

      // Must not propagate — a re-anchor failure should never block the documentAction request.
      NeoHandlerUtils.reanchorLinesToHeaderWarehouse("inout-1", LOG);
    }
  }
}
