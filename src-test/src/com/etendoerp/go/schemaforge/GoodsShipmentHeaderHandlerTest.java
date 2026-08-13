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
 * <p>Covers three responsibilities:
 * <ul>
 *   <li>{@code handle()} — routes ACTION requests to the right downstream handler
 *       (create draft invoice / clone record / create return receipt) or returns null
 *       when none matches.</li>
 *   <li>{@code afterHandle()} — guard conditions that short-circuit before DB access:
 *       non-GET method, missing previous result, null body, and empty data array.</li>
 *   <li>{@code afterHandle()} with DB — outer try-catch returns null on DB failure for
 *       both single-record (recordId non-null) and batch (recordId null) paths.</li>
 * </ul>
 */
public class GoodsShipmentHeaderHandlerTest {

  /**
   * Creates a {@link GoodsShipmentHeaderHandler} with its {@code @Inject} fields replaced by the
   * provided mocks via reflection, bypassing CDI in the unit-test context.
   *
   * @deprecated Use {@link #handlerWithMocks(CreateDraftInvoiceHandler, NeoCloneRecordHandler,
   *     CreateReturnReceiptHandler)} to inject all three handlers.
   */
  private static GoodsShipmentHeaderHandler handlerWithMocks(
      CreateDraftInvoiceHandler mockCreateDraftInvoice,
      NeoCloneRecordHandler mockClone) throws Exception {
    return handlerWithMocks(mockCreateDraftInvoice, mockClone, null);
  }

  /**
   * Creates a {@link GoodsShipmentHeaderHandler} with all three {@code @Inject} handler fields
   * replaced by the provided mocks via reflection, bypassing CDI in the unit-test context.
   */
  private static GoodsShipmentHeaderHandler handlerWithMocks(
      CreateDraftInvoiceHandler mockCreateDraftInvoice,
      NeoCloneRecordHandler mockClone,
      CreateReturnReceiptHandler mockReturnReceipt) throws Exception {
    GoodsShipmentHeaderHandler handler = new GoodsShipmentHeaderHandler();
    Field invoiceField = GoodsShipmentHeaderHandler.class.getDeclaredField("createDraftInvoiceHandler");
    invoiceField.setAccessible(true);
    invoiceField.set(handler, mockCreateDraftInvoice);
    Field cloneField = GoodsShipmentHeaderHandler.class.getDeclaredField("neoCloneRecordHandler");
    cloneField.setAccessible(true);
    cloneField.set(handler, mockClone);
    Field returnField = GoodsShipmentHeaderHandler.class.getDeclaredField("createReturnReceiptHandler");
    returnField.setAccessible(true);
    returnField.set(handler, mockReturnReceipt);
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
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      // prepareStatement call order: computeSingle → enrichLinkedOrder →
      // enrichLinkedInvoices → enrichReturnReceipts → enrichCanCreateReturn
      PreparedStatement psSingle = mock(PreparedStatement.class);
      PreparedStatement psLinkedOrder = mock(PreparedStatement.class);
      PreparedStatement psLinkedInvoices = mock(PreparedStatement.class);
      PreparedStatement psReturn = mock(PreparedStatement.class);

      ResultSet rsLinkedOrder = mock(ResultSet.class);
      when(psLinkedOrder.executeQuery()).thenReturn(rsLinkedOrder);
      when(rsLinkedOrder.next()).thenReturn(false);

      ResultSet rsLinkedInvoices = mock(ResultSet.class);
      when(psLinkedInvoices.executeQuery()).thenReturn(rsLinkedInvoices);
      when(rsLinkedInvoices.next()).thenReturn(false);

      when(conn.prepareStatement(anyString()))
          .thenReturn(psSingle)
          .thenReturn(psLinkedOrder)
          .thenReturn(psLinkedInvoices)
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

  // ── handle() — createReturnReceiptHandler dispatch ────────────────────────

  /**
   * Verifies that handle returns the return-receipt response when invoice and clone
   * handlers both return null but the return-receipt handler matches.
   */
  @Test
  public void testHandleDispatchesToCreateReturnReceiptHandler() throws Exception {
    CreateDraftInvoiceHandler mockInvoice = mock(CreateDraftInvoiceHandler.class);
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    CreateReturnReceiptHandler mockReturn = mock(CreateReturnReceiptHandler.class);
    GoodsShipmentHeaderHandler handler = handlerWithMocks(mockInvoice, mockClone, mockReturn);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .build();
    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "createReturn"));
    when(mockInvoice.handle(ctx)).thenReturn(null);
    when(mockClone.handle(ctx)).thenReturn(null);
    when(mockReturn.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
  }

  /**
   * Verifies that handle returns null when all three downstream handlers return null.
   */
  @Test
  public void testHandleReturnsNullWhenAllThreeHandlersMiss() throws Exception {
    CreateDraftInvoiceHandler mockInvoice = mock(CreateDraftInvoiceHandler.class);
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    CreateReturnReceiptHandler mockReturn = mock(CreateReturnReceiptHandler.class);
    GoodsShipmentHeaderHandler handler = handlerWithMocks(mockInvoice, mockClone, mockReturn);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    when(mockInvoice.handle(ctx)).thenReturn(null);
    when(mockClone.handle(ctx)).thenReturn(null);
    when(mockReturn.handle(ctx)).thenReturn(null);

    assertNull(handler.handle(ctx));
  }

  // ── afterHandle() with DB — single-record path (recordId non-null) ─────────

  /**
   * Verifies that afterHandle returns null when recordId is non-null but OBDal
   * throws on getConnection() — the outer try-catch must absorb the exception
   * and return null instead of propagating it to the caller.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDbThrowsOnSingleRecord() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance)
          .thenThrow(new RuntimeException("OBDal not available in unit tests"));

      JSONObject item = new JSONObject().put("id", "shipment-1");
      JSONArray data = new JSONArray().put(item);
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));

      NeoContext ctx = NeoContext.builder()
          .specName("goods-shipment")
          .entityName("header")
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .recordId("shipment-1")
          .build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      assertNull(new GoodsShipmentHeaderHandler().afterHandle(ctx));
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
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
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
   * Verifies that afterHandle returns a non-null 200 OK response when recordId is non-null
   * and the PreparedStatement throws a SQL exception — computeSingle swallows it internally
   * and returns 0. All enrich* methods also have their own catches, so the outer try-catch
   * never fires and a valid response is returned with invoiceStatus=0.
   *
   * <p>This test documents the internal-swallow contract: individual DB failures inside
   * helper methods do NOT propagate to the outer catch and do NOT cause afterHandle to
   * return null.
   */
  @Test
  public void testAfterHandleSurvivesComputeSingleDbFailureAndReturnsOk() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal mockDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(mockDal);
      // getReadOnlyInstance is also called by enrich* — stub it too so they don't NPE
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(mockDal);

      Connection mockConn = mock(Connection.class);
      when(mockDal.getConnection()).thenReturn(mockConn);
      // prepareStatement throws — computeSingle catches this internally and returns 0
      when(mockConn.prepareStatement(anyString()))
          .thenThrow(new java.sql.SQLException("connection reset"));

      JSONObject item = new JSONObject().put("id", "shipment-2");
      JSONArray data = new JSONArray().put(item);
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));

      NeoContext ctx = NeoContext.builder()
          .specName("goods-shipment")
          .entityName("header")
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .recordId("shipment-2")
          .build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      // All individual exceptions are swallowed; afterHandle completes and returns 200 OK.
      NeoResponse result = new GoodsShipmentHeaderHandler().afterHandle(ctx);
      assertNotNull(result);
    }
  }

  // ── afterHandle() with DB — batch path (recordId null) ────────────────────

  /**
   * Verifies that afterHandle returns null when recordId is null and OBDal.getInstance()
   * throws — the outer try-catch absorbs the exception and returns null.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDbThrowsOnBatch() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance)
          .thenThrow(new RuntimeException("OBDal not available in unit tests"));

      JSONObject item = new JSONObject().put("id", "shipment-3");
      JSONArray data = new JSONArray().put(item);
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));

      // recordId is null → batch path
      NeoContext ctx = NeoContext.builder()
          .specName("goods-shipment")
          .entityName("header")
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      assertNull(new GoodsShipmentHeaderHandler().afterHandle(ctx));
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
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
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

  /**
   * Verifies that afterHandle survives a batch PreparedStatement SQL exception and
   * returns a non-null 200 OK response. computeBatch catches the SQL exception
   * internally, returns an empty map, and annotateBatch assigns invoiceStatus=0 to
   * every item. The outer try-catch never fires.
   */
  @Test
  public void testAfterHandleSurvivesBatchDbFailureAndReturnsOk() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal mockDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(mockDal);

      Connection mockConn = mock(Connection.class);
      when(mockDal.getConnection()).thenReturn(mockConn);
      // computeBatch catches this SQL exception internally and returns empty map
      when(mockConn.prepareStatement(anyString()))
          .thenThrow(new java.sql.SQLException("network timeout"));

      JSONObject item = new JSONObject().put("id", "shipment-4");
      JSONArray data = new JSONArray().put(item);
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));

      NeoContext ctx = NeoContext.builder()
          .specName("goods-shipment")
          .entityName("header")
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      // computeBatch swallows the SQL exception, annotateBatch writes invoiceStatus=0,
      // and afterHandle returns NeoResponse.ok(body) — not null.
      NeoResponse result = new GoodsShipmentHeaderHandler().afterHandle(ctx);
      assertNotNull(result);
    }
  }

  // ── computeSingle / annotateBatch — DB returns empty ResultSet ────────────

  /**
   * Verifies that annotateBatch writes invoiceStatus=0 for each item when the DB
   * returns an empty ResultSet (no matched invoice lines), and afterHandle returns
   * a non-null 200 OK response.
   *
   * <p>The batch path: recordId is null → {@code annotateBatch} → {@code computeBatch}
   * (empty ResultSet → empty map) → each item gets {@code invoiceStatus=0} →
   * {@code NeoResponse.ok(body)} returned. No enrich* methods run on the batch path.
   */
  @Test
  public void testAfterHandleAnnotatesBatchWithZeroWhenResultSetIsEmpty() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal mockDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(mockDal);

      ResultSet mockRs = mock(ResultSet.class);
      when(mockRs.next()).thenReturn(false);

      PreparedStatement mockPs = mock(PreparedStatement.class);
      when(mockPs.executeQuery()).thenReturn(mockRs);

      Connection mockConn = mock(Connection.class);
      when(mockDal.getConnection()).thenReturn(mockConn);
      when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);

      JSONObject item = new JSONObject().put("id", "shipment-5");
      JSONArray data = new JSONArray().put(item);
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));

      // recordId is null → batch path (no enrich* calls)
      NeoContext ctx = NeoContext.builder()
          .specName("goods-shipment")
          .entityName("header")
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new GoodsShipmentHeaderHandler().afterHandle(ctx);
      assertNotNull(result);
    }
  }

  @Test
  public void handleReturnsPostingResponseWhenServiceHandlesAction() {
    com.etendoerp.go.schemaforge.handlers.DocumentPostingService service =
        mock(com.etendoerp.go.schemaforge.handlers.DocumentPostingService.class);
    NeoContext ctx = mock(NeoContext.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    when(service.handleAction(ctx)).thenReturn(sentinel);

    GoodsShipmentHeaderHandler h = new GoodsShipmentHeaderHandler();
    h.setPostingService(service);

    assertSame(sentinel, h.handle(ctx));
  }

  // NOTE (ETP-4531): GoodsShipmentHeaderHandler previously overrode afterCallout() solely to
  // block a callout-driven accountingDate update (the movementDate -> accountingDate
  // cascade). The unified-date requirement now wants that cascade to happen, so the override
  // was removed entirely — the handler falls back to NeoHandler's default no-op afterCallout.
  // There is no handler-specific afterCallout behavior left here to test.

  // ── ETP-4531: mirrorAccountingDate (unified date, server-side mirror) ───────

  @Test
  public void mirrorAccountingDate_postCrud_copiesMovementDateIntoAccountingDate()
      throws Exception {
    JSONObject body = new JSONObject().put("movementDate", "2026-07-01");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();

    GoodsShipmentHeaderHandler.mirrorAccountingDate(ctx);

    assertEquals("2026-07-01", body.getString("accountingDate"));
  }

  @Test
  public void mirrorAccountingDate_putCrud_overwritesStaleAccountingDate() throws Exception {
    JSONObject body = new JSONObject()
        .put("movementDate", "2026-07-10").put("accountingDate", "2026-01-01");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .requestBody(body)
        .build();

    GoodsShipmentHeaderHandler.mirrorAccountingDate(ctx);

    assertEquals("2026-07-10", body.getString("accountingDate"));
  }

  @Test
  public void mirrorAccountingDate_getMethod_doesNotMutateBody() throws Exception {
    JSONObject body = new JSONObject().put("movementDate", "2026-07-01");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .requestBody(body)
        .build();

    GoodsShipmentHeaderHandler.mirrorAccountingDate(ctx);

    assertNull(body.opt("accountingDate"));
  }

  /**
   * Regression test for the live-reproduced ETP-4531 bug: the real React UI
   * ({@code useEntity.js#getMethod}) always sends {@code PATCH} — never a full {@code PUT} —
   * for edits to an EXISTING shipment, with a sparse body containing only the changed field.
   * The original {@code POST}/{@code PUT}-only check silently skipped this case.
   */
  @Test
  public void mirrorAccountingDate_patchCrudSparseBody_copiesMovementDateIntoAccountingDate()
      throws Exception {
    JSONObject body = new JSONObject().put("movementDate", "2026-07-15");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .requestBody(body)
        .build();

    GoodsShipmentHeaderHandler.mirrorAccountingDate(ctx);

    assertEquals("2026-07-15", body.getString("accountingDate"));
  }

  @Test
  public void mirrorAccountingDate_patchCrud_overwritesStaleAccountingDate() throws Exception {
    JSONObject body = new JSONObject()
        .put("movementDate", "2026-07-15").put("accountingDate", "2026-07-17");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .requestBody(body)
        .build();

    GoodsShipmentHeaderHandler.mirrorAccountingDate(ctx);

    assertEquals("2026-07-15", body.getString("accountingDate"));
  }
}
