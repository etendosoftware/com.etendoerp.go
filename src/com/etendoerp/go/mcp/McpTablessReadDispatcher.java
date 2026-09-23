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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Read-path dispatch for entities that have no AD tab (ETP-5405).
 *
 * <p>Extracted from {@link McpToolRouter}, which had grown past the method count Sonar allows for
 * one class (java:S1448). The two members here are one concern — deciding whether a read can be
 * served by the entity's handler, and shaping the arguments that handler will read — so they form
 * a unit rather than an arbitrary split to get under a threshold.</p>
 *
 * <p>Deliberately NOT folded into {@link McpHookExecutor}, even though that is where the hook
 * primitives live: the router tests mock that class statically, so a dispatch method living there
 * would be stubbed to {@code null} by those mocks and would silently disable the very dispatch
 * under test. Keeping it in a class nobody mocks means the tests still exercise the real decision
 * while stubbing only the hook primitives it calls.</p>
 */
final class McpTablessReadDispatcher {

  private McpTablessReadDispatcher() {
  }

  /**
   * Serve a read from the entity's {@link NeoHandler} when the entity has no AD tab.
   *
   * <p>Sixteen active, included entities carry {@code ETGO_SF_ENTITY.ad_tab_id IS NULL} because
   * they are not backed by a window at all — the nine dashboard widgets, {@code contacts/bp-stats}
   * and {@code bp-trend}, {@code not-posted-documents/header}, the three report specs and
   * {@code warehouse/location}. Every one of them has a handler that serves it, and over REST that
   * handler answers: {@code NeoCrudHandler.dispatchCrudRequestInternal} consults the entity's
   * {@code Java_Qualifier} first and only falls through to the tab-based generic path when there
   * is none. {@code neo_list} and {@code neo_get} did the opposite — they demanded the tab up
   * front and threw, so the handler was never consulted and the agent got a 500 on an entity the
   * SPA renders fine.</p>
   *
   * <p><b>Deliberately narrower than REST.</b> REST runs the handler ahead of the generic path for
   * every entity; this runs it only when there is no tab. The 171 entities that do have one keep
   * the exact code path they have today, so nothing that currently answers can change shape. That
   * leaves a known gap in the other direction — 80 tab-backed entities have a handler whose
   * {@code afterHandle} enriches a REST read and still does not run here, so an MCP read of those
   * returns less than the same read over REST. Closing it means running both hooks on every read,
   * which is a behaviour change across most of the API and is tracked separately; this method is
   * the half that cannot regress anything.</p>
   *
   * @param recordId    the record being fetched, {@code null} for a list
   * @param queryParams the MCP arguments flattened into the shape a read handler reads its input
   *                    from; never {@code null}
   * @return the MCP result when the handler served the read, or {@code null} to continue to the
   *         generic tab-based path
   */
  static JSONObject run(String specName, String entityName, String recordId, SFEntity sfEntity,
      Map<String, String> queryParams) throws JSONException {
    if (sfEntity.getADTab() != null) {
      return null;
    }
    NeoHandler handler = McpHookExecutor.resolveEntityHandler(sfEntity);
    if (handler == null) {
      return null;
    }
    NeoContext ctx = McpHookExecutor.buildReadHookContext(specName, entityName, recordId, null,
        sfEntity, queryParams);
    return McpHookExecutor.runPreHook(handler, ctx);
  }

  /**
   * Flatten the read arguments into the query-param map a handler expects.
   *
   * <p>The paging and sort keys keep the names core uses on the REST query string, and each filter
   * becomes a plain entry under its own name — which is how the SPA sends them and therefore what
   * a handler written against REST already reads (see {@code NotPostedDocumentsHandler.buildDsParams},
   * which looks up {@code document}, {@code accountingStatus}, {@code dateFrom}, {@code dateTo}).
   * An absent filter is simply an absent key: every one of these handlers defaults it.</p>
   */
  static Map<String, String> buildParams(JSONObject filters, String parentId, Integer offset,
      Integer limit, String orderBy) {
    Map<String, String> params = new HashMap<>();
    if (offset != null && limit != null) {
      params.put(JsonConstants.STARTROW_PARAMETER, String.valueOf(offset));
      params.put(JsonConstants.ENDROW_PARAMETER, String.valueOf(offset + limit - 1));
    }
    if (StringUtils.isNotBlank(orderBy)) {
      params.put(JsonConstants.SORTBY_PARAMETER, orderBy);
    }
    if (StringUtils.isNotBlank(parentId)) {
      params.put(McpConstants.PARAM_PARENT_ID, parentId);
    }
    if (filters != null) {
      for (Iterator<String> it = filters.keys(); it.hasNext();) {
        String key = it.next();
        String value = filters.optString(key, null);
        if (value != null) {
          params.put(key, value);
        }
      }
    }
    return params;
  }
}
