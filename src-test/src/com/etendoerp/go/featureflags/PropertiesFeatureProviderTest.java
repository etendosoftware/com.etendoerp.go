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
}
