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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.LocalDate;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialClientSetup;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetup;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.BoundedJsonParser;
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
import com.etendoerp.go.payment.StripeCurrencyScale;
import com.etendoerp.go.payment.StripePriceService;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.CheckoutRequestWrites;
import com.etendoerp.go.payment.PausedSubscriptionAlert;
import com.etendoerp.go.payment.StripeEventTypes;
import com.etendoerp.go.payment.ProvisioningFailureReason;
import com.etendoerp.go.payment.EnvironmentPlanCache;
import com.etendoerp.go.payment.PlanCatalogService;
import com.etendoerp.go.payment.PlanNotAvailableException;
import com.etendoerp.go.payment.SubscriptionModelSwitch;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.SubscriptionEventOutcome;
import com.etendoerp.go.payment.StripeJson;
import com.etendoerp.go.payment.SubscriptionLifecycleApplier;
import com.etendoerp.go.payment.EnvironmentAccessGuard;
import com.etendoerp.go.payment.SystemContext;
import com.etendoerp.go.payment.StripeCustomerPortalService;
import com.etendoerp.go.payment.DemoDataTransferService;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;
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
import com.etendoerp.go.onboarding.CalendarReviewService;
import com.etendoerp.go.onboarding.OnboardingInitialPeriodStateService;
import com.etendoerp.go.onboarding.OnboardingPeriodControlService;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService;
import com.etendoerp.go.onboarding.OnboardingWarehouseAddressService;
import com.etendoerp.go.onboarding.NdjsonOnboardingProgressSink;
import com.etendoerp.go.onboarding.NdjsonSinkWriter;
import com.etendoerp.go.onboarding.OnboardingProgressSink;
import com.etendoerp.go.onboarding.OnboardingStreamWriter;
import com.etendoerp.go.common.SpanishTaxIdValidator;
import com.etendoerp.go.onboarding.OnboardingCompanyDataService;
import com.etendoerp.go.onboarding.OnboardingCompanyProfileTransferService;
import com.etendoerp.go.onboarding.OnboardingSampleDataService;
import com.etendoerp.go.onboarding.OnboardingSequenceGeneratorService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.AccountIdentity;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.util.OwnerSupport;
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
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.usageevents.SessionLoginUsage;
import com.etendoerp.go.schemaforge.util.OwnerSupport;
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
 *   GET  /sws/go/plans        — List the purchasable plans (requires session token). Plan keys
 *                               only; the provider price id is never exposed.
 *   GET  /sws/go/environments — List environments for the account (requires session token),
 *                               each carrying its plan ("free" | "productive"), plan key and
 *                               subscription status
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
  private static final String FIELD_FISCAL_ID_VALUE = "fiscalIdValue";
  private static final String FIELD_DEMO_CLIENT_ID = "demoClientId";
  private static final String FIELD_CLIENT_ID = "clientId";
  private static final String FIELD_ENVIRONMENT = "environment";
  /** Provisioning path recorded on an attempt that claimed a pooled tenant. */
  private static final String PATH_POOL = "POOL";
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
  private static final String PATH_SESSION = "/session";
  /** ETP-5847 — signs in with the one-time login link the signup API mails. */
  private static final String PATH_SESSION_LINK = "/session/link";
  private static final String AUTH_METHOD_LINK = "link";
  private static final String CODE_LOGIN_LINK_INVALID = "LOGIN_LINK_INVALID";
  private static final String LOGIN_LINK_INVALID_MESSAGE =
      "This login link is invalid, already used or expired";
  private static final String ERROR_UNKNOWN_ENDPOINT = "Unknown endpoint: ";
  private static final String FIELD_PAYMENT_TOKEN = "paymentToken";
  private static final String FIELD_PLAN_KEY = "planKey";
  private static final String CODE_PLAN_NOT_AVAILABLE = "PLAN_NOT_AVAILABLE";
  private static final String PLAN_NOT_AVAILABLE_MESSAGE =
      "The selected plan is no longer available. Reload the page to see the current plans.";
  private static final String CHECKOUT_NOT_CONFIGURED = "CHECKOUT_NOT_CONFIGURED";
  private static final String CHECKOUT_NOT_CONFIGURED_MESSAGE = "Checkout is not configured";
  private static final String MISSING_TOKEN_MESSAGE = "Missing required field: token";
  private static final String FIELD_INCLUDE_SAMPLE_DATA = "includeSampleData";
  private static final String FIELD_ACCOUNT_EMAIL = "accountEmail";
  private static final String FIELD_CURRENCY = "currency";
  /** Key of the resource a Stripe event carries under {@code data}. */
  private static final String STRIPE_EVENT_OBJECT = "object";
  private static final String HEADER_ORIGIN = "Origin";
  private static final String BILLING_OWNER_REQUIRED = "BILLING_OWNER_REQUIRED";
  private static final String BILLING_OWNER_MESSAGE = "Only the environment owner can manage billing";
  private static final String CLIENT_NAME_REQUIRED = "clientName is required";
  private static final String CLIENT_NAME_IN_USE_MESSAGE =
      "You already have a productive environment with this name. Choose a different name.";
  private static final String RESERVED_CLIENT_NAME_MESSAGE =
      "This company name is reserved. Choose a different name.";
  /** ETP-5742 — refusals of {@code POST /billing/reactivation}. */
  private static final String CODE_REACTIVATION_OWNER_REQUIRED = "REACTIVATION_OWNER_REQUIRED";
  private static final String REACTIVATION_OWNER_MESSAGE =
      "Only the owner of this environment can reactivate its subscription";
  private static final String CODE_REACTIVATION_NOT_AVAILABLE = "REACTIVATION_NOT_AVAILABLE";
  private static final String REACTIVATION_NOT_AVAILABLE_MESSAGE =
      "This environment has no canceled subscription to reactivate";
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
  // ETP-5426: the optional sample-data step. "warning" is its failure status: the tenant is
  // already committed and usable, so a sample-data failure never becomes a provisioning "error".
  private static final String PROGRESS_SAMPLE_DATA = "sampleData";
  private static final String PROGRESS_DONE = "done";
  private static final String PROGRESS_WARNING = "warning";
  // Stable codes for provisioning failures whose underlying message is an unresolved AD message
  // key. Mirrored by the frontend's onboarding/errorMessages.js (ETP-4665).
  private static final long PASSWORD_RESET_TTL_SECONDS = 30 * 60L;
  private static final String PASSWORD_RESET_NEUTRAL_MESSAGE =
      "If an account exists for that email, password reset instructions will be sent.";
  private static final String PASSWORD_RESET_INVALID_MESSAGE =
      "Invalid or expired password reset token";
  // ETP-5258 — stable code so the reset screen translates the dead-link case by code instead of
  // showing the English text above, which is a developer-facing fallback only.
  private static final String CODE_PASSWORD_RESET_INVALID = "PASSWORD_RESET_INVALID";
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
  private static final String SSO_PREFIX = "/sso/";
  private static final String PATH_ONBOARDING_DRAFT = "/onboarding/draft";
  static final String CODE_DEMO_SELECTION_REQUIRED = "DEMO_SELECTION_REQUIRED";
  /** ETP-5734 — opt-in query parameter of {@code GET /environments}. */
  private static final String PARAM_DETAILS = "details";
  /** Maximum accepted age of a Stripe-Signature timestamp, per the provider's guidance. */
  private static final long CHECKOUT_WEBHOOK_TOLERANCE_SECONDS = 300;
  /** The event types that confirm a hosted-checkout payment; every other type is ignored. */
  private static final List<String> CHECKOUT_PAID_EVENT_TYPES = StripeEventTypes.CHECKOUT_PAID;
  private static final List<String> SUBSCRIPTION_EVENT_TYPES =
      StripeEventTypes.SUBSCRIPTION_LIFECYCLE;
  /**
   * ETP-5047 — events that raise an operator alert and change nothing. A dispute is a chargeback
   * the provider is already arbitrating: blocking the tenant on it would punish a customer before
   * the outcome is known, and a lost dispute reaches the subscription through the ordinary
   * lifecycle events anyway.
   */
  private static final List<String> ALERT_ONLY_EVENT_TYPES = StripeEventTypes.ALERT_ONLY;
  /**
   * ETP-5048 — Stripe's dedicated event for a subscription that entered status {@code paused}.
   * Alert-only, like a {@code customer.subscription.updated} carrying that status: see
   * {@link PausedSubscriptionAlert}. {@code customer.subscription.resumed} only ends the pause
   * episode (so the next pause alerts again): a pause never changed anything to undo.
   */
  private static final String SUBSCRIPTION_PAUSED_EVENT = StripeEventTypes.SUBSCRIPTION_PAUSED;
  /** The stable prefix of the webhook's operator alerts (ETP-5048, aligned with the reconciler). */
  static final String BILLING_ALERT_PREFIX = "[BILLING-ALERT]";
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
      FIELD_FISCAL_ID_VALUE, FIELD_ADDRESS, "sector", FIELD_INCLUDE_SAMPLE_DATA };
  private static final String PATH_ONBOARDING_FIRST_STEPS = "/onboarding/first-steps";
  private static final String FIELD_FIRST_STEPS = "firstSteps";
  private static final String FIELD_FIRST_STEPS_VERSION = "v";
  private static final String FIELD_FIRST_STEPS_SEEN = "seen";
  private static final String FIELD_FIRST_STEPS_COMPLETED = "completed";
  /** ETP-5047 — the access decision reported when entering a blocked tenant. */
  private static final String FIELD_ACCESS_DECISION = "accessDecision";
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
  private static final String PATH_CALENDAR_REVIEW = "/onboarding/calendar-review";
  private static final String FIELD_COMPANY_DATA = "companyData";

  OnboardingDatasetImportService onboardingDatasetImportService = new OnboardingDatasetImportService();
  OnboardingCompanyDataService onboardingCompanyDataService = new OnboardingCompanyDataService();
  OnboardingCompanyProfileTransferService onboardingCompanyProfileTransferService =
      new OnboardingCompanyProfileTransferService();
  OnboardingAccountingWiringService onboardingAccountingWiringService =
      new OnboardingAccountingWiringService();
  OnboardingPeriodControlService onboardingPeriodControlService =
      new OnboardingPeriodControlService();
  OnboardingInitialPeriodStateService onboardingInitialPeriodStateService =
      new OnboardingInitialPeriodStateService();
  CalendarReviewService calendarReviewService = new CalendarReviewService();
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
  OnboardingSampleDataService onboardingSampleDataService = new OnboardingSampleDataService();
  PooledTenantClaimService pooledTenantClaimService = new PooledTenantClaimService();
  DevProvisioningFailureFixtureService devProvisioningFailureFixtureService =
      new DevProvisioningFailureFixtureService();
  TenantPaywallService tenantPaywallService = new TenantPaywallService();
  TenantEnvironmentLifecycleService tenantEnvironmentLifecycleService =
      new TenantEnvironmentLifecycleService();
  DevLifecycleToolService devLifecycleToolService = new DevLifecycleToolService();
  TenantPlanService tenantPlanService = new TenantPlanService();
  SubscriptionService subscriptionService = new SubscriptionService();
  SubscriptionModelSwitch subscriptionModelSwitch = new SubscriptionModelSwitch();
  HostedCheckoutService hostedCheckoutService = new HostedCheckoutService();
  PlanCatalogService planCatalogService = new PlanCatalogService();
  CheckoutRequestStore checkoutRequestStore = new CheckoutRequestStore();
  DemoDataTransferService demoDataTransferService = new DemoDataTransferService();
  BillingEventStore billingEventStore = new BillingEventStore();
  /** ETP-5048 — records the onboarding inputs of a purchase; package-visible for tests. */
  CheckoutRequestWrites checkoutRequestWrites = new CheckoutRequestWrites();
  CheckoutWebhookProcessor checkoutWebhookProcessor =
      new CheckoutWebhookProcessor(billingEventStore, CHECKOUT_WEBHOOK_TOLERANCE_SECONDS);
  SubscriptionLifecycleApplier subscriptionLifecycleApplier = new SubscriptionLifecycleApplier();
  /**
   * ETP-5047 — one guard for the servlet's life. It reads {@link #tenantEnvironmentLifecycleService}
   * at every check, so a test that replaces that field keeps working.
   */
  private final EnvironmentAccessGuard environmentAccessGuard =
      new EnvironmentAccessGuard(() -> tenantEnvironmentLifecycleService);
  StripeCustomerPortalService stripeCustomerPortalService = new StripeCustomerPortalService();
  CompanyInvitationService companyInvitationService;
  private final TransactionalAuthEmailSender authEmailSender;
  private final AccountRegistrationService accountRegistration;
  com.etendoerp.go.schemaforge.email.InternalAlertService internalAlertService =
      new com.etendoerp.go.schemaforge.email.InternalAlertService();
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
    this.accountRegistration = new AccountRegistrationService(authEmailSender);
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
    } else if (isPath(path, PATH_CALENDAR_REVIEW)) {
      handleCalendarReviewState(request, response);
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
    } else if (isPath(path, "/plans")) {
      handlePlans(request, response);
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
    if (isPath(path, "/dev/provisioning-failure-fixture")
        && DevProvisioningFailureFixtureService.isEnabled()) {
      handleDevProvisioningFailureFixturePost(request, response);
      return;
    }
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

  private void handleDevProvisioningFailureFixturePost(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    runWithPlatformAccount(request, response, "dev-provisioning-failure-fixture", account -> {
      JSONObject body = readJsonBodyOrBadRequest(request, response);
      if (body == null) return;
      writeResponse(response, HttpServletResponse.SC_OK,
          devProvisioningFailureFixtureService.create(account,
              body.optString(FIELD_CLIENT_NAME, "")));
    });
  }

  private void handleDevProvisioningFailureFixtureDelete(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    String prefix = "/dev/provisioning-failure-fixture/";
    String token = request.getPathInfo().substring(prefix.length());
    if (!DevProvisioningFailureFixtureService.isFixtureRequest(token)) {
      // A malformed token is the caller's error, not a server failure.
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid fixture cleanup token");
      return;
    }
    runWithPlatformAccount(request, response, "dev-provisioning-failure-fixture-cleanup", account -> {
      JSONObject result = new JSONObject();
      result.put("cleaned", devProvisioningFailureFixtureService.cleanup(account, token));
      writeResponse(response, HttpServletResponse.SC_OK, result);
    });
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
    } else if (isPath(path, PATH_SESSION_LINK)) {
      handleSessionLink(request, response);
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
    } else if (isPath(path, "/billing/reactivation")) {
      handleSubscriptionReactivation(request, response);
    } else if (isPath(path, "/demo-data-transfer/retry")) {
      handleDemoDataTransferRetry(request, response);
    } else if (isPath(path, PATH_CALENDAR_REVIEW + "/confirm")) {
      handleCalendarReviewAction(request, response, false);
    } else if (isPath(path, PATH_CALENDAR_REVIEW + "/regenerate")) {
      handleCalendarReviewAction(request, response, true);
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
    if (path != null && path.startsWith("/dev/provisioning-failure-fixture/")
        && DevProvisioningFailureFixtureService.isEnabled()) {
      handleDevProvisioningFailureFixtureDelete(request, response);
    } else if (isPath(path, PATH_SESSION)) {
      handleSessionDelete(request, response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_UNKNOWN_ENDPOINT + path);
    }
  }

  /**
   * GET /sws/go/plans — the read-only Subscription Plan Catalog, so the browser can name a plan.
   *
   * <p>Header: {@code Authorization: Bearer <session token>}, the same credential the checkout
   * endpoints take, resolved by the same {@link #runWithAuthenticatedAccount} template. The
   * plan catalog is not secret, but it is only ever needed by someone about to buy, and inventing a
   * second, public auth path for it would be a new surface to get wrong.
   *
   * <p>Returns {@code 200} with {@code { "plans": [ { planKey, name, description, displayPrice,
   * currency, billingInterval } ] }}. An empty plan catalog is an empty array and a {@code 200}: "there
   * is nothing on sale" is an answer, not a failure, and the caller has to render the same
   * "checkout unavailable" state for it either way.
   *
   * <p><b>Legacy price fallback.</b> While {@link PlanCatalogService#isLegacyFallbackActive()}
   * holds — a legacy price is configured and no plan carries a provider price — the list is exactly
   * the grandfathered {@code legacy-productive} plan, quoted from the configured Stripe price. That
   * is the same predicate checkout decides on, so the list never offers something checkout refuses.
   * If Stripe cannot quote the price, the plan is left out (logged) rather than answering 500.
   *
   * @see #handleCheckoutSession the endpoint this exists to feed
   */
  private void handlePlans(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedAccount(request, response, "list plans", account -> {
      JSONArray plans = new JSONArray();
      Optional<Plan> legacyFallback = planCatalogService.findLegacyFallbackPlan();
      if (legacyFallback.isPresent()) {
        JSONObject item = buildLegacyFallbackPlanJson(legacyFallback.get());
        if (item != null) {
          plans.put(item);
        }
      } else {
        for (Plan plan : planCatalogService.listPurchasablePlans()) {
          JSONObject item = buildPlanJson(plan);
          if (item != null) {
            plans.put(item);
          }
        }
      }
      JSONObject result = new JSONObject();
      result.put("plans", plans);
      writeResponse(response, HttpServletResponse.SC_OK, result);
    });
  }

  /**
   * Projects the grandfathered plan onto the browser's view of it, quoting the configured legacy
   * Stripe price — the one checkout will charge under the fallback.
   *
   * <p>The price comes from Stripe ({@link StripePriceService#retrieveConfiguredPrice()}, the same
   * lookup {@code BillingOfferConfiguration} derives the billing offer from), never from a typed
   * amount, so the amount shown is the amount charged. A failed lookup leaves the plan out: offering a plan whose price nobody could verify is the one thing this list
   * must not do, and a 500 would break the page for a problem the buyer cannot act on.
   *
   * <p>The description is sent empty: the grandfathered row's {@code DESCRIPTION} documents the
   * fallback for operators and is not buyer-facing copy.
   *
   * @param legacy the active grandfathered plan row
   * @return the JSON view, or null when the configured price cannot be quoted
   */
  private JSONObject buildLegacyFallbackPlanJson(Plan legacy) throws JSONException {
    StripePriceService.Price price;
    try {
      price = stripePriceService.retrieveConfiguredPrice();
    } catch (IOException | JSONException | RuntimeException e) {
      log.warn("Legacy price fallback is active but the configured price could not be quoted; "
          + "leaving plan '{}' out of the plan catalog response", legacy.getSearchKey(), e);
      return null;
    }
    BigDecimal displayPrice = BigDecimal.valueOf(price.getAmountMinor())
        .movePointLeft(StripeCurrencyScale.exponent(price.getCurrency()));
    JSONObject item = new JSONObject();
    item.put(FIELD_PLAN_KEY, legacy.getSearchKey());
    item.put("name", StringUtils.defaultString(legacy.getName()));
    // Deliberately empty, never the row's DESCRIPTION: that text is operator documentation
    // ("Grandfathered plan ... sold only via the legacy price fallback ... etendo.go.checkout...")
    // and would put internal configuration keys in front of a buyer on the upgrade page.
    item.put("description", "");
    item.put("displayPrice", displayPrice.toPlainString());
    item.put(FIELD_CURRENCY, price.getCurrency());
    item.put("billingInterval", StringUtils.defaultString(price.getInterval()));
    return item;
  }

  /**
   * Projects a Subscription Plan Catalog row onto the browser's view of it.
   *
   * <p><b>{@code PROVIDER_PRICE_ID} is deliberately absent, and must stay absent.</b> The browser
   * names a plan, never a price: {@link #handleCheckoutSession} has no request field for a price
   * and no code path that reads one, so a price id would be useless to a client and merely useful
   * to an attacker mapping the billing account. Shipping only the key keeps that guarantee
   * structural rather than a convention someone has to remember.
   *
   * <p>A row with no display price or no currency is dropped rather than sent with nulls: the
   * client cannot quote it, so offering it would produce a purchase whose price the buyer was
   * never shown. That is a plan catalog data defect, hence the warning.
   *
   * @param plan an active, sellable plan catalog row
   * @return the JSON view, or null when the row cannot be quoted and must not be offered
   */
  private JSONObject buildPlanJson(Plan plan) throws JSONException {
    BigDecimal displayPrice = plan.getDisplayPrice();
    String currency = plan.getCurrencyCode();
    if (displayPrice == null || StringUtils.isBlank(currency)) {
      log.warn("Plan '{}' is sellable but carries no display price or currency; "
          + "leaving it out of the plan catalog response", plan.getSearchKey());
      return null;
    }
    JSONObject item = new JSONObject();
    item.put(FIELD_PLAN_KEY, plan.getSearchKey());
    item.put("name", StringUtils.defaultString(plan.getName()));
    item.put("description", StringUtils.defaultString(plan.getDescription()));
    // A plain string, not a double: the amount crosses to a client that formats it, and a
    // binary float would quietly re-round a price on the way.
    item.put("displayPrice", displayPrice.toPlainString());
    item.put(FIELD_CURRENCY, currency);
    item.put("billingInterval", StringUtils.defaultString(plan.getBillingInterval()));
    return item;
  }

  /**
   * POST /sws/go/checkout/sessions — starts a provider-hosted checkout for a plan catalog plan.
   *
   * <p>{@code planKey} names the Subscription Plan Catalog row being bought; a missing key asks for
   * the legacy price fallback. See {@link #createHostedCheckoutSession} for the refusals.
   */
  private void handleCheckoutSession(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    runWithAuthenticatedContext(request, response, "checkout-session", authenticated -> {
      Account account = authenticated.account;
      if (!requireBillingOwner(response, account)) return;
      JSONObject body = readJsonBodyOrBadRequest(request, response);
      if (body == null) return;
      String clientName = validatedBillingClientName(response, body, account);
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
      createHostedCheckoutSession(response, account, body, clientName, selection,
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

  private String validatedBillingClientName(HttpServletResponse response, JSONObject body,
      Account account) throws IOException {
    String clientName = body.optString(FIELD_CLIENT_NAME, "").trim();
    if (clientName.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          CLIENT_NAME_REQUIRED, CLIENT_NAME_REQUIRED);
      return null;
    }
    if (ProvisioningClientName.matches(clientName)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          RESERVED_CLIENT_NAME_MESSAGE, RESERVED_CLIENT_NAME_MESSAGE);
      return null;
    }
    if (isProductiveNameTakenByAccount(clientName, account.getEmail())) {
      // Refused before the provider is contacted: a paid checkout fixes its company name, so
      // accepting it here would charge for an environment that can never be provisioned.
      writeError(response, HttpServletResponse.SC_CONFLICT,
          ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE, CLIENT_NAME_IN_USE_MESSAGE,
          CLIENT_NAME_IN_USE_MESSAGE);
      return null;
    }
    return clientName;
  }

  /**
   * An account cannot hold two productive environments with the same company name (ETP-5548):
   * telling them apart would be left to the customer. Any other match is allowed — another
   * account's environment, or this account's demo, which a purchase never converts but replaces
   * with a new productive. Onboarding applies the same rule again in
   * {@code rejectDuplicateProductiveName}; this pre-payment copy fails open, because the onboarding
   * one still refuses the collision and records it as non-retryable.
   *
   * <p>Runs through {@link SystemContext}, so the caller gets its own OBContext and admin-mode
   * state back on every path — {@code restorePreviousMode()} alone would leave the system context
   * installed for the rest of the request (ETP-5045). Failing open happens inside the system
   * scope, so the context is restored on that path too.
   */
  private boolean isProductiveNameTakenByAccount(String clientName, String accountEmail) {
    return SystemContext.call("the pre-checkout company name check", () -> {
      try {
        return ownsProductiveNamed(clientName, accountEmail);
      } catch (RuntimeException e) {
        log.warn("Could not check whether company name is in use before checkout; the onboarding "
            + "check still applies", e);
        return false;
      }
    });
  }

  /**
   * Caller provides the admin context. Case and surrounding blanks are ignored.
   *
   * <p>ETP-5742 — a canceled productive environment still holds its name: its closed subscription
   * row makes the plan read {@code free}, so the plan alone let a purchase under that name through
   * to a new environment that the company-name constraint then refuses, after the payment. The
   * lifecycle type is asked too; the way back into that environment is re-subscribing it, not
   * buying it again.
   */
  private boolean ownsProductiveNamed(String clientName, String accountEmail) {
    for (String clientId : EtendoGoJwtSupport.findClientIdsByName(clientName)) {
      if (EtendoGoJwtDalHelper.clientBelongsToAccountEmail(clientId, accountEmail)
          && (TenantPlanService.PLAN_PRODUCTIVE.equals(tenantPlanService.resolvePlan(clientId))
              || hasProductiveLifecycle(clientId))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the lifecycle snapshot says productive. Deliberately {@code resolve}, not
   * {@link TenantEnvironmentLifecycleService#isProductiveEnvironment}: that one fails closed (an
   * unreadable tenant counts as productive, the safe answer for a purchase source), while a name
   * check must fail open — onboarding repeats it after the payment, and a lookup error there would
   * refuse a paid environment as a non-retryable name collision. {@code resolve} answers null on
   * an error.
   */
  private boolean hasProductiveLifecycle(String clientId) {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        tenantEnvironmentLifecycleService.resolve(clientId);
    return snapshot != null
        && snapshot.getType() == EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE;
  }

  /**
   * Opens a provider checkout for a new purchase and answers the caller, translating each failure
   * mode into the response the checkout contract promises.
   *
   * <p><b>The browser names a plan, never a price.</b> There is no request field for a price and
   * no code path that reads one, so a body carrying {@code "priceId"} is <em>ignored, not
   * validated</em> — rejecting it would imply the server might otherwise have honoured it. Two
   * refusals, deliberately different:
   * <ul>
   *   <li>{@code 400 PLAN_NOT_AVAILABLE} — the key names no active plan catalog row, or asks for
   *       the grandfathered plan (or names none) while the legacy price fallback is inactive.
   *       Unknown and inactive are answered identically, because the endpoint must not confirm
   *       which keys exist; same non-disclosure discipline as {@link #handleCheckoutStatus}. The
   *       usual innocent cause is a plan list the browser loaded before it changed, so the user
   *       message says to reload.</li>
   *   <li>{@code 503 CHECKOUT_NOT_CONFIGURED} — checkout has no credentials, or the plan exists
   *       but carries no provider price id. Both are "there is nothing sellable here", which is a
   *       deployment state rather than a bad request.</li>
   * </ul>
   */
  private void createHostedCheckoutSession(HttpServletResponse response, Account account,
      JSONObject body, String clientName, CheckoutSelection selection,
      boolean accountBillingPurchase) throws IOException {
    // Optional on the wire: a blank key asks for the legacy price fallback, and is refused with
    // the same PLAN_NOT_AVAILABLE an unknown key gets once that fallback is inactive.
    String planKey = StringUtils.trimToNull(body.optString(FIELD_PLAN_KEY, ""));
    // Billing redirects are server-owned. Never trust a browser-supplied Origin as a return URL.
    final String origin = PublicUrlResolver.resolveConfiguredAppBaseUrl();
    writeStartedCheckout(response, accountBillingPurchase, () -> {
      // The source, transfer choice, plan and Stripe Price are persisted on the purchase row, and
      // the flag-gated transfer selection and the onboarding inputs (ETP-5048) are recorded before
      // the provider is contacted.
      JSONObject result = hostedCheckoutService.createSession(account.getId(), account.getEmail(),
          clientName, origin, planKey, new HostedCheckoutService.SessionOptions(
              selection.demoClientId, selection.transferProducts, selection.transferContacts,
              requestId -> {
                // ETP-5048 — what a server-side provisioning needs if the browser never comes
                // back; best effort, it never fails the purchase.
                checkoutRequestWrites.recordOnboardingInputs(requestId,
                    body.optString(FIELD_LANGUAGE, ""), body.optString(FIELD_CURRENCY, ""),
                    body.optString(FIELD_COUNTRY_CODE, ""));
                recordDemoDataTransferSelection(body, requestId, selection);
              }));
      addDemoDataTransferSelectionBestEffort(result, result.optString(FIELD_REQUEST_ID, ""),
          selection.demoClientId);
      return result;
    });
  }

  /** Starts a provider checkout session; see {@link #writeStartedCheckout}. */
  @FunctionalInterface
  private interface CheckoutStart {
    JSONObject start() throws IOException, JSONException;
  }

  /**
   * Answers a started checkout with {@code 201}, translating each failure mode into the response
   * the checkout contract promises. Shared by the new-environment purchase and the ETP-5742
   * re-subscription, so both refuse an unavailable plan, an unconfigured checkout and a provider
   * error the same way.
   *
   * @param accountBillingPurchase whether the caller is the account billing boundary, which words
   *     a provider error as a billing purchase rather than a checkout session
   */
  private void writeStartedCheckout(HttpServletResponse response, boolean accountBillingPurchase,
      CheckoutStart start) throws IOException {
    try {
      writeResponse(response, HttpServletResponse.SC_CREATED, start.start());
    } catch (PlanNotAvailableException e) {
      // Logged with the key, answered without it: the caller learns that this key is not usable
      // and nothing about the rest of the plan catalog. The message carries the key already
      // neutralised for logging (single line, bounded) — see PlanNotAvailableException.
      log.warn("Rejected a checkout for an unavailable plan: {}", e.getMessage());
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_PLAN_NOT_AVAILABLE,
          PLAN_NOT_AVAILABLE_MESSAGE, PLAN_NOT_AVAILABLE_MESSAGE);
    } catch (HostedCheckoutService.CheckoutNotConfiguredException e) {
      log.warn("Checkout is not available: {}", e.getMessage());
      writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, CHECKOUT_NOT_CONFIGURED,
          CHECKOUT_NOT_CONFIGURED_MESSAGE, CHECKOUT_NOT_CONFIGURED_MESSAGE);
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
      String clientName = validatedBillingClientName(response, body, account);
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
      createHostedCheckoutSession(response, account, body, clientName, selection,
          true);
    });
  }

  /**
   * POST /sws/go/billing/reactivation — ETP-5742: re-subscribes the session's own environment
   * after its subscription was canceled, instead of buying a new one.
   *
   * <p>The environment is the session's, never a request field, and it must be:
   * <ul>
   *   <li><b>owned by the account</b> ({@code clientOwnedByAccountEmail}, ETP-5743): an invited
   *       member is refused {@code 403 REACTIVATION_OWNER_REQUIRED} — paying for someone else's
   *       environment is the owner's decision;</li>
   *   <li><b>a productive environment whose subscription is canceled</b>
   *       ({@code EXPIRED} in the lifecycle snapshot, whether it reads from a closed
   *       {@code ETGO_SUBSCRIPTION} row or from the preference projection); anything else is
   *       {@code 409 REACTIVATION_NOT_AVAILABLE}. A payment that failed ({@code PAST_DUE}) is
   *       fixed in the Customer Portal, not by a second subscription.</li>
   * </ul>
   *
   * <p>Answers like a purchase: {@code 201} with {@code requestId} and {@code checkoutUrl}. A
   * re-subscription of the same environment still open is reopened ({@code 200}); one already paid
   * and waiting for the payment webhook answers {@code 200 {"requestId", "status": "paid"}}. The
   * request names the environment as its {@code CREATED_CLIENT_ID} from the start, which is what
   * the payment webhook applies the purchase to ({@link #applyReactivationIfPending}) and what
   * routes the new subscription's lifecycle events. Same plan as the canceled subscription when
   * it had one, otherwise the legacy price fallback, exactly as a purchase naming no plan.
   */
  private void handleSubscriptionReactivation(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    runWithAuthenticatedContext(request, response, "subscription-reactivation", authenticated -> {
      Account account = authenticated.account;
      String clientId = currentSessionClientId(request, response, authenticated);
      if (clientId == null) return;
      if (StringUtils.isBlank(clientId) || ZERO_ID.equals(clientId)) {
        writeError(response, HttpServletResponse.SC_CONFLICT, CODE_REACTIVATION_NOT_AVAILABLE,
            REACTIVATION_NOT_AVAILABLE_MESSAGE, REACTIVATION_NOT_AVAILABLE_MESSAGE);
        return;
      }
      if (!isOwnedClient(clientId, account.getEmail())) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, CODE_REACTIVATION_OWNER_REQUIRED,
            REACTIVATION_OWNER_MESSAGE, REACTIVATION_OWNER_MESSAGE);
        return;
      }
      if (!isCanceledProductive(clientId)) {
        writeError(response, HttpServletResponse.SC_CONFLICT, CODE_REACTIVATION_NOT_AVAILABLE,
            REACTIVATION_NOT_AVAILABLE_MESSAGE, REACTIVATION_NOT_AVAILABLE_MESSAGE);
        return;
      }
      CheckoutRequest active = checkoutRequestStore.findActiveReactivation(account.getId(),
          account.getEmail(), clientId);
      if (active != null) {
        handleExistingReactivation(response, account, active);
        return;
      }
      ReactivationTarget target = SystemContext.call("the re-subscription target",
          () -> reactivationTarget(clientId));
      // Billing redirects are server-owned. Never trust a browser-supplied Origin as a return URL.
      final String origin = PublicUrlResolver.resolveConfiguredAppBaseUrl();
      writeStartedCheckout(response, true, () -> hostedCheckoutService.createSession(
          account.getId(), account.getEmail(), target.clientName, origin, target.planKey,
          HostedCheckoutService.SessionOptions.reactivating(clientId)));
    });
  }

  /** The environment name and the plan a re-subscription is bought under. */
  private static final class ReactivationTarget {
    private final String clientName;
    private final String planKey;

    private ReactivationTarget(String clientName, String planKey) {
      this.clientName = clientName;
      this.planKey = planKey;
    }
  }

  /**
   * Caller provides the system context. The plan is the canceled subscription's when the tenant
   * has a row; a tenant with none (the preference projection) names no plan, which asks for the
   * legacy price fallback like any purchase that names none.
   */
  private ReactivationTarget reactivationTarget(String clientId) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    String planKey = subscriptionService.findLatest(clientId)
        .map(Subscription::getPlan)
        .map(Plan::getSearchKey)
        .orElse(null);
    return new ReactivationTarget(client == null ? "" : client.getName(), planKey);
  }

  /**
   * Whether the environment is productive and its subscription canceled: the lifecycle snapshot,
   * so a closed {@code ETGO_SUBSCRIPTION} row and the {@code ETGO_SubscriptionStatus} preference
   * answer alike. Fails closed: an unreadable lifecycle is not offered a re-subscription.
   */
  private boolean isCanceledProductive(String clientId) {
    return SystemContext.call("the re-subscription eligibility check", () -> {
      TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
          tenantEnvironmentLifecycleService.resolve(clientId);
      return snapshot != null
          && snapshot.getType() == EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE
          && snapshot.getSubscriptionStatus() == EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED;
    });
  }

  /**
   * A second click or a second tab: an unpaid re-subscription is reopened like any unpaid purchase
   * ({@link #handleExistingBillingPurchase}); a paid one is only waiting for its webhook.
   */
  private void handleExistingReactivation(HttpServletResponse response, Account account,
      CheckoutRequest active) throws IOException, JSONException {
    if ("PAID".equals(active.getCheckoutRequestStatus())) {
      JSONObject result = new JSONObject();
      result.put(FIELD_REQUEST_ID, active.getRequest());
      result.put(FIELD_STATUS, "paid");
      writeResponse(response, HttpServletResponse.SC_OK, result);
      return;
    }
    handleExistingBillingPurchase(response, account, active);
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
      JSONObject result = new JSONObject();
      result.put(FIELD_REQUEST_ID, requestId);
      String status = checkoutRequestStore.deriveProvisioningStatus(checkoutRequest);
      result.put(FIELD_STATUS, status);
      result.put("retryAllowed", checkoutRequestStore.isProvisioningRetryAllowed(checkoutRequest));
      if (checkoutRequest != null) {
        addCheckoutRequestDetails(result, requestId, status, checkoutRequest);
      }
      writeResponse(response, HttpServletResponse.SC_OK, result);
    });
  }

  /** The polling contract's fields that only exist once the request does. */
  private void addCheckoutRequestDetails(JSONObject result, String requestId, String status,
      CheckoutRequest checkoutRequest) throws JSONException {
    result.put(FIELD_CLIENT_NAME, checkoutRequest.getClientName());
    if (checkoutRequest.getCreatedClient() != null) {
      result.put(FIELD_CLIENT_ID, checkoutRequest.getCreatedClient().getId());
    }
    if (checkoutRequest.getUpdated() != null) {
      result.put("updatedAt", checkoutRequest.getUpdated().toInstant().toString());
    }
    if (CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED.equals(status)) {
      addProvisioningFailure(result, checkoutRequest.getFailureReason());
    }
    if (checkoutRequest.getDemoClient() != null) {
      result.put(FIELD_DEMO_CLIENT_ID, checkoutRequest.getDemoClient().getId());
      addDemoDataTransferSelection(result, requestId, checkoutRequest.getDemoClient().getId());
    }
  }

  /**
   * Adds the failure of an attempt to the public polling contract as a stable code plus a fixed
   * description. The persisted reason is never returned: it holds the raw cause (exception text,
   * internal ids, SQL) for operations, and the client localizes the code instead.
   */
  private static void addProvisioningFailure(JSONObject result, String persistedReason)
      throws JSONException {
    String failureCode = ProvisioningFailureReason.codeOf(persistedReason);
    result.put("failureCode", failureCode);
    result.put("failureReason", ProvisioningFailureReason.safeDescription(failureCode));
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
        String clientId = null;
        if (purchase.getCreatedClient() != null) {
          clientId = purchase.getCreatedClient().getId();
          snapshot = tenantEnvironmentLifecycleService.resolve(clientId);
        }
        writeResponse(response, HttpServletResponse.SC_OK,
            buildBillingSubscriptionJson(live, snapshot, clientId));
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
      TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot, String clientId)
      throws JSONException {
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
    // ETP-5734 — the company the subscription pays for, so "Mis empresas" can put the live
    // renewal date on that company's row. Additive: existing consumers ignore it.
    result.put(FIELD_CLIENT_ID, clientId == null ? JSONObject.NULL : clientId);
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
    result.put(FIELD_CLIENT_ID, createdClientId == null ? JSONObject.NULL : createdClientId);
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
    if (ALERT_ONLY_EVENT_TYPES.contains(type)) {
      applyAlertOnlyEvent(eventId, type, event);
      return;
    }
    if (SUBSCRIPTION_PAUSED_EVENT.equals(type)) {
      SystemContext.run("a paused subscription event", () -> {
        raisePausedSubscriptionAlert(type, event);
        billingEventStore.markApplied(eventId);
      });
      return;
    }
    if (StripeEventTypes.SUBSCRIPTION_RESUMED.equals(type)) {
      SystemContext.run("a resumed subscription event", () -> {
        endResolvedPauseEpisodes(type, event);
        billingEventStore.markApplied(eventId);
      });
      return;
    }
    billingEventStore.markIgnored(eventId, "unhandled event type");
  }

  /**
   * ETP-5048 — raises the review alert of a paused subscription, once per pause episode whatever
   * path noticed it first (a later pause, after a resume was seen, alerts again). Changes no
   * subscription status, grace anchor or access decision.
   */
  private void raisePausedSubscriptionAlert(String type, JSONObject event) {
    JSONObject data = event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject(STRIPE_EVENT_OBJECT);
    String subscriptionId = SubscriptionLifecycleApplier.subscriptionIdOf(type, object);
    TenantEnvironmentLifecycleService.LifecycleTarget target =
        resolveLifecycleTarget(subscriptionId, object);
    CheckoutRequest purchase = checkoutRequestStore.findByStripeSubscription(subscriptionId);
    new PausedSubscriptionAlert(billingEventStore, internalAlertService).raiseOnce(
        BILLING_ALERT_PREFIX, subscriptionId, target == null ? null : target.clientId(),
        purchase == null ? null : purchase.getRequest(), "webhook");
  }

  /**
   * ETP-5048 — ends the pause episodes a subscription event shows are over (status no longer
   * {@code paused}, {@code pause_collection} cleared), so the next pause alerts again. Commits on
   * its own, before the event's own writes. Changes no subscription state.
   *
   * <p>Best effort: a failed rename is a WARN and the event goes on, so it never fails the
   * lifecycle event (and never makes Stripe redeliver it in a loop). An event delivered out of
   * order (an older "active" after a newer pause) may end an episode early; the worst case is one
   * duplicate alert on the same pause later, never a missed one.
   */
  private void endResolvedPauseEpisodes(String type, JSONObject event) {
    JSONObject data = event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject(STRIPE_EVENT_OBJECT);
    new PausedSubscriptionAlert(billingEventStore, internalAlertService).endResolvedBestEffort(
        SubscriptionLifecycleApplier.subscriptionIdOf(type, object), object);
  }

  /**
   * Records an alert-only event as {@code APPLIED} and raises a WARN carrying the provider ids an
   * operator needs to find it in the Stripe dashboard. No subscription status, grace anchor or
   * access decision is touched — see {@link #ALERT_ONLY_EVENT_TYPES}. Only ids, the amount and the
   * provider's reason code are logged: never card or customer personal data.
   */
  private void applyAlertOnlyEvent(String eventId, String type, JSONObject event) {
    JSONObject data = event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject(STRIPE_EVENT_OBJECT);
    log.warn(BILLING_ALERT_PREFIX + " Stripe event '{}' ({}) — dispute {} on charge {} (payment "
        + "intent {}),"
        + " amount {} {}, reason '{}'. No access change was made; review it in the Stripe"
        + " dashboard.", eventId, type, optProviderText(object, "id"),
        optProviderText(object, "charge"), optProviderText(object, "payment_intent"),
        optProviderText(object, "amount"), optProviderText(object, FIELD_CURRENCY),
        optProviderText(object, "reason"));
    billingEventStore.markApplied(eventId);
  }

  /**
   * Reads one provider field for a log line: a nested object contributes its {@code id}, a
   * missing or JSON-null value reads as {@code "-"} (jettison's {@code optString} would print the
   * literal word "null").
   */
  private static String optProviderText(JSONObject object, String key) {
    if (object == null || !object.has(key) || object.isNull(key)) {
      return "-";
    }
    JSONObject nested = object.optJSONObject(key);
    String value = nested == null ? object.optString(key, "") : nested.optString("id", "");
    return StringUtils.defaultIfBlank(value, "-");
  }

  private void applyCheckoutPaid(String eventId, String type, JSONObject event) {
    JSONObject data = event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject(STRIPE_EVENT_OBJECT);
    JSONObject metadata = object == null ? null : object.optJSONObject("metadata");
    String requestId = metadata == null ? "" : metadata.optString("request_id", "");
    String email = metadata == null ? "" : metadata.optString("account_email", "");
    if (StringUtils.isBlank(requestId) || StringUtils.isBlank(email)) {
      billingEventStore.markIgnored(eventId, "missing correlation metadata");
      return;
    }
    // The customer and subscription ids are read here and nowhere else: this is the only event
    // that carries them alongside the correlation id, and every later subscription or invoice
    // event arrives keyed by the subscription rather than by the request. Both are read as
    // nullable: a payment-mode checkout sends "subscription": null, which a bare optString would
    // store as the text "null" (ETP-5047).
    boolean recorded = checkoutRequestStore.recordPaid(requestId,
        StripeJson.text(object, "customer"),
        StripeJson.text(object, "subscription"));
    if (!recorded) {
      billingEventStore.markIgnored(eventId, "unknown checkout request");
      return;
    }
    applyReactivationIfPending(requestId, SubscriptionLifecycleApplier.eventCreatedAt(event));
    billingEventStore.markApplied(eventId);
    log.info("Checkout webhook event '{}' ({}) applied", eventId, type);
  }

  /**
   * ETP-5742 — applies a paid re-subscription to the canceled environment it names, here in the
   * payment webhook: unlike a new purchase there is nothing for onboarding to provision, and the
   * customer is waiting on the blocked screen, not on the upgrade page.
   *
   * <p>The payment is recorded exactly as a paid upgrade records it
   * ({@link #applyPaidUpgradeSideEffects}), so both data shapes take the same path they would on a
   * purchase:
   * <ul>
   *   <li><b>a tenant with a subscription row</b> (closed, since its subscription was canceled) —
   *       a fresh row is opened for the new Stripe subscription next to the closed one, and the
   *       period is then read from the provider ({@link #fillSubscriptionPeriodBestEffort}), as
   *       after an onboarding;</li>
   *   <li><b>a tenant with no row</b> while the subscription model is off — the plan marker and
   *       {@code ETGO_SubscriptionStatus = CURRENT} are written, and the event watermark is moved
   *       to this payment, so a late event of the canceled subscription can no longer expire the
   *       tenant again.</li>
   * </ul>
   *
   * <p>The request is then closed as {@code PROVISIONED}, which commits the writes above with it:
   * a failure before that rolls them back with the event, the webhook answers 500 and the provider
   * redelivers, and the request is still {@code PAID} for the retry. Opening the row is idempotent
   * for an already open, active one.
   *
   * <p><b>One path, two callers (ETP-5048).</b> The billing reconciliation applies a
   * re-subscription whose payment webhook was lost through this same method, via
   * {@link PaidReactivationService}. Two callers on one request are fenced by
   * {@link CheckoutRequestStore#findPaidReactivation}, which locks the request row and re-reads
   * it: the second waits for the first to commit, then finds it {@code PROVISIONED} and does
   * nothing.
   *
   * @param requestId the paid request
   * @param paidAt the provider instant of the payment, the preference route's new watermark; null
   *     leaves the watermark as it is
   * @return whether this call applied a re-subscription
   */
  boolean applyReactivationIfPending(String requestId, Instant paidAt) {
    CheckoutRequest reactivation = checkoutRequestStore.findPaidReactivation(requestId);
    if (reactivation == null) {
      return false;
    }
    String clientId = reactivation.getCreatedClient().getId();
    SystemContext.run("a subscription re-subscription",
        () -> applyReactivation(reactivation, clientId, paidAt));
    fillSubscriptionPeriodBestEffort(clientId);
    return true;
  }

  private void applyReactivation(CheckoutRequest reactivation, String clientId,
      Instant paidAt) {
    String requestId = reactivation.getRequest();
    // Decided before the payment is recorded, the way recordPaymentAsSubscription decides it: once
    // a row is opened below, the tenant has one.
    boolean preferenceRoute = !subscriptionModelSwitch.isOn()
        && !subscriptionService.hasAnySubscription(clientId);
    applyPaidUpgradeSideEffects(clientId, EtendoGoJwtSupport.findStarOrgId(clientId),
        reactivation.getClientName(), reactivation.getAccountEmail(), requestId);
    if (preferenceRoute) {
      // ETP-5046-TRANSITIONAL-FALLBACK — the preference route keeps its own watermark.
      tenantEnvironmentLifecycleService.recordSubscriptionEventAt(clientId, paidAt);
    }
    checkoutRequestStore.recordProvisioned(requestId, clientId);
    log.info("Re-subscription '{}' applied to environment {}", requestId, clientId);
  }

  /**
   * Applies a subscription or invoice lifecycle event to the owned environment.
   *
   * <p>These events carry no {@code request_id}: that metadata travels only on the checkout
   * session. Invoice events are keyed by the subscription field, while subscription events are
   * keyed by the object id. The open subscription row carrying that id wins; otherwise the
   * checkout request (then the customer) names the tenant — see {@link #resolveLifecycleTarget}.
   * An event that resolves to no environment is ignored, never blocking.
   *
   * <p>The event is evaluated twice: once without state, so a malformed or unhandled event is
   * ignored before any lookup, and once against the environment's stored projection, which makes
   * an out-of-order older event stale and keeps an authoritative {@code PAST_DUE} due date.
   *
   * <p>The outcome lands on the resolved {@code ETGO_SUBSCRIPTION} row, and on the preference
   * projection for a tenant with no active row, open or closed (see
   * {@link TenantEnvironmentLifecycleService#applySubscriptionEvent}); the stored state the event
   * is evaluated against is read from the same place.
   *
   * <p>The status, due date and event instant are written in the session that
   * {@code markApplied} commits, so they land together. When a write fails the session is rolled
   * back before the row is marked {@code FAILED}; otherwise that commit would persist a partial
   * projection. The claim row itself was committed by the claim and survives the rollback.
   */
  private void applySubscriptionLifecycle(String eventId, String type, JSONObject event) {
    // The webhook is matched before the authentication chain, so this thread has NO OBContext.
    // Every write below (the subscription row, the lifecycle preferences, the event watermark)
    // needs one, and the stores it calls now give the caller's context back instead of leaking a
    // system one — so the system context is installed explicitly here, and the caller's (null)
    // context restored afterwards. SystemContext is the shared implementation the stores use too:
    // admin mode is left before the context it sits on is taken away, and neither unwinding step
    // can throw, so a failure leaving admin mode never skips restoring the caller's context.
    SystemContext.run("a subscription lifecycle event",
        () -> applySubscriptionLifecycleAsSystem(eventId, type, event));
  }

  private void applySubscriptionLifecycleAsSystem(String eventId, String type, JSONObject event) {
    if (type.startsWith(SubscriptionLifecycleApplier.SUBSCRIPTION_EVENT_PREFIX)) {
      // ETP-5048 — a pause alerts once per episode: one this update shows has ended is closed.
      endResolvedPauseEpisodes(type, event);
    }
    SubscriptionEventOutcome screened = subscriptionLifecycleApplier.evaluate(type, event);
    if (screened.isIgnored()) {
      if (SubscriptionLifecycleApplier.REASON_PAUSED.equals(screened.reason())) {
        raisePausedSubscriptionAlert(type, event);
      }
      billingEventStore.markIgnored(eventId, screened.reason());
      return;
    }
    JSONObject data = event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject(STRIPE_EVENT_OBJECT);
    String subscriptionId = SubscriptionLifecycleApplier.subscriptionIdOf(type, object);
    TenantEnvironmentLifecycleService.LifecycleTarget target =
        resolveLifecycleTarget(subscriptionId, object);
    if (target == null) {
      billingEventStore.markIgnored(eventId, "unresolved subscription");
      return;
    }
    if (target.isIgnored()) {
      billingEventStore.markIgnored(eventId, target.ignoreReason());
      return;
    }
    SubscriptionEventOutcome outcome = subscriptionLifecycleApplier.evaluate(type, event,
        target.storedState());
    if (outcome.isIgnored()) {
      billingEventStore.markIgnored(eventId, outcome.reason());
      return;
    }
    boolean stored = tenantEnvironmentLifecycleService.applySubscriptionEvent(target, outcome,
        SubscriptionLifecycleApplier.eventCreatedAt(event));
    if (!stored) {
      EtendoGoDalHelper.rollbackDalChanges("subscription lifecycle event", null, log);
      billingEventStore.markFailed(eventId, "Could not store the subscription projection");
      return;
    }
    billingEventStore.markApplied(eventId);
    log.info("Subscription lifecycle event '{}' ({}) applied", eventId, type);
  }

  /**
   * Resolves where a lifecycle event lands (ETP-5047).
   *
   * <p>First the open {@code ETGO_SUBSCRIPTION} row carrying the event's Stripe subscription id —
   * the id is not unique across rows, only across open ones (§5.2). When no open row carries it,
   * the tenant is found the pre-ETP-5047 way, through the checkout request that bought the
   * subscription or, failing that, the Stripe customer; the lifecycle service then decides whether
   * that tenant's row, its preference projection or nothing takes the event.
   *
   * @return the target, or null when no tenant can be resolved at all
   */
  private TenantEnvironmentLifecycleService.LifecycleTarget resolveLifecycleTarget(
      String subscriptionId, JSONObject object) {
    TenantEnvironmentLifecycleService.LifecycleTarget byRow =
        tenantEnvironmentLifecycleService.targetForSubscription(subscriptionId);
    if (byRow != null) {
      return byRow;
    }
    CheckoutRequest purchase = checkoutRequestStore.findByStripeSubscription(subscriptionId);
    if (purchase == null) {
      purchase = checkoutRequestStore.findByStripeCustomer(
          StripeJson.text(object, "customer"));
    }
    if (purchase == null || purchase.getCreatedClient() == null) {
      return null;
    }
    return tenantEnvironmentLifecycleService.targetForTenant(purchase.getCreatedClient().getId(),
        subscriptionId);
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
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_PASSWORD_RESET_INVALID,
            PASSWORD_RESET_INVALID_MESSAGE, PASSWORD_RESET_INVALID_MESSAGE);
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
          MISSING_TOKEN_MESSAGE, MISSING_TOKEN_MESSAGE);
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
      writeError(response, HttpServletResponse.SC_FORBIDDEN, sessionAuth.getRefusalMessage());
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
        // Every draft field is text except the sample-data opt-in (ETP-5426), a checkbox. Dropping
        // a Boolean here would silently lose the choice across a logout, not fail.
        if (value instanceof String
            || (value instanceof Boolean && FIELD_INCLUDE_SAMPLE_DATA.equals(field))) {
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

  /**
   * GET /sws/go/onboarding/calendar-review (ETP-5577) — the accounting calendar review state of
   * the session's tenant (see {@link CalendarReviewService#state}), plus {@code isOwner}: any user
   * of the tenant may read it (the posting-error guidance tells non-owners to ask the owner), only
   * the owner may act on it.
   */
  private void handleCalendarReviewState(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    runWithAuthenticatedContext(request, response, "get calendar review", authenticated -> {
      TenantSession session = resolveTenantSession(request, response, authenticated);
      if (session == null) return;
      JSONObject result = calendarReviewService.state(session.clientId, LocalDate.now());
      result.put("isOwner", tenantOwnerUserId(request, authenticated, session.clientId) != null);
      writeSuccessStatus(response, result);
    });
  }

  /**
   * POST /sws/go/onboarding/calendar-review/confirm and …/regenerate (ETP-5577). Owner of THIS
   * tenant only: owning another environment of the account is not enough, regenerate deletes the
   * calendar. Regenerate takes {@code {"fiscalYearStart": "JANUARY|APRIL|JULY|OCTOBER"}} and
   * answers 409 when anything already depends on the calendar.
   */
  private void handleCalendarReviewAction(HttpServletRequest request,
      HttpServletResponse response, boolean regenerate) throws IOException {
    runWithAuthenticatedContext(request, response, "calendar review", authenticated -> {
      TenantSession session = resolveTenantSession(request, response, authenticated);
      if (session == null) return;
      String userId = tenantOwnerUserId(request, authenticated, session.clientId);
      if (userId == null) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN,
            "Only the owner of this environment can review its calendar");
        return;
      }
      if (regenerate) {
        String fiscalYearStart = readJsonBody(request).optString("fiscalYearStart", "");
        CalendarReviewService.Refusal refusal = calendarReviewService.regenerate(
            session.clientId, fiscalYearStart, userId, LocalDate.now());
        if (refusal != null) {
          EtendoGoDalHelper.rollbackDalChanges("calendar regenerate", null, log);
          writeError(response, refusal.status, refusal.message);
          return;
        }
      } else {
        calendarReviewService.confirm(session.clientId, userId);
      }
      EtendoGoDalHelper.commitDalChanges("calendar review", log);
      // Same shape as the GET: the caller is the owner, or it would have been refused above.
      JSONObject result = calendarReviewService.state(session.clientId, LocalDate.now());
      result.put("isOwner", true);
      writeSuccessStatus(response, result);
    });
  }

  /**
   * The caller's {@code AD_User} in {@code clientId} when that user is the tenant's owner, else
   * {@code null}. The user comes from the credential (the cookie session's environment user, or a
   * legacy bearer's {@code user} claim), never from the request body.
   */
  private String tenantOwnerUserId(HttpServletRequest request, AuthenticatedAccount authenticated,
      String clientId) {
    String userId = null;
    if (authenticated.sessionRecord != null) {
      userId = authenticated.sessionRecord.getUserId();
    } else {
      DecodedJWT jwt = bearerTenantClaims(request);
      userId = jwt == null ? null : jwt.getClaim("user").asString();
    }
    return ownerOfTenant(userId, clientId);
  }

  /**
   * {@code userId} when that user belongs to {@code clientId} and is its owner, else {@code null}.
   * Owning another environment of the same account does not count.
   */
  static String ownerOfTenant(String userId, String clientId) {
    if (StringUtils.isBlank(userId)) {
      return null;
    }
    User user = OBDal.getInstance().get(User.class, userId);
    return user != null && user.getClient() != null && clientId.equals(user.getClient().getId())
        && OwnerSupport.isOwner(userId) ? userId : null;
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
    // A demo that already originated a productive environment is spent as a source: buying again
    // from it creates a clean productive environment, exactly like buying from a productive one.
    // ETP-5047 — so does a productive environment whose plan reads free because its subscription
    // was canceled: it is not a demo, so it is never asked for, nor taken as, a source.
    // So is a demo the account was only invited into (ETP-5743): it is not the buyer's to copy.
    if (isProductiveClient(sessionClientId) || isProductiveEnvironment(sessionClientId)
        || isAssociatedDemo(sessionClientId)
        || !isOwnedClient(sessionClientId, authenticated.account.getEmail())) {
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
      DecodedJWT jwt = bearerTenantClaims(request);
      if (jwt != null) clientId = StringUtils.trimToNull(
          jwt.getClaim(JwtAuthUtils.CLAIM_CLIENT).asString());
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

  private boolean isOwnedClient(String clientId, String accountEmail) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      return EtendoGoJwtDalHelper.clientOwnedByAccountEmail(clientId, accountEmail);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Whether the named environment may be the demo source of a purchase: owned by the account, on
   * the free plan, not already the origin of a productive environment (ETP-5548), and not itself a
   * productive environment (ETP-5047 — a canceled one reads plan free, see
   * {@link TenantEnvironmentLifecycleService#isProductiveEnvironment}). A purchase source is copied
   * into the buyer's new environment and then locked, so the account must own it: being invited
   * into it is not enough (ETP-5743).
   */
  private boolean isOwnedFreeDemoClient(String demoClientId, String accountEmail) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      Client client = OBDal.getInstance().get(Client.class, demoClientId);
      return client != null
          && EtendoGoJwtDalHelper.clientOwnedByAccountEmail(demoClientId, accountEmail)
          && TenantPlanService.PLAN_FREE.equals(tenantPlanService.resolvePlan(demoClientId))
          && !tenantEnvironmentLifecycleService.isAssociatedWithProductive(demoClientId)
          && !tenantEnvironmentLifecycleService.isProductiveEnvironment(demoClientId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-5047 — purchase-source eligibility, not access: the lifecycle type, never the enforced
   * access decision, so the enforcement kill switch cannot turn a productive environment into a
   * source. Scoped like {@link #isAssociatedDemo}.
   */
  private boolean isProductiveEnvironment(String clientId) {
    return SystemContext.call("the productive-environment check",
        () -> tenantEnvironmentLifecycleService.isProductiveEnvironment(clientId));
  }

  /** Scoped like {@link #isProductiveNameTakenByAccount}: the caller's context is restored. */
  private boolean isAssociatedDemo(String clientId) {
    return SystemContext.call("the associated-demo check",
        () -> tenantEnvironmentLifecycleService.isAssociatedWithProductive(clientId));
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
   * The tenant claims of a legacy bearer caller that has no cookie session: the single place this
   * servlet decodes an inbound bearer to read its environment ({@code resolveTenantSession} and the
   * checkout's {@code currentSessionClientId} both go through here, so the credential is read in
   * one spot — see {@code AuthenticationEntryPointGuardTest}). {@code null} when there is no bearer
   * or it does not decode.
   */
  private DecodedJWT bearerTenantClaims(HttpServletRequest request) {
    try {
      return SecureWebServicesUtils.decodeToken(extractBearerToken(request));
    } catch (Exception e) {
      log.debug("Bearer token carries no environment claims", e);
      return null;
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
      DecodedJWT jwt = bearerTenantClaims(request);
      if (jwt != null) {
        clientId = jwt.getClaim(JwtAuthUtils.CLAIM_CLIENT).asString();
        orgId = jwt.getClaim(JwtAuthUtils.CLAIM_ORG).asString();
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
    // the owner can pay. ETP-5047 — decided by the shared guard (kill switch and structured 402
    // body included); it allows a tenant that predates lifecycle metadata, the legacy transition.
    EnvironmentAccessGuard.Denial denial = environmentAccessGuard.check(clientId, "tenant-session");
    if (denial != null) {
      denial.writeTo(response);
      return null;
    }
    return new TenantSession(clientId, StringUtils.defaultIfBlank(orgId, "0"));
  }

  /**
   * Clears the account's saved onboarding draft once the environment is committed.
   *
   * <p><b>Written as system</b> (ETP-5047). The draft lives on {@code ETGO_ACCOUNT}, a client-0
   * row, and this runs after the onboarding commit with the thread in the new tenant's context:
   * the DAL write check refuses a client-0 row from there at flush ({@code OBSecurityException:
   * Client (0) ... is not present in ClientList}). That went unnoticed because the dirty account
   * stayed in the session and the ready email, which commits as system, persisted it by accident
   * — and on the paid-from-demo path a system context leaked by the demo transfer made the clear
   * itself succeed until that leak was fixed. {@link SystemContext} hands the tenant's context
   * back afterwards.
   *
   * <p>A failure is rolled back, so a refused account write never stays pending for the next
   * commit on this thread. {@code updateOnboardingDraft} commits its own work on success. The
   * rollback can also discard a checkout close left pending by a failed {@code recordProvisioned};
   * see {@link #startDemoDataTransferBestEffort} for that edge.
   *
   * @param account the account that drove the onboarding, may be null
   */
  private void clearOnboardingDraftBestEffort(Account account) {
    if (account == null) {
      return;
    }
    try {
      SystemContext.run("clearing the onboarding draft",
          () -> EtendoGoJwtDalHelper.updateOnboardingDraft(account, null));
    } catch (RuntimeException e) {
      log.warn("Clearing onboarding draft failed without blocking onboarding", e);
      EtendoGoDalHelper.rollbackDalChanges("onboarding draft clear", e, log);
    }
  }

  /**
   * ETP-5117: a tenant converting to productive must stop overriding the System-level
   * ETSG_ForceTestMode default (e.g. a tenant that started as Demo and got its own row via
   * {@link OnboardingForceTestModeService}). This operation is part of the paid onboarding
   * transaction and therefore propagates failures so the plan and fiscal mode cannot diverge.
   * See {@link OnboardingForceTestModeService}'s own javadoc ("The reverse direction") for why
   * this needs its own service call, not a one-liner.
   *
   * @param clientId the tenant just marked productive
   */
  private void revertTestModeForProductiveTenant(String clientId) {
    onboardingForceTestModeService.revertTestModeForProductiveTenant(clientId);
  }

  /**
   * ETP-5117: applies the side effects of a paid upgrade once {@code handleOnboarding}'s paywall
   * has approved the request — records that the tenant is productive, marks it in the lifecycle
   * projection and reverts any {@code ETSG_ForceTestMode} override (see
   * {@link #revertTestModeForProductiveTenant}). Runs as the last write before the onboarding
   * commit and joins that transaction.
   *
   * <p><b>ETP-5046: the subscription row is the record of the payment.</b> The
   * {@code ETGO_TenantPlan} preference is no longer written alongside it — a parallel store that is
   * written on every upgrade but read by nothing (since {@code resolvePlan} reads the subscription
   * first) is not a safety measure, it is a second answer that drifts. So:
   * <ul>
   *   <li><b>Subscription model OFF in this environment</b> ({@link SubscriptionModelSwitch}, the
   *       System-level {@code ETGO_SubscriptionModel} preference is not {@code Y}) <b>and the
   *       tenant has no subscription row</b> → no row is attempted and the preference IS written,
   *       exactly as before ETP-5046. The lifecycle webhooks then keep this rowless tenant's
   *       billing state in the preference projection. Each environment stays here until its
   *       operator switches the model on (and then runs the R37 backfill for the tenants already
   *       on the preference). A tenant that already has a
   *       row (any row) takes the subscription path below whatever the switch says, because its row
   *       is what every read answers from.</li>
   *   <li><b>Subscription write succeeds</b> → the preference is NOT written, and any stale
   *       {@code ETGO_TenantPlan} row this tenant still carries is retired
   *       ({@link TenantPlanService#retireProductivePreference}). A newly paid tenant therefore
   *       lands directly in the post-cutover state, exactly like a tenant the R37 backfill has
   *       processed.</li>
   *   <li><b>Subscription write fails</b> → the preference IS written, as the safety net. That is
   *       the one case where {@code TenantPlanPreferenceFallback} has to answer, and the one case
   *       where the marker is the only record that the tenant paid. Nothing is retired.</li>
   * </ul>
   * Once the switch is on, together with statement 3 of the R37 backfill this makes the rest of
   * the cutover a per-tenant state transition with an observable end condition
   * ({@code select count(*) from ad_preference where attribute='ETGO_TenantPlan'} reaching 0).
   * The switch itself is the one environment-level moment: before it, nothing moves. Grep marker
   * for the Phase F deletion: {@code ETP-5046-TRANSITIONAL-FALLBACK}.
   *
   * <p><b>Fails closed when the tenant would commit with contradictory metadata (ETP-5548).</b>
   * Paid provisioning is recoverable, so an exception here rolls the attempt back for a retry
   * instead of committing a paid tenant that reads back as free. It throws when neither the
   * subscription nor the fallback marker could be recorded, when the lifecycle projection cannot be
   * written, and when the demo override cannot be reverted. The two writes that stay best-effort
   * have a recorded alternative: a failed subscription is covered by the fallback marker, and a
   * failed retirement only leaves the fallback answering until R37 retires the row. Both are logged
   * as errors naming the environment and the masked account.
   *
   * <p>Package-visible rather than private so the specs can drive it directly, matching
   * {@link #ensureOnboardingDataset} and {@link #importOnboardingDataset}.
   *
   * @param clientId the tenant just created/resolved
   * @param starOrgId the tenant's "*" organization id, required by {@code markProductive}
   * @param clientName the onboarding request's client name, used only for the failure log line
   * @param accountEmail the account driving onboarding, masked in the failure log line
   * @param paymentToken the checkout request id this environment was paid under
   */
  void applyPaidUpgradeSideEffects(String clientId, String starOrgId, String clientName,
      String accountEmail, String paymentToken) {
    PaymentRecord paymentRecord = recordPaymentAsSubscription(clientId, clientName, accountEmail,
        paymentToken);
    if (paymentRecord == PaymentRecord.SUBSCRIPTION) {
      // The subscription IS the record now, so the preference is retired rather than written.
      // ETP-5046-TRANSITIONAL-FALLBACK — remove this call in Phase F with the fallback itself.
      retireTenantPlanPreferenceBestEffort(clientId, clientName, accountEmail);
    } else if (!tenantPlanService.markProductive(clientId, starOrgId)) {
      // ETP-5046-TRANSITIONAL-FALLBACK — the preference path: the subscription model is off in
      // this environment, or the row could not be opened. Either way this preference is the sole
      // record that the tenant paid, and TenantPlanPreferenceFallback is what will read it back.
      throw new OBException("Paid environment '" + clientName + "' (client " + clientId
          + ") for account " + maskEmail(accountEmail)
          + (paymentRecord == PaymentRecord.PREFERENCE_BY_MODEL
              // No subscription was attempted: the pre-ETP-5046 failure, in develop's words.
              ? " could not be marked as plan '"
              : " has neither a subscription nor the fallback plan marker '")
          + TenantPlanService.PLAN_PRODUCTIVE + "'");
    }
    if (!tenantEnvironmentLifecycleService.markProductive(clientId)) {
      throw new OBException("Paid environment '" + clientName + "' (client " + clientId
          + ") could not be marked in the lifecycle projection");
    }
    // A tenant must not commit with a productive plan and an active demo override.
    revertTestModeForProductiveTenant(clientId);
  }

  /**
   * ETP-5046 (ETP-5046-TRANSITIONAL-FALLBACK) — retires this tenant's stale {@code ETGO_TenantPlan}
   * preference once its subscription row exists. Delete with the rest of the fallback in Phase F.
   *
   * <p>Failing to retire the row is harmless: the transitional fallback just keeps answering for
   * that tenant and the R37 backfill retires it on its next run. So this must never be able to fail
   * an upgrade that has already been paid for — {@code retireProductivePreference} does not throw,
   * and this wrapper makes that guarantee local rather than remote.
   *
   * @param clientId the tenant whose subscription was just opened
   * @param clientName the onboarding request's client name, for the failure log line
   * @param accountEmail the account driving onboarding, masked in the failure log line
   */
  private void retireTenantPlanPreferenceBestEffort(String clientId, String clientName,
      String accountEmail) {
    try {
      tenantPlanService.retireProductivePreference(clientId);
    } catch (RuntimeException e) {
      log.error("Paid environment '{}' (client {}) for account {} has its subscription, but its "
          + "legacy {} preference could not be retired; the transitional fallback keeps answering "
          + "for it until the R37 backfill runs", clientName, clientId, maskEmail(accountEmail),
          TenantPlanService.PREFERENCE_ATTRIBUTE, e);
    }
  }

  /** Which store recorded a paid upgrade, as decided by {@link #recordPaymentAsSubscription}. */
  enum PaymentRecord {
    /** An {@code ETGO_SUBSCRIPTION} row records the payment. */
    SUBSCRIPTION,
    /** No row was attempted: the subscription model is off and the tenant has no row. */
    PREFERENCE_BY_MODEL,
    /** A row was attempted and could not be opened; the preference is the safety net. */
    PREFERENCE_AFTER_FAILED_SUBSCRIPTION
  }

  /**
   * Records the payment as an {@code ETGO_SUBSCRIPTION} row when this environment runs the
   * subscription model OR the tenant already has a row, and reports which store recorded it.
   *
   * <p><b>A tenant with a row is decided by the row — for writes too.</b> Every read
   * ({@code resolvePlan}, the access policy, the lifecycle webhooks) answers from a tenant's row
   * whenever it has one, so a payment recorded only in the {@code ETGO_TenantPlan} preference would
   * be invisible for such a tenant: a canceled or closed row would keep it free and blocked while it
   * pays. So the switch decides only for a tenant with NO row. "Has a row" means any row, open or
   * closed, active or not ({@link SubscriptionService#hasAnySubscription}) — the same test R37's
   * retirement branch uses, and one that stays correct whatever later tickets do to how the current
   * row is picked.
   *
   * <p>With the switch off and no row, nothing is attempted and the caller writes the preference —
   * the pre-ETP-5046 record, not a failure; it is logged at INFO, not ERROR. If the row lookup
   * itself fails, the purchase takes that same path and the failure is logged as an error.
   *
   * @param clientId the tenant just created/resolved
   * @param clientName the onboarding request's client name, for the log lines
   * @param accountEmail the account driving onboarding, masked in the log lines
   * @param paymentToken the checkout request id this environment was paid under
   * @return which store recorded the payment; anything but {@link PaymentRecord#SUBSCRIPTION}
   *     means the caller must write the preference
   */
  private PaymentRecord recordPaymentAsSubscription(String clientId, String clientName,
      String accountEmail, String paymentToken) {
    if (!subscriptionModelSwitch.isOn() && !tenantHasSubscriptionRow(clientId, clientName,
        accountEmail)) {
      log.info("The subscription model is off in this environment ({} is not '{}'); paid "
          + "environment '{}' (client {}) for account {} is recorded with the {} preference",
          SubscriptionModelSwitch.PREFERENCE_PROPERTY, SubscriptionModelSwitch.ON, clientName,
          clientId, maskEmail(accountEmail), TenantPlanService.PREFERENCE_ATTRIBUTE);
      return PaymentRecord.PREFERENCE_BY_MODEL;
    }
    return openSubscriptionBestEffort(clientId, clientName, accountEmail, paymentToken)
        ? PaymentRecord.SUBSCRIPTION
        : PaymentRecord.PREFERENCE_AFTER_FAILED_SUBSCRIPTION;
  }

  /**
   * Whether the tenant already has any subscription row. Never throws: a failed lookup is logged
   * and answered {@code false}, which keeps a switched-off environment on its preference path.
   *
   * <p><b>Trade-off, accepted (decided 2026-10-14, recorded in register §2.9).</b> Answering
   * {@code false} on a failed lookup means that a tenant which DID have a row would get its payment
   * recorded only in the marker — invisible behind that row. Since ETP-5047 a tenant whose only row
   * is closed reads as canceled, so such a tenant would stay canceled while it pays. A new purchase
   * never reaches a tenant with a closed row (it provisions a new environment, and since ETP-5047 a
   * canceled productive tenant is no longer a demo source). The ETP-5742 re-subscription does, and
   * it does not rely on this lookup: {@code applyReactivation} asks
   * {@link SubscriptionService#hasAnySubscription} itself, unguarded, before recording the payment,
   * so a failing lookup fails the payment webhook (which the provider redelivers) instead of
   * reaching the marker path. The acceptance holds anyway: it fails closed in practice. On PostgreSQL a failed SQL
   * statement aborts the transaction, so the onboarding writes that follow fail too and the upgrade
   * fails instead of being recorded in the marker. Only a failure other than a failed SQL statement
   * (a mapping or hydration error after the statement ran, for instance) could reach the marker
   * path with a usable transaction. Failing the purchase on every lookup error instead would trade
   * that edge for failing every rowless purchase whenever the lookup misbehaves.
   */
  private boolean tenantHasSubscriptionRow(String clientId, String clientName,
      String accountEmail) {
    try {
      return subscriptionService.hasAnySubscription(clientId);
    } catch (RuntimeException e) {
      log.error("Paid environment '{}' (client {}) for account {}: could not check for an existing "
          + "subscription row; recording the payment with the {} preference", clientName, clientId,
          maskEmail(accountEmail), TenantPlanService.PREFERENCE_ATTRIBUTE, e);
      return false;
    }
  }

  /**
   * Opens the tenant's subscription row from the checkout request it was bought under.
   *
   * <p>Best-effort and loud: a failure here is not a failed upgrade, because the caller then
   * records the payment through the fallback marker instead, and only fails the attempt when that
   * cannot be written either. Every failure is logged as an error naming the environment and the
   * masked account, so it is searchable rather than indistinguishable from a subscription that was
   * never attempted.
   *
   * <p>The checkout request is read through the store's own account-scoped lookup, which opens a
   * system {@link OBContext} and does not put the caller's back. Onboarding is mid-flight here and
   * every later step depends on the admin context it prepared, so the context is captured and
   * restored around the call.
   *
   * <p><b>The returned flag decides which store records the plan</b> (ETP-5046): on {@code true}
   * the subscription is the record and the legacy preference is retired; on {@code false} the
   * caller falls back to writing the preference, because it is then the only record that the tenant
   * paid. Every one of the three exits below — no checkout request, no plan on it, or a throwing
   * write — is a failure to record the subscription and therefore returns {@code false}.
   *
   * @param clientId the tenant just created/resolved
   * @param clientName the onboarding request's client name, for the failure log line
   * @param accountEmail the account driving onboarding, masked in the failure log line
   * @param paymentToken the checkout request id this environment was paid under
   * @return {@code true} when the subscription row was opened, {@code false} on any failure
   */
  private boolean openSubscriptionBestEffort(String clientId, String clientName,
      String accountEmail, String paymentToken) {
    try {
      CheckoutRequest checkoutRequest = findCheckoutRequestPreservingContext(paymentToken,
          accountEmail);
      if (checkoutRequest == null) {
        log.error("Paid environment '{}' (client {}) for account {} has no checkout request under "
            + "token '{}'; no subscription was opened", clientName, clientId,
            maskEmail(accountEmail), paymentToken);
        return false;
      }
      Plan plan = checkoutRequest.getPlan();
      if (plan == null) {
        log.error("Paid environment '{}' (client {}) for account {} was bought under a checkout "
            + "request carrying no plan; no subscription was opened", clientName, clientId,
            maskEmail(accountEmail));
        return false;
      }
      // The price the checkout actually charged, which under the legacy price fallback is the
      // configured legacy price rather than anything on the (priceless) grandfathered plan.
      subscriptionService.openSubscription(clientId, plan, checkoutRequest.getEtendoGoAccount(),
          checkoutRequest.getStripeCustomer(), checkoutRequest.getStripeSubscription(),
          checkoutRequest.getStripePrice());
      return true;
    } catch (RuntimeException e) {
      log.error("Paid environment '{}' (client {}) for account {} could not have its subscription "
          + "opened; falling back to the legacy plan marker", clientName, clientId,
          maskEmail(accountEmail), e);
      return false;
    }
  }

  /**
   * Reads a checkout request without letting the store's system context escape into the
   * onboarding flow.
   *
   * @param paymentToken the checkout request id
   * @param accountEmail authenticated account email, which the store matches on
   * @return the matching request, or null
   */
  private CheckoutRequest findCheckoutRequestPreservingContext(String paymentToken,
      String accountEmail) {
    OBContext previousContext = OBContext.getOBContext();
    try {
      return checkoutRequestStore.find(paymentToken, accountEmail);
    } finally {
      if (previousContext != null) {
        OBContext.setOBContext(previousContext);
      }
    }
  }

  /**
   * GET /sws/go/environments
   * Header: Authorization: Bearer &lt;session_token&gt;
   * Returns 200 with environments linked to the account, plus the account email as the
   * flag-targeting identity. Links via AD_User.username matching the account email.
   *
   * <p>Each environment carries three plan fields, read from its {@code ETGO_SUBSCRIPTION} row —
   * the open one, else the latest closed one (ETP-5047):
   * <ul>
   *   <li>{@code plan} — the original coarse {@code "free" | "productive"} value, unchanged since
   *       ETP-4686 and still what live clients branch on. Never null.</li>
   *   <li>{@code planKey} — the Subscription Plan Catalog key of the subscribed plan, or JSON
   *       null. A plan <em>key</em> names a plan catalog row and a free tenant has none, so this
   *       is null rather than {@code "free"}.</li>
   *   <li>{@code subscriptionStatus} — {@code active}, {@code past_due} or {@code canceled}, or
   *       JSON null when there is no subscription.</li>
   * </ul>
   *
   * <p>All three come from a single {@link EnvironmentPlanCache} built before the sort. Resolving
   * the plan per comparison would issue O(n log n) queries for one page render.
   *
   * <p><b>TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK).</b> Tenants with no active subscription
   * row, open or closed (a closed row answers for its tenant, as canceled, since ETP-5047), are
   * checked once, in one extra query, against the retired {@code ETGO_TenantPlan} preference, so
   * this list agrees with {@code TenantPlanService#resolvePlan} until the R37 backfill has run.
   * Such a tenant reports {@code plan = "productive"} with {@code planKey} and
   * {@code subscriptionStatus} both JSON null. Delete with the fallback in Phase F.
   *
   * <p>ETP-5734 — {@code ?details=true} also appends the per-company fields of
   * {@link EnvironmentDetails}. Opt-in so the list every app load reads stays as cheap as before.
   */
  private void handleEnvironments(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    boolean withDetails = Boolean.parseBoolean(request.getParameter(PARAM_DETAILS));
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
      // One query for every tenant on the page, resolved before the sort rather than inside it.
      // Built per request and passed down as a parameter — never cached in a field or a
      // ThreadLocal, because Tomcat pools request threads and the next request on this thread
      // belongs to a different account. See EnvironmentPlanCache.
      Set<String> environmentClientIds = new LinkedHashSet<>();
      for (User environmentUser : environmentUsers) {
        environmentClientIds.add(environmentUser.getClient().getId());
      }
      // ETP-5046-TRANSITIONAL-FALLBACK — the id set is passed too, so the cache can ask the
      // retired preference about the tenants that have no subscription row yet. Exactly one extra
      // query, only when at least one tenant is missing, and it keeps this list in agreement with
      // TenantPlanService#resolvePlan. Delete the extra argument in Phase F.
      EnvironmentPlanCache planCache = EnvironmentPlanCache.of(environmentClientIds,
          subscriptionService.findLatestForClients(environmentClientIds));
      // The first environment is entered automatically after account login. Prefer the paid
      // productive tenant so a demo tenant never unexpectedly becomes the active workspace when
      // an account owns both plans. The client repeats this ordering for older backends.
      environmentUsers.sort(Comparator
          .comparing((User user) -> planCache.isProductive(user.getClient().getId()))
          .reversed()
          .thenComparing(user -> StringUtils.defaultString(user.getClient().getName()),
              String.CASE_INSENSITIVE_ORDER));
      Map<String, Date> unfinishedPaidClients = findUnfinishedPaidClients(account);
      for (User environmentUser : environmentUsers) {
        if (!isUnfinishedPaidEnvironment(environmentUser.getClient(), unfinishedPaidClients)) {
          addEnvironmentEntries(envArray, environmentUser, planCache, withDetails);
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

  /** One entry per business organization of the user's client, or one without when it has none. */
  private static void addEnvironmentEntries(org.codehaus.jettison.json.JSONArray envArray,
      User environmentUser, EnvironmentPlanCache planCache, boolean withDetails)
      throws JSONException {
    Client client = environmentUser.getClient();
    JSONObject details = withDetails
        ? EnvironmentDetails.resolve(client.getId(), environmentUser,
            OwnerSupport.isOwner(environmentUser.getId()))
        : null;
    List<Organization> organizations =
        EtendoGoJwtDalHelper.findNonStarOrganizations(client.getId());
    if (organizations.isEmpty()) {
      envArray.put(withEnvironmentDetails(
          EtendoGoJwtDalHelper.buildEnvironmentJson(client, null, environmentUser, planCache),
          details));
      return;
    }
    for (Organization organization : organizations) {
      envArray.put(withEnvironmentDetails(EtendoGoJwtDalHelper.buildEnvironmentJson(client,
          organization, environmentUser, planCache), details));
    }
  }

  private static JSONObject withEnvironmentDetails(JSONObject env, JSONObject details)
      throws JSONException {
    if (details != null && env != null) {
      EnvironmentDetails.merge(env, details);
    }
    return env;
  }

  /**
   * Whether {@code client} is the tenant a paid purchase is still building, which must not be
   * offered as an environment until its checkout is provisioned.
   *
   * <p>The classic path commits the client while it builds it, so a failed or running paid attempt
   * leaves a client that exists but is incomplete. It is recognized by the purchase's company name
   * and by having been created at or after that purchase was paid. An environment older than the
   * payment (the demo a purchase may share its name with) is the customer's working tenant and
   * stays listed.
   *
   * <p>Since ETP-5548 a classic-path client keeps its {@link ProvisioningClientName} until the
   * attempt commits, so a client still carrying one is always hidden; the name match covers the
   * short window between that commit and the purchase being marked provisioned, and clients built
   * before the provisioning name existed.
   *
   * @param client listed tenant
   * @param unfinishedPaidClients from {@link CheckoutRequestStore#findUnfinishedPaidClientNames}
   * @return {@code true} when the tenant is hidden
   */
  static boolean isUnfinishedPaidEnvironment(Client client,
      Map<String, Date> unfinishedPaidClients) {
    if (client != null && ProvisioningClientName.matches(client.getName())) {
      return true;
    }
    if (client == null || unfinishedPaidClients == null || unfinishedPaidClients.isEmpty()) {
      return false;
    }
    String name = StringUtils.lowerCase(StringUtils.trimToNull(client.getName()), Locale.ROOT);
    Date paidAt = name == null ? null : unfinishedPaidClients.get(name);
    Date createdAt = client.getCreationDate();
    return paidAt != null && createdAt != null && !createdAt.before(paidAt);
  }

  /**
   * Fail-open lookup for {@link #isUnfinishedPaidEnvironment}: hiding an incomplete tenant is a
   * guard, so a failed lookup lists every environment, exactly as before the guard existed, rather
   * than leaving the account with no environments at all.
   */
  private Map<String, Date> findUnfinishedPaidClients(Account account) {
    try {
      return checkoutRequestStore.findUnfinishedPaidClientNames(account.getId(),
          account.getEmail());
    } catch (RuntimeException e) {
      log.warn("Could not read unfinished paid purchases for account {}; listing them all",
          maskEmail(account.getEmail()), e);
      return Map.of();
    }
  }

  /**
   * ETP-5047 — the commercial access decision for the tenant an environment user belongs to,
   * through the one guard NEO and MCP use too (decision, kill switch, error body). Runs in the
   * caller's system context.
   *
   * @param userId the {@code AD_User} being entered, already verified to belong to the account
   * @param entryPoint a short label for the log line
   * @return the denial, or null when access is allowed or the user no longer exists
   */
  private EnvironmentAccessGuard.Denial environmentAccessDenial(String userId, String entryPoint) {
    String clientId = clientIdOfUser(userId);
    return clientId == null ? null : environmentAccessGuard.check(clientId, entryPoint);
  }

  /**
   * ETP-5047 — the commercial access decision the guard enforces for the tenant an environment
   * user belongs to, for a caller that reports it without refusing ({@code POST
   * /session/environment}). Unlike {@link #environmentAccessDenial} it logs nothing: no request is
   * refused, so the guard's "Commercial access denied" line would be false.
   *
   * @param userId the {@code AD_User} being entered, already verified to belong to the account
   * @return the enforced decision; null when the tenant predates lifecycle metadata or the user no
   *     longer exists
   */
  private EnvironmentAccessPolicy.Decision enforcedAccessDecision(String userId) {
    String clientId = clientIdOfUser(userId);
    return clientId == null ? null : environmentAccessGuard.enforcedDecision(clientId);
  }

  private static String clientIdOfUser(String userId) {
    User user = OBDal.getInstance().get(User.class, userId);
    return user == null || user.getClient() == null ? null : user.getClient().getId();
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
      // ETP-5047 — this endpoint hands out a raw Etendo JWT, which reaches every secure web
      // service of the tenant, not only NEO: a blocked tenant must not get one.
      EnvironmentAccessGuard.Denial denial = environmentAccessDenial(userId, "environment-login");
      if (denial != null) {
        denial.writeTo(response);
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

    // Set up NDJSON streaming
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/x-ndjson");
    response.setCharacterEncoding(UTF_8);
    response.setHeader(HEADER_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);
    // Remembers the result line a failing step writes, so the run neither duplicates it nor
    // records a generic reason in place of the real one.
    OnboardingStreamWriter writer = new OnboardingStreamWriter(response.getWriter());

    // Keepalive: a background thread emits a blank NDJSON line on a fixed cadence so the
    // gap between bytes never exceeds the CloudFront/proxy inter-byte timeout while a slow
    // step runs. The frontend skips empty lines (processLines), so this needs no UI change.
    ScheduledExecutorService heartbeat = startOnboardingHeartbeat(writer);

    // Stop the keepalive before the final flush so no heartbeat races the result line.
    runOnboarding(preparation, writer, heartbeat::shutdownNow);
  }

  /**
   * Runs one prepared onboarding to its end over {@code writer} and records how it ended. This is
   * the transport-independent part of {@code POST /sws/go/onboarding}: the HTTP path calls it with
   * the response stream, and {@link PaidProvisioningService} (ETP-5048) calls it for a paid
   * purchase with no browser attached, with a writer that feeds an
   * {@link OnboardingProgressSink}; {@link #provisionSignupEnvironment} (ETP-5847) calls it for an
   * environment the signup API creates, with a log sink.
   *
   * <p>Provisioning has many graceful exits that write a result line and answer {@code false}
   * rather than throw; they are promoted to the same exception path as thrown failures, so the
   * transaction is rolled back before the failure diagnostic (which commits on its own) is written.
   *
   * @param preparation the validated request, already claimed when it is a paid one
   * @param writer the NDJSON stream; its first result line is the one kept
   * @param beforeFinalFlush runs after the outcome is recorded and before the final flush (the
   *     HTTP path stops its keepalive here); never null
   * @return the client id of the environment created, or {@code null} when it was not (the
   *     failure is already rolled back and recorded, and its reason is on {@code writer})
   */
  String runOnboarding(OnboardingPreparation preparation, OnboardingStreamWriter writer,
      Runnable beforeFinalFlush) {
    String accountEmail = preparation.accountEmail;
    boolean paidUpgrade = preparation.paidUpgrade;

    // Tracked across the try/catch/finally below. Provisioning has many graceful exits that
    // `return` after writing a result line rather than throwing, so the catch block alone would
    // miss most real failures — the dataset step is the common one.
    boolean provisioningCompleted = false;
    boolean rollbackConfirmed = false;
    String failureReason = null;
    String failureCode = null;
    ProvisioningAlertAttempt alertAttempt = new ProvisioningAlertAttempt(paidUpgrade);

    // Generate a random password for the admin user
    String adminPassword = UUID.randomUUID().toString().substring(0, 12);

    try {
      provisioningCompleted = executeOnboardingProvisioning(writer, preparation, adminPassword, alertAttempt);
      if (!provisioningCompleted) {
        // Boolean failure exits are promoted to the same exception path as thrown failures. This
        // is especially important for a pooled tenant: claim and personalization must be rolled
        // back before the diagnostic write in finally can commit its independent update.
        throw new OBException("Onboarding provisioning did not complete");
      }

    } catch (Exception e) {
      rollbackConfirmed = rollBackFailedOnboarding(e, alertAttempt);
      if (writer.hasResult() && !writer.isResultSuccess()) {
        // The failing step already told the client why, possibly with a stable code. That line is
        // the one the client keeps; a second, generic result would replace it.
        failureReason = writer.getResultMessage();
        failureCode = writer.getResultCode();
      } else {
        sendProgress(writer, PROGRESS_ERROR, PROGRESS_ERROR,
            "Onboarding failed: " + e.getMessage());
        sendFinalResult(writer, false, "Onboarding failed: " + e.getMessage());
        failureReason = e.getMessage();
      }
    } finally {
      recordOnboardingOutcome(new OnboardingOutcome(provisioningCompleted, rollbackConfirmed,
          failureReason, failureCode), preparation, alertAttempt);
      beforeFinalFlush.run();
      OBContext.restorePreviousMode();
      writer.flush();
      warnWhenOnboardingStreamWasLost(writer, accountEmail);
    }
    return provisioningCompleted ? alertAttempt.clientId : null;
  }

  /**
   * ETP-5048 — provisions a paid purchase with no browser attached, for
   * {@link PaidProvisioningService}.
   *
   * <p>The preparation is the HTTP path's, minus what only a request has: the account and the
   * inputs come from the checkout row instead of a session and a body, and a refusal is returned
   * instead of written. Then the same fenced claim and the same {@link #runOnboarding}. The email
   * verification gate is not repeated: the purchase it guards has already been paid.
   *
   * @param input what the browser would have sent, resolved from the checkout row
   * @param sink receives the run's progress and result lines
   * @return how the attempt ended
   */
  PaidProvisioningService.Outcome provisionPaidPurchase(PaidProvisioningService.PaidInput input,
      OnboardingProgressSink sink) {
    OnboardingRequestData data = new OnboardingRequestData();
    data.clientName = input.clientName();
    data.currencyIso = input.currencyIso();
    data.language = input.language();
    data.countryCode = input.countryCode();
    data.address = serverSideAddress(input);
    data.fullName = EtendoGoJwtSupport.resolveOwnerDisplayName(input.fullName(),
        input.accountName(), input.accountEmail());
    data.taxId = serverSideTaxId(input);
    data.paymentToken = input.requestId();
    data.upgradeAction = "create-productive";
    data.includeSampleData = false;
    String accountId = input.accountId();
    String accountEmail = input.accountEmail();

    String currencyId = findCurrencyId(data.currencyIso);
    if (currencyId == null) {
      return PaidProvisioningService.Outcome.of(PaidProvisioningService.Status.NEEDS_REVIEW,
          "UNKNOWN_CURRENCY", "No currency with ISO code " + data.currencyIso);
    }
    if (isPaidOnboardingNotOwned(accountEmail)) {
      return PaidProvisioningService.Outcome.of(PaidProvisioningService.Status.NEEDS_REVIEW,
          BILLING_OWNER_REQUIRED, BILLING_OWNER_MESSAGE);
    }
    TenantPaywallService.Outcome paywall = evaluatePaywall(accountId, accountEmail, data);
    if (paywall.getDecision().isBlocked() || !paywall.isProductive()) {
      return PaidProvisioningService.Outcome.of(PaidProvisioningService.Status.NOT_PAID, null,
          "The paywall does not confirm this purchase as paid");
    }
    String selectionRefusal = restorePaidSelection(data, accountId, accountEmail);
    if (selectionRefusal != null) {
      return PaidProvisioningService.Outcome.of(PaidProvisioningService.Status.NEEDS_REVIEW,
          CODE_DEMO_SELECTION_REQUIRED, selectionRefusal);
    }
    Long attempt = claimPaid(data.paymentToken, accountId, accountEmail);
    if (attempt == null) {
      return PaidProvisioningService.Outcome.of(PaidProvisioningService.Status.NOT_CLAIMED, null,
          "Purchase is " + checkoutRequestStore.deriveProvisioningStatus(
              checkoutRequestStore.find(data.paymentToken, accountId, accountEmail)));
    }
    OnboardingPreparation preparation = new OnboardingPreparation(accountId, accountEmail, data,
        currencyId, true, attempt);
    OnboardingStreamWriter writer = new OnboardingStreamWriter(
        new PrintWriter(new NdjsonSinkWriter(sink)));
    boolean completed = runOnboarding(preparation, writer, () -> { }) != null;
    if (completed) {
      return new PaidProvisioningService.Outcome(PaidProvisioningService.Status.PROVISIONED, null,
          "Environment created", attempt);
    }
    return new PaidProvisioningService.Outcome(PaidProvisioningService.Status.FAILED,
        StringUtils.defaultIfBlank(writer.getResultCode(),
            ProvisioningFailureReason.CODE_PROVISIONING_FAILED),
        StringUtils.left(StringUtils.normalizeSpace(writer.getResultMessage()), 200), attempt);
  }

  /**
   * ETP-5048 — the company address of a server-side provisioning: the draft's, under the limit
   * the HTTP path enforces. Too long a value is dropped, not truncated: the owner fixes it in the
   * Organization window, while a provisioning that fails over it helps nobody.
   */
  private static String serverSideAddress(PaidProvisioningService.PaidInput input) {
    String address = StringUtils.trimToEmpty(input.address());
    if (OnboardingFieldLimits.exceeds(address, OnboardingFieldLimits.ADDRESS)) {
      log.warn("Server-side provisioning of {}: the draft address exceeds {} characters and is "
          + "left out", input.requestId(), OnboardingFieldLimits.ADDRESS);
      return "";
    }
    return address;
  }

  /**
   * ETP-5048 — the fiscal identifier of a server-side provisioning: the draft's, when it passes
   * the check the HTTP path runs before streaming ({@link #validateOnboardingTaxId}). A value that
   * fails it is left out rather than failing the provisioning; the owner sets it later.
   */
  private static String serverSideTaxId(PaidProvisioningService.PaidInput input) {
    String taxId = StringUtils.trimToEmpty(input.taxId());
    if (taxId.isEmpty()
        || !SpanishTaxIdValidator.SPAIN_COUNTRY_CODE.equalsIgnoreCase(input.countryCode())) {
      return taxId;
    }
    SpanishTaxIdValidator.Result check = SpanishTaxIdValidator.validate(taxId);
    if (check == SpanishTaxIdValidator.Result.BAD_FORMAT
        || check == SpanishTaxIdValidator.Result.BAD_CHECK_DIGIT) {
      log.warn("Server-side provisioning of {}: the draft fiscal identifier is not valid ({}) "
          + "and is left out", input.requestId(), check);
      return "";
    }
    return taxId;
  }

  /** Outcome of {@link #provisionSignupEnvironment}: the client created, or why there is none. */
  record SignupProvisioningResult(String clientId, String failure) {
    boolean created() {
      return clientId != null;
    }
  }

  /**
   * ETP-5847 — creates the demo environment of an account the signup API registered, with no
   * HTTP request behind it: the same pipeline as {@code POST /onboarding}, fed from the company
   * data the signup stored as the account's onboarding draft. The ready mail is left to the
   * signup API ({@link SignupAccountService}), which decides whether to send it and with which
   * link.
   *
   * <p>Only a free demo: an account that the paywall would charge, or refuses, is not provisioned.
   *
   * @param form the onboarding draft's form, with the keys {@code POST /onboarding} takes
   * @param progress where the steps are written; nobody is streaming them, it is a log sink
   */
  SignupProvisioningResult provisionSignupEnvironment(String accountId, String accountEmail,
      String accountName, JSONObject form, OnboardingStreamWriter progress) {
    OnboardingRequestData onboardingRequest = new OnboardingRequestData();
    onboardingRequest.clientName = form.optString(FIELD_CLIENT_NAME, "").trim();
    onboardingRequest.currencyIso = form.optString(FIELD_CURRENCY, "EUR").trim();
    onboardingRequest.language = form.optString(FIELD_LANGUAGE, "en_US").trim();
    onboardingRequest.countryCode = form.optString(FIELD_COUNTRY_CODE, "ES").trim();
    onboardingRequest.address = "";
    onboardingRequest.taxId = form.optString(FIELD_FISCAL_ID_VALUE, "").trim();
    onboardingRequest.paymentToken = "";
    onboardingRequest.upgradeAction = "create-productive";
    onboardingRequest.includeSampleData = form.optBoolean(FIELD_INCLUDE_SAMPLE_DATA, false);
    onboardingRequest.fullName = EtendoGoJwtSupport.resolveOwnerDisplayName(
        form.optString(FIELD_FULL_NAME, ""), accountName, accountEmail);
    onboardingRequest.skipEnvironmentReadyMail = true;

    String currencyId = findCurrencyId(onboardingRequest.currencyIso);
    if (currencyId == null) {
      return new SignupProvisioningResult(null,
          "Unknown currency: " + onboardingRequest.currencyIso);
    }
    TenantPaywallService.Outcome paywall =
        evaluatePaywall(accountId, accountEmail, onboardingRequest);
    if (paywall.getDecision().isBlocked() || paywall.isProductive()) {
      return new SignupProvisioningResult(null,
          "The account cannot create a free demo environment");
    }
    String clientId = runOnboarding(new OnboardingPreparation(accountId, accountEmail,
        onboardingRequest, currencyId, false, null), progress, () -> { });
    return clientId != null
        ? new SignupProvisioningResult(clientId, null)
        : new SignupProvisioningResult(null,
            StringUtils.defaultIfBlank(progress.getResultMessage(), "Onboarding failed"));
  }

  /**
   * Rolls a failed onboarding back and sends its internal alert, only once the rollback is
   * confirmed: an alert must never report an outcome the database may not hold.
   *
   * @return whether the rollback was confirmed
   */
  private boolean rollBackFailedOnboarding(Exception e, ProvisioningAlertAttempt alertAttempt) {
    log.error("Onboarding failed", e);
    boolean rollbackConfirmed =
        EtendoGoDalHelper.rollbackDalChangesAndConfirm("onboarding", e, log);
    if (!rollbackConfirmed) {
      // The request catches this failure, so the outer DAL cleaner cannot infer an error.
      org.openbravo.dal.core.SessionHandler.getInstance().setDoRollback(true);
    }
    if (alertAttempt.committed) {
      return rollbackConfirmed;
    }
    if (rollbackConfirmed) {
      notifyProvisioningAlert(alertAttempt,
          com.etendoerp.go.schemaforge.email.InternalAlertEvent.Status.ERROR,
          e.getClass().getSimpleName());
    } else {
      log.warn("Internal provisioning alert suppressed because rollback did not complete attempt={}",
          alertAttempt.attemptId);
    }
    return rollbackConfirmed;
  }

  /** How an onboarding attempt ended, as the failure diagnostic needs it. */
  private static final class OnboardingOutcome {
    private final boolean provisioningCompleted;
    private final boolean rollbackConfirmed;
    private final String failureReason;
    private final String failureCode;

    private OnboardingOutcome(boolean provisioningCompleted, boolean rollbackConfirmed,
        String failureReason, String failureCode) {
      this.provisioningCompleted = provisioningCompleted;
      this.rollbackConfirmed = rollbackConfirmed;
      this.failureReason = failureReason;
      this.failureCode = failureCode;
    }
  }

  /**
   * Records the failure diagnostic of a paid attempt, unless the rollback failed: the diagnostic
   * store commits its own transaction and must never commit unresolved tenant changes.
   */
  private void recordOnboardingOutcome(OnboardingOutcome outcome,
      OnboardingPreparation preparation, ProvisioningAlertAttempt alertAttempt) {
    if (outcome.provisioningCompleted || outcome.rollbackConfirmed) {
      recordProvisioningFailureReason(preparation.paidUpgrade, outcome.provisioningCompleted,
          preparation.request, outcome.failureReason, outcome.failureCode);
    } else if (preparation.paidUpgrade) {
      log.warn("Provisioning failure diagnostic suppressed because rollback did not complete "
          + "attempt={} stage={}", alertAttempt.attemptId, alertAttempt.stage);
    }
  }

  private boolean executeOnboardingProvisioning(PrintWriter writer,
      OnboardingPreparation preparation, String adminPassword,
      ProvisioningAlertAttempt alertAttempt) throws Exception {
    long onboardingStartedAt = System.nanoTime();
    String correlationId = alertAttempt.attemptId;
    OnboardingRequestData onboardingRequest = preparation.request;
    String accountId = preparation.accountId;
    String accountEmail = preparation.accountEmail;
    String currencyId = preparation.currencyId;
    boolean paidUpgrade = preparation.paidUpgrade;
    alertAttempt.stage = "admin_context";
    VariablesSecureApp vars = prepareAdminContext(writer, onboardingRequest.language);
    // ETP-5389: a pre-provisioned tenant when the pool can serve this request, otherwise null and
    // everything below is the classic path, unchanged.
    alertAttempt.stage = "tenant_selection";
    if (paidUpgrade && rejectDuplicateProductiveName(writer, onboardingRequest, accountEmail)) {
      return false;
    }
    String provisioningName = ProvisioningClientName.of(accountId, onboardingRequest.paymentToken);
    String clientId = selectTenant(writer, vars, preparation, provisioningName, adminPassword,
        alertAttempt);
    boolean pooled = PATH_POOL.equals(alertAttempt.path);
    String mode = pooled ? "pool" : "classic";
    log.info("[ONBOARDING-PERF] phase=tenant_selection mode={} correlationId={} clientId={} "
        + "elapsedMs={}", mode, correlationId, clientId,
        elapsedMillis(onboardingStartedAt));
    alertAttempt.clientId = clientId;
    alertAttempt.stage = pooled ? "pooled_admin" : "classic_admin";
    if (clientId == null) return false;
    long residualStartedAt = System.nanoTime();
    String demoSourceClientId = resolveDemoSourceClientId(paidUpgrade, onboardingRequest);
    OnboardingProvisioningChain.AdminContext adminContext =
        resolveAdminContextData(clientId, writer);
    if (adminContext == null) return false;
    alertAttempt.stage = PROGRESS_ORGANIZATION;
    if (!pooled && ensureOrganization(writer, onboardingRequest.clientName, clientId,
        adminContext, currencyId) == null) return false;
    String orgId = resolveOrganizationId(clientId);
    if (orgId == null) {
      sendProgress(writer, PROGRESS_DATASET, PROGRESS_ERROR,
          "Could not resolve organization for onboarding dataset import");
      sendFinalResult(writer, false, "Organization not found after onboarding");
      return false;
    }
    alertAttempt.stage = pooled ? "pooled_dataset" : "classic_dataset";
    boolean provisioned = pooled
        ? finishPooledTenant(writer, clientId, orgId, adminContext, onboardingRequest)
        : ensureOnboardingDataset(writer, clientId, orgId, adminContext.adminUserId,
            adminContext.adminRoleId, onboardingRequest);
    if (!provisioned) return false;
    finishTenantMetadata(preparation, alertAttempt, clientId, orgId, demoSourceClientId,
        adminContext, pooled ? null : provisioningName);
    alertAttempt.stage = "commit";
    EtendoGoDalHelper.commitDalChanges("onboarding", log);
    alertAttempt.committed = true;
    alertAttempt.stage = "committed";
    notifyProvisioningAlert(alertAttempt,
        com.etendoerp.go.schemaforge.email.InternalAlertEvent.Status.OK, null);
    log.info("[ONBOARDING-PERF] phase=onboarding_residual mode={} correlationId={} clientId={} "
        + "elapsedMs={}", mode, correlationId, clientId,
        elapsedMillis(residualStartedAt));
    // Before completeCommittedOnboarding and the sample data, which needs a demo's open periods.
    applyInitialPeriodStateBestEffort(clientId);
    completeCommittedOnboarding(accountId, accountEmail, onboardingRequest, clientId, paidUpgrade,
        preparation.provisioningClaim, demoSourceClientId);
    if (paidUpgrade) {
      // ETP-5047: after the checkout is closed, never before — see the method.
      fillSubscriptionPeriodBestEffort(clientId);
    }
    // ETP-5426: after the commit (a failure here can no longer undo the tenant) and before the
    // costing schedule is activated (so the background costing never races the import).
    importSampleDataBestEffort(writer, clientId, orgId, adminContext, onboardingRequest,
        paidUpgrade);
    onboardingCostingScheduleService.activateSchedule(clientId);
    sendProgress(writer, "finalize", PROGRESS_IN_PROGRESS, "Finalizing setup...");
    sendProgress(writer, "finalize", "done", "Environment ready");
    sendFinalResult(writer, true, "Environment created successfully");
    return true;
  }

  /**
   * Picks the tenant for this attempt and records the path taken on {@code alertAttempt}: the
   * half-built client this same attempt left behind (never replaced by a pooled one), else a
   * pooled tenant, else a new classic client created under {@code provisioningName}.
   *
   * @return the tenant's client id, or {@code null} when none could be resolved or created
   */
  private String selectTenant(PrintWriter writer, VariablesSecureApp vars,
      OnboardingPreparation preparation, String provisioningName, String adminPassword,
      ProvisioningAlertAttempt alertAttempt) throws Exception {
    OnboardingRequestData request = preparation.request;
    boolean resumable = EtendoGoJwtSupport.findClientIdByName(provisioningName) != null;
    String pooledClientId = resumable ? null : claimPooledTenant(writer, preparation.accountId,
        preparation.accountEmail, request, adminPassword, alertAttempt.attemptId);
    if (pooledClientId != null) {
      alertAttempt.path = PATH_POOL;
      return pooledClientId;
    }
    alertAttempt.path = "CLASSIC";
    return resolveOrCreateClient(writer, vars, preparation.accountEmail, request,
        provisioningName, preparation.currencyId, adminPassword);
  }

  /**
   * The last writes before the commit: the demo's company profile and the productive markers of a
   * paid attempt, or the demo trial of a free one, then the requested name of a classic client.
   *
   * @param provisioningName name a classic client was built under; {@code null} for a pooled one
   */
  private void finishTenantMetadata(OnboardingPreparation preparation,
      ProvisioningAlertAttempt alertAttempt, String clientId, String orgId,
      String demoSourceClientId, OnboardingProvisioningChain.AdminContext adminContext,
      String provisioningName) {
    OnboardingRequestData request = preparation.request;
    alertAttempt.stage = "lifecycle";
    if (preparation.paidUpgrade) {
      transferDemoCompanyProfile(preparation.accountEmail, demoSourceClientId, clientId, orgId);
      // Last write before the commit on purpose. The classic path commits internally while it
      // builds the tenant (InitialClientSetup, InitialOrgSetup), so a productive marker written
      // any earlier would be committed by those steps and survive a later failure, leaving a
      // paid-but-incomplete environment that reads back as productive. Written here, the plan,
      // lifecycle and override removal only exist if every step above succeeded. A classic
      // dataset pass may have turned the ETSG force-test-mode override on meanwhile, because the
      // plan still read free then; the override removal below reverts and deletes that row.
      alertAttempt.stage = "productive_metadata";
      applyPaidUpgradeSideEffects(clientId, adminContext.starOrgId, request.clientName,
          preparation.accountEmail, request.paymentToken);
    } else if (!tenantEnvironmentLifecycleService.markDemoReady(clientId, Instant.now())) {
      throw new IllegalStateException("Could not initialize demo trial lifecycle");
    }
    // ETP-5577: in the onboarding transaction, so the markers exist exactly when the tenant does.
    onboardingInitialPeriodStateService.markPending(clientId, orgId, adminContext.adminUserId,
        adminContext.adminRoleId, preparation.paidUpgrade);
    if (provisioningName != null) {
      // After every step that names something after the client, and in the final transaction,
      // so a failure leaves the client under its provisioning name for the retry to find.
      alertAttempt.stage = "client_name";
      applyRequestedClientName(clientId, orgId, provisioningName, request.clientName);
    }
  }

  /**
   * ETP-5577 — applies the tenant's initial fiscal-period state (see
   * {@link OnboardingInitialPeriodStateService}): a demo's periods through the end of its trial
   * (ETP-5575); for a productive tenant, its signup years, all left never-opened until the owner
   * reviews the calendar. Runs after the onboarding commit, and again on environment login while the
   * state is still pending.
   *
   * <p>Runs in its own transaction and never fails the caller: the tenant is already committed, so
   * on any failure (the commit included, which is why it sits inside the try) this step rolls back
   * only itself and its pending marker survives for the next login. The started/done markers make a
   * step that never finished visible in the logs.
   */
  void applyInitialPeriodStateBestEffort(String clientId) {
    try {
      if (onboardingInitialPeriodStateService.applyIfPending(clientId)) {
        EtendoGoDalHelper.commitDalChanges("initial period state", log);
        log.info("ETP-5577 initial period state done for client {}", clientId);
      }
    } catch (Exception e) { // NOSONAR - best effort: the committed tenant must survive any failure
      log.error("ETP-5577 initial period state failed for client {}", clientId, e);
      EtendoGoDalHelper.rollbackDalChanges("initial period state", e, log);
    }
  }

  /**
   * ETP-5047 — gives the subscription row a paid onboarding just opened its Stripe billing period,
   * read from the provider. The purchase's first {@code invoice.paid} and
   * {@code customer.subscription.updated} arrived before the row existed and were ignored for
   * good, so without this the row stays periodless until its first renewal.
   *
   * <p><b>Runs after {@code completeCommittedOnboarding}, not right after the commit.</b> Until
   * {@code recordProvisioned} closes the checkout request it is not {@code PROVISIONED} and names
   * no created client; a retry after a crash in that window (once the provisioning lease expires)
   * cannot find the tenant, which by then carries its requested name, and would provision a second
   * one for the same payment. The provider call takes seconds at worst, so it must not widen that
   * window. Nothing here needs the checkout open: the fill reads only the subscription row.
   *
   * <p>The service requires an empty DAL session: it closes the session before the provider call,
   * so neither a lock nor a pooled connection is held while Stripe answers, and writes the period
   * in a fresh transaction that is committed here. Each step of
   * {@code completeCommittedOnboarding} commits its own work when it succeeds; a failed demo data
   * transfer start, draft clear (which runs as system, the account being a client-0 row) or ready
   * email rolls its own work back. Only a failed checkout close ({@code recordProvisioned}) just
   * logs, by design: if its fenced {@code PROVISIONED} update ran and only the flush or commit
   * after it failed, the update is still pending in the transaction and the next successful commit
   * persists it, where a rollback would discard a correct close. When it is still pending here,
   * the service's session close before the provider call commits it, as the sample data and
   * costing steps after this one would otherwise. See {@link #startDemoDataTransferBestEffort} for
   * the edge where a later step's rollback discards it instead. It never fails the
   * onboarding: the row is already committed, and on any failure (the commit included, which is
   * why it sits inside the try) only this step rolls back. Provider failures are WARNs from the
   * reader; see {@link SubscriptionService#fillPeriodFromProviderAfterCommit}.
   *
   * @param clientId the tenant just committed
   */
  void fillSubscriptionPeriodBestEffort(String clientId) {
    try {
      if (subscriptionService.fillPeriodFromProviderAfterCommit(clientId)) {
        EtendoGoDalHelper.commitDalChanges("subscription billing period", log);
      }
    } catch (Exception e) { // NOSONAR - best effort: the committed tenant must survive any failure
      log.warn("The subscription of client {} could not be given its billing period; it stays "
          + "without one until its next lifecycle event", clientId, e);
      EtendoGoDalHelper.rollbackDalChanges("subscription billing period", e, log);
    }
  }

  /**
   * ETP-5577 — retries a tenant's initial period state on environment login while it is still
   * pending (its post-onboarding step failed). Last on the success path: the login's own work is
   * committed first, so a failure here rolls back only the retry. Never throws.
   */
  void retryInitialPeriodStateOnLogin(String clientId) {
    try {
      if (clientId == null || !onboardingInitialPeriodStateService.isPending(clientId)) {
        return;
      }
      EtendoGoDalHelper.commitDalChanges("environment login", log);
    } catch (Exception e) { // NOSONAR - the login answer is already written
      log.warn("ETP-5577 could not check the initial period state of client {}", clientId, e);
      return;
    }
    applyInitialPeriodStateBestEffort(clientId);
  }

  /**
   * ETP-5426 — imports the optional sample data into the tenant just committed, when the request
   * opted in and is eligible (see {@link OnboardingSampleDataService#isEligible}).
   *
   * <p>Runs in its own transaction, after the onboarding commit, so it applies equally to a tenant
   * claimed from the pool and to a classic one. Best effort by contract: any failure is rolled
   * back — discarding only the sample data — and reported as a {@code warning} progress line; the
   * onboarding still ends with a successful result.
   */
  void importSampleDataBestEffort(PrintWriter writer, String clientId, String orgId,
      OnboardingProvisioningChain.AdminContext adminContext, OnboardingRequestData request,
      boolean paidUpgrade) {
    if (!OnboardingSampleDataService.isEligible(request.includeSampleData, paidUpgrade,
        request.countryCode, request.currencyIso)) {
      if (request.includeSampleData) {
        log.info("Sample data requested but not eligible for client {} (paid={}, country={}, "
            + "currency={}); skipping", clientId, paidUpgrade, request.countryCode,
            request.currencyIso);
      }
      return;
    }
    sendProgress(writer, PROGRESS_SAMPLE_DATA, PROGRESS_IN_PROGRESS, "Loading sample data...");
    try {
      onboardingSampleDataService.importSampleData(clientId, orgId, adminContext.adminUserId,
          adminContext.adminRoleId);
      EtendoGoDalHelper.commitDalChanges("onboarding sample data", log);
      sendProgress(writer, PROGRESS_SAMPLE_DATA, PROGRESS_DONE, "Sample data loaded");
    } catch (Exception e) { // NOSONAR — best effort by contract: the tenant must survive any failure.
      log.error("Sample data import failed for client {}; the tenant is kept without it",
          clientId, e);
      EtendoGoDalHelper.rollbackDalChanges("onboarding sample data", e, log);
      sendProgress(writer, PROGRESS_SAMPLE_DATA, PROGRESS_WARNING,
          "Sample data could not be loaded");
    }
  }

  /**
   * ETP-5389 — takes a pre-provisioned tenant for this request, or answers {@code null} so the
   * caller runs the classic path. See {@link PooledTenantClaimService} for when that happens.
   */
  private String claimPooledTenant(PrintWriter writer, String accountId, String accountEmail,
      OnboardingRequestData request, String adminPassword, String correlationId) {
    String fixtureClientId = devProvisioningFailureFixtureService.reservedClientId(
        request.paymentToken, accountId, accountEmail);
    if (DevProvisioningFailureFixtureService.isFixtureRequest(request.paymentToken)
        && fixtureClientId == null) {
      throw new OBException("Dedicated E2E fixture reservation is unavailable for this account");
    }
    NdjsonOnboardingProgressSink sink = new NdjsonOnboardingProgressSink(writer);
    PooledTenantClaimService.ClaimRequest claimRequest =
        new PooledTenantClaimService.ClaimRequest(accountEmail, request.clientName,
            request.fullName, request.currencyIso, request.countryCode, request.language,
            request.address, adminPassword);
    return fixtureClientId == null
        ? pooledTenantClaimService.claim(sink, claimRequest, correlationId)
        : pooledTenantClaimService.claim(sink, claimRequest, correlationId, fixtureClientId,
            request.paymentToken);
  }

  private static long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000L;
  }

  /**
   * ETP-5389 — the per-request steps a claimed pooled tenant still needs. The pool already ran the
   * whole chain; only the steps that consume signup data are repeated: {@code orgInfo} (tax id —
   * the address was written by the claim) and {@code warehouseAddress}, which re-copies the now
   * complete fiscal address. Both are the chain's own idempotent steps.
   */
  private boolean finishPooledTenant(PrintWriter writer, String clientId, String orgId,
      OnboardingProvisioningChain.AdminContext adminContext, OnboardingRequestData request) {
    if (devProvisioningFailureFixtureService.shouldFail(request.paymentToken)) {
      throw new IllegalStateException("E2E fixture forced pooled tenant finalization failure");
    }
    return wireOrgInfo(writer, clientId, orgId, adminContext.adminUserId,
        adminContext.adminRoleId, request)
        && wireWarehouseAddress(writer, clientId, orgId, adminContext.adminUserId,
            adminContext.adminRoleId);
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
    // ETP-5689: an additional environment arrives without a full name; fall back to the
    // account's name (then its email) here, once, so both the pooled and the classic path name
    // the owner after the person instead of the client-suffixed username.
    onboardingRequest.fullName = EtendoGoJwtSupport.resolveOwnerDisplayName(
        onboardingRequest.fullName, authenticated.account.getName(), accountEmail);
    String currencyId = resolveCurrencyId(onboardingRequest.currencyIso, response);
    if (currencyId == null
        || rejectWhenPaidOnboardingIsNotOwned(accountEmail, onboardingRequest, response)) {
      return null;
    }
    PaywallOutcome paywallOutcome =
        resolveOnboardingPaywall(accountId, accountEmail, onboardingRequest, response);
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
    String refusal = restorePaidSelection(onboardingRequest, accountId, accountEmail);
    if (refusal != null) {
      writeError(response, HttpServletResponse.SC_CONFLICT, CODE_DEMO_SELECTION_REQUIRED, refusal,
          refusal);
      return false;
    }
    return true;
  }

  /**
   * Restores the demo source a paid purchase recorded onto {@code onboardingRequest}, or clears it
   * when the purchase recorded none or its source can no longer be one.
   *
   * @return null when the setup may continue, otherwise why the recorded demo cannot be used
   */
  private String restorePaidSelection(OnboardingRequestData onboardingRequest,
      String accountId, String accountEmail) {
    boolean selectionRecorded = checkoutRequestStore.hasRecordedDemoSelection(
        onboardingRequest.paymentToken, accountId, accountEmail);
    if (!selectionRecorded) {
      // Legacy paid requests never saved a source or transfer intent. Resume them as a new
      // productive environment without inferring a demo or copying/revoking any tenant.
      onboardingRequest.demoClientId = null;
      return null;
    }

    String persistedDemoClientId = checkoutRequestStore.findDemoClientId(
        onboardingRequest.paymentToken, accountId, accountEmail);
    if (StringUtils.isNotBlank(persistedDemoClientId) && isAssociatedDemo(persistedDemoClientId)) {
      // Two purchases started from the same demo: the first one to finish took it as its origin.
      // This one is already paid, so it is not refused — it becomes a clean productive
      // environment, without a source, data transfer or demo revocation (ETP-5548).
      log.info("Demo {} already originated a productive environment; the paid setup continues "
          + "without a source", persistedDemoClientId);
      onboardingRequest.demoClientId = null;
      return null;
    }
    if (StringUtils.isNotBlank(persistedDemoClientId)
        && isProductiveEnvironment(persistedDemoClientId)) {
      // ETP-5047 — a purchase recorded a productive environment as its source (a canceled one
      // reads plan free) before the checkout stopped accepting one. It is already paid, so like
      // the spent demo above it is not refused: it becomes a clean productive environment.
      log.info("Source {} of the paid setup is not usable as a demo source (a productive "
          + "environment, or its type could not be read); the paid setup continues without a "
          + "source", persistedDemoClientId);
      onboardingRequest.demoClientId = null;
      return null;
    }
    Set<String> currentFreeDemoClientIds = findFreeDemoClientIdsForAccount(accountEmail);
    try {
      onboardingRequest.demoClientId = resolvePaidDemoClientId(true, persistedDemoClientId,
          currentFreeDemoClientIds);
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
    return null;
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
    Long attempt = claimPaid(onboardingRequest.paymentToken, accountId, accountEmail);
    if (attempt != null) {
      return attempt;
    }
    writeClaimRefusal(response, checkoutRequestStore.find(onboardingRequest.paymentToken,
        accountId, accountEmail));
    return null;
  }

  /**
   * The fenced claim of a paid request: of two concurrent callers exactly one gets an attempt.
   *
   * @return the attempt token now holding the request, or null when it could not be claimed
   */
  private Long claimPaid(String paymentToken, String accountId, String accountEmail) {
    if (!checkoutRequestStore.claimForProvisioning(paymentToken, accountId, accountEmail)) {
      return null;
    }
    Long attempt = checkoutRequestStore.findProvisioningAttempt(paymentToken, accountId,
        accountEmail);
    // A successful conditional update always has a persisted attempt. Keep the success path
    // recoverable even if a legacy database omits that value.
    return attempt == null ? 0L : attempt;
  }

  /**
   * Explains why a paid request could not be claimed. A failure no retry can fix and an
   * environment already set up are refused for good, so neither may answer "still running": the
   * customer would wait and retry forever. Anything else is a claim held by another call.
   */
  private void writeClaimRefusal(HttpServletResponse response, CheckoutRequest checkoutRequest)
      throws IOException {
    String status = checkoutRequestStore.deriveProvisioningStatus(checkoutRequest);
    if (CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED.equals(status)
        && !checkoutRequestStore.isProvisioningRetryAllowed(checkoutRequest)) {
      String failureDescription = ProvisioningFailureReason.safeDescription(
          ProvisioningFailureReason.codeOf(checkoutRequest.getFailureReason()));
      writeError(response, HttpServletResponse.SC_CONFLICT, "PROVISIONING_RETRY_NOT_ALLOWED",
          "Setup cannot be retried for this environment",
          failureDescription + ". Setup cannot be retried; contact support to continue.");
      return;
    }
    if (CheckoutRequestStore.DERIVED_STATUS_PROVISIONED.equals(status)) {
      writeError(response, HttpServletResponse.SC_CONFLICT, "PROVISIONING_ALREADY_COMPLETED",
          "This environment is already set up",
          "This environment is already set up. Open it from your environment list.");
      return;
    }
    writeError(response, HttpServletResponse.SC_CONFLICT, "PROVISIONING_ALREADY_IN_PROGRESS",
        "Setup is still running for this environment",
        "Setup is still running for this environment. Refresh its status before trying again.");
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
    if (onboardingRequest.skipEnvironmentReadyMail) {
      return;
    }
    String normalizedLanguage = StringUtils.trimToNull(onboardingRequest.language);
    sendAuthEmailBestEffort("environment-ready",
        () -> authEmailSender.sendEnvironmentReady(account, clientId, normalizedLanguage));
  }

  /**
   * Starts the demo-to-productive transfer for a freshly provisioned paid tenant. Does nothing
   * when the purchase has no demo source. Best-effort in its own right: a failure here is logged
   * as a transfer failure, never as an unclosed checkout, and never reaches the onboarding caller.
   *
   * <p>A failure is also rolled back (ETP-5047). The service commits each of its writes, but one
   * that fails between writing a preference and its own commit leaves that preference pending, and
   * the next commit on this thread — the draft clear, the billing period fill — would persist a
   * half-written transfer. The tenant has committed by then, and so has a selection this call
   * recorded before the start failed, so neither is lost.
   *
   * <p><b>One edge, accepted rather than handled.</b> The checkout close normally has committed by
   * then too. Only when {@code recordProvisioned}'s fenced {@code PROVISIONED} update ran and the
   * flush or commit right after it failed (it only logs, by design) is that update still pending,
   * and this rollback — like the draft clear's and the ready email's — then discards it. The
   * tenant stays committed, productive and subscribed, but its checkout request stays
   * {@code PROVISIONING} with no created client. Nothing re-closes it: a retry once the lease
   * expires is refused by {@code rejectDuplicateProductiveName}, because the account now owns a
   * productive environment of that name, and the request records the non-retryable
   * {@code CLIENT_NAME_IN_USE} failure — no second tenant is built, but the request needs a manual
   * close. It takes two failures in a row on one thread, so it is not worth a different design.
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
      EtendoGoDalHelper.rollbackDalChanges("demo data transfer start", e, log);
    }
  }

  /**
   * Annotates a paid checkout request that did not finish provisioning.
   *
   * <p>Annotation only, and deliberately never a status change: the row stays where provisioning
   * left it so {@code DERIVED_STATUS} reports {@code STALLED}, rather than being marked terminal
   * by a path that may itself be failing. {@code recordFailureReason} swallows its own errors for
   * the same reason — a failed annotation must not turn one problem into two. Called from the
   * onboarding {@code finally} only after successful completion or confirmed rollback. Unsafe
   * rollback failures skip annotation because its store commits the current DAL transaction.
   *
   * @param paidUpgrade whether this environment was bought rather than free
   * @param provisioningCompleted whether provisioning reached its final result line
   * @param onboardingRequest the parsed onboarding request
   * @param failureReason the cause the client was shown, otherwise null
   * @param failureCode the stable code of that cause, {@code null} when it has none; persisted with
   *     the reason so the status endpoint can decide retryability and answer without raw text
   */
  private void recordProvisioningFailureReason(boolean paidUpgrade, boolean provisioningCompleted,
      OnboardingRequestData onboardingRequest, String failureReason, String failureCode) {
    if (!paidUpgrade || provisioningCompleted) {
      return;
    }
    checkoutRequestStore.recordFailureReason(onboardingRequest.paymentToken,
        ProvisioningFailureReason.encode(failureCode, failureReason));
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
  private PaywallOutcome resolveOnboardingPaywall(String accountId, String accountEmail,
      OnboardingRequestData onboardingRequest, HttpServletResponse response) throws IOException {
    try {
      TenantPaywallService.Outcome paywall =
          evaluatePaywall(accountId, accountEmail, onboardingRequest);
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
    if (isPaidOnboardingNotOwned(accountEmail)) {
      writeError(response, HttpServletResponse.SC_FORBIDDEN, BILLING_OWNER_REQUIRED,
          BILLING_OWNER_MESSAGE, BILLING_OWNER_MESSAGE);
      return true;
    }
    return false;
  }

  /** An account that already owns environments must be a server-marked owner to pay for more. */
  private boolean isPaidOnboardingNotOwned(String accountEmail) {
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
    return hasEnvironments && !billingOwner;
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
  private TenantPaywallService.Outcome evaluatePaywall(String accountId, String accountEmail,
      OnboardingRequestData onboardingRequest) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      boolean ownsEnvironment = EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail(accountEmail) > 0;
      boolean resuming = isResumingOwnedTenant(
          ProvisioningClientName.of(accountId, onboardingRequest.paymentToken), accountEmail);
      return tenantPaywallService.evaluate(ownsEnvironment, resuming, false,
          onboardingRequest.paymentToken, accountEmail, onboardingRequest.clientName);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Tells a resume of an existing tenant from a request for a new one. A half-built client this
   * attempt left behind, found by its {@link ProvisioningClientName}, is the retry path
   * {@code validateExistingClient} handles downstream — provisioning it again reconciles what is
   * missing rather than creating a tenant, so it must not be charged a second time. The company
   * name plays no part (ETP-5548): matching it used to resume, and so convert, a finished demo.
   */
  private boolean isResumingOwnedTenant(String provisioningName, String accountEmail) {
    String existingClientId = EtendoGoJwtSupport.findClientIdByName(provisioningName);
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
      String entryRoleId = roleListData.getEntryRoleId();
      Role role = entryRoleId != null ? OBDal.getInstance().get(Role.class, entryRoleId) : null;
      String jwtToken = SecureWebServicesUtils.generateToken(user, role);

      JSONObject result = new JSONObject();
      result.put(FIELD_TOKEN, jwtToken);
      result.put(FIELD_ROLE_LIST, roleListData.getRoleArray());
      writeResponse(response, HttpServletResponse.SC_OK, result);
      recordLegacyEnvironmentLogin(jwtToken, startNanos);
      retryInitialPeriodStateOnLogin(user.getClient() != null ? user.getClient().getId() : null);
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
      if (ProvisioningClientName.matches(clientName)) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, RESERVED_CLIENT_NAME_MESSAGE);
        return null;
      }
      OnboardingRequestData data = new OnboardingRequestData();
      data.clientName = clientName;
      data.currencyIso = body.optString(FIELD_CURRENCY, "EUR").trim();
      data.language = body.optString(FIELD_LANGUAGE, "en_US").trim();
      // Country drives the org's tax resolution; default to Spain (ES) when the form omits it.
      data.countryCode = body.optString(FIELD_COUNTRY_CODE, "ES").trim();
      data.address = body.optString(FIELD_ADDRESS, "").trim();
      // Full name of the person onboarding. Optional in the payload; it becomes the display
      // name of the client admin user. When blank, prepareOnboarding falls back to the account's
      // name, then its email (ETP-5689).
      data.fullName = body.optString(FIELD_FULL_NAME, "").trim();
      // Tax ID (ETP-4749): optional in the wizard, so a blank value here is expected and
      // must not fail the request — wireOrgInfo() only persists it when non-blank.
      data.taxId = body.optString(FIELD_FISCAL_ID_VALUE, "").trim();
      data.paymentToken = body.optString(FIELD_PAYMENT_TOKEN, "").trim();
      data.upgradeAction = body.optString("upgradeAction", "create-productive").trim();
      // ETP-5426: opt-in, off unless the form explicitly sends true.
      data.includeSampleData = body.optBoolean(FIELD_INCLUDE_SAMPLE_DATA, false);
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
    String currencyId = findCurrencyId(currencyIso);
    if (currencyId != null) {
      return currencyId;
    }
    writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Unknown currency: " + currencyIso);
    return null;
  }

  /** @return the id of the currency with this ISO code, or null when there is none */
  private String findCurrencyId(String currencyIso) {
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    try {
      var currency = EtendoGoJwtDalHelper.findCurrencyByIsoCode(currencyIso);
      return currency == null ? null : currency.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
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
      String accountEmail, OnboardingRequestData requestData, String provisioningName,
      String currencyId, String adminPassword) throws Exception {
    sendProgress(writer, PROGRESS_CLIENT, PROGRESS_IN_PROGRESS,
        "Creating client: " + requestData.clientName + "...");
    // ETP-5548: the client is created, and found again on a retry, under the attempt's
    // provisioning name; the company name is applied by applyRequestedClientName at the end.
    String clientId = EtendoGoJwtSupport.findClientIdByName(provisioningName);
    if (clientId != null) {
      return validateExistingClient(writer, requestData.clientName, clientId, accountEmail)
          ? clientId : null;
    }

    String clientUser = EtendoGoJwtSupport.buildClientUsername(accountEmail, requestData.clientName);
    String createdClientId = createClient(vars, currencyId, provisioningName, clientUser,
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
    // ETP-4428: a half-built client is resumable ONLY when it belongs to this account. A previous
    // partial onboarding leaves the client behind (with its org/role/user) but missing downstream
    // provisioning; re-entering it lets the idempotent chain reconcile what is missing. Since
    // ETP-5548 the lookup is by provisioning name, so another account's client cannot match in
    // practice; the check stays as the tenant-isolation guarantee.
    if (!EtendoGoJwtDalHelper.clientBelongsToAccountEmail(clientId, accountEmail)) {
      sendProgress(writer, PROGRESS_CLIENT, PROGRESS_ERROR,
          "The environment being created belongs to another account.");
      sendFinalResult(writer, false,
          "The environment being created belongs to another account. Contact support.");
      return false;
    }
    sendProgress(writer, PROGRESS_CLIENT, "done", "Client already exists, resuming...");
    return true;
  }

  /**
   * Onboarding copy of the pre-payment rule in {@code isProductiveNameTakenByAccount}: an account
   * never gets two productive environments with the same company name (ETP-5548). The checkout
   * fixes its company name, so the failure carries the non-retryable
   * {@code CLIENT_NAME_IN_USE} code instead of offering a retry that would fail the same way.
   *
   * @return {@code true} when the request was refused and the result line written
   */
  private boolean rejectDuplicateProductiveName(PrintWriter writer,
      OnboardingRequestData requestData, String accountEmail) {
    OBContext.setAdminMode(true);
    try {
      if (!ownsProductiveNamed(requestData.clientName, accountEmail)) {
        return false;
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    sendProgress(writer, PROGRESS_CLIENT, PROGRESS_ERROR,
        "You already have a productive environment named '" + requestData.clientName + "'.");
    sendFinalResult(writer, false, CLIENT_NAME_IN_USE_MESSAGE,
        ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE);
    return true;
  }

  /**
   * Gives a classic-path client the requested company name, replacing its provisioning name in the
   * client and in every name the build derived from it (admin role, trees, ledger, chart of
   * accounts, calendar) — the same rewrite a pooled tenant gets at claim time. A client that does
   * not carry {@code provisioningName} is left alone.
   *
   * <p>The organization is created with the company name directly, so it only differs when a free
   * retry resumed the attempt under a different name (a paid checkout fixes its name): it then
   * takes the requested name too. Its legal name follows only while it still equals the old name —
   * a legal name copied from the demo is the customer's and stays.
   */
  void applyRequestedClientName(String clientId, String orgId, String provisioningName,
      String clientName) {
    OBContext.setAdminMode(true);
    try {
      Client client = OBDal.getInstance().get(Client.class, clientId);
      if (client == null || !StringUtils.equals(provisioningName, client.getName())) {
        return;
      }
      client.setName(clientName);
      client.setSearchKey(clientName);
      client.setDescription(clientName);
      OBDal.getInstance().save(client);
      Organization org = orgId == null ? null : OBDal.getInstance().get(Organization.class, orgId);
      if (org != null && !StringUtils.equals(clientName, org.getName())) {
        String previousName = org.getName();
        org.setName(clientName);
        org.setSearchKey(clientName);
        if (StringUtils.isBlank(org.getSocialName())
            || StringUtils.equals(previousName, org.getSocialName())) {
          org.setSocialName(clientName);
        }
        OBDal.getInstance().save(org);
      }
      OBDal.getInstance().flush();
      pooledTenantClaimService.renamePlaceholderDerivedNames(clientId, provisioningName,
          clientName);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private String createClient(VariablesSecureApp vars, String currencyId, String clientName,
      String clientUser, String adminPassword, PrintWriter writer) {
    return provisioningChain().createClient(new NdjsonOnboardingProgressSink(writer), vars,
        currencyId, clientName, clientUser, adminPassword);
  }

  private OnboardingProvisioningChain.AdminContext resolveAdminContextData(String clientId,
      PrintWriter writer) {
    return provisioningChain().resolveAdminContext(new NdjsonOnboardingProgressSink(writer),
        clientId);
  }

  private Boolean ensureOrganization(PrintWriter writer, String clientName,
      String clientId, OnboardingProvisioningChain.AdminContext adminContext, String currencyId) {
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
    return provisioningChain().createOrganization(new NdjsonOnboardingProgressSink(writer),
        clientName, clientId, starOrgId, currencyId);
  }

  /** @see OnboardingProvisioningChain#applySocialName */
  boolean applySocialName(String clientId, String clientName) {
    return provisioningChain().applySocialName(clientId, clientName);
  }

  private String resolveOrganizationId(String clientId) {
    return provisioningChain().resolveOrganizationId(clientId);
  }


  /**
   * Builds the provisioning chain over this servlet's own service instances. Built per call, not
   * cached, so a service swapped on the servlet (tests do this) is always the one that runs.
   */
  OnboardingProvisioningChain provisioningChain() {
    OnboardingProvisioningChain chain = new OnboardingProvisioningChain();
    chain.onboardingDatasetImportService = onboardingDatasetImportService;
    chain.onboardingAccountingWiringService = onboardingAccountingWiringService;
    chain.onboardingPeriodControlService = onboardingPeriodControlService;
    chain.onboardingSequenceGeneratorService = onboardingSequenceGeneratorService;
    chain.onboardingMarkOrgReadyService = onboardingMarkOrgReadyService;
    chain.onboardingFiscalDataSetupService = onboardingFiscalDataSetupService;
    chain.onboardingOrgInfoService = onboardingOrgInfoService;
    chain.onboardingWarehouseAddressService = onboardingWarehouseAddressService;
    chain.onboardingAcctdimCentrallyMaintainedService = onboardingAcctdimCentrallyMaintainedService;
    chain.onboardingAdminIdentityService = onboardingAdminIdentityService;
    chain.onboardingBaselineService = onboardingBaselineService;
    chain.onboardingForceTestModeService = onboardingForceTestModeService;
    chain.onboardingCostingScheduleService = onboardingCostingScheduleService;
    return chain;
  }

  private static OnboardingProvisioningChain.OrgInfoInput orgInfoInput(
      OnboardingRequestData requestData) {
    return requestData == null ? null
        : new OnboardingProvisioningChain.OrgInfoInput(requestData.countryCode,
            requestData.address, requestData.taxId);
  }

  /** @see OnboardingProvisioningChain#ensureOnboardingDataset */
  boolean ensureOnboardingDataset(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId,
      OnboardingRequestData requestData) {
    return provisioningChain().ensureOnboardingDataset(new NdjsonOnboardingProgressSink(writer),
        clientId, orgId, adminUserId, adminRoleId, orgInfoInput(requestData));
  }

  boolean importOnboardingDataset(PrintWriter writer, String clientId, String orgId) {
    return provisioningChain().importOnboardingDataset(new NdjsonOnboardingProgressSink(writer),
        clientId, orgId);
  }

  boolean wireOrgInfo(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId, OnboardingRequestData requestData) {
    return provisioningChain().wireOrgInfo(new NdjsonOnboardingProgressSink(writer), clientId,
        orgId, adminUserId, adminRoleId, orgInfoInput(requestData));
  }

  boolean wireWarehouseAddress(PrintWriter writer, String clientId, String orgId,
      String adminUserId, String adminRoleId) {
    return provisioningChain().wireWarehouseAddress(new NdjsonOnboardingProgressSink(writer),
        clientId, orgId, adminUserId, adminRoleId);
  }

  /**
   * Write a NDJSON progress line.
   */
  void sendProgress(PrintWriter writer, String step, String status, String message) {
    new NdjsonOnboardingProgressSink(writer).progress(step, status, message);
  }

  /**
   * Write the final NDJSON result line.
   */
  void sendFinalResult(PrintWriter writer, boolean success, String message) {
    sendFinalResult(writer, success, message, null);
  }

  /**
   * Write the final NDJSON result line, tagged with a stable error code (ETP-4665).
   */
  void sendFinalResult(PrintWriter writer, boolean success, String message, String code) {
    new NdjsonOnboardingProgressSink(writer).result(success, message, code);
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
   * ETP-4798: issues a fresh email-verification token and mails the link out. The logic lives in
   * {@link AccountRegistrationService#issueEmailVerification} (ETP-5847), shared with the public
   * signup API; this delegate keeps every call site here unchanged.
   */
  private void issueEmailVerification(Account account, String language, boolean welcome) {
    accountRegistration.issueEmailVerification(account, language, welcome);
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
    return AccountRegistrationService.generateSecureUrlToken();
  }

  /** SHA-256 of a mailed token. Only the digest is ever persisted. */
  private String hashAuthToken(String token) {
    return AccountRegistrationService.hashAuthToken(token);
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
    return BoundedJsonParser.parseObject(readRawBody(request));
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
   * POST /sws/go/session/link
   * Body: { "token": "..." }
   *
   * <p>ETP-5847 — signs in with the one-time login link the signup API mails when an account's
   * environment is ready, so a passwordless account gets in without a password. It spends the
   * link (it works once), confirms the address (the mail reached its owner) and opens a cookie
   * session exactly like {@code POST /session}. The body adds {@code environment.userId}: the
   * account's user in the environment the signup created, which the app enters next with
   * {@code POST /session/environment}, so every rule of entering an environment stays in one
   * place.
   *
   * <p>Only this POST spends the link. The page the mail links to asks for a click first, so a
   * mail scanner that opens the URL spends nothing.
   */
  private void handleSessionLink(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    JSONObject body;
    try {
      body = readJsonBody(request);
    } catch (JSONException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_JSON_BODY);
      return;
    }
    String token = body.optString(FIELD_TOKEN, "").trim();
    if (token.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
          MISSING_TOKEN_MESSAGE, MISSING_TOKEN_MESSAGE);
      return;
    }
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      Account account = SignupAccountDalHelper.findAccountByLoginLinkHash(hashAuthToken(token),
          new Date());
      if (account == null) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, CODE_LOGIN_LINK_INVALID,
            LOGIN_LINK_INVALID_MESSAGE, LOGIN_LINK_INVALID_MESSAGE);
        return;
      }
      String accountId = account.getId();
      SignupAccountDalHelper.consumeLoginLink(account, new Date());
      Account consumed = EtendoGoJwtDalHelper.findActiveAccountById(accountId);
      String environmentUserId = SignupAccountDalHelper.environmentUserId(consumed);
      IssuedGoSession issued = goSessionService.create(accountId, AUTH_METHOD_LINK,
          request.getHeader(HEADER_USER_AGENT), null);
      setSessionCookies(response, issued);
      response.setHeader(HEADER_CACHE_CONTROL, VALUE_NO_STORE);
      response.setHeader(HEADER_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);
      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_ACCOUNT, buildAccountJson(consumed));
      result.put(FIELD_CSRF_TOKEN, issued.getCsrfToken());
      result.put(FIELD_ENVIRONMENT, new JSONObject().put(FIELD_USER_ID,
          environmentUserId == null ? JSONObject.NULL : environmentUserId));
      writeResponse(response, HttpServletResponse.SC_OK, result);
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("login link", e, log);
      log.error("Login link sign-in failed", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SERVER_ERROR);
    } catch (JSONException e) {
      log.error("JSON error building the login link response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
    } finally {
      OBContext.restorePreviousMode();
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
        writeError(response, HttpServletResponse.SC_FORBIDDEN, auth.getRefusalMessage());
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
   * { status, environment, roleList, csrfToken } plus a rotated cookie. Re-entering the environment
   * the session already holds keeps the session, its cookies and its CSRF token (ETP-5550). Unsafe
   * method → CSRF required.
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
        writeError(response, HttpServletResponse.SC_FORBIDDEN, auth.getRefusalMessage());
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

      List<String> previousContext = environmentContextOf(sessionRecord);
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

      IssuedGoSession entered = rotateUnlessReentry(sessionRecord,
          previousContext.equals(environmentContextOf(sessionRecord)), response);
      if (entered == null) {
        writeError(response, HttpServletResponse.SC_CONFLICT,
            "Session changed concurrently; restore and retry");
        return;
      }

      response.setHeader(HEADER_CACHE_CONTROL, VALUE_NO_STORE);
      response.setHeader(HEADER_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, STATUS_SUCCESS);
      result.put(FIELD_ENVIRONMENT, buildSessionEnvironment(entered.getRecord()));
      result.put(FIELD_ROLE_LIST, roleListData.getRoleArray());
      result.put(FIELD_CSRF_TOKEN, entered.getCsrfToken());
      // ETP-5047 — entering a blocked tenant is NOT refused here, deliberately. The blocked-access
      // screen and the pages it sends the customer to (/account, /upgrade) render inside the
      // entered environment, so refusing entry would lock a blocked customer out of the one place
      // that lets them pay. The tenant's data paths refuse on their own instead — NEO, the NEO_DATA
      // surfaces and MCP answer 402 on every request. accessDecision is informational and
      // backend-only: nothing in the SPA reads it; its blocked screen is driven by the NEO 402.
      // It reads the enforced decision instead of asking the guard to check: nothing is refused
      // here, so the guard's refusal log line must not be written for this call.
      EnvironmentAccessPolicy.Decision accessDecision = enforcedAccessDecision(userId);
      if (accessDecision != null && accessDecision != EnvironmentAccessPolicy.Decision.ALLOWED) {
        result.put(FIELD_ACCESS_DECISION, accessDecision.name());
      }
      writeResponse(response, HttpServletResponse.SC_OK, result);
      recordCookieEnvironmentLogin(entered.getRecord(), startNanos);
      retryInitialPeriodStateOnLogin(entered.getRecord().getCtxClientId());
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
   * switch: defaults to the user's entry role when none is requested (see
   * {@link #defaultEntryRoleId}), checks the role is one of the user's own, and that the requested
   * organization (if any) belongs to that role. Writes the matching error response and returns
   * {@code null} when the request is invalid.
   */
  private Role resolveRequestedRole(EtendoGoJwtSupport.RoleListData roleListData,
      String requestedRoleId, String requestedOrgId, HttpServletResponse response)
      throws IOException, JSONException {
    String roleId = requestedRoleId.isEmpty()
        ? defaultEntryRoleId(roleListData, requestedOrgId)
        : requestedRoleId;
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
   * ETP-5096 — the role an entry with no explicit role uses: the user's default role
   * ({@link EtendoGoJwtSupport.RoleListData#getEntryRoleId()}), not the oldest one. The client
   * always sends its first organization, so when the default role cannot open that organization
   * the oldest role (the one entry used before) is kept: a user who could enter must not start
   * getting a 403 because their default role is narrower.
   */
  private static String defaultEntryRoleId(EtendoGoJwtSupport.RoleListData roleListData,
      String requestedOrgId) throws JSONException {
    String entryRoleId = roleListData.getEntryRoleId();
    if (requestedOrgId.isEmpty() || entryRoleId == null
        || entryRoleId.equals(roleListData.getFirstRoleId())) {
      return entryRoleId;
    }
    JSONObject entryRole = findRole(roleListData.getRoleArray(), entryRoleId);
    return entryRole != null && roleContainsOrganization(entryRole, requestedOrgId)
        ? entryRoleId
        : roleListData.getFirstRoleId();
  }

  /**
   * ETP-5550 — rotating on an environment entry guards a privilege change; re-entering the
   * environment the session already holds is none, and rotating anyway revokes the CSRF token every
   * other open tab still holds (the onboarding re-enters on its own whenever it mounts with a live
   * session). So a re-entry keeps the session as it is.
   *
   * @param sessionRecord the authenticated session, already carrying the entered context
   * @param reentry       whether that context is the one the session held before
   * @param response      where a rotation sets its new cookies
   * @return on a re-entry, the current session with no plaintext tokens (its cookies are left
   *     untouched) and its current CSRF token; otherwise the rotated session, its cookies set; or
   *     {@code null} when a concurrent rotation won
   */
  private IssuedGoSession rotateUnlessReentry(GoSessionRecord sessionRecord, boolean reentry,
      HttpServletResponse response) {
    if (reentry) {
      return new IssuedGoSession(null, null, sessionRecord.getCsrfToken(), sessionRecord);
    }
    IssuedGoSession rotated = goSessionService.rotate(sessionRecord);
    if (rotated != null) {
      setSessionCookies(response, rotated);
    }
    return rotated;
  }

  /** The environment a session is in; any difference in it is a privilege change. */
  private static List<String> environmentContextOf(GoSessionRecord sessionRecord) {
    return Arrays.asList(sessionRecord.getUserId(), sessionRecord.getRoleId(),
        sessionRecord.getCtxClientId(), sessionRecord.getCtxOrgId(),
        sessionRecord.getWarehouseId());
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
      result.put(FIELD_ENVIRONMENT, buildSessionEnvironment(sessionRecord));
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
    env.put(FIELD_CLIENT_ID, sessionRecord.getCtxClientId());
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
        writeError(response, HttpServletResponse.SC_FORBIDDEN,
            GoSessionSecurity.MSG_ORIGIN_NOT_ALLOWED);
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

  private void notifyProvisioningAlert(ProvisioningAlertAttempt attempt,
      com.etendoerp.go.schemaforge.email.InternalAlertEvent.Status status, String failureCategory) {
    try {
      internalAlertService.sendAfterTransaction(new com.etendoerp.go.schemaforge.email.InternalAlertEvent(
          "environment-provisioning", status, attempt.attemptId,
          new com.etendoerp.go.schemaforge.email.InternalAlertEvent.Target(
              attempt.paid ? "PRODUCTIVE" : "DEMO", attempt.clientId, attempt.path),
          attempt.stage, StringUtils.defaultIfBlank(failureCategory, null)));
    } catch (RuntimeException e) {
      // Even a substituted alert sender must never change a committed provisioning outcome.
      log.warn("Internal provisioning alert failed attempt={} category={}", attempt.attemptId,
          e.getClass().getSimpleName());
    }
  }

  private static final class ProvisioningAlertAttempt {
    private final String attemptId = UUID.randomUUID().toString();
    private final boolean paid;
    private String clientId;
    private String path = "UNKNOWN";
    private String stage = "started";
    private boolean committed;
    private ProvisioningAlertAttempt(boolean paid) { this.paid = paid; }
  }

  static final class OnboardingPreparation {
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
    // ETP-5426: the signup form's "include sample data" opt-in. Honoured only when
    // OnboardingSampleDataService.isEligible accepts the request.
    private boolean includeSampleData;
    // ETP-5847: set for an environment the signup API creates, which mails its own ready message
    // (with a login link, or none at all when the caller turned mails off).
    private boolean skipEnvironmentReadyMail;
  }

}
