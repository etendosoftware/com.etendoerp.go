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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.etendoerp.go.roles.TemplateRoleWindowAccess.WindowGrant;

/**
 * Plain, DB-free unit tests for the ETP-4878 window-access permission matrix in {@link
 * TemplateRoleWindowAccess}. No DB, no {@code ConnectionProvider}, no Gradle classpath
 * workaround needed — unlike {@code EnsureSystemRoleTemplatesScript} (a {@code ModuleScript}
 * under {@code src-util/modulescript}, only exercisable against a real DB), this class is plain
 * data under this module's normal {@code src/} tree, so it runs under the ordinary {@code
 * src-test} tree like any other plain unit test in this package.
 *
 * <p>Counts and specific grants below are transcribed directly from the ETP-4878 ticket's
 * Ventas/Compras/Financiero/Almacén matrix, cross-referenced against the research dispatch's
 * resolved {@code AD_Window_ID} mapping. See {@link TemplateRoleWindowAccess}'s own javadoc for
 * the full per-role breakdown and the 12 deferred, windowless matrix rows this suite does not
 * (and should never) reference.</p>
 */
class TemplateRoleWindowAccessTest {

  // Windows referenced by name in the assertions below, for readability.
  private static final String WINDOW_CONTACTS = "123";
  private static final String WINDOW_SALES_ORDER = "143";
  private static final String WINDOW_PAYMENT_OUT = "6F8F913FA60F4CBD93DC1D3AA696E76E";
  private static final String WINDOW_PRODUCT_CATEGORY = "144";
  private static final String WINDOW_SIMPLE_GL_JOURNAL = "B917E8A7B0864ACEA9D941E3B7494E53";
  private static final String WINDOW_CLASSIC_GL_JOURNAL = "132";
  private static final String WINDOW_WAREHOUSE = "139";

  private static WindowGrant grantFor(List<WindowGrant> grants, String windowId) {
    for (WindowGrant grant : grants) {
      if (grant.getWindowId().equals(windowId)) {
        return grant;
      }
    }
    return null;
  }

  @Test
  void exposesExactlyTheFourNonAdminTemplateRoles() {
    Map<String, List<WindowGrant>> byRoleId = TemplateRoleWindowAccess.byRoleId();
    assertEquals(4, byRoleId.size());
    assertTrue(byRoleId.containsKey(SystemRoleTemplates.FINANCE_ROLE_ID));
    assertTrue(byRoleId.containsKey(SystemRoleTemplates.SALES_ROLE_ID));
    assertTrue(byRoleId.containsKey(SystemRoleTemplates.PURCHASING_ROLE_ID));
    assertTrue(byRoleId.containsKey(SystemRoleTemplates.INVENTORY_ROLE_ID));
  }

  @Test
  void financeHasTwentySevenGrantsIncludingTheResolvedSimpleGlJournal() {
    List<WindowGrant> finance = TemplateRoleWindowAccess.byRoleId()
        .get(SystemRoleTemplates.FINANCE_ROLE_ID);
    assertEquals(27, finance.size(),
        "Finance's ETP-4878 column has 27 non-dash rows once the 12 windowless rows are excluded");

    WindowGrant glJournalGrant = grantFor(finance, WINDOW_SIMPLE_GL_JOURNAL);
    assertNotNull(glJournalGrant,
        "Asientos manuales must resolve to Simple G/L Journal (ETP-4878 decision 2)");
    assertFalse(glJournalGrant.isReadOnly(), "Financiero has full (✓) access to Asientos manuales");

    assertNull(grantFor(finance, WINDOW_CLASSIC_GL_JOURNAL),
        "The classic G/L Journal window (#132) must NEVER be granted — it was the ambiguous, "
            + "un-onboarded candidate the coordinator explicitly ruled out");
  }

  @Test
  void salesHasThirteenGrantsAndNoPaymentOutAccess() {
    List<WindowGrant> sales = TemplateRoleWindowAccess.byRoleId()
        .get(SystemRoleTemplates.SALES_ROLE_ID);
    assertEquals(13, sales.size());
    assertNull(grantFor(sales, WINDOW_PAYMENT_OUT),
        "Sales must NOT have a grant for Pago (Payment Out) — the matrix shows — for "
            + "Ventas on that row");
  }

  @Test
  void purchasingHasElevenGrants() {
    List<WindowGrant> purchasing = TemplateRoleWindowAccess.byRoleId()
        .get(SystemRoleTemplates.PURCHASING_ROLE_ID);
    assertEquals(11, purchasing.size());
    WindowGrant contacts = grantFor(purchasing, WINDOW_CONTACTS);
    assertTrue(contacts != null && !contacts.isReadOnly(),
        "Purchasing has full access to Contactos per the matrix");
  }

  @Test
  void inventoryHasThirteenGrantsWithReadOnlySalesOrderAndFullWarehouse() {
    List<WindowGrant> inventory = TemplateRoleWindowAccess.byRoleId()
        .get(SystemRoleTemplates.INVENTORY_ROLE_ID);
    assertEquals(13, inventory.size());

    WindowGrant salesOrder = grantFor(inventory, WINDOW_SALES_ORDER);
    assertTrue(salesOrder != null && salesOrder.isReadOnly(),
        "Almacén has read-only (R) access to Pedido de venta per the matrix");

    WindowGrant warehouse = grantFor(inventory, WINDOW_WAREHOUSE);
    assertTrue(warehouse != null && !warehouse.isReadOnly(),
        "Almacén has full access to its own Warehouse window");
  }

  @Test
  void productCategoryIsReadOnlyForSalesAndPurchasingButFullForFinanceAndInventory() {
    Map<String, List<WindowGrant>> byRoleId = TemplateRoleWindowAccess.byRoleId();

    WindowGrant salesGrant = grantFor(byRoleId.get(SystemRoleTemplates.SALES_ROLE_ID), WINDOW_PRODUCT_CATEGORY);
    WindowGrant purchasingGrant = grantFor(byRoleId.get(SystemRoleTemplates.PURCHASING_ROLE_ID), WINDOW_PRODUCT_CATEGORY);
    WindowGrant financeGrant = grantFor(byRoleId.get(SystemRoleTemplates.FINANCE_ROLE_ID), WINDOW_PRODUCT_CATEGORY);
    WindowGrant inventoryGrant = grantFor(byRoleId.get(SystemRoleTemplates.INVENTORY_ROLE_ID), WINDOW_PRODUCT_CATEGORY);

    assertTrue(salesGrant != null && salesGrant.isReadOnly());
    assertTrue(purchasingGrant != null && purchasingGrant.isReadOnly());
    assertTrue(financeGrant != null && !financeGrant.isReadOnly());
    assertTrue(inventoryGrant != null && !inventoryGrant.isReadOnly());
  }

  @Test
  void noTemplateGrantsTheSameWindowTwice() {
    for (Map.Entry<String, List<WindowGrant>> entry : TemplateRoleWindowAccess.byRoleId().entrySet()) {
      Set<String> seen = new HashSet<>();
      for (WindowGrant grant : entry.getValue()) {
        seen.add(grant.getWindowId());
      }
      assertEquals(entry.getValue().size(), seen.size(),
          entry.getKey() + " must not repeat the same AD_Window_ID twice in its own grant list");
    }
  }

  @Test
  void totalGrantCountAcrossAllFourRolesMatchesTheMatrix() {
    int total = 0;
    for (List<WindowGrant> grants : TemplateRoleWindowAccess.byRoleId().values()) {
      total += grants.size();
    }
    assertEquals(64, total, "13 (Sales) + 11 (Purchasing) + 27 (Finance) + 13 (Inventory) = 64");
  }

  @Test
  void byRoleIdReturnsAFreshMutableMapEachCall() {
    Map<String, List<WindowGrant>> first = TemplateRoleWindowAccess.byRoleId();
    first.clear();
    Map<String, List<WindowGrant>> second = TemplateRoleWindowAccess.byRoleId();
    assertEquals(4, second.size(),
        "Mutating a caller's copy must never affect the next caller — byRoleId() must return a "
            + "fresh map each time, mirroring SystemRoleTemplates#byName()'s own contract");
  }
}
