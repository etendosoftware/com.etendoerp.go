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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.currency.ConversionRate;
import org.openbravo.model.common.currency.ConversionRateDoc;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

/**
 * Currency-conversion concern for the payment registration flow: resolving the request's
 * conversion rate, guarding the single-currency path, and expressing a payment amount in the
 * financial account's currency. Split out of {@link PaymentRegistrationService} to keep that
 * class under the method-count limit (Sonar S1200) and its {@code doRegisterPaymentAdvanced}
 * under the cognitive-complexity limit (Sonar S3776).
 */
final class PaymentCurrencyConverter {

  /**
   * Request field carrying the user-supplied (or seeded) invoice→account conversion rate. Shared
   * with the reconciliation explicit-conversion path ({@link ReconciliationConversionSupport}).
   */
  static final String KEY_CONVERSION_RATE = "conversionRate";

  static final String MSG_RATE_REQUIRED =
      "A conversion rate is required when the invoice and account currencies differ";
  static final String MSG_INVALID_RATE_FORMAT = "Invalid conversion rate format";
  static final String MSG_RATE_NOT_POSITIVE = "Conversion rate must be greater than zero";
  static final String MSG_RATE_ONE_CROSS_CURRENCY =
      "A conversion rate other than 1 is required when the invoice and account currencies differ";

  /**
   * Decimal places tried, in order, when deriving a rate from {@code txnAmount / paymentAmount}
   * (see {@link #consistentRate}). Six first because that is what Classic's Add Payment stores
   * ({@code FIN_AddPayment.setFinancialTransactionAmountAndRate}).
   */
  private static final int[] DERIVED_RATE_SCALES = { 6, 8, 10, 12 };

  /** Fallback precision (decimal places) for a currency that declares none. */
  private static final int DEFAULT_CURRENCY_SCALE = 2;

  private PaymentCurrencyConverter() {
  }

  /** True when the payment crosses currencies: the invoice's differs from the debited account's. */
  static boolean isCrossCurrency(Invoice invoice, FIN_FinancialAccount account) {
    return invoice.getCurrency() != null && account.getCurrency() != null
        && !invoice.getCurrency().getId().equals(account.getCurrency().getId());
  }

  /**
   * Fills {@code body}'s {@code conversionRate} from the invoice's own exchange rate when a
   * cross-currency request arrived without one.
   *
   * <p>Used by the PIS bank-transfer path (ETP-5084), where the rate is not optional in practice:
   * the amount instructed to the bank has to be converted to the account currency. The SPA modal
   * always sends a rate, so this only covers direct API callers — and it writes it back INTO the
   * body on purpose, because that body is snapshotted as the payment intent and replayed when the
   * bank resolves. A rate kept only in a local variable would be absent from the replay, which
   * would then fail {@link #resolveConversionRate}'s own "rate required" check.
   *
   * <p>Deliberately the invoice's own rate ({@link #resolveInvoiceRate}: its
   * {@code ConversionRateDoc} first, then the general table), matching the contract established for
   * cross-currency reconciliation in ETP-4502. Throws when no rate exists at all, which is the
   * honest outcome — the transfer amount would be unknowable.
   */
  static void seedInvoiceRateIfAbsent(JSONObject body, Invoice invoice,
      FIN_FinancialAccount account) throws JSONException {
    if (StringUtils.isNotBlank(body.optString(KEY_CONVERSION_RATE, "").trim())
        || !isCrossCurrency(invoice, account)) {
      return;
    }
    body.put(KEY_CONVERSION_RATE, resolveInvoiceRate(invoice, account).toPlainString());
  }

  /**
   * Outcome of {@link #resolveConversionRate}: either a resolved {@code rate} (with {@code error}
   * null) or an {@code error} response (a 400 to return verbatim, with {@code rate} null). Exactly
   * one field is non-null.
   */
  record RateResolution(BigDecimal rate, NeoResponse error) {
  }

  /**
   * Resolves the conversion rate for a two-step-modal advanced registration. The payment amount is
   * expressed in the invoice currency; when the selected account is in a different currency the
   * modal MUST supply an explicit conversion rate. When the currencies match, the rate defaults to
   * ONE. Returns a {@link RateResolution} carrying either the resolved rate or the 400 error to
   * return, so every status code and message is preserved exactly as the inline validation did.
   */
  static RateResolution resolveConversionRate(JSONObject body, Invoice invoice,
      FIN_FinancialAccount account) {
    boolean foreignCurrency = isCrossCurrency(invoice, account);
    String rawRate = body.optString(KEY_CONVERSION_RATE, "").trim();
    if (StringUtils.isBlank(rawRate)) {
      // Defense-in-depth (B1): a genuinely foreign payment arriving with no rate would otherwise
      // silently book amount x 1 in the account currency (e.g. 100 USD posted as 100 EUR).
      if (foreignCurrency) {
        return new RateResolution(null, NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            MSG_RATE_REQUIRED));
      }
      return new RateResolution(BigDecimal.ONE, null);
    }
    return parseRate(rawRate, foreignCurrency);
  }

  /**
   * Parses a conversion rate the user TYPED and checks it with {@link #rateError}. Returns the same
   * {@link RateResolution} shape as {@link #resolveConversionRate}: the rate, or the 400 to return
   * verbatim ({@value #MSG_INVALID_RATE_FORMAT} when {@code rawRate} is not a number). Shared by the
   * two-step payment modal and the reconciliation explicit-conversion path
   * ({@link ReconciliationConversionSupport}), so both answer with the same messages.
   */
  static RateResolution parseRate(String rawRate, boolean crossCurrency) {
    BigDecimal rate;
    try {
      rate = new BigDecimal(rawRate.trim());
    } catch (NumberFormatException e) {
      return new RateResolution(null, NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_INVALID_RATE_FORMAT));
    }
    NeoResponse invalid = rateError(rate, crossCurrency);
    return invalid != null ? new RateResolution(null, invalid) : new RateResolution(rate, null);
  }

  /**
   * The 400 a conversion rate earns, or {@code null} when it is acceptable: it must be greater than
   * zero and, across two different currencies, other than exactly ONE — a cross-currency rate of 1
   * is almost certainly a missing / placeholder value, and booking amount x 1 across two currencies
   * is wrong. Applies to a rate the user supplied; a rate the server derives is never checked here.
   */
  static NeoResponse rateError(BigDecimal rate, boolean crossCurrency) {
    if (rate.signum() <= 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_RATE_NOT_POSITIVE);
    }
    if (crossCurrency && rate.compareTo(BigDecimal.ONE) == 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_RATE_ONE_CROSS_CURRENCY);
    }
    return null;
  }

  /**
   * Rejects multi-currency payments on the simple invoice quick-pay / bank-reconciliation path
   * ({@link PaymentRegistrationService#registerPaymentCore}), which has no conversion-rate input.
   * The two-step modal ({@link PaymentRegistrationService#doRegisterPaymentAdvanced}) instead
   * threads a real conversion rate and does NOT call this guard.
   */
  static void assertCurrencyMatch(Currency invoiceCurrency, Currency accountCurrency) {
    if (invoiceCurrency != null && accountCurrency != null
        && !invoiceCurrency.getId().equals(accountCurrency.getId())) {
      throw new OBException("The selected account currency (" + accountCurrency.getISOCode()
          + ") does not match the invoice currency (" + invoiceCurrency.getISOCode()
          + "). Multi-currency payments must be processed from Etendo Classic.");
    }
  }

  /**
   * The payment amount expressed in the financial account's currency: {@code amount * rate},
   * rounded to the account currency's standard precision. When the account has no currency (or no
   * declared precision) the product is returned unrounded. Used on both the create path
   * ({@link PaymentRegistrationService#doRegisterPaymentAdvanced}) and the edit/confirm path
   * ({@link PaymentDraftEditService#reapplyDraftBasics}) so a reused draft keeps its rate. A
   * {@code rate} of ONE returns {@code amount} rounded to precision.
   */
  static BigDecimal convertedAmount(BigDecimal amount, BigDecimal rate,
      FIN_FinancialAccount account) {
    BigDecimal converted = amount.multiply(rate);
    Currency accountCurrency = account.getCurrency();
    if (accountCurrency != null && accountCurrency.getStandardPrecision() != null) {
      converted = converted.setScale(accountCurrency.getStandardPrecision().intValue(),
          RoundingMode.HALF_UP);
    }
    return converted;
  }

  /**
   * Writes the financial-transaction fields on {@code payment} VERBATIM: the rate exactly as the
   * caller resolved it, and {@code txnAmount} exactly as {@link #convertedAmount} rounded it.
   *
   * <p>Deliberately NOT {@code FIN_AddPayment.setFinancialTransactionAmountAndRate}: that core
   * helper recomputes {@code rate = txnAmount / paymentAmount} to "correct exchange rate for
   * rounding that occurs in UI", because Classic's Add Payment treats the CONVERTED AMOUNT as the
   * user's input. Our two-step modal is the other way round — the user types the RATE and the
   * account-currency amount is derived — so that correction silently mangles the stored rate: a
   * typed 0.89 on 58.70 USD becomes 52.24 EUR and then 52.24/58.70 = 0.889948892674617, and the
   * reopened draft no longer shows what the user entered (ETP-4841). Note the same recompute is
   * commented out in {@code AdvPaymentMngtDao.getNewPayment} (core bug 17829), so storing the rate
   * verbatim is the behavior core itself settled on.
   */
  static void applyTransactionAmountAndRate(FIN_Payment payment, BigDecimal rate,
      BigDecimal txnAmount) {
    payment.setFinancialTransactionAmount(txnAmount);
    payment.setFinancialTransactionConvertRate(rate);
  }

  /**
   * The conversion rate to use for a bank-reconciliation payment against {@code invoice}, expressed
   * invoice-currency → account-currency: the invoice's own document-level exchange rate
   * ({@link ConversionRateDoc}, set when the invoice was issued or via the frontend's
   * {@code CurrencyRatePicker}) when one exists for the exact currency pair, otherwise the general
   * {@code C_Conversion_Rate} spot rate for the invoice date. Mirrors the precedence
   * {@link InvoiceExchangeRateValidator} uses to gate invoice completion — document rate wins over
   * the general table. Returns {@link BigDecimal#ONE} when the currencies already match. Throws
   * {@link OBException} when the currencies differ and neither source has a rate, since booking the
   * payment would otherwise silently use a wrong (or undefined) conversion.
   */
  static BigDecimal resolveInvoiceRate(Invoice invoice, FIN_FinancialAccount account) {
    Currency from = invoice.getCurrency();
    Currency to = account.getCurrency();
    if (from == null || to == null || from.getId().equals(to.getId())) {
      return BigDecimal.ONE;
    }
    BigDecimal docRate = documentRate(invoice, from, to);
    if (docRate != null) {
      return docRate;
    }
    BigDecimal generalRate = generalRate(invoice, from, to);
    if (generalRate != null) {
      return generalRate;
    }
    throw new OBException("No exchange rate available to reconcile invoice "
        + invoice.getDocumentNo() + " (" + from.getISOCode() + " -> " + to.getISOCode() + ")");
  }

  /**
   * The invoice's own document-level rate for the exact {@code from -> to} pair, or {@code null}
   * when none is set (or it is zero). Mirrors
   * {@code InvoiceExchangeRateValidator.hasDocumentRate}, returning the rate value instead of a
   * boolean.
   */
  private static BigDecimal documentRate(Invoice invoice, Currency from, Currency to) {
    OBCriteria<ConversionRateDoc> crit = OBDal.getInstance().createCriteria(ConversionRateDoc.class);
    crit.add(Restrictions.eq(ConversionRateDoc.PROPERTY_INVOICE, invoice));
    crit.add(Restrictions.eq(ConversionRateDoc.PROPERTY_CURRENCY, from));
    crit.add(Restrictions.eq(ConversionRateDoc.PROPERTY_TOCURRENCY, to));
    crit.setFilterOnReadableClients(false);
    crit.setFilterOnReadableOrganization(false);
    for (ConversionRateDoc rateDoc : crit.list()) {
      BigDecimal rate = rateDoc.getRate();
      if (rate != null && rate.signum() != 0) {
        return rate;
      }
    }
    return null;
  }

  /**
   * The general {@code C_Conversion_Rate} spot rate for the invoice date, or {@code null} when
   * none is configured (or it is zero, which would otherwise divide-by-zero downstream).
   */
  private static BigDecimal generalRate(Invoice invoice, Currency from, Currency to) {
    if (invoice.getInvoiceDate() == null) {
      return null;
    }
    ConversionRate rate = FinancialUtils.getConversionRate(invoice.getInvoiceDate(), from, to,
        invoice.getOrganization(), invoice.getClient());
    if (rate == null) {
      return null;
    }
    BigDecimal multiplyRate = rate.getMultipleRateBy();
    return multiplyRate != null && multiplyRate.signum() != 0 ? multiplyRate : null;
  }

  /**
   * The invoice-currency amount that, converted at {@code rate}, produces {@code baseAmount} in the
   * account currency — the inverse of {@link #convertedAmount}. Used when a statement line only
   * partially settles an invoice: the invoice-currency payment amount is derived from the portion
   * of the line consumed, rounded to the invoice currency's own precision.
   */
  static BigDecimal invoiceAmountFor(BigDecimal baseAmount, BigDecimal rate, Currency invoiceCurrency) {
    return baseAmount.divide(rate, standardScale(invoiceCurrency), RoundingMode.HALF_UP);
  }

  /**
   * The currency's standard precision (decimal places), or 2 when the currency is unknown or
   * declares none.
   */
  static int standardScale(Currency currency) {
    return currency != null && currency.getStandardPrecision() != null
        ? currency.getStandardPrecision().intValue()
        : DEFAULT_CURRENCY_SCALE;
  }

  /**
   * A rate for which {@code paymentAmount x rate}, rounded HALF_UP to {@code accountScale}, gives
   * back exactly {@code txnAmount}. Accounting ({@code AcctServer.getConvertedAmt}) recomputes the
   * account-currency amount as amount x rate; when that does not reproduce the booked transaction
   * amount, the posting ends in a Currency Balancing line instead of a clean exchange difference.
   *
   * <p>Candidates, first match wins: {@code preferred} verbatim (the rate the user typed, so it is
   * kept whenever it is consistent), then {@code txnAmount / paymentAmount} at 6, 8, 10 and 12
   * decimals (HALF_UP). {@link MathContext#DECIMAL64} is the last resort. Example: 40.91 settled for
   * 27.87 gives 0.681252 (40.91 x 0.681252 = 27.870019, which rounds to 27.87).
   *
   * @param paymentAmount the payment in the invoice currency; must be greater than zero
   * @param txnAmount     the transaction amount in the account currency
   * @param preferred     the rate to keep when consistent, or {@code null}
   * @param accountScale  the account currency's precision
   */
  static BigDecimal consistentRate(BigDecimal paymentAmount, BigDecimal txnAmount,
      BigDecimal preferred, int accountScale) {
    if (preferred != null && reproduces(paymentAmount, txnAmount, preferred, accountScale)) {
      return preferred;
    }
    for (int scale : DERIVED_RATE_SCALES) {
      BigDecimal candidate = txnAmount.divide(paymentAmount, scale, RoundingMode.HALF_UP);
      if (reproduces(paymentAmount, txnAmount, candidate, accountScale)) {
        return candidate;
      }
    }
    return txnAmount.divide(paymentAmount, MathContext.DECIMAL64);
  }

  private static boolean reproduces(BigDecimal paymentAmount, BigDecimal txnAmount,
      BigDecimal rate, int accountScale) {
    return paymentAmount.multiply(rate).setScale(accountScale, RoundingMode.HALF_UP)
        .compareTo(txnAmount) == 0;
  }
}
