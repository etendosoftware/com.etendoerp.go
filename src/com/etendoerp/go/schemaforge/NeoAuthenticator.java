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

package com.etendoerp.go.schemaforge;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.auth.EnvironmentAuthOutcome;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.SurfacePolicy;
import com.etendoerp.go.payment.EnvironmentAccessGuard;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Authentication and access-check collaborator for {@link NeoServlet}.
 *
 * <p>Authentication itself is the shared {@link EnvironmentRequestAuthenticator} pipeline under
 * {@link SurfacePolicy#NEO_API} (ETP-5455): this class used to carry its own copy of it — one
 * branch per credential scheme, each re-implementing the context setup, the commercial access
 * check and the language — and the copies drifted. What stays here is NEO-specific: how a refusal
 * is written, and the per-window and per-process access questions for the current role.
 *
 * <p>ETP-5047 — the pipeline's commercial-access check is the shared {@link EnvironmentAccessGuard}
 * (decision + kill switch). NEO answers its denial with the guard's structured body through
 * {@link NeoServlet#writeResponse}: HTTP 402, the message text unchanged since ETP-5443, plus
 * {@code error.code} and {@code error.decision}.
 */
class NeoAuthenticator {

  private static final Logger log = LogManager.getLogger(NeoAuthenticator.class);

  /** The entry-point label of the commercial-access log line (ETP-5047). */
  private static final String ENTRY_POINT = "neo";

  private final NeoServlet servlet;
  private final EnvironmentRequestAuthenticator environmentAuthenticator;

  NeoAuthenticator(NeoServlet servlet) {
    this(servlet, new EnvironmentRequestAuthenticator());
  }

  NeoAuthenticator(NeoServlet servlet, EnvironmentRequestAuthenticator environmentAuthenticator) {
    this.servlet = servlet;
    this.environmentAuthenticator = environmentAuthenticator;
  }

  /**
   * Authenticate the request and set up its {@link OBContext}. On failure writes the HTTP error
   * to {@code response} and returns {@code false}.
   */
  boolean authenticateRequest(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    EnvironmentAuthOutcome outcome =
        environmentAuthenticator.authenticate(request, SurfacePolicy.NEO_API, ENTRY_POINT);
    if (outcome.isAuthenticated()) {
      return true;
    }
    EnvironmentAccessGuard.Denial denial = outcome.getAccessDenial();
    if (denial != null) {
      log.info("Commercial access denied for NEO request: {}", outcome.getMessage());
      servlet.writeResponse(response, NeoResponse.error(EnvironmentAccessGuard.Denial.STATUS,
          denial.errorBody(EnvironmentAccessGuard.Denial.STATUS)));
      return false;
    }
    log.warn("Unauthorized NEO request: {}", outcome.getMessage());
    servlet.sendError(response, outcome.getHttpStatus(), outcome.getMessage());
    return false;
  }

  boolean hasWindowAccess(String windowId) {
    return NeoServletSupport.hasWindowAccess(windowId);
  }

  boolean hasWindowAccess(String windowId, String httpMethod) {
    return NeoServletSupport.hasWindowAccess(windowId, httpMethod);
  }

  boolean hasWindowAccessForSpec(SFSpec spec, String httpMethod) {
    return NeoServletSupport.hasWindowAccessForSpec(spec, httpMethod);
  }

  boolean hasReportSpecAccess(SFSpec spec, String httpMethod) {
    return NeoServletSupport.hasReportSpecAccess(spec, httpMethod);
  }

  boolean hasProcessAccess(String processId) {
    return NeoServletSupport.hasProcessAccess(processId);
  }
}
