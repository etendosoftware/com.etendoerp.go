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
package com.etendoerp.go.roles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

/**
 * ETP-5565 — the composition hook ({@code UserRoleCompositionService#sweepAfterComposition}) end
 * to end: core's real propagation copies a template's INACTIVE rows too and lets them win by
 * precedence, the hook's native-SQL sweep corrects the personal role in the same request, and the
 * evicted session state then serves the swept rows and flushes cleanly.
 *
 * <p>{@code WeldBaseTest}, not plain {@code OBBaseTest}: core's propagation only runs with the CDI
 * interceptor installed (see {@code UserRoleCompositionServiceIntegrationTest}). Nothing is
 * committed.</p>
 */
public class CompositionSweepIntegrationTest extends WeldBaseTest {

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  /**
   * Finance-like template A (SeqNo 10) grants window W fully; Sales-like template B (SeqNo 20)
   * holds a soft-deleted row for W and an inactive row for X that nobody grants. Without the hook
   * the user ends with an inactive copy of W (B wins core's precedence) and an inactive copy of X.
   */
  @Test
  public void compositionRealignsCopiesCoreTookFromInactiveTemplateRows() {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      List<Window> windows = windows();
      Window w = windows.get(0);
      Window x = windows.get(1);
      Role a;
      Role b;
      // System-client fixtures: bypass the client check, like UserRoleCompositionServiceIntegrationTest.
      OBContext.setAdminMode();
      try {
        a = template();
        b = template();
        grant(a, w, true);
        grant(b, w, false);
        grant(b, x, false);
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }

      UserRoleCompositionService.AssignmentResult result = new UserRoleCompositionService()
          .assignTemplateRoles(TEST_USER_ID, Arrays.asList(a.getId(), b.getId()));

      Role personal = OBDal.getInstance().get(Role.class, result.personalRoleId);
      List<WindowAccess> wRows = rows(personal, w);
      assertEquals("One row for W", 1, wRows.size());
      WindowAccess wRow = wRows.get(0);
      assertEquals("W must be active: A grants it", Boolean.TRUE, wRow.isActive());
      assertEquals(Boolean.TRUE, wRow.isEditableField());
      assertEquals("Sourced from the template that grants it", a.getId(),
          wRow.getInheritedFrom().getId());
      assertEquals("No copy of X: no template grants it actively", 0, rows(personal, x).size());

      // The personal role was evicted: its collection is reloaded from the swept state.
      for (WindowAccess access : personal.getADWindowAccessList()) {
        assertFalse("The role's collection must not hold the deleted copy of X",
            x.getId().equals(access.getWindow().getId()));
      }
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Window> windows() {
    OBCriteria<Window> criteria = OBDal.getInstance().createCriteria(Window.class);
    criteria.addOrderBy(Window.PROPERTY_ID, true);
    criteria.setMaxResults(2);
    List<Window> windows = criteria.list();
    assertEquals(2, windows.size());
    return windows;
  }

  private static Role template() {
    Role role = OBProvider.getInstance().get(Role.class);
    role.setNewOBObject(true);
    role.setClient(OBDal.getInstance().get(Client.class, "0"));
    role.setOrganization(OBDal.getInstance().get(Organization.class, "0"));
    role.setActive(true);
    role.setName("ETP-5565 IT template " + System.nanoTime());
    role.setUserLevel(SystemRoleTemplates.FIXED_ROLE_USER_LEVEL);
    role.setManual(true);
    role.setTemplate(true);
    role.setClientAdmin(false);
    OBDal.getInstance().save(role);
    OBDal.getInstance().flush();
    return role;
  }

  private static void grant(Role role, Window window, boolean active) {
    WindowAccess access = OBProvider.getInstance().get(WindowAccess.class);
    access.setNewOBObject(true);
    access.setClient(role.getClient());
    access.setOrganization(role.getOrganization());
    access.setActive(active);
    access.setRole(role);
    access.setWindow(window);
    access.setEditableField(true);
    OBDal.getInstance().save(access);
  }

  /** Every row, active or not, of {@code role} for {@code window}, read fresh from the DB. */
  @SuppressWarnings("unchecked")
  private static List<WindowAccess> rows(Role role, Window window) {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.setFilterOnActive(false);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_WINDOW, window));
    List<WindowAccess> rows = criteria.list();
    assertNotNull(rows);
    return rows;
  }
}
