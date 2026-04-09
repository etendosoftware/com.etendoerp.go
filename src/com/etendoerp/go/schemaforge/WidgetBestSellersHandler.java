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

import java.math.BigDecimal;
import java.util.List;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler that returns the top 10 best-selling products by quantity
 * for the dashboard widget. Queries completed sales invoice lines grouped
 * by product, ordered by total quantity invoiced descending, including UOM.
 */
@Named("widgetBestSellersHandler")
public class WidgetBestSellersHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetBestSellersHandler.class);

  private static final String BEST_SELLERS_QUERY =
      "SELECT p.name, SUM(il.qtyinvoiced) AS qty, COALESCE(uom.name, '') AS uom "
    + "FROM c_invoiceline il "
    + "JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id "
    + "JOIN m_product p ON p.m_product_id = il.m_product_id "
    + "LEFT JOIN c_uom uom ON uom.c_uom_id = p.c_uom_id "
    + "WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId "
    + "GROUP BY p.m_product_id, p.name, uom.name "
    + "ORDER BY SUM(il.qtyinvoiced) DESC "
    + "LIMIT 10";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();

        @SuppressWarnings("unchecked")
        NativeQuery<Object[]> query = OBDal.getInstance()
            .getSession()
            .createNativeQuery(BEST_SELLERS_QUERY);
        query.setParameter("clientId", clientId);

        List<Object[]> rows = query.list();

        JSONArray data = new JSONArray();
        for (Object[] row : rows) {
          JSONObject item = new JSONObject();
          item.put("name", row[0]);
          item.put("qty", ((BigDecimal) row[1]).longValue());
          item.put("uom", row[2]);
          data.put(item);
        }

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);
        responseData.put("count", data.length());

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.ok(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error fetching best sellers", e);
      return NeoResponse.error(500, "Best sellers handler failed: " + e.getMessage());
    }
  }
}
