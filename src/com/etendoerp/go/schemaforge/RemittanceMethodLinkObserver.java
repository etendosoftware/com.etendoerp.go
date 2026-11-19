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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Objects;

import javax.enterprise.event.Observes;

import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;

/**
 * ETP-6148 — a remittance payment method can only be linked to a Bank financial account
 * ({@code FIN_FINANCIAL_ACCOUNT.TYPE = 'B'}): SEPA collections and transfers move money through a
 * bank account with an IBAN, never through cash or a card.
 *
 * <p>A remittance method is one a provisioned remittance type points at
 * ({@link RemittanceSepaProvisioning#isRemittanceMethod}, the {@code ETGO_SEPA_SCHEME} type
 * parameter). Checked on every channel that writes {@code FIN_FinAcc_PaymentMethod} through the DAL
 * — the NEO financial-account window, MCP, and Classic's Financial Account window — and refused
 * with {@link MessageKeyedException} {@value #MSG_NOT_ALLOWED}, which NEO answers as 422
 * {@code business_rule}. Not flag-gated: the rule is about data, and a remittance method only
 * exists where the flag was on. The provisioning itself only links Bank accounts
 * ({@link FinancialAccountRemittanceSupport}); this guards the other writers, such as a user adding
 * the method by hand in Classic's Financial Account window.
 *
 * <p>Only an <em>active</em> link is checked, so leaving a link inactive is always allowed. On update the check runs only when the link's account,
 * method or active flag change, so an unrelated edit of an existing link is never refused.
 */
public class RemittanceMethodLinkObserver extends EntityPersistenceEventObserver {

  static final String MSG_NOT_ALLOWED = "ETGO_RemittanceMethodAccountNotAllowed";

  /** {@code FIN_FINANCIAL_ACCOUNT.TYPE} of a bank account. */
  static final String TYPE_BANK = "B";

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[] {
          ModelProvider.getInstance().getEntity(FinAccPaymentMethod.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  public void onNew(@Observes EntityNewEvent event) {
    check(event);
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    check(event);
  }

  private void check(EntityPersistenceEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Entity entity = resolveEntities()[0];
    Property activeProp = entity.getProperty(FinAccPaymentMethod.PROPERTY_ACTIVE);
    Property accountProp = entity.getProperty(FinAccPaymentMethod.PROPERTY_ACCOUNT);
    Property methodProp = entity.getProperty(FinAccPaymentMethod.PROPERTY_PAYMENTMETHOD);
    if (!Boolean.TRUE.equals(event.getCurrentState(activeProp))) {
      return;
    }
    if (event instanceof EntityUpdateEvent && !changed((EntityUpdateEvent) event, activeProp,
        accountProp, methodProp)) {
      return;
    }
    FIN_FinancialAccount account = (FIN_FinancialAccount) event.getCurrentState(accountProp);
    FIN_PaymentMethod method = (FIN_PaymentMethod) event.getCurrentState(methodProp);
    if (account == null || method == null || TYPE_BANK.equals(account.getType())) {
      return;
    }
    boolean remittanceMethod;
    OBContext.setAdminMode(true);
    try {
      remittanceMethod = RemittanceSepaProvisioning.isRemittanceMethod(method);
    } finally {
      OBContext.restorePreviousMode();
    }
    if (remittanceMethod) {
      throw new MessageKeyedException(MSG_NOT_ALLOWED);
    }
  }

  private static boolean changed(EntityUpdateEvent event, Property... properties) {
    for (Property property : properties) {
      if (!Objects.equals(idOf(event.getPreviousState(property)),
          idOf(event.getCurrentState(property)))) {
        return true;
      }
    }
    return false;
  }

  private static Object idOf(Object value) {
    return value instanceof org.openbravo.base.structure.BaseOBObject
        ? ((org.openbravo.base.structure.BaseOBObject) value).getId()
        : value;
  }
}
