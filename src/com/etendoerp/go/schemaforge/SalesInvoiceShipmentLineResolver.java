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

import java.math.BigDecimal;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.OrderLine;

/**
 * Binds a sales invoice line created from an ORDER line to the shipment line it invoices
 * (ETP-6002, IMP-1): writes {@code goodsShipmentLine} ({@code M_InOutLine_ID}) into the create body
 * before the line is saved.
 *
 * <p><b>Why.</b> An invoice and a shipment are related only by the invoice-line ↔ shipment-line
 * link (the column or {@code M_MatchSI}) since ETP-6002 dropped the order-line arm on the sales
 * side ({@link InOutInvoiceLinks}). A line imported with "Add from order" (one CRUD POST per order
 * line) carried the order line only, so it would no longer reach its shipment at all.
 *
 * <p><b>The rule</b> is the one shared rule, {@link OrderLineMovementAllocation} — the same the
 * {@code importOrderLines} action and the sales "create invoice from order" path use: the order
 * line's completed shipment lines, oldest first, each with what it still has to invoice (drafts
 * included). One CRUD create makes ONE line, so it takes the FIRST allocation: when the quantity is
 * above that shipment line's pending, the line is linked to it alone and not split (logged). No
 * pending shipment line → nothing is written: an order line not yet shipped is invoiceable as such.
 *
 * <p><b>When.</b> A CRUD create of a sales invoice line carrying {@code salesOrderLine}, no
 * {@code goodsShipmentLine} of its own (the shipment import sends it and is respected), no
 * {@code sourceInvoiceLineId} (a line copied from another invoice is not a delivery), on an
 * invoice that is not a rectificative, with a positive quantity when one is given. It runs in the
 * pre-hook of {@link SalesInvoiceLineHandler}, so REST single and {@code /batch} behave the same;
 * on MCP the CRUD create refuses {@code salesOrderLine} (read-only) and the declared
 * {@code importOrderLines} action binds shipment lines itself, with the same rule.
 */
final class SalesInvoiceShipmentLineResolver {

  private static final Logger log = LogManager.getLogger(SalesInvoiceShipmentLineResolver.class);

  static final String FIELD_SALES_ORDER_LINE =
      SalesRectificativeLineLinkSupport.FIELD_SALES_ORDER_LINE;
  static final String FIELD_GOODS_SHIPMENT_LINE =
      SalesRectificativeLineLinkSupport.FIELD_GOODS_SHIPMENT_LINE;
  private static final String FIELD_INVOICED_QTY = "invoicedQuantity";

  private SalesInvoiceShipmentLineResolver() {
  }

  /**
   * Writes {@code goodsShipmentLine} into a qualifying create body (see the class javadoc); leaves
   * every other request untouched.
   *
   * @param context             the pre-hook context of the line create
   * @param sourceInvoiceLineId the {@code sourceInvoiceLineId} the request carried (read before
   *                            the parent handler removed it), or {@code null}
   * @throws OBException when the body cannot be written. A failure READING the pending quantities
   *     ({@link NeoInvoiceSupport#computePendingQtyPerLineOrThrow}) is not caught either, on purpose:
   *     the line create fails loudly (a 500) instead of being saved unlinked as if nothing were
   *     pending — a silent NULL would hide the shipment from the invoice for good.
   */
  static void bindPendingShipmentLineOnCreate(NeoContext context, String sourceInvoiceLineId) {
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !"POST".equals(context.getHttpMethod()) || context.getRequestBody() == null
        || StringUtils.isNotBlank(sourceInvoiceLineId)) {
      return;
    }
    JSONObject body = context.getRequestBody();
    String orderLineId = idOrNull(body, FIELD_SALES_ORDER_LINE);
    BigDecimal qty = quantityOf(body);
    if (orderLineId == null || idOrNull(body, FIELD_GOODS_SHIPMENT_LINE) != null
        || (qty != null && qty.signum() <= 0)) {
      return;
    }
    String invoiceId = idOrNull(body, InvoiceLineAmountSupport.FIELD_PARENT_ID);
    if (invoiceId == null) {
      invoiceId = idOrNull(body, InvoiceLineAmountSupport.FIELD_INVOICE);
    }
    if (invoiceId == null) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
      if (invoice == null
          || SalesInvoiceHeaderHandler.isRectificativeDocType(invoice.getTransactionDocument())) {
        return;
      }
      OrderLine orderLine = OBDal.getInstance().get(OrderLine.class, orderLineId);
      // A create without a quantity takes one unit, the line's default.
      BigDecimal requested = qty != null ? qty : BigDecimal.ONE;
      List<OrderLineMovementAllocation.Allocation> allocations =
          OrderLineMovementAllocation.allocateOrderLine(orderLine, requested);
      if (allocations.isEmpty()) {
        return;
      }
      OrderLineMovementAllocation.Allocation first = allocations.get(0);
      body.put(FIELD_GOODS_SHIPMENT_LINE, first.line.getId());
      if (allocations.size() > 1 || first.quantity.abs().compareTo(requested.abs()) < 0) {
        log.info("Invoice line of order line {} ({}) exceeds the {} still pending on shipment line {};"
            + " linked to it without splitting", orderLineId, requested, first.quantity,
            first.line.getId());
      }
    } catch (JSONException e) {
      throw new OBException("Could not bind the shipment line of order line " + orderLineId, e);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static BigDecimal quantityOf(JSONObject body) {
    if (!body.has(FIELD_INVOICED_QTY) || body.isNull(FIELD_INVOICED_QTY)) {
      return null;
    }
    try {
      return new BigDecimal(body.optString(FIELD_INVOICED_QTY).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String idOrNull(JSONObject body, String key) {
    String value = body.optString(key, null);
    return StringUtils.isBlank(value) || "null".equals(value) ? null : value;
  }
}
