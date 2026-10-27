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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.etendoerp.go.schemaforge.NeoCurrencyTotals.Converted;
import com.etendoerp.go.schemaforge.NeoCurrencyTotals.Subtotal;
import com.etendoerp.go.schemaforge.NeoCurrencyTotals.TargetCurrency;

/**
 * Unit tests for the pure folding logic of {@link NeoCurrencyTotals}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoCurrencyTotals
 */
public class NeoCurrencyTotalsTest {

  private static final TargetCurrency EUR = new TargetCurrency("eur", "EUR", 2);

  private final List<String> lookups = new ArrayList<>();

  private NeoCurrencyTotals totals(String usdRate) {
    return new NeoCurrencyTotals((from, to) -> {
      lookups.add(from + ">" + to);
      return "usd".equals(from) && usdRate != null ? new BigDecimal(usdRate) : null;
    });
  }

  private static Subtotal sub(String id, String iso, String amount) {
    return new Subtotal(id, iso, new BigDecimal(amount));
  }

  @Test
  public void sameCurrencyNeedsNoRateLookup() {
    Converted c = totals("2").convert(List.of(sub("eur", "EUR", "10.00")), EUR);
    assertEquals(0, new BigDecimal("10.00").compareTo(c.total));
    assertFalse(c.approximate);
    assertTrue(lookups.isEmpty());
  }

  @Test
  public void sameCurrencyMatchesByIsoWhenIdDiffers() {
    Converted c = totals("2").convert(List.of(sub("other-id", "EUR", "5.00")), EUR);
    assertFalse(c.approximate);
    assertTrue(lookups.isEmpty());
  }

  @Test
  public void zeroForeignSubtotalNeedsNoRateAndIsNotApproximateNorMissing() {
    Converted c = totals(null).convert(List.of(sub("gbp", "GBP", "0.00")), EUR);
    assertEquals(0, BigDecimal.ZERO.compareTo(c.total));
    assertFalse(c.approximate);
    assertTrue(c.missingRateCurrencies.isEmpty());
    assertTrue(lookups.isEmpty());
  }

  @Test
  public void convertedSubtotalRoundsHalfUpToTargetPrecision() {
    // 0.01 * 0.5 = 0.005 -> 0.01 (HALF_UP), not 0.00 (HALF_EVEN)
    Converted c = totals("0.5").convert(List.of(sub("usd", "USD", "0.01")), EUR);
    assertEquals(new BigDecimal("0.01"), c.total);
    assertTrue(c.approximate);
  }

  @Test
  public void zeroDecimalTargetRoundsToWholeUnits() {
    TargetCurrency jpy = new TargetCurrency("jpy", "JPY", 0);
    Converted c = totals("0.5").convert(
        List.of(sub("usd", "USD", "5.00"), sub("jpy", "JPY", "10")), jpy);
    // 5 * 0.5 = 2.5 -> 3 ; + 10 = 13
    assertEquals(new BigDecimal("13"), c.total);
    assertEquals(0, c.total.scale());
  }

  @Test
  public void missingRateExcludesTheCurrencyAndListsIt() {
    Converted c = totals(null).convert(
        List.of(sub("eur", "EUR", "10.00"), sub("usd", "USD", "99.00")), EUR);
    assertEquals(0, new BigDecimal("10.00").compareTo(c.total));
    assertEquals(List.of("USD"), c.missingRateCurrencies);
    assertFalse(c.approximate);
  }

  @Test
  public void nullTargetIsRawSumLabelledWithFirstIsoAndNeverApproximate() {
    Converted c = totals("2").convert(
        List.of(sub("usd", "USD", "10.00"), sub("eur", "EUR", "5.00")), null);
    assertEquals(0, new BigDecimal("15.00").compareTo(c.total));
    assertEquals("USD", c.iso);
    assertFalse(c.approximate);
    assertTrue(c.missingRateCurrencies.isEmpty());
    assertTrue(lookups.isEmpty());
  }

  @Test
  public void nullTargetWithNoSubtotalsHasNullIsoAndZeroTotal() {
    Converted c = totals("2").convert(List.of(), null);
    assertNull(c.iso);
    assertEquals(0, BigDecimal.ZERO.compareTo(c.total));
    assertFalse(c.approximate);
  }

  @Test
  public void targetIsoIsReportedWhenTargetPresent() {
    assertEquals("EUR", totals("2").convert(List.of(), EUR).iso);
  }

  @Test
  public void oneLookupPerCurrencyPairAcrossConvertCalls() {
    NeoCurrencyTotals t = totals("0.5");
    t.convert(List.of(sub("usd", "USD", "10.00")), EUR);
    t.convert(List.of(sub("usd", "USD", "20.00")), EUR);
    assertEquals(1, lookups.size());
  }

  @Test
  public void aMissingRateIsMemoisedToo() {
    NeoCurrencyTotals t = totals(null);
    t.convert(List.of(sub("gbp", "GBP", "1.00")), EUR);
    t.convert(List.of(sub("gbp", "GBP", "2.00")), EUR);
    assertEquals(1, lookups.size());
  }

  @Test
  public void approximateOnlyWhenANonZeroForeignSubtotalWasConverted() {
    Converted mixed = totals("2").convert(
        List.of(sub("eur", "EUR", "1.00"), sub("usd", "USD", "0.00")), EUR);
    assertFalse(mixed.approximate);
    Converted foreign = totals("2").convert(List.of(sub("usd", "USD", "3.00")), EUR);
    assertTrue(foreign.approximate);
    assertEquals(0, new BigDecimal("6.00").compareTo(foreign.total));
  }

  @Test
  public void nullSubtotalAmountIsTreatedAsZero() {
    assertEquals(0, BigDecimal.ZERO.compareTo(new Subtotal("x", "X", null).total));
  }
}
