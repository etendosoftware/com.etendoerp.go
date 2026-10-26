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
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/**
 * @covers com.etendoerp.go.schemaforge.BatchJobJdbcStore
 *
 * <p>Guards the SQL of {@code ETGO_BATCH_JOB}: every state change is conditional on the state the
 * worker expects, so a worker can never overwrite INTERRUPTED/CANCELLED and the sweep can never
 * overwrite a finished job.</p>
 */
public class BatchJobJdbcStoreSqlTest {

  private static String norm(String sql) {
    return sql.toLowerCase(Locale.ROOT);
  }

  @Test
  public void insertBindsAsManyValuesAsItNamesPlaceholders() {
    String sql = BatchJobJdbcStore.INSERT_SQL;
    int columns = sql.substring(sql.indexOf('(') + 1, sql.indexOf(')')).split(",").length;
    String values = sql.substring(sql.toUpperCase(Locale.ROOT).indexOf("VALUES"));
    int slots = values.substring(values.indexOf('(') + 1, values.lastIndexOf(')')).split(",").length;
    assertEquals(columns, slots);
    assertEquals(11, sql.chars().filter(c -> c == '?').count());
    assertTrue(norm(sql).contains("'queued'"));
  }

  @Test
  public void claimOnlyMovesAQueuedJob() {
    assertTrue(norm(BatchJobJdbcStore.CLAIM_SQL).contains("state = 'queued'"));
  }

  @Test
  public void flushOnlyWritesWhileTheJobIsRunning() {
    assertTrue(norm(BatchJobJdbcStore.FLUSH_SQL).contains("state = 'running'"));
  }

  @Test
  public void sweepOnlyTouchesActiveJobsWithAStaleHeartbeatByTheDatabaseClock() {
    String sql = norm(BatchJobJdbcStore.SWEEP_SQL);
    assertTrue(sql.contains("state in ('queued', 'running')"));
    assertTrue(sql.contains("heartbeat < now()"));
  }

  @Test
  public void purgeNeverDeletesAnActiveJob() {
    assertTrue(norm(BatchJobJdbcStore.PURGE_SQL).contains("state not in ('queued', 'running')"));
  }

  @Test
  public void cancelOnlyTouchesActiveJobs() {
    assertTrue(norm(BatchJobJdbcStore.CANCEL_SQL).contains("state in ('queued', 'running')"));
  }
}
