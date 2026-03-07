package com.etendoerp.go.schemaforge;

import java.io.IOException;
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
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.etendorx.services.DataSourceServlet;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * NEO Headless 2.0 servlet.
 *
 * Mapped to /sws/neo/* via AD_SERVLET registration.
 * Uses JWT authentication via SecureWebServices (same as EtendoGo).
 *
 * URL pattern: /sws/neo/{specName}/{entityName}[/{id}]
 *
 * Resolves Spec/Entity/Field records (ETGO_SF_Spec, ETGO_SF_Entity, ETGO_SF_Field)
 * which point directly to AD_Window, AD_Tab, and AD_Column.
 * Hooks are discovered via CDI using the Java_Qualifier on ETGO_SF_Entity.
 */
public class NeoServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(NeoServlet.class);

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
    } catch (Exception e) {
      log.warn("Unauthorized NEO request: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
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

      // Find the spec
      BaseOBObject spec = findSpec(pathInfo.specName);
      if (spec == null) {
        sendError(response, HttpServletResponse.SC_NOT_FOUND,
            "Spec not found: " + pathInfo.specName);
        return;
      }

      // Handle selector requests
      if (pathInfo.isSelector) {
        if (!"GET".equals(method)) {
          sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
              "Selectors only support GET");
          return;
        }
        handleSelector(response, (String) spec.getId(), pathInfo, request);
        return;
      }

      // Find the entity within this spec
      BaseOBObject entity = findEntity((String) spec.getId(), pathInfo.entityName);
      if (entity == null) {
        sendError(response, HttpServletResponse.SC_NOT_FOUND,
            "Entity not found in spec: " + pathInfo.entityName);
        return;
      }

      // Check if the HTTP method is enabled
      if (!isMethodEnabled(entity, method)) {
        sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            method + " not enabled for " + pathInfo.entityName);
        return;
      }

      // Get AD_Tab directly from entity
      Tab adTab = getAdTab(entity);

      // Build query params map
      Map<String, String> queryParams = extractQueryParams(request);

      // Build context
      NeoContext neoContext = NeoContext.builder()
          .specName(pathInfo.specName)
          .entityName(pathInfo.entityName)
          .httpMethod(method)
          .recordId(pathInfo.recordId)
          .queryParams(queryParams)
          .adTab(adTab)
          .obContext(OBContext.getOBContext())
          .build();

      // Read request body for POST/PUT/PATCH
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
                .obContext(neoContext.getObContext())
                .build();
          }
        } catch (Exception e) {
          sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage());
          return;
        }
      }

      // 4. Check for hooks via Java_Qualifier on entity
      String javaQualifier = (String) entity.get("javaQualifier");

      NeoResponse neoResponse;
      if (StringUtils.isNotBlank(javaQualifier)) {
        neoResponse = handleWithHooks(javaQualifier, neoContext, request, response);
      } else {
        neoResponse = handleDefault(neoContext, request, response);
      }

      // 5. Write response (null means DataSourceServlet already wrote it)
      if (neoResponse != null) {
        writeResponse(response, neoResponse);
      }

    } catch (Exception e) {
      log.error("Error processing NEO request: {}", e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void authenticateJwt(HttpServletRequest request) throws Exception {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new OBException("Missing or invalid Authorization header");
    }
    String token = authHeader.substring(7);
    DecodedJWT decodedToken = SecureWebServicesUtils.decodeToken(token);

    String userId = decodedToken.getClaim("ad_user_id").asString();
    String roleId = decodedToken.getClaim("ad_role_id").asString();
    String orgId = decodedToken.getClaim("ad_org_id").asString();
    String warehouseId = decodedToken.getClaim("m_warehouse_id").asString();
    String clientId = decodedToken.getClaim("ad_client_id").asString();

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
   *   /{specName}/{entityName}[/{id}]
   *   /{specName}/{entityName}/selectors[/{columnName}]
   */
  NeoPathInfo parsePath(String pathInfo) {
    if (pathInfo == null || pathInfo.isEmpty()) {
      throw new IllegalArgumentException("Path is required: /{specName}/{entityName}[/{id}]");
    }

    String path = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    String[] parts = path.split("/");

    if (parts.length < 2) {
      throw new IllegalArgumentException(
          "Invalid path. Expected: /{specName}/{entityName}[/{id}], got: " + pathInfo);
    }

    String specName = parts[0];
    String entityName = parts[1];

    // Check for /selectors sub-path
    if (parts.length >= 3 && "selectors".equals(parts[2])) {
      String selectorField = parts.length >= 4 ? parts[3] : null;
      return new NeoPathInfo(specName, entityName, null, true, selectorField);
    }

    String recordId = parts.length >= 3 ? parts[2] : null;
    return new NeoPathInfo(specName, entityName, recordId);
  }

  /**
   * Find an active ETGO_SF_Spec by name.
   */
  @SuppressWarnings("unchecked")
  private BaseOBObject findSpec(String specName) {
    OBCriteria<BaseOBObject> criteria = OBDal.getInstance().createCriteria("ETGO_SF_Spec");
    criteria.add(Restrictions.eq("name", specName));
    criteria.add(Restrictions.eq("active", true));
    criteria.setMaxResults(1);
    List<BaseOBObject> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Find an active, included ETGO_SF_Entity by parent spec ID and entity name.
   */
  @SuppressWarnings("unchecked")
  private BaseOBObject findEntity(String specId, String entityName) {
    OBCriteria<BaseOBObject> criteria = OBDal.getInstance().createCriteria("ETGO_SF_Entity");
    criteria.add(Restrictions.eq("etgoSfSpec.id", specId));
    criteria.add(Restrictions.eq("name", entityName));
    criteria.add(Restrictions.eq("active", true));
    criteria.add(Restrictions.eq("included", true));
    criteria.setMaxResults(1);
    List<BaseOBObject> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Check if the given HTTP method is enabled on the entity.
   */
  private boolean isMethodEnabled(BaseOBObject entity, String method) {
    switch (method) {
      case "GET":
        return Boolean.TRUE.equals(entity.get("get"))
            || Boolean.TRUE.equals(entity.get("getbyid"));
      case "POST":
        return Boolean.TRUE.equals(entity.get("post"));
      case "PUT":
        return Boolean.TRUE.equals(entity.get("put"));
      case "PATCH":
        return Boolean.TRUE.equals(entity.get("patch"));
      case "DELETE":
        return Boolean.TRUE.equals(entity.get("delete"));
      default:
        return false;
    }
  }

  /**
   * Get the AD_Tab linked to the entity.
   */
  private Tab getAdTab(BaseOBObject entity) {
    try {
      Object tabRef = entity.get("aDTabID");
      if (tabRef instanceof Tab) {
        return (Tab) tabRef;
      }
      if (tabRef instanceof BaseOBObject) {
        String tabId = (String) ((BaseOBObject) tabRef).getId();
        return OBDal.getInstance().get(Tab.class, tabId);
      }
    } catch (Exception e) {
      log.warn("Could not resolve AD_Tab from entity: {}", e.getMessage());
    }
    return null;
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

      // Let the handler process the request
      NeoResponse result = handler.handle(context);
      if (result != null) {
        return result;
      }

      // If handler returns null, continue with default behavior
      return handleDefault(context, request, response);
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

  private NeoResponse handleDefault(NeoContext context, HttpServletRequest request,
      HttpServletResponse response) {
    try {
      DataSourceServlet dsServlet = new DataSourceServlet();
      String path = "/" + context.getEntityName();
      if (context.getRecordId() != null) {
        path += "/" + context.getRecordId();
      }

      switch (context.getHttpMethod()) {
        case "GET":
          dsServlet.doGet(path, request, response);
          return null;
        case "POST":
          dsServlet.doPost(path, request, response);
          return null;
        case "PUT":
        case "PATCH":
          dsServlet.doPut(path, request, response);
          return null;
        case "DELETE":
          dsServlet.doDelete(path, request, response);
          return null;
        default:
          return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
              "Unsupported method: " + context.getHttpMethod());
      }
    } catch (Exception e) {
      log.error("Error in default handler for {} {}", context.getHttpMethod(), context.getEntityName(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
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

  private void handleSelector(HttpServletResponse response, String specId,
      NeoPathInfo pathInfo, HttpServletRequest request) throws IOException {
    NeoResponse selectorResponse;
    if (pathInfo.selectorField == null) {
      // List all available selectors
      selectorResponse = NeoSelectorService.listSelectors(specId, pathInfo.entityName);
    } else {
      // Query a specific selector
      String search = request.getParameter("q");
      int limit = parseIntParam(request, "limit", 20);
      int offset = parseIntParam(request, "offset", 0);
      selectorResponse = NeoSelectorService.querySelector(
          specId, pathInfo.entityName, pathInfo.selectorField,
          search, limit, offset);
    }
    writeResponse(response, selectorResponse);
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

    NeoPathInfo(String specName, String entityName, String recordId) {
      this(specName, entityName, recordId, false, null);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField) {
      this.specName = specName;
      this.entityName = entityName;
      this.recordId = recordId;
      this.isSelector = isSelector;
      this.selectorField = selectorField;
    }
  }
}
