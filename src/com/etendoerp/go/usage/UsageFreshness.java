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
 * minute.
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
      System::currentTimeMillis, UsageFreshness::readLastSuccessfulRun,
      UsageScheduleSettings::lagThresholdMs);

  private final LongSupplier clock;
  private final Supplier<Date> lastSuccessfulRun;
  private final LongSupplier lagThresholdMs;
  private final KeyedLogThrottle throttle;
  private volatile long cachedAt;
  private volatile Date cachedLastRun;

  /**
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

  /** @return the shared instance the readers and the process use */
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
      if (cachedAt == 0L || now - cachedAt >= CACHE_MS) {
        cachedLastRun = lastSuccessfulRun.get();
        cachedAt = now;
      }
      Date last = cachedLastRun;
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
