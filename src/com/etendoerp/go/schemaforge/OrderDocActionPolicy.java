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
 * instance with its own wording (a {@link Messages}), and calls it from its own
 * {@code unavailableActions} / {@code handle}. The allow-list is the one the order windows share —
 * Confirm on a draft or in-progress order, Reactivate on a booked one — and is a property of the
 * window that opts in, not of this class. An entity gets the rule only by constructing it.
 * The decision on the value that will actually run, the 404 and the fail-closed 500 are
 * {@link DocActionRefusalGuard}'s.</p>
 */
final class OrderDocActionPolicy {

  /** Close, which no order window offers. */
  static final String DOC_ACTION_CLOSE = "CL";

  /** Void, which no order window offers. */
  static final String DOC_ACTION_VOID = "VO";

  /** The values the order windows send, per stored status; any other status accepts nothing. */
  static final Map<String, Set<String>> ALLOWED_BY_STATUS = Map.of(
      "DR", Set.of("CO"),
      "IP", Set.of("CO"),
      "CO", Set.of("CO", "RE"));

  /**
   * Values let through, per status, to the rule that answered them before: Reactivate on a draft,
   * which {@code DocActionReactivationRule} refuses with 422 {@code ETGO_ReactivateOnlyCompleted}.
   * Not advertised.
   */
  static final Map<String, Set<String>> LEFT_TO_EXISTING_RULES = Map.of("DR", Set.of("RE"));

  /** The values of the {@code C_Order.DocAction} list an agent could send, for the advice. */
  static final List<String> DOC_ACTION_VALUES =
      List.of("CO", "RE", "VO", "CL", "PR", "AP", "RJ", "PO", "RA", "RC", "XL");

  /** The refusal keys and advice reasons of one order window. */
  static final class Messages {
    private final String closeKey;
    private final String closeReason;
    private final String voidKey;
    private final String voidReason;
    private final String otherKey;

    /**
     * @param closeKey    the {@code AD_MESSAGE} of the 400 for Close
     * @param closeReason why Close is unavailable, as advertised
     * @param voidKey     the {@code AD_MESSAGE} of the 400 for Void
     * @param voidReason  why Void is unavailable, as advertised
     * @param otherKey    the {@code AD_MESSAGE} of the 400 for any other refused value
     */
    Messages(String closeKey, String closeReason, String voidKey, String voidReason,
        String otherKey) {
      this.closeKey = closeKey;
      this.closeReason = closeReason;
      this.voidKey = voidKey;
      this.voidReason = voidReason;
      this.otherKey = otherKey;
    }
  }

  private final Messages messages;
  private final String noun;
  private final String bookedWord;

  /**
   * @param noun       how the order is named in advice and failures, e.g. {@code sales order}
   * @param bookedWord how its completed state is named, e.g. {@code booked}
   * @param messages   the window's refusal keys and reasons
   */
  OrderDocActionPolicy(String noun, String bookedWord, Messages messages) {
    this.noun = noun;
    this.bookedWord = bookedWord;
    this.messages = messages;
  }

  /** The values the window sends in {@code status}; empty for any other status. */
  Set<String> allowedIn(String status) {
    return status == null ? Set.of() : ALLOWED_BY_STATUS.getOrDefault(status, Set.of());
  }

  /** Why {@code value} is unavailable: Close's and Void's own reason, else the generic one. */
  String reasonNotOffered(String value) {
    if (DOC_ACTION_CLOSE.equals(value)) {
      return messages.closeReason;
    }
    if (DOC_ACTION_VOID.equals(value)) {
      return messages.voidReason;
    }
    return "The app does not offer documentAction " + value + " on a " + noun + " in this "
        + "status: it confirms (CO) a draft or in-progress order and reactivates (RE) a "
        + bookedWord + " one, nothing else.";
  }

  /** The refusal key for a value the pre-hook does not allow. */
  String refusalKeyOf(String value) {
    if (DOC_ACTION_CLOSE.equals(value)) {
      return messages.closeKey;
    }
    if (DOC_ACTION_VOID.equals(value)) {
      return messages.voidKey;
    }
    return messages.otherKey;
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
    for (String value : DOC_ACTION_VALUES) {
      if (!allowed.contains(value)) {
        unavailable.putIfAbsent(NeoActionAvailability.valueKey(
            OrderDocumentAvailability.ACTION_DOCUMENT_ACTION, value), reasonNotOffered(value));
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
        throw new IllegalStateException(Character.toUpperCase(noun.charAt(0)) + noun.substring(1) + " " + orderId + " cannot be loaded");
      }
      String status = order.getDocumentStatus();
      Set<String> allowed = new HashSet<>(allowedIn(status));
      if (status != null) {
        allowed.addAll(LEFT_TO_EXISTING_RULES.getOrDefault(status, Set.of()));
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
        this::refusalKeyOf);
  }
}
