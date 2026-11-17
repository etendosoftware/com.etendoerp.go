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
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOC_TAX_AMOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_QUANTITY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRICE;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
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

  /** One tax row: the lines of one rate and one rounding mode, their base and their tax. */
  public static final class TaxRow {
    private final BigDecimal rate;
    private final boolean lineBased;
    private final BigDecimal base;
    private final BigDecimal amount;

    TaxRow(BigDecimal rate, boolean lineBased, BigDecimal base, BigDecimal amount) {
      this.rate = rate;
      this.lineBased = lineBased;
      this.base = base;
      this.amount = amount;
    }

    /**
     * Returns the rate in percent.
     *
     * @return the rate in percent
     */
    public BigDecimal rate() {
      return rate;
    }

    /**
     * Returns whether the tax is rounded per line ({@code L}) instead of on the sum ({@code D}).
     *
     * @return whether the tax is rounded per line ({@code L}) instead of on the sum ({@code D})
     */
    public boolean lineBased() {
      return lineBased;
    }

    /**
     * Returns the sum of the nets of the row.
     *
     * @return the sum of the nets of the row
     */
    public BigDecimal base() {
      return base;
    }

    /**
     * Returns the tax of the row.
     *
     * @return the tax of the row
     */
    public BigDecimal amount() {
      return amount;
    }
  }

  /** The totals of the lines: net base, tax rows and their sum, all at the currency scale. */
  public static final class Totals {
    private final BigDecimal base;
    private final BigDecimal tax;
    private final List<TaxRow> rows;

    Totals(BigDecimal base, BigDecimal tax, List<TaxRow> rows) {
      this.base = base;
      this.tax = tax;
      this.rows = rows;
    }

    /**
     * Returns the sum of the line nets.
     *
     * @return the sum of the line nets
     */
    public BigDecimal base() {
      return base;
    }

    /**
     * Returns the sum of the tax of each row.
     *
     * @return the sum of the tax of each row
     */
    public BigDecimal tax() {
      return tax;
    }

    /**
     * Returns the base plus the tax.
     *
     * @return the base plus the tax
     */
    public BigDecimal total() {
      return base.add(tax);
    }

    /**
     * Returns the tax rows, by rate ascending, the sum-rounded row before the line-rounded one.
     *
     * @return the tax rows, by rate ascending, the sum-rounded row before the line-rounded one
     */
    public List<TaxRow> rows() {
      return rows;
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
    return lineNet(quantity, unitPrice, discount, AMOUNT_SCALE);
  }

  /**
   * The net amount of a line at a currency's scale, second rounding.
   *
   * @param quantity  the quantity
   * @param unitPrice the price before the discount
   * @param discount  the percent 0-100, {@code null} or out of range for none
   * @param scale     the decimals of the document currency ({@code C_Currency.StdPrecision})
   * @return {@code round(quantity x unit6, scale)}
   */
  public static BigDecimal lineNet(BigDecimal quantity, BigDecimal unitPrice,
      BigDecimal discount, int scale) {
    return quantity.multiply(unit6(unitPrice, discount)).setScale(scale, RoundingMode.HALF_UP);
  }

  /**
   * The totals of a document's lines at two decimals.
   *
   * @param lines the {@code lines} array of the document, may be {@code null}
   * @return the totals, or {@code null} when no line carries both quantity and price
   */
  public static Totals totals(JSONArray lines) {
    return totals(lines, AMOUNT_SCALE);
  }

  /**
   * The totals of a document's lines at the scale of the document currency. The tax follows the
   * rounding mode of each line's tax ({@code tax.docTaxAmount}, as Etendo's {@code C_Tax}): {@code D}
   * (the default, also when absent) rounds once per rate on the sum of the nets, {@code L} rounds
   * the tax of every line and sums them. A document may mix both.
   *
   * @param lines the {@code lines} array of the document, may be {@code null}
   * @param scale the decimals of the document currency
   * @return the totals, or {@code null} when no line carries both quantity and price
   */
  public static Totals totals(JSONArray lines, int scale) {
    Map<RowKey, BigDecimal[]> rows = new TreeMap<>();
    BigDecimal base = BigDecimal.ZERO;
    boolean any = false;
    for (int i = 0; lines != null && i < lines.length(); i++) {
      BigDecimal net = addLine(rows, lines.optJSONObject(i), scale);
      if (net != null) {
        any = true;
        base = base.add(net);
      }
    }
    return any ? summarize(rows, base, scale) : null;
  }

  /**
   * Adds one line to the rows of its rate and rounding mode.
   *
   * @return the net of the line, {@code null} when it has no quantity or no price
   */
  private static BigDecimal addLine(Map<RowKey, BigDecimal[]> rows, JSONObject line, int scale) {
    BigDecimal quantity = OcrJson.decimal(line, K_QUANTITY);
    BigDecimal price = OcrJson.decimal(line, K_UNIT_PRICE);
    if (quantity == null || price == null) {
      return null;
    }
    BigDecimal net = lineNet(quantity, price, OcrJson.decimal(line, K_DISCOUNT), scale);
    BigDecimal rate = effectiveRate(line);
    if (rate != null) {
      boolean lineBased = isLineBased(line);
      BigDecimal[] row = rows.computeIfAbsent(new RowKey(rate.stripTrailingZeros(), lineBased),
          k -> new BigDecimal[] { rate.stripTrailingZeros(), BigDecimal.ZERO, BigDecimal.ZERO });
      row[1] = row[1].add(net);
      if (lineBased) {
        row[2] = row[2].add(taxOf(net, rate, scale));
      }
    }
    return net;
  }

  /** The totals from the accumulated rows: the tax of each row by its mode, and their sum. */
  private static Totals summarize(Map<RowKey, BigDecimal[]> rows, BigDecimal base, int scale) {
    BigDecimal tax = BigDecimal.ZERO;
    List<TaxRow> out = new ArrayList<>();
    for (Map.Entry<RowKey, BigDecimal[]> entry : rows.entrySet()) {
      BigDecimal[] row = entry.getValue();
      boolean lineBased = entry.getKey().lineBased();
      BigDecimal amount = lineBased ? row[2] : taxOf(row[1], row[0], scale);
      tax = tax.add(amount);
      out.add(new TaxRow(row[0], lineBased, row[1].setScale(scale, RoundingMode.HALF_UP),
          amount.setScale(scale, RoundingMode.HALF_UP)));
    }
    return new Totals(base.setScale(scale, RoundingMode.HALF_UP),
        tax.setScale(scale, RoundingMode.HALF_UP), out);
  }

  /**
   * The key of a tax row: numeric ascending by rate (negative withholding rates included), the
   * {@code D} row before the {@code L} row of the same rate. The rate is normalized
   * ({@code stripTrailingZeros}) so 10 and 10.00 are one row.
   */
  private record RowKey(BigDecimal rate, boolean lineBased) implements Comparable<RowKey> {
    @Override
    public int compareTo(RowKey other) {
      int byRate = rate.compareTo(other.rate);
      return byRate != 0 ? byRate : Boolean.compare(lineBased, other.lineBased);
    }
  }

  private static BigDecimal taxOf(BigDecimal base, BigDecimal rate, int scale) {
    return base.multiply(rate).movePointLeft(2).setScale(scale, RoundingMode.HALF_UP);
  }

  /** Whether the line's tax is rounded per line: {@code tax.docTaxAmount = L}; anything else is {@code D}. */
  static boolean isLineBased(JSONObject line) {
    JSONObject tax = line == null ? null : line.optJSONObject(K_TAX);
    Object mode = tax == null ? null : tax.opt(K_DOC_TAX_AMOUNT);
    return "L".equals(mode);
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
