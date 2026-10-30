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
 * business-query filters ({@code etendo_list} range operators + named document statuses).
 *
 * @covers com.etendoerp.go.mcp.McpBusinessFilters
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

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> validValues() {
      return java.util.stream.Stream.of(
          org.junit.jupiter.params.provider.Arguments.of(BigDecimal.class, 1000, "1000"),
          org.junit.jupiter.params.provider.Arguments.of(Long.class, "12.5", "12.5"),
          org.junit.jupiter.params.provider.Arguments.of(BigDecimal.class, " -3 ", "-3"),
          org.junit.jupiter.params.provider.Arguments.of(Boolean.class, true, "true"),
          org.junit.jupiter.params.provider.Arguments.of(Boolean.class, "FALSE", "false"),
          org.junit.jupiter.params.provider.Arguments.of(Date.class, "2026-07-31",
              "to_date('2026-07-31','YYYY-MM-DD')"),
          org.junit.jupiter.params.provider.Arguments.of(Date.class, "2026-07-31T10:15:00",
              "to_date('2026-07-31','YYYY-MM-DD')"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} {1}")
    @org.junit.jupiter.params.provider.MethodSource("validValues")
    @DisplayName("renders a value of the property's type")
    void rendersValidValues(Class<?> type, Object value, String expected) {
      assertEquals(expected, McpBusinessFilters.formatHqlValue("f", type, true, value));
    }

    /** ETP-5914: these reached the query (500) or were cleaned into a filter that matched all. */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidValues() {
      return java.util.stream.Stream.of(
          org.junit.jupiter.params.provider.Arguments.of(BigDecimal.class, "abc", "number"),
          org.junit.jupiter.params.provider.Arguments.of(BigDecimal.class, "1=1", "number"),
          org.junit.jupiter.params.provider.Arguments.of(Boolean.class, "abc", "boolean"),
          org.junit.jupiter.params.provider.Arguments.of(Boolean.class, "Y", "boolean"),
          org.junit.jupiter.params.provider.Arguments.of(Date.class, "ayer", "date"),
          org.junit.jupiter.params.provider.Arguments.of(Date.class, "2026-02-30", "date"),
          org.junit.jupiter.params.provider.Arguments.of(Date.class, "31/07/2026", "date"),
          org.junit.jupiter.params.provider.Arguments.of(Date.class, "2026-07-31'); drop", "date"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} {1}")
    @org.junit.jupiter.params.provider.MethodSource("invalidValues")
    @DisplayName("refuses a value of another type with a 422 naming field, type and value")
    void refusesInvalidValues(Class<?> type, Object value, String expectedType) {
      McpRoutingException e = org.junit.jupiter.api.Assertions.assertThrows(
          McpRoutingException.class,
          () -> McpBusinessFilters.formatHqlValue("grandTotalAmount", type, true, value));

      assertEquals(McpConstants.ERROR_VALIDATION, e.getErrorCode());
      assertTrue(e.getMessage().contains("'grandTotalAmount' expects a " + expectedType),
          e.getMessage());
      assertTrue(e.getMessage().contains(String.valueOf(value)), e.getMessage());
    }

    @Test
    @DisplayName("an 'in' list with one bad element is refused as a whole")
    void inListIsValidatedPerElement() {
      org.junit.jupiter.api.Assertions.assertThrows(McpRoutingException.class,
          () -> McpBusinessFilters.inCondition("qty", "e.qty", BigDecimal.class, true,
              java.util.List.of(1, "x")));
      assertEquals("e.qty in (1, 2)", McpBusinessFilters.inCondition("qty", "e.qty",
          BigDecimal.class, true, java.util.List.of(1, 2)));
    }

    @Test
    @DisplayName("quotes and escapes strings and foreign keys")
    void stringsAndForeignKeys() {
      assertEquals("'ACME'", McpBusinessFilters.formatHqlValue("f", String.class, true, "ACME"));
      // a FK (non-primitive) is compared by id string regardless of the declared type
      assertEquals("'ABC123'",
          McpBusinessFilters.formatHqlValue("f", BigDecimal.class, false, "ABC123"));
    }

    @Test
    @DisplayName("escapes embedded single quotes to prevent HQL injection")
    void escapesQuotes() {
      assertEquals("'O''Brien'",
          McpBusinessFilters.formatHqlValue("f", String.class, true, "O'Brien"));
    }
  }

  @Nested
  @DisplayName("contains / in (ETP-5852)")
  class ContainsAndIn {

    @Test
    @DisplayName("contains is a case-insensitive substring match")
    void containsIsCaseInsensitiveSubstring() {
      assertEquals("upper(e.name) like upper('%ern%') escape '\\'",
          McpBusinessFilters.containsCondition("e.name", "ern"));
    }

    @Test
    @DisplayName("contains matches wildcards and quotes literally, never as HQL")
    void containsEscapesWildcardsAndQuotes() {
      assertEquals("upper(e.name) like upper('%50\\% o''Brien\\_x%') escape '\\'",
          McpBusinessFilters.containsCondition("e.name", "50% o'Brien_x"));
    }

    @Test
    @DisplayName("in renders each value for the property's type")
    void inRendersTypedValues() {
      assertEquals("e.searchKey in ('SK-1', 'it''s')", McpBusinessFilters.inCondition(
          "searchKey", "e.searchKey", String.class, true, java.util.List.of("SK-1", "it's")));
      assertEquals("e.qty in (1, 2.5)", McpBusinessFilters.inCondition(
          "qty", "e.qty", BigDecimal.class, true, java.util.List.of(1, "2.5")));
    }

    @Test
    @DisplayName("an unknown-operator refusal lists contains and in")
    void operatorKeysListContainsAndIn() {
      assertTrue(McpBusinessFilters.operatorKeys()
          .containsAll(java.util.List.of("gt", "gte", "lt", "lte", "between", "contains", "in")));
    }
  }
}
