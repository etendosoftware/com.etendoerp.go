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
 * Decides how an authenticated NEO request should proceed (ETP-4575, 4b): use the cookie session,
 * reject it, or fall back to the legacy Bearer path while the migration flag is on. Pure function of
 * the cookie {@link GoSessionAuthResult.Status} and the legacy-Bearer flag, so it is unit-testable
 * independently of {@code OBContext} and the servlet.
 */
public final class GoNeoAuth {

  public enum Action {
    /** A valid cookie session resolved — reconstruct {@code OBContext} from it. */
    USE_SESSION,
    /** A cookie session resolved but the unsafe request failed CSRF/Origin → 403. */
    CSRF_REJECTED,
    /** A cookie was present but the session is invalid/expired/revoked → 401. */
    SESSION_INVALID,
    /** No cookie session; the legacy Bearer path is still enabled → try it. */
    USE_LEGACY_BEARER,
    /** No cookie session and the legacy Bearer path is disabled → 401. */
    NO_CREDENTIALS
  }

  private GoNeoAuth() {
  }

  /**
   * @param status              the cookie authentication outcome
   * @param legacyBearerEnabled whether the legacy Bearer path is still accepted
   * @return the action the caller must take
   */
  public static Action decide(GoSessionAuthResult.Status status, boolean legacyBearerEnabled) {
    switch (status) {
      case AUTHENTICATED:
        return Action.USE_SESSION;
      case CSRF_FAILED:
        return Action.CSRF_REJECTED;
      case UNAUTHENTICATED:
        return Action.SESSION_INVALID;
      case NO_SESSION:
      default:
        return legacyBearerEnabled ? Action.USE_LEGACY_BEARER : Action.NO_CREDENTIALS;
    }
  }
}
