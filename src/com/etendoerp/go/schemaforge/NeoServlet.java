package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
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
import org.openbravo.model.ad.ui.Window;

import com.auth0.jwt.interfaces.DecodedJWT;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;
import com.etendoerp.go.schemaforge.data.SFEntity;
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

  private static final String METHOD_DELETE = "DELETE";
  private static final String METHOD_PATCH = "PATCH";
  private static final String PARAM_PARENT_ID = "parentId";
  private static final String ERR_ENTITY_NOT_FOUND = "Entity not found: ";
  private static final String ERR_NO_LINKED_TAB = "Entity has no linked AD_Tab: ";

  private final NeoDiscoveryHandler discoveryHandler = new NeoDiscoveryHandler(this);
  private final NeoButtonHandler buttonHandler = new NeoButtonHandler();
  private final NeoDisplayLogicHandler displayLogicHandler = new NeoDisplayLogicHandler();

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
        discoveryHandler.handleDiscovery(response);
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
        handleProcessSpecRequest(spec, method, request, response);
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
  private void handleProcessSpecRequest(SFSpec spec, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    Process adProcess = spec.getProcess();
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
    Process adReportProcess = spec.getProcess();
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
      discoveryHandler.handleSpecDescribe(response, spec);
      return;
    }
    if (handleWindowSubEndpoint(spec, pathInfo, method, request, response)) {
      return;
    }
    // CRUD entity handling
    handleWindowEntityCrud(spec, pathInfo, method, request, response);
  }

  /**
   * Routes window sub-endpoint requests (selectors, actions, evaluate-display, callout, defaults).
   * Returns true if the request was handled by a sub-endpoint, false if it should fall through to CRUD.
   */
  private boolean handleWindowSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
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
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Selectors only support GET");
      return true;
    }
    NeoResponse selectorResult = dispatchWithHooks(spec, pathInfo.entityName,
        NeoEndpointType.SELECTOR, pathInfo.selectorField, method,
        () -> handleSelector(spec.getId(), pathInfo, request));
    writeResponse(response, selectorResult);
    return true;
  }

  private boolean handleActionSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"POST".equals(method) && !"GET".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Actions support GET (list) and POST (execute)");
      return true;
    }
    NeoResponse actionResult = dispatchWithHooks(spec, pathInfo.entityName,
        NeoEndpointType.ACTION, pathInfo.actionName, method,
        () -> buttonHandler.handleButtonAction(spec, pathInfo, method, request));
    writeResponse(response, actionResult);
    return true;
  }

  private boolean handleEvaluateDisplaySubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"POST".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Method not allowed. Use POST.");
      return true;
    }
    NeoResponse evalResult = dispatchWithHooks(spec, pathInfo.entityName,
        NeoEndpointType.EVALUATE_DISPLAY, null, method,
        () -> displayLogicHandler.handleEvaluateDisplay(spec, pathInfo, request));
    writeResponse(response, evalResult);
    return true;
  }

  private boolean handleCalloutSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
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

  private boolean handleDefaultsSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
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
    boolean methodEnabled = isMethodEnabled(method, entity);
    if (!methodEnabled) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          method + " not enabled for " + pathInfo.entityName);
      return;
    }
    Tab adTab = entity.getADTab();
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
      neoContext = parseAndAttachRequestBody(neoContext, request, response);
      if (neoContext == null) {
        return;
      }
    }
    NeoResponse neoResponse = dispatchCrudRequest(entity, neoContext, request, response);
    if (neoResponse != null) {
      writeResponse(response, neoResponse);
    }
  }

  /**
   * Reads the HTTP request body, parses it as JSON, and returns a new NeoContext with the body
   * attached. Returns null and sends a 400 error response if the body is malformed.
   */
  private NeoContext parseAndAttachRequestBody(NeoContext neoContext,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    try {
      String bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (StringUtils.isBlank(bodyStr)) {
        return neoContext;
      }
      return NeoContext.builder()
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
    } catch (Exception e) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage());
      return null;
    }
  }

  /**
   * Dispatches a CRUD request to the appropriate handler (hooked or default).
   */
  private NeoResponse dispatchCrudRequest(SFEntity entity, NeoContext neoContext,
      HttpServletRequest request, HttpServletResponse response) {
    String javaQualifier = entity.getJavaQualifier();
    if (StringUtils.isNotBlank(javaQualifier)) {
      return handleWithHooks(javaQualifier, neoContext, request, response);
    }
    return handleDefault(neoContext, request, response);
  }

  /**
   * Returns true if the given HTTP method is enabled on the entity's configuration.
   */
  private boolean isMethodEnabled(String method, SFEntity entity) {
    if ("GET".equals(method)) {
      return Boolean.TRUE.equals(entity.isGet()) || Boolean.TRUE.equals(entity.isGetByID());
    } else if ("POST".equals(method)) {
      return Boolean.TRUE.equals(entity.isPost());
    } else if ("PUT".equals(method)) {
      return Boolean.TRUE.equals(entity.isPut());
    } else if (METHOD_PATCH.equals(method)) {
      return Boolean.TRUE.equals(entity.isPatch());
    } else if (METHOD_DELETE.equals(method)) {
      return Boolean.TRUE.equals(entity.isDelete());
    }
    return false;
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
    Tab adTab = (entity != null) ? entity.getADTab() : null;
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

    applyWhereClause(params, adTab, parentId);
    applyPaginationDefaults(params);
    return params;
  }

  /**
   * Builds the HQL where clause from the tab filter and parent filter, and adds it to params.
   */
  private void applyWhereClause(Map<String, String> params, Tab adTab, String parentId) {
    StringBuilder where = new StringBuilder();
    String tabWhere = adTab.getHqlwhereclause();
    if (StringUtils.isNotBlank(tabWhere)) {
      if (parentId != null && tabWhere.contains("@")) {
        tabWhere = tabWhere.replaceAll("@[A-Za-z_]+@", "'" + parentId.replace("'", "''") + "'");
      }
      where.append("(").append(tabWhere).append(")");
    }
    if (parentId != null && adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
      String parentFilter = resolveParentFilter(adTab, parentId);
      if (StringUtils.isNotBlank(parentFilter)) {
        if (where.length() > 0) {
          where.append(" and ");
        }
        where.append("(").append(parentFilter).append(")");
      }
    }
    if (where.length() > 0) {
      params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, where.toString());
      params.put(JsonConstants.USE_ALIAS, "true");
    }
  }

  /**
   * Applies default pagination parameters if not already provided by the caller.
   */
  private void applyPaginationDefaults(Map<String, String> params) {
    if (!params.containsKey(JsonConstants.STARTROW_PARAMETER)) {
      params.put(JsonConstants.STARTROW_PARAMETER, "0");
    }
    if (!params.containsKey(JsonConstants.ENDROW_PARAMETER)) {
      params.put(JsonConstants.ENDROW_PARAMETER, "100");
    }
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
        NeoResponse earlyError = validatePostRequest(context);
        if (earlyError != null) {
          return earlyError;
        }
        result = executePostCreate(context, adTab, dalEntityName, fieldFilter, jsonService, params);
        break;
      }
      case "PUT":
      case METHOD_PATCH: {
        NeoResponse earlyError = validateUpdateRequest(context);
        if (earlyError != null) {
          return earlyError;
        }
        result = executeUpdate(context, dalEntityName, fieldFilter, jsonService, params);
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
    NeoResponse errorResponse = checkJsonServiceResponse(responseJson);
    if (errorResponse != null) {
      return errorResponse;
    }
    fieldFilter.filterGetResponse(responseJson);
    return NeoResponse.ok(responseJson);
  }

  /**
   * Validates a POST request. Returns an error NeoResponse if invalid, null if the request is OK.
   */
  private NeoResponse validatePostRequest(NeoContext context) {
    if (context.getRecordId() != null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "POST (create) must not include a record ID in the URL");
    }
    return null;
  }

  /**
   * Validates a PUT/PATCH request. Returns an error NeoResponse if invalid, null if OK.
   */
  private NeoResponse validateUpdateRequest(NeoContext context) {
    if (context.getRecordId() == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          context.getHttpMethod() + " requires a record ID in the URL");
    }
    return null;
  }

  /**
   * Validates the response for error/validation-error status codes.
   * Returns an error NeoResponse if a failure is detected, or null if the response is OK.
   */
  private NeoResponse checkJsonServiceResponse(JSONObject responseJson) throws Exception {
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
   * Executes the POST (create) JSON service operation and returns the raw result string.
   */
  private String executePostCreate(NeoContext context, Tab adTab, String dalEntityName,
      NeoFieldFilter fieldFilter, DefaultJsonDataService jsonService,
      Map<String, String> params) throws Exception {
    JSONObject requestBody = context.getRequestBody();
    String parentIdValue = null;
    if (requestBody != null && requestBody.has(PARAM_PARENT_ID)) {
      parentIdValue = requestBody.getString(PARAM_PARENT_ID);
      requestBody.remove(PARAM_PARENT_ID);
      injectParentIdAsProperty(adTab, requestBody, parentIdValue);
    }
    JSONObject filteredBody = fieldFilter.filterWriteRequest(requestBody);
    NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, context, parentIdValue);
    String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, null);
    return jsonService.add(params, wrappedBody);
  }

  /**
   * Maps the generic "parentId" value to the actual FK property name on the child entity.
   */
  private void injectParentIdAsProperty(Tab adTab, JSONObject requestBody, String parentIdValue) {
    if (adTab.getTabLevel() == null || adTab.getTabLevel() <= 0) {
      return;
    }
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
        } catch (Exception ex) {
          log.debug("Could not resolve parent column for '{}': {}", col.getDBColumnName(), ex.getMessage());
        }
      }
    }
  }

  /**
   * Executes the PUT/PATCH (update) JSON service operation and returns the raw result string.
   */
  private String executeUpdate(NeoContext context, String dalEntityName,
      NeoFieldFilter fieldFilter, DefaultJsonDataService jsonService,
      Map<String, String> params) throws Exception {
    JSONObject filteredBody = fieldFilter.filterWriteRequest(context.getRequestBody());
    String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, context.getRecordId());
    return jsonService.update(params, wrappedBody);
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
   * Resolves the HQL filter expression that constrains child tab records by parent record ID.
   */
  private String resolveParentFilter(Tab childTab, String parentId) {
    try {
      Tab parentTab = KernelUtils.getInstance().getParentTab(childTab);
      if (parentTab == null) return null;
      String parentProperty = ApplicationUtils.getParentProperty(childTab, parentTab);
      if (StringUtils.isBlank(parentProperty)) return null;
      Entity childEntity = ModelProvider.getInstance().getEntityByTableId(childTab.getTable().getId());
      Property prop = childEntity.getProperty(parentProperty);
      String escaped = parentId.replace("'", "''");
      return (prop != null && !prop.isPrimitive())
          ? "e." + parentProperty + ".id='" + escaped + "'"
          : "e." + parentProperty + "='" + escaped + "'";
    } catch (Exception e) {
      log.error("Error resolving parent filter for tab '{}': {}", childTab.getName(), e.getMessage(), e);
      return null;
    }
  }

  /**
   * Handle a process-type spec POST. Reads the request body as JSON
   * and delegates to NeoProcessService.
   */
  private void handleProcessSpec(SFSpec spec, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      Process adProcess = spec.getProcess();
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
      Process adProcess = spec.getProcess();
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

  void writeResponse(HttpServletResponse response, NeoResponse neoResponse)
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

  private NeoResponse handleSelector(String specId,
      NeoPathInfo pathInfo, HttpServletRequest request) {
    if (pathInfo.selectorField == null) {
      // List all available selectors
      return NeoSelectorService.listSelectors(specId, pathInfo.entityName);
    }
    // Query a specific selector
    String search = request.getParameter("q");
    int limit = 20;
    int offset = 0;
    try {
      limit = Integer.parseInt(request.getParameter("limit"));
    } catch (NumberFormatException ignored) {
      // Use default limit if the parameter is absent or not a valid integer
    }
    try {
      offset = Integer.parseInt(request.getParameter("offset"));
    } catch (NumberFormatException ignored) {
      // Use default offset if the parameter is absent or not a valid integer
    }

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

  void sendError(HttpServletResponse response, int status, String message)
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
