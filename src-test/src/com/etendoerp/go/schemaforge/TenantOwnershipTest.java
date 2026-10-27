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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for {@link TenantOwnership} — the guard that stops an id supplied by a request from
 * resolving to another tenant's row (ETP-4950), including the client-only variant the NEO write
 * paths use for the references a request sets (ETP-5785).
 *
 * @covers com.etendoerp.go.schemaforge.TenantOwnership
 *
 * <p>These matter more than most: every other test in the module runs with no {@code OBContext} on
 * the thread, where the guard deliberately fails open, so this is the only place the isolating
 * behaviour is actually exercised.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class TenantOwnershipTest {

  private static final String ACCOUNT_ID = "ACC-1";
  private static final String OWN_CLIENT = "client-own";
  private static final String OTHER_CLIENT = "client-other";
  private static final String OWN_ORG = "org-own";
  private static final String OTHER_ORG = "org-other";
  private static final String SYSTEM_CLIENT = "0";
  private static final String ACCOUNT_ENTITY = "FIN_Financial_Account";

  /**
   * Releases the inline mock-maker references created during the test. Without this they
   * accumulate across the module's single test JVM and push the fork past its heap cap.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** A financial account owned by the given client / organization. */
  private static FIN_FinancialAccount accountOf(String clientId, String orgId) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    if (clientId != null) {
      Client client = mock(Client.class);
      when(client.getId()).thenReturn(clientId);
      when(account.getClient()).thenReturn(client);
    }
    if (orgId != null) {
      Organization organization = mock(Organization.class);
      when(organization.getId()).thenReturn(orgId);
      when(account.getOrganization()).thenReturn(organization);
    }
    return account;
  }

  /** Pins the session's readable clients / organizations for the duration of the mocked static. */
  private static void stubContext(MockedStatic<OBContext> obContext, String[] clients,
      String[] orgs) {
    OBContext context = mock(OBContext.class);
    when(context.getReadableClients()).thenReturn(clients);
    when(context.getReadableOrganizations()).thenReturn(orgs);
    obContext.when(OBContext::getOBContext).thenReturn(context);
  }

  // ── loadOwned ──────────────────────────────────────────────────────────────

  /** An entity of the caller's own client and organization is returned. */
  @Test
  public void testLoadOwnedReturnsAnEntityOfTheCurrentTenant() {
    FIN_FinancialAccount account = accountOf(OWN_CLIENT, OWN_ORG);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinancialAccount.class, ACCOUNT_ID)).thenReturn(account);
      stubContext(obContext, new String[] {OWN_CLIENT}, new String[] {OWN_ORG});

      assertNotNull(TenantOwnership.loadOwned(FIN_FinancialAccount.class, ACCOUNT_ID));
    }
  }

  /**
   * The whole point: an id that resolves to ANOTHER client's row comes back as {@code null}, so the
   * caller answers "not found" instead of serving or mutating that tenant's data.
   */
  @Test
  public void testLoadOwnedHidesAnEntityOfAnotherClient() {
    FIN_FinancialAccount foreign = accountOf(OTHER_CLIENT, OTHER_ORG);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinancialAccount.class, ACCOUNT_ID)).thenReturn(foreign);
      stubContext(obContext, new String[] {OWN_CLIENT}, new String[] {OWN_ORG});

      assertNull(TenantOwnership.loadOwned(FIN_FinancialAccount.class, ACCOUNT_ID));
    }
  }

  /** Same client, but an organization outside the role's tree, is hidden too. */
  @Test
  public void testLoadOwnedHidesAnEntityOfAnUnreadableOrganization() {
    FIN_FinancialAccount sibling = accountOf(OWN_CLIENT, OTHER_ORG);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinancialAccount.class, ACCOUNT_ID)).thenReturn(sibling);
      stubContext(obContext, new String[] {OWN_CLIENT}, new String[] {OWN_ORG});

      assertNull(TenantOwnership.loadOwned(FIN_FinancialAccount.class, ACCOUNT_ID));
    }
  }

  /** A blank id never reaches the DAL at all. */
  @Test
  public void testLoadOwnedShortCircuitsOnABlankId() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      assertNull(TenantOwnership.loadOwned(FIN_FinancialAccount.class, "  "));

      verify(dal, never()).get(FIN_FinancialAccount.class, "  ");
    }
  }

  /** An unknown id stays null rather than blowing up in the visibility check. */
  @Test
  public void testLoadOwnedReturnsNullForAnUnknownId() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinancialAccount.class, ACCOUNT_ID)).thenReturn(null);
      stubContext(obContext, new String[] {OWN_CLIENT}, new String[] {OWN_ORG});

      assertNull(TenantOwnership.loadOwned(FIN_FinancialAccount.class, ACCOUNT_ID));
    }
  }

  // ── isVisibleToCurrentTenant ───────────────────────────────────────────────

  /**
   * With no session on the thread the guard fails OPEN.
   *
   * <p>Deliberate: background processes and the module's own unit tests run without an
   * {@code OBContext}, and reporting every row as missing there would break them. It is also why
   * this class carries the only real coverage of the isolating behaviour.
   */
  @Test
  public void testIsVisibleFailsOpenWithoutASession() {
    FIN_FinancialAccount foreign = accountOf(OTHER_CLIENT, OTHER_ORG);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(null);

      assertTrue(TenantOwnership.isVisibleToCurrentTenant(foreign));
    }
  }

  /** A null entity is never visible. */
  @Test
  public void testIsVisibleRejectsNull() {
    assertFalse(TenantOwnership.isVisibleToCurrentTenant(null));
  }

  /**
   * An entity with no client at all is rejected: a persisted tenant row always has one, so a null
   * here means the check cannot be made — and a guard that cannot decide must fail closed.
   */
  @Test
  public void testIsVisibleRejectsAnEntityWithoutAClient() {
    FIN_FinancialAccount orphan = accountOf(null, OWN_ORG);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      stubContext(obContext, new String[] {OWN_CLIENT}, new String[] {OWN_ORG});

      assertFalse(TenantOwnership.isVisibleToCurrentTenant(orphan));
    }
  }

  /** A missing readable-client set fails closed instead of throwing. */
  @Test
  public void testIsVisibleFailsClosedOnANullReadableSet() {
    FIN_FinancialAccount account = accountOf(OWN_CLIENT, OWN_ORG);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      stubContext(obContext, null, null);

      assertFalse(TenantOwnership.isVisibleToCurrentTenant(account));
    }
  }

  /** An entity with no organization is judged on its client alone. */
  @Test
  public void testIsVisibleAcceptsAnEntityWithoutAnOrganization() {
    FIN_FinancialAccount account = accountOf(OWN_CLIENT, null);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      stubContext(obContext, new String[] {OWN_CLIENT}, new String[] {OWN_ORG});

      assertTrue(TenantOwnership.isVisibleToCurrentTenant(account));
    }
  }

  // ── loadOwnedByClient / belongsToReadableClient (ETP-5785) ────────────────

  /** Stubs {@code OBDal.get(entityName, id)} to return {@code row}. */
  private static void stubDalGet(MockedStatic<OBDal> obDal, BaseOBObject row) {
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    when(dal.get(ACCOUNT_ENTITY, ACCOUNT_ID)).thenReturn(row);
  }

  /** A row of the caller's own client is returned. */
  @Test
  public void testLoadOwnedByClientReturnsARowOfTheCurrentClient() {
    FIN_FinancialAccount account = accountOf(OWN_CLIENT, OWN_ORG);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      stubDalGet(obDal, account);
      stubContext(obContext, new String[] {OWN_CLIENT, SYSTEM_CLIENT}, new String[] {OWN_ORG});

      assertNotNull(TenantOwnership.loadOwnedByClient(ACCOUNT_ENTITY, ACCOUNT_ID));
    }
  }

  /** A System row (client {@code 0}) is shared by every tenant and therefore accepted. */
  @Test
  public void testLoadOwnedByClientAcceptsASystemRow() {
    FIN_FinancialAccount system = accountOf(SYSTEM_CLIENT, "0");
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      stubDalGet(obDal, system);
      stubContext(obContext, new String[] {OWN_CLIENT, SYSTEM_CLIENT}, new String[] {OWN_ORG});

      assertNotNull(TenantOwnership.loadOwnedByClient(ACCOUNT_ENTITY, ACCOUNT_ID));
    }
  }

  /** The point of ETP-5785: a row of another client is reported as missing. */
  @Test
  public void testLoadOwnedByClientHidesARowOfAnotherClient() {
    FIN_FinancialAccount foreign = accountOf(OTHER_CLIENT, OTHER_ORG);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      stubDalGet(obDal, foreign);
      stubContext(obContext, new String[] {OWN_CLIENT, SYSTEM_CLIENT}, new String[] {OWN_ORG});

      assertNull(TenantOwnership.loadOwnedByClient(ACCOUNT_ENTITY, ACCOUNT_ID));
    }
  }

  /**
   * Unlike {@link TenantOwnership#loadOwned}, the organization is not judged: a row of an
   * unreadable organization of the same client is still the tenant's own.
   */
  @Test
  public void testLoadOwnedByClientIgnoresTheOrganization() {
    FIN_FinancialAccount sibling = accountOf(OWN_CLIENT, OTHER_ORG);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      stubDalGet(obDal, sibling);
      stubContext(obContext, new String[] {OWN_CLIENT, SYSTEM_CLIENT}, new String[] {OWN_ORG});

      assertNotNull(TenantOwnership.loadOwnedByClient(ACCOUNT_ENTITY, ACCOUNT_ID));
    }
  }

  /** An unknown id and a blank id both come back as {@code null}; a blank one never hits the DAL. */
  @Test
  public void testLoadOwnedByClientReturnsNullForUnknownOrBlankIds() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubContext(obContext, new String[] {OWN_CLIENT, SYSTEM_CLIENT}, new String[] {OWN_ORG});

      assertNull(TenantOwnership.loadOwnedByClient(ACCOUNT_ENTITY, ACCOUNT_ID));
      assertNull(TenantOwnership.loadOwnedByClient(ACCOUNT_ENTITY, " "));
      assertNull(TenantOwnership.loadOwnedByClient((String) null, ACCOUNT_ID));

      verify(dal, never()).get(ACCOUNT_ENTITY, " ");
    }
  }

  /** A client row is the tenant itself: judged by its own id against the readable clients. */
  @Test
  public void testBelongsToReadableClientJudgesAClientRowByItsId() {
    Client own = mock(Client.class);
    when(own.getId()).thenReturn(OWN_CLIENT);
    Client other = mock(Client.class);
    when(other.getId()).thenReturn(OTHER_CLIENT);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      stubContext(obContext, new String[] {OWN_CLIENT, SYSTEM_CLIENT}, new String[] {OWN_ORG});

      assertTrue(TenantOwnership.belongsToReadableClient(own));
      assertFalse(TenantOwnership.belongsToReadableClient(other));
      assertFalse(TenantOwnership.belongsToReadableClient(null));
    }
  }

  /** Same fail-open contract as the rest of the class when no session is on the thread. */
  @Test
  public void testBelongsToReadableClientFailsOpenWithoutASession() {
    FIN_FinancialAccount foreign = accountOf(OTHER_CLIENT, OTHER_ORG);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(null);

      assertTrue(TenantOwnership.belongsToReadableClient(foreign));
    }
  }

  /** Client-enabled entities and the client entity itself are tenant-scoped; nothing else is. */
  @Test
  public void testIsTenantScoped() {
    Entity clientEnabled = mock(Entity.class);
    when(clientEnabled.isClientEnabled()).thenReturn(true);
    Entity clientEntity = mock(Entity.class);
    when(clientEntity.getName()).thenReturn(Client.ENTITY_NAME);
    Entity shared = mock(Entity.class);
    when(shared.getName()).thenReturn("Shared");

    assertTrue(TenantOwnership.isTenantScoped(clientEnabled));
    assertTrue(TenantOwnership.isTenantScoped(clientEntity));
    assertFalse(TenantOwnership.isTenantScoped(shared));
    assertFalse(TenantOwnership.isTenantScoped(null));
  }
}
