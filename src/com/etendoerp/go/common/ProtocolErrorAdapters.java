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

  public static void writeSimpleJsonError(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write("{\"error\":\"" + escapeJson(message) + "\"}");
  }

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
