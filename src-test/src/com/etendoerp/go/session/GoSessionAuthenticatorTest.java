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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * Red-first unit tests for {@link GoSessionAuthenticator} (ETP-4575): the cookie → auth decision,
 * including CSRF/Origin enforcement on unsafe methods and the ETP-5675 account header on every
 * method. {@link GoSessionService} is mocked, so no DB.
 *
 * @covers com.etendoerp.go.session.GoSessionAuthenticator
 */
public class GoSessionAuthenticatorTest {

  private static final String APP_ORIGIN = "https://app.example.test";
  private static final String APP_URL = APP_ORIGIN + "/sws/neo/foo";
  private static final String RAW_TOKEN = "opaque-session-token";
  private static final String CSRF = "csrf-token-1234567890";
  private static final String CSRF_TOKEN_INVALID = "CSRF validation failed";
  private static final String ORIGIN_NOT_ALLOWED = "Origin not allowed";
  private static final String ACCOUNT_MISMATCH = "Session belongs to another account";
  private static final String SESSION_ACCOUNT = "ACCOUNT-B";
  private static final String OTHER_ACCOUNT = "ACCOUNT-C";

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
    GoSessionRecord sessionRecord = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(sessionRecord);
    HttpServletRequest req = mockRequest("GET", RAW_TOKEN, null, null);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.AUTHENTICATED, result.getStatus());
    assertSame(sessionRecord, result.getRecord());
  }

  @Test
  public void validSessionOnUnsafeMethodWithValidCsrfIsAuthenticated() {
    GoSessionRecord sessionRecord = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(sessionRecord);
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, APP_ORIGIN, CSRF);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.AUTHENTICATED, result.getStatus());
  }

  @Test
  public void validSessionOnUnsafeMethodWithoutCsrfFailsCsrf() {
    GoSessionRecord sessionRecord = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(sessionRecord);
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, APP_ORIGIN, null);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.CSRF_FAILED, result.getStatus());
    assertEquals(CSRF_TOKEN_INVALID, result.getRefusalMessage());
  }

  /**
   * ETP-5550 — a tab still holding the CSRF token of a session another tab rotated away. The exact
   * message is the contract the client reads to re-read the session and retry once.
   */
  @Test
  public void validSessionOnUnsafeMethodWithAStaleCsrfFailsTheToken() {
    GoSessionRecord sessionRecord = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(sessionRecord);
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, APP_ORIGIN, "csrf-of-a-rotated-session");

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.CSRF_FAILED, result.getStatus());
    assertEquals(CSRF_TOKEN_INVALID, result.getRefusalMessage());
  }

  @Test
  public void validSessionOnUnsafeMethodWithForeignOriginFailsCsrf() {
    GoSessionRecord sessionRecord = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(sessionRecord);
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, "https://evil.example.test", CSRF);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.CSRF_FAILED, result.getStatus());
    assertEquals(ORIGIN_NOT_ALLOWED, result.getRefusalMessage());
  }

  /** A cross-site request is refused for its origin, so the client never retries it. */
  @Test
  public void foreignOriginWinsOverAMissingCsrf() {
    GoSessionRecord sessionRecord = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(sessionRecord);
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, "https://evil.example.test", null);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(ORIGIN_NOT_ALLOWED, result.getRefusalMessage());
  }

  @Test
  public void authenticatedRequestRenewsTheIdleExpiry() {
    GoSessionRecord sessionRecord = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(sessionRecord);
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, APP_ORIGIN, CSRF);

    new GoSessionAuthenticator(service).authenticate(req);

    verify(service).renewIdleExpiry(sessionRecord);
  }

  @Test
  public void csrfRejectedRequestDoesNotRenewTheIdleExpiry() {
    GoSessionRecord sessionRecord = recordWithCsrf(CSRF);
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(sessionRecord);
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, APP_ORIGIN, null);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.CSRF_FAILED, result.getStatus());
    verify(service, never()).renewIdleExpiry(any());
  }

  @Test
  public void invalidSessionDoesNotRenewTheIdleExpiry() {
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(null);
    HttpServletRequest req = mockRequest("GET", RAW_TOKEN, null, null);

    new GoSessionAuthenticator(service).authenticate(req);

    verify(service, never()).renewIdleExpiry(any());
  }

  // ===================== ETP-5675 — account header =====================

  @Test
  public void readFromATabOfAnotherAccountIsRefused() {
    GoSessionService service = serviceResolving(recordForAccount(SESSION_ACCOUNT));
    HttpServletRequest req = mockRequest("GET", RAW_TOKEN, null, null);
    when(req.getHeader(GoSessionSecurity.ACCOUNT_HEADER)).thenReturn(OTHER_ACCOUNT);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.CSRF_FAILED, result.getStatus());
    assertEquals(ACCOUNT_MISMATCH, result.getRefusalMessage());
    verify(service, never()).renewIdleExpiry(any());
  }

  @Test
  public void accountMismatchIsReportedBeforeAStaleCsrf() {
    GoSessionService service = serviceResolving(recordForAccount(SESSION_ACCOUNT));
    HttpServletRequest req = mockRequest("POST", RAW_TOKEN, APP_ORIGIN, "stale-proof");
    when(req.getHeader(GoSessionSecurity.ACCOUNT_HEADER)).thenReturn(OTHER_ACCOUNT);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(ACCOUNT_MISMATCH, result.getRefusalMessage());
  }

  @Test
  public void matchingAccountHeaderIsAuthenticated() {
    GoSessionRecord sessionRecord = recordForAccount(SESSION_ACCOUNT);
    GoSessionService service = serviceResolving(sessionRecord);
    HttpServletRequest req = mockRequest("GET", RAW_TOKEN, null, null);
    when(req.getHeader(GoSessionSecurity.ACCOUNT_HEADER)).thenReturn(SESSION_ACCOUNT);

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.AUTHENTICATED, result.getStatus());
    assertSame(sessionRecord, result.getRecord());
  }

  @Test
  public void blankAccountHeaderIsNotChecked() {
    GoSessionService service = serviceResolving(recordForAccount(SESSION_ACCOUNT));
    HttpServletRequest req = mockRequest("GET", RAW_TOKEN, null, null);
    when(req.getHeader(GoSessionSecurity.ACCOUNT_HEADER)).thenReturn("  ");

    GoSessionAuthResult result = new GoSessionAuthenticator(service).authenticate(req);

    assertEquals(GoSessionAuthResult.Status.AUTHENTICATED, result.getStatus());
  }

  private static GoSessionService serviceResolving(GoSessionRecord sessionRecord) {
    GoSessionService service = mock(GoSessionService.class);
    when(service.resolve(RAW_TOKEN)).thenReturn(sessionRecord);
    return service;
  }

  private static GoSessionRecord recordForAccount(String accountId) {
    GoSessionRecord sessionRecord = recordWithCsrf(CSRF);
    sessionRecord.setAccountId(accountId);
    return sessionRecord;
  }

  private static GoSessionRecord recordWithCsrf(String csrf) {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setCsrfToken(csrf);
    return sessionRecord;
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
