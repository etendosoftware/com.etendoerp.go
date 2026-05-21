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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * Shared post-processing helper for invoices created from orders (both sales and purchase).
 *
 * <p>After {@code CreateInvoiceLinesFromProcess} runs, this bean:
 * <ol>
 *   <li>Copies {@code em_etgo_total_discount} from the source order to the new invoice.</li>
 *   <li>Calls {@link TotalDiscountService#recalculate} to materialize the ETGO_DTO discount lines.</li>
 *   <li>Rebuilds {@code c_invoicetax} so the taxable base reflects product lines plus discount.</li>
 *   <li>Updates the invoice header totals ({@code summedLineAmount}, {@code grandTotalAmount}).</li>
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
   * Copies the total discount percentage from the source order to the new invoice, materializes
   * the ETGO_DTO discount lines, and rebuilds {@code c_invoicetax} with the correct taxable base.
   *
   * <p>Must be called after {@code CreateInvoiceLinesFromProcess} has run AND after
   * {@code OBDal.flush()} + {@code session.refresh(invoice)} so the invoice's line collection
   * reflects the product lines.
   *
   * <p>No-op when the source order has no total discount ({@code em_etgo_total_discount = 0}
   * or {@code NULL}).
   *
   * @param invoice         the newly created draft invoice
   * @param sourceOrderId   the {@code C_Order_ID} from which the invoice was created
   * @param discountService the {@link TotalDiscountService} CDI bean
   */
  public void applyOrderDiscountToInvoice(Invoice invoice, String sourceOrderId,
      TotalDiscountService discountService) {
    if (discountService == null) {
      return;
    }
    BigDecimal pct = readOrderDiscountPct(sourceOrderId);
    if (pct == null || pct.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    copyDiscountPctToInvoice(invoice.getId(), pct);
    OBDal.getInstance().flush();
    discountService.recalculate(invoice.getId(), true);
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().refresh(invoice);
    rebuildInvoiceTaxAggregates(invoice);
  }

  /**
   * Deletes all existing {@link InvoiceTax} rows for the invoice and recreates them by summing
   * {@code lineNetAmount} across ALL current invoice lines (product + discount) grouped by tax.
   * Also updates {@code summedLineAmount} and {@code grandTotalAmount} on the invoice header.
   *
   * <p>Package-private for unit testing.
   */
  void rebuildInvoiceTaxAggregates(Invoice invoice) {
    List<InvoiceTax> existing = new ArrayList<>(invoice.getInvoiceTaxList());
    for (InvoiceTax it : existing) {
      OBDal.getInstance().remove(it);
    }
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().refresh(invoice);

    int precision = invoice.getCurrency().getStandardPrecision().intValue();
    Map<String, BigDecimal> taxBaseMap = new LinkedHashMap<>();
    Map<String, TaxRate> taxRateMap = new LinkedHashMap<>();
    BigDecimal totalLines = BigDecimal.ZERO;

    for (InvoiceLine il : invoice.getInvoiceLineList()) {
      BigDecimal lineNet = il.getLineNetAmount() != null ? il.getLineNetAmount() : BigDecimal.ZERO;
      totalLines = totalLines.add(lineNet);
      TaxRate tax = il.getTax();
      if (tax != null && Boolean.FALSE.equals(tax.isSummaryLevel())) {
        taxBaseMap.merge(tax.getId(), lineNet, BigDecimal::add);
        taxRateMap.putIfAbsent(tax.getId(), tax);
      }
    }

    BigDecimal totalTax = BigDecimal.ZERO;
    long lineNo = 10;
    for (Map.Entry<String, TaxRate> entry : taxRateMap.entrySet()) {
      TaxRate tax = entry.getValue();
      BigDecimal taxBase = taxBaseMap.get(entry.getKey());
      BigDecimal rate = tax.getRate() != null ? tax.getRate() : BigDecimal.ZERO;
      BigDecimal taxAmt = taxBase.multiply(rate).divide(new BigDecimal("100"), precision, ROUNDING);

      InvoiceTax it = OBProvider.getInstance().get(InvoiceTax.class);
      it.setClient(invoice.getClient());
      it.setOrganization(invoice.getOrganization());
      it.setInvoice(invoice);
      it.setTax(tax);
      it.setLineNo(lineNo);
      it.setTaxableAmount(taxBase.setScale(precision, ROUNDING));
      it.setTaxAmount(taxAmt);
      it.setRecalculate(false);
      OBDal.getInstance().save(it);

      totalTax = totalTax.add(taxAmt);
      lineNo += 10;
    }

    invoice.setSummedLineAmount(totalLines.setScale(precision, ROUNDING));
    invoice.setGrandTotalAmount(totalLines.add(totalTax).setScale(precision, ROUNDING));
    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();
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

  private void copyDiscountPctToInvoice(String invoiceId, BigDecimal pct) {
    String sql = "UPDATE c_invoice SET em_etgo_total_discount = ? WHERE c_invoice_id = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setBigDecimal(1, pct);
      ps.setString(2, invoiceId);
      ps.executeUpdate();
      log.debug("Copied total discount {}% to invoice {}", pct, invoiceId);
    } catch (Exception e) {
      log.error("Could not copy total discount to invoice {}: {}", invoiceId, e.getMessage(), e);
    }
  }
}
