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

package com.etendoerp.go.rest;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.Invitation;

/**
 * ETP-5194 — the invitation lookups and the revoke behind the {@code user} email-correction
 * window ({@code UserEmailCorrection} / {@code UserRoleAssignmentHandler}): which invitation
 * statuses let an admin correct an invitee's email, whether a user was ever invited or accepted,
 * whether a fresh invitation belongs to a given user, and revoking the invitations an email
 * correction superseded. Split from {@link CompanyInvitationService}, which owns the invitation
 * lifecycle itself (create, send, resend, accept), so that class stays within its method budget.
 * Same package as the package-private {@link CompanyInvitationDalHelper} it queries through.
 */
public final class CompanyInvitationEmailCorrection {

  private static final String STATUS_ACCEPTED = "ACCEPTED";
  private static final String STATUS_EXPIRED = "EXPIRED";
  private static final String STATUS_REVOKED = "REVOKED";
  private static final String STATUS_DELIVERY_FAILED = "DELIVERY_FAILED";

  private CompanyInvitationEmailCorrection() {
  }

  /**
   * Whether an invitation in {@code status} lets an admin correct the invitee's email (ETP-5194):
   * only {@code EXPIRED} and {@code DELIVERY_FAILED}, the two states in which the invitee cannot
   * have accepted through the current link. {@code status} is expected to be the effective status
   * returned by {@link CompanyInvitationService#findLatestInvitationStatus}.
   *
   * @param status the effective invitation status, may be {@code null}
   * @return {@code true} for {@code EXPIRED}/{@code DELIVERY_FAILED}
   */
  public static boolean isEmailCorrectableStatus(String status) {
    return STATUS_EXPIRED.equalsIgnoreCase(status)
        || STATUS_DELIVERY_FAILED.equalsIgnoreCase(status);
  }

  /**
   * Whether an invitation was ever accepted in {@code clientId} by {@code userId} or for {@code
   * email} (ETP-5194). An accepted invitation means a Go account is linked to this user in this
   * tenant, so the email must stay locked even when a later invitation expired, or when the email
   * was changed outside Go afterwards.
   *
   * @param clientId tenant client id scoping the lookup
   * @param userId the {@code AD_User_ID}
   * @param email the {@code AD_User}'s current email
   * @return {@code true} when an {@code ACCEPTED} invitation exists
   */
  public static boolean hasAcceptedInvitation(String clientId, String userId, String email) {
    if (StringUtils.isBlank(clientId) || StringUtils.isBlank(userId)
        || StringUtils.isBlank(email)) {
      return false;
    }
    return CompanyInvitationDalHelper.existsAcceptedInvitation(clientId, userId, email);
  }

  /**
   * Whether the latest invitation addressed to {@code email} in {@code clientId} was issued to
   * {@code userId} (ETP-5194). After an email correction it tells a fresh invitation for this user
   * apart from a failure (no row) or a pre-existing open invitation of another {@code AD_User}
   * that {@link
   * CompanyInvitationService#createInvitationForNewlyCreatedUser} merely reported back.
   *
   * @param clientId tenant client id
   * @param email the address the invitation was sent to
   * @param userId the {@code AD_User_ID} expected to own it
   * @return {@code true} when that latest invitation exists and points at {@code userId}
   */
  public static boolean latestInvitationBelongsTo(String clientId, String email, String userId) {
    if (StringUtils.isBlank(clientId) || StringUtils.isBlank(email)
        || StringUtils.isBlank(userId)) {
      return false;
    }
    Invitation latest = CompanyInvitationDalHelper.findLatestInvitation(clientId,
        email.toLowerCase(Locale.ROOT));
    return latest != null && latest.getUser() != null && userId.equals(latest.getUser().getId());
  }

  /**
   * Whether {@code userId} was ever invited in {@code clientId} (ETP-5194) — i.e. it is a Go user
   * and not a business-partner contact, which never gets an invitation of its own. An email
   * correction, and the re-invite that follows it, is only offered to such a user.
   *
   * @param clientId tenant client id
   * @param userId the {@code AD_User_ID}
   * @return {@code true} when at least one invitation row points at this user
   */
  public static boolean hasInvitationForUser(String clientId, String userId) {
    if (StringUtils.isBlank(clientId) || StringUtils.isBlank(userId)) {
      return false;
    }
    return CompanyInvitationDalHelper.existsInvitationForUser(clientId, userId);
  }

  /**
   * Revokes every invitation of {@code userId} that was addressed to an email other than
   * {@code currentEmail} and is not already {@code ACCEPTED}/{@code REVOKED} (ETP-5194). Runs
   * after an admin corrects the invitee's email and a fresh invitation has been issued to the new
   * address, so a link sent to the wrong address can never be accepted afterwards.
   *
   * @param clientId tenant client id
   * @param userId the {@code AD_User_ID} whose email changed
   * @param currentEmail the user's new email
   * @return how many invitations were revoked
   */
  public static int revokeSupersededInvitations(String clientId, String userId,
      String currentEmail) {
    if (StringUtils.isBlank(clientId) || StringUtils.isBlank(userId)
        || StringUtils.isBlank(currentEmail)) {
      return 0;
    }
    int revoked = 0;
    for (Invitation invitation : CompanyInvitationDalHelper.findInvitationsForUser(clientId,
        userId)) {
      String status = invitation.getStatus();
      if (STATUS_ACCEPTED.equalsIgnoreCase(status) || STATUS_REVOKED.equalsIgnoreCase(status)
          || currentEmail.equalsIgnoreCase(invitation.getEmail())) {
        continue;
      }
      invitation.setStatus(STATUS_REVOKED);
      OBDal.getInstance().save(invitation);
      revoked++;
    }
    if (revoked > 0) {
      OBDal.getInstance().flush();
    }
    return revoked;
  }
}
