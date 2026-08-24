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
package com.etendoerp.go.modulescript;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;

/**
 * ETP-4852 — seeds the four system-level ({@code AD_Client_ID = '0'}) fixed-role templates
 * (Finance, Sales, Purchasing, Inventory), replacing the old per-client clone (the retired
 * {@code OnboardingRoleProvisioningService}, deleted as dead code once this template-inheritance
 * model landed — REVIEW cycle 1 cleanup). Runs automatically on every {@code update.database} (the standard
 * {@code ModuleScript} contract — see {@code org.openbravo.modulescript.ModuleScript}); fully
 * idempotent, so re-running it on an environment that already has the rows is a no-op.
 *
 * <p><b>Deliberately excludes "Admin".</b> The client-level {@code is_client_admin = 'Y'} role
 * stays exactly as core provisions it per tenant — this script never touches it.</p>
 *
 * <p><b>Fully self-contained by design — no dependency on this module's own {@code src/} tree,
 * full stop.</b> The role ids ({@link com.etendoerp.go.roles.SystemRoleTemplates}) and the
 * ETP-4878 window-access matrix ({@link com.etendoerp.go.roles.TemplateRoleWindowAccess}) both
 * exist as plain {@code src/} classes too — reused by {@code UserRoleCompositionService}, the
 * webhooks, and their own {@code src-test} unit tests — but this script does NOT import either
 * one. It carries its own literal copies of the four role ids and its own inlined window-access
 * matrix below, duplicated on purpose (keep them in sync if a template role or its window grants
 * ever change).
 *
 * <p>Why: in the real automated deploy pipeline, {@code update.database} (and the
 * {@code buildvalidation} step that loads and runs this class) runs BEFORE the module's main
 * {@code src/} tree has been compiled at all — {@code compile.complete}/smartbuild is a later,
 * separate step. A brand-new {@code src/} class introduced by the same branch as this script (like
 * {@code SystemRoleTemplates} was for ETP-4852/ETP-4878) has no pre-existing compiled artifact
 * from any prior deploy, so importing it here throws {@code NoClassDefFoundError} the moment this
 * class is loaded — this is exactly the failure ETP-4852/ETP-4878 shipped with under that
 * ordering. An earlier revision of this javadoc claimed the cross-tree import "works" because
 * {@code compile.modulescript} resolves it fine against the module's already-built main classes —
 * true only under a manual dev workflow (compile main {@code src/} once, then
 * {@code compile.modulescript}, then {@code update.database}), never under the real deploy order.
 * Every OTHER script in {@code src-util/modulescript/} already followed self-containment for this
 * reason; this class now does too.</p>
 *
 * <p>The window-access matrix is kept as its own reusable, unit-testable {@code src/} class
 * ({@code TemplateRoleWindowAccess}, see {@code TemplateRoleWindowAccessTest}) specifically so the
 * 64-row data is reachable by a normal {@code src-test} unit test with no DB and no Gradle
 * classpath workaround — that benefit is why the matrix lives there as the canonical copy, not a
 * reason for this script to import it. This script stays a thin consumer of its own inlined copy:
 * role creation + the check-then-insert/reconcile idempotency pattern below, nothing else.</p>
 *
 * <p><b>ETP-4878 — real permission matrix (supersedes the old 2-window-per-role smoke test).</b>
 * Each template now carries the full window-access matrix from the ticket (Ventas/Compras/
 * Financiero/Almacén columns; "Admin" stays client-level and is out of scope). Grant counts:
 * Sales 13, Purchasing 11, Finance 27, Inventory 13 (33 distinct windows, some shared across more
 * than one role at different access levels — e.g. "Categoría del producto" is read-only for
 * Sales/Purchasing but full for Finance/Inventory). "Asientos manuales" resolves to the
 * <b>Simple G/L Journal</b> window ({@code B917E8A7B0864ACEA9D941E3B7494E53}), not the classic
 * {@code G/L Journal} (window {@code 132}, which literally carries the ES label "Asientos
 * manuales" but has no Schema Forge spec at all) — a human call on an otherwise genuinely
 * ambiguous resolution, made explicitly for this ticket. Full per-window detail lives in
 * {@code TemplateRoleWindowAccess}'s own javadoc, not duplicated here.</p>
 *
 * <p><b>ETP-4830 item #6.3 — process/report access, mechanical follow-up to the window matrix
 * above.</b> A real-DB audit found all four templates had ZERO {@code AD_Process_Access}/
 * {@code obuiapp_process_access} rows despite the 64 window grants — a composed user could open
 * a window but not click any action button on it. {@link #reconcileProcessAccess} closes this
 * for every window a role has FULL access to: every classic/OBUIAPP process reachable as a
 * button on that window is granted, queried LIVE from the DB every run (not a hardcoded list,
 * unlike the window matrix above) so it self-heals if a button is later added/removed. Read-only
 * window grants contribute no process access. Deliberately mechanical, not a hand-curated
 * per-role judgment call (~270 individual report/process items exist system-wide — curating all
 * of them was explicitly scoped out as its own follow-up ticket); STANDALONE reports/processes
 * not tied to any window button remain a separate, known gap. See that method's own javadoc for
 * the full rule and rationale.</p>
 *
 * <p><b>Twelve matrix rows are deliberately NOT implemented — known gap, follow-up ticket
 * pending.</b> Every one of these has NO {@code AD_Window_ID} at all backing it (either a pure
 * custom/aggregate Schema Forge page with zero classic-AD entity, or a report-type spec whose
 * access is resolved via a different, non-window mechanism) — {@code AD_Window_Access} cannot
 * express a grant against something that has no window. Listed here so the gap is visible from
 * the class that would otherwise silently look complete:
 * <ul>
 *   <li><b>Inicio (Dashboard)</b> — {@code dashboard} spec is pure widget-handler qualifiers, no
 *       {@code ad_tab_id}/{@code ad_window_id} anywhere.</li>
 *   <li><b>Favoritos</b> — no backing AD entity of any kind found (app-shell client feature).</li>
 *   <li><b>Copilot (Asistente IA)</b> — {@code AD_Menu} "Copilot" exists but its
 *       {@code ad_window_id} is null (points at an embedded chat feature, not a window).</li>
 *   <li><b>Informes de inventario</b> — {@code inventory-stock-report} spec, type R, no window,
 *       no tab; pure webhook handler.</li>
 *   <li><b>Documentos no contabilizados</b> — {@code not-posted-documents} spec, type W but
 *       {@code ad_window_id} null; fully custom, no classic window backing it.</li>
 *   <li><b>Monitor fiscal</b> — {@code fiscal-monitor} artifact is {@code category: "custom"},
 *       {@code entities: {}}; not even pushed to {@code ETGO_SF_SPEC}.</li>
 *   <li><b>Modelos fiscales</b> — {@code fiscal-models} artifact has no {@code decisions.json} at
 *       all yet (only mock data) — earliest possible pipeline stage.</li>
 *   <li><b>Informes financieros</b> — no single window backs this label; multiple jsreport-print
 *       candidates exist ({@code profit-loss}, {@code balance-sheet}, {@code tax-report}, the
 *       {@code reports} index, …), none with an {@code AD_Window_ID} — likely a menu category,
 *       not one window.</li>
 *   <li><b>Informe Antigüedad de Cobros</b> — {@code aging-receivable} spec exists (type R) but
 *       has neither {@code ad_window_id} nor {@code ad_tab_id}; same report-access-mechanism gap
 *       as ETP-4596.</li>
 *   <li><b>Informe Antigüedad de Pagos</b> — no {@code ETGO_SF_SPEC} row exists at all (only a
 *       jsreport template artifact); more severe than its sibling above.</li>
 *   <li><b>Escaneo inteligente</b> — {@code smart-scan} artifact is an aggregate/custom route
 *       page ({@code /smart-scan}); no {@code ad_window}/{@code ad_menu} entry whatsoever.</li>
 *   <li><b>Configuración fiscal</b> — {@code fiscal-config} artifact is {@code category:
 *       "configuration"}, {@code entities: {}}; not in {@code ETGO_SF_SPEC}.</li>
 * </ul>
 * See {@code docs/neo-headless.md} (in this module) for the same list with the research
 * dispatch's full resolution table. Populating these 12 requires either building the missing AD
 * entity/spec first or a different, non-{@code AD_Window_Access} grant mechanism — out of scope
 * for this script until that follow-up ticket lands.</p>
 *
 * <p><b>"Roles", "Usuario", and "Conectar asistente de IA" resolve to real {@code AD_Window_ID}s
 * (111, 108, and {@code 6006F3B3DDF74D618CBEE21BEFD398DC} respectively) but are deliberately NOT
 * granted to any of the four templates</b> — the ticket's matrix shows "—" (no access) for all
 * four non-Admin roles on all three. They stay Admin-only, consistent with Admin being out of
 * scope for this ticket.</p>
 *
 * <p><b>This class's compiled {@code .class} files are committed to git</b> — an intentional
 * exception to this module's {@code build/} gitignore, force-added, and unusual for
 * {@code com.etendoerp.go}'s own module scripts (it mirrors Etendo core's long-standing precedent
 * of committing compiled {@code ModuleScript} classes under
 * {@code src-util/modulescript/build/classes/org/openbravo/modulescript/}). The reason is a build
 * quirk, not a style choice: {@code update.database} executes whatever {@code ModuleScript}
 * classes are already compiled — it does NOT compile them itself. Compilation is the separate,
 * easy-to-forget {@code ./gradlew compile.modulescript -Dmodule=com.etendoerp.go} step. Shipping
 * the compiled bytes means a fresh checkout of this branch runs this script correctly on
 * {@code update.database} with no extra step.</p>
 *
 * <p><b>Consequence for future edits — read before touching this file.</b> After ANY change to
 * this source, you MUST re-run {@code ./gradlew compile.modulescript -Dmodule=com.etendoerp.go}
 * and {@code git add -f} the regenerated {@code .class} file(s) again before committing. Skip the
 * recompile and the checked-in binary silently diverges from this source — {@code update.database}
 * keeps running the stale, pre-edit logic with no compile error or warning to flag it. If a
 * template role id or a window-access grant ever changes in {@code SystemRoleTemplates} /
 * {@code TemplateRoleWindowAccess}, update the literal copies below to match — nothing enforces
 * that sync automatically since this script deliberately does not import either class.</p>
 */
public class EnsureSystemRoleTemplatesScript extends ModuleScript {

  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String STAR_ORG_ID = "0";
  private static final String SYSTEM_ADMIN_USER_ID = "0";

  /** {@code AD_Role.UserLevel} shared by every fixed role in this fleet (client + org). */
  private static final String USER_LEVEL = "  O";

  /**
   * Literal copies of {@code SystemRoleTemplates}'s four role ids — duplicated on purpose, see
   * class javadoc. Keep in sync if a template role is ever recreated with a new id.
   */
  private static final String FINANCE_ROLE_ID = "B88A34B5D1874F8685FA6F3C3A609412";
  private static final String SALES_ROLE_ID = "15ECC46CFBD74CF3A76D1F4DC8BA9F80";
  private static final String PURCHASING_ROLE_ID = "5E279F5102F9410F9B8CCBA424741F46";
  private static final String INVENTORY_ROLE_ID = "73581A7B4F414A2C9059C83CE7BE97BF";

  /** English names for the role INSERT, keyed by the literal ids above. */
  private static final Map<String, String> ROLE_NAMES_BY_ID = namesByRoleId();

  private static Map<String, String> namesByRoleId() {
    Map<String, String> byId = new LinkedHashMap<>();
    byId.put(FINANCE_ROLE_ID, "Finance");
    byId.put(SALES_ROLE_ID, "Sales");
    byId.put(PURCHASING_ROLE_ID, "Purchasing");
    byId.put(INVENTORY_ROLE_ID, "Inventory");
    return byId;
  }

  /**
   * One window grant: the window id and whether it is read-only ("R") vs. full ("✓") access.
   * Inlined copy of {@code TemplateRoleWindowAccess.WindowGrant} — see class javadoc for why this
   * script keeps its own copy instead of importing the {@code src/} class.
   */
  private static final class WindowGrant {
    private final String windowId;
    private final boolean readOnly;

    private WindowGrant(String windowId, boolean readOnly) {
      this.windowId = windowId;
      this.readOnly = readOnly;
    }

    private String getWindowId() {
      return windowId;
    }

    private boolean isReadOnly() {
      return readOnly;
    }
  }

  private static WindowGrant full(String windowId) {
    return new WindowGrant(windowId, false);
  }

  private static WindowGrant readOnly(String windowId) {
    return new WindowGrant(windowId, true);
  }

  /**
   * Sales ("Ventas") column of the ETP-4878 matrix — 13 grants. Comments name the matrix row in
   * Spanish (matching the ticket) followed by the AD_Window's own English name. Inlined copy of
   * {@code TemplateRoleWindowAccess#salesGrants()}.
   */
  private static List<WindowGrant> salesGrants() {
    return List.of(
        full("123"),                                          // Contactos — Business Partner
        full("6CB5B67ED33F47DFA334079D3EA2340E"),              // Presupuesto — Sales Quotation
        full("143"),                                           // Pedido de venta — Sales Order
        full("169"),                                           // Albarán de venta — Goods Shipment
        full("167"),                                           // Factura de venta — Sales Invoice
        full("FF808081330213E60133021822E40007"),              // Albarán de devolución — Return from Customer
        full("140"),                                           // Producto — Product
        readOnly("144"),                                       // Categoría del producto — Product Category
        full("168"),                                           // Inventario físico — Physical Inventory
        full("E547CE89D4C04429B6340FFA44E70716"),              // Cobro — Payment In
        full("146"),                                           // Tarifa — Price List
        readOnly("141"),                                       // Condiciones de pago — Payment Term
        readOnly("192"));                                      // Categoría de contacto — Business Partner Category
  }

  /**
   * Purchasing ("Compras") column of the ETP-4878 matrix — 11 grants. Inlined copy of
   * {@code TemplateRoleWindowAccess#purchasingGrants()}.
   */
  private static List<WindowGrant> purchasingGrants() {
    return List.of(
        full("123"),                                          // Contactos — Business Partner
        full("181"),                                          // Pedido de compra — Purchase Order
        full("184"),                                          // Albarán de compra — Goods Receipt
        full("183"),                                          // Factura de compra — Purchase Invoice
        full("C50A8AEE6F044825B5EF54FAAE76826F"),              // Devolución a proveedor — Return to Vendor
        full("140"),                                          // Producto — Product
        readOnly("144"),                                       // Categoría del producto — Product Category
        full("6F8F913FA60F4CBD93DC1D3AA696E76E"),              // Pago — Payment Out
        full("146"),                                          // Tarifa — Price List
        readOnly("141"),                                       // Condiciones de pago — Payment Term
        readOnly("192"));                                      // Categoría de contacto — Business Partner Category
  }

  /**
   * Finance ("Financiero") column of the ETP-4878 matrix — 27 grants. Inlined copy of
   * {@code TemplateRoleWindowAccess#financeGrants()}.
   */
  private static List<WindowGrant> financeGrants() {
    return List.of(
        full("123"),                                          // Contactos — Business Partner
        readOnly("6CB5B67ED33F47DFA334079D3EA2340E"),          // Presupuesto — Sales Quotation
        readOnly("143"),                                       // Pedido de venta — Sales Order
        full("167"),                                          // Factura de venta — Sales Invoice
        readOnly("181"),                                       // Pedido de compra — Purchase Order
        full("183"),                                          // Factura de compra — Purchase Invoice
        full("140"),                                          // Producto — Product
        full("144"),                                          // Categoría del producto — Product Category
        full("168"),                                          // Inventario físico — Physical Inventory
        full("139"),                                          // Almacén — Warehouse and Storage Bins
        full("E547CE89D4C04429B6340FFA44E70716"),              // Cobro — Payment In
        full("6F8F913FA60F4CBD93DC1D3AA696E76E"),              // Pago — Payment Out
        full("94EAA455D2644E04AB25D93BE5157B6D"),              // Cuentas — Financial Account
        full("118"),                                          // Plan de cuentas — Account Tree
        full("125"),                                          // Esquema contable — General Ledger Configuration
        full("117"),                                          // Calendario — Fiscal Calendar
        full("800027"),                                       // Activos — Assets
        full("252"),                                          // Grupo de activos — Asset Group
        full("800026"),                                       // Amortización — Amortization
        full("B917E8A7B0864ACEA9D941E3B7494E53"),              // Asientos manuales — Simple G/L Journal (decision 2)
        full("116"),                                          // Rangos de conversión — Conversion Rates
        full("146"),                                          // Tarifa — Price List
        full("141"),                                          // Condiciones de pago — Payment Term
        full("137"),                                          // Impuesto — Tax Rate
        full("138"),                                          // Categoría de impuesto — Tax Category
        full("192"),                                          // Categoría de contacto — Business Partner Category
        full("6FEBA130CDE24CC09041FFA6117ADFA9"));             // Registro descarga tipos de cambio — Conversion Rate Downloader Log
  }

  /**
   * Inventory ("Almacén") column of the ETP-4878 matrix — 13 grants. Inlined copy of
   * {@code TemplateRoleWindowAccess#inventoryGrants()}.
   */
  private static List<WindowGrant> inventoryGrants() {
    return List.of(
        readOnly("123"),                                      // Contactos — Business Partner
        readOnly("143"),                                       // Pedido de venta — Sales Order
        full("169"),                                          // Albarán de venta — Goods Shipment
        full("FF808081330213E60133021822E40007"),              // Albarán de devolución — Return from Customer
        readOnly("181"),                                       // Pedido de compra — Purchase Order
        full("184"),                                          // Albarán de compra — Goods Receipt
        full("C50A8AEE6F044825B5EF54FAAE76826F"),              // Devolución a proveedor — Return to Vendor
        full("140"),                                          // Producto — Product
        full("144"),                                          // Categoría del producto — Product Category
        full("168"),                                          // Inventario físico — Physical Inventory
        full("170"),                                          // Movimiento entre almacenes — Goods Movements
        full("800076"),                                       // Consumo interno — Internal Consumption
        full("139"));                                          // Almacén — Warehouse and Storage Bins
  }

  /**
   * The full role→grant-list matrix, keyed by {@code AD_Role_ID} — one entry per template role,
   * in Finance/Sales/Purchasing/Inventory order. Inlined copy of
   * {@code TemplateRoleWindowAccess#byRoleId()}.
   */
  private static Map<String, List<WindowGrant>> windowAccessByRoleId() {
    Map<String, List<WindowGrant>> map = new LinkedHashMap<>();
    map.put(FINANCE_ROLE_ID, financeGrants());
    map.put(SALES_ROLE_ID, salesGrants());
    map.put(PURCHASING_ROLE_ID, purchasingGrants());
    map.put(INVENTORY_ROLE_ID, inventoryGrants());
    return map;
  }

  @Override
  public void execute() {
    try {
      ConnectionProvider cp = getConnectionProvider();
      Map<String, List<WindowGrant>> grantsByRoleId = windowAccessByRoleId();
      for (Map.Entry<String, List<WindowGrant>> entry : grantsByRoleId.entrySet()) {
        String roleId = entry.getKey();
        ensureRole(cp, roleId, ROLE_NAMES_BY_ID.get(roleId));
        reconcileWindowAccess(cp, roleId, entry.getValue());
        reconcileProcessAccess(cp, roleId, entry.getValue());
      }
    } catch (Exception e) {
      handleError(e);
    }
  }

  /**
   * Inserts the system-level template role for {@code roleId} unless a role with that exact ID
   * already exists (the ID is literal/fixed, so a re-run's own previous insert is what this
   * guard normally matches — not a name lookup, which would be a weaker guard against a
   * same-named row created some other way).
   */
  private void ensureRole(ConnectionProvider cp, String roleId, String name) throws Exception {
    if (exists(cp, "SELECT 1 FROM AD_Role WHERE AD_Role_ID = ?", roleId)) {
      return;
    }
    String sql = "INSERT INTO AD_Role (AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, "
        + "Created, CreatedBy, Updated, UpdatedBy, Name, Description, UserLevel, IsManual, "
        + "Is_Client_Admin, IsAdvanced, IsRestrictBackend, IsPortal, IsPortalAdmin, "
        + "IsWebServiceEnabled, IsTemplate, EM_ETGO_Show_Acct_Fields) "
        + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, 'Y', 'N', 'N', 'N', 'N', 'N', 'N', "
        + "'Y', 'N')";
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      ps.setString(1, roleId);
      ps.setString(2, SYSTEM_CLIENT_ID);
      ps.setString(3, STAR_ORG_ID);
      ps.setString(4, SYSTEM_ADMIN_USER_ID);
      ps.setString(5, SYSTEM_ADMIN_USER_ID);
      ps.setString(6, name);
      ps.setString(7, "System-level template role (ETP-4852) — compose per-user personal roles "
          + "by inheriting from this template, never edit directly.");
      ps.setString(8, USER_LEVEL);
      ps.executeUpdate();
    }
  }

  /**
   * Reconciles {@code roleId}'s {@code AD_Window_Access} rows against {@code grants} (this
   * script's own inlined copy of the {@code TemplateRoleWindowAccess} matrix) — the ETP-4878
   * replacement for the old insert-only smoke test. For
   * every desired grant: inserts it if missing, or corrects {@code IsReadWrite} in place if an
   * active row already exists with the wrong access level (e.g. a window that moved from "R" to
   * "✓" between revisions of the matrix). Then removes (hard {@code DELETE}, mirroring the
   * "remove it, don't just leave it" spirit of {@code UserRoleCompositionService
   * #reconcileInheritances}) any active grant this role has for a window that is NOT in the
   * current matrix — e.g. the old 2-window smoke-test grants, for roles/windows the real matrix
   * says "—" for.
   *
   * <p>Scoped strictly per {@code roleId}: only rows owned by THIS template role are ever
   * touched, never another role's. Safe because these four template roles are entirely managed
   * by this script — no other code path writes {@code AD_Window_Access} rows for them.</p>
   *
   * <p><b>Out of scope:</b> retroactively updating personal roles that already inherited from a
   * template before this reconciliation ran. This script writes raw SQL against the TEMPLATE role
   * only — the {@code RoleInheritanceManager} propagation covered by {@code
   * UserRoleCompositionServiceIntegrationTest} fires off {@code AD_Role_Inheritance}/{@code
   * AD_Window_Access} Hibernate events, which this JDBC-only {@code ModuleScript} does not
   * generate. Retroactively re-syncing already-composed personal roles when a template's matrix
   * changes later is ETP-4877's territory, not this script's.</p>
   */
  private void reconcileWindowAccess(ConnectionProvider cp, String roleId, List<WindowGrant> grants)
      throws Exception {
    Set<String> desiredWindowIds = new HashSet<>();
    for (WindowGrant grant : grants) {
      desiredWindowIds.add(grant.getWindowId());
      upsertWindowAccess(cp, roleId, grant.getWindowId(), grant.isReadOnly());
    }
    removeStaleWindowAccess(cp, roleId, desiredWindowIds);
  }

  /**
   * Inserts one {@code AD_Window_Access} row for {@code roleId}/{@code windowId} unless an active
   * row already exists; if one exists but its {@code IsReadWrite} disagrees with {@code
   * readOnly}, updates it in place instead of leaving a stale access level.
   */
  private void upsertWindowAccess(ConnectionProvider cp, String roleId, String windowId,
      boolean readOnly) throws Exception {
    String desiredReadWrite = readOnly ? "N" : "Y";
    String currentReadWrite = singleString(cp,
        "SELECT IsReadWrite FROM AD_Window_Access WHERE AD_Role_ID = ? AND AD_Window_ID = ? "
            + "AND IsActive = 'Y'",
        roleId, windowId);
    if (currentReadWrite == null) {
      insertWindowAccess(cp, roleId, windowId, desiredReadWrite);
    } else if (!currentReadWrite.equals(desiredReadWrite)) {
      updateWindowAccessReadWrite(cp, roleId, windowId, desiredReadWrite);
    }
  }

  private void insertWindowAccess(ConnectionProvider cp, String roleId, String windowId,
      String readWrite) throws Exception {
    String sql = "INSERT INTO AD_Window_Access (AD_Window_Access_ID, AD_Client_ID, AD_Org_ID, "
        + "IsActive, Created, CreatedBy, Updated, UpdatedBy, AD_Role_ID, AD_Window_ID, "
        + "IsReadWrite) VALUES (get_uuid(), ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?)";
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      ps.setString(1, SYSTEM_CLIENT_ID);
      ps.setString(2, STAR_ORG_ID);
      ps.setString(3, SYSTEM_ADMIN_USER_ID);
      ps.setString(4, SYSTEM_ADMIN_USER_ID);
      ps.setString(5, roleId);
      ps.setString(6, windowId);
      ps.setString(7, readWrite);
      ps.executeUpdate();
    }
  }

  private void updateWindowAccessReadWrite(ConnectionProvider cp, String roleId, String windowId,
      String readWrite) throws Exception {
    String sql = "UPDATE AD_Window_Access SET IsReadWrite = ?, Updated = now(), UpdatedBy = ? "
        + "WHERE AD_Role_ID = ? AND AD_Window_ID = ? AND IsActive = 'Y'";
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      ps.setString(1, readWrite);
      ps.setString(2, SYSTEM_ADMIN_USER_ID);
      ps.setString(3, roleId);
      ps.setString(4, windowId);
      ps.executeUpdate();
    }
  }

  /**
   * Deletes every active {@code AD_Window_Access} row for {@code roleId} whose window is NOT in
   * {@code desiredWindowIds} — the reconciliation half of ETP-4878's replacement for the old
   * insert-only smoke test (e.g. the old smoke-test grants for a role/window pair the real matrix
   * now says "—" for).
   */
  private void removeStaleWindowAccess(ConnectionProvider cp, String roleId,
      Set<String> desiredWindowIds) throws Exception {
    List<String> staleWindowIds = new ArrayList<>();
    String selectSql = "SELECT AD_Window_ID FROM AD_Window_Access WHERE AD_Role_ID = ? "
        + "AND IsActive = 'Y'";
    try (PreparedStatement ps = cp.getPreparedStatement(selectSql)) {
      ps.setString(1, roleId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String windowId = rs.getString(1);
          if (!desiredWindowIds.contains(windowId)) {
            staleWindowIds.add(windowId);
          }
        }
      }
    }
    String deleteSql = "DELETE FROM AD_Window_Access WHERE AD_Role_ID = ? AND AD_Window_ID = ? "
        + "AND IsActive = 'Y'";
    for (String windowId : staleWindowIds) {
      try (PreparedStatement ps = cp.getPreparedStatement(deleteSql)) {
        ps.setString(1, roleId);
        ps.setString(2, windowId);
        ps.executeUpdate();
      }
    }
  }

  /**
   * ETP-4830 item #6.3 — process/report access, the mechanical follow-up to
   * {@link #reconcileWindowAccess}. Confirmed via a real-DB audit before this shipped: all four
   * templates had ZERO {@code AD_Process_Access}/{@code obuiapp_process_access} rows despite
   * having 64 window grants between them — so a composed user could OPEN a window (e.g. Sales
   * Order) but not click any of its action buttons (Process Order, Add Payment, Post, ...),
   * since {@code NeoAccessHelper#hasProcessAccess}/{@code #hasObuiappProcessAccess} gate those
   * independently of window access.
   *
   * <p><b>Human-approved rule (not a hand-curated matrix — deliberately mechanical):</b> for
   * every window this role has FULL (not read-only) access to, grant every classic
   * {@code AD_Process}/OBUIAPP process reachable as a BUTTON on that window's tabs (a
   * {@code AD_Column} with {@code AD_Process_ID} or {@code EM_OBUIAPP_Process_ID} set, on an
   * active {@code AD_Field}). Read-only window grants contribute NO process access — a read-only
   * window is "look but don't touch", and these buttons are all mutating actions (Post, Process
   * Order, Reverse Payment, ...), so unlocking them from a read-only grant would contradict what
   * "read-only" means. Curating a role-by-role judgment call for the ~270 individual
   * report/process items in this system (mirroring ETP-4878's window matrix) was explicitly
   * ruled out as its own, much bigger ticket — this mechanical derivation is the approved
   * interim close for the button-linked subset (~180 of those ~270); STANDALONE reports/processes
   * not tied to any window button remain a known, separate gap.</p>
   *
   * <p>Queried LIVE against the DB every run, not a hardcoded literal list (unlike {@link
   * #windowAccessByRoleId()}'s window matrix) — deliberately, so this self-heals if a button is
   * ever added to or removed from one of these windows later, with no code change needed here.
   * Propagation onto composed personal roles is NOT this script's job: core's
   * {@code RoleInheritanceManager} already propagates {@code AD_Process_Access}/
   * {@code obuiapp_process_access} the exact same generic way it propagates
   * {@code AD_Window_Access} (via its {@code ReportAndProcessAccessInjector}/
   * {@code ProcessDefinitionAccessInjector}), confirmed by inspecting the injector list — no
   * change needed in {@code UserRoleCompositionService} at all.</p>
   */
  private void reconcileProcessAccess(ConnectionProvider cp, String roleId, List<WindowGrant> grants)
      throws Exception {
    Set<String> desiredProcessIds = new HashSet<>();
    Set<String> desiredObuiappProcessIds = new HashSet<>();
    for (WindowGrant grant : grants) {
      if (grant.isReadOnly()) {
        continue;
      }
      desiredProcessIds.addAll(findButtonProcessIds(cp, grant.getWindowId()));
      desiredObuiappProcessIds.addAll(findButtonObuiappProcessIds(cp, grant.getWindowId()));
    }
    for (String processId : desiredProcessIds) {
      upsertProcessAccess(cp, roleId, processId);
    }
    removeStaleProcessAccess(cp, roleId, desiredProcessIds);
    for (String obuiappProcessId : desiredObuiappProcessIds) {
      upsertObuiappProcessAccess(cp, roleId, obuiappProcessId);
    }
    removeStaleObuiappProcessAccess(cp, roleId, desiredObuiappProcessIds);
  }

  /** Distinct {@code AD_Process_ID}s reachable as a button on any active tab of {@code windowId}. */
  private List<String> findButtonProcessIds(ConnectionProvider cp, String windowId) throws Exception {
    List<String> ids = new ArrayList<>();
    String sql = "SELECT DISTINCT c.AD_Process_ID FROM AD_Field f "
        + "JOIN AD_Column c ON c.AD_Column_ID = f.AD_Column_ID "
        + "JOIN AD_Tab t ON t.AD_Tab_ID = f.AD_Tab_ID "
        + "WHERE t.AD_Window_ID = ? AND f.IsActive = 'Y' AND c.AD_Process_ID IS NOT NULL";
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      ps.setString(1, windowId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getString(1));
        }
      }
    }
    return ids;
  }

  /**
   * Distinct {@code EM_OBUIAPP_Process_ID}s reachable as a button on any active tab of
   * {@code windowId}.
   */
  private List<String> findButtonObuiappProcessIds(ConnectionProvider cp, String windowId)
      throws Exception {
    List<String> ids = new ArrayList<>();
    String sql = "SELECT DISTINCT c.EM_OBUIAPP_Process_ID FROM AD_Field f "
        + "JOIN AD_Column c ON c.AD_Column_ID = f.AD_Column_ID "
        + "JOIN AD_Tab t ON t.AD_Tab_ID = f.AD_Tab_ID "
        + "WHERE t.AD_Window_ID = ? AND f.IsActive = 'Y' AND c.EM_OBUIAPP_Process_ID IS NOT NULL";
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      ps.setString(1, windowId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getString(1));
        }
      }
    }
    return ids;
  }

  /** Inserts one {@code AD_Process_Access} row for {@code roleId}/{@code processId} unless it exists. */
  private void upsertProcessAccess(ConnectionProvider cp, String roleId, String processId)
      throws Exception {
    if (exists(cp, "SELECT 1 FROM AD_Process_Access WHERE AD_Role_ID = ? AND AD_Process_ID = ? "
        + "AND IsActive = 'Y'", roleId, processId)) {
      return;
    }
    String sql = "INSERT INTO AD_Process_Access (AD_Process_Access_ID, AD_Client_ID, AD_Org_ID, "
        + "IsActive, Created, CreatedBy, Updated, UpdatedBy, AD_Role_ID, AD_Process_ID, "
        + "IsReadWrite) VALUES (get_uuid(), ?, ?, 'Y', now(), ?, now(), ?, ?, ?, 'Y')";
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      ps.setString(1, SYSTEM_CLIENT_ID);
      ps.setString(2, STAR_ORG_ID);
      ps.setString(3, SYSTEM_ADMIN_USER_ID);
      ps.setString(4, SYSTEM_ADMIN_USER_ID);
      ps.setString(5, roleId);
      ps.setString(6, processId);
      ps.executeUpdate();
    }
  }

  /**
   * Inserts one {@code obuiapp_process_access} row for {@code roleId}/{@code obuiappProcessId}
   * unless it exists.
   */
  private void upsertObuiappProcessAccess(ConnectionProvider cp, String roleId,
      String obuiappProcessId) throws Exception {
    if (exists(cp, "SELECT 1 FROM obuiapp_process_access WHERE AD_Role_ID = ? "
        + "AND obuiapp_process_id = ? AND IsActive = 'Y'", roleId, obuiappProcessId)) {
      return;
    }
    String sql = "INSERT INTO obuiapp_process_access (obuiapp_process_access_id, AD_Client_ID, "
        + "AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, AD_Role_ID, "
        + "obuiapp_process_id, IsReadWrite) VALUES (get_uuid(), ?, ?, 'Y', now(), ?, now(), ?, ?, "
        + "?, 'Y')";
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      ps.setString(1, SYSTEM_CLIENT_ID);
      ps.setString(2, STAR_ORG_ID);
      ps.setString(3, SYSTEM_ADMIN_USER_ID);
      ps.setString(4, SYSTEM_ADMIN_USER_ID);
      ps.setString(5, roleId);
      ps.setString(6, obuiappProcessId);
      ps.executeUpdate();
    }
  }

  /**
   * Deletes every active {@code AD_Process_Access} row for {@code roleId} whose process is NOT in
   * {@code desiredProcessIds} — same reconciliation half as {@link #removeStaleWindowAccess}.
   */
  private void removeStaleProcessAccess(ConnectionProvider cp, String roleId,
      Set<String> desiredProcessIds) throws Exception {
    List<String> staleIds = new ArrayList<>();
    String selectSql = "SELECT AD_Process_ID FROM AD_Process_Access WHERE AD_Role_ID = ? "
        + "AND IsActive = 'Y'";
    try (PreparedStatement ps = cp.getPreparedStatement(selectSql)) {
      ps.setString(1, roleId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String processId = rs.getString(1);
          if (!desiredProcessIds.contains(processId)) {
            staleIds.add(processId);
          }
        }
      }
    }
    String deleteSql = "DELETE FROM AD_Process_Access WHERE AD_Role_ID = ? AND AD_Process_ID = ? "
        + "AND IsActive = 'Y'";
    for (String processId : staleIds) {
      try (PreparedStatement ps = cp.getPreparedStatement(deleteSql)) {
        ps.setString(1, roleId);
        ps.setString(2, processId);
        ps.executeUpdate();
      }
    }
  }

  /**
   * Deletes every active {@code obuiapp_process_access} row for {@code roleId} whose process is
   * NOT in {@code desiredObuiappProcessIds} — same reconciliation half as
   * {@link #removeStaleWindowAccess}.
   */
  private void removeStaleObuiappProcessAccess(ConnectionProvider cp, String roleId,
      Set<String> desiredObuiappProcessIds) throws Exception {
    List<String> staleIds = new ArrayList<>();
    String selectSql = "SELECT obuiapp_process_id FROM obuiapp_process_access "
        + "WHERE AD_Role_ID = ? AND IsActive = 'Y'";
    try (PreparedStatement ps = cp.getPreparedStatement(selectSql)) {
      ps.setString(1, roleId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String obuiappProcessId = rs.getString(1);
          if (!desiredObuiappProcessIds.contains(obuiappProcessId)) {
            staleIds.add(obuiappProcessId);
          }
        }
      }
    }
    String deleteSql = "DELETE FROM obuiapp_process_access WHERE AD_Role_ID = ? "
        + "AND obuiapp_process_id = ? AND IsActive = 'Y'";
    for (String obuiappProcessId : staleIds) {
      try (PreparedStatement ps = cp.getPreparedStatement(deleteSql)) {
        ps.setString(1, roleId);
        ps.setString(2, obuiappProcessId);
        ps.executeUpdate();
      }
    }
  }

  private boolean exists(ConnectionProvider cp, String sql, String... params) throws Exception {
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setString(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private String singleString(ConnectionProvider cp, String sql, String... params)
      throws Exception {
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setString(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }
}
