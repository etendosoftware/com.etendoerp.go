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

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves public URLs advertised in OAuth2/MCP discovery metadata.
 */
public final class PublicUrlResolver {

  public static final String MCP_PUBLIC_URL_PROPERTY = "etgo.mcp.public.url";
  public static final String MCP_PUBLIC_URL_ENV = "ETGO_MCP_PUBLIC_URL";
  public static final String OAUTH2_PUBLIC_URL_PROPERTY = "etgo.oauth2.public.url";
  public static final String OAUTH2_PUBLIC_URL_ENV = "ETGO_OAUTH2_PUBLIC_URL";

  private static final Logger log = LogManager.getLogger(PublicUrlResolver.class);

  private PublicUrlResolver() {
  }

  /**
   * Returns the public MCP resource URL, for example {@code https://go.example.com/mcp}.
   *
   * @param request current HTTP request used to build the fallback internal servlet URL
   * @return configured public MCP resource URL, the request-derived internal MCP URL, or null
   *     when neither can be resolved
   */
  public static String resolveMcpResourceUrl(HttpServletRequest request) {
    String configured = resolveConfiguredUrl(MCP_PUBLIC_URL_PROPERTY, MCP_PUBLIC_URL_ENV);
    if (configured != null) {
      return configured;
    }
    return appendPath(buildBaseUrl(request), "sws/mcp");
  }

  /**
   * Returns the public OAuth2 authorization server URL, for example
   * {@code https://go.example.com/etendo/oauth2}.
   *
   * @param request current HTTP request used to build the fallback internal servlet URL
   * @return configured public OAuth2 URL, the request-derived internal OAuth2 URL, or null
   *     when neither can be resolved
   */
  public static String resolveOAuth2Url(HttpServletRequest request) {
    String configured = resolveConfiguredUrl(OAUTH2_PUBLIC_URL_PROPERTY, OAUTH2_PUBLIC_URL_ENV);
    if (configured != null) {
      return configured;
    }
    return appendPath(buildBaseUrl(request), "oauth2");
  }

  /**
   * Appends a path segment to a URL without producing duplicate slashes.
   *
   * @param baseUrl base URL, optionally ending with slashes
   * @param path path segment, optionally starting with slashes
   * @return joined URL, or null when either input is blank
   */
  public static String appendPath(String baseUrl, String path) {
    String normalizedBaseUrl = stripTrailingSlash(StringUtils.trimToNull(baseUrl));
    String normalizedPath = StringUtils.stripStart(StringUtils.trimToNull(path), "/");
    if (StringUtils.isBlank(normalizedBaseUrl) || StringUtils.isBlank(normalizedPath)) {
      return null;
    }
    return normalizedBaseUrl + "/" + normalizedPath;
  }

  private static String resolveConfiguredUrl(String propertyName, String envName) {
    String configured = readProperty(propertyName);
    if (StringUtils.isBlank(configured)) {
      configured = System.getenv(envName);
    }
    return stripTrailingSlash(StringUtils.trimToNull(configured));
  }

  private static String readProperty(String propertyName) {
    String systemProperty = System.getProperty(propertyName);
    if (StringUtils.isNotBlank(systemProperty)) {
      return systemProperty;
    }
    try {
      return org.openbravo.base.session.OBPropertiesProvider.getInstance()
          .getOpenbravoProperties().getProperty(propertyName);
    } catch (Exception e) {
      log.debug("Could not read {} from Openbravo properties: {}", propertyName, e.getMessage());
      return null;
    }
  }

  private static String stripTrailingSlash(String value) {
    return StringUtils.trimToNull(StringUtils.stripEnd(value, "/"));
  }

  private static String buildBaseUrl(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String scheme = request.getScheme();
    String host = request.getServerName();
    int port = request.getServerPort();
    String contextPath = request.getContextPath();
    boolean defaultPort = ("http".equals(scheme) && port == 80)
        || ("https".equals(scheme) && port == 443);
    return scheme + "://" + host + (defaultPort ? "" : ":" + port) + contextPath;
  }
}
