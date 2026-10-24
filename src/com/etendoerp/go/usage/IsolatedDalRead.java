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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
 * <p><b>How.</b> The DAL keeps its session per thread, so the read runs on a small pool of daemon
 * threads, under the caller's {@link OBContext}, and its session is rolled back and closed when it
 * ends. The caller waits for it, at most {@link #TIMEOUT_SECONDS} seconds.
 *
 * <p><b>Never</b> use it for a read that must see the caller's uncommitted rows: the NEO write
 * check (row match, live count, advisory lock) runs on the request's session on purpose.
 */
public final class IsolatedDalRead {

  private static final Logger log = LogManager.getLogger(IsolatedDalRead.class);

  /** Upper bound on one isolated read. */
  static final long TIMEOUT_SECONDS = 30L;

  private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(
      new DaemonThreads());

  private IsolatedDalRead() {
  }

  /**
   * Runs {@code read} in its own DAL session and returns its result. Whatever the read writes is
   * rolled back.
   *
   * @param read the read; it must return plain values or entities it no longer needs to navigate
   * @param <T> the result type
   * @return what {@code read} returned
   * @throws IllegalStateException when the read failed, timed out or was interrupted
   */
  public static <T> T call(Supplier<T> read) {
    OBContext callerContext = OBContext.getOBContext();
    Future<T> future = EXECUTOR.submit(() -> runIsolated(read, callerContext));
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

  private static <T> T runIsolated(Supplier<T> read, OBContext callerContext) {
    OBContext.setOBContext(callerContext);
    try {
      return read.get();
    } finally {
      try {
        OBDal.getInstance().rollbackAndClose();
      } catch (RuntimeException e) {
        log.debug("Closing the session of an isolated read failed", e);
      }
      OBContext.setOBContext((OBContext) null);
    }
  }

  /** Daemon threads, so a pending read never keeps the JVM alive. */
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
