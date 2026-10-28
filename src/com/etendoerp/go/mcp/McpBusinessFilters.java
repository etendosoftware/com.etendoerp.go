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
 * Pure (DAL-free) building blocks for the business-query semantics that {@code etendo_list} exposes
 * on top of the plain {@code key=value} equality filters (IMP-3).
 *
 * <p>The range-operator building blocks live here, emitted as HQL fragments that
 * {@link McpToolRouterSupport#buildWhereFromFilters} splices into the fetch where clause:
 * <ul>
 *   <li><b>Range operators</b> — {@code gt / lt / gte / lte / between} on any real property.
 *       {@link #operatorToSql(String)} maps the operator key to its SQL symbol and
 *       {@link #formatHqlValue(String, Class, boolean, Object)} renders the value for the property's Java
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

  /**
   * {@code contains} text operator (ETP-5852) — case-insensitive substring match on a text
   * property; its value is the text to look for.
   */
  static final String OP_CONTAINS = "contains";

  /** {@code in} set operator (ETP-5852) — its value is a non-empty array of exact values. */
  static final String OP_IN = "in";

  /** Range-operator keys mapped to their SQL comparison symbol (insertion order = doc order). */
  /** ETP-5914: the type names an invalid filter value is reported against. */
  static final String TYPE_NUMBER = "number";
  static final String TYPE_DATE = "date";
  static final String TYPE_BOOLEAN = "boolean";
  private static final String NUMBER_PATTERN = "-?\\d+(\\.\\d+)?";

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

  /**
   * Every recognized operator key, for the {@code available} list of an unknown-operator refusal
   * (ETP-5184). {@code between} is appended because it is handled outside {@link #OPERATORS} but
   * is just as valid a thing for a caller to have meant.
   *
   * @return the operator keys in documentation order, then {@code between}, {@code contains}, {@code in}
   */
  static java.util.List<String> operatorKeys() {
    java.util.List<String> keys = new java.util.ArrayList<>(OPERATORS.keySet());
    keys.add(OP_BETWEEN);
    keys.add(OP_CONTAINS);
    keys.add(OP_IN);
    return keys;
  }

  /**
   * HQL for {@code contains}: {@code upper(path) like upper('%text%') escape '\'}. The text's own
   * {@code \ % _} are escaped so they match literally instead of acting as wildcards, and the
   * literal is quoted like every other value, so the caller's text can neither widen the match
   * nor inject HQL.
   */
  static String containsCondition(String path, Object value) {
    String text = value == null ? "" : String.valueOf(value);
    String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "upper(" + path + ") like upper(" + quote("%" + escaped + "%") + ") escape '\\'";
  }

  /**
   * HQL for {@code in}: {@code path in (v1, v2, ...)}, each value rendered by
   * {@link #formatHqlValue} for the property's type, exactly like an equality filter.
   */
  static String inCondition(String key, String path, Class<?> javaType, boolean primitive,
      java.util.List<Object> values) {
    StringBuilder sb = new StringBuilder(path).append(" in (");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(formatHqlValue(key, javaType, primitive, values.get(i)));
    }
    return sb.append(')').toString();
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
   * <p>ETP-5914: a value that is not of the property's type is refused with a 422 naming the filter,
   * the expected type and the value, instead of reaching the query: a quoted non-number failed it
   * with a 500, a non-date was cleaned to an empty date that dropped the filter, and any non-"true"
   * boolean was read as {@code false}.</p>
   *
   * @param key       the filter key, named by the refusal
   * @param javaType  the property's Java type ({@link org.openbravo.base.model.Property#getPrimitiveObjectType()}); may be {@code null}
   * @param primitive whether the property is primitive (a non-primitive is a FK compared by id string)
   * @param value     the raw filter value
   * @return the HQL literal, never {@code null}
   * @throws McpRoutingException {@code 422 validation_error} for a value of the wrong type
   */
  static String formatHqlValue(String key, Class<?> javaType, boolean primitive, Object value) {
    String raw = value == null ? "" : String.valueOf(value).trim();
    if (!primitive || javaType == null) {
      return quote(raw);
    }
    if (Number.class.isAssignableFrom(javaType)) {
      return number(key, raw, value);
    }
    if (Boolean.class.equals(javaType)) {
      return bool(key, raw, value);
    }
    if (Date.class.isAssignableFrom(javaType)) {
      return "to_date('" + isoDate(key, raw, value) + "','YYYY-MM-DD')";
    }
    return quote(raw);
  }

  private static String number(String key, String raw, Object value) {
    if (!raw.matches(NUMBER_PATTERN)) {
      throw McpRoutingException.invalidFilterValue(key, TYPE_NUMBER, value);
    }
    return raw;
  }

  private static String bool(String key, String raw, Object value) {
    if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
      throw McpRoutingException.invalidFilterValue(key, TYPE_BOOLEAN, value);
    }
    return raw.toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * ETP-5914: the {@code YYYY-MM-DD} part of an ISO date or date-time, validated as a real date. A
   * value that is not one is refused rather than cleaned: stripping it to its digits used to turn
   * {@code "ayer"} into an empty date that silently dropped the filter. Parsed by
   * {@code java.time}, so a well-shaped but impossible date (2026-02-30) is refused too.
   */
  private static String isoDate(String key, String raw, Object value) {
    try {
      if (raw.indexOf('T') < 0) {
        return java.time.LocalDate.parse(raw).toString();
      }
      return java.time.LocalDate.from(
          java.time.format.DateTimeFormatter.ISO_DATE_TIME.parse(raw)).toString();
    } catch (java.time.DateTimeException e) {
      throw McpRoutingException.invalidFilterValue(key, TYPE_DATE, value);
    }
  }

  /** Quote and escape a value as an HQL string literal. */
  private static String quote(String raw) {
    return "'" + raw.replace("'", "''") + "'";
  }

}
