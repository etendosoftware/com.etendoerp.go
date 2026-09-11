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

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

/**
 * Turns an incoming request's {@code __Host-} session cookie into an authentication decision
 * (ETP-4575). Resolves the opaque cookie to a live session via {@link GoSessionService} and
 * enforces the CSRF/Origin contract on unsafe methods via {@link GoSessionSecurity}.
 *
 * <p>This is the pure decision layer, free of {@code OBContext} side effects so it is unit-testable;
 * the servlet filter that consumes an {@link GoSessionAuthResult.Status#AUTHENTICATED} result is
 * responsible for reconstructing {@code OBContext} from the resolved record.
 */
public class GoSessionAuthenticator {

  private final GoSessionService sessionService;

  /**
   * Create an authenticator backed by the given session service.
   *
   * @param sessionService the service used to resolve opaque session tokens
   */
  public GoSessionAuthenticator(GoSessionService sessionService) {
    this.sessionService = sessionService;
  }

  /**
   * Resolve the session cookie and decide whether the request is authenticated.
   *
   * @param request the incoming request
   * @return the auth outcome; never {@code null}
   */
  public GoSessionAuthResult authenticate(HttpServletRequest request) {
    String rawToken = extractSessionToken(request);
    if (rawToken == null) {
      return GoSessionAuthResult.noSession();
    }
    GoSessionRecord sessionRecord = sessionService.resolve(rawToken);
    if (sessionRecord == null) {
      return GoSessionAuthResult.unauthenticated();
    }
    if (!GoSessionSecurity.isUnsafeRequestAuthorized(request, sessionRecord.getCsrfToken())) {
      return GoSessionAuthResult.csrfFailed();
    }
    return GoSessionAuthResult.authenticated(sessionRecord);
  }

  private static String extractSessionToken(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (GoSessionSecurity.COOKIE_NAME.equals(cookie.getName())) {
        return StringUtils.trimToNull(cookie.getValue());
      }
    }
    return null;
  }
}
