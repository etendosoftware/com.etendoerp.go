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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;

/**
 * Unit tests for {@link NeoFieldFilter}.
 * Exercises filtering, renaming, and metadata key logic via reflection on internal methods.
 */
class NeoFieldFilterTest {

  @Test
  @DisplayName("forEntity with a null SFEntity yields an inactive pass-through filter")
  void forEntityNullYieldsInactiveFilter() {
    NeoFieldFilter filter = NeoFieldFilter.forEntity(null, "Order");
    assertNotNull(filter);
    JSONObject input = new JSONObject();
    assertEquals(input, filter.filterGetResponse(input));
  }


  /**
   * Creates a NeoFieldFilter via the private constructor for testing.
   */
  private static NeoFieldFilter createFilter(Set<String> included, Set<String> writable,
      Set<String> rejectableOnCreate, Map<String, String> apiKeyToProp,
      Map<String, String> propToApiKey, boolean active) throws Exception {
    Constructor<NeoFieldFilter> ctor = NeoFieldFilter.class.getDeclaredConstructor(
        Set.class, Set.class, Set.class, Map.class, Map.class, boolean.class);
    ctor.setAccessible(true);
    return ctor.newInstance(included, writable, rejectableOnCreate, apiKeyToProp, propToApiKey,
        active);
  }

  private static NeoFieldFilter activeFilter(Set<String> included, Set<String> writable)
      throws Exception {
    return createFilter(included, writable, Collections.emptySet(),
        Collections.emptyMap(), Collections.emptyMap(), true);
  }

  private static NeoFieldFilter activeFilterWithMappings(Set<String> included, Set<String> writable,
      Map<String, String> apiKeyToProp, Map<String, String> propToApiKey) throws Exception {
    return createFilter(included, writable, Collections.emptySet(), apiKeyToProp, propToApiKey,
        true);
  }

  private static NeoFieldFilter activeFilterWithRejectable(Set<String> included,
      Set<String> writable, Set<String> rejectableOnCreate) throws Exception {
    return createFilter(included, writable, rejectableOnCreate,
        Collections.emptyMap(), Collections.emptyMap(), true);
  }

  private static boolean invokeIsMetadataKey(NeoFieldFilter filter, String key) throws Exception {
    Method m = NeoFieldFilter.class.getDeclaredMethod("isMetadataKey", String.class);
    m.setAccessible(true);
    return (boolean) m.invoke(filter, key);
  }

  /**
   * Invokes the private static {@code includeFkIdentifierVariant} helper via reflection.
   * Passes {@code null} as the instance because the method is static.
   */
  private static void invokeIncludeFkIdentifierVariant(Set<String> included,
      Map<String, String> apiKeyMap, Map<String, String> propToApiMap, Property prop,
      String propName, String qualifier) throws Exception {
    Method m = NeoFieldFilter.class.getDeclaredMethod("includeFkIdentifierVariant",
        Set.class, Map.class, Map.class, Property.class, String.class, String.class);
    m.setAccessible(true);
    m.invoke(null, included, apiKeyMap, propToApiMap, prop, propName, qualifier);
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
      NeoFieldFilter filter = createFilter(null, null, null,
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

      JSONObject row = new JSONObject();
      row.put("id", "123");
      row.put("name", "Test");
      row.put("secretField", "hidden");
      row.put("_entityName", "Order");

      JSONArray data = new JSONArray();
      data.put(row);
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

      JSONObject row = new JSONObject();
      row.put("id", "1");
      row.put("_identifier", "Order 001");
      row.put("$ref", "something");
      row.put("recordTime", "12345");
      row.put("entityName", "OrderLine");
      row.put("unwanted", "remove me");

      JSONArray data = new JSONArray();
      data.put(row);
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

      JSONObject row = new JSONObject();
      row.put("id", "1");
      row.put("priceActual", 100.5);

      JSONArray data = new JSONArray();
      data.put(row);
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
      NeoFieldFilter filter = createFilter(null, null, null,
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

    /**
     * ETP-4531 regression: filterWriteRequest does NOT return an independent copy when the
     * body has no "data" envelope — it mutates the SAME JSONObject reference it's given.
     * A caller that captures a reference to the pre-filter body (e.g. to re-inject a
     * server-mirrored value stripped by filtering) BEFORE calling filterWriteRequest, then
     * checks that SAME reference AFTER the call, will always see the already-filtered state —
     * because there is only ever one object once filtering runs, not two. This is exactly what
     * caused NeoCrudHandler#executeUpdate's original accountingDate re-injection fix to
     * silently no-op: it read `rawBody.has("accountingDate")` after already calling
     * `filterWriteRequest(rawBody)`, by which point `rawBody` had been stripped along with
     * `filteredBody` (the same object). The correct fix captures the value BEFORE filtering.
     */
    @Test
    @DisplayName("Mutates the input JSONObject in place (no defensive copy) when there is no data envelope")
    void mutatesInputInPlace() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "name"));
      Set<String> writable = new HashSet<>(Set.of("id", "name"));
      NeoFieldFilter filter = activeFilter(included, writable);

      JSONObject body = new JSONObject();
      body.put("id", "1");
      body.put("readOnlyField", "should be removed");

      JSONObject result = filter.filterWriteRequest(body);

      assertTrue(result == body, "filterWriteRequest must return the SAME reference, not a copy");
      assertFalse(body.has("readOnlyField"),
          "the original reference is stripped too — reading it after the call sees the filtered state");
    }
  }

  @Nested
  @DisplayName("resolveWritablePropName")
  class ResolveWritablePropName {
    @Test
    @DisplayName("Resolves a remapped API key to its DAL property name")
    void resolvesRemappedKey() throws Exception {
      Map<String, String> apiKeyToProp = new HashMap<>();
      apiKeyToProp.put("accountingDate", "dateAcct");
      NeoFieldFilter filter = activeFilterWithMappings(Collections.emptySet(), Collections.emptySet(),
          apiKeyToProp, Collections.emptyMap());

      assertEquals("dateAcct", filter.resolveWritablePropName("accountingDate"));
    }

    @Test
    @DisplayName("Returns the key unchanged when no remapping is configured")
    void returnsUnchangedWhenNoMapping() throws Exception {
      NeoFieldFilter filter = activeFilter(Collections.emptySet(), Collections.emptySet());

      assertEquals("accountingDate", filter.resolveWritablePropName("accountingDate"));
    }
  }

  @Nested
  @DisplayName("emittableResponseKeys (IMP-18)")
  class EmittableResponseKeys {
    @Test
    @DisplayName("Returns the included properties renamed to their API keys")
    void returnsApiKeys() throws Exception {
      Map<String, String> propToApiKey = new HashMap<>();
      propToApiKey.put("dateAcct", "accountingDate");
      NeoFieldFilter filter = activeFilterWithMappings(
          new HashSet<>(Set.of("id", "documentNo", "dateAcct")), Collections.emptySet(),
          Collections.emptyMap(), propToApiKey);

      // The DAL name "dateAcct" must NOT appear: the caller never sees it, so asking for it is
      // as wrong as asking for a field that does not exist.
      assertEquals(Optional.of(Set.of("id", "documentNo", "accountingDate")),
          filter.emittableResponseKeys());
    }

    @Test
    @DisplayName("Returns Optional.empty() for an inactive filter — the response is unfiltered, so "
        + "the spec cannot answer what is emittable")
    void inactiveReturnsEmpty() {
      assertEquals(Optional.empty(), NeoFieldFilter.forEntity(null, "Order").emittableResponseKeys());
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

    /**
     * IMP-28 clause 2: an included, read-only field with no configured default and no owning
     * NeoHandler must be REJECTED (thrown), not silently dropped — that silent drop is the root
     * cause the ticket describes: the caller gets 200 with the value discarded and no signal.
     */
    @Test
    @DisplayName("rejects a read-only field with no default/handler excuse instead of dropping it")
    void rejectsUnexcusedReadOnlyField() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "name", "salePrice"));
      Set<String> writable = new HashSet<>(Set.of("id", "name"));
      Set<String> rejectableOnCreate = new HashSet<>(Set.of("salePrice"));
      NeoFieldFilter filter = activeFilterWithRejectable(included, writable, rejectableOnCreate);

      JSONObject body = new JSONObject();
      body.put("id", "1");
      body.put("name", "Test");
      body.put("salePrice", 42.0);

      ReadOnlyFieldRejectedException ex = assertThrows(
          ReadOnlyFieldRejectedException.class, () -> filter.filterCreateRequest(body));
      assertEquals("salePrice", ex.getFieldName());
    }

    @Test
    @DisplayName("rejection names the API-facing key the caller actually sent, not the DAL name")
    void rejectionReportsApiKeyNotDalName() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "priceActual"));
      Set<String> writable = new HashSet<>(Set.of("id"));
      Set<String> rejectableOnCreate = new HashSet<>(Set.of("priceActual"));
      Map<String, String> apiKeyToProp = new HashMap<>();
      apiKeyToProp.put("unitPrice", "priceActual");
      NeoFieldFilter filter = createFilter(included, writable, rejectableOnCreate,
          apiKeyToProp, Collections.emptyMap(), true);

      JSONObject body = new JSONObject();
      body.put("id", "1");
      body.put("unitPrice", 42.0);

      ReadOnlyFieldRejectedException ex = assertThrows(
          ReadOnlyFieldRejectedException.class, () -> filter.filterCreateRequest(body));
      assertEquals("unitPrice", ex.getFieldName(),
          "the caller sent 'unitPrice' — that is the name they must see, not the DAL 'priceActual'");
    }

    @Test
    @DisplayName("a request that omits the rejectable field entirely is not rejected")
    void doesNotRejectWhenFieldAbsent() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "name", "salePrice"));
      Set<String> writable = new HashSet<>(Set.of("id", "name"));
      Set<String> rejectableOnCreate = new HashSet<>(Set.of("salePrice"));
      NeoFieldFilter filter = activeFilterWithRejectable(included, writable, rejectableOnCreate);

      JSONObject body = new JSONObject();
      body.put("id", "1");
      body.put("name", "Test");

      JSONObject result = filter.filterCreateRequest(body);
      assertFalse(result.has("salePrice"));
    }

    @Test
    @DisplayName("rejection also fires when the body arrives wrapped in a data envelope")
    void rejectsInsideDataEnvelope() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "salePrice"));
      Set<String> writable = new HashSet<>(Set.of("id"));
      Set<String> rejectableOnCreate = new HashSet<>(Set.of("salePrice"));
      NeoFieldFilter filter = activeFilterWithRejectable(included, writable, rejectableOnCreate);

      JSONObject inner = new JSONObject();
      inner.put("id", "1");
      inner.put("salePrice", 42.0);
      JSONObject body = new JSONObject();
      body.put("data", inner);

      assertThrows(
          ReadOnlyFieldRejectedException.class, () -> filter.filterCreateRequest(body));
    }

    /**
     * IMP-28 clause 2 regression guard: fields whose value is legitimately supplied by their
     * entity's own NeoHandler pre-hook (e.g. {@code InventoryLineHandler} injecting
     * {@code bookQuantity}, {@code transactionDocument} on document headers) must keep passing
     * through unfiltered — they are simply never added to rejectableOnCreateFields in the first
     * place (see NeoFieldFilter#processFieldMappings), so an empty rejectable set behaves
     * exactly like the pre-clause-2 passthrough.
     */
    @Test
    @DisplayName("still passes through a handler-supplied read-only field (regression: bookQuantity/transactionDocument)")
    void passesThroughHandlerSuppliedReadOnlyField() throws Exception {
      Set<String> included = new HashSet<>(Set.of("id", "bookQuantity", "transactionDocument"));
      Set<String> writable = new HashSet<>(Set.of("id"));
      // Empty: these fields were excluded from rejectableOnCreateFields because their entity has
      // a Java_Qualifier (a NeoHandler may have supplied them) or an AD default is configured.
      NeoFieldFilter filter = activeFilterWithRejectable(included, writable, Collections.emptySet());

      JSONObject body = new JSONObject();
      body.put("id", "1");
      body.put("bookQuantity", 5);
      body.put("transactionDocument", "some-doc-type-id");

      JSONObject result = filter.filterCreateRequest(body);
      assertTrue(result.has("bookQuantity"));
      assertTrue(result.has("transactionDocument"));
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

  @Nested
  @DisplayName("includeFkIdentifierVariant")
  class IncludeFkIdentifierVariant {

    @Test
    @DisplayName("primitive property adds nothing")
    void primitivePropertyAddsNothing() throws Exception {
      Set<String> included = new HashSet<>();
      Map<String, String> apiKeyMap = new HashMap<>();
      Map<String, String> propToApiMap = new HashMap<>();
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);

      invokeIncludeFkIdentifierVariant(included, apiKeyMap, propToApiMap, prop,
          "businessPartner", "partner");

      assertTrue(included.isEmpty(), "included must stay empty for primitive property");
      assertTrue(apiKeyMap.isEmpty(), "apiKeyMap must stay empty for primitive property");
      assertTrue(propToApiMap.isEmpty(), "propToApiMap must stay empty for primitive property");
    }

    @Test
    @DisplayName("non-primitive with null target entity adds nothing")
    void nonPrimitiveNullTargetAddsNothing() throws Exception {
      Set<String> included = new HashSet<>();
      Map<String, String> apiKeyMap = new HashMap<>();
      Map<String, String> propToApiMap = new HashMap<>();
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(false);
      when(prop.getTargetEntity()).thenReturn(null);

      invokeIncludeFkIdentifierVariant(included, apiKeyMap, propToApiMap, prop,
          "businessPartner", "partner");

      assertTrue(included.isEmpty(), "included must stay empty when targetEntity is null");
      assertTrue(apiKeyMap.isEmpty(), "apiKeyMap must stay empty when targetEntity is null");
      assertTrue(propToApiMap.isEmpty(),
          "propToApiMap must stay empty when targetEntity is null");
    }

    @Test
    @DisplayName("FK with null qualifier includes identifier variant only")
    void fkNullQualifierIncludesVariantOnly() throws Exception {
      Set<String> included = new HashSet<>();
      Map<String, String> apiKeyMap = new HashMap<>();
      Map<String, String> propToApiMap = new HashMap<>();
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(false);
      when(prop.getTargetEntity()).thenReturn(mock(Entity.class));

      invokeIncludeFkIdentifierVariant(included, apiKeyMap, propToApiMap, prop,
          "businessPartner", null);

      assertTrue(included.contains("businessPartner$_identifier"),
          "included must contain the $_identifier variant");
      assertEquals(1, included.size());
      assertTrue(apiKeyMap.isEmpty(), "apiKeyMap must stay empty with null qualifier");
      assertTrue(propToApiMap.isEmpty(), "propToApiMap must stay empty with null qualifier");
    }

    @Test
    @DisplayName("FK with qualifier equal to propName includes identifier variant only")
    void fkQualifierEqualToPropNameIncludesVariantOnly() throws Exception {
      Set<String> included = new HashSet<>();
      Map<String, String> apiKeyMap = new HashMap<>();
      Map<String, String> propToApiMap = new HashMap<>();
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(false);
      when(prop.getTargetEntity()).thenReturn(mock(Entity.class));

      invokeIncludeFkIdentifierVariant(included, apiKeyMap, propToApiMap, prop,
          "businessPartner", "businessPartner");

      assertTrue(included.contains("businessPartner$_identifier"),
          "included must contain the $_identifier variant");
      assertEquals(1, included.size());
      assertTrue(apiKeyMap.isEmpty(),
          "apiKeyMap must stay empty when qualifier equals propName");
      assertTrue(propToApiMap.isEmpty(),
          "propToApiMap must stay empty when qualifier equals propName");
    }

    @Test
    @DisplayName("FK with distinct qualifier registers alias in both maps")
    void fkDistinctQualifierRegistersAlias() throws Exception {
      Set<String> included = new HashSet<>();
      Map<String, String> apiKeyMap = new HashMap<>();
      Map<String, String> propToApiMap = new HashMap<>();
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(false);
      when(prop.getTargetEntity()).thenReturn(mock(Entity.class));

      invokeIncludeFkIdentifierVariant(included, apiKeyMap, propToApiMap, prop,
          "finFinancialAccount", "account");

      assertTrue(included.contains("finFinancialAccount$_identifier"),
          "included must contain the DAL $_identifier variant");
      assertEquals(1, included.size());
      assertEquals("account$_identifier",
          propToApiMap.get("finFinancialAccount$_identifier"),
          "propToApiMap must map DAL variant to qualifier variant");
      assertEquals(1, propToApiMap.size());
      assertEquals("finFinancialAccount$_identifier",
          apiKeyMap.get("account$_identifier"),
          "apiKeyMap must map qualifier variant to DAL variant");
      assertEquals(1, apiKeyMap.size());
    }
  }
}
