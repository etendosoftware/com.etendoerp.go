package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
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
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
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
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;
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

  private static final String METHOD_DELETE = "DELETE";
  private static final String METHOD_PATCH = "PATCH";
  private static final String PARAM_PARENT_ID = "parentId";
  private static final String TYPE_STRING = "string";
  private static final String ERR_ENTITY_NOT_FOUND = "Entity not found: ";
  private static final String ERR_NO_LINKED_TAB = "Entity has no linked AD_Tab: ";

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
    processRequest(request, response, METHOD_DELETE);
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (METHOD_PATCH.equalsIgnoreCase(request.getMethod())) {
      processRequest(request, response, METHOD_PATCH);
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
    NeoPathInfo pathInfo;
    try {
      pathInfo = parsePath(request.getPathInfo());
    } catch (IllegalArgumentException e) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
      return;
    }

    // 3. Resolve spec, entity, and tab
    try {
      OBContext.setAdminMode();

      // Discovery mode: list all specs
      if (pathInfo.specName == null) {
        if (!"GET".equals(method)) {
          sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
              "Discovery endpoint only supports GET");
          return;
        }
        handleDiscovery(response);
        return;
      }

      // Find the spec
      SFSpec spec = findSpec(pathInfo.specName);
      if (spec == null) {
        sendError(response, HttpServletResponse.SC_NOT_FOUND,
            "Spec not found: " + pathInfo.specName);
        return;
      }

      // Route by spec type
      String specType = spec.getSpecType();
      if ("P".equals(specType)) {
        handleProcessSpecRequest(spec, pathInfo, method, request, response);
        return;
      }
      if ("R".equals(specType)) {
        handleReportSpecRequest(spec, pathInfo, method, request, response);
        return;
      }

      // Window spec routing
      handleWindowSpecRequest(spec, pathInfo, method, request, response);

    } catch (Exception e) {
      log.error("Error processing NEO request: {}", e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Handles process-type spec requests (specType = "P").
   * Checks process access, describes on GET, executes on POST.
   */
  private void handleProcessSpecRequest(SFSpec spec, NeoPathInfo pathInfo, String method,
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

  /**
   * Handles report-type spec requests (specType = "R").
   * Checks for custom handler qualifier first, then falls back to standard Jasper report flow.
   */
  private void handleReportSpecRequest(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    String reportHandlerQualifier = resolveReportHandlerQualifier(spec);
    if (reportHandlerQualifier != null) {
      JSONObject requestBody = null;
      String bodyStr = new String(request.getInputStream().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      if (StringUtils.isNotBlank(bodyStr)) {
        requestBody = new JSONObject(bodyStr);
      }
      NeoContext handlerContext = NeoContext.builder()
          .specName(pathInfo.specName)
          .entityName(pathInfo.specName)
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

  /**
   * Handles window-type spec requests (specType = "W" or default).
   * Enforces window access, routes sub-endpoints (selectors, actions, evaluate-display,
   * callout, defaults), then falls through to CRUD entity handling.
   */
  private void handleWindowSpecRequest(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    Window window = spec.getADWindow();
    if (window != null && !hasWindowAccess(window.getId())) {
      sendError(response, HttpServletResponse.SC_FORBIDDEN,
          "Access denied to window for current role");
      return;
    }
    if (pathInfo.entityName == null) {
      if (!"GET".equals(method)) {
        sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Spec describe only supports GET");
        return;
      }
      handleSpecDescribe(response, spec);
      return;
    }
    if (pathInfo.isSelector) {
      if (!"GET".equals(method)) {
        sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Selectors only support GET");
        return;
      }
      NeoResponse selectorResult = dispatchWithHooks(spec, pathInfo.entityName,
          NeoEndpointType.SELECTOR, pathInfo.selectorField, method,
          () -> handleSelector(spec.getId(), pathInfo, request));
      writeResponse(response, selectorResult);
      return;
    }
    if (pathInfo.isAction) {
      if (!"POST".equals(method) && !"GET".equals(method)) {
        sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Actions support GET (list) and POST (execute)");
        return;
      }
      NeoResponse actionResult = dispatchWithHooks(spec, pathInfo.entityName,
          NeoEndpointType.ACTION, pathInfo.actionName, method,
          () -> handleButtonAction(spec, pathInfo, method, request));
      writeResponse(response, actionResult);
      return;
    }
    if (pathInfo.isEvaluateDisplay) {
      if (!"POST".equals(method)) {
        sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Method not allowed. Use POST.");
        return;
      }
      NeoResponse evalResult = dispatchWithHooks(spec, pathInfo.entityName,
          NeoEndpointType.EVALUATE_DISPLAY, null, method,
          () -> handleEvaluateDisplay(spec, pathInfo, request));
      writeResponse(response, evalResult);
      return;
    }
    if (pathInfo.isCallout) {
      if (!"POST".equals(method)) {
        sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Callout endpoint only supports POST");
        return;
      }
      NeoResponse calloutResult = dispatchWithHooks(spec, pathInfo.entityName,
          NeoEndpointType.CALLOUT, null, method,
          () -> handleCallout(spec, pathInfo, request));
      writeResponse(response, calloutResult);
      return;
    }
    if (pathInfo.isDefaults) {
      if (!"GET".equals(method)) {
        sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Defaults endpoint only supports GET");
        return;
      }
      NeoResponse defaultsResult = dispatchWithHooks(spec, pathInfo.entityName,
          NeoEndpointType.DEFAULTS, null, method,
          () -> handleDefaults(spec, pathInfo, request));
      writeResponse(response, defaultsResult);
      return;
    }
    // CRUD entity handling
    handleWindowEntityCrud(spec, pathInfo, method, request, response);
  }

  /**
   * Handles CRUD operations on a window entity: resolves the entity, validates the method,
   * builds context (including request body), and dispatches to default or hooked handler.
   */
  private void handleWindowEntityCrud(SFSpec spec, NeoPathInfo pathInfo, String method,
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
    if ("POST".equals(method) || "PUT".equals(method) || METHOD_PATCH.equals(method)) {
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
    criteria.add(Restrictions.ilike(SFSpec.PROPERTY_NAME, specName, MatchMode.EXACT));
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
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, entityName, MatchMode.EXACT));
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
      case METHOD_PATCH:
        return Boolean.TRUE.equals(entity.isPatch());
      case METHOD_DELETE:
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
      NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(
          context.getSfEntity(), dalEntityName);
      Map<String, String> params = buildDalParams(context, adTab, dalEntityName);

      return executeJsonServiceAndBuildResponse(
          context, adTab, dalEntityName, fieldFilter, jsonService, params);
    } catch (Exception e) {
      log.error("Error in default handler for {} {}", context.getHttpMethod(), context.getEntityName(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Builds the DAL parameter map for a request, including tab metadata,
   * record ID, query params, where clause, and pagination defaults.
   */
  private Map<String, String> buildDalParams(NeoContext context, Tab adTab, String dalEntityName) {
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.ENTITYNAME, dalEntityName);
    params.put(JsonConstants.TAB_PARAMETER, adTab.getId());
    params.put(JsonConstants.WINDOW_ID, adTab.getWindow().getId());
    params.put(JsonConstants.NO_ACTIVE_FILTER, "true");

    if (context.getRecordId() != null) {
      params.put(JsonConstants.ID, context.getRecordId());
    }
    if (context.getQueryParams() != null) {
      params.putAll(context.getQueryParams());
    }

    String parentId = context.getQueryParams() != null
        ? context.getQueryParams().get(PARAM_PARENT_ID)
        : null;

    String whereClause = buildWhereClause(adTab, parentId);
    if (StringUtils.isNotBlank(whereClause)) {
      params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, whereClause);
      params.put(JsonConstants.USE_ALIAS, "true");
    }

    if (!params.containsKey(JsonConstants.STARTROW_PARAMETER)) {
      params.put(JsonConstants.STARTROW_PARAMETER, "0");
    }
    if (!params.containsKey(JsonConstants.ENDROW_PARAMETER)) {
      params.put(JsonConstants.ENDROW_PARAMETER, "100");
    }
    return params;
  }

  /**
   * Builds the HQL where clause combining the tab's own HQL filter and the parent filter.
   */
  private String buildWhereClause(Tab adTab, String parentId) {
    StringBuilder whereClause = new StringBuilder();
    String tabWhere = adTab.getHqlwhereclause();
    if (StringUtils.isNotBlank(tabWhere)) {
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
    return whereClause.toString();
  }

  /**
   * Executes the appropriate JSON service operation (fetch/add/update/remove),
   * checks the response for errors, filters the response fields, and returns the NeoResponse.
   */
  private NeoResponse executeJsonServiceAndBuildResponse(NeoContext context, Tab adTab,
      String dalEntityName, NeoFieldFilter fieldFilter,
      DefaultJsonDataService jsonService, Map<String, String> params) throws Exception {
    String result;
    switch (context.getHttpMethod()) {
      case "GET":
        result = jsonService.fetch(params);
        break;
      case "POST": {
        if (context.getRecordId() != null) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "POST (create) must not include a record ID in the URL");
        }
        JSONObject requestBody = context.getRequestBody();
        String parentIdValue = null;
        if (requestBody != null && requestBody.has(PARAM_PARENT_ID)) {
          parentIdValue = requestBody.getString(PARAM_PARENT_ID);
          requestBody.remove(PARAM_PARENT_ID);
          if (adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
            injectParentIdIntoBody(requestBody, adTab, parentIdValue);
          }
        }
        JSONObject filteredBody = fieldFilter.filterWriteRequest(requestBody);
        NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, context, parentIdValue);
        String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, null);
        result = jsonService.add(params, wrappedBody);
        break;
      }
      case "PUT":
      case METHOD_PATCH: {
        if (context.getRecordId() == null) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              context.getHttpMethod() + " requires a record ID in the URL");
        }
        JSONObject filteredBody = fieldFilter.filterWriteRequest(context.getRequestBody());
        String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, context.getRecordId());
        result = jsonService.update(params, wrappedBody);
        break;
      }
      case METHOD_DELETE:
        result = jsonService.remove(params);
        break;
      default:
        return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Unsupported method: " + context.getHttpMethod());
    }

    JSONObject responseJson = new JSONObject(result);
    NeoResponse serviceError = checkServiceErrorResponse(responseJson);
    if (serviceError != null) {
      return serviceError;
    }
    fieldFilter.filterGetResponse(responseJson);
    return NeoResponse.ok(responseJson);
  }

  /**
   * Checks the JSON response from DefaultJsonDataService for failure or validation error status.
   * Returns an error NeoResponse if found, null if the response is successful.
   */
  private NeoResponse checkServiceErrorResponse(JSONObject responseJson) throws Exception {
    JSONObject innerResponse = responseJson.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
    if (innerResponse == null) {
      return null;
    }
    int status = innerResponse.optInt(JsonConstants.RESPONSE_STATUS, 0);
    if (status == JsonConstants.RPCREQUEST_STATUS_FAILURE) {
      String errMsg = innerResponse.has(JsonConstants.RESPONSE_ERROR)
          ? innerResponse.getJSONObject(JsonConstants.RESPONSE_ERROR)
              .optString("message", "Write operation failed")
          : "Write operation failed";
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errMsg);
    }
    if (status == JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, responseJson);
    }
    return null;
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

  /**
   * Resolves the DAL property name for the link-to-parent column and injects
   * the parentId value into the request body under that property name.
   * This maps the generic "parentId" field to the actual FK property
   * (e.g., parentId -> salesOrder for C_OrderLine).
   */
  private void injectParentIdIntoBody(JSONObject requestBody, Tab adTab, String parentIdValue) {
    Entity dalEnt = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEnt == null) {
      return;
    }
    for (Column col : adTab.getTable().getADColumnList()) {
      if (col.isLinkToParentColumn() && col.isActive()) {
        try {
          Property prop = dalEnt.getPropertyByColumnName(col.getDBColumnName());
          if (prop != null) {
            requestBody.put(prop.getName(), parentIdValue);
            break;
          }
        } catch (Exception e) {
          log.debug("Could not resolve parent column property for '{}': {}",
              col.getDBColumnName(), e.getMessage());
        }
      }
    }
  }

  /**
   * Builds an HQL where clause fragment that filters a child tab's records
   * by the parent record ID.
   */
  private String buildParentWhereClause(Tab childTab, String parentId) {
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
        return executeButtonAction(entity.getId(), pathInfo, request);
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
   * Lists all button-type actions available on an entity.
   * Returns a JSON array of action objects with columnName, processType, and processName.
   */
  private NeoResponse listButtonActions(String entityId) throws Exception {
    OBCriteria<SFField> fieldCriteria = OBDal.getInstance().createCriteria(SFField.class);
    fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    fieldCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    fieldCriteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    List<SFField> fields = fieldCriteria.list();

    JSONArray actions = new JSONArray();
    for (SFField field : fields) {
      Column column = field.getADColumn();
      if (column == null || column.getReference() == null
          || !"28".equals((String) column.getReference().getId())) {
        continue;
      }
      Process classicProcess = column.getProcess();
      Object obuiappProcess = column.getOBUIAPPProcess();
      if (classicProcess == null && obuiappProcess == null) {
        continue;
      }
      JSONObject actionObj = new JSONObject();
      actionObj.put("columnName", column.getDBColumnName());
      if (obuiappProcess != null) {
        actionObj.put("processType", "OBUIAPP");
        org.openbravo.client.application.Process obuiProc =
            (org.openbravo.client.application.Process) obuiappProcess;
        actionObj.put("processName", obuiProc.getName() != null ? obuiProc.getName() : "");
      } else {
        actionObj.put("processType", "Classic");
        actionObj.put("processName", classicProcess.getName() != null ? classicProcess.getName() : "");
      }
      actions.put(actionObj);
    }
    JSONObject responseBody = new JSONObject();
    responseBody.put("actions", actions);
    return NeoResponse.ok(responseBody);
  }

  /**
   * Executes a specific button action by column name.
   * Resolves the target column, validates it is a button with a process,
   * checks access, reads request body, and delegates to NeoProcessService.
   */
  private NeoResponse executeButtonAction(String entityId, NeoPathInfo pathInfo,
      HttpServletRequest request) throws Exception {
    OBCriteria<SFField> fieldCriteria = OBDal.getInstance().createCriteria(SFField.class);
    fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    fieldCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    fieldCriteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    List<SFField> fields = fieldCriteria.list();

    Column targetColumn = null;
    for (SFField field : fields) {
      Column column = field.getADColumn();
      if (column != null && pathInfo.actionName.equals(column.getDBColumnName())) {
        targetColumn = column;
        break;
      }
    }
    if (targetColumn == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Action not found: " + pathInfo.actionName);
    }
    if (targetColumn.getReference() == null
        || !"28".equals((String) targetColumn.getReference().getId())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Field is not a button: " + pathInfo.actionName);
    }
    Process adProcess = resolveButtonProcess(targetColumn);
    if (adProcess == null && targetColumn.getOBUIAPPProcess() == null) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "No process linked to button: " + pathInfo.actionName);
    }
    if (adProcess == null) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Button process not supported (OBUIAPP-only process without classic fallback): "
              + pathInfo.actionName);
    }
    if (!hasProcessAccess(adProcess.getId())) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          "Access denied to process for current role");
    }
    String bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    JSONObject params = StringUtils.isNotBlank(bodyStr) ? new JSONObject(bodyStr) : new JSONObject();
    params.put("recordId", pathInfo.recordId);
    return NeoProcessService.executeProcess(adProcess, params);
  }

  /**
   * Resolves the AD_Process for a button column, preferring classic process over OBUIAPP.
   * Returns null if only an OBUIAPP process exists without a classic fallback.
   */
  private Process resolveButtonProcess(Column targetColumn) {
    Process adProcess = targetColumn.getProcess();
    if (adProcess == null && targetColumn.getOBUIAPPProcess() != null) {
      // OBUIAPP-only: attempt classic process lookup as fallback
      adProcess = targetColumn.getProcess();
    }
    return adProcess;
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
        if (!hasSpecAccess(spec)) {
          continue;
        }
        specsArray.put(buildSpecSummaryObject(spec));
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
   * Returns true if the current user has access to the given spec (window, process, or report).
   */
  private boolean hasSpecAccess(SFSpec spec) {
    String specType = spec.getSpecType();
    if ("W".equals(specType)) {
      Window specWindow = spec.getADWindow();
      return specWindow == null || hasWindowAccess(specWindow.getId());
    }
    if ("P".equals(specType) || "R".equals(specType)) {
      Process adProcess = resolveProcess(spec);
      return adProcess == null || hasProcessAccess(adProcess.getId());
    }
    return true;
  }

  /**
   * Builds the discovery summary JSON object for a single spec.
   * Includes id, name, type, description, module, and type-specific fields.
   */
  private JSONObject buildSpecSummaryObject(SFSpec spec) throws Exception {
    String specType = spec.getSpecType();
    JSONObject specObj = new JSONObject();
    specObj.put("id", spec.getId());
    specObj.put("name", spec.getName());
    specObj.put("type", specType);
    specObj.put("description", spec.getDescription());

    if ("W".equals(specType)) {
      Window specWindow = spec.getADWindow();
      if (specWindow != null) specObj.put("windowId", specWindow.getId());
      specObj.put("entities", buildEntitySummaryArray(spec.getId()));
    } else if ("P".equals(specType) || "R".equals(specType)) {
      Process adProcess = resolveProcess(spec);
      if (adProcess != null) specObj.put("processId", adProcess.getId());
      if ("R".equals(specType)) specObj.put("isReport", true);
    }

    Module specModule = spec.getADModule();
    if (specModule != null) specObj.put("moduleId", specModule.getId());
    return specObj;
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
      methods.put(METHOD_PATCH);
    }
    if (Boolean.TRUE.equals(entity.isDelete())) {
      methods.put(METHOD_DELETE);
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
    if (refId == null) return TYPE_STRING;
    switch (refId) {
      case "10": case "14": case "34": // String, Text, Memo
        return TYPE_STRING;
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
        return TYPE_STRING;
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
            ERR_ENTITY_NOT_FOUND + pathInfo.entityName);
      }

      Tab tab = sfEntity.getADTab();
      if (tab == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            ERR_NO_LINKED_TAB + pathInfo.entityName);
      }

      // Parse request body
      JSONObject fieldValues = parseFieldValuesFromRequest(request);
      if (fieldValues == null) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid request body");
      }

      // Build evaluation context and evaluate all fields
      Map<String, Object> evalContext = buildEvalContext(fieldValues);
      JSONObject result = evaluateAllFieldExpressions(tab, evalContext);
      return NeoResponse.ok(result);

    } catch (Exception e) {
      log.error("Error evaluating display logic for {}/{}", pathInfo.specName,
          pathInfo.entityName, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Error evaluating display logic: " + e.getMessage());
    }
  }

  /**
   * Evaluates displayLogic and readOnlyLogic for all active fields in the given tab.
   * Returns a JSON object with "visibility" and "readOnly" maps keyed by DAL property name.
   */
  private JSONObject evaluateAllFieldExpressions(Tab tab, Map<String, Object> evalContext)
      throws Exception {
    JSONObject visibility = new JSONObject();
    JSONObject readOnly = new JSONObject();
    for (Field field : tab.getADFieldList()) {
      if (!field.isActive()) {
        continue;
      }
      String propertyName = getPropertyName(field);
      String displayLogic = field.getDisplayLogic();
      if (displayLogic != null && !displayLogic.trim().isEmpty()) {
        visibility.put(propertyName, evaluateExpression(displayLogic, tab, field, evalContext));
      }
      Column column = field.getColumn();
      if (column != null) {
        String readOnlyLogic = column.getReadOnlyLogic();
        if (readOnlyLogic != null && !readOnlyLogic.trim().isEmpty()) {
          readOnly.put(propertyName, evaluateExpression(readOnlyLogic, tab, field, evalContext));
        }
      }
    }
    JSONObject result = new JSONObject();
    result.put("visibility", visibility);
    result.put("readOnly", readOnly);
    return result;
  }

  /**
   * Evaluates a single display logic or readOnly logic expression.
   * 4-step pipeline: preprocess -> parse -> shim -> eval.
   *
   * @param expression   the raw AD expression
   * @param tab          the AD_Tab for context resolution
   * @param field        the AD_Field for field-type detection (may be null for tab-level)
   * @param evalContext  the evaluation context with field values and session data
   * @return evaluation result; on failure: true for display (show), true for readOnly (lock)
   */
  private boolean evaluateExpression(String expression, Tab tab, Field field,
      Map<String, Object> evalContext) {
    try {
      // Step 1: Replace system preferences and macros (static, pre-parser)
      String preprocessed = DynamicExpressionParser
          .replaceSystemPreferencesInDisplayLogic(expression);

      // Step 2: Parse expression -- resolves session vars, auxiliary inputs,
      // field references, accounting dimensions
      DynamicExpressionParser parser =
          new DynamicExpressionParser(preprocessed, tab, field);
      String jsExpr = parser.getJSExpression();

      // Step 3: Prepend OB.Utilities shim so Rhino can evaluate
      // SmartClient-dependent code (OB.Utilities.getValue, OB.Utilities.Date.JSToOB)
      String fullScript = OB_UTILITIES_SHIM + "\n" + jsExpr;

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
   * Parses the "fieldValues" object from the request body for evaluate-display requests.
   * Returns an empty JSONObject if the body is absent or has no "fieldValues" key.
   * Returns null if the body is present but unparseable (signals a 400 error to the caller).
   */
  private JSONObject parseFieldValuesFromRequest(HttpServletRequest request) {
    try {
      String body = new String(
          request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (body == null || body.trim().isEmpty()) {
        return new JSONObject();
      }
      JSONObject bodyJson = new JSONObject(body);
      return bodyJson.has("fieldValues")
          ? bodyJson.getJSONObject("fieldValues")
          : new JSONObject();
    } catch (Exception e) {
      log.debug("Failed to parse evaluate-display request body: {}", e.getMessage());
      return null;
    }
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

    // Resolve $Element_* accounting dimension preferences.
    // DynamicExpressionParser generates JS referencing context.$Element_XX
    // for displayLogic expressions like @$Element_MC@='Y'.
    try {
      DalConnectionProvider conn = new DalConnectionProvider(false);
      VariablesSecureApp vars = new VariablesSecureApp(
          obCtx.getUser().getId(),
          obCtx.getCurrentClient().getId(),
          obCtx.getCurrentOrganization().getId(),
          obCtx.getRole().getId(),
          obCtx.getLanguage().getLanguage());
      String[] elements = { "MC", "AY", "OT", "AS", "CC", "U1", "U2", "PJ", "BU", "PR" };
      for (String el : elements) {
        String key = "$Element_" + el;
        String value = Utility.getContext(conn, vars, key, "");
        ctx.put(key, value);
      }
    } catch (Exception e) {
      log.debug("Could not resolve $Element_* preferences: {}", e.getMessage());
    }

    // Add a "context" object alias so DynamicExpressionParser's JS output
    // (context.xxx references) can resolve against our eval context
    ctx.put("context", ctx);

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
            ERR_ENTITY_NOT_FOUND + pathInfo.entityName);
      }

      Tab tab = sfEntity.getADTab();
      if (tab == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            ERR_NO_LINKED_TAB + pathInfo.entityName);
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
            ERR_ENTITY_NOT_FOUND + pathInfo.entityName);
      }

      Tab tab = sfEntity.getADTab();
      if (tab == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            ERR_NO_LINKED_TAB + pathInfo.entityName);
      }

      String parentId = request.getParameter(PARAM_PARENT_ID);

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
}
