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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Removes keys that an MCP client may not receive, from the body of a tool result (ETP-5306).
 *
 * <p><b>Why this exists.</b> {@code $ref} is a reserved key inside Google Gemini's
 * {@code function_response.response}: it means "a pointer to an attached part, resolvable by
 * {@code display_name}". Openbravo's {@code DataToJsonConverter#toJsonObject} puts it on
 * <em>every</em> serialised record ({@code JsonConstants.REF}, line 169 of that class), so every
 * row of a {@code neo_list} / {@code neo_get} carried one. Gemini tried to resolve the pointer,
 * found no such part, and rejected the <em>whole</em> request with HTTP 400 {@code INVALID_ARGUMENT}
 * — "The referenced name {@code BusinessPartner/BC8D…} in function_response.response does not match
 * to a display_name in the function_response.parts". The failure is on the tool <em>result</em>, so
 * no prompt change and no retry can work around it: the Etendo GO MCP server was unusable with any
 * Gemini model as soon as the agent read a record.
 *
 * <p><b>What is and is not stripped.</b> Only the key spelled exactly {@code $ref} is removed.
 * A key that merely <em>contains</em> a {@code $} is harmless and must be kept — the FK identifier
 * columns are all spelled {@code xxx$_identifier}, and they were verified to pass. Likewise a
 * <em>value</em> of the form {@code "$ref:<opId>"} (the {@code neo_batch} placeholder,
 * {@link com.etendoerp.go.schemaforge.BatchService#REF_PREFIX}) is untouched: this class only ever
 * looks at key names.
 *
 * <p><b>Why removing it is lossless.</b> {@code encodeReference} builds the value as
 * {@code entityName + "/" + id}, and both halves are already present on the same row as
 * {@code _entityName} and {@code id}. The construction rule is now declared once — in
 * {@code neo_schema}'s hint and in the {@code docs} preamble — instead of being paid for on every
 * row of every response, which is also why this is an Agent Context Economy win, not just a fix.
 *
 * <p><b>MCP surface only.</b> The stripping happens in the MCP content wrappers
 * ({@code McpToolRouter#wrapAsTextContent} / {@code wrapAsErrorContent}), not in the shared
 * serialiser. The NEO REST API ({@code /sws/neo/…}) has other consumers — the React SPA among them
 * — and its response contract is unchanged.
 *
 * <p><b>Why it operates on {@link JSONObject}, never on serialised text.</b> Re-parsing a rendered
 * body to strip a key would silently rewrite the numbers in it: jettison parses JSON numbers into
 * {@code Integer}/{@code Long}/{@code Double}, so a decimal wider than a {@code double} comes back
 * degraded — {@code 123456789012345678901234.5} renders as {@code 1.2345678901234569E23} after one
 * round trip, measured against jettison 1.3. Stripping in place on the object graph, before it is
 * rendered, leaves every {@code BigDecimal} exactly as the producer put it. This is why the JSON
 * overloads of the content wrappers take the object and render it here, rather than accepting a
 * rendered string and sanitising that.
 */
final class McpResponseSanitizer {

  /**
   * The reserved key. Mirrors {@code org.openbravo.service.json.JsonConstants#REF}; spelled out
   * here rather than imported so this class states the MCP-side contract on its own terms.
   */
  static final String RESERVED_REF_KEY = "$ref";

  private McpResponseSanitizer() {
  }

  /**
   * Strip the reserved keys from {@code body} in place and render it with the indentation the MCP
   * tool results use.
   *
   * @param body the tool-result body (may be {@code null})
   * @return the rendered body, or {@code "{}"} when {@code body} is {@code null}
   * @throws JSONException if the body cannot be rendered
   */
  static String render(JSONObject body) throws JSONException {
    if (body == null) {
      return "{}";
    }
    strip(body);
    return body.toString(2);
  }

  /**
   * Recursively remove every {@link #RESERVED_REF_KEY} entry from {@code object} and from every
   * object nested inside it, directly or through arrays. Rows nest (child entities, {@code data}
   * arrays, a handler's own envelope), so a shallow removal would leave the client rejecting the
   * response for a {@code $ref} one level down.
   *
   * @param object the object to sanitize in place; {@code null} is a no-op
   */
  static void strip(JSONObject object) throws JSONException {
    if (object == null) {
      return;
    }
    object.remove(RESERVED_REF_KEY);
    // Snapshot the key set: jettison's iterator is backed by the underlying map, and descending
    // into a value must not be done while that iterator is live.
    List<String> keys = new ArrayList<>();
    for (Iterator<?> it = object.keys(); it.hasNext();) {
      keys.add(String.valueOf(it.next()));
    }
    for (String key : keys) {
      stripValue(object.get(key));
    }
  }

  /**
   * Recursively sanitize every element of {@code array}.
   *
   * @param array the array to sanitize in place; {@code null} is a no-op
   */
  static void strip(JSONArray array) throws JSONException {
    if (array == null) {
      return;
    }
    for (int i = 0; i < array.length(); i++) {
      stripValue(array.get(i));
    }
  }

  /** Dispatch on the runtime type of a JSON value; scalars are left alone. */
  private static void stripValue(Object value) throws JSONException {
    if (value instanceof JSONObject) {
      strip((JSONObject) value);
    } else if (value instanceof JSONArray) {
      strip((JSONArray) value);
    }
  }
}
