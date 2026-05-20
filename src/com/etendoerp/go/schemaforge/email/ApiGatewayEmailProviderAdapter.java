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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.apache.commons.io.IOUtils;
import org.codehaus.jettison.json.JSONException;

/**
 * Provider adapter for an API Gateway-style transactional email endpoint.
 */
public class ApiGatewayEmailProviderAdapter implements EmailProviderAdapter {

  private static final String API_KEY_HEADER = "x-api-key";

  private final EmailProviderConfig config;
  private final EmailTransport transport;

  /**
   * Creates an adapter using runtime server-side provider configuration.
   */
  public ApiGatewayEmailProviderAdapter() {
    this(EmailProviderConfig.fromRuntime(), new HttpUrlConnectionEmailTransport());
  }

  ApiGatewayEmailProviderAdapter(EmailProviderConfig config, EmailTransport transport) {
    this.config = Objects.requireNonNull(config, "Email provider config cannot be null");
    this.transport = Objects.requireNonNull(transport, "Email transport cannot be null");
  }

  /**
   * Indicates whether runtime provider configuration is complete.
   *
   * @return {@code true} when the adapter has base URL and API key configured
   */
  @Override
  public boolean isConfigured() {
    return config.isConfigured();
  }

  /**
   * Sends a resolved provider request to the configured API Gateway endpoint.
   *
   * @param request provider payload resolved by a contract
   * @return provider response metadata
   * @throws IOException when the HTTP transport fails
   * @throws JSONException when the provider payload cannot be serialized
   */
  @Override
  public EmailProviderResponse send(EmailProviderRequest request)
      throws IOException, JSONException {
    Objects.requireNonNull(request, "Email provider request cannot be null");
    if (!isConfigured()) {
      throw new IOException("Email provider is not properly configured. "
          + "Check base URL and API key.");
    }
    return transport.post(config.getBaseUrl(), config.getApiKey(),
        request.toProviderPayload().toString(), config.getTimeoutMs());
  }

  interface EmailTransport {
    /**
     * Posts a serialized provider payload to the configured email endpoint.
     *
     * @param endpoint configured provider URL
     * @param apiKey server-side provider API key
     * @param body serialized provider payload
     * @param timeoutMs connection and read timeout in milliseconds
     * @return provider response metadata
     * @throws IOException when the HTTP transport fails
     */
    EmailProviderResponse post(String endpoint, String apiKey, String body, int timeoutMs)
        throws IOException;
  }

  static class HttpUrlConnectionEmailTransport implements EmailTransport {
    @Override
    public EmailProviderResponse post(String endpoint, String apiKey, String body, int timeoutMs)
        throws IOException {
      HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL()
          .openConnection();
      connection.setConnectTimeout(timeoutMs);
      connection.setReadTimeout(timeoutMs);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty(API_KEY_HEADER, apiKey);

      byte[] payload = body.getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(payload.length);
      try (OutputStream outputStream = connection.getOutputStream()) {
        outputStream.write(payload);
      }

      try {
        int statusCode = connection.getResponseCode();
        return new EmailProviderResponse(statusCode, readResponseBody(connection, statusCode));
      } finally {
        connection.disconnect();
      }
    }

    private static String readResponseBody(HttpURLConnection connection, int statusCode)
        throws IOException {
      InputStream stream = statusCode >= 400 ? connection.getErrorStream()
          : connection.getInputStream();
      if (stream == null) {
        return "";
      }
      try (InputStream inputStream = stream) {
        return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
      }
    }
  }
}
