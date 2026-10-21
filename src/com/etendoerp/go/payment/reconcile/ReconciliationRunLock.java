/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.database.ExternalConnectionPool;

/**
 * ETP-5048 (review F4) — at most one billing reconciliation runs at a time. A menu launch and a
 * scheduled run can overlap; the second would then re-read rows the first is repairing and report
 * false errors (a close "already recorded by an earlier run").
 *
 * <p><b>How.</b> A PostgreSQL transaction-scoped advisory lock ({@code pg_try_advisory_xact_lock}
 * on a fixed key), held on a <em>dedicated</em> pooled connection for the whole run. Not on the DAL
 * session: every phase commits and closes it, which would end a transaction-scoped lock at the
 * first commit, and a session-level lock would stay with whichever pooled connection the session
 * happened to use. The dedicated connection keeps one transaction open (auto-commit off) until
 * {@link Held#close}, whose rollback releases the lock; should that rollback fail, the connection
 * is broken and the server ends its transaction, and the lock with it. A second run does not
 * wait: {@link #tryAcquire} answers null at once. {@code com.etendoerp.go} is PostgreSQL-only.
 *
 * <p><b>Fails open.</b> When no dedicated connection can be had, the run goes ahead unlocked with a
 * WARN: every repair is fenced or idempotent on its own, so an overlap costs false error reports,
 * never a double repair, while failing closed would stop the reconciliation altogether.
 */
class ReconciliationRunLock {

  private static final Logger log = LogManager.getLogger(ReconciliationRunLock.class);

  /** The advisory-lock key, namespaced by the ticket so it collides with no other user. */
  static final int LOCK_NAMESPACE = 5048;
  static final int LOCK_ID = 1;
  private static final String TRY_LOCK = "SELECT pg_try_advisory_xact_lock(?, ?)";

  private final Supplier<Connection> connections;

  /** Production wiring: a dedicated connection from the external pool. */
  ReconciliationRunLock() {
    this(() -> ExternalConnectionPool.getInstance().getConnection());
  }

  /**
   * @param connections supplies the dedicated connection; null or a failure means "no lock"
   */
  ReconciliationRunLock(Supplier<Connection> connections) {
    this.connections = connections;
  }

  /**
   * Takes the run lock, without waiting.
   *
   * @return the held lock, to close when the run ends; {@link Held#UNLOCKED} when no connection
   *     could be had (fails open); null when another run holds it
   */
  Held tryAcquire() {
    Connection connection;
    try {
      connection = connections.get();
    } catch (RuntimeException e) {
      connection = null;
    }
    if (connection == null) {
      log.warn("{} No dedicated connection for the run lock; running without it",
          ReconciliationAudit.LOG_PREFIX);
      return Held.UNLOCKED;
    }
    try {
      connection.setAutoCommit(false);
      try (PreparedStatement lock = connection.prepareStatement(TRY_LOCK)) {
        lock.setInt(1, LOCK_NAMESPACE);
        lock.setInt(2, LOCK_ID);
        try (ResultSet answer = lock.executeQuery()) {
          if (answer.next() && answer.getBoolean(1)) {
            return new Held(connection);
          }
        }
      }
      new Held(connection).close();
      return null;
    } catch (SQLException e) {
      log.warn("{} The run lock could not be taken ({}); running without it",
          ReconciliationAudit.LOG_PREFIX, e.getMessage());
      new Held(connection).close();
      return Held.UNLOCKED;
    }
  }

  /** A held run lock: closing it ends the dedicated transaction, which releases the lock. */
  static class Held implements AutoCloseable {

    /** No lock is held (see the fail-open rule); closing it does nothing. */
    static final Held UNLOCKED = new Held(null);

    private final Connection connection;

    Held(Connection connection) {
      this.connection = connection;
    }

    @Override
    public void close() {
      if (connection == null) {
        return;
      }
      try {
        connection.rollback();
        connection.setAutoCommit(true);
      } catch (SQLException e) {
        log.warn("{} Releasing the run lock failed ({}); the connection is closed, which ends "
            + "its transaction", ReconciliationAudit.LOG_PREFIX, e.getMessage());
      } finally {
        try {
          connection.close();
        } catch (SQLException e) {
          log.debug("Closing the run-lock connection failed", e);
        }
      }
    }
  }
}
