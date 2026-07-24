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
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.webhookevents.data.DefinedWebHook;
import com.etendoerp.webhookevents.data.DefinedwebhookRole;

/**
 * Unit tests for {@link OnboardingWebhookAccessService}.
 *
 * <p>Follows the established onboarding-service test pattern (see {@code
 * OnboardingPeriodControlServiceTest}): a {@code TestableService} subclass overrides the protected
 * DB "seam" methods (context handling, admin-mode, and the webhook/role/grant resolution steps) so
 * no real database is touched by the {@code wire()} orchestration tests. {@code createGrant}'s own
 * real logic is exercised separately under a {@link MockedStatic} of {@link OBDal}/{@link
 * OBProvider} that swallows the save.
 */
public class OnboardingWebhookAccessServiceTest {

  // ---------------------------------------------------------------------------
  // 1. Context validation
  // ---------------------------------------------------------------------------

  @Test
  public void testWireFailsWhenClientIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.wire(null, "USER-1", "ROLE-1");
      fail("Expected missing clientId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing client"));
    }
  }

  @Test
  public void testWireFailsWhenAdminUserIsMissing() {
    TestableService service = new TestableService();
    try {
      service.wire("CLIENT-1", null, "ROLE-1");
      fail("Expected missing admin user to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin user"));
    }
  }

  @Test
  public void testWireFailsWhenAdminRoleIsMissing() {
    TestableService service = new TestableService();
    try {
      service.wire("CLIENT-1", "USER-1", null);
      fail("Expected missing admin role to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin role"));
    }
  }

  // ---------------------------------------------------------------------------
  // 2. Idempotency — an existing active grant short-circuits before any resolution
  // ---------------------------------------------------------------------------

  @Test
  public void testWireSkipsWhenRoleAlreadyHasActiveGrant() {
    TestableService service = new TestableService();
    service.alreadyGranted = true;

    service.wire("CLIENT-1", "USER-1", "ROLE-1");

    assertFalse("resolveWebhook must not run once already granted", service.resolveWebhookCalled);
    assertFalse("createGrant must not run once already granted", service.createGrantCalled);
    assertFalse("flushChanges must not run once already granted", service.flushed);
  }

  // ---------------------------------------------------------------------------
  // 3. Resolution failures inside wire()
  // ---------------------------------------------------------------------------

  @Test
  public void testWireFailsWhenWebhookNotFound() {
    TestableService service = new TestableService();
    service.webhookResolvesToNull = true;

    try {
      service.wire("CLIENT-1", "USER-1", "ROLE-1");
      fail("Expected missing webhook to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("SFWindowAccessMap"));
    }
    assertFalse("createGrant must not run when the webhook is missing", service.createGrantCalled);
  }

  @Test
  public void testWireFailsWhenAdminRoleNotFound() {
    TestableService service = new TestableService();
    service.roleResolvesToNull = true;

    try {
      service.wire("CLIENT-1", "USER-1", "ROLE-1");
      fail("Expected missing admin role entity to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Admin role not found"));
    }
    assertFalse("createGrant must not run when the role entity is missing", service.createGrantCalled);
  }

  // ---------------------------------------------------------------------------
  // 4. Happy path: grant created, flushed, context restored
  // ---------------------------------------------------------------------------

  @Test
  public void testWireCreatesGrantFlushesAndRestoresContext() {
    TestableService service = new TestableService();
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    service.wire("CLIENT-1", "USER-1", "ROLE-1");

    assertTrue("resolveWebhook must run", service.resolveWebhookCalled);
    assertTrue("createGrant must run", service.createGrantCalled);
    assertTrue("changes must be flushed", service.flushed);
    assertSame("createGrant must receive the resolved role", service.role, service.createGrantRoleArg);
    assertSame("createGrant must receive the resolved webhook", service.webhook,
        service.createGrantWebhookArg);
    assertSame(previous, OBContext.getOBContext());
  }

  @Test
  public void testWireRestoresPreviousContextAfterFailure() {
    TestableService service = new TestableService();
    service.webhookResolvesToNull = true;
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    try {
      service.wire("CLIENT-1", "USER-1", "ROLE-1");
      fail("Expected delegated failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("SFWindowAccessMap"));
    }

    assertSame(previous, OBContext.getOBContext());
  }

  // ---------------------------------------------------------------------------
  // 5. createGrant — real logic, direct saves swallowed by a MockedStatic
  // ---------------------------------------------------------------------------

  @Test
  public void testCreateGrantSetsAllFieldsAndSaves() {
    RealLogicService service = new RealLogicService();
    Client client = mock(Client.class);
    Organization starOrg = mock(Organization.class);
    Role role = mock(Role.class);
    DefinedWebHook webhook = mock(DefinedWebHook.class);
    org.openbravo.model.ad.module.Module module = mock(org.openbravo.model.ad.module.Module.class);
    when(webhook.getModule()).thenReturn(module);
    service.starOrg = starOrg;

    DefinedwebhookRole grant = mock(DefinedwebhookRole.class);
    OBDal dal = mock(OBDal.class);
    when(dal.get(Client.class, "CLIENT-1")).thenReturn(client);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(DefinedwebhookRole.class)).thenReturn(grant);

      service.createGrant("CLIENT-1", role, webhook);

      org.mockito.Mockito.verify(grant).setClient(client);
      org.mockito.Mockito.verify(grant).setOrganization(starOrg);
      org.mockito.Mockito.verify(grant).setActive(true);
      org.mockito.Mockito.verify(grant).setRole(role);
      org.mockito.Mockito.verify(grant).setSmfwheDefinedwebhook(webhook);
      org.mockito.Mockito.verify(grant).setModuleID(module);
      org.mockito.Mockito.verify(dal).save(grant);
    }
  }

  // ---------------------------------------------------------------------------
  // Test doubles
  // ---------------------------------------------------------------------------

  private abstract static class ContextStubbedService extends OnboardingWebhookAccessService {

    @Override
    protected OBContext captureCurrentContext() {
      return OBContext.getOBContext();
    }

    @Override
    protected void applyExecutionContext(String adminUserId, String adminRoleId,
        String clientId, String orgId) {
      OBContext.setOBContext(mock(OBContext.class));
    }

    @Override
    protected void restoreExecutionContext(OBContext previousContext) {
      OBContext.setOBContext(previousContext);
    }

    @Override
    protected void enterAdminMode() {
      // no-op
    }

    @Override
    protected void exitAdminMode() {
      // no-op
    }
  }

  /**
   * Subclass used by the {@code wire()} tests: every resolution/persistence step is overridden as
   * a seam, so only the orchestration logic in {@code wire()} itself is exercised.
   */
  private static final class TestableService extends ContextStubbedService {

    final Role role = mock(Role.class);
    final DefinedWebHook webhook = mock(DefinedWebHook.class);

    boolean alreadyGranted;
    boolean webhookResolvesToNull;
    boolean roleResolvesToNull;

    boolean resolveWebhookCalled;
    boolean createGrantCalled;
    boolean flushed;

    Role createGrantRoleArg;
    DefinedWebHook createGrantWebhookArg;

    @Override
    protected void flushChanges() {
      flushed = true;
    }

    @Override
    protected boolean hasActiveGrant(String roleId) {
      return alreadyGranted;
    }

    @Override
    protected DefinedWebHook resolveWebhook() {
      resolveWebhookCalled = true;
      return webhookResolvesToNull ? null : webhook;
    }

    @Override
    protected Role resolveRole(String roleId) {
      return roleResolvesToNull ? null : role;
    }

    @Override
    protected void createGrant(String clientId, Role role, DefinedWebHook webhook) {
      createGrantCalled = true;
      createGrantRoleArg = role;
      createGrantWebhookArg = webhook;
    }
  }

  /**
   * Subclass used by {@code testCreateGrantSetsAllFieldsAndSaves}, which exercises the REAL
   * {@code createGrant} logic. Only {@code resolveOrganization} is stubbed (the "*" org lookup);
   * the direct {@code OBDal}/{@code OBProvider} calls are covered by the test's own
   * {@link MockedStatic}s.
   */
  private static final class RealLogicService extends ContextStubbedService {

    Organization starOrg;

    @Override
    protected Organization resolveOrganization(String orgId) {
      return starOrg;
    }
  }
}
