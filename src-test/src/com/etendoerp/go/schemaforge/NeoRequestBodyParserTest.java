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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link NeoRequestBodyParser}.
 */
class NeoRequestBodyParserTest {

  private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = NeoRequestBodyParser.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  @Nested
  @DisplayName("parseOptionalJsonObject")
  class ParseOptional {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t" })
    void blankOrNullReturnsNull(String input) throws Exception {
      Object result = invokeStatic("parseOptionalJsonObject",
          new Class<?>[]{ String.class }, input);
      assertNull(result);
    }

    @Test
    void validJsonReturnsObject() throws Exception {
      JSONObject result = (JSONObject) invokeStatic("parseOptionalJsonObject",
          new Class<?>[]{ String.class }, "{\"key\":\"value\"}");
      assertNotNull(result);
      assertEquals("value", result.getString("key"));
    }
  }

  @Nested
  @DisplayName("parseJsonObjectOrEmpty")
  class ParseOrEmpty {
    @Test
    void blankStringReturnsEmptyJsonObject() throws Exception {
      JSONObject result = (JSONObject) invokeStatic("parseJsonObjectOrEmpty",
          new Class<?>[]{ String.class }, "");
      assertNotNull(result);
      assertEquals(0, result.length());
    }

    @Test
    void validJsonReturnsObject() throws Exception {
      JSONObject result = (JSONObject) invokeStatic("parseJsonObjectOrEmpty",
          new Class<?>[]{ String.class }, "{\"a\":1}");
      assertEquals(1, result.getInt("a"));
    }
  }

  @Nested
  @DisplayName("parseJsonObject")
  class ParseJson {
    @Test
    void validJsonParsesCorrectly() throws Exception {
      JSONObject result = (JSONObject) invokeStatic("parseJsonObject",
          new Class<?>[]{ String.class }, "{\"name\":\"test\",\"count\":42}");
      assertEquals("test", result.getString("name"));
      assertEquals(42, result.getInt("count"));
    }

    @Test
    void invalidJsonThrowsException() {
      assertThrows(Exception.class, () ->
          invokeStatic("parseJsonObject", new Class<?>[]{ String.class }, "not json"));
    }
  }
}
