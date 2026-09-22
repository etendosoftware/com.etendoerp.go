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
 * Outcome of {@link GoSessionAuthenticator#authenticate}. Maps to a caller action:
 * <ul>
 *   <li>{@link Status#NO_SESSION} — no session cookie; the caller may fall back to legacy Bearer.</li>
 *   <li>{@link Status#UNAUTHENTICATED} — a cookie was present but invalid/expired/revoked → {@code 401}.</li>
 *   <li>{@link Status#CSRF_FAILED} — valid session but the unsafe request failed CSRF/Origin → {@code 403}.</li>
 *   <li>{@link Status#AUTHENTICATED} — resolved session available via {@link #getRecord()}.</li>
 * </ul>
 */
public final class GoSessionAuthResult {

  /** The four possible outcomes of resolving the session cookie. */
  public enum Status { NO_SESSION, UNAUTHENTICATED, CSRF_FAILED, AUTHENTICATED }

  private final Status status;
  private final GoSessionRecord sessionRecord;

  private GoSessionAuthResult(Status status, GoSessionRecord sessionRecord) {
    this.status = status;
    this.sessionRecord = sessionRecord;
  }

  /** @return a result meaning no session cookie was present */
  public static GoSessionAuthResult noSession() {
    return new GoSessionAuthResult(Status.NO_SESSION, null);
  }

  /** @return a result meaning a cookie was present but the session is invalid/expired/revoked */
  public static GoSessionAuthResult unauthenticated() {
    return new GoSessionAuthResult(Status.UNAUTHENTICATED, null);
  }

  /** @return a result meaning a valid session failed the CSRF/Origin check on an unsafe method */
  public static GoSessionAuthResult csrfFailed() {
    return new GoSessionAuthResult(Status.CSRF_FAILED, null);
  }

  /**
   * Builds an authenticated result wrapping the resolved session.
   *
   * @param sessionRecord the resolved session record
   * @return a result carrying the authenticated session
   */
  public static GoSessionAuthResult authenticated(GoSessionRecord sessionRecord) {
    return new GoSessionAuthResult(Status.AUTHENTICATED, sessionRecord);
  }

  /** @return the outcome of resolving the session cookie */
  public Status getStatus() {
    return status;
  }

  /**
   * Returns the resolved session record when authenticated.
   *
   * @return the resolved session record when {@link #getStatus()} is {@code AUTHENTICATED}, else {@code null}
   */
  public GoSessionRecord getRecord() {
    return sessionRecord;
  }

  /** @return {@code true} when the request carries a valid, authenticated session */
  public boolean isAuthenticated() {
    return status == Status.AUTHENTICATED;
  }
}
