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
package com.etendoerp.go.oauth2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.supportaccess.SupportAccessGuard;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5351 (T5) — a support session never obtains a credential that outlives it: no OAuth2
 * authorization code (the MCP connector flow), no API key secret.
 */
class OAuth2SupportSessionTest {

  private static final String ORIGIN = "https://app.example.test";
  private static final String CSRF = "csrf-token-value-123456";
  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String SUPPORT_USER = SupportAccessGuard.supportUserIdFor(CLIENT);

  private final GoSessionService sessionService = mock(GoSessionService.class);

  @Test
  void aSupportCookieSessionCannotAuthorizeAnOAuthClient() {
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId(SupportAccessGuard.SUPPORT_ACCOUNT_ID);
    session.setSupportAccessId("ACCESS1");
    session.setUserId(SUPPORT_USER);
    session.setRoleId("R1");
    session.setCtxClientId(CLIENT);
    session.setCsrfToken(CSRF);
    when(sessionService.resolve("tok")).thenReturn(session);

    OAuth2Servlet.AuthException e = assertThrows(OAuth2Servlet.AuthException.class,
        () -> OAuth2RequestAuthenticator.authenticateAuthorizeRequest(sessionService,
            mock(GoSessionRoleReconciler.class), cookieRequest(), authorizeRequest(null)));

    assertEquals(403, e.statusCode);
  }

  @Test
  void aSupportUserJwtCannotAuthorizeAnOAuthClient() {
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim user = claim(SUPPORT_USER);
    Claim role = claim("R1");
    Claim client = claim(CLIENT);
    when(jwt.getClaim("user")).thenReturn(user);
    when(jwt.getClaim("role")).thenReturn(role);
    when(jwt.getClaim("client")).thenReturn(client);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");

    try (MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      sws.when(() -> SecureWebServicesUtils.decodeToken("jwt")).thenReturn(jwt);
      OAuth2Servlet.AuthException e = assertThrows(OAuth2Servlet.AuthException.class,
          () -> OAuth2RequestAuthenticator.authenticateAuthorizeRequest(sessionService,
              mock(GoSessionRoleReconciler.class), request, authorizeRequest("jwt")));
      assertEquals(403, e.statusCode);
    }
  }

  @Test
  void theSupportUserCannotMintAnApiKey() {
    OAuth2Servlet.AuthException e = assertThrows(OAuth2Servlet.AuthException.class,
        () -> OAuth2RequestAuthenticator.requireCredentialMintingAllowed(SUPPORT_USER, CLIENT));
    assertEquals(403, e.statusCode);
  }

  @Test
  void anOrdinaryUserStillMintsApiKeys() {
    assertDoesNotThrow(() -> OAuth2RequestAuthenticator.requireCredentialMintingAllowed("100", CLIENT));
  }

  private static OAuth2AuthorizeSupport.AuthorizeRequestData authorizeRequest(String jwt) {
    return new OAuth2AuthorizeSupport.AuthorizeRequestData(jwt, "etgo-client",
        "https://claude.ai/callback", "challenge", "state", "neo:read", 3600);
  }

  private static HttpServletRequest cookieRequest() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getHeader("Origin")).thenReturn(ORIGIN);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(CSRF);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + "/oauth2/authorize"));
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "tok") });
    return req;
  }

  private static Claim claim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }
}
