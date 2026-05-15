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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Routes window sub-endpoint requests (selectors, actions, evaluate-display,
 * callout, defaults) and runs them through the hook pipeline. Pulled out of
 * {@link NeoServlet} to keep the servlet focused on HTTP wiring.
 */
class NeoSubEndpointDispatcher {

  private final NeoServlet servlet;

  NeoSubEndpointDispatcher(NeoServlet servlet) {
    this.servlet = servlet;
  }

  /**
   * Routes window sub-endpoint requests (selectors, actions, evaluate-display, callout, defaults).
   * Returns true if the request was handled by a sub-endpoint, false if it should fall through to CRUD.
   */
  boolean handleWindowSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (pathInfo.isSelector) {
      return handleSelectorSubEndpoint(spec, pathInfo, method, request, response);
    }
    if (pathInfo.isAction) {
      return handleActionSubEndpoint(spec, pathInfo, method, request, response);
    }
    if (pathInfo.isEvaluateDisplay) {
      return handleEvaluateDisplaySubEndpoint(spec, pathInfo, method, request, response);
    }
    if (pathInfo.isCallout) {
      return handleCalloutSubEndpoint(spec, pathInfo, method, request, response);
    }
    if (pathInfo.isDefaults) {
      return handleDefaultsSubEndpoint(spec, pathInfo, method, request, response);
    }
    return false;
  }

  private boolean handleSelectorSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"GET".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Selectors only support GET");
      return true;
    }
    return handleHookedSubEndpoint(new HookedSubEndpointRequest(spec, pathInfo.entityName,
        NeoEndpointType.SELECTOR, pathInfo.selectorField, method, null,
        () -> servlet.selectorEndpoint.handleSelector(spec.getId(), pathInfo, request)), response);
  }

  private boolean handleActionSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"POST".equals(method) && !"GET".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Actions support GET (list) and POST (execute)");
      return true;
    }
    ActionDispatchParams actionParams = resolveActionDispatchParams(pathInfo, method, request,
        response);
    if (actionParams == null) {
      return true;
    }
    SFEntity actionEntity = servlet.findEntity(spec.getId(), pathInfo.entityName);
    if (actionEntity == null) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Entity not found in spec: " + pathInfo.entityName);
      return true;
    }
    return handleHookedSubEndpoint(new HookedSubEndpointRequest(spec, pathInfo.entityName,
        NeoEndpointType.ACTION, pathInfo.actionName, method, actionParams,
        () -> servlet.buttonHandler.handleButtonAction(pathInfo, method, request, actionEntity)), response);
  }

  private boolean handleEvaluateDisplaySubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"POST".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Method not allowed. Use POST.");
      return true;
    }
    return handleHookedSubEndpoint(new HookedSubEndpointRequest(spec, pathInfo.entityName,
        NeoEndpointType.EVALUATE_DISPLAY, null, method, null,
        () -> servlet.displayLogicHandler.handleEvaluateDisplay(spec, pathInfo, request)), response);
  }

  private boolean handleCalloutSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"POST".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Callout endpoint only supports POST");
      return true;
    }
    return handleHookedSubEndpoint(new HookedSubEndpointRequest(spec, pathInfo.entityName,
        NeoEndpointType.CALLOUT, null, method, null,
        () -> servlet.calloutEndpoint.handleCallout(spec, pathInfo, request)), response);
  }

  private boolean handleDefaultsSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"GET".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Defaults endpoint only supports GET");
      return true;
    }
    return handleHookedSubEndpoint(new HookedSubEndpointRequest(spec, pathInfo.entityName,
        NeoEndpointType.DEFAULTS, null, method, null,
        () -> servlet.defaultsEndpoint.handleDefaults(spec, pathInfo, request)), response);
  }

  private boolean handleHookedSubEndpoint(HookedSubEndpointRequest request,
      HttpServletResponse response) throws IOException {
    NeoResponse endpointResult = request.actionParams == null
        ? servlet.hookDispatcher.dispatchWithHooks(request.spec, request.entityName, request.endpointType,
            request.fieldName, request.method, request.defaultAction)
        : servlet.hookDispatcher.dispatchWithHooks(request.spec, request.entityName, request.endpointType,
            request.fieldName, request.method, request.actionParams, request.defaultAction);
    servlet.writeResponse(response, endpointResult);
    return true;
  }

  private ActionDispatchParams resolveActionDispatchParams(NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    ActionDispatchParams actionParams = new ActionDispatchParams(pathInfo.recordId, null);
    if (!"POST".equals(method)) {
      return actionParams;
    }
    try {
      String bodyStr = NeoRequestBodyParser.readRequestBody(request);
      request.setAttribute(NeoServlet.ACTION_REQUEST_BODY_ATTR, bodyStr);
      return new ActionDispatchParams(pathInfo.recordId, NeoRequestBodyParser.parseOptionalJsonObject(bodyStr));
    } catch (Exception e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid JSON body: " + e.getMessage());
      return null;
    }
  }

  static class ActionDispatchParams {
    final String recordId;
    final JSONObject requestBody;

    ActionDispatchParams(String recordId, JSONObject requestBody) {
      this.recordId = recordId;
      this.requestBody = requestBody;
    }
  }

  private static class HookedSubEndpointRequest {
    final SFSpec spec;
    final String entityName;
    final NeoEndpointType endpointType;
    final String fieldName;
    final String method;
    final ActionDispatchParams actionParams;
    final java.util.function.Supplier<NeoResponse> defaultAction;

    HookedSubEndpointRequest(SFSpec spec, String entityName, NeoEndpointType endpointType,
        String fieldName, String method, ActionDispatchParams actionParams,
        java.util.function.Supplier<NeoResponse> defaultAction) {
      this.spec = spec;
      this.entityName = entityName;
      this.endpointType = endpointType;
      this.fieldName = fieldName;
      this.method = method;
      this.actionParams = actionParams;
      this.defaultAction = defaultAction;
    }
  }
}
