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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;
import org.openbravo.base.exception.OBException;

/**
 * Tests for OAuth2 policy helpers that do not require DAL state.
 *
 * @covers com.etendoerp.go.oauth2.OAuth2ClientPolicy
 * @covers com.etendoerp.go.oauth2.PublicApiKeyPolicy
 */
public class OAuth2ClientPolicyTest {
  private static final Set<String> VALID_SCOPES = new HashSet<>(
      Arrays.asList("neo:read", "neo:write", "neo:process", "neo:report", "neo:*"));

  @Test
  public void publicApiKeyNamesAreNormalizedAndBounded() {
    assertEquals("Production key", PublicApiKeyPolicy.normalizeName("  Production   key "));
    try {
      PublicApiKeyPolicy.normalizeName("x".repeat(PublicApiKeyPolicy.MAX_NAME_LENGTH + 1));
      throw new AssertionError("expected an overlong name to be rejected");
    } catch (PublicApiKeyPolicy.InvalidRequestException expected) {
      assertTrue(expected.getMessage().contains("at most"));
    }
  }

  @Test
  public void publicApiKeyCapabilitiesMapToInternalScopesWithoutWildcard() {
    Set<String> capabilities = PublicApiKeyPolicy.normalizeCapabilities(
        new String[] { PublicApiKeyPolicy.CAPABILITY_READ, PublicApiKeyPolicy.CAPABILITY_WRITE });
    assertEquals("etendo:read etendo:write", PublicApiKeyPolicy.toInternalScopes(capabilities));
    assertFalse(PublicApiKeyPolicy.toInternalScopes(capabilities).contains("*"));
  }

  /** Keys issued before ETP-5602 store neo: scopes; they keep mapping to the same capabilities. */
  @Test
  public void publicApiKeyLegacyScopesMapToTheSameCapabilities() {
    assertEquals(
        new java.util.LinkedHashSet<>(Arrays.asList(
            PublicApiKeyPolicy.CAPABILITY_READ, PublicApiKeyPolicy.CAPABILITY_WRITE)),
        PublicApiKeyPolicy.capabilitiesForScopes(
            PublicApiKeyPolicy.PUBLIC_KEY_SCOPE_MARKER + " neo:read neo:write"));
  }

  /** A requested scope is allowed by its alias of the other prefix, and by either wildcard. */
  @Test
  public void isScopeAllowedAppliesTheNeoEtendoEquivalence() {
    assertTrue(OAuth2ClientPolicy.isScopeAllowed(
        Collections.singleton("etendo:read"), Collections.singleton("neo:read")));
    assertTrue(OAuth2ClientPolicy.isScopeAllowed(
        Collections.singleton("neo:read"), Collections.singleton("etendo:read")));
    assertTrue(OAuth2ClientPolicy.isScopeAllowed(
        new HashSet<>(Arrays.asList("neo:read", "etendo:write")), Collections.singleton("neo:*")));
    assertTrue(OAuth2ClientPolicy.isScopeAllowed(
        new HashSet<>(Arrays.asList("neo:report", "etendo:process")),
        Collections.singleton("etendo:*")));
  }

  /** The equivalence never widens access: an alias grants its counterpart and nothing else. */
  @Test
  public void isScopeAllowedDoesNotWidenAccessAcrossPrefixes() {
    assertFalse(OAuth2ClientPolicy.isScopeAllowed(
        Collections.singleton("etendo:write"), Collections.singleton("neo:read")));
    assertFalse(OAuth2ClientPolicy.isScopeAllowed(
        Collections.singleton("neo:*"), Collections.singleton("etendo:read")));
    assertFalse(OAuth2ClientPolicy.isScopeAllowed(
        new HashSet<>(Arrays.asList("etendo:read", "etendo:report")),
        Collections.singleton("neo:read")));
  }

  /** Both prefixes are supported request values; unknown scopes of either prefix are not. */
  @Test
  public void hasUnsupportedScopesAcceptsBothPrefixesAndRejectsUnknownOnes() {
    assertFalse(OAuth2ClientPolicy.hasUnsupportedScopes(
        "etendo:read neo:write etendo:* neo:*", ApiScopes.ACCEPTED));
    assertTrue(OAuth2ClientPolicy.hasUnsupportedScopes("etendo:admin", ApiScopes.ACCEPTED));
    assertTrue(OAuth2ClientPolicy.hasUnsupportedScopes("neo:admin", ApiScopes.ACCEPTED));
    assertTrue(OAuth2ClientPolicy.hasUnsupportedScopes(
        PublicApiKeyPolicy.PUBLIC_KEY_SCOPE_MARKER, ApiScopes.ACCEPTED));
  }

  @Test
  public void publicApiKeyStorageAddsPrivateMarkerWithoutExposingItAsCapability() {
    Set<String> capabilities = Collections.singleton(PublicApiKeyPolicy.CAPABILITY_READ);
    String storedScopes = PublicApiKeyPolicy.toStoredScopes(capabilities);
    assertTrue(storedScopes.startsWith(PublicApiKeyPolicy.PUBLIC_KEY_SCOPE_MARKER + " "));
    assertEquals(capabilities, PublicApiKeyPolicy.capabilitiesForScopes(storedScopes));
  }

  @Test
  public void publicApiKeyStorageKeepsOwnerOrganizationPrivateAndRecoverable() {
    String marker = PublicApiKeyPolicy.ownerOrganizationMarker("ORG-123");
    assertEquals("ORG-123", PublicApiKeyPolicy.ownerOrganizationId(
        "neo:public-api-key neo:read " + marker));
    assertEquals("ORG-123", PublicApiKeyPolicy.ownerOrganizationId(marker));
  }

  @Test(expected = PublicApiKeyPolicy.InvalidCapabilityException.class)
  public void publicApiKeyRejectsUnknownAndWildcardCapabilities() {
    PublicApiKeyPolicy.normalizeCapabilities(new String[] { "neo:*" });
  }

  /** Empty requested scopes mean caller is requesting the client default scope set. */
  @Test
  public void hasUnsupportedScopesAcceptsBlankScopeRequest() {
    assertFalse(OAuth2ClientPolicy.hasUnsupportedScopes(null, VALID_SCOPES));
    assertFalse(OAuth2ClientPolicy.hasUnsupportedScopes("  ", VALID_SCOPES));
  }

  /** Unknown scopes must be rejected before parseScopes can drop them. */
  @Test
  public void hasUnsupportedScopesRejectsUnknownScopeRequest() {
    assertTrue(OAuth2ClientPolicy.hasUnsupportedScopes("unknown:scope", VALID_SCOPES));
  }

  /** A mixed request is invalid if any token is outside the supported scope set. */
  @Test
  public void hasUnsupportedScopesRejectsMixedUnknownScopeRequest() {
    assertTrue(OAuth2ClientPolicy.hasUnsupportedScopes("neo:read unknown:scope", VALID_SCOPES));
  }

  /** Valid scope requests continue to pass through policy validation. */
  @Test
  public void hasUnsupportedScopesAcceptsValidScopeRequest() {
    assertFalse(OAuth2ClientPolicy.hasUnsupportedScopes("neo:read neo:write", VALID_SCOPES));
  }

  /** Dynamic registration defaults to the configured default scope when the request omits scope. */
  @Test
  public void normalizeClientScopesUsesDefaultWhenBlank() {
    assertEquals("neo:*",
        OAuth2ClientPolicy.normalizeClientScopes("  ", "neo:*", VALID_SCOPES));
  }

  /** Dynamic registration preserves an explicit valid scope request. */
  @Test
  public void normalizeClientScopesKeepsValidRequestedScopes() {
    assertEquals("neo:read neo:write",
        OAuth2ClientPolicy.normalizeClientScopes(" neo:read neo:write ", "neo:*", VALID_SCOPES));
  }

  /** Dynamic registration canonicalizes OAuth scope whitespace before storage and response. */
  @Test
  public void normalizeClientScopesCanonicalizesWhitespace() {
    assertEquals("neo:read neo:write",
        OAuth2ClientPolicy.normalizeClientScopes("neo:read\tneo:write\n", "neo:*", VALID_SCOPES));
  }

  /** Dynamic registration rejects unsupported scope values before persisting the client. */
  @Test(expected = OAuth2ClientPolicy.InvalidScopeException.class)
  public void normalizeClientScopesRejectsUnsupportedScopes() {
    OAuth2ClientPolicy.normalizeClientScopes("neo:read unknown:scope", "neo:*", VALID_SCOPES);
  }

  /** Wildcard client grants the concrete requested scopes for authorization-code tokens. */
  @Test
  public void buildAuthCodeDataGrantsRequestedScopesWhenClientHasWildcard() {
    OAuth2AuthorizeSupport.AuthorizeRequestData request =
        new OAuth2AuthorizeSupport.AuthorizeRequestData(
            "token", "client", "http://127.0.0.1/callback", "challenge", "state",
            "neo:read", -1L);
    OAuth2Servlet.AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
        request,
        "user",
        "role",
        Collections.singleton("neo:read"),
        Collections.singleton("neo:*"),
        300000);

    assertEquals("neo:read", codeData.scopes);
  }

  /** Dynamic client registration requires at least one redirect URI. */
  @Test(expected = OBException.class)
  public void normalizeRedirectUrisRejectsMissingRedirectUris() throws Exception {
    OAuth2ClientPolicy.normalizeRedirectUris(null);
  }
}
