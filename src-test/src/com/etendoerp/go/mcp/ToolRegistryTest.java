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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link ToolRegistry} naming helpers and {@link McpToolDefinition}.
 * <p>
 * Full integration tests (RBAC filtering, DAL queries) require OBBaseTest and run
 * against a live Etendo instance. These unit tests cover the pure-logic parts.
 */
public class ToolRegistryTest {

  private static final String INVOICES = "invoices";

  /** Tests that a single-dash kebab string is converted to snake_case correctly. */
  @Test
  public void testKebabToSnakeSimple() {
    assertEquals("complete_order", ToolRegistry.kebabToSnake("complete-order"));
  }

  /** Tests that a multi-dash kebab string is converted to snake_case correctly. */
  @Test
  public void testKebabToSnakeMultipleDashes() {
    assertEquals("sales_order_lines", ToolRegistry.kebabToSnake("sales-order-lines"));
  }

  /** Tests that a string with no dashes passes through kebabToSnake unchanged. */
  @Test
  public void testKebabToSnakeNoDashes() {
    assertEquals(INVOICES, ToolRegistry.kebabToSnake(INVOICES));
  }

  /** Tests that a single-character segment kebab string is converted to snake_case correctly. */
  @Test
  public void testKebabToSnakeSingleChar() {
    assertEquals("a_b", ToolRegistry.kebabToSnake("a-b"));
  }

  /** Tests that McpToolDefinition getters return the values provided at construction. */
  @Test
  public void testMcpToolDefinitionGetters() {
    Map<String, Object> schema = Map.of("type", "object");
    McpToolDefinition tool = new McpToolDefinition("neo_list", "List records", schema);

    assertEquals("neo_list", tool.getName());
    assertEquals("List records", tool.getDescription());
    assertEquals(schema, tool.getInputSchema());
  }

  /** Tests that a null input schema is normalized to an empty map by McpToolDefinition. */
  @Test
  public void testMcpToolDefinitionNullSchema() {
    McpToolDefinition tool = new McpToolDefinition("neo_discover", "Discover specs", null);

    assertNotNull(tool.getInputSchema());
    assertTrue(tool.getInputSchema().isEmpty());
  }

  /** Tests that a single-underscore snake_case string is converted to kebab-case correctly. */
  @Test
  public void testSnakeToKebabSimple() {
    assertEquals("complete-order", ToolRegistry.snakeToKebab("complete_order"));
  }

  /** Tests that a multi-underscore snake_case string is converted to kebab-case correctly. */
  @Test
  public void testSnakeToKebabMultipleUnderscores() {
    assertEquals("sales-order-lines", ToolRegistry.snakeToKebab("sales_order_lines"));
  }

  /** Tests that a string with no underscores passes through snakeToKebab unchanged. */
  @Test
  public void testSnakeToKebabNoUnderscores() {
    assertEquals(INVOICES, ToolRegistry.snakeToKebab(INVOICES));
  }

  /** Tests that McpToolDefinition.toString() includes the tool name and description. */
  @Test
  public void testMcpToolDefinitionToString() {
    McpToolDefinition tool = new McpToolDefinition("neo_get", "Get record", Collections.emptyMap());
    String str = tool.toString();
    assertTrue(str.contains("neo_get"));
    assertTrue(str.contains("Get record"));
  }

  /** Tests that neo_batch is recognised as a CRUD tool (so spec resolution is skipped). */
  @Test
  public void testNeoBatchIsCrudTool() {
    assertTrue(ToolRegistry.isCrudTool("neo_batch"));
  }

  /**
   * Tests the schema produced by buildBatchTool: required top-level 'operations' array,
   * with each item requiring id/spec/entity and supporting optional parentRef/body.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testBuildBatchToolSchema() {
    McpToolDefinition tool = new ToolRegistry().buildBatchTool();

    assertEquals("neo_batch", tool.getName());
    assertNotNull(tool.getDescription());
    // This assertion has now been wrong twice, in opposite directions, which is why it checks the
    // caller-visible consequence rather than a keyword. It first required the description to
    // "mention atomic transaction" while the endpoint was not atomic at all (IMP-23): every op was
    // committed by core's write path, so a failure left the earlier ones durable. It was then
    // inverted to require "not atomic" — correct only until option B made the batch genuinely
    // atomic. What must hold in every version is that the description tells the agent what to do
    // after a failure: retry the whole batch, unless 'atomic' says records survived.
    String description = tool.getDescription().toLowerCase();
    assertTrue("Description must state the batch is atomic", description.contains("atomically"));
    assertTrue("Description must flag the process-commit exception, or agents will trust 'atomic' "
        + "blindly in the one case where it is false", description.contains("'atomic':false"));
    assertTrue("Description must point the caller at 'persisted' to recover surviving records",
        description.contains("persisted"));

    Map<String, Object> schema = tool.getInputSchema();
    assertEquals("object", schema.get("type"));
    assertEquals(List.of("operations"), schema.get("required"));

    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    Map<String, Object> opsSchema = (Map<String, Object>) props.get("operations");
    assertEquals("array", opsSchema.get("type"));

    Map<String, Object> item = (Map<String, Object>) opsSchema.get("items");
    assertEquals("object", item.get("type"));
    assertEquals(List.of("id", "spec", "entity"), item.get("required"));

    Map<String, Object> itemProps = (Map<String, Object>) item.get("properties");
    assertNotNull(itemProps.get("id"));
    assertNotNull(itemProps.get("spec"));
    assertNotNull(itemProps.get("entity"));
    assertNotNull(itemProps.get("parentRef"));
    assertNotNull(itemProps.get("body"));
  }
}
