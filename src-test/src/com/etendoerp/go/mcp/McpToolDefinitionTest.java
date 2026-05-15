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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpToolDefinition}.
 */
class McpToolDefinitionTest {

  @Nested
  @DisplayName("Constructor and getters")
  class ConstructorAndGetters {
    @Test
    void storesNameAndDescription() {
      McpToolDefinition def = new McpToolDefinition("neo_list", "List records", null);
      assertEquals("neo_list", def.getName());
      assertEquals("List records", def.getDescription());
    }

    @Test
    void nullSchemaDefaultsToEmptyMap() {
      McpToolDefinition def = new McpToolDefinition("tool", "desc", null);
      assertNotNull(def.getInputSchema());
      assertTrue(def.getInputSchema().isEmpty());
    }

    @Test
    void providedSchemaIsStored() {
      Map<String, Object> schema = new HashMap<>();
      schema.put("type", "object");
      McpToolDefinition def = new McpToolDefinition("tool", "desc", schema);
      assertEquals("object", def.getInputSchema().get("type"));
    }
  }

  @Nested
  @DisplayName("toString")
  class ToStringTests {
    @Test
    void containsNameAndDescription() {
      McpToolDefinition def = new McpToolDefinition("my_tool", "My description", null);
      String str = def.toString();
      assertTrue(str.contains("my_tool"));
      assertTrue(str.contains("My description"));
    }
  }
}
