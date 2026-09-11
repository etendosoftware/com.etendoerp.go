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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Map;

import javax.servlet.FilterChain;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.common.PublicUrlResolver;

/**
 * Unit tests for {@link OAuth2Filter}.
 *
 * <p>Covers: OPTIONS/GET pass-through, missing/empty bearer tokens,
 * token-not-found (JWT fallback), revoked/expired/inactive-client tokens,
 * valid token attribute propagation, {@code validateToken()} static method,
 * {@code TokenInfo} constructors, and {@code escapeJson()} edge cases.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuth2FilterTest {

  private OAuth2Filter filter;

  @Mock
  private HttpServletRequest httpRequest;
  @Mock
  private HttpServletResponse httpResponse;
  @Mock
  private FilterChain filterChain;
  @Mock
  private OBDal obDal;
  @Mock
  private Session session;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OAuth2Utils> oAuth2UtilsMock;
  private MockedStatic<PublicUrlResolver> publicUrlMock;

  private StringWriter responseBody;
  private PrintWriter printWriter;

  @Test
  @DisplayName("protects both canonical and WebMCP-friendly MCP mappings")
  void mcpFilterCoversBothEndpoints() {
    WebFilter annotation = OAuth2Filter.class.getAnnotation(WebFilter.class);

    assertNotNull(annotation);
    assertTrue(java.util.Arrays.asList(annotation.urlPatterns()).contains("/sws/mcp"));
    assertTrue(java.util.Arrays.asList(annotation.urlPatterns()).contains("/mcp"));
  }

  @BeforeEach
  void setUp() throws Exception {
    filter = new OAuth2Filter();

    obDalMock = mockStatic(OBDal.class);
    oAuth2UtilsMock = mockStatic(OAuth2Utils.class);
    publicUrlMock = mockStatic(PublicUrlResolver.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.getSession()).thenReturn(session);

    publicUrlMock.when(() -> PublicUrlResolver.resolveMcpResourceUrl(any(HttpServletRequest.class)))
        .thenReturn("https://example.com/sws/mcp");
    publicUrlMock.when(() -> PublicUrlResolver.appendPath(anyString(), anyString()))
        .thenReturn("https://example.com/sws/mcp/.well-known/oauth-protected-resource");

    responseBody = new StringWriter();
    printWriter = new PrintWriter(responseBody);
    when(httpResponse.getWriter()).thenReturn(printWriter);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    oAuth2UtilsMock.close();
    publicUrlMock.close();
  }

  // ── doFilter: pass-through for OPTIONS and GET ────────────────────────────

  /**
   * Verifies that OPTIONS requests (CORS preflight) pass through without authentication.
   */
  @Test
  @DisplayName("doFilter: OPTIONS request passes through without auth")
  void doFilterOptionsPassesThrough() throws Exception {
    when(httpRequest.getMethod()).thenReturn("OPTIONS");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(filterChain).doFilter(httpRequest, httpResponse);
    verify(httpResponse, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  /**
   * Verifies that GET requests (discovery) pass through without authentication.
   */
  @Test
  @DisplayName("doFilter: GET request passes through without auth")
  void doFilterGetPassesThrough() throws Exception {
    when(httpRequest.getMethod()).thenReturn("GET");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(filterChain).doFilter(httpRequest, httpResponse);
    verify(httpResponse, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  /**
   * Verifies that case-insensitive method matching works for OPTIONS.
   */
  @Test
  @DisplayName("doFilter: case-insensitive OPTIONS passes through")
  void doFilterOptionsCaseInsensitive() throws Exception {
    when(httpRequest.getMethod()).thenReturn("options");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(filterChain).doFilter(httpRequest, httpResponse);
  }

  // ── doFilter: missing / malformed Authorization header ────────────────────

  /**
   * Verifies that a POST with no Authorization header returns 401.
   */
  @Test
  @DisplayName("doFilter: missing Authorization header returns 401")
  void doFilterMissingAuthHeader() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn(null);

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(httpResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(filterChain, never()).doFilter(any(), any());
    printWriter.flush();
    assertTrue(responseBody.toString().contains("invalid_request"));
    assertTrue(responseBody.toString().contains("Missing or malformed"));
  }

  /**
   * Verifies that a non-Bearer Authorization header returns 401.
   */
  @Test
  @DisplayName("doFilter: non-Bearer auth header returns 401")
  void doFilterNonBearerAuthHeader() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(httpResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(filterChain, never()).doFilter(any(), any());
  }

  /**
   * Verifies that an empty Bearer token returns 401.
   */
  @Test
  @DisplayName("doFilter: empty bearer token returns 401")
  void doFilterEmptyBearerToken() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn("Bearer ");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(httpResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(filterChain, never()).doFilter(any(), any());
    printWriter.flush();
    assertTrue(responseBody.toString().contains("Bearer token is empty"));
  }

  /**
   * Verifies that a Bearer token consisting only of spaces returns 401.
   */
  @Test
  @DisplayName("doFilter: whitespace-only bearer token returns 401")
  void doFilterWhitespaceOnlyBearerToken() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn("Bearer    ");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(httpResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(filterChain, never()).doFilter(any(), any());
  }

  // ── doFilter: token not found (JWT fallback) ──────────────────────────────

  /**
   * Verifies that when the token is not found in the OAuth2 store, the request
   * passes through to the filter chain for JWT fallback.
   */
  @Test
  @DisplayName("doFilter: token not found passes to chain for JWT fallback")
  void doFilterTokenNotFoundPassesToChain() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn("Bearer some-unknown-token");
    oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("some-unknown-token"))
        .thenReturn("hashed-unknown");

    mockLookupReturningNull();

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(filterChain).doFilter(httpRequest, httpResponse);
    verify(httpResponse, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  // ── doFilter: revoked token ───────────────────────────────────────────────

  /**
   * Verifies that a revoked token returns 401 with "invalid_token" error.
   */
  @Test
  @DisplayName("doFilter: revoked token returns 401")
  void doFilterRevokedToken() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn("Bearer revoked-token");
    oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("revoked-token"))
        .thenReturn("hashed-revoked");

    mockLookupReturningError("Token has been revoked");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(httpResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(filterChain, never()).doFilter(any(), any());
    printWriter.flush();
    assertTrue(responseBody.toString().contains("Token has been revoked"));
  }

  // ── doFilter: expired token ───────────────────────────────────────────────

  /**
   * Verifies that an expired token returns 401 with appropriate error description.
   */
  @Test
  @DisplayName("doFilter: expired token returns 401")
  void doFilterExpiredToken() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn("Bearer expired-token");
    oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("expired-token"))
        .thenReturn("hashed-expired");

    mockLookupReturningError("Token expired");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(httpResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(filterChain, never()).doFilter(any(), any());
    printWriter.flush();
    assertTrue(responseBody.toString().contains("Token expired"));
  }

  // ── doFilter: inactive client ─────────────────────────────────────────────

  /**
   * Verifies that a token from an inactive client returns 401.
   */
  @Test
  @DisplayName("doFilter: inactive client returns 401")
  void doFilterInactiveClient() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn("Bearer inactive-client-token");
    oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("inactive-client-token"))
        .thenReturn("hashed-inactive");

    mockLookupReturningError("OAuth2 client is inactive");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(httpResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    printWriter.flush();
    assertTrue(responseBody.toString().contains("OAuth2 client is inactive"));
  }

  // ── doFilter: valid token sets attributes ─────────────────────────────────

  /**
   * Verifies that a valid token sets all expected request attributes and
   * the request passes through to the filter chain.
   */
  @Test
  @DisplayName("doFilter: valid token sets request attributes and passes to chain")
  void doFilterValidTokenSetsAttributes() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn("Bearer valid-token-123");
    oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("valid-token-123"))
        .thenReturn("hashed-valid");

    mockLookupReturningValid("user-1", "role-1", "client-1", "read write");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(httpRequest).setAttribute(OAuth2Filter.ATTR_USER_ID, "user-1");
    verify(httpRequest).setAttribute(OAuth2Filter.ATTR_ROLE_ID, "role-1");
    verify(httpRequest).setAttribute(OAuth2Filter.ATTR_CLIENT_ID, "client-1");
    verify(httpRequest).setAttribute(OAuth2Filter.ATTR_ORG_ID, "0");
    verify(httpRequest).setAttribute(OAuth2Filter.ATTR_SCOPES, "read write");
    verify(filterChain).doFilter(httpRequest, httpResponse);
    verify(httpResponse, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  // ── doFilter: no-expiration token (ETP-4393 validity_seconds=0) ───────────

  /**
   * ETP-4393 — a token issued with {@code validity_seconds=0} (no expiration) stores a
   * null {@code expires_at}. Verifies the filter treats a null expiry as "never expired"
   * and passes the request through, distinct from {@link #doFilterExpiredToken()} which
   * covers the opposite (a past, non-null expiry).
   */
  @Test
  @DisplayName("doFilter: null expires_at (no expiration) is treated as valid")
  void doFilterNullExpiresAtTokenIsValid() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn("Bearer no-expiration-token");
    oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("no-expiration-token"))
        .thenReturn("hashed-no-expiration");

    mockLookupReturningValid("user-1", "role-1", "client-1", "neo:read");

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(filterChain).doFilter(httpRequest, httpResponse);
    verify(httpResponse, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  // ── doFilter: exception during validation returns 500 ─────────────────────

  /**
   * Verifies that an unexpected exception during token lookup returns 500.
   */
  @Test
  @DisplayName("doFilter: exception during validation returns 500")
  void doFilterExceptionReturns500() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn("Bearer crash-token");
    oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("crash-token"))
        .thenReturn("hashed-crash");

    when(session.doReturningWork(any())).thenThrow(new RuntimeException("DB down"));

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(httpResponse).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    verify(filterChain, never()).doFilter(any(), any());
    printWriter.flush();
    assertTrue(responseBody.toString().contains("server_error"));
  }

  // ── doFilter: WWW-Authenticate header on 401 ─────────────────────────────

  /**
   * Verifies that 401 responses include a WWW-Authenticate header with
   * resource_metadata URL.
   */
  @Test
  @DisplayName("doFilter: 401 response includes WWW-Authenticate header")
  void doFilterUnauthorizedIncludesWwwAuthenticateHeader() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn(null);

    filter.doFilter(httpRequest, httpResponse, filterChain);

    ArgumentCaptor<String> headerCaptor = ArgumentCaptor.forClass(String.class);
    verify(httpResponse).setHeader(eq("WWW-Authenticate"), headerCaptor.capture());

    String wwwAuth = headerCaptor.getValue();
    assertTrue(wwwAuth.contains("Bearer"));
    assertTrue(wwwAuth.contains("resource_metadata="));
    assertTrue(wwwAuth.contains("invalid_request"));
  }

  /**
   * Verifies that the response content type is set to JSON on error.
   */
  @Test
  @DisplayName("doFilter: error response sets JSON content type")
  void doFilterErrorSetsJsonContentType() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getHeader("Authorization")).thenReturn(null);

    filter.doFilter(httpRequest, httpResponse, filterChain);

    verify(httpResponse).setContentType("application/json;charset=UTF-8");
  }

  // ── validateToken: static method ──────────────────────────────────────────

  @Nested
  @DisplayName("validateToken")
  class ValidateTokenTests {

    /**
     * Verifies that a null bearer token returns null.
     */
    @Test
    @DisplayName("null token returns null")
    void validateTokenNullReturnsNull() {
      assertNull(OAuth2Filter.validateToken(null));
    }

    /**
     * Verifies that an empty bearer token returns null.
     */
    @Test
    @DisplayName("empty token returns null")
    void validateTokenEmptyReturnsNull() {
      assertNull(OAuth2Filter.validateToken(""));
    }

    /**
     * Verifies that a valid token returns a map with all identity attributes.
     */
    @Test
    @DisplayName("valid token returns identity map")
    void validateTokenValidReturnsIdentityMap() {
      oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("good-token"))
          .thenReturn("hashed-good");

      mockLookupReturningValid("u1", "r1", "c1", "mcp:tools");

      Map<String, String> identity = OAuth2Filter.validateToken("good-token");

      assertNotNull(identity);
      assertEquals("u1", identity.get(OAuth2Filter.ATTR_USER_ID));
      assertEquals("r1", identity.get(OAuth2Filter.ATTR_ROLE_ID));
      assertEquals("c1", identity.get(OAuth2Filter.ATTR_CLIENT_ID));
      assertEquals("0", identity.get(OAuth2Filter.ATTR_ORG_ID));
      assertEquals("mcp:tools", identity.get(OAuth2Filter.ATTR_SCOPES));
    }

    /**
     * Verifies that a token with an error (e.g. revoked) returns null.
     */
    @Test
    @DisplayName("error token returns null")
    void validateTokenErrorReturnsNull() {
      oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("bad-token"))
          .thenReturn("hashed-bad");

      mockLookupReturningError("Token has been revoked");

      assertNull(OAuth2Filter.validateToken("bad-token"));
    }

    /**
     * Verifies that a token not found in the store returns null.
     */
    @Test
    @DisplayName("token not found returns null")
    void validateTokenNotFoundReturnsNull() {
      oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("missing-token"))
          .thenReturn("hashed-missing");

      mockLookupReturningNull();

      assertNull(OAuth2Filter.validateToken("missing-token"));
    }

    /**
     * Verifies that an exception during lookup returns null.
     */
    @Test
    @DisplayName("exception during lookup returns null")
    void validateTokenExceptionReturnsNull() {
      oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("crash-token"))
          .thenReturn("hashed-crash");

      when(session.doReturningWork(any())).thenThrow(new RuntimeException("DB down"));

      assertNull(OAuth2Filter.validateToken("crash-token"));
    }
  }

  // ── TokenInfo: inner class ────────────────────────────────────────────────

  @Nested
  @DisplayName("TokenInfo")
  class TokenInfoTests {

    /**
     * Verifies that the success constructor sets all fields correctly
     * and leaves error fields null.
     */
    @Test
    @DisplayName("success constructor sets fields correctly")
    void tokenInfoSuccessConstructor() {
      OAuth2Filter.TokenInfo info = new OAuth2Filter.TokenInfo("u1", "r1", "c1", "read");

      assertEquals("u1", info.userId);
      assertEquals("r1", info.roleId);
      assertEquals("c1", info.clientId);
      assertEquals("read", info.scopes);
      assertNull(info.errorCode);
      assertNull(info.errorDesc);
    }

    /**
     * Verifies that the error factory method sets error fields
     * and leaves identity fields null.
     */
    @Test
    @DisplayName("error factory sets error fields correctly")
    void tokenInfoErrorFactory() {
      OAuth2Filter.TokenInfo info = OAuth2Filter.TokenInfo.error("Token expired");

      assertNull(info.userId);
      assertNull(info.roleId);
      assertNull(info.clientId);
      assertNull(info.scopes);
      assertEquals("invalid_token", info.errorCode);
      assertEquals("Token expired", info.errorDesc);
    }
  }

  // ── escapeJson: private method tested via sendError output ────────────────

  @Nested
  @DisplayName("escapeJson")
  class EscapeJsonTests {

    /**
     * Invokes the private escapeJson method via reflection.
     */
    private String invokeEscapeJson(String value) throws Exception {
      Method method = OAuth2Filter.class.getDeclaredMethod("escapeJson", String.class);
      method.setAccessible(true);
      return (String) method.invoke(filter, value);
    }

    /**
     * Verifies that null input returns an empty string.
     */
    @Test
    @DisplayName("null returns empty string")
    void escapeJsonNull() throws Exception {
      assertEquals("", invokeEscapeJson(null));
    }

    /**
     * Verifies that backslashes are escaped.
     */
    @Test
    @DisplayName("backslash is escaped")
    void escapeJsonBackslash() throws Exception {
      assertEquals("path\\\\to\\\\file", invokeEscapeJson("path\\to\\file"));
    }

    /**
     * Verifies that double quotes are escaped.
     */
    @Test
    @DisplayName("double quotes are escaped")
    void escapeJsonQuotes() throws Exception {
      assertEquals("say \\\"hello\\\"", invokeEscapeJson("say \"hello\""));
    }

    /**
     * Verifies that newlines are escaped.
     */
    @Test
    @DisplayName("newlines are escaped")
    void escapeJsonNewlines() throws Exception {
      assertEquals("line1\\nline2", invokeEscapeJson("line1\nline2"));
    }

    /**
     * Verifies that carriage returns are escaped.
     */
    @Test
    @DisplayName("carriage returns are escaped")
    void escapeJsonCarriageReturns() throws Exception {
      assertEquals("line1\\rline2", invokeEscapeJson("line1\rline2"));
    }

    /**
     * Verifies that tabs are escaped.
     */
    @Test
    @DisplayName("tabs are escaped")
    void escapeJsonTabs() throws Exception {
      assertEquals("col1\\tcol2", invokeEscapeJson("col1\tcol2"));
    }

    /**
     * Verifies that a string with multiple special characters is fully escaped.
     */
    @Test
    @DisplayName("multiple special characters are all escaped")
    void escapeJsonMultipleSpecialChars() throws Exception {
      assertEquals("a\\\\b\\\"c\\nd\\re\\tf", invokeEscapeJson("a\\b\"c\nd\re\tf"));
    }

    /**
     * Verifies that a plain string passes through unchanged.
     */
    @Test
    @DisplayName("plain string passes through unchanged")
    void escapeJsonPlainString() throws Exception {
      assertEquals("hello world", invokeEscapeJson("hello world"));
    }

    /**
     * Verifies that the error JSON output is well-formed end-to-end.
     */
    @Test
    @DisplayName("error JSON output is well-formed end-to-end")
    void escapeJsonErrorResponseWellFormed() throws Exception {
      when(httpRequest.getMethod()).thenReturn("POST");
      when(httpRequest.getHeader("Authorization")).thenReturn("Bearer test-token");
      oAuth2UtilsMock.when(() -> OAuth2Utils.hashToken("test-token"))
          .thenReturn("hashed-test");

      mockLookupReturningError("Token has been revoked");

      filter.doFilter(httpRequest, httpResponse, filterChain);

      printWriter.flush();
      String body = responseBody.toString();
      assertTrue(body.contains("\"error\":\"invalid_token\""));
      assertTrue(body.contains("\"error_description\":\"Token has been revoked\""));
    }
  }

  // ── Attribute constant values ─────────────────────────────────────────────

  /**
   * Verifies that the attribute key constants have the expected values.
   */
  @Test
  @DisplayName("attribute key constants have expected values")
  void attributeConstants() {
    assertEquals("oauth2.userId", OAuth2Filter.ATTR_USER_ID);
    assertEquals("oauth2.roleId", OAuth2Filter.ATTR_ROLE_ID);
    assertEquals("oauth2.clientId", OAuth2Filter.ATTR_CLIENT_ID);
    assertEquals("oauth2.orgId", OAuth2Filter.ATTR_ORG_ID);
    assertEquals("oauth2.scopes", OAuth2Filter.ATTR_SCOPES);
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  /**
   * Mocks the Hibernate session's doReturningWork to return null (token not found).
   */
  @SuppressWarnings("unchecked")
  private void mockLookupReturningNull() {
    when(session.doReturningWork(any())).thenAnswer(invocation -> {
      org.hibernate.jdbc.ReturningWork<OAuth2Filter.TokenInfo> work = invocation.getArgument(0);
      Connection connection = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);

      when(connection.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      return work.execute(connection);
    });
  }

  /**
   * Mocks the Hibernate session's doReturningWork to return a TokenInfo with an error.
   */
  @SuppressWarnings("unchecked")
  private void mockLookupReturningError(String errorDesc) {
    when(session.doReturningWork(any())).thenAnswer(invocation -> {
      org.hibernate.jdbc.ReturningWork<OAuth2Filter.TokenInfo> work = invocation.getArgument(0);
      Connection connection = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);

      when(connection.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);

      // Determine which error scenario based on description
      if (errorDesc.contains("revoked")) {
        when(rs.getString("is_revoked")).thenReturn("Y");
        when(rs.getString("etgo_oauth2_token_id")).thenReturn("token-id-123");
      } else if (errorDesc.contains("expired")) {
        when(rs.getString("is_revoked")).thenReturn("N");
        Timestamp pastTimestamp = new Timestamp(System.currentTimeMillis() - 3600000);
        when(rs.getTimestamp("expires_at")).thenReturn(pastTimestamp);
        oAuth2UtilsMock.when(() -> OAuth2Utils.isTokenExpired(any(Timestamp.class)))
            .thenReturn(true);
      } else if (errorDesc.contains("inactive")) {
        when(rs.getString("is_revoked")).thenReturn("N");
        when(rs.getTimestamp("expires_at")).thenReturn(null);
        oAuth2UtilsMock.when(() -> OAuth2Utils.isTokenExpired(any()))
            .thenReturn(false);
        when(rs.getString("client_active")).thenReturn("N");
      }

      return work.execute(connection);
    });
  }

  /**
   * Mocks the Hibernate session's doReturningWork to return a valid TokenInfo.
   */
  @SuppressWarnings("unchecked")
  private void mockLookupReturningValid(String userId, String roleId, String clientId,
      String scopes) {
    when(session.doReturningWork(any())).thenAnswer(invocation -> {
      org.hibernate.jdbc.ReturningWork<OAuth2Filter.TokenInfo> work = invocation.getArgument(0);
      Connection connection = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);

      when(connection.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getString("is_revoked")).thenReturn("N");
      when(rs.getTimestamp("expires_at")).thenReturn(null);
      oAuth2UtilsMock.when(() -> OAuth2Utils.isTokenExpired(any()))
          .thenReturn(false);
      when(rs.getString("client_active")).thenReturn("Y");
      when(rs.getString("token_scopes")).thenReturn(scopes);
      when(rs.getString("client_scopes")).thenReturn(null);
      when(rs.getString("ad_user_id")).thenReturn(userId);
      when(rs.getString("ad_role_id")).thenReturn(roleId);
      when(rs.getString("etendo_client_id")).thenReturn(clientId);

      return work.execute(connection);
    });
  }
}
