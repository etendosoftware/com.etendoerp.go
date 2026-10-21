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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
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

import com.etendoerp.go.roles.overlap.TemplateRemovalTracker;

/**
 * ETP-5565 — the composition hook ({@code
 * TemplateAccessPropagationService#realignAfterComposition}) end to end: the personal role ends
 * aligned to the templates' ACTIVE grants in the same request, and the evicted session state then
 * serves the swept rows and flushes cleanly. Core's real propagation copied a template's INACTIVE
 * rows too and let them win by precedence; since ETP-5830 it never copies them from a system
 * template ({@code SystemTemplateAccessRows}), and these tests hold the end state either way.
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
      // System-client fixtures bypass the client check, as in
      // UserRoleCompositionServiceIntegrationTest.
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

  /**
   * ETP-5830 — three templates soft-delete the same window X (one grant removed from all of them,
   * as the Smart Scan removal did). Removing one template makes core recalculate the two left in
   * a single flush: each copied X again, the second could not see the first one's pending insert,
   * and the flush hit {@code ad_window_access_un_key}. An inactive system-template row must never
   * be copied, on add or on remove.
   */
  @Test
  public void inactiveRowSharedByTemplatesIsNeverCopiedOnAddOrRemove() {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      List<Window> windows = windows();
      Window w = windows.get(0);
      Window x = windows.get(1);
      List<String> templateIds = new ArrayList<>();
      OBContext.setAdminMode();
      try {
        for (int i = 0; i < 3; i++) {
          Role template = template();
          grant(template, w, true);
          grant(template, x, false);
          templateIds.add(template.getId());
        }
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }
      UserRoleCompositionService service = new UserRoleCompositionService();

      String personalId = inFreshRequest(service, templateIds).personalRoleId;
      Role personal = OBDal.getInstance().get(Role.class, personalId);
      assertEquals("No copy of X after adding them", 0, rows(personal, x).size());
      assertEquals("One row for W", 1, rows(personal, w).size());

      inFreshRequest(service, templateIds.subList(0, 2));
      personal = OBDal.getInstance().get(Role.class, personalId);
      assertEquals("No copy of X after removing one", 0, rows(personal, x).size());
      List<WindowAccess> wRows = rows(personal, w);
      assertEquals("W still granted by the remaining templates", 1, wRows.size());
      assertTrue(templateIds.subList(0, 2).contains(wRows.get(0).getInheritedFrom().getId()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** One call in a fresh session, as a separate webhook request would be. */
  private static UserRoleCompositionService.AssignmentResult inFreshRequest(
      UserRoleCompositionService service, List<String> templates) {
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().clear();
    UserRoleCompositionService.AssignmentResult result = service.assignTemplateRoles(TEST_USER_ID,
        templates);
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().clear();
    TemplateRemovalTracker.clear();
    return result;
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
