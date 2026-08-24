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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

/**
 * ETP-4906 (Task B5, human question 2026-08-14, dispatched 2026-08-16) — real-seed-data proof
 * that the role-composition engine's access-control resolution actually delivers the 4 outcomes
 * an admin relies on when composing a user's access from {@link SystemRoleTemplates}: no access,
 * read-only access, full access, and most-permissive-wins when two composed templates disagree.
 *
 * <p><b>Why this is a SIBLING class, not more methods on {@code
 * UserRoleCompositionServiceOverlapIntegrationTest}.</b> That file's own scope is proving the
 * ETP-4852 overlap-corruption FIX mechanism in the abstract, using a synthetic shared window
 * ({@code UNUSED_WINDOW_ID = "100"}, "Tables and Columns") deliberately chosen to be outside
 * either template's real grants — its javadoc is explicit that this keeps it independent of
 * whatever the templates' real grants happen to be. This class does the opposite on purpose: it
 * asserts against the REAL {@link TemplateRoleWindowAccess} matrix (window {@code 192}, "Business
 * Partner Category" — literally the one window that Sales grants read-only and Finance grants
 * full) precisely because the ticket's human question was "does the ACTUAL production matrix
 * behave correctly", not just "is the mechanism correct in the abstract". Mixing the two would
 * make a future matrix edit (e.g. ETP-4877 re-syncing already-composed personal roles) silently
 * churn an unrelated mechanism-proof file, and vice versa a mechanism refactor would touch a file
 * that is supposed to be about real seed data. Confirmed live against this environment's DB
 * (2026-08-16, read-only {@code psql} query) that the matrix is seeded exactly as {@link
 * TemplateRoleWindowAccess} describes: Sales has NO {@code AD_Window_Access} row at all for
 * window {@code 183} (Purchase Invoice), Purchasing has NO row at all for window {@code 167}
 * (Sales Invoice), Sales' row for window {@code 192} has {@code IsReadWrite = 'N'}, and Finance's
 * row for the same window has {@code IsReadWrite = 'Y'} — exactly the 4-outcome case below.</p>
 *
 * <p><b>Scope.</b> DB-level {@code WeldBaseTest} assertions only, same as its sibling. Actually
 * hitting a spec endpoint over HTTP as a composed user and checking a real 403/empty-data
 * response is explicitly OUT of scope for this ticket (see Task B5 in the ETP-4906 plan) —
 * {@code NeoAccessHelper#hasWindowAccess()} runtime enforcement is pre-existing machinery this
 * ticket did not build and is not re-verified here.</p>
 *
 * <p>Nothing here is ever committed — every row created (personal role, its {@code
 * AD_Role_Inheritance}, its {@code AD_Window_Access}, its {@code AD_User_Roles}, and the mutated
 * {@link #TEST_USER_ID} default role) is rolled back in {@link #rollbackChanges()}, mirroring
 * {@code UserRoleCompositionServiceOverlapIntegrationTest}'s own convention.</p>
 */
public class UserRoleCompositionServiceRealAccessControlIntegrationTest extends WeldBaseTest {

  /** Purchase Invoice — granted (full) only by Purchasing's real seed data, never Sales'. */
  private static final String PURCHASE_INVOICE_WINDOW_ID = "183";

  /** Sales Invoice — granted (full) only by Sales' real seed data, never Purchasing's. */
  private static final String SALES_INVOICE_WINDOW_ID = "167";

  /**
   * Business Partner Category — Sales grants it read-only ({@code IsReadWrite = 'N'}), Finance
   * grants it full ({@code IsReadWrite = 'Y'}); the real most-permissive-wins case.
   */
  private static final String BP_CATEGORY_WINDOW_ID = "192";

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testSalesOnlyComposedRoleHasNoAccessToPurchaseInvoice() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      assertNotNull("The real Sales system template must already exist (seeded by "
          + "EnsureSystemRoleTemplatesScript on update.database)", salesTemplate);

      Window purchaseInvoice = OBDal.getInstance().get(Window.class, PURCHASE_INVOICE_WINDOW_ID);
      assertNotNull("Test fixture must contain AD_Window " + PURCHASE_INVOICE_WINDOW_ID,
          purchaseInvoice);

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult result = service.assignTemplateRoles(
          TEST_USER_ID, Collections.singletonList(salesTemplate.getId()));
      assertEquals(1, result.addedCount);

      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      assertNotNull(personalRole);

      assertNull("A role composed from Sales ALONE must have NO active AD_Window_Access row for "
          + "Purchase Invoice — Sales' real seed data never grants it",
          findWindowAccess(personalRole, purchaseInvoice));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testPurchasingOnlyComposedRoleHasNoAccessToSalesInvoice() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Role purchasingTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.PURCHASING_ROLE_ID);
      assertNotNull("The real Purchasing system template must already exist (seeded by "
          + "EnsureSystemRoleTemplatesScript on update.database)", purchasingTemplate);

      Window salesInvoice = OBDal.getInstance().get(Window.class, SALES_INVOICE_WINDOW_ID);
      assertNotNull("Test fixture must contain AD_Window " + SALES_INVOICE_WINDOW_ID,
          salesInvoice);

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult result = service.assignTemplateRoles(
          TEST_USER_ID, Collections.singletonList(purchasingTemplate.getId()));
      assertEquals(1, result.addedCount);

      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      assertNotNull(personalRole);

      assertNull("A role composed from Purchasing ALONE must have NO active AD_Window_Access "
          + "row for Sales Invoice — Purchasing's real seed data never grants it",
          findWindowAccess(personalRole, salesInvoice));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testSalesAloneIsReadOnlyOnBpCategoryAndAddingFinanceUpgradesToFullMostPermissiveWins()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      assertNotNull("The real Sales system template must already exist (seeded by "
          + "EnsureSystemRoleTemplatesScript on update.database)", salesTemplate);
      assertNotNull("The real Finance system template must already exist (seeded by "
          + "EnsureSystemRoleTemplatesScript on update.database)", financeTemplate);

      Window bpCategory = OBDal.getInstance().get(Window.class, BP_CATEGORY_WINDOW_ID);
      assertNotNull("Test fixture must contain AD_Window " + BP_CATEGORY_WINDOW_ID, bpCategory);

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();

      // Step 1: Sales ALONE — real seed data grants BP Category read-only.
      UserRoleCompositionService.AssignmentResult salesOnly = service.assignTemplateRoles(
          TEST_USER_ID, Collections.singletonList(salesTemplate.getId()));
      assertEquals(1, salesOnly.addedCount);

      Role personalRoleAfterSales = OBDal.getInstance().get(Role.class, salesOnly.personalRoleId);
      WindowAccess readOnlyAccess = findWindowAccess(personalRoleAfterSales, bpCategory);
      assertNotNull("Sales alone must grant BP Category access (read-only)", readOnlyAccess);
      assertFalse("Sales alone must resolve BP Category as READ-ONLY, matching the real "
          + "ETP-4878 matrix (IsReadWrite = 'N')",
          Boolean.TRUE.equals(readOnlyAccess.isEditableField()));

      // Step 2: compose Sales + Finance TOGETHER — Finance's full grant must win.
      UserRoleCompositionService.AssignmentResult salesAndFinance = service.assignTemplateRoles(
          TEST_USER_ID, Arrays.asList(salesTemplate.getId(), financeTemplate.getId()));

      Role personalRoleAfterBoth = OBDal.getInstance().get(Role.class,
          salesAndFinance.personalRoleId);
      assertEquals("Composing a second template must reuse the SAME personal role, not create "
          + "a new one", personalRoleAfterSales.getId(), personalRoleAfterBoth.getId());

      WindowAccess fullAccess = findWindowAccess(personalRoleAfterBoth, bpCategory);
      assertNotNull("BP Category access must still be present after composing Finance in",
          fullAccess);
      assertTrue("Sales + Finance composed together must resolve BP Category as FULL — proves "
          + "both Finance's own full-access grant AND most-permissive-wins (Finance's 'Y' beats "
          + "Sales' 'N' on the same window), matching the real ETP-4878 matrix",
          Boolean.TRUE.equals(fullAccess.isEditableField()));
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
