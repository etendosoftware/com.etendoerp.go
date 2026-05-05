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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.apps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

/**
 * Issues RS256 JWTs for the F1 Etendo Go Apps spike (ETP-3805).
 *
 * <p>Tokens are signed with an RSA private key and can be verified by any external
 * app using the public key published through the JWKS endpoint.
 *
 * <p>Token structure:
 * <ul>
 *   <li>Header: {@code alg=RS256}, {@code kid=<configured kid>}</li>
 *   <li>Issuer: {@code etendo-go}</li>
 *   <li>Subject: Etendo user id</li>
 *   <li>Audience: {@code [etendo-go, <appId>]} — both the backend and the target app
 *       must accept the token</li>
 *   <li>Claims: {@code tenant}, {@code org}, {@code app}, {@code scopes}</li>
 *   <li>TTL: 5 minutes (short-lived, per design)</li>
 * </ul>
 *
 * <p>Production: load the keypair from a secrets manager. The spike reads a PEM keypair
 * from disk via {@link #fromPemFiles(Path, Path, String)}.
 */
public class JwtIssuerService {

  static final String ISSUER = "etendo-go";
  static final long TTL_SECONDS = 300;

  private final RSAPrivateKey privateKey;
  private final RSAPublicKey publicKey;
  private final String kid;
  private final Algorithm algorithm;

  public JwtIssuerService(RSAPrivateKey privateKey, RSAPublicKey publicKey, String kid) {
    this.privateKey = privateKey;
    this.publicKey = publicKey;
    this.kid = kid;
    this.algorithm = Algorithm.RSA256(publicKey, privateKey);
  }

  /**
   * Builds a service from a PEM-encoded keypair on disk.
   *
   * @param privateKeyPath PKCS#8 encoded PEM file
   * @param publicKeyPath  X.509 encoded PEM file
   * @param kid            key identifier, surfaced in the JWT header and JWKS
   */
  public static JwtIssuerService fromPemFiles(Path privateKeyPath, Path publicKeyPath, String kid)
      throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
    RSAPrivateKey priv = loadPrivateKey(privateKeyPath);
    RSAPublicKey pub = loadPublicKey(publicKeyPath);
    return new JwtIssuerService(priv, pub, kid);
  }

  /**
   * Mints a JWT for a given user/tenant/app triple.
   *
   * @param scopes may be empty; {@code null} is treated as empty
   */
  public String issue(String userId, String tenantId, String orgId, String appId,
      List<String> scopes) {
    Instant now = Instant.now();
    String[] scopeArray = scopes == null ? new String[0] : scopes.toArray(new String[0]);
    return JWT.create()
        .withKeyId(kid)
        .withIssuer(ISSUER)
        .withSubject(userId)
        .withAudience(ISSUER, appId)
        .withClaim("tenant", tenantId)
        .withClaim("org", orgId)
        .withClaim("app", appId)
        .withArrayClaim("scopes", scopeArray)
        .withIssuedAt(Date.from(now))
        .withExpiresAt(Date.from(now.plusSeconds(TTL_SECONDS)))
        .sign(algorithm);
  }

  public RSAPublicKey getPublicKey() {
    return publicKey;
  }

  public String getKid() {
    return kid;
  }

  private static RSAPrivateKey loadPrivateKey(Path path)
      throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
    byte[] der = decodePem(Files.readString(path, StandardCharsets.UTF_8), "PRIVATE KEY");
    return (RSAPrivateKey) KeyFactory.getInstance("RSA")
        .generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  private static RSAPublicKey loadPublicKey(Path path)
      throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
    byte[] der = decodePem(Files.readString(path, StandardCharsets.UTF_8), "PUBLIC KEY");
    return (RSAPublicKey) KeyFactory.getInstance("RSA")
        .generatePublic(new X509EncodedKeySpec(der));
  }

  private static byte[] decodePem(String pem, String type) {
    String begin = "-----BEGIN " + type + "-----";
    String end = "-----END " + type + "-----";
    int beginIdx = pem.indexOf(begin);
    int endIdx = pem.indexOf(end);
    if (beginIdx < 0 || endIdx < 0) {
      throw new IllegalArgumentException("PEM does not contain " + type + " block");
    }
    String body = pem.substring(beginIdx + begin.length(), endIdx).replaceAll("\\s", "");
    return Base64.getDecoder().decode(body);
  }
}
