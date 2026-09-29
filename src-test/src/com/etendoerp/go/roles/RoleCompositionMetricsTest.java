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
package com.etendoerp.go.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Plain, DB-free unit tests for {@link RoleCompositionMetrics} (ETP-5503) — the per-call counters
 * the overlap guards report into from inside core's propagation.
 */
class RoleCompositionMetricsTest {

  @Test
  void countersAndStagesAccumulateIntoTheBoundInstance() {
    try (RoleCompositionMetrics metrics = RoleCompositionMetrics.start()) {
      RoleCompositionMetrics.addPrevented(3);
      RoleCompositionMetrics.addPrevented(2);
      RoleCompositionMetrics.addCopied();
      RoleCompositionMetrics.addCopied();
      RoleCompositionMetrics.addWidened();
      RoleCompositionMetrics.addRepointed();
      RoleCompositionMetrics.addStageTime("save", 2_000_000L);
      RoleCompositionMetrics.addStageTime("save", 3_000_000L);
      RoleCompositionMetrics.addStageTime("flush", 1_000_000L);

      assertEquals(5, metrics.getPrevented());
      assertEquals(2, metrics.getCopied());
      String summary = metrics.summary();
      assertTrue(summary.startsWith(
          "prevented=5 copied=2 widened=1 repointed=1 stagesMs={save=5, flush=1} totalMs="),
          summary);
    }
  }

  @Test
  void callsWithNoBoundInstanceAreIgnored() {
    RoleCompositionMetrics.addPrevented(7);
    RoleCompositionMetrics.addCopied();
    RoleCompositionMetrics.addStageTime("save", 1_000_000L);

    try (RoleCompositionMetrics metrics = RoleCompositionMetrics.start()) {
      assertEquals(0, metrics.getPrevented());
      assertEquals(0, metrics.getCopied());
      assertTrue(metrics.summary().contains("stagesMs={}"), metrics.summary());
    }
  }

  @Test
  void closeUnbindsTheInstance() {
    RoleCompositionMetrics closed;
    try (RoleCompositionMetrics metrics = RoleCompositionMetrics.start()) {
      closed = metrics;
    }
    RoleCompositionMetrics.addPrevented(4);

    assertEquals(0, closed.getPrevented());
  }

  @Test
  void closingAReplacedInstanceKeepsTheNewerOneBound() {
    RoleCompositionMetrics older = RoleCompositionMetrics.start();
    try (RoleCompositionMetrics newer = RoleCompositionMetrics.start()) {
      older.close();
      RoleCompositionMetrics.addPrevented(1);

      assertEquals(1, newer.getPrevented());
      assertEquals(0, older.getPrevented());
    }
  }
}
