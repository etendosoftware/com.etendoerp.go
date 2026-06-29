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

package com.etendoerp.go.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Isolated outbound HTTP helper that fetches documentation from Context7 for the
 * {@code etendosoftware/etendo-go-docs} library.
 * <p>
 * This is the only place in the MCP module that performs an external network call,
 * so all egress concerns (timeouts, URL building, encoding, status handling) are kept
 * here and never leak into the router.
 * <p>
 * Functionally equivalent to:
 * <pre>
 * curl -s "https://context7.com/api/v1/etendosoftware/etendo-go-docs?topic=&lt;term&gt;&amp;type=txt&amp;tokens=&lt;n&gt;"
 * </pre>
 * <p>
 * The {@link HttpClient} is injected via a package-private constructor so unit tests
 * can supply a mock and exercise the logic without touching the network.
 */
class Context7DocsClient {

  private static final Logger log = LogManager.getLogger(Context7DocsClient.class);

  /** Default Context7 endpoint for the Etendo Go docs library. */
  static final String DEFAULT_BASE_URL =
      "https://context7.com/api/v1/etendosoftware/etendo-go-docs";
  /** Default approximate size (in tokens) of the returned documentation. */
  static final int DEFAULT_TOKENS = 5000;
  /** Default response format. */
  static final String DEFAULT_TYPE = "txt";
  /** Alternative supported response format. */
  static final String TYPE_JSON = "json";

  /** Lower bound for the {@code tokens} clamp. */
  static final int MIN_TOKENS = 500;
  /** Upper bound for the {@code tokens} clamp. */
  static final int MAX_TOKENS = 20000;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_ERROR_BODY_CHARS = 500;

  private final HttpClient client;
  private final String baseUrl;

  /**
   * Production constructor: builds a default {@link HttpClient} pointing at the
   * canonical Context7 endpoint.
   */
  Context7DocsClient() {
    this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), DEFAULT_BASE_URL);
  }

  /**
   * Test-friendly constructor: inject a mocked {@link HttpClient}.
   *
   * @param client the HTTP client to use for outbound requests
   */
  Context7DocsClient(HttpClient client) {
    this(client, DEFAULT_BASE_URL);
  }

  /**
   * Fully parameterized constructor for tests that also want to override the base URL.
   *
   * @param client  the HTTP client to use for outbound requests
   * @param baseUrl the Context7 base URL (without query string)
   */
  Context7DocsClient(HttpClient client, String baseUrl) {
    this.client = client;
    this.baseUrl = baseUrl;
  }

  /**
   * Fetch documentation text from Context7 for the given topic, without authentication.
   * Delegates to {@link #fetchDocs(String, int, String, String)} with a {@code null} API key.
   *
   * @param topic  the term/topic to search (required, non-blank)
   * @param tokens approximate max size of the returned docs; clamped to [500, 20000]
   * @param type   the response format; any value other than "txt" or "json" is
   *               coerced to "txt"
   * @return the raw response body returned by Context7
   * @throws IllegalArgumentException if {@code topic} is blank
   * @throws McpToolException         if the request fails or returns a non-2xx status
   */
  String fetchDocs(String topic, int tokens, String type) {
    return fetchDocs(topic, tokens, type, null);
  }

  /**
   * Fetch documentation text from Context7 for the given topic, optionally authenticated.
   * <p>
   * When {@code apiKey} is non-blank, an {@code Authorization: Bearer <apiKey>} header is
   * sent; when it is blank or {@code null} the request is made unauthenticated (lower rate
   * limit). The API key is never logged.
   *
   * @param topic  the term/topic to search (required, non-blank)
   * @param tokens approximate max size of the returned docs; clamped to [500, 20000]
   * @param type   the response format; any value other than "txt" or "json" is
   *               coerced to "txt"
   * @param apiKey optional Context7 API key; blank/null means no auth header
   * @return the raw response body returned by Context7
   * @throws IllegalArgumentException if {@code topic} is blank
   * @throws McpToolException         if the request fails or returns a non-2xx status
   */
  String fetchDocs(String topic, int tokens, String type, String apiKey) {
    if (StringUtils.isBlank(topic)) {
      throw new IllegalArgumentException("topic must not be blank");
    }
    int clampedTokens = clampTokens(tokens);
    String resolvedType = whitelistType(type);

    URI uri = buildUri(baseUrl, topic, clampedTokens, resolvedType);
    // Do not log the API key or the Authorization header.
    log.debug("Fetching Context7 docs: {} (authenticated={})", uri, StringUtils.isNotBlank(apiKey));

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "text/plain");
    if (StringUtils.isNotBlank(apiKey)) {
      requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
    }
    HttpRequest request = requestBuilder.GET().build();

    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return response.body();
      }
      throw new McpToolException(
          "Context7 request failed with status " + status + ": " + truncate(response.body()),
          null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new McpToolException("Context7 request was interrupted", e);
    } catch (IOException e) {
      throw new McpToolException("Context7 request failed: " + e.getMessage(), e);
    }
  }

  /**
   * Clamp the requested token count to the supported range.
   *
   * @param tokens the requested token count
   * @return {@code tokens} bounded to [{@link #MIN_TOKENS}, {@link #MAX_TOKENS}]
   */
  static int clampTokens(int tokens) {
    if (tokens < MIN_TOKENS) {
      return MIN_TOKENS;
    }
    return Math.min(tokens, MAX_TOKENS);
  }

  /**
   * Whitelist the response format. Only "txt" and "json" are accepted (case-insensitive);
   * any other or blank value is coerced to {@link #DEFAULT_TYPE}.
   *
   * @param type the requested response format
   * @return "txt" or "json"
   */
  static String whitelistType(String type) {
    if (StringUtils.isBlank(type)) {
      return DEFAULT_TYPE;
    }
    String normalized = type.trim().toLowerCase(java.util.Locale.ROOT);
    if (DEFAULT_TYPE.equals(normalized) || TYPE_JSON.equals(normalized)) {
      return normalized;
    }
    return DEFAULT_TYPE;
  }

  /**
   * Build the Context7 request URI. The {@code topic} is URL-encoded; the {@code type}
   * and {@code tokens} values are controlled (clamped/whitelisted) before this point.
   *
   * @param base   the base URL (without query string)
   * @param topic  the search topic (will be URL-encoded)
   * @param tokens the clamped token count
   * @param type   the response format
   * @return the fully built request URI
   */
  static URI buildUri(String base, String topic, int tokens, String type) {
    String encodedTopic = URLEncoder.encode(topic, StandardCharsets.UTF_8);
    String encodedType = URLEncoder.encode(type, StandardCharsets.UTF_8);
    String query = "topic=" + encodedTopic + "&type=" + encodedType + "&tokens=" + tokens;
    return URI.create(base + "?" + query);
  }

  private static String truncate(String body) {
    if (body == null) {
      return "";
    }
    if (body.length() <= MAX_ERROR_BODY_CHARS) {
      return body;
    }
    return body.substring(0, MAX_ERROR_BODY_CHARS) + "…";
  }
}
