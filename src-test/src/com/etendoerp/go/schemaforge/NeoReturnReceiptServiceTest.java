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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

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

  /**
   * When hasExistingReturn detects an existing return (Connection.prepareStatement throws so the
   * private method returns false, meaning the code continues — but we verify the 409 branch by
   * making the PreparedStatement succeed and return a count > 0).
   *
   * Strategy: mock the Connection returned by OBDal.getInstance().getConnection() so that
   * executeQuery on the PreparedStatement delivers a ResultSet with count=1.  That causes
   * hasExistingReturn to return true and the method must return 409 Conflict.
   */
  @Test
  public void testCreateReturn_existingReturn_returns409() throws Exception {
    JSONObject body = new JSONObject();
    body.put("lines", new JSONArray().put(
        new JSONObject().put("lineId", "line-1").put("returnQuantity", 2)));

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
      when(source.getId()).thenReturn("ship-1");
      when(dal.get(eq(ShipmentInOut.class), eq("ship-1"))).thenReturn(source);

      // Wire hasExistingReturn: Connection → PreparedStatement → ResultSet with count=1
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getInt(1)).thenReturn(1);

      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      assertEquals("Expected 409 when an existing return already exists",
          HttpServletResponse.SC_CONFLICT, result.getHttpStatus());
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

  // ─────────────────────────────────────────────────────────────────────────────
  // hasExistingReturn — silent exception path
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * When the DB Connection throws on prepareStatement, hasExistingReturn silently returns false
   * and the main flow continues past the 409 check. We verify this by confirming the response is
   * NOT 409 (it becomes 400 because no valid lines can be added without a real DB, which triggers
   * the OBException path).
   *
   * This test exercises the exception-swallowing branch of hasExistingReturn.
   */
  @Test
  public void testCreateReturn_hasExistingReturn_connectionThrows_continuesPastConflictCheck()
      throws Exception {
    JSONObject body = new JSONObject();
    body.put("lines", new JSONArray().put(
        new JSONObject().put("lineId", "line-1").put("returnQuantity", 2)));

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
      when(source.getId()).thenReturn("ship-1");
      when(dal.get(eq(ShipmentInOut.class), eq("ship-1"))).thenReturn(source);

      // Connection.prepareStatement throws → hasExistingReturn catches it and returns false
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenThrow(new SQLException("simulated SQL error"));

      // No DocumentType found → OBException → caught as 400
      when(dal.createCriteria(DocumentType.class)).thenThrow(
          new OBException("no doc type — proves 409 was skipped"));

      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      NeoResponse result = NeoReturnReceiptService.createReturn(ctx, "goods-shipment", true, "C-");

      // Must NOT be 409 — the conflict check was bypassed due to the swallowed exception
      assertEquals("Expected code other than 409 when hasExistingReturn swallows SQL error",
          HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
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

  private static final String WH_PRINCIPAL = "wh-principal";
  private static final String WH_SECONDARY = "wh-secondary";

  private static Warehouse mockWarehouse(String id) {
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getId()).thenReturn(id);
    return warehouse;
  }

  private static Locator mockLocator(String id, Warehouse warehouse) {
    Locator locator = mock(Locator.class);
    when(locator.getId()).thenReturn(id);
    when(locator.getWarehouse()).thenReturn(warehouse);
    return locator;
  }

  /** Stubs the {@code M_Locator} default-lookup criteria used to anchor a bin to a warehouse. */
  @SuppressWarnings("unchecked")
  private static void stubDefaultLocatorLookup(OBDal dal, Locator result) {
    OBCriteria criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Locator.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrder(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(result);
  }

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

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator sourceBin = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));
      Locator headerDefaultBin = mockLocator("loc-principal-default", headerWarehouse);
      stubDefaultLocatorLookup(dal, headerDefaultBin);

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

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator sourceBin = mockLocator("loc-principal-A", headerWarehouse);

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
}
