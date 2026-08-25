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

import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * ETP-4798 — DAL access for the email ownership confirmation issued at self-service registration.
 *
 * <p>Its own class rather than five more methods on {@link EtendoGoJwtDalHelper}, which already
 * carries every other account query and had reached the point where one more concern made it
 * unreadable (and tripped the method-count limit). Same split the invitation flow made with
 * {@code CompanyInvitationDalHelper}.
 *
 * <p>The column constants stay on {@link EtendoGoJwtDalHelper} because the SSO paths there write
 * them too — an SSO account is born confirmed, and signing in through the provider closes any
 * confirmation still pending on that address.
 */
final class EmailVerificationDalHelper {

  private static final String PARAM_VERIFY_TOKEN_HASH = "verifyTokenHash";
  private static final String PARAM_NOW = "now";

  private EmailVerificationDalHelper() {
  }

  /**
   * Stores the hash of a freshly issued email-verification token. Only the hash is persisted — the
   * clear token exists solely inside the link that goes out by mail, exactly like the
   * password-reset token, so a database dump does not let anyone verify someone else's address.
   * Issuing a new token replaces any token still pending, which is what makes "resend" safe: the
   * previous link stops working.
   */
  static void storeEmailVerifyToken(Account account, String verifyTokenHash, Date expiresAt) {
    account.set(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_HASH, verifyTokenHash);
    account.set(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_EXPIRES, expiresAt);
    OBDal.getInstance().save(account);
    flushAndCommit();
  }

  /**
   * Snapshots the verification token currently on the account, so a re-issue whose mail never
   * leaves the building can be undone.
   *
   * <p>Mirrors {@link EtendoGoJwtDalHelper#capturePasswordResetToken}, and exists for the same
   * reason: {@link #storeEmailVerifyToken} overwrites whatever was pending, and the overwrite has
   * to be reversible when the send that justified it fails.
   */
  static EmailVerifyTokenState captureEmailVerifyToken(Account account) {
    if (account == null) {
      return null;
    }
    return new EmailVerifyTokenState(
        (String) account.get(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_HASH),
        (Date) account.get(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_EXPIRES));
  }

  /**
   * Puts back the token captured by {@link #captureEmailVerifyToken}. A null state means the
   * account held no token, which restores to "no token" — the fail-open case a first issue at
   * {@code /register} must keep.
   */
  static void restoreEmailVerifyToken(Account account, EmailVerifyTokenState tokenState) {
    if (account == null) {
      return;
    }
    storeEmailVerifyToken(account,
        tokenState == null ? null : tokenState.verifyTokenHash,
        tokenState == null ? null : tokenState.verifyTokenExpires);
  }

  static Account findAccountByVerifyTokenHash(String verifyTokenHash, Date now) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where account." + EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_HASH + " = :"
            + PARAM_VERIFY_TOKEN_HASH
            + " and account." + EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_EXPIRES
            + " > :" + PARAM_NOW
            + " and account.active = true");
    query.setNamedParameter(PARAM_VERIFY_TOKEN_HASH, verifyTokenHash);
    query.setNamedParameter(PARAM_NOW, now);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  /**
   * Marks the address as proven.
   *
   * <p>Two deliberate differences from the password-reset equivalent. It does not clear the token
   * hash, so following the link again inside its TTL still resolves to this account and the
   * endpoint can answer 200 instead of "invalid token" — people re-click confirmation links, and
   * some mail clients fetch them unprompted. Once the verified timestamp is set the token grants
   * nothing, so there is nothing left to replay. And it does not touch the session token: the
   * account holder is usually signed in already (they registered, then clicked), and signing them
   * out here would dump them out of the onboarding they are in the middle of.
   */
  static void consumeEmailVerification(Account account, Date verifiedAt) {
    account.set(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED, verifiedAt);
    OBDal.getInstance().save(account);
    flushAndCommit();
  }

  static boolean isEmailVerified(Account account) {
    return account != null
        && account.get(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED) != null;
  }

  /**
   * True when this account owes an email confirmation: a verification token was issued for it and
   * never consumed.
   *
   * <p>The "token was issued" half is what keeps accounts that predate ETP-4798 working. They have
   * no verified timestamp and no token, so they read as "nothing pending" and are never gated — no
   * backfill migration, and no existing user locked out by the deploy. Every account created by
   * {@code /register} from now on gets a token stored immediately after the account commits, so it
   * is gated until the link is clicked.
   */
  static boolean isEmailVerificationPending(Account account) {
    return account != null
        && account.get(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED) == null
        && account.get(EtendoGoJwtDalHelper.PROPERTY_VERIFY_TOKEN_HASH) != null;
  }

  private static void flushAndCommit() {
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();
  }

  /** Immutable snapshot of the verification token columns, for the capture/restore pair above. */
  static final class EmailVerifyTokenState {
    private final String verifyTokenHash;
    private final Date verifyTokenExpires;

    private EmailVerifyTokenState(String verifyTokenHash, Date verifyTokenExpires) {
      this.verifyTokenHash = verifyTokenHash;
      this.verifyTokenExpires = verifyTokenExpires;
    }

    /** True when a confirmation was already pending before the re-issue that is being undone. */
    boolean hasToken() {
      return verifyTokenHash != null;
    }
  }
}
