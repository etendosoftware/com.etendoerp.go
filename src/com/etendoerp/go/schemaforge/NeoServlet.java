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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.auth0.jwt.interfaces.DecodedJWT;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.smf.securewebservices.utils.SecureWebServicesUtils;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.go.schemaforge.util.NeoDiscoveryHelper;
import com.etendoerp.go.schemaforge.util.NeoDisplayLogicHelper;
import com.etendoerp.go.schemaforge.util.NeoImageHelper;
import org.openbravo.client.application.ApplicationUtils;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import com.etendoerp.go.schemaforge.util.NeoProcessReportHelper;
import com.etendoerp.go.schemaforge.util.NeoListIdentifierHelper;
import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;

/**
 * NEO Headless 2.0 servlet.
 *
 * Mapped to /sws/neo/* via AD_SERVLET registration.
 * Uses JWT authentication via SecureWebServices (same as EtendoGo).
 *
 * URL pattern:
 *   Window specs: /sws/neo/{specName}/{entityName}[/{id}]
 *   Process specs: /sws/neo/{specName}  (POST only)
 *
 * Resolves Spec/Entity/Field records (ETGO_SF_Spec, ETGO_SF_Entity, ETGO_SF_Field)
 * which point directly to AD_Window, AD_Tab, and AD_Column.
 * Hooks are discovered via CDI using the Java_Qualifier on ETGO_SF_Entity.
 */
public class NeoServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(NeoServlet.class);
  private static final String HOOK_ERROR_MSG = "An internal error occurred while processing the hook handler";

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    processRequest(request, response, "GET");
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    processRequest(request, response, "POST");
  }

  @Override
  public void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    processRequest(request, response, "PUT");
  }

  @Override
  public void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
    processRequest(request, response, "DELETE");
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if ("PATCH".equalsIgnoreCase(request.getMethod())) {
      processRequest(request, response, "PATCH");
    } else {
      try {
        super.service(request, response);
      } catch (Exception e) {
        log.error("Error in NeoServlet.service", e);
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
      }
    }
  }

  private void processRequest(HttpServletRequest request, HttpServletResponse response,
      String method) throws IOException {
    // 1. Authenticate via JWT
    try {
      authenticateJwt(request);
    } catch (OBException e) {
      // OBException messages are safe to expose (we control them)
      log.warn("Unauthorized NEO request: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return;
    } catch (Exception e) {
      // Other exceptions (JWT decode failures, NPEs) — don't leak internals
      log.warn("Unauthorized NEO request: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return;
    }

    // 2. Parse the path
    NeoPathInfo pathInfo = parsePath(request.getPathInfo());

    // 3. Resolve spec, entity, and tab
    try {
      OBContext.setAdminMode();

      if (pathInfo.specName == null) {
        handleDiscoveryMode(method, response);
        return;
      }

      // Built-in image endpoint: /sws/neo/image[/{id}]
      if ("image".equals(pathInfo.specName)) {
        String resolvedImageId = pathInfo.recordId != null ? pathInfo.recordId : pathInfo.entityName;
        NeoImageHelper.handleImageRequest(resolvedImageId, method, request, response);
        return;
      }

      SFSpec spec = findSpec(pathInfo.specName);
      if (spec == null) {
        sendError(response, HttpServletResponse.SC_NOT_FOUND,
            "Spec not found: " + pathInfo.specName);
        return;
      }

      if (routeBySpecType(spec, pathInfo.specName, method, request, response)) {
        return;
      }

      // Check window access
      Window window = spec.getADWindow();
      if (window != null && !NeoAccessHelper.hasWindowAccess(window.getId())) {
        sendError(response, HttpServletResponse.SC_FORBIDDEN,
            "Access denied to window for current role");
        return;
      }

      if (pathInfo.entityName == null) {
        handleSpecDescribeMode(spec, method, response);
        return;
      }

      if (routeSubPath(spec, pathInfo, method, request, response)) {
        return;
      }

      handleEntityCrudRequest(spec, pathInfo, method, request, response);

    } catch (NeoRequestException e) {
      sendError(response, e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
      log.error("Error processing NEO request: {}", e.getMessage(), e);
      // Intentional: generic message avoids leaking internal details (stack traces,
      // column names, query fragments) to API consumers. Full details are in the log.
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while processing the request.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void handleDiscoveryMode(String method, HttpServletResponse response) throws IOException {
    if (!"GET".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Discovery endpoint only supports GET");
      return;
    }
    writeResponse(response, NeoDiscoveryHelper.handleDiscovery());
  }

  private void handleSpecDescribeMode(SFSpec spec, String method, HttpServletResponse response)
      throws IOException, NeoRequestException {
    requireMethod(method, "GET", "Spec describe only supports GET");
    writeResponse(response, NeoDiscoveryHelper.handleSpecDescribe(spec));
  }

  private boolean routeBySpecType(SFSpec spec, String specName, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException, JSONException {
    String specType = spec.getSpecType();
    if ("P".equals(specType)) {
      routeProcessSpec(spec, method, request, response);
      return true;
    }
    if ("R".equals(specType)) {
      routeReportSpec(spec, specName, method, request, response);
      return true;
    }
    return false;
  }

  private void routeProcessSpec(SFSpec spec, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    Process adProcess = NeoAccessHelper.resolveProcess(spec);
    if (adProcess != null && !NeoAccessHelper.hasProcessAccess(adProcess.getId())) {
      sendError(response, HttpServletResponse.SC_FORBIDDEN,
          "Access denied to process for current role");
      return;
    }
    if ("GET".equals(method)) {
      if (adProcess == null) {
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Process spec has no linked AD_Process");
        return;
      }
      writeResponse(response, NeoProcessService.describeProcess(adProcess));
      return;
    }
    if (!"POST".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Process specs only support GET (describe) and POST (execute)");
      return;
    }
    NeoProcessReportHelper.handleProcessSpec(spec, request, response);
  }

  private void routeReportSpec(SFSpec spec, String specName, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException, JSONException {
    String reportHandlerQualifier = NeoProcessReportHelper.resolveReportHandlerQualifier(spec);
    if (reportHandlerQualifier != null) {
      JSONObject requestBody = null;
      String bodyStr = new String(request.getInputStream().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      if (StringUtils.isNotBlank(bodyStr)) {
        requestBody = new JSONObject(bodyStr);
      }
      NeoContext handlerContext = NeoContext.builder()
          .specName(specName)
          .entityName(specName)
          .httpMethod(method)
          .requestBody(requestBody)
          .queryParams(extractQueryParams(request))
          .obContext(OBContext.getOBContext())
          .build();
      NeoResponse handlerResult = handleWithHooks(reportHandlerQualifier, handlerContext, request, response);
      if (handlerResult != null) {
        writeResponse(response, handlerResult);
        return;
      }
    }

    Process adReportProcess = NeoAccessHelper.resolveProcess(spec);
    if (adReportProcess != null && !NeoAccessHelper.hasProcessAccess(adReportProcess.getId())) {
      sendError(response, HttpServletResponse.SC_FORBIDDEN,
          "Access denied to report for current role");
      return;
    }
    if ("GET".equals(method)) {
      if (adReportProcess == null) {
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Report spec has no linked AD_Process");
        return;
      }
      writeResponse(response, NeoReportService.describeReport(adReportProcess));
      return;
    }
    if (!"POST".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Report specs support GET (describe) and POST (generateReport)");
      return;
    }
    NeoProcessReportHelper.handleReportSpec(spec, request, response);
  }

  private boolean routeSubPath(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response)
      throws IOException, NeoRequestException {
    if (pathInfo.isSelector) {
      return routeSelectorSubPath(spec, pathInfo, method, request, response);
    }
    if (pathInfo.isAction) {
      return routeActionSubPath(spec, pathInfo, method, request, response);
    }
    if (pathInfo.isEvaluateDisplay) {
      return routeEvaluateDisplaySubPath(spec, pathInfo, method, request, response);
    }
    if (pathInfo.isCallout) {
      return routeCalloutSubPath(spec, pathInfo, method, request, response);
    }
    if (pathInfo.isDefaults) {
      return routeDefaultsSubPath(spec, pathInfo, method, request, response);
    }
    return false;
  }

  private boolean routeSelectorSubPath(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response)
      throws IOException, NeoRequestException {
    requireMethod(method, "GET", "Selectors only support GET");
    NeoResponse selectorResult = dispatchWithHooks(spec, pathInfo.entityName,
        NeoEndpointType.SELECTOR, pathInfo.selectorField, method,
        () -> handleSelector(spec.getId(), pathInfo, request));
    writeResponse(response, selectorResult);
    return true;
  }

  private boolean routeEvaluateDisplaySubPath(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response)
      throws IOException, NeoRequestException {
    requireMethod(method, "POST", "Method not allowed. Use POST.");
    NeoResponse evalResult = dispatchWithHooks(spec, pathInfo.entityName,
        NeoEndpointType.EVALUATE_DISPLAY, null, method,
        () -> NeoDisplayLogicHelper.handleEvaluateDisplay(spec, pathInfo, request));
    writeResponse(response, evalResult);
    return true;
  }

  private void requireMethod(String actualMethod, String expectedMethod, String message)
      throws NeoMethodNotAllowedException {
    if (!expectedMethod.equals(actualMethod)) {
      throw new NeoMethodNotAllowedException(message);
    }
  }

  private boolean routeCalloutSubPath(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"POST".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Callout endpoint only supports POST");
      return true;
    }
    NeoResponse calloutResult = dispatchWithHooks(spec, pathInfo.entityName,
        NeoEndpointType.CALLOUT, null, method,
        () -> handleCallout(spec, pathInfo, request));
    writeResponse(response, calloutResult);
    return true;
  }

  private boolean routeDefaultsSubPath(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"GET".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Defaults endpoint only supports GET");
      return true;
    }
    NeoResponse defaultsResult = dispatchWithHooks(spec, pathInfo.entityName,
        NeoEndpointType.DEFAULTS, null, method,
        () -> handleDefaults(spec, pathInfo, request));
    writeResponse(response, defaultsResult);
    return true;
  }

  private boolean routeActionSubPath(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"POST".equals(method) && !"GET".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Actions support GET (list) and POST (execute)");
      return true;
    }
    JSONObject actionBody = null;
    if ("POST".equals(method)) {
      try {
        byte[] bodyBytes = request.getInputStream().readAllBytes();
        String bodyStr = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
        if (StringUtils.isNotBlank(bodyStr)) {
          actionBody = new JSONObject(bodyStr);
        }
        // Reset the input stream for handleButtonAction fallback
        // Wrap request to allow re-reading the body
        final byte[] cachedBody = bodyBytes;
        request = new javax.servlet.http.HttpServletRequestWrapper(request) {
          @Override
          public javax.servlet.ServletInputStream getInputStream() {
            return new javax.servlet.ServletInputStream() {
              private final java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(cachedBody);
              @Override public int read() { return bais.read(); }
              @Override public boolean isFinished() { return bais.available() == 0; }
              @Override public boolean isReady() { return true; }
              @Override public void setReadListener(javax.servlet.ReadListener l) {
                // No async support needed for cached body re-reading
              }
            };
          }
        };
      } catch (Exception ignored) {
        // Body parsing is optional; action proceeds without it
      }
    }
    final HttpServletRequest wrappedRequest = request;
    ActionDispatchParams actionParams = new ActionDispatchParams(
        pathInfo.recordId, actionBody);
    NeoResponse actionResult = dispatchWithHooks(spec, pathInfo.entityName,
        NeoEndpointType.ACTION, pathInfo.actionName, method, actionParams,
        () -> handleButtonAction(spec, pathInfo, method, wrappedRequest));
    writeResponse(response, actionResult);
    return true;
  }

  private void handleEntityCrudRequest(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    SFEntity entity = findEntity(spec.getId(), pathInfo.entityName);
    if (entity == null) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Entity not found in spec: " + pathInfo.entityName);
      return;
    }

    if (!isMethodEnabled(entity, method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          method + " not enabled for " + pathInfo.entityName);
      return;
    }

    Tab adTab = getAdTab(entity);
    Map<String, String> queryParams = extractQueryParams(request);

    NeoContext neoContext = NeoContext.builder()
        .specName(pathInfo.specName)
        .entityName(pathInfo.entityName)
        .httpMethod(method)
        .recordId(pathInfo.recordId)
        .queryParams(queryParams)
        .adTab(adTab)
        .sfEntity(entity)
        .obContext(OBContext.getOBContext())
        .endpointType(NeoEndpointType.CRUD)
        .build();

    if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
      try {
        String bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (StringUtils.isNotBlank(bodyStr)) {
          neoContext = NeoContext.builder()
              .specName(neoContext.getSpecName())
              .entityName(neoContext.getEntityName())
              .httpMethod(neoContext.getHttpMethod())
              .recordId(neoContext.getRecordId())
              .requestBody(new JSONObject(bodyStr))
              .queryParams(neoContext.getQueryParams())
              .adTab(neoContext.getAdTab())
              .sfEntity(neoContext.getSfEntity())
              .obContext(neoContext.getObContext())
              .endpointType(neoContext.getEndpointType())
              .build();
        }
      } catch (Exception e) {
        sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage());
        return;
      }
    }

    String javaQualifier = entity.getJavaQualifier();
    NeoResponse neoResponse;
    if (StringUtils.isNotBlank(javaQualifier)) {
      neoResponse = handleWithHooks(javaQualifier, neoContext, request, response);
    } else {
      neoResponse = handleDefault(neoContext, request, response);
    }

    if (neoResponse != null) {
      writeResponse(response, neoResponse);
    }
  }

  private void authenticateJwt(HttpServletRequest request) throws Exception {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new OBException("Missing or invalid Authorization header");
    }
    String token = authHeader.substring(7);
    DecodedJWT decodedToken = SecureWebServicesUtils.decodeToken(token);

    String userId = decodedToken.getClaim("user").asString();
    String roleId = decodedToken.getClaim("role").asString();
    String orgId = decodedToken.getClaim("organization").asString();
    String warehouseId = decodedToken.getClaim("warehouse").asString();
    String clientId = decodedToken.getClaim("client").asString();

    if (StringUtils.isAnyBlank(userId, roleId, orgId, clientId)) {
      throw new OBException("Invalid token: missing required claims");
    }

    OBContext context = SecureWebServicesUtils.createContext(userId, roleId, orgId, warehouseId, clientId);
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);
  }

  /**
   * Parse the path into spec name, entity name, and optional record ID or selector info.
   * Expected formats:
   *   /{specName}                              (process specs, POST only)
   *   /{specName}/{entityName}[/{id}]          (window specs)
   *   /{specName}/{entityName}/selectors[/{columnName}]
   *   /{specName}/{entityName}/{recordId}/action[/{columnName}]
   * <p>This method never throws — null, empty, and single-segment paths return discovery/process
   * mode results instead of failing.
   */
  NeoPathInfo parsePath(String pathInfo) {
    // Discovery mode: no path or root path
    if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
      return new NeoPathInfo(null, null, null);
    }

    String path = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    String[] parts = path.split("/");

    if (parts.length < 1 || parts[0].isEmpty()) {
      return new NeoPathInfo(null, null, null);
    }

    String specName = parts[0];

    // Single segment: spec-only path (for process specs)
    if (parts.length == 1) {
      return new NeoPathInfo(specName, null, null);
    }

    String entityName = parts[1];

    // Check for /selectors sub-path
    if (parts.length >= 3 && "selectors".equals(parts[2])) {
      String selectorField = parts.length >= 4 ? parts[3] : null;
      return new NeoPathInfo(specName, entityName, null, true, selectorField);
    }

    // Check for /callout sub-path
    if (parts.length >= 3 && "callout".equals(parts[2])) {
      return new NeoPathInfo(specName, entityName, null, false, null, false, null, false, true, false);
    }

    // Check for /defaults sub-path
    if (parts.length >= 3 && "defaults".equals(parts[2])) {
      return new NeoPathInfo(specName, entityName, null, false, null, false, null, false, false, true);
    }

    // Check for /evaluate-display sub-path
    if (parts.length >= 3 && "evaluate-display".equals(parts[2])) {
      return new NeoPathInfo(specName, entityName, null, false, null, false, null, true);
    }

    // Check for /{spec}/{entity}/{recordId}/action[/{columnName}]
    if (parts.length >= 4 && "action".equals(parts[3])) {
      String actionName = parts.length >= 5 ? parts[4] : null;
      return new NeoPathInfo(specName, entityName, parts[2], false, null, true, actionName);
    }

    String recordId = parts.length >= 3 ? parts[2] : null;
    return new NeoPathInfo(specName, entityName, recordId);
  }

  /**
   * Find an active ETGO_SF_Spec by name.
   */
  private SFSpec findSpec(String specName) {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_NAME, specName));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    List<SFSpec> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Find an active, included ETGO_SF_Entity by parent spec ID and entity name.
   */
  private SFEntity findEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, entityName));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Check if the given HTTP method is enabled on the entity.
   */
  private boolean isMethodEnabled(SFEntity entity, String method) {
    switch (method) {
      case "GET":
        return Boolean.TRUE.equals(entity.isGet())
            || Boolean.TRUE.equals(entity.isGetByID());
      case "POST":
        return Boolean.TRUE.equals(entity.isPost());
      case "PUT":
        return Boolean.TRUE.equals(entity.isPut());
      case "PATCH":
        return Boolean.TRUE.equals(entity.isPatch());
      case "DELETE":
        return Boolean.TRUE.equals(entity.isDelete());
      default:
        return false;
    }
  }

  /**
   * Get the AD_Tab linked to the entity.
   */
  private Tab getAdTab(SFEntity entity) {
    return entity.getADTab();
  }

  private Map<String, String> extractQueryParams(HttpServletRequest request) {
    Map<String, String> params = new HashMap<>();
    Enumeration<String> names = request.getParameterNames();
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      params.put(name, request.getParameter(name));
    }
    return params;
  }

  /**
   * Handle request with CDI hooks discovered via Java_Qualifier.
   * The handler acts as a pre+post hook (the handler decides via convention).
   */
  private NeoResponse handleWithHooks(String javaQualifier, NeoContext context,
      HttpServletRequest request, HttpServletResponse response) {
    try {
      NeoHandler handler = lookupHandler(javaQualifier);
      if (handler == null) {
        log.warn("No handler found for qualifier '{}', falling back to default", javaQualifier);
        return handleDefault(context, request, response);
      }

      // Pre-hook
      NeoResponse preResult = handler.handle(context);
      if (preResult != null) {
        context.setPreviousResult(preResult);
        NeoResponse afterResult = handler.afterHandle(context);
        return afterResult != null ? afterResult : preResult;
      }

      // Default service
      NeoResponse defaultResult = handleDefault(context, request, response);

      // Post-hook
      context.setPreviousResult(defaultResult);
      NeoResponse afterResult = handler.afterHandle(context);
      return afterResult != null ? afterResult : defaultResult;
    } catch (Exception e) {
      log.error("Error executing hook handler: {}", javaQualifier, e);
      return NeoResponse.error(500, HOOK_ERROR_MSG);
    }
  }

  private NeoHandler lookupHandler(String qualifier) {
    try {
      for (NeoHandler handler : WeldUtils.getInstances(NeoHandler.class)) {
        javax.inject.Named named = handler.getClass().getAnnotation(javax.inject.Named.class);
        if (named != null && qualifier.equals(named.value())) {
          return handler;
        }
      }
      log.warn("No NeoHandler found with @Named(\"{}\")", qualifier);
      return null;
    } catch (Exception e) {
      log.error("Failed to lookup handler with qualifier: {}", qualifier, e);
      return null;
    }
  }

  /**
   * Execute a sub-endpoint through the hook pipeline.
   * If a handler exists for the entity qualifier, it gets pre/post hook calls.
   *
   * @param spec          the SF spec
   * @param entityName    the entity name within the spec
   * @param endpointType  which sub-endpoint is being invoked
   * @param fieldName     optional field name (selector column, action column, etc.)
   * @param httpMethod    the HTTP method
   * @param defaultAction supplier that executes the default service logic
   * @return the final NeoResponse
   */
  private NeoResponse dispatchWithHooks(
      SFSpec spec, String entityName,
      NeoEndpointType endpointType, String fieldName,
      String httpMethod,
      java.util.function.Supplier<NeoResponse> defaultAction) {

    // Try to resolve entity for its qualifier
    SFEntity entity = findEntity(spec.getId(), entityName);
    String qualifier = (entity != null) ? entity.getJavaQualifier() : null;

    if (StringUtils.isBlank(qualifier)) {
      return defaultAction.get();
    }

    NeoHandler handler = lookupHandler(qualifier);
    if (handler == null) {
      return defaultAction.get();
    }

    // Build hook context
    Tab adTab = (entity != null) ? getAdTab(entity) : null;
    NeoContext hookCtx = NeoContext.builder()
        .specName(spec.getName())
        .entityName(entityName)
        .httpMethod(httpMethod)
        .endpointType(endpointType)
        .fieldName(fieldName)
        .sfEntity(entity)
        .adTab(adTab)
        .obContext(OBContext.getOBContext())
        .build();

    try {
      // Pre-hook
      NeoResponse preResult = handler.handle(hookCtx);
      if (preResult != null) {
        hookCtx.setPreviousResult(preResult);
        NeoResponse afterResult = handler.afterHandle(hookCtx);
        return afterResult != null ? afterResult : preResult;
      }

      // Default service
      NeoResponse defaultResult = defaultAction.get();

      // Post-hook
      hookCtx.setPreviousResult(defaultResult);
      NeoResponse afterResult = handler.afterHandle(hookCtx);
      return afterResult != null ? afterResult : defaultResult;

    } catch (Exception e) {
      log.error("Error in hook dispatch for {}/{}: {}",
          endpointType, entityName, e.getMessage(), e);
      return NeoResponse.error(500, HOOK_ERROR_MSG);
    }
  }

  /**
   * Groups the extra parameters needed by action endpoint hook dispatch,
   * reducing the method parameter count.
   */
  private static class ActionDispatchParams {
    final String recordId;
    final JSONObject requestBody;

    ActionDispatchParams(String recordId, JSONObject requestBody) {
      this.recordId = recordId;
      this.requestBody = requestBody;
    }
  }

  /**
   * Overload that passes recordId and requestBody to the hook context.
   * Used by action endpoints where the record context matters.
   */
  private NeoResponse dispatchWithHooks(
      SFSpec spec, String entityName,
      NeoEndpointType endpointType, String fieldName,
      String httpMethod, ActionDispatchParams actionParams,
      java.util.function.Supplier<NeoResponse> defaultAction) {

    SFEntity entity = findEntity(spec.getId(), entityName);
    String qualifier = (entity != null) ? entity.getJavaQualifier() : null;

    if (StringUtils.isBlank(qualifier)) {
      return defaultAction.get();
    }

    NeoHandler handler = lookupHandler(qualifier);
    if (handler == null) {
      return defaultAction.get();
    }

    Tab adTab = (entity != null) ? getAdTab(entity) : null;
    NeoContext hookCtx = NeoContext.builder()
        .specName(spec.getName())
        .entityName(entityName)
        .httpMethod(httpMethod)
        .endpointType(endpointType)
        .fieldName(fieldName)
        .recordId(actionParams.recordId)
        .requestBody(actionParams.requestBody)
        .sfEntity(entity)
        .adTab(adTab)
        .obContext(OBContext.getOBContext())
        .build();

    try {
      NeoResponse preResult = handler.handle(hookCtx);
      if (preResult != null) {
        hookCtx.setPreviousResult(preResult);
        NeoResponse afterResult = handler.afterHandle(hookCtx);
        return afterResult != null ? afterResult : preResult;
      }

      NeoResponse defaultResult = defaultAction.get();

      hookCtx.setPreviousResult(defaultResult);
      NeoResponse afterResult = handler.afterHandle(hookCtx);
      return afterResult != null ? afterResult : defaultResult;

    } catch (Exception e) {
      log.error("Error in hook dispatch for {}/{}: {}",
          endpointType, entityName, e.getMessage(), e);
      return NeoResponse.error(500, HOOK_ERROR_MSG);
    }
  }

  private NeoResponse handleDefault(NeoContext context, HttpServletRequest request,
      HttpServletResponse response) {
    try {
      Tab adTab = context.getAdTab();
      if (adTab == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "No AD_Tab linked to entity: " + context.getEntityName());
      }

      String dalEntityName = adTab.getTable().getName();
      DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();

      // Build field filter from ETGO_SF_FIELD configuration (cached for this request)
      NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(
          context.getSfEntity(), dalEntityName);

      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.ENTITYNAME, dalEntityName);
      params.put(JsonConstants.TAB_PARAMETER, adTab.getId());
      params.put(JsonConstants.WINDOW_ID, adTab.getWindow().getId());
      params.put(JsonConstants.NO_ACTIVE_FILTER, "true");

      if (context.getRecordId() != null) {
        params.put(JsonConstants.ID, context.getRecordId());
      }

      // Copy query params (filters, pagination, sorting)
      if (context.getQueryParams() != null) {
        for (Map.Entry<String, String> entry : context.getQueryParams().entrySet()) {
          params.put(entry.getKey(), entry.getValue());
        }
      }

      // Build where clause: tab's own HQL + parent filter for child tabs
      StringBuilder whereClause = new StringBuilder();

      String parentId = context.getQueryParams() != null
          ? context.getQueryParams().get("parentId")
          : null;

      String tabWhere = adTab.getHqlwhereclause();
      if (StringUtils.isNotBlank(tabWhere)) {
        // Substitute @PLACEHOLDER@ style tokens used in classic UI tab where clauses
        // (e.g. @FIN_Payment_ID@) with the actual parentId so indirect parent-child
        // relationships — where there is no direct FK to the parent table — work in NEO.
        if (parentId != null && tabWhere.contains("@")) {
          tabWhere = tabWhere.replaceAll("@[A-Za-z_]+@", "'" + parentId.replace("'", "''") + "'");
        }
        whereClause.append("(").append(tabWhere).append(")");
      }
      if (parentId != null && adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
        String parentFilter = NeoTypeCoercionHelper.buildParentWhereClause(adTab, parentId);
        if (StringUtils.isNotBlank(parentFilter)) {
          if (whereClause.length() > 0) {
            whereClause.append(" and ");
          }
          whereClause.append("(").append(parentFilter).append(")");
        }
      }

      if (whereClause.length() > 0) {
        params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, whereClause.toString());
        params.put(JsonConstants.USE_ALIAS, "true");
      }

      // Set pagination defaults if not provided
      if (!params.containsKey(JsonConstants.STARTROW_PARAMETER)) {
        params.put(JsonConstants.STARTROW_PARAMETER, "0");
      }
      if (!params.containsKey(JsonConstants.ENDROW_PARAMETER)) {
        params.put(JsonConstants.ENDROW_PARAMETER, "100");
      }

      String result;
      switch (context.getHttpMethod()) {
        case "GET":
          result = jsonService.fetch(params);
          break;
        case "POST": {
          // Validate: POST must NOT have a recordId (creates don't target an existing record)
          if (context.getRecordId() != null) {
            return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
                "POST (create) must not include a record ID in the URL");
          }
          // Resolve parentId from body: map generic "parentId" to the actual FK property name
          // (e.g., parentId → salesOrder for C_OrderLine, parentId → invoice for C_InvoiceLine)
          JSONObject requestBody = context.getRequestBody();
          String parentIdValue = null;
          if (requestBody != null && requestBody.has("parentId")) {
            parentIdValue = requestBody.getString("parentId");
            requestBody.remove("parentId");
            // Find the link-to-parent column and resolve its DAL property name
            if (adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
              Entity dalEnt = ModelProvider.getInstance().getEntityByTableName(adTab.getTable().getDBTableName());
              if (dalEnt != null) {
                for (Column col : adTab.getTable().getADColumnList()) {
                  if (col.isLinkToParentColumn() && col.isActive()) {
                    try {
                      Property prop = dalEnt.getPropertyByColumnName(col.getDBColumnName());
                      if (prop != null) {
                        requestBody.put(prop.getName(), parentIdValue);
                        break;
                      }
                    } catch (Exception ignored) {}
                  }
                }
              }
            }
          }
          // Filter out non-included fields (allow read-only on create — callouts/defaults set them)
          JSONObject filteredBody = fieldFilter.filterCreateRequest(requestBody);
          // Inject defaults for mandatory columns not in ETGO_SF_FIELD config
          NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, context, parentIdValue);
          // ProductPrice safety net: ensure create payload has required price fields/defaults
          enrichProductPriceCreateDefaults(filteredBody, dalEntityName, parentIdValue);
          // Wrap for DefaultJsonDataService: {"data": {fields, "_entityName": ..., "_new": true}}
          String wrappedBody = NeoTypeCoercionHelper.wrapForSmartclient(filteredBody, dalEntityName, null);
          result = jsonService.add(params, wrappedBody);
          break;
        }
        case "PUT":
        case "PATCH": {
          // Validate: PUT/PATCH require a recordId
          if (context.getRecordId() == null) {
            return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
                context.getHttpMethod() + " requires a record ID in the URL");
          }
          // Filter out non-included and read-only fields from request body
          JSONObject filteredBody = fieldFilter.filterWriteRequest(context.getRequestBody());
          // Wrap for DefaultJsonDataService: {"data": {fields, "_entityName": ..., "id": recordId}}
          String wrappedBody = NeoTypeCoercionHelper.wrapForSmartclient(filteredBody, dalEntityName, context.getRecordId());
          result = jsonService.update(params, wrappedBody);
          break;
        }
        case "DELETE":
          result = jsonService.remove(params);
          break;
        default:
          return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
              "Unsupported method: " + context.getHttpMethod());
      }

      JSONObject responseJson = new JSONObject(result);

      // Check for error responses from DefaultJsonDataService
      JSONObject innerResponse = responseJson.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
      if (innerResponse != null) {
        int status = innerResponse.optInt(JsonConstants.RESPONSE_STATUS, 0);
        if (status == JsonConstants.RPCREQUEST_STATUS_FAILURE) {
          String errMsg = innerResponse.has(JsonConstants.RESPONSE_ERROR)
              ? innerResponse.getJSONObject(JsonConstants.RESPONSE_ERROR)
                  .optString("message", "Write operation failed")
              : "Write operation failed";
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              OBMessageUtils.messageBD(errMsg));
        }
        if (status == JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR) {
          // Return 400 with the full error details for validation errors
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, responseJson);
        }
      }

      // Filter response to only include configured fields (for all methods)
      fieldFilter.filterGetResponse(responseJson);

      if ("GET".equals(context.getHttpMethod()) && context.getSfEntity() != null) {
        NeoListIdentifierHelper.enrichListIdentifiers(responseJson, context.getSfEntity());
      }

      return NeoResponse.ok(responseJson);
    } catch (Exception e) {
      log.error("Error in default handler for {} {}", context.getHttpMethod(), context.getEntityName(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Wraps a flat JSON body into the structure expected by DefaultJsonDataService:
   * {@code {"data": {fields..., "_entityName": dalEntityName, "id": recordId}}}
   *
   * <p>DefaultJsonDataService.getContentAsJSON() calls jsonObject.get("data"),
   * so the content MUST be wrapped in a "data" envelope. Additionally,
   * the "_entityName" property is required for OBDal to resolve the entity,
   * and "id" is required for updates.</p>
   *
   * @param filteredBody the filtered request body (flat JSON from client)
   * @param dalEntityName the DAL entity name (e.g. "Order")
   * @param recordId the record ID for updates, or null for creates
   * @return the wrapped JSON string ready for DefaultJsonDataService
   */
  private String wrapForSmartclient(JSONObject filteredBody, String dalEntityName,
      String recordId) {
    try {
      JSONObject data = filteredBody != null ? filteredBody : new JSONObject();
      data.put(JsonConstants.ENTITYNAME, dalEntityName);
      if (recordId != null) {
        data.put(JsonConstants.ID, recordId);
      } else {
        // Mark as new record for creates
        data.put(JsonConstants.NEW_INDICATOR, true);
      }

      JSONObject wrapper = new JSONObject();
      wrapper.put(JsonConstants.DATA, data);
      return wrapper.toString();
    } catch (Exception e) {
      log.error("Error wrapping body for Smartclient format: {}", e.getMessage(), e);
      return "{}";
    }
  }

  private void enrichProductPriceCreateDefaults(JSONObject body, String dalEntityName,
      String parentIdValue) {
    if (body == null || !"ProductPrice".equals(dalEntityName)) {
      return;
    }

    try {
      if (StringUtils.isNotBlank(parentIdValue) && !body.has("product")) {
        body.put("product", parentIdValue);
      }

      if (!body.has("priceLimit")) {
        if (body.has("listPrice")) {
          body.put("priceLimit", body.opt("listPrice"));
        } else if (body.has("standardPrice")) {
          body.put("priceLimit", body.opt("standardPrice"));
        }
      }

      if (!body.has("priceListVersion")) {
        String versionId = resolveDefaultSalesPriceListVersionId();
        if (StringUtils.isNotBlank(versionId)) {
          body.put("priceListVersion", versionId);
        }
      }
    } catch (Exception e) {
      log.warn("Could not enrich ProductPrice defaults: {}", e.getMessage());
    }
  }

  private String resolveDefaultSalesPriceListVersionId() {
    OBContext obContext = OBContext.getOBContext();
    if (obContext == null || obContext.getCurrentClient() == null) {
      return null;
    }

    String clientId = obContext.getCurrentClient().getId();
    String orgId = obContext.getCurrentOrganization() != null
        ? obContext.getCurrentOrganization().getId()
        : "0";

    String sql = "SELECT plv.m_pricelist_version_id "
        + "FROM m_pricelist_version plv "
        + "JOIN m_pricelist pl ON pl.m_pricelist_id = plv.m_pricelist_id "
        + "WHERE plv.isactive = 'Y' "
        + "  AND pl.isactive = 'Y' "
        + "  AND pl.issopricelist = 'Y' "
        + "  AND plv.ad_client_id = ? "
        + "  AND (plv.ad_org_id = '0' OR plv.ad_org_id = ?) "
        + "ORDER BY CASE WHEN plv.ad_org_id = ? THEN 0 ELSE 1 END, plv.validfrom DESC";

    try (Connection conn = OBDal.getInstance().getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, clientId);
      ps.setString(2, orgId);
      ps.setString(3, orgId);
      ps.setMaxRows(1);

      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String id = rs.getString(1);
          if (StringUtils.isNotBlank(id)) {
            return id;
          }
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve default sales price list version: {}", e.getMessage());
    }

    return null;
  }

  /**
   * Builds an HQL where clause fragment that filters a child tab's records
   * by the parent record ID.
   */
  String buildParentWhereClause(Tab childTab, String parentId) {
    if (childTab == null) {
      return null;
    }
    try {
      Tab parentTab = KernelUtils.getInstance().getParentTab(childTab);
      if (parentTab == null) {
        return null;
      }

      String parentProperty = ApplicationUtils.getParentProperty(childTab, parentTab);
      if (StringUtils.isBlank(parentProperty)) {
        return null;
      }

      Entity childEntity = ModelProvider.getInstance()
          .getEntityByTableId(childTab.getTable().getId());
      Property prop = childEntity.getProperty(parentProperty);

      if (prop != null && !prop.isPrimitive()) {
        return "e." + parentProperty + ".id='" + parentId.replace("'", "''") + "'";
      } else {
        return "e." + parentProperty + "='" + parentId.replace("'", "''") + "'";
      }
    } catch (Exception e) {
      log.error("Error building parent where clause for tab '{}': {}",
          childTab.getName(), e.getMessage(), e);
      return null;
    }
  }

  /**
   * Check if a report spec has a NeoHandler qualifier on any of its entities.
   * Returns the qualifier string if found, null otherwise.
   * This allows report specs to use custom handlers instead of the standard Jasper flow.
   */
  private String resolveReportHandlerQualifier(SFSpec spec) {
    try {
      OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
      criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
      List<SFEntity> entities = criteria.list();
      for (SFEntity entity : entities) {
        String qualifier = entity.getJavaQualifier();
        if (StringUtils.isNotBlank(qualifier)) {
          return qualifier;
        }
      }
    } catch (Exception e) {
      log.warn("Error checking report handler qualifier for spec '{}': {}", spec.getName(), e.getMessage());
    }
    return null;
  }

  /**
   * Resolve the AD_Process linked to a process-type spec.
   */
  private Process resolveProcess(SFSpec spec) {
    return spec.getProcess();
  }

  /**
   * Handle a process-type spec POST. Reads the request body as JSON
   * and delegates to NeoProcessService.
   */
  private void handleProcessSpec(SFSpec spec, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      Process adProcess = resolveProcess(spec);
      if (adProcess == null) {
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Process spec has no linked AD_Process");
        return;
      }

      // Read request body
      JSONObject requestBody = null;
      String bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (StringUtils.isNotBlank(bodyStr)) {
        requestBody = new JSONObject(bodyStr);
      }

      // Delegate to NeoProcessService
      NeoResponse result = NeoProcessService.executeProcess(adProcess, requestBody);
      writeResponse(response, result);
    } catch (Exception e) {
      log.error("Error executing process spec '{}': {}", spec.getName(), e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Process execution error: " + e.getMessage());
    }
  }

  /**
   * Handle a report-type spec POST. Reads the request body for exportType and params,
   * resolves report metadata, sets response headers, then streams the report output.
   */
  private void handleReportSpec(SFSpec spec, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      Process adProcess = resolveProcess(spec);
      if (adProcess == null) {
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Report spec has no linked AD_Process");
        return;
      }

      // Read request body
      String bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      JSONObject body = StringUtils.isNotBlank(bodyStr)
          ? new JSONObject(bodyStr) : new JSONObject();
      String exportType = body.optString("exportType", "PDF");
      JSONObject params = body.optJSONObject("params");
      if (params == null) {
        params = new JSONObject();
      }

      // Resolve metadata first (filename, content type)
      NeoReportService.ReportMetadata meta =
          NeoReportService.resolveReportMetadata(adProcess, exportType);

      // Set response headers BEFORE writing to output stream
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(meta.getContentType());
      response.setHeader("Content-Disposition",
          "attachment; filename=\"" + meta.getFilename() + "\"");

      // Generate report directly to response output stream
      OutputStream out = response.getOutputStream();
      NeoReportService.generateReport(adProcess, params, exportType, out);
      out.flush();

    } catch (Exception e) {
      log.error("Error generating report for spec '{}': {}",
          spec.getName(), e.getMessage(), e);
      // Only send error if response not already committed
      if (!response.isCommitted()) {
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Report generation failed: " + e.getMessage());
      }
    }
  }

  private void writeResponse(HttpServletResponse response, NeoResponse neoResponse)
      throws IOException {
    if (neoResponse == null) {
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
      return;
    }

    for (Map.Entry<String, String> header : neoResponse.getHeaders().entrySet()) {
      response.setHeader(header.getKey(), header.getValue());
    }

    response.setStatus(neoResponse.getHttpStatus());

    if (neoResponse.getBody() != null) {
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(neoResponse.getBody().toString());
    }
  }

  /**
   * Handle button action requests.
   * GET with no actionName: list available button actions for the entity.
   * POST with actionName: execute the button process for a specific record.
   */
  private NeoResponse handleButtonAction(SFSpec spec,
      NeoPathInfo pathInfo, String method, HttpServletRequest request) {
    try {
      SFEntity entity = findEntity(spec.getId(), pathInfo.entityName);
      if (entity == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
            "Entity not found in spec: " + pathInfo.entityName);
      }

      if ("GET".equals(method) && pathInfo.actionName == null) {
        return NeoButtonActionHelper.listButtonActions(entity.getId());
      }
      if ("POST".equals(method) && pathInfo.actionName != null) {
        return NeoButtonActionHelper.executeButtonAction(entity, pathInfo, request);
      }
      if ("GET".equals(method)) {
        return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Use POST to execute an action, GET is only for listing actions");
      }
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "POST requires an action name: /{spec}/{entity}/{recordId}/action/{columnName}");
    } catch (Exception e) {
      log.error("Error handling button action: {}", e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Action error: " + e.getMessage());
    }
  }

  private NeoResponse handleSelector(String specId,
      NeoPathInfo pathInfo, HttpServletRequest request) {
    if (pathInfo.selectorField == null) {
      // List all available selectors
      return NeoSelectorService.listSelectors(specId, pathInfo.entityName);
    }
    // Query a specific selector
    String search = request.getParameter("q");
    int limit = parseIntParam(request, "limit", 20);
    int offset = parseIntParam(request, "offset", 0);

    // Collect context params (all query params except q, limit, offset)
    Map<String, String> contextParams = new HashMap<>();
    Enumeration<String> paramNames = request.getParameterNames();
    while (paramNames.hasMoreElements()) {
      String paramName = paramNames.nextElement();
      if (!"q".equals(paramName) && !"limit".equals(paramName)
          && !"offset".equals(paramName)) {
        contextParams.put(paramName, request.getParameter(paramName));
      }
    }

    return NeoSelectorService.querySelector(
        specId, pathInfo.entityName, pathInfo.selectorField,
        search, limit, offset, contextParams);
  }

  private int parseIntParam(HttpServletRequest request, String name, int defaultValue) {
    String val = request.getParameter(name);
    if (val == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(val);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  // ── Discovery, access, and display logic moved to util package ──────

  /**
   * Handle callout execution requests.
   * POST /sws/neo/{specName}/{entityName}/callout
   * Delegates to NeoCalloutService for callout resolution, execution, and response transformation.
   */
  private NeoResponse handleCallout(SFSpec spec,
      NeoPathInfo pathInfo, HttpServletRequest request) {
    try {
      // Find entity
      SFEntity sfEntity = findEntity(spec.getId(), pathInfo.entityName);
      if (sfEntity == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
            "Entity not found: " + pathInfo.entityName);
      }

      Tab tab = sfEntity.getADTab();
      if (tab == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Entity has no linked AD_Tab: " + pathInfo.entityName);
      }

      // Parse request body
      String bodyStr = new String(
          request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (StringUtils.isBlank(bodyStr)) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Request body is required for callout execution");
      }

      JSONObject requestBody;
      try {
        requestBody = new JSONObject(bodyStr);
      } catch (Exception e) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Invalid JSON body: " + e.getMessage());
      }

      if (!requestBody.has("field")) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Missing required field: 'field'");
      }

      // Build context
      NeoContext neoContext = NeoContext.builder()
          .specName(pathInfo.specName)
          .entityName(pathInfo.entityName)
          .httpMethod("POST")
          .requestBody(requestBody)
          .adTab(tab)
          .sfEntity(sfEntity)
          .obContext(OBContext.getOBContext())
          .build();

      // Delegate to callout service
      return NeoCalloutService.executeCallout(neoContext, requestBody);
    } catch (Exception e) {
      log.error("Error handling callout for {}/{}: {}",
          pathInfo.specName, pathInfo.entityName, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Callout error: " + e.getMessage());
    }
  }

  /**
   * Handle default value resolution requests.
   * GET /sws/neo/{specName}/{entityName}/defaults
   * Delegates to NeoDefaultsService for resolving AD_Column defaults.
   */
  private NeoResponse handleDefaults(SFSpec spec,
      NeoPathInfo pathInfo, HttpServletRequest request) {
    try {
      SFEntity sfEntity = findEntity(spec.getId(), pathInfo.entityName);
      if (sfEntity == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
            "Entity not found: " + pathInfo.entityName);
      }

      Tab tab = sfEntity.getADTab();
      if (tab == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Entity has no linked AD_Tab: " + pathInfo.entityName);
      }

      String parentId = request.getParameter("parentId");

      NeoContext ctx = NeoContext.builder()
          .specName(pathInfo.specName)
          .entityName(pathInfo.entityName)
          .httpMethod("GET")
          .adTab(tab)
          .sfEntity(sfEntity)
          .obContext(OBContext.getOBContext())
          .build();

      return NeoDefaultsService.resolveDefaults(ctx, parentId);
    } catch (Exception e) {
      log.error("Error resolving defaults for {}/{}: {}",
          pathInfo.specName, pathInfo.entityName, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Defaults error: " + e.getMessage());
    }
  }

  private void sendError(HttpServletResponse response, int status, String message)
      throws IOException {
    NeoResponse errorResponse = NeoResponse.error(status, message);
    writeResponse(response, errorResponse);
  }

  private static class NeoRequestException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int statusCode;

    NeoRequestException(int statusCode, String message) {
      super(message);
      this.statusCode = statusCode;
    }

    int getStatusCode() {
      return statusCode;
    }
  }

  private static class NeoMethodNotAllowedException extends NeoRequestException {
    private static final long serialVersionUID = 1L;

    NeoMethodNotAllowedException(String message) {
      super(HttpServletResponse.SC_METHOD_NOT_ALLOWED, message);
    }
  }

  /**
   * Parsed path components.
   */
  public static class NeoPathInfo {
    public final String specName;
    public final String entityName;
    public final String recordId;
    public final boolean isSelector;
    public final String selectorField;
    public final boolean isAction;
    public final String actionName;
    public final boolean isEvaluateDisplay;
    public final boolean isCallout;
    public final boolean isDefaults;

    NeoPathInfo(String specName, String entityName, String recordId) {
      this(specName, entityName, recordId, false, null, false, null, false, false, false);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField) {
      this(specName, entityName, recordId, isSelector, selectorField, false, null, false, false, false);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField,
        boolean isAction, String actionName) {
      this(specName, entityName, recordId, isSelector, selectorField,
          isAction, actionName, false, false, false);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField,
        boolean isAction, String actionName, boolean isEvaluateDisplay) {
      this(specName, entityName, recordId, isSelector, selectorField,
          isAction, actionName, isEvaluateDisplay, false, false);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField,
        boolean isAction, String actionName, boolean isEvaluateDisplay,
        boolean isCallout) {
      this(specName, entityName, recordId, isSelector, selectorField,
          isAction, actionName, isEvaluateDisplay, isCallout, false);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField,
        boolean isAction, String actionName, boolean isEvaluateDisplay,
        boolean isCallout, boolean isDefaults) {
      this.specName = specName;
      this.entityName = entityName;
      this.recordId = recordId;
      this.isSelector = isSelector;
      this.selectorField = selectorField;
      this.isAction = isAction;
      this.actionName = actionName;
      this.isEvaluateDisplay = isEvaluateDisplay;
      this.isCallout = isCallout;
      this.isDefaults = isDefaults;
    }
  }

}
