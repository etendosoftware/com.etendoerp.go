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

/**
 * Pure rules for configurable product attributes (ETP-5977 POC). Kept free of DAL so each rule has a
 * plain unit test; the event handlers only gather the facts and call these methods.
 */
public final class ProductAttributeRules {

  /** Data type code for a text attribute. */
  public static final String TYPE_TEXT = "T";
  /** Data type code for a numeric attribute. */
  public static final String TYPE_NUMBER = "N";
  /** AD table the pilot supports. */
  public static final String PRODUCT_TABLE_NAME = "M_Product";

  private ProductAttributeRules() {
  }

  /** @return true for the data types the pilot stores (the others are reserved for later). */
  public static boolean isSupportedType(String dataType) {
    return TYPE_TEXT.equals(dataType) || TYPE_NUMBER.equals(dataType);
  }

  /** @return an error message, or null when the definition type is acceptable. */
  public static String checkDefinitionType(String dataType) {
    return isSupportedType(dataType) ? null
        : "Attribute data type '" + dataType + "' is not supported yet (supported: T, N)";
  }

  /** @return an error message, or null when the definition targets the pilot entity. */
  public static String checkDefinitionTable(String tableName) {
    return PRODUCT_TABLE_NAME.equalsIgnoreCase(tableName) ? null
        : "Attribute definitions only support " + PRODUCT_TABLE_NAME + " in this version, not " + tableName;
  }

  /**
   * Checks that exactly the value column matching the definition type is set.
   *
   * @return an error message, or null when the value is acceptable
   */
  public static String checkValueColumns(String dataType, String text, BigDecimal number) {
    if (text != null && number != null) {
      return "Only one of text value and number value may be set";
    }
    if (TYPE_TEXT.equals(dataType)) {
      return text == null ? "A text attribute needs a text value" : null;
    }
    if (TYPE_NUMBER.equals(dataType)) {
      return number == null ? "A numeric attribute needs a number value" : null;
    }
    return checkDefinitionType(dataType);
  }
}
