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

package com.etendoerp.go.rest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.InitialClientSetup;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.common.CorsUtils;
import com.etendoerp.go.onboarding.OnboardingDatasetImportService;
import com.etendoerp.go.schemaforge.data.Account;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * EtendoGo JWT Servlet — account management for platform users.
 *
 * Mapped to /sws/go/* via AD_MODEL_OBJECT_MAPPING (ID: FB313FD86E7846F8992F4C61B7230066).
 *
 * Endpoints:
 *   POST /sws/go/register     — Create a new account (public, no auth)
 *   POST /sws/go/login        — Authenticate and get session token (public, no auth)
 *   POST /sws/go/onboarding   — Create a new environment (requires session token, streams NDJSON)
 *   GET  /sws/go/me           — Get current account info (requires session token)
 *   GET  /sws/go/environments — List environments for the account (requires session token)
 *   GET  /sws/go/login?userId=X — Get an Etendo JWT for an AD_User (requires session token + ownership)
 *
 * Auth model: session token in Authorization header ("Bearer <token>").
 * This is independent of Etendo's JWT auth — it uses ETGO_ACCOUNT.SESSION_TOKEN.
 *
 * Database access uses OBDal/OBQuery, including the generated DAL entity for ETGO_Account.
 */
public class EtendoGoJwtServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(EtendoGoJwtServlet.class);

  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int SALT_BYTES = 16;
  private static final String UTF_8 = "UTF-8";
  private static final String FIELD_EMAIL = "email";
  private static final String FIELD_CLIENT_NAME = "clientName";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_TOKEN = "token";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_SUCCESS = "success";
  private static final String STATUS_SUCCESS = FIELD_SUCCESS;
  private static final String INVALID_JSON_BODY = "Invalid JSON body";
  private static final String INTERNAL_ERROR = "Internal error";
  private static final String SERVER_ERROR = "Server error";
  private static final String INVALID_AUTHORIZATION_HEADER =
      "Missing or invalid Authorization header";
  private static final String INVALID_OR_EXPIRED_TOKEN = "Invalid or expired token";
  private static final String DB_CLIENT_ID = "ad_client_id";
  private static final String DB_ORG_ID = "ad_org_id";
  private static final String PROGRESS_IN_PROGRESS = "in_progress";
  private static final String PROGRESS_CLIENT = "client";
  private static final String PROGRESS_ERROR = "error";
  private static final String PROGRESS_ORGANIZATION = "organization";
  private static final String PROGRESS_DATASET = "dataset";

  // --- CORS ---

  private void setCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
    CorsUtils.apply(request, response, "GET, POST, OPTIONS",
        "Content-Type, Authorization, Accept", null, false);
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response)
      throws javax.servlet.ServletException, IOException {
    setCorsHeaders(request, response);
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
      return;
    }
    super.service(request, response);
  }

  // --- HTTP method dispatchers ---

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if ("/me".equals(path) || "/me/".equals(path)) {
      handleMe(request, response);
    } else if ("/environments".equals(path) || "/environments/".equals(path)) {
      handleEnvironments(request, response);
    } else if ("/login".equals(path) || "/login/".equals(path)) {
      handleEnvironmentLogin(request, response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + path);
    }
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if ("/register".equals(path) || "/register/".equals(path)) {
      handleRegister(request, response);
    } else if ("/login".equals(path) || "/login/".equals(path)) {
      handleLogin(request, response);
    } else if ("/onboarding".equals(path) || "/onboarding/".equals(path)) {
      handleOnboarding(request, response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + path);
    }
  }

  // --- Endpoint handlers ---

  /**
   * POST /sws/go/register
   * Body: { "email": "...", "password": "...", "name": "..." }
   * Returns 201 with session token on success, 400 if email is taken.
   */
  private void handleRegister(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_JSON_BODY);
      return;
    }

    String email;
    String password;
    String name;
    try {
      email = body.getString(FIELD_EMAIL).trim().toLowerCase();
      password = body.getString("password");
      name = body.getString("name").trim();
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required fields: email, password, name");
      return;
    }

    if (email.isEmpty() || password.isEmpty() || name.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Fields email, password, and name must not be empty");
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      if (findActiveAccountByEmail(email) != null) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Email already registered");
        return;
      }

      String passwordHash = hashPassword(password);
      String sessionToken = generateToken();
      Account account = createAccount(email, passwordHash, name, sessionToken);

      JSONObject accountJson = new JSONObject();
      accountJson.put("id", account.getId());
      accountJson.put(FIELD_EMAIL, account.getEmail());
      accountJson.put("name", account.getName());

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_TOKEN, sessionToken);
      result.put("account", accountJson);

      writeResponse(response, HttpServletResponse.SC_CREATED, result);
    } catch (SQLException | RuntimeException e) {
      log.error("Database error during account registration", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Registration failed due to a server error");
    } catch (JSONException e) {
      log.error("JSON error building register response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * POST /sws/go/login
   * Body: { "email": "...", "password": "..." }
   * Returns 200 with new session token on success, 401 on invalid credentials.
   */
  private void handleLogin(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_JSON_BODY);
      return;
    }

    String email;
    String password;
    try {
      email = body.getString(FIELD_EMAIL).trim().toLowerCase();
      password = body.getString("password");
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required fields: email, password");
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      Account account = findActiveAccountByEmail(email);
      if (account == null || !verifyPassword(password, account.getPasswordHash())) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid credentials");
        return;
      }

      String sessionToken = generateToken();
      account.setSessionToken(sessionToken);
      OBDal.getInstance().save(account);
      flushAndCommitDalChanges();

      JSONObject accountJson = new JSONObject();
      accountJson.put("id", account.getId());
      accountJson.put(FIELD_EMAIL, account.getEmail());
      accountJson.put("name", account.getName());

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_TOKEN, sessionToken);
      result.put("account", accountJson);

      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (SQLException | RuntimeException e) {
      log.error("Database error during login", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Login failed due to a server error");
    } catch (JSONException e) {
      log.error("JSON error building login response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * GET /sws/go/me
   * Header: Authorization: Bearer <session_token>
   * Returns 200 with account info, 401 if token is invalid.
   */
  private void handleMe(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String token = extractBearerToken(request);
    if (token == null) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
          INVALID_AUTHORIZATION_HEADER);
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      Account account = findActiveAccountByToken(token);
      if (account == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }

      JSONObject result = new JSONObject();
      result.put("id", account.getId());
      result.put(FIELD_EMAIL, account.getEmail());
      result.put("name", account.getName());
      if (account.getCreationDate() != null) {
        result.put("created", account.getCreationDate().toInstant().toString());
      }

      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      log.error("Database error fetching account by token", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error building /me response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * GET /sws/go/environments
   * Header: Authorization: Bearer <session_token>
   * Returns 200 with environments linked to the account.
   * Links via AD_User.username matching the account email.
   */
  private void handleEnvironments(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String token = extractBearerToken(request);
    if (token == null) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
          INVALID_AUTHORIZATION_HEADER);
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      Account account = findActiveAccountByToken(token);
      if (account == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }

      org.codehaus.jettison.json.JSONArray envArray = new org.codehaus.jettison.json.JSONArray();
      List<User> environmentUsers = findEnvironmentUsersByAccountEmail(account.getEmail());
      for (User environmentUser : environmentUsers) {
        Client client = environmentUser.getClient();
        List<Organization> organizations = findNonStarOrganizations(client.getId());
        if (organizations.isEmpty()) {
          envArray.put(buildEnvironmentJson(client, null, environmentUser));
          continue;
        }
        for (Organization organization : organizations) {
          envArray.put(buildEnvironmentJson(client, organization, environmentUser));
        }
      }

      JSONObject result = new JSONObject();
      result.put("environments", envArray);
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      log.error("Database error in /environments", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error building /environments response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * GET /sws/go/login?userId={adUserId}
   * Header: Authorization: Bearer <session_token>
   * Returns an Etendo JWT for the given AD_User, if it belongs to the account.
   */
  private void handleEnvironmentLogin(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String token = extractBearerToken(request);
    if (token == null) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
          INVALID_AUTHORIZATION_HEADER);
      return;
    }

    String userId = request.getParameter("userId");
    if (userId == null || userId.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing userId parameter");
      return;
    }

    Connection conn = OBDal.getInstance().getConnection();
    try {
      String accountEmail = EtendoGoJwtSupport.requireAccountEmail(conn, token);
      if (accountEmail == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }

      if (!EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(conn, accountEmail, userId)) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN,
            "User does not belong to this account");
        return;
      }

        EtendoGoJwtSupport.RoleListData roleListData =
          EtendoGoJwtSupport.loadRoleListData(conn, userId);
      writeEnvironmentLoginResponse(response, userId, roleListData);

    } catch (SQLException e) {
      log.error("Database error in /login", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error in /login", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } catch (Exception e) {
      log.error("Token generation error in /login", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Token generation failed");
    }
  }

  private Account findActiveAccountByEmail(String email) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where lower(account.email) = :email and account.active = true");
    query.setNamedParameter("email", email.toLowerCase());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  private Account findActiveAccountByToken(String token) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where account.sessionToken = :token and account.active = true");
    query.setNamedParameter("token", token);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  private Account createAccount(String email, String passwordHash, String name, String sessionToken)
      throws SQLException {
    Account account = OBProvider.getInstance().get(Account.class);
    account.setClient(OBDal.getInstance().get(Client.class, "0"));
    account.setOrganization(OBDal.getInstance().get(Organization.class, "0"));
    account.setEmail(email);
    account.setPasswordHash(passwordHash);
    account.setName(name);
    account.setSessionToken(sessionToken);
    OBDal.getInstance().save(account);
    flushAndCommitDalChanges();
    return account;
  }

  private void flushAndCommitDalChanges() throws SQLException {
    OBDal.getInstance().flush();
    OBDal.getInstance().getConnection().commit();
  }

  private List<User> findEnvironmentUsersByAccountEmail(String accountEmail) {
    OBQuery<User> query = OBDal.getInstance().createQuery(User.class,
        "as user where (user.username = :accountEmail or user.username like :accountPrefix) "
            + "and user.active = true and user.client.active = true and user.client.id <> '0' "
            + "order by user.client.creationDate, user.creationDate");
    query.setNamedParameter("accountEmail", accountEmail);
    query.setNamedParameter("accountPrefix", accountEmail + "+%");
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  private List<Organization> findNonStarOrganizations(String clientId) {
    OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        "as organization where organization.client.id = :clientId "
            + "and organization.searchKey <> :starValue order by organization.creationDate");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("starValue", "*");
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  private JSONObject buildEnvironmentJson(Client client, Organization organization, User environmentUser)
      throws JSONException {
    JSONObject env = new JSONObject();
    env.put("clientId", client.getId());
    env.put(FIELD_CLIENT_NAME, client.getName());
    env.put("orgId", organization != null ? organization.getId() : JSONObject.NULL);
    env.put("orgName", organization != null ? organization.getName() : JSONObject.NULL);
    env.put("adminUserId", environmentUser.getId());
    env.put("adminUser", environmentUser.getUsername());
    return env;
  }

  private Currency findCurrencyByIsoCode(String currencyIso) {
    OBQuery<Currency> query = OBDal.getInstance().createQuery(Currency.class,
        "as currency where upper(currency.iSOCode) = :currencyIso");
    query.setNamedParameter("currencyIso", currencyIso.toUpperCase());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  private UserRoles findClientAdminUserRole(String clientId) {
    OBQuery<UserRoles> query = OBDal.getInstance().createQuery(UserRoles.class,
        "as userRole where userRole.role.client.id = :clientId "
            + "and userRole.userContact.id <> '100' order by userRole.role.creationDate");
    query.setNamedParameter("clientId", clientId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    List<UserRoles> userRoles = query.list();
    return userRoles.isEmpty() ? null : userRoles.get(0);
  }

  private Organization findFirstOrganization(String clientId) {
    OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        "as organization where organization.client.id = :clientId "
            + "and organization.searchKey <> :starValue order by organization.creationDate");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("starValue", "*");
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    List<Organization> organizations = query.list();
    return organizations.isEmpty() ? null : organizations.get(0);
  }

  // --- Onboarding ---
  /**
   * POST /sws/go/onboarding
   * Header: Authorization: Bearer <session_token>
   * Body: { "clientName": "...", "currency": "EUR", "language": "es_ES", "countryCode": "ES" }
   *
   * Creates a new Etendo environment (AD_Client + AD_Org) using the existing
   * InitialClientSetup and InitialOrgSetup business utilities.
   *
   * Streams NDJSON progress lines to the frontend.
   */
  private void handleOnboarding(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String token = extractBearerToken(request);
    if (token == null) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
          INVALID_AUTHORIZATION_HEADER);
      return;
    }

    Connection conn = OBDal.getInstance().getConnection();
    final String accountEmail;
    try {
      accountEmail = EtendoGoJwtSupport.requireAccountEmail(conn, token);
      if (accountEmail == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }
    } catch (SQLException e) {
      log.error("Database error validating token for onboarding", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
      return;
    }

    OnboardingRequestData onboardingRequest = parseOnboardingRequest(request, response);
    if (onboardingRequest == null) {
      return;
    }

    String currencyId = resolveCurrencyId(onboardingRequest.currencyIso, response);
    if (currencyId == null) {
      return;
    }

    // Set up NDJSON streaming
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/x-ndjson");
    response.setCharacterEncoding(UTF_8);
    response.setHeader("X-Content-Type-Options", "nosniff");
    PrintWriter writer = response.getWriter();

    // Generate a random password for the admin user
    String adminPassword = UUID.randomUUID().toString().substring(0, 12);

    try {
      VariablesSecureApp vars = prepareAdminContext(writer, onboardingRequest.language);
      String clientId = resolveOrCreateClient(conn, writer, vars, accountEmail, onboardingRequest,
          currencyId, adminPassword);
      if (clientId == null) {
        return;
      }

      AdminContextData adminContext = resolveAdminContextData(clientId, writer);
      if (adminContext == null) {
        return;
      }

      if (!ensureOrganization(conn, writer, onboardingRequest.clientName, clientId, adminContext,
          currencyId)) {
        return;
      }

      String orgId = resolveOrganizationId(clientId);
      if (orgId == null) {
        sendProgress(writer, PROGRESS_DATASET, PROGRESS_ERROR,
            "Could not resolve organization for onboarding dataset import");
        sendFinalResult(writer, false, "Organization not found after onboarding");
        return;
      }

      if (!importOnboardingDataset(writer, clientId, orgId)) {
        return;
      }

      sendProgress(writer, "finalize", PROGRESS_IN_PROGRESS, "Finalizing setup...");
      sendProgress(writer, "finalize", "done", "Environment ready");
      sendFinalResult(writer, true, "Environment created successfully");

    } catch (Exception e) {
      log.error("Onboarding failed", e);
      try {
        OBDal.getInstance().rollbackAndClose();
      } catch (Exception rollbackEx) {
        log.error("Rollback failed", rollbackEx);
      }
      sendProgress(writer, PROGRESS_ERROR, PROGRESS_ERROR,
          "Onboarding failed: " + e.getMessage());
      sendFinalResult(writer, false, "Onboarding failed: " + e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
      writer.flush();
    }
  }

  private void writeEnvironmentLoginResponse(HttpServletResponse response, String userId,
      EtendoGoJwtSupport.RoleListData roleListData) throws Exception {
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    try {
      User user = OBDal.getInstance().get(User.class, userId);
      if (user == null) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, "User not found");
        return;
      }
      Role role = roleListData.firstRoleId != null
          ? OBDal.getInstance().get(Role.class, roleListData.firstRoleId)
          : null;
      String jwtToken = SecureWebServicesUtils.generateToken(user, role);

      JSONObject result = new JSONObject();
      result.put(FIELD_TOKEN, jwtToken);
      result.put("roleList", roleListData.roleArray);
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private OnboardingRequestData parseOnboardingRequest(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      JSONObject body = readJsonBody(request);
      String clientName = body.getString(FIELD_CLIENT_NAME).trim();
      if (clientName.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST,
        FIELD_CLIENT_NAME + " must not be empty");
        return null;
      }
      OnboardingRequestData data = new OnboardingRequestData();
      data.clientName = clientName;
      data.currencyIso = body.optString("currency", "EUR").trim();
      data.language = body.optString("language", "en_US").trim();
      return data;
    } catch (JSONException e) {
        String message = e.getMessage() != null && e.getMessage().contains(FIELD_CLIENT_NAME)
          ? "Missing required field: " + FIELD_CLIENT_NAME
          : INVALID_JSON_BODY;
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, message);
      return null;
    }
  }

  private String resolveCurrencyId(String currencyIso, HttpServletResponse response)
      throws IOException {
    Currency currency = findCurrencyByIsoCode(currencyIso);
    if (currency != null) {
      return currency.getId();
    }
    writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Unknown currency: " + currencyIso);
    return null;
  }

  private VariablesSecureApp prepareAdminContext(PrintWriter writer, String language) {
    sendProgress(writer, "setup", PROGRESS_IN_PROGRESS, "Setting up admin context...");
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    VariablesSecureApp vars = new VariablesSecureApp("0", "0", "0", "0", language);
    sendProgress(writer, "setup", "done", "Admin context ready");
    return vars;
  }

  private String resolveOrCreateClient(Connection conn, PrintWriter writer, VariablesSecureApp vars,
      String accountEmail, OnboardingRequestData requestData, String currencyId,
      String adminPassword) throws Exception {
    sendProgress(writer, PROGRESS_CLIENT, PROGRESS_IN_PROGRESS,
        "Creating client: " + requestData.clientName + "...");
    String clientId = EtendoGoJwtSupport.findClientIdByName(conn, requestData.clientName);
    if (clientId != null) {
      return validateExistingClient(conn, writer, requestData.clientName, clientId) ? clientId : null;
    }

    String clientUser = EtendoGoJwtSupport.buildClientUsername(conn, accountEmail,
        requestData.clientName);
    if (!createClient(vars, currencyId, requestData.clientName, clientUser, adminPassword, writer)) {
      return null;
    }
    return EtendoGoJwtSupport.findClientIdByName(OBDal.getInstance().getConnection(),
        requestData.clientName);
  }

  private boolean validateExistingClient(Connection conn, PrintWriter writer, String clientName,
      String clientId) throws SQLException {
    if (!EtendoGoJwtSupport.hasStarOrganization(conn, clientId)) {
      sendProgress(writer, PROGRESS_CLIENT, PROGRESS_ERROR,
          "Client '" + clientName + "' exists but is incomplete. Use a different name.");
      sendFinalResult(writer, false,
          "A previous attempt left '" + clientName + "' in an incomplete state. Please choose a different company name.");
      return false;
    }
    sendProgress(writer, PROGRESS_CLIENT, "done", "Client already exists, resuming...");
    return true;
  }

  private boolean createClient(VariablesSecureApp vars, String currencyId, String clientName,
      String clientUser, String adminPassword, PrintWriter writer) {
    InitialClientSetup clientSetup = new InitialClientSetup();
    OBError clientResult = clientSetup.createClient(vars, currencyId, clientName, clientUser,
        adminPassword, "", "Account", "Calendar", false, null, false, false, false,
        false, false);
    if (!"Success".equals(clientResult.getType())) {
      String errorMsg = clientResult.getMessage() != null
          ? clientResult.getMessage()
          : "Client creation failed";
      sendProgress(writer, PROGRESS_CLIENT, PROGRESS_ERROR, errorMsg);
      sendFinalResult(writer, false, errorMsg);
      return false;
    }
    sendProgress(writer, PROGRESS_CLIENT, "done", "Client created successfully");
    return true;
  }

  private AdminContextData resolveAdminContextData(String clientId,
      PrintWriter writer) throws SQLException {
    AdminContextData data = new AdminContextData();
    UserRoles adminUserRole = findClientAdminUserRole(clientId);
    if (adminUserRole != null) {
      data.adminRoleId = adminUserRole.getRole().getId();
      data.adminUserId = adminUserRole.getUserContact().getId();
    }
    if (data.adminRoleId == null || data.adminUserId == null) {
      sendProgress(writer, PROGRESS_ORGANIZATION, PROGRESS_ERROR,
          "Could not find admin role for new client");
      sendFinalResult(writer, false, "Admin role not found — client may be incomplete");
      return null;
    }
    data.starOrgId = EtendoGoJwtSupport.findStarOrgId(OBDal.getInstance().getConnection(), clientId);
    OBContext.setOBContext(data.adminUserId, data.adminRoleId, clientId, data.starOrgId);
    OBContext.setAdminMode(true);
    return data;
  }

  private boolean ensureOrganization(Connection conn, PrintWriter writer, String clientName,
      String clientId, AdminContextData adminContext, String currencyId) throws SQLException {
    sendProgress(writer, PROGRESS_ORGANIZATION, PROGRESS_IN_PROGRESS,
        "Creating organization: " + clientName + "...");
    if (EtendoGoJwtSupport.organizationExists(conn, clientId)) {
      sendProgress(writer, PROGRESS_ORGANIZATION, "done",
          "Organization already exists, resuming...");
      return true;
    }
    return createOrganization(writer, clientName, clientId, adminContext.starOrgId, currencyId);
  }

  private boolean createOrganization(PrintWriter writer, String clientName, String clientId,
      String starOrgId, String currencyId) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    if (client == null) {
      sendProgress(writer, PROGRESS_ORGANIZATION, PROGRESS_ERROR,
          "Could not load client entity");
      sendFinalResult(writer, false, "Client entity not found in DAL");
      return false;
    }
    InitialOrgSetup orgSetup = new InitialOrgSetup(client);
    OBError orgResult = orgSetup.createOrganization(clientName, "", "0", starOrgId, null,
        "", "", false, null, currencyId, false, false, false, false, false);
    if (!"Success".equals(orgResult.getType())) {
      String errorMsg = orgResult.getMessage() != null
          ? orgResult.getMessage()
          : "Organization creation failed";
      sendProgress(writer, PROGRESS_ORGANIZATION, PROGRESS_ERROR, errorMsg);
      sendFinalResult(writer, false, errorMsg);
      return false;
    }
    sendProgress(writer, PROGRESS_ORGANIZATION, "done", "Organization created successfully");
    return true;
  }

  private String resolveOrganizationId(String clientId) {
    Organization organization = findFirstOrganization(clientId);
    return organization != null ? organization.getId() : null;
  }

  boolean importOnboardingDataset(PrintWriter writer, String clientId, String orgId) {
    sendProgress(writer, PROGRESS_DATASET, PROGRESS_IN_PROGRESS,
        "Importing onboarding dataset...");
    try {
      createOnboardingDatasetImportService().importDataset(clientId, orgId);
      sendProgress(writer, PROGRESS_DATASET, "done", "Onboarding dataset imported");
      return true;
    } catch (Exception e) {
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Onboarding dataset import failed";
      sendProgress(writer, PROGRESS_DATASET, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  OnboardingDatasetImportService createOnboardingDatasetImportService() {
    return new OnboardingDatasetImportService();
  }


  /**
   * Write a NDJSON progress line.
   */
  private void sendProgress(PrintWriter writer, String step, String status, String message) {
    try {
      JSONObject progress = new JSONObject();
      progress.put("type", "progress");
      progress.put("step", step);
      progress.put(FIELD_STATUS, status);
      progress.put(FIELD_MESSAGE, message);
      progress.put("timestamp", Instant.now().toString());
      writer.println(progress.toString());
      writer.flush();
    } catch (JSONException e) {
      log.warn("Error writing progress", e);
    }
  }

  /**
   * Write the final NDJSON result line.
   */
  private void sendFinalResult(PrintWriter writer, boolean success, String message) {
    try {
      JSONObject result = new JSONObject();
      result.put("type", "result");
      result.put(FIELD_SUCCESS, success);
      result.put(FIELD_MESSAGE, message);
      result.put("timestamp", Instant.now().toString());
      writer.println(result.toString());
      writer.flush();
    } catch (JSONException e) {
      log.warn("Error writing final result", e);
    }
  }

  // --- Password utilities ---

  /**
   * Hash a plaintext password using SHA-256 with a random salt.
   * Returns "base64(salt):base64(hash)" so the salt can be recovered for verification.
   */
  private String hashPassword(String password) {
    try {
      SecureRandom random = new SecureRandom();
      byte[] salt = new byte[SALT_BYTES];
      random.nextBytes(salt);

      MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
      md.update(salt);
      byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));

      String saltB64 = Base64.getEncoder().encodeToString(salt);
      String hashB64 = Base64.getEncoder().encodeToString(hash);
      return saltB64 + ":" + hashB64;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /**
   * Verify a plaintext password against a stored "salt:hash" string.
   * Returns true if the password matches.
   */
  private boolean verifyPassword(String password, String storedHash) {
    if (storedHash == null || !storedHash.contains(":")) {
      return false;
    }
    try {
      String[] parts = storedHash.split(":", 2);
      byte[] salt = Base64.getDecoder().decode(parts[0]);
      byte[] expectedHash = Base64.getDecoder().decode(parts[1]);

      MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
      md.update(salt);
      byte[] actualHash = md.digest(password.getBytes(StandardCharsets.UTF_8));

      // Constant-time comparison to prevent timing attacks
      if (actualHash.length != expectedHash.length) {
        return false;
      }
      int diff = 0;
      for (int i = 0; i < actualHash.length; i++) {
        diff |= actualHash[i] ^ expectedHash[i];
      }
      return diff == 0;
    } catch (NoSuchAlgorithmException | IllegalArgumentException e) {
      log.warn("Password verification failed: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Generate a random URL-safe session token (UUID without hyphens, 32 hex chars).
   */
  private String generateToken() {
    return UUID.randomUUID().toString().replace("-", "").toLowerCase();
  }

  // --- HTTP utilities ---

  /**
   * Extract the Bearer token from the Authorization header.
   * Returns null if the header is absent or malformed.
   */
  private String extractBearerToken(HttpServletRequest request) {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      return null;
    }
    String token = authHeader.substring(7).trim();
    return token.isEmpty() ? null : token;
  }

  /**
   * Read and parse the request body as a JSONObject.
   */
  private JSONObject readJsonBody(HttpServletRequest request)
      throws IOException, JSONException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
    }
    return new JSONObject(sb.toString());
  }

  /**
   * Write a JSON response with the given HTTP status code.
   */
  private void writeResponse(HttpServletResponse response, int status, JSONObject body)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding(UTF_8);
    try (PrintWriter writer = response.getWriter()) {
      writer.write(body.toString());
    }
  }

  /**
   * Write a JSON error response: { "error": { "message": "...", "status": N } }
   */
  private void writeError(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding(UTF_8);
    try (PrintWriter writer = response.getWriter()) {
      try {
        JSONObject error = new JSONObject();
        error.put(FIELD_MESSAGE, message);
        error.put(FIELD_STATUS, status);

        JSONObject wrapper = new JSONObject();
        wrapper.put(PROGRESS_ERROR, error);

        writer.write(wrapper.toString());
      } catch (JSONException e) {
        // Fallback to plain text if JSON construction fails
        writer.write("{\"error\":{\"message\":\"" + message + "\",\"status\":" + status + "}}");
      }
    }
  }

  private static class OnboardingRequestData {
    private String clientName;
    private String currencyIso;
    private String language;
  }

  private static class AdminContextData {
    private String adminRoleId;
    private String adminUserId;
    private String starOrgId;
  }
}
