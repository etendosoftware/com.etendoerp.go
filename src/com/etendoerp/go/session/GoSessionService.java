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
package com.etendoerp.go.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.oauth2.OAuth2Utils;

/**
 * Lifecycle of the backend-managed opaque session (ETP-4575): create, resolve, rotate, refresh
 * (with replay detection) and revoke.
 *
 * <p>Only credential <b>hashes</b> are persisted; the plaintext session/refresh tokens are returned
 * once via {@link IssuedGoSession} for the cookie and never stored. Expiry is enforced on both an
 * idle timeout and an absolute cap. Refresh tokens are one-time: replaying a consumed refresh token
 * revokes the entire rotation family.
 *
 * @see <a href="../../../../../../docs/adr/0001-backend-managed-session.md">ADR-0001</a>
 */
public class GoSessionService {

  private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);
  private static final Duration DEFAULT_ABSOLUTE_TIMEOUT = Duration.ofHours(12);

  private final GoSessionStore store;
  private final Duration idleTimeout;
  private final Duration absoluteTimeout;

  /**
   * Create a service with the default idle (30 min) and absolute (12 h) timeouts.
   *
   * @param store the persistence port for session records
   */
  public GoSessionService(GoSessionStore store) {
    this(store, DEFAULT_IDLE_TIMEOUT, DEFAULT_ABSOLUTE_TIMEOUT);
  }

  /**
   * Create a service with explicit idle and absolute session timeouts.
   *
   * @param store           the persistence port for session records
   * @param idleTimeout     the inactivity window after which a session expires
   * @param absoluteTimeout the maximum session lifetime regardless of activity
   */
  public GoSessionService(GoSessionStore store, Duration idleTimeout, Duration absoluteTimeout) {
    this.store = store;
    this.idleTimeout = idleTimeout;
    this.absoluteTimeout = absoluteTimeout;
  }

  /**
   * Create a brand-new session for a freshly authenticated account.
   *
   * @param accountId  the owning platform account id
   * @param authMethod {@code "password"} or {@code "sso"}
   * @param userAgent  optional user-agent for binding/audit
   * @param ipHash     optional hashed client IP for binding/audit
   * @return the issued session (plaintext tokens + persisted record)
   */
  public IssuedGoSession create(String accountId, String authMethod, String userAgent, String ipHash) {
    Instant now = Instant.now();
    String rawToken = OAuth2Utils.generateSecureToken();
    String rawRefresh = OAuth2Utils.generateSecureToken();
    String csrf = OAuth2Utils.generateSecureToken();

    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setId(newId());
    sessionRecord.setAccountId(accountId);
    sessionRecord.setSessionTokenHash(OAuth2Utils.hashToken(rawToken));
    sessionRecord.setRefreshTokenHash(OAuth2Utils.hashToken(rawRefresh));
    sessionRecord.setCsrfToken(csrf);
    sessionRecord.setAuthMethod(authMethod);
    sessionRecord.setExpiresAt(now.plus(idleTimeout));
    sessionRecord.setAbsoluteExpiresAt(now.plus(absoluteTimeout));
    sessionRecord.setRevoked(false);
    sessionRecord.setUserAgent(userAgent);
    sessionRecord.setIpHash(ipHash);

    store.save(sessionRecord);
    return new IssuedGoSession(rawToken, rawRefresh, csrf, sessionRecord);
  }

  /**
   * Resolve the active session behind a plaintext session token.
   *
   * @param rawSessionToken the plaintext token from the cookie
   * @return the active, non-revoked, non-expired sessionRecord, or {@code null}
   */
  public GoSessionRecord resolve(String rawSessionToken) {
    if (StringUtils.isBlank(rawSessionToken)) {
      return null;
    }
    GoSessionRecord sessionRecord = store.findByTokenHash(OAuth2Utils.hashToken(rawSessionToken));
    if (sessionRecord == null || sessionRecord.isRevoked() || isExpired(sessionRecord)) {
      return null;
    }
    return sessionRecord;
  }

  /**
   * Rotate a session: revoke the current record and issue a fresh one that preserves the account,
   * environment context and the absolute expiry cap. Used after authentication and after an
   * environment/privilege change.
   *
   * @param current the record to rotate away from
   * @return the newly issued session
   */
  public IssuedGoSession rotate(GoSessionRecord current) {
    Instant now = Instant.now();
    String rawToken = OAuth2Utils.generateSecureToken();
    String rawRefresh = OAuth2Utils.generateSecureToken();
    String csrf = OAuth2Utils.generateSecureToken();

    GoSessionRecord next = new GoSessionRecord();
    next.setId(newId());
    next.setAccountId(current.getAccountId());
    next.setSessionTokenHash(OAuth2Utils.hashToken(rawToken));
    next.setRefreshTokenHash(OAuth2Utils.hashToken(rawRefresh));
    next.setCsrfToken(csrf);
    next.setAuthMethod(current.getAuthMethod());
    next.setExpiresAt(now.plus(idleTimeout));
    // Preserve the absolute cap so rotation cannot extend a session indefinitely.
    next.setAbsoluteExpiresAt(current.getAbsoluteExpiresAt());
    next.setRevoked(false);
    next.setRotatedFromId(current.getId());
    next.setUserId(current.getUserId());
    next.setRoleId(current.getRoleId());
    next.setCtxClientId(current.getCtxClientId());
    next.setCtxOrgId(current.getCtxOrgId());
    next.setWarehouseId(current.getWarehouseId());
    next.setUserAgent(current.getUserAgent());
    next.setIpHash(current.getIpHash());

    if (!store.rotateAtomically(current, next)) {
      return null;
    }
    return new IssuedGoSession(rawToken, rawRefresh, csrf, next);
  }

  /**
   * Exchange a one-time refresh token for a rotated session.
   *
   * @param rawRefreshToken the plaintext refresh token from the cookie
   * @return the newly issued session, or {@code null} if the refresh is unknown, expired, or a
   *     replay (a replay revokes the whole rotation family)
   */
  public IssuedGoSession refresh(String rawRefreshToken) {
    if (StringUtils.isBlank(rawRefreshToken)) {
      return null;
    }
    GoSessionRecord sessionRecord = store.findByRefreshTokenHash(OAuth2Utils.hashToken(rawRefreshToken));
    if (sessionRecord == null) {
      return null;
    }
    if (sessionRecord.isRevoked()) {
      // This refresh token was already consumed (rotated away or logged out): treat as replay and
      // revoke the entire family so a stolen refresh cannot be used.
      revokeFamily(sessionRecord);
      return null;
    }
    if (isAbsoluteExpired(sessionRecord)) {
      revoke(sessionRecord);
      return null;
    }
    IssuedGoSession rotated = rotate(sessionRecord);
    if (rotated == null) {
      GoSessionRecord consumed = store.findByRefreshTokenHash(
          OAuth2Utils.hashToken(rawRefreshToken));
      revokeFamily(consumed);
    }
    return rotated;
  }

  /**
   * Invalidate a session server-side (logout).
   *
   * @param sessionRecord the record to revoke
   */
  public void revoke(GoSessionRecord sessionRecord) {
    if (sessionRecord == null || sessionRecord.isRevoked()) {
      return;
    }
    sessionRecord.setRevoked(true);
    store.update(sessionRecord);
  }

  private void revokeFamily(GoSessionRecord sessionRecord) {
    if (sessionRecord == null) {
      return;
    }
    if (!sessionRecord.isRevoked()) {
      sessionRecord.setRevoked(true);
      store.update(sessionRecord);
    }
    GoSessionRecord descendant = store.findByRotatedFromId(sessionRecord.getId());
    if (descendant != null) {
      revokeFamily(descendant);
    }
  }

  private boolean isExpired(GoSessionRecord sessionRecord) {
    return isIdleExpired(sessionRecord) || isAbsoluteExpired(sessionRecord);
  }

  private boolean isIdleExpired(GoSessionRecord sessionRecord) {
    return sessionRecord.getExpiresAt() == null || Instant.now().isAfter(sessionRecord.getExpiresAt());
  }

  private boolean isAbsoluteExpired(GoSessionRecord sessionRecord) {
    return sessionRecord.getAbsoluteExpiresAt() == null || Instant.now().isAfter(sessionRecord.getAbsoluteExpiresAt());
  }

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }
}
