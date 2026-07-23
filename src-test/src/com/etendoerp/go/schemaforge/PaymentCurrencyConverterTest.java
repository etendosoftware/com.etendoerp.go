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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.currency.ConversionRate;
import org.openbravo.model.common.currency.ConversionRateDoc;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * DB-free unit tests for {@link PaymentCurrencyConverter#resolveInvoiceRate} and
 * {@link PaymentCurrencyConverter#invoiceAmountFor}, the ETP-4502 iteration-2 replacement for the
 * (now removed) {@code derivedRate} arithmetic: the conversion rate is no longer derived from the
 * two settlement amounts, it comes from the invoice's own exchange rate — its document-level
 * {@link ConversionRateDoc} first, then the general {@code C_Conversion_Rate} spot rate via
 * {@link FinancialUtils} — with same-currency short-circuiting to {@link BigDecimal#ONE}.
 *
 * <p>{@code documentRate}/{@code generalRate} are private and touch {@link OBDal}/
 * {@link FinancialUtils} via static calls, so both are mocked with Mockito's inline
 * {@code mockStatic}, mirroring the house style already used in
 * {@code ReconciliationFlowSupportForeignInvoiceTest} (a {@link MockedStatic} opened in
 * {@code @BeforeEach} and closed in {@code @AfterEach}).
 *
 * <p>Edge cases covered ({@code >= 3} required):
 * <ul>
 *   <li>same currency (invoice == account) → {@link BigDecimal#ONE}, no DB/FinancialUtils call</li>
 *   <li>different currencies, a document rate exists (non-zero) → that rate wins, FinancialUtils
 *       never consulted</li>
 *   <li>different currencies, no document rate (empty criteria result) → falls back to the general
 *       rate</li>
 *   <li>a document rate row of exactly zero is treated as absent → falls through to the general
 *       rate</li>
 *   <li>different currencies, neither source has a usable rate → {@link OBException}</li>
 *   <li>the invoice has no invoice date → the general-rate lookup is skipped (also throws when no
 *       document rate either)</li>
 *   <li>{@code invoiceAmountFor}: normal rounding at the invoice currency's precision, a currency
 *       with no declared precision falls back to scale 2, and a round-trip against
 *       {@link PaymentCurrencyConverter#convertedAmount} recovers the original amount</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentCurrencyConverterTest {

  private static final String ACCOUNT_CURRENCY_ID = "eur-id";
  private static final String INVOICE_CURRENCY_ID = "usd-id";

  private OBDal obDal;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<FinancialUtils> financialUtilsMock;

  @BeforeEach
  void setUp() {
    obDal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    financialUtilsMock = mockStatic(FinancialUtils.class);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    financialUtilsMock.close();
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static Currency currency(String id) {
    Currency c = mock(Currency.class);
    when(c.getId()).thenReturn(id);
    return c;
  }

  private static Currency currencyWithPrecision(String id, Integer precision) {
    Currency c = currency(id);
    when(c.getStandardPrecision()).thenReturn(precision == null ? null : BigDecimal.valueOf(precision));
    return c;
  }

  private FIN_FinancialAccount account(Currency currency) {
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getCurrency()).thenReturn(currency);
    return acc;
  }

  /** An invoice with the given currency and (by default) a non-null invoice date. */
  private Invoice invoice(Currency currency) {
    Invoice inv = mock(Invoice.class);
    when(inv.getCurrency()).thenReturn(currency);
    when(inv.getInvoiceDate()).thenReturn(new Date());
    when(inv.getDocumentNo()).thenReturn("INV-1");
    return inv;
  }

  /** Stubs {@code OBDal.getInstance().createCriteria(ConversionRateDoc.class).list()}. */
  @SuppressWarnings("unchecked")
  private void stubDocumentRateCriteria(List<ConversionRateDoc> result) {
    OBCriteria<ConversionRateDoc> crit = mock(OBCriteria.class);
    when(crit.list()).thenReturn(result);
    when(obDal.createCriteria(ConversionRateDoc.class)).thenReturn(crit);
  }

  private static ConversionRateDoc rateDoc(String rate) {
    ConversionRateDoc doc = mock(ConversionRateDoc.class);
    when(doc.getRate()).thenReturn(rate == null ? null : new BigDecimal(rate));
    return doc;
  }

  /** Stubs {@code FinancialUtils.getConversionRate(...)} to return a rate with the given multiplier. */
  private void stubGeneralRate(String multiplyRate) {
    ConversionRate cr = mock(ConversionRate.class);
    when(cr.getMultipleRateBy()).thenReturn(multiplyRate == null ? null : new BigDecimal(multiplyRate));
    financialUtilsMock
        .when(() -> FinancialUtils.getConversionRate(any(), any(), any(), any(), any()))
        .thenReturn(cr);
  }

  private void stubGeneralRateReturnsNull() {
    financialUtilsMock
        .when(() -> FinancialUtils.getConversionRate(any(), any(), any(), any(), any()))
        .thenReturn(null);
  }

  // ── resolveInvoiceRate ───────────────────────────────────────────────────

  @Test
  void resolveInvoiceRate_sameCurrency_returnsOneWithoutConsultingAnySource() {
    Currency eur = currency(ACCOUNT_CURRENCY_ID);
    Invoice inv = invoice(eur);
    FIN_FinancialAccount acc = account(eur);

    BigDecimal rate = PaymentCurrencyConverter.resolveInvoiceRate(inv, acc);

    assertEquals(0, BigDecimal.ONE.compareTo(rate));
    financialUtilsMock.verify(
        () -> FinancialUtils.getConversionRate(any(), any(), any(), any(), any()), never());
  }

  @Test
  void resolveInvoiceRate_documentRateAvailable_takesPrecedenceOverGeneralRate() {
    Currency usd = currency(INVOICE_CURRENCY_ID);
    Currency eur = currency(ACCOUNT_CURRENCY_ID);
    Invoice inv = invoice(usd);
    FIN_FinancialAccount acc = account(eur);
    stubDocumentRateCriteria(List.of(rateDoc("0.92")));

    BigDecimal rate = PaymentCurrencyConverter.resolveInvoiceRate(inv, acc);

    assertEquals(0, new BigDecimal("0.92").compareTo(rate));
    financialUtilsMock.verify(
        () -> FinancialUtils.getConversionRate(any(), any(), any(), any(), any()), never());
  }

  @Test
  void resolveInvoiceRate_noDocumentRate_fallsBackToGeneralRate() {
    Currency usd = currency(INVOICE_CURRENCY_ID);
    Currency eur = currency(ACCOUNT_CURRENCY_ID);
    Invoice inv = invoice(usd);
    FIN_FinancialAccount acc = account(eur);
    stubDocumentRateCriteria(Collections.emptyList());
    stubGeneralRate("0.87");

    BigDecimal rate = PaymentCurrencyConverter.resolveInvoiceRate(inv, acc);

    assertEquals(0, new BigDecimal("0.87").compareTo(rate));
  }

  @Test
  void resolveInvoiceRate_documentRateOfExactlyZero_isTreatedAsAbsent() {
    Currency usd = currency(INVOICE_CURRENCY_ID);
    Currency eur = currency(ACCOUNT_CURRENCY_ID);
    Invoice inv = invoice(usd);
    FIN_FinancialAccount acc = account(eur);
    stubDocumentRateCriteria(List.of(rateDoc("0")));
    stubGeneralRate("0.87");

    BigDecimal rate = PaymentCurrencyConverter.resolveInvoiceRate(inv, acc);

    // The zero-rate doc must NOT be returned — the general rate is used instead.
    assertEquals(0, new BigDecimal("0.87").compareTo(rate));
  }

  @Test
  void resolveInvoiceRate_neitherSourceHasARate_throwsOBException() {
    Currency usd = currency(INVOICE_CURRENCY_ID);
    Currency eur = currency(ACCOUNT_CURRENCY_ID);
    Invoice inv = invoice(usd);
    FIN_FinancialAccount acc = account(eur);
    stubDocumentRateCriteria(Collections.emptyList());
    stubGeneralRateReturnsNull();

    OBException ex = assertThrows(OBException.class,
        () -> PaymentCurrencyConverter.resolveInvoiceRate(inv, acc));
    assertTrue(ex.getMessage().contains("INV-1"), ex.getMessage());
  }

  @Test
  void resolveInvoiceRate_generalRateMultiplierIsZero_treatedAsMissing_throws() {
    Currency usd = currency(INVOICE_CURRENCY_ID);
    Currency eur = currency(ACCOUNT_CURRENCY_ID);
    Invoice inv = invoice(usd);
    FIN_FinancialAccount acc = account(eur);
    stubDocumentRateCriteria(Collections.emptyList());
    stubGeneralRate("0");

    assertThrows(OBException.class,
        () -> PaymentCurrencyConverter.resolveInvoiceRate(inv, acc));
  }

  @Test
  void resolveInvoiceRate_invoiceHasNoInvoiceDate_generalRateSkipped_throwsWhenNoDocumentRate() {
    Currency usd = currency(INVOICE_CURRENCY_ID);
    Currency eur = currency(ACCOUNT_CURRENCY_ID);
    Invoice inv = invoice(usd);
    when(inv.getInvoiceDate()).thenReturn(null);
    FIN_FinancialAccount acc = account(eur);
    stubDocumentRateCriteria(Collections.emptyList());

    assertThrows(OBException.class,
        () -> PaymentCurrencyConverter.resolveInvoiceRate(inv, acc));
    // getConversionRate must never be called when the invoice date is null.
    financialUtilsMock.verify(
        () -> FinancialUtils.getConversionRate(any(), any(), any(), any(), any()), never());
  }

  // ── invoiceAmountFor ─────────────────────────────────────────────────────

  @Test
  void invoiceAmountFor_dividesAndRoundsAtInvoiceCurrencyPrecision() {
    Currency usd = currencyWithPrecision(INVOICE_CURRENCY_ID, 2);
    BigDecimal result = PaymentCurrencyConverter.invoiceAmountFor(
        new BigDecimal("27"), new BigDecimal("0.9"), usd);
    assertEquals(0, new BigDecimal("30.00").compareTo(result));
    assertEquals(2, result.scale());
  }

  @Test
  void invoiceAmountFor_currencyWithNoPrecisionDeclared_fallsBackToScaleTwo() {
    Currency usd = currencyWithPrecision(INVOICE_CURRENCY_ID, null);
    BigDecimal result = PaymentCurrencyConverter.invoiceAmountFor(
        new BigDecimal("10"), new BigDecimal("3"), usd);
    assertEquals(2, result.scale());
    assertEquals(0, new BigDecimal("3.33").compareTo(result));
  }

  @Test
  void invoiceAmountFor_nullCurrency_fallsBackToScaleTwo() {
    BigDecimal result = PaymentCurrencyConverter.invoiceAmountFor(
        new BigDecimal("10"), new BigDecimal("4"), null);
    assertEquals(2, result.scale());
    assertEquals(0, new BigDecimal("2.5").compareTo(result));
  }

  @Test
  void invoiceAmountFor_isInverseOfConvertedAmount_roundTrip() {
    // convertedAmount(30, 0.9, accountEUR) = 27.00 (rounded to the account precision).
    Currency eur = currencyWithPrecision(ACCOUNT_CURRENCY_ID, 2);
    FIN_FinancialAccount acc = account(eur);
    BigDecimal converted = PaymentCurrencyConverter.convertedAmount(
        new BigDecimal("30"), new BigDecimal("0.9"), acc);
    assertEquals(0, new BigDecimal("27.00").compareTo(converted));

    // invoiceAmountFor(27, 0.9, invoiceUSD) should recover 30, the original invoice amount.
    Currency usd = currencyWithPrecision(INVOICE_CURRENCY_ID, 2);
    BigDecimal recovered = PaymentCurrencyConverter.invoiceAmountFor(
        converted, new BigDecimal("0.9"), usd);
    assertEquals(0, new BigDecimal("30.00").compareTo(recovered));
  }

  @Test
  void invoiceAmountFor_nonTerminatingQuotient_roundsHalfUp() {
    Currency usd = currencyWithPrecision(INVOICE_CURRENCY_ID, 2);
    // 10 / 3 = 3.3333... -> HALF_UP at scale 2 -> 3.33
    BigDecimal result = PaymentCurrencyConverter.invoiceAmountFor(
        new BigDecimal("10"), new BigDecimal("3"), usd);
    assertEquals("3.33", result.toPlainString());
  }
}
