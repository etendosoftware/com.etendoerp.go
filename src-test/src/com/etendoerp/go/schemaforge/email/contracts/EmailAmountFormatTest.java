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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.Test;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.common.NeoCurrencyFormatConfig;

/**
 * Unit tests for {@link EmailAmountFormat}, the amount of the portal decision card shown as the app
 * shows it (ETP-6209): instance separators, the currency's symbol on its side, its precision,
 * rounded half-up.
 *
 * <p>New class: no test covered it, and its only caller's test
 * ({@code PortalDecisionEmailContractsTest}) checks one rendered amount through the whole email;
 * the formatting rules are a pure function best pinned as a table.
 *
 * @covers com.etendoerp.go.schemaforge.email.contracts.EmailAmountFormat
 */
public class EmailAmountFormatTest {

  private static final String NBSP = " ";
  private static final NeoCurrencyFormatConfig EUROPEAN = new NeoCurrencyFormatConfig(".", ",");
  private static final NeoCurrencyFormatConfig ENGLISH = new NeoCurrencyFormatConfig(",", ".");

  @Test
  public void formatsLikeTheApp() {
    Currency euro = currency("€", "EUR", Boolean.TRUE, 2L);
    Currency dollar = currency("$", "USD", Boolean.FALSE, 2L);
    Currency sideUnset = currency("€", "EUR", null, 2L);
    Currency noSymbol = currency(" ", "XYZ", Boolean.TRUE, 2L);
    Currency yen = currency("¥", "JPY", Boolean.TRUE, 0L);
    Currency noPrecision = currency("€", "EUR", Boolean.TRUE, null);
    // Columns: label, amount, currency, separators, expected.
    Object[][] cases = {
        { "zero", "0", euro, EUROPEAN, "0,00" + NBSP + "€" },
        { "no grouping below a thousand", "999", euro, EUROPEAN, "999,00" + NBSP + "€" },
        { "a thousand", "1000", euro, EUROPEAN, "1.000,00" + NBSP + "€" },
        { "millions", "1234567.89", euro, EUROPEAN, "1.234.567,89" + NBSP + "€" },
        { "instance separators", "1234567.89", euro, ENGLISH, "1,234,567.89" + NBSP + "€" },
        { "symbol on the left, attached", "1234.5", dollar, EUROPEAN, "$1.234,50" },
        { "side unset is the right", "1234.5", sideUnset, EUROPEAN, "1.234,50" + NBSP + "€" },
        { "no symbol: ISO code", "1234.5", noSymbol, EUROPEAN, "1.234,50" + NBSP + "XYZ" },
        { "no currency", "1234.5", null, EUROPEAN, "1.234,50" },
        { "precision 0", "1234.5", yen, EUROPEAN, "1.235" + NBSP + "¥" },
        { "precision unset is 2", "7", noPrecision, EUROPEAN, "7,00" + NBSP + "€" },
        { "half-up", "2.345", euro, EUROPEAN, "2,35" + NBSP + "€" },
        { "negative, symbol right", "-1234.5", euro, EUROPEAN, "-1.234,50" + NBSP + "€" },
        { "negative, symbol left", "-1234.5", dollar, EUROPEAN, "-$1.234,50" },
        { "rounds to zero without a sign", "-0.001", euro, EUROPEAN, "0,00" + NBSP + "€" },
    };
    for (Object[] c : cases) {
      assertEquals((String) c[0], c[4], EmailAmountFormat.format(new BigDecimal((String) c[1]),
          (Currency) c[2], (NeoCurrencyFormatConfig) c[3]));
    }
  }

  @Test
  public void noAmountIsNoText() {
    assertNull(EmailAmountFormat.format(null, currency("€", "EUR", Boolean.TRUE, 2L), EUROPEAN));
  }

  private static Currency currency(String symbol, String isoCode, Boolean symbolRight,
      Long precision) {
    Currency currency = mock(Currency.class);
    when(currency.getSymbol()).thenReturn(symbol);
    when(currency.getISOCode()).thenReturn(isoCode);
    when(currency.isCurrencySymbolAtTheRight()).thenReturn(symbolRight);
    when(currency.getStandardPrecision()).thenReturn(precision);
    return currency;
  }
}
