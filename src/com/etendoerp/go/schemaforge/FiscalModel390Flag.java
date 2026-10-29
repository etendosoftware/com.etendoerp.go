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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * ETP-5716 — the one gate of the {@value GoFeatureFlags#FLAG_FISCAL_MODEL_390} flag. Retiring the
 * flag is a grep for this class.
 *
 * <p>Only {@link NeoFiscalModelsCatalogService} calls it, at two toggle points:
 * <ul>
 *   <li><b>Read</b> ({@link #applyToRead}): flag off, the catalog answer drops model
 *       {@value #MODEL_ID} — so every surface that only shows an <em>active</em> model hides it —
 *       and lists it under {@value #UNAVAILABLE_KEY}, so the catalog drawer does not offer the
 *       toggle either. Flag on, the answer is exactly the stored catalog.</li>
 *   <li><b>Write</b> ({@link #applyToWrite}): the {@value #UNAVAILABLE_KEY} marker is never
 *       persisted, and with the flag off the client's value for {@value #MODEL_ID} is ignored and
 *       the value stored before is kept — a save can neither switch 390 on nor erase a tenant's
 *       choice, so turning the flag back on restores it.</li>
 * </ul>
 *
 * <p><b>Hidden is the default.</b> {@link GoFeatureFlags#isEnabled} answers {@code false} for an
 * unconfigured flag, an unreachable control plane and any evaluation error.
 *
 * <p>A visual gate only: the {@code /fiscal390/*} and legal-representative endpoints are not
 * refused here (see {@link GoFeatureFlags#FLAG_FISCAL_MODEL_390}).
 */
final class FiscalModel390Flag {

  /** The catalog id of the gated model. */
  static final String MODEL_ID = "390";

  /**
   * Metadata key of the catalog answer listing the models the tenant cannot use. Never a model
   * id (ids are numeric), present only when non-empty, and never persisted.
   */
  static final String UNAVAILABLE_KEY = "_unavailable";

  private FiscalModel390Flag() {
  }

  /**
   * Whether Modelo 390 is available to a tenant.
   *
   * @param clientId the tenant, published as {@link FeatureFlagContext#ATTRIBUTE_CLIENT_ID} so a
   *     hosted targeting rule can name it; may be null
   */
  static boolean isEnabled(String clientId) {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_FISCAL_MODEL_390,
        FeatureFlagContext.forAccount(null).with(FeatureFlagContext.ATTRIBUTE_CLIENT_ID, clientId));
  }

  /**
   * Builds the catalog answer from the stored catalog. Never modifies {@code stored}.
   *
   * @param stored the persisted catalog ({@code {}} when none)
   * @param enabled the flag's value for the current tenant
   */
  static JSONObject applyToRead(JSONObject stored, boolean enabled) throws JSONException {
    JSONObject answer = new JSONObject(stored.toString());
    answer.remove(UNAVAILABLE_KEY);
    if (!enabled) {
      answer.remove(MODEL_ID);
      answer.put(UNAVAILABLE_KEY, new JSONArray().put(MODEL_ID));
    }
    return answer;
  }

  /**
   * Builds the catalog to persist from a client's save. Never modifies its arguments.
   *
   * @param incoming the catalog the client sent
   * @param stored the catalog persisted before this save ({@code {}} when none)
   * @param enabled the flag's value for the current tenant
   */
  static JSONObject applyToWrite(JSONObject incoming, JSONObject stored, boolean enabled)
      throws JSONException {
    JSONObject toSave = new JSONObject(incoming.toString());
    toSave.remove(UNAVAILABLE_KEY);
    if (!enabled) {
      toSave.remove(MODEL_ID);
      if (stored.has(MODEL_ID)) {
        toSave.put(MODEL_ID, stored.get(MODEL_ID));
      }
    }
    return toSave;
  }
}
