/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.InvoiceInboxProcessor;

/**
 * The safety net of the invoice inbox worker: the first sweep, the periodic ones, and the shutdown.
 * The processor is mocked; what is asserted is when the sweep runs and that the scheduler starts once
 * and stops cleanly.
 *
 * @covers com.etendoerp.go.startup.InvoiceInboxSweeperStartup
 */
class InvoiceInboxSweeperStartupTest {

  private MockedStatic<InvoiceInboxProcessor> processor;
  private InvoiceInboxSweeperStartup startup;

  @BeforeEach
  void setUp() {
    processor = mockStatic(InvoiceInboxProcessor.class);
    startup = new InvoiceInboxSweeperStartup();
  }

  @AfterEach
  void tearDown() {
    startup.shutdown();
    processor.close();
  }

  private ScheduledExecutorService scheduler() throws Exception {
    Field field = InvoiceInboxSweeperStartup.class.getDeclaredField("scheduler");
    field.setAccessible(true);
    return (ScheduledExecutorService) field.get(startup);
  }

  @Test
  @DisplayName("the first pass sweeps once and starts the periodic scheduler")
  void theFirstPassSweepsAndSchedules() throws Exception {
    assertNull(scheduler());

    startup.runPass();

    processor.verify(InvoiceInboxProcessor::sweep);
    assertNotNull(scheduler());
    assertFalse(scheduler().isShutdown());
  }

  @Test
  @DisplayName("a second pass does not create a second scheduler")
  void theSchedulerStartsOnce() throws Exception {
    startup.runPass();
    ScheduledExecutorService first = scheduler();

    startup.runPass();

    assertSame(first, scheduler());
  }

  @Test
  @DisplayName("the scheduler starts even when the first sweep fails, and the failure still reaches the base class")
  void aFailingFirstSweepStillSchedules() throws Exception {
    processor.when(InvoiceInboxProcessor::sweep).thenThrow(new IllegalStateException("db down"));

    assertThrows(IllegalStateException.class, startup::runPass);

    assertNotNull(scheduler());
  }

  @Test
  @DisplayName("shutdown stops the scheduler and the worker, and can be called twice")
  void shutdownStopsEverything() throws Exception {
    startup.runPass();
    ScheduledExecutorService running = scheduler();

    startup.shutdown();
    startup.shutdown();

    assertTrue(running.isShutdown());
    assertNull(scheduler());
    processor.verify(InvoiceInboxProcessor::shutdown, org.mockito.Mockito.times(2));
  }

  @Test
  @DisplayName("the qualifier and the period are the ones the module registers")
  void identity() {
    assertEquals("com.etendoerp.go.startup.InvoiceInboxSweeperStartup",
        InvoiceInboxSweeperStartup.QUALIFIER);
    assertEquals(2L, InvoiceInboxSweeperStartup.PERIOD_MINUTES);
  }
}
