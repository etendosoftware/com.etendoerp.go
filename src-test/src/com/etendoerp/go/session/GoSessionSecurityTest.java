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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * Red-first unit tests for {@link GoSessionSecurity} (ETP-4575).
 *
 * <p>Covers the cookie contract (SEC-10) and cross-site CSRF acceptance criteria without touching
 * the database: cookie attribute building and Origin/CSRF validation on unsafe methods.
 */
public class GoSessionSecurityTest {

  private static final String APP_ORIGIN = "https://app.example.test";
  private static final String APP_URL = APP_ORIGIN + "/sws/go/session";
  private static final String CSRF = "csrf-token-value-1234567890";

  // ===================== Cookie contract =====================

  @Test
  public void sessionCookieHasHostPrefixAndSecurityAttributes() {
    String cookie = GoSessionSecurity.buildSessionCookie("opaque-session-value");

    assertTrue(cookie.startsWith("__Host-go_session=opaque-session-value"));
    assertTrue(cookie.contains("Secure"));
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("Path=/"));
    assertTrue(cookie.contains("SameSite=Lax"));
    // __Host- prefix forbids Domain; session cookie forbids a persistent lifetime.
    assertFalse(cookie.contains("Domain="));
    assertFalse(cookie.contains("Max-Age"));
    assertFalse(cookie.contains("Expires="));
  }

  @Test(expected = IllegalArgumentException.class)
  public void sessionCookieRejectsBlankValue() {
    GoSessionSecurity.buildSessionCookie("");
  }

  @Test
  public void expiredCookieClearsWithMaxAgeZero() {
    String cookie = GoSessionSecurity.buildExpiredSessionCookie();

    assertTrue(cookie.startsWith("__Host-go_session="));
    assertTrue(cookie.contains("Max-Age=0"));
    assertTrue(cookie.contains("Secure"));
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("Path=/"));
    assertTrue(cookie.contains("SameSite=Lax"));
  }

  // ===================== CSRF / Origin on unsafe methods =====================

  @Test
  public void safeMethodsDoNotRequireCsrf() {
    HttpServletRequest req = mockRequest("GET", null, null);

    assertTrue(GoSessionSecurity.isUnsafeRequestAuthorized(req, CSRF));
  }

  @Test
  public void crossSiteUnsafeRequestWithoutCsrfIsDenied() {
    HttpServletRequest req = mockRequest("POST", "https://evil.example.test", null);

    assertFalse(GoSessionSecurity.isUnsafeRequestAuthorized(req, CSRF));
  }

  @Test
  public void sameOriginUnsafeRequestWithValidCsrfIsAllowed() {
    HttpServletRequest req = mockRequest("POST", APP_ORIGIN, CSRF);

    assertTrue(GoSessionSecurity.isUnsafeRequestAuthorized(req, CSRF));
  }

  @Test
  public void unsafeRequestMissingCsrfHeaderIsDenied() {
    HttpServletRequest req = mockRequest("POST", APP_ORIGIN, null);

    assertFalse(GoSessionSecurity.isUnsafeRequestAuthorized(req, CSRF));
  }

  @Test
  public void unsafeRequestWithForeignOriginIsDenied() {
    HttpServletRequest req = mockRequest("POST", "https://evil.example.test", CSRF);

    assertFalse(GoSessionSecurity.isUnsafeRequestAuthorized(req, CSRF));
  }

  @Test
  public void csrfTokenMismatchIsDenied() {
    HttpServletRequest req = mockRequest("POST", APP_ORIGIN, "some-other-token");

    assertFalse(GoSessionSecurity.isUnsafeRequestAuthorized(req, CSRF));
  }

  @Test
  public void unsafeRequestWithNoOriginNorRefererIsDenied() {
    HttpServletRequest req = mockRequest("POST", null, CSRF);

    assertFalse(GoSessionSecurity.isUnsafeRequestAuthorized(req, CSRF));
  }

  private static HttpServletRequest mockRequest(String method, String origin, String csrfHeader) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getHeader("Origin")).thenReturn(origin);
    when(req.getHeader("Referer")).thenReturn(null);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(csrfHeader);
    when(req.getRequestURL()).thenReturn(new StringBuffer(APP_URL));
    return req;
  }
}
