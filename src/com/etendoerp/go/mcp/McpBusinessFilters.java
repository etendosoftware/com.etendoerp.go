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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure (DAL-free) building blocks for the business-query semantics that {@code neo_list} exposes
 * on top of the plain {@code key=value} equality filters (IMP-3).
 *
 * <p>The range-operator building blocks live here, emitted as HQL fragments that
 * {@link McpToolRouterSupport#buildWhereFromFilters} splices into the fetch where clause:
 * <ul>
 *   <li><b>Range operators</b> — {@code gt / lt / gte / lte / between} on any real property.
 *       {@link #operatorToSql(String)} maps the operator key to its SQL symbol and
 *       {@link #formatHqlValue(Class, boolean, Object)} renders the value for the property's Java
 *       type (numbers unquoted, booleans as {@code true/false}, dates via
 *       {@code to_date(...,'YYYY-MM-DD')}, everything else quoted &amp; escaped).</li>
 * </ul>
 * Named document statuses (the {@code {status: "<name>"}} shape) are no longer hardcoded here — they
 * are hand-authored per spec and resolved by {@link McpNamedFilters}.
 *
 * <p>Everything here is deliberately static and free of DAL/model access so it can be unit-tested
 * without a running Openbravo instance. Property <em>resolution</em> (column&nbsp;→&nbsp;property)
 * stays in {@link McpToolRouterSupport}, which owns the {@link org.openbravo.base.model.Entity}.
 */
final class McpBusinessFilters {

  private McpBusinessFilters() {
  }

  /** Filter key that selects a named business status instead of a column match. */
  static final String STATUS_KEY = "status";

  /** {@code between} range operator — its value is a two-element {@code [from, to]} array. */
  static final String OP_BETWEEN = "between";

  /** Range-operator keys mapped to their SQL comparison symbol (insertion order = doc order). */
  private static final Map<String, String> OPERATORS = new LinkedHashMap<>();

  static {
    OPERATORS.put("gt", ">");
    OPERATORS.put("gte", ">=");
    OPERATORS.put("lt", "<");
    OPERATORS.put("lte", "<=");
  }

  /**
   * @return the SQL symbol for a range-operator key ({@code gt/gte/lt/lte}), or {@code null} if the
   *     key is not a simple comparison operator (e.g. {@code between}, handled separately).
   */
  static String operatorToSql(String operatorKey) {
    return operatorKey == null ? null : OPERATORS.get(operatorKey);
  }

  /** @return {@code true} if {@code key} is any recognized range operator (including {@code between}). */
  static boolean isRangeOperator(String key) {
    return OP_BETWEEN.equals(key) || OPERATORS.containsKey(key);
  }

  /**
   * Render a scalar value as an HQL literal appropriate for the target property's Java type.
   * Numbers stay unquoted, booleans become {@code true}/{@code false}, dates are wrapped in
   * {@code to_date(...,'YYYY-MM-DD')} (Openbravo registers {@code to_date} as an HQL function), and
   * anything else — including foreign keys — is single-quoted with quotes escaped.
   *
   * @param javaType  the property's Java type ({@link org.openbravo.base.model.Property#getPrimitiveObjectType()}); may be {@code null}
   * @param primitive whether the property is primitive (a non-primitive is a FK compared by id string)
   * @param value     the raw filter value
   * @return the HQL literal, never {@code null}
   */
  static String formatHqlValue(Class<?> javaType, boolean primitive, Object value) {
    String raw = value == null ? "" : String.valueOf(value);
    if (primitive && javaType != null) {
      if (Number.class.isAssignableFrom(javaType)) {
        return sanitizeNumber(raw);
      }
      if (Boolean.class.equals(javaType)) {
        return Boolean.parseBoolean(raw) ? "true" : "false";
      }
      if (Date.class.isAssignableFrom(javaType)) {
        return "to_date('" + sanitizeDate(raw) + "','YYYY-MM-DD')";
      }
    }
    return quote(raw);
  }

  /** Quote and escape a value as an HQL string literal. */
  private static String quote(String raw) {
    return "'" + raw.replace("'", "''") + "'";
  }

  /**
   * Keep only a numeric literal; if the value is not a clean number fall back to a quoted string so
   * a malformed input can never inject HQL.
   */
  private static String sanitizeNumber(String raw) {
    return raw.matches("-?\\d+(\\.\\d+)?") ? raw : quote(raw);
  }

  /** Reject anything that is not an ISO-ish date so the {@code to_date} literal stays safe. */
  private static String sanitizeDate(String raw) {
    return raw.replaceAll("[^0-9-]", "");
  }
}
