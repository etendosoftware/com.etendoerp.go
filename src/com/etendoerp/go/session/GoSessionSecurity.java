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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.common.CorsUtils;

/**
 * Security helpers for the backend-managed Etendo Go session (ETP-4575).
 *
 * <p>Two concerns, both stateless and independent of persistence:
 * <ul>
 *   <li><b>Cookie contract</b> — builds the {@code __Host-} session cookie
 *       ({@code Secure; HttpOnly; Path=/; SameSite=Lax}, no {@code Domain}, no persistent
 *       lifetime), so the credential is never readable by JavaScript (closes SEC-10).</li>
 *   <li><b>CSRF defense</b> — on unsafe HTTP methods, requires a session-bound CSRF token echoed
 *       in a custom header plus a same-origin {@code Origin}/{@code Referer} check. {@code SameSite}
 *       is defense in depth, not the sole control.</li>
 * </ul>
 *
 * @see <a href="../../../../../../docs/adr/0001-backend-managed-session.md">ADR-0001</a>
 */
public final class GoSessionSecurity {

  /** Session cookie name. The {@code __Host-} prefix forces {@code Secure}, {@code Path=/} and no {@code Domain}. */
  public static final String COOKIE_NAME = "__Host-go_session";

  /** Header carrying the session-bound CSRF token on unsafe methods. */
  public static final String CSRF_HEADER = "X-Go-CSRF";

  private static final String COOKIE_ATTRIBUTES = "; Secure; HttpOnly; Path=/; SameSite=Lax";
  private static final String ORIGIN_HEADER = "Origin";
  private static final String REFERER_HEADER = "Referer";

  private GoSessionSecurity() {
    // prevent instantiation
  }

  /**
   * Build the {@code Set-Cookie} value that carries the opaque session token. It is a session
   * cookie (no {@code Max-Age}/{@code Expires}); the real lifetime is enforced server-side.
   *
   * @param tokenValue the opaque session token value (never its hash)
   * @return the {@code Set-Cookie} header value
   * @throws IllegalArgumentException if {@code tokenValue} is blank
   */
  public static String buildSessionCookie(String tokenValue) {
    if (StringUtils.isBlank(tokenValue)) {
      throw new IllegalArgumentException("Session token value must not be blank");
    }
    return COOKIE_NAME + "=" + tokenValue + COOKIE_ATTRIBUTES;
  }

  /**
   * Build the {@code Set-Cookie} value that clears the session cookie on logout.
   *
   * @return the expiring {@code Set-Cookie} header value
   */
  public static String buildExpiredSessionCookie() {
    return COOKIE_NAME + "=" + COOKIE_ATTRIBUTES + "; Max-Age=0";
  }

  /**
   * @param method the HTTP method
   * @return {@code true} for methods that do not change state and are exempt from CSRF checks
   */
  public static boolean isSafeMethod(String method) {
    return "GET".equalsIgnoreCase(method)
        || "HEAD".equalsIgnoreCase(method)
        || "OPTIONS".equalsIgnoreCase(method);
  }

  /**
   * Authorize an incoming request against the CSRF/origin policy. Safe methods always pass; unsafe
   * methods require both a same-origin request and a matching CSRF token.
   *
   * @param request           the incoming request
   * @param expectedCsrfToken the CSRF token bound to the resolved session
   * @return {@code true} if the request may proceed
   */
  public static boolean isUnsafeRequestAuthorized(HttpServletRequest request, String expectedCsrfToken) {
    if (isSafeMethod(request.getMethod())) {
      return true;
    }
    return isOriginAllowed(request) && isCsrfValid(request, expectedCsrfToken);
  }

  /**
   * Validate the request origin against the allowlist, failing closed. Uses the {@code Origin}
   * header when present, falling back to the {@code Referer} origin.
   *
   * @param request the incoming request
   * @return {@code true} if the origin is allowlisted
   */
  public static boolean isOriginAllowed(HttpServletRequest request) {
    String origin = StringUtils.trimToNull(request.getHeader(ORIGIN_HEADER));
    if (origin != null) {
      return CorsUtils.isAllowedOrigin(request, origin);
    }
    String referer = StringUtils.trimToNull(request.getHeader(REFERER_HEADER));
    String refererOrigin = extractOrigin(referer);
    return refererOrigin != null && CorsUtils.isAllowedOrigin(request, refererOrigin);
  }

  /**
   * Constant-time comparison of the CSRF header against the session-bound token.
   *
   * @param request           the incoming request
   * @param expectedCsrfToken the CSRF token bound to the resolved session
   * @return {@code true} if the header is present and matches
   */
  public static boolean isCsrfValid(HttpServletRequest request, String expectedCsrfToken) {
    String provided = StringUtils.trimToNull(request.getHeader(CSRF_HEADER));
    if (provided == null || StringUtils.isBlank(expectedCsrfToken)) {
      return false;
    }
    return MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.UTF_8),
        expectedCsrfToken.getBytes(StandardCharsets.UTF_8));
  }

  private static String extractOrigin(String url) {
    if (url == null) {
      return null;
    }
    try {
      URI uri = new URI(url);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return null;
      }
      StringBuilder origin = new StringBuilder()
          .append(uri.getScheme())
          .append("://")
          .append(uri.getHost());
      int port = uri.getPort();
      if (port >= 0 && port != uri.toURL().getDefaultPort()) {
        origin.append(':').append(port);
      }
      return origin.toString();
    } catch (URISyntaxException | java.net.MalformedURLException ignored) {
      return null;
    }
  }
}
