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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
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
 * {@link #actionContracts()}, so {@code neo_schema(view:"actions")} lists them next to the AD
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
  private static final String ACTION_DOC_ACTION = "DocAction";
  private static final String FIELD_DOCUMENT_ACTION = "documentAction";
  private static final String PARAM_DOC_ACTION = "docAction";
  private static final String DOC_ACTION_REJECT = "RJ";

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

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse paymentMethodSelector = PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(context,
        PaymentMethodSelectorSupport.DirectionFallback.WINDOW);
    if (paymentMethodSelector != null) {
      return paymentMethodSelector;
    }
    // ETP-5535: ahead of the total-discount sync, which recalculates on every DocAction — a
    // rejection does not touch the lines, exactly as the UI's rejectQuotation does not.
    if (isRejectDocAction(context)) {
      return rejectQuotationHandler.reject(context);
    }
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, false);
    AbstractOrderHeaderHandler.syncTotalDiscountOnDocAction(context, totalDiscountService, false);

    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && "Convertquotation".equals(context.getFieldName())) {
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
   * The quotation's handler-served actions (ETP-5535). Published by {@code neo_schema} next to the
   * AD buttons; each request is still judged by its own handler, so what the React modals send is
   * accepted exactly as before.
   */
  @Override
  public Map<String, NeoActionContract> actionContracts() {
    return ACTION_CONTRACTS;
  }

  private static Map<String, NeoActionContract> buildActionContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
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
   * <p>The value is read where the action requests carry it: {@code docAction} at the root (the
   * {@code actionParameter} {@code neo_schema} advertises for the button), {@code documentAction}
   * at the root, or {@code fieldValues.documentAction}. The button is named by its column
   * ({@code DocAction}) or its field ({@code documentAction}), as {@code findButtonColumn} accepts
   * both. A {@code DocAction} request without an explicit {@code RJ} — the SPA's
   * {@code SendToEvaluationModal} sends {@code fieldValues: {}} — is not affected.</p>
   *
   * @param context the request
   * @return {@code true} for a POST ACTION on the DocAction button asking for {@code RJ}
   */
  static boolean isRejectDocAction(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !"POST".equals(context.getHttpMethod())) {
      return false;
    }
    String action = context.getFieldName();
    if (!ACTION_DOC_ACTION.equals(action) && !FIELD_DOCUMENT_ACTION.equals(action)) {
      return false;
    }
    return DOC_ACTION_REJECT.equals(requestedDocAction(context.getRequestBody()));
  }

  private static String requestedDocAction(JSONObject body) {
    if (body == null) {
      return null;
    }
    JSONObject fieldValues = body.optJSONObject("fieldValues");
    String nested = fieldValues != null
        ? StringUtils.trimToNull(fieldValues.optString(FIELD_DOCUMENT_ACTION, null)) : null;
    if (nested != null) {
      return nested;
    }
    String root = StringUtils.trimToNull(body.optString(PARAM_DOC_ACTION, null));
    return root != null ? root
        : StringUtils.trimToNull(body.optString(FIELD_DOCUMENT_ACTION, null));
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
   * the MCP ({@code neo_action Convertquotation}) stayed Completed while the same action in the UI
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
      JSONObject result = new JSONObject();
      result.put("salesOrderId", newOrder.getId());
      result.put(FIELD_DOCUMENT_STATUS, newOrder.getDocumentStatus());
      return NeoResponse.ok(result);
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

  /**
   * After {@code Convertquotation} creates a sales order from the quotation,
   * copies {@code EM_ETGO_Currency_Rate} from the quotation header to the new order header
   * so that the agreed exchange rate is preserved across documents.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && "Convertquotation".equals(context.getFieldName())) {
      transferCurrencyRateToNewOrder(context.getRecordId());
    }
    return super.afterHandle(context);
  }

  @Override
  protected TotalDiscountService getTotalDiscountService() {
    return totalDiscountService;
  }

  private void transferCurrencyRateToNewOrder(String quotationId) {
    if (quotationId == null || quotationId.isEmpty()) {
      return;
    }
    try {
      Connection conn = OBDal.getInstance().getConnection();
      String rate = null;
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT em_etgo_currency_rate FROM c_order WHERE c_order_id = ?")) {
        ps.setString(1, quotationId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            rate = rs.getString(1);
          }
        }
      }
      if (rate == null) {
        return;
      }
      final java.math.BigDecimal rateVal = new java.math.BigDecimal(rate);
      // Update the most recently created order from this quotation that does not already have a rate.
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE c_order SET em_etgo_currency_rate = ? "
        + "WHERE c_order_id = ("
        + "  SELECT c_order_id FROM c_order"
        + "  WHERE quotation_id = ? AND issotrx = 'Y' AND em_etgo_currency_rate IS NULL"
        + "  ORDER BY created DESC LIMIT 1"
        + ")")) {
        ps.setBigDecimal(1, rateVal);
        ps.setString(2, quotationId);
        int rows = ps.executeUpdate();
        if (rows > 0) {
          log.info("[ETP-4027] Copied currency rate {} from quotation {} to new order",
              rateVal, quotationId);
        }
      }
    } catch (Exception e) {
      log.warn("[ETP-4027] transferCurrencyRateToNewOrder failed for quotation {}: {}",
          quotationId, e.getMessage());
    }
  }
}
