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
import java.util.Arrays;
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
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that returns, for an admin caller, an aggregate overview of the CALLING TENANT's 5
 * fixed roles (ETP-4513 — "Configuración &gt; Roles"): each role's display name, raw AD
 * description, count of assigned users ({@code AD_User_Roles}), and the list of Etendo GO
 * windows it can reach ({@code AD_Window_Access}, intersected with the windows Etendo GO
 * actually exposes today — see {@link #resolveActiveEtendoGoWindowIds()}).
 *
 * <p>Unlike {@code SFWindowAccessMap}, which answers "what can the CURRENT caller's own role
 * reach" for any authenticated role, this endpoint is a cross-role aggregate: it always returns
 * data for all 5 of the caller's OWN tenant's roles, regardless of which one the caller happens
 * to be using. That is exactly why it is gated to admin/client-admin callers only
 * ({@link NeoAccessHelper#isAdminOrClientAdmin(Role)}) — a regular role has no legitimate reason
 * to see every other role's user count and window list. Anyone else (including a request with
 * no role assigned) gets an empty {@code roles} array, mirroring {@link SFListMenu}'s "deny
 * silently, don't 403" convention for this webhook family.</p>
 *
 * <p><b>Tenant-relative role resolution (fixed 2026-07-27, was GOClient-hardcoded).</b> The
 * original implementation always returned GOClient's 5 specific {@code AD_Role_ID}s, regardless
 * of the caller's own client — harmless while GOClient was the only tenant with these roles at
 * all, but broken the moment ETP-4515/4516 (Phase 7) gave every tenant its own equivalent role
 * set: a non-GOClient admin would see GOClient's role NAMES (their ids happened to still resolve
 * via a direct {@code OBDal.get()} by PK, which bypasses client filtering) but EMPTY user
 * counts/windows, because the dependent {@code UserRoles}/{@code WindowAccess} queries silently
 * filtered out GOClient's rows as unreadable from the caller's own (different) client context —
 * a live, reproducible bug (RolesPresa tenant, 2026-07-27). Now resolves the 4 fixed-name roles
 * (Finance/Sales/Purchasing/Inventory) plus whichever role has {@code is_client_admin='Y'} WITHIN
 * {@code currentRole.getClient()} — the same "resolve by name + is_client_admin, scoped to
 * :client_id" approach used by the now-retired-and-deleted {@code OnboardingRoleProvisioningService}
 * (ETP-4852) and {@code R16-tenant-roles-and-webhook-access.sql} in {@code etendo_schema_forge}. Every
 * OBCriteria below explicitly disables readable-client/org filtering (matching every sibling
 * webhook in this package) so cross-tenant filtering can never silently empty a same-tenant
 * result again.</p>
 *
 * <p><b>{@code rawDescription} is NOT display copy.</b> {@code AD_Role.description} is
 * boilerplate for 4 of the 5 GOClient roles today ({@code "*** Please, do not edit this role.
 * Use Copy Record instead ***"}) — this backend has no i18n awareness, so it cannot produce
 * user-facing copy itself. The field is returned only as a raw/debug fallback; the frontend
 * (`RolesOverviewPage.jsx`) maps the 4 fixed role NAMES (and the {@code isClientAdmin} flag for
 * the 5th) to curated, i18n-keyed copy instead of rendering this field.</p>
 *
 * <p>The current role is captured once, at the very top of {@link #get(Map, Map)}, before
 * {@link OBContext#setAdminMode()} is entered — the same convention {@link SFListMenu} follows
 * and for the same reason: access decisions must always be made against the role actually
 * resolved for this request, never against whatever the ambient OBContext happens to expose
 * once admin mode is active.</p>
 *
 * GET /webhooks/SFRolesOverview
 */
public class SFRolesOverview extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFRolesOverview.class);

  /** JSON key for the roles array in the response. */
  private static final String ROLES = "roles";

  /** JSON key for a role's id. */
  private static final String ID = "id";

  /** JSON key for a role's display name. */
  private static final String NAME = "name";

  /**
   * JSON key for a role's raw {@code AD_Role.description} — debug/fallback only, NOT display
   * copy. See the class javadoc for why.
   */
  private static final String RAW_DESCRIPTION = "rawDescription";

  /**
   * JSON key marking the tenant's client-admin role — its NAME varies per tenant (e.g.
   * "RolesPresa Admin" vs "GOClient Admin"), so the frontend needs this flag to render it with
   * generic "Administrator" copy instead of the literal AD_Role.name.
   */
  private static final String IS_CLIENT_ADMIN = "isClientAdmin";

  /** JSON key for a role's assigned-user count. */
  private static final String USER_COUNT = "userCount";

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

  /**
   * The 4 fixed non-admin role names every tenant gets (ETP-4515/4516), in the display order
   * this endpoint returns them (after the client-admin role, which always sorts first). Mirrors
   * the now-deleted {@code OnboardingRoleProvisioningService.ROLE_NAMES} / R16's role list in
   * {@code etendo_schema_forge} — keep in lockstep.
   */
  private static final String[] FIXED_ROLE_NAMES = { "Finance", "Sales", "Purchasing", "Inventory" };

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    // Capture the real current role BEFORE entering admin mode — see the class javadoc and
    // SFListMenu's identical convention for why: access decisions must always be made against
    // the role actually resolved for this request, never against whatever the ambient
    // OBContext happens to expose once admin mode is active.
    Role currentRole = NeoAccessHelper.resolveCurrentRole();

    if (currentRole == null || !NeoAccessHelper.isAdminOrClientAdmin(currentRole)) {
      responseVars.put("result", emptyResult().toString());
      return;
    }

    OBContext.setAdminMode();
    try {
      JSONObject result = buildRolesOverview(currentRole.getClient().getId());
      responseVars.put("result", result.toString());
    } catch (Exception e) {
      log.error("Error in SFRolesOverview", e);
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
      throw new IllegalStateException("Unable to build empty roles-overview result", e);
    }
  }

  /**
   * Builds the {@code roles} array for {@code clientId}'s own 5 fixed roles: the client-admin
   * role first, then the 4 named roles in {@link #FIXED_ROLE_NAMES} order.
   */
  private JSONObject buildRolesOverview(String clientId) throws JSONException {
    Set<String> goWindowIds = resolveActiveEtendoGoWindowIds();
    List<Role> tenantRoles = resolveTenantRoles(clientId);

    JSONArray roles = new JSONArray();
    for (Role role : tenantRoles) {
      roles.put(buildRoleJson(role, goWindowIds));
    }

    JSONObject result = new JSONObject();
    result.put(ROLES, roles);
    return result;
  }

  /**
   * Resolves {@code clientId}'s own client-admin role plus its 4 {@link #FIXED_ROLE_NAMES}
   * roles, ordered admin-first then {@link #FIXED_ROLE_NAMES} order. Scoped strictly to
   * {@code clientId} — a tenant's own role NAMES (not GOClient's specific ids) are what's
   * universal across tenants (see the now-deleted {@code OnboardingRoleProvisioningService}),
   * so this is the same resolution strategy as the rest of the role-provisioning feature.
   */
  @SuppressWarnings("unchecked")
  private List<Role> resolveTenantRoles(String clientId) {
    OBCriteria<Role> criteria = OBDal.getInstance().createCriteria(Role.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Role.PROPERTY_CLIENT + ".id", clientId));
    criteria.add(Restrictions.eq(Role.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.or(
        Restrictions.eq(Role.PROPERTY_CLIENTADMIN, true),
        Restrictions.in(Role.PROPERTY_NAME, (Object[]) FIXED_ROLE_NAMES)));

    List<String> fixedNameOrder = Arrays.asList(FIXED_ROLE_NAMES);
    List<Role> roles = new ArrayList<>((List<Role>) criteria.list());
    roles.sort((a, b) -> {
      boolean aAdmin = Boolean.TRUE.equals(a.isClientAdmin());
      boolean bAdmin = Boolean.TRUE.equals(b.isClientAdmin());
      if (aAdmin != bAdmin) {
        return aAdmin ? -1 : 1;
      }
      return Integer.compare(fixedNameOrder.indexOf(a.getName()), fixedNameOrder.indexOf(b.getName()));
    });
    return roles;
  }

  /**
   * Builds a single role's JSON entry.
   */
  private JSONObject buildRoleJson(Role role, Set<String> goWindowIds) throws JSONException {
    JSONObject roleJson = new JSONObject();
    roleJson.put(ID, role.getId());
    roleJson.put(NAME, role.getName());
    roleJson.put(RAW_DESCRIPTION, role.getDescription());
    roleJson.put(IS_CLIENT_ADMIN, Boolean.TRUE.equals(role.isClientAdmin()));
    roleJson.put(USER_COUNT, countActiveUsers(role));
    roleJson.put(WINDOWS, buildWindowsJson(role, goWindowIds));
    return roleJson;
  }

  /**
   * Counts the distinct users with an active {@code AD_User_Roles} row for {@code role}.
   */
  @SuppressWarnings("unchecked")
  private int countActiveUsers(Role role) {
    OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_ACTIVE, true));

    Set<String> userIds = new LinkedHashSet<>();
    for (UserRoles userRole : (List<UserRoles>) criteria.list()) {
      if (userRole.getUserContact() != null) {
        userIds.add(userRole.getUserContact().getId());
      }
    }
    return userIds.size();
  }

  /**
   * Builds the {@code windows} array for {@code role}: every active {@code AD_Window_Access}
   * row it has, intersected with {@code goWindowIds} — a role may hold native Etendo
   * window-access rows for windows Etendo GO never exposes (e.g. inherited/legacy grants), and
   * those must not leak into this "assigned windows" view. Sorted by window name.
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
   * {@code ETGO_SF_SPEC} — i.e. every window Etendo GO actually exposes today. Used to keep
   * {@link #buildWindowsJson(Role, Set)} scoped to Etendo GO's own window set, rather than every
   * native {@code AD_Window_Access} row a role happens to carry (a GOClient role, especially
   * the admin one, commonly also holds access to native-only Etendo windows that Etendo GO
   * never surfaces — those are noise for this "assigned windows" view).
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
