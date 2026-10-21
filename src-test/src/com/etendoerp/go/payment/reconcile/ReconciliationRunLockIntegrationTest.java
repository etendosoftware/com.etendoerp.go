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
package com.etendoerp.go.payment.reconcile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Test;
import org.openbravo.database.ExternalConnectionPool;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.payment.TestLogCapture;

/**
 * Real-database specs for {@link ReconciliationRunLock}, the advisory lock that keeps two billing
 * reconciliation runs from overlapping. {@code ReconciliationRunLockTest} pins the JDBC calls
 * against mocks; only PostgreSQL can show that a second holder is really refused while the first
 * holds the lock, and that closing the first frees it.
 *
 * <p>Writes nothing: an advisory lock lives only in the server's lock table, and every connection
 * this class opens is closed in a {@code finally} (a closed connection ends its transaction, and
 * the lock with it). If a real reconciliation runs on the same database at that moment, it holds
 * the lock and the first assertion below fails; nothing else is affected.
 *
 * @covers com.etendoerp.go.payment.reconcile.ReconciliationRunLock
 */
public class ReconciliationRunLockIntegrationTest extends OBBaseTest {

  private ReconciliationRunLock.Held first;
  private ReconciliationRunLock.Held second;

  @After
  public void releaseEverything() {
    for (ReconciliationRunLock.Held held : new ReconciliationRunLock.Held[] { first, second }) {
      if (held != null) {
        held.close();
      }
    }
  }

  @Test
  public void aSecondRunIsRefusedWhileTheFirstHoldsTheLockAndAdmittedOnceItIsReleased()
      throws Exception {
    first = new ReconciliationRunLock().tryAcquire();
    assertNotNull("The first run takes the lock", first);
    assertNotSame("A real connection backs it", ReconciliationRunLock.Held.UNLOCKED, first);
    assertEquals("Visible to the server as one granted advisory lock", 1L, grantedRunLocks());

    assertNull("A second run does not wait: it is told another one runs",
        new ReconciliationRunLock().tryAcquire());

    first.close();
    first = null;
    assertEquals("Closing the run releases the lock", 0L, grantedRunLocks());
    second = new ReconciliationRunLock().tryAcquire();
    assertNotNull("The next run takes it again", second);
    assertNotSame(ReconciliationRunLock.Held.UNLOCKED, second);
  }

  @Test
  public void withoutAConnectionTheRunGoesAheadUnlockedWithAWarning() {
    TestLogCapture log = TestLogCapture.attachTo(ReconciliationRunLock.class, Level.WARN);
    try {
      first = new ReconciliationRunLock(() -> {
        throw new IllegalStateException("pool exhausted");
      }).tryAcquire();

      assertSame(ReconciliationRunLock.Held.UNLOCKED, first);
      assertTrue(log.messagesAt(Level.WARN).toString(), log.messagesAt(Level.WARN).contains(
          "[BILLING-RECONCILE] No dedicated connection for the run lock; running without it"));
    } finally {
      log.detach();
    }
  }

  @Test
  public void aConnectionThatCannotTakeTheLockFailsOpenWithAWarning() throws Exception {
    Connection broken = ExternalConnectionPool.getInstance().getConnection();
    broken.close();
    TestLogCapture log = TestLogCapture.attachTo(ReconciliationRunLock.class, Level.WARN);
    try {
      first = new ReconciliationRunLock(() -> broken).tryAcquire();

      assertSame(ReconciliationRunLock.Held.UNLOCKED, first);
      assertTrue(log.messagesAt(Level.WARN).toString(), log.messagesAt(Level.WARN).stream()
          .anyMatch(line -> line.startsWith("[BILLING-RECONCILE] The run lock could not be "
              + "taken")));
    } finally {
      log.detach();
    }
  }

  /** @return how many sessions hold the run lock now, read on a connection of its own */
  private static long grantedRunLocks() throws SQLException {
    try (Connection connection = ExternalConnectionPool.getInstance().getConnection();
        PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM pg_locks "
            + "WHERE locktype = 'advisory' AND classid = ? AND objid = ? AND granted")) {
      query.setInt(1, ReconciliationRunLock.LOCK_NAMESPACE);
      query.setInt(2, ReconciliationRunLock.LOCK_ID);
      try (ResultSet answer = query.executeQuery()) {
        answer.next();
        return answer.getLong(1);
      }
    }
  }
}
