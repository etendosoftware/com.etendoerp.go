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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * Unit tests for BpEmailAnnotatorHandler.afterHandle().
 *
 * Tests are split into four groups:
 * <ul>
 *   <li><strong>Guard-condition</strong> – early returns without any DB access</li>
 *   <li><strong>Single-record GET</strong> – bpEmail annotated from C_BPartner.EM_Etgo_Email</li>
 *   <li><strong>List GET (batch)</strong> – batch query maps emails to each record</li>
 *   <li><strong>DB error resilience</strong> – graceful fallback to empty string on SQL failure</li>
 * </ul>
 */
public class BpEmailAnnotatorHandlerTest {

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * Builds a GET NeoContext for a header entity with the given record id.
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
   * Builds a list GET NeoContext (no recordId).
   */
  private static NeoContext listCtx() {
    return getCtxWithId(null);
  }

  /**
   * Wraps a single record carrying a businessPartner FK in the standard NEO response envelope.
   *
   * @param recordId   the record id
   * @param bPartnerId the businessPartner FK value to include
   */
  private static JSONObject singleRecordBody(String recordId, String bPartnerId) throws JSONException {
    JSONObject rec = new JSONObject()
        .put("id", recordId)
        .put("businessPartner", bPartnerId)
        .put("documentNo", "DOC-" + recordId);
    return new JSONObject().put("response", new JSONObject().put("data", new JSONArray().put(rec)));
  }

  /**
   * Builds a list response body from a 2D array of {@code {recordId, bPartnerId}} pairs.
   *
   * @param entries array where each element is {@code {recordId, bPartnerId}}
   */
  private static JSONObject listBody(String[][] entries) throws JSONException {
    JSONArray data = new JSONArray();
    for (String[] entry : entries) {
      data.put(new JSONObject().put("id", entry[0]).put("businessPartner", entry[1]).put("documentNo", "DOC-" + entry[0]));
    }
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  // ── handle() ──────────────────────────────────────────────────────────────

  /**
   * Verifies that handle() always returns null so the default service runs.
   */
  @Test
  public void testHandleAlwaysReturnsNull() {
    assertNull(new BpEmailAnnotatorHandler().handle(getCtxWithId("rec-1")));
  }

  // ── guard conditions (no DB needed) ───────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for POST requests without invoking DB logic.
   */
  @Test
  public void testAfterHandleReturnsNullForPostMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(new BpEmailAnnotatorHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null for PATCH requests without invoking DB logic.
   */
  @Test
  public void testAfterHandleReturnsNullForPatchMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new BpEmailAnnotatorHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenPreviousResultIsNull() {
    NeoContext ctx = getCtxWithId("rec-1");
    assertNull(new BpEmailAnnotatorHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtxWithId("rec-1");
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new BpEmailAnnotatorHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the response JSON lacks the "response" wrapper.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoResponseWrapper() throws JSONException {
    NeoContext ctx = getCtxWithId("rec-1");
    ctx.setPreviousResult(NeoResponse.ok(new JSONObject().put("something", "else")));
    assertNull(new BpEmailAnnotatorHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtxWithId("rec-1");
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new BpEmailAnnotatorHandler().afterHandle(ctx));
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

      JSONObject body = singleRecordBody("rec-1", "bp-1");
      NeoContext ctx = getCtxWithId("rec-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new BpEmailAnnotatorHandler().afterHandle(ctx);

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

      JSONObject body = singleRecordBody("rec-2", "bp-2");
      NeoContext ctx = getCtxWithId("rec-2");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new BpEmailAnnotatorHandler().afterHandle(ctx);

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
    JSONObject rec = new JSONObject().put("id", "rec-3").put("documentNo", "DOC-3");
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray().put(rec)));
    NeoContext ctx = getCtxWithId("rec-3");
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new BpEmailAnnotatorHandler().afterHandle(ctx);

    assertNotNull(result);
    JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals("", data.getJSONObject(0).getString("bpEmail"));
  }

  // ── list GET (batch) ───────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle maps bpEmail from the batch query to the correct record.
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

      JSONObject body = listBody(new String[][]{{"rec-1", "bp-1"}, {"rec-2", "bp-2"}});
      NeoContext ctx = listCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new BpEmailAnnotatorHandler().afterHandle(ctx);

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

      JSONObject body = listBody(new String[][]{{"rec-3", "bp-3"}, {"rec-4", "bp-4"}});
      NeoContext ctx = listCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new BpEmailAnnotatorHandler().afterHandle(ctx);

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

      JSONObject body = listBody(new String[][]{{"rec-5", "bp-5"}});
      NeoContext ctx = listCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new BpEmailAnnotatorHandler().afterHandle(ctx);

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
    data.put(new JSONObject().put("id", "rec-6").put("documentNo", "DOC-6"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = listCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new BpEmailAnnotatorHandler().afterHandle(ctx);

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

      JSONObject body = singleRecordBody("rec-7", "bp-7");
      NeoContext ctx = getCtxWithId("rec-7");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new BpEmailAnnotatorHandler().afterHandle(ctx);

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

      JSONObject body = listBody(new String[][]{{"rec-8", "bp-8"}, {"rec-9", "bp-9"}});
      NeoContext ctx = listCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new BpEmailAnnotatorHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals("", data.getJSONObject(0).getString("bpEmail"));
      assertEquals("", data.getJSONObject(1).getString("bpEmail"));
    }
  }
}
