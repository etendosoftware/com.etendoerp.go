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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.OrderLine;

import com.etendoerp.go.schemaforge.ImportInvoiceLinesIntoInOutHandler.LineState;

/**
 * The pure arithmetic of the goods shipment's "Import from invoice" listing under
 * {@link ImportInvoiceLinesIntoInOutHandler.SourcePolicy#byDirectLinks} (ETP-6002 CP-22, CP-27):
 * the follow-up's verdict as a pending per invoice line, the listed lines at that pending, and the
 * row's pending amount. Kept out of {@link ImportInvoiceLinesIntoInOutHandler} so that class stays
 * within the method limit; no state, no DAL access, no entity name.
 */
final class DirectLinkPending {

  private DirectLinkPending() {
  }

  /**
   * The follow-up's verdict on one invoice as invoice line id to pending (review M3): every
   * carried line with its quantity; none for a missing verdict or one that carries no line
   * (nothing pending, or not eligible).
   */
  static Map<String, BigDecimal> pendingByLine(PendingResolver.Source source) {
    Map<String, BigDecimal> pending = new HashMap<>();
    if (source == null) {
      return pending;
    }
    for (PendingResolver.SourceLine line : source.getLines()) {
      pending.put(line.getSourceLineId(), line.getPendingQty());
    }
    return pending;
  }

  /**
   * The lines of an invoice under {@code byDirectLinks}: each at the pending the invoice's own
   * «Create shipment» follow-up computes (one rule for the popup, the MCP listing and
   * {@code createShipment}, ETP-6002 review M2). A listed line the follow-up does not carry (no
   * product, nothing pending) is at 0. The draft note is the same as under the order-line rule.
   *
   * @param orderLineOf the order line each listed line resolves to, by invoice line id
   * @param pendingByLine {@link #pendingByLine} of the invoice
   * @param otherDrafts the document numbers of the OTHER drafts holding each order line
   */
  static List<LineState> lineStates(List<InvoiceLine> lines, Map<String, OrderLine> orderLineOf,
      Map<String, BigDecimal> pendingByLine, Map<String, Set<String>> otherDrafts) {
    List<LineState> states = new ArrayList<>();
    for (InvoiceLine line : lines) {
      OrderLine orderLine = orderLineOf.get(line.getId());
      BigDecimal pending = pendingByLine.getOrDefault(line.getId(), BigDecimal.ZERO)
          .max(BigDecimal.ZERO);
      states.add(new LineState(line, orderLine == null ? null : orderLine.getId(), pending,
          orderLine == null ? Set.of() : otherDrafts.getOrDefault(orderLine.getId(), Set.of())));
    }
    return states;
  }

  /**
   * The popup's row total of an invoice (ETP-6002 CP-27): unit price x pending over the lines
   * with a positive pending, at the invoice currency's precision — the same figure
   * {@code availableOrders} gives an order ({@code ImportOrderLinesIntoInOutHandler}).
   */
  static BigDecimal pendingAmount(Invoice invoice, List<LineState> lines) {
    BigDecimal sum = BigDecimal.ZERO;
    for (LineState state : lines) {
      if (state.pending.signum() > 0) {
        BigDecimal unitPrice = state.line.getUnitPrice() == null ? BigDecimal.ZERO
            : state.line.getUnitPrice();
        sum = sum.add(unitPrice.multiply(state.pending));
      }
    }
    Currency currency = invoice.getCurrency();
    int precision = currency == null || currency.getStandardPrecision() == null ? 2
        : currency.getStandardPrecision().intValue();
    return sum.setScale(precision, RoundingMode.HALF_UP);
  }
}
