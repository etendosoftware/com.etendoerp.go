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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.webhooks;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.roles.SystemRoleTemplates;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.ReportAccessCatalog;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that returns, for an admin/client-admin caller, the 4 SYSTEM-LEVEL role templates
 * (Finance/Sales/Purchasing/Inventory, {@link SystemRoleTemplates}, {@code AD_Client_ID = '0'})
 * available to compose a personal role from — the read path {@code SFRolesOverview} cannot serve
 * (see below), and the ETP-4906 fix for the "no template role should be at client level, only at
 * system level" architecture target ({@code docs/plans/2026-08-14-etp-4906-multi-role-user-
 * assignment.md}, "Manual QA Feedback Round 2" finding 2).
 *
 * <p><b>Why not {@code SFRolesOverview}?</b> That webhook is hard-scoped to the CALLING tenant's
 * own client (resolves the 4 fixed role NAMES + the client-admin role WITHIN
 * {@code currentRole.getClient()}) — by design, for its own ETP-4513 "Configuración &gt; Roles"
 * page. Once a tenant deactivates (or never had) its own per-client copies of these roles, that
 * query legitimately returns nothing for them, which is correct FOR THAT PAGE but leaves nothing
 * for the multi-role assignment UI to offer. This webhook resolves the SAME 4 role names, but
 * always at the system client ({@code AD_Client_ID = '0'}), via the fixed ids in
 * {@link SystemRoleTemplates} — never the caller's own client, and never any other template a
 * tenant might additionally have created for itself.</p>
 *
 * <p>Deliberately omits {@code userCount} and any client-admin row: this endpoint's only
 * consumer is "which template roles can I compose from", not "give me an aggregate view of my
 * tenant's role usage" ({@code SFRolesOverview}'s job) — there is no client-admin role at system
 * level to report on either, since {@link SystemRoleTemplates}'s own class javadoc explicitly
 * excludes the client-level "Admin" role from the template set.</p>
 *
 * <p><b>Opt-in {@code matrix} + {@code reportsMatrix} (ETP-5485).</b> With {@code
 * ?includeMatrix=true} the response also carries the window × role {@code matrix} and the
 * Informes {@code reportsMatrix}, with one {@code access} column per template role id. Both are
 * built by {@link RoleAccessMatrix} — the same builder {@code SFRolesOverview} uses — so the User
 * window's "Roles del usuario" tab and "Configuración &gt; Roles" always list the same rows
 * (including the ETP-5071 proxy rows "Modelos Fiscales" / "Documentos no contabilizados", which
 * that tab was missing while it rebuilt rows on its own from {@code windows[]}). Opt-in because
 * the other callers of this endpoint (the Users grid role chips, the assign-templates control)
 * only need {@code roles}, and the matrix costs a category query plus the proxy-access queries
 * per role. Without the parameter the response is exactly as before.</p>
 *
 * <p>The per-role {@code windows[]} deliberately keeps its original window set (every active
 * {@code SPEC_TYPE = 'W'} window, NOT minus {@code RoleAccessMatrix}'s UI exclusions), so its
 * consumers see no change; no consumer renders rows from it.</p>
 *
 * <p>The current role is captured once, at the very top of {@link #get(Map, Map)}, before
 * {@link OBContext#setAdminMode()} is entered — the same convention every sibling webhook in this
 * package follows and for the same reason: access decisions must always be made against the role
 * actually resolved for this request, never against whatever the ambient OBContext happens to
 * expose once admin mode is active.</p>
 *
 * GET /sws/neo/systemroletemplates — reached ONLY through the NEO pseudo-spec bridge
 * (see {@code docs/neo-headless.md} §4.10/§4.11); no legacy {@code /webhooks/*} path, same as
 * {@code SFAssignUserRoles}/{@code SFUserRoleAssignments}.
 */
public class SFSystemRoleTemplates extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFSystemRoleTemplates.class);

  /** JSON key for the roles array in the response. */
  private static final String ROLES = "roles";

  /** JSON key for a role's id. */
  private static final String ID = "id";

  /** JSON key for a role's display name. */
  private static final String NAME = "name";

  /** JSON key for a role's assigned-windows array. */
  private static final String WINDOWS = "windows";

  /**
   * ETP-5402 — JSON key for a role's assigned-reports array (the Informes subsection), parallel
   * to {@link #WINDOWS}. Part of the DEFAULT response shape, which callers that list or pick
   * template roles still consume ({@code RoleChipsCell.jsx}, {@code AssignTemplateRolesControl.jsx}
   * in {@code etendo_schema_forge}), so it stays even though it is no longer the matrix source.
   *
   * <p>Since ETP-5485 the User window's "Roles del usuario" tab does NOT read per-role
   * {@code windows}/{@code reports} for its cells: it requests {@link #INCLUDE_MATRIX_PARAM} and
   * renders {@link #MATRIX}/{@link #REPORTS_MATRIX}, built by the same {@code RoleAccessMatrix}
   * as {@code SFRolesOverview}. Keep that in mind before trimming or extending either array.
   */
  private static final String REPORTS = "reports";

  /** ETP-5485 — query parameter that opts into {@link #MATRIX} + {@link #REPORTS_MATRIX}. */
  static final String INCLUDE_MATRIX_PARAM = "includeMatrix";

  /** ETP-5485 — JSON key for the window × template-role matrix ({@link RoleAccessMatrix}). */
  private static final String MATRIX = "matrix";

  /** ETP-5485 — JSON key for the Informes × template-role matrix ({@link RoleAccessMatrix}). */
  private static final String REPORTS_MATRIX = "reportsMatrix";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    // Capture the real current role BEFORE entering admin mode — see the class javadoc and
    // SFRolesOverview's identical convention for why.
    Role currentRole = NeoAccessHelper.resolveCurrentRole();

    if (currentRole == null || !NeoAccessHelper.isAdminOrClientAdmin(currentRole)) {
      responseVars.put("result", emptyResult().toString());
      return;
    }

    OBContext.setAdminMode();
    try {
      boolean includeMatrix = parameter != null
          && Boolean.parseBoolean(parameter.get(INCLUDE_MATRIX_PARAM));
      JSONObject result = buildSystemRoleTemplatesOverview(includeMatrix);
      responseVars.put("result", result.toString());
    } catch (Exception e) {
      log.error("Error in SFSystemRoleTemplates", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Builds the empty result used when the current request has no role assigned, or has a role
   * that is not admin/client-admin.
   */
  private static JSONObject emptyResult() {
    try {
      JSONObject result = new JSONObject();
      result.put(ROLES, new JSONArray());
      return result;
    } catch (JSONException e) {
      // JSONObject#put never throws for a non-null key; unreachable in practice.
      throw new IllegalStateException("Unable to build empty system-role-templates result", e);
    }
  }

  /**
   * Builds the {@code roles} array for the 4 fixed system-level templates, in
   * {@link SystemRoleTemplates#byName()}'s own Finance/Sales/Purchasing/Inventory order — plus,
   * when {@code includeMatrix} (ETP-5485), the shared {@code matrix} / {@code reportsMatrix}. A
   * template whose id no longer resolves to an active {@code Role} (deleted or deactivated —
   * not expected in practice, but not this webhook's job to prevent either) is skipped rather
   * than surfaced as an error, the same "degrade gracefully" convention every sibling webhook in
   * this package follows; it is then absent from the matrix columns too.
   */
  private JSONObject buildSystemRoleTemplatesOverview(boolean includeMatrix) throws JSONException {
    Map<String, Window> allGoWindowsById = RoleAccessMatrix.resolveAllActiveEtendoGoWindowsById();
    // In-memory filter of the same query result — the matrix rows use the UI window set.
    Map<String, Window> uiGoWindowsById = RoleAccessMatrix.withoutUiExcluded(allGoWindowsById);

    JSONArray roles = new JSONArray();
    Map<String, Map<String, String>> tierMapsByRoleId = new LinkedHashMap<>();
    Map<String, Map<String, String>> reportTierMapsByRoleId = new LinkedHashMap<>();
    for (String roleId : SystemRoleTemplates.byName().values()) {
      Role role = OBDal.getInstance().get(Role.class, roleId);
      if (role == null || !Boolean.TRUE.equals(role.isActive())) {
        continue;
      }
      // Windows are resolved before reports, as before ETP-5485 — keeps the per-role query order.
      JSONObject roleJson = buildRoleJson(role, allGoWindowsById);
      Map<String, String> reportTiers = ReportAccessCatalog.resolveTierMap(role);
      roleJson.put(REPORTS, ReportAccessCatalog.reportsJson(reportTiers));
      roles.put(roleJson);
      if (includeMatrix) {
        tierMapsByRoleId.put(role.getId(), RoleAccessMatrix.resolveTierMap(role, uiGoWindowsById));
        reportTierMapsByRoleId.put(role.getId(), reportTiers);
      }
    }

    JSONObject result = new JSONObject();
    result.put(ROLES, roles);
    if (includeMatrix) {
      result.put(MATRIX, RoleAccessMatrix.buildMatrix(uiGoWindowsById, tierMapsByRoleId));
      result.put(REPORTS_MATRIX, RoleAccessMatrix.buildReportsMatrix(reportTierMapsByRoleId));
    }
    return result;
  }

  /**
   * Builds a single role's JSON entry: id, name and its windows array (the caller adds the
   * ETP-5402 Informes {@code reports} array). No {@code userCount}, no {@code isClientAdmin} —
   * see the class javadoc for why.
   *
   * <p>{@code windows} is {@code role}'s active {@code AD_Window_Access} rows intersected with
   * {@code goWindowsById} (the UNEXCLUDED set — see the class javadoc), via the shared {@link
   * RoleAccessMatrix#windowsJson(Map, Map)}.</p>
   */
  private JSONObject buildRoleJson(Role role, Map<String, Window> goWindowsById)
      throws JSONException {
    JSONObject roleJson = new JSONObject();
    roleJson.put(ID, role.getId());
    roleJson.put(NAME, role.getName());
    Map<String, String> windowTiers = RoleAccessMatrix.resolveWindowTierMap(role,
        goWindowsById.keySet());
    roleJson.put(WINDOWS, RoleAccessMatrix.windowsJson(windowTiers, goWindowsById));
    return roleJson;
  }
}
