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

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Cleans what the extraction read from a document before it reaches an agent (ETP-5715).
 *
 * <p>The text came from an untrusted PDF, so it can carry instructions aimed at the agent that reads
 * the answer. This class cannot make the text safe, but it bounds what an attacker can place there:
 * only the keys the answer schema declares survive (no attacker-chosen property names), every string
 * is capped, control and invisible formatting characters are removed (no hidden or line-broken
 * payloads), the number of lines is capped. The answer then puts all of it
 * under a block marked {@code untrusted}, and never in a message or hint.</p>
 */
public final class OcrSanitizer {

  /** Longest string kept, in characters. */
  public static final int MAX_TEXT_LENGTH = 200;
  /** Most lines kept. */
  public static final int MAX_LINES = 100;

  private static final String KEY_PROPERTIES = "properties";
  private static final String KEY_ITEMS = "items";

  private OcrSanitizer() {
  }

  /**
   * Keeps only what the schema declares, cleaned.
   *
   * @param payload the parsed answer of the extraction
   * @param schema  the answer schema of the document type
   * @return a new object; keys absent from the payload are {@code null}
   * @throws JSONException when the schema cannot be walked
   */
  public static JSONObject sanitize(JSONObject payload, JSONObject schema) throws JSONException {
    JSONObject properties = schema.optJSONObject(KEY_PROPERTIES);
    JSONObject out = new JSONObject();
    if (properties == null) {
      return out;
    }
    Iterator<?> keys = properties.keys();
    while (keys.hasNext()) {
      String key = (String) keys.next();
      out.put(key, clean(payload == null ? null : payload.opt(key),
          properties.getJSONObject(key)));
    }
    return out;
  }

  private static Object clean(Object value, JSONObject schema) throws JSONException {
    if (schema.has(KEY_PROPERTIES)) {
      return value instanceof JSONObject ? sanitize((JSONObject) value, schema) : JSONObject.NULL;
    }
    if (schema.optJSONObject(KEY_ITEMS) != null) {
      return value instanceof JSONArray
          ? lines((JSONArray) value, schema.getJSONObject(KEY_ITEMS)) : JSONObject.NULL;
    }
    return scalar(value, schema);
  }

  /**
   * A leaf value, held to the type its schema declares: a number field keeps only a finite,
   * bounded number (a numeric string of bounded precision is converted), a string field keeps text
   * (a bounded number is written as text), anything else is null. A schema with no type keeps the
   * value as it is judged by its own Java type.
   */
  private static Object scalar(Object value, JSONObject schema) {
    Set<String> types = typesOf(schema);
    boolean numeric = types.contains("number") || types.contains("integer");
    boolean textual = types.isEmpty() || types.contains("string");
    if (numeric && !textual) {
      Number number = OcrJson.boundedNumber(value);
      return number == null ? JSONObject.NULL : number;
    }
    if (value instanceof String) {
      return text((String) value);
    }
    if (value instanceof Number) {
      Number number = OcrJson.boundedNumber(value);
      if (number == null) {
        return JSONObject.NULL;
      }
      return textual ? text(((java.math.BigDecimal) number).toPlainString()) : number;
    }
    return JSONObject.NULL;
  }

  private static Set<String> typesOf(JSONObject schema) {
    Object type = schema.opt("type");
    Set<String> types = new HashSet<>();
    if (type instanceof String) {
      types.add((String) type);
    } else if (type instanceof JSONArray) {
      JSONArray array = (JSONArray) type;
      for (int i = 0; i < array.length(); i++) {
        types.add(array.optString(i));
      }
    }
    return types;
  }

  private static JSONArray lines(JSONArray source, JSONObject itemSchema) throws JSONException {
    JSONArray out = new JSONArray();
    int count = Math.min(source.length(), MAX_LINES);
    for (int i = 0; i < count; i++) {
      Object item = source.opt(i);
      if (item instanceof JSONObject) {
        out.put(sanitize((JSONObject) item, itemSchema));
      }
    }
    return out;
  }

  private static boolean isBlankLike(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.getType(codePoint) == Character.CONTROL;
  }

  /**
   * Characters that print nothing or mean nothing: invisible formatting (zero-width, bidirectional
   * marks), variation selectors, private use, unassigned code points and lone surrogates — places
   * to hide a payload from a human reader that a model still reads.
   */
  private static boolean isInvisible(int codePoint) {
    int type = Character.getType(codePoint);
    boolean variationSelector = (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
        || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
    return variationSelector || type == Character.FORMAT || type == Character.PRIVATE_USE
        || type == Character.UNASSIGNED || type == Character.SURROGATE;
  }

  private static void appendSpace(StringBuilder text) {
    if (text.length() > 0 && text.charAt(text.length() - 1) != ' ') {
      text.append(' ');
    }
  }

  /**
   * A single clean line: runs of whitespace and control characters become one space, and invisible
   * formatting characters (zero-width, bidirectional marks) are dropped, the result is trimmed and
   * capped at {@value #MAX_TEXT_LENGTH} characters.
   *
   * @param raw the text read from the document
   * @return the clean text, empty when nothing is left
   */
  public static String text(String raw) {
    StringBuilder cleaned = new StringBuilder(Math.min(raw.length(), MAX_TEXT_LENGTH + 1));
    int i = 0;
    while (i < raw.length() && cleaned.length() <= MAX_TEXT_LENGTH) {
      int codePoint = raw.codePointAt(i);
      i += Character.charCount(codePoint);
      if (isBlankLike(codePoint)) {
        appendSpace(cleaned);
      } else if (!isInvisible(codePoint)) {
        cleaned.appendCodePoint(codePoint);
      }
    }
    String line = cleaned.toString().trim();
    return line.length() > MAX_TEXT_LENGTH ? line.substring(0, MAX_TEXT_LENGTH).trim() : line;
  }
}
