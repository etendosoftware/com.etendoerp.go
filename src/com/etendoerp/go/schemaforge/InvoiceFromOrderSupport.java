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
import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
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
    boolean dirty = false;
    for (InvoiceLine il : invoice.getInvoiceLineList()) {
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
   * When the source order has a per-order rate override ({@code EM_ETGO_Currency_Rate}),
   * creates a {@code C_Conversion_Rate_Document} record for the new invoice so that
   * {@code InvoiceExchangeRateValidator} finds a document-level rate and allows completion,
   * and {@code DocInvoice} uses the same rate for accounting journal entries.
   *
   * <p>No-op when {@code EM_ETGO_Currency_Rate} is null, invoice and org currencies are
   * the same, or a rate record already exists for this invoice + currency pair.
   *
   * @param order   the source order carrying the custom exchange rate
   * @param invoice the newly created draft invoice
   */
  public void propagateOrderRateToInvoice(Order order, Invoice invoice) {
    try {
      BigDecimal rate = order.getETGOCurrencyRate();
      if (rate == null) {
        return;
      }
      if (invoice.getCurrency() == null) {
        return;
      }
      String orgId = order.getOrganization().getId();
      String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
      if (orgCurrencyId == null || orgCurrencyId.equals(invoice.getCurrency().getId())) {
        return;
      }

      Connection conn = OBDal.getInstance().getConnection();

      String checkSql =
          "SELECT 1 FROM c_conversion_rate_document"
        + " WHERE c_invoice_id = ? AND c_currency_id = ? AND c_currency_id_to = ? LIMIT 1";
      try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
        ps.setString(1, invoice.getId());
        ps.setString(2, invoice.getCurrency().getId());
        ps.setString(3, orgCurrencyId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return;
          }
        }
      }

      String newId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
      String userId = OBContext.getOBContext().getUser().getId();
      BigDecimal grandTotal = invoice.getGrandTotalAmount();

      // EM_ETGO_Currency_Rate is the org→doc multiplyRate (e.g. EUR→USD = 1.16).
      // C_Conversion_Rate_Document.rate is the doc→org multiplier: amount_USD × docRate = amount_EUR.
      BigDecimal docRate = BigDecimal.ONE.divide(rate, 12, RoundingMode.HALF_UP);
      BigDecimal foreignAmount = (grandTotal != null)
          ? grandTotal.multiply(docRate).setScale(2, RoundingMode.HALF_UP)
          : null;

      String insertSql =
          "INSERT INTO c_conversion_rate_document ("
        + " c_conversion_rate_document_id, ad_client_id, ad_org_id, isactive,"
        + " created, createdby, updated, updatedby,"
        + " c_invoice_id, c_currency_id, c_currency_id_to, rate, foreign_amount"
        + ") VALUES (?, ?, ?, 'Y', NOW(), ?, NOW(), ?, ?, ?, ?, ?, ?)";

      try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
        ps.setString(1, newId);
        ps.setString(2, invoice.getClient().getId());
        ps.setString(3, invoice.getOrganization().getId());
        ps.setString(4, userId);
        ps.setString(5, userId);
        ps.setString(6, invoice.getId());
        ps.setString(7, invoice.getCurrency().getId());
        ps.setString(8, orgCurrencyId);
        ps.setBigDecimal(9, docRate);
        if (foreignAmount != null) {
          ps.setBigDecimal(10, foreignAmount);
        } else {
          ps.setNull(10, java.sql.Types.NUMERIC);
        }
        ps.executeUpdate();
        log.info("[ETP-4027] Created C_Conversion_Rate_Document {} for invoice {} (docRate={}, eTGORate={})",
            newId, invoice.getId(), docRate, rate);
      }

      // ETP-4029: also persist the rate on the invoice column so summaries/lists can display it.
      invoice.setETGOCurrencyRate(rate);
      OBDal.getInstance().save(invoice);
    } catch (Exception e) {
      log.warn("[ETP-4027] propagateOrderRateToInvoice failed for order {} → invoice {}: {}",
          order.getId(), invoice.getId(), e.getMessage());
    }
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
