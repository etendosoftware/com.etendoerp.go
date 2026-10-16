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
package com.etendoerp.go.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.SessionRoleRevokedException;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5270 — {@code POST /oauth2/authorize} through the legacy JWT fallback. The authorized client
 * keeps the role for as long as its tokens live, so a role revoked since the JWT was issued must
 * not be handed to it — the cookie branch already refuses it (ETP-5395), this is the JWT branch.
 */
class OAuth2RequestAuthenticatorTest {

  private static final String JWT_TOKEN = "legacy-jwt";
  private static final String USER_ID = "user-1";
  private static final String ROLE_ID = "role-1";
  private static final String CLIENT_ID = "client-1";
  private static final String LEGACY_BEARER_PROPERTY = "etgo.legacy.bearer.enabled";

  private GoSessionRoleReconciler reconciler;
  private HttpServletRequest request;
  private MockedStatic<SecureWebServicesUtils> swsStatic;

  @BeforeEach
  void setUp() {
    reconciler = mock(GoSessionRoleReconciler.class);
    request = mock(HttpServletRequest.class); // no cookie: the JWT fallback is the path taken
    swsStatic = mockStatic(SecureWebServicesUtils.class);
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim user = stringClaim(USER_ID);
    Claim role = stringClaim(ROLE_ID);
    Claim client = stringClaim(CLIENT_ID);
    when(jwt.getClaim("user")).thenReturn(user);
    when(jwt.getClaim("role")).thenReturn(role);
    when(jwt.getClaim("client")).thenReturn(client);
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(JWT_TOKEN)).thenReturn(jwt);
    System.setProperty(LEGACY_BEARER_PROPERTY, "true");
  }

  @AfterEach
  void tearDown() {
    swsStatic.close();
    System.clearProperty(LEGACY_BEARER_PROPERTY);
  }

  @Test
  void aJwtWhoseRoleIsStillHeldIsAuthorizedWithIt() throws Exception {
    OAuth2RequestAuthenticator.AuthorizePrincipal principal = authorize();

    assertEquals(USER_ID, principal.userId);
    assertEquals(ROLE_ID, principal.roleId);
    verify(reconciler).requireHeldRole(USER_ID, ROLE_ID, CLIENT_ID);
  }

  @Test
  void aJwtWhoseRoleWasRevokedIsRefusedWith403() {
    doThrow(new SessionRoleRevokedException(GoSessionRoleReconciler.MSG_TOKEN_ROLE_REVOKED))
        .when(reconciler).requireHeldRole(USER_ID, ROLE_ID, CLIENT_ID);

    OAuth2Servlet.AuthException refused =
        assertThrows(OAuth2Servlet.AuthException.class, this::authorize);

    assertEquals(403, refused.statusCode);
    assertEquals(GoSessionRoleReconciler.MSG_TOKEN_ROLE_REVOKED, refused.getMessage());
  }

  private OAuth2RequestAuthenticator.AuthorizePrincipal authorize() throws Exception {
    OAuth2AuthorizeSupport.AuthorizeRequestData authorizeRequest =
        new OAuth2AuthorizeSupport.AuthorizeRequestData(JWT_TOKEN, "client", "https://cb.test",
            "challenge", null, "neo:read", -1L);
    return OAuth2RequestAuthenticator.authenticateAuthorizeRequest(mock(GoSessionService.class),
        reconciler, request, authorizeRequest);
  }

  private static Claim stringClaim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }
}
