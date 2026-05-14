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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.selector.meta.RichFieldMeta;

/**
 * Unit tests for {@link SelectorResponseSupport}.
 */
class SelectorResponseSupportTest {

  private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = SelectorResponseSupport.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  @Nested
  @DisplayName("buildSelectorResponse")
  class BuildSelectorResponse {
    @Test
    void buildsCorrectEnvelope() throws Exception {
      JSONArray items = new JSONArray();
      items.put(new JSONObject().put("id", "1"));
      JSONArray columns = new JSONArray();

      NeoResponse response = (NeoResponse) invokeStatic("buildSelectorResponse",
          new Class<?>[]{ JSONArray.class, JSONArray.class, int.class, int.class, int.class },
          items, columns, 50, 10, 0);

      assertEquals(200, response.getHttpStatus());
      JSONObject body = response.getBody();
      assertEquals(50, body.getInt("totalCount"));
      assertEquals(1, body.getJSONArray("items").length());
    }

    @Test
    void hasMoreTrueWhenMoreRecords() throws Exception {
      NeoResponse response = (NeoResponse) invokeStatic("buildSelectorResponse",
          new Class<?>[]{ JSONArray.class, JSONArray.class, int.class, int.class, int.class },
          new JSONArray(), new JSONArray(), 100, 10, 0);

      assertTrue(response.getBody().getBoolean("hasMore"));
    }

    @Test
    void hasMoreFalseWhenAtEnd() throws Exception {
      NeoResponse response = (NeoResponse) invokeStatic("buildSelectorResponse",
          new Class<?>[]{ JSONArray.class, JSONArray.class, int.class, int.class, int.class },
          new JSONArray(), new JSONArray(), 10, 10, 0);

      assertFalse(response.getBody().getBoolean("hasMore"));
    }

    @Test
    void hasMoreFalseWhenPastEnd() throws Exception {
      NeoResponse response = (NeoResponse) invokeStatic("buildSelectorResponse",
          new Class<?>[]{ JSONArray.class, JSONArray.class, int.class, int.class, int.class },
          new JSONArray(), new JSONArray(), 5, 10, 0);

      assertFalse(response.getBody().getBoolean("hasMore"));
    }
  }

  @Nested
  @DisplayName("buildGridColumnMetadata")
  class BuildGridColumnMetadata {
    @Test
    void emptyFieldsReturnsEmptyArray() throws Exception {
      JSONArray result = (JSONArray) invokeStatic("buildGridColumnMetadata",
          new Class<?>[]{ List.class }, Collections.emptyList());
      assertEquals(0, result.length());
    }

    @Test
    void mapsFieldsToKeyLabelPath() throws Exception {
      RichFieldMeta field = new RichFieldMeta("name", "Name", "businessPartner.name", 10L);
      JSONArray result = (JSONArray) invokeStatic("buildGridColumnMetadata",
          new Class<?>[]{ List.class }, Collections.singletonList(field));

      assertEquals(1, result.length());
      JSONObject col = result.getJSONObject(0);
      assertEquals("name", col.getString("key"));
      assertEquals("Name", col.getString("label"));
      assertEquals("businessPartner.name", col.getString("path"));
    }

    @Test
    void multipleFieldsMapInOrder() throws Exception {
      RichFieldMeta f1 = new RichFieldMeta("code", "Code", "searchKey", 1L);
      RichFieldMeta f2 = new RichFieldMeta("desc", "Description", "description", 2L);
      JSONArray result = (JSONArray) invokeStatic("buildGridColumnMetadata",
          new Class<?>[]{ List.class }, Arrays.asList(f1, f2));

      assertEquals(2, result.length());
      assertEquals("code", result.getJSONObject(0).getString("key"));
      assertEquals("desc", result.getJSONObject(1).getString("key"));
    }
  }

  @Nested
  @DisplayName("normalizeEntityId")
  class NormalizeEntityId {
    @Test
    void regularIdPassesThrough() throws Exception {
      String result = (String) invokeStatic("normalizeEntityId",
          new Class<?>[]{ String.class }, "ABC123DEF456");
      assertEquals("ABC123DEF456", result);
    }

    @Test
    void compositeId64HexTakesSecondHalf() throws Exception {
      String hex64 = "0123456789abcdef0123456789abcdefFEDCBA9876543210FEDCBA9876543210";
      String result = (String) invokeStatic("normalizeEntityId",
          new Class<?>[]{ String.class }, hex64);
      assertEquals("FEDCBA9876543210FEDCBA9876543210", result);
    }

    @Test
    void nullReturnsNull() throws Exception {
      String result = (String) invokeStatic("normalizeEntityId",
          new Class<?>[]{ String.class }, (Object) null);
      assertNull(result);
    }

    @Test
    void nonHex64CharStringPassesThrough() throws Exception {
      String s = "x".repeat(64); // not hex
      String result = (String) invokeStatic("normalizeEntityId",
          new Class<?>[]{ String.class }, s);
      assertEquals(s, result);
    }
  }

  @Nested
  @DisplayName("extractRecordId")
  class ExtractRecordId {
    @Test
    void usesIdColumnIndex() throws Exception {
      Object[] row = { "val0", "val1", "theId" };
      String result = (String) invokeStatic("extractRecordId",
          new Class<?>[]{ Object[].class, Integer.class }, row, 2);
      assertEquals("theId", result);
    }

    @Test
    void fallsBackToFirstColumnWhenNullIndex() throws Exception {
      Object[] row = { "firstCol", "other" };
      String result = (String) invokeStatic("extractRecordId",
          new Class<?>[]{ Object[].class, Integer.class }, row, (Integer) null);
      assertEquals("firstCol", result);
    }

    @Test
    void normalizesCompositeIdFromRow() throws Exception {
      String hex64 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
      Object[] row = { hex64 };
      String result = (String) invokeStatic("extractRecordId",
          new Class<?>[]{ Object[].class, Integer.class }, row, (Integer) null);
      assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", result);
    }
  }
}
