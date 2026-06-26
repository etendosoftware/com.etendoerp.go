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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link Context7DocsClient} — covers URI building/encoding,
 * token clamping, and the {@code fetchDocs} HTTP flow with a mocked
 * {@link HttpClient} so no real network call is made.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Context7DocsClientTest {

  private static final String BASE = "https://example.test/api/v1/lib";

  @Mock
  private HttpClient mockHttpClient;

  @Mock
  private HttpResponse<String> mockResponse;

  // ── buildUri ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("buildUri")
  class BuildUriTests {

    @Test
    @DisplayName("URL-encodes the topic")
    void encodesTopic() {
      String topic = "a b/c";
      String expectedTopic = URLEncoder.encode(topic, StandardCharsets.UTF_8);

      URI uri = Context7DocsClient.buildUri(BASE, topic, 5000, "txt");

      assertTrue(uri.toString().contains("topic=" + expectedTopic),
          "URI should contain the URL-encoded topic, was: " + uri);
    }

    @Test
    @DisplayName("URL-encodes the type")
    void encodesType() {
      String type = "json type";
      String expectedType = URLEncoder.encode(type, StandardCharsets.UTF_8);

      URI uri = Context7DocsClient.buildUri(BASE, "finance", 5000, type);

      assertTrue(uri.toString().contains("type=" + expectedType),
          "URI should contain the URL-encoded type, was: " + uri);
    }

    @Test
    @DisplayName("includes tokens and type in the query string")
    void includesTokensAndType() {
      URI uri = Context7DocsClient.buildUri(BASE, "finance", 1234, "txt");

      String s = uri.toString();
      assertTrue(s.contains("tokens=1234"), "URI should contain tokens, was: " + s);
      assertTrue(s.contains("type=txt"), "URI should contain type, was: " + s);
    }

    @Test
    @DisplayName("respects the provided base URL")
    void respectsBaseUrl() {
      URI uri = Context7DocsClient.buildUri(BASE, "finance", 5000, "txt");

      assertTrue(uri.toString().startsWith(BASE + "?"),
          "URI should start with the base URL, was: " + uri);
    }
  }

  // ── clampTokens ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("clampTokens")
  class ClampTokensTests {

    @Test
    @DisplayName("below the minimum clamps up to MIN_TOKENS")
    void belowMinClampsUp() {
      assertEquals(Context7DocsClient.MIN_TOKENS, Context7DocsClient.clampTokens(499));
    }

    @Test
    @DisplayName("exactly the minimum is unchanged")
    void exactlyMinUnchanged() {
      assertEquals(500, Context7DocsClient.clampTokens(500));
    }

    @Test
    @DisplayName("a value inside the range is unchanged")
    void insideRangeUnchanged() {
      assertEquals(5000, Context7DocsClient.clampTokens(5000));
    }

    @Test
    @DisplayName("exactly the maximum is unchanged")
    void exactlyMaxUnchanged() {
      assertEquals(20000, Context7DocsClient.clampTokens(20000));
    }

    @Test
    @DisplayName("above the maximum clamps down to MAX_TOKENS")
    void aboveMaxClampsDown() {
      assertEquals(Context7DocsClient.MAX_TOKENS, Context7DocsClient.clampTokens(20001));
    }
  }

  // ── whitelistType ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("whitelistType")
  class WhitelistTypeTests {

    @Test
    @DisplayName("an unsupported value coerces to txt")
    void unsupportedCoercesToTxt() {
      assertEquals("txt", Context7DocsClient.whitelistType("xml"));
    }

    @Test
    @DisplayName("json (any case) is normalized to json")
    void jsonNormalized() {
      assertEquals("json", Context7DocsClient.whitelistType("JSON"));
    }

    @Test
    @DisplayName("txt is preserved")
    void txtPreserved() {
      assertEquals("txt", Context7DocsClient.whitelistType("txt"));
    }

    @Test
    @DisplayName("blank coerces to txt")
    void blankCoercesToTxt() {
      assertEquals("txt", Context7DocsClient.whitelistType("   "));
    }

    @Test
    @DisplayName("null coerces to txt")
    void nullCoercesToTxt() {
      assertEquals("txt", Context7DocsClient.whitelistType(null));
    }
  }

  // ── fetchDocs — Authorization header ───────────────────────────────────────

  @Nested
  @DisplayName("fetchDocs — Authorization header")
  class AuthHeaderTests {

    private HttpRequest captureRequestAfterFetch(String apiKey) throws Exception {
      when(mockResponse.statusCode()).thenReturn(200);
      when(mockResponse.body()).thenReturn("docs body");
      when(mockHttpClient.send(any(HttpRequest.class),
          org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
          .thenReturn(mockResponse);

      Context7DocsClient client = new Context7DocsClient(mockHttpClient, BASE);
      if (apiKey == AbsentKey.SENTINEL) {
        client.fetchDocs("finance", 5000, "txt");
      } else {
        client.fetchDocs("finance", 5000, "txt", apiKey);
      }

      ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
      verify(mockHttpClient).send(captor.capture(),
          org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
      return captor.getValue();
    }

    @Test
    @DisplayName("a non-blank API key adds a Bearer Authorization header")
    void apiKeyAddsBearerHeader() throws Exception {
      HttpRequest request = captureRequestAfterFetch("secret-token");

      assertTrue(request.headers().firstValue("Authorization").isPresent(),
          "Authorization header should be present");
      assertEquals("Bearer secret-token",
          request.headers().firstValue("Authorization").orElse(null));
    }

    @Test
    @DisplayName("the API key is trimmed before building the Bearer header")
    void apiKeyTrimmed() throws Exception {
      HttpRequest request = captureRequestAfterFetch("  secret-token  ");

      assertEquals("Bearer secret-token",
          request.headers().firstValue("Authorization").orElse(null));
    }

    @Test
    @DisplayName("a blank API key adds no Authorization header")
    void blankApiKeyNoHeader() throws Exception {
      HttpRequest request = captureRequestAfterFetch("");

      assertFalse(request.headers().firstValue("Authorization").isPresent(),
          "Authorization header should be absent for a blank key");
    }

    @Test
    @DisplayName("a null API key adds no Authorization header")
    void nullApiKeyNoHeader() throws Exception {
      HttpRequest request = captureRequestAfterFetch((String) null);

      assertFalse(request.headers().firstValue("Authorization").isPresent(),
          "Authorization header should be absent for a null key");
    }

    @Test
    @DisplayName("the 3-arg fetchDocs delegates without an Authorization header")
    void threeArgDelegatesWithoutAuth() throws Exception {
      HttpRequest request = captureRequestAfterFetch(AbsentKey.SENTINEL);

      assertFalse(request.headers().firstValue("Authorization").isPresent(),
          "3-arg overload should not send an Authorization header");
    }
  }

  /** Sentinel to distinguish "call the 3-arg overload" from "pass a null key". */
  private static final class AbsentKey {
    static final String SENTINEL = new String("__ABSENT__");

    private AbsentKey() {
    }
  }

  // ── fetchDocs ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("fetchDocs")
  class FetchDocsTests {

    @Test
    @DisplayName("returns the response body on 200")
    void returnsBodyOn200() throws Exception {
      when(mockResponse.statusCode()).thenReturn(200);
      when(mockResponse.body()).thenReturn("the docs body");
      when(mockHttpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(mockResponse);

      Context7DocsClient client = new Context7DocsClient(mockHttpClient, BASE);
      String body = client.fetchDocs("finance", 5000, "txt");

      assertEquals("the docs body", body);
    }

    @Test
    @DisplayName("returns the body for any 2xx status")
    void returnsBodyOn2xx() throws Exception {
      when(mockResponse.statusCode()).thenReturn(204);
      when(mockResponse.body()).thenReturn("no content body");
      when(mockHttpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(mockResponse);

      Context7DocsClient client = new Context7DocsClient(mockHttpClient, BASE);
      String body = client.fetchDocs("finance", 5000, "txt");

      assertEquals("no content body", body);
    }

    @Test
    @DisplayName("blank topic throws IllegalArgumentException")
    void blankTopicThrows() {
      Context7DocsClient client = new Context7DocsClient(mockHttpClient, BASE);

      assertThrows(IllegalArgumentException.class, () -> client.fetchDocs("   ", 5000, "txt"));
    }

    @Test
    @DisplayName("null topic throws IllegalArgumentException")
    void nullTopicThrows() {
      Context7DocsClient client = new Context7DocsClient(mockHttpClient, BASE);

      assertThrows(IllegalArgumentException.class, () -> client.fetchDocs(null, 5000, "txt"));
    }

    @Test
    @DisplayName("404 status throws McpToolException with the status code in the message")
    void notFoundThrows() throws Exception {
      when(mockResponse.statusCode()).thenReturn(404);
      when(mockResponse.body()).thenReturn("not found");
      when(mockHttpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(mockResponse);

      Context7DocsClient client = new Context7DocsClient(mockHttpClient, BASE);
      McpToolException ex = assertThrows(McpToolException.class,
          () -> client.fetchDocs("finance", 5000, "txt"));
      assertTrue(ex.getMessage().contains("404"),
          "Message should contain the status code, was: " + ex.getMessage());
    }

    @Test
    @DisplayName("429 status throws McpToolException with the status code in the message")
    void tooManyRequestsThrows() throws Exception {
      when(mockResponse.statusCode()).thenReturn(429);
      when(mockResponse.body()).thenReturn("rate limited");
      when(mockHttpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(mockResponse);

      Context7DocsClient client = new Context7DocsClient(mockHttpClient, BASE);
      McpToolException ex = assertThrows(McpToolException.class,
          () -> client.fetchDocs("finance", 5000, "txt"));
      assertTrue(ex.getMessage().contains("429"),
          "Message should contain the status code, was: " + ex.getMessage());
    }

    @Test
    @DisplayName("500 status throws McpToolException with the status code in the message")
    void serverErrorThrows() throws Exception {
      when(mockResponse.statusCode()).thenReturn(500);
      when(mockResponse.body()).thenReturn("server error");
      when(mockHttpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(mockResponse);

      Context7DocsClient client = new Context7DocsClient(mockHttpClient, BASE);
      McpToolException ex = assertThrows(McpToolException.class,
          () -> client.fetchDocs("finance", 5000, "txt"));
      assertTrue(ex.getMessage().contains("500"),
          "Message should contain the status code, was: " + ex.getMessage());
    }

    @Test
    @DisplayName("IOException from the client is wrapped as McpToolException")
    void ioExceptionWrapped() throws Exception {
      when(mockHttpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new java.io.IOException("connection refused"));

      Context7DocsClient client = new Context7DocsClient(mockHttpClient, BASE);
      McpToolException ex = assertThrows(McpToolException.class,
          () -> client.fetchDocs("finance", 5000, "txt"));
      assertTrue(ex.getMessage().contains("connection refused"),
          "Message should contain the underlying cause, was: " + ex.getMessage());
    }
  }
}
