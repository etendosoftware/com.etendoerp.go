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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * JSON request-body parsing helpers extracted from {@link NeoServlet}.
 * Provides depth-bounded parsing guarded against StackOverflowError on truncated or deep JSON.
 */
public final class NeoRequestBodyParser {

  /**
   * Deepest array/object nesting accepted before parsing. A valid body needs only 3-5 levels;
   * 32 provides plenty of headroom while protecting the parser thread stack from StackOverflowError.
   */
  public static final int MAX_JSON_DEPTH = 32;

  private NeoRequestBodyParser() {
  }

  /**
   * Reads the entire HTTP request body as a UTF-8 string.
   *
   * @param request the HTTP request whose body is read
   * @return the request body text decoded as UTF-8
   * @throws IOException if an I/O error occurs while reading the request input stream
   */
  public static String readRequestBody(HttpServletRequest request) throws IOException {
    return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  /**
   * Linear scan for array/object nesting deeper than {@code maxDepth}, ignoring brackets
   * inside JSON strings (escapes honoured). Cheap and total; it does not validate the JSON.
   *
   * @param body the JSON payload to inspect
   * @param maxDepth the maximum allowed nesting depth of brackets and braces
   * @return {@code true} if bracket or brace depth exceeds {@code maxDepth}, {@code false} otherwise
   */
  public static boolean exceedsDepth(String body, int maxDepth) {
    if (body == null) {
      return false;
    }
    int depth = 0;
    int i = 0;
    while (i < body.length()) {
      char c = body.charAt(i);
      if (c == '"') {
        i = endOfString(body, i);
      } else if (c == '[' || c == '{') {
        if (++depth > maxDepth) {
          return true;
        }
      } else if (c == ']' || c == '}') {
        depth--;
      }
      i++;
    }
    return false;
  }

  /**
   * @return the index of the quote closing the JSON string opened at {@code open} (escapes
   *         honoured), or {@code body.length()} when the string is never closed
   */
  private static int endOfString(String body, int open) {
    int i = open + 1;
    while (i < body.length()) {
      char c = body.charAt(i);
      if (c == '"') {
        return i;
      }
      // A backslash escapes the next character, so it is skipped too.
      i += c == '\\' ? 2 : 1;
    }
    return body.length();
  }

  /**
   * Parses {@code json} into a {@link JSONObject}, enforcing a maximum nesting depth of
   * {@value #MAX_JSON_DEPTH} and catching parser engine failures (including {@link StackOverflowError}
   * and runtime errors on truncated inputs), throwing {@link JSONException}.
   *
   * @param json the JSON string to parse
   * @return the parsed {@link JSONObject}
   * @throws JSONException if parsing fails, input is null, or depth exceeds {@value #MAX_JSON_DEPTH}
   */
  public static JSONObject parseJsonObjectSafely(String json) throws JSONException {
    return parseJsonObjectSafely(json, MAX_JSON_DEPTH);
  }

  /**
   * Parses {@code json} into a {@link JSONObject}, enforcing a maximum nesting depth of
   * {@code maxDepth} and catching parser engine failures (including {@link StackOverflowError}
   * and runtime errors on truncated inputs), throwing {@link JSONException}.
   *
   * @param json the JSON string to parse
   * @param maxDepth the maximum allowed nesting depth
   * @return the parsed {@link JSONObject}
   * @throws JSONException if parsing fails, input is null, or depth exceeds {@code maxDepth}
   */
  public static JSONObject parseJsonObjectSafely(String json, int maxDepth) throws JSONException {
    if (json == null) {
      throw new JSONException("JSON string is null");
    }
    if (exceedsDepth(json, maxDepth)) {
      throw new JSONException("JSON text exceeds maximum depth of " + maxDepth);
    }
    try {
      return new JSONObject(json);
    } catch (JSONException e) {
      throw e;
    } catch (RuntimeException | StackOverflowError e) { // NOSONAR — convert jettison stack/runtime error to checked JSONException
      throw new JSONException("Malformed JSON: " + e.getMessage());
    }
  }

  /**
   * Parses {@code json} into a {@link JSONArray}, enforcing a maximum nesting depth of
   * {@value #MAX_JSON_DEPTH} and catching parser engine failures (including {@link StackOverflowError}
   * and runtime errors on truncated inputs), throwing {@link JSONException}.
   *
   * @param json the JSON string to parse
   * @return the parsed {@link JSONArray}
   * @throws JSONException if parsing fails, input is null, or depth exceeds {@value #MAX_JSON_DEPTH}
   */
  public static JSONArray parseJsonArraySafely(String json) throws JSONException {
    return parseJsonArraySafely(json, MAX_JSON_DEPTH);
  }

  /**
   * Parses {@code json} into a {@link JSONArray}, enforcing a maximum nesting depth of
   * {@code maxDepth} and catching parser engine failures (including {@link StackOverflowError}
   * and runtime errors on truncated inputs), throwing {@link JSONException}.
   *
   * @param json the JSON string to parse
   * @param maxDepth the maximum allowed nesting depth
   * @return the parsed {@link JSONArray}
   * @throws JSONException if parsing fails, input is null, or depth exceeds {@code maxDepth}
   */
  public static JSONArray parseJsonArraySafely(String json, int maxDepth) throws JSONException {
    if (json == null) {
      throw new JSONException("JSON string is null");
    }
    if (exceedsDepth(json, maxDepth)) {
      throw new JSONException("JSON text exceeds maximum depth of " + maxDepth);
    }
    try {
      return new JSONArray(json);
    } catch (JSONException e) {
      throw e;
    } catch (RuntimeException | StackOverflowError e) { // NOSONAR — convert jettison stack/runtime error to checked JSONException
      throw new JSONException("Malformed JSON array: " + e.getMessage());
    }
  }

  /**
   * Parses {@code bodyStr} into a {@link JSONObject} safely, or returns {@code null} if blank.
   *
   * @param bodyStr the raw JSON string or blank input
   * @return the parsed {@link JSONObject}, or {@code null} if {@code bodyStr} is blank
   * @throws JSONException if {@code bodyStr} is non-blank but malformed or exceeds max depth
   */
  public static JSONObject parseOptionalJsonObject(String bodyStr) throws JSONException {
    if (StringUtils.isBlank(bodyStr)) {
      return null;
    }
    return parseJsonObjectSafely(bodyStr);
  }

  /**
   * Parses {@code bodyStr} into a {@link JSONObject} safely, returning an empty object if blank.
   *
   * @param bodyStr the raw JSON string or blank input
   * @return the parsed {@link JSONObject}, or a new empty {@link JSONObject} if blank
   * @throws JSONException if {@code bodyStr} is non-blank but malformed or exceeds max depth
   */
  public static JSONObject parseJsonObjectOrEmpty(String bodyStr) throws JSONException {
    JSONObject parsed = parseOptionalJsonObject(bodyStr);
    return parsed != null ? parsed : new JSONObject();
  }

  /**
   * Parses {@code bodyStr} into a {@link JSONObject} safely with default max depth.
   *
   * @param bodyStr the raw JSON string
   * @return the parsed {@link JSONObject}
   * @throws JSONException if {@code bodyStr} is null, malformed, or exceeds max depth
   */
  public static JSONObject parseJsonObject(String bodyStr) throws JSONException {
    return parseJsonObjectSafely(bodyStr);
  }
}
