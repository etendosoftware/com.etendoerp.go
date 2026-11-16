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
package com.etendoerp.go.accounts;

import java.util.Date;
import java.util.Objects;

import javax.enterprise.event.Observes;

import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * ETP-6223 — refuses to set the demo expiry date ({@code ETGO_ACCOUNT.DEMO_EXPIRES_AT}) of an
 * account that owns no running demo, as {@code PATCH /accounts/{id}} does
 * ({@code 409 ACCOUNT_HAS_NO_DEMO}). Without it, Classic (<i>Etendo GO Accounts</i> window) would
 * save a date that no environment reads.
 *
 * <p>Clearing the date is always allowed, so a value left on an account whose demo was replaced
 * by a productive environment can be removed. Updates that leave the date as it was are not
 * checked.
 */
public class AccountDemoExpiryHandler extends EntityPersistenceEventObserver {

  static final String MSG_NO_DEMO = "ETGO_AccountHasNoDemo";

  private static Entity[] entities;

  private final DemoExpiryService demoExpiry;

  /** CDI constructor. */
  public AccountDemoExpiryHandler() {
    this(new DemoExpiryService());
  }

  AccountDemoExpiryHandler(DemoExpiryService demoExpiry) {
    this.demoExpiry = demoExpiry;
  }

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[] { ModelProvider.getInstance().getEntity(Account.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  /**
   * Rejects a new demo expiry date on an account without a running demo.
   *
   * @param event the update event
   */
  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Property expiry = resolveEntities()[0].getProperty(Account.PROPERTY_DEMOEXPIRESAT);
    Object current = event.getCurrentState(expiry);
    if (current == null || sameInstant(event.getPreviousState(expiry), current)) {
      return;
    }
    if (!demoExpiry.ownsDemo((Account) event.getTargetInstance())) {
      throw new OBException(OBMessageUtils.messageBD(MSG_NO_DEMO));
    }
  }

  /**
   * Compares two stored dates by instant: Hibernate hands back a {@link java.sql.Timestamp} and
   * Classic sets a {@link Date}, and {@code Timestamp.equals(Date)} is false even for the same
   * instant.
   */
  static boolean sameInstant(Object previous, Object current) {
    if (previous instanceof Date && current instanceof Date) {
      return ((Date) previous).getTime() == ((Date) current).getTime();
    }
    return Objects.equals(previous, current);
  }
}
