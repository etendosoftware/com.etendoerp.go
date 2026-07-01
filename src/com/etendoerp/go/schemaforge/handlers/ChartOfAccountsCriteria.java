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

package com.etendoerp.go.schemaforge.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;

/**
 * Pure-function helpers for building the SQL WHERE clause and ORDER BY from SmartClient
 * Advanced Criteria payloads, plus the {@code applySqlParameters} utility used by
 * {@link ChartOfAccountsHandler}.
 *
 * <p>Extracted from {@link ChartOfAccountsHandler} to keep method count within the
 * Sonar S1448 limit (≤35 methods per class).
 */
class ChartOfAccountsCriteria {

  private static final String DEFAULT_LIST_ORDER_BY = "value ASC";

  /** JSON key name for the criterion value AND the SQL column name for {@code C_ElementValue.Value}. */
  private static final String JSON_KEY_CRITERION_VALUE = "value";

  private static final String OP_EQUALS = "equals";
  private static final String OP_NOT_EQUAL = "notEqual";
  private static final String SQL_LOWER_COALESCE = "LOWER(COALESCE(";

  private ChartOfAccountsCriteria() {
    // utility class — no instances
  }

  // ── Public entry points (called by ChartOfAccountsHandler) ────────────────

  static String buildLeafAccountWhereClause(String rawCriteria,
      Map<String, Object> sqlParams) throws Exception {
    if (rawCriteria == null || rawCriteria.trim().isEmpty()) {
      return "";
    }

    String sql = buildCriteriaSql(parseCriteriaPayload(rawCriteria), sqlParams, new int[]{0});
    return sql == null || sql.isEmpty() ? "" : " AND (" + sql + ")";
  }

  static String resolveLeafAccountOrderBy(String rawSortBy) {
    if (rawSortBy == null || rawSortBy.trim().isEmpty()) {
      return DEFAULT_LIST_ORDER_BY;
    }

    String trimmed = rawSortBy.trim();
    String[] parts = trimmed.split("\\s+");
    String fieldToken = parts.length > 0 ? parts[0] : "";
    String direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1]) ? "DESC" : "ASC";

    if (fieldToken.startsWith("-")) {
      fieldToken = fieldToken.substring(1);
      direction = "DESC";
    }

    String column = resolveListColumn(fieldToken);
    return column != null ? column + " " + direction : DEFAULT_LIST_ORDER_BY;
  }

  static void applySqlParameters(NativeQuery<Object> query, Map<String, Object> sqlParams) {
    for (Map.Entry<String, Object> entry : sqlParams.entrySet()) {
      query.setParameter(entry.getKey(), entry.getValue());
    }
  }

  // ── Criteria parsing ──────────────────────────────────────────────────────

  private static Object parseCriteriaPayload(String rawCriteria) throws Exception {
    String trimmed = rawCriteria.trim();
    return trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
  }

  private static String buildCriteriaSql(Object node, Map<String, Object> sqlParams,
      int[] sequence) throws Exception {
    if (node instanceof JSONArray) {
      return joinCriteriaGroup((JSONArray) node, "and", sqlParams, sequence);
    }
    if (!(node instanceof JSONObject)) {
      return null;
    }

    JSONObject json = (JSONObject) node;
    if (json.has("criteria")) {
      JSONArray criteria = json.optJSONArray("criteria");
      if (criteria == null) {
        return null;
      }
      return joinCriteriaGroup(criteria, json.optString("operator", "and"), sqlParams, sequence);
    }
    return buildSingleCriterionSql(json, sqlParams, sequence);
  }

  private static String joinCriteriaGroup(JSONArray criteria, String operator,
      Map<String, Object> sqlParams, int[] sequence) throws Exception {
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < criteria.length(); i++) {
      String fragment = buildCriteriaSql(criteria.opt(i), sqlParams, sequence);
      if (fragment != null && !fragment.isEmpty()) {
        parts.add(fragment);
      }
    }
    if (parts.isEmpty()) {
      return null;
    }
    if (parts.size() == 1) {
      return parts.get(0);
    }

    String glue = "or".equalsIgnoreCase(operator) ? " OR " : " AND ";
    return "(" + String.join(glue, parts) + ")";
  }

  private static String buildSingleCriterionSql(JSONObject criterion,
      Map<String, Object> sqlParams, int[] sequence) throws Exception {
    String fieldName = criterion.optString("fieldName", null);
    String operator = criterion.optString("operator", null);
    String column = resolveListColumn(fieldName);
    if (column == null || operator == null || operator.isEmpty()) {
      return null;
    }

    if (ChartOfAccountsHandler.FIELD_SEARCH_KEY.equals(fieldName) || "name".equals(fieldName)) {
      return buildTextCriterionSql(column, operator,
          criterion.opt(JSON_KEY_CRITERION_VALUE), sqlParams, sequence);
    }
    if ("accountType".equals(fieldName)) {
      return buildEnumCriterionSql(column, operator,
          criterion.opt(JSON_KEY_CRITERION_VALUE), sqlParams, sequence);
    }
    if ("active".equals(fieldName)) {
      return buildBooleanCriterionSql(column, operator,
          criterion.opt(JSON_KEY_CRITERION_VALUE), sqlParams, sequence);
    }
    return null;
  }

  // ── Text criterion ────────────────────────────────────────────────────────

  private static String buildTextCriterionSql(String column, String operator, Object value,
      Map<String, Object> sqlParams, int[] sequence) {
    switch (operator) {
      case "isNull":
        return column + " IS NULL";
      case "notNull":
      case "isNotNull":
        return column + " IS NOT NULL";
      case "iContains":
        return bindLowercaseLike(column, value, sqlParams, sequence, true);
      case "iNotContains": {
        String bound = bindLowercaseLike(column, value, sqlParams, sequence, true);
        return bound == null ? null : "COALESCE(" + column + ", '') NOT ILIKE "
            + bound.substring(bound.lastIndexOf(' ') + 1);
      }
      case "iEquals":
      case OP_EQUALS:
        return bindCaseInsensitiveEquals(column, value, sqlParams, sequence, false);
      case "iNotEqual":
      case OP_NOT_EQUAL:
        return bindCaseInsensitiveEquals(column, value, sqlParams, sequence, true);
      case "inSet":
        return bindTextInSet(column, value, sqlParams, sequence);
      default:
        return null;
    }
  }

  // ── Enum criterion ────────────────────────────────────────────────────────

  private static String buildEnumCriterionSql(String column, String operator, Object value,
      Map<String, Object> sqlParams, int[] sequence) {
    switch (operator) {
      case "isNull":
        return column + " IS NULL";
      case "notNull":
      case "isNotNull":
        return column + " IS NOT NULL";
      case OP_EQUALS:
      case "iEquals":
        return bindPlainEquals(column, value, sqlParams, sequence, false);
      case OP_NOT_EQUAL:
      case "iNotEqual":
        return bindPlainEquals(column, value, sqlParams, sequence, true);
      case "inSet":
        return bindPlainInSet(column, value, sqlParams, sequence);
      default:
        return null;
    }
  }

  // ── Boolean criterion ─────────────────────────────────────────────────────

  private static String buildBooleanCriterionSql(String column, String operator, Object value,
      Map<String, Object> sqlParams, int[] sequence) {
    if (!OP_EQUALS.equals(operator) && !OP_NOT_EQUAL.equals(operator)) {
      return null;
    }

    String boolFlag = toYesNoFlag(value);
    if (boolFlag == null) {
      return null;
    }
    String paramName = nextParamName(sequence);
    sqlParams.put(paramName, boolFlag);
    return column + (OP_NOT_EQUAL.equals(operator) ? " <> :" : " = :") + paramName;
  }

  // ── Bind helpers ──────────────────────────────────────────────────────────

  private static String bindLowercaseLike(String column, Object value,
      Map<String, Object> sqlParams, int[] sequence, boolean contains) {
    String normalized = normalizeTextValue(value);
    if (normalized == null) {
      return null;
    }
    String paramName = nextParamName(sequence);
    sqlParams.put(paramName, contains ? "%" + normalized + "%" : normalized);
    return column + " ILIKE :" + paramName;
  }

  private static String bindCaseInsensitiveEquals(String column, Object value,
      Map<String, Object> sqlParams, int[] sequence, boolean negate) {
    String normalized = normalizeTextValue(value);
    if (normalized == null) {
      return null;
    }
    String paramName = nextParamName(sequence);
    sqlParams.put(paramName, normalized);
    return (negate
        ? SQL_LOWER_COALESCE + column + ", '')) <> :"
        : SQL_LOWER_COALESCE + column + ", '')) = :") + paramName;
  }

  private static String bindTextInSet(String column, Object value,
      Map<String, Object> sqlParams, int[] sequence) {
    List<String> values = splitCriterionValues(value);
    if (values.isEmpty()) {
      return null;
    }
    List<String> placeholders = new ArrayList<>();
    for (String item : values) {
      String normalized = normalizeTextValue(item);
      if (normalized == null) {
        continue;
      }
      String paramName = nextParamName(sequence);
      sqlParams.put(paramName, normalized);
      placeholders.add(":" + paramName);
    }
    return placeholders.isEmpty()
        ? null
        : SQL_LOWER_COALESCE + column + ", '')) IN (" + String.join(", ", placeholders) + ")";
  }

  private static String bindPlainEquals(String column, Object value,
      Map<String, Object> sqlParams, int[] sequence, boolean negate) {
    String normalized = normalizePlainValue(value);
    if (normalized == null) {
      return null;
    }
    String paramName = nextParamName(sequence);
    sqlParams.put(paramName, normalized);
    return column + (negate ? " <> :" : " = :") + paramName;
  }

  private static String bindPlainInSet(String column, Object value,
      Map<String, Object> sqlParams, int[] sequence) {
    List<String> values = splitCriterionValues(value);
    if (values.isEmpty()) {
      return null;
    }
    List<String> placeholders = new ArrayList<>();
    for (String item : values) {
      String normalized = normalizePlainValue(item);
      if (normalized == null) {
        continue;
      }
      String paramName = nextParamName(sequence);
      sqlParams.put(paramName, normalized);
      placeholders.add(":" + paramName);
    }
    return placeholders.isEmpty() ? null : column + " IN (" + String.join(", ", placeholders) + ")";
  }

  // ── Value normalization ───────────────────────────────────────────────────

  private static List<String> splitCriterionValues(Object value) {
    List<String> values = new ArrayList<>();
    if (value instanceof JSONArray) {
      JSONArray jsonArray = (JSONArray) value;
      for (int i = 0; i < jsonArray.length(); i++) {
        String item = normalizePlainValue(jsonArray.opt(i));
        if (item != null) {
          values.add(item);
        }
      }
      return values;
    }

    String raw = normalizePlainValue(value);
    if (raw == null) {
      return values;
    }
    for (String part : raw.split(",")) {
      String normalized = normalizePlainValue(part);
      if (normalized != null) {
        values.add(normalized);
      }
    }
    return values;
  }

  private static String normalizeTextValue(Object value) {
    String normalized = normalizePlainValue(value);
    return normalized == null ? null : normalized.toLowerCase();
  }

  private static String normalizePlainValue(Object value) {
    if (value == null || JSONObject.NULL.equals(value)) {
      return null;
    }
    String normalized = String.valueOf(value).trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static String toYesNoFlag(Object value) {
    if (value instanceof Boolean) {
      return Boolean.TRUE.equals(value) ? "Y" : "N";
    }
    String normalized = normalizeTextValue(value);
    if (normalized == null) {
      return null;
    }
    if ("true".equals(normalized) || "y".equals(normalized) || "yes".equals(normalized)) {
      return "Y";
    }
    if ("false".equals(normalized) || "n".equals(normalized) || "no".equals(normalized)) {
      return "N";
    }
    return null;
  }

  private static String nextParamName(int[] sequence) {
    sequence[0]++;
    return "filter" + sequence[0];
  }

  private static String resolveListColumn(String fieldName) {
    if (ChartOfAccountsHandler.FIELD_SEARCH_KEY.equals(fieldName)) {
      return JSON_KEY_CRITERION_VALUE;
    }
    if ("name".equals(fieldName)) {
      return "name";
    }
    if ("accountType".equals(fieldName)) {
      return "accounttype";
    }
    if ("active".equals(fieldName)) {
      return "isactive";
    }
    return null;
  }
}
