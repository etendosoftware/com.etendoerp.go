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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.database.ExternalConnectionPool;

/**
 * JDBC implementation of {@link BatchJobStore} over {@code ETGO_BATCH_JOB} (ETP-5677).
 *
 * <p>Every call borrows its own autocommit connection from {@link ExternalConnectionPool} and
 * returns it in a try-with-resources: the job table is deliberately <i>not</i> written through the
 * DAL, so a progress write is visible to a status poll while a row's DAL transaction is still open
 * and can never join (or be rolled back with) it.</p>
 */
final class BatchJobJdbcStore implements BatchJobStore {

  private static final Logger log = LogManager.getLogger(BatchJobJdbcStore.class);

  /** Columns read by {@link #find(String)}. */
  static final String SELECT_SQL =
      "SELECT etgo_batch_job_id, ad_client_id, ad_org_id, owner_user_id, owner_role_id, channel, "
          + "state, total_rows, total_ops, processed_rows, cancel_requested, result "
          + "FROM etgo_batch_job WHERE etgo_batch_job_id = ?";

  static final String CONTROL_SQL =
      "SELECT state, cancel_requested FROM etgo_batch_job WHERE etgo_batch_job_id = ?";

  /** New job: QUEUED with a fresh heartbeat. */
  static final String INSERT_SQL =
      "INSERT INTO etgo_batch_job (etgo_batch_job_id, ad_client_id, ad_org_id, isactive, created, "
          + "createdby, updated, updatedby, state, channel, owner_user_id, owner_role_id, "
          + "total_rows, total_ops, processed_rows, cancel_requested, node_id, heartbeat) "
          + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, 'QUEUED', ?, ?, ?, ?, ?, 0, 'N', ?, now())";

  static final String CLAIM_SQL =
      "UPDATE etgo_batch_job SET state = 'RUNNING', heartbeat = now(), updated = now() "
          + "WHERE etgo_batch_job_id = ? AND state = 'QUEUED'";

  static final String FLUSH_SQL =
      "UPDATE etgo_batch_job SET processed_rows = ?, result = ?, heartbeat = now(), "
          + "updated = now() WHERE etgo_batch_job_id = ? AND state = 'RUNNING'";

  static final String CANCEL_SQL =
      "UPDATE etgo_batch_job SET cancel_requested = 'Y', updated = now() "
          + "WHERE etgo_batch_job_id = ? AND state IN ('QUEUED', 'RUNNING')";

  static final String SWEEP_SQL =
      "UPDATE etgo_batch_job SET state = 'INTERRUPTED', finished = now(), updated = now() "
          + "WHERE state IN ('QUEUED', 'RUNNING') "
          + "AND heartbeat < now() - (? * interval '1 second')";

  static final String PURGE_SQL =
      "DELETE FROM etgo_batch_job WHERE state NOT IN ('QUEUED', 'RUNNING') "
          + "AND finished < now() - (? * interval '1 hour')";

  static final String DELETE_SQL = "DELETE FROM etgo_batch_job WHERE etgo_batch_job_id = ?";

  private static final String COUNT_USER_SQL =
      "SELECT count(*) FROM etgo_batch_job WHERE ad_client_id = ? AND owner_user_id = ? "
          + "AND state IN ('QUEUED', 'RUNNING')";

  private static final String COUNT_CLIENT_SQL =
      "SELECT count(*) FROM etgo_batch_job WHERE ad_client_id = ? AND state IN ('QUEUED', 'RUNNING')";

  /** Per-JVM id written to the row for diagnostics only; liveness is the heartbeat. */
  static final String NODE_ID = UUID.randomUUID().toString().replace("-", "");

  @Override
  public void insert(JobRecord job) {
    run(c -> {
      try (PreparedStatement s = c.prepareStatement(INSERT_SQL)) {
        int i = 1;
        s.setString(i++, job.id);
        s.setString(i++, job.clientId);
        s.setString(i++, job.orgId);
        s.setString(i++, job.userId);
        s.setString(i++, job.userId);
        s.setString(i++, job.channel);
        s.setString(i++, job.userId);
        s.setString(i++, job.roleId);
        s.setInt(i++, job.totalRows);
        s.setInt(i++, job.totalOps);
        s.setString(i, NODE_ID);
        return s.executeUpdate();
      }
    });
  }

  @Override
  public JobRecord find(String jobId) {
    return run(c -> {
      try (PreparedStatement s = c.prepareStatement(SELECT_SQL)) {
        s.setString(1, jobId);
        try (ResultSet rs = s.executeQuery()) {
          if (!rs.next()) {
            return null;
          }
          JobRecord job = new JobRecord();
          job.id = rs.getString(1);
          job.clientId = rs.getString(2);
          job.orgId = rs.getString(3);
          job.userId = rs.getString(4);
          job.roleId = rs.getString(5);
          job.channel = rs.getString(6);
          job.state = BatchJobState.valueOf(rs.getString(7));
          job.totalRows = rs.getInt(8);
          job.totalOps = rs.getInt(9);
          job.processedRows = rs.getInt(10);
          job.cancelRequested = "Y".equals(rs.getString(11));
          job.result = rs.getString(12);
          return job;
        }
      }
    });
  }

  @Override
  public Control readControl(String jobId) {
    return run(c -> {
      try (PreparedStatement s = c.prepareStatement(CONTROL_SQL)) {
        s.setString(1, jobId);
        try (ResultSet rs = s.executeQuery()) {
          return rs.next()
              ? new Control(BatchJobState.valueOf(rs.getString(1)), "Y".equals(rs.getString(2)))
              : null;
        }
      }
    });
  }

  @Override
  public int countActiveForUser(String clientId, String userId) {
    return count(COUNT_USER_SQL, clientId, userId);
  }

  @Override
  public int countActiveForClient(String clientId) {
    return count(COUNT_CLIENT_SQL, clientId);
  }

  @Override
  public boolean claim(String jobId) {
    return update(CLAIM_SQL, jobId) > 0;
  }

  @Override
  public boolean flush(String jobId, int processedRows, String result) {
    return run(c -> {
      try (PreparedStatement s = c.prepareStatement(FLUSH_SQL)) {
        s.setInt(1, processedRows);
        s.setString(2, result);
        s.setString(3, jobId);
        return s.executeUpdate();
      }
    }) > 0;
  }

  @Override
  public boolean transition(String jobId, BatchJobState to, Collection<BatchJobState> from,
      String result) {
    StringBuilder sql = new StringBuilder("UPDATE etgo_batch_job SET state = ?, updated = now()");
    if (to.isTerminal()) {
      sql.append(", finished = now()");
    }
    if (result != null) {
      sql.append(", result = ?");
    }
    sql.append(" WHERE etgo_batch_job_id = ? AND state IN (");
    for (int i = 0; i < from.size(); i++) {
      sql.append(i == 0 ? "?" : ", ?");
    }
    sql.append(')');
    return run(c -> {
      try (PreparedStatement s = c.prepareStatement(sql.toString())) {
        int i = 1;
        s.setString(i++, to.name());
        if (result != null) {
          s.setString(i++, result);
        }
        s.setString(i++, jobId);
        for (BatchJobState state : from) {
          s.setString(i++, state.name());
        }
        return s.executeUpdate();
      }
    }) > 0;
  }

  @Override
  public boolean requestCancel(String jobId) {
    return update(CANCEL_SQL, jobId) > 0;
  }

  @Override
  public void heartbeat(Collection<String> jobIds) {
    if (jobIds.isEmpty()) {
      return;
    }
    StringBuilder sql = new StringBuilder(
        "UPDATE etgo_batch_job SET heartbeat = now() WHERE state IN ('QUEUED', 'RUNNING') "
            + "AND etgo_batch_job_id IN (");
    for (int i = 0; i < jobIds.size(); i++) {
      sql.append(i == 0 ? "?" : ", ?");
    }
    sql.append(')');
    run(c -> {
      try (PreparedStatement s = c.prepareStatement(sql.toString())) {
        int i = 1;
        for (String id : jobIds) {
          s.setString(i++, id);
        }
        return s.executeUpdate();
      }
    });
  }

  @Override
  public int sweepStale(int staleSeconds) {
    return run(c -> {
      try (PreparedStatement s = c.prepareStatement(SWEEP_SQL)) {
        s.setInt(1, staleSeconds);
        return s.executeUpdate();
      }
    });
  }

  @Override
  public int purgeFinished(int retentionHours) {
    return run(c -> {
      try (PreparedStatement s = c.prepareStatement(PURGE_SQL)) {
        s.setInt(1, retentionHours);
        return s.executeUpdate();
      }
    });
  }

  @Override
  public void delete(String jobId) {
    update(DELETE_SQL, jobId);
  }

  private int update(String sql, String id) {
    return run(c -> {
      try (PreparedStatement s = c.prepareStatement(sql)) {
        s.setString(1, id);
        return s.executeUpdate();
      }
    });
  }

  private int count(String sql, String... params) {
    return run(c -> {
      try (PreparedStatement s = c.prepareStatement(sql)) {
        for (int i = 0; i < params.length; i++) {
          s.setString(i + 1, params[i]);
        }
        try (ResultSet rs = s.executeQuery()) {
          return rs.next() ? rs.getInt(1) : 0;
        }
      }
    });
  }

  /** A JDBC step that may throw {@link SQLException}. */
  @FunctionalInterface
  private interface Work<T> {
    T apply(Connection connection) throws SQLException;
  }

  /** Runs one step on its own autocommit pooled connection. */
  private static <T> T run(Work<T> work) {
    ExternalConnectionPool pool = ExternalConnectionPool.getInstance();
    if (pool == null) {
      throw new IllegalStateException("No external connection pool: batch jobs are unavailable");
    }
    try (Connection connection = pool.getConnection()) {
      connection.setAutoCommit(true);
      return work.apply(connection);
    } catch (SQLException e) {
      log.warn("[BATCH-JOB] store call failed: {}", e.getMessage());
      throw new IllegalStateException("Batch job store failure: " + e.getMessage(), e);
    }
  }
}
