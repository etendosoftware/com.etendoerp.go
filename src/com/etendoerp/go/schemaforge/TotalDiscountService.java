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
import java.util.LinkedHashMap;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * CDI service responsible for managing the total discount line on commercial documents
 * (orders and invoices).
 *
 * <p>When a user sets a total discount percentage on a document header, this service:
 * <ol>
 *   <li>Reads the stored {@code EM_Etgo_Total_Discount} percentage from the header table.</li>
 *   <li>Deletes any existing discount line (identified by the dummy product {@code ETGO_DTO}).</li>
 *   <li>If the percentage is greater than zero, creates one negative-amount discount line per
 *       tax group, each proportional to that group's net subtotal (mirrors Classic behavior).</li>
 * </ol>
 *
 * <p>The dummy product ID is {@code E4BC94E71D664E73A066DAF78BF39DB3} (search key
 * {@code ETGO_DTO}). This product must exist in the database before the feature is used.
 */
@ApplicationScoped
public class TotalDiscountService {

  private static final Logger log = LogManager.getLogger(TotalDiscountService.class);

  /** Search key and ID of the dummy product used to represent the discount line. */
  static final String DISCOUNT_PRODUCT_ID = "E4BC94E71D664E73A066DAF78BF39DB3";

  private static final int PRICE_SCALE = 2;
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  /**
   * Recalculates (delete + recreate if needed) the total discount lines for the given document.
   *
   * <p>Mirrors Classic {@code C_ORDER_POST1} / {@code C_INVOICE_POST}: one negative discount line
   * is created per tax group so that each tax bucket carries the proportional discount amount.
   *
   * @param headerId  the ID of the C_Invoice or C_Order record
   * @param isInvoice {@code true} for invoice documents, {@code false} for order documents
   */
  public void recalculate(String headerId, boolean isInvoice) {
    OBContext.setAdminMode(true);
    try {
      BigDecimal pct = readDiscountPct(headerId, isInvoice);
      deleteExistingDiscountLine(headerId, isInvoice);
      if (pct == null || pct.compareTo(BigDecimal.ZERO) <= 0) {
        return;
      }
      Map<String, BigDecimal> netByTax = readNetSubtotalByTax(headerId, isInvoice);
      if (netByTax.isEmpty()) {
        return;
      }
      long lineNo = readNextLineNo(headerId, isInvoice);
      for (Map.Entry<String, BigDecimal> entry : netByTax.entrySet()) {
        BigDecimal net = entry.getValue();
        if (net == null || net.compareTo(BigDecimal.ZERO) == 0) {
          continue;
        }
        BigDecimal discountAmt = net
            .multiply(pct)
            .divide(new BigDecimal("100"), PRICE_SCALE, ROUNDING)
            .negate();
        if (isInvoice) {
          createInvoiceDiscountLine(headerId, discountAmt, entry.getKey(), lineNo);
        } else {
          createOrderDiscountLine(headerId, discountAmt, entry.getKey(), lineNo);
        }
        lineNo += 10;
      }
      OBDal.getInstance().flush();
    } catch (Exception e) {
      log.error("Error recalculating total discount for {} id={}", isInvoice ? "invoice" : "order",
          headerId, e);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // -------------------------------------------------------------------------
  // Database read helpers (JDBC — column may not be in OBM)
  // -------------------------------------------------------------------------

  /**
   * Reads the EM_Etgo_Total_Discount value from the header table.
   *
   * @return the percentage, or {@code null} if not set / column missing
   */
  @SuppressWarnings("java:S2077")
  private BigDecimal readDiscountPct(String headerId, boolean isInvoice) {
    String table = isInvoice ? "c_invoice" : "c_order";
    String idCol = isInvoice ? "c_invoice_id" : "c_order_id";
    String sql = "SELECT em_etgo_total_discount FROM " + table + " WHERE " + idCol + " = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, headerId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          BigDecimal val = rs.getBigDecimal(1);
          return val;
        }
      }
    } catch (Exception e) {
      log.warn("Could not read em_etgo_total_discount for {} id={}: {}", table, headerId,
          e.getMessage());
    }
    return null;
  }

  /**
   * Returns the sum of linenetamt grouped by tax, for all non-discount active lines.
   * Mirrors Classic {@code C_ORDER_POST1} / {@code C_INVOICE_POST}: one entry per tax group
   * so that the resulting discount lines preserve the correct tax distribution.
   */
  @SuppressWarnings("java:S2077")
  private Map<String, BigDecimal> readNetSubtotalByTax(String headerId, boolean isInvoice) {
    String lineTable = isInvoice ? "c_invoiceline" : "c_orderline";
    String parentCol = isInvoice ? "c_invoice_id" : "c_order_id";
    String sql = "SELECT c_tax_id, COALESCE(SUM(linenetamt), 0) FROM " + lineTable
        + " WHERE " + parentCol + " = ?"
        + "   AND m_product_id != ?"
        + "   AND isactive = 'Y'"
        + " GROUP BY c_tax_id"
        + " ORDER BY MIN(line)";
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, headerId);
      ps.setString(2, DISCOUNT_PRODUCT_ID);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String taxId = rs.getString(1);
          BigDecimal net = rs.getBigDecimal(2);
          if (taxId != null && net != null) {
            result.put(taxId, net);
          }
        }
      }
    } catch (Exception e) {
      log.warn("Could not compute net subtotal by tax for {} id={}: {}", lineTable, headerId,
          e.getMessage());
    }
    return result;
  }

  /**
   * Returns the C_UOM_ID from the first non-discount active line on the document, or {@code null}.
   */
  @SuppressWarnings("java:S2077")
  private String readFirstLineUomId(String headerId, boolean isInvoice) {
    String lineTable = isInvoice ? "c_invoiceline" : "c_orderline";
    String parentCol = isInvoice ? "c_invoice_id" : "c_order_id";
    String sql = "SELECT c_uom_id FROM " + lineTable
        + " WHERE " + parentCol + " = ?"
        + "   AND m_product_id != ?"
        + "   AND isactive = 'Y'"
        + " ORDER BY line LIMIT 1";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, headerId);
      ps.setString(2, DISCOUNT_PRODUCT_ID);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString(1);
        }
      }
    } catch (Exception e) {
      log.warn("Could not fetch uom id for {} id={}: {}", lineTable, headerId, e.getMessage());
    }
    return null;
  }

  /**
   * Returns the next line number to use for the discount line (MAX(line) + 10).
   */
  @SuppressWarnings("java:S2077")
  private long readNextLineNo(String headerId, boolean isInvoice) {
    String lineTable = isInvoice ? "c_invoiceline" : "c_orderline";
    String parentCol = isInvoice ? "c_invoice_id" : "c_order_id";
    String sql = "SELECT COALESCE(MAX(line), 0) FROM " + lineTable
        + " WHERE " + parentCol + " = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, headerId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getLong(1) + 10;
        }
      }
    } catch (Exception e) {
      log.warn("Could not read max line no for {} id={}: {}", lineTable, headerId, e.getMessage());
    }
    return 10;
  }

  // -------------------------------------------------------------------------
  // Delete existing discount line
  // -------------------------------------------------------------------------

  /**
   * Deletes any discount line (by dummy product ID) on the given document.
   * Uses JDBC DELETE to avoid having to load the full entity graph.
   */
  @SuppressWarnings("java:S2077")
  private void deleteExistingDiscountLine(String headerId, boolean isInvoice) {
    String lineTable = isInvoice ? "c_invoiceline" : "c_orderline";
    String parentCol = isInvoice ? "c_invoice_id" : "c_order_id";
    String sql = "DELETE FROM " + lineTable
        + " WHERE " + parentCol + " = ?"
        + "   AND m_product_id = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, headerId);
      ps.setString(2, DISCOUNT_PRODUCT_ID);
      int deleted = ps.executeUpdate();
      if (deleted > 0) {
        log.debug("Deleted {} discount line(s) from {} id={}", deleted, lineTable, headerId);
      }
    } catch (Exception e) {
      log.error("Could not delete discount line from {} id={}: {}", lineTable, headerId,
          e.getMessage(), e);
    }
  }

  // -------------------------------------------------------------------------
  // Create discount line — Invoice
  // -------------------------------------------------------------------------

  private void createInvoiceDiscountLine(String headerId, BigDecimal discountAmt, String taxId,
      long lineNo) {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, headerId);
    if (invoice == null) {
      log.warn("Invoice not found: {}", headerId);
      return;
    }
    Product product = OBDal.getInstance().get(Product.class, DISCOUNT_PRODUCT_ID);
    if (product == null) {
      log.warn("Discount product not found: {}", DISCOUNT_PRODUCT_ID);
      return;
    }

    String uomId = readFirstLineUomId(headerId, true);

    InvoiceLine line = OBProvider.getInstance().get(InvoiceLine.class);
    line.setClient(invoice.getClient());
    line.setOrganization(invoice.getOrganization());
    line.setActive(true);
    line.setInvoice(invoice);
    line.setLineNo(lineNo);
    line.setProduct(product);

    if (uomId != null) {
      UOM uom = OBDal.getInstance().get(UOM.class, uomId);
      if (uom != null) {
        line.setUOM(uom);
      }
    }
    TaxRate tax = OBDal.getInstance().get(TaxRate.class, taxId);
    if (tax != null) {
      line.setTax(tax);
    }

    line.setInvoicedQuantity(BigDecimal.ONE);
    line.setUnitPrice(discountAmt);
    line.setListPrice(discountAmt);
    line.setStandardPrice(discountAmt);
    line.setPriceLimit(BigDecimal.ZERO);
    line.setLineNetAmount(discountAmt);
    line.setGrossUnitPrice(BigDecimal.ZERO);
    line.setBaseGrossUnitPrice(BigDecimal.ZERO);
    line.setGrossListPrice(BigDecimal.ZERO);
    line.setGrossAmount(BigDecimal.ZERO);

    OBDal.getInstance().save(line);
    log.debug("Created invoice discount line: invoice={}, taxId={}, amt={}", headerId, taxId,
        discountAmt);
  }

  // -------------------------------------------------------------------------
  // Create discount line — Order
  // -------------------------------------------------------------------------

  private void createOrderDiscountLine(String headerId, BigDecimal discountAmt, String taxId,
      long lineNo) {
    Order order = OBDal.getInstance().get(Order.class, headerId);
    if (order == null) {
      log.warn("Order not found: {}", headerId);
      return;
    }
    Product product = OBDal.getInstance().get(Product.class, DISCOUNT_PRODUCT_ID);
    if (product == null) {
      log.warn("Discount product not found: {}", DISCOUNT_PRODUCT_ID);
      return;
    }

    String uomId = readFirstLineUomId(headerId, false);

    OrderLine line = OBProvider.getInstance().get(OrderLine.class);
    line.setClient(order.getClient());
    line.setOrganization(order.getOrganization());
    line.setActive(true);
    line.setSalesOrder(order);
    line.setLineNo(lineNo);
    line.setProduct(product);

    // Mandatory NOT NULL columns without DB defaults — must be set explicitly.
    line.setOrderDate(order.getOrderDate());
    line.setWarehouse(order.getWarehouse());
    line.setCurrency(order.getCurrency());

    if (uomId != null) {
      UOM uom = OBDal.getInstance().get(UOM.class, uomId);
      if (uom != null) {
        line.setUOM(uom);
      }
    }
    TaxRate tax = OBDal.getInstance().get(TaxRate.class, taxId);
    if (tax != null) {
      line.setTax(tax);
    }

    line.setOrderedQuantity(BigDecimal.ONE);
    line.setUnitPrice(discountAmt);
    line.setListPrice(discountAmt);
    line.setStandardPrice(discountAmt);
    line.setPriceLimit(BigDecimal.ZERO);
    line.setLineNetAmount(discountAmt);
    line.setGrossUnitPrice(BigDecimal.ZERO);
    line.setBaseGrossUnitPrice(BigDecimal.ZERO);
    line.setLineGrossAmount(BigDecimal.ZERO);
    line.setDeliveredQuantity(BigDecimal.ZERO);
    line.setInvoicedQuantity(BigDecimal.ZERO);
    line.setReservedQuantity(BigDecimal.ZERO);
    line.setFreightAmount(BigDecimal.ZERO);
    line.setDirectShipment(false);

    OBDal.getInstance().save(line);
    log.debug("Created order discount line: order={}, taxId={}, amt={}", headerId, taxId,
        discountAmt);
  }
}
