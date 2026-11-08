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

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * ETP-5051 — the cadence of the scheduled usage aggregation: one globally configurable interval for
 * every billing resource ({@code open-and-notable-topics.md} §5.15), default two hours.
 *
 * <p>Read from {@value #INTERVAL_MINUTES_PROPERTY} (or the environment variable
 * {@value #INTERVAL_MINUTES_ENV}). It sets the interval of the process request the startup hook
 * plants and the lag a stored aggregate may reach before a WARN: a last successful run older than
 * twice the cadence. Changing it after the request was planted means editing the request in the
 * Process Request window too.
 */
public final class UsageScheduleSettings {

  static final String INTERVAL_MINUTES_PROPERTY = "etendo.go.usage.schedule.interval.minutes";
  static final String INTERVAL_MINUTES_ENV = "ETGO_USAGE_SCHEDULE_INTERVAL_MINUTES";
  /** Two hours (§5.15). */
  public static final int DEFAULT_INTERVAL_MINUTES = 120;
  /** Below this a run (window plus a sealing day, every resource) would overlap the next one. */
  static final int MIN_INTERVAL_MINUTES = 15;
  /** A stored aggregate is stale once its last successful run is this many cadences old. */
  public static final int LAG_FACTOR = 2;

  private UsageScheduleSettings() {
  }

  /** @return the configured cadence in minutes, at least {@value #MIN_INTERVAL_MINUTES} */
  public static int intervalMinutes() {
    return Math.max(MIN_INTERVAL_MINUTES, GoRuntimeProperties.readInt(INTERVAL_MINUTES_PROPERTY,
        INTERVAL_MINUTES_ENV, DEFAULT_INTERVAL_MINUTES));
  }

  /** @return the age, in milliseconds, past which the last successful run is reported as late */
  public static long lagThresholdMs() {
    return LAG_FACTOR * intervalMinutes() * 60_000L;
  }
}
