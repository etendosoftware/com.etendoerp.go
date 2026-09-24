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

package com.etendoerp.go.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;

/**
 * Shared CORS policy for Etendo GO endpoints.
 */
public final class CorsUtils {

  private static final String ALLOWED_ORIGINS_PROPERTY = "etgo.allowed.origins";
  private static final String ALLOWED_ORIGINS_ENV = "ETGO_ALLOWED_ORIGINS";
  // ETP-4575 — these are the local development origins the session's CSRF/origin
  // check trusts out of the box. 3000 (CRA) and 5173 (vite's own default) came
  // from the generic list and never matched this project: the app's dev and
  // preview servers run on 3100, and the E2E harness starts its preview on 4173.
  // With those missing, every unsafe request from a local UI failed the origin
  // check and surfaced as "CSRF validation failed" even though the token was
  // correct — the integration E2E suite could not pass on a fresh checkout.
  // Localhost only; any other origin still has to be declared through
  // etgo.allowed.origins / ETGO_ALLOWED_ORIGINS.
  private static final Set<String> DEFAULT_ALLOWED_ORIGINS = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList(
          "http://localhost:3000",
          "http://localhost:3100",
          "http://localhost:4173",
          "http://localhost:5173",
          "http://127.0.0.1:3000",
          "http://127.0.0.1:3100",
          "http://127.0.0.1:4173",
          "http://127.0.0.1:5173")));

  private CorsUtils() {
  }

  /**
   * Applies the configured CORS policy when the request origin is allowlisted.
     *
     * @param request current HTTP request
     * @param response current HTTP response
     * @param methods allowed HTTP methods header value
     * @param headers allowed request headers header value
     * @param exposedHeaders response headers exposed to the client, if any
     * @param allowCredentials whether credentials are allowed for the origin
   */
  public static void apply(HttpServletRequest request, HttpServletResponse response,
      String methods, String headers, String exposedHeaders, boolean allowCredentials) {
    String origin = StringUtils.trimToNull(request.getHeader("Origin"));
    if (origin == null || !isAllowedOrigin(request, origin)) {
      return;
    }

    response.setHeader("Access-Control-Allow-Origin", origin);
    response.setHeader("Vary", "Origin");
    response.setHeader("Access-Control-Allow-Methods", methods);
    response.setHeader("Access-Control-Allow-Headers", headers);
    response.setHeader("Access-Control-Max-Age", "86400");
    if (StringUtils.isNotBlank(exposedHeaders)) {
      response.setHeader("Access-Control-Expose-Headers", exposedHeaders);
    }
    if (allowCredentials) {
      response.setHeader("Access-Control-Allow-Credentials", "true");
    }
  }

  /**
   * Whether the given origin is allowlisted for the current request (same-origin, default
   * allowlist, or configured origins). Exposed for reuse by the session CSRF/origin check.
   *
   * @param request current HTTP request
   * @param origin  the origin to validate (e.g. from the {@code Origin} or {@code Referer} header)
   * @return {@code true} if the origin is allowed
   */
  public static boolean isAllowedOrigin(HttpServletRequest request, String origin) {
    String requestOrigin = buildRequestOrigin(request);
    if (origin.equals(requestOrigin) || DEFAULT_ALLOWED_ORIGINS.contains(origin)) {
      return true;
    }
    return resolveConfiguredOrigins().stream().anyMatch(pattern -> matchesOrigin(pattern, origin));
  }

  private static Set<String> resolveConfiguredOrigins() {
    String configured = System.getProperty(ALLOWED_ORIGINS_PROPERTY);
    if (StringUtils.isBlank(configured)) {
      configured = System.getenv(ALLOWED_ORIGINS_ENV);
    }
    if (StringUtils.isBlank(configured)) {
      return Collections.emptySet();
    }
    return Arrays.stream(configured.split(","))
        .map(StringUtils::trimToNull)
        .filter(StringUtils::isNotBlank)
        .collect(Collectors.toSet());
  }

  /**
   * Matches a configured origin entry against an actual request origin. A configured entry
   * with no {@code *} must match exactly (pre-existing behavior). A {@code *} stands for
   * exactly one hostname label (no {@code .} or {@code /}) — e.g. {@code http://*.localhost:3100}
   * matches {@code http://goclean.localhost:3100} and {@code http://etendo.localhost:3100}, so a
   * single entry covers every local subdomain instead of listing each one out.
   *
   * @param pattern a raw entry from {@code etgo.allowed.origins} / {@code ETGO_ALLOWED_ORIGINS}
   * @param origin  the actual request origin to test
   * @return {@code true} if {@code origin} matches {@code pattern}
   */
  private static boolean matchesOrigin(String pattern, String origin) {
    if (!pattern.contains("*")) {
      return pattern.equals(origin);
    }
    String[] parts = pattern.split(Pattern.quote("*"), -1);
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        regex.append("[^./]+");
      }
      regex.append(Pattern.quote(parts[i]));
    }
    return origin.matches(regex.toString());
  }

  private static String buildRequestOrigin(HttpServletRequest request) {
    StringBuilder url = new StringBuilder(request.getRequestURL().toString());
    try {
      URI uri = new URI(url.toString());
      StringBuilder origin = new StringBuilder()
          .append(uri.getScheme())
          .append("://")
          .append(uri.getHost());
      int port = uri.getPort();
      if (port >= 0 && port != uri.toURL().getDefaultPort()) {
        origin.append(':').append(port);
      }
      return origin.toString();
    } catch (URISyntaxException | java.net.MalformedURLException ignored) {
      return "";
    }
  }
}