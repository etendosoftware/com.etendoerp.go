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
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

import com.etendoerp.go.schemaforge.util.NeoActionAvailability;

/**
 * What the order header customizations say about their actions and response keys (ETP-5714):
 * the actions that do not apply to ONE order for a reason a column cannot express, and the keys
 * the header adds to a GET that no spec field backs.
 *
 * <p>Besides Reactivate, an order header also says when its two creation actions — the goods
 * document and the invoice — do not apply because nothing is left to receive/ship or invoice, or
 * because a draft already covers it ({@link #unavailable(BaseOBObject, Flow)}): the same answer
 * the app gives by hiding the button.</p>
 *
 * <p>Kept out of {@link AbstractOrderHeaderHandler} on purpose — that class is already far past
 * the size Sonar accepts — and called explicitly by it, so nothing here selects an entity.
 * Advisory only: the guards stay the enforcement.</p>
 */
final class OrderDocumentAvailability {

  private static final Logger log = LogManager.getLogger(OrderDocumentAvailability.class);

  /** The document-action button of an order, as {@code etendo_schema} names it. */
  static final String ACTION_DOCUMENT_ACTION = "documentAction";

  /** Reactivate, the {@code documentAction} value an order with linked documents refuses. */
  static final String VALUE_REACTIVATE = "RE";

  /** Why Reactivate is ruled out: same rule as the SPA's {@code hasLinkedDocuments} flag. */
  static final String REASON_LINKED_DOCUMENTS = "This order has linked shipments, receipts or "
      + "invoices, so it cannot be reactivated: the app hides Reactivate in that case. The other "
      + "documentAction values still apply.";

  /**
   * The keys the order header adds to every GET record and no spec field backs, declared as the
   * handler's {@code responseEnrichedFields()} so an MCP {@code fields:[…]} projection does not
   * report them in {@code unknownFields} while the response carries them.
   */
  static final Set<String> RESPONSE_ENRICHED_FIELDS =
      Set.of("hasLinkedDocuments", "needsPrimaryDoc", "needsInvoiceDoc");

  /**
   * Draft invoices that cover an order: through their lines
   * ({@code C_InvoiceLine.C_OrderLine_ID}) or directly through {@code C_Invoice.C_Order_ID} — the
   * two paths the app's own "needs an invoice" decision unions. Only active invoices and lines
   * count, as on the screen. A plain {@code Session.createQuery} applies no readable-scope
   * filter, so the client and organization restrictions are in the query itself
   * ({@link #draftInvoiceNumbers}).
   */
  static final String DRAFT_INVOICES_HQL = "select distinct i.documentNo from Invoice i "
      + "where i.active = true and i.documentStatus = 'DR' and i.salesTransaction = :sot "
      + "and i.client.id in (:clients) and i.organization.id in (:orgs) "
      + "and (i.salesOrder.id = :orderId or exists (select 1 from InvoiceLine il "
      + "where il.invoice.id = i.id and il.active = true "
      + "and il.salesOrderLine.active = true and il.salesOrderLine.salesOrder.id = :orderId)) "
      + "order by i.documentNo";

  /**
   * What one order header calls its creation actions and which side of the trade it is on. Each
   * header customization passes its own, so nothing here selects an entity.
   *
   * @param sales                  {@code true} for a sales order (shipments), {@code false} for
   *                               a purchase order (goods receipts)
   * @param receiveAction          the action that creates the goods document from the order
   * @param invoiceAction          the action that creates the invoice from the order
   * @param skipTotalDiscountLine  whether the invoice action leaves the total-discount line out of
   *                               what it invoices
   */
  record Flow(boolean sales, String receiveAction, String invoiceAction,
      boolean skipTotalDiscountLine) { }

  private OrderDocumentAvailability() {
  }

  /**
   * {@link #unavailable(BaseOBObject)} plus the creation actions that have nothing to do on this
   * completed order (ETP-5714): the goods-document action when no line has quantity left to
   * receive/ship, and the invoice action when no line has quantity left to invoice or a draft
   * invoice already covers the order. Each answer states the reason and what to do instead.
   *
   * @param subject the record the agent asked about
   * @param flow    the creation actions of the calling header
   * @return {@code action[:value] → reason}; empty for anything that is not a completed order
   */
  static Map<String, String> unavailable(BaseOBObject subject, Flow flow) {
    Map<String, String> unavailable = new LinkedHashMap<>(unavailable(subject));
    if (subject instanceof Order
        && DocumentAvailability.STATUS_COMPLETED.equals(((Order) subject).getDocumentStatus())) {
      Order order = (Order) subject;
      // Each part on its own: a failure computing one creation action must not drop the
      // Reactivate advice above, nor the other action's answer.
      try {
        putIfPresent(unavailable, flow.receiveAction(), receiveReason(order, flow));
      } catch (RuntimeException e) {
        log.warn("Could not judge {} of order {}: {}", flow.receiveAction(), order.getId(),
            e.getMessage());
      }
      try {
        putIfPresent(unavailable, flow.invoiceAction(), invoiceReason(order, flow));
      } catch (RuntimeException e) {
        log.warn("Could not judge {} of order {}: {}", flow.invoiceAction(), order.getId(),
            e.getMessage());
      }
    }
    return unavailable;
  }

  private static void putIfPresent(Map<String, String> into, String action, String reason) {
    if (reason != null) {
      into.put(action, reason);
    }
  }

  /** Why the goods-document action does not apply, or {@code null} when it does. */
  private static String receiveReason(Order order, Flow flow) {
    Set<String> coveringDrafts = new LinkedHashSet<>();
    if (!InOutLineFromOrderFactory.collectPendingLines(order, flow.sales(), coveringDrafts)
        .isEmpty()) {
      return null;
    }
    String verb = flow.sales() ? "ship" : "receive";
    if (coveringDrafts.isEmpty()) {
      return "Nothing is left to " + verb + ": every line of this order is already "
          + (flow.sales() ? "shipped" : "received") + ".";
    }
    return "A draft " + (flow.sales() ? "shipment" : "goods receipt") + " ("
        + String.join(", ", coveringDrafts) + ") already covers what is left to " + verb
        + "; complete or delete it instead of creating another one.";
  }

  /** Why the invoice action does not apply, or {@code null} when it does. */
  private static String invoiceReason(Order order, Flow flow) {
    if (!hasPendingInvoiceLine(order, flow.skipTotalDiscountLine())) {
      return "Nothing is left to invoice: every line of this order is already invoiced.";
    }
    List<String> drafts = draftInvoiceNumbers(order.getId(), flow.sales());
    if (drafts.isEmpty()) {
      return null;
    }
    return "A draft invoice (" + String.join(", ", drafts) + ") already exists for this order; "
        + "complete or delete it instead of creating another one.";
  }

  /**
   * The document numbers of the draft invoices that cover the order, limited to the clients and
   * organizations the current role can read.
   *
   * @param orderId the order
   * @param sales   the order's side of the trade
   * @return the numbers, empty when none
   */
  static List<String> draftInvoiceNumbers(String orderId, boolean sales) {
    OBContext context = OBContext.getOBContext();
    return OBDal.getInstance().getSession()
        .createQuery(DRAFT_INVOICES_HQL, String.class)
        .setParameter("sot", sales)
        .setParameter("orderId", orderId)
        .setParameterList("clients", Arrays.asList(context.getReadableClients()))
        .setParameterList("orgs", Arrays.asList(context.getReadableOrganizations()))
        .list();
  }

  /**
   * Whether any line still has quantity to invoice: ordered minus invoiced, with the sign of the
   * ordered quantity (the rule of the two {@code create…Invoice} handlers; an over-invoiced line
   * is not pending).
   */
  private static boolean hasPendingInvoiceLine(Order order, boolean skipTotalDiscountLine) {
    for (OrderLine line : order.getOrderLineList()) {
      if (!line.isActive() || line.getProduct() == null || (skipTotalDiscountLine
          && TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(line.getProduct().getId()))) {
        continue;
      }
      BigDecimal ordered = line.getOrderedQuantity() != null ? line.getOrderedQuantity()
          : BigDecimal.ZERO;
      BigDecimal invoiced = line.getInvoicedQuantity() != null ? line.getInvoicedQuantity()
          : BigDecimal.ZERO;
      BigDecimal pending = ordered.subtract(invoiced);
      if (pending.signum() != 0 && pending.signum() == ordered.signum()) {
        return true;
      }
    }
    return false;
  }

  /**
   * The {@code documentAction} values that do not apply to this order: Reactivate while it has a
   * linked shipment/receipt or invoice (the {@link AbstractOrderHeaderHandler#hasLinkedDocuments}
   * rule, the very query behind the UI's flag).
   *
   * @param subject the record the agent asked about
   * @return {@code action:value → reason}; empty for anything that is not a completed order, when
   *         nothing is linked, or when the lookup fails (advisory: it never guesses)
   */
  static Map<String, String> unavailable(BaseOBObject subject) {
    if (!(subject instanceof Order)) {
      return Collections.emptyMap();
    }
    Order order = (Order) subject;
    if (!DocumentAvailability.STATUS_COMPLETED.equals(order.getDocumentStatus())) {
      return Collections.emptyMap();
    }
    try {
      if (AbstractOrderHeaderHandler.hasLinkedDocuments(order.getId())) {
        return Map.of(NeoActionAvailability.valueKey(ACTION_DOCUMENT_ACTION, VALUE_REACTIVATE),
            REASON_LINKED_DOCUMENTS);
      }
    } catch (SQLException e) {
      log.debug("Could not read the linked documents of order {}: {}", order.getId(),
          e.getMessage());
    }
    return Collections.emptyMap();
  }
}
