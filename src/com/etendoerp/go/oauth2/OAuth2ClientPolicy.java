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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;

final class OAuth2ClientPolicy {
  private static final Logger log = LogManager.getLogger(OAuth2ClientPolicy.class);

  private OAuth2ClientPolicy() {
  }

  static Set<String> parseScopes(String scopeStr, Set<String> validScopes) {
    if (scopeStr == null || scopeStr.trim().isEmpty() || validScopes == null) {
      return Collections.emptySet();
    }
    Set<String> scopes = new HashSet<>(Arrays.asList(scopeStr.trim().split("\\s+")));
    scopes.retainAll(validScopes);
    return scopes;
  }

  static boolean hasUnsupportedScopes(String scopeStr, Set<String> validScopes) {
    if (scopeStr == null || scopeStr.trim().isEmpty()) {
      return false;
    }
    if (validScopes == null) {
      return true;
    }
    Set<String> requestedScopes = new HashSet<>(Arrays.asList(scopeStr.trim().split("\\s+")));
    return !validScopes.containsAll(requestedScopes);
  }

  static boolean isScopeAllowed(Set<String> requested, Set<String> allowed, String wildcardScope) {
    if (requested == null || allowed == null) {
      return false;
    }
    return allowed.contains(wildcardScope) || allowed.containsAll(requested);
  }

  static String normalizeRedirectUris(JSONArray redirectUris) throws JSONException {
    if (redirectUris == null || redirectUris.length() == 0) {
      throw new OBException("redirect_uris is required");
    }
    for (int i = 0; i < redirectUris.length(); i++) {
      String redirectUri = redirectUris.getString(i);
      if (!isSafeRedirectUri(redirectUri)) {
        throw new OBException(
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
  /**
   * Checks whether a redirect URI is present in the registered redirect URI JSON array.
   *
   * @param redirectUrisJson JSON array string with registered redirect URIs
   * @param redirectUri redirect URI to validate
   * @return {@code true} when the URI is explicitly registered for the client
   */
  static boolean isRegisteredRedirectUri(String redirectUrisJson, String redirectUri) {
    if (redirectUri == null || redirectUri.isEmpty()
        || redirectUrisJson == null || redirectUrisJson.isEmpty()) {
      return false;
    }
    try {
      JSONArray redirectUris = new JSONArray(redirectUrisJson);
      for (int i = 0; i < redirectUris.length(); i++) {
        if (redirectUri.equals(redirectUris.getString(i))) {
          return true;
        }
      }
      return false;
    } catch (JSONException e) {
      log.error("Invalid JSON format for redirect URIs: {}", redirectUrisJson, e);
      return false;
    }
  }


}
