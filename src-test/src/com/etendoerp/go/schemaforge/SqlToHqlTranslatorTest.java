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

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

/**
 * Unit tests for {@link SqlToHqlTranslator}.
 */
class SqlToHqlTranslatorTest {

  // -------------------------------------------------------------------------
  // splitTopLevelAnd
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("splitTopLevelAnd")
  class SplitTopLevelAnd {

    @Test
    @DisplayName("Splits simple AND-connected clauses")
    void splitsSimpleAnd() {
      List<String> parts = SqlToHqlTranslator.splitTopLevelAnd("A = 1 AND B = 2 AND C = 3");
      assertEquals(3, parts.size());
      assertEquals("A = 1", parts.get(0));
      assertEquals("B = 2", parts.get(1));
      assertEquals("C = 3", parts.get(2));
    }

    @Test
    @DisplayName("Does not split AND inside parentheses")
    void doesNotSplitNestedAnd() {
      List<String> parts = SqlToHqlTranslator.splitTopLevelAnd(
          "A = 1 AND (B = 2 AND C = 3) AND D = 4");
      assertEquals(3, parts.size());
      assertEquals("A = 1", parts.get(0));
      assertEquals("(B = 2 AND C = 3)", parts.get(1));
      assertEquals("D = 4", parts.get(2));
    }

    @Test
    @DisplayName("Single clause without AND returns one element")
    void singleClause() {
      List<String> parts = SqlToHqlTranslator.splitTopLevelAnd("X = 1");
      assertEquals(1, parts.size());
      assertEquals("X = 1", parts.get(0));
    }

    @Test
    @DisplayName("Does not split BAND or ANDERSON (AND inside identifier)")
    void doesNotSplitAndInsideWord() {
      List<String> parts = SqlToHqlTranslator.splitTopLevelAnd("BAND = 1 AND ANDERSON = 2");
      assertEquals(2, parts.size());
      assertEquals("BAND = 1", parts.get(0));
      assertEquals("ANDERSON = 2", parts.get(1));
    }

    @Test
    @DisplayName("Handles deeply nested parentheses")
    void deeplyNested() {
      List<String> parts = SqlToHqlTranslator.splitTopLevelAnd(
          "((A AND B)) AND C");
      assertEquals(2, parts.size());
      assertEquals("((A AND B))", parts.get(0));
      assertEquals("C", parts.get(1));
    }

    @Test
    @DisplayName("Handles empty clause segments")
    void emptyInput() {
      List<String> parts = SqlToHqlTranslator.splitTopLevelAnd("");
      assertEquals(0, parts.size());
    }

    @Test
    @DisplayName("Handles lowercase 'and'")
    void lowercaseAnd() {
      List<String> parts = SqlToHqlTranslator.splitTopLevelAnd("x = 1 and y = 2");
      // AND matching is case-insensitive in the implementation
      assertEquals(2, parts.size());
    }
  }

  // -------------------------------------------------------------------------
  // unwrapSelectFromDual
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("unwrapSelectFromDual")
  class UnwrapSelectFromDual {

    @Test
    @DisplayName("Returns null for null input")
    void nullReturnsNull() {
      assertNull(SqlToHqlTranslator.unwrapSelectFromDual(null));
    }

    @Test
    @DisplayName("Returns unchanged string without SELECT FROM DUAL")
    void noChangeWithoutPattern() {
      String input = "e.name = 'test'";
      assertEquals(input, SqlToHqlTranslator.unwrapSelectFromDual(input));
    }
  }

  // -------------------------------------------------------------------------
  // simplifyConstantCaseExpressions
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("simplifyConstantCaseExpressions")
  class SimplifyConstantCase {

    @Test
    @DisplayName("Returns null for null input")
    void nullReturnsNull() {
      assertNull(SqlToHqlTranslator.simplifyConstantCaseExpressions(null));
    }

    @Test
    @DisplayName("Returns unchanged string without CASE WHEN")
    void noChangeWithoutCaseWhen() {
      String input = "e.name = 'test'";
      assertEquals(input, SqlToHqlTranslator.simplifyConstantCaseExpressions(input));
    }

    @Test
    @DisplayName("Returns unchanged string for empty input")
    void emptyInputReturnsEmpty() {
      assertEquals("", SqlToHqlTranslator.simplifyConstantCaseExpressions(""));
    }
  }

  // -------------------------------------------------------------------------
  // resolveColumnToHql
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveColumnToHql")
  class ResolveColumnToHql {

    @Test
    @DisplayName("Returns e.<columnName> when property is not found")
    void fallbackToColumnName() {
      org.openbravo.base.model.Entity entity =
          org.mockito.Mockito.mock(org.openbravo.base.model.Entity.class);
      org.mockito.Mockito.when(entity.getPropertyByColumnName("UnknownCol")).thenReturn(null);

      String result = SqlToHqlTranslator.resolveColumnToHql(entity, "UnknownCol");
      assertEquals("e.UnknownCol", result);
    }

    @Test
    @DisplayName("Returns e.<propertyName> for primitive property")
    void primitiveProperty() {
      org.openbravo.base.model.Entity entity =
          org.mockito.Mockito.mock(org.openbravo.base.model.Entity.class);
      org.openbravo.base.model.Property prop =
          org.mockito.Mockito.mock(org.openbravo.base.model.Property.class);
      org.mockito.Mockito.when(entity.getPropertyByColumnName("IsActive")).thenReturn(prop);
      org.mockito.Mockito.when(prop.isPrimitive()).thenReturn(true);
      org.mockito.Mockito.when(prop.getName()).thenReturn("active");

      String result = SqlToHqlTranslator.resolveColumnToHql(entity, "IsActive");
      assertEquals("e.active", result);
    }

    @Test
    @DisplayName("Returns e.<propertyName>.id for FK property")
    void fkProperty() {
      org.openbravo.base.model.Entity entity =
          org.mockito.Mockito.mock(org.openbravo.base.model.Entity.class);
      org.openbravo.base.model.Property prop =
          org.mockito.Mockito.mock(org.openbravo.base.model.Property.class);
      org.openbravo.base.model.Entity targetEntity =
          org.mockito.Mockito.mock(org.openbravo.base.model.Entity.class);
      org.mockito.Mockito.when(entity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);
      org.mockito.Mockito.when(prop.isPrimitive()).thenReturn(false);
      org.mockito.Mockito.when(prop.getTargetEntity()).thenReturn(targetEntity);
      org.mockito.Mockito.when(prop.getName()).thenReturn("businessPartner");

      String result = SqlToHqlTranslator.resolveColumnToHql(entity, "C_BPartner_ID");
      assertEquals("e.businessPartner.id", result);
    }
  }
}