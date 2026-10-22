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

  /**
   * Inserts a new {@code QUEUED} job with a fresh heartbeat.
   *
   * @param job the job to store; its id, owner and counters are read from it
   */
  void insert(JobRecord job);

  /**
   * Reads one stored job.
   *
   * @param jobId the id of the job to read
   * @return the job, or {@code null} when it does not exist (or was purged)
   */
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

  /**
   * Reads what a worker re-reads before each row: is the job still running, was a cancel asked?
   *
   * @param jobId the id of the job to read
   * @return the state and cancel flag of the job, or {@code null} when it no longer exists
   */
  Control readControl(String jobId);

  /**
   * Counts the jobs of one user that are still queued or running.
   *
   * @param clientId the tenant the jobs belong to
   * @param userId   the user whose jobs are counted
   * @return QUEUED + RUNNING jobs of one user in one client
   */
  int countActiveForUser(String clientId, String userId);

  /**
   * Counts the jobs of one tenant that are still queued or running.
   *
   * @param clientId the tenant whose jobs are counted
   * @return QUEUED + RUNNING jobs of one client
   */
  int countActiveForClient(String clientId);

  /**
   * Claims a job for this node.
   *
   * @param jobId the id of the job to claim
   * @return {@code true} when the conditional {@code QUEUED → RUNNING} update applied, {@code false}
   *         when the job was no longer queued (cancelled or swept meanwhile)
   */
  boolean claim(String jobId);

  /**
   * Records progress of a RUNNING job (processed row count, full per-row result, heartbeat).
   *
   * @param jobId         the id of the running job
   * @param processedRows how many rows are done
   * @param result        the per-row results as JSON
   * @return {@code false} when the job is no longer RUNNING, which tells the worker to stop
   */
  boolean flush(String jobId, int processedRows, String result);

  /**
   * Conditional move to {@code to} from any of {@code from}.
   *
   * @param jobId  the id of the job to move
   * @param to     the state to move to
   * @param from   the states the move is allowed from
   * @param result the per-row result to store, or {@code null} to leave it untouched
   * @return {@code true} when the update applied
   */
  boolean transition(String jobId, BatchJobState to, Collection<BatchJobState> from,
      String result);

  /**
   * Sets the cancel flag of a non-terminal job.
   *
   * @param jobId the id of the job to cancel
   * @return {@code false} when the job is terminal/missing
   */
  boolean requestCancel(String jobId);

  /**
   * Refreshes the heartbeat of the given QUEUED/RUNNING jobs.
   *
   * @param jobIds the ids of the jobs this node holds
   */
  void heartbeat(Collection<String> jobIds);

  /**
   * Moves every QUEUED/RUNNING job whose heartbeat is older than {@code staleSeconds} (measured by
   * the database clock, so nodes with skewed clocks agree) to {@code INTERRUPTED}.
   *
   * @param staleSeconds how long a job may go without a heartbeat
   * @return how many jobs were interrupted
   */
  int sweepStale(int staleSeconds);

  /**
   * Deletes the finished jobs that are older than the retention.
   *
   * @param retentionHours how long a finished job is kept
   * @return how many jobs were deleted
   */
  int purgeFinished(int retentionHours);

  /**
   * Deletes a job row (used when the executor refused it right after the insert).
   *
   * @param jobId the id of the job to delete
   */
  void delete(String jobId);
}
