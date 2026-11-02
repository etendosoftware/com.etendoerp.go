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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.featureflags.PropertiesFeatureProvider;
import com.etendoerp.go.schemaforge.data.Account;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;

/**
 * The {@code bank-bounce-suggestions} gate: the {@code ETGO_ACCOUNT} email of the request user
 * reaches the provider as targeting key and as both email attributes, a targeted account reads on
 * and any other account off, and every way of not knowing the account — no {@link OBContext}, no
 * account behind the user, an account without email, a failed lookup — reads off, never on.
 *
 * <p>End to end through the real {@link GoFeatureFlags} and, where the account resolution is what
 * is under test, the real {@link GoAccountResolver} over a mocked {@link OBContext} and
 * {@link OBDal}. {@code Openbravo.properties} is mocked empty so a developer's local override cannot
 * leak into the "unset" assertion (same isolation as {@code InvoiceInboxFlagTest}).
 *
 * @covers com.etendoerp.go.schemaforge.BankBounceSuggestionsFlag
 */
class BankBounceSuggestionsFlagTest {

  private static final String FLAG_KEY = "bank-bounce-suggestions";
  private static final String FLAG_PROPERTY = "etendo.go.flags.bank-bounce-suggestions";
  /** {@code GoFeatureFlags.OPENFEATURE_DOMAIN}: package-private there, so named here. */
  private static final String OPENFEATURE_DOMAIN = "etendo-go";
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

  @Test
  void theFlagKeyIsTheRegisteredOne() {
    assertEquals(FLAG_KEY, GoFeatureFlags.FLAG_BANK_BOUNCE_SUGGESTIONS);
  }

  // ---------------------------------------------------------------- evaluation context

  /**
   * What a hosted provider receives: the account email as targeting key and {@code Email}, and
   * lowercased as {@code email} (the Datadog rule spelling). A per-account rule matches it.
   */
  @Test
  void theAccountEmailReachesTheProviderAndATargetedAccountReadsOn() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    boolean enabled = withCurrentAccount(ACCOUNT_EMAIL, provider);

    assertTrue(enabled);
    assertEquals(1, provider.contexts.size());
    EvaluationContext ctx = provider.contexts.get(0);
    assertEquals(ACCOUNT_EMAIL, ctx.getTargetingKey());
    assertEquals(ACCOUNT_EMAIL, stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL));
    assertEquals(ACCOUNT_EMAIL_LOWERCASE,
        stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE));
  }

  @Test
  void anAccountTheRuleDoesNotTargetReadsOff() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withCurrentAccount("someone.else@example.com", provider));
    assertEquals(1, provider.contexts.size(), "it was evaluated, and answered off");
  }

  /** The account is resolved the way {@code PortalLinkPolicy} resolves it: the request user's. */
  @Test
  void theAccountOfTheRequestUserIsResolvedFromTheContext() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(ACCOUNT_EMAIL);

    boolean enabled = withResolvedAccount(contextOfUser(ACCOUNT_EMAIL_LOWERCASE), () -> account,
        provider);

    assertTrue(enabled);
    assertEquals(ACCOUNT_EMAIL, provider.contexts.get(0).getTargetingKey());
  }

  // ---------------------------------------------------------------- no account means off

  @Test
  void noOBContextReadsOffWithNoIdentity() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withResolvedAccount(null, () -> fail("no lookup without a context"), provider));
    assertNoIdentity(provider);
  }

  @Test
  void noAccountBehindTheUserReadsOff() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withResolvedAccount(contextOfUser(ACCOUNT_EMAIL_LOWERCASE), () -> null, provider));
    assertNoIdentity(provider);
  }

  @Test
  void anAccountWithANullEmailReadsOff() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(null);

    assertFalse(withResolvedAccount(contextOfUser(ACCOUNT_EMAIL_LOWERCASE), () -> account,
        provider));
    assertNoIdentity(provider);
  }

  @Test
  void aFailedAccountQueryReadsOff() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    boolean enabled = assertDoesNotThrow(() -> withResolvedAccount(
        contextOfUser(ACCOUNT_EMAIL_LOWERCASE), () -> {
          throw new IllegalStateException("database unavailable");
        }, provider));

    assertFalse(enabled);
    assertNoIdentity(provider);
  }

  @Test
  void aFailureReadingTheUserReadsOff() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenThrow(new IllegalStateException("session gone"));

    boolean enabled = assertDoesNotThrow(() -> withResolvedAccount(context,
        () -> fail("no lookup without a user"), provider));

    assertFalse(enabled);
    assertNoIdentity(provider);
  }

  @Test
  void noUserInTheContextReadsOff() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withResolvedAccount(mock(OBContext.class), () -> fail("no lookup without a user"),
        provider));
    assertNoIdentity(provider);
  }

  // ---------------------------------------------------------------- fail closed

  @Test
  void anUnsetFlagReadsOff() {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(ACCOUNT_EMAIL);

      assertFalse(BankBounceSuggestionsFlag.isEnabled());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "maybe", "on", "enabled", "2" })
  void aValueThatIsNotABooleanReadsOff(String value) {
    System.setProperty(FLAG_PROPERTY, value);
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(ACCOUNT_EMAIL);

      assertFalse(BankBounceSuggestionsFlag.isEnabled(), value);
    }
  }

  @Test
  void aProviderThatFailsReadsOffAndDoesNotThrow() {
    // The failing provider would have matched this very account: a failure must still read off.
    CapturingProvider provider = new CapturingProvider(true, ACCOUNT_EMAIL_LOWERCASE);

    boolean enabled = assertDoesNotThrow(() -> withCurrentAccount(ACCOUNT_EMAIL, provider));

    assertFalse(enabled);
  }

  // ---------------------------------------------------------------- helpers

  /** Evaluates with {@link GoAccountResolver#currentAccountEmail()} stubbed to {@code email}. */
  private static boolean withCurrentAccount(String email, FeatureProvider provider) {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(email);
      return withProvider(provider, BankBounceSuggestionsFlag::isEnabled);
    }
  }

  /**
   * Evaluates through the real {@link GoAccountResolver}: {@code context} is the current
   * {@link OBContext} ({@code null} = none), and the active-account query answers what
   * {@code account} supplies (or throws what it throws).
   */
  @SuppressWarnings("unchecked")
  private static boolean withResolvedAccount(OBContext context, Supplier<Account> account,
      FeatureProvider provider) {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(context);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      OBQuery<Account> query = mock(OBQuery.class);
      when(dal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenAnswer(invocation -> account.get());
      return withProvider(provider, BankBounceSuggestionsFlag::isEnabled);
    }
  }

  private static OBContext contextOfUser(String username) {
    User user = mock(User.class);
    when(user.getUsername()).thenReturn(username);
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(user);
    return context;
  }

  private static <T> T withProvider(FeatureProvider provider, Supplier<T> evaluation) {
    // Initialise the OpenFeature client without resolving any account.
    GoFeatureFlags.isEnabled(FLAG_KEY, FeatureFlagContext.environment());
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    try {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, provider);
      return evaluation.get();
    } finally {
      api.setProviderAndWait(OPENFEATURE_DOMAIN, new PropertiesFeatureProvider());
    }
  }

  /** The flag was asked with no account at all: no targeting key, no email attribute. */
  private static void assertNoIdentity(CapturingProvider provider) {
    for (EvaluationContext ctx : provider.contexts) {
      assertNull(ctx.getTargetingKey());
      assertNull(stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL));
      assertNull(stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE));
    }
  }

  private static <T> T fail(String message) {
    throw new AssertionError(message);
  }

  private static String stringValue(EvaluationContext ctx, String key) {
    Value value = ctx == null ? null : ctx.getValue(key);
    return value == null ? null : value.asString();
  }

  /**
   * A boolean provider that records every evaluation context it receives for
   * {@code bank-bounce-suggestions} and answers {@code true} only when the lowercase {@code email}
   * attribute equals {@code enabledForEmail} — a per-account rule. Or throws on every evaluation.
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
      return () -> "bank-bounce-suggestions-capturing-stub";
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
      String email = stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE);
      boolean match = FLAG_KEY.equals(key) && enabledForEmail.equals(email);
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
