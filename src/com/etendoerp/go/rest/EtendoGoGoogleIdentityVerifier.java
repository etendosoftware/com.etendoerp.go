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

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

/**
 * Verifies Google Identity Services sign-in responses.
 *
 * Browser clients submit the Google ID token in {@code credential}. Account identity is
 * resolved exclusively from the validated token.
 */
final class EtendoGoGoogleIdentityVerifier implements EtendoGoSsoAssertionVerifier {

  static final String CLIENT_ID_PROPERTY = "etendo.go.sso.google.clientId";
  static final String CLIENT_ID_ENV = "ETGO_GOOGLE_CLIENT_ID";
  static final String CSRF_COOKIE = "g_csrf_token";
  static final String FIELD_CREDENTIAL = "credential";
  static final String FIELD_CSRF_TOKEN = "g_csrf_token";

  private static final Logger log = LogManager.getLogger(EtendoGoGoogleIdentityVerifier.class);
  private static final String FIELD_CLIENT_ID = "client_id";
  private static final NetHttpTransport GOOGLE_HTTP_TRANSPORT = new NetHttpTransport();
  private static final GsonFactory GOOGLE_JSON_FACTORY = GsonFactory.getDefaultInstance();

  private final ConfigurationProvider configurationProvider;
  private final GoogleCredentialValidator credentialValidator;

  EtendoGoGoogleIdentityVerifier() {
    this(new RuntimeConfigurationProvider(), new GoogleApiCredentialValidator());
  }

  EtendoGoGoogleIdentityVerifier(GoogleIdentityConfiguration configuration,
      GoogleCredentialValidator credentialValidator) {
    this(new FixedConfigurationProvider(configuration), credentialValidator);
  }

  private EtendoGoGoogleIdentityVerifier(ConfigurationProvider configurationProvider,
      GoogleCredentialValidator credentialValidator) {
    this.configurationProvider = configurationProvider;
    this.credentialValidator = credentialValidator;
  }

  @Override
  public EtendoGoSsoAssertion verify(HttpServletRequest request, String rawBody)
      throws EtendoGoSsoAssertionException {
    GoogleIdentityConfiguration configuration = configurationProvider.get();
    if (!configuration.isConfigured()) {
      throw new EtendoGoSsoAssertionException(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "Google SSO login is not configured");
    }

    GoogleSignInBody body = parseBody(request, rawBody);
    validateCsrfTokenIfPresent(request, body.csrfToken);
    validateClientId(body.clientId, configuration);
    try {
      return credentialValidator.validate(body.credential, configuration);
    } catch (IOException | GeneralSecurityException e) {
      log.warn("Google ID token validation failed", e);
      throw unauthorized("Invalid Google credential");
    }
  }

  private static GoogleSignInBody parseBody(HttpServletRequest request, String rawBody)
      throws EtendoGoSsoAssertionException {
    String contentType = StringUtils.lowerCase(StringUtils.defaultString(request.getContentType()),
        Locale.ROOT);
    GoogleSignInBody body = contentType.contains("application/x-www-form-urlencoded")
        ? parseFormBody(rawBody)
        : parseJsonBody(rawBody);
    if (StringUtils.isBlank(body.credential)) {
      throw badRequest("Missing Google credential");
    }
    return body;
  }

  private static GoogleSignInBody parseJsonBody(String rawBody)
      throws EtendoGoSsoAssertionException {
    try {
      JSONObject body = new JSONObject(StringUtils.defaultString(rawBody));
      return new GoogleSignInBody(StringUtils.trimToNull(body.optString(FIELD_CREDENTIAL, null)),
          StringUtils.trimToNull(body.optString(FIELD_CSRF_TOKEN, null)),
          StringUtils.trimToNull(body.optString(FIELD_CLIENT_ID, null)));
    } catch (JSONException e) {
      throw badRequest("Invalid Google sign-in body");
    }
  }

  private static GoogleSignInBody parseFormBody(String rawBody)
      throws EtendoGoSsoAssertionException {
    String credential = null;
    String csrfToken = null;
    String clientId = null;
    String[] pairs = StringUtils.defaultString(rawBody).split("&");
    for (String pair : pairs) {
      int separator = pair.indexOf('=');
      if (separator < 0) {
        continue;
      }
      String key = decode(pair.substring(0, separator));
      String value = decode(pair.substring(separator + 1));
      if (FIELD_CREDENTIAL.equals(key)) {
        credential = StringUtils.trimToNull(value);
      } else if (FIELD_CSRF_TOKEN.equals(key)) {
        csrfToken = StringUtils.trimToNull(value);
      } else if (FIELD_CLIENT_ID.equals(key)) {
        clientId = StringUtils.trimToNull(value);
      }
    }
    return new GoogleSignInBody(credential, csrfToken, clientId);
  }

  private static String decode(String value) throws EtendoGoSsoAssertionException {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw badRequest("Invalid Google sign-in body");
    }
  }

  private static void validateCsrfTokenIfPresent(HttpServletRequest request, String bodyToken)
      throws EtendoGoSsoAssertionException {
    if (StringUtils.isBlank(bodyToken)) {
      return;
    }
    String cookieToken = findCookie(request, CSRF_COOKIE);
    if (StringUtils.isBlank(cookieToken)) {
      throw badRequest("Missing Google CSRF cookie");
    }
    if (!constantTimeEquals(cookieToken, bodyToken)) {
      throw new EtendoGoSsoAssertionException(HttpServletResponse.SC_FORBIDDEN,
          "Invalid Google CSRF token");
    }
  }

  private static String findCookie(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName())) {
        return StringUtils.trimToNull(cookie.getValue());
      }
    }
    return null;
  }

  private static void validateClientId(String clientId,
      GoogleIdentityConfiguration configuration) throws EtendoGoSsoAssertionException {
    if (StringUtils.isNotBlank(clientId) && !configuration.clientIds.contains(clientId)) {
      throw unauthorized("Invalid Google credential audience");
    }
  }

  private static boolean constantTimeEquals(String expected, String received) {
    byte[] expectedBytes = StringUtils.defaultString(expected).getBytes(StandardCharsets.UTF_8);
    byte[] receivedBytes = StringUtils.defaultString(received).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedBytes, receivedBytes);
  }

  static boolean isAuthoritativeEmail(boolean emailVerified) {
    return emailVerified;
  }

  private static EtendoGoSsoAssertionException unauthorized(String message) {
    return new EtendoGoSsoAssertionException(HttpServletResponse.SC_UNAUTHORIZED, message);
  }

  private static EtendoGoSsoAssertionException badRequest(String message) {
    return new EtendoGoSsoAssertionException(HttpServletResponse.SC_BAD_REQUEST, message);
  }

  private interface ConfigurationProvider {
    GoogleIdentityConfiguration get();
  }

  private static final class RuntimeConfigurationProvider implements ConfigurationProvider {
    @Override
    public GoogleIdentityConfiguration get() {
      return GoogleIdentityConfiguration.fromRuntime();
    }
  }

  private static final class FixedConfigurationProvider implements ConfigurationProvider {
    private final GoogleIdentityConfiguration configuration;

    private FixedConfigurationProvider(GoogleIdentityConfiguration configuration) {
      this.configuration = configuration;
    }

    @Override
    public GoogleIdentityConfiguration get() {
      return configuration;
    }
  }

  interface GoogleCredentialValidator {
    /**
     * Validates a Google credential and maps trusted token claims to an SSO assertion.
     *
     * @param credential Google Identity Services credential from the browser.
     * @param configuration server-side Google SSO configuration.
     * @return SSO assertion resolved from verified Google token claims.
     * @throws IOException if Google's public token metadata cannot be read.
     * @throws GeneralSecurityException if token signature verification fails.
     * @throws EtendoGoSsoAssertionException if the credential is invalid for this tenant.
     */
    EtendoGoSsoAssertion validate(String credential, GoogleIdentityConfiguration configuration)
        throws IOException, GeneralSecurityException, EtendoGoSsoAssertionException;
  }

  private static final class GoogleApiCredentialValidator implements GoogleCredentialValidator {
    @Override
    public EtendoGoSsoAssertion validate(String credential,
        GoogleIdentityConfiguration configuration)
        throws IOException, GeneralSecurityException, EtendoGoSsoAssertionException {
      GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(GOOGLE_HTTP_TRANSPORT,
          GOOGLE_JSON_FACTORY).setAudience(configuration.clientIds).build();
      GoogleIdToken idToken = verifier.verify(credential);
      if (idToken == null) {
        throw unauthorized("Invalid Google credential");
      }
      Payload payload = idToken.getPayload();
      String subject = StringUtils.trimToNull(payload.getSubject());
      String email = normalizeEmail(payload.getEmail());
      if (subject == null || email == null) {
        throw unauthorized("Invalid Google credential claims");
      }
      boolean emailVerified = Boolean.TRUE.equals(payload.getEmailVerified());
      String name = fallbackName((String) payload.get("name"), email);
      return new EtendoGoSsoAssertion(EtendoGoSsoProviderRegistry.GOOGLE_PROVIDER, subject,
          email, name, isAuthoritativeEmail(emailVerified));
    }

    private static String normalizeEmail(String email) {
      return StringUtils.lowerCase(StringUtils.trimToNull(email), Locale.ROOT);
    }

    private static String fallbackName(String name, String email) {
      String normalizedName = StringUtils.trimToNull(name);
      if (normalizedName != null) {
        return normalizedName;
      }
      int atIndex = email.indexOf('@');
      return atIndex > 0 ? email.substring(0, atIndex) : email;
    }
  }

  static final class GoogleIdentityConfiguration {
    private final List<String> clientIds;

    GoogleIdentityConfiguration(List<String> clientIds) {
      this.clientIds = Collections.unmodifiableList(normalizeClientIds(clientIds));
    }

    private boolean isConfigured() {
      return !clientIds.isEmpty();
    }

    private static GoogleIdentityConfiguration fromRuntime() {
      return new GoogleIdentityConfiguration(
          parseCsv(resolveConfiguredValue(CLIENT_ID_PROPERTY, CLIENT_ID_ENV)));
    }

    private static List<String> parseCsv(String value) {
      List<String> values = new ArrayList<>();
      for (String item : StringUtils.defaultString(value).split(",")) {
        String normalized = StringUtils.trimToNull(item);
        if (normalized != null) {
          values.add(normalized);
        }
      }
      return values;
    }

    private static List<String> normalizeClientIds(List<String> values) {
      List<String> normalized = new ArrayList<>();
      if (values == null) {
        return normalized;
      }
      for (String value : values) {
        String clientId = StringUtils.trimToNull(value);
        if (clientId != null) {
          normalized.add(clientId);
        }
      }
      return normalized;
    }

    private static String resolveConfiguredValue(String propertyName, String envName) {
      String configured = readProperty(propertyName);
      if (StringUtils.isBlank(configured)) {
        configured = System.getenv(envName);
      }
      return StringUtils.trimToNull(configured);
    }

    private static String readProperty(String propertyName) {
      String systemProperty = System.getProperty(propertyName);
      if (StringUtils.isNotBlank(systemProperty)) {
        return systemProperty;
      }
      try {
        Properties properties = org.openbravo.base.session.OBPropertiesProvider.getInstance()
            .getOpenbravoProperties();
        return properties == null ? null : properties.getProperty(propertyName);
      } catch (Exception e) {
        log.debug("Could not read {} from Openbravo properties: {}", propertyName,
            e.getMessage());
        return null;
      }
    }
  }

  private static final class GoogleSignInBody {
    private final String credential;
    private final String csrfToken;
    private final String clientId;

    private GoogleSignInBody(String credential, String csrfToken, String clientId) {
      this.credential = credential;
      this.csrfToken = csrfToken;
      this.clientId = clientId;
    }
  }
}
