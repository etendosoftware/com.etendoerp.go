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
    void coercesBooleanCaseInsensitively() {
      // ETP-4793: this path used to require an uppercase "Y" while the MCP coercer accepted "y",
      // so the same payload coerced differently depending on which surface it arrived through.
      // Both now share NeoBooleanFormat.
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      when(entity.getProperty("active")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "active", "y", coerced);

      assertEquals(true, coerced.get("active"));
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

    /**
     * ETP-4793 / IMP-16. These four pin the date branch. It matters more than it looks: the DAL
     * parses dates leniently, so a {@code dd-MM-yyyy} value reaching persistence is stored as
     * year 0012 instead of failing — this branch is the last place it can be repaired, and it
     * fires even when the caller sent no date, because {@code injectMandatoryDefaults} resolves
     * the server default (always {@code dd-MM-yyyy}, from core's {@code DateTimeData.today})
     * immediately before coercion runs.
     */
    @Test
    @DisplayName("normalizes a dd-MM-yyyy date to ISO — the year-0012 regression")
    void coercesUiPatternDateToIso() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDate()).thenReturn(true);
      when(entity.getProperty("orderDate")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "orderDate", "06-08-2026", coerced);

      assertEquals("2026-08-06", coerced.get("orderDate"));
    }

    @Test
    @DisplayName("leaves an already-ISO date out of the coerced map")
    void isoDateIsNotRewritten() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDate()).thenReturn(true);
      when(entity.getProperty("orderDate")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "orderDate", "2026-08-06", coerced);

      assertTrue(coerced.isEmpty());
    }

    @Test
    @DisplayName("a datetime property keeps its time component")
    void datetimeKeepsTime() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDatetime()).thenReturn(true);
      when(entity.getProperty("movementDate")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "movementDate",
          "2026-08-06 18:55:31.567837+00", coerced);

      assertEquals("2026-08-06T18:55:31", coerced.get("movementDate"));
    }

    @Test
    @DisplayName("an unrecognized date shape is passed through verbatim, not blanked")
    void unrecognizedDateIsLeftAlone() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDate()).thenReturn(true);
      when(entity.getProperty("orderDate")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "orderDate", "06/08/2026", coerced);

      // Nothing is written, so the caller's original value survives untouched. Substituting a
      // guessed date would be worse than the lenient parser this branch exists to protect.
      assertTrue(coerced.isEmpty());
    }

    /**
     * A time-of-day property is a {@code java.util.Date} too, and its JSON value looks like a
     * datetime — so a gate written on the Java type alone would rewrite it. It must not:
     * {@code JsonToDataConverter} keeps only the part after the {@code T} for these properties
     * and supplies the calendar day itself, so producing {@code yyyy-MM-dd} here would delete
     * the only half that is read.
     */
    @Test
    @DisplayName("a time-of-day property is left untouched — the gate is the domain type")
    void timePropertyIsNotTouched() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isTimestamp()).thenReturn(true);
      when(entity.getProperty("startTime")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "startTime", "2026-08-06T14:30:00", coerced);

      assertTrue(coerced.isEmpty());
    }

    @Test
    @DisplayName("an absolute-datetime property is left untouched, time included")
    void absoluteDateTimePropertyIsNotTouched() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isAbsoluteDateTime()).thenReturn(true);
      when(entity.getProperty("created")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "created", "06-08-2026 14:30:00", coerced);

      assertTrue(coerced.isEmpty());
    }

    /**
     * A non-zero offset already reaches the DAL correctly — {@code convertFromXSDToJavaFormat}
     * rewrites {@code +02:00} to {@code +0200} and the lenient datetime parser honours it. The
     * canonical form has nowhere to put an offset, so dropping it would shift the instant by two
     * hours: the fix would become the corruption. Refusing the conversion is the only safe move.
     */
    @Test
    @DisplayName("a datetime with a non-zero offset is refused, never shifted to UTC")
    void nonZeroOffsetIsRefused() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDatetime()).thenReturn(true);
      when(entity.getProperty("movementDate")).thenReturn(prop);

      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(entity, "movementDate", "2026-08-06T14:30:00+02:00",
          coerced);

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
