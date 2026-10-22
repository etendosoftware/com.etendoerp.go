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
import java.util.function.Supplier;

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
    /** A demo trial expired or access was revoked when it was promoted to a paid environment. */
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
    private final boolean associatedWithProductive;

    private Environment(EnvironmentType type, Instant trialStartedAt, Instant renewalDueAt,
        boolean associatedWithProductive) {
      this.type = type;
      this.trialStartedAt = trialStartedAt;
      this.renewalDueAt = renewalDueAt;
      this.associatedWithProductive = associatedWithProductive;
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
     * Creates a demo environment with an optional renewal date, without a productive association.
     *
     * @param trialStartedAt instant when the demo trial started
     * @param renewalDueAt instant when the renewal is due
     * @return a demo environment
     */
    public static Environment demo(Instant trialStartedAt, Instant renewalDueAt) {
      if (trialStartedAt == null) {
        throw new IllegalArgumentException("A demo requires a trial start timestamp");
      }
      return new Environment(EnvironmentType.DEMO, trialStartedAt, renewalDueAt, false);
    }

    /**
     * Creates a demo that has been converted to a paid productive environment. Its access is
     * revoked as soon as the association is persisted, independently of trial or renewal state.
     *
     * @param trialStartedAt original instant when the demo trial started
     * @param renewalDueAt productive subscription renewal date, when available
     * @return an associated demo environment
     */
    public static Environment associatedDemo(Instant trialStartedAt, Instant renewalDueAt) {
      return new Environment(EnvironmentType.DEMO, trialStartedAt, renewalDueAt, true);
    }

    /**
     * Creates a productive environment with no renewal date.
     *
     * @return a productive environment
     */
    public static Environment productive() {
      return new Environment(EnvironmentType.PRODUCTIVE, null, null, false);
    }

    /**
     * Creates a productive environment with an optional renewal date.
     *
     * @param renewalDueAt instant when the subscription renewal is due
     * @return a productive environment
     */
    public static Environment productive(Instant renewalDueAt) {
      return new Environment(EnvironmentType.PRODUCTIVE, null, renewalDueAt, false);
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

    public boolean isAssociatedWithProductive() {
      return associatedWithProductive;
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
    if (environment.trialStartedAt == null) {
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
   * Returns the instant a past-due subscription's payment grace ends: the renewal due date plus the
   * configured renewal grace days.
   *
   * <p>The one definition of the grace window. {@link #evaluate} decides access on it, and every
   * response that reports it ({@code GET /sws/go/billing/subscription} and the per-environment
   * {@code graceEndsAt} of {@code GET /sws/go/environments}) reads it from here, so the instant a
   * client shows and the instant access is actually cut cannot drift apart.
   *
   * <p>The configuration is a supplier and is read only when there is a grace window to compute:
   * a tenant that is not past due never depends on the grace setting being readable.
   *
   * @param subscriptionStatus current subscription state, may be null
   * @param renewalDueAt the stored renewal due date (the grace anchor), may be null
   * @param configuration supplies the configured grace period; consulted only for a past-due
   *     subscription with a renewal due date
   * @return the grace end, or null unless the subscription is {@link SubscriptionStatus#PAST_DUE}
   *     and has a renewal due date
   */
  public static Instant graceEndsAt(SubscriptionStatus subscriptionStatus, Instant renewalDueAt,
      Supplier<Configuration> configuration) {
    if (subscriptionStatus != SubscriptionStatus.PAST_DUE || renewalDueAt == null) {
      return null;
    }
    return renewalDueAt.plus(configuration.get().renewalGraceDays, ChronoUnit.DAYS);
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
      if (environment.associatedWithProductive) {
        return Decision.DEMO_TRIAL_EXPIRED;
      }
      if (subscriptionStatus == SubscriptionStatus.CURRENT) {
        return Decision.ALLOWED;
      }
      if (isWithinGrace(subscriptionStatus, environment, now, configuration)) {
        return Decision.ALLOWED;
      }
      Instant expiresAt = environment.trialStartedAt.plus(configuration.trialDays, ChronoUnit.DAYS);
      return now.isBefore(expiresAt) ? Decision.ALLOWED : Decision.DEMO_TRIAL_EXPIRED;
    }
    if (subscriptionStatus == SubscriptionStatus.CURRENT
        || subscriptionStatus == SubscriptionStatus.LEGACY_ENTITLEMENT) {
      return Decision.ALLOWED;
    }
    if (isWithinGrace(subscriptionStatus, environment, now, configuration)) {
      return Decision.ALLOWED;
    }
    return Decision.SUBSCRIPTION_REQUIRED;
  }

  private static boolean isWithinGrace(SubscriptionStatus subscriptionStatus,
      Environment environment, Instant now, Configuration configuration) {
    Instant graceEndsAt = graceEndsAt(subscriptionStatus, environment.renewalDueAt,
        () -> configuration);
    return graceEndsAt != null && now.isBefore(graceEndsAt);
  }
}
