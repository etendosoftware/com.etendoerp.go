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
package com.etendoerp.go.mcp;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * Turns the answer of the email contract into the tool's answer (ETP-5715).
 *
 * <p><b>Only {@code SENT} is a success.</b> The send modal treats {@code DUPLICATE} as a success
 * toast; an agent must not, because nothing was delivered and the user may be waiting for a mail
 * that never comes. Every other status is an error envelope that says so ({@code sent: false}, the
 * server's own status in {@code emailStatus}) and keeps the facts the server gave: when the
 * identical email went out, which limit was hit and when to retry.</p>
 */
final class McpDocumentEmailOutcome {

  private static final String KEY_SENT = "sent";
  private static final String KEY_REMOVED = "removedDuplicates";
  private static final String KEY_EMAIL_STATUS = "emailStatus";
  private static final String KEY_MESSAGE = "message";
  private static final String KEY_PREVIOUS = "previousSentAt";
  private static final String KEY_RETRY = "retryAfterSeconds";
  private static final String KEY_SCOPE = "throttleScope";
  private static final String KEY_REASON = "reasonCode";

  private static final String CODE_DUPLICATE = "email_duplicate";
  private static final String CODE_THROTTLED = "email_throttled";
  private static final String CODE_SUPPRESSED = "email_suppressed";
  private static final String CODE_NO_RECIPIENT = "email_no_recipient";
  private static final String CODE_PROVIDER = "email_provider_failed";

  private static final int HTTP_CONFLICT = 409;
  private static final int HTTP_UNPROCESSABLE = 422;
  private static final int HTTP_TOO_MANY = 429;
  private static final int HTTP_BAD_GATEWAY = 502;
  private static final String NOT_SENT = "Not sent: ";

  private McpDocumentEmailOutcome() {
  }

  /**
   * Maps the contract's answer.
   *
   * @param response the answer of {@code TransactionalEmailService#send}
   * @param request the validated arguments (its recipients are echoed on success)
   * @param spec the document's spec
   * @return the success body, or an error envelope that says the email was not sent
   * @throws JSONException when a body cannot be built
   */
  static JSONObject of(NeoResponse response, McpDocumentEmailRequest request, String spec)
      throws JSONException {
    JSONObject data = dataOf(response);
    String status = data == null ? null : data.optString("status", null);
    if (status == null) {
      return failed(McpConstants.STATUS_SERVER_ERROR, McpConstants.ERROR_SERVER, "UNKNOWN",
          NOT_SENT + "the email service gave an answer that could not be read.",
          "Check the document's email history in the application before trying again.");
    }
    if (TransactionalEmailService.STATUS_SENT.equals(status)) {
      return sent(request, spec);
    }
    return notSent(status, response.getHttpStatus(), data);
  }

  private static JSONObject dataOf(NeoResponse response) {
    JSONObject body = response == null ? null : response.getBody();
    JSONObject wrapper = body == null ? null : body.optJSONObject("response");
    return wrapper == null ? null : wrapper.optJSONObject("data");
  }

  private static JSONObject sent(McpDocumentEmailRequest request, String spec)
      throws JSONException {
    JSONObject body = new JSONObject();
    body.put(KEY_SENT, true);
    body.put("status", TransactionalEmailService.STATUS_SENT);
    body.put(McpConstants.PARAM_SPEC, spec);
    body.put(McpConstants.PARAM_ID, request.getId());
    body.put(McpDocumentEmailRequest.PARAM_TO, new JSONArray(request.getTo()));
    body.put(McpDocumentEmailRequest.PARAM_CC, new JSONArray(request.getCc()));
    body.put(McpConstants.KEY_DETAIL, "The email was accepted for delivery. It carries a link to "
        + "the document's PDF.");
    body.put(KEY_REMOVED, new JSONArray(request.getRemovedDuplicates()));
    body.put(McpConstants.KEY_HINT, "Tell the user it was sent to the addresses above"
        + (request.getRemovedDuplicates().isEmpty() ? "" : ", and that these addresses were left "
            + "out because they were listed twice (an address in both to and cc stays in to): "
            + String.join(", ", request.getRemovedDuplicates()))
        + ". Sending the identical email again is refused as a duplicate.");
    return body;
  }

  private static JSONObject notSent(String status, int http, JSONObject data)
      throws JSONException {
    String message = StringUtils.defaultString(data.optString(KEY_MESSAGE, null));
    switch (status) {
      case TransactionalEmailService.STATUS_DUPLICATE:
        return duplicate(data);
      case TransactionalEmailService.STATUS_THROTTLED:
        return throttled(data);
      case TransactionalEmailService.STATUS_SUPPRESSED:
        return failed(McpConstants.STATUS_FORBIDDEN, CODE_SUPPRESSED, status,
            NOT_SENT + StringUtils.defaultIfBlank(message, "a recipient is suppressed or sending "
                + "is disabled."), "Remove the suppressed recipient or ask an administrator; "
                + "retrying the same call changes nothing.");
      case TransactionalEmailService.STATUS_NO_RECIPIENT:
        return failed(HTTP_UNPROCESSABLE, CODE_NO_RECIPIENT, status,
            NOT_SENT + StringUtils.defaultIfBlank(message, "there is no recipient."),
            "Pass at least one address in 'to'.");
      case TransactionalEmailService.STATUS_UNAUTHORIZED:
        return failed(McpConstants.STATUS_FORBIDDEN, McpConstants.ERROR_FORBIDDEN, status,
            NOT_SENT + StringUtils.defaultIfBlank(message, "this role may not send the document."),
            "This role cannot send this document. Ask for write access to its window.");
      case TransactionalEmailService.STATUS_VALIDATION_FAILED:
        return validation(http, data, message);
      case TransactionalEmailService.STATUS_PROVIDER_FAILED:
        return failed(HTTP_BAD_GATEWAY, CODE_PROVIDER, status,
            NOT_SENT + StringUtils.defaultIfBlank(message, "the email provider failed."),
            "Nothing was delivered. Retry later; if it persists, report it with etendo_feedback.");
      default:
        return failed(McpConstants.STATUS_SERVER_ERROR, McpConstants.ERROR_SERVER, status,
            NOT_SENT + "the email service answered " + status + ".",
            "Check the document's email history in the application before trying again.");
    }
  }

  private static JSONObject duplicate(JSONObject data) throws JSONException {
    String previous = data.optString(KEY_PREVIOUS, null);
    String detail = NOT_SENT + "an identical email was already sent"
        + (previous == null ? "." : " at " + previous + ".");
    JSONObject body = failed(HTTP_CONFLICT, CODE_DUPLICATE, TransactionalEmailService
        .STATUS_DUPLICATE, detail, "Nothing was delivered by this call. To send a different "
            + "email change the recipients, the subject or the message.");
    if (previous != null) {
      body.put(KEY_PREVIOUS, previous);
    }
    return body;
  }

  private static JSONObject throttled(JSONObject data) throws JSONException {
    String retry = data.optString(KEY_RETRY, null);
    String scope = data.optString(KEY_SCOPE, null);
    JSONObject body = failed(HTTP_TOO_MANY, CODE_THROTTLED, TransactionalEmailService
        .STATUS_THROTTLED, NOT_SENT + "the hourly sending limit was reached"
            + (scope == null ? "" : " (" + scope + ")") + ".", "Nothing was delivered. Try again "
            + (retry == null ? "later." : "in about " + retry + " seconds."));
    if (retry != null) {
      body.put(KEY_RETRY, retry);
    }
    if (scope != null) {
      body.put(KEY_SCOPE, scope);
    }
    return body;
  }

  private static JSONObject validation(int http, JSONObject data, String message)
      throws JSONException {
    boolean missing = http == McpConstants.STATUS_NOT_FOUND;
    JSONObject body = failed(http, missing ? McpConstants.ERROR_NOT_FOUND
        : McpConstants.ERROR_VALIDATION, TransactionalEmailService.STATUS_VALIDATION_FAILED,
        NOT_SENT + StringUtils.defaultIfBlank(message, "the email was refused."),
        "Correct the call and retry.");
    String reason = data.optString(KEY_REASON, null);
    if (reason != null) {
      body.put(KEY_REASON, reason);
    }
    return body;
  }

  private static JSONObject failed(int status, String code, String emailStatus, String detail,
      String hint) throws JSONException {
    JSONObject body = McpAttachmentTools.errorEnvelope(status, code, detail, hint);
    body.put(KEY_SENT, false);
    body.put(KEY_EMAIL_STATUS, emailStatus);
    return body;
  }
}
