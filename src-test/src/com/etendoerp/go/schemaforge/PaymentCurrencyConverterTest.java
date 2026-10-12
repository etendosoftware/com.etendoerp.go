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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 *   <li>{@code consistentRate} (ETP-5657): the preferred rate verbatim when it reproduces the
 *       transaction, else {@code txn / pay} at 6, 8, 10 then 12 decimals, then
 *       {@code DECIMAL64}</li>
 *   <li>{@code rateError} / {@code parseRate} / {@code resolveConversionRate}: the same literal
 *       400 messages the two-step payment modal always answered with</li>
 *   <li>{@code standardScale}: the currency's precision, 2 when unknown</li>
 * </ul>
 *
 * @covers com.etendoerp.go.schemaforge.PaymentCurrencyConverter
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
    when(c.getStandardPrecision()).thenReturn(precision == null ? null : Long.valueOf(precision));
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

  // ── isCrossCurrency / seedInvoiceRateIfAbsent (ETP-5084) ─────────────────

  @Test
  void isCrossCurrency_differentCurrencies_isTrue() {
    Invoice inv = invoice(currency(INVOICE_CURRENCY_ID));
    FIN_FinancialAccount acc = account(currency(ACCOUNT_CURRENCY_ID));

    assertTrue(PaymentCurrencyConverter.isCrossCurrency(inv, acc));
  }

  @Test
  void isCrossCurrency_sameCurrencyOrMissingOne_isFalse() {
    Currency eur = currency(ACCOUNT_CURRENCY_ID);
    assertFalse(PaymentCurrencyConverter.isCrossCurrency(invoice(eur), account(eur)));
    // A missing currency on either side cannot be shown to be a cross-currency payment.
    assertFalse(PaymentCurrencyConverter.isCrossCurrency(invoice(null), account(eur)));
    assertFalse(PaymentCurrencyConverter.isCrossCurrency(invoice(eur), account(null)));
  }

  /**
   * The PIS path cannot proceed without a rate (the bank is instructed for the converted amount), so
   * a direct API caller that omits it gets the invoice's own rate filled in rather than a 400.
   */
  @Test
  void seedInvoiceRateIfAbsent_crossCurrencyWithNoRate_writesTheInvoiceRateIntoTheBody()
      throws Exception {
    Invoice inv = invoice(currency(INVOICE_CURRENCY_ID));
    FIN_FinancialAccount acc = account(currency(ACCOUNT_CURRENCY_ID));
    stubDocumentRateCriteria(Collections.singletonList(rateDoc("0.92")));
    JSONObject body = new JSONObject();

    PaymentCurrencyConverter.seedInvoiceRateIfAbsent(body, inv, acc);

    assertEquals("0.92", body.getString("conversionRate"));
    // And the seeded body now satisfies the very validation that would otherwise have rejected it.
    assertEquals(0, new BigDecimal("0.92").compareTo(
        PaymentCurrencyConverter.resolveConversionRate(body, inv, acc).rate()));
  }

  /**
   * Seeding must never overwrite what the user typed in the modal: that rate is what the resulting
   * payment is booked at, so replacing it would make the instructed and booked amounts diverge.
   */
  @Test
  void seedInvoiceRateIfAbsent_rateAlreadyPresent_leavesItUntouched() throws Exception {
    Invoice inv = invoice(currency(INVOICE_CURRENCY_ID));
    FIN_FinancialAccount acc = account(currency(ACCOUNT_CURRENCY_ID));
    JSONObject body = new JSONObject().put("conversionRate", "0.87");

    PaymentCurrencyConverter.seedInvoiceRateIfAbsent(body, inv, acc);

    assertEquals("0.87", body.getString("conversionRate"));
    // No rate source was consulted at all.
    financialUtilsMock.verify(
        () -> FinancialUtils.getConversionRate(any(), any(), any(), any(), any()), never());
  }

  @Test
  void seedInvoiceRateIfAbsent_sameCurrency_addsNothing() throws Exception {
    Currency eur = currency(ACCOUNT_CURRENCY_ID);
    JSONObject body = new JSONObject();

    PaymentCurrencyConverter.seedInvoiceRateIfAbsent(body, invoice(eur), account(eur));

    assertFalse(body.has("conversionRate"));
  }

  /** No rate anywhere: throwing beats instructing the bank for an unconverted amount. */
  @Test
  void seedInvoiceRateIfAbsent_noRateAvailable_throws() {
    Invoice inv = invoice(currency(INVOICE_CURRENCY_ID));
    FIN_FinancialAccount acc = account(currency(ACCOUNT_CURRENCY_ID));
    stubDocumentRateCriteria(Collections.emptyList());
    stubGeneralRateReturnsNull();
    JSONObject body = new JSONObject();

    assertThrows(OBException.class,
        () -> PaymentCurrencyConverter.seedInvoiceRateIfAbsent(body, inv, acc));
  }

  // ── consistentRate (ETP-5657) ────────────────────────────────────────────

  private static void assertReproduces(String pay, BigDecimal rate, String txn, int scale) {
    assertEquals(0, new BigDecimal(pay).multiply(rate).setScale(scale, RoundingMode.HALF_UP)
        .compareTo(new BigDecimal(txn)), pay + " x " + rate + " must round to " + txn);
  }

  @Test
  void consistentRate_preferredRateThatReproduces_isReturnedVerbatim() {
    BigDecimal typed = new BigDecimal("0.6813");

    BigDecimal rate = PaymentCurrencyConverter.consistentRate(new BigDecimal("40.91"),
        new BigDecimal("27.87"), typed, 2);

    assertSame(typed, rate);
  }

  @Test
  void consistentRate_noPreferredRate_derivesSixDecimals() {
    BigDecimal rate = PaymentCurrencyConverter.consistentRate(new BigDecimal("40.91"),
        new BigDecimal("27.87"), null, 2);

    assertEquals("0.681252", rate.toPlainString());
    assertReproduces("40.91", rate, "27.87", 2);
  }

  @Test
  void consistentRate_preferredRateThatDoesNotReproduce_isReplacedByTheDerivedOne() {
    BigDecimal rate = PaymentCurrencyConverter.consistentRate(new BigDecimal("40.91"),
        new BigDecimal("27.87"), new BigDecimal("0.7"), 2);

    assertEquals("0.681252", rate.toPlainString());
  }

  /**
   * The more the payment amount grows, the more decimals the rate needs for {@code pay x rate} to
   * round back to the transaction: each case below fails at every scale before the expected one.
   */
  @ParameterizedTest(name = "{0} -> {1} at {3} decimals")
  @CsvSource({
      "1000000,       123456.79,     0.12345679,     8",
      "100000000,     12345678.91,   0.1234567891,   10",
      "10000000000,   1234567891.23, 0.123456789123, 12" })
  void consistentRate_escalatesTheScaleUntilTheRateReproduces(String pay, String txn,
      String expectedRate, int expectedScale) {
    BigDecimal rate = PaymentCurrencyConverter.consistentRate(new BigDecimal(pay),
        new BigDecimal(txn), null, 2);

    assertEquals(expectedRate, rate.toPlainString());
    assertEquals(expectedScale, rate.scale());
    assertReproduces(pay, rate, txn, 2);
  }

  @Test
  void consistentRate_nothingUpToTwelveDecimalsReproduces_fallsBackToDecimal64() {
    BigDecimal pay = new BigDecimal("1000000000000");
    BigDecimal txn = new BigDecimal("123456789012.34");

    BigDecimal rate = PaymentCurrencyConverter.consistentRate(pay, txn, null, 2);

    assertEquals(txn.divide(pay, MathContext.DECIMAL64), rate);
    assertTrue(rate.scale() > 12, "beyond the last fixed scale: " + rate.toPlainString());
    assertReproduces("1000000000000", rate, "123456789012.34", 2);
  }

  @Test
  void consistentRate_zeroPrecisionAccount_reproducesWholeUnits() {
    BigDecimal rate = PaymentCurrencyConverter.consistentRate(new BigDecimal("100.00"),
        new BigDecimal("15234"), null, 0);

    assertEquals(0, new BigDecimal("152.34").compareTo(rate));
    assertReproduces("100.00", rate, "15234", 0);
  }

  // ── rateError / parseRate / standardScale (ETP-5657) ─────────────────────

  private static String message(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  @Test
  void rateError_positiveRateOtherThanOne_isAccepted() {
    assertNull(PaymentCurrencyConverter.rateError(new BigDecimal("0.92"), true));
    assertNull(PaymentCurrencyConverter.rateError(new BigDecimal("0.92"), false));
  }

  @ParameterizedTest(name = "rate={0}, crossCurrency={1}")
  @CsvSource(delimiter = '|', value = {
      "0     | true  | Conversion rate must be greater than zero",
      "-0.5  | false | Conversion rate must be greater than zero",
      "1     | true  | A conversion rate other than 1 is required when the invoice and account "
          + "currencies differ",
      "1.000 | true  | A conversion rate other than 1 is required when the invoice and account "
          + "currencies differ" })
  void rateError_refusedRates_answerTheLiteral400(String rate, boolean crossCurrency,
      String expectedMessage) throws Exception {
    NeoResponse response = PaymentCurrencyConverter.rateError(new BigDecimal(rate), crossCurrency);

    assertEquals(400, response.getHttpStatus());
    assertEquals(expectedMessage, message(response));
  }

  @Test
  void rateError_rateOfOneInTheSameCurrency_isAccepted() {
    assertNull(PaymentCurrencyConverter.rateError(BigDecimal.ONE, false));
  }

  @Test
  void parseRate_trimsAndParses() {
    PaymentCurrencyConverter.RateResolution resolution =
        PaymentCurrencyConverter.parseRate(" 0.92 ", true);

    assertNull(resolution.error());
    assertEquals(0, new BigDecimal("0.92").compareTo(resolution.rate()));
  }

  @Test
  void parseRate_malformed_isInvalidFormat() throws Exception {
    PaymentCurrencyConverter.RateResolution resolution =
        PaymentCurrencyConverter.parseRate("abc", true);

    assertNull(resolution.rate());
    assertEquals(400, resolution.error().getHttpStatus());
    assertEquals("Invalid conversion rate format", message(resolution.error()));
  }

  @Test
  void parseRate_appliesRateError() throws Exception {
    PaymentCurrencyConverter.RateResolution crossOne =
        PaymentCurrencyConverter.parseRate("1", true);
    PaymentCurrencyConverter.RateResolution sameOne =
        PaymentCurrencyConverter.parseRate("1", false);

    assertNull(crossOne.rate());
    assertEquals("A conversion rate other than 1 is required when the invoice and account "
        + "currencies differ", message(crossOne.error()));
    assertNull(sameOne.error());
    assertEquals(0, BigDecimal.ONE.compareTo(sameOne.rate()));
  }

  @Test
  void standardScale_currencyPrecisionOrTwo() {
    assertEquals(0, PaymentCurrencyConverter.standardScale(currencyWithPrecision("JPY", 0)));
    assertEquals(4, PaymentCurrencyConverter.standardScale(currencyWithPrecision("XX4", 4)));
    assertEquals(2, PaymentCurrencyConverter.standardScale(currencyWithPrecision("XXX", null)));
    assertEquals(2, PaymentCurrencyConverter.standardScale(null));
  }

  // ── resolveConversionRate: messages unchanged by the rateError/parseRate refactor ──

  @ParameterizedTest(name = "conversionRate=''{0}'' cross={1}")
  @CsvSource(delimiter = '|', value = {
      "''    | true  | A conversion rate is required when the invoice and account currencies "
          + "differ",
      "abc   | true  | Invalid conversion rate format",
      "abc   | false | Invalid conversion rate format",
      "0     | true  | Conversion rate must be greater than zero",
      "-1    | false | Conversion rate must be greater than zero",
      "1     | true  | A conversion rate other than 1 is required when the invoice and account "
          + "currencies differ" })
  void resolveConversionRate_refusals_keepTheirLiteralMessages(String rawRate,
      boolean crossCurrency, String expectedMessage) throws Exception {
    Currency eur = currency(ACCOUNT_CURRENCY_ID);
    Invoice inv = invoice(crossCurrency ? currency(INVOICE_CURRENCY_ID) : eur);
    JSONObject body = new JSONObject().put("conversionRate", rawRate);

    PaymentCurrencyConverter.RateResolution resolution =
        PaymentCurrencyConverter.resolveConversionRate(body, inv, account(eur));

    assertNull(resolution.rate());
    assertEquals(400, resolution.error().getHttpStatus());
    assertEquals(expectedMessage, message(resolution.error()));
  }

  @Test
  void resolveConversionRate_sameCurrencyWithoutRate_defaultsToOne() {
    Currency eur = currency(ACCOUNT_CURRENCY_ID);

    PaymentCurrencyConverter.RateResolution resolution =
        PaymentCurrencyConverter.resolveConversionRate(new JSONObject(), invoice(eur),
            account(eur));

    assertNull(resolution.error());
    assertEquals(0, BigDecimal.ONE.compareTo(resolution.rate()));
  }

  @Test
  void resolveConversionRate_sameCurrencyRateOfOne_isAccepted() throws Exception {
    Currency eur = currency(ACCOUNT_CURRENCY_ID);

    PaymentCurrencyConverter.RateResolution resolution =
        PaymentCurrencyConverter.resolveConversionRate(
            new JSONObject().put("conversionRate", "1"), invoice(eur), account(eur));

    assertNull(resolution.error());
    assertEquals(0, BigDecimal.ONE.compareTo(resolution.rate()));
  }

  @Test
  void resolveConversionRate_crossCurrencyValidRate_isReturned() throws Exception {
    PaymentCurrencyConverter.RateResolution resolution =
        PaymentCurrencyConverter.resolveConversionRate(
            new JSONObject().put("conversionRate", " 0.92 "),
            invoice(currency(INVOICE_CURRENCY_ID)), account(currency(ACCOUNT_CURRENCY_ID)));

    assertNull(resolution.error());
    assertEquals(0, new BigDecimal("0.92").compareTo(resolution.rate()));
  }
}
