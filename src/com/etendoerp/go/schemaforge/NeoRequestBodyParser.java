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
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.BoundedJsonParser;
import com.etendoerp.go.common.MalformedJsonException;

/**
 * JSON request-body parsing helpers extracted from {@link NeoServlet}.
 *
 * <p>Every parse delegates to {@link BoundedJsonParser}, the single validator of client JSON: a
 * body that is malformed, truncated or nested too deep is a {@link MalformedJsonException} (still
 * a {@code JSONException}, so existing 400 mappings keep working), never a jettison
 * {@code Error} and never a message that echoes the body.</p>
 */
public final class NeoRequestBodyParser {

  /** Deepest array/object nesting accepted by the one-argument parse methods. */
  public static final int MAX_JSON_DEPTH = BoundedJsonParser.DEFAULT_MAX_DEPTH;

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
   * Parses {@code json} into a {@link JSONObject}, nested at most {@value #MAX_JSON_DEPTH} levels.
   *
   * @param json the JSON string to parse
   * @return the parsed {@link JSONObject}
   * @throws MalformedJsonException if {@code json} is null, malformed, too deep, or not an object
   */
  public static JSONObject parseJsonObjectSafely(String json) throws MalformedJsonException {
    return BoundedJsonParser.parseObject(json);
  }

  /**
   * Parses {@code json} into a {@link JSONObject}, nested at most {@code maxDepth} levels.
   *
   * @param json the JSON string to parse
   * @param maxDepth the maximum allowed nesting depth
   * @return the parsed {@link JSONObject}
   * @throws MalformedJsonException if {@code json} is null, malformed, too deep, or not an object
   */
  public static JSONObject parseJsonObjectSafely(String json, int maxDepth)
      throws MalformedJsonException {
    return BoundedJsonParser.parseObject(json, maxDepth);
  }

  /**
   * Parses {@code json} into a {@link JSONArray}, nested at most {@value #MAX_JSON_DEPTH} levels.
   *
   * @param json the JSON string to parse
   * @return the parsed {@link JSONArray}
   * @throws MalformedJsonException if {@code json} is null, malformed, too deep, or not an array
   */
  public static JSONArray parseJsonArraySafely(String json) throws MalformedJsonException {
    return BoundedJsonParser.parseArray(json);
  }

  /**
   * Parses {@code json} into a {@link JSONArray}, nested at most {@code maxDepth} levels.
   *
   * @param json the JSON string to parse
   * @param maxDepth the maximum allowed nesting depth
   * @return the parsed {@link JSONArray}
   * @throws MalformedJsonException if {@code json} is null, malformed, too deep, or not an array
   */
  public static JSONArray parseJsonArraySafely(String json, int maxDepth)
      throws MalformedJsonException {
    return BoundedJsonParser.parseArray(json, maxDepth);
  }

  /**
   * Parses {@code bodyStr} into a {@link JSONObject}, or returns {@code null} if blank.
   *
   * @param bodyStr the raw JSON string or blank input
   * @return the parsed {@link JSONObject}, or {@code null} if {@code bodyStr} is blank
   * @throws MalformedJsonException if {@code bodyStr} is non-blank but malformed or too deep
   */
  public static JSONObject parseOptionalJsonObject(String bodyStr) throws MalformedJsonException {
    if (StringUtils.isBlank(bodyStr)) {
      return null;
    }
    return parseJsonObjectSafely(bodyStr);
  }

  /**
   * Parses {@code bodyStr} into a {@link JSONObject}, returning an empty object if blank.
   *
   * @param bodyStr the raw JSON string or blank input
   * @return the parsed {@link JSONObject}, or a new empty {@link JSONObject} if blank
   * @throws MalformedJsonException if {@code bodyStr} is non-blank but malformed or too deep
   */
  public static JSONObject parseJsonObjectOrEmpty(String bodyStr) throws MalformedJsonException {
    JSONObject parsed = parseOptionalJsonObject(bodyStr);
    return parsed != null ? parsed : new JSONObject();
  }

  /**
   * Parses {@code bodyStr} into a {@link JSONObject} with the default max depth.
   *
   * @param bodyStr the raw JSON string
   * @return the parsed {@link JSONObject}
   * @throws MalformedJsonException if {@code bodyStr} is null, malformed, or too deep
   */
  public static JSONObject parseJsonObject(String bodyStr) throws MalformedJsonException {
    return parseJsonObjectSafely(bodyStr);
  }
}
