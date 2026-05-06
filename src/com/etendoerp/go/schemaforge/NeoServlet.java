package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.annotation.MultipartConfig;
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
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.auth0.jwt.interfaces.DecodedJWT;
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
@MultipartConfig(maxFileSize = 10L * 1024 * 1024, maxRequestSize = 12L * 1024 * 1024, fileSizeThreshold = 1024 * 1024)
public class NeoServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(NeoServlet.class);

  private static final String METHOD_DELETE = "DELETE";
  private static final String METHOD_PATCH = "PATCH";
  private static final String PARAM_PARENT_ID = "parentId";
  private static final String ERR_ENTITY_NOT_FOUND = "Entity not found: ";
  private static final String ERR_NO_LINKED_TAB = "Entity has no linked AD_Tab: ";
  private static final String HOOK_ERROR_MSG = "An internal error occurred while processing the hook handler";
  public static final String ACTION_REQUEST_BODY_ATTR = "neo.action.requestBody";
  private static final String KEY_UPDATES = "updates";
  private static final String KEY_COMBOS = "combos";

  private final NeoDiscoveryHandler discoveryHandler = new NeoDiscoveryHandler(this);
  private final NeoBuiltInEndpointHandler builtInEndpointHandler =
      new NeoBuiltInEndpointHandler(this, discoveryHandler);
  private final NeoButtonHandler buttonHandler = new NeoButtonHandler();
  private final NeoDisplayLogicHandler displayLogicHandler = new NeoDisplayLogicHandler();
  // Package-private so sibling collaborators (BatchService) can dispatch through
  // the same default CRUD pipeline without going via HTTP. The class itself is
  // already package-private; widening the field follows the same scope.
  final NeoCrudHandler crudHandler = new NeoCrudHandler(this);
  private final BatchService batchService = new BatchService(this);

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
    if (!authenticateRequest(request, response)) {
      return;
    }

    NeoPathInfo pathInfo = parseRequestPath(request, response);
    if (pathInfo == null) {
      return;
    }

    try {
      OBContext.setAdminMode();
      if (builtInEndpointHandler.handle(pathInfo, method, request, response)) {
        return;
      }

      // Generic transactional batch endpoint: POST /sws/neo/batch
      //   Runs an ordered list of CRUD ops in one OBDal transaction with
      //   $ref:<opId> substitution between ops. Same primitive is consumed by
      //   the React UI (composite-document ingest) and external agents (MCP).
      //   Find-or-create logic stays with the caller — no per-window server code.
      if ("batch".equals(pathInfo.specName)) {
        if (!"POST".equals(method)) {
          sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
              "Batch endpoint only supports POST");
          return;
        }
        batchService.handle(request, response);
        return;
      }

      handleSpecRequest(pathInfo, method, request, response);
    } catch (Exception e) {
      log.error("Error processing NEO request: {}", e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private boolean authenticateRequest(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      authenticateJwt(request);
      return true;
    } catch (OBException e) {
      // OBException messages are safe to expose (we control them)
      log.warn("Unauthorized NEO request: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return false;
    } catch (Exception e) {
      // Other exceptions (JWT decode failures, NPEs) don't leak internals.
      log.warn("Unauthorized NEO request: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return false;
    }
  }

  private NeoPathInfo parseRequestPath(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      NeoPathInfo pathInfo = NeoServletSupport.parsePath(request.getPathInfo());
      if (pathInfo != null) {
        return pathInfo;
      }
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Unable to parse request path");
    } catch (IllegalArgumentException e) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    }
    return null;
  }

  private void handleSpecRequest(NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    SFSpec spec = NeoServletSupport.findSpec(pathInfo.specName);
    if (spec == null) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND,
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
      JSONObject requestBody = parseOptionalJsonObject(readRequestBody(request));
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
    crudHandler.handleWindowEntityCrud(spec, pathInfo, method, request, response);
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
    return handleHookedSubEndpoint(new HookedSubEndpointRequest(spec, pathInfo.entityName,
        NeoEndpointType.SELECTOR, pathInfo.selectorField, method, null,
        () -> handleSelector(spec.getId(), pathInfo, request)), response);
  }

  private boolean handleActionSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"POST".equals(method) && !"GET".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Actions support GET (list) and POST (execute)");
      return true;
    }
    ActionDispatchParams actionParams = resolveActionDispatchParams(pathInfo, method, request,
        response);
    if (actionParams == null) {
      return true;
    }
    SFEntity actionEntity = findEntity(spec.getId(), pathInfo.entityName);
    if (actionEntity == null) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Entity not found in spec: " + pathInfo.entityName);
      return true;
    }
    return handleHookedSubEndpoint(new HookedSubEndpointRequest(spec, pathInfo.entityName,
        NeoEndpointType.ACTION, pathInfo.actionName, method, actionParams,
        () -> buttonHandler.handleButtonAction(pathInfo, method, request, actionEntity)), response);
  }

  private boolean handleEvaluateDisplaySubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"POST".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Method not allowed. Use POST.");
      return true;
    }
    return handleHookedSubEndpoint(new HookedSubEndpointRequest(spec, pathInfo.entityName,
        NeoEndpointType.EVALUATE_DISPLAY, null, method, null,
        () -> displayLogicHandler.handleEvaluateDisplay(spec, pathInfo, request)), response);
  }

  private boolean handleCalloutSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"POST".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Callout endpoint only supports POST");
      return true;
    }
    return handleHookedSubEndpoint(new HookedSubEndpointRequest(spec, pathInfo.entityName,
        NeoEndpointType.CALLOUT, null, method, null,
        () -> handleCallout(spec, pathInfo, request)), response);
  }

  private boolean handleDefaultsSubEndpoint(SFSpec spec, NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!"GET".equals(method)) {
      sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Defaults endpoint only supports GET");
      return true;
    }
    return handleHookedSubEndpoint(new HookedSubEndpointRequest(spec, pathInfo.entityName,
        NeoEndpointType.DEFAULTS, null, method, null,
        () -> handleDefaults(spec, pathInfo, request)), response);
  }

  private boolean handleHookedSubEndpoint(HookedSubEndpointRequest request,
      HttpServletResponse response) throws IOException {
    NeoResponse endpointResult = request.actionParams == null
        ? dispatchWithHooks(request.spec, request.entityName, request.endpointType,
            request.fieldName, request.method, request.defaultAction)
        : dispatchWithHooks(request.spec, request.entityName, request.endpointType,
            request.fieldName, request.method, request.actionParams, request.defaultAction);
    writeResponse(response, endpointResult);
    return true;
  }

  private ActionDispatchParams resolveActionDispatchParams(NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    ActionDispatchParams actionParams = new ActionDispatchParams(pathInfo.recordId, null);
    if (!"POST".equals(method)) {
      return actionParams;
    }
    try {
      String bodyStr = readRequestBody(request);
      request.setAttribute(ACTION_REQUEST_BODY_ATTR, bodyStr);
      return new ActionDispatchParams(pathInfo.recordId, parseOptionalJsonObject(bodyStr));
    } catch (Exception e) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid JSON body: " + e.getMessage());
      return null;
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
    // The JWT warehouse may have been set to an inaccessible warehouse by the token generator
    // (SecureWebServicesUtils.getWarehouse() falls back to warehouseList.get(0) when the user
    // has no default, without filtering by client). Validate it against the user's readable orgs;
    // if inaccessible, find the first warehouse the user can actually access.
    if (context.getWarehouse() != null) {
      String whOrgId = context.getWarehouse().getOrganization().getId();
      boolean accessible = false;
      for (String readableOrg : context.getReadableOrganizations()) {
        if (readableOrg.equals(whOrgId)) {
          accessible = true;
          break;
        }
      }
      if (!accessible) {
        log.warn("JWT warehouse '{}' (org='{}') is not in user '{}' readable orgs — resolving accessible warehouse",
            warehouseId, whOrgId, userId);
        String correctedWarehouseId = NeoServletSupport.findAccessibleWarehouse(context);
        context = SecureWebServicesUtils.createContext(userId, roleId, orgId, correctedWarehouseId, clientId);
      }
    }
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);
  }

  /**
   * Find an active, included ETGO_SF_Entity by parent spec ID and entity name.
   */
  SFEntity findEntity(String specId, String entityName) {
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

  Map<String, String> extractQueryParams(HttpServletRequest request) {
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
  NeoResponse handleWithHooks(String javaQualifier, NeoContext context,
      HttpServletRequest request, HttpServletResponse response) {
    try {
      NeoHandler handler = lookupHandler(javaQualifier);
      if (handler == null) {
        log.warn("No handler found for qualifier '{}', falling back to default", javaQualifier);
        return crudHandler.handleDefault(context);
      }

      // Pre-hook
      NeoResponse preResult = handler.handle(context);
      if (preResult != null) {
        context.setPreviousResult(preResult);
        NeoResponse afterResult = handler.afterHandle(context);
        return afterResult != null ? afterResult : preResult;
      }

      // Default service
      NeoResponse defaultResult = crudHandler.handleDefault(context);

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
    return dispatchWithHooks(spec, entityName, endpointType, fieldName, httpMethod, null,
        defaultAction);
  }

  /**
   * Handle a process-type spec POST. Reads the request body as JSON
   * and delegates to NeoProcessService.
   */
  private static class ActionDispatchParams {
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

    NeoContext hookCtx = buildHookContext(spec, entityName, endpointType, fieldName,
        httpMethod, entity, actionParams);
    return executeHookChain(handler, hookCtx, defaultAction, endpointType, entityName);
  }

  private NeoContext buildHookContext(SFSpec spec, String entityName,
      NeoEndpointType endpointType, String fieldName, String httpMethod,
      SFEntity entity, ActionDispatchParams actionParams) {
    Tab adTab = entity != null ? entity.getADTab() : null;
    NeoContext.Builder contextBuilder = NeoContext.builder()
        .specName(spec.getName())
        .entityName(entityName)
        .httpMethod(httpMethod)
        .endpointType(endpointType)
        .fieldName(fieldName)
        .sfEntity(entity)
        .adTab(adTab)
        .obContext(OBContext.getOBContext());
    if (actionParams != null) {
      contextBuilder.recordId(actionParams.recordId)
          .requestBody(actionParams.requestBody);
    }
    return contextBuilder.build();
  }

  private NeoResponse executeHookChain(
      NeoHandler handler, NeoContext hookCtx,
      java.util.function.Supplier<NeoResponse> defaultAction,
      NeoEndpointType endpointType, String entityName) {
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
      JSONObject requestBody = parseOptionalJsonObject(readRequestBody(request));

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
      JSONObject body = parseJsonObjectOrEmpty(readRequestBody(request));
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
    return NeoServletSupport.hasWindowAccess(windowId);
  }

  /**
   * Check if the current role has access to the given process.
   */
  private boolean hasProcessAccess(String processId) {
    return NeoServletSupport.hasProcessAccess(processId);
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
      String bodyStr = readRequestBody(request);
      if (StringUtils.isBlank(bodyStr)) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Request body is required for callout execution");
      }

      JSONObject requestBody;
      try {
        requestBody = parseJsonObject(bodyStr);
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
          .endpointType(NeoEndpointType.CALLOUT)
          .requestBody(requestBody)
          .adTab(tab)
          .sfEntity(sfEntity)
          .obContext(OBContext.getOBContext())
          .build();

      // Execute the callout
      NeoResponse calloutResult = NeoCalloutService.executeCallout(neoContext, requestBody);

      // Cascade: if the callout set fields that have further callouts (e.g., SL_Order_Product
      // sets tax+grossUnitPrice for gross-price lists → SL_Order_Amt derives unitPrice),
      // chain those callouts and merge the results back into the response.
      // This is done here (not in NeoCalloutService) to avoid recursion when
      // executeSingleCallout calls NeoCalloutService.executeCallout internally.
      if (calloutResult.getHttpStatus() == 200 && calloutResult.getBody() != null) {
        String fieldName = requestBody.optString("field", "");
        JSONObject formState = requestBody.optJSONObject("formState");
        NeoDefaultsService.CalloutCascadeResult cascade =
            NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
                neoContext, tab, fieldName, formState, calloutResult.getBody());
        if (cascade.hasResults()) {
          mergeCalloutResponse(calloutResult.getBody(), cascade.toJSON());
          log.debug("[NEO-CALLOUT] Cascade merged additional fields into response");
        }
      }

      // Post-hook: give the entity's NeoHandler a chance to enrich the callout response
      // (e.g. add a synthetic 'taxRate' update when the trigger is C_Tax_ID). Same dispatch
      // shape as `handleWithHooks` — looked up by the entity's Java_Qualifier and merged
      // without overwriting fields already set by the underlying callout.
      if (calloutResult.getHttpStatus() == 200 && calloutResult.getBody() != null) {
        String qualifier = sfEntity.getJavaQualifier();
        if (StringUtils.isNotBlank(qualifier)) {
          NeoHandler handler = lookupHandler(qualifier);
          if (handler != null) {
            try {
              neoContext.setPreviousResult(calloutResult);
              NeoResponse handlerResult = handler.afterCallout(neoContext);
              if (handlerResult != null && handlerResult.getBody() != null) {
                mergeCalloutResponse(calloutResult.getBody(), handlerResult.getBody());
                log.debug("[NEO-CALLOUT] Handler '{}' merged additional fields via afterCallout",
                    qualifier);
              }
            } catch (Exception e) {
              log.warn("[NEO-CALLOUT] afterCallout for handler '{}' failed (non-fatal): {}",
                  qualifier, e.getMessage());
            }
          }
        }
      }

      return calloutResult;
    } catch (Exception e) {
      log.error("Error handling callout for {}/{}: {}",
          pathInfo.specName, pathInfo.entityName, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Callout error: " + e.getMessage());
    }
  }

  /**
   * Merge cascade results into an existing REST callout response.
   * Does not overwrite fields already set by the initial callout.
   */
  private void mergeCalloutResponse(JSONObject base, JSONObject addition) {
    try {
      mergeJsonSection(base, addition, KEY_UPDATES);
      mergeJsonSection(base, addition, KEY_COMBOS);
    } catch (Exception e) {
      log.debug("[NEO-CALLOUT] Failed to merge cascade results: {}", e.getMessage());
    }
  }

  private static void mergeJsonSection(JSONObject base, JSONObject addition, String sectionKey)
      throws org.codehaus.jettison.json.JSONException {
    JSONObject addSection = addition.optJSONObject(sectionKey);
    if (addSection == null) {
      return;
    }
    JSONObject baseSection = base.optJSONObject(sectionKey);
    if (baseSection == null) {
      base.put(sectionKey, addSection);
      return;
    }
    @SuppressWarnings("unchecked")
    Iterator<String> keys = addSection.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!baseSection.has(key)) {
        baseSection.put(key, addSection.get(key));
      }
    }
  }

  static String readRequestBody(HttpServletRequest request) throws IOException {
    return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static JSONObject parseOptionalJsonObject(String bodyStr) throws Exception {
    if (StringUtils.isBlank(bodyStr)) {
      return null;
    }
    return parseJsonObject(bodyStr);
  }

  private static JSONObject parseJsonObjectOrEmpty(String bodyStr) throws Exception {
    JSONObject parsed = parseOptionalJsonObject(bodyStr);
    return parsed != null ? parsed : new JSONObject();
  }

  private static JSONObject parseJsonObject(String bodyStr) throws Exception {
    return new JSONObject(bodyStr);
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
