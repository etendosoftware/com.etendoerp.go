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

import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;

/**
 * ETP-4852 — seeds the four system-level ({@code AD_Client_ID = '0'}) fixed-role templates
 * (Finance, Sales, Purchasing, Inventory), replacing the old per-client clone
 * ({@code OnboardingRoleProvisioningService}, now retired from the live onboarding chain — see
 * its class javadoc). Runs automatically on every {@code update.database} (the standard
 * {@code ModuleScript} contract — see {@code org.openbravo.modulescript.ModuleScript}); fully
 * idempotent, so re-running it on an environment that already has the rows is a no-op.
 *
 * <p><b>Deliberately excludes "Admin".</b> The client-level {@code is_client_admin = 'Y'} role
 * stays exactly as core provisions it per tenant — this script never touches it.</p>
 *
 * <p><b>IDs are literal, not {@code get_uuid()}-generated at insert time</b> — mirrored in {@link
 * com.etendoerp.go.roles.SystemRoleTemplates}, which is what the rest of the module (the
 * composition webhook, its tests) reference. See that class's javadoc for why the same four
 * strings are intentionally duplicated here instead of importing it: every existing
 * {@code ModuleScript} in this codebase is self-contained raw SQL against a
 * {@link ConnectionProvider}, with no dependency on its own module's {@code src/} tree (these
 * scripts run as part of {@code update.database}, before the module's own classes are
 * necessarily on that build step's classpath) — this one follows the same convention.</p>
 *
 * <p><b>Smoke-test {@code AD_Window_Access} only — NOT the real permission matrix.</b> Each
 * template gets exactly two full-access window grants, enough to prove end-to-end that core's
 * {@code RoleInheritanceManager} propagates {@code AD_Window_Access} from a system-level
 * template down to a per-tenant personal role via {@code AD_Role_Inheritance} (verified live,
 * see {@code UserRoleCompositionServiceIntegrationTest}). Populating the full 48-window
 * Admin/Ventas/Compras/Financiero/Almacén matrix from the ticket is explicitly ETP-4878's job,
 * not this script's.</p>
 */
public class EnsureSystemRoleTemplatesScript extends ModuleScript {

  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String STAR_ORG_ID = "0";
  private static final String SYSTEM_ADMIN_USER_ID = "0";

  /** {@code AD_Role.UserLevel} shared by every fixed role in this fleet (client + org). */
  private static final String USER_LEVEL = "  O";

  private static final String FINANCE_ROLE_ID = "B88A34B5D1874F8685FA6F3C3A609412";
  private static final String SALES_ROLE_ID = "15ECC46CFBD74CF3A76D1F4DC8BA9F80";
  private static final String PURCHASING_ROLE_ID = "5E279F5102F9410F9B8CCBA424741F46";
  private static final String INVENTORY_ROLE_ID = "73581A7B4F414A2C9059C83CE7BE97BF";

  /** One template descriptor: role id, English name, and its 2-window smoke set. */
  private static final TemplateRole[] TEMPLATES = {
      new TemplateRole(FINANCE_ROLE_ID, "Finance",
          new String[] { "94EAA455D2644E04AB25D93BE5157B6D", "E547CE89D4C04429B6340FFA44E70716" }),
      new TemplateRole(SALES_ROLE_ID, "Sales",
          new String[] { "143", "123" }),
      new TemplateRole(PURCHASING_ROLE_ID, "Purchasing",
          new String[] { "181", "140" }),
      new TemplateRole(INVENTORY_ROLE_ID, "Inventory",
          new String[] { "184", "139" }),
  };

  @Override
  public void execute() {
    try {
      ConnectionProvider cp = getConnectionProvider();
      for (TemplateRole template : TEMPLATES) {
        ensureRole(cp, template);
        for (String windowId : template.smokeWindowIds) {
          ensureWindowAccess(cp, template.roleId, windowId);
        }
      }
    } catch (Exception e) {
      handleError(e);
    }
  }

  /**
   * Inserts the system-level template role for {@code template} unless a role with that exact
   * ID already exists (the ID is literal/fixed, so a re-run's own previous insert is what this
   * guard normally matches — not a name lookup, which would be a weaker guard against a
   * same-named row created some other way).
   */
  private void ensureRole(ConnectionProvider cp, TemplateRole template) throws Exception {
    if (exists(cp, "SELECT 1 FROM AD_Role WHERE AD_Role_ID = ?", template.roleId)) {
      return;
    }
    String sql = "INSERT INTO AD_Role (AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, "
        + "Created, CreatedBy, Updated, UpdatedBy, Name, Description, UserLevel, IsManual, "
        + "Is_Client_Admin, IsAdvanced, IsRestrictBackend, IsPortal, IsPortalAdmin, "
        + "IsWebServiceEnabled, IsTemplate, EM_ETGO_Show_Acct_Fields) "
        + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, 'Y', 'N', 'N', 'N', 'N', 'N', 'N', "
        + "'Y', 'N')";
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      ps.setString(1, template.roleId);
      ps.setString(2, SYSTEM_CLIENT_ID);
      ps.setString(3, STAR_ORG_ID);
      ps.setString(4, SYSTEM_ADMIN_USER_ID);
      ps.setString(5, SYSTEM_ADMIN_USER_ID);
      ps.setString(6, template.name);
      ps.setString(7, "System-level template role (ETP-4852) — compose per-user personal roles "
          + "by inheriting from this template, never edit directly.");
      ps.setString(8, USER_LEVEL);
      ps.executeUpdate();
    }
  }

  /**
   * Inserts one smoke-test {@code AD_Window_Access} row for {@code roleId}/{@code windowId}
   * unless an active row already exists — the guard a real re-run relies on, since the window
   * ids are stable/shared (core-owned, {@code AD_Client_ID = '0'}) but not tied to this script's
   * own literal role IDs the way the role insert above is.
   */
  private void ensureWindowAccess(ConnectionProvider cp, String roleId, String windowId)
      throws Exception {
    if (exists(cp,
        "SELECT 1 FROM AD_Window_Access WHERE AD_Role_ID = ? AND AD_Window_ID = ? "
            + "AND IsActive = 'Y'",
        roleId, windowId)) {
      return;
    }
    String sql = "INSERT INTO AD_Window_Access (AD_Window_Access_ID, AD_Client_ID, AD_Org_ID, "
        + "IsActive, Created, CreatedBy, Updated, UpdatedBy, AD_Role_ID, AD_Window_ID, "
        + "IsReadWrite) VALUES (get_uuid(), ?, ?, 'Y', now(), ?, now(), ?, ?, ?, 'Y')";
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      ps.setString(1, SYSTEM_CLIENT_ID);
      ps.setString(2, STAR_ORG_ID);
      ps.setString(3, SYSTEM_ADMIN_USER_ID);
      ps.setString(4, SYSTEM_ADMIN_USER_ID);
      ps.setString(5, roleId);
      ps.setString(6, windowId);
      ps.executeUpdate();
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

  /** One template role's ID, English name, and 2-window smoke-test access set. */
  private static final class TemplateRole {
    final String roleId;
    final String name;
    final String[] smokeWindowIds;

    TemplateRole(String roleId, String name, String[] smokeWindowIds) {
      this.roleId = roleId;
      this.name = name;
      this.smokeWindowIds = smokeWindowIds;
    }
  }
}
