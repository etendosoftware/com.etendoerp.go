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

import java.util.Collection;

/**
 * Persistence of asynchronous batch jobs (ETP-5677). An interface so the job service is testable
 * without a database; {@link BatchJobJdbcStore} is the production implementation.
 *
 * <p>Every state change is a <b>conditional</b> update ({@code WHERE state IN (…)}) and reports
 * whether it applied, so a worker can never overwrite {@code INTERRUPTED}/{@code CANCELLED} and a
 * sweep can never overwrite a terminal state.</p>
 */
interface BatchJobStore {

  /** One stored job. */
  final class JobRecord {
    String id;
    String clientId;
    String orgId;
    String userId;
    String roleId;
    String channel;
    BatchJobState state;
    int totalRows;
    int totalOps;
    int processedRows;
    boolean cancelRequested;
    /** JSON array of per-row results, or {@code null} before the first row. */
    String result;
  }

  /** Inserts a new {@code QUEUED} job with a fresh heartbeat. */
  void insert(JobRecord job);

  /** @return the job, or {@code null} when it does not exist (or was purged) */
  JobRecord find(String jobId);

  /** What a worker re-reads before each row: still running, cancel asked? */
  final class Control {
    final BatchJobState state;
    final boolean cancelRequested;

    Control(BatchJobState state, boolean cancelRequested) {
      this.state = state;
      this.cancelRequested = cancelRequested;
    }
  }

  /** @return the state and cancel flag of the job, or {@code null} when it no longer exists */
  Control readControl(String jobId);

  /** @return QUEUED + RUNNING jobs of one user in one client */
  int countActiveForUser(String clientId, String userId);

  /** @return QUEUED + RUNNING jobs of one client */
  int countActiveForClient(String clientId);

  /** Conditional {@code QUEUED → RUNNING}; {@code false} when it was no longer queued. */
  boolean claim(String jobId);

  /**
   * Records progress of a RUNNING job (processed row count, full per-row result, heartbeat).
   *
   * @return {@code false} when the job is no longer RUNNING, which tells the worker to stop
   */
  boolean flush(String jobId, int processedRows, String result);

  /**
   * Conditional move to {@code to} from any of {@code from}.
   *
   * @param result the per-row result to store, or {@code null} to leave it untouched
   * @return {@code true} when the update applied
   */
  boolean transition(String jobId, BatchJobState to, Collection<BatchJobState> from,
      String result);

  /** Sets the cancel flag of a non-terminal job; {@code false} when the job is terminal/missing. */
  boolean requestCancel(String jobId);

  /** Refreshes the heartbeat of the given QUEUED/RUNNING jobs. */
  void heartbeat(Collection<String> jobIds);

  /**
   * Moves every QUEUED/RUNNING job whose heartbeat is older than {@code staleSeconds} (measured by
   * the database clock, so nodes with skewed clocks agree) to {@code INTERRUPTED}.
   *
   * @return how many jobs were interrupted
   */
  int sweepStale(int staleSeconds);

  /** Deletes finished jobs older than {@code retentionHours}. */
  int purgeFinished(int retentionHours);

  /** Deletes a job row (used when the executor refused it right after the insert). */
  void delete(String jobId);
}
