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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.util.NeoReportParam;

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

  /** JSON Schema's array type, and the key that carries its element schema. */
  private static final String TYPE_ARRAY = "array";
  /** @see #TYPE_ARRAY */
  private static final String KEY_ITEMS = "items";

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

  /** A JSON-schema boolean property. */
  static Map<String, Object> booleanProp(String description) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "boolean");
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    return prop;
  }

  /** A JSON-schema array of objects, used for the {@code neo_feedback} verdict's nested lists. */
  static Map<String, Object> objectArrayProp(String description, Map<String, Object> itemProps,
      List<String> itemRequired) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", TYPE_ARRAY);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    prop.put(KEY_ITEMS, buildObjectSchema(itemProps, itemRequired));
    return prop;
  }

  /**
   * A JSON-schema array whose items are constrained to {@code values}.
   *
   * <p>The array counterpart of {@link #enumProp}: where a scalar parameter with a closed set of
   * legal values gets an {@code enum}, a list-valued one gets the same {@code enum} on its
   * {@code items}. IMP-41 — {@code neo_vector_search.targets} was a free string array, so the only
   * way to learn a legal key was to guess one and read the refusal. A model that can only pick from
   * the list cannot misspell the key at all.</p>
   *
   * @param description the parameter description
   * @param values      the legal item values; must not be empty, or the parameter is unsatisfiable
   * @return the array schema
   */
  static Map<String, Object> stringEnumArrayProp(String description, List<String> values) {
    Map<String, Object> items = new LinkedHashMap<>();
    items.put("type", McpConstants.TYPE_STRING);
    items.put("enum", values);
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", TYPE_ARRAY);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    prop.put(KEY_ITEMS, items);
    return prop;
  }

  /**
   * Render one handler-declared parameter as a JSON-schema property.
   *
   * <p>Shared by the {@code generate_*} report tools (ETP-4793 / IMP-19) and the handler actions
   * of {@code neo_schema({view:"actions"})} (ETP-5447), which declare their inputs with the same
   * {@link NeoReportParam} vocabulary — one renderer, so the two surfaces cannot spell a type
   * differently.</p>
   *
   * <p>{@code date} is carried as a string with the expected shape stated in the description:
   * JSON Schema's own {@code format:"date"} is an annotation most MCP clients do not enforce, and
   * IMP-16 traced silent corruption to date values whose shape was never written down where an
   * agent could read it. {@code array} and {@code object} are rendered without deeper typing; the
   * description carries their shape.</p>
   */
  static Map<String, Object> declaredParamProp(NeoReportParam param) {
    String description = param.getDescription();
    if (!param.getAllowedValues().isEmpty()) {
      return enumProp(description, param.getAllowedValues());
    }
    String type = param.getType();
    if (NeoReportParam.TYPE_DATE.equals(type)) {
      Map<String, Object> prop = stringProp(description + " Format: yyyy-MM-dd.");
      prop.put("format", "date");
      return prop;
    }
    if (NeoReportParam.TYPE_INTEGER.equals(type)) {
      return numericProp(NeoReportParam.TYPE_INTEGER, description);
    }
    if (NeoReportParam.TYPE_BOOLEAN.equals(type)) {
      return booleanProp(description);
    }
    if (NeoReportParam.TYPE_ARRAY.equals(type)) {
      Map<String, Object> items = new LinkedHashMap<>();
      items.put("type", McpConstants.TYPE_OBJECT);
      Map<String, Object> prop = new LinkedHashMap<>();
      prop.put("type", TYPE_ARRAY);
      prop.put(McpConstants.KEY_DESCRIPTION, description);
      prop.put(KEY_ITEMS, items);
      return prop;
    }
    if (NeoReportParam.TYPE_OBJECT.equals(type)) {
      return objectProp(description);
    }
    return stringProp(description);
  }

  /**
   * The {@code object} schema of a handler-declared parameter list: {@code properties} (always
   * present, empty when the list is — "takes no inputs" is a statement, not "any object") plus
   * {@code required} when any parameter is mandatory.
   *
   * @param params the declared parameters, in declaration order
   * @return the object schema
   */
  static Map<String, Object> declaredParamsSchema(List<NeoReportParam> params) {
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    for (NeoReportParam param : params) {
      properties.put(param.getName(), declaredParamProp(param));
      if (param.isRequired()) {
        required.add(param.getName());
      }
    }
    return buildObjectSchema(properties, required);
  }

  /**
   * Convert a schema fragment built by this class into a Jettison {@link JSONObject}, recursing
   * into nested maps and lists, for the responses (rather than tool definitions) that carry one.
   *
   * @param map the schema fragment
   * @return the equivalent JSON object
   * @throws JSONException if a value cannot be put
   */
  static JSONObject toJsonObject(Map<String, Object> map) throws JSONException {
    JSONObject json = new JSONObject();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      json.put(entry.getKey(), toJsonValue(entry.getValue()));
    }
    return json;
  }

  @SuppressWarnings("unchecked")
  private static Object toJsonValue(Object value) throws JSONException {
    if (value instanceof Map) {
      return toJsonObject((Map<String, Object>) value);
    }
    if (value instanceof List) {
      JSONArray array = new JSONArray();
      for (Object item : (List<Object>) value) {
        array.put(toJsonValue(item));
      }
      return array;
    }
    return value;
  }

  /** A JSON-schema array of strings, used for the IMP-2 {@code fields} projection whitelist. */
  static Map<String, Object> stringArrayProp(String description) {
    Map<String, Object> items = new LinkedHashMap<>();
    items.put("type", McpConstants.TYPE_STRING);
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", TYPE_ARRAY);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    prop.put(KEY_ITEMS, items);
    return prop;
  }
}
