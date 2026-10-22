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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * Shared post-processing helper for invoices created from orders (both sales and purchase).
 *
 * <p>After {@code CreateInvoiceLinesFromProcess} runs, this bean:
 * <ol>
 *   <li>Copies {@code em_etgo_total_discount} from the source order to the new invoice and
 *       materialises the ETGO_DTO discount line right away. The DB trigger chain
 *       ({@code c_invoiceline_trg2 → c_invoicelinetax_trg}) updates {@code c_invoicetax} in place.
 *       Done at creation rather than at completion so the totals are correct regardless of
 *       whether the invoice is later completed via NEO Headless or the Classic UI (Classic's
 *       complete process never invokes our header handlers).</li>
 *   <li>Fills in {@code lineGrossAmount} for invoice lines that have a zero/null gross amount.</li>
 * </ol>
 *
 * <p>Extracted from {@link CreateDraftInvoiceHandler} so that {@link CreatePurchaseInvoiceHandler}
 * can reuse the same behaviour without duplication.
 */
@ApplicationScoped
public class InvoiceFromOrderSupport {

  private static final Logger log = LogManager.getLogger(InvoiceFromOrderSupport.class);
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  /** Decimals of a derived list price: the form rounds the price it sends to 6 (toFixed(6)). */
  private static final int LIST_PRICE_SCALE = 6;

  /**
   * Copies {@code etgoTotalDiscount} from the source order to the new invoice and materialises
   * the ETGO_DTO discount line so the c_invoicetax aggregates and c_invoice totals reflect the
   * discount regardless of whether the invoice is later completed via NEO Headless or the
   * Classic UI (Classic's complete path never invokes our header handlers).
   *
   * <p>No-op when the source order has no positive total discount.
   *
   * @param invoice         the newly created draft invoice
   * @param sourceOrderId   the {@code C_Order_ID} from which the invoice was created
   * @param discountService CDI service that creates the ETGO_DTO line and lets the DB trigger
   *                        chain ({@code c_invoiceline_trg2 → c_invoicelinetax_trg}) refresh
   *                        {@code c_invoicetax} in place; pass {@code null} to skip the
   *                        materialisation step
   * @return a freshly-reloaded {@link Invoice} instance with up-to-date totals and collections,
   *         or the same {@code invoice} argument when the order carries no discount (no-op path)
   */
  public Invoice applyOrderDiscountToInvoice(Invoice invoice, String sourceOrderId,
      TotalDiscountService discountService) {
    BigDecimal pct = readOrderDiscountPct(sourceOrderId);
    if (pct == null || pct.compareTo(BigDecimal.ZERO) <= 0) {
      return invoice;
    }
    String invoiceId = invoice.getId();
    invoice.setEtgoTotalDiscount(pct);
    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();
    if (discountService != null) {
      // Materialise the ETGO_DTO line and let the DB trigger chain
      // (c_invoiceline_trg2 → c_invoicelinetax_trg) update c_invoicetax in place.
      // We do this at creation rather than at completion so the totals are
      // correct regardless of whether the invoice is completed via NEO Headless
      // or the Classic UI (the latter never invokes our header handlers).
      discountService.recalculate(invoiceId, true);
      OBDal.getInstance().flush();
    }
    // session.clear() is intentional here. Using session.refresh(invoice) is not an option:
    // refresh() cascades through invoice.invoiceTaxList and invoice.invoiceLineList, but the
    // DB triggers above just rewrote those rows out from under Hibernate's L1 cache (in
    // particular c_invoicetax: the original row is updated in place, the cached proxy still
    // points to the same id but its state has changed). A cascade refresh on that graph
    // throws EntityNotFoundException for rows the trigger touched, or — worse — Etendo's
    // OBInterceptor resurrects detached proxies as fresh INSERTs and we end up with a
    // duplicate c_invoicetax row (the original ETP-4015 symptom). We tried per-entity evict()
    // first; it did not work because the parent collection still referenced the proxies.
    // Clearing the L1 cache is the only approach we found that produces a clean, single
    // c_invoicetax row in every scenario we tested. The caller must use the returned invoice
    // and not the one passed in.
    OBDal.getInstance().getSession().clear();
    return OBDal.getInstance().get(Invoice.class, invoiceId);
  }

  /**
   * Ensures every invoice line has its {@code lineGrossAmount} populated.
   * Lines with an already-positive gross amount are skipped.
   *
   * @param invoice the invoice whose lines should be patched
   */
  public void ensureLineGrossAmounts(Invoice invoice) {
    ensureLineGrossAmounts(invoice, null);
  }

  /**
   * Same repair, restricted to the invoice lines in {@code onlyLineIds} (ETP-5713). The line import
   * adds lines to a draft that already holds others; a whole-invoice pass would recompute the gross
   * of any existing line with a non-positive one (a deliberate zero line, a negative line).
   * {@code null} means every line, exactly as {@link #ensureLineGrossAmounts(Invoice)}.
   *
   * @param invoice     the invoice whose lines should be patched
   * @param onlyLineIds the ids of the lines to consider, or {@code null} for all
   */
  public void ensureLineGrossAmounts(Invoice invoice, java.util.Set<String> onlyLineIds) {
    int precision = invoice.getCurrency().getStandardPrecision().intValue();
    for (InvoiceLine il : invoice.getInvoiceLineList()) {
      BigDecimal current = il.getGrossAmount();
      boolean inScope = onlyLineIds == null || onlyLineIds.contains(il.getId());
      boolean hasGross = current != null && current.compareTo(BigDecimal.ZERO) > 0;
      if (inScope && !hasGross) {
        il.setGrossAmount(calculateLineGross(il, precision));
        OBDal.getInstance().save(il);
      }
    }
    OBDal.getInstance().flush();
  }

  /**
   * Computes the gross amount for a single invoice line.
   * Uses {@code grossUnitPrice * qty} when positive; otherwise falls back to
   * {@code lineNetAmount * (1 + taxRate/100)}.
   *
   * <p>Package-private for unit testing.
   */
  BigDecimal calculateLineGross(InvoiceLine il, int precision) {
    BigDecimal qty = il.getInvoicedQuantity() != null ? il.getInvoicedQuantity() : BigDecimal.ZERO;
    BigDecimal grossPrice = il.getGrossUnitPrice();
    if (grossPrice != null && grossPrice.compareTo(BigDecimal.ZERO) > 0) {
      return qty.multiply(grossPrice).setScale(precision, ROUNDING);
    }
    BigDecimal net = il.getLineNetAmount() != null ? il.getLineNetAmount() : BigDecimal.ZERO;
    TaxRate tax = il.getTax();
    BigDecimal rate = (tax != null && tax.getRate() != null) ? tax.getRate() : BigDecimal.ZERO;
    BigDecimal taxAmt = net.multiply(rate).divide(new BigDecimal("100"), precision, ROUNDING);
    return net.add(taxAmt).setScale(precision, ROUNDING);
  }

  /**
   * Copies {@code discount} from each invoice line's source {@link OrderLine} into the
   * {@code EM_Etgo_Discount} field on the invoice line. Skips lines that have no source
   * order line, no discount on the source, or already a non-zero value.
   *
   * <p>The native {@code CreateInvoiceLinesFromProcess} copies the unit price, list
   * price and net amount correctly, but {@code C_InvoiceLine} has no standard
   * {@code discount} column — it lives in the EM_ extension. Without this copy the
   * frontend reads zero from {@code EM_Etgo_Discount} and renders "0%" alongside an
   * already-discounted unit price, breaking the totals breakdown displayed in the UI.
   *
   * <p>{@code InvoiceLine.getSalesOrderLine()} maps {@code C_InvoiceLine.C_OrderLine_ID} —
   * the "SalesOrderLine" name is a historical artifact of the Openbravo model, it works
   * identically for purchase order lines.
   *
   * <p>Extracted from {@link CreateDraftInvoiceHandler} (ETP-4006) so that
   * {@link CreatePurchaseInvoiceHandler} can reuse the same behaviour (ETP-4780) instead
   * of losing the per-line discount when generating a Purchase Invoice from a Purchase
   * Order or Goods Receipt.
   *
   * @param invoice the newly created invoice whose lines should receive the discount
   */
  public void copyLineDiscountsFromOrder(Invoice invoice) {
    copyLineDiscountsFromOrder(invoice, null);
  }

  /**
   * Same copy, restricted to the invoice lines in {@code onlyLineIds} (ETP-5713): the line import
   * adds lines to an invoice that already holds others — some edited by hand, some deliberately
   * without a discount — which a whole-invoice pass would re-discount. {@code null} means every
   * line, exactly as {@link #copyLineDiscountsFromOrder(Invoice)}.
   *
   * <p>The native line creation copies unit price, list price and net amount from the order line
   * exactly; only {@code EM_Etgo_Discount} is added here, as a percentage to display. It never
   * recomputes a price, so an imported line is not discounted twice.
   *
   * @param invoice     the invoice whose lines should receive the discount
   * @param onlyLineIds the ids of the lines to consider, or {@code null} for all
   */
  public void copyLineDiscountsFromOrder(Invoice invoice, java.util.Set<String> onlyLineIds) {
    boolean dirty = false;
    for (InvoiceLine il : invoice.getInvoiceLineList()) {
      if (onlyLineIds != null && !onlyLineIds.contains(il.getId())) {
        continue;
      }
      BigDecimal srcDiscount = resolveCopyableSourceDiscount(il);
      if (srcDiscount != null) {
        il.setEtgoDiscount(srcDiscount);
        OBDal.getInstance().save(il);
        dirty = true;
      }
    }
    if (dirty) {
      OBDal.getInstance().flush();
    }
  }

  /**
   * Gives a copied invoice line the list price the form would have stored for its price (ETP-5714):
   * a line with a non-zero price, no list price (absent or 0) and no gross price gets
   * {@code listPrice = unitPrice} when it has no discount, and
   * {@code listPrice = unitPrice / (1 − discount / 100)} (6 decimals, as the form rounds) when it has
   * one.
   *
   * <p>The native line copy ({@code CreateInvoiceLinesFromProcess}, behind the line import and the
   * one-step create from an order or a receipt) takes the prices of the source line exactly. A
   * source whose list price is 0 — an order line an agent created with only a price — therefore gives
   * an invoice line with a price and a list price of 0, and the grid's "Precio" (which is the list
   * price) shows 0,00 next to correct totals. In the form the user types the list price and the unit
   * price is derived from it ({@code unit = list × (1 − d/100)}), so the list price is the inverse of
   * that. Run it AFTER {@link #copyLineDiscountsFromOrder}: the rule reads the discount. A list price
   * the source carries is never replaced; a discount of 100 % or outside 0..100 cannot be inverted
   * and leaves the line alone. A line carrying a gross price is left alone too: the form never
   * persists one (net prices are 0 on a taxes-included price list, so there is no price to align).
   * Same rule as {@code InvoiceLineAmountSupport#alignListPriceWithPrice}, over the typed line
   * instead of a JSON body.
   *
   * @param invoice     the invoice whose lines are considered
   * @param onlyLineIds the ids of the lines to consider (the lines of an import: the draft may hold
   *                    others), or {@code null} for every line
   */
  public void alignLineListPrices(Invoice invoice, java.util.Set<String> onlyLineIds) {
    boolean dirty = false;
    for (InvoiceLine il : invoice.getInvoiceLineList()) {
      BigDecimal list = onlyLineIds == null || onlyLineIds.contains(il.getId())
          ? derivedListPrice(il) : null;
      if (list != null) {
        il.setListPrice(list);
        OBDal.getInstance().save(il);
        dirty = true;
      }
    }
    if (dirty) {
      OBDal.getInstance().flush();
    }
  }

  /**
   * The list price the form would store for a line that has a price and no list price, or
   * {@code null} when nothing should be written. Same rule as
   * {@code InvoiceLineAmountSupport#alignListPriceWithPrice} (there over a JSON body).
   */
  private static BigDecimal derivedListPrice(InvoiceLine il) {
    BigDecimal unit = il.getUnitPrice();
    if (!isNonZero(unit) || isNonZero(il.getListPrice()) || isNonZero(il.getGrossUnitPrice())) {
      return null;
    }
    BigDecimal discount = il.getEtgoDiscount();
    if (!isNonZero(discount)) {
      return unit;
    }
    if (discount.signum() < 0 || discount.compareTo(HUNDRED) >= 0) {
      return null;
    }
    return unit.multiply(HUNDRED).divide(HUNDRED.subtract(discount), LIST_PRICE_SCALE,
        RoundingMode.HALF_UP);
  }

  private static boolean isNonZero(BigDecimal value) {
    return value != null && value.signum() != 0;
  }

  /**
   * Returns the source {@link OrderLine#getDiscount()} value that should be copied
   * into the given invoice line's {@code EM_Etgo_Discount} field, or {@code null}
   * when the copy should be skipped. The copy is skipped when there is no source
   * order line, the source carries no discount, or the invoice line already has a
   * non-zero discount value (set explicitly elsewhere).
   */
  private BigDecimal resolveCopyableSourceDiscount(InvoiceLine il) {
    OrderLine ol = il.getSalesOrderLine();
    if (ol == null) {
      return null;
    }
    BigDecimal srcDiscount = ol.getDiscount();
    if (srcDiscount == null || srcDiscount.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    BigDecimal current = il.getEtgoDiscount();
    if (current != null && current.compareTo(BigDecimal.ZERO) != 0) {
      return null;
    }
    return srcDiscount;
  }

  /**
   * Gives an invoice generated from an order its own exchange rate (ETP-5840): recomputed from the
   * INVOICE's reference date (its accounting date) — never copied from the order, whose rate belongs
   * to the order date — and mirrored into the invoice's exchange-rates tab
   * ({@code C_Conversion_Rate_Document}), so the tab reflects the header rate from the start.
   *
   * <p>No rate configured for that date → the rate stays {@code null} and the tab empty; the invoice
   * is created anyway and its next save is refused until a rate is entered. Org-currency invoices
   * are left alone. The name is kept for its callers ({@link CreateDraftInvoiceHandler},
   * {@link CreatePurchaseInvoiceHandler}); {@code order} is only used for logging now.
   *
   * @param order   the source order (logging only)
   * @param invoice the newly created draft invoice, managed by the current session
   */
  public void propagateOrderRateToInvoice(Order order, Invoice invoice) {
    if (invoice == null) {
      return;
    }
    DocumentCurrencyRate.RateType type = Boolean.TRUE.equals(invoice.isSalesTransaction())
        ? SalesInvoiceHeaderHandler.RATE_TYPE : PurchaseInvoiceHeaderHandler.RATE_TYPE;
    BigDecimal rate = new DocumentCurrencyRate(type).recompute(invoice);
    AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(invoice.getId(), true);
    log.debug("[ETP-5840] Invoice {} generated from order {} got rate {}", invoice.getId(),
        order != null ? order.getId() : null, rate);
  }

  // ── JDBC helpers ────────────────────────────────────────────────────────────

  private BigDecimal readOrderDiscountPct(String orderId) {
    String sql = "SELECT em_etgo_total_discount FROM c_order WHERE c_order_id = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getBigDecimal(1);
        }
      }
    } catch (Exception e) {
      log.warn("Could not read em_etgo_total_discount for order {}: {}", orderId, e.getMessage());
    }
    return null;
  }

}
