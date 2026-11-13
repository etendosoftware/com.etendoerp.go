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
 * The {@code psd2-statement-import-settings} gate: the {@code ETGO_ACCOUNT} email of the request user
 * reaches the provider as targeting key and as both email attributes, a targeted account reads on
 * and any other account off, and every way of not knowing the account — no {@link OBContext}, no
 * account behind the user, an account without email, a failed lookup — reads off, never on.
 *
 * <p>End to end through the real {@link GoFeatureFlags} and, where the account resolution is what
 * is under test, the real {@link GoAccountResolver} over a mocked {@link OBContext} and
 * {@link OBDal}. {@code Openbravo.properties} is mocked empty and a JVM {@code -D} override of the
 * flag is lifted for each case (and put back after it), so neither a developer's local override nor
 * a forced run can leak into the "unset" assertion (same isolation as {@code InvoiceInboxFlagTest}).
 *
 * @covers com.etendoerp.go.schemaforge.Psd2ImportSettingsFlag
 */
class Psd2ImportSettingsFlagTest {

  private static final String FLAG_KEY = "psd2-statement-import-settings";
  private static final String FLAG_PROPERTY = "etendo.go.flags.psd2-statement-import-settings";
  /** {@code GoFeatureFlags.OPENFEATURE_DOMAIN}: package-private there, so named here. */
  private static final String OPENFEATURE_DOMAIN = "etendo-go";
  private static final String ACCOUNT_EMAIL = "Ana.Garcia@Example.com";
  private static final String ACCOUNT_EMAIL_LOWERCASE = "ana.garcia@example.com";

  private MockedStatic<OBPropertiesProvider> propertiesMock;
  /** The JVM's own value of the flag property (a {@code -D} override), restored after each case. */
  private String jvmOverride;

  /**
   * Isolates each case from the machine's configuration: clears a {@code -D} override of the
   * flag (kept to restore it) and serves an empty {@code Openbravo.properties}.
   */
  @BeforeEach
  void isolateFromOpenbravoProperties() {
    // A -D override outranks every other source, so "unset" means unset here too; it is put back
    // afterwards so the rest of the JVM still runs with it.
    jvmOverride = System.clearProperty(FLAG_PROPERTY);
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesMock = mockStatic(OBPropertiesProvider.class);
    propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
  }

  /**
   * Restores the JVM's own {@code -D} override of the flag, if it had one, and closes the
   * properties mock.
   */
  @AfterEach
  void clearOverride() {
    if (jvmOverride != null) {
      System.setProperty(FLAG_PROPERTY, jvmOverride);
    } else {
      System.clearProperty(FLAG_PROPERTY);
    }
    propertiesMock.close();
  }

  /** The flag key is the registered one. */
  @Test
  void theFlagKeyIsTheRegisteredOne() {
    assertEquals(FLAG_KEY, GoFeatureFlags.FLAG_PSD2_STATEMENT_IMPORT_SETTINGS);
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

  /** An account the rule does not target reads off. */
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

  /** No ob context reads off with no identity. */
  @Test
  void noOBContextReadsOffWithNoIdentity() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withResolvedAccount(null, () -> fail("no lookup without a context"), provider));
    assertNoIdentity(provider);
  }

  /** No account behind the user reads off. */
  @Test
  void noAccountBehindTheUserReadsOff() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withResolvedAccount(contextOfUser(ACCOUNT_EMAIL_LOWERCASE), () -> null, provider));
    assertNoIdentity(provider);
  }

  /** An account with a null email reads off. */
  @Test
  void anAccountWithANullEmailReadsOff() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(null);

    assertFalse(withResolvedAccount(contextOfUser(ACCOUNT_EMAIL_LOWERCASE), () -> account,
        provider));
    assertNoIdentity(provider);
  }

  /** A failed account query reads off. */
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

  /** A failure reading the user reads off. */
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

  /** No user in the context reads off. */
  @Test
  void noUserInTheContextReadsOff() {
    CapturingProvider provider = new CapturingProvider(false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withResolvedAccount(mock(OBContext.class), () -> fail("no lookup without a user"),
        provider));
    assertNoIdentity(provider);
  }

  // ---------------------------------------------------------------- fail closed

  /** An unset flag reads off. */
  @Test
  void anUnsetFlagReadsOff() {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(ACCOUNT_EMAIL);

      assertFalse(Psd2ImportSettingsFlag.isEnabled());
    }
  }

  /** A value that is not a boolean reads off. */
  @ParameterizedTest
  @ValueSource(strings = { "maybe", "on", "enabled", "2" })
  void aValueThatIsNotABooleanReadsOff(String value) {
    System.setProperty(FLAG_PROPERTY, value);
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(ACCOUNT_EMAIL);

      assertFalse(Psd2ImportSettingsFlag.isEnabled(), value);
    }
  }

  /** A provider that fails reads off and does not throw. */
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
      return withProvider(provider, Psd2ImportSettingsFlag::isEnabled);
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
      return withProvider(provider, Psd2ImportSettingsFlag::isEnabled);
    }
  }

  /**
   * An {@code OBContext} mock whose user has the given username.
   *
   * @param username the username
   * @return the context
   */
  private static OBContext contextOfUser(String username) {
    User user = mock(User.class);
    when(user.getUsername()).thenReturn(username);
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(user);
    return context;
  }

  /**
   * Runs an evaluation with {@code provider} installed as the Etendo GO OpenFeature provider,
   * then puts the properties provider back.
   *
   * @param provider   the provider to install
   * @param evaluation what to evaluate
   * @param <T>        the result type
   * @return the evaluation's result
   */
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

  /**
   * Throws an {@link AssertionError}, typed so it can stand for any supplier's result.
   *
   * @param message the failure message
   * @param <T>     the expected type
   * @return never
   */
  private static <T> T fail(String message) {
    throw new AssertionError(message);
  }

  /**
   * A string attribute of an evaluation context.
   *
   * @param ctx the context, possibly {@code null}
   * @param key the attribute
   * @return its value, or {@code null}
   */
  private static String stringValue(EvaluationContext ctx, String key) {
    Value value = ctx == null ? null : ctx.getValue(key);
    return value == null ? null : value.asString();
  }

  /**
   * A boolean provider that records every evaluation context it receives for
   * {@code psd2-statement-import-settings} and answers {@code true} only when the lowercase {@code email}
   * attribute equals {@code enabledForEmail} — a per-account rule. Or throws on every evaluation.
   */
  private static final class CapturingProvider implements FeatureProvider {
    private final boolean fail;
    private final String enabledForEmail;
    private final List<EvaluationContext> contexts = new ArrayList<>();

    /**
     * A capturing provider.
     *
     * @param fail            whether every evaluation throws
     * @param enabledForEmail the lowercase email the flag answers {@code true} for
     */
    CapturingProvider(boolean fail, String enabledForEmail) {
      this.fail = fail;
      this.enabledForEmail = enabledForEmail;
    }

    /**
     * The provider's name.
     *
     * @return its metadata
     */
    @Override
    public Metadata getMetadata() {
      return () -> "psd2-statement-import-settings-capturing-stub";
    }

    /**
     * Records the context of a {@code psd2-statement-import-settings} evaluation and answers {@code true}
     * only for the targeted email; throws when built to fail.
     *
     * @param key          the flag key
     * @param defaultValue the caller's default
     * @param ctx          the evaluation context
     * @return the evaluation
     */
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

    /**
     * Not used: answers the default.
     *
     * @param key          the flag key
     * @param defaultValue the default
     * @param ctx          the evaluation context
     * @return the default
     */
    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<String>builder().value(defaultValue).build();
    }

    /**
     * Not used: answers the default.
     *
     * @param key          the flag key
     * @param defaultValue the default
     * @param ctx          the evaluation context
     * @return the default
     */
    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<Integer>builder().value(defaultValue).build();
    }

    /**
     * Not used: answers the default.
     *
     * @param key          the flag key
     * @param defaultValue the default
     * @param ctx          the evaluation context
     * @return the default
     */
    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<Double>builder().value(defaultValue).build();
    }

    /**
     * Not used: answers the default.
     *
     * @param key          the flag key
     * @param defaultValue the default
     * @param ctx          the evaluation context
     * @return the default
     */
    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue,
        EvaluationContext ctx) {
      return ProviderEvaluation.<Value>builder().value(defaultValue).build();
    }
  }
}
