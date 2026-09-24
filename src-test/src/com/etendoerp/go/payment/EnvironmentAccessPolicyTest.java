/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"); you may not use this file except in compliance with
 * the License.
 * *************************************************************************
 */

package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;

import com.etendoerp.go.payment.EnvironmentAccessPolicy.Configuration;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Environment;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.SubscriptionStatus;

public class EnvironmentAccessPolicyTest {

  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
  private static final Configuration DEFAULTS = new Configuration(15, 15);
  private final EnvironmentAccessPolicy policy = new EnvironmentAccessPolicy();

  @Test
  public void demoIsAvailableUntilConfiguredTrialBoundary() {
    Environment demo = Environment.demo(START);

    assertEquals(Decision.ALLOWED, policy.evaluate(demo, true, SubscriptionStatus.NONE,
        START.plusSeconds(15 * 24 * 60 * 60L - 1), DEFAULTS));
    assertEquals(Decision.DEMO_TRIAL_EXPIRED, policy.evaluate(demo, true, SubscriptionStatus.NONE,
        START.plusSeconds(15 * 24 * 60 * 60L), DEFAULTS));
  }

  @Test
  public void trialLengthIsConfigurable() {
    Environment demo = Environment.demo(START);
    Configuration sevenDays = new Configuration(7, 15);

    assertEquals(Decision.DEMO_TRIAL_EXPIRED, policy.evaluate(demo, true, SubscriptionStatus.NONE,
        START.plusSeconds(7 * 24 * 60 * 60L), sevenDays));
  }

  @Test
  public void remainingTrialDaysIsCeiledForDisplayAndClampedAfterExpiry() {
    Environment demo = Environment.demo(START);

    assertEquals(15, policy.remainingTrialDays(demo, START, DEFAULTS));
    assertEquals(1, policy.remainingTrialDays(demo,
        START.plusSeconds(15 * 24 * 60 * 60L).minusNanos(1), DEFAULTS));
    assertEquals(1, policy.remainingTrialDays(demo,
        START.plusSeconds(14 * 24 * 60 * 60L + 1), DEFAULTS));
    assertEquals(0, policy.remainingTrialDays(demo,
        START.plusSeconds(15 * 24 * 60 * 60L), DEFAULTS));
  }

  @Test
  public void currentSubscriptionAllowsBothEnvironmentTypes() {
    assertEquals(Decision.ALLOWED, policy.evaluate(Environment.demo(START), true,
        SubscriptionStatus.CURRENT, START.plusSeconds(365 * 24 * 60 * 60L), DEFAULTS));
    assertEquals(Decision.ALLOWED, policy.evaluate(Environment.productive(), true,
        SubscriptionStatus.CURRENT, START, DEFAULTS));
    assertEquals(Decision.ALLOWED, policy.evaluate(Environment.productive(START), true,
        SubscriptionStatus.CURRENT, START.plusSeconds(15 * 24 * 60 * 60L), DEFAULTS));
  }

  @Test
  public void invitationMembershipIsIndependentFromSubscription() {
    assertEquals(Decision.ALLOWED, policy.evaluate(Environment.demo(START), true,
        SubscriptionStatus.NONE, START.plusSeconds(1), DEFAULTS));
    assertEquals(Decision.MEMBERSHIP_REQUIRED, policy.evaluate(Environment.demo(START), false,
        SubscriptionStatus.CURRENT, START.plusSeconds(1), DEFAULTS));
  }

  @Test
  public void productiveEnvironmentRequiresCurrentSubscription() {
    assertEquals(Decision.SUBSCRIPTION_REQUIRED, policy.evaluate(Environment.productive(), true,
        SubscriptionStatus.PAST_DUE, START, DEFAULTS));
    assertEquals(Decision.SUBSCRIPTION_REQUIRED, policy.evaluate(Environment.productive(), true,
        SubscriptionStatus.NONE, START, DEFAULTS));
  }

  @Test
  public void historicalProductiveEntitlementRemainsExplicitlyAllowed() {
    assertEquals(Decision.ALLOWED, policy.evaluate(Environment.productive(), true,
        SubscriptionStatus.LEGACY_ENTITLEMENT, START, DEFAULTS));
  }

  @Test
  public void pastDueProductiveEnvironmentIsAllowedOnlyDuringGrace() {
    Environment productive = Environment.productive(START);

    assertEquals(Decision.ALLOWED, policy.evaluate(productive, true, SubscriptionStatus.PAST_DUE,
        START.plusSeconds(15 * 24 * 60 * 60L - 1), DEFAULTS));
    assertEquals(Decision.SUBSCRIPTION_REQUIRED,
        policy.evaluate(productive, true, SubscriptionStatus.PAST_DUE,
            START.plusSeconds(15 * 24 * 60 * 60L), DEFAULTS));
  }

  @Test
  public void associatedDemoIsRevokedImmediatelyDespiteAnActiveTrialOrRenewalGrace() {
    Instant activeTrialTime = START.plus(Duration.ofDays(1));
    Environment associatedDemo = Environment.associatedDemo(START,
        activeTrialTime.plus(Duration.ofDays(2)));

    assertEquals(Decision.DEMO_TRIAL_EXPIRED, policy.evaluate(associatedDemo, true,
        SubscriptionStatus.CURRENT, activeTrialTime, DEFAULTS));
    assertEquals(Decision.DEMO_TRIAL_EXPIRED, policy.evaluate(associatedDemo, true,
        SubscriptionStatus.PAST_DUE, activeTrialTime, DEFAULTS));
    assertEquals(Decision.ALLOWED, policy.evaluate(Environment.demo(START), true,
        SubscriptionStatus.NONE, activeTrialTime, DEFAULTS));
    assertEquals(Decision.ALLOWED, policy.evaluate(Environment.productive(), true,
        SubscriptionStatus.CURRENT, activeTrialTime, DEFAULTS));
  }

  @Test
  public void storedDemoAssociationFlowsIntoPolicyAndRevokesAccessBeforeTrialExpiry() {
    Instant now = Instant.now();
    Map<String, Preference> stored = new HashMap<>();
    stored.put(key(TenantEnvironmentLifecycleService.ENVIRONMENT_TYPE_ATTRIBUTE, "demo-client"),
        preference(TenantEnvironmentLifecycleService.TYPE_DEMO));
    stored.put(key(TenantEnvironmentLifecycleService.DEMO_TRIAL_STARTED_ATTRIBUTE, "demo-client"),
        preference(now.minus(Duration.ofHours(1)).toString()));
    stored.put(key(TenantEnvironmentLifecycleService.ASSOCIATED_PRODUCTIVE_ATTRIBUTE, "demo-client"),
        preference("productive-client"));
    stored.put(key(TenantEnvironmentLifecycleService.ENVIRONMENT_TYPE_ATTRIBUTE, "productive-client"),
        preference(TenantEnvironmentLifecycleService.TYPE_PRODUCTIVE));
    stored.put(key(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE,
        "productive-client"), preference(SubscriptionStatus.CURRENT.name()));
    stored.put(key(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
        "productive-client"), preference(now.plus(Duration.ofDays(30)).toString()));
    OBQuery<Preference> query = mock(OBQuery.class);
    AtomicReference<String> attribute = new AtomicReference<>();
    AtomicReference<String> clientId = new AtomicReference<>();
    doAnswer(invocation -> {
      if ("attribute".equals(invocation.getArgument(0))) {
        attribute.set(invocation.getArgument(1));
      } else if ("clientId".equals(invocation.getArgument(0))) {
        clientId.set(invocation.getArgument(1));
      }
      return query;
    }).when(query).setNamedParameter(anyString(), any());
    when(query.uniqueResult()).thenAnswer(invocation -> stored.get(key(attribute.get(), clientId.get())));
    OBDal dal = mock(OBDal.class);
    when(dal.createQuery(eq(Preference.class), anyString())).thenReturn(query);
    TenantPlanService plans = mock(TenantPlanService.class);
    when(plans.resolvePlan("demo-client")).thenReturn(TenantPlanService.PLAN_FREE);
    TenantEnvironmentLifecycleService lifecycle = new TenantEnvironmentLifecycleService(plans);

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot = lifecycle.resolve("demo-client");

      assertTrue(snapshot.isAssociatedWithProductive());
      assertTrue(snapshot.toPolicyEnvironment().isAssociatedWithProductive());
      assertEquals(Decision.DEMO_TRIAL_EXPIRED,
          lifecycle.evaluateAccess("demo-client", true, now));
    }
  }

  @Test
  public void associationMarkerFailsClosedWhenLegacyDemoHasNoTrialStart() {
    Instant now = Instant.now();
    Map<String, Preference> stored = new HashMap<>();
    stored.put(key(TenantEnvironmentLifecycleService.ENVIRONMENT_TYPE_ATTRIBUTE, "demo-client"),
        preference(TenantEnvironmentLifecycleService.TYPE_DEMO));
    stored.put(key(TenantEnvironmentLifecycleService.ASSOCIATED_PRODUCTIVE_ATTRIBUTE, "demo-client"),
        preference("productive-client"));
    OBQuery<Preference> query = mock(OBQuery.class);
    AtomicReference<String> attribute = new AtomicReference<>();
    AtomicReference<String> clientId = new AtomicReference<>();
    doAnswer(invocation -> {
      if ("attribute".equals(invocation.getArgument(0))) {
        attribute.set(invocation.getArgument(1));
      } else if ("clientId".equals(invocation.getArgument(0))) {
        clientId.set(invocation.getArgument(1));
      }
      return query;
    }).when(query).setNamedParameter(anyString(), any());
    when(query.uniqueResult()).thenAnswer(invocation -> stored.get(key(attribute.get(), clientId.get())));
    OBDal dal = mock(OBDal.class);
    when(dal.createQuery(eq(Preference.class), anyString())).thenReturn(query);
    TenantPlanService plans = mock(TenantPlanService.class);
    when(plans.resolvePlan("demo-client")).thenReturn(TenantPlanService.PLAN_FREE);
    TenantEnvironmentLifecycleService lifecycle = new TenantEnvironmentLifecycleService(plans);

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      assertEquals(Decision.DEMO_TRIAL_EXPIRED,
          lifecycle.evaluateAccess("demo-client", true, now));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void trialDaysMustBePositive() {
    new Configuration(0, 15);
  }

  private static String key(String attribute, String clientId) {
    return clientId + "|" + attribute;
  }

  private static Preference preference(String value) {
    Preference preference = mock(Preference.class);
    when(preference.getSearchKey()).thenReturn(value);
    return preference;
  }
}
