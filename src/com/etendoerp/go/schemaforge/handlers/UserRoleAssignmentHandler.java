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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
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
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.rest.CompanyInvitationService;
import com.etendoerp.go.rest.EtendoGoJwtSupport;
import com.etendoerp.go.schemaforge.AbstractSmartDeactivationHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.UserRoleSyncSupport;

/**
 * NeoHandler for the {@code user} spec. Three independent concerns share this one class because
 * only one {@code JAVA_QUALIFIER} can be registered per {@code ETGO_SF_ENTITY} row (see
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
 *   editing an existing user. Best-effort, secondary side effect: the {@link User} has already
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
 *
 *   <li><b>Admin-initiated user creation (ETP-4829/ETP-4830):</b> the admin-facing "create user"
 *   form never shows a username field (see {@code artifacts/user/decisions.json}'s create-only
 *   {@code username} override in {@code etendo_schema_forge}) — it is the email address for the
 *   first client and a client-suffixed username for later clients, matching the convention of
 *   {@code EtendoGoJwtDalHelper}/
 *   {@code EtendoGoJwtSupport} already rely on to link an {@code AD_User} row to its {@code
 *   etgo_account} row by matching value. The frontend never sends a {@code username}, but this
 *   is enforced server-side too (defense in depth): {@link #handle(NeoContext)} rewrites the
 *   {@code POST} request body's {@code username} to a normalized email-derived username
 *   (with the shared client suffix when needed)
 *   before the default CRUD create runs — reachable because {@link NeoContext#getRequestBody()}
 *   is the same mutable {@code JSONObject} the default service reads afterward (see {@code
 *   NeoServletSupport#handleWithHooks}). A blank/missing email is rejected with 400 before it
 *   ever reaches the DB's NOT NULL constraint, for a clearer error message. Once the {@code
 *   AD_User} is created, {@link #afterHandle(NeoContext)} reads the created record's {@code
 *   email} back out of the response body (POST's {@code recordId} is never populated on {@link
 *   NeoContext} — this is the one path that doesn't need it) and sends a company invitation via
 *   {@link CompanyInvitationService#createInvitationForNewlyCreatedUser}, the same
 *   invitation/token/email mechanism ETP-4894 built for company administrators inviting an
 *   existing user (dedup of an already-open invitation and throttling included). Admin role
 *   assignment happens independently, any time after creation, via {@code
 *   AssignTemplateRolesControl}'s own save — this is why the invitation intentionally skips the
 *   "invited user already has an active role" check {@link CompanyInvitationService#createInvitation}
 *   otherwise enforces. There is deliberately no eager {@code etgo_account} row created on this
 *   path any more (ETP-4830 superseded ETP-4829's pending/active bookkeeping): the invitation's
 *   {@code register-and-accept} flow is now the sole place an {@code etgo_account} gets created
 *   for an admin-created user, lazily, once the invitee actually accepts. Same best-effort
 *   contract as role sync: never fails the parent {@code AD_User} creation. The resulting {@code
 *   invitationStatus} (see {@link #attachInvitationStatus}) is surfaced back on every {@code
 *   user} GET so the frontend can render a "pending invite" badge.</li>
 *
 *   <li><b>Write-path guards on {@code PUT}/{@code PATCH} (ETP-4830 QA rejection cycle 1):</b>
 *   {@link #handle(NeoContext)} rejects two dangerous updates with a 400 BEFORE the default CRUD
 *   update ever runs:
 *   <ul>
 *     <li><i>Email immutability.</i> {@code decisions.json}'s {@code readOnlyLogicJs:
 *     "!!record.id"} on {@code email} is client-side only — {@code push-to-neo.js}'s {@code
 *     mapVisibility()} has no notion of a dynamic display-logic expression, so {@code
 *     NeoFieldFilter}'s {@code writableFields} still lets a direct PATCH change {@code email}
 *     after creation, desyncing it from {@code username}/the linked {@code etgo_account} (both
 *     keyed off the original email — see concern (3) above and {@code EtendoGoJwtDalHelper}/
 *     {@code EtendoGoJwtSupport}). Rejected unless the incoming value is byte-for-byte identical
 *     to the persisted one, so a client re-submitting its own unchanged form value is a no-op,
 *     not an error. Scoped narrowly to {@code email} on this window's write path — NOT a generic
 *     {@code readOnlyLogicJs} enforcement mechanism (the same gap exists elsewhere, e.g. {@code
 *     transactionDocument}, and is tracked separately).</li>
 *     <li><i>Self/last-admin lockout.</i> {@code NeoFieldFilter#forEntity} always adds {@code
 *     active} to {@code writable} "so toggles persist", with no guard of its own. Only evaluated
 *     when the request explicitly sets {@code active=false} ({@link
 *     AbstractSmartDeactivationHandler#isExplicitlyDeactivating}, the same helper other {@code
 *     active=false} guards in this module use — widened to {@code public static} for this reuse
 *     since this handler cannot itself extend that single-purpose base class). Rejects when the
 *     target record is the currently-authenticated user's own record (resolved from {@code
 *     context.getObContext().getUser()}, the same "who is making this request" pattern used
 *     elsewhere in this package), and separately rejects when the target user is the last
 *     remaining active {@code AD_User} holding an active client-admin {@code AD_Role} ({@code
 *     Role.isClientAdmin() == true}) for that client — see {@link #isLastActiveClientAdmin}.
 *     </li>
 *   </ul>
 *   Both guards fail CLOSED (surface a 500) on an unexpected error rather than silently falling
 *   through to the default CRUD update — same reasoning {@code AbstractSmartDeactivationHandler}'s
 *   own javadoc gives for its guard.</li>
 * </ol>
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2.
 */
@Named("user")
public class UserRoleAssignmentHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(UserRoleAssignmentHandler.class);

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  /** {@code AD_User_ID} of the System-client "Admin" and "System" bootstrap accounts. */
  private static final Set<String> HIDDEN_BOOTSTRAP_USER_IDS = Set.of("0", "100");

  private static final String FIELD_TOTAL_ROWS = "totalRows";
  private static final String FIELD_ID = "id";
  private static final String FIELD_USERNAME = "username";
  private static final String FIELD_EMAIL = "email";
  private static final String FIELD_INVITATION_STATUS = "invitationStatus";

  /**
   * Pre-hook dispatch: on a {@code user} {@code POST} (create), derives a unique {@code
   * username}; on a {@code user} {@code PUT}/{@code PATCH} (update), guards against the
   * email-immutability and self/last-admin-lockout writes described in the class javadoc's
   * ETP-4830 write-path-guards concern. No-op for every other method/endpoint.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    if (METHOD_POST.equalsIgnoreCase(method)) {
      return handleCreate(context);
    }
    if (METHOD_PUT.equalsIgnoreCase(method) || METHOD_PATCH.equalsIgnoreCase(method)) {
      return validateUpdate(context);
    }
    return null;
  }

  /**
   * Derives a unique {@code username} from {@code email} and the current client, and rejects a
   * blank/missing email with 400.
   *
   * <p>No longer validates or reads an admin-typed {@code password} (ETP-4830 removed that
   * temporary bypass — see the class javadoc's concern (3)): invite-email is now the only way
   * to activate an admin-created user's {@code etgo_account}, so a {@code password} field on
   * this create form, if the frontend still sends one, is simply ignored here (it still reaches
   * {@code AD_User.Password}, Openbravo's own classic-backend login, unrelated to {@code
   * etgo_account}).
   */
  private NeoResponse handleCreate(NeoContext context) {
    JSONObject requestBody = context.getRequestBody();
    if (requestBody == null) {
      return null;
    }
    String email = StringUtils.trimToNull(requestBody.optString(FIELD_EMAIL, null));
    if (email == null) {
      return NeoResponse.error(400, "Field 'email' is required to create a user");
    }
    try {
      String normalizedEmail = email.toLowerCase();
      OBContext obContext = OBContext.getOBContext();
      String clientName = obContext != null && obContext.getCurrentClient() != null
          ? obContext.getCurrentClient().getName() : null;
      requestBody.put(FIELD_USERNAME,
          clientName == null
              ? normalizedEmail
              : EtendoGoJwtSupport.buildClientUsername(normalizedEmail, clientName));
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.handle: failed to derive username from email: {}",
          e.getMessage(), e);
    }
    return null;
  }

  /**
   * Pre-hook guard for a {@code user} {@code PUT}/{@code PATCH}: rejects an {@code email} change
   * on an existing record, then rejects a self/last-admin-lockout {@code active=false} write.
   * See the class javadoc's ETP-4830 write-path-guards concern for the full rationale. Runs
   * BEFORE the default CRUD update (this is a {@code handle()} pre-hook, not an {@code
   * afterHandle()} side effect), so a rejection here never lets the write reach the DB.
   *
   * @return a 400/500 error response to short-circuit the request, or {@code null} to let the
   *     default CRUD update proceed
   */
  private NeoResponse validateUpdate(NeoContext context) {
    JSONObject requestBody = context.getRequestBody();
    String userId = context.getRecordId();
    if (requestBody == null || userId == null) {
      return null;
    }
    NeoResponse emailGuard = rejectEmailChange(requestBody, userId);
    if (emailGuard != null) {
      return emailGuard;
    }
    return rejectDangerousDeactivation(requestBody, userId, context.getObContext());
  }

  /**
   * Rejects a {@code PUT}/{@code PATCH} that changes {@code email} on an existing {@code user}
   * record. A no-op when the request doesn't touch {@code email} at all, or when the incoming
   * value is byte-for-byte identical (after trimming) to the currently-persisted one — a naive
   * client re-submitting its own unchanged form value must not 400.
   */
  private NeoResponse rejectEmailChange(JSONObject requestBody, String userId) {
    if (!requestBody.has(FIELD_EMAIL)) {
      return null;
    }
    String incomingEmail = StringUtils.trimToNull(requestBody.optString(FIELD_EMAIL, null));
    try {
      OBContext.setAdminMode(true);
      try {
        User user = OBDal.getInstance().get(User.class, userId);
        if (user == null) {
          // Record doesn't exist (yet) — let the default CRUD update produce its own error.
          return null;
        }
        String currentEmail = StringUtils.trimToNull(user.getEmail());
        if (!Objects.equals(incomingEmail, currentEmail)) {
          return NeoResponse.error(400,
              "Field 'email' cannot be changed after the user has been created");
        }
        return null;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("UserRoleAssignmentHandler.rejectEmailChange error for user {}: {}", userId,
          e.getMessage(), e);
      // Fail CLOSED: an error here must not silently let an email change through unverified.
      return NeoResponse.error(500, "Error validating email immutability: " + e.getMessage());
    }
  }

  /**
   * Rejects a {@code PUT}/{@code PATCH} that explicitly sets {@code active=false} on the
   * currently-authenticated user's own record, or on the last remaining active user holding an
   * active client-admin role for that client. A no-op for any request that doesn't explicitly
   * deactivate (see {@link AbstractSmartDeactivationHandler#isExplicitlyDeactivating}).
   */
  private NeoResponse rejectDangerousDeactivation(JSONObject requestBody, String userId,
      OBContext obContext) {
    if (!AbstractSmartDeactivationHandler.isExplicitlyDeactivating(requestBody)) {
      return null;
    }
    String actingUserId = obContext != null && obContext.getUser() != null
        ? obContext.getUser().getId() : null;
    if (actingUserId != null && actingUserId.equals(userId)) {
      return NeoResponse.error(400, "You cannot deactivate your own user account");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        User targetUser = OBDal.getInstance().get(User.class, userId);
        if (targetUser == null) {
          return null;
        }
        if (isLastActiveClientAdmin(targetUser)) {
          return NeoResponse.error(400,
              "Cannot deactivate the last active administrator for this client");
        }
        return null;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("UserRoleAssignmentHandler.rejectDangerousDeactivation error for user {}: {}",
          userId, e.getMessage(), e);
      // Fail CLOSED: an error here must not silently let a lockout-risking deactivation through.
      return NeoResponse.error(500, "Error validating deactivation: " + e.getMessage());
    }
  }

  /**
   * Whether {@code targetUser} is the sole remaining active {@code AD_User} holding an active
   * client-admin {@code AD_Role} ({@code Role.isClientAdmin() == true}) for {@code targetUser}'s
   * client — i.e. deactivating it would leave that client with zero active admins. Counts
   * distinct users via {@code AD_User_Roles} rather than {@code AD_User.Default_Ad_Role_ID}
   * (same reasoning as {@link com.etendoerp.go.schemaforge.util.UserRoleSyncSupport}'s own
   * javadoc: real access checks read {@code AD_User_Roles}, not the UI-convenience pointer
   * field). {@code false} when {@code targetUser} doesn't currently hold an active client-admin
   * role at all — deactivating it then carries no lockout risk from this angle.
   */
  private boolean isLastActiveClientAdmin(User targetUser) {
    Client client = targetUser.getClient();
    if (client == null) {
      return false;
    }
    OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
    criteria.createAlias(UserRoles.PROPERTY_ROLE, "role");
    criteria.createAlias(UserRoles.PROPERTY_USERCONTACT, "assignedUser");
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.eq("role." + Role.PROPERTY_CLIENTADMIN, true));
    criteria.add(Restrictions.eq("role." + Role.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.eq("assignedUser." + User.PROPERTY_ACTIVE, true));
    List<UserRoles> activeAdminAssignments = criteria.list();
    Set<String> activeAdminUserIds = new HashSet<>();
    for (UserRoles row : activeAdminAssignments) {
      activeAdminUserIds.add(row.getUserContact().getId());
    }
    return activeAdminUserIds.size() == 1 && activeAdminUserIds.contains(targetUser.getId());
  }

  /**
   * Post-hook dispatch: sends a company invitation after a {@code user} create, filters
   * bootstrap users out of a {@code user} list GET and attaches {@code invitationStatus} to
   * every surviving {@code user} GET row (list or single-record), or syncs {@code
   * AD_User_Roles} after a {@code user} update. See the class javadoc for why all these concerns
   * live in one handler.
   *
   * @return always {@code null} — every concern mutates {@code context.getPreviousResult()}'s
   *     body in place (or leaves it untouched) rather than replacing the response.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    if (METHOD_POST.equalsIgnoreCase(method)) {
      inviteNewlyCreatedUser(context);
      return null;
    }
    if (METHOD_GET.equalsIgnoreCase(method)) {
      if (context.getRecordId() == null) {
        hideBootstrapUsers(context);
      }
      attachInvitationStatus(context);
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
   * On a successful {@code user} create, reads the newly-created record's {@code email} back
   * out of the response body and sends a company invitation via {@link
   * CompanyInvitationService#createInvitationForNewlyCreatedUser} (see concern (3) in the class
   * javadoc). {@link NeoContext#getRecordId()} is never populated for {@code POST}, so the
   * created record's fields are read from {@code previousResult.body.response.data} instead — a
   * lone {@code JSONObject}, same shape as a single-record GET (see {@link
   * #hideBootstrapUsers}'s javadoc for that same shape). {@code context.getObContext()} is
   * captured by the dispatcher before this method's own {@code
   * OBContext.setAdminMode(true)} runs, so it still reflects the real acting admin's
   * client/org/user — {@code setAdminMode} only lifts security checks, it never changes those.
   * Best-effort: any failure is logged and swallowed, never failing the parent {@code AD_User}
   * creation.
   */
  private void inviteNewlyCreatedUser(NeoContext context) {
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONObject body = previousResult != null ? previousResult.getBody() : null;
      JSONObject inner = body != null ? body.optJSONObject(JsonConstants.RESPONSE_RESPONSE) : null;
      JSONObject data = inner != null ? inner.optJSONObject(JsonConstants.RESPONSE_DATA) : null;
      if (data == null) {
        return;
      }
      String email = StringUtils.trimToNull(data.optString(FIELD_EMAIL, null));
      if (email == null) {
        return;
      }
      OBContext obContext = context.getObContext();
      OBContext.setAdminMode(true);
      try {
        new CompanyInvitationService().createInvitationForNewlyCreatedUser(obContext,
            email.toLowerCase(), null, null);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.inviteNewlyCreatedUser error: {}",
          e.getMessage(), e);
    }
  }

  /**
   * On a {@code user} GET (list or single-record), attaches an {@code invitationStatus} field
   * to every surviving row — {@code null} when no invitation was ever sent, otherwise one of
   * {@code PENDING}/{@code SENT}/{@code ACCEPTED}/{@code EXPIRED}/{@code REVOKED}/{@code
   * DELIVERY_FAILED} (see {@link CompanyInvitationService#findLatestInvitationStatus}) — so the
   * frontend can render a "pending invite" badge without a separate round trip. A single-record
   * fetch has no {@code data} array (it's a lone JSON object instead, same shape {@link
   * #hideBootstrapUsers}'s javadoc documents), so this checks both shapes.
   */
  private void attachInvitationStatus(NeoContext context) {
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONObject body = previousResult != null ? previousResult.getBody() : null;
      JSONObject inner = body != null ? body.optJSONObject(JsonConstants.RESPONSE_RESPONSE) : null;
      if (inner == null) {
        return;
      }
      String clientId = context.getObContext() != null
          && context.getObContext().getCurrentClient() != null
          ? context.getObContext().getCurrentClient().getId() : null;
      if (clientId == null) {
        return;
      }
      OBContext.setAdminMode(true);
      try {
        JSONArray data = inner.optJSONArray(JsonConstants.RESPONSE_DATA);
        if (data != null) {
          for (int i = 0; i < data.length(); i++) {
            attachInvitationStatusToRow(data.optJSONObject(i), clientId);
          }
        } else {
          attachInvitationStatusToRow(inner.optJSONObject(JsonConstants.RESPONSE_DATA), clientId);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.attachInvitationStatus error: {}", e.getMessage(), e);
    }
  }

  private void attachInvitationStatusToRow(JSONObject row, String clientId) {
    if (row == null) {
      return;
    }
    String email = StringUtils.trimToNull(row.optString(FIELD_EMAIL, null));
    String status = email == null ? null
        : CompanyInvitationService.findLatestInvitationStatus(clientId, email);
    try {
      row.put(FIELD_INVITATION_STATUS, status == null ? JSONObject.NULL : status);
    } catch (JSONException e) {
      log.warn("UserRoleAssignmentHandler.attachInvitationStatusToRow error: {}",
          e.getMessage(), e);
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
    UserRoleSyncSupport.syncSingleActiveUserRole(user, targetRole);
  }
}
