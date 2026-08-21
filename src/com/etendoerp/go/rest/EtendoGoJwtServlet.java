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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Instant;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialClientSetup;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.EtendoGoCorsServlet;
import com.etendoerp.go.common.ProtocolErrorAdapters;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.payment.TenantPaywallService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.payment.HostedCheckoutService;
import com.etendoerp.go.payment.CheckoutConfiguration;
import com.etendoerp.go.payment.CheckoutPaymentRegistry;
import com.etendoerp.go.payment.CheckoutWebhookVerifier;
import com.etendoerp.go.onboarding.OnboardingAcctdimCentrallyMaintainedService;
import com.etendoerp.go.onboarding.OnboardingBaselineService;
import com.etendoerp.go.onboarding.OnboardingAccountingWiringService;
import com.etendoerp.go.onboarding.OnboardingDatasetImportService;
import com.etendoerp.go.onboarding.OnboardingDefaultCustomerService;
import com.etendoerp.go.onboarding.OnboardingFiscalDataSetupService;
import com.etendoerp.go.onboarding.OnboardingOrgInfoService;
import com.etendoerp.go.onboarding.OnboardingMarkOrgReadyService;
import com.etendoerp.go.onboarding.OnboardingPeriodControlService;
import com.etendoerp.go.onboarding.OnboardingBankConnectionSyncService;
import com.etendoerp.go.onboarding.OnboardingSequenceGeneratorService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoLegacyBearer;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.IssuedGoSession;
import com.etendoerp.go.session.JdbcGoSessionStore;
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
 *   GET  /sws/go/onboarding/draft  — Get the saved onboarding wizard draft (requires session token)
 *   POST /sws/go/onboarding/draft  — Save or clear the onboarding wizard draft (requires session token)
 *   GET  /sws/go/me           — Get current account info (requires session token)
 *   GET  /sws/go/environments — List environments for the account (requires session token),
 *                               each carrying its plan ("free" | "productive")
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
  private static final String FIELD_HTTP_STATUS = "httpStatus";
  private static final String FIELD_TOKEN = "token";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_CODE = "code";
  private static final String FIELD_USER_MESSAGE = "userMessage";
  private static final String FIELD_PASSWORD = "password";
  private static final String FIELD_SUCCESS = "success";
  private static final String CODE_INVITATION_ERROR = "INVITATION_ERROR";
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String FIELD_ACCOUNT = "account";
  private static final String FIELD_AUTH_METHOD = "authMethod";
  private static final String FIELD_LANGUAGE = "language";
  private static final String FIELD_CSRF_TOKEN = "csrfToken";
  private static final String FIELD_USER_ID = "userId";
  private static final String FIELD_ROLE_LIST = "roleList";
  private static final String HEADER_USER_AGENT = "User-Agent";
  private static final String HEADER_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
  private static final String VALUE_NOSNIFF = "nosniff";
  private static final String HEADER_CACHE_CONTROL = "Cache-Control";
  private static final String VALUE_NO_STORE = "no-store";
  private static final String HEADER_SET_COOKIE = "Set-Cookie";
  private static final String MSG_CSRF_VALIDATION_FAILED = "CSRF validation failed";
  private static final String PATH_SESSION = "/session";
  private static final String ERROR_UNKNOWN_ENDPOINT = "Unknown endpoint: ";
  private static final String FIELD_PAYMENT_TOKEN = "paymentToken";
  private static final String FIELD_ACCOUNT_EMAIL = "accountEmail";
  private static final String FIELD_ERROR = "error";
  private static final String ERROR_PAYMENT_REQUIRED = "payment_required";
  // javax.servlet.http.HttpServletResponse predates RFC 7231 and has no 402 constant.
  private static final int SC_PAYMENT_REQUIRED = 402;
  private static final String ZERO_ID = "0";
  private static final String STATUS_SUCCESS = FIELD_SUCCESS;
  private static final String INVALID_JSON_BODY = "Invalid JSON body";
  private static final String INTERNAL_ERROR = "Internal error";
  private static final String SERVER_ERROR = "Server error";
  private static final String INVALID_AUTHORIZATION_HEADER =
      "Missing or invalid Authorization header";
  private static final String INVALID_OR_EXPIRED_TOKEN = "Invalid or expired token";
  // ETP-4664 — stable, machine-readable codes for register/login errors, so the
  // frontend can translate by code instead of showing the raw English message.
  private static final String CODE_INVALID_REQUEST = "INVALID_REQUEST";
  private static final String CODE_REGISTER_MISSING_FIELDS = "REGISTER_MISSING_FIELDS";
  private static final String CODE_REGISTER_EMPTY_FIELDS = "REGISTER_EMPTY_FIELDS";
  private static final String CODE_INVALID_EMAIL_FORMAT = "INVALID_EMAIL_FORMAT";
  private static final String CODE_EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";
  private static final String CODE_REGISTER_SERVER_ERROR = "REGISTER_SERVER_ERROR";
  private static final String CODE_LOGIN_MISSING_FIELDS = "LOGIN_MISSING_FIELDS";
  private static final String CODE_INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
  private static final String CODE_LOGIN_SERVER_ERROR = "LOGIN_SERVER_ERROR";
  private static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";
  // ETP-4575 — the 5-arg writeError repeats each message as both `message` and
  // `userMessage`, so every call site duplicated its literal twice (Sonar S1192).
  private static final String INVALID_CREDENTIALS = "Invalid credentials";
  private static final String MISSING_EMAIL_PASSWORD =
      "Missing required fields: email, password";
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
  private static final String PROGRESS_BANK_CONNECTION_SYNC = "bankConnectionSync";
  private static final String PROGRESS_BP_GROUP_ACCT_PATCH = "bpGroupAcctPatch";
  private static final String PROGRESS_ACCTDIM_VISIBILITY = "acctdimVisibility";
  private static final String LEGAL_WITH_ACCOUNTING_ORG_TYPE_ID = "1";
  // Stable codes for provisioning failures whose underlying message is an unresolved AD message
  // key. Mirrored by the frontend's onboarding/errorMessages.js (ETP-4665).
  private static final String ERROR_CODE_CLIENT_CREATION_FAILED = "CLIENT_CREATION_FAILED";
  private static final String ERROR_CODE_ORG_CREATION_FAILED = "ORG_CREATION_FAILED";
  private static final long PASSWORD_RESET_TTL_SECONDS = 30 * 60L;
  private static final String PASSWORD_RESET_NEUTRAL_MESSAGE =
      "If an account exists for that email, password reset instructions will be sent.";
  private static final String PASSWORD_RESET_INVALID_MESSAGE =
      "Invalid or expired password reset token";
  private static final String SSO_PREFIX = "/sso/";
  private static final String PATH_ONBOARDING_DRAFT = "/onboarding/draft";
  private static final String FIELD_DRAFT = "draft";
  private static final String FIELD_DRAFT_STEP = "step";
  private static final String FIELD_DRAFT_FORM = "form";
  private static final String FIELD_COUNTRY_CODE = "countryCode";
  private static final int ONBOARDING_DRAFT_MAX_LENGTH = 4000;
  private static final String FIELD_FULL_NAME = "fullName";
  private static final String FIELD_ADDRESS = "address";
  private static final String[] ONBOARDING_DRAFT_FORM_FIELDS = { FIELD_FULL_NAME, "businessType",
      FIELD_CLIENT_NAME, "currency", FIELD_LANGUAGE, FIELD_COUNTRY_CODE, "fiscalIdType",
      "fiscalIdValue", FIELD_ADDRESS, "sector" };

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
  OnboardingAcctdimCentrallyMaintainedService onboardingAcctdimCentrallyMaintainedService =
      new OnboardingAcctdimCentrallyMaintainedService();
  OnboardingBaselineService onboardingBaselineService =
      new OnboardingBaselineService();
  OnboardingBankConnectionSyncService onboardingBankConnectionSyncService =
      new OnboardingBankConnectionSyncService();
  TenantPaywallService tenantPaywallService = new TenantPaywallService();
  TenantPlanService tenantPlanService = new TenantPlanService();
  HostedCheckoutService hostedCheckoutService = new HostedCheckoutService();
  CompanyInvitationService companyInvitationService;
  private final TransactionalAuthEmailSender authEmailSender;
  private final EtendoGoSsoProviderRegistry ssoProviderRegistry;
  private final GoSessionService goSessionService;

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
    this(authEmailSender, ssoProviderRegistry, new GoSessionService(new JdbcGoSessionStore()));
  }

  EtendoGoJwtServlet(TransactionalAuthEmailSender authEmailSender,
      EtendoGoSsoProviderRegistry ssoProviderRegistry, GoSessionService goSessionService) {
    this.authEmailSender = authEmailSender;
    this.ssoProviderRegistry = ssoProviderRegistry;
    this.goSessionService = goSessionService;
    this.companyInvitationService = new CompanyInvitationService(authEmailSender);
  }

  // --- HTTP method dispatchers ---

  /**
   * Route matcher tolerating an optional trailing slash.
   */
  private static boolean isPath(String path, String route) {
    return route.equals(path) || (route + "/").equals(path);
  }

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if (isPath(path, "/me")) {
      handleMe(request, response);
    } else if (isPath(path, PATH_ONBOARDING_DRAFT)) {
      handleGetOnboardingDraft(request, response);
    } else if (isPath(path, "/environments")) {
      handleEnvironments(request, response);
    } else if (isPath(path, "/login")) {
      handleEnvironmentLogin(request, response);
    } else if (isPath(path, PATH_SESSION)) {
      handleSessionRestore(request, response);
    } else if (isPath(path, "/company-invitations/mine")) {
      handleCompanyInvitationMine(request, response);
    } else if (isPath(path, "/company-invitations/resolve")) {
      handleCompanyInvitationResolve(request, response);
    } else if (path != null && path.startsWith("/checkout/sessions/")) {
      handleCheckoutStatus(request, response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_UNKNOWN_ENDPOINT + path);
    }
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    // Kept ahead of everything else: the provider calls this one unauthenticated,
    // so it must not fall through any of the credential-bearing groups below.
    if (isPath(path, "/checkout/webhook")) {
      handleCheckoutWebhook(request, response);
      return;
    }
    // Split into groups purely to keep this dispatcher under its cognitive-complexity
    // limit — the chain reached 20 once the session family and the invitation
    // endpoints both landed here. Every route below is an EXACT match on a distinct
    // literal, and the two prefix matches cannot collide (`/sso/` is the legacy
    // provider path, `/session/sso/` the session-family one, and neither string is a
    // prefix of the other), so grouping does not change which handler wins.
    if (dispatchSessionPost(path, request, response)
        || dispatchLegacyAuthPost(path, request, response)
        || dispatchCredentialPost(path, request, response)
        || dispatchProvisioningPost(path, request, response)) {
      return;
    }
    writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_UNKNOWN_ENDPOINT + path);
  }

  /** The `/session*` family (ETP-4575): cookie-backed sessions. */
  private boolean dispatchSessionPost(String path, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (isPath(path, "/session/register")) {
      handleSessionRegister(request, response);
    } else if (isPath(path, PATH_SESSION)) {
      handleSessionCreate(request, response);
    } else if (isPath(path, "/session/environment")) {
      handleSessionEnvironment(request, response);
    } else if (isPath(path, "/session/refresh")) {
      handleSessionRefresh(request, response);
    } else if (path != null && path.startsWith("/session/sso/")) {
      handleSessionCreateSso(path.substring("/session/sso/".length()), request, response);
    } else {
      return false;
    }
    return true;
  }

  /** The pre-session endpoints, still answering with a bearer token. */
  private boolean dispatchLegacyAuthPost(String path, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    String ssoProvider = extractSsoProvider(path);
    if (isPath(path, "/register")) {
      handleRegister(request, response);
    } else if (isPath(path, "/login")) {
      handleLogin(request, response);
    } else if (ssoProvider != null) {
      handleSsoLogin(ssoProvider, request, response);
    } else {
      return false;
    }
    return true;
  }

  /** Password reset and change — credential management, no session created. */
  private boolean dispatchCredentialPost(String path, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (isPath(path, "/password-reset/request")) {
      handlePasswordResetRequest(request, response);
    } else if (isPath(path, "/password-reset/confirm")) {
      handlePasswordResetConfirm(request, response);
    } else if (isPath(path, "/change-password")) {
      handleChangePassword(request, response);
    } else {
      return false;
    }
    return true;
  }

  /** Onboarding, checkout and company invitations. */
  private boolean dispatchProvisioningPost(String path, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (isPath(path, PATH_ONBOARDING_DRAFT)) {
      handleSaveOnboardingDraft(request, response);
    } else if (isPath(path, "/onboarding")) {
      handleOnboarding(request, response);
    } else if (isPath(path, "/checkout/sessions")) {
      handleCheckoutSession(request, response);
    } else if (isPath(path, "/company-invitations")) {
      handleCompanyInvitationCreate(request, response);
    } else if (isPath(path, "/company-invitations/accept")) {
      handleCompanyInvitationAccept(request, response);
    } else if (isPath(path, "/company-invitations/register-and-accept")) {
      handleCompanyInvitationRegisterAndAccept(request, response);
    } else {
      return false;
    }
    return true;
  }

  @Override
  public void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if (isPath(path, PATH_SESSION)) {
      handleSessionDelete(request, response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_UNKNOWN_ENDPOINT + path);
    }
  }

  private void handleCheckoutSession(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedAccount(request, response, "checkout-session", account -> {
      JSONObject body = readJsonBodyOrBadRequest(request, response);
      if (body == null) return;
      String clientName = body.optString(FIELD_CLIENT_NAME, "").trim();
      if (clientName.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
            "clientName is required", "clientName is required");
        return;
      }
      String requestOrigin = request.getHeader("Origin");
      final String origin = StringUtils.isBlank(requestOrigin)
          ? PublicUrlResolver.resolveAppBaseUrl(request) : requestOrigin;
      try {
        JSONObject result = hostedCheckoutService.createSession(account.getEmail(), clientName, origin);
        writeResponse(response, HttpServletResponse.SC_CREATED, result);
      } catch (IllegalStateException e) {
        writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "CHECKOUT_NOT_CONFIGURED",
            "Checkout is not configured", "Checkout is not configured");
      } catch (Exception e) {
        log.error("Could not create hosted checkout session", e);
        writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "CHECKOUT_PROVIDER_ERROR",
            "Unable to create checkout session", "Unable to create checkout session");
      }
    });
  }

  private void handleCheckoutStatus(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String prefix = "/checkout/sessions/";
    String path = request.getPathInfo();
    String requestId = path != null && path.startsWith(prefix) ? path.substring(prefix.length()) : "";
    runWithAuthenticatedAccount(request, response, "checkout-status", account -> {
      CheckoutPaymentRegistry.Payment payment = CheckoutPaymentRegistry.find(requestId,
          account.getEmail());
      JSONObject result = new JSONObject();
      result.put("requestId", requestId);
      result.put(FIELD_STATUS, payment == null ? "pending" : "paid");
      if (payment != null) result.put(FIELD_CLIENT_NAME, payment.clientName);
      writeResponse(response, HttpServletResponse.SC_OK, result);
    });
  }

  private void handleCheckoutWebhook(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String payload = readRawBody(request);
    String signature = request.getHeader("Stripe-Signature");
    if (!CheckoutWebhookVerifier.verify(payload, signature, CheckoutConfiguration.webhookSecret(),
        Instant.now().getEpochSecond(), 300)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_CHECKOUT_SIGNATURE",
          "Invalid checkout webhook signature", "Invalid checkout webhook signature");
      return;
    }
    try {
      JSONObject event = new JSONObject(payload);
      String eventId = event.optString("id", "");
      if (!CheckoutPaymentRegistry.claimEvent(eventId)) {
        writeResponse(response, HttpServletResponse.SC_OK, new JSONObject().put("received", true));
        return;
      }
      String type = event.optString("type", "");
      if ("checkout.session.completed".equals(type)
          || "checkout.session.async_payment_succeeded".equals(type)) {
        JSONObject object = event.getJSONObject("data").getJSONObject("object");
        JSONObject metadata = object.optJSONObject("metadata");
        String requestId = metadata == null ? "" : metadata.optString("request_id", "");
        String email = metadata == null ? "" : metadata.optString("account_email", "");
        String clientName = metadata == null ? "" : metadata.optString("client_name", "");
        if (!StringUtils.isBlank(requestId) && !StringUtils.isBlank(email)) {
          CheckoutPaymentRegistry.recordPaid(requestId, email, clientName);
        }
      }
      writeResponse(response, HttpServletResponse.SC_OK, new JSONObject().put("received", true));
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_CHECKOUT_PAYLOAD",
          "Invalid checkout webhook payload", "Invalid checkout webhook payload");
    }
  }

  // --- Endpoint handlers ---

  /**
   * POST /sws/go/company-invitations
   * Header: Authorization: Bearer <inviter token>
   * Body: { "email": "recipient@example.com" }
   */
  private void handleCompanyInvitationCreate(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          INVALID_JSON_BODY, INVALID_JSON_BODY);
      return;
    }
    String email = body.optString(FIELD_EMAIL, "").trim();
    String language = body.optString(FIELD_LANGUAGE, "").trim();
    String requestOrigin = request.getHeader("Origin");
    String origin = StringUtils.isBlank(requestOrigin)
        ? PublicUrlResolver.resolveAppBaseUrl(request) : requestOrigin;
    runWithAuthenticatedAccount(request, response, "create company invitation", account -> {
      JSONObject result = companyInvitationService.createInvitation(account, email, origin, language);
      if (result.optBoolean(FIELD_ERROR, false)) {
        int httpStatus = result.optInt(FIELD_HTTP_STATUS, HttpServletResponse.SC_BAD_REQUEST);
        writeError(response, httpStatus, result.optString(FIELD_CODE, CODE_INVITATION_ERROR),
            result.optString(FIELD_MESSAGE, "Could not create invitation"),
            result.optString(FIELD_MESSAGE, "Could not create invitation"));
        return;
      }
      writeResponse(response, HttpServletResponse.SC_CREATED, result);
    });
  }

  /**
   * GET /sws/go/company-invitations/mine
   * Header: Authorization: Bearer <account session token>
   */
  private void handleCompanyInvitationMine(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    runWithAuthenticatedAccount(request, response, "list company invitations", account -> {
      JSONObject result = companyInvitationService.listInvitationsForAccount(account);
      if (result.optBoolean(FIELD_ERROR, false)) {
        int httpStatus = result.optInt(FIELD_HTTP_STATUS, HttpServletResponse.SC_UNAUTHORIZED);
        writeError(response, httpStatus, result.optString("code", "AUTHENTICATION_REQUIRED"),
            result.optString(FIELD_MESSAGE, "Authentication required"),
            result.optString(FIELD_MESSAGE, "Authentication required"));
        return;
      }
      writeResponse(response, HttpServletResponse.SC_OK, result);
    });
  }

  /**
   * GET /sws/go/company-invitations/resolve?token=<token>
   */
  private void handleCompanyInvitationResolve(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    String token = request.getParameter(FIELD_TOKEN);
    try {
      JSONObject result = companyInvitationService.resolveInvitation(token);
      if (result.optBoolean(FIELD_ERROR, false)) {
        int httpStatus = result.optInt(FIELD_HTTP_STATUS, HttpServletResponse.SC_BAD_REQUEST);
        writeError(response, httpStatus, result.optString(FIELD_CODE, CODE_INVITATION_ERROR),
            result.optString(FIELD_MESSAGE, "Could not resolve invitation"),
            result.optString(FIELD_MESSAGE, "Could not resolve invitation"));
        return;
      }
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (Exception e) {
      log.error("Error resolving company invitation", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, CODE_INTERNAL_ERROR,
          INTERNAL_ERROR, INTERNAL_ERROR);
    }
  }

  /**
   * POST /sws/go/company-invitations/accept
   * Body: { "token": "..." }
   */
  private void handleCompanyInvitationAccept(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          INVALID_JSON_BODY, INVALID_JSON_BODY);
      return;
    }
    String token = body.optString(FIELD_TOKEN, "").trim();
    // ETP-4576 — resolved through the same helper its siblings use
    // (`create`/`list` company invitations), which accepts a `__Host-` session
    // cookie OR a bearer header. This endpoint read `extractBearerToken`
    // directly, so it was the only one of the family that a cookie-session
    // caller could not authenticate against: after a cookie login the page holds
    // no bearer token at all, and accepting an invitation as an existing account
    // failed with no way for the client to fix it.
    runWithAuthenticatedAccount(request, response, "accept company invitation", account -> {
      JSONObject result = companyInvitationService.acceptExistingAccount(token, account);
      if (result.optBoolean(FIELD_ERROR, false)) {
        int httpStatus = result.optInt(FIELD_HTTP_STATUS, HttpServletResponse.SC_BAD_REQUEST);
        writeError(response, httpStatus, result.optString(FIELD_CODE, CODE_INVITATION_ERROR),
            result.optString(FIELD_MESSAGE, "Could not accept invitation"),
            result.optString(FIELD_MESSAGE, "Could not accept invitation"));
        return;
      }
      writeResponse(response, HttpServletResponse.SC_OK, result);
    });
  }

  /**
   * POST /sws/go/company-invitations/register-and-accept
   * Body: { "token": "...", "name": "...", "password": "..." }
   */
  private void handleCompanyInvitationRegisterAndAccept(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          INVALID_JSON_BODY, INVALID_JSON_BODY);
      return;
    }
    String token = body.optString(FIELD_TOKEN, "").trim();
    String name = body.optString("name", "").trim();
    String password = body.optString(FIELD_PASSWORD, "");
    try {
      JSONObject result = companyInvitationService.registerAndAccept(token, name, password);
      if (result.optBoolean(FIELD_ERROR, false)) {
        int httpStatus = result.optInt(FIELD_HTTP_STATUS, HttpServletResponse.SC_BAD_REQUEST);
        writeError(response, httpStatus, result.optString(FIELD_CODE, CODE_INVITATION_ERROR),
            result.optString(FIELD_MESSAGE, "Could not register and accept invitation"),
            result.optString(FIELD_MESSAGE, "Could not register and accept invitation"));
        return;
      }
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (Exception e) {
      log.error("Error registering and accepting company invitation", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, CODE_INTERNAL_ERROR,
          INTERNAL_ERROR, INTERNAL_ERROR);
    }
  }

  /**
   * POST /sws/go/register
   * Body: { "email": "...", "password": "...", "name": "...", "language": "es_ES" }
   * Returns 201 with session token on success, 400 if email is taken.
   */
  private void handleRegister(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    handleRegister(request, response, false);
  }

  private void handleSessionRegister(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    handleRegister(request, response, true);
  }

  private void handleRegister(HttpServletRequest request, HttpServletResponse response,
      boolean createCookieSession) throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          INVALID_JSON_BODY, INVALID_JSON_BODY);
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
      language = body.optString(FIELD_LANGUAGE, "").trim();
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_REGISTER_MISSING_FIELDS,
          "Missing required fields: email, password, name",
          "Missing required fields: email, password, name");
      return;
    }

    if (email.isEmpty() || password.isEmpty() || name.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_REGISTER_EMPTY_FIELDS,
          "Fields email, password, and name must not be empty",
          "Fields email, password, and name must not be empty");
      return;
    }
    // Defense in depth: reject anything that is not a well-formed email. This blocks control
    // characters and bare LIKE wildcards (e.g. "%") from ever reaching the account store, which
    // together with the escaped ownership LIKE keeps tenant isolation intact (ETP-4428).
    if (!EmailContractCommandSupport.isValidEmail(email)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_EMAIL_FORMAT,
          "Invalid email format", "Invalid email format");
      return;
    }
    // ETP-4665: the email later becomes AD_USER.USERNAME/NAME (60) during provisioning, so an
    // over-long address is only detected halfway through tenant creation. Reject it at signup.
    OnboardingFieldLimits.LengthViolation violation = OnboardingFieldLimits.firstViolation(
        FIELD_EMAIL, email, OnboardingFieldLimits.EMAIL,
        "name", name, OnboardingFieldLimits.ACCOUNT_NAME,
        FIELD_PASSWORD, password, OnboardingFieldLimits.PASSWORD);
    if (violation != null) {
      writeFieldTooLongError(response, violation);
      return;
    }
    if (!PasswordPolicy.isStrong(password)) {
      writeWeakPasswordError(response);
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      if (EtendoGoJwtDalHelper.findActiveAccountByEmail(email) != null) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_EMAIL_ALREADY_REGISTERED,
            "Email already registered", "Email already registered");
        return;
      }

      String passwordHash = hashPassword(password);
      String legacySessionToken = generateToken();
      Account account = EtendoGoJwtDalHelper.createAccount(email, passwordHash, name,
          legacySessionToken);
      String normalizedLanguage = StringUtils.trimToNull(language);
      sendAuthEmailBestEffort("new-account",
          () -> authEmailSender.sendNewAccount(account, normalizedLanguage));

      if (createCookieSession) {
        IssuedGoSession issued = goSessionService.create(account.getId(), FIELD_PASSWORD,
            request.getHeader(HEADER_USER_AGENT), null);
        writeSessionResponse(response, HttpServletResponse.SC_CREATED, account, issued);
      } else {
        JSONObject result = new JSONObject();
        result.put(FIELD_STATUS, STATUS_SUCCESS);
        result.put(FIELD_TOKEN, legacySessionToken);
        result.put(FIELD_ACCOUNT, buildAccountJson(account));
        writeResponse(response, HttpServletResponse.SC_CREATED, result);
      }
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("account registration", e, log);
      log.error("Database error during account registration", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, CODE_REGISTER_SERVER_ERROR,
          "Registration failed due to a server error", "Registration failed due to a server error");
    } catch (JSONException e) {
      log.error("JSON error building register response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, CODE_INTERNAL_ERROR,
          INTERNAL_ERROR, INTERNAL_ERROR);
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
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          INVALID_JSON_BODY, INVALID_JSON_BODY);
      return;
    }

    String email;
    String password;
    try {
      email = body.getString(FIELD_EMAIL).trim().toLowerCase();
      password = body.getString(FIELD_PASSWORD);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_LOGIN_MISSING_FIELDS,
          MISSING_EMAIL_PASSWORD, MISSING_EMAIL_PASSWORD);
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      Account account = EtendoGoJwtDalHelper.findActiveAccountByEmail(email);
      if (account == null || !EtendoGoJwtDalHelper.hasLocalPassword(account)
          || !verifyPassword(password, account.getPasswordHash())) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, CODE_INVALID_CREDENTIALS,
            INVALID_CREDENTIALS, INVALID_CREDENTIALS);
        return;
      }

      String sessionToken = generateToken();
      EtendoGoJwtDalHelper.updateSessionToken(account, sessionToken);

      JSONObject accountJson = buildAccountJson(account);

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_TOKEN, sessionToken);
      result.put(FIELD_ACCOUNT, accountJson);

      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("login", e, log);
      log.error("Database error during login", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, CODE_LOGIN_SERVER_ERROR,
          "Login failed due to a server error", "Login failed due to a server error");
    } catch (JSONException e) {
      log.error("JSON error building login response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, CODE_INTERNAL_ERROR,
          INTERNAL_ERROR, INTERNAL_ERROR);
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

      String sessionToken = generateToken();
      Account account = resolveSsoAccount(assertion, sessionToken, response);
      if (account == null) {
        return;
      }

      JSONObject accountJson = buildAccountJson(account);

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

  /**
   * Resolve (find, link, or create) the account for a verified SSO assertion under the current
   * admin context, storing {@code sessionToken} as its platform token. Writes a 409 and returns
   * {@code null} on a linking conflict. Shared by the legacy SSO login and the cookie SSO create.
   */
  private Account resolveSsoAccount(EtendoGoSsoAssertion assertion, String sessionToken,
      HttpServletResponse response) throws IOException {
    Account account = EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity(
        assertion.getProvider(), assertion.getSubject());
    if (account == null) {
      account = EtendoGoJwtDalHelper.findActiveAccountByEmail(assertion.getEmail());
      if (account != null) {
        if (!assertion.isEmailAuthoritative()) {
          writeError(response, HttpServletResponse.SC_CONFLICT,
              "Account requires explicit linking before SSO login");
          return null;
        }
        if (!EtendoGoJwtDalHelper.linkSsoIdentityIfCompatible(account,
            assertion.getProvider(), assertion.getSubject(), assertion.getEmail())) {
          writeError(response, HttpServletResponse.SC_CONFLICT,
              "Account is already linked to a different SSO identity");
          return null;
        }
      }
    }
    Date loginAt = new Date();
    if (account == null) {
      return EtendoGoJwtDalHelper.createSsoAccount(assertion.getEmail(), assertion.getName(),
          assertion.getProvider(), assertion.getSubject(), assertion.getEmail(), sessionToken,
          loginAt);
    }
    EtendoGoJwtDalHelper.updateSsoSession(account, assertion.getEmail(), sessionToken, loginAt);
    return account;
  }

  /**
   * POST /sws/go/session/sso/{provider}
   * SSO variant of session create: verifies the provider assertion, resolves the account and issues
   * the {@code __Host-} session + refresh cookies. The platform token is never returned to JS.
   */
  private void handleSessionCreateSso(String provider, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
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

      Account account = resolveSsoAccount(assertion, generateToken(), response);
      if (account == null) {
        return;
      }
      IssuedGoSession issued = goSessionService.create(account.getId(), "sso",
          request.getHeader(HEADER_USER_AGENT), null);
      writeSessionResponse(response, HttpServletResponse.SC_OK, account, issued);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("session SSO create", e, log);
      log.error("Database error during SSO session create", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Session creation failed due to a server error");
    } catch (JSONException e) {
      log.error("JSON error building SSO session response", e);
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
    if (!PasswordPolicy.isStrong(password)) {
      writeWeakPasswordError(response);
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
    if (!hasAnyCredential(request)) {
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
    if (!PasswordPolicy.isStrong(newPassword)) {
      writeWeakPasswordError(response);
      return;
    }

    try {
      AuthenticatedAccount authenticated = resolveAuthenticatedAccountContext(request, response);
      if (authenticated == null) {
        return;
      }
      Account account = authenticated.account;
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

      JSONObject accountJson = buildAccountJson(account);

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_ACCOUNT, accountJson);
      if (authenticated.sessionRecord != null) {
        IssuedGoSession rotated = goSessionService.rotate(authenticated.sessionRecord);
        if (rotated == null) {
          writeError(response, HttpServletResponse.SC_CONFLICT,
              "Session changed concurrently; restore and retry");
          return;
        }
        setSessionCookies(response, rotated);
        result.put(FIELD_CSRF_TOKEN, rotated.getCsrfToken());
      } else {
        result.put(FIELD_TOKEN, sessionToken);
      }
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
    if (!hasAnyCredential(request)) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_AUTHORIZATION_HEADER);
      return;
    }
    try {
      AuthenticatedAccount authenticated = resolveAuthenticatedAccountContext(request, response);
      if (authenticated == null) {
        return;
      }
      Account account = authenticated.account;

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
   * Resolve the platform account from the request's Bearer token under the
   * system admin context. Always enters admin mode (so callers can restore it
   * in their finally block) and writes the 401 response when the header is
   * missing or the token does not match an active account, returning null.
   */
  private Account resolveAuthenticatedAccount(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    AuthenticatedAccount authenticated = resolveAuthenticatedAccountContext(request, response);
    return authenticated == null ? null : authenticated.account;
  }

  /**
   * Whether the request carries any credential at all (a session cookie or a bearer header),
   * without touching {@code OBContext} or the DB — lets callers fail fast with 401 for a fully
   * unauthenticated request before ever entering admin mode.
   */
  private boolean hasAnyCredential(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (GoSessionSecurity.COOKIE_NAME.equals(cookie.getName())) {
          return true;
        }
      }
    }
    return extractBearerToken(request) != null;
  }

  private AuthenticatedAccount resolveAuthenticatedAccountContext(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    GoSessionAuthResult sessionAuth = new GoSessionAuthenticator(goSessionService).authenticate(request);
    if (sessionAuth.getStatus() == GoSessionAuthResult.Status.CSRF_FAILED) {
      writeError(response, HttpServletResponse.SC_FORBIDDEN, MSG_CSRF_VALIDATION_FAILED);
      return null;
    }
    if (sessionAuth.getStatus() == GoSessionAuthResult.Status.UNAUTHENTICATED) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
      return null;
    }
    if (sessionAuth.isAuthenticated()) {
      Account account = EtendoGoJwtDalHelper.findActiveAccountById(
          sessionAuth.getRecord().getAccountId());
      if (account == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return null;
      }
      return new AuthenticatedAccount(account, sessionAuth.getRecord());
    }

    String token = extractBearerToken(request);
    if (token == null || !GoLegacyBearer.isEnabled()) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_AUTHORIZATION_HEADER);
      return null;
    }
    GoLegacyBearer.recordUse();
    Account account = EtendoGoJwtDalHelper.findActiveAccountByToken(token);
    if (account == null) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
      return null;
    }
    return new AuthenticatedAccount(account, null);
  }

  private static final class AuthenticatedAccount {
    private final Account account;
    private final GoSessionRecord sessionRecord;

    private AuthenticatedAccount(Account account, GoSessionRecord sessionRecord) {
      this.account = account;
      this.sessionRecord = sessionRecord;
    }
  }

  @FunctionalInterface
  private interface AuthenticatedAccountAction {
    void execute(Account account) throws IOException, JSONException;
  }

  /**
   * Shared request template for the draft endpoints: resolves the account,
   * runs the action, and maps failures to the standard 500 responses with a
   * DAL rollback. Keeps the per-endpoint logic free of boilerplate.
   */
  private void runWithAuthenticatedAccount(HttpServletRequest request,
      HttpServletResponse response, String actionLabel, AuthenticatedAccountAction action)
      throws IOException {
    if (!hasAnyCredential(request)) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_AUTHORIZATION_HEADER);
      return;
    }
    try {
      Account account = resolveAuthenticatedAccount(request, response);
      if (account == null) {
        return;
      }
      action.execute(account);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges(actionLabel, e, log);
      log.error("Request '{}' failed", actionLabel, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error handling '{}'", actionLabel, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void writeSuccessStatus(HttpServletResponse response, JSONObject result)
      throws IOException, JSONException {
    result.put(FIELD_STATUS, STATUS_SUCCESS);
    writeResponse(response, HttpServletResponse.SC_OK, result);
  }

  /**
   * GET /sws/go/onboarding/draft
   * Header: Authorization: Bearer <session_token>
   * Returns 200 with { status, draft } where draft is the saved onboarding
   * wizard draft (object) or null when no draft is stored.
   */
  private void handleGetOnboardingDraft(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedAccount(request, response, "get onboarding draft", account -> {
      JSONObject result = new JSONObject();
      result.put(FIELD_DRAFT, parseStoredOnboardingDraft(account));
      writeSuccessStatus(response, result);
    });
  }

  /**
   * POST /sws/go/onboarding/draft
   * Header: Authorization: Bearer <session_token>
   * Body: { "draft": { "step": 1|2, "form": { ... } } } to save, { "draft": null } to clear.
   * Only whitelisted form fields are stored; the serialized draft is capped at 4000 chars.
   */
  private void handleSaveOnboardingDraft(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedAccount(request, response, "save onboarding draft", account -> {
      JSONObject body = readJsonBodyOrBadRequest(request, response);
      if (body == null) {
        return;
      }
      JSONObject draft = body.optJSONObject(FIELD_DRAFT);
      String storedDraft = null;
      if (draft != null) {
        storedDraft = sanitizeOnboardingDraft(draft).toString();
        if (storedDraft.length() > ONBOARDING_DRAFT_MAX_LENGTH) {
          writeError(response, HttpServletResponse.SC_BAD_REQUEST,
              "Onboarding draft is too large");
          return;
        }
      }
      // updateOnboardingDraft flushes and commits internally
      // (EtendoGoJwtDalHelper.flushAndCommitDalChanges) — no extra commit here.
      EtendoGoJwtDalHelper.updateOnboardingDraft(account, storedDraft);
      writeSuccessStatus(response, new JSONObject());
    });
  }

  /**
   * Read the JSON request body, writing a 400 response and returning null when
   * the payload is not valid JSON.
   */
  private JSONObject readJsonBodyOrBadRequest(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      return readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_JSON_BODY);
      return null;
    }
  }

  /**
   * Keep only known wizard fields so arbitrary client payloads are never persisted.
   */
  private JSONObject sanitizeOnboardingDraft(JSONObject draft) throws JSONException {
    JSONObject clean = new JSONObject();
    int step = draft.optInt(FIELD_DRAFT_STEP, 1);
    clean.put(FIELD_DRAFT_STEP, Math.min(Math.max(step, 1), 2));
    JSONObject cleanForm = new JSONObject();
    JSONObject form = draft.optJSONObject(FIELD_DRAFT_FORM);
    if (form != null) {
      for (String field : ONBOARDING_DRAFT_FORM_FIELDS) {
        Object value = form.opt(field);
        if (value instanceof String) {
          cleanForm.put(field, value);
        }
      }
    }
    clean.put(FIELD_DRAFT_FORM, cleanForm);
    return clean;
  }

  private Object parseStoredOnboardingDraft(Account account) {
    String storedDraft = EtendoGoJwtDalHelper.getOnboardingDraft(account);
    if (StringUtils.isBlank(storedDraft)) {
      return JSONObject.NULL;
    }
    try {
      return new JSONObject(storedDraft);
    } catch (JSONException e) {
      log.warn("Stored onboarding draft for account {} is not valid JSON; ignoring",
          account.getId());
      return JSONObject.NULL;
    }
  }

  private void clearOnboardingDraftBestEffort(Account account) {
    if (account == null) {
      return;
    }
    try {
      // updateOnboardingDraft flushes and commits internally
      // (EtendoGoJwtDalHelper.flushAndCommitDalChanges) — no extra commit here.
      EtendoGoJwtDalHelper.updateOnboardingDraft(account, null);
    } catch (RuntimeException e) {
      log.warn("Clearing onboarding draft failed without blocking onboarding", e);
    }
  }

  /**
   * GET /sws/go/environments
   * Header: Authorization: Bearer <session_token>
   * Returns 200 with environments linked to the account, each carrying its plan
   * ("free" | "productive"), plus the account email as the flag-targeting identity.
   * Links via AD_User.username matching the account email.
   */
  private void handleEnvironments(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!hasAnyCredential(request)) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_AUTHORIZATION_HEADER);
      return;
    }
    try {
      AuthenticatedAccount authenticated = resolveAuthenticatedAccountContext(request, response);
      if (authenticated == null) {
        return;
      }
      Account account = authenticated.account;

      org.codehaus.jettison.json.JSONArray envArray = new org.codehaus.jettison.json.JSONArray();
      List<User> environmentUsers = new ArrayList<>(
          EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail(account.getEmail()));
      // The first environment is entered automatically after account login. Prefer the paid
      // productive tenant so a demo tenant never unexpectedly becomes the active workspace when
      // an account owns both plans. The client repeats this ordering for older backends.
      environmentUsers.sort(Comparator
          .comparing((User user) -> TenantPlanService.PLAN_PRODUCTIVE
              .equals(tenantPlanService.resolvePlan(user.getClient().getId())))
          .reversed()
          .thenComparing(user -> StringUtils.defaultString(user.getClient().getName()),
              String.CASE_INSENSITIVE_ORDER));
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
      // The account email is the backend's feature-flag targeting key. Returned here (ETP-4686)
      // because the only account identity the web client persists is the ERP admin username of the
      // selected environment, which would bucket the same user differently once a targeting-aware
      // provider is wired up. Note this is necessary but NOT sufficient: the core's
      // fetchEnvironments helper drops top-level fields, and the client needs one identity at
      // bootstrap rather than per page. See docs/feature-flags-and-tenant-upgrade.md §1.
      result.put(FIELD_ACCOUNT_EMAIL, account.getEmail());
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
    if (token == null || !GoLegacyBearer.isEnabled()) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
          INVALID_AUTHORIZATION_HEADER);
      return;
    }
    GoLegacyBearer.recordUse();

    String userId = request.getParameter(FIELD_USER_ID);
    if (userId == null || userId.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing userId parameter");
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      Account account = EtendoGoJwtDalHelper.findActiveAccountByBearerToken(token);
      String accountEmail = account == null ? null : account.getEmail();
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
   * Body: { "clientName": "...", "currency": "EUR", "language": "es_ES", "countryCode": "ES",
   *         "paymentToken": "<checkout requestId returned by POST /sws/go/checkout/sessions>" }
   *
   * Creates a new Etendo environment (AD_Client + AD_Org) using the existing
   * InitialClientSetup and InitialOrgSetup business utilities.
   *
   * Streams NDJSON progress lines to the frontend.
   *
   * <p>An account that already owns an environment must supply an accepted {@code paymentToken} to
   * create an additional one, or to convert an existing one to productive; otherwise the request is
   * refused with HTTP 402 and {@code {"error":"payment_required"}} before any provisioning starts. A
   * first environment is always free. This is unconditional — the capability carries no feature flag
   * (ETP-4966). A payment the Stripe webhook confirmed is also what marks the resulting environment
   * productive. See {@code docs/feature-flags-and-tenant-upgrade.md}.
   */
  private void handleOnboarding(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!hasAnyCredential(request)) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_AUTHORIZATION_HEADER);
      return;
    }
    AuthenticatedAccount authenticated = null;
    try {
      authenticated = resolveAuthenticatedAccountContext(request, response);
    } catch (RuntimeException e) {
      log.error("Database error validating token for onboarding", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
    if (authenticated == null) {
      return;
    }
    String accountId = authenticated.account.getId();
    String accountEmail = authenticated.account.getEmail();

    OnboardingRequestData onboardingRequest = parseOnboardingRequest(request, response);
    if (onboardingRequest == null) {
      return;
    }

    String currencyId = resolveCurrencyId(onboardingRequest.currencyIso, response);
    if (currencyId == null) {
      return;
    }

    // Paywall (ETP-4686). Runs before the NDJSON stream opens and before any provisioning, so a
    // refused request leaves no half-created tenant behind and can still answer with a plain
    // JSON error instead of a stream. The backend is authoritative here: the /upgrade page in the
    // web client shows the checkout, but this check is what actually gates tenant creation.
    //
    // REFUSED means the paywall blocked the request (or evaluation failed) and already answered.
    PaywallGate paywallGate = resolvePaywallOutcome(accountEmail, onboardingRequest, response);
    if (paywallGate == PaywallGate.REFUSED) {
      return;
    }
    boolean paidUpgrade = paywallGate == PaywallGate.PAID;

    // Set up NDJSON streaming
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/x-ndjson");
    response.setCharacterEncoding(UTF_8);
    response.setHeader(HEADER_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);
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

      // Joins the onboarding transaction, so a successful marker commits with the tenant. Still
      // best-effort in the other direction: a tenant may commit unmarked rather than have
      // provisioning rolled back over a plan marker. What must never happen quietly is exactly
      // that case, so it is logged as an error naming the account — "paid but demo" is the
      // symptom ETP-4966 was reported as, and this line is what makes it searchable instead of
      // indistinguishable from a marker that was never attempted.
      if (paidUpgrade && !tenantPlanService.markProductive(clientId, adminContext.starOrgId)) {
        log.error("Paid environment '{}' (client {}) for account {} could not be marked as plan "
            + "'{}' and will read back as free", onboardingRequest.clientName, clientId,
            maskEmail(accountEmail), TenantPlanService.PLAN_PRODUCTIVE);
      }

      // The returned flag (created vs. already-existing) is no longer used to gate downstream
      // steps — the provisioning chain reconciles unconditionally (ETP-4428). A null return still
      // signals a failure that already emitted its own progress/result line.
      Boolean organizationResolved = ensureOrganization(writer, onboardingRequest.clientName, clientId,
          adminContext, currencyId);
      if (organizationResolved == null) {
        return;
      }

      String orgId = resolveOrganizationId(clientId);
      if (orgId == null) {
        sendProgress(writer, PROGRESS_DATASET, PROGRESS_ERROR,
            "Could not resolve organization for onboarding dataset import");
        sendFinalResult(writer, false, "Organization not found after onboarding");
        return;
      }

      if (!ensureOnboardingDataset(writer, clientId, orgId,
          adminContext.adminUserId, adminContext.adminRoleId, onboardingRequest)) {
        return;
      }

      EtendoGoDalHelper.commitDalChanges("onboarding", log);
      // Activate the bank statement-sync schedule now that its row is committed and therefore
      // visible to the scheduler's own DB connection. Best-effort: internally swallows failures
      // and the SCH row is still picked up on the next scheduler initialization.
      onboardingBankConnectionSyncService.activateSchedule(clientId);
      Account account = findAccountForCommittedOnboarding(accountId, accountEmail);
      clearOnboardingDraftBestEffort(account);
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
      warnIfOnboardingStreamLost(writer, accountEmail);
    }
  }

  /**
   * Outcome of the paywall gate. Three explicit states instead of a nullable {@code Boolean}: the
   * "keep going" answer still has to carry whether the account paid, and {@link #REFUSED} means the
   * error response was already written, so the caller must return without opening the stream.
   */
  private enum PaywallGate {
    PAID, FREE, REFUSED
  }

  /**
   * Paywall gate (ETP-4686). Runs before the NDJSON stream opens and before any provisioning, so a
   * refused request leaves no half-created tenant behind and can still answer with a plain JSON
   * error instead of a stream. The backend is authoritative here: the /upgrade page in the web
   * client shows the checkout, but this check is what actually gates tenant creation.
   *
   * @return {@link PaywallGate#PAID} or {@link PaywallGate#FREE} to continue, or
   *     {@link PaywallGate#REFUSED} when the request was blocked or evaluation failed — in which
   *     case the error response has already been written.
   */
  private PaywallGate resolvePaywallOutcome(String accountEmail,
      OnboardingRequestData onboardingRequest, HttpServletResponse response) throws IOException {
    try {
      TenantPaywallService.Outcome paywall = evaluatePaywall(accountEmail, onboardingRequest);
      if (paywall.getDecision().isBlocked()) {
        writePaymentRequiredError(response, paywall.getDecision());
        return PaywallGate.REFUSED;
      }
      return paywall.isProductive() ? PaywallGate.PAID : PaywallGate.FREE;
    } catch (RuntimeException e) {
      log.error("Paywall evaluation failed for onboarding", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
      return PaywallGate.REFUSED;
    }
  }

  /**
   * PrintWriter swallows IOExceptions (broken pipe): when CloudFront or any proxy hits its response
   * timeout it silently drops the client mid-stream while the backend keeps running to completion
   * (and commits). {@code checkError()} is the only way to detect it. Surface it explicitly so it
   * stops being invisible in the logs.
   */
  private void warnIfOnboardingStreamLost(PrintWriter writer, String accountEmail) {
    if (writer.checkError()) {
      log.warn("Onboarding stream to client was lost before the result line was delivered "
          + "(likely a CloudFront/proxy response timeout). The environment may have been "
          + "created successfully server-side, but the UI will report a false failure. "
          + "accountEmail={}", maskEmail(accountEmail));
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

  /**
   * Evaluates the paid-environment rules for an onboarding request (ETP-4686, ETP-4966).
   *
   * <p>Unconditional: the capability has no flag, so this runs for every onboarding request and the
   * backend is the only authority on both answers it produces. While it was gated, the browser
   * evaluated the flag through ConfigCat and the backend through local properties that were unset
   * everywhere — so the browser sold environments the backend then handed out for free.
   */
  private TenantPaywallService.Outcome evaluatePaywall(String accountEmail,
      OnboardingRequestData onboardingRequest) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      boolean ownsEnvironment = EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail(accountEmail) > 0;
      boolean resuming = isResumingOwnedTenant(onboardingRequest.clientName, accountEmail);
      boolean convertingDemo = "convert-demo".equalsIgnoreCase(onboardingRequest.upgradeAction);
      return tenantPaywallService.evaluate(ownsEnvironment, resuming, convertingDemo,
          onboardingRequest.paymentToken, accountEmail, onboardingRequest.clientName);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Tells a resume of an existing tenant from a request for a new one. A company name that already
   * resolves to a client this account owns is the retry path {@code validateExistingClient} handles
   * downstream — provisioning it again reconciles what is missing rather than creating a tenant, so
   * it must not be charged a second time.
   */
  private boolean isResumingOwnedTenant(String clientName, String accountEmail) {
    String existingClientId = EtendoGoJwtSupport.findClientIdByName(clientName);
    return existingClientId != null
        && EtendoGoJwtDalHelper.clientBelongsToAccountEmail(existingClientId, accountEmail);
  }

  private void writePaymentRequiredError(HttpServletResponse response,
      TenantPaywallService.Decision decision) throws IOException {
    String message = decision == TenantPaywallService.Decision.PAYMENT_DECLINED
        ? "The payment was declined. Use a different payment method and try again."
        : "Creating an additional environment requires a payment. Complete the checkout and retry.";
    JSONObject body = new JSONObject();
    try {
      body.put(FIELD_ERROR, ERROR_PAYMENT_REQUIRED);
      body.put(FIELD_MESSAGE, message);
    } catch (JSONException e) {
      log.error("Could not build the payment-required response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
      return;
    }
    writeResponse(response, SC_PAYMENT_REQUIRED, body);
  }

  private String resolveOnboardingAccountEmail(String token, HttpServletResponse response)
      throws IOException {
    String accountEmail = null;
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      Account account = EtendoGoJwtDalHelper.findActiveAccountByBearerToken(token);
      String resolvedEmail = account == null ? null : account.getEmail();
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
      result.put(FIELD_ROLE_LIST, roleListData.roleArray);
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
      data.language = body.optString(FIELD_LANGUAGE, "en_US").trim();
      // Country drives the org's tax resolution; default to Spain (ES) when the form omits it.
      data.countryCode = body.optString(FIELD_COUNTRY_CODE, "ES").trim();
      data.address = body.optString(FIELD_ADDRESS, "").trim();
      // Full name of the person onboarding. Optional in the payload; when present
      // it becomes the display name of the client admin user (otherwise Etendo's
      // InitialClientSetup leaves it as the username/email).
      data.fullName = body.optString(FIELD_FULL_NAME, "").trim();
      // Tax ID (ETP-4749): optional in the wizard, so a blank value here is expected and
      // must not fail the request — wireOrgInfo() only persists it when non-blank.
      data.taxId = body.optString("fiscalIdValue", "").trim();
      data.paymentToken = body.optString(FIELD_PAYMENT_TOKEN, "").trim();
      data.upgradeAction = body.optString("upgradeAction", "create-productive").trim();
      // ETP-4665: validate before the NDJSON stream opens. Past this point a length overflow
      // surfaces as a DAL ValidationException halfway through tenant creation, which rolls the
      // transaction back and reports the opaque "@CreateClientFailed@".
      OnboardingFieldLimits.LengthViolation violation = OnboardingFieldLimits.firstViolation(
          FIELD_CLIENT_NAME, data.clientName, OnboardingFieldLimits.CLIENT_NAME,
          FIELD_FULL_NAME, data.fullName, OnboardingFieldLimits.FULL_NAME,
          FIELD_ADDRESS, data.address, OnboardingFieldLimits.ADDRESS);
      if (violation != null) {
        writeFieldTooLongError(response, violation);
        return null;
      }
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

  /**
   * Masks an email for logging so no PII lands in the logs: keeps the first character of the local
   * part plus the domain (e.g. {@code r***@corp.com}). Null/blank/malformed inputs collapse to a
   * safe placeholder. Enough to correlate a lost-stream warning without recording the address.
   */
  static String maskEmail(String email) {
    String trimmed = StringUtils.trimToNull(email);
    if (trimmed == null) {
      return "(unknown)";
    }
    int at = trimmed.indexOf('@');
    if (at <= 0) {
      return trimmed.charAt(0) + "***";
    }
    return trimmed.charAt(0) + "***" + trimmed.substring(at);
  }

  private String resolveOrCreateClient(PrintWriter writer, VariablesSecureApp vars,
      String accountEmail, OnboardingRequestData requestData, String currencyId,
      String adminPassword) throws Exception {
    sendProgress(writer, PROGRESS_CLIENT, PROGRESS_IN_PROGRESS,
        "Creating client: " + requestData.clientName + "...");
    String clientId = EtendoGoJwtSupport.findClientIdByName(requestData.clientName);
    if (clientId != null) {
      return validateExistingClient(writer, requestData.clientName, clientId, accountEmail)
          ? clientId : null;
    }

    String clientUser = EtendoGoJwtSupport.buildClientUsername(accountEmail, requestData.clientName);
    if (!createClient(vars, currencyId, requestData.clientName, clientUser, adminPassword, writer)) {
      return null;
    }
    // InitialClientSetup names the admin AD_User after its username (the email).
    // Override it with the full name entered during onboarding so the app shows
    // the person's name instead of their email. No-op when fullName is blank.
    EtendoGoJwtSupport.applyClientAdminDisplayName(clientUser, requestData.fullName);
    return EtendoGoJwtSupport.findClientIdByName(requestData.clientName);
  }

  private boolean validateExistingClient(PrintWriter writer, String clientName,
      String clientId, String accountEmail) {
    // ETP-4428: an existing same-named client is resumable ONLY when it belongs to this account.
    // A previous partial onboarding leaves the client behind (with its org/role/user) but missing
    // downstream provisioning; re-entering it lets the idempotent chain reconcile what is missing.
    // A name collision with ANOTHER account's client must never be resumable (tenant isolation).
    if (!EtendoGoJwtDalHelper.clientBelongsToAccountEmail(clientId, accountEmail)) {
      sendProgress(writer, PROGRESS_CLIENT, PROGRESS_ERROR,
          "Company name '" + clientName + "' is already in use. Use a different name.");
      sendFinalResult(writer, false,
          "The company name '" + clientName + "' is already in use. Please choose a different company name.");
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
      // InitialClientSetup reports failures as UNRESOLVED AD message keys ("@CreateClientFailed@")
      // whose text says nothing about the actual cause — the real exception only reaches the
      // server log. Keep the raw value here for diagnostics and hand the client a stable code it
      // can localize (ETP-4665).
      String errorMsg = clientResult.getMessage() != null
          ? clientResult.getMessage()
          : "Client creation failed";
      log.error("Client creation failed for '{}': {}", clientName, errorMsg);
      sendProgress(writer, PROGRESS_CLIENT, PROGRESS_ERROR, errorMsg);
      sendFinalResult(writer, false, errorMsg, ERROR_CODE_CLIENT_CREATION_FAILED);
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
      // Same as createClient: InitialOrgSetup yields raw AD keys such as "@CreateOrgFailed@".
      String errorMsg = orgResult.getMessage() != null
          ? orgResult.getMessage()
          : "Organization creation failed";
      log.error("Organization creation failed for '{}': {}", clientName, errorMsg);
      sendProgress(writer, PROGRESS_ORGANIZATION, PROGRESS_ERROR, errorMsg);
      sendFinalResult(writer, false, errorMsg, ERROR_CODE_ORG_CREATION_FAILED);
      return false;
    }
    // ETP-4749: AD_Org.SocialName ("Nombre comercial" in the Organization settings window)
    // was never set anywhere in the onboarding flow — InitialOrgSetup/InitialSetupUtility
    // (Etendo core) only set Name/SearchKey. The wizard has no separate "trade name" field,
    // so reuse the same clientName already used for Name — it already resolves to the
    // user's Full Name for Freelancers (CompanyStep.jsx has no Company Name field for
    // that business type). A missing SocialName write here must not fail an otherwise
    // successful organization creation; log and move on.
    applySocialName(clientId, clientName);
    sendProgress(writer, PROGRESS_ORGANIZATION, "done", "Organization created successfully");
    return true;
  }

  /**
   * Sets {@code AD_Org.SocialName} from the onboarding {@code clientName}, once, right after
   * organization creation succeeds. Deliberately NOT part of {@link OnboardingOrgInfoService}'s
   * idempotent reconcile chain (which re-runs on every resumed/retried onboarding call): a
   * resumed tenant may already have had its "Nombre comercial" edited by hand in the
   * Organization settings window, and re-running this on every retry would silently overwrite
   * that edit. Organization creation itself only happens once (guarded by
   * {@code organizationExists()} in {@link #ensureOrganization}), so this call site shares the
   * same one-time guarantee.
   *
   * @return {@code true} when the organization was found and updated; {@code false} otherwise
   *     (logged, non-fatal — the organization itself was already created successfully).
   */
  boolean applySocialName(String clientId, String clientName) {
    Organization org = EtendoGoJwtDalHelper.findFirstOrganization(clientId);
    if (org == null) {
      log.warn("applySocialName: no organization found for client {} right after creation",
          clientId);
      return false;
    }
    org.setSocialName(clientName);
    OBDal.getInstance().save(org);
    OBDal.getInstance().flush();
    return true;
  }

  private String resolveOrganizationId(String clientId) {
    Organization organization = EtendoGoJwtDalHelper.findFirstOrganization(clientId);
    return organization != null ? organization.getId() : null;
  }

  /**
   * Runs the tenant-provisioning chain under a reconcile model (ETP-4428): every step is
   * idempotent or self-guarding, so the full chain runs unconditionally. On a retry after a
   * partial failure this repairs whatever is missing and no-ops what already exists. Previously
   * the dataset/accounting/period-control steps were gated on whether the organization had just
   * been created, which left a resumed tenant (client+org survive the rollback, dataset does not)
   * without seed data, ledger or fiscal periods.
   */
  boolean ensureOnboardingDataset(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId,
      OnboardingRequestData requestData) {
    if (!importOnboardingDataset(writer, clientId, orgId)) {
      return false;
    }
    if (!wireAccounting(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!wirePeriodControl(writer, clientId, orgId, adminUserId, adminRoleId)) {
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
    if (!ensureDefaultCustomer(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!scheduleBankConnectionSync(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    // ETP-4720: patch the 5 C_BP_Group_Acct columns neither the core c_bp_group_trg() trigger nor
    // OnboardingAccountingWiringService's own BP_GROUP_ACCT_SQL populate. Runs LAST among the
    // provisioning steps (right before the data-fix baseline) since it only needs C_BP_Group and
    // C_AcctSchema_Default, both already provisioned by step 1 -- see
    // OnboardingAccountingWiringService#patchBpGroupAcctMissingColumns for the full root-cause
    // explanation and its lockstep corrective twin (R21-bp-group-acct-remaining-columns.sql).
    if (!patchBpGroupAcctMissingColumns(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    // ETP-4854 (gap K1): force flat, per-dimension accounting-dimension visibility for the new
    // tenant. Runs AFTER the accounting-wiring steps (which created this client's
    // C_AcctSchema_Element rows, all defaulting isactive='Y') and BEFORE the baseline stamp — see
    // OnboardingAcctdimCentrallyMaintainedService for the full root-cause explanation and its
    // lockstep corrective twin (R23-acctdim-centrally-maintained.sql).
    if (!forceFlatAccountingDimensionVisibility(writer, clientId)) {
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
      String taxId = requestData != null ? requestData.taxId : null;
      onboardingOrgInfoService.ensureOrgInfo(clientId, orgId, adminUserId, adminRoleId,
          countryCode, address, taxId);
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
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_CUSTOMER, PROGRESS_IN_PROGRESS,
        "Creating default customer...");
    try {
      onboardingDefaultCustomerService.ensureDefaultCustomer(clientId, orgId, adminUserId,
          adminRoleId);
      // A2: provision the per-BP posting accounts now that the default customer exists. wireAccounting
      // ran earlier (before any business partner existed), so C_BP_CUSTOMER_ACCT would otherwise stay
      // empty. Idempotent (NOT-EXISTS-guarded), so it runs unconditionally under the reconcile
      // model (ETP-4428): the ledger it copies defaults from is guaranteed present because
      // wireAccounting ran earlier in the same chain.
      onboardingAccountingWiringService.wireBusinessPartnerAccounts(clientId, orgId, adminUserId,
          adminRoleId);
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
   * Patches any {@code C_BP_Group_Acct} row still missing one of the 5 columns that neither the
   * core {@code c_bp_group_trg()} trigger nor {@code OnboardingAccountingWiringService}'s own
   * {@code BP_GROUP_ACCT_SQL} populate (ETP-4720) — see
   * {@code OnboardingAccountingWiringService#patchBpGroupAcctMissingColumns} for the full
   * explanation and its corrective twin ({@code R21-bp-group-acct-remaining-columns.sql}).
   */
  boolean patchBpGroupAcctMissingColumns(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_BP_GROUP_ACCT_PATCH, PROGRESS_IN_PROGRESS,
        "Patching business-partner group posting accounts...");
    try {
      onboardingAccountingWiringService.patchBpGroupAcctMissingColumns(clientId, orgId,
          adminUserId, adminRoleId);
      sendProgress(writer, PROGRESS_BP_GROUP_ACCT_PATCH, "done",
          "Business-partner group posting accounts patched");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding bp-group-acct patch", e, log);
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Business-partner group posting-account patch failed";
      sendProgress(writer, PROGRESS_BP_GROUP_ACCT_PATCH, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  /**
   * Forces flat, per-dimension accounting-dimension visibility ({@code Acctdim_Centrally_Maintained
   * = 'N'}) for the new tenant, backfilling {@code C_AcctSchema_Element.isactive} first so the
   * flip does not change what the tenant would otherwise see (ETP-4854, gap K1) — see
   * {@link OnboardingAcctdimCentrallyMaintainedService} for the full explanation.
   */
  boolean forceFlatAccountingDimensionVisibility(PrintWriter writer, String clientId) {
    sendProgress(writer, PROGRESS_ACCTDIM_VISIBILITY, PROGRESS_IN_PROGRESS,
        "Configuring accounting-dimension visibility...");
    try {
      onboardingAcctdimCentrallyMaintainedService.forceFlatAccountingDimensionVisibility(clientId);
      sendProgress(writer, PROGRESS_ACCTDIM_VISIBILITY, "done",
          "Accounting-dimension visibility configured");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding acctdim-visibility", e, log);
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Accounting-dimension visibility configuration failed";
      sendProgress(writer, PROGRESS_ACCTDIM_VISIBILITY, PROGRESS_ERROR, errorMessage);
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
   * Creates the per-client daily bank statement-sync schedule, backed by the PSD2 module's
   * "Get Bank Statements" process (idempotent). Non-fatal: a
   * failure here must never block onboarding, so it is logged and reported as skipped rather than
   * aborting. The Quartz job is activated after the commit (see {@code handleOnboarding}); even if
   * activation does not run, the {@code SCH} row is picked up on the next scheduler initialization.
   */
  boolean scheduleBankConnectionSync(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_BANK_CONNECTION_SYNC, PROGRESS_IN_PROGRESS,
        "Scheduling automatic bank statement sync...");
    try {
      onboardingBankConnectionSyncService.scheduleBankConnectionStatementSync(clientId, orgId, adminUserId, adminRoleId);
      sendProgress(writer, PROGRESS_BANK_CONNECTION_SYNC, "done", "Automatic bank statement sync scheduled");
    } catch (Exception e) {
      log.warn("Could not schedule bank statement sync for client {}: {}", clientId, e.getMessage());
      sendProgress(writer, PROGRESS_BANK_CONNECTION_SYNC, "done", "Automatic bank statement sync skipped");
    }
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
    sendFinalResult(writer, success, message, null);
  }

  /**
   * Write the final NDJSON result line, tagged with a stable error code.
   *
   * <p>Provisioning failures carry unresolved Etendo AD message keys (e.g.
   * {@code @CreateClientFailed@}) that the UI cannot translate and must never display. The code
   * gives the client something stable to localize, while {@code message} stays in the payload for
   * non-UI callers and logs (ETP-4665).
   */
  void sendFinalResult(PrintWriter writer, boolean success, String message, String code) {
    try {
      JSONObject result = new JSONObject();
      result.put("type", "result");
      result.put(FIELD_SUCCESS, success);
      result.put(FIELD_MESSAGE, message);
      if (code != null) {
        result.put(FIELD_CODE, code);
      }
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
   *
   * @deprecated logic moved to {@link PasswordHasher#hash} (ETP-4829, so
   *     {@link EtendoGoAccountProvisioning} can hash admin-set passwords the same way without
   *     depending on this servlet); kept as a thin delegate so every existing call site here is
   *     unchanged.
   */
  @Deprecated
  private String hashPassword(String password) {
    return PasswordHasher.hash(password);
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
  private Account findAccountForCommittedOnboarding(String accountId, String accountEmail) {
    Account account = EtendoGoJwtDalHelper.findActiveAccountById(accountId);
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
   * POST /sws/go/session
   * Body: { "email": "...", "password": "..." }
   * Creates a backend-managed session: verifies the password, issues an opaque {@code __Host-}
   * session cookie and returns { status, account, csrfToken }. The session token is never returned
   * in the body (SEC-10). Legacy /login stays available during the migration window.
   */
  private void handleSessionCreate(HttpServletRequest request, HttpServletResponse response)
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
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, MISSING_EMAIL_PASSWORD);
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      Account account = EtendoGoJwtDalHelper.findActiveAccountByEmail(email);
      if (account == null || !EtendoGoJwtDalHelper.hasLocalPassword(account)
          || !verifyPassword(password, account.getPasswordHash())) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_CREDENTIALS);
        return;
      }

      IssuedGoSession issued = goSessionService.create(account.getId(), FIELD_PASSWORD,
          request.getHeader(HEADER_USER_AGENT), null);
      writeSessionResponse(response, HttpServletResponse.SC_OK, account, issued);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("session create", e, log);
      log.error("Database error during session create", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Session creation failed due to a server error");
    } catch (JSONException e) {
      log.error("JSON error building session response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * DELETE /sws/go/session
   * Invalidates the current session server-side and clears the cookie. Idempotent — always clears
   * the cookie even without a valid session. As an unsafe method it requires the CSRF proof.
   */
  private void handleSessionDelete(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      GoSessionAuthResult auth = new GoSessionAuthenticator(goSessionService).authenticate(request);
      if (auth.getStatus() == GoSessionAuthResult.Status.CSRF_FAILED) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, MSG_CSRF_VALIDATION_FAILED);
        return;
      }
      if (auth.isAuthenticated()) {
        goSessionService.revoke(auth.getRecord());
      }
      clearSessionCookies(response);
      response.setHeader(HEADER_CACHE_CONTROL, VALUE_NO_STORE);
      response.setHeader(HEADER_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("session delete", e, log);
      log.error("Database error during session delete", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Logout failed due to a server error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * POST /sws/go/session/environment
   * Body: { "userId": "..." }
   * Enters an environment: verifies the user belongs to the account, resolves the full context
   * (user/role/client/org/warehouse) and rotates the session with that context stored. Returns
   * { status, environment, roleList, csrfToken } plus a rotated cookie. Unsafe method → CSRF required.
   */
  private void handleSessionEnvironment(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_JSON_BODY);
      return;
    }
    String userId = body.optString(FIELD_USER_ID, "").trim();
    String requestedRoleId = body.optString("roleId", "").trim();
    String requestedOrgId = body.optString("orgId", "").trim();
    if (userId.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing userId");
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      GoSessionAuthResult auth = new GoSessionAuthenticator(goSessionService).authenticate(request);
      if (auth.getStatus() == GoSessionAuthResult.Status.CSRF_FAILED) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, MSG_CSRF_VALIDATION_FAILED);
        return;
      }
      if (!auth.isAuthenticated()) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }
      GoSessionRecord sessionRecord = auth.getRecord();

      Account account = EtendoGoJwtDalHelper.findActiveAccountById(sessionRecord.getAccountId());
      if (account == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }
      if (!EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(account.getEmail(), userId)) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN,
            "User does not belong to this account");
        return;
      }

      User user = OBDal.getInstance().get(User.class, userId);
      if (user == null) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, "User not found");
        return;
      }
      EtendoGoJwtSupport.RoleListData roleListData = EtendoGoJwtSupport.loadRoleListData(userId);
      Role role = resolveRequestedRole(roleListData, requestedRoleId, requestedOrgId, response);
      if (role == null) {
        return;
      }

      // Reuse the platform's context derivation: generate the environment JWT and read its claims,
      // so the session stores exactly the user/role/client/org/warehouse the JWT layer would.
      DecodedJWT context = SecureWebServicesUtils.decodeToken(
          SecureWebServicesUtils.generateToken(user, role));
      sessionRecord.setUserId(context.getClaim("user").asString());
      sessionRecord.setRoleId(context.getClaim("role").asString());
      sessionRecord.setCtxClientId(context.getClaim(PROGRESS_CLIENT).asString());
      String generatedOrgId = context.getClaim(PROGRESS_ORGANIZATION).asString();
      sessionRecord.setCtxOrgId(requestedOrgId.isEmpty() ? generatedOrgId : requestedOrgId);
      sessionRecord.setWarehouseId(resolveWarehouseId(requestedOrgId, generatedOrgId, context));

      IssuedGoSession rotated = goSessionService.rotate(sessionRecord);
      if (rotated == null) {
        writeError(response, HttpServletResponse.SC_CONFLICT,
            "Session changed concurrently; restore and retry");
        return;
      }

      setSessionCookies(response, rotated);
      response.setHeader(HEADER_CACHE_CONTROL, VALUE_NO_STORE);
      response.setHeader(HEADER_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put("environment", buildSessionEnvironment(rotated.getRecord()));
      result.put(FIELD_ROLE_LIST, roleListData.roleArray);
      result.put(FIELD_CSRF_TOKEN, rotated.getCsrfToken());
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("session environment", e, log);
      log.error("Database error during environment switch", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error during environment switch", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } catch (Exception e) {
      log.error("Token generation error during environment switch", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Environment switch failed");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static JSONObject findRole(JSONArray roleList, String roleId) throws JSONException {
    if (roleList == null || roleId == null) {
      return null;
    }
    for (int i = 0; i < roleList.length(); i++) {
      JSONObject role = roleList.getJSONObject(i);
      if (roleId.equals(role.optString("id"))) {
        return role;
      }
    }
    return null;
  }

  private static boolean roleContainsOrganization(JSONObject role, String orgId)
      throws JSONException {
    JSONArray organizations = role.optJSONArray("orgList");
    if (organizations == null) {
      return false;
    }
    for (int i = 0; i < organizations.length(); i++) {
      if (orgId.equals(organizations.getJSONObject(i).optString("id"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolve and validate the requested role (and, if given, organization) for an environment
   * switch: defaults to the user's first role when none is requested, checks the role is one of
   * the user's own, and that the requested organization (if any) belongs to that role. Writes the
   * matching error response and returns {@code null} when the request is invalid.
   */
  private Role resolveRequestedRole(EtendoGoJwtSupport.RoleListData roleListData,
      String requestedRoleId, String requestedOrgId, HttpServletResponse response)
      throws IOException, JSONException {
    String roleId = requestedRoleId.isEmpty() ? roleListData.firstRoleId : requestedRoleId;
    JSONObject selectedRole = findRole(roleListData.roleArray, roleId);
    if (roleId == null || selectedRole == null) {
      writeError(response, HttpServletResponse.SC_FORBIDDEN,
          "Requested role is not available to this user");
      return null;
    }
    if (!requestedOrgId.isEmpty() && !roleContainsOrganization(selectedRole, requestedOrgId)) {
      writeError(response, HttpServletResponse.SC_FORBIDDEN,
          "Requested organization is not available to this role");
      return null;
    }
    Role role = OBDal.getInstance().get(Role.class, roleId);
    if (role == null) {
      writeError(response, HttpServletResponse.SC_FORBIDDEN,
          "Requested role is not available to this user");
    }
    return role;
  }

  /**
   * Resolve the session's warehouse: the JWT-generated default when no organization was explicitly
   * requested (or it matches the default), otherwise an active warehouse under the requested
   * organization.
   */
  private static String resolveWarehouseId(String requestedOrgId, String generatedOrgId,
      DecodedJWT context) {
    if (requestedOrgId.isEmpty() || requestedOrgId.equals(generatedOrgId)) {
      return context.getClaim("warehouse").asString();
    }
    return findWarehouseForOrganization(requestedOrgId);
  }

  /**
   * Resolve an active warehouse under the given organization, for when an explicit environment
   * switch selects an organization other than the one the JWT context derivation would default to.
   *
   * @return the warehouse id, or {@code null} if the organization has no active warehouse
   */
  private static String findWarehouseForOrganization(String orgId) {
    Organization organization = OBDal.getInstance().get(Organization.class, orgId);
    if (organization == null) {
      return null;
    }
    OBCriteria<Warehouse> criteria = OBDal.getInstance().createCriteria(Warehouse.class);
    criteria.add(Restrictions.eq(Warehouse.PROPERTY_ORGANIZATION, organization));
    criteria.add(Restrictions.eq(Warehouse.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    Warehouse warehouse = (Warehouse) criteria.uniqueResult();
    return warehouse == null ? null : warehouse.getId();
  }

  /**
   * GET /sws/go/session
   * Restores the account and current environment context from the session cookie. Safe method — no
   * CSRF required. Returns { status, account, environment|null, csrfToken }; 401 when there is no
   * live session.
   */
  private void handleSessionRestore(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      GoSessionAuthResult auth = new GoSessionAuthenticator(goSessionService).authenticate(request);
      if (!auth.isAuthenticated()) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }
      GoSessionRecord sessionRecord = auth.getRecord();
      Account account = EtendoGoJwtDalHelper.findActiveAccountById(sessionRecord.getAccountId());
      if (account == null) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }

      JSONObject accountJson = buildAccountJson(account);

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_ACCOUNT, accountJson);
      result.put("environment", buildSessionEnvironment(sessionRecord));
      result.put(FIELD_ROLE_LIST, loadSessionRoleList(sessionRecord));
      result.put(FIELD_CSRF_TOKEN, sessionRecord.getCsrfToken());

      response.setHeader(HEADER_CACHE_CONTROL, VALUE_NO_STORE);
      response.setHeader(HEADER_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      log.error("Database error during session restore", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error during session restore", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Build the environment block of a restore response: the selected {@code user/role/client/org/
   * warehouse}, or {@code null} when no environment has been entered yet on this session.
   */
  private static Object buildSessionEnvironment(GoSessionRecord sessionRecord) throws JSONException {
    if (sessionRecord.getUserId() == null) {
      return JSONObject.NULL;
    }
    JSONObject env = new JSONObject();
    env.put(FIELD_USER_ID, sessionRecord.getUserId());
    env.put("roleId", sessionRecord.getRoleId());
    env.put("clientId", sessionRecord.getCtxClientId());
    env.put("orgId", sessionRecord.getCtxOrgId());
    env.put("warehouseId", sessionRecord.getWarehouseId());
    return env;
  }

  private static JSONArray loadSessionRoleList(GoSessionRecord sessionRecord) throws JSONException {
    if (sessionRecord.getUserId() == null) {
      return new JSONArray();
    }
    return EtendoGoJwtSupport.loadRoleListData(sessionRecord.getUserId()).roleArray;
  }

  /**
   * POST /sws/go/session/refresh
   * Rotates the session from the one-time refresh cookie and issues fresh session + refresh cookies.
   * Protected by same-origin ({@code SameSite=Lax} + {@code Origin}) rather than a CSRF token, since
   * the session may already be expired when refresh runs. A replayed/expired refresh clears the
   * cookies and returns 401.
   */
  private void handleSessionRefresh(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);

      if (!GoSessionSecurity.isOriginAllowed(request)) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, MSG_CSRF_VALIDATION_FAILED);
        return;
      }
      String rawRefresh = extractRefreshToken(request);
      IssuedGoSession rotated = rawRefresh == null ? null : goSessionService.refresh(rawRefresh);
      if (rotated == null) {
        clearSessionCookies(response);
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_OR_EXPIRED_TOKEN);
        return;
      }

      setSessionCookies(response, rotated);
      response.setHeader(HEADER_CACHE_CONTROL, VALUE_NO_STORE);
      response.setHeader(HEADER_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_CSRF_TOKEN, rotated.getCsrfToken());
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("session refresh", e, log);
      log.error("Database error during session refresh", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error during session refresh", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void setSessionCookies(HttpServletResponse response, IssuedGoSession issued) {
    response.addHeader(HEADER_SET_COOKIE, GoSessionSecurity.buildSessionCookie(issued.getSessionToken()));
    response.addHeader(HEADER_SET_COOKIE, GoSessionSecurity.buildRefreshCookie(issued.getRefreshToken()));
  }

  private void clearSessionCookies(HttpServletResponse response) {
    response.addHeader(HEADER_SET_COOKIE, GoSessionSecurity.buildExpiredSessionCookie());
    response.addHeader(HEADER_SET_COOKIE, GoSessionSecurity.buildExpiredRefreshCookie());
  }

  private static String extractRefreshToken(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (GoSessionSecurity.REFRESH_COOKIE_NAME.equals(cookie.getName())) {
        return StringUtils.trimToNull(cookie.getValue());
      }
    }
    return null;
  }

  /**
   * Build the standard {@code {id, email, name}} JSON projection of an account, shared by every
   * endpoint that returns account data.
   */
  private static JSONObject buildAccountJson(Account account) throws JSONException {
    JSONObject accountJson = new JSONObject();
    accountJson.put("id", account.getId());
    accountJson.put(FIELD_EMAIL, account.getEmail());
    accountJson.put("name", account.getName());
    return accountJson;
  }

  /**
   * Write a session response: sets the opaque {@code __Host-} cookie plus {@code no-store} and
   * {@code nosniff} headers, and returns { status, account, csrfToken }. The session token itself
   * is never placed in the body.
   */
  private void writeSessionResponse(HttpServletResponse response, int status, Account account,
      IssuedGoSession issued) throws IOException, JSONException {
    setSessionCookies(response, issued);
    response.setHeader(HEADER_CACHE_CONTROL, VALUE_NO_STORE);
    response.setHeader(HEADER_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);

    JSONObject accountJson = buildAccountJson(account);

    JSONObject result = new JSONObject();
    result.put(FIELD_STATUS, STATUS_SUCCESS);
    result.put(FIELD_ACCOUNT, accountJson);
    result.put(FIELD_CSRF_TOKEN, issued.getCsrfToken());
    writeResponse(response, status, result);
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

  /**
   * Write a stable, machine-readable register/login error envelope: {@code
   * { "error": { "code": "...", "message": "...", "userMessage": "...", "status": N } } }.
   *
   * ETP-4664 — lets the frontend translate the error by {@code code} instead of
   * showing the raw (English) {@code message}/{@code userMessage} text.
   */
  private void writeError(HttpServletResponse response, int status, String code, String message,
      String userMessage) throws IOException {
    try {
      JSONObject error = new JSONObject();
      error.put(FIELD_CODE, code);
      error.put(FIELD_MESSAGE, message);
      error.put(FIELD_USER_MESSAGE, userMessage);
      error.put(FIELD_STATUS, status);
      JSONObject envelope = new JSONObject();
      envelope.put(PROGRESS_ERROR, error);
      writeResponse(response, status, envelope);
    } catch (JSONException e) {
      log.error("JSON error building error response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    }
  }

  /**
   * Write the weak-password rejection as HTTP 400 with a stable, machine-readable
   * envelope: {@code { "error": { "code": "WEAK_PASSWORD", "message": "...",
   * "userMessage": "..." } } }.
   */
  private void writeWeakPasswordError(HttpServletResponse response) throws IOException {
    try {
      JSONObject error = new JSONObject();
      error.put(FIELD_CODE, PasswordPolicy.ERROR_CODE);
      error.put(FIELD_MESSAGE, PasswordPolicy.MESSAGE);
      error.put(FIELD_USER_MESSAGE, PasswordPolicy.USER_MESSAGE);
      JSONObject envelope = new JSONObject();
      envelope.put(PROGRESS_ERROR, error);
      writeResponse(response, HttpServletResponse.SC_BAD_REQUEST, envelope);
    } catch (JSONException e) {
      log.error("JSON error building weak-password response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    }
  }

  /**
   * Write a length rejection as HTTP 400 with the same machine-readable envelope used by
   * {@link #writeWeakPasswordError}: {@code { "error": { "code": "FIELD_TOO_LONG", "field": "...",
   * "max": 60, "message": "..." } } }. The client localizes it from the code and {@code max}; the
   * English {@code message} is only a fallback for non-UI callers (ETP-4665).
   */
  private void writeFieldTooLongError(HttpServletResponse response,
      OnboardingFieldLimits.LengthViolation violation) throws IOException {
    try {
      JSONObject error = new JSONObject();
      error.put(FIELD_CODE, OnboardingFieldLimits.ERROR_CODE);
      error.put("field", violation.field());
      error.put("max", violation.max());
      error.put(FIELD_MESSAGE,
          String.format("Field %s must not exceed %d characters", violation.field(),
              violation.max()));
      JSONObject envelope = new JSONObject();
      envelope.put(PROGRESS_ERROR, error);
      writeResponse(response, HttpServletResponse.SC_BAD_REQUEST, envelope);
    } catch (JSONException e) {
      log.error("JSON error building field-too-long response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    }
  }

  private static class OnboardingRequestData {
    private String clientName;
    private String currencyIso;
    private String language;
    private String countryCode;
    private String address;
    private String fullName;
    // Optional Tax ID from the wizard's "Details to start invoicing" step (ETP-4749).
    // Same JSON key as ONBOARDING_DRAFT_FORM_FIELDS ("fiscalIdValue") for consistency.
    private String taxId;
    // Present only when the paid environment flow issued one (ETP-4686). Correlated against
    // CheckoutPaymentRegistry: a token the Stripe webhook confirmed is what makes the resulting
    // environment productive (ETP-4966).
    private String paymentToken;
    private String upgradeAction;
  }

  private static class AdminContextData {
    private String adminRoleId;
    private String adminUserId;
    private String starOrgId;
  }
}
