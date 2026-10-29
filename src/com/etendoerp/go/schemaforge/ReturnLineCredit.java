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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * The per-line arithmetic of the return import into a rectificative invoice (ETP-5876), split out of
 * {@link ImportInOutLinesIntoInvoiceHandler}: what each side's modal leaves of a return line to
 * credit, and the reference prices the native process writes that the screen leaves at 0. The
 * database reads that feed it stay in the handler, which tests replace.
 */
final class ReturnLineCredit {

  private ReturnLineCredit() {
  }

  /**
   * What each return line can still be credited, as a POSITIVE magnitude (lines with nothing left
   * are absent).
   *
   * @param lines        the return's active lines by id
   * @param inTarget     the movement lines the target invoice already holds a line for
   * @param presenceOnly the purchase modal's rule (quantity, skipping only {@code inTarget});
   *                     {@code false} is the sales modal's (netting {@code credited})
   * @param credited     magnitude already invoiced per return line, any invoice status (sales)
   * @param blocked      order lines the sales modal treats as taken
   */
  static Map<String, BigDecimal> pending(Map<String, ShipmentInOutLine> lines,
      Set<String> inTarget, boolean presenceOnly, Map<String, BigDecimal> credited,
      Set<String> blocked) {
    Map<String, BigDecimal> pending = new LinkedHashMap<>();
    for (Map.Entry<String, ShipmentInOutLine> entry : lines.entrySet()) {
      BigDecimal movement = entry.getValue().getMovementQuantity();
      if (movement == null || movement.signum() == 0) {
        continue;
      }
      BigDecimal left = presenceOnly ? presenceLeft(entry.getKey(), movement, inTarget)
          : nettedLeft(entry, movement, credited, blocked);
      if (left.signum() > 0) {
        pending.put(entry.getKey(), left);
      }
    }
    return pending;
  }

  private static BigDecimal presenceLeft(String id, BigDecimal movement, Set<String> inTarget) {
    return inTarget.contains(id) ? BigDecimal.ZERO : movement.abs();
  }

  private static BigDecimal nettedLeft(Map.Entry<String, ShipmentInOutLine> entry,
      BigDecimal movement, Map<String, BigDecimal> credited, Set<String> blocked) {
    ShipmentInOutLine returnLine = entry.getValue();
    if (returnLine.getSalesOrderLine() != null
        && blocked.contains(returnLine.getSalesOrderLine().getId())) {
      return BigDecimal.ZERO;
    }
    return movement.abs().subtract(credited.getOrDefault(entry.getKey(), BigDecimal.ZERO));
  }

  /** The movement lines this invoice already holds an active line for. */
  static Set<String> movementLineIdsOf(Invoice invoice) {
    Set<String> ids = new HashSet<>();
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (line.isActive() && line.getGoodsShipmentLine() != null) {
        ids.add(line.getGoodsShipmentLine().getId());
      }
    }
    return ids;
  }

  /**
   * The native process stores the price list's standard price and price limit on a line it prices
   * from the price list; the return modal's body carries neither, so the screen stores 0 in both.
   * Cleared on the lines {@code ids} only, in the same transaction; {@code priceactual},
   * {@code pricelist} and every amount are left as the process set them.
   */
  static void clearReferencePrices(Invoice invoice, Set<String> ids) {
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (ids.contains(line.getId())) {
        line.setStandardPrice(BigDecimal.ZERO);
        line.setPriceLimit(BigDecimal.ZERO);
        OBDal.getInstance().save(line);
      }
    }
    OBDal.getInstance().flush();
  }
}
