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

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Handles the {@code neo_widget} MCP tool (gap G4, ETP-4284), extracted from
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

  private McpWidgetHandler() {
  }

  /**
   * Resolve the selected widget to its backing {@code dashboard} spec entity, look up the
   * entity's {@link NeoHandler} via its {@code Java_Qualifier}, and invoke it with a GET
   * {@link NeoContext}.
   *
   * @param arguments tool arguments: {@code widget} (enum, required) and optional
   *                  {@code params} object (e.g. {@code {"range": "30d"}})
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

    NeoHandler handler = McpHookExecutor.resolveEntityHandler(sfEntity);
    if (handler == null) {
      return McpToolRouter.wrapAsErrorContent("No handler registered for widget '" + widget
          + "' (entity '" + entityName + "', qualifier '" + sfEntity.getJavaQualifier() + "').");
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
    JSONObject body = response.getBody();
    String text = body != null ? body.toString(2) : "{}";
    if (response.getHttpStatus() >= 400) {
      return McpToolRouter.wrapAsErrorContent(text);
    }
    return McpToolRouter.wrapAsTextContent(text);
  }
}
