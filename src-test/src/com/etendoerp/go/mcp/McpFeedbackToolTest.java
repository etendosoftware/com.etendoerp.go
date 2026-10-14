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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The INFO line an accepted {@code etendo_feedback} report leaves in the log: it points at the
 * {@code ETGO_MCP_USAGE} row and carries counts and tool names only — never the agent's text.
 *
 * @covers com.etendoerp.go.mcp.McpFeedbackTool
 * @covers com.etendoerp.go.mcp.McpUsageRow
 */
class McpFeedbackToolTest {

  private static final String SECRET = "Customer ACME owes 12.345 EUR";

  private static String normalizedReport() throws Exception {
    JSONObject verdict = new JSONObject()
        .put("outcome", "MIXED")
        .put("summary", SECRET)
        .put("achieved", SECRET)
        .put("frictions", new JSONArray()
            .put(new JSONObject().put("what", SECRET).put("phase", "write"))
            .put(new JSONObject().put("what", "second").put("phase", "read")))
        .put("failures", new JSONArray()
            .put(new JSONObject().put("tool", "etendo_create").put("error", SECRET)))
        .put("wastedCalls", new JSONArray()
            .put(new JSONObject().put("tool", "etendo_list"))
            .put(new JSONObject().put("tool", "I tried " + SECRET)))
        .put("suggestions", new JSONArray()
            .put(new JSONObject().put("what", SECRET)));
    return McpFeedbackVerdict.normalize(verdict);
  }

  @Test
  void rowIdIsMintedWhenTheRowIsBuilt() {
    McpUsageRow row = McpUsageRow.builder().toolName("etendo_feedback").build();
    assertTrue(row.id() != null && row.id().matches("[0-9A-F]{32}"), row.id());
    assertEquals("FIXED", McpUsageRow.builder().id("FIXED").build().id());
  }

  @Test
  void receivedLineCarriesTheRowIdCountsAndToolNamesOnly() throws Exception {
    McpUsageRow row = McpUsageRow.builder()
        .id("USAGE1")
        .sessionKey("sess-1")
        .clientId("CLIENT1")
        .clientName("claude-code")
        .toolName(McpConstants.TOOL_NEO_FEEDBACK)
        .rowType(McpUsageRow.ROW_TYPE_FEEDBACK)
        .payload(normalizedReport())
        .build();

    String line = McpFeedbackTool.receivedLogLine(row);

    assertEquals("MCP feedback received: usageId=USAGE1 session=sess-1 clientId=CLIENT1 "
        + "client=claude-code frictions=2 failures=1 wasted=2 suggestions=1 "
        + "tools=[etendo_create, etendo_list]", line);
    assertFalse(line.contains("ACME"), "no free text from the report: " + line);
  }

  /**
   * ETP-5640: modern clients send no {@code Mcp-Session-Id}. Each gets its own derived session, so
   * one client exhausting its allowance does not rate-limit every other modern client.
   */
  @Test
  void distinctDerivedSessionsDoNotShareTheRateLimit() throws Exception {
    JSONObject verdict = new JSONObject().put("outcome", "OKAY").put("summary", "ok")
        .put("achieved", "ok");
    try {
      McpUsageTelemetry.setCurrentSessionKey("m-feedback-a");
      for (int i = 0; i < McpFeedbackTool.MAX_PER_WINDOW; i++) {
        assertFalse(McpFeedbackTool.handle(verdict).optBoolean("isError", false));
      }
      assertTrue(McpFeedbackTool.handle(verdict).optBoolean("isError", false),
          "the eleventh report of one session is rate limited");

      McpUsageTelemetry.setCurrentSessionKey("m-feedback-b");
      assertFalse(McpFeedbackTool.handle(verdict).optBoolean("isError", false),
          "another modern client keeps its own allowance");
    } finally {
      McpUsageTelemetry.clearCurrentSessionKey();
    }
  }

  /**
   * W3: a modern caller's bucket is its authenticated identity, not its self-reported client name —
   * rotating {@code _meta} clientInfo.name must not mint a fresh allowance.
   */
  @Test
  void rotatingTheClientNameDoesNotResetTheBucket() throws Exception {
    JSONObject verdict = new JSONObject().put("outcome", "OKAY").put("summary", "ok")
        .put("achieved", "ok");
    for (int i = 0; i <= McpFeedbackTool.MAX_PER_WINDOW; i++) {
      JSONObject params = new JSONObject().put("_meta", new JSONObject()
          .put(McpRequestEra.META_PROTOCOL_VERSION, McpProtocolVersion.MODERN_LATEST)
          .put(McpServlet.META_CLIENT_INFO, new JSONObject().put("name", "rotating-" + i)));
      try {
        McpUsageTelemetry.bindModernCaller("u-feedback-rotate", "c1", "r1", params,
            McpRequestEra.classify("tools/call", params, new McpRequestEra.Headers(
                McpProtocolVersion.MODERN_LATEST, "tools/call", null), false));
        boolean refused = McpFeedbackTool.handle(verdict).optBoolean("isError", false);
        assertEquals(i == McpFeedbackTool.MAX_PER_WINDOW, refused, "report #" + (i + 1));
      } finally {
        McpUsageTelemetry.clearCurrentSessionKey();
        McpUsageTelemetry.clearCurrentClient();
      }
    }
  }
}
