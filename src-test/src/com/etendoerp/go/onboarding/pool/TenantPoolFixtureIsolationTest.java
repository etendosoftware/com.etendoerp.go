/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * *************************************************************************
 */
package com.etendoerp.go.onboarding.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The fixture pool reservation cannot leak into an ordinary signup claim. */
class TenantPoolFixtureIsolationTest {

  @Test
  void ordinaryClaimExcludesEveryFixtureMarkedRow() throws Exception {
    JdbcPool pool = new JdbcPool();
    when(pool.statement.executeQuery()).thenReturn(pool.result);
    when(pool.result.next()).thenReturn(false);

    assertEquals(null, pool.claimReady("version-1"));

    String sql = pool.preparedSql();
    assertTrue(sql.contains("error_message, '') NOT LIKE 'E2E fixture:%'"));
    assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"));
    verify(pool.statement).setString(1, "version-1");
  }

  @Test
  void fixtureClaimIsBoundToVersionAndRequestId() throws Exception {
    JdbcPool pool = new JdbcPool();
    when(pool.statement.executeQuery()).thenReturn(pool.result);
    when(pool.result.next()).thenReturn(true);
    when(pool.result.getString(1)).thenReturn("pool-row");
    when(pool.result.getString(2)).thenReturn("fixture-client");

    TenantPoolStore.Claim claim = pool.claimFixture("request-1", "version-1");

    assertNotNull(claim);
    assertEquals("pool-row", claim.poolRowId());
    assertEquals("fixture-client", claim.clientId());
    String sql = pool.preparedSql();
    assertTrue(sql.contains("error_message = 'E2E fixture:READY'"));
    assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"));
    verify(pool.statement).setString(1, "E2E fixture:request-1");
    verify(pool.statement).setString(2, "version-1");
  }

  @Test
  void cleanupRestoresOnlyItsOwnClaimedReservation() throws Exception {
    JdbcPool pool = new JdbcPool();
    when(pool.statement.executeUpdate()).thenReturn(0);
    assertFalse(pool.restoreReady("pool-row", "fixture-client", "wrong-request"));
    String sql = pool.preparedSql();
    assertTrue(sql.contains("status = 'CLAIMED' AND error_message = ?"));
    verify(pool.statement).setObject(1, "pool-row");
    verify(pool.statement).setObject(2, "fixture-client");
    verify(pool.statement).setObject(3, "E2E fixture:wrong-request");
  }

  private static class JdbcPool extends TenantPoolStore {
    final Connection jdbc = mock(Connection.class);
    final PreparedStatement statement = mock(PreparedStatement.class);
    final ResultSet result = mock(ResultSet.class);

    JdbcPool() throws Exception {
      when(jdbc.prepareStatement(anyString())).thenReturn(statement);
    }

    @Override
    protected Connection connection() {
      return jdbc;
    }

    String preparedSql() throws Exception {
      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(jdbc).prepareStatement(sql.capture());
      return sql.getValue();
    }
  }
}
