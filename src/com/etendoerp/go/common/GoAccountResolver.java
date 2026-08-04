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

package com.etendoerp.go.common;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * Resolves the platform account ({@code ETGO_ACCOUNT}) that owns an environment user.
 *
 * <p>This is the reverse of the lookup onboarding performs. Onboarding names the environment's
 * AD_User after the account email, appending a client-derived suffix when that username is already
 * taken (see {@code EtendoGoJwtSupport.buildClientUsername}):
 *
 * <pre>
 *   first environment  -> "user@example.com"
 *   later environments -> "user@example.com+acmeltd"
 * </pre>
 *
 * <p>The suffix alphabet is {@code [a-z0-9]} only — the client name is lowercased and stripped of
 * everything else — so it can never itself contain {@code '+'}. Splitting on the <strong>last</strong>
 * {@code '+'} therefore recovers the email exactly, even for an account that legitimately uses
 * plus-addressing ({@code "user+tag@example.com+acmeltd"} resolves to {@code "user+tag@example.com"}).
 * Splitting on the <em>first</em> {@code '+'} would corrupt exactly those users, which is why this
 * resolves by candidate lookup rather than by naive string manipulation.
 *
 * <p>Both lookups are exact-match on the email, so no LIKE pattern is built from user-controlled
 * text and there are no wildcards to escape.
 *
 * <p>Every method is null-safe and returns an empty {@link Optional} rather than throwing: an AD_User
 * with no platform account behind it (a hand-created ERP user, a system user) is an ordinary case,
 * not an error.
 */
public final class GoAccountResolver {

  private static final Logger log = LogManager.getLogger(GoAccountResolver.class);

  private static final String PARAM_EMAIL = "email";
  private static final String ACCOUNT_BY_EMAIL_QUERY =
      "as account where lower(account.email) = :" + PARAM_EMAIL + " and account.active = true";

  private GoAccountResolver() {
  }

  /**
   * Resolves the platform account behind an environment username.
   *
   * @param username the AD_User username, which onboarding derives from the account email
   * @return the owning account, or empty when the username maps to no active account
   */
  public static Optional<Account> findAccountByUsername(String username) {
    String normalized = StringUtils.trimToNull(username);
    if (normalized == null) {
      return Optional.empty();
    }
    try {
      Account exact = findActiveAccountByEmail(normalized);
      if (exact != null) {
        return Optional.of(exact);
      }
      // Not a direct match, so this is a later environment whose username carries the
      // client-derived suffix. Strip it and retry; see the class javadoc for why the LAST '+'.
      int suffixStart = normalized.lastIndexOf('+');
      if (suffixStart <= 0) {
        return Optional.empty();
      }
      return Optional.ofNullable(findActiveAccountByEmail(normalized.substring(0, suffixStart)));
    } catch (RuntimeException e) {
      // Identity enrichment must never break the caller that asked for it.
      log.warn("Could not resolve the platform account for username {}: {}",
          maskUsername(normalized), e.getMessage(), e);
      return Optional.empty();
    }
  }

  private static Account findActiveAccountByEmail(String email) {
    OBQuery<Account> query = OBDal.getReadOnlyInstance().createQuery(Account.class,
        ACCOUNT_BY_EMAIL_QUERY);
    query.setNamedParameter(PARAM_EMAIL, email.toLowerCase());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  /**
   * Masks a username for logging so no address lands in the logs, mirroring the masking the JWT
   * servlet applies to account emails.
   */
  private static String maskUsername(String username) {
    int at = username.indexOf('@');
    if (at <= 0) {
      return username.charAt(0) + "***";
    }
    return username.charAt(0) + "***" + username.substring(at);
  }
}
