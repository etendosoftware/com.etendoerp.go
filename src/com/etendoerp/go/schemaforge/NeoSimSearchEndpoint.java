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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.copilot.toolpack.webhooks.SimSearch;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoTrl;
import com.smf.securewebservices.utils.WSResult;

/**
 * Global "simsearch" pseudo-spec collaborator for {@link NeoServlet}, mapped as
 * {@code GET /sws/neo/simsearch} the same way the {@code batch} pseudo-spec bypasses
 * spec/entity resolution.
 *
 * <p>Reuses {@link SimSearch}'s trigram similarity-search logic directly, but reached
 * through NEO Headless's own JWT authentication instead of the Webhooks module. The
 * Webhooks path additionally requires a per-(webhook, role) grant row in
 * {@code SMFWHE_DEFINEDWEBHOOK_ROLE} — a separate authorization layer on top of normal
 * entity security that every calling role has to be provisioned for by hand. Any role
 * with a valid NEO bearer token can already reach this endpoint; entity-level security
 * is still enforced inside {@link SimSearch#handleSimSearch} via
 * {@code OBContext.getEntityAccessChecker()}, so no security check is actually removed.</p>
 */
class NeoSimSearchEndpoint {

  private static final Logger log = LogManager.getLogger(NeoSimSearchEndpoint.class);

  private static final String PARAM_ENTITY_NAME = "entityName";
  private static final String PARAM_ITEMS = "items";
  private static final String PARAM_QTY_RESULTS = "qtyResults";
  private static final String PARAM_MIN_SIM_PERCENT = "minSimPercent";
  private static final String DEFAULT_QTY_RESULTS = "1";
  private static final String DEFAULT_MIN_SIM_PERCENT = String.valueOf(SimSearch.MIN_SIM_PERCENT);
  private static final String ITEM_LABEL_PREFIX = "item_";

  /**
   * Handles {@code GET /sws/neo/simsearch?entityName=...&items=[...]}. Mirrors the
   * request contract of the "SimSearch" webhook so existing callers only need their
   * base URL changed, not their query params.
   */
  NeoResponse handle(HttpServletRequest request) {
    String entityName = request.getParameter(PARAM_ENTITY_NAME);
    String itemsJson = request.getParameter(PARAM_ITEMS);
    if (StringUtils.isEmpty(entityName) || StringUtils.isEmpty(itemsJson)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Missing required parameter: entityName and items are both required");
    }

    int qtyResults;
    try {
      qtyResults = Integer.parseInt(coalesce(request.getParameter(PARAM_QTY_RESULTS), DEFAULT_QTY_RESULTS));
    } catch (NumberFormatException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "qtyResults must be an integer");
    }
    String minSimPercent = coalesce(request.getParameter(PARAM_MIN_SIM_PERCENT), DEFAULT_MIN_SIM_PERCENT);

    try {
      JSONArray itemsArray = new JSONArray(itemsJson);
      JSONObject results = new JSONObject();
      for (int i = 0; i < itemsArray.length(); i++) {
        String searchTerm = itemsArray.getString(i).replace("'", "");
        if (StringUtils.isBlank(searchTerm)) {
          continue;
        }
        String baseTerm = toBaseLanguageTerm(entityName, searchTerm);
        WSResult result = SimSearch.handleSimSearch(baseTerm, entityName, qtyResults, minSimPercent);
        results.put(ITEM_LABEL_PREFIX + i, result.getJSONResponse());
      }
      return NeoResponse.ok(results);
    } catch (JSONException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid items JSON array: " + e.getMessage());
    } catch (ClassNotFoundException e) {
      return NeoResponse.error(422, "Entity not found: " + entityName);
    } catch (Exception e) {
      log.error("Error processing simsearch request", e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Rewrite a term the user typed in the session language into the base-language name
   * {@link SimSearch} can actually match.
   *
   * <p>{@code SimSearch} compares trigrams against the <em>base</em> row only — translated text
   * lives in a sibling {@code *_Trl} table it never reads. So a Spanish session searching
   * {@code "España"} scores 0.083 against {@code "Spain"} and resolves nothing, which is exactly
   * what made translated country and unit-of-measure cells fail on CSV import. Translating here,
   * at our own boundary, keeps {@code SimSearch} as the matcher and needs no per-language code:
   * {@link NeoTrl} discovers the {@code *_Trl} sibling by convention, so every translatable
   * entity and every loaded language is covered by the same call.
   *
   * <p>Falls through to the original term whenever the rewrite is not unambiguous — a term with
   * no translation, an entity with no {@code *_Trl} sibling, or a translation shared by several
   * base rows. Those requests behave exactly as they did before this method existed.
   */
  private static String toBaseLanguageTerm(String entityName, String searchTerm) {
    String baseTerm = NeoTrl.baseNameForTranslation(entityName, searchTerm, NeoLanguage.currentCode());
    if (baseTerm == null) {
      return searchTerm;
    }
    log.debug("simsearch: translated '{}' to base-language '{}' for entity '{}'", searchTerm,
        baseTerm, entityName);
    return baseTerm;
  }

  private static String coalesce(String value, String fallback) {
    return StringUtils.isEmpty(value) || "null".equalsIgnoreCase(value) ? fallback : value;
  }
}
