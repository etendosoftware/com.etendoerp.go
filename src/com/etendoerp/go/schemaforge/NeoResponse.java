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

  private int httpStatus;
  private JSONObject body;
  private Map<String, String> headers;

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

  public NeoResponse withHeader(String name, String value) {
    this.headers.put(name, value);
    return this;
  }

  public static NeoResponse ok(JSONObject data) {
    return new NeoResponse(200, data);
  }

  public static NeoResponse created(JSONObject data) {
    return new NeoResponse(201, data);
  }

  public static NeoResponse noContent() {
    return new NeoResponse(204, null);
  }

  public static NeoResponse error(int status, String message) {
    try {
      JSONObject errorBody = new JSONObject();
      JSONObject errorObj = new JSONObject();
      errorObj.put("message", message);
      errorObj.put("status", status);
      errorBody.put("error", errorObj);
      return new NeoResponse(status, errorBody);
    } catch (JSONException e) {
      return new NeoResponse(status, null);
    }
  }

  /**
   * Create an error response with a full JSON body (e.g. validation error details).
   */
  public static NeoResponse error(int status, JSONObject body) {
    return new NeoResponse(status, body);
  }
}
