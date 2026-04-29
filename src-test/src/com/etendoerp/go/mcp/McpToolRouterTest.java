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

import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link McpToolRouter} static helpers and MCP content formatting.
 * <p>
 * CRUD handler tests require OBBaseTest with a live Etendo instance.
 * These tests cover the pure-logic, no-DAL parts.
 */
public class McpToolRouterTest {

  private static final String FIELD_CONTENT = "content";
  private static final String SPEC_SALES_ORDER = "sales-order";
  private static final String TOOL_NEO_LIST = "neo_list";
  private static final String TOOL_COMPLETE_ORDER = "complete_order";

  // ── wrapAsTextContent ──────────────────────────────────────────────────

  /** Tests that wrapAsTextContent produces a valid MCP text content structure. */
  @Test
  public void testWrapAsTextContentStructure() throws Exception {
    JSONObject result = McpToolRouter.wrapAsTextContent("hello world");

    assertNotNull(result);
    assertTrue(result.has(FIELD_CONTENT));
    assertFalse(result.has("isError"));

    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(1, content.length());

    JSONObject block = content.getJSONObject(0);
    assertEquals("text", block.getString("type"));
    assertEquals("hello world", block.getString("text"));
  }

  /** Tests that wrapAsTextContent preserves embedded JSON text verbatim. */
  @Test
  public void testWrapAsTextContentWithJson() throws Exception {
    String jsonText = "{\"records\": 5}";
    JSONObject result = McpToolRouter.wrapAsTextContent(jsonText);

    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(jsonText, content.getJSONObject(0).getString("text"));
  }

  // ── wrapAsErrorContent ─────────────────────────────────────────────────

  /** Tests that wrapAsErrorContent sets isError flag and wraps the message. */
  @Test
  public void testWrapAsErrorContentStructure() throws Exception {
    JSONObject result = McpToolRouter.wrapAsErrorContent("Something failed");

    assertNotNull(result);
    assertTrue(result.has(FIELD_CONTENT));
    assertTrue(result.getBoolean("isError"));

    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(1, content.length());

    JSONObject block = content.getJSONObject(0);
    assertEquals("text", block.getString("type"));
    assertEquals("Something failed", block.getString("text"));
  }

  // ── ToolRegistry.resolveSpecName ───────────────────────────────────────

  /** Tests that resolveSpecName returns the spec argument for all CRUD tool names. */
  @Test
  public void testResolveSpecNameForCrudTool() throws Exception {
    JSONObject args = new JSONObject();
    args.put("spec", SPEC_SALES_ORDER);
    args.put("entity", "header");

    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName(TOOL_NEO_LIST, args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName("neo_get", args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName("neo_create", args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName("neo_update", args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName("neo_delete", args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName("neo_selectors", args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName("neo_defaults", args));
  }

  /** Tests that resolveSpecName returns null for the discover tool which needs no spec. */
  @Test
  public void testResolveSpecNameForDiscoverTool() {
    assertNull(ToolRegistry.resolveSpecName("neo_discover", null));
  }

  /** Tests that resolveSpecName converts snake_case process tool names to kebab-case. */
  @Test
  public void testResolveSpecNameForProcessTool() {
    assertEquals("complete-order", ToolRegistry.resolveSpecName(TOOL_COMPLETE_ORDER, null));
    assertEquals("validate-invoice", ToolRegistry.resolveSpecName("validate_invoice", null));
  }

  /** Tests that resolveSpecName strips the generate_ prefix for report tools. */
  @Test
  public void testResolveSpecNameForReportTool() {
    assertEquals("invoice-report",
        ToolRegistry.resolveSpecName("generate_invoice_report", null));
    assertEquals("sales-summary",
        ToolRegistry.resolveSpecName("generate_sales_summary", null));
  }

  /** Tests that resolveSpecName returns null for CRUD tools with missing arguments. */
  @Test
  public void testResolveSpecNameForCrudToolWithoutSpec() {
    assertNull(ToolRegistry.resolveSpecName(TOOL_NEO_LIST, null));
  }

  // ── ToolRegistry.isCrudTool ────────────────────────────────────────────

  /** Tests that isCrudTool returns true for all known CRUD tool names. */
  @Test
  public void testIsCrudToolTrue() {
    assertTrue(ToolRegistry.isCrudTool("neo_discover"));
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_LIST));
    assertTrue(ToolRegistry.isCrudTool("neo_get"));
    assertTrue(ToolRegistry.isCrudTool("neo_create"));
    assertTrue(ToolRegistry.isCrudTool("neo_update"));
    assertTrue(ToolRegistry.isCrudTool("neo_delete"));
    assertTrue(ToolRegistry.isCrudTool("neo_selectors"));
    assertTrue(ToolRegistry.isCrudTool("neo_defaults"));
  }

  /** Tests that isCrudTool returns false for process, report, and unknown tool names. */
  @Test
  public void testIsCrudToolFalse() {
    assertFalse(ToolRegistry.isCrudTool(TOOL_COMPLETE_ORDER));
    assertFalse(ToolRegistry.isCrudTool("generate_invoice_report"));
    assertFalse(ToolRegistry.isCrudTool("neo_other"));
    assertFalse(ToolRegistry.isCrudTool(""));
  }

  // ── ToolRegistry.snakeToKebab ─────────────────────────────────────────

  /** Tests that snakeToKebab correctly converts underscores to hyphens. */
  @Test
  public void testSnakeToKebab() {
    assertEquals("complete-order", ToolRegistry.snakeToKebab(TOOL_COMPLETE_ORDER));
    assertEquals("sales-order-lines", ToolRegistry.snakeToKebab("sales_order_lines"));
    assertEquals("invoices", ToolRegistry.snakeToKebab("invoices"));
  }

  // ── McpAuthorizationService ────────────────────────────────────────────

  /** Tests that write tools require write scope at execution time. */
  @Test(expected = SecurityException.class)
  public void testAuthorizeToolCallRejectsCreateWithoutWriteScope() {
    McpAuthorizationService.authorizeToolCall("neo_create", Set.of("neo:read"));
  }

  /** Tests that write scope allows write tools at execution time. */
  @Test
  public void testAuthorizeToolCallAllowsCreateWithWriteScope() {
    McpAuthorizationService.authorizeToolCall("neo_create", Set.of("neo:write"));
  }

  /** Tests that report tools require report scope at execution time. */
  @Test(expected = SecurityException.class)
  public void testAuthorizeToolCallRejectsReportWithoutReportScope() {
    McpAuthorizationService.authorizeToolCall("generate_invoice_report", Set.of("neo:read"));
  }

  /** Tests that the wildcard scope allows every tool type at execution time. */
  @Test
  public void testAuthorizeToolCallAllowsWildcardScope() {
    McpAuthorizationService.authorizeToolCall("neo_create", Set.of("neo:*"));
    McpAuthorizationService.authorizeToolCall("complete_order", Set.of("neo:*"));
    McpAuthorizationService.authorizeToolCall("generate_invoice_report", Set.of("neo:*"));
  }
}
