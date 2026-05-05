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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;

/**
 * Unit tests for {@link PriceListHeaderHandler}.
 *
 * <p>Tests are split into three groups:
 * <ul>
 *   <li><strong>handle()</strong> – always returns null (pass-through pre-hook)</li>
 *   <li><strong>Guard conditions</strong> – early returns without any DB access</li>
 *   <li><strong>Version injection</strong> – single-record and batch GET paths that
 *       annotate {@code priceListVersion} on each record</li>
 * </ul>
 */
public class PriceListHeaderHandlerTest {

  // ── helpers ───────────────────────────────────────────────────────────────

  private static NeoContext getCtxWithId(String recordId) {
    return NeoContext.builder()
        .specName("price-list")
        .entityName("priceList")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  private static NeoContext listCtx() {
    return getCtxWithId(null);
  }

  private static JSONObject singleRecordBody(String id) throws JSONException {
    JSONArray data = new JSONArray().put(new JSONObject().put("id", id));
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static JSONObject listBody(String... ids) throws JSONException {
    JSONArray data = new JSONArray();
    for (String id : ids) {
      data.put(new JSONObject().put("id", id));
    }
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static NeoContext ctxWithPreviousResult(String recordId, JSONObject body) {
    NeoContext ctx = getCtxWithId(recordId);
    ctx.setPreviousResult(NeoResponse.ok(body));
    return ctx;
  }

  private static NeoContext listCtxWithPreviousResult(JSONObject body) {
    NeoContext ctx = listCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    return ctx;
  }

  @SuppressWarnings("unchecked")
  private static OBCriteria<PriceListVersion> stubVersionCriteria(OBDal dal,
      java.util.List<PriceListVersion> versions) {
    OBCriteria<PriceListVersion> crit = mock(OBCriteria.class);
    when(dal.createCriteria(PriceListVersion.class)).thenReturn(crit);
    when(crit.list()).thenReturn(versions);
    return crit;
  }

  // ── handle() ──────────────────────────────────────────────────────────────

  /**
   * Verifies that handle always returns null (pure pass-through pre-hook).
   */
  @Test
  public void testHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new PriceListHeaderHandler().handle(ctx));
  }

  // ── guard conditions ──────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for POST requests.
   */
  @Test
  public void testAfterHandleReturnsNullForPostMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null for PATCH requests.
   */
  @Test
  public void testAfterHandleReturnsNullForPatchMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = getCtxWithId("pl-1");
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result has a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtxWithId("pl-1");
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the response JSON lacks the "response" wrapper.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoResponseWrapper() throws JSONException {
    NeoContext ctx = getCtxWithId("pl-1");
    ctx.setPreviousResult(NeoResponse.ok(new JSONObject().put("other", "value")));
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtxWithId("pl-1");
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  // ── single-record GET ─────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle injects the version id into a single-record GET response.
   */
  @Test
  public void testAfterHandleSingleRecordInjectsVersionId() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList mockPl = mock(PriceList.class);
      when(dal.get(PriceList.class, "pl-1")).thenReturn(mockPl);
      PriceListVersion mockVersion = mock(PriceListVersion.class);
      when(mockVersion.getId()).thenReturn("v-1");
      stubVersionCriteria(dal, Collections.singletonList(mockVersion));

      JSONObject body = singleRecordBody("pl-1");
      NeoContext ctx = ctxWithPreviousResult("pl-1", body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      String injected = result.getBody()
          .getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getString("priceListVersion");
      assertEquals("v-1", injected);
    }
  }

  /**
   * Verifies that afterHandle injects an empty string for priceListVersion when the
   * price list record is not found in OBDal (e.g., stale id).
   */
  @Test
  public void testAfterHandleSingleRecordUsesEmptyStringWhenPriceListNotFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(PriceList.class, "pl-99")).thenReturn(null);

      JSONObject body = singleRecordBody("pl-99");
      NeoContext ctx = ctxWithPreviousResult("pl-99", body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals("", result.getBody()
          .getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getString("priceListVersion"));
    }
  }

  /**
   * Verifies that afterHandle injects an empty string when the price list has no version yet.
   */
  @Test
  public void testAfterHandleSingleRecordUsesEmptyStringWhenNoVersionExists() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList mockPl = mock(PriceList.class);
      when(dal.get(PriceList.class, "pl-2")).thenReturn(mockPl);
      stubVersionCriteria(dal, Collections.emptyList());

      JSONObject body = singleRecordBody("pl-2");
      NeoContext ctx = ctxWithPreviousResult("pl-2", body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals("", result.getBody()
          .getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getString("priceListVersion"));
    }
  }

  // ── list GET (batch) ───────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle injects the correct version id for each record in a list response.
   */
  @Test
  public void testAfterHandleBatchInjectsVersionIds() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList pl1 = mock(PriceList.class);
      when(pl1.getId()).thenReturn("pl-1");
      PriceListVersion v1 = mock(PriceListVersion.class);
      when(v1.getPriceList()).thenReturn(pl1);
      when(v1.getId()).thenReturn("v-1");

      PriceList pl2 = mock(PriceList.class);
      when(pl2.getId()).thenReturn("pl-2");
      PriceListVersion v2 = mock(PriceListVersion.class);
      when(v2.getPriceList()).thenReturn(pl2);
      when(v2.getId()).thenReturn("v-2");

      stubVersionCriteria(dal, Arrays.asList(v1, v2));

      JSONObject body = listBody("pl-1", "pl-2");
      NeoContext ctx = listCtxWithPreviousResult(body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals("v-1", data.getJSONObject(0).getString("priceListVersion"));
      assertEquals("v-2", data.getJSONObject(1).getString("priceListVersion"));
    }
  }

  /**
   * Verifies that afterHandle injects empty strings for records whose version is not found.
   */
  @Test
  public void testAfterHandleBatchUsesEmptyStringForMissingVersions() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubVersionCriteria(dal, Collections.emptyList());

      JSONObject body = listBody("pl-3", "pl-4");
      NeoContext ctx = listCtxWithPreviousResult(body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals("", data.getJSONObject(0).getString("priceListVersion"));
      assertEquals("", data.getJSONObject(1).getString("priceListVersion"));
    }
  }

  /**
   * Verifies that afterHandle returns null and does not propagate exceptions when
   * the underlying resolver throws a RuntimeException.
   */
  @Test
  public void testAfterHandleReturnsNullOnUnexpectedException() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(PriceList.class, "pl-err")).thenThrow(new RuntimeException("DB unavailable"));

      JSONObject body = singleRecordBody("pl-err");
      NeoContext ctx = ctxWithPreviousResult("pl-err", body);

      assertNull(new PriceListHeaderHandler().afterHandle(ctx));
    }
  }
}
