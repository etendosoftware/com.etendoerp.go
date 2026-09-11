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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;

/**
 * Unit tests for PurchaseOrderHeaderHandler.afterHandle().
 *
 * The afterHandle() logic is inherited from AbstractOrderHeaderHandler — it annotates
 * hasLinkedDocuments by querying C_Invoice and M_InOut. These tests confirm the same
 * contract holds for the purchase-order handler.
 */
public class PurchaseOrderHeaderHandlerTest {

  // ── helpers ───────────────────────────────────────────────────────────────

  private static NeoContext getCtxWithId(String recordId) {
    return NeoContext.builder()
        .specName("purchase-order")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  private static JSONObject singleRecordBody(String id) throws JSONException {
    JSONObject orderRec = new JSONObject().put("id", id).put("documentNo", "PO-" + id);
    JSONArray data = new JSONArray().put(orderRec);
    JSONObject response = new JSONObject().put("data", data);
    return new JSONObject().put("response", response);
  }

  private static JSONObject listBody(String... ids) throws JSONException {
    JSONArray data = new JSONArray();
    for (String id : ids) {
      data.put(new JSONObject().put("id", id).put("documentNo", "PO-" + id));
    }
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  // ── guard conditions ───────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for non-GET requests without invoking DB logic.
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(new PurchaseOrderHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenPreviousResultIsNull() {
    NeoContext ctx = getCtxWithId("po-1");
    assertNull(new PurchaseOrderHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtxWithId("po-1");
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new PurchaseOrderHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtxWithId("po-1");
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new PurchaseOrderHeaderHandler().afterHandle(ctx));
  }

  // ── total discount adjustment (ETP-4029 follow-up: same gap as invoices) ──
  //
  // afterHandle() always runs the hasLinkedDocuments DB check after the discount loop — every
  // test below mocks OBDal minimally (no linked documents found) purely to keep that
  // unconditional step from touching a real DB.

  private static PurchaseOrderHeaderHandler handlerWithTotalDiscountMock(
      TotalDiscountService mock) throws Exception {
    PurchaseOrderHeaderHandler handler = new PurchaseOrderHeaderHandler();
    Field field = PurchaseOrderHeaderHandler.class.getDeclaredField("totalDiscountService");
    field.setAccessible(true);
    field.set(handler, mock);
    return handler;
  }

  private static JSONObject orderRecordWithDiscount(boolean processed, double discount,
      double grandTotal) throws JSONException {
    return new JSONObject().put("id", "po-disc-1").put("processed", processed).put(
        "etgoTotalDiscount", discount).put("grandTotalAmount", grandTotal);
  }

  /** Stubs OBDal so the DB-backed hasLinkedDocuments check finds nothing. */
  private static void stubNoLinkedDocuments(MockedStatic<OBDal> obDalMock) throws Exception {
    OBDal dal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);
  }

  /**
   * Verifies that a processed purchase order is not adjusted.
   */
  @Test
  public void testAfterHandleSkipsProcessedOrderWithDiscount() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(orderRecordWithDiscount(true, 10.0, 108.90))));
      NeoContext ctx = getCtxWithId("po-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(108.90, grand, 0.001);
    }
  }

  /**
   * Verifies that a draft purchase order with no total discount is not modified.
   */
  @Test
  public void testAfterHandleSkipsDraftOrderWithNoDiscount() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(orderRecordWithDiscount(false, 0.0, 121.00))));
      NeoContext ctx = getCtxWithId("po-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(121.00, grand, 0.001);
    }
  }

  /**
   * Verifies that a draft purchase order whose discount is already materialized as a real line
   * is NOT adjusted a second time.
   */
  @Test
  public void testAfterHandleSkipsDraftOrderWithMaterializedDiscountLine() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      TotalDiscountService mockService = mock(TotalDiscountService.class);
      when(mockService.hasDiscountLine("po-disc-1", false)).thenReturn(true);
      PurchaseOrderHeaderHandler handler = handlerWithTotalDiscountMock(mockService);

      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(orderRecordWithDiscount(false, 10.0, 108.90))));
      NeoContext ctx = getCtxWithId("po-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(108.90, grand, 0.001);
    }
  }

  /**
   * Verifies that a draft purchase order with etgoTotalDiscount=10 and grandTotalAmount=121.00
   * is adjusted to 108.90 (121.00 x 0.90) — the exact scenario reproduced manually against the
   * running app (purchase invoice reproduction; same shared AbstractOrderHeaderHandler logic).
   */
  @Test
  public void testAfterHandleAdjustsGrandTotalForDraftOrderWithDiscount() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      TotalDiscountService mockService = mock(TotalDiscountService.class);
      when(mockService.hasDiscountLine("po-disc-1", false)).thenReturn(false);
      PurchaseOrderHeaderHandler handler = handlerWithTotalDiscountMock(mockService);

      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(orderRecordWithDiscount(false, 10.0, 121.00))));
      NeoContext ctx = getCtxWithId("po-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(108.90, grand, 0.005);
    }
  }

  // ── single-record GET ──────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle annotates hasLinkedDocuments=true when the DB query finds a linked document.
   */
  @Test
  public void testAfterHandleSingleRecordAnnotatesTrueWhenLinkedDocumentExists() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);

      JSONObject body = singleRecordBody("po-1");
      NeoContext ctx = getCtxWithId("po-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertTrue(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
    }
  }

  /**
   * Verifies that afterHandle annotates hasLinkedDocuments=false when the DB query returns no rows.
   */
  @Test
  public void testAfterHandleSingleRecordAnnotatesFalseWhenNoLinkedDocuments() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      JSONObject body = singleRecordBody("po-2");
      NeoContext ctx = getCtxWithId("po-2");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
    }
  }

  // ── list GET (batch) ───────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle annotates only the ids returned by the batch query as true.
   */
  @Test
  public void testAfterHandleListAnnotatesMatchingIdsAsTrue() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("po-1");

      JSONObject body = listBody("po-1", "po-2");
      NeoContext ctx = NeoContext.builder()
          .specName("purchase-order").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
          .build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertTrue(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
      assertFalse(data.getJSONObject(1).getBoolean("hasLinkedDocuments"));
    }
  }

  // ── DB error resilience ────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle annotates hasLinkedDocuments=false and does not throw when the single-record DB query fails.
   */
  @Test
  public void testAfterHandleSingleRecordReturnsFalseWhenDbQueryThrows() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenThrow(new SQLException("DB down"));

      JSONObject body = singleRecordBody("po-3");
      NeoContext ctx = getCtxWithId("po-3");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
    }
  }

  /**
   * Verifies that afterHandle annotates all list records as false and does not throw when the batch DB query fails.
   */
  @Test
  public void testAfterHandleListAnnotatesAllFalseWhenBatchQueryThrows() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenThrow(new SQLException("DB down"));

      JSONArray dataArr = new JSONArray();
      dataArr.put(new JSONObject().put("id", "po-4").put("documentNo", "PO-4"));
      dataArr.put(new JSONObject().put("id", "po-5").put("documentNo", "PO-5"));
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", dataArr));
      NeoContext ctx = NeoContext.builder()
          .specName("purchase-order").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
          .build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
      assertFalse(data.getJSONObject(1).getBoolean("hasLinkedDocuments"));
    }
  }

  /**
   * Verifies that afterHandle returns a valid response when all list records lack an id field,
   * skipping the batch DB query entirely.
   */
  @Test
  public void testAfterHandleListReturnsResponseWhenAllRecordsHaveNoId() throws JSONException {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("documentNo", "NO-ID-DOC"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = NeoContext.builder()
        .specName("purchase-order").entityName("header")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .build();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
  }

  // ── handle() dispatch ──────────────────────────────────────────────────────

  private static PurchaseOrderHeaderHandler handlerWithMockClone(NeoCloneRecordHandler mockClone)
      throws Exception {
    PurchaseOrderHeaderHandler handler = new PurchaseOrderHeaderHandler();
    Field field = PurchaseOrderHeaderHandler.class.getDeclaredField("cloneRecordHandler");
    field.setAccessible(true);
    field.set(handler, mockClone);
    return handler;
  }

  /**
   * Verifies that handle returns the clone response immediately when the clone handler matches.
   */
  @Test
  public void testHandleShortCircuitsWhenCloneHandlerResponds() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    PurchaseOrderHeaderHandler handler = handlerWithMockClone(mockClone);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "clone"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("cloneRecord").build();
    when(mockClone.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
  }

  /**
   * Verifies that handle returns null when no downstream handler matches the context.
   */
  @Test
  public void testHandleReturnsNullWhenNoHandlerMatches() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    PurchaseOrderHeaderHandler handler = handlerWithMockClone(mockClone);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    when(mockClone.handle(ctx)).thenReturn(null);

    assertNull(handler.handle(ctx));
  }

  // ── ETP-5238: paymentMethod SELECTOR is independent of Financial Account ────

  /**
   * A SELECTOR context wired with an AD Tab/Window pair whose {@code isSalesTransaction()} is
   * {@code false} — the purchase-order window declares {@link
   * com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport.DirectionFallback#WINDOW},
   * so this is what the handler resolves direction from once the {@code IsSOTrx} request param
   * is absent.
   */
  private static NeoContext purchaseWindowSelectorCtx() {
    Window window = mock(Window.class);
    when(window.isSalesTransaction()).thenReturn(false);
    Tab tab = mock(Tab.class);
    when(tab.getWindow()).thenReturn(window);
    return NeoContext.builder()
        .specName("purchase-order").entityName("header").httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR).fieldName("paymentMethod")
        .adTab(tab).build();
  }

  private void stubSelectorRequest(MockedStatic<RequestContext> reqCtx, Map<String, String> params) {
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
   * before any dispatch to the injected ACTION delegates, and — since this window declares the
   * {@code WINDOW} direction fallback — the criteria built when {@code IsSOTrx} is absent must be
   * against {@code payoutAllow}, never {@code payinAllow} (the regression this fix closes: this
   * window's own field name, {@code paymentMethod}, is shared with the sales side).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleServesPaymentMethodSelectorIndependentOfFinancialAccount() throws Exception {
    NeoContext ctx = purchaseWindowSelectorCtx();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {
      stubSelectorRequest(reqCtx, Collections.emptyMap());

      ArgumentCaptor<Criterion> captor = stubPaymentMethodCriteria(obDal, Collections.emptyList());

      NeoResponse response = new PurchaseOrderHeaderHandler().handle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      List<String> rendered = renderedCriteria(captor);
      assertTrue("expected a payoutAllow filter, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payoutallow")));
      assertFalse("must not filter on payinAllow for a purchase document, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payinallow")));
    }
  }

  /**
   * Same window, but with the request explicitly sending {@code IsSOTrx=N} (the normal case in
   * production) — must resolve identically to the absent-param case above.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleServesPaymentMethodSelectorBuildsPayoutCriteriaWhenIsSOTrxIsN() throws Exception {
    NeoContext ctx = purchaseWindowSelectorCtx();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {
      Map<String, String> params = new HashMap<>();
      params.put("IsSOTrx", "N");
      stubSelectorRequest(reqCtx, params);

      ArgumentCaptor<Criterion> captor = stubPaymentMethodCriteria(obDal, Collections.emptyList());

      NeoResponse response = new PurchaseOrderHeaderHandler().handle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      List<String> rendered = renderedCriteria(captor);
      assertTrue("expected a payoutAllow filter, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payoutallow")));
      assertFalse("must not filter on payinAllow for a purchase document, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payinallow")));
    }
  }

  /**
   * Non-selector requests are completely unaffected by the new SELECTOR branch (no-regression).
   */
  @Test
  public void testHandleStillReturnsNullForNonSelectorRequestAfterPaymentMethodWiring() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new PurchaseOrderHeaderHandler().handle(ctx));
  }
}
