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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

/**
 * Unit tests for {@link EtendoGoGoogleIdentityVerifier}.
 */
public class EtendoGoGoogleIdentityVerifierTest {

  @Test
  public void validJsonCredentialReturnsGoogleAssertion() throws Exception {
    EtendoGoGoogleIdentityVerifier verifier = verifierReturning(
        new EtendoGoSsoAssertion("google", "sub-123", "user@gmail.com", "User", true));
    HttpServletRequest request = request("application/json");

    EtendoGoSsoAssertion assertion = verifier.verify(request,
        "{\"credential\":\"id-token\"}");

    assertEquals("google", assertion.getProvider());
    assertEquals("sub-123", assertion.getSubject());
    assertEquals("user@gmail.com", assertion.getEmail());
    assertTrue(assertion.isEmailAuthoritative());
  }

  @Test
  public void validFormCredentialReturnsGoogleAssertion() throws Exception {
    EtendoGoGoogleIdentityVerifier verifier = verifierReturning(
        new EtendoGoSsoAssertion("google", "sub-456", "user@example.com", "User", true));
    HttpServletRequest request = requestWithCookie("csrf-2",
        "application/x-www-form-urlencoded");

    EtendoGoSsoAssertion assertion = verifier.verify(request,
        "credential=id-token&g_csrf_token=csrf-2&client_id=client-id.apps.googleusercontent.com");

    assertEquals("sub-456", assertion.getSubject());
    assertTrue(assertion.isEmailAuthoritative());
  }

  @Test
  public void missingConfigurationReturnsServiceUnavailable() throws Exception {
    EtendoGoGoogleIdentityVerifier verifier = new EtendoGoGoogleIdentityVerifier(
        new EtendoGoGoogleIdentityVerifier.GoogleIdentityConfiguration(
            Collections.emptyList(), null),
        (credential, configuration) -> null);
    HttpServletRequest request = request("application/json");

    try {
      verifier.verify(request, "{\"credential\":\"id-token\"}");
    } catch (EtendoGoSsoAssertionException e) {
      assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getStatusCode());
      return;
    }
    throw new AssertionError("Expected SSO assertion exception");
  }

  @Test
  public void mismatchedCsrfTokenReturnsForbidden() throws Exception {
    EtendoGoGoogleIdentityVerifier verifier = verifierReturning(
        new EtendoGoSsoAssertion("google", "sub-123", "user@gmail.com", "User", true));
    HttpServletRequest request = requestWithCookie("cookie-token", "application/json");

    try {
      verifier.verify(request, "{\"credential\":\"id-token\",\"g_csrf_token\":\"body-token\"}");
    } catch (EtendoGoSsoAssertionException e) {
      assertEquals(HttpServletResponse.SC_FORBIDDEN, e.getStatusCode());
      return;
    }
    throw new AssertionError("Expected SSO assertion exception");
  }

  @Test
  public void invalidCredentialReturnsUnauthorized() throws Exception {
    EtendoGoGoogleIdentityVerifier verifier = new EtendoGoGoogleIdentityVerifier(
        configured(),
        (credential, configuration) -> {
          throw new IOException("invalid token");
        });
    HttpServletRequest request = request("application/json");

    try {
      verifier.verify(request, "{\"credential\":\"id-token\"}");
    } catch (EtendoGoSsoAssertionException e) {
      assertEquals(HttpServletResponse.SC_UNAUTHORIZED, e.getStatusCode());
      return;
    }
    throw new AssertionError("Expected SSO assertion exception");
  }

  @Test
  public void thirdPartyEmailIsNotAuthoritativeWithoutHostedDomain() {
    EtendoGoSsoAssertion assertion = new EtendoGoSsoAssertion("google", "sub-789",
        "user@example.com", "User", false);

    assertFalse(assertion.isEmailAuthoritative());
  }

  private static EtendoGoGoogleIdentityVerifier verifierReturning(
      EtendoGoSsoAssertion assertion) {
    return new EtendoGoGoogleIdentityVerifier(configured(),
        (credential, configuration) -> assertion);
  }

  private static EtendoGoGoogleIdentityVerifier.GoogleIdentityConfiguration configured() {
    return new EtendoGoGoogleIdentityVerifier.GoogleIdentityConfiguration(
        Collections.singletonList("client-id.apps.googleusercontent.com"), null);
  }

  private static HttpServletRequest requestWithCookie(String csrfToken, String contentType) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn(contentType);
    when(request.getCookies()).thenReturn(new Cookie[] {
        new Cookie(EtendoGoGoogleIdentityVerifier.CSRF_COOKIE, csrfToken)
    });
    return request;
  }

  private static HttpServletRequest request(String contentType) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn(contentType);
    when(request.getCookies()).thenReturn(null);
    return request;
  }
}
