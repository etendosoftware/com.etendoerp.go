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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler for the Goods Shipment line entity.
 * Extends {@link AbstractInOutLineHandler} for GET enrichment and overrides
 * handle/afterHandle to link a previously created invoice line to the new shipment line on POST.
 */
@Named("goodsShipmentLineHandler")
public class GoodsShipmentLineHandler extends AbstractInOutLineHandler {

  private static final Logger log = LogManager.getLogger(GoodsShipmentLineHandler.class);

  // Captures invoiceLineId before NeoFieldFilter strips it from the request body.
  // Cleared in afterHandle regardless of outcome.
  private static final ThreadLocal<String> PENDING_INVOICE_LINE_ID = new ThreadLocal<>();

  @Override
  public NeoResponse handle(NeoContext context) {
    PENDING_INVOICE_LINE_ID.remove();
    if ("POST".equalsIgnoreCase(context.getHttpMethod())) {
      JSONObject body = context.getRequestBody();
      if (body != null) {
        String invoiceLineId = body.optString("invoiceLineId", null);
        if (invoiceLineId != null && !invoiceLineId.isEmpty()) {
          PENDING_INVOICE_LINE_ID.set(invoiceLineId);
        }
      }
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if ("POST".equalsIgnoreCase(context.getHttpMethod())) {
      try {
        linkInvoiceLineIfPresent(context);
      } finally {
        PENDING_INVOICE_LINE_ID.remove();
      }
      return null;
    }
    return super.afterHandle(context);
  }

  private void linkInvoiceLineIfPresent(NeoContext context) {
    String invoiceLineId = PENDING_INVOICE_LINE_ID.get();
    if (invoiceLineId == null) return;
    try {
      NeoResponse prev = context.getPreviousResult();
      if (prev == null || prev.getBody() == null) return;
      JSONObject responseWrapper = prev.getBody().optJSONObject("response");
      if (responseWrapper == null) return;
      JSONArray dataArr = responseWrapper.optJSONArray("data");
      if (dataArr == null || dataArr.length() == 0) return;
      String newLineId = dataArr.getJSONObject(0).optString("id", null);
      if (newLineId == null || newLineId.isEmpty()) return;

      OBDal.getInstance().getSession()
          .createNativeQuery(
              "UPDATE c_invoiceline SET m_inoutline_id = :lineId, updated = now() " +
              "WHERE c_invoiceline_id = :invLineId AND m_inoutline_id IS NULL")
          .setParameter("lineId", newLineId)
          .setParameter("invLineId", invoiceLineId)
          .executeUpdate();
    } catch (Exception e) {
      log.warn("Could not link invoice line after shipment line creation: {}", e.getMessage());
    }
  }
}
