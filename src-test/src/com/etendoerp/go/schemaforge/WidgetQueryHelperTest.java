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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link WidgetQueryHelper}.
 * Tests the pure utility methods (rangeToSqlDateFrom, buildDataResponse) that
 * don't require database mocking.
 */
class WidgetQueryHelperTest {

  /**
   * Uses reflection to invoke the package-private rangeToSqlDateFrom.
   */
  private static String invokeRangeToSqlDateFrom(String range) throws Exception {
    Method method = WidgetQueryHelper.class.getDeclaredMethod("rangeToSqlDateFrom", String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, range);
  }

  /**
   * Uses reflection to invoke the package-private buildDataResponse.
   */
  private static NeoResponse invokeBuildDataResponse(JSONArray data) throws Exception {
    Method method = WidgetQueryHelper.class.getDeclaredMethod("buildDataResponse", JSONArray.class);
    method.setAccessible(true);
    return (NeoResponse) method.invoke(null, data);
  }

  @Nested
  @DisplayName("rangeToSqlDateFrom")
  class RangeToSqlDateFrom {

    @ParameterizedTest
    @CsvSource({
        "last30d, '30 days'",
        "last90d, '90 days'",
        "mtd, month",
        "ytd, year",
        "lastYear, '12 months'"
    })
    void knownRangesProduceExpectedSql(String range, String expectedFragment) throws Exception {
      String result = invokeRangeToSqlDateFrom(range);
      assertNotNull(result);
      assertTrue(result.contains(expectedFragment),
          "Expected '" + expectedFragment + "' in: " + result);
    }

    @Test
    void unknownRangeDefaultsToLastYear() throws Exception {
      String result = invokeRangeToSqlDateFrom("custom");
      assertTrue(result.contains("12 months"));
    }
  }

  @Nested
  @DisplayName("buildDataResponse")
  class BuildDataResponse {

    @Test
    void emptyArrayReturns200WithCountZero() throws Exception {
      NeoResponse response = invokeBuildDataResponse(new JSONArray());
      assertEquals(200, response.getHttpStatus());

      JSONObject respData = response.getBody().getJSONObject("response");
      assertEquals(0, respData.getInt("count"));
      assertEquals(0, respData.getJSONArray("data").length());
    }

    @Test
    void nonEmptyArrayReturnsCorrectCount() throws Exception {
      JSONArray data = new JSONArray();
      data.put(new JSONObject().put("name", "test"));
      data.put(new JSONObject().put("name", "test2"));

      NeoResponse response = invokeBuildDataResponse(data);
      assertEquals(200, response.getHttpStatus());

      JSONObject respData = response.getBody().getJSONObject("response");
      assertEquals(2, respData.getInt("count"));
    }

    @Test
    void responseHasStandardEnvelope() throws Exception {
      NeoResponse response = invokeBuildDataResponse(new JSONArray());
      assertTrue(response.getBody().has("response"));
      assertTrue(response.getBody().getJSONObject("response").has("data"));
      assertTrue(response.getBody().getJSONObject("response").has("count"));
    }
  }
}
