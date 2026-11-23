package com.etendoerp.go.schemaforge;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;

/**
 * Reads the pieces of a classified NEO error response that a batch failure envelope reports.
 * Split out of {@link BatchService} to keep that class cohesive and within its size budget.
 */
final class BatchRefusals {

  private BatchRefusals() {
  }

  /**
   * The body of a classified refusal, as the failure's {@code detail} so its {@code code} and
   * {@code messageKeys} reach the caller; {@code null} for a 5xx, whose failure carried no detail.
   */
  static JSONObject detailOf(NeoResponse classified) {
    return classified.getHttpStatus() < HttpServletResponse.SC_INTERNAL_SERVER_ERROR
        ? classified.getBody()
        : null;
  }

  /** The {@code error.message} of a NEO error response, or {@code null}. */
  static String messageOf(NeoResponse response) {
    JSONObject body = response.getBody();
    JSONObject error = body != null ? body.optJSONObject(BatchService.KEY_ERROR) : null;
    return error != null ? error.optString("message", null) : null;
  }
}
