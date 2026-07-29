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

package com.etendoerp.go.common;

import org.apache.commons.lang3.StringUtils;

/**
 * Instance-wide currency number-formatting configuration (thousands/decimal separators).
 *
 * This is a fixed standard for the whole Etendo GO instance — not per-organization,
 * not per-currency. It backs the single canonical {@code formatCurrency()} used across
 * the app-shell (browser) and the jsreport payload builder, so both stay in sync from
 * one source instead of two independently hardcoded values.
 */
public final class NeoCurrencyFormatConfig {

  static final String PROP_THOUSANDS = "currency.thousandsSeparator";
  static final String PROP_DECIMAL = "currency.decimalSeparator";
  static final String ENV_THOUSANDS = "ETGO_CURRENCY_THOUSANDS_SEPARATOR";
  static final String ENV_DECIMAL = "ETGO_CURRENCY_DECIMAL_SEPARATOR";
  static final String DEFAULT_THOUSANDS = ".";
  static final String DEFAULT_DECIMAL = ",";

  private final String thousandsSeparator;
  private final String decimalSeparator;

  /**
   * Creates immutable currency-format configuration with normalized separator values.
   *
   * @param thousandsSeparator the thousands-grouping separator character
   * @param decimalSeparator   the decimal separator character
   */
  public NeoCurrencyFormatConfig(String thousandsSeparator, String decimalSeparator) {
    this.thousandsSeparator = StringUtils.defaultIfBlank(thousandsSeparator, DEFAULT_THOUSANDS);
    this.decimalSeparator = StringUtils.defaultIfBlank(decimalSeparator, DEFAULT_DECIMAL);
  }

  /**
   * Reads currency-format configuration from Java, Openbravo, or environment properties.
   *
   * @return runtime currency-format configuration
   */
  public static NeoCurrencyFormatConfig fromRuntime() {
    String thousands = ConfigPropertyReader.readConfigValue(PROP_THOUSANDS, ENV_THOUSANDS, DEFAULT_THOUSANDS);
    String decimal = ConfigPropertyReader.readConfigValue(PROP_DECIMAL, ENV_DECIMAL, DEFAULT_DECIMAL);
    return new NeoCurrencyFormatConfig(thousands, decimal);
  }

  public String getThousandsSeparator() {
    return thousandsSeparator;
  }

  public String getDecimalSeparator() {
    return decimalSeparator;
  }
}
