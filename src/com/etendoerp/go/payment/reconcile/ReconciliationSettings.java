/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * ETP-5048 — the tunables of the billing reconciliation, read from the module's usual layered
 * configuration (JVM property, {@code Openbravo.properties}, environment variable). Every value
 * has a safe default; a malformed value falls back to it.
 *
 * <p>See {@code docs/plans/2026-10-18-etp-5048-billing-reconciliation-design.md} §7.
 */
public final class ReconciliationSettings {

  private static final Logger log = LogManager.getLogger(ReconciliationSettings.class);

  static final String INTERVAL_MINUTES_PROPERTY = "etendo.go.billing.reconcile.interval.minutes";
  static final String INTERVAL_MINUTES_ENV = "ETGO_BILLING_RECONCILE_INTERVAL_MINUTES";
  static final String BATCH_SIZE_PROPERTY = "etendo.go.billing.reconcile.batch.size";
  static final String BATCH_SIZE_ENV = "ETGO_BILLING_RECONCILE_BATCH_SIZE";
  static final String ABANDONED_HOURS_PROPERTY = "etendo.go.billing.reconcile.abandoned.hours";
  static final String ABANDONED_HOURS_ENV = "ETGO_BILLING_RECONCILE_ABANDONED_HOURS";
  static final String PAID_GRACE_MINUTES_PROPERTY =
      "etendo.go.billing.reconcile.paid.grace.minutes";
  static final String PAID_GRACE_MINUTES_ENV = "ETGO_BILLING_RECONCILE_PAID_GRACE_MINUTES";
  static final String RECEIVED_STALE_MINUTES_PROPERTY =
      "etendo.go.billing.reconcile.received.stale.minutes";
  static final String RECEIVED_STALE_MINUTES_ENV = "ETGO_BILLING_RECONCILE_RECEIVED_STALE_MINUTES";
  static final String ABSENT_MAX_SHARE_PROPERTY = "etendo.go.billing.reconcile.absent.max.share";
  static final String ABSENT_MAX_SHARE_ENV = "ETGO_BILLING_RECONCILE_ABSENT_MAX_SHARE";
  static final String MAX_ATTEMPTS_PROPERTY =
      "etendo.go.billing.reconcile.max.provisioning.attempts";
  static final String MAX_ATTEMPTS_ENV = "ETGO_BILLING_RECONCILE_MAX_PROVISIONING_ATTEMPTS";

  static final int DEFAULT_INTERVAL_MINUTES = 60;
  static final int DEFAULT_BATCH_SIZE = 50;
  /**
   * Sessions carry Stripe's default 24 h expiry (no {@code expires_at} is sent), so a shorter
   * window could only ever close rows the provider still answers {@code open} for.
   */
  static final int MIN_ABANDONED_HOURS = 48;
  static final int DEFAULT_PAID_GRACE_MINUTES = 15;
  static final int DEFAULT_RECEIVED_STALE_MINUTES = 60;
  static final double DEFAULT_ABSENT_MAX_SHARE = 0.25d;
  static final int DEFAULT_MAX_PROVISIONING_ATTEMPTS = 5;

  private final int intervalMinutes;
  private final int batchSize;
  private final int abandonedHours;
  private final int paidGraceMinutes;
  private final int receivedStaleMinutes;
  private final double absentMaxShare;
  private final int maxProvisioningAttempts;

  ReconciliationSettings(int intervalMinutes, int batchSize, int abandonedHours,
      int paidGraceMinutes, int receivedStaleMinutes, double absentMaxShare,
      int maxProvisioningAttempts) {
    this.intervalMinutes = Math.max(5, intervalMinutes);
    this.batchSize = Math.max(1, batchSize);
    this.abandonedHours = Math.max(MIN_ABANDONED_HOURS, abandonedHours);
    this.paidGraceMinutes = Math.max(1, paidGraceMinutes);
    this.receivedStaleMinutes = Math.max(5, receivedStaleMinutes);
    this.absentMaxShare = absentMaxShare < 0d || absentMaxShare > 1d
        ? DEFAULT_ABSENT_MAX_SHARE : absentMaxShare;
    this.maxProvisioningAttempts = Math.max(1, maxProvisioningAttempts);
  }

  /** @return the settings as configured now */
  public static ReconciliationSettings fromConfiguration() {
    return new ReconciliationSettings(
        GoRuntimeProperties.readInt(INTERVAL_MINUTES_PROPERTY, INTERVAL_MINUTES_ENV,
            DEFAULT_INTERVAL_MINUTES),
        GoRuntimeProperties.readInt(BATCH_SIZE_PROPERTY, BATCH_SIZE_ENV, DEFAULT_BATCH_SIZE),
        GoRuntimeProperties.readInt(ABANDONED_HOURS_PROPERTY, ABANDONED_HOURS_ENV,
            MIN_ABANDONED_HOURS),
        GoRuntimeProperties.readInt(PAID_GRACE_MINUTES_PROPERTY, PAID_GRACE_MINUTES_ENV,
            DEFAULT_PAID_GRACE_MINUTES),
        GoRuntimeProperties.readInt(RECEIVED_STALE_MINUTES_PROPERTY, RECEIVED_STALE_MINUTES_ENV,
            DEFAULT_RECEIVED_STALE_MINUTES),
        readShare(),
        GoRuntimeProperties.readInt(MAX_ATTEMPTS_PROPERTY, MAX_ATTEMPTS_ENV,
            DEFAULT_MAX_PROVISIONING_ATTEMPTS));
  }

  /** @return the defaults, ignoring configuration */
  public static ReconciliationSettings defaults() {
    return new ReconciliationSettings(DEFAULT_INTERVAL_MINUTES, DEFAULT_BATCH_SIZE,
        MIN_ABANDONED_HOURS, DEFAULT_PAID_GRACE_MINUTES, DEFAULT_RECEIVED_STALE_MINUTES,
        DEFAULT_ABSENT_MAX_SHARE, DEFAULT_MAX_PROVISIONING_ATTEMPTS);
  }

  private static double readShare() {
    String raw = GoRuntimeProperties.readValue(ABSENT_MAX_SHARE_PROPERTY, ABSENT_MAX_SHARE_ENV,
        "");
    if (StringUtils.isBlank(raw)) {
      return DEFAULT_ABSENT_MAX_SHARE;
    }
    try {
      return Double.parseDouble(raw.trim());
    } catch (NumberFormatException e) {
      log.warn("{} is not a number ('{}'); using {}", ABSENT_MAX_SHARE_PROPERTY, raw,
          DEFAULT_ABSENT_MAX_SHARE);
      return DEFAULT_ABSENT_MAX_SHARE;
    }
  }

  /** @return minutes between scheduled runs (at least 5) */
  public int getIntervalMinutes() {
    return intervalMinutes;
  }

  /** @return candidate rows per phase per run */
  public int getBatchSize() {
    return batchSize;
  }

  /** @return hours before a checkout counts as abandoned (at least 48) */
  public int getAbandonedHours() {
    return abandonedHours;
  }

  /** @return minutes a paid purchase is left to its browser before the server provisions it */
  public int getPaidGraceMinutes() {
    return paidGraceMinutes;
  }

  /** @return minutes before a {@code RECEIVED} provider event counts as stuck */
  public int getReceivedStaleMinutes() {
    return receivedStaleMinutes;
  }

  /** @return the share of absent subscriptions above which the breaker trips (0…1) */
  public double getAbsentMaxShare() {
    return absentMaxShare;
  }

  /** @return provisioning attempts after which a purchase goes to review instead of retrying */
  public int getMaxProvisioningAttempts() {
    return maxProvisioningAttempts;
  }
}
