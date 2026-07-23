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
 * Result of issuing (creating or rotating) a session (ETP-4575).
 *
 * <p>Carries the <b>plaintext</b> session and refresh tokens and the CSRF token — the only place
 * they exist server-side. The session token goes into the {@code __Host-} cookie, the CSRF token is
 * returned in the response body; neither plaintext token is ever persisted (only their hashes).
 */
public class IssuedGoSession {

  private final String sessionToken;
  private final String refreshToken;
  private final String csrfToken;
  private final GoSessionRecord record;

  public IssuedGoSession(String sessionToken, String refreshToken, String csrfToken,
      GoSessionRecord record) {
    this.sessionToken = sessionToken;
    this.refreshToken = refreshToken;
    this.csrfToken = csrfToken;
    this.record = record;
  }

  /** @return the plaintext opaque session token (for the {@code __Host-} cookie) */
  public String getSessionToken() {
    return sessionToken;
  }

  /** @return the plaintext opaque refresh token */
  public String getRefreshToken() {
    return refreshToken;
  }

  /** @return the CSRF token bound to this session (returned in the response body) */
  public String getCsrfToken() {
    return csrfToken;
  }

  /** @return the persisted session record (holds only hashes) */
  public GoSessionRecord getRecord() {
    return record;
  }
}
