/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

/**
 * Specs for {@link ReconciliationRunLock} (review F4): one billing reconciliation at a time, held
 * as a transaction-scoped advisory lock on a dedicated connection, never waited for, and failing
 * open when no connection can be had. A new class, so a new spec file.
 *
 * @covers com.etendoerp.go.payment.reconcile.ReconciliationRunLock
 */
public class ReconciliationRunLockTest {

  private Connection connection;
  private PreparedStatement statement;
  private ResultSet answer;

  @Before
  public void setUp() throws SQLException {
    connection = mock(Connection.class);
    statement = mock(PreparedStatement.class);
    answer = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(answer);
    when(answer.next()).thenReturn(true);
  }

  @Test
  public void aFreeLockIsTakenOnTheFixedKeyAndReleasedByEndingTheTransaction() throws Exception {
    when(answer.getBoolean(1)).thenReturn(true);

    ReconciliationRunLock.Held held = new ReconciliationRunLock(() -> connection).tryAcquire();

    assertNotNull(held);
    verify(connection).setAutoCommit(false);
    verify(connection).prepareStatement("SELECT pg_try_advisory_xact_lock(?, ?)");
    verify(statement).setInt(1, ReconciliationRunLock.LOCK_NAMESPACE);
    verify(statement).setInt(2, ReconciliationRunLock.LOCK_ID);

    held.close();

    InOrder release = inOrder(connection);
    release.verify(connection).rollback();
    release.verify(connection).close();
  }

  @Test
  public void aLockAnotherRunHoldsIsNotWaitedForAndTheConnectionIsGivenBack() throws Exception {
    when(answer.getBoolean(1)).thenReturn(false);

    assertNull(new ReconciliationRunLock(() -> connection).tryAcquire());

    verify(connection).rollback();
    verify(connection).close();
  }

  @Test
  public void withoutAConnectionTheRunGoesAheadUnlocked() {
    assertSame(ReconciliationRunLock.Held.UNLOCKED,
        new ReconciliationRunLock(() -> null).tryAcquire());
    assertSame(ReconciliationRunLock.Held.UNLOCKED, new ReconciliationRunLock(() -> {
      throw new IllegalStateException("no pool");
    }).tryAcquire());
    ReconciliationRunLock.Held.UNLOCKED.close();
  }

  @Test
  public void aFailingLockQueryFailsOpenAndGivesTheConnectionBack() throws Exception {
    when(statement.executeQuery()).thenThrow(new SQLException("function does not exist"));

    assertSame(ReconciliationRunLock.Held.UNLOCKED,
        new ReconciliationRunLock(() -> connection).tryAcquire());
    verify(connection).close();
  }

  @Test
  public void aFailedReleaseStillClosesTheConnection() throws Exception {
    when(answer.getBoolean(1)).thenReturn(true);
    org.mockito.Mockito.doThrow(new SQLException("broken")).when(connection).rollback();

    new ReconciliationRunLock(() -> connection).tryAcquire().close();

    verify(connection).close();
  }
}
