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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler that returns KPI summary data for the dashboard widget.
 * Queries real invoice data from c_invoice, using the most recent invoice
 * month as the "current" month anchor (since data may not be recent).
 */
@Named("widgetKpisHandler")
public class WidgetKpisHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetKpisHandler.class);

  private static final String FORMAT_CURRENCY = "currency";

  private static final String REVENUE_SQL =
      "SELECT "
      + "  COALESCE(SUM(CASE WHEN date_trunc('month', i.dateinvoiced) = date_trunc('month', mx.max_date) "
      + "    THEN i.grandtotal END), 0) AS current_month, "
      + "  COALESCE(SUM(CASE WHEN date_trunc('month', i.dateinvoiced) = date_trunc('month', mx.max_date) - INTERVAL '1 month' "
      + "    THEN i.grandtotal END), 0) AS previous_month "
      + "FROM c_invoice i, "
      + "  (SELECT MAX(dateinvoiced) AS max_date FROM c_invoice WHERE ad_client_id = :clientId AND docstatus IN ('CO','CL')) mx "
      + "WHERE i.ad_client_id = :clientId "
      + "  AND i.issotrx = :isSoTrx "
      + "  AND i.docstatus IN ('CO','CL') "
      + "  AND date_trunc('month', i.dateinvoiced) >= date_trunc('month', mx.max_date) - INTERVAL '1 month'";

  private static final String PENDING_SQL =
      "SELECT COUNT(*) "
      + "FROM c_invoice i "
      + "WHERE i.ad_client_id = :clientId "
      + "  AND i.issotrx = 'Y' "
      + "  AND i.docstatus = 'CO' "
      + "  AND i.outstandingamt > 0";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();

        BigDecimal[] revenue = queryInvoiceTotals(clientId, "Y");
        BigDecimal[] expenses = queryInvoiceTotals(clientId, "N");

        BigDecimal revenueCurrentMonth = revenue[0];
        BigDecimal revenuePreviousMonth = revenue[1];
        BigDecimal expensesCurrentMonth = expenses[0];
        BigDecimal expensesPreviousMonth = expenses[1];

        BigDecimal netProfitCurrent = revenueCurrentMonth.subtract(expensesCurrentMonth);
        BigDecimal netProfitPrevious = revenuePreviousMonth.subtract(expensesPreviousMonth);

        long pendingCount = queryPendingInvoices(clientId);

        double revenueTrend = calculateTrend(revenueCurrentMonth, revenuePreviousMonth);
        double expensesTrend = calculateTrend(expensesCurrentMonth, expensesPreviousMonth);
        double netProfitTrend = calculateTrend(netProfitCurrent, netProfitPrevious);

        JSONArray data = new JSONArray();
        data.put(kpi("revenueThisMonth", "Revenue this month",
          revenueCurrentMonth.doubleValue(), FORMAT_CURRENCY, revenueTrend, "DollarSign"));
        data.put(kpi("expensesThisMonth", "Expenses this month",
          expensesCurrentMonth.doubleValue(), FORMAT_CURRENCY, expensesTrend, "CreditCard"));
        data.put(kpi("netProfit", "Net Profit",
          netProfitCurrent.doubleValue(), FORMAT_CURRENCY, netProfitTrend, "TrendingUp"));
        data.put(kpi("pendingInvoices", "Pending Invoices",
            pendingCount, "number", 0, "Clock"));

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
      log.error("Error building KPI data", e);
      return NeoResponse.error(500, "KPI handler failed: " + e.getMessage());
    }
  }

  /**
   * Queries invoice totals for the current month (max dateinvoiced month) and previous month.
   * Returns an array of [currentMonthTotal, previousMonthTotal].
   */
  @SuppressWarnings("unchecked")
  private BigDecimal[] queryInvoiceTotals(String clientId, String isSoTrx) {
    NativeQuery<Object[]> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(REVENUE_SQL);
    query.setParameter("clientId", clientId);
    query.setParameter("isSoTrx", isSoTrx);

    List<Object[]> rows = query.list();
    if (rows.isEmpty() || rows.get(0) == null) {
      return new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO };
    }

    Object[] row = rows.get(0);
    return new BigDecimal[] {
        toBigDecimal(row[0]),
        toBigDecimal(row[1])
    };
  }

  /**
   * Counts pending sales invoices (outstanding amount > 0, completed status).
   */
  private long queryPendingInvoices(String clientId) {
    NativeQuery<Number> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(PENDING_SQL);
    query.setParameter("clientId", clientId);

    Number result = query.uniqueResult();
    return result != null ? result.longValue() : 0L;
  }

  /**
   * Calculates the trend percentage between current and previous values.
   * Returns 0 if previous value is zero (avoids division by zero).
   * Result is rounded to 1 decimal place.
   */
  private static double calculateTrend(BigDecimal current, BigDecimal previous) {
    if (previous.compareTo(BigDecimal.ZERO) == 0) {
      return 0.0;
    }
    BigDecimal diff = current.subtract(previous);
    BigDecimal trend = diff.multiply(BigDecimal.valueOf(100))
        .divide(previous.abs(), 1, RoundingMode.HALF_UP);
    return trend.doubleValue();
  }

  private static JSONObject kpi(String key, String label, Number value, String format,
      double trend, String icon) throws Exception {
    JSONObject obj = new JSONObject();
    obj.put("key", key);
    obj.put("label", label);
    obj.put("value", value);
    obj.put("format", format);
    obj.put("trend", trend);
    obj.put("icon", icon);
    return obj;
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return new BigDecimal(String.valueOf(value));
  }
}
