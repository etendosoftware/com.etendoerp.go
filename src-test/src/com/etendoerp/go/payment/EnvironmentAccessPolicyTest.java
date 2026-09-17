/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"); you may not use this file except in compliance with
 * the License.
 * *************************************************************************
 */

package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;

import java.time.Instant;

import org.junit.Test;

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

  @Test(expected = IllegalArgumentException.class)
  public void trialDaysMustBePositive() {
    new Configuration(0, 15);
  }
}
