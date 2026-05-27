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
    int precision = invoice.getCurrency().getStandardPrecision().intValue();
    for (InvoiceLine il : invoice.getInvoiceLineList()) {
      BigDecimal current = il.getGrossAmount();
      if (current != null && current.compareTo(BigDecimal.ZERO) > 0) {
        continue;
      }
      il.setGrossAmount(calculateLineGross(il, precision));
      OBDal.getInstance().save(il);
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
