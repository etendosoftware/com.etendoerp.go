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
import java.math.RoundingMode;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
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

/**
 * Currency-conversion concern for the payment registration flow: resolving the request's
 * conversion rate, guarding the single-currency path, and expressing a payment amount in the
 * financial account's currency. Split out of {@link PaymentRegistrationService} to keep that
 * class under the method-count limit (Sonar S1200) and its {@code doRegisterPaymentAdvanced}
 * under the cognitive-complexity limit (Sonar S3776).
 */
final class PaymentCurrencyConverter {

  private PaymentCurrencyConverter() {
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
    boolean foreignCurrency = invoice.getCurrency() != null && account.getCurrency() != null
        && !invoice.getCurrency().getId().equals(account.getCurrency().getId());
    String rawRate = body.optString("conversionRate", "").trim();
    BigDecimal conversionRate;
    if (StringUtils.isBlank(rawRate)) {
      // Defense-in-depth (B1): a genuinely foreign payment arriving with no rate would otherwise
      // silently book amount x 1 in the account currency (e.g. 100 USD posted as 100 EUR).
      if (foreignCurrency) {
        return new RateResolution(null, NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "A conversion rate is required when the invoice and account currencies differ"));
      }
      conversionRate = BigDecimal.ONE;
    } else {
      try {
        conversionRate = new BigDecimal(rawRate);
      } catch (NumberFormatException e) {
        return new RateResolution(null, NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Invalid conversion rate format"));
      }
    }
    if (conversionRate.signum() <= 0) {
      return new RateResolution(null, NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Conversion rate must be greater than zero"));
    }
    // A cross-currency rate of exactly ONE is almost certainly a missing / placeholder value —
    // reject it too rather than book amount x 1 across two different currencies.
    if (foreignCurrency && conversionRate.compareTo(BigDecimal.ONE) == 0) {
      return new RateResolution(null, NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "A conversion rate other than 1 is required when the invoice and account currencies differ"));
    }
    return new RateResolution(conversionRate, null);
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
    int scale = invoiceCurrency != null && invoiceCurrency.getStandardPrecision() != null
        ? invoiceCurrency.getStandardPrecision().intValue()
        : 2;
    return baseAmount.divide(rate, scale, RoundingMode.HALF_UP);
  }
}
