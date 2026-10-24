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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
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
import org.openbravo.base.structure.BaseOBObject;
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
 * <p><b>Two pools.</b> The DAL keeps its session per thread, so a read runs on a pool of named
 * daemon threads. Reports and the NEO write path never share one, so a burst of slow reports cannot
 * stall a create:
 * <ul>
 *   <li>{@link #call} — reports: {@value #WORKER_THREADS} workers, {@value #QUEUE_CAPACITY} queued,
 *       the caller waits up to {@value #TIMEOUT_SECONDS} s, statements capped at
 *       {@value #STATEMENT_TIMEOUT_MS} ms;</li>
 *   <li>{@link #callOnWritePath} — the reads a create waits for (quota index rebuild, usage-lag
 *       check): {@value #WRITE_PATH_WORKER_THREADS} workers, {@value #WRITE_PATH_QUEUE_CAPACITY}
 *       queued, the caller waits up to {@value #WRITE_PATH_TIMEOUT_SECONDS} s, statements capped at
 *       {@value #WRITE_PATH_STATEMENT_TIMEOUT_MS} ms.</li>
 * </ul>
 * A read beyond a pool's queue is refused at once. Idle workers end after a minute.
 *
 * <p><b>Each worker</b>:
 * <ul>
 *   <li>sets {@code statement_timeout} for its transaction, below the caller's wait, so a slow
 *       query is really stopped by PostgreSQL — cancelling the future does not stop a pgjdbc
 *       query;</li>
 *   <li>builds its own {@link OBContext} for the caller's user, role, client, organization,
 *       language and warehouse — never the caller's instance, so a worker that outlives its
 *       timeout cannot touch it;</li>
 *   <li>is interrupted when its caller stops waiting; a long read checks
 *       {@link #checkNotAbandoned()} and stops;</li>
 *   <li>ends with {@link DalThreadCleaner#cleanWithRollback()} and
 *       {@link OBContext#clearAdminModeStack()}, the cleanup of a request thread.</li>
 * </ul>
 * A call made from any worker runs inline: a nested hop could wait for a worker the pool does not
 * have.
 *
 * <p><b>Never</b> use it for a read that must see the caller's uncommitted rows: the NEO write
 * check (row match, live count, advisory lock) runs on the request's session on purpose.
 */
public final class IsolatedDalRead {

  private static final Logger log = LogManager.getLogger(IsolatedDalRead.class);

  /**
   * Report workers. Reports run once per request (environment entry, environment list, account
   * page) and each holds one connection for a short read. Four keeps the worst case far below any
   * production connection pool while letting a few reports run at once.
   */
  static final int WORKER_THREADS = 4;
  /** Report reads waiting for a worker before new ones are refused. */
  static final int QUEUE_CAPACITY = 64;
  /** Upper bound a report caller waits for one isolated read. */
  static final long TIMEOUT_SECONDS = 30L;
  /** PostgreSQL's cap on each statement of a report read, below the caller's wait. */
  static final long STATEMENT_TIMEOUT_MS = 25_000L;

  /** Write-path workers: an index rebuild a minute and a lag read a minute, never at once more. */
  static final int WRITE_PATH_WORKER_THREADS = 2;
  /** Write-path reads waiting for a worker before new ones are refused. */
  static final int WRITE_PATH_QUEUE_CAPACITY = 8;
  /** Upper bound a create waits for one write-path read. */
  static final long WRITE_PATH_TIMEOUT_SECONDS = 5L;
  /** PostgreSQL's cap on each statement of a write-path read, below the create's wait. */
  static final long WRITE_PATH_STATEMENT_TIMEOUT_MS = 4_000L;

  private static final ThreadLocal<Boolean> IN_WORKER = new ThreadLocal<>();

  private static final Pool REPORTS = new Pool("etgo-isolated-read-", WORKER_THREADS,
      QUEUE_CAPACITY, STATEMENT_TIMEOUT_MS);
  private static final Pool WRITE_PATH = new Pool("etgo-quota-read-", WRITE_PATH_WORKER_THREADS,
      WRITE_PATH_QUEUE_CAPACITY, WRITE_PATH_STATEMENT_TIMEOUT_MS);

  private IsolatedDalRead() {
  }

  /**
   * Runs a report read in its own DAL session and returns its result. Whatever the read writes is
   * rolled back. Called from a worker, it runs inline.
   *
   * @param read the read; it must return plain values or entities it no longer needs to navigate
   * @param <T> the result type
   * @return what {@code read} returned
   * @throws IllegalStateException when the read failed, timed out, was refused (pool full),
   *     cancelled or interrupted; a {@link RuntimeException} thrown by the read is rethrown as is
   */
  public static <T> T call(Supplier<T> read) {
    return call(read, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
  }

  /**
   * {@link #call(Supplier)} with another caller wait: the seam its timeout tests use.
   *
   * @param read the read
   * @param timeoutMillis how long the caller waits
   * @param <T> the result type
   * @return what {@code read} returned
   */
  static <T> T call(Supplier<T> read, long timeoutMillis) {
    return REPORTS.call(read, timeoutMillis);
  }

  /**
   * Runs a read a NEO create waits for — the quota index rebuild, the usage-lag check — on the
   * write path's own small pool, with a short wait. Called from a worker, it runs inline.
   *
   * @param read the read
   * @param <T> the result type
   * @return what {@code read} returned
   * @throws IllegalStateException as {@link #call(Supplier)}
   */
  public static <T> T callOnWritePath(Supplier<T> read) {
    return WRITE_PATH.call(read, TimeUnit.SECONDS.toMillis(WRITE_PATH_TIMEOUT_SECONDS));
  }

  /**
   * Stops a long read whose caller gave up: throws when the current thread was interrupted, which
   * is how a worker learns that its caller timed out or the pool shut down. Cheap; call it between
   * the steps of a read that loops.
   *
   * @throws CancellationException when the current thread is interrupted (the flag is kept)
   */
  public static void checkNotAbandoned() {
    if (Thread.currentThread().isInterrupted()) {
      throw new CancellationException("The isolated read was abandoned by its caller");
    }
  }

  /**
   * Stops the workers of both pools and cancels the reads still queued, so their callers stop
   * waiting at once. Called from {@code NeoServlet#destroy()}, beside the batch job workers; the
   * next call builds a new pool.
   */
  public static void shutdown() {
    REPORTS.shutdown();
    WRITE_PATH.shutdown();
  }

  /** One bounded pool of isolated workers. */
  private static final class Pool {
    private final String threadPrefix;
    private final int workers;
    private final int queueCapacity;
    private final String capStatementsSql;
    private final AtomicReference<ThreadPoolExecutor> executor = new AtomicReference<>();

    private Pool(String threadPrefix, int workers, int queueCapacity, long statementTimeoutMs) {
      this.threadPrefix = threadPrefix;
      this.workers = workers;
      this.queueCapacity = queueCapacity;
      this.capStatementsSql = "set local statement_timeout = " + statementTimeoutMs;
    }

    private <T> T call(Supplier<T> read, long timeoutMillis) {
      if (Boolean.TRUE.equals(IN_WORKER.get())) {
        return read.get();
      }
      WorkerContext context = WorkerContext.of(OBContext.getOBContext());
      Future<T> future;
      try {
        future = executor().submit(() -> runIsolated(read, context));
      } catch (RejectedExecutionException e) {
        throw new IllegalStateException("Isolated read refused: all " + workers
            + " workers busy and " + queueCapacity + " reads queued", e);
      }
      try {
        return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) {
          throw (RuntimeException) cause;
        }
        throw new IllegalStateException("Isolated read failed", cause);
      } catch (CancellationException e) {
        throw new IllegalStateException("Isolated read cancelled: the pool shut down", e);
      } catch (TimeoutException e) {
        future.cancel(true);
        throw new IllegalStateException("Isolated read timed out after " + timeoutMillis + " ms",
            e);
      } catch (InterruptedException e) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for an isolated read", e);
      }
    }

    private <T> T runIsolated(Supplier<T> read, WorkerContext context) {
      IN_WORKER.set(Boolean.TRUE);
      try {
        // SET LOCAL ends with the transaction, so the pooled connection goes back with the default.
        OBDal.getInstance().getSession().createNativeQuery(capStatementsSql).executeUpdate();
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

    /** The live pool, built on first use and after a shutdown; never null. */
    private ThreadPoolExecutor executor() {
      for (;;) {
        ThreadPoolExecutor local = executor.get();
        if (local != null) {
          return local;
        }
        ThreadPoolExecutor created = build();
        if (executor.compareAndSet(null, created)) {
          return created;
        }
        created.shutdownNow();
      }
    }

    private ThreadPoolExecutor build() {
      ThreadPoolExecutor pool = new ThreadPoolExecutor(workers, workers, 60L, TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(queueCapacity), new DaemonThreads(threadPrefix),
          new ThreadPoolExecutor.AbortPolicy());
      pool.allowCoreThreadTimeOut(true);
      return pool;
    }

    private void shutdown() {
      ThreadPoolExecutor local = executor.getAndSet(null);
      if (local == null) {
        return;
      }
      for (Runnable queued : local.shutdownNow()) {
        if (queued instanceof Future) {
          ((Future<?>) queued).cancel(false);
        }
      }
    }
  }

  /** The ids of the caller's context, to build an equivalent one on the worker. */
  private static final class WorkerContext {
    private static final WorkerContext NONE =
        new WorkerContext(null, null, null, null, null, null);

    private final String userId;
    private final String roleId;
    private final String clientId;
    private final String orgId;
    private final String languageCode;
    private final String warehouseId;

    private WorkerContext(String userId, String roleId, String clientId, String orgId,
        String languageCode, String warehouseId) {
      this.userId = userId;
      this.roleId = roleId;
      this.clientId = clientId;
      this.orgId = orgId;
      this.languageCode = languageCode;
      this.warehouseId = warehouseId;
    }

    private static WorkerContext of(OBContext caller) {
      if (caller == null || caller.getUser() == null || caller.getRole() == null
          || caller.getCurrentClient() == null || caller.getCurrentOrganization() == null) {
        return NONE;
      }
      return new WorkerContext(caller.getUser().getId(), caller.getRole().getId(),
          caller.getCurrentClient().getId(), caller.getCurrentOrganization().getId(),
          caller.getLanguage() == null ? null : caller.getLanguage().getLanguage(),
          idOf(caller.getWarehouse()));
    }

    private static String idOf(BaseOBObject entity) {
      return entity == null ? null : (String) entity.getId();
    }

    /**
     * Sets the worker's own context; with no caller context the reads use the admin context. A null
     * language reads the user's own.
     */
    private void bind() {
      if (userId != null) {
        OBContext.setOBContext(userId, roleId, clientId, orgId, languageCode, warehouseId);
      }
    }
  }

  /** Named daemon threads, so a pending read never keeps the JVM alive. */
  private static final class DaemonThreads implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    private DaemonThreads(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
