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

package com.etendoerp.go.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON-schema fragments an MCP tool's {@code inputSchema} is assembled from.
 *
 * <p>Split out of {@link ToolRegistry} (ETP-5184): these are pure, stateless shapes with no
 * knowledge of specs, RBAC or scopes, whereas everything left in the registry decides <i>which</i>
 * tools a session gets. The seam is that one boundary, not a line count — the registry reads as
 * "what to expose", this class as "how a JSON schema is spelled", and neither needs the other's
 * context.
 *
 * <p>Intended to be used via static import, so a schema declaration reads as the literal it
 * describes: {@code props.put("id", stringProp("The record id"))}.
 *
 * <p>{@link LinkedHashMap} throughout on purpose: the insertion order is what an agent sees when it
 * reads the tool list, so the argument order stays the one the author wrote.
 */
final class McpJsonSchema {

  private McpJsonSchema() {
    // utility class — no instances
  }

  /** The JSON-schema {@code required} keyword, kept as one constant so it is not re-typed. */
  static final String KEY_REQUIRED = "required";

  /** An {@code object} schema over {@code properties}; {@code required} is omitted when empty. */
  static Map<String, Object> buildObjectSchema(Map<String, Object> properties,
      List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", McpConstants.TYPE_OBJECT);
    schema.put(McpConstants.KEY_PROPERTIES, properties);
    if (required != null && !required.isEmpty()) {
      schema.put(KEY_REQUIRED, required);
    }
    return schema;
  }

  static Map<String, Object> stringProp(String description) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", McpConstants.TYPE_STRING);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    return prop;
  }

  static Map<String, Object> enumProp(String description, List<String> values) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", McpConstants.TYPE_STRING);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    prop.put("enum", values);
    return prop;
  }

  static Map<String, Object> numericProp(String type, String description) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", type);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    return prop;
  }

  /**
   * An {@code object} property. The nested properties are varargs only so a caller can declare a
   * free-form object by passing none.
   */
  @SafeVarargs
  static Map<String, Object> objectProp(String description, Map<String, Object>... nestedProps) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", McpConstants.TYPE_OBJECT);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    if (nestedProps.length > 0 && nestedProps[0] != null && !nestedProps[0].isEmpty()) {
      prop.put(McpConstants.KEY_PROPERTIES, nestedProps[0]);
    }
    return prop;
  }

  /** A JSON-schema array of strings, used for the IMP-2 {@code fields} projection whitelist. */
  static Map<String, Object> stringArrayProp(String description) {
    Map<String, Object> items = new LinkedHashMap<>();
    items.put("type", McpConstants.TYPE_STRING);
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "array");
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    prop.put("items", items);
    return prop;
  }
}
