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
package com.etendoerp.go.schemaforge.webhooks;

import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.rest.EtendoGoJwtSupport;
import com.etendoerp.go.rest.EtendoGoJwtSupport.RoleListData;
import com.etendoerp.webhookevents.services.BaseWebhookService;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5195 — webhook that reissues the CALLER'S OWN NEO bearer JWT with their CURRENT
 * {@code AD_User.Default_Ad_Role_ID}, closing the "stale role claim" gap in {@code
 * com.etendoerp.go.schemaforge.NeoAuthenticator#authenticateJwt}: every NEO request rebuilds
 * {@link OBContext} straight from the incoming token's {@code role} claim, which is whatever
 * role the token was minted with at login and is never re-derived from the DB on later
 * requests. A promote/demote ({@code UserRoleCompositionService#promoteToAdmin}/{@code
 * #demoteFromAdmin}, see {@code docs/neo-headless.md} §8i) swaps {@code
 * AD_User.Default_Ad_Role_ID}, but a caller's already-issued token keeps authenticating as the
 * pre-promotion/demotion role until a new token is minted — today only at login. This endpoint
 * lets the frontend request a fresh token right after such an action, without forcing a full
 * re-login.
 *
 * <p><b>Endpoint:</b> {@code GET /sws/neo/refreshtoken} (no parameters) — reached ONLY through
 * the NEO pseudo-spec bridge (see {@code docs/neo-headless.md} §4.10/§4.11); no legacy
 * {@code /webhooks/*} path, same as every sibling authored after that pattern existed.</p>
 *
 * <p><b>Security — this can only ever reissue the CALLER'S OWN token, never anyone else's.</b>
 * The caller is authenticated by {@code NeoAuthenticator#authenticateJwt} — the exact same
 * signature/expiry validation every other NEO request goes through — BEFORE this webhook is
 * ever reached: {@code NeoServlet#processRequest} runs {@code
 * authenticator.authenticateRequest(...)} first and unconditionally, and a failed validation
 * there writes the {@code 401} itself and returns before the pseudo-spec dispatcher (hence this
 * class) is ever consulted. {@code userId} is read ONLY from {@link OBContext#getOBContext()}'s
 * user — populated by {@code authenticateJwt} from the validated token's own {@code user}
 * claim — and NEVER from a request parameter or body. There is deliberately no parameter that
 * could let a caller name a different target user; doing so would be a privilege-escalation
 * hole.</p>
 *
 * <p>The {@code role} claim of the incoming token is deliberately NOT reused: the role to embed
 * in the new token is re-resolved fresh from {@link User#getDefaultRole()} for that same user
 * (a plain {@link OBDal} lookup by id, not anything cached from the request), so a stale
 * token's advisory role claim never leaks into the reissued one. The new token itself is minted
 * via {@link SecureWebServicesUtils#generateToken(User, Role)} — the exact 2-argument overload
 * {@code EtendoGoJwtServlet#writeEnvironmentLoginResponse} already uses to mint the very first
 * token at login — which additionally re-resolves a matching organization/warehouse for that
 * role the same way login does (see that method's own javadoc for why {@code org}/{@code
 * warehouse} are left {@code null} here).</p>
 *
 * <p><b>Response shape.</b> When a role is genuinely resolved for the caller (the ordinary
 * case), the response is {@code {"token": "<jwt>", "session": {...}}} — the {@code session}
 * object (version 1) carries {@code userId}, {@code clientId}, {@code selectedRoleId},
 * {@code selectedOrgId} (all read back from the {@code user}/{@code client}/{@code role}/
 * {@code organization} claims of the token just minted, via {@link
 * SecureWebServicesUtils#decodeToken(String)} — never re-derived independently, so the response
 * can never disagree with what the JWT actually contains) and {@code roleList} (the same shape
 * {@link EtendoGoJwtSupport#loadRoleListData(String)} already builds for login). This activates
 * the richer validation the frontend's {@code reconcileSessionRefresh} already implements (see
 * {@code docs/auth-session-refresh.md} in {@code schema_forge_core}) instead of its "legacy"
 * token-swap-only fallback.
 *
 * <p>The {@code currentRole == null} case — a user resolving to literally no assignable role at
 * all; not the ordinary case, the promote/demote invariant this endpoint exists for always
 * leaves one — is UNCHANGED: the response stays the bare {@code {"token": "<jwt>"}}, no
 * {@code session} key, so the frontend's legacy fallback still applies there. This is a
 * genuinely unexpected state, so — like before — it is deliberately NOT modeled as a
 * {@code success:false} domain rejection the way {@code SFPromoteUserRole}'s target-user
 * validation is: it surfaces as the bridge's normal {@code error}/{@code 500} path.</p>
 */
public class SFRefreshToken extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFRefreshToken.class);

  private static final String RESPONSE_VAR_RESULT = "result";
  private static final String RESPONSE_VAR_ERROR = "error";
  private static final String FIELD_TOKEN = "token";
  private static final String FIELD_SESSION = "session";
  private static final String FIELD_SESSION_VERSION = "version";
  private static final String FIELD_USER_ID = "userId";
  private static final String FIELD_CLIENT_ID = "clientId";
  private static final String FIELD_SELECTED_ROLE_ID = "selectedRoleId";
  private static final String FIELD_SELECTED_ORG_ID = "selectedOrgId";
  private static final String FIELD_ROLE_LIST = "roleList";
  private static final int SESSION_CONTRACT_VERSION = 1;
  private static final String CLAIM_USER = "user";
  private static final String CLAIM_CLIENT = "client";
  private static final String CLAIM_ROLE = "role";
  private static final String CLAIM_ORGANIZATION = "organization";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    String callerUserId = resolveCallerUserId();
    if (callerUserId == null) {
      responseVars.put(RESPONSE_VAR_RESULT,
          WebhookFailureResponses.failure("Unable to resolve the authenticated user").toString());
      return;
    }

    try {
      // Re-read the user fresh from the DB so Default_Ad_Role_ID reflects any promote/demote
      // that happened after the caller's current token was minted. Never reuse the role claim
      // decoded from the incoming token itself -- that is precisely the stale value this
      // endpoint exists to bypass.
      User user = OBDal.getInstance().get(User.class, callerUserId);
      if (user == null) {
        responseVars.put(RESPONSE_VAR_RESULT,
            WebhookFailureResponses.failure("User not found").toString());
        return;
      }
      // ETP-5195, R5: this endpoint mints a live bearer token, so it must not do so for an
      // inactive account -- an admin deactivating a user mid-session must not leave this refresh
      // path as a way to keep minting valid tokens for them.
      if (!Boolean.TRUE.equals(user.isActive())) {
        responseVars.put(RESPONSE_VAR_RESULT,
            WebhookFailureResponses.failure("User is not active").toString());
        return;
      }
      Role currentRole = user.getDefaultRole();
      // A null currentRole is the existing, documented "no assignable role" case (see this
      // class's javadoc) -- left unchanged, it still flows straight through to generateToken
      // below. It is only when a role IS resolved that ETP-5195, R5 requires verifying the user
      // is genuinely, ACTIVELY eligible for it: under normal flow Default_Ad_Role_ID and
      // AD_User_Roles should always agree, but this endpoint must not TRUST that invariant
      // blindly, since a data inconsistency would otherwise let it mint a token for a role the
      // user isn't actually assigned to.
      if (currentRole != null && !isEligibleForRole(user, currentRole)) {
        responseVars.put(RESPONSE_VAR_RESULT,
            WebhookFailureResponses.failure("User is not eligible for the assigned role")
                .toString());
        return;
      }
      String newToken = SecureWebServicesUtils.generateToken(user, currentRole);
      if (currentRole != null) {
        // ETP-5195 -- session metadata extension: only meaningful once a role was actually
        // (re)resolved. Decoding the token we just minted -- rather than re-deriving
        // client/org/role from scratch -- guarantees the response always agrees with what is
        // really embedded in it.
        JSONObject session = buildSessionMetadata(newToken, callerUserId);
        responseVars.put(RESPONSE_VAR_RESULT, successWithSession(newToken, session).toString());
      } else {
        // Legacy/no-metadata case: intentionally unchanged, see class javadoc.
        responseVars.put(RESPONSE_VAR_RESULT, success(newToken).toString());
      }
    } catch (Exception e) {
      log.error("Unexpected error in SFRefreshToken for user {}", callerUserId, e);
      responseVars.put(RESPONSE_VAR_ERROR, e.getMessage());
    }
  }

  /**
   * Reads the caller's user id ONLY from the current {@link OBContext} -- which {@code
   * NeoAuthenticator#authenticateJwt} populated from the already-validated token's own {@code
   * user} claim before this webhook was ever reached. Never accepts it as a parameter.
   */
  private String resolveCallerUserId() {
    OBContext context = OBContext.getOBContext();
    return context != null && context.getUser() != null ? context.getUser().getId() : null;
  }

  /**
   * ETP-5195, R5 — verifies {@code role} is active, belongs to the SAME client the caller is
   * authenticated as, and that {@code user} holds a genuine, ACTIVE {@code AD_User_Roles}
   * assignment to it, rather than trusting that {@code Default_Ad_Role_ID} always points at a
   * role the user is really, currently eligible for. The cross-client check guards against the
   * same kind of data inconsistency: under normal flow {@code Default_Ad_Role_ID} should always
   * resolve to a role in the caller's own client, but this endpoint must not TRUST that
   * blindly — a role from a DIFFERENT client would otherwise mint a token embedding that role
   * (and its org/warehouse) from a different tenant than the caller's own validated session, a
   * cross-tenant leak. Reuses the same {@link UserRoles} query convention {@link
   * SFRolesOverview#resolveActiveUserIds} already established for this kind of cross-cutting
   * lookup. Entered under admin mode: the caller's own {@link OBContext} is scoped to the role
   * embedded in their (possibly stale) current token, which is exactly the value this check
   * cannot trust — the lookup itself must not depend on it.
   */
  private boolean isEligibleForRole(User user, Role role) {
    OBContext callerContext = OBContext.getOBContext();
    if (callerContext == null || callerContext.getCurrentClient() == null) {
      // Fail closed rather than NPE-ing into the generic bridge error: same deny outcome, but
      // surfaces as the specific ineligibility failure below instead of a bare exception.
      return false;
    }
    String callerClientId = callerContext.getCurrentClient().getId();
    if (!Objects.equals(callerClientId, role.getClient().getId())) {
      return false;
    }
    if (!Boolean.TRUE.equals(role.isActive())) {
      return false;
    }
    OBContext.setAdminMode(true);
    try {
      OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
      criteria.add(Restrictions.eq(UserRoles.PROPERTY_ROLE + ".id", role.getId()));
      criteria.add(Restrictions.eq(UserRoles.PROPERTY_USERCONTACT + ".id", user.getId()));
      criteria.add(Restrictions.eq(UserRoles.PROPERTY_ACTIVE, true));
      return criteria.count() > 0;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private JSONObject success(String token) {
    try {
      JSONObject body = new JSONObject();
      body.put(FIELD_TOKEN, token);
      return body;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build success result", e);
    }
  }

  /**
   * ETP-5195 -- session metadata extension. Sibling of {@link #success(String)} for the
   * eligible-role case: same top-level {@code token} field, plus a {@code session} object
   * carrying the identity/role-list metadata the frontend's {@code reconcileSessionRefresh}
   * already knows how to validate (see {@code docs/auth-session-refresh.md} in
   * {@code schema_forge_core}).
   */
  private JSONObject successWithSession(String token, JSONObject session) {
    try {
      JSONObject body = success(token);
      body.put(FIELD_SESSION, session);
      return body;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build success result", e);
    }
  }

  /**
   * Builds the {@code session} metadata object by decoding the token just minted -- rather than
   * re-deriving client/role/organization independently -- so the response can never disagree
   * with what is actually embedded in the returned JWT. {@code roleList} is resolved via {@link
   * EtendoGoJwtSupport#loadRoleListData(String)}, the same helper/query {@code
   * EtendoGoJwtServlet} already uses to build the equivalent list at login.
   *
   * @param newToken the JWT just minted by {@link SecureWebServicesUtils#generateToken(User,
   *     Role)} for the current caller/role
   * @param callerUserId the caller's own id, already resolved via {@link
   *     #resolveCallerUserId()} -- passed in rather than re-read from the decoded token, though
   *     both are expected to always agree
   */
  private JSONObject buildSessionMetadata(String newToken, String callerUserId) throws Exception {
    DecodedJWT decoded = SecureWebServicesUtils.decodeToken(newToken);
    String userId = decoded.getClaim(CLAIM_USER).asString();
    String clientId = decoded.getClaim(CLAIM_CLIENT).asString();
    String selectedRoleId = decoded.getClaim(CLAIM_ROLE).asString();
    String selectedOrgId = decoded.getClaim(CLAIM_ORGANIZATION).asString();

    RoleListData roleListData = EtendoGoJwtSupport.loadRoleListData(callerUserId);

    JSONObject session = new JSONObject();
    session.put(FIELD_SESSION_VERSION, SESSION_CONTRACT_VERSION);
    session.put(FIELD_USER_ID, userId);
    session.put(FIELD_CLIENT_ID, clientId);
    session.put(FIELD_SELECTED_ROLE_ID, selectedRoleId);
    session.put(FIELD_SELECTED_ORG_ID, selectedOrgId);
    session.put(FIELD_ROLE_LIST, roleListData.roleArray);
    return session;
  }
}
