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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 * The per-account flag readers {@link BankBounceSuggestionsFlag} ({@code bank-bounce-suggestions})
 * and {@link Psd2ImportSettingsFlag} ({@code psd2-statement-import-settings}): the
 * {@code ETGO_ACCOUNT} email of the request user reaches the provider as targeting key and as both
 * email attributes, a targeted account reads on and any other account off, and every way of not
 * knowing the account — no {@link OBContext}, no account behind the user, an account without email,
 * a failed lookup — reads off, never on. Both classes are independent copies of the same reader, so
 * every case runs on each of them.
 *
 * <p>End to end through the real {@link GoFeatureFlags} and, where the account resolution is what
 * is under test, the real {@link GoAccountResolver} over a mocked {@link OBContext} and
 * {@link OBDal}. {@code Openbravo.properties} is mocked empty and a JVM {@code -D} override of the
 * flags is lifted for each case (and put back after it), so neither a developer's local override nor
 * a forced run can leak into the "unset" assertion (same isolation as {@code InvoiceInboxFlagTest}).
 *
 * @covers com.etendoerp.go.schemaforge.BankBounceSuggestionsFlag
 * @covers com.etendoerp.go.schemaforge.Psd2ImportSettingsFlag
 */
class AccountTargetedFlagTest {

  /** {@code GoFeatureFlags.OPENFEATURE_DOMAIN}: package-private there, so named here. */
  private static final String OPENFEATURE_DOMAIN = "etendo-go";
  private static final String ACCOUNT_EMAIL = "Ana.Garcia@Example.com";
  private static final String ACCOUNT_EMAIL_LOWERCASE = "ana.garcia@example.com";

  /**
   * One flag reader under test.
   *
   * @param key           the flag key the reader is expected to ask for
   * @param registeredKey the key as registered in {@link GoFeatureFlags}
   * @param reader        the reader's {@code isEnabled()}
   */
  private record Flag(String key, String registeredKey, Supplier<Boolean> reader) {
    String property() {
      return "etendo.go.flags." + key;
    }

    @Override
    public String toString() {
      return key;
    }
  }

  static Stream<Flag> flags() {
    return Stream.of(
        new Flag("bank-bounce-suggestions", GoFeatureFlags.FLAG_BANK_BOUNCE_SUGGESTIONS,
            BankBounceSuggestionsFlag::isEnabled),
        new Flag("psd2-statement-import-settings",
            GoFeatureFlags.FLAG_PSD2_STATEMENT_IMPORT_SETTINGS, Psd2ImportSettingsFlag::isEnabled));
  }

  static Stream<Arguments> flagsAndNonBooleanValues() {
    return flags().flatMap(flag -> Stream.of("maybe", "on", "enabled", "2")
        .map(value -> Arguments.of(flag, value)));
  }

  private MockedStatic<OBPropertiesProvider> propertiesMock;
  /** The JVM's own value of each flag property (a {@code -D} override), restored after a case. */
  private final Map<String, String> jvmOverrides = new HashMap<>();

  /**
   * Isolates each case from the machine's configuration: clears a {@code -D} override of the
   * flags (kept to restore it) and serves an empty {@code Openbravo.properties}.
   */
  @BeforeEach
  void isolateFromOpenbravoProperties() {
    // A -D override outranks every other source, so "unset" means unset here too; it is put back
    // afterwards so the rest of the JVM still runs with it.
    flags().forEach(flag -> jvmOverrides.put(flag.property(),
        System.clearProperty(flag.property())));
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesMock = mockStatic(OBPropertiesProvider.class);
    propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
  }

  /**
   * Restores the JVM's own {@code -D} override of the flags, if they had one, and closes the
   * properties mock.
   */
  @AfterEach
  void clearOverride() {
    jvmOverrides.forEach((property, value) -> {
      if (value != null) {
        System.setProperty(property, value);
      } else {
        System.clearProperty(property);
      }
    });
    propertiesMock.close();
  }

  /** The flag key is the registered one. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void theFlagKeyIsTheRegisteredOne(Flag flag) {
    assertEquals(flag.key(), flag.registeredKey());
  }

  // ---------------------------------------------------------------- evaluation context

  /**
   * What a hosted provider receives: the account email as targeting key and {@code Email}, and
   * lowercased as {@code email} (the Datadog rule spelling). A per-account rule matches it.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void theAccountEmailReachesTheProviderAndATargetedAccountReadsOn(Flag flag) {
    CapturingProvider provider = new CapturingProvider(flag.key(), false, ACCOUNT_EMAIL_LOWERCASE);

    boolean enabled = withCurrentAccount(flag, ACCOUNT_EMAIL, provider);

    assertTrue(enabled);
    assertEquals(1, provider.contexts.size());
    EvaluationContext ctx = provider.contexts.get(0);
    assertEquals(ACCOUNT_EMAIL, ctx.getTargetingKey());
    assertEquals(ACCOUNT_EMAIL, stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL));
    assertEquals(ACCOUNT_EMAIL_LOWERCASE,
        stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE));
  }

  /** An account the rule does not target reads off. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void anAccountTheRuleDoesNotTargetReadsOff(Flag flag) {
    CapturingProvider provider = new CapturingProvider(flag.key(), false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withCurrentAccount(flag, "someone.else@example.com", provider));
    assertEquals(1, provider.contexts.size(), "it was evaluated, and answered off");
  }

  /** The account is resolved the way {@code PortalLinkPolicy} resolves it: the request user's. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void theAccountOfTheRequestUserIsResolvedFromTheContext(Flag flag) {
    CapturingProvider provider = new CapturingProvider(flag.key(), false, ACCOUNT_EMAIL_LOWERCASE);
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(ACCOUNT_EMAIL);

    boolean enabled = withResolvedAccount(flag, contextOfUser(ACCOUNT_EMAIL_LOWERCASE),
        () -> account, provider);

    assertTrue(enabled);
    assertEquals(ACCOUNT_EMAIL, provider.contexts.get(0).getTargetingKey());
  }

  // ---------------------------------------------------------------- no account means off

  /** No ob context reads off with no identity. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void noOBContextReadsOffWithNoIdentity(Flag flag) {
    CapturingProvider provider = new CapturingProvider(flag.key(), false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withResolvedAccount(flag, null, () -> fail("no lookup without a context"),
        provider));
    assertNoIdentity(provider);
  }

  /** No account behind the user reads off. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void noAccountBehindTheUserReadsOff(Flag flag) {
    CapturingProvider provider = new CapturingProvider(flag.key(), false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withResolvedAccount(flag, contextOfUser(ACCOUNT_EMAIL_LOWERCASE), () -> null,
        provider));
    assertNoIdentity(provider);
  }

  /** An account with a null email reads off. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void anAccountWithANullEmailReadsOff(Flag flag) {
    CapturingProvider provider = new CapturingProvider(flag.key(), false, ACCOUNT_EMAIL_LOWERCASE);
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(null);

    assertFalse(withResolvedAccount(flag, contextOfUser(ACCOUNT_EMAIL_LOWERCASE), () -> account,
        provider));
    assertNoIdentity(provider);
  }

  /** A failed account query reads off. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void aFailedAccountQueryReadsOff(Flag flag) {
    CapturingProvider provider = new CapturingProvider(flag.key(), false, ACCOUNT_EMAIL_LOWERCASE);

    boolean enabled = assertDoesNotThrow(() -> withResolvedAccount(flag,
        contextOfUser(ACCOUNT_EMAIL_LOWERCASE), () -> {
          throw new IllegalStateException("database unavailable");
        }, provider));

    assertFalse(enabled);
    assertNoIdentity(provider);
  }

  /** A failure reading the user reads off. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void aFailureReadingTheUserReadsOff(Flag flag) {
    CapturingProvider provider = new CapturingProvider(flag.key(), false, ACCOUNT_EMAIL_LOWERCASE);
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenThrow(new IllegalStateException("session gone"));

    boolean enabled = assertDoesNotThrow(() -> withResolvedAccount(flag, context,
        () -> fail("no lookup without a user"), provider));

    assertFalse(enabled);
    assertNoIdentity(provider);
  }

  /** No user in the context reads off. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void noUserInTheContextReadsOff(Flag flag) {
    CapturingProvider provider = new CapturingProvider(flag.key(), false, ACCOUNT_EMAIL_LOWERCASE);

    assertFalse(withResolvedAccount(flag, mock(OBContext.class),
        () -> fail("no lookup without a user"), provider));
    assertNoIdentity(provider);
  }

  // ---------------------------------------------------------------- fail closed

  /** An unset flag reads off. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void anUnsetFlagReadsOff(Flag flag) {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(ACCOUNT_EMAIL);

      assertFalse(flag.reader().get());
    }
  }

  /** A value that is not a boolean reads off. */
  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("flagsAndNonBooleanValues")
  void aValueThatIsNotABooleanReadsOff(Flag flag, String value) {
    System.setProperty(flag.property(), value);
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(ACCOUNT_EMAIL);

      assertFalse(flag.reader().get(), value);
    }
  }

  /** A provider that fails reads off and does not throw. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("flags")
  void aProviderThatFailsReadsOffAndDoesNotThrow(Flag flag) {
    // The failing provider would have matched this very account: a failure must still read off.
    CapturingProvider provider = new CapturingProvider(flag.key(), true, ACCOUNT_EMAIL_LOWERCASE);

    boolean enabled = assertDoesNotThrow(() -> withCurrentAccount(flag, ACCOUNT_EMAIL, provider));

    assertFalse(enabled);
  }

  // ---------------------------------------------------------------- helpers

  /** Evaluates with {@link GoAccountResolver#currentAccountEmail()} stubbed to {@code email}. */
  private static boolean withCurrentAccount(Flag flag, String email,
      FeatureProvider provider) {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn(email);
      return withProvider(flag, provider, flag.reader());
    }
  }

  /**
   * Evaluates through the real {@link GoAccountResolver}: {@code context} is the current
   * {@link OBContext} ({@code null} = none), and the active-account query answers what
   * {@code account} supplies (or throws what it throws).
   */
  @SuppressWarnings("unchecked")
  private static boolean withResolvedAccount(Flag flag, OBContext context,
      Supplier<Account> account, FeatureProvider provider) {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(context);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      OBQuery<Account> query = mock(OBQuery.class);
      when(dal.createQuery(eq(Account.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenAnswer(invocation -> account.get());
      return withProvider(flag, provider, flag.reader());
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
   * @param flag       the flag under test
   * @param provider   the provider to install
   * @param evaluation what to evaluate
   * @param <T>        the result type
   * @return the evaluation's result
   */
  private static <T> T withProvider(Flag flag, FeatureProvider provider,
      Supplier<T> evaluation) {
    // Initialise the OpenFeature client without resolving any account.
    GoFeatureFlags.isEnabled(flag.key(), FeatureFlagContext.environment());
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
   * A boolean provider that records every evaluation context it receives for {@code flagKey}
   * and answers {@code true} only when the lowercase {@code email}
   * attribute equals {@code enabledForEmail} — a per-account rule. Or throws on every evaluation.
   */
  private static final class CapturingProvider implements FeatureProvider {
    private final String flagKey;
    private final boolean fail;
    private final String enabledForEmail;
    private final List<EvaluationContext> contexts = new ArrayList<>();

    /**
     * A capturing provider.
     *
     * @param flagKey         the flag whose evaluations are recorded
     * @param fail            whether every evaluation throws
     * @param enabledForEmail the lowercase email the flag answers {@code true} for
     */
    CapturingProvider(String flagKey, boolean fail, String enabledForEmail) {
      this.flagKey = flagKey;
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
      return () -> flagKey + "-capturing-stub";
    }

    /**
     * Records the context of a {@code flagKey} evaluation and answers {@code true}
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
      if (flagKey.equals(key)) {
        contexts.add(ctx);
      }
      String email = stringValue(ctx, FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE);
      boolean match = flagKey.equals(key) && enabledForEmail.equals(email);
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
