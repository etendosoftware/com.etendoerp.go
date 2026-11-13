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

import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.common.businesspartner.Location;
import java.util.Set;

/**
 * NeoHandler for the Purchase Order header entity.
 *
 * <p>ETP-5238: the {@code paymentMethod} SELECTOR is served by
 * {@link PaymentMethodSelectorSupport}, independent of Financial Account linkage.
 *
 * Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code createGoodsReceipt} → {@link CreateGoodsReceiptHandler}</li>
 *   <li>{@code createPurchaseInvoice} → {@link CreatePurchaseInvoiceHandler}</li>
 * </ul>
 *
 * <p>Before the Complete action (documentAction=CO), creates the total discount line so it is
 * included in the completed document. Delegates to {@link TotalDiscountService} via the shared
 * helper in {@link AbstractOrderHeaderHandler}.
 *
 * <p>GET post-hook (hasLinkedDocuments, plus the ETP-5295 needsPrimaryDoc/needsInvoiceDoc
 * annotations) is inherited from {@link AbstractOrderHeaderHandler}. {@code isSalesTransaction()}
 * returns {@code false} here, which is what makes those annotations look at goods RECEIPTS and
 * PURCHASE invoices ({@code IsSOTrx = 'N'}).
 *
 * <p>ETP-6108: the {@code DocAction} button is an ALLOW-list of what the Purchase Order window
 * offers, decided on the stored {@code documentStatus} ({@link OrderDocActionPolicy#ALLOWED_BY_STATUS}) — the same
 * list as the sales order, checked against the window: Confirm ({@code CO}) on a draft
 * ({@code DR}) or an in-progress ({@code IP}) order, Reactivate ({@code RE}) on a booked one
 * ({@code CO}; the window hides it once a receipt or invoice is linked, which
 * {@link OrderDocumentAvailability} already advertises and {@link OrderReactivationGuard}
 * enforces, with the purchase order's own message). The {@code IP} row could not be
 * reproduced on screen and is treated like the sales order's. {@code CO} on a booked order is let
 * through without being advertised (the list's bulk bar sends it to every selected row;
 * {@code DocActionIdempotency} answers it "already applied"), and {@code RE} on a draft is left to
 * {@code DocActionReactivationRule}'s 422, as on sales. Every other effective value — Close
 * ({@code CL}, which zeroes ordered quantities and totals), Void, Prepare ({@code PR}),
 * {@code AP}, {@code --}, nothing at all — is refused with 400 through
 * {@link DocActionRefusalGuard}, with Close's and Void's own messages, and advertised by
 * {@link #unavailableActions}. The mechanics are the shared {@link OrderDocActionPolicy}.
 *
 * <p><b>Binding (ETP-6108).</b> Bound by {@link NeoExtension} on every channel. The row's
 * {@code Java_Qualifier} ({@code purchaseOrderHeaderHandler}) and the class's {@code @Named} were
 * removed in the same change (neo-extension-migration).
 */
@NeoExtension(spec = "purchase-order", entity = "header")
public class PurchaseOrderHeaderHandler extends AbstractOrderHeaderHandler {

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private CreateGoodsReceiptHandler createGoodsReceiptHandler;

  @Inject
  private CreatePurchaseInvoiceHandler createPurchaseInvoiceHandler;

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

  @Override
  protected DocumentCurrencyRate currencyRate() {
    return currencyRate;
  }

  @Override
  protected boolean isSalesTransaction() {
    return false;
  }

  /**
   * The actions this header serves through its delegates, declared for agents (ETP-5709):
   * {@code createGoodsReceipt}, {@code createPurchaseInvoice} and {@code cloneRecord}. Published
   * by the MCP next to the AD buttons; REST and the SPA do not read it.
   */
  @Override
  public Map<String, NeoActionContract> actionContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(CreateGoodsReceiptHandler.CONTRACT.getName(), CreateGoodsReceiptHandler.CONTRACT);
    contracts.put(CreatePurchaseInvoiceHandler.ORDER_CONTRACT.getName(),
        CreatePurchaseInvoiceHandler.ORDER_CONTRACT);
    // ETP-6179: the SIF fields the generated invoice would get.
    contracts.put(CreatePurchaseInvoiceHandler.ORDER_SIF_DEFAULTS_CONTRACT.getName(),
        CreatePurchaseInvoiceHandler.ORDER_SIF_DEFAULTS_CONTRACT);
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
      new OrderDocumentAvailability.Flow(false, CreateGoodsReceiptHandler.CONTRACT.getName(),
          CreatePurchaseInvoiceHandler.ORDER_CONTRACT.getName(), false, false);

  /** The 400 a Close on a purchase order answers with (ETP-6108). */
  static final String MSG_CLOSE_NOT_OFFERED = "ETGO_PurchaseOrderCloseNotOffered";

  /** Why {@code documentAction:CL} is unavailable on every purchase order (ETP-6108). */
  static final String REASON_CLOSE_NOT_OFFERED = "The app does not offer Close on a purchase "
      + "order, so documentAction CL is refused on every purchase order. To change a completed "
      + "order, reactivate it (RE, only while it has no linked documents), edit it and complete "
      + "it again.";

  /** The 400 a Reactivate on a purchase order with linked receipts or invoices answers with. */
  static final String MSG_REACTIVATE_LINKED_DOCUMENTS = "ETGO_PurchaseOrderReactivateLinkedDocs";

  /** The 400 a Void on a purchase order answers with (ETP-6108). */
  static final String MSG_VOID_NOT_OFFERED = "ETGO_PurchaseOrderVoidNotOffered";

  /** Why {@code documentAction:VO} is unavailable on a purchase order (ETP-6108). */
  static final String REASON_VOID_NOT_OFFERED = "The app does not offer Void on a purchase "
      + "order, so documentAction VO is refused on every purchase order. Delete a draft with "
      + "etendo_delete instead; reactivate a completed order (RE, only while it has no linked "
      + "documents), edit it and complete it again.";

  /** The 400 for any other value the window does not offer in the order's status (ETP-6108). */
  static final String MSG_ACTION_NOT_OFFERED = "ETGO_PurchaseOrderActionNotOffered";

  /**
   * The allow-list mechanics, shared with the sales order ({@link OrderDocActionPolicy}, whose
   * {@code ALLOWED_BY_STATUS} is what the window offers per stored {@code documentStatus}), fed
   * with this window's wording.
   */
  private static final OrderDocActionPolicy DOC_ACTION_POLICY = new OrderDocActionPolicy(
      "purchase order", "completed",
      new OrderDocActionPolicy.Messages(MSG_CLOSE_NOT_OFFERED, REASON_CLOSE_NOT_OFFERED,
          MSG_VOID_NOT_OFFERED, REASON_VOID_NOT_OFFERED, MSG_ACTION_NOT_OFFERED));

  /**
   * {@inheritDoc} Reactivate is ruled out for an order that has linked documents, and each
   * creation action when nothing is left to do or a draft already covers it (ETP-5714). ETP-6108:
   * every {@code documentAction} value the pre-hook does not allow in the record's
   * {@code documentStatus} ({@link OrderDocActionPolicy#ALLOWED_BY_STATUS}) is ruled out too, with Close's and Void's
   * own reasons and a generic one for the rest.
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
    // ETP-6108: Reactivate is refused when the order has linked receipts or invoices — the rule
    // the SPA applies by hiding the action, enforced for every channel as on the sales order.
    NeoResponse reactivationRefusal = OrderReactivationGuard.refuseIfLinkedDocuments(context,
        MSG_REACTIVATE_LINKED_DOCUMENTS);
    if (reactivationRefusal != null) {
      return reactivationRefusal;
    }
    // ETP-6108: only the DocAction values the window offers in the order's status run.
    NeoResponse notOffered = DOC_ACTION_POLICY.refuseUnlessAllowed(context);
    if (notOffered != null) {
      return notOffered;
    }
    // ETP-5881 / ETP-5852: a create that names a vendor but no address (REST, the public API and
    // MCP; the SPA always sends one) gets the vendor's only address and its bill-to, as on sales
    // orders (ETP-5703). MCP also runs it before its mandatory check (resolveServerCreateFields).
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
        createGoodsReceiptHandler,
        createPurchaseInvoiceHandler,
        currencyOptionsHandler);
  }

  @Override
  protected TotalDiscountService getTotalDiscountService() {
    return totalDiscountService;
  }
}
