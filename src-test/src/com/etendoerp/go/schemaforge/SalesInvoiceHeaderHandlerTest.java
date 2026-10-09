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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link SalesInvoiceHeaderHandler}.
 *
 * <p>Covers two responsibilities:
 * <ul>
 *   <li>{@code handle()} — routes ACTION requests to the right downstream handler
 *       (clone record / register payment) or returns null when none matches.</li>
 *   <li>{@code afterHandle()} — adjusts {@code grandTotalAmount} and {@code outstandingAmount}
 *       in GET responses for draft invoices that carry a positive {@code etgoTotalDiscount}.</li>
 *   <li>{@code followUpFlows()} — the one goods-shipment follow-up and its sales-side wiring,
 *       and the {@code followUp} annotation written by {@code afterHandle()} (ETP-5576).</li>
 * </ul>
 *
 * @covers com.etendoerp.go.schemaforge.SalesInvoiceHeaderHandler
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

  // ── ETP-5547: action POSTs must not re-sync the rate row of a processed invoice ──

  private static final String ETP5547_INVOICE = "inv-5547";

  /**
   * Runs {@code afterHandle} for a header ACTION POST on a processed, foreign-currency invoice
   * with an exchange-rate override, and asserts the conversion-rate sync was never reached.
   * Before ETP-5547 every POST re-synced the invoice's rate row; on a posted invoice Core's
   * trigger rejected that write and the aborted transaction silently rolled back the confirmed
   * payment (registerPayment) or the clone (cloneRecord) after a 2xx had been answered.
   */
  private static void assertActionDoesNotSyncConversionRate(String actionName) throws Exception {
    NeoContext ctx = NeoContext.builder()
        .specName("sales-invoice").entityName("header")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName(actionName)
        .recordId(ETP5547_INVOICE).requestBody(new JSONObject()).build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class);
         MockedStatic<ConversionRateDocumentSync> syncMock =
             Mockito.mockStatic(ConversionRateDocumentSync.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);

      Invoice invoice = mock(Invoice.class);
      Currency currency = mock(Currency.class);
      Organization org = mock(Organization.class);
      when(dal.get(Invoice.class, ETP5547_INVOICE)).thenReturn(invoice);
      when(invoice.getId()).thenReturn(ETP5547_INVOICE);
      when(invoice.isProcessed()).thenReturn(true);
      when(invoice.getPosted()).thenReturn("N");
      when(invoice.getCurrency()).thenReturn(currency);
      when(currency.getId()).thenReturn("usd-5547");
      when(invoice.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-5547");
      when(invoice.getETGOCurrencyRate()).thenReturn(new BigDecimal("1.16"));
      when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("29.39"));
      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-5547")).thenReturn("eur-5547");

      new SalesInvoiceHeaderHandler().afterHandle(ctx);

      syncMock.verifyNoInteractions();
      Mockito.verify(dal, Mockito.never()).getConnection();
    }
  }

  @Test
  public void testAfterHandleDoesNotSyncConversionRateOnRegisterPaymentAction() throws Exception {
    assertActionDoesNotSyncConversionRate("registerPayment");
  }

  @Test
  public void testAfterHandleDoesNotSyncConversionRateOnCloneRecordAction() throws Exception {
    assertActionDoesNotSyncConversionRate("cloneRecord");
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

    // ETP-5576: the record has an id, so afterHandle runs the follow-up lookup. Mock OBDal so it
    // degrades deterministically (FOLLOW_UP_LOOKUP_FAILED) instead of reaching a real DAL.
    NeoResponse result;
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      result = handler.afterHandle(ctx);
    }

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
      // ETP-5576: afterHandle now also runs the follow-up lookup (and other enrichers) on the
      // same connection. Route only enrichSourceInvoice's statement to the stubbed row; every
      // other statement reads an empty result, so none of them consumes rs.next().
      PreparedStatement otherPs = mock(PreparedStatement.class);
      ResultSet emptyRs = mock(ResultSet.class);
      when(otherPs.executeQuery()).thenReturn(emptyRs);
      when(conn.prepareStatement(anyString())).thenReturn(otherPs);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(contains("Canceled_Inoutline_ID"))).thenReturn(ps);
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
      // ETP-5576: afterHandle now also runs the follow-up lookup (and other enrichers) on the
      // same connection. Route only enrichSourceInvoice's statement to the stubbed row; every
      // other statement reads an empty result, so none of them consumes rs.next().
      PreparedStatement otherPs = mock(PreparedStatement.class);
      ResultSet emptyRs = mock(ResultSet.class);
      when(otherPs.executeQuery()).thenReturn(emptyRs);
      when(conn.prepareStatement(anyString())).thenReturn(otherPs);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(contains("Canceled_Inoutline_ID"))).thenReturn(ps);
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
      // ETP-5576: the page is annotated with the sales follow-up; the pending query found no
      // row for this id, so the shipment is not offered.
      JSONObject followUp = rec.getJSONObject("followUp");
      assertEquals(0, followUp.getJSONArray("available").length());
      JSONObject shipment = followUp.getJSONObject("shipment");
      assertFalse(shipment.getBoolean("needed"));
      assertEquals("FOLLOW_UP_SOURCE_NOT_FOUND", shipment.getString("reason"));
      assertEquals("createShipment", shipment.getString("action"));
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

  // ── ETP-5238: paymentMethod SELECTOR is independent of Financial Account ────

  /**
   * A SELECTOR context wired with an AD Tab/Window pair whose {@code isSalesTransaction()} is
   * {@code true} — the sales-invoice window declares {@link
   * com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport.DirectionFallback#WINDOW},
   * so this is what the handler resolves direction from once the {@code IsSOTrx} request param
   * is absent.
   */
  private static NeoContext salesWindowSelectorCtx() {
    Window window = mock(Window.class);
    when(window.isSalesTransaction()).thenReturn(true);
    Tab tab = mock(Tab.class);
    when(tab.getWindow()).thenReturn(window);
    return NeoContext.builder()
        .specName("sales-invoice").entityName("header").httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR).fieldName("paymentMethod")
        .adTab(tab).build();
  }

  private void stubPaymentMethodSelectorRequest(MockedStatic<RequestContext> reqCtx,
      Map<String, String> params) {
    RequestContext requestContext = mock(RequestContext.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    reqCtx.when(RequestContext::get).thenReturn(requestContext);
    when(requestContext.getRequest()).thenReturn(request);
    when(request.getParameter(anyString())).thenReturn(null);

    Map<String, String[]> parameterMap = new HashMap<>();
    for (Map.Entry<String, String> entry : params.entrySet()) {
      parameterMap.put(entry.getKey(), new String[] { entry.getValue() });
    }
    when(request.getParameterMap()).thenReturn(parameterMap);
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Criterion> stubPaymentMethodCriteria(MockedStatic<OBDal> obDal,
      List<FIN_PaymentMethod> rows) {
    OBDal obDalMock = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(obDalMock);
    OBCriteria<FIN_PaymentMethod> criteria = mock(OBCriteria.class);
    when(obDalMock.createCriteria(FIN_PaymentMethod.class)).thenReturn(criteria);
    ArgumentCaptor<Criterion> captor = ArgumentCaptor.forClass(Criterion.class);
    when(criteria.add(captor.capture())).thenReturn(criteria);
    when(criteria.addOrderBy(any(), anyBoolean())).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    when(criteria.setFirstResult(anyInt())).thenReturn(criteria);
    when(criteria.count()).thenReturn(rows.size());
    when(criteria.list()).thenReturn(rows);
    return captor;
  }

  /** Renders every captured restriction (read only AFTER invoking the method under test). */
  private List<String> renderedCriteria(ArgumentCaptor<Criterion> captor) {
    return captor.getAllValues().stream().map(Criterion::toString)
        .collect(java.util.stream.Collectors.toList());
  }

  /**
   * The paymentMethod SELECTOR request must be served by
   * {@code com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport}, short-circuiting
   * before {@code postingService}/{@code totalDiscountService} or any ACTION delegate is reached,
   * and — since this window declares the {@code WINDOW} direction fallback — the criteria built
   * when {@code IsSOTrx} is absent must be against {@code payinAllow}, never {@code payoutAllow}.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleServesPaymentMethodSelectorIndependentOfFinancialAccount() throws Exception {
    NeoContext ctx = salesWindowSelectorCtx();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {
      stubPaymentMethodSelectorRequest(reqCtx, Collections.emptyMap());

      ArgumentCaptor<Criterion> captor = stubPaymentMethodCriteria(obDal, Collections.emptyList());

      NeoResponse response = new SalesInvoiceHeaderHandler().handle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      List<String> rendered = renderedCriteria(captor);
      assertTrue("expected a payinAllow filter, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payinallow")));
      assertFalse("must not filter on payoutAllow for a sales document, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payoutallow")));
    }
  }

  /**
   * Same window, but with the request explicitly sending {@code IsSOTrx=Y} (the normal case in
   * production) — must resolve identically to the absent-param case above.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleServesPaymentMethodSelectorBuildsPayinCriteriaWhenIsSOTrxIsY() throws Exception {
    NeoContext ctx = salesWindowSelectorCtx();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {
      Map<String, String> params = new HashMap<>();
      params.put("IsSOTrx", "Y");
      stubPaymentMethodSelectorRequest(reqCtx, params);

      ArgumentCaptor<Criterion> captor = stubPaymentMethodCriteria(obDal, Collections.emptyList());

      NeoResponse response = new SalesInvoiceHeaderHandler().handle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      List<String> rendered = renderedCriteria(captor);
      assertTrue("expected a payinAllow filter, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payinallow")));
      assertFalse("must not filter on payoutAllow for a sales document, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payoutallow")));
    }
  }

  /**
   * Non-selector requests are completely unaffected by the new SELECTOR branch (no-regression).
   */
  @Test
  public void testHandleStillReturnsNullForNonSelectorRequestAfterPaymentMethodWiring() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new SalesInvoiceHeaderHandler().handle(ctx));
  }

  // ── follow-up document registration (ETP-5576) ────────────────────────────

  /**
   * A sales invoice offers exactly one follow-up, the goods shipment, wired end to end to the
   * SALES side: its resolver rejects a purchase invoice, its eligibility is this handler's own FAC
   * classification (a credit memo is not eligible), its creator builds a SALES movement through
   * {@link InvoiceInOutMapping}, and the created lines are linked through M_MatchSI.
   */
  @Test
  public void followUpFlowsRegisterTheGoodsShipmentWiredToTheSalesSide() throws Exception {
    List<FollowUpFlow> flows = new SalesInvoiceHeaderHandler().followUpFlows();

    assertEquals(1, flows.size());
    FollowUpFlow flow = flows.get(0);
    assertSame(FollowUpTarget.GOODS_SHIPMENT, flow.target());
    assertEquals(Invoice.class, flow.sourceEntity());
    InvoicePendingResolver resolver = (InvoicePendingResolver) flow.resolver();
    assertEquals(FollowUpException.Reason.WRONG_DIRECTION,
        resolver.ineligibility("N", "CO", "dt-ari"));
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType creditMemo = mock(DocumentType.class);
      when(creditMemo.getDocumentCategory()).thenReturn("ARC");
      DocumentType standard = mock(DocumentType.class);
      when(standard.getDocumentCategory()).thenReturn("ARI");
      when(dal.get(DocumentType.class, "dt-arc")).thenReturn(creditMemo);
      when(dal.get(DocumentType.class, "dt-ari")).thenReturn(standard);

      assertEquals(FollowUpException.Reason.NOT_ELIGIBLE_TYPE,
          resolver.ineligibility("Y", "CO", "dt-arc"));
      assertNull(resolver.ineligibility("Y", "CO", "dt-ari"));
    }

    InOutTargetBuilder.Line line = InOutTargetBuilder.Line.builder().sourceLineId("il-1")
        .quantity(BigDecimal.ONE).stockable(false).build();
    Object[] buildArgs = createThroughFlow(flow, line);

    assertEquals(InOutTargetBuilder.Direction.SALES, buildArgs[0]);
    ShipmentInOutLine created = mock(ShipmentInOutLine.class);
    when(created.getId()).thenReturn("iol-1");
    try (MockedStatic<InvoiceLineLinker> linkerMock = Mockito.mockStatic(InvoiceLineLinker.class)) {
      ((InOutTargetBuilder.LineLinker) buildArgs[3]).link(line, created);

      linkerMock.verify(() -> InvoiceLineLinker.linkInvoiceLineToInOutLine("il-1", "iol-1",
          InOutInvoiceLinks.MatchTable.SALES));
    }
  }

  /**
   * Runs {@code flow.createTarget} with {@link InvoiceInOutMapping#map} answering one
   * {@code line} and {@link InOutTargetBuilder#build} mocked, and returns the arguments the
   * builder received (direction, header, lines, linker).
   */
  private static Object[] createThroughFlow(FollowUpFlow flow, InOutTargetBuilder.Line line) {
    List<PendingResolver.SourceLine> pending = Collections.singletonList(
        new PendingResolver.SourceLine("il-1", BigDecimal.ONE));
    InOutFollowUpCreator.Mapping mapping = new InOutFollowUpCreator.Mapping(
        new InOutTargetBuilder.Header(null, null, null, null, null, null, null),
        Collections.singletonList(line));
    ShipmentInOut inout = mock(ShipmentInOut.class);
    when(inout.getId()).thenReturn("io-1");
    AtomicReference<Object[]> buildArgs = new AtomicReference<>();
    try (MockedStatic<InvoiceInOutMapping> mappingMock = Mockito.mockStatic(InvoiceInOutMapping.class);
         MockedStatic<InOutTargetBuilder> builderMock = Mockito.mockStatic(InOutTargetBuilder.class)) {
      mappingMock.when(() -> InvoiceInOutMapping.map("inv-1", pending, FollowUpInputs.none()))
          .thenReturn(mapping);
      builderMock.when(() -> InOutTargetBuilder.build(any(), any(), any(), any()))
          .thenAnswer(inv -> {
            buildArgs.set(inv.getArguments());
            return inout;
          });

      assertEquals("io-1", flow.createTarget("inv-1", pending, FollowUpInputs.none()).getId());
    }
    return buildArgs.get();
  }

  // ---------------------------------------------------------------------------
  // ETP-5692 — completed-invoice write fence, wired into the header handler
  // ---------------------------------------------------------------------------

  /** A processed invoice whose stored {@code paymentMethod} / {@code costcenter} are "PM-1" / "CC-1". */
  private static Invoice etp5692Invoice(String docStatus, String posted) {
    java.util.Map<String, Object> stored = new java.util.HashMap<>();
    org.openbravo.base.structure.BaseOBObject pm = mock(org.openbravo.base.structure.BaseOBObject.class);
    when(pm.getId()).thenReturn("PM-1");
    org.openbravo.base.structure.BaseOBObject cc = mock(org.openbravo.base.structure.BaseOBObject.class);
    when(cc.getId()).thenReturn("CC-1");
    stored.put("paymentMethod", pm);
    stored.put("costcenter", cc);
    Invoice invoice = mock(Invoice.class);
    org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
    when(entity.hasProperty(anyString())).thenAnswer(inv -> stored.containsKey(inv.getArgument(0)));
    when(invoice.getEntity()).thenReturn(entity);
    when(invoice.get(anyString())).thenAnswer(inv -> stored.get(inv.<String>getArgument(0)));
    when(invoice.isProcessed()).thenReturn(true);
    when(invoice.getDocumentStatus()).thenReturn(docStatus);
    when(invoice.getPosted()).thenReturn(posted);
    return invoice;
  }

  private static NeoResponse etp5692Patch(NeoHandler handler, Invoice invoice, String key,
      String value) throws Exception {
    JSONObject body = new JSONObject();
    body.put(key, value);
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId("INV-5692")
        .requestBody(body)
        .build();
    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Invoice.class, "INV-5692")).thenReturn(invoice);
      return handler.handle(context);
    }
  }

  /**
   * ETP-5692 BUG-3 repro: a REST/MCP PATCH of a field the SPA locks on a Completed invoice used to
   * answer 200. The header handler now refuses it before the CRUD write.
   */
  @Test
  public void etp5692PatchOfLockedFieldOnCompletedInvoiceIsRefused() throws Exception {
    NeoResponse response = etp5692Patch(new SalesInvoiceHeaderHandler(),
        etp5692Invoice("CO", "N"), "paymentMethod", "PM-2");

    assertNotNull(response);
    assertEquals(422, response.getHttpStatus());
    assertEquals("completed_invoice_fields_locked",
        response.getBody().getJSONObject("error").getString("code"));
  }

  /** ETP-5692 BUG-2 repro: the header cost center of a POSTED invoice used to answer 200. */
  @Test
  public void etp5692PatchOfCostCenterOnPostedInvoiceIsRefused() throws Exception {
    NeoResponse response = etp5692Patch(new SalesInvoiceHeaderHandler(),
        etp5692Invoice("CO", "Y"), "costcenter", "CC-2");

    assertNotNull(response);
    assertEquals(422, response.getHttpStatus());
    assertEquals("posted_invoice_fields_locked",
        response.getBody().getJSONObject("error").getString("code"));
  }
}
