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

package com.etendoerp.go.schemaforge.util;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Rewrites selector item labels so that any selector serving a foreign key to {@code M_Locator}
 * displays the parent warehouse name instead of the raw storage-bin identifier.
 *
 * <p>This is the generic, all-windows replacement for the former per-window
 * {@code InternalConsumptionLineHandler} label rewrite. It runs at the end of the generic
 * selector pipeline, so every locator selector across all windows behaves consistently.</p>
 *
 * <p>Fail-safe: any error leaves the selector response untouched.</p>
 */
public class NeoLocatorSelectorHelper {

  private static final Logger log = LogManager.getLogger(NeoLocatorSelectorHelper.class);

  private static final String FIELD_ITEMS = "items";
  private static final String FIELD_ID = "id";
  private static final String FIELD_LABEL = "label";

  private NeoLocatorSelectorHelper() {
  }

  /**
   * Rewrites the {@code label} of every selector item with its parent warehouse name when the
   * selector's column is a locator FK. Returns the response unchanged otherwise.
   *
   * <p>The response object is mutated in place and returned for call-site convenience.</p>
   *
   * @param response the selector response produced by the generic selector pipeline
   * @param column   the AD column this selector is serving
   * @return the same {@code response} (mutated when applicable)
   */
  public static NeoResponse rewriteLocatorLabels(NeoResponse response, Column column) {
    try {
      if (response == null || response.getBody() == null || column == null) {
        return response;
      }
      if (!LocatorWarehouseResolver.isLocatorRef(column)) {
        return response;
      }
      JSONObject body = response.getBody();
      JSONArray items = body.optJSONArray(FIELD_ITEMS);
      if (items == null || items.length() == 0) {
        return response;
      }
      Set<String> ids = collectIds(items);
      if (ids.isEmpty()) {
        return response;
      }
      Map<String, String> warehouseNames = LocatorWarehouseResolver.resolveNames(ids);
      if (warehouseNames.isEmpty()) {
        return response;
      }
      applyLabels(items, warehouseNames);
      return response;
    } catch (Exception e) {
      log.debug("Error rewriting locator selector labels: {}", e.getMessage());
      return response;
    }
  }

  private static Set<String> collectIds(JSONArray items) {
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item != null) {
        String id = item.optString(FIELD_ID, null);
        if (id != null && !id.isEmpty()) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  private static void applyLabels(JSONArray items, Map<String, String> warehouseNames)
      throws JSONException {
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item == null) {
        continue;
      }
      String warehouseName = warehouseNames.get(item.optString(FIELD_ID, null));
      if (warehouseName != null) {
        item.put(FIELD_LABEL, warehouseName);
      }
    }
  }
}
