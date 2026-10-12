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
import java.util.Set;

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
 * Endpoint: GET /sws/neo/contacts/bp-stats?businessPartnerId={c_bpartner_id}[&months={3|6}]
 *
 * Returns current month and previous month totals, computed directly in SQL
 * to avoid fetching thousands of invoice records to the frontend.
 *
 * <p>ETP-5600: amounts are NET ({@code c_invoice.totallines}, VAT excluded), consistent with the
 * dashboard {@code WidgetKpisHandler} (ETP-5011).</p>
 *
 * <p>ETP-5600: the optional {@code months} param selects a period for the summary widget. Only
 * the values in {@link #ALLOWED_MONTHS} are honoured; anything else is treated as absent, and an
 * absent {@code months} returns exactly the original response (the MCP call shape
 * {@code etendo_list(contacts, bp-stats, filters:{businessPartnerId})} depends on it). When it is
 * present the response gains two KPI entries ({@code revenue}, {@code expenses}) carrying
 * {@code hasPrevious}, plus a top-level {@code months}. The current window is the last N calendar
 * months including the current one, up to {@code NOW()}; the comparison window is the N calendar
 * months immediately before it (same equal-size preceding window as {@code WidgetKpisHandler},
 * ETP-5493).</p>
 */
@Named("contactsBpStatsHandler")
public class ContactsBpStatsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ContactsBpStatsHandler.class);

  private static final String PARAM_BP_ID = "businessPartnerId";
  static final String PARAM_MONTHS = "months";
  static final Set<Integer> ALLOWED_MONTHS = Set.of(3, 6);

  private static final String BP_STATS_SQL =
      "SELECT "
      + "  COALESCE(SUM(CASE WHEN date_trunc('month', i.dateinvoiced) = date_trunc('month', NOW()) "
      + "    THEN i.totallines END), 0) AS current_month, "
      + "  COALESCE(SUM(CASE WHEN date_trunc('month', i.dateinvoiced) = date_trunc('month', NOW()) - INTERVAL '1 month' "
      + "    THEN i.totallines END), 0) AS previous_month "
      + "FROM c_invoice i "
      + "WHERE i.ad_client_id = :clientId "
      + "  AND i.c_bpartner_id = :bpartnerId "
      + "  AND i.issotrx = :isSoTrx "
      + "  AND i.docstatus IN ('CO', 'CL') "
      + "  AND date_trunc('month', i.dateinvoiced) >= date_trunc('month', NOW()) - INTERVAL '1 month'";

  /** Start of the current window: first day of the month (N - 1) months ago. */
  private static final String PERIOD_START =
      "date_trunc('month', NOW()) - (:months - 1) * INTERVAL '1 month'";

  /** Start of the comparison window: N months before {@link #PERIOD_START}. */
  private static final String PREVIOUS_PERIOD_START =
      "date_trunc('month', NOW()) - (2 * :months - 1) * INTERVAL '1 month'";

  /**
   * Same month-vs-month columns as {@link #BP_STATS_SQL} (so the original KPI entries keep their
   * values), plus the selected period and its equal-size preceding window. {@code :months} is
   * bound as an integer; its lower bound covers the previous month for every allowed N.
   */
  private static final String BP_STATS_PERIOD_SQL =
      "SELECT "
      + "  COALESCE(SUM(CASE WHEN date_trunc('month', i.dateinvoiced) = date_trunc('month', NOW()) "
      + "    THEN i.totallines END), 0) AS current_month, "
      + "  COALESCE(SUM(CASE WHEN date_trunc('month', i.dateinvoiced) = date_trunc('month', NOW()) - INTERVAL '1 month' "
      + "    THEN i.totallines END), 0) AS previous_month, "
      + "  COALESCE(SUM(CASE WHEN i.dateinvoiced >= " + PERIOD_START + " "
      + "    AND i.dateinvoiced <= NOW() "
      + "    THEN i.totallines END), 0) AS current_period, "
      + "  COALESCE(SUM(CASE WHEN i.dateinvoiced >= " + PREVIOUS_PERIOD_START + " "
      + "    AND i.dateinvoiced < " + PERIOD_START + " "
      + "    THEN i.totallines END), 0) AS previous_period "
      + "FROM c_invoice i "
      + "WHERE i.ad_client_id = :clientId "
      + "  AND i.c_bpartner_id = :bpartnerId "
      + "  AND i.issotrx = :isSoTrx "
      + "  AND i.docstatus IN ('CO', 'CL') "
      + "  AND i.dateinvoiced >= " + PREVIOUS_PERIOD_START;

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
        Integer months = resolveMonths(params);

        BigDecimal[] revenue = queryTotals(clientId, bpartnerId, "Y", months);
        BigDecimal[] expenses = queryTotals(clientId, bpartnerId, "N", months);

        JSONArray data = new JSONArray();
        data.put(buildKpi("revenueThisMonth", "Revenue this month",
            revenue[0], revenue[1], "currency", "DollarSign"));
        data.put(buildKpi("expensesThisMonth", "Expenses this month",
            expenses[0], expenses[1], "currency", "CreditCard"));
        if (months != null) {
          data.put(buildPeriodKpi("revenue", "Revenue",
              revenue[2], revenue[3], "DollarSign"));
          data.put(buildPeriodKpi("expenses", "Expenses",
              expenses[2], expenses[3], "CreditCard"));
        }

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);
        responseData.put("count", data.length());
        if (months != null) {
          responseData.put(PARAM_MONTHS, months.intValue());
        }

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

  /**
   * Returns the requested period length when it is one of {@link #ALLOWED_MONTHS}, otherwise
   * {@code null} (absent, blank, non-numeric or not allowed all mean "no period requested").
   */
  static Integer resolveMonths(Map<String, String> params) {
    String raw = params.get(PARAM_MONTHS);
    if (StringUtils.isBlank(raw)) {
      return null;
    }
    try {
      int months = Integer.parseInt(raw.trim());
      return ALLOWED_MONTHS.contains(months) ? months : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Returns [currentMonth, previousMonth] when {@code months} is {@code null}, or
   * [currentMonth, previousMonth, currentPeriod, previousPeriod] otherwise.
   */
  @SuppressWarnings("unchecked")
  private BigDecimal[] queryTotals(String clientId, String bpartnerId, String isSoTrx,
      Integer months) {
    int columns = months != null ? 4 : 2;
    NativeQuery<Object[]> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(months != null ? BP_STATS_PERIOD_SQL : BP_STATS_SQL);
    query.setParameter("clientId", clientId);
    query.setParameter("bpartnerId", bpartnerId);
    query.setParameter("isSoTrx", isSoTrx);
    if (months != null) {
      query.setParameter(PARAM_MONTHS, months);
    }

    BigDecimal[] totals = new BigDecimal[columns];
    List<Object[]> rows = query.list();
    Object[] row = rows.isEmpty() ? null : rows.get(0);
    for (int i = 0; i < columns; i++) {
      totals[i] = row != null && row.length > i ? toBigDecimal(row[i]) : BigDecimal.ZERO;
    }
    return totals;
  }

  private static JSONObject buildPeriodKpi(String key, String label,
      BigDecimal current, BigDecimal previous, String icon) throws Exception {
    JSONObject obj = buildKpi(key, label, current, previous, "currency", icon);
    obj.put("hasPrevious", hasPrevious(previous));
    return obj;
  }

  /**
   * A comparison is meaningful only when the previous-period value is non-zero; otherwise the
   * percentage change is undefined and the client hides the variation chip (trend stays 0).
   */
  static boolean hasPrevious(BigDecimal previous) {
    return previous.compareTo(BigDecimal.ZERO) != 0;
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
