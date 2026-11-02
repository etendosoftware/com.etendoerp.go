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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

  /**
   * The Product "Movements" tab's flag (ETP-5889), on by default so the tests of the extra work see it;
   * a test that needs it off stubs {@code false}. The Warehouse spec never consults it.
   */
  private MockedStatic<ProductMovementsPolicy> policy;

  @BeforeEach
  void flagIsOn() {
    policy = Mockito.mockStatic(ProductMovementsPolicy.class);
    policy.when(ProductMovementsPolicy::isEnabled).thenReturn(true);
  }

  @AfterEach
  void closeFlag() {
    policy.close();
  }

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

  // ---------------------------------------------------------------------------
  // afterHandle() — internal consumption (ETP-5039)
  // ---------------------------------------------------------------------------

  /**
   * A transaction linked to an Internal Consumption line must resolve to the
   * {@code internal-consumption} window key, with the header id and label taken
   * from {@code M_Internal_Consumption} (which, like {@code M_Inventory}, has no
   * {@code documentno} column and uses {@code name} as its identifier).
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_internalConsumption_resolvesToInternalConsumptionWindow() throws Exception {
    JSONArray data = new JSONArray().put(txRow("TX-IC-1"));
    JSONObject body = wrapData(data);

    Object[] sqlRow = new Object[]{ "TX-IC-1", "HDR-IC-1", "internal-consumption", "IC-0001" };

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
      assertEquals("HDR-IC-1", row.getString("etgoDocHeaderId"));
      assertEquals("internal-consumption", row.getString("etgoDocWindow"));
      assertEquals("IC-0001", row.getString("etgoDocLabel"));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — etgoStockAfter (ETP-5889, Product "Movements" tab)
  // ---------------------------------------------------------------------------

  /**
   * Mocks two native queries on the session: any SQL answers {@code documentRows} (the document
   * targets) and the stock-after SQL answers {@code stockRows}. The later, more specific stubbing
   * wins for the stock SQL.
   */
  @SuppressWarnings("unchecked")
  private static NativeQuery<Object[]> mockDocumentAndStockQueries(Session session, List<Object[]> documentRows,
      NativeQuery<Object[]> stockQuery) {
    NativeQuery<Object[]> documentQuery = mockNativeQuery(session, documentRows);
    when(session.createNativeQuery(contains("stock_after"))).thenReturn(stockQuery);
    return documentQuery;
  }

  /** The native queries only see what the session can read: its readable organizations are bound. */
  private static void stubReadableOrganizations(MockedStatic<OBContext> obCtx) {
    OBContext context = mock(OBContext.class);
    when(context.getReadableOrganizations()).thenReturn(new String[]{ "0", "ORG-1" });
    obCtx.when(OBContext::getOBContext).thenReturn(context);
  }

  @SuppressWarnings("unchecked")
  private static NativeQuery<Object[]> stockQuery(List<Object[]> rows) {
    NativeQuery<Object[]> query = mock(NativeQuery.class);
    when(query.setParameterList(eq("ids"), anyList())).thenReturn(query);
    when(query.setParameterList(eq("orgs"), anyList())).thenReturn(query);
    when(query.list()).thenReturn(rows);
    return query;
  }

  /**
   * Each row that the stock query resolves carries the stock left after its movement, negative
   * values included (a product can be sold before it is received); a row the query does not
   * resolve carries nothing.
   */
  @Test
  void afterHandle_addsStockAfter_toEveryRowTheQueryResolves() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")).put(txRow("TX-002")).put(txRow("TX-003")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      mockDocumentAndStockQueries(session, Collections.emptyList(), stockQuery(Arrays.asList(
          new Object[]{ "TX-001", new BigDecimal("-12.00"), new BigDecimal("-12.00") },
          new Object[]{ "TX-002", "563", "563" })));

      NeoContext ctx = productCtx(null);
      ctx.setPreviousResult(NeoResponse.ok(body));

      JSONArray rows = HANDLER.afterHandle(ctx).getBody().getJSONObject("response").getJSONArray("data");

      assertEquals(-12.0, rows.getJSONObject(0).getDouble("etgoStockAfter"), 0.0001);
      assertEquals(563.0, rows.getJSONObject(1).getDouble("etgoStockAfter"), 0.0001);
      assertFalse(rows.getJSONObject(2).has("etgoStockAfter"), "a row the query did not resolve must carry no stock");
    }
  }

  /**
   * A failure while resolving the stock must not cost the rows their document fields: they keep
   * etgoDocHeaderId/etgoDocWindow/etgoDocLabel and simply carry no etgoStockAfter.
   */
  @Test
  void afterHandle_stockQueryFailure_keepsTheDocumentFieldsAndOmitsTheStock() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      NativeQuery<Object[]> failing = stockQuery(Collections.emptyList());
      when(failing.list()).thenThrow(new RuntimeException("simulated stock failure"));
      mockDocumentAndStockQueries(session,
          Collections.singletonList(new Object[]{ "TX-001", "HDR-1", "goods-receipt", "1000001" }), failing);

      NeoContext ctx = productCtx(null);
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = assertDoesNotThrow(() -> HANDLER.afterHandle(ctx));

      JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("HDR-1", row.getString("etgoDocHeaderId"));
      assertEquals("goods-receipt", row.getString("etgoDocWindow"));
      assertEquals("1000001", row.getString("etgoDocLabel"));
      assertFalse(row.has("etgoStockAfter"), "a failed stock query must leave the field out");
    }
  }

  /**
   * A null or unreadable stock is left out, never turned into 0: the tab shows a dash for it, and a
   * zero would read as "the product ran out here".
   */
  @Test
  void afterHandle_nullOrUnreadableStock_isOmittedNotZero() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")).put(txRow("TX-002")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      mockDocumentAndStockQueries(session, Collections.emptyList(), stockQuery(Arrays.asList(
          new Object[]{ "TX-001", null, null },
          new Object[]{ "TX-002", "not-a-number", "not-a-number" })));

      NeoContext ctx = productCtx(null);
      ctx.setPreviousResult(NeoResponse.ok(body));

      JSONArray rows = HANDLER.afterHandle(ctx).getBody().getJSONObject("response").getJSONArray("data");

      assertFalse(rows.getJSONObject(0).has("etgoStockAfter"));
      assertFalse(rows.getJSONObject(1).has("etgoStockAfter"));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — response.etgoTotalRows (exact total for the tab counter)
  // ---------------------------------------------------------------------------

  private static NeoContext productCtx(java.util.Map<String, String> queryParams) {
    return NeoContext.builder().specName("product").entityName("transactions").httpMethod("GET").endpointType(
        NeoEndpointType.CRUD).queryParams(queryParams).build();
  }

  @SuppressWarnings("unchecked")
  private static NativeQuery<Object> countQuery(Object total) {
    NativeQuery<Object> query = mock(NativeQuery.class);
    when(query.setParameter(eq("productId"), anyString())).thenReturn(query);
    when(query.setParameterList(eq("orgs"), anyList())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(total);
    return query;
  }

  /**
   * NEO's totalRows is only a lower bound on a partial page, so a Product request with no criteria
   * gets the exact number of movements of the product in {@code response.etgoTotalRows}.
   */
  @Test
  void afterHandle_productRequestWithoutCriteria_addsTheExactTotal() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      mockNativeQuery(session, Collections.emptyList());
      NativeQuery<Object> exact = countQuery(java.math.BigInteger.valueOf(8));
      when(session.createNativeQuery(contains("COUNT(*)"))).thenReturn((NativeQuery) exact);

      NeoContext ctx = productCtx(java.util.Map.of("parentId", "P1", "_startRow", "0", "_endRow", "0"));
      ctx.setPreviousResult(NeoResponse.ok(body));

      JSONObject response = HANDLER.afterHandle(ctx).getBody().getJSONObject("response");

      assertEquals(8L, response.getLong("etgoTotalRows"));
    }
  }

  /** With criteria the total would have to honour them, which the handler cannot do: no field. */
  @Test
  void afterHandle_productRequestWithCriteria_addsNoTotalAndNeverCounts() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      mockNativeQuery(session, Collections.emptyList());

      NeoContext ctx = productCtx(java.util.Map.of("parentId", "P1", "criteria", "[{\"fieldName\":\"movementQuantity\"}]"));
      ctx.setPreviousResult(NeoResponse.ok(body));

      JSONObject response = HANDLER.afterHandle(ctx).getBody().getJSONObject("response");

      assertFalse(response.has("etgoTotalRows"));
      Mockito.verify(session, Mockito.never()).createNativeQuery(contains("COUNT(*)"));
    }
  }

  /** The Warehouse window shares this handler and its parentId is a storage bin, not a product. */
  @Test
  void afterHandle_otherSpec_addsNoTotal() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      mockNativeQuery(session, Collections.emptyList());

      NeoContext ctx = NeoContext.builder().specName("warehouse").entityName("productTransactions").httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD).queryParams(java.util.Map.of("parentId", "BIN-1")).build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      assertFalse(HANDLER.afterHandle(ctx).getBody().getJSONObject("response").has("etgoTotalRows"));
    }
  }

  /** A failing count must not cost the rows their fields. */
  @Test
  void afterHandle_countFailure_keepsTheRowsAndOmitsTheTotal() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      mockNativeQuery(session, Collections.singletonList(new Object[]{ "TX-001", "HDR-1", "goods-receipt", "1000001" }));
      NativeQuery<Object> failing = countQuery(null);
      when(failing.uniqueResult()).thenThrow(new RuntimeException("simulated count failure"));
      when(session.createNativeQuery(contains("COUNT(*)"))).thenReturn((NativeQuery) failing);

      NeoContext ctx = productCtx(java.util.Map.of("parentId", "P1"));
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = assertDoesNotThrow(() -> HANDLER.afterHandle(ctx));

      JSONObject response = result.getBody().getJSONObject("response");
      assertFalse(response.has("etgoTotalRows"));
      assertEquals("HDR-1", response.getJSONArray("data").getJSONObject(0).getString("etgoDocHeaderId"));
    }
  }

  /**
   * A native query bypasses the DAL read restrictions, so both the stock and the count are bound to
   * the organizations the session can read, never to every organization.
   */
  @Test
  @SuppressWarnings({ "unchecked", "rawtypes" })
  void afterHandle_stockAndCountQueries_areBoundToTheReadableOrganizations() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      NativeQuery<Object[]> stock = stockQuery(Collections.emptyList());
      mockDocumentAndStockQueries(session, Collections.emptyList(), stock);
      NativeQuery<Object> count = countQuery(java.math.BigInteger.ONE);
      when(session.createNativeQuery(contains("COUNT(*)"))).thenReturn((NativeQuery) count);

      NeoContext ctx = productCtx(java.util.Map.of("parentId", "P1"));
      ctx.setPreviousResult(NeoResponse.ok(body));
      HANDLER.afterHandle(ctx);

      Mockito.verify(stock).setParameterList("orgs", Arrays.asList("0", "ORG-1"));
      Mockito.verify(count).setParameterList("orgs", Arrays.asList("0", "ORG-1"));
      Mockito.verify(count).setParameter("productId", "P1");
    }
  }

  /** Without a parentId there is no product to count: no field and no count query. */
  @Test
  void afterHandle_productRequestWithoutParentId_addsNoTotalAndNeverCounts() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      mockNativeQuery(session, Collections.emptyList());

      NeoContext ctx = productCtx(java.util.Map.of("_startRow", "0"));
      ctx.setPreviousResult(NeoResponse.ok(body));

      assertFalse(HANDLER.afterHandle(ctx).getBody().getJSONObject("response").has("etgoTotalRows"));
      Mockito.verify(session, Mockito.never()).createNativeQuery(contains("COUNT(*)"));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — the flag (ProductMovementsPolicy) gates the extra work
  // ---------------------------------------------------------------------------

  /**
   * With the flag off for the account, a Product request gets the document fields only, as before
   * ETP-5889: no stock query, no count, no extra fields.
   */
  @Test
  void afterHandle_flagOff_addsNoStockNoTotalAndRunsNeitherQuery() throws Exception {
    policy.when(ProductMovementsPolicy::isEnabled).thenReturn(false);
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      mockNativeQuery(session, Collections.singletonList(new Object[]{ "TX-001", "HDR-1", "goods-receipt", "1000001" }));

      NeoContext ctx = productCtx(java.util.Map.of("parentId", "P1"));
      ctx.setPreviousResult(NeoResponse.ok(body));

      JSONObject response = HANDLER.afterHandle(ctx).getBody().getJSONObject("response");

      JSONObject row = response.getJSONArray("data").getJSONObject(0);
      assertEquals("HDR-1", row.getString("etgoDocHeaderId"), "the document fields are unaffected by the flag");
      assertFalse(row.has("etgoStockAfter"));
      assertFalse(response.has("etgoTotalRows"));
      Mockito.verify(session, Mockito.never()).createNativeQuery(contains("stock_after"));
      Mockito.verify(session, Mockito.never()).createNativeQuery(contains("COUNT(*)"));
    }
  }

  /** The Warehouse window shares this handler and has no use for the extra work: the flag is never read. */
  @Test
  void afterHandle_warehouseSpec_neverConsultsTheFlag() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      mockNativeQuery(session, Collections.emptyList());

      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      HANDLER.afterHandle(ctx);

      policy.verifyNoInteractions();
      Mockito.verify(session, Mockito.never()).createNativeQuery(contains("stock_after"));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — count-only probe (_endRow=0) skips the stock query
  // ---------------------------------------------------------------------------

  /** Runs afterHandle on a product request; returns the stock query mock for verification. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static NativeQuery<Object[]> runProductRequest(java.util.Map<String, String> params, Session session,
      JSONObject body, JSONObject[] responseOut) throws Exception {
    NativeQuery<Object[]> stock = stockQuery(Collections.singletonList(new Object[]{ "TX-001", new BigDecimal("7"), new BigDecimal("7") }));
    mockDocumentAndStockQueries(session, Collections.singletonList(new Object[]{ "TX-001", "HDR-1", "goods-receipt", "1000001" }), stock);
    NativeQuery<Object> count = countQuery(java.math.BigInteger.valueOf(42));
    when(session.createNativeQuery(contains("COUNT(*)"))).thenReturn((NativeQuery) count);

    NeoContext ctx = productCtx(params);
    ctx.setPreviousResult(NeoResponse.ok(body));
    responseOut[0] = HANDLER.afterHandle(ctx).getBody().getJSONObject("response");
    return stock;
  }

  /**
   * A count-only request (_endRow=0) must not run the stock query nor add etgoStockAfter, but still
   * adds etgoTotalRows and the document fields.
   */
  @Test
  void afterHandle_countOnlyRequest_skipsStockButKeepsTotalAndDocumentFields() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      JSONObject[] out = new JSONObject[1];

      NativeQuery<Object[]> stock = runProductRequest(
          java.util.Map.of("parentId", "P1", "_startRow", "0", "_endRow", "0"), session, body, out);

      JSONObject row = out[0].getJSONArray("data").getJSONObject(0);
      assertFalse(row.has("etgoStockAfter"));
      assertEquals("HDR-1", row.getString("etgoDocHeaderId"));
      assertEquals(42L, out[0].getLong("etgoTotalRows"));
      Mockito.verify(session, Mockito.never()).createNativeQuery(contains("stock_after"));
      Mockito.verifyNoInteractions(stock);
    }
  }

  /** A normal page (_endRow=49) still resolves the stock after each movement. */
  @Test
  void afterHandle_normalPage_stillResolvesStockAfter() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      JSONObject[] out = new JSONObject[1];

      runProductRequest(java.util.Map.of("parentId", "P1", "_startRow", "0", "_endRow", "49"), session, body, out);

      assertEquals(new BigDecimal("7"), new BigDecimal(out[0].getJSONArray("data").getJSONObject(0).get("etgoStockAfter").toString()));
      Mockito.verify(session).createNativeQuery(contains("stock_after"));
    }
  }

  /** Without _endRow the request is a normal page: the stock is resolved. */
  @Test
  void afterHandle_missingEndRow_isANormalPage() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      JSONObject[] out = new JSONObject[1];

      runProductRequest(java.util.Map.of("parentId", "P1"), session, body, out);

      assertTrue(out[0].getJSONArray("data").getJSONObject(0).has("etgoStockAfter"));
    }
  }

  /** Null query params: not count-only, the stock is resolved and no total is added (no parentId). */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_nullQueryParams_isANormalPageWithoutTotal() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      NativeQuery<Object[]> stock = stockQuery(Collections.singletonList(new Object[]{ "TX-001", new BigDecimal("3"), new BigDecimal("3") }));
      mockDocumentAndStockQueries(session, Collections.emptyList(), stock);

      NeoContext ctx = productCtx(null);
      ctx.setPreviousResult(NeoResponse.ok(body));
      JSONObject response = HANDLER.afterHandle(ctx).getBody().getJSONObject("response");

      assertTrue(response.getJSONArray("data").getJSONObject(0).has("etgoStockAfter"));
      assertFalse(response.has("etgoTotalRows"));
      Mockito.verify(session, Mockito.never()).createNativeQuery(contains("COUNT(*)"));
    }
  }

  /**
   * The stock SQL is bounded by the newest movement date of the page, and still binds both the ids
   * and the readable organizations.
   */
  @Test
  void afterHandle_stockSql_isBoundedByPageDateAndBindsIdsAndOrgs() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      NativeQuery<Object[]> stock = stockQuery(Collections.emptyList());
      mockDocumentAndStockQueries(session, Collections.emptyList(), stock);

      NeoContext ctx = productCtx(java.util.Map.of("parentId", "P1", "_endRow", "49"));
      ctx.setPreviousResult(NeoResponse.ok(body));
      HANDLER.afterHandle(ctx);

      org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
      Mockito.verify(session, Mockito.atLeastOnce()).createNativeQuery(sql.capture());
      String stockSql = sql.getAllValues().stream().filter(q -> q.contains("stock_after")).findFirst().orElseThrow();
      assertTrue(stockSql.contains("t.movementdate <= (SELECT MAX(q.movementdate)"));
      assertTrue(stockSql.contains("q.m_transaction_id IN (:ids)"));
      Mockito.verify(stock).setParameterList("ids", Collections.singletonList("TX-001"));
      Mockito.verify(stock).setParameterList("orgs", Arrays.asList("0", "ORG-1"));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — etgoStockAfter: product stock vs. warehouse stock (warehouse filter)
  // ---------------------------------------------------------------------------

  private static final String WAREHOUSE_CRITERION =
      "{\"fieldName\":\"etgoWarehouse\",\"operator\":\"equals\",\"value\":\"WH-1\"}";

  /**
   * Runs a Product request whose stock query answers one row per tx: column 1 (product stock) = 100,
   * column 2 (warehouse stock) = 40, so the column the handler read is told by the value. Returns the
   * etgoStockAfter of the row, or null when the field is absent.
   */
  private static BigDecimal stockAfterFor(java.util.Map<String, String> params, Object productStock,
      Object warehouseStock) throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      NativeQuery<Object[]> stock = stockQuery(
          Collections.singletonList(new Object[]{ "TX-001", productStock, warehouseStock }));
      mockDocumentAndStockQueries(session, Collections.emptyList(), stock);

      NeoContext ctx = productCtx(params);
      ctx.setPreviousResult(NeoResponse.ok(body));
      JSONObject row = HANDLER.afterHandle(ctx).getBody().getJSONObject("response").getJSONArray("data")
          .getJSONObject(0);
      return row.has("etgoStockAfter") ? new BigDecimal(row.get("etgoStockAfter").toString()) : null;
    }
  }

  private static java.util.Map<String, String> criteria(String json) {
    return java.util.Map.of("parentId", "P1", "_endRow", "49", "criteria", json);
  }

  private static final BigDecimal PRODUCT_STOCK = new BigDecimal("100");
  private static final BigDecimal WAREHOUSE_STOCK = new BigDecimal("40");

  @Test
  void afterHandle_noCriteria_usesTheProductStock() throws Exception {
    assertEquals(PRODUCT_STOCK, stockAfterFor(java.util.Map.of("parentId", "P1", "_endRow", "49"),
        PRODUCT_STOCK, WAREHOUSE_STOCK));
  }

  @Test
  void afterHandle_warehouseCriterion_usesTheWarehouseStock() throws Exception {
    assertEquals(WAREHOUSE_STOCK,
        stockAfterFor(criteria("[" + WAREHOUSE_CRITERION + "]"), PRODUCT_STOCK, WAREHOUSE_STOCK));
  }

  @Test
  void afterHandle_dateAndDirectionCriteriaOnly_useTheProductStock() throws Exception {
    String json = "[{\"fieldName\":\"movementDate\",\"operator\":\"greaterOrEqual\",\"value\":\"2026-01-01\"},"
        + "{\"fieldName\":\"movementQuantity\",\"operator\":\"greaterThan\",\"value\":\"0\"}]";
    assertEquals(PRODUCT_STOCK, stockAfterFor(criteria(json), PRODUCT_STOCK, WAREHOUSE_STOCK));
  }

  @Test
  void afterHandle_warehouseCriterionAmongOthers_usesTheWarehouseStock() throws Exception {
    String json = "[{\"fieldName\":\"movementDate\",\"operator\":\"greaterOrEqual\",\"value\":\"2026-01-01\"},"
        + WAREHOUSE_CRITERION + ",{\"fieldName\":\"movementQuantity\",\"operator\":\"lessThan\",\"value\":\"0\"}]";
    assertEquals(WAREHOUSE_STOCK, stockAfterFor(criteria(json), PRODUCT_STOCK, WAREHOUSE_STOCK));
  }

  /** Anything that is not that warehouse equality means "not filtered": the stock of the product, no failure. */
  @Test
  void afterHandle_unreadableOrNonMatchingCriteria_fallBackToTheProductStock() throws Exception {
    String[] notFiltered = {
        "{not json", "", "   ", "{\"fieldName\":\"etgoWarehouse\",\"operator\":\"equals\",\"value\":\"WH-1\"}",
        "[]", "[null]", "[\"etgoWarehouse\"]",
        "[{\"fieldName\":\"etgoWarehouse\",\"operator\":\"notEqual\",\"value\":\"WH-1\"}]",
        "[{\"fieldName\":\"etgoWarehouse\",\"operator\":\"iContains\",\"value\":\"WH-1\"}]",
        "[{\"fieldName\":\"warehouse\",\"operator\":\"equals\",\"value\":\"WH-1\"}]",
        "[{\"fieldName\":\"etgoWarehouse\",\"operator\":\"equals\",\"value\":\"\"}]",
        "[{\"fieldName\":\"etgoWarehouse\",\"operator\":\"equals\",\"value\":\"  \"}]",
        "[{\"fieldName\":\"etgoWarehouse\",\"operator\":\"equals\"}]",
        "[{\"fieldName\":\"etgoWarehouse\",\"value\":\"WH-1\"}]" };
    for (String json : notFiltered) {
      assertEquals(PRODUCT_STOCK, assertDoesNotThrow(() -> stockAfterFor(criteria(json), PRODUCT_STOCK, WAREHOUSE_STOCK)),
          "criteria '" + json + "' must read the product stock");
    }
  }

  /** The chosen column is null: the stock is omitted, never 0, even if the other column has a value. */
  @Test
  void afterHandle_nullInTheChosenColumn_omitsTheStockEvenIfTheOtherHasOne() throws Exception {
    assertNull(stockAfterFor(java.util.Map.of("parentId", "P1", "_endRow", "49"), null, WAREHOUSE_STOCK));
    assertNull(stockAfterFor(criteria("[" + WAREHOUSE_CRITERION + "]"), PRODUCT_STOCK, null));
  }

  /** Both windows run in one statement: the per-warehouse partition is in the SQL and ids/orgs stay bound. */
  @Test
  void afterHandle_stockSql_partitionsByProductAndWarehouse() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      NativeQuery<Object[]> stock = stockQuery(Collections.emptyList());
      mockDocumentAndStockQueries(session, Collections.emptyList(), stock);

      NeoContext ctx = productCtx(criteria("[" + WAREHOUSE_CRITERION + "]"));
      ctx.setPreviousResult(NeoResponse.ok(body));
      HANDLER.afterHandle(ctx);

      org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
      Mockito.verify(session, Mockito.atLeastOnce()).createNativeQuery(sql.capture());
      String stockSql = sql.getAllValues().stream().filter(q -> q.contains("stock_after")).findFirst().orElseThrow();
      assertTrue(stockSql.contains("PARTITION BY t.m_product_id, t.em_etgo_warehouse_id"));
      assertTrue(stockSql.matches("(?s).*PARTITION BY t\\.m_product_id\\s+ORDER BY.*"), "the product-wide sum must remain");
      assertTrue(stockSql.contains("stock_after_warehouse"));
      assertTrue(stockSql.contains("t.ad_org_id IN (:orgs)"));
      Mockito.verify(stock).setParameterList("ids", Collections.singletonList("TX-001"));
      Mockito.verify(stock).setParameterList("orgs", Arrays.asList("0", "ORG-1"));
    }
  }

  /** The count-only probe still never reads the stock, warehouse filter or not. */
  @Test
  void afterHandle_countOnlyWithWarehouseFilter_stillSkipsTheStockQuery() throws Exception {
    JSONObject body = wrapData(new JSONArray().put(txRow("TX-001")));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      JSONObject[] out = new JSONObject[1];

      NativeQuery<Object[]> stock = runProductRequest(java.util.Map.of("parentId", "P1", "_startRow", "0",
          "_endRow", "0", "criteria", "[" + WAREHOUSE_CRITERION + "]"), session, body, out);

      assertFalse(out[0].getJSONArray("data").getJSONObject(0).has("etgoStockAfter"));
      Mockito.verify(session, Mockito.never()).createNativeQuery(contains("stock_after"));
      Mockito.verifyNoInteractions(stock);
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — guards run before any native count query (LOW-2)
  // ---------------------------------------------------------------------------

  private static void assertNoNativeQuery(NeoContext ctx) {
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);

      assertNull(HANDLER.afterHandle(ctx));

      Mockito.verify(session, Mockito.never()).createNativeQuery(anyString());
    }
  }

  /** No previous result: null and no native query (no count) for the product spec. */
  @Test
  void afterHandle_productSpecNoPreviousResult_returnsNullAndRunsNoQuery() {
    assertNoNativeQuery(productCtx(java.util.Map.of("parentId", "P1")));
  }

  /** An error response carries no data array: null and no count query. */
  @Test
  void afterHandle_productSpecErrorBody_returnsNullAndRunsNoQuery() throws Exception {
    NeoContext ctx = productCtx(java.util.Map.of("parentId", "P1"));
    ctx.setPreviousResult(new NeoResponse(500,
        new JSONObject().put("response", new JSONObject().put("status", -1).put("error", "boom"))));
    assertNoNativeQuery(ctx);
  }

  /** A previous result with a null body: null and no count query. */
  @Test
  void afterHandle_productSpecNullBody_returnsNullAndRunsNoQuery() {
    NeoContext ctx = productCtx(java.util.Map.of("parentId", "P1"));
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNoNativeQuery(ctx);
  }

  /** An empty data array (no rows) never triggers the count either. */
  @Test
  void afterHandle_productSpecEmptyData_returnsNullAndRunsNoQuery() throws Exception {
    NeoContext ctx = productCtx(java.util.Map.of("parentId", "P1"));
    ctx.setPreviousResult(NeoResponse.ok(wrapData(new JSONArray())));
    assertNoNativeQuery(ctx);
  }
}
