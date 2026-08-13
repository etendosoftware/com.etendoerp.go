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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hibernate.criterion.SimpleExpression;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link ReturnToVendorShipmentHeaderHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code handle()} — non-ACTION exit, clone delegation, unknown action, importReceiptLines
 *       guard conditions, availableReceipts (SQL and error paths),
 *       availableReceiptLines (SQL and error paths), createReturnInvoice guard conditions,
 *       and the documentAction (fillMissingStorageBins) pass-through.</li>
 *   <li>{@code afterHandle()} — guard conditions, no-id enrichment, SQL empty path,
 *       sourceReceipts with rows, and returnInvoices with rows.</li>
 * </ul>
 */
public class ReturnToVendorShipmentHeaderHandlerTest {

  private ReturnToVendorShipmentHeaderHandler handler;

  @Before
  public void setUp() {
    handler = new ReturnToVendorShipmentHeaderHandler();
    handler.cloneRecordHandler = mock(NeoCloneRecordHandler.class);
    handler.createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    when(handler.cloneRecordHandler.handle(any())).thenReturn(null);
  }

  // ── handle() — non-ACTION early exit ──────────────────────────────────────

  /**
   * Non-ACTION endpoint: handle returns null immediately.
   */
  @Test
  public void testHandleReturnsNullForNonActionEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.handle(ctx));
  }

  // ── handle() — clone handler delegation ───────────────────────────────────

  /**
   * ACTION "clone": delegates to cloneRecordHandler and returns its response.
   */
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

  /**
   * ACTION with an unrecognised field name: handle returns null.
   */
  @Test
  public void testHandleReturnsNullForUnknownAction() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("unknownAction").build();
    assertNull(handler.handle(ctx));
  }

  // ── handle() — importReceiptLines guard conditions ─────────────────────────

  /**
   * ACTION "importReceiptLines" with null recordId: returns 400 Bad Request.
   */
  @Test
  public void testHandleImportReceiptLinesMissingRecordIdReturnsBadRequest() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("importReceiptLines").recordId(null).build();
    NeoResponse result = handler.handle(ctx);
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  /**
   * ACTION "importReceiptLines", OBDal.get returns null for the document: returns 404.
   */
  @Test
  public void testHandleImportReceiptLinesDocNotFoundReturnsNotFound() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(null);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("importReceiptLines").recordId("ret-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_NOT_FOUND, result.getHttpStatus());
    }
  }

  /**
   * ACTION "importReceiptLines", document found but body has no "lines" array: returns 400.
   */
  @Test
  public void testHandleImportReceiptLinesNoLinesInBodyReturnsBadRequest() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("importReceiptLines").recordId("ret-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  // ── handle() — availableReceipts ──────────────────────────────────────────

  /**
   * ACTION "availableReceipts" with no businessPartner in the body: returns 400.
   */
  @Test
  public void testHandleAvailableReceiptsMissingBusinessPartnerReturnsBadRequest() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("availableReceipts").build();
    NeoResponse result = handler.handle(ctx);
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  /**
   * ACTION "availableReceipts", SQL returns 1 row: response is 200 with data array of length 1.
   */
  @Test
  public void testHandleAvailableReceiptsSqlReturnsOneRow() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("rcpt-1");
      when(rs.getString(2)).thenReturn("PRC-001");
      when(rs.getString(3)).thenReturn("2026-01-20");
      when(rs.getString(4)).thenReturn("Vendor A");
      when(rs.getString(5)).thenReturn("bp-1");

      JSONObject body = new JSONObject().put("businessPartner", "bp-1");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("availableReceipts").requestBody(body).build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(1, data.length());
      assertEquals("PRC-001", data.getJSONObject(0).getString("documentNo"));
    }
  }

  /**
   * ACTION "availableReceipts", SQL throws: returns 500 Internal Server Error.
   */
  @Test
  public void testHandleAvailableReceiptsSqlThrowsReturnsInternalError() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB down"));

      JSONObject body = new JSONObject().put("businessPartner", "bp-1");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("availableReceipts").requestBody(body).build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }

  // ── handle() — availableReceiptLines ──────────────────────────────────────

  /**
   * ACTION "availableReceiptLines" with no receiptId in the body: returns 400.
   */
  @Test
  public void testHandleAvailableReceiptLinesMissingReceiptIdReturnsBadRequest() throws Exception {
    JSONObject body = new JSONObject().put("businessPartner", "bp-1");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("availableReceiptLines").requestBody(body).build();
    NeoResponse result = handler.handle(ctx);
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  /**
   * ACTION "availableReceiptLines" with no businessPartner in the body: returns 400.
   */
  @Test
  public void testHandleAvailableReceiptLinesMissingBusinessPartnerReturnsBadRequest() throws Exception {
    JSONObject body = new JSONObject().put("receiptId", "rcpt-1");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("availableReceiptLines").requestBody(body).build();
    NeoResponse result = handler.handle(ctx);
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  /**
   * ACTION "availableReceiptLines", SQL returns 1 row: response is 200 with data array of length 1.
   */
  @Test
  public void testHandleAvailableReceiptLinesSqlReturnsOneRow() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-1");
      when(rs.getString(2)).thenReturn("prod-1");
      when(rs.getString(3)).thenReturn("Widget B");
      when(rs.getString(4)).thenReturn("uom-1");
      when(rs.getBigDecimal(5)).thenReturn(new BigDecimal("8.00"));

      JSONObject body = new JSONObject()
          .put("receiptId", "rcpt-1")
          .put("businessPartner", "bp-1");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("availableReceiptLines").requestBody(body).build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(1, data.length());
      assertEquals("Widget B", data.getJSONObject(0).getString("product$_identifier"));
    }
  }

  // ── handle() — createReturnInvoice guard conditions ───────────────────────

  /**
   * ACTION "createReturnInvoice" with null recordId: returns 400.
   */
  @Test
  public void testHandleCreateReturnInvoiceMissingRecordIdReturnsBadRequest() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturnInvoice").recordId(null).build();
    NeoResponse result = handler.handle(ctx);
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  /**
   * ACTION "createReturnInvoice", OBDal.get returns null: returns 404.
   */
  @Test
  public void testHandleCreateReturnInvoiceDocNotFoundReturnsNotFound() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(null);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("ret-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_NOT_FOUND, result.getHttpStatus());
    }
  }

  /**
   * ACTION "createReturnInvoice", document found but documentStatus is not "CO": returns 400.
   */
  @Test
  public void testHandleCreateReturnInvoiceDocNotCompletedReturnsBadRequest() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);
      when(returnDoc.getDocumentStatus()).thenReturn("DR");

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("ret-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  /**
   * ACTION "createReturnInvoice", document completed but has no product lines: returns 400.
   */
  @Test
  public void testHandleCreateReturnInvoiceNoProductLinesReturnsBadRequest() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);
      when(returnDoc.getDocumentStatus()).thenReturn("CO");
      when(returnDoc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.<ShipmentInOutLine>emptyList());

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("ret-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  /**
   * ACTION "createReturnInvoice", completed document with product lines but no APC document type
   * found for the organisation: returns 500.
   */
  @Test
  public void testHandleCreateReturnInvoiceNoApcDocTypeReturnsInternalError() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);
      when(returnDoc.getDocumentStatus()).thenReturn("CO");

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      Product product = mock(Product.class);
      when(line.getProduct()).thenReturn(product);
      when(returnDoc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      Organization org = mock(Organization.class);
      when(returnDoc.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");

      // findApcDocType returns empty list → docType is null → 500
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.<DocumentType>emptyList());

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("ret-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());

      // ETP-4036: findReturnDocTypeForOrg fix — when requireReturn=false the criteria
      // must explicitly filter by PROPERTY_RETURN = false (not skip the restriction).
      // Verify that criteria.add() was called at least once with a SimpleExpression
      // whose property name is "return" and value is Boolean.FALSE.
      verify(criteria, atLeastOnce()).add(argThat(criterion -> {
        if (!(criterion instanceof SimpleExpression)) return false;
        SimpleExpression expr = (SimpleExpression) criterion;
        return "return".equals(expr.getPropertyName())
            && Boolean.FALSE.equals(expr.getValue());
      }));
    }
  }

  // ── handle() — documentAction pass-through ────────────────────────────────

  /**
   * ACTION "documentAction": fillMissingStorageBins is called and handle returns null,
   * leaving the NEO native completion handler to process the request.
   */
  @Test
  public void testHandleDocumentActionReturnsNull() {
    // fillMissingStorageBins exits early when recordId is null
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction").recordId(null).build();
    assertNull(handler.handle(ctx));
  }

  // ── afterHandle() — guard conditions ──────────────────────────────────────

  /**
   * Non-GET endpoint: afterHandle returns null immediately.
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * GET without a previous result: afterHandle returns null.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = NeoContext.builder()
        .specName("return-to-vendor-shipment").entityName("header")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.afterHandle(ctx));
  }

  // ── afterHandle() — enrichment with no ids (no DB needed) ─────────────────

  /**
   * Record with no "id" field: afterHandle enriches it with empty collections
   * (sourceReceipts=[], returnInvoices=[], hasReturnInvoice=false, linesCount=0).
   */
  @Test
  public void testAfterHandleEnrichesRecordWithEmptyCollectionsWhenNoId() throws Exception {
    JSONObject rec = new JSONObject().put("documentStatus", "DR");
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(rec)));
    NeoContext ctx = NeoContext.builder()
        .specName("return-to-vendor-shipment").entityName("header")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = handler.afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
    JSONObject enriched = result.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals(0, enriched.getJSONArray("sourceReceipts").length());
    assertEquals(0, enriched.getJSONArray("returnInvoices").length());
    assertFalse(enriched.getBoolean("hasReturnInvoice"));
    assertEquals(0, enriched.getInt("linesCount"));
  }

  // ── afterHandle() — enrichment with real id and empty SQL results ──────────

  /**
   * Record with an id, but all three SQL queries return empty result sets:
   * the record is enriched with empty arrays and zero line count.
   */
  @Test
  public void testAfterHandleEnrichesWithRealIdAndEmptySqlResults() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement psSource = mock(PreparedStatement.class);
      PreparedStatement psInvoices = mock(PreparedStatement.class);
      PreparedStatement psCounts = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(psSource)
          .thenReturn(psInvoices)
          .thenReturn(psCounts);

      ResultSet rsSource = mock(ResultSet.class);
      ResultSet rsInvoices = mock(ResultSet.class);
      ResultSet rsCounts = mock(ResultSet.class);
      when(psSource.executeQuery()).thenReturn(rsSource);
      when(psInvoices.executeQuery()).thenReturn(rsInvoices);
      when(psCounts.executeQuery()).thenReturn(rsCounts);
      when(rsSource.next()).thenReturn(false);
      when(rsInvoices.next()).thenReturn(false);
      when(rsCounts.next()).thenReturn(false);

      JSONObject rec = new JSONObject().put("id", "ret-1").put("documentStatus", "DR");
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(rec)));
      NeoContext ctx = NeoContext.builder()
          .specName("return-to-vendor-shipment").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONObject enriched = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(0, enriched.getJSONArray("sourceReceipts").length());
      assertEquals(0, enriched.getJSONArray("returnInvoices").length());
      assertFalse(enriched.getBoolean("hasReturnInvoice"));
      assertEquals(0, enriched.getInt("linesCount"));
    }
  }

  // ── afterHandle() — enrichment with fetchSourceDocuments returning 1 row ───

  /**
   * Record with an id, fetchSourceDocuments returns 1 row:
   * sourceReceipts array has 1 entry and sourceReceiptDocNo is set.
   */
  @Test
  public void testAfterHandleEnrichesSourceReceiptsWithOneRow() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement psSource = mock(PreparedStatement.class);
      PreparedStatement psInvoices = mock(PreparedStatement.class);
      PreparedStatement psCounts = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(psSource)
          .thenReturn(psInvoices)
          .thenReturn(psCounts);

      // fetchSourceDocuments: 1 row
      ResultSet rsSource = mock(ResultSet.class);
      when(psSource.executeQuery()).thenReturn(rsSource);
      when(rsSource.next()).thenReturn(true, false);
      when(rsSource.getString(1)).thenReturn("ret-1");
      when(rsSource.getString(2)).thenReturn("src-rcpt-1");
      when(rsSource.getString(3)).thenReturn("PRC-001");
      when(rsSource.getString(4)).thenReturn("CO");

      // fetchReturnInvoices: empty
      ResultSet rsInvoices = mock(ResultSet.class);
      when(psInvoices.executeQuery()).thenReturn(rsInvoices);
      when(rsInvoices.next()).thenReturn(false);

      // fetchLineCounts: empty
      ResultSet rsCounts = mock(ResultSet.class);
      when(psCounts.executeQuery()).thenReturn(rsCounts);
      when(rsCounts.next()).thenReturn(false);

      JSONObject rec = new JSONObject().put("id", "ret-1");
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(rec)));
      NeoContext ctx = NeoContext.builder()
          .specName("return-to-vendor-shipment").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      JSONObject enriched = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);

      JSONArray sourceReceipts = enriched.getJSONArray("sourceReceipts");
      assertEquals(1, sourceReceipts.length());
      assertEquals("PRC-001", sourceReceipts.getJSONObject(0).getString("documentNo"));
      assertEquals("PRC-001", enriched.getString("sourceReceiptDocNo"));
    }
  }

  // ── afterHandle() — enrichment with fetchReturnInvoices returning 1 row ────

  /**
   * Record with an id, fetchReturnInvoices returns 1 row:
   * returnInvoices array has 1 entry and hasReturnInvoice is true.
   */
  @Test
  public void testAfterHandleEnrichesReturnInvoicesWithOneRow() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement psSource = mock(PreparedStatement.class);
      PreparedStatement psInvoices = mock(PreparedStatement.class);
      PreparedStatement psCounts = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(psSource)
          .thenReturn(psInvoices)
          .thenReturn(psCounts);

      // fetchSourceDocuments: empty
      ResultSet rsSource = mock(ResultSet.class);
      when(psSource.executeQuery()).thenReturn(rsSource);
      when(rsSource.next()).thenReturn(false);

      // fetchReturnInvoices: 1 row
      ResultSet rsInvoices = mock(ResultSet.class);
      when(psInvoices.executeQuery()).thenReturn(rsInvoices);
      when(rsInvoices.next()).thenReturn(true, false);
      when(rsInvoices.getString(1)).thenReturn("ret-1");
      when(rsInvoices.getString(2)).thenReturn("inv-1");
      when(rsInvoices.getString(3)).thenReturn("APC-001");
      when(rsInvoices.getString(4)).thenReturn("CO");
      when(rsInvoices.getBigDecimal(5)).thenReturn(new BigDecimal("150.00"));
      when(rsInvoices.getString(6)).thenReturn("EUR");

      // fetchLineCounts: 1 row
      ResultSet rsCounts = mock(ResultSet.class);
      when(psCounts.executeQuery()).thenReturn(rsCounts);
      when(rsCounts.next()).thenReturn(true, false);
      when(rsCounts.getString(1)).thenReturn("ret-1");
      when(rsCounts.getInt(2)).thenReturn(2);

      JSONObject rec = new JSONObject().put("id", "ret-1");
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(rec)));
      NeoContext ctx = NeoContext.builder()
          .specName("return-to-vendor-shipment").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      JSONObject enriched = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);

      JSONArray returnInvoices = enriched.getJSONArray("returnInvoices");
      assertEquals(1, returnInvoices.length());
      assertEquals("APC-001", returnInvoices.getJSONObject(0).getString("documentNo"));
      assertTrue(enriched.getBoolean("hasReturnInvoice"));
      assertEquals(2, enriched.getInt("linesCount"));
    }
  }

  // ── handle() — availableReceiptLines SQL exception ────────────────────────

  /**
   * ACTION "availableReceiptLines", SQL throws: handle returns 500 Internal Error.
   */
  @Test
  public void testHandleAvailableReceiptLinesSqlThrowsReturnsInternalError() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB error"));

      JSONObject body = new JSONObject()
          .put("receiptId", "rcpt-1").put("businessPartner", "bp-1");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("availableReceiptLines").requestBody(body).build();

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }

  // ── handle() — documentAction with non-null recordId ─────────────────────

  /**
   * ACTION "documentAction", returnDoc is null:
   * fillMissingStorageBins exits early; handle returns null.
   */
  @Test
  public void testHandleDocumentActionDocNotFoundReturnsNull() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ShipmentInOut.class, "ret-x")).thenReturn(null);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("documentAction").recordId("ret-x").build();
      assertNull(handler.handle(ctx));
    }
  }

  /**
   * ACTION "documentAction", returnDoc has no lines:
   * fillMissingStorageBins loop does not execute; handle returns null.
   */
  @Test
  public void testHandleDocumentActionWithDocAndEmptyLinesReturnsNull() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-x")).thenReturn(returnDoc);
      when(returnDoc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.<ShipmentInOutLine>emptyList());

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("documentAction").recordId("ret-x").build();
      assertNull(handler.handle(ctx));
    }
  }

  // ── handle() — importReceiptLines ─────────────────────────────────────────

  /**
   * ACTION "importReceiptLines", one valid line with qty>0:
   * the line is created and saved; response data has importedCount=1.
   */
  @Test
  public void testHandleImportReceiptLinesHappyPathImportsOneLine() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      // fetchMaxLineNo uses getConnection()
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getLong(1)).thenReturn(10L); // nextLineNo = 20

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);
      when(returnDoc.getClient()).thenReturn(mock(Client.class));
      when(returnDoc.getOrganization()).thenReturn(mock(Organization.class));

      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      when(dal.get(ShipmentInOutLine.class, "src-1")).thenReturn(sourceLine);
      when(sourceLine.getProduct()).thenReturn(mock(Product.class));
      when(sourceLine.getUOM()).thenReturn(mock(UOM.class));
      when(sourceLine.getStorageBin()).thenReturn(null);

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      ShipmentInOutLine retLine = mock(ShipmentInOutLine.class);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(retLine);

      JSONObject body = new JSONObject().put("lines",
          new JSONArray().put(
              new JSONObject().put("sourceLineId", "src-1").put("returnQuantity", "3")));
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("importReceiptLines").recordId("ret-1").requestBody(body).build();

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(1, result.getBody()
          .getJSONObject("response").getJSONObject("data").getInt("importedCount"));
    }
  }

  /**
   * ACTION "importReceiptLines", line has returnQuantity="0": the line is skipped;
   * response data has importedCount=0.
   */
  @Test
  public void testHandleImportReceiptLinesSkipsLineWithZeroQuantity() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);

      JSONObject body = new JSONObject().put("lines",
          new JSONArray().put(
              new JSONObject().put("sourceLineId", "src-1").put("returnQuantity", "0")));
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("importReceiptLines").recordId("ret-1").requestBody(body).build();

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(0, result.getBody()
          .getJSONObject("response").getJSONObject("data").getInt("importedCount"));
    }
  }

  // ── handle() — createReturnInvoice: findApcDocType org-specific match ─────

  /**
   * ACTION "createReturnInvoice", findApcDocType returns a doc type that matches
   * the org exactly (first loop in findApcDocType), but buildReturnInvoiceHeader
   * throws OBException because the business partner is missing payment terms:
   * handle returns 400.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleCreateReturnInvoiceOrgSpecificDocTypeFoundBpMissingPaymentTermsReturns400()
      throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("org-1");

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);
      when(returnDoc.getDocumentStatus()).thenReturn("CO");
      when(returnDoc.getOrganization()).thenReturn(org);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getProduct()).thenReturn(mock(Product.class));
      when(returnDoc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      // findApcDocType: one candidate that matches org-1 exactly
      DocumentType apcDocType = mock(DocumentType.class);
      Organization dtOrg = mock(Organization.class);
      when(dtOrg.getId()).thenReturn("org-1");
      when(apcDocType.getOrganization()).thenReturn(dtOrg);
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(apcDocType));

      // ReturnShipmentUtils.findSourceInvoice uses dal.getSession() + createQuery
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      Query<Invoice> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(Invoice.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(Collections.<Invoice>emptyList());

      // buildReturnInvoiceHeader: OBProvider creates invoice mock
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      Invoice invoice = mock(Invoice.class);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      // bp missing payment terms → OBException
      BusinessPartner bp = mock(BusinessPartner.class);
      when(returnDoc.getBusinessPartner()).thenReturn(bp);
      when(bp.getPurchasePricelist()).thenReturn(null);
      when(bp.getPaymentTerms()).thenReturn(null); // triggers OBException

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("ret-1").build();

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }
}
