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
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.erpCommon.utility.PropertyNotFoundException;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.common.BoundedJsonParser;
import com.etendoerp.go.common.MalformedJsonException;

/**
 * Persists which fiscal declaration models (303, 349, ...) are active in the
 * Fiscal Models catalog, scoped per <b>Client</b> (tenant) via AD_PREFERENCE —
 * NOT per organization, NOT per user. Every user of the same client sees and
 * shares the same catalog state.
 *
 * <p>Property key: {@code ETGO_FiscalModelsCatalog}
 * <p>Value format: JSON object mapping model id → active flag, e.g.
 * {@code {"303": true, "349": false}}.
 *
 * <p>Exposed at:
 *   GET /sws/neo/fiscal-models-catalog — returns the current client's active-models JSON object
 *   PUT /sws/neo/fiscal-models-catalog — replaces the current client's active-models JSON object
 *
 * <p>When nothing has been saved yet for the client, GET returns an empty JSON object
 * ({@code {}}); the frontend treats any model absent from the response as inactive,
 * giving an "all models inactive" default with no server-side special-casing.
 *
 * <p>ETP-5716: while the {@code fiscal-model-390} flag is off for the tenant, GET also carries
 * {@code "_unavailable": ["390"]} (and never {@code "390"}), and PUT keeps the stored 390 value —
 * see {@link FiscalModel390Flag}.
 */
class NeoFiscalModelsCatalogService {

  private static final Logger log = LogManager.getLogger(NeoFiscalModelsCatalogService.class);
  private static final String PREF_KEY = "ETGO_FiscalModelsCatalog";

  private NeoFiscalModelsCatalogService() {
  }

  /**
   * Returns the active-models JSON object for the current OBContext client.
   * Returns an empty JSON object when no preference has been stored yet for this client.
   *
   * <p>ETP-5716 toggle point ({@code FiscalModel390Flag}): with the {@code fiscal-model-390}
   * flag off, model 390 is dropped from the answer and listed under {@code _unavailable}.
   */
  static NeoResponse getActiveModels() {
    Client client = OBContext.getOBContext().getCurrentClient();
    JSONObject stored = readStoredModels(client);
    try {
      return NeoResponse.ok(FiscalModel390Flag.applyToRead(stored,
          FiscalModel390Flag.isEnabled(client != null ? client.getId() : null)));
    } catch (Exception e) {
      log.error("Error building fiscal models catalog answer: {}", e.getMessage(), e);
      return NeoResponse.ok(new JSONObject());
    }
  }

  /**
   * Reads the stored catalog of {@code client}; {@code {}} when nothing was saved yet or the
   * stored value cannot be read or parsed.
   */
  private static JSONObject readStoredModels(Client client) {
    try {
      String raw = Preferences.getPreferenceValue(
          PREF_KEY, false,
          client, null, null, null, null);
      return new JSONObject(raw);
    } catch (PropertyNotFoundException e) {
      return new JSONObject();
    } catch (PropertyException e) {
      log.warn("Could not read {} for client {}: {}", PREF_KEY, clientIdOf(client), e.getMessage());
      return new JSONObject();
    } catch (Exception e) {
      log.error("Error parsing stored fiscal models catalog: {}", e.getMessage(), e);
      return new JSONObject();
    }
  }

  private static String clientIdOf(Client client) {
    return (client != null) ? client.getId() : "0";
  }

  /**
   * Upserts the active-models preference for the current OBContext client.
   *
   * @param json JSON object string (e.g. {@code {"303": true, "349": false}})
   * @throws IllegalArgumentException if {@code json} is not valid JSON
   */
  static void saveActiveModels(String json) {
    JSONObject parsed;
    try {
      parsed = BoundedJsonParser.parseObject(json);
    } catch (MalformedJsonException e) {
      // The client's body: a 400, not a server fault worth a stack trace.
      log.warn("Invalid fiscal models catalog JSON: {}", e.getMessage());
      throw new IllegalArgumentException("Invalid JSON body");
    }
    OBContext ctx = OBContext.getOBContext();
    Client client = ctx.getCurrentClient();
    // ETP-5716 toggle point (FiscalModel390Flag): the `_unavailable` marker is never stored, and
    // with the fiscal-model-390 flag off the stored value of model 390 is kept as it was.
    JSONObject toSave;
    try {
      toSave = FiscalModel390Flag.applyToWrite(parsed, readStoredModels(client),
          FiscalModel390Flag.isEnabled(client != null ? client.getId() : null));
    } catch (JSONException e) {
      log.warn("Invalid fiscal models catalog JSON: {}", e.getMessage());
      throw new IllegalArgumentException("Invalid JSON body");
    }
    Preferences.setPreferenceValue(PREF_KEY, toSave.toString(), false, client, null, null, null, null, null);
  }
}
