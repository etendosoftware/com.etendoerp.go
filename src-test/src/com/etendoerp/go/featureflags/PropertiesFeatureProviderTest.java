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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

import datadog.trace.api.openfeature.Provider;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;

/**
 * @covers com.etendoerp.go.featureflags.PropertiesFeatureProvider
 * @covers com.etendoerp.go.featureflags.GoFeatureFlags
 */
class PropertiesFeatureProviderTest {

  /**
   * A key that belongs to no shipped feature. The provider is key-agnostic, so these tests only ever
   * needed <em>a</em> key — and borrowing a live flag's key made them break when that flag retired
   * (ETP-4966). Keep this deliberately fictional so it cannot happen again.
   */
  private static final String FLAG = "sample-flag";
  private static final String FLAG_PROPERTY = PropertiesFeatureProvider.PROPERTY_PREFIX + FLAG;

  /**
   * ConfigCat SDK key property name (ETP-5267). Reused directly from {@link GoFeatureFlags}
   * instead of duplicated as a literal, so this suite cannot silently drift from the real
   * property name it guards.
   */
  private static final String CONFIGCAT_SDK_KEY_PROPERTY = GoFeatureFlags.CONFIGCAT_SDK_KEY_PROPERTY;

  private final PropertiesFeatureProvider provider = new PropertiesFeatureProvider();

  private MockedStatic<OBPropertiesProvider> propertiesMock;

  private static final Supplier<FeatureProvider> REAL_PROVIDER_FACTORY =
      GoFeatureFlags.providerFactory;
  private static final BooleanSupplier REAL_AGENT_PROBE = GoFeatureFlags.datadogAgentProbe;

  /**
   * Isolate every test from the ambient {@code Openbravo.properties}/{@code gradle.properties} on
   * whatever machine runs this suite: {@code ConfigPropertyReader} falls back to
   * {@code OBPropertiesProvider} whenever the JVM system property is unset, so a developer's local
   * override (e.g. {@code etendo.go.flags.sample-flag=true}, kept locally to exercise a flag by
   * hand) would otherwise leak into "unconfigured" assertions here and fail them
   * on that machine only, never in CI. Returning empty properties forces every test to exercise
   * only what it explicitly sets via {@code System.setProperty}. Same pattern as
   * {@code PublicUrlResolverTest}.
   */
  @BeforeEach
  void mockOpenbravoProperties() {
    OBPropertiesProvider mockProvider = mock(OBPropertiesProvider.class);
    when(mockProvider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesMock = mockStatic(OBPropertiesProvider.class);
    propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(mockProvider);
  }

  @AfterEach
  void clearOverrides() {
    if (propertiesMock != null) {
      propertiesMock.close();
    }
    System.clearProperty(FLAG_PROPERTY);
    System.clearProperty(CONFIGCAT_SDK_KEY_PROPERTY);
    System.clearProperty(GoFeatureFlags.DATADOG_FLAGS_ENABLED_PROPERTY);
    GoFeatureFlags.providerFactory = REAL_PROVIDER_FACTORY;
    GoFeatureFlags.datadogAgentProbe = REAL_AGENT_PROBE;
    GoFeatureFlags.reset();
  }

  @Test
  void anUnconfiguredFlagResolvesToTheDefault() {
    ProviderEvaluation<Boolean> evaluation = provider.getBooleanEvaluation(FLAG, false, null);
    assertFalse(evaluation.getValue());
    assertNull(evaluation.getErrorCode());
  }

  @ParameterizedTest
  @ValueSource(strings = { "true", "TRUE", "True", "Y", "y", "yes", "1" })
  void affirmativeValuesEnableTheFlag(String configured) {
    System.setProperty(FLAG_PROPERTY, configured);
    assertTrue(provider.getBooleanEvaluation(FLAG, false, null).getValue());
  }

  @ParameterizedTest
  @ValueSource(strings = { "false", "FALSE", "N", "no", "0" })
  void negativeValuesDisableTheFlag(String configured) {
    System.setProperty(FLAG_PROPERTY, configured);
    assertFalse(provider.getBooleanEvaluation(FLAG, true, null).getValue());
  }

  /**
   * A typo must not be indistinguishable from an intentionally disabled flag: the caller still gets
   * its default, but the evaluation carries a parse error so the mistake is visible.
   */
  @ParameterizedTest
  @ValueSource(strings = { "maybe", "on", "enabled", "tru", "2" })
  void unparseableValuesFallBackToTheDefaultAndReportAParseError(String configured) {
    System.setProperty(FLAG_PROPERTY, configured);
    ProviderEvaluation<Boolean> evaluation = provider.getBooleanEvaluation(FLAG, false, null);
    assertFalse(evaluation.getValue());
    assertEquals(ErrorCode.PARSE_ERROR, evaluation.getErrorCode());
  }

  @Test
  void aBlankFlagKeyResolvesToTheDefault() {
    assertFalse(provider.getBooleanEvaluation("   ", false, null).getValue());
    assertFalse(provider.getBooleanEvaluation(null, false, null).getValue());
  }

  @ParameterizedTest
  @CsvSource({
      "sample-flag,     ETGO_FLAG_SAMPLE_FLAG",
      "some.other-flag, ETGO_FLAG_SOME_OTHER_FLAG",
      "simple,          ETGO_FLAG_SIMPLE"
  })
  void mapsFlagKeysToEnvironmentVariableNames(String flagKey, String expected) {
    assertEquals(expected, PropertiesFeatureProvider.toEnvName(flagKey));
  }

  /**
   * Only booleans are backed by configuration. The other types return the caller's default with a
   * type mismatch rather than pretending to resolve.
   */
  @Test
  void nonBooleanTypesReturnTheDefaultWithATypeMismatch() {
    assertEquals(ErrorCode.TYPE_MISMATCH,
        provider.getStringEvaluation(FLAG, "fallback", null).getErrorCode());
    assertEquals("fallback", provider.getStringEvaluation(FLAG, "fallback", null).getValue());
    assertEquals(7, provider.getIntegerEvaluation(FLAG, 7, null).getValue());
    assertEquals(1.5d, provider.getDoubleEvaluation(FLAG, 1.5d, null).getValue());
    assertEquals(ErrorCode.TYPE_MISMATCH,
        provider.getObjectEvaluation(FLAG, new Value("x"), null).getErrorCode());
  }

  @Test
  void exposesItsName() {
    assertEquals("etendo-go-properties", provider.getMetadata().getName());
  }

  // --- End to end through the OpenFeature entry point application code actually calls ---

  @Test
  void flagsDefaultToFalseThroughTheEntryPoint() {
    GoFeatureFlags.reset();
    assertFalse(GoFeatureFlags.isEnabled(FLAG, FeatureFlagContext.forAccount("user@example.com")));
    assertFalse(GoFeatureFlags.isEnabled(FLAG, null));
  }

  @Test
  void aConfiguredFlagReadsAsEnabledThroughTheEntryPoint() {
    System.setProperty(FLAG_PROPERTY, "true");
    GoFeatureFlags.reset();
    assertTrue(GoFeatureFlags.isEnabled(FLAG, FeatureFlagContext.forAccount("user@example.com")));
  }

  @Test
  void anUnknownFlagKeyReadsAsDisabled() {
    GoFeatureFlags.reset();
    assertFalse(GoFeatureFlags.isEnabled("no-such-flag",
        FeatureFlagContext.forAccount("user@example.com")));
  }

  // --- ConfigCat SDK key guard (ETP-5267) ---
  //
  // GoFeatureFlags#createProvider() picks the control plane from the ConfigCat SDK key: absent
  // or blank ⇒ the local PropertiesFeatureProvider arm exercised above; set ⇒ ConfigCatProvider.
  // An absent, blank or wrong key must resolve every flag to `false`, never `true` — this is the
  // exact ETP-4966 failure shape (a control plane that cannot be read as "on" must never be
  // indistinguishable from "on"). `mockOpenbravoProperties()` already isolates the ConfigCat key
  // from a real Openbravo.properties/gradle.properties entry (it mocks OBPropertiesProvider to
  // empty for every property lookup, not just the flag's own); `clearOverrides()` now also clears
  // the JVM system property so no test here can leak into a sibling. Not isolated: the
  // ETGO_CONFIGCAT_SDK_KEY environment-variable fallback in ConfigPropertyReader. This suite has
  // no env-var-stubbing utility (no system-stubs/system-lambda dependency is on the classpath),
  // and JVM system properties always win over it here, so every case below sets the system
  // property explicitly rather than relying on "unset" for the blank/wrong cases. Only the
  // "absent" case is exposed to that gap — on a machine that literally exports
  // ETGO_CONFIGCAT_SDK_KEY, that one test would take the ConfigCat arm instead of the local one.

  /**
   * No SDK key anywhere ⇒ {@link GoFeatureFlags#createProvider()} takes the local-configuration
   * arm, where an unconfigured flag is {@code false}.
   */
  @Test
  void anAbsentConfigCatSdkKeyResolvesFlagsToFalseThroughTheEntryPoint() {
    System.clearProperty(CONFIGCAT_SDK_KEY_PROPERTY);
    GoFeatureFlags.reset();
    assertFalse(GoFeatureFlags.isEnabled(FLAG, FeatureFlagContext.forAccount("user@example.com")));
  }

  /**
   * A blank/whitespace-only key must collapse to "absent", exactly like the case above: it is
   * {@code StringUtils.trimToNull} in {@link GoFeatureFlags#createProvider()} that makes this
   * true, turning any blank string into {@code null} before the {@code null} check that decides
   * between the two arms. Because that collapse happens first, {@link
   * dev.openfeature.contrib.providers.configcat.ConfigCatProvider} is never constructed and
   * ConfigCat is never attempted — the local arm is a pure in-process property lookup, so a
   * {@code false} result here is also proof of that.
   */
  @ParameterizedTest
  @ValueSource(strings = { "", "   ", "\t\t" })
  void aBlankConfigCatSdkKeyFallsBackToLocalConfigurationAndResolvesFalse(String blankKey) {
    System.setProperty(CONFIGCAT_SDK_KEY_PROPERTY, blankKey);
    GoFeatureFlags.reset();
    assertFalse(GoFeatureFlags.isEnabled(FLAG, FeatureFlagContext.forAccount("user@example.com")));
  }

  /**
   * A syntactically wrong key must still resolve every flag to {@code false} — and this
   * assertion is hermetic, not merely hoped to be. Verified both by disassembling the actual
   * {@code configcat-java-client} jar this module ships and by running this exact test against
   * it: {@code com.configcat.ConfigCatClient#isValidKey} requires the key to split on {@code "/"}
   * into either two 22-character segments or the three-part {@code
   * configcat-sdk-1/<22 chars>/<22 chars>} form. None of the keys below satisfy that shape, so
   * {@code ConfigCatClient.get(String, Consumer)} throws {@code IllegalArgumentException} (e.g.
   * {@code "SDK Key 'wrong/lengths' is invalid."}) as a pure string check — before it ever opens a
   * connection or starts a polling thread. That exception propagates up through {@code
   * ConfigCatProvider#initialize} and {@code OpenFeatureAPI#setProviderAndWait}, straight into
   * {@link GoFeatureFlags#install()}'s own {@code catch (Exception e)} (confirmed via the actual
   * "Could not install the feature-flag provider" log line, not assumed): the client this method
   * memoizes is {@code null}, and {@link GoFeatureFlags#isEnabled(String, FeatureFlagContext)}'s
   * {@code if (client == null) return false;} guard is what answers here — OpenFeature's own
   * per-evaluation defaulting is never even reached. No live ConfigCat project, no network
   * reachability and no bounded wait are exercised — this only proves the guard, not ConfigCat's
   * own error handling once a connection is actually attempted with a key that merely doesn't
   * exist. That is a real, deliberate scope limit: this suite has no ConfigCat sandbox and must
   * not depend on one.
   */
  @ParameterizedTest
  @ValueSource(strings = { "not-a-real-configcat-sdk-key", "too/many/slashes/in-here", "wrong/lengths" })
  void aSyntacticallyInvalidConfigCatSdkKeyStillResolvesFlagsToFalseWithoutNetworkAccess(String bogusKey) {
    System.setProperty(CONFIGCAT_SDK_KEY_PROPERTY, bogusKey);
    GoFeatureFlags.reset();
    assertFalse(GoFeatureFlags.isEnabled(FLAG, FeatureFlagContext.forAccount("user@example.com")));
  }

  /**
   * A configured local flag must not become an implicit fallback when ConfigCat is selected but
   * cannot initialize. Otherwise a production ConfigCat outage or invalid key could enable a
   * feature through stale local configuration instead of failing closed.
   */
  @Test
  void aConfigCatInitializationFailureDoesNotFallBackToAnEnabledLocalFlag() {
    System.setProperty(CONFIGCAT_SDK_KEY_PROPERTY, "wrong/lengths");
    System.setProperty(FLAG_PROPERTY, "true");
    GoFeatureFlags.reset();

    assertFalse(GoFeatureFlags.isEnabled(FLAG, FeatureFlagContext.forAccount("user@example.com")));
  }

  // --- Datadog arm and provider selection order (ETP-5751) ---

  /**
   * Datadog → ConfigCat → local properties: the first arm whose prerequisites are all met wins. A
   * Datadog switch without the agent is "not configured", so it falls through rather than failing.
   */
  @ParameterizedTest
  @CsvSource(nullValues = "NULL", value = {
      "true,  true,  NULL,    DATADOG",
      "true,  true,  sdk/key, DATADOG",
      "true,  false, sdk/key, CONFIGCAT",
      "true,  false, NULL,    PROPERTIES",
      "false, true,  sdk/key, CONFIGCAT",
      "false, true,  NULL,    PROPERTIES",
      "false, false, NULL,    PROPERTIES",
  })
  void selectsTheFirstUsableControlPlane(boolean datadogRequested, boolean agentLoaded,
      String sdkKey, GoFeatureFlags.ProviderKind expected) {
    assertEquals(expected, GoFeatureFlags.selectProvider(datadogRequested, agentLoaded, sdkKey));
  }

  /** This test JVM runs without {@code -javaagent:dd-java-agent.jar}, like dev, CI and e2e. */
  @Test
  void theDatadogAgentIsNotDetectedWithoutTheJavaAgent() {
    assertFalse(GoFeatureFlags.isDatadogAgentLoaded());
  }

  /**
   * The Datadog switch on in an agent-less JVM must fall through to the local arm — not install a
   * Datadog provider that can never become ready and so turn every flag off. A configured local
   * flag reading true is what proves the fall-through happened.
   */
  @Test
  void theDatadogSwitchWithoutTheAgentFallsBackToLocalConfiguration() {
    System.setProperty(GoFeatureFlags.DATADOG_FLAGS_ENABLED_PROPERTY, "true");
    System.setProperty(FLAG_PROPERTY, "true");
    GoFeatureFlags.reset();

    assertTrue(GoFeatureFlags.isEnabled(FLAG, FeatureFlagContext.forAccount("user@example.com")));
  }

  /**
   * With the switch on, the agent loaded and a ConfigCat key also set, Datadog wins: it is first in
   * the priority order, so a key left over from the ConfigCat era must not shadow it.
   */
  @Test
  void theDatadogSwitchWithTheAgentLoadedSelectsTheDatadogProviderOverConfigCat() {
    System.setProperty(GoFeatureFlags.DATADOG_FLAGS_ENABLED_PROPERTY, "true");
    System.setProperty(CONFIGCAT_SDK_KEY_PROPERTY, "sdk/key");
    GoFeatureFlags.datadogAgentProbe = () -> true;

    assertInstanceOf(Provider.class, GoFeatureFlags.createProvider());
  }

  /**
   * The Datadog switch is read with {@code GoRuntimeProperties.readBoolean}, and the container
   * entrypoint parses {@code DD_FEATURE_FLAGS_ENABLED} with the same rule to decide whether to load
   * the agent. Pinning the accepted set here catches a change on either side that would let the
   * entrypoint and the JVM disagree about the switch.
   */
  @ParameterizedTest
  @CsvSource({
      "true,   true",
      "TRUE,   true",
      "' True ', true",
      "Y,      true",
      "y,      true",
      "1,      false",
      "yes,    false",
      "false,  false",
      "N,      false",
  })
  void theDatadogSwitchAcceptsOnlyTheReadBooleanTruthySet(String configured, boolean selected) {
    System.setProperty(GoFeatureFlags.DATADOG_FLAGS_ENABLED_PROPERTY, configured);
    GoFeatureFlags.datadogAgentProbe = () -> true;

    FeatureProvider created = GoFeatureFlags.createProvider();

    assertEquals(selected, created instanceof Provider);
  }

  // --- install() failure paths (ETP-5751) ---

  /**
   * A bounded wait that runs out ({@link ProviderNotReadyError}) is not a broken provider: the
   * client stays bound, flags read false meanwhile, and the provider's own PROVIDER_READY event
   * turns them live with no reinstall. Dropping the client instead would pin every flag false until
   * the next restart.
   */
  @Test
  void aProviderNotReadyYetStaysBoundAndServesItsValueOnceReady() {
    LateConfiguredProvider lateProvider = new LateConfiguredProvider();
    AtomicInteger installs = new AtomicInteger();
    GoFeatureFlags.providerFactory = () -> {
      installs.incrementAndGet();
      return lateProvider;
    };
    GoFeatureFlags.reset();

    assertFalse(GoFeatureFlags.isEnabled(FLAG, FeatureFlagContext.forAccount("user@example.com")));
    assertFalse(GoFeatureFlags.isEnabled("other-flag", null));

    lateProvider.configure(true);
    lateProvider.emitProviderReady(ProviderEventDetails.builder().build()).await();

    assertTrue(GoFeatureFlags.isEnabled(FLAG, FeatureFlagContext.forAccount("user@example.com")));
    assertEquals(1, installs.get());
  }

  /** The not-ready path hands back a client rather than {@code null}. */
  @Test
  void installReturnsABoundClientWhenTheProviderIsNotReadyYet() {
    GoFeatureFlags.providerFactory = LateConfiguredProvider::new;

    assertNotNull(GoFeatureFlags.install());
  }

  /**
   * A provider that cannot start ({@link FatalError}, or any other exception) yields no client and
   * every flag reads false — even with the local properties arm saying {@code true}. Falling
   * through to that arm is the ETP-4966 shape and must never happen.
   */
  @ParameterizedTest
  @ValueSource(strings = { "fatal", "runtime" })
  void aProviderThatFailsToInitializeYieldsNoClientAndDoesNotFallBackToLocalFlags(String failure) {
    System.setProperty(FLAG_PROPERTY, "true");
    AtomicInteger installs = new AtomicInteger();
    GoFeatureFlags.providerFactory = () -> {
      installs.incrementAndGet();
      return new FailingProvider("fatal".equals(failure)
          ? new FatalError("provider cannot start")
          : new IllegalStateException("provider cannot start"));
    };

    assertNull(GoFeatureFlags.install());

    GoFeatureFlags.reset();
    assertFalse(GoFeatureFlags.isEnabled(FLAG, FeatureFlagContext.forAccount("user@example.com")));
    assertFalse(GoFeatureFlags.isEnabled(FLAG, null));
    assertEquals(2, installs.get());
  }

  /** Stands in for a provider whose first configuration arrives after the bounded wait. */
  private static final class LateConfiguredProvider extends EventProvider {
    private volatile Boolean configured;

    void configure(boolean value) {
      configured = value;
    }

    @Override
    public void initialize(EvaluationContext evaluationContext) {
      throw new ProviderNotReadyError("first configuration not received yet");
    }

    @Override
    public Metadata getMetadata() {
      return () -> "late-configured";
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue,
        EvaluationContext ctx) {
      Boolean value = configured;
      if (value == null) {
        return ProviderEvaluation.<Boolean>builder().value(defaultValue)
            .reason(Reason.ERROR.toString()).errorCode(ErrorCode.PROVIDER_NOT_READY).build();
      }
      return ProviderEvaluation.<Boolean>builder().value(value)
          .reason(Reason.STATIC.toString()).build();
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<String>builder().value(defaultValue).build();
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<Integer>builder().value(defaultValue).build();
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<Double>builder().value(defaultValue).build();
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<Value>builder().value(defaultValue).build();
    }
  }

  /** Answers {@code true} for everything, so only a failed install can explain a {@code false}. */
  private static final class FailingProvider extends EventProvider {
    private final RuntimeException failure;

    FailingProvider(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public void initialize(EvaluationContext evaluationContext) {
      throw failure;
    }

    @Override
    public Metadata getMetadata() {
      return () -> "failing";
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<Boolean>builder().value(true).build();
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<String>builder().value(defaultValue).build();
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<Integer>builder().value(defaultValue).build();
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<Double>builder().value(defaultValue).build();
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<Value>builder().value(defaultValue).build();
    }
  }
}
