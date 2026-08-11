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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpBusinessFilters} — the pure, DAL-free building blocks of the IMP-3
 * business-query filters ({@code neo_list} range operators + named document statuses).
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpBusinessFilters")
class McpBusinessFiltersTest {

  @Nested
  @DisplayName("operatorToSql")
  class OperatorToSql {

    @Test
    @DisplayName("maps the four comparison operators to their SQL symbols")
    void mapsComparisonOperators() {
      assertEquals(">", McpBusinessFilters.operatorToSql("gt"));
      assertEquals(">=", McpBusinessFilters.operatorToSql("gte"));
      assertEquals("<", McpBusinessFilters.operatorToSql("lt"));
      assertEquals("<=", McpBusinessFilters.operatorToSql("lte"));
    }

    @Test
    @DisplayName("returns null for between (handled separately) and unknown/null keys")
    void returnsNullForNonComparisonKeys() {
      assertNull(McpBusinessFilters.operatorToSql("between"));
      assertNull(McpBusinessFilters.operatorToSql("contains"));
      assertNull(McpBusinessFilters.operatorToSql(null));
    }

    @Test
    @DisplayName("isRangeOperator recognizes all operators including between")
    void isRangeOperatorRecognizesAll() {
      assertTrue(McpBusinessFilters.isRangeOperator("gt"));
      assertTrue(McpBusinessFilters.isRangeOperator("between"));
      assertFalse(McpBusinessFilters.isRangeOperator("status"));
    }
  }

  @Nested
  @DisplayName("formatHqlValue")
  class FormatHqlValue {

    @Test
    @DisplayName("renders numbers unquoted")
    void numbersUnquoted() {
      assertEquals("1000", McpBusinessFilters.formatHqlValue(BigDecimal.class, true, 1000));
      assertEquals("12.5", McpBusinessFilters.formatHqlValue(Long.class, true, "12.5"));
    }

    @Test
    @DisplayName("renders booleans as true/false")
    void booleans() {
      assertEquals("true", McpBusinessFilters.formatHqlValue(Boolean.class, true, true));
      assertEquals("false", McpBusinessFilters.formatHqlValue(Boolean.class, true, "false"));
    }

    @Test
    @DisplayName("wraps dates in to_date(...,'YYYY-MM-DD')")
    void dates() {
      assertEquals("to_date('2026-07-31','YYYY-MM-DD')",
          McpBusinessFilters.formatHqlValue(Date.class, true, "2026-07-31"));
    }

    @Test
    @DisplayName("quotes and escapes strings and foreign keys")
    void stringsAndForeignKeys() {
      assertEquals("'ACME'", McpBusinessFilters.formatHqlValue(String.class, true, "ACME"));
      // a FK (non-primitive) is compared by id string regardless of the declared type
      assertEquals("'ABC123'", McpBusinessFilters.formatHqlValue(String.class, false, "ABC123"));
    }

    @Test
    @DisplayName("escapes embedded single quotes to prevent HQL injection")
    void escapesQuotes() {
      assertEquals("'O''Brien'", McpBusinessFilters.formatHqlValue(String.class, true, "O'Brien"));
    }

    @Test
    @DisplayName("a non-numeric value on a numeric property is quoted, never inlined raw")
    void malformedNumberIsQuoted() {
      assertEquals("'1=1'", McpBusinessFilters.formatHqlValue(BigDecimal.class, true, "1=1"));
    }

    @Test
    @DisplayName("strips non-date characters before building the to_date literal")
    void sanitizesDate() {
      assertEquals("to_date('2026-07-31','YYYY-MM-DD')",
          McpBusinessFilters.formatHqlValue(Date.class, true, "2026-07-31'); drop"));
    }
  }
}
