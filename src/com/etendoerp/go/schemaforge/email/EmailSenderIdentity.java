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

package com.etendoerp.go.schemaforge.email;

import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;

/**
 * Resolves the Reply-To address of the operator who triggered a transactional email.
 * <p>
 * ETP-5003: every email leaves the gateway with a {@code noreply@} sender, because the {@code From}
 * domain has to be verified in SES and cannot be a per-tenant address. That left recipients of a
 * document email — customers receiving an invoice or an order — with no way to answer the person
 * who sent it. Reply-To has no such constraint in SES, so the operator's own address goes there.
 * <p>
 * The address is derived server-side from {@link OBContext}, never from the request body:
 * {@code replyTo} stays in {@code TransactionalEmailService}'s forbidden-command-fields set, so a
 * browser still cannot choose who replies land on.
 */
public final class EmailSenderIdentity {

  private static final Logger log = LogManager.getLogger();

  /**
   * Single-address matcher, deliberately stricter than RFC 5322.
   * <p>
   * The excluded characters are the ones that would let a stored address change the meaning of the
   * header it lands in: whitespace (including CR and LF, which would inject a header break), the
   * {@code ,} and {@code ;} address separators, and the {@code <>"} display-name delimiters.
   */
  private static final Pattern SINGLE_ADDRESS =
      Pattern.compile("^[^\\s@,;<>\"]+@[^\\s@,;<>\"]+\\.[^\\s@,;<>\"]+$");

  private EmailSenderIdentity() {
  }

  /**
   * Resolves the Reply-To address for the user owning the current session.
   *
   * @return the operator's address, or {@code null} when the session has no usable one — callers
   *     pass that through unchanged, which omits the header exactly as before this existed
   */
  public static String resolveReplyTo() {
    try {
      OBContext context = OBContext.getOBContext();
      User user = context == null ? null : context.getUser();
      String replyTo = user == null ? null : pickAddress(user.getEmail(), user.getUsername());
      if (replyTo == null) {
        // Without this the only symptom is a customer who cannot answer, which looks identical to
        // the provider dropping the header — the two need telling apart from the logs alone.
        log.warn("No Reply-To resolved for the session user {}; the recipient will have no address "
            + "to answer.", user == null ? "<none>" : user.getId());
      } else {
        log.debug("Reply-To resolved from the session user: {}", replyTo);
      }
      return replyTo;
    } catch (Exception e) {
      // A missing or half-initialised session must never fail a send: the email is still valid
      // without a Reply-To header.
      log.debug("Could not resolve the Reply-To address from the session: {}", e.getMessage(), e);
      return null;
    }
  }

  /**
   * Picks the operator's address, preferring the dedicated email field over the username.
   * <p>
   * The fallback is not cosmetic. Etendo GO signs users up by email, so the address lands in
   * {@code AD_User.USERNAME} while {@code AD_User.EMAIL} stays null — as of 2026-08-26 that is the
   * case for every account that has actually sent a document email on this instance. Reading only
   * {@code EMAIL} would resolve nothing for them.
   *
   * @param email value of {@code AD_User.EMAIL}, may be null or unusable
   * @param username value of {@code AD_User.USERNAME}, may be null or not an address at all
   * @return the first of the two that is a single well-formed address, or {@code null}
   */
  static String pickAddress(String email, String username) {
    String preferred = singleAddressOrNull(email);
    return preferred != null ? preferred : singleAddressOrNull(username);
  }

  private static String singleAddressOrNull(String candidate) {
    String value = StringUtils.trimToNull(candidate);
    return value != null && SINGLE_ADDRESS.matcher(value).matches() ? value : null;
  }
}
