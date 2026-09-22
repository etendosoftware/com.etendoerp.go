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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.etendoerp.go.session.GoSessionAuthResult.Status;

/** Unit tests for {@link GoNeoAuth#decide} (ETP-4575, 4b). */
public class GoNeoAuthTest {

  @Test
  public void authenticatedSessionIsUsed() {
    assertEquals(GoNeoAuth.Action.USE_SESSION, GoNeoAuth.decide(Status.AUTHENTICATED, true));
    assertEquals(GoNeoAuth.Action.USE_SESSION, GoNeoAuth.decide(Status.AUTHENTICATED, false));
  }

  @Test
  public void csrfFailureIsRejected() {
    assertEquals(GoNeoAuth.Action.CSRF_REJECTED, GoNeoAuth.decide(Status.CSRF_FAILED, true));
  }

  @Test
  public void invalidSessionIsRejected() {
    assertEquals(GoNeoAuth.Action.SESSION_INVALID, GoNeoAuth.decide(Status.UNAUTHENTICATED, true));
  }

  @Test
  public void noCookieFallsBackToBearerWhenEnabled() {
    assertEquals(GoNeoAuth.Action.USE_LEGACY_BEARER, GoNeoAuth.decide(Status.NO_SESSION, true));
  }

  @Test
  public void noCookieRejectedWhenBearerDisabled() {
    assertEquals(GoNeoAuth.Action.NO_CREDENTIALS, GoNeoAuth.decide(Status.NO_SESSION, false));
  }
}
