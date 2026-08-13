/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.data.FieldProvider;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.Validation;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link ComboReferenceSelectorExecutor}.
 */
class ComboReferenceSelectorExecutorTest {

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<ComboReferenceSelectorExecutor> constructor = ComboReferenceSelectorExecutor.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }


  /** FK reference id (TableDir=19) recognised by NeoSelectorService.isFkReference. */
  private static final String FK_REF_ID = "19";
  /** Non-FK reference id that isFkReference rejects. */
  private static final String NON_FK_REF_ID = "999";

  // ---------------------------------------------------------------------------
  // Reflection helpers for private methods
  // ---------------------------------------------------------------------------

  private static Object invokePrivateStatic(String methodName, Class<?>[] paramTypes,
      Object... args) throws Exception {
    Method method = ComboReferenceSelectorExecutor.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  private static Boolean invokeHasSqlValidationRule(Column column) throws Exception {
    return (Boolean) invokePrivateStatic("hasSqlValidationRule",
        new Class<?>[] { Column.class }, column);
  }

  private static Field invokeResolveComboField(SFEntity entity, Column column) throws Exception {
    return (Field) invokePrivateStatic("resolveComboField",
        new Class<?>[] { SFEntity.class, Column.class }, entity, column);
  }

  private static NeoResponse invokeBuildResponse(FieldProvider[] rows, int limit, int offset)
      throws Exception {
    return (NeoResponse) invokePrivateStatic("buildResponse",
        new Class<?>[] { FieldProvider[].class, int.class, int.class }, rows, limit, offset);
  }

  // ---------------------------------------------------------------------------
  // Helper: build a mock Column with optional SQL validation rule
  // ---------------------------------------------------------------------------

  private static Column mockColumnWithValidation(String validationType) {
    Column column = mock(Column.class);
    if (validationType != null) {
      Validation validation = mock(Validation.class);
      when(validation.getType()).thenReturn(validationType);
      when(column.getValidation()).thenReturn(validation);
    } else {
      when(column.getValidation()).thenReturn(null);
    }
    return column;
  }

  private static SFEntity mockEntityWithTab() {
    SFEntity entity = mock(SFEntity.class);
    Tab tab = mock(Tab.class);
    doReturn("TAB-001").when(tab).getId();
    when(entity.getADTab()).thenReturn(tab);
    return entity;
  }

  // ---------------------------------------------------------------------------
  // shouldUseCoreComboSelector
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("shouldUseCoreComboSelector")
  class ShouldUseCoreComboSelector {

    @Test
    @DisplayName("returns false when sourceEntity is null")
    void returnsFalseWhenEntityNull() {
      Column column = mockColumnWithValidation("S");
      assertFalse(ComboReferenceSelectorExecutor.shouldUseCoreComboSelector(null, column, FK_REF_ID));
    }

    @Test
    @DisplayName("returns false when refId is not an FK reference")
    void returnsFalseWhenNotFkReference() {
      try (MockedStatic<NeoSelectorService> neo = mockStatic(NeoSelectorService.class)) {
        neo.when(() -> NeoSelectorService.isFkReference(NON_FK_REF_ID)).thenReturn(false);

        SFEntity entity = mockEntityWithTab();
        Column column = mockColumnWithValidation("S");
        assertFalse(
            ComboReferenceSelectorExecutor.shouldUseCoreComboSelector(entity, column, NON_FK_REF_ID));
      }
    }

    @Test
    @DisplayName("returns false when column has no SQL validation rule")
    void returnsFalseWhenNoSqlValidation() {
      try (MockedStatic<NeoSelectorService> neo = mockStatic(NeoSelectorService.class)) {
        neo.when(() -> NeoSelectorService.isFkReference(FK_REF_ID)).thenReturn(true);

        SFEntity entity = mockEntityWithTab();
        Column column = mockColumnWithValidation(null);
        assertFalse(
            ComboReferenceSelectorExecutor.shouldUseCoreComboSelector(entity, column, FK_REF_ID));
      }
    }

    @Test
    @DisplayName("returns false when combo field cannot be resolved")
    @SuppressWarnings("unchecked")
    void returnsFalseWhenNoComboField() {
      try (MockedStatic<NeoSelectorService> neo = mockStatic(NeoSelectorService.class);
           MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {

        neo.when(() -> NeoSelectorService.isFkReference(FK_REF_ID)).thenReturn(true);

        OBDal obDal = mock(OBDal.class);
        obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

        OBCriteria<Field> criteria = mock(OBCriteria.class);
        when(obDal.createCriteria(Field.class)).thenReturn(criteria);
        when(criteria.list()).thenReturn(Collections.emptyList());

        SFEntity entity = mockEntityWithTab();
        Column column = mockColumnWithValidation("S");
        assertFalse(
            ComboReferenceSelectorExecutor.shouldUseCoreComboSelector(entity, column, FK_REF_ID));
      }
    }

    @Test
    @DisplayName("returns true when all conditions are met")
    @SuppressWarnings("unchecked")
    void returnsTrueWhenAllConditionsMet() {
      try (MockedStatic<NeoSelectorService> neo = mockStatic(NeoSelectorService.class);
           MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {

        neo.when(() -> NeoSelectorService.isFkReference(FK_REF_ID)).thenReturn(true);

        OBDal obDal = mock(OBDal.class);
        obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

        OBCriteria<Field> criteria = mock(OBCriteria.class);
        when(obDal.createCriteria(Field.class)).thenReturn(criteria);

        Field field = mock(Field.class);
        when(criteria.list()).thenReturn(List.of(field));

        SFEntity entity = mockEntityWithTab();
        Column column = mockColumnWithValidation("S");
        assertTrue(
            ComboReferenceSelectorExecutor.shouldUseCoreComboSelector(entity, column, FK_REF_ID));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // hasSqlValidationRule (private, tested via reflection)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("hasSqlValidationRule")
  class HasSqlValidationRule {

    @Test
    @DisplayName("returns false for null column")
    void returnsFalseForNullColumn() throws Exception {
      assertFalse(invokeHasSqlValidationRule(null));
    }

    @Test
    @DisplayName("returns false when column has no validation")
    void returnsFalseWhenNoValidation() throws Exception {
      Column column = mockColumnWithValidation(null);
      assertFalse(invokeHasSqlValidationRule(column));
    }

    @Test
    @DisplayName("returns false when validation type is not S")
    void returnsFalseWhenWrongType() throws Exception {
      Column column = mockColumnWithValidation("C");
      assertFalse(invokeHasSqlValidationRule(column));
    }

    @Test
    @DisplayName("returns true when validation type is S")
    void returnsTrueForTypeS() throws Exception {
      Column column = mockColumnWithValidation("S");
      assertTrue(invokeHasSqlValidationRule(column));
    }

    @Test
    @DisplayName("returns true when validation type is lowercase s (case insensitive)")
    void returnsTrueForLowercaseS() throws Exception {
      Column column = mockColumnWithValidation("s");
      assertTrue(invokeHasSqlValidationRule(column));
    }
  }

  // ---------------------------------------------------------------------------
  // resolveComboField (private, tested via reflection)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveComboField")
  class ResolveComboField {

    @Test
    @DisplayName("returns null when sourceEntity is null")
    void returnsNullWhenEntityNull() throws Exception {
      Column column = mock(Column.class);
      assertNull(invokeResolveComboField(null, column));
    }

    @Test
    @DisplayName("returns null when column is null")
    void returnsNullWhenColumnNull() throws Exception {
      SFEntity entity = mockEntityWithTab();
      assertNull(invokeResolveComboField(entity, null));
    }

    @Test
    @DisplayName("returns null when entity has no AD tab")
    void returnsNullWhenNoTab() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      when(entity.getADTab()).thenReturn(null);
      Column column = mock(Column.class);
      assertNull(invokeResolveComboField(entity, column));
    }

    @Test
    @DisplayName("returns null when no matching field found")
    @SuppressWarnings("unchecked")
    void returnsNullWhenFieldNotFound() throws Exception {
      try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        OBDal obDal = mock(OBDal.class);
        obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

        OBCriteria<Field> criteria = mock(OBCriteria.class);
        when(obDal.createCriteria(Field.class)).thenReturn(criteria);
        when(criteria.list()).thenReturn(Collections.emptyList());

        SFEntity entity = mockEntityWithTab();
        Column column = mock(Column.class);
        assertNull(invokeResolveComboField(entity, column));
      }
    }

    @Test
    @DisplayName("returns field when matching field exists")
    @SuppressWarnings("unchecked")
    void returnsFieldWhenFound() throws Exception {
      try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        OBDal obDal = mock(OBDal.class);
        obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

        OBCriteria<Field> criteria = mock(OBCriteria.class);
        when(obDal.createCriteria(Field.class)).thenReturn(criteria);

        Field expectedField = mock(Field.class);
        when(criteria.list()).thenReturn(List.of(expectedField));

        SFEntity entity = mockEntityWithTab();
        Column column = mock(Column.class);
        Field result = invokeResolveComboField(entity, column);
        assertNotNull(result);
        assertEquals(expectedField, result);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // buildResponse (private, tested via reflection)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("buildResponse")
  class BuildResponse {

    private FieldProvider mockRow(String id, String name) {
      FieldProvider row = mock(FieldProvider.class);
      when(row.getField("ID")).thenReturn(id);
      when(row.getField("NAME")).thenReturn(name);
      return row;
    }

    @Test
    @DisplayName("returns empty items for zero rows")
    void emptyRowsReturnsEmptyItems() throws Exception {
      NeoResponse response = invokeBuildResponse(new FieldProvider[0], 10, 0);

      assertEquals(200, response.getHttpStatus());
      JSONObject body = response.getBody();
      assertEquals(0, body.getJSONArray("items").length());
      assertEquals(0, body.getInt("totalCount"));
      assertFalse(body.getBoolean("hasMore"));
    }

    @Test
    @DisplayName("maps rows to JSON with id and label within limit")
    void rowsWithinLimitMappedCorrectly() throws Exception {
      FieldProvider[] rows = {
          mockRow("R1", "Row One"),
          mockRow("R2", "Row Two")
      };
      NeoResponse response = invokeBuildResponse(rows, 10, 0);

      assertEquals(200, response.getHttpStatus());
      JSONObject body = response.getBody();
      JSONArray items = body.getJSONArray("items");
      assertEquals(2, items.length());
      assertEquals("R1", items.getJSONObject(0).getString("id"));
      assertEquals("Row One", items.getJSONObject(0).getString("label"));
      assertEquals("R2", items.getJSONObject(1).getString("id"));
      assertEquals("Row Two", items.getJSONObject(1).getString("label"));
      assertEquals(2, body.getInt("totalCount"));
      assertFalse(body.getBoolean("hasMore"));
    }

    @Test
    @DisplayName("hasMore is true when rows exceed limit")
    void rowsExceedingLimitSetsHasMore() throws Exception {
      // limit=2, but 3 rows returned means hasMore=true
      FieldProvider[] rows = {
          mockRow("R1", "One"),
          mockRow("R2", "Two"),
          mockRow("R3", "Three")
      };
      int limit = 2;
      int offset = 0;
      NeoResponse response = invokeBuildResponse(rows, limit, offset);

      JSONObject body = response.getBody();
      // Only first 2 rows included (visibleRows = limit when hasMore)
      assertEquals(2, body.getJSONArray("items").length());
      // totalCount = offset + limit + 1 = 0 + 2 + 1 = 3
      assertEquals(3, body.getInt("totalCount"));
      assertTrue(body.getBoolean("hasMore"));
    }

    @Test
    @DisplayName("totalCount accounts for offset when rows exceed limit")
    void totalCountWithOffset() throws Exception {
      FieldProvider[] rows = {
          mockRow("R1", "One"),
          mockRow("R2", "Two"),
          mockRow("R3", "Three")
      };
      int limit = 2;
      int offset = 10;
      NeoResponse response = invokeBuildResponse(rows, limit, offset);

      JSONObject body = response.getBody();
      // hasMore = true (3 > 2), totalCount = offset + limit + 1 = 10 + 2 + 1 = 13
      assertEquals(13, body.getInt("totalCount"));
      assertTrue(body.getBoolean("hasMore"));
      assertEquals(2, body.getJSONArray("items").length());
    }

    @Test
    @DisplayName("totalCount accounts for offset when rows within limit")
    void totalCountWithOffsetNoMore() throws Exception {
      FieldProvider[] rows = {
          mockRow("R1", "One"),
          mockRow("R2", "Two")
      };
      int limit = 5;
      int offset = 10;
      NeoResponse response = invokeBuildResponse(rows, limit, offset);

      JSONObject body = response.getBody();
      // hasMore = false (2 <= 5), totalCount = offset + visibleRows = 10 + 2 = 12
      assertEquals(12, body.getInt("totalCount"));
      assertFalse(body.getBoolean("hasMore"));
    }
  }
}
