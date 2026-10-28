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

package com.etendoerp.go.attributes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;

/**
 * The attribute part of a Product list request (ETP-5977, H9): what to filter by and what to sort by, parsed from the
 * query parameters and checked for shape. Pure: the definitions are resolved through a function the caller supplies, so
 * nothing here touches the database.
 *
 * <p>Parameters: {@code attrFilterDef} with one or more of {@code attrFilterMin}/{@code attrFilterMax} (numbers),
 * {@code attrFilterPrefix} (text prefix) or {@code attrFilterEq}; {@code attrSortDef} with {@code attrSortDir}
 * ({@code asc} default, or {@code desc}). None of them carries HQL.</p>
 */
public final class ProductAttributeListRequest {

  static final String FILTER_DEF = "attrFilterDef";
  static final String FILTER_MIN = "attrFilterMin";
  static final String FILTER_MAX = "attrFilterMax";
  static final String FILTER_PREFIX = "attrFilterPrefix";
  static final String FILTER_EQ = "attrFilterEq";
  static final String SORT_DEF = "attrSortDef";
  static final String SORT_DIR = "attrSortDir";

  /** A definition the caller has already checked (exists, active, targets Product, readable client). */
  public static final class Definition {
    private final String id;
    private final boolean number;

    public Definition(String id, boolean number) {
      this.id = id;
      this.number = number;
    }

    public String getId() {
      return id;
    }

    public boolean isNumber() {
      return number;
    }
  }

  private Definition filterDefinition;
  private BigDecimal min;
  private BigDecimal max;
  private String prefix;
  private String equalsValue;
  private Definition sortDefinition;
  private boolean descending;

  private ProductAttributeListRequest() {
  }

  /** @return true when the request names an attribute filter or sort. */
  public static boolean applies(Map<String, String> params) {
    return params != null && (params.containsKey(FILTER_DEF) || params.containsKey(SORT_DEF));
  }

  /**
   * @param params      the request's query parameters
   * @param definitions resolves a definition id (already format-checked) to a usable definition, or throws
   *                    {@link IllegalArgumentException}
   * @throws IllegalArgumentException when a parameter is invalid
   */
  public static ProductAttributeListRequest parse(Map<String, String> params,
      Function<String, Definition> definitions) {
    ProductAttributeListRequest r = new ProductAttributeListRequest();
    if (params.containsKey(FILTER_DEF)) {
      r.filterDefinition = definitions.apply(checkedId(params.get(FILTER_DEF), FILTER_DEF));
      r.parseFilter(params);
    }
    if (params.containsKey(SORT_DEF)) {
      r.sortDefinition = definitions.apply(checkedId(params.get(SORT_DEF), SORT_DEF));
      String dir = params.getOrDefault(SORT_DIR, "asc").toLowerCase();
      if (!"asc".equals(dir) && !"desc".equals(dir)) {
        throw new IllegalArgumentException(SORT_DIR + " must be asc or desc");
      }
      r.descending = "desc".equals(dir);
    }
    return r;
  }

  private void parseFilter(Map<String, String> params) {
    boolean bounds = params.containsKey(FILTER_MIN) || params.containsKey(FILTER_MAX);
    if (bounds && !filterDefinition.isNumber()) {
      throw new IllegalArgumentException(FILTER_MIN + "/" + FILTER_MAX + " applies to numeric attributes only");
    }
    if (params.containsKey(FILTER_PREFIX) && filterDefinition.isNumber()) {
      throw new IllegalArgumentException(FILTER_PREFIX + " applies to text attributes only");
    }
    min = params.containsKey(FILTER_MIN) ? decimal(params.get(FILTER_MIN), FILTER_MIN) : null;
    max = params.containsKey(FILTER_MAX) ? decimal(params.get(FILTER_MAX), FILTER_MAX) : null;
    prefix = params.get(FILTER_PREFIX);
    if (params.containsKey(FILTER_EQ)) {
      equalsValue = params.get(FILTER_EQ);
      if (filterDefinition.isNumber()) {
        decimal(equalsValue, FILTER_EQ);
      }
    }
    if (min == null && max == null && prefix == null && equalsValue == null) {
      throw new IllegalArgumentException(FILTER_DEF + " needs " + FILTER_MIN + ", " + FILTER_MAX + ", "
          + FILTER_PREFIX + " or " + FILTER_EQ);
    }
  }

  private static String checkedId(String id, String param) {
    if (id == null || !id.matches("[0-9A-Fa-f]{32}")) {
      throw new IllegalArgumentException(param + " is not a valid attribute definition id");
    }
    return id;
  }

  private static BigDecimal decimal(String value, String param) {
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException | NullPointerException e) {
      throw new IllegalArgumentException(param + " is not a number");
    }
  }

  public Definition getFilterDefinition() {
    return filterDefinition;
  }

  public BigDecimal getMin() {
    return min;
  }

  public BigDecimal getMax() {
    return max;
  }

  public String getPrefix() {
    return prefix;
  }

  public String getEquals() {
    return equalsValue;
  }

  public Definition getSortDefinition() {
    return sortDefinition;
  }

  public boolean isDescending() {
    return descending;
  }
}
