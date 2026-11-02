/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.concurrent.Callable;

import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.ClientEnabled;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

/**
 * DAL-level backstop for tenant isolation on every write made while serving a NEO or MCP request
 * (ETP-5819).
 *
 * <p><b>Why this exists.</b> NEO runs its requests in admin mode with the DAL's client/organization
 * write check switched off, so isolation rested only on NEO's own guards (ETP-5785) — a new write
 * path that skipped them would write another tenant's rows. Turning the core check back on is not
 * an option: it also enforces the role's <i>writable organizations</i>, which drop {@code *} for
 * every GO role at user level {@code "  O"} (see {@link StarOrgWriteScope}), so every write touching
 * a {@code *} row — document sequences, shared master data — would fail; and it reads only the
 * innermost admin-mode frame, so any nested {@code setAdminMode()} would switch it off again.
 *
 * <p><b>What it checks.</b> A tenant is a client, so this checks the client only: inside a guarded
 * request, a row whose client is not the context's current client cannot be inserted, updated or
 * deleted. That includes client {@code 0} (System) rows, which a tenant request must never change.
 * For an update the previous client is checked too, so a row cannot be pulled into the tenant by
 * rewriting its client. Organization scoping within the tenant stays with NEO's guards. The one
 * exception is explicit: code that knowingly writes System rows for the tenant runs that write in
 * {@link #allowingSystemRows}, which admits client {@code 0} — never another tenant.
 *
 * <p><b>Why a thread flag and a flush.</b> The observer is active only between {@link #call}'s entry
 * and exit, on that thread, so background processes, imports and every non-NEO servlet are
 * untouched. Hibernate fires these events on flush, and core commits after the servlet returns —
 * outside the flag — so {@link #call} flushes before clearing it.
 */
public class NeoTenantWriteGuard extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(NeoTenantWriteGuard.class);

  private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

  private static final ThreadLocal<Boolean> SYSTEM_ROWS_ALLOWED = new ThreadLocal<>();

  private static final String SYSTEM_CLIENT_ID = "0";

  /** The client property every {@link ClientEnabled} entity shares. */
  private static final String CLIENT_PROPERTY = "client";

  /**
   * Read only by {@code isValidEvent}, which {@link #check} does not use: the flag, not the entity,
   * decides whether a write is checked.
   */
  private static final Entity[] NO_ENTITIES = new Entity[0];

  /**
   * Runs {@code body} with the guard active on this thread and flushes its pending writes before
   * leaving, so they are checked here and not at commit time. Nested calls join the outer scope.
   *
   * <p>shortcut: a refusal raised by the closing flush arrives after a handler may already have
   * written its response; the transaction still rolls back, so only the status the caller sees is
   * wrong. It can only happen when a write already slipped past NEO's own guards.
   *
   * @param body the request work to guard
   * @param <T>  the value {@code body} produces
   * @return the value produced by {@code body}
   * @throws Exception whatever {@code body} or the flush throws
   */
  public static <T> T call(Callable<T> body) throws Exception {
    if (isActive()) {
      return body.call();
    }
    ACTIVE.set(Boolean.TRUE);
    try {
      T result = body.call();
      OBDal.getInstance().flush();
      return result;
    } finally {
      ACTIVE.remove();
    }
  }

  /**
   * Runs {@code body} letting the guard admit rows of client {@code 0} (System); rows of another
   * tenant are still refused. Only for a write the caller already exempts, on purpose, from core's
   * own client check with {@code setAdminMode(false)} — role template accesses copied onto a
   * tenant's role keep the template's System client, for instance.
   *
   * @param body the write, typically a flush, that may touch System rows
   */
  public static void allowingSystemRows(Runnable body) {
    Boolean previous = SYSTEM_ROWS_ALLOWED.get();
    SYSTEM_ROWS_ALLOWED.set(Boolean.TRUE);
    try {
      body.run();
    } finally {
      if (previous == null) {
        SYSTEM_ROWS_ALLOWED.remove();
      } else {
        SYSTEM_ROWS_ALLOWED.set(previous);
      }
    }
  }

  static boolean isActive() {
    return Boolean.TRUE.equals(ACTIVE.get());
  }

  @Override
  protected Entity[] getObservedEntities() {
    return NO_ENTITIES;
  }

  /**
   * @param event the persistence event
   */
  public void onSave(@Observes EntityNewEvent event) {
    check(event, null);
  }

  /**
   * @param event the persistence event
   */
  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isActive()) {
      return;
    }
    Property clientProperty = event.getTargetInstance()
        .getEntity()
        .getProperty(CLIENT_PROPERTY, false);
    Object previous = clientProperty != null ? event.getPreviousState(clientProperty) : null;
    check(event, previous instanceof Client ? ((Client) previous).getId() : null);
  }

  /**
   * @param event the persistence event
   */
  public void onDelete(@Observes EntityDeleteEvent event) {
    check(event, null);
  }

  // isValidEvent is deliberately not used: it skips events while triggers are disabled, and a
  // security check must not.
  private void check(EntityPersistenceEvent event, String previousClientId) {
    if (!isActive()) {
      return;
    }
    BaseOBObject target = event.getTargetInstance();
    OBContext context = OBContext.getOBContext();
    String currentClientId = context != null && context.getCurrentClient() != null
        ? context.getCurrentClient().getId()
        : null;
    String targetClientId = clientIdOf(target);
    boolean foreign = isForeign(targetClientId, currentClientId)
        || isForeign(previousClientId, currentClientId);
    if (!foreign) {
      return;
    }
    log.error("Refused a write on {} {} of client {} from a request of client {}",
        target.getEntityName(), target.getId(),
        previousClientId != null ? previousClientId : targetClientId, currentClientId);
    SessionHandler.getInstance().setDoRollback(true);
    throw new OBSecurityException("Write access denied");
  }

  /** {@code null} for a row without a client yet: the DAL assigns the current one on save. */
  private static String clientIdOf(BaseOBObject target) {
    if (target instanceof Client) {
      return (String) target.getId();
    }
    if (target instanceof ClientEnabled) {
      Client client = ((ClientEnabled) target).getClient();
      return client != null ? client.getId() : null;
    }
    return null;
  }

  /** Fails closed: a known row client with no current client to match is foreign. */
  private static boolean isForeign(String rowClientId, String currentClientId) {
    if (rowClientId == null || rowClientId.equals(currentClientId)) {
      return false;
    }
    boolean admittedSystemRow = SYSTEM_CLIENT_ID.equals(rowClientId)
        && Boolean.TRUE.equals(SYSTEM_ROWS_ALLOWED.get());
    return !admittedSystemRow;
  }
}
