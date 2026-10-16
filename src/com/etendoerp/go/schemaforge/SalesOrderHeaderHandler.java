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

import javax.inject.Inject;
import javax.inject.Named;

import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport;

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
    // ETP-5703: a create that names a business partner but no address (an agent; the SPA always
    // sends one) gets the partner's only ship-to location — the AD rule of
    // C_Order.C_BPartner_Location_ID is "C_BPartner Location - Ship To" — and, only then, a bill-to
    // for BillTo_ID ("C_BPartner Location - Bill To") that is never left empty (see
    // PartnerLocationDefaults#alignBillTo). REST single and batch; etendo_create keeps its 422
    // (declared divergence, neo-headless.md §4.12.9).
    PartnerLocationDefaults.fillShipToAndBillTo(context, Order.PROPERTY_PARTNERADDRESS,
        Location.PROPERTY_SHIPTOADDRESS, Order.PROPERTY_INVOICEADDRESS,
        Location.PROPERTY_INVOICETOADDRESS);
    AbstractOrderHeaderHandler.mirrorAccountingDate(context);
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
