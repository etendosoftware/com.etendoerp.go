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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;

/**
 * ETP-5351 — the single answer to "is this the support identity?". Every hardening point (T5)
 * asks here instead of re-deriving the rule, so the rule lives in one place.
 *
 * <p>Three identities are recognised, all <b>without a database round trip</b>:</p>
 * <ul>
 *   <li><b>the technical account</b> ({@code ETGO_ACCOUNT}) every support session hangs from:
 *       matched by its fixed id, never by email (an account registered with that email before the
 *       seed ran must not become the support account);</li>
 *   <li><b>a support session</b> ({@code ETGO_GO_SESSION}): it carries an
 *       {@code ETGO_SUPPORT_ACCESS_ID}, or belongs to the technical account;</li>
 *   <li><b>the per-tenant "Soporte Etendo" user</b> ({@code AD_User}): its id is derived from the
 *       client id ({@link #supportUserIdFor(String)}), so the check needs the user and client
 *       ids only. {@link SupportUserProvisioner} is the only writer of that id.</li>
 * </ul>
 *
 * <p>Every check here answers "deny" on a match, so a false positive can only take something
 * away from a legitimate user, never grant anything to the support identity.</p>
 */
public final class SupportAccessGuard {

  /** {@code ETGO_Account_ID} of the technical support account (seeded by the module script). */
  public static final String SUPPORT_ACCOUNT_ID = "76EE310B5C1F481499BFEEF69A7569C8";
  /** Undeliverable email of the technical support account. Never used to look it up. */
  public static final String SUPPORT_ACCOUNT_EMAIL = "soporte@etendo-go.invalid";
  /** {@code AD_Role_ID} of the System role "Soporte Etendo GO" operators log into Classic with. */
  public static final String SUPPORT_OPERATOR_ROLE_ID = "E70BA0AAD9964AC4909099444C334BD2";
  /** {@code ETGO_GO_SESSION.AUTH_METHOD} of a support session. */
  public static final String AUTH_METHOD_SUPPORT = "support";
  /** Stable error code of every refusal caused by the support identity. */
  public static final String ERROR_CODE_FORBIDDEN = "SUPPORT_SESSION_FORBIDDEN";
  /** Developer-facing message paired with {@link #ERROR_CODE_FORBIDDEN}. */
  public static final String MESSAGE_FORBIDDEN = "Not available in a support session";

  private static final String SUPPORT_USER_ID_SEED = "ETGO_SUPPORT_USER:";
  private static final int AD_ID_LENGTH = 32;
  private static final String PATH_FIRST_STEPS = "/onboarding/first-steps";

  /**
   * Unsafe account-surface routes ({@code /sws/go/*} behind the account resolver) a support
   * session may still call. Everything else that writes is refused: password, email and
   * auth-method changes, invitations, onboarding of new environments, billing and checkout.
   * {@code first-steps} only stores UI checklist state on the technical account.
   */
  private static final Set<String> SUPPORT_UNSAFE_ACCOUNT_ROUTES = Set.of(PATH_FIRST_STEPS);

  /**
   * Safe account-surface routes a support session may NOT call: {@code GET /login} mints an
   * environment JWT, a credential that would outlive the session.
   */
  private static final Set<String> SUPPORT_DENIED_SAFE_ACCOUNT_ROUTES = Set.of("/login");

  private SupportAccessGuard() {
  }

  /**
   * Whether the id is the technical support account's.
   *
   * @param accountId an {@code ETGO_Account_ID}, may be null
   * @return {@code true} only for the technical support account
   */
  public static boolean isSupportAccountId(String accountId) {
    return SUPPORT_ACCOUNT_ID.equals(accountId);
  }

  /**
   * Whether the account is the technical support account.
   *
   * @param account an account, may be null
   * @return {@code true} only for the technical support account
   */
  public static boolean isSupportAccount(Account account) {
    return account != null && isSupportAccountId(account.getId());
  }

  /**
   * Whether the session is a support session: marked with a support access, or owned by the
   * technical account (which only support sessions can be).
   *
   * @param sessionRecord a session, may be null
   * @return {@code true} for a support session
   */
  public static boolean isSupportSession(GoSessionRecord sessionRecord) {
    return sessionRecord != null && (StringUtils.isNotBlank(sessionRecord.getSupportAccessId())
        || isSupportAccountId(sessionRecord.getAccountId()));
  }

  /**
   * Whether the email is the technical support account's. Used only to refuse things addressed
   * to it (registration, invitations, SSO), never to find the account.
   *
   * @param email an email, may be null
   * @return {@code true} when it is the support account email, ignoring case and blanks
   */
  public static boolean isSupportEmail(String email) {
    String normalized = StringUtils.trimToNull(email);
    return normalized != null
        && SUPPORT_ACCOUNT_EMAIL.equals(normalized.toLowerCase(Locale.ROOT));
  }

  /**
   * Whether the user is the "Soporte Etendo" user of the given client.
   *
   * @param userId   an {@code AD_User_ID}, may be null
   * @param clientId the {@code AD_Client_ID} the user acts in, may be null
   * @return {@code true} when {@code userId} is that client's support user
   */
  public static boolean isSupportUser(String userId, String clientId) {
    return userId != null && StringUtils.isNotBlank(clientId)
        && userId.equals(supportUserIdFor(clientId));
  }

  /**
   * The fixed {@code AD_User_ID} of a client's "Soporte Etendo" user: the first 32 hex characters
   * (upper case) of {@code SHA-256("ETGO_SUPPORT_USER:" + clientId)}. Deterministic, so creating
   * it twice collides on the primary key instead of creating a second user, and recognising it
   * needs no query.
   *
   * @param clientId the tenant's {@code AD_Client_ID}
   * @return the support user id for that client
   * @throws IllegalArgumentException if {@code clientId} is blank
   */
  public static String supportUserIdFor(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      throw new IllegalArgumentException("clientId is required");
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((SUPPORT_USER_ID_SEED + clientId).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(AD_ID_LENGTH);
      for (int i = 0; hex.length() < AD_ID_LENGTH; i++) {
        hex.append(String.format("%02X", digest[i]));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * Whether a support session may call an account-surface route ({@code /sws/go/*} resolved
   * through the account resolver). Safe methods are allowed except the ones minting credentials;
   * unsafe methods only on an explicit allowlist. The session routes ({@code /session*}) decide
   * for themselves and are not covered here.
   *
   * @param method the HTTP method
   * @param path   the servlet path info, with or without a trailing slash
   * @return {@code true} when the route stays available in a support session
   */
  public static boolean isAllowedOnAccountSurface(String method, String path) {
    String route = normalizeRoute(path);
    if (GoSessionSecurity.isSafeMethod(method)) {
      return !SUPPORT_DENIED_SAFE_ACCOUNT_ROUTES.contains(route);
    }
    return SUPPORT_UNSAFE_ACCOUNT_ROUTES.contains(route);
  }

  private static String normalizeRoute(String path) {
    String route = StringUtils.defaultString(path);
    while (route.length() > 1 && route.endsWith("/")) {
      route = route.substring(0, route.length() - 1);
    }
    return route;
  }
}
