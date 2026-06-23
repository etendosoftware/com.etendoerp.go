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
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Instant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialClientSetup;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.common.EtendoGoCorsServlet;
import com.etendoerp.go.common.ProtocolErrorAdapters;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.onboarding.OnboardingBaselineService;
import com.etendoerp.go.onboarding.OnboardingAccountingWiringService;
import com.etendoerp.go.onboarding.OnboardingDatasetImportService;
import com.etendoerp.go.onboarding.OnboardingDefaultCustomerService;
import com.etendoerp.go.onboarding.OnboardingFiscalDataSetupService;
import com.etendoerp.go.onboarding.OnboardingOrgInfoService;
import com.etendoerp.go.onboarding.OnboardingMarkOrgReadyService;
import com.etendoerp.go.onboarding.OnboardingPeriodControlService;
import com.etendoerp.go.onboarding.OnboardingSequenceGeneratorService;
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
 *   POST /sws/go/sso/{provider} — Exchange a provider credential for a session token (public)
 *   POST /sws/go/password-reset/request — Request neutral password reset email (public)
 *   POST /sws/go/password-reset/confirm — Confirm password reset token (public)
 *   POST /sws/go/change-password — Change local password (requires session token)
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
@SuppressWarnings("java:S1448")
public class EtendoGoJwtServlet extends EtendoGoCorsServlet {

  private static final Logger log = LogManager.getLogger(EtendoGoJwtServlet.class);

  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int SALT_BYTES = 16;
  // Heartbeat cadence for the onboarding NDJSON stream. Must stay well below the
  // CloudFront/proxy origin-response (inter-byte) timeout — default 30s — so a slow
  // step never leaves the connection idle long enough to be dropped mid-stream.
  private static final int ONBOARDING_HEARTBEAT_SECONDS = 10;
  private static final String UTF_8 = "UTF-8";
  private static final String FIELD_EMAIL = "email";
  private static final String FIELD_CLIENT_NAME = "clientName";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_TOKEN = "token";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_PASSWORD = "password";
  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String FIELD_ACCOUNT = "account";
  private static final String FIELD_AUTH_METHOD = "authMethod";
  private static final String STATUS_SUCCESS = FIELD_SUCCESS;
  private static final String INVALID_JSON_BODY = "Invalid JSON body";
  private static final String INTERNAL_ERROR = "Internal error";
  private static final String SERVER_ERROR = "Server error";
  private static final String INVALID_AUTHORIZATION_HEADER =
      "Missing or invalid Authorization header";
  private static final String INVALID_OR_EXPIRED_TOKEN = "Invalid or expired token";
  private static final String PROGRESS_IN_PROGRESS = "in_progress";
  private static final String PROGRESS_CLIENT = "client";
  private static final String PROGRESS_ERROR = "error";
  private static final String PROGRESS_ORGANIZATION = "organization";
  private static final String PROGRESS_DATASET = "dataset";
  private static final String PROGRESS_ACCOUNTING = "accounting";
  private static final String PROGRESS_PERIOD_CONTROL = "periodControl";
  private static final String PROGRESS_SEQUENCES = "sequences";
  private static final String PROGRESS_FISCAL = "fiscal";
  private static final String PROGRESS_ORG_READY = "orgReady";
  private static final String PROGRESS_CUSTOMER = "customer";
  private static final String PROGRESS_ORG_INFO = "orgInfo";
  private static final String PROGRESS_BASELINE = "baseline";
  private static final String LEGAL_WITH_ACCOUNTING_ORG_TYPE_ID = "1";
  private static final long PASSWORD_RESET_TTL_SECONDS = 30 * 60L;
  private static final String PASSWORD_RESET_NEUTRAL_MESSAGE =
      "If an account exists for that email, password reset instructions will be sent.";
  private static final String PASSWORD_RESET_INVALID_MESSAGE =
      "Invalid or expired password reset token";
  private static final String SSO_PREFIX = "/sso/";

  OnboardingDatasetImportService onboardingDatasetImportService = new OnboardingDatasetImportService();
  OnboardingAccountingWiringService onboardingAccountingWiringService =
      new OnboardingAccountingWiringService();
  OnboardingPeriodControlService onboardingPeriodControlService =
      new OnboardingPeriodControlService();
  OnboardingSequenceGeneratorService onboardingSequenceGeneratorService =
      new OnboardingSequenceGeneratorService();
  OnboardingMarkOrgReadyService onboardingMarkOrgReadyService =
      new OnboardingMarkOrgReadyService();
  OnboardingFiscalDataSetupService onboardingFiscalDataSetupService =
      new OnboardingFiscalDataSetupService();
  OnboardingOrgInfoService onboardingOrgInfoService =
      new OnboardingOrgInfoService();
  OnboardingDefaultCustomerService onboardingDefaultCustomerService =
      new OnboardingDefaultCustomerService();
  OnboardingBaselineService onboardingBaselineService =
      new OnboardingBaselineService();
  private final TransactionalAuthEmailSender authEmailSender;
  private final EtendoGoSsoProviderRegistry ssoProviderRegistry;

  /**
   * Creates the default servlet wired to the runtime transactional auth email sender.
   */
  public EtendoGoJwtServlet() {
    this(new TransactionalAuthEmailSender(), new EtendoGoSsoProviderRegistry());
  }

  EtendoGoJwtServlet(TransactionalAuthEmailSender authEmailSender) {
    this(authEmailSender, new EtendoGoSsoProviderRegistry());
  }

  EtendoGoJwtServlet(TransactionalAuthEmailSender authEmailSender,
      EtendoGoSsoAssertionVerifier ssoAssertionVerifier) {
    this(authEmailSender, EtendoGoSsoProviderRegistry.singleProvider(
        EtendoGoSsoProviderRegistry.GOOGLE_PROVIDER, ssoAssertionVerifier));
  }

  EtendoGoJwtServlet(TransactionalAuthEmailSender authEmailSender,
      EtendoGoSsoProviderRegistry ssoProviderRegistry) {
    this.authEmailSender = authEmailSender;
    this.ssoProviderRegistry = ssoProviderRegistry;
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
    String ssoProvider = extractSsoProvider(path);
    if ("/register".equals(path) || "/register/".equals(path)) {
      handleRegister(request, response);
    } else if ("/login".equals(path) || "/login/".equals(path)) {
      handleLogin(request, response);
    } else if (ssoProvider != null) {
      handleSsoLogin(ssoProvider, request, response);
    } else if ("/password-reset/request".equals(path)
        || "/password-reset/request/".equals(path)) {
      handlePasswordResetRequest(request, response);
    } else if ("/password-reset/confirm".equals(path)
        || "/password-reset/confirm/".equals(path)) {
      handlePasswordResetConfirm(request, response);
    } else if ("/change-password".equals(path) || "/change-password/".equals(path)) {
      handleChangePassword(request, response);
    } else if ("/onboarding".equals(path) || "/onboarding/".equals(path)) {
      handleOnboarding(request, response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + path);
    }
  }

  // --- Endpoint handlers ---

  /**
   * POST /sws/go/register
   * Body: { "email": "...", "password": "...", "name": "...", "language": "es_ES" }
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
    String language;
    try {
      email = body.getString(FIELD_EMAIL).trim().toLowerCase();
      password = body.getString(FIELD_PASSWORD);
      name = body.getString("name").trim();
      language = body.optString("language", "").trim();
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

      if (EtendoGoJwtDalHelper.findActiveAccountByEmail(email) != null) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Email already registered");
        return;
      }

      String passwordHash = hashPassword(password);
      String sessionToken = generateToken();
      Account account = EtendoGoJwtDalHelper.createAccount(email, passwordHash, name, sessionToken);
      String normalizedLanguage = StringUtils.trimToNull(language);
      sendAuthEmailBestEffort("new-account",
          () -> authEmailSender.sendNewAccount(account, normalizedLanguage));

      JSONObject accountJson = new JSONObject();
      accountJson.put("id", account.getId());
      accountJson.put(FIELD_EMAIL, account.getEmail());
      accountJson.put("name", account.getName());

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_TOKEN, sessionToken);
      result.put(FIELD_ACCOUNT, accountJson);

      writeResponse(response, HttpServletResponse.SC_CREATED, result);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("account registration", e, log);
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
      password = body.getString(FIELD_PASSWORD);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required fields: email, password");
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      Account account = EtendoGoJwtDalHelper.findActiveAccountByEmail(email);
      if (account == null || !EtendoGoJwtDalHelper.hasLocalPassword(account)
          || !verifyPassword(password, account.getPasswordHash())) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid credentials");
        return;
      }

      String sessionToken = generateToken();
      EtendoGoJwtDalHelper.updateSessionToken(account, sessionToken);

      JSONObject accountJson = new JSONObject();
      accountJson.put("id", account.getId());
      accountJson.put(FIELD_EMAIL, account.getEmail());
      accountJson.put("name", account.getName());

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_TOKEN, sessionToken);
      result.put(FIELD_ACCOUNT, accountJson);

      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("login", e, log);
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
   * POST /sws/go/sso/{provider}
   * Returns 200 with a platform session token after validating the provider credential.
   */
  private void handleSsoLogin(String provider, HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    String rawBody = readRawBody(request);
    EtendoGoSsoAssertion assertion;
    try {
      assertion = ssoProviderRegistry.verify(provider, request, rawBody);
    } catch (EtendoGoSsoAssertionException e) {
      writeError(response, e.getStatusCode(), e.getMessage());
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      Account account = EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity(
          assertion.getProvider(), assertion.getSubject());
      if (account == null) {
        account = EtendoGoJwtDalHelper.findActiveAccountByEmail(assertion.getEmail());
        if (account != null) {
          if (!assertion.isEmailAuthoritative()) {
            writeError(response, HttpServletResponse.SC_CONFLICT,
                "Account requires explicit linking before SSO login");
            return;
          }
          if (!EtendoGoJwtDalHelper.linkSsoIdentityIfCompatible(account,
              assertion.getProvider(), assertion.getSubject(), assertion.getEmail())) {
            writeError(response, HttpServletResponse.SC_CONFLICT,
                "Account is already linked to a different SSO identity");
            return;
          }
        }
      }

      String sessionToken = generateToken();
      Date loginAt = new Date();
      if (account == null) {
        account = EtendoGoJwtDalHelper.createSsoAccount(assertion.getEmail(), assertion.getName(),
            assertion.getProvider(), assertion.getSubject(), assertion.getEmail(), sessionToken,
            loginAt);
      } else {
        EtendoGoJwtDalHelper.updateSsoSession(account, assertion.getEmail(), sessionToken, loginAt);
      }

      JSONObject accountJson = new JSONObject();
      accountJson.put("id", account.getId());
      accountJson.put(FIELD_EMAIL, account.getEmail());
      accountJson.put("name", account.getName());

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_TOKEN, sessionToken);
      result.put(FIELD_AUTH_METHOD, "sso");
      result.put(FIELD_ACCOUNT, accountJson);

      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("SSO login", e, log);
      log.error("Database error during SSO login", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "SSO login failed due to a server error");
    } catch (JSONException e) {
      EtendoGoDalHelper.rollbackDalChanges("SSO login response", e, log);
      log.error("JSON error building SSO login response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String extractSsoProvider(String path) {
    if (path == null || !path.startsWith(SSO_PREFIX)) {
      return null;
    }
    String provider = path.substring(SSO_PREFIX.length());
    if (provider.endsWith("/")) {
      provider = provider.substring(0, provider.length() - 1);
    }
    if (provider.isEmpty() || provider.contains("/")) {
      return null;
    }
    return provider.toLowerCase(Locale.ROOT);
  }

  /**
   * POST /sws/go/password-reset/request
   * Body: { "email": "..." }
   * Always returns neutral success for syntactically valid requests.
   */
  private void handlePasswordResetRequest(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_JSON_BODY);
      return;
    }

    String email;
    try {
      email = body.getString(FIELD_EMAIL).trim().toLowerCase();
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing required field: email");
      return;
    }
    if (email.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Field email must not be empty");
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      Account account = EtendoGoJwtDalHelper.findActiveAccountByEmail(email);
      if (account != null && EtendoGoJwtDalHelper.hasLocalPassword(account)) {
        storeResetTokenAndSendEmail(account, PublicUrlResolver.resolveConfiguredAppBaseUrl());
      }
      writePasswordResetNeutralResponse(response);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("password reset request", e, log);
      log.error("Password reset request failed", e);
      writePasswordResetNeutralResponse(response);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * POST /sws/go/password-reset/confirm
   * Body: { "token": "...", "password": "..." }
   */
  private void handlePasswordResetConfirm(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_JSON_BODY);
      return;
    }

    String token;
    String password;
    try {
      token = body.getString(FIELD_TOKEN).trim();
      password = body.getString(FIELD_PASSWORD);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required fields: token, password");
      return;
    }
    if (token.isEmpty() || password.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Fields token and password must not be empty");
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      Account account = EtendoGoJwtDalHelper.findActiveAccountByResetTokenHash(
          hashResetToken(token), new Date());
      if (account == null) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, PASSWORD_RESET_INVALID_MESSAGE);
        return;
      }
      EtendoGoJwtDalHelper.consumePasswordReset(account, hashPassword(password), new Date());

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_MESSAGE, "Password reset successfully");
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("password reset confirm", e, log);
      log.error("Password reset confirm failed", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error building password reset response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * POST /sws/go/change-password
   * Header: Authorization: Bearer <session_token>
   * Body: { "currentPassword": "...", "newPassword": "..." }
   */
  private void handleChangePassword(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String token = extractBearerToken(request);
    if (token == null) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_AUTHORIZATION_HEADER);
      return;
    }

    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_JSON_BODY);
      return;
    }

    String currentPassword;
    String newPassword;
    try {
      currentPassword = body.getString("currentPassword");
      newPassword = body.getString("newPassword");
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required fields: currentPassword, newPassword");
      return;
    }
    if (currentPassword.isEmpty() || newPassword.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Fields currentPassword and newPassword must not be empty");
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      Account account = EtendoGoJwtDalHelper.findActiveAccountByToken(token);
      if (account == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }
      if (!EtendoGoJwtDalHelper.hasLocalPassword(account)) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST,
            "Local password is not configured for this account");
        return;
      }
      if (!verifyPassword(currentPassword, account.getPasswordHash())) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Current password is invalid");
        return;
      }
      String sessionToken = generateToken();
      EtendoGoJwtDalHelper.changePassword(account, hashPassword(newPassword), sessionToken,
          new Date());
      sendAuthEmailBestEffort("password-changed",
          () -> authEmailSender.sendPasswordChanged(account));

      JSONObject accountJson = new JSONObject();
      accountJson.put("id", account.getId());
      accountJson.put(FIELD_EMAIL, account.getEmail());
      accountJson.put("name", account.getName());

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_TOKEN, sessionToken);
      result.put(FIELD_ACCOUNT, accountJson);
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("change password", e, log);
      log.error("Change password failed", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error building change password response", e);
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

      Account account = EtendoGoJwtDalHelper.findActiveAccountByToken(token);
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

      Account account = EtendoGoJwtDalHelper.findActiveAccountByToken(token);
      if (account == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }

      org.codehaus.jettison.json.JSONArray envArray = new org.codehaus.jettison.json.JSONArray();
      List<User> environmentUsers = EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail(account.getEmail());
      for (User environmentUser : environmentUsers) {
        Client client = environmentUser.getClient();
        List<Organization> organizations = EtendoGoJwtDalHelper.findNonStarOrganizations(client.getId());
        if (organizations.isEmpty()) {
          envArray.put(EtendoGoJwtDalHelper.buildEnvironmentJson(client, null, environmentUser));
          continue;
        }
        for (Organization organization : organizations) {
          envArray.put(EtendoGoJwtDalHelper.buildEnvironmentJson(client, organization, environmentUser));
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

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      String accountEmail = EtendoGoJwtSupport.requireAccountEmail(token);
      if (accountEmail == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }

      if (!EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(accountEmail, userId)) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN,
            "User does not belong to this account");
        return;
      }

      EtendoGoJwtSupport.RoleListData roleListData =
          EtendoGoJwtSupport.loadRoleListData(userId);
      writeEnvironmentLoginResponse(response, userId, roleListData);

    } catch (RuntimeException e) {
      log.error("Database error in /login", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error in /login", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } catch (Exception e) {
      log.error("Token generation error in /login", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Token generation failed");
    } finally {
      OBContext.restorePreviousMode();
    }
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

    String accountEmail = resolveOnboardingAccountEmail(token, response);
    if (accountEmail == null) {
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

    // Keepalive: a background thread emits a blank NDJSON line on a fixed cadence so the
    // gap between bytes never exceeds the CloudFront/proxy inter-byte timeout while a slow
    // step runs. The frontend skips empty lines (processLines), so this needs no UI change.
    ScheduledExecutorService heartbeat = startOnboardingHeartbeat(writer);

    try {
      VariablesSecureApp vars = prepareAdminContext(writer, onboardingRequest.language);
      String clientId = resolveOrCreateClient(writer, vars, accountEmail, onboardingRequest, currencyId,
          adminPassword);
      if (clientId == null) {
        return;
      }

      AdminContextData adminContext = resolveAdminContextData(clientId, writer);
      if (adminContext == null) {
        return;
      }

      Boolean organizationCreated = ensureOrganization(writer, onboardingRequest.clientName, clientId,
          adminContext, currencyId);
      if (organizationCreated == null) {
        return;
      }

      String orgId = resolveOrganizationId(clientId);
      if (orgId == null) {
        sendProgress(writer, PROGRESS_DATASET, PROGRESS_ERROR,
            "Could not resolve organization for onboarding dataset import");
        sendFinalResult(writer, false, "Organization not found after onboarding");
        return;
      }

      if (!ensureOnboardingDataset(writer, clientId, orgId, organizationCreated,
          adminContext.adminUserId, adminContext.adminRoleId, onboardingRequest)) {
        return;
      }

      EtendoGoDalHelper.commitDalChanges("onboarding", log);
      Account account = findAccountForCommittedOnboarding(token, accountEmail);
      String normalizedLanguage = StringUtils.trimToNull(onboardingRequest.language);
      sendAuthEmailBestEffort("environment-ready",
          () -> authEmailSender.sendEnvironmentReady(account, clientId, normalizedLanguage));

      sendProgress(writer, "finalize", PROGRESS_IN_PROGRESS, "Finalizing setup...");
      sendProgress(writer, "finalize", "done", "Environment ready");
      sendFinalResult(writer, true, "Environment created successfully");

    } catch (Exception e) {
      log.error("Onboarding failed", e);
      EtendoGoDalHelper.rollbackDalChanges("onboarding", e, log);
      sendProgress(writer, PROGRESS_ERROR, PROGRESS_ERROR,
          "Onboarding failed: " + e.getMessage());
      sendFinalResult(writer, false, "Onboarding failed: " + e.getMessage());
    } finally {
      // Stop the keepalive before the final flush so no heartbeat races the result line.
      heartbeat.shutdownNow();
      OBContext.restorePreviousMode();
      writer.flush();
      // PrintWriter swallows IOExceptions (broken pipe): when CloudFront or any proxy
      // hits its response timeout it silently drops the client mid-stream while the
      // backend keeps running to completion (and commits). checkError() is the only
      // way to detect it. Surface it explicitly so it stops being invisible in the logs.
      if (writer.checkError()) {
        log.warn("Onboarding stream to client was lost before the result line was delivered "
            + "(likely a CloudFront/proxy response timeout). The environment may have been "
            + "created successfully server-side, but the UI will report a false failure. "
            + "accountEmail={}", accountEmail);
      }
    }
  }

  /**
   * Starts a daemon scheduler that emits a blank NDJSON line every
   * {@link #ONBOARDING_HEARTBEAT_SECONDS} seconds. This keeps bytes flowing on the
   * streaming response so a long-running onboarding step never leaves the connection
   * idle past the CloudFront/proxy inter-byte timeout (which would silently drop the
   * client mid-stream and make the UI report a false failure).
   *
   * <p>The caller MUST call {@code shutdownNow()} on the returned executor in a
   * {@code finally} block.
   */
  private ScheduledExecutorService startOnboardingHeartbeat(PrintWriter writer) {
    return startOnboardingHeartbeat(writer, ONBOARDING_HEARTBEAT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Interval-injectable variant (package-private for tests so they do not have to wait
   * the production {@link #ONBOARDING_HEARTBEAT_SECONDS} cadence).
   */
  ScheduledExecutorService startOnboardingHeartbeat(PrintWriter writer, long interval, TimeUnit unit) {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "onboarding-heartbeat");
      thread.setDaemon(true);
      return thread;
    });
    scheduler.scheduleAtFixedRate(() -> sendHeartbeat(writer), interval, interval, unit);
    return scheduler;
  }

  /**
   * Writes a NDJSON heartbeat line to keep the connection alive during slow steps. It is a
   * self-describing {@code {"type":"heartbeat"}} object (not a blank line) so it is visible
   * in raw stream captures and logs while still being ignored by the frontend, which only
   * reacts to {@code type=progress} and {@code type=result}. PrintWriter is internally
   * synchronized, so concurrent writes from this heartbeat and the main onboarding thread
   * each emit whole lines without corrupting the NDJSON output.
   */
  void sendHeartbeat(PrintWriter writer) {
    try {
      JSONObject heartbeat = new JSONObject();
      heartbeat.put("type", "heartbeat");
      heartbeat.put(FIELD_TIMESTAMP, Instant.now().toString());
      writer.println(heartbeat.toString());
      writer.flush();
    } catch (JSONException e) {
      log.warn("Error writing heartbeat", e);
    }
  }

  private String resolveOnboardingAccountEmail(String token, HttpServletResponse response)
      throws IOException {
    String accountEmail = null;
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      String resolvedEmail = EtendoGoJwtSupport.requireAccountEmail(token);
      if (resolvedEmail == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
      } else {
        accountEmail = resolvedEmail;
      }
    } catch (RuntimeException e) {
      log.error("Database error validating token for onboarding", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
    return accountEmail;
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
      // Country drives the org's tax resolution; default to Spain (ES) when the form omits it.
      data.countryCode = body.optString("countryCode", "ES").trim();
      data.address = body.optString("address", "").trim();
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
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    try {
      var currency = EtendoGoJwtDalHelper.findCurrencyByIsoCode(currencyIso);
      if (currency != null) {
        return currency.getId();
      }
    } finally {
      OBContext.restorePreviousMode();
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

  private String resolveOrCreateClient(PrintWriter writer, VariablesSecureApp vars,
      String accountEmail, OnboardingRequestData requestData, String currencyId,
      String adminPassword) throws Exception {
    sendProgress(writer, PROGRESS_CLIENT, PROGRESS_IN_PROGRESS,
        "Creating client: " + requestData.clientName + "...");
    String clientId = EtendoGoJwtSupport.findClientIdByName(requestData.clientName);
    if (clientId != null) {
      return validateExistingClient(writer, requestData.clientName, clientId) ? clientId : null;
    }

    String clientUser = EtendoGoJwtSupport.buildClientUsername(accountEmail, requestData.clientName);
    if (!createClient(vars, currencyId, requestData.clientName, clientUser, adminPassword, writer)) {
      return null;
    }
    return EtendoGoJwtSupport.findClientIdByName(requestData.clientName);
  }

  private boolean validateExistingClient(PrintWriter writer, String clientName,
      String clientId) {
    if (!EtendoGoJwtSupport.hasStarOrganization(clientId)) {
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
      PrintWriter writer) {
    AdminContextData data = new AdminContextData();
    var adminUserRole = EtendoGoJwtDalHelper.findClientAdminUserRole(clientId);
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
    data.starOrgId = EtendoGoJwtSupport.findStarOrgId(clientId);
    OBContext.setOBContext(data.adminUserId, data.adminRoleId, clientId, data.starOrgId);
    return data;
  }

  private Boolean ensureOrganization(PrintWriter writer, String clientName,
      String clientId, AdminContextData adminContext, String currencyId) {
    sendProgress(writer, PROGRESS_ORGANIZATION, PROGRESS_IN_PROGRESS,
        "Creating organization: " + clientName + "...");
    if (EtendoGoJwtSupport.organizationExists(clientId)) {
      sendProgress(writer, PROGRESS_ORGANIZATION, "done",
          "Organization already exists, resuming...");
      return Boolean.FALSE;
    }
    return createOrganization(writer, clientName, clientId, adminContext.starOrgId, currencyId)
        ? Boolean.TRUE
        : null;
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
    // Onboarding imports accounting-ready sample data after the organization exists.
    // For fresh clients there is no ready package organization yet, so forcing accounting
    // during InitialOrgSetup would fail before dataset import can run.
    OBError orgResult = orgSetup.createOrganization(clientName, "",
        LEGAL_WITH_ACCOUNTING_ORG_TYPE_ID, starOrgId, null, "", "", false, null, currencyId,
        false, false, false, false, false);
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
    Organization organization = EtendoGoJwtDalHelper.findFirstOrganization(clientId);
    return organization != null ? organization.getId() : null;
  }

  boolean ensureOnboardingDataset(PrintWriter writer, String clientId, String orgId,
      boolean importRequired, String adminUserId, String adminRoleId,
      OnboardingRequestData requestData) {
    if (importRequired && !importOnboardingDataset(writer, clientId, orgId)) {
      return false;
    }
    if (!importRequired) {
      sendProgress(writer, PROGRESS_DATASET, "done",
          "Existing organization detected, skipping onboarding dataset import");
    }
    if (importRequired && !wireAccounting(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (importRequired && !wirePeriodControl(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!generateOnboardingSequences(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!markOrgReady(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!setupFiscalData(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!wireOrgInfo(writer, clientId, orgId, adminUserId, adminRoleId, requestData)) {
      return false;
    }
    if (!ensureDefaultCustomer(writer, clientId, orgId, adminUserId, adminRoleId, importRequired)) {
      return false;
    }
    // Final action before commitDalChanges: stamp the tenant's data-fix baseline so it lands in the
    // same atomic onboarding commit. A genuine SQL error propagates (not caught here) so the outer
    // handleOnboarding catch rolls back cleanly; the expected ON CONFLICT->0-rows case is benign.
    //
    // The baseline applied_utc is a hardcoded CUT (ONBOARDING_PROVISIONED_THROUGH in
    // OnboardingBaselineService), NOT now(). It represents the last corrective data-fix that
    // this version of onboarding already provisions natively, so the runner skips all fixes
    // at-or-before that cutoff for freshly-onboarded tenants.
    //
    // WHEN ADDING A NEW ONBOARDING SERVICE (gap fix): bump ONBOARDING_PROVISIONED_THROUGH to the
    // timestamp of the corresponding .sql fix in cli/src/data-fixes/sql/. See the gap-closing
    // workflow in docs/etendo-ad/onboarding-and-datafixes-map.md §0.
    return registerBaseline(writer, clientId);
  }

  boolean importOnboardingDataset(PrintWriter writer, String clientId, String orgId) {
    sendProgress(writer, PROGRESS_DATASET, PROGRESS_IN_PROGRESS,
        "Importing onboarding dataset...");
    try {
      onboardingDatasetImportService.importDataset(clientId, orgId);
      sendProgress(writer, PROGRESS_DATASET, "done", "Onboarding dataset imported");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding dataset import", e, log);
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Onboarding dataset import failed";
      sendProgress(writer, PROGRESS_DATASET, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  boolean wireAccounting(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_ACCOUNTING, PROGRESS_IN_PROGRESS,
        "Wiring organization general ledger...");
    try {
      onboardingAccountingWiringService.wire(clientId, orgId, adminUserId, adminRoleId);
      sendProgress(writer, PROGRESS_ACCOUNTING, "done", "Organization general ledger wired");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding accounting wiring", e, log);
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Organization accounting wiring failed";
      sendProgress(writer, PROGRESS_ACCOUNTING, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  boolean wirePeriodControl(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_PERIOD_CONTROL, PROGRESS_IN_PROGRESS,
        "Enabling fiscal period control...");
    try {
      onboardingPeriodControlService.wire(clientId, orgId, adminUserId, adminRoleId);
      sendProgress(writer, PROGRESS_PERIOD_CONTROL, "done", "Fiscal period control enabled");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding period-control wiring", e, log);
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Organization period-control wiring failed";
      sendProgress(writer, PROGRESS_PERIOD_CONTROL, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  boolean generateOnboardingSequences(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_SEQUENCES, PROGRESS_IN_PROGRESS,
        "Generating organization sequences...");
    try {
      int count = onboardingSequenceGeneratorService.generateSequences(clientId, orgId, adminUserId,
          adminRoleId);
      sendProgress(writer, PROGRESS_SEQUENCES, "done",
          "Organization sequences generated: " + count);
      return true;
    } catch (Exception e) {
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Organization sequence generation failed";
      sendProgress(writer, PROGRESS_SEQUENCES, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  boolean markOrgReady(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_ORG_READY, PROGRESS_IN_PROGRESS,
        "Marking organization as ready...");
    try {
      onboardingMarkOrgReadyService.markOrgReady(clientId, orgId, adminUserId, adminRoleId);
      sendProgress(writer, PROGRESS_ORG_READY, "done", "Organization is ready");
      return true;
    } catch (Exception e) {
      log.error("Error marking organization as ready", e);
      String errorMessage = e.getMessage() != null ? e.getMessage() : "Mark org ready failed";
      sendProgress(writer, PROGRESS_ORG_READY, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  boolean setupFiscalData(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_FISCAL, PROGRESS_IN_PROGRESS,
        "Setting up fiscal data...");
    try {
      onboardingFiscalDataSetupService.setup(clientId, orgId, adminUserId, adminRoleId);
      sendProgress(writer, PROGRESS_FISCAL, "done", "Fiscal data ready");
      return true;
    } catch (Exception e) {
      log.error("Error during fiscal data setup", e);
      String errorMessage = e.getMessage() != null ? e.getMessage() : "Fiscal data setup failed";
      sendProgress(writer, PROGRESS_FISCAL, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  boolean wireOrgInfo(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId, OnboardingRequestData requestData) {
    sendProgress(writer, PROGRESS_ORG_INFO, PROGRESS_IN_PROGRESS,
        "Setting up organization address...");
    try {
      String countryCode = requestData != null ? requestData.countryCode : null;
      String address = requestData != null ? requestData.address : null;
      onboardingOrgInfoService.ensureOrgInfo(clientId, orgId, adminUserId, adminRoleId,
          countryCode, address);
      sendProgress(writer, PROGRESS_ORG_INFO, "done", "Organization address ready");
      return true;
    } catch (Exception e) {
      log.error("Error during organization info setup", e);
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Organization info setup failed";
      sendProgress(writer, PROGRESS_ORG_INFO, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  boolean ensureDefaultCustomer(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId, boolean importRequired) {
    sendProgress(writer, PROGRESS_CUSTOMER, PROGRESS_IN_PROGRESS,
        "Creating default customer...");
    try {
      onboardingDefaultCustomerService.ensureDefaultCustomer(clientId, orgId, adminUserId,
          adminRoleId);
      // A2: provision the per-BP posting accounts now that the default customer exists. wireAccounting
      // ran earlier (before any business partner existed), so C_BP_CUSTOMER_ACCT would otherwise stay
      // empty. Gated on importRequired because the ledger it copies defaults from comes from the import.
      if (importRequired) {
        onboardingAccountingWiringService.wireBusinessPartnerAccounts(clientId, orgId, adminUserId,
            adminRoleId);
      }
      sendProgress(writer, PROGRESS_CUSTOMER, "done", "Default customer ready");
      return true;
    } catch (Exception e) {
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Default customer creation failed";
      sendProgress(writer, PROGRESS_CUSTOMER, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  /**
   * Registers the tenant's data-fix baseline row (the LIVE preventive counterpart of the corrective
   * runner's DETECTED sweep) as the final onboarding action before the commit.
   *
   * <p>Unlike the other steps, a genuine SQL failure here is NOT caught-and-returned-false: it
   * propagates so {@code handleOnboarding}'s catch performs a clean {@code rollbackDalChanges}.
   * Swallowing it would poison the shared transaction and abort the otherwise-successful commit.
   * The expected {@code ON CONFLICT DO NOTHING} → 0-rows outcome never throws (DETECTED conserved).</p>
   */
  boolean registerBaseline(PrintWriter writer, String clientId) {
    sendProgress(writer, PROGRESS_BASELINE, PROGRESS_IN_PROGRESS,
        "Registering data-fix baseline...");
    onboardingBaselineService.registerBaseline(clientId);
    sendProgress(writer, PROGRESS_BASELINE, "done", "Data-fix baseline registered");
    return true;
  }



  /**
   * Write a NDJSON progress line.
   */
  void sendProgress(PrintWriter writer, String step, String status, String message) {
    try {
      JSONObject progress = new JSONObject();
      progress.put("type", "progress");
      progress.put("step", step);
      progress.put(FIELD_STATUS, status);
      progress.put(FIELD_MESSAGE, message);
      progress.put(FIELD_TIMESTAMP, Instant.now().toString());
      writer.println(progress.toString());
      writer.flush();
      // If the flush failed the client is already gone (broken pipe, swallowed by
      // PrintWriter). Log at DEBUG which step was streaming so the cut point is
      // identifiable when onboarding-stream logging is enabled.
      if (writer.checkError()) {
        log.debug("Client connection lost while streaming onboarding step '{}' (status={})",
            step, status);
      }
    } catch (JSONException e) {
      log.warn("Error writing progress", e);
    }
  }

  /**
   * Write the final NDJSON result line.
   */
  void sendFinalResult(PrintWriter writer, boolean success, String message) {
    try {
      JSONObject result = new JSONObject();
      result.put("type", "result");
      result.put(FIELD_SUCCESS, success);
      result.put(FIELD_MESSAGE, message);
      result.put(FIELD_TIMESTAMP, Instant.now().toString());
      writer.println(result.toString());
      writer.flush();
      // The final result line is what the UI waits for. If the flush failed the client
      // never received it (broken pipe swallowed by PrintWriter) — the UI will report a
      // false failure even though the backend finished. Make that explicit.
      if (writer.checkError()) {
        log.warn("Onboarding final result (success={}) could not be delivered to the client; "
            + "the connection was already closed (likely a CloudFront/proxy stream timeout).",
            success);
      }
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
  private Account findAccountForCommittedOnboarding(String token, String accountEmail) {
    Account account = EtendoGoJwtDalHelper.findActiveAccountByToken(token);
    return account != null ? account : EtendoGoJwtDalHelper.findActiveAccountByEmail(accountEmail);
  }

  private void storeResetTokenAndSendEmail(Account account, String appBaseUrl) {
    EtendoGoJwtDalHelper.PasswordResetTokenState previousTokenState =
        EtendoGoJwtDalHelper.capturePasswordResetToken(account);
    String resetToken = generatePasswordResetToken();
    String resetTokenHash = hashResetToken(resetToken);
    Date expiresAt = Date.from(Instant.now().plusSeconds(PASSWORD_RESET_TTL_SECONDS));
    EtendoGoJwtDalHelper.storePasswordResetToken(account, resetTokenHash, expiresAt);

    boolean emailSent = false;
    String resetLink = EtendoGoAuthLinkBuilder.resetPasswordLink(resetToken, appBaseUrl);
    if (resetLink == null) {
      log.warn("Auth email reset-password skipped because the public app base URL is not configured");
    } else {
      try {
        emailSent = authEmailSender.sendPasswordReset(account, resetTokenHash, resetLink);
      } catch (RuntimeException e) {
        log.warn("Auth email reset-password failed after token storage", e);
      }
    }
    if (!emailSent) {
      EtendoGoJwtDalHelper.restorePasswordResetToken(account, previousTokenState);
    }
  }

  private void sendAuthEmailBestEffort(String contractName, Runnable sendAction) {
    try {
      sendAction.run();
    } catch (RuntimeException e) {
      log.warn("Transactional auth email {} failed without blocking account flow",
          contractName, e);
    }
  }



  /**
   * Generate a random URL-safe session token (UUID without hyphens, 32 hex chars).
   */
  private String generateToken() {
    return UUID.randomUUID().toString().replace("-", "").toLowerCase();
  }

  private String generatePasswordResetToken() {
    byte[] token = new byte[32];
    new SecureRandom().nextBytes(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  private String hashResetToken(String token) {
    try {
      MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
      byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
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
    return new JSONObject(readRawBody(request));
  }

  private String readRawBody(HttpServletRequest request) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      char[] buffer = new char[4096];
      int charsRead;
      while ((charsRead = reader.read(buffer)) != -1) {
        sb.append(buffer, 0, charsRead);
      }
    }
    return sb.toString();
  }

  private void writePasswordResetNeutralResponse(HttpServletResponse response)
      throws IOException {
    try {
      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_MESSAGE, PASSWORD_RESET_NEUTRAL_MESSAGE);
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (JSONException e) {
      log.error("JSON error building password reset request response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    }
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
    ProtocolErrorAdapters.writeRestError(
        response,
        status,
        message,
        FIELD_MESSAGE,
        FIELD_STATUS,
        PROGRESS_ERROR);
  }

  private static class OnboardingRequestData {
    private String clientName;
    private String currencyIso;
    private String language;
    private String countryCode;
    private String address;
  }

  private static class AdminContextData {
    private String adminRoleId;
    private String adminUserId;
    private String starOrgId;
  }
}
