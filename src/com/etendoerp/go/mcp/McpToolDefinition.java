package com.etendoerp.go.mcp;

import java.util.Collections;
import java.util.Map;

/**
 * Represents an MCP tool definition for registration with the MCP server.
 * <p>
 * Stores the tool name, human-readable description, and a JSON Schema
 * (as a Map) describing the expected input parameters. This POJO decouples
 * the tool generation logic from the MCP SDK wire format — the McpServer
 * adapter converts these into SDK-native Tool objects at registration time.
 */
public class McpToolDefinition {

  private final String name;
  private final String description;
  private final Map<String, Object> inputSchema;

  /**
   * Create a new tool definition.
   *
   * @param name        unique tool name (snake_case, e.g. "neo_list" or "complete_order")
   * @param description human-readable description of what the tool does
   * @param inputSchema JSON Schema for the tool's input parameters, represented as a Map
   */
  public McpToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    this.name = name;
    this.description = description;
    this.inputSchema = inputSchema != null ? inputSchema : Collections.emptyMap();
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public Map<String, Object> getInputSchema() {
    return inputSchema;
  }

  @Override
  public String toString() {
    return "McpToolDefinition{name='" + name + "', description='" + description + "'}";
  }
}
