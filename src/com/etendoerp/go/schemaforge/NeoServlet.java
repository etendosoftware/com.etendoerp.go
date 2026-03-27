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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.ApplicationUtils;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Window;

import com.auth0.jwt.interfaces.DecodedJWT;
import org.openbravo.base.expression.OBScriptEngine;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.application.DynamicExpressionParser;
import org.openbravo.erpCommon.utility.DimensionDisplayUtility;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.model.ad.utility.Image;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

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
  private static final int MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;

  /**
   * Minimal shim for SmartClient functions used by DynamicExpressionParser output.
   * DynamicExpressionParser generates JS like:
   *   OB.Utilities.getValue(currentValues, 'documentStatus') === 'CO'
   *   OB.Utilities.Date.JSToOB(OB.Utilities.getValue(currentValues,'orderDate'), OB.Format.date)
   *
   * These functions don't exist in a bare Rhino context. The shim provides:
   *   - getValue(obj, key) -> obj[key] (null-safe property accessor)
   *   - Date.JSToOB(value, format) -> value (pass-through; display logic only compares strings)
   *   - OB.Format.date -> empty string (unused by pass-through JSToOB)
   */
  private static final String OB_UTILITIES_SHIM =
      "var OB = { Utilities: { "
      + "getValue: function(obj, key) { return obj != null ? obj[key] : null; }, "
      + "Date: { JSToOB: function(v) { return v; } } }, "
      + "Format: { date: '' } };";

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
        handleImageRequest(resolvedImageId, method, request, response);
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
      if (window != null && !hasWindowAccess(window.getId())) {
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
    handleDiscovery(response);
  }

  private void handleSpecDescribeMode(SFSpec spec, String method, HttpServletResponse response)
      throws IOException, NeoRequestException {
    requireMethod(method, "GET", "Spec describe only supports GET");
    handleSpecDescribe(response, spec);
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
    Process adProcess = resolveProcess(spec);
    if (adProcess != null && !hasProcessAccess(adProcess.getId())) {
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
    handleProcessSpec(spec, request, response);
  }

  private void routeReportSpec(SFSpec spec, String specName, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException, JSONException {
    String reportHandlerQualifier = resolveReportHandlerQualifier(spec);
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

    Process adReportProcess = resolveProcess(spec);
    if (adReportProcess != null && !hasProcessAccess(adReportProcess.getId())) {
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
    handleReportSpec(spec, request, response);
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
        () -> handleEvaluateDisplay(spec, pathInfo, request));
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
    NeoResponse actionResult = dispatchWithHooks(spec, pathInfo.entityName,
        NeoEndpointType.ACTION, pathInfo.actionName, method,
        () -> handleButtonAction(spec, pathInfo, method, request));
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
      return NeoResponse.error(500, "Hook handler error: " + e.getMessage());
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
      return NeoResponse.error(500, "Hook handler error: " + e.getMessage());
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
        String parentFilter = buildParentWhereClause(adTab, parentId);
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
          // Wrap for DefaultJsonDataService: {"data": {fields, "_entityName": ..., "_new": true}}
          String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, null);
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
          String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, context.getRecordId());
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
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errMsg);
        }
        if (status == JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR) {
          // Return 400 with the full error details for validation errors
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, responseJson);
        }
      }

      // Filter response to only include configured fields (for all methods)
      fieldFilter.filterGetResponse(responseJson);

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
      // Coerce string values to proper JSON types using the DAL model
      coerceTypes(data, dalEntityName);
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

  /**
   * Coerce string values in the JSON body to proper types based on the DAL model.
   * The frontend and defaults endpoint may send numeric values as strings (e.g., "0")
   * but DefaultJsonDataService expects BigDecimal for numeric properties.
   */
  @SuppressWarnings("unchecked")
  private void coerceTypes(JSONObject data, String dalEntityName) {
    try {
      Entity entity = ModelProvider.getInstance().getEntity(dalEntityName);
      if (entity == null) {
        return;
      }
      // Collect coerced values first to avoid ConcurrentModificationException
      Map<String, Object> coerced = new java.util.HashMap<>();
      Iterator<String> keys = data.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Object val = data.opt(key);
        if (val instanceof String) {
          coerceField(entity, key, (String) val, coerced);
        }
      }
      // Apply coerced values after iteration
      for (Map.Entry<String, Object> entry : coerced.entrySet()) {
        data.put(entry.getKey(), entry.getValue());
      }
      if (!coerced.isEmpty()) {
        log.info("[NEO] coerceTypes: converted {} fields for {}: {}", coerced.size(),
            dalEntityName, coerced.keySet());
      }
    } catch (Exception e) {
      log.error("Error coercing types for {}: {}", dalEntityName, e.getMessage(), e);
    }
  }

  private void coerceField(Entity entity, String key, String strVal, Map<String, Object> coerced) {
    try {
      Property prop = entity.getProperty(key);
      if (prop != null && prop.isPrimitive()) {
        Class<?> type = prop.getPrimitiveObjectType();
        if (type != null && java.math.BigDecimal.class.isAssignableFrom(type) && !strVal.isEmpty()) {
          coerced.put(key, new java.math.BigDecimal(strVal));
        } else if (type != null && Long.class.isAssignableFrom(type) && !strVal.isEmpty()) {
          coerced.put(key, Long.parseLong(strVal));
        }
      }
    } catch (Exception ignored) {
      // Not a DAL property or not primitive — skip
    }
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
        return listButtonActions(entity.getId());
      }
      if ("POST".equals(method) && pathInfo.actionName != null) {
        return executeButtonAction(entity, pathInfo, request);
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

  /**
   * List available button actions for an entity, returning column name and process metadata.
   */
  private NeoResponse listButtonActions(String entityId) throws Exception {
    List<SFField> fields = loadEntityFields(entityId);
    JSONArray actions = new JSONArray();
    for (SFField field : fields) {
      JSONObject actionObj = buildButtonActionEntry(field);
      if (actionObj != null) {
        actions.put(actionObj);
      }
    }
    JSONObject responseBody = new JSONObject();
    responseBody.put("actions", actions);
    return NeoResponse.ok(responseBody);
  }

  /**
   * Build a single action entry JSON for a field if it is a valid button with a linked process.
   * Returns null if the field should be skipped.
   */
  private JSONObject buildButtonActionEntry(SFField field) throws Exception {
    Column column = field.getADColumn();
    if (column == null || column.getReference() == null
        || !"28".equals((String) column.getReference().getId())) {
      return null;
    }

    Process classicProcess = column.getProcess();
    org.openbravo.client.application.Process obuiappProcess = column.getOBUIAPPProcess();

    if (classicProcess == null && obuiappProcess == null) {
      if ("Posted".equals(column.getDBColumnName())) {
        obuiappProcess = resolveDefaultPostProcess();
      }
      if (obuiappProcess == null) {
        return null;
      }
    }

    JSONObject actionObj = new JSONObject();
    actionObj.put("columnName", column.getDBColumnName());
    if (obuiappProcess != null) {
      actionObj.put("processType", "OBUIAPP");
      actionObj.put("processName", obuiappProcess.getName() != null ? obuiappProcess.getName() : "");
    } else {
      actionObj.put("processType", "Classic");
      actionObj.put("processName", classicProcess.getName() != null ? classicProcess.getName() : "");
    }
    return actionObj;
  }

  /**
   * Execute a button action for a specific record.
   */
  private NeoResponse executeButtonAction(SFEntity entity,
      NeoPathInfo pathInfo, HttpServletRequest request) throws Exception {
    Column targetColumn = findButtonColumn(entity.getId(), pathInfo.actionName);
    if (targetColumn == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Action not found: " + pathInfo.actionName);
    }
    if (targetColumn.getReference() == null
        || !"28".equals((String) targetColumn.getReference().getId())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Field is not a button: " + pathInfo.actionName);
    }

    org.openbravo.client.application.Process obuiappProcess = targetColumn.getOBUIAPPProcess();
    Process adProcess = targetColumn.getProcess();

    if (adProcess == null && obuiappProcess == null) {
      if ("Posted".equals(targetColumn.getDBColumnName())) {
        obuiappProcess = resolveDefaultPostProcess();
      }
      if (adProcess == null && obuiappProcess == null) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "No process linked to button: " + pathInfo.actionName);
      }
    }

    String bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    JSONObject params = StringUtils.isNotBlank(bodyStr) ? new JSONObject(bodyStr) : new JSONObject();
    params.put("recordId", pathInfo.recordId);
    params.put("inpRecordId", pathInfo.recordId);
    if (entity.getADTab() != null) {
      params.put("inpTabId", entity.getADTab().getId());
    }

    if (obuiappProcess != null) {
      return NeoProcessService.executeObuiappProcess(obuiappProcess, params);
    }
    if (!hasProcessAccess(adProcess.getId())) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          "Access denied to process for current role");
    }
    return NeoProcessService.executeProcess(adProcess, params);
  }

  /**
   * Load the active, included SFField list for an entity.
   */
  private List<SFField> loadEntityFields(String entityId) {
    OBCriteria<SFField> fieldCriteria = OBDal.getInstance().createCriteria(SFField.class);
    fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    fieldCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    fieldCriteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    return fieldCriteria.list();
  }

  /**
   * Find the Column for the given action name among the entity's button fields.
   * Returns null if not found.
   */
  private Column findButtonColumn(String entityId, String actionName) {
    for (SFField field : loadEntityFields(entityId)) {
      Column column = field.getADColumn();
      if (column != null && actionName.equals(column.getDBColumnName())) {
        return column;
      }
    }
    return null;
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

  /**
   * Check if the current role has access to the given window.
   */
  private boolean hasWindowAccess(String windowId) {
    String roleId = OBContext.getOBContext().getRole().getId();
    if ("0".equals(roleId)) {
      return true;
    }
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_WINDOW + ".id", windowId));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  /**
   * Default OBUIAPP process ID for the "Post" action (com.smf.jobs.defaults.Post).
   * Used as fallback when a "Posted" button column has no linked process.
   */
  private static final String DEFAULT_POST_PROCESS_ID = "57496FB9CF9E4E8F847224017941570E";

  /**
   * Resolve the default Post OBUIAPP process for hardcoded "Posted" buttons.
   * Returns null if the process is not available in this instance.
   */
  private org.openbravo.client.application.Process resolveDefaultPostProcess() {
    try {
      return OBDal.getInstance().get(
          org.openbravo.client.application.Process.class, DEFAULT_POST_PROCESS_ID);
    } catch (Exception e) {
      log.debug("Default Post process not found: {}", DEFAULT_POST_PROCESS_ID);
      return null;
    }
  }

  /**
   * Check if the current role has access to the given process.
   */
  private boolean hasProcessAccess(String processId) {
    String roleId = OBContext.getOBContext().getRole().getId();
    if ("0".equals(roleId)) {
      return true;
    }
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_PROCESS + ".id", processId));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  // ── Discovery endpoints ──────────────────────────────────────────────

  /**
   * Handle GET /sws/neo/ — list all active specs the current user can access.
   */
  private void handleDiscovery(HttpServletResponse response) throws IOException {
    try {
      OBCriteria<SFSpec> specCriteria = OBDal.getInstance().createCriteria(SFSpec.class);
      specCriteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
      List<SFSpec> allSpecs = specCriteria.list();

      JSONArray specsArray = new JSONArray();
      for (SFSpec spec : allSpecs) {
        String specType = spec.getSpecType();
        String specName = spec.getName();

        // Check access
        Window specWindow = spec.getADWindow();
        if ("W".equals(specType)) {
          if (specWindow != null && !hasWindowAccess(specWindow.getId())) {
            continue;
          }
        } else if ("P".equals(specType) || "R".equals(specType)) {
          Process adProcess = resolveProcess(spec);
          if (adProcess != null && !hasProcessAccess(adProcess.getId())) {
            continue;
          }
        }

        JSONObject specObj = new JSONObject();
        specObj.put("id", spec.getId());
        specObj.put("name", specName);
        specObj.put("type", specType);
        specObj.put("description", spec.getDescription());

        // Include window/process IDs for management
        if ("W".equals(specType)) {
          if (specWindow != null) specObj.put("windowId", specWindow.getId());
          specObj.put("entities", buildEntitySummaryArray(spec.getId()));
        } else if ("P".equals(specType) || "R".equals(specType)) {
          Process adProcess = resolveProcess(spec);
          if (adProcess != null) specObj.put("processId", adProcess.getId());
          if ("R".equals(specType)) specObj.put("isReport", true);
        }

        // Module ID
        Module specModule = spec.getADModule();
        if (specModule != null) specObj.put("moduleId", specModule.getId());

        specsArray.put(specObj);
      }

      JSONObject result = new JSONObject();
      result.put("specs", specsArray);
      writeResponse(response, NeoResponse.ok(result));
    } catch (Exception e) {
      log.error("Error in discovery endpoint: {}", e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Discovery error: " + e.getMessage());
    }
  }

  /**
   * Handle GET /sws/neo/{specName} — describe a window spec with entities and fields.
   */
  private void handleSpecDescribe(HttpServletResponse response, SFSpec spec)
      throws IOException {
    try {
      String specId = spec.getId();
      String specType = spec.getSpecType();

      JSONObject result = new JSONObject();
      result.put("id", spec.getId());
      result.put("name", spec.getName());
      result.put("type", specType);
      result.put("description", spec.getDescription());
      Module specModule = spec.getADModule();
      if (specModule != null) result.put("moduleId", specModule.getId());

      // Query entities for this spec
      OBCriteria<SFEntity> entityCriteria = OBDal.getInstance()
          .createCriteria(SFEntity.class);
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
      entityCriteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
      List<SFEntity> entities = entityCriteria.list();

      JSONArray entitiesArray = new JSONArray();
      for (SFEntity entity : entities) {
        JSONObject entityObj = new JSONObject();
        entityObj.put("id", entity.getId());
        entityObj.put("name", entity.getName());
        entityObj.put("methods", buildMethodsArray(entity));

        // Resolve tab and include metadata
        Tab adTab = getAdTab(entity);
        if (adTab != null) {
          entityObj.put("tabLevel", adTab.getTabLevel());
          entityObj.put("tabId", adTab.getId());
        }

        // Method flags for editing
        entityObj.put("isGet", Boolean.TRUE.equals(entity.isGet()));
        entityObj.put("isGetbyid", Boolean.TRUE.equals(entity.isGetByID()));
        entityObj.put("isPost", Boolean.TRUE.equals(entity.isPost()));
        entityObj.put("isPut", Boolean.TRUE.equals(entity.isPut()));
        entityObj.put("isPatch", Boolean.TRUE.equals(entity.isPatch()));
        entityObj.put("isDelete", Boolean.TRUE.equals(entity.isDelete()));

        // Build fields array
        entityObj.put("fields", buildFieldsArray(entity.getId()));
        entitiesArray.put(entityObj);
      }

      result.put("entities", entitiesArray);
      writeResponse(response, NeoResponse.ok(result));
    } catch (Exception e) {
      log.error("Error describing spec '{}': {}", spec.getName(), e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Spec describe error: " + e.getMessage());
    }
  }

  /**
   * Build a summary of entities for the discovery endpoint (name + methods only).
   */
  private JSONArray buildEntitySummaryArray(String specId) throws Exception {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
    List<SFEntity> entities = criteria.list();

    JSONArray arr = new JSONArray();
    for (SFEntity entity : entities) {
      JSONObject obj = new JSONObject();
      obj.put("name", entity.getName());
      obj.put("methods", buildMethodsArray(entity));
      arr.put(obj);
    }
    return arr;
  }

  /**
   * Build a JSON array of enabled HTTP methods for an entity.
   */
  private JSONArray buildMethodsArray(SFEntity entity) {
    JSONArray methods = new JSONArray();
    if (Boolean.TRUE.equals(entity.isGet()) || Boolean.TRUE.equals(entity.isGetByID())) {
      methods.put("GET");
    }
    if (Boolean.TRUE.equals(entity.isPost())) {
      methods.put("POST");
    }
    if (Boolean.TRUE.equals(entity.isPut())) {
      methods.put("PUT");
    }
    if (Boolean.TRUE.equals(entity.isPatch())) {
      methods.put("PATCH");
    }
    if (Boolean.TRUE.equals(entity.isDelete())) {
      methods.put("DELETE");
    }
    return methods;
  }

  /**
   * Build the fields array for a given entity, resolving AD_Column metadata.
   */
  private JSONArray buildFieldsArray(String entityId) throws Exception {
    OBCriteria<SFField> criteria = OBDal.getInstance().createCriteria(SFField.class);
    criteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
    List<SFField> fields = criteria.list();

    JSONArray arr = new JSONArray();
    for (SFField field : fields) {
      Column column = field.getADColumn();
      if (column == null) {
        continue;
      }

      String refId = column.getReference() != null
          ? (String) column.getReference().getId() : null;

      JSONObject fieldObj = new JSONObject();
      fieldObj.put("id", field.getId());
      fieldObj.put("columnId", column.getId());
      fieldObj.put("name", column.getDBColumnName());
      fieldObj.put("label", column.getName());
      fieldObj.put("columnType", mapReferenceToType(refId));
      fieldObj.put("readOnly", Boolean.TRUE.equals(field.isReadOnly()));
      fieldObj.put("included", Boolean.TRUE.equals(field.isIncluded()));
      fieldObj.put("required", column.isMandatory());

      boolean hasSelector = isSelectorReference(refId);
      fieldObj.put("hasSelector", hasSelector);
      if (hasSelector) {
        fieldObj.put("selectorType", mapSelectorType(refId));
        // Extract dependent params from column's validation rule
        JSONArray selectorParams = extractValidationParams(column);
        if (selectorParams.length() > 0) {
          fieldObj.put("selectorParams", selectorParams);
        }
      }

      arr.put(fieldObj);
    }
    return arr;
  }

  private static final Set<String> SELECTOR_REFS = new HashSet<>();
  static {
    SELECTOR_REFS.add("19"); // TableDir
    SELECTOR_REFS.add("18"); // Table
    SELECTOR_REFS.add("30"); // Search
    SELECTOR_REFS.add("95E2A8B50A254B2AAE6774B8C2F28120"); // OBUISEL
  }

  private boolean isSelectorReference(String refId) {
    return refId != null && SELECTOR_REFS.contains(refId);
  }

  private static final Pattern VALIDATION_PARAM_PATTERN = Pattern.compile("@(\\w+)@");

  /**
   * Extract parameter names from a column's validation rule.
   * Validation rules use @ColumnName@ as placeholders for dependent fields.
   */
  private JSONArray extractValidationParams(Column column) {
    JSONArray params = new JSONArray();
    org.openbravo.model.ad.domain.Validation valRule = column.getValidation();
    if (valRule == null || valRule.getValidationCode() == null) {
      return params;
    }
    Set<String> seen = new HashSet<>();
    Matcher m = VALIDATION_PARAM_PATTERN.matcher(valRule.getValidationCode());
    while (m.find()) {
      String param = m.group(1);
      if (!seen.contains(param)) {
        params.put(param);
        seen.add(param);
      }
    }
    return params;
  }

  private String mapSelectorType(String refId) {
    if (refId == null) return null;
    switch (refId) {
      case "19": return "TableDir";
      case "18": return "Table";
      case "30": return "Search";
      case "95E2A8B50A254B2AAE6774B8C2F28120": return "OBUISEL";
      default: return null;
    }
  }

  /**
   * Map AD_Reference_ID to a simple type name for the discovery API.
   */
  private String mapReferenceToType(String refId) {
    if (refId == null) return "string";
    switch (refId) {
      case "10": case "14": case "34": // String, Text, Memo
        return "string";
      case "11": case "22": case "29": case "12": // Integer, Number, Quantity, Amount
      case "800008": case "800019": // GeneralQuantity, Price
        return "number";
      case "20": // YesNo
        return "boolean";
      case "15": // Date
        return "date";
      case "16": // DateTime
        return "datetime";
      case "24": // Time
        return "time";
      case "28": // Button
        return "button";
      case "17": // List
        return "list";
      case "13": // ID
        return "id";
      default:
        return "string";
    }
  }

  /**
   * Evaluates displayLogic and readOnlyLogic expressions for all fields of a tab.
   * Uses Etendo's DynamicExpressionParser to resolve session variables, preferences,
   * accounting dimensions, auxiliary inputs, and server-expanded macros.
   * Injects an OB.Utilities shim so the SmartClient-dependent JS output can be
   * evaluated by bare Rhino/OBScriptEngine.
   *
   * POST /sws/neo/{specName}/{entityName}/evaluate-display
   */
  private NeoResponse handleEvaluateDisplay(SFSpec spec,
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
      JSONObject fieldValues = new JSONObject();
      try {
        String body = new String(
            request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (body != null && !body.trim().isEmpty()) {
          JSONObject bodyJson = new JSONObject(body);
          if (bodyJson.has("fieldValues")) {
            fieldValues = bodyJson.getJSONObject("fieldValues");
          }
        }
      } catch (Exception e) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid request body");
      }

      // Build evaluation context
      Map<String, Object> evalContext = buildEvalContext(fieldValues);

      // Evaluate all fields
      JSONObject visibility = new JSONObject();
      JSONObject readOnly = new JSONObject();

      List<Field> fields = tab.getADFieldList();
      for (Field field : fields) {
        if (!field.isActive()) {
          continue;
        }

        String propertyName = getPropertyName(field);

        // Evaluate displayLogic
        String displayLogic = field.getDisplayLogic();
        if (displayLogic != null && !displayLogic.trim().isEmpty()) {
          boolean isVisible = evaluateExpression(displayLogic, tab, field, evalContext, false);
          visibility.put(propertyName, isVisible);
        }

        // Evaluate readOnlyLogic from the column
        Column column = field.getColumn();
        if (column != null) {
          String readOnlyLogic = column.getReadOnlyLogic();
          if (readOnlyLogic != null && !readOnlyLogic.trim().isEmpty()) {
            boolean isReadOnly = evaluateExpression(readOnlyLogic, tab, field, evalContext, true);
            readOnly.put(propertyName, isReadOnly);
          }
        }
      }

      // Build response
      JSONObject result = new JSONObject();
      result.put("visibility", visibility);
      result.put("readOnly", readOnly);

      return NeoResponse.ok(result);

    } catch (Exception e) {
      log.error("Error evaluating display logic for {}/{}", pathInfo.specName,
          pathInfo.entityName, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Error evaluating display logic: " + e.getMessage());
    }
  }

  /**
   * Evaluates a single display logic or readOnly logic expression.
   * 4-step pipeline: preprocess -> parse -> shim -> eval.
   *
   * @param expression   the raw AD expression
   * @param tab          the AD_Tab for context resolution
   * @param field        the AD_Field for field-type detection (may be null for tab-level)
   * @param evalContext  the evaluation context with field values and session data
   * @param isReadOnlyLogic true if evaluating readOnlyLogic (affects safe default on failure)
   * @return evaluation result; on failure: true for display (show), true for readOnly (lock)
   */
  private boolean evaluateExpression(String expression, Tab tab, Field field,
      Map<String, Object> evalContext, boolean isReadOnlyLogic) {
    try {
      // Step 1: Parse the raw expression.
      // Pass the expression directly to DynamicExpressionParser so it can correctly handle
      // special tokens like @ACCT_DIMENSION_DISPLAY@ (accounting dimension logic computed
      // via DimensionDisplayUtility) and field references. Do NOT pre-process with
      // replaceSystemPreferencesInDisplayLogic: that method replaces @ACCT_DIMENSION_DISPLAY@
      // with a null CachedPreference value before the parser can handle it, causing the field
      // to always evaluate as hidden.
      DynamicExpressionParser parser = new DynamicExpressionParser(expression, tab, field);
      String jsExpr = parser.getJSExpression();

      // If the parser produces an empty expression (e.g. @ACCT_DIMENSION_DISPLAY@ on a field
      // that has no entry in AD_DimensionMapping — meaning it is not an accounting dimension),
      // treat it as "no display logic applies" and return the safe default:
      // visible=true, readOnly=true. This matches classic UI behavior where an unresolvable
      // expression does not hide the field.
      if (jsExpr == null || jsExpr.trim().isEmpty()) {
        return true;
      }

      // Step 2: Load any session attributes the parser identified as needed
      // (e.g. @showAddPayment@, @APRM_OrderIsPaid@, @#ShowAcct@) that are not already
      // in the context from buildEvalContext().
      List<String> sessionAttrs = parser.getSessionAttributes();
      if (!sessionAttrs.isEmpty()) {
        try {
          OBContext obCtx = OBContext.getOBContext();
          DalConnectionProvider conn = new DalConnectionProvider(false);
          VariablesSecureApp vars = new VariablesSecureApp(
              obCtx.getUser().getId(),
              obCtx.getCurrentClient().getId(),
              obCtx.getCurrentOrganization().getId(),
              obCtx.getRole().getId(),
              obCtx.getLanguage().getLanguage());
          for (String attr : sessionAttrs) {
            if (!evalContext.containsKey(attr)) {
              evalContext.put(attr, Utility.getContext(conn, vars, attr, ""));
            }
          }
        } catch (Exception e) {
          log.debug("Could not resolve session attributes for expression '{}': {}",
              expression, e.getMessage());
        }
      }

      // Step 3: Build proper JS objects for 'context' and 'currentValues'.
      // DynamicExpressionParser generates expressions like context['$Element_BP_POO_H']
      // and OB.Utilities.getValue(currentValues, 'DOCBASETYPE'). These require native JS
      // objects — Java HashMaps in Rhino Bindings do NOT support bracket-notation property
      // access reliably across Rhino versions, so we serialize them as JSON literals.
      String contextPreamble = buildJsObjectPreamble("context", evalContext, true);
      @SuppressWarnings("unchecked")
      Map<String, Object> currentValues = (Map<String, Object>) evalContext.get("currentValues");
      String cvPreamble = buildJsObjectPreamble("currentValues", currentValues, false);

      String fullScript = OB_UTILITIES_SHIM + "\n" + contextPreamble + "\n" + cvPreamble + "\n" + jsExpr;

      // Step 4: Evaluate using Rhino (sandboxed)
      Object result = OBScriptEngine.getInstance().eval(fullScript, evalContext);
      return Boolean.TRUE.equals(result);

    } catch (Exception e) {
      log.warn("Failed to evaluate expression: {} for field: {}",
          expression, field != null ? field.getName() : "tab-level", e);
      // Safe defaults: true for both — show the field (visible) and lock it (read-only)
      return true;
    }
  }

  /**
   * Serializes a Map as a JavaScript var declaration (JSON literal).
   * Used to inject 'context' and 'currentValues' as native JS objects so that
   * bracket-notation access (context['$Element_BP_POO_H']) works in Rhino regardless of version.
   *
   * @param varName   the JS variable name (e.g. "context", "currentValues")
   * @param map       the map to serialize; null is treated as an empty map
   * @param skipSelf  if true, skip entries whose keys match varName or "currentValues"
   *                  (to avoid circular references when serializing ctx itself)
   */
  @SuppressWarnings("unchecked")
  private String buildJsObjectPreamble(String varName, Map<String, Object> map, boolean skipSelf) {
    org.codehaus.jettison.json.JSONObject obj = new org.codehaus.jettison.json.JSONObject();
    if (map != null) {
      for (Map.Entry<String, Object> e : map.entrySet()) {
        String key = e.getKey();
        if (skipSelf && ("context".equals(key) || "currentValues".equals(key))) {
          continue;
        }
        Object val = e.getValue();
        if (val == null || val instanceof Map) {
          continue;
        }
        try {
          obj.put(key, val.toString());
        } catch (Exception ex) {
          // skip un-serializable entries
        }
      }
    }
    return "var " + varName + " = " + obj.toString() + ";";
  }

  /**
   * Builds the evaluation context from request field values and session data.
   * Field values are stored both as top-level entries (for context.xxx references)
   * and under "currentValues" key (for OB.Utilities.getValue(currentValues, 'xxx')).
   */
  private Map<String, Object> buildEvalContext(JSONObject fieldValues) {
    Map<String, Object> ctx = new HashMap<>();

    // Convert fieldValues to a Map that Rhino can access as "currentValues"
    Map<String, Object> currentValues = new HashMap<>();
    @SuppressWarnings("unchecked")
    java.util.Iterator<String> keys = fieldValues.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = fieldValues.opt(key);
      currentValues.put(key, value == JSONObject.NULL ? null : value);
    }
    ctx.put("currentValues", currentValues);

    // Also add field values at top level for auxiliary inputs and session vars
    // that resolve to context.xxx (not OB.Utilities.getValue)
    ctx.putAll(currentValues);

    // Add session context
    OBContext obCtx = OBContext.getOBContext();
    ctx.put("AD_Org_ID", obCtx.getCurrentOrganization().getId());
    ctx.put("AD_Client_ID", obCtx.getCurrentClient().getId());
    ctx.put("AD_Role_ID", obCtx.getRole().getId());
    ctx.put("AD_User_ID", obCtx.getUser().getId());

    // Resolve accounting dimension context variables the same way LoginUtils does at login.
    // DynamicExpressionParser generates JS referencing context.$IsAcctDimCentrally,
    // context.$Element_XX (non-centrally managed) and context['$Element_XX_DOCTYPE_H']
    // (centrally managed) for @ACCT_DIMENSION_DISPLAY@ expressions.
    try {
      org.openbravo.model.ad.system.Client client = OBDal.getInstance()
          .get(org.openbravo.model.ad.system.Client.class, obCtx.getCurrentClient().getId());

      boolean isCentrally = client.isAcctdimCentrallyMaintained();
      ctx.put(DimensionDisplayUtility.IsAcctDimCentrally, isCentrally ? "Y" : "N");

      if (isCentrally) {
        // Centrally maintained: load $Element_XX_DOCTYPE_LEVEL entries from client config.
        // DynamicExpressionParser generates context['$Element_BP_' + DOCBASETYPE + '_H']
        // which requires these specific combinations to be in the context.
        Map<String, String> acctDimMap = DimensionDisplayUtility
            .getAccountingDimensionConfiguration(client);
        ctx.putAll(acctDimMap);
      } else {
        // Non-centrally maintained: load legacy global $Element_XX variables.
        // These come from the accounting schema element configuration.
        DalConnectionProvider conn = new DalConnectionProvider(false);
        VariablesSecureApp vars = new VariablesSecureApp(
            obCtx.getUser().getId(),
            obCtx.getCurrentClient().getId(),
            obCtx.getCurrentOrganization().getId(),
            obCtx.getRole().getId(),
            obCtx.getLanguage().getLanguage());
        String[] elements = { "MC", "AY", "OT", "AS", "CC", "U1", "U2", "PJ", "BU", "PR", "BP", "OO" };
        for (String el : elements) {
          String key = "$Element_" + el;
          ctx.put(key, Utility.getContext(conn, vars, key, ""));
        }
      }

      // Compute DOCBASETYPE auxiliary input from the transactionDocument field value.
      // DimensionDisplayUtility generates JS that reads currentValues.DOCBASETYPE to build
      // the centrally-managed dimension key (e.g. $Element_BP_POO_H).
      String transactionDocId = currentValues.containsKey("transactionDocument")
          ? String.valueOf(currentValues.get("transactionDocument"))
          : "";
      if (!transactionDocId.isEmpty() && !transactionDocId.equals("null")) {
        org.openbravo.model.common.enterprise.DocumentType docType = OBDal.getInstance()
            .get(org.openbravo.model.common.enterprise.DocumentType.class, transactionDocId);
        if (docType != null && docType.getDocumentCategory() != null) {
          currentValues.put("DOCBASETYPE", docType.getDocumentCategory());
          ctx.put("DOCBASETYPE", docType.getDocumentCategory());
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve accounting dimension context: {}", e.getMessage());
    }

    return ctx;
  }

  /**
   * Maps a Field to its DAL property name, matching NeoFieldFilter conventions.
   * Uses ModelProvider to resolve Column -> Entity -> Property -> name.
   *
   * Examples:
   *   C_BPARTNER_ID  -> businessPartner
   *   DOCSTATUS      -> documentStatus
   *   GRANDTOTAL     -> grandTotalAmount
   */
  private String getPropertyName(Field field) {
    Column column = field.getColumn();
    if (column == null) {
      return field.getName();
    }
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(column.getTable().getId());
    if (dalEntity != null) {
      Property prop = dalEntity.getPropertyByColumnName(column.getDBColumnName());
      if (prop != null) {
        return prop.getName();
      }
    }
    return column.getDBColumnName();
  }

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
  static class NeoPathInfo {
    final String specName;
    final String entityName;
    final String recordId;
    final boolean isSelector;
    final String selectorField;
    final boolean isAction;
    final String actionName;
    final boolean isEvaluateDisplay;
    final boolean isCallout;
    final boolean isDefaults;

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

  /**
   * Handle built-in image endpoint.
   *
   * GET  /sws/neo/image/{id}  — return image binary with correct content type
   * POST /sws/neo/image        — upload image; body: { name, mimeType, data (base64) }
   *                              returns { id, name }
   */
  private void handleImageRequest(String imageId, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    try {
      if ("GET".equals(method)) {
        handleGetImage(imageId, response);
      } else if ("POST".equals(method)) {
        handlePostImage(request, response);
      } else {
        sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Image endpoint only supports GET and POST");
      }
    } catch (Exception e) {
      log.error("Error handling image request", e);
      OBDal.getInstance().rollbackAndClose();
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Image request failed");
    }
  }

  private void handleGetImage(String imageId, HttpServletResponse response) throws Exception {
    if (StringUtils.isBlank(imageId)) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Image ID required");
      return;
    }
    Image image = OBDal.getInstance().get(Image.class, imageId);
    if (image == null) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, "Image not found: " + imageId);
      return;
    }
    byte[] data = image.getBindaryData();
    if (data == null || data.length == 0) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, "Image has no data");
      return;
    }
    String mimeType = image.getMimetype();
    if (StringUtils.isBlank(mimeType)) {
      mimeType = "image/png";
    }
    response.setContentType(mimeType);
    response.setContentLength(data.length);
    try (OutputStream out = response.getOutputStream()) {
      out.write(data);
    }
  }

  private void handlePostImage(HttpServletRequest request, HttpServletResponse response) throws Exception {
    byte[] rawBytes = request.getInputStream().readNBytes(MAX_IMAGE_SIZE_BYTES + 1);
    if (rawBytes.length > MAX_IMAGE_SIZE_BYTES) {
      sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Image exceeds 10 MB limit");
      return;
    }
    String bodyStr = new String(rawBytes, StandardCharsets.UTF_8);
    JSONObject body = new JSONObject(bodyStr);
    String name = body.optString("name", "image");
    String mimeType = body.optString("mimeType", "image/png");
    String dataBase64 = body.optString("data", "");
    if (StringUtils.isBlank(dataBase64)) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Image data required");
      return;
    }
    // Strip data URI prefix if present (e.g. "data:image/png;base64,...")
    if (dataBase64.contains(",")) {
      dataBase64 = dataBase64.substring(dataBase64.indexOf(',') + 1);
    }
    byte[] imageBytes = Base64.getDecoder().decode(dataBase64);
    Image image = OBProvider.getInstance().get(Image.class);
    image.setClient(OBContext.getOBContext().getCurrentClient());
    image.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    image.setName(name);
    image.setMimetype(mimeType);
    image.setBindaryData(imageBytes);
    OBDal.getInstance().save(image);
    OBDal.getInstance().flush();
    JSONObject result = new JSONObject();
    result.put("imageId", image.getId());
    result.put("name", image.getName());
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setStatus(HttpServletResponse.SC_OK);
    try (OutputStream out = response.getOutputStream()) {
      out.write(result.toString().getBytes(StandardCharsets.UTF_8));
    }
  }
}
