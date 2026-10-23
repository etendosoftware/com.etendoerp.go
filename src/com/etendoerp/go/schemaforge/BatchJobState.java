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

/**
 * Lifecycle of an asynchronous batch job (ETP-5677), stored in {@code ETGO_BATCH_JOB.STATE}.
 *
 * <p>{@code QUEUED → RUNNING → SUCCEEDED | PARTIAL | FAILED}, with {@code CANCELLED} (cancel
 * requested by the owner) and {@code INTERRUPTED} (the node that held the job stopped refreshing
 * its heartbeat) as the other two ways to end. The state is only ever advanced by a conditional
 * update, so a terminal state is never overwritten.</p>
 */
public enum BatchJobState {
  /** Accepted and persisted, waiting for a worker thread. */
  QUEUED,
  /** A worker is committing rows. */
  RUNNING,
  /** Every row committed. */
  SUCCEEDED,
  /** Some rows committed and some were rejected. */
  PARTIAL,
  /** No row committed (every row rejected, or the job itself failed). */
  FAILED,
  /** Stopped between rows at the owner's request; rows committed before stay committed. */
  CANCELLED,
  /** The holding node died: the rows flushed to the result are certain, later ones are unknown. */
  INTERRUPTED;

  /** @return {@code true} for a state the job never leaves */
  public boolean isTerminal() {
    return this != QUEUED && this != RUNNING;
  }

  /** @return {@code true} while a node is expected to be working on the job */
  public boolean isActive() {
    return !isTerminal();
  }
}
