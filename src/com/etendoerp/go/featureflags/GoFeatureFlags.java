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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.configcat.PollingModes;
import com.etendoerp.go.common.GoRuntimeProperties;

import dev.openfeature.contrib.providers.configcat.ConfigCatProvider;
import dev.openfeature.contrib.providers.configcat.ConfigCatProviderConfig;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;

/**
 * Backend feature-flag entry point for Etendo Go.
 *
 * <p><strong>Application API:</strong> OpenFeature (dev.openfeature:sdk). Application code only ever
 * calls {@link #isEnabled(String, FeatureFlagContext)} and never sees an OpenFeature type, so the
 * control plane behind it can change without touching a single caller.
 *
 * <p><strong>Control plane: ConfigCat when configured, local properties otherwise.</strong> With
 * {@value #CONFIGCAT_SDK_KEY_PROPERTY} set, {@link ConfigCatProvider} serves flags from ConfigCat,
 * so a flag can be flipped and per-account targeting rules changed <em>without a restart</em>. With
 * it unset, {@link PropertiesFeatureProvider} resolves flags from local configuration
 * ({@code etendo.go.flags.<flag-key>}) in-process with no network call — a plain boolean per
 * environment, with no per-account targeting. Keeping both is deliberate: dev, CI and e2e stay
 * deterministic and independent of a shared remote project.
 *
 * <p><b>Per-account targeting therefore requires ConfigCat.</b> There is deliberately no
 * local mechanism for it: a second way to express the same decision is a second thing to keep in
 * sync, and — worse — it would silently do nothing wherever an SDK key is set, since only one
 * provider is ever installed. A configuration knob that silently does nothing is the same family of
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
   * a ConfigCat targeting rule; with no SDK key configured the flag is a plain per-environment
   * boolean, so a shared environment must have ConfigCat set up before this is switched on for
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
   * ConfigCat SDK key. Set ⇒ flags come from ConfigCat and can be flipped without a restart; unset
   * ⇒ {@link PropertiesFeatureProvider} resolves them from local configuration.
   */
  static final String CONFIGCAT_SDK_KEY_PROPERTY = "etendo.go.configcat.sdkKey";

  /** Environment-variable spelling of {@value #CONFIGCAT_SDK_KEY_PROPERTY}. */
  static final String CONFIGCAT_SDK_KEY_ENV = "ETGO_CONFIGCAT_SDK_KEY";

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

  private static volatile Client openFeatureClient;
  private static volatile boolean initializationAttempted;

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
      return Boolean.TRUE.equals(
          client.getBooleanValue(flagKey, false, toEvaluationContext(context)));
    } catch (Exception e) {
      // The OpenFeature client already absorbs provider errors into the default value; this guard
      // exists so an unexpected failure can never propagate into the caller's request handling.
      log.warn("Feature flag '{}' evaluation failed, treating as disabled: {}", flagKey,
          e.getMessage(), e);
      return false;
    }
  }

  /**
   * Builds the provider backing flag evaluation.
   *
   * <p><strong>This is the swap point for the control plane, and it now has two arms.</strong>
   * With {@value #CONFIGCAT_SDK_KEY_PROPERTY} set, flags come from ConfigCat — hosted, so a flag
   * can be flipped without a restart, with per-account targeting rules evaluated remotely. With it
   * unset, {@link PropertiesFeatureProvider} resolves flags from local configuration. That fallback
   * is deliberate, not a leftover: dev boxes, CI and e2e stay deterministic and independent of any
   * shared remote project. It is a plain per-environment boolean — per-account targeting exists
   * only on the ConfigCat arm, on purpose (see this class's javadoc).
   *
   * <p><strong>An absent, blank or wrong SDK key must mean "flag off", never "flag on".</strong> A
   * blank key takes the local arm, where an unconfigured flag is {@code false}. A wrong key makes
   * ConfigCat's initialization fail, {@link #install()} catch it, and every flag resolve to its
   * {@code false} code default. Both roads lead to off. This is the ETP-4966 shape and it is the
   * one behaviour here that must never be "improved" into a fallback that allows.
   *
   * <p>Initialization is bounded: {@link PollingModes#autoPoll(int, int)} caps how long the first
   * evaluation may wait on the network at {@value #CONFIGCAT_MAX_INIT_WAIT_SECONDS} seconds, after
   * which the client answers from its own in-memory snapshot while background polling continues.
   * Nothing here caches by hand — that is the SDK's job, and duplicating it would add a second
   * staleness window nobody is watching.
   *
   * @return the provider to bind to the {@value #OPENFEATURE_DOMAIN} domain
   */
  private static FeatureProvider createProvider() {
    String sdkKey = StringUtils.trimToNull(GoRuntimeProperties.readValue(
        CONFIGCAT_SDK_KEY_PROPERTY, CONFIGCAT_SDK_KEY_ENV, null));
    if (sdkKey == null) {
      log.info("{} is not configured; feature flags resolve from local configuration",
          CONFIGCAT_SDK_KEY_PROPERTY);
      return new PropertiesFeatureProvider();
    }
    ConfigCatProviderConfig config = ConfigCatProviderConfig.builder()
        .sdkKey(sdkKey)
        .options(options -> options.pollingMode(
            PollingModes.autoPoll(CONFIGCAT_POLL_SECONDS, CONFIGCAT_MAX_INIT_WAIT_SECONDS)))
        .build();
    return new ConfigCatProvider(config);
  }

  /**
   * Resets the memoized provider so the next evaluation re-reads configuration. Intended for tests
   * and for a configuration reload; production code evaluates flags directly.
   */
  static void reset() {
    synchronized (INIT_LOCK) {
      openFeatureClient = null;
      initializationAttempted = false;
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

  private static Client install() {
    try {
      OpenFeatureAPI api = OpenFeatureAPI.getInstance();
      FeatureProvider provider = createProvider();
      // Blocking install: setProvider() alone returns before the provider is
      // ready to evaluate, so the first read after installing can still hit the
      // previous provider and silently answer with the code default. For a flag
      // that gates authorisation, "not ready yet" and "disabled" must never be
      // the same observable outcome.
      api.setProviderAndWait(OPENFEATURE_DOMAIN, provider);
      log.info("Etendo Go feature flags installed using provider '{}'",
          provider.getMetadata().getName());
      return api.getClient(OPENFEATURE_DOMAIN);
    } catch (Exception e) {
      log.error("Could not install the feature-flag provider; all flags resolve to their code "
          + "defaults", e);
      return null;
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
