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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.data.Account;

/** Issues the first password-setup link for an administrator-created pending account. */
final class PasswordResetInvitationService {

  private static final Logger log = LogManager.getLogger(PasswordResetInvitationService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int TOKEN_BYTES = 32;
  private static final long TOKEN_TTL_SECONDS = 24 * 60 * 60;
  private static final String STATUS_PENDING = "pending";

  private PasswordResetInvitationService() {
  }

  static void sendIfNeeded(Account account) {
    if (account == null || !StringUtils.equals(STATUS_PENDING, (String) account.get("status"))
        || EtendoGoJwtDalHelper.hasPasswordResetToken(account)) {
      return;
    }

    String token = generateToken();
    String link = EtendoGoAuthLinkBuilder.resetPasswordLink(token,
        PublicUrlResolver.resolveConfiguredAppBaseUrl());
    if (link == null) {
      log.warn("Invitation email skipped because the public app base URL is not configured");
      return;
    }

    EtendoGoJwtDalHelper.PasswordResetTokenState previousTokenState =
        EtendoGoJwtDalHelper.capturePasswordResetToken(account);
    String tokenHash = hashToken(token);
    EtendoGoJwtDalHelper.storePasswordResetToken(account, tokenHash,
        Date.from(Instant.now().plusSeconds(TOKEN_TTL_SECONDS)));

    boolean sent = false;
    try {
      sent = new TransactionalAuthEmailSender().sendPasswordReset(account, tokenHash, link);
    } catch (RuntimeException e) {
      log.warn("Invitation email failed after token storage", e);
    }
    if (!sent) {
      EtendoGoJwtDalHelper.restorePasswordResetToken(account, previousTokenState);
    }
  }

  private static String generateToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String hashToken(String token) {
    try {
      return Base64.getEncoder().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required for password reset tokens", e);
    }
  }
}
