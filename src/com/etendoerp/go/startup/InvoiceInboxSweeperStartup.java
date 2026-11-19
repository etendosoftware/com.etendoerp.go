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

package com.etendoerp.go.startup;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.client.kernel.ComponentProvider;

import com.etendoerp.go.schemaforge.InvoiceInboxProcessor;

/**
 * ETP-6048 — the safety net of the invoice inbox worker: at startup and then every
 * {@value #PERIOD_MINUTES} minutes it hands the worker every {@code PROCESSING} row nobody is
 * working on (a restart, a node that died, a full queue, an upload taken by another node) and ends
 * as {@code SERVICE_FAILURE} the rows that ran out of passes.
 *
 * <p>It does not evaluate the feature flag: a {@code PROCESSING} row exists only because the flag
 * was on when it was uploaded. Never blocks or breaks startup: the first sweep runs on
 * {@link SessionAwareStartup}'s daemon worker, later ones on a single daemon scheduler thread, and
 * every failure is logged by the processor.</p>
 */
@ApplicationScoped
@ComponentProvider.Qualifier(InvoiceInboxSweeperStartup.QUALIFIER)
public class InvoiceInboxSweeperStartup extends SessionAwareStartup {

  static final String QUALIFIER = "com.etendoerp.go.startup.InvoiceInboxSweeperStartup";
  static final long PERIOD_MINUTES = 2L;

  private static final Logger log = LogManager.getLogger(InvoiceInboxSweeperStartup.class);
  private static final String NAME = "InvoiceInboxSweeperStartup";

  private ScheduledExecutorService scheduler;

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected String name() {
    return NAME;
  }

  @Override
  protected void runPass() {
    try {
      InvoiceInboxProcessor.sweep();
    } finally {
      schedulePeriodicSweeps();
    }
  }

  private synchronized void schedulePeriodicSweeps() {
    if (scheduler != null) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, NAME + "-tick");
      thread.setDaemon(true);
      return thread;
    });
    scheduler.scheduleWithFixedDelay(InvoiceInboxProcessor::sweep, PERIOD_MINUTES,
        PERIOD_MINUTES, TimeUnit.MINUTES);
  }

  /** Stops the sweeps and the worker when the application shuts down or the webapp reloads. */
  @PreDestroy
  synchronized void shutdown() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
    InvoiceInboxProcessor.shutdown();
  }
}
