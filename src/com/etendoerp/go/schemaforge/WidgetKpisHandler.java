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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;

/**
 * NeoHandler that returns KPI summary data for the dashboard widget ("Resumen financiero").
 *
 * <p>ETP-5493: the widget follows the dashboard period selector ({@code ?range=}), using the
 * same range math as every other ranged widget ({@link WidgetQueryHelper#executeRangedQuery}):
 * the current period is {@code [from, now]} and the comparison period is the window of
 * equivalent size that precedes it ({@code [prevFrom, prevTo)}). For rolling ranges
 * ({@code last30d}, {@code last90d}, {@code lastYear}) that is the immediately preceding
 * window of the same length; for calendar ranges it is the same elapsed span one month/year
 * earlier ({@code mtd} vs the same days of last month, {@code ytd} vs the same days of last
 * year). This supersedes ETP-5011, which pinned the widget to the calendar year and made it
 * ignore the selector.</p>
 *
 * <p>Missing/blank {@code range} defaults to {@code ytd}, which is the ETP-5011 behaviour
 * (current calendar year) so callers that send no range (MCP/agent tools, older clients)
 * keep getting a year-to-date figure. Unknown non-blank values are resolved by
 * {@link WidgetQueryHelper} exactly as for the other widgets (rolling 12 months).</p>
 *
 * <p>Each KPI carries {@code hasPrevious}: {@code false} when the comparison period value is
 * zero, in which case {@code trend} is {@code 0} and carries no meaning (a percentage change
 * from zero is undefined). Clients must hide the trend badge instead of rendering "0%".
 * {@code hasPrevious} is additive; {@code trend} keeps its numeric type so existing
 * consumers are unaffected.</p>
 *
 * <p>Pending invoices is a point-in-time backlog count and is intentionally independent of
 * the selected period.</p>
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

  /** Range applied when the request carries no {@code range} (year-to-date, the ETP-5011 behaviour). */
  static final String DEFAULT_RANGE = "ytd";

  private static final String ISSOTRX_PLACEHOLDER = "@ISSOTRX@";

  /**
   * Revenue/expense totals template. {@code %1$s}/{@code %2$s}/{@code %3$s} are the
   * {@link WidgetQueryHelper} range expressions (current "from", previous "from", previous
   * exclusive "to") and {@link #ISSOTRX_PLACEHOLDER} is replaced by a hardcoded 'Y'/'N' literal,
   * because {@link WidgetQueryHelper#executeRangedQuery} binds only {@code :clientId}.
   */
  private static final String REVENUE_SQL =
      "SELECT "
      + "  COALESCE(SUM(CASE WHEN i.dateinvoiced >= %1$s "
      + "    AND i.dateinvoiced <= NOW() "
      + "    THEN i.totallines END), 0) AS current_period, "
      + "  COALESCE(SUM(CASE WHEN i.dateinvoiced >= %2$s "
      + "    AND i.dateinvoiced < %3$s "
      + "    THEN i.totallines END), 0) AS previous_period "
      + "FROM c_invoice i "
      + "WHERE i.ad_client_id = :clientId "
      + "  AND i.issotrx = " + ISSOTRX_PLACEHOLDER + " "
      + "  AND i.docstatus IN ('CO','CL') "
      + "  AND i.dateinvoiced >= %2$s "
      + "  AND i.dateinvoiced <= NOW()";

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

        String range = resolveRange(context);
        BigDecimal[] revenue = queryInvoiceTotals(clientId, "Y", range);
        BigDecimal[] expenses = queryInvoiceTotals(clientId, "N", range);

        BigDecimal revenueCurrent = revenue[0];
        BigDecimal revenuePrevious = revenue[1];
        BigDecimal expensesCurrent = expenses[0];
        BigDecimal expensesPrevious = expenses[1];

        BigDecimal netProfitCurrent = revenueCurrent.subtract(expensesCurrent);
        BigDecimal netProfitPrevious = revenuePrevious.subtract(expensesPrevious);

        long pendingCount = queryPendingInvoices(clientId);

        double revenueTrend = calculateTrend(revenueCurrent, revenuePrevious);
        double expensesTrend = calculateTrend(expensesCurrent, expensesPrevious);
        double netProfitTrend = calculateTrend(netProfitCurrent, netProfitPrevious);

        JSONArray data = new JSONArray();
        data.put(kpi("revenueThisMonth", "Revenue",
          revenueCurrent.doubleValue(), FORMAT_CURRENCY, revenueTrend,
          hasPrevious(revenuePrevious), "DollarSign"));
        data.put(kpi("expensesThisMonth", "Expenses",
          expensesCurrent.doubleValue(), FORMAT_CURRENCY, expensesTrend,
          hasPrevious(expensesPrevious), "CreditCard"));
        data.put(kpi("netProfit", "Net Profit",
          netProfitCurrent.doubleValue(), FORMAT_CURRENCY, netProfitTrend,
          hasPrevious(netProfitPrevious), "TrendingUp"));
        // Point-in-time backlog: not period-based, so there is no comparison to make.
        data.put(kpi("pendingInvoices", "Pending Invoices",
            pendingCount, "number", 0, false, "Clock"));

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
   * Returns the requested {@code range} query param, or {@link #DEFAULT_RANGE} when it is
   * absent or blank.
   */
  private static String resolveRange(NeoContext context) {
    Map<String, String> params = context.getQueryParams();
    String range = params != null ? params.get("range") : null;
    return (range == null || range.trim().isEmpty()) ? DEFAULT_RANGE : range.trim();
  }

  /**
   * Queries invoice totals for the period selected by {@code range} and its comparison period.
   * Returns an array of [currentPeriodTotal, previousPeriodTotal].
   */
  private BigDecimal[] queryInvoiceTotals(String clientId, String isSoTrx, String range) {
    String sql = REVENUE_SQL.replace(ISSOTRX_PLACEHOLDER, "Y".equals(isSoTrx) ? "'Y'" : "'N'");
    List<Object[]> rows = WidgetQueryHelper.executeRangedQuery(sql, clientId, range);
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
  static double calculateTrend(BigDecimal current, BigDecimal previous) {
    if (previous.compareTo(BigDecimal.ZERO) == 0) {
      return 0.0;
    }
    BigDecimal diff = current.subtract(previous);
    BigDecimal trend = diff.multiply(BigDecimal.valueOf(100))
        .divide(previous.abs(), 1, RoundingMode.HALF_UP);
    return trend.doubleValue();
  }

  /**
   * A comparison is meaningful only when the previous-period value is non-zero; otherwise the
   * percentage change is undefined and the client should hide the trend badge.
   */
  static boolean hasPrevious(BigDecimal previous) {
    return previous.compareTo(BigDecimal.ZERO) != 0;
  }

  private static JSONObject kpi(String key, String label, Number value, String format,
      double trend, boolean hasPrevious, String icon) throws Exception {
    JSONObject obj = new JSONObject();
    obj.put("key", key);
    obj.put("label", label);
    obj.put("value", value);
    obj.put("format", format);
    obj.put("trend", trend);
    obj.put("hasPrevious", hasPrevious);
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
