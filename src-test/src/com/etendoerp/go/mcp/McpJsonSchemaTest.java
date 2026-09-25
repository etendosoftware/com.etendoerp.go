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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for the handler-declared parameter renderers of {@link McpJsonSchema} (ETP-5447):
 * {@code declaredParamProp}, {@code declaredParamsSchema} and {@code toJsonObject}. They are shared
 * by the {@code generate_*} report tools and the handler actions of
 * {@code neo_schema({view:"actions"})}, so one type must render the same way on both surfaces.
 */
@ExtendWith(MockitoExtension.class)
class McpJsonSchemaTest {

  private static final String KEY_TYPE = "type";
  private static final String KEY_DESCRIPTION = "description";
  private static final String KEY_ITEMS = "items";
  private static final String KEY_ENUM = "enum";
  private static final String KEY_FORMAT = "format";
  private static final String KEY_PROPERTIES = "properties";
  private static final String KEY_REQUIRED = "required";
  private static final String TYPE_STRING = "string";
  private static final String TYPE_OBJECT = "object";
  private static final String TYPE_ARRAY = "array";
  private static final String DESCRIPTION = "What it means.";
  private static final String PARAM = "param";
  private static final String LINES = "lines";
  private static final String NOTES = "notes";

  @Test
  void testDeclaredParamPropRendersString() {
    Map<String, Object> prop = McpJsonSchema.declaredParamProp(
        NeoReportParam.optional(PARAM, NeoReportParam.TYPE_STRING, DESCRIPTION));

    assertEquals(TYPE_STRING, prop.get(KEY_TYPE));
    assertEquals(DESCRIPTION, prop.get(KEY_DESCRIPTION));
    assertEquals(2, prop.size());
  }

  @Test
  void testDeclaredParamPropRendersDateAsStringWithFormatAndShape() {
    Map<String, Object> prop = McpJsonSchema.declaredParamProp(
        NeoReportParam.required(PARAM, NeoReportParam.TYPE_DATE, DESCRIPTION));

    assertEquals(TYPE_STRING, prop.get(KEY_TYPE));
    assertEquals("date", prop.get(KEY_FORMAT));
    assertEquals(DESCRIPTION + " Format: yyyy-MM-dd.", prop.get(KEY_DESCRIPTION));
  }

  @Test
  void testDeclaredParamPropRendersInteger() {
    Map<String, Object> prop = McpJsonSchema.declaredParamProp(
        NeoReportParam.optional(PARAM, NeoReportParam.TYPE_INTEGER, DESCRIPTION));

    assertEquals("integer", prop.get(KEY_TYPE));
    assertEquals(DESCRIPTION, prop.get(KEY_DESCRIPTION));
  }

  @Test
  void testDeclaredParamPropRendersBoolean() {
    Map<String, Object> prop = McpJsonSchema.declaredParamProp(
        NeoReportParam.optional(PARAM, NeoReportParam.TYPE_BOOLEAN, DESCRIPTION));

    assertEquals("boolean", prop.get(KEY_TYPE));
    assertEquals(DESCRIPTION, prop.get(KEY_DESCRIPTION));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testDeclaredParamPropRendersArrayOfObjects() {
    Map<String, Object> prop = McpJsonSchema.declaredParamProp(
        NeoReportParam.required(LINES, NeoReportParam.TYPE_ARRAY, DESCRIPTION));

    assertEquals(TYPE_ARRAY, prop.get(KEY_TYPE));
    assertEquals(DESCRIPTION, prop.get(KEY_DESCRIPTION));
    Map<String, Object> items = (Map<String, Object>) prop.get(KEY_ITEMS);
    assertEquals(Map.of(KEY_TYPE, TYPE_OBJECT), items);
  }

  @Test
  void testDeclaredParamPropRendersObjectWithoutProperties() {
    Map<String, Object> prop = McpJsonSchema.declaredParamProp(
        NeoReportParam.optional(PARAM, NeoReportParam.TYPE_OBJECT, DESCRIPTION));

    assertEquals(TYPE_OBJECT, prop.get(KEY_TYPE));
    assertEquals(DESCRIPTION, prop.get(KEY_DESCRIPTION));
    assertFalse(prop.containsKey(KEY_PROPERTIES));
  }

  @Test
  void testDeclaredParamPropRendersOptionsAsEnum() {
    Map<String, Object> prop = McpJsonSchema.declaredParamProp(
        NeoReportParam.options(PARAM, DESCRIPTION, List.of("A", "B")));

    assertEquals(TYPE_STRING, prop.get(KEY_TYPE));
    assertEquals(List.of("A", "B"), prop.get(KEY_ENUM));
    assertEquals(DESCRIPTION, prop.get(KEY_DESCRIPTION));
  }

  @Test
  void testDeclaredParamPropFallsBackToStringForAnUnknownType() {
    Map<String, Object> prop = McpJsonSchema.declaredParamProp(
        NeoReportParam.optional(PARAM, "number", DESCRIPTION));

    assertEquals(TYPE_STRING, prop.get(KEY_TYPE));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testDeclaredParamsSchemaKeepsOrderAndListsRequired() {
    Map<String, Object> schema = McpJsonSchema.declaredParamsSchema(List.of(
        NeoReportParam.required("name", NeoReportParam.TYPE_STRING, DESCRIPTION),
        NeoReportParam.optional(NOTES, NeoReportParam.TYPE_STRING, DESCRIPTION),
        NeoReportParam.required(LINES, NeoReportParam.TYPE_ARRAY, DESCRIPTION)));

    assertEquals(TYPE_OBJECT, schema.get(KEY_TYPE));
    Map<String, Object> properties = (Map<String, Object>) schema.get(KEY_PROPERTIES);
    assertEquals(List.of("name", NOTES, LINES), List.copyOf(properties.keySet()));
    assertEquals(List.of("name", LINES), schema.get(KEY_REQUIRED));
    assertEquals(TYPE_ARRAY, ((Map<String, Object>) properties.get(LINES)).get(KEY_TYPE));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testDeclaredParamsSchemaOmitsRequiredWhenAllOptional() {
    Map<String, Object> schema = McpJsonSchema.declaredParamsSchema(List.of(
        NeoReportParam.optional(NOTES, NeoReportParam.TYPE_STRING, DESCRIPTION)));

    assertFalse(schema.containsKey(KEY_REQUIRED));
    assertEquals(1, ((Map<String, Object>) schema.get(KEY_PROPERTIES)).size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testDeclaredParamsSchemaForNoParametersStillCarriesEmptyProperties() {
    Map<String, Object> schema = McpJsonSchema.declaredParamsSchema(List.of());

    assertEquals(TYPE_OBJECT, schema.get(KEY_TYPE));
    assertTrue(schema.containsKey(KEY_PROPERTIES));
    assertTrue(((Map<String, Object>) schema.get(KEY_PROPERTIES)).isEmpty());
    assertFalse(schema.containsKey(KEY_REQUIRED));
  }

  @Test
  void testToJsonObjectConvertsNestedMapsAndLists() throws Exception {
    Map<String, Object> schema = McpJsonSchema.declaredParamsSchema(List.of(
        NeoReportParam.required(LINES, NeoReportParam.TYPE_ARRAY, DESCRIPTION),
        NeoReportParam.options("mode", DESCRIPTION, List.of("X", "Y"))));

    JSONObject json = McpJsonSchema.toJsonObject(schema);

    assertEquals(TYPE_OBJECT, json.getString(KEY_TYPE));
    JSONObject lines = json.getJSONObject(KEY_PROPERTIES).getJSONObject(LINES);
    assertEquals(TYPE_ARRAY, lines.getString(KEY_TYPE));
    assertEquals(TYPE_OBJECT, lines.getJSONObject(KEY_ITEMS).getString(KEY_TYPE));
    JSONArray modes = json.getJSONObject(KEY_PROPERTIES).getJSONObject("mode")
        .getJSONArray(KEY_ENUM);
    assertEquals(2, modes.length());
    assertEquals("X", modes.getString(0));
    JSONArray required = json.getJSONArray(KEY_REQUIRED);
    assertEquals(1, required.length());
    assertEquals(LINES, required.getString(0));
  }

  @Test
  void testToJsonObjectKeepsScalarValues() throws Exception {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("flag", true);
    map.put("count", 3);
    map.put("label", "x");

    JSONObject json = McpJsonSchema.toJsonObject(map);

    assertTrue(json.getBoolean("flag"));
    assertEquals(3, json.getInt("count"));
    assertEquals("x", json.getString("label"));
  }

  @Test
  void testToJsonObjectOfEmptyMapIsEmpty() throws Exception {
    assertEquals(0, McpJsonSchema.toJsonObject(new LinkedHashMap<>()).length());
  }
}
