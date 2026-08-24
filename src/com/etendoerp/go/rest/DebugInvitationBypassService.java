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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Invitation;

/**
 * ETP-4830 (item #4) — dev/QA-only account-provisioning and invitation-status bypass, so the
 * invite-email flow (ETP-4830) and the frontend's pending-invitation pill states (see
 * {@code PendingInvitationPill} in {@code etendo_schema_forge}) can be exercised without a real
 * email round-trip.
 *
 * <p><b>Reuses, never duplicates, the real account-provisioning mechanism</b>
 * ({@link EtendoGoJwtDalHelper#createAccount}, {@link PasswordHasher#hash}, the same
 * {@link CompanyInvitationService#generateToken()} token generator) — the only thing this class
 * skips relative to {@link CompanyInvitationService#registerAndAccept} is the token/email
 * round-trip itself, exactly as the ticket asks. Lives in this package (not {@code
 * com.etendoerp.go.schemaforge.webhooks}, where every other NEO pseudo-spec webhook lives)
 * specifically so it can call {@link EtendoGoJwtDalHelper} and {@link CompanyInvitationDalHelper}
 * directly — both are package-private, by design, to keep account-provisioning DAL primitives
 * from being called ad hoc outside this package. {@code
 * com.etendoerp.go.schemaforge.webhooks.SFDebugInvitationBypass} is the thin NEO-facing shim that
 * marshals request parameters into calls on this class, mirroring the {@code SFAssignUserRoles}
 * (shim, {@code webhooks} package) / {@code UserRoleCompositionService} (real logic, a third
 * package) split already used for ETP-4852.</p>
 *
 * <p><b>Security boundary lives one level up.</b> This class performs no flag/gating check of its
 * own — {@code NeoPseudoSpecDispatcher#dispatchDebugInvitationBypass} checks
 * {@code GoRuntimeProperties.readBoolean} BEFORE {@code SFDebugInvitationBypass} (and therefore
 * this class) is ever constructed, so a disabled flag means zero DB access, not just an
 * early-return here. See {@code docs/neo-headless.md}'s "Debug invitation bypass" subsection.</p>
 */
public class DebugInvitationBypassService {

  private static final String STATUS_ACTIVE = "active";
  private static final String STATUS_ACCEPTED = "ACCEPTED";

  private static final Set<String> VALID_STATUSES = new HashSet<>(Arrays.asList(
      "PENDING", "SENT", STATUS_ACCEPTED, "EXPIRED", "REVOKED", "DELIVERY_FAILED"));

  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_EMAIL = "email";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_ACCOUNT_ID = "accountId";
  private static final String FIELD_ACCOUNT_CREATED = "accountCreated";
  private static final String FIELD_TEMPORARY_PASSWORD = "temporaryPassword";
  private static final String FIELD_INVITATION_ID = "invitationId";
  private static final String FIELD_INVITATION_STATUS = "invitationStatus";

  /**
   * Force-accepts an invitation for {@code email} (or the email of the {@code AD_User} identified
   * by {@code adUserId}, when {@code email} is blank): finds-or-creates an active
   * {@code etgo_account} for that email (reusing {@link EtendoGoJwtDalHelper#createAccount} —
   * never duplicated here) and, if a matching {@code ETGO_INVITATION} row exists (any status,
   * most recent one, regardless of client — this is a dev tool, not scoped to "the caller's
   * tenant only"), flips it to {@code ACCEPTED} and links it to that account. Skips the
   * token/email step entirely: no {@code ETGO_INVITATION} row is required to exist for this to
   * succeed, unlike the real accept flow.
   *
   * @param email invitee email (normalized to lower-case); takes priority over {@code adUserId}
   * @param adUserId {@code AD_User_ID} to resolve an email from when {@code email} is blank
   * @param name display name for a newly created account (defaults to the email when blank)
   * @return response JSON — {@code success:false} with a {@code message} on a validation
   *     failure (still HTTP 200, matching this webhook family's "don't 500 a validation
   *     rejection" convention — see {@code SFAssignUserRoles}'s class javadoc)
   * @throws JSONException when the response cannot be serialized
   */
  public JSONObject forceAccept(String email, String adUserId, String name) throws JSONException {
    String resolvedEmail = resolveEmail(email, adUserId);
    if (resolvedEmail == null) {
      return failure("Email is required (directly, or resolvable from AdUserId)");
    }

    Account account = EtendoGoJwtDalHelper.findActiveAccountByEmail(resolvedEmail);
    boolean created = false;
    String temporaryPassword = null;
    if (account == null) {
      temporaryPassword = generateTempPassword();
      String passwordHash = PasswordHasher.hash(temporaryPassword);
      String trimmedName = StringUtils.defaultIfBlank(StringUtils.trimToNull(name), resolvedEmail);
      String sessionToken = CompanyInvitationService.generateToken();
      account = EtendoGoJwtDalHelper.createAccount(resolvedEmail, passwordHash, trimmedName,
          sessionToken);
      created = true;
    } else if (!isActiveAccount(account)) {
      account.setActive(true);
      account.set(Account.PROPERTY_STATUS, STATUS_ACTIVE);
      OBDal.getInstance().save(account);
      OBDal.getInstance().flush();
    }

    Invitation invitation = findLatestInvitationForEmail(resolvedEmail);
    if (invitation != null && !STATUS_ACCEPTED.equalsIgnoreCase(invitation.getStatus())) {
      invitation.setEtgoAccount(account);
      invitation.setStatus(STATUS_ACCEPTED);
      OBDal.getInstance().save(invitation);
      OBDal.getInstance().flush();
    }
    OBDal.getInstance().commitAndClose();

    JSONObject result = new JSONObject();
    result.put(FIELD_SUCCESS, true);
    result.put(FIELD_EMAIL, resolvedEmail);
    result.put(FIELD_ACCOUNT_ID, account.getId());
    result.put(FIELD_ACCOUNT_CREATED, created);
    if (temporaryPassword != null) {
      result.put(FIELD_TEMPORARY_PASSWORD, temporaryPassword);
    }
    result.put(FIELD_INVITATION_ID, invitation != null ? invitation.getId() : JSONObject.NULL);
    result.put(FIELD_INVITATION_STATUS,
        invitation != null ? invitation.getStatus() : JSONObject.NULL);
    return result;
  }

  /**
   * Forces {@code ETGO_INVITATION.STATUS} to an arbitrary enum value, for exercising the
   * frontend's pending-invitation pill states (ETP-4830) without waiting on real email delivery.
   *
   * @param invitationId {@code ETGO_INVITATION} id; takes priority over {@code email} when both
   *     are given
   * @param email resolves the most recently created invitation for this email when
   *     {@code invitationId} is blank
   * @param status one of {@code PENDING}/{@code SENT}/{@code ACCEPTED}/{@code EXPIRED}/
   *     {@code REVOKED}/{@code DELIVERY_FAILED}
   * @return response JSON, {@code success:false} with a {@code message} on a validation failure
   * @throws JSONException when the response cannot be serialized
   */
  public JSONObject forceStatus(String invitationId, String email, String status)
      throws JSONException {
    String normalizedStatus = StringUtils.trimToEmpty(status).toUpperCase(Locale.ROOT);
    if (!VALID_STATUSES.contains(normalizedStatus)) {
      return failure("Status must be one of " + VALID_STATUSES);
    }

    Invitation invitation = resolveInvitation(invitationId, email);
    if (invitation == null) {
      return failure("No matching invitation found");
    }

    invitation.setStatus(normalizedStatus);
    OBDal.getInstance().save(invitation);
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();

    JSONObject result = new JSONObject();
    result.put(FIELD_SUCCESS, true);
    result.put(FIELD_INVITATION_ID, invitation.getId());
    result.put(FIELD_EMAIL, invitation.getEmail());
    result.put(FIELD_STATUS, invitation.getStatus());
    return result;
  }

  private Invitation resolveInvitation(String invitationId, String email) {
    String trimmedId = StringUtils.trimToNull(invitationId);
    if (trimmedId != null) {
      return OBDal.getInstance().get(Invitation.class, trimmedId);
    }
    String normalizedEmail = StringUtils.trimToNull(email);
    return normalizedEmail == null ? null : findLatestInvitationForEmail(normalizedEmail);
  }

  private Invitation findLatestInvitationForEmail(String email) {
    List<Invitation> invitations = CompanyInvitationDalHelper.findInvitationsForEmail(email);
    return invitations.isEmpty() ? null : invitations.get(0);
  }

  private String resolveEmail(String email, String adUserId) {
    String trimmedEmail = StringUtils.trimToNull(email);
    if (trimmedEmail != null) {
      return trimmedEmail.toLowerCase(Locale.ROOT);
    }
    String trimmedUserId = StringUtils.trimToNull(adUserId);
    if (trimmedUserId == null) {
      return null;
    }
    User user = OBDal.getInstance().get(User.class, trimmedUserId);
    if (user == null) {
      return null;
    }
    String userEmail = StringUtils.trimToNull(user.getEmail());
    return userEmail != null ? userEmail.toLowerCase(Locale.ROOT) : null;
  }

  private static boolean isActiveAccount(Account account) {
    return Boolean.TRUE.equals(account.isActive())
        && STATUS_ACTIVE.equalsIgnoreCase((String) account.get(Account.PROPERTY_STATUS));
  }

  /** Prefix guarantees {@link PasswordPolicy#isStrong} without regex-checking random content. */
  private static String generateTempPassword() {
    return "Aa1!" + CompanyInvitationService.generateToken().substring(0, 16);
  }

  private static JSONObject failure(String message) throws JSONException {
    JSONObject result = new JSONObject();
    result.put(FIELD_SUCCESS, false);
    result.put(FIELD_MESSAGE, message);
    return result;
  }
}
