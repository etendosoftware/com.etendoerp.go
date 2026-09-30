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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runs a best-effort unit of JDBC work under a savepoint on the request's shared connection
 * (ETP-5547). On PostgreSQL the first failed statement aborts the whole transaction; a caller that
 * logs and swallows that failure would otherwise answer 2xx and then lose every other write of the
 * request at commit time. Rolling back to the savepoint undoes only the failed work and leaves the
 * transaction usable. Same pattern as {@code BusinessPartnerHandler#applySearchKeyUpdate}.
 *
 * <p>Meant for raw JDBC work. When {@code work} flushes Hibernate, the caller must clear the
 * session on failure: rolling back the rows a flush wrote does not roll back the session's belief
 * that they are persisted, so the next flush would replay or contradict them.
 */
final class JdbcSavepoints {

  private static final Logger log = LogManager.getLogger(JdbcSavepoints.class);

  private JdbcSavepoints() {
  }

  /** A unit of JDBC work that {@link #run} protects. */
  @FunctionalInterface
  interface Work<E extends Exception> {
    /**
     * Performs the JDBC work. Any exception it throws makes {@link JdbcSavepoints#run} roll the
     * connection back to the savepoint before rethrowing it.
     *
     * @throws E when the work fails
     */
    void run() throws E;
  }

  /**
   * Runs {@code work} under a savepoint on {@code conn}. On failure the connection is rolled back to
   * the savepoint and the original exception is rethrown; on success the savepoint is released.
   *
   * @throws SQLException when the savepoint itself cannot be set (e.g. the transaction is already
   *     aborted), or whatever {@code work} throws
   */
  static <E extends Exception> void run(Connection conn, Work<E> work) throws E, SQLException {
    Savepoint savepoint = conn.setSavepoint();
    try {
      work.run();
    } catch (Exception e) {
      rollbackQuietly(conn, savepoint, e);
      throw e;
    }
    releaseQuietly(conn, savepoint);
  }

  private static void rollbackQuietly(Connection conn, Savepoint savepoint, Exception cause) {
    try {
      conn.rollback(savepoint);
    } catch (SQLException rollbackError) {
      // Keep the original failure as the one the caller sees.
      cause.addSuppressed(rollbackError);
      log.error("[ETP-5547] Could not roll back to savepoint; the request transaction may be"
          + " aborted", rollbackError);
    }
  }

  private static void releaseQuietly(Connection conn, Savepoint savepoint) {
    try {
      conn.releaseSavepoint(savepoint);
    } catch (SQLException e) {
      // Releasing is housekeeping only: the savepoint disappears at commit anyway.
      log.debug("[ETP-5547] Could not release savepoint: {}", e.getMessage());
    }
  }
}
