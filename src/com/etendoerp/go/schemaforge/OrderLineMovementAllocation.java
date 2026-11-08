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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * The ONE rule for binding the quantity of an order line to the receipt / shipment lines that
 * delivered it (ETP-6002): the order line's COMPLETED movement lines, oldest first (movement date,
 * then id), each taking up to what it still has to invoice, the remainder left unbound. A quantity
 * above the oldest line's pending is SPLIT over the next ones.
 *
 * <p>"Still to invoice" is {@link NeoInvoiceSupport#computePendingQtyPerLineOrThrow} with drafts
 * included — the receipt / shipment cap: a movement line already held by a draft invoice is not
 * offered again. A candidate must have the order line's product and unit and the sign of the
 * requested quantity, and it is capped by its own movement quantity.
 *
 * <p>Extracted unchanged from {@link ImportOrderLinesIntoInvoiceHandler} (the declared
 * {@code importOrderLines} action, both directions) so the sales "create invoice from order" path
 * ({@link CreateDraftInvoiceHandler} → {@link InvoiceLineLinker}) uses the same rule instead of a
 * second one. Nothing here depends on the direction: purchase behaves exactly as before.
 */
final class OrderLineMovementAllocation {

  private OrderLineMovementAllocation() {
  }

  /** One movement line that can take part of an order line's quantity, and how much of it. */
  static final class Candidate {
    final ShipmentInOutLine line;
    /** Still invoiceable on this line, signed like it; drafts and invoices already deducted. */
    final BigDecimal pending;

    Candidate(ShipmentInOutLine line, BigDecimal pending) {
      this.line = line;
      this.pending = pending;
    }
  }

  /** A slice of the requested quantity assigned to one movement line. */
  static final class Allocation {
    final ShipmentInOutLine line;
    final BigDecimal quantity;

    Allocation(ShipmentInOutLine line, BigDecimal quantity) {
      this.line = line;
      this.quantity = quantity;
    }
  }

  /**
   * The COMPLETED receipt / shipment lines of an order line, oldest first (movement date, then
   * id): the lines an invoice quantity is distributed over before any remainder.
   */
  static List<ShipmentInOutLine> completedMovementLines(OrderLine orderLine) {
    return OBDal.getInstance().getSession().createQuery(
        "select il from MaterialMgmtShipmentInOutLine il join il.shipmentReceipt sh "
            + "where il.salesOrderLine.id = :id and sh.processed = 'Y' "
            + "and sh.documentStatus = 'CO' and il.active = true "
            + "order by sh.movementDate, il.id", ShipmentInOutLine.class)
        .setParameter("id", orderLine.getId()).list();
  }

  /** Pending MAGNITUDE per movement line of one document, drafts included (the receipt cap). */
  static Map<String, BigDecimal> movementPending(String inOutId) {
    return NeoInvoiceSupport.computePendingQtyPerLineOrThrow(inOutId, true);
  }

  /** The documents the given movement lines belong to, in first-seen order. */
  static Set<String> documentIdsOf(Collection<ShipmentInOutLine> movements) {
    Set<String> ids = new LinkedHashSet<>();
    for (ShipmentInOutLine movement : movements) {
      ids.add(movement.getShipmentReceipt().getId());
    }
    return ids;
  }

  /** The movement lines that can take quantity of this order line: same product, unit and sign. */
  static List<Candidate> candidatesOf(OrderLine orderLine, BigDecimal quantity,
      List<ShipmentInOutLine> movements, Map<String, BigDecimal> pendingByMovement) {
    List<Candidate> candidates = new ArrayList<>();
    for (ShipmentInOutLine movement : movements) {
      BigDecimal moved = movement.getMovementQuantity();
      BigDecimal magnitude = pendingByMovement.get(movement.getId());
      if (moved == null || moved.signum() != quantity.signum() || magnitude == null
          || !sameProductAndUnit(orderLine, movement)) {
        continue;
      }
      BigDecimal capped = magnitude.min(moved.abs());
      if (capped.signum() > 0) {
        candidates.add(new Candidate(movement, moved.signum() < 0 ? capped.negate() : capped));
      }
    }
    return candidates;
  }

  private static boolean sameProductAndUnit(OrderLine orderLine, ShipmentInOutLine movement) {
    return orderLine.getProduct() != null && movement.getProduct() != null
        && orderLine.getProduct().getId().equals(movement.getProduct().getId())
        && orderLine.getUOM() != null && movement.getUOM() != null
        && orderLine.getUOM().getId().equals(movement.getUOM().getId());
  }

  /**
   * Distributes {@code quantity} over the candidates in the order given (oldest first), each up to
   * its own pending, keeping the sign of the request. What no candidate can take is the remainder
   * ({@code quantity} minus the sum of the allocations).
   */
  static List<Allocation> allocate(BigDecimal quantity, List<Candidate> candidates) {
    List<Allocation> allocations = new ArrayList<>();
    BigDecimal left = quantity.abs();
    for (Candidate candidate : candidates) {
      if (left.signum() == 0) {
        break;
      }
      if (candidate.pending.signum() == quantity.signum()) {
        BigDecimal taken = left.min(candidate.pending.abs());
        allocations.add(new Allocation(candidate.line,
            quantity.signum() < 0 ? taken.negate() : taken));
        left = left.subtract(taken);
      }
    }
    return allocations;
  }

  /**
   * The whole rule for one order line read fresh: its completed movement lines, their pending,
   * the candidates and the allocation of {@code quantity}. For a caller that plans one order line
   * at a time; the order import reads every document once for several order lines instead.
   */
  static List<Allocation> allocateOrderLine(OrderLine orderLine, BigDecimal quantity) {
    if (orderLine == null || quantity == null || quantity.signum() == 0) {
      return new ArrayList<>();
    }
    List<ShipmentInOutLine> movements = completedMovementLines(orderLine);
    Map<String, BigDecimal> pending = new HashMap<>();
    for (String documentId : documentIdsOf(movements)) {
      pending.putAll(movementPending(documentId));
    }
    return allocate(quantity, candidatesOf(orderLine, quantity, movements, pending));
  }
}
