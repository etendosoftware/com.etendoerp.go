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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;

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
 * The {@code invoice-inbox} gate: what evaluation context reaches the provider (a per-user
 * Datadog rule depends on it), how the current account and tenant are resolved, and that every
 * failure of the flag layer reads as "off" without throwing.
 *
 * <p>{@code Openbravo.properties} is mocked empty so a developer's local override cannot leak into
 * the "unset" assertion (same isolation as {@code FiscalModel390FlagTest}). The environment arm
 * ({@code ETGO_FLAG_INVOICE_INBOX}) shares the property arm's reader and is not set here: a JVM
 * cannot change its own environment.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxFlag
 */
class InvoiceInboxFlagTest {

  private static final String FLAG_KEY = "invoice-inbox";
  private static final String FLAG_PROPERTY = "etendo.go.flags.invoice-inbox";
  /** {@code GoFeatureFlags.OPENFEATURE_DOMAIN}: package-private there, so named here. */
  private static final String OPENFEATURE_DOMAIN = "etendo-go";
  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String ACCOUNT_EMAIL = "Ana.Garcia@Example.com";
  private static final String ACCOUNT_EMAIL_LOWERCASE = "ana.garcia@example.com";

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

  // ---------------------------------------------------------------- evaluation context

  /**
   * End to end through the real {@link GoFeatureFlags}: what a hosted provider actually receives.
   * A Datadog rule matches the lowercase {@code email} attribute by exact name and value, so it
   * must arrive lowercased, next to the targeting key, {@code Email} and {@code clientId}.
   */
  @Test
  void theAccountEmailAndTheTenantReachTheProvider() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    boolean enabled = withProvider(provider,
        () -> InvoiceInboxFlag.isEnabled(ACCOUNT_EMAIL, CLIENT_ID));

    assertTrue(enabled, "a per-user rule on the lowercase email matches");
    assertEquals(1, provider.contexts.size());
    EvaluationContext ctx = provider.contexts.get(0);
    assertEquals(ACCOUNT_EMAIL, ctx.getTargetingKey());
    assertEquals(ACCOUNT_EMAIL, stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL));
    assertEquals(ACCOUNT_EMAIL_LOWERCASE,
        stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE));
    assertEquals(CLIENT_ID, stringValue(ctx, FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
  }

  @Test
  void anAccountTheRuleDoesNotTargetReadsOff() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    boolean enabled = withProvider(provider,
        () -> InvoiceInboxFlag.isEnabled("someone.else@example.com", CLIENT_ID));

    assertFalse(enabled);
    assertEquals(1, provider.contexts.size());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   " })
  void aBlankEmailPublishesOnlyTheTenantAndReadsOff(String blankEmail) {
    // The provider would match this very user were the email sent: blank must still read off.
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    boolean enabled = withProvider(provider,
        () -> InvoiceInboxFlag.isEnabled(blankEmail, CLIENT_ID));

    assertFalse(enabled, "an email rule cannot match an account-less context");
    assertEquals(1, provider.contexts.size());
    EvaluationContext ctx = provider.contexts.get(0);
    String targetingKey = ctx.getTargetingKey();
    assertTrue(targetingKey == null || targetingKey.isEmpty(), "no targeting key: " + targetingKey);
    assertNull(stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL));
    assertNull(stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE));
    assertEquals(CLIENT_ID, stringValue(ctx, FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
  }

  // ---------------------------------------------------------------- current account and tenant

  @Test
  void noArgumentIsEnabledPassesTheCurrentAccountAndTenantThrough() {
    List<FeatureFlagContext> contexts = new ArrayList<>();
    // Built before any static stubbing opens: Mockito forbids stubbing a mock mid-stubbing.
    OBContext context = contextOfClient(CLIENT_ID);
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(ACCOUNT_EMAIL);
      obContext.when(OBContext::getOBContext).thenReturn(context);
      flags.when(() -> GoFeatureFlags.isEnabled(org.mockito.ArgumentMatchers.eq(FLAG_KEY),
          org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            contexts.add(invocation.getArgument(1));
            return true;
          });

      assertTrue(InvoiceInboxFlag.isEnabled());

      assertEquals(1, contexts.size());
      assertEquals(ACCOUNT_EMAIL, contexts.get(0).getTargetingKey());
      assertEquals(CLIENT_ID,
          contexts.get(0).getAttributes().get(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }

  @Test
  void noOBContextMeansNoTenantAndNoException() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(ACCOUNT_EMAIL);
      obContext.when(OBContext::getOBContext).thenReturn(null);

      boolean enabled = withProvider(provider, InvoiceInboxFlag::isEnabled);

      assertTrue(enabled, "the account alone still targets");
      assertNull(stringValue(provider.contexts.get(0), FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }

  @Test
  void noCurrentClientMeansNoTenantAndNoException() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(null);
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(ACCOUNT_EMAIL);
      obContext.when(OBContext::getOBContext).thenReturn(context);

      boolean enabled = withProvider(provider, InvoiceInboxFlag::isEnabled);

      assertTrue(enabled);
      assertNull(stringValue(provider.contexts.get(0), FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }

  @Test
  void aFailureReadingTheClientIsSwallowedAndTheTenantIsOmitted() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenThrow(new IllegalStateException("no session"));
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(ACCOUNT_EMAIL);
      obContext.when(OBContext::getOBContext).thenReturn(context);

      boolean enabled = assertDoesNotThrow(() -> withProvider(provider, InvoiceInboxFlag::isEnabled));

      assertTrue(enabled, "targeting enrichment must never fail the request");
      assertNull(stringValue(provider.contexts.get(0), FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }

  // ---------------------------------------------------------------- isEnabledFor(email)

  @Test
  void isEnabledForPublishesTheEmailAndTheCurrentClientAndEqualsTheExplicitForm() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);
    OBContext context = contextOfClient(CLIENT_ID);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(context);

      boolean viaCurrent = withProvider(provider, () -> InvoiceInboxFlag.isEnabledFor(ACCOUNT_EMAIL));
      boolean explicit = withProvider(provider,
          () -> InvoiceInboxFlag.isEnabled(ACCOUNT_EMAIL, CLIENT_ID));

      assertTrue(viaCurrent);
      assertEquals(explicit, viaCurrent);
      EvaluationContext ctx = provider.contexts.get(0);
      assertEquals(ACCOUNT_EMAIL, ctx.getTargetingKey());
      assertEquals(CLIENT_ID, stringValue(ctx, FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
      assertEquals(provider.contexts.get(0).getTargetingKey(),
          provider.contexts.get(1).getTargetingKey());
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   " })
  void isEnabledForABlankEmailIsUntargetedAndReadsOff(String blankEmail) {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);
    OBContext context = contextOfClient(CLIENT_ID);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(context);

      boolean enabled = withProvider(provider, () -> InvoiceInboxFlag.isEnabledFor(blankEmail));

      assertFalse(enabled);
      EvaluationContext ctx = provider.contexts.get(0);
      assertNull(stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL));
      assertEquals(CLIENT_ID, stringValue(ctx, FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }

  @Test
  void isEnabledForWithNoOBContextOmitsTheTenantWithoutThrowing() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(null);

      boolean enabled = assertDoesNotThrow(
          () -> withProvider(provider, () -> InvoiceInboxFlag.isEnabledFor(ACCOUNT_EMAIL)));

      assertTrue(enabled);
      assertNull(stringValue(provider.contexts.get(0), FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }

  @Test
  void isEnabledForAnUnconfiguredFlagIsOff() {
    OBContext context = contextOfClient(CLIENT_ID);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(context);

      assertFalse(InvoiceInboxFlag.isEnabledFor(ACCOUNT_EMAIL));
      assertFalse(InvoiceInboxFlag.isEnabledFor(null));
    }
  }

  // ---------------------------------------------------------------- fail closed

  @Test
  void anUnsetFlagReadsAsOff() {
    assertFalse(InvoiceInboxFlag.isEnabled(ACCOUNT_EMAIL, CLIENT_ID));
    assertFalse(InvoiceInboxFlag.isEnabled(null, null));
  }

  @ParameterizedTest
  @ValueSource(strings = { "maybe", "on", "enabled", "2" })
  void aValueThatIsNotABooleanReadsAsOff(String value) {
    System.setProperty(FLAG_PROPERTY, value);

    assertFalse(InvoiceInboxFlag.isEnabled(ACCOUNT_EMAIL, CLIENT_ID), value);
  }

  @Test
  void aProviderThatFailsReadsAsOffAndDoesNotThrow() {
    // The failing provider would have matched this very user: a failure must still read off.
    CapturingProvider provider = new CapturingProvider(true, ACCOUNT_EMAIL_LOWERCASE);

    boolean enabled = assertDoesNotThrow(() -> withProvider(provider,
        () -> InvoiceInboxFlag.isEnabled(ACCOUNT_EMAIL, CLIENT_ID)));

    assertFalse(enabled);
  }

  // ---------------------------------------------------------------- local property arm

  @Test
  void anExplicitTrueTurnsTheLocalArmOn() {
    System.setProperty(FLAG_PROPERTY, "true");

    assertTrue(InvoiceInboxFlag.isEnabled(ACCOUNT_EMAIL, CLIENT_ID));
    assertTrue(InvoiceInboxFlag.isEnabled(null, null), "the local arm is not per-user");
  }

  @Test
  void anExplicitFalseKeepsTheLocalArmOff() {
    System.setProperty(FLAG_PROPERTY, "false");

    assertFalse(InvoiceInboxFlag.isEnabled(ACCOUNT_EMAIL, CLIENT_ID));
  }

  // ---------------------------------------------------------------- helpers

  private static OBContext contextOfClient(String clientId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(client);
    return context;
  }

  private static <T> T withProvider(FeatureProvider provider,
      java.util.function.Supplier<T> evaluation) {
    InvoiceInboxFlag.isEnabled(null, null); // initialise the OpenFeature client
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    try {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, provider);
      return evaluation.get();
    } finally {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, new PropertiesFeatureProvider());
    }
  }

  private static String stringValue(EvaluationContext ctx, String key) {
    Value value = ctx.getValue(key);
    return value == null ? null : value.asString();
  }

  /**
   * A boolean provider that records every evaluation context it receives for {@code invoice-inbox}
   * and answers {@code true} only when the lowercase {@code email} attribute equals
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
      return () -> "invoice-inbox-capturing-stub";
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue,
        EvaluationContext ctx) {
      if (fail) {
        throw new IllegalStateException("control plane unreachable");
      }
      if (FLAG_KEY.equals(key)) {
        contexts.add(ctx);
      }
      Value email = ctx == null ? null
          : ctx.getValue(FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE);
      boolean match = FLAG_KEY.equals(key) && email != null
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
}
