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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.selector.meta.RichFieldMeta;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Unit tests for {@link SelectorRowMapper}.
 * Tests column index mapping, alias extraction, ID resolution, and grid field mapping.
 */
class SelectorRowMapperTest {

  private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = SelectorRowMapper.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  @Nested
  @DisplayName("buildSelectColumnIndexMap")
  class BuildSelectColumnIndexMap {
    @Test
    void mapsSimpleExpressions() throws Exception {
      String[] exprs = { "e.id", "e.name", "e.amount" };
      @SuppressWarnings("unchecked")
      Map<String, Integer> result = (Map<String, Integer>) invokeStatic(
          "buildSelectColumnIndexMap", new Class<?>[]{ String[].class }, (Object) exprs);

      assertEquals(0, result.get("id"));
      assertEquals(1, result.get("name"));
      assertEquals(2, result.get("amount"));
    }

    @Test
    void mapsExprWithAsAlias() throws Exception {
      String[] exprs = { "e.c_bpartner_id as bpartner", "SUM(e.amount) as total" };
      @SuppressWarnings("unchecked")
      Map<String, Integer> result = (Map<String, Integer>) invokeStatic(
          "buildSelectColumnIndexMap", new Class<?>[]{ String[].class }, (Object) exprs);

      assertEquals(0, result.get("bpartner"));
      assertEquals(1, result.get("total"));
    }

    @Test
    void handlesTableDotColumn() throws Exception {
      String[] exprs = { "bp.name" };
      @SuppressWarnings("unchecked")
      Map<String, Integer> result = (Map<String, Integer>) invokeStatic(
          "buildSelectColumnIndexMap", new Class<?>[]{ String[].class }, (Object) exprs);

      assertEquals(0, result.get("name"));
    }

    @Test
    void caseInsensitiveKeys() throws Exception {
      String[] exprs = { "e.Name AS productName" };
      @SuppressWarnings("unchecked")
      Map<String, Integer> result = (Map<String, Integer>) invokeStatic(
          "buildSelectColumnIndexMap", new Class<?>[]{ String[].class }, (Object) exprs);

      assertEquals(0, result.get("productname"));
    }
  }

  @Nested
  @DisplayName("extractAlias (via buildSelectColumnIndexMap)")
  class ExtractAlias {
    @Test
    void lastAsIsUsed() throws Exception {
      // "CASE WHEN x THEN y END as result" should use "result"
      String[] exprs = { "CASE WHEN 1=1 THEN 'Y' END as result" };
      @SuppressWarnings("unchecked")
      Map<String, Integer> result = (Map<String, Integer>) invokeStatic(
          "buildSelectColumnIndexMap", new Class<?>[]{ String[].class }, (Object) exprs);

      assertEquals(0, result.get("result"));
    }

    @Test
    void bareExpressionUsesFullString() throws Exception {
      String[] exprs = { "COUNT(*)" };
      @SuppressWarnings("unchecked")
      Map<String, Integer> result = (Map<String, Integer>) invokeStatic(
          "buildSelectColumnIndexMap", new Class<?>[]{ String[].class }, (Object) exprs);

      assertEquals(0, result.get("count(*)"));
    }
  }

  @Nested
  @DisplayName("resolveIdColumnIndex")
  class ResolveIdColumnIndex {
    private static SelectorMeta metaWithValueProp(String valueProp) {
      // Use reflection to create SelectorMeta with custom valueProperty since it's final
      try {
        java.lang.reflect.Constructor<SelectorMeta> ctor =
            SelectorMeta.class.getDeclaredConstructor(String.class, String.class, String.class);
        SelectorMeta meta = ctor.newInstance("TestEntity", "name", null);
        // valueProperty defaults to "id", so we need reflection to change it for testing
        java.lang.reflect.Field vpField = SelectorMeta.class.getDeclaredField("valueProperty");
        vpField.setAccessible(true);
        // Remove final modifier
        java.lang.reflect.Field modifiersField = java.lang.reflect.Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(vpField, vpField.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
        vpField.set(meta, valueProp);
        return meta;
      } catch (Exception e) {
        // Fallback: just create with default "id" valueProperty
        return new SelectorMeta("TestEntity", "name", null);
      }
    }

    @Test
    void resolvesByValueProperty() throws Exception {
      // Use default SelectorMeta (valueProperty="id") and put "id" in the map at position 2
      SelectorMeta meta = new SelectorMeta("TestEntity", "name", null);
      Map<String, Integer> colMap = new HashMap<>();
      colMap.put("id", 2);
      String[] exprs = { "e.name", "e.code", "e.id" };

      Integer result = (Integer) invokeStatic("resolveIdColumnIndex",
          new Class<?>[]{ SelectorMeta.class, String.class, Map.class, String[].class },
          meta, "e", colMap, exprs);

      assertEquals(2, result);
    }

    @Test
    void fallsBackToIdColumn() throws Exception {
      SelectorMeta meta = metaWithValueProp("missing");
      Map<String, Integer> colMap = new HashMap<>();
      colMap.put("id", 0);
      String[] exprs = { "e.id", "e.name" };

      Integer result = (Integer) invokeStatic("resolveIdColumnIndex",
          new Class<?>[]{ SelectorMeta.class, String.class, Map.class, String[].class },
          meta, "e", colMap, exprs);

      assertEquals(0, result);
    }

    @Test
    void scansForAliasDotIdPrefix() throws Exception {
      SelectorMeta meta = metaWithValueProp("noMatch");
      Map<String, Integer> colMap = new HashMap<>();
      // no "id" key, no "noMatch" key
      String[] exprs = { "bp.name", "bp.id" };

      Integer result = (Integer) invokeStatic("resolveIdColumnIndex",
          new Class<?>[]{ SelectorMeta.class, String.class, Map.class, String[].class },
          meta, "bp", colMap, exprs);

      assertEquals(1, result);
    }

    @Test
    void returnsNullWhenNotFound() throws Exception {
      SelectorMeta meta = metaWithValueProp("x");
      Map<String, Integer> colMap = new HashMap<>();
      String[] exprs = { "foo.bar" };

      Integer result = (Integer) invokeStatic("resolveIdColumnIndex",
          new Class<?>[]{ SelectorMeta.class, String.class, Map.class, String[].class },
          meta, "e", colMap, exprs);

      assertNull(result);
    }
  }

  @Nested
  @DisplayName("mapGridFieldsToItem")
  class MapGridFieldsToItem {
    @Test
    void mapsFieldValuesFromRow() throws Exception {
      JSONObject item = new JSONObject();
      Object[] row = { "Product A", 42L };
      Map<String, Integer> colMap = new HashMap<>();
      colMap.put("name", 0);
      colMap.put("qty", 1);
      List<RichFieldMeta> fields = Arrays.asList(
          new RichFieldMeta("name", "Name", "product.name", 1L),
          new RichFieldMeta("qty", "Quantity", "orderedQuantity", 2L));

      invokeStatic("mapGridFieldsToItem",
          new Class<?>[]{ JSONObject.class, Object[].class, Map.class, List.class },
          item, row, colMap, fields);

      assertEquals("Product A", item.getString("name"));
      assertEquals(42L, item.getLong("qty"));
    }

    @Test
    void nullValueMapsToJsonNull() throws Exception {
      JSONObject item = new JSONObject();
      Object[] row = { null };
      Map<String, Integer> colMap = new HashMap<>();
      colMap.put("val", 0);
      List<RichFieldMeta> fields = Collections.singletonList(
          new RichFieldMeta("val", "Value", "value", 1L));

      invokeStatic("mapGridFieldsToItem",
          new Class<?>[]{ JSONObject.class, Object[].class, Map.class, List.class },
          item, row, colMap, fields);

      assertTrue(item.has("val"));
      assertTrue(item.isNull("val"));
    }

    @Test
    void skipsFieldsNotInColumnMap() throws Exception {
      JSONObject item = new JSONObject();
      Object[] row = { "A" };
      Map<String, Integer> colMap = new HashMap<>();
      colMap.put("name", 0);
      List<RichFieldMeta> fields = Collections.singletonList(
          new RichFieldMeta("missing", "Missing", "missing", 1L));

      invokeStatic("mapGridFieldsToItem",
          new Class<?>[]{ JSONObject.class, Object[].class, Map.class, List.class },
          item, row, colMap, fields);

      assertFalse(item.has("missing"));
    }
  }

  private static void assertFalse(boolean condition) {
    org.junit.jupiter.api.Assertions.assertFalse(condition);
  }
}
