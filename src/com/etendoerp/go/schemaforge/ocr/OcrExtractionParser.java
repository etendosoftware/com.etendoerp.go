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

package com.etendoerp.go.schemaforge.ocr;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.BoundedJsonParser;

/**
 * Reads what the extraction tool answered (ETP-5715), the way the application's upload panel does
 * ({@code useOcrExtraction.js}): the answer text may be wrapped in Markdown fences or in prose, and
 * a blank or unreadable PDF comes back as valid JSON whose every field is empty.
 *
 * <p>All parsing goes through {@link BoundedJsonParser}: the text is produced by a model reading an
 * untrusted document, so it is never handed to the JSON library unbounded.</p>
 */
public final class OcrExtractionParser {

  private static final String KEY_ANSWER = "answer";
  private static final String KEY_RESPONSE = "response";
  private static final String FENCE = "```";

  private OcrExtractionParser() {
  }

  /**
   * The extracted payload inside the tool's HTTP answer.
   *
   * <p>Same fallbacks as the panel: {@code answer} (an object, or text), else
   * {@code answer.response}, {@code response}, {@code message} or {@code raw}.</p>
   *
   * @param toolResponse the JSON body the tool answered with
   * @return the payload, or {@code null} when nothing parseable came back
   */
  public static JSONObject payloadOf(JSONObject toolResponse) {
    if (toolResponse == null) {
      return null;
    }
    Object answer = toolResponse.opt(KEY_ANSWER);
    if (answer instanceof JSONObject && !((JSONObject) answer).has(KEY_RESPONSE)) {
      return (JSONObject) answer;
    }
    return parseLoose(textOf(toolResponse, answer));
  }

  /**
   * Whether the tool reported its own failure: an {@code error} and no {@code answer}, which the
   * service answers with HTTP 200.
   *
   * @param toolResponse the JSON body the tool answered with
   * @return {@code true} when it is an error answer
   */
  public static boolean isToolError(JSONObject toolResponse) {
    return toolResponse != null && toolResponse.has("error") && !toolResponse.has(KEY_ANSWER);
  }

  private static String textOf(JSONObject response, Object answer) {
    if (answer instanceof String) {
      return (String) answer;
    }
    if (answer instanceof JSONObject) {
      return ((JSONObject) answer).optString(KEY_RESPONSE, "");
    }
    for (String key : new String[] { KEY_RESPONSE, "message", "raw" }) {
      String text = response.optString(key, "");
      if (!text.isEmpty()) {
        return text;
      }
    }
    return "";
  }

  /**
   * Best-effort parse of a JSON object out of a text blob: fences are dropped and, when the text is
   * not JSON as a whole, the span between the first and the last brace is tried.
   *
   * @param text the answer text
   * @return the object, or {@code null} when none can be read
   */
  static JSONObject parseLoose(String text) {
    String cleaned = stripFences(text);
    JSONObject direct = tryParse(cleaned);
    if (direct != null) {
      return direct;
    }
    int first = cleaned.indexOf('{');
    int last = cleaned.lastIndexOf('}');
    return first >= 0 && last > first ? tryParse(cleaned.substring(first, last + 1)) : null;
  }

  private static JSONObject tryParse(String text) {
    try {
      return BoundedJsonParser.parseObject(text);
    } catch (JSONException e) {
      return null;
    }
  }

  private static String stripFences(String text) {
    String trimmed = text == null ? "" : text.trim();
    if (!trimmed.startsWith(FENCE) || !trimmed.endsWith(FENCE) || trimmed.length() < 6) {
      return trimmed;
    }
    String body = trimmed.substring(3, trimmed.length() - 3);
    if (body.toLowerCase(Locale.ROOT).startsWith("json")) {
      body = body.substring(4);
    }
    return body.trim();
  }

  /**
   * Whether the payload carries anything usable: any of the type's data keys filled, or at least
   * one line. The same rule as the panel's {@code hasExtractedData}.
   *
   * @param type    the document type, which names the data keys
   * @param payload the parsed answer
   * @return {@code true} when something was read
   */
  public static boolean hasData(OcrDocType type, JSONObject payload) {
    if (payload == null) {
      return false;
    }
    JSONArray lines = payload.optJSONArray(OcrDocType.LINES_KEY);
    if (lines != null && lines.length() > 0) {
      return true;
    }
    for (String key : type.getDataKeys()) {
      if (isFilled(payload.opt(key))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isFilled(Object value) {
    if (value == null || JSONObject.NULL.equals(value)) {
      return false;
    }
    return !(value instanceof String) || StringUtils.isNotBlank((String) value);
  }
}
