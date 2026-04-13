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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

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
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
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
import com.etendoerp.go.schemaforge.util.NeoCrudHelper;
import com.etendoerp.go.schemaforge.util.NeoDiscoveryHelper;
import com.etendoerp.go.schemaforge.util.NeoDisplayLogicHelper;
import com.etendoerp.go.schemaforge.util.NeoImageHelper;
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
  private static final String PATCH_METHOD = "PATCH";
  private static final String DELETE_METHOD = "DELETE";
  private static final String PARENT_ID_KEY = "parentId";
  // Etendo record IDs: 32-char hex, UUID with hyphens, or legacy numeric strings
  private static final java.util.regex.Pattern VALID_ID_PATTERN =
      java.util.regex.Pattern.compile("[A-Za-z0-9\\-]+");
  private static final String KEY_UPDATES = "updates";
  private static final String KEY_COMBOS = "combos";
  private static final Set<String> CONTACTS_PRECREATE_BILLING_FIELDS = new HashSet<>(
      Arrays.asList(
          "priceList",
          "paymentMethod",
          "paymentTerms",
          "account",
          "customerBlocking",
          "purchasePricelist",
          "pOPaymentMethod",
          "pOPaymentTerms",
          "pOFinancialAccount",
          "vendorBlocking"));

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
    processRequest(request, response, DELETE_METHOD);
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (PATCH_METHOD.equalsIgnoreCase(request.getMethod())) {
      processRequest(request, response, PATCH_METHOD);
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

    if ("POST".equals(method) || "PUT".equals(method) || PATCH_METHOD.equals(method)) {
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
        String correctedWarehouseId = findAccessibleWarehouse(context);
        context = SecureWebServicesUtils.createContext(userId, roleId, orgId, correctedWarehouseId, clientId);
      }
    }
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);
  }

  /**
   * Finds the first warehouse accessible to the current user: active, same client, and belonging
   * to one of the user's readable organizations. Returns {@code null} if none is found, in which
   * case the context will be created without a warehouse and warehouse defaults will be empty.
   */
  private static String findAccessibleWarehouse(OBContext ctx) {
    try {
      OBContext.setAdminMode(true);
      Set<String> readableOrgs = new java.util.HashSet<>(
          java.util.Arrays.asList(ctx.getReadableOrganizations()));
      OBCriteria<org.openbravo.model.common.enterprise.Warehouse> crit =
          OBDal.getInstance().createCriteria(org.openbravo.model.common.enterprise.Warehouse.class);
      crit.add(Restrictions.eq(
          org.openbravo.model.common.enterprise.Warehouse.PROPERTY_CLIENT,
          ctx.getCurrentClient()));
      crit.add(Restrictions.eq(
          org.openbravo.model.common.enterprise.Warehouse.PROPERTY_ACTIVE, true));
      crit.setMaxResults(50);
      for (org.openbravo.model.common.enterprise.Warehouse wh : crit.list()) {
        String whOrgId = wh.getOrganization().getId();
        if (readableOrgs.contains(whOrgId)) {
          log.debug("Resolved accessible warehouse '{}' (org='{}') for user '{}'",
              wh.getId(), whOrgId, ctx.getUser().getId());
          return wh.getId();
        }
      }
      log.warn("No accessible warehouse found for user '{}' client '{}'",
          ctx.getUser().getId(), ctx.getCurrentClient().getId());
      return null;
    } catch (Exception e) {
      log.error("Error finding accessible warehouse for user '{}': {}",
          ctx.getUser().getId(), e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
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
      case PATCH_METHOD:
        return Boolean.TRUE.equals(entity.isPatch());
      case DELETE_METHOD:
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

    return executeHookChain(handler, hookCtx, defaultAction, endpointType, entityName);
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

    return executeHookChain(handler, hookCtx, defaultAction, endpointType, entityName);
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

      Map<String, String> params = NeoCrudHelper.buildBaseParams(context, adTab, dalEntityName);

      // Build where clause: tab's own HQL + parent filter for child tabs
      StringBuilder whereClause = new StringBuilder();

      String parentId = context.getQueryParams() != null
          ? context.getQueryParams().get(PARENT_ID_KEY)
          : null;

      // Reject parentId values that contain characters outside the expected ID format
      // to prevent HQL injection via the query parameter.
      if (parentId != null && !VALID_ID_PATTERN.matcher(parentId).matches()) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Invalid parentId format: " + parentId);
      }

      String tabWhere = adTab.getHqlwhereclause();
      if (StringUtils.isNotBlank(tabWhere)) {
        // Substitute @PLACEHOLDER@ style tokens used in classic UI tab where clauses
        // (e.g. @FIN_Payment_ID@, @Locator.id@) with the actual parentId so indirect parent-child
        // relationships — where there is no direct FK to the parent table — work in NEO.
        // The pattern allows dots to handle compound references like @Locator.id@.
        if (parentId != null && tabWhere.contains("@")) {
          tabWhere = tabWhere.replaceAll("@[A-Za-z_.]+@",
              Matcher.quoteReplacement("'" + parentId.replace("'", "''") + "'"));
        }
        whereClause.append("(").append(tabWhere).append(")");
      }
      if (parentId != null && adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
        NeoTypeCoercionHelper.ParentFilter parentFilter =
            NeoTypeCoercionHelper.buildParentWhereClause(adTab, parentId);
        if (parentFilter != null) {
          if (whereClause.length() > 0) {
            whereClause.append(" and ");
          }
          // resolveForStringApi() is used here because DefaultJsonDataService only
          // accepts a flat HQL string — named parameters are not supported by that API.
          whereClause.append("(").append(parentFilter.resolveForStringApi()).append(")");
        }
      }

      // _neoWhere is an internal extension point: buildBaseParams strips it from HTTP query params
      // (HQL injection prevention), but trusted internal code (hooks, custom handlers) may add it
      // to the params map after buildBaseParams returns. Consumed here before passing to the service.
      String neoWhere = params.remove(NeoCrudHelper.NEO_WHERE_PARAM);
      if (StringUtils.isNotBlank(neoWhere)) {
        if (whereClause.length() > 0) {
          whereClause.append(" and ");
        }
        whereClause.append("(").append(neoWhere).append(")");
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
          String parentIdValue = NeoCrudHelper.resolveAndMapParentId(requestBody, adTab);
          // Filter out non-included fields (allow read-only on create — callouts/defaults set them)
          JSONObject filteredBody = fieldFilter.filterCreateRequest(requestBody);
          // Inject defaults for mandatory columns not in ETGO_SF_FIELD config
          NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, context, parentIdValue);
          // For header tabs (level 0): run callout cascade to derive field values (e.g. docType,
          // business partner defaults), then re-apply tab filter, remove empty FKs, and re-inject
          // mandatory defaults after callout mutations.
          if (adTab.getTabLevel() != null && adTab.getTabLevel() == 0) {
            // Detect sequence preview fields already in the body (values wrapped in angle
            // brackets, e.g. "<1000009>"). The callout cascade must not overwrite these —
            // the real sequence number is consumed by DocumentNoHandlerLegacy on OBDal.save().
            Set<String> seqFields = new HashSet<>();
            Iterator<String> bodyKeys = filteredBody.keys();
            while (bodyKeys.hasNext()) {
              String k = bodyKeys.next();
              Object v = filteredBody.opt(k);
              if (v instanceof String) {
                String s = (String) v;
                if (s.startsWith("<") && s.endsWith(">") && s.length() > 2) {
                  seqFields.add(k);
                }
              }
            }
            NeoDefaultsService.executeCalloutCascade(context, adTab, filteredBody, seqFields);
            NeoDefaultsService.reapplyDocTypeFromTabFilter(filteredBody, adTab, context);
            NeoDefaultsService.removeEmptyFkValues(filteredBody, adTab);
            NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, context, parentIdValue);
          }
          // For line entities: when 'product' is set but 'uOM' (C_UOM_ID) is missing,
          // derive it from M_Product.C_UOM_ID. The callout (SL_Invoice_Product,
          // SL_Order_Product) returns uOM as empty because the classic UI resolves it
          // via readOnly display logic — NEO must inject it explicitly to satisfy the
          // c_invoiceline_trg / c_orderline_trg DB trigger (product/transaction UOM check).
          NeoDefaultsService.injectProductDerivedUomIfMissing(filteredBody);
          // For tax-inclusive invoice lines: compute LINE_GROSS_AMOUNT = grossUnitPrice × qty
          // when grossAmount is 0 (stale callout value computed before user entered qty).
          // The c_invoiceline_before_trg trigger derives PRICEACTUAL from LINE_GROSS_AMOUNT
          // for tax-inclusive price lists, so it must be non-zero.
          NeoDefaultsService.injectGrossAmountIfMissing(filteredBody);
          // Contacts/BP create follows Classic flow: billing preferences are configured
          // after header creation, not persisted on the first save.
          stripContactsPreCreateBillingDefaults(filteredBody, context, adTab);
          // Wrap in SmartClient envelope for DefaultJsonDataService (data + _entityName + _new)
          String wrappedBody = NeoTypeCoercionHelper.wrapForSmartclient(filteredBody, dalEntityName, null);
          result = jsonService.add(params, wrappedBody);
          break;
        }
        case "PUT":
        case PATCH_METHOD: {
          // Validate: PUT/PATCH require a recordId
          if (context.getRecordId() == null) {
            return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
                context.getHttpMethod() + " requires a record ID in the URL");
          }
          // Filter out non-included and read-only fields from request body
          JSONObject filteredBody = fieldFilter.filterWriteRequest(context.getRequestBody());
          // Wrap in SmartClient envelope for DefaultJsonDataService (data + _entityName + id)
          String wrappedBody = NeoTypeCoercionHelper.wrapForSmartclient(filteredBody, dalEntityName, context.getRecordId());
          result = jsonService.update(params, wrappedBody);
          break;
        }
        case DELETE_METHOD:
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

  private void stripContactsPreCreateBillingDefaults(JSONObject body, NeoContext context, Tab adTab) {
    if (body == null || context == null || adTab == null) {
      return;
    }
    if (!("contacts".equalsIgnoreCase(context.getSpecName())
        && "businessPartner".equals(context.getEntityName())
        && adTab.getTabLevel() != null
        && adTab.getTabLevel() == 0)) {
      return;
    }

    for (String key : CONTACTS_PRECREATE_BILLING_FIELDS) {
      body.remove(key);
      body.remove(key + "$_identifier");
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
            NeoDefaultsService.cascadeInteractiveCallout(neoContext, tab, fieldName, formState, calloutResult.getBody());
        if (cascade.hasResults()) {
          mergeCalloutResponse(calloutResult.getBody(), cascade.toJSON());
          log.debug("[NEO-CALLOUT] Cascade merged additional fields into response");
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

      String parentId = request.getParameter(PARENT_ID_KEY);

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
