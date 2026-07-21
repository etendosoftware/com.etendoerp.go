/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.etendoerp.go.oauth2.OAuth2AuthorizeSupport.AuthorizeRequestData;

/**
 * Unit tests for {@link OAuth2AuthorizeSupport}: request parsing (including the
 * {@code validity_seconds} authorize-request parameter — ETP-4393), auth-code data assembly and
 * the success redirect writer. No servlet container or DAL model is required.
 */
class OAuth2AuthorizeSupportTest {

  private static final String APPLICATION_JSON = "application/json";

  @Test
  @DisplayName("is a utility class with a private, side-effect-free constructor")
  void utilityClassHasPrivateConstructor() throws Exception {
    Constructor<OAuth2AuthorizeSupport> constructor =
        OAuth2AuthorizeSupport.class.getDeclaredConstructor();
    assertTrue(Modifier.isPrivate(constructor.getModifiers()),
        "OAuth2AuthorizeSupport must not be instantiable from outside");
    constructor.setAccessible(true);
    assertNotNull(constructor.newInstance());
  }

  @Nested
  @DisplayName("parseAuthorizeRequest")
  class ParseAuthorizeRequest {

    @Test
    @DisplayName("reads the fields from the JSON body when content type is application/json")
    void jsonBody() throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentType()).thenReturn("application/json; charset=UTF-8");
      JSONObject body = new JSONObject()
          .put("token", "jwt-1")
          .put("client_id", "client-1")
          .put("redirect_uri", "https://app/cb")
          .put("code_challenge", "chal")
          .put("state", "st")
          .put("scope", "neo:read");

      AuthorizeRequestData data = OAuth2AuthorizeSupport.parseAuthorizeRequest(
          request, APPLICATION_JSON, req -> body);

      assertEquals("jwt-1", data.jwtToken);
      assertEquals("client-1", data.clientId);
      assertEquals("https://app/cb", data.redirectUri);
      assertEquals("chal", data.codeChallenge);
      assertEquals("st", data.state);
      assertEquals("neo:read", data.scope);
    }

    @Test
    @DisplayName("falls back to form parameters when content type is not JSON")
    void formParameters() throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
      when(request.getParameter("token")).thenReturn("jwt-2");
      when(request.getParameter("client_id")).thenReturn("client-2");
      when(request.getParameter("redirect_uri")).thenReturn("https://app/cb2");
      when(request.getParameter("code_challenge")).thenReturn("chal2");
      when(request.getParameter("state")).thenReturn("st2");
      when(request.getParameter("scope")).thenReturn("neo:write");

      AuthorizeRequestData data = OAuth2AuthorizeSupport.parseAuthorizeRequest(
          request, APPLICATION_JSON, req -> {
            throw new AssertionError("JSON parser must not be invoked for form requests");
          });

      assertEquals("jwt-2", data.jwtToken);
      assertEquals("client-2", data.clientId);
      assertEquals("https://app/cb2", data.redirectUri);
      assertEquals("neo:write", data.scope);
    }

    @Test
    @DisplayName("treats a null content type as a form request")
    void nullContentType() throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentType()).thenReturn(null);
      when(request.getParameter("token")).thenReturn("jwt-3");

      AuthorizeRequestData data = OAuth2AuthorizeSupport.parseAuthorizeRequest(
          request, APPLICATION_JSON, req -> {
            throw new AssertionError("JSON parser must not be invoked");
          });

      assertEquals("jwt-3", data.jwtToken);
    }

    // ---- validity_seconds parsing (ETP-4393) ----

    @Test
    @DisplayName("JSON body reads validity_seconds via optLong")
    void jsonBodyReadsValiditySecondsViaOptLong() throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentType()).thenReturn(APPLICATION_JSON);

      JSONObject payload = new JSONObject()
          .put("token", "jwt-token")
          .put("client_id", "client-1")
          .put("redirect_uri", "https://example.com/cb")
          .put("code_challenge", "challenge")
          .put("state", "state-1")
          .put("scope", "neo:read")
          .put("validity_seconds", 604_800L);

      AuthorizeRequestData data = OAuth2AuthorizeSupport.parseAuthorizeRequest(
          request, APPLICATION_JSON, req -> payload);

      assertEquals(604_800L, data.validitySeconds);
    }

    @Test
    @DisplayName("JSON body defaults validity_seconds to the absent sentinel when missing")
    void jsonBodyDefaultsValiditySecondsToAbsentSentinelWhenMissing() throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentType()).thenReturn(APPLICATION_JSON);

      JSONObject payload = new JSONObject()
          .put("token", "jwt-token")
          .put("client_id", "client-1")
          .put("redirect_uri", "https://example.com/cb")
          .put("code_challenge", "challenge");
      // validity_seconds intentionally omitted.

      AuthorizeRequestData data = OAuth2AuthorizeSupport.parseAuthorizeRequest(
          request, APPLICATION_JSON, req -> payload);

      assertEquals(-1L, data.validitySeconds);
    }

    @Test
    @DisplayName("form path parses a numeric validity_seconds")
    void formPathParsesNumericValiditySeconds() throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
      when(request.getParameter("token")).thenReturn("jwt-token");
      when(request.getParameter("client_id")).thenReturn("client-1");
      when(request.getParameter("redirect_uri")).thenReturn("https://example.com/cb");
      when(request.getParameter("code_challenge")).thenReturn("challenge");
      when(request.getParameter("validity_seconds")).thenReturn("2592000");

      AuthorizeRequestData data = OAuth2AuthorizeSupport.parseAuthorizeRequest(
          request, APPLICATION_JSON, req -> null);

      assertEquals(2_592_000L, data.validitySeconds);
    }

    @Test
    @DisplayName("form path treats a null validity_seconds as the absent sentinel")
    void formPathNullValiditySecondsIsAbsentSentinel() throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
      when(request.getParameter("validity_seconds")).thenReturn(null);

      AuthorizeRequestData data = OAuth2AuthorizeSupport.parseAuthorizeRequest(
          request, APPLICATION_JSON, req -> null);

      assertEquals(-1L, data.validitySeconds);
    }

    @Test
    @DisplayName("form path treats a blank validity_seconds as the absent sentinel")
    void formPathBlankValiditySecondsIsAbsentSentinel() throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
      when(request.getParameter("validity_seconds")).thenReturn("   ");

      AuthorizeRequestData data = OAuth2AuthorizeSupport.parseAuthorizeRequest(
          request, APPLICATION_JSON, req -> null);

      assertEquals(-1L, data.validitySeconds);
    }

    @Test
    @DisplayName("form path treats a non-numeric validity_seconds as the absent sentinel")
    void formPathNonNumericValiditySecondsIsAbsentSentinel() throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
      when(request.getParameter("validity_seconds")).thenReturn("not-a-number");

      AuthorizeRequestData data = OAuth2AuthorizeSupport.parseAuthorizeRequest(
          request, APPLICATION_JSON, req -> null);

      assertEquals(-1L, data.validitySeconds);
    }

    @Test
    @DisplayName("form path preserves a negative validity_seconds as-is (normalization is downstream)")
    void formPathNegativeValiditySecondsIsParsedAsIs() throws Exception {
      // The raw form value itself is preserved here; normalization to the default happens
      // downstream in normalizeValiditySeconds/buildAuthCodeData, not during parsing.
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
      when(request.getParameter("validity_seconds")).thenReturn("-99");

      AuthorizeRequestData data = OAuth2AuthorizeSupport.parseAuthorizeRequest(
          request, APPLICATION_JSON, req -> null);

      assertEquals(-99L, data.validitySeconds);
    }
  }

  @Nested
  @DisplayName("buildAuthCodeData")
  class BuildAuthCodeData {

    private AuthorizeRequestData authorize() {
      return authorizeWithValidity(-1L);
    }

    private AuthorizeRequestData authorizeWithValidity(long validitySeconds) {
      return new AuthorizeRequestData("jwt", "client-9", "https://app/cb", "challenge", "st",
          "neo:read", validitySeconds);
    }

    @Test
    @DisplayName("rejects null authorize data")
    void nullAuthorize() {
      assertThrows(IllegalArgumentException.class, () -> OAuth2AuthorizeSupport.buildAuthCodeData(
          null, "u", "r", set("neo:read"), set("neo:read"), "*", 1000L));
    }

    @Test
    @DisplayName("rejects null requested scopes")
    void nullRequestedScopes() {
      assertThrows(IllegalArgumentException.class, () -> OAuth2AuthorizeSupport.buildAuthCodeData(
          authorize(), "u", "r", null, set("neo:read"), "*", 1000L));
    }

    @Test
    @DisplayName("copies identity fields from the authorize request")
    void copiesIdentity() {
      OAuth2Servlet.AuthCodeData data = OAuth2AuthorizeSupport.buildAuthCodeData(
          authorize(), "user-1", "role-1", set("neo:read"), set("neo:read"), "*", 5000L);

      assertEquals("client-9", data.clientId);
      assertEquals("user-1", data.userId);
      assertEquals("role-1", data.roleId);
      assertEquals("https://app/cb", data.redirectUri);
      assertEquals("challenge", data.codeChallenge);
      assertFalse(data.used);
      assertTrue(data.expiresAt > System.currentTimeMillis());
    }

    @Test
    @DisplayName("grants all allowed scopes when none are explicitly requested")
    void emptyRequestGrantsAllowed() {
      OAuth2Servlet.AuthCodeData data = OAuth2AuthorizeSupport.buildAuthCodeData(
          authorize(), "u", "r", Collections.emptySet(), set("neo:read", "neo:write"), "*", 1000L);

      assertEquals("neo:read neo:write", data.scopes);
    }

    @Test
    @DisplayName("grants exactly the requested scopes when the wildcard scope is allowed")
    void wildcardGrantsRequested() {
      OAuth2Servlet.AuthCodeData data = OAuth2AuthorizeSupport.buildAuthCodeData(
          authorize(), "u", "r", set("neo:process"), set("*"), "*", 1000L);

      assertEquals("neo:process", data.scopes);
    }

    @Test
    @DisplayName("intersects requested and allowed scopes without the wildcard")
    void intersectsWithoutWildcard() {
      OAuth2Servlet.AuthCodeData data = OAuth2AuthorizeSupport.buildAuthCodeData(
          authorize(), "u", "r", set("neo:read", "neo:process"),
          set("neo:read", "neo:write"), "*", 1000L);

      assertEquals("neo:read", data.scopes);
    }

    // ---- validity_seconds normalization (ETP-4393) ----

    @Test
    @DisplayName("sets the explicit requested validity")
    void setsExplicitRequestedValidity() {
      OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
          authorizeWithValidity(604_800L), "user-1", "role-1",
          set("neo:read"), set("neo:read"), "neo:*", 300_000);

      assertEquals(604_800L, codeData.validitySeconds);
    }

    @Test
    @DisplayName("normalizes the absent sentinel to the default validity")
    void normalizesAbsentSentinelToDefault() {
      OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
          authorizeWithValidity(-1L), "user-1", "role-1",
          set("neo:read"), set("neo:read"), "neo:*", 300_000);

      assertEquals(86_400L, codeData.validitySeconds);
    }

    @Test
    @DisplayName("preserves zero as no-expiration")
    void preservesZeroAsNoExpiration() {
      OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
          authorizeWithValidity(0L), "user-1", "role-1",
          set("neo:read"), set("neo:read"), "neo:*", 300_000);

      assertEquals(0L, codeData.validitySeconds);
    }

    @Test
    @DisplayName("clamps an excessive validity to the maximum")
    void clampsExcessiveValidityToMax() {
      OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
          authorizeWithValidity(99_999_999L), "user-1", "role-1",
          set("neo:read"), set("neo:read"), "neo:*", 300_000);

      assertEquals(2_592_000L, codeData.validitySeconds);
    }

    @Test
    @DisplayName("clamps a below-minimum validity to the minimum")
    void clampsBelowMinValidityToMin() {
      OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
          authorizeWithValidity(60L), "user-1", "role-1",
          set("neo:read"), set("neo:read"), "neo:*", 300_000);

      assertEquals(300L, codeData.validitySeconds);
    }
  }

  @Nested
  @DisplayName("writeAuthorizeSuccess")
  class WriteAuthorizeSuccess {

    @Test
    @DisplayName("rejects a null redirect URI")
    void nullRedirect() {
      OAuth2Servlet servlet = mock(OAuth2Servlet.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      assertThrows(IllegalArgumentException.class, () -> OAuth2AuthorizeSupport.writeAuthorizeSuccess(
          response, null, "st", "CODE", servlet));
    }

    @Test
    @DisplayName("appends code and state with '?' on a URL without a query string")
    void appendsWithQuestionMark() throws Exception {
      JSONObject result = captureRedirect("https://app/cb", "st ate", "CO DE");
      assertEquals("https://app/cb?code=CO+DE&state=st+ate", result.getString("redirect_url"));
    }

    @Test
    @DisplayName("appends code with '&' when the URL already has a query string and omits blank state")
    void appendsWithAmpersandNoState() throws Exception {
      JSONObject result = captureRedirect("https://app/cb?foo=1", "", "CODE");
      assertEquals("https://app/cb?foo=1&code=CODE", result.getString("redirect_url"));
    }

    @Test
    @DisplayName("preserves a URL fragment at the end of the redirect")
    void preservesFragment() throws Exception {
      JSONObject result = captureRedirect("https://app/cb#frag", null, "CODE");
      assertEquals("https://app/cb?code=CODE#frag", result.getString("redirect_url"));
    }

    private JSONObject captureRedirect(String redirectUri, String state, String authCode)
        throws Exception {
      OAuth2Servlet servlet = mock(OAuth2Servlet.class);
      HttpServletResponse response = mock(HttpServletResponse.class);

      OAuth2AuthorizeSupport.writeAuthorizeSuccess(response, redirectUri, state, authCode, servlet);

      ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
      verify(servlet).writeJsonResponse(eq(response), eq(HttpServletResponse.SC_OK),
          captor.capture());
      return captor.getValue();
    }
  }

  private static Set<String> set(String... values) {
    return new LinkedHashSet<>(java.util.Arrays.asList(values));
  }
}
