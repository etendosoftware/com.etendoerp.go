package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

  // ── wrapAsTextContent ──────────────────────────────────────────────────

  @Test
  public void testWrapAsTextContentStructure() throws Exception {
    JSONObject result = McpToolRouter.wrapAsTextContent("hello world");

    assertNotNull(result);
    assertTrue(result.has("content"));
    assertFalse(result.has("isError"));

    JSONArray content = result.getJSONArray("content");
    assertEquals(1, content.length());

    JSONObject block = content.getJSONObject(0);
    assertEquals("text", block.getString("type"));
    assertEquals("hello world", block.getString("text"));
  }

  @Test
  public void testWrapAsTextContentWithJson() throws Exception {
    String jsonText = "{\"records\": 5}";
    JSONObject result = McpToolRouter.wrapAsTextContent(jsonText);

    JSONArray content = result.getJSONArray("content");
    assertEquals(jsonText, content.getJSONObject(0).getString("text"));
  }

  // ── wrapAsErrorContent ─────────────────────────────────────────────────

  @Test
  public void testWrapAsErrorContentStructure() throws Exception {
    JSONObject result = McpToolRouter.wrapAsErrorContent("Something failed");

    assertNotNull(result);
    assertTrue(result.has("content"));
    assertTrue(result.getBoolean("isError"));

    JSONArray content = result.getJSONArray("content");
    assertEquals(1, content.length());

    JSONObject block = content.getJSONObject(0);
    assertEquals("text", block.getString("type"));
    assertEquals("Something failed", block.getString("text"));
  }

  // ── ToolRegistry.resolveSpecName ───────────────────────────────────────

  @Test
  public void testResolveSpecNameForCrudTool() throws Exception {
    JSONObject args = new JSONObject();
    args.put("spec", "sales-order");
    args.put("entity", "header");

    assertEquals("sales-order", ToolRegistry.resolveSpecName("neo_list", args));
    assertEquals("sales-order", ToolRegistry.resolveSpecName("neo_get", args));
    assertEquals("sales-order", ToolRegistry.resolveSpecName("neo_create", args));
    assertEquals("sales-order", ToolRegistry.resolveSpecName("neo_update", args));
    assertEquals("sales-order", ToolRegistry.resolveSpecName("neo_delete", args));
    assertEquals("sales-order", ToolRegistry.resolveSpecName("neo_selectors", args));
    assertEquals("sales-order", ToolRegistry.resolveSpecName("neo_defaults", args));
  }

  @Test
  public void testResolveSpecNameForDiscoverTool() {
    // neo_discover is a CRUD tool but doesn't need a spec
    assertNull(ToolRegistry.resolveSpecName("neo_discover", null));
  }

  @Test
  public void testResolveSpecNameForProcessTool() {
    // Process tool name is snake_case of spec name
    assertEquals("complete-order", ToolRegistry.resolveSpecName("complete_order", null));
    assertEquals("validate-invoice", ToolRegistry.resolveSpecName("validate_invoice", null));
  }

  @Test
  public void testResolveSpecNameForReportTool() {
    // Report tool: strip "generate_" and convert to kebab
    assertEquals("invoice-report",
        ToolRegistry.resolveSpecName("generate_invoice_report", null));
    assertEquals("sales-summary",
        ToolRegistry.resolveSpecName("generate_sales_summary", null));
  }

  @Test
  public void testResolveSpecNameForCrudToolWithoutSpec() {
    // CRUD tool with null arguments returns null
    assertNull(ToolRegistry.resolveSpecName("neo_list", null));
  }

  // ── ToolRegistry.isCrudTool ────────────────────────────────────────────

  @Test
  public void testIsCrudToolTrue() {
    assertTrue(ToolRegistry.isCrudTool("neo_discover"));
    assertTrue(ToolRegistry.isCrudTool("neo_list"));
    assertTrue(ToolRegistry.isCrudTool("neo_get"));
    assertTrue(ToolRegistry.isCrudTool("neo_create"));
    assertTrue(ToolRegistry.isCrudTool("neo_update"));
    assertTrue(ToolRegistry.isCrudTool("neo_delete"));
    assertTrue(ToolRegistry.isCrudTool("neo_selectors"));
    assertTrue(ToolRegistry.isCrudTool("neo_defaults"));
  }

  @Test
  public void testIsCrudToolFalse() {
    assertFalse(ToolRegistry.isCrudTool("complete_order"));
    assertFalse(ToolRegistry.isCrudTool("generate_invoice_report"));
    assertFalse(ToolRegistry.isCrudTool("neo_other"));
    assertFalse(ToolRegistry.isCrudTool(""));
  }

  // ── ToolRegistry.snakeToKebab ─────────────────────────────────────────

  @Test
  public void testSnakeToKebab() {
    assertEquals("complete-order", ToolRegistry.snakeToKebab("complete_order"));
    assertEquals("sales-order-lines", ToolRegistry.snakeToKebab("sales_order_lines"));
    assertEquals("invoices", ToolRegistry.snakeToKebab("invoices"));
  }
}
