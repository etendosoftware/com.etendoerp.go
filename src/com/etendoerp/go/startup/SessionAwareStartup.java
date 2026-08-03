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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.startup;

import org.apache.logging.log4j.Logger;
import org.openbravo.client.kernel.ApplicationInitializer;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.SessionInfo;

/**
 * Shared scaffolding for Etendo GO startup self-healers.
 *
 * <p>These initializers must NOT run on the startup thread (they would block it and, worse, borrow a
 * DAL connection before {@link SessionInfo} is initialized, hitting the {@code ad_context_info}
 * temp-table problem) and must NEVER break the application if they fail. This base class owns that
 * common skeleton: it spawns a daemon worker, waits (bounded) for {@code SessionInfo} to be ready,
 * runs the subclass pass, and — on any failure — logs and rolls back so a half-done transaction is
 * never left open. Subclasses only supply their logger, a display name, and the actual self-heal
 * pass in {@link #runPass()}.</p>
 */
abstract class SessionAwareStartup implements ApplicationInitializer {

  /** Poll interval while waiting for the DB session info to be initialized. */
  private static final long SESSION_POLL_MS = 100L;
  /** Hard cap before proceeding regardless of session-info state. */
  private static final long SESSION_WAIT_TIMEOUT_MS = 60_000L;

  /** The concrete subclass logger, so messages keep their original category. */
  protected abstract Logger log();

  /** Short name used for the worker thread and log-message prefix (typically the class name). */
  protected abstract String name();

  /**
   * The synchronous, idempotent self-heal pass. Invoked on the worker thread once {@link SessionInfo}
   * is initialized; may throw — the base class catches, logs and rolls back.
   */
  protected abstract void runPass();

  @Override
  public void initialize() {
    // Run asynchronously so we never block (nor fail) the application startup sequence, and so we
    // can wait for SessionInfo to be initialized before borrowing a DAL connection (borrowing one
    // too early hits the ad_context_info temp-table problem).
    Thread worker = new Thread(this::runSafely, name() + "-startup");
    worker.setDaemon(true);
    worker.start();
  }

  private void runSafely() {
    try {
      waitForSessionInfoInitialized();
      runPass();
    } catch (Exception e) {
      // Startup self-healing must never break the application: log and continue.
      log().error("{}: startup self-heal failed; skipping.", name(), e);
      try {
        OBDal.getInstance().rollbackAndClose();
      } catch (Exception rollbackError) {
        log().debug("{}: rollback after failure also failed.", name(), rollbackError);
      }
    }
  }

  /**
   * Blocks (bounded by {@link #SESSION_WAIT_TIMEOUT_MS}) until {@link SessionInfo#isInitialized()} is
   * true, so the pass can safely borrow a DAL connection. If the timeout elapses it warns and returns
   * so the pass proceeds anyway. Restores the interrupt flag and returns early if interrupted.
   */
  protected void waitForSessionInfoInitialized() {
    long deadline = System.currentTimeMillis() + SESSION_WAIT_TIMEOUT_MS;
    while (!SessionInfo.isInitialized() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(SESSION_POLL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (!SessionInfo.isInitialized()) {
      log().warn("{}: SessionInfo not initialized after {} ms; proceeding anyway.", name(),
          SESSION_WAIT_TIMEOUT_MS);
    }
  }
}
