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

package com.etendoerp.go.featureflags;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.configcat.PollingModes;
import com.etendoerp.go.common.GoRuntimeProperties;

import datadog.trace.api.openfeature.Provider;
import dev.openfeature.contrib.providers.configcat.ConfigCatProvider;
import dev.openfeature.contrib.providers.configcat.ConfigCatProviderConfig;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;

/**
 * Backend feature-flag entry point for Etendo Go.
 *
 * <p><strong>Application API:</strong> OpenFeature (dev.openfeature:sdk). Application code only ever
 * calls {@link #isEnabled(String, FeatureFlagContext)} and never sees an OpenFeature type, so the
 * control plane behind it can change without touching a single caller.
 *
 * <p><strong>Control plane: Datadog, then ConfigCat, then local properties (ETP-5751).</strong>
 * {@link #createProvider()} picks the first arm that is both configured and usable:
 * <ol>
 *   <li><b>Datadog Feature Flags</b> ({@link Provider}, {@code com.datadoghq:dd-openfeature}) when
 *       {@value #DATADOG_FLAGS_ENABLED_ENV} / {@value #DATADOG_FLAGS_ENABLED_PROPERTY} is true
 *       <em>and</em> {@code dd-java-agent} is loaded in this JVM. The provider is only a thin
 *       bridge; the agent fetches the configuration, so without the agent it can never become
 *       ready. The Datadog environment a flag answers from is chosen by {@code DD_ENV}.</li>
 *   <li><b>ConfigCat</b> ({@link ConfigCatProvider}) when {@value #CONFIGCAT_SDK_KEY_PROPERTY} /
 *       {@value #CONFIGCAT_SDK_KEY_ENV} is set.</li>
 *   <li><b>Local properties</b> ({@link PropertiesFeatureProvider}, {@code etendo.go.flags.<flag-key>})
 *       otherwise — in-process, no network call, a plain boolean per environment with no
 *       per-account targeting.</li>
 * </ol>
 * Both hosted arms let a flag be flipped and per-account targeting rules changed <em>without a
 * restart</em>. Keeping the local arm is deliberate: dev, CI and e2e have no agent and no SDK key,
 * so they stay deterministic and independent of any shared remote project.
 *
 * <p><b>Per-account targeting therefore requires a hosted arm.</b> There is deliberately no
 * local mechanism for it: a second way to express the same decision is a second thing to keep in
 * sync, and — worse — it would silently do nothing wherever a hosted arm is selected, since only
 * one provider is ever installed. A configuration knob that silently does nothing is the same family of
 * trap as ETP-4966. Locally the plain boolean is enough, because a dev box has one user.
 *
 * <p><strong>Swap point.</strong> {@link #createProvider()} is the <em>only</em> place that decides
 * which provider backs the API. Nothing else in this class, this package, or any caller changes.
 * Keep it that way: provider-specific configuration, scheduling and failure handling belong inside
 * the provider, not here.
 *
 * <p><strong>⚠ A flag read by BOTH ends now needs {@code targeting-key-divergence} closed first.</strong>
 * Before ConfigCat was wired here, the backend could not read the same control plane as the browser,
 * so the two ends <em>could not</em> share a flag. Now they can — and they still send <b>different
 * targeting keys</b>: the web client sends the ERP username ({@code sf_auth_user}), this module
 * sends the {@code ETGO_ACCOUNT} email. A flag evaluated on both ends would therefore bucket the
 * same human two ways, which is exactly the ETP-4966 failure: an unset or differently-resolved key
 * on one end is indistinguishable from a disabled feature, and it shipped a charged account a free
 * environment. Until the {@code targeting-key-divergence} item on {@code paid-second-tenant} is
 * closed, a new flag must be read on <em>one</em> end only. {@link #FLAG_BP_PORTAL_LINK} is
 * backend-only for this reason and must never be added to the web client's {@code flag-keys.js}.
 *
 * <p><strong>Failure behaviour — never block, never fail, default false.</strong> A flag check runs
 * inside request handling, so it must not be able to slow down or break a request:
 * <ul>
 *   <li>Flag not configured → the code default ({@code false}).</li>
 *   <li>Flag configured with a non-boolean value → the code default, with the provider reporting a
 *       parse error rather than silently reading the typo as false.</li>
 *   <li>Provider registration failed → no provider is bound and every flag resolves to its
 *       default.</li>
 *   <li>Any unexpected error during evaluation → the default.</li>
 * </ul>
 *
 * <p>Backend evaluation is authoritative for permissions, data and processes. The web client
 * evaluates the same flags for presentation only; the backend must never trust the client's verdict.
 */
public final class GoFeatureFlags {

  private static final Logger log = LogManager.getLogger(GoFeatureFlags.class);

  // `tenant-upgrade` was retired in ETP-4966: the paid productive-environment capability is
  // permanent, and gating it behind a key the browser and the backend resolved through different
  // control planes is what let a charged account receive a demo environment.

  /**
   * ETP-5267 — whether a {@code sales-invoice-send} email carries a link to the Business Partner
   * self-service portal.
   *
   * <p><b>Targeted per sending account, and false for everyone until one is named.</b> The
   * decision is "does the account sending this invoice have the portal link switched on", answered
   * against the {@code ETGO_ACCOUNT} email this module publishes in the evaluation context (both as
   * the targeting key and as {@link FeatureFlagContext#ATTRIBUTE_EMAIL}). Per-account enablement is
   * a targeting rule on a hosted arm (Datadog or ConfigCat); with neither configured the flag is a
   * plain per-environment boolean, so a shared environment must have a hosted arm before this is
   * switched on for
   * anyone. See {@code PortalLinkPolicy}, which resolves the sending account and owns this flag's
   * only call site.
   *
   * <p><b>Backend-only, and it must stay that way.</b> No key is declared in the web client's
   * {@code flag-keys.js} and nothing in the browser reads this — <b>never add one.</b> The decision
   * point is entirely server-side, since the link is injected while building the email in Java, so
   * a browser key would create a second evaluator with nothing to evaluate. That is not a style
   * preference: per the ETP-4966 lesson above, a flag whose two ends resolve from different control
   * planes has no single truth, and an unset key on one end is indistinguishable from a disabled
   * feature. With a single evaluator there is no second end to disagree.
   *
   * <p>This also does <em>not</em> reopen the {@code targeting-key-divergence} item on
   * {@code paid-second-tenant}: that divergence is the frontend sending the ERP username while the
   * backend targets the account email. This flag targets the account email — the key the backend
   * targets on — and has no frontend end at all.
   *
   * <p><b>It gates the link, not the portal.</b> The {@code /portal/:token} route, the three
   * {@code /sws/portal/*} endpoints, the {@code etgo_portal_access} table and the revoke action all
   * ship unconditionally — the same pattern {@code docs/feature-flags.md} already documents as
   * correct for {@code /upgrade}, where the route is registered unconditionally and only the menu
   * entry is gated, "because hiding the route would imply the flag was protecting something, which
   * it is not". What protects the endpoints is the token. Revocation in particular must keep working
   * whatever this flag says: it is the only kill switch for a link already out.
   */
  public static final String FLAG_BP_PORTAL_LINK = "bp-portal-link";

  /**
   * ETP-5389 — pre-provisioned tenant pool for onboarding. On, {@code POST /sws/go/onboarding}
   * claims a READY tenant from {@code ETGO_TENANT_POOL} (when the request is the supported
   * EUR/ES/es_ES combination) and the pool filler process keeps the pool topped up; off, both the
   * claim and the filler are inert and onboarding is the classic from-scratch path. Environment
   * level and backend-only: the browser never reads it. Its only caller is
   * {@code TenantPoolConfig}.
   */
  public static final String FLAG_ONBOARDING_TENANT_POOL = "onboarding-tenant-pool";

  /**
   * ETP-5047 — the kill switch of commercial environment-access enforcement: the HTTP 402 that
   * NEO (every scheme), the {@code NEO_DATA} surfaces (favorites, fiscal test mode, report
   * selectors, OAuth2 API keys), MCP, the {@code /sws/go} tenant-session endpoints and the legacy
   * {@code GET /sws/go/login?userId=} answer once a demo trial has expired or a subscription's
   * payment grace has elapsed. The survey configuration ({@code NEO_AUXILIARY}) is not gated, and
   * {@code POST /sws/go/session/environment} reports the decision without refusing; the complete
   * list lives on {@code EnvironmentAccessGuard}.
   *
   * <p><b>Phrased as an OFF switch on purpose, because every failure of this class resolves to
   * {@code false}.</b> Enforcement is live from deploy and stays live when the flag is not
   * configured, when ConfigCat is unreachable, when the SDK key is wrong or when evaluation
   * throws: all of those read "not switched off". Only a value that resolves to {@code true}
   * disables the refusal: on ConfigCat the boolean setting served as {@code true}; locally
   * ({@link PropertiesFeatureProvider}) {@code true}, {@code Y}, {@code yes} or {@code 1}, case
   * insensitive and trimmed. {@code false}/{@code N}/{@code no}/{@code 0} keep enforcing, and any
   * other value is a {@code PARSE_ERROR} that keeps enforcing too. A positively phrased
   * "enforcement enabled" flag would do the opposite — a missing key would silently open every
   * blocked tenant — so do not "fix" the polarity.
   *
   * <p><b>What {@code true} does.</b> The access decision is still computed and logged at INFO
   * (entry point, tenant, decision) instead of being refused, so an operator can see who would
   * have been blocked. It is meant for an incident — a wrong status after a Stripe outage, a bad
   * deploy — not as a way to grant a tenant free access.
   *
   * <p><b>Targeting.</b> Evaluated with an account-less context carrying the tenant as
   * {@link FeatureFlagContext#ATTRIBUTE_CLIENT_ID}, so a ConfigCat rule can switch enforcement off
   * for one tenant ({@code clientId}) or for everyone. Locally it is a plain boolean,
   * {@code etendo.go.flags.environment-access-enforcement-off} /
   * {@code ETGO_FLAG_ENVIRONMENT_ACCESS_ENFORCEMENT_OFF}.
   *
   * <p><b>Backend-only.</b> The browser follows the 402 it is given and needs no key of its own —
   * same reasoning as {@link #FLAG_BP_PORTAL_LINK}; never add it to {@code flag-keys.js}. Its only
   * caller is {@code EnvironmentAccessEnforcementFlag}.
   */
  public static final String FLAG_ENVIRONMENT_ACCESS_ENFORCEMENT_OFF =
      "environment-access-enforcement-off";

  /**
   * ETP-5640 — kill switch for the stateless MCP era (spec 2026-07-28). <b>Inverted on
   * purpose:</b> {@code true} turns the modern era <em>off</em> and {@code /sws/mcp} answers
   * exactly as before it existed — {@code server/discover} gets {@code -32601}, per-request
   * {@code _meta} is ignored and every request is served under the {@code initialize}-based era.
   * Unset or {@code false} is the normal state: the server is dual-era.
   *
   * <p>The inversion follows from this class's failure behaviour: every flag resolves to
   * {@code false} when it is missing, when the hosted control plane is unreachable or when
   * evaluation fails. A flag that <em>enabled</em> the modern era would switch it off on any
   * control-plane hiccup; this one leaves the default in place and only a deliberate {@code true}
   * rolls back — live without a restart on a hosted arm (Datadog or ConfigCat); on the local arm
   * the value comes from {@code etendo.go.flags.mcp-modern-era-disabled} and needs a restart.
   *
   * <p><b>Environment level only — never target it per account.</b> MCP clients cache the era per
   * origin, and one origin serves every tenant, so a per-account answer would hand one cached
   * decision to callers the flag treats differently. It is evaluated with an empty context.
   *
   * <p><b>Backend-only.</b> Nothing in the browser reads it; never add it to the web client's
   * {@code flag-keys.js}. Its only caller is {@code McpRequestEra}. Retire it (keeping the modern
   * path) once the modern era is stable in production.
   */
  public static final String FLAG_MCP_MODERN_ERA_DISABLED = "mcp-modern-era-disabled";

  /**
   * ETP-5676 — kill switch for the per-batch redundant-callout skip. <b>Inverted on purpose</b>,
   * for the same reason as {@link #FLAG_MCP_MODERN_ERA_DISABLED}: unset or {@code false} is the
   * normal state, in which a batch sent with {@code _skipRedundantCallouts: true} may skip a
   * create-cascade callout whose outputs an earlier operation of the same batch proved discarded.
   * {@code true} turns the skip off for every batch, whatever the request asks, so each callout
   * runs as on a batch without the option. Live within one ConfigCat poll where
   * {@value #CONFIGCAT_SDK_KEY_PROPERTY} is set; otherwise read from
   * {@code etendo.go.flags.batch-callout-skip-disabled} and needs a restart.
   *
   * <p><b>Environment level and backend-only.</b> Evaluated with an empty context, once per batch,
   * by {@code NeoBatchScope}, its only caller; nothing in the browser reads it. Retire it (keeping
   * the skip) once the skip has run in production without a wrong default.
   */
  public static final String FLAG_BATCH_CALLOUT_SKIP_DISABLED = "batch-callout-skip-disabled";

  /**
   * ETP-5791 — contact types driven by the contact category. On, a contact category
   * ({@code C_BP_Group}) must declare Customer and/or Vendor/Creditor, a new contact inherits
   * those types from its category unless the request sends them explicitly, a contact cannot be
   * saved with no type, moving a contact to another category re-inherits both types, and a new
   * tenant gets "Cliente/Proveedor" as its default contact category (decided when the tenant is
   * handed to the user, pool tenants included). Off (the default), the customizations skip those
   * rules, every write behaves as before the ticket and new tenants keep "Cliente" as default.
   *
   * <p><b>It gates behaviour, not schema.</b> The {@code EM_Etgo_Iscustomer} /
   * {@code EM_Etgo_Isvendor} columns, their AD metadata, the {@code ETGO_SF_FIELD} rows and the
   * reference/sample data ship unconditionally; with the flag off nothing enforces or reads them.
   *
   * <p><b>Environment level and backend-only.</b> Evaluated with an account-less context by
   * {@code ContactTypeRules#isEnabled()}, the only place it is read (the onboarding's
   * {@code OnboardingDefaultContactCategoryService} goes through it too); nothing in the browser reads it, so
   * never add it to the web client's {@code flag-keys.js}. Locally
   * {@code etendo.go.flags.contact-category-type} / {@code ETGO_FLAG_CONTACT_CATEGORY_TYPE}.
   */
  public static final String FLAG_CONTACT_CATEGORY_TYPE = "contact-category-type";

  /**
   * ETP-5048 — whether the billing reconciliation is scheduled. Strictly off by default (and off
   * on every failure of this class): while it is off {@code BillingReconciliationScheduleStartup}
   * plants no process request, so turning it on takes effect at the next restart. The process also
   * checks it on every scheduled run (a process request with timing {@code S}), whoever created
   * the request, so turning it off stops all scheduled work without a restart: a scheduled run
   * with the flag off does nothing, ends as ERROR in Process Monitor and raises one internal alert
   * per UTC day. A run an operator starts from the Classic menu, and a run fired as part of a
   * Process Group (it has no process-request id of its own), is not gated. Environment level,
   * evaluated with an empty context;
   * locally {@code etendo.go.flags.billing-reconciliation-schedule} /
   * {@code ETGO_FLAG_BILLING_RECONCILIATION_SCHEDULE}. Backend-only. Its callers are
   * {@code BillingReconciliationScheduleStartup} and
   * {@code BillingReconciliationProcess}.
   */
  public static final String FLAG_BILLING_RECONCILIATION_SCHEDULE =
      "billing-reconciliation-schedule";

  /**
   * ConfigCat SDK key. Set ⇒ flags come from ConfigCat and can be flipped without a restart; unset
   * ⇒ {@link PropertiesFeatureProvider} resolves them from local configuration.
   */
  static final String CONFIGCAT_SDK_KEY_PROPERTY = "etendo.go.configcat.sdkKey";

  /** Environment-variable spelling of {@value #CONFIGCAT_SDK_KEY_PROPERTY}. */
  static final String CONFIGCAT_SDK_KEY_ENV = "ETGO_CONFIGCAT_SDK_KEY";

  /**
   * Turns the Datadog arm on (ETP-5751). Deliberately the <em>same</em> switch {@code dd-java-agent}
   * reads to start its feature-flag subsystem, so there is one knob to set, not two that can
   * disagree: with it on and no agent loaded, the arm is skipped and the next one is tried.
   */
  static final String DATADOG_FLAGS_ENABLED_PROPERTY = "dd.feature.flags.enabled";

  /** Environment-variable spelling of {@value #DATADOG_FLAGS_ENABLED_PROPERTY}. */
  static final String DATADOG_FLAGS_ENABLED_ENV = "DD_FEATURE_FLAGS_ENABLED";

  /**
   * A class that only {@code dd-java-agent} puts on the bootstrap class path. {@code dd-openfeature}
   * resolves it lazily inside {@link Provider#initialize}, so it is the exact dependency whose
   * absence would make the Datadog arm fail — probing it first lets an agent-less JVM (dev, CI, e2e)
   * fall through to the next arm instead of resolving every flag false. It is not shipped by any
   * public jar ({@code dd-trace-api} included), so its presence means the agent is loaded.
   */
  static final String DATADOG_AGENT_PROBE_CLASS =
      "datadog.trace.api.featureflag.FeatureFlaggingGateway";

  /**
   * Upper bound on how long installing the Datadog provider waits for its first configuration (its
   * own default is 30 s). Same reasoning as {@link #CONFIGCAT_MAX_INIT_WAIT_SECONDS}; past it the
   * provider stays installed and turns ready by itself when the configuration arrives — see
   * {@link #install()}.
   */
  private static final int DATADOG_MAX_INIT_WAIT_SECONDS = 5;

  /** The control planes {@link #createProvider()} can select, in priority order. */
  enum ProviderKind {
    DATADOG, CONFIGCAT, PROPERTIES
  }

  /** How often ConfigCat re-polls its configuration in the background. */
  private static final int CONFIGCAT_POLL_SECONDS = 60;

  /**
   * Upper bound on how long installing the provider may wait for ConfigCat's first fetch. Past it
   * the client serves its own snapshot rather than blocking a request thread — a flag check runs
   * inside request handling, so an unreachable control plane must cost a bounded wait once, never
   * an unbounded one per call.
   */
  private static final int CONFIGCAT_MAX_INIT_WAIT_SECONDS = 5;

  /**
   * OpenFeature domain the provider is bound to. Using a domain instead of the global default
   * provider keeps this module from clobbering a provider installed by another module.
   */
  static final String OPENFEATURE_DOMAIN = "etendo-go";

  private static final Object INIT_LOCK = new Object();
  private static final Set<String> loggedFallbacks = ConcurrentHashMap.newKeySet();

  private static volatile Client openFeatureClient;
  private static volatile boolean initializationAttempted;

  /**
   * Builds the provider {@link #install()} binds. Always {@link #createProvider()} in production;
   * a test seam only, so the install failure paths can be driven with a provider that fails on
   * purpose.
   */
  static Supplier<FeatureProvider> providerFactory = GoFeatureFlags::createProvider;

  /**
   * Answers whether {@code dd-java-agent} is loaded. Always {@link #isDatadogAgentLoaded()} in
   * production; a test seam only, since no test JVM runs with the agent.
   */
  static BooleanSupplier datadogAgentProbe = GoFeatureFlags::isDatadogAgentLoaded;

  private GoFeatureFlags() {
  }

  /**
   * Evaluates a boolean flag, defaulting to {@code false}.
   *
   * @param flagKey the flag key, as declared by the constant that owns it in this class
   * @param context targeting information for this evaluation
   * @return {@code true} only when the control plane positively resolves the flag to true
   */
  public static boolean isEnabled(String flagKey, FeatureFlagContext context) {
    Client client = resolveClient();
    if (client == null) {
      return false;
    }
    try {
      FlagEvaluationDetails<Boolean> details =
          client.getBooleanDetails(flagKey, false, toEvaluationContext(context));
      logFallbackIfNeeded(flagKey, details);
      return Boolean.TRUE.equals(details.getValue());
    } catch (Exception e) {
      // The OpenFeature client already absorbs provider errors into the default value; this guard
      // exists so an unexpected failure can never propagate into the caller's request handling.
      log.warn("Feature flag '{}' evaluation threw {}; treating as disabled", flagKey,
          e.getClass().getSimpleName());
      return false;
    }
  }

  private static void logFallbackIfNeeded(String flagKey, FlagEvaluationDetails<Boolean> details) {
    if (details == null) {
      return;
    }
    String reason = details.getReason();
    Object errorCode = details.getErrorCode();
    if (errorCode == null && !"DEFAULT".equals(reason) && !"ERROR".equals(reason)) {
      return;
    }
    String fallbackReason = reason == null ? "unknown" : reason;
    String fallbackError = errorCode == null ? "none" : errorCode.toString();
    String deduplicationKey = flagKey + '|' + fallbackReason + '|' + fallbackError;
    if (loggedFallbacks.add(deduplicationKey)) {
      log.info("Feature flag '{}' resolved to its code default false (reason={}, errorCode={})",
          flagKey, fallbackReason, fallbackError);
    }
  }

  /**
   * Builds the provider backing flag evaluation.
   *
   * <p><strong>This is the swap point for the control plane, and it has three arms</strong>, chosen
   * by {@link #selectProvider(boolean, boolean, String)}: Datadog when it is switched on and the
   * agent is loaded, else ConfigCat when an SDK key is set, else {@link PropertiesFeatureProvider}.
   * The local fallback is deliberate, not a leftover: dev boxes, CI and e2e stay deterministic and
   * independent of any shared remote project. It is a plain per-environment boolean — per-account
   * targeting exists only on the hosted arms, on purpose (see this class's javadoc).
   *
   * <p><strong>A missing prerequisite skips an arm; a broken arm turns every flag off.</strong>
   * Datadog switched on without the agent, or a blank SDK key, simply moves on to the next arm —
   * that is configuration absent, not configuration wrong. But once a hosted arm is selected, a
   * failure to initialize it (a wrong ConfigCat key, a Datadog provider that cannot start) makes
   * {@link #install()} catch it and every flag resolve to its {@code false} code default; it never
   * falls through to a lower arm whose local value might say {@code true}. This is the ETP-4966
   * shape and it is the one behaviour here that must never be "improved" into a fallback that
   * allows.
   *
   * <p>Initialization is bounded on both hosted arms: {@link PollingModes#autoPoll(int, int)} caps
   * ConfigCat's first fetch at {@value #CONFIGCAT_MAX_INIT_WAIT_SECONDS} seconds and
   * {@link Provider.Options#initTimeout(long, TimeUnit)} caps Datadog's at
   * {@value #DATADOG_MAX_INIT_WAIT_SECONDS}. Nothing here caches by hand — that is the SDK's job,
   * and duplicating it would add a second staleness window nobody is watching.
   *
   * @return the provider to bind to the {@value #OPENFEATURE_DOMAIN} domain
   */
  static FeatureProvider createProvider() {
    boolean datadogRequested = GoRuntimeProperties.readBoolean(
        DATADOG_FLAGS_ENABLED_PROPERTY, DATADOG_FLAGS_ENABLED_ENV, false);
    boolean datadogAgentLoaded = datadogRequested && datadogAgentProbe.getAsBoolean();
    String sdkKey = StringUtils.trimToNull(GoRuntimeProperties.readValue(
        CONFIGCAT_SDK_KEY_PROPERTY, CONFIGCAT_SDK_KEY_ENV, null));
    if (datadogRequested && !datadogAgentLoaded) {
      // Visible on purpose: in a deployed environment this means the image lost its -javaagent and
      // flags are being served from a different control plane than the one the team is editing.
      log.warn("{} is on but dd-java-agent is not loaded; skipping the Datadog feature-flag "
          + "provider", DATADOG_FLAGS_ENABLED_ENV);
    }
    switch (selectProvider(datadogRequested, datadogAgentLoaded, sdkKey)) {
      case DATADOG:
        log.info("Selecting the Datadog feature-flag provider (environment from DD_ENV)");
        return new Provider(new Provider.Options()
            .initTimeout(DATADOG_MAX_INIT_WAIT_SECONDS, TimeUnit.SECONDS));
      case CONFIGCAT:
        log.info("{} is configured; selecting the ConfigCat feature-flag provider (SDK key "
            + "omitted)", CONFIGCAT_SDK_KEY_PROPERTY);
        ConfigCatProviderConfig config = ConfigCatProviderConfig.builder()
            .sdkKey(sdkKey)
            .options(options -> options.pollingMode(
                PollingModes.autoPoll(CONFIGCAT_POLL_SECONDS, CONFIGCAT_MAX_INIT_WAIT_SECONDS)))
            .build();
        return new ConfigCatProvider(config);
      default:
        log.info("No hosted feature-flag control plane is configured; selecting the local "
            + "properties feature-flag provider");
        return new PropertiesFeatureProvider();
    }
  }

  /**
   * Pure selection rule behind {@link #createProvider()}: the first arm whose prerequisites are all
   * met wins, in the order Datadog → ConfigCat → local properties.
   *
   * @param datadogRequested whether {@value #DATADOG_FLAGS_ENABLED_ENV} is on
   * @param datadogAgentLoaded whether {@code dd-java-agent} is loaded in this JVM
   * @param configCatSdkKey the trimmed ConfigCat SDK key, or {@code null} when unset or blank
   * @return the arm to install
   */
  static ProviderKind selectProvider(boolean datadogRequested, boolean datadogAgentLoaded,
      String configCatSdkKey) {
    if (datadogRequested && datadogAgentLoaded) {
      return ProviderKind.DATADOG;
    }
    if (configCatSdkKey != null) {
      return ProviderKind.CONFIGCAT;
    }
    return ProviderKind.PROPERTIES;
  }

  /**
   * Whether {@code dd-java-agent} is loaded in this JVM, probed through
   * {@value #DATADOG_AGENT_PROBE_CLASS} without initializing it.
   *
   * @return {@code true} when the agent's feature-flag bootstrap classes are visible
   */
  static boolean isDatadogAgentLoaded() {
    try {
      Class.forName(DATADOG_AGENT_PROBE_CLASS, false, GoFeatureFlags.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /**
   * Resets the memoized provider so the next evaluation re-reads configuration. Intended for tests
   * and for a configuration reload; production code evaluates flags directly.
   */
  static void reset() {
    synchronized (INIT_LOCK) {
      openFeatureClient = null;
      initializationAttempted = false;
      loggedFallbacks.clear();
    }
  }

  private static Client resolveClient() {
    Client cached = openFeatureClient;
    if (cached != null) {
      return cached;
    }
    synchronized (INIT_LOCK) {
      if (!initializationAttempted) {
        initializationAttempted = true;
        openFeatureClient = install();
      }
      return openFeatureClient;
    }
  }

  static Client install() {
    try {
      OpenFeatureAPI api = OpenFeatureAPI.getInstance();
      FeatureProvider provider = providerFactory.get();
      if (bindProvider(api, provider)) {
        log.info("Etendo Go feature-flag provider '{}' initialized for domain '{}'",
            provider.getMetadata().getName(), OPENFEATURE_DOMAIN);
      }
      return api.getClient(OPENFEATURE_DOMAIN);
    } catch (Exception e) {
      // Provider exceptions can contain request URLs or configuration material; keep the
      // diagnostic useful without logging exception messages, SDK keys or evaluation context.
      log.error("Could not initialize the feature-flag provider ({}); all flags resolve to their "
          + "code defaults", e.getClass().getSimpleName());
      return null;
    }
  }

  /**
   * Binds the provider to the {@value #OPENFEATURE_DOMAIN} domain, waiting (bounded) until it is
   * ready.
   *
   * <p>Blocking install: setProvider() alone returns before the provider is ready to evaluate, so
   * the first read after installing can still hit the previous provider and silently answer with
   * the code default. For a flag that gates authorisation, "not ready yet" and "disabled" must
   * never be the same observable outcome.
   *
   * @return {@code true} when the provider is ready; {@code false} when it is bound but still
   *     waiting for its first configuration
   * @throws dev.openfeature.sdk.exceptions.OpenFeatureError any other initialization failure (e.g. {@code FatalError}), which
   *     {@link #install()} turns into "every flag off"
   */
  private static boolean bindProvider(OpenFeatureAPI api, FeatureProvider provider) {
    try {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, provider);
      return true;
    } catch (ProviderNotReadyError e) {
      // The bounded wait ran out before the first configuration arrived (Datadog's agent fetches
      // it asynchronously). The provider stays bound in the ERROR state, every flag resolves to
      // its false default meanwhile, and it turns READY by itself when the configuration lands.
      // Dropping the client here instead would pin every flag false until the next restart.
      log.warn("Feature-flag provider '{}' is not ready yet for domain '{}'; flags resolve to "
          + "their code defaults until its first configuration arrives",
          provider.getMetadata().getName(), OPENFEATURE_DOMAIN);
      return false;
    }
  }

  private static MutableContext toEvaluationContext(FeatureFlagContext context) {
    MutableContext evaluationContext = new MutableContext();
    if (context == null) {
      return evaluationContext;
    }
    if (context.getTargetingKey() != null) {
      evaluationContext.setTargetingKey(context.getTargetingKey());
    }
    for (Map.Entry<String, String> attribute : context.getAttributes().entrySet()) {
      evaluationContext.add(attribute.getKey(), attribute.getValue());
    }
    return evaluationContext;
  }
}
