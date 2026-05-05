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
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoImageHelper;

/**
 * Routes NEO endpoints that are served directly by the servlet rather than by
 * an SF spec.
 */
class NeoBuiltInEndpointHandler {

  private static final String METHOD_DELETE = "DELETE";

  private final NeoServlet servlet;
  private final NeoDiscoveryHandler discoveryHandler;

  NeoBuiltInEndpointHandler(NeoServlet servlet, NeoDiscoveryHandler discoveryHandler) {
    this.servlet = servlet;
    this.discoveryHandler = discoveryHandler;
  }

  boolean handle(NeoServlet.NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (pathInfo.specName == null) {
      return handleDiscoveryEndpoint(method, response);
    }
    if ("image".equals(pathInfo.specName)) {
      return handleImageEndpoint(pathInfo, method, request, response);
    }
    if ("session".equals(pathInfo.specName)) {
      return handleSessionEndpoint(method, response);
    }
    if ("filters".equals(pathInfo.specName)) {
      handleFiltersEndpoint(pathInfo, method, request, response);
      return true;
    }
    if ("certificate".equals(pathInfo.specName)) {
      handleCertificateEndpoint(method, request, response);
      return true;
    }
    return false;
  }

  private boolean handleImageEndpoint(NeoServlet.NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if ("GET".equals(method) && StringUtils.isBlank(pathInfo.entityName)) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Image ID required");
      return true;
    }
    NeoImageHelper.handleImageRequest(pathInfo.entityName, method, request, response);
    return true;
  }

  private boolean handleDiscoveryEndpoint(String method, HttpServletResponse response)
      throws IOException {
    if (!"GET".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Discovery endpoint only supports GET");
      return true;
    }
    discoveryHandler.handleDiscovery(response);
    return true;
  }

  private boolean handleSessionEndpoint(String method, HttpServletResponse response)
      throws IOException {
    if (!"GET".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Session endpoint only supports GET");
      return true;
    }
    servlet.writeResponse(response, NeoSessionService.resolveSession());
    return true;
  }

  private void handleFiltersEndpoint(NeoServlet.NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (pathInfo.entityName == null) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Window name required: /sws/neo/filters/{window}");
      return;
    }
    if ("GET".equals(method)) {
      servlet.writeResponse(response, NeoFiltersService.getWindowPresets(pathInfo.entityName));
      return;
    }
    if (pathInfo.recordId == null) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Preset name required: /sws/neo/filters/{window}/{preset}");
      return;
    }
    handleFilterPresetMutation(pathInfo, method, request, response);
  }

  private void handleFilterPresetMutation(NeoServlet.NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if ("PUT".equals(method)) {
      NeoFiltersService.savePreset(pathInfo.entityName, pathInfo.recordId,
          NeoServlet.readRequestBody(request));
      OBDal.getInstance().flush();
      servlet.writeResponse(response, null);
      return;
    }
    if (METHOD_DELETE.equals(method)) {
      NeoFiltersService.deletePreset(pathInfo.entityName, pathInfo.recordId);
      OBDal.getInstance().flush();
      servlet.writeResponse(response, null);
      return;
    }
    servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
        "Filters endpoint only supports GET, PUT and DELETE");
  }

  private void handleCertificateEndpoint(String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if ("GET".equals(method)) {
      servlet.writeResponse(response, NeoCertificateHelper.handleCertificateGet(request));
      return;
    }
    if ("POST".equals(method)) {
      servlet.writeResponse(response, NeoCertificateHelper.handleCertificateUpload(request));
      return;
    }
    servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
        "Certificate endpoint supports GET and POST");
  }
}
