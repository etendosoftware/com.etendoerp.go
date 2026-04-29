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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;

final class OAuth2ClientPolicy {

  private OAuth2ClientPolicy() {
  }

  static Set<String> parseScopes(String scopeStr, Set<String> validScopes) {
    if (scopeStr == null || scopeStr.trim().isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> scopes = new HashSet<>(Arrays.asList(scopeStr.trim().split("\\s+")));
    scopes.retainAll(validScopes);
    return scopes;
  }

  static boolean isScopeAllowed(Set<String> requested, Set<String> allowed, String wildcardScope) {
    return allowed.contains(wildcardScope) || allowed.containsAll(requested);
  }

  static String normalizeRedirectUris(JSONArray redirectUris) throws JSONException {
    if (redirectUris == null || redirectUris.length() == 0) {
      throw new IllegalArgumentException("redirect_uris is required");
    }
    for (int i = 0; i < redirectUris.length(); i++) {
      String redirectUri = redirectUris.getString(i);
      if (!isSafeRedirectUri(redirectUri)) {
        throw new IllegalArgumentException(
            "redirect_uris must use https, http://localhost, or http://127.0.0.1");
      }
    }
    return redirectUris.toString();
  }

  static boolean isSafeRedirectUri(String redirectUri) {
    if (redirectUri == null || redirectUri.isEmpty()) {
      return false;
    }
    try {
      java.net.URI uri = new java.net.URI(redirectUri);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if ("https".equalsIgnoreCase(scheme)) {
        return host != null && !host.isEmpty();
      }
      return "http".equalsIgnoreCase(scheme)
          && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
    } catch (java.net.URISyntaxException e) {
      return false;
    }
  }
}
