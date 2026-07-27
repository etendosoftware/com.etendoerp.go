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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;

/**
 * Unit tests for {@link OnboardingRoleProvisioningService}.
 *
 * <p>A {@code TestableService} subclass overrides every protected DB "seam" method so no real
 * database is touched by the {@code wire()} orchestration tests.
 */
public class OnboardingRoleProvisioningServiceTest {

  private static final String[] ROLE_NAMES = { "Finance", "Sales", "Purchasing", "Inventory" };

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
  // 2. Role cloning — idempotency
  // ---------------------------------------------------------------------------

  @Test
  public void testWireSkipsCloneWhenAllFourRolesAlreadyExist() {
    TestableService service = new TestableService();
    for (String name : ROLE_NAMES) {
      service.existingTargetRoles.put(name, mock(Role.class));
    }

    service.wire("CLIENT-1", "USER-1", "ROLE-1");

    assertTrue("no clones should happen", service.clonedRoleNames.isEmpty());
  }

  @Test
  public void testWireClonesOnlyTheMissingRoles() {
    TestableService service = new TestableService();
    service.existingTargetRoles.put("Finance", mock(Role.class));
    service.existingTargetRoles.put("Sales", mock(Role.class));

    service.wire("CLIENT-1", "USER-1", "ROLE-1");

    assertEquals(new HashSet<>(java.util.Arrays.asList("Purchasing", "Inventory")),
        new HashSet<>(service.clonedRoleNames));
  }

  @Test
  public void testWireFailsWhenGoClientTemplateRoleIsMissing() {
    TestableService service = new TestableService();
    service.missingTemplateRoleName = "Finance";

    try {
      service.wire("CLIENT-1", "USER-1", "ROLE-1");
      fail("Expected missing GOClient template role to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Finance"));
    }
  }

  @Test
  public void testWireClonesShowAcctFieldsAndWindowAccessForEachNewRole() {
    TestableService service = new TestableService();
    service.showAcctByRoleName.put("Finance", "Y");
    service.showAcctByRoleName.put("Sales", "N");

    service.wire("CLIENT-1", "USER-1", "ROLE-1");

    assertEquals("Y", service.writtenShowAcctFields.get("Finance"));
    assertEquals("N", service.writtenShowAcctFields.get("Sales"));
    assertTrue("window access must be cloned for every new role",
        service.windowAccessClonedFor.containsAll(java.util.Arrays.asList(ROLE_NAMES)));
  }

  // ---------------------------------------------------------------------------
  // 3. Context handling
  // ---------------------------------------------------------------------------

  @Test
  public void testWireFlushesAndRestoresContextOnSuccess() {
    TestableService service = new TestableService();
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    service.wire("CLIENT-1", "USER-1", "ROLE-1");

    assertTrue("changes must be flushed", service.flushed);
    assertSame(previous, OBContext.getOBContext());
  }

  @Test
  public void testWireRestoresPreviousContextAfterFailure() {
    TestableService service = new TestableService();
    service.missingTemplateRoleName = "Finance";
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    try {
      service.wire("CLIENT-1", "USER-1", "ROLE-1");
      fail("Expected delegated failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Finance"));
    }

    assertSame(previous, OBContext.getOBContext());
  }

  // ---------------------------------------------------------------------------
  // Test doubles
  // ---------------------------------------------------------------------------

  private static Role namedRole(String name) {
    Role role = mock(Role.class);
    when(role.getId()).thenReturn(name.toUpperCase() + "-ID");
    when(role.getName()).thenReturn(name);
    return role;
  }

  private abstract static class ContextStubbedService extends OnboardingRoleProvisioningService {

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
   * Subclass used by every {@code wire()} test: every resolution/persistence step is overridden
   * as a seam, so only the orchestration logic in {@code wire()} itself is exercised.
   */
  private static final class TestableService extends ContextStubbedService {

    final Map<String, Role> existingTargetRoles = new HashMap<>();
    final Map<String, String> showAcctByRoleName = new HashMap<>();
    final Map<String, String> writtenShowAcctFields = new HashMap<>();
    final List<String> clonedRoleNames = new ArrayList<>();
    final Set<String> windowAccessClonedFor = new HashSet<>();

    String missingTemplateRoleName;
    boolean flushed;

    private final Map<String, Role> clonedByName = new HashMap<>();
    private final Map<String, String> sourceRoleNameById = new HashMap<>();

    @Override
    protected void flushChanges() {
      flushed = true;
    }

    @Override
    protected Role resolveRoleByName(String clientId, String roleName) {
      if ("802509E12436405C86BA1FD5B1DF508C".equals(clientId)) {
        // GOClient template lookup.
        if (roleName.equals(missingTemplateRoleName)) {
          return null;
        }
        Role source = namedRole(roleName);
        sourceRoleNameById.put(source.getId(), roleName);
        return source;
      }
      return existingTargetRoles.get(roleName);
    }

    @Override
    protected Role cloneRoleAttributes(String clientId, Role source) {
      clonedRoleNames.add(source.getName());
      Role clone = namedRole(source.getName() + "-CLONE");
      clonedByName.put(source.getName(), clone);
      return clone;
    }

    @Override
    protected String readShowAcctFields(String roleId) {
      String name = sourceRoleNameById.get(roleId);
      return showAcctByRoleName.getOrDefault(name, "N");
    }

    @Override
    protected void writeShowAcctFields(String roleId, String value) {
      for (Map.Entry<String, Role> entry : clonedByName.entrySet()) {
        if (entry.getValue().getId().equals(roleId)) {
          writtenShowAcctFields.put(entry.getKey(), value);
        }
      }
    }

    @Override
    protected List<WindowAccess> resolveActiveWindowAccess(Role role) {
      // Called with the GOClient SOURCE role (see cloneWindowAccess) — its name is the same key
      // used in clonedByName, so this records which role's window access was (attempted to be)
      // cloned without needing real WindowAccess rows.
      windowAccessClonedFor.add(role.getName());
      return new ArrayList<>();
    }
  }
}
