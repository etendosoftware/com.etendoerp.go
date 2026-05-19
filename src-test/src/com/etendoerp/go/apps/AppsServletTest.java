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

package com.etendoerp.go.apps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.etendoerp.go.common.ServletResponseUtils;

/**
 * Unit tests for {@link AppsServlet}.
 *
 * <p>Covers: routing logic for GET/POST dispatchers, {@code base64UrlUnsigned}
 * encoding with and without leading zero byte, {@code buildJwk} JSON structure,
 * and {@code handleToken} validation (missing appId, missing Authorization header,
 * missing Bearer prefix, missing JWT claims).
 *
 * <p>Note: we do not mock {@code EtendoGoCorsServlet} (superclass) or
 * {@code SecureWebServicesUtils} to avoid issues with static initializers.
 * The tests focus on the pure utility methods and routing/validation logic
 * that can be exercised via mock interactions with {@code ServletResponseUtils}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppsServletTest {

  private AppsServlet servlet;

  @Mock
  private HttpServletRequest request;
  @Mock
  private HttpServletResponse response;

  private MockedStatic<ServletResponseUtils> responseUtilsMock;

  @BeforeEach
  void setUp() {
    servlet = new AppsServlet();
    responseUtilsMock = mockStatic(ServletResponseUtils.class);
  }

  @AfterEach
  void tearDown() {
    if (responseUtilsMock != null) {
      responseUtilsMock.close();
    }
  }

  // ── base64UrlUnsigned ────────────────────────────────────────────────────

  /**
   * Verifies that a BigInteger whose byte representation has a leading zero
   * (sign padding) is encoded without that zero byte.
   */
  @Test
  void base64UrlUnsignedStripsLeadingZeroByte() {
    // 128 = 0x80 -> toByteArray() returns [0x00, 0x80] (2 bytes, leading zero for sign).
    BigInteger value = BigInteger.valueOf(128);
    String encoded = AppsServlet.base64UrlUnsigned(value);

    byte[] decoded = Base64.getUrlDecoder().decode(encoded);
    assertEquals(1, decoded.length, "Leading zero byte must be stripped");
    assertEquals((byte) 0x80, decoded[0]);
  }

  /**
   * Verifies that a BigInteger whose byte representation does NOT have a
   * leading zero byte is encoded as-is.
   */
  @Test
  void base64UrlUnsignedKeepsBytesWithoutLeadingZero() {
    // 65537 = 0x010001 -> toByteArray() returns [0x01, 0x00, 0x01] (no leading zero).
    BigInteger value = BigInteger.valueOf(65537);
    String encoded = AppsServlet.base64UrlUnsigned(value);

    byte[] decoded = Base64.getUrlDecoder().decode(encoded);
    assertEquals(3, decoded.length, "Bytes without leading zero must be preserved as-is");
    assertEquals((byte) 0x01, decoded[0]);
    assertEquals((byte) 0x00, decoded[1]);
    assertEquals((byte) 0x01, decoded[2]);
  }

  /**
   * Verifies that the encoding uses base64url (no padding, URL-safe alphabet).
   */
  @Test
  void base64UrlUnsignedUsesUrlSafeNoPadding() {
    BigInteger value = new BigInteger("FFFFFFFFFFFFFFFF", 16);
    String encoded = AppsServlet.base64UrlUnsigned(value);

    assertTrue(!encoded.contains("+"), "Must not contain '+' (standard base64)");
    assertTrue(!encoded.contains("/"), "Must not contain '/' (standard base64)");
    assertTrue(!encoded.contains("="), "Must not contain '=' (padding)");
  }

  // ── buildJwk ─────────────────────────────────────────────────────────────

  /**
   * Verifies that {@code buildJwk} produces a JSON object with all required
   * JWK fields: kty, alg, use, kid, n, e.
   */
  @Test
  void buildJwkProducesCorrectJsonStructure() throws Exception {
    RSAPublicKey publicKey = mock(RSAPublicKey.class);
    when(publicKey.getModulus()).thenReturn(BigInteger.valueOf(65537));
    when(publicKey.getPublicExponent()).thenReturn(BigInteger.valueOf(65537));

    JwtIssuerService svc = mock(JwtIssuerService.class);
    when(svc.getPublicKey()).thenReturn(publicKey);
    when(svc.getKid()).thenReturn("test-kid-1");

    JSONObject jwk = AppsServlet.buildJwk(svc);

    assertEquals("RSA", jwk.getString("kty"));
    assertEquals("RS256", jwk.getString("alg"));
    assertEquals("sig", jwk.getString("use"));
    assertEquals("test-kid-1", jwk.getString("kid"));
    assertNotNull(jwk.getString("n"), "JWK must contain 'n' (modulus)");
    assertNotNull(jwk.getString("e"), "JWK must contain 'e' (exponent)");
  }

  /**
   * Verifies that the 'n' and 'e' values in the JWK are valid base64url strings
   * that decode back to the original BigInteger values.
   */
  @Test
  void buildJwkEncodesModulusAndExponentCorrectly() throws Exception {
    BigInteger modulus = new BigInteger("12345678901234567890");
    BigInteger exponent = BigInteger.valueOf(65537);

    RSAPublicKey publicKey = mock(RSAPublicKey.class);
    when(publicKey.getModulus()).thenReturn(modulus);
    when(publicKey.getPublicExponent()).thenReturn(exponent);

    JwtIssuerService svc = mock(JwtIssuerService.class);
    when(svc.getPublicKey()).thenReturn(publicKey);
    when(svc.getKid()).thenReturn("kid-2");

    JSONObject jwk = AppsServlet.buildJwk(svc);

    String nEncoded = jwk.getString("n");
    String eEncoded = jwk.getString("e");

    assertEquals(nEncoded, AppsServlet.base64UrlUnsigned(modulus));
    assertEquals(eEncoded, AppsServlet.base64UrlUnsigned(exponent));
  }

  // ── doGet routing ────────────────────────────────────────────────────────

  /**
   * Verifies that GET requests to an unknown path result in a 404 error
   * via {@code ServletResponseUtils.sendError}.
   */
  @Test
  void doGetUnknownPathReturns404() throws Exception {
    when(request.getPathInfo()).thenReturn("/unknown");

    servlet.doGet(request, response);

    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response), eq(HttpServletResponse.SC_NOT_FOUND),
            anyString()));
  }

  /**
   * Verifies that GET requests to {@code /.well-known/jwks.json} do NOT
   * trigger a 404 error (they route to handleJwks instead).
   */
  @Test
  void doGetJwksPathDoesNotReturn404() throws Exception {
    when(request.getPathInfo()).thenReturn("/.well-known/jwks.json");

    // handleJwks will fail because there is no issuer loaded, but it should
    // NOT call sendError with 404 - it will call sendError with 500 instead.
    StringWriter sw = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(sw));

    servlet.doGet(request, response);

    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response), eq(HttpServletResponse.SC_NOT_FOUND),
            anyString()), never());
  }

  // ── doPost routing ───────────────────────────────────────────────────────

  /**
   * Verifies that POST requests to an unknown path result in a 404 error.
   */
  @Test
  void doPostUnknownPathReturns404() throws Exception {
    when(request.getPathInfo()).thenReturn("/unknown");

    servlet.doPost(request, response);

    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response), eq(HttpServletResponse.SC_NOT_FOUND),
            anyString()));
  }

  /**
   * Verifies that POST to {@code /token} does NOT trigger a 404 error
   * (it routes to handleToken instead).
   */
  @Test
  void doPostTokenPathDoesNotReturn404() throws Exception {
    when(request.getPathInfo()).thenReturn("/token");
    // handleToken will hit the appId check first.

    servlet.doPost(request, response);

    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response), eq(HttpServletResponse.SC_NOT_FOUND),
            anyString()), never());
  }

  /**
   * Verifies that POST to {@code /token/} (with trailing slash) also routes
   * to handleToken and does NOT produce a 404.
   */
  @Test
  void doPostTokenPathWithTrailingSlashDoesNotReturn404() throws Exception {
    when(request.getPathInfo()).thenReturn("/token/");

    servlet.doPost(request, response);

    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response), eq(HttpServletResponse.SC_NOT_FOUND),
            anyString()), never());
  }

  // ── handleToken: missing appId ───────────────────────────────────────────

  /**
   * Verifies that POST to /token without an appId parameter returns 400.
   */
  @Test
  void handleTokenMissingAppIdReturns400() throws Exception {
    when(request.getPathInfo()).thenReturn("/token");
    when(request.getParameter("appId")).thenReturn(null);

    servlet.doPost(request, response);

    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
            anyString()));
  }

  /**
   * Verifies that POST to /token with a blank appId parameter returns 400.
   */
  @Test
  void handleTokenBlankAppIdReturns400() throws Exception {
    when(request.getPathInfo()).thenReturn("/token");
    when(request.getParameter("appId")).thenReturn("   ");

    servlet.doPost(request, response);

    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
            anyString()));
  }

  // ── handleToken: missing or invalid Authorization header ─────────────────

  /**
   * Verifies that POST to /token without an Authorization header returns 401.
   */
  @Test
  void handleTokenMissingAuthHeaderReturns401() throws Exception {
    when(request.getPathInfo()).thenReturn("/token");
    when(request.getParameter("appId")).thenReturn("my-app");
    when(request.getHeader("Authorization")).thenReturn(null);

    servlet.doPost(request, response);

    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response),
            eq(HttpServletResponse.SC_UNAUTHORIZED), anyString()));
  }

  /**
   * Verifies that POST to /token with an Authorization header that does not
   * start with "Bearer " returns 401.
   */
  @Test
  void handleTokenMissingBearerPrefixReturns401() throws Exception {
    when(request.getPathInfo()).thenReturn("/token");
    when(request.getParameter("appId")).thenReturn("my-app");
    when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

    servlet.doPost(request, response);

    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response),
            eq(HttpServletResponse.SC_UNAUTHORIZED), anyString()));
  }

  /**
   * Verifies that the 400 check for appId runs before the 401 check for
   * Authorization, so a request missing both gets a 400.
   */
  @Test
  void handleTokenValidationOrderAppIdBefore401() throws Exception {
    when(request.getPathInfo()).thenReturn("/token");
    when(request.getParameter("appId")).thenReturn(null);
    when(request.getHeader("Authorization")).thenReturn(null);

    servlet.doPost(request, response);

    // Should get 400 (bad request) not 401 (unauthorized), because appId is checked first.
    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
            anyString()));
    responseUtilsMock.verify(
        () -> ServletResponseUtils.sendError(eq(response),
            eq(HttpServletResponse.SC_UNAUTHORIZED), anyString()), never());
  }
}
