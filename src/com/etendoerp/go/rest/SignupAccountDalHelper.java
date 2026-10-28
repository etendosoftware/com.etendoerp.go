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

import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.supportaccess.SupportAccessGuard;

/**
 * DAL access to the {@code ETGO_ACCOUNT} columns the signup API owns (ETP-5847): the signup state,
 * and the one-time login link mailed when the environment is ready.
 *
 * <p>Properties are read and written by name, as {@link EmailVerificationDalHelper} does, so this
 * class does not depend on the generated entity having been rebuilt. Every write commits: the
 * signup runs across a request and a background worker, and each step must be visible to the
 * other at once. Writes take an account loaded in the current session: a commit closes it, so a
 * caller re-reads the account before the next write.
 */
final class SignupAccountDalHelper {

  static final String PROPERTY_LOGIN_LINK_HASH = "loginLinkHash";
  static final String PROPERTY_LOGIN_LINK_EXPIRES = "loginLinkExpires";
  static final String PROPERTY_SIGNUP_CHANNEL = "signupChannel";
  static final String PROPERTY_SIGNUP_STATUS = "signupStatus";
  static final String PROPERTY_SIGNUP_ENVIRONMENT = "signupEnvironment";
  static final String PROPERTY_SIGNUP_ERROR = "signupError";
  static final String PROPERTY_SIGNUP_SEND_EMAILS = "signupSendEmails";
  static final String PROPERTY_SIGNUP_SOURCE = "signupSource";
  static final String PROPERTY_TERMS_ACCEPTED_AT = "termsAcceptedAt";

  static final String STATUS_PROVISIONING = "provisioning";
  static final String STATUS_READY = "ready";
  static final String STATUS_FAILED = "failed";

  /** {@code ETGO_ACCOUNT.SIGNUP_ERROR} is VARCHAR(2000). */
  private static final int ERROR_MAX_LENGTH = 2000;

  private static final String PARAM_ID = "id";
  private static final String PARAM_HASH = "hash";
  private static final String PARAM_NOW = "now";

  private SignupAccountDalHelper() {
  }

  /** What the signup API stores about an account it creates, besides the account itself. */
  record SignupData(String channel, boolean sendEmails, String source, Date termsAcceptedAt) {
  }

  /**
   * Creates a passwordless account in the {@code provisioning} state. No password and no
   * email-verification token: the address is proven when the login link mailed to it is used.
   *
   * @param onboardingDraft the company data, in the onboarding draft shape the provisioning reads
   */
  static Account createSignupAccount(String email, String name, String onboardingDraft,
      SignupData signup) {
    return EtendoGoJwtDalHelper.createAccount(email, null, name, null, onboardingDraft,
        account -> {
          account.set(PROPERTY_SIGNUP_CHANNEL, signup.channel());
          account.set(PROPERTY_SIGNUP_SEND_EMAILS, signup.sendEmails());
          account.set(PROPERTY_SIGNUP_SOURCE, signup.source());
          account.set(PROPERTY_TERMS_ACCEPTED_AT, signup.termsAcceptedAt());
          account.set(PROPERTY_SIGNUP_STATUS, STATUS_PROVISIONING);
        });
  }

  /**
   * The active account the signup API created with this id, or {@code null}: an account
   * registered by the app is never visible through the API.
   */
  static Account findSignupAccount(String accountId) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where account.id = :" + PARAM_ID + " and account.active = true"
            + " and account." + PROPERTY_SIGNUP_CHANNEL + " is not null");
    query.setNamedParameter(PARAM_ID, accountId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  static void markProvisioning(Account account) {
    account.set(PROPERTY_SIGNUP_STATUS, STATUS_PROVISIONING);
    account.set(PROPERTY_SIGNUP_ERROR, null);
    saveAndCommit(account);
  }

  static void markReady(Account account, String clientId) {
    account.set(PROPERTY_SIGNUP_STATUS, STATUS_READY);
    account.set(PROPERTY_SIGNUP_ENVIRONMENT, clientId);
    account.set(PROPERTY_SIGNUP_ERROR, null);
    saveAndCommit(account);
  }

  static void markFailed(Account account, String reason) {
    account.set(PROPERTY_SIGNUP_STATUS, STATUS_FAILED);
    account.set(PROPERTY_SIGNUP_ERROR, StringUtils.abbreviate(
        StringUtils.defaultIfBlank(reason, "Environment creation failed"), ERROR_MAX_LENGTH));
    saveAndCommit(account);
  }

  static String status(Account account) {
    return (String) account.get(PROPERTY_SIGNUP_STATUS);
  }

  static String environment(Account account) {
    return (String) account.get(PROPERTY_SIGNUP_ENVIRONMENT);
  }

  static String error(Account account) {
    return (String) account.get(PROPERTY_SIGNUP_ERROR);
  }

  static boolean sendsEmails(Account account) {
    return Boolean.TRUE.equals(account.get(PROPERTY_SIGNUP_SEND_EMAILS));
  }

  /**
   * Stores the hash of a fresh login link. Only the hash is persisted, as with every mailed token;
   * storing a new one replaces the previous, so the older link stops working.
   */
  static void storeLoginLink(Account account, String linkHash, Date expiresAt) {
    account.set(PROPERTY_LOGIN_LINK_HASH, linkHash);
    account.set(PROPERTY_LOGIN_LINK_EXPIRES, expiresAt);
    saveAndCommit(account);
  }

  static Account findAccountByLoginLinkHash(String linkHash, Date now) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where account." + PROPERTY_LOGIN_LINK_HASH + " = :" + PARAM_HASH
            + " and account." + PROPERTY_LOGIN_LINK_EXPIRES + " > :" + PARAM_NOW
            + " and account.active = true");
    query.setNamedParameter(PARAM_HASH, linkHash);
    query.setNamedParameter(PARAM_NOW, now);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    Account account = query.uniqueResult();
    return SupportAccessGuard.isSupportAccount(account) ? null : account;
  }

  /**
   * Spends the link: it works once. Also confirms the address, which receiving the link proved.
   * Unlike the email-verification token it is cleared, because this one opens a session.
   */
  static void consumeLoginLink(Account account, Date usedAt) {
    account.set(PROPERTY_LOGIN_LINK_HASH, null);
    account.set(PROPERTY_LOGIN_LINK_EXPIRES, null);
    if (account.get(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED) == null) {
      account.set(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED, usedAt);
    }
    saveAndCommit(account);
  }

  /**
   * The account's user in the environment the signup created, or {@code null} when that
   * environment is gone or the account has no user there.
   */
  static String environmentUserId(Account account) {
    String clientId = environment(account);
    if (clientId == null) {
      return null;
    }
    for (User user : EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail(account.getEmail())) {
      if (clientId.equals(user.getClient().getId())) {
        return user.getId();
      }
    }
    return null;
  }

  private static void saveAndCommit(Account account) {
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();
  }
}
