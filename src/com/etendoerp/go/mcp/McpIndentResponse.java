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

import static com.etendoerp.go.mcp.McpJsonSchema.booleanProp;

import java.util.LinkedHashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;

/**
 * Both ends of the {@code _indentResponse} presentation flag (IMP-53): {@link ToolRegistry}
 * declares it on every published tool through {@link #declare}, and {@link McpToolRouter#route}
 * strips it from the call arguments through {@link #take} before anything else reads them. Kept in
 * one class so the advertised flag and the one the router consumes cannot drift apart.
 */
final class McpIndentResponse {

  private McpIndentResponse() {
  }

  /**
   * Remove {@code _indentResponse} from the arguments and return its value (IMP-53). Anything but
   * {@code true} (or the string {@code "true"}) means the default, compact output.
   *
   * @param arguments the tool arguments (may be null); the flag is removed from them
   * @return whether this call asked for indented JSON
   */
  static boolean take(JSONObject arguments) {
    if (arguments == null) {
      return false;
    }
    Object flag = arguments.remove(McpConstants.PARAM_INDENT_RESPONSE);
    return "true".equalsIgnoreCase(String.valueOf(flag));
  }

  /**
   * The same tool with the optional {@code _indentResponse} boolean added to its input schema
   * (IMP-53). Declared once, for every published tool rather than in each builder, so a new tool
   * cannot miss it. The schema maps are copied: a builder may hand back an immutable one.
   * {@link #take} strips the flag before routing, so it is never a business argument.
   *
   * @param tool the tool as its builder produced it
   * @return a copy whose input schema also declares {@code _indentResponse}
   */
  static McpToolDefinition declare(McpToolDefinition tool) {
    Map<String, Object> schema = new LinkedHashMap<>(tool.getInputSchema());
    schema.putIfAbsent("type", McpConstants.TYPE_OBJECT);
    Map<String, Object> props = new LinkedHashMap<>();
    Object existing = schema.get(McpConstants.KEY_PROPERTIES);
    if (existing instanceof Map) {
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) existing).entrySet()) {
        props.put(String.valueOf(entry.getKey()), entry.getValue());
      }
    }
    props.put(McpConstants.PARAM_INDENT_RESPONSE, booleanProp(McpConstants.DESC_INDENT_RESPONSE));
    schema.put(McpConstants.KEY_PROPERTIES, props);
    return new McpToolDefinition(tool.getName(), tool.getDescription(), schema, tool.getTitle());
  }
}
