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

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.service.json.JsonConstants;

/**
 * Lets an entity's customization decide the order of a REST list page when that order is not a
 * property path ({@link NeoHandler#orderedPage}).
 *
 * <p>Two steps around the default list fetch, both in {@code NeoCrudHandler.handleDefault}:
 * {@link #apply} asks the customization for the ids of the page and narrows the fetch to exactly
 * those rows; {@link #reorder} puts the fetched rows back in the customization's order and
 * restores the paging figures (the narrowed fetch would otherwise report a one-page total).
 * Nothing here names an entity: when the customization declines, the request runs exactly as it
 * would without this class.</p>
 *
 * <p>The ids the customization returns are spliced into the HQL as quoted literals (core offers
 * no bind parameter for the where clause), so each one is checked against a strict identifier
 * pattern first and a page with any other value is refused (default order).</p>
 */
final class NeoOrderedListPage {

  private static final Logger log = LogManager.getLogger(NeoOrderedListPage.class);

  /** Largest page this mechanism serves; a bigger request keeps the default order. */
  static final int MAX_PAGE_SIZE = 1000;

  static final String ATTRIBUTE = NeoOrderedListPage.class.getName() + ".page";

  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private NeoOrderedListPage() {
  }

  /** What {@link #apply} remembers for {@link #reorder}. */
  private static final class Applied {
    private final NeoOrderedPage page;
    private final int startRow;

    private Applied(NeoOrderedPage page, int startRow) {
      this.page = page;
      this.startRow = startRow;
    }
  }

  /**
   * Asks the customization for an ordered page and, when it gives one, narrows {@code params} (the
   * DAL fetch params, mutated in place) to those rows.
   *
   * @param context the list GET context
   * @param params the DAL params the list is fetched with; must already carry the where clause
   * @return {@code true} when the fetch was narrowed and {@link #reorder} must run on its result
   */
  static boolean apply(NeoContext context, Map<String, String> params) {
    NeoListAggregateQuery filteredSet = NeoListAggregateQuery.of(context);
    NeoHandler customization = customization(context);
    if (filteredSet == null || customization == null) {
      return false;
    }
    int startRow = parseInt(params.get(JsonConstants.STARTROW_PARAMETER), 0);
    int endRow = parseInt(params.get(JsonConstants.ENDROW_PARAMETER), startRow);
    int pageSize = endRow - startRow + 1;
    if (startRow < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      return false;
    }
    NeoOrderedPage page = customization.orderedPage(context, filteredSet, startRow, pageSize);
    if (page == null || !allSafe(page.getIds())) {
      if (page != null) {
        log.warn("Ordered page refused: an id is not a plain identifier; default order kept");
      }
      return false;
    }
    narrow(params, page.getIds());
    context.setAttribute(ATTRIBUTE, new Applied(page, startRow));
    return true;
  }

  /**
   * Puts the rows of the fetched response in the order of the page {@link #apply} resolved and
   * restores {@code startRow}, {@code endRow} and {@code totalRows} to those of the whole list.
   * A no-op when {@link #apply} did not narrow this request.
   *
   * @param context the list GET context
   * @param responseJson the DAL response, mutated in place
   */
  static void reorder(NeoContext context, JSONObject responseJson) throws JSONException {
    Object stored = context.getAttribute(ATTRIBUTE);
    JSONObject response = responseJson == null ? null
        : responseJson.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
    if (!(stored instanceof Applied) || response == null) {
      return;
    }
    Applied applied = (Applied) stored;
    JSONArray data = response.optJSONArray(JsonConstants.RESPONSE_DATA);
    Map<String, JSONObject> byId = new HashMap<>();
    for (int i = 0; data != null && i < data.length(); i++) {
      JSONObject row = data.optJSONObject(i);
      if (row != null) {
        byId.put(row.optString(JsonConstants.ID), row);
      }
    }
    // Ids that vanish between the id read and the row fetch are dropped silently; totalRows self-heals on the next page.
    JSONArray ordered = new JSONArray();
    for (String id : applied.page.getIds()) {
      JSONObject row = byId.get(id);
      if (row != null) {
        ordered.put(row);
      }
    }
    response.put(JsonConstants.RESPONSE_DATA, ordered);
    response.put(JsonConstants.RESPONSE_STARTROW, applied.startRow);
    response.put(JsonConstants.RESPONSE_ENDROW, applied.startRow + Math.max(ordered.length() - 1, 0));
    response.put(JsonConstants.RESPONSE_TOTALROWS, applied.page.getTotalRows());
  }

  /** Restricts the fetch to {@code ids}, served as a single page sorted by id. */
  private static void narrow(Map<String, String> params, List<String> ids) {
    String restriction = ids.isEmpty() ? "1 = 0" : inList(ids);
    String where = params.get(JsonConstants.WHERE_AND_FILTER_CLAUSE);
    params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE,
        StringUtils.isBlank(where) ? restriction : "(" + where + ") and " + restriction);
    params.put(JsonConstants.STARTROW_PARAMETER, "0");
    params.put(JsonConstants.ENDROW_PARAMETER, String.valueOf(Math.max(ids.size() - 1, 0)));
    params.put(JsonConstants.SORTBY_PARAMETER, JsonConstants.ID);
    params.remove(JsonConstants.ORDERBY_PARAMETER);
  }

  /**
   * The only place in shared code that splices ids into HQL. Safe here because the ids come from a
   * server-side query (the customization), never from the request, and {@link #allSafe} has already
   * validated each against {@code [A-Za-z0-9_-]{1,64}}; core offers no bind parameter for the extra
   * where clause.
   */
  private static String inList(List<String> ids) {
    List<String> quoted = new ArrayList<>(ids.size());
    for (String id : ids) {
      quoted.add("'" + id + "'");
    }
    return JsonConstants.MAIN_ALIAS + ".id in (" + String.join(", ", quoted) + ")";
  }

  private static boolean allSafe(List<String> ids) {
    for (String id : ids) {
      if (id == null || !SAFE_ID.matcher(id).matches()) {
        return false;
      }
    }
    return true;
  }

  private static int parseInt(String raw, int fallback) {
    try {
      return raw == null ? fallback : Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static NeoHandler customization(NeoContext context) {
    if (context == null) {
      return null;
    }
    return NeoExtensionDispatcher.resolveOnly(NeoExtensionRequest.builder()
        .qualifier(context.getSfEntity() != null ? context.getSfEntity().getJavaQualifier() : null)
        .specName(context.getSpecName())
        .entityName(context.getEntityName())
        .surface(NeoExtensionSurface.READ)
        .channel(NeoExtensionChannel.REST_SINGLE)
        .context(context)
        .build());
  }
}
