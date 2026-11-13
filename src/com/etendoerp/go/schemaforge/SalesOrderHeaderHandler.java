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
import java.util.Map;

import javax.inject.Inject;

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport;
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
 * ETP-6108: the purchase order opts in too.
 *
 * <p>ETP-6026: the {@code DocAction} button is an ALLOW-list of what the window offers, decided on
 * the stored {@code documentStatus} ({@link OrderDocActionPolicy#ALLOWED_BY_STATUS}): Confirm ({@code CO}) on a draft
 * ({@code DR}) or an in-progress ({@code IP}) order — the form shows Confirmar on both, since an
 * {@code IP} order is not processed — and Reactivate ({@code RE}) on a booked one ({@code CO}).
 * {@code CO} on a booked order is also let through: the list's bulk bar sends it to every
 * selected row in a mixed draft + booked selection, and {@code DocActionIdempotency} answers that
 * row "already applied" without running anything. {@code RE} on a draft is left to the rule that
 * answered it before ({@link OrderDocActionPolicy#LEFT_TO_EXISTING_RULES}), so the bulk bar's Reactivar over drafts
 * still gets 422 {@code ETGO_ReactivateOnlyCompleted}; on any other non-booked status it is
 * refused like the rest. Every other effective value —
 * Void, Close, Prepare ({@code PR}, which used to take a draft to {@code IP} over MCP),
 * {@code AP}, {@code --}, nothing at all — is refused with 400
 * ({@code DocActionRefusalGuard#refuseUnlessAllowed}; Close and Void keep their own messages).
 * Advertised by {@link #unavailableActions}. ETP-6108: the purchase order applies the same rule
 * through the shared {@link OrderDocActionPolicy}.
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

  /** The invoice's order import, whose quantity rule "Generar factura" uses (ETP-5828). */
  @Inject
  private ImportOrderLinesIntoInvoiceHandler orderLineImport;

  /**
   * ETP-5840: the rate is the one valid on the {@code orderDate} ({@code DateOrdered}), shown as
   * "Order date". Order-family rules in {@link DocumentRateGuards#guardOrderHeader}.
   */
  static final DocumentCurrencyRate.RateType RATE_TYPE = new DocumentCurrencyRate.RateType(
      Order.ENTITY_NAME, Order.PROPERTY_ETGOCURRENCYRATE, Order.PROPERTY_ORDERDATE,
      DocumentRateMessages.LABEL_ORDER_DATE);

  private final DocumentCurrencyRate currencyRate = new DocumentCurrencyRate(RATE_TYPE);

  /** The 400 a Close on a sales order answers with (ETP-5704). */
  static final String MSG_CLOSE_NOT_OFFERED = "ETGO_SalesOrderCloseNotOffered";

  /** Why {@code documentAction:CL} is unavailable on every sales order (ETP-5704). */
  static final String REASON_CLOSE_NOT_OFFERED = "The app does not offer Close on a sales order, "
      + "so documentAction CL is refused on every sales order. To change a booked order, "
      + "reactivate it (RE, only while it has no linked documents), edit it and complete it again.";

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
   * The allow-list mechanics (ETP-6108: shared with the purchase order through
   * {@link OrderDocActionPolicy}, whose {@code ALLOWED_BY_STATUS} is what the window offers per
   * stored {@code documentStatus}), fed with this window's wording.
   */
  private static final OrderDocActionPolicy DOC_ACTION_POLICY = new OrderDocActionPolicy(
      "sales order", "booked",
      new OrderDocActionPolicy.Messages(MSG_CLOSE_NOT_OFFERED, REASON_CLOSE_NOT_OFFERED,
          MSG_VOID_NOT_OFFERED, REASON_VOID_NOT_OFFERED, MSG_ACTION_NOT_OFFERED));

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
    // ETP-6179: the SIF fields the generated invoice would get.
    contracts.put(CreateDraftInvoiceHandler.ORDER_SIF_DEFAULTS_CONTRACT.getName(),
        CreateDraftInvoiceHandler.ORDER_SIF_DEFAULTS_CONTRACT);
    contracts.put(NeoCloneRecordHandler.CONTRACT.getName(), NeoCloneRecordHandler.CONTRACT);
    return contracts;
  }

  /**
   * {@inheritDoc} The order header adds {@code hasLinkedDocuments}, {@code needsPrimaryDoc} and
   * {@code needsInvoiceDoc} to every GET record (ETP-5714).
   */
  @Override
  public Set<String> responseEnrichedFields() {
    return RESPONSE_ENRICHED_FIELDS;
  }

  /** The order header's enriched keys plus {@code pendingInvoiceAmount} (ETP-5828). */
  static final Set<String> RESPONSE_ENRICHED_FIELDS = union(
      OrderDocumentAvailability.RESPONSE_ENRICHED_FIELDS, FIELD_PENDING_INVOICE_AMOUNT);

  private static Set<String> union(Set<String> base, String extra) {
    Set<String> all = new java.util.LinkedHashSet<>(base);
    all.add(extra);
    return java.util.Collections.unmodifiableSet(all);
  }

  /**
   * The order's creation actions, for {@link OrderDocumentAvailability} (ETP-5714). ETP-5828: a
   * sales order invoices what is pending net of its draft invoices, so a draft only hides
   * "Generar factura" when it covers everything left.
   */
  private static final OrderDocumentAvailability.Flow AVAILABILITY_FLOW =
      new OrderDocumentAvailability.Flow(true, CreateShipmentHandler.CONTRACT.getName(),
          CreateDraftInvoiceHandler.ORDER_CONTRACT.getName(), true, true);

  /** {@inheritDoc} ETP-5828: the same net-of-drafts rule as {@link #AVAILABILITY_FLOW}. */
  @Override
  protected boolean invoicePendingNetOfDrafts() {
    return AVAILABILITY_FLOW.invoiceNetOfDrafts();
  }

  /**
   * {@inheritDoc} ETP-5828: what {@code createDraftInvoice} would invoice now without explicit
   * {@code lines} — the quantities of {@code ImportOrderLinesIntoInvoiceHandler#shipmentScopedPending}
   * (the action's own rule), priced by {@link OrderInvoicePendingAmount}. Zero when nothing is
   * pending net of the drafts.
   */
  @Override
  protected java.math.BigDecimal pendingInvoiceAmount(String orderId) {
    OBContext.setAdminMode(true);
    try {
      Order order = OBDal.getInstance().get(Order.class, orderId);
      if (order == null) {
        return null;
      }
      ImportOrderLinesIntoInvoiceHandler delegate = orderLineImport != null ? orderLineImport
          : new ImportOrderLinesIntoInvoiceHandler();
      return OrderInvoicePendingAmount.forOrder(order, delegate.shipmentScopedPending(order));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * {@inheritDoc} Reactivate is ruled out for an order that has linked documents, and each
   * creation action when nothing is left to do or a draft already covers it (ETP-5714). Close is
   * ruled out on every sales order, whatever its status: the app never offers it and the pre-hook
   * refuses it (ETP-5704). ETP-6026: every {@code documentAction} value the pre-hook does not
   * allow in the record's {@code documentStatus} ({@link OrderDocActionPolicy#ALLOWED_BY_STATUS}) is ruled out, with
   * Close's and Void's own reasons and a generic one for the rest.
   */
  @Override
  public Map<String, String> unavailableActions(BaseOBObject subject) {
    Map<String, String> unavailable =
        new LinkedHashMap<>(OrderDocumentAvailability.unavailable(subject, AVAILABILITY_FLOW));
    DOC_ACTION_POLICY.addUnavailable(unavailable, subject);
    return unavailable;
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
    NeoResponse notOffered = DOC_ACTION_POLICY.refuseUnlessAllowed(context);
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
