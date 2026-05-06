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
import static org.junit.Assert.fail;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

/**
 * Unit tests for {@link JwtIssuerService}.
 *
 * <p>Uses an in-memory RSA keypair generated per test - no dependency on
 * {@code config/apps-spike/*.pem} being present, which keeps tests portable.
 */
public class JwtIssuerServiceTest {

  private static final String KID = "spike-kid-1";
  private static final String APP_ID = "spike-hello-app";
  private static final String USER_ID = "user-42";
  private static final String TENANT_ID = "acme-prod";
  private static final String ORG_ID = "acme-hq";

  private RSAPublicKey publicKey;
  private JwtIssuerService service;

  @Before
  public void setUp() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    this.publicKey = (RSAPublicKey) pair.getPublic();
    RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
    this.service = new JwtIssuerService(privateKey, publicKey, KID);
  }

  @Test
  public void signedTokenVerifiesWithPublicKey() {
    List<String> scopes = Arrays.asList("read:products", "read:users");

    String token = service.issue(USER_ID, TENANT_ID, ORG_ID, APP_ID, scopes);

    assertNotNull(token);

    DecodedJWT decoded = JWT.require(Algorithm.RSA256(publicKey, null))
        .withIssuer("etendo-go")
        .withSubject(USER_ID)
        .build()
        .verify(token);

    assertEquals(KID, decoded.getKeyId());
    assertEquals(USER_ID, decoded.getSubject());
    assertEquals(TENANT_ID, decoded.getClaim("tenant").asString());
    assertEquals(ORG_ID, decoded.getClaim("org").asString());
    assertEquals(APP_ID, decoded.getClaim("app").asString());
    assertTrue(decoded.getAudience().contains("etendo-go"));
    assertTrue(decoded.getAudience().contains(APP_ID));
    assertEquals(scopes, decoded.getClaim("scopes").asList(String.class));
    assertNotNull(decoded.getIssuedAt());
    assertNotNull(decoded.getExpiresAt());
    // TTL is 300s: expiresAt should be ~5 minutes after issuedAt, allow 2s jitter
    long ttlMs = decoded.getExpiresAt().getTime() - decoded.getIssuedAt().getTime();
    assertTrue("TTL should be around 300s but was " + ttlMs + "ms",
        ttlMs >= 298_000 && ttlMs <= 302_000);
  }

  @Test
  public void verificationWithDifferentKeyFails() throws Exception {
    String token = service.issue(USER_ID, TENANT_ID, ORG_ID, APP_ID,
        Arrays.asList("read:products"));

    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    RSAPublicKey otherPublic = (RSAPublicKey) gen.generateKeyPair().getPublic();

    try {
      JWT.require(Algorithm.RSA256(otherPublic, null)).build().verify(token);
      fail("Expected verification to fail with a different public key");
    } catch (JWTVerificationException expected) {
      // OK
    }
  }

  @Test
  public void getPublicKeyReturnsProvidedKey() {
    assertEquals(publicKey, service.getPublicKey());
  }

  @Test
  public void getKidReturnsProvidedKid() {
    assertEquals(KID, service.getKid());
  }
}
