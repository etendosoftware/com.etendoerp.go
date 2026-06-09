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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link ReturnMaterialReceiptHeaderHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code handle()} — guard conditions: non-ACTION endpoint, clone handler delegation,
 *       unknown action, wrong HTTP method, and missing required params for each action.</li>
 *   <li>{@code afterHandle()} — guard conditions and enrichment with no-id records
 *       (all fetch methods return early when ids list is empty, so no DB mock needed).</li>
 * </ul>
 *
 * <p>DB-heavy paths (full importShipmentLines body, createReturnInvoice with real entities,
 * fetchSource* with rows) are covered by integration tests.
 */
public class ReturnMaterialReceiptHeaderHandlerTest {

  private ReturnMaterialReceiptHeaderHandler handler;

  @Before
  public void setUp() {
    handler = new ReturnMaterialReceiptHeaderHandler();
    handler.cloneRecordHandler = mock(NeoCloneRecordHandler.class);
    handler.createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    when(handler.cloneRecordHandler.handle(any())).thenReturn(null);
  }

  // ── handle() — non-ACTION early exit ──────────────────────────────────────

  @Test
  public void testHandleReturnsNullForNonActionEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.handle(ctx));
  }

  // ── handle() — clone handler delegation ───────────────────────────────────

  @Test
  public void testHandleDelegatesToCloneHandlerAndReturnsItsResponse() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("clone").build();
    NeoResponse cloneResp = new NeoResponse(200, new JSONObject());
    when(handler.cloneRecordHandler.handle(ctx)).thenReturn(cloneResp);
    assertSame(cloneResp, handler.handle(ctx));
  }

  // ── handle() — action routing ─────────────────────────────────────────────

  @Test
  public void testHandleReturnsNullForUnknownAction() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("unknownAction").build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleReturnsNullForKnownActionWithWrongMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName("importShipmentLines").build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleDocumentActionWithNullReceiptIdReturnsNull() {
    // fillMissingStorageBins early-returns when receiptId is null
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction").recordId(null).build();
    assertNull(handler.handle(ctx));
  }

  // ── handle() — action guard conditions (missing required params) ──────────

  @Test
  public void testHandleImportShipmentLinesMissingRecordIdReturnsBadRequest() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("importShipmentLines").recordId(null).build();
    NeoResponse result = handler.handle(ctx);
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  @Test
  public void testHandleAvailableShipmentsMissingBodyReturnsBadRequest() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("availableShipments").build();
    NeoResponse result = handler.handle(ctx);
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  @Test
  public void testHandleAvailableShipmentLinesMissingBodyReturnsBadRequest() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("availableShipmentLines").build();
    NeoResponse result = handler.handle(ctx);
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  @Test
  public void testHandleCreateReturnInvoiceMissingRecordIdReturnsBadRequest() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturnInvoice").recordId(null).build();
    NeoResponse result = handler.handle(ctx);
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  // ── afterHandle() — guard conditions ──────────────────────────────────────

  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = NeoContext.builder()
        .specName("return-material-receipt").entityName("header")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = NeoContext.builder()
        .specName("return-material-receipt").entityName("header")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(handler.afterHandle(ctx));
  }

  // ── afterHandle() — enrichment with no-id records (no DB needed) ──────────

  /**
   * When no records have an id, collectIds returns empty list and all fetch methods
   * short-circuit before hitting the DB. The loop still runs and injects empty
   * sourceShipments / returnInvoices arrays and a zero linesCount on each record.
   */
  @Test
  public void testAfterHandleEnrichesRecordWithEmptyCollectionsWhenNoIds() throws Exception {
    JSONObject rec = new JSONObject().put("documentStatus", "DR");
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(rec)));
    NeoContext ctx = NeoContext.builder()
        .specName("return-material-receipt").entityName("header")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = handler.afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
    JSONObject enriched = result.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals(0, enriched.getJSONArray("sourceShipments").length());
    assertEquals(0, enriched.getJSONArray("returnInvoices").length());
    assertFalse(enriched.getBoolean("hasReturnInvoice"));
    assertEquals(0, enriched.getInt("linesCount"));
  }

  /**
   * When a record has an id, fetchSourceShipments calls OBDal.getInstance().getConnection()
   * outside its inner try/catch. Without a mock, this throws an exception that propagates
   * to afterHandle's outer catch, which logs and returns null.
   */
  @Test
  public void testAfterHandleReturnsNullOnDbException() throws Exception {
    JSONObject rec = new JSONObject().put("id", "rec-1");
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(rec)));
    NeoContext ctx = NeoContext.builder()
        .specName("return-material-receipt").entityName("header")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = handler.afterHandle(ctx);

    assertNull(result);
  }
}
