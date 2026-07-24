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
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * Red-first unit tests for {@link GoSessionAuthenticator} (ETP-4575): the cookie → auth decision,
 * including CSRF/Origin enforcement on unsafe methods. {@link GoSessionService} is mocked, so no DB.
 */
public class GoSessionAuthenticatorTest {

  private static final String APP_ORIGIN = "https://app.example.test";
  private static final String APP_URL = APP_ORIGIN + "/sws/neo/foo";
  private static final String RAW_TOKEN = "opaque-session-token";
  private static final String CSRF = "csrf-token-1234567890";

  @Test
  public void noCookieYieldsNoSession() {
    GoSessionService service = mock(GoSessionService.class);
    HttpServletRequest req = mockRequest("GET", null, null, null);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.NO_SESSION, result.getStatus());
  }

  @Test
  public void unknownCookieNameYieldsNoSession() {
    GoSessionService service = mock(GoSessionService.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getCookies()).thenReturn(new Cookie[] { new Cookie("other", "x") });

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.NO_SESSION, result.getStatus());
  }

  @Test
  public void invalidSessionYieldsUnauthenticated() {
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(null);
    HttpServletRequest req = mockRequest("GET", RAW_TOKEN, null, null);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.UNAUTHENTICATED, result.getStatus());
  }

  @Test
  public void validSessionOnSafeMethodIsAuthenticated() {
    GoSessionRecord record = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(record);
    HttpServletRequest req = mockRequest("GET", RAW_TOKEN, null, null);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.AUTHENTICATED, result.getStatus());
    assertSame(record, result.getRecord());
  }

  @Test
  public void validSessionOnUnsafeMethodWithValidCsrfIsAuthenticated() {
    GoSessionRecord record = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(record);
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, APP_ORIGIN, CSRF);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.AUTHENTICATED, result.getStatus());
  }

  @Test
  public void validSessionOnUnsafeMethodWithoutCsrfFailsCsrf() {
    GoSessionRecord record = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(record);
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, APP_ORIGIN, null);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.CSRF_FAILED, result.getStatus());
  }

  @Test
  public void validSessionOnUnsafeMethodWithForeignOriginFailsCsrf() {
    GoSessionRecord record = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(record);
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, "https://evil.example.test", CSRF);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.CSRF_FAILED, result.getStatus());
  }

  private static GoSessionRecord recordWithCsrf(String csrf) {
    GoSessionRecord record = new GoSessionRecord();
    record.setCsrfToken(csrf);
    return record;
  }

  private static HttpServletRequest mockRequest(String method, String cookieValue, String origin,
      String csrfHeader) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getHeader("Origin")).thenReturn(origin);
    when(req.getHeader("Referer")).thenReturn(null);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(csrfHeader);
    when(req.getRequestURL()).thenReturn(new StringBuffer(APP_URL));
    if (cookieValue != null) {
      when(req.getCookies()).thenReturn(
          new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, cookieValue) });
    } else {
      when(req.getCookies()).thenReturn(null);
    }
    return req;
  }
}
