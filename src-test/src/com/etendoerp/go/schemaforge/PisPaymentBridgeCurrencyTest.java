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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

/**
 * Which currency a PIS transfer is instructed in, and for how much (ETP-5084).
 *
 * <p>A bank transfer leaves the account in the ACCOUNT's currency, so both the payment template and
 * the amount must be derived from it — not from the invoice's / payment's currency, which is what
 * the code did until ETP-5084. Getting this wrong is silent and expensive: a 100 USD invoice paid
 * from a EUR account used to send "100" tagged as euros, overpaying the supplier by the whole
 * spread, while the ledger booked the correctly converted figure.
 *
 * <p>Both methods under test are private statics, reached by reflection — the same approach
 * {@link PisPaymentBridgeReturnUrlTest} uses for this class's other private helpers.
 */
class PisPaymentBridgeCurrencyTest {

  private static final String SEPA = "SEPA";
  private static final String DOMESTIC = "DOMESTIC";
  private static final String FPS = "FPS";

  private static String templateFor(String isoCode) throws Exception {
    Method m = PisPaymentBridge.class.getDeclaredMethod("templateForCurrency", String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, isoCode);
  }

  private static BigDecimal bankAmountFor(FIN_Payment payment, Currency bankCurrency)
      throws Exception {
    Method m = PisPaymentBridge.class.getDeclaredMethod("bankAmountFor", FIN_Payment.class,
        Currency.class);
    m.setAccessible(true);
    try {
      return (BigDecimal) m.invoke(null, payment, bankCurrency);
    } catch (InvocationTargetException e) {
      // Unwrap so callers can assertThrows on the real exception rather than the reflection wrapper.
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw e;
    }
  }

  private static Currency bankCurrencyFor(FIN_Payment payment) throws Exception {
    Method m = PisPaymentBridge.class.getDeclaredMethod("bankCurrencyFor", FIN_Payment.class);
    m.setAccessible(true);
    return (Currency) m.invoke(null, payment);
  }

  private static Currency currency(String id, String isoCode, Long precision) {
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(id);
    when(currency.getISOCode()).thenReturn(isoCode);
    when(currency.getStandardPrecision()).thenReturn(precision);
    return currency;
  }

  /**
   * The risk the whole change exists to remove, asserted on the two helpers together: for a USD
   * payment made from a EUR account, NEITHER the invoice-currency amount nor the invoice currency
   * may reach the bank. Kept at the top of this class rather than inside a grouping, because the
   * nested groups below exercise the two helpers separately and it is their combination that decides
   * what Salt Edge is actually told.
   */
  @Test
  @DisplayName("the reported risk: a cross-currency payment instructs neither the invoice amount "
      + "nor the invoice currency")
  void crossCurrencyPaymentUsesTheAccountsCurrencyAndConvertedAmount() throws Exception {
    Currency usd = currency("USD-ID", "USD", 2L);
    Currency eur = currency("EUR-ID", "EUR", 2L);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getCurrency()).thenReturn(eur);

    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getCurrency()).thenReturn(usd);
    when(payment.getAccount()).thenReturn(account);
    when(payment.getAmount()).thenReturn(new BigDecimal("100.00"));
    when(payment.getFinancialTransactionAmount()).thenReturn(new BigDecimal("92.00"));

    Currency bankCurrency = bankCurrencyFor(payment);
    assertSame(eur, bankCurrency, "the bank is instructed in the ACCOUNT's currency");
    assertEquals(0, new BigDecimal("92.00").compareTo(bankAmountFor(payment, bankCurrency)),
        "and for the converted amount, never the 100.00 invoice figure");
    // And the template follows that same currency, so a EUR account cannot end up on a USD template.
    assertEquals(SEPA, templateFor(bankCurrency.getISOCode()));
  }

  @Nested
  @DisplayName("templateForCurrency — keyed on the debtor account currency")
  class TemplateForCurrency {

    @Test
    void eurUsesSepa() throws Exception {
      assertEquals(SEPA, templateFor("EUR"));
    }

    @Test
    @DisplayName("USD uses DOMESTIC — the mapping ETP-5084 adds")
    void usdUsesDomestic() throws Exception {
      assertEquals(DOMESTIC, templateFor("USD"));
    }

    @Test
    void gbpUsesFps() throws Exception {
      assertEquals(FPS, templateFor("GBP"));
    }

    /** ISO codes arrive from the DB and are compared case-insensitively. */
    @Test
    void matchesCaseInsensitively() throws Exception {
      assertEquals(DOMESTIC, templateFor("usd"));
      assertEquals(FPS, templateFor("gbp"));
    }

    /**
     * Eligibility already rejects any other account currency, so this is a degrade-not-fail
     * fallback rather than a supported path.
     */
    @Test
    void unknownAndNullDegradeToSepa() throws Exception {
      assertEquals(SEPA, templateFor("CHF"));
      assertEquals(SEPA, templateFor(null));
    }
  }

  @Nested
  @DisplayName("bankAmountFor — the figure actually instructed to the bank")
  class BankAmountFor {

    @Test
    @DisplayName("same currency: the payment amount is already in the account currency")
    void sameCurrencyUsesPaymentAmount() throws Exception {
      Currency eur = currency("EUR-ID", "EUR", 2L);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(payment.getCurrency()).thenReturn(eur);
      when(payment.getAmount()).thenReturn(new BigDecimal("100.00"));

      assertEquals(0, new BigDecimal("100.00").compareTo(bankAmountFor(payment, eur)));
    }

    /**
     * The converted figure already lives on the payment, written at registration time by
     * {@code PaymentCurrencyConverter.applyTransactionAmountAndRate}. Reusing it verbatim is what
     * keeps a retry from drifting away from what the ledger holds.
     */
    @Test
    @DisplayName("cross currency: reuses financialTransactionAmount verbatim")
    void crossCurrencyUsesStoredTransactionAmount() throws Exception {
      Currency usd = currency("USD-ID", "USD", 2L);
      Currency eur = currency("EUR-ID", "EUR", 2L);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(payment.getCurrency()).thenReturn(usd);
      when(payment.getAmount()).thenReturn(new BigDecimal("100.00"));
      when(payment.getFinancialTransactionAmount()).thenReturn(new BigDecimal("92.00"));

      assertEquals(0, new BigDecimal("92.00").compareTo(bankAmountFor(payment, eur)),
          "must be the account-currency amount, never the 100.00 invoice-currency one");
    }

    /** A payment predating the converted-amount column falls back to its stored rate. */
    @Test
    @DisplayName("cross currency with no stored amount: recomputes from the stored rate")
    void crossCurrencyRecomputesFromStoredRate() throws Exception {
      Currency usd = currency("USD-ID", "USD", 2L);
      Currency eur = currency("EUR-ID", "EUR", 2L);
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      when(account.getCurrency()).thenReturn(eur);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(payment.getCurrency()).thenReturn(usd);
      when(payment.getAmount()).thenReturn(new BigDecimal("100.00"));
      when(payment.getFinancialTransactionAmount()).thenReturn(BigDecimal.ZERO);
      when(payment.getFinancialTransactionConvertRate()).thenReturn(new BigDecimal("0.92"));
      when(payment.getAccount()).thenReturn(account);

      assertEquals(0, new BigDecimal("92.00").compareTo(bankAmountFor(payment, eur)));
    }

    /**
     * Refusing is the only honest outcome: instructing the unconverted amount would move the wrong
     * amount of money, and guessing a rate here would diverge from the ledger.
     */
    @Test
    @DisplayName("cross currency with neither amount nor rate: refuses instead of guessing")
    void crossCurrencyWithoutAnyRateThrows() {
      Currency usd = currency("USD-ID", "USD", 2L);
      Currency eur = currency("EUR-ID", "EUR", 2L);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(payment.getCurrency()).thenReturn(usd);
      when(payment.getAmount()).thenReturn(new BigDecimal("100.00"));
      when(payment.getDocumentNo()).thenReturn("PAY-1");
      when(payment.getFinancialTransactionAmount()).thenReturn(null);
      when(payment.getFinancialTransactionConvertRate()).thenReturn(null);

      assertThrows(OBException.class, () -> bankAmountFor(payment, eur));
    }

    @Test
    @DisplayName("a null bank currency cannot be cross-currency, so the amount passes through")
    void nullBankCurrencyPassesThrough() throws Exception {
      Currency usd = currency("USD-ID", "USD", 2L);
      BigDecimal amount = new BigDecimal("100.00");
      FIN_Payment payment = mock(FIN_Payment.class);
      when(payment.getCurrency()).thenReturn(usd);
      when(payment.getAmount()).thenReturn(amount);

      assertSame(amount, bankAmountFor(payment, null));
    }
  }
}
