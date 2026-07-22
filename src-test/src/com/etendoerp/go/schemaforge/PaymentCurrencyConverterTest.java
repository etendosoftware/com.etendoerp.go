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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.openbravo.base.exception.OBException;

/**
 * Focused, DB-free unit tests for {@link PaymentCurrencyConverter#derivedRate(BigDecimal, BigDecimal)}.
 *
 * <p>{@code derivedRate} is the pure arithmetic core of ETP-4502 multi-currency bank
 * reconciliation: given the statement-line amount in the account currency ({@code accountAmount})
 * and the invoice outstanding in the invoice currency ({@code paymentAmount}), it returns the
 * realized conversion rate {@code accountAmount / paymentAmount}, scaled to 12 decimals with
 * {@code HALF_UP} rounding (matching the {@code C_Conversion_Rate} multiplyrate scale). It touches
 * no DB, DAL, or OBContext, so it is exercised here directly.
 *
 * <p>Edge cases covered ({@code >= 3} required):
 * <ul>
 *   <li>normal ratio (line 27 in account currency ÷ invoice 30 → 0.9)</li>
 *   <li>inverse ratio (30 ÷ 27 → 1.111111111111)</li>
 *   <li>same amounts → rate 1.000000000000 (same-currency-equivalent)</li>
 *   <li>zero payment amount → OBException</li>
 *   <li>null payment amount → OBException</li>
 *   <li>rounding / precision: non-terminating quotients truncated to scale 12 HALF_UP</li>
 *   <li>negative amounts (abs is applied by the caller; the raw method preserves sign)</li>
 * </ul>
 */
class PaymentCurrencyConverterTest {

  /** The derived rate scale (kept in sync with PaymentCurrencyConverter.DERIVED_RATE_SCALE). */
  private static final int RATE_SCALE = 12;

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  /**
   * Canonical ETP-4502 example: an invoice outstanding of 30 (invoice currency) settled by a
   * statement line of 27 (account currency) yields a rate of 0.9.
   */
  @Test
  void derivedRate_normalCase_returnsAccountOverPayment() {
    BigDecimal rate = PaymentCurrencyConverter.derivedRate(bd("30"), bd("27"));
    assertEquals(0, bd("0.9").compareTo(rate), "27 / 30 should equal 0.9");
    assertEquals(RATE_SCALE, rate.scale(), "rate must be scaled to 12 decimals");
    assertEquals("0.900000000000", rate.toPlainString());
  }

  /**
   * Inverse of the canonical case: swapping the amounts produces the reciprocal rate, exercising a
   * non-terminating quotient that must be rounded to 12 decimals.
   */
  @Test
  void derivedRate_inverse_roundsToScale() {
    BigDecimal rate = PaymentCurrencyConverter.derivedRate(bd("27"), bd("30"));
    // 30 / 27 = 1.11111111111... -> HALF_UP at 12 decimals
    assertEquals("1.111111111111", rate.toPlainString());
    assertEquals(RATE_SCALE, rate.scale());
  }

  /**
   * Equal amounts (the degenerate "same-currency"-equivalent input) yield exactly 1, expressed at
   * the derived scale.
   */
  @Test
  void derivedRate_equalAmounts_returnsOne() {
    BigDecimal rate = PaymentCurrencyConverter.derivedRate(bd("100"), bd("100"));
    assertEquals(0, BigDecimal.ONE.compareTo(rate));
    assertEquals("1.000000000000", rate.toPlainString());
  }

  /**
   * A zero payment amount (zero invoice outstanding) cannot yield a rate and must be rejected with
   * an {@link OBException} rather than throwing an {@link ArithmeticException} on divide-by-zero.
   */
  @Test
  void derivedRate_zeroPaymentAmount_throwsOBException() {
    OBException ex = assertThrows(OBException.class,
        () -> PaymentCurrencyConverter.derivedRate(BigDecimal.ZERO, bd("27")));
    assertTrue(ex.getMessage().toLowerCase().contains("zero"),
        "message should mention the zero amount, was: " + ex.getMessage());
  }

  /**
   * A null payment amount is a programming/precondition error and must be rejected with an
   * {@link OBException}, not a {@link NullPointerException}.
   */
  @Test
  void derivedRate_nullPaymentAmount_throwsOBException() {
    assertThrows(OBException.class,
        () -> PaymentCurrencyConverter.derivedRate(null, bd("27")));
  }

  /**
   * Non-terminating quotients are truncated to exactly 12 decimals using HALF_UP; verifies the
   * rounding boundary is applied at the declared scale.
   */
  @Test
  void derivedRate_nonTerminatingQuotient_isHalfUpAtScale12() {
    // 1 / 3 = 0.333333333333... -> 0.333333333333 (13th digit 3, rounds down)
    assertEquals("0.333333333333",
        PaymentCurrencyConverter.derivedRate(bd("3"), bd("1")).toPlainString());
    // 2 / 7 = 0.285714285714285... -> 0.285714285714 (13th digit 2, rounds down)
    assertEquals("0.285714285714",
        PaymentCurrencyConverter.derivedRate(bd("7"), bd("2")).toPlainString());
    // 2 / 3 = 0.666666666666... -> 0.666666666667 (13th digit 6, rounds up)
    assertEquals("0.666666666667",
        PaymentCurrencyConverter.derivedRate(bd("3"), bd("2")).toPlainString());
  }

  /**
   * The raw method divides the values as given; the reconciliation caller passes absolute amounts,
   * but this documents that a negative account amount would flow through unchanged (sign preserved).
   */
  @Test
  void derivedRate_negativeAccountAmount_preservesSign() {
    BigDecimal rate = PaymentCurrencyConverter.derivedRate(bd("30"), bd("-27"));
    assertEquals("-0.900000000000", rate.toPlainString());
  }

  /**
   * A near-zero (but non-zero) payment amount does not throw: only an exactly-zero signum is
   * rejected, so a tiny outstanding still derives a (large) rate.
   */
  @Test
  void derivedRate_tinyNonZeroPaymentAmount_doesNotThrow() {
    BigDecimal rate = PaymentCurrencyConverter.derivedRate(bd("0.000000000001"), bd("1"));
    assertTrue(rate.signum() > 0, "a positive quotient is expected");
  }
}
