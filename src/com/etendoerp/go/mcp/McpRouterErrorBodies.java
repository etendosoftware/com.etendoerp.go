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

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The refusal envelopes {@link McpToolRouter} returns when a tool is reachable but the answer is
 * no.
 *
 * <p>Split out of the router under Sonar's class-size rule (S1448), and they belong together for
 * a better reason than the count: both answer a caller who did nothing malformed, and both have
 * to say whether retrying is worth anything. Getting that wrong is what turns a permanent
 * decision into a retry loop, which is the defect this module already fixed once by mapping role
 * refusals from 500 to 403.</p>
 */
final class McpRouterErrorBodies {

  private McpRouterErrorBodies() {
    throw new IllegalStateException("Utility class");
  }

  /**
   * The envelope for a tool the current role may not reach.
   *
   * <p>Answers 403 rather than 500 and says the answer will not change, so a client that retries
   * on 5xx does not loop over a permanent authorization decision.</p>
   *
   * @param toolName the tool that was refused
   * @param detail   the underlying refusal message
   * @return the error body, never {@code null} even if it could not be filled
   */
  static JSONObject forbidden(String toolName, String detail) {
    JSONObject body = new JSONObject();
    try {
      body.put(McpConstants.KEY_STATUS, McpConstants.STATUS_FORBIDDEN);
      body.put(McpConstants.KEY_ERROR, McpConstants.ERROR_FORBIDDEN);
      body.put(McpConstants.KEY_DETAIL, detail);
      body.put("tool", toolName);
      body.put(McpConstants.KEY_HINT, "Your role does not have access to this. The answer is the "
          + "same every time, so do not retry: ask for the grant, or use neo_discover to see what "
          + "this role may reach.");
    } catch (JSONException ignored) {
      // An envelope that cannot be built must not replace the refusal with a server error.
    }
    return body;
  }

  /**
   * The envelope for {@code neo_batch}, which this server does not serve.
   *
   * <p>Names the replacement and, more importantly, the one way the replacement is NOT
   * equivalent: a batch was undone as a unit and separate creates are not, so a caller that
   * retries blindly can double-create.</p>
   *
   * @return the error body
   * @throws JSONException if the body cannot be built
   */
  static JSONObject batchDisabled() throws JSONException {
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_METHOD_NOT_ALLOWED);
    error.put(McpConstants.KEY_ERROR, McpConstants.ERROR_TOOL_DISABLED);
    error.put(McpConstants.KEY_DETAIL,
        "neo_batch is disabled on this server. Create the records one at a time with neo_create "
            + "instead: create the parent first, then pass its returned id as parentId on each "
            + "child create.");
    error.put("hint",
        "These are not equivalent in one respect: a batch was applied as a unit, so a failure "
            + "undid the whole set. Separate creates are not undone \u2014 if one fails, the records "
            + "already created stay. Check what exists before retrying.");
    error.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
    return error;
  }
}
