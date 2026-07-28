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
package com.etendoerp.go.schemaforge.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the {@code user} spec. Two independent post-hook concerns share this one class
 * because only one {@code JAVA_QUALIFIER} can be registered per {@code ETGO_SF_ENTITY} row (see
 * {@code docs/neo-headless-extensibility.md} §2.2), and this entity's qualifier was already
 * claimed by the role-sync concern below (ETP-4512):
 *
 * <ol>
 *   <li><b>Role sync (ETP-4512):</b> keeps {@code AD_User_Roles} in sync with
 *   {@code AD_User.Default_Ad_Role_ID}. The Go SPA's "assign role to user" UX (Configuración
 *   &gt; Usuarios) only ever offers a single-role dropdown backed by {@code Default_Ad_Role_ID}
 *   — see {@code AssignRoleControl.jsx} in {@code etendo_schema_forge}, which deliberately
 *   sources its options from the unrestricted {@code userRoles.role} selector rather than this
 *   field's own {@code Default_Ad_Role_ID} selector (which is filtered to roles the user
 *   already has, making it useless for assigning a *new* role). Real login/window-access checks
 *   read {@code AD_User_Roles}, not {@code Default_Ad_Role_ID}, so this handler is the only
 *   place that writes {@code AD_User_Roles} for a {@code user} save, enforcing at most one
 *   active row per user: every save deletes any existing row(s) for that user and inserts
 *   exactly one new row for the role currently set in {@code Default_Ad_Role_ID} (or leaves the
 *   user role-less if that field is cleared). Scoped to {@code PUT}/{@code PATCH} only —
 *   editing an existing user. Admin-initiated user <em>creation</em> is a separate concern
 *   (ETP-4602, not yet implemented), and {@link NeoContext#getRecordId()} is not reliably
 *   populated on {@code POST}. Best-effort, secondary side effect: the {@link User} has already
 *   been saved by the time {@link #afterHandle(NeoContext)} runs, so a failure here must never
 *   fail the parent request — any exception is logged and swallowed, and {@code null} is
 *   returned so the original CRUD response is kept untouched.</li>
 *
 *   <li><b>Bootstrap-user hiding (2026-07-27):</b> the "Admin" ({@code AD_User_ID='100'}) and
 *   "System" ({@code AD_User_ID='0'}) accounts belong to the System client ({@code
 *   AD_Client_ID='0'}), which Openbravo's readable-client security model always treats as
 *   visible to every tenant — so they leaked into every tenant's "Usuarios" grid even though
 *   they are internal bootstrap accounts, never real assignable users. Filtered out of every
 *   GET-list response for this entity, unconditionally (no tenant needs to see or manage them
 *   through the Go SPA — the native classic backend remains the place for that kind of
 *   maintenance).</li>
 * </ol>
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2.
 */
@Named("user")
public class UserRoleAssignmentHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(UserRoleAssignmentHandler.class);

  private static final String METHOD_GET = "GET";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  /** {@code AD_User_ID} of the System-client "Admin" and "System" bootstrap accounts. */
  private static final Set<String> HIDDEN_BOOTSTRAP_USER_IDS = Set.of("0", "100");

  private static final String FIELD_TOTAL_ROWS = "totalRows";
  private static final String FIELD_ID = "id";

  /** No pre-hook behavior: this handler only reacts after the default service ran. */
  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  /**
   * Post-hook dispatch: filters bootstrap users out of a {@code user} list GET, or syncs
   * {@code AD_User_Roles} after a {@code user} update. See the class javadoc for why both
   * concerns live in one handler.
   *
   * @return always {@code null} — both concerns mutate {@code context.getPreviousResult()}'s
   *     body in place (or leave it untouched) rather than replacing the response.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    if (METHOD_GET.equalsIgnoreCase(context.getHttpMethod()) && context.getRecordId() == null) {
      hideBootstrapUsers(context);
      return null;
    }
    return syncRoleAfterUpdate(context);
  }

  /**
   * Removes the "Admin"/"System" bootstrap-account rows (see class javadoc) from a {@code user}
   * list GET response, adjusting {@code totalRows} to match. A single-record fetch has no
   * {@code data} array (it's a lone JSON object instead), so {@code optJSONArray} naturally
   * no-ops there — this only ever touches list responses.
   */
  private void hideBootstrapUsers(NeoContext context) {
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONObject body = previousResult != null ? previousResult.getBody() : null;
      JSONObject inner = body != null ? body.optJSONObject(JsonConstants.RESPONSE_RESPONSE) : null;
      JSONArray data = inner != null ? inner.optJSONArray(JsonConstants.RESPONSE_DATA) : null;
      if (data == null) {
        return;
      }
      JSONArray filtered = new JSONArray();
      int removed = 0;
      for (int i = 0; i < data.length(); i++) {
        JSONObject row = data.optJSONObject(i);
        String id = row != null ? row.optString(FIELD_ID, null) : null;
        if (id != null && HIDDEN_BOOTSTRAP_USER_IDS.contains(id)) {
          removed++;
          continue;
        }
        filtered.put(row);
      }
      if (removed == 0) {
        return;
      }
      inner.put(JsonConstants.RESPONSE_DATA, filtered);
      int totalRows = inner.optInt(FIELD_TOTAL_ROWS, -1);
      if (totalRows >= 0) {
        inner.put(FIELD_TOTAL_ROWS, Math.max(0, totalRows - removed));
      }
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.hideBootstrapUsers error: {}", e.getMessage(), e);
    }
  }

  /**
   * On a successful update of the {@code user} entity, ensures {@code AD_User_Roles} has
   * exactly one active row matching the saved {@code Default_Ad_Role_ID} (or zero rows if it
   * was cleared). See concern (1) in the class javadoc.
   *
   * @return always {@code null} — this is a side effect, never a response replacement.
   */
  private NeoResponse syncRoleAfterUpdate(NeoContext context) {
    String method = context.getHttpMethod();
    if (!METHOD_PUT.equalsIgnoreCase(method) && !METHOD_PATCH.equalsIgnoreCase(method)) {
      return null;
    }
    String userId = context.getRecordId();
    if (userId == null) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        syncUserRole(userId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.afterHandle error for user {}: {}", userId,
          e.getMessage(), e);
    }
    return null;
  }

  private void syncUserRole(String userId) {
    User user = OBDal.getInstance().get(User.class, userId);
    if (user == null) {
      return;
    }
    Role targetRole = user.getDefaultRole();

    OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_USERCONTACT, user));
    List<UserRoles> existing = criteria.list();

    boolean alreadyInSync = existing.size() == 1 && targetRole != null
        && targetRole.getId().equals(existing.get(0).getRole().getId());
    if (alreadyInSync) {
      return;
    }

    for (UserRoles row : new ArrayList<>(existing)) {
      OBDal.getInstance().remove(row);
    }
    OBDal.getInstance().flush();

    if (targetRole == null) {
      log.info("User {} has no default role set; cleared all AD_User_Roles rows.", userId);
      return;
    }

    UserRoles newRow = OBProvider.getInstance().get(UserRoles.class);
    newRow.setNewOBObject(true);
    newRow.setClient(targetRole.getClient());
    newRow.setOrganization(targetRole.getOrganization());
    newRow.setUserContact(user);
    newRow.setRole(targetRole);
    newRow.setRoleAdmin(false);
    OBDal.getInstance().save(newRow);
    OBDal.getInstance().flush();
    log.info("Assigned role {} to user {} via AD_User_Roles.", targetRole.getId(), userId);
  }
}
