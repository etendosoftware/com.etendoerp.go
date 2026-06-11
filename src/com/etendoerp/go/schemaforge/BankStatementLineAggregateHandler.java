/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * Keeps the parent {@link FIN_BankStatement}'s {@code EM_ETGO_*} aggregate
 * columns up to date when its lines change outside our own endpoints — most
 * importantly the core reconciliation
 * ({@code org.openbravo.advpaymentmngt.APRM_MatchingUtility} /
 * {@code FIN_TransactionProcess}), which sets/clears
 * {@code FIN_BankStatementLine.financialAccountTransaction} via the DAL and so
 * fires an {@link EntityUpdateEvent} here.
 *
 * <p>Fires on every {@code FIN_BankStatementLine} NEW / UPDATE / DELETE and
 * delegates to {@link BankStatementAggregates#recomputeWithDelta}, which
 * aggregates the other active lines plus the in-flight one from the event state
 * — no flush in the middle of the event.
 *
 * <p>The recompute saves the parent statement (a different entity), so it never
 * re-triggers this observer.
 *
 * <p>During our own bulk flows (import / manual create / update / delete) the
 * observer is suppressed via {@link #suppress()} so a statement with N lines
 * recomputes once at the end instead of N times; those flows call
 * {@link BankStatementAggregates#recompute} explicitly.
 */
public class BankStatementLineAggregateHandler extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(BankStatementLineAggregateHandler.class);

  private static Entity[] entities;

  /**
   * Per-thread suppression flag. Set around our bulk line operations so the
   * per-line observer becomes a no-op and the owning flow recomputes once.
   */
  private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);

  /** Suppresses the observer on the current thread (bulk flows). */
  public static void suppress() {
    SUPPRESSED.set(Boolean.TRUE);
  }

  /** Re-enables the observer on the current thread and clears the flag. */
  public static void resume() {
    SUPPRESSED.remove();
  }

  /** Whether the observer is currently suppressed on this thread. */
  public static boolean isSuppressed() {
    return Boolean.TRUE.equals(SUPPRESSED.get());
  }

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[]{
          ModelProvider.getInstance().getEntity(FIN_BankStatementLine.ENTITY_NAME)
      };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  public void onNew(@Observes EntityNewEvent event) {
    handle(event, BankStatementAggregates.Op.NEW);
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    handle(event, BankStatementAggregates.Op.UPDATE);
  }

  public void onDelete(@Observes EntityDeleteEvent event) {
    handle(event, BankStatementAggregates.Op.DELETE);
  }

  private void handle(EntityPersistenceEvent event, BankStatementAggregates.Op op) {
    if (isSuppressed() || !isValidEvent(event)) {
      return;
    }
    FIN_BankStatementLine line = (FIN_BankStatementLine) event.getTargetInstance();
    FIN_BankStatement parent = line.getBankStatement();
    if (parent == null) {
      return;
    }
    log.debug("Recomputing aggregates for statement {} after line {} {}",
        parent.getId(), line.getId(), op);
    BankStatementAggregates.recomputeWithDelta(parent, line, op, event);
  }
}
