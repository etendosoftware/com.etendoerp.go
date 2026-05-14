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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ProtocolErrorAdapters}.
 */
class ProtocolErrorAdaptersTest {

  // -------------------------------------------------------------------------
  // buildJsonRpcError
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("buildJsonRpcError")
  class BuildJsonRpcError {

    @Test
    @DisplayName("Builds valid JSON-RPC 2.0 error envelope")
    void buildsValidEnvelope() throws Exception {
      JSONObject result = ProtocolErrorAdapters.buildJsonRpcError("req-1", -32600, "Invalid Request");

      assertEquals("2.0", result.getString("jsonrpc"));
      assertEquals("req-1", result.getString("id"));

      JSONObject error = result.getJSONObject("error");
      assertEquals(-32600, error.getInt("code"));
      assertEquals("Invalid Request", error.getString("message"));
    }

    @Test
    @DisplayName("Uses JSONObject.NULL for null id")
    void nullIdUsesJsonNull() throws Exception {
      JSONObject result = ProtocolErrorAdapters.buildJsonRpcError(null, -32700, "Parse error");

      assertTrue(result.isNull("id"));
    }

    @Test
    @DisplayName("Uses 'Internal error' for null message")
    void nullMessageFallback() throws Exception {
      JSONObject result = ProtocolErrorAdapters.buildJsonRpcError("1", -32603, null);

      JSONObject error = result.getJSONObject("error");
      assertEquals("Internal error", error.getString("message"));
    }

    @Test
    @DisplayName("Supports integer id")
    void integerIdSupported() throws Exception {
      JSONObject result = ProtocolErrorAdapters.buildJsonRpcError(42, -32600, "Bad");

      assertEquals(42, result.getInt("id"));
    }
  }

  // -------------------------------------------------------------------------
  // writeSimpleJsonError
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("writeSimpleJsonError")
  class WriteSimpleJsonError {

    @Test
    @DisplayName("Writes correct status, content type, and body")
    void writesCorrectResponse() throws Exception {
      HttpServletResponse response = mock(HttpServletResponse.class);
      StringWriter sw = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(sw));

      ProtocolErrorAdapters.writeSimpleJsonError(response, 400, "Bad request");

      verify(response).setStatus(400);
      verify(response).setContentType("application/json;charset=UTF-8");

      String body = sw.toString();
      assertTrue(body.contains("\"error\""));
      assertTrue(body.contains("Bad request"));
    }
  }

  // -------------------------------------------------------------------------
  // writeRestError
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("writeRestError")
  class WriteRestError {

    @Test
    @DisplayName("Writes REST error with custom field names")
    void writesRestError() throws Exception {
      HttpServletResponse response = mock(HttpServletResponse.class);
      StringWriter sw = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(sw));

      ProtocolErrorAdapters.writeRestError(response, 404, "Not found",
          "message", "status", "error");

      verify(response).setStatus(404);
      verify(response).setContentType("application/json");

      String body = sw.toString();
      JSONObject parsed = new JSONObject(body);
      JSONObject error = parsed.getJSONObject("error");
      assertEquals("Not found", error.getString("message"));
      assertEquals(404, error.getInt("status"));
    }
  }

  // -------------------------------------------------------------------------
  // writeOAuthError
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("writeOAuthError")
  class WriteOAuthError {

    @Test
    @DisplayName("Writes OAuth2 error with error and error_description fields")
    void writesOAuthError() throws Exception {
      HttpServletResponse response = mock(HttpServletResponse.class);
      StringWriter sw = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(sw));

      ProtocolErrorAdapters.writeOAuthError(response, 401,
          "invalid_token", "Token has expired");

      verify(response).setStatus(401);
      verify(response).setContentType("application/json");

      String body = sw.toString();
      JSONObject parsed = new JSONObject(body);
      assertEquals("invalid_token", parsed.getString("error"));
      assertEquals("Token has expired", parsed.getString("error_description"));
    }
  }

  // -------------------------------------------------------------------------
  // escapeJson
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("escapeJson")
  class EscapeJson {

    @Test
    @DisplayName("Returns empty string for null input")
    void nullReturnsEmpty() {
      assertEquals("", ProtocolErrorAdapters.escapeJson(null));
    }

    @Test
    @DisplayName("Escapes backslashes")
    void escapesBackslash() {
      assertEquals("a\\\\b", ProtocolErrorAdapters.escapeJson("a\\b"));
    }

    @Test
    @DisplayName("Escapes double quotes")
    void escapesDoubleQuotes() {
      assertEquals("say \\\"hello\\\"", ProtocolErrorAdapters.escapeJson("say \"hello\""));
    }

    @Test
    @DisplayName("Escapes newlines and carriage returns")
    void escapesNewlines() {
      String result = ProtocolErrorAdapters.escapeJson("line1\nline2\rline3");
      assertEquals("line1\\nline2\\rline3", result);
    }

    @Test
    @DisplayName("Returns unchanged string without special characters")
    void noSpecialChars() {
      assertEquals("hello world", ProtocolErrorAdapters.escapeJson("hello world"));
    }

    @Test
    @DisplayName("Handles combined special characters")
    void combinedEscapes() {
      String input = "He said \"hi\"\npath: C:\\temp";
      String expected = "He said \\\"hi\\\"\\npath: C:\\\\temp";
      assertEquals(expected, ProtocolErrorAdapters.escapeJson(input));
    }
  }
}