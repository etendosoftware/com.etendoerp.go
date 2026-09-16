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

package com.etendoerp.go.mcp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.database.ExternalConnectionPool;
import org.openbravo.erpCommon.utility.SequenceIdData;

import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * Fire-and-forget writer for {@code ETGO_MCP_USAGE} (Track B1).
 *
 * <h2>Rule 1 — telemetry must never break or slow a tool call</h2>
 *
 * <p>This is the primary correctness requirement of the class, and it is guaranteed structurally
 * rather than by convention. Three independent properties hold:</p>
 *
 * <ol>
 *   <li><b>Its own transaction, on its own connection.</b> The row is written over a connection
 *       borrowed straight from {@link ExternalConnectionPool}, never from {@code OBDal} and never
 *       from the Hibernate {@code SessionHandler}. The business operation's transaction has already
 *       been committed and closed by {@link McpSessionManager#executeInContext} before the row is
 *       even enqueued, and nothing here can reach it: there is no shared {@code Session}, no shared
 *       {@code Connection}, and no shared transaction to roll back. A failing telemetry INSERT
 *       therefore cannot roll back the user's order — the hazard D25 names.</li>
 *   <li><b>Its own thread.</b> {@link #record(McpUsageRow)} only enqueues; the INSERT happens on a
 *       single daemon writer thread. The caller pays a queue offer, not a database round-trip, so a
 *       slow or unreachable database slows telemetry and nothing else.</li>
 *   <li><b>It cannot throw.</b> {@link #record(McpUsageRow)} catches {@link Throwable}; so does the
 *       writer task. A full queue drops the row and logs at debug. There is no code path from a
 *       telemetry failure back to the caller or to the tool result.</li>
 * </ol>
 *
 * <p>The cost of (2) and (3), stated plainly: rows buffered at shutdown are lost, and rows are
 * dropped rather than queued without bound under sustained overload. Both are the correct trade —
 * telemetry is diagnostic, the user's transaction is not.</p>
 *
 * <h2>Losses are counted and reported, never silent</h2>
 *
 * <p>D24 makes this table the source of truth on the argument that Mixpanel can drop events and the
 * table cannot. A silently dropped row weakens exactly that claim, because a gap in the data is
 * indistinguishable from "nobody called the MCP that hour" — a wrong conclusion somebody will
 * eventually draw. So both loss paths are counted in {@link #droppedRows} and surfaced at WARN: the
 * first drop immediately, then at most one line per {@value #DROP_WARN_INTERVAL_MS} ms carrying the
 * running total (throttled, because under sustained overload an unthrottled warning would become
 * the noisiest line in the log), and whatever is still queued at {@link #shutdown()}.</p>
 *
 * <p>The gap is made <i>visible</i>, deliberately not <i>queryable</i>: a sentinel row in the table
 * was considered and rejected, because it would touch the {@code row_type} check constraint and
 * D31's contract for something a log line already answers.</p>
 *
 * <h2>Rule 2 — shape, never content</h2>
 *
 * <p>Enforced upstream, in what {@link McpUsageRow} is allowed to carry and in what
 * {@link McpUsageTelemetry} puts there. This class writes whatever it is given, so the rule lives
 * where the values are chosen; see both classes' javadoc.</p>
 *
 * <h2>Opt-out (D28)</h2>
 *
 * <p>On by default. An instance opts out with {@code mcp.telemetry.enabled=false} in
 * {@code Openbravo.properties}. It is read as a property rather than an AD Preference on purpose:
 * D28 asks for a <i>per-instance</i> switch, a Preference is per client/org/user/role, and a
 * property needs neither an {@code OBContext} nor a database read on the hot path.</p>
 *
 * <p>The Mixpanel projection has its own switch, {@code mcp.telemetry.mixpanel.enabled}, because
 * the two decisions are genuinely different: a client may well want the table, which stays inside
 * its own database, and refuse the third party. Turning the table off turns the projection off too
 * — there is nothing to project — but not the other way round.</p>
 *
 * <h2>Mixpanel (B2)</h2>
 *
 * <p>Emission goes through the module's existing {@link NeoTelemetryService}, which already owns the
 * Mixpanel client, the EU host, the config and the sanitizer. There is deliberately no second client
 * here. It runs on the writer thread, AFTER the row is committed, so D24's ordering holds literally:
 * the authoritative record exists before the projection is attempted, and a Mixpanel failure cannot
 * cost us the row. {@code NeoTelemetryService.emit} is itself non-throwing.</p>
 */
final class McpUsageLogger {

  private static final Logger log = LogManager.getLogger(McpUsageLogger.class);

  /** Per-instance opt-out (D28). Absent or unparseable means enabled. */
  private static final String PROP_ENABLED = "mcp.telemetry.enabled";
  /** Independent opt-out for the Mixpanel projection (B2). Absent or unparseable means enabled. */
  private static final String PROP_MIXPANEL_ENABLED = "mcp.telemetry.mixpanel.enabled";

  /**
   * Bound on buffered rows. Chosen so a database stall costs a bounded amount of heap and then
   * starts dropping, instead of growing until the JVM dies — telemetry may degrade, the server
   * may not.
   */
  private static final int QUEUE_CAPACITY = 2_000;

  /** Etendo audit user for rows written by the server itself rather than by a UI action. */
  private static final String SYSTEM_USER = "100";
  private static final String DEFAULT_CLIENT = "0";
  private static final String DEFAULT_ORG = "0";

  /** Width of every VARCHAR column on the table; values are clipped rather than allowed to fail. */
  private static final int VARCHAR_LIMIT = 200;

  /** Minimum gap between drop warnings, so sustained overload cannot flood the log. */
  private static final long DROP_WARN_INTERVAL_MS = 60_000L;

  /** How long {@link #shutdown()} waits for the queue to drain before reporting what is left. */
  private static final long SHUTDOWN_GRACE_MS = 2_000L;

  /** Every row this logger failed to persist, for any reason, since the JVM started. */
  private static final AtomicLong droppedRows = new AtomicLong();

  /** Epoch millis of the last drop warning; 0 until the first one. */
  private static final AtomicLong lastDropWarnAt = new AtomicLong();

  private static final String INSERT_SQL =
      "INSERT INTO etgo_mcp_usage ("
          + "etgo_mcp_usage_id, ad_client_id, ad_org_id, isactive, created, createdby, "
          + "updated, updatedby, session_key, tool_name, verb, target_entity, fields_touched, "
          + "outcome, error_code, duration_ms, req_bytes, resp_bytes, client_name, "
          + "client_version, row_type, payload) "
          + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  /**
   * Single writer thread with a bounded queue and a caller-never-blocks rejection policy. Kept as a
   * {@link ThreadPoolExecutor} rather than a {@code newSingleThreadExecutor} precisely so the queue
   * can be bounded — the convenience factory uses an unbounded one.
   */
  private static final ThreadPoolExecutor WRITER = buildWriter();

  /**
   * The shared telemetry facade. Lazily built because {@code runtime()} reads configuration and
   * logs, and there is no reason to do that in a static initialiser at class-load time. Package
   * private and settable so a test can inject a capturing service.
   */
  private static volatile NeoTelemetryService telemetryService;

  private McpUsageLogger() {
  }

  /** Package-private seam for tests; pass null to restore the runtime service. */
  static void setTelemetryService(NeoTelemetryService service) {
    telemetryService = service;
  }

  private static NeoTelemetryService telemetry() {
    NeoTelemetryService local = telemetryService;
    if (local == null) {
      synchronized (McpUsageLogger.class) {
        local = telemetryService;
        if (local == null) {
          local = NeoTelemetryService.runtime();
          telemetryService = local;
        }
      }
    }
    return local;
  }

  private static ThreadPoolExecutor buildWriter() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
          Thread thread = new Thread(runnable, "mcp-usage-telemetry");
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  /** @return false when this instance has opted out of telemetry (D28). */
  static boolean isEnabled() {
    return isPropertyEnabled(PROP_ENABLED);
  }

  /** @return false when this instance keeps the table but refuses the Mixpanel projection (B2). */
  static boolean isMixpanelEnabled() {
    return isPropertyEnabled(PROP_MIXPANEL_ENABLED);
  }

  private static boolean isPropertyEnabled(String property) {
    try {
      String raw = OBPropertiesProvider.getInstance()
          .getOpenbravoProperties()
          .getProperty(property);
      // Default ON: only an explicit, well-formed "false" turns it off.
      return !StringUtils.equalsIgnoreCase(StringUtils.trimToNull(raw), "false");
    } catch (Exception e) {
      log.debug("Could not read {}; keeping it enabled.", property, e);
      return true;
    }
  }

  /**
   * Enqueue one row for asynchronous insertion. Returns immediately and never throws: a full queue,
   * a rejected task or any other failure is swallowed, because no telemetry problem may ever reach
   * the MCP caller.
   *
   * @param row the already-resolved row; ignored when null or when telemetry is disabled
   */
  static void enqueue(McpUsageRow row) {
    if (row == null || !isEnabled()) {
      return;
    }
    try {
      WRITER.execute(() -> write(row));
    } catch (RejectedExecutionException e) {
      noteDrop("queue full or writer shut down", row.toolName());
    } catch (Throwable t) { // NOSONAR — telemetry must not escalate anything to the caller.
      noteDrop("could not enqueue", row.toolName());
      log.debug("Could not enqueue MCP telemetry row.", t);
    }
  }

  /**
   * Count one unpersisted row and warn about it, at most once per
   * {@value #DROP_WARN_INTERVAL_MS} ms after the first. Never throws — it is called from paths whose
   * whole purpose is to swallow failure.
   *
   * @param reason short description of why the row was lost, for the log line
   * @param toolName the tool whose row was lost, or null when not known
   */
  private static void noteDrop(String reason, String toolName) {
    try {
      long total = droppedRows.incrementAndGet();
      long now = System.currentTimeMillis();
      long last = lastDropWarnAt.get();
      boolean due = (last == 0L) || (now - last >= DROP_WARN_INTERVAL_MS);
      // compareAndSet so concurrent drops produce one line, not one per thread.
      if (due && lastDropWarnAt.compareAndSet(last, now)) {
        log.warn("MCP telemetry row lost ({}, tool '{}'). {} row(s) lost since startup — "
            + "ETGO_MCP_USAGE is incomplete for this period; do not read a gap as absence of "
            + "traffic.", reason, toolName, total);
      }
    } catch (Throwable t) { // NOSONAR — accounting for a lost row must not lose anything else.
      log.debug("Could not account for a dropped MCP telemetry row.", t);
    }
  }

  /** @return rows this logger failed to persist since the JVM started. */
  static long getDroppedRows() {
    return droppedRows.get();
  }

  /**
   * Stop the writer and report what never made it to the table. Called from
   * {@link McpServlet#destroy()}, i.e. on undeploy or container shutdown.
   *
   * <p>Waits {@value #SHUTDOWN_GRACE_MS} ms for the queue to drain, then counts whatever is still
   * queued as lost and says so at WARN — the second silent gap D24's argument cannot afford.</p>
   */
  static void shutdown() {
    try {
      WRITER.shutdown();
      if (!WRITER.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
        List<Runnable> pending = WRITER.shutdownNow();
        droppedRows.addAndGet(pending.size());
        log.warn("MCP telemetry stopped with {} row(s) still queued; they are lost. "
            + "{} row(s) lost in total since startup.", pending.size(), droppedRows.get());
      } else if (droppedRows.get() > 0L) {
        log.warn("MCP telemetry stopped cleanly. {} row(s) were lost since startup.",
            droppedRows.get());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Interrupted while stopping MCP telemetry.", e);
    } catch (Throwable t) { // NOSONAR — shutdown must not fail undeploy.
      log.debug("Could not stop MCP telemetry cleanly.", t);
    }
  }

  /**
   * Insert one row on the writer thread, in its own transaction on its own pooled connection.
   * Swallows every failure by design (see the class javadoc, rule 1).
   */
  private static void write(McpUsageRow row) {
    ExternalConnectionPool pool = ExternalConnectionPool.getInstance();
    if (pool == null) {
      noteDrop("no external connection pool", row.toolName());
      return;
    }
    Connection connection = null;
    try {
      connection = pool.getConnection();
      connection.setAutoCommit(false);
      try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
        bind(statement, row);
        statement.executeUpdate();
      }
      connection.commit();
      // D24 made literal: the authoritative row is committed BEFORE the projection is attempted.
      project(row);
    } catch (Throwable t) { // NOSONAR — a telemetry failure is logged and forgotten, never raised.
      rollbackQuietly(connection);
      noteDrop("insert failed", row.toolName());
      log.debug("Could not write MCP telemetry row for tool '{}'.", row.toolName(), t);
    } finally {
      closeQuietly(connection);
    }
  }

  /**
   * Emit the row's SHAPE to Mixpanel through the shared telemetry service (B2).
   *
   * <p>What travels is the same rule as the table (D25): tool, verb, target entity, field NAMES,
   * outcome, error code, latency, byte counts. What never travels is {@link McpUsageRow#payload} —
   * a feedback row's report is the agent's free text and, inside {@code failures[].payload}, the
   * literal arguments of a failing call. That is exactly the content the rule exists to keep off a
   * third party, so the projection carries only that a feedback row happened, never what it said.
   *
   * <p>Never throws: {@code NeoTelemetryService.emit} swallows sink failures, and this method
   * swallows anything else. A projection failure must not cost us the committed row.
   */
  private static void project(McpUsageRow row) {
    if (!isMixpanelEnabled()) {
      return;
    }
    try {
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("tool", row.toolName());
      props.put("verb", row.verb());
      props.put("entity", row.targetEntity());
      props.put("fieldsTouched", row.fieldsTouched());
      props.put("status", row.outcome());
      props.put("errorCode", row.errorCode());
      props.put("rowType", row.rowType());
      props.put("clientName", row.clientName());
      props.put("durationMs", row.durationMs());
      props.put("reqBytes", row.reqBytes());
      props.put("respBytes", row.respBytes());
      // row.payload() is deliberately absent and must stay absent.
      telemetry().emit(NeoTelemetryEvents.BACKEND_MCP_TOOL_CALL_COMPLETED, props);
    } catch (Throwable t) { // NOSONAR — the projection is best-effort by construction.
      log.debug("Could not project MCP telemetry for tool '{}'.", row.toolName(), t);
    }
  }

  private static void bind(PreparedStatement statement, McpUsageRow row) throws SQLException {
    int i = 1;
    statement.setString(i++, SequenceIdData.getUUID());
    statement.setString(i++, StringUtils.defaultIfBlank(row.clientId(), DEFAULT_CLIENT));
    statement.setString(i++, StringUtils.defaultIfBlank(row.orgId(), DEFAULT_ORG));
    String auditUser = StringUtils.defaultIfBlank(row.userId(), SYSTEM_USER);
    statement.setString(i++, auditUser);
    statement.setString(i++, auditUser);
    setText(statement, i++, row.sessionKey());
    setText(statement, i++, row.toolName());
    setText(statement, i++, row.verb());
    setText(statement, i++, row.targetEntity());
    // fields_touched and payload are TEXT columns: no clipping, no width to overflow.
    setClob(statement, i++, row.fieldsTouched());
    setText(statement, i++, row.outcome());
    setText(statement, i++, row.errorCode());
    setLong(statement, i++, row.durationMs());
    setLong(statement, i++, row.reqBytes());
    setLong(statement, i++, row.respBytes());
    setText(statement, i++, row.clientName());
    setText(statement, i++, row.clientVersion());
    setText(statement, i++, row.rowType());
    setClob(statement, i, row.payload());
  }

  /** Bind a VARCHAR(200) column, clipping rather than letting an oversized value fail the insert. */
  private static void setText(PreparedStatement statement, int index, String value)
      throws SQLException {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, StringUtils.abbreviate(trimmed, VARCHAR_LIMIT));
    }
  }

  private static void setClob(PreparedStatement statement, int index, String value)
      throws SQLException {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, trimmed);
    }
  }

  private static void setLong(PreparedStatement statement, int index, Long value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.NUMERIC);
    } else {
      statement.setLong(index, value);
    }
  }

  private static void rollbackQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.rollback();
    } catch (SQLException e) {
      log.debug("Could not roll back MCP telemetry connection.", e);
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
      log.debug("Could not return MCP telemetry connection to the pool.", e);
    }
  }
}
