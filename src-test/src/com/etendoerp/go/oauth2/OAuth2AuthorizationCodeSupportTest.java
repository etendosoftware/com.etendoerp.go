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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OAuth2AuthorizationCodeSupport#validateAuthorizationCode}.
 *
 * <p>Every branch is covered without a live database or servlet container.
 * {@link OAuth2Servlet.AuthCodeData} is constructed directly because it is a
 * package-private inner class; the support class under test also lives in the
 * same package.</p>
 */
class OAuth2AuthorizationCodeSupportTest {

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Builds a valid S256 code challenge from the given verifier.
   */
  private static String buildChallenge(String verifier) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  /**
   * Creates an {@link OAuth2Servlet.AuthCodeData} instance that passes all
   * checks: not null, not used, not expired, matching PKCE challenge, and
   * matching redirect URI.
   */
  private static OAuth2Servlet.AuthCodeData validCodeData(String codeVerifier, String redirectUri)
      throws Exception {
    OAuth2Servlet.AuthCodeData data = new OAuth2Servlet.AuthCodeData();
    data.used = false;
    data.expiresAt = System.currentTimeMillis() + 300_000; // 5 minutes from now
    data.codeChallenge = buildChallenge(codeVerifier);
    data.redirectUri = redirectUri;
    data.clientId = "client-1";
    data.userId = "user-1";
    data.roleId = "role-1";
    data.scopes = "neo:read";
    return data;
  }

  // ── direct test ─────────────────────────────────────────────────────────────
  // A top-level @Test (in addition to the @Nested groups below) so the suite class
  // itself carries an executable test, exercising validateAuthorizationCode end to end.

  @Test
  @DisplayName("expired authorization code is rejected with the 'expired' message")
  void expiredCodeReturnsExpiredError() throws Exception {
    String verifier = "outer-level-verifier-abcdefghij";
    String redirectUri = "https://myapp.example.com/oauth/callback";
    OAuth2Servlet.AuthCodeData data = validCodeData(verifier, redirectUri);
    data.expiresAt = System.currentTimeMillis() - 1_000; // already expired

    String error = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(
        data, verifier, redirectUri);

    assertNotNull(error, "Expected a non-null error for an expired code");
    assertEquals("Authorization code expired", error);
  }

  // ── null codeData ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("null codeData")
  class NullCodeData {
    @Test
    void returnsNotFoundOrExpiredMessage() {
      String error = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(
          null, "verifier", "https://app.example.com/callback");
      assertNotNull(error, "Expected an error message, got null (success)");
      assertEquals("Authorization code not found or expired", error);
    }
  }

  // ── used code ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("used code")
  class UsedCode {
    @Test
    void returnsAlreadyUsedMessage() {
      OAuth2Servlet.AuthCodeData data = new OAuth2Servlet.AuthCodeData();
      data.used = true;
      data.expiresAt = System.currentTimeMillis() + 300_000;
      data.codeChallenge = "any-challenge";
      data.redirectUri = "https://app.example.com/callback";

      String error = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(
          data, "verifier", "https://app.example.com/callback");
      assertNotNull(error);
      assertEquals("Authorization code already used", error);
    }
  }

  // ── expired code ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("expired code")
  class ExpiredCode {
    @Test
    void returnsExpiredMessage() {
      OAuth2Servlet.AuthCodeData data = new OAuth2Servlet.AuthCodeData();
      data.used = false;
      data.expiresAt = System.currentTimeMillis() - 1_000; // 1 second ago
      data.codeChallenge = "any-challenge";
      data.redirectUri = "https://app.example.com/callback";

      String error = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(
          data, "verifier", "https://app.example.com/callback");
      assertNotNull(error);
      assertEquals("Authorization code expired", error);
    }
  }

  // ── PKCE failure ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("PKCE verification failure")
  class PkceFail {
    @Test
    void returnsPkceFailedMessage() throws Exception {
      String correctVerifier = "correct-verifier-abc";
      OAuth2Servlet.AuthCodeData data = validCodeData(correctVerifier,
          "https://app.example.com/callback");

      // Use a WRONG verifier — the challenge was built from "correct-verifier-abc"
      String error = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(
          data, "wrong-verifier-xyz", "https://app.example.com/callback");
      assertNotNull(error);
      assertEquals("PKCE verification failed", error);
    }

    @Test
    void nullVerifierReturnsPkceFailedMessage() throws Exception {
      String correctVerifier = "verifier-123";
      OAuth2Servlet.AuthCodeData data = validCodeData(correctVerifier,
          "https://app.example.com/cb");

      String error = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(
          data, null, "https://app.example.com/cb");
      assertNotNull(error);
      assertEquals("PKCE verification failed", error);
    }
  }

  // ── redirect URI mismatch ─────────────────────────────────────────────────

  @Nested
  @DisplayName("redirect_uri mismatch")
  class RedirectUriMismatch {
    @Test
    void returnsMismatchMessage() throws Exception {
      String verifier = "redirect-verifier-abc";
      OAuth2Servlet.AuthCodeData data = validCodeData(verifier,
          "https://app.example.com/callback");

      String error = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(
          data, verifier, "https://attacker.example.com/steal");
      assertNotNull(error);
      assertEquals("redirect_uri mismatch", error);
    }

    @Test
    void nullRedirectUriMismatchesNonNullDataUri() throws Exception {
      String verifier = "verifier-redirect";
      OAuth2Servlet.AuthCodeData data = validCodeData(verifier,
          "https://app.example.com/callback");

      String error = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(
          data, verifier, null);
      assertNotNull(error);
      assertEquals("redirect_uri mismatch", error);
    }

    @Test
    void matchingNullBothSidesIsRedirectMatch() throws Exception {
      String verifier = "verifier-null-redirect";
      OAuth2Servlet.AuthCodeData data = validCodeData(verifier,
          "https://app.example.com/callback");
      // Override redirect to null in data
      data.redirectUri = null;

      String error = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(
          data, verifier, null);
      // null == null via Objects.equals, so no redirect mismatch → must succeed
      assertNull(error, "Both redirectUris are null, should return null (success)");
    }
  }

  // ── success path ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("success")
  class Success {
    @Test
    void validCodeDataReturnsNull() throws Exception {
      String verifier = "success-verifier-abcdefghij";
      String redirectUri = "https://myapp.example.com/oauth/callback";
      OAuth2Servlet.AuthCodeData data = validCodeData(verifier, redirectUri);

      String error = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(
          data, verifier, redirectUri);
      assertNull(error, "Expected null (no error) for a valid code, but got: " + error);
    }
  }
}
