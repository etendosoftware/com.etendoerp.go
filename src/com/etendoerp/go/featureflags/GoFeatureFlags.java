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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mixpanel.mixpanelapi.MixpanelAPI;
import com.mixpanel.mixpanelapi.featureflags.config.LocalFlagsConfig;
import com.mixpanel.mixpanelapi.featureflags.provider.LocalFlagsProvider;
import com.mixpanel.openfeature.MixpanelProvider;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;

/**
 * Backend feature-flag entry point for Etendo Go.
 *
 * <p><strong>Application API:</strong> OpenFeature (dev.openfeature:sdk).
 * <strong>Control plane:</strong> Mixpanel Feature Flags, through the official
 * {@code com.mixpanel:mixpanel-java-openfeature} provider. Application code only ever calls
 * {@link #isEnabled(String, FeatureFlagContext)}, so swapping the control plane is a change
 * confined to this class.
 *
 * <p><strong>Evaluation is local.</strong> The Mixpanel provider is installed in local-evaluation
 * mode: flag definitions are fetched once and then refreshed by a daemon poller (default every 60s),
 * and each evaluation is an in-memory rule match. No request ever performs a network call to decide
 * a flag.
 *
 * <p><strong>Failure behaviour — never block, never fail, default false.</strong>
 * <ul>
 *   <li>Token missing or flags disabled → the provider is never installed and every flag resolves
 *       to its code default.</li>
 *   <li>Provider unreachable on startup → definitions are not ready, the provider returns the
 *       default (PROVIDER_NOT_READY) and the poller keeps retrying in the background.</li>
 *   <li>A later poll fails → the previously fetched definitions are retained and keep serving.
 *       Definitions are only ever replaced by a successful fetch.</li>
 *   <li>Unknown flag key, type mismatch, or any unexpected error → the default.</li>
 * </ul>
 *
 * <p>Backend evaluation is authoritative for permissions, data and processes. The web client
 * evaluates the same flags for presentation only; the backend must never trust the client's verdict.
 */
public final class GoFeatureFlags {

  private static final Logger log = LogManager.getLogger(GoFeatureFlags.class);

  /** Gates the paid second-tenant (productive plan) upgrade flow. Default OFF. */
  public static final String FLAG_TENANT_UPGRADE = "tenant-upgrade";

  /**
   * OpenFeature domain the Mixpanel provider is bound to. Using a domain instead of the global
   * default provider keeps this module from clobbering any provider another module installs.
   */
  static final String OPENFEATURE_DOMAIN = "etendo-go";

  private static final Object INIT_LOCK = new Object();
  private static final int EXPOSURE_QUEUE_CAPACITY = 1000;

  private static volatile Client openFeatureClient;
  private static volatile boolean initializationAttempted;

  private GoFeatureFlags() {
  }

  /**
   * Evaluates a boolean flag, defaulting to {@code false}.
   *
   * @param flagKey the flag key as defined in Mixpanel (e.g. {@link #FLAG_TENANT_UPGRADE})
   * @param context targeting information for this evaluation
   * @return {@code true} only when the control plane positively resolves the flag to true
   */
  public static boolean isEnabled(String flagKey, FeatureFlagContext context) {
    Client client = resolveClient();
    if (client == null) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(client.getBooleanValue(flagKey, false, toEvaluationContext(context)));
    } catch (Exception e) {
      // The OpenFeature client already absorbs provider errors into the default value; this guard
      // exists so an unexpected failure can never propagate into the caller's request handling.
      log.warn("Feature flag '{}' evaluation failed, treating as disabled: {}", flagKey,
          e.getMessage(), e);
      return false;
    }
  }

  /**
   * Resets the memoized provider so the next evaluation re-reads the runtime configuration.
   * Intended for tests and for a configuration reload; production code evaluates flags directly.
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
        openFeatureClient = install(GoFeatureFlagsConfig.fromRuntime());
      }
      return openFeatureClient;
    }
  }

  private static Client install(GoFeatureFlagsConfig config) {
    if (!config.isConfigured()) {
      log.info("Etendo Go feature flags are not configured (enabled={}, token present={}); "
          + "all flags resolve to their code defaults", config.isEnabled(),
          config.getProjectToken() != null);
      return null;
    }
    try {
      MixpanelAPI mixpanel = new MixpanelAPI(buildLocalFlagsConfig(config));
      LocalFlagsProvider localFlags = mixpanel.getLocalFlags();
      startPollingAsync(localFlags);
      OpenFeatureAPI api = OpenFeatureAPI.getInstance();
      api.setProvider(OPENFEATURE_DOMAIN, new MixpanelProvider(localFlags));
      log.info("Etendo Go feature flags installed (host={}, pollingIntervalSeconds={})",
          config.getApiHost(), config.getPollingIntervalSeconds());
      return api.getClient(OPENFEATURE_DOMAIN);
    } catch (Exception e) {
      log.error("Could not install the Mixpanel feature-flag provider; all flags resolve to their "
          + "code defaults", e);
      return null;
    }
  }

  private static LocalFlagsConfig buildLocalFlagsConfig(GoFeatureFlagsConfig config) {
    return LocalFlagsConfig.builder()
        .projectToken(config.getProjectToken())
        .apiHost(config.getApiHost())
        .requestTimeoutSeconds(config.getRequestTimeoutSeconds())
        .exposureExecutor(newExposureExecutor())
        .enablePolling(true)
        .pollingIntervalSeconds(config.getPollingIntervalSeconds())
        .build();
  }

  /**
   * Runs the initial definitions fetch — a blocking HTTP call — and the polling schedule it
   * installs on a daemon thread. Doing it inline would make the first flag evaluation of a JVM wait
   * on Mixpanel, which is exactly what an authoritative backend check must never do.
   */
  private static void startPollingAsync(LocalFlagsProvider localFlags) {
    Thread starter = new Thread(localFlags::startPollingForDefinitions,
        "etendo-go-flags-init");
    starter.setDaemon(true);
    starter.start();
  }

  /**
   * Executor for Mixpanel exposure events. Without one the provider posts an exposure event
   * synchronously on the evaluating thread, adding HTTP latency to every flag check. Exposure is
   * analytics, not correctness, so the queue is bounded and overflow is discarded rather than
   * allowed to grow or to push back on the caller.
   */
  private static ThreadPoolExecutor newExposureExecutor() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(EXPOSURE_QUEUE_CAPACITY),
        runnable -> {
          Thread thread = new Thread(runnable, "etendo-go-flags-exposure");
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.DiscardPolicy());
    executor.allowCoreThreadTimeOut(false);
    return executor;
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
