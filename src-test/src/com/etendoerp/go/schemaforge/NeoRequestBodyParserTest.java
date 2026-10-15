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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link NeoRequestBodyParser}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoRequestBodyParser
 */
class NeoRequestBodyParserTest {

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<NeoRequestBodyParser> constructor = NeoRequestBodyParser.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  @Nested
  @DisplayName("parseJsonObjectSafely")
  class ParseJsonObjectSafely {

    @Test
    void normalValidJsonObject() throws Exception {
      JSONObject result = NeoRequestBodyParser.parseJsonObjectSafely("{\"name\":\"test\",\"count\":42}");
      assertEquals("test", result.getString("name"));
      assertEquals(42, result.getInt("count"));
    }

    @Test
    void truncatedBodyStackBreakerThrowsJsonException() {
      // Jettison's recursion on truncated array tokens ("{\"x\":[") normally ends in StackOverflowError
      JSONException ex = assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonObjectSafely("{\"x\":["));
      assertNotNull(ex.getMessage());
    }

    @Test
    void deeplyNestedArraysStackBreakerThrowsJsonException() {
      // ~40 KB body with 20,000 nested brackets fails depth pre-scan
      String deep = "{\"events\":" + "[".repeat(20_000) + "]".repeat(20_000) + "}";
      JSONException ex = assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonObjectSafely(deep));
      assertTrue(ex.getMessage().contains("exceeds maximum depth"));
    }

    @Test
    void deeplyNestedObjectsStackBreakerThrowsJsonException() {
      String deep = "{\"a\":".repeat(35) + "1" + "}".repeat(35);
      JSONException ex = assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonObjectSafely(deep));
      assertTrue(ex.getMessage().contains("exceeds maximum depth"));
    }

    @Test
    void depthLimitBoundaries() throws Exception {
      // 32 levels of nesting (within default MAX_JSON_DEPTH = 32)
      StringBuilder sbValid = new StringBuilder();
      for (int i = 0; i < 31; i++) {
        sbValid.append("{\"lvl\":");
      }
      sbValid.append("{\"val\":1}");
      for (int i = 0; i < 31; i++) {
        sbValid.append("}");
      }
      JSONObject ok = NeoRequestBodyParser.parseJsonObjectSafely(sbValid.toString());
      assertNotNull(ok);

      // 33 levels of nesting exceeds MAX_JSON_DEPTH = 32
      StringBuilder sbExceeded = new StringBuilder();
      for (int i = 0; i < 32; i++) {
        sbExceeded.append("{\"lvl\":");
      }
      sbExceeded.append("{\"val\":1}");
      for (int i = 0; i < 32; i++) {
        sbExceeded.append("}");
      }
      JSONException ex = assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonObjectSafely(sbExceeded.toString()));
      assertTrue(ex.getMessage().contains("exceeds maximum depth"));
    }

    @Test
    void customDepthLimitEnforced() throws Exception {
      String fourLevels = "{\"a\":{\"b\":{\"c\":{\"d\":1}}}}";
      JSONObject ok = NeoRequestBodyParser.parseJsonObjectSafely(fourLevels, 4);
      assertNotNull(ok);

      JSONException ex = assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonObjectSafely(fourLevels, 3));
      assertTrue(ex.getMessage().contains("exceeds maximum depth"));
    }

    @Test
    void nullThrowsJsonException() {
      assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonObjectSafely(null));
    }

    @Test
    void bracketsInsideStringsDoNotCountTowardDepth() throws Exception {
      String json = "{\"string\":\"" + "[".repeat(100) + "\",\"num\":123}";
      JSONObject parsed = NeoRequestBodyParser.parseJsonObjectSafely(json);
      assertEquals(123, parsed.getInt("num"));
    }

    @Test
    void escapedQuotesInsideStringsHandledCorrectly() throws Exception {
      String json = "{\"str\":\"hello \\\"world\\\" [[[\",\"active\":true}";
      JSONObject parsed = NeoRequestBodyParser.parseJsonObjectSafely(json);
      assertTrue(parsed.getBoolean("active"));
    }
  }

  @Nested
  @DisplayName("parseJsonArraySafely")
  class ParseJsonArraySafely {

    @Test
    void normalValidJsonArray() throws Exception {
      JSONArray result = NeoRequestBodyParser.parseJsonArraySafely("[\"a\", \"b\", 3]");
      assertEquals(3, result.length());
      assertEquals("a", result.getString(0));
      assertEquals(3, result.getInt(2));
    }

    @Test
    void truncatedArrayStackBreakerThrowsJsonException() {
      JSONException ex = assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonArraySafely("[{\"x\":["));
      assertNotNull(ex.getMessage());
    }

    @Test
    void deeplyNestedArrayStackBreakerThrowsJsonException() {
      String deep = "[".repeat(20_000) + "]".repeat(20_000);
      JSONException ex = assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonArraySafely(deep));
      assertTrue(ex.getMessage().contains("exceeds maximum depth"));
    }

    @Test
    void nullThrowsJsonException() {
      assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonArraySafely(null));
    }

    @Test
    void bracketsInsideStringsInArrayDoNotCountTowardDepth() throws Exception {
      String json = "[\"" + "[".repeat(100) + "\", \"ok\"]";
      JSONArray parsed = NeoRequestBodyParser.parseJsonArraySafely(json);
      assertEquals(2, parsed.length());
      assertEquals("ok", parsed.getString(1));
    }
  }

  @Nested
  @DisplayName("exceedsDepth")
  class ExceedsDepth {

    @Test
    void nullReturnsFalse() {
      assertFalse(NeoRequestBodyParser.exceedsDepth(null, 32));
    }

    @Test
    void shallowJsonReturnsFalse() {
      assertFalse(NeoRequestBodyParser.exceedsDepth("{\"a\":[1,2,{\"b\":3}]}", 32));
    }

    @Test
    void deepJsonReturnsTrue() {
      String deep = "[".repeat(33) + "]".repeat(33);
      assertTrue(NeoRequestBodyParser.exceedsDepth(deep, 32));
    }

    @Test
    void bracketsInStringIgnored() {
      String body = "{\"a\":\"" + "{[{[{[{[".repeat(10) + "\"}";
      assertFalse(NeoRequestBodyParser.exceedsDepth(body, 5));
    }

    @Test
    void escapedQuoteInsideStringHandled() {
      String body = "{\"a\":\"quote \\\" inside " + "[[[[".repeat(10) + "\"}";
      assertFalse(NeoRequestBodyParser.exceedsDepth(body, 5));
    }
  }

  @Nested
  @DisplayName("parseOptionalJsonObject")
  class ParseOptional {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t" })
    void blankOrNullReturnsNull(String input) throws Exception {
      JSONObject result = NeoRequestBodyParser.parseOptionalJsonObject(input);
      assertNull(result);
    }

    @Test
    void validJsonReturnsObject() throws Exception {
      JSONObject result = NeoRequestBodyParser.parseOptionalJsonObject("{\"key\":\"value\"}");
      assertNotNull(result);
      assertEquals("value", result.getString("key"));
    }

    @Test
    void malformedJsonThrowsException() {
      assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseOptionalJsonObject("{\"x\":["));
    }
  }

  @Nested
  @DisplayName("parseJsonObjectOrEmpty")
  class ParseOrEmpty {
    @Test
    void blankStringReturnsEmptyJsonObject() throws Exception {
      JSONObject result = NeoRequestBodyParser.parseJsonObjectOrEmpty("");
      assertNotNull(result);
      assertEquals(0, result.length());
    }

    @Test
    void validJsonReturnsObject() throws Exception {
      JSONObject result = NeoRequestBodyParser.parseJsonObjectOrEmpty("{\"a\":1}");
      assertEquals(1, result.getInt("a"));
    }

    @Test
    void malformedJsonThrowsException() {
      assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonObjectOrEmpty("{\"x\":["));
    }
  }

  @Nested
  @DisplayName("parseJsonObject")
  class ParseJson {
    @Test
    void validJsonParsesCorrectly() throws Exception {
      JSONObject result = NeoRequestBodyParser.parseJsonObject("{\"name\":\"test\",\"count\":42}");
      assertEquals("test", result.getString("name"));
      assertEquals(42, result.getInt("count"));
    }

    @Test
    void invalidJsonThrowsException() {
      assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonObject("not json"));
    }

    @Test
    void truncatedStackBreakerThrowsException() {
      assertThrows(JSONException.class, () ->
          NeoRequestBodyParser.parseJsonObject("{\"x\":["));
    }
  }
}
