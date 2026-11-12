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
package com.etendoerp.go.mcp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.database.ExternalConnectionPool;

/**
 * JDBC implementation of {@link McpRoutineStore} (ETP-6236).
 *
 * <p>The same discipline as {@code BatchJobJdbcStore}: every call borrows its own pooled
 * connection, so the routine tables are never written through the DAL and never join (or are
 * rolled back with) the call's business transaction. A write that needs two statements (a new
 * routine and its first version, a new version and the routine's current number) runs them in one
 * explicit transaction on that connection.</p>
 */
final class McpRoutineJdbcStore implements McpRoutineStore {

  private static final Logger log = LogManager.getLogger(McpRoutineJdbcStore.class);

  private static final String ROUTINE_COLUMNS =
      "etgo_mcp_routine_id, name, description, current_version";

  static final String FIND_SQL = "SELECT " + ROUTINE_COLUMNS + " FROM etgo_mcp_routine "
      + "WHERE ad_client_id = ? AND owner_user_id = ? AND name = ?";

  static final String LIST_SQL = "SELECT " + ROUTINE_COLUMNS + " FROM etgo_mcp_routine "
      + "WHERE ad_client_id = ? AND owner_user_id = ? ORDER BY name";

  static final String VERSION_SQL = "SELECT version_no, params, steps, "
      + "to_char(created, 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM etgo_mcp_routine_version "
      + "WHERE etgo_mcp_routine_id = ? AND version_no = ?";

  static final String VERSION_NUMBERS_SQL = "SELECT version_no FROM etgo_mcp_routine_version "
      + "WHERE etgo_mcp_routine_id = ? ORDER BY version_no";

  static final String INSERT_ROUTINE_SQL = "INSERT INTO etgo_mcp_routine (etgo_mcp_routine_id, "
      + "ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby, "
      + "owner_user_id, name, description, current_version) "
      + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, 1)";

  static final String INSERT_VERSION_SQL = "INSERT INTO etgo_mcp_routine_version ("
      + "etgo_mcp_routine_version_id, ad_client_id, ad_org_id, isactive, created, createdby, "
      + "updated, updatedby, etgo_mcp_routine_id, version_no, params, steps) "
      + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, ?)";

  static final String BUMP_SQL = "UPDATE etgo_mcp_routine SET current_version = ?, "
      + "description = ?, updated = now(), updatedby = ? WHERE etgo_mcp_routine_id = ?";

  static final String NEXT_VERSION_SQL = "SELECT coalesce(max(version_no), 0) + 1 "
      + "FROM etgo_mcp_routine_version WHERE etgo_mcp_routine_id = ?";

  static final String DELETE_SQL = "DELETE FROM etgo_mcp_routine "
      + "WHERE ad_client_id = ? AND owner_user_id = ? AND name = ?";

  static final String INSERT_EXEC_SQL = "INSERT INTO etgo_mcp_routine_exec ("
      + "etgo_mcp_routine_exec_id, ad_client_id, ad_org_id, isactive, created, createdby, "
      + "updated, updatedby, owner_user_id, owner_role_id, routine_name, version_no, run_mode, "
      + "status, steps, result) VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  static final String FIND_EXEC_SQL = "SELECT etgo_mcp_routine_exec_id, run_mode, status, "
      + "routine_name, version_no, steps, result, "
      + "to_char(created, 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM etgo_mcp_routine_exec "
      + "WHERE ad_client_id = ? AND owner_user_id = ? AND etgo_mcp_routine_exec_id = ?";

  @Override
  public Routine find(Owner owner, String name) {
    return run(connection -> {
      List<Routine> rows = queryRoutines(connection, FIND_SQL, owner.clientId, owner.userId,
          name);
      return rows.isEmpty() ? null : rows.get(0);
    });
  }

  @Override
  public List<Routine> list(Owner owner) {
    return run(connection -> queryRoutines(connection, LIST_SQL, owner.clientId, owner.userId));
  }

  @Override
  public Version version(String routineId, int number) {
    return run(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(VERSION_SQL)) {
        statement.setString(1, routineId);
        statement.setInt(2, number);
        return readVersion(statement);
      }
    });
  }

  @Override
  public List<Integer> versionNumbers(String routineId) {
    return run(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(VERSION_NUMBERS_SQL)) {
        statement.setString(1, routineId);
        return readInts(statement);
      }
    });
  }

  @Override
  public Routine create(Owner owner, String name, String description, String params,
      String steps) {
    String routineId = newId();
    return inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(INSERT_ROUTINE_SQL)) {
        int i = 1;
        statement.setString(i++, routineId);
        statement.setString(i++, owner.clientId);
        statement.setString(i++, owner.orgId);
        statement.setString(i++, owner.userId);
        statement.setString(i++, owner.userId);
        statement.setString(i++, owner.userId);
        statement.setString(i++, name);
        statement.setString(i, description);
        statement.executeUpdate();
      }
      insertVersion(connection, owner, routineId, 1, params, steps);
      return new Routine(routineId, name, description, 1);
    });
  }

  @Override
  public int addVersion(Owner owner, Routine routine, String description, String params,
      String steps) {
    return inTransaction(connection -> {
      int number;
      try (PreparedStatement statement = connection.prepareStatement(NEXT_VERSION_SQL)) {
        statement.setString(1, routine.id);
        number = readInts(statement).get(0);
      }
      insertVersion(connection, owner, routine.id, number, params, steps);
      try (PreparedStatement statement = connection.prepareStatement(BUMP_SQL)) {
        statement.setInt(1, number);
        statement.setString(2, description);
        statement.setString(3, owner.userId);
        statement.setString(4, routine.id);
        statement.executeUpdate();
      }
      return number;
    });
  }

  @Override
  public boolean delete(Owner owner, String name) {
    return run(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(DELETE_SQL)) {
        statement.setString(1, owner.clientId);
        statement.setString(2, owner.userId);
        statement.setString(3, name);
        return statement.executeUpdate() > 0;
      }
    });
  }

  @Override
  public String recordExecution(Owner owner, Execution execution) {
    String executionId = newId();
    run(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(INSERT_EXEC_SQL)) {
        int i = 1;
        statement.setString(i++, executionId);
        statement.setString(i++, owner.clientId);
        statement.setString(i++, owner.orgId);
        statement.setString(i++, owner.userId);
        statement.setString(i++, owner.userId);
        statement.setString(i++, owner.userId);
        statement.setString(i++, owner.roleId);
        statement.setString(i++, execution.routineName);
        if (execution.version == null) {
          statement.setNull(i++, Types.NUMERIC);
        } else {
          statement.setInt(i++, execution.version);
        }
        statement.setString(i++, execution.mode);
        statement.setString(i++, execution.status);
        statement.setString(i++, execution.steps);
        statement.setString(i, execution.result);
        return statement.executeUpdate();
      }
    });
    return executionId;
  }

  @Override
  public Execution findExecution(Owner owner, String executionId) {
    return run(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(FIND_EXEC_SQL)) {
        statement.setString(1, owner.clientId);
        statement.setString(2, owner.userId);
        statement.setString(3, executionId);
        return readExecution(statement);
      }
    });
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static void insertVersion(Connection connection, Owner owner, String routineId,
      int number, String params, String steps) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_VERSION_SQL)) {
      int i = 1;
      statement.setString(i++, newId());
      statement.setString(i++, owner.clientId);
      statement.setString(i++, owner.orgId);
      statement.setString(i++, owner.userId);
      statement.setString(i++, owner.userId);
      statement.setString(i++, routineId);
      statement.setInt(i++, number);
      statement.setString(i++, params);
      statement.setString(i, steps);
      statement.executeUpdate();
    }
  }

  private static List<Routine> queryRoutines(Connection connection, String sql,
      String... params) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setString(i + 1, params[i]);
      }
      return readRoutines(statement);
    }
  }

  private static List<Routine> readRoutines(PreparedStatement statement) throws SQLException {
    List<Routine> rows = new ArrayList<>();
    try (ResultSet rs = statement.executeQuery()) {
      while (rs.next()) {
        rows.add(new Routine(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
      }
    }
    return rows;
  }

  private static Version readVersion(PreparedStatement statement) throws SQLException {
    try (ResultSet rs = statement.executeQuery()) {
      return rs.next()
          ? new Version(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4))
          : null;
    }
  }

  private static List<Integer> readInts(PreparedStatement statement) throws SQLException {
    List<Integer> values = new ArrayList<>();
    try (ResultSet rs = statement.executeQuery()) {
      while (rs.next()) {
        values.add(rs.getInt(1));
      }
    }
    return values;
  }

  private static Execution readExecution(PreparedStatement statement) throws SQLException {
    try (ResultSet rs = statement.executeQuery()) {
      if (!rs.next()) {
        return null;
      }
      int version = rs.getInt(5);
      Integer versionNo = rs.wasNull() ? null : version;
      return new Execution(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
          versionNo, rs.getString(6), rs.getString(7), rs.getString(8));
    }
  }

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase();
  }

  /** A JDBC step that may throw {@link SQLException}. */
  @FunctionalInterface
  private interface Work<T> {
    T apply(Connection connection) throws SQLException;
  }

  /** Runs one step on its own autocommit pooled connection. */
  private static <T> T run(Work<T> work) {
    try (Connection connection = connection()) {
      connection.setAutoCommit(true);
      return work.apply(connection);
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  /** Runs several statements as one transaction on their own pooled connection. */
  private static <T> T inTransaction(Work<T> work) {
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.apply(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  private static Connection connection() throws SQLException {
    ExternalConnectionPool pool = ExternalConnectionPool.getInstance();
    if (pool == null) {
      throw new IllegalStateException("No external connection pool: routines are unavailable");
    }
    return pool.getConnection();
  }

  private static IllegalStateException failure(SQLException e) {
    log.warn("[ROUTINE] store call failed: {}", e.getMessage());
    return new IllegalStateException("Routine store failure: " + e.getMessage(), e);
  }
}
