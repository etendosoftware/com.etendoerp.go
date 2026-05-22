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
 * the same filtering logic in each handler.
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
        return null;
      }
      responseWrapper.put("data", filtered);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.warn("Could not filter discount lines from GET response: {}", e.getMessage());
      return null;
    }
  }
}
