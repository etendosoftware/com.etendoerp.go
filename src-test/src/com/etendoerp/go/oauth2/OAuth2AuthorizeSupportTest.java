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
package com.etendoerp.go.oauth2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * ETP-4393 — unit tests for {@link OAuth2AuthorizeSupport}: parsing the
 * {@code validity_seconds} authorize-request parameter (JSON and form-encoded paths) and
 * propagating the normalized value into {@link OAuth2Servlet.AuthCodeData}.
 */
public class OAuth2AuthorizeSupportTest {

  private static final String APPLICATION_JSON = "application/json";

  // ===================== parseAuthorizeRequest — JSON body path =====================

  @Test
  public void parseAuthorizeRequestJsonBodyReadsValiditySecondsViaOptLong() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn(APPLICATION_JSON);

    JSONObject payload = new JSONObject();
    payload.put("token", "jwt-token");
    payload.put("client_id", "client-1");
    payload.put("redirect_uri", "https://example.com/cb");
    payload.put("code_challenge", "challenge");
    payload.put("state", "state-1");
    payload.put("scope", "neo:read");
    payload.put("validity_seconds", 604_800L);

    OAuth2AuthorizeSupport.AuthorizeRequestData data =
        OAuth2AuthorizeSupport.parseAuthorizeRequest(request, APPLICATION_JSON,
            req -> payload);

    assertEquals("jwt-token", data.jwtToken);
    assertEquals("client-1", data.clientId);
    assertEquals("https://example.com/cb", data.redirectUri);
    assertEquals("challenge", data.codeChallenge);
    assertEquals("state-1", data.state);
    assertEquals("neo:read", data.scope);
    assertEquals(604_800L, data.validitySeconds);
  }

  @Test
  public void parseAuthorizeRequestJsonBodyDefaultsValiditySecondsToAbsentSentinelWhenMissing()
      throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn(APPLICATION_JSON);

    JSONObject payload = new JSONObject();
    payload.put("token", "jwt-token");
    payload.put("client_id", "client-1");
    payload.put("redirect_uri", "https://example.com/cb");
    payload.put("code_challenge", "challenge");
    // validity_seconds intentionally omitted.

    OAuth2AuthorizeSupport.AuthorizeRequestData data =
        OAuth2AuthorizeSupport.parseAuthorizeRequest(request, APPLICATION_JSON,
            req -> payload);

    assertEquals(-1L, data.validitySeconds);
  }

  // ===================== parseAuthorizeRequest — form-encoded path =====================

  @Test
  public void parseAuthorizeRequestFormPathParsesNumericValiditySeconds() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(request.getParameter("token")).thenReturn("jwt-token");
    when(request.getParameter("client_id")).thenReturn("client-1");
    when(request.getParameter("redirect_uri")).thenReturn("https://example.com/cb");
    when(request.getParameter("code_challenge")).thenReturn("challenge");
    when(request.getParameter("validity_seconds")).thenReturn("2592000");

    OAuth2AuthorizeSupport.AuthorizeRequestData data =
        OAuth2AuthorizeSupport.parseAuthorizeRequest(request, APPLICATION_JSON, req -> null);

    assertEquals(2_592_000L, data.validitySeconds);
  }

  @Test
  public void parseAuthorizeRequestFormPathNullValiditySecondsIsAbsentSentinel() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(request.getParameter("validity_seconds")).thenReturn(null);

    OAuth2AuthorizeSupport.AuthorizeRequestData data =
        OAuth2AuthorizeSupport.parseAuthorizeRequest(request, APPLICATION_JSON, req -> null);

    assertEquals(-1L, data.validitySeconds);
  }

  @Test
  public void parseAuthorizeRequestFormPathBlankValiditySecondsIsAbsentSentinel() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(request.getParameter("validity_seconds")).thenReturn("   ");

    OAuth2AuthorizeSupport.AuthorizeRequestData data =
        OAuth2AuthorizeSupport.parseAuthorizeRequest(request, APPLICATION_JSON, req -> null);

    assertEquals(-1L, data.validitySeconds);
  }

  @Test
  public void parseAuthorizeRequestFormPathNonNumericValiditySecondsIsAbsentSentinel()
      throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(request.getParameter("validity_seconds")).thenReturn("not-a-number");

    OAuth2AuthorizeSupport.AuthorizeRequestData data =
        OAuth2AuthorizeSupport.parseAuthorizeRequest(request, APPLICATION_JSON, req -> null);

    assertEquals(-1L, data.validitySeconds);
  }

  @Test
  public void parseAuthorizeRequestFormPathNegativeValiditySecondsIsParsedAsIs() throws Exception {
    // The raw form value itself is preserved here; normalization to the default happens
    // downstream in normalizeValiditySeconds/buildAuthCodeData, not during parsing.
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(request.getParameter("validity_seconds")).thenReturn("-99");

    OAuth2AuthorizeSupport.AuthorizeRequestData data =
        OAuth2AuthorizeSupport.parseAuthorizeRequest(request, APPLICATION_JSON, req -> null);

    assertEquals(-99L, data.validitySeconds);
  }

  // ===================== buildAuthCodeData — validitySeconds normalization =====================

  private static OAuth2AuthorizeSupport.AuthorizeRequestData authorizeRequestWithValidity(
      long validitySeconds) {
    return new OAuth2AuthorizeSupport.AuthorizeRequestData(
        "jwt-token", "client-1", "https://example.com/cb", "challenge", "state-1",
        "neo:read", validitySeconds);
  }

  @Test
  public void buildAuthCodeDataSetsExplicitRequestedValidity() {
    Set<String> requested = Collections.singleton("neo:read");
    Set<String> allowed = new LinkedHashSet<>(Collections.singletonList("neo:read"));

    OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
        authorizeRequestWithValidity(604_800L), "user-1", "role-1",
        requested, allowed, "neo:*", 300_000);

    assertEquals(604_800L, codeData.validitySeconds);
  }

  @Test
  public void buildAuthCodeDataNormalizesAbsentSentinelToDefault() {
    Set<String> requested = Collections.singleton("neo:read");
    Set<String> allowed = new LinkedHashSet<>(Collections.singletonList("neo:read"));

    OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
        authorizeRequestWithValidity(-1L), "user-1", "role-1",
        requested, allowed, "neo:*", 300_000);

    assertEquals(86_400L, codeData.validitySeconds);
  }

  @Test
  public void buildAuthCodeDataPreservesZeroAsNoExpiration() {
    Set<String> requested = Collections.singleton("neo:read");
    Set<String> allowed = new LinkedHashSet<>(Collections.singletonList("neo:read"));

    OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
        authorizeRequestWithValidity(0L), "user-1", "role-1",
        requested, allowed, "neo:*", 300_000);

    assertEquals(0L, codeData.validitySeconds);
  }

  @Test
  public void buildAuthCodeDataClampsExcessiveValidityToMax() {
    Set<String> requested = Collections.singleton("neo:read");
    Set<String> allowed = new LinkedHashSet<>(Collections.singletonList("neo:read"));

    OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
        authorizeRequestWithValidity(99_999_999L), "user-1", "role-1",
        requested, allowed, "neo:*", 300_000);

    assertEquals(2_592_000L, codeData.validitySeconds);
  }

  @Test
  public void buildAuthCodeDataClampsBelowMinValidityToMin() {
    Set<String> requested = Collections.singleton("neo:read");
    Set<String> allowed = new LinkedHashSet<>(Collections.singletonList("neo:read"));

    OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
        authorizeRequestWithValidity(60L), "user-1", "role-1",
        requested, allowed, "neo:*", 300_000);

    assertEquals(300L, codeData.validitySeconds);
  }

  @Test
  public void buildAuthCodeDataRejectsNullAuthorizeRequest() {
    Set<String> requested = Collections.singleton("neo:read");
    Set<String> allowed = new LinkedHashSet<>(Collections.singletonList("neo:read"));

    try {
      OAuth2AuthorizeSupport.buildAuthCodeData(null, "user-1", "role-1",
          requested, allowed, "neo:*", 300_000);
      fail("Expected IllegalArgumentException — authorize request must not be null");
    } catch (IllegalArgumentException expected) {
      // ok
    }
  }
}
