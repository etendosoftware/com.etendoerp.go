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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Invitation;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;

/**
 * Service managing company user invitations (ETP-4894).
 *
 * Handles creation, public token resolution, existing-account acceptance, and new-account
 * registration & acceptance outside onboarding.
 */
public class CompanyInvitationService {

  private static final Logger log = LogManager.getLogger(CompanyInvitationService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int TOKEN_BYTES = 32;
  private static final long INVITATION_TTL_DAYS = 7;
  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_SENT = "SENT";
  private static final String STATUS_ACCEPTED = "ACCEPTED";
  private static final String STATUS_EXPIRED = "EXPIRED";
  private static final String STATUS_REVOKED = "REVOKED";
  private static final String STATUS_DELIVERY_FAILED = "DELIVERY_FAILED";
  private static final String FIELD_EMAIL = "email";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_EXPIRES_AT = "expiresAt";
  private static final String FIELD_CLIENT_NAME = "clientName";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_ERROR = "error";
  private static final String FIELD_HTTP_STATUS = "httpStatus";
  private static final String CODE_MISSING_TOKEN = "MISSING_TOKEN";
  private static final String CODE_INVALID_TOKEN = "INVALID_TOKEN";
  private static final String CODE_EXPIRED_TOKEN = "EXPIRED_TOKEN";
  private static final String MESSAGE_MISSING_TOKEN = "Invitation token is required";
  private static final String MESSAGE_INVALID_TOKEN = "Invalid or unknown invitation link";
  private static final String MESSAGE_EXPIRED_TOKEN = "This invitation link has expired or has been revoked";

  private final TransactionalAuthEmailSender authEmailSender;

  /** Creates a service using the default transactional email sender. */
  public CompanyInvitationService() {
    this(new TransactionalAuthEmailSender());
  }

  /**
   * Creates a service using the supplied email sender.
   *
   * @param authEmailSender sender used for invitation messages
   */
  public CompanyInvitationService(TransactionalAuthEmailSender authEmailSender) {
    this.authEmailSender = authEmailSender;
  }

  /**
   * Creates an invitation for a company administrator to invite a user by email.
   *
   * @param inviterAccount authenticated account of the inviter
   * @param email Recipient email address
   * @param appBaseUrl Application base URL for building the invitation link
   * @param language Optional language preference
   * @return Response JSON object
   * @throws JSONException when the response cannot be serialized
   */
  public JSONObject createInvitation(Account inviterAccount, String email, String appBaseUrl,
      String language) throws JSONException {
    if (inviterAccount == null) {
      return errorResponse(401, "UNAUTHORIZED", "Authentication required to send invitations");
    }

    String normalizedEmail = StringUtils.trimToEmpty(email).toLowerCase(Locale.ROOT);
    if (normalizedEmail.isEmpty()) {
      return errorResponse(400, "MISSING_EMAIL", "Email address is required");
    }
    if (!EmailContractCommandSupport.isValidEmail(normalizedEmail)) {
      return errorResponse(400, "INVALID_EMAIL_FORMAT", "Invalid email format");
    }

    return createInvitationForInviter(resolveInviter(inviterAccount), normalizedEmail, appBaseUrl,
        language);
  }

  /**
   * Creates (and sends) a company invitation for a user an admin just created in the same
   * request (ETP-4830), replacing the old eager-pending-{@code etgo_account} provisioning:
   * {@code register-and-accept} is now the sole place an {@code etgo_account} row is created for
   * an admin-created user, and it already does that lazily at accept time. The inviter is
   * resolved from {@code obContext} (the request's {@code OBContext}, captured by the caller
   * before it is best-effort-wrapped in {@code OBContext.setAdminMode(true)}) rather than from an
   * authenticated {@code etgo_account} bearer token — this runs from a {@code NeoHandler}
   * post-hook on the {@code user} entity's {@code POST}, not from the public
   * {@code /sws/go/invitations} endpoint {@link #createInvitation} serves. Skips the
   * "invited user already has an active role" check (see the {@code requireExistingRole}
   * overload of {@link #createInvitationForInviter}); everything else — dedup of an already-open
   * invitation, throttling, token generation, and the {@code company-invitation} email send — is
   * identical to any other invitation.
   *
   * @param obContext the OB security context of the admin performing the creation
   * @param email the newly created user's email (invitation recipient)
   * @param appBaseUrl application base URL for building the invitation link, or blank to fall
   *     back to the configured default
   * @param language optional language preference
   * @return response JSON object (same shape as {@link #createInvitation})
   * @throws JSONException when the response cannot be serialized
   */
  public JSONObject createInvitationForNewlyCreatedUser(OBContext obContext, String email,
      String appBaseUrl, String language) throws JSONException {
    String normalizedEmail = StringUtils.trimToEmpty(email).toLowerCase(Locale.ROOT);
    if (normalizedEmail.isEmpty()) {
      return errorResponse(400, "MISSING_EMAIL", "Email address is required");
    }
    return createInvitationForInviter(resolveInviterFromContext(obContext), normalizedEmail,
        appBaseUrl, language, false);
  }

  /**
   * Returns the status of the most recently created invitation for {@code clientId}/{@code
   * email}, or {@code null} if none exists (ETP-4830). Backs the {@code user} NeoHandler's
   * {@code invitationStatus} field on GET responses, so the frontend can render a "pending
   * invite" badge without a separate round trip.
   *
   * @param clientId tenant client id scoping the lookup
   * @param email the {@code AD_User}'s email
   * @return one of {@code PENDING}, {@code SENT}, {@code ACCEPTED}, {@code EXPIRED},
   *     {@code REVOKED}, {@code DELIVERY_FAILED}, or {@code null} when no invitation was ever
   *     sent for this client/email
   */
  public static String findLatestInvitationStatus(String clientId, String email) {
    if (StringUtils.isBlank(clientId) || StringUtils.isBlank(email)) {
      return null;
    }
    Invitation invitation = CompanyInvitationDalHelper.findLatestInvitation(clientId,
        email.toLowerCase(Locale.ROOT));
    return invitation != null ? invitation.getStatus() : null;
  }

  private static InviterContext resolveInviterFromContext(OBContext obContext) {
    return obContext == null ? null
        : new InviterContext(obContext.getCurrentClient(), obContext.getCurrentOrganization(),
            obContext.getUser());
  }

  private JSONObject createInvitationForInviter(InviterContext inviter, String email,
      String appBaseUrl, String language) throws JSONException {
    return createInvitationForInviter(inviter, email, appBaseUrl, language, true);
  }

  /**
   * @param requireExistingRole when {@code false}, skips the "invited user already has an
   *     active role in the invitation organization" check — used only by
   *     {@link #createInvitationForNewlyCreatedUser} (ETP-4830): a freshly admin-created
   *     {@code AD_User} has zero roles yet by construction (role assignment happens later, via
   *     {@code AssignTemplateRolesControl}'s own save/PUT), so the check would always 400 there
   *     and adds no real safety — the user unambiguously belongs to the inviter's client/org
   *     because it was just created inside the same request.
   */
  private JSONObject createInvitationForInviter(InviterContext inviter, String email,
      String appBaseUrl, String language, boolean requireExistingRole) throws JSONException {
    if (inviter == null || inviter.client == null || "0".equals(inviter.client.getId())) {
      return errorResponse(403, "FORBIDDEN",
          "Inviter does not have company administration permissions");
    }

    Organization invitationOrganization = inviter.org != null ? inviter.org
        : OBDal.getInstance().get(Organization.class, "0");
    User invitedUser = CompanyInvitationDalHelper.findUserForClientEmail(inviter.client, email);
    if (invitedUser == null) {
      return errorResponse(400, "INVITED_USER_NOT_FOUND",
          "Create the AD_USER and assign its organization roles before sending the invitation");
    }
    if (requireExistingRole && !CompanyInvitationDalHelper.hasActiveRoleForOrganization(
        invitedUser, invitationOrganization)) {
      return errorResponse(400, "INVITED_USER_NO_ROLE",
          "The AD_USER must have an active role assigned to the invitation organization");
    }

    Invitation existing = CompanyInvitationDalHelper.findOpenInvitation(inviter.client.getId(),
        email);
    if (existing != null && (existing.getExpiresAt() == null
        || existing.getExpiresAt().after(new Date()))) {
      return existingInvitationResponse(existing);
    }

    String rawToken = generateToken();
    Date expiresAt = Date.from(Instant.now().plus(INVITATION_TTL_DAYS, ChronoUnit.DAYS));
    Invitation invitation = persistInvitation(inviter, invitationOrganization, invitedUser, email,
        hashToken(rawToken), expiresAt);
    String baseUrl = StringUtils.isNotBlank(appBaseUrl) ? appBaseUrl
        : PublicUrlResolver.resolveConfiguredAppBaseUrl();
    String inviteLink = PublicUrlResolver.appendPath(baseUrl, "invite") + "?token=" + rawToken;
    boolean sent = sendInvitation(invitation, inviteLink, language);
    invitation.setStatus(sent ? STATUS_SENT : STATUS_DELIVERY_FAILED);
    OBDal.getInstance().save(invitation);
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();
    return invitationResponse(invitation);
  }

  private JSONObject existingInvitationResponse(Invitation existing) throws JSONException {
    JSONObject invitationJson = invitationResponseJson(existing);
    JSONObject result = new JSONObject();
    result.put(FIELD_STATUS, FIELD_SUCCESS);
    result.put(FIELD_MESSAGE, "An invitation is already pending for this email");
    result.put("invitation", invitationJson);
    return result;
  }

  private Invitation persistInvitation(InviterContext inviter, Organization organization,
      User invitedUser, String email, String tokenHash, Date expiresAt) {
    Invitation invitation = OBProvider.getInstance().get(Invitation.class);
    invitation.setClient(inviter.client);
    invitation.setOrganization(organization);
    invitation.setUser(invitedUser);
    invitation.setEmail(email);
    invitation.setTokenHash(tokenHash);
    invitation.setStatus(STATUS_PENDING);
    invitation.setExpiresAt(expiresAt);
    if (inviter.user != null) {
      invitation.setCreatedBy(inviter.user);
      invitation.setUpdatedBy(inviter.user);
    }
    OBDal.getInstance().save(invitation);
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();
    return invitation;
  }

  private boolean sendInvitation(Invitation invitation, String inviteLink, String language) {
    try {
      return authEmailSender.sendCompanyInvitation(invitation, inviteLink, language);
    } catch (RuntimeException e) {
      log.warn("Company invitation email failed to send", e);
      return false;
    }
  }

  private JSONObject invitationResponse(Invitation invitation) throws JSONException {
    JSONObject result = new JSONObject();
    result.put(FIELD_STATUS, FIELD_SUCCESS);
    result.put("invitation", invitationResponseJson(invitation));
    return result;
  }

  private JSONObject invitationResponseJson(Invitation invitation) throws JSONException {
    JSONObject result = new JSONObject();
    result.put("id", invitation.getId());
    result.put(FIELD_EMAIL, invitation.getEmail());
    result.put(FIELD_STATUS, invitation.getStatus());
    if (invitation.getExpiresAt() != null) {
      result.put(FIELD_EXPIRES_AT, invitation.getExpiresAt().toInstant().toString());
    }
    return result;
  }

  /**
   * Lists invitations addressed to the authenticated Etendo Go account.
   *
   * <p>The account email is resolved server-side from the bearer token. Client and organization
   * filters are intentionally disabled because an account can receive invitations from multiple
   * companies, while the email predicate prevents disclosure of another account's invitations.
   *
   * @param account authenticated Etendo Go account resolved by the servlet
   * @return the invitations addressed to that account
   * @throws JSONException when the response cannot be serialized
   */
  public JSONObject listInvitationsForAccount(Account account) throws JSONException {
    if (account == null || StringUtils.isBlank(account.getEmail())) {
      return errorResponse(401, "AUTHENTICATION_REQUIRED", "Authentication required");
    }

    String email = account.getEmail().trim().toLowerCase(Locale.ROOT);
    JSONArray invitations = new JSONArray();
    for (Invitation invitation : CompanyInvitationDalHelper.findInvitationsForEmail(email)) {
      JSONObject item = new JSONObject();
      item.put("id", invitation.getId());
      item.put(FIELD_EMAIL, invitation.getEmail());
      item.put(FIELD_STATUS, invitation.getStatus());
      item.put(FIELD_CLIENT_NAME, invitation.getClient() == null ? ""
          : invitation.getClient().getName());
      if (invitation.getExpiresAt() != null) {
        item.put(FIELD_EXPIRES_AT, invitation.getExpiresAt().toInstant().toString());
      }
      invitations.put(item);
    }

    JSONObject result = new JSONObject();
    result.put(FIELD_STATUS, FIELD_SUCCESS);
    result.put("invitations", invitations);
    return result;
  }

  /**
   * Resolves a public invitation link safely without leaking unauthorized account details.
   *
   * @param rawToken Bearer token from the email link
   * @return Response JSON containing public display info and resolution branch
   * @throws JSONException when the response cannot be serialized
   */
  public JSONObject resolveInvitation(String rawToken) throws JSONException {
    if (StringUtils.isBlank(rawToken)) {
      return errorResponse(400, CODE_MISSING_TOKEN, MESSAGE_MISSING_TOKEN);
    }

    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    try {
      String tokenHash = hashToken(rawToken);
      Invitation invitation = CompanyInvitationDalHelper.findInvitationByTokenHash(tokenHash);
      if (invitation == null) {
        return errorResponse(404, CODE_INVALID_TOKEN, MESSAGE_INVALID_TOKEN);
      }

      String companyName = invitation.getClient() != null ? invitation.getClient().getName() : "";
      String email = invitation.getEmail();
      String maskedEmail = maskEmail(email);

      if (STATUS_ACCEPTED.equalsIgnoreCase(invitation.getStatus())) {
        JSONObject result = new JSONObject();
        result.put(FIELD_STATUS, STATUS_ACCEPTED);
        result.put(FIELD_CLIENT_NAME, companyName);
        result.put(FIELD_EMAIL, maskedEmail);
        result.put("branch", "accepted");
        return result;
      }

      if (STATUS_REVOKED.equalsIgnoreCase(invitation.getStatus())
          || STATUS_EXPIRED.equalsIgnoreCase(invitation.getStatus())
          || (invitation.getExpiresAt() != null && invitation.getExpiresAt().before(new Date()))) {
        return errorResponse(400, CODE_EXPIRED_TOKEN, MESSAGE_EXPIRED_TOKEN);
      }

      Account account = EtendoGoJwtDalHelper.findActiveAccountByEmail(email);
      boolean accountExists = account != null && Boolean.TRUE.equals(account.isActive())
          && "active".equalsIgnoreCase((String) account.get(FIELD_STATUS));
      String branch = accountExists ? "existing_account" : "registration_required";

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, invitation.getStatus());
      result.put(FIELD_CLIENT_NAME, companyName);
      result.put(FIELD_EMAIL, email);
      result.put("maskedEmail", maskedEmail);
      if (invitation.getExpiresAt() != null) {
        result.put(FIELD_EXPIRES_AT, invitation.getExpiresAt().toInstant().toString());
      }
      result.put("accountExists", accountExists);
      result.put("branch", branch);
      return result;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Accepts an invitation for an existing Etendo Go account holder.
   *
   * @param rawToken Bearer token from the invitation
   * @param accountBearerToken authenticated Etendo Go account session
   * @return Response JSON
   * @throws JSONException when the response cannot be serialized
   */
  public JSONObject acceptExistingAccount(String rawToken, String accountBearerToken)
      throws JSONException {
    if (StringUtils.isBlank(rawToken)) {
      return errorResponse(400, CODE_MISSING_TOKEN, MESSAGE_MISSING_TOKEN);
    }
    if (StringUtils.isBlank(accountBearerToken)) {
      return errorResponse(401, "AUTHENTICATION_REQUIRED",
          "Sign in with the invited Etendo Go account before accepting");
    }

    return withAdminMode(() -> acceptExistingAccountInAdminMode(rawToken, accountBearerToken));
  }

  private JSONObject acceptExistingAccountInAdminMode(String rawToken, String accountBearerToken)
      throws JSONException {
    Invitation invitation = findInvitation(rawToken);
    if (invitation == null) {
      return errorResponse(404, CODE_INVALID_TOKEN, MESSAGE_INVALID_TOKEN);
    }

    String companyName = invitation.getClient() != null ? invitation.getClient().getName() : "";
    JSONObject acceptedResponse = acceptedInvitationResponse(invitation, companyName);
    if (acceptedResponse != null) {
      return acceptedResponse;
    }
    if (isClosedInvitation(invitation)) {
      return errorResponse(400, CODE_EXPIRED_TOKEN, MESSAGE_EXPIRED_TOKEN);
    }

      Account account = EtendoGoJwtDalHelper.findActiveAccountByEmail(invitation.getEmail());
      Account authenticatedAccount = EtendoGoJwtDalHelper
          .findActiveAccountByBearerToken(accountBearerToken);
      if (authenticatedAccount == null || !StringUtils.equalsIgnoreCase(
          authenticatedAccount.getEmail(), invitation.getEmail())) {
        return errorResponse(403, "INVITATION_ACCOUNT_MISMATCH",
            "The signed-in account does not match this invitation");
      }
      if (account == null || !StringUtils.equalsIgnoreCase(account.getId(), authenticatedAccount.getId())) {
        return errorResponse(400, "ACCOUNT_REQUIRED",
            "No active platform account found. Registration is required.");
      }

      User user = invitation.getUser();
      if (user == null || !Boolean.TRUE.equals(user.isActive())
          || !CompanyInvitationDalHelper.hasActiveRoleForOrganization(user,
              invitation.getOrganization())) {
        return errorResponse(409, "INVITATION_USER_CONFIGURATION_INVALID",
            "The invitation user or its organization role is no longer valid");
      }

      invitation.setEtgoAccount(account);
      invitation.setStatus(STATUS_ACCEPTED);
      OBDal.getInstance().save(invitation);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, FIELD_SUCCESS);
      result.put(FIELD_MESSAGE, "Invitation accepted successfully");
      result.put(FIELD_CLIENT_NAME, companyName);
    return result;
  }

  /**
   * Registers a minimal platform account bound strictly to the invitation email, then accepts it.
   *
   * @param rawToken Bearer token
   * @param name Account holder display name
   * @param password Account password
   * @return Response JSON with session token and account data
   * @throws JSONException when the response cannot be serialized
   */
  public JSONObject registerAndAccept(String rawToken, String name, String password)
      throws JSONException {
    if (StringUtils.isBlank(rawToken)) {
      return errorResponse(400, CODE_MISSING_TOKEN, MESSAGE_MISSING_TOKEN);
    }

    String trimmedName = StringUtils.trimToEmpty(name);
    if (trimmedName.isEmpty()) {
      return errorResponse(400, "MISSING_NAME", "Full name is required");
    }
    if (StringUtils.isBlank(password) || !PasswordPolicy.isStrong(password)) {
      return errorResponse(400, "WEAK_PASSWORD", PasswordPolicy.USER_MESSAGE);
    }

    return withAdminMode(() -> registerAndAcceptInAdminMode(rawToken, trimmedName, password));
  }

  private JSONObject registerAndAcceptInAdminMode(String rawToken, String trimmedName,
      String password) throws JSONException {
    Invitation invitation = findInvitation(rawToken);
    if (invitation == null) {
      return errorResponse(404, CODE_INVALID_TOKEN, MESSAGE_INVALID_TOKEN);
    }

    String companyName = invitation.getClient() != null ? invitation.getClient().getName() : "";
    JSONObject acceptedResponse = acceptedInvitationResponse(invitation, companyName);
    if (acceptedResponse != null) {
      return acceptedResponse;
    }
    if (isClosedInvitation(invitation)) {
      return errorResponse(400, CODE_EXPIRED_TOKEN, MESSAGE_EXPIRED_TOKEN);
    }

      String email = invitation.getEmail();
      Account account = EtendoGoJwtDalHelper.findActiveAccountByEmail(email);
      String sessionToken = generateToken();
      String passwordHash = PasswordHasher.hash(password);

      if (account == null) {
        account = EtendoGoJwtDalHelper.createAccount(email, passwordHash, trimmedName, sessionToken);
      } else {
        account.setPasswordHash(passwordHash);
        account.setName(trimmedName);
        account.setSessionToken(sessionToken);
        account.set(Account.PROPERTY_STATUS, "active");
        OBDal.getInstance().save(account);
        OBDal.getInstance().flush();
      }

      User user = invitation.getUser();
      if (user == null || !Boolean.TRUE.equals(user.isActive())
          || !CompanyInvitationDalHelper.hasActiveRoleForOrganization(user,
              invitation.getOrganization())) {
        return errorResponse(409, "INVITATION_USER_CONFIGURATION_INVALID",
            "The invitation user or its organization role is no longer valid");
      }

      invitation.setEtgoAccount(account);
      invitation.setStatus(STATUS_ACCEPTED);
      OBDal.getInstance().save(invitation);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();

      JSONObject accountJson = new JSONObject();
      accountJson.put("id", account.getId());
      accountJson.put(FIELD_EMAIL, account.getEmail());
      accountJson.put("name", account.getName());

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, FIELD_SUCCESS);
      result.put("token", sessionToken);
      result.put("account", accountJson);
      result.put(FIELD_CLIENT_NAME, companyName);
    return result;
  }

  // --- Internal helpers ---

  @FunctionalInterface
  private interface InvitationOperation {
    JSONObject execute() throws JSONException;
  }

  private JSONObject withAdminMode(InvitationOperation operation) throws JSONException {
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    try {
      return operation.execute();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private Invitation findInvitation(String rawToken) {
    return CompanyInvitationDalHelper.findInvitationByTokenHash(hashToken(rawToken));
  }

  private boolean isClosedInvitation(Invitation invitation) {
    return STATUS_REVOKED.equalsIgnoreCase(invitation.getStatus())
        || STATUS_EXPIRED.equalsIgnoreCase(invitation.getStatus())
        || (invitation.getExpiresAt() != null && invitation.getExpiresAt().before(new Date()));
  }

  private JSONObject acceptedInvitationResponse(Invitation invitation, String companyName)
      throws JSONException {
    if (!STATUS_ACCEPTED.equalsIgnoreCase(invitation.getStatus())) {
      return null;
    }
    JSONObject result = new JSONObject();
    result.put(FIELD_STATUS, FIELD_SUCCESS);
    result.put(FIELD_MESSAGE, "Invitation already accepted");
    result.put(FIELD_CLIENT_NAME, companyName);
    return result;
  }

  static String generateToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hashToken(String token) {
    try {
      return Base64.getEncoder().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required for tokens", e);
    }
  }

  static String maskEmail(String email) {
    if (StringUtils.isBlank(email) || !email.contains("@")) {
      return email;
    }
    int atIndex = email.indexOf('@');
    String local = email.substring(0, atIndex);
    String domain = email.substring(atIndex);
    if (local.length() == 1) {
      return local.charAt(0) + "***" + domain;
    }
    return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
  }

  private static InviterContext resolveInviter(Account account) {
    if (account != null) {
      // Find active ERP user associated with this account
      OBQuery<User> query = OBDal.getInstance().createQuery(User.class,
          "as u where (lower(u.email) = :email or lower(u.username) = :email) and u.client.id <> '0' and u.active = true");
      query.setNamedParameter(FIELD_EMAIL, account.getEmail().toLowerCase(Locale.ROOT));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      List<User> users = query.list();
      if (!users.isEmpty()) {
        User user = users.get(0);
        return new InviterContext(user.getClient(), user.getOrganization(), user);
      }
    }
    return null;
  }

  private static JSONObject errorResponse(int status, String code, String message)
      throws JSONException {
    JSONObject json = new JSONObject();
    json.put(FIELD_ERROR, true);
    json.put("code", code);
    json.put(FIELD_MESSAGE, message);
    json.put(FIELD_HTTP_STATUS, status);
    return json;
  }

  private static class InviterContext {
    final Client client;
    final Organization org;
    final User user;

    InviterContext(Client client, Organization org, User user) {
      this.client = client;
      this.org = org;
      this.user = user;
    }
  }
}
