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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.go.common.GoAccountResolver;
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
 * The {@code fiscal-model-390} gate: how the Fiscal Models catalog answer and save are shaped with
 * the flag on and off, that every failure of the flag layer reads as "hidden", and — the part a
 * per-user Datadog rule depends on — what evaluation context reaches the provider.
 *
 * <p>{@code Openbravo.properties} is mocked empty so a developer's local override cannot leak into
 * the "unset" assertion (same isolation as {@code EnvironmentAccessEnforcementFlagTest}).
 *
 * @covers com.etendoerp.go.schemaforge.FiscalModel390Flag
 */
class FiscalModel390FlagTest {

  private static final String FLAG_PROPERTY = "etendo.go.flags.fiscal-model-390";
  /** {@code GoFeatureFlags.OPENFEATURE_DOMAIN}: package-private there, so named here. */
  private static final String OPENFEATURE_DOMAIN = "etendo-go";
  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String ACCOUNT_EMAIL = "Ana.Garcia@Example.com";

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

  // ---------------------------------------------------------------- applyToRead

  @Test
  void readWithTheFlagOffDropsThe390AndMarksItUnavailable() throws Exception {
    JSONObject stored = new JSONObject("{\"303\":true,\"390\":true}");

    JSONObject answer = FiscalModel390Flag.applyToRead(stored, false);

    assertTrue(answer.getBoolean("303"));
    assertFalse(answer.has("390"));
    JSONArray unavailable = answer.getJSONArray(FiscalModel390Flag.UNAVAILABLE_KEY);
    assertEquals(1, unavailable.length());
    assertEquals("390", unavailable.getString(0));
    assertEquals(2, answer.length());
  }

  @Test
  void readWithTheFlagOffMarksThe390UnavailableEvenWhenNothingIsStored() throws Exception {
    JSONObject answer = FiscalModel390Flag.applyToRead(new JSONObject(), false);

    assertEquals(1, answer.length());
    assertEquals("390", answer.getJSONArray(FiscalModel390Flag.UNAVAILABLE_KEY).getString(0));
  }

  @Test
  void readWithTheFlagOnAnswersExactlyTheStoredCatalog() throws Exception {
    JSONObject stored = new JSONObject("{\"303\":true,\"349\":false,\"390\":true}");

    JSONObject answer = FiscalModel390Flag.applyToRead(stored, true);

    assertEquals(stored.toString(), answer.toString());
    assertFalse(answer.has(FiscalModel390Flag.UNAVAILABLE_KEY));
  }

  @Test
  void readNeverEchoesAStoredMarker() throws Exception {
    JSONObject stored = new JSONObject("{\"303\":true,\"_unavailable\":[\"303\"]}");

    JSONObject on = FiscalModel390Flag.applyToRead(stored, true);
    JSONObject off = FiscalModel390Flag.applyToRead(stored, false);

    assertFalse(on.has(FiscalModel390Flag.UNAVAILABLE_KEY));
    assertEquals("[\"390\"]", off.getJSONArray(FiscalModel390Flag.UNAVAILABLE_KEY).toString());
  }

  @Test
  void readNeverModifiesTheStoredCatalog() throws Exception {
    JSONObject stored = new JSONObject("{\"303\":true,\"390\":true}");
    String before = stored.toString();

    FiscalModel390Flag.applyToRead(stored, false);
    FiscalModel390Flag.applyToRead(stored, true);

    assertEquals(before, stored.toString());
  }

  // ---------------------------------------------------------------- applyToWrite

  @Test
  void writeWithTheFlagOffIgnoresTheIncoming390AndKeepsTheStoredOne() throws Exception {
    JSONObject incoming = new JSONObject("{\"303\":true,\"390\":false}");
    JSONObject stored = new JSONObject("{\"303\":false,\"390\":true}");

    JSONObject toSave = FiscalModel390Flag.applyToWrite(incoming, stored, false);

    assertTrue(toSave.getBoolean("303"));
    assertTrue(toSave.getBoolean("390"), "a tenant's earlier 390 choice survives the save");
  }

  @Test
  void writeWithTheFlagOffCannotSwitchThe390OnWhenNothingWasStored() throws Exception {
    JSONObject incoming = new JSONObject("{\"303\":true,\"390\":true}");

    JSONObject toSave = FiscalModel390Flag.applyToWrite(incoming, new JSONObject(), false);

    assertFalse(toSave.has("390"));
    assertTrue(toSave.getBoolean("303"));
  }

  @Test
  void writeWithTheFlagOnStoresTheIncoming390() throws Exception {
    JSONObject incoming = new JSONObject("{\"303\":true,\"390\":false}");
    JSONObject stored = new JSONObject("{\"390\":true}");

    JSONObject toSave = FiscalModel390Flag.applyToWrite(incoming, stored, true);

    assertFalse(toSave.getBoolean("390"));
    assertTrue(toSave.getBoolean("303"));
  }

  @Test
  void writeWithTheFlagOnKeepsTheStored390WhenTheBodyOmitsIt() throws Exception {
    JSONObject stored = new JSONObject("{\"303\":true,\"390\":true}");

    JSONObject toSave = FiscalModel390Flag.applyToWrite(
        new JSONObject("{\"303\":false}"), stored, true);

    assertFalse(toSave.getBoolean("303"));
    assertTrue(toSave.getBoolean("390"), "absent is not false: the stored 390 survives");
  }

  @Test
  void writeNeverStoresTheMarker() throws Exception {
    JSONObject incoming = new JSONObject("{\"303\":true,\"_unavailable\":[\"390\"]}");

    assertFalse(FiscalModel390Flag.applyToWrite(incoming, new JSONObject(), true)
        .has(FiscalModel390Flag.UNAVAILABLE_KEY));
    assertFalse(FiscalModel390Flag.applyToWrite(incoming, new JSONObject(), false)
        .has(FiscalModel390Flag.UNAVAILABLE_KEY));
  }

  @Test
  void writeNeverModifiesItsArguments() throws Exception {
    JSONObject incoming = new JSONObject("{\"303\":true,\"390\":false,\"_unavailable\":[\"390\"]}");
    JSONObject stored = new JSONObject("{\"390\":true}");
    String incomingBefore = incoming.toString();
    String storedBefore = stored.toString();

    FiscalModel390Flag.applyToWrite(incoming, stored, false);
    FiscalModel390Flag.applyToWrite(incoming, stored, true);

    assertEquals(incomingBefore, incoming.toString());
    assertEquals(storedBefore, stored.toString());
  }

  // ---------------------------------------------------------------- flag value

  @Test
  void anUnsetFlagReadsAsHidden() {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::findCurrentAccountEmail).thenReturn(ACCOUNT_EMAIL);

      assertFalse(FiscalModel390Flag.isEnabled(CLIENT_ID));
      assertFalse(FiscalModel390Flag.isEnabled(null));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "maybe", "on", "enabled", "2" })
  void aValueThatIsNotABooleanReadsAsHidden(String value) {
    System.setProperty(FLAG_PROPERTY, value);

    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      assertFalse(FiscalModel390Flag.isEnabled(CLIENT_ID), value);
    }
  }

  @Test
  void anExplicitTrueMakesThe390Available() {
    System.setProperty(FLAG_PROPERTY, "true");

    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      assertTrue(FiscalModel390Flag.isEnabled(CLIENT_ID));
    }
  }

  // ---------------------------------------------------------------- evaluation context

  @Test
  void contextForAnAccountCarriesTheEmailThreeWaysAndTheTenant() {
    FeatureFlagContext context = FiscalModel390Flag.contextFor(ACCOUNT_EMAIL, CLIENT_ID);

    assertEquals(ACCOUNT_EMAIL, context.getTargetingKey());
    assertEquals(ACCOUNT_EMAIL, context.getAttributes().get(FeatureFlagContext.ATTRIBUTE_EMAIL));
    assertEquals("ana.garcia@example.com",
        context.getAttributes().get(FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE));
    assertEquals(CLIENT_ID, context.getAttributes().get(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
  }

  @Test
  void contextWithNoAccountCarriesTheTenantOnly() {
    FeatureFlagContext context = FiscalModel390Flag.contextFor(null, CLIENT_ID);

    assertNull(context.getTargetingKey());
    assertEquals(1, context.getAttributes().size());
    assertEquals(CLIENT_ID, context.getAttributes().get(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
  }

  @Test
  void isEnabledEvaluatesTheFlagKeyWithTheResolvedAccountEmail() {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
         MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      accounts.when(GoAccountResolver::findCurrentAccountEmail).thenReturn(ACCOUNT_EMAIL);
      List<FeatureFlagContext> contexts = new ArrayList<>();
      flags.when(() -> GoFeatureFlags.isEnabled(eq("fiscal-model-390"), any()))
          .thenAnswer(invocation -> {
            contexts.add(invocation.getArgument(1));
            return true;
          });

      assertTrue(FiscalModel390Flag.isEnabled(CLIENT_ID));

      assertEquals(1, contexts.size());
      assertEquals(ACCOUNT_EMAIL, contexts.get(0).getTargetingKey());
      assertEquals(CLIENT_ID,
          contexts.get(0).getAttributes().get(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }

  /**
   * End to end through the real {@link GoFeatureFlags}: what a hosted provider actually receives.
   * A Datadog rule matches the lowercase {@code email} attribute by exact name and value, so it
   * must arrive lowercased, next to the targeting key, {@code Email} and {@code clientId}.
   */
  @Test
  void theAccountEmailAndTheTenantReachTheProvider() {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::findCurrentAccountEmail).thenReturn(ACCOUNT_EMAIL);
      CapturingProvider provider = new CapturingProvider(false, "ana.garcia@example.com");

      boolean enabled = withProvider(provider, () -> FiscalModel390Flag.isEnabled(CLIENT_ID));

      assertTrue(enabled, "a per-user rule on the lowercase email matches");
      assertEquals(1, provider.contexts.size());
      EvaluationContext ctx = provider.contexts.get(0);
      assertEquals(ACCOUNT_EMAIL, ctx.getTargetingKey());
      assertEquals(ACCOUNT_EMAIL, stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL));
      assertEquals("ana.garcia@example.com",
          stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE));
      assertEquals(CLIENT_ID, stringValue(ctx, FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }

  @Test
  void withNoResolvableAccountOnlyTheTenantReachesTheProvider() {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::findCurrentAccountEmail).thenReturn(null);
      CapturingProvider provider = new CapturingProvider(false, "ana.garcia@example.com");

      boolean enabled = withProvider(provider, () -> FiscalModel390Flag.isEnabled(CLIENT_ID));

      assertFalse(enabled, "an email rule cannot match an account-less context");
      assertEquals(1, provider.contexts.size());
      EvaluationContext ctx = provider.contexts.get(0);
      String targetingKey = ctx.getTargetingKey();
      assertTrue(targetingKey == null || targetingKey.isEmpty(), "no targeting key: " + targetingKey);
      assertNull(stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL));
      assertNull(stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE));
      assertEquals(CLIENT_ID, stringValue(ctx, FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }

  @Test
  void aProviderThatFailsReadsAsHidden() {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::findCurrentAccountEmail).thenReturn(ACCOUNT_EMAIL);
      // The failing provider would have matched this very user: a failure must still hide.
      CapturingProvider provider = new CapturingProvider(true, "ana.garcia@example.com");

      assertFalse(withProvider(provider, () -> FiscalModel390Flag.isEnabled(CLIENT_ID)));
    }
  }

  private static boolean withProvider(FeatureProvider provider,
      java.util.function.BooleanSupplier evaluation) {
    FiscalModel390Flag.isEnabled(CLIENT_ID); // initialise the OpenFeature client
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    try {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, provider);
      return evaluation.getAsBoolean();
    } finally {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, new PropertiesFeatureProvider());
    }
  }

  private static String stringValue(EvaluationContext ctx, String key) {
    Value value = ctx.getValue(key);
    return value == null ? null : value.asString();
  }

  /**
   * A boolean provider that records every evaluation context it receives and answers {@code true}
   * only for {@code fiscal-model-390} when the lowercase {@code email} attribute equals
   * {@code enabledForEmail} — the shape of a Datadog per-user rule. Or throws on every evaluation.
   */
  private static final class CapturingProvider implements FeatureProvider {
    private final boolean fail;
    private final String enabledForEmail;
    private final List<EvaluationContext> contexts = new ArrayList<>();

    CapturingProvider(boolean fail, String enabledForEmail) {
      this.fail = fail;
      this.enabledForEmail = enabledForEmail;
    }

    @Override
    public Metadata getMetadata() {
      return () -> "fiscal-model-390-capturing-stub";
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue,
        EvaluationContext ctx) {
      if (fail) {
        throw new IllegalStateException("control plane unreachable");
      }
      if ("fiscal-model-390".equals(key)) {
        contexts.add(ctx);
      }
      Value email = ctx == null ? null
          : ctx.getValue(FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE);
      boolean match = "fiscal-model-390".equals(key) && email != null
          && enabledForEmail.equals(email.asString());
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

  @Test
  void failHiddenAnswerHasNoModelAndMarksThe390Unavailable() throws Exception {
    JSONObject answer = FiscalModel390Flag.failHiddenAnswer();
    assertEquals(1, answer.length());
    assertEquals("[\"390\"]", answer.getJSONArray(FiscalModel390Flag.UNAVAILABLE_KEY).toString());
  }
}
