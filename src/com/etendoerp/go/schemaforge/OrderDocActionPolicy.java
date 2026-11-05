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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.util.NeoActionAvailability;

/**
 * The mechanics of an order window's {@code documentAction} ALLOW-list (ETP-6026, ETP-6108): what
 * the window offers per stored {@code documentStatus}, evaluated for the advice
 * ({@link #addUnavailable}) and enforced in the pre-hook ({@link #refuseUnlessAllowed}).
 *
 * <p><b>Called explicitly, by value.</b> It names no entity, spec or table: each order
 * customization ({@link SalesOrderHeaderHandler}, {@link PurchaseOrderHeaderHandler}) builds one
 * instance with its own allow-list, reasons and message keys, and calls it from its own
 * {@code unavailableActions} / {@code handle}. An entity gets the rule only by constructing it.
 * The decision on the value that will actually run, the 404 and the fail-closed 500 are
 * {@link DocActionRefusalGuard}'s.</p>
 */
final class OrderDocActionPolicy {

  private final Map<String, Set<String>> allowedByStatus;
  private final Map<String, Set<String>> leftToExistingRules;
  private final List<String> docActionValues;
  private final Function<String, String> reasonNotOffered;
  private final UnaryOperator<String> refusalKeyOf;
  private final String recordLabel;

  /**
   * @param allowedByStatus     the values the window sends, per stored status; a status that is not
   *                            a key accepts nothing
   * @param leftToExistingRules values let through, per status, to the rule that answered them
   *                            before; not advertised
   * @param docActionValues     the values an agent could send, walked to build the advice
   * @param reasonNotOffered    why a value is unavailable, per value
   * @param refusalKeyOf        the {@code AD_MESSAGE} of the 400, per refused value
   * @param recordLabel         how the order is named in the "cannot be loaded" failure
   */
  OrderDocActionPolicy(Map<String, Set<String>> allowedByStatus,
      Map<String, Set<String>> leftToExistingRules, List<String> docActionValues,
      Function<String, String> reasonNotOffered, UnaryOperator<String> refusalKeyOf,
      String recordLabel) {
    this.allowedByStatus = allowedByStatus;
    this.leftToExistingRules = leftToExistingRules;
    this.docActionValues = docActionValues;
    this.reasonNotOffered = reasonNotOffered;
    this.refusalKeyOf = refusalKeyOf;
    this.recordLabel = recordLabel;
  }

  /** The values the window sends in {@code status}; empty for any other status. */
  Set<String> allowedIn(String status) {
    return status == null ? Set.of() : allowedByStatus.getOrDefault(status, Set.of());
  }

  /**
   * Adds to {@code unavailable} every {@code documentAction} value the window does not offer in
   * the subject's stored status, keeping any entry already there. A subject that is not an
   * {@link Order} adds nothing.
   */
  void addUnavailable(Map<String, String> unavailable, BaseOBObject subject) {
    if (!(subject instanceof Order)) {
      return;
    }
    Set<String> allowed = allowedIn(((Order) subject).getDocumentStatus());
    for (String value : docActionValues) {
      if (!allowed.contains(value)) {
        unavailable.putIfAbsent(NeoActionAvailability.valueKey(
            OrderDocumentAvailability.ACTION_DOCUMENT_ACTION, value), reasonNotOffered.apply(value));
      }
    }
  }

  /**
   * The values the pre-hook lets through: the window's allow-list plus the values left to existing
   * rules. Read under admin mode, as the DocAction resolution reads the record.
   *
   * @param orderId the order the button runs on
   * @return the accepted values
   * @throws IllegalStateException when the order cannot be loaded — the guard then fails closed
   */
  Set<String> allowedDocActions(String orderId) {
    OBContext.setAdminMode(true);
    try {
      Order order = OBDal.getInstance().get(Order.class, orderId);
      if (order == null) {
        throw new IllegalStateException(recordLabel + " " + orderId + " cannot be loaded");
      }
      String status = order.getDocumentStatus();
      Set<String> allowed = new HashSet<>(allowedIn(status));
      if (status != null) {
        allowed.addAll(leftToExistingRules.getOrDefault(status, Set.of()));
      }
      return allowed;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The 400 for a value the window does not offer (500 when the status cannot be read), or
   * {@code null} when the request may continue. See
   * {@link DocActionRefusalGuard#refuseUnlessAllowed}.
   */
  NeoResponse refuseUnlessAllowed(NeoContext context) {
    return DocActionRefusalGuard.refuseUnlessAllowed(context, this::allowedDocActions,
        refusalKeyOf);
  }
}
