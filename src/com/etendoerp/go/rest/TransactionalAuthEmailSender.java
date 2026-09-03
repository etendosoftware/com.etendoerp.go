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

import java.time.Instant;

import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Invitation;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

class TransactionalAuthEmailSender {

  private static final Logger log = LogManager.getLogger(TransactionalAuthEmailSender.class);

  private static final String CONTRACT_COMPANY_INVITATION = "company-invitation";
  private static final String CONTRACT_ENVIRONMENT_READY = "environment-ready";
  private static final String CONTRACT_NEW_ACCOUNT = "new-account";
  private static final String CONTRACT_NEW_ACCOUNT_INVITEE = "new-account-invitee";
  private static final String CONTRACT_ORGANIZATION_JOINED = "organization-joined";
  private static final String CONTRACT_PASSWORD_CHANGED = "password-changed";
  private static final String CONTRACT_PASSWORD_ADDED = "password-added";
  private static final String CONTRACT_AUTH_METHOD_REMOVED = "auth-method-removed";
  private static final String CONTRACT_RESET_PASSWORD = "reset-password";
  private static final String CONTRACT_SET_PASSWORD = "set-password";
  private static final String CONTRACT_VERIFY_EMAIL = "verify-email";

  private final TransactionalEmailService emailService;

  TransactionalAuthEmailSender() {
    this(new TransactionalEmailService());
  }

  TransactionalAuthEmailSender(TransactionalEmailService emailService) {
    this.emailService = emailService;
  }

  boolean sendNewAccount(Account account) {
    return sendNewAccount(account, null, null);
  }

  boolean sendNewAccount(Account account, String language) {
    return sendNewAccount(account, language, null);
  }

  /**
   * Welcome email for a freshly registered account.
   *
   * <p>ETP-4798 folds the email confirmation into this one message instead of adding a second
   * mail at registration: when a verification link is available it replaces the plain
   * {@code /onboarding} link, so the recipient confirms the address by following the same "continue
   * here" call to action they already got. Two separate mails arriving together would compete for
   * the same click and double the chance of one landing in spam.
   *
   * <p>A null {@code verifyLink} (no public app base URL configured, or no token issued) degrades
   * to the previous behaviour rather than skipping the mail.
   *
   * @param verifyLink email-verification link, or null to fall back to the plain onboarding link
   */
  boolean sendNewAccount(Account account, String language, String verifyLink, Date expiresAt) {
    if (account == null) {
      return false;
    }
    String link = StringUtils.isNotBlank(verifyLink) ? verifyLink
        : EtendoGoAuthLinkBuilder.onboardingLink();
    return sendAccountLink(CONTRACT_NEW_ACCOUNT, account, link, null, language, expiresAt);
  }

  boolean sendNewAccount(Account account, String language, String verifyLink) {
    if (account == null) {
      return false;
    }
    String link = StringUtils.isNotBlank(verifyLink) ? verifyLink
        : EtendoGoAuthLinkBuilder.onboardingLink();
    return sendAccountLink(CONTRACT_NEW_ACCOUNT, account, link, null, language);
  }

  /**
   * Standalone "confirm your email" message, used when the account holder asks for the link again.
   * It is a distinct contract from {@code new-account} on purpose: the copy is a reminder rather
   * than a welcome, and it gets its own throttle budget so re-sends cannot exhaust the welcome
   * allowance (or vice versa).
   */
  boolean sendVerifyEmail(Account account, String verifyTokenHash, String verifyLink) {
    return sendVerifyEmail(account, verifyTokenHash, verifyLink, null);
  }

  boolean sendVerifyEmail(Account account, String verifyTokenHash, String verifyLink,
      String language) {
    if (account == null || StringUtils.isBlank(verifyTokenHash)
        || StringUtils.isBlank(verifyLink)) {
      return false;
    }
    return sendVerifyEmail(account, verifyTokenHash, verifyLink, language, null);
  }

  /**
   * Sends the email-verification message, stating the token's real window.
   *
   * @param account the account being verified
   * @param verifyTokenHash hash of the issued token, used as the record id
   * @param verifyLink the verification link
   * @param language the recipient language
   * @param expiresAt when the token stops working; the copy states the remaining hours and omits
   *     the claim when unknown
   * @return whether the email was accepted for delivery
   */
  boolean sendVerifyEmail(Account account, String verifyTokenHash, String verifyLink,
      String language, Date expiresAt) {
    return sendAccountLink(CONTRACT_VERIFY_EMAIL, account, verifyLink, verifyTokenHash, language,
        expiresAt);
  }

  /**
   * Welcome email for a user who created the account by accepting an invitation.
   *
   * <p>A separate contract from {@link #sendNewAccount}, because the two welcomes ask for different
   * things. The standard one now carries the email-verification link; an invited operator has
   * nothing to verify — the invitation is itself the proof that somebody meant to reach this
   * address — and never runs onboarding, so its button goes to the dashboard.</p>
   *
   * @param account the account just created
   * @param language the recipient language
   * @return whether the email was accepted for delivery
   */
  boolean sendNewAccountForInvitee(Account account, String language) {
    if (account == null) {
      return false;
    }
    return sendAccountLink(CONTRACT_NEW_ACCOUNT_INVITEE, account,
        EtendoGoAuthLinkBuilder.dashboardLink(), null, language);
  }

  /**
   * Tells a user they now belong to an organization, sent once an invitation is accepted.
   *
   * @param account the accepting account
   * @param companyName the organization the user joined
   * @param invitationId the invitation record, used as the idempotency record id
   * @param language the recipient language
   * @return whether the email was accepted for delivery
   */
  boolean sendOrganizationJoined(Account account, String companyName, String invitationId,
      String language) {
    if (account == null || StringUtils.isBlank(companyName)) {
      return false;
    }
    try {
      JSONObject body = baseCommand(account);
      body.put("companyName", companyName);
      if (StringUtils.isNotBlank(invitationId)) {
        body.put(EmailContractCommandSupport.FIELD_RECORD_ID, invitationId);
      }
      addLanguageField(body, language);
      return sendBestEffort(CONTRACT_ORGANIZATION_JOINED, body);
    } catch (JSONException e) {
      log.warn("Could not build organization-joined email command", e);
      return false;
    }
  }

  boolean sendEnvironmentReady(Account account, String clientId) {
    return sendEnvironmentReady(account, clientId, null);
  }

  boolean sendEnvironmentReady(Account account, String clientId, String language) {
    if (account == null) {
      return false;
    }
    try {
      JSONObject body = baseCommand(account, clientId);
      addLanguageField(body, language);
      String dashboardLink = EtendoGoAuthLinkBuilder.dashboardLink();
      if (dashboardLink != null) {
        body.put(EmailContractCommandSupport.FIELD_LINK, dashboardLink);
      }
      body.put(EmailContractCommandSupport.FIELD_RECORD_ID, clientId);
      return sendBestEffort(CONTRACT_ENVIRONMENT_READY, body);
    } catch (JSONException e) {
      log.warn("Could not build environment-ready email command", e);
      return false;
    }
  }

  boolean sendPasswordReset(Account account, String resetTokenHash, String resetLink) {
    return sendPasswordReset(account, resetTokenHash, resetLink, null);
  }

  /**
   * Sends the password-reset email, stating the token's real expiry.
   *
   * @param account the account requesting the reset
   * @param resetTokenHash hash of the issued token, used as the record id
   * @param resetLink the reset link
   * @param expiresAt when the token stops working; the email states the remaining window and omits
   *     it when unknown
   * @return whether the email was accepted for delivery
   */
  boolean sendPasswordReset(Account account, String resetTokenHash, String resetLink,
      Date expiresAt) {
    if (account == null || StringUtils.isBlank(resetTokenHash) || StringUtils.isBlank(resetLink)) {
      return false;
    }
    return sendAccountLink(CONTRACT_RESET_PASSWORD, account, resetLink, resetTokenHash, null,
        expiresAt);
  }

  /**
   * Sends the set-password email: the same link as a reset, worded for somebody who has no password
   * yet rather than one who forgot theirs.
   *
   * <p>ETP-5115. An account created through an identity provider has no local password, so a reset
   * request used to skip the send entirely while the screen still said a link had gone out — the
   * account had no way to recover and no way to give itself one. It gets this instead. The link and
   * the token are identical to a reset; only the copy differs, because telling someone we received
   * a request to <em>reset</em> a password they never had is how you make a working flow read like
   * a bug.
   *
   * @param account the account requesting the reset
   * @param resetTokenHash hash of the issued token, used as the record id
   * @param resetLink the link that lets the account choose its first password
   * @param expiresAt when the token stops working; the email states the remaining window and omits
   *     it when unknown
   * @return whether the email was accepted for delivery
   */
  boolean sendSetPassword(Account account, String resetTokenHash, String resetLink, Date expiresAt) {
    if (account == null || StringUtils.isBlank(resetTokenHash) || StringUtils.isBlank(resetLink)) {
      return false;
    }
    return sendAccountLink(CONTRACT_SET_PASSWORD, account, resetLink, resetTokenHash, null,
        expiresAt);
  }

  boolean sendCompanyInvitation(Invitation invitation, String inviteLink) {
    return sendCompanyInvitation(invitation, inviteLink, null);
  }

  boolean sendCompanyInvitation(Invitation invitation, String inviteLink, String language) {
    if (invitation == null || StringUtils.isBlank(inviteLink)) {
      return false;
    }
    try {
      JSONObject body = new JSONObject();
      body.put(EmailContractCommandSupport.FIELD_VERSION, EmailContractCommandSupport.VERSION);
      body.put(EmailContractCommandSupport.FIELD_RECORD_ID, invitation.getId());
      body.put(EmailContractCommandSupport.FIELD_TENANT_ID,
          invitation.getClient() != null ? invitation.getClient().getId() : "0");
      body.put(EmailContractCommandSupport.FIELD_LINK, inviteLink);
      addLanguageField(body, language);
      return sendBestEffort(CONTRACT_COMPANY_INVITATION, body);
    } catch (JSONException e) {
      log.warn("Could not build company-invitation email command", e);
      return false;
    }
  }

  /**
   * Notifies that a password was added to an account that had none.
   *
   * <p>ETP-5115. Separate from {@code password-changed} on purpose: telling somebody their password
   * "was changed" when they just created their first one reads as though something happened to a
   * credential they did not have, which is exactly the alarm this mail exists to avoid raising
   * falsely. Like its sibling it carries a per-send record id so two operations in a row are not
   * collapsed into one by the duplicate check.
   *
   * @param account the account that now has a local password
   * @return whether the email was accepted for delivery
   */
  boolean sendPasswordAdded(Account account) {
    if (account == null) {
      return false;
    }
    try {
      JSONObject body = baseCommand(account);
      addLanguageField(body, null);
      body.put(EmailContractCommandSupport.FIELD_DATE, Instant.now().toString());
      body.put(EmailContractCommandSupport.FIELD_RECORD_ID,
          account.getId() + ":" + java.util.UUID.randomUUID());
      return sendBestEffort(CONTRACT_PASSWORD_ADDED, body);
    } catch (JSONException e) {
      log.warn("Could not build password-added email command", e);
      return false;
    }
  }

  /**
   * Notifies that a way of signing in was removed from an account.
   *
   * <p>ETP-5115. Deliberately does not name which one. The copy is one catalog entry and naming the
   * method would mean interpolating it, which this contract shape does not carry — and the value of
   * the notice does not depend on it: what the owner needs to know is that the ways into their
   * account changed without them, and the remedy is the same either way. Naming it would be an
   * improvement, not a prerequisite.
   *
   * @param account the account a method was removed from
   * @return whether the email was accepted for delivery
   */
  boolean sendAuthMethodRemoved(Account account) {
    if (account == null) {
      return false;
    }
    try {
      JSONObject body = baseCommand(account);
      addLanguageField(body, null);
      body.put(EmailContractCommandSupport.FIELD_DATE, Instant.now().toString());
      body.put(EmailContractCommandSupport.FIELD_RECORD_ID,
          account.getId() + ":" + java.util.UUID.randomUUID());
      return sendBestEffort(CONTRACT_AUTH_METHOD_REMOVED, body);
    } catch (JSONException e) {
      log.warn("Could not build auth-method-removed email command", e);
      return false;
    }
  }

  boolean sendPasswordChanged(Account account) {
    return sendPasswordChanged(account, null);
  }

  boolean sendPasswordChanged(Account account, String language) {
    if (account == null) {
      return false;
    }
    try {
      JSONObject body = baseCommand(account);
      addLanguageField(body, language);
      body.put(EmailContractCommandSupport.FIELD_DATE, Instant.now().toString());
      body.put(EmailContractCommandSupport.FIELD_RECORD_ID,
          account.getId() + ":" + java.util.UUID.randomUUID());
      return sendBestEffort(CONTRACT_PASSWORD_CHANGED, body);
    } catch (JSONException e) {
      log.warn("Could not build password-changed email command", e);
      return false;
    }
  }

  private boolean sendAccountLink(String contractName, Account account, String link,
      String recordId) {
    return sendAccountLink(contractName, account, link, recordId, null);
  }

  private boolean sendAccountLink(String contractName, Account account, String link,
      String recordId, String language) {
    return sendAccountLink(contractName, account, link, recordId, language, null);
  }

  private boolean sendAccountLink(String contractName, Account account, String link,
      String recordId, String language, Date expiresAt) {
    if (account == null || link == null) {
      return false;
    }
    try {
      JSONObject body = baseCommand(account);
      body.put(EmailContractCommandSupport.FIELD_LINK, link);
      addLanguageField(body, language);
      if (recordId != null) {
        body.put(EmailContractCommandSupport.FIELD_RECORD_ID, recordId);
      }
      if (expiresAt != null) {
        // The contract states the remaining window in the copy; it must come from the TTL the
        // servlet actually applied, not from a number repeated in the message catalog.
        body.put("expiresAt", expiresAt.toInstant().toString());
      }
      return sendBestEffort(contractName, body);
    } catch (JSONException e) {
      log.warn("Could not build {} email command", contractName, e);
      return false;
    }
  }

  private static void addLanguageField(JSONObject body, String language) throws JSONException {
    String normalizedLanguage = StringUtils.trimToNull(language);
    if (normalizedLanguage != null) {
      body.put(EmailContractCommandSupport.FIELD_LANGUAGE, normalizedLanguage);
    }
  }

  private JSONObject baseCommand(Account account) throws JSONException {
    return baseCommand(account, account.getId());
  }

  private JSONObject baseCommand(Account account, String tenantId) throws JSONException {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_VERSION, EmailContractCommandSupport.VERSION);
    body.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, account.getId());
    // Local account emails can be sent before an environment client exists; use the account id
    // as the fallback throttle partition to avoid a single global auth-email bucket.
    body.put(EmailContractCommandSupport.FIELD_TENANT_ID,
        EmailContractCommandSupport.firstNonBlank(tenantId, account.getId()));
    return body;
  }

  private boolean sendBestEffort(String contractName, JSONObject body) {
    OBContext previousContext = OBContext.getOBContext();
    boolean adminModeSet = false;
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      adminModeSet = true;
      NeoResponse response = emailService.send(contractName, body);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      if (response != null && response.getHttpStatus() >= 400) {
        // The contract's own message is the only thing that says WHY it refused — the
        // observability sink records the status and the metrics, never the reason. Logging just
        // "HTTP 400" cost a full investigation once (a 400 that turned out to be an unresolved
        // invite link, indistinguishable from a bad recipient or a missing field): never drop it
        // again.
        log.warn("Transactional auth email {} finished with HTTP {}: {}", contractName,
            response.getHttpStatus(), describeFailure(response));
        return false;
      }
      return response != null;
    } catch (RuntimeException e) {
      EtendoGoDalHelper.rollbackDalChanges("transactional auth email", e, log);
      log.warn("Transactional auth email {} failed after the account transaction was committed",
          contractName, e);
      return false;
    } finally {
      if (adminModeSet) {
        OBContext.restorePreviousMode();
      }
      OBContext.setOBContext(previousContext);
    }
  }

  /**
   * Extracts the contract's failure reason from a NEO response for logging.
   *
   * @param response the response the email service returned (never {@code null} here)
   * @return the contract's {@code message}/{@code status}, or the raw body when neither is present
   */
  private static String describeFailure(NeoResponse response) {
    JSONObject body = response.getBody();
    if (body == null) {
      return "no response body";
    }
    String message = body.optString("message", null);
    String status = body.optString("status", null);
    if (StringUtils.isNotBlank(message)) {
      return StringUtils.isNotBlank(status) ? status + " - " + message : message;
    }
    return StringUtils.isNotBlank(status) ? status : body.toString();
  }
}
