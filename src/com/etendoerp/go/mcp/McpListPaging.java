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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.service.json.JsonConstants;

/**
 * Paging corrections the MCP {@code etendo_list} applies to the page core's
 * {@code DefaultJsonDataService} serves (ETP-5837, QA CP-16).
 *
 * <p>Core's JSON service is built for the Classic grid: when a requested page comes back empty and
 * {@code startRow > 0}, it fetches again from row 0 and answers the first page. A grid never asks
 * past the end, so that is harmless there; an agent paging until it gets an empty page does, and
 * never got one. This class turns that answer back into the page that was asked for.
 *
 * <p>Structural only: it reads the paging keys of core's response envelope and never an entity.
 * MCP only; the REST list keeps core's behaviour.
 */
final class McpListPaging {

  private McpListPaging() {
  }

  /**
   * Turns core's "start over" answer to a page past the end into the empty page that was asked for
   * (ETP-5837, QA CP-16).
   *
   * <p>{@code DefaultJsonDataService.fetch} re-runs a read from row 0 when the requested page comes
   * back empty and {@code startRow > 0} (core's grid behaviour: "reload the startrow again from 0").
   * So {@code etendo_list} with an {@code offset} at or past {@code totalRows} answered the FIRST
   * page again, with {@code startRow: 0}. An agent paging until it gets an empty page never got one
   * and looped over the same rows. The response's own {@code startRow} is how this is detected: it
   * is the first row core actually served, and it differs from the requested offset only when core
   * restarted.
   *
   * <p>Structural: it reads only the paging keys of the core envelope, never an entity. Applied to
   * the MCP list only; the REST list keeps core's behaviour.
   *
   * <p>The corrected page is {@code data: []}, {@code startRow = offset},
   * {@code endRow = offset - 1} (no row served: {@code endRow - startRow + 1 = 0}) and the
   * {@code totalRows} core counted, unchanged.
   *
   * @param responseJson the core response, still wrapped in {@code response}; modified in place
   * @param offset       the requested first row
   * @return {@code true} when the page was replaced by an empty one
   * @throws JSONException never in practice (plain values only)
   */
  static boolean emptyPageWhenPastEnd(JSONObject responseJson, int offset) throws JSONException {
    if (responseJson == null || offset <= 0) {
      return false;
    }
    JSONObject inner = responseJson.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
    if (inner == null || !inner.has(JsonConstants.RESPONSE_STARTROW)) {
      return false;
    }
    if (inner.optInt(JsonConstants.RESPONSE_STARTROW, offset) == offset) {
      return false;
    }
    inner.put(JsonConstants.RESPONSE_DATA, new JSONArray());
    inner.put(JsonConstants.RESPONSE_STARTROW, offset);
    inner.put(JsonConstants.RESPONSE_ENDROW, offset - 1);
    return true;
  }
}
