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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.common;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Logger;

import com.etendoerp.go.auth.EnvironmentAuthOutcome;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.SurfacePolicy;

/**
 * Authentication entry point for the Etendo GO servlets that serve environment data outside
 * {@code NeoServlet} (favorites, fiscal test mode).
 *
 * <p>ETP-5455 — it used to carry its own copy of the cookie-then-Bearer resolution, which is how
 * those servlets ended up without the commercial-access check NEO applies: the copy had never been
 * given it. It is now the shared {@link EnvironmentRequestAuthenticator} pipeline under
 * {@link SurfacePolicy#NEO_DATA}. The bearer-only {@code authenticate(HttpServletRequest)} primitive
 * that also lived here — reachable by nobody, and a way around the legacy kill switch for whoever
 * called it next — is gone.
 */
public class JwtAuthUtils {

  /**
   * The two NEO session claims other servlets read directly off a decoded token to scope a
   * request to its tenant. Public because this class is the module's single home for the claim
   * NAMES, and the alternative is what {@code EtendoGoJwtServlet} was doing: repeating the bare
   * literal, which Sonar's S1192 then answers by proposing the nearest same-valued constant —
   * there, the onboarding progress-step ids {@code PROGRESS_CLIENT}/{@code PROGRESS_ORGANIZATION}.
   * Those carry the same two values by coincidence, not by contract: binding token parsing to
   * them would mean a renamed progress step silently breaks authentication.
   */
  public static final String CLAIM_ORG = "organization";
  /** @see #CLAIM_ORG */
  public static final String CLAIM_CLIENT = "client";

  private static final EnvironmentRequestAuthenticator AUTHENTICATOR =
      new EnvironmentRequestAuthenticator();

  private JwtAuthUtils() {
  }

  /**
   * Authenticates the request and, on failure, writes the error response and logs the reason.
   *
   * @param request  the incoming HTTP request
   * @param response the HTTP response (used to write the error body on failure)
   * @param log      logger used to record the failure cause
   * @param context  short label for the endpoint, included in the log message
   * @return {@code true} when authentication succeeded, {@code false} when the caller must abort
   * @throws IOException if writing the error response body fails
   */
  public static boolean authenticateOrFail(HttpServletRequest request, HttpServletResponse response,
      Logger log, String context) throws IOException {
    return authenticateOrFail(AUTHENTICATOR, request, response, log, context);
  }

  /**
   * Same as {@link #authenticateOrFail(HttpServletRequest, HttpServletResponse, Logger, String)},
   * over a given pipeline.
   */
  static boolean authenticateOrFail(EnvironmentRequestAuthenticator authenticator,
      HttpServletRequest request, HttpServletResponse response, Logger log, String context)
      throws IOException {
    EnvironmentAuthOutcome outcome = authenticator.authenticate(request, SurfacePolicy.NEO_DATA);
    if (outcome.isAuthenticated()) {
      return true;
    }
    log.warn("Refused {} ({}): {}", context, outcome.getHttpStatus(), outcome.getMessage());
    ServletResponseUtils.sendError(response, outcome.getHttpStatus(), outcome.getMessage());
    return false;
  }
}
