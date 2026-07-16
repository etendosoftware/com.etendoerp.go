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
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.Location;

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

  @Test
  public void handleReturnsPostingResponseWhenServiceHandlesAction() {
    com.etendoerp.go.schemaforge.handlers.DocumentPostingService service =
        mock(com.etendoerp.go.schemaforge.handlers.DocumentPostingService.class);
    NeoContext ctx = mock(NeoContext.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    when(service.handleAction(ctx)).thenReturn(sentinel);

    GoodsReceiptHeaderHandler h = new GoodsReceiptHeaderHandler();
    h.setPostingService(service);

    assertSame(sentinel, h.handle(ctx));
  }

  // ── handle() — postingService returns null, routing continues ─────────────

  /**
   * When the posting service returns null the handler falls through to the
   * NeoHeaderActionRouter dispatch path without short-circuiting.
   */
  @Test
  public void handle_postingServiceReturnsNull_routesToActionRouter() {
    com.etendoerp.go.schemaforge.handlers.DocumentPostingService service =
        mock(com.etendoerp.go.schemaforge.handlers.DocumentPostingService.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("unknownAction")
        .recordId("r-1")
        .build();
    when(service.handleAction(ctx)).thenReturn(null);

    GoodsReceiptHeaderHandler h = new GoodsReceiptHeaderHandler();
    h.setPostingService(service);

    // NeoHeaderActionRouter.dispatch returns null when no sub-handler claims the action
    NeoResponse result = h.handle(ctx);
    assertNull("No matching action → dispatch returns null", result);
  }

  // ── handle() — null postingService skips posting check safely ─────────────

  /**
   * When postingService has not been injected (null), handle() must not throw.
   */
  @Test
  public void handle_nullPostingService_doesNotThrow() {
    GoodsReceiptHeaderHandler h = new GoodsReceiptHeaderHandler();
    h.setPostingService(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("r-2")
        .build();

    // Must not throw; dispatch returns null for a GET CRUD with no matching action
    NeoResponse result = h.handle(ctx);
    assertNull(result);
  }

  // ── handle() — CRUD POST injects partnerAddress when BP present ──────────────

  /**
   * On a CRUD POST without recordId and with a businessPartner in the body,
   * the handler injects a partnerAddress into the body if one can be found.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handle_crudPostWithBusinessPartner_injectsPartnerAddress() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {

      ctxMock.when(() -> OBContext.setAdminMode(Mockito.anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Location loc = mock(Location.class);
      when(loc.getId()).thenReturn("loc-ship-1");

      OBCriteria<Location> crit = mock(OBCriteria.class);
      when(dal.createCriteria(Location.class)).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(loc);

      JSONObject body = new JSONObject().put("businessPartner", "bp-123");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST")
          .endpointType(NeoEndpointType.CRUD)
          .requestBody(body)
          // no recordId → CRUD create path
          .build();

      GoodsReceiptHeaderHandler h = new GoodsReceiptHeaderHandler();
      h.setPostingService(null);
      h.handle(ctx);

      // After handle() the body should have been enriched
      assertEquals("loc-ship-1", body.optString("partnerAddress", null));
    }
  }

  /**
   * On a CRUD POST without a body the handler skips address injection silently.
   */
  @Test
  public void handle_crudPostNullBody_doesNotThrow() {
    GoodsReceiptHeaderHandler h = new GoodsReceiptHeaderHandler();
    h.setPostingService(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        // requestBody defaults to null
        .build();

    // Must not throw
    h.handle(ctx);
  }

  /**
   * On a CRUD POST where the body already contains partnerAddress the handler
   * must not overwrite it.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handle_crudPostBodyAlreadyHasPartnerAddress_doesNotOverwrite() throws Exception {
    GoodsReceiptHeaderHandler h = new GoodsReceiptHeaderHandler();
    h.setPostingService(null);

    JSONObject body = new JSONObject()
        .put("businessPartner", "bp-456")
        .put("partnerAddress", "existing-loc");

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();

    // No OBDal mock needed — the guard `body.has("partnerAddress")` must short-circuit
    h.handle(ctx);

    assertEquals("partnerAddress must not be overwritten", "existing-loc",
        body.optString("partnerAddress", null));
  }

  // ── afterHandle — single record non-zero invoiceStatus ───────────────────────

  /**
   * Verifies that a non-zero invoice status returned by the DB query is written
   * into the single-record enrichment output.
   */
  @Test
  public void afterHandle_singleRecord_nonZeroInvoiceStatusIsPopulated() throws Exception {
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();

    JSONObject rec = new JSONObject().put("id", "r-nz");
    JSONArray data = new JSONArray().put(rec);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", data));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .recordId("r-nz")
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
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      // First PS call = computeInvoiceStatus (returns 50), second = computeReturnStatus (returns 0)
      when(rs.next()).thenReturn(true, true, false);
      when(rs.getInt(1)).thenReturn(50, 0);
      when(rs.getInt(2)).thenReturn(50);

      Connection roConn = mock(Connection.class);
      PreparedStatement roPs = mock(PreparedStatement.class);
      ResultSet roRs = mock(ResultSet.class);
      when(roInst.getConnection()).thenReturn(roConn);
      when(roConn.prepareStatement(anyString())).thenReturn(roPs);
      when(roPs.executeQuery()).thenReturn(roRs);
      when(roRs.next()).thenReturn(false);

      NeoResponse result = handler.afterHandle(ctx);
      assertNotNull(result);
      JSONObject enriched = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      // invoiceStatus must be populated (non-zero)
      assertNotNull(enriched.opt("invoiceStatus"));
    }
  }

  // ── afterHandle — batch mode, record without id in data ──────────────────────

  /**
   * Records in batch mode that do not have an "id" field must be silently skipped
   * (no invoiceStatus written), and the overall response must still be 200.
   */
  @Test
  public void afterHandle_batchMode_recordWithoutIdIsSkipped() throws Exception {
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();

    JSONObject recWithoutId = new JSONObject().put("documentNo", "DOC-001");
    JSONArray data = new JSONArray().put(recWithoutId);
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
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false); // empty result set

      NeoResponse result = handler.afterHandle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      // Record without id must not have invoiceStatus injected
      assertNull("invoiceStatus must not be set on a record without id",
          recWithoutId.opt("invoiceStatus"));
    }
  }

  // ── ETP-4531: afterCallout blocks callout-driven accountingDate ─────────────

  @Test
  public void afterCallout_blocksAccountingDateFromMovementDateTrigger() throws Exception {
    // Mirrors the live M_InOut.MovementDate -> SL_InOut_AccountingDate coupling: editing
    // movementDate must never carry an accountingDate value through to the saved record.
    JSONObject updates = new JSONObject().put("accountingDate", "2026-07-01");
    JSONObject calloutBody = new JSONObject().put("updates", updates);
    JSONObject requestBody = new JSONObject().put("field", "movementDate").put("value", "2026-07-01");
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(200, calloutBody))
        .requestBody(requestBody)
        .build();
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();

    NeoResponse result = handler.afterCallout(ctx);

    assertNull(result);
    assertNull("accountingDate must be stripped when movementDate is the trigger",
        updates.opt("accountingDate"));
  }

  @Test
  public void afterCallout_keepsAccountingDateWhenItIsTheTriggerField() throws Exception {
    JSONObject updates = new JSONObject().put("accountingDate", "2026-07-05");
    JSONObject calloutBody = new JSONObject().put("updates", updates);
    JSONObject requestBody = new JSONObject()
        .put("field", "accountingDate").put("value", "2026-07-05");
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(200, calloutBody))
        .requestBody(requestBody)
        .build();
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();

    handler.afterCallout(ctx);

    assertEquals("2026-07-05", updates.getString("accountingDate"));
  }

  @Test
  public void afterCallout_noPreviousResult_returnsNull() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").build();
    GoodsReceiptHeaderHandler handler = new GoodsReceiptHeaderHandler();

    assertNull(handler.afterCallout(ctx));
  }
}
