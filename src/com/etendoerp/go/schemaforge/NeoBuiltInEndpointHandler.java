package com.etendoerp.go.schemaforge;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
      NeoImageHelper.handleImageRequest(pathInfo.entityName, method, request, response);
      return true;
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
          NeoRequestBodyParser.readRequestBody(request));
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
    if (METHOD_DELETE.equals(method)) {
      servlet.writeResponse(response, NeoCertificateHelper.handleCertificateDelete(request));
      return;
    }
    if ("POST".equals(method)) {
      servlet.writeResponse(response, NeoCertificateHelper.handleCertificateUpload(request));
      return;
    }
    servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
        "Certificate endpoint supports GET, POST and DELETE");
  }
}
