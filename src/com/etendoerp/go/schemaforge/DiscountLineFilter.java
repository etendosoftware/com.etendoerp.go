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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Shared helper that strips discount lines (dummy product {@code ETGO_DTO}) from
 * GET list responses before they reach the UI.
 *
 * <p>Used by {@link OrderLineHandler} and {@link InvoiceLineHandler} to avoid duplicating
 * the same filtering logic in each handler, and by {@link AbstractInOutLineHandler}
 * (ETP-4844) so Goods Receipt/Shipment lines get the same GET-time protection —
 * defense-in-depth for any pre-existing/Classic-created document that still carries
 * the line, since {@link InOutLineFromOrderFactory#pendingQuantityFor} already stops
 * new ones from being created.
 */
class DiscountLineFilter {

  private static final Logger log = LogManager.getLogger(DiscountLineFilter.class);

  private DiscountLineFilter() {
  }

  /**
   * Removes rows whose {@code product} field matches the discount-product ID from the
   * {@code response.data} array in the previous CRUD GET result.
   *
   * @return a new {@link NeoResponse} with the filtered body if any rows were removed,
   *         or {@code null} to leave the original response untouched.
   */
  static NeoResponse filterFromResponse(NeoContext context) {
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = prev.getBody();
      JSONObject responseWrapper = body.optJSONObject("response");
      if (responseWrapper == null) {
        return null;
      }
      JSONArray dataArr = responseWrapper.optJSONArray("data");
      if (dataArr == null || dataArr.length() == 0) {
        return null;
      }
      JSONArray filtered = filterDataArray(dataArr, responseWrapper);
      return filtered == dataArr ? null : NeoResponse.ok(body);
    } catch (Exception e) {
      log.warn("Could not filter discount lines from GET response: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Removes rows whose {@code product} field matches the discount-product ID from
   * {@code dataArr}, and — only when at least one row was actually removed — replaces
   * the {@code "data"} entry in {@code responseWrapper} with the filtered array.
   *
   * <p>Exposed separately from {@link #filterFromResponse} so callers that still need
   * to run further per-line enrichment on the (possibly filtered) rows — such as
   * {@link AbstractInOutLineHandler#afterHandle} — can filter first and keep working
   * off the returned array, instead of re-deriving it from a freshly built response.
   *
   * @param dataArr         the {@code response.data} array from a GET result
   * @param responseWrapper the enclosing {@code response} object that owns {@code dataArr};
   *                        mutated in place only when rows are actually removed
   * @return {@code dataArr} unchanged when nothing was removed, or a new, filtered
   *         {@link JSONArray} (already installed into {@code responseWrapper}) otherwise
   */
  static JSONArray filterDataArray(JSONArray dataArr, JSONObject responseWrapper) throws org.codehaus.jettison.json.JSONException {
    JSONArray filtered = new JSONArray();
    boolean removed = false;
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject row = dataArr.optJSONObject(i);
      if (row == null) {
        continue;
      }
      if (TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(row.optString("product", ""))) {
        removed = true;
      } else {
        filtered.put(row);
      }
    }
    if (!removed) {
      return dataArr;
    }
    responseWrapper.put("data", filtered);
    return filtered;
  }
}
