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

import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.util.NeoActionAvailability;

/**
 * What the order header customizations say about their actions and response keys (ETP-5714):
 * the actions that do not apply to ONE order for a reason a column cannot express, and the keys
 * the header adds to a GET that no spec field backs.
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

  private OrderDocumentAvailability() {
  }

  /**
   * The {@code documentAction} values that do not apply to this order: Reactivate while it has a
   * linked shipment/receipt or invoice (the {@link AbstractOrderHeaderHandler#hasLinkedDocuments}
   * rule, the very query behind the UI's flag).
   *
   * @param record the record the agent asked about
   * @return {@code action:value → reason}; empty for anything that is not a completed order, when
   *         nothing is linked, or when the lookup fails (advisory: it never guesses)
   */
  static Map<String, String> unavailable(BaseOBObject record) {
    if (!(record instanceof Order)) {
      return Collections.emptyMap();
    }
    Order order = (Order) record;
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
