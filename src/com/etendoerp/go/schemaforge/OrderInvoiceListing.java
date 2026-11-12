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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

/**
 * The listing actions of {@link ImportOrderLinesIntoInvoiceHandler} ({@code availableOrders},
 * {@code availableOrderLines}; the {@code pendingBasis: "classic"} rule was removed in ETP-5828,
 * QA MCP-2, once the purchase popup listed with the server rule), moved unchanged
 * out of the handler (ETP-6099, the handler was past the method count Sonar accepts). Every DAL
 * read goes back through the handler's seams, so what a test overrides on the handler is still
 * what runs.
 */
final class OrderInvoiceListing {

  /** How many order line ids one draft-quantities query binds at most. */
  private static final int DRAFT_QUERY_CHUNK = 1000;

  private final ImportOrderLinesIntoInvoiceHandler handler;

  OrderInvoiceListing(ImportOrderLinesIntoInvoiceHandler handler) {
    this.handler = handler;
  }

  /** A return order is credited through its own flow, never imported into an invoice. */
  static boolean isReturn(Order order) {
    return order.getTransactionDocument() != null
        && Boolean.TRUE.equals(order.getTransactionDocument().isReturn());
  }

  // ---------------------------------------------------------------------------
  // availableOrders / availableOrderLines
  // ---------------------------------------------------------------------------

  /**
   * The invoice's "Importar desde pedido" popup lists through this action (ETP-5828 sales,
   * ETP-6099 purchase) instead of reading up to 500 orders of the whole company and filtering them
   * in the browser, which hid a partner's older orders once the company had more than 500. The
   * caller may ask for a longer page ({@code limit}, up to
   * {@value ImportOrderLinesIntoInvoiceHandler#MAX_LISTED_ORDERS}) and page through the scan
   * ({@code offset}, see {@link ImportListing}). On a sales invoice the candidates are only the
   * orders that still have a line not fully invoiced, newest created first; a purchase invoice
   * keeps its order-date scan. {@code includeCovered} also lists the orders whose pending (before
   * drafts) is entirely held by draft invoices, at {@code pendingLines} 0.
   */
  NeoResponse availableOrders(Invoice invoice, JSONObject body) throws JSONException {
    boolean sales = Boolean.TRUE.equals(invoice.isSalesTransaction());
    String partnerId = invoice.getBusinessPartner() == null ? null
        : invoice.getBusinessPartner().getId();
    String currencyId = invoice.getCurrency() == null ? null : invoice.getCurrency().getId();
    ImportListing.ListRequest request = handler.listRequest(body);
    List<Order> candidates = request.offset == 0
        ? handler.findCandidateOrders(partnerId, sales, currencyId, request.scan)
        : handler.findCandidateOrders(partnerId, sales, currencyId, request.offset, request.scan);
    Function<Order, Map<String, BigDecimal>> pendingOf = pendingLookup(candidates, sales);
    // ETP-6099: with detail, the lines THIS invoice already holds are not selectable in the popup.
    Set<String> importedHere = request.detail ? handler.orderLinesOnInvoice(invoice.getId())
        : Set.of();
    return handler.discover(candidates, order -> availableOrderRow(order, partnerId,
        ImportOrderLinesIntoInvoiceHandler.selectableOnly(pendingOf.apply(order), importedHere),
        request), request);
  }

  /**
   * The pending of each candidate of {@link #availableOrders}: on the sales side (ETP-5828, like
   * limit / scan / prefilter) the draft quantities of every candidate's lines are read in one pass
   * (chunked), not one query per order, and subtracted exactly as
   * {@link ImportOrderLinesIntoInvoiceHandler#pendingToInvoice} would; the purchase side keeps the
   * lazy per-order {@code pendingToInvoice}. A return order has none.
   */
  private Function<Order, Map<String, BigDecimal>> pendingLookup(List<Order> candidates,
      boolean sales) {
    if (!sales) {
      return order -> isReturn(order) ? Map.of() : handler.pendingToInvoice(order, false);
    }
    Map<String, Map<String, BigDecimal>> baseByOrder = new HashMap<>();
    Set<String> lineIds = new java.util.LinkedHashSet<>();
    for (Order candidate : candidates) {
      if (!isReturn(candidate)) {
        Map<String, BigDecimal> base = OrderInvoicePending.beforeDrafts(candidate);
        baseByOrder.put(candidate.getId(), base);
        lineIds.addAll(base.keySet());
      }
    }
    Map<String, BigDecimal> drafts = draftQuantitiesInChunks(lineIds, true);
    return order -> isReturn(order) ? Map.of()
        : ImportOrderLinesIntoInvoiceHandler.afterDrafts(baseByOrder.get(order.getId()), drafts);
  }

  /**
   * One {@code availableOrders} row, or {@code null} when the order has nothing pending (with
   * {@code includeCovered}: nothing pending even before drafts, ETP-6099). With {@code detail} the
   * {@code pending} map holds only the selectable lines
   * ({@link ImportOrderLinesIntoInvoiceHandler#selectableOnly}), so an order whose remaining lines
   * are all on this invoice comes at {@code pendingLines} 0 and is listed only with
   * {@code includeCovered} (its lines are held by a draft: this one).
   */
  private JSONObject availableOrderRow(Order order, String partnerId,
      Map<String, BigDecimal> pending, ImportListing.ListRequest request) throws JSONException {
    int pendingLines = pending.size();
    if (pendingLines == 0 && !(request.includeCovered && isHeldByDrafts(order))) {
      return null;
    }
    return orderRow(order, partnerId, pending, request);
  }

  /**
   * The row both {@code availableOrders} rules build for a listed order: the document row, the
   * popup's "Dto. global X %" tag (always a %, 0 when the order has none), the popup's row amount
   * (the plain sum of {@code pending}, no global discount applied) and, with {@code detail}, the
   * creation date.
   */
  private JSONObject orderRow(Order order, String partnerId, Map<String, BigDecimal> pending,
      ImportListing.ListRequest request) throws JSONException {
    String orderCurrency = order.getCurrency() == null ? null : order.getCurrency().getId();
    JSONObject row = AbstractLineImportHandler.documentRow(order.getId(), order.getDocumentNo(),
        AbstractLineImportHandler.day(order.getOrderDate()), partnerId,
        order.getBusinessPartner().getName(), orderCurrency, pending.size());
    row.put(ImportOrderLinesIntoInvoiceHandler.KEY_GLOBAL_DISCOUNT,
        InvoiceGlobalDiscountRule.reportedPercent(order.getEtgoTotalDiscount()));
    row.put(ImportOrderLinesIntoInvoiceHandler.KEY_AMOUNT,
        handler.pendingOrderAmount(order, pending));
    if (request.detail) {
      ImportListing.putCreationDate(row, order.getCreationDate());
    }
    return row;
  }

  /** An order (not a return) with something to invoice before drafts: drafts hold all of it. */
  private static boolean isHeldByDrafts(Order order) {
    return !isReturn(order) && !OrderInvoicePending.beforeDrafts(order).isEmpty();
  }

  /**
   * {@link ImportOrderLinesIntoInvoiceHandler#draftInvoicedQuantities} over any number of lines,
   * {@value #DRAFT_QUERY_CHUNK} at a time.
   */
  private Map<String, BigDecimal> draftQuantitiesInChunks(Collection<String> lineIds,
      boolean sales) {
    Map<String, BigDecimal> held = new HashMap<>();
    List<String> ids = new ArrayList<>(lineIds);
    for (int from = 0; from < ids.size(); from += DRAFT_QUERY_CHUNK) {
      held.putAll(handler.draftInvoicedQuantities(
          ids.subList(from, Math.min(ids.size(), from + DRAFT_QUERY_CHUNK)), sales));
    }
    return held;
  }

  /**
   * The lines of the order that can still be invoiced here. With {@code includeCovered} (a sales
   * invoice only, ETP-5828) also the lines with nothing left, at {@code pendingQuantity} 0: the
   * import popup shows them greyed out as already imported, and so reads every line it shows, with
   * its price, from this ONE call instead of a second request per order.
   */
  NeoResponse availableOrderLines(Invoice invoice, Order order, boolean includeCovered,
      boolean detail) throws JSONException {
    boolean sales = Boolean.TRUE.equals(order.isSalesTransaction());
    Map<String, BigDecimal> pending = handler.pendingToInvoice(order, sales);
    // ETP-6099: with detail the answer also marks the lines already on this invoice, otherwise it
    // stays as it was.
    Set<String> importedHere = detail ? handler.orderLinesOnInvoice(invoice.getId()) : Set.of();
    // ETP-5828 QA UI-4: which other drafts hold each line, so a taken line can be named.
    List<String> lineIds = new ArrayList<>();
    for (OrderLine ol : order.getOrderLineList()) {
      if (OrderImportListing.isListedLine(ol)) {
        lineIds.add(ol.getId());
      }
    }
    Map<String, List<OrderInvoicePending.DraftInvoiceRef>> holders =
        handler.draftInvoicesHolding(lineIds, sales, invoice.getId());
    JSONArray rows = new JSONArray();
    for (OrderLine ol : order.getOrderLineList()) {
      BigDecimal left = pending.get(ol.getId());
      if (left == null && includeCovered && OrderImportListing.isListedLine(ol)) {
        left = BigDecimal.ZERO;
      }
      if (left != null) {
        JSONObject row = OrderImportListing.availableLineRow(order, ol, left);
        putDraftHolders(row, holders.get(ol.getId()));
        if (detail) {
          OrderImportListing.putLineDetail(row, ol);
          row.put(ImportOrderLinesIntoInvoiceHandler.KEY_IMPORTED_HERE,
              importedHere.contains(ol.getId()));
        }
        rows.put(row);
      }
    }
    return handler.completePage(rows);
  }

  /** {@code inDraftInvoices} on a line row, only when another draft holds the line. */
  private static void putDraftHolders(JSONObject row,
      List<OrderInvoicePending.DraftInvoiceRef> drafts) throws JSONException {
    if (drafts == null || drafts.isEmpty()) {
      return;
    }
    JSONArray refs = new JSONArray();
    for (OrderInvoicePending.DraftInvoiceRef draft : drafts) {
      refs.put(new JSONObject().put("id", draft.id).put("documentNo", draft.documentNo)
          .put("quantity", OrderImportListing.trimmed(draft.quantity)));
    }
    row.put(ImportOrderLinesIntoInvoiceHandler.KEY_IN_DRAFT_INVOICES, refs);
  }
}
