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

package com.etendoerp.go.mcp;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoErrorClassifier;
import com.etendoerp.go.schemaforge.NeoErrorClassifier.Classification;
import com.etendoerp.go.schemaforge.NeoErrorResponses;
import com.etendoerp.go.schemaforge.util.NeoErrorSanitizer;

/**
 * The envelopes an MCP tool call comes back in.
 *
 * <p>Split out of {@link McpToolRouter} (ETP-5184, java:S1448): these build the body an agent
 * reads, and know nothing about specs, the DAL, RBAC or scopes, whereas everything left in the
 * router decides <i>what to do</i> with a tool call. The seam is that boundary, not a line count —
 * the router reads as "which handler serves this call", this class as "how the outcome is spelled".
 *
 * <p>Intended to be used via static import, so the router's call sites read exactly as they did
 * before the split.
 *
 * <p>The two content wrappers ({@code McpToolRouter.wrapAsTextContent} /
 * {@code wrapAsErrorContent}) deliberately stayed on the router: they are that class's published
 * package API — {@link McpHookExecutor} and {@link McpWidgetHandler} already call them qualified,
 * as do the router's unit tests — and moving them buys no headroom the split does not already
 * give.
 */
final class McpToolResponses {

  private static final Logger log = LogManager.getLogger(McpToolResponses.class);

  private McpToolResponses() {
  }

  /**
   * Render a routing failure, falling back to the old prose line only if the envelope cannot be
   * serialised (ETP-4793 / IMP-17).
   */
  static String buildRoutingErrorBody(McpRoutingException e, String toolName) {
    try {
      JSONObject envelope = e.toEnvelope();
      envelope.put(McpConstants.KEY_TOOL, toolName);
      // B3: the moment an agent is stuck is the moment its feedback is worth most.
      envelope.put(McpConstants.KEY_FEEDBACK, McpConstants.FEEDBACK_INVITATION);
      return McpResponseSanitizer.serialize(envelope);
    } catch (JSONException jsonEx) {
      log.error("Could not build routing error envelope for '{}'", toolName, jsonEx);
      return "Error executing " + toolName + ": " + e.getMessage();
    }
  }

  /**
   * Render anything else thrown out of a tool call as the IMP-5 envelope (ETP-4793 / IMP-17).
   *
   * <p>This is the last leak IMP-5 left open: every unanticipated failure came back as the bare line
   * {@code "Error executing etendo_list: …"} (evidence C14), so an agent could not tell a mistake it
   * could fix from a server fault it could not, and had to parse prose to find out. The code is
   * deliberately {@code server_error} rather than {@code validation_error}: if the router could have
   * told the caller what to change, one of its typed paths would already have done it, and
   * inviting a retry-with-corrections here would send the agent round a loop that cannot terminate.
   * The message is sanitised on the way out — an unexpected failure is exactly where a DB internal
   * or a row dump would otherwise reach the client.</p>
   */
  static String buildUnexpectedErrorBody(String toolName, Exception e) {
    try {
      // ETP-5782: a failure the agent can act on — a trigger or an AD-message-keyed refusal, an
      // unknown reference, a row still in use, a deadlock — is classified by what the exception
      // is, not answered as a server fault. The session is rolled back first, so nothing the call
      // wrote before throwing is committed and the message is translated on a usable session.
      Classification c = NeoErrorClassifier.classify(e, null);
      NeoErrorResponses.rollbackUnlessBatchOwned();
      if (!c.isServerSide() || c.status() == HTTP_SERVICE_UNAVAILABLE) {
        log.warn("MCP tool '{}' refused: {} {} ({})", toolName, c.status(), c.code(),
            e.getClass().getSimpleName());
        JSONObject envelope = classifiedEnvelope(c.status(), c.code(),
            NeoErrorClassifier.displayMessage(c), c.messageKeys());
        envelope.put(McpConstants.KEY_TOOL, toolName);
        return McpResponseSanitizer.serialize(envelope);
      }
      JSONObject envelope = new JSONObject();
      envelope.put(McpConstants.KEY_STATUS, McpConstants.STATUS_SERVER_ERROR);
      envelope.put(McpConstants.KEY_ERROR, McpConstants.ERROR_SERVER);
      envelope.put(McpConstants.KEY_DETAIL, NeoErrorSanitizer.sanitize(e));
      envelope.put(McpConstants.KEY_TOOL, toolName);
      envelope.put(McpConstants.KEY_HINT, "This is a server-side failure, not a bad request — "
          + "re-sending the same call with corrected values will not help.");
      // B3: a server fault the agent cannot fix is exactly what we want reported.
      envelope.put(McpConstants.KEY_FEEDBACK, McpConstants.FEEDBACK_INVITATION);
      return McpResponseSanitizer.serialize(envelope);
    } catch (JSONException jsonEx) {
      log.error("Could not build error envelope for '{}'", toolName, jsonEx);
      return "Error executing " + toolName + ": " + e.getMessage();
    }
  }

  private static final int HTTP_SERVICE_UNAVAILABLE = 503;

  /**
   * The IMP-5 envelope for a failure {@link NeoErrorClassifier} classified (ETP-5782), shared by
   * the router's catch-all and by the DAL failure path ({@code McpWriteRequestSupport}).
   *
   * <p>REST's 400s ({@code unknown_reference}, {@code missing_required_value}) are answered as the
   * MCP surface's {@code 422 validation_error}, as every other rejected value already is on this
   * channel; the class itself travels in {@code code}. Declared in {@code neo-headless.md}
   * §4.12.9.
   *
   * @param restStatus  the status REST answers for the class
   * @param code        the class, {@code NeoErrorClassifier.CODE_*}
   * @param detail      the display message
   * @param messageKeys the AD message keys of the failure; may be empty
   * @return the envelope (no {@code tool}, no {@code seeAlso}: the caller adds what applies)
   * @throws JSONException if the envelope cannot be built
   */
  static JSONObject classifiedEnvelope(int restStatus, String code, String detail,
      List<String> messageKeys) throws JSONException {
    int status = restStatus == 400 ? McpConstants.STATUS_UNPROCESSABLE : restStatus;
    JSONObject envelope = new JSONObject();
    envelope.put(McpConstants.KEY_STATUS, status);
    envelope.put(McpConstants.KEY_ERROR, errorFor(status));
    envelope.put("code", code);
    envelope.put(McpConstants.KEY_DETAIL, detail);
    if (messageKeys != null && !messageKeys.isEmpty()) {
      envelope.put("messageKeys", new JSONArray(messageKeys));
    }
    envelope.put(McpConstants.KEY_HINT, hintFor(code));
    return envelope;
  }

  private static String errorFor(int status) {
    switch (status) {
      case McpConstants.STATUS_FORBIDDEN:
        return McpConstants.ERROR_FORBIDDEN;
      case McpConstants.STATUS_CONFLICT:
        return McpConstants.ERROR_CONFLICT;
      case McpConstants.STATUS_UNPROCESSABLE:
        return McpConstants.ERROR_VALIDATION;
      default:
        return McpConstants.ERROR_SERVER;
    }
  }

  private static String hintFor(String code) {
    switch (code) {
      case NeoErrorClassifier.CODE_BUSINESS_RULE:
        return "A business rule rejected the request. Read 'detail' (the rule's own message), "
            + "change what it names and retry — nothing was written.";
      case NeoErrorClassifier.CODE_UNKNOWN_REFERENCE:
        return "A referenced id does not exist or is not accessible. Look the record up with "
            + "etendo_list and send an id from the result.";
      case NeoErrorClassifier.CODE_RECORD_IN_USE:
        return "Other records still reference this one. Delete or reassign them first; retrying "
            + "unchanged will fail the same way.";
      case NeoErrorClassifier.CODE_INVALID_VALUE:
        return "A value was rejected by a database check. Call etendo_schema with view:\"create\" "
            + "for the field types and allowed values, correct the value and retry.";
      case NeoErrorClassifier.CODE_MISSING_REQUIRED_VALUE:
        return "A required value is missing. Call etendo_schema with view:\"create\" to see "
            + "the mandatory fields, send them and retry.";
      case NeoErrorClassifier.CODE_RETRYABLE_CONFLICT:
        return "Another operation was changing the same data at the same time. Retry the same "
            + "call unchanged.";
      case NeoErrorClassifier.CODE_SERVICE_UNAVAILABLE:
        return "The service is temporarily unavailable. Retry the same call later.";
      case NeoErrorClassifier.CODE_FORBIDDEN:
        return "The current role is not allowed to do this; retrying will not change that.";
      case NeoErrorClassifier.CODE_DUPLICATE_KEY:
        return "A record with this business key already exists. Find it with etendo_list and "
            + "update it, or send a different key.";
      default:
        return "Read 'detail' for what failed.";
    }
  }

  /**
   * ETP-5184: an image tool reports a rejection through the same error-content channel every other
   * MCP write uses, so an agent detects the failure the same way regardless of which tool produced
   * it. The envelope itself already carries {@code status}/{@code error}/{@code hint}.
   */
  static JSONObject imageToolResult(JSONObject body) throws JSONException {
    boolean failed = body.has(McpConstants.KEY_ERROR);
    return failed ? McpToolRouter.wrapAsErrorContent(body)
        : McpToolRouter.wrapAsTextContent(body);
  }

  /**
   * The single {@code etendo_delete} success answer, {@code {"deleted": true, "id": recordId}}.
   * Shared by the generic removal path ({@link McpToolRouter#handleDelete}) and
   * {@link McpHookExecutor#runDeletePreHook} (a handler resolving the DELETE with 204 No Content)
   * so the two cannot diverge (ETP-5474).
   *
   * @param recordId the id of the deleted record
   * @return the MCP text-content result carrying the confirmation
   */
  static JSONObject deleteConfirmation(String recordId) throws JSONException {
    JSONObject deleteResult = new JSONObject();
    deleteResult.put("deleted", true);
    deleteResult.put("id", recordId);
    return McpToolRouter.wrapAsTextContent(deleteResult);
  }
}
