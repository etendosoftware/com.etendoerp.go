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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

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
      return envelope.toString(2);
    } catch (JSONException jsonEx) {
      log.error("Could not build routing error envelope for '{}'", toolName, jsonEx);
      return "Error executing " + toolName + ": " + e.getMessage();
    }
  }

  /**
   * Render anything else thrown out of a tool call as the IMP-5 envelope (ETP-4793 / IMP-17).
   *
   * <p>This is the last leak IMP-5 left open: every unanticipated failure came back as the bare line
   * {@code "Error executing neo_list: …"} (evidence C14), so an agent could not tell a mistake it
   * could fix from a server fault it could not, and had to parse prose to find out. The code is
   * deliberately {@code server_error} rather than {@code validation_error}: if the router could have
   * told the caller what to change, one of its typed paths would already have done it, and
   * inviting a retry-with-corrections here would send the agent round a loop that cannot terminate.
   * The message is sanitised on the way out — an unexpected failure is exactly where a DB internal
   * or a row dump would otherwise reach the client.</p>
   */
  static String buildUnexpectedErrorBody(String toolName, Exception e) {
    try {
      JSONObject envelope = new JSONObject();
      envelope.put(McpConstants.KEY_STATUS, McpConstants.STATUS_SERVER_ERROR);
      envelope.put(McpConstants.KEY_ERROR, McpConstants.ERROR_SERVER);
      envelope.put(McpConstants.KEY_DETAIL, NeoErrorSanitizer.sanitize(e));
      envelope.put(McpConstants.KEY_TOOL, toolName);
      envelope.put(McpConstants.KEY_HINT, "This is a server-side failure, not a bad request — "
          + "re-sending the same call with corrected values will not help.");
      return envelope.toString(2);
    } catch (JSONException jsonEx) {
      log.error("Could not build error envelope for '{}'", toolName, jsonEx);
      return "Error executing " + toolName + ": " + e.getMessage();
    }
  }

  /**
   * ETP-5184: an image tool reports a rejection through the same error-content channel every other
   * MCP write uses, so an agent detects the failure the same way regardless of which tool produced
   * it. The envelope itself already carries {@code status}/{@code error}/{@code hint}.
   */
  static JSONObject imageToolResult(JSONObject body) throws JSONException {
    boolean failed = body.has(McpConstants.KEY_ERROR);
    return failed ? McpToolRouter.wrapAsErrorContent(body.toString(2))
        : McpToolRouter.wrapAsTextContent(body.toString(2));
  }
}
