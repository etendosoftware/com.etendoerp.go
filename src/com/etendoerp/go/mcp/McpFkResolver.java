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

package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;

/**
 * Resolves FK-by-name values in a {@code neo_create}/{@code neo_update} body (IMP-4).
 * <p>
 * Historically every foreign-key field required the exact 32-char record id, forcing an agent to
 * call {@code neo_selectors} first even for an obvious single-match lookup (e.g.
 * {@code businessPartner: "Acme Corp"}). This resolves such human search strings server-side via
 * the same {@link NeoSelectorService#querySelectorByColumn} path {@code neo_selectors} uses,
 * leaving an already-valid id untouched.
 * <p>
 * <b>Known limitation:</b> the selector context passed here is built from {@code adTab} alone
 * (window sales/purchase context, business-partner role) — {@link McpSelectorContextHelper}'s
 * {@code recordContext}/{@code parentContext} synthesis from the in-flight body is NOT attempted,
 * because that would require resolving fields in dependency order (e.g. {@code priceList} needs
 * {@code businessPartner} already resolved) — a second, more invasive pass. A dependent FK (e.g.
 * {@code partnerAddress} depending on {@code businessPartner}) may therefore return more matches
 * than a context-aware {@code neo_selectors} call would, including a false ambiguous_fk. Callers
 * that hit this should fall back to resolving the dependent field explicitly via
 * {@code neo_selectors} with {@code recordContext}.
 */
final class McpFkResolver {

  private McpFkResolver() {
  }

  private static final Pattern ID_PATTERN = Pattern.compile("[0-9A-Fa-f]{32}");
  private static final String KEY_ITEMS = "items";
  private static final String KEY_ID = "id";
  private static final String KEY_CANDIDATES = "candidates";
  private static final String KEY_FIELD = "field";
  private static final int SELECTOR_LIMIT = 10;

  /** @return {@code true} when {@code value} already looks like a 32-char hex Etendo id. */
  static boolean looksLikeId(String value) {
    return value != null && ID_PATTERN.matcher(value).matches();
  }

  /** The three outcomes of resolving a search string against a selector's match count. */
  enum Outcome {
    NOT_FOUND, RESOLVED, AMBIGUOUS
  }

  /** Pure decision: how many selector matches map to which outcome (DAL-free, unit-testable). */
  static Outcome decideOutcome(int matchCount) {
    if (matchCount == 0) {
      return Outcome.NOT_FOUND;
    }
    return matchCount == 1 ? Outcome.RESOLVED : Outcome.AMBIGUOUS;
  }

  /**
   * Resolves every FK-by-name value in {@code body}, replacing it in place with the matched
   * record id. Values that are not FK fields, not strings, empty, or already an id are left
   * untouched.
   *
   * @param body          the create/update body, keyed by canonical DAL property name (already
   *                      passed through {@code mapFieldsToDalProperties})
   * @param dalEntity     the DAL entity backing the tab's table
   * @param adTab         the tab, used to resolve each FK's {@code AD_Column}
   * @param contextParams selector context params (see the class-level limitation note)
   * @param log           caller's logger, for warn/debug tracing
   * @return {@code null} on success (body updated in place, possibly unchanged), or a structured
   *         {@code not_found}/{@code ambiguous_fk} error object the caller must return as-is
   */
  static JSONObject resolveFkNames(JSONObject body, Entity dalEntity, Tab adTab,
      Map<String, String> contextParams, Logger log) throws JSONException {
    if (body == null || dalEntity == null) {
      return null;
    }
    List<String> keys = new ArrayList<>();
    Iterator<String> it = body.keys();
    while (it.hasNext()) {
      keys.add(it.next());
    }

    for (String key : keys) {
      JSONObject error = resolveOneField(body, dalEntity, adTab, contextParams, log, key);
      if (error != null) {
        return error;
      }
    }
    return null;
  }

  private static JSONObject resolveOneField(JSONObject body, Entity dalEntity, Tab adTab,
      Map<String, String> contextParams, Logger log, String key) throws JSONException {
    Property prop = dalEntity.getProperty(key, false);
    if (prop == null || prop.isPrimitive() || prop.getTargetEntity() == null) {
      return null;
    }
    Object rawValue = body.opt(key);
    if (!(rawValue instanceof String)) {
      return null;
    }
    String search = (String) rawValue;
    if (search.isEmpty() || looksLikeId(search)) {
      return null;
    }

    Column column = McpSchemaFieldBuilder.findColumn(adTab, key, dalEntity);
    if (column == null) {
      log.debug("FK-by-name: no AD_Column resolved for '{}', leaving value as-is", key);
      return null;
    }

    NeoResponse selectorResponse = NeoSelectorService.querySelectorByColumn(
        column, key, search, SELECTOR_LIMIT, 0, contextParams);
    if (selectorResponse.getHttpStatus() >= 400 || selectorResponse.getBody() == null) {
      log.warn("FK-by-name: selector lookup failed for '{}'='{}' (status {}), leaving value as-is",
          key, search, selectorResponse.getHttpStatus());
      return null;
    }

    JSONArray items = selectorResponse.getBody().optJSONArray(KEY_ITEMS);
    int matchCount = items == null ? 0 : items.length();
    switch (decideOutcome(matchCount)) {
      case NOT_FOUND:
        return buildNotFoundError(key, search);
      case AMBIGUOUS:
        return buildAmbiguousError(key, search, items);
      case RESOLVED:
      default:
        body.put(key, items.getJSONObject(0).optString(KEY_ID));
        return null;
    }
  }

  private static JSONObject buildNotFoundError(String field, String search) throws JSONException {
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    error.put(McpConstants.KEY_ERROR, McpConstants.ERROR_NOT_FOUND);
    error.put(McpConstants.KEY_DETAIL,
        "No match for '" + field + "'='" + search + "'. Use neo_selectors to search, or pass "
            + "the exact record id instead.");
    error.put(KEY_FIELD, field);
    return error;
  }

  private static JSONObject buildAmbiguousError(String field, String search, JSONArray items)
      throws JSONException {
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    error.put(McpConstants.KEY_ERROR, McpConstants.ERROR_AMBIGUOUS_FK);
    error.put(McpConstants.KEY_DETAIL,
        "'" + field + "'='" + search + "' matched " + items.length() + " records. Pick one of "
            + "the candidates' ids, or narrow the search text.");
    error.put(KEY_FIELD, field);
    error.put(KEY_CANDIDATES, items);
    return error;
  }
}
