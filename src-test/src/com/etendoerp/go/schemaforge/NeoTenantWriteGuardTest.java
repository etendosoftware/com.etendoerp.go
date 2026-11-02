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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.ClientEnabled;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

/**
 * ETP-5819: inside a guarded request no row of another client — System included — can be written,
 * and outside one nothing changes. No DB: the model, the context and the session are mocked.
 *
 * @covers com.etendoerp.go.schemaforge.NeoTenantWriteGuard
 */
class NeoTenantWriteGuardTest {

  private static final String TENANT = "TENANT-A";
  private static final String OTHER_TENANT = "TENANT-B";
  private static final String SYSTEM = "0";

  private NeoTenantWriteGuard guard;
  private OBDal dal;
  private SessionHandler session;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<SessionHandler> sessionStatic;

  @BeforeEach
  void setUp() {
    guard = new NeoTenantWriteGuard();
    contextStatic = mockStatic(OBContext.class);
    withCurrentClient(TENANT);
    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    session = mock(SessionHandler.class);
    sessionStatic = mockStatic(SessionHandler.class);
    sessionStatic.when(SessionHandler::getInstance).thenReturn(session);
  }

  @AfterEach
  void tearDown() {
    contextStatic.close();
    dalStatic.close();
    sessionStatic.close();
  }

  // ------------------------------------------------------------ builders

  private void withCurrentClient(String clientId) {
    OBContext context = mock(OBContext.class);
    Client client = clientId != null ? client(clientId) : null;
    when(context.getCurrentClient()).thenReturn(client);
    contextStatic.when(OBContext::getOBContext).thenReturn(context);
  }

  private static Client client(String id) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(id);
    return client;
  }

  private static BaseOBObject row(String clientId) {
    BaseOBObject row = mock(BaseOBObject.class, withSettings().extraInterfaces(ClientEnabled.class));
    Client client = clientId != null ? client(clientId) : null;
    when(((ClientEnabled) row).getClient()).thenReturn(client);
    when(row.getEntityName()).thenReturn("SomeEntity");
    return row;
  }

  private static EntityNewEvent newEvent(BaseOBObject target) {
    EntityNewEvent event = mock(EntityNewEvent.class);
    when(event.getTargetInstance()).thenReturn(target);
    return event;
  }

  private static EntityDeleteEvent deleteEvent(BaseOBObject target) {
    EntityDeleteEvent event = mock(EntityDeleteEvent.class);
    when(event.getTargetInstance()).thenReturn(target);
    return event;
  }

  private static EntityUpdateEvent updateEvent(BaseOBObject target, String previousClientId) {
    Entity entity = mock(Entity.class);
    Property clientProperty = mock(Property.class);
    when(entity.getProperty("client", false)).thenReturn(clientProperty);
    when(target.getEntity()).thenReturn(entity);
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    when(event.getTargetInstance()).thenReturn(target);
    Client previous = client(previousClientId);
    when(event.getPreviousState(clientProperty)).thenReturn(previous);
    return event;
  }

  /** Runs {@code body} inside the guard, the way NeoServlet and McpServlet do. */
  private static void guarded(Runnable body) throws Exception {
    NeoTenantWriteGuard.call(() -> {
      body.run();
      return null;
    });
  }

  private void assertRefused(Runnable write) {
    assertThrows(OBSecurityException.class, () -> guarded(write));
    verify(session).setDoRollback(true);
  }

  // ------------------------------------------------------------ outside a guarded request

  @Test
  void outsideAGuardedRequestNothingIsChecked() {
    assertFalse(NeoTenantWriteGuard.isActive());
    assertDoesNotThrow(() -> guard.onSave(newEvent(row(OTHER_TENANT))));
    assertDoesNotThrow(() -> guard.onDelete(deleteEvent(row(SYSTEM))));
    verify(session, never()).setDoRollback(true);
  }

  // ------------------------------------------------------------ inside a guarded request

  @Test
  void ownTenantWritesPass() {
    assertDoesNotThrow(() -> guarded(() -> {
      guard.onSave(newEvent(row(TENANT)));
      guard.onUpdate(updateEvent(row(TENANT), TENANT));
      guard.onDelete(deleteEvent(row(TENANT)));
    }));
    verify(session, never()).setDoRollback(true);
  }

  @Test
  void aNewRowWithoutClientYetPasses() {
    assertDoesNotThrow(() -> guarded(() -> guard.onSave(newEvent(row(null)))));
  }

  @Test
  void insertingIntoAnotherTenantIsRefused() {
    assertRefused(() -> guard.onSave(newEvent(row(OTHER_TENANT))));
  }

  @Test
  void updatingAnotherTenantsRowIsRefused() {
    assertRefused(() -> guard.onUpdate(updateEvent(row(OTHER_TENANT), OTHER_TENANT)));
  }

  @Test
  void pullingAnotherTenantsRowIntoOursIsRefused() {
    assertRefused(() -> guard.onUpdate(updateEvent(row(TENANT), OTHER_TENANT)));
  }

  @Test
  void pushingOurRowIntoAnotherTenantIsRefused() {
    assertRefused(() -> guard.onUpdate(updateEvent(row(OTHER_TENANT), TENANT)));
  }

  @Test
  void deletingAnotherTenantsRowIsRefused() {
    assertRefused(() -> guard.onDelete(deleteEvent(row(OTHER_TENANT))));
  }

  /** The decision for shared system data: a tenant request never changes a client 0 row. */
  @Test
  void systemRowsAreRefused() {
    assertRefused(() -> guard.onUpdate(updateEvent(row(SYSTEM), SYSTEM)));
  }

  @Test
  void theClientRowItselfIsJudgedByItsId() {
    Client own = client(TENANT);
    Client other = client(OTHER_TENANT);
    assertDoesNotThrow(() -> guarded(() -> guard.onDelete(deleteEvent(own))));
    assertRefused(() -> guard.onDelete(deleteEvent(other)));
  }

  @Test
  void withoutACurrentClientAForeignRowFailsClosed() {
    withCurrentClient(null);
    assertRefused(() -> guard.onSave(newEvent(row(TENANT))));
  }

  // ------------------------------------------------------------ the scope itself

  @Test
  void theScopeFlushesInsideAndClearsTheFlag() throws Exception {
    boolean[] seen = new boolean[1];
    String result = NeoTenantWriteGuard.call(() -> {
      seen[0] = NeoTenantWriteGuard.isActive();
      return "done";
    });
    assertEquals("done", result);
    assertTrue(seen[0]);
    assertFalse(NeoTenantWriteGuard.isActive());
    verify(dal, times(1)).flush();
  }

  @Test
  void theFlagIsClearedWhenTheBodyThrows() {
    assertThrows(IllegalStateException.class, () -> NeoTenantWriteGuard.call(() -> {
      throw new IllegalStateException("boom");
    }));
    assertFalse(NeoTenantWriteGuard.isActive());
  }

  @Test
  void aNestedScopeJoinsTheOuterOneAndFlushesOnce() throws Exception {
    NeoTenantWriteGuard.call(() -> {
      NeoTenantWriteGuard.call(() -> null);
      assertTrue(NeoTenantWriteGuard.isActive());
      return null;
    });
    assertFalse(NeoTenantWriteGuard.isActive());
    verify(dal, times(1)).flush();
  }
}
