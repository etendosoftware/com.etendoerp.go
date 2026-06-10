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
package com.etendoerp.go.rest;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;

/**
 * Provider-agnostic SSO verification boundary.
 */
final class EtendoGoSsoProviderRegistry {

  /**
   * Provider id used by the built-in Google verifier and package-level tests.
   */
  static final String GOOGLE_PROVIDER = "google";

  private final Map<String, EtendoGoSsoAssertionVerifier> verifiers;

  EtendoGoSsoProviderRegistry() {
    this(Collections.singletonMap(GOOGLE_PROVIDER, new EtendoGoGoogleIdentityVerifier()));
  }

  EtendoGoSsoProviderRegistry(Map<String, EtendoGoSsoAssertionVerifier> verifiers) {
    this.verifiers = normalizeVerifiers(verifiers);
  }

  /**
   * Creates a registry with a single provider, primarily for servlet tests.
   *
   * @param provider provider id to register.
   * @param verifier verifier implementation for the provider.
   * @return registry containing only the provided verifier.
   */
  static EtendoGoSsoProviderRegistry singleProvider(String provider,
      EtendoGoSsoAssertionVerifier verifier) {
    return new EtendoGoSsoProviderRegistry(Collections.singletonMap(provider, verifier));
  }

  /**
   * Verifies an SSO request using the verifier registered for the provider.
   *
   * @param provider provider id from the request path.
   * @param request servlet request with provider-specific headers and cookies.
   * @param rawBody raw browser-submitted request body.
   * @return trusted SSO assertion.
   * @throws EtendoGoSsoAssertionException when the provider is unsupported or the
   * assertion fails.
   */
  EtendoGoSsoAssertion verify(String provider, HttpServletRequest request, String rawBody)
      throws EtendoGoSsoAssertionException {
    String normalizedProvider = normalizeProvider(provider);
    EtendoGoSsoAssertionVerifier verifier = verifiers.get(normalizedProvider);
    if (verifier == null) {
      throw new EtendoGoSsoAssertionException(HttpServletResponse.SC_NOT_FOUND,
          "Unsupported SSO provider");
    }
    EtendoGoSsoAssertion assertion = verifier.verify(request, rawBody);
    if (assertion == null
        || !normalizedProvider.equals(normalizeProvider(assertion.getProvider()))) {
      throw new EtendoGoSsoAssertionException(HttpServletResponse.SC_UNAUTHORIZED,
          "SSO provider mismatch");
    }
    return assertion;
  }

  /**
   * Normalizes a provider id for path matching.
   *
   * @param provider raw provider value.
   * @return normalized provider id, or null when blank.
   */
  static String normalizeProvider(String provider) {
    return StringUtils.lowerCase(StringUtils.trimToNull(provider), Locale.ROOT);
  }

  private static Map<String, EtendoGoSsoAssertionVerifier> normalizeVerifiers(
      Map<String, EtendoGoSsoAssertionVerifier> verifiers) {
    Map<String, EtendoGoSsoAssertionVerifier> normalized = new HashMap<>();
    if (verifiers == null) {
      return normalized;
    }
    for (Map.Entry<String, EtendoGoSsoAssertionVerifier> entry : verifiers.entrySet()) {
      String provider = normalizeProvider(entry.getKey());
      if (provider != null && entry.getValue() != null) {
        normalized.put(provider, entry.getValue());
      }
    }
    return Collections.unmodifiableMap(normalized);
  }
}
