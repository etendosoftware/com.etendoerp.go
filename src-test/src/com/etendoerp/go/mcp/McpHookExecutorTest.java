/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link McpHookExecutor}.
 * <p>
 * Tests cover the pure-logic methods that require no DAL session:
 * {@code neoResponseToMcpResult}, {@code runPreHook}, {@code runPostHook}, and
 * the early-exit paths of {@code resolveEntityHandler} (blank/null qualifier).
 * The CDI lookup path of {@code resolveEntityHandler} is covered by integration tests.
 * {@code buildActionHookContext} is covered here with a statically mocked
 * {@code OBContext}; the CRUD/DEFAULTS context builders remain integration-covered.
 */
public class McpHookExecutorTest {

  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_IS_ERROR = "isError";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_TEXT = "text";

  // ── neoResponseToMcpResult ────────────────────────────────────────────

  @Test
  public void testNeoResponseToMcpResultSuccessStatusReturnsTextContentWithoutIsError()
      throws Exception {
    JSONObject body = new JSONObject();
    body.put("id", "abc123");
    NeoResponse response = NeoResponse.ok(body);

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertNotNull(result);
    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(1, content.length());
    assertEquals("text", content.getJSONObject(0).getString(FIELD_TYPE));
    assertFalse("isError must not be set on 200", result.has(FIELD_IS_ERROR));
  }

  @Test
  public void testNeoResponseToMcpResultCreatedStatusReturnsTextContent() throws Exception {
    JSONObject body = new JSONObject();
    body.put("id", "new-record");
    NeoResponse response = NeoResponse.created(body);

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertNotNull(result);
    assertFalse("201 is not an error", result.has(FIELD_IS_ERROR));
    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertTrue(content.getJSONObject(0).getString(FIELD_TEXT).contains("new-record"));
  }

  @Test
  public void testNeoResponseToMcpResult400StatusSetsIsError() throws Exception {
    NeoResponse response = NeoResponse.error(400, "Name is required");

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertNotNull(result);
    assertTrue("400 must set isError", result.getBoolean(FIELD_IS_ERROR));
  }

  @Test
  public void testNeoResponseToMcpResult409StatusSetsIsError() throws Exception {
    NeoResponse response = NeoResponse.error(409, "Duplicate priority");

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
  }

  @Test
  public void testNeoResponseToMcpResult500StatusSetsIsError() throws Exception {
    NeoResponse response = NeoResponse.error(500, "Unexpected error");

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
  }

  @Test
  public void testNeoResponseToMcpResultNullBodyReturnsEmptyJsonText() throws Exception {
    NeoResponse response = NeoResponse.noContent(); // 204 + null body

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertNotNull(result);
    assertFalse(result.has(FIELD_IS_ERROR));
    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals("{}", content.getJSONObject(0).getString(FIELD_TEXT));
  }

  @Test
  public void testNeoResponseToMcpResultErrorWithNullBodyIncludesStatusInText() throws Exception {
    NeoResponse response = new NeoResponse(503, null);

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    String text = result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT);
    assertTrue("Text should mention status 503", text.contains("503"));
  }

  /**
   * The fourth error funnel, asserted at the funnel rather than only at the helper
   * (ETP-4793 / IMP-5 clause (iv)).
   *
   * <p>The tests above pass on the pre-fix code too: they check {@code isError} and that the text
   * mentions the status, which the verbatim body already did. That is why the defect survived —
   * nothing here ever looked at the <em>shape</em> an agent has to parse. This one does, and it is
   * the reason the assertion is on {@code error} being a code string: that key held a nested object
   * before, so an agent branching on it got neither a match nor an exception, just silence.</p>
   */
  @Test
  public void testNeoResponseToMcpResultErrorIsNormalizedToFlatEnvelope() throws Exception {
    NeoResponse response = NeoResponse.error(422,
        "No accounting schema with currency is configured for organization 6184");

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    JSONObject envelope = new JSONObject(
        result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT));
    assertEquals("validation_error", envelope.getString("error"));
    assertEquals(422, envelope.getInt("status"));
    assertTrue(envelope.getString("detail").startsWith("No accounting schema"));
  }

  // ── runPreHook ────────────────────────────────────────────────────────

  @Test
  public void testRunPreHookNullHandlerReturnsNull() throws Exception {
    NeoContext ctx = mock(NeoContext.class);

    JSONObject result = McpHookExecutor.runPreHook(null, ctx);

    assertNull(result);
  }

  @Test
  public void testRunPreHookHandlerReturnsNullReturnsNull() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    NeoContext ctx = mock(NeoContext.class);
    when(handler.handle(ctx)).thenReturn(null);

    JSONObject result = McpHookExecutor.runPreHook(handler, ctx);

    assertNull(result);
    verify(handler).handle(ctx);
  }

  @Test
  public void testRunPreHookHandlerReturns200ReturnsMcpTextContent() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    NeoContext ctx = mock(NeoContext.class);
    JSONObject body = new JSONObject();
    body.put("ok", true);
    when(handler.handle(ctx)).thenReturn(NeoResponse.ok(body));

    JSONObject result = McpHookExecutor.runPreHook(handler, ctx);

    assertNotNull(result);
    assertFalse(result.has(FIELD_IS_ERROR));
  }

  @Test
  public void testRunPreHookHandlerReturns400ReturnsMcpErrorContent() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    NeoContext ctx = mock(NeoContext.class);
    when(handler.handle(ctx)).thenReturn(NeoResponse.error(400, "Invalid"));

    JSONObject result = McpHookExecutor.runPreHook(handler, ctx);

    assertNotNull(result);
    assertTrue(result.getBoolean(FIELD_IS_ERROR));
  }

  // ── runPostHook ───────────────────────────────────────────────────────

  @Test
  public void testRunPostHookNullHandlerReturnsNull() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    JSONObject responseJson = new JSONObject();

    JSONObject result = McpHookExecutor.runPostHook(null, ctx, responseJson);

    assertNull(result);
  }

  @Test
  public void testRunPostHookHandlerReturnsNullReturnsNull() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    NeoContext ctx = mock(NeoContext.class);
    JSONObject responseJson = new JSONObject();
    when(handler.afterHandle(ctx)).thenReturn(null);

    JSONObject result = McpHookExecutor.runPostHook(handler, ctx, responseJson);

    assertNull(result);
    verify(ctx).setPreviousResult(any(NeoResponse.class));
    verify(handler).afterHandle(ctx);
  }

  @Test
  public void testRunPostHookHandlerReplacesResponseReturnsMcpResult() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    NeoContext ctx = mock(NeoContext.class);
    JSONObject responseJson = new JSONObject();
    JSONObject overrideBody = new JSONObject();
    overrideBody.put("replaced", true);
    when(handler.afterHandle(ctx)).thenReturn(NeoResponse.ok(overrideBody));

    JSONObject result = McpHookExecutor.runPostHook(handler, ctx, responseJson);

    assertNotNull(result);
    assertFalse(result.has(FIELD_IS_ERROR));
    assertTrue(result.getJSONArray(FIELD_CONTENT).getJSONObject(0)
        .getString(FIELD_TEXT).contains("replaced"));
  }

  // ── resolveEntityHandler (early-exit paths, no CDI) ──────────────────

  @Test
  public void testResolveEntityHandlerNullQualifierReturnsNull() {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getJavaQualifier()).thenReturn(null);

    NeoHandler result = McpHookExecutor.resolveEntityHandler(sfEntity);

    assertNull(result);
  }

  @Test
  public void testResolveEntityHandlerBlankQualifierReturnsNull() {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getJavaQualifier()).thenReturn("   ");

    NeoHandler result = McpHookExecutor.resolveEntityHandler(sfEntity);

    assertNull(result);
  }

  @Test
  public void testResolveEntityHandlerEmptyQualifierReturnsNull() {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getJavaQualifier()).thenReturn("");

    NeoHandler result = McpHookExecutor.resolveEntityHandler(sfEntity);

    assertNull(result);
  }

  // ── buildActionHookContext (ETP-4285) ─────────────────────────────────

  @Test
  public void testBuildActionHookContextSetsActionEndpointTypeAndFieldName() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    Tab adTab = mock(Tab.class);
    JSONObject params = new JSONObject();
    params.put("docAction", "CO");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      NeoContext ctx = McpHookExecutor.buildActionHookContext("sales-order", "header",
          "REC-1", "documentAction", params, adTab, sfEntity);

      // endpointType + fieldName are exactly what handlers branch on, e.g.
      // AbstractOrderHeaderHandler.isActionDocumentActionComplete.
      assertEquals(NeoEndpointType.ACTION, ctx.getEndpointType());
      assertEquals("documentAction", ctx.getFieldName());
      assertEquals("POST", ctx.getHttpMethod());
      assertEquals("REC-1", ctx.getRecordId());
      assertEquals("sales-order", ctx.getSpecName());
      assertEquals("header", ctx.getEntityName());
      assertEquals("CO", ctx.getRequestBody().getString("docAction"));
      assertEquals(adTab, ctx.getAdTab());
      assertEquals(sfEntity, ctx.getSfEntity());
    }
  }

  // ── buildActionHookContext with method + query params (ETP-5447) ──────

  private static final String SPEC_FA = "financial-account";
  private static final String ENTITY_ACCOUNT = "account";
  private static final String ACC_ID = "ACC-1";
  private static final String LIST_STATEMENTS = "listStatements";

  @Test
  public void testBuildActionHookContextNineArgCarriesMethodAndQueryParams() throws Exception {
    JSONObject params = new JSONObject();
    params.put("limit", "5");
    Map<String, String> queryParams = Map.of("limit", "5");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      NeoContext ctx = McpHookExecutor.buildActionHookContext(SPEC_FA, ENTITY_ACCOUNT, ACC_ID,
          LIST_STATEMENTS, "GET", params, queryParams, null, null);

      assertEquals("GET", ctx.getHttpMethod());
      assertEquals(NeoEndpointType.ACTION, ctx.getEndpointType());
      assertEquals(LIST_STATEMENTS, ctx.getFieldName());
      assertEquals(SPEC_FA, ctx.getSpecName());
      assertEquals(ENTITY_ACCOUNT, ctx.getEntityName());
      assertEquals(ACC_ID, ctx.getRecordId());
      assertSame(params, ctx.getRequestBody());
      assertEquals(queryParams, ctx.getQueryParams());
      assertTrue(ctx.isMcpOrigin());
      assertNull(ctx.getAdTab());
      assertNull(ctx.getSfEntity());
    }
  }

  @Test
  public void testBuildActionHookContextNineArgTurnsNullQueryParamsIntoAnEmptyMap()
      throws Exception {
    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      NeoContext ctx = McpHookExecutor.buildActionHookContext(SPEC_FA, ENTITY_ACCOUNT, ACC_ID,
          "createStatement", "POST", new JSONObject(), null, null, null);

      assertEquals("POST", ctx.getHttpMethod());
      assertNotNull(ctx.getQueryParams());
      assertTrue(ctx.getQueryParams().isEmpty());
    }
  }

  @Test
  public void testBuildActionHookContextSevenArgDefaultsToPostWithEmptyQueryParams()
      throws Exception {
    JSONObject params = new JSONObject();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      NeoContext ctx = McpHookExecutor.buildActionHookContext(SPEC_FA, ENTITY_ACCOUNT, ACC_ID,
          LIST_STATEMENTS, params, null, null);

      assertEquals("POST", ctx.getHttpMethod());
      assertNotNull(ctx.getQueryParams());
      assertTrue(ctx.getQueryParams().isEmpty());
      assertEquals(NeoEndpointType.ACTION, ctx.getEndpointType());
      assertEquals(LIST_STATEMENTS, ctx.getFieldName());
      assertSame(params, ctx.getRequestBody());
      assertTrue(ctx.isMcpOrigin());
    }
  }
}
