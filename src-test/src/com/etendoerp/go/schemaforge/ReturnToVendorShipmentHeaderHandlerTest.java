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

import static com.etendoerp.go.schemaforge.InvoiceCompletionTestSupport.completionSuccess;
import static com.etendoerp.go.schemaforge.InvoiceCompletionTestSupport.mockCompletedInvoice;
import static com.etendoerp.go.schemaforge.ReturnInvoiceSqlTestSupport.stubReturnInvoiceQueries;

import com.etendoerp.go.schemaforge.InvoiceCompletionTestSupport.CompletionMocks;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

import org.hibernate.criterion.SimpleExpression;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
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
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.ReversedInvoice;
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
 *   <li>ETP-5711: the over-return cap on {@code importReceiptLines} and the contracts published
 *       to agents ({@code actionContracts()}).</li>
 * </ul>
 *
 * @covers com.etendoerp.go.schemaforge.ReturnToVendorShipmentHeaderHandler
 * @covers com.etendoerp.go.schemaforge.ReturnShipmentUtils
 * @covers com.etendoerp.go.schemaforge.ReturnInvoiceSupport
 */
public class ReturnToVendorShipmentHeaderHandlerTest {

  private ReturnToVendorShipmentHeaderHandler handler;

  /** Message {@code applyBusinessPartnerFinancials} rejects an unusable purchase BP with. */
  private static final String BP_FINANCIALS_MISSING =
      "Business Partner is missing mandatory PO Payment Terms or PO Payment Method";

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
  public void testHandleCreateReturnInvoiceNoProductLinesReturnsBadRequest() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, "inv-src-1");
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
      assertEquals("No product lines in this return shipment", errorMessage(result));
    }
  }

  /**
   * ACTION "createReturnInvoice", completed document with product lines but no APC document type
   * found for the organisation: returns 500.
   */
  @Test
  public void testHandleCreateReturnInvoiceNoApcDocTypeReturnsInternalError() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      // ETP-5381: the P5 duplicate guard runs before the doc-type lookup and propagates DB
      // failures as OBException -> 400, which would mask the 500 this test is about.
      stubReturnInvoiceQueries(dal, "inv-src-1");

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

  // ── afterHandle() — issuerOrg enrichment (ETP-4939) ───────────────────────

  /**
   * Detail GET (non-null recordId) for a record whose id matches: afterHandle must inject
   * {@code issuerOrg}, mirroring the pattern already used by the goods-shipment sibling
   * handler ({@code GoodsShipmentHeaderHandler#enrichIssuerOrg}). Resolves the shipment via
   * {@code OBDal.getReadOnlyInstance().get(ShipmentInOut.class, "ret-1")}, then its
   * organization via {@code NeoSessionService.resolveOrganization}, which itself reads
   * {@code OrganizationInformation} through the same read-only DAL instance.
   */
  @Test
  public void testAfterHandleEnrichesRecordWithIssuerOrg() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      OBDal readOnlyDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);

      // ReturnShipmentUtils.fetch* batch queries — all return empty result sets.
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      // Shipment + organization, resolved via the read-only DAL instance.
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("org-1");
      ShipmentInOut shipment = mock(ShipmentInOut.class);
      when(shipment.getOrganization()).thenReturn(org);
      when(readOnlyDal.get(ShipmentInOut.class, "ret-1")).thenReturn(shipment);

      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(readOnlyDal.get(OrganizationInformation.class, "org-1")).thenReturn(orgInfo);
      when(orgInfo.getTaxID()).thenReturn("B12345678");
      when(orgInfo.getLocationAddress()).thenReturn(null);
      Organization infoOrg = mock(Organization.class);
      when(infoOrg.getName()).thenReturn("Acme Corp");
      when(orgInfo.getOrganization()).thenReturn(infoOrg);

      JSONObject rec = new JSONObject().put("id", "ret-1");
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(rec)));
      NeoContext ctx = NeoContext.builder()
          .specName("return-to-vendor-shipment").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
          .recordId("ret-1").build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      JSONObject enriched = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue(enriched.has("issuerOrg"));
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
   * ACTION "importReceiptLines", one valid line with qty>0 within what is still returnable:
   * the line is created and saved; response data has importedCount=1.
   */
  @Test
  public void testHandleImportReceiptLinesHappyPathImportsOneLine() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ReturnedQuantityTestSupport.stubReturnedQuantities(dal, 10L, (src, excl) -> BigDecimal.ZERO);

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);
      when(returnDoc.getClient()).thenReturn(mock(Client.class));
      when(returnDoc.getOrganization()).thenReturn(mock(Organization.class));

      ShipmentInOutLine sourceLine =
          ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "5");
      when(dal.get(ShipmentInOutLine.class, "src-1")).thenReturn(sourceLine);
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

  /** Runs importReceiptLines against a receipt line of {@code received} units, {@code returned} of them
   *  already on other (non-voided) returns, and answers the response; the lines saved are reported
   *  through {@code saved}. */
  private NeoResponse importReceiptLines(String received, String returned, JSONArray lines,
      java.util.List<ShipmentInOutLine> saved) throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ReturnedQuantityTestSupport.stubReturnedQuantities(dal, 10L,
          (src, excl) -> new BigDecimal(returned));

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);
      when(returnDoc.getClient()).thenReturn(mock(Client.class));
      when(returnDoc.getOrganization()).thenReturn(mock(Organization.class));
      ShipmentInOutLine sourceLine =
          ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", received);
      when(dal.get(ShipmentInOutLine.class, "src-1")).thenReturn(sourceLine);
      when(sourceLine.getUOM()).thenReturn(mock(UOM.class));

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenAnswer(inv -> {
        ShipmentInOutLine retLine = mock(ShipmentInOutLine.class);
        saved.add(retLine);
        return retLine;
      });

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("importReceiptLines").recordId("ret-1")
          .requestBody(new JSONObject().put("lines", lines)).build();
      return handler.handle(ctx);
    }
  }

  private static JSONObject importEntry(String quantity) throws Exception {
    return new JSONObject().put("sourceLineId", "src-1").put("returnQuantity", quantity);
  }

  /**
   * Over-return on import: more than what is left of the receipt line is a 422 naming the product,
   * the requested and the available quantity, and nothing is created.
   */
  @Test
  public void testHandleImportReceiptLinesOverReturnIsRefusedAndNothingCreated() throws Exception {
    java.util.List<ShipmentInOutLine> saved = new java.util.ArrayList<>();

    NeoResponse result = importReceiptLines("10", "6", new JSONArray().put(importEntry("5")), saved);

    assertNotNull(result);
    assertEquals(422, result.getHttpStatus());
    JSONObject error = result.getBody().getJSONObject("error");
    assertEquals("returnQuantityExceedsAvailable", error.getString("code"));
    assertTrue(error.getString("message"), error.getString("message").contains("Agua"));
    assertEquals(5, error.getDouble("requested"), 0.0001);
    assertEquals(4, error.getDouble("available"), 0.0001);
    assertTrue("a refused import creates no line", saved.isEmpty());
  }

  /** A second partial return with exactly what is left works. */
  @Test
  public void testHandleImportReceiptLinesSecondPartialWithinRemainingIsAccepted() throws Exception {
    java.util.List<ShipmentInOutLine> saved = new java.util.ArrayList<>();

    NeoResponse result = importReceiptLines("10", "6", new JSONArray().put(importEntry("4")), saved);

    assertEquals(200, result.getHttpStatus());
    assertEquals(1, saved.size());
  }

  /** The same source line twice in one request accumulates: 3 + 3 against 5 left is refused. */
  @Test
  public void testHandleImportReceiptLinesDuplicateSourceLinesAccumulate() throws Exception {
    java.util.List<ShipmentInOutLine> saved = new java.util.ArrayList<>();

    NeoResponse result = importReceiptLines("5", "0",
        new JSONArray().put(importEntry("3")).put(importEntry("3")), saved);

    assertEquals(422, result.getHttpStatus());
    assertEquals("returnQuantityExceedsAvailable",
        result.getBody().getJSONObject("error").getString("code"));
    assertEquals(6, result.getBody().getJSONObject("error").getDouble("requested"), 0.0001);
    assertTrue(saved.isEmpty());
  }

  /** Nothing left at all: any quantity is refused with available 0. */
  @Test
  public void testHandleImportReceiptLinesFullyReturnedLineAcceptsNothing() throws Exception {
    NeoResponse result = importReceiptLines("5", "5", new JSONArray().put(importEntry("1")),
        new java.util.ArrayList<>());

    assertEquals(422, result.getHttpStatus());
    assertEquals(0, result.getBody().getJSONObject("error").getDouble("available"), 0.0001);
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

  // ── actionContracts() — published to agents ───────────────────────────────

  /** The purchase return is published in the order the flow runs, mirroring the sales return. */
  @Test
  public void testActionContractsPublishTheReturnFlowInOrder() {
    java.util.Map<String, NeoActionContract> contracts = handler.actionContracts();

    assertEquals(java.util.List.of("availableReceipts", "availableReceiptLines",
        "importReceiptLines", "rectifiableInvoices", "createReturnInvoice", "post", "unpost",
        "documentAction"),
        java.util.List.copyOf(contracts.keySet()));
    assertFalse(contracts.get("availableReceipts").isMutating());
    assertFalse(contracts.get("availableReceiptLines").isMutating());
    assertTrue(contracts.get("importReceiptLines").isMutating());
    assertTrue(contracts.get("documentAction").getDescription().contains("DRAFT"));
  }

  /** Every declared action is one handle() dispatches (a 400 with no record and no body), except
   *  documentAction, which handle() leaves to NEO's native completion process. */
  @Test
  public void testEveryDeclaredActionIsServedByHandle() {
    for (String action : handler.actionContracts().keySet()) {
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(action).recordId(null).build();
      NeoResponse result = handler.handle(ctx);
      if ("documentAction".equals(action)) {
        assertNull("completion is the native process", result);
        continue;
      }
      if ("post".equals(action) || "unpost".equals(action)) {
        // ETP-5714: served by DocumentPostingService, which handle() consults first; it is not
        // injected here, so handle() falls through exactly as for any action the entity leaves out.
        continue;
      }
      assertNotNull(action + " is dispatched by handle()", result);
      assertEquals(action, HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  /** The contract refuses malformed agent calls and accepts the bodies the flow sends. */
  @Test
  public void testActionContractsJudgeAgentCalls() throws Exception {
    java.util.Map<String, NeoActionContract> contracts = handler.actionContracts();

    assertEquals(422, NeoActionContract.validate(contracts, "availableReceipts",
        new JSONObject()).getHttpStatus());
    assertNull(NeoActionContract.validate(contracts, "availableReceipts",
        new JSONObject().put("businessPartner", "BP1")));
    assertEquals(422, NeoActionContract.validate(contracts, "availableReceiptLines",
        new JSONObject().put("receiptId", "R1")).getHttpStatus());
    assertNull(NeoActionContract.validate(contracts, "availableReceiptLines",
        new JSONObject().put("receiptId", "R1").put("businessPartner", "BP1")));
    assertEquals(422, NeoActionContract.validate(contracts, "importReceiptLines",
        new JSONObject().put("lines", "x")).getHttpStatus());
    assertNull(NeoActionContract.validate(contracts, "importReceiptLines", new JSONObject()
        .put("lines", new JSONArray().put(importEntry("1")))));
    assertNull(NeoActionContract.validate(contracts, "createReturnInvoice",
        new JSONObject().put("originInvoices", new JSONArray().put("INV1"))));
    assertNull(NeoActionContract.validate(contracts, "documentAction",
        new JSONObject().put("docAction", "CO")));
    assertEquals(422, NeoActionContract.validate(contracts, "documentAction",
        new JSONObject().put("docAction", "VO")).getHttpStatus());
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
      stubReturnInvoiceQueries(dal, "inv-src-1");

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

      // ReturnInvoiceSupport.findSourceInvoice uses dal.getSession() + createQuery
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
      assertEquals(BP_FINANCIALS_MISSING, errorMessage(result));
    }
  }

  // ── ETP-5381: atomic create-and-confirm, P5 guard, rectifiableInvoices ────

  /**
   * ETP-5381: the payload is built from the invoice re-read AFTER completion, so it must report
   * the confirmed status. A {@code DR} here would mean the rectificative invoice was left as a
   * draft — the exact failure the atomic create-and-confirm step exists to prevent.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void handleCreateReturnInvoice_reportsConfirmedStatusAndConfirmsOnce() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      stubReturnInvoiceQueries(dal, "inv-src-1");

      mockCreateReturnInvoiceFlow(dal, provider, "ret-inv-1");

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "ret-inv-1", "REC-2000001", "CO");

        NeoResponse result = handler.handle(NeoContext.builder()
            .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
            .fieldName("createReturnInvoice").recordId("ret-1").build());

        assertNotNull(result);
        assertEquals(200, result.getHttpStatus());
        JSONObject data = result.getBody().getJSONObject("response").getJSONObject("data");
        assertEquals("ret-inv-1", data.getString("id"));
        assertEquals("REC-2000001", data.getString("documentNo"));
        assertEquals("The response must report the confirmed status, not DR",
            "CO", data.getString("documentStatus"));
        verify(completion.processInvoiceUtil, Mockito.times(1))
            .process(eq("ret-inv-1"), eq("CO"), anyString(), anyString(), anyString(), any(), any());
        verify(dal, Mockito.never()).rollbackAndClose();
      }
    }
  }

  /**
   * ETP-5381: no confirmed invoice can be rectified → 400 with the "select an invoice" message,
   * and no invoice is created. A rectificative invoice with no {@code C_Invoice_Reverse} row
   * cannot ever be confirmed, so failing before any write beats leaving a stuck draft behind.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleCreateReturnInvoice_noRectifiableInvoices_returnsBadRequestAndCreatesNothing()
      throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      stubReturnInvoiceQueries(dal); // no candidates

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);
      when(returnDoc.getDocumentStatus()).thenReturn("CO");
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getProduct()).thenReturn(mock(Product.class));
      when(returnDoc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));
      Organization org = mock(Organization.class);
      when(returnDoc.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");
      stubDocTypeLookup(dal, "org-1");

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("ret-1").build());

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertEquals(ReturnShipmentUtils.ERR_RECTIFIED_INVOICE_REQUIRED, errorMessage(result));
      // ETP-5712: the agent must read WHY (nothing was invoiced) and WHAT to do, not a UI hint.
      assertTrue(errorMessage(result), errorMessage(result).contains("no invoice to rectify"));
      assertTrue(errorMessage(result), errorMessage(result).contains("originInvoices"));
      verify(provider, Mockito.never()).get(Invoice.class);
      verify(dal, Mockito.never()).save(any());
    }
  }

  /**
   * ETP-5381: an explicit {@code originInvoices} selection wins over the auto-detected candidate.
   * The SQL fallback offers {@code inv-auto}; the body asks for {@code inv-picked}, and that is
   * the invoice the {@code C_Invoice_Reverse} link must point at.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void handleCreateReturnInvoice_explicitOriginInvoicesWinOverAutoDetection()
      throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      stubReturnInvoiceQueries(dal, "inv-auto");

      mockCreateReturnInvoiceFlow(dal, provider, "ret-inv-2");
      Invoice picked = mock(Invoice.class);
      when(dal.get(eq(Invoice.class), eq("inv-picked"))).thenReturn(picked);
      Invoice auto = mock(Invoice.class);
      when(dal.get(eq(Invoice.class), eq("inv-auto"))).thenReturn(auto);
      ReversedInvoice revLink = mock(ReversedInvoice.class);
      when(provider.get(eq(ReversedInvoice.class))).thenReturn(revLink);

      JSONObject body = new JSONObject().put("originInvoices", new JSONArray().put("inv-picked"));

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "ret-inv-2", "REC-2000002", "CO");

        NeoResponse result = handler.handle(NeoContext.builder()
            .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
            .fieldName("createReturnInvoice").recordId("ret-1").requestBody(body).build());

        assertNotNull(result);
        assertEquals(200, result.getHttpStatus());
        verify(revLink).setReversedInvoice(picked);
        verify(revLink, Mockito.never()).setReversedInvoice(auto);
      }
    }
  }

  /**
   * The {@code rectifiableInvoices} action returns the selectable candidate list with each
   * chain-detected row flagged, the ids the server would have auto-picked, and whether this
   * return document already has an invoice — the three pieces the modal needs to preselect a
   * choice and to disable the button instead of letting the user walk into a 409.
   */
  @Test
  public void handleRectifiableInvoices_returnsCandidatesSuggestionAndHasReturnInvoiceFlag()
      throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, false, Arrays.asList("inv-new"),
          Arrays.asList("inv-new", "inv-old"));

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("rectifiableInvoices").recordId("ret-1").build());

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONObject data = result.getBody().getJSONObject("response").getJSONObject("data");
      JSONArray invoices = data.getJSONArray("invoices");
      assertEquals(2, invoices.length());
      assertEquals("inv-new", invoices.getJSONObject(0).getString("id"));
      assertEquals("inv-old", invoices.getJSONObject(1).getString("id"));
      assertTrue("The chain-detected row must carry the preselection flag",
          invoices.getJSONObject(0).getBoolean("suggested"));
      assertFalse("A selectable row outside the chain is offered but not suggested",
          invoices.getJSONObject(1).getBoolean("suggested"));
      JSONArray suggested = data.getJSONArray("suggestedInvoiceIds");
      assertEquals("The suggestion must be the same candidate resolveRectifiedInvoiceIds picks",
          1, suggested.length());
      assertEquals("inv-new", suggested.getString(0));
      assertFalse(data.getBoolean("hasReturnInvoice"));
    }
  }

  /**
   * ETP-5381: a return-to-vendor shipment created by hand has no {@code Canceled_Inoutline_ID}
   * chain, so nothing is auto-detected — but the action must still hand the modal every invoice
   * the user may pick. Before the split this returned an empty list and blocked the user.
   */
  @Test
  public void handleRectifiableInvoices_noChain_stillReturnsSelectableInvoices() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, false, Collections.<String>emptyList(),
          Arrays.asList("inv-a", "inv-b"));

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("rectifiableInvoices").recordId("ret-1").build());

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONObject data = result.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(2, data.getJSONArray("invoices").length());
      assertEquals(0, data.getJSONArray("suggestedInvoiceIds").length());
      assertFalse(data.getJSONArray("invoices").getJSONObject(0).getBoolean("suggested"));
    }
  }

  /**
   * ETP-5381: a return covering two shipments billed on two invoices preselects BOTH — the case
   * the old singular {@code suggestedInvoiceId} could not express.
   */
  @Test
  public void handleRectifiableInvoices_twoDetectedInvoices_suggestsBoth() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, false, Arrays.asList("inv-new", "inv-old"),
          Arrays.asList("inv-new", "inv-old", "inv-unrelated"));

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("rectifiableInvoices").recordId("ret-1").build());

      assertNotNull(result);
      JSONObject data = result.getBody().getJSONObject("response").getJSONObject("data");
      JSONArray suggested = data.getJSONArray("suggestedInvoiceIds");
      assertEquals(2, suggested.length());
      assertEquals("inv-new", suggested.getString(0));
      assertEquals("inv-old", suggested.getString(1));
      JSONArray invoices = data.getJSONArray("invoices");
      assertEquals(3, invoices.length());
      assertTrue(invoices.getJSONObject(0).getBoolean("suggested"));
      assertTrue(invoices.getJSONObject(1).getBoolean("suggested"));
      assertFalse(invoices.getJSONObject(2).getBoolean("suggested"));
    }
  }

  @Test
  public void handleRectifiableInvoices_missingRecordIdReturnsBadRequest() {
    NeoResponse result = handler.handle(NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("rectifiableInvoices").build());
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }


  // ── ETP-5712: discoverability, response shape, duplicates, zero-amount guard ──────

  /**
   * ETP-5712: confirming a return does NOT create the factura rectificativa, and an agent that
   * reads only {@code documentAction} must learn that and the call that does. The goods
   * receipt/shipment keep their own text ({@link InOutCompletionContract#CONTRACT}).
   */
  @Test
  public void testDocumentActionDescriptionSaysConfirmingDoesNotCreateTheRectificativa() {
    String text = handler.actionContracts().get("documentAction").getDescription();

    assertTrue(text, text.contains("DRAFT"));
    assertTrue(text, text.contains("createReturnInvoice"));
    assertTrue(text, text.contains("factura rectificativa"));
    assertTrue(text, text.contains("does NOT create"));
    assertFalse("the return text must not be the goods receipt/shipment one",
        text.contains("createGoodsReceipt"));
    assertFalse("the shared goods receipt/shipment contract stays untouched",
        InOutCompletionContract.CONTRACT.getDescription().contains("createReturnInvoice"));
  }

  /** ETP-5712: the createReturnInvoice description warns about duplicates and lists the response. */
  @Test
  public void testCreateReturnInvoiceDescriptionWarnsAboutDuplicatesAndListsTheResponse() {
    String text = handler.actionContracts().get("createReturnInvoice").getDescription();

    assertTrue(text, text.contains("hasReturnInvoice"));
    assertTrue(text, text.contains("previousReturnInvoices"));
    assertTrue(text, text.contains("returnInvoiceWithoutAmount"));
    assertTrue(text, text.contains("grandTotalAmount"));
    assertTrue(text, text.contains("factura rectificativa"));
    String flow = handler.actionContracts().get("availableReceipts").getDescription();
    assertTrue("the FLOW text says the confirmation does not create it: " + flow,
        flow.contains("does NOT create the factura rectificativa"));
  }

  /**
   * ETP-5712: the answer says what was created: document type, direction, amounts READ AFTER the
   * completion (the DB triggers compute the totals there, so the invoice is refreshed first), the
   * return it is linked to, the invoices it rectifies, and the rectificativas the return already
   * had before this call.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void handleCreateReturnInvoice_responseCarriesTypeAmountsAndLinkage() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      stubReturnInvoiceQueries(dal, "inv-src-1");
      mockCreateReturnInvoiceFlow(dal, provider, "ret-inv-1");
      ShipmentInOut returnDoc = dal.get(ShipmentInOut.class, "ret-1");
      when(returnDoc.getId()).thenReturn("ret-1");
      when(returnDoc.getDocumentNo()).thenReturn("RTV-0001");
      Invoice origin = mock(Invoice.class);
      when(origin.getDocumentNo()).thenReturn("FC-0042");
      when(dal.get(eq(Invoice.class), eq("inv-src-1"))).thenReturn(origin);

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        Invoice completed = mockCompletedInvoice(dal, "ret-inv-1", "REC-2000001", "CO");
        DocumentType type = mock(DocumentType.class);
        when(type.getName()).thenReturn("Factura rectificativa de compra");
        when(completed.getDocumentType()).thenReturn(type);
        when(completed.isSalesTransaction()).thenReturn(false);
        when(completed.getGrandTotalAmount()).thenReturn(new BigDecimal("-60.50"));
        when(completed.getSummedLineAmount()).thenReturn(new BigDecimal("-50.00"));

        NeoResponse result = handler.handle(NeoContext.builder()
            .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
            .fieldName("createReturnInvoice").recordId("ret-1").build());

        assertEquals(200, result.getHttpStatus());
        JSONObject data = result.getBody().getJSONObject("response").getJSONObject("data");
        assertEquals("ret-inv-1", data.getString("id"));
        assertEquals("REC-2000001", data.getString("documentNo"));
        assertEquals("CO", data.getString("documentStatus"));
        assertEquals("Factura rectificativa de compra", data.getString("documentType"));
        assertFalse(data.getBoolean("isSalesTransaction"));
        assertEquals(-60.50, data.getDouble("grandTotalAmount"), 0.001);
        assertEquals(-50.00, data.getDouble("totalLines"), 0.001);
        assertEquals("ret-1", data.getString("linkedReturnId"));
        assertEquals("RTV-0001", data.getString("returnDocumentNo"));
        JSONArray rectified = data.getJSONArray("rectifiedInvoices");
        assertEquals(1, rectified.length());
        assertEquals("inv-src-1", rectified.getJSONObject(0).getString("id"));
        assertEquals("FC-0042", rectified.getJSONObject(0).getString("documentNo"));
        assertEquals(0, data.getJSONArray("previousReturnInvoices").length());

        org.mockito.InOrder order = Mockito.inOrder(completion.processInvoiceUtil, dal);
        order.verify(completion.processInvoiceUtil)
            .process(eq("ret-inv-1"), eq("CO"), anyString(), anyString(), anyString(), any(), any());
        order.verify(dal).refresh(completed);
      }
    }
  }

  /**
   * ETP-5712: a second rectificative on the same return is ALLOWED (ETP-5381 removed the guard on
   * purpose) but reported: the answer lists the non-voided ones the return already had BEFORE this
   * call, so the agent sees it duplicated.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void handleCreateReturnInvoice_secondCallIsAllowedAndListsPreviousReturnInvoices()
      throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      stubReturnInvoiceQueries(dal, true, Arrays.asList("inv-src-1"), Arrays.asList("inv-src-1"),
          Collections.singletonList(new String[] {"prev-inv-1", "REC-OLD", "CO", "ret-1"}));
      mockCreateReturnInvoiceFlow(dal, provider, "ret-inv-2");

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "ret-inv-2", "REC-NEW", "CO");

        NeoResponse result = handler.handle(NeoContext.builder()
            .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
            .fieldName("createReturnInvoice").recordId("ret-1").build());

        assertEquals("a duplicate is allowed", 200, result.getHttpStatus());
        JSONObject data = result.getBody().getJSONObject("response").getJSONObject("data");
        assertEquals("REC-NEW", data.getString("documentNo"));
        JSONArray previous = data.getJSONArray("previousReturnInvoices");
        assertEquals(1, previous.length());
        assertEquals("prev-inv-1", previous.getJSONObject(0).getString("id"));
        assertEquals("REC-OLD", previous.getJSONObject(0).getString("documentNo"));
        assertEquals("CO", previous.getJSONObject(0).getString("documentStatus"));
        verify(completion.processInvoiceUtil, Mockito.times(1))
            .process(eq("ret-inv-2"), eq("CO"), anyString(), anyString(), anyString(), any(), any());
      }
    }
  }

  /**
   * ETP-5712: a product whose price cannot be resolved (no order/invoice line price, no source
   * invoice line, no price-list entry) must not become a confirmed rectificativa of 0: 422, and
   * NOTHING is persisted or completed.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void handleCreateReturnInvoice_productWithoutPriceIsRefusedWith422AndPersistsNothing()
      throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      stubReturnInvoiceQueries(dal, "inv-src-1");
      mockCreateReturnInvoiceFlow(dal, provider, "ret-inv-3");
      ShipmentInOut returnDoc = dal.get(ShipmentInOut.class, "ret-1");
      Product agua = returnDoc.getMaterialMgmtShipmentInOutLineList().get(0).getProduct();
      when(agua.getName()).thenReturn("Agua");
      InvoiceLine unpriced = mock(InvoiceLine.class);
      when(unpriced.getUnitPrice()).thenReturn(BigDecimal.ZERO);
      when(handler.createDraftInvoiceHandler.createShipmentInvoiceLine(
          any(), any(), any(), Mockito.anyLong())).thenReturn(unpriced);

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        NeoResponse result = handler.handle(NeoContext.builder()
            .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
            .fieldName("createReturnInvoice").recordId("ret-1").build());

        assertEquals(422, result.getHttpStatus());
        JSONObject error = result.getBody().getJSONObject("error");
        assertEquals("returnInvoiceWithoutAmount", error.getString("code"));
        assertEquals(422, error.getInt("status"));
        assertTrue(error.getString("message"), error.getString("message").contains("Agua"));
        assertEquals("Agua", error.getJSONArray("products").getString(0));
        verify(dal, Mockito.never()).save(any());
        verify(dal, Mockito.never()).flush();
        verify(dal, Mockito.never()).rollbackAndClose();
        verify(provider, Mockito.never()).get(ReversedInvoice.class);
        verify(completion.processInvoiceUtil, Mockito.never())
            .process(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
      }
    }
  }

  /**
   * ETP-5712: when the completion is rejected (e.g. closed period) AFTER the draft was flushed,
   * the action answers the business error as 400 and builds NO success payload. Our code neither
   * commits nor swallows into a success: it rolls the transaction back explicitly (once, on top
   * of the core's own rollback in {@code ProcessInvoiceUtil.process}).
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void handleCreateReturnInvoice_completionRejected_answers400RollsBackOnceAndNeverCommits()
      throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      stubReturnInvoiceQueries(dal, "inv-src-1");
      mockCreateReturnInvoiceFlow(dal, provider, "ret-inv-9");

      try (CompletionMocks completion = new CompletionMocks(dal,
          InvoiceCompletionTestSupport.completionError("The period is closed"))) {
        NeoResponse result = handler.handle(NeoContext.builder()
            .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
            .fieldName("createReturnInvoice").recordId("ret-1").build());

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
        assertEquals("The period is closed", errorMessage(result));
        assertNull("no success payload for a rejected completion",
            result.getBody().optJSONObject("response"));
        verify(completion.processInvoiceUtil, Mockito.times(1))
            .process(eq("ret-inv-9"), eq("CO"), anyString(), anyString(), anyString(), any(), any());
        verify(dal, Mockito.never()).commitAndClose();
        verify(dal, Mockito.times(1)).rollbackAndClose();
      }
    }
  }

  /**
   * ETP-5712: a completion that blows up at runtime (a hook throwing, which the core does NOT roll
   * back) is answered as the 500 it always was, and the draft is discarded explicitly so the
   * request filter cannot commit it.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void handleCreateReturnInvoice_completionThrowsAtRuntime_answers500AndRollsBack()
      throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      stubReturnInvoiceQueries(dal, "inv-src-1");
      mockCreateReturnInvoiceFlow(dal, provider, "ret-inv-8");

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        when(completion.processInvoiceUtil.process(
            anyString(), eq("CO"), anyString(), anyString(), anyString(), any(), any()))
            .thenThrow(new IllegalStateException("hook boom"));

        NeoResponse result = handler.handle(NeoContext.builder()
            .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
            .fieldName("createReturnInvoice").recordId("ret-1").build());

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
        verify(dal, Mockito.times(1)).rollbackAndClose();
        verify(dal, Mockito.never()).commitAndClose();
      }
    }
  }

  /**
   * ETP-5712: the core may already have rolled back and closed the session, so our own rollback
   * can fail ("Session is null"). That must never hide the real error being answered.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void handleCreateReturnInvoice_rollbackItselfFails_stillAnswersTheOriginalError()
      throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      stubReturnInvoiceQueries(dal, "inv-src-1");
      mockCreateReturnInvoiceFlow(dal, provider, "ret-inv-7");
      Mockito.doThrow(new IllegalStateException("Session is null")).when(dal).rollbackAndClose();

      try (CompletionMocks completion = new CompletionMocks(dal,
          InvoiceCompletionTestSupport.completionError("The period is closed"))) {
        NeoResponse result = handler.handle(NeoContext.builder()
            .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
            .fieldName("createReturnInvoice").recordId("ret-1").build());

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
        assertEquals("The period is closed", errorMessage(result));
        verify(dal, Mockito.times(1)).rollbackAndClose();
      }
    }
  }

  // ── shared stubs for the createReturnInvoice happy path ───────────────────

  /**
   * Stubs everything {@code handleCreateReturnInvoice} needs between the shipment lookup and the
   * completion: a completed return shipment with one product line, a matching rectificative doc
   * type, a source invoice found through the {@code findSourceInvoice} HQL (so the header copies
   * its financials instead of deriving them from the vendor), the invoice header built via
   * {@link OBProvider}, and the invoice-line builder.
   *
   * @return the invoice mock {@code buildReturnInvoiceHeader} will hand back
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private Invoice mockCreateReturnInvoiceFlow(OBDal dal, OBProvider provider, String invoiceId) {
    ShipmentInOut returnDoc = mock(ShipmentInOut.class);
    when(dal.get(ShipmentInOut.class, "ret-1")).thenReturn(returnDoc);
    when(returnDoc.getDocumentStatus()).thenReturn("CO");

    ShipmentInOutLine line = mock(ShipmentInOutLine.class);
    when(line.getProduct()).thenReturn(mock(Product.class));
    when(line.getMovementQuantity()).thenReturn(new BigDecimal("-5.00"));
    ShipmentInOutLine origLine = mock(ShipmentInOutLine.class);
    when(origLine.getId()).thenReturn("orig-line-1");
    when(line.getCanceledInoutLine()).thenReturn(origLine);
    when(returnDoc.getMaterialMgmtShipmentInOutLineList())
        .thenReturn(Collections.singletonList(line));

    Organization org = mock(Organization.class);
    when(returnDoc.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    stubDocTypeLookup(dal, "org-1");
    when(returnDoc.getBusinessPartner()).thenReturn(mock(BusinessPartner.class));

    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    Query<Invoice> invoiceQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(Invoice.class))).thenReturn(invoiceQuery);
    when(invoiceQuery.setParameter(anyString(), any())).thenReturn(invoiceQuery);
    when(invoiceQuery.setMaxResults(anyInt())).thenReturn(invoiceQuery);
    when(invoiceQuery.list()).thenReturn(Collections.singletonList(mock(Invoice.class)));

    Invoice invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn(invoiceId);
    when(provider.get(eq(Invoice.class))).thenReturn(invoice);

    InvoiceLine il = mock(InvoiceLine.class);
    // ETP-5712: a line the price chain cannot price is refused, so the happy path has a price.
    when(il.getUnitPrice()).thenReturn(new BigDecimal("10.00"));
    when(il.getTax()).thenReturn(mock(org.openbravo.model.financialmgmt.tax.TaxRate.class));
    when(handler.createDraftInvoiceHandler.createShipmentInvoiceLine(
        any(), any(), any(), Mockito.anyLong())).thenReturn(il);
    InvoiceFromOrderSupport support = mock(InvoiceFromOrderSupport.class);
    when(handler.createDraftInvoiceHandler.getSupport()).thenReturn(support);

    when(dal.get(eq(Invoice.class), eq("inv-src-1"))).thenReturn(mock(Invoice.class));
    when(dal.get(eq(Invoice.class), eq("inv-auto"))).thenReturn(mock(Invoice.class));
    stubNoExistingReversedInvoiceLink(dal);
    when(provider.get(eq(ReversedInvoice.class))).thenReturn(mock(ReversedInvoice.class));
    return invoice;
  }

  /** A rectificative doc type whose organization matches, so the first loop selects it. */
  @SuppressWarnings("unchecked")
  private static void stubDocTypeLookup(OBDal dal, String orgId) {
    DocumentType docType = mock(DocumentType.class);
    Organization docOrg = mock(Organization.class);
    when(docType.getOrganization()).thenReturn(docOrg);
    when(docOrg.getId()).thenReturn(orgId);
    OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(docType));
  }

  /** {@code linkRectifiedInvoices}' "is this pair already linked?" probe answers "no". */
  @SuppressWarnings("unchecked")
  private static void stubNoExistingReversedInvoiceLink(OBDal dal) {
    OBCriteria<ReversedInvoice> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(ReversedInvoice.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.<ReversedInvoice>emptyList());
  }

  /** Reads the human-readable message out of a {@code NeoResponse.error} payload. */
  private static String errorMessage(NeoResponse response) {
    return response.getBody().optJSONObject("error").optString("message", null);
  }

  /**
   * ETP-5714: the keys ReturnToVendorShipmentHeaderHandler's afterHandle adds to a GET record are declared, so an MCP
   * {@code fields:[…]} projection does not report them in {@code unknownFields} while the same
   * response carries them.
   */
  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName("return to vendor: enriched response keys are declared")
  void enrichedKeysAreDeclaredReturnToVendorShipmentHeaderHandler() {
    java.util.Set<String> declared = new ReturnToVendorShipmentHeaderHandler().responseEnrichedFields();
    for (String key : java.util.List.of("sourceReceipts", "sourceReceiptDocNo", "returnInvoices", "hasReturnInvoice", "linesCount", "invoiceStatus")) {
      org.junit.jupiter.api.Assertions.assertTrue(declared.contains(key), key + " in " + declared);
    }
  }
}
