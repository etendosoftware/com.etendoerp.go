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

import static com.etendoerp.go.schemaforge.BankStatementsSupport.deriveStatementStatus;

import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;

/**
 * Keeps {@code FIN_BankStatement.EM_ETGO_STATUS} — the "Estado" column the SPA's
 * "Extractos importados" list reads — in sync with the header's real
 * {@code Processed} flag, regardless of which code path flips it.
 *
 * <p>{@link BankStatementAggregates#apply} already derives this status from
 * {@code (processed, lineCount, matchedCount)}, but only WE call it — from
 * {@link BankStatementsHandler}'s own create/process/reactivate flows and from
 * {@link BankStatementLineAggregateHandler} whenever a LINE changes. A statement
 * imported through the PSD2 bank-connection sync
 * ({@code SaltEdgeAccountLinkHelper#fetchAccountTransactions}, in the external
 * {@code com.etendoerp.psd2} module) is created — and its lines inserted — through
 * a path that never calls into this module's handlers at all: the line-level
 * observer above DOES still fire for each inserted line (so line/matched counts
 * and totals stay correct), but at that moment {@code Processed} is still
 * {@code false}, so the status those line events compute and store is correctly
 * {@code "DRAFT"} — for THAT instant. The external sync then flips
 * {@code Processed} to {@code true} directly on the header, and nothing re-derives
 * the status afterward, since no line event fires for a header-only change. The
 * column is left showing "Borrador" forever on an already-processed statement —
 * confirmed live: {@code em_etgo_status='DRAFT'} with {@code processed='Y'} on a
 * PSD2-synced statement, and the SPA's own "Procesar" action then fails with
 * "Only draft (unprocessed) statements can be modified" because the REAL flag
 * (which that guard correctly reads) says it already is (ETP-4891 follow-up).
 *
 * <p>Fires on every {@code FIN_BankStatement} NEW / UPDATE and writes the derived
 * status onto the in-flight event state ({@code setCurrentState}) of the SAME
 * row — same technique as {@link BankStatementLinePendingAmountHandler} — so
 * there is no extra flush and no re-triggering of this same observer. Safe to
 * run unconditionally (no {@code suppress()} gate needed, unlike the per-line
 * observer): recomputing from the header's own already-correct
 * {@code EM_ETGO_LINE_COUNT}/{@code EM_ETGO_MATCHED_COUNT} is cheap (no query)
 * and idempotent — it reaches the same answer whether our own flows or an
 * external one triggered the save.
 */
public class BankStatementHeaderStatusHandler extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(BankStatementHeaderStatusHandler.class);

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[]{
          ModelProvider.getInstance().getEntity(FIN_BankStatement.ENTITY_NAME)
      };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  public void onNew(@Observes EntityNewEvent event) {
    apply(event);
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    apply(event);
  }

  private void apply(EntityPersistenceEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Entity statementEntity = resolveEntities()[0];
    Property statusProp = statementEntity.getPropertyByColumnName(BankStatementAggregates.COL_STATUS);
    Property lineCountProp =
        statementEntity.getPropertyByColumnName(BankStatementAggregates.COL_LINE_COUNT);
    Property matchedCountProp =
        statementEntity.getPropertyByColumnName(BankStatementAggregates.COL_MATCHED_COUNT);
    if (statusProp == null || lineCountProp == null || matchedCountProp == null) {
      log.debug("EM_ETGO_* aggregate columns not in model yet — skipping status sync");
      return;
    }
    boolean processed = Boolean.TRUE.equals(
        event.getCurrentState(statementEntity.getProperty(FIN_BankStatement.PROPERTY_PROCESSED)));
    int lineCount = intOf(event.getCurrentState(lineCountProp));
    int matchedCount = intOf(event.getCurrentState(matchedCountProp));
    String status = deriveStatementStatus(processed, lineCount, matchedCount);
    event.setCurrentState(statusProp, status);
  }

  private static int intOf(Object value) {
    return value instanceof Number ? ((Number) value).intValue() : 0;
  }
}
