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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.OrderLine;

/**
 * NeoExtension of the Goods Shipment line entity.
 *
 * <p>Order/invoice qty + product-code enrichment, the storageBin default-locator pre-hook
 * (ETP-4863) and the stock-derived {@code movementQuantity} strip (ETP-5062) are identical to
 * {@link GoodsReceiptLineHandler}'s and live in the shared {@link AbstractInOutLineHandler}. What
 * is the shipment line's own (ETP-6002 CP-22):
 * <ul>
 *   <li>{@link #createDirectives()} declares {@code invoiceLineId}, so an MCP create carrying it is
 *       not reported in {@code unknownFields} while the link it asks for is written.</li>
 *   <li>{@link #linkInvoiceLine} links the source invoice line the way the follow-up document
 *       service does ({@link InvoiceLineLinker#linkInvoiceLineToInOutLine}): the first shipment of
 *       the invoice line in its column, a later partial one in {@code M_MatchSI}. The goods
 *       shipment's "Import from invoice" listing counts both links
 *       ({@link ImportInvoiceLinesIntoInOutHandler.SourcePolicy#byDirectLinks}); with only the
 *       column, a second partial shipment made with {@code invoiceLineId} and no order line was
 *       never seen and the rest of the invoice line was offered again.</li>
 *   <li>{@link #handle} drops a {@code salesOrderLine} that is a QUOTATION line
 *       ({@link SalesQuotations#isQuotationLine}) from a create or update (ETP-6002 CP-14). The
 *       window's "Import from invoice" popup copies the invoice line's order line, and an invoice
 *       made straight from a quotation carries quotation lines: completing the shipment then
 *       marked the quotation delivered and listed it on the shipment, though the shipment relates
 *       to the invoice only. A line of a real sales order is kept (order and shipment are
 *       related).</li>
 *   <li>{@link #deriveOrderLineFromInvoiceLine} sets {@code salesOrderLine} from the
 *       {@code invoiceLineId} of a create that sends none (QA BUG-1): the MCP is never offered
 *       that system field, so its lines got no order line while the window's did.</li>
 * </ul>
 *
 * <p><b>Binding (ETP-6002).</b> {@link NeoExtension} on every channel; the row's
 * {@code Java_Qualifier} ({@code goodsShipmentLineHandler}) and the class's {@code @Named} were
 * removed in the same change (neo-extension-migration). The goods receipt line keeps its own
 * binding and the base class's column-only link.
 */
@NeoExtension(spec = "goods-shipment", entity = "goodsShipmentLine")
public class GoodsShipmentLineHandler extends AbstractInOutLineHandler {

  private static final Logger log = LogManager.getLogger(GoodsShipmentLineHandler.class);

  /** The create-body key naming the sales invoice line a shipment line ships. */
  static final String FIELD_INVOICE_LINE_ID = "invoiceLineId";

  /** The body key of the shipment line's order line ({@code C_OrderLine_ID}). */
  static final String FIELD_SALES_ORDER_LINE = "salesOrderLine";

  @Override
  public NeoResponse handle(NeoContext context) {
    dropQuotationOrderLine(context);
    deriveOrderLineFromInvoiceLine(context);
    return super.handle(context);
  }

  /**
   * ETP-6002 (QA BUG-1, MCP parity): on a create carrying {@code invoiceLineId} and no
   * {@code salesOrderLine}, takes the order line from that invoice line, as the window's
   * «Añadir desde Factura» popup sends it. Over MCP {@code salesOrderLine} is a system field the
   * agent is never offered, so without this a shipment line made from an invoice of a regular
   * sales order carried no order line: the order's delivered quantity stayed at 0, the shipment
   * listed no order and {@code availableOrderLines} offered the units again. A QUOTATION line is
   * never taken ({@link SalesQuotations#isQuotationLine}, CP-14); an invoice line that is not the
   * caller's own is ignored (the link code ignores it too); a {@code salesOrderLine} the caller
   * sent is kept. Same on REST single, {@code /batch} and MCP, which all run this pre-hook.
   */
  static void deriveOrderLineFromInvoiceLine(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !"POST".equalsIgnoreCase(context.getHttpMethod())) {
      return;
    }
    JSONObject body = context.getRequestBody();
    if (body == null || hasValue(body, FIELD_SALES_ORDER_LINE)
        || !hasValue(body, FIELD_INVOICE_LINE_ID)) {
      return;
    }
    InvoiceLine invoiceLine = TenantOwnership.loadOwned(InvoiceLine.class,
        body.optString(FIELD_INVOICE_LINE_ID, null));
    OrderLine orderLine = invoiceLine == null ? null : invoiceLine.getSalesOrderLine();
    if (orderLine == null || SalesQuotations.isQuotationLine(orderLine)) {
      return;
    }
    try {
      body.put(FIELD_SALES_ORDER_LINE, orderLine.getId());
    } catch (JSONException e) {
      log.warn("Could not take the order line of invoice line {}: {}",
          body.optString(FIELD_INVOICE_LINE_ID, null), e.getMessage());
    }
  }

  /** Whether {@code body} carries a non-blank, non-null value under {@code key}. */
  private static boolean hasValue(JSONObject body, String key) {
    if (!body.has(key) || body.isNull(key)) {
      return false;
    }
    String value = body.optString(key, null);
    return value != null && !value.trim().isEmpty();
  }

  /**
   * Removes {@code salesOrderLine} from a CRUD write body when it names a line of a sales
   * quotation (ETP-6002 CP-14): no shipment delivers a quotation. Same on REST single,
   * {@code /batch} and MCP, which all run this pre-hook; the window keeps sending it and is corrected here. An
   * order line that is not the caller's own is left alone (the write path refuses it).
   */
  static void dropQuotationOrderLine(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      return;
    }
    JSONObject body = context.getRequestBody();
    String orderLineId = body == null ? null : body.optString(FIELD_SALES_ORDER_LINE, null);
    if (orderLineId == null || orderLineId.isEmpty()) {
      return;
    }
    OrderLine orderLine = TenantOwnership.loadOwned(OrderLine.class, orderLineId);
    if (SalesQuotations.isQuotationLine(orderLine)) {
      body.remove(FIELD_SALES_ORDER_LINE);
      log.debug("Shipment line not linked to quotation line {}: a quotation is not delivered",
          orderLineId);
    }
  }

  /**
   * Publishes {@code invoiceLineId} to agents ({@code etendo_schema(view:"create")}) and lets the
   * MCP write verbs accept it without reporting it as an unknown field (ETP-6002 CP-22). It is not
   * a spec field: {@link AbstractInOutLineHandler#handle} reads it before the field filter and
   * {@link #linkInvoiceLine} links the line after the create.
   */
  @Override
  public Map<String, String> createDirectives() {
    return Map.of(FIELD_INVOICE_LINE_ID,
        "Optional. The id of the sales invoice line this shipment line ships (from "
            + "availableInvoiceLines). Links the new line to that invoice line, so the invoice "
            + "line's pending quantity drops by what this line moves and a fully shipped invoice "
            + "is no longer offered. The shipment line also takes that invoice line's sales "
            + "order line (salesOrderLine is set for you, never a quotation's), so the order "
            + "counts the delivery too. An id that is not one of your invoice lines is ignored. "
            + "Create only.");
  }

  /** The first shipment of the invoice line in its column, a later one in the match table. */
  @Override
  protected void linkInvoiceLine(String invoiceLineId, String newLineId) {
    InvoiceLineLinker.linkInvoiceLineToInOutLine(invoiceLineId, newLineId,
        InOutInvoiceLinks.MatchTable.SALES);
  }
}
