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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.erpCommon.utility.PropertyNotFoundException;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.ad.system.Client;

/**
 * Persists named filter presets per window per user via AD_PREFERENCE.
 *
 * Property key: {@code ETGO_WindowFilters}
 * Value format: JSON object — window spec name → map of preset name → filter values, e.g.:
 * <pre>
 * {
 *   "sales-order": {
 *     "Pendientes": {"status": "PE"},
 *     "Este mes":   {"dateFrom": "2026-04-01"}
 *   },
 *   "payment-in": {
 *     "Sin cobrar": {"status": "PE"}
 *   }
 * }
 * </pre>
 *
 * Exposed at:
 *   GET    /sws/neo/filters/{window}          — all named presets for that window
 *   PUT    /sws/neo/filters/{window}/{preset} — save or overwrite a named preset
 *   DELETE /sws/neo/filters/{window}/{preset} — remove a named preset
 */
class NeoFiltersService {

  private static final Logger log = LogManager.getLogger(NeoFiltersService.class);
  private static final String PREF_KEY = "ETGO_WindowFilters";

  private NeoFiltersService() {
  }

  /**
   * Returns all named presets for the given window.
   * Returns an empty object when none exist yet.
   */
  static NeoResponse getWindowPresets(String windowName) {
    JSONObject all = loadAll();
    try {
      JSONObject presets = all.has(windowName) ? all.getJSONObject(windowName) : new JSONObject();
      return NeoResponse.ok(presets);
    } catch (Exception e) {
      log.error("Error reading presets for window {}: {}", windowName, e.getMessage(), e);
      return NeoResponse.error(500, "Error reading window filter presets");
    }
  }

  /**
   * Saves or overwrites a single named preset for the given window.
   * Other windows and other presets within the same window are not affected.
   */
  static void savePreset(String windowName, String presetName, String filtersJson) {
    JSONObject all = loadAll();
    try {
      JSONObject windowPresets = all.has(windowName) ? all.getJSONObject(windowName) : new JSONObject();
      windowPresets.put(presetName, new JSONObject(filtersJson));
      all.put(windowName, windowPresets);
    } catch (Exception e) {
      log.error("Invalid filters JSON for window {}, preset {}: {}", windowName, presetName, e.getMessage(), e);
      throw new IllegalArgumentException("Invalid JSON body");
    }
    persistAll(all);
  }

  /**
   * Removes a named preset for the given window.
   * No-op if the preset or window does not exist.
   */
  static void deletePreset(String windowName, String presetName) {
    JSONObject all = loadAll();
    try {
      if (!all.has(windowName)) {
        return;
      }
      JSONObject windowPresets = all.getJSONObject(windowName);
      windowPresets.remove(presetName);
      all.put(windowName, windowPresets);
    } catch (Exception e) {
      log.error("Error removing preset {} for window {}: {}", presetName, windowName, e.getMessage(), e);
      throw new IllegalArgumentException("Error removing preset");
    }
    persistAll(all);
  }

  private static JSONObject loadAll() {
    OBContext ctx = OBContext.getOBContext();
    try {
      String raw = Preferences.getPreferenceValue(
          PREF_KEY, false,
          ctx.getCurrentClient(), ctx.getCurrentOrganization(),
          ctx.getUser(), ctx.getRole(), null);
      return new JSONObject(raw);
    } catch (PropertyNotFoundException e) {
      return new JSONObject();
    } catch (PropertyException e) {
      String userId = (ctx.getUser() != null) ? ctx.getUser().getId() : "0";
      log.warn("Could not read {} for user {}: {}", PREF_KEY, userId, e.getMessage());
      return new JSONObject();
    } catch (Exception e) {
      log.error("Error parsing stored filters: {}", e.getMessage(), e);
      return new JSONObject();
    }
  }

  private static void persistAll(JSONObject all) {
    OBContext ctx = OBContext.getOBContext();
    Client client = ctx.getCurrentClient();
    Organization org = ctx.getCurrentOrganization();
    User user = ctx.getUser();
    Preferences.setPreferenceValue(PREF_KEY, all.toString(), false, client, org, user, null, null, null);
  }
}
