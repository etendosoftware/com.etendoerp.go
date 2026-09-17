/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"); you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * *************************************************************************
 */

package com.etendoerp.go.payment;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Provider-neutral access rules for demo and productive environments.
 *
 * <p>This class contains only deterministic policy. Persistence, authentication, billing
 * providers, and environment provisioning remain outside it so the rules can be reused by the
 * account API and by request filters.
 */
public final class EnvironmentAccessPolicy {

  public enum EnvironmentType {
    DEMO,
    PRODUCTIVE
  }

  public enum SubscriptionStatus {
    NONE,
    CURRENT,
    PAST_DUE,
    EXPIRED,
    /** Explicit compatibility entitlement for historical productive environments. */
    LEGACY_ENTITLEMENT
  }

  public enum Decision {
    ALLOWED,
    MEMBERSHIP_REQUIRED,
    DEMO_TRIAL_EXPIRED,
    SUBSCRIPTION_REQUIRED
  }

  /** Configuration loaded by an adapter from the server configuration source. */
  public static final class Configuration {
    private final int trialDays;
    private final int renewalGraceDays;

    public Configuration(int trialDays, int renewalGraceDays) {
      if (trialDays <= 0) {
        throw new IllegalArgumentException("trialDays must be greater than zero");
      }
      if (renewalGraceDays < 0) {
        throw new IllegalArgumentException("renewalGraceDays cannot be negative");
      }
      this.trialDays = trialDays;
      this.renewalGraceDays = renewalGraceDays;
    }

    public int getTrialDays() {
      return trialDays;
    }

    public int getRenewalGraceDays() {
      return renewalGraceDays;
    }
  }

  public static final class Environment {
    private final EnvironmentType type;
    private final Instant trialStartedAt;
    private final Instant renewalDueAt;

    private Environment(EnvironmentType type, Instant trialStartedAt, Instant renewalDueAt) {
      this.type = type;
      this.trialStartedAt = trialStartedAt;
      this.renewalDueAt = renewalDueAt;
    }

    public static Environment demo(Instant trialStartedAt) {
      if (trialStartedAt == null) {
        throw new IllegalArgumentException("A demo requires a trial start timestamp");
      }
      return new Environment(EnvironmentType.DEMO, trialStartedAt, null);
    }

    public static Environment productive() {
      return new Environment(EnvironmentType.PRODUCTIVE, null, null);
    }

    public static Environment productive(Instant renewalDueAt) {
      return new Environment(EnvironmentType.PRODUCTIVE, null, renewalDueAt);
    }

    public EnvironmentType getType() {
      return type;
    }

    public Instant getTrialStartedAt() {
      return trialStartedAt;
    }

    public Instant getRenewalDueAt() {
      return renewalDueAt;
    }
  }

  /** Returns the instant at which an environment's configured trial ends. */
  public Instant trialExpiresAt(Environment environment, Configuration configuration) {
    if (environment == null || environment.type != EnvironmentType.DEMO) {
      return null;
    }
    return environment.trialStartedAt.plus(configuration.trialDays, ChronoUnit.DAYS);
  }

  /**
   * Returns whole calendar days remaining for display. A still usable partial day is shown as one;
   * an expired trial is always shown as zero.
   */
  public long remainingTrialDays(Environment environment, Instant now,
      Configuration configuration) {
    Instant expiresAt = trialExpiresAt(environment, configuration);
    if (expiresAt == null || !now.isBefore(expiresAt)) {
      return 0;
    }
    Duration remaining = Duration.between(now, expiresAt);
    long seconds = remaining.getSeconds();
    if (remaining.getNano() > 0) {
      seconds++;
    }
    return (seconds + (24 * 60 * 60L) - 1) / (24 * 60 * 60L);
  }

  /**
   * Evaluates access for a member of the destination company. Membership is intentionally an
   * input independent of subscription ownership, which preserves invited-user access semantics.
   */
  public Decision evaluate(Environment environment, boolean activeMembership,
      SubscriptionStatus subscriptionStatus, Instant now, Configuration configuration) {
    if (!activeMembership) {
      return Decision.MEMBERSHIP_REQUIRED;
    }
    if (environment.type == EnvironmentType.DEMO) {
      if (subscriptionStatus == SubscriptionStatus.CURRENT) {
        return Decision.ALLOWED;
      }
      Instant expiresAt = environment.trialStartedAt.plus(configuration.trialDays, ChronoUnit.DAYS);
      return now.isBefore(expiresAt) ? Decision.ALLOWED : Decision.DEMO_TRIAL_EXPIRED;
    }
    if (subscriptionStatus == SubscriptionStatus.CURRENT
        || subscriptionStatus == SubscriptionStatus.LEGACY_ENTITLEMENT) {
      return Decision.ALLOWED;
    }
    if (subscriptionStatus == SubscriptionStatus.PAST_DUE
        && environment.renewalDueAt != null
        && now.isBefore(environment.renewalDueAt.plus(configuration.renewalGraceDays,
            ChronoUnit.DAYS))) {
      return Decision.ALLOWED;
    }
    return Decision.SUBSCRIPTION_REQUIRED;
  }
}
