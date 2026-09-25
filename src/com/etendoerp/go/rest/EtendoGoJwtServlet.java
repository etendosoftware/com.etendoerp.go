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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
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
import com.etendoerp.go.common.JwtAuthUtils;
import com.etendoerp.go.common.ProtocolErrorAdapters;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantPaywallService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.payment.HostedCheckoutService;
import com.etendoerp.go.payment.CheckoutConfiguration;
import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.BillingOfferConfiguration;
import com.etendoerp.go.payment.StripePriceService;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.SubscriptionEventOutcome;
import com.etendoerp.go.payment.SubscriptionLifecycleApplier;
import com.etendoerp.go.payment.StripeCustomerPortalService;
import com.etendoerp.go.payment.DemoDataTransferService;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.payment.CheckoutWebhookProcessor;
import com.etendoerp.go.onboarding.OnboardingAcctdimCentrallyMaintainedService;
import com.etendoerp.go.onboarding.OnboardingAdminIdentityService;
import com.etendoerp.go.onboarding.OnboardingBaselineService;
import com.etendoerp.go.onboarding.OnboardingAccountingWiringService;
import com.etendoerp.go.onboarding.OnboardingDatasetImportService;
import com.etendoerp.go.onboarding.OnboardingForceTestModeService;
import com.etendoerp.go.onboarding.OnboardingFiscalDataSetupService;
import com.etendoerp.go.onboarding.OnboardingOrgInfoService;
import com.etendoerp.go.onboarding.OnboardingMarkOrgReadyService;
import com.etendoerp.go.onboarding.OnboardingPeriodControlService;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService;
import com.etendoerp.go.onboarding.OnboardingWarehouseAddressService;
import com.etendoerp.go.common.SpanishTaxIdValidator;
import com.etendoerp.go.onboarding.OnboardingCompanyDataService;
import com.etendoerp.go.onboarding.OnboardingCompanyProfileTransferService;
import com.etendoerp.go.onboarding.OnboardingSequenceGeneratorService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.AccountIdentity;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoLegacyBearer;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.IssuedGoSession;
import com.etendoerp.go.session.JdbcGoSessionStore;
import com.etendoerp.go.session.SessionRoleRevokedException;
import com.etendoerp.go.schemaforge.util.OwnerSupport;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.usageevents.SessionLoginUsage;
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
 *   GET  /sws/go/billing/overview — Account-level purchase projection (requires session token)
 *   GET  /sws/go/billing/offers — Server-owned billing offer projection
 *   POST /sws/go/billing/purchases — Start a new owner-authorized purchase
 *   GET  /sws/go/billing/purchases/{id} — Read one account-scoped purchase projection
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
  private final StripePriceService stripePriceService;

  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int SALT_BYTES = 16;
  // Heartbeat cadence for the onboarding NDJSON stream. Must stay well below the
  // CloudFront/proxy origin-response (inter-byte) timeout — default 30s — so a slow
  // step never leaves the connection idle long enough to be dropped mid-stream.
  private static final int ONBOARDING_HEARTBEAT_SECONDS = 10;
  private static final String UTF_8 = "UTF-8";
  private static final String FIELD_EMAIL = "email";
  private static final String FIELD_CLIENT_NAME = "clientName";
  private static final String FIELD_DEMO_CLIENT_ID = "demoClientId";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_HTTP_STATUS = "httpStatus";
  private static final String FIELD_TOKEN = "token";
  private static final String FIELD_REQUEST_ID = "requestId";
  private static final String FIELD_DATA_TRANSFER = "dataTransfer";
  private static final String CHECKOUT_SELECTION_ERROR = "CHECKOUT_SELECTION_ERROR";
  private static final String CHECKOUT_SELECTION_SAVE_ERROR = "Unable to save transfer selection";
  private static final String CHECKOUT_SELECTION_PERSIST_ERROR =
      "Could not persist checkout transfer selection";
  private static final String BILLING_PROVIDER_ERROR = "BILLING_PROVIDER_ERROR";
  private static final String BILLING_SELECTION_PERSIST_ERROR =
      "Could not persist billing transfer selection";
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
  private static final String FIELD_CURRENCY = "currency";
  private static final String HEADER_ORIGIN = "Origin";
  private static final String BILLING_OWNER_REQUIRED = "BILLING_OWNER_REQUIRED";
  private static final String BILLING_OWNER_MESSAGE = "Only the environment owner can manage billing";
  private static final String CLIENT_NAME_REQUIRED = "clientName is required";
  private static final String CHECKOUT_NOT_CONFIGURED = "CHECKOUT_NOT_CONFIGURED";
  private static final String CHECKOUT_NOT_CONFIGURED_MESSAGE = "Checkout is not configured";
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
  // ETP-4798 — email ownership confirmation. Stable codes, mirrored by the web client's
  // onboarding/errorMessages.js so it translates by code and never shows this English text.
  private static final String CODE_EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
  private static final String CODE_EMAIL_VERIFY_INVALID = "EMAIL_VERIFY_INVALID";
  // AUTH-07 / ETP-5022 — change-password failures. Stable codes so the web client translates
  // by code; the English text below is a developer-facing fallback, never end-user copy.
  private static final String CODE_MISSING_CREDENTIALS = "CHANGE_PASSWORD_MISSING_CREDENTIALS";
  private static final String CODE_INVALID_CURRENT_PASSWORD = "INVALID_CURRENT_PASSWORD";
  private static final String CODE_METHOD_NOT_FOUND = "AUTH_METHOD_NOT_FOUND";
  private static final String CODE_LAST_AUTH_METHOD = "LAST_AUTH_METHOD";
  private static final String METHOD_PASSWORD = "password";
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
  private static final String PROGRESS_ORG_INFO = "orgInfo";
  private static final String PROGRESS_WAREHOUSE_ADDRESS = "warehouseAddress";
  private static final String PROGRESS_BASELINE = "baseline";
  private static final String PROGRESS_COSTING_SCHEDULE = "costingSchedule";
  private static final String PROGRESS_BP_GROUP_ACCT_PATCH = "bpGroupAcctPatch";
  private static final String PROGRESS_ACCTDIM_VISIBILITY = "acctdimVisibility";
  private static final String PROGRESS_ADMIN_IDENTITY = "adminIdentity";
  private static final String PROGRESS_FORCE_TEST_MODE = "forceTestMode";
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
  // 24h, not the 30 minutes a password reset gets. A reset is a deliberate act the user performs
  // and immediately waits on; a registration confirmation is often opened the next morning, and an
  // expired link there means a dead end in the middle of signup.
  private static final long EMAIL_VERIFICATION_TTL_SECONDS = 24 * 60 * 60L;
  private static final String EMAIL_NOT_VERIFIED_MESSAGE =
      "Confirm your email address before creating an environment.";
  private static final String EMAIL_VERIFY_INVALID_MESSAGE =
      "Invalid or expired email verification token";
  private static final String EMAIL_VERIFY_NEUTRAL_MESSAGE =
      "If this account still needs an email confirmation, a new link has been sent.";
  private static final String PATH_VERIFY_EMAIL = "/verify-email";
  private static final String PATH_VERIFY_EMAIL_RESEND = "/verify-email/resend";
  private static final String FIELD_EMAIL_VERIFIED = "emailVerified";
  private static final String FIELD_EMAIL_VERIFICATION_PENDING = "emailVerificationPending";
  private static final String CONTRACT_NEW_ACCOUNT = "new-account";
  private static final String SSO_PREFIX = "/sso/";
  private static final String PATH_ONBOARDING_DRAFT = "/onboarding/draft";
  /** Maximum accepted age of a Stripe-Signature timestamp, per the provider's guidance. */
  private static final long CHECKOUT_WEBHOOK_TOLERANCE_SECONDS = 300;
  /** The event types that confirm a hosted-checkout payment; every other type is ignored. */
  private static final List<String> CHECKOUT_PAID_EVENT_TYPES = List.of(
      "checkout.session.completed", "checkout.session.async_payment_succeeded");
  private static final List<String> SUBSCRIPTION_EVENT_TYPES = List.of(
      "invoice.payment_failed", "invoice.paid", "customer.subscription.updated",
      "customer.subscription.deleted");
  private static final String FIELD_DRAFT = "draft";
  private static final String FIELD_DRAFT_STEP = "step";
  private static final String FIELD_DRAFT_FORM = "form";
  private static final String FIELD_COUNTRY_CODE = "countryCode";
  private static final int ONBOARDING_DRAFT_MAX_LENGTH = 4000;
  private static final String FIELD_FULL_NAME = "fullName";
  private static final String FIELD_ADDRESS = "address";
  private static final String FIELD_PRODUCTS = "products";
  private static final String FIELD_CONTACTS = "contacts";
  private static final String[] ONBOARDING_DRAFT_FORM_FIELDS = { FIELD_FULL_NAME, "businessType",
      FIELD_CLIENT_NAME, FIELD_CURRENCY, FIELD_LANGUAGE, FIELD_COUNTRY_CODE, "fiscalIdType",
      "fiscalIdValue", FIELD_ADDRESS, "sector" };
  private static final String PATH_ONBOARDING_FIRST_STEPS = "/onboarding/first-steps";
  private static final String FIELD_FIRST_STEPS = "firstSteps";
  private static final String FIELD_FIRST_STEPS_VERSION = "v";
  private static final String FIELD_FIRST_STEPS_SEEN = "seen";
  private static final String FIELD_FIRST_STEPS_COMPLETED = "completed";
  /**
   * ETP-5364 — the user closed the checklist for good, so the sidebar must stop offering it.
   * Independent of {@code seen} (which only spends the one-time post-signup redirect) and of
   * {@code completed} being full: a tenant can finish every step and still want the entry, and
   * the flag is reversible from the page itself.
   */
  private static final String FIELD_FIRST_STEPS_DISMISSED = "dismissed";
  private static final int FIRST_STEPS_VERSION = 1;
  private static final int FIRST_STEPS_MAX_LENGTH = 1000;
  /**
   * Allowlist of First Steps checklist ids that may be persisted, in the order they are stored.
   * Kept in the same order the checklist renders (see {@code firstStepsConfig.js}) so a stored
   * value reads the way the user saw it; the frontend only ever tests membership, so the order
   * itself is cosmetic. {@code create-account} is deliberately absent — it is implicit, the
   * account already exists — and a client sending it gets it dropped rather than rejected.
   */
  private static final String[] FIRST_STEPS_IDS = { "company-data", "fiscal-config", FIELD_PRODUCTS,
      FIELD_CONTACTS, "invoice-sequence", "team" };
  private static final String PATH_ONBOARDING_COMPANY_DATA = "/onboarding/company-data";
  private static final String FIELD_COMPANY_DATA = "companyData";

  OnboardingDatasetImportService onboardingDatasetImportService = new OnboardingDatasetImportService();
  OnboardingCompanyDataService onboardingCompanyDataService = new OnboardingCompanyDataService();
  OnboardingCompanyProfileTransferService onboardingCompanyProfileTransferService =
      new OnboardingCompanyProfileTransferService();
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
  OnboardingWarehouseAddressService onboardingWarehouseAddressService =
      new OnboardingWarehouseAddressService();
  OnboardingAcctdimCentrallyMaintainedService onboardingAcctdimCentrallyMaintainedService =
      new OnboardingAcctdimCentrallyMaintainedService();
  OnboardingAdminIdentityService onboardingAdminIdentityService =
      new OnboardingAdminIdentityService();
  OnboardingBaselineService onboardingBaselineService =
      new OnboardingBaselineService();
  OnboardingForceTestModeService onboardingForceTestModeService =
      new OnboardingForceTestModeService();
  OnboardingCostingScheduleService onboardingCostingScheduleService =
      new OnboardingCostingScheduleService();
  TenantPaywallService tenantPaywallService = new TenantPaywallService();
  TenantEnvironmentLifecycleService tenantEnvironmentLifecycleService =
      new TenantEnvironmentLifecycleService();
  DevLifecycleToolService devLifecycleToolService = new DevLifecycleToolService();
  TenantPlanService tenantPlanService = new TenantPlanService();
  HostedCheckoutService hostedCheckoutService = new HostedCheckoutService();
  CheckoutRequestStore checkoutRequestStore = new CheckoutRequestStore();
  DemoDataTransferService demoDataTransferService = new DemoDataTransferService();
  BillingEventStore billingEventStore = new BillingEventStore();
  CheckoutWebhookProcessor checkoutWebhookProcessor =
      new CheckoutWebhookProcessor(billingEventStore, CHECKOUT_WEBHOOK_TOLERANCE_SECONDS);
  SubscriptionLifecycleApplier subscriptionLifecycleApplier = new SubscriptionLifecycleApplier();
  StripeCustomerPortalService stripeCustomerPortalService = new StripeCustomerPortalService();
  CompanyInvitationService companyInvitationService;
  private final TransactionalAuthEmailSender authEmailSender;
  private final EtendoGoSsoProviderRegistry ssoProviderRegistry;
  private final GoSessionService goSessionService;
  // Package-visible so tests can swap the database-backed role lookups for a fake.
  GoSessionRoleReconciler sessionRoleReconciler = new GoSessionRoleReconciler();

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
    this(authEmailSender, ssoProviderRegistry, goSessionService, new StripePriceService());
  }

  EtendoGoJwtServlet(TransactionalAuthEmailSender authEmailSender,
      EtendoGoSsoProviderRegistry ssoProviderRegistry, GoSessionService goSessionService,
      StripePriceService stripePriceService) {
    this.authEmailSender = authEmailSender;
    this.ssoProviderRegistry = ssoProviderRegistry;
    this.goSessionService = goSessionService;
    this.stripePriceService = stripePriceService;
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
    if (routePrimaryGet(path, request, response) || routeBillingGet(path, request, response)
        || routeAccountGet(path, request, response) || routeCheckoutGet(path, request, response)) {
      return;
    }
    writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_UNKNOWN_ENDPOINT + path);
  }

  private boolean routePrimaryGet(String path, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (isPath(path, "/dev/lifecycle") && DevLifecycleToolService.isEnabled()) {
      handleDevLifecycleGet(request, response);
    } else if (isPath(path, "/me")) {
      handleMe(request, response);
    } else if (isPath(path, PATH_ONBOARDING_DRAFT)) {
      handleGetOnboardingDraft(request, response);
    } else if (isPath(path, PATH_ONBOARDING_FIRST_STEPS)) {
      handleGetFirstSteps(request, response);
    } else if (isPath(path, PATH_ONBOARDING_COMPANY_DATA)) {
      handleGetCompanyData(request, response);
    } else if (isPath(path, "/demo-data-transfer")) {
      handleDemoDataTransferStatus(request, response);
    } else if (isPath(path, "/environments")) {
      handleEnvironments(request, response);
    } else {
      return false;
    }
    return true;
  }

  private boolean routeBillingGet(String path, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (isPath(path, "/billing/offers")) {
      handleBillingOffers(request, response);
    } else if (isPath(path, "/billing/overview")) {
      handleBillingOverview(request, response);
    } else if (isPath(path, "/billing/subscription")) {
      handleBillingSubscription(request, response);
    } else if (path != null && path.startsWith("/billing/purchases/")) {
      handleBillingPurchase(request, response);
    } else {
      return false;
    }
    return true;
  }

  private boolean routeAccountGet(String path, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (isPath(path, "/login")) {
      handleEnvironmentLogin(request, response);
    } else if (isPath(path, PATH_SESSION)) {
      handleSessionRestore(request, response);
    } else if (isPath(path, "/company-invitations/mine")) {
      handleCompanyInvitationMine(request, response);
    } else if (isPath(path, "/company-invitations/resolve")) {
      handleCompanyInvitationResolve(request, response);
    } else {
      return false;
    }
    return true;
  }

  private boolean routeCheckoutGet(String path, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (path != null && path.startsWith("/checkout/sessions/")) {
      handleCheckoutStatus(request, response);
    } else {
      return false;
    }
    return true;
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if (isPath(path, "/dev/lifecycle") && DevLifecycleToolService.isEnabled()) {
      handleDevLifecyclePost(request, response);
      return;
    }
    // Kept ahead of every credential-bearing group below: the provider calls this one
    // unauthenticated, so it must not fall through any of them. (The dev-lifecycle block
    // above is an exact-path match on a different path, so it does not affect that.)
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

  /** Local-only ETP-5396 lifecycle test controls. Disabled deployments answer the normal 404. */
  private void handleDevLifecycleGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedAccount(request, response, "dev-lifecycle-read", account ->
        writeResponse(response, HttpServletResponse.SC_OK,
            devLifecycleToolService.read(account.getEmail())));
  }

  private void handleDevLifecyclePost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedAccount(request, response, "dev-lifecycle-update", account -> {
      JSONObject body = readJsonBodyOrBadRequest(request, response);
      if (body == null) return;
      writeResponse(response, HttpServletResponse.SC_OK,
          devLifecycleToolService.update(account.getEmail(), body));
    });
  }

  /**
   * The pre-session endpoints, still answering with a bearer token.
   *
   * <p>Keeps the ETP-4575 name: `doPost` above dispatches through it, and develop's rename to
   * `routeAuthenticationPost` had no caller on this branch.
   *
   * @return true when the path matched one of them and the request was handled
   */
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
    } else if (isPath(path, "/auth-methods/remove")) {
      handleRemoveAuthMethod(request, response);
    } else if (isPath(path, PATH_VERIFY_EMAIL)) {
      handleVerifyEmail(request, response);
    } else if (isPath(path, PATH_VERIFY_EMAIL_RESEND)) {
      handleResendVerifyEmail(request, response);
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
    } else if (isPath(path, PATH_ONBOARDING_FIRST_STEPS)) {
      handleSaveFirstSteps(request, response);
    } else if (isPath(path, "/onboarding")) {
      handleOnboarding(request, response);
    } else if (isPath(path, "/billing/purchases")) {
      handleBillingPurchaseCreate(request, response);
    } else if (isPath(path, "/billing/subscription/portal")) {
      handleBillingPortal(request, response);
    } else if (isPath(path, "/demo-data-transfer/retry")) {
      handleDemoDataTransferRetry(request, response);
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
    runWithAuthenticatedContext(request, response, "checkout-session", authenticated -> {
      Account account = authenticated.account;
      if (!requireBillingOwner(response, account)) return;
      JSONObject body = readJsonBodyOrBadRequest(request, response);
      if (body == null) return;
      String clientName = validatedBillingClientName(response, body);
      if (clientName == null) return;
      CheckoutRequest activePurchase = checkoutRequestStore
          .findActiveForAccountAndClientName(account.getId(), account.getEmail(), clientName);
      if (activePurchase != null) {
        handleExistingBillingPurchase(response, account, activePurchase);
        return;
      }
      CheckoutSelection selection = resolveCheckoutSelection(request, response, authenticated,
          body);
      if (selection == null) return;
      // Billing redirects are server-owned. Never trust a browser-supplied Origin as a return URL.
      final String origin = PublicUrlResolver.resolveConfiguredAppBaseUrl();
      createHostedCheckoutSession(response, account, body, clientName, origin, selection,
          false);
    });
  }

  private boolean hasOwnedEnvironment(Account account) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      return EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail(account.getEmail());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private boolean requireBillingOwner(HttpServletResponse response, Account account)
      throws IOException {
    if (hasOwnedEnvironment(account)) return true;
    writeError(response, HttpServletResponse.SC_FORBIDDEN, BILLING_OWNER_REQUIRED,
        BILLING_OWNER_MESSAGE, BILLING_OWNER_MESSAGE);
    return false;
  }

  private String validatedBillingClientName(HttpServletResponse response, JSONObject body)
      throws IOException {
    String clientName = body.optString(FIELD_CLIENT_NAME, "").trim();
    if (clientName.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          CLIENT_NAME_REQUIRED, CLIENT_NAME_REQUIRED);
      return null;
    }
    return clientName;
  }

  private void createHostedCheckoutSession(HttpServletResponse response, Account account,
      JSONObject body, String clientName, String origin, CheckoutSelection selection,
      boolean accountBillingPurchase) throws IOException {
    try {
      // The source, transfer choice and Stripe Price are persisted on the purchase row, and the
      // flag-gated transfer selection is recorded before the provider is contacted.
      JSONObject result = hostedCheckoutService.createSession(account.getId(), account.getEmail(),
          clientName, origin, selection.demoClientId, selection.transferProducts,
          selection.transferContacts,
          requestId -> recordDemoDataTransferSelection(body, requestId, selection));
      addDemoDataTransferSelectionBestEffort(result, result.optString(FIELD_REQUEST_ID, ""),
          selection.demoClientId);
      writeResponse(response, HttpServletResponse.SC_CREATED, result);
    } catch (IllegalStateException e) {
      if (!CheckoutConfiguration.isConfigured()) {
        writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, CHECKOUT_NOT_CONFIGURED,
            CHECKOUT_NOT_CONFIGURED_MESSAGE, CHECKOUT_NOT_CONFIGURED_MESSAGE);
      } else {
        log.error(selectionPersistenceError(accountBillingPurchase), e);
        writeCheckoutSelectionError(response);
      }
    } catch (RuntimeException e) {
      log.error(selectionPersistenceError(accountBillingPurchase), e);
      writeCheckoutSelectionError(response);
    } catch (Exception e) {
      if (accountBillingPurchase) {
        log.error("Could not create account billing purchase", e);
        writeError(response, HttpServletResponse.SC_BAD_GATEWAY, BILLING_PROVIDER_ERROR,
            "Unable to create billing purchase", "Unable to create billing purchase");
      } else {
        log.error("Could not create hosted checkout session", e);
        writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "CHECKOUT_PROVIDER_ERROR",
            "Unable to create checkout session", "Unable to create checkout session");
      }
    }
  }

  private String selectionPersistenceError(boolean accountBillingPurchase) {
    return accountBillingPurchase ? BILLING_SELECTION_PERSIST_ERROR
        : CHECKOUT_SELECTION_PERSIST_ERROR;
  }

  private void writeCheckoutSelectionError(HttpServletResponse response) throws IOException {
    writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, CHECKOUT_SELECTION_ERROR,
        CHECKOUT_SELECTION_SAVE_ERROR, CHECKOUT_SELECTION_SAVE_ERROR);
  }

  /**
   * Creates a new account-level purchase. The local contract is provider-neutral; the existing
   * hosted checkout service is only the first adapter behind this boundary.
   */
  private void handleBillingPurchaseCreate(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    runWithAuthenticatedContext(request, response, "billing-purchase-create", authenticated -> {
      Account account = authenticated.account;
      if (!requireBillingOwner(response, account)) return;
      JSONObject body = readJsonBodyOrBadRequest(request, response);
      if (body == null) return;
      String clientName = validatedBillingClientName(response, body);
      if (clientName == null) return;
      CheckoutRequest activePurchase = checkoutRequestStore
          .findActiveForAccountAndClientName(account.getId(), account.getEmail(), clientName);
      if (activePurchase != null) {
        handleActiveBillingPurchase(response, account, body, activePurchase);
        return;
      }
      CheckoutSelection selection = resolveCheckoutSelection(request, response, authenticated,
          body);
      if (selection == null) return;
      // Billing redirects are server-owned. Never trust a browser-supplied Origin as a return URL.
      final String origin = PublicUrlResolver.resolveConfiguredAppBaseUrl();
      createHostedCheckoutSession(response, account, body, clientName, origin, selection,
          true);
    });
  }

  private void handleActiveBillingPurchase(HttpServletResponse response, Account account,
      JSONObject body, CheckoutRequest activePurchase) throws IOException, JSONException {
    String status = activePurchase.getCheckoutRequestStatus();
    if ("CREATING".equals(status) || "CREATED".equals(status)) {
      try {
        // Only a purchase that recorded a demo source may carry a transfer selection.
        String persistedDemoClientId = activePurchase.getDemoClient() == null
            ? null : activePurchase.getDemoClient().getId();
        recordDemoDataTransferSelection(body, activePurchase.getRequest(),
            new CheckoutSelection(persistedDemoClientId, false, false));
      } catch (IllegalStateException e) {
        writeError(response, HttpServletResponse.SC_CONFLICT, "TRANSFER_SELECTION_LOCKED",
            "Transfer selection cannot change after checkout creation",
            "Transfer selection cannot change after checkout creation");
        return;
      } catch (RuntimeException e) {
        log.error(BILLING_SELECTION_PERSIST_ERROR, e);
        writeCheckoutSelectionError(response);
        return;
      }
    }
    handleExistingBillingPurchase(response, account, activePurchase);
  }

  private void handleExistingBillingPurchase(HttpServletResponse response, Account account,
      CheckoutRequest activePurchase) throws IOException, JSONException {
    String status = activePurchase.getCheckoutRequestStatus();
    if ("CREATING".equals(status) || "CREATED".equals(status)) {
      // Billing redirects are server-owned. Never trust a browser-supplied Origin as a return URL.
      final String origin = PublicUrlResolver.resolveConfiguredAppBaseUrl();
      try {
        JSONObject result = hostedCheckoutService.reopenSession(activePurchase.getRequest(),
            account.getEmail(), activePurchase.getClientName(), origin);
        if ("complete".equals(result.optString("providerStatus", ""))) {
          writeCompletedBillingPurchase(response, account, activePurchase, result);
          return;
        }
        addDemoDataTransferSelectionBestEffort(result, activePurchase.getRequest(),
            activePurchase.getDemoClient() == null ? null : activePurchase.getDemoClient().getId());
        writeResponse(response, HttpServletResponse.SC_OK, result);
      } catch (HostedCheckoutService.OriginalPriceUnavailableException e) {
        writeError(response, HttpServletResponse.SC_CONFLICT, "PURCHASE_PRICE_UNAVAILABLE",
            "This purchase cannot be resumed. Please contact support.",
            "This purchase cannot be resumed. Please contact support.");
      } catch (IllegalStateException e) {
        writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, CHECKOUT_NOT_CONFIGURED,
            CHECKOUT_NOT_CONFIGURED_MESSAGE, CHECKOUT_NOT_CONFIGURED_MESSAGE);
      } catch (Exception e) {
        log.error("Could not reopen account billing purchase", e);
        writeError(response, HttpServletResponse.SC_BAD_GATEWAY, BILLING_PROVIDER_ERROR,
            "Unable to reopen billing purchase", "Unable to reopen billing purchase");
      }
      return;
    }
    JSONObject result = buildBillingPurchaseJson(activePurchase);
    writeResponse(response, HttpServletResponse.SC_CONFLICT, result);
  }

  private void writeCompletedBillingPurchase(HttpServletResponse response, Account account,
      CheckoutRequest activePurchase, JSONObject providerResult) throws IOException, JSONException {
    boolean paymentReceived = providerResult.optBoolean("paymentComplete", false);
    if (paymentReceived) {
      checkoutRequestStore.recordPaid(activePurchase.getRequest(),
          providerResult.optString("stripeCustomer", ""),
          providerResult.optString("stripeSubscription", ""));
    }
    CheckoutRequest currentPurchase = checkoutRequestStore.find(activePurchase.getRequest(),
        account.getId(), account.getEmail());
    JSONObject purchaseResult = buildBillingPurchaseJson(
        currentPurchase == null ? activePurchase : currentPurchase);
    purchaseResult.put("paymentReceived", paymentReceived);
    purchaseResult.put(FIELD_MESSAGE, paymentReceived
        ? "Payment received. We are finishing your environment setup."
        : "Checkout completed. We are confirming your payment before setup continues.");
    writeResponse(response, HttpServletResponse.SC_OK, purchaseResult);
  }

  private void handleCheckoutStatus(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String prefix = "/checkout/sessions/";
    String path = request.getPathInfo();
    String requestId = path != null && path.startsWith(prefix) ? path.substring(prefix.length()) : "";
    runWithPlatformAccount(request, response, "checkout-status", account -> {
      CheckoutRequest checkoutRequest = checkoutRequestStore.find(requestId, account.getId(),
          account.getEmail());
      // Answers "pending" for an unknown request id, for another account's request id, and for a
      // genuinely unpaid one alike. That is deliberate: the endpoint must never confirm that a
      // request id exists, and the account predicate inside find() is what enforces it.
      boolean paid = checkoutRequest != null
          && checkoutRequestStore.isPaidFor(requestId, account.getId(), account.getEmail(), null);
      JSONObject result = new JSONObject();
      result.put(FIELD_REQUEST_ID, requestId);
      result.put(FIELD_STATUS, paid ? "paid" : "pending");
      if (paid) {
        result.put(FIELD_CLIENT_NAME, checkoutRequest.getClientName());
        if (checkoutRequest.getDemoClient() != null) {
          result.put(FIELD_DEMO_CLIENT_ID, checkoutRequest.getDemoClient().getId());
        }
      }
      if (checkoutRequest != null && checkoutRequest.getDemoClient() != null) {
        addDemoDataTransferSelection(result, requestId, checkoutRequest.getDemoClient().getId());
      }
      writeResponse(response, HttpServletResponse.SC_OK, result);
    });
  }

  /** Account-level billing overview; it remains available when every ERP environment is blocked. */
  private void handleBillingOverview(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithPlatformAccount(request, response, "billing-overview", account -> {
      OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
      OBContext.setAdminMode(true);
      try {
        JSONObject result = new JSONObject();
        result.put(FIELD_ACCOUNT_EMAIL, account.getEmail());
        result.put("canManageBilling",
            EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail(account.getEmail()));
        org.codehaus.jettison.json.JSONArray purchases = new org.codehaus.jettison.json.JSONArray();
        for (CheckoutRequest purchase : checkoutRequestStore.findForAccount(account.getId(),
            account.getEmail())) {
          purchases.put(buildBillingPurchaseJson(purchase));
        }
        result.put("purchases", purchases);
        writeResponse(response, HttpServletResponse.SC_OK, result);
      } catch (JSONException e) {
        log.error("JSON error building billing overview", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
      } finally {
        OBContext.restorePreviousMode();
      }
    });
  }

  /** Returns the server-owned offer projection used by the account billing UI. */
  private void handleBillingOffers(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithPlatformAccount(request, response, "billing-offers", account -> {
      try {
        BillingOfferConfiguration.Offer offer =
            BillingOfferConfiguration.current(stripePriceService);
        JSONObject result = new JSONObject();
        result.put("code", "productive-tenant");
        result.put("priceId", offer.getPriceId());
        result.put("amountMinor", offer.getAmountMinor());
        result.put(FIELD_CURRENCY, offer.getCurrency());
        result.put("interval", offer.getInterval());
        writeResponse(response, HttpServletResponse.SC_OK, result);
      } catch (IllegalStateException | IOException | JSONException e) {
        log.warn("Could not retrieve configured Stripe billing price", e);
        writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
            "BILLING_OFFER_UNAVAILABLE", "The current price is temporarily unavailable",
            "The current price is temporarily unavailable");
      }
    });
  }

  /**
   * Returns the authenticated account's live subscription detail plus stored grace state.
   *
   * <p>Only the provider call maps an {@link IllegalStateException} to 503: that is where the
   * Stripe "not configured" condition is raised. The same exception from the stored-state lookup
   * is a real server fault and reaches {@code runWithPlatformAccount}'s 500 path.
   */
  private void handleBillingSubscription(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    runWithPlatformAccount(request, response, "billing-subscription", account -> {
      CheckoutRequest purchase = checkoutRequestStore.findSubscriptionForAccount(account.getId(),
          account.getEmail());
      if (purchase == null) {
        JSONObject result = new JSONObject();
        result.put("hasSubscription", false);
        writeResponse(response, HttpServletResponse.SC_OK, result);
        return;
      }
      StripeCustomerPortalService.SubscriptionDetail live;
      try {
        live = stripeCustomerPortalService.retrieveSubscription(purchase.getStripeSubscription());
      } catch (IllegalStateException e) {
        writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, CHECKOUT_NOT_CONFIGURED,
            CHECKOUT_NOT_CONFIGURED_MESSAGE, CHECKOUT_NOT_CONFIGURED_MESSAGE);
        return;
      } catch (IOException e) {
        log.error("Could not load account billing subscription", e);
        writeError(response, HttpServletResponse.SC_BAD_GATEWAY, BILLING_PROVIDER_ERROR,
            "Unable to load billing subscription", "Unable to load billing subscription");
        return;
      }
      OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
      OBContext.setAdminMode(true);
      try {
        TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot = null;
        if (purchase.getCreatedClient() != null) {
          snapshot = tenantEnvironmentLifecycleService.resolve(purchase.getCreatedClient().getId());
        }
        writeResponse(response, HttpServletResponse.SC_OK,
            buildBillingSubscriptionJson(live, snapshot));
      } finally {
        OBContext.restorePreviousMode();
      }
    });
  }

  /**
   * Creates a portal session using only the authenticated account's stored customer id.
   *
   * <p>The purchase is resolved exactly as the Subscription page resolves it, so the portal always
   * opens for the customer of the subscription the page shows.
   */
  private void handleBillingPortal(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithPlatformAccount(request, response, "billing-portal", account -> {
      CheckoutRequest purchase = checkoutRequestStore.findSubscriptionForAccount(account.getId(),
          account.getEmail());
      if (purchase == null || StringUtils.isBlank(purchase.getStripeCustomer())) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, "NO_SUBSCRIPTION",
            "No subscription for this account", "No subscription for this account");
        return;
      }
      JSONObject session;
      try {
        session = stripeCustomerPortalService.createSession(purchase.getStripeCustomer());
      } catch (IllegalStateException e) {
        writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, CHECKOUT_NOT_CONFIGURED,
            CHECKOUT_NOT_CONFIGURED_MESSAGE, CHECKOUT_NOT_CONFIGURED_MESSAGE);
        return;
      } catch (IOException e) {
        log.error("Could not create a billing portal session", e);
        writeError(response, HttpServletResponse.SC_BAD_GATEWAY, BILLING_PROVIDER_ERROR,
            "Unable to open the billing portal", "Unable to open the billing portal");
        return;
      }
      JSONObject result = new JSONObject();
      result.put("url", session.optString("url", ""));
      writeResponse(response, HttpServletResponse.SC_OK, result);
    });
  }

  private JSONObject buildBillingSubscriptionJson(
      StripeCustomerPortalService.SubscriptionDetail live,
      TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot) throws JSONException {
    Instant graceEndsAt = null;
    int graceDaysRemaining = 0;
    if (snapshot != null
        && snapshot.getSubscriptionStatus() == EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE
        && snapshot.getRenewalDueAt() != null) {
      graceEndsAt = snapshot.getRenewalDueAt().plus(tenantEnvironmentLifecycleService
          .configuration().getRenewalGraceDays(), java.time.temporal.ChronoUnit.DAYS);
      long remainingSeconds = graceEndsAt.getEpochSecond() - Instant.now().getEpochSecond();
      graceDaysRemaining = remainingSeconds <= 0 ? 0 : (int) ((remainingSeconds + 86399) / 86400);
    }
    JSONObject result = new JSONObject();
    result.put("hasSubscription", true);
    result.put("plan", live.getPlan());
    result.put("amountMinor", live.getAmountMinor());
    result.put(FIELD_CURRENCY, live.getCurrency());
    result.put(FIELD_STATUS, live.getStatus());
    result.put("renewalAt", live.getRenewalAt() == null ? JSONObject.NULL : live.getRenewalAt());
    result.put("cancelAtPeriodEnd", live.isCancelAtPeriodEnd());
    result.put("graceEndsAt", graceEndsAt == null ? JSONObject.NULL : graceEndsAt.toString());
    result.put("graceDaysRemaining", graceDaysRemaining);
    return result;
  }

  /** Returns one account-scoped purchase projection, with foreign IDs kept indistinguishable. */
  private void handleBillingPurchase(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String prefix = "/billing/purchases/";
    String purchaseId = request.getPathInfo().substring(prefix.length());
    runWithPlatformAccount(request, response, "billing-purchase", account -> {
      CheckoutRequest purchase = checkoutRequestStore.find(purchaseId, account.getId(),
          account.getEmail());
      if (purchase == null) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, "PURCHASE_NOT_FOUND",
            "Purchase not found", "Purchase not found");
        return;
      }
      try {
        writeResponse(response, HttpServletResponse.SC_OK, buildBillingPurchaseJson(purchase));
      } catch (JSONException e) {
        log.error("JSON error building billing purchase", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
      }
    });
  }

  private JSONObject buildBillingPurchaseJson(CheckoutRequest purchase) throws JSONException {
    JSONObject result = new JSONObject();
    result.put("purchaseId", purchase.getRequest());
    result.put(FIELD_STATUS, StringUtils.defaultString(purchase.getCheckoutRequestStatus(), "UNKNOWN"));
    result.put(FIELD_CLIENT_NAME, StringUtils.defaultString(purchase.getClientName()));
    if (purchase.getDemoClient() != null) {
      result.put(FIELD_DEMO_CLIENT_ID, purchase.getDemoClient().getId());
    }
    String createdClientId = purchase.getCreatedClient() == null
        ? null : purchase.getCreatedClient().getId();
    result.put("clientId", createdClientId == null ? JSONObject.NULL : createdClientId);
    if (purchase.getDemoClient() != null) {
      addDemoDataTransferSelection(result, purchase.getRequest(), purchase.getDemoClient().getId());
    }
    if (purchase.getCreatedClient() != null) {
      result.put("createdClientId", createdClientId);
    }
    if (purchase.getFailureReason() != null) {
      result.put("failureReason", purchase.getFailureReason());
    }
    return result;
  }

  /**
   * POST /sws/go/checkout/webhook — the provider's signed event delivery.
   *
   * <p>Signature, payload and idempotency are decided by {@link CheckoutWebhookProcessor} over the
   * durable {@link BillingEventStore}, so a redelivery after a restart is answered as a duplicate.
   * The wire contract:
   *
   * <ul>
   *   <li>invalid signature → 400 {@code INVALID_CHECKOUT_SIGNATURE}, and unusable payload → 400
   *       {@code INVALID_CHECKOUT_PAYLOAD}. The provider does not retry either, which is correct:
   *       neither can succeed on a second attempt.
   *   <li>duplicate → 200 {@code {"received":true}} with nothing applied.
   *   <li>accepted → the event is applied and its row marked {@code APPLIED} or {@code IGNORED}.
   * </ul>
   *
   * <p><b>Two deliberate changes from the pre-ETP-5045 contract</b>, both chosen so that no event
   * can be acknowledged without being recorded:
   *
   * <ol>
   *   <li>A payment-type event whose {@code data.object} is missing used to be a 400
   *       {@code INVALID_CHECKOUT_PAYLOAD}; it is now 200 with the row marked {@code IGNORED}. The
   *       claim has already committed by that point, so answering 400 would have left a recorded
   *       event the provider stops retrying, with no explanation on the row.
   *   <li>A {@link RuntimeException} while applying the event used to escape to the container as
   *       an HTML 500; it is now a structured 500 {@code CHECKOUT_WEBHOOK_FAILED} with the row
   *       marked {@code FAILED}. The status is what makes the provider retry, and the
   *       {@code FAILED} row is what makes that retry re-claim rather than dismiss it.
   * </ol>
   */
  private void handleCheckoutWebhook(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String payload = readRawBody(request);
    String signature = request.getHeader("Stripe-Signature");
    CheckoutWebhookProcessor.Acceptance acceptance;
    try {
      acceptance = checkoutWebhookProcessor.evaluate(payload, signature,
          CheckoutConfiguration.webhookSecret(), Instant.now().getEpochSecond());
    } catch (RuntimeException e) {
      // The claim itself failed, so nothing was recorded and there is no row to annotate. A 500
      // is the only honest answer: it makes the provider redeliver an event we did not keep.
      log.error("Checkout webhook event could not be claimed", e);
      writeWebhookFailed(response);
      return;
    }
    switch (acceptance.result()) {
      case INVALID_SIGNATURE:
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_CHECKOUT_SIGNATURE",
            "Invalid checkout webhook signature", "Invalid checkout webhook signature");
        return;
      case INVALID_PAYLOAD:
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_CHECKOUT_PAYLOAD",
            "Invalid checkout webhook payload", "Invalid checkout webhook payload");
        return;
      case DUPLICATE:
        writeWebhookReceived(response);
        return;
      default:
        break;
    }
    String eventId = acceptance.eventId();
    String type = acceptance.event().optString("type", "");
    try {
      applyCheckoutEvent(eventId, type, acceptance.event());
    } catch (RuntimeException e) {
      // The full exception goes to the log; the audit column gets the class name and a fixed
      // phrase only. A provider message can quote payload fragments, and FAILURE_REASON is
      // required to stay operationally safe (no customer or card data) — see BillingEventStore.
      log.error("Checkout webhook event '{}' ({}) could not be applied", eventId, type, e);
      // Discard whatever the handler left pending, so the FAILED write below does not commit a
      // partial projection with it. The claim row was committed on its own and is unaffected.
      EtendoGoDalHelper.rollbackDalChanges("checkout webhook event", e, log);
      billingEventStore.markFailed(eventId,
          "Handler failed while applying the event: " + e.getClass().getName());
      writeWebhookFailed(response);
      return;
    }
    writeWebhookReceived(response);
  }

  /**
   * Applies one claimed event and records the outcome on its {@code ETGO_BILLING_EVENT} row.
   *
   * <p>Payment confirmation and subscription lifecycle events use separate paths. Everything else
   * is acknowledged and marked {@code IGNORED} with the reason, so the audit row says why nothing
   * happened.
   */
  private void applyCheckoutEvent(String eventId, String type, JSONObject event) {
    if (CHECKOUT_PAID_EVENT_TYPES.contains(type)) {
      applyCheckoutPaid(eventId, type, event);
      return;
    }
    if (SUBSCRIPTION_EVENT_TYPES.contains(type)) {
      applySubscriptionLifecycle(eventId, type, event);
      return;
    }
    billingEventStore.markIgnored(eventId, "unhandled event type");
  }

  private void applyCheckoutPaid(String eventId, String type, JSONObject event) {
    JSONObject data = event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject("object");
    JSONObject metadata = object == null ? null : object.optJSONObject("metadata");
    String requestId = metadata == null ? "" : metadata.optString("request_id", "");
    String email = metadata == null ? "" : metadata.optString("account_email", "");
    if (StringUtils.isBlank(requestId) || StringUtils.isBlank(email)) {
      billingEventStore.markIgnored(eventId, "missing correlation metadata");
      return;
    }
    // The customer and subscription ids are read here and nowhere else: this is the only event
    // that carries them alongside the correlation id, and every later subscription or invoice
    // event arrives keyed by the subscription rather than by the request.
    boolean recorded = checkoutRequestStore.recordPaid(requestId, object.optString("customer", ""),
        object.optString("subscription", ""));
    if (!recorded) {
      billingEventStore.markIgnored(eventId, "unknown checkout request");
      return;
    }
    billingEventStore.markApplied(eventId);
    log.info("Checkout webhook event '{}' ({}) applied", eventId, type);
  }

  /**
   * Applies a subscription or invoice lifecycle event to the owned environment.
   *
   * <p>These events carry no {@code request_id}: that metadata travels only on the checkout
   * session. Invoice events are keyed by the subscription field, while subscription events are
   * keyed by the object id; both fall back to the customer when the subscription lookup misses.
   * An event that resolves to no environment is ignored, never blocking.
   *
   * <p>The event is evaluated twice: once without state, so a malformed or unhandled event is
   * ignored before any lookup, and once against the environment's stored projection, which makes
   * an out-of-order older event stale and keeps an authoritative {@code PAST_DUE} due date.
   *
   * <p>The status, due date and event instant are written in the session that
   * {@code markApplied} commits, so they land together. When a write fails the session is rolled
   * back before the row is marked {@code FAILED}; otherwise that commit would persist a partial
   * projection. The claim row itself was committed by the claim and survives the rollback.
   */
  private void applySubscriptionLifecycle(String eventId, String type, JSONObject event) {
    SubscriptionEventOutcome screened = subscriptionLifecycleApplier.evaluate(type, event);
    if (screened.isIgnored()) {
      billingEventStore.markIgnored(eventId, screened.reason());
      return;
    }
    JSONObject data = event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject("object");
    String subscriptionId = SubscriptionLifecycleApplier.subscriptionIdOf(type, object);
    CheckoutRequest purchase = checkoutRequestStore.findByStripeSubscription(subscriptionId);
    if (purchase == null) {
      purchase = checkoutRequestStore.findByStripeCustomer(object.optString("customer", ""));
    }
    if (purchase == null || purchase.getCreatedClient() == null) {
      billingEventStore.markIgnored(eventId, "unresolved subscription");
      return;
    }
    String clientId = purchase.getCreatedClient().getId();
    SubscriptionEventOutcome outcome = subscriptionLifecycleApplier.evaluate(type, event,
        tenantEnvironmentLifecycleService.readSubscriptionState(clientId));
    if (outcome.isIgnored()) {
      billingEventStore.markIgnored(eventId, outcome.reason());
      return;
    }
    boolean stored = tenantEnvironmentLifecycleService.updateSubscriptionStatus(clientId,
        outcome.status(), outcome.dueAt());
    if (!stored) {
      EtendoGoDalHelper.rollbackDalChanges("subscription lifecycle event", null, log);
      billingEventStore.markFailed(eventId, "Could not store the subscription projection");
      return;
    }
    tenantEnvironmentLifecycleService.recordSubscriptionEventAt(clientId,
        SubscriptionLifecycleApplier.eventCreatedAt(event));
    billingEventStore.markApplied(eventId);
    log.info("Subscription lifecycle event '{}' ({}) applied", eventId, type);
  }

  /**
   * Acknowledges a webhook delivery the way the provider expects: 200 {@code {"received":true}}.
   *
   * <p>Built through the map constructor rather than {@code put}, which declares a
   * {@link JSONException} that a one-key literal cannot raise — there is no failure here to
   * handle, so there is no handler.
   */
  private void writeWebhookReceived(HttpServletResponse response) throws IOException {
    writeResponse(response, HttpServletResponse.SC_OK, new JSONObject(Map.of("received", true)));
  }

  /**
   * Refuses a webhook delivery with a structured 500, which is what makes the provider retry it.
   */
  private void writeWebhookFailed(HttpServletResponse response) throws IOException {
    writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "CHECKOUT_WEBHOOK_FAILED",
        "Checkout webhook event could not be applied",
        "Checkout webhook event could not be applied");
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
    String requestOrigin = request.getHeader(HEADER_ORIGIN);
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
      // ETP-4798: the session token below still comes back, so the user keeps filling in the
      // onboarding form and their draft keeps saving. What the unconfirmed address blocks is the
      // one irreversible, costly step — creating the tenant in handleOnboarding.
      issueEmailVerification(account, normalizedLanguage, true);

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
      // ETP-5115 / AUTH-05: an account with no local password used to fall out here and receive
      // nothing at all, while the screen still confirmed a link had been sent. That is every
      // SSO-created account, which has passwordHash null by design — so the one flow that exists to
      // recover access was a silent no-op for exactly the users who most needed it. It now gets a
      // link too; only the wording differs, because it is being asked to create a first password
      // rather than restore one it forgot.
      //
      // The neutral response below stays exactly as it was, and must. Varying it by account state
      // is the classic user-enumeration vector: it would confirm to any anonymous prober both that
      // the address is registered and which identity provider it uses. The disclosure belongs in
      // the email, which only the owner of the mailbox reads.
      if (account != null) {
        storeResetTokenAndSendEmail(account, PublicUrlResolver.resolveConfiguredAppBaseUrl(),
            EtendoGoJwtDalHelper.hasLocalPassword(account));
      } else {
        logPasswordResetBranch("no-account", email);
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
          hashAuthToken(token), new Date());
      if (account == null) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, PASSWORD_RESET_INVALID_MESSAGE);
        return;
      }
      EtendoGoJwtDalHelper.consumePasswordReset(account, hashPassword(password), new Date());
      // ETP-5003 — the security notice belongs to every password change, not only the one made
      // from inside the app. This is the path an attacker with a stolen reset link would take, so
      // it is the one where the owner most needs to be told.
      sendAuthEmailBestEffort("password-changed",
          () -> authEmailSender.sendPasswordChanged(account));

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
   * POST /sws/go/verify-email
   * Body: { "token": "..." }
   *
   * <p>ETP-4798. Deliberately unauthenticated: the token in the link <em>is</em> the credential,
   * and requiring a session on top would break the ordinary case of opening the mail in a browser
   * where the user is not signed in.
   *
   * <p>Idempotent by design. Following the same link twice — which people do constantly, and which
   * mail clients do on their own when they prefetch — answers 200 both times instead of showing a
   * scary "invalid token" on the second click. That is why
   * {@link EtendoGoJwtDalHelper#consumeEmailVerification} leaves the token hash in place: once the
   * address is confirmed the token grants nothing, so replaying it within its TTL is a no-op rather
   * than something worth defending against.
   */
  private void handleVerifyEmail(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          INVALID_JSON_BODY, INVALID_JSON_BODY);
      return;
    }

    String verifyToken;
    try {
      verifyToken = body.getString(FIELD_TOKEN).trim();
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          "Missing required field: token", "Missing required field: token");
      return;
    }
    if (verifyToken.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          "Field token must not be empty", "Field token must not be empty");
      return;
    }

    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      Account account = EmailVerificationDalHelper.findAccountByVerifyTokenHash(
          hashAuthToken(verifyToken), new Date());
      if (account == null) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_EMAIL_VERIFY_INVALID,
            EMAIL_VERIFY_INVALID_MESSAGE, EMAIL_VERIFY_INVALID_MESSAGE);
        return;
      }
      if (!EmailVerificationDalHelper.isEmailVerified(account)) {
        EmailVerificationDalHelper.consumeEmailVerification(account, new Date());
      }

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_MESSAGE, "Email address confirmed");
      result.put(FIELD_EMAIL_VERIFIED, true);
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("email verification", e, log);
      log.error("Email verification failed", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error building email verification response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * POST /sws/go/verify-email/resend
   * Header: Authorization: Bearer &lt;session_token&gt;
   *
   * <p>ETP-4798. Answers the same neutral 200 whether a new link went out, the address was already
   * confirmed, or nothing was pending, so the response carries no account state and a double-click
   * on "resend" is harmless. Neutrality here is about not restating what the caller already knows —
   * unlike the password-reset request, this endpoint is authenticated, so a genuine server failure
   * is reported as one rather than hidden behind a success.
   *
   * <p>Only re-issues when a confirmation is genuinely pending. In particular it does not mint a
   * first token for an account that predates ETP-4798: doing so would move that account from
   * "never gated" to "gated", locking out a user who did nothing but press a button.
   */
  private void handleResendVerifyEmail(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedAccount(request, response, "email verification resend", account -> {
      String language = StringUtils.trimToNull(request.getParameter(FIELD_LANGUAGE));
      if (EmailVerificationDalHelper.isEmailVerificationPending(account)) {
        issueEmailVerification(account, language, false);
      }
      writeEmailVerifyNeutralResponse(response);
    });
  }

  /**
   * Verifies the current password of an account that has one, writing the error response itself.
   *
   * <p>Only the nesting moved here; both branches are unchanged. It is a separate method so
   * {@link #handleChangePassword} stays inside its cognitive complexity budget, and the caller
   * guards it with {@code !enrolling} because an account with no local password has nothing to
   * verify.
   *
   * @return true when the caller may proceed; false when a response has already been written
   */
  private boolean currentPasswordAccepted(HttpServletResponse response, Account account,
      String currentPassword) throws IOException {
    if (currentPassword.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_MISSING_CREDENTIALS,
          "changePassword: request lacks currentPassword for an account that has one",
          "The current password is required.");
      return false;
    }
    if (!verifyPassword(currentPassword, account.getPasswordHash())) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, CODE_INVALID_CURRENT_PASSWORD,
          "changePassword: current password did not verify",
          "The current password is not correct.");
      return false;
    }
    return true;
  }

  /** The two passwords a change-password request carries, once validated. */
  private static final class ChangePasswordRequest {
    private final String currentPassword;
    private final String newPassword;

    private ChangePasswordRequest(String currentPassword, String newPassword) {
      this.currentPassword = currentPassword;
      this.newPassword = newPassword;
    }
  }

  /**
   * Reads and validates the change-password body. Writes the error response and returns
   * {@code null} when the body is unusable, so the caller only has to check for null.
   *
   * <p>ETP-5115: currentPassword is read optionally rather than demanded up front. An account with
   * no local password has none to give, and requiring it here rejected those callers with a
   * missing-credentials error before anything ever looked at the account — so the endpoint that
   * says "this account signs in through an external provider" could not be reached by the very
   * accounts it describes. Whether it is actually required is decided by the caller, once the
   * account is known; an account that has a password still must supply it.
   */
  private ChangePasswordRequest readChangePasswordRequest(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_JSON_BODY);
      return null;
    }

    String newPassword;
    try {
      newPassword = body.getString("newPassword");
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_MISSING_CREDENTIALS,
          "changePassword: request body lacks newPassword",
          "The new password is required.");
      return null;
    }
    if (newPassword.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_MISSING_CREDENTIALS,
          "changePassword: newPassword is empty",
          "The new password is required.");
      return null;
    }
    if (!PasswordPolicy.isStrong(newPassword)) {
      writeWeakPasswordError(response);
      return null;
    }
    return new ChangePasswordRequest(body.optString("currentPassword", ""), newPassword);
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

    ChangePasswordRequest passwords = readChangePasswordRequest(request, response);
    if (passwords == null) {
      return;
    }
    String currentPassword = passwords.currentPassword;
    String newPassword = passwords.newPassword;

    try {
      AuthenticatedAccount authenticated = resolveAuthenticatedAccountContext(request, response);
      if (authenticated == null) {
        return;
      }
      Account account = authenticated.account;
      // ETP-5115: an account with no local password is enrolling rather than changing, and there
      // is no current password to verify — the authenticated session already proves who is asking.
      // This used to be a dead end that told the caller the account "has no password to change"
      // and stopped there, leaving an SSO-only account unable to give itself one from inside the
      // app. ETP-4575: the account comes from the session context, not a bearer-only lookup.
      boolean enrolling = !EtendoGoJwtDalHelper.hasLocalPassword(account);
      if (!enrolling && !currentPasswordAccepted(response, account, currentPassword)) {
        return;
      }
      String sessionToken = generateToken();
      EtendoGoJwtDalHelper.changePassword(account, hashPassword(newPassword), sessionToken,
          new Date());
      // The notice tells the owner their way in changed, so it has to say which thing happened:
      // "your password was changed" is alarming and wrong for somebody who just created a first one.
      // A block lambda, not a ternary — sendAuthEmailBestEffort takes a Runnable, and a conditional
      // expression is not void-compatible.
      sendAuthEmailBestEffort(enrolling ? "password-added" : "password-changed", () -> {
        if (enrolling) {
          authEmailSender.sendPasswordAdded(account);
        } else {
          authEmailSender.sendPasswordChanged(account);
        }
      });

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
  /**
   * Describes how an account can be signed into: an optional local password plus one entry per
   * linked identity provider.
   *
   * <p>ETP-5115. Until now the client had to guess. The web app decided whether to offer "change
   * password" by reading a value it had stashed in {@code localStorage} at login, which is why an
   * SSO account was shown no such option at all — not disabled with a reason, simply absent. The
   * server is the only side that knows, so it says so.
   *
   * <p><strong>{@code removable} is computed here and nowhere else.</strong> It lists the methods
   * that could be taken away while leaving the account still reachable, and it is the only thing a
   * client may use to enable a remove control. Recomputing the rule in the browser would put the
   * invariant in the one place that cannot enforce it. The server checks it again when a removal is
   * actually requested — this list is for drawing the screen, never for authorising the act.
   *
   * <p>The provider's subject claim is deliberately absent. It identifies the user at the provider
   * and nothing on this screen needs it.
   *
   * <p><strong>Reading the identities can write one.</strong> An account still carrying its
   * identity in the old inline columns is migrated to a child row on first read, so this GET has a
   * write as a side effect. That is deliberate and it is the point: {@code /me} is the most-called
   * endpoint in the app, which makes it the fastest way for the population to migrate without a
   * backfill. The write is idempotent, guarded by a unique constraint, and opens no transaction of
   * its own.
   *
   * @param account the account being described
   * @return the {@code authMethods} object
   * @throws JSONException if the response cannot be built
   */
  /**
   * POST /sws/go/auth-methods/remove
   * Body: { "method": "password" | "<provider>", "currentPassword": "..." }
   *
   * <p>ETP-5115. Removes one way of signing in. One endpoint rather than two so the invariant that
   * makes this safe — an account must keep at least one method — is evaluated in exactly one place
   * for both kinds of method.
   *
   * <p><strong>The invariant is enforced here, on the server, inside the transaction.</strong> The
   * {@code removable} list that {@code /me} publishes exists to draw the screen and is deliberately
   * not trusted: two tabs would each read one remaining method and both would be allowed through,
   * emptying the account. The set is therefore re-read here, immediately before the delete.
   *
   * <p><strong>Re-authentication</strong> asks the caller to prove they hold a method, where doing
   * so is cheap. Removing the password requires the current password. Removing an identity does
   * not, because no equally cheap proof exists for a provider — the session token carries it, and
   * the notice mail is what makes an unwanted removal visible. Tightening that into a full step-up
   * is a decision left open in the plan, not an oversight.
   */
  private void handleRemoveAuthMethod(HttpServletRequest request, HttpServletResponse response)
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
    String method = StringUtils.trimToEmpty(body.optString("method", ""));
    String currentPassword = body.optString("currentPassword", "");
    if (method.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_MISSING_CREDENTIALS,
          "removeAuthMethod: request body lacks method", "The method to remove is required.");
      return;
    }

    try {
      // ETP-5455 — through the account surface's single resolver: it used to read the Bearer
      // header inline, so the SPA's cookie session got 401 here (and the frontend logs out on a
      // 401), and the legacy kill switch did not apply.
      AuthenticatedAccount authenticated = resolveAuthenticatedAccountContext(request, response);
      if (authenticated == null) {
        return;
      }
      removeAuthMethod(authenticated.account, method, currentPassword, response);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("remove auth method", e, log);
      log.error("Database error removing an authentication method", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error building remove-auth-method response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Split out of {@link #handleRemoveAuthMethod} so neither trips the complexity limit. */
  private void removeAuthMethod(Account account, String method, String currentPassword,
      HttpServletResponse response) throws IOException, JSONException {
    boolean hasPassword = EtendoGoJwtDalHelper.hasLocalPassword(account);
    List<AccountIdentity> identities = AccountIdentityDalHelper.identitiesFor(account);
    boolean removingPassword = StringUtils.equals(method, METHOD_PASSWORD);
    AccountIdentity target = removingPassword ? null
        : AccountIdentityDalHelper.identityForProvider(account, method);

    if (removingPassword ? !hasPassword : target == null) {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, CODE_METHOD_NOT_FOUND,
          "removeAuthMethod: the account does not have the requested method",
          "That sign-in method is not enabled on this account.");
      return;
    }
    // Re-read rather than trusting what /me last published: this is the check that keeps the
    // account reachable, and it has to see the state as it is at this instant.
    if ((hasPassword ? 1 : 0) + identities.size() <= 1) {
      writeError(response, HttpServletResponse.SC_CONFLICT, CODE_LAST_AUTH_METHOD,
          "removeAuthMethod: refusing to remove the only remaining method",
          "This is the only way you can sign in. Add another method before removing this one.");
      return;
    }
    if (removingPassword) {
      if (currentPassword.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_MISSING_CREDENTIALS,
            "removeAuthMethod: request lacks currentPassword",
            "The current password is required.");
        return;
      }
      if (!verifyPassword(currentPassword, account.getPasswordHash())) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, CODE_INVALID_CURRENT_PASSWORD,
            "removeAuthMethod: current password did not verify",
            "The current password is not correct.");
        return;
      }
    }

    String sessionToken = generateToken();
    if (removingPassword) {
      EtendoGoJwtDalHelper.removeLocalPassword(account, sessionToken, new Date());
    } else {
      AccountIdentityDalHelper.unlink(target);
      EtendoGoJwtDalHelper.updateSessionToken(account, sessionToken);
    }
    sendAuthEmailBestEffort("auth-method-removed",
        () -> authEmailSender.sendAuthMethodRemoved(account));

    JSONObject result = new JSONObject();
    result.put(FIELD_STATUS, STATUS_SUCCESS);
    result.put(FIELD_TOKEN, sessionToken);
    result.put("authMethods", buildAuthMethods(account));
    writeResponse(response, HttpServletResponse.SC_OK, result);
  }

  private JSONObject buildAuthMethods(Account account) throws JSONException {
    boolean hasPassword = EtendoGoJwtDalHelper.hasLocalPassword(account);
    List<AccountIdentity> identities = AccountIdentityDalHelper.identitiesFor(account);

    JSONObject password = new JSONObject();
    password.put("enabled", hasPassword);
    Date changedAt = EtendoGoJwtDalHelper.getPasswordChangedAt(account);
    if (hasPassword && changedAt != null) {
      password.put("lastChanged", changedAt.toInstant().toString());
    }

    JSONArray identityArray = new JSONArray();
    for (AccountIdentity identity : identities) {
      JSONObject entry = new JSONObject();
      entry.put("provider", identity.getAuthProvider());
      entry.put(FIELD_EMAIL, identity.getExternalEmail());
      if (identity.getLinked() != null) {
        entry.put("linked", identity.getLinked().toInstant().toString());
      }
      if (identity.getLastSSOLogin() != null) {
        entry.put("lastLogin", identity.getLastSSOLogin().toInstant().toString());
      }
      identityArray.put(entry);
    }

    // One method has to survive. With a password and N identities the total is 1 + N, and a method
    // is removable exactly when the total is greater than one — which is why the sole remaining
    // method is reported as not removable rather than being left out of the list entirely: the
    // screen still has to draw it, just without an enabled control.
    int total = (hasPassword ? 1 : 0) + identities.size();
    JSONArray removable = new JSONArray();
    if (total > 1) {
      if (hasPassword) {
        removable.put(METHOD_PASSWORD);
      }
      for (AccountIdentity identity : identities) {
        removable.put(identity.getAuthProvider());
      }
    }

    JSONObject authMethods = new JSONObject();
    authMethods.put(METHOD_PASSWORD, password);
    authMethods.put("identities", identityArray);
    authMethods.put("removable", removable);
    return authMethods;
  }

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
      // ETP-4798: two separate facts, because they are not opposites. "pending" is what the web
      // client shows the confirm-your-email banner for; an account that predates this feature is
      // neither verified nor pending, and must see no banner and hit no gate.
      result.put(FIELD_EMAIL_VERIFIED, EmailVerificationDalHelper.isEmailVerified(account));
      result.put(FIELD_EMAIL_VERIFICATION_PENDING,
          EmailVerificationDalHelper.isEmailVerificationPending(account));
      result.put("authMethods", buildAuthMethods(account));

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
   * Resolve the platform account for a billing / checkout / upgrade endpoint. Always enters admin
   * mode (so callers can restore it in their finally block) and writes the error response,
   * returning null, when the request is not authenticated.
   *
   * <p>ETP-5455 — the account surface's single resolver under
   * {@link AccountRequirement#PLATFORM_CREDENTIAL_ONLY}. It used to carry its own Bearer branch,
   * which skipped the legacy kill switch and the use counter, and branched on the mere PRESENCE
   * of a session cookie: a blank {@code __Host-go_session} value sent billing down the cookie
   * resolver's wide lookup, where an environment JWT got past the platform-token-only rule.
   */
  private Account resolvePlatformAccount(HttpServletRequest request, HttpServletResponse response) throws IOException {
    AuthenticatedAccount authenticated =
        resolveAccount(request, response, AccountRequirement.PLATFORM_CREDENTIAL_ONLY);
    return authenticated == null ? null : authenticated.account;
  }

  /**
   * Whether the request carries any credential at all (a session cookie or a bearer header),
   * without touching {@code OBContext} or the DB — lets callers fail fast with 401 for a fully
   * unauthenticated request before ever entering admin mode.
   */
  private boolean hasAnyCredential(HttpServletRequest request) {
    return hasSessionCookie(request) || extractBearerToken(request) != null;
  }

  /** Whether the request carries the {@code __Host-go_session} cookie, whatever its value. */
  private static boolean hasSessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (GoSessionSecurity.COOKIE_NAME.equals(cookie.getName())) {
          return true;
        }
      }
    }
    return false;
  }

  private AuthenticatedAccount resolveAuthenticatedAccountContext(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    return resolveAccount(request, response, AccountRequirement.ANY_ACCOUNT_CREDENTIAL);
  }

  /**
   * Which Bearer an account endpoint accepts when no session cookie is present (ETP-5455). The
   * ONLY thing that may differ between account endpoints: the cookie, CSRF, kill-switch and
   * counting rules are the same for all of them.
   */
  enum AccountRequirement {
    /** An account session token or an environment JWT (the wide lookup). */
    ANY_ACCOUNT_CREDENTIAL,
    /** The account session (platform) token only: environment JWTs are refused for billing. */
    PLATFORM_CREDENTIAL_ONLY
  }

  /**
   * The account surface's single resolver (ETP-5455). Enters admin mode unconditionally, so the
   * callers' {@code finally} can always restore it, and writes the error response itself.
   *
   * <p>The cookie session wins whenever it is present and valid; a dead one is final (401, no
   * fallback to a Bearer sent alongside). Only without a session does a Bearer count — gated by
   * the legacy kill switch, counted as a legacy use, and looked up as the requirement says.
   */
  private AuthenticatedAccount resolveAccount(HttpServletRequest request,
      HttpServletResponse response, AccountRequirement requirement) throws IOException {
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
    // The wide lookup, not findActiveAccountByToken, for ANY_ACCOUNT_CREDENTIAL: the legacy path
    // has to keep accepting Etendo's JWTs, and only this one falls back to decoding the token and
    // resolving the account from its `user` claim when no opaque sessionToken matches.
    // Centralising the resolution here narrowed it by accident once, which answered 401 to every
    // JWT-bearing client on /me, /environments, the onboarding draft and the invitation
    // endpoints — the exact callers GoLegacyBearer stays enabled for.
    Account account = requirement == AccountRequirement.PLATFORM_CREDENTIAL_ONLY
        ? EtendoGoJwtDalHelper.findActiveAccountByPlatformToken(token)
        : EtendoGoJwtDalHelper.findActiveAccountByBearerToken(token);
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

  /** Like {@link AuthenticatedAccountAction}, but also sees the cookie session, if any. */
  @FunctionalInterface
  private interface AuthenticatedContextAction {
    void execute(AuthenticatedAccount authenticated) throws IOException, JSONException;
  }

  /**
   * Shared request template for the draft endpoints: resolves the account,
   * runs the action, and maps failures to the standard 500 responses with a
   * DAL rollback. Keeps the per-endpoint logic free of boilerplate.
   */
  private void runWithPlatformAccount(HttpServletRequest request,
      HttpServletResponse response, String actionLabel, AuthenticatedAccountAction action)
      throws IOException {
    try {
      Account account = resolvePlatformAccount(request, response);
      if (account == null) return;
      action.execute(account);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges(actionLabel, e, log);
      log.error("Platform request failed: {}", actionLabel, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("Platform request JSON error: {}", actionLabel, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void runWithAuthenticatedAccount(HttpServletRequest request,
      HttpServletResponse response, String actionLabel, AuthenticatedAccountAction action)
      throws IOException {
    runWithAuthenticatedContext(request, response, actionLabel,
        authenticated -> action.execute(authenticated.account));
  }

  private void runWithAuthenticatedContext(HttpServletRequest request,
      HttpServletResponse response, String actionLabel, AuthenticatedContextAction action)
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
      action.execute(authenticated);
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

  /**
   * GET /sws/go/onboarding/first-steps
   * Header: Authorization: Bearer &lt;session_token&gt;
   * Returns 200 with { status, firstSteps } where firstSteps is the stored First Steps
   * checklist state ({ v, seen, dismissed, completed }) or null when nothing is stored.
   */
  private void handleGetFirstSteps(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedAccount(request, response, "get onboarding first steps", account -> {
      JSONObject result = new JSONObject();
      result.put(FIELD_FIRST_STEPS, parseStoredFirstSteps(account));
      writeSuccessStatus(response, result);
    });
  }

  /**
   * POST /sws/go/onboarding/first-steps
   * Header: Authorization: Bearer &lt;session_token&gt;
   * Body: { "firstSteps": { "v": 1, "seen": true, "dismissed": false, "completed": [ ... ] } }
   * to save,
   * { "firstSteps": null } to clear.
   * Only allowlisted step ids are stored and the serialized value is capped at
   * {@link #FIRST_STEPS_MAX_LENGTH} chars (400 otherwise).
   */
  private void handleSaveFirstSteps(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedAccount(request, response, "save onboarding first steps", account -> {
      JSONObject body = readJsonBodyOrBadRequest(request, response);
      if (body == null) {
        return;
      }
      JSONObject firstSteps = body.optJSONObject(FIELD_FIRST_STEPS);
      String storedFirstSteps = null;
      if (firstSteps != null) {
        storedFirstSteps = sanitizeFirstSteps(firstSteps).toString();
        if (storedFirstSteps.length() > FIRST_STEPS_MAX_LENGTH) {
          writeError(response, HttpServletResponse.SC_BAD_REQUEST,
              "First steps payload is too large");
          return;
        }
      }
      // updateFirstSteps flushes and commits internally
      // (FirstStepsDalHelper.flushAndCommit) — no extra commit here.
      FirstStepsDalHelper.updateFirstSteps(account, storedFirstSteps);
      writeSuccessStatus(response, new JSONObject());
    });
  }

  /**
   * Keep only the known checklist shape so arbitrary client payloads are never persisted: the
   * version is forced to {@link #FIRST_STEPS_VERSION} whatever the client sent, {@code seen} and
   * {@code dismissed} are coerced to real booleans, and {@code completed} is intersected with
   * {@link #FIRST_STEPS_IDS}. Unknown ids and non-string entries are dropped silently and
   * duplicates collapse, so the stored array is always deduplicated and in allowlist order
   * regardless of the order the client sent.
   *
   * <p>Package-visible and {@code static} so it can be unit-tested directly — it reads nothing
   * but its argument and the constants above, and the same convention already applies to
   * {@code maskEmail}. See {@code EtendoGoJwtServletFirstStepsTest}.
   */
  static JSONObject sanitizeFirstSteps(JSONObject firstSteps) throws JSONException {
    JSONObject clean = new JSONObject();
    clean.put(FIELD_FIRST_STEPS_VERSION, FIRST_STEPS_VERSION);
    clean.put(FIELD_FIRST_STEPS_SEEN, firstSteps.optBoolean(FIELD_FIRST_STEPS_SEEN, false));
    clean.put(FIELD_FIRST_STEPS_DISMISSED,
        firstSteps.optBoolean(FIELD_FIRST_STEPS_DISMISSED, false));
    Set<String> requested = new HashSet<>();
    JSONArray completed = firstSteps.optJSONArray(FIELD_FIRST_STEPS_COMPLETED);
    if (completed != null) {
      for (int i = 0; i < completed.length(); i++) {
        Object entry = completed.opt(i);
        if (entry instanceof String) {
          requested.add((String) entry);
        }
      }
    }
    JSONArray cleanCompleted = new JSONArray();
    for (String stepId : FIRST_STEPS_IDS) {
      if (requested.contains(stepId)) {
        cleanCompleted.put(stepId);
      }
    }
    clean.put(FIELD_FIRST_STEPS_COMPLETED, cleanCompleted);
    return clean;
  }

  /**
   * Reads the stored First Steps JSON, tolerating a corrupt value: malformed JSON is logged as a
   * warning and reported as {@code null} instead of failing the request.
   */
  private Object parseStoredFirstSteps(Account account) {
    String storedFirstSteps = FirstStepsDalHelper.getFirstSteps(account);
    if (StringUtils.isBlank(storedFirstSteps)) {
      return JSONObject.NULL;
    }
    try {
      return new JSONObject(storedFirstSteps);
    } catch (JSONException e) {
      log.warn("Stored first steps for account {} is not valid JSON; ignoring", account.getId());
      return JSONObject.NULL;
    }
  }

  /**
   * GET /sws/go/onboarding/company-data
   * Auth: the {@code __Host-go_session} cookie (the tenant is the session's selected
   * environment), or a legacy {@code Authorization: Bearer &lt;NEO session token&gt;} (the tenant
   * is the JWT's client/org claims) — see {@link #resolveTenantSession}.
   * Returns 200 with { status, companyData } where companyData is
   * { name, tradeName, taxId, address } for the caller's own tenant — each value nullable — or
   * null when the tenant has no organisation yet. Read-only: the Organization window is where
   * these are edited.
   */
  private void handleGetCompanyData(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedContext(request, response, "get onboarding company data", authenticated -> {
      TenantSession session = resolveTenantSession(request, response, authenticated);
      if (session == null) {
        return;
      }
      OnboardingCompanyDataService.CompanyData data =
          onboardingCompanyDataService.read(session.clientId, session.orgId);
      JSONObject result = new JSONObject();
      result.put(FIELD_COMPANY_DATA, data == null ? JSONObject.NULL
          : new JSONObject()
              .put("name", nullSafe(data.getName()))
              .put("tradeName", nullSafe(data.getTradeName()))
              .put("taxId", nullSafe(data.getTaxId()))
              .put(FIELD_ADDRESS, nullSafe(data.getAddress())));
      writeSuccessStatus(response, result);
    });
  }

  /** Durable progress projection for the productive tenant's First Steps data-transfer row. */
  private void handleDemoDataTransferStatus(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    runWithAuthenticatedContext(request, response, "get demo data transfer", authenticated -> {
      TenantSession session = resolveTenantSession(request, response, authenticated);
      if (session == null) return;
      try {
        writeResponse(response, HttpServletResponse.SC_OK,
            demoDataTransferService.status(session.clientId,
                () -> EtendoGoJwtDalHelper.findOnlyFreeTenantIdByAccountEmail(
                    authenticated.account.getEmail())));
      } catch (JSONException e) {
        log.error("Could not read demo data transfer state", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
      }
    });
  }

  /** Reclaims failed or stranded transfer work; payment and tenant provisioning are never repeated. */
  private void handleDemoDataTransferRetry(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    runWithAuthenticatedContext(request, response, "retry demo data transfer", authenticated -> {
      TenantSession session = resolveTenantSession(request, response, authenticated);
      if (session == null) return;
      try {
        writeResponse(response, HttpServletResponse.SC_OK,
            demoDataTransferService.retry(session.clientId,
                EtendoGoJwtDalHelper.findOnlyFreeTenantIdByAccountEmail(
                    authenticated.account.getEmail())));
      } catch (JSONException e) {
        log.error("Could not retry demo data transfer", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
      }
    });
  }

  /** Persists checkout selection before the hosted-provider redirect loses browser state. */
  void recordDemoDataTransferSelection(JSONObject body, JSONObject checkoutResult,
      CheckoutSelection selection) {
    recordDemoDataTransferSelection(body, checkoutResult.optString(FIELD_REQUEST_ID, ""),
        selection);
  }

  /** A purchase without a demo source never records a transfer selection. */
  private void recordDemoDataTransferSelection(JSONObject body, String requestId,
      CheckoutSelection selection) {
    if (selection == null || StringUtils.isBlank(selection.demoClientId)) return;
    JSONObject transferSelection = body.optJSONObject(FIELD_DATA_TRANSFER);
    if (transferSelection == null) return;
    demoDataTransferService.recordSelection(requestId,
        transferSelection.optBoolean(FIELD_PRODUCTS), transferSelection.optBoolean(FIELD_CONTACTS));
  }

  private static String optionalDemoClientId(JSONObject body) {
    return StringUtils.trimToNull(body.optString(FIELD_DEMO_CLIENT_ID, ""));
  }

  /** Resolves purchase source from authenticated context, ignoring stale state for production. */
  private CheckoutSelection resolveCheckoutSelection(HttpServletRequest request,
      HttpServletResponse response, AuthenticatedAccount authenticated, JSONObject body)
      throws IOException {
    String sessionClientId = currentSessionClientId(request, response, authenticated);
    if (sessionClientId == null) return null;
    if (StringUtils.isBlank(sessionClientId) || ZERO_ID.equals(sessionClientId)) {
      return new CheckoutSelection(null, false, false);
    }
    if (isProductiveClient(sessionClientId)) {
      return new CheckoutSelection(null, false, false);
    }
    String demoClientId = optionalDemoClientId(body);
    if (StringUtils.isNotBlank(sessionClientId) && isFreeClient(sessionClientId)
        && demoClientId == null) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "DEMO_SELECTION_REQUIRED",
          "Select the trial environment to use for this purchase.",
          "Select the trial environment to use for this purchase.");
      return null;
    }
    if (demoClientId != null && !isOwnedFreeDemoClient(demoClientId,
        authenticated.account.getEmail())) {
      writeInvalidDemoSelection(response);
      return null;
    }
    JSONObject transfer = body.optJSONObject(FIELD_DATA_TRANSFER);
    return new CheckoutSelection(demoClientId,
        demoClientId != null && transfer != null && transfer.optBoolean(FIELD_PRODUCTS, false),
        demoClientId != null && transfer != null && transfer.optBoolean(FIELD_CONTACTS, false));
  }

  private String currentSessionClientId(HttpServletRequest request, HttpServletResponse response,
      AuthenticatedAccount authenticated) throws IOException {
    String clientId = authenticated.sessionRecord == null ? null
        : StringUtils.trimToNull(authenticated.sessionRecord.getCtxClientId());
    if (authenticated.sessionRecord == null) {
      try {
        DecodedJWT jwt = SecureWebServicesUtils.decodeToken(extractBearerToken(request));
        if (jwt != null) clientId = StringUtils.trimToNull(
            jwt.getClaim(JwtAuthUtils.CLAIM_CLIENT).asString());
      } catch (Exception e) {
        log.debug("Bearer token carries no environment context for checkout", e);
      }
    }
    if (clientId == null) return "";
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      if (!EtendoGoJwtDalHelper.clientBelongsToAccountEmail(clientId,
          authenticated.account.getEmail())) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN,
            "The session client is not owned by this account");
        return null;
      }
      return clientId;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private boolean isProductiveClient(String clientId) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      return TenantPlanService.PLAN_PRODUCTIVE.equals(tenantPlanService.resolvePlan(clientId));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private boolean isFreeClient(String clientId) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      return TenantPlanService.PLAN_FREE.equals(tenantPlanService.resolvePlan(clientId));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private boolean isOwnedFreeDemoClient(String demoClientId, String accountEmail) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      Client client = OBDal.getInstance().get(Client.class, demoClientId);
      return client != null
          && EtendoGoJwtDalHelper.clientBelongsToAccountEmail(demoClientId, accountEmail)
          && TenantPlanService.PLAN_FREE.equals(tenantPlanService.resolvePlan(demoClientId));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void writeInvalidDemoSelection(HttpServletResponse response) throws IOException {
    writeError(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_DEMO_SELECTION",
        "The selected trial environment is unavailable.",
        "The selected trial environment is unavailable.");
  }

  private static final class CheckoutSelection {
    private final String demoClientId;
    private final boolean transferProducts;
    private final boolean transferContacts;

    private CheckoutSelection(String demoClientId, boolean transferProducts,
        boolean transferContacts) {
      this.demoClientId = demoClientId;
      this.transferProducts = transferProducts;
      this.transferContacts = transferContacts;
    }
  }

  private void addDemoDataTransferSelection(JSONObject result, String requestId, String demoClientId)
      throws JSONException {
    if (StringUtils.isBlank(demoClientId)) return;
    // Always true since ETP-5480 retired the flag; kept because the upgrade page still reads it.
    result.put("dataTransferEnabled", true);
    JSONObject selection = demoDataTransferService.selection(requestId);
    if (selection != null) result.put(FIELD_DATA_TRANSFER, selection);
  }

  /** A projection failure after Stripe has created a session must not masquerade as provider 502. */
  private void addDemoDataTransferSelectionBestEffort(JSONObject result, String requestId,
      String demoClientId) {
    try {
      addDemoDataTransferSelection(result, requestId, demoClientId);
    } catch (RuntimeException | JSONException e) {
      log.error("Checkout {} was created but its transfer selection could not be projected",
          requestId, e);
    }
  }

  /** JSON-null for an absent value, so the client can tell "blank" from "not answered". */
  private static Object nullSafe(String value) {
    return value == null ? JSONObject.NULL : value;
  }

  /** The client and organization the caller is currently working in. */
  private static final class TenantSession {
    private final String clientId;
    private final String orgId;

    TenantSession(String clientId, String orgId) {
      this.clientId = clientId;
      this.orgId = orgId;
    }
  }

  /**
   * The tenant behind the presented credential.
   *
   * The onboarding endpoints authenticate an ACCOUNT, which on its own does not say which
   * environment the caller is in — an account can own several. The token the app sends from
   * inside an environment is the NEO session JWT (that is the branch
   * {@code findActiveAccountByBearerToken} resolves through the {@code user} claim), and it
   * carries the session's own client and organization. Those claims are what scope this
   * request.
   *
   * A request authenticated by the {@code __Host-go_session} cookie (ADR-0001) carries no JWT:
   * its environment is the one selected on the session record ({@code ctxClientId} /
   * {@code ctxOrgId}, written by the session environment-select flow). The request itself is
   * never a source for the client.
   *
   * The resolved client is re-checked against the account that owns it: the account gate and
   * the claim must agree, so neither a token nor a session can name a client its account does
   * not own. Answers 400 for a pure account session, which has no environment to write to.
   */
  private TenantSession resolveTenantSession(HttpServletRequest request,
      HttpServletResponse response, AuthenticatedAccount authenticated) throws IOException {
    Account account = authenticated.account;
    String clientId = null;
    String orgId = null;
    if (authenticated.sessionRecord != null) {
      clientId = authenticated.sessionRecord.getCtxClientId();
      orgId = authenticated.sessionRecord.getCtxOrgId();
    } else {
      try {
        DecodedJWT jwt = SecureWebServicesUtils.decodeToken(extractBearerToken(request));
        if (jwt != null) {
          clientId = jwt.getClaim(JwtAuthUtils.CLAIM_CLIENT).asString();
          orgId = jwt.getClaim(JwtAuthUtils.CLAIM_ORG).asString();
        }
      } catch (Exception e) {
        log.debug("Bearer token carries no NEO session claims", e);
      }
    }
    // ETP-4576 — under the cookie session there is no bearer JWT to read claims from, so the
    // block above yields nothing and this endpoint answered 400 to every cookie-session caller
    // (the whole product now). The session record carries the environment it was opened in, so
    // read the tenant from there and keep the JWT branch for legacy bearer clients.
    // `authenticate` is a pure read: it resolves the cookie and checks CSRF only for unsafe
    // methods, so calling it on this GET neither rotates the session nor writes anything.
    if (StringUtils.isBlank(clientId)) {
      GoSessionAuthResult sessionAuth =
          new GoSessionAuthenticator(goSessionService).authenticate(request);
      if (sessionAuth.isAuthenticated()) {
        clientId = sessionAuth.getRecord().getCtxClientId();
        orgId = sessionAuth.getRecord().getCtxOrgId();
      }
    }
    if (StringUtils.isBlank(clientId)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST,
          "This endpoint requires an environment session");
      return null;
    }
    if (!EtendoGoJwtDalHelper.clientBelongsToAccountEmail(clientId, account.getEmail())) {
      writeError(response, HttpServletResponse.SC_FORBIDDEN,
          "The session client is not owned by this account");
      return null;
    }
    // ETP-5455 (decision 2) — a commercially blocked environment's data is fully inaccessible,
    // not only through NEO: every account endpoint that acts on the session's tenant is refused
    // with the same 402 NEO answers. The account itself (billing, upgrade, /me) stays reachable so
    // the owner can pay. null = a tenant that predates lifecycle metadata, the legacy transition.
    EnvironmentAccessPolicy.Decision access =
        tenantEnvironmentLifecycleService.evaluateAccess(clientId, true, Instant.now());
    if (access != null && access != EnvironmentAccessPolicy.Decision.ALLOWED) {
      writeError(response, SC_PAYMENT_REQUIRED,
          "Environment access is not available: " + access.name());
      return null;
    }
    return new TenantSession(clientId, StringUtils.defaultIfBlank(orgId, "0"));
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
   * ETP-5117: a tenant converting to productive must stop overriding the System-level
   * ETSG_ForceTestMode default (e.g. a tenant that started as Demo and got its own row via
   * {@link OnboardingForceTestModeService}). Same best-effort philosophy as {@code
   * markProductive} itself — commercial/fiscal-config metadata, never allowed to abort an
   * otherwise-successful paid signup. See {@link OnboardingForceTestModeService}'s own javadoc
   * ("The reverse direction") for why this needs its own service call, not a one-liner.
   *
   * @param clientId the tenant just marked productive
   */
  private void revertTestModeForProductiveTenantBestEffort(String clientId) {
    try {
      onboardingForceTestModeService.revertTestModeForProductiveTenant(clientId);
    } catch (RuntimeException e) {
      log.error("Could not revert ETSG_ForceTestMode for now-productive tenant '{}': {}",
          clientId, e.getMessage(), e);
    }
  }

  /**
   * ETP-5117: applies the side effects of a paid upgrade once {@code handleOnboarding}'s paywall
   * has approved the request — marks the tenant productive and, only on success, reverts any
   * {@code ETSG_ForceTestMode} override (see {@link #revertTestModeForProductiveTenantBestEffort}).
   * Joins the onboarding transaction, so a successful marker commits with the tenant. Still
   * best-effort in the revert direction, mirroring {@code markProductive} itself: commercial/fiscal
   * -config metadata must never abort an otherwise-successful paid signup. A failed marker is only
   * logged — "paid but demo" is the symptom ETP-4966 was reported as, and this line is what makes it
   * searchable instead of indistinguishable from a marker that was never attempted.
   *
   * @param clientId the tenant just created/resolved
   * @param starOrgId the tenant's "*" organization id, required by {@code markProductive}
   * @param clientName the onboarding request's client name, used only for the failure log line
   * @param accountEmail the account driving onboarding, masked in the failure log line
   */
  private void applyPaidUpgradeSideEffects(String clientId, String starOrgId, String clientName,
      String accountEmail) {
    if (!tenantPlanService.markProductive(clientId, starOrgId)) {
      log.error("Paid environment '{}' (client {}) for account {} could not be marked as plan "
          + "'{}' and will read back as free", clientName, clientId,
          maskEmail(accountEmail), TenantPlanService.PLAN_PRODUCTIVE);
    } else {
      if (!tenantEnvironmentLifecycleService.markProductive(clientId)) {
        log.error("Paid environment '{}' (client {}) could not be marked in the lifecycle "
            + "projection", clientName, clientId);
      }
      revertTestModeForProductiveTenantBestEffort(clientId);
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
    long startNanos = System.nanoTime();
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
      writeEnvironmentLoginResponse(response, userId, roleListData, startNanos);

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
    OnboardingPreparation preparation = prepareOnboarding(request, response);
    if (preparation == null) {
      return;
    }
    String accountEmail = preparation.accountEmail;
    OnboardingRequestData onboardingRequest = preparation.request;
    boolean paidUpgrade = preparation.paidUpgrade;

    // Tracked across the try/catch/finally below. Provisioning has many graceful exits that
    // `return` after writing a result line rather than throwing, so the catch block alone would
    // miss most real failures — the dataset step is the common one.
    boolean provisioningCompleted = false;
    String failureReason = null;

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
      provisioningCompleted = executeOnboardingProvisioning(writer, preparation, adminPassword);

    } catch (Exception e) {
      log.error("Onboarding failed", e);
      EtendoGoDalHelper.rollbackDalChanges("onboarding", e, log);
      sendProgress(writer, PROGRESS_ERROR, PROGRESS_ERROR,
          "Onboarding failed: " + e.getMessage());
      sendFinalResult(writer, false, "Onboarding failed: " + e.getMessage());
      failureReason = e.getMessage();
    } finally {
      recordProvisioningFailureReason(paidUpgrade, provisioningCompleted, onboardingRequest,
          failureReason);
      // Stop the keepalive before the final flush so no heartbeat races the result line.
      heartbeat.shutdownNow();
      OBContext.restorePreviousMode();
      writer.flush();
      warnWhenOnboardingStreamWasLost(writer, accountEmail);
    }
  }

  private boolean executeOnboardingProvisioning(PrintWriter writer,
      OnboardingPreparation preparation, String adminPassword) throws Exception {
    OnboardingRequestData onboardingRequest = preparation.request;
    String accountId = preparation.accountId;
    String accountEmail = preparation.accountEmail;
    String currencyId = preparation.currencyId;
    boolean paidUpgrade = preparation.paidUpgrade;
    VariablesSecureApp vars = prepareAdminContext(writer, onboardingRequest.language);
    String clientId = resolveOrCreateClient(writer, vars, accountEmail, onboardingRequest,
        currencyId, adminPassword);
    if (clientId == null) return false;
    String demoSourceClientId = resolveDemoSourceClientId(paidUpgrade, onboardingRequest);
    AdminContextData adminContext = resolveAdminContextData(clientId, writer);
    if (adminContext == null) return false;
    if (paidUpgrade) {
      applyPaidUpgradeSideEffects(clientId, adminContext.starOrgId, onboardingRequest.clientName,
          accountEmail);
    }
    if (ensureOrganization(writer, onboardingRequest.clientName, clientId, adminContext,
        currencyId) == null) return false;
    String orgId = resolveOrganizationId(clientId);
    if (orgId == null) {
      sendProgress(writer, PROGRESS_DATASET, PROGRESS_ERROR,
          "Could not resolve organization for onboarding dataset import");
      sendFinalResult(writer, false, "Organization not found after onboarding");
      return false;
    }
    if (!ensureOnboardingDataset(writer, clientId, orgId, adminContext.adminUserId,
        adminContext.adminRoleId, onboardingRequest)) return false;
    if (paidUpgrade) {
      transferDemoCompanyProfile(accountEmail, demoSourceClientId, clientId, orgId);
    }
    if (!paidUpgrade && !tenantEnvironmentLifecycleService.markDemoReady(clientId, Instant.now())) {
      throw new IllegalStateException("Could not initialize demo trial lifecycle");
    }
    EtendoGoDalHelper.commitDalChanges("onboarding", log);
    completeCommittedOnboarding(accountId, accountEmail, onboardingRequest, clientId, paidUpgrade,
        preparation.provisioningClaim, demoSourceClientId);
    onboardingCostingScheduleService.activateSchedule(clientId);
    sendProgress(writer, "finalize", PROGRESS_IN_PROGRESS, "Finalizing setup...");
    sendProgress(writer, "finalize", "done", "Environment ready");
    sendFinalResult(writer, true, "Environment created successfully");
    return true;
  }

  /**
   * Revokes the demo selected on the purchase and copies its company profile into the paid
   * environment. A purchase without a demo source (productive origin) skips both.
   */
  private void transferDemoCompanyProfile(String accountEmail, String sourceClientId,
      String clientId, String orgId) {
    if (StringUtils.isBlank(sourceClientId)) {
      log.info("No demo environment was selected for paid onboarding account {}; "
          + "company profile transfer is skipped", maskEmail(accountEmail));
      return;
    }
    if (!tenantEnvironmentLifecycleService.associateDemoWithProductive(sourceClientId, clientId)) {
      throw new OBException("Could not close access to the selected demo environment after payment");
    }
    onboardingCompanyProfileTransferService.copy(sourceClientId, clientId, orgId);
    log.info("Copied company profile from demo client {} to productive client {} organization {}",
        sourceClientId, clientId, orgId);
  }

  /** A paid setup always starts from the demo persisted on its purchase, never an inferred one. */
  private static String resolveDemoSourceClientId(boolean paidUpgrade,
      OnboardingRequestData onboardingRequest) {
    return paidUpgrade ? StringUtils.trimToNull(onboardingRequest.demoClientId) : null;
  }

  private OnboardingPreparation prepareOnboarding(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    // ETP-4575 — the credential is whatever the active scheme holds, not a bearer. develop's
    // version opened with `extractBearerToken`, which is null under the cookie session, so every
    // onboarding would have answered 401. `resolveAuthenticatedAccountContext` accepts the
    // `__Host-` session and the legacy bearer alike, and hands back the resolved account, so the
    // email and the id below come from the session rather than from decoding a token.
    if (!hasAnyCredential(request)) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_AUTHORIZATION_HEADER);
      return null;
    }
    AuthenticatedAccount authenticated;
    try {
      authenticated = resolveAuthenticatedAccountContext(request, response);
    } catch (RuntimeException e) {
      log.error("Database error validating the session for onboarding", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
    if (authenticated == null) {
      return null;
    }
    String accountId = authenticated.account.getId();
    String accountEmail = authenticated.account.getEmail();
    if (rejectWhenEmailNotVerified(authenticated.account, response)) {
      return null;
    }
    OnboardingRequestData onboardingRequest = parseOnboardingRequest(request, response);
    if (onboardingRequest == null) {
      return null;
    }
    String currencyId = resolveCurrencyId(onboardingRequest.currencyIso, response);
    if (currencyId == null
        || rejectWhenPaidOnboardingIsNotOwned(accountEmail, onboardingRequest, response)) {
      return null;
    }
    PaywallOutcome paywallOutcome =
        resolveOnboardingPaywall(accountEmail, onboardingRequest, response);
    if (paywallOutcome == PaywallOutcome.REFUSED) {
      return null;
    }
    boolean paidUpgrade = paywallOutcome == PaywallOutcome.PAID;
    if (paidUpgrade && !restorePaidOnboardingSelection(onboardingRequest, accountId,
        accountEmail, response)) {
      return null;
    }
    Long provisioningClaim = claimPaidProvisioning(paidUpgrade, onboardingRequest, accountId,
        accountEmail, response);
    if (paidUpgrade && provisioningClaim == null) {
      return null;
    }
    return new OnboardingPreparation(accountId, accountEmail, onboardingRequest, currencyId,
        paidUpgrade, provisioningClaim);
  }

  private boolean restorePaidOnboardingSelection(OnboardingRequestData onboardingRequest,
      String accountId, String accountEmail, HttpServletResponse response) throws IOException {
    boolean selectionRecorded = checkoutRequestStore.hasRecordedDemoSelection(
        onboardingRequest.paymentToken, accountId, accountEmail);
    if (!selectionRecorded) {
      // Legacy paid requests never saved a source or transfer intent. Resume them as a new
      // productive environment without inferring a demo or copying/revoking any tenant.
      onboardingRequest.demoClientId = null;
      return true;
    }

    String persistedDemoClientId = checkoutRequestStore.findDemoClientId(
        onboardingRequest.paymentToken, accountId, accountEmail);
    Set<String> currentFreeDemoClientIds = findFreeDemoClientIdsForAccount(accountEmail);
    try {
      onboardingRequest.demoClientId = resolvePaidDemoClientId(true, persistedDemoClientId,
          currentFreeDemoClientIds);
    } catch (IllegalArgumentException e) {
      writeError(response, HttpServletResponse.SC_CONFLICT, "DEMO_SELECTION_REQUIRED",
          e.getMessage(), e.getMessage());
      return false;
    }
    return true;
  }

  /** Resolves a paid purchase's fixed demo source, or null for a legacy purchase without one. */
  static String resolvePaidDemoClientId(boolean selectionRecorded, String persistedDemoClientId) {
    if (!selectionRecorded) {
      return null;
    }
    return StringUtils.trimToNull(persistedDemoClientId);
  }

  static String resolvePaidDemoClientId(boolean selectionRecorded, String persistedDemoClientId,
      Set<String> currentFreeDemoClientIds) {
    String selected = resolvePaidDemoClientId(selectionRecorded, persistedDemoClientId);
    if (selected == null) return null;
    if (currentFreeDemoClientIds == null || !currentFreeDemoClientIds.contains(selected)) {
      throw new IllegalArgumentException(
          "The demo selected for this paid setup is no longer available. Contact support before retrying.");
    }
    return selected;
  }

  private Set<String> findFreeDemoClientIdsForAccount(String accountEmail) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      return EtendoGoJwtDalHelper.findFreeTenantIdsByAccountEmail(accountEmail);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Claims the paid request for provisioning, refusing the call when another already holds it.
   *
   * <p>Claimed before the NDJSON stream opens, for the same reason the paywall is: a refusal must
   * answer with plain JSON and leave nothing half-built. The claim is a conditional update, so of
   * two concurrent calls for one payment exactly one proceeds — that is the
   * reload-during-provisioning case, where the {@code ?checkout=success} URL stays live for the
   * whole slow run. A free environment claims nothing and is never refused here.
   *
   * @param paidUpgrade whether this environment was bought rather than free
   * @param onboardingRequest the parsed onboarding request
   * @param accountEmail authenticated account email
   * @param response response the refusal is written to
   * @return true when the request was refused and the caller must stop
   */
  private Long claimPaidProvisioning(boolean paidUpgrade,
      OnboardingRequestData onboardingRequest, String accountId, String accountEmail,
      HttpServletResponse response)
      throws IOException {
    if (!paidUpgrade) {
      return null;
    }
    if (checkoutRequestStore.claimForProvisioning(onboardingRequest.paymentToken, accountId,
        accountEmail)) {
      Long attempt = checkoutRequestStore.findProvisioningAttempt(onboardingRequest.paymentToken,
          accountId, accountEmail);
      // A successful conditional update always has a persisted attempt. Keep the success path
      // recoverable even if a legacy database omits that value.
      return attempt == null ? 0L : attempt;
    }
    writeError(response, HttpServletResponse.SC_CONFLICT, "PROVISIONING_ALREADY_IN_PROGRESS",
        "Setup is still running for this environment",
        "Setup is still running for this environment. Refresh its status before trying again.");
    return null;
  }

  /**
   * Logs an onboarding stream that was dropped before its result line reached the browser.
   *
   * <p>{@link PrintWriter} swallows {@code IOException} (broken pipe): when CloudFront or any
   * proxy hits its response timeout it silently drops the client mid-stream while the backend
   * keeps running to completion (and commits). {@code checkError()} is the only way to detect it.
   * Surfaced explicitly so it stops being invisible in the logs.
   *
   * @param writer the NDJSON stream writer, already flushed
   * @param accountEmail authenticated account email, masked before it is logged
   */
  private void warnWhenOnboardingStreamWasLost(PrintWriter writer, String accountEmail) {
    if (writer.checkError()) {
      log.warn("Onboarding stream to client was lost before the result line was delivered "
          + "(likely a CloudFront/proxy response timeout). The environment may have been "
          + "created successfully server-side, but the UI will report a false failure. "
          + "accountEmail={}", maskEmail(accountEmail));
    }
  }

  /**
   * Bookkeeping that may only run once the tenant itself has committed.
   *
   * <p>Extracted from {@code handleOnboarding} to keep that method readable; the order of these
   * steps is load-bearing and unchanged. Everything here is best-effort in the same spirit as
   * {@link #applyPaidUpgradeSideEffects}: a tenant may commit with its checkout row unclosed
   * rather than have provisioning rolled back over bookkeeping, and {@code DERIVED_STATUS}
   * surfaces the gap as {@code STALLED} either way. Anything that does throw still reaches the
   * caller's catch exactly as before.
   *
   * @param token bearer token of the onboarding request
   * @param accountEmail authenticated account email
   * @param onboardingRequest the parsed onboarding request
   * @param clientId {@code AD_CLIENT_ID} of the environment just provisioned
   * @param paidUpgrade whether this environment was bought rather than free
   */
  private void completeCommittedOnboarding(String accountId, String accountEmail,
      OnboardingRequestData onboardingRequest, String clientId, boolean paidUpgrade,
      Long provisioningClaim, String demoSourceClientId) {
    if (paidUpgrade) {
      try {
        checkoutRequestStore.recordProvisioned(onboardingRequest.paymentToken, clientId,
            provisioningClaim);
      } catch (RuntimeException e) {
        log.error("Environment '{}' (client {}) was provisioned but its checkout request could "
            + "not be closed", onboardingRequest.clientName, clientId, e);
      }
      startDemoDataTransferBestEffort(onboardingRequest.paymentToken, demoSourceClientId,
          clientId, accountId, accountEmail);
    }
    Account account = findAccountForCommittedOnboarding(accountId, accountEmail);
    clearOnboardingDraftBestEffort(account);
    String normalizedLanguage = StringUtils.trimToNull(onboardingRequest.language);
    sendAuthEmailBestEffort("environment-ready",
        () -> authEmailSender.sendEnvironmentReady(account, clientId, normalizedLanguage));
  }

  /**
   * Starts the demo-to-productive transfer for a freshly provisioned paid tenant. Does nothing
   * when the purchase has no demo source. Best-effort in its own right: a failure here is logged
   * as a transfer failure, never as an unclosed checkout, and never reaches the onboarding caller.
   *
   * @param paymentToken the checkout request id the selection was recorded under
   * @param demoClientId exact source selected on the checkout request
   * @param clientId {@code AD_CLIENT_ID} of the productive environment just provisioned
   */
  void startDemoDataTransferBestEffort(String paymentToken, String demoClientId, String clientId,
      String accountId, String accountEmail) {
    if (StringUtils.isBlank(demoClientId)) return;
    try {
      CheckoutRequestStore.TransferSelection selection = checkoutRequestStore
          .findTransferSelection(paymentToken, accountId, accountEmail);
      if (selection != null) {
        demoDataTransferService.recordSelection(paymentToken, selection.isProducts(),
            selection.isContacts());
      }
      demoDataTransferService.start(paymentToken, demoClientId, clientId);
    } catch (RuntimeException e) {
      log.error("Environment (client {}) was provisioned but its demo data transfer could not be "
          + "started", clientId, e);
    }
  }

  /**
   * Annotates a paid checkout request that did not finish provisioning.
   *
   * <p>Annotation only, and deliberately never a status change: the row stays where provisioning
   * left it so {@code DERIVED_STATUS} reports {@code STALLED}, rather than being marked terminal
   * by a path that may itself be failing. {@code recordFailureReason} swallows its own errors for
   * the same reason — a failed annotation must not turn one problem into two. Called from the
   * onboarding {@code finally}, so it runs on every exit including the graceful ones that return
   * after writing a result line.
   *
   * @param paidUpgrade whether this environment was bought rather than free
   * @param provisioningCompleted whether provisioning reached its final result line
   * @param onboardingRequest the parsed onboarding request
   * @param failureReason the exception message when one was caught, otherwise null
   */
  private void recordProvisioningFailureReason(boolean paidUpgrade, boolean provisioningCompleted,
      OnboardingRequestData onboardingRequest, String failureReason) {
    if (!paidUpgrade || provisioningCompleted) {
      return;
    }
    checkoutRequestStore.recordFailureReason(onboardingRequest.paymentToken,
        failureReason != null ? failureReason : "Provisioning did not complete");
  }

  /**
   * Paywall (ETP-4686). Runs before the NDJSON stream opens and before any provisioning, so a
   * refused request leaves no half-created tenant behind and can still answer with a plain JSON
   * error instead of a stream. The backend is authoritative here: the /upgrade page in the web
   * client shows the checkout, but this check is what actually gates tenant creation.
   *
   * @return {@link PaywallOutcome#REFUSED} when the caller must stop (the error response is
   *     already written), otherwise whether the environment is free or paid
   */
  private PaywallOutcome resolveOnboardingPaywall(String accountEmail,
      OnboardingRequestData onboardingRequest, HttpServletResponse response) throws IOException {
    try {
      TenantPaywallService.Outcome paywall = evaluatePaywall(accountEmail, onboardingRequest);
      if (paywall.getDecision().isBlocked()) {
        writePaymentRequiredError(response, paywall.getDecision());
        return PaywallOutcome.REFUSED;
      }
      return paywall.isProductive() ? PaywallOutcome.PAID : PaywallOutcome.FREE;
    } catch (RuntimeException e) {
      log.error("Paywall evaluation failed for onboarding", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
      return PaywallOutcome.REFUSED;
    }
  }

  /** Paid retries are billing mutations too and require a server-marked environment owner. */
  private boolean rejectWhenPaidOnboardingIsNotOwned(String accountEmail,
      OnboardingRequestData onboardingRequest, HttpServletResponse response) throws IOException {
    if (StringUtils.isBlank(onboardingRequest.paymentToken)) {
      return false;
    }
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    boolean hasEnvironments;
    boolean billingOwner;
    try {
      hasEnvironments = EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail(accountEmail) > 0;
      billingOwner = EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail(accountEmail);
    } finally {
      OBContext.restorePreviousMode();
    }
    if (hasEnvironments && !billingOwner) {
      writeError(response, HttpServletResponse.SC_FORBIDDEN, BILLING_OWNER_REQUIRED,
          BILLING_OWNER_MESSAGE, BILLING_OWNER_MESSAGE);
      return true;
    }
    return false;
  }

  /**
   * What the paywall decided for one onboarding request. An enum rather than a nullable
   * {@code Boolean}: "refused" and "free" are different answers, and encoding one of them as null
   * makes the caller carry a three-state Boolean that unboxes to an NPE the day someone forgets
   * the null check (java:S2447).
   */
  private enum PaywallOutcome {
    /** Refused, or the evaluation itself failed. The error response is already written. */
    REFUSED,
    /** Allowed. A free environment — the tenant is not marked productive. */
    FREE,
    /** Allowed. A paid environment — the tenant is marked productive after provisioning. */
    PAID
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
      return tenantPaywallService.evaluate(ownsEnvironment, resuming, false,
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

  private void writeEnvironmentLoginResponse(HttpServletResponse response, String userId,
      EtendoGoJwtSupport.RoleListData roleListData, long startNanos) throws Exception {
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    try {
      User user = OBDal.getInstance().get(User.class, userId);
      if (user == null) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, "User not found");
        return;
      }
      Role role = roleListData.getFirstRoleId() != null
          ? OBDal.getInstance().get(Role.class, roleListData.getFirstRoleId())
          : null;
      String jwtToken = SecureWebServicesUtils.generateToken(user, role);

      JSONObject result = new JSONObject();
      result.put(FIELD_TOKEN, jwtToken);
      result.put(FIELD_ROLE_LIST, roleListData.getRoleArray());
      writeResponse(response, HttpServletResponse.SC_OK, result);
      recordLegacyEnvironmentLogin(jwtToken, startNanos);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-5462: {@code session.login} for the cookie-session path, from the ids the rotated session
   * now stores. Last on the success path and never throws: a failure here must not reach the
   * caller's catch blocks, which would write an error over the answer already sent.
   */
  private static void recordCookieEnvironmentLogin(GoSessionRecord entered, long startNanos) {
    try {
      SessionLoginUsage.recordLogin(SessionLoginUsage.ACTION_COOKIE_LOGIN, entered.getCtxClientId(),
          entered.getCtxOrgId(), entered.getUserId(), entered.getRoleId(),
          entered.getAuthMethod(), startNanos);
    } catch (Exception e) { // NOSONAR — usage recording must not affect the login.
      log.debug("Could not record the cookie environment login usage event.", e);
    }
  }

  /**
   * ETP-5462: {@code session.login} for the legacy bearer path. The ids come from the claims of the
   * environment JWT just issued (decoded, not re-verified: it is ours and a microsecond old), the
   * same source the cookie path stores in its session. Runs after the response is written and never
   * throws, so it cannot turn a successful login into an error.
   */
  private static void recordLegacyEnvironmentLogin(String jwtToken, long startNanos) {
    try {
      DecodedJWT claims = JWT.decode(jwtToken);
      SessionLoginUsage.recordLogin(SessionLoginUsage.ACTION_LOGIN,
          claims.getClaim(PROGRESS_CLIENT).asString(),
          claims.getClaim(PROGRESS_ORGANIZATION).asString(),
          claims.getClaim("user").asString(),
          claims.getClaim("role").asString(),
          null, startNanos);
    } catch (Exception e) { // NOSONAR — usage recording must not affect the login.
      log.debug("Could not record the legacy environment login usage event.", e);
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
      data.currencyIso = body.optString(FIELD_CURRENCY, "EUR").trim();
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
      if ("convert-demo".equalsIgnoreCase(data.upgradeAction)) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST,
            "Demo environments cannot be converted; create a new productive environment");
        return null;
      }
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
      if (!validateOnboardingTaxId(response, data)) {
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

  /**
   * ETP-5190 — rejects a malformed fiscal identifier at signup, the first of the two moments a
   * tenant sets one (the other is the Organization window, guarded by
   * {@code OrganizationInformationHandler}). Both run {@link SpanishTaxIdValidator}.
   *
   * <p>Validated HERE, alongside the length checks, for the reason those are here: past this
   * point the NDJSON provisioning stream is open, and a rejection halfway through tenant
   * creation reaches the user as the opaque "@CreateClientFailed@" (ETP-4665).
   *
   * <p>Blank stays acceptable — the wizard marks the field optional and
   * {@code wireOrgInfo()} only persists a non-blank value. Only a value the user actually
   * typed, and typed wrong, is refused.
   *
   * <p>Gated on the requested country: these are Spanish rules, and {@code countryCode}
   * defaults to {@code ES} a few lines above, so today every signup is covered — but a payload
   * naming another country must not be judged by them.
   *
   * @return {@code true} to continue; {@code false} once an error response has been written
   */
  private boolean validateOnboardingTaxId(HttpServletResponse response,
      OnboardingRequestData data) throws IOException {
    if (!SpanishTaxIdValidator.SPAIN_COUNTRY_CODE.equalsIgnoreCase(data.countryCode)) {
      return true;
    }
    switch (SpanishTaxIdValidator.validate(data.taxId)) {
      case BAD_FORMAT:
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, SpanishTaxIdValidator.ERR_FORMAT);
        return false;
      case BAD_CHECK_DIGIT:
        writeError(response, HttpServletResponse.SC_BAD_REQUEST,
            SpanishTaxIdValidator.ERR_CHECK_DIGIT);
        return false;
      default:
        return true;
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

  /**
   * Resolves an owned client for resume, or creates one and returns the exact ID recorded by
   * {@link InitialClientSetup} in the session. The created path must not resolve by name again:
   * a second name lookup could return a different client than the one just provisioned.
   */
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
    String createdClientId = createClient(vars, currencyId, requestData.clientName, clientUser,
        adminPassword, writer);
    if (createdClientId == null) {
      return null;
    }
    // InitialClientSetup names the admin AD_User after its username (the email).
    // Override it with the full name entered during onboarding so the app shows
    // the person's name instead of their email. No-op when fullName is blank.
    EtendoGoJwtSupport.applyClientAdminDisplayName(clientUser, requestData.fullName);
    // ETP-5019 — InitialClientSetup's underlying insertUser() never sets AD_User.Email at all
    // (only Name/Description/Username), so the owner's "Correo electrónico" field renders empty
    // in the Users window. Backfill it from the real account email (not clientUser, which may
    // carry a client-name suffix) right after creation, same best-effort pattern as the display
    // name override above.
    EtendoGoJwtSupport.applyClientAdminEmail(clientUser, accountEmail);
    // InitialClientSetup records the exact client it created in the session. Carry that ID
    // forward instead of resolving by name again, which could select a different same-named
    // client if the name lookup changes after creation.
    return createdClientId;
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

  private String createClient(VariablesSecureApp vars, String currencyId, String clientName,
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
      return null;
    }
    String createdClientId = StringUtils.trimToNull(vars.getSessionValue("AD_Client_ID"));
    if (createdClientId == null) {
      String errorMessage = "Client creation succeeded but did not return the created client ID";
      log.error("Client creation for '{}' returned success without AD_Client_ID in session",
          clientName);
      sendProgress(writer, PROGRESS_CLIENT, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage, ERROR_CODE_CLIENT_CREATION_FAILED);
      return null;
    }
    sendProgress(writer, PROGRESS_CLIENT, "done", "Client created successfully");
    return createdClientId;
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
    markTenantOwnerBestEffort(clientId, data.adminUserId);
    return data;
  }

  /**
   * ETP-4830 — flags {@code adminUserId} as {@code clientId}'s owner (see {@link
   * OwnerSupport#markAsOwnerIfNoneExists}), the very first time this resolves for a brand-new
   * client: at this exact point in the provisioning chain (right after {@link #createClient}
   * created the client's real, single {@code AD_User} and BEFORE {@link
   * #importOnboardingDataset} brings in the GOClient sample dataset's own {@code AD_User} rows —
   * see {@code referencedata/sampledata/GOClient/AD_USER.xml}), {@code adminUserId} is
   * unambiguously the one true founder, never a bundled sample/demo user. {@link
   * OwnerSupport#markAsOwnerIfNoneExists} is itself idempotent (no-op once an owner already
   * exists for the client), so calling this on every resumed/retried onboarding pass — this
   * method runs on both the create AND the resume path — is safe and never re-assigns or moves
   * ownership.
   *
   * <p>Best-effort by design (ETP-4830 scope decision): a failure here must never fail the
   * onboarding chain — every owner-protection check downstream ({@code
   * UserRoleAssignmentHandler}/{@code UserRoleCompositionService}) already treats a
   * false/unset {@code is_owner} as "guard never triggers", so a tenant that failed to get an
   * owner marked here simply ships with no owner-lock yet, exactly like every pre-existing
   * tenant from before this column existed.</p>
   */
  private void markTenantOwnerBestEffort(String clientId, String adminUserId) {
    try {
      OwnerSupport.markAsOwnerIfNoneExists(clientId, adminUserId);
    } catch (RuntimeException e) {
      log.warn("markTenantOwnerBestEffort: failed to flag owner for client {} user {}: {}",
          clientId, adminUserId, e.getMessage(), e);
    }
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
    // Depends on AD_ORGINFO already being located by wireOrgInfo above.
    if (!wireWarehouseAddress(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    if (!scheduleCostingBackground(writer, clientId, orgId, adminUserId, adminRoleId)) {
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
    // ETP-4999 (gap M1): wire the onboarding admin's own session defaults to the REAL business
    // org, not the root/wildcard '0' InitialClientSetup left them at. Runs AFTER the org and its
    // warehouse both exist (step 1) and BEFORE the baseline stamp — see
    // OnboardingAdminIdentityService for the full root-cause explanation (including why this does
    // NOT touch AD_User_Roles) and its lockstep corrective twin (R26-admin-identity-real-org.sql).
    if (!wireAdminIdentity(writer, clientId, orgId, adminUserId, adminRoleId)) {
      return false;
    }
    // ETP-5117 (gap N1): force SII/TicketBAI/VeriFactu into test/sandbox mode for Demo/free
    // tenants, so no manual step in Classic is needed to trial the fiscal submission modules.
    // Runs AFTER the org exists (needed as the new preference row's visibility scope) and BEFORE
    // the baseline stamp — see OnboardingForceTestModeService for the full explanation (including
    // why it must never touch the System-level default preference row) and its lockstep
    // corrective twin (R31-force-test-mode-demo-tenants.sql).
    if (!forceTestModeForFreeTenant(writer, clientId, orgId)) {
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

  boolean wireWarehouseAddress(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_WAREHOUSE_ADDRESS, PROGRESS_IN_PROGRESS,
        "Aligning warehouse address...");
    try {
      onboardingWarehouseAddressService.alignDefaultWarehouseAddress(clientId, orgId, adminUserId,
          adminRoleId);
      sendProgress(writer, PROGRESS_WAREHOUSE_ADDRESS, "done", "Warehouse address aligned");
      return true;
    } catch (Exception e) {
      log.error("Error during warehouse-address alignment", e);
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Warehouse address alignment failed";
      sendProgress(writer, PROGRESS_WAREHOUSE_ADDRESS, PROGRESS_ERROR, errorMessage);
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
   * Wires the onboarding admin's session defaults to the real business organization (ETP-4999,
   * gap M1) — see {@link OnboardingAdminIdentityService} for the full explanation and its
   * corrective twin ({@code R26-admin-identity-real-org.sql}).
   */
  boolean wireAdminIdentity(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_ADMIN_IDENTITY, PROGRESS_IN_PROGRESS,
        "Wiring admin identity to organization...");
    try {
      onboardingAdminIdentityService.wireAdminIdentity(clientId, orgId, adminUserId, adminRoleId);
      sendProgress(writer, PROGRESS_ADMIN_IDENTITY, "done", "Admin identity wired");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding admin-identity wiring", e, log);
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Admin identity wiring failed";
      sendProgress(writer, PROGRESS_ADMIN_IDENTITY, PROGRESS_ERROR, errorMessage);
      sendFinalResult(writer, false, errorMessage);
      return false;
    }
  }

  /**
   * Forces SII/TicketBAI/VeriFactu submissions into test/sandbox mode for a Demo/free tenant
   * (ETP-5117, gap N1) — see {@link OnboardingForceTestModeService} for the full explanation and
   * its corrective twin ({@code R31-force-test-mode-demo-tenants.sql}).
   */
  boolean forceTestModeForFreeTenant(PrintWriter writer, String clientId, String orgId) {
    sendProgress(writer, PROGRESS_FORCE_TEST_MODE, PROGRESS_IN_PROGRESS,
        "Configuring fiscal test mode...");
    try {
      onboardingForceTestModeService.forceTestModeForFreeTenant(clientId, orgId);
      sendProgress(writer, PROGRESS_FORCE_TEST_MODE, "done", "Fiscal test mode configured");
      return true;
    } catch (Exception e) {
      EtendoGoDalHelper.rollbackDalChanges("onboarding force-test-mode", e, log);
      String errorMessage = e.getMessage() != null ? e.getMessage()
          : "Fiscal test mode configuration failed";
      sendProgress(writer, PROGRESS_FORCE_TEST_MODE, PROGRESS_ERROR, errorMessage);
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
   * Creates the per-client 5-minute costing schedule, backed by core's "Costing Background process"
   * (idempotent). Onboarding already imports a VALIDATED costing rule, so without this schedule the
   * rule sits there and no cost is ever calculated. Non-fatal: a missing costing schedule is worth
   * a log line, never a failed environment creation. The Quartz job is activated after the commit
   * (see {@code handleOnboarding}); even if that activation does not run, the {@code SCH} row is
   * picked up on the next scheduler initialization.
   */
  boolean scheduleCostingBackground(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    sendProgress(writer, PROGRESS_COSTING_SCHEDULE, PROGRESS_IN_PROGRESS,
        "Scheduling automatic cost calculation...");
    try {
      onboardingCostingScheduleService.scheduleCostingBackground(clientId, orgId, adminUserId,
          adminRoleId);
      sendProgress(writer, PROGRESS_COSTING_SCHEDULE, "done", "Automatic cost calculation scheduled");
    } catch (Exception e) {
      log.warn("Could not schedule cost calculation for client {}: {}", clientId, e.getMessage());
      sendProgress(writer, PROGRESS_COSTING_SCHEDULE, "done", "Automatic cost calculation skipped");
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
   * @deprecated logic moved to {@link PasswordHasher#hash} (ETP-4829, so other callers could
   *     hash passwords the same way without depending on this servlet); kept as a thin delegate
   *     so every existing call site here is unchanged.
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

  /**
   * Issues a password link and mails it out, picking the contract from whether the account already
   * has a local password: {@code reset-password} when it does, {@code set-password} when it does
   * not. The link, the token and its expiry are identical either way — only the copy changes.
   *
   * @param account the account requesting the link
   * @param appBaseUrl configured public app base URL, null when none is configured
   * @param hasLocalPassword whether the account already has a password to restore
   */
  private void storeResetTokenAndSendEmail(Account account, String appBaseUrl,
      boolean hasLocalPassword) {
    String branch = hasLocalPassword ? "reset" : "enrol";
    EtendoGoJwtDalHelper.PasswordResetTokenState previousTokenState =
        EtendoGoJwtDalHelper.capturePasswordResetToken(account);
    String resetToken = generateSecureUrlToken();
    String resetTokenHash = hashAuthToken(resetToken);
    Date expiresAt = Date.from(Instant.now().plusSeconds(PASSWORD_RESET_TTL_SECONDS));
    EtendoGoJwtDalHelper.storePasswordResetToken(account, resetTokenHash, expiresAt);

    boolean emailSent = false;
    String resetLink = EtendoGoAuthLinkBuilder.resetPasswordLink(resetToken, appBaseUrl);
    if (resetLink == null) {
      log.warn("Auth email {} skipped because the public app base URL is not configured", branch);
    } else {
      try {
        emailSent = hasLocalPassword
            ? authEmailSender.sendPasswordReset(account, resetTokenHash, resetLink, expiresAt)
            : authEmailSender.sendSetPassword(account, resetTokenHash, resetLink, expiresAt);
      } catch (RuntimeException e) {
        log.warn("Auth email {} failed after token storage", branch, e);
      }
    }
    if (!emailSent) {
      EtendoGoJwtDalHelper.restorePasswordResetToken(account, previousTokenState);
    }
    logPasswordResetBranch(emailSent ? branch : branch + "-not-sent", account.getEmail());
  }

  /**
   * Records which branch a password-reset request took.
   *
   * <p>ETP-5115. The neutral response is deliberate and stays, but it means one answer hides
   * several outcomes, and until now only two of them left even a warning — so nobody could tell
   * "it did not arrive" from "it was never sent", which is precisely what the AUTH-05 finding asked
   * for. The address is masked: this is a diagnostic, not a record of who asked to reset what.
   *
   * @param branch what happened: no-account, reset, enrol, or either of the latter not sent
   * @param email the requested address, logged masked
   */
  private void logPasswordResetBranch(String branch, String email) {
    log.info("Password reset request resolved to branch {} for {}", branch, maskEmail(email));
  }

  /**
   * ETP-4798: issues a fresh email-verification token and mails the link out, used both at
   * registration ({@code welcome} true, the link rides inside the welcome mail) and on an explicit
   * re-send ({@code welcome} false, its own {@code verify-email} message).
   *
   * <p><strong>Fails open only when there was nothing to fall back to.</strong> Issuing a token
   * overwrites whatever was pending, so the previous state is captured first and put back when the
   * mail does not go out. That distinction is the whole point: on a first issue at {@code /register}
   * there is no previous token, so a failed send leaves the account reading as "nothing pending" and
   * {@link #rejectWhenEmailNotVerified} lets it through — a misconfigured provider or an unset
   * {@code etendo.go.app.baseUrl} must not silently lock every new signup out of creating an
   * environment. On a re-send there IS a previous token, and restoring it keeps the account gated
   * and keeps the link already sitting in the user's inbox working. Clearing it there instead
   * would hand anyone a way to switch the gate off simply by pressing "resend" until the
   * per-recipient throttle refuses the send. Same capture/restore pair
   * {@link #storeResetTokenAndSendEmail} uses for the reset token.
   *
   * @param welcome true to fold the link into the {@code new-account} welcome mail, false to send
   *     the standalone {@code verify-email} reminder
   */
  private void issueEmailVerification(Account account, String language, boolean welcome) {
    EmailVerificationDalHelper.EmailVerifyTokenState previousTokenState =
        EmailVerificationDalHelper.captureEmailVerifyToken(account);
    boolean tokenStored = false;
    try {
      String verifyToken = generateSecureUrlToken();
      String verifyTokenHash = hashAuthToken(verifyToken);
      String verifyLink = EtendoGoAuthLinkBuilder.verifyEmailLink(verifyToken);
      if (verifyLink == null) {
        log.warn("Email verification skipped because the public app base URL "
            + "({}) is not configured", PublicUrlResolver.APP_BASE_URL_PROPERTY);
        if (welcome) {
          sendAuthEmailBestEffort(CONTRACT_NEW_ACCOUNT,
              () -> authEmailSender.sendNewAccount(account, language));
        }
        return;
      }

      // ETP-5003 — the same expiry the token is stored with is handed to the email, so the copy
      // states the window the server actually grants instead of repeating a constant.
      Date verifyExpiresAt = Date.from(Instant.now().plusSeconds(EMAIL_VERIFICATION_TTL_SECONDS));
      EmailVerificationDalHelper.storeEmailVerifyToken(account, verifyTokenHash, verifyExpiresAt);
      tokenStored = true;

      boolean emailSent = welcome
          ? authEmailSender.sendNewAccount(account, language, verifyLink, verifyExpiresAt)
          : authEmailSender.sendVerifyEmail(account, verifyTokenHash, verifyLink, language,
              verifyExpiresAt);
      if (emailSent) {
        return;
      }
    } catch (RuntimeException e) {
      // Swallowed on purpose, and this is the whole reason the method owns its own try: it runs
      // AFTER the account transaction has already committed. Letting the exception escape would
      // reach handleRegister's catch, roll back nothing that matters, and answer "registration
      // failed" for an account that exists — leaving the user unable to retry (the address is now
      // taken) and unable to log in to the account they just created.
      log.warn("Email verification could not be issued", e);
    }
    if (tokenStored) {
      revertUnusableEmailVerifyToken(account, previousTokenState);
    }
  }

  /**
   * Undoes a token re-issue whose mail never went out, putting back whatever the account held
   * before it.
   *
   * <p>When a confirmation was already pending, the previous token comes back: the account stays
   * gated and the link already in the user's inbox keeps working. When nothing was pending this
   * restores to "no token", leaving the account ungated rather than blocked behind a link nobody
   * can click.
   */
  private void revertUnusableEmailVerifyToken(Account account,
      EmailVerificationDalHelper.EmailVerifyTokenState previousTokenState) {
    boolean hadPendingToken = previousTokenState != null && previousTokenState.hasToken();
    try {
      EmailVerificationDalHelper.restoreEmailVerifyToken(account, previousTokenState);
    } catch (RuntimeException e) {
      log.error("Could not revert the unusable email verification token; this account may be gated "
          + "out of creating an environment with no deliverable confirmation link", e);
      return;
    }
    if (hadPendingToken) {
      log.warn("Email verification re-send could not be delivered; the previously issued token was "
          + "restored, so the account stays gated and its earlier link still works");
    } else {
      log.warn("Email verification token dropped because its mail could not be sent — "
          + "the account is left ungated rather than locked out");
    }
  }

  /**
   * ETP-4798 gate. Returns true (and has written the 403) when this account still owes an email
   * confirmation. Any failure resolving that answers false: an infrastructure problem on our side
   * must not be what stops a paying user from creating their environment.
   */
  private boolean rejectWhenEmailNotVerified(Account account, HttpServletResponse response)
      throws IOException {
    boolean pending = false;
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      pending = EmailVerificationDalHelper.isEmailVerificationPending(account);
    } catch (RuntimeException e) {
      log.error("Could not check the email verification state for onboarding; allowing the "
          + "request through", e);
      return false;
    } finally {
      OBContext.restorePreviousMode();
    }
    if (pending) {
      writeError(response, HttpServletResponse.SC_FORBIDDEN, CODE_EMAIL_NOT_VERIFIED,
          EMAIL_NOT_VERIFIED_MESSAGE, EMAIL_NOT_VERIFIED_MESSAGE);
    }
    return pending;
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

  /** Cryptographically random, URL-safe token for a mailed one-shot link (reset, verification). */
  private String generateSecureUrlToken() {
    byte[] token = new byte[32];
    new SecureRandom().nextBytes(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  /** SHA-256 of a mailed token. Only the digest is ever persisted. */
  private String hashAuthToken(String token) {
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

  private void writeEmailVerifyNeutralResponse(HttpServletResponse response)
      throws IOException {
    try {
      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_MESSAGE, EMAIL_VERIFY_NEUTRAL_MESSAGE);
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (JSONException e) {
      log.error("JSON error building email verification resend response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    }
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
    long startNanos = System.nanoTime();
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
      result.put(FIELD_ROLE_LIST, roleListData.getRoleArray());
      result.put(FIELD_CSRF_TOKEN, rotated.getCsrfToken());
      writeResponse(response, HttpServletResponse.SC_OK, result);
      recordCookieEnvironmentLogin(rotated.getRecord(), startNanos);
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
    String roleId = requestedRoleId.isEmpty() ? roleListData.getFirstRoleId() : requestedRoleId;
    JSONObject selectedRole = findRole(roleListData.getRoleArray(), roleId);
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
   * Rebinds the session to a role its user still holds, when its current one was revoked.
   *
   * @return {@code false} when the role was revoked and the user holds no other valid role
   */
  private boolean reconcileSessionRole(GoSessionRecord sessionRecord) {
    try {
      sessionRoleReconciler.reconcile(sessionRecord);
      return true;
    } catch (SessionRoleRevokedException e) {
      return false;
    }
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

      // A promote/demote since the environment was entered: report (and persist) the role the
      // user holds now, or a reload restores a role that is gone and lands on "no access".
      if (!reconcileSessionRole(sessionRecord)) {
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
    return EtendoGoJwtSupport.loadRoleListData(sessionRecord.getUserId()).getRoleArray();
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

  private static final class OnboardingPreparation {
    private final String accountId;
    private final String accountEmail;
    private final OnboardingRequestData request;
    private final String currencyId;
    private final boolean paidUpgrade;
    private final Long provisioningClaim;

    private OnboardingPreparation(String accountId, String accountEmail,
        OnboardingRequestData request, String currencyId, boolean paidUpgrade,
        Long provisioningClaim) {
      this.accountId = accountId;
      this.accountEmail = accountEmail;
      this.request = request;
      this.currencyId = currencyId;
      this.paidUpgrade = paidUpgrade;
      this.provisioningClaim = provisioningClaim;
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
    // ETGO_CHECKOUT_REQUEST (CheckoutRequestStore): a token the Stripe webhook confirmed is what
    // makes the resulting environment productive (ETP-4966, durable since ETP-5045).
    private String paymentToken;
    // Resolved only from the account-scoped checkout row; onboarding never trusts a browser value.
    private String demoClientId;
    private String upgradeAction;
  }

  private static class AdminContextData {
    private String adminRoleId;
    private String adminUserId;
    private String starOrgId;
  }
}
