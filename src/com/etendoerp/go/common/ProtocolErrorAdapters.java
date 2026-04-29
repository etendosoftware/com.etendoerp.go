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

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Shared protocol-specific error envelopes used by Etendo Go servlets.
 */
public final class ProtocolErrorAdapters {

  private ProtocolErrorAdapters() {
  }

  /**
   * Build a JSON-RPC 2.0 error envelope for MCP responses.
   *
   * @param id JSON-RPC request id, or {@code null} for unknown ids
   * @param code JSON-RPC error code
   * @param message human-readable error message
   * @return JSON-RPC error object with {@code jsonrpc}, {@code id}, and {@code error} fields
   * @throws JSONException when the JSON error envelope cannot be constructed
   */
  public static JSONObject buildJsonRpcError(Object id, int code, String message) throws JSONException {
    JSONObject error = new JSONObject();
    error.put("code", code);
    error.put("message", message != null ? message : "Internal error");

    JSONObject resp = new JSONObject();
    resp.put("jsonrpc", "2.0");
    resp.put("id", id != null ? id : JSONObject.NULL);
    resp.put("error", error);
    return resp;
  }

  /**
   * Write a minimal JSON error response of the form {@code {"error":"..."}}.
   *
   * @param response HTTP response to write to
   * @param status HTTP status code
   * @param message error message to serialize
   * @throws IOException when the response writer cannot be written
   */
  public static void writeSimpleJsonError(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write("{\"error\":\"" + escapeJson(message) + "\"}");
  }

  /**
   * Write the REST error envelope used by Etendo Go account endpoints.
   *
   * @param response HTTP response to write to
   * @param status HTTP status code
   * @param message error message body
   * @param messageField JSON field name for the message value
   * @param statusField JSON field name for the numeric status value
   * @param wrapperField top-level wrapper field name
   * @throws IOException when the response writer cannot be written
   */
  public static void writeRestError(HttpServletResponse response, int status, String message,
      String messageField, String statusField, String wrapperField) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    try {
      JSONObject error = new JSONObject();
      error.put(messageField, message);
      error.put(statusField, status);

      JSONObject wrapper = new JSONObject();
      wrapper.put(wrapperField, error);
      response.getWriter().write(wrapper.toString());
    } catch (JSONException e) {
      response.getWriter().write("{\"error\":{\"" + messageField + "\":\""
          + escapeJson(message) + "\",\"" + statusField + "\":" + status + "}}");
    }
  }

  /**
   * Write an OAuth2 RFC 6749 style error response.
   *
   * @param response HTTP response to write to
   * @param status HTTP status code
   * @param error OAuth2 error code
   * @param description OAuth2 error description
   * @throws IOException when the response cannot be written
   */
  public static void writeOAuthError(HttpServletResponse response, int status, String error,
      String description) throws IOException {
    try {
      JSONObject body = new JSONObject();
      body.put("error", error);
      body.put("error_description", description);
      response.setStatus(status);
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      response.getWriter().write(body.toString());
    } catch (JSONException e) {
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * Escape a string for safe inclusion in small inline JSON fallback payloads.
   *
   * @param value raw text to escape
   * @return escaped JSON-safe text, or an empty string when {@code value} is null
   */
  public static String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}
