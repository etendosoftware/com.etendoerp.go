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
import java.math.RoundingMode;
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
 * NeoHandler that returns aggregated invoice stats (revenue + expenses) for a
 * specific Business Partner. Called by the Contact detail view sidebar.
 *
 * Endpoint: GET /sws/neo/contacts/bp-stats?businessPartnerId={c_bpartner_id}
 *
 * Returns current month and previous month totals, computed directly in SQL
 * to avoid fetching thousands of invoice records to the frontend.
 */
@Named("contactsBpStatsHandler")
public class ContactsBpStatsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ContactsBpStatsHandler.class);

  private static final String PARAM_BP_ID = "businessPartnerId";

  private static final String BP_STATS_SQL =
      "SELECT "
      + "  COALESCE(SUM(CASE WHEN date_trunc('month', i.dateinvoiced) = date_trunc('month', NOW()) "
      + "    THEN i.grandtotal END), 0) AS current_month, "
      + "  COALESCE(SUM(CASE WHEN date_trunc('month', i.dateinvoiced) = date_trunc('month', NOW()) - INTERVAL '1 month' "
      + "    THEN i.grandtotal END), 0) AS previous_month "
      + "FROM c_invoice i "
      + "WHERE i.ad_client_id = :clientId "
      + "  AND i.c_bpartner_id = :bpartnerId "
      + "  AND i.issotrx = :isSoTrx "
      + "  AND i.docstatus IN ('CO', 'CL') "
      + "  AND date_trunc('month', i.dateinvoiced) >= date_trunc('month', NOW()) - INTERVAL '1 month'";

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

        BigDecimal[] revenue = queryTotals(clientId, bpartnerId, "Y");
        BigDecimal[] expenses = queryTotals(clientId, bpartnerId, "N");

        JSONArray data = new JSONArray();
        data.put(buildKpi("revenueThisMonth", "Revenue this month",
            revenue[0], revenue[1], "currency", "DollarSign"));
        data.put(buildKpi("expensesThisMonth", "Expenses this month",
            expenses[0], expenses[1], "currency", "CreditCard"));

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
      log.error("Error computing BP stats for bpartnerId={}", bpartnerId, e);
      return NeoResponse.error(500, "BP stats handler failed: " + e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private BigDecimal[] queryTotals(String clientId, String bpartnerId, String isSoTrx) {
    NativeQuery<Object[]> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(BP_STATS_SQL);
    query.setParameter("clientId", clientId);
    query.setParameter("bpartnerId", bpartnerId);
    query.setParameter("isSoTrx", isSoTrx);

    List<Object[]> rows = query.list();
    if (rows.isEmpty() || rows.get(0) == null) {
      return new BigDecimal[]{ BigDecimal.ZERO, BigDecimal.ZERO };
    }
    Object[] row = rows.get(0);
    return new BigDecimal[]{ toBigDecimal(row[0]), toBigDecimal(row[1]) };
  }

  private static JSONObject buildKpi(String key, String label,
      BigDecimal current, BigDecimal previous, String format, String icon) throws Exception {
    double trend = calculateTrend(current, previous);
    JSONObject obj = new JSONObject();
    obj.put("key", key);
    obj.put("label", label);
    obj.put("value", current.doubleValue());
    obj.put("previousValue", previous.doubleValue());
    obj.put("format", format);
    obj.put("trend", trend);
    obj.put("icon", icon);
    return obj;
  }

  private static double calculateTrend(BigDecimal current, BigDecimal previous) {
    if (previous.compareTo(BigDecimal.ZERO) == 0) return 0.0;
    return current.subtract(previous)
        .multiply(BigDecimal.valueOf(100))
        .divide(previous.abs(), 1, RoundingMode.HALF_UP)
        .doubleValue();
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value == null) return BigDecimal.ZERO;
    if (value instanceof BigDecimal) return (BigDecimal) value;
    return new BigDecimal(String.valueOf(value));
  }
}
