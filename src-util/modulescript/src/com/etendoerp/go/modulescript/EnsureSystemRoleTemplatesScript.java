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

import com.etendoerp.go.roles.SystemRoleTemplates;
import com.etendoerp.go.roles.TemplateRoleWindowAccess;
import com.etendoerp.go.roles.TemplateRoleWindowAccess.WindowGrant;

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
 * <p><b>Role ids come from {@link SystemRoleTemplates}, and the ETP-4878 permission matrix comes
 * from {@link TemplateRoleWindowAccess} — a deliberate, single EXCEPTION to the usual "{@code
 * ModuleScript} is self-contained, no dependency on this module's own {@code src/} tree"
 * convention (every OTHER script here still follows that convention; see the historical note this
 * javadoc used to carry about literal-ID duplication, superseded 2026-08-13/ETP-4878).</b>
 * Confirmed empirically that {@code compile.modulescript} resolves this import fine — it compiles
 * against the module's already-built {@code main} classes, so the isolation convention is about
 * self-containment/readability of an individual script, not a hard compile-time sandbox. The
 * tradeoff was made specifically so the 64-row permission matrix is plain data reachable by a
 * normal {@code src-test} unit test (no DB, no Gradle classpath workaround) — see {@code
 * TemplateRoleWindowAccessTest}. This script stays a thin consumer: role creation + the
 * check-then-insert/reconcile idempotency pattern below, nothing else.</p>
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
 * ambiguous resolution, made explicitly for this ticket. Full per-window detail lives in {@link
 * TemplateRoleWindowAccess}'s own javadoc, not duplicated here.</p>
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
 * and {@code git add -f} the regenerated {@code .class} file again before committing (this class
 * no longer has its own nested data classes — {@code WindowGrant} now lives on {@link
 * TemplateRoleWindowAccess}, compiled as part of the module's normal {@code main} sourceSet, not
 * force-added). Skip the recompile and the checked-in binary silently diverges from this source —
 * {@code update.database} keeps running the stale, pre-edit logic with no compile error or
 * warning to flag it.</p>
 */
public class EnsureSystemRoleTemplatesScript extends ModuleScript {

  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String STAR_ORG_ID = "0";
  private static final String SYSTEM_ADMIN_USER_ID = "0";

  /** {@code AD_Role.UserLevel} shared by every fixed role in this fleet (client + org). */
  private static final String USER_LEVEL = "  O";

  /** English names for the role INSERT, keyed the same way {@link SystemRoleTemplates#byName()} is. */
  private static final Map<String, String> ROLE_NAMES_BY_ID = namesByRoleId();

  private static Map<String, String> namesByRoleId() {
    Map<String, String> byName = SystemRoleTemplates.byName();
    Map<String, String> byId = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : byName.entrySet()) {
      byId.put(entry.getValue(), entry.getKey());
    }
    return byId;
  }

  @Override
  public void execute() {
    try {
      ConnectionProvider cp = getConnectionProvider();
      Map<String, List<WindowGrant>> grantsByRoleId = TemplateRoleWindowAccess.byRoleId();
      for (Map.Entry<String, List<WindowGrant>> entry : grantsByRoleId.entrySet()) {
        String roleId = entry.getKey();
        ensureRole(cp, roleId, ROLE_NAMES_BY_ID.get(roleId));
        reconcileWindowAccess(cp, roleId, entry.getValue());
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
   * Reconciles {@code roleId}'s {@code AD_Window_Access} rows against {@code grants} (from {@link
   * TemplateRoleWindowAccess}) — the ETP-4878 replacement for the old insert-only smoke test. For
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
    String deleteSql = "DELETE FROM AD_Window_Access WHERE AD_Role_ID = ? AND AD_Window_ID = ?";
    for (String windowId : staleWindowIds) {
      try (PreparedStatement ps = cp.getPreparedStatement(deleteSql)) {
        ps.setString(1, roleId);
        ps.setString(2, windowId);
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
