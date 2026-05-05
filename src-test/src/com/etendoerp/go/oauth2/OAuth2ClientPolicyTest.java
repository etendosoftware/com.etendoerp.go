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
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;
import org.openbravo.base.exception.OBException;

/** Tests for OAuth2 policy helpers that do not require DAL state. */
public class OAuth2ClientPolicyTest {
  private static final Set<String> VALID_SCOPES = new HashSet<>(
      Arrays.asList("neo:read", "neo:write", "neo:process", "neo:report", "neo:*"));

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

  /** Dynamic client registration requires at least one redirect URI. */
  @Test(expected = OBException.class)
  public void normalizeRedirectUrisRejectsMissingRedirectUris() throws Exception {
    OAuth2ClientPolicy.normalizeRedirectUris(null);
  }
}
