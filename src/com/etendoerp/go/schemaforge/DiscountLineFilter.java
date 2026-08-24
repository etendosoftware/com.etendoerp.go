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

import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Shared helper that strips rows matching a hidden-value set from a {@code response.data}
 * GET list result before it reaches the UI.
 *
 * <p>Originally written to strip discount lines (dummy product {@code ETGO_DTO}) for
 * {@link OrderLineHandler} and {@link InvoiceLineHandler}; generalized (ETP-4967) so
 * {@link ProductCategoryDefaultHandler} can reuse the same envelope-extraction and
 * filter-loop logic for hiding system-flagged categories, instead of duplicating it.
 */
class DiscountLineFilter {

  private static final Logger log = LogManager.getLogger(DiscountLineFilter.class);
  private static final String FIELD_PRODUCT = "product";

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
    return filterFieldFromResponse(context, FIELD_PRODUCT,
        Set.of(TotalDiscountService.DISCOUNT_PRODUCT_ID));
  }

  /**
   * Removes rows whose {@code fieldName} value is contained in {@code hiddenValues} from the
   * {@code response.data} array in the previous CRUD GET result (list and single-record alike
   * — the envelope has the same shape either way).
   *
   * @param context the current request; only {@link NeoContext#getPreviousResult()} is used
   * @param fieldName the row field to match against {@code hiddenValues}
   * @param hiddenValues values of {@code fieldName} whose row must be stripped; an empty set
   *        is a no-op (returns {@code null} without inspecting the response)
   * @return a new {@link NeoResponse} with the filtered body if any rows were removed,
   *         or {@code null} to leave the original response untouched
   */
  static NeoResponse filterFieldFromResponse(NeoContext context, String fieldName,
      Set<String> hiddenValues) {
    if (hiddenValues.isEmpty()) {
      return null;
    }
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = prev.getBody();
      JSONObject responseWrapper = body.optJSONObject("response");
      JSONArray dataArr = responseWrapper != null ? responseWrapper.optJSONArray("data") : null;
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
        if (hiddenValues.contains(row.optString(fieldName, ""))) {
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
      log.warn("Could not filter rows (field={}) from GET response: {}", fieldName,
          e.getMessage());
      return null;
    }
  }
}
