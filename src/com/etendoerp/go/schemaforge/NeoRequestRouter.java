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
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * Path-parsing and top-level spec dispatch collaborator for {@link NeoServlet}.
 * Resolves the {@link NeoPathInfo} and routes to the process / report / window
 * spec handlers based on the spec type, delegating sub-endpoint and CRUD work
 * back to the servlet.
 */
class NeoRequestRouter {

  private final NeoServlet servlet;

  NeoRequestRouter(NeoServlet servlet) {
    this.servlet = servlet;
  }

  NeoPathInfo parseRequestPath(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      NeoPathInfo pathInfo = NeoServletSupport.parsePath(request.getPathInfo());
      if (pathInfo != null) {
        return pathInfo;
      }
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Unable to parse request path");
    } catch (IllegalArgumentException e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    }
    return null;
  }

  void handleSpecRequest(NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    SFSpec spec = NeoServletSupport.findSpec(pathInfo.specName);
    if (spec == null) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Spec not found: " + pathInfo.specName);
      return;
    }

    String specType = spec.getSpecType();
    if ("P".equals(specType)) {
      handleProcessSpecRequest(spec, method, request, response);
      return;
    }
    if ("R".equals(specType)) {
      handleReportSpecRequest(spec, pathInfo, method, request, response);
      return;
    }
    handleWindowSpecRequest(spec, pathInfo, method, request, response);
  }

  /**
   * Handles process-type spec requests (specType = "P").
   * Checks process access, describes on GET, executes on POST.
   */
  void handleProcessSpecRequest(SFSpec spec, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    Process adProcess = spec.getProcess();
    if (adProcess != null && !servlet.authenticator.hasProcessAccess(adProcess.getId())) {
      servlet.sendError(response, HttpServletResponse.SC_FORBIDDEN,
          "Access denied to process for current role");
      return;
    }
    if ("GET".equals(method)) {
      if (adProcess == null) {
        servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Process spec has no linked AD_Process");
        return;
      }
      servlet.writeResponse(response, NeoProcessService.describeProcess(adProcess));
      return;
    }
    if (!"POST".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Process specs only support GET (describe) and POST (execute)");
      return;
    }
    servlet.processReportEndpoint.handleProcessSpec(spec, request, response);
  }

  /**
   * Handles report-type spec requests (specType = "R").
   *
   * <p>Report generation is NEO-native-handlers-only (ETP-4255). When the spec is backed
   * by a NEO report handler ({@code Java_Qualifier}), that handler serves the request.
   * Otherwise the spec is non-callable: there is no Jasper/AD_Process fallback. Both GET
   * and POST then return HTTP 200 with the canonical
   * {@code not_configured_for_report_generation} status body, identical to MCP discover
   * and the MCP report tool.</p>
   */
  void handleReportSpecRequest(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    // NEO-native handler dispatch (single-segment handlers such as aging-receivable).
    String reportHandlerQualifier = NeoReportCallability.resolveReportHandlerQualifier(spec);
    if (reportHandlerQualifier != null
        && dispatchReportHandler(reportHandlerQualifier, pathInfo, method, request, response)) {
      return;
    }
    // No NEO-native report handler: Jasper/AD_Process report execution has been removed.
    // Expose the report as non-callable with a stable status (HTTP 200), never an error.
    servlet.writeResponse(response,
        NeoResponse.ok(NeoReportCallability.buildNotConfiguredResponse(spec.getName())));
  }

  /**
   * Runs the custom handler and writes its result (or streams a CSV export when
   * the GET carries {@code export=csv}). Returns {@code true} when it handled the
   * request, {@code false} when there was no handler result to write.
   */
  private boolean dispatchReportHandler(String qualifier, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    JSONObject requestBody = NeoRequestBodyParser.parseOptionalJsonObject(NeoRequestBodyParser.readRequestBody(request));
    NeoContext handlerContext = NeoContext.builder()
        .specName(pathInfo.specName)
        .entityName(pathInfo.specName)
        .httpMethod(method)
        .requestBody(requestBody)
        .queryParams(servlet.extractQueryParams(request))
        .obContext(OBContext.getOBContext())
        .build();
    NeoResponse handlerResult = servlet.handleWithHooks(qualifier, handlerContext, request, response);
    if (handlerResult == null) {
      return false;
    }
    if ("GET".equals(method)
        && NeoCsvExportService.tryExport(handlerResult, handlerContext.getQueryParams(), response)) {
      return true;
    }
    servlet.writeResponse(response, handlerResult);
    return true;
  }

  /**
   * Handles window-type spec requests (specType = "W" or default).
   * Enforces window access, routes sub-endpoints (selectors, actions, evaluate-display,
   * callout, defaults), then falls through to CRUD entity handling.
   */
  void handleWindowSpecRequest(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    Window window = spec.getADWindow();
    if (window != null && !servlet.authenticator.hasWindowAccess(window.getId())) {
      servlet.sendError(response, HttpServletResponse.SC_FORBIDDEN,
          "Access denied to window for current role");
      return;
    }
    if (pathInfo.entityName == null) {
      if (!"GET".equals(method)) {
        servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Spec describe only supports GET");
        return;
      }
      servlet.discoveryHandler.handleSpecDescribe(response, spec);
      return;
    }
    if (servlet.subEndpointDispatcher.handleWindowSubEndpoint(spec, pathInfo, method, request, response)) {
      return;
    }
    // CRUD entity handling
    servlet.crudHandler.handleWindowEntityCrud(spec, pathInfo, method, request, response);
  }
}
