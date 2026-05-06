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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Shared utilities for product-related NEO handlers.
 */
final class ProductHandlerUtils {

  private ProductHandlerUtils() {
  }

  /**
   * Build a standard NEO list response wrapping a data array.
   */
  static NeoResponse buildListResponse(JSONArray data) {
    try {
      JSONArray safeData = (data != null) ? data : new JSONArray();
      int size = safeData.length();
      JSONObject inner = new JSONObject();
      inner.put("data",      safeData);
      inner.put("startRow",  0);
      inner.put("endRow",    size);
      inner.put("totalRows", size);
      inner.put("status",    0);
      JSONObject body = new JSONObject();
      body.put("response", inner);
      return NeoResponse.ok(body);
    } catch (JSONException e) {
      return NeoResponse.error(500, "Error building list response");
    }
  }

  /**
   * Safely convert a raw SQL result value to {@link BigDecimal}.
   * Returns {@link BigDecimal#ZERO} for null or unparseable values.
   */
  static BigDecimal toBigDecimal(Object val) {
    if (val == null) {
      return BigDecimal.ZERO;
    }
    if (val instanceof BigDecimal) {
      return (BigDecimal) val;
    }
    try {
      return new BigDecimal(val.toString());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }
}
