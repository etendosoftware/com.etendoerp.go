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

  private WidgetQueryHelper() {
  }

  /** Maps a frontend range key to a safe, hardcoded PostgreSQL date expression. */
  static String rangeToSqlDateFrom(String range) {
    switch (range) {
      case "last30d":  return "NOW() - INTERVAL '30 days'";
      case "last90d":  return "NOW() - INTERVAL '90 days'";
      case "mtd":      return "date_trunc('month', NOW())";
      case "ytd":      return "date_trunc('year', NOW())";
      case "lastYear":
      default:         return "NOW() - INTERVAL '12 months'";
    }
  }

  /**
   * Executes a SQL template by substituting the date expression for {@code range} and
   * binding {@code :clientId}. The {@code sqlTemplate} must contain a single {@code %s}
   * placeholder for the date expression.
   */
  @SuppressWarnings("unchecked")
  static List<Object[]> executeRangedQuery(String sqlTemplate, String clientId, String range) {
    String sql = String.format(sqlTemplate, rangeToSqlDateFrom(range));
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
