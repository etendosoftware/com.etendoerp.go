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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link McpHookExecutor}.
 * <p>
 * Tests cover the pure-logic methods that require no DAL session:
 * {@code neoResponseToMcpResult}, {@code runPreHook}, {@code runPostHook}, and
 * the early-exit paths of {@code resolveEntityHandler} (blank/null qualifier).
 * The CDI lookup path of {@code resolveEntityHandler} and {@code buildHookContext}
 * (which calls {@code OBContext.getOBContext()}) are covered by integration tests.
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
}
