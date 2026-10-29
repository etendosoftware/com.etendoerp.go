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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.Session;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Unit tests for {@link NeoBatchScope}: the per-request lookup memo, the request options it
 * carries and its lifetime on the (pooled) request thread (ETP-5676).
 *
 * @covers com.etendoerp.go.schemaforge.NeoBatchScope
 */
public class NeoBatchScopeTest {

  /** A failed assertion must not leave a scope on the pooled test thread for the next test. */
  @After
  public void clearLeakedScope() {
    while (NeoBatchScope.current() != null) {
      NeoBatchScope.close(null);
    }
  }

  @Test
  public void testMemoizeLoadsOncePerKeyInsideABatchAndNeverOutside() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));
      AtomicInteger loads = new AtomicInteger();

      NeoBatchScope.memoize("k", loads::incrementAndGet);
      NeoBatchScope.memoize("k", loads::incrementAndGet);
      assertEquals("outside a batch every call loads", 2, loads.get());

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        assertEquals(Integer.valueOf(3), NeoBatchScope.memoize("k", loads::incrementAndGet));
        assertEquals(Integer.valueOf(3), NeoBatchScope.memoize("k", loads::incrementAndGet));
        NeoBatchScope.memoize("other", loads::incrementAndGet);
        assertEquals("one load per distinct key", 4, loads.get());
      } finally {
        NeoBatchScope.close(previous);
      }
      assertNull("the scope dies with the request", NeoBatchScope.current());

      NeoBatchScope next = NeoBatchScope.open(false);
      try {
        NeoBatchScope.memoize("k", loads::incrementAndGet);
        assertEquals("a new request starts with an empty memo", 5, loads.get());
      } finally {
        NeoBatchScope.close(next);
      }
    }
  }

  /** The close-time summary counts hits and misses per namespace (class|lookup), not per key. */
  @Test
  public void testMemoizeCountsHitsAndMissesPerNamespace() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        NeoBatchScope.memoize("a.Owner|spec|x", () -> 1);
        NeoBatchScope.memoize("a.Owner|spec|x", () -> 1);
        NeoBatchScope.memoize("a.Owner|spec|y", () -> 2);
        int[] spec = NeoBatchScope.currentMemoStats().get("a.Owner|spec");
        assertEquals(1, spec[0]);
        assertEquals(2, spec[1]);
      } finally {
        NeoBatchScope.close(previous);
      }
    }
  }

  /** A process that commits mid-batch closes the session; DAL objects loaded before are stale. */
  @Test
  public void testMemoizeForgetsEverythingWhenTheSessionChanges() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class), mock(Session.class));
      AtomicInteger loads = new AtomicInteger();

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        NeoBatchScope.memoize("k", loads::incrementAndGet);
        NeoBatchScope.memoize("k", loads::incrementAndGet);
        assertEquals(2, loads.get());
      } finally {
        NeoBatchScope.close(previous);
      }
    }
  }

  /**
   * After the session change the memo refills in the new session and is reused from there: the
   * clear is a one-off per session, not a permanent bypass.
   */
  @Test
  public void testMemoizeRefillsInTheNewSessionAndReusesIt() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Session before = mock(Session.class);
      Session after = mock(Session.class);
      when(dal.getSession()).thenReturn(before, before, after, after);
      AtomicInteger loads = new AtomicInteger();

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        assertEquals(Integer.valueOf(1), NeoBatchScope.memoize("k", loads::incrementAndGet));
        assertEquals(Integer.valueOf(1), NeoBatchScope.memoize("k", loads::incrementAndGet));
        assertEquals("the commit closed the session: the stale answer is dropped",
            Integer.valueOf(2), NeoBatchScope.memoize("k", loads::incrementAndGet));
        assertEquals("and the fresh one is reused in the new session",
            Integer.valueOf(2), NeoBatchScope.memoize("k", loads::incrementAndGet));
        assertEquals(2, loads.get());
      } finally {
        NeoBatchScope.close(previous);
      }
    }
  }

  /** A lookup that answers "nothing" is not repeated for every operation of the batch either. */
  @Test
  public void testMemoizeRemembersANullAnswer() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      AtomicInteger loads = new AtomicInteger();

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        assertNull(NeoBatchScope.memoize("k", () -> {
          loads.incrementAndGet();
          return null;
        }));
        assertNull(NeoBatchScope.memoize("k", () -> {
          loads.incrementAndGet();
          return null;
        }));
        assertEquals(1, loads.get());
      } finally {
        NeoBatchScope.close(previous);
      }
    }
  }

  /**
   * A DAL that cannot hand out a session (no context yet) does not break the memo: the session is
   * then treated as one stable "none", and the answer is still reused.
   */
  @Test
  public void testMemoizeStillWorksWhenTheSessionCannotBeRead() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenThrow(new IllegalStateException("no DAL"));
      AtomicInteger loads = new AtomicInteger();

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        NeoBatchScope.memoize("k", loads::incrementAndGet);
        NeoBatchScope.memoize("k", loads::incrementAndGet);
        assertEquals(1, loads.get());
      } finally {
        NeoBatchScope.close(previous);
      }
    }
  }

  @Test
  public void testScopeCarriesTheOnlyReturnIdsOption() {
    NeoBatchScope previous = NeoBatchScope.open(true);
    try {
      assertTrue(NeoBatchScope.current().isOnlyReturnIds());
    } finally {
      NeoBatchScope.close(previous);
    }
    previous = NeoBatchScope.open(false);
    try {
      assertFalse(NeoBatchScope.current().isOnlyReturnIds());
    } finally {
      NeoBatchScope.close(previous);
    }
  }

  /**
   * Closing an inner scope puts the outer one back — with its own options and its own memory —
   * and closing the outer one leaves the thread clean.
   */
  @Test
  public void testCloseRestoresThePreviousScope() {
    assertNull(NeoBatchScope.open(true, true));
    NeoBatchScope outer = NeoBatchScope.current();
    NeoCalloutDiscardMemory outerMemory = NeoBatchScope.currentCalloutDiscardMemory();

    NeoBatchScope previous = NeoBatchScope.open(false, true);
    assertSame(outer, previous);
    assertNotSame(outer, NeoBatchScope.current());
    assertNotSame(outerMemory, NeoBatchScope.currentCalloutDiscardMemory());
    NeoBatchScope.close(previous);

    assertSame(outer, NeoBatchScope.current());
    assertTrue(NeoBatchScope.current().isOnlyReturnIds());
    assertSame(outerMemory, NeoBatchScope.currentCalloutDiscardMemory());

    NeoBatchScope.close(null);
    assertNull(NeoBatchScope.current());
  }

  /** Outside a batch there is no memory, so the create cascade runs every callout as before. */
  @Test
  public void testCalloutDiscardMemoryExistsOnlyInsideABatchAndIsFreshPerRequest() {
    assertNull(NeoBatchScope.currentCalloutDiscardMemory());

    NeoBatchScope previous = NeoBatchScope.open(false, true);
    NeoCalloutDiscardMemory first;
    try {
      first = NeoBatchScope.currentCalloutDiscardMemory();
      assertNotNull(first);
      assertSame("one memory for the whole request", first,
          NeoBatchScope.currentCalloutDiscardMemory());
    } finally {
      NeoBatchScope.close(previous);
    }
    assertNull(NeoBatchScope.currentCalloutDiscardMemory());

    previous = NeoBatchScope.open(false, true);
    try {
      assertNotSame("a new request does not inherit what the last one learned", first,
          NeoBatchScope.currentCalloutDiscardMemory());
    } finally {
      NeoBatchScope.close(previous);
    }
  }

  /**
   * The skip is opt-in: a batch sent without {@code _skipRedundantCallouts} has no memory, so the
   * cascade runs every callout exactly as on develop.
   */
  @Test
  public void testCalloutDiscardMemoryIsAbsentWithoutTheOptIn() {
    NeoBatchScope previous = NeoBatchScope.open(true);
    try {
      assertNull(NeoBatchScope.currentCalloutDiscardMemory());
    } finally {
      NeoBatchScope.close(previous);
    }
    previous = NeoBatchScope.open(false, false);
    try {
      assertNull(NeoBatchScope.currentCalloutDiscardMemory());
    } finally {
      NeoBatchScope.close(previous);
    }
  }

  /**
   * The {@code batch-callout-skip-disabled} kill switch wins over the request: with it on, a batch
   * that opted in still gets no memory and runs every callout.
   */
  @Test
  public void testKillSwitchDisablesTheSkipEvenWhenTheRequestOptsIn() {
    try (MockedStatic<GoFeatureFlags> flags = Mockito.mockStatic(GoFeatureFlags.class)) {
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_BATCH_CALLOUT_SKIP_DISABLED), any())).thenReturn(true);
      NeoBatchScope previous = NeoBatchScope.open(false, true);
      try {
        assertNull(NeoBatchScope.currentCalloutDiscardMemory());
      } finally {
        NeoBatchScope.close(previous);
      }

      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_BATCH_CALLOUT_SKIP_DISABLED), any())).thenReturn(false);
      previous = NeoBatchScope.open(false, true);
      try {
        assertNotNull("kill switch off: the opt-in is honoured",
            NeoBatchScope.currentCalloutDiscardMemory());
      } finally {
        NeoBatchScope.close(previous);
      }
    }
  }

  /** Outside a batch the write already committed: the after-commit action runs at once. */
  @Test
  public void testRunAfterCommitRunsImmediatelyOutsideABatch() {
    AtomicInteger runs = new AtomicInteger();

    NeoBatchScope.runAfterCommit(runs::incrementAndGet);

    assertEquals(1, runs.get());
  }

  /**
   * Inside a batch the action waits for the commit, runs once then, and a failing action neither
   * propagates nor stops the next one.
   */
  @Test
  public void testRunAfterCommitIsQueuedInsideABatchAndRunsOnceAfterTheCommit() {
    AtomicInteger runs = new AtomicInteger();
    NeoBatchScope previous = NeoBatchScope.open(false);
    try {
      NeoBatchScope.runAfterCommit(() -> {
        throw new IllegalStateException("telemetry down");
      });
      NeoBatchScope.runAfterCommit(runs::incrementAndGet);
      assertEquals("nothing runs before the commit", 0, runs.get());

      NeoBatchScope.runAfterCommitActions();
      assertEquals(1, runs.get());

      NeoBatchScope.runAfterCommitActions();
      assertEquals("the queue is drained by the commit", 1, runs.get());
    } finally {
      NeoBatchScope.close(previous);
    }
  }

  /** A batch that rolls back closes its scope without the commit hook: its actions never run. */
  @Test
  public void testRunAfterCommitActionsAreDroppedWhenTheBatchRollsBack() {
    AtomicInteger runs = new AtomicInteger();
    NeoBatchScope previous = NeoBatchScope.open(false);
    try {
      NeoBatchScope.runAfterCommit(runs::incrementAndGet);
    } finally {
      NeoBatchScope.close(previous);
    }

    NeoBatchScope next = NeoBatchScope.open(false);
    try {
      NeoBatchScope.runAfterCommitActions();
    } finally {
      NeoBatchScope.close(next);
    }
    assertEquals("a rolled-back batch leaks nothing into the next one", 0, runs.get());
  }

  @Test
  public void testCurrentMemoStatsIsEmptyOutsideABatch() {
    assertTrue(NeoBatchScope.currentMemoStats().isEmpty());
  }

  @Test
  public void testNamespaceOfKeepsTheFirstTwoSegments() {
    assertEquals("a.Owner|spec", NeoBatchScope.namespaceOf("a.Owner|spec|sales-order"));
    assertEquals("a.Owner|entity", NeoBatchScope.namespaceOf("a.Owner|entity|S1|header"));
    assertEquals("a.Owner|spec", NeoBatchScope.namespaceOf("a.Owner|spec"));
    assertEquals("plain", NeoBatchScope.namespaceOf("plain"));
  }
}
