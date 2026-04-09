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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.onboarding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.CorsUtils;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

import com.etendoerp.go.onboarding.steps.CreateClientStep;
import com.etendoerp.go.onboarding.steps.CreateOrgStep;
import com.etendoerp.go.onboarding.steps.CreateRoleStep;
import com.etendoerp.go.onboarding.steps.SeedReferenceDataStep;
import com.etendoerp.go.onboarding.steps.CreateDocTypesStep;
import com.etendoerp.go.onboarding.steps.MarkOrgReadyStep;

/**
 * Onboarding servlet that orchestrates the creation of a new client environment.
 *
 * Mapped to /sws/neo/onboarding via AD_SERVLET registration.
 * Uses JWT authentication via SecureWebServices.
 * Requires System Administrator role (ID "0").
 *
 * GET  /sws/neo/onboarding  -> JSON describe (input schema)
 * POST /sws/neo/onboarding  -> Runs 6 steps with chunked NDJSON progress
 */
public class OnboardingServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(OnboardingServlet.class);

  private static final String SYSTEM_ADMIN_ROLE_ID = "0";
  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String SYSTEM_ORG_ID = "0";
  private static final String SYSTEM_USER_ID = "100";
  private static final String FB_DEMO_CLIENT_ID = "23C59575B9CF467C9620760EB255B389";
  private static final String QA_TESTING_CLIENT_ID = "4028E6C72959682B01295A070852010D";
  private static final String CONTENT_TYPE_JSON = "application/json";
  private static final String CONTENT_TYPE_NDJSON = "application/x-ndjson";
  private static final String CHARSET_UTF8 = "UTF-8";
  private static final String FIELD_STATUS = "status";
  private static final String STATUS_SUCCESS = "success";
  private static final String FIELD_CLIENT_NAME = "clientName";
  private static final String FIELD_ORG_NAME = "orgName";
  private static final String FIELD_ADMIN_USER = "adminUser";
  private static final String FIELD_TYPE_STRING = "string";
  private static final int TOTAL_STEPS = 6;

  private void setCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
    CorsUtils.apply(request, response, "GET, POST, OPTIONS", "Content-Type, Authorization",
        null, true);
  }

  @Override
  protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
    setCorsHeaders(request, response);
    response.setStatus(HttpServletResponse.SC_OK);
  }

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    setCorsHeaders(request, response);
    try {
      String currentRoleId = authenticateJwtAndGetRole(request);
      String action = request.getParameter("action");
      if ("environments".equals(action)) {
        if (!isAdminRole(currentRoleId)) {
          sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
              "Unauthorized: System Administrator role required");
          return;
        }
        sendEnvironments(response);
      } else if ("login".equals(action)) {
        if (!isAdminRole(currentRoleId)) {
          sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
              "Unauthorized: System Administrator role required");
          return;
        }
        loginAsUser(request, response);
      } else {
        sendDescribe(response);
      }
    } catch (OBException e) {
      sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
    } catch (Exception e) {
      log.error("Error in OnboardingServlet.doGet", e);
      sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void loginAsUser(HttpServletRequest request, HttpServletResponse response) throws Exception {
    String userId = request.getParameter("userId");
    if (StringUtils.isBlank(userId)) {
      sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "userId parameter required");
      return;
    }
    OBContext.setAdminMode(true);
    try {
      User loginUser = OBDal.getInstance().get(User.class, userId.trim());
      if (loginUser == null) {
        sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "User not found");
        return;
      }
      if (!Boolean.TRUE.equals(loginUser.isActive())
          || loginUser.getClient() == null
          || SYSTEM_USER_ID.equals(loginUser.getId())
          || SYSTEM_CLIENT_ID.equals(loginUser.getClient().getId())) {
        sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
            "Target user is not eligible for environment login");
        return;
      }
      String jwt = SecureWebServicesUtils.generateToken(loginUser);
      prepareJsonResponse(response, CONTENT_TYPE_JSON);
      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put("token", jwt);
      response.getWriter().write(result.toString());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void sendEnvironments(HttpServletResponse response) throws Exception {
    OBContext.setAdminMode(true);
    try {
      prepareJsonResponse(response, CONTENT_TYPE_JSON);

      Connection conn = OBDal.getInstance().getConnection();
      JSONArray environments = new JSONArray();
      String sql = "SELECT DISTINCT ON (c.ad_client_id) c.ad_client_id, c.name, c.created, "
          + "o.ad_org_id, o.name AS org_name, "
          + "u.username, u.ad_user_id "
          + "FROM ad_client c "
          + "LEFT JOIN ad_org o ON o.ad_client_id = c.ad_client_id AND o.ad_org_id != '0' "
          + "LEFT JOIN ad_user u ON u.ad_client_id = c.ad_client_id "
          + "AND u.ad_user_id != ? AND u.username NOT LIKE '%.org' "
          + "WHERE c.ad_client_id NOT IN (?, ?, ?) "
          + "ORDER BY c.ad_client_id, c.created DESC";

      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, SYSTEM_USER_ID);
        ps.setString(2, SYSTEM_CLIENT_ID);
        ps.setString(3, FB_DEMO_CLIENT_ID);
        ps.setString(4, QA_TESTING_CLIENT_ID);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            JSONObject env = new JSONObject();
            env.put("clientId", rs.getString("ad_client_id"));
            env.put(FIELD_CLIENT_NAME, rs.getString("name"));
            env.put("created", rs.getString("created"));
            env.put("orgId", rs.getString("ad_org_id"));
            env.put(FIELD_ORG_NAME, rs.getString("org_name"));
            env.put(FIELD_ADMIN_USER, rs.getString("username"));
            env.put("adminUserId", rs.getString("ad_user_id"));
            environments.put(env);
          }
        }
      }

      JSONObject result = new JSONObject();
      result.put("environments", environments);
      response.getWriter().write(result.toString());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    setCorsHeaders(request, response);
    try {
      // 1. Authenticate and validate caller privileges before switching context
      String roleId = authenticateJwtAndGetRole(request);
      if (!isAdminRole(roleId)) {
        sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
            "Unauthorized: System Administrator role required");
        return;
      }

      // 2. Switch to System Admin context for creating new clients
      // userId=100 (System), roleId=0 (System Administrator), clientId=0, orgId=0
      OBContext.setOBContext(SYSTEM_USER_ID, SYSTEM_ADMIN_ROLE_ID, SYSTEM_CLIENT_ID,
          SYSTEM_ORG_ID);
      OBContext.setAdminMode(false);

      // 3. Parse request body
      JSONObject body = parseRequestBody(request);

      String clientName = body.optString(FIELD_CLIENT_NAME, null);
      String orgName = body.optString(FIELD_ORG_NAME, null);
      String adminUser = body.optString(FIELD_ADMIN_USER, null);
      String adminPassword = body.optString("adminPassword", null);
      String currencyCode = body.optString("currency", null);
      String languageCode = body.optString("language", null);
      String countryCode = body.optString("countryCode", null);

      // 4. Validate required fields
      if (StringUtils.isAnyBlank(clientName, orgName, adminUser, adminPassword,
          currencyCode, languageCode, countryCode)) {
        sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
            "Missing required fields: clientName, orgName, adminUser, adminPassword, "
                + "currency, language, countryCode");
        return;
      }

      // 5. Validate uniqueness before starting
      OBCriteria<Client> clientCriteria = OBDal.getInstance().createCriteria(Client.class);
      clientCriteria.add(Restrictions.eq(Client.PROPERTY_NAME, clientName));
      if (clientCriteria.count() > 0) {
        sendJsonError(response, HttpServletResponse.SC_CONFLICT,
            "Ya existe una empresa con el nombre '" + clientName + "'");
        return;
      }

      // Check duplicate admin email (username is global in Etendo)
      OBCriteria<User> userCriteria = OBDal.getInstance().createCriteria(User.class);
      userCriteria.add(Restrictions.eq(User.PROPERTY_USERNAME, adminUser));
      if (userCriteria.count() > 0) {
        sendJsonError(response, HttpServletResponse.SC_CONFLICT,
            "El email '" + adminUser + "' ya esta en uso por otro usuario");
        return;
      }

      // 6. Build onboarding context
        OnboardingContext ctx = buildOnboardingContext(clientName, orgName, adminUser,
          adminPassword, currencyCode, languageCode, countryCode);

      // 7. Set up NDJSON streaming response
        prepareJsonResponse(response, CONTENT_TYPE_NDJSON);
      OutputStream out = response.getOutputStream();

      // 8. Build step chain
      List<OnboardingStep> steps = buildStepChain();

      // 9. Execute steps with progress reporting (fail-fast on error)
      long totalStart = System.currentTimeMillis();

      for (int i = 0; i < steps.size(); i++) {
        OnboardingStep step = steps.get(i);
        int stepNum = i + 1;

        if (!executeStep(out, response, ctx, step, stepNum, totalStart)) {
          return;
        }
      }

      // All steps succeeded — commit
      OBDal.getInstance().getConnection().commit();

      long totalMs = System.currentTimeMillis() - totalStart;

      // 11. Write final success summary
      JSONObject summary = new JSONObject();
      summary.put(FIELD_STATUS, STATUS_SUCCESS);
      summary.put("totalMs", totalMs);
      summary.put("clientId", ctx.getClientId());
      summary.put("orgId", ctx.getOrgId());

      JSONObject userIds = new JSONObject();
      userIds.put("clientAdmin", ctx.getClientAdminUserId());
      userIds.put("orgAdmin", ctx.getOrgAdminUserId());
      summary.put("userIds", userIds);

      addAdminToken(summary, ctx.getClientAdminUserId());

      out.write((summary.toString() + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
      response.flushBuffer();

    } catch (OBException e) {
      sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
    } catch (Exception e) {
      log.error("Error in OnboardingServlet.doPost", e);
      sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Authenticate the JWT token and set OBContext. Returns the role ID from the token.
   */
  private String authenticateJwtAndGetRole(HttpServletRequest request) throws Exception {
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

    OBContext context = SecureWebServicesUtils.createContext(userId, roleId, orgId, warehouseId,
        clientId);
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);

    return roleId;
  }

  /**
   * Check if the role is an admin role (System Administrator or system-level userLevel).
   */
  private boolean isAdminRole(String roleId) {
    if (SYSTEM_ADMIN_ROLE_ID.equals(roleId)) {
      return true;
    }
    try {
      Role role = OBDal.getInstance().get(Role.class, roleId);
      if (role != null) {
        String userLevel = role.getUserLevel();
        // "S" = System level, " S" = System (with space prefix)
        return userLevel != null && userLevel.trim().contains("S");
      }
    } catch (Exception e) {
      log.warn("Could not validate role {}: {}", roleId, e.getMessage());
    }
    return false;
  }

  /**
   * Build the ordered list of onboarding steps.
   */
  private List<OnboardingStep> buildStepChain() {
    List<OnboardingStep> steps = new ArrayList<>();
    steps.add(new CreateClientStep());
    steps.add(new CreateOrgStep());
    steps.add(new CreateRoleStep());
    steps.add(new SeedReferenceDataStep());
    steps.add(new CreateDocTypesStep());
    steps.add(new MarkOrgReadyStep());
    return steps;
  }

  /**
   * Write a single NDJSON progress line and flush.
   */
  private void writeProgressLine(OutputStream out, int stepNum, String name, String status,
      long ms) throws Exception {
    JSONObject line = new JSONObject();
    line.put("step", stepNum);
    line.put("total", TOTAL_STEPS);
    line.put("name", name);
    line.put(FIELD_STATUS, status);
    if (ms > 0) {
      line.put("ms", ms);
    }
    out.write((line.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /**
   * Parse the request body as a JSONObject.
   */
  private JSONObject parseRequestBody(HttpServletRequest request) throws Exception {
    StringBuilder sb = new StringBuilder();
    BufferedReader reader = request.getReader();
    String line;
    while ((line = reader.readLine()) != null) {
      sb.append(line);
    }
    return new JSONObject(sb.toString());
  }

  /**
   * Send the describe response with the input schema for the onboarding endpoint.
   */
  private void sendDescribe(HttpServletResponse response) throws Exception {
    JSONObject describe = new JSONObject();
    describe.put("endpoint", "/sws/neo/onboarding");
    describe.put("method", "POST");
    describe.put("description", "Create a new client environment with organization, users, "
        + "roles, and reference data");

    JSONArray fields = new JSONArray();
    fields.put(fieldDef(FIELD_CLIENT_NAME, FIELD_TYPE_STRING, true, "Name for the new client"));
    fields.put(fieldDef(FIELD_ORG_NAME, FIELD_TYPE_STRING, true, "Name for the main organization"));
    fields.put(fieldDef(FIELD_ADMIN_USER, FIELD_TYPE_STRING, true, "Username for the client administrator"));
    fields.put(fieldDef("adminPassword", FIELD_TYPE_STRING, true, "Password for the client administrator"));
    fields.put(fieldDef("currency", FIELD_TYPE_STRING, true,
        "ISO 4217 currency code (e.g., USD, EUR)"));
    fields.put(fieldDef("language", FIELD_TYPE_STRING, true,
        "Language code (e.g., en_US, es_ES)"));
    fields.put(fieldDef("countryCode", FIELD_TYPE_STRING, true,
        "ISO 3166-1 alpha-2 country code (e.g., US, ES)"));
    describe.put("fields", fields);

    prepareJsonResponse(response, CONTENT_TYPE_JSON);
    response.getWriter().write(describe.toString());
  }

  /**
   * Build a field definition JSONObject for the describe response.
   */
  private JSONObject fieldDef(String name, String type, boolean required, String description)
      throws Exception {
    JSONObject field = new JSONObject();
    field.put("name", name);
    field.put("type", type);
    field.put("required", required);
    field.put("description", description);
    return field;
  }

  /**
   * Send a JSON error response.
   */
  private void sendJsonError(HttpServletResponse response, int status, String message)
      throws IOException {
    if (response.isCommitted()) {
      log.warn("Cannot send error (response already committed): {} {}", status, message);
      return;
    }
    response.setStatus(status);
    prepareJsonResponse(response, CONTENT_TYPE_JSON);
    try {
      JSONObject error = new JSONObject();
      error.put("error", message);
      error.put(FIELD_STATUS, status);
      response.getWriter().write(error.toString());
    } catch (Exception e) {
      log.error("Failed to write error response", e);
      response.getWriter().write("{\"error\":\"Internal server error\"}");
    }
  }

  private OnboardingContext buildOnboardingContext(String clientName, String orgName,
      String adminUser, String adminPassword, String currencyCode, String languageCode,
      String countryCode) {
    OnboardingContext ctx = new OnboardingContext();
    ctx.setClientName(clientName);
    ctx.setOrgName(orgName);
    ctx.setAdminUser(adminUser);
    ctx.setAdminPassword(adminPassword);
    ctx.setCurrencyCode(currencyCode);
    ctx.setLanguageCode(languageCode);
    ctx.setCountryCode(countryCode);
    return ctx;
  }

  private boolean executeStep(OutputStream out, HttpServletResponse response, OnboardingContext ctx,
      OnboardingStep step, int stepNum, long totalStart) throws Exception {
    writeProgressLine(out, stepNum, step.name(), "running", 0);

    long stepStart = System.currentTimeMillis();
    try {
      step.execute(ctx);
      OBDal.getInstance().flush();
      long elapsed = System.currentTimeMillis() - stepStart;
      writeProgressLine(out, stepNum, step.name(), "done", elapsed);
      return true;
    } catch (Exception e) {
      long elapsed = System.currentTimeMillis() - stepStart;
      log.error("Onboarding step {} ({}) failed", stepNum, step.name(), e);
      rollbackOnboarding(stepNum);
      writeFailureResult(out, step, stepNum, elapsed, totalStart, e);
      response.flushBuffer();
      return false;
    }
  }

  private void rollbackOnboarding(int stepNum) {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (Exception rollbackEx) {
      log.warn("Rollback after step {} error", stepNum, rollbackEx);
    }
  }

  private void writeFailureResult(OutputStream out, OnboardingStep step, int stepNum, long elapsed,
      long totalStart, Exception e) throws Exception {
    JSONObject failLine = new JSONObject();
    failLine.put("step", stepNum);
    failLine.put("total", TOTAL_STEPS);
    failLine.put("name", step.name());
    failLine.put(FIELD_STATUS, "failed");
    failLine.put("ms", elapsed);
    failLine.put("error", buildErrorMessage(e));
    out.write((failLine.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    out.flush();

    JSONObject result = new JSONObject();
    result.put("result", "failed");
    result.put("failedStep", step.name());
    result.put("totalMs", System.currentTimeMillis() - totalStart);
    result.put("rolledBack", true);
    out.write((result.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private String buildErrorMessage(Exception e) {
    StringBuilder errorMsg = new StringBuilder(
        e.getMessage() != null ? e.getMessage() : e.getClass().getName());
    Throwable cause = e.getCause();
    while (cause != null) {
      errorMsg.append(" | Caused by: ").append(cause.getClass().getSimpleName())
          .append(": ").append(cause.getMessage());
      cause = cause.getCause();
    }
    return errorMsg.toString();
  }

  private void addAdminToken(JSONObject summary, String clientAdminUserId) {
    try {
      User tokenUser = OBDal.getInstance().get(User.class, clientAdminUserId);
      if (tokenUser != null) {
        summary.put("token", SecureWebServicesUtils.generateToken(tokenUser));
      }
    } catch (Exception tokenEx) {
      log.warn("Could not generate token for new admin user", tokenEx);
    }
  }

  private void prepareJsonResponse(HttpServletResponse response, String contentType) {
    response.setContentType(contentType);
    response.setCharacterEncoding(CHARSET_UTF8);
  }
}
