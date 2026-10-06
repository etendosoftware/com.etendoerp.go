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

import java.util.Locale;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.rest.CompanyInvitationEmailCorrection;
import com.etendoerp.go.rest.CompanyInvitationService;
import com.etendoerp.go.rest.EtendoGoJwtSupport;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.util.OwnerSupport;

/**
 * ETP-5194 — the email-correction window of the {@code user} entity, extracted from {@link
 * UserRoleAssignmentHandler} so that handler stays within its method budget. Everything here is
 * specific to that one entity and is only called from it: the pre-hook guard on an email change
 * ({@link #rejectEmailChange}), the {@code emailEditable} flag on every GET row ({@link
 * #attachEmailEditable}), and the email/username helpers create and correction share.
 *
 * <p>The rule ({@link #isEmailEditable(User, String)}): the email of a Go user may be corrected
 * only while no Go account can be linked to it through an accepted invitation — not the owner, the
 * user has an invitation of its own (a business-partner contact never has one and must never be
 * invited), the latest invitation to the current address is {@code EXPIRED}/{@code
 * DELIVERY_FAILED} or absent, and no invitation of the user or to the address was ever accepted.
 * After an allowed change, {@link UserRoleAssignmentHandler}'s post-hook re-derives the username
 * and re-invites, reading the marker this class leaves under {@link #ATTR_EMAIL_CHANGE}.
 */
final class UserEmailCorrection {

  private static final Logger log = LogManager.getLogger(UserEmailCorrection.class);

  static final String FIELD_EMAIL = "email";
  static final String FIELD_ID = "id";
  static final String FIELD_INVITATION_STATUS = "invitationStatus";
  static final String FIELD_IS_OWNER = "isOwner";
  /** Whether the row's {@code email} may still be corrected, see {@link #attachEmailEditable}. */
  static final String FIELD_EMAIL_EDITABLE = "emailEditable";
  /**
   * {@link NeoContext} attribute set by {@link #rejectEmailChange} when it lets an email change
   * through, read back by the handler's post-hook on the same request. Holds an {@link
   * EmailChange}.
   */
  static final String ATTR_EMAIL_CHANGE = UserEmailCorrection.class.getName() + ".emailChange";
  /**
   * The refusal for an email change outside the correction window. Says when the change IS
   * allowed, so an MCP agent can tell the user what to do. Kept verbatim — {@code
   * backendErrors.js} in the SPA translates it by exact match.
   */
  static final String MSG_EMAIL_LOCKED = "Field 'email' can only be changed while the user's "
      + "invitation has expired or could not be delivered";
  /**
   * The refusal when the target was never invited — typically a business-partner contact person,
   * which lives on the same {@code AD_User} table. Points an MCP agent that picked this spec by
   * mistake to the one where that email is freely editable (and never invites). Kept verbatim —
   * {@code backendErrors.js} translates it by exact match.
   */
  static final String MSG_EMAIL_NOT_A_GO_USER = "This user was never invited to Etendo "
      + "(for example, a business partner contact person): edit its email through spec "
      + "'contacts', entity 'contact'";

  private UserEmailCorrection() {
  }

  /** The email a user had before an allowed correction, kept for the post-hook's log line. */
  static final class EmailChange {
    private final String previousEmail;

    private EmailChange(String previousEmail) {
      this.previousEmail = previousEmail;
    }

    String previousEmail() {
      return previousEmail;
    }
  }

  /**
   * The trimmed {@code email} of a request body or response row, {@code null} when absent, blank
   * or JSON {@code null}. Jettison's {@code optString} turns a JSON {@code null} into the string
   * {@code "null"}, which would otherwise read as a non-blank address (ETP-5194 QA BUG-1/BUG-2).
   */
  static String emailOf(JSONObject json) {
    return json == null || json.isNull(FIELD_EMAIL) ? null
        : StringUtils.trimToNull(json.optString(FIELD_EMAIL, null));
  }

  /**
   * The {@code username} a user with {@code normalizedEmail} gets in {@code client}: the email
   * itself, or the client-suffixed form when it is already taken (see {@link
   * EtendoGoJwtSupport#buildClientUsername}). Shared by create and by an email correction so both
   * follow the convention {@code EtendoGoJwtDalHelper} matches on.
   */
  static String deriveUsername(String normalizedEmail, Client client) {
    String clientName = client != null ? client.getName() : null;
    return clientName == null
        ? normalizedEmail
        : EtendoGoJwtSupport.buildClientUsername(normalizedEmail, clientName);
  }

  /**
   * Rejects {@code normalizedEmail} (already lowercased) when another {@code AD_User} of {@code
   * client} uses it — see {@link UserRoleAssignmentHandler}'s ETP-5264 duplicate-email-guard
   * concern for why this proactive check exists (it stands in for the DB's own {@code username}
   * unique-constraint, whose violation would otherwise surface a raw message naming a field the
   * form never shows). Deliberately does NOT filter by {@code active}, like the constraint it
   * stands in for. {@code excludeUserId} skips the user's own row on a correction; {@code null}
   * on create. A no-op ({@code null}) when {@code client} is {@code null}.
   */
  static NeoResponse rejectDuplicateEmail(String normalizedEmail, Client client,
      String excludeUserId) {
    if (client == null) {
      return null;
    }
    OBCriteria<User> criteria = OBDal.getInstance().createCriteria(User.class);
    criteria.add(Restrictions.eq(User.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.ilike(User.PROPERTY_EMAIL, normalizedEmail, MatchMode.EXACT));
    if (excludeUserId != null) {
      criteria.add(Restrictions.ne(User.PROPERTY_ID, excludeUserId));
    }
    criteria.setMaxResults(1);
    if (!criteria.list().isEmpty()) {
      return NeoResponse.error(400, "A user with this email address already exists");
    }
    return null;
  }

  /**
   * Guards an {@code email} change on an existing {@code user} record (ETP-4830, relaxed by
   * ETP-5194). A no-op when the request doesn't touch {@code email} at all, or when the incoming
   * value is identical (after trimming) to the persisted one — a naive client re-submitting its
   * own unchanged form value must not 400.
   *
   * <p>A real change is only allowed while {@link #isEmailEditable(User, String)} holds. Anything
   * else gets a 400: {@link #MSG_EMAIL_NOT_A_GO_USER} for a non-owner never invited, {@link
   * #MSG_EMAIL_LOCKED} otherwise. An allowed change must be a non-blank, well-formed address not
   * used by another user of the client; it is lowercased in the request body (the same
   * normalization create applies) and recorded on the context under {@link #ATTR_EMAIL_CHANGE}.
   * Fails CLOSED: an unexpected error answers 500, never lets the change through.
   */
  static NeoResponse rejectEmailChange(JSONObject requestBody, String userId,
      NeoContext context) {
    if (!requestBody.has(FIELD_EMAIL)) {
      return null;
    }
    String incomingEmail = emailOf(requestBody);
    try {
      OBContext.setAdminMode(true);
      try {
        User user = OBDal.getInstance().get(User.class, userId);
        if (user == null) {
          // Record doesn't exist (yet) — let the default CRUD update produce its own error.
          return null;
        }
        String currentEmail = StringUtils.trimToNull(user.getEmail());
        if (Objects.equals(incomingEmail, currentEmail)) {
          return null;
        }
        if (!isEmailEditable(user, currentEmail)) {
          return NeoResponse.error(400, isNeverInvitedNonOwner(user)
              ? MSG_EMAIL_NOT_A_GO_USER : MSG_EMAIL_LOCKED);
        }
        return acceptEmailChange(requestBody, user, incomingEmail, currentEmail, context);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("UserEmailCorrection.rejectEmailChange error for user {}: {}", userId,
          e.getMessage(), e);
      return NeoResponse.error(500, "Error validating email immutability: " + e.getMessage());
    }
  }

  /**
   * Whether {@code user} is not the owner and has no invitation of its own — a business-partner
   * contact person or a user never invited to Go. Only picks the refusal message; evaluated on
   * the rejection path only, so it costs nothing on an allowed change.
   */
  private static boolean isNeverInvitedNonOwner(User user) {
    String clientId = user.getClient() != null ? user.getClient().getId() : null;
    return !OwnerSupport.isOwner(user.getId())
        && !CompanyInvitationEmailCorrection.hasInvitationForUser(clientId, user.getId());
  }

  /**
   * Validates an email change {@link #isEmailEditable(User, String)} already allowed, normalizes
   * it in the request body and marks the context for the post-hook.
   *
   * @return a 400 for a blank, malformed or duplicate address, otherwise {@code null}
   */
  private static NeoResponse acceptEmailChange(JSONObject requestBody, User user,
      String incomingEmail, String currentEmail, NeoContext context) throws JSONException {
    if (incomingEmail == null) {
      return NeoResponse.error(400, "Field 'email' is required");
    }
    String normalizedEmail = incomingEmail.toLowerCase(Locale.ROOT);
    requestBody.put(FIELD_EMAIL, normalizedEmail);
    if (normalizedEmail.equalsIgnoreCase(currentEmail)) {
      // Only the case differed from what is already stored: nothing actually changes.
      return null;
    }
    if (!EmailContractCommandSupport.isValidEmail(normalizedEmail)) {
      return NeoResponse.error(400, "Invalid email format");
    }
    NeoResponse duplicateEmailGuard =
        rejectDuplicateEmail(normalizedEmail, user.getClient(), user.getId());
    if (duplicateEmailGuard != null) {
      return duplicateEmailGuard;
    }
    context.setAttribute(ATTR_EMAIL_CHANGE, new EmailChange(currentEmail));
    return null;
  }

  /**
   * Whether {@code user}'s email may still be corrected. Only while ALL hold:
   * <ul>
   *   <li>it is not the client's owner;</li>
   *   <li>it is a Go user, not a business-partner contact: at least one invitation was issued to
   *   this very {@code AD_User} ({@link CompanyInvitationEmailCorrection#hasInvitationForUser}).
   *   A contact must never be sent an invitation by any flow, and a correction always re-invites.
   *   A blank email therefore stays locked too — such a row is a contact or a user that was never
   *   invited;</li>
   *   <li>the latest invitation to the current email is {@code EXPIRED} or {@code
   *   DELIVERY_FAILED} — or there is none at all, the state an earlier correction is left in when
   *   its re-invite could not be issued, which must stay recoverable;</li>
   *   <li>no invitation of this user, nor to this email, was ever accepted.</li>
   * </ul>
   * Those are the only states in which no Go account can be linked to this {@code AD_User}
   * through an accepted invitation, which is the link the original ETP-4830 lock protected.
   * Lookups throw rather than default, so callers fail closed. Must run in admin mode.
   */
  private static boolean isEmailEditable(User user, String currentEmail) {
    boolean isOwner = OwnerSupport.isOwner(user.getId());
    String clientId = user.getClient() != null ? user.getClient().getId() : null;
    String latestStatus = currentEmail == null ? null
        : CompanyInvitationService.findLatestInvitationStatus(clientId, currentEmail);
    return isEmailEditable(isOwner, user.getId(), currentEmail, latestStatus, clientId);
  }

  /**
   * The {@link #isEmailEditable(User, String)} rule over values a GET row already carries, so the
   * list does not repeat the owner and status lookups. Only a correctable or absent status costs
   * a query.
   */
  private static boolean isEmailEditable(boolean isOwner, String userId, String email,
      String latestStatus, String clientId) {
    if (isOwner || email == null || userId == null) {
      return false;
    }
    if (latestStatus != null
        && !CompanyInvitationEmailCorrection.isEmailCorrectableStatus(latestStatus)) {
      return false;
    }
    return CompanyInvitationEmailCorrection.hasInvitationForUser(clientId, userId)
        && !CompanyInvitationEmailCorrection.hasAcceptedInvitation(clientId, userId, email);
  }

  /**
   * On a {@code user} GET (list or single-record), attaches a boolean {@code emailEditable} to
   * every row: whether the email can still be corrected. The SPA's {@code readOnlyLogicJs} on
   * {@code email} reads this flag rather than re-deriving the rule, so the client cannot drift
   * from {@link #rejectEmailChange}, and typing in the form cannot flip it. Must run after the
   * handler attached {@code invitationStatus} and {@code isOwner}, which it reads. A row whose
   * evaluation fails gets {@code false} (fail closed).
   */
  static void attachEmailEditable(NeoContext context) {
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
      OBContext.setAdminMode(true);
      try {
        JSONArray data = inner.optJSONArray(JsonConstants.RESPONSE_DATA);
        if (data != null) {
          for (int i = 0; i < data.length(); i++) {
            attachEmailEditableToRow(data.optJSONObject(i), clientId);
          }
        } else {
          attachEmailEditableToRow(inner.optJSONObject(JsonConstants.RESPONSE_DATA), clientId);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("UserEmailCorrection.attachEmailEditable error: {}", e.getMessage(), e);
    }
  }

  /** Writes {@code emailEditable} onto one row; see {@link #attachEmailEditable}. */
  static void attachEmailEditableToRow(JSONObject row, String clientId) {
    if (row == null) {
      return;
    }
    boolean editable;
    try {
      String email = emailOf(row);
      String status = row.isNull(FIELD_INVITATION_STATUS) ? null
          : row.optString(FIELD_INVITATION_STATUS, null);
      // No isOwner / invitationStatus on the row means that lookup failed: unknown, not "none".
      editable = row.has(FIELD_IS_OWNER) && row.has(FIELD_INVITATION_STATUS)
          && isEmailEditable(row.optBoolean(FIELD_IS_OWNER, true),
              StringUtils.trimToNull(row.optString(FIELD_ID, null)), email, status, clientId);
    } catch (Exception e) {
      log.warn("UserEmailCorrection.attachEmailEditableToRow error for user {}: {}",
          row.optString(FIELD_ID, null), e.getMessage(), e);
      editable = false;
    }
    try {
      row.put(FIELD_EMAIL_EDITABLE, editable);
    } catch (JSONException e) {
      log.warn("UserEmailCorrection.attachEmailEditableToRow error: {}", e.getMessage(), e);
    }
  }
}
