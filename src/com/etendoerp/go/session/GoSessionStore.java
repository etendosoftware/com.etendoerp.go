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

/**
 * Persistence port for {@link GoSessionRecord} (ETP-4575).
 *
 * <p>Abstracting persistence behind this interface keeps {@link GoSessionService} logic unit-testable
 * with an in-memory fake; the production implementation performs raw JDBC over the Hibernate
 * connection against {@code ETGO_GO_SESSION}, mirroring the {@code OAuth2Filter} pattern.
 */
public interface GoSessionStore {

  /**
   * Insert a new session row.
   *
   * @param sessionRecord the record to persist
   */
  void save(GoSessionRecord sessionRecord);

  /**
   * Persist mutations to an existing session row (e.g. revocation).
   *
   * @param sessionRecord the record to update
   */
  void update(GoSessionRecord sessionRecord);

  /**
   * Atomically consume an active session and insert its rotated successor.
   *
   * <p>The implementation must only revoke {@code current} when it is still active. Concurrent
   * callers racing with the same record must result in exactly one successful rotation.
   *
   * @param current the active record being consumed
   * @param successor the freshly generated successor
   * @return {@code true} when this caller won the rotation race
   */
  boolean rotateAtomically(GoSessionRecord current, GoSessionRecord successor);

  /**
   * Look up a session by the hash of its opaque session token.
   *
   * @param sessionTokenHash SHA-256 hash of the opaque session token
   * @return the matching record, or {@code null} if none
   */
  GoSessionRecord findByTokenHash(String sessionTokenHash);

  /**
   * Look up a session by the hash of its opaque refresh token.
   *
   * @param refreshTokenHash SHA-256 hash of the opaque refresh token
   * @return the matching record, or {@code null} if none
   */
  GoSessionRecord findByRefreshTokenHash(String refreshTokenHash);

  /**
   * Find the session that was rotated from the given session id (its direct descendant in the
   * rotation chain). Used to revoke a whole session family on refresh replay.
   *
   * @param sessionId the ancestor session id
   * @return the descendant record, or {@code null} if none
   */
  GoSessionRecord findByRotatedFromId(String sessionId);
}
