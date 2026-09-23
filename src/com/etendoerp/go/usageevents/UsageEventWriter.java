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

package com.etendoerp.go.usageevents;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.database.ExternalConnectionPool;
import org.openbravo.erpCommon.utility.SequenceIdData;

/**
 * Single-thread, batching writer for {@code ETGO_USAGE_EVENT}.
 *
 * <p>Carries the same three structural guarantees as {@code McpUsageLogger}, which this class
 * mirrors:</p>
 *
 * <ol>
 *   <li><b>Its own transaction, on its own connection.</b> Rows are written over a connection
 *       borrowed straight from {@link ExternalConnectionPool}, never from {@code OBDal} and never
 *       from the Hibernate {@code SessionHandler}. There is no shared {@code Session}, no shared
 *       {@code Connection} and no shared transaction, so a failing INSERT cannot roll back the
 *       business operation it describes.</li>
 *   <li><b>Its own thread.</b> {@link #offer(UsageEvent)} only enqueues on a bounded queue and never
 *       blocks; the INSERT happens on a single daemon thread. A slow or unreachable database slows
 *       usage recording and nothing else.</li>
 *   <li><b>It cannot throw.</b> Neither {@link #offer(UsageEvent)} nor the writer loop lets anything
 *       escape; the loop survives any failure of a batch and moves on to the next one.</li>
 * </ol>
 *
 * <h2>Batching</h2>
 *
 * <p>The improvement over {@code McpUsageLogger}: the thread waits for one event, then drains up to
 * {@value #BATCH_SIZE} in total and inserts them with {@code addBatch()} in one commit. The UI
 * endpoint produces bursts, and one round-trip per row does not scale. A batch is all-or-nothing in
 * PostgreSQL, so one poisoned row (an FK to a user that does not exist, say) would otherwise cost
 * the other 99: when the batch fails, it is retried row by row and only the rows that fail on their
 * own are lost.</p>
 *
 * <h2>Losses are counted and reported, never silent</h2>
 *
 * <p>A gap in the table is indistinguishable from "nobody did that", which is exactly the wrong
 * conclusion somebody will eventually draw. So every lost row — queue full, writer stopped, insert
 * failed, still queued at shutdown — is counted, and surfaced at WARN: the first loss immediately,
 * then at most one line per {@value #DROP_WARN_INTERVAL_MS} ms with the running total, and whatever
 * is left at {@link #shutdown()}.</p>
 *
 * <p>The cost, stated plainly: rows still buffered past the shutdown grace period are lost, and rows
 * are dropped rather than queued without bound under sustained overload. Both are the right trade —
 * usage data is insight, the user's transaction and the server's heap are not negotiable.</p>
 */
final class UsageEventWriter {

  /**
   * Persists one batch. The production sink is {@link #insertBatch(List)}; tests inject their own.
   * Returns the number of rows actually persisted; the writer counts the rest as lost. Throwing
   * counts the whole batch as lost. The list must not be retained.
   */
  @FunctionalInterface
  interface Sink {
    int write(List<UsageEvent> batch) throws Exception; // NOSONAR — any failure is a lost batch.
  }

  private static final Logger log = LogManager.getLogger(UsageEventWriter.class);

  /**
   * Bound on buffered rows, so a database stall costs a bounded amount of heap and then starts
   * dropping instead of growing until the JVM dies.
   */
  static final int QUEUE_CAPACITY = 5_000;

  /** Maximum rows per INSERT batch and commit. */
  static final int BATCH_SIZE = 100;

  /** How long the idle thread waits before re-checking whether it has been stopped. */
  private static final long POLL_TIMEOUT_MS = 1_000L;

  /** Minimum gap between loss warnings, so sustained overload cannot flood the log. */
  static final long DROP_WARN_INTERVAL_MS = 60_000L;

  /** How long {@link #shutdown()} waits for the queue to drain before reporting what is left. */
  static final long SHUTDOWN_GRACE_MS = 2_000L;

  // Real widths of the VARCHAR columns (ETGO_USAGE_EVENT.xml). Values are clipped to them rather
  // than allowed to fail the INSERT — and, in a batch, every other row with it.
  static final int ID_WIDTH = 32;
  static final int EVENT_TYPE_WIDTH = 60;
  static final int SOURCE_WIDTH = 20;
  static final int SESSION_KEY_WIDTH = 200;
  static final int TARGET_WIDTH = 200;
  static final int ACTION_WIDTH = 60;
  static final int OUTCOME_WIDTH = 20;
  static final int ERROR_CODE_WIDTH = 200;
  static final int APP_VERSION_WIDTH = 60;

  /** Largest value {@code DURATION_MS NUMERIC(10,0)} holds. */
  static final long DURATION_MAX = 9_999_999_999L;

  static final String INSERT_SQL =
      "INSERT INTO etgo_usage_event ("
          + "etgo_usage_event_id, ad_client_id, ad_org_id, isactive, created, createdby, "
          + "updated, updatedby, event_type, source, ad_user_id, ad_role_id, session_key, "
          + "target, action, outcome, error_code, duration_ms, occurred_at, app_version, "
          + "properties) "
          + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private final BlockingQueue<UsageEvent> queue;
  private final Sink sink;
  private final LogThrottle drops;
  private final Thread thread;
  private volatile boolean accepting = true;

  private UsageEventWriter(int capacity, Sink sink, LongSupplier clock) {
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.sink = sink;
    this.drops = new LogThrottle(DROP_WARN_INTERVAL_MS, clock);
    this.thread = new Thread(this::run, "etgo-usage-events");
    this.thread.setDaemon(true);
  }

  /** Start the production writer: JDBC sink, {@value #QUEUE_CAPACITY} rows of buffer. */
  static UsageEventWriter start() {
    return start(QUEUE_CAPACITY, UsageEventWriter::insertBatch, System::currentTimeMillis);
  }

  /** Test seam: start a writer over any sink, capacity and clock. */
  static UsageEventWriter start(int capacity, Sink sink, LongSupplier clock) {
    UsageEventWriter writer = new UsageEventWriter(capacity, sink, clock);
    writer.thread.start();
    return writer;
  }

  /**
   * Enqueue one event. Never blocks and never throws.
   *
   * @return false when the event was dropped (queue full or writer stopped); the drop is counted
   */
  boolean offer(UsageEvent event) {
    try {
      if (!accepting) {
        noteDrop("writer stopped", 1);
        return false;
      }
      if (!queue.offer(event)) {
        noteDrop("queue full", 1);
        return false;
      }
      return true;
    } catch (Throwable t) { // NOSONAR — nothing may escalate to the caller.
      noteDrop("could not enqueue", 1);
      log.debug("Could not enqueue a usage event.", t);
      return false;
    }
  }

  /** @return rows this writer failed to persist since it started */
  long getDroppedRows() {
    return drops.total();
  }

  /** @return rows currently buffered and not yet written */
  int getQueuedRows() {
    return queue.size();
  }

  /**
   * Stop accepting, let the thread drain for {@value #SHUTDOWN_GRACE_MS} ms, then count whatever is
   * still queued as lost and say so at WARN. Idempotent, never throws.
   */
  void shutdown() {
    shutdown(SHUTDOWN_GRACE_MS);
  }

  /** Test seam for {@link #shutdown()} with a custom grace period. */
  void shutdown(long graceMs) {
    if (!accepting) {
      return;
    }
    accepting = false;
    try {
      thread.join(graceMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Interrupted while stopping the usage event writer.", e);
    } catch (Throwable t) { // NOSONAR — shutdown must not fail undeploy.
      log.debug("Could not wait for the usage event writer.", t);
    }
    try {
      if (thread.isAlive()) {
        thread.interrupt();
      }
      List<UsageEvent> pending = new ArrayList<>();
      queue.drainTo(pending);
      if (!pending.isEmpty()) {
        drops.add(pending.size());
        log.warn("Usage event writer stopped with {} row(s) still queued; they are lost. "
            + "{} row(s) lost in total since startup.", pending.size(), drops.total());
      } else if (drops.total() > 0L) {
        log.warn("Usage event writer stopped cleanly. {} row(s) were lost since startup.",
            drops.total());
      }
    } catch (Throwable t) { // NOSONAR — shutdown must not fail undeploy.
      log.debug("Could not stop the usage event writer cleanly.", t);
    }
  }

  /** The writer loop. Keeps draining after {@link #shutdown()} until the queue is empty. */
  private void run() {
    List<UsageEvent> batch = new ArrayList<>(BATCH_SIZE);
    while (accepting || !queue.isEmpty()) {
      try {
        UsageEvent first = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (first == null) {
          continue;
        }
        batch.add(first);
        queue.drainTo(batch, BATCH_SIZE - 1);
        flush(batch);
      } catch (InterruptedException e) {
        // Only shutdown() interrupts, past its grace period; it accounts for the queue itself.
        Thread.currentThread().interrupt();
        return;
      } catch (Throwable t) { // NOSONAR — the loop must survive anything a batch throws.
        log.debug("Unexpected failure in the usage event writer loop.", t);
      } finally {
        batch.clear();
      }
    }
  }

  private void flush(List<UsageEvent> batch) {
    int size = batch.size();
    try {
      int written = sink.write(List.copyOf(batch));
      if (written < size) {
        noteDrop("insert failed", (long) size - Math.max(written, 0));
      }
    } catch (Throwable t) { // NOSONAR — a failed batch is counted and forgotten, never raised.
      noteDrop("insert failed", size);
      log.debug("Could not write a batch of {} usage event(s).", size, t);
    }
  }

  /** Count {@code n} lost rows and warn, throttled. Never throws. */
  private void noteDrop(String reason, long n) {
    try {
      if (drops.note(n)) {
        log.warn("Usage event row(s) lost ({}). {} row(s) lost since startup — ETGO_USAGE_EVENT "
            + "is incomplete for this period; do not read a gap as absence of usage.", reason,
            drops.total());
      }
    } catch (Throwable t) { // NOSONAR — accounting for a loss must not lose anything else.
      log.debug("Could not account for a lost usage event row.", t);
    }
  }

  // ── Production sink ────────────────────────────────────────────────────

  /**
   * Insert a batch in one transaction on a pooled connection; on failure, roll back and retry row
   * by row so one bad row does not cost the others.
   *
   * @return rows persisted
   */
  static int insertBatch(List<UsageEvent> batch) throws SQLException {
    ExternalConnectionPool pool = ExternalConnectionPool.getInstance();
    if (pool == null) {
      throw new IllegalStateException("No external connection pool available.");
    }
    Connection connection = null;
    try {
      connection = pool.getConnection();
      connection.setAutoCommit(false);
      try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
        for (UsageEvent event : batch) {
          bind(statement, event);
          statement.addBatch();
        }
        statement.executeBatch();
      }
      connection.commit();
      return batch.size();
    } catch (SQLException batchFailure) {
      rollbackQuietly(connection);
      if (connection == null || batch.size() == 1) {
        throw batchFailure;
      }
      log.debug("Usage event batch of {} failed; retrying row by row.", batch.size(),
          batchFailure);
      return insertOneByOne(connection, batch);
    } finally {
      closeQuietly(connection);
    }
  }

  private static int insertOneByOne(Connection connection, List<UsageEvent> batch) {
    int written = 0;
    for (UsageEvent event : batch) {
      try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
        bind(statement, event);
        statement.executeUpdate();
        connection.commit();
        written++;
      } catch (SQLException e) {
        rollbackQuietly(connection);
        log.debug("Could not write usage event '{}'.", event.eventType(), e);
      }
    }
    return written;
  }

  /** Bind one event to {@link #INSERT_SQL}. Package-private so the binding can be tested. */
  static void bind(PreparedStatement statement, UsageEvent event) throws SQLException {
    int i = 1;
    statement.setString(i++, SequenceIdData.getUUID());
    statement.setString(i++, StringUtils.defaultIfBlank(id(event.clientId()),
        UsageEvent.DEFAULT_CLIENT));
    statement.setString(i++, StringUtils.defaultIfBlank(id(event.orgId()),
        UsageEvent.DEFAULT_ORG));
    String auditUser = StringUtils.defaultIfBlank(id(event.userId()), UsageEvent.SYSTEM_USER);
    statement.setString(i++, auditUser);
    statement.setString(i++, auditUser);
    statement.setString(i++, StringUtils.left(event.eventType(), EVENT_TYPE_WIDTH));
    statement.setString(i++, StringUtils.left(
        StringUtils.defaultIfBlank(event.source(), UsageEvent.SOURCE_BACKEND), SOURCE_WIDTH));
    setId(statement, i++, event.userId());
    setId(statement, i++, event.roleId());
    setText(statement, i++, event.sessionKey(), SESSION_KEY_WIDTH);
    setText(statement, i++, event.target(), TARGET_WIDTH);
    setText(statement, i++, event.action(), ACTION_WIDTH);
    setText(statement, i++, event.outcome(), OUTCOME_WIDTH);
    setText(statement, i++, event.errorCode(), ERROR_CODE_WIDTH);
    setDuration(statement, i++, event.durationMs());
    Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();
    statement.setTimestamp(i++, Timestamp.from(occurredAt));
    setText(statement, i++, event.appVersion(), APP_VERSION_WIDTH);
    // properties is a TEXT column holding JSON, already capped by UsageEvent: never clipped here,
    // because a clipped JSON text would break every properties::jsonb query.
    String properties = StringUtils.trimToNull(event.properties());
    if (properties == null) {
      statement.setNull(i, Types.VARCHAR);
    } else {
      statement.setString(i, properties);
    }
  }

  /**
   * An id is either whole or absent: clipping one would produce a different, dangling id and an FK
   * violation. Returns null for blank or over-wide values.
   */
  private static String id(String value) {
    String trimmed = StringUtils.trimToNull(value);
    return trimmed == null || trimmed.length() > ID_WIDTH ? null : trimmed;
  }

  private static void setId(PreparedStatement statement, int index, String value)
      throws SQLException {
    String id = id(value);
    if (id == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, id);
    }
  }

  /** Bind a VARCHAR column, clipping to its real width rather than failing the insert. */
  private static void setText(PreparedStatement statement, int index, String value, int width)
      throws SQLException {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, StringUtils.abbreviate(trimmed, width));
    }
  }

  /** Negative durations are meaningless and stored as NULL; oversized ones are capped. */
  private static void setDuration(PreparedStatement statement, int index, Long value)
      throws SQLException {
    if (value == null || value < 0L) {
      statement.setNull(index, Types.NUMERIC);
    } else {
      statement.setLong(index, Math.min(value, DURATION_MAX));
    }
  }

  private static void rollbackQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.rollback();
    } catch (SQLException e) {
      log.debug("Could not roll back the usage event connection.", e);
    }
  }

  private static void closeQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.setAutoCommit(true);
      connection.close();
    } catch (SQLException e) {
      log.debug("Could not return the usage event connection to the pool.", e);
    }
  }
}
