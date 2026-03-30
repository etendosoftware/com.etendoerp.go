package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link ToolRegistry} naming helpers and {@link McpToolDefinition}.
 * <p>
 * Full integration tests (RBAC filtering, DAL queries) require OBBaseTest and run
 * against a live Etendo instance. These unit tests cover the pure-logic parts.
 */
public class ToolRegistryTest {

  @Test
  public void testKebabToSnakeSimple() {
    assertEquals("complete_order", ToolRegistry.kebabToSnake("complete-order"));
  }

  @Test
  public void testKebabToSnakeMultipleDashes() {
    assertEquals("sales_order_lines", ToolRegistry.kebabToSnake("sales-order-lines"));
  }

  @Test
  public void testKebabToSnakeNoDashes() {
    assertEquals("invoices", ToolRegistry.kebabToSnake("invoices"));
  }

  @Test
  public void testKebabToSnakeSingleChar() {
    assertEquals("a_b", ToolRegistry.kebabToSnake("a-b"));
  }

  @Test
  public void testMcpToolDefinitionGetters() {
    Map<String, Object> schema = Map.of("type", "object");
    McpToolDefinition tool = new McpToolDefinition("neo_list", "List records", schema);

    assertEquals("neo_list", tool.getName());
    assertEquals("List records", tool.getDescription());
    assertEquals(schema, tool.getInputSchema());
  }

  @Test
  public void testMcpToolDefinitionNullSchema() {
    McpToolDefinition tool = new McpToolDefinition("neo_discover", "Discover specs", null);

    assertNotNull(tool.getInputSchema());
    assertTrue(tool.getInputSchema().isEmpty());
  }

  @Test
  public void testMcpToolDefinitionToString() {
    McpToolDefinition tool = new McpToolDefinition("neo_get", "Get record", Collections.emptyMap());
    String str = tool.toString();
    assertTrue(str.contains("neo_get"));
    assertTrue(str.contains("Get record"));
  }
}
