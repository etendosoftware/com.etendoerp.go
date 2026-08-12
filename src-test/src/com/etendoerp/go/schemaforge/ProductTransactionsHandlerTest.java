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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link ProductTransactionsHandler}.
 *
 * <p>Coverage goals:
 * <ul>
 *   <li>{@code handle()} always returns null (pass-through pre-hook).</li>
 *   <li>{@code afterHandle()} returns null when {@code extractGetDataArray} returns null.</li>
 *   <li>{@code afterHandle()} returns null when {@code previousResult} is null.</li>
 *   <li>{@code afterHandle()} happy path: two rows, one resolved to a known target, one
 *       unresolved — only the resolved row gets the three enrichment fields.</li>
 *   <li>{@code afterHandle()} with a null {@code docLabel} in the SQL result — header id
 *       and window key are set but {@code etgoDocLabel} is absent.</li>
 *   <li>{@code afterHandle()} swallows any exception thrown inside {@code resolveDocumentTargets}
 *       and returns {@code context.getPreviousResult()} unchanged.</li>
 * </ul>
 *
 * <p>Tests that require a live Etendo DB are covered by integration tests.
 */
class ProductTransactionsHandlerTest {

  private static final ProductTransactionsHandler HANDLER = new ProductTransactionsHandler();

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Returns a GET/CRUD context targeting the productTransactions entity.
   */
  private static NeoContext getCtx() {
    return NeoContext.builder().specName("warehouse").entityName("productTransactions").httpMethod("GET").endpointType(
        NeoEndpointType.CRUD).build();
  }

  /**
   * Builds a standard response body wrapping the given rows inside
   * {@code { "response": { "data": [...] } }}.
   */
  private static JSONObject wrapData(JSONArray data) throws Exception {
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  /**
   * Creates a minimal transaction row JSON with the given id.
   */
  private static JSONObject txRow(String id) throws Exception {
    return new JSONObject().put("id", id).put("movementType", "V+");
  }

  /**
   * Mocks the Hibernate {@link NativeQuery} on the given {@link Session} to return
   * the provided list of {@code Object[]} rows when {@code list()} is called.
   */
  @SuppressWarnings("unchecked")
  private static NativeQuery<Object[]> mockNativeQuery(Session session, List<Object[]> rows) {
    NativeQuery<Object[]> query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameterList(eq("ids"), anyList())).thenReturn(query);
    when(query.list()).thenReturn(rows);
    return query;
  }

  // ---------------------------------------------------------------------------
  // handle() — always null
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code handle()} returns null for a GET context,
   * delegating to default CRUD processing.
   */
  @Test
  void handle_alwaysReturnsNull_forGetContext() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * Verifies that {@code handle()} returns null even for POST/ACTION contexts.
   */
  @Test
  void handle_alwaysReturnsNull_forPostContext() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(HANDLER.handle(ctx));
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — guard: extractGetDataArray returns null
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code afterHandle()} returns null when the context has no
   * previous result set (which makes {@code extractGetDataArray} return null).
   */
  @Test
  void afterHandle_returnsNull_whenNoPreviousResult() {
    NeoContext ctx = getCtx();
    // no previousResult → extractGetDataArray returns null
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that {@code afterHandle()} returns null when the previous result
   * carries a null body (extractGetDataArray guard).
   */
  @Test
  void afterHandle_returnsNull_whenBodyIsNull() {
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that {@code afterHandle()} returns null when the data array inside
   * the response is empty (extractGetDataArray returns null for empty arrays).
   */
  @Test
  void afterHandle_returnsNull_whenDataArrayIsEmpty() throws Exception {
    JSONObject body = wrapData(new JSONArray());
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that {@code afterHandle()} returns null when the HTTP method is not GET,
   * because {@code extractGetDataArray} only processes GET requests.
   */
  @Test
  void afterHandle_returnsNull_forNonGetMethod() throws Exception {
    JSONArray data = new JSONArray().put(txRow("TX-001"));
    JSONObject body = wrapData(data);
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(HANDLER.afterHandle(ctx));
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — guard: previousResult is null after extractGetDataArray
  // ---------------------------------------------------------------------------

  /**
   * Verifies the explicit null-previousResult guard inside afterHandle().
   *
   * <p>We mock {@code NeoHandlerUtils.extractGetDataArray} to return a non-null
   * array while leaving {@code context.getPreviousResult()} as null, hitting the
   * second guard branch ({@code if (dataArr == null || previousResult == null)}).
   */
  @Test
  void afterHandle_returnsNull_whenExtractReturnsArrayButPreviousResultIsNull() throws Exception {
    JSONArray fakeData = new JSONArray().put(txRow("TX-001"));

    try (MockedStatic<NeoHandlerUtils> utils = Mockito.mockStatic(NeoHandlerUtils.class)) {
      utils.when(() -> NeoHandlerUtils.extractGetDataArray(Mockito.any())).thenReturn(fakeData);
      // collectIds is not reached; previousResult stays null
      NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
      // no setPreviousResult → getPreviousResult() == null

      assertNull(HANDLER.afterHandle(ctx));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — happy path: two rows, one resolved
  // ---------------------------------------------------------------------------

  /**
   * Happy-path test: data array with two rows.
   * The first row is resolved by the SQL query (goods shipment, with a doc number).
   * The second row has no entry in the result map (production transaction, no window).
   * Asserts that only the first row receives the three enrichment fields.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_happyPath_enrichesResolvedRowAndLeavesUnresolvedIntact() throws Exception {
    JSONArray data = new JSONArray().put(txRow("TX-001")).put(txRow("TX-002"));
    JSONObject body = wrapData(data);

    // SQL returns one row: TX-001 → goods-shipment, header "HDR-999", doc "1000026"
    Object[] sqlRow = new Object[]{ "TX-001", "HDR-999", "goods-shipment", "1000026" };

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      mockNativeQuery(session, Collections.singletonList(sqlRow));

      NeoContext ctx = getCtx();
      NeoResponse previousResult = NeoResponse.ok(body);
      ctx.setPreviousResult(previousResult);

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());

      JSONArray resultData = result.getBody().getJSONObject("response").getJSONArray("data");

      // Row 0 (TX-001) — must have all three enrichment fields
      JSONObject row0 = resultData.getJSONObject(0);
      assertEquals("HDR-999", row0.getString("etgoDocHeaderId"), "TX-001 must have etgoDocHeaderId set");
      assertEquals("goods-shipment", row0.getString("etgoDocWindow"), "TX-001 must have etgoDocWindow set");
      assertEquals("1000026", row0.getString("etgoDocLabel"), "TX-001 must have etgoDocLabel set");

      // Row 1 (TX-002) — no SQL result → must NOT have enrichment fields
      JSONObject row1 = resultData.getJSONObject(1);
      assertFalse(row1.has("etgoDocHeaderId"), "TX-002 must NOT have etgoDocHeaderId");
      assertFalse(row1.has("etgoDocWindow"), "TX-002 must NOT have etgoDocWindow");
      assertFalse(row1.has("etgoDocLabel"), "TX-002 must NOT have etgoDocLabel");
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — null docLabel: headerId + windowKey set, etgoDocLabel absent
  // ---------------------------------------------------------------------------

  /**
   * When the SQL result row carries a null {@code docLabel} (column 3), the handler
   * must still set {@code etgoDocHeaderId} and {@code etgoDocWindow} but must NOT
   * add the key {@code etgoDocLabel} to the JSON object.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_nullDocLabel_setsHeaderAndWindowButOmitsLabel() throws Exception {
    JSONArray data = new JSONArray().put(txRow("TX-003"));
    JSONObject body = wrapData(data);

    // SQL returns: headerId and windowKey are non-null, but docLabel is null
    Object[] sqlRow = new Object[]{ "TX-003", "HDR-888", "goods-receipt", null };

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      mockNativeQuery(session, Collections.singletonList(sqlRow));

      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);

      assertTrue(row.has("etgoDocHeaderId"), "etgoDocHeaderId must be present");
      assertEquals("HDR-888", row.getString("etgoDocHeaderId"));
      assertTrue(row.has("etgoDocWindow"), "etgoDocWindow must be present");
      assertEquals("goods-receipt", row.getString("etgoDocWindow"));
      assertFalse(row.has("etgoDocLabel"), "etgoDocLabel must be absent when docLabel is null");
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — return-material-receipt and return-to-vendor-shipment
  // (discriminated by C_DocType.IsReturn, NOT M_InOut.MovementType — the same
  // MovementType is shared by a normal document and its return)
  // ---------------------------------------------------------------------------

  /**
   * A sales return (M_InOut with IsSOTrx {@code Y} and its {@code C_DocType.IsReturn}
   * flag set to {@code Y}) must resolve to the {@code return-material-receipt}
   * window key, not {@code goods-shipment}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_salesReturn_resolvesToReturnMaterialReceipt() throws Exception {
    JSONArray data = new JSONArray().put(txRow("TX-RET-SALES"));
    JSONObject body = wrapData(data);

    Object[] sqlRow = new Object[]{ "TX-RET-SALES", "HDR-RMR-1", "return-material-receipt", "RM-0001" };

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      mockNativeQuery(session, Collections.singletonList(sqlRow));

      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("HDR-RMR-1", row.getString("etgoDocHeaderId"));
      assertEquals("return-material-receipt", row.getString("etgoDocWindow"));
      assertEquals("RM-0001", row.getString("etgoDocLabel"));
    }
  }

  /**
   * A purchase return (M_InOut with IsSOTrx {@code N} and its {@code C_DocType.IsReturn}
   * flag set to {@code Y}) must resolve to the {@code return-to-vendor-shipment}
   * window key, not {@code goods-receipt}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_purchaseReturn_resolvesToReturnToVendorShipment() throws Exception {
    JSONArray data = new JSONArray().put(txRow("TX-RET-PURCH"));
    JSONObject body = wrapData(data);

    Object[] sqlRow = new Object[]{ "TX-RET-PURCH", "HDR-RVS-1", "return-to-vendor-shipment", "RV-0001" };

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      mockNativeQuery(session, Collections.singletonList(sqlRow));

      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("HDR-RVS-1", row.getString("etgoDocHeaderId"));
      assertEquals("return-to-vendor-shipment", row.getString("etgoDocWindow"));
      assertEquals("RV-0001", row.getString("etgoDocLabel"));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — SQL row with null headerId or null windowKey: row is skipped
  // ---------------------------------------------------------------------------

  /**
   * When the SQL result has a null headerId (column 1), the handler must not inject
   * any enrichment fields — the target resolution guard
   * {@code if (headerId != null && windowKey != null)} must drop the row.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_nullHeaderId_rowIsNotEnriched() throws Exception {
    JSONArray data = new JSONArray().put(txRow("TX-004"));
    JSONObject body = wrapData(data);

    // SQL returns: headerId null → target is invalid, must be skipped
    Object[] sqlRow = new Object[]{ "TX-004", null, "goods-shipment", "1000030" };

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      mockNativeQuery(session, Collections.singletonList(sqlRow));

      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse(row.has("etgoDocHeaderId"), "Row with null headerId must not be enriched");
      assertFalse(row.has("etgoDocWindow"));
    }
  }

  /**
   * When the SQL result has a non-null headerId but a null windowKey (column 2) — the
   * scenario produced by the {@code LEFT JOIN c_doctype} when a document's doctype row
   * is missing or its {@code isreturn} flag can't be evaluated by the CASE — the handler
   * must not inject any enrichment fields either. {@code resolveDocumentTargets} only adds
   * an entry when both headerId AND windowKey are non-null (ETP-4864 gap: this branch was
   * not previously exercised even though {@code afterHandle_nullHeaderId_rowIsNotEnriched}
   * covered the mirror case).
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_nullWindowKey_rowIsNotEnriched() throws Exception {
    JSONArray data = new JSONArray().put(txRow("TX-005"));
    JSONObject body = wrapData(data);

    // SQL returns: headerId present, windowKey null → target is invalid, must be skipped
    Object[] sqlRow = new Object[]{ "TX-005", "HDR-777", null, "1000031" };

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      mockNativeQuery(session, Collections.singletonList(sqlRow));

      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse(row.has("etgoDocHeaderId"), "Row with null windowKey must not be enriched");
      assertFalse(row.has("etgoDocWindow"));
      assertFalse(row.has("etgoDocLabel"));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — exception inside resolveDocumentTargets is swallowed
  // ---------------------------------------------------------------------------

  /**
   * Verifies that when {@code createNativeQuery} throws a {@link RuntimeException}
   * inside {@code resolveDocumentTargets}, {@code afterHandle} catches it, logs the
   * error, and returns {@code context.getPreviousResult()} without rethrowing.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_exceptionInResolve_returnsOriginalPreviousResult() throws Exception {
    JSONArray data = new JSONArray().put(txRow("TX-ERR"));
    JSONObject body = wrapData(data);

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);

      // createNativeQuery throws to simulate a DB failure
      when(session.createNativeQuery(anyString())).thenThrow(new RuntimeException("simulated DB failure"));

      NeoContext ctx = getCtx();
      NeoResponse original = NeoResponse.ok(body);
      ctx.setPreviousResult(original);

      NeoResponse result = assertDoesNotThrow(() -> HANDLER.afterHandle(ctx),
          "afterHandle must not rethrow any exception");

      // The handler must return the original previousResult on exception
      assertSame(original, result, "afterHandle must return context.getPreviousResult() when resolution fails");
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — all rows have no id → collectIds returns empty list → early return null
  // ---------------------------------------------------------------------------

  /**
   * When every record in the data array has no {@code id} field, {@code collectIds}
   * returns an empty list and {@code afterHandle} returns null without hitting the DB.
   */
  @Test
  void afterHandle_noIdsInRows_returnsNull() throws Exception {
    // Row with no "id" key
    JSONObject rowNoId = new JSONObject().put("movementType", "V+");
    JSONArray data = new JSONArray().put(rowNoId);
    JSONObject body = wrapData(data);

    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    // No OBDal mock needed — the handler returns null before querying the DB
    assertNull(HANDLER.afterHandle(ctx), "afterHandle must return null when no tx ids can be collected");
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — multiple rows all resolved (verifies batch coverage)
  // ---------------------------------------------------------------------------

  /**
   * Verifies that a batch of three rows are all enriched correctly when the SQL
   * returns a result for each transaction id.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_multipleRowsAllResolved_allEnriched() throws Exception {
    JSONArray data = new JSONArray().put(txRow("TX-A")).put(txRow("TX-B")).put(txRow("TX-C"));
    JSONObject body = wrapData(data);

    List<Object[]> sqlRows = Arrays.asList(new Object[]{ "TX-A", "HDR-A", "goods-shipment", "DOC-A" },
        new Object[]{ "TX-B", "HDR-B", "goods-movements", "DOC-B" },
        new Object[]{ "TX-C", "HDR-C", "physical-inventory", "DOC-C" });

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      mockNativeQuery(session, sqlRows);

      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONArray resultData = result.getBody().getJSONObject("response").getJSONArray("data");

      // Verify each row in order
      String[][] expected = { { "HDR-A", "goods-shipment", "DOC-A" }, { "HDR-B", "goods-movements", "DOC-B" }, { "HDR-C", "physical-inventory", "DOC-C" } };
      for (int i = 0; i < expected.length; i++) {
        JSONObject row = resultData.getJSONObject(i);
        assertEquals(expected[i][0], row.getString("etgoDocHeaderId"), "Row " + i + " etgoDocHeaderId");
        assertEquals(expected[i][1], row.getString("etgoDocWindow"), "Row " + i + " etgoDocWindow");
        assertEquals(expected[i][2], row.getString("etgoDocLabel"), "Row " + i + " etgoDocLabel");
      }
    }
  }
}
