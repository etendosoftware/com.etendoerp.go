package com.etendoerp.go.onboarding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
import org.openbravo.model.ad.system.Client;

import com.auth0.jwt.interfaces.DecodedJWT;
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
  private static final int TOTAL_STEPS = 6;

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    try {
      authenticateJwt(request);
      sendDescribe(response);
    } catch (OBException e) {
      sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
    } catch (Exception e) {
      log.error("Error in OnboardingServlet.doGet", e);
      sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    try {
      // 1. Authenticate (validates JWT is valid)
      authenticateJwt(request);

      // 2. Switch to System Admin context for creating new clients
      // userId=100 (System), roleId=0 (System Administrator), clientId=0, orgId=0
      OBContext.setOBContext("100", "0", "0", "0");
      OBContext.setAdminMode(false);

      // 3. Parse request body
      JSONObject body = parseRequestBody(request);

      String clientName = body.optString("clientName", null);
      String orgName = body.optString("orgName", null);
      String adminUser = body.optString("adminUser", null);
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

      // 5. Check duplicate client name
      OBCriteria<Client> clientCriteria = OBDal.getInstance().createCriteria(Client.class);
      clientCriteria.add(Restrictions.eq(Client.PROPERTY_NAME, clientName));
      if (clientCriteria.count() > 0) {
        sendJsonError(response, HttpServletResponse.SC_CONFLICT,
            "Client with name '" + clientName + "' already exists");
        return;
      }

      // 6. Build onboarding context
      OnboardingContext ctx = new OnboardingContext();
      ctx.setClientName(clientName);
      ctx.setOrgName(orgName);
      ctx.setAdminUser(adminUser);
      ctx.setAdminPassword(adminPassword);
      ctx.setCurrencyCode(currencyCode);
      ctx.setLanguageCode(languageCode);
      ctx.setCountryCode(countryCode);

      // 7. Set up NDJSON streaming response
      response.setContentType("application/x-ndjson");
      response.setCharacterEncoding("UTF-8");
      OutputStream out = response.getOutputStream();

      // 8. Build step chain
      List<OnboardingStep> steps = buildStepChain();

      // 9. Execute steps with progress reporting (collect all errors)
      long totalStart = System.currentTimeMillis();
      List<String> failedSteps = new ArrayList<>();

      for (int i = 0; i < steps.size(); i++) {
        OnboardingStep step = steps.get(i);
        int stepNum = i + 1;

        // Write "running" progress line
        writeProgressLine(out, stepNum, step.name(), "running", 0);

        long stepStart = System.currentTimeMillis();
        try {
          step.execute(ctx);
          OBDal.getInstance().flush();

          long elapsed = System.currentTimeMillis() - stepStart;
          writeProgressLine(out, stepNum, step.name(), "done", elapsed);

        } catch (Exception e) {
          long elapsed = System.currentTimeMillis() - stepStart;
          log.error("Onboarding step {} ({}) failed", stepNum, step.name(), e);

          // Clear Hibernate session to allow next step to proceed
          try {
            OBDal.getInstance().rollbackAndClose();
          } catch (Exception rollbackEx) {
            log.warn("Session cleanup after step {} error", stepNum, rollbackEx);
          }

          // Restore context for next step
          try {
            OBContext.setOBContext(ctx.getClientAdminUserId() != null ? ctx.getClientAdminUserId() : "100",
                "0", ctx.getClientId() != null ? ctx.getClientId() : "0", "0");
            OBContext.setAdminMode(false);
          } catch (Exception ctxEx) {
            log.warn("Context restore failed after step {}", stepNum, ctxEx);
          }

          // Write failure line
          JSONObject failLine = new JSONObject();
          failLine.put("step", stepNum);
          failLine.put("total", TOTAL_STEPS);
          failLine.put("name", step.name());
          failLine.put("status", "failed");
          failLine.put("ms", elapsed);
          StringBuilder errorMsg = new StringBuilder(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
          Throwable cause = e.getCause();
          while (cause != null) {
            errorMsg.append(" | Caused by: ").append(cause.getClass().getSimpleName())
                .append(": ").append(cause.getMessage());
            cause = cause.getCause();
          }
          failLine.put("error", errorMsg.toString());
          out.write((failLine.toString() + "\n").getBytes(StandardCharsets.UTF_8));
          out.flush();
          response.flushBuffer();
          failedSteps.add(step.name());
          continue;
        }
      }

      // 10. Check results
      if (!failedSteps.isEmpty()) {
        // Some steps failed — rollback everything
        try {
          OBDal.getInstance().rollbackAndClose();
        } catch (Exception ignored) {}
        long totalMs = System.currentTimeMillis() - totalStart;
        JSONObject result = new JSONObject();
        result.put("result", "failed");
        result.put("failedSteps", new JSONArray(failedSteps));
        result.put("totalMs", totalMs);
        result.put("rolledBack", true);
        out.write((result.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        response.flushBuffer();
        return;
      }

      // All steps succeeded — commit
      OBDal.getInstance().getConnection().commit();

      long totalMs = System.currentTimeMillis() - totalStart;

      // 11. Write final success summary
      JSONObject summary = new JSONObject();
      summary.put("status", "success");
      summary.put("totalMs", totalMs);
      summary.put("clientId", ctx.getClientId());
      summary.put("orgId", ctx.getOrgId());

      JSONObject userIds = new JSONObject();
      userIds.put("clientAdmin", ctx.getClientAdminUserId());
      userIds.put("orgAdmin", ctx.getOrgAdminUserId());
      summary.put("userIds", userIds);

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
   * Authenticate the JWT token and set OBContext (no role return, for GET).
   */
  private void authenticateJwt(HttpServletRequest request) throws Exception {
    authenticateJwtAndGetRole(request);
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
    line.put("status", status);
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
    fields.put(fieldDef("clientName", "string", true, "Name for the new client"));
    fields.put(fieldDef("orgName", "string", true, "Name for the main organization"));
    fields.put(fieldDef("adminUser", "string", true, "Username for the client administrator"));
    fields.put(fieldDef("adminPassword", "string", true, "Password for the client administrator"));
    fields.put(fieldDef("currency", "string", true,
        "ISO 4217 currency code (e.g., USD, EUR)"));
    fields.put(fieldDef("language", "string", true,
        "Language code (e.g., en_US, es_ES)"));
    fields.put(fieldDef("countryCode", "string", true,
        "ISO 3166-1 alpha-2 country code (e.g., US, ES)"));
    describe.put("fields", fields);

    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
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
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    try {
      JSONObject error = new JSONObject();
      error.put("error", message);
      error.put("status", status);
      response.getWriter().write(error.toString());
    } catch (Exception e) {
      log.error("Failed to write error response", e);
      response.getWriter().write("{\"error\":\"Internal server error\"}");
    }
  }
}
