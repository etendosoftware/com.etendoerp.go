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
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Unit tests for {@link NeoReturnReceiptService} and {@link CreateReturnReceiptHandler}.
 *
 * <p>All tests run without a real DB. Static collaborators (OBDal, OBContext, Utility) are
 * replaced with Mockito mocks via try-with-resources {@code MockedStatic} blocks, following
 * the same pattern used throughout this test suite.
 *
 * @covers com.etendoerp.go.schemaforge.NeoReturnReceiptService
 * @covers com.etendoerp.go.schemaforge.CreateReturnReceiptHandler
 * @covers com.etendoerp.go.schemaforge.ReturnableQuantityGuard
 */
public class NeoReturnReceiptServiceTest {

  // ─────────────────────────────────────────────────────────────────────────────
  // createReturn — guard clauses that return null
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * When the endpoint type is not ACTION the method must pass through to the next handler
   * (return null) without touching OBDal or OBContext.
   */
  @Test
  public void testCreateReturn_notActionEndpoint_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("ship-1")
        .build();

    NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

    assertNull("Expected null when endpoint type is not ACTION", result);
  }

  /**
   * When fieldName is not "createReturn" the method must return null.
   */
  @Test
  public void testCreateReturn_wrongFieldName_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("someOtherAction")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("ship-1")
        .build();

    NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

    assertNull("Expected null when fieldName is not 'createReturn'", result);
  }

  /**
   * When the HTTP method is not POST the method must return null.
   */
  @Test
  public void testCreateReturn_wrongHttpMethod_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("GET")
        .specName("goods-shipment")
        .recordId("ship-1")
        .build();

    NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

    assertNull("Expected null when HTTP method is not POST", result);
  }

  /**
   * When specName does not match the expected spec the method must return null,
   * allowing the call to be handled by a different handler.
   */
  @Test
  public void testCreateReturn_specNameMismatch_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("purchase-order")
        .recordId("ship-1")
        .build();

    NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

    assertNull("Expected null when specName does not match expectedSpecName", result);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // createReturn — validation errors (400 / 404)
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * A blank recordId must produce a 400 Bad Request before any DB access.
   */
  @Test
  public void testCreateReturn_blankRecordId_returns400() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("   ")
        .build();

    NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

    assertEquals("Expected 400 for blank recordId",
        HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  /**
   * When OBDal.get returns null for the shipment the method must produce a 404 Not Found.
   */
  @Test
  public void testCreateReturn_shipmentNotFound_returns404() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("ship-missing")
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), eq("ship-missing"))).thenReturn(null);

      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      assertEquals("Expected 404 when shipment is not found",
          HttpServletResponse.SC_NOT_FOUND, result.getHttpStatus());
    }
  }

  /**
   * A null or empty "lines" array in the request body must produce a 400 Bad Request.
   */
  @Test
  public void testCreateReturn_nullRequestedLines_returns400() throws Exception {
    JSONObject body = new JSONObject();
    // "lines" key is intentionally absent — optJSONArray returns null

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("ship-1")
        .requestBody(body)
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOut source = mock(ShipmentInOut.class);
      when(dal.get(eq(ShipmentInOut.class), eq("ship-1"))).thenReturn(source);

      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      assertEquals("Expected 400 when lines array is absent",
          HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  /**
   * An empty "lines" array (length 0) must also produce a 400 Bad Request.
   */
  @Test
  public void testCreateReturn_emptyLinesArray_returns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("lines", new JSONArray());

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("ship-1")
        .requestBody(body)
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOut source = mock(ShipmentInOut.class);
      when(dal.get(eq(ShipmentInOut.class), eq("ship-1"))).thenReturn(source);

      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      assertEquals("Expected 400 when lines array is empty",
          HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // createReturn — returnable quantity (ETP-5729 CP-12)
  // ─────────────────────────────────────────────────────────────────────────────

  private static final String DOC_TYPE_SENTINEL = "no doc type — proves the quantity check passed";

  private static NeoContext createReturnContext(String lineId, int qty) throws Exception {
    JSONObject body = new JSONObject();
    body.put("lines", new JSONArray().put(
        new JSONObject().put("lineId", lineId).put("returnQuantity", qty)));
    return NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("ship-1")
        .requestBody(body)
        .build();
  }

  /**
   * Stubs the returnable-quantity row of {@code line-1}: shipped 5, {@code alreadyReturned} taken
   * by other non-voided returns. Every mock is created before it is stubbed (no nested stubbing).
   */
  private static Connection stubReturnableRow(OBDal dal, ShipmentInOut source,
      String alreadyReturned) throws Exception {
    ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
    when(sourceLine.getId()).thenReturn("line-1");
    when(source.getMaterialMgmtShipmentInOutLineList()).thenReturn(List.of(sourceLine));
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("line-1");
    when(rs.getString(2)).thenReturn("Product A");
    when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("5"));
    when(rs.getBigDecimal(4)).thenReturn(new BigDecimal(alreadyReturned));
    return conn;
  }

  private static void stubAdminMode(MockedStatic<OBContext> obContextMock) {
    obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(inv -> null);
    obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
  }

  /**
   * A second return of the same shipment is allowed while quantity remains: 2 of 5 already taken
   * by an earlier return (draft or completed — the query does not tell them apart, only voided
   * returns are left out), 3 requested. There is no "a return already exists" refusal any more:
   * the flow carries on to the document type lookup, stubbed here to stop with a sentinel.
   */
  @Test
  public void testCreateReturn_secondReturnWithinRemainingQty_isAllowed() throws Exception {
    NeoContext ctx = createReturnContext("line-1", 3);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      ShipmentInOut source = mock(ShipmentInOut.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), eq("ship-1"))).thenReturn(source);
      Connection conn = stubReturnableRow(dal, source, "2");
      when(dal.createCriteria(DocumentType.class)).thenThrow(new OBException(DOC_TYPE_SENTINEL));
      stubAdminMode(obContextMock);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertEquals(DOC_TYPE_SENTINEL, result.getBody().getJSONObject("error").getString("message"));
      verify(conn).prepareStatement(Mockito.argThat(sql -> sql.contains("r.docstatus <> 'VO'")
          && !sql.contains("'DR'")));
    }
  }

  /**
   * When earlier returns — a draft one included — already cover the whole shipped quantity, a
   * new return is refused with a structured 409 that names the product and the numbers, and
   * nothing is persisted (no empty return header is left behind).
   */
  @Test
  public void testCreateReturn_earlierReturnsCoverEverything_returns409AndPersistsNothing()
      throws Exception {
    NeoContext ctx = createReturnContext("line-1", 1);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      ShipmentInOut source = mock(ShipmentInOut.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), eq("ship-1"))).thenReturn(source);
      stubReturnableRow(dal, source, "5");
      stubAdminMode(obContextMock);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      assertEquals(HttpServletResponse.SC_CONFLICT, result.getHttpStatus());
      JSONObject error = result.getBody().getJSONObject("error");
      assertEquals("RETURN_QTY_EXCEEDS_SHIPPED", error.getString("code"));
      assertEquals("ETGO_ReturnQtyExceedsShipped", error.getJSONArray("messageKeys").getString(0));
      JSONObject params = error.getJSONObject("messageParams");
      assertEquals("Product A", params.getString("product"));
      assertEquals("1", params.getString("requested"));
      assertEquals("0", params.getString("available"));
      assertEquals("5", params.getString("shipped"));
      verify(dal, never()).save(any());
      verify(dal, never()).createCriteria(DocumentType.class);
    }
  }

  /**
   * The returnable-quantity check fails CLOSED: when it cannot be read the request answers 500
   * and nothing is created, instead of letting an unchecked return through.
   */
  @Test
  public void testCreateReturn_returnableQtyQueryFails_returns500AndPersistsNothing()
      throws Exception {
    NeoContext ctx = createReturnContext("line-1", 1);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      ShipmentInOut source = mock(ShipmentInOut.class);
      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      Connection conn = mock(Connection.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), eq("ship-1"))).thenReturn(source);
      when(sourceLine.getId()).thenReturn("line-1");
      when(source.getMaterialMgmtShipmentInOutLineList()).thenReturn(List.of(sourceLine));
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenThrow(new SQLException("simulated SQL error"));
      stubAdminMode(obContextMock);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      verify(dal, never()).save(any());
    }
  }

  /**
   * Requested lines that do not belong to the shipment are a 400 before anything is queried or
   * persisted — never an empty return document.
   */
  @Test
  public void testCreateReturn_noRequestedLineBelongsToShipment_returns400WithoutPersisting()
      throws Exception {
    NeoContext ctx = createReturnContext("foreign-line", 1);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      ShipmentInOut source = mock(ShipmentInOut.class);
      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), eq("ship-1"))).thenReturn(source);
      when(sourceLine.getId()).thenReturn("line-1");
      when(source.getMaterialMgmtShipmentInOutLineList()).thenReturn(List.of(sourceLine));
      stubAdminMode(obContextMock);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertEquals(NeoReturnReceiptService.NO_VALID_LINES_MESSAGE,
          result.getBody().getJSONObject("error").getString("message"));
      verify(dal, never()).getConnection();
      verify(dal, never()).save(any());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // createReturn — exception handling
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * An OBException thrown inside the try block must be caught and returned as 400.
   *
   * We trigger it by making OBDal.get throw OBException directly, which propagates
   * out of the inner try into the OBException catch block.
   */
  @Test
  public void testCreateReturn_obExceptionThrown_returns400() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("ship-1")
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), eq("ship-1")))
          .thenThrow(new OBException("simulated OBException"));

      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      assertEquals("Expected 400 when OBException is thrown",
          HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  /**
   * A generic (non-OBException) RuntimeException thrown inside the try block must be
   * caught and returned as 500 Internal Server Error.
   */
  @Test
  public void testCreateReturn_genericExceptionThrown_returns500() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("ship-1")
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), eq("ship-1")))
          .thenThrow(new RuntimeException("simulated unexpected error"));

      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      assertEquals("Expected 500 when a generic Exception is thrown",
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // ensureDocumentNo
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * When the receipt already has a non-blank documentNo that does not start with "<",
   * ensureDocumentNo must return immediately without calling Utility or saving.
   */
  @Test
  public void testEnsureDocumentNo_validDocNoAlreadySet_returnsEarly() {
    ShipmentInOut receipt = mock(ShipmentInOut.class);
    when(receipt.getDocumentNo()).thenReturn("DOC-0001");

    // No static mocks needed — verify no OBDal interaction
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = Mockito.mockStatic(Utility.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoReturnReceiptService.ensureDocumentNo(receipt);

      utilityMock.verify(
          () -> Utility.getDocumentNoConnection(any(), any(), any(), any(), anyBoolean()),
          never());
      verify(dal, never()).save(any());
    }
  }

  /**
   * When the receipt's documentNo is blank, Utility.getDocumentNoConnection is called.
   * If it returns blank, a warning is logged and documentNo is NOT updated.
   */
  @Test
  public void testEnsureDocumentNo_blankCurrentDocNo_utilityReturnsBlank_noUpdate() {
    ShipmentInOut receipt = mock(ShipmentInOut.class);
    when(receipt.getDocumentNo()).thenReturn("");

    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    when(receipt.getClient()).thenReturn(client);
    when(receipt.getId()).thenReturn("ret-1");
    when(receipt.getDocumentType()).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = Mockito.mockStatic(Utility.class)) {

      OBDal dal = mock(OBDal.class);
      Connection conn = mock(Connection.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection(false)).thenReturn(conn);

      // Utility returns blank → warning logged, no setDocumentNo
      utilityMock.when(() -> Utility.getDocumentNoConnection(
          eq(conn), any(DalConnectionProvider.class),
          eq("client-1"), eq("M_InOut"), eq(true)))
          .thenReturn("");

      NeoReturnReceiptService.ensureDocumentNo(receipt);

      verify(receipt, never()).setDocumentNo(anyString());
      verify(dal, never()).save(any());
    }
  }

  /**
   * When Utility.getDocumentNoConnection returns a valid (non-blank) docNo,
   * the receipt's documentNo is updated and the record is saved.
   */
  @Test
  public void testEnsureDocumentNo_utilityReturnsValidDocNo_setsAndSaves() {
    ShipmentInOut receipt = mock(ShipmentInOut.class);
    when(receipt.getDocumentNo()).thenReturn("");

    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    when(receipt.getClient()).thenReturn(client);
    when(receipt.getId()).thenReturn("ret-1");
    when(receipt.getDocumentType()).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = Mockito.mockStatic(Utility.class)) {

      OBDal dal = mock(OBDal.class);
      Connection conn = mock(Connection.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection(false)).thenReturn(conn);

      utilityMock.when(() -> Utility.getDocumentNoConnection(
          eq(conn), any(DalConnectionProvider.class),
          eq("client-1"), eq("M_InOut"), eq(true)))
          .thenReturn("RET-00042");

      NeoReturnReceiptService.ensureDocumentNo(receipt);

      verify(receipt).setDocumentNo("RET-00042");
      verify(dal).save(receipt);
    }
  }

  /**
   * A documentNo that starts with "<" is treated as a placeholder and triggers
   * the Utility call just like a blank value.
   */
  @Test
  public void testEnsureDocumentNo_placeholderDocNo_triggersUtility() {
    ShipmentInOut receipt = mock(ShipmentInOut.class);
    when(receipt.getDocumentNo()).thenReturn("<123456>");

    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-2");
    when(receipt.getClient()).thenReturn(client);
    when(receipt.getId()).thenReturn("ret-2");
    when(receipt.getDocumentType()).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = Mockito.mockStatic(Utility.class)) {

      OBDal dal = mock(OBDal.class);
      Connection conn = mock(Connection.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection(false)).thenReturn(conn);

      utilityMock.when(() -> Utility.getDocumentNoConnection(
          eq(conn), any(DalConnectionProvider.class),
          eq("client-2"), eq("M_InOut"), eq(true)))
          .thenReturn("RET-00099");

      NeoReturnReceiptService.ensureDocumentNo(receipt);

      verify(receipt).setDocumentNo("RET-00099");
      verify(dal).save(receipt);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CreateReturnReceiptHandler
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * handle() must return null when the context is not an ACTION endpoint because
   * NeoReturnReceiptService.createReturn short-circuits with null for non-ACTION calls.
   * This indirectly verifies that handle() delegates to createReturn with the correct
   * "goods-shipment" spec and does not add any logic of its own.
   */
  @Test
  public void testCreateReturnReceiptHandler_handle_nonActionContext_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("ship-1")
        .build();

    CreateReturnReceiptHandler handler = new CreateReturnReceiptHandler();
    NeoResponse result = handler.handle(ctx);

    assertNull("handle() must return null for non-ACTION contexts", result);
  }

  /**
   * afterHandle() must always return null — it has no post-processing logic.
   */
  @Test
  public void testCreateReturnReceiptHandler_afterHandle_alwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("goods-shipment")
        .recordId("ship-1")
        .build();

    CreateReturnReceiptHandler handler = new CreateReturnReceiptHandler();
    NeoResponse result = handler.afterHandle(ctx);

    assertNull("afterHandle() must always return null", result);
  }

  /**
   * handle() passes the correct fixed arguments to createReturn. We verify this by
   * checking that a context with the wrong specName ("purchase-order") causes handle()
   * to return null — confirming the delegate is called with "goods-shipment" as the
   * expected spec.
   */
  @Test
  public void testCreateReturnReceiptHandler_handle_wrongSpec_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("createReturn")
        .httpMethod("POST")
        .specName("purchase-order")
        .recordId("ship-1")
        .build();

    CreateReturnReceiptHandler handler = new CreateReturnReceiptHandler();
    NeoResponse result = handler.handle(ctx);

    assertNull("handle() must return null when specName does not match 'goods-shipment'", result);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // ETP-4863 — createReturnLineShell must anchor the bin to the header warehouse
  //
  // This shell is the DAL "import lines from a source document" path shared by
  // NeoReturnReceiptService.createReturn and CreatePurchaseReturnHandler.addReturnLine.
  // Neither goes through the line NeoHandler, so NeoHandlerUtils.injectDefaultLocatorIfMissing
  // never runs and the source document's bin used to be copied verbatim — landing the stock
  // transaction in the SOURCE document's warehouse instead of the return header's.
  // ─────────────────────────────────────────────────────────────────────────────

  private static final String WH_PRINCIPAL = LocatorTestSupport.WH_PRINCIPAL;
  private static final String WH_SECONDARY = LocatorTestSupport.WH_SECONDARY;

  /**
   * Source line's bin lives in another warehouse than the return header → the shell must carry
   * the header warehouse's default bin instead of the source's.
   */
  @Test
  public void testCreateReturnLineShell_sourceBinFromAnotherWarehouse_anchorsToHeaderWarehouse() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator sourceBin = LocatorTestSupport.mockLocator("loc-secondary-A", LocatorTestSupport.mockWarehouse(WH_SECONDARY));
      Locator headerDefaultBin = LocatorTestSupport.mockLocator("loc-principal-default", headerWarehouse);
      LocatorTestSupport.stubDefaultLocatorLookup(dal, headerDefaultBin);

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(returnDoc.getWarehouse()).thenReturn(headerWarehouse);
      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      when(sourceLine.getStorageBin()).thenReturn(sourceBin);

      ShipmentInOutLine shell = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(shell);

      NeoReturnReceiptService.createReturnLineShell(returnDoc, sourceLine, 10L);

      verify(shell, never()).setStorageBin(sourceBin);
      verify(shell).setStorageBin(headerDefaultBin);
    }
  }

  /**
   * Source line's bin already belongs to the return header's warehouse → kept verbatim, and no
   * default-locator lookup is issued at all.
   */
  @Test
  public void testCreateReturnLineShell_sourceBinInHeaderWarehouse_isKept() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator sourceBin = LocatorTestSupport.mockLocator("loc-principal-A", headerWarehouse);

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(returnDoc.getWarehouse()).thenReturn(headerWarehouse);
      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      when(sourceLine.getStorageBin()).thenReturn(sourceBin);

      ShipmentInOutLine shell = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(shell);

      NeoReturnReceiptService.createReturnLineShell(returnDoc, sourceLine, 10L);

      verify(shell).setStorageBin(sourceBin);
      verify(dal, never()).createCriteria(Locator.class);
    }
  }

  /**
   * Regression guard, not a bug demonstrator: a source line with NO bin, whose header warehouse
   * DOES have an active locator, already got that locator as its default before ETP-4863 too —
   * {@code anchorLocatorToWarehouse} treats "absent" and "belongs elsewhere" identically, so the
   * intermediate {@code if (anchoredBin != null)}-guarded version already resolved and wrote this
   * same anchor bin. This test passes unchanged on the pre-widening code; it guards that this
   * preexisting-and-correct default-fill keeps working, it does not exercise what ETP-4863
   * changed (see {@code testCreateReturnLineShell_headerWarehouseHasNoLocator_doesNotKeepForeignBin}
   * for the test that actually demonstrates the widening).
   */
  @Test
  public void testCreateReturnLineShell_sourceLineWithoutBin_getsHeaderWarehouseDefault() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator headerDefaultBin = LocatorTestSupport.mockLocator(
          "loc-principal-default", headerWarehouse);
      LocatorTestSupport.stubDefaultLocatorLookup(dal, headerDefaultBin);

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(returnDoc.getWarehouse()).thenReturn(headerWarehouse);
      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      when(sourceLine.getStorageBin()).thenReturn(null);

      ShipmentInOutLine shell = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(shell);

      NeoReturnReceiptService.createReturnLineShell(returnDoc, sourceLine, 10L);

      verify(shell).setStorageBin(headerDefaultBin);
    }
  }

  /**
   * Cascade step 4 at this call site: the return header's warehouse has no active locator, so the
   * source document's foreign bin must NOT be carried over — the shell is left with a null bin
   * and the document fails loudly at posting. Uniform with {@code assignBinsToLines} and
   * {@code createAndLinkLine}.
   */
  @Test
  public void testCreateReturnLineShell_headerWarehouseHasNoLocator_doesNotKeepForeignBin() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator sourceBin = LocatorTestSupport.mockLocator("loc-secondary-A",
          LocatorTestSupport.mockWarehouse(WH_SECONDARY));
      LocatorTestSupport.stubLocatorCascade(dal, null);

      ShipmentInOut returnDoc = mock(ShipmentInOut.class);
      when(returnDoc.getWarehouse()).thenReturn(headerWarehouse);
      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      when(sourceLine.getStorageBin()).thenReturn(sourceBin);

      ShipmentInOutLine shell = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(shell);

      NeoReturnReceiptService.createReturnLineShell(returnDoc, sourceLine, 10L);

      verify(shell, never()).setStorageBin(sourceBin);
      verify(shell).setStorageBin(null);
    }
  }
}
