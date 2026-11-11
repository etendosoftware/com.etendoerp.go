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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * @covers com.etendoerp.go.schemaforge.ProductTransactionsHandler
 * @covers com.etendoerp.go.schemaforge.ProductMovementTypeOrder
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
  // binding — one @NeoExtension per (spec, entity), no legacy @Named
  // ---------------------------------------------------------------------------

  /**
   * Both pairs the handler serves resolve by annotation, and the shared behaviour class carries no
   * binding of its own (a {@code @Named} would be a second, ambiguous bean).
   */
  @Test
  void binding_bothPairsAreAnnotated_andBaseIsUnbound() {
    NeoExtension w = WarehouseProductTransactionsHandler.class.getAnnotation(NeoExtension.class);
    NeoExtension p = ProductMovementsTabHandler.class.getAnnotation(NeoExtension.class);
    assertEquals("warehouse", w.spec());
    assertEquals("productTransactions", w.entity());
    assertEquals("product", p.spec());
    assertEquals("transactions", p.entity());
    assertNull(ProductTransactionsHandler.class.getAnnotation(NeoExtension.class));
    assertNull(ProductTransactionsHandler.class.getAnnotation(javax.inject.Named.class));
    assertNull(WarehouseProductTransactionsHandler.class.getAnnotation(javax.inject.Named.class));
    assertNull(ProductMovementsTabHandler.class.getAnnotation(javax.inject.Named.class));
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
  // afterHandle() — shared helpers and the readable-organizations binding
  // ---------------------------------------------------------------------------

  private static NeoContext productCtx(java.util.Map<String, String> queryParams) {
    return NeoContext.builder().specName("product").entityName("transactions").httpMethod("GET").endpointType(
        NeoEndpointType.CRUD).queryParams(queryParams).build();
  }

  /**
   * A native query bypasses the DAL read restrictions, so the stock query is bound to the
   * organizations the session can read, never to every organization.
   */
  @Test
  @SuppressWarnings({ "unchecked", "rawtypes" })
  void afterHandle_stockQuery_isBoundToTheReadableOrganizations() throws Exception {
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

      NeoContext ctx = productCtx(java.util.Map.of("parentId", "P1"));
      ctx.setPreviousResult(NeoResponse.ok(body));
      HANDLER.afterHandle(ctx);

      Mockito.verify(stock).setParameterList("orgs", Arrays.asList("0", "ORG-1"));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — the flag (ProductMovementsPolicy) gates the extra work
  // ---------------------------------------------------------------------------

  /**
   * With the flag off for the account, a Product request gets the document fields only, as before
   * ETP-5889: no stock query, no extra fields.
   */
  @Test
  void afterHandle_flagOff_addsNoStockAndRunsNoStockQuery() throws Exception {
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
      Mockito.verify(session, Mockito.never()).createNativeQuery(contains("stock_after"));
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
  // afterHandle() — stock resolution on a normal page
  // ---------------------------------------------------------------------------

  /** Runs afterHandle on a product request; returns the stock query mock for verification. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static NativeQuery<Object[]> runProductRequest(java.util.Map<String, String> params, Session session,
      JSONObject body, JSONObject[] responseOut) throws Exception {
    NativeQuery<Object[]> stock = stockQuery(Collections.singletonList(new Object[]{ "TX-001", new BigDecimal("7"), new BigDecimal("7") }));
    mockDocumentAndStockQueries(session, Collections.singletonList(new Object[]{ "TX-001", "HDR-1", "goods-receipt", "1000001" }), stock);

    NeoContext ctx = productCtx(params);
    ctx.setPreviousResult(NeoResponse.ok(body));
    responseOut[0] = HANDLER.afterHandle(ctx).getBody().getJSONObject("response");
    return stock;
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

  /** Null query params: the stock is resolved. */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_nullQueryParams_isANormalPage() throws Exception {
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

  /** Runs a Product request whose stock query answers one row per entry; returns the resulting rows. */
  private JSONArray productRowsFor(java.util.Map<String, String> params, List<Object[]> stockRows,
      JSONArray inputRows, boolean flagOn) throws Exception {
    policy.when(ProductMovementsPolicy::isEnabled).thenReturn(flagOn);
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      mockDocumentAndStockQueries(session, Collections.emptyList(), stockQuery(stockRows));
      NeoContext ctx = productCtx(params);
      ctx.setPreviousResult(NeoResponse.ok(wrapData(inputRows)));
      JSONArray rows = HANDLER.afterHandle(ctx).getBody().getJSONObject("response").getJSONArray("data");
      if (!flagOn) {
        Mockito.verify(session, Mockito.never()).createNativeQuery(contains("stock_after"));
      }
      return rows;
    }
  }

  /** The wall clock is exposed as is, zero padded, fractions dropped, with no time-zone shift. */
  @Test
  void afterHandle_processTime_isTheWallClockAsIsoLocalDateTime() throws Exception {
    JSONArray rows = productRowsFor(null, Arrays.asList(
        new Object[]{ "TX-001", new BigDecimal("5"), new BigDecimal("5"), Timestamp.valueOf("2026-11-03 16:21:43.372") },
        new Object[]{ "TX-002", new BigDecimal("6"), new BigDecimal("6"), Timestamp.valueOf("2026-01-02 09:05:07") }),
        new JSONArray().put(txRow("TX-001")).put(txRow("TX-002")), true);

    assertEquals("2026-11-03T16:21:43", rows.getJSONObject(0).getString("etgoProcessTime"));
    assertEquals("2026-01-02T09:05:07", rows.getJSONObject(1).getString("etgoProcessTime"));
  }

  /** No process date: the field is omitted (never an empty string); the stock stays. */
  @Test
  void afterHandle_nullProcessTime_isOmittedAndKeepsTheStock() throws Exception {
    JSONArray rows = productRowsFor(null, Collections.singletonList(
        new Object[]{ "TX-001", new BigDecimal("5"), new BigDecimal("5"), null }),
        new JSONArray().put(txRow("TX-001")), true);

    assertFalse(rows.getJSONObject(0).has("etgoProcessTime"));
    assertEquals(5.0, rows.getJSONObject(0).getDouble("etgoStockAfter"), 0.0001);
  }

  /** A three-column row (no process date column) and an unreadable value are tolerated, not fatal. */
  @Test
  void afterHandle_missingOrUnreadableProcessTime_isOmitted() throws Exception {
    JSONArray rows = productRowsFor(null, Arrays.asList(
        new Object[]{ "TX-001", new BigDecimal("5"), new BigDecimal("5") },
        new Object[]{ "TX-002", new BigDecimal("6"), new BigDecimal("6"), "not a timestamp" }),
        new JSONArray().put(txRow("TX-001")).put(txRow("TX-002")), true);

    assertFalse(rows.getJSONObject(0).has("etgoProcessTime"));
    assertFalse(rows.getJSONObject(1).has("etgoProcessTime"));
    assertEquals(6.0, rows.getJSONObject(1).getDouble("etgoStockAfter"), 0.0001);
  }

  /** Flag off: no field and no stock query at all (the time rides on that query). */
  @Test
  void afterHandle_flagOff_addsNoProcessTime() throws Exception {
    JSONArray rows = productRowsFor(java.util.Map.of("parentId", "P1"), Collections.singletonList(
        new Object[]{ "TX-001", new BigDecimal("5"), new BigDecimal("5"), Timestamp.valueOf("2026-11-03 16:21:43") }),
        new JSONArray().put(txRow("TX-001")), false);

    assertFalse(rows.getJSONObject(0).has("etgoProcessTime"));
  }

  /** The warehouse filter only picks the stock column: the time is the same either way. */
  @Test
  void afterHandle_processTime_isUnaffectedByTheWarehouseFilter() throws Exception {
    List<Object[]> stockRows = Collections.singletonList(
        new Object[]{ "TX-001", PRODUCT_STOCK, WAREHOUSE_STOCK, Timestamp.valueOf("2026-11-03 09:05:07") });
    JSONArray plain = productRowsFor(java.util.Map.of("parentId", "P1", "_endRow", "49"), stockRows,
        new JSONArray().put(txRow("TX-001")), true);
    JSONArray filtered = productRowsFor(criteria("[" + WAREHOUSE_CRITERION + "]"), stockRows,
        new JSONArray().put(txRow("TX-001")), true);

    assertEquals("2026-11-03T09:05:07", plain.getJSONObject(0).getString("etgoProcessTime"));
    assertEquals("2026-11-03T09:05:07", filtered.getJSONObject(0).getString("etgoProcessTime"));
    assertEquals(100.0, plain.getJSONObject(0).getDouble("etgoStockAfter"), 0.0001);
    assertEquals(40.0, filtered.getJSONObject(0).getDouble("etgoStockAfter"), 0.0001);
  }

  /** The SQL returns the process time from the same statement and the order key is untouched. */
  @Test
  void afterHandle_stockSql_returnsTheProcessTimeWithoutChangingTheOrder() throws Exception {
    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {
      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      stubReadableOrganizations(obCtx);
      mockDocumentAndStockQueries(session, Collections.emptyList(), stockQuery(Collections.emptyList()));
      NeoContext ctx = productCtx(null);
      ctx.setPreviousResult(NeoResponse.ok(wrapData(new JSONArray().put(txRow("TX-001")))));
      HANDLER.afterHandle(ctx);
      Mockito.verify(session, Mockito.atLeastOnce()).createNativeQuery(sql.capture());
    }
    String stockSql = sql.getAllValues().stream().filter(q -> q.contains("stock_after")).findFirst().orElseThrow();
    assertTrue(stockSql.contains("s.trxprocessdate AS process_time"));
    assertTrue(stockSql.contains("SELECT t.m_transaction_id, t.trxprocessdate,"));
    assertTrue(stockSql.contains("ORDER BY t.movementdate, t.trxprocessdate, t.m_transaction_id"));
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

  // ---------------------------------------------------------------------------
  // Sort by the visible Type label: bucket rule, order-by and the orderedPage hook
  // ---------------------------------------------------------------------------

  /**
   * One row of the shared table of cases: {kind, movementQuantity, movementType, expected bucket}.
   * The SAME table is in {@code productMovements.vitest.js} (SPA, {@code resolveMovementBucket}); if one
   * side changes a rule, both tests must change together or one of them fails.
   */
  private static final String[][] BUCKET_CASES = {
      { "goods-receipt", "5", "V+", "vendor-receipt" },
      { "goods-receipt", "0", "V+", "vendor-receipt" },
      { "goods-receipt", "-5", "V-", "vendor-return" },
      { "return-to-vendor-shipment", "-1", "V-", "vendor-return" },
      { "goods-shipment", "-3", "C-", "customer-shipment" },
      { "goods-shipment", "0", "C-", "customer-shipment" },
      { "goods-shipment", "3", "C+", "customer-return" },
      { "return-material-receipt", "2", "C+", "customer-return" },
      { "goods-movements", "1", "M+", "transfer" },
      { "goods-movements", "-1", "M-", "transfer" },
      { "internal-consumption", "-1", "D-", "internal-consumption" },
      { "physical-inventory", "2", "I+", "inventory-in" },
      { "physical-inventory", "0", "I+", "inventory-in" },
      { "physical-inventory", "-2", "I-", "inventory-out" },
      { null, "4", "V+", "vendor-receipt" },
      { null, "-4", "V-", "vendor-return" },
      { null, "-4", "C-", "customer-shipment" },
      { null, "4", "C+", "customer-return" },
      { null, "4", "I+", "inventory-in" },
      { null, "-4", "I-", "inventory-out" },
      { null, "4", "M+", "transfer" },
      { null, "-4", "M-", "transfer" },
      { null, "4", "D+", "internal-consumption" },
      { null, "-4", "D-", "internal-consumption" },
      { null, "4", "P+", "unlabeled" },
      { null, "4", null, "unlabeled" },
      // a kind the rule does not know falls through to the movement type code, like a null kind
      { "something-new", "4", "V+", "vendor-receipt" },
      { "something-new", "4", "P+", "unlabeled" },
      // the kind wins over the code
      { "goods-shipment", "-3", "V+", "customer-shipment" },
  };

  /** Tiny interpreter of the generated {@code case ... end}: enough to evaluate it on a row. */
  private static final Pattern CASE_TOKEN = Pattern.compile(
      "\\(|\\)|case|when|then|else|end|and|e\\.\\w+|'[^']*'|[<>=]|-?\\d+");

  private static String evalCase(String expression, String kind, String qty, String movementType) {
    List<String> tokens = new java.util.ArrayList<>();
    Matcher m = CASE_TOKEN.matcher(expression);
    while (m.find()) {
      tokens.add(m.group());
    }
    int[] pos = { 0 };
    return evalValue(tokens, pos, Map.of("e.etgoDocKind", kind == null ? "" : "'" + kind + "'",
        "e.movementQuantity", qty, "e.movementType", movementType == null ? "" : "'" + movementType + "'"));
  }

  private static String evalValue(List<String> t, int[] p, Map<String, String> row) {
    if ("(".equals(t.get(p[0]))) {
      p[0]++;
      String v = evalValue(t, p, row);
      p[0]++; // ')'
      return v;
    }
    if (!"case".equals(t.get(p[0]))) {
      return t.get(p[0]++);
    }
    p[0]++;
    String result = null;
    boolean decided = false;
    while ("when".equals(t.get(p[0]))) {
      p[0]++;
      String left = row.get(t.get(p[0]++));
      String op = t.get(p[0]++);
      String right = t.get(p[0]++);
      boolean ok = "=".equals(op) ? right.equals(left)
          : "<".equals(op) ? Double.parseDouble(left) < Double.parseDouble(right)
              : Double.parseDouble(left) > Double.parseDouble(right);
      p[0]++; // then
      String v = evalValue(t, p, row);
      if (ok && !decided) {
        result = v;
        decided = true;
      }
    }
    p[0]++; // else
    String other = evalValue(t, p, row);
    p[0]++; // end
    return decided ? result : other;
  }

  private static Function<String, String> quoted() {
    return id -> "'" + id + "'";
  }

  /** Every kind and sign of the shared table lands in the bucket the client derives its label from. */
  @Test
  void typeOrder_bucketRule_matchesTheSharedTableOfCases() {
    String expression = ProductMovementTypeOrder.bucketCase(quoted());
    for (String[] c : BUCKET_CASES) {
      assertEquals("'" + c[3] + "'", evalCase(expression, c[0], c[1], c[2]),
          "kind=" + c[0] + " qty=" + c[1] + " type=" + c[2]);
    }
  }

  /** The bucket ids the backend accepts are exactly the ones the shared table can produce. */
  @Test
  void typeOrder_bucketIds_areTheOnesTheTableProduces() {
    java.util.Set<String> produced = new java.util.TreeSet<>();
    for (String[] c : BUCKET_CASES) {
      if (!"unlabeled".equals(c[3])) {
        produced.add(c[3]);
      }
    }
    assertEquals(new java.util.TreeSet<>(ProductMovementTypeOrder.BUCKETS), produced);
  }

  private static int rankOf(String orderBy, String kind, String qty, String movementType) {
    String rankCase = orderBy.substring(0, orderBy.indexOf(" asc, "));
    return Integer.parseInt(evalCase(rankCase, kind, qty, movementType));
  }

  private static final String ES_ORDER = "vendor-receipt,customer-shipment,vendor-return,customer-return,"
      + "transfer,internal-consumption,inventory-in,inventory-out";

  /** Ascending: rows rank by the client's order of the labels, tie-breakers follow, id closes the order. */
  @Test
  void typeOrder_ascending_ranksByTheClientOrderThenTieBreakers() {
    String orderBy = ProductMovementTypeOrder.buildOrderBy(
        "etgoDocKind,-movementDate,-transactionProcessDate,-id", ES_ORDER);
    assertNotNull(orderBy);
    assertEquals(0, rankOf(orderBy, "goods-receipt", "5", "V+"));
    assertEquals(1, rankOf(orderBy, "goods-shipment", "-3", "C-"));
    assertEquals(2, rankOf(orderBy, "goods-receipt", "-5", "V-"));
    assertEquals(3, rankOf(orderBy, "goods-shipment", "3", "C+"));
    assertEquals(7, rankOf(orderBy, "physical-inventory", "-2", "I-"));
    assertTrue(orderBy.endsWith(" asc, e.movementDate desc, e.transactionProcessDate desc, e.id desc"),
        orderBy);
  }

  /** Descending reverses the buckets, never the tie-breakers the client sent. */
  @Test
  void typeOrder_descending_reversesTheBuckets() {
    String orderBy = ProductMovementTypeOrder.buildOrderBy("-etgoDocKind,-movementDate,-id", ES_ORDER);
    assertNotNull(orderBy);
    assertEquals(7, rankOf(orderBy, "goods-receipt", "5", "V+"));
    assertEquals(0, rankOf(orderBy, "physical-inventory", "-2", "I-"));
    assertTrue(orderBy.endsWith(" asc, e.movementDate desc, e.id desc"), orderBy);
  }

  /** Rows with no label sort last in both directions; a bucket the client did not list sorts before them. */
  @Test
  void typeOrder_unlabeledRowsAreLast_inBothDirections() {
    String partial = "vendor-receipt,vendor-return";
    for (String sort : new String[] { "etgoDocKind,id", "-etgoDocKind,id" }) {
      String orderBy = ProductMovementTypeOrder.buildOrderBy(sort, partial);
      int listed = rankOf(orderBy, "goods-receipt", "5", "V+");
      int unlisted = rankOf(orderBy, "goods-movements", "1", "M+");
      int unlabeled = rankOf(orderBy, null, "4", "P+");
      assertTrue(listed < unlisted && unlisted < unlabeled, sort + ": " + listed + "," + unlisted + "," + unlabeled);
    }
  }

  /** Without the parameter, with an empty one, or for any other sort field the default order stands. */
  @Test
  void typeOrder_notApplicable_returnsNull() {
    assertNull(ProductMovementTypeOrder.buildOrderBy("etgoDocKind,-movementDate,-id", null));
    assertNull(ProductMovementTypeOrder.buildOrderBy("etgoDocKind,-movementDate,-id", ""));
    assertNull(ProductMovementTypeOrder.buildOrderBy("movementQuantity,-id", ES_ORDER));
    assertNull(ProductMovementTypeOrder.buildOrderBy("-movementDate,-id", ES_ORDER));
    assertNull(ProductMovementTypeOrder.buildOrderBy(null, ES_ORDER));
    assertNull(ProductMovementTypeOrder.buildOrderBy(" ", ES_ORDER));
  }

  /** Unknown ids are ignored; request text never reaches the HQL, only allow-list members do. */
  @Test
  void typeOrder_unknownAndMaliciousIds_areIgnoredNeverConcatenated() {
    String hostile = "vendor-receipt';delete from m_transaction;--, 1) or (1=1, ,vendor-return,vendor-return,<script>";
    assertEquals(List.of("vendor-return"), ProductMovementTypeOrder.parseOrder(hostile));
    String orderBy = ProductMovementTypeOrder.buildOrderBy("etgoDocKind,id", hostile);
    assertNotNull(orderBy);
    assertFalse(orderBy.contains("delete"));
    assertFalse(orderBy.contains("script"));
    assertFalse(orderBy.contains("1=1"));
    // only hostile ids: nothing valid to order by, so the default order stands
    assertNull(ProductMovementTypeOrder.buildOrderBy("etgoDocKind,id", "x';drop table a;--,y"));
  }

  /** A tie-breaker outside the allow-list makes the request decline: nothing client-chosen reaches the HQL. */
  @Test
  void typeOrder_unknownTieBreaker_declines() {
    assertNull(ProductMovementTypeOrder.buildOrderBy("etgoDocKind,e.id;drop table x", ES_ORDER));
    assertNull(ProductMovementTypeOrder.buildOrderBy("etgoDocKind,-product", ES_ORDER));
  }

  /** A sort with no id tie-breaker still ends in the id: pages never overlap. */
  @Test
  void typeOrder_withoutIdTieBreaker_appendsTheId() {
    String orderBy = ProductMovementTypeOrder.buildOrderBy("etgoDocKind,-movementDate", ES_ORDER);
    assertTrue(orderBy.endsWith(", e.movementDate desc, e.id asc"), orderBy);
  }

  /** The hook asks the filtered set for exactly the requested page, in the built order, plus the total. */
  @Test
  void orderedPage_productSpec_readsTheRequestedPageOfTheFilteredSet() {
    NeoListAggregateQuery set = mock(NeoListAggregateQuery.class);
    when(set.orderedIds(anyString(), eq(50), eq(50))).thenReturn(List.of("A", "B"));
    when(set.count()).thenReturn(123);
    NeoContext ctx = productCtx(Map.of("parentId", "P1", "_sortBy", "-etgoDocKind,-movementDate,-id",
        "typeLabelOrder", ES_ORDER));

    NeoOrderedPage page = HANDLER.orderedPage(ctx, set, 50, 50);

    assertNotNull(page);
    assertEquals(List.of("A", "B"), page.getIds());
    assertEquals(123, page.getTotalRows());
    verify(set).orderedIds(contains("e.movementDate desc"), eq(50), eq(50));
  }

  /** No parameter: the hook declines and runs no query, so every other caller keeps the current sort. */
  @Test
  void orderedPage_withoutTheParameter_declinesAndRunsNothing() {
    NeoListAggregateQuery set = mock(NeoListAggregateQuery.class);
    NeoContext ctx = productCtx(Map.of("parentId", "P1", "_sortBy", "etgoDocKind,-movementDate,-id"));

    assertNull(HANDLER.orderedPage(ctx, set, 0, 50));
    verify(set, never()).orderedIds(anyString(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.anyInt());
    verify(set, never()).count();
  }

  /** The flag off, or another window sharing the handler: the default order, nothing queried. */
  @Test
  void orderedPage_flagOffOrWarehouseSpec_declines() {
    NeoListAggregateQuery set = mock(NeoListAggregateQuery.class);
    Map<String, String> params = Map.of("_sortBy", "etgoDocKind,-id", "typeLabelOrder", ES_ORDER);
    policy.when(ProductMovementsPolicy::isEnabled).thenReturn(false);
    assertNull(HANDLER.orderedPage(productCtx(params), set, 0, 50));
    policy.when(ProductMovementsPolicy::isEnabled).thenReturn(true);
    NeoContext warehouse = NeoContext.builder().specName("warehouse").entityName("productTransactions")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).queryParams(params).build();
    assertNull(HANDLER.orderedPage(warehouse, set, 0, 50));
    verify(set, never()).count();
  }

  /**
   * The return rows as the real data stores them (not as the sign rule alone would guess): a customer
   * return is {@code C-} with a POSITIVE quantity, a vendor return is {@code V+} with a NEGATIVE one;
   * the document kind decides, so the odd movement type and sign do not move the row to another bucket.
   * Kept apart from the shared table, which mirrors the client's and must not change here alone.
   */
  @Test
  void typeOrder_realReturnRows_followTheirDocumentKind() {
    String expression = ProductMovementTypeOrder.bucketCase(quoted());
    assertEquals("'customer-return'", evalCase(expression, "return-material-receipt", "3", "C-"));
    assertEquals("'customer-return'", evalCase(expression, "return-material-receipt", "-3", "C-"));
    assertEquals("'vendor-return'", evalCase(expression, "return-to-vendor-shipment", "-3", "V+"));
    assertEquals("'vendor-return'", evalCase(expression, "return-to-vendor-shipment", "3", "V+"));
    assertEquals("'internal-consumption'", evalCase(expression, "internal-consumption", "-3", "D-"));
    // a plain shipment with the same C- code is NOT a return
    assertEquals("'customer-shipment'", evalCase(expression, "goods-shipment", "-3", "C-"));
  }

  /** A repeated id counts once: the ranks stay dense and no listed bucket falls behind the unlisted ones. */
  @Test
  void typeOrder_duplicateIdsInTheOrder_countOnce() {
    assertEquals(List.of("vendor-return", "vendor-receipt"),
        ProductMovementTypeOrder.parseOrder("vendor-return,vendor-return,vendor-receipt,vendor-return"));
    String orderBy = ProductMovementTypeOrder.buildOrderBy("etgoDocKind,id",
        "vendor-return,vendor-return,vendor-receipt");
    assertEquals(0, rankOf(orderBy, "return-to-vendor-shipment", "-1", "V-"));
    assertEquals(1, rankOf(orderBy, "goods-receipt", "1", "V+"));
    assertEquals(2, rankOf(orderBy, "goods-movements", "1", "M+"));
    assertEquals(3, rankOf(orderBy, null, "1", "P+"));
  }

  /** Blank, whitespace-only or separator-only values are an absent order: default sort. */
  @Test
  void typeOrder_blankOrder_declines() {
    for (String blank : new String[] { " ", ",", " , ,", "\t" }) {
      assertNull(ProductMovementTypeOrder.buildOrderBy("etgoDocKind,id", blank), "[" + blank + "]");
    }
    assertTrue(ProductMovementTypeOrder.parseOrder(null).isEmpty());
  }

  /** Ids with padding are trimmed; ids with different case or inner text are not buckets. */
  @Test
  void typeOrder_parseOrder_trimsButDoesNotGuess() {
    assertEquals(List.of("transfer", "vendor-receipt"),
        ProductMovementTypeOrder.parseOrder(" transfer , Vendor-Receipt,vendor-receipt "));
  }

  /**
   * No HQL injection: whatever the request carries, the generated order-by is made only of the
   * allow-listed property paths, the bucket/kind/code constants, integers computed here and keywords.
   */
  @Test
  void typeOrder_generatedHql_containsOnlyAllowListedTokens() {
    String hostile = "vendor-receipt,'; drop table a;--,transfer\") or 1=1";
    String orderBy = ProductMovementTypeOrder.buildOrderBy(
        "-etgoDocKind,-movementDate,transactionProcessDate,id", hostile);
    assertNotNull(orderBy);
    java.util.Set<String> literals = new java.util.HashSet<>(List.of("goods-receipt", "return-to-vendor-shipment",
        "goods-shipment", "return-material-receipt", "goods-movements", "internal-consumption",
        "physical-inventory", "V+", "V-", "C-", "C+", "I+", "I-", "M+", "M-", "D+", "D-"));
    Matcher quotedLiteral = Pattern.compile("'([^']*)'").matcher(orderBy);
    while (quotedLiteral.find()) {
      assertTrue(literals.contains(quotedLiteral.group(1)), "unexpected literal: " + quotedLiteral.group(1));
    }
    String rest = orderBy.replaceAll("'[^']*'", "");
    java.util.Set<String> words = new java.util.HashSet<>(List.of("case", "when", "then", "else", "end", "asc",
        "desc", "e.etgoDocKind", "e.movementQuantity", "e.movementType", "e.movementDate",
        "e.transactionProcessDate", "e.id"));
    Matcher token = Pattern.compile("[A-Za-z_.]+|\\d+|[(),<>=]|\\s+|.").matcher(rest);
    while (token.find()) {
      String t = token.group();
      assertTrue(t.isBlank() || t.matches("\\d+") || t.matches("[(),<>=]") || words.contains(t),
          "unexpected token: [" + t + "] in " + orderBy);
    }
  }

  /** An allow-listed tie-breaker may be descending, may be the id alone, and the id is never repeated. */
  @Test
  void typeOrder_tieBreakers_areAllowListedAndIdIsNotRepeated() {
    String orderBy = ProductMovementTypeOrder.buildOrderBy("etgoDocKind,-id", ES_ORDER);
    assertTrue(orderBy.endsWith(" asc, e.id desc"), orderBy);
    assertEquals(1, orderBy.split("e\\.id", -1).length - 1);
    assertTrue(ProductMovementTypeOrder.buildOrderBy("etgoDocKind", ES_ORDER).endsWith(" asc, e.id asc"));
    assertNull(ProductMovementTypeOrder.buildOrderBy("etgoDocKind,id,product", ES_ORDER));
  }

  /** An unallowed tie-breaker makes the hook decline (200, default order) and run no query. */
  @Test
  void orderedPage_unallowedTieBreaker_declinesWithoutQuerying() {
    NeoListAggregateQuery set = mock(NeoListAggregateQuery.class);
    NeoContext ctx = productCtx(Map.of("_sortBy", "etgoDocKind,-product", "typeLabelOrder", ES_ORDER));
    assertNull(HANDLER.orderedPage(ctx, set, 0, 50));
    verify(set, never()).count();
  }

  /** A context with no query params at all is the default order. */
  @Test
  void orderedPage_withoutQueryParams_declines() {
    NeoListAggregateQuery set = mock(NeoListAggregateQuery.class);
    assertNull(HANDLER.orderedPage(productCtx(null), set, 0, 50));
    verify(set, never()).count();
  }
}
