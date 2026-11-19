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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The reason a confirmation of an inbox document failed, as an IDENTIFIER and not as translated
 * text (ETP-6051). What is stored in {@code ETGO_INCOMING_DOCUMENT.ERROR_DETAIL} is the compact
 * JSON {@code {"v":1,"code":"<code>","params":{...},"text":"<English default>"}}: the SPA
 * translates by {@code code} with {@code params}; every other reader (MCP, list, {@code etendo_get})
 * gets the raw string and its English {@code text}. A value that does not parse as this JSON is a
 * legacy free text and is returned as it is by the readers.
 *
 * <p>The column holds {@value #MAX_STORED} characters. A longer value is shortened in its
 * {@code text} and in the string values of its {@code params}, never as a string: the stored value
 * is always parseable.</p>
 */
final class InvoiceInboxFailureDetail {

  /** The longest value stored: the column is {@code varchar(2000)}. */
  static final int MAX_STORED = 2000;
  /** The version of the stored format. */
  static final int VERSION = 1;

  static final String NO_LOOKUPS = "no_lookups";
  static final String COMPLETION_ERROR = "completion_error";
  static final String NO_VENDOR_CATEGORY = "no_vendor_category";
  static final String NO_PRODUCT_SEQUENCE = "no_product_sequence";
  static final String NO_FILE = "no_file";
  static final String ATTACH_FAILED = "attach_failed";
  static final String CURRENCY_MISMATCH = "currency_mismatch";
  static final String TOTAL_MISMATCH = "total_mismatch";
  static final String STEP_FAILED = "step_failed";
  static final String SEARCH_KEY_EXISTS = "search_key_exists";
  /** Text that arrives already built from outside: it cannot be an identifier. */
  static final String REFUSED = "refused";

  static final String STEP_VENDOR = "vendor";
  static final String STEP_VENDOR_ADDRESS = "vendorAddress";
  static final String STEP_HEADER = "header";
  static final String STEP_LINE = "line";
  static final String STEP_PRODUCT = "product";
  static final String STEP_CREATION = "creation";

  static final String K_CODE = "code";
  static final String K_PARAMS = "params";
  static final String K_TEXT = "text";
  static final String K_VERSION = "v";
  static final String P_STEP = "step";
  static final String P_N = "n";
  static final String P_NAME = "name";
  static final String P_REASON = "reason";
  static final String P_MISSING = "missing";
  static final String P_MESSAGE = "message";
  static final String P_INVOICE = "invoice";
  static final String P_DOCUMENT = "document";

  private static final String ELLIPSIS = "...";
  private static final int MIN_KEPT = 32;

  private InvoiceInboxFailureDetail() {
  }

  /** One reason: a code and its parameters; its English text is derived from them. */
  static final class Detail {
    private final String code;
    private final Map<String, Object> params;
    private final String storedText;

    private Detail(String code, Map<String, Object> params, String storedText) {
      this.code = code;
      this.params = params;
      this.storedText = storedText;
    }

    /** The identifier the SPA translates by. */
    String code() {
      return code;
    }

    /** The parameters of the code; string, number or list of strings values. */
    Map<String, Object> params() {
      return params;
    }

    /** The English default text: the stored one when it was parsed, else derived from the code. */
    String text() {
      return storedText != null ? storedText : englishText(code, params);
    }

    /** The parameters as a JSON object. */
    JSONObject paramsJson() {
      return toJson(params);
    }

    /** The value to store: compact JSON that fits the column and always parses. */
    String stored() {
      return serialize(code, params, text());
    }

    private static String serialize(String code, Map<String, Object> params, String text) {
      JSONObject paramsJson = toJson(params);
      String shownText = StringUtils.defaultString(text);
      try {
        for (int attempt = 0; attempt < 200; attempt++) {
          String json = assemble(code, paramsJson, shownText);
          int over = json.length() - MAX_STORED;
          if (over <= 0) {
            return json;
          }
          String longest = longestString(paramsJson, shownText);
          if (longest == null) {
            paramsJson = dropBulk(paramsJson);
            continue;
          }
          // Cut the longest string by half the overflow (shared with its equals): the strings shrink
          // geometrically, so the text and the params stay readable instead of one being wiped.
          int length = K_TEXT.equals(longest) ? shownText.length()
              : paramsJson.getString(longest).length();
          int[] others = longestOthers(paramsJson, shownText, longest);
          int cut = Math.max(1, over / (2 * (others[1] + 1)));
          // never wipe a string while it can still shrink: keep a readable stub first
          cut = length > MIN_KEPT ? Math.min(cut, length - MIN_KEPT) : length;
          if (K_TEXT.equals(longest)) {
            shownText = shorten(shownText, cut);
          } else {
            paramsJson.put(longest, shorten(paramsJson.getString(longest), cut));
          }
        }
        return assemble(code, new JSONObject(), "");
      } catch (JSONException e) {
        throw new IllegalStateException("Could not serialize the failure detail", e);
      }
    }

    private static JSONObject toJson(Map<String, Object> params) {
      JSONObject out = new JSONObject();
      try {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
          Object value = entry.getValue();
          out.put(entry.getKey(), value instanceof List ? new JSONArray((List<?>) value) : value);
        }
      } catch (JSONException e) {
        throw new IllegalStateException("Could not build the failure parameters", e);
      }
      return out;
    }

    /**
     * The strings other than {@code except} ({@code text} stands for the text): the length of the
     * longest and how many are as long as {@code except} is (its equals).
     */
    private static int[] longestOthers(JSONObject params, String text, String except)
        throws JSONException {
      java.util.List<Integer> lengths = new java.util.ArrayList<>();
      int own;
      if (K_TEXT.equals(except)) {
        own = text.length();
      } else {
        own = params.getString(except).length();
        lengths.add(text.length());
      }
      Iterator<?> names = params.keys();
      while (names.hasNext()) {
        String name = (String) names.next();
        Object value = params.get(name);
        if (!name.equals(except) && value instanceof String) {
          lengths.add(((String) value).length());
        }
      }
      int longest = 0;
      int equals = 0;
      for (int length : lengths) {
        longest = Math.max(longest, length);
        equals += length == own ? 1 : 0;
      }
      return new int[] { longest, equals };
    }

    private static String assemble(String code, JSONObject params, String text)
        throws JSONException {
      return new JSONObject().put(K_VERSION, VERSION).put(K_CODE, code).put(K_PARAMS, params)
          .put(K_TEXT, text).toString();
    }

    /** The key ({@code text} for the text) of the longest shortenable string; null when none. */
    private static String longestString(JSONObject params, String text) throws JSONException {
      String best = text.isEmpty() ? null : K_TEXT;
      int bestLength = text.length();
      Iterator<?> names = params.keys();
      while (names.hasNext()) {
        String name = (String) names.next();
        Object value = params.get(name);
        if (value instanceof String && ((String) value).length() > bestLength
            && !((String) value).isEmpty()) {
          best = name;
          bestLength = ((String) value).length();
        }
      }
      return best;
    }

    private static String shorten(String value, int over) {
      int target = value.length() - over - 1;
      return target < ELLIPSIS.length() + 1 ? "" : StringUtils.abbreviate(value, target);
    }

    /** Last resort when only structure is left: the list of missing fields, then every parameter. */
    private static JSONObject dropBulk(JSONObject params) {
      if (params.has(P_MISSING)) {
        params.remove(P_MISSING);
        return params;
      }
      return new JSONObject();
    }
  }

  /**
   * Builds a reason.
   *
   * @param code   one of the codes of this class
   * @param params name and value pairs, as alternating arguments; a {@code null} value is left out
   * @return the reason
   */
  static Detail of(String code, Object... params) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < params.length; i += 2) {
      if (params[i + 1] != null) {
        map.put(String.valueOf(params[i]), params[i + 1]);
      }
    }
    return new Detail(code, map, null);
  }

  /** The reason of text that arrives already built from outside (completion, preset, pipeline). */
  static Detail ofRefusal(String message) {
    return of(REFUSED, P_MESSAGE, StringUtils.defaultString(message));
  }

  /**
   * Reads a stored value.
   *
   * @param stored the value of {@code ERROR_DETAIL}
   * @return the reason when the value is our JSON; empty for a legacy free text, a blank value or a
   *         JSON that is not ours
   */
  static Optional<Detail> parse(String stored) {
    if (stored == null || !stored.trim().startsWith("{")) {
      return Optional.empty();
    }
    try {
      JSONObject root = new JSONObject(stored.trim());
      String code = root.optString(K_CODE, "");
      if (!root.has(K_VERSION) || code.isEmpty()) {
        return Optional.empty();
      }
      JSONObject params = root.optJSONObject(K_PARAMS);
      Map<String, Object> map = new LinkedHashMap<>();
      Iterator<?> names = params == null ? null : params.keys();
      while (names != null && names.hasNext()) {
        String name = (String) names.next();
        map.put(name, fromJson(params.get(name)));
      }
      String text = root.has(K_TEXT) ? root.optString(K_TEXT, null) : null;
      return Optional.of(new Detail(code, map, text));
    } catch (JSONException e) {
      return Optional.empty();
    }
  }

  private static Object fromJson(Object value) throws JSONException {
    if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      List<Object> list = new java.util.ArrayList<>();
      for (int i = 0; i < array.length(); i++) {
        list.add(array.get(i));
      }
      return list;
    }
    return value;
  }

  // ── The English default text ────────────────────────────────────────────────────────────────

  /** The English default text of a code; an unknown code shows its reason or message. */
  static String englishText(String code, Map<String, Object> params) {
    switch (code) {
      case NO_LOOKUPS:
        return "no lookups for the document type";
      case COMPLETION_ERROR:
        return InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_COMPLETION_ERROR);
      case NO_VENDOR_CATEGORY:
        return InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_NO_CATEGORY);
      case NO_PRODUCT_SEQUENCE:
        return "no document sequence for the search key of a new product";
      case NO_FILE:
        return InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_NO_FILE);
      case ATTACH_FAILED:
        return InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_ATTACH_FAILED);
      case CURRENCY_MISMATCH:
        return InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_CURRENCY_MISMATCH);
      case TOTAL_MISMATCH:
        return InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_TOTAL_MISMATCH,
            P_INVOICE, string(params, P_INVOICE), P_DOCUMENT, string(params, P_DOCUMENT));
      case SEARCH_KEY_EXISTS:
        return stepText(params) + ": "
            + InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_SEARCH_KEY_EXISTS);
      case STEP_FAILED:
        return stepText(params) + ": " + string(params, P_REASON) + missingText(params);
      case REFUSED:
        return string(params, P_MESSAGE);
      default:
        return StringUtils.firstNonBlank(string(params, P_REASON), string(params, P_MESSAGE), code);
    }
  }

  /** The English name of the failed step: "new vendor", "line 2", "new product 'x'". */
  static String stepText(Map<String, Object> params) {
    String step = string(params, P_STEP);
    switch (step) {
      case STEP_VENDOR:
        return "new vendor";
      case STEP_VENDOR_ADDRESS:
        return "new vendor address";
      case STEP_HEADER:
        return "invoice header";
      case STEP_LINE:
        return "line " + string(params, P_N);
      case STEP_PRODUCT:
        return "new product '" + string(params, P_NAME) + "'";
      default:
        return STEP_CREATION;
    }
  }

  private static String missingText(Map<String, Object> params) {
    Object missing = params.get(P_MISSING);
    if (!(missing instanceof List) || ((List<?>) missing).isEmpty()) {
      return "";
    }
    return " (" + String.join(", ", ((List<?>) missing).stream().map(String::valueOf)
        .toArray(String[]::new)) + ")";
  }

  private static String string(Map<String, Object> params, String key) {
    Object value = params.get(key);
    return value == null ? "" : String.valueOf(value);
  }
}
