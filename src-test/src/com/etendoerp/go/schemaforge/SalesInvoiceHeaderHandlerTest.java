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

/**
 * Unit tests for SalesInvoiceHeaderHandler.afterHandle().
 *
 * Tests are split into four groups:
 * <ul>
 *   <li><strong>Guard-condition</strong> – early returns without any DB access</li>
 *   <li><strong>Single-record GET</strong> – bpEmail annotated from C_BPartner.EM_Etgo_Email</li>
 *   <li><strong>List GET (batch)</strong> – batch query maps emails to each invoice record</li>
 *   <li><strong>DB error resilience</strong> – graceful fallback to empty string on SQL failure</li>
 * </ul>
 */
public class SalesInvoiceHeaderHandlerTest {

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * Builds a GET NeoContext for the sales-invoice header with the given record id.
   * Pass {@code null} to simulate a list GET (no specific record).
   */
  private static NeoContext getCtxWithId(String recordId) {
    return NeoContext.builder()
        .specName("sales-invoice")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  /**
   * Builds a list GET NeoContext (no recordId) for the sales-invoice header.
   */
  private static NeoContext listCtx() {
    return getCtxWithId(null);
  }

  /**
   * Wraps a single invoice record in the standard NEO response envelope.
   *
   * @param invoiceId  the invoice record id
   * @param bPartnerId the businessPartner FK value to include in the record
   */
  private static JSONObject singleRecordBody(String invoiceId, String bPartnerId) throws JSONException {
    JSONObject rec = new JSONObject()
        .put("id", invoiceId)
        .put("businessPartner", bPartnerId)
        .put("documentNo", "INV-" + invoiceId);
    return new JSONObject().put("response", new JSONObject().put("data", new JSONArray().put(rec)));
  }

  /**
   * Builds a list response body from a 2D array of {@code {invoiceId, bPartnerId}} pairs.
   *
   * @param entries array where each element is {@code {invoiceId, bPartnerId}}
   */
  private static JSONObject listBody(String[][] entries) throws JSONException {
    JSONArray data = new JSONArray();
    for (String[] entry : entries) {
      data.put(new JSONObject().put("id", entry[0]).put("businessPartner", entry[1]).put("documentNo", "INV-" + entry[0]));
    }
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  /**
   * Creates a {@link SalesInvoiceHeaderHandler} with its {@code @Inject} fields replaced by the
   * provided mocks via reflection, bypassing CDI in the unit-test context.
   *
   * @param mockClone   mock for {@link NeoCloneRecordHandler}
   * @param mockPayment mock for {@link RegisterPaymentHandler}
   */
  private static SalesInvoiceHeaderHandler handlerWithMocks(
      NeoCloneRecordHandler mockClone, RegisterPaymentHandler mockPayment) throws Exception {
    SalesInvoiceHeaderHandler handler = new SalesInvoiceHeaderHandler();
    Field cloneField = SalesInvoiceHeaderHandler.class.getDeclaredField("cloneRecordHandler");
    cloneField.setAccessible(true);
    cloneField.set(handler, mockClone);
    Field paymentField = SalesInvoiceHeaderHandler.class.getDeclaredField("registerPaymentHandler");
    paymentField.setAccessible(true);
    paymentField.set(handler, mockPayment);
    return handler;
  }

  // ── guard conditions (no DB needed) ───────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for POST requests without invoking DB logic.
   */
  @Test
  public void testAfterHandleReturnsNullForPostMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null for PATCH requests without invoking DB logic.
   */
  @Test
  public void testAfterHandleReturnsNullForPatchMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenPreviousResultIsNull() {
    NeoContext ctx = getCtxWithId("inv-1");
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtxWithId("inv-1");
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the response JSON lacks the "response" wrapper.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoResponseWrapper() throws JSONException {
    NeoContext ctx = getCtxWithId("inv-1");
    ctx.setPreviousResult(NeoResponse.ok(new JSONObject().put("something", "else")));
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtxWithId("inv-1");
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new SalesInvoiceHeaderHandler().afterHandle(ctx));
  }

  // ── single-record GET ──────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle annotates bpEmail with the value from C_BPartner when the DB query finds a row.
   */
  @Test
  public void testAfterHandleSingleRecordAnnotatesBpEmailWhenFound() throws Exception {
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
      when(rs.getString(1)).thenReturn("customer@example.com");

      JSONObject body = singleRecordBody("inv-1", "bp-1");
      NeoContext ctx = getCtxWithId("inv-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals("customer@example.com", data.getJSONObject(0).getString("bpEmail"));
    }
  }

  /**
   * Verifies that afterHandle annotates bpEmail as empty string when the DB query returns no rows.
   */
  @Test
  public void testAfterHandleSingleRecordAnnotatesEmptyEmailWhenNotFound() throws Exception {
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

      JSONObject body = singleRecordBody("inv-2", "bp-2");
      NeoContext ctx = getCtxWithId("inv-2");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals("", data.getJSONObject(0).getString("bpEmail"));
    }
  }

  /**
   * Verifies that afterHandle annotates bpEmail as empty string when the record has no businessPartner field.
   */
  @Test
  public void testAfterHandleSingleRecordAnnotatesEmptyEmailWhenBpIdIsMissing() throws JSONException {
    JSONObject rec = new JSONObject().put("id", "inv-3").put("documentNo", "INV-3");
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray().put(rec)));
    NeoContext ctx = getCtxWithId("inv-3");
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals("", data.getJSONObject(0).getString("bpEmail"));
  }

  // ── list GET (batch) ───────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle maps bpEmail from the batch query to the correct invoice record.
   */
  @Test
  public void testAfterHandleListAnnotatesBpEmailForMatchingPartners() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      // Only bp-1 has an email in the DB
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("bp-1");
      when(rs.getString(2)).thenReturn("partner@example.com");

      JSONObject body = listBody(new String[][]{{"inv-1", "bp-1"}, {"inv-2", "bp-2"}});
      NeoContext ctx = listCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(2, data.length());
      assertEquals("partner@example.com", data.getJSONObject(0).getString("bpEmail"));
      assertEquals("", data.getJSONObject(1).getString("bpEmail"));
    }
  }

  /**
   * Verifies that afterHandle annotates all list records with empty bpEmail when the batch query returns no rows.
   */
  @Test
  public void testAfterHandleListAnnotatesEmptyEmailWhenNoBatchResults() throws Exception {
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

      JSONObject body = listBody(new String[][]{{"inv-3", "bp-3"}, {"inv-4", "bp-4"}});
      NeoContext ctx = listCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals("", data.getJSONObject(0).getString("bpEmail"));
      assertEquals("", data.getJSONObject(1).getString("bpEmail"));
    }
  }

  /**
   * Verifies that afterHandle returns a 200 response after annotating the list.
   */
  @Test
  public void testAfterHandleListReturnsOkAfterAnnotation() throws Exception {
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

      JSONObject body = listBody(new String[][]{{"inv-5", "bp-5"}});
      NeoContext ctx = listCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
    }
  }

  /**
   * Verifies that afterHandle returns a valid response when list records have no businessPartner field,
   * skipping the batch DB query entirely.
   */
  @Test
  public void testAfterHandleListSkipsBatchWhenNoBusinessPartnerFields() throws JSONException {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("id", "inv-6").put("documentNo", "INV-6"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = listCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
  }

  // ── DB error resilience ────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle annotates bpEmail as empty string and does not throw when the single-record DB query fails.
   */
  @Test
  public void testAfterHandleSingleRecordAnnotatesEmptyEmailWhenDbQueryThrows() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenThrow(new SQLException("DB down"));

      JSONObject body = singleRecordBody("inv-7", "bp-7");
      NeoContext ctx = getCtxWithId("inv-7");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals("", data.getJSONObject(0).getString("bpEmail"));
    }
  }

  /**
   * Verifies that afterHandle annotates all list records with empty bpEmail and does not throw when the batch DB query fails.
   */
  @Test
  public void testAfterHandleListAnnotatesEmptyEmailWhenBatchQueryThrows() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenThrow(new SQLException("DB down"));

      JSONObject body = listBody(new String[][]{{"inv-8", "bp-8"}, {"inv-9", "bp-9"}});
      NeoContext ctx = listCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesInvoiceHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals("", data.getJSONObject(0).getString("bpEmail"));
      assertEquals("", data.getJSONObject(1).getString("bpEmail"));
    }
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
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("registerPayment").build();
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

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    when(mockClone.handle(ctx)).thenReturn(null);
    when(mockPayment.handle(ctx)).thenReturn(null);

    assertNull(handler.handle(ctx));
  }
}
