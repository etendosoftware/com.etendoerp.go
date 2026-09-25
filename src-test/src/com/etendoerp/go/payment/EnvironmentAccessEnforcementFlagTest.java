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

package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openbravo.base.session.OBPropertiesProvider;
import org.mockito.MockedStatic;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.featureflags.PropertiesFeatureProvider;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;

/**
 * ETP-5047 — the kill switch of environment-access enforcement,
 * {@code environment-access-enforcement-off}.
 *
 * <p>Phrased as an OFF switch because every failure of the flag layer resolves to {@code false}:
 * unset, unparseable, a provider that throws — all must read "not switched off", i.e. keep
 * enforcing. Only an explicit {@code true} stops the refusal. And the tenant must reach the
 * evaluation context as {@code clientId}, or a per-tenant ConfigCat rule could never match.
 *
 * <p>{@code Openbravo.properties} is mocked empty so a developer's local override cannot leak into
 * the "unset" assertion (same isolation as {@link DemoDataTransferFlagTest}).
 */
class EnvironmentAccessEnforcementFlagTest {

  private static final String FLAG_PROPERTY = "etendo.go.flags.environment-access-enforcement-off";
  /** {@code GoFeatureFlags.OPENFEATURE_DOMAIN}: package-private there, so named here. */
  private static final String OPENFEATURE_DOMAIN = "etendo-go";
  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";

  private MockedStatic<OBPropertiesProvider> propertiesMock;

  @BeforeEach
  void isolateFromOpenbravoProperties() {
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesMock = mockStatic(OBPropertiesProvider.class);
    propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
  }

  @AfterEach
  void clearOverride() {
    System.clearProperty(FLAG_PROPERTY);
    propertiesMock.close();
  }

  @Test
  void anUnsetFlagKeepsEnforcing() {
    assertFalse(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID));
    assertFalse(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(null));
  }

  @ParameterizedTest
  @ValueSource(strings = { "maybe", "on", "enabled", "2" })
  void aValueThatIsNotABooleanKeepsEnforcing(String value) {
    System.setProperty(FLAG_PROPERTY, value);

    assertFalse(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID));
  }

  @Test
  void anExplicitFalseKeepsEnforcing() {
    System.setProperty(FLAG_PROPERTY, "false");

    assertFalse(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID));
  }

  @Test
  void onlyAnExplicitTrueSwitchesEnforcementOff() {
    System.setProperty(FLAG_PROPERTY, "true");

    assertTrue(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID));
  }

  /** QA-low — every local spelling of "true" switches enforcement off (trimmed, any case). */
  @ParameterizedTest
  @ValueSource(strings = { "true", "TRUE", "True", " true ", "Y", "y", "yes", "YES", "1" })
  void everyLocalSpellingOfTrueSwitchesEnforcementOff(String value) {
    System.setProperty(FLAG_PROPERTY, value);

    assertTrue(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID), value);
  }

  /** QA-low — every local spelling of "false", and anything unparseable, keeps enforcing. */
  @ParameterizedTest
  @ValueSource(strings = { "false", "FALSE", "N", "n", "no", "NO", "0", "on", "off", "enabled",
      "truee", "t" })
  void everyOtherValueKeepsEnforcing(String value) {
    System.setProperty(FLAG_PROPERTY, value);

    assertFalse(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID), value);
  }

  @Test
  void evaluatesTheKillSwitchKeyWithTheTenantAsClientIdAndNoAccount() {
    try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      List<FeatureFlagContext> contexts = new ArrayList<>();
      flags.when(() -> GoFeatureFlags.isEnabled(
          org.mockito.ArgumentMatchers.eq("environment-access-enforcement-off"),
          org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            contexts.add(invocation.getArgument(1));
            return true;
          });

      assertTrue(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID));

      assertEquals(1, contexts.size());
      FeatureFlagContext context = contexts.get(0);
      assertEquals(CLIENT_ID, context.getAttributes().get(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
      assertEquals(null, context.getTargetingKey(), "account-less: the rule targets the tenant");
    }
  }

  @Test
  void theTenantReachesTheProviderSoAPerTenantRuleCanMatch() {
    // End to end through the real GoFeatureFlags: a provider that switches enforcement off for
    // one tenant only, the way a ConfigCat rule on clientId would.
    EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID); // initialise the client
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    try {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, new StubProvider(false, CLIENT_ID));

      assertTrue(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID));
      assertFalse(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff("ANOTHER-TENANT"));
      assertFalse(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(null));
    } finally {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, new PropertiesFeatureProvider());
    }
  }

  @Test
  void aProviderThatFailsKeepsEnforcing() {
    EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID); // initialise the client
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    try {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, new StubProvider(true, CLIENT_ID));

      // The failing provider would have answered true for this very tenant: a failure must still
      // never read as "switched off".
      assertFalse(EnvironmentAccessEnforcementFlag.isEnforcementSwitchedOff(CLIENT_ID));
    } finally {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, new PropertiesFeatureProvider());
    }
  }

  /**
   * A boolean provider that answers {@code true} only when the evaluation context's
   * {@code clientId} is {@code switchedOffFor} — or throws on every evaluation.
   */
  private static final class StubProvider implements FeatureProvider {
    private final boolean fail;
    private final String switchedOffFor;

    StubProvider(boolean fail, String switchedOffFor) {
      this.fail = fail;
      this.switchedOffFor = switchedOffFor;
    }

    @Override
    public Metadata getMetadata() {
      return () -> "etp-5047-stub";
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue,
        EvaluationContext ctx) {
      if (fail) {
        throw new IllegalStateException("control plane unreachable");
      }
      Value clientId = ctx == null ? null : ctx.getValue(FeatureFlagContext.ATTRIBUTE_CLIENT_ID);
      boolean match = "environment-access-enforcement-off".equals(key) && clientId != null
          && switchedOffFor.equals(clientId.asString());
      return ProviderEvaluation.<Boolean>builder().value(match).reason("TARGETING_MATCH").build();
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
