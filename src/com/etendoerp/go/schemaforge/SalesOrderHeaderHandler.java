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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport;
import com.etendoerp.go.schemaforge.util.NeoActionAvailability;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import org.openbravo.base.structure.BaseOBObject;
import java.util.Set;

/**
 * NeoHandler for the Sales Order header entity.
 *
 * <p>ETP-5238: the {@code paymentMethod} SELECTOR is served by
 * {@link PaymentMethodSelectorSupport}, independent of Financial Account linkage.
 *
 * Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code createShipment} → {@link CreateShipmentHandler}</li>
 *   <li>{@code createDraftInvoice} / {@code checkDraftInvoice} / {@code listInvoices} → {@link CreateDraftInvoiceHandler}</li>
 * </ul>
 *
 * <p>Before the Complete action (documentAction=CO), creates the total discount line so it is
 * included in the completed document. Delegates to {@link TotalDiscountService} via the shared
 * helper in {@link AbstractOrderHeaderHandler}.
 *
 * <p>GET post-hook (hasLinkedDocuments, plus the ETP-5295 needsPrimaryDoc/needsInvoiceDoc
 * annotations) is inherited from {@link AbstractOrderHeaderHandler}.
 *
 * <p>ETP-5704: Reactivate ({@code DocAction} = {@code RE}) on an order with linked shipments or
 * invoices is refused before the button runs — see {@link OrderReactivationGuard}. Same rule as
 * the {@code hasLinkedDocuments} flag the SPA hides the action with; REST and MCP both reach this
 * pre-hook.
 *
 * <p>ETP-5704 (QA CP-20): Close ({@code DocAction} = {@code CL}) is refused on every sales order,
 * linked documents or not — the Sales Order window never offers it, and core's Close zeroes the
 * pending quantities and leaves a draft shipment dangling. Same resolution and fail-closed rules
 * as Reactivate ({@link DocActionRefusalGuard}); advertised by {@link #unavailableActions}.
 * Purchase orders do not opt in.
 *
 * <p>ETP-6026: the {@code DocAction} button is an ALLOW-list of what the window offers, decided on
 * the stored {@code documentStatus} ({@link #ALLOWED_BY_STATUS}): Confirm ({@code CO}) on a draft
 * ({@code DR}) or an in-progress ({@code IP}) order — the form shows Confirmar on both, since an
 * {@code IP} order is not processed — and Reactivate ({@code RE}) on a booked one ({@code CO}).
 * {@code CO} on a booked order is also let through: the list's bulk bar sends it to every
 * selected row in a mixed draft + booked selection, and {@code DocActionIdempotency} answers that
 * row "already applied" without running anything. {@code RE} on a draft is left to the rule that
 * answered it before ({@link #LEFT_TO_EXISTING_RULES}), so the bulk bar's Reactivar over drafts
 * still gets 422 {@code ETGO_ReactivateOnlyCompleted}; on any other non-booked status it is
 * refused like the rest. Every other effective value —
 * Void, Close, Prepare ({@code PR}, which used to take a draft to {@code IP} over MCP),
 * {@code AP}, {@code --}, nothing at all — is refused with 400
 * ({@code DocActionRefusalGuard#refuseUnlessAllowed}; Close and Void keep their own messages).
 * Advertised by {@link #unavailableActions}. Purchase orders do not opt in.
 *
 * <p><b>Binding (ETP-6026).</b> Bound by {@link NeoExtension} on every channel. The row's
 * {@code Java_Qualifier} ({@code salesOrderHeaderHandler}) and the class's {@code @Named} were
 * removed in the same change (neo-extension-migration).
 */
@NeoExtension(spec = "sales-order", entity = "header")
public class SalesOrderHeaderHandler extends AbstractOrderHeaderHandler {

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private CreateShipmentHandler createShipmentHandler;

  @Inject
  private CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Inject
  private CurrencyOptionsHandler currencyOptionsHandler;

  @Inject
  private TotalDiscountService totalDiscountService;

  /**
   * ETP-5840: the rate is the one valid on the {@code orderDate} ({@code DateOrdered}), shown as
   * "Order date". Order-family rules in {@link DocumentRateGuards#guardOrderHeader}.
   */
  static final DocumentCurrencyRate.RateType RATE_TYPE = new DocumentCurrencyRate.RateType(
      Order.ENTITY_NAME, Order.PROPERTY_ETGOCURRENCYRATE, Order.PROPERTY_ORDERDATE,
      DocumentRateMessages.LABEL_ORDER_DATE);

  private final DocumentCurrencyRate currencyRate = new DocumentCurrencyRate(RATE_TYPE);

  /** Close, the {@code documentAction} value the Sales Order window never offers (ETP-5704). */
  static final String DOC_ACTION_CLOSE = "CL";

  /** The 400 a Close on a sales order answers with (ETP-5704). */
  static final String MSG_CLOSE_NOT_OFFERED = "ETGO_SalesOrderCloseNotOffered";

  /** Why {@code documentAction:CL} is unavailable on every sales order (ETP-5704). */
  static final String REASON_CLOSE_NOT_OFFERED = "The app does not offer Close on a sales order, "
      + "so documentAction CL is refused on every sales order. To change a booked order, "
      + "reactivate it (RE, only while it has no linked documents), edit it and complete it again.";

  /** Void, which the window never offers on a sales order (ETP-6026). */
  static final String DOC_ACTION_VOID = "VO";

  /** The 400 a Void on a sales order answers with (ETP-6026). */
  static final String MSG_VOID_NOT_OFFERED = "ETGO_SalesOrderVoidNotOffered";

  /** Why {@code documentAction:VO} is unavailable on a sales order (ETP-6026). */
  static final String REASON_VOID_NOT_OFFERED = "The app does not offer Void on a sales order, so "
      + "documentAction VO is refused on every sales order. Delete a draft with etendo_delete "
      + "instead; reactivate a booked order (RE, only while it has no linked documents), edit it "
      + "and complete it again.";

  /** The 400 for any other value the window does not offer in the order's status (ETP-6026). */
  static final String MSG_ACTION_NOT_OFFERED = "ETGO_SalesOrderActionNotOffered";

  /**
   * The {@code DocAction} values the window sends, per stored {@code documentStatus} (ETP-6026).
   * A status that is not a key accepts nothing.
   */
  static final Map<String, Set<String>> ALLOWED_BY_STATUS = Map.of(
      "DR", Set.of("CO"),
      "IP", Set.of("CO"),
      "CO", Set.of("CO", "RE"));

  /**
   * Values the allow-list lets through, per status, to the rule that answered them before
   * (ETP-6026): Reactivate on a draft, which {@code DocActionReactivationRule} — after this
   * pre-hook, and only on {@code DR} — refuses with 422 {@code ETGO_ReactivateOnlyCompleted}, so
   * the bulk bar's Reactivar over a selection holding drafts reports those rows exactly as before.
   * Nowhere else: on {@code VO}/{@code CL} core only answered a technical {@code @AlreadyPosted@},
   * so the allow-list's 400 applies there. Not advertised.
   */
  static final Map<String, Set<String>> LEFT_TO_EXISTING_RULES = Map.of("DR", Set.of("RE"));

  /** The values of the {@code C_Order.DocAction} list an agent could send, for the advice. */
  static final List<String> DOC_ACTION_VALUES =
      List.of("CO", "RE", "VO", "CL", "PR", "AP", "RJ", "PO", "RA", "RC", "XL");

  @Override
  protected DocumentCurrencyRate currencyRate() {
    return currencyRate;
  }

  /**
   * The actions this header serves through its delegates, declared for agents (ETP-5709):
   * {@code createShipment}, {@code createDraftInvoice} and {@code cloneRecord}. Published by the
   * MCP next to the AD buttons; REST and the SPA do not read it.
   */
  @Override
  public Map<String, NeoActionContract> actionContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(CreateShipmentHandler.CONTRACT.getName(), CreateShipmentHandler.CONTRACT);
    contracts.put(CreateDraftInvoiceHandler.ORDER_CONTRACT.getName(),
        CreateDraftInvoiceHandler.ORDER_CONTRACT);
    contracts.put(NeoCloneRecordHandler.CONTRACT.getName(), NeoCloneRecordHandler.CONTRACT);
    return contracts;
  }

  /**
   * {@inheritDoc} The order header adds {@code hasLinkedDocuments}, {@code needsPrimaryDoc} and
   * {@code needsInvoiceDoc} to every GET record (ETP-5714).
   */
  @Override
  public Set<String> responseEnrichedFields() {
    return OrderDocumentAvailability.RESPONSE_ENRICHED_FIELDS;
  }

  /** The order's creation actions, for {@link OrderDocumentAvailability} (ETP-5714). */
  private static final OrderDocumentAvailability.Flow AVAILABILITY_FLOW =
      new OrderDocumentAvailability.Flow(true, CreateShipmentHandler.CONTRACT.getName(),
          CreateDraftInvoiceHandler.ORDER_CONTRACT.getName(), true);

  /**
   * {@inheritDoc} Reactivate is ruled out for an order that has linked documents, and each
   * creation action when nothing is left to do or a draft already covers it (ETP-5714). Close is
   * ruled out on every sales order, whatever its status: the app never offers it and the pre-hook
   * refuses it (ETP-5704). ETP-6026: every {@code documentAction} value the pre-hook does not
   * allow in the record's {@code documentStatus} ({@link #ALLOWED_BY_STATUS}) is ruled out, with
   * Close's and Void's own reasons and a generic one for the rest.
   */
  @Override
  public Map<String, String> unavailableActions(BaseOBObject subject) {
    Map<String, String> unavailable =
        new LinkedHashMap<>(OrderDocumentAvailability.unavailable(subject, AVAILABILITY_FLOW));
    if (subject instanceof Order) {
      Set<String> allowed = allowedIn(((Order) subject).getDocumentStatus());
      for (String value : DOC_ACTION_VALUES) {
        if (!allowed.contains(value)) {
          unavailable.putIfAbsent(NeoActionAvailability.valueKey(
              OrderDocumentAvailability.ACTION_DOCUMENT_ACTION, value), reasonNotOffered(value));
        }
      }
    }
    return unavailable;
  }

  /** The values the window sends in {@code status}; empty for any other status (ETP-6026). */
  static Set<String> allowedIn(String status) {
    return status == null ? Set.of() : ALLOWED_BY_STATUS.getOrDefault(status, Set.of());
  }

  /** Why {@code value} is unavailable: Close's and Void's own reason, else the generic one. */
  static String reasonNotOffered(String value) {
    if (DOC_ACTION_CLOSE.equals(value)) {
      return REASON_CLOSE_NOT_OFFERED;
    }
    if (DOC_ACTION_VOID.equals(value)) {
      return REASON_VOID_NOT_OFFERED;
    }
    return "The app does not offer documentAction " + value + " on a sales order in this "
        + "status: it confirms (CO) a draft or in-progress order and reactivates (RE) a booked "
        + "one, nothing else.";
  }

  /** The refusal key for a value the pre-hook does not allow (ETP-6026). */
  static String refusalKeyOf(String value) {
    if (DOC_ACTION_CLOSE.equals(value)) {
      return MSG_CLOSE_NOT_OFFERED;
    }
    if (DOC_ACTION_VOID.equals(value)) {
      return MSG_VOID_NOT_OFFERED;
    }
    return MSG_ACTION_NOT_OFFERED;
  }

  /**
   * The {@code DocAction} values the pre-hook lets through, from the stored {@code documentStatus}
   * (ETP-6026): the window's allow-list plus {@link #LEFT_TO_EXISTING_RULES}. Read under admin
   * mode, as the DocAction resolution reads the record.
   *
   * @param orderId the order the button runs on
   * @return the accepted values
   * @throws IllegalStateException when the order cannot be loaded — the guard then fails closed
   */
  static Set<String> allowedDocActions(String orderId) {
    OBContext.setAdminMode(true);
    try {
      Order order = OBDal.getInstance().get(Order.class, orderId);
      if (order == null) {
        throw new IllegalStateException("Sales order " + orderId + " cannot be loaded");
      }
      String status = order.getDocumentStatus();
      Set<String> allowed = new java.util.HashSet<>(allowedIn(status));
      if (status != null) {
        allowed.addAll(LEFT_TO_EXISTING_RULES.getOrDefault(status, Set.of()));
      }
      return allowed;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse paymentMethodSelector = PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(context,
        PaymentMethodSelectorSupport.DirectionFallback.WINDOW);
    if (paymentMethodSelector != null) {
      return paymentMethodSelector;
    }
    // ETP-5704: Reactivate is refused when the order has linked shipments or invoices — the rule
    // the SPA already applies by hiding the action, now enforced for every channel.
    NeoResponse reactivationRefusal = OrderReactivationGuard.refuseIfLinkedDocuments(context);
    if (reactivationRefusal != null) {
      return reactivationRefusal;
    }
    // ETP-6026: only the DocAction values the window offers in the order's status run.
    NeoResponse notOffered = DocActionRefusalGuard.refuseUnlessAllowed(context,
        SalesOrderHeaderHandler::allowedDocActions, SalesOrderHeaderHandler::refusalKeyOf);
    if (notOffered != null) {
      return notOffered;
    }
    // ETP-5703: the partner's only ship-to (and its bill-to) on a create without an address.
    PartnerLocationDefaults.fillOrderLocations(context);
    AbstractOrderHeaderHandler.mirrorAccountingDate(context);
    // ETP-5840: exchange rate — recalculation, save and completion validation — before anything
    // with side effects runs.
    NeoResponse rateRefusal = DocumentRateGuards.guardOrderHeader(context, currencyRate);
    if (rateRefusal != null) {
      return rateRefusal;
    }
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, false);
    return NeoHeaderActionRouter.dispatch(
        context,
        cloneRecordHandler,
        createShipmentHandler,
        createDraftInvoiceHandler,
        currencyOptionsHandler);
  }

  @Override
  protected TotalDiscountService getTotalDiscountService() {
    return totalDiscountService;
  }
}
