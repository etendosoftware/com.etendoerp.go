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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.WidgetPeriod;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * Handles the {@code etendo_widget} MCP tool (gap G4, ETP-4284), extracted from
 * {@link McpToolRouter} so the router stays within its authorized method budget and
 * the widget-handling logic lives in a single, dedicated collaborator (parity with the
 * generic-service rule for adding MCP tools).
 * <p>
 * Behaviour is identical to the previous in-router implementation: it resolves the
 * selected widget to its backing {@code dashboard} spec entity, looks up the entity's
 * {@link NeoHandler} via its {@code Java_Qualifier}, and invokes it with a GET
 * {@link NeoContext}. Spec/entity resolution and argument validation are delegated to
 * the shared {@link McpToolRouterSupport} helpers (same ones the router uses) so there
 * is no duplicated lookup logic. The widget data handlers (e.g. {@code WidgetKpisHandler})
 * are reused unmodified; this class is only the MCP wrapper that makes them
 * discoverable/invocable.
 */
final class McpWidgetHandler {

  private static final Logger log = LogManager.getLogger(McpWidgetHandler.class);

  static final String KEY_RANGE = "range";

  /** The range a widget computes when none is sent (WidgetKpisHandler, WidgetRevenueTrendHandler). */
  static final Map<String, String> DEFAULT_RANGE_BY_WIDGET = Map.of(
      McpConstants.WIDGET_KPIS, "ytd",
      McpConstants.WIDGET_REVENUE_TREND, "lastYear");

  /** The default window of a ranked widget called without a range (WidgetQueryPolicyRegistry). */
  static final Map<String, String> DEFAULT_WINDOW_BY_WIDGET = Map.of(
      McpConstants.WIDGET_BEST_PRODUCTS, "all completed sales invoices; trend = the month of the "
          + "latest invoice against the month before",
      McpConstants.WIDGET_BEST_SELLERS, "all completed sales invoices; trend = the month of the "
          + "latest invoice against the month before",
      McpConstants.WIDGET_TOP_CLIENTS, "the 12 months up to the latest completed sales invoice",
      McpConstants.WIDGET_RECENT_INVOICES, "the 30 days up to the latest completed sales invoice");

  private McpWidgetHandler() {
  }

  /**
   * Resolve the selected widget to its backing {@code dashboard} spec entity, look up the
   * entity's {@link NeoHandler} via its {@code Java_Qualifier}, and invoke it with a GET
   * {@link NeoContext}.
   *
   * @param arguments tool arguments: {@code widget} (enum, required) and optional
   *                  {@code params} object (e.g. {@code {"range": "last30d"}})
   * @return MCP text content with the widget JSON payload, or error content
   */
  static JSONObject handle(JSONObject arguments) throws Exception {
    McpToolRouterSupport.validateArgs(arguments, McpConstants.PARAM_WIDGET);
    String widget = arguments.getString(McpConstants.PARAM_WIDGET);

    String entityName = ToolRegistry.WIDGET_ENTITY_BY_NAME.get(widget);
    if (entityName == null) {
      return McpToolRouter.wrapAsErrorContent("Unknown widget '" + widget + "'. Valid widgets: "
          + String.join(", ", ToolRegistry.WIDGET_ENTITY_BY_NAME.keySet()));
    }

    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(McpConstants.SPEC_DASHBOARD);
    SFEntity sfEntity = McpToolRouterSupport.findIncludedEntity(spec.getId(), entityName);

    NeoHandler handler = NeoHandlerLookup.forEntity(sfEntity);
    if (handler == null) {
      return McpToolRouter.wrapAsErrorContent("No handler registered for widget '" + widget
          + "' (spec '" + McpConstants.SPEC_DASHBOARD + "', entity '" + entityName + "').");
    }

    // ETP-5852 (M-38): an unknown range used to fall back to the rolling 12 months in silence,
    // answering figures of another period than the one asked for.
    JSONObject rawParams = arguments.optJSONObject(McpConstants.PARAM_PARAMS);
    String asked = rawParams == null ? null
        : org.apache.commons.lang3.StringUtils.trimToNull(rawParams.optString(KEY_RANGE, null));
    if (asked != null && !WidgetPeriod.RANGES.contains(asked)) {
      throw McpRequestRefusals.invalidWidgetRange(widget, asked, WidgetPeriod.RANGES);
    }

    // Forward the optional params object as the query-param map (e.g. range), mirroring
    // the HTTP GET /sws/neo/dashboard/{entity}?range=... contract the handlers expect.
    Map<String, String> queryParams = new HashMap<>();
    JSONObject params = arguments.optJSONObject(McpConstants.PARAM_PARAMS);
    if (params != null) {
      Iterator<String> keys = params.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        queryParams.put(key, params.optString(key, null));
      }
    }

    NeoContext context = NeoContext.builder()
        .specName(McpConstants.SPEC_DASHBOARD)
        .entityName(entityName)
        .httpMethod("GET")
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .queryParams(queryParams)
        .build();

    NeoResponse response = handler.handle(context);
    if (response == null) {
      return McpToolRouter.wrapAsErrorContent("Widget '" + widget + "' returned no response.");
    }
    // ETP-5306: the JSONObject overloads, so a widget body is sanitised like every other result.
    JSONObject body = response.getBody();
    if (response.getHttpStatus() >= 400) {
      return McpToolRouter.wrapAsErrorContent(body);
    }
    addPeriod(body, widget, asked);
    return McpToolRouter.wrapAsTextContent(body);
  }

  /**
   * ETP-5852 (M-38 / M-42): says which period the figures cover and when they were computed, next
   * to {@code data}. Widgets are computed on every call (no cache), so {@code asOf} is now. A
   * widget whose figures depend on a range gets {@code range} (the effective one, the widget's
   * default when none was sent), {@code from} and {@code to}; a ranked widget called without a
   * range describes its own default window instead.
   */
  static void addPeriod(JSONObject body, String widget, String asked) {
    JSONObject response = body == null ? null : body.optJSONObject("response");
    if (response == null) {
      return;
    }
    try {
      JSONObject period = new JSONObject();
      java.time.LocalDate today = java.time.LocalDate.now();
      String range = asked != null ? asked : DEFAULT_RANGE_BY_WIDGET.get(widget);
      if (range != null) {
        period.put(KEY_RANGE, range);
        period.put("from", WidgetPeriod.start(range).toString());
        period.put("to", today.toString());
      } else if (DEFAULT_WINDOW_BY_WIDGET.containsKey(widget)) {
        period.put(KEY_RANGE, JSONObject.NULL);
        period.put("window", DEFAULT_WINDOW_BY_WIDGET.get(widget));
      } else {
        period.put("window", "point in time: the state now, whatever the date of the documents");
      }
      period.put("asOf", java.time.OffsetDateTime.now().withNano(0).toString());
      response.put("period", period);
    } catch (Exception e) {
      // The period is an annotation: the figures already computed are still answered.
      log.debug("Could not describe the period of widget {}: {}", widget, e.getMessage());
    }
  }
}
