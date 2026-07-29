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

import java.math.BigDecimal;

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
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * Maintains {@code FIN_BankStatementLine.EM_ETGO_Pending_Amount}: the portion of a (sub-)line still
 * pending to reconcile — {@code |cramount − dramount|} while the line has no linked
 * {@code financialAccountTransaction}, {@code 0} once it does.
 *
 * <p>Fires on every NEW / UPDATE of a bank-statement line, including the core reconciliation
 * ({@code org.openbravo.advpaymentmngt.APRM_MatchingUtility}), which sets/clears
 * {@code financialAccountTransaction} and splits lines via the DAL. The value is written onto the
 * in-flight event state ({@code setCurrentState}) of the SAME row, so there is no extra flush and no
 * recursion.
 *
 * <p>This is a per-physical-line value. For a split reconciliation group the remaining amount of the
 * logical line is the sum of this column across the group's sub-lines, computed by
 * {@code BankStatementsSupport.mergeMatchGroups}. See ETP-4502 iteration 5.
 */
public class BankStatementLinePendingAmountHandler extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(BankStatementLinePendingAmountHandler.class);

  private static Entity[] entities;

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
    apply(event);
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    apply(event);
  }

  private void apply(EntityPersistenceEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Entity lineEntity = resolveEntities()[0];
    Property pendingProp = ReactivationSupport.extensionProperty(
        FIN_BankStatementLine.ENTITY_NAME, ReactivationSupport.COL_PENDING_AMOUNT);
    if (pendingProp == null) {
      log.warn("Column {} not yet in the model; skipping pending-amount maintenance",
          ReactivationSupport.COL_PENDING_AMOUNT);
      return;
    }
    BigDecimal credit = nullSafe((BigDecimal) event.getCurrentState(
        lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_CRAMOUNT)));
    BigDecimal debit = nullSafe((BigDecimal) event.getCurrentState(
        lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_DRAMOUNT)));
    Object transaction = event.getCurrentState(
        lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION));
    BigDecimal pending = transaction == null ? credit.subtract(debit).abs() : BigDecimal.ZERO;
    event.setCurrentState(pendingProp, pending);
  }

  private static BigDecimal nullSafe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
