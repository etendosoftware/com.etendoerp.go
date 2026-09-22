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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The {@code neo_feedback} tool (B3): the agent tells us, in its own words, what was confusing,
 * what it could not find, what it had to guess, and what failed.
 *
 * <h2>Why this is the highest-value row in the table</h2>
 *
 * <p>{@code ETGO_MCP_USAGE} can see <i>that</i> an agent called {@code neo_schema} five times and
 * gave up. It cannot see what the agent was <i>trying to do</i>. That intent is what turns a metric
 * into an actionable defect, and the calling agent is the only party that holds it.</p>
 *
 * <h2>The row is written by the servlet, not here</h2>
 *
 * <p>A {@code neo_feedback} call <i>is</i> a tool call, so it produces exactly ONE row (D31):
 * {@code McpServlet.recordToolCall} writes it with {@code row_type = 'feedback'} and the normalized
 * verdict in {@code Payload}, carrying the same session, tenant, timestamp and client columns as
 * every other row. That is the point of storing it here rather than in a table of its own — the
 * feedback sits in the same session sequence as the calls that provoked it, and reads as a story
 * instead of an isolated complaint.</p>
 *
 * <p>This class therefore validates and rate-limits, and returns the agent's answer. It deliberately
 * does not write anything, so there is no second writer and no shared mutable state between the
 * router and the servlet.</p>
 *
 * <h2>Rate limiting</h2>
 *
 * <p>Per MCP session, so a looping agent cannot flood the table. A client that does not echo
 * {@code Mcp-Session-Id} has no session of its own and shares one anonymous bucket — deliberately
 * the stricter reading, because an unidentified flooder is exactly the case the limit is for.</p>
 */
final class McpFeedbackTool {

  private static final Logger log = LogManager.getLogger(McpFeedbackTool.class);

  /** Accepted reports per session per window. A verdict is a per-task summary, not a stream. */
  static final int MAX_PER_WINDOW = 10;
  /** Length of the rate-limit window. */
  static final long WINDOW_MS = 60L * 60L * 1000L;
  /** Bound on tracked sessions, so the limiter cannot itself become a leak. */
  private static final int MAX_TRACKED_SESSIONS = 1_000;

  /** Bucket key used by clients that do not echo the session header. */
  private static final String ANONYMOUS_BUCKET = "__anonymous__";

  private static final Map<String, Window> WINDOWS = Collections.synchronizedMap(
      new LinkedHashMap<String, Window>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
          return size() > MAX_TRACKED_SESSIONS;
        }
      });

  private McpFeedbackTool() {
  }

  /**
   * Handle one {@code neo_feedback} call.
   *
   * @param args the submitted verdict
   * @return the MCP tool result — an acknowledgement, or an error envelope the agent can act on
   */
  static JSONObject handle(JSONObject args) {
    try {
      return dispatch(args);
    } catch (JSONException e) {
      throw new McpToolException("Error building the neo_feedback response", e);
    }
  }

  private static JSONObject dispatch(JSONObject args) throws JSONException {
    String sessionKey = StringUtils.defaultIfBlank(
        McpUsageTelemetry.currentSessionKey(), ANONYMOUS_BUCKET);

    if (!accept(sessionKey)) {
      return McpToolRouter.wrapAsErrorContent(
          errorBody("rate_limited",
              "This session has already submitted " + MAX_PER_WINDOW + " feedback reports in the "
                  + "last hour. Nothing is wrong — send one consolidated report per task rather "
                  + "than one per call.").toString(2));
    }

    try {
      // Validated here; STORED by McpServlet.recordToolCall, which owns the single row.
      McpFeedbackVerdict.normalize(args);
    } catch (McpFeedbackVerdict.InvalidVerdictException e) {
      return McpToolRouter.wrapAsErrorContent(
          errorBody(McpConstants.ERROR_VALIDATION, e.getMessage()).toString(2));
    }

    log.info("neo_feedback accepted for session {}", sessionKey);
    return McpToolRouter.wrapAsTextContent(acknowledgement());
  }

  /**
   * Normalize the verdict for storage, or return null when it is not storable.
   * <p>
   * Called by {@link McpServlet} on the row-writing path. Returns null rather than throwing because
   * a telemetry path must never raise: a rejected verdict simply produces a row with no payload,
   * and the agent has already been told why in {@link #handle}'s response.
   *
   * @param args the submitted verdict
   * @return the normalized report, or null when it did not validate
   */
  static String payloadFor(JSONObject args) {
    try {
      return McpFeedbackVerdict.normalize(args);
    } catch (Exception e) {
      log.debug("neo_feedback verdict not storable: {}", e.getMessage());
      return null;
    }
  }

  /** @return true when this session is still within its window allowance. */
  private static boolean accept(String sessionKey) {
    long now = System.currentTimeMillis();
    synchronized (WINDOWS) {
      Window window = WINDOWS.get(sessionKey);
      if (window == null || now - window.startedAt >= WINDOW_MS) {
        WINDOWS.put(sessionKey, new Window(now));
        return true;
      }
      if (window.count >= MAX_PER_WINDOW) {
        return false;
      }
      window.count++;
      return true;
    }
  }

  /** Package-private for tests: forget every tracked window. */
  static void resetRateLimiter() {
    WINDOWS.clear();
  }

  private static JSONObject acknowledgement() throws JSONException {
    JSONObject body = new JSONObject();
    body.put(McpConstants.KEY_STATUS, "ok");
    body.put("recorded", true);
    body.put(McpConstants.KEY_HINT,
        "Recorded, thank you. It is read by the people who build this API. You can send another "
            + "report whenever something else gets in your way.");
    return body;
  }

  private static JSONObject errorBody(String code, String detail) throws JSONException {
    JSONObject body = new JSONObject();
    body.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    body.put(McpConstants.KEY_ERROR, code);
    body.put(McpConstants.KEY_DETAIL, detail);
    body.put(McpConstants.KEY_TOOL, McpConstants.TOOL_NEO_FEEDBACK);
    return body;
  }

  /** One session's allowance within the current window. Guarded by the {@code WINDOWS} monitor. */
  private static final class Window {
    private final long startedAt;
    private int count;

    Window(long startedAt) {
      this.startedAt = startedAt;
      this.count = 1;
    }
  }
}
