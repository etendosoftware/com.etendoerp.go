/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * A completed answer from the payment provider: the HTTP status and the raw body, plus the two
 * things every caller in this module ends up asking of a rejection — the provider's own error
 * code and its message.
 *
 * <p>Reaching a caller at all means the call completed. A transport failure is a
 * {@link StripeTransportException} and never arrives here, which is what lets a 4xx be read as a
 * verdict about the request rather than as "the provider might be down".
 *
 * <p>The body is parsed lazily and at most once: an error body is usually JSON but is not
 * guaranteed to be (a proxy or a WAF in front of the provider answers HTML), so parsing eagerly
 * in the constructor would turn a readable 502-from-a-proxy into a parse failure with no status
 * in it. An unparseable body yields an empty object rather than an exception, so
 * {@link #errorCode()} and {@link #errorMessage()} degrade to null instead of blowing up the
 * caller's own error handling.
 */
public final class StripeResponse {

  private static final Logger log = LogManager.getLogger(StripeResponse.class);

  private final int status;
  private final String body;

  private JSONObject parsed;
  private boolean parseAttempted;

  /**
   * Creates a completed provider answer.
   *
   * @param status HTTP status code the provider answered with
   * @param body raw response body, may be null or empty
   */
  public StripeResponse(int status, String body) {
    this.status = status;
    this.body = body == null ? "" : body;
  }

  /**
   * Returns the HTTP status code.
   *
   * @return HTTP status code of the completed call
   */
  public int status() {
    return status;
  }

  /**
   * Returns the raw response body.
   *
   * @return response body, never null
   */
  public String body() {
    return body;
  }

  /**
   * Returns whether the provider accepted the request.
   *
   * @return true when the status is in the 2xx range
   */
  public boolean isSuccess() {
    return status / 100 == 2;
  }

  /**
   * Returns the parsed body, parsing it on first use.
   *
   * @return the parsed body, or an empty object when the body is not valid JSON
   */
  public JSONObject json() {
    if (!parseAttempted) {
      parseAttempted = true;
      parsed = parseBody();
    }
    return parsed;
  }

  private JSONObject parseBody() {
    if (body.isEmpty()) {
      return new JSONObject();
    }
    try {
      return new JSONObject(body);
    } catch (JSONException e) {
      // Not an error worth failing on: the status is the useful part, and a non-JSON body means
      // something in front of the provider answered, not the provider itself.
      log.debug("Provider response body is not valid JSON (status {})", status, e);
      return new JSONObject();
    }
  }

  /**
   * Returns the provider error code from the standard error envelope.
   *
   * @return the {@code error.code} value, or null when absent
   */
  public String errorCode() {
    return errorField("code");
  }

  /**
   * Returns the provider error message from the standard error envelope.
   *
   * @return the {@code error.message} value, or null when absent
   */
  public String errorMessage() {
    return errorField("message");
  }

  private String errorField(String field) {
    JSONObject error = json().optJSONObject("error");
    if (error == null) {
      return null;
    }
    String value = error.optString(field, null);
    return value == null || value.isEmpty() ? null : value;
  }
}
