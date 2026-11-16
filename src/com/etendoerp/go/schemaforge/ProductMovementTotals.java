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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Whole-set "Total del periodo" of the Product Movements tab: the net signed quantity and the
 * number of ALL the transactions matching the list's filters (period, warehouse, direction), not
 * only the loaded page.
 *
 * <p>Opt-in: only a list GET carrying {@value #PARAM_TOTALS}{@code =true} pays for it (the SPA sends
 * it on page 0). The result is {@code response.totals = {netQuantity, count}}, added next to
 * {@code response.data}. It is one grouped HQL query through {@link NeoListAggregateQuery}, so it
 * applies exactly the criteria of the list; MCP lists never remember a query and carry no totals.
 * The quantity is {@code movementQuantity} as stored (returns and consumptions already carry their
 * sign), which is what the tab displays.
 */
final class ProductMovementTotals {

  private static final Logger log = LogManager.getLogger(ProductMovementTotals.class);

  /** Request parameter that opts in; the value must be {@code true}. */
  static final String PARAM_TOTALS = "etgoTotals";

  static final String KEY_RESPONSE = "response";
  static final String KEY_TOTALS = "totals";
  static final String KEY_NET_QUANTITY = "netQuantity";
  static final String KEY_COUNT = "count";

  /** Server-side constants (alias {@code e}); one group per client, so one row in practice. */
  private static final String SELECT = "sum(e.movementQuantity), count(e.id)";
  private static final String GROUP_BY = "e.client.id";

  private ProductMovementTotals() {
  }

  /**
   * Adds {@code response.totals} to a list GET body. Never throws.
   *
   * <p>Invariant: no DB access may follow this call. A failed aggregate (statement timeout) leaves
   * the PG transaction aborted; the only thing that follows on the REST list path is the request
   * filter's commit, which an aborted read-only transaction survives (see neo-headless.md 5.3.4.b).
   *
   * @param context the request context
   * @param body the list response body
   * @return whether the totals were added; false when not requested, not a list GET, no aggregate
   *     query for this request (MCP), or anything failed
   */
  static boolean injectInto(NeoContext context, JSONObject body) {
    if (!isRequested(context) || body == null) {
      return false;
    }
    try {
      JSONObject wrapper = body.optJSONObject(KEY_RESPONSE);
      NeoListAggregateQuery query = NeoListAggregateQuery.of(context);
      if (wrapper == null || query == null) {
        return false;
      }
      wrapper.put(KEY_TOTALS, fold(query.groupBy(SELECT, GROUP_BY)));
      return true;
    } catch (Exception e) {
      log.warn("Could not build the product movements totals: {}", e.getMessage());
      return false;
    }
  }

  private static boolean isRequested(NeoContext context) {
    Map<String, String> params = context.getQueryParams();
    return "GET".equals(context.getHttpMethod()) && context.getRecordId() == null
        && params != null && "true".equals(params.get(PARAM_TOTALS));
  }

  /** Folds the grouped rows (none for an empty set) into {@code {netQuantity, count}}; null-safe. */
  static JSONObject fold(List<Object[]> rows) throws JSONException {
    BigDecimal net = BigDecimal.ZERO;
    long count = 0;
    for (Object[] row : rows) {
      if (row[0] != null) {
        net = net.add(new BigDecimal(row[0].toString()));
      }
      count += row[1] == null ? 0 : ((Number) row[1]).longValue();
    }
    return new JSONObject().put(KEY_NET_QUANTITY, net).put(KEY_COUNT, count);
  }
}
