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

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpConstants}.
 * Verifies constant values used across MCP tool generation.
 */
class McpConstantsTest {

  private static String getConstant(String fieldName) throws Exception {
    Field field = McpConstants.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (String) field.get(null);
  }

  @Test
  @DisplayName("Parameter constants have expected values")
  void parameterConstants() throws Exception {
    assertEquals("entity", getConstant("PARAM_ENTITY"));
    assertEquals("fields", getConstant("PARAM_FIELDS"));
    assertEquals("column", getConstant("PARAM_COLUMN"));
    assertEquals("parentId", getConstant("PARAM_PARENT_ID"));
  }

  @Test
  @DisplayName("Type constants have expected values")
  void typeConstants() throws Exception {
    assertEquals("string", getConstant("TYPE_STRING"));
    assertEquals("object", getConstant("TYPE_OBJECT"));
  }

  @Test
  @DisplayName("Schema key constants")
  void schemaKeyConstants() throws Exception {
    assertEquals("properties", getConstant("KEY_PROPERTIES"));
    assertEquals("description", getConstant("KEY_DESCRIPTION"));
  }

  @Test
  @DisplayName("Generate prefix constant")
  void generatePrefix() throws Exception {
    assertEquals("generate_", getConstant("GENERATE_PREFIX"));
  }

  @Test
  @DisplayName("Label constants are not null")
  void labelConstants() throws Exception {
    assertNotNull(getConstant("LABEL_SPEC_NAME"));
    assertNotNull(getConstant("LABEL_ENTITY_NAME"));
    assertNotNull(getConstant("LABEL_ENTITY_NAME_WITH_EXAMPLE"));
  }
}
