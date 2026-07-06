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
 * Unit tests for {@link PaymentScheduleDetailHandler}.
 *
 * <p>Coverage goals:
 * <ul>
 *   <li>{@code handle()} always returns null (pass-through pre-hook), regardless
 *       of HTTP method.</li>
 *   <li>{@code afterHandle()} returns null when {@code extractGetDataArray}/
 *       {@code previousResult} guards are hit (no previous result, null body,
 *       empty data array, non-GET method).</li>
 *   <li>{@code afterHandle()} returns null when every row is missing an
 *       {@code id} (i.e. {@code collectIds} returns an empty list) without
 *       hitting the DB.</li>
 *   <li>{@code afterHandle()} happy path: a row backed by an invoice-linked
 *       schedule gets {@code invoiceDocumentNo} populated from the invoice's
 *       document number.</li>
 *   <li>{@code afterHandle()} happy path: a row backed by an order-linked
 *       schedule gets {@code invoiceDocumentNo} populated from the order's
 *       document number.</li>
 *   <li>{@code afterHandle()}: a row with no matching schedule (SQL returns a
 *       null {@code doc_no}, or no row at all for that id) is left unchanged —
 *       {@code invoiceDocumentNo} is never added, not even as an empty string.</li>
 *   <li>{@code afterHandle()}: a batch of multiple rows is enriched
 *       independently per-row from the single batched query result.</li>
 *   <li>{@code afterHandle()} swallows any exception thrown while fetching
 *       document numbers and returns {@code context.getPreviousResult()}
 *       unchanged (no rethrow).</li>
 * </ul>
 *
 * <p>The join logic that distinguishes invoice-linked vs. order-linked schedules
 * lives entirely inside the native SQL string and cannot be exercised without a
 * live database; that is covered by integration tests. These unit tests instead
 * pin the handler's contract given whatever rows the (mocked) query returns.
 */
class PaymentScheduleDetailHandlerTest {

  private static final PaymentScheduleDetailHandler HANDLER = new PaymentScheduleDetailHandler();

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Returns a GET/CRUD context targeting the payment schedule detail lines entity.
   */
  private static NeoContext getCtx() {
    return NeoContext.builder()
        .specName("payment-out")
        .entityName("lines")
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
   * Creates a minimal payment schedule detail row JSON with the given id.
   */
  private static JSONObject psdRow(String id) throws Exception {
    return new JSONObject().put("id", id).put("amount", 100);
  }

  /**
   * Mocks the Hibernate {@link NativeQuery} on the given {@link Session} to return
   * the provided list of {@code Object[]} rows (detail_id, doc_no) when
   * {@code list()} is called.
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
  // handle() — always null (edge case 4)
  // ---------------------------------------------------------------------------

  @Test
  void handle_alwaysReturnsNull_forGetContext() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  @Test
  void handle_alwaysReturnsNull_forPostContext() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — guards (edge case 5)
  // ---------------------------------------------------------------------------

  @Test
  void afterHandle_returnsNull_whenNoPreviousResult() {
    NeoContext ctx = getCtx();
    // no previousResult set -> extractGetDataArray returns null
    assertNull(HANDLER.afterHandle(ctx));
  }

  @Test
  void afterHandle_returnsNull_whenBodyIsNull() {
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(HANDLER.afterHandle(ctx));
  }

  @Test
  void afterHandle_returnsNull_whenDataArrayIsEmpty() throws Exception {
    JSONObject body = wrapData(new JSONArray());
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(HANDLER.afterHandle(ctx));
  }

  @Test
  void afterHandle_returnsNull_forNonGetMethod() throws Exception {
    JSONArray data = new JSONArray().put(psdRow("PSD-001"));
    JSONObject body = wrapData(data);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(HANDLER.afterHandle(ctx));
  }

  @Test
  void afterHandle_noIdsInRows_returnsNull() throws Exception {
    // Row with no "id" key
    JSONObject rowNoId = new JSONObject().put("amount", 100);
    JSONArray data = new JSONArray().put(rowNoId);
    JSONObject body = wrapData(data);

    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    // No OBDal mock needed -- the handler must return null before querying the DB
    assertNull(HANDLER.afterHandle(ctx),
        "afterHandle must return null when no detail ids can be collected");
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — happy path: invoice-linked schedule (edge case 1)
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_invoiceLinkedRow_injectsInvoiceDocumentNo() throws Exception {
    JSONArray data = new JSONArray().put(psdRow("PSD-INV-1"));
    JSONObject body = wrapData(data);

    // SQL returns the invoice's documentno for this detail (invoice-linked schedule)
    Object[] sqlRow = new Object[]{ "PSD-INV-1", "FRE-000123" };

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
      assertEquals(200, result.getHttpStatus());
      JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("FRE-000123", row.getString("invoiceDocumentNo"));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — happy path: order-linked schedule (edge case 2)
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_orderLinkedRow_injectsOrderDocumentNo() throws Exception {
    JSONArray data = new JSONArray().put(psdRow("PSD-ORD-1"));
    JSONObject body = wrapData(data);

    // SQL returns the order's documentno for this detail (invoice null, order-linked schedule)
    Object[] sqlRow = new Object[]{ "PSD-ORD-1", "PO-000456" };

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
      JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("PO-000456", row.getString("invoiceDocumentNo"));
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — no matching schedule: doc_no is null (edge case 3)
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_nullDocNoInResult_leavesRowUnchanged() throws Exception {
    JSONArray data = new JSONArray().put(psdRow("PSD-NONE-1"));
    JSONObject body = wrapData(data);

    // SQL returns a row for this id, but doc_no is null (no invoice, no order match)
    Object[] sqlRow = new Object[]{ "PSD-NONE-1", null };

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
      JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse(row.has("invoiceDocumentNo"),
          "Row with a null doc_no must NOT get invoiceDocumentNo added (not even as empty string)");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_idAbsentFromQueryResult_leavesRowUnchanged() throws Exception {
    // Two rows requested, but the SQL only returns a match for the first one
    JSONArray data = new JSONArray()
        .put(psdRow("PSD-MATCH"))
        .put(psdRow("PSD-NO-MATCH"));
    JSONObject body = wrapData(data);

    Object[] sqlRow = new Object[]{ "PSD-MATCH", "FRE-000999" };

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
      JSONArray resultData = result.getBody().getJSONObject("response").getJSONArray("data");

      JSONObject matched = resultData.getJSONObject(0);
      assertEquals("FRE-000999", matched.getString("invoiceDocumentNo"));

      JSONObject unmatched = resultData.getJSONObject(1);
      assertFalse(unmatched.has("invoiceDocumentNo"),
          "Row with no entry in the SQL result map must NOT get invoiceDocumentNo added");
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — multiple rows, each enriched independently (edge case 6)
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_multipleRows_eachEnrichedIndependentlyFromBatchedQuery() throws Exception {
    JSONArray data = new JSONArray()
        .put(psdRow("PSD-A"))
        .put(psdRow("PSD-B"))
        .put(psdRow("PSD-C"));
    JSONObject body = wrapData(data);

    List<Object[]> sqlRows = Arrays.asList(
        new Object[]{ "PSD-A", "FRE-000111" },
        new Object[]{ "PSD-B", "PO-000222" },
        new Object[]{ "PSD-C", null }
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

      assertEquals("FRE-000111", resultData.getJSONObject(0).getString("invoiceDocumentNo"));
      assertEquals("PO-000222", resultData.getJSONObject(1).getString("invoiceDocumentNo"));
      assertFalse(resultData.getJSONObject(2).has("invoiceDocumentNo"),
          "Row PSD-C (null doc_no) must not get invoiceDocumentNo added");
    }
  }

  // ---------------------------------------------------------------------------
  // afterHandle() — exception is swallowed, original previousResult returned
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void afterHandle_exceptionDuringFetch_returnsOriginalPreviousResult() throws Exception {
    JSONArray data = new JSONArray().put(psdRow("PSD-ERR"));
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

      assertSame(original, result,
          "afterHandle must return context.getPreviousResult() when fetchDocumentNos fails");
    }
  }
}
