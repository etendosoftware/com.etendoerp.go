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

package com.etendoerp.go.schemaforge;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Wrapper for NEO Headless API responses.
 */
public class NeoResponse {

  private static final String MESSAGE_FIELD = "message";

  private int httpStatus;
  private JSONObject body;
  private Map<String, String> headers;

  /**
   * Creates a NeoResponse with the given HTTP status and JSON body.
   *
   * @param httpStatus the HTTP status code (e.g. 200, 404)
   * @param body       the JSON response body, or {@code null} for empty responses
   */
  public NeoResponse(int httpStatus, JSONObject body) {
    this.httpStatus = httpStatus;
    this.body = body;
    this.headers = new HashMap<>();
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public JSONObject getBody() {
    return body;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  /**
   * Adds a response header and returns this response for chaining.
   *
   * @param name  the header name
   * @param value the header value
   * @return this NeoResponse
   */
  public NeoResponse withHeader(String name, String value) {
    this.headers.put(name, value);
    return this;
  }

  /**
   * Creates a 200 OK response with the given data payload.
   *
   * @param data the JSON response data
   * @return a NeoResponse with HTTP 200
   */
  public static NeoResponse ok(JSONObject data) {
    return new NeoResponse(200, data);
  }

  /**
   * Creates a 201 Created response with the given data payload.
   *
   * @param data the JSON representation of the created resource
   * @return a NeoResponse with HTTP 201
   */
  public static NeoResponse created(JSONObject data) {
    return new NeoResponse(201, data);
  }

  /**
   * Creates a 201 Created response wrapping {@code data} in the standard
   * {@code {"response":{"data": ...}}} envelope used by NEO action handlers.
   *
   * @param data the JSON payload to wrap
   * @return a NeoResponse with HTTP 201
   * @throws JSONException if building the envelope fails
   */
  public static NeoResponse createdWithData(JSONObject data) throws JSONException {
    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);
    return new NeoResponse(201, wrapper);
  }

  /**
   * Creates a 204 No Content response with no body.
   *
   * @return a NeoResponse with HTTP 204 and null body
   */
  public static NeoResponse noContent() {
    return new NeoResponse(204, null);
  }

  /**
   * Creates an error response with the given status code and plain-text message.
   *
   * @param status  the HTTP status code (e.g. 400, 404, 500)
   * @param message the human-readable error message
   * @return a NeoResponse with a JSON error body containing the status and message
   */
  public static NeoResponse error(int status, String message) {
    try {
      JSONObject errorBody = new JSONObject();
      JSONObject errorObj = new JSONObject();
      errorObj.put(MESSAGE_FIELD, message);
      errorObj.put("status", status);
      errorBody.put("error", errorObj);
      return new NeoResponse(status, errorBody);
    } catch (JSONException e) {
      return new NeoResponse(status, null);
    }
  }

  /**
   * Creates an error response with a full JSON body (e.g. validation error details).
   *
   * @param status the HTTP status code
   * @param body   the pre-built JSON error body
   * @return a NeoResponse with the given status and JSON body
   */
  public static NeoResponse error(int status, JSONObject body) {
    return new NeoResponse(status, body);
  }

  /**
   * Ensures every error response carries a top-level {@code message} field.
   *
   * <p>Different code paths that build error bodies in this package disagree on shape.
   * {@link #error(int, String)} and structured guard-clause/precondition responses (e.g.
   * missing mandatory parameters, access-denied, {@code PRECONDITIONS_UNMET}) nest the
   * human-readable text under {@code error.message}. But a process that runs and then
   * reports a business-rule rejection (a classic DB-procedure DocAction like
   * {@code C_Order_Post}, or an OBUIAPP handler result) builds a <em>flat</em> body with
   * a top-level {@code message} directly — see {@code NeoProcessService}'s
   * {@code translatePInstanceResult}/{@code translateObuiappResult}/{@code translateClassicResult}.
   *
   * <p>Frontend document-confirmation flows (e.g. {@code OrderConfirmModal.jsx}) read
   * {@code response.message} / {@code message} off the parsed error body and do not
   * uniformly know about the nested {@code error.message} shape. Without this
   * normalization, any guard-clause or precondition rejection silently degrades to a
   * generic "Process failed (400)" in the UI instead of showing the real cause — even
   * though the real cause was already computed server-side.
   *
   * <p>Idempotent and safe to apply at multiple layers: a body that already has a
   * top-level {@code message}, or has neither {@code message} nor a nested
   * {@code error.message}, is returned unchanged.
   *
   * @param response the response to normalize (may be {@code null})
   * @return the same response, with a top-level {@code message} copied from a nested
   *         {@code error.message} when the top-level key was missing; unchanged otherwise
   */
  public static NeoResponse ensureTopLevelMessage(NeoResponse response) {
    if (response == null || response.getHttpStatus() < 400 || response.getBody() == null) {
      return response;
    }
    JSONObject body = response.getBody();
    if (body.has(MESSAGE_FIELD)) {
      return response;
    }
    JSONObject error = body.optJSONObject("error");
    if (error == null) {
      return response;
    }
    try {
      body.put(MESSAGE_FIELD, error.optString(MESSAGE_FIELD, "Request failed"));
    } catch (JSONException ignored) {
      // Best-effort normalization: fall back to the original (unnormalized) body.
    }
    return response;
  }
}
