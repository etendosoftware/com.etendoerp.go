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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The date range, page and balance of {@code listMovements} (ETP-5852, M-1 / M-70), applied to the
 * full movement list the treasury endpoint answers (newest first, as the Movements tab shows it).
 *
 * <p>{@code fromDate}/{@code toDate} keep the movements whose calendar day falls in the range,
 * both inclusive; {@code limit}/{@code offset} page what is left, newest first. The answer gains
 * {@code totalRows} (movements in the range) and {@code balanceAtToDate}: the account balance at
 * the end of {@code toDate}, computed as the endpoint's own running balance is — the current
 * balance minus every movement dated after that day — so it matches the {@code balance} column.
 * Without {@code toDate} it is the current balance.</p>
 */
final class MovementWindow {

  static final String KEY_TOTAL_ROWS = "totalRows";
  static final String KEY_BALANCE_AT_TO_DATE = "balanceAtToDate";

  private static final String KEY_TRANSACTIONS = "transactions";
  private static final String KEY_DATE = "date";
  private static final String KEY_AMOUNT = "amount";
  private static final int DAY_LENGTH = 10;

  private MovementWindow() {
  }

  /**
   * @param listed the endpoint's answer, {@code {response:{data:{transactions, totals, …}}}}
   * @param params the action's parameters; may be {@code null}
   * @return the same response, its transactions narrowed and the two keys added
   */
  static NeoResponse apply(NeoResponse listed, JSONObject params) throws JSONException {
    JSONObject body = listed.getBody();
    JSONObject response = body == null ? null : body.optJSONObject("response");
    JSONObject data = response == null ? null : response.optJSONObject("data");
    JSONArray all = data == null ? null : data.optJSONArray(KEY_TRANSACTIONS);
    if (all == null) {
      return listed;
    }
    String from = param(params, FinancialAccountMovementActions.P_FROM_DATE);
    String to = param(params, FinancialAccountMovementActions.P_TO_DATE);
    int offset = intParam(params, FinancialAccountMovementActions.P_OFFSET, 0);
    int limit = intParam(params, FinancialAccountMovementActions.P_LIMIT, Integer.MAX_VALUE);

    JSONObject totals = data.optJSONObject("totals");
    BigDecimal balance = totals == null ? BigDecimal.ZERO
        : new BigDecimal(totals.optString("balance", "0"));
    JSONArray page = new JSONArray();
    int inRange = 0;
    for (int i = 0; i < all.length(); i++) {
      JSONObject row = all.getJSONObject(i);
      String day = StringUtils.left(row.optString(KEY_DATE, ""), DAY_LENGTH);
      if (to != null && day.compareTo(to) > 0) {
        balance = balance.subtract(new BigDecimal(row.optString(KEY_AMOUNT, "0")));
        continue;
      }
      if (from != null && day.compareTo(from) < 0) {
        continue;
      }
      if (inRange >= offset && page.length() < limit) {
        page.put(row);
      }
      inRange++;
    }
    data.put(KEY_TRANSACTIONS, page);
    data.put(KEY_TOTAL_ROWS, inRange);
    data.put(KEY_BALANCE_AT_TO_DATE, balance);
    return listed;
  }

  private static String param(JSONObject params, String key) {
    return params == null ? null : StringUtils.trimToNull(params.optString(key, null));
  }

  private static int intParam(JSONObject params, String key, int fallback) {
    String raw = param(params, key);
    return raw == null ? fallback : new BigDecimal(raw).intValueExact();
  }
}
