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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

/**
 * What is still pending to invoice on each line of an order, as ONE rule (ETP-5713, made shared in
 * ETP-5828): ordered minus invoiced ({@code C_OrderLine.QtyInvoiced}, moved only by a COMPLETED
 * invoice) minus what the lines of every DRAFT invoice of the same direction already hold against
 * that order line. A line with nothing left, an over-invoiced one or the synthetic total-discount
 * line is absent.
 *
 * <p>Its readers: the invoice's {@code availableOrders} / {@code availableOrderLines} /
 * {@code importOrderLines} ({@link ImportOrderLinesIntoInvoiceHandler}) and, on the sales side, the
 * order's own "Generar factura" ({@code createDraftInvoice}, {@code needsInvoiceDoc} and the
 * action's availability advice). Each caller decides whether it applies the draft part; nothing
 * here selects an entity or a direction.</p>
 */
final class OrderInvoicePending {

  /**
   * Per order line, the quantity held by the active lines of DRAFT invoices of the direction.
   * Deliberately the very query the order import has always used.
   */
  static final String DRAFT_QUANTITIES_HQL = "select il.salesOrderLine.id, il.invoicedQuantity "
      + "from InvoiceLine il join il.invoice i where i.documentStatus = 'DR' "
      + "and i.salesTransaction = :sot and il.active = true and il.salesOrderLine.id in :ids";

  private OrderInvoicePending() {
  }

  /**
   * Ordered minus invoiced per order line, signed like the ordered quantity: inactive lines, lines
   * without a product, the total-discount line and lines with nothing left (or over-invoiced) are
   * absent. Drafts are NOT subtracted here.
   *
   * @param order the order
   * @return {@code orderLineId -> pending}, in the order's line order
   */
  static Map<String, BigDecimal> beforeDrafts(Order order) {
    Map<String, BigDecimal> base = new LinkedHashMap<>();
    for (OrderLine ol : order.getOrderLineList()) {
      if (!ol.isActive() || ol.getProduct() == null
          || TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(ol.getProduct().getId())) {
        continue;
      }
      BigDecimal ordered = ol.getOrderedQuantity() == null ? BigDecimal.ZERO
          : ol.getOrderedQuantity();
      BigDecimal invoiced = ol.getInvoicedQuantity() == null ? BigDecimal.ZERO
          : ol.getInvoicedQuantity();
      BigDecimal pending = ordered.subtract(invoiced);
      if (pending.signum() != 0 && pending.signum() == ordered.signum()) {
        base.put(ol.getId(), pending);
      }
    }
    return base;
  }

  /**
   * {@code base} minus {@code drafts}; a line a draft already covers (or over-covers) drops out.
   *
   * @param base   pending before drafts, from {@link #beforeDrafts}
   * @param drafts held by drafts per order line, from {@link #draftQuantities}
   * @return what is left per order line, signed like {@code base}
   */
  static Map<String, BigDecimal> afterDrafts(Map<String, BigDecimal> base,
      Map<String, BigDecimal> drafts) {
    Map<String, BigDecimal> remaining = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> entry : base.entrySet()) {
      BigDecimal left = entry.getValue().subtract(
          drafts.getOrDefault(entry.getKey(), BigDecimal.ZERO));
      if (left.signum() == entry.getValue().signum()) {
        remaining.put(entry.getKey(), left);
      }
    }
    return remaining;
  }

  /**
   * Per order line, what the lines of DRAFT invoices of the direction already hold.
   *
   * @param orderLineIds the order lines; must not be empty
   * @param sales        the direction of the drafts to count
   * @return {@code orderLineId -> held quantity}; a line no draft holds is absent
   */
  static Map<String, BigDecimal> draftQuantities(Collection<String> orderLineIds, boolean sales) {
    List<Object[]> rows = OBDal.getInstance().getSession()
        .createQuery(DRAFT_QUANTITIES_HQL, Object[].class)
        .setParameter("sot", sales).setParameterList("ids", orderLineIds).list();
    Map<String, BigDecimal> held = new LinkedHashMap<>();
    for (Object[] row : rows) {
      held.merge((String) row[0], row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1],
          BigDecimal::add);
    }
    return held;
  }

  /**
   * The DRAFT invoices (of the direction, {@code exceptInvoiceId} left out) whose active lines hold
   * each order line, by document number: the same population {@link #DRAFT_QUANTITIES_HQL} counts.
   */
  static final String DRAFT_HOLDERS_HQL = "select distinct il.salesOrderLine.id, i.id, "
      + "i.documentNo from InvoiceLine il join il.invoice i where i.documentStatus = 'DR' "
      + "and i.salesTransaction = :sot and il.active = true and il.salesOrderLine.id in :ids "
      + "and i.id <> :inv order by i.documentNo, i.id";

  /** A draft invoice holding part of an order line: what the popup and the agent are told. */
  static final class DraftInvoiceRef {
    final String id;
    final String documentNo;

    DraftInvoiceRef(String id, String documentNo) {
      this.id = id;
      this.documentNo = documentNo;
    }
  }

  /**
   * Which OTHER draft invoices hold each order line (ETP-5828 QA UI-4): an order line the popup
   * shows as taken can name the draft that took it instead of a bare «ya importado».
   *
   * @param orderLineIds    the order lines; an empty collection reads nothing
   * @param sales           the direction of the drafts
   * @param exceptInvoiceId the invoice the caller imports into (its own lines are "imported here")
   * @return {@code orderLineId -> drafts by document number}; a line no other draft holds is absent
   */
  static Map<String, List<DraftInvoiceRef>> draftHolders(Collection<String> orderLineIds,
      boolean sales, String exceptInvoiceId) {
    Map<String, List<DraftInvoiceRef>> holders = new LinkedHashMap<>();
    if (orderLineIds.isEmpty()) {
      return holders;
    }
    List<Object[]> rows = OBDal.getInstance().getSession()
        .createQuery(DRAFT_HOLDERS_HQL, Object[].class)
        .setParameter("sot", sales).setParameter("inv", exceptInvoiceId)
        .setParameterList("ids", orderLineIds).list();
    for (Object[] row : rows) {
      holders.computeIfAbsent((String) row[0], k -> new ArrayList<>())
          .add(new DraftInvoiceRef((String) row[1], (String) row[2]));
    }
    return holders;
  }

  /**
   * The whole rule: {@link #beforeDrafts} minus {@link #draftQuantities}.
   *
   * @param order the order
   * @param sales the direction of the drafts to count
   * @return what is left per order line; empty when nothing is
   */
  static Map<String, BigDecimal> netOfDrafts(Order order, boolean sales) {
    Map<String, BigDecimal> base = beforeDrafts(order);
    if (base.isEmpty()) {
      return base;
    }
    return afterDrafts(base, draftQuantities(base.keySet(), sales));
  }
}
