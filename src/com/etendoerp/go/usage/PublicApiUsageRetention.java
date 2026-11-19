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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.database.ExternalConnectionPool;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.erpCommon.utility.PropertyNotFoundException;

import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * ETP-6057 — deletes the raw {@code publicapi.request} rows of {@code ETGO_USAGE_EVENT} older than
 * the retention, so per-minute rows do not grow without bound. Run at the end of every settling-window
 * run of {@link UsageAggregationProcess} (the usage schedule); a backfill run never purges.
 *
 * <p><b>What it touches.</b> Only rows whose {@code EVENT_TYPE} is
 * {@value UsageEventTypes#PUBLIC_API_REQUEST} — never another event type, never
 * {@code ETGO_USAGE_DAILY}: the daily totals the usage job stored stay, the per-key detail behind
 * them goes. The delete uses the {@code (EVENT_TYPE, OCCURRED_AT)} index.</p>
 *
 * <p><b>Retention.</b> The System-level {@value #PREFERENCE_PROPERTY} preference, default
 * {@value #DEFAULT_RETENTION_DAYS} days. It is a LIST preference, registered in the
 * {@code Property Configuration} reference like {@link UsageSettings#PREFERENCE_PROPERTY}, so it is
 * read with {@code isListProperty = true}. It never goes below the longest settling window plus one
 * day: a day the usage job still recomputes must keep its raw rows, or its stored total would be
 * revised downward when they disappear.</p>
 *
 * <p>Never fails the aggregation run: a purge error is logged at WARN, rolled back on the purge's own
 * connection and retried by the next run.</p>
 */
public final class PublicApiUsageRetention {

  private static final Logger log = LogManager.getLogger(PublicApiUsageRetention.class);

  /** AD_Ref_List value registered against the {@code Property Configuration} reference. */
  public static final String PREFERENCE_PROPERTY = "ETGO_PublicApiUsageRetentionDays";

  /** Applied when the preference is not set. */
  public static final int DEFAULT_RETENTION_DAYS = 90;

  private static final String SYSTEM = "0";

  /** Only the one event type, only before the cutoff; never another type, never the daily rows. */
  static final String PURGE_SQL =
      "DELETE FROM etgo_usage_event WHERE event_type = ? AND occurred_at < ?";

  private PublicApiUsageRetention() {
  }

  /**
   * Delete the expired rows, on a connection of its own, and commit. Never throws.
   *
   * <p>Its own pooled connection and transaction ({@link ExternalConnectionPool}, as the usage event
   * writer), never the DAL session: the aggregation run it ends has already committed its days, and
   * a failed delete must not abort the session that run reports through.</p>
   *
   * @param now the moment of the run
   * @return rows deleted; 0 when the purge failed
   */
  public static int purge(Date now) {
    try {
      Date cutoff = cutoff(now, configuredDays(), UsageSettings.getMaxSettlingWindowDays());
      int deleted = delete(cutoff);
      if (deleted > 0) {
        log.info("Public API usage: purged {} {} row(s) dated before {}.", deleted,
            UsageEventTypes.PUBLIC_API_REQUEST, cutoff);
      }
      return deleted;
    } catch (Throwable t) { // NOSONAR — retention must never fail the aggregation run.
      log.warn("Public API usage: could not purge expired {} rows; the next run retries.",
          UsageEventTypes.PUBLIC_API_REQUEST, t);
      return 0;
    }
  }

  private static int delete(Date cutoff) throws SQLException {
    ExternalConnectionPool pool = ExternalConnectionPool.getInstance();
    if (pool == null) {
      throw new IllegalStateException("No external connection pool available.");
    }
    try (Connection connection = pool.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement = connection.prepareStatement(PURGE_SQL)) {
        statement.setString(1, UsageEventTypes.PUBLIC_API_REQUEST);
        statement.setTimestamp(2, new Timestamp(cutoff.getTime()));
        int deleted = statement.executeUpdate();
        connection.commit();
        return deleted;
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  /**
   * The first instant that is kept: local midnight, {@code max(retention, settling + 1)} days ago.
   *
   * @param now            the moment of the run
   * @param retentionDays  the configured retention
   * @param settlingWindow the longest settling window configured anywhere
   * @return rows strictly before it are deleted
   */
  static Date cutoff(Date now, int retentionDays, int settlingWindow) {
    int days = Math.max(retentionDays, settlingWindow + 1);
    return UsageDayRange.minusDays(UsageDayRange.startOfDay(now), days);
  }

  /** @return the System-level preference, or the default when unset, unreadable or not positive */
  static int configuredDays() {
    try {
      return parse(Preferences.getPreferenceValue(PREFERENCE_PROPERTY, true, SYSTEM, SYSTEM, null,
          null, null));
    } catch (PropertyNotFoundException e) {
      return DEFAULT_RETENTION_DAYS;
    } catch (PropertyException | OBException e) {
      log.warn("Could not read {}: {}; using {} days.", PREFERENCE_PROPERTY, e.getMessage(),
          DEFAULT_RETENTION_DAYS);
      return DEFAULT_RETENTION_DAYS;
    }
  }

  static int parse(String raw) {
    if (StringUtils.isBlank(raw)) {
      return DEFAULT_RETENTION_DAYS;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      if (parsed > 0) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // Falls through to the default below, with the same warning.
    }
    log.warn("{} is not a positive number of days ('{}'); using {}.", PREFERENCE_PROPERTY, raw,
        DEFAULT_RETENTION_DAYS);
    return DEFAULT_RETENTION_DAYS;
  }
}
