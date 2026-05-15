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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link NeoFieldFilter}.
 * Exercises filtering, renaming, and metadata key logic via reflection on internal methods.
 */
class NeoFieldFilterTest {

  /**
   * Creates a NeoFieldFilter via the private constructor for testing.
   */
  private static NeoFieldFilter createFilter(Set<String> included, Set<String> writable,
      Map<String, String> apiKeyToProp, Map<String, String> propToApiKey, boolean active)
      throws Exception {
    Constructor<NeoFieldFilter> ctor = NeoFieldFilter.class.getDeclaredConstructor(
        Set.class, Set.class, Map.class, Map.class, boolean.class);
    ctor.setAccessible(true);
    return ctor.newInstance(included, writable, apiKeyToProp, propToApiKey, active);
  }

  private static NeoFieldFilter activeFilter(Set<String> included, Set<String> writable)
      throws Exception {
    return createFilter(included, writable,
        Collections.emptyMap(), Collections.emptyMap(), true);
  }

  private static NeoFieldFilter activeFilterWithMappings(Set<String> included, Set<String> writable,
      Map<String, String> apiKeyToProp, Map<String, String> propToApiKey) throws Exception {
    return createFilter(included, writable, apiKeyToProp, propToApiKey, true);
  }

  private static boolean invokeIsMetadataKey(NeoFieldFilter filter, String key) throws Exception {
    Method m = NeoFieldFilter.class.getDeclaredMethod("isMetadataKey", String.class);
    m.setAccessible(true);
    return (boolean) m.invoke(filter, key);
  }

  @Nested
  @DisplayName("forEntity with null entity")
  class ForEntityNull {
    @Test
    void nullSfEntityReturnsInactive() {
      NeoFieldFilter filter = NeoFieldFilter.forEntity(null, "Order");
      assertNotNull(filter);
      // Inactive filter should pass through GET response unchanged
      JSONObject input = new JSONObject();
      assertEquals(input, filter.filterGetResponse(input));
    }
  }

  @Nested
  @DisplayName("filterGetResponse")
  class FilterGetResponse {
    @Test
    void inactiveFilterReturnsUnchanged() throws Exception {
      NeoFieldFilter filter = createFilter(null, null,
          Collections.emptyMap(), Collections.emptyMap(), false);
      JSONObject input = new JSONObject().put("someField", "value");
      JSONObject result = filter.filterGetResponse(input);
      assertEquals("value", result.getString("someField"));
    }

    @Test
    void nullResponseReturnsNull() throws Exception {
      NeoFieldFilter filter = activeFilter(Set.of("id"), Set.of("id"));
      assertNull(filter.filterGetResponse(null));
    }

    @Test
    void removesNonIncludedFieldsFromData() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "name", "_entityName"));
      NeoFieldFilter filter = activeFilter(included, included);

      JSONObject record = new JSONObject();
      record.put("id", "123");
      record.put("name", "Test");
      record.put("secretField", "hidden");
      record.put("_entityName", "Order");

      JSONArray data = new JSONArray();
      data.put(record);
      JSONObject response = new JSONObject();
      response.put("data", data);
      JSONObject wrapper = new JSONObject();
      wrapper.put("response", response);

      filter.filterGetResponse(wrapper);

      JSONObject filtered = wrapper.getJSONObject("response")
          .getJSONArray("data").getJSONObject(0);
      assertTrue(filtered.has("id"));
      assertTrue(filtered.has("name"));
      assertTrue(filtered.has("_entityName"));
      assertFalse(filtered.has("secretField"));
    }

    @Test
    void preservesMetadataKeys() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id"));
      NeoFieldFilter filter = activeFilter(included, included);

      JSONObject record = new JSONObject();
      record.put("id", "1");
      record.put("_identifier", "Order 001");
      record.put("$ref", "something");
      record.put("recordTime", "12345");
      record.put("entityName", "OrderLine");
      record.put("unwanted", "remove me");

      JSONArray data = new JSONArray();
      data.put(record);
      JSONObject response = new JSONObject();
      response.put("data", data);
      JSONObject wrapper = new JSONObject();
      wrapper.put("response", response);

      filter.filterGetResponse(wrapper);

      JSONObject filtered = wrapper.getJSONObject("response")
          .getJSONArray("data").getJSONObject(0);
      assertTrue(filtered.has("_identifier"));
      assertTrue(filtered.has("$ref"));
      assertTrue(filtered.has("recordTime"));
      assertTrue(filtered.has("entityName"));
      assertFalse(filtered.has("unwanted"));
    }

    @Test
    void renamesPropertiesToApiKeysInGetResponse() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "priceActual"));
      Map<String, String> propToApi = new HashMap<>();
      propToApi.put("priceActual", "unitPrice");
      NeoFieldFilter filter = activeFilterWithMappings(included, included,
          Collections.emptyMap(), propToApi);

      JSONObject record = new JSONObject();
      record.put("id", "1");
      record.put("priceActual", 100.5);

      JSONArray data = new JSONArray();
      data.put(record);
      JSONObject response = new JSONObject();
      response.put("data", data);
      JSONObject wrapper = new JSONObject();
      wrapper.put("response", response);

      filter.filterGetResponse(wrapper);

      JSONObject filtered = wrapper.getJSONObject("response")
          .getJSONArray("data").getJSONObject(0);
      assertFalse(filtered.has("priceActual"), "DAL name should be removed");
      assertTrue(filtered.has("unitPrice"), "API key should be present");
      assertEquals(100.5, filtered.getDouble("unitPrice"), 0.001);
    }

    @Test
    void handlesResponseWithNoDataArray() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id"));
      NeoFieldFilter filter = activeFilter(included, included);

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", new JSONObject().put("status", 0));

      // Should not throw
      JSONObject result = filter.filterGetResponse(wrapper);
      assertNotNull(result);
    }

    @Test
    void handlesResponseWithNoResponseKey() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id"));
      NeoFieldFilter filter = activeFilter(included, included);

      JSONObject wrapper = new JSONObject().put("other", "data");

      JSONObject result = filter.filterGetResponse(wrapper);
      assertNotNull(result);
    }
  }

  @Nested
  @DisplayName("filterWriteRequest")
  class FilterWriteRequest {
    @Test
    void inactiveFilterReturnsUnchanged() throws Exception {
      NeoFieldFilter filter = createFilter(null, null,
          Collections.emptyMap(), Collections.emptyMap(), false);
      JSONObject body = new JSONObject().put("any", "value");
      assertEquals(body, filter.filterWriteRequest(body));
    }

    @Test
    void removesNonWritableFields() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "name", "readOnlyField"));
      Set<String> writable = new HashSet<>(Set.of("id", "name"));
      NeoFieldFilter filter = activeFilter(included, writable);

      JSONObject body = new JSONObject();
      body.put("id", "1");
      body.put("name", "Test");
      body.put("readOnlyField", "should be removed");
      body.put("unknownField", "also removed");

      JSONObject result = filter.filterWriteRequest(body);
      assertTrue(result.has("id"));
      assertTrue(result.has("name"));
      assertFalse(result.has("readOnlyField"));
      assertFalse(result.has("unknownField"));
    }

    @Test
    void nullBodyReturnsNull() throws Exception {
      NeoFieldFilter filter = activeFilter(Set.of("id"), Set.of("id"));
      assertNull(filter.filterWriteRequest(null));
    }

    @Test
    void unwrapsDataEnvelope() throws Exception {
      Set<String> writable = new HashSet<>(Set.of("id", "name"));
      NeoFieldFilter filter = activeFilter(writable, writable);

      JSONObject inner = new JSONObject();
      inner.put("id", "1");
      inner.put("name", "Foo");
      inner.put("extra", "remove");
      JSONObject body = new JSONObject();
      body.put("data", inner);

      JSONObject result = filter.filterWriteRequest(body);
      assertTrue(result.has("name"));
      assertFalse(result.has("extra"));
    }

    @Test
    void remapsApiKeysBeforeFiltering() throws Exception {
      Set<String> writable = new HashSet<>(Set.of("id", "priceActual"));
      Map<String, String> apiKeyToProp = new HashMap<>();
      apiKeyToProp.put("unitPrice", "priceActual");
      NeoFieldFilter filter = activeFilterWithMappings(writable, writable,
          apiKeyToProp, Collections.emptyMap());

      JSONObject body = new JSONObject();
      body.put("id", "1");
      body.put("unitPrice", 99.5);

      JSONObject result = filter.filterWriteRequest(body);
      assertTrue(result.has("priceActual"), "API key should be renamed to DAL name");
      assertFalse(result.has("unitPrice"), "API key should be removed after rename");
    }
  }

  @Nested
  @DisplayName("filterCreateRequest")
  class FilterCreateRequest {
    @Test
    void allowsReadOnlyFieldsForCreate() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "name", "readOnlyField"));
      Set<String> writable = new HashSet<>(Set.of("id", "name"));
      NeoFieldFilter filter = activeFilter(included, writable);

      JSONObject body = new JSONObject();
      body.put("id", "1");
      body.put("name", "Test");
      body.put("readOnlyField", "allowed for create");
      body.put("unknownField", "removed");

      JSONObject result = filter.filterCreateRequest(body);
      assertTrue(result.has("readOnlyField"), "Read-only included fields allowed on create");
      assertFalse(result.has("unknownField"));
    }
  }

  @Nested
  @DisplayName("isMetadataKey")
  class IsMetadataKey {
    @ParameterizedTest
    @ValueSource(strings = { "_entityName", "_identifier", "$ref", "$className",
        "recordTime", "entityName" })
    void recognizesMetadataKeys(String key) throws Exception {
      NeoFieldFilter filter = activeFilter(Set.of("id"), Set.of("id"));
      assertTrue(invokeIsMetadataKey(filter, key));
    }

    @ParameterizedTest
    @ValueSource(strings = { "id", "name", "priceActual", "businessPartner" })
    void rejectsRegularKeys(String key) throws Exception {
      NeoFieldFilter filter = activeFilter(Set.of("id"), Set.of("id"));
      assertFalse(invokeIsMetadataKey(filter, key));
    }
  }

  @Nested
  @DisplayName("Multiple records filtering")
  class MultipleRecords {
    @Test
    void filtersAllRecordsInDataArray() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "name"));
      NeoFieldFilter filter = activeFilter(included, included);

      JSONArray data = new JSONArray();
      for (int i = 0; i < 3; i++) {
        JSONObject rec = new JSONObject();
        rec.put("id", String.valueOf(i));
        rec.put("name", "Item " + i);
        rec.put("hidden", "secret");
        data.put(rec);
      }
      JSONObject response = new JSONObject().put("data", data);
      JSONObject wrapper = new JSONObject().put("response", response);

      filter.filterGetResponse(wrapper);

      JSONArray filtered = wrapper.getJSONObject("response").getJSONArray("data");
      assertEquals(3, filtered.length());
      for (int i = 0; i < 3; i++) {
        JSONObject rec = filtered.getJSONObject(i);
        assertTrue(rec.has("id"));
        assertTrue(rec.has("name"));
        assertFalse(rec.has("hidden"));
      }
    }
  }
}
