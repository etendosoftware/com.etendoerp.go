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

package com.etendoerp.go.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

/**
 * The isolated read: a bounded pool of named daemon workers, each running one read in its own
 * session with its statements capped, and always cleaning its thread afterwards.
 *
 * <p><b>Why the DAL is swapped through a field, not {@code mockStatic}.</b> The read runs on a
 * worker thread, and a Mockito static mock is active only on the thread that opened it. The
 * worker's {@code OBDal.getInstance()} is therefore pointed at a mock through OBDal's cached
 * singleton field (restored after each test). {@link org.openbravo.dal.core.DalThreadCleaner} and
 * {@link OBContext#clearAdminModeStack()} run for real: with no session on the worker they only
 * reset thread-locals, which is exactly what the cleanup tests observe.
 *
 * <p>Binding the caller's real user, role, client and organization on the worker needs the
 * database; that, the real {@code statement_timeout} and the caller's transaction surviving a
 * failed read are shown in {@code IsolatedDalReadIntegrationTest}.
 *
 * <p>The caller's wait is exercised through the package-private {@code call(read, timeoutMillis)}
 * seam; {@link #thePostgresCapStopsAStatementBeforeTheCallerStopsWaiting()} guards the ordering of
 * the production limits.
 *
 * @covers com.etendoerp.go.usage.IsolatedDalRead
 * @covers com.etendoerp.go.usage.IsolatedReader
 */
class IsolatedDalReadTest {

  private static final String CAP_SQL = "set local statement_timeout = 25000";
  private static final String WORKER_NAME = "etgo-isolated-read-\\d+";
  private static final long WAIT_SECONDS = 10L;
  private static final String WRITE_PATH_CAP_SQL = "set local statement_timeout = 4000";
  private static final String WRITE_PATH_WORKER_NAME = "etgo-quota-read-\\d+";
  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);

  private final List<String> events = Collections.synchronizedList(new ArrayList<>());
  private final List<Thread> callerThreads = Collections.synchronizedList(new ArrayList<>());
  private ExecutorService callers;
  private Session session;
  private NativeQuery<?> capQuery;
  private OBDal previousDal;
  private OBContext previousContext;

  @BeforeEach
  void setUp() throws Exception {
    IsolatedDalRead.shutdown();
    previousContext = OBContext.getOBContext();
    OBContext.setOBContext((OBContext) null);

    OBDal dal = mock(OBDal.class);
    session = mock(Session.class);
    capQuery = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    doReturn(capQuery).when(session).createNativeQuery(anyString());
    when(capQuery.executeUpdate()).thenAnswer(invocation -> {
      events.add("cap@" + Thread.currentThread().getName());
      return 0;
    });
    previousDal = swapDal(dal);

    callers = Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "test-caller-" + callerThreads.size());
      thread.setDaemon(true);
      callerThreads.add(thread);
      return thread;
    });
  }

  @AfterEach
  void tearDown() throws Exception {
    callers.shutdownNow();
    IsolatedDalRead.shutdown();
    swapDal(previousDal);
    OBContext.setOBContext(previousContext);
  }

  /** Points {@code OBDal.getInstance()} at {@code dal} on every thread; returns the previous one. */
  private static OBDal swapDal(OBDal dal) throws ReflectiveOperationException {
    Field field = OBDal.class.getDeclaredField("instance");
    field.setAccessible(true);
    OBDal previous = (OBDal) field.get(null);
    field.set(null, dal);
    return previous;
  }

  private <T> Future<T> callFromAnotherThread(Supplier<T> read) {
    return callers.submit(() -> IsolatedDalRead.call(read));
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
  }

  private static void awaitBarrier(CyclicBarrier barrier) {
    try {
      barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("workers did not meet", e);
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "latch not released in time");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // --- where and how a read runs ---------------------------------------------------------------

  @Test
  void aReadRunsOnANamedDaemonWorkerAndReturnsItsResult() {
    Thread[] worker = new Thread[1];

    String result = IsolatedDalRead.call(() -> {
      worker[0] = Thread.currentThread();
      return "value";
    });

    assertEquals("value", result);
    assertNotSame(Thread.currentThread(), worker[0]);
    assertTrue(worker[0].getName().matches(WORKER_NAME), worker[0].getName());
    assertTrue(worker[0].isDaemon(), "a pending read must never keep the JVM alive");
  }

  @Test
  void eachIsolatedReadCapsItsStatementsOnItsWorkerBeforeReading() {
    String worker = IsolatedDalRead.call(() -> {
      events.add("read@" + Thread.currentThread().getName());
      return Thread.currentThread().getName();
    });

    assertEquals(List.of("cap@" + worker, "read@" + worker), events);
    verify(session).createNativeQuery(CAP_SQL);
  }

  @Test
  void thePostgresCapStopsAStatementBeforeTheCallerStopsWaiting() {
    assertEquals(25_000L, IsolatedDalRead.STATEMENT_TIMEOUT_MS);
    assertEquals(30L, IsolatedDalRead.TIMEOUT_SECONDS);
    assertTrue(IsolatedDalRead.STATEMENT_TIMEOUT_MS
        < TimeUnit.SECONDS.toMillis(IsolatedDalRead.TIMEOUT_SECONDS));
  }

  // --- nested calls ----------------------------------------------------------------------------

  @Test
  void aCallFromAWorkerRunsInlineOnThatWorker() {
    Thread[] outer = new Thread[1];

    Thread inner = IsolatedDalRead.call(() -> {
      outer[0] = Thread.currentThread();
      return IsolatedDalRead.call(Thread::currentThread);
    });

    assertSame(outer[0], inner);
    assertEquals(1, events.size(), "the nested call is no second hop: one statement cap only");
  }

  @Test
  void nestedCallsNeverWaitForAWorkerEvenWhenEveryWorkerIsBusy() throws Exception {
    CyclicBarrier allWorkersBusy = new CyclicBarrier(IsolatedDalRead.WORKER_THREADS);
    List<Future<Thread[]>> outerCalls = new ArrayList<>();
    for (int i = 0; i < IsolatedDalRead.WORKER_THREADS; i++) {
      outerCalls.add(callFromAnotherThread(() -> {
        awaitBarrier(allWorkersBusy);
        Thread nested = IsolatedDalRead.call(Thread::currentThread);
        return new Thread[] { Thread.currentThread(), nested };
      }));
    }

    Set<Thread> workers = new HashSet<>();
    for (Future<Thread[]> call : outerCalls) {
      Thread[] threads = await(call);
      assertSame(threads[0], threads[1]);
      workers.add(threads[0]);
    }
    assertEquals(IsolatedDalRead.WORKER_THREADS, workers.size());
  }

  // --- a bounded pool --------------------------------------------------------------------------

  @Test
  void aFullPoolAndQueueRefuseTheNextCallInsteadOfBlocking() throws Exception {
    int capacity = IsolatedDalRead.WORKER_THREADS + IsolatedDalRead.QUEUE_CAPACITY;
    CountDownLatch workersBusy = new CountDownLatch(IsolatedDalRead.WORKER_THREADS);
    CountDownLatch release = new CountDownLatch(1);
    List<Future<Integer>> accepted = new ArrayList<>();
    try {
      for (int i = 0; i < capacity; i++) {
        int index = i;
        accepted.add(callFromAnotherThread(() -> {
          workersBusy.countDown();
          awaitLatch(release);
          return index;
        }));
      }
      awaitLatch(workersBusy);
      awaitAllCallersWaitingOnTheirRead(capacity);

      AtomicBoolean refusedReadRan = new AtomicBoolean();
      long start = System.nanoTime();
      IllegalStateException refused = assertThrows(IllegalStateException.class,
          () -> IsolatedDalRead.call(() -> {
            refusedReadRan.set(true);
            return -1;
          }));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertInstanceOf(RejectedExecutionException.class, refused.getCause());
      assertTrue(refused.getMessage().contains("4 workers busy and 64 reads queued"),
          refused.getMessage());
      assertTrue(elapsedMillis < 5_000L, "a refused call must not wait: " + elapsedMillis + " ms");
      release.countDown();
      for (int i = 0; i < capacity; i++) {
        assertEquals(i, await(accepted.get(i)));
      }
      assertFalse(refusedReadRan.get());
    } finally {
      release.countDown();
    }
  }

  /**
   * A caller is parked in a timed wait only inside {@code future.get(timeout)}, i.e. once its read
   * was accepted by the pool.
   */
  private void awaitAllCallersWaitingOnTheirRead(int count) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      long waiting;
      synchronized (callerThreads) {
        waiting = callerThreads.stream()
            .filter(thread -> thread.getState() == Thread.State.TIMED_WAITING)
            .count();
      }
      if (waiting == count) {
        return;
      }
      LockSupport.parkNanos(POLL_NANOS);
    }
    throw new AssertionError("the pool and its queue did not fill up in time");
  }

  @Test
  void afterShutdownTheNextCallBuildsAFreshPool() throws Exception {
    Thread before = IsolatedDalRead.call(Thread::currentThread);

    IsolatedDalRead.shutdown();
    before.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    Thread after = IsolatedDalRead.call(Thread::currentThread);

    assertFalse(before.isAlive(), "shutdown() must stop the old workers");
    assertNotSame(before, after);
    assertTrue(after.getName().matches(WORKER_NAME), after.getName());
    assertTrue(after.isAlive());
  }

  @Test
  void shutdownWithNoPoolIsANoOp() {
    IsolatedDalRead.shutdown();
    IsolatedDalRead.shutdown();

    assertEquals("still works", IsolatedDalRead.call(() -> "still works"));
  }

  // --- failures --------------------------------------------------------------------------------

  @Test
  void aRuntimeExceptionFromTheReadReachesTheCallerUnchanged() {
    IllegalArgumentException thrown = new IllegalArgumentException("bad read");

    IllegalArgumentException caught = assertThrows(IllegalArgumentException.class,
        () -> IsolatedDalRead.call(() -> {
          throw thrown;
        }));

    assertSame(thrown, caught);
  }

  @Test
  void anErrorFromTheReadIsWrappedAsIllegalState() {
    Error error = new Error("not a runtime exception");

    IllegalStateException caught = assertThrows(IllegalStateException.class,
        () -> IsolatedDalRead.call(() -> {
          throw error;
        }));

    assertEquals("Isolated read failed", caught.getMessage());
    assertSame(error, caught.getCause());
  }

  @Test
  void aFailingStatementCapFailsTheCallWithoutRunningTheRead() {
    IllegalStateException capFailure = new IllegalStateException("database unavailable");
    when(capQuery.executeUpdate()).thenThrow(capFailure);
    AtomicBoolean readRan = new AtomicBoolean();

    IllegalStateException caught = assertThrows(IllegalStateException.class,
        () -> IsolatedDalRead.call(() -> {
          readRan.set(true);
          return "never";
        }));

    assertSame(capFailure, caught);
    assertFalse(readRan.get());
  }

  @Test
  void anInterruptedCallerGetsIllegalStateAndKeepsItsInterruptFlag() {
    CountDownLatch release = new CountDownLatch(1);
    try {
      Thread.currentThread().interrupt();

      IllegalStateException caught = assertThrows(IllegalStateException.class,
          () -> IsolatedDalRead.call(() -> {
            awaitLatch(release);
            return "late";
          }));

      assertInstanceOf(InterruptedException.class, caught.getCause());
      assertTrue(Thread.interrupted(), "the caller's interrupt flag must be restored");
    } finally {
      release.countDown();
      Thread.interrupted();
    }
  }

  // --- cleanup ---------------------------------------------------------------------------------

  @Test
  void everyWorkerIsCleanedEvenWhenItsReadThrows() throws Exception {
    int workers = IsolatedDalRead.WORKER_THREADS;
    CyclicBarrier dirtyTogether = new CyclicBarrier(workers);
    List<Future<String>> dirtyCalls = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      int index = i;
      dirtyCalls.add(callFromAnotherThread(() -> {
        awaitBarrier(dirtyTogether);
        events.add("dirty@" + Thread.currentThread().getName());
        OBContext.setOBContext(new OBContext());
        OBContext.setAdminMode(true);
        throw new IllegalStateException("read " + index + " failed");
      }));
    }
    for (int i = 0; i < workers; i++) {
      Future<String> dirtyCall = dirtyCalls.get(i);
      ExecutionException failed = assertThrows(ExecutionException.class,
          () -> await(dirtyCall));
      assertEquals("read " + i + " failed", failed.getCause().getMessage());
    }

    CyclicBarrier probeTogether = new CyclicBarrier(workers);
    List<Future<Object[]>> probes = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      probes.add(callFromAnotherThread(() -> {
        awaitBarrier(probeTogether);
        return new Object[] { Thread.currentThread().getName(), OBContext.getOBContext(),
            new OBContext().isInAdministratorMode() };
      }));
    }

    Set<String> probed = new HashSet<>();
    for (Future<Object[]> probe : probes) {
      Object[] state = await(probe);
      probed.add((String) state[0]);
      assertNull(state[1], "cleanWithRollback() must clear the worker's context");
      assertEquals(Boolean.FALSE, state[2], "clearAdminModeStack() must empty the admin stack");
    }
    Set<String> dirtied = new HashSet<>();
    synchronized (events) {
      events.stream().filter(e -> e.startsWith("dirty@"))
          .forEach(e -> dirtied.add(e.substring("dirty@".length())));
    }
    assertEquals(workers, dirtied.size());
    assertEquals(dirtied, probed, "every dirtied worker must have been probed");
  }

  // --- the caller's context --------------------------------------------------------------------

  @Test
  void withNoCallerContextTheWorkerHasNone() {
    assertNull(OBContext.getOBContext());

    assertNull(IsolatedDalRead.call(OBContext::getOBContext));
  }

  @Test
  void aCallerContextWithoutAnOrganizationGivesTheWorkerNone() {
    OBContext caller = mock(OBContext.class);
    User user = mock(User.class);
    Role role = mock(Role.class);
    Client client = mock(Client.class);
    when(user.getId()).thenReturn("USER");
    when(role.getId()).thenReturn("ROLE");
    when(client.getId()).thenReturn("CLIENT");
    when(caller.getUser()).thenReturn(user);
    when(caller.getRole()).thenReturn(role);
    when(caller.getCurrentClient()).thenReturn(client);
    when(caller.getCurrentOrganization()).thenReturn(null);
    OBContext.setOBContext(caller);

    OBContext onWorker = IsolatedDalRead.call(OBContext::getOBContext);

    assertNull(onWorker);
    assertSame(caller, OBContext.getOBContext(), "the caller's own context is left alone");
  }

  // --- the caller's wait --------------------------------------------------------------------

  /**
   * ETP-5051 re-review: the caller stops waiting at its timeout, and the abandoned worker is
   * interrupted so that a long read can stop ({@code checkNotAbandoned}).
   */
  @Test
  void aReadPastTheCallersWaitTimesOutAndItsWorkerIsInterrupted() {
    CountDownLatch neverReleased = new CountDownLatch(1);
    CountDownLatch workerInterrupted = new CountDownLatch(1);

    IllegalStateException caught = assertThrows(IllegalStateException.class,
        () -> IsolatedDalRead.call(() -> {
          try {
            neverReleased.await(WAIT_SECONDS, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            workerInterrupted.countDown();
            Thread.currentThread().interrupt();
          }
          return "too late";
        }, 100L));

    assertInstanceOf(TimeoutException.class, caught.getCause());
    assertEquals("Isolated read timed out after 100 ms", caught.getMessage());
    awaitLatch(workerInterrupted);
  }

  @Test
  void anAbandonedWorkerStopsAtItsNextCheck() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(CancellationException.class,
          IsolatedDalRead::checkNotAbandoned);
    } finally {
      Thread.interrupted();
    }
    IsolatedDalRead.checkNotAbandoned();
  }

  // --- the write path's own pool ---------------------------------------------------------------

  /**
   * REPRO (ETP-5051 re-review W1): the reads a create waits for (index rebuild, usage-lag check)
   * must not queue behind reports. With every report worker stuck, a write-path read still runs at
   * once, on its own named worker, with its own shorter statement cap.
   */
  @Test
  void aWritePathReadRunsWhileEveryReportWorkerIsStuck() throws Exception {
    CountDownLatch reportsBusy = new CountDownLatch(IsolatedDalRead.WORKER_THREADS);
    CountDownLatch release = new CountDownLatch(1);
    List<Future<String>> reports = new ArrayList<>();
    try {
      for (int i = 0; i < IsolatedDalRead.WORKER_THREADS; i++) {
        reports.add(callFromAnotherThread(() -> {
          reportsBusy.countDown();
          awaitLatch(release);
          return "report";
        }));
      }
      awaitLatch(reportsBusy);

      long start = System.nanoTime();
      String worker = IsolatedDalRead.callOnWritePath(() -> Thread.currentThread().getName());
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertTrue(worker.matches(WRITE_PATH_WORKER_NAME), worker);
      assertTrue(elapsedMillis < 5_000L, "the write path waited " + elapsedMillis + " ms");
      verify(session).createNativeQuery(WRITE_PATH_CAP_SQL);
      assertTrue(IsolatedDalRead.WRITE_PATH_STATEMENT_TIMEOUT_MS
          < TimeUnit.SECONDS.toMillis(IsolatedDalRead.WRITE_PATH_TIMEOUT_SECONDS));
      release.countDown();
      for (Future<String> report : reports) {
        assertEquals("report", await(report));
      }
    } finally {
      release.countDown();
    }
  }

  @Test
  void aCallFromAWritePathWorkerRunsInlineToo() {
    Thread[] outer = new Thread[1];

    Thread inner = IsolatedDalRead.callOnWritePath(() -> {
      outer[0] = Thread.currentThread();
      return IsolatedDalRead.call(Thread::currentThread);
    });

    assertSame(outer[0], inner);
  }

  // --- shutdown --------------------------------------------------------------------------------

  /**
   * ETP-5051 re-review N2: shutdown cancels the reads still queued, so their callers stop waiting
   * at once instead of waiting out their timeout.
   */
  @Test
  void shutdownCancelsTheQueuedReadsAndTheirCallersStopWaiting() throws Exception {
    CountDownLatch workersBusy = new CountDownLatch(IsolatedDalRead.WORKER_THREADS);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean queuedReadRan = new AtomicBoolean();
    try {
      for (int i = 0; i < IsolatedDalRead.WORKER_THREADS; i++) {
        callFromAnotherThread(() -> {
          workersBusy.countDown();
          awaitLatch(release);
          return "running";
        });
      }
      awaitLatch(workersBusy);
      Future<String> queued = callFromAnotherThread(() -> {
        queuedReadRan.set(true);
        return "queued";
      });
      awaitAllCallersWaitingOnTheirRead(IsolatedDalRead.WORKER_THREADS + 1);

      IsolatedDalRead.shutdown();

      ExecutionException failed = assertThrows(ExecutionException.class, () -> await(queued));
      assertInstanceOf(IllegalStateException.class, failed.getCause());
      assertInstanceOf(CancellationException.class,
          failed.getCause().getCause());
      assertFalse(queuedReadRan.get());
    } finally {
      release.countDown();
    }
  }

  // --- IsolatedReader --------------------------------------------------------------------------

  @Test
  void theInlineReaderRunsOnTheCallersThreadWithNoHop() {
    assertSame(Thread.currentThread(), IsolatedReader.INLINE.call(Thread::currentThread));
    assertSame(Thread.currentThread(), IsolatedReader.onCallerThread(Thread::currentThread));
    verify(session, never()).createNativeQuery(anyString());
  }

  @Test
  void theDalReaderHopsToAnIsolatedWorker() {
    Thread worker = IsolatedReader.DAL.call(Thread::currentThread);

    assertNotSame(Thread.currentThread(), worker);
    assertTrue(worker.getName().matches(WORKER_NAME), worker.getName());
    verify(session).createNativeQuery(CAP_SQL);
  }
}
