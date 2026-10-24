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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.DalThreadCleaner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-5051 — runs a read in its own DAL session and transaction, so that no failure inside it can
 * touch the caller's transaction.
 *
 * <p><b>Why.</b> A query that fails on the request's Hibernate session marks that transaction
 * rollback-only, and PostgreSQL aborts it too: everything the request writes afterwards is
 * silently rolled back at commit. A quota <em>report</em> (environment entry, the account page,
 * the environment list), the quota index refresh and the usage-lag check need no uncommitted row,
 * so they read through this instead and their failures stay theirs.
 * {@code OBDal.getReadOnlyInstance()} is not used because it falls back to the request's own
 * session unless a read-only pool is configured for the running process.
 *
 * <p><b>How.</b> The DAL keeps its session per thread, so the read runs on a small bounded pool of
 * named daemon threads ({@value #WORKER_THREADS} workers, {@value #QUEUE_CAPACITY} queued; more is
 * refused, which every caller treats as "nothing to report"). Each worker:
 * <ul>
 *   <li>sets {@code statement_timeout} to {@value #STATEMENT_TIMEOUT_MS} ms for its transaction, so
 *       a slow query is really stopped by PostgreSQL — cancelling the future does not stop a pgjdbc
 *       query — before the caller stops waiting at {@value #TIMEOUT_SECONDS} s;</li>
 *   <li>builds its own {@link OBContext} for the caller's user, role, client and organization —
 *       never the caller's instance, so a worker that outlives its timeout cannot touch it;</li>
 *   <li>ends with {@link DalThreadCleaner#cleanWithRollback()} and
 *       {@link OBContext#clearAdminModeStack()}, the cleanup of a request thread.</li>
 * </ul>
 * A call made from a worker runs inline: a nested hop could wait for a worker the pool does not
 * have.
 *
 * <p><b>Never</b> use it for a read that must see the caller's uncommitted rows: the NEO write
 * check (row match, live count, advisory lock) runs on the request's session on purpose.
 */
public final class IsolatedDalRead {

  private static final Logger log = LogManager.getLogger(IsolatedDalRead.class);

  /**
   * Workers. Reports run once per request (environment entry, environment list, account page) and
   * the index rebuild once a minute; each holds one connection for a short read. Four keeps the
   * worst case far below any production connection pool while letting a few reports run at once.
   */
  static final int WORKER_THREADS = 4;
  /** Reads waiting for a worker before new ones are refused. */
  static final int QUEUE_CAPACITY = 64;
  /** Upper bound the caller waits for one isolated read. */
  static final long TIMEOUT_SECONDS = 30L;
  /** PostgreSQL's cap on each statement of the read, below the caller's wait. */
  static final long STATEMENT_TIMEOUT_MS = 25_000L;
  private static final String CAP_STATEMENTS_SQL =
      "set local statement_timeout = " + STATEMENT_TIMEOUT_MS;

  private static final ThreadLocal<Boolean> IN_WORKER = new ThreadLocal<>();
  private static final AtomicReference<ExecutorService> EXECUTOR = new AtomicReference<>();

  private IsolatedDalRead() {
  }

  /**
   * Runs {@code read} in its own DAL session and returns its result. Whatever the read writes is
   * rolled back. Called from a worker, it runs inline.
   *
   * @param read the read; it must return plain values or entities it no longer needs to navigate
   * @param <T> the result type
   * @return what {@code read} returned
   * @throws IllegalStateException when the read failed, timed out, was refused (pool full) or was
   *     interrupted; a {@link RuntimeException} thrown by the read is rethrown as is
   */
  public static <T> T call(Supplier<T> read) {
    if (Boolean.TRUE.equals(IN_WORKER.get())) {
      return read.get();
    }
    WorkerContext context = WorkerContext.of(OBContext.getOBContext());
    Future<T> future;
    try {
      future = executor().submit(() -> runIsolated(read, context));
    } catch (RejectedExecutionException e) {
      throw new IllegalStateException("Isolated read refused: all " + WORKER_THREADS
          + " workers busy and " + QUEUE_CAPACITY + " reads queued", e);
    }
    try {
      return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new IllegalStateException("Isolated read failed", cause);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IllegalStateException("Isolated read timed out after " + TIMEOUT_SECONDS + " s",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for an isolated read", e);
    }
  }

  /**
   * Stops the workers. Called from {@code NeoServlet#destroy()}, beside the batch job workers; the
   * next call builds a new pool.
   */
  public static void shutdown() {
    ExecutorService local = EXECUTOR.getAndSet(null);
    if (local != null) {
      local.shutdownNow();
    }
  }

  private static ExecutorService executor() {
    ExecutorService local = EXECUTOR.get();
    if (local == null) {
      ExecutorService created = buildExecutor();
      if (EXECUTOR.compareAndSet(null, created)) {
        local = created;
      } else {
        created.shutdownNow();
        local = EXECUTOR.get();
      }
    }
    return local;
  }

  private static ExecutorService buildExecutor() {
    return new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), new DaemonThreads(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  private static <T> T runIsolated(Supplier<T> read, WorkerContext context) {
    IN_WORKER.set(Boolean.TRUE);
    try {
      capStatements();
      context.bind();
      return read.get();
    } finally {
      try {
        DalThreadCleaner.getInstance().cleanWithRollback();
      } catch (RuntimeException e) {
        log.debug("Cleaning the session of an isolated read failed", e);
      } finally {
        OBContext.clearAdminModeStack();
        IN_WORKER.remove();
      }
    }
  }

  /**
   * Caps every statement of the worker's transaction. {@code SET LOCAL} ends with the transaction,
   * so the pooled connection goes back with the server default.
   */
  private static void capStatements() {
    OBDal.getInstance().getSession().createNativeQuery(CAP_STATEMENTS_SQL).executeUpdate();
  }

  /** The ids of the caller's context, to build an equivalent one on the worker. */
  private static final class WorkerContext {
    private final String userId;
    private final String roleId;
    private final String clientId;
    private final String orgId;

    private WorkerContext(String userId, String roleId, String clientId, String orgId) {
      this.userId = userId;
      this.roleId = roleId;
      this.clientId = clientId;
      this.orgId = orgId;
    }

    private static WorkerContext of(OBContext caller) {
      if (caller == null || caller.getUser() == null || caller.getRole() == null
          || caller.getCurrentClient() == null || caller.getCurrentOrganization() == null) {
        return new WorkerContext(null, null, null, null);
      }
      return new WorkerContext(caller.getUser().getId(), caller.getRole().getId(),
          caller.getCurrentClient().getId(), caller.getCurrentOrganization().getId());
    }

    /** Sets the worker's own context; with no caller context the reads use the admin context. */
    private void bind() {
      if (userId != null) {
        OBContext.setOBContext(userId, roleId, clientId, orgId);
      }
    }
  }

  /** Named daemon threads, so a pending read never keeps the JVM alive. */
  private static final class DaemonThreads implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "etgo-isolated-read-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
