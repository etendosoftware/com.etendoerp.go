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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.ui.Process;
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
   * Creates a {@link SalesInvoiceHeaderHandler} with its {@code totalDiscountService} field
   * replaced by the provided mock via reflection, bypassing CDI in the unit-test context.
   *
   * @param mockTotalDiscountService
   *     mock for {@link TotalDiscountService}
   * @return handler instance with the mock injected
   * @throws Exception
   *     if reflection access fails
   */
  private static SalesInvoiceHeaderHandler handlerWithTotalDiscountMock(
      TotalDiscountService mockTotalDiscountService) throws Exception {
    SalesInvoiceHeaderHandler handler = new SalesInvoiceHeaderHandler();
    Field discountField = SalesInvoiceHeaderHandler.class.getDeclaredField("totalDiscountService");
    discountField.setAccessible(true);
    discountField.set(handler, mockTotalDiscountService);
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

  /**
   * Verifies that a draft invoice whose discount is already materialized as a real line (e.g.
   * created from an order that already carried the discount, via InvoiceFromOrderSupport) is NOT
   * adjusted a second time — grandTotalAmount already reflects the discount in this case, so
   * re-applying the percentage would double-count it.
   */
  @Test
  public void testAfterHandleSkipsDraftWithMaterializedDiscountLine() throws Exception {
    TotalDiscountService mockTotalDiscountService = mock(TotalDiscountService.class);
    when(mockTotalDiscountService.hasDiscountLine("inv-with-line", true)).thenReturn(true);
    SalesInvoiceHeaderHandler handler = handlerWithTotalDiscountMock(mockTotalDiscountService);

    JSONObject invoice = new JSONObject().put("id", "inv-with-line").put("processed", false).put(
        "etgoTotalDiscount", 10.0).put("grandTotalAmount", 11.88).put("outstandingAmount", 11.88);
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(invoice)));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = handler.afterHandle(ctx);

    assertNotNull(result);
    double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0).getDouble(
        "grandTotalAmount");
    assertEquals(11.88, grand, 0.001);
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

  // ── SifSubRecordAttachments wiring (ETP-4888) ─────────────────────────────

  /**
   * Verifies that {@link SifSubRecordAttachments#enrich} is only invoked for detail GET
   * (recordId != null) — a list GET must never carry {@code aeatsiiFacturaId},
   * {@code tbaiSyncInvoiceId}, or {@code invoiceVerifactuId}.
   */
  @Test
  public void testSifSubRecordAttachmentsNotCalledForListView() throws Exception {
    JSONObject body = invoiceBody(false, 0.0, 100.0, 100.0);
    NeoContext ctx = getCtx(); // recordId = null — list view
    ctx.setPreviousResult(NeoResponse.ok(body));

    // No OBDal mock needed — SifSubRecordAttachments.enrich should not be reached
    NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    JSONObject rec = result.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);

    assertFalse(rec.has("aeatsiiFacturaId"));
    assertFalse(rec.has("tbaiSyncInvoiceId"));
    assertFalse(rec.has("invoiceVerifactuId"));
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
  public void resolveSubtype_arcCategory_returnsRectificativa() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARC");
      when(dal.get(DocumentType.class, "dt-arc")).thenReturn(dt);

      TestableSalesHandler h = new TestableSalesHandler();
      assertEquals("RECTIFICATIVA", h.callResolveSubtype("dt-arc"));
    }
  }

  @Test
  public void resolveSubtype_ariRmCategory_returnsRectificativa() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARI_RM");
      when(dal.get(DocumentType.class, "dt-ari-rm")).thenReturn(dt);

      TestableSalesHandler h = new TestableSalesHandler();
      assertEquals("RECTIFICATIVA", h.callResolveSubtype("dt-ari-rm"));
    }
  }

  /**
   * ETP-4737: the new unified rectificative doc type is driven primarily by the
   * {@code EM_Etsg_Isrectificative} flag, independent of {@code documentCategory} — proven here
   * with an otherwise-FAC category ("ARI", standard AR invoice) that only classifies as
   * RECTIFICATIVA because the flag is set.
   */
  @Test
  public void resolveSubtype_rectificativeFlagSet_returnsRectificativaRegardlessOfCategory() {
    AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARI");
      when(dt.isEtsgIsRectificative()).thenReturn(true);
      when(dal.get(DocumentType.class, "dt-new-rectificativa")).thenReturn(dt);

      TestableSalesHandler h = new TestableSalesHandler();
      assertEquals("RECTIFICATIVA", h.callResolveSubtype("dt-new-rectificativa"));
    } finally {
      AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(null);
    }
  }

  /**
   * When the rectificative column is not present (SIF General not installed), classification
   * falls back to the legacy category-based rule even though the mock would otherwise report the
   * flag as set — {@link RectificativeSupport#isRectificative} must short-circuit to false.
   */
  @Test
  public void resolveSubtype_rectificativeColumnAbsent_fallsBackToCategory() {
    AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(false);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARI");
      when(dal.get(DocumentType.class, "dt-ari-no-column")).thenReturn(dt);

      TestableSalesHandler h = new TestableSalesHandler();
      assertEquals("FAC", h.callResolveSubtype("dt-ari-no-column"));
    } finally {
      AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(null);
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

  // ── afterHandle(): the stored amount sign is passed through untouched ─────

  /**
   * Builds a body for subtype/sign tests with a specific transactionDocument field.
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
   * ETP-4841 regression guard for the deleted {@code applyAmountNegationForCredit}: a POSITIVE
   * Factura Rectificativa (via the legacy ARC / Credit Memo category) is an under-invoiced
   * correction that the customer OWES, so its amounts must reach the grid exactly as stored.
   * Forcing them negative made the list contradict the detail page and mislabelled a payable as a
   * "saldo a favor".
   */
  @Test
  public void afterHandle_positiveRectificativaViaArc_keepsPositiveAmounts() throws Exception {
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
      // The doc-type badge still says RECTIFICATIVA — only the SIGN rewriting is gone.
      assertEquals("RECTIFICATIVA", rec.getString("arInvoiceSubtype"));
      assertEquals(150.0, rec.getDouble("grandTotalAmount"), 0.001);
      assertEquals(100.0, rec.getDouble("outstandingAmount"), 0.001);
    }
  }

  /**
   * Same guard through the other legacy rectificative category (ARI_RM / Return Invoice): a
   * positive total is passed through untouched.
   */
  @Test
  public void afterHandle_positiveRectificativaViaAriRm_keepsPositiveAmounts() throws Exception {
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
      assertEquals("RECTIFICATIVA", rec.getString("arInvoiceSubtype"));
      assertEquals(200.0, rec.getDouble("grandTotalAmount"), 0.001);
      assertEquals(200.0, rec.getDouble("outstandingAmount"), 0.001);
    }
  }

  /**
   * A genuinely NEGATIVE rectificativa (the ordinary "saldo a favor" case) also passes through
   * unchanged — the handler neither negates nor re-negates, whatever the stored sign is.
   */
  @Test
  public void afterHandle_negativeRectificativa_keepsNegativeAmounts() throws Exception {
    JSONObject body = invoiceBodyWithDocType("dt-arc", -150.0, -100.0);
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
      assertEquals("RECTIFICATIVA", rec.getString("arInvoiceSubtype"));
      assertEquals(-150.0, rec.getDouble("grandTotalAmount"), 0.001);
      assertEquals(-100.0, rec.getDouble("outstandingAmount"), 0.001);
    }
  }

  /**
   * A NEGATIVE ordinary Factura (the case ETP-4841 made spendable in the payment modal) keeps both
   * its FAC subtype and its negative amounts: the handler must not "fix" the sign of a plain
   * invoice either.
   */
  @Test
  public void afterHandle_negativeOrdinaryFactura_keepsFacSubtypeAndNegativeAmounts() throws Exception {
    JSONObject body = invoiceBodyWithDocType("dt-ari", -80.0, -80.0);
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARI");
      when(dal.get(DocumentType.class, "dt-ari")).thenReturn(dt);

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("FAC", rec.getString("arInvoiceSubtype"));
      assertEquals(-80.0, rec.getDouble("grandTotalAmount"), 0.001);
      assertEquals(-80.0, rec.getDouble("outstandingAmount"), 0.001);
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

  // ── handle() — ETP-4388: discount recalculation must precede completion ─────
  //
  // AR mirror of PurchaseInvoiceHeaderHandlerTest#handle_completionAction_recalculatesDiscount-
  // BeforeCompleting / #handle_nonCompletionAction_doesNotRecalculateDiscount. The identical
  // reordering fix (AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete BEFORE
  // completeInvoiceIfNeeded) was applied to SalesInvoiceHeaderHandler.handle() but the AP-only
  // regression coverage never got an AR counterpart. These tests close that gap.

  /**
   * Regression for the review finding on ETP-4388: {@code completeInvoiceIfNeeded} must not
   * short-circuit {@code handle()} before {@code applyTotalDiscountBeforeComplete} runs, or the
   * discount line would be stale/missing when the document is completed. Verifies both that
   * {@code totalDiscountService.recalculate(...)} is actually invoked for a completion request,
   * and that it runs BEFORE {@code ProcessInvoiceUtil.process(...)}. CRUD-shape request (PATCH
   * with {@code documentAction=CO} in the body).
   */
  @Test
  public void handle_completionAction_recalculatesDiscountBeforeCompleting() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("ar-inv-discount-co")
        .requestBody(body)
        .build();

    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    OBError success = new OBError();
    success.setType("Success");

    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        eq("ar-inv-discount-co"), eq("CO"), eq(""), eq(""), eq(""), any(), any()))
        .thenReturn(success);

    TotalDiscountService totalDiscountService = mock(TotalDiscountService.class);
    SalesInvoiceHeaderHandler handler = handlerWithTotalDiscountMock(totalDiscountService);

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
         MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      // validateLineQtyBeforeComplete guard: no linked shipment lines → passes.
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any())).thenReturn(vars);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);
      when(dal.get(Process.class, "111")).thenReturn(mock(Process.class));

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());

      InOrder order = Mockito.inOrder(totalDiscountService, processInvoiceUtil);
      order.verify(totalDiscountService).recalculate("ar-inv-discount-co", true);
      order.verify(processInvoiceUtil).process(
          eq("ar-inv-discount-co"), eq("CO"), eq(""), eq(""), eq(""), any(), any());
    }
  }

  /**
   * Non-completion requests must not trigger discount recalculation at all — only the CO path
   * does (guarded by {@code applyTotalDiscountBeforeComplete}'s own completion check).
   */
  @Test
  public void handle_nonCompletionAction_doesNotRecalculateDiscount() throws Exception {
    JSONObject body = new JSONObject().put("someOtherField", "x");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("ar-inv-not-completing")
        .requestBody(body)
        .build();

    TotalDiscountService totalDiscountService = mock(TotalDiscountService.class);
    SalesInvoiceHeaderHandler handler = handlerWithTotalDiscountMock(totalDiscountService);

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      handler.handle(ctx);

      Mockito.verifyNoInteractions(totalDiscountService);
    }
  }

  /**
   * ACTION-shape completion request (POST /action/documentAction with
   * {@code fieldValues.documentAction=CO} — the shape sent by the draft-mode confirm button) must
   * also recalculate the discount BEFORE completing. Closes the shape-coverage gap: the CRUD-shape
   * ordering was covered above (and, for AP, in PurchaseInvoiceHeaderHandlerTest), but neither
   * side had a regression test for the ACTION shape.
   */
  @Test
  public void handle_actionShapeCompletionAction_recalculatesDiscountBeforeCompleting()
      throws Exception {
    JSONObject fieldValues = new JSONObject().put("documentAction", "CO");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .recordId("ar-inv-action-co")
        .requestBody(body)
        .build();

    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    OBError success = new OBError();
    success.setType("Success");

    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        eq("ar-inv-action-co"), eq("CO"), eq(""), eq(""), eq(""), any(), any()))
        .thenReturn(success);

    TotalDiscountService totalDiscountService = mock(TotalDiscountService.class);
    SalesInvoiceHeaderHandler handler = handlerWithTotalDiscountMock(totalDiscountService);

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
         MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any())).thenReturn(vars);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);
      when(dal.get(Process.class, "111")).thenReturn(mock(Process.class));

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());

      InOrder order = Mockito.inOrder(totalDiscountService, processInvoiceUtil);
      order.verify(totalDiscountService).recalculate("ar-inv-action-co", true);
      order.verify(processInvoiceUtil).process(
          eq("ar-inv-action-co"), eq("CO"), eq(""), eq(""), eq(""), any(), any());
    }
  }
}
