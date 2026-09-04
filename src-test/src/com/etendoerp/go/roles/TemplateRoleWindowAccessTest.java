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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
  private static final String WINDOW_MATCHED_PURCHASE_INVOICES = "107";

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
    assertEquals(28, finance.size(),
        "Finance's ETP-4878 column has 27 non-dash rows once the 12 windowless rows are excluded, "
            + "plus window 107 (Receipt-Invoice Link) added post-matrix by ETP-5075");

    WindowGrant glJournalGrant = grantFor(finance, WINDOW_SIMPLE_GL_JOURNAL);
    assertNotNull(glJournalGrant,
        "Asientos manuales must resolve to Simple G/L Journal (ETP-4878 decision 2)");
    assertFalse(glJournalGrant.isReadOnly(), "Financiero has full (✓) access to Asientos manuales");

    assertNull(grantFor(finance, WINDOW_CLASSIC_GL_JOURNAL),
        "The classic G/L Journal window (#132) must NEVER be granted — it was the ambiguous, "
            + "un-onboarded candidate the coordinator explicitly ruled out");

    WindowGrant matchedPurchaseInvoicesGrant = grantFor(finance, WINDOW_MATCHED_PURCHASE_INVOICES);
    assertTrue(matchedPurchaseInvoicesGrant != null && !matchedPurchaseInvoicesGrant.isReadOnly(),
        "Financiero has FULL access to Relación albarán-factura (ETP-5075): the window's data is "
            + "read-only, but its posting action is a POST that hasWindowAccess only clears when "
            + "IsReadWrite='Y' — a read-only grant would 403 the post");
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
  void purchasingHasTwelveGrants() {
    List<WindowGrant> purchasing = TemplateRoleWindowAccess.byRoleId()
        .get(SystemRoleTemplates.PURCHASING_ROLE_ID);
    assertEquals(12, purchasing.size(),
        "Purchasing's ETP-4878 column has 11 rows, plus window 107 (Receipt-Invoice Link) added "
            + "post-matrix by ETP-5075");
    WindowGrant contacts = grantFor(purchasing, WINDOW_CONTACTS);
    assertTrue(contacts != null && !contacts.isReadOnly(),
        "Purchasing has full access to Contactos per the matrix");

    WindowGrant matchedPurchaseInvoicesGrant = grantFor(purchasing, WINDOW_MATCHED_PURCHASE_INVOICES);
    assertTrue(matchedPurchaseInvoicesGrant != null && !matchedPurchaseInvoicesGrant.isReadOnly(),
        "Compras has FULL access to Relación albarán-factura (ETP-5075) — see financeGrants' "
            + "assertion for why the posting action requires IsReadWrite='Y'");
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
    assertEquals(66, total,
        "13 (Sales) + 12 (Purchasing) + 28 (Finance) + 13 (Inventory) = 66 — the +2 over the "
            + "original 64 is window 107 (Receipt-Invoice Link) added to Purchasing and Finance "
            + "by ETP-5075");
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

  /**
   * QA (Sentinel, ETP-4878) — the class javadoc and {@code EnsureSystemRoleTemplatesScript}'s own
   * javadoc both claim "33 distinct AD_Window_IDs" across the 64 grants, but nothing in the
   * existing suite actually counted the DISTINCT windows (only the raw 64-grant total, which
   * would stay 64 even if every role duplicated the same handful of windows). This locks in the
   * documented number so a future matrix edit that silently drifts from it is caught here instead
   * of only being caught by someone re-reading the javadoc by hand.
   */
  @Test
  void thirtyThreeDistinctWindowIdsAreCoveredAcrossAllFourRoles() {
    Set<String> distinctWindowIds = new TreeSet<>();
    for (List<WindowGrant> grants : TemplateRoleWindowAccess.byRoleId().values()) {
      for (WindowGrant grant : grants) {
        distinctWindowIds.add(grant.getWindowId());
      }
    }
    assertEquals(34, distinctWindowIds.size(),
        "The matrix's 66 grants must resolve to exactly 34 distinct AD_Window_IDs once shared "
            + "windows (e.g. Contactos, Producto, Tarifa) are counted once, per the class javadoc "
            + "and EnsureSystemRoleTemplatesScript's own javadoc — the 34th is window 107 "
            + "(Receipt-Invoice Link), added to both Purchasing and Finance by ETP-5075");
  }

  /**
   * QA (Sentinel, ETP-4878) — locks in the exact two {@code AD_Window_ID}s each role carried
   * under the old ETP-4852 2-window smoke test (see the pre-ETP-4878 revision of {@code
   * EnsureSystemRoleTemplatesScript}), confirming they all survive UNCHANGED (same access level)
   * in the new real matrix.
   *
   * <p><b>Why this matters beyond "nothing regressed":</b> it means {@code
   * EnsureSystemRoleTemplatesScript#removeStaleWindowAccess} is NEVER actually exercised by the
   * real old-smoke-test → new-matrix transition on any of the 4 roles — every one of these 8
   * windows is a subset of, not disjoint from, its role's new column. The delete-stale-grant code
   * path is correctly implemented (verified by reading {@code removeStaleWindowAccess} — it is a
   * plain "active row not in desiredWindowIds → DELETE"), but this specific migration exercises
   * only the upsert half, never the delete half, against real production data. If this test ever
   * starts failing because one of these 8 windows or its access level DID change, that is exactly
   * the scenario that would finally exercise (or require re-verifying) the delete path — treat a
   * failure here as a signal to re-run the live-DB check documented in the QA report, not just a
   * data typo.</p>
   */
  @Test
  void allEightOldEtp4852SmokeTestWindowsSurviveUnchangedInTheNewMatrix() {
    Map<String, List<WindowGrant>> byRoleId = TemplateRoleWindowAccess.byRoleId();

    // Maps each role id to its old smoke-test window ids and whether that grant was full access.
    Map<String, Map<String, Boolean>> oldSmokeGrantsByRole = new HashMap<>();
    Map<String, Boolean> financeOld = new HashMap<>();
    financeOld.put("94EAA455D2644E04AB25D93BE5157B6D", true); // Financial Account
    financeOld.put("E547CE89D4C04429B6340FFA44E70716", true); // Payment In
    oldSmokeGrantsByRole.put(SystemRoleTemplates.FINANCE_ROLE_ID, financeOld);

    Map<String, Boolean> salesOld = new HashMap<>();
    salesOld.put("143", true); // Sales Order
    salesOld.put("123", true); // Business Partner
    oldSmokeGrantsByRole.put(SystemRoleTemplates.SALES_ROLE_ID, salesOld);

    Map<String, Boolean> purchasingOld = new HashMap<>();
    purchasingOld.put("181", true); // Purchase Order
    purchasingOld.put("140", true); // Product
    oldSmokeGrantsByRole.put(SystemRoleTemplates.PURCHASING_ROLE_ID, purchasingOld);

    Map<String, Boolean> inventoryOld = new HashMap<>();
    inventoryOld.put("184", true); // Goods Receipt
    inventoryOld.put("139", true); // Warehouse and Storage Bins
    oldSmokeGrantsByRole.put(SystemRoleTemplates.INVENTORY_ROLE_ID, inventoryOld);

    for (Map.Entry<String, Map<String, Boolean>> roleEntry : oldSmokeGrantsByRole.entrySet()) {
      String roleId = roleEntry.getKey();
      List<WindowGrant> newGrants = byRoleId.get(roleId);
      for (Map.Entry<String, Boolean> windowEntry : roleEntry.getValue().entrySet()) {
        WindowGrant newGrant = grantFor(newGrants, windowEntry.getKey());
        assertNotNull(newGrant, "Old smoke-test window " + windowEntry.getKey()
            + " for role " + roleId + " must still be present in the new ETP-4878 matrix");
        assertEquals(windowEntry.getValue(), !newGrant.isReadOnly(),
            "Old smoke-test window " + windowEntry.getKey() + " for role " + roleId
                + " must keep its old (full) access level in the new matrix, or "
                + "removeStaleWindowAccess's delete path would newly apply to it");
      }
    }
  }

  /**
   * QA (Sentinel, ETP-4878) — cross-ticket integration seam finding. Computes, straight from the
   * matrix data (no DB needed), every {@code AD_Window_ID} that is granted by two or more of the
   * four roles at DIFFERING access levels. This set is non-empty, which is the data-level root
   * cause behind the cross-template {@code AD_Window_Access} overlap bug found via this exact
   * matrix (ETP-4852, fixed in {@code UserRoleCompositionService} — see that class's javadoc and
   * {@code UserRoleCompositionServiceOverlapIntegrationTest}): any personal role composed from
   * two templates that both appear as a key for the same window here is exactly the scenario that
   * fix's most-permissive-wins reconciliation pass exists to resolve — the window must end up full
   * access if EITHER template wanted full, never silently one-or-the-other.
   *
   * <p>This did not exist before ETP-4878: the old 2-window-per-role smoke test used disjoint
   * window sets across all 4 roles, so this set would have been empty under the pre-ETP-4878
   * matrix. Locking in the current, non-empty set here so a future matrix edit that resolves (or
   * widens) the conflict is a visible, deliberate diff to this test, not a silent side effect.</p>
   */
  @Test
  void multipleWindowsAreGrantedByTwoOrMoreRolesAtConflictingAccessLevels() {
    Map<String, List<WindowGrant>> byRoleId = TemplateRoleWindowAccess.byRoleId();
    Map<String, Boolean> firstAccessLevelSeenByWindowId = new HashMap<>();
    Set<String> conflictingWindowIds = new TreeSet<>();

    for (List<WindowGrant> grants : byRoleId.values()) {
      for (WindowGrant grant : grants) {
        Boolean previouslySeen = firstAccessLevelSeenByWindowId.putIfAbsent(grant.getWindowId(),
            grant.isReadOnly());
        if (previouslySeen != null && !previouslySeen.equals(grant.isReadOnly())) {
          conflictingWindowIds.add(grant.getWindowId());
        }
      }
    }

    assertTrue(conflictingWindowIds.contains("123"),
        "Contactos (123) is full for Sales/Purchasing/Finance but read-only for Inventory — a "
            + "known conflicting window per the matrix");
    assertTrue(conflictingWindowIds.contains("143"),
        "Pedido de venta (143) is full for Sales but read-only for Finance/Inventory — a known "
            + "conflicting window per the matrix");
    assertFalse(conflictingWindowIds.isEmpty(),
        "At least one window must be granted at conflicting access levels across roles — this "
            + "is the data-level root cause of the ETP-4852 multi-template composition overlap "
            + "resolved by UserRoleCompositionService's most-permissive-wins reconciliation "
            + "pass (see UserRoleCompositionServiceOverlapIntegrationTest)");
  }
}
