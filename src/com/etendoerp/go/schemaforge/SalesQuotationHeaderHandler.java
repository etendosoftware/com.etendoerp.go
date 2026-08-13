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

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_process.ConvertQuotationIntoOrder;
import org.openbravo.model.common.order.Order;

/**
 * NeoHandler for the Sales Quotation header entity.
 *
 * Dispatches custom ACTION requests:
 * <ul>
 *   <li>{@code Convertquotation} → calls {@link ConvertQuotationIntoOrder#convertQuotationIntoSalesOrder}
 *       with {@code recalculatePrices=false} so that unit prices agreed in the quotation are
 *       preserved in the new sales order (default Etendo behaviour would re-fetch prices from
 *       the active price list, overwriting the quoted amounts).</li>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code currencyOptions} → {@link CurrencyOptionsHandler}</li>
 *   <li>{@code createDraftInvoice} / {@code checkDraftInvoice} / {@code listInvoices} → {@link CreateDraftInvoiceHandler}</li>
 *   <li>{@code rejectQuotation} → {@link RejectQuotationHandler}</li>
 *   <li>{@code createRejectReason} → {@link CreateRejectReasonHandler}</li>
 * </ul>
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
   * Intercepts the {@code Convertquotation} button action and calls
   * {@link ConvertQuotationIntoOrder#convertQuotationIntoSalesOrder} with
   * {@code recalculatePrices=false}.
   *
   * <p>Returning a non-null response short-circuits the default NEO button handler, which
   * would otherwise invoke {@code ConvertQuotationIntoOrder.doExecute} with
   * {@code recalculatePrices=true} (the default when the parameter is absent from the HTTP
   * request), causing all order-line prices to be re-fetched from the active price list and
   * the quoted amounts to be lost.
   */
  private NeoResponse handleConvertQuotation(String quotationId) {
    try {
      Order newOrder = convertQuotationProcess.convertQuotationIntoSalesOrder(false, quotationId);
      log.info("[ETP-4027] Created sales order {} from quotation {} with quoted prices preserved",
          newOrder.getDocumentNo(), quotationId);
      JSONObject result = new JSONObject();
      result.put("salesOrderId", newOrder.getId());
      return NeoResponse.ok(result);
    } catch (Exception e) {
      log.error("[ETP-4027] handleConvertQuotation failed for quotation {}: {}",
          quotationId, e.getMessage(), e);
      return NeoResponse.error(500, e.getMessage());
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
