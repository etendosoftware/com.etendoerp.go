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
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.service.json.JsonConstants;

/**
 * Unit tests for {@link NeoTypeCoercionHelper}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoTypeCoercionHelperTest {

  private MockedStatic<ModelProvider> modelProviderMock;
  private ModelProvider modelProvider;
  private Entity entity;

  @BeforeEach
  void setUp() {
    modelProvider = mock(ModelProvider.class);
    entity = mock(Entity.class);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
    when(modelProvider.getEntity("TestEntity")).thenReturn(entity);
  }

  @AfterEach
  void tearDown() {
    modelProviderMock.close();
  }

  @Nested
  @DisplayName("coerceField")
  class CoerceField {

    @Test
    void coercesBigDecimalField() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) BigDecimal.class);
      when(entity.getProperty("amount")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "amount", "123.45", coerced);

      assertEquals(new BigDecimal("123.45"), coerced.get("amount"));
    }

    @Test
    void coercesLongField() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Long.class);
      when(entity.getProperty("lineNo")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "lineNo", "42", coerced);

      assertEquals(42L, coerced.get("lineNo"));
    }

    @Test
    void coercesIntegerField() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Integer.class);
      when(entity.getProperty("seqNo")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "seqNo", "10", coerced);

      assertEquals(10, coerced.get("seqNo"));
    }

    @Test
    void coercesBooleanFromYValue() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      when(entity.getProperty("active")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "active", "Y", coerced);

      assertEquals(true, coerced.get("active"));
    }

    @Test
    void coercesBooleanFromTrueString() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      when(entity.getProperty("active")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "active", "true", coerced);

      assertEquals(true, coerced.get("active"));
    }

    @Test
    void coercesBooleanFromNValue() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      when(entity.getProperty("active")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "active", "N", coerced);

      assertEquals(false, coerced.get("active"));
    }

    @Test
    void skipsNonPrimitiveProperty() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(false);
      when(entity.getProperty("partner")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "partner", "some-id", coerced);

      assertTrue(coerced.isEmpty());
    }

    @Test
    void skipsUnknownProperty() {
      when(entity.getProperty("unknown")).thenReturn(null);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "unknown", "val", coerced);

      assertTrue(coerced.isEmpty());
    }

    @Test
    void emptyStringIsNotCoerced() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) BigDecimal.class);
      when(entity.getProperty("amount")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "amount", "", coerced);

      assertTrue(coerced.isEmpty());
    }
  }

  @Nested
  @DisplayName("wrapForSmartclient")
  class WrapForSmartclient {
    @Test
    void wrapsBodyWithEntityNameAndRecordId() throws Exception {
      JSONObject body = new JSONObject();
      body.put("name", "Test");

      // Entity not found won't prevent wrapping
      when(modelProvider.getEntity("TestEntity")).thenReturn(null);

      String result = NeoTypeCoercionHelper.wrapForSmartclient(body, "TestEntity", "rec-123");
      JSONObject wrapper = new JSONObject(result);
      JSONObject data = wrapper.getJSONObject(JsonConstants.DATA);

      assertEquals("TestEntity", data.getString(JsonConstants.ENTITYNAME));
      assertEquals("rec-123", data.getString(JsonConstants.ID));
      assertEquals("Test", data.getString("name"));
    }

    @Test
    void nullRecordIdSetsNewIndicator() throws Exception {
      when(modelProvider.getEntity("TestEntity")).thenReturn(null);

      String result = NeoTypeCoercionHelper.wrapForSmartclient(new JSONObject(), "TestEntity", null);
      JSONObject wrapper = new JSONObject(result);
      JSONObject data = wrapper.getJSONObject(JsonConstants.DATA);

      assertTrue(data.getBoolean(JsonConstants.NEW_INDICATOR));
      assertFalse(data.has(JsonConstants.ID));
    }

    @Test
    void nullBodyUsesEmptyObject() throws Exception {
      when(modelProvider.getEntity("TestEntity")).thenReturn(null);

      String result = NeoTypeCoercionHelper.wrapForSmartclient(null, "TestEntity", "id-1");
      JSONObject wrapper = new JSONObject(result);
      assertTrue(wrapper.has(JsonConstants.DATA));
    }
  }

  @Nested
  @DisplayName("ParentFilter")
  class ParentFilterTests {
    @Test
    void resolveForStringApiSubstitutesPlaceholder() {
      NeoTypeCoercionHelper.ParentFilter filter = new NeoTypeCoercionHelper.ParentFilter(
          "e.partner.id = :neoParentId", "ABC123");
      String resolved = filter.resolveForStringApi();
      assertEquals("e.partner.id = 'ABC123'", resolved);
    }

    @Test
    void hqlAndParamValueAreAccessible() {
      NeoTypeCoercionHelper.ParentFilter filter = new NeoTypeCoercionHelper.ParentFilter(
          "e.order.id = :neoParentId", "ORDER-1");
      assertEquals("e.order.id = :neoParentId", filter.hql);
      assertEquals("ORDER-1", filter.paramValue);
    }
  }

  @Nested
  @DisplayName("buildParentWhereClause")
  class BuildParentWhereClause {
    @Test
    void nullTabReturnsNull() {
      assertNull(NeoTypeCoercionHelper.buildParentWhereClause(null, "parent-id"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "DROP TABLE;", "' OR 1=1--", "abc<script>" })
    void invalidParentIdReturnsNull(String badId) {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      assertNull(NeoTypeCoercionHelper.buildParentWhereClause(tab, badId));
    }
  }
}
