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

import java.util.Arrays;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

/**
 * QA (Sentinel) — independent re-verification of the ETP-4852 overlap-corruption fix ({@code
 * UserRoleCompositionService#preventWindowAccessOverlapCorruption}/{@code
 * #reconcileWindowAccessAfterComposition}), requested by the coordinator as the reject-cycle
 * close-out for the CRITICAL bug originally reported against ETP-4878. Deliberately NOT reusing
 * {@code UserRoleCompositionServiceOverlapIntegrationTest} (the fix author's own test) — this
 * suite is an independent angle:
 *
 * <ul>
 *   <li>{@link #testThreeTemplatesSharingAWindowResolveToMostPermissiveAcrossAllThree()} — 3
 *       overlapping templates (not just 2), to rule out the fix being accidentally pairwise-only
 *       (e.g. only comparing the 2nd template against the 1st, missing a 3rd).</li>
 *   <li>{@link #testRealMatrixOverlapSalesAndInventoryOnContactosResolvesToFull()} and
 *       {@link #testRealMatrixOverlapSalesAndPurchasingOnProductCategoryStaysReadOnly()} — close
 *       the loop on the ORIGINAL finding directly: real templates, a real {@code AD_Window_ID}
 *       from the actual ETP-4878 matrix (not the synthetic window {@code 100} both existing test
 *       suites use), and the actual access levels the matrix assigns. Since {@code
 *       EnsureSystemRoleTemplatesScript} has not run against this environment yet (confirmed via
 *       a live read-only query — the DB still only has the old ETP-4852 2-window smoke-test
 *       grants), the real matrix's grants are seeded directly on the templates here, mirroring
 *       exactly what that script will eventually write.</li>
 * </ul>
 */
public class UserRoleCompositionServiceOverlapReverificationTest extends WeldBaseTest {

  /** Real ETP-4878 matrix window: "Contactos" (Business Partner) — full for Sales, RO for Inventory. */
  private static final String WINDOW_CONTACTOS = "123";

  /** Real ETP-4878 matrix window: "Categoría del producto" — read-only for BOTH Sales and Purchasing. */
  private static final String WINDOW_PRODUCT_CATEGORY = "144";

  /** Confirmed (live DB check) NOT part of any of the 4 templates' real ETP-4852 smoke-test grants. */
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
  public void testThreeTemplatesSharingAWindowResolveToMostPermissiveAcrossAllThree()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      Role purchasingTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.PURCHASING_ROLE_ID);
      assertNotNull(financeTemplate);
      assertNotNull(salesTemplate);
      assertNotNull(purchasingTemplate);

      // All three read-only EXCEPT one (Purchasing) full — the "winner" is in the middle of the
      // composition order, not first or last, so a pairwise-only fix (only checking the newest
      // template against the immediately-preceding state) could plausibly still miss it.
      grantWindowAccess(financeTemplate, sharedWindow, true);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(purchasingTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult result = service.assignTemplateRoles(
          TEST_USER_ID, Arrays.asList(financeTemplate.getId(), salesTemplate.getId(),
              purchasingTemplate.getId()));

      assertEquals("All three templates must be applied as three distinct inheritances", 3,
          result.addedCount);

      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      assertNotNull(personalRole);

      WindowAccess shared = findWindowAccess(personalRole, sharedWindow);
      assertNotNull("The shared window must survive composition across all 3 templates", shared);
      assertTrue("Most-permissive-wins across 3 templates: Sales' full access must win even "
          + "though it is neither the first nor the last template in the request",
          Boolean.TRUE.equals(shared.isEditableField()));
      assertEquals(personalRole.getClient().getId(), shared.getClient().getId());
      assertEquals(personalRole.getOrganization().getId(), shared.getOrganization().getId());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Closes the loop on the ORIGINAL QA finding directly: the real ETP-4878 matrix grants
   * "Contactos" (window {@code 123}) FULL to Sales and READ-ONLY to Inventory — one of the 7 real
   * conflicting windows enumerated in the original QA report. Composing a user from both real
   * templates must now succeed and resolve to full, not throw.
   *
   * <p><b>Sales already has a real, pre-existing {@code AD_Window_Access} row for window {@code
   * 123}</b> — confirmed live: it is one half of Sales' own OLD ETP-4852 2-window smoke-test pair
   * ({@code 143}/{@code 123}), already committed in this environment, at exactly the access level
   * (full) the real ETP-4878 matrix also wants. An earlier revision of this test tried to INSERT
   * a second row for Sales+123 regardless and hit {@code ad_window_access_un_key}'s live {@code
   * UNIQUE INDEX (ad_role_id, ad_window_id)} — a real constraint the original QA report's "no
   * unique constraint" claim missed because it only queried {@code pg_constraint}, not {@code
   * pg_indexes} (a plain {@code CREATE UNIQUE INDEX}, not a named table constraint, so it never
   * shows up there). Corrected here by only seeding Inventory's side; Sales' existing row already
   * matches what the real matrix needs.</p>
   */
  @Test
  public void testRealMatrixOverlapSalesAndInventoryOnContactosResolvesToFull() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window contactos = OBDal.getInstance().get(Window.class, WINDOW_CONTACTOS);
      assertNotNull("AD_Window 123 (Contactos / Business Partner) must exist", contactos);

      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      Role inventoryTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.INVENTORY_ROLE_ID);
      assertNotNull(salesTemplate);
      assertNotNull(inventoryTemplate);

      // Sanity check: Sales must already carry its real (pre-existing, smoke-test-era) FULL
      // grant on Contactos — if this ever stops being true, the "skip seeding Sales" shortcut
      // below becomes invalid and this test needs revisiting, not just re-running.
      WindowAccess existingSalesGrant = findWindowAccess(salesTemplate, contactos);
      assertNotNull("Sales must already have an active AD_Window_Access row for Contactos "
          + "(its old ETP-4852 smoke-test grant)", existingSalesGrant);
      assertTrue("Sales' existing Contactos grant must already be full access",
          Boolean.TRUE.equals(existingSalesGrant.isEditableField()));

      // Only Inventory needs a NEW row — seeded directly here (mirrors what
      // EnsureSystemRoleTemplatesScript will eventually write once it runs on update.database)
      // using the real matrix's real access level: Inventory=read-only on Contactos.
      grantWindowAccess(inventoryTemplate, contactos, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult result = service.assignTemplateRoles(
          TEST_USER_ID, Arrays.asList(salesTemplate.getId(), inventoryTemplate.getId()));

      assertEquals(2, result.addedCount);

      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      WindowAccess access = findWindowAccess(personalRole, contactos);
      assertNotNull("Contactos must survive composing the real Sales+Inventory templates",
          access);
      assertTrue("Sales' full access to Contactos must win over Inventory's read-only, per the "
          + "real ETP-4878 matrix", Boolean.TRUE.equals(access.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Real-matrix overlap where BOTH roles agree at the SAME (read-only) access level — a distinct
   * code path from the conflicting-level cases both existing test suites cover. Confirms {@code
   * reconcileWindowAccessAfterComposition} does not spuriously promote a window to full access
   * just because 2+ templates share it, when neither of them actually wants full.
   */
  @Test
  public void testRealMatrixOverlapSalesAndPurchasingOnProductCategoryStaysReadOnly()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window productCategory = OBDal.getInstance().get(Window.class, WINDOW_PRODUCT_CATEGORY);
      assertNotNull("AD_Window 144 (Categoría del producto / Product Category) must exist",
          productCategory);

      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      Role purchasingTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.PURCHASING_ROLE_ID);
      assertNotNull(salesTemplate);
      assertNotNull(purchasingTemplate);

      // Real matrix: BOTH Sales and Purchasing grant Categoría del producto read-only.
      grantWindowAccess(salesTemplate, productCategory, true);
      OBDal.getInstance().flush();
      grantWindowAccess(purchasingTemplate, productCategory, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult result = service.assignTemplateRoles(
          TEST_USER_ID, Arrays.asList(salesTemplate.getId(), purchasingTemplate.getId()));

      assertEquals(2, result.addedCount);

      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      WindowAccess access = findWindowAccess(personalRole, productCategory);
      assertNotNull("Categoría del producto must survive composing Sales+Purchasing", access);
      assertFalse("Neither template wants full access, so the union must stay read-only, not be "
          + "spuriously promoted to full", Boolean.TRUE.equals(access.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void grantWindowAccess(Role role, Window window, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      WindowAccess access = OBProvider.getInstance().get(WindowAccess.class);
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

  @SuppressWarnings("unchecked")
  private WindowAccess findWindowAccess(Role role, Window window) {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_WINDOW, window));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (WindowAccess) criteria.uniqueResult();
  }
}
