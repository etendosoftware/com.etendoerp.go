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
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.order.Order;
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
 */
@Named("purchaseOrderHeaderHandler")
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

  /** {@inheritDoc} Reactivate is ruled out for an order that has linked documents (ETP-5714). */
  @Override
  public Map<String, String> unavailableActions(BaseOBObject subject) {
    return OrderDocumentAvailability.unavailable(subject);
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse paymentMethodSelector = PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(context,
        PaymentMethodSelectorSupport.DirectionFallback.WINDOW);
    if (paymentMethodSelector != null) {
      return paymentMethodSelector;
    }
    // A create that names a vendor but no address (REST and the public API; the SPA always sends
    // one) gets the vendor's address, as on sales orders (ETP-5703) and as SE_Order_BPartner does
    // for both sides.
    PartnerLocationDefaults.fillShipToAndBillTo(context, Order.PROPERTY_PARTNERADDRESS,
        Location.PROPERTY_SHIPTOADDRESS, Order.PROPERTY_INVOICEADDRESS,
        Location.PROPERTY_INVOICETOADDRESS);
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
