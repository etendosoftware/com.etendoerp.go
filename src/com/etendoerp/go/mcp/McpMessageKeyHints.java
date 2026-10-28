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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Recovery hints keyed on the AD message key that identifies a refusal (ETP-5577, ETP-5714).
 *
 * <p>The key is the identity, never the entity: a refusal of any document that raises the same
 * message gets the same advice. Shared by every path that builds an error body — the generic
 * error wrapper ({@link McpToolRouter#wrapAsErrorContent}) and the classified failure envelope
 * ({@link McpToolResponses#classifiedEnvelope}) — so a message-specific hint is never replaced by
 * the generic one for the class of failure.</p>
 */
final class McpMessageKeyHints {

  /** Recovery hint for a refusal keyed {@code PeriodNotAvailable} (ETP-5577). */
  static final String PERIOD_NOT_AVAILABLE_HINT = "The date falls in an accounting period that is "
      + "not open (a new company starts with every period never-opened). Do not open it on your "
      + "own: ask the user first. If they agree, find the period with etendo_list on spec "
      + "'open-close-period-control', entity 'periodControl', then call etendo_action on it with "
      + "action 'openClose' and openClose = 'O', and retry.";

  static final String PERIOD_NOT_AVAILABLE_KEY = "PeriodNotAvailable";

  /**
   * Core's {@code AD_MESSAGE} 20501 ("Document posted/processed", shown in Spanish as "Documento
   * ya procesado"): the document is already processed and the request needed it editable
   * (ETP-5714).
   */
  static final String DOCUMENT_PROCESSED_KEY = "20501";

  /** Recovery hint for a refusal keyed {@code 20501} (ETP-5714). */
  static final String DOCUMENT_PROCESSED_HINT = "The document is already processed (completed), "
      + "so it cannot be changed as it is. If etendo_schema(view:'actions', id:<record id>) "
      + "offers documentAction RE for it, reactivate it (etendo_action with documentAction RE), "
      + "edit it and complete it again; otherwise it cannot be edited: read that action's "
      + "unavailableReason for why.";

  private static final String KEY_MESSAGE_KEYS = "messageKeys";

  private static final Map<String, String> HINT_BY_MESSAGE_KEY = new LinkedHashMap<>();

  static {
    HINT_BY_MESSAGE_KEY.put(PERIOD_NOT_AVAILABLE_KEY, PERIOD_NOT_AVAILABLE_HINT);
    // ETP-5945 (in ETP-5852): a date with no valid period at all gets the same advice.
    HINT_BY_MESSAGE_KEY.put("PeriodNotValid", PERIOD_NOT_AVAILABLE_HINT);
    HINT_BY_MESSAGE_KEY.put(DOCUMENT_PROCESSED_KEY, DOCUMENT_PROCESSED_HINT);
  }

  private McpMessageKeyHints() {
  }

  /**
   * The hint for the first known message key in the list.
   *
   * @param messageKeys the AD message keys of a failure; may be {@code null} or empty
   * @return the hint, or {@code null} when no key has one
   */
  static String hintFor(List<String> messageKeys) {
    if (messageKeys == null) {
      return null;
    }
    return hintForRendered(new JSONArray(messageKeys).toString());
  }

  /**
   * Adds the keyed hint to a refusal body that names a known {@code messageKeys} entry — wherever
   * the key sits, top level or under a nested {@code error} — unless the body already carries a
   * hint.
   *
   * @param body the error body; {@code null} is ignored
   * @throws JSONException if the hint cannot be written
   */
  static void addTo(JSONObject body) throws JSONException {
    if (body == null || body.has(McpConstants.KEY_HINT)) {
      return;
    }
    JSONObject nested = body.optJSONObject(McpConstants.KEY_ERROR);
    JSONArray keys = body.optJSONArray(KEY_MESSAGE_KEYS);
    if (keys == null && nested != null) {
      keys = nested.optJSONArray(KEY_MESSAGE_KEYS);
    }
    if (keys == null) {
      return;
    }
    String hint = hintForRendered(keys.toString());
    if (hint != null) {
      body.put(McpConstants.KEY_HINT, hint);
    }
  }

  private static String hintForRendered(String rendered) {
    for (Map.Entry<String, String> entry : HINT_BY_MESSAGE_KEY.entrySet()) {
      if (rendered.contains('"' + entry.getKey() + '"')) {
        return entry.getValue();
      }
    }
    return null;
  }
}
