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

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.After;
import org.junit.Test;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

/**
 * QA (Sentinel, ETP-4878) — CRITICAL cross-ticket integration seam finding, confirmed live against
 * a real DB (not just reasoned from source). The QA brief asked: "does a personal role that
 * inherits from 2+ template roles actually end up seeing the UNION of both roles' new (larger)
 * window grants correctly once ETP-4878's matrix is live?" The answer is <b>no — it does not
 * compose at all; the whole {@code assignTemplateRoles} call throws {@link OBSecurityException}
 * and rolls back</b>, whenever the two templates share ANY window (not only when they disagree on
 * access level).
 *
 * <p><b>Why this is reachable for the first time because of ETP-4878.</b> The old ETP-4852
 * 2-window smoke test gave each of the 4 templates a fully disjoint window pair (Finance:
 * Financial Account + Payment In; Sales: Sales Order + Business Partner; Purchasing: Purchase
 * Order + Product; Inventory: Goods Receipt + Warehouse) — no two roles ever shared a window, so
 * this code path was never exercised by any personal role composed from 2+ system templates.
 * ETP-4878's real matrix intentionally and heavily overlaps roles on shared windows (Contactos,
 * Producto, Categoría del producto, Tarifa, Condiciones de pago, …) — see {@code
 * TemplateRoleWindowAccessTest#multipleWindowsAreGrantedByTwoOrMoreRolesAtConflictingAccessLevels}
 * for the data-level enumeration of shared windows. Any GO user assigned 2+ overlapping templates
 * (e.g. Sales + Purchasing, both of which grant Contactos/Producto/Tarifa/Condiciones de
 * pago/Categoría de contacto) will hit this the moment {@code assignTemplateRoles} is called with
 * both ids in the same request.</p>
 *
 * <p><b>Root cause, traced into core (org.openbravo.role.inheritance), NOT this module's code.
 * </b> {@code RoleInheritanceManager.propagateNewAccess} → {@code handleAccess}: when a personal
 * role's inheritance from a SECOND template is applied and that template grants a window the
 * FIRST template's inheritance already propagated, {@code handleAccess} does not create a second
 * row — it calls {@code updateRoleAccess}, which does {@code DalUtil.copyToTarget(inherited,
 * access, false, injector.getSkippedProperties())}. {@code WindowAccessInjector} never overrides
 * {@code getSkippedProperties()}, so the base {@code AccessTypeInjector} default — {@code
 * creationDate}/{@code createdBy} only — applies: {@code client} and {@code organization} are
 * NOT skipped. The copy therefore overwrites the TARGET's (personal role's, tenant-client)
 * {@code AD_Window_Access} row with the SOURCE template's OWN {@code client}/{@code organization}
 * (system client {@code "0"}) — corrupting that row's tenancy. The very next {@code
 * OBDal.flush()} inside {@code UserRoleCompositionService.reconcileInheritances} then hits {@code
 * SecurityChecker.checkWriteAccess}, which rejects a client-{@code "0"} row under an {@code
 * OBContext} whose client list is just the tenant client — throwing {@link OBSecurityException}
 * and aborting the whole composition.</p>
 *
 * <p><b>Confirmed independent of access-level agreement.</b> An earlier revision of this test
 * gave the two templates DIFFERENT access levels (full vs. read-only) on the shared window,
 * hypothesizing the bug was specific to a conflict. It reproduces identically when both grant the
 * SAME access level too — {@code handleAccess} takes the update path purely because a window is
 * already covered by an earlier-processed inheritance, regardless of what access level either
 * side requests.</p>
 *
 * <p><b>Uses the REAL Finance/Sales system template roles</b> (seeded by {@code
 * EnsureSystemRoleTemplatesScript} on {@code update.database}), not throwaway ones — an earlier
 * revision minted two brand-new client-{@code 0} {@code Role} rows in-session instead and hit the
 * exact same failure, ruling out "freshly-inserted role in this session" as the cause. The extra
 * grant here is added on window {@code 100} ("Tables and Columns"), confirmed via a live,
 * read-only query against this environment's DB to NOT be part of either role's real
 * ETP-4852/ETP-4878 grants, so this test is independent of the real matrix's content and never
 * mutates it (nothing here is committed regardless — see {@link #rollbackChanges()}).</p>
 */
public class TemplateRoleWindowAccessConflictIntegrationTest extends WeldBaseTest {

  /** Confirmed (live DB check) NOT part of either role's real ETP-4852/ETP-4878 grants. */
  private static final String UNUSED_WINDOW_ID = "100";

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testComposingTwoTemplatesThatShareAWindowThrowsOBSecurityExceptionInsteadOfUnioningAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull("Test fixture must contain AD_Window " + UNUSED_WINDOW_ID, sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      assertNotNull("The real Finance system template must already exist (seeded by "
          + "EnsureSystemRoleTemplatesScript on update.database)", financeTemplate);
      assertNotNull("The real Sales system template must already exist (seeded by "
          + "EnsureSystemRoleTemplatesScript on update.database)", salesTemplate);

      // Both templates grant the SAME (otherwise-unused) window — mirrors what the real
      // ETP-4878 matrix now does on windows like Contactos/Producto/Tarifa, regardless of
      // whether the two access levels agree or conflict (confirmed to not matter — see class
      // javadoc).
      grantWindowAccess(financeTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();

      // THE BUG (core org.openbravo.role.inheritance, exposed for the first time by ETP-4878's
      // overlapping matrix): composing a personal role from two templates that share a window
      // does not union their access — it throws and the whole call rolls back. See class
      // javadoc for the exact root cause (RoleInheritanceManager#updateRoleAccess copying the
      // source template's client/organization onto the target's existing row via
      // DalUtil#copyToTarget, since WindowAccessInjector never adds client/organization to
      // AccessTypeInjector#getSkippedProperties()). If this assertion ever fails because the
      // call now succeeds, core's inheritance propagation has been fixed (or worked around) —
      // treat that as a signal to replace this test with a real union-of-access assertion, not
      // to delete it silently.
      assertThrows(OBSecurityException.class, () -> service.assignTemplateRoles(TEST_USER_ID,
          Arrays.asList(financeTemplate.getId(), salesTemplate.getId())));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void grantWindowAccess(Role role, Window window, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      WindowAccess access = org.openbravo.base.provider.OBProvider.getInstance()
          .get(WindowAccess.class);
      access.setNewOBObject(true);
      access.setClient(role.getClient());
      access.setOrganization(role.getOrganization());
      access.setActive(true);
      access.setRole(role);
      access.setWindow(window);
      access.setEditableField(!readOnly);
      OBDal.getInstance().save(access);
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
