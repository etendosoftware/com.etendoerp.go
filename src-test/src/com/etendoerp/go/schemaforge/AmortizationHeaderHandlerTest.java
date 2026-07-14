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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link AmortizationHeaderHandler}.
 *
 * <p>Covers {@code handle()} pass-through, {@code afterHandle()} early-exit guards,
 * and all branches of the private {@code computeNameDefault()} method.
 */
public class AmortizationHeaderHandlerTest {

  private static final String ASSET_ID = "ASSET-001";
  private static final String ASSET_ENTITY_NAME = "FinancialMgmtAsset";
  private static final String COL_NAME = "Name";
  private static final String COL_START_DATE = "Amortizationstartdate";
  private static final String EXPECTED_FALLBACK = "Amortización";

  private final AmortizationHeaderHandler handler = new AmortizationHeaderHandler();

  // ─── handle() ────────────────────────────────────────────────────────────────

  /**
   * TC-1: handle() is a pre-hook pass-through — always returns null.
   */
  @Test
  public void testHandleReturnsNull() {
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.handle(ctx));
  }

  // ─── afterHandle() early-exit guards ─────────────────────────────────────────

  /**
   * TC-2: afterHandle() returns null for non-DEFAULTS endpoints (e.g. CRUD).
   */
  @Test
  public void testAfterHandleIgnoresNonDefaultsEndpoint() {
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * TC-3: afterHandle() returns null when getPreviousResult() is null.
   */
  @Test
  public void testAfterHandleIgnoresNullPreviousResult() {
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.DEFAULTS).build();
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * TC-4: afterHandle() returns null when the previous body is null.
   */
  @Test
  public void testAfterHandleIgnoresNullPreviousBody() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(new NeoResponse(200, null))
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * TC-5: CP-B6 no-overwrite guard — if defaults already has a non-null name, returns null.
   */
  @Test
  public void testAfterHandleDoesNotOverwriteExistingName() throws Exception {
    JSONObject body = new JSONObject();
    JSONObject defaults = new JSONObject();
    defaults.put("name", "Already Set");
    body.put("defaults", defaults);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull("Should not overwrite name already in defaults", handler.afterHandle(ctx));
  }

  // ─── afterHandle() + computeNameDefault() — fallback cases ──────────────────

  /**
   * TC-6: No assetId param → injects fallback name "Amortización".
   */
  @Test
  public void testAfterHandleFallbackWhenNoAssetId() throws Exception {
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class)) {
      RequestContext requestContext = mock(RequestContext.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      reqCtx.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getRequest()).thenReturn(request);
      when(request.getParameter("assetId")).thenReturn(null);

      NeoResponse response = handler.afterHandle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      assertEquals(EXPECTED_FALLBACK, response.getBody().getJSONObject("defaults").getString("name"));
    }
  }

  /**
   * TC-7: assetId present, asset found with name "Maquinaria" and date 2026-01-15
   *        → name = "Amortización - Maquinaria - 2026-01-15".
   */
  @Test
  public void testAfterHandleFullNameWithAssetNameAndDate() throws Exception {
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(body))
        .build();

    Date assetDate = new SimpleDateFormat("yyyy-MM-dd").parse("2026-01-15");

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
         MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {

      // RequestContext
      RequestContext requestContext = mock(RequestContext.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      reqCtx.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getRequest()).thenReturn(request);
      when(request.getParameter("assetId")).thenReturn(ASSET_ID);

      // OBDal + asset
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      BaseOBObject asset = mock(BaseOBObject.class);
      when(dal.get(ASSET_ENTITY_NAME, ASSET_ID)).thenReturn(asset);

      // Entity + Properties via asset.getEntity()
      Entity assetEntity = mock(Entity.class);
      when(asset.getEntity()).thenReturn(assetEntity);

      Property nameProp = mock(Property.class);
      when(nameProp.getName()).thenReturn("name");
      when(assetEntity.getPropertyByColumnName(COL_NAME, false)).thenReturn(nameProp);

      Property dateProp = mock(Property.class);
      when(dateProp.getName()).thenReturn("amortizationstartdate");
      when(assetEntity.getPropertyByColumnName(COL_START_DATE, false)).thenReturn(dateProp);

      // Asset property values
      when(asset.get("name")).thenReturn("Maquinaria");
      when(asset.get("amortizationstartdate")).thenReturn(assetDate);

      NeoResponse response = handler.afterHandle(ctx);

      assertNotNull(response);
      String name = response.getBody().getJSONObject("defaults").getString("name");
      assertEquals("Amortización - Maquinaria - 2026-01-15", name);
    }
  }

  /**
   * TC-8: assetId present but asset not found (OBDal returns null) → fallback.
   */
  @Test
  public void testAfterHandleFallbackWhenAssetNotFound() throws Exception {
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
         MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {

      RequestContext requestContext = mock(RequestContext.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      reqCtx.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getRequest()).thenReturn(request);
      when(request.getParameter("assetId")).thenReturn(ASSET_ID);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ASSET_ENTITY_NAME, ASSET_ID)).thenReturn(null);

      NeoResponse response = handler.afterHandle(ctx);

      assertNotNull(response);
      assertEquals(EXPECTED_FALLBACK,
          response.getBody().getJSONObject("defaults").getString("name"));
    }
  }

  /**
   * TC-9: asset found, name present, date null → "Amortización - Maquinaria" (no date suffix).
   */
  @Test
  public void testAfterHandleNameWithoutDate() throws Exception {
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
         MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {

      RequestContext requestContext = mock(RequestContext.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      reqCtx.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getRequest()).thenReturn(request);
      when(request.getParameter("assetId")).thenReturn(ASSET_ID);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      BaseOBObject asset = mock(BaseOBObject.class);
      when(dal.get(ASSET_ENTITY_NAME, ASSET_ID)).thenReturn(asset);

      Entity assetEntity = mock(Entity.class);
      when(asset.getEntity()).thenReturn(assetEntity);

      Property nameProp = mock(Property.class);
      when(nameProp.getName()).thenReturn("name");
      when(assetEntity.getPropertyByColumnName(COL_NAME, false)).thenReturn(nameProp);

      Property dateProp = mock(Property.class);
      when(dateProp.getName()).thenReturn("amortizationstartdate");
      when(assetEntity.getPropertyByColumnName(COL_START_DATE, false)).thenReturn(dateProp);

      when(asset.get("name")).thenReturn("Maquinaria");
      when(asset.get("amortizationstartdate")).thenReturn(null);

      NeoResponse response = handler.afterHandle(ctx);

      assertNotNull(response);
      assertEquals("Amortización - Maquinaria",
          response.getBody().getJSONObject("defaults").getString("name"));
    }
  }

  /**
   * TC-10: asset found but name is null → fallback.
   */
  @Test
  public void testAfterHandleFallbackWhenAssetNameNull() throws Exception {
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
         MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {

      RequestContext requestContext = mock(RequestContext.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      reqCtx.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getRequest()).thenReturn(request);
      when(request.getParameter("assetId")).thenReturn(ASSET_ID);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      BaseOBObject asset = mock(BaseOBObject.class);
      when(dal.get(ASSET_ENTITY_NAME, ASSET_ID)).thenReturn(asset);

      Entity assetEntity = mock(Entity.class);
      when(asset.getEntity()).thenReturn(assetEntity);

      Property nameProp = mock(Property.class);
      when(nameProp.getName()).thenReturn("name");
      when(assetEntity.getPropertyByColumnName(COL_NAME, false)).thenReturn(nameProp);

      Property dateProp = mock(Property.class);
      when(dateProp.getName()).thenReturn("amortizationstartdate");
      when(assetEntity.getPropertyByColumnName(COL_START_DATE, false)).thenReturn(dateProp);

      when(asset.get("name")).thenReturn(null);
      when(asset.get("amortizationstartdate")).thenReturn(null);

      NeoResponse response = handler.afterHandle(ctx);

      assertNotNull(response);
      assertEquals(EXPECTED_FALLBACK,
          response.getBody().getJSONObject("defaults").getString("name"));
    }
  }

  /**
   * TC-10b: asset found but name is empty string → fallback.
   */
  @Test
  public void testAfterHandleFallbackWhenAssetNameEmpty() throws Exception {
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
         MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {

      RequestContext requestContext = mock(RequestContext.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      reqCtx.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getRequest()).thenReturn(request);
      when(request.getParameter("assetId")).thenReturn(ASSET_ID);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      BaseOBObject asset = mock(BaseOBObject.class);
      when(dal.get(ASSET_ENTITY_NAME, ASSET_ID)).thenReturn(asset);

      Entity assetEntity = mock(Entity.class);
      when(asset.getEntity()).thenReturn(assetEntity);

      Property nameProp = mock(Property.class);
      when(nameProp.getName()).thenReturn("name");
      when(assetEntity.getPropertyByColumnName(COL_NAME, false)).thenReturn(nameProp);

      Property dateProp = mock(Property.class);
      when(dateProp.getName()).thenReturn("amortizationstartdate");
      when(assetEntity.getPropertyByColumnName(COL_START_DATE, false)).thenReturn(dateProp);

      when(asset.get("name")).thenReturn("");
      when(asset.get("amortizationstartdate")).thenReturn(null);

      NeoResponse response = handler.afterHandle(ctx);

      assertNotNull(response);
      assertEquals(EXPECTED_FALLBACK,
          response.getBody().getJSONObject("defaults").getString("name"));
    }
  }

  /**
   * TC-11: RequestContext.get() throws → computeNameDefault() returns fallback without crashing.
   */
  @Test
  public void testAfterHandleFallbackWhenRequestContextThrows() throws Exception {
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class)) {
      reqCtx.when(RequestContext::get).thenThrow(new RuntimeException("no request context"));

      NeoResponse response = handler.afterHandle(ctx);

      assertNotNull(response);
      assertEquals(EXPECTED_FALLBACK,
          response.getBody().getJSONObject("defaults").getString("name"));
    }
  }

  /**
   * TC-12: body has no "defaults" key — handler creates it and injects the name.
   */
  @Test
  public void testAfterHandleCreatesDefaultsObjectWhenAbsent() throws Exception {
    JSONObject body = new JSONObject();
    // body has no "defaults" key at all
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class)) {
      RequestContext requestContext = mock(RequestContext.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      reqCtx.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getRequest()).thenReturn(request);
      when(request.getParameter("assetId")).thenReturn(null);

      NeoResponse response = handler.afterHandle(ctx);

      assertNotNull(response);
      JSONObject responseBody = response.getBody();
      assertTrue("defaults key should be created", responseBody.has("defaults"));
      assertNotNull(responseBody.getJSONObject("defaults").getString("name"));
    }
  }

  // ─── @Named qualifier ────────────────────────────────────────────────────────

  @Test
  public void testHandlerIsRegisteredWithExpectedQualifier() {
    javax.inject.Named named = AmortizationHeaderHandler.class.getAnnotation(javax.inject.Named.class);
    assertTrue(named != null && "amortizationHeaderHandler".equals(named.value()));
  }

  // ─── Posted list-filter rewrite ──────────────────────────────────────────────

  /** value:true (JS boolean) → operator equals, value "Y". */
  @Test
  public void testRewritePostedTrueBecomesEqualsY() throws Exception {
    String criteria = "[{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":true}]";
    JSONArray out = new JSONArray(AmortizationHeaderHandler.rewritePostedInCriteria(criteria));
    JSONObject e = out.getJSONObject(0);
    assertEquals("posted", e.getString("fieldName"));
    assertEquals("equals", e.getString("operator"));
    assertEquals("Y", e.getString("value"));
  }

  /** value:false → operator notEqual, value "Y". */
  @Test
  public void testRewritePostedFalseBecomesNotEqualY() throws Exception {
    String criteria = "[{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":false}]";
    JSONArray out = new JSONArray(AmortizationHeaderHandler.rewritePostedInCriteria(criteria));
    JSONObject e = out.getJSONObject(0);
    assertEquals("notEqual", e.getString("operator"));
    assertEquals("Y", e.getString("value"));
  }

  /** String "true"/"false" values are handled the same as booleans. */
  @Test
  public void testRewritePostedStringTrueFalse() throws Exception {
    JSONObject t = new JSONArray(AmortizationHeaderHandler.rewritePostedInCriteria(
        "[{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":\"true\"}]")).getJSONObject(0);
    assertEquals("equals", t.getString("operator"));
    assertEquals("Y", t.getString("value"));

    JSONObject f = new JSONArray(AmortizationHeaderHandler.rewritePostedInCriteria(
        "[{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":\"false\"}]")).getJSONObject(0);
    assertEquals("notEqual", f.getString("operator"));
    assertEquals("Y", f.getString("value"));
  }

  /** Status codes "Y"/"N" are handled: Y → equals, N → notEqual. */
  @Test
  public void testRewritePostedStatusCodesYN() throws Exception {
    JSONObject y = new JSONArray(AmortizationHeaderHandler.rewritePostedInCriteria(
        "[{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":\"Y\"}]")).getJSONObject(0);
    assertEquals("equals", y.getString("operator"));

    JSONObject n = new JSONArray(AmortizationHeaderHandler.rewritePostedInCriteria(
        "[{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":\"N\"}]")).getJSONObject(0);
    assertEquals("notEqual", n.getString("operator"));
    assertEquals("Y", n.getString("value"));
  }

  /** The "Posted" AD column name (capitalized) is also recognized. */
  @Test
  public void testRewriteRecognizesPostedColumnName() throws Exception {
    JSONObject e = new JSONArray(AmortizationHeaderHandler.rewritePostedInCriteria(
        "[{\"fieldName\":\"Posted\",\"operator\":\"equals\",\"value\":true}]")).getJSONObject(0);
    assertEquals("equals", e.getString("operator"));
    assertEquals("Y", e.getString("value"));
  }

  /** No posted filter → criteria is returned semantically unchanged (other filters preserved). */
  @Test
  public void testRewriteLeavesNonPostedCriteriaUnchanged() throws Exception {
    String criteria = "[{\"fieldName\":\"name\",\"operator\":\"iContains\",\"value\":\"jan\"}]";
    JSONArray out = new JSONArray(AmortizationHeaderHandler.rewritePostedInCriteria(criteria));
    JSONObject e = out.getJSONObject(0);
    assertEquals("name", e.getString("fieldName"));
    assertEquals("iContains", e.getString("operator"));
    assertEquals("jan", e.getString("value"));
  }

  /** A posted filter is rewritten while sibling filters are preserved untouched. */
  @Test
  public void testRewritePreservesOtherFiltersAlongsidePosted() throws Exception {
    String criteria = "[{\"fieldName\":\"name\",\"operator\":\"iContains\",\"value\":\"jan\"},"
        + "{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":false}]";
    JSONArray out = new JSONArray(AmortizationHeaderHandler.rewritePostedInCriteria(criteria));

    JSONObject name = out.getJSONObject(0);
    assertEquals("name", name.getString("fieldName"));
    assertEquals("iContains", name.getString("operator"));
    assertEquals("jan", name.getString("value"));

    JSONObject posted = out.getJSONObject(1);
    assertEquals("notEqual", posted.getString("operator"));
    assertEquals("Y", posted.getString("value"));
  }

  /** Posted entries nested inside an AdvancedCriteria wrapper are rewritten recursively. */
  @Test
  public void testRewritePostedInsideAdvancedCriteria() throws Exception {
    String criteria = "{\"_constructor\":\"AdvancedCriteria\",\"operator\":\"and\",\"criteria\":["
        + "{\"fieldName\":\"name\",\"operator\":\"iContains\",\"value\":\"jan\"},"
        + "{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":true}]}";
    JSONObject out = new JSONObject(AmortizationHeaderHandler.rewritePostedInCriteria(criteria));
    JSONArray inner = out.getJSONArray("criteria");
    JSONObject posted = inner.getJSONObject(1);
    assertEquals("equals", posted.getString("operator"));
    assertEquals("Y", posted.getString("value"));
    // sibling preserved
    assertEquals("iContains", inner.getJSONObject(0).getString("operator"));
  }

  /** rewritePostedFilter mutates the NeoContext queryParams map in place. */
  @Test
  public void testRewritePostedFilterMutatesQueryParams() {
    Map<String, String> params = new HashMap<>();
    params.put("criteria", "[{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":false}]");
    params.put("_sortBy", "name");
    NeoContext ctx = NeoContext.builder().queryParams(params).build();

    AmortizationHeaderHandler.rewritePostedFilter(ctx);

    assertTrue(params.get("criteria").contains("notEqual"));
    assertTrue(params.get("criteria").contains("\"value\":\"Y\""));
    // paging/sort param left intact
    assertEquals("name", params.get("_sortBy"));
  }

  /** rewritePostedFilter is a no-op when there is no criteria param. */
  @Test
  public void testRewritePostedFilterNoCriteriaParamIsNoOp() {
    Map<String, String> params = new HashMap<>();
    params.put("_startRow", "0");
    NeoContext ctx = NeoContext.builder().queryParams(params).build();

    AmortizationHeaderHandler.rewritePostedFilter(ctx);

    assertFalse(params.containsKey("criteria"));
    assertEquals("0", params.get("_startRow"));
  }

  /** rewritePostedFilter leaves malformed criteria untouched and never throws. */
  @Test
  public void testRewritePostedFilterMalformedCriteriaLeftUnchanged() {
    Map<String, String> params = new HashMap<>();
    params.put("criteria", "not-json{");
    NeoContext ctx = NeoContext.builder().queryParams(params).build();

    AmortizationHeaderHandler.rewritePostedFilter(ctx);

    assertEquals("not-json{", params.get("criteria"));
  }

  /** handle() applies the posted rewrite and still returns null so default CRUD proceeds. */
  @Test
  public void testHandleAppliesPostedRewriteAndReturnsNull() {
    Map<String, String> params = new HashMap<>();
    params.put("criteria", "[{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":true}]");
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CRUD).queryParams(params).build();

    assertNull(handler.handle(ctx));
    assertTrue(params.get("criteria").contains("\"value\":\"Y\""));
    assertTrue(params.get("criteria").contains("equals"));
  }

  @Test
  public void handleReturnsPostingResponseWhenServiceHandlesAction() {
    com.etendoerp.go.schemaforge.handlers.DocumentPostingService service =
        mock(com.etendoerp.go.schemaforge.handlers.DocumentPostingService.class);
    NeoContext context = mock(NeoContext.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    when(service.handleAction(context)).thenReturn(sentinel);

    AmortizationHeaderHandler postingHandler = new AmortizationHeaderHandler();
    postingHandler.setPostingService(service);

    assertSame(sentinel, postingHandler.handle(context));
  }
}
