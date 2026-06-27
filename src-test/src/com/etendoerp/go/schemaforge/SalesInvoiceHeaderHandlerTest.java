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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;

/**
 * Unit tests for {@link SalesInvoiceHeaderHandler}.
 *
 * <p>Covers two responsibilities:
 * <ul>
 *   <li>{@code handle()} — routes ACTION requests to the right downstream handler
 *       (clone record / register payment) or returns null when none matches.</li>
 *   <li>{@code afterHandle()} — adjusts {@code grandTotalAmount} and {@code outstandingAmount}
 *       in GET responses for draft invoices that carry a positive {@code etgoTotalDiscount}.</li>
 * </ul>
 */
public class SalesInvoiceHeaderHandlerTest {

  /**
   * Creates a {@link SalesInvoiceHeaderHandler} with its {@code @Inject} fields replaced by the
   * provided mocks via reflection, bypassing CDI in the unit-test context.
   *
   * @param mockClone
   *     mock for {@link NeoCloneRecordHandler}
   * @param mockPayment
   *     mock for {@link RegisterPaymentHandler}
   * @return handler instance with injected mocks
   * @throws Exception
   *     if reflection access fails
   */
  private static SalesInvoiceHeaderHandler handlerWithMocks(NeoCloneRecordHandler mockClone,
      RegisterPaymentHandler mockPayment) throws Exception {
    SalesInvoiceHeaderHandler handler = new SalesInvoiceHeaderHandler();
    Field cloneField = SalesInvoiceHeaderHandler.class.getDeclaredField("cloneRecordHandler");
    cloneField.setAccessible(true);
    cloneField.set(handler, mockClone);
    Field paymentField = SalesInvoiceHeaderHandler.class.getDeclaredField("registerPaymentHandler");
    paymentField.setAccessible(true);
    paymentField.set(handler, mockPayment);
    return handler;
  }

  /**
   * Builds a GET/CRUD {@link NeoContext} targeting the sales-invoice header entity.
   *
   * @return a fresh context suitable for {@code afterHandle()} tests
   */
  private static NeoContext getCtx() {
    return NeoContext.builder().specName("sales-invoice").entityName("header").httpMethod("GET").endpointType(
        NeoEndpointType.CRUD).build();
  }

  /**
   * Builds a minimal response body wrapping a single invoice record.
   *
   * @param processed
   *     value for the {@code processed} field
   * @param discount
   *     value for the {@code etgoTotalDiscount} field
   * @param grandTotal
   *     value for the {@code grandTotalAmount} field
   * @param outstanding
   *     value for the {@code outstandingAmount} field
   * @return JSON body in the standard {@code response → data[]} envelope
   * @throws JSONException
   *     if JSON construction fails
   */
  private static JSONObject invoiceBody(boolean processed, double discount, double grandTotal,
      double outstanding) throws JSONException {
    JSONObject invoice = new JSONObject().put("processed", processed).put("etgoTotalDiscount", discount).put(
        "grandTotalAmount", grandTotal).put("outstandingAmount", outstanding);
    JSONArray data = new JSONArray().put(invoice);
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  // ── handle() dispatch ──────────────────────────────────────────────────────

  /**
   * Verifies that handle returns the register-payment response when the payment handler matches.
   */
  @Test
  public void testHandleDispatchesToRegisterPaymentHandler() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    RegisterPaymentHandler mockPayment = mock(RegisterPaymentHandler.class);
    SalesInvoiceHeaderHandler handler = handlerWithMocks(mockClone, mockPayment);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "registerPayment"));
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName(
        "registerPayment").build();
    when(mockPayment.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
  }

  /**
   * Verifies that handle returns null when no downstream handler matches the context.
   */
  @Test
  public void testHandleReturnsNullWhenNoHandlerMatches() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    RegisterPaymentHandler mockPayment = mock(RegisterPaymentHandler.class);
    SalesInvoiceHeaderHandler handler = handlerWithMocks(mockClone, mockPayment);

    NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    when(mockClone.handle(ctx)).thenReturn(null);
    when(mockPayment.handle(ctx)).thenReturn(null);

    assertNull(handler.handle(ctx));
  }

  // ── afterHandle() guard conditions ────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for non-GET requests without modifying anything.
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null for GET requests with a non-CRUD endpoint type.
   */
  @Test
  public void testAfterHandleReturnsNullForNonCrudEndpoint() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.SELECTOR).build();
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenPreviousResultIsNull() {
    NeoContext ctx = getCtx();
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  // ── afterHandle() skip conditions ─────────────────────────────────────────

  /**
   * Verifies that confirmed invoices (processed=true) are not adjusted, because
   * TotalDiscountService already created negative lines in the DB at completion time.
   */
  @Test
  public void testAfterHandleSkipsConfirmedInvoice() throws Exception {
    JSONObject body = invoiceBody(true, 10.0, 470.63, 470.63);
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0).getDouble(
        "grandTotalAmount");
    assertEquals(470.63, grand, 0.001);
  }

  /**
   * Verifies that a draft invoice with no total discount (etgoTotalDiscount=0) is not modified.
   */
  @Test
  public void testAfterHandleSkipsDraftInvoiceWithNoDiscount() throws Exception {
    JSONObject body = invoiceBody(false, 0.0, 470.63, 470.63);
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0).getDouble(
        "grandTotalAmount");
    assertEquals(470.63, grand, 0.001);
  }

  // ── afterHandle() adjustment ───────────────────────────────────────────────

  /**
   * Verifies that a draft invoice with etgoTotalDiscount=5 and grandTotalAmount=470.63
   * is adjusted to 447.10 (470.63 × 0.95, rounded to 2 decimals).
   */
  @Test
  public void testAfterHandleAdjustsGrandTotalForDraftWithDiscount() throws Exception {
    JSONObject body = invoiceBody(false, 5.0, 470.63, 0.0);
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
    double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0).getDouble(
        "grandTotalAmount");
    assertEquals(447.10, grand, 0.005);
  }

  /**
   * Verifies that a draft invoice with etgoTotalDiscount=5 and outstandingAmount=470.63
   * is adjusted to 447.10 alongside grandTotalAmount.
   */
  @Test
  public void testAfterHandleAdjustsOutstandingAmountForDraftWithDiscount() throws Exception {
    JSONObject body = invoiceBody(false, 5.0, 0.0, 470.63);
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    double outstanding = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0).getDouble(
        "outstandingAmount");
    assertEquals(447.10, outstanding, 0.005);
  }

  /**
   * Verifies that all records in a list response are adjusted when each carries a positive discount.
   */
  @Test
  public void testAfterHandleAdjustsAllRecordsInListResponse() throws Exception {
    JSONArray data = new JSONArray().put(
        new JSONObject().put("processed", false).put("etgoTotalDiscount", 10.0).put("grandTotalAmount", 100.0).put(
            "outstandingAmount", 100.0)).put(
        new JSONObject().put("processed", false).put("etgoTotalDiscount", 20.0).put("grandTotalAmount", 200.0).put(
            "outstandingAmount", 200.0));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    JSONArray resultData = result.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(90.0, resultData.getJSONObject(0).getDouble("grandTotalAmount"), 0.005);
    assertEquals(160.0, resultData.getJSONObject(1).getDouble("grandTotalAmount"), 0.005);
  }

  /**
   * Verifies that in a mixed list only the draft record with a positive discount is adjusted;
   * confirmed and zero-discount records remain untouched.
   */
  @Test
  public void testAfterHandleAdjustsOnlyEligibleRecordInMixedList() throws Exception {
    JSONArray data = new JSONArray().put(
            new JSONObject().put("processed", true).put("etgoTotalDiscount", 10.0).put("grandTotalAmount", 500.0).put(
                "outstandingAmount", 500.0))   // confirmed — skip
        .put(new JSONObject().put("processed", false).put("etgoTotalDiscount", 0.0).put("grandTotalAmount", 300.0).put(
            "outstandingAmount", 300.0))   // no discount — skip
        .put(new JSONObject().put("processed", false).put("etgoTotalDiscount", 5.0).put("grandTotalAmount", 470.63).put(
            "outstandingAmount", 470.63)); // adjust
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    JSONArray resultData = result.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(500.0, resultData.getJSONObject(0).getDouble("grandTotalAmount"), 0.005); // unchanged
    assertEquals(300.0, resultData.getJSONObject(1).getDouble("grandTotalAmount"), 0.005); // unchanged
    assertEquals(447.10, resultData.getJSONObject(2).getDouble("grandTotalAmount"), 0.005); // adjusted
  }

  // ── enrichSourceInvoice() ──────────────────────────────────────────────────

  /**
   * Builds a context for detail GET (recordId != null).
   */
  private static NeoContext getDetailCtx(String recordId) {
    return NeoContext.builder()
        .specName("sales-invoice")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  /**
   * Builds a minimal invoice response body.
   */
  private static JSONObject invoiceBodyNoDiscount(String id) throws JSONException {
    JSONObject invoice = new JSONObject()
        .put("id", id)
        .put("processed", false)
        .put("etgoTotalDiscount", 0.0)
        .put("grandTotalAmount", 100.0)
        .put("outstandingAmount", 100.0);
    return new JSONObject().put("response", new JSONObject().put("data", new JSONArray().put(invoice)));
  }

  /**
   * Verifies that enrichSourceInvoice injects both sourceReturnReceipt and sourceInvoice
   * when the SQL query finds a return receipt linked to an original invoice.
   */
  @Test
  public void testEnrichSourceInvoiceInjectsBothFieldsWhenReturnReceiptAndInvoiceFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString("ret_id")).thenReturn("ret-001");
      when(rs.getString("ret_doc")).thenReturn("RRET-001");
      when(rs.getString("ret_status")).thenReturn("CO");
      when(rs.getString("inv_id")).thenReturn("inv-orig-001");
      when(rs.getString("inv_doc")).thenReturn("INV-ORIG-001");

      JSONObject body = invoiceBodyNoDiscount("inv-001");
      NeoContext ctx = getDetailCtx("inv-001");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);

      JSONObject retReceipt = rec.getJSONObject("sourceReturnReceipt");
      assertEquals("ret-001", retReceipt.getString("id"));
      assertEquals("RRET-001", retReceipt.getString("documentNo"));
      assertEquals("CO", retReceipt.getString("documentStatus"));

      JSONObject sourceInvoice = rec.getJSONObject("sourceInvoice");
      assertEquals("inv-orig-001", sourceInvoice.getString("id"));
      assertEquals("INV-ORIG-001", sourceInvoice.getString("documentNo"));
    }
  }

  /**
   * Verifies that enrichSourceInvoice injects only sourceReturnReceipt (no sourceInvoice key)
   * when the SQL row has a return receipt but no original invoice (inv_id = null).
   */
  @Test
  public void testEnrichSourceInvoiceInjectsOnlyReturnReceiptWhenNoOriginalInvoice() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString("ret_id")).thenReturn("ret-002");
      when(rs.getString("ret_doc")).thenReturn("RRET-002");
      when(rs.getString("ret_status")).thenReturn("DR");
      when(rs.getString("inv_id")).thenReturn(null); // no original invoice

      JSONObject body = invoiceBodyNoDiscount("inv-002");
      NeoContext ctx = getDetailCtx("inv-002");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);

      assertNotNull(rec.optJSONObject("sourceReturnReceipt"));
      assertFalse(rec.has("sourceInvoice"));
    }
  }

  /**
   * Verifies that neither sourceReturnReceipt nor sourceInvoice is injected
   * when the SQL returns no rows (regular invoice, no Canceled_Inoutline_ID).
   */
  @Test
  public void testEnrichSourceInvoiceInjectsNothingWhenNoRowsFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      JSONObject body = invoiceBodyNoDiscount("inv-003");
      NeoContext ctx = getDetailCtx("inv-003");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);

      assertFalse(rec.has("sourceReturnReceipt"));
      assertFalse(rec.has("sourceInvoice"));
    }
  }

  /**
   * Verifies that enrichSourceInvoice is NOT called for list GET (recordId == null),
   * so neither sourceReturnReceipt nor sourceInvoice appears in list results.
   */
  @Test
  public void testEnrichSourceInvoiceNotCalledForListView() throws Exception {
    JSONObject body = invoiceBody(false, 0.0, 100.0, 100.0);
    NeoContext ctx = getCtx(); // recordId = null — list view
    ctx.setPreviousResult(NeoResponse.ok(body));

    // No OBDal mock needed — enrichSourceInvoice should not be reached
    NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    JSONObject rec = result.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);

    assertFalse(rec.has("sourceReturnReceipt"));
    assertFalse(rec.has("sourceInvoice"));
  }

  // ── classifyDocType (via resolveSubtype) ──────────────────────────────────

  /**
   * Test accessor subclass that exposes resolveSubtype for direct testing.
   */
  private static class TestableSalesHandler extends SalesInvoiceHeaderHandler {
    public String callResolveSubtype(String docTypeId) {
      return resolveSubtype(docTypeId);
    }
  }

  @Test
  public void resolveSubtype_blankDocTypeId_returnsFac() {
    TestableSalesHandler h = new TestableSalesHandler();
    assertEquals("FAC", h.callResolveSubtype(null));
    assertEquals("FAC", h.callResolveSubtype(""));
    assertEquals("FAC", h.callResolveSubtype("   "));
  }

  @Test
  public void resolveSubtype_docTypeNotFound_returnsFac() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(DocumentType.class, "dt-missing")).thenReturn(null);

      TestableSalesHandler h = new TestableSalesHandler();
      assertEquals("FAC", h.callResolveSubtype("dt-missing"));
    }
  }

  @Test
  public void resolveSubtype_arcCategory_returnsNc() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARC");
      when(dal.get(DocumentType.class, "dt-arc")).thenReturn(dt);

      TestableSalesHandler h = new TestableSalesHandler();
      assertEquals("NC", h.callResolveSubtype("dt-arc"));
    }
  }

  @Test
  public void resolveSubtype_ariRmCategory_returnsDev() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARI_RM");
      when(dal.get(DocumentType.class, "dt-ari-rm")).thenReturn(dt);

      TestableSalesHandler h = new TestableSalesHandler();
      assertEquals("DEV", h.callResolveSubtype("dt-ari-rm"));
    }
  }

  @Test
  public void resolveSubtype_ariCategory_returnsFac() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARI");
      when(dal.get(DocumentType.class, "dt-ari")).thenReturn(dt);

      TestableSalesHandler h = new TestableSalesHandler();
      assertEquals("FAC", h.callResolveSubtype("dt-ari"));
    }
  }

  @Test
  public void resolveSubtype_otherCategory_returnsFac() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("MMO");
      when(dal.get(DocumentType.class, "dt-other")).thenReturn(dt);

      TestableSalesHandler h = new TestableSalesHandler();
      assertEquals("FAC", h.callResolveSubtype("dt-other"));
    }
  }

  @Test
  public void resolveSubtype_dbException_returnsFac() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(DocumentType.class, "dt-err"))
          .thenThrow(new RuntimeException("DB error"));

      TestableSalesHandler h = new TestableSalesHandler();
      assertEquals("FAC", h.callResolveSubtype("dt-err"));
    }
  }

  // ── afterHandle(): amount negation for NC / DEV subtypes ─────────────────

  /**
   * Builds a body for NC/DEV subtype tests with a specific transactionDocument field.
   */
  private static JSONObject invoiceBodyWithDocType(String docTypeId, double grand, double outstanding)
      throws Exception {
    JSONObject invoice = new JSONObject()
        .put("transactionDocument", docTypeId)
        .put("processed", false)
        .put("etgoTotalDiscount", 0.0)
        .put("grandTotalAmount", grand)
        .put("outstandingAmount", outstanding);
    return new JSONObject().put("response", new JSONObject().put("data", new JSONArray().put(invoice)));
  }

  /**
   * Verifies that grandTotalAmount and outstandingAmount are negated for NC (credit memo)
   * subtype records when amounts are positive.
   */
  @Test
  public void afterHandle_ncSubtype_negatesPositiveAmounts() throws Exception {
    JSONObject body = invoiceBodyWithDocType("dt-arc", 150.0, 100.0);
    NeoContext ctx = getCtx(); // list mode — no recordId, no enrichSourceInvoice call
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARC");
      when(dal.get(DocumentType.class, "dt-arc")).thenReturn(dt);

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("NC", rec.getString("arInvoiceSubtype"));
      assertEquals(-150.0, rec.getDouble("grandTotalAmount"), 0.001);
      assertEquals(-100.0, rec.getDouble("outstandingAmount"), 0.001);
    }
  }

  /**
   * Verifies that grandTotalAmount and outstandingAmount are negated for DEV (return invoice)
   * subtype records when amounts are positive.
   */
  @Test
  public void afterHandle_devSubtype_negatesPositiveAmounts() throws Exception {
    JSONObject body = invoiceBodyWithDocType("dt-ari-rm", 200.0, 200.0);
    NeoContext ctx = getCtx(); // list mode
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARI_RM");
      when(dal.get(DocumentType.class, "dt-ari-rm")).thenReturn(dt);

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("DEV", rec.getString("arInvoiceSubtype"));
      assertEquals(-200.0, rec.getDouble("grandTotalAmount"), 0.001);
      assertEquals(-200.0, rec.getDouble("outstandingAmount"), 0.001);
    }
  }

  /**
   * Verifies that non-positive amounts are NOT negated — the guard {@code grand > 0} must hold.
   */
  @Test
  public void afterHandle_ncSubtype_doesNotNegateZeroOrNegativeAmounts() throws Exception {
    JSONObject body = invoiceBodyWithDocType("dt-arc", 0.0, -50.0);
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARC");
      when(dal.get(DocumentType.class, "dt-arc")).thenReturn(dt);

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(0.0, rec.getDouble("grandTotalAmount"), 0.001);   // unchanged
      assertEquals(-50.0, rec.getDouble("outstandingAmount"), 0.001); // already negative, unchanged
    }
  }

  // ── afterHandle(): enrichDocTypeLocked in detail view ───────────────────

  /**
   * Verifies that {@code docTypeLocked = true} is injected for detail-view GET responses
   * (i.e., when context carries a recordId).
   */
  @Test
  public void afterHandle_detailView_enrichesDocTypeLocked() throws Exception {
    JSONObject body = invoiceBodyNoDiscount("inv-lock");
    NeoContext ctx = getDetailCtx("inv-lock");
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false); // no return receipt found

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue("docTypeLocked must be true in detail view", rec.getBoolean("docTypeLocked"));
    }
  }

  /**
   * Verifies that a SQL exception in enrichSourceInvoice is caught silently —
   * afterHandle still returns a valid response without rethrowing.
   */
  @Test
  public void testEnrichSourceInvoiceSqlExceptionCaughtSilently() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenThrow(new SQLException("connection closed"));

      JSONObject body = invoiceBodyNoDiscount("inv-004");
      NeoContext ctx = getDetailCtx("inv-004");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
    }
  }
}
