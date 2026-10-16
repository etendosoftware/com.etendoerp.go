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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.currency.ConversionRate;
import org.openbravo.model.common.currency.Currency;

/**
 * Folds per-currency subtotals into the organization's functional currency at today's rate. Shared
 * by the Financial Accounts sidebar (ETP-5580) and the payment list summary (ETP-5778), so both
 * answer "what is the total" and "is it approximate" by the same rule.
 *
 * <p>Total by construction: a missing rate excludes that currency (it is listed in
 * {@link Converted#missingRateCurrencies}) instead of failing, a zero subtotal needs no rate, and a
 * {@code null} target falls back to a raw sum. Each subtotal is rounded separately to the target
 * precision ({@code HALF_UP}), so parts may differ from the whole by a cent.
 *
 * <p>An instance memoises one rate per currency pair, so reuse it across several {@link #convert}
 * calls within a request.
 */
public final class NeoCurrencyTotals {

  /** Precision used when a currency declares none. */
  static final int DEFAULT_CURRENCY_PRECISION = 2;

  /** Looks up the multiply rate from one currency id to another; {@code null} when none usable. */
  @FunctionalInterface
  public interface RateLookup {
    BigDecimal rate(String fromCurrencyId, String toCurrencyId);
  }

  /** The currency totals are converted into; {@code precision} rounds each converted subtotal. */
  public static class TargetCurrency {
    public final String id;
    public final String iso;
    public final int precision;

    public TargetCurrency(String id, String iso, int precision) {
      this.id = id;
      this.iso = iso;
      this.precision = precision;
    }

    /** Whether the given currency is this one (by id, or by ISO code as a fallback). */
    public boolean matches(String currencyId, String currencyIso) {
      return StringUtils.equals(id, currencyId)
          || (StringUtils.isNotEmpty(iso) && StringUtils.equals(iso, currencyIso));
    }
  }

  /** An amount already summed in its original currency. */
  public static final class Subtotal {
    public final String currencyId;
    public final String currencyIso;
    public final BigDecimal total;

    public Subtotal(String currencyId, String currencyIso, BigDecimal total) {
      this.currencyId = currencyId;
      this.currencyIso = currencyIso;
      this.total = total == null ? BigDecimal.ZERO : total;
    }
  }

  /**
   * Result of {@link #convert}. {@code iso} is the target's ISO code, or in the no-target fallback
   * the first subtotal's ISO code ({@code null} when there are none).
   */
  public static final class Converted {
    public final BigDecimal total;
    public final String iso;
    public final boolean approximate;
    public final List<String> missingRateCurrencies;

    Converted(BigDecimal total, String iso, boolean approximate, List<String> missing) {
      this.total = total;
      this.iso = iso;
      this.approximate = approximate;
      this.missingRateCurrencies = missing;
    }
  }

  private final RateLookup rates;
  private final Map<String, BigDecimal> rateCache = new HashMap<>();

  public NeoCurrencyTotals(RateLookup rates) {
    this.rates = rates;
  }

  /**
   * Converts and adds the subtotals.
   *
   * @param subtotals the per-currency subtotals
   * @param target the currency to convert into, or {@code null} when none is resolvable
   * @return the converted total; never {@code null}
   */
  public Converted convert(Collection<Subtotal> subtotals, TargetCurrency target) {
    if (target == null) {
      return rawTotal(subtotals);
    }
    BigDecimal total = BigDecimal.ZERO;
    boolean approximate = false;
    List<String> missing = new ArrayList<>();
    for (Subtotal subtotal : subtotals) {
      // A zero subtotal adds nothing at any rate: skip it before the lookup, so a 0.00 foreign
      // amount neither flags the total approximate nor shows up as a missing rate.
      if (target.matches(subtotal.currencyId, subtotal.currencyIso)
          || subtotal.total.signum() == 0) {
        total = total.add(subtotal.total);
        continue;
      }
      BigDecimal converted = convertOne(subtotal, target);
      if (converted == null) {
        missing.add(subtotal.currencyIso);
      } else {
        total = total.add(converted);
        approximate = true;
      }
    }
    return new Converted(total.setScale(target.precision, RoundingMode.HALF_UP), target.iso,
        approximate, missing);
  }

  /** No functional currency anywhere: raw sum, never approximate, labelled with the first currency. */
  private static Converted rawTotal(Collection<Subtotal> subtotals) {
    BigDecimal total = BigDecimal.ZERO;
    for (Subtotal subtotal : subtotals) {
      total = total.add(subtotal.total);
    }
    String iso = subtotals.isEmpty() ? null : subtotals.iterator().next().currencyIso;
    return new Converted(total, iso, false, new ArrayList<>());
  }

  private BigDecimal convertOne(Subtotal subtotal, TargetCurrency target) {
    String key = subtotal.currencyId + ">" + target.id;
    BigDecimal rate;
    if (rateCache.containsKey(key)) {
      rate = rateCache.get(key);
    } else {
      rate = rates.rate(subtotal.currencyId, target.id);
      rateCache.put(key, rate);
    }
    if (rate == null) {
      return null;
    }
    return subtotal.total.multiply(rate).setScale(target.precision, RoundingMode.HALF_UP);
  }

  /**
   * Resolves the current organization's functional currency through
   * {@link NeoConversionHelper#resolveOrgCurrencyId()} (org, then legal entity, then client).
   *
   * @return the org currency, or {@code null} when none is configured in the hierarchy
   */
  public static TargetCurrency resolveOrgCurrency() {
    String currencyId = NeoConversionHelper.resolveOrgCurrencyId();
    if (currencyId == null) {
      return null;
    }
    Currency currency = OBDal.getInstance().get(Currency.class, currencyId);
    if (currency == null) {
      return null;
    }
    Long precision = currency.getStandardPrecision();
    return new TargetCurrency(currencyId, StringUtils.trimToEmpty(currency.getISOCode()),
        precision != null ? precision.intValue() : DEFAULT_CURRENCY_PRECISION);
  }

  /**
   * Today's general {@code C_Conversion_Rate} multiply rate, looked up through core
   * {@link FinancialUtils#getConversionRate} so the organization tree is walked up to {@code 0}.
   * Never throws for a missing rate: a zero rate is reported as {@code null} too.
   *
   * @param fromCurrencyId the source {@code C_Currency_ID}
   * @param toCurrencyId the target {@code C_Currency_ID}
   * @return the multiply rate, or {@code null} when no usable rate is configured
   */
  public static BigDecimal todayMultiplyRate(String fromCurrencyId, String toCurrencyId) {
    OBDal dal = OBDal.getInstance();
    OBContext context = OBContext.getOBContext();
    ConversionRate rate = FinancialUtils.getConversionRate(new Date(),
        dal.getProxy(Currency.class, fromCurrencyId), dal.getProxy(Currency.class, toCurrencyId),
        context.getCurrentOrganization(), context.getCurrentClient());
    if (rate == null) {
      return null;
    }
    BigDecimal multiplyRate = rate.getMultipleRateBy();
    return multiplyRate != null && multiplyRate.signum() != 0 ? multiplyRate : null;
  }
}
