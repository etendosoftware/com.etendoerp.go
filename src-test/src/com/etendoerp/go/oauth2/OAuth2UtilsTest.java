/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OAuth2Utils}.
 * Covers token generation, hashing, verification, PKCE, and expiration checks.
 */
class OAuth2UtilsTest {

  @Nested
  @DisplayName("generateSecureToken")
  class GenerateSecureToken {
    @Test
    void returns64CharHexString() {
      String token = OAuth2Utils.generateSecureToken();
      assertNotNull(token);
      assertEquals(64, token.length());
      assertTrue(token.matches("[0-9a-f]{64}"), "Token should be lowercase hex");
    }

    @Test
    void generatesUniqueTokensOnEachCall() {
      String t1 = OAuth2Utils.generateSecureToken();
      String t2 = OAuth2Utils.generateSecureToken();
      assertNotEquals(t1, t2);
    }
  }

  @Nested
  @DisplayName("hashSecret / verifySecret")
  class HashAndVerify {
    @Test
    void hashSecretReturnsSaltColonHashFormat() {
      String hash = OAuth2Utils.hashSecret("mySecret");
      assertNotNull(hash);
      assertTrue(hash.contains(":"), "Hash should contain salt:hash separator");
      String[] parts = hash.split(":");
      assertEquals(2, parts.length);
      assertEquals(32, parts[0].length(), "Salt should be 16 bytes = 32 hex chars");
      assertEquals(64, parts[1].length(), "SHA-256 hash should be 32 bytes = 64 hex chars");
    }

    @Test
    void verifySecretMatchesCorrectSecret() {
      String secret = "testSecret123";
      String hashed = OAuth2Utils.hashSecret(secret);
      assertTrue(OAuth2Utils.verifySecret(secret, hashed));
    }

    @Test
    void verifySecretRejectsWrongSecret() {
      String hashed = OAuth2Utils.hashSecret("correct");
      assertFalse(OAuth2Utils.verifySecret("wrong", hashed));
    }

    @Test
    void verifySecretReturnsFalseForNullPlainSecret() {
      assertFalse(OAuth2Utils.verifySecret(null, "salt:hash"));
    }

    @Test
    void verifySecretReturnsFalseForNullHashedSecret() {
      assertFalse(OAuth2Utils.verifySecret("secret", null));
    }

    @Test
    void verifySecretReturnsFalseForMalformedHash() {
      assertFalse(OAuth2Utils.verifySecret("secret", "nocolon"));
    }

    @Test
    void differentHashesForSameSecretDueToRandomSalt() {
      String secret = "sameSecret";
      String hash1 = OAuth2Utils.hashSecret(secret);
      String hash2 = OAuth2Utils.hashSecret(secret);
      assertNotEquals(hash1, hash2, "Random salt should produce different hashes");
      assertTrue(OAuth2Utils.verifySecret(secret, hash1));
      assertTrue(OAuth2Utils.verifySecret(secret, hash2));
    }
  }

  @Nested
  @DisplayName("hashToken")
  class HashToken {
    @Test
    void returns64CharHex() {
      String hash = OAuth2Utils.hashToken("someToken");
      assertNotNull(hash);
      assertEquals(64, hash.length());
      assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void sameInputProducesSameHash() {
      String h1 = OAuth2Utils.hashToken("token123");
      String h2 = OAuth2Utils.hashToken("token123");
      assertEquals(h1, h2);
    }

    @Test
    void differentInputProducesDifferentHash() {
      assertNotEquals(OAuth2Utils.hashToken("a"), OAuth2Utils.hashToken("b"));
    }
  }

  @Nested
  @DisplayName("isTokenExpired")
  class IsTokenExpired {
    @Test
    void nullDateIsExpired() {
      assertTrue(OAuth2Utils.isTokenExpired(null));
    }

    @Test
    void pastDateIsExpired() {
      Date past = new Date(System.currentTimeMillis() - 60_000);
      assertTrue(OAuth2Utils.isTokenExpired(past));
    }

    @Test
    void futureDateIsNotExpired() {
      Date future = new Date(System.currentTimeMillis() + 60_000);
      assertFalse(OAuth2Utils.isTokenExpired(future));
    }
  }

  @Nested
  @DisplayName("generateClientId")
  class GenerateClientId {
    @Test
    void startsWithEtgoPrefix() {
      String clientId = OAuth2Utils.generateClientId();
      assertTrue(clientId.startsWith("etgo-"));
    }

    @Test
    void has21CharsTotalLength() {
      // "etgo-" (5) + 16 hex chars = 21
      String clientId = OAuth2Utils.generateClientId();
      assertEquals(21, clientId.length());
    }

    @Test
    void suffixIsHex() {
      String clientId = OAuth2Utils.generateClientId();
      String suffix = clientId.substring(5);
      assertTrue(suffix.matches("[0-9a-f]{16}"));
    }
  }

  @Nested
  @DisplayName("generateAuthCode")
  class GenerateAuthCode {
    @Test
    void startsWithAcPrefix() {
      String code = OAuth2Utils.generateAuthCode();
      assertTrue(code.startsWith("ac-"));
    }

    @Test
    void has67CharsTotal() {
      // "ac-" (3) + 64 hex chars = 67
      String code = OAuth2Utils.generateAuthCode();
      assertEquals(67, code.length());
    }
  }

  @Nested
  @DisplayName("verifyCodeChallenge (PKCE S256)")
  class VerifyCodeChallenge {
    @Test
    void validVerifierMatchesChallenge() throws Exception {
      String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
      // Compute S256 challenge: BASE64URL(SHA256(ASCII(verifier)))
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

      assertTrue(OAuth2Utils.verifyCodeChallenge(verifier, challenge));
    }

    @Test
    void wrongVerifierDoesNotMatch() throws Exception {
      String verifier = "correctVerifier";
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

      assertFalse(OAuth2Utils.verifyCodeChallenge("wrongVerifier", challenge));
    }

    @Test
    void nullVerifierReturnsFalse() {
      assertFalse(OAuth2Utils.verifyCodeChallenge(null, "challenge"));
    }

    @Test
    void nullChallengeReturnsFalse() {
      assertFalse(OAuth2Utils.verifyCodeChallenge("verifier", null));
    }

    @Test
    void bothNullReturnsFalse() {
      assertFalse(OAuth2Utils.verifyCodeChallenge(null, null));
    }
  }
}
