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

  /** Distinguishes a trial environment from a paid productive environment. */
  public enum EnvironmentType {
    DEMO,
    PRODUCTIVE
  }

  /** Billing state used by the access policy. */
  public enum SubscriptionStatus {
    NONE,
    CURRENT,
    PAST_DUE,
    EXPIRED,
    /** Explicit compatibility entitlement for historical productive environments. */
    LEGACY_ENTITLEMENT
  }

  /** Result returned when evaluating access for an environment member. */
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

    /**
     * Creates policy configuration with validated trial and grace periods.
     *
     * @param trialDays number of days in a demo trial
     * @param renewalGraceDays number of grace days after renewal is due
     */
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

  /** Immutable environment facts consumed by the access policy. */
  public static final class Environment {
    private final EnvironmentType type;
    private final Instant trialStartedAt;
    private final Instant renewalDueAt;

    private Environment(EnvironmentType type, Instant trialStartedAt, Instant renewalDueAt) {
      this.type = type;
      this.trialStartedAt = trialStartedAt;
      this.renewalDueAt = renewalDueAt;
    }

    /**
     * Creates a demo environment with no renewal date.
     *
     * @param trialStartedAt instant when the demo trial started
     * @return a demo environment
     */
    public static Environment demo(Instant trialStartedAt) {
      return demo(trialStartedAt, null);
    }

    /**
     * Creates a demo environment with an optional renewal date.
     *
     * @param trialStartedAt instant when the demo trial started
     * @param renewalDueAt instant when the associated renewal is due
     * @return a demo environment
     */
    public static Environment demo(Instant trialStartedAt, Instant renewalDueAt) {
      if (trialStartedAt == null) {
        throw new IllegalArgumentException("A demo requires a trial start timestamp");
      }
      return new Environment(EnvironmentType.DEMO, trialStartedAt, renewalDueAt);
    }

    /**
     * Creates a productive environment with no renewal date.
     *
     * @return a productive environment
     */
    public static Environment productive() {
      return new Environment(EnvironmentType.PRODUCTIVE, null, null);
    }

    /**
     * Creates a productive environment with an optional renewal date.
     *
     * @param renewalDueAt instant when the subscription renewal is due
     * @return a productive environment
     */
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

  /**
   * Returns the instant at which an environment's configured trial ends.
   * @param environment environment facts to evaluate
   * @param configuration configured trial duration
   * @return trial expiration instant, or null for productive environments
   */
  public Instant trialExpiresAt(Environment environment, Configuration configuration) {
    if (environment == null || environment.type != EnvironmentType.DEMO) {
      return null;
    }
    return environment.trialStartedAt.plus(configuration.trialDays, ChronoUnit.DAYS);
  }

  /**
   * Returns whole calendar days remaining for display. A still usable partial day is shown as one;
   * an expired trial is always shown as zero.
   * @param environment environment facts to evaluate
   * @param now current instant
   * @param configuration configured trial duration
   * @return whole days remaining in the trial
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
   * @param environment environment facts to evaluate
   * @param activeMembership whether the caller belongs to the environment
   * @param subscriptionStatus current subscription state
   * @param now current instant
   * @param configuration configured trial and grace periods
   * @return the access decision
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
      if (subscriptionStatus == SubscriptionStatus.PAST_DUE
          && environment.renewalDueAt != null
          && now.isBefore(environment.renewalDueAt.plus(configuration.renewalGraceDays,
              ChronoUnit.DAYS))) {
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
