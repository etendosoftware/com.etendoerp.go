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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_process.ConvertQuotationIntoOrder;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * NeoHandler for the Sales Quotation header entity.
 *
 * <p>ETP-5238: the {@code paymentMethod} SELECTOR is served by
 * {@link PaymentMethodSelectorSupport}, independent of Financial Account linkage.
 *
 * Dispatches custom ACTION requests:
 * <ul>
 *   <li>{@code Convertquotation} → calls {@link ConvertQuotationIntoOrder#convertQuotationIntoSalesOrder}
 *       with {@code recalculatePrices=false} so that unit prices agreed in the quotation are
 *       preserved in the new sales order (default Etendo behaviour would re-fetch prices from
 *       the active price list, overwriting the quoted amounts), then reactivates the order so it
 *       is left in Draft (ETP-5528, see {@link #handleConvertQuotation}).</li>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code currencyOptions} → {@link CurrencyOptionsHandler}</li>
 *   <li>{@code createDraftInvoice} / {@code checkDraftInvoice} / {@code listInvoices} → {@link CreateDraftInvoiceHandler}</li>
 *   <li>{@code rejectQuotation} → {@link RejectQuotationHandler}</li>
 *   <li>{@code createRejectReason} → {@link CreateRejectReasonHandler}</li>
 *   <li>{@code DocAction} with value {@code RJ} → {@link RejectQuotationHandler#reject} (ETP-5535,
 *       see {@link #isRejectDocAction})</li>
 * </ul>
 *
 * <p>ETP-5535: {@code rejectQuotation} and {@code createRejectReason} are declared through
 * {@link #actionContracts()}, so {@code etendo_schema(view:"actions")} lists them next to the AD
 * buttons. Before that they were reachable over MCP but undiscoverable.
 *
 * <p>Total discount is synced on two paths:
 * <ul>
 *   <li>{@code documentAction=CO} via CRUD or ACTION — handled by
 *       {@link AbstractOrderHeaderHandler#applyTotalDiscountBeforeComplete}</li>
 *   <li>{@code DocAction} process button — handled by
 *       {@link AbstractOrderHeaderHandler#syncTotalDiscountOnDocAction},
 *       used by {@code SendToEvaluationModal} when confirming a draft quotation (DR→UE)</li>
 * </ul>
 *
 * <p>Currency / price-list / exchange-rate behaviors are inherited from
 * {@link AbstractOrderHeaderHandler#afterCallout}.
 */
@Named("salesQuotationHeaderHandler")
public class SalesQuotationHeaderHandler extends AbstractOrderHeaderHandler {

  private static final Logger log = LogManager.getLogger(SalesQuotationHeaderHandler.class);
  private static final String FIELD_DOCUMENT_STATUS = "documentStatus";
  private static final String DOC_STATUS_COMPLETED = "CO";
  private static final String DOC_ACTION_REJECT = "RJ";
  /** The button's column name, what the SPA posts. */
  private static final String ACTION_CONVERT_COLUMN = "Convertquotation";
  /** The button's field name, what an MCP {@code etendo_action} call reaches the handler under (ETP-5710). */
  private static final String ACTION_CONVERT_FIELD = "convertquotation";

  /** The named actions published to agents, in presentation order (ETP-5535). */
  private static final Map<String, NeoActionContract> ACTION_CONTRACTS = buildActionContracts();

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private CurrencyOptionsHandler currencyOptionsHandler;

  @Inject
  private RejectQuotationHandler rejectQuotationHandler;

  @Inject
  private CreateRejectReasonHandler createRejectReasonHandler;

  @Inject
  private CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Inject
  private TotalDiscountService totalDiscountService;

  @Inject
  private ConvertQuotationIntoOrder convertQuotationProcess;

  /**
   * ETP-5840: the rate is the one valid on the {@code orderDate} ({@code DateOrdered}), shown as
   * "Quotation date". Order-family rules in {@link DocumentRateGuards#guardOrderHeader}.
   */
  static final DocumentCurrencyRate.RateType RATE_TYPE = new DocumentCurrencyRate.RateType(
      Order.ENTITY_NAME, Order.PROPERTY_ETGOCURRENCYRATE, Order.PROPERTY_ORDERDATE,
      DocumentRateMessages.LABEL_QUOTATION_DATE);

  private final DocumentCurrencyRate currencyRate = new DocumentCurrencyRate(RATE_TYPE);

  @Override
  protected DocumentCurrencyRate currencyRate() {
    return currencyRate;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse paymentMethodSelector = PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(context,
        PaymentMethodSelectorSupport.DirectionFallback.WINDOW);
    if (paymentMethodSelector != null) {
      return paymentMethodSelector;
    }
    // ETP-5703: the partner's only ship-to (and its bill-to) on a create without an address.
    PartnerLocationDefaults.fillOrderLocations(context);
    // ETP-5535: ahead of the total-discount sync, which recalculates on every DocAction — a
    // rejection does not touch the lines, exactly as the UI's rejectQuotation does not.
    if (isRejectDocAction(context)) {
      return rejectQuotationHandler.reject(context);
    }
    // ETP-5840: exchange rate — recalculation, save and confirmation validation — before the
    // total-discount sync and the actions run.
    NeoResponse rateRefusal = DocumentRateGuards.guardOrderHeader(context, currencyRate);
    if (rateRefusal != null) {
      return rateRefusal;
    }
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, false);
    AbstractOrderHeaderHandler.syncTotalDiscountOnDocAction(context, totalDiscountService, false);

    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && isConvertQuotationAction(context.getFieldName())) {
      // ETP-5840: a foreign-currency quotation without a rate is not converted; the client gets the
      // translated completion refusal. The SPA's confirm modal only pre-checks — this is the rule.
      NeoResponse missingRate = currencyRate.refuseCompletionWithoutRate(context.getRecordId());
      if (missingRate != null) {
        return missingRate;
      }
      return handleConvertQuotation(context.getRecordId());
    }

    return NeoHeaderActionRouter.dispatch(
        context,
        currencyOptionsHandler,
        cloneRecordHandler,
        rejectQuotationHandler,
        createRejectReasonHandler,
        createDraftInvoiceHandler);
  }

  /**
   * The quotation's handler-served actions (ETP-5535). Published by {@code etendo_schema} next to the
   * AD buttons; each request is still judged by its own handler, so what the React modals send is
   * accepted exactly as before.
   */
  @Override
  public Map<String, NeoActionContract> actionContracts() {
    return ACTION_CONTRACTS;
  }

  private static Map<String, NeoActionContract> buildActionContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(CreateDraftInvoiceHandler.QUOTATION_CONTRACT.getName(),
        CreateDraftInvoiceHandler.QUOTATION_CONTRACT);
    contracts.put(RejectQuotationHandler.CONTRACT.getName(), RejectQuotationHandler.CONTRACT);
    contracts.put(CreateRejectReasonHandler.CONTRACT.getName(), CreateRejectReasonHandler.CONTRACT);
    return Collections.unmodifiableMap(contracts);
  }

  /**
   * Whether the request runs the {@code DocAction} button with the value {@code RJ} (Reject)
   * (ETP-5535).
   *
   * <p>The button's value list offers "Reject", so an agent reads it as the way to reject a
   * quotation. Through {@code C_Order_Post} that cannot work for any caller: core requires
   * {@code C_Reject_Reason_ID} on the header ({@code @NoRejectReason@}), and {@code rejectReason}
   * is read-only, so nothing can set it first. The request is routed instead to the flow the UI
   * uses, {@link RejectQuotationHandler#reject}, which takes the reason in the same body. Both the
   * MCP and the REST action reach this handler, so both channels get it.</p>
   *
   * <p>Where the value and the button name are read from is {@link DocActionRequestSupport#requests}
   * (extracted from this class in ETP-5704): root {@code docAction} first — the key the process
   * itself runs — then {@code fieldValues.documentAction}, then root {@code documentAction}. Until
   * ETP-5704 {@code fieldValues.documentAction} was read first, so a body naming RJ there and CO in
   * {@code docAction} was rejected instead of completed. The record's stored DocAction never
   * triggers the rejection. A {@code DocAction} request without an explicit {@code RJ} — the SPA's
   * {@code SendToEvaluationModal} sends {@code fieldValues: {}} — is not affected.</p>
   *
   * @param context the request
   * @return {@code true} for a POST ACTION on the DocAction button asking for {@code RJ}
   */
  static boolean isRejectDocAction(NeoContext context) {
    return DocActionRequestSupport.requests(context, DOC_ACTION_REJECT);
  }

  private static boolean isConvertQuotationAction(String action) {
    return ACTION_CONVERT_COLUMN.equals(action) || ACTION_CONVERT_FIELD.equals(action);
  }

  /**
   * Intercepts the {@code Convertquotation} button action and calls
   * {@link ConvertQuotationIntoOrder#convertQuotationIntoSalesOrder} with
   * {@code recalculatePrices=false}.
   *
   * <p>Returning a non-null response short-circuits the default NEO button handler, which
   * would otherwise invoke {@code ConvertQuotationIntoOrder.doExecute} with
   * {@code recalculatePrices=true} (the default when the parameter is absent from the HTTP
   * request), causing all order-line prices to be re-fetched from the active price list and
   * the quoted amounts to be lost.
   *
   * <p><b>ETP-5528 — the order is left in Draft.</b> Core always completes the order it creates
   * ({@code c_order_post1}). Until ETP-5528 the SPA then reactivated it from the browser with a
   * second request ({@code QuotationConfirmModal}, ETP-3570), which is why an order created through
   * the MCP ({@code etendo_action Convertquotation}) stayed Completed while the same action in the UI
   * ended in Draft. The reactivation now runs here, through the same {@code C_Order_Post 'RE'} the
   * UI request reaches ({@link OrderDocActionSupport}), so both channels share one flow. The UI's own
   * call is guarded by {@code documentStatus === 'CO'}, so against an order that is already Draft
   * it simply does not fire — no double reactivation.
   *
   * <p>Best-effort only for failures the procedure REPORTS (an {@code AD_PInstance} result other
   * than 1, or no access to {@code C_Order_Post}): the conversion is kept, the order stays
   * Completed, the failure is logged and {@code documentStatus} in the response says which state the
   * order is in. An exception thrown on the way (the flush, {@code CallProcess} wrapping an
   * {@code SQLException}, a statement timeout) is NOT swallowed: it leaves the PostgreSQL
   * transaction aborted, so the conversion would be rolled back at commit anyway, and answering 200
   * with a {@code salesOrderId} that never gets persisted would lie to the client. It propagates to
   * the catch below and the whole request fails as one unit.
   */
  private NeoResponse handleConvertQuotation(String quotationId) {
    try {
      Order newOrder = convertQuotationProcess.convertQuotationIntoSalesOrder(false, quotationId);
      log.info("[ETP-4027] Created sales order {} from quotation {} with quoted prices preserved",
          newOrder.getDocumentNo(), quotationId);
      reactivateConvertedOrder(newOrder);
      recomputeConvertedOrderRate(newOrder);
      // ETP-5051: the plan-quota check of the order created from the quotation and its lines.
      // Nothing is committed before this point, so a refusal rolls the conversion back whole.
      NeoQuotaCreateCheck.requireAllowedDocument(newOrder);
      JSONObject result = new JSONObject();
      result.put("salesOrderId", newOrder.getId());
      result.put(FIELD_DOCUMENT_STATUS, newOrder.getDocumentStatus());
      return NeoResponse.ok(result);
    } catch (NeoQuotaCreateCheck.Refused e) {
      return e.toResponse();
    } catch (Exception e) {
      log.error("[ETP-4027] handleConvertQuotation failed for quotation {}: {}",
          quotationId, e.getMessage(), e);
      return NeoResponse.error(500, e.getMessage());
    }
  }

  /**
   * Reactivates the order core just completed, leaving it in Draft (ETP-5528).
   *
   * <p>A failure the procedure reports is logged and the order is left as the procedure left it.
   * A thrown exception is deliberately not caught here — see {@link #handleConvertQuotation}.
   *
   * @throws Exception when the reactivation call itself fails; the transaction is then unusable
   */
  private static void reactivateConvertedOrder(Order order) throws Exception {
    if (!DOC_STATUS_COMPLETED.equals(order.getDocumentStatus())) {
      return;
    }
    if (OrderDocActionSupport.runDocAction(order, OrderDocActionSupport.DOC_ACTION_REACTIVATE)) {
      log.info("[ETP-5528] Reactivated sales order {} created from a quotation (status {})",
          order.getDocumentNo(), order.getDocumentStatus());
    } else {
      log.warn("[ETP-5528] C_Order_Post reported a failure reactivating sales order {}; it stays {}",
          order.getDocumentNo(), order.getDocumentStatus());
    }
  }

  @Override
  protected TotalDiscountService getTotalDiscountService() {
    return totalDiscountService;
  }

  /**
   * Recomputes the exchange rate of the order {@code Convertquotation} just created (ETP-5840).
   *
   * <p>Core's {@code DalUtil.copy} carries the quotation's {@code EM_ETGO_Currency_Rate} over, but
   * the order is dated today, so the quotation's rate is not the order's: it is recomputed from the
   * order's own reference date with the sales order's rate rules, never copied. No rate configured →
   * {@code null}, and the order's next save is refused until one is entered. Runs after the
   * reactivation, on the Draft order; an order core left Completed (reactivation reported a failure)
   * is left as it is.
   */
  private static void recomputeConvertedOrderRate(Order order) {
    OBDal.getInstance().getSession().refresh(order);
    new DocumentCurrencyRate(SalesOrderHeaderHandler.RATE_TYPE).recompute(order);
  }
}
