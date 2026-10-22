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
import java.util.Base64;
import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * Account rules shared by the app's {@code /sws/go/register} and the public signup API
 * ({@code /sws/go/signup/v1}, ETP-5847): whether an address is taken, reserved company names, the
 * email-verification mail, and the mailed one-time tokens.
 *
 * <p>Public because the signup API lives in its own package; the DAL helpers it wraps stay
 * package-private. Callers own the OBContext: every method here expects the SYSTEM admin context
 * both servlets set before touching accounts.
 */
public class AccountRegistrationService {

  private static final Logger log = LogManager.getLogger(AccountRegistrationService.class);

  // 24h, not the 30 minutes a password reset gets. A reset is a deliberate act the user performs
  // and immediately waits on; a registration confirmation is often opened the next morning, and an
  // expired link there means a dead end in the middle of signup.
  static final long EMAIL_VERIFICATION_TTL_SECONDS = 24 * 60 * 60L;
  private static final String CONTRACT_NEW_ACCOUNT = "new-account";
  private static final String HASH_ALGORITHM = "SHA-256";

  /** What an email address already is, for a caller that has to tell the user. */
  public enum EmailStatus {
    /** No active account uses it. */
    AVAILABLE,
    /** An active account uses it and still owes the confirmation of its address. */
    PENDING_VERIFICATION,
    /** An active account uses it and nothing is pending: the owner can log in. */
    REGISTERED
  }

  private final TransactionalAuthEmailSender authEmailSender;

  /** Creates the service wired to the runtime transactional email sender. */
  public AccountRegistrationService() {
    this(new TransactionalAuthEmailSender());
  }

  AccountRegistrationService(TransactionalAuthEmailSender authEmailSender) {
    this.authEmailSender = authEmailSender;
  }

  /**
   * Tells whether the address is free, waiting for its confirmation, or taken.
   *
   * <p>Accounts that predate ETP-4798 have no verification token and read as
   * {@link EmailStatus#REGISTERED}: they were never gated, see
   * {@link EmailVerificationDalHelper#isEmailVerificationPending}.
   *
   * @param email the address, already trimmed and lower-cased
   * @return whether the address is free, waiting for its confirmation, or taken
   */
  public EmailStatus emailStatus(String email) {
    Account account = EtendoGoJwtDalHelper.findActiveAccountByEmail(email);
    if (account == null) {
      return EmailStatus.AVAILABLE;
    }
    return EmailVerificationDalHelper.isEmailVerificationPending(account)
        ? EmailStatus.PENDING_VERIFICATION
        : EmailStatus.REGISTERED;
  }

  /**
   * Whether a company name is reserved for a client that is still being provisioned
   * (ETP-5548), so a signup cannot claim it.
   *
   * @param companyName the name the signup asks for
   * @return {@code true} when the name has the shape of a provisional client name
   */
  public boolean isReservedCompanyName(String companyName) {
    return ProvisioningClientName.matches(companyName);
  }

  /**
   * ETP-4798: issues a fresh email-verification token and mails the link out, used both at
   * registration ({@code welcome} true, the link rides inside the welcome mail) and on an explicit
   * re-send ({@code welcome} false, its own {@code verify-email} message).
   *
   * <p><strong>Fails open only when there was nothing to fall back to.</strong> Issuing a token
   * overwrites whatever was pending, so the previous state is captured first and put back when the
   * mail does not go out. That distinction is the whole point: on a first issue at registration
   * there is no previous token, so a failed send leaves the account reading as "nothing pending"
   * and the onboarding gate lets it through — a misconfigured provider or an unset
   * {@code etendo.go.app.baseUrl} must not silently lock every new signup out of creating an
   * environment. On a re-send there IS a previous token, and restoring it keeps the account gated
   * and keeps the link already sitting in the user's inbox working. Clearing it there instead
   * would hand anyone a way to switch the gate off simply by pressing "resend" until the
   * per-recipient throttle refuses the send.
   *
   * @param welcome true to fold the link into the {@code new-account} welcome mail, false to send
   *     the standalone {@code verify-email} reminder
   */
  void issueEmailVerification(Account account, String language, boolean welcome) {
    EmailVerificationDalHelper.EmailVerifyTokenState previousTokenState =
        EmailVerificationDalHelper.captureEmailVerifyToken(account);
    boolean tokenStored = false;
    try {
      String verifyToken = generateSecureUrlToken();
      String verifyTokenHash = hashAuthToken(verifyToken);
      String verifyLink = EtendoGoAuthLinkBuilder.verifyEmailLink(verifyToken);
      if (verifyLink == null) {
        log.warn("Email verification skipped because the public app base URL "
            + "({}) is not configured", PublicUrlResolver.APP_BASE_URL_PROPERTY);
        if (welcome) {
          sendAuthEmailBestEffort(CONTRACT_NEW_ACCOUNT,
              () -> authEmailSender.sendNewAccount(account, language));
        }
        return;
      }

      // ETP-5003 — the same expiry the token is stored with is handed to the email, so the copy
      // states the window the server actually grants instead of repeating a constant.
      Date verifyExpiresAt = Date.from(Instant.now().plusSeconds(EMAIL_VERIFICATION_TTL_SECONDS));
      EmailVerificationDalHelper.storeEmailVerifyToken(account, verifyTokenHash, verifyExpiresAt);
      tokenStored = true;

      boolean emailSent = welcome
          ? authEmailSender.sendNewAccount(account, language, verifyLink, verifyExpiresAt)
          : authEmailSender.sendVerifyEmail(account, verifyTokenHash, verifyLink, language,
              verifyExpiresAt);
      if (emailSent) {
        return;
      }
    } catch (RuntimeException e) {
      // Swallowed on purpose, and this is the whole reason the method owns its own try: it runs
      // AFTER the account transaction has already committed. Letting the exception escape would
      // answer "registration failed" for an account that exists — leaving the user unable to
      // retry (the address is now taken) and unable to log in to the account they just created.
      log.warn("Email verification could not be issued", e);
    }
    if (tokenStored) {
      revertUnusableEmailVerifyToken(account, previousTokenState);
    }
  }

  /**
   * Undoes a token re-issue whose mail never went out, putting back whatever the account held
   * before it.
   *
   * <p>When a confirmation was already pending, the previous token comes back: the account stays
   * gated and the link already in the user's inbox keeps working. When nothing was pending this
   * restores to "no token", leaving the account ungated rather than blocked behind a link nobody
   * can click.
   */
  private void revertUnusableEmailVerifyToken(Account account,
      EmailVerificationDalHelper.EmailVerifyTokenState previousTokenState) {
    boolean hadPendingToken = previousTokenState != null && previousTokenState.hasToken();
    try {
      EmailVerificationDalHelper.restoreEmailVerifyToken(account, previousTokenState);
    } catch (RuntimeException e) {
      log.error("Could not revert the unusable email verification token; this account may be gated "
          + "out of creating an environment with no deliverable confirmation link", e);
      return;
    }
    if (hadPendingToken) {
      log.warn("Email verification re-send could not be delivered; the previously issued token was "
          + "restored, so the account stays gated and its earlier link still works");
    } else {
      log.warn("Email verification token dropped because its mail could not be sent — "
          + "the account is left ungated rather than locked out");
    }
  }

  private void sendAuthEmailBestEffort(String contractName, Runnable sendAction) {
    try {
      sendAction.run();
    } catch (RuntimeException e) {
      log.warn("Transactional auth email {} failed without blocking account flow",
          contractName, e);
    }
  }

  /** Cryptographically random, URL-safe token for a mailed one-shot link (reset, verification). */
  static String generateSecureUrlToken() {
    byte[] token = new byte[32];
    new SecureRandom().nextBytes(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  /** SHA-256 of a mailed token. Only the digest is ever persisted. */
  static String hashAuthToken(String token) {
    try {
      MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
      byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
