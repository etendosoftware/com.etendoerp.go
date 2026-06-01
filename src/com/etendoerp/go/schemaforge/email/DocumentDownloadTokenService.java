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

package com.etendoerp.go.schemaforge.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

/**
 * Creates and validates signed download tokens tied to a successful email audit event.
 */
public final class DocumentDownloadTokenService {

  public static final String PROP_DOWNLOAD_BASE_URL =
      "etendo.go.email.documentDownloadBaseUrl";
  public static final String ENV_DOWNLOAD_BASE_URL =
      "ETGO_EMAIL_DOCUMENT_DOWNLOAD_BASE_URL";
  public static final String PROP_TOKEN_SECRET =
      "etendo.go.email.documentDownloadTokenSecret";
  public static final String ENV_TOKEN_SECRET =
      "ETGO_EMAIL_DOCUMENT_DOWNLOAD_TOKEN_SECRET";
  public static final String PROP_TOKEN_TTL_SECONDS =
      "etendo.go.email.documentDownloadTokenTtlSeconds";
  public static final String ENV_TOKEN_TTL_SECONDS =
      "ETGO_EMAIL_DOCUMENT_DOWNLOAD_TOKEN_TTL_SECONDS";
  public static final long DEFAULT_TOKEN_TTL_SECONDS = 7L * 24L * 60L * 60L;

  private static final Logger log = LogManager.getLogger(DocumentDownloadTokenService.class);
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String TOKEN_VERSION = "v1";

  private DocumentDownloadTokenService() {
  }

  /**
   * Builds a public download link for one document send event.
   *
   * @param contractName email contract name
   * @param specName NEO document spec name
   * @param recordId document record id
   * @param clientId client that owns the document
   * @param idempotencyKey send event idempotency key
   * @return signed download link when configuration is complete
   */
  public static Optional<String> createDownloadLink(String contractName, String specName,
      String recordId, String clientId, String idempotencyKey) {
    String baseUrl = readConfig(PROP_DOWNLOAD_BASE_URL, ENV_DOWNLOAD_BASE_URL);
    String secret = readConfig(PROP_TOKEN_SECRET, ENV_TOKEN_SECRET);
    if (StringUtils.isAnyBlank(baseUrl, secret, contractName, specName, recordId, clientId,
        idempotencyKey)) {
      return Optional.empty();
    }
    String token = createToken(contractName, specName, recordId, clientId, idempotencyKey,
        currentTimeSeconds() + tokenTtlSeconds());
    return Optional.of(StringUtils.removeEnd(baseUrl, "/") + "/" + token);
  }

  /**
   * Validates a signed document download token.
   *
   * @param token signed token
   * @return validated token claims
   */
  public static Optional<Claims> validate(String token) {
    String normalizedToken = StringUtils.trimToNull(token);
    String secret = readConfig(PROP_TOKEN_SECRET, ENV_TOKEN_SECRET);
    if (StringUtils.isAnyBlank(normalizedToken, secret)) {
      return Optional.empty();
    }
    int separator = normalizedToken.lastIndexOf('.');
    if (separator <= 0 || separator == normalizedToken.length() - 1) {
      return Optional.empty();
    }
    String payloadPart = normalizedToken.substring(0, separator);
    String signaturePart = normalizedToken.substring(separator + 1);
    if (!constantTimeEquals(signaturePart, sign(payloadPart, secret))) {
      return Optional.empty();
    }
    try {
      JSONObject payload = new JSONObject(new String(base64UrlDecode(payloadPart),
          StandardCharsets.UTF_8));
      Claims claims = Claims.from(payload);
      if (!TOKEN_VERSION.equals(claims.version) || claims.expiresAtSeconds < currentTimeSeconds()) {
        return Optional.empty();
      }
      return Optional.of(claims);
    } catch (JSONException | IllegalArgumentException e) {
      log.debug("Invalid document download token: {}", e.getMessage(), e);
      return Optional.empty();
    }
  }

  static String createToken(String contractName, String specName, String recordId,
      String clientId, String idempotencyKey, long expiresAtSeconds) {
    try {
      JSONObject payload = new JSONObject();
      payload.put("v", TOKEN_VERSION);
      payload.put("contract", contractName);
      payload.put("spec", specName);
      payload.put("record", recordId);
      payload.put("client", clientId);
      payload.put("send", idempotencyKey);
      payload.put("exp", expiresAtSeconds);
      String payloadPart = base64Url(payload.toString().getBytes(StandardCharsets.UTF_8));
      return payloadPart + "." + sign(payloadPart, readRequiredSecret());
    } catch (JSONException e) {
      throw new OBException("Could not build document download token", e);
    }
  }

  static String readConfig(String propertyName, String envName) {
    String systemValue = StringUtils.trimToNull(System.getProperty(propertyName));
    if (systemValue != null) {
      return systemValue;
    }
    String envValue = StringUtils.trimToNull(System.getenv(envName));
    return envValue != null ? envValue : readOpenbravoProperty(propertyName);
  }

  private static long tokenTtlSeconds() {
    String configured = readConfig(PROP_TOKEN_TTL_SECONDS, ENV_TOKEN_TTL_SECONDS);
    if (configured == null) {
      return DEFAULT_TOKEN_TTL_SECONDS;
    }
    try {
      return Math.max(60L, Long.parseLong(configured));
    } catch (NumberFormatException e) {
      log.debug("Invalid document download token TTL '{}', using default", configured);
      return DEFAULT_TOKEN_TTL_SECONDS;
    }
  }

  private static String readRequiredSecret() {
    String secret = readConfig(PROP_TOKEN_SECRET, ENV_TOKEN_SECRET);
    if (secret == null) {
      throw new OBException("Document download token secret is not configured");
    }
    return secret;
  }

  private static String readOpenbravoProperty(String propertyName) {
    try {
      return StringUtils.trimToNull(org.openbravo.base.session.OBPropertiesProvider.getInstance()
          .getOpenbravoProperties().getProperty(propertyName));
    } catch (Exception e) {
      log.debug("Could not read Openbravo property {}: {}", propertyName, e.getMessage(), e);
      return null;
    }
  }

  private static String sign(String payloadPart, String secret) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return base64Url(mac.doFinal(payloadPart.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new OBException("Could not sign document download token", e);
    }
  }

  private static String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] base64UrlDecode(String value) {
    return Base64.getUrlDecoder().decode(value);
  }

  private static boolean constantTimeEquals(String expected, String actual) {
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        actual.getBytes(StandardCharsets.UTF_8));
  }

  private static long currentTimeSeconds() {
    return System.currentTimeMillis() / 1000L;
  }

  /**
   * Validated document download token claims.
   */
  public static final class Claims {
    private final String version;
    private final String contractName;
    private final String specName;
    private final String recordId;
    private final String clientId;
    private final String idempotencyKey;
    private final long expiresAtSeconds;

    private Claims(String version, String contractName, String specName, String recordId,
        String clientId, String idempotencyKey, long expiresAtSeconds) {
      this.version = version;
      this.contractName = contractName;
      this.specName = specName;
      this.recordId = recordId;
      this.clientId = clientId;
      this.idempotencyKey = idempotencyKey;
      this.expiresAtSeconds = expiresAtSeconds;
    }

    private static Claims from(JSONObject payload) {
      return new Claims(
          StringUtils.trimToNull(payload.optString("v")),
          StringUtils.trimToNull(payload.optString("contract")),
          StringUtils.trimToNull(payload.optString("spec")),
          StringUtils.trimToNull(payload.optString("record")),
          StringUtils.trimToNull(payload.optString("client")),
          StringUtils.trimToNull(payload.optString("send")),
          payload.optLong("exp", 0L));
    }

    public String getContractName() {
      return contractName;
    }

    public String getSpecName() {
      return specName;
    }

    public String getRecordId() {
      return recordId;
    }

    public String getClientId() {
      return clientId;
    }

    public String getIdempotencyKey() {
      return idempotencyKey;
    }
  }
}
