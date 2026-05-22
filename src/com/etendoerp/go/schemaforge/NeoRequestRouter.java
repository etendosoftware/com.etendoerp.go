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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Path-parsing and top-level spec dispatch collaborator for {@link NeoServlet}.
 * Resolves the {@link NeoPathInfo} and routes to the process / report / window
 * spec handlers based on the spec type, delegating sub-endpoint and CRUD work
 * back to the servlet.
 */
class NeoRequestRouter {

  private static final Logger log = LogManager.getLogger(NeoRequestRouter.class);

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
   * Checks for custom handler qualifier first, then falls back to standard Jasper report flow.
   */
  void handleReportSpecRequest(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    // Check for a custom NeoHandler qualifier on any entity of this report spec
    String reportHandlerQualifier = null;
    try {
      OBCriteria<SFEntity> qCriteria = OBDal.getInstance().createCriteria(SFEntity.class);
      qCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
      for (SFEntity qEntity : qCriteria.list()) {
        if (StringUtils.isNotBlank(qEntity.getJavaQualifier())) {
          reportHandlerQualifier = qEntity.getJavaQualifier();
          break;
        }
      }
    } catch (Exception e) {
      log.warn("Error checking report handler qualifier for spec '{}': {}", spec.getName(), e.getMessage());
    }
    if (reportHandlerQualifier != null) {
      JSONObject requestBody = NeoRequestBodyParser.parseOptionalJsonObject(NeoRequestBodyParser.readRequestBody(request));
      NeoContext handlerContext = NeoContext.builder()
          .specName(pathInfo.specName)
          .entityName(pathInfo.specName)
          .httpMethod(method)
          .requestBody(requestBody)
          .queryParams(servlet.extractQueryParams(request))
          .obContext(OBContext.getOBContext())
          .build();
      NeoResponse handlerResult = servlet.handleWithHooks(reportHandlerQualifier, handlerContext, request, response);
      if (handlerResult != null) {
        servlet.writeResponse(response, handlerResult);
        return;
      }
    }
    Process adReportProcess = spec.getProcess();
    if (adReportProcess != null && !servlet.authenticator.hasProcessAccess(adReportProcess.getId())) {
      servlet.sendError(response, HttpServletResponse.SC_FORBIDDEN,
          "Access denied to report for current role");
      return;
    }
    if ("GET".equals(method)) {
      if (adReportProcess == null) {
        servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Report spec has no linked AD_Process");
        return;
      }
      servlet.writeResponse(response, NeoReportService.describeReport(adReportProcess));
      return;
    }
    if (!"POST".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Report specs support GET (describe) and POST (generateReport)");
      return;
    }
    servlet.processReportEndpoint.handleReportSpec(spec, request, response);
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
