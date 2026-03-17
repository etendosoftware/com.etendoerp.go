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
