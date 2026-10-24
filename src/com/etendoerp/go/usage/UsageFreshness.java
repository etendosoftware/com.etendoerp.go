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

package com.etendoerp.go.usage;

import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-5051 — warns when the stored usage falls behind its cadence ({@code open-and-notable-topics.md}
 * §5.15): the last successful run in {@code ETGO_USAGE_RUN_LOG} is older than
 * {@link UsageScheduleSettings#LAG_FACTOR} times the configured cadence, or there is none.
 *
 * <p>Checked where staleness matters — when a quota reads the stored aggregate, and when a scheduled
 * aggregation run starts — never on a timer of its own. The last-run read is cached for a minute and
 * the WARN is throttled to one line an hour, so a busy write path costs at most one small query a
 * minute. The production read runs in its own session on the write path's own pool
 * ({@link IsolatedDalRead#callOnWritePath}): it is reached from the NEO write check, so a failing
 * lag check must never poison the create it runs beside, and a busy report pool must never make it
 * wait.
 *
 * <p><b>Known limit:</b> the run log has one row per resource and tenant that the run counted, and
 * a run that counted nothing for anyone writes none. An installation with resources but no usage at
 * all therefore reads as "never ran". Such an installation has nothing a quota could be over.
 */
public class UsageFreshness {

  private static final Logger log = LogManager.getLogger(UsageFreshness.class);

  static final String STATUS_SUCCESS = UsageRunLedger.STATUS_SUCCESS;
  private static final long CACHE_MS = 60_000L;
  private static final long WARN_INTERVAL_MS = 3_600_000L;
  private static final String THROTTLE_KEY = "usage-lag";

  private static final UsageFreshness DEFAULT = new UsageFreshness(
      System::currentTimeMillis,
      () -> IsolatedDalRead.callOnWritePath(UsageFreshness::readLastSuccessfulRun),
      UsageScheduleSettings::lagThresholdMs);

  private final LongSupplier clock;
  private final Supplier<Date> lastSuccessfulRun;
  private final LongSupplier lagThresholdMs;
  private final KeyedLogThrottle throttle;
  private final AtomicReference<CachedRun> cached = new AtomicReference<>();

  /**
   * Creates a checker over the given time source, run-log reader and threshold.
   *
   * @param clock the time source, in milliseconds
   * @param lastSuccessfulRun reads the finish time of the last successful run, or null
   * @param lagThresholdMs the age past which a run is late
   */
  public UsageFreshness(LongSupplier clock, Supplier<Date> lastSuccessfulRun,
      LongSupplier lagThresholdMs) {
    this.clock = clock;
    this.lastSuccessfulRun = lastSuccessfulRun;
    this.lagThresholdMs = lagThresholdMs;
    this.throttle = new KeyedLogThrottle(WARN_INTERVAL_MS, clock);
  }

  /**
   * The shared instance the consumption reader and the aggregation process use.
   *
   * @return the shared instance
   */
  public static UsageFreshness getDefault() {
    return DEFAULT;
  }

  /**
   * Checks the lag and logs a throttled WARN when the stored usage is late. Never throws: a failed
   * read is logged at DEBUG and treated as "nothing to report".
   *
   * @return {@code true} when the stored usage is late
   */
  public boolean checkLag() {
    try {
      long now = clock.getAsLong();
      CachedRun current = cached.get();
      if (current == null || now - current.readAt >= CACHE_MS) {
        current = readRun(now);
        cached.set(current);
      }
      if (current.failed) {
        return false;
      }
      Date last = current.lastRun;
      long threshold = lagThresholdMs.getAsLong();
      boolean late = last == null || now - last.getTime() > threshold;
      if (late && throttle.shouldLog(THROTTLE_KEY)) {
        log.warn("[USAGE-LAG] The stored usage is late: the last successful usage aggregation "
            + "run finished {} and runs are due every {} min; quotas that read the stored "
            + "aggregate are evaluated against stale usage. Check the usage-aggregation-schedule "
            + "flag, the Billing Resource Usage Aggregation process request and Process Monitor.",
            last == null ? "never" : last.toInstant().toString(), threshold / 60_000L
                / UsageScheduleSettings.LAG_FACTOR);
      }
      return late;
    } catch (RuntimeException e) {
      log.debug("Could not check the usage lag", e);
      return false;
    }
  }

  /**
   * Reads the run log once. A failed read is cached like a successful one, so a slow or broken
   * read (it may wait up to the isolated read's timeout) is retried once a minute, not on every
   * consumption read.
   */
  private CachedRun readRun(long now) {
    try {
      return new CachedRun(now, lastSuccessfulRun.get(), false);
    } catch (RuntimeException e) {
      log.debug("Could not read the usage run log; not checking the lag for a minute", e);
      return new CachedRun(now, null, true);
    }
  }

  /** One read of the run log: when it was read, what it said, and whether it failed. Immutable. */
  private static final class CachedRun {
    private final long readAt;
    private final Date lastRun;
    private final boolean failed;

    private CachedRun(long readAt, Date lastRun, boolean failed) {
      this.readAt = readAt;
      this.lastRun = lastRun == null ? null : new Date(lastRun.getTime());
      this.failed = failed;
    }
  }

  /** @return the finish time of the last successful run, or null when there is none */
  static Date readLastSuccessfulRun() {
    // A scalar on the raw session: the run log is System-owned and no DAL filter may narrow it.
    return OBDal.getInstance().getSession()
        .createQuery("select max(l.finishedAt) from ETGO_USAGE_RUN_LOG l"
            + " where l.status = :status", Date.class)
        .setParameter("status", STATUS_SUCCESS)
        .uniqueResult();
  }
}
