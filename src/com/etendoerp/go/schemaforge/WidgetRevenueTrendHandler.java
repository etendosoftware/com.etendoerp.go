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
import org.openbravo.model.ad.access.Role;

/**
 * NeoHandler that returns monthly revenue trend data for the dashboard widget.
 * Queries completed sales invoices (c_invoice) grouped by month, using the last
 * 12 months of available data anchored to the most recent invoice date.
 *
 * <p>ETP-5011 (Inconsistency 2): uses {@code c_invoice.totallines} (tax-exclusive
 * subtotal, "base imponible") rather than {@code grandtotal}, so this widget's
 * monthly totals stay consistent with the net figures in {@code WidgetKpisHandler}
 * ("Resumen Financiero") — VAT/IVA is not the company's own income or expense.</p>
 */
@Named("widgetRevenueTrendHandler")
public class WidgetRevenueTrendHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetRevenueTrendHandler.class);

  private static final String TREND_QUERY =
      "WITH max_date AS ( "
    + "  SELECT date_trunc('month', max(dateinvoiced)) AS last_month "
    + "  FROM c_invoice "
    + "  WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId "
    + "), "
    + "months AS ( "
    + "  SELECT generate_series( "
    + "    (SELECT last_month - interval '11 months' FROM max_date), "
    + "    (SELECT last_month FROM max_date), "
    + "    CAST('1 month' AS interval) "
    + "  ) AS month "
    + ") "
    + "SELECT to_char(m.month, 'Mon') AS label, "
    + "       COALESCE(SUM(CASE WHEN i.issotrx = 'Y' THEN i.totallines ELSE 0 END), 0) AS revenue_total, "
    + "       COALESCE(SUM(CASE WHEN i.issotrx = 'N' THEN i.totallines ELSE 0 END), 0) AS expense_total "
    + "FROM months m "
    + "LEFT JOIN c_invoice i ON date_trunc('month', i.dateinvoiced) = m.month "
    + "  AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId "
    + "  AND i.issotrx IN ('Y','N') "
    + "GROUP BY m.month, to_char(m.month, 'Mon') "
    + "ORDER BY m.month";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    // ETP-5088 — role gate. Resolved BEFORE admin mode below, which exists only to bypass
    // row-level security on the query, never to decide access. Denied returns an empty payload
    // rather than a 403 (see WidgetAccessPolicy): same treasury axis as the Financial summary — Admin + Finance only.
    Role role = WidgetAccessPolicy.currentRole();
    if (!WidgetAccessPolicy.canRead(role, WidgetAccessPolicy.WINDOW_FINANCIAL_ACCOUNT)) {
      return WidgetQueryHelper.buildEmptyDataResponse();
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();

        @SuppressWarnings("unchecked")
        NativeQuery<Object[]> query = OBDal.getInstance()
            .getSession()
            .createNativeQuery(TREND_QUERY);
        query.setParameter("clientId", clientId);

        List<Object[]> rows = query.list();

        JSONArray labels = new JSONArray();
        JSONArray values = new JSONArray();
        JSONArray expenseValues = new JSONArray();

        for (Object[] row : rows) {
          String label = ((String) row[0]).trim();
          BigDecimal revenueTotal = (BigDecimal) row[1];
          BigDecimal expenseTotal = (BigDecimal) row[2];
          labels.put(label);
          values.put(revenueTotal.doubleValue());
          expenseValues.put(expenseTotal.doubleValue());
        }

        JSONObject trend = new JSONObject();
        trend.put("labels", labels);
        trend.put("values", values);
        trend.put("expenseValues", expenseValues);

        JSONArray data = new JSONArray();
        data.put(trend);

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
      log.error("Error building revenue trend data", e);
      return NeoResponse.error(500, "Revenue trend handler failed: " + e.getMessage());
    }
  }
}
