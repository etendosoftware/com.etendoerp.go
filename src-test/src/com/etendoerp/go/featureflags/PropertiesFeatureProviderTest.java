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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;

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
}
