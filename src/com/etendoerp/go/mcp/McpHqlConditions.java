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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Property;

import com.etendoerp.go.schemaforge.NeoPropertyPaths;

/**
 * The HQL conditions of an {@code etendo_list} filter on one property: equality, the comparison
 * operators, {@code between}, {@code contains} and {@code in}, on a column or on a property-path
 * field. Generic: they depend on the compared property's type only, never on the entity. Split out
 * of {@link McpQuerySupport} (Sonar java:S1448), which still decides which expression each filter
 * key is compared on.
 */
final class McpHqlConditions {

  private McpHqlConditions() {
  }

  /**
   * Append {@code <path> = value}, or {@code <idPath> = 'value'} for a reference.
   *
   * @param where  the where clause being built
   * @param key    the filter key as the caller spelled it
   * @param prop   the compared property
   * @param path   the HQL expression of the value
   * @param idPath the HQL expression of the referenced id ({@code path + ".id"})
   * @param value  the value to compare with
   */
  static void appendEqualityAt(StringBuilder where, String key, Property prop,
      String path, String idPath, Object value) {
    appendAnd(where);
    if (!prop.isPrimitive()) {
      where.append(idPath).append('=')
          .append(McpBusinessFilters.formatHqlValue(key, String.class, false, value));
    } else {
      where.append(path).append('=').append(
          McpBusinessFilters.formatHqlValue(key, prop.getPrimitiveObjectType(), true, value));
    }
  }

  /**
   * ETP-6148: the condition on a property-path field, compared on the property its path ends on.
   * Same operators and refusals as a column field. The path is joined implicitly, which drops a
   * row whose path is broken: every operator offered here (equality, ranges, {@code between},
   * {@code contains}, {@code in}) is false on a null value anyway, so the result is the one the
   * REST grid's left-joined criteria give.
   *
   * @param where     the where clause being built
   * @param pathField the property-path field
   * @param key       the filter key (the field's API key)
   * @param value     the value, or an operator object
   * @throws JSONException if an operator value cannot be read
   */
  static void appendPathCondition(StringBuilder where,
      NeoPropertyPaths.PathField pathField, String key, Object value) throws JSONException {
    Property prop = pathField.getTarget();
    String path = pathField.hqlPath("e");
    String idPath = path + ".id";
    if (value instanceof JSONObject) {
      appendOperatorConditionsAt(where, prop, path, idPath, key, (JSONObject) value);
    } else {
      appendEqualityAt(where, key, prop, path, idPath, value);
    }
  }

  /**
   * Append one HQL comparison per range operator found in {@code operators}, on given HQL
   * expressions.
   *
   * @param where     the where clause being built
   * @param prop      the compared property
   * @param path      the HQL expression of the value
   * @param idPath    the HQL expression of the referenced id, used by {@code in} on a reference
   * @param key       the filter key as the caller spelled it
   * @param operators the operator object
   * @throws JSONException if an operator value cannot be read
   */
  static void appendOperatorConditionsAt(StringBuilder where, Property prop,
      String path, String idPath, String key, JSONObject operators) throws JSONException {
    Class<?> type = prop.isPrimitive() ? prop.getPrimitiveObjectType() : String.class;
    java.util.Iterator<String> ops = operators.keys();
    while (ops.hasNext()) {
      String op = ops.next();
      appendAnd(where);
      if (McpBusinessFilters.OP_BETWEEN.equals(op)) {
        where.append(betweenCondition(path, prop, type, key, operators.optJSONArray(op)));
      } else if (McpBusinessFilters.OP_CONTAINS.equals(op)) {
        where.append(containsCondition(path, prop, type, key, operators.get(op)));
      } else if (McpBusinessFilters.OP_IN.equals(op)) {
        String inPath = prop.isPrimitive() ? path : idPath;
        where.append(McpBusinessFilters.inCondition(key, inPath, type, prop.isPrimitive(),
            inValues(key, operators.optJSONArray(op))));
      } else {
        String sql = McpBusinessFilters.operatorToSql(op);
        if (sql == null) {
          throw McpRoutingException.unknownFilterOperator(key, op,
              McpBusinessFilters.operatorKeys());
        }
        where.append(path).append(' ').append(sql).append(' ')
            .append(McpBusinessFilters.formatHqlValue(key, type, prop.isPrimitive(), operators.get(op)));
      }
    }
  }

  private static String betweenCondition(String path, Property prop, Class<?> type, String key,
      JSONArray bounds) throws JSONException {
    if (bounds == null || bounds.length() != 2) {
      // ETP-5184: same silent-drop shape as an unknown key. A malformed between used to be
      // logged and skipped, so `{"amount":{"between":[100]}}` answered 200 with every row —
      // the opposite of the narrowing the caller asked for.
      throw McpRequestRefusals.malformedFilterOperator(key, McpBusinessFilters.OP_BETWEEN,
          "the 'between' operator takes a two-element [from, to] array");
    }
    return path + " between "
        + McpBusinessFilters.formatHqlValue(key, type, prop.isPrimitive(), bounds.get(0))
        + " and "
        + McpBusinessFilters.formatHqlValue(key, type, prop.isPrimitive(), bounds.get(1));
  }

  /**
   * ETP-5852: partial text search. Only on a text column — on a number, date, boolean or foreign
   * key a substring match is meaningless, so it is refused instead of guessed.
   */
  private static String containsCondition(String path, Property prop, Class<?> type, String key,
      Object text) {
    if (!prop.isPrimitive()) {
      throw McpRequestRefusals.containsOnReference(key, text);
    }
    if (!String.class.equals(type)) {
      throw McpRequestRefusals.malformedFilterOperator(key, McpBusinessFilters.OP_CONTAINS,
          "'contains' works only on text fields; use exact match, 'in' or a range here");
    }
    if (!(text instanceof String) || ((String) text).isEmpty()) {
      throw McpRequestRefusals.malformedFilterOperator(key, McpBusinessFilters.OP_CONTAINS,
          "the 'contains' operator takes a non-empty text value");
    }
    return McpBusinessFilters.containsCondition(path, text);
  }

  /** ETP-5852: the values of an {@code in}, one request for several exact values. */
  private static java.util.List<Object> inValues(String key, JSONArray values)
      throws JSONException {
    if (values == null || values.length() == 0) {
      throw McpRequestRefusals.malformedFilterOperator(key, McpBusinessFilters.OP_IN,
          "the 'in' operator takes a non-empty array of values");
    }
    java.util.List<Object> list = new java.util.ArrayList<>();
    for (int i = 0; i < values.length(); i++) {
      list.add(values.get(i));
    }
    return list;
  }

  /**
   * Append {@code and} when the clause already holds a condition.
   *
   * @param where the where clause being built
   */
  static void appendAnd(StringBuilder where) {
    if (where.length() > 0) {
      where.append(" and ");
    }
  }
}
