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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for {@link TenantOwnership} — the guard that stops an id supplied by a request from
 * resolving to another tenant's row (ETP-4950).
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
}
