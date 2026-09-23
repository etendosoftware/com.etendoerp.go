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

package com.etendoerp.go.auth;

import javax.servlet.http.HttpServletResponse;

import org.openbravo.dal.core.OBContext;

/**
 * Result of {@link EnvironmentRequestAuthenticator#authenticate} (ETP-5455).
 *
 * <p>Deliberately a value rather than a written response: the consumers write errors in three
 * different ways ({@code NeoServlet#sendError}, {@code ServletResponseUtils#sendError}, their own
 * JSON envelope), and none of that is authentication. What they must NOT differ on — the status
 * and the message for a given failure — is decided here, once.
 */
public final class EnvironmentAuthOutcome {

  /** Outcome kinds; each maps to exactly one HTTP status. */
  public enum Status {
    AUTHENTICATED(HttpServletResponse.SC_OK),
    CSRF_REJECTED(HttpServletResponse.SC_FORBIDDEN),
    UNAUTHENTICATED(HttpServletResponse.SC_UNAUTHORIZED),
    /** javax.servlet predates RFC 7231 and has no constant for 402. */
    PAYMENT_REQUIRED(402);

    private final int httpStatus;

    Status(int httpStatus) {
      this.httpStatus = httpStatus;
    }

    /** @return the HTTP status a consumer must answer with */
    public int getHttpStatus() {
      return httpStatus;
    }
  }

  private final Status status;
  private final String message;
  private final AuthScheme scheme;
  private final OBContext context;

  private EnvironmentAuthOutcome(Status status, String message, AuthScheme scheme,
      OBContext context) {
    this.status = status;
    this.message = message;
    this.scheme = scheme;
    this.context = context;
  }

  static EnvironmentAuthOutcome authenticated(AuthScheme scheme, OBContext context) {
    return new EnvironmentAuthOutcome(Status.AUTHENTICATED, null, scheme, context);
  }

  static EnvironmentAuthOutcome refused(Status status, String message, AuthScheme scheme) {
    return new EnvironmentAuthOutcome(status, message, scheme, null);
  }

  /** @return true when the request may proceed; {@link #getContext()} is then set */
  public boolean isAuthenticated() {
    return status == Status.AUTHENTICATED;
  }

  /** @return the outcome kind */
  public Status getStatus() {
    return status;
  }

  /** @return the HTTP status to answer with when the request is refused */
  public int getHttpStatus() {
    return status.getHttpStatus();
  }

  /** @return the client-safe error message, or null when authenticated */
  public String getMessage() {
    return message;
  }

  /** @return the scheme the credential was resolved as, or null when none was resolved */
  public AuthScheme getScheme() {
    return scheme;
  }

  /** @return the context installed for the request, or null when refused */
  public OBContext getContext() {
    return context;
  }
}
