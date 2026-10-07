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
import org.openbravo.model.ad.access.Role;

/**
 * NeoHandler that returns the revenue/expense trend for the dashboard "Evolucion financiera"
 * widget.
 *
 * <p>ETP-5493: the series follows the dashboard period selector ({@code ?range=}). The current
 * window is {@code [from, NOW()]}, with {@code from} given by
 * {@link WidgetQueryHelper#rangeToSqlDateFrom(String)}, so the totals are consistent with
 * {@code WidgetKpisHandler}. The series is anchored to NOW() (no longer to the most recent
 * invoice date) and bucketed with a granularity hardcoded per range:</p>
 *
 * <table>
 *   <caption>Range to bucket granularity</caption>
 *   <tr><th>range</th><th>granularity</th></tr>
 *   <tr><td>last30d</td><td>day (30 days, today included)</td></tr>
 *   <tr><td>mtd</td><td>day (1st of the month to today)</td></tr>
 *   <tr><td>last90d</td><td>week (ISO, Monday start; 13-14 buckets, first one partial)</td></tr>
 *   <tr><td>ytd</td><td>month (January to the current month)</td></tr>
 *   <tr><td>lastYear</td><td>month (13 buckets, first one partial)</td></tr>
 *   <tr><td>missing/blank/unknown</td><td>same as lastYear (rolling 12 months, by month)</td></tr>
 * </table>
 *
 * <p>The first and last buckets may be partial: only invoices with {@code dateinvoiced} in
 * {@code [from, NOW()]} are summed. Buckets without invoices are emitted as 0
 * ({@code generate_series}). The bucket unit is chosen from {@link #granularityFor(String)}
 * and never taken from user input; {@code range} only flows through the
 * {@link WidgetQueryHelper} switch.</p>
 *
 * <p>Payload ({@code data[0]}): {@code labels}, {@code values} (revenue) and
 * {@code expenseValues} (unchanged, backward compatible) plus the additive fields
 * {@code dates} (ISO {@code yyyy-MM-dd} start of every bucket), {@code granularity}
 * ({@code day|week|month}), {@code revenueTotal}, {@code previousRevenueTotal} (revenue of the
 * comparison window {@code [prevFrom, prevTo)}), {@code growthPct} (same formula as the KPIs
 * trend) and {@code hasPrevious} ({@code false} when the previous revenue is zero, in which
 * case {@code growthPct} is 0 and meaningless). {@code labels} are the month abbreviation for
 * month buckets and the ISO date for day/week buckets.</p>
 *
 * <p>ETP-5011 (Inconsistency 2): uses {@code c_invoice.totallines} (tax-exclusive
 * subtotal, "base imponible") rather than {@code grandtotal}, so this widget's
 * totals stay consistent with the net figures in {@code WidgetKpisHandler}
 * ("Resumen Financiero") — VAT/IVA is not the company's own income or expense.</p>
 */
@Named("widgetRevenueTrendHandler")
public class WidgetRevenueTrendHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetRevenueTrendHandler.class);

  static final String GRANULARITY_DAY = "day";
  static final String GRANULARITY_WEEK = "week";
  static final String GRANULARITY_MONTH = "month";

  /** Hardcoded placeholder replaced by one of the GRANULARITY_* constants, never by user input. */
  private static final String UNIT_PLACEHOLDER = "@UNIT@";

  /**
   * Bucketed series. {@code %1$s} is the current window "from" expression from
   * {@link WidgetQueryHelper}; everything is cast to {@code timestamp} so bucket comparison does
   * not depend on the session time zone. {@code dateinvoiced} is a {@code timestamp} (invoices
   * created by GO carry a time of day), so {@link WidgetQueryHelper} day-aligns every rolling
   * "from" to the first midnight at or after it (ETP-5493): the series can start straight at
   * {@code date_trunc(unit, from)}, the first bucket is never one that cannot match (last30d gives
   * exactly 30 points) and the trend counts exactly the invoices the KPIs count. Columns: bucket
   * start (ISO date),
   * month label, revenue, expenses.
   */
  private static final String TREND_QUERY =
      "WITH buckets AS ( "
    + "  SELECT generate_series( "
    + "    CAST(date_trunc('@UNIT@', %1$s) AS timestamp), "
    + "    CAST(date_trunc('@UNIT@', NOW()) AS timestamp), "
    + "    CAST('1 @UNIT@' AS interval) "
    + "  ) AS bucket "
    + ") "
    + "SELECT to_char(b.bucket, 'YYYY-MM-DD') AS bucket_date, "
    + "       to_char(b.bucket, 'Mon') AS month_label, "
    + "       COALESCE(SUM(CASE WHEN i.issotrx = 'Y' THEN i.totallines ELSE 0 END), 0) AS revenue_total, "
    + "       COALESCE(SUM(CASE WHEN i.issotrx = 'N' THEN i.totallines ELSE 0 END), 0) AS expense_total "
    + "FROM buckets b "
    + "LEFT JOIN c_invoice i ON date_trunc('@UNIT@', i.dateinvoiced) = b.bucket "
    + "  AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId "
    + "  AND i.issotrx IN ('Y','N') "
    + "  AND i.dateinvoiced >= %1$s AND i.dateinvoiced <= NOW() "
    + "GROUP BY b.bucket "
    + "ORDER BY b.bucket";

  /**
   * Revenue of the comparison window {@code [prevFrom, prevTo)}. The COUNT(*) column only
   * guarantees an {@code Object[]} row shape (a single-column native query returns scalars).
   */
  private static final String PREVIOUS_REVENUE_QUERY =
      "SELECT COALESCE(SUM(i.totallines), 0) AS previous_revenue, COUNT(*) AS invoice_count "
    + "FROM c_invoice i "
    + "WHERE i.ad_client_id = :clientId "
    + "  AND i.issotrx = 'Y' "
    + "  AND i.docstatus IN ('CO','CL') "
    + "  AND i.dateinvoiced >= %2$s "
    + "  AND i.dateinvoiced < %3$s";

  /**
   * Maps a range to its bucket unit from a hardcoded whitelist. Anything not listed
   * (including null/blank/unknown) resolves to month, matching
   * {@link WidgetQueryHelper}'s rolling-12-months default.
   */
  static String granularityFor(String range) {
    if (range == null) {
      return GRANULARITY_MONTH;
    }
    switch (range) {
      case "last30d":
      case "mtd":
        return GRANULARITY_DAY;
      case "last90d":
        return GRANULARITY_WEEK;
      case "ytd":
      case "lastYear":
      default:
        return GRANULARITY_MONTH;
    }
  }

  /** Trimmed {@code range} query param, or {@code null} when absent/blank (rolling 12 months). */
  private static String resolveRange(NeoContext context) {
    Map<String, String> params = context.getQueryParams();
    String range = params != null ? params.get("range") : null;
    return (range == null || range.trim().isEmpty()) ? null : range.trim();
  }

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

        String range = resolveRange(context);
        String effectiveRange = range != null ? range : "lastYear";
        String granularity = granularityFor(range);
        String trendSql = TREND_QUERY.replace(UNIT_PLACEHOLDER, granularity);

        List<Object[]> rows = WidgetQueryHelper.executeRangedQuery(trendSql, clientId, effectiveRange);

        JSONArray labels = new JSONArray();
        JSONArray dates = new JSONArray();
        JSONArray values = new JSONArray();
        JSONArray expenseValues = new JSONArray();
        BigDecimal revenueSum = BigDecimal.ZERO;

        for (Object[] row : rows) {
          String date = String.valueOf(row[0]);
          BigDecimal revenueTotal = (BigDecimal) row[2];
          BigDecimal expenseTotal = (BigDecimal) row[3];
          labels.put(GRANULARITY_MONTH.equals(granularity) ? String.valueOf(row[1]).trim() : date);
          dates.put(date);
          values.put(revenueTotal.doubleValue());
          expenseValues.put(expenseTotal.doubleValue());
          revenueSum = revenueSum.add(revenueTotal);
        }

        BigDecimal previousRevenue = queryPreviousRevenue(clientId, effectiveRange);

        JSONObject trend = new JSONObject();
        trend.put("labels", labels);
        trend.put("values", values);
        trend.put("expenseValues", expenseValues);
        trend.put("dates", dates);
        trend.put("granularity", granularity);
        trend.put("revenueTotal", revenueSum.doubleValue());
        trend.put("previousRevenueTotal", previousRevenue.doubleValue());
        trend.put("growthPct", WidgetKpisHandler.calculateTrend(revenueSum, previousRevenue));
        trend.put("hasPrevious", WidgetKpisHandler.hasPrevious(previousRevenue));

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

  private static BigDecimal queryPreviousRevenue(String clientId, String range) {
    List<Object[]> rows = WidgetQueryHelper.executeRangedQuery(PREVIOUS_REVENUE_QUERY, clientId, range);
    if (rows.isEmpty() || rows.get(0) == null || rows.get(0)[0] == null) {
      return BigDecimal.ZERO;
    }
    Object value = rows.get(0)[0];
    return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
  }
}
