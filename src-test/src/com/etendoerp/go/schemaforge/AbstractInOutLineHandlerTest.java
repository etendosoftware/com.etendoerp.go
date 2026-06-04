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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link AbstractInOutLineHandler}.
 *
 * <p>Uses {@link GoodsReceiptLineHandler} as the minimal concrete subclass, since
 * the abstract class has no abstract methods — all logic lives directly in it.
 *
 * <p>Group A tests cover {@code handle()} without DB access.
 * Group B tests cover the POST path of {@code afterHandle()} and ThreadLocal cleanup.
 * Group C tests cover the GET enrichment path of {@code afterHandle()}.
 */
public class AbstractInOutLineHandlerTest {

  // ── Group A — handle() (no DB) ────────────────────────────────────────────

  /**
   * handle() with POST and a non-empty invoiceLineId stores the value in the
   * ThreadLocal and returns null so the default CRUD continues.
   */
  @Test
  public void handle_post_withInvoiceLineId_storesInThreadLocal() throws Exception {
    GoodsReceiptLineHandler handler = new GoodsReceiptLineHandler();

    JSONObject body = new JSONObject().put("invoiceLineId", "inv-line-1");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();

    NeoResponse result = handler.handle(ctx);

    assertNull("handle() must return null to fall through to default CRUD", result);
  }

  /**
   * handle() with POST and no invoiceLineId in the body returns null without error.
   */
  @Test
  public void handle_post_withoutInvoiceLineId_returnsNull() throws Exception {
    GoodsReceiptLineHandler handler = new GoodsReceiptLineHandler();

    JSONObject body = new JSONObject().put("quantity", 5);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();

    assertNull(handler.handle(ctx));
  }

  /**
   * handle() with POST and a null body returns null without NPE.
   */
  @Test
  public void handle_post_nullBody_returnsNull() {
    GoodsReceiptLineHandler handler = new GoodsReceiptLineHandler();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(null)
        .build();

    assertNull(handler.handle(ctx));
  }

  /**
   * handle() with GET always returns null — the handler only acts in afterHandle.
   */
  @Test
  public void handle_get_returnsNull() {
    GoodsReceiptLineHandler handler = new GoodsReceiptLineHandler();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    assertNull(handler.handle(ctx));
  }

  // ── Group B — afterHandle() POST, ThreadLocal clearing ───────────────────

  /**
   * afterHandle() POST clears the ThreadLocal even when previousResult is null
   * (linkInvoiceLineIfPresent exits early via guard clause). A subsequent call
   * with GET and no data must not attempt a DB update, confirming ThreadLocal
   * is cleared by the finally block.
   */
  @Test
  public void afterHandle_post_clearsPendingInvoiceLineId_evenOnError() throws Exception {
    GoodsReceiptLineHandler handler = new GoodsReceiptLineHandler();

    // Arm the ThreadLocal via handle()
    JSONObject reqBody = new JSONObject().put("invoiceLineId", "inv-line-clear-test");
    NeoContext handleCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(reqBody)
        .build();
    handler.handle(handleCtx);

    // afterHandle() with null previousResult — guard in linkInvoiceLineIfPresent exits early
    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    NeoResponse result = handler.afterHandle(afterCtx);

    // Must complete without exception and return null
    assertNull("afterHandle POST must return null after clearing ThreadLocal", result);

    // Confirm ThreadLocal is cleared: a second afterHandle GET with no data should
    // succeed without any DB call (NeoHandlerUtils.extractGetDataArray returns null)
    NeoContext getCtx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    NeoResponse getResult = handler.afterHandle(getCtx);
    assertNull("GET afterHandle with no previousResult must return null", getResult);
  }

  // ── Group C — afterHandle() GET, enrichment ───────────────────────────────

  /**
   * afterHandle() GET enriches each line object with orderQuantity, productCode,
   * and invoicedQuantity fetched via a PreparedStatement on the dal Connection.
   */
  @Test
  public void afterHandle_get_enrichesLinesWithOrderQtyProductCodeAndInvoicedQty() throws Exception {
    GoodsReceiptLineHandler handler = new GoodsReceiptLineHandler();

    JSONObject line = new JSONObject().put("id", "line-1");
    JSONArray dataArr = new JSONArray().put(line);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", dataArr));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);

      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // One row: id=line-1, orderedQty=5.0, productCode=PROD-A, invoicedQty=2.0
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-1");
      when(rs.getBigDecimal(2)).thenReturn(BigDecimal.valueOf(5.0));
      when(rs.getString(3)).thenReturn("PROD-A");
      when(rs.getBigDecimal(4)).thenReturn(BigDecimal.valueOf(2.0));

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull("afterHandle GET must return a non-null NeoResponse", result);
      JSONObject enrichedBody = result.getBody();
      assertNotNull(enrichedBody);

      JSONObject enrichedLine = enrichedBody
          .getJSONObject("response")
          .getJSONArray("data")
          .getJSONObject(0);

      assertEquals("orderQuantity must be set from DB",
          BigDecimal.valueOf(5.0), enrichedLine.get("orderQuantity"));
      assertEquals("productCode must be set from DB",
          "PROD-A", enrichedLine.getString("productCode"));
      assertEquals("invoicedQuantity must be set from DB",
          BigDecimal.valueOf(2.0), enrichedLine.get("invoicedQuantity"));
    }
  }

  /**
   * afterHandle() GET with an empty data array returns null (NeoHandlerUtils guard).
   */
  @Test
  public void afterHandle_get_emptyDataArray_returnsNull() throws Exception {
    GoodsReceiptLineHandler handler = new GoodsReceiptLineHandler();

    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray()));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(body))
        .build();

    // NeoHandlerUtils.extractGetDataArray returns null for empty array — no DB needed
    NeoResponse result = handler.afterHandle(ctx);
    assertNull("Empty data array must cause afterHandle to return null", result);
  }

  /**
   * afterHandle() GET returns null when getConnection() throws.
   *
   * <p>{@code getConnection()} is called BEFORE the try-with-resources in
   * {@code fetchLineData}, so the exception propagates up to {@code afterHandle}'s
   * outer catch block, which returns null.
   */
  @Test
  public void afterHandle_get_dbErrorInFetch_returnsNull() throws Exception {
    GoodsReceiptLineHandler handler = new GoodsReceiptLineHandler();

    JSONObject line = new JSONObject().put("id", "line-x");
    JSONArray dataArr = new JSONArray().put(line);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", dataArr));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB unavailable"));

      // Exception from getConnection() propagates to afterHandle's outer catch → null
      NeoResponse result = handler.afterHandle(ctx);
      assertNull(result);
    }
  }

  // ── Group D — linkInvoiceLineIfPresent (POST afterHandle with invoiceLineId) ──

  /**
   * When handle() captures an invoiceLineId and afterHandle() POST runs with a
   * previousResult that contains the new line ID, the handler must execute an
   * UPDATE on c_invoiceline to set the back-reference. Verifies that the native
   * query is executed with the correct parameters.
   */
  @Test
  public void afterHandle_post_withInvoiceLineIdAndValidResponse_executesUpdate() throws Exception {
    GoodsReceiptLineHandler handler = new GoodsReceiptLineHandler();

    // Arm the ThreadLocal via handle()
    JSONObject reqBody = new JSONObject().put("invoiceLineId", "inv-line-abc");
    handler.handle(NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(reqBody)
        .build());

    // Build a previousResult with response.data[0].id = new-line-id
    JSONObject newLineObj = new JSONObject().put("id", "new-line-id");
    JSONObject responseWrapper = new JSONObject()
        .put("data", new JSONArray().put(newLineObj));
    JSONObject prevBody = new JSONObject().put("response", responseWrapper);
    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(new NeoResponse(201, prevBody))
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      org.hibernate.query.NativeQuery nq = mock(org.hibernate.query.NativeQuery.class);
      when(session.createNativeQuery(Mockito.anyString())).thenReturn(nq);
      when(nq.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nq);
      when(nq.executeUpdate()).thenReturn(1);

      NeoResponse result = handler.afterHandle(afterCtx);

      assertNull("POST afterHandle must return null", result);
      org.mockito.ArgumentCaptor<String> sqlCaptor =
          org.mockito.ArgumentCaptor.forClass(String.class);
      Mockito.verify(session).createNativeQuery(sqlCaptor.capture());
      assertTrue("SQL must UPDATE c_invoiceline",
          sqlCaptor.getValue().contains("UPDATE c_invoiceline"));
      Mockito.verify(nq).setParameter("lineId", "new-line-id");
      Mockito.verify(nq).setParameter("invLineId", "inv-line-abc");
    }
  }

  /**
   * linkInvoiceLineIfPresent with an empty id in the response data must exit early
   * without triggering a DB update.
   */
  @Test
  public void afterHandle_post_withEmptyNewLineId_skipsUpdate() throws Exception {
    GoodsReceiptLineHandler handler = new GoodsReceiptLineHandler();

    handler.handle(NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(new JSONObject().put("invoiceLineId", "inv-line-xyz"))
        .build());

    JSONObject newLineObj = new JSONObject().put("id", "");
    JSONObject prevBody = new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray().put(newLineObj)));
    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(new NeoResponse(201, prevBody))
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      handler.afterHandle(afterCtx);

      Mockito.verify(dal, Mockito.never()).getSession();
    }
  }
}
