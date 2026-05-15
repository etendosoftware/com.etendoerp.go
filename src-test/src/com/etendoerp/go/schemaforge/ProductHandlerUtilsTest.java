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

import java.lang.reflect.Method;
import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductHandlerUtils}.
 */
class ProductHandlerUtilsTest {

  private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = ProductHandlerUtils.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  @Nested
  @DisplayName("buildListResponse")
  class BuildListResponse {
    @Test
    void wrapsDataInStandardEnvelope() throws Exception {
      JSONArray data = new JSONArray();
      data.put(new JSONObject().put("id", "1"));
      data.put(new JSONObject().put("id", "2"));

      NeoResponse response = (NeoResponse) invokeStatic("buildListResponse",
          new Class<?>[]{ JSONArray.class }, data);

      assertEquals(200, response.getHttpStatus());
      JSONObject inner = response.getBody().getJSONObject("response");
      assertEquals(2, inner.getJSONArray("data").length());
      assertEquals(0, inner.getInt("startRow"));
      assertEquals(2, inner.getInt("endRow"));
      assertEquals(2, inner.getInt("totalRows"));
      assertEquals(0, inner.getInt("status"));
    }

    @Test
    void nullDataReturnsEmptyArray() throws Exception {
      NeoResponse response = (NeoResponse) invokeStatic("buildListResponse",
          new Class<?>[]{ JSONArray.class }, (JSONArray) null);

      assertEquals(200, response.getHttpStatus());
      JSONObject inner = response.getBody().getJSONObject("response");
      assertEquals(0, inner.getJSONArray("data").length());
      assertEquals(0, inner.getInt("totalRows"));
    }

    @Test
    void emptyDataReturnsZeroRows() throws Exception {
      NeoResponse response = (NeoResponse) invokeStatic("buildListResponse",
          new Class<?>[]{ JSONArray.class }, new JSONArray());

      JSONObject inner = response.getBody().getJSONObject("response");
      assertEquals(0, inner.getInt("endRow"));
    }
  }

  @Nested
  @DisplayName("toBigDecimal")
  class ToBigDecimal {
    @Test
    void nullReturnsZero() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[]{ Object.class }, (Object) null);
      assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void bigDecimalPassesThrough() throws Exception {
      BigDecimal input = new BigDecimal("123.45");
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[]{ Object.class }, input);
      assertEquals(input, result);
    }

    @Test
    void stringConvertsToBigDecimal() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[]{ Object.class }, "999.99");
      assertEquals(new BigDecimal("999.99"), result);
    }

    @Test
    void integerConvertsToBigDecimal() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[]{ Object.class }, 42);
      assertEquals(new BigDecimal("42"), result);
    }

    @Test
    void unparseableStringReturnsZero() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[]{ Object.class }, "not-a-number");
      assertEquals(BigDecimal.ZERO, result);
    }
  }
}
