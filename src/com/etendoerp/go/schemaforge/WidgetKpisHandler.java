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
import org.openbravo.model.ad.access.Role;

/**
 * NeoHandler that returns KPI summary data for the dashboard widget.
 * Queries real invoice data from c_invoice aggregated over the current calendar
 * year (January 1st through December 31st), compared against the full previous
 * calendar year.
 *
 * <p>ETP-5011: this widget deliberately ignores the dashboard date-range selector
 * ({@code ?range=}). Unlike the widgets that go through
 * {@link WidgetQueryHelper#resolveQuery}, the Financial Summary is always a
 * calendar-year figure, which is what its "this year" / "vs previous year" copy
 * states. Do not wire it to the range selector.</p>
 *
 * <p>ETP-5011 (Inconsistency 2): revenue/expenses use {@code c_invoice.totallines}
 * (the tax-exclusive subtotal, i.e. "base imponible") rather than
 * {@code grandtotal} (which includes VAT/IVA). VAT is not the company's own
 * income or expense, and this keeps the widget consistent with "Productos más
 * vendidos" (also net). {@code WidgetPendingAmountsHandler} ("Cobros y Pagos")
 * is a deliberate exception: it uses {@code outstandingamt} because it reports
 * actual cash owed, where VAT legitimately belongs.</p>
 */
@Named("widgetKpisHandler")
public class WidgetKpisHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetKpisHandler.class);

  private static final String FORMAT_CURRENCY = "currency";
  private static final String CLIENT_ID_PARAM = "clientId";

  private static final String REVENUE_SQL =
      "SELECT "
      + "  COALESCE(SUM(CASE WHEN i.dateinvoiced >= date_trunc('year', NOW()) "
      + "    AND i.dateinvoiced < date_trunc('year', NOW()) + INTERVAL '1 year' "
      + "    THEN i.totallines END), 0) AS current_year, "
      + "  COALESCE(SUM(CASE WHEN i.dateinvoiced >= date_trunc('year', NOW()) - INTERVAL '1 year' "
      + "    AND i.dateinvoiced < date_trunc('year', NOW()) "
      + "    THEN i.totallines END), 0) AS previous_year "
      + "FROM c_invoice i "
      + "WHERE i.ad_client_id = :clientId "
      + "  AND i.issotrx = :isSoTrx "
      + "  AND i.docstatus IN ('CO','CL') "
      + "  AND i.dateinvoiced >= date_trunc('year', NOW()) - INTERVAL '1 year' "
      + "  AND i.dateinvoiced < date_trunc('year', NOW()) + INTERVAL '1 year'";

  private static final String PENDING_SQL =
      "SELECT COUNT(*) "
      + "FROM c_invoice i "
      + "WHERE i.ad_client_id = :clientId "
      + "  AND i.issotrx = 'Y' "
      + "  AND i.docstatus = 'CO' "
      + "  AND i.outstandingamt > 0";

  private static final String HAS_ACTIVITY_SQL =
      "SELECT 1 FROM c_invoice "
      + "WHERE ad_client_id = :clientId "
      + "AND docstatus IN ('CO','CL')";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    // ETP-5088 — role gate. Resolved BEFORE admin mode below, which exists only to bypass
    // row-level security on the query, never to decide access. Denied returns an empty payload
    // rather than a 403 (see WidgetAccessPolicy): the Financial summary is treasury data: the matrix gives it to Admin + Finance only, which invoices cannot express (they would let Sales and Purchasing in).
    Role role = WidgetAccessPolicy.currentRole();
    if (!WidgetAccessPolicy.canRead(role, WidgetAccessPolicy.WINDOW_FINANCIAL_ACCOUNT)) {
      return WidgetQueryHelper.buildEmptyDataResponse();
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();

        if (!queryHasActivity(clientId)) {
          JSONObject responseData = new JSONObject();
          responseData.put("data", new JSONArray());
          responseData.put("count", 0);
          JSONObject wrapper = new JSONObject();
          wrapper.put("response", responseData);
          return NeoResponse.ok(wrapper);
        }

        BigDecimal[] revenue = queryInvoiceTotals(clientId, "Y");
        BigDecimal[] expenses = queryInvoiceTotals(clientId, "N");

        BigDecimal revenueCurrentYear = revenue[0];
        BigDecimal revenuePreviousYear = revenue[1];
        BigDecimal expensesCurrentYear = expenses[0];
        BigDecimal expensesPreviousYear = expenses[1];

        BigDecimal netProfitCurrent = revenueCurrentYear.subtract(expensesCurrentYear);
        BigDecimal netProfitPrevious = revenuePreviousYear.subtract(expensesPreviousYear);

        long pendingCount = queryPendingInvoices(clientId);

        double revenueTrend = calculateTrend(revenueCurrentYear, revenuePreviousYear);
        double expensesTrend = calculateTrend(expensesCurrentYear, expensesPreviousYear);
        double netProfitTrend = calculateTrend(netProfitCurrent, netProfitPrevious);

        JSONArray data = new JSONArray();
        data.put(kpi("revenueThisMonth", "Revenue this year",
          revenueCurrentYear.doubleValue(), FORMAT_CURRENCY, revenueTrend, "DollarSign"));
        data.put(kpi("expensesThisMonth", "Expenses this year",
          expensesCurrentYear.doubleValue(), FORMAT_CURRENCY, expensesTrend, "CreditCard"));
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
   * Queries invoice totals for the current calendar year and the previous calendar year.
   * Returns an array of [currentYearTotal, previousYearTotal].
   */
  @SuppressWarnings("unchecked")
  private BigDecimal[] queryInvoiceTotals(String clientId, String isSoTrx) {
    NativeQuery<Object[]> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(REVENUE_SQL);
    query.setParameter(CLIENT_ID_PARAM, clientId);
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
   * Returns true if the client has at least one completed/closed invoice.
   * Uses FETCH FIRST 1 ROW ONLY to stop at the first match.
   */
  private boolean queryHasActivity(String clientId) {
    NativeQuery<Object> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(HAS_ACTIVITY_SQL);
    query.setParameter(CLIENT_ID_PARAM, clientId);
    return query.setMaxResults(1).uniqueResult() != null;
  }

  /**
   * Counts pending sales invoices (outstanding amount > 0, completed status).
   */
  private long queryPendingInvoices(String clientId) {
    NativeQuery<Number> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(PENDING_SQL);
    query.setParameter(CLIENT_ID_PARAM, clientId);

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
