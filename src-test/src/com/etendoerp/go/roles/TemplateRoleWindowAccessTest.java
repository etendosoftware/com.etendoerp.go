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
 * resolved {@code AD_Window_ID} mapping (ETP-5116 later removed 2 over-grants and added 2 new
 * proxy grants — see the affected tests below). See {@link TemplateRoleWindowAccess}'s own
 * javadoc for the full per-role breakdown and the 10 deferred, windowless matrix rows this suite
 * does not (and should never) reference.</p>
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
  private static final String WINDOW_PHYSICAL_INVENTORY = "168";
  private static final String WINDOW_SII_MONITOR = "FEF76C3E0F104F06A89AAD15A4A4A35C";
  private static final String WINDOW_TAX_REPORT = "3E8FEA1EA7404D979306C9EE7FD2E7E8";
  private static final String WINDOW_SII_CONFIG = "C1D3A2A017AC4B82B9FEE6F4D2A0C55A";
  private static final String WINDOW_TBAI_CONFIG = "C327DE215AC945F69363905840118177";
  private static final String WINDOW_VERIFACTU_CONFIG = "27A453FA86974745977672F1A8DCCEFF";
  private static final String WINDOW_FINANCIAL_REPORTS = "D647D118F5014D00AF47A636B2CD0DD3";
  private static final String WINDOW_SMART_SCAN = "33705E0F52874D91B0BB2FF8BB648B8E";

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
    assertEquals(33, finance.size(),
        "Finance's ETP-4878 column has 25 non-dash rows once the 12 windowless rows and the 2 "
            + "ETP-5116 over-grants (Categoría del producto, Inventario físico) are excluded, plus "
            + "window 107 (Receipt-Invoice Link, ETP-5075), 2 ETP-5116 proxy grants (SII "
            + "Monitor, Tax Report), 3 more ETP-5116 direct grants for Configuración fiscal "
            + "(SII/TBAI/Verifactu Configuration), and 2 more ETP-5116 direct grants from a later "
            + "pass (Informes financieros, Escaneo inteligente)");

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

    assertNull(grantFor(finance, WINDOW_PRODUCT_CATEGORY),
        "ETP-5116: Financiero must have NO access to Categoría del producto — the previous "
            + "full(\"144\") grant was an over-grant and has been removed");
    assertNull(grantFor(finance, WINDOW_PHYSICAL_INVENTORY),
        "ETP-5116: Financiero must have NO access to Inventario físico — the previous "
            + "full(\"168\") grant was an over-grant and has been removed");

    WindowGrant siiMonitorGrant = grantFor(finance, WINDOW_SII_MONITOR);
    assertTrue(siiMonitorGrant != null && !siiMonitorGrant.isReadOnly(),
        "ETP-5116: Financiero has FULL access to the SII Monitor window, proxying the windowless "
            + "\"Monitor fiscal\" row");

    WindowGrant taxReportGrant = grantFor(finance, WINDOW_TAX_REPORT);
    assertTrue(taxReportGrant != null && !taxReportGrant.isReadOnly(),
        "ETP-5116: Financiero has FULL access to the Tax Report window, proxying the windowless "
            + "\"Modelos fiscales\" row");

    WindowGrant siiConfigGrant = grantFor(finance, WINDOW_SII_CONFIG);
    assertTrue(siiConfigGrant != null && !siiConfigGrant.isReadOnly(),
        "ETP-5116: Financiero has FULL access to SII Configuration — one of the 3 real windows "
            + "\"Configuración fiscal\" maps to (product decision)");

    WindowGrant tbaiConfigGrant = grantFor(finance, WINDOW_TBAI_CONFIG);
    assertTrue(tbaiConfigGrant != null && !tbaiConfigGrant.isReadOnly(),
        "ETP-5116: Financiero has FULL access to TBAI Configuration — one of the 3 real windows "
            + "\"Configuración fiscal\" maps to (product decision)");

    WindowGrant verifactuConfigGrant = grantFor(finance, WINDOW_VERIFACTU_CONFIG);
    assertTrue(verifactuConfigGrant != null && !verifactuConfigGrant.isReadOnly(),
        "ETP-5116: Financiero has FULL access to Verifactu Configuration — one of the 3 real "
            + "windows \"Configuración fiscal\" maps to (product decision)");
  }

  @Test
  void salesHasTwelveGrantsAndNoPaymentOutOrPhysicalInventoryAccess() {
    List<WindowGrant> sales = TemplateRoleWindowAccess.byRoleId()
        .get(SystemRoleTemplates.SALES_ROLE_ID);
    assertEquals(13, sales.size(),
        "13 in the original ETP-4878 matrix, minus the ETP-5116 over-grant removal of "
            + "full(\"168\") (Inventario físico) — Ventas should have NO access to that window — "
            + "plus 1 more ETP-5116 grant from a later pass (Escaneo inteligente)");
    assertNull(grantFor(sales, WINDOW_PAYMENT_OUT),
        "Sales must NOT have a grant for Pago (Payment Out) — the matrix shows — for "
            + "Ventas on that row");
    assertNull(grantFor(sales, WINDOW_PHYSICAL_INVENTORY),
        "ETP-5116: Ventas must have NO access to Inventario físico — the previous full(\"168\") "
            + "grant was an over-grant and has been removed");
  }

  @Test
  void purchasingHasTwelveGrants() {
    List<WindowGrant> purchasing = TemplateRoleWindowAccess.byRoleId()
        .get(SystemRoleTemplates.PURCHASING_ROLE_ID);
    assertEquals(13, purchasing.size(),
        "Purchasing's ETP-4878 column has 11 rows, plus window 107 (Receipt-Invoice Link) added "
            + "post-matrix by ETP-5075, plus 1 more ETP-5116 grant from a later pass (Escaneo "
            + "inteligente)");
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
    assertEquals(14, inventory.size(),
        "13 in the original ETP-4878 matrix, plus 1 more ETP-5116 grant from a later pass "
            + "(Escaneo inteligente)");

    WindowGrant salesOrder = grantFor(inventory, WINDOW_SALES_ORDER);
    assertTrue(salesOrder != null && salesOrder.isReadOnly(),
        "Almacén has read-only (R) access to Pedido de venta per the matrix");

    WindowGrant warehouse = grantFor(inventory, WINDOW_WAREHOUSE);
    assertTrue(warehouse != null && !warehouse.isReadOnly(),
        "Almacén has full access to its own Warehouse window");
  }

  /**
   * ETP-5116 — "Informes financieros" / Financial Reports ({@code
   * D647D118F5014D00AF47A636B2CD0DD3}) is a brand-new pseudo-{@code AD_Window} permission anchor
   * (0 tabs) created for the {@code report-viewer-finance} menu item. Per the target matrix it is
   * Financiero-only: Ventas, Compras and Almacén must have NO access to it.
   */
  @Test
  void financialReportsIsGrantedToFinancieroOnlyAndFullAccess() {
    Map<String, List<WindowGrant>> byRoleId = TemplateRoleWindowAccess.byRoleId();

    WindowGrant financeGrant = grantFor(byRoleId.get(SystemRoleTemplates.FINANCE_ROLE_ID),
        WINDOW_FINANCIAL_REPORTS);
    assertTrue(financeGrant != null && !financeGrant.isReadOnly(),
        "ETP-5116: Financiero must have FULL access to Informes financieros (Financial Reports)");

    assertNull(grantFor(byRoleId.get(SystemRoleTemplates.SALES_ROLE_ID), WINDOW_FINANCIAL_REPORTS),
        "ETP-5116: Ventas must have NO access to Informes financieros — Financiero-only per the "
            + "target matrix");
    assertNull(grantFor(byRoleId.get(SystemRoleTemplates.PURCHASING_ROLE_ID), WINDOW_FINANCIAL_REPORTS),
        "ETP-5116: Compras must have NO access to Informes financieros — Financiero-only per the "
            + "target matrix");
    assertNull(grantFor(byRoleId.get(SystemRoleTemplates.INVENTORY_ROLE_ID), WINDOW_FINANCIAL_REPORTS),
        "ETP-5116: Almacén must have NO access to Informes financieros — Financiero-only per the "
            + "target matrix");
  }

  /**
   * ETP-5116 — "Escaneo inteligente" / Smart Scan ({@code 33705E0F52874D91B0BB2FF8BB648B8E}) is a
   * brand-new pseudo-{@code AD_Window} permission anchor (0 tabs). Per the target matrix it is
   * intentionally open to everyone: all four non-Admin templates get FULL access. Admin is out of
   * scope for this matrix (see class javadoc) — it already bypasses window-access checks entirely
   * via {@code NeoAccessHelper#isAdminOrClientAdmin}, so it needs no explicit row here to also
   * have access, making all 5 roles (4 explicit + Admin's implicit bypass) effectively covered.
   */
  @Test
  void smartScanIsGrantedFullAccessToAllFourNonAdminTemplates() {
    Map<String, List<WindowGrant>> byRoleId = TemplateRoleWindowAccess.byRoleId();

    for (String roleId : List.of(SystemRoleTemplates.FINANCE_ROLE_ID, SystemRoleTemplates.SALES_ROLE_ID,
        SystemRoleTemplates.PURCHASING_ROLE_ID, SystemRoleTemplates.INVENTORY_ROLE_ID)) {
      WindowGrant grant = grantFor(byRoleId.get(roleId), WINDOW_SMART_SCAN);
      assertTrue(grant != null && !grant.isReadOnly(),
          "ETP-5116: role " + roleId + " must have FULL access to Escaneo inteligente (Smart "
              + "Scan) — intentionally open to every non-Admin template");
    }
  }

  @Test
  void productCategoryIsReadOnlyForSalesAndPurchasingFullForInventoryAndAbsentForFinance() {
    Map<String, List<WindowGrant>> byRoleId = TemplateRoleWindowAccess.byRoleId();

    WindowGrant salesGrant = grantFor(byRoleId.get(SystemRoleTemplates.SALES_ROLE_ID), WINDOW_PRODUCT_CATEGORY);
    WindowGrant purchasingGrant = grantFor(byRoleId.get(SystemRoleTemplates.PURCHASING_ROLE_ID), WINDOW_PRODUCT_CATEGORY);
    WindowGrant financeGrant = grantFor(byRoleId.get(SystemRoleTemplates.FINANCE_ROLE_ID), WINDOW_PRODUCT_CATEGORY);
    WindowGrant inventoryGrant = grantFor(byRoleId.get(SystemRoleTemplates.INVENTORY_ROLE_ID), WINDOW_PRODUCT_CATEGORY);

    assertTrue(salesGrant != null && salesGrant.isReadOnly());
    assertTrue(purchasingGrant != null && purchasingGrant.isReadOnly());
    assertNull(financeGrant,
        "ETP-5116: Financiero must have NO access to Categoría del producto — the previous "
            + "full(\"144\") grant was an over-grant and has been removed");
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
    assertEquals(73, total,
        "13 (Sales) + 13 (Purchasing) + 33 (Finance) + 14 (Inventory) = 73 — from the original "
            + "64: +2 for window 107 (Receipt-Invoice Link) added to Purchasing and Finance by "
            + "ETP-5075, then ETP-5116 removed 1 net grant (Sales -1 for dropping full(\"168\")) "
            + "and added 3 net to Finance (+2/-2 for the 144/168 over-grant removals and the 2 "
            + "new SII Monitor/Tax Report proxy grants, then +3 more for the direct Configuración "
            + "fiscal grants — SII/TBAI/Verifactu Configuration), reaching 68, then a later "
            + "ETP-5116 pass added Escaneo inteligente to all 4 roles (+4) and Informes "
            + "financieros to Finance only (+1), reaching 73");
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
   *
   * <p>ETP-5116 update: 34 distinct windows (33 + window 107 from ETP-5075) plus 2 proxy windows
   * (SII Monitor, Tax Report) plus 3 more direct windows (SII/TBAI/Verifactu Configuration) = 39.
   * The removed over-grants (144, 168) did not change the distinct count since both windows
   * remain granted elsewhere (144 via Sales/Purchasing/Inventory; 168 via Inventory). A later
   * ETP-5116 pass added 2 more brand-new distinct windows (Informes financieros, Escaneo
   * inteligente) = 41. Escaneo inteligente is granted to all 4 roles but counts once here.</p>
   */
  @Test
  void thirtySixDistinctWindowIdsAreCoveredAcrossAllFourRoles() {
    Set<String> distinctWindowIds = new TreeSet<>();
    for (List<WindowGrant> grants : TemplateRoleWindowAccess.byRoleId().values()) {
      for (WindowGrant grant : grants) {
        distinctWindowIds.add(grant.getWindowId());
      }
    }
    assertEquals(41, distinctWindowIds.size(),
        "The matrix's 73 grants must resolve to exactly 41 distinct AD_Window_IDs once shared "
            + "windows (e.g. Contactos, Producto, Tarifa, Escaneo inteligente) are counted once — "
            + "34 from ETP-4878/ETP-5075 (window 107 added to both Purchasing and Finance), plus "
            + "2 ETP-5116 proxy windows (SII Monitor, Tax Report) and 3 more ETP-5116 direct "
            + "windows (SII/TBAI/Verifactu Configuration), all added to Finance, plus 2 more "
            + "brand-new ETP-5116 windows from a later pass (Informes financieros — Finance only; "
            + "Escaneo inteligente — all 4 roles, counted once)");
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

  // --- ETP-5116: standalone-process grants (Documentos no contabilizados, aging schedules) ---

  private static final String PROCESS_NOT_POSTED_DOCUMENTS = "D6AB95CE52D34E1599590526115E26C6";
  private static final String PROCESS_RECEIVABLES_AGING = "0D37A9F6109549DEB058373EF2DAEB6A";
  private static final String PROCESS_PAYABLES_AGING = "EB4C4053F3B94A17A08D1DD7E89CEB7E";

  @Test
  void exposesExactlyTheFourNonAdminTemplateRolesForStandaloneProcessGrants() {
    Map<String, List<String>> byRoleId = TemplateRoleWindowAccess.standaloneProcessGrantsByRoleId();
    assertEquals(4, byRoleId.size());
    assertTrue(byRoleId.containsKey(SystemRoleTemplates.FINANCE_ROLE_ID));
    assertTrue(byRoleId.containsKey(SystemRoleTemplates.SALES_ROLE_ID));
    assertTrue(byRoleId.containsKey(SystemRoleTemplates.PURCHASING_ROLE_ID));
    assertTrue(byRoleId.containsKey(SystemRoleTemplates.INVENTORY_ROLE_ID));
  }

  @Test
  void financeHasAllThreeStandaloneProcessGrants() {
    List<String> finance = TemplateRoleWindowAccess.standaloneProcessGrantsByRoleId()
        .get(SystemRoleTemplates.FINANCE_ROLE_ID);
    assertEquals(3, finance.size(),
        "Financiero holds all three ETP-5116 standalone processes per the v2 target matrix: the "
            + "Documentos no contabilizados proxy plus BOTH aging schedules");
    assertTrue(finance.contains(PROCESS_NOT_POSTED_DOCUMENTS),
        "Financiero must have the Documentos no contabilizados proxy grant");
    assertTrue(finance.contains(PROCESS_RECEIVABLES_AGING),
        "Financiero must have the Receivables Aging Schedule grant");
    assertTrue(finance.contains(PROCESS_PAYABLES_AGING),
        "Financiero must have the Payables Aging Schedule grant");
  }

  @Test
  void salesHasOnlyTheReceivablesAgingStandaloneGrant() {
    List<String> sales = TemplateRoleWindowAccess.standaloneProcessGrantsByRoleId()
        .get(SystemRoleTemplates.SALES_ROLE_ID);
    assertEquals(List.of(PROCESS_RECEIVABLES_AGING), sales,
        "Ventas must have exactly the Receivables Aging Schedule standalone grant, nothing else");
  }

  @Test
  void purchasingHasOnlyThePayablesAgingStandaloneGrant() {
    List<String> purchasing = TemplateRoleWindowAccess.standaloneProcessGrantsByRoleId()
        .get(SystemRoleTemplates.PURCHASING_ROLE_ID);
    assertEquals(List.of(PROCESS_PAYABLES_AGING), purchasing,
        "Compras must have exactly the Payables Aging Schedule standalone grant, nothing else");
  }

  @Test
  void inventoryHasNoStandaloneProcessGrants() {
    List<String> inventory = TemplateRoleWindowAccess.standaloneProcessGrantsByRoleId()
        .get(SystemRoleTemplates.INVENTORY_ROLE_ID);
    assertTrue(inventory.isEmpty(), "Almacén must have zero ETP-5116 standalone-process grants");
  }

  @Test
  void noStandaloneProcessIsGrantedTwiceWithinTheSameRole() {
    for (Map.Entry<String, List<String>> entry
        : TemplateRoleWindowAccess.standaloneProcessGrantsByRoleId().entrySet()) {
      Set<String> seen = new HashSet<>(entry.getValue());
      assertEquals(entry.getValue().size(), seen.size(),
          entry.getKey() + " must not repeat the same standalone process id twice in its own "
              + "grant list");
    }
  }

  /**
   * "Idempotency" at this DB-free data-class level: the same contract {@link
   * #byRoleIdReturnsAFreshMutableMapEachCall} already locks in for the window matrix — mutating a
   * caller's copy of the returned map must never leak into the next caller. This is what makes
   * {@code EnsureSystemRoleTemplatesScript}'s own per-run reconciliation safe to re-invoke without
   * accumulating state between roles/runs; the DB-level "running the reconciliation twice creates
   * no duplicate row" guarantee itself lives in that class's {@code upsertObuiappProcessAccess}/
   * {@code removeStaleStandaloneProcessAccess} and is exercised there, not here (this class has no
   * {@code ConnectionProvider} at all — see class javadoc).
   */
  @Test
  void standaloneProcessGrantsByRoleIdReturnsAFreshMutableMapEachCall() {
    Map<String, List<String>> first = TemplateRoleWindowAccess.standaloneProcessGrantsByRoleId();
    first.clear();
    Map<String, List<String>> second = TemplateRoleWindowAccess.standaloneProcessGrantsByRoleId();
    assertEquals(4, second.size(),
        "Mutating a caller's copy must never affect the next caller — "
            + "standaloneProcessGrantsByRoleId() must return a fresh map each time");
  }
}
