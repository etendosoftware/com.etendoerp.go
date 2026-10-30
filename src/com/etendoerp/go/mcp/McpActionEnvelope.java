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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * One envelope for every {@code etendo_action} result (ETP-5945, resolved in ETP-5852).
 *
 * <p>An action answers from several places — the generic button run
 * ({@code processResult}/{@code processMessage}), a customization's own response
 * ({@code success}/{@code message}, or {@code status}/{@code title}/{@code message}), a routing
 * refusal ({@code status}/{@code error}/{@code detail}) — so an agent had to guess where the outcome
 * was. Every result now also carries the same two keys: {@code success} (boolean, from the result's
 * error flag) and {@code message} (the first message the body has, with Etendo message keys such as
 * {@code @Success@} translated). The original keys stay, so nothing that read them breaks.</p>
 */
final class McpActionEnvelope {

  static final String KEY_SUCCESS = "success";
  static final String KEY_MESSAGE = "message";

  /** Where a result may carry its human message, in order of preference. */
  private static final String[] MESSAGE_KEYS = { KEY_MESSAGE, "processMessage", "detail", "title" };
  private static final String KEY_TEXT = "text";
  private static final String VALUE_ERROR = "error";

  private McpActionEnvelope() {
  }

  /**
   * Adds {@code success} and {@code message} to an {@code etendo_action} tool result whose content
   * is one JSON object. Any other result (prose, several contents) is returned as is.
   *
   * @param result the MCP tool result; mutated in place
   * @return {@code result}
   */
  static JSONObject normalize(JSONObject result) {
    if (result == null) {
      return null;
    }
    try {
      JSONArray content = result.optJSONArray("content");
      if (content == null || content.length() != 1) {
        return result;
      }
      JSONObject item = content.getJSONObject(0);
      String text = item.optString(KEY_TEXT, null);
      if (text == null || !text.trim().startsWith("{")) {
        return result;
      }
      JSONObject body = new JSONObject(text);
      body.put(KEY_SUCCESS, succeeded(result, body));
      String message = firstMessage(body);
      if (message != null) {
        body.put(KEY_MESSAGE, NeoMessageTranslator.safeParseTranslation(message));
      }
      item.put(KEY_TEXT, McpResponseSanitizer.render(body));
    } catch (JSONException e) {
      // A body that is not a JSON object is left as it came: the envelope is additive.
    }
    return result;
  }

  /**
   * Failed when the result is flagged as an error or the body itself says so (a customization's
   * {@code success:false}, a {@code processResult} or {@code status} of {@code error}).
   */
  private static boolean succeeded(JSONObject result, JSONObject body) {
    return !result.optBoolean("isError", false)
        && body.optBoolean(KEY_SUCCESS, true)
        && !VALUE_ERROR.equalsIgnoreCase(body.optString("processResult", ""))
        && !VALUE_ERROR.equalsIgnoreCase(body.optString("status", ""));
  }

  private static String firstMessage(JSONObject body) {
    for (String key : MESSAGE_KEYS) {
      String value = StringUtils.trimToNull(body.optString(key, null));
      if (value != null && !"null".equals(value)) {
        return value;
      }
    }
    return null;
  }
}
