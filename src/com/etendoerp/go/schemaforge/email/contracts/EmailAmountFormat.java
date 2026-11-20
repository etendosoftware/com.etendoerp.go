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
package com.etendoerp.go.schemaforge.email.contracts;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.common.NeoCurrencyFormatConfig;

/**
 * Formats an amount for the customer portal decision card (ETP-6209) the way the app shows it:
 * "2.450,00 €".
 *
 * <p>The separators are the instance-wide ones the SPA's {@code formatCurrency} reads, from the
 * same source: {@link NeoCurrencyFormatConfig} (behind {@code GET /sws/neo/currency-format}). The
 * symbol and its side come from {@code C_Currency} ({@code Symbol},
 * {@code IsSymbolRightSide}, the flag the SPA's {@code symbolRightSide} map also reads): on the
 * right after a non-breaking space, on the left glued to the number. A currency without a symbol
 * shows its ISO code after the number. Rounded half-up to the currency's standard precision.</p>
 *
 * <p>Deliberately <b>not</b> {@code DalEmailContractDataResolver.formatAmount}: that plain
 * "2450.00 EUR" stays in the document emails, which this change does not touch.</p>
 */
final class EmailAmountFormat {

  /** Keeps "2.450,00" and "€" on one line. */
  static final String NBSP = " ";
  private static final int DEFAULT_PRECISION = 2;
  private static final int MAX_PRECISION = 6;
  private static final int GROUP = 3;

  private EmailAmountFormat() {
  }

  /**
   * Formats an amount with the instance separators.
   *
   * @param amount the amount, may be {@code null}
   * @param currency its currency, may be {@code null}
   * @return the formatted amount, or {@code null} when there is no amount
   */
  static String format(BigDecimal amount, Currency currency) {
    return format(amount, currency, NeoCurrencyFormatConfig.fromRuntime());
  }

  static String format(BigDecimal amount, Currency currency, NeoCurrencyFormatConfig separators) {
    if (amount == null) {
      return null;
    }
    BigDecimal rounded = amount.setScale(precision(currency), RoundingMode.HALF_UP);
    // A value that rounds to zero is never shown as "-0,00".
    String sign = rounded.signum() < 0 ? "-" : "";
    String number = digits(rounded.abs(), separators);
    String symbol = currency == null ? null : StringUtils.trimToNull(currency.getSymbol());
    if (symbol == null) {
      String isoCode = currency == null ? null : StringUtils.trimToNull(currency.getISOCode());
      return sign + number + (isoCode == null ? "" : " " + isoCode);
    }
    boolean right = !Boolean.FALSE.equals(currency.isCurrencySymbolAtTheRight());
    return right ? sign + number + NBSP + symbol : sign + symbol + number;
  }

  private static int precision(Currency currency) {
    Long standard = currency == null ? null : currency.getStandardPrecision();
    return standard == null ? DEFAULT_PRECISION
        : (int) Math.max(0, Math.min(MAX_PRECISION, standard));
  }

  /** "2450.00" → "2.450,00" with the configured separators. */
  static String digits(BigDecimal nonNegative, NeoCurrencyFormatConfig separators) {
    String plain = nonNegative.toPlainString();
    int dot = plain.indexOf('.');
    String integer = dot < 0 ? plain : plain.substring(0, dot);
    StringBuilder grouped = new StringBuilder(integer.length() + integer.length() / GROUP);
    for (int i = 0; i < integer.length(); i++) {
      if (i > 0 && (integer.length() - i) % GROUP == 0) {
        grouped.append(separators.getThousandsSeparator());
      }
      grouped.append(integer.charAt(i));
    }
    return dot < 0 ? grouped.toString()
        : grouped + separators.getDecimalSeparator() + plain.substring(dot + 1);
  }
}
