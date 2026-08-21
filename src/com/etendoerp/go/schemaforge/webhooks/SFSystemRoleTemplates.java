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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.roles.SystemRoleTemplates;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
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

  /** JSON key for a window entry's access tier. */
  private static final String TIER = "tier";

  /** Access-tier value for a window with full (read+write) access. */
  private static final String FULL = "full";

  /** Access-tier value for a window with read-only access. */
  private static final String READ_ONLY = "read-only";

  /** {@code ETGO_SF_SPEC.SPEC_TYPE} value identifying a window/CRUD spec. */
  private static final String SPEC_TYPE_WINDOW = "W";

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
      JSONObject result = buildSystemRoleTemplatesOverview();
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
   * {@link SystemRoleTemplates#byName()}'s own Finance/Sales/Purchasing/Inventory order. A
   * template whose id no longer resolves to an active {@code Role} (deleted or deactivated —
   * not expected in practice, but not this webhook's job to prevent either) is skipped rather
   * than surfaced as an error, the same "degrade gracefully" convention every sibling webhook in
   * this package follows.
   */
  private JSONObject buildSystemRoleTemplatesOverview() throws JSONException {
    Set<String> goWindowIds = resolveActiveEtendoGoWindowIds();

    JSONArray roles = new JSONArray();
    for (String roleId : SystemRoleTemplates.byName().values()) {
      Role role = OBDal.getInstance().get(Role.class, roleId);
      if (role == null || !Boolean.TRUE.equals(role.isActive())) {
        continue;
      }
      roles.put(buildRoleJson(role, goWindowIds));
    }

    JSONObject result = new JSONObject();
    result.put(ROLES, roles);
    return result;
  }

  /**
   * Builds a single role's JSON entry: id, name, and its windows array. No {@code userCount},
   * no {@code isClientAdmin} — see the class javadoc for why.
   */
  private JSONObject buildRoleJson(Role role, Set<String> goWindowIds) throws JSONException {
    JSONObject roleJson = new JSONObject();
    roleJson.put(ID, role.getId());
    roleJson.put(NAME, role.getName());
    roleJson.put(WINDOWS, buildWindowsJson(role, goWindowIds));
    return roleJson;
  }

  /**
   * Builds the {@code windows} array for {@code role}: every active {@code AD_Window_Access} row
   * it has, intersected with {@code goWindowIds} — mirrors {@code SFRolesOverview}'s identical
   * method. Client/organization filtering is explicitly disabled: these roles live at the system
   * client ({@code AD_Client_ID = '0'}), which a non-system caller's ambient readable-client set
   * would otherwise silently filter out.
   */
  @SuppressWarnings("unchecked")
  private JSONArray buildWindowsJson(Role role, Set<String> goWindowIds) throws JSONException {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));

    List<JSONObject> windowJsons = new ArrayList<>();
    for (WindowAccess access : (List<WindowAccess>) criteria.list()) {
      Window window = access.getWindow();
      if (window == null || !goWindowIds.contains(window.getId())) {
        continue;
      }
      JSONObject windowJson = new JSONObject();
      windowJson.put(ID, window.getId());
      windowJson.put(NAME, window.getName());
      windowJson.put(TIER, Boolean.TRUE.equals(access.isEditableField()) ? FULL : READ_ONLY);
      windowJsons.add(windowJson);
    }

    windowJsons.sort((a, b) -> {
      try {
        return a.getString(NAME).compareToIgnoreCase(b.getString(NAME));
      } catch (JSONException e) {
        return 0;
      }
    });

    JSONArray windows = new JSONArray();
    for (JSONObject windowJson : windowJsons) {
      windows.put(windowJson);
    }
    return windows;
  }

  /**
   * Resolves every distinct {@code AD_Window} backing an active, {@code SPEC_TYPE = 'W'}
   * {@code ETGO_SF_SPEC} — i.e. every window Etendo GO actually exposes today. Mirrors
   * {@code SFRolesOverview}'s identical method (this is the 3rd copy of this query across
   * {@code SFRolesOverview}/{@code SFWindowAccessMap}/this class — not yet extracted to a shared
   * helper, same as the first 2; flagged, not required, per this ticket's own dispatch note).
   *
   * @return the distinct window IDs (insertion order)
   */
  @SuppressWarnings("unchecked")
  private Set<String> resolveActiveEtendoGoWindowIds() {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, SPEC_TYPE_WINDOW));

    Set<String> windowIds = new LinkedHashSet<>();
    for (SFSpec spec : (List<SFSpec>) criteria.list()) {
      Window window = spec.getADWindow();
      if (window != null) {
        windowIds.add(window.getId());
      }
    }
    return windowIds;
  }
}
