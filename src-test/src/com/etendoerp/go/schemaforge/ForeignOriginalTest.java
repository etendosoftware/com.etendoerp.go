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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link ForeignOriginal}, the single "is this transaction a foreign pair" rule
 * shared by the reconciliation candidates, the statement-line transactions and the Movements list.
 *
 * @covers com.etendoerp.go.schemaforge.ForeignOriginal
 */
@DisplayName("ForeignOriginal — the original-currency trio of a cross-currency transaction")
class ForeignOriginalTest {

  private static final BigDecimal AMOUNT = new BigDecimal("40.91");
  private static final BigDecimal RATE = new BigDecimal("0.67954");

  @Test
  @DisplayName("a real pair keeps the stored amount, the trimmed ISO and the rate verbatim")
  void realPairIsKept() {
    ForeignOriginal foreign = ForeignOriginal.of(" USD ", "EUR", AMOUNT, RATE);

    assertNotNull(foreign);
    assertSame(AMOUNT, foreign.storedAmount());
    assertEquals("USD", foreign.currencyIso());
    assertSame(RATE, foreign.rate());
  }

  @Test
  @DisplayName("a real pair with no stored rate keeps the rate null")
  void realPairWithoutRateKeepsItNull() {
    ForeignOriginal foreign = ForeignOriginal.of("USD", "EUR", AMOUNT, null);

    assertNotNull(foreign);
    assertEquals("USD", foreign.currencyIso());
    assertNull(foreign.rate());
  }

  @ParameterizedTest(name = "foreign=''{0}'' account=''{1}''")
  @CsvSource(value = {
      "NULL, EUR",
      "'',   EUR",
      "'  ', EUR",
      "USD,  NULL",
      "USD,  ''",
      "EUR,  EUR",
      "' EUR', 'EUR '" }, nullValues = "NULL")
  @DisplayName("blank foreign ISO, blank account ISO or the same currency → not a pair (null)")
  void notAForeignPair(String foreignIso, String accountIso) {
    assertNull(ForeignOriginal.of(foreignIso, accountIso, AMOUNT, RATE));
  }

  @Test
  @DisplayName("no stored foreign amount → not a pair (null)")
  void nullAmountIsNotAPair() {
    assertNull(ForeignOriginal.of("USD", "EUR", null, RATE));
  }

  @ParameterizedTest(name = "stored {0}, base {1} → {2}")
  @CsvSource({
      "58.70,  -40.00, -58.70",
      "-58.70, -40.00, -58.70",
      "58.70,  40.00,  58.70",
      "-58.70, 40.00,  58.70",
      "58.70,  0,      58.70",
      "-58.70, 0,      58.70" })
  @DisplayName("signedLike: negative base → negative magnitude; zero or positive → positive")
  void signedLikeFollowsTheBaseSign(String stored, String base, String expected) {
    ForeignOriginal foreign = new ForeignOriginal(new BigDecimal(stored), "USD", RATE);

    BigDecimal signed = foreign.signedLike(new BigDecimal(base));

    assertEquals(0, new BigDecimal(expected).compareTo(signed), signed.toPlainString());
  }
}
