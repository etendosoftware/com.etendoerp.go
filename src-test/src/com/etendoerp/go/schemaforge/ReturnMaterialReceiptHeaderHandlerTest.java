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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.plm.Product;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.ReversedInvoice;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link ReturnMaterialReceiptHeaderHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code handle()} — non-ACTION exit, clone delegation, unknown action, wrong method,
 *       documentAction with null/real receiptId (fillMissingStorageBins paths), and
 *       missing required params for each action.</li>
 *   <li>{@code handleImportShipmentLines()} — missing recordId, receipt not found, no lines.</li>
 *   <li>{@code handleAvailableShipments()} — missing businessPartner, SQL empty, SQL with rows.</li>
 *   <li>{@code handleAvailableShipmentLines()} — missing shipmentId, SQL empty, SQL with rows.</li>
 *   <li>{@code handleCreateReturnInvoice()} — missing recordId, receipt not found,
 *       receipt not completed, no product lines, no rectificative (ARI) doc type.</li>
 *   <li>{@code afterHandle()} — guard conditions, no-id enrichment, SQL empty path,
 *       SQL with rows (addShipmentToMap + addInvoiceToMap), issuerOrg enrichment on a
 *       detail GET (ETP-5124), and outer-catch path.</li>
 * </ul>
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

  // ── handle() — documentAction / fillMissingStorageBins ────────────────────

  @Test
  public void testHandleDocumentActionWithNullReceiptIdReturnsNull() {
    // fillMissingStorageBins early-returns when receiptId is null
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction").recordId(null).build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleDocumentActionReceiptNotFoundSkipsFillAndReturnsNull() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(null);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("documentAction").recordId("rec-1").build();
      assertNull(handler.handle(ctx));
    }
  }

  @Test
  public void testHandleDocumentActionReceiptFoundWithNoLinesFlushesThenReturnsNull() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);
      when(receipt.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.<ShipmentInOutLine>emptyList());

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("documentAction").recordId("rec-1").build();
      assertNull(handler.handle(ctx));
    }
  }

  // ── handle() — action guard conditions ────────────────────────────────────

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

  // ── handleImportShipmentLines — receipt not found and no lines ────────────

  @Test
  public void testHandleImportShipmentLinesReceiptNotFoundReturnsNotFound() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(null);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("importShipmentLines").recordId("rec-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_NOT_FOUND, result.getHttpStatus());
    }
  }

  @Test
  public void testHandleImportShipmentLinesNoLinesInBodyReturnsBadRequest() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);

      // no requestBody → requestedLines is null → "No lines specified"
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("importShipmentLines").recordId("rec-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  // ── handleAvailableShipments — full SQL path ──────────────────────────────

  @Test
  public void testHandleAvailableShipmentsWithValidBpIdReturnsEmptyList() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      JSONObject body = new JSONObject().put("businessPartner", "bp-1");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("availableShipments").requestBody(body).build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(0,
          result.getBody().getJSONObject("response").getJSONArray("data").length());
    }
  }

  @Test
  public void testHandleAvailableShipmentsReturnsRowsFromSql() throws Exception {
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
      when(rs.getString(1)).thenReturn("ship-1");
      when(rs.getString(2)).thenReturn("RC-001");
      when(rs.getString(3)).thenReturn("2026-01-15");
      when(rs.getString(4)).thenReturn("Supplier A");
      when(rs.getString(5)).thenReturn("bp-1");

      JSONObject body = new JSONObject().put("businessPartner", "bp-1");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("availableShipments").requestBody(body).build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(1, data.length());
      assertEquals("RC-001", data.getJSONObject(0).getString("documentNo"));
    }
  }

  // ── handleAvailableShipmentLines — full SQL path ──────────────────────────

  @Test
  public void testHandleAvailableShipmentLinesWithValidShipmentIdReturnsEmptyList() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      JSONObject body = new JSONObject().put("shipmentId", "ship-1");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("availableShipmentLines").requestBody(body).build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(0,
          result.getBody().getJSONObject("response").getJSONArray("data").length());
    }
  }

  @Test
  public void testHandleAvailableShipmentLinesReturnsRowsFromSql() throws Exception {
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
      when(rs.getString(3)).thenReturn("Widget A");
      when(rs.getString(4)).thenReturn("uom-1");
      when(rs.getBigDecimal(5)).thenReturn(new BigDecimal("10.00"));

      JSONObject body = new JSONObject().put("shipmentId", "ship-1");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("availableShipmentLines").requestBody(body).build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(1, data.length());
      assertEquals("Widget A", data.getJSONObject(0).getString("product$_identifier"));
    }
  }

  // ── handleCreateReturnInvoice — guard conditions ──────────────────────────

  @Test
  public void testHandleCreateReturnInvoiceReceiptNotFoundReturnsNotFound() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(null);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("rec-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_NOT_FOUND, result.getHttpStatus());
    }
  }

  @Test
  public void testHandleCreateReturnInvoiceReceiptNotCompletedReturnsBadRequest() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);
      when(receipt.getDocumentStatus()).thenReturn("DR");

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("rec-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  @Test
  public void testHandleCreateReturnInvoiceNoProductLinesReturnsBadRequest() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);
      when(receipt.getDocumentStatus()).thenReturn("CO");
      when(receipt.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.<ShipmentInOutLine>emptyList());

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("rec-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  @Test
  public void testHandleCreateReturnInvoiceNoAriRmDocTypeReturnsInternalError() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);
      when(receipt.getDocumentStatus()).thenReturn("CO");

      // One line with a product so the empty-lines guard is bypassed
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      Product product = mock(Product.class);
      when(line.getProduct()).thenReturn(product);
      when(receipt.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      Organization org = mock(Organization.class);
      when(receipt.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");

      // findAriRmDocType returns empty list → docType is null → 500
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.<DocumentType>emptyList());

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("rec-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }

  // ── findAriRmDocType — org-0 and fallback paths ───────────────────────────

  @Test
  public void testFindAriRmDocTypeUsesOrgZeroFallback() throws Exception {
    // No candidate matches receipt org, but one has org "0" → returns it (second loop)
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(mock(Invoice.class));

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);
      when(receipt.getDocumentStatus()).thenReturn("CO");
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getProduct()).thenReturn(mock(Product.class));
      when(line.getCanceledInoutLine()).thenReturn(null);
      when(receipt.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));
      Organization org = mock(Organization.class);
      when(receipt.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");

      // Candidate has org "0" — matches second loop
      DocumentType docType = mock(DocumentType.class);
      Organization zeroOrg = mock(Organization.class);
      when(docType.getOrganization()).thenReturn(zeroOrg);
      when(zeroOrg.getId()).thenReturn("0");
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(docType));

      // buildReturnInvoiceHeader → OBException (bp missing payment terms) → 400
      BusinessPartner bp = mock(BusinessPartner.class);
      when(receipt.getBusinessPartner()).thenReturn(bp);
      when(bp.getPaymentTerms()).thenReturn(null);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("rec-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  @Test
  public void testFindAriRmDocTypeUsesCandidateFallback() throws Exception {
    // No candidate matches receipt org or "0" → returns candidates.get(0) (last return)
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(mock(Invoice.class));

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);
      when(receipt.getDocumentStatus()).thenReturn("CO");
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getProduct()).thenReturn(mock(Product.class));
      when(line.getCanceledInoutLine()).thenReturn(null);
      when(receipt.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));
      Organization org = mock(Organization.class);
      when(receipt.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");

      // Candidate has unrelated org → neither loop matches → fallback candidates.get(0)
      DocumentType docType = mock(DocumentType.class);
      Organization otherOrg = mock(Organization.class);
      when(docType.getOrganization()).thenReturn(otherOrg);
      when(otherOrg.getId()).thenReturn("org-99");
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(docType));

      BusinessPartner bp = mock(BusinessPartner.class);
      when(receipt.getBusinessPartner()).thenReturn(bp);
      when(bp.getPaymentTerms()).thenReturn(null);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("rec-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  // ── handleCreateReturnInvoice — full success path ─────────────────────────

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  public void testHandleCreateReturnInvoiceSucceedsWithSourceInvoice() throws Exception {
    // Covers: findSourceInvoice HQL path, buildReturnInvoiceHeader if-branch (sourceInvoice!=null),
    // linkReversedInvoice, addReturnInvoiceLines, buildAndSaveInvoiceLine, resolveApplicableTax.
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);
      when(receipt.getDocumentStatus()).thenReturn("CO");

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      Product product = mock(Product.class);
      when(line.getProduct()).thenReturn(product);
      when(line.getMovementQuantity()).thenReturn(new BigDecimal("5.00"));
      when(receipt.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      Organization org = mock(Organization.class);
      when(receipt.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");

      // findAriRmDocType — org matches
      DocumentType docType = mock(DocumentType.class);
      Organization docOrg = mock(Organization.class);
      when(docType.getOrganization()).thenReturn(docOrg);
      when(docOrg.getId()).thenReturn("org-1");
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(docType));

      // findSourceInvoice — origLine present → HQL path
      ShipmentInOutLine origLine = mock(ShipmentInOutLine.class);
      when(line.getCanceledInoutLine()).thenReturn(origLine);
      when(origLine.getId()).thenReturn("orig-line-1");
      Invoice sourceInvoice = mock(Invoice.class);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      Query invoiceQuery = mock(Query.class);
      when(session.createQuery(anyString(), eq(Invoice.class))).thenReturn(invoiceQuery);
      when(invoiceQuery.setParameter(anyString(), any())).thenReturn(invoiceQuery);
      when(invoiceQuery.setMaxResults(anyInt())).thenReturn(invoiceQuery);
      when(invoiceQuery.list()).thenReturn(Collections.singletonList(sourceInvoice));

      // buildReturnInvoiceHeader — sourceInvoice != null path
      Invoice invoice = mock(Invoice.class);
      when(provider.get(eq(Invoice.class))).thenReturn(invoice);
      BusinessPartner bp = mock(BusinessPartner.class);
      when(receipt.getBusinessPartner()).thenReturn(bp);

      // linkReversedInvoice
      ReversedInvoice revLink = mock(ReversedInvoice.class);
      when(provider.get(eq(ReversedInvoice.class))).thenReturn(revLink);

      // buildAndSaveInvoiceLine — createShipmentInvoiceLine returns il
      InvoiceLine il = mock(InvoiceLine.class);
      when(handler.createDraftInvoiceHandler.createShipmentInvoiceLine(
          any(), any(), any(), anyLong())).thenReturn(il);
      // il.getUnitPrice()=null → unitPrice=0; invoice.getPriceList()=null → skip resolvePriceFromPriceList
      // il.getTax()=null → resolveApplicableTax (NPE caught internally → null); unitPrice=0 → no throw

      // ensureDocumentNo, getSupport().ensureLineGrossAmounts, recalculateTotals
      InvoiceFromOrderSupport support = mock(InvoiceFromOrderSupport.class);
      when(handler.createDraftInvoiceHandler.getSupport()).thenReturn(support);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("rec-1").build();
      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
    }
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

  // ── afterHandle() — enrichment with no ids (no DB needed) ────────────────

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

  // ── afterHandle() — enrichment with real id and DB mocks (empty results) ──

  /**
   * Record with an id: all three fetch methods execute their full SQL path.
   * Empty result sets → maps stay empty → record gets empty enrichment arrays.
   * Covers fetchSourceShipments, fetchReturnInvoices, fetchLineCounts SQL bodies.
   */
  @Test
  public void testAfterHandleEnrichesWithRealIdAndEmptySqlResults() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement psShipments = mock(PreparedStatement.class);
      PreparedStatement psInvoices = mock(PreparedStatement.class);
      PreparedStatement psCounts = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(psShipments)
          .thenReturn(psInvoices)
          .thenReturn(psCounts);

      ResultSet rsShipments = mock(ResultSet.class);
      ResultSet rsInvoices = mock(ResultSet.class);
      ResultSet rsCounts = mock(ResultSet.class);
      when(psShipments.executeQuery()).thenReturn(rsShipments);
      when(psInvoices.executeQuery()).thenReturn(rsInvoices);
      when(psCounts.executeQuery()).thenReturn(rsCounts);
      when(rsShipments.next()).thenReturn(false);
      when(rsInvoices.next()).thenReturn(false);
      when(rsCounts.next()).thenReturn(false);

      JSONObject rec = new JSONObject().put("id", "rec-1").put("documentStatus", "DR");
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
  }

  // ── afterHandle() — enrichment with rows (covers addShipmentToMap, addInvoiceToMap) ──

  @Test
  public void testAfterHandleEnrichesWithSqlRows() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement psShipments = mock(PreparedStatement.class);
      PreparedStatement psInvoices = mock(PreparedStatement.class);
      PreparedStatement psCounts = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(psShipments)
          .thenReturn(psInvoices)
          .thenReturn(psCounts);

      // fetchSourceShipments: 1 row → addShipmentToMap
      ResultSet rsShipments = mock(ResultSet.class);
      when(psShipments.executeQuery()).thenReturn(rsShipments);
      when(rsShipments.next()).thenReturn(true, false);
      when(rsShipments.getString(1)).thenReturn("rec-1");
      when(rsShipments.getString(2)).thenReturn("ship-src-1");
      when(rsShipments.getString(3)).thenReturn("RC-001");
      when(rsShipments.getString(4)).thenReturn("CO");

      // fetchReturnInvoices: 1 row → addInvoiceToMap
      ResultSet rsInvoices = mock(ResultSet.class);
      when(psInvoices.executeQuery()).thenReturn(rsInvoices);
      when(rsInvoices.next()).thenReturn(true, false);
      when(rsInvoices.getString(1)).thenReturn("rec-1");
      when(rsInvoices.getString(2)).thenReturn("inv-1");
      when(rsInvoices.getString(3)).thenReturn("INV-001");
      when(rsInvoices.getString(4)).thenReturn("CO");
      when(rsInvoices.getBigDecimal(5)).thenReturn(new BigDecimal("250.00"));
      when(rsInvoices.getString(6)).thenReturn("EUR");

      // fetchLineCounts: 1 row
      ResultSet rsCounts = mock(ResultSet.class);
      when(psCounts.executeQuery()).thenReturn(rsCounts);
      when(rsCounts.next()).thenReturn(true, false);
      when(rsCounts.getString(1)).thenReturn("rec-1");
      when(rsCounts.getInt(2)).thenReturn(3);

      JSONObject rec = new JSONObject().put("id", "rec-1");
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(rec)));
      NeoContext ctx = NeoContext.builder()
          .specName("return-material-receipt").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      JSONObject enriched = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);

      JSONArray shipments = enriched.getJSONArray("sourceShipments");
      assertEquals(1, shipments.length());
      assertEquals("RC-001", shipments.getJSONObject(0).getString("documentNo"));
      assertEquals("RC-001", enriched.getString("sourceShipmentDocNo"));

      JSONArray invoices = enriched.getJSONArray("returnInvoices");
      assertEquals(1, invoices.length());
      assertEquals("INV-001", invoices.getJSONObject(0).getString("documentNo"));
      assertTrue(enriched.getBoolean("hasReturnInvoice"));
      assertEquals(3, enriched.getInt("linesCount"));
    }
  }

  // ── afterHandle() — issuerOrg enrichment (ETP-5124) ───────────────────────

  /**
   * Detail GET (non-null recordId) for a record whose id matches: afterHandle must inject
   * {@code issuerOrg}, mirroring the pattern already used by the return-to-vendor-shipment
   * sibling handler ({@code ReturnToVendorShipmentHeaderHandler}, ETP-4939) via the shared
   * {@code NeoHandlerUtils#enrichIssuerOrg}. Resolves the shipment via
   * {@code OBDal.getReadOnlyInstance().get(ShipmentInOut.class, "rec-1")}, then its
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
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(receipt.getOrganization()).thenReturn(org);
      when(readOnlyDal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);

      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(readOnlyDal.get(OrganizationInformation.class, "org-1")).thenReturn(orgInfo);
      when(orgInfo.getTaxID()).thenReturn("B12345678");
      when(orgInfo.getLocationAddress()).thenReturn(null);
      Organization infoOrg = mock(Organization.class);
      when(infoOrg.getName()).thenReturn("Acme Corp");
      when(orgInfo.getOrganization()).thenReturn(infoOrg);

      JSONObject rec = new JSONObject().put("id", "rec-1");
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(rec)));
      NeoContext ctx = NeoContext.builder()
          .specName("return-material-receipt").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
          .recordId("rec-1").build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      JSONObject enriched = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue(enriched.has("issuerOrg"));
    }
  }

  // ── handleAvailableShipments / Lines — SQL exception paths ───────────────

  @Test
  public void testHandleAvailableShipmentsSqlExceptionReturnsInternalError() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB down"));

      JSONObject body = new JSONObject().put("businessPartner", "bp-1");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("availableShipments").requestBody(body).build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }

  @Test
  public void testHandleAvailableShipmentLinesSqlExceptionReturnsInternalError() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB down"));

      JSONObject body = new JSONObject().put("shipmentId", "ship-1");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("availableShipmentLines").requestBody(body).build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }

  @Test
  public void testHandleImportShipmentLinesSqlExceptionReturnsInternalError() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB down"));

      JSONObject reqBody = new JSONObject().put("lines",
          new JSONArray().put(new JSONObject().put("sourceLineId", "x").put("returnQuantity", 1)));
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("importShipmentLines").recordId("rec-1").requestBody(reqBody).build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }

  // ── handleImportShipmentLines — import loop path ──────────────────────────

  @Test
  public void testHandleImportShipmentLinesImportsOneLine() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getLong(1)).thenReturn(10L); // fetchMaxLineNo → nextLineNo = 20

      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      when(dal.get(ShipmentInOutLine.class, "src-1")).thenReturn(sourceLine);
      when(sourceLine.getProduct()).thenReturn(mock(Product.class));
      when(sourceLine.getStorageBin()).thenReturn(null);

      ShipmentInOutLine newLine = mock(ShipmentInOutLine.class);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(newLine);

      JSONObject lineReq = new JSONObject().put("sourceLineId", "src-1").put("returnQuantity", 2.0);
      JSONObject reqBody = new JSONObject().put("lines", new JSONArray().put(lineReq));
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("importShipmentLines").recordId("rec-1").requestBody(reqBody).build();
      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(1, result.getBody()
          .getJSONObject("response").getJSONObject("data").getInt("importedCount"));
    }
  }

  // ── fillMissingStorageBins — loop body paths ──────────────────────────────

  @Test
  public void testHandleDocumentActionFillsBinsFromOrigLineStorageBin() {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      ShipmentInOutLine origLine = mock(ShipmentInOutLine.class);
      Locator locator = mock(Locator.class);
      when(line.getCanceledInoutLine()).thenReturn(origLine);
      when(origLine.getStorageBin()).thenReturn(locator);
      when(line.getStorageBin()).thenReturn(null); // different → set it
      when(receipt.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("documentAction").recordId("rec-1").build();
      assertNull(handler.handle(ctx));

      Mockito.verify(line).setStorageBin(locator);
      Mockito.verify(dal).save(line);
    }
  }

  /**
   * Line has neither a {@code canceledInoutLine} nor a {@code storageBin} → the header
   * warehouse's default locator is looked up and assigned.
   *
   * <p>ETP-4863: the lookup moved from {@code ReturnShipmentUtils}' own raw-JDBC query to the
   * criteria-based {@code NeoHandlerUtils.findDefaultLocatorForWarehouse}, so that one definition
   * of "the warehouse's default bin" is shared by the CRUD path and every DAL path. The stubs
   * follow the lookup; the assertion (line gets the header warehouse's default bin) is unchanged.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleDocumentActionFillsBinsFromDefaultLocator() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getCanceledInoutLine()).thenReturn(null);
      when(line.getStorageBin()).thenReturn(null);

      Warehouse warehouse = mock(Warehouse.class);
      when(receipt.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-1");

      Locator defaultLoc = mock(Locator.class);
      when(defaultLoc.getId()).thenReturn("loc-1");
      OBCriteria<Locator> locatorCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(locatorCriteria);
      when(locatorCriteria.add(any())).thenReturn(locatorCriteria);
      when(locatorCriteria.addOrder(any())).thenReturn(locatorCriteria);
      when(locatorCriteria.setMaxResults(1)).thenReturn(locatorCriteria);
      when(locatorCriteria.uniqueResult()).thenReturn(defaultLoc);

      when(receipt.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("documentAction").recordId("rec-1").build();
      assertNull(handler.handle(ctx));

      Mockito.verify(line).setStorageBin(defaultLoc);
      Mockito.verify(dal).save(line);
    }
  }

  // ── handleCreateReturnInvoice — OBException path (bad BP) ─────────────────

  @Test
  public void testHandleCreateReturnInvoiceObExceptionReturnsBadRequest() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(dal.get(ShipmentInOut.class, "rec-1")).thenReturn(receipt);
      when(receipt.getDocumentStatus()).thenReturn("CO");

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getProduct()).thenReturn(mock(Product.class));
      when(line.getCanceledInoutLine()).thenReturn(null); // findSourceInvoice skips
      when(receipt.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      Organization org = mock(Organization.class);
      when(receipt.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");

      // findAriRmDocType — one candidate matching org-1
      DocumentType docType = mock(DocumentType.class);
      Organization docOrg = mock(Organization.class);
      when(docType.getOrganization()).thenReturn(docOrg);
      when(docOrg.getId()).thenReturn("org-1");
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(docType));

      // buildReturnInvoiceHeader — sourceInvoice=null, BP missing payment terms → OBException
      when(provider.get(Invoice.class)).thenReturn(mock(Invoice.class));
      BusinessPartner bp = mock(BusinessPartner.class);
      when(receipt.getBusinessPartner()).thenReturn(bp);
      when(bp.getPriceList()).thenReturn(null);
      when(bp.getPaymentTerms()).thenReturn(null);

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("createReturnInvoice").recordId("rec-1").build();
      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  // ── afterHandle() — outer catch path ──────────────────────────────────────

  @Test
  public void testAfterHandleReturnsNullOnDbException() throws Exception {
    // OBDal.getInstance() returns null → null.getConnection() throws NPE OUTSIDE
    // fetchSourceShipments' inner try/catch → propagates to afterHandle's outer catch → null.
    // (If getInstance returned non-null but getConnection() returned null, the NPE would
    // happen inside the try-with-resources and be caught silently, not reaching outer catch.)
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(null);

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
}
