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

import java.math.BigDecimal;
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
 * Unit tests for {@link BinContentsHandler}.
 *
 * <p>Coverage goals:
 * <ul>
 *   <li>{@code handle()} always returns null (pass-through pre-hook).</li>
 *   <li>{@code afterHandle()} returns null when {@code extractGetDataArray} returns null.</li>
 *   <li>{@code afterHandle()} returns null when {@code previousResult} is null.</li>
 *   <li>{@code afterHandle()} returns null when {@code collectIds} returns an empty list.</li>
 *   <li>{@code afterHandle()} happy path: two rows; the row present in the SQL result gets
 *       {@code etgoValuation} injected, the row absent from the result is left unchanged.</li>
 *   <li>{@code afterHandle()} with a null valuation column ({@code row[1] == null}) → the row
 *       receives {@code BigDecimal.ZERO} (the handler's null-coercion rule).</li>
 *   <li>{@code afterHandle()} swallows any exception thrown inside {@code computeValuations}
 *       and returns {@code context.getPreviousResult()} unchanged (no rethrow).</li>
 * </ul>
 *
 * <p>Tests that require a live Etendo DB are covered by integration tests.
 */
class BinContentsHandlerTest {

  private static final BinContentsHandler HANDLER = new BinContentsHandler();

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Returns a GET/CRUD context targeting the binContents entity.
   */
  private static NeoContext getCtx() {
    return NeoContext.builder()
        .specName("warehouse")
        .entityName("binContents")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /**
   * Builds a standard response body wrapping the given rows inside
   * {@code { "response": { "data": [...] } }}.
   */
  private static JSONObject wrapData(JSONArray data) throws Exception {
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  /**
   * Creates a minimal storage-detail row JSON with the given id.
   */
  private static JSONObject sdRow(String id) throws Exception {
    return new JSONObject().put("id", id).put("qtyonhand", 10);
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
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * Verifies that {@code handle()} returns null even for POST/ACTION contexts.
   */
  @Test
  void handle_alwaysReturnsNull_forPostContext() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .build();
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
    JSONArray data = new JSONArray().put(sdRow("SD-001"));
    JSONObject body = wrapData(data);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .build();
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
    JSONArray fakeData = new JSONArray().put(sdRow("SD-001"));

    try (MockedStatic<NeoHandlerUtils> utils = Mockito.mockStatic(NeoHandlerUtils.class)) {
      utils.when(() -> NeoHandlerUtils.extractGetDataArray(Mockito.any())).thenReturn(fakeData);
      // collectIds is not reached; previousResult stays null
      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .build();
      // no setPreviousResult → getPreviousResult() == null

      assertNull(HANDLER.afterHandle(ctx));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — guard: collectIds returns empty list
  // ---------------------------------------------------------------------------

  /**
   * When every record in the data array has no {@code id} field, {@code collectIds}
   * returns an empty list and {@code afterHandle} returns null without hitting the DB.
   */
  @Test
  void afterHandle_noIdsInRows_returnsNull() throws Exception {
    // Row with no "id" key
    JSONObject rowNoId = new JSONObject().put("qtyonhand", 10);
    JSONArray data = new JSONArray().put(rowNoId);
    JSONObject body = wrapData(data);

    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    // No OBDal mock needed — the handler returns null before querying the DB
    assertNull(HANDLER.afterHandle(ctx),
        "afterHandle must return null when no storage detail ids can be collected");
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — happy path: two rows, one resolved, one not in result map
  // ---------------------------------------------------------------------------

  /**
   * Happy-path test: data array with two rows.
   * The first row (SD-001) is resolved by the SQL query with a known valuation.
   * The second row (SD-002) has no entry in the result map.
   * Asserts that only SD-001 receives {@code etgoValuation}; SD-002 is left unchanged.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_happyPath_enrichesResolvedRowAndLeavesUnresolvedIntact() throws Exception {
    JSONArray data = new JSONArray()
        .put(sdRow("SD-001"))
        .put(sdRow("SD-002"));
    JSONObject body = wrapData(data);

    // SQL returns one row: SD-001 → valuation 250.50
    Object[] sqlRow = new Object[]{ "SD-001", new BigDecimal("250.50") };

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

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

      // Row 0 (SD-001) — must have etgoValuation injected
      JSONObject row0 = resultData.getJSONObject(0);
      assertTrue(row0.has("etgoValuation"), "SD-001 must have etgoValuation set");
      assertEquals(new BigDecimal("250.50"), new BigDecimal(row0.getString("etgoValuation")),
          "SD-001 etgoValuation must match the SQL result");

      // Row 1 (SD-002) — no SQL result → must NOT have etgoValuation
      JSONObject row1 = resultData.getJSONObject(1);
      assertFalse(row1.has("etgoValuation"), "SD-002 must NOT have etgoValuation when absent from SQL result");
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — null valuation in SQL result → BigDecimal.ZERO
  // ---------------------------------------------------------------------------

  /**
   * When the SQL result row carries a null valuation column ({@code row[1] == null}),
   * the handler must coerce it to {@code BigDecimal.ZERO} and inject that value.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_nullValuationInResult_injectsZero() throws Exception {
    JSONArray data = new JSONArray().put(sdRow("SD-003"));
    JSONObject body = wrapData(data);

    // SQL returns: valuation column is null (product has no M_Costing row)
    Object[] sqlRow = new Object[]{ "SD-003", null };

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);
      mockNativeQuery(session, Collections.singletonList(sqlRow));

      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject row = result.getBody()
          .getJSONObject("response")
          .getJSONArray("data")
          .getJSONObject(0);

      assertTrue(row.has("etgoValuation"), "etgoValuation must be present even when SQL column is null");
      assertEquals(BigDecimal.ZERO.toPlainString(),
          new BigDecimal(row.getString("etgoValuation")).toPlainString(),
          "Null valuation must be coerced to BigDecimal.ZERO");
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — exception inside computeValuations is swallowed
  // ---------------------------------------------------------------------------

  /**
   * Verifies that when {@code createNativeQuery} throws a {@link RuntimeException}
   * inside {@code computeValuations}, {@code afterHandle} catches it, logs the
   * error, and returns {@code context.getPreviousResult()} without rethrowing.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_exceptionInComputeValuations_returnsOriginalPreviousResult() throws Exception {
    JSONArray data = new JSONArray().put(sdRow("SD-ERR"));
    JSONObject body = wrapData(data);

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

      OBDal mockDal = mock(OBDal.class);
      Session session = mock(Session.class);
      when(mockDal.getSession()).thenReturn(session);
      obDal.when(OBDal::getInstance).thenReturn(mockDal);

      // createNativeQuery throws to simulate a DB failure
      when(session.createNativeQuery(anyString()))
          .thenThrow(new RuntimeException("simulated DB failure"));

      NeoContext ctx = getCtx();
      NeoResponse original = NeoResponse.ok(body);
      ctx.setPreviousResult(original);

      NeoResponse result = assertDoesNotThrow(() -> HANDLER.afterHandle(ctx),
          "afterHandle must not rethrow any exception");

      // The handler must return the original previousResult on exception
      assertSame(original, result,
          "afterHandle must return context.getPreviousResult() when computeValuations fails");
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — multiple rows all resolved (verifies batch coverage)
  // ---------------------------------------------------------------------------

  /**
   * Verifies that a batch of three storage detail rows are all enriched correctly
   * when the SQL returns a valuation for each id.
   */
  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_multipleRowsAllResolved_allEnriched() throws Exception {
    JSONArray data = new JSONArray()
        .put(sdRow("SD-A"))
        .put(sdRow("SD-B"))
        .put(sdRow("SD-C"));
    JSONObject body = wrapData(data);

    List<Object[]> sqlRows = Arrays.asList(
        new Object[]{ "SD-A", new BigDecimal("100.00") },
        new Object[]{ "SD-B", new BigDecimal("200.00") },
        new Object[]{ "SD-C", new BigDecimal("300.00") }
    );

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

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

      String[][] expected = {
          { "SD-A", "100.00" },
          { "SD-B", "200.00" },
          { "SD-C", "300.00" }
      };
      for (int i = 0; i < expected.length; i++) {
        JSONObject row = resultData.getJSONObject(i);
        assertTrue(row.has("etgoValuation"), "Row " + i + " must have etgoValuation");
        assertEquals(new BigDecimal(expected[i][1]),
            new BigDecimal(row.getString("etgoValuation")),
            "Row " + i + " etgoValuation must match");
      }
    }
  }
}
