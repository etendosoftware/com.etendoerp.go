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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Servlet-level tests for {@link AppsServlet}.
 */
public class AppsServletTest {

  private static final String KID = "apps-test-kid";
  private static final String APP_ID = "spike-hello-app";
  private static final String ETENDO_TOKEN = "etendo-session-token";
  private static final String USER_ID = "user-42";
  private static final String CLIENT_ID = "acme-prod";
  private static final String ORG_ID = "acme-hq";

  private final AppsServlet servlet = new AppsServlet();

  private AtomicReference<JwtIssuerService> issuerRef;
  private JwtIssuerService previousIssuer;
  private RSAPublicKey publicKey;
  private JwtIssuerService testIssuer;

  @Before
  public void setUp() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    this.publicKey = (RSAPublicKey) pair.getPublic();
    RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
    this.testIssuer = new JwtIssuerService(privateKey, publicKey, KID);

    this.issuerRef = issuerRef();
    this.previousIssuer = issuerRef.get();
    issuerRef.set(testIssuer);
  }

  @After
  public void tearDown() {
    if (issuerRef != null) {
      issuerRef.set(previousIssuer);
    }
  }

  @Test
  public void jwksEndpointReturnsPublicKey() throws Exception {
    HttpServletRequest request = mockRequest("/.well-known/jwks.json");
    ResponseCapture response = mockResponse();

    servlet.doGet(request, response.response);

    assertJsonStatus(response, HttpServletResponse.SC_OK);
    JSONObject body = new JSONObject(response.body());
    JSONArray keys = body.getJSONArray("keys");
    assertEquals(1, keys.length());

    JSONObject jwk = keys.getJSONObject(0);
    assertEquals("RSA", jwk.getString("kty"));
    assertEquals("RS256", jwk.getString("alg"));
    assertEquals("sig", jwk.getString("use"));
    assertEquals(KID, jwk.getString("kid"));
    assertNotNull(jwk.getString("n"));
    assertTrue(!jwk.getString("n").isEmpty());
    assertNotNull(jwk.getString("e"));
    assertTrue(!jwk.getString("e").isEmpty());
  }

  @Test
  public void tokenEndpointReturnsSignedToken() throws Exception {
    HttpServletRequest request = mockRequest("/token");
    when(request.getParameter("appId")).thenReturn(APP_ID);
    when(request.getHeader("Authorization")).thenReturn("Bearer " + ETENDO_TOKEN);
    ResponseCapture response = mockResponse();
    DecodedJWT decoded = mockDecodedJwt(USER_ID, CLIENT_ID, ORG_ID);

    try (MockedStatic<SecureWebServicesUtils> secureMock = mockStatic(
        SecureWebServicesUtils.class)) {
      secureMock.when(() -> SecureWebServicesUtils.decodeToken(ETENDO_TOKEN)).thenReturn(decoded);

      servlet.doPost(request, response.response);
    }

    assertJsonStatus(response, HttpServletResponse.SC_OK);
    JSONObject body = new JSONObject(response.body());
    String token = body.getString("token");
    assertNotNull(token);
    assertEquals(JwtIssuerService.TTL_SECONDS, body.getLong("expiresInSeconds"));

    DecodedJWT issued = JWT.require(Algorithm.RSA256(publicKey, null))
        .withIssuer("etendo-go")
        .withSubject(USER_ID)
        .withAudience("etendo-go", APP_ID)
        .build()
        .verify(token);

    assertEquals(KID, issued.getKeyId());
    assertEquals(USER_ID, issued.getSubject());
    assertEquals(CLIENT_ID, issued.getClaim("tenant").asString());
    assertEquals(ORG_ID, issued.getClaim("org").asString());
    assertEquals(APP_ID, issued.getClaim("app").asString());
    assertEquals(2, issued.getClaim("scopes").asList(String.class).size());
    assertEquals("read:products", issued.getClaim("scopes").asList(String.class).get(0));
    assertEquals("read:users", issued.getClaim("scopes").asList(String.class).get(1));
  }

  @Test
  public void missingAppIdReturnsBadRequest() throws Exception {
    HttpServletRequest request = mockRequest("/token");
    when(request.getParameter("appId")).thenReturn(null);
    ResponseCapture response = mockResponse();

    servlet.doPost(request, response.response);

    assertErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
        "Missing 'appId' parameter");
  }

  @Test
  public void blankAppIdReturnsBadRequest() throws Exception {
    HttpServletRequest request = mockRequest("/token");
    when(request.getParameter("appId")).thenReturn("   ");
    ResponseCapture response = mockResponse();

    servlet.doPost(request, response.response);

    assertErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
        "Missing 'appId' parameter");
  }

  @Test
  public void missingAuthorizationHeaderReturnsUnauthorized() throws Exception {
    HttpServletRequest request = mockRequest("/token");
    when(request.getParameter("appId")).thenReturn(APP_ID);
    ResponseCapture response = mockResponse();

    servlet.doPost(request, response.response);

    assertErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
        "Missing or invalid Authorization header");
  }

  @Test
  public void malformedAuthorizationHeaderReturnsUnauthorized() throws Exception {
    HttpServletRequest request = mockRequest("/token");
    when(request.getParameter("appId")).thenReturn(APP_ID);
    when(request.getHeader("Authorization")).thenReturn("Basic abc123");
    ResponseCapture response = mockResponse();

    servlet.doPost(request, response.response);

    assertErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
        "Missing or invalid Authorization header");
  }

  @Test
  public void invalidEtendoTokenReturnsUnauthorized() throws Exception {
    HttpServletRequest request = mockRequest("/token");
    when(request.getParameter("appId")).thenReturn(APP_ID);
    when(request.getHeader("Authorization")).thenReturn("Bearer " + ETENDO_TOKEN);
    ResponseCapture response = mockResponse();

    try (MockedStatic<SecureWebServicesUtils> secureMock = mockStatic(
        SecureWebServicesUtils.class)) {
      secureMock.when(() -> SecureWebServicesUtils.decodeToken(ETENDO_TOKEN))
          .thenThrow(new RuntimeException("bad token"));

      servlet.doPost(request, response.response);
    }

    assertErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
        "Invalid Etendo session token");
  }

  @Test
  public void missingRequiredClaimsReturnsUnauthorized() throws Exception {
    HttpServletRequest request = mockRequest("/token");
    when(request.getParameter("appId")).thenReturn(APP_ID);
    when(request.getHeader("Authorization")).thenReturn("Bearer " + ETENDO_TOKEN);
    ResponseCapture response = mockResponse();
    DecodedJWT decoded = mockDecodedJwt(USER_ID, " ", ORG_ID);

    try (MockedStatic<SecureWebServicesUtils> secureMock = mockStatic(
        SecureWebServicesUtils.class)) {
      secureMock.when(() -> SecureWebServicesUtils.decodeToken(ETENDO_TOKEN)).thenReturn(decoded);

      servlet.doPost(request, response.response);
    }

    assertErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
        "Etendo token missing required claims (user, client, organization)");
  }

  @Test
  public void unknownGetPathReturnsNotFound() throws Exception {
    HttpServletRequest request = mockRequest("/unknown");
    ResponseCapture response = mockResponse();

    servlet.doGet(request, response.response);

    assertErrorResponse(response, HttpServletResponse.SC_NOT_FOUND,
        "Unknown endpoint: /unknown");
  }

  @Test
  public void unknownPostPathReturnsNotFound() throws Exception {
    HttpServletRequest request = mockRequest("/unknown");
    ResponseCapture response = mockResponse();

    servlet.doPost(request, response.response);

    assertErrorResponse(response, HttpServletResponse.SC_NOT_FOUND,
        "Unknown endpoint: /unknown");
  }

  private static AtomicReference<JwtIssuerService> issuerRef() throws Exception {
    Field field = AppsServlet.class.getDeclaredField("issuer");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<JwtIssuerService> ref = (AtomicReference<JwtIssuerService>) field.get(null);
    return ref;
  }

  private static HttpServletRequest mockRequest(String pathInfo) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(pathInfo);
    return request;
  }

  private static ResponseCapture mockResponse() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    PrintWriter writer = new PrintWriter(body);
    ResponseCapture capture = new ResponseCapture(response, body);
    doAnswer(invocation -> {
      capture.status = invocation.getArgument(0);
      return null;
    }).when(response).setStatus(anyInt());
    doAnswer(invocation -> {
      capture.contentType = invocation.getArgument(0);
      return null;
    }).when(response).setContentType(anyString());
    doAnswer(invocation -> {
      capture.characterEncoding = invocation.getArgument(0);
      return null;
    }).when(response).setCharacterEncoding(anyString());
    when(response.getWriter()).thenReturn(writer);
    return capture;
  }

  private static DecodedJWT mockDecodedJwt(String userId, String clientId, String orgId) {
    DecodedJWT decoded = mock(DecodedJWT.class);
    Claim userClaim = claim(userId);
    Claim clientClaim = claim(clientId);
    Claim organizationClaim = claim(orgId);
    when(decoded.getClaim("user")).thenReturn(userClaim);
    when(decoded.getClaim("client")).thenReturn(clientClaim);
    when(decoded.getClaim("organization")).thenReturn(organizationClaim);
    return decoded;
  }

  private static Claim claim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }

  private static void assertJsonStatus(ResponseCapture response, int expectedStatus) {
    assertEquals(expectedStatus, response.status());
    assertEquals("application/json", response.contentType());
    assertEquals("UTF-8", response.characterEncoding());
    assertNotNull(response.body());
    assertTrue(!response.body().isEmpty());
  }

  private static void assertErrorResponse(ResponseCapture response, int expectedStatus,
      String expectedError) throws Exception {
    assertJsonStatus(response, expectedStatus);
    JSONObject body = new JSONObject(response.body());
    assertEquals(expectedError, body.getString("error"));
  }

  private static final class ResponseCapture {
    private final HttpServletResponse response;
    private final StringWriter body;
    private int status;
    private String contentType;
    private String characterEncoding;

    private ResponseCapture(HttpServletResponse response, StringWriter body) {
      this.response = response;
      this.body = body;
    }

    private int status() {
      return status;
    }

    private String contentType() {
      return contentType;
    }

    private String characterEncoding() {
      return characterEncoding;
    }

    private String body() {
      return body.toString();
    }
  }
}
