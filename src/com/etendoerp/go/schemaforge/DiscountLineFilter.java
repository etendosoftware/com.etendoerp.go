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
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Shared helper that strips rows matching a hidden-value set from a {@code response.data}
 * GET list result before it reaches the UI.
 *
 * <p>Originally written to strip discount lines (dummy product {@code ETGO_DTO}) for
 * {@link OrderLineHandler} and {@link InvoiceLineHandler}; generalized (ETP-4967) so
 * {@link ProductCategoryDefaultHandler} can reuse the same envelope-extraction and
 * filter-loop logic for hiding system-flagged categories (or any other field/value set),
 * instead of duplicating it. Also used by {@link AbstractInOutLineHandler} (ETP-4844) so
 * Goods Receipt/Shipment lines get the same GET-time protection — defense-in-depth for any
 * pre-existing/Classic-created document that still carries the line, since
 * {@link InOutLineFromOrderFactory#pendingQuantityFor} already stops new ones from being
 * created. {@link #filterDataArray} is exposed separately (rather than only through
 * {@link #filterFieldFromResponse}) so that caller can filter first and keep working off the
 * returned array for further per-line enrichment.
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
      JSONArray filtered = filterArrayByField(dataArr, responseWrapper, fieldName, hiddenValues);
      return filtered == dataArr ? null : NeoResponse.ok(body);
    } catch (Exception e) {
      log.warn("Could not filter rows (field={}) from GET response: {}", fieldName,
          e.getMessage());
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
  static JSONArray filterDataArray(JSONArray dataArr, JSONObject responseWrapper)
      throws JSONException {
    return filterArrayByField(dataArr, responseWrapper, FIELD_PRODUCT,
        Set.of(TotalDiscountService.DISCOUNT_PRODUCT_ID));
  }

  /**
   * Common filter loop backing both {@link #filterFieldFromResponse} (arbitrary field/value
   * set, e.g. system-category ids) and {@link #filterDataArray} (fixed discount-product
   * filtering for in/out line handlers) — kept in exactly one place per this class's own
   * javadoc intent.
   *
   * @return {@code dataArr} unchanged when nothing was removed, or a new, filtered
   *         {@link JSONArray} (already installed into {@code responseWrapper}) otherwise
   */
  private static JSONArray filterArrayByField(JSONArray dataArr, JSONObject responseWrapper,
      String fieldName, Set<String> hiddenValues) throws JSONException {
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
      return dataArr;
    }
    responseWrapper.put("data", filtered);
    return filtered;
  }
}
