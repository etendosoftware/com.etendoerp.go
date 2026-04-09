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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler that returns the last 6 months of sales and purchase invoice
 * totals for a specific Business Partner. Used by the Contact detail sidebar chart.
 *
 * Endpoint: GET /sws/neo/contacts/bp-trend?businessPartnerId={c_bpartner_id}
 *
 * Returns arrays of labels, revenue totals, and expense totals for each month,
 * computed directly in SQL using generate_series so no records need to be
 * transferred to the frontend.
 */
@Named("contactsBpTrendHandler")
public class ContactsBpTrendHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ContactsBpTrendHandler.class);

  private static final String PARAM_BP_ID = "businessPartnerId";

  private static final String TREND_SQL =
      "WITH months AS ( "
      + "  SELECT generate_series( "
      + "    date_trunc('month', NOW()) - INTERVAL '5 months', "
      + "    date_trunc('month', NOW()), "
      + "    '1 month' "
      + "  ) AS month "
      + ") "
      + "SELECT "
      + "  to_char(m.month, 'Mon') AS label, "
      + "  COALESCE(SUM(CASE WHEN i.issotrx = 'Y' THEN i.grandtotal END), 0) AS revenue, "
      + "  COALESCE(SUM(CASE WHEN i.issotrx = 'N' THEN i.grandtotal END), 0) AS expenses "
      + "FROM months m "
      + "LEFT JOIN c_invoice i "
      + "  ON date_trunc('month', i.dateinvoiced) = m.month "
      + "  AND i.c_bpartner_id = :bpartnerId "
      + "  AND i.ad_client_id = :clientId "
      + "  AND i.docstatus IN ('CO', 'CL') "
      + "GROUP BY m.month "
      + "ORDER BY m.month";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    Map<String, String> params = context.getQueryParams();
    String bpartnerId = params.get(PARAM_BP_ID);
    if (StringUtils.isBlank(bpartnerId)) {
      return NeoResponse.error(400, "Missing required parameter: " + PARAM_BP_ID);
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();

        List<Object[]> rows = queryTrend(clientId, bpartnerId);

        JSONArray labels = new JSONArray();
        JSONArray revenue = new JSONArray();
        JSONArray expenses = new JSONArray();

        for (Object[] row : rows) {
          labels.put(row[0] != null ? row[0].toString() : "");
          revenue.put(toBigDecimal(row[1]).doubleValue());
          expenses.put(toBigDecimal(row[2]).doubleValue());
        }

        JSONObject trendData = new JSONObject();
        trendData.put("labels", labels);
        trendData.put("revenue", revenue);
        trendData.put("expenses", expenses);

        JSONObject responseData = new JSONObject();
        responseData.put("data", trendData);

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.ok(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error computing BP trend for bpartnerId={}", bpartnerId, e);
      return NeoResponse.error(500, "BP trend handler failed: " + e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object[]> queryTrend(String clientId, String bpartnerId) {
    NativeQuery<Object[]> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(TREND_SQL);
    query.setParameter("clientId", clientId);
    query.setParameter("bpartnerId", bpartnerId);
    return query.list();
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value == null) return BigDecimal.ZERO;
    if (value instanceof BigDecimal) return (BigDecimal) value;
    return new BigDecimal(String.valueOf(value));
  }
}
