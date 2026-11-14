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

package com.etendoerp.go.schemaforge.ocr;

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DISCOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_QUANTITY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRICE;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.TreeMap;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * The amounts of an inbox document, with the rule GO itself applies to an invoice line (ETP-6050).
 * Pure: no database, no session, so the worker, the review panel's {@code apply} and the SPA's
 * twin all compute the same cents.
 *
 * <p>GO stores a discount as a percent only. The line price before the discount is the inbox
 * {@code unitPrice} ({@code listPrice} in GO) and the rule is {@code unit6 = round6(unitPrice x
 * (1 - discount/100))}, {@code lineNet = round2(quantity x unit6)}: TWO roundings, in that order,
 * both HALF_UP (away from zero). It is NOT {@code round2(quantity x unitPrice x (1 - d))}, which
 * differs by a cent on large quantities of cheap, long-decimal prices (see
 * {@code InvoiceLineAmountSupport.applyDiscount} and {@code LineAmountSupport}).</p>
 *
 * <p>The tax is computed per rate on the sum of the line nets, {@code round2(taxBase x rate/100)}.
 * The rate of a line is ONE rule on both sides (this class and the SPA's {@code lineRate}): the
 * line's own {@code taxRate} when it is a number, else the {@code rate} of its resolved
 * {@code tax} block, rounded HALF_UP to {@value #RATE_SCALE} decimals BEFORE grouping and
 * computing (what the document stores since {@code apply} keeps {@code taxRate} at two decimals,
 * and what Etendo's tax rates carry in practice: 21, 10, 4, 5.2). A line with no rate at all adds
 * to the base only. A line without quantity or without price contributes
 * nothing. A discount outside 0-100 is ignored (read as no discount).</p>
 */
public final class InvoiceInboxTotals {

  /** Decimals of a unit price after the discount ({@code InvoiceLineAmountSupport.PRICE_SCALE}). */
  public static final int PRICE_SCALE = 6;
  /** Decimals of an amount ({@code LineAmountSupport.AMOUNT_SCALE}). */
  public static final int AMOUNT_SCALE = 2;
  /** Decimals of a tax rate, applied before grouping by rate. */
  public static final int RATE_SCALE = 2;

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private InvoiceInboxTotals() {
  }

  /** The totals of the lines: net base, tax and their sum, all at the amount scale. */
  public static final class Totals {
    private final BigDecimal base;
    private final BigDecimal tax;

    Totals(BigDecimal base, BigDecimal tax) {
      this.base = base;
      this.tax = tax;
    }

    /** Returns the sum of the line nets. */
    public BigDecimal base() {
      return base;
    }

    /** Returns the sum of the tax of each rate. */
    public BigDecimal tax() {
      return tax;
    }

    /** Returns the base plus the tax. */
    public BigDecimal total() {
      return base.add(tax);
    }
  }

  /**
   * The unit price after the discount, first rounding.
   *
   * @param unitPrice the price before the discount
   * @param discount  the percent 0-100, {@code null} or out of range for none
   * @return {@code round6(unitPrice x (1 - discount/100))}
   */
  public static BigDecimal unit6(BigDecimal unitPrice, BigDecimal discount) {
    BigDecimal percent = validDiscount(discount);
    return unitPrice.multiply(HUNDRED.subtract(percent)).movePointLeft(2)
        .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * The net amount of a line, second rounding.
   *
   * @param quantity  the quantity
   * @param unitPrice the price before the discount
   * @param discount  the percent 0-100, {@code null} or out of range for none
   * @return {@code round2(quantity x unit6)}
   */
  public static BigDecimal lineNet(BigDecimal quantity, BigDecimal unitPrice,
      BigDecimal discount) {
    return quantity.multiply(unit6(unitPrice, discount)).setScale(AMOUNT_SCALE,
        RoundingMode.HALF_UP);
  }

  /**
   * The totals of a document's lines.
   *
   * @param lines the {@code lines} array of the document, may be {@code null}
   * @return the totals, or {@code null} when no line carries both quantity and price
   */
  public static Totals totals(JSONArray lines) {
    Map<BigDecimal, BigDecimal> baseByRate = new TreeMap<>();
    BigDecimal base = BigDecimal.ZERO;
    boolean any = false;
    for (int i = 0; lines != null && i < lines.length(); i++) {
      JSONObject line = lines.optJSONObject(i);
      BigDecimal quantity = OcrJson.decimal(line, K_QUANTITY);
      BigDecimal price = OcrJson.decimal(line, K_UNIT_PRICE);
      if (quantity == null || price == null) {
        continue;
      }
      any = true;
      BigDecimal net = lineNet(quantity, price, OcrJson.decimal(line, K_DISCOUNT));
      base = base.add(net);
      BigDecimal rate = effectiveRate(line);
      if (rate != null) {
        baseByRate.merge(rate.stripTrailingZeros(), net, BigDecimal::add);
      }
    }
    if (!any) {
      return null;
    }
    BigDecimal tax = BigDecimal.ZERO;
    for (Map.Entry<BigDecimal, BigDecimal> entry : baseByRate.entrySet()) {
      tax = tax.add(entry.getValue().multiply(entry.getKey()).movePointLeft(2)
          .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
    }
    return new Totals(base.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
        tax.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
  }

  /**
   * The rate a line is taxed at: its own {@code taxRate}, else the rate of its resolved {@code tax},
   * at {@value #RATE_SCALE} decimals HALF_UP; {@code null} when it has neither.
   *
   * @param line a line of the document
   * @return the rate in percent, {@code null} for none
   */
  public static BigDecimal effectiveRate(JSONObject line) {
    BigDecimal rate = OcrJson.decimal(line, K_TAX_RATE);
    if (rate == null) {
      rate = OcrJson.decimal(line == null ? null : line.optJSONObject(K_TAX), K_RATE);
    }
    return rate == null ? null : rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal validDiscount(BigDecimal discount) {
    boolean valid = discount != null && discount.signum() >= 0
        && discount.compareTo(HUNDRED) <= 0;
    return valid ? discount : BigDecimal.ZERO;
  }
}
