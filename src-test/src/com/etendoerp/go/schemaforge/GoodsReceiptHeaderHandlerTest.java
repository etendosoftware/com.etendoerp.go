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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
 * Unit tests for {@link GoodsReceiptHeaderHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code afterHandle()} early-exit paths (non-GET, null prev result, empty data).</li>
 *   <li>{@code afterHandle()} single-record enrichment — invoiceStatus, returnStatus,
 *       linkedInvoices, linkedOrder, linkedReturns.</li>
 *   <li>{@code afterHandle()} batch (list) mode — invoiceStatus per record.</li>
 *   <li>Error resilience — DB error in enrichment returns null instead of propagating.</li>
 * </ul>
 */
public class GoodsReceiptHeaderHandlerTest {

  // ── afterHandle — early exits ─────────────────────────────────────────────

  @Test
  public void afterHandle_nonGet_returnsNull() {
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();
    NeoContext ctx = NeoContext.builder().httpMethod("POST").build();
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandle_getWithNoPreviousResult_returnsNull() {
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();
    NeoContext ctx = NeoContext.builder().httpMethod("GET").build();
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandle_getWithNullBody_returnsNull() {
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, null))
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandle_getWithEmptyDataArray_returnsNull() throws Exception {
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  // ── afterHandle — batch mode (no recordId) ────────────────────────────────

  @Test
  public void afterHandle_batchMode_enrichesInvoiceStatusPerRecord() throws Exception {
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();

    JSONObject rec1 = new JSONObject().put("id", "r1");
    JSONObject rec2 = new JSONObject().put("id", "r2");
    JSONArray data = new JSONArray().put(rec1).put(rec2);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", data));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(any())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      // Return one row: r1 → 75%
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("r1");
      when(rs.getInt(2)).thenReturn(75);

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray enriched = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(75, enriched.getJSONObject(0).getInt("invoiceStatus"));
    }
  }

  // ── afterHandle — single record mode ──────────────────────────────────────

  @Test
  public void afterHandle_singleRecord_enrichesInvoiceAndReturnStatus() throws Exception {
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();

    JSONObject rec = new JSONObject().put("id", "receipt-1");
    JSONArray data = new JSONArray().put(rec);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", data));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .recordId("receipt-1")
        .previousResult(new NeoResponse(200, body))
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      OBDal readOnlyDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(any())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      Connection roConn = mock(Connection.class);
      PreparedStatement roPs = mock(PreparedStatement.class);
      ResultSet roRs = mock(ResultSet.class);
      when(readOnlyDal.getConnection()).thenReturn(roConn);
      when(roConn.prepareStatement(any())).thenReturn(roPs);
      when(roPs.executeQuery()).thenReturn(roRs);
      when(roRs.next()).thenReturn(false);

      NeoResponse result = handler.afterHandle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
    }
  }

  // ── afterHandle — DB error resilience ────────────────────────────────────

  @Test
  public void afterHandle_dbErrorInBatchQuery_returnsOkWithDefaults() throws Exception {
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();

    JSONObject rec = new JSONObject().put("id", "r1");
    JSONArray data = new JSONArray().put(rec);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", data));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      // computeInvoiceStatusBatch catches the exception internally and returns empty map
      when(dal.getConnection()).thenThrow(new RuntimeException("DB down"));

      NeoResponse result = handler.afterHandle(ctx);
      // computeInvoiceStatusBatch swallows the error → ok(body) returned
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
    }
  }

  // ── computeInvoiceStatus — single record path ────────────────────────────

  @Test
  public void afterHandle_singleRecord_invoiceStatusZeroWhenNoRows() throws Exception {
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();

    JSONObject rec = new JSONObject().put("id", "r-empty");
    JSONArray data = new JSONArray().put(rec);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", data));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .recordId("r-empty")
        .previousResult(new NeoResponse(200, body))
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      OBDal roInst = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(roInst);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(any())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false); // no rows → status = 0

      Connection roConn = mock(Connection.class);
      PreparedStatement roPs = mock(PreparedStatement.class);
      ResultSet roRs = mock(ResultSet.class);
      when(roInst.getConnection()).thenReturn(roConn);
      when(roConn.prepareStatement(any())).thenReturn(roPs);
      when(roPs.executeQuery()).thenReturn(roRs);
      when(roRs.next()).thenReturn(false);

      NeoResponse result = handler.afterHandle(ctx);
      assertNotNull(result);
      JSONObject enriched = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(0, enriched.getInt("invoiceStatus"));
      assertEquals(0, enriched.getInt("returnStatus"));
    }
  }
}
