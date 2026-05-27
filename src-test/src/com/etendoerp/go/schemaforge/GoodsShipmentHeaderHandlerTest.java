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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link GoodsShipmentHeaderHandler}.
 *
 * <p>Covers two responsibilities:
 * <ul>
 *   <li>{@code handle()} — routes ACTION requests to the right downstream handler
 *       (create draft invoice / clone record) or returns null when none matches.</li>
 *   <li>{@code afterHandle()} — guard conditions that short-circuit before DB access:
 *       non-GET method, missing previous result, null body, and empty data array.</li>
 * </ul>
 *
 * <p>Tests that require DB access (invoiceStatus computation, issuerOrg enrichment)
 * are not included here — those are covered by integration tests.
 */
public class GoodsShipmentHeaderHandlerTest {

  /**
   * Creates a {@link GoodsShipmentHeaderHandler} with its {@code @Inject} fields replaced by the
   * provided mocks via reflection, bypassing CDI in the unit-test context.
   */
  private static GoodsShipmentHeaderHandler handlerWithMocks(
      CreateDraftInvoiceHandler mockCreateDraftInvoice,
      NeoCloneRecordHandler mockClone) throws Exception {
    GoodsShipmentHeaderHandler handler = new GoodsShipmentHeaderHandler();
    Field invoiceField = GoodsShipmentHeaderHandler.class.getDeclaredField("createDraftInvoiceHandler");
    invoiceField.setAccessible(true);
    invoiceField.set(handler, mockCreateDraftInvoice);
    Field cloneField = GoodsShipmentHeaderHandler.class.getDeclaredField("neoCloneRecordHandler");
    cloneField.setAccessible(true);
    cloneField.set(handler, mockClone);
    return handler;
  }

  /**
   * Builds a GET/CRUD {@link NeoContext} targeting the goods-shipment header entity.
   */
  private static NeoContext getCtx() {
    return NeoContext.builder()
        .specName("goods-shipment")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  // ── handle() dispatch ──────────────────────────────────────────────────────

  /**
   * Verifies that handle returns the create-draft-invoice response when that handler matches.
   */
  @Test
  public void testHandleDispatchesToCreateDraftInvoiceHandler() throws Exception {
    CreateDraftInvoiceHandler mockInvoice = mock(CreateDraftInvoiceHandler.class);
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    GoodsShipmentHeaderHandler handler = handlerWithMocks(mockInvoice, mockClone);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createDraftInvoice")
        .build();
    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "createDraftInvoice"));
    when(mockInvoice.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
  }

  /**
   * Verifies that handle returns the clone response when the clone handler matches
   * and the invoice handler returns null first.
   */
  @Test
  public void testHandleDispatchesToNeoCloneRecordHandler() throws Exception {
    CreateDraftInvoiceHandler mockInvoice = mock(CreateDraftInvoiceHandler.class);
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    GoodsShipmentHeaderHandler handler = handlerWithMocks(mockInvoice, mockClone);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("cloneRecord")
        .build();
    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "cloneRecord"));
    when(mockInvoice.handle(ctx)).thenReturn(null);
    when(mockClone.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
  }

  /**
   * Verifies that handle returns null when no downstream handler matches the context.
   */
  @Test
  public void testHandleReturnsNullWhenNoHandlerMatches() throws Exception {
    CreateDraftInvoiceHandler mockInvoice = mock(CreateDraftInvoiceHandler.class);
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    GoodsShipmentHeaderHandler handler = handlerWithMocks(mockInvoice, mockClone);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    when(mockInvoice.handle(ctx)).thenReturn(null);
    when(mockClone.handle(ctx)).thenReturn(null);

    assertNull(handler.handle(ctx));
  }

  // ── afterHandle() guard conditions ────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for non-GET requests.
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .build();
    assertNull(new GoodsShipmentHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = getCtx();
    assertNull(new GoodsShipmentHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new GoodsShipmentHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new GoodsShipmentHeaderHandler().afterHandle(ctx));
  }

  // ── enrichReturnReceipts() ─────────────────────────────────────────────────

  /**
   * Builds a detail GET context with a non-null recordId, targeting the goods-shipment header.
   */
  private static NeoContext getDetailCtx(String recordId) {
    return NeoContext.builder()
        .specName("goods-shipment")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  /**
   * Builds a minimal single-record response body for the given shipment ID.
   */
  private static JSONObject shipmentBody(String id) throws Exception {
    JSONObject rec = new JSONObject().put("id", id).put("documentNo", "SH-" + id);
    JSONArray data = new JSONArray().put(rec);
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  /**
   * Verifies that enrichReturnReceipts injects a returnReceipts JSONArray with correct fields
   * when the SQL query returns rows for the shipment.
   */
  @Test
  public void testEnrichReturnReceiptsInjectsArrayWhenRowsFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      // First prepareStatement call is for computeSingle (invoiceStatus),
      // second is for enrichReturnReceipts. Mock both.
      PreparedStatement psSingle = mock(PreparedStatement.class);
      PreparedStatement psReturn = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(psSingle)
          .thenReturn(psReturn);

      // computeSingle result — returns 0 rows so invoiceStatus = 0
      ResultSet rsSingle = mock(ResultSet.class);
      when(psSingle.executeQuery()).thenReturn(rsSingle);
      when(rsSingle.next()).thenReturn(false);

      // enrichReturnReceipts result — one return receipt row
      ResultSet rsReturn = mock(ResultSet.class);
      when(psReturn.executeQuery()).thenReturn(rsReturn);
      when(rsReturn.next()).thenReturn(true, false);
      when(rsReturn.getString(1)).thenReturn("ret-001");
      when(rsReturn.getString(2)).thenReturn("RRET-001");
      when(rsReturn.getString(3)).thenReturn("CO");

      JSONObject body = shipmentBody("sh-1");
      NeoContext ctx = getDetailCtx("sh-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new GoodsShipmentHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      JSONArray returnReceipts = rec.getJSONArray("returnReceipts");
      assertEquals(1, returnReceipts.length());
      JSONObject rr = returnReceipts.getJSONObject(0);
      assertEquals("ret-001", rr.getString("id"));
      assertEquals("RRET-001", rr.getString("documentNo"));
      assertEquals("CO", rr.getString("documentStatus"));
    }
  }

  /**
   * Verifies that enrichReturnReceipts injects an empty returnReceipts array (not absent)
   * when the SQL query returns no rows.
   */
  @Test
  public void testEnrichReturnReceiptsInjectsEmptyArrayWhenNoRowsFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement psSingle = mock(PreparedStatement.class);
      PreparedStatement psReturn = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(psSingle)
          .thenReturn(psReturn);

      ResultSet rsSingle = mock(ResultSet.class);
      when(psSingle.executeQuery()).thenReturn(rsSingle);
      when(rsSingle.next()).thenReturn(false);

      ResultSet rsReturn = mock(ResultSet.class);
      when(psReturn.executeQuery()).thenReturn(rsReturn);
      when(rsReturn.next()).thenReturn(false);

      JSONObject body = shipmentBody("sh-2");
      NeoContext ctx = getDetailCtx("sh-2");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new GoodsShipmentHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      // returnReceipts must be present even when empty
      JSONArray returnReceipts = rec.getJSONArray("returnReceipts");
      assertEquals(0, returnReceipts.length());
    }
  }

  /**
   * Verifies that enrichReturnReceipts is NOT called (returnReceipts absent) for list GET
   * requests where recordId is null.
   */
  @Test
  public void testEnrichReturnReceiptsNotCalledForListView() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      // Only one PS call expected — the batch invoiceStatus query
      PreparedStatement psBatch = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(psBatch);
      ResultSet rsBatch = mock(ResultSet.class);
      when(psBatch.executeQuery()).thenReturn(rsBatch);
      when(rsBatch.next()).thenReturn(false);

      JSONObject rec = new JSONObject().put("id", "sh-3").put("documentNo", "SH-3");
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(rec)));
      NeoContext ctx = getCtx(); // recordId = null
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new GoodsShipmentHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject resultRec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      // returnReceipts should NOT be injected for list view
      assertFalse(resultRec.has("returnReceipts"));
    }
  }

  /**
   * Verifies that a SQL exception in enrichReturnReceipts is caught silently —
   * afterHandle still returns a valid response without throwing.
   */
  @Test
  public void testEnrichReturnReceiptsSqlExceptionCaughtSilently() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement psSingle = mock(PreparedStatement.class);
      PreparedStatement psReturn = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(psSingle)
          .thenReturn(psReturn);

      ResultSet rsSingle = mock(ResultSet.class);
      when(psSingle.executeQuery()).thenReturn(rsSingle);
      when(rsSingle.next()).thenReturn(false);

      // enrichReturnReceipts throws SQL exception
      when(psReturn.executeQuery()).thenThrow(new SQLException("DB timeout"));

      JSONObject body = shipmentBody("sh-4");
      NeoContext ctx = getDetailCtx("sh-4");
      ctx.setPreviousResult(NeoResponse.ok(body));

      // Must not throw — afterHandle catches the exception in enrichReturnReceipts
      NeoResponse result = new GoodsShipmentHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
    }
  }
}
