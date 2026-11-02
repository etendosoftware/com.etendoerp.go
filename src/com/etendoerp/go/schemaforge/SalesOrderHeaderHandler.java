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
import javax.inject.Named;

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
 */
@Named("salesOrderHeaderHandler")
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
   * refuses it (ETP-5704).
   */
  @Override
  public Map<String, String> unavailableActions(BaseOBObject subject) {
    Map<String, String> unavailable =
        new LinkedHashMap<>(OrderDocumentAvailability.unavailable(subject, AVAILABILITY_FLOW));
    if (subject instanceof Order) {
      unavailable.put(NeoActionAvailability.valueKey(
          OrderDocumentAvailability.ACTION_DOCUMENT_ACTION, DOC_ACTION_CLOSE),
          REASON_CLOSE_NOT_OFFERED);
    }
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
    NeoResponse closeRefusal = DocActionRefusalGuard.refuse(context, DOC_ACTION_CLOSE,
        DocActionRefusalGuard.ALWAYS, MSG_CLOSE_NOT_OFFERED);
    if (closeRefusal != null) {
      return closeRefusal;
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
