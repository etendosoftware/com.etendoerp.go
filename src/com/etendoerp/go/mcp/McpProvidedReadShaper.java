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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Gives a read served by an entity's customization (the READ pre-phase, {@link
 * McpHookExecutor#runReadProvider}) the same contract as the generic {@code etendo_list} /
 * {@code etendo_get} path (ETP-5852).
 *
 * <p>Production agents reported that some lists — {@code product/stock}, {@code product/price} and
 * the other entities whose customization builds its own response — came back as
 * {@code {"response":{"data":[…],"status":0}}} instead of the flat {@code {startRow, endRow,
 * totalRows, data}} every other list has, and ignored {@code fields} and {@code limit}: an agent
 * asked for one row and one field and got every row with every column. The generic path flattens,
 * projects and pages; this path returned the customization's body as it was.
 *
 * <p>MCP-only. The customization's REST response, which the SPA reads, is not touched.
 */
final class McpProvidedReadShaper {

  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";
  private static final String KEY_START_ROW = "startRow";
  private static final String KEY_END_ROW = "endRow";
  private static final String KEY_TOTAL_ROWS = "totalRows";

  private McpProvidedReadShaper() {
  }

  /**
   * The MCP result for a customization's read response.
   *
   * @param response the customization's response, never {@code null}
   * @param args     the tool arguments ({@code fields}, for the projection)
   * @param offset   the first row the caller asked for; {@code null} on {@code etendo_get}
   * @param limit    the page size the caller asked for; {@code null} on {@code etendo_get}
   */
  static JSONObject shape(NeoResponse response, JSONObject args, Integer offset, Integer limit)
      throws JSONException {
    if (response.getHttpStatus() >= 400 || response.getBody() == null) {
      return McpHookExecutor.neoResponseToMcpResult(response);
    }
    JSONObject wrapped = wrapped(response.getBody());
    if (wrapped == null) {
      // Not a row list (a summary object, a report): nothing to page or project.
      return McpToolRouter.wrapAsTextContent(response.getBody());
    }
    if (offset != null && limit != null) {
      page(wrapped.getJSONObject(KEY_RESPONSE), offset, limit);
    }
    project(wrapped, args);
    JSONObject flat = McpToolRouterSupport.flattenCoreResponse(wrapped);
    // etendo_get (no paging) answers one record as an object, as the generic get does.
    return McpToolRouter.wrapAsTextContent(offset == null && limit == null
        ? McpRecordUrls.unwrapSingleRecord(flat) : flat);
  }

  /**
   * The body in the core list shape {@code {"response":{"data":[…], …}}}, whether the
   * customization nested it or not; {@code null} when it carries no row array.
   */
  static JSONObject wrapped(JSONObject body) throws JSONException {
    JSONObject inner = body.optJSONObject(KEY_RESPONSE);
    if (inner != null && inner.optJSONArray(KEY_DATA) != null) {
      JSONObject only = new JSONObject();
      only.put(KEY_RESPONSE, inner);
      // Keys next to "response" (rare) are kept inside it, so the flatten loses nothing.
      for (Iterator<?> keys = body.keys(); keys.hasNext();) {
        String key = String.valueOf(keys.next());
        if (!KEY_RESPONSE.equals(key) && !inner.has(key)) {
          inner.put(key, body.get(key));
        }
      }
      return only;
    }
    if (body.optJSONArray(KEY_DATA) != null) {
      return new JSONObject().put(KEY_RESPONSE, new JSONObject(body.toString()));
    }
    return null;
  }

  /**
   * Applies {@code offset}/{@code limit} when the customization ignored them — it returned more
   * rows than the page holds. A customization that paged itself returns at most {@code limit}
   * rows and is left alone. {@code totalRows} keeps the full count so the caller can page on.
   */
  static void page(JSONObject response, int offset, int limit) throws JSONException {
    JSONArray data = response.getJSONArray(KEY_DATA);
    if (limit <= 0 || data.length() <= limit) {
      return;
    }
    int total = data.length();
    int from = Math.min(Math.max(offset, 0), total);
    int to = Math.min(from + limit, total);
    JSONArray page = new JSONArray();
    for (int i = from; i < to; i++) {
      page.put(data.get(i));
    }
    response.put(KEY_DATA, page);
    response.put(KEY_START_ROW, from);
    response.put(KEY_END_ROW, Math.max(from, to - 1));
    response.put(KEY_TOTAL_ROWS, Math.max(total, response.optInt(KEY_TOTAL_ROWS, 0)));
  }

  /**
   * Applies an explicit {@code fields:[…]} whitelist. The names the rows can emit are read from the
   * rows themselves — a customization's row is not the spec's field list — so a requested name no
   * row carries is reported in {@code unknownFields}; on an empty page nothing is judged.
   */
  static void project(JSONObject wrapped, JSONObject args) throws JSONException {
    JSONArray fields = args == null ? null : args.optJSONArray(McpFieldProjection.PARAM_FIELDS);
    if (fields == null) {
      return;
    }
    Set<String> requested = McpFieldProjection.baseNames(McpFieldProjection.parseFields(fields));
    JSONArray data = wrapped.getJSONObject(KEY_RESPONSE).getJSONArray(KEY_DATA);
    Set<String> emittable = new HashSet<>();
    for (int i = 0; i < data.length(); i++) {
      JSONObject row = data.optJSONObject(i);
      for (Iterator<?> keys = row == null ? null : row.keys(); keys != null && keys.hasNext();) {
        emittable.add(McpDefaultsView.baseProperty(String.valueOf(keys.next())));
      }
    }
    McpFieldProjection.reportUnknownFields(wrapped, requested,
        emittable.isEmpty() ? Optional.empty() : Optional.of(emittable));
    McpFieldProjection.apply(wrapped, requested);
  }
}
