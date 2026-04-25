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
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

/**
 * NeoHandler that returns the top 10 best-selling products by quantity for the requested
 * date range. When no range is supplied it ranks by all-time quantity (original behaviour).
 */
@Named("widgetBestSellersHandler")
public class WidgetBestSellersHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetBestSellersHandler.class);

  // All-time ranking; trend comparison anchored to MAX(dateinvoiced) month
  private static final String BEST_SELLERS_FALLBACK =
      "WITH all_data AS ("
    + "  SELECT p.m_product_id, p.name, SUM(il.qtyinvoiced) AS qty, COALESCE(uom.name, '') AS uom"
    + "  FROM c_invoiceline il"
    + "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
    + "  JOIN m_product p ON p.m_product_id = il.m_product_id"
    + "  LEFT JOIN c_uom uom ON uom.c_uom_id = p.c_uom_id"
    + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
    + "  GROUP BY p.m_product_id, p.name, uom.name"
    + "), curr_period AS ("
    + "  SELECT il.m_product_id, SUM(il.qtyinvoiced) AS qty"
    + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
    + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
    + "    AND date_trunc('month', i.dateinvoiced) = ("
    + "      SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
    + "      WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId)"
    + "  GROUP BY il.m_product_id"
    + "), prev_period AS ("
    + "  SELECT il.m_product_id, SUM(il.qtyinvoiced) AS qty"
    + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
    + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
    + "    AND date_trunc('month', i.dateinvoiced) = ("
    + "      SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
    + "      WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId"
    + "        AND date_trunc('month', dateinvoiced) < ("
    + "          SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
    + "          WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId))"
    + "  GROUP BY il.m_product_id"
    + ")"
    + " SELECT all_data.m_product_id AS id, all_data.name, all_data.qty, all_data.uom,"
    + "  CASE WHEN prev_period.qty IS NOT NULL AND prev_period.qty > 0"
    + "    THEN ROUND(((curr_period.qty - prev_period.qty) / prev_period.qty) * 100)"
    + "    ELSE NULL END AS trend_pct"
    + " FROM all_data"
    + " LEFT JOIN curr_period ON all_data.m_product_id = curr_period.m_product_id"
    + " LEFT JOIN prev_period ON all_data.m_product_id = prev_period.m_product_id"
    + " ORDER BY all_data.qty DESC LIMIT 10";

  // Range-filtered ranking; %s is a safe hardcoded SQL date expression.
  // curr_period = current calendar month; prev_period = most recent month before current that has data.
  private static final String BEST_SELLERS_RANGED =
      "WITH all_data AS ("
    + "  SELECT p.m_product_id, p.name, SUM(il.qtyinvoiced) AS qty, COALESCE(uom.name, '') AS uom"
    + "  FROM c_invoiceline il"
    + "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
    + "  JOIN m_product p ON p.m_product_id = il.m_product_id"
    + "  LEFT JOIN c_uom uom ON uom.c_uom_id = p.c_uom_id"
    + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
    + "    AND i.dateinvoiced >= %s"
    + "  GROUP BY p.m_product_id, p.name, uom.name"
    + "), curr_period AS ("
    + "  SELECT il.m_product_id, SUM(il.qtyinvoiced) AS qty"
    + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
    + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
    + "    AND date_trunc('month', i.dateinvoiced) = date_trunc('month', NOW())"
    + "  GROUP BY il.m_product_id"
    + "), prev_period AS ("
    + "  SELECT il.m_product_id, SUM(il.qtyinvoiced) AS qty"
    + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
    + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
    + "    AND date_trunc('month', i.dateinvoiced) = ("
    + "      SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
    + "      WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId"
    + "        AND date_trunc('month', dateinvoiced) < date_trunc('month', NOW()))"
    + "  GROUP BY il.m_product_id"
    + ")"
    + " SELECT all_data.m_product_id AS id, all_data.name, all_data.qty, all_data.uom,"
    + "  CASE WHEN prev_period.qty IS NOT NULL AND prev_period.qty > 0"
    + "    THEN ROUND(((curr_period.qty - prev_period.qty) / prev_period.qty) * 100)"
    + "    ELSE NULL END AS trend_pct"
    + " FROM all_data"
    + " LEFT JOIN curr_period ON all_data.m_product_id = curr_period.m_product_id"
    + " LEFT JOIN prev_period ON all_data.m_product_id = prev_period.m_product_id"
    + " ORDER BY all_data.qty DESC LIMIT 10";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();
        Map<String, String> params = context.getQueryParams();
        String range = params != null ? params.get("range") : null;

        List<Object[]> rows = (range != null && !range.isEmpty())
            ? WidgetQueryHelper.executeRangedQuery(BEST_SELLERS_RANGED, clientId, range)
            : WidgetQueryHelper.executeFallbackQuery(BEST_SELLERS_FALLBACK, clientId);

        JSONArray data = new JSONArray();
        for (Object[] row : rows) {
          JSONObject item = new JSONObject();
          item.put("id",   row[0]);
          item.put("name", row[1]);
          item.put("qty",  ((BigDecimal) row[2]).longValue());
          item.put("uom",  row[3]);
          if (row[4] != null) item.put("trendPct", ((BigDecimal) row[4]).intValue());
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
