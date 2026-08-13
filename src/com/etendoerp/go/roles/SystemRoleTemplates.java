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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ETP-4852 — the fixed-role template {@code AD_Role} rows, moved from being duplicated per
 * client to a single canonical, system-owned ({@code AD_Client_ID = '0'}) row per role, marked
 * {@code IsTemplate = 'Y'} / {@code IsManual = 'Y'} (core's {@code ad_role_manualtemplate_check}
 * constraint requires both together).
 *
 * <p>These four IDs are seeded once, idempotently, by
 * {@code EnsureSystemRoleTemplatesScript} (a core {@code ModuleScript}, run automatically by
 * {@code update.database} — see {@code src-util/modulescript/}). They are declared here, not
 * only in that script, because the script's source root ({@code src-util/modulescript/}) is
 * intentionally self-contained (mirrors every other {@code ModuleScript} in this codebase, e.g.
 * {@code com.etendoerp.sif.general}'s scripts, none of which import from their module's own
 * {@code src/} tree) — so the same four literal IDs are duplicated in both files by design, not
 * an oversight. Keep them in sync if a template role is ever recreated with a new ID.</p>
 *
 * <p><b>Deliberately excluded:</b> the client-level "Admin" role ({@code AD_Role.is_client_admin
 * = 'Y'}, auto-created per tenant by core's {@code InitialClientSetup}) is NOT one of these
 * templates — the ticket explicitly keeps it client-level. {@link
 * com.etendoerp.go.roles.UserRoleCompositionService} never touches it.</p>
 *
 * <p><b>Not the only valid templates.</b> {@link UserRoleCompositionService} accepts ANY active
 * role with {@code IsTemplate = 'Y'} as a composition source, not only these four — this class
 * exists to seed the well-known starting set, not to gate what a user may compose from. Future
 * templates (e.g. once ETP-4878 populates the full 48-window matrix, or a tenant-specific
 * template is added later) need no change here.</p>
 */
public final class SystemRoleTemplates {

  public static final String FINANCE_ROLE_ID = "B88A34B5D1874F8685FA6F3C3A609412";
  public static final String SALES_ROLE_ID = "15ECC46CFBD74CF3A76D1F4DC8BA9F80";
  public static final String PURCHASING_ROLE_ID = "5E279F5102F9410F9B8CCBA424741F46";
  public static final String INVENTORY_ROLE_ID = "73581A7B4F414A2C9059C83CE7BE97BF";

  /** {@code AD_Role.UserLevel} shared by every fixed role in this fleet (client + org). */
  public static final String FIXED_ROLE_USER_LEVEL = "  O";

  private SystemRoleTemplates() {
    // constants holder
  }

  /**
   * The four seeded template IDs, keyed by their (English) role name — insertion order matches
   * the ticket's own enumeration (Finance, Sales, Purchasing, Inventory).
   *
   * @return a fresh, mutable {@link LinkedHashMap} from role name to {@code AD_Role_ID}, in
   *     Finance/Sales/Purchasing/Inventory order
   */
  public static Map<String, String> byName() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("Finance", FINANCE_ROLE_ID);
    map.put("Sales", SALES_ROLE_ID);
    map.put("Purchasing", PURCHASING_ROLE_ID);
    map.put("Inventory", INVENTORY_ROLE_ID);
    return map;
  }
}
