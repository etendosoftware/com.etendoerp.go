/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;

/**
 * How an {@code ETGO_Fiscal_Decl_Incident} row's message and severity are encoded and read back
 * (ETP-5716 structured messages). Extracted from {@link FiscalDeclCrudHandler} to keep it under
 * the SonarQube {@code java:S1448} method-count threshold.
 */
final class FiscalDeclIncidentMessages {

  /** Key of the human-readable text inside a {@link #structuredMessage}. */
  static final String STRUCTURED_TEXT_KEY = "text";
  static final String PARAMS_KEY = "params";
  static final String ACTION_KEY = "action";

  private FiscalDeclIncidentMessages() {
  }

  /**
   * The {@code MESSAGE} of an incident a model computes itself (ETP-5716, Modelo 390 rules):
   * {@code {"text": <fallback text>, "params": {...}, "action": {...}}} as JSON, so the reader can
   * translate the incident by its code with its parameters and offer the action — without a new
   * column. {@code params}/{@code action} are omitted when {@code null}.
   *
   * @param text   the fallback (English) text
   * @param params the message parameters, or {@code null}
   * @param action the action the reader may offer, or {@code null}
   * @return the JSON text to store as the incident's message
   * @throws JSONException never in practice (string keys only)
   */
  static String structuredMessage(String text, Map<String, ?> params,
      Map<String, ?> action) throws JSONException {
    JSONObject o = new JSONObject();
    o.put(STRUCTURED_TEXT_KEY, StringUtils.defaultString(text));
    if (params != null && !params.isEmpty()) {
      o.put(PARAMS_KEY, new JSONObject(params));
    }
    if (action != null && !action.isEmpty()) {
      o.put(ACTION_KEY, new JSONObject(action));
    }
    return o.toString();
  }

  /**
   * Puts {@code message} (and, for a {@link #structuredMessage}, {@code params} / {@code action})
   * into {@code out}. Anything that is not a JSON object with a string {@code text} is a plain
   * message.
   *
   * @param out    the incident JSON being built
   * @param stored the persisted message
   * @throws JSONException never in practice (string keys only)
   */
  static void putMessageFields(JSONObject out, String stored) throws JSONException {
    String trimmed = StringUtils.trimToEmpty(stored);
    if (trimmed.startsWith("{")) {
      try {
        JSONObject parsed = new JSONObject(trimmed);
        Object text = parsed.opt(STRUCTURED_TEXT_KEY);
        if (text instanceof String) {
          out.put(FiscalDeclCrudHandler.MESSAGE_KEY, text);
          if (parsed.optJSONObject(PARAMS_KEY) != null) {
            out.put(PARAMS_KEY, parsed.getJSONObject(PARAMS_KEY));
          }
          if (parsed.optJSONObject(ACTION_KEY) != null) {
            out.put(ACTION_KEY, parsed.getJSONObject(ACTION_KEY));
          }
          return;
        }
      } catch (JSONException e) {
        // not structured: a plain message that happens to start with a brace
      }
    }
    out.put(FiscalDeclCrudHandler.MESSAGE_KEY, stored);
  }

  /**
   * Reads the persisted {@code severity} value, defaulting to
   * {@link FiscalDeclCrudHandler#SEVERITY_BLOCK} for any row that predates this column
   * (blank/null) — preserves the pre-ETP-4456 behavior for old rows rather than surfacing an
   * empty string to the frontend.
   */
  static String resolveSeverity(BaseOBObject inc) {
    String severity = FiscalDeclCrudHandler.asString(
        inc.get(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY));
    return StringUtils.isNotBlank(severity) ? severity : FiscalDeclCrudHandler.SEVERITY_BLOCK;
  }
}
