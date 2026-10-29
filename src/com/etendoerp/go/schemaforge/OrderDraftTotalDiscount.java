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

import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * The {@code grandTotalAmount} an order or quotation header shows while it is a draft with a total
 * discount still pending, for {@link AbstractOrderHeaderHandler}: the discount only becomes a real
 * line at Complete time (via {@link TotalDiscountService}), so the stored total is the
 * undiscounted one until then.
 */
final class OrderDraftTotalDiscount {

  private static final Logger log = LogManager.getLogger(OrderDraftTotalDiscount.class);
  private static final String FIELD_PROCESSED = "processed";
  private static final String FIELD_TOTAL_DISCOUNT_PCT = "etgoTotalDiscount";
  private static final String FIELD_GRAND_TOTAL_AMOUNT = "grandTotalAmount";
  private static final String FIELD_ID = "id";

  private OrderDraftTotalDiscount() {
  }

  /**
   * Adjusts {@code grandTotalAmount} for a draft order/quotation carrying a positive
   * {@code etgoTotalDiscount} percentage that has not yet been materialized as a real line —
   * the same GET-time compensation {@code SalesInvoiceHeaderHandler} already applies for
   * invoices (list view and preview cards otherwise showed the raw undiscounted total while
   * the document was still in draft).
   *
   * <p>No-op when the document is processed (the DB total already reflects the discount),
   * when no discount percentage is set, or when the discount is already a real line
   * ({@code hasDiscountLine} — avoids double-counting when e.g. an order was created carrying
   * an already-materialized discount). Mirrors {@code AbstractInvoiceHeaderHandler}'s exact
   * guard order: the service is only obtained when {@code id} is present.
   *
   * <p>Unlike the invoice version, there is no {@code outstandingAmount} to adjust here —
   * orders/quotations do not expose that field.
   */
  static void applyToRecord(JSONObject order, Supplier<TotalDiscountService> service)
      throws Exception {
    if (order.optBoolean(FIELD_PROCESSED, false)) {
      return;
    }
    double discountPct = order.optDouble(FIELD_TOTAL_DISCOUNT_PCT, 0.0);
    if (discountPct <= 0.0) {
      return;
    }
    String orderId = order.optString(FIELD_ID, null);
    if (orderId != null && service.get().hasDiscountLine(orderId, false)) {
      return;
    }
    double factor = 1.0 - discountPct / 100.0;
    double grand = order.optDouble(FIELD_GRAND_TOTAL_AMOUNT, 0.0);
    order.put(FIELD_GRAND_TOTAL_AMOUNT, NeoHandlerUtils.roundHalfUp(grand * factor));
  }

  /**
   * ETP-5727 (in ETP-5852): the record an MCP update answers with shows the same
   * {@code grandTotalAmount} a read shows right after it — with the draft's pending total discount
   * applied (see {@link #applyToRecord}). Before, the update answered the undiscounted total and
   * only the next read showed the discounted one. MCP {@code PUT} only: the SPA reads the record
   * again after saving. The response body is patched in place.
   */
  static void applyToMcpUpdate(NeoContext context, Supplier<TotalDiscountService> service) {
    if (!context.isMcpOrigin() || !"PUT".equals(context.getHttpMethod())) {
      return;
    }
    NeoResponse previous = context.getPreviousResult();
    JSONObject wrapper = previous == null || previous.getBody() == null ? null
        : previous.getBody().optJSONObject("response");
    JSONArray data = wrapper == null ? null : wrapper.optJSONArray("data");
    if (data == null) {
      return;
    }
    try {
      for (int i = 0; i < data.length(); i++) {
        applyToRecord(data.getJSONObject(i), service);
      }
    } catch (Exception e) {
      log.error("Error applying the total discount to an update response (id={})",
          context.getRecordId(), e);
    }
  }
}
