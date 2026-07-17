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

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

/** Shared query utilities for widget NeoHandlers that support ranged SQL queries. */
final class WidgetQueryHelper {

  private static final String RANGE_LAST_30D = "last30d";
  private static final String RANGE_LAST_90D = "last90d";
  private static final String RANGE_LAST_YEAR = "lastYear";
  private static final String RANGE_MTD = "mtd";
  private static final String RANGE_YTD = "ytd";

  private WidgetQueryHelper() {
  }

  /**
   * Maps a frontend range key to the "from" of the current period as a safe, hardcoded
   * PostgreSQL date expression.
   */
  static String rangeToSqlDateFrom(String range) {
    switch (range) {
      case RANGE_LAST_30D:  return "NOW() - INTERVAL '30 days'";
      case RANGE_LAST_90D:  return "NOW() - INTERVAL '90 days'";
      case RANGE_MTD:       return "date_trunc('month', NOW())";
      case RANGE_YTD:       return "date_trunc('year', NOW())";
      case RANGE_LAST_YEAR:
      default:              return "NOW() - INTERVAL '12 months'";
    }
  }

  /**
   * Maps a frontend range key to the "from" of the previous comparison period: the window
   * of the same size immediately preceding the current one. For calendar ranges (mtd/ytd)
   * it is the start of the previous month/year so the comparison covers an equivalent span.
   */
  static String rangeToSqlPrevFrom(String range) {
    switch (range) {
      case RANGE_LAST_30D:  return "NOW() - INTERVAL '60 days'";
      case RANGE_LAST_90D:  return "NOW() - INTERVAL '180 days'";
      case RANGE_MTD:       return "date_trunc('month', NOW() - INTERVAL '1 month')";
      case RANGE_YTD:       return "date_trunc('year', NOW() - INTERVAL '1 year')";
      case RANGE_LAST_YEAR:
      default:              return "NOW() - INTERVAL '24 months'";
    }
  }

  /**
   * Maps a frontend range key to the exclusive "to" of the previous comparison period.
   * For rolling ranges it equals {@link #rangeToSqlDateFrom(String)} (the current period's
   * "from"); for calendar ranges (mtd/ytd) it is the same offset shifted back one
   * month/year so the previous span matches the elapsed portion of the current one.
   */
  static String rangeToSqlPrevTo(String range) {
    switch (range) {
      case RANGE_LAST_30D:  return "NOW() - INTERVAL '30 days'";
      case RANGE_LAST_90D:  return "NOW() - INTERVAL '90 days'";
      case RANGE_MTD:       return "NOW() - INTERVAL '1 month'";
      case RANGE_YTD:       return "NOW() - INTERVAL '1 year'";
      case RANGE_LAST_YEAR:
      default:              return "NOW() - INTERVAL '12 months'";
    }
  }

  /**
   * Executes a SQL template by substituting the date expressions for {@code range} and
   * binding {@code :clientId}. The template may reference up to three indexed placeholders:
   * {@code %1$s} (current period "from"), {@code %2$s} (previous period "from") and
   * {@code %3$s} (previous period exclusive "to"). Templates that only need the current
   * period may use a single plain {@code %s}, which resolves to the first argument.
   */
  @SuppressWarnings("unchecked")
  static List<Object[]> executeRangedQuery(String sqlTemplate, String clientId, String range) {
    String sql = String.format(sqlTemplate,
        rangeToSqlDateFrom(range),
        rangeToSqlPrevFrom(range),
        rangeToSqlPrevTo(range));
    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter("clientId", clientId);
    return query.list();
  }

  /** Executes a fixed (non-ranged) SQL string, binding {@code :clientId}. */
  @SuppressWarnings("unchecked")
  static List<Object[]> executeFallbackQuery(String sql, String clientId) {
    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter("clientId", clientId);
    return query.list();
  }

  /** Dispatches to ranged or fallback query depending on whether {@code range} is set. */
  static List<Object[]> resolveQuery(String fallbackSql, String rangedSql, String clientId, String range) {
    return (range != null && !range.isEmpty())
        ? executeRangedQuery(rangedSql, clientId, range)
        : executeFallbackQuery(fallbackSql, clientId);
  }

  /** Wraps a data array into the standard {@code {"response":{"data":[...],"count":N}}} envelope. */
  static NeoResponse buildDataResponse(JSONArray data) throws JSONException {
    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    responseData.put("count", data.length());
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);
    return NeoResponse.ok(wrapper);
  }
}
